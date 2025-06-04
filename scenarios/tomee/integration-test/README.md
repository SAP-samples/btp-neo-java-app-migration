# Integration Test for the Persistence with EJB Scenario on TomEE Runtime

## Run Tests Locally

1. Deploy the test application.

    To run the integration tests, you need to deploy the test application first. It is located in the [`cf`](../cf/) directory. To deploy the application, refer to [Deploy the Application](../cf/README.md#deploy-the-application) steps.

2. Run the tests.

    To run the tests, execute the following command:
    ```sh
    mvn clean install -Pintegration-tests -Dapp.url="<app-url>"
    ```
    > Note: The `<app-url>` is the URL of the deployed application and can be found in the SAP BTP cockpit.
