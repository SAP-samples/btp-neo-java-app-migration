# Running the Refactored Application for Persistence with EJB on TomEE Runtime in the Cloud Foundry Environment

## Deploy the Application

To run the application, you need to deploy it in the Cloud Foundry environment. Perform these steps:

1. Build the application by executing the following command:
    ```sh
    mvn clean install
    ```

2. Log in to your Cloud Foundry account:
    ```sh
    cf login --sso
    ```

3. Deploy the application to the Cloud Foundry environment:
    ```sh
    cf deploy . -f
    ```

## Access the Application

To test the application in a browser, find the application URL on the **Overview** page of your application in the SAP BTP cockpit, and then open it.
The format of the application URL is `https://<app-name>.cfapps.<cf-app-domain>`.