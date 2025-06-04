# Running the Refactored Application for SAP Authorization and Trust Management Service in the Cloud Foundry Environment

## Deploy the Application

To run the application, you need to deploy it in the Cloud Foundry environment. Perform these steps:

1. Build the application by executing the following command:
    ```sh
    mvn clean install
    ```

2. Log on to your Cloud Foundry account:
    ```sh
    cf login --sso
    ```

3. Deploy the application to the Cloud Foundry environment:
    ```sh
    cf deploy . -f
    ```

## Access the Application

To test the application in a browser, open the approuter application URL, which you can find in the SAP BTP cockpit. The standard URL format is `https://<app-name>.cfapps.<cf-app-domain>`.

The application has three endpoints:
| Endpoint     | Description                                                                                                                               |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `/`          | This endpoint can be accessed without authentication. The page displays a link to the protected area.                                     |
| `/protected` | This endpoint is protected and requires authentication. When the user is authenticated, the page displays a welcome message with the user details. |
| `/logout`    | This endpoint triggers logout. The page displays a link to the protected area.                                                            |

Please note that to access the protected area, you will need to assign the appropriate role collection (`authentication-app-rc`) using the SAP BTP cockpit:
1. Navigate to your subaccount in the cockpit.
2. Under `Security`, choose `Users`. Then find and select your user.
3. Under `Role Collections`, choose `Assign Role Collection`. Then search for `authentication-app-rc` and select it.
4. Choose `Assign Role Collection`.