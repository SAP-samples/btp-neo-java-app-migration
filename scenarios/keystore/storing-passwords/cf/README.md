# Running the Refactored Application for Password Storage API in the Cloud Foundry Environment

This example covers only the scenario of retrieving a password from the SAP Credential Store service. 
The password is manually added in the credential store service via the SAP BTP cockpit. It is under the alias `test` and namespace `pass-storage-app`.

## Deploy the Application
To run the application, you need to deploy it in the Cloud Foundry environment. Deploy it by following these steps:
1. Build the application by executing the following command:
    ```sh
    mvn clean install
    ```
2. Log on to your Cloud Foundry account:
    ```sh
    cf login --sso
    ```
3. Deploy the application in the Cloud Foundry environment:
    ```sh
    cf deploy . -f
    ```
   
## Access the Application
To test the application in a browser:
1. Open the application URL from the **Overview** page of the application in the SAP BTP cockpit.
2. Pass these query parameters:
    - `namespace` <br>
    - `alias` 

    The URL should look like this: `https://<app-name>.cfapps.<cf-app-domain>/?namespace=<namespace-name>&alias=<alias-name>`.