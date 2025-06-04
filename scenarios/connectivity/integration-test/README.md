# Integration Test for the Connectivity Scenario

This test covers two basic connectivity scenarios:
- Outbound Internet Connectivity scenario
- Cloud to On-Premise Connectivity scenario

## Run Tests Locally

### Prerequisites

To run the integration tests locally, you need to deploy the test application first. It is located in [`cf`](../cf/) directory. To deploy the application, you can refer to [these](../cf/README.md#deploy-the-application) steps.

Once the test application is deployed, you have two options to choose from. You can either:
- Run the tests with a script.
- Run the tests with manual configurations.

### Run the Tests with a Script
You can run the integration tests by executing a single script.

1. Export the required environment variables.

    | Environment Variable Name | Description                                                              |
    | ------------------------- | ------------------------------------------------------------------------ |
    | APP_NAME                  | The name of the deployed application. It can be found in `mtad.yaml`     |
    | APP_URL                   | The URL of the deployed application. It can be found in SAP BTP Cockpit |
    | REGION_HOST               | The region host of your subaccount                                       |
    | SUBACCOUNT                | The SubaccountID                                                         |
    | CLOUD_USER                | The user's email address                                                 |
    | CLOUD_PASSWORD            | The user's password                                                      |

    You can use following template:
    ```sh
    export APP_NAME=
    export APP_URL=
    export REGION_HOST=
    export SUBACCOUNT=
    export CLOUD_USER=
    export CLOUD_PASSWORD=
    ```

2. Run the script.

    To run the tests execute the following script:
    ```sh
    ./run-test.sh
    ```

### Run the Tests with Manual Configurations

1. Configure the test application.

    To configure the test application you should follow [this](../cf/README.md) guide.

2. Run the test.

    To run the test you should execute the following command:
    ```sh
    mvn clean install -Pintegration-tests -Dapp.url="<app-url>?destname=<destination-name>"
    ```