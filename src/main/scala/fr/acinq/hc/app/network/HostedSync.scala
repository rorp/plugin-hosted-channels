package fr.acinq.hc.app.network

import fr.acinq.hc.app._
import fr.acinq.hc.app.HC._
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.hc.app.network.HostedSync._

import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, UnknownMessage}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Kit}
import fr.acinq.hc.app.db.{Blocking, HostedUpdatesDb}
import fr.acinq.eclair.router.{Router, SyncProgress}
import scala.util.{Failure, Random, Success, Try}

import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.io.UnknownMessageReceived
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Nothing
import fr.acinq.hc.app.wire.Codecs
import scala.collection.mutable
import scala.concurrent.Future
import scodec.Attempt


object HostedSync {
  case object GetHostedSyncData

  case object SyncFromPHCPeers { val label = "SyncFromPHCPeers" }

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  case object RefreshRouterData { val label = "RefreshRouterData" }

  case object TickSendGossip { val label = "TickSendGossip" }

  case class GotAllSyncFrom(info: PeerConnectedWrap)

  case class SendSyncTo(info: PeerConnectedWrap)
}

class HostedSync(kit: Kit, updatesDb: HostedUpdatesDb, phcConfig: PHCConfig) extends FSMDiagnosticActorLogging[HostedSyncState, HostedSyncData] {

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty withDefaultValue 0

  context.system.scheduler.scheduleWithFixedDelay(10.minutes, PHC.tickStaggeredBroadcastThreshold, self, TickSendGossip)

  context.system.eventStream.subscribe(channel = classOf[SyncProgress], subscriber = self)

  startWith(stateData = WaitForNormalNetworkData(updatesDb.getState), stateName = WAIT_FOR_ROUTER_DATA)

  private val syncProcessor = new AnnouncementMessageProcessor {
    override val tagsOfInterest: Set[Int] = Set(PHC_ANNOUNCE_SYNC_TAG, PHC_UPDATE_SYNC_TAG)
    override def processNewAnnounce(announce: ChannelAnnouncement, data: OperationalData, seenFrom: PublicKey): OperationalData =
      data.copy(phcNetwork = data.phcNetwork addNewAnnounce announce)

    override def processKnownAnnounce(announce: ChannelAnnouncement, data: OperationalData, seenFrom: PublicKey): OperationalData =
      data.copy(phcNetwork = data.phcNetwork addUpdatedAnnounce announce)

    override def processUpdate(update: ChannelUpdate, data: OperationalData, seenFrom: PublicKey): OperationalData =
      data.copy(phcNetwork = data.phcNetwork addUpdate update)
  }

  private val gossipProcessor = new AnnouncementMessageProcessor {
    override val tagsOfInterest: Set[Int] = Set(PHC_ANNOUNCE_GOSSIP_TAG, PHC_UPDATE_GOSSIP_TAG)
    override def processNewAnnounce(announce: ChannelAnnouncement, data: OperationalData, seenFrom: PublicKey): OperationalData =
      data.copy(phcGossip = data.phcGossip.addAnnounce(announce, seenFrom), phcNetwork = data.phcNetwork addNewAnnounce announce)

    override def processKnownAnnounce(announce: ChannelAnnouncement, data: OperationalData, seenFrom: PublicKey): OperationalData =
      data.copy(phcGossip = data.phcGossip.addAnnounce(announce, seenFrom), phcNetwork = data.phcNetwork addUpdatedAnnounce announce)

    override def processUpdate(update: ChannelUpdate, data: OperationalData, seenFrom: PublicKey): OperationalData =
      data.copy(phcGossip = data.phcGossip.addUpdate(update, seenFrom), phcNetwork = data.phcNetwork addUpdate update)
  }

  when(WAIT_FOR_ROUTER_DATA) {
    case Event(SyncProgress(1D), _) =>
      kit.router ! Router.GetRouterData
      stay

    case Event(routerData: fr.acinq.eclair.router.Router.Data, data: WaitForNormalNetworkData) =>
      val data1 = OperationalData(data.phcNetwork, CollectedGossip(Map.empty), None, routerData.channels, routerData.graph)
      log.info("PLGN PHC, HostedSync, got normal network data")
      goto(WAIT_FOR_PHC_SYNC) using data1
  }

  when(WAIT_FOR_PHC_SYNC) {
    case Event(Nothing, _) =>
      stay
  }

  when(DOING_PHC_SYNC, stateTimeout = 10.minutes) {
    case Event(StateTimeout, data: OperationalData) =>
      log.info("PLGN PHC, HostedSync, sync timeout, rescheduling sync")
      goto(WAIT_FOR_PHC_SYNC) using WaitForNormalNetworkData(data.phcNetwork)

    case Event(msg: UnknownMessageReceived, data: OperationalData) if syncProcessor.tagsOfInterest.contains(msg.message.tag) =>
      stay using syncProcessor.process(msg.nodeId, msg.message, data)

    case Event(GotAllSyncFrom(wrap), data: OperationalData) if data.lastSyncNodeId.contains(wrap.info.nodeId) =>
      tryPersist(data.phcNetwork).map { adds =>
        // Note: nodes whose NC count falls below minimum will eventually be pruned out
        val u1Count = updatesDb.pruneOldUpdates1(System.currentTimeMillis.millis.toSeconds)
        val u2Count = updatesDb.pruneOldUpdates2(System.currentTimeMillis.millis.toSeconds)
        val annCount = updatesDb.pruneUpdateLessAnnounces
        val phcNetwork1 = updatesDb.getState

        // In case of success we prune database and recreate network from scratch
        log.info(s"PLGN PHC, HostedSync, added=${adds.sum}, removed u1=$u1Count, removed u2=$u2Count, removed ann=$annCount")
        log.info(s"PLGN PHC, HostedSync, chans old=${data.phcNetwork.channels.size}, new=${phcNetwork1.channels.size}")
        goto(OPERATIONAL) using data.copy(phcNetwork = phcNetwork1)
      }.recover { err =>
        log.info(s"PLGN PHC, HostedSync, db fail=${err.getMessage}")
        // Note that in case of db failure announces are retained, this is fine
        val phcNetwork1 = data.phcNetwork.copy(unsaved = PHCNetwork.emptyUnsaved)
        goto(WAIT_FOR_PHC_SYNC) using WaitForNormalNetworkData(phcNetwork1)
      }.get
  }

  when(OPERATIONAL) {
    case Event(Nothing, _) =>
      stay
  }

  whenUnhandled {
    case Event(TickClearIpAntiSpam, _) =>
      ipAntiSpam.clear
      stay

    case Event(RefreshRouterData, _: OperationalData) =>
      kit.router ! Router.GetRouterData
      stay

    case Event(routerData: Router.Data, data: OperationalData) =>
      val data1 = data.copy(normalChannels = routerData.channels, normalGraph = routerData.graph)
      log.info("PLGN PHC, HostedSync, updated normal network data from Router")
      stay using data1

    // SEND OUT GOSSIP AND SYNC

    case Event(TickSendGossip, data: OperationalData) =>
      val phcNetwork1 = data.phcNetwork.copy(unsaved = PHCNetwork.emptyUnsaved)
      val data1 = data.copy(phcGossip = CollectedGossip(Map.empty), phcNetwork = phcNetwork1)

      Future {
        val currentPublicPeers = randomPublicPeers(data)
        val allUpdates = data.phcGossip.updates1.values ++ data.phcGossip.updates2.values
        log.info(s"PLGN PHC, TickSendGossip, sending to peers num=${currentPublicPeers.size}")

        for {
          (_, wrap) <- data.phcGossip.announces
          publicPeerConnectedWrap <- currentPublicPeers
          if !wrap.seenFrom.contains(publicPeerConnectedWrap.info.nodeId)
        } publicPeerConnectedWrap sendRoutingMsg wrap.announcement

        for {
          wrap <- allUpdates
          publicPeerConnectedWrap <- currentPublicPeers
          if !wrap.seenFrom.contains(publicPeerConnectedWrap.info.nodeId)
        } publicPeerConnectedWrap sendRoutingMsg wrap.update
      } onComplete {
        case Failure(err) => log.info(s"PLGN PHC, TickSendGossip, fail, error=${err.getMessage}")
        case _ => log.info(s"PLGN PHC, TickSendGossip, success, ${data.phcGossip.asString}")
      }

      tryPersistLog(data.phcNetwork)
      stay using data1

    case Event(SendSyncTo(wrap), data: OperationalData) =>
      if (ipAntiSpam(wrap.remoteIp) > phcConfig.maxSyncSendsPerIpPerMinute) {
        wrap sendHostedChannelMsg ReplyPublicHostedChannelsEnd(kit.nodeParams.chainHash)
        log.info(s"PLGN PHC, SendSyncTo, abuse, peer=${wrap.info.nodeId}")
      } else Future {
        data.phcNetwork.channels.values.flatMap(_.orderedMessages).foreach(wrap.sendUnknownMsg)
        wrap sendHostedChannelMsg ReplyPublicHostedChannelsEnd(kit.nodeParams.chainHash)
      } onComplete {
        case Failure(err) => log.info(s"PLGN PHC, SendSyncTo, fail, peer=${wrap.info.nodeId} error=${err.getMessage}")
        case _ => log.info(s"PLGN PHC, SendSyncTo, success, peer=${wrap.info.nodeId}")
      }

      // Record this request for anti-spam
      ipAntiSpam(wrap.remoteIp) += 1
      stay

    // PERIODIC SYNC

    case Event(SyncFromPHCPeers, data: OperationalData) =>
      randomPublicPeers(data).headOption match {
        case Some(publicPeerConnectedWrap) =>
          val lastSyncNodeIdOpt = Some(publicPeerConnectedWrap.info.nodeId)
          log.info(s"PLGN PHC, HostedSync, with peer=${publicPeerConnectedWrap.info.nodeId}")
          publicPeerConnectedWrap sendHostedChannelMsg QueryPublicHostedChannels(kit.nodeParams.chainHash)
          goto(DOING_PHC_SYNC) using data.copy(lastSyncNodeId = lastSyncNodeIdOpt)

        case None =>
          // A frequent ask timer has been scheduled in transition
          log.info("PLGN PHC, HostedSync, no PHC peers, waiting")
          goto(WAIT_FOR_PHC_SYNC)
      }

    // LISTENING TO GOSSIP

    case Event(msg: UnknownMessageReceived, data: OperationalData) if gossipProcessor.tagsOfInterest.contains(msg.message.tag) =>
      stay using gossipProcessor.process(msg.nodeId, msg.message, data)

    case Event(msg: UnknownMessage, data: OperationalData) if gossipProcessor.tagsOfInterest.contains(msg.tag) =>
      log.info(s"PLGN PHC, got locally originated gossip message=${msg.getClass.getSimpleName}")
      val data1 = gossipProcessor.process(kit.nodeParams.nodeId, msg, data)
      tryPersistLog(data1.phcNetwork)
      stay using data1

    case Event(GetHostedSyncData, data: OperationalData) =>
      stay replying data
  }

  onTransition {
    case WAIT_FOR_ROUTER_DATA -> WAIT_FOR_PHC_SYNC =>
      context.system.eventStream.unsubscribe(channel = classOf[SyncProgress], subscriber = self)
      // We always ask for full sync on startup, keep asking frequently if no PHC peer is connected yet
      startTimerWithFixedDelay(TickClearIpAntiSpam.label, TickClearIpAntiSpam, 1.minute)
      startTimerWithFixedDelay(RefreshRouterData.label, RefreshRouterData, 10.minutes)
      self ! SyncFromPHCPeers

    case _ -> WAIT_FOR_PHC_SYNC =>
      // PHC sync has been taking too long, try again with hopefully another PHC peer
      startTimerWithFixedDelay(SyncFromPHCPeers.label, SyncFromPHCPeers, 2.minutes)

    case WAIT_FOR_PHC_SYNC -> _ =>
      // Once PHC sync is started we schedule a less frequent periodic re-sync (timer is restarted)
      startTimerWithFixedDelay(SyncFromPHCPeers.label, SyncFromPHCPeers, PHC.tickRequestFullSyncThreshold)
  }

  initialize()

  private def randomPublicPeers(data: OperationalData): Seq[PeerConnectedWrap] = Random shuffle {
    HC.remoteNode2Connection.values.toList.filter(wrap => data.normalGraph.getIncomingEdgesOf(wrap.info.nodeId).nonEmpty)
  }

  private def isUpdateAcceptable(update: ChannelUpdate, data: OperationalData): Boolean =
    data.phcNetwork.channels.get(update.shortChannelId) match {
      case Some(phc) if data.tooFewNormalChans(phc.channelAnnounce.nodeId1, phc.channelAnnounce.nodeId2, phcConfig).isDefined =>
        log.info(s"PLGN PHC, gossip update fail: too few normal channels, msg=$update")
        false

      case _ if update.htlcMaximumMsat.forall(_ > phcConfig.maxCapacity) =>
        log.info(s"PLGN PHC, gossip update fail: capacity is above max, msg=$update")
        false

      case _ if update.htlcMaximumMsat.forall(_ < phcConfig.minCapacity) =>
        log.info(s"PLGN PHC, gossip update fail: capacity is below min, msg=$update")
        false

      case Some(phc) if !phc.isUpdateFresh(update) =>
        log.info(s"PLGN PHC, gossip update fail: not fresh, msg=$update")
        false

      case Some(phc) if !phc.verifySig(update) =>
        log.info(s"PLGN PHC, gossip update fail: wrong signature, msg=$update")
        false

      case None => false
      case _ => true
    }

  private def tryPersist(phcNetwork: PHCNetwork) = Try {
    Blocking.txWrite(DBIO.sequence(phcNetwork.unsaved.orderedMessages.map {
      case acceptableMessage: ChannelAnnouncement => updatesDb.addAnnounce(acceptableMessage)
      case acceptableMessage: ChannelUpdate => updatesDb.addUpdate(acceptableMessage)
      case message => throw new RuntimeException(message.getClass.getSimpleName)
    }.toSeq), updatesDb.db)
  }

  private def tryPersistLog(phcNetwork: PHCNetwork): Unit = tryPersist(phcNetwork) match {
    case Failure(error) => log.info(s"PLGN PHC, TickSendGossip, db fail=${error.getMessage}")
    case Success(adds) => log.info(s"PLGN PHC, TickSendGossip, db adds=${adds.sum}")
  }

  abstract class AnnouncementMessageProcessor {
    def processNewAnnounce(announce: ChannelAnnouncement, data: OperationalData, seenFrom: PublicKey): OperationalData
    def processKnownAnnounce(announce: ChannelAnnouncement, data: OperationalData, seenFrom: PublicKey): OperationalData
    def processUpdate(update: ChannelUpdate, data: OperationalData, seenFrom: PublicKey): OperationalData
    val tagsOfInterest: Set[Int]

    def baseCheck(ann: ChannelAnnouncement, data: OperationalData): Boolean =
      data.tooFewNormalChans(ann.nodeId1, ann.nodeId2, phcConfig).isEmpty &&
        data.phcNetwork.isAnnounceAcceptable(ann)

    def process(fromNodeId: PublicKey, receivedMessage: UnknownMessage, data: OperationalData): OperationalData = Codecs.decodeAnnounceMessage(receivedMessage) match {
      case Attempt.Successful(msg: ChannelAnnouncement) if baseCheck(msg, data) && data.phcNetwork.channels.contains(msg.shortChannelId) =>
        // This is an update of an already existing PHC because it's contained in channels map
        processKnownAnnounce(msg, data, fromNodeId)

      case Attempt.Successful(msg: ChannelAnnouncement) if baseCheck(msg, data) && data.phcNetwork.tooManyPHCs(msg.nodeId1, msg.nodeId2, phcConfig).isEmpty =>
        // This is a new PHC so we must check if any of related nodes already has too many PHCs before proceeding
        processNewAnnounce(msg, data, fromNodeId)

      case Attempt.Successful(msg: ChannelUpdate) if isUpdateAcceptable(msg, data) =>
        processUpdate(msg, data, fromNodeId)

      case Attempt.Successful(something) =>
        log.info(s"PLGN PHC, HostedSync, unacceptable message=$something, peer=$fromNodeId")
        data

      case _: Attempt.Failure =>
        log.info(s"PLGN PHC, HostedSync, parsing fail, peer=$fromNodeId")
        data
    }
  }
}