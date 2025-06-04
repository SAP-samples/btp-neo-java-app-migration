#!/bin/bash
set -ex

CURRENT_SCRIPT_DIR="$(realpath $(dirname ${0}))"
TEST_SCRIPTS_DIR="${CURRENT_SCRIPT_DIR}/../../../pipelines/scripts"
source ${TEST_SCRIPTS_DIR}/commons.sh

DESTINATION_BODY_TEMPLATE='{
    "Type": "HTTP",
    "Name": "${DEST_NAME}",
    "ProxyType": "${PROXY_TYPE}",
    "URL": "${DEST_URL}",
    "Authentication": "NoAuthentication"
}'

function export_internet_destination_env() {
    export DEST_NAME="outbound-internet-destination"
    export DEST_URL="https://github.com/"
    export PROXY_TYPE="Internet"
}

function export_on_premise_destination_env() {
    export DEST_NAME="on-premise-destination"
    export DEST_URL="http://${VIRTUAL_HOST}:${VIRTUAL_PORT}/backend-app/noauth"
    export PROXY_TYPE="OnPremise"
    DESTINATION_BODY_TEMPLATE=$(echo "${DESTINATION_BODY_TEMPLATE}" | jq ". += {"CloudConnectorLocationId" : \"\${CC_LOCATION_ID}\" }")
}

cleanup() {
    cleanup_docker_image ${SCC_DOCKER_NAME}
    cleanup_docker_image ${BACKEND_APP_DOCKER_NAME}
    cleanup_docker_network
}

function run_on_premise_environment() {
    export CC_LOCATION_ID="${APP_NAME}"
    export NETWORK_NAME="onpremise-network"
    export SCC_DOCKER_NAME="scc"
    export BACKEND_APP_DOCKER_NAME="backend-app"
    export VIRTUAL_HOST="onpremise"
    export VIRTUAL_PORT="80"
    export LOCAL_HOST="${BACKEND_APP_DOCKER_NAME}"
    export LOCAL_PORT="8080"
    export PROTOCOL="HTTP"
    cleanup

    echo "Create network"
    docker network create ${NETWORK_NAME}

    ${TEST_SCRIPTS_DIR}/onpremise/run-backend-app.sh
    ${TEST_SCRIPTS_DIR}/onpremise/run-scc.sh
}

function create_destination() {
    destination_body=$(echo "${DESTINATION_BODY_TEMPLATE}" | envsubst)
    ${TEST_SCRIPTS_DIR}/create-destination.sh "${APP_NAME}" "${destination_body}"
    cf restart ${APP_NAME}
}

function execute_test() {
    echo "Starting Maven test execution"

    mvn clean install \
        -f "${CURRENT_SCRIPT_DIR}" \
        -Pintegration-tests \
        -Dapp.url="${APP_URL}?destname=${DEST_NAME}"

    echo "Maven test execution completed"
}

function main() {
    if [[ -n "${DEBUG}" && "${DEBUG}" == "true" ]]; then
        set -x
    fi

    validate_env APP_URL APP_NAME

    export_internet_destination_env
    create_destination
    execute_test

    run_on_premise_environment
    export_on_premise_destination_env
    create_destination
    execute_test
}

main "${@}"