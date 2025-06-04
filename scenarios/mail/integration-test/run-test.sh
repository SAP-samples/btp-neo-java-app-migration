#!/bin/bash
set -e

CURRENT_SCRIPT_DIR="$(realpath $(dirname ${0}))"
TEST_SCRIPTS_DIR="${CURRENT_SCRIPT_DIR}/../../../pipelines/scripts"
source ${TEST_SCRIPTS_DIR}/commons.sh

DESTINATION_BODY_TEMPLATE='{
    "Type": "MAIL",
    "Name": "Session",
    "ProxyType": "${PROXY_TYPE}",
    "mail.user": "${MAIL_USER}",
    "mail.password": "${MAIL_PASSWORD}",
    "mail.transport.protocol": "smtp",
    "mail.smtp.host": "${SMTP_HOST}",
    "mail.smtp.port": "${SMTP_PORT}",
    "mail.smtp.auth": "true",
    "mail.smtp.starttls.enable": "true"
}'

function export_internet_destination_env() {
    export PROXY_TYPE="Internet"
    export SMTP_HOST="${INTERNET_SMTP_HOST}"
    export SMTP_PORT="${INTERNET_SMTP_PORT}"
    export MAIL_USER="${MAIL_USER}"
    export MAIL_PASSWORD="${MAIL_PASSWORD}"
}

function export_on_premise_destination_env() {
    export PROXY_TYPE="OnPremise"
    export SMTP_HOST="${VIRTUAL_HOST}"
    export SMTP_PORT="${VIRTUAL_PORT}"
    export MAIL_USER=""
    export MAIL_PASSWORD=""
    DESTINATION_BODY_TEMPLATE=$(echo "${DESTINATION_BODY_TEMPLATE}" | jq ". += {"CloudConnectorLocationId" : \"\${CC_LOCATION_ID}\" }")
    DESTINATION_BODY_TEMPLATE=$(echo "${DESTINATION_BODY_TEMPLATE}" | jq ". += {"Authentication" : \"NoAuthentication\" }")
}

function run_on_premise_environment() {
    export CC_LOCATION_ID="${APP_NAME}"
    export NETWORK_NAME="onpremise_network"
    export SCC_DOCKER_NAME="scc"
    export MAILHOG_DOCKER_NAME="mailhog"
    export VIRTUAL_HOST="mail"
    export VIRTUAL_PORT="25"
    export LOCAL_HOST="${MAILHOG_DOCKER_NAME}"
    export LOCAL_PORT="1025"
    export PROTOCOL="TCP"
    cleanup

    echo "Create network"
    docker network create ${NETWORK_NAME}

    ${TEST_SCRIPTS_DIR}/onpremise/run-mailhog.sh
    ${TEST_SCRIPTS_DIR}/onpremise/run-scc.sh
}

function create_destination() {
    destination_body=$(echo "${DESTINATION_BODY_TEMPLATE}" | envsubst)
    ${TEST_SCRIPTS_DIR}/create-destination.sh "${APP_NAME}" "${destination_body}"
    cf restart ${APP_NAME}
}

function execute_tests() {
    echo "Starting Maven test execution"

    mvn clean install \
        -f "${CURRENT_SCRIPT_DIR}" \
        -Pintegration-tests \
        -Dapp.url="${APP_URL}" \
        -Dmail.from.address="${MAIL_ADDRESS_USER}" \
        -Dmail.to.address="${MAIL_ADDRESS_USER}"

    echo "Maven test execution completed"
}

function print_test_logs() {
    echo "Printing mail-test logs"
    cat "${CURRENT_SCRIPT_DIR}"/target/surefire-reports/*.txt

    echo "Printing scc logs"
    docker logs ${SCC_DOCKER_NAME}

    echo "Printing mailhog logs"
    docker logs ${MAILHOG_DOCKER_NAME}
}

cleanup() {
    cleanup_docker_image ${SCC_DOCKER_NAME}
    cleanup_docker_image ${MAILHOG_DOCKER_NAME}
    cleanup_docker_network
}

function main() {
    if [[ -n "${DEBUG}" && "${DEBUG}" == "true" ]]; then
        set -x
    fi

    validate_env APP_URL APP_NAME MAIL_ADDRESS_USER MAIL_ADDRESS_PASSWORD INTERNET_SMTP_HOST INTERNET_SMTP_PORT MAIL_USER MAIL_PASSWORD

    trap 'if [ $? -ne 0 ]; then print_test_logs; fi;' EXIT ERR

    export_internet_destination_env
    create_destination
    execute_tests

    run_on_premise_environment
    export_on_premise_destination_env
    create_destination
    execute_tests
}

main "$@"