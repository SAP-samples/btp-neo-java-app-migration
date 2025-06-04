
# Mail Sample
The sample provides a simple web UI to compose an e-mail message and send it.

This test is part of the Neo SDK test suite and is adapted to be used for the Neo environment to Cloud Foundry environment migration scenario.

## On-Premise Mail Test Scenario
The test:
 * Installs and configures **SAP Cloud Connector (SCC)** as explained on the following [wiki](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/cloud-connector) page.

* Configures a fake **Mail server**.

* Validates that the setup is working properly by executing the **sample mail test**.

## Execute Test Locally
Once you have a running application, execute:
```
    mvn clean install \
        -Pintegration-tests \
        -Dapp.url="${APP_URL}/" \
        -Dmail.from.address="${MAIL_USER}" \
        -Dmail.to.address="${MAIL_USER}" 
```

| ENV |  Description  |
|:-----|:--------:|
| APP_URL   | The URL of the deployed application. You can find it in the SAP BTP cockpit. |
| MAIL_USER   |   The mail user configured in the destination configuration | 
