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

# Regression test: Verify TomEE is using HANA, not HSQLDB
# If the HSQLDB folder exists, it means TomEE is misconfigured
function check_no_hsqldb() {
    echo "Checking that TomEE is NOT using HSQLDB..."
    local result
    result=$(cf ssh "${APP_NAME}" -c "ls -la /home/vcap/app/META-INF/.sap_java_buildpack/tomee/data/hsqldb/ 2>/dev/null || echo 'No HSQLDB folder!'")
    
    if [[ "${result}" == *"No HSQLDB folder!"* ]]; then
        echo "SUCCESS: No HSQLDB folder - TomEE is correctly using HANA"
    else
        echo "ERROR: HSQLDB folder found! TomEE is using HSQLDB instead of HANA."
        echo "Check that WEB-INF/resources.xml exists and JBP_CONFIG_RESOURCE_CONFIGURATION"
        echo "points to tomee/webapps/ROOT/WEB-INF/resources.xml"
        echo "Folder contents: ${result}"
        return 1
    fi
}

function main(){
    if [[ -n "${DEBUG}" && "${DEBUG}" == "true" ]]; then
        set -x
    fi

    validate_env APP_URL APP_NAME

    execute_tests
    check_no_hsqldb
}

main "${@}"