#!/bin/bash

# default script values, can be overridden for convenience.
api_url='http://localhost:8090'
# uncomment the line below if you don't want to provide a password each time you call phc-cli
# api_password='your_api_password'
# for some commands the json output can be shortened for better readability
short=false

# prints help message
usage() {
  echo -e "==============================
Command line client for PHC
==============================

This tool requires the eclair node's API to be enabled and listening
on <$api_url>.

Usage
-----
\x1b[93mphc-cli\x1b[39m [\x1b[93mOPTIONS\x1b[39m]... <\x1b[93mCOMMAND\x1b[39m> [--command-param=command-value]...

where OPTIONS can be:
  -p <password>         API's password
  -a <address>          Override the API URL with <address>
  -h                    Show this help
  -s                    Some commands can print a trimmed JSON

and COMMAND is one of the available commands:

  - invoke                  --nodeId --refundAddress --secret
  - externalfulfill         --nodeId --htlcId --paymentPreimage
  - findbyremoteid          --nodeId
  - findbysecret            --plainUserSecret
  - overridepropose         --nodeId --newLocalBalanceMsat
  - overrideaccept          --nodeId
  - makepublic              --nodeId
  - makeprivate             --nodeId
  - resize                  --nodeId --newCapacitySat
  - suspend                 --nodeId
  - hide                    --nodeId
  - verifyremotestate       --state
  - restorefromremotestate  --state
  - broadcastpreimages      --preimages --feerateSatByte
  - phcnodes" 1>&2;
exit 1;
}

# -- script's logic begins here

# Check if jq is installed. If not, display instructions and abort program
command -v jq >/dev/null 2>&1 || { echo -e "This tool requires jq.\nFor installation instructions, visit https://stedolan.github.io/jq/download/.\n\nAborting..."; exit 1; }

# curl installed? If not, give a hint
command -v curl >/dev/null 2>&1 || { echo -e "This tool requires curl.\n\nAborting..."; exit 1; }

# extract script options
while getopts ':cu:su:p:a:hu:' flag; do
  case "${flag}" in
    p) api_password="${OPTARG}" ;;
    a) api_url="${OPTARG}" ;;
    h) usage ;;
    s) short=true ;;
    *) ;;
  esac
done
shift $(($OPTIND - 1))

# extract api's endpoint (e.g. sendpayment, connect, ...) from params
api_endpoint=${1}
shift 1

# display a usage method if no method given or help requested
if [ -z $api_endpoint ] || [ "$api_endpoint" == "help" ]; then
  usage;
fi

# long options are expected to be of format: --param=param_value
api_payload=""
for arg in "${@}"; do
  case ${arg} in
    "--"*)  api_payload="$api_payload --data-urlencode \"${arg:2}\"";
            ;;
    *)      echo "incorrect argument, please use --arg=value"; usage;
            ;;
  esac
done;

# jq filter parses response body for error message
jq_filter='if type=="object" and .error != null then .error else .';

# apply special jq filter if we are in "short" ouput mode -- only for specific commands such as 'channels'
if [ "$short" = true ]; then
  jq_channel_filter="{ nodeId, shortChannelId: .data.shortChannelId, channelId, state, balanceSat: (try (.data.commitments.localCommit.spec.toLocal / 1000 | floor) catch null), capacitySat: .data.commitments.commitInput.amountSatoshis, channelPoint: .data.commitments.commitInput.outPoint }";
  case $api_endpoint in
    "channels")   jq_filter="$jq_filter | map( $jq_channel_filter )" ;;
    "channel")    jq_filter="$jq_filter | $jq_channel_filter" ;;
    *) ;;
  esac
fi

jq_filter="$jq_filter end";

# if no password is provided, auth should only contain user login so that curl prompts for the api password
if [ -z $api_password ]; then
  auth="phc-cli";
else
  auth="phc-cli:$api_password";
fi

# we're now ready to execute the API call
eval curl "--user $auth --silent --show-error -X POST -H \"Content-Type: application/x-www-form-urlencoded\" $api_payload $api_url/$api_endpoint" | jq -r "$jq_filter"
