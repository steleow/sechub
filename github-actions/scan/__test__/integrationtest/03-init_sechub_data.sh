#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -e

cd $(dirname "$0")

SECHUB_SERVER_PORT=$1
PDS_PORT=$2 # currently not really used, reserved for later access

echo "[START] setup integration test scenario for github action 'scan' "
echo "        precondition: SecHub server mut be started locally"

echo "----- Handle settings"
if [[ "$SECHUB_SERVER_PORT" == "" ]]; then
   SECHUB_SERVER_PORT=8443
   echo "SECHUB_SERVER_PORT was not defined - use fallback: $SECHUB_SERVER_PORT"
fi
if [[ "$PDS_PORT" == "" ]]; then
   PDS_PORT=8444
   echo "PDS_PORT was not defined - use fallback: $PDS_PORT"
fi
if [[ "$SECHUB_SERVER" == "" ]]; then
    SECHUB_SERVER="https://localhost:$SECHUB_SERVER_PORT"
   echo "SECHUB_SERVER was not defined - use fallback: $SECHUB_SERVER"
fi
if [[ "$SECHUB_USERID" == "" ]]; then
   SECHUB_USERID="int-test_superadmin"
   echo "SECHUB_USERID was not defined - use fallback: $SECHUB_USERID"
fi
if [[ "$SECHUB_APITOKEN" == "" ]]; then
   SECHUB_APITOKEN="int-test_superadmin-pwd"
   echo "SECHUB_APITOKEN was not defined - use fallback"
fi

## export for calls
export SECHUB_SERVER_PORT
export PDS_PORT
export SECHUB_SERVER
export SECHUB_USERID
export SECHUB_APITOKEN


echo "----- Prepare sechub api script usage"
cd ../../../..
# at root level now

cd sechub-developertools/scripts
pwd

# we use the given sechub user (integration test admin) as user itself
# means we need no user creation,signup etc.
user=$SECHUB_USERID
project="test-project"

echo "----- Check server alive"
./sechub-api.sh alive_check

echo "----- Create test project:$project for user:$user"
./sechub-api.sh project_create $project $user "Testproject for integration tests"
./sechub-api.sh project_assign_user $project $user # assign user to project



# wait a short time - to give SecHub chance to handle events etc.
sleep 2s
# now the setup shall be done and effective
