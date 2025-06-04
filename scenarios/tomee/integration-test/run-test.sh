#!/bin/bash
set -e

CURRENT_SCRIPT_DIR="$(realpath $(dirname ${0}))"
source ${CURRENT_SCRIPT_DIR}/../../../pipelines/scripts/commons.sh

function execute_tests() {
    echo "Starting Maven test execution"

    mvn clean install \
        -f "${CURRENT_SCRIPT_DIR}" \
        -Pintegration-tests \
        -Dapp.url="${APP_URL}"

    echo "Maven test execution completed"
}

function main(){
    if [[ -n "${DEBUG}" && "${DEBUG}" == "true" ]]; then
        set -x
    fi

    validate_env APP_URL

    execute_tests
}

main "${@}"