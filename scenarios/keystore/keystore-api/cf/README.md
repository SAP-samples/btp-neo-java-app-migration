# Running the Refactored Application for Keystore API in the Cloud Foundry Environment

This example illustrates how to retrieve both individual keys and all keys within the Cloud Foundry environment.
A key is manually added to the SAP Credential Store service via the SAP BTP cockpit.
The stored key is uniquely identified by an alias `keystore-app-key` and namespace `keystore-app`.

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

    The URL should look like this: `https://<app-name>.cfapps.<cf-app-domain>/keystore?namespace=<namespace-name>&alias=<alias-name>`.
    > Note: The `/keystore` path in the URL comes from the [web.xml](.\keystore-api-sample\src\main\webapp\WEB-INF\web.xml) file.