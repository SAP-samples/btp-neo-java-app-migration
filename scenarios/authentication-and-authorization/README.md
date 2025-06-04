# Authentication and Authorization

## Table of Contents
- [Overview](#overview)
- [Application Security Descriptor](#application-security-descriptor)
  - [Understanding the Application Security Descriptor Concepts](#understanding-the-application-security-descriptor-concepts)
  - [Application Security Descriptor Template](#application-security-descriptor-template)
- [Authentication Method](#authentication-method)
- [User Management in SAP BTP Cloud Foundry Environment](#user-management-in-the-cloud-foundry-environment)
  - [Refactoring Guidelines](#refactoring-guidelines)
- [Application Router](#application-router)
  - [Using the Application Router from the npm Registry](#using-the-application-router-from-the-npm-registry)
  - [Extending the Application Router](#extending-the-application-router)
  - [Deploying the Application Router](#deploying-the-application-router)
- [Example](#example)
- [Related Information](#related-information)
- [Additional Scenarios](#additional-scenarios)

## Overview
SAP BTP, Cloud Foundry environment uses SAP Authorization and Trust Management (XSUAA) service instances for authentication and authorization. For more information on how to implement authentication and authorization in the Cloud Foundry environment, see [Adding Authentication and Authorization | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/adding-authentication-and-authorization).<br>

To use it, create a service instance and a service binding for your application. You can create them manually using the Cloud Foundry command line interface (cf CLI) or simply add them as a resource to your `mtad.yaml` descriptor file. If you choose the `mtad.yaml` approach, the creation of the SAP Authorization and Trust Management service instance/service binding is done automatically by the `cf deploy` command. Here is how to define the resource in the `mtad.yaml` file:
```yaml
resources:
  ...
  - name: <service-instance-name>
    type: org.cloudfoundry.managed-service
    parameters:
      service: xsuaa
      service-plan: application
      path: ./xs-security.json
  ...
```
> Note: We suggest that the `<service-instance-name>` follows the `<app-name>-xsuaa` structure, but you can choose your own name.
> Note: For more information on how to create your `mtad.yaml` file, see [Multitarget Applications in the Cloud Foundry Environment | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/multitarget-applications-in-cloud-foundry-environment) or check this [example](../../README.md#81-prepare-the-mta-deployment-descriptor-file).

## Application Security Descriptor
You need to create the `xs-security.json` file, also known as the application security descriptor, in either the `root` directory or a security subfolder of your project, and provide a path to the newly created file. The application security descriptor is a configuration file that specifies your application's authorization information such as `scopes`, `role-templates`, `role-collection`, etc. Using the cockpit, administrators of the environment assign role collections to business users.
> Note: For more information about the application security descriptor, see [Application Security Descriptor Configuration Syntax | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/application-security-descriptor-configuration-syntax).

### Understanding the Application Security Descriptor Concepts
- The element `xsappname` defines a prefix for the runtime name of the application. For each tenant (subaccount), which has subscribed to the application, the SAP Authorization and Trust Management service supplies a unique tenant index for the application subscription and internally concatenates the tenant index with `xsappname` at runtime.
  The `xsappname` has to be unique within the entire SAP Authorization and Trust Management instance.

- The `tenant-mode` property is used to define the way the tenant's OAuth clients get their client secrets. The application router uses the tenant mode information for the implementation of multitenancy with the application service plan.

- `scopes` represent the API endpoints - or functions - for which access should be restricted. They’re required if you want to define the functions a user is authorized to process. A scope has an arbitrary name and must be prefixed with the runtime application name to distinguish the user scopes between tenants (subaccounts) which have subscribed to the application and equally named scopes between different applications. The `$XSAPPNAME` dummy value is a wildcard for the application runtime name and is used to prefix the arbitrary scope names. The XSUAA service substitutes the `$XSAPPNAME` dummy value with the application runtime name for each of the subscribed tenants.

- `attributes` represent the data entities for which access should be restricted. They’re required if you want to define which data a user is authorized to process. The `attributes` element is only relevant for a user scenario. These attributes can be referenced by role templates.

- `role-templates` combine `scopes` with `attributes` and serve as templates from which roles are created later by the administrator.

- The optional `role-collections` property enables you to define role collections with predefined roles. Administrators use these predefined role collections. They can assign them to users during the initial setup of SAP BTP. The `role-collections` property only makes sense if application developers reference role templates that can create default roles at deployment time.

### Application Security Descriptor Template
Here is a basic template for `xs-security.json`:
```json
{
    "xsappname": "<your-app-name>",
    "tenant-mode": "<tenant-mode>",
    "scopes": [
        {
            "name": "$XSAPPNAME.<scope-name>",
            "description": "<scope-description>"
        }
    ],
    "attributes": [
        {
            "name": "<attribute-name>",
            "description": "<attribute-description>",
            "valueType" : "<value-type>"
        }
    ],
    "role-templates": [
        {
            "name": "<role-template-name>",
            "description": "<role-description>",
            "scope-references": [
                "$XSAPPNAME.<scope-name>"
            ],
            "attribute-references": [
                "<attribute-name>"
            ]
        }
    ],
    "role-collections": [
        {
            "name": "<role-collection-name>",
            "description": "<role-collection-description>",
            "role-template-references": [
                "$XSAPPNAME.<role-template-name>"
            ]
        }
    ]
}
```

> Note: In the SAP BTP, Neo environment, all authenticated users have the `Everyone` role implicitly, but that is not the case for the Cloud Foundry environment.<br>
> Note: You can find the role required by your application in your `src/main/webapp/WEB-INF/web.xml`.

## Authentication Method
In the `src/main/webapp/WEB-INF/web.xml`, change `<auth-method>` to XSUAA:<br>
```xml
<login-config>
    <auth-method>XSUAA</auth-method>
</login-config>
```

## User Management in the Cloud Foundry Environment
In Java applications in the SAP BTP, Neo environment, the `UserManagementAccessor` API is used to retrieve information about the current user. In the SAP BTP, Cloud Foundry environment, we use the [cloud-security-services-integration-library | github.com](https://github.com/SAP/cloud-security-services-integration-library/tree/main-2.x).<br>

To do that, we need to add the following dependency to the `pom.xml` file:<br>

```xml
<dependency>
    <groupId>com.sap.cloud.security</groupId>
    <artifactId>java-api</artifactId>
</dependency>
```

### Refactoring Guidelines
Here are some guidelines to follow when refactoring your source code:<br>

- Principal Authentication Mechanism:
  - **Neo environment**: Use `javax.servlet.http.HttpServletRequest.getUserPrincipal()` to get the user principal.
  - **Cloud Foundry environment**: Use `com.sap.cloud.security.token.SecurityContext.getAccessToken()` to get the JWT token(`com.sap.cloud.security.token.Token`).

- User Attribute Retrieval:
  - **Neo environment**: Fetch attributes via `com.sap.security.um.user.UserProvider` and `com.sap.security.um.user.User`.
  - **Cloud Foundry environment**: You can extract user attributes directly from the JWT token retrieved from the security context with the `com.sap.cloud.security.token.Token.getClaimAsString(<user-attribute-name>)` function.

> Note: Ensure that you have implemented proper error handling and removed all unused variables, functions, and import statements.

## Application Router
To enable web access via a browser or a browser-based user interface, use an **application router** to create a secure route to your application. The **SAP Approuter** is a key component for applications that interact with users, especially when serving static files like `*.html` or using template engines. It acts as a gateway, triggering authentication when necessary and managing routing for your application.

The SAP Approuter serves as the single point-of-entry for an application running in the Cloud Foundry environment on SAP BTP. It is used to:
- `Serve static content`: Deliver HTML, CSS, and JavaScript files directly to the client.
- `Authenticate users`: Trigger authentication processes to ensure secure access.
- `Rewrite URLs`: Modify URLs of requests to match routing rules.
- `Forward or proxy requests`: Redirect client requests to the appropriate backend services.
- `Propagate user information`: Include user information, such as user ID and roles, when forwarding requests to backend services.

The following files are mandatory to define the routes and other configurations for the Approuter:
- `package.json`: The package descriptor used by the Node.js package manager (`npm`) to start the application router, defining the Approuter and its dependencies.
- `xs-app.json`: The application descriptor containing the configuration used by the application router, such as routes and destinations for request forwarding.

These files and other configuration files for the application router are located in the `approuter` directory. Therefore, create a new subdirectory named `approuter` in the root directory of your project.

### Using the Application Router from the npm Registry
> Note: If your application uses **basic authentication**<!--, ...todo ... -->, you should use your own **extended** approuter. See [Extending the Application Router](#extending-the-application-router).

In most cases, it is recommended to use the official application router. You can find it as a library on `npmjs.com` ([@sap/approuter | www.npmjs.com](https://www.npmjs.com/package/@sap/approuter)).

#### Package Descriptor
The package descriptor (`package.json`) contains the start command for the application router and a list of the package dependencies. In the `approuter` directory, create a `package.json` file with the following content:
> Note: Here is  a [sample `package.json`](cf/approuter/package.json).
```json
{
    "name": "approuter",
    "dependencies": {
        "@sap/approuter": "<version>"
    },
    "scripts": {
        "start": "node node_modules/@sap/approuter/approuter.js"
    }
} 
```
> Note: The version of the `@sap/approuter` may be updated over time. Please check the [NPM package page](https://www.npmjs.com/package/@sap/approuter) for the latest version during the actual migration and replace `<version>` with it.

#### Routing Configuration File (Application Descriptor)
The routing configuration defined in the application descriptor (`xs-app.json`) contains the configurations used by the application router.<br>
Create an `xs-app.json` file inside the `approuter` directory with the following content:
> Note: The following configuration will work for the basic case when all application endpoints require authentication. If your application uses more complex authentication and routing, see [Routing Configuration File | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/routing-configuration-file) for more information on how to create the application descriptor. You can find a sample `xs-app.json` file at [cf/approuter/xs-app.json](cf/approuter/xs-app.json).
```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "xsuaa",
            "csrfProtection": false
        }
    ]
}
```

> Note: Replace `<destination-name>` with the name of your destination. This destination is used to proxy requests from the Approuter to the backend application. You can create this destination in the SAP BTP cockpit or with the deployment descriptor (`mtad.yaml`). You can see how to create the destination with a `mtad.yaml` file in the step [Deploying the Application Router](#deploying-the-application-router).

### Extending the Application Router
Instead of starting the application directly, you can configure it to use its own start script. This allows us to extend the application router with custom middleware to handle specific request routing and authentication scenarios, providing more control over how requests are processed. <!--This will be needed if your application is using ... todo basic auth, ... -->

#### Custom Middleware Handler
Create a file named `authentication-challenge-handler.js` inside the `approuter` directory. The file functions as a custom middleware extension, which is inserted in the approuter instance to provide more control over how requests are processed. For more information, see [Custom Middleware Injection | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/extending-application-router#custom-middleware-injection).

Here's the code for the custom middleware extension:
> Note: You can find a custom middleware extension in [`authentication-challenge-handler.js`](cf/approuter-extended/authentication-challenge-handler.js).

```js
function redirect(response, locationURL) {
    console.log('Redirecting to: [' + locationURL + ']');
    response.setHeader('Location', locationURL);
    response.statusCode = 303;
    response.end();
}

function handleAuthenticationChallenge(context, authenticateHeader) {
    let incomingRequest = context.incomingRequest;
    let incomingResponse = context.incomingResponse;

    if (authenticateHeader.includes('SAML2 realm="Identity Authentication Service"')) {
        console.log('Handling SAML2 (OIDC) authentication challenge.');
        incomingResponse.setHeader('Location', '/authentication/endpoint' + incomingRequest.url);
        incomingResponse.statusCode = 303;
        console.log('Redirecting to: [/authentication/endpoint' + incomingRequest.url + ']');
    } else if (authenticateHeader.includes('Basic realm="SAP HANA Cloud Platform"')) {
        console.log('Handling BASIC (OIDC) authentication challenge.');
        incomingResponse.setHeader('Location', '/basic/authentication/endpoint' + incomingRequest.url);
        incomingResponse.statusCode = 303;
        console.log('Redirecting to: [/basic/authentication/endpoint' + incomingRequest.url + ']');
    }
}

function handleLogout(context, logoutRequest) {
    let incomingRequest = context.incomingRequest;
    let incomingResponse = context.incomingResponse;

    if (logoutRequest.includes('logout-request')) {
        console.log('Triggering logout.');
        incomingResponse.setHeader('Location', '/logout/endpoint?originalURL=' + incomingRequest.url);
        incomingResponse.statusCode = 303;
        console.log('Redirecting to: [/logout/endpoint?originalURL=' + incomingRequest.url + ']');
    }
}

module.exports = {
    insertMiddleware: {
        beforeRequestHandler: [
            {
                handler: function authenticationChallengeHandler(request, response, callNextHandler) {
                    console.log('Handling request with path: [' + request.url + ']');

                    if (request.url.startsWith('/authentication/endpoint')) {
                        let locationURL = request.url.substring('/authentication/endpoint'.length);
                        redirect(response, locationURL);
                    } else if (request.url.startsWith('/basic/authentication/endpoint')) {
                        let locationURL = request.url.substring('/basic/authentication/endpoint'.length);
                        redirect(response, locationURL);
                    } else if (request.url.startsWith('/logout/callback?originalURL=')) {
                        let locationURL = request.url.substring('/logout/callback?originalURL='.length);
                        redirect(response, locationURL);
                    } else {
                        request.afterRequestHandler = function (context, done) {
                            let outgoingResponse = context.outgoingResponse;
                            let authenticateHeader = outgoingResponse.headers['www-authenticate'];
                            let logoutRequest = outgoingResponse.headers['com.sap.cloud.security.logout'];

                            console.log('Received headers from target system are: [' + JSON.stringify(outgoingResponse.headers) + ']');

                            if (authenticateHeader !== undefined) {
                                console.log('WWW-Authenticate header value is: [' + authenticateHeader + ']');
                                handleAuthenticationChallenge(context, authenticateHeader);
                            }
                
                            if (logoutRequest !== undefined) {
                                console.log('com.sap.cloud.security.logout header value is: [' + logoutRequest + ']');
                                handleLogout(context, logoutRequest);
                            }

                            console.log('Finalizing the request.');
                            done(null, context.incomingResponse);
                        };

                        callNextHandler();
                    }
                }
            }
        ]
    }
};
```

#### Custom Middleware Injection
To initialize and start the Approuter with the custom middleware injected, create a file named `server.js` inside the `approuter` directory with the following content:
> Note: You can find the sample `server.js` at [cf/approuter-extended/server.js](cf/approuter-extended/server.js).
```js
var approuter = require('@sap/approuter');
var ar = approuter();

ar.start({
    extensions: [
        require('./authentication-challenge-handler.js')
    ]
});
```

#### Package Descriptor
To specify the `dependencies` and `scripts` required to run the approuter, create the `package.json` file inside the `approuter` directory. The file should contain the following configurations:
> Note: You can find a sample `package.json` at [cf/approuter-extended/package.json](cf/approuter-extended/package.json).
```json
{
    "name": "approuter",
    "dependencies": {
        "@sap/approuter": "<version>"
    },
    "scripts": {
        "start": "node server.js"
    }
}
```

> Note: The version of the `@sap/approuter` may be updated over time. Please check the [NPM package page | npmjs.com](https://www.npmjs.com/package/@sap/approuter) for the latest version during the actual migration and replace `<version>` with it.

#### Routing Configuration File
The routing configuration defined in the application descriptor (`xs-app.json`) contains the configurations used by the application router.<br>
Create a `xs-app.json` file inside the `approuter` directory with the following content:
> Note: The following configuration will work for most of the cases, but if your application uses more complex authentication and routing, see [Routing Configuration File | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/routing-configuration-file) for more information on how to create the application descriptor. You can find a sample `xs-app.json` file at [cf/approuter-extended/xs-app.json](cf/approuter-extended/xs-app.json).

```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^/authentication/endpoint(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "xsuaa",
            "csrfProtection": false
        },
        {
            "source": "^/basic/authentication/endpoint(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "basic",
            "csrfProtection": false
        },
        {
            "source": "^/logout/callback\\?originalURL=(.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "none",
            "csrfProtection": false
        },
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "none",
            "csrfProtection": false
        }
    ],
    "logout": {
        "logoutEndpoint": "/logout/endpoint",
        "logoutPage": "/logout/callback"
    }
}
```

> Note: Replace `<destination-name>` with the name of your destination. This destination is used to proxy requests from the Approuter to the backend application. You can create this destination in the SAP BTP cockpit or with the deployment descriptor (`mtad.yaml`). You can see how to create the destination with an `mtad.yaml` file in the step [Deploying the Application Router](#deploying-the-application-router).

### Deploying the Application Router
You can deploy the approuter by defining it in the deployment descriptor (`mtad.yaml`):
```yaml
_schema-version: "3.2"
version: 0.0.1
ID: <id>
modules:
  ...
  - name: <app-name>-approuter
    type: nodejs
    path: approuter
    parameters:
      disk-quota: 256M
      memory: 256M
      routes:
        - route: '${protocol}://<app-name>.${default-domain}'
          protocol: http1
    properties:
      XS_APP_LOG_LEVEL: debug
    requires:
      - name: <app-name>-xsuaa
      - name: <app-name>-java-app
        group: destinations # creating the destination
        properties:
          name: <destination-name> # you should use this name in xs-app.json file
          url: '~{java_app_url}' # The url to the java app
          forwardAuthToken: true

resources:
  ...
  - name: <app-name>-xsuaa
    type: org.cloudfoundry.managed-service
    parameters:
      service: xsuaa
      service-plan: application
      path: ./xs-security.json
  - name: <app-name>-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite
```
> Note: You can see a full example of `mtad.yaml` at [../../README.md#81-prepare-the-mta-deployment-descriptor-file](../../README.md#81-prepare-the-mta-deployment-descriptor-file).

<!-- ### Containerizing the Approuter
#### Building the Docker image
Instead of deploying the approuter as a `nodejs` module, you can deploy it from Docker image. To build the image, you can follow these steps:

1. Navigate to `approuter` directory:
```sh
cd approuter
```

2. Create a `Dockerfile` with the following content:
```Dockerfile
FROM node:slim

WORKDIR /<app-workdir>
COPY ./ ./

RUN npm install

EXPOSE 7000
CMD [ "npm", "start" ]
```

> Note: Replace `<app-workdir>` with the actual working directory name you want to use inside the Docker container. You can use `\usr\src\approuter`.<br>
> Note: You can replace the `node` version with version that is compatible with the version of `@sap/approuter` version in the `package.json` file. You can see the released docker imahes [here](https://hub.docker.com/_/node).

3. Build the Docker image:
```sh
docker build -t <approuter-image-name> .
```

> Note: Replace `<approuter-image-name>` with the name that you want to use for the Docker image. You can use `approuter` for example.

#### Deploying the approuter form Docker image
In `mtad.yaml` file add the following module for the approuter:
```yaml
_schema-version: "3.2"
version: 0.0.1
ID: <id>
modules:
  ...
  - name: <app-name>-approuter
    type: application
    requires:
      - name: <app-name>-xsuaa
      - name: <app-name>-java-app
        group: destinations
        properties:
          name: backend-app-destination
          url: '~{neo-app-url}'
          forwardAuthToken: true
    parameters:
      docker:
        image: <approuter-image-name>
      routes:
        - route: '${protocol}://<app-name>.${default-domain}'
          protocol: http1
      disk-quota: 256M
      memory: 256M
    properties:
      CF_NODEJS_LOGGING_LEVEL: "info"
      XS_APP_LOG_LEVEL: "info"
...
``` -->

## Example
- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)

## Related Information
- [Application Router | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/application-router)
- [Extending the Application Router | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/extending-application-router)
- [Application Router Configuration Syntax | SAP Help Portal](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-developer-guide-for-cloud-foundry-multitarget-applications-sap-web-ide-full-stack/application-router-configuration-syntax)
- [Java API Security Token Usage Source Code | github.com](https://github.com/SAP/cloud-security-services-integration-library/tree/main/java-api/src/main/java/com/sap/cloud/security/token)
- [Token Usage | github.com](https://github.com/SAP/cloud-security-services-integration-library/tree/main/java-security#token-usage)
- [Configure Authentication and Authorization | SAP Help Portal](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-developer-guide-for-cloud-foundry-multitarget-applications-sap-web-ide-full-stack/configure-authentication-and-authorization#configure-authentication-and-authorization-checks-for-java-applications)
- [Authentication and Functional Authorization | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/add-authentication-and-functional-authorization-checks-to-your-application)
- [Tutorial: Secure Your Application on SAP Business Technology Platform Cloud Foundry | SAP Learning](https://developers.sap.com/tutorials/s4sdk-secure-cloudfoundry.html)

## [Additional Scenarios](../../README.md#7-additional-scenarios)
