# Destinations

## Table of Contents
- [Overview](#overview)
- [Refactoring Guidelines for Destinations Usage](#refactoring-guidelines-for-destinations-usage)
- [Destination Management](#destination-management)
- [Related Information](#related-information)
- [Additional Scenarios](#additional-scenarios)

## Overview

A **destination** is an abstraction of a real system or service that applications may want to connect to.
It is a representation of the connection details, such as the URL, authentication, proxy settings, and HTTP headers.
The **destination** concept is an integral part of the SAP Cloud SDK for Java. SAP Cloud SDK uses `Destination` objects as an abstraction of remote services.

Ultimately, a `Destination` will be converted into an [HTTP Client | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/http-client) that can be used to actually send requests to the target.
Hereby, the SAP Cloud SDK supports both cloud and [on-premise | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/on-premise) targets.

The SAP Cloud SDK offers APIs to load destinations from the following sources:

- From the [Destination service | SAP Business Accelerator Hub](https://api.sap.com/api/SAP_CP_CF_Connectivity_Destination/overview) in the Cloud Foundry environment:
  - Via the [`DestinationAccessor` API | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/btp-destination-service)
  > If you are using Java version below 17, refer to [Use Destinations To Connect To Other Systems and Services | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/v4/features/connectivity/destination-service) on how to set up destinations in SAP Cloud SDK v4.
- From the existing `Destination` objects:
  - Via the [`DefaultHttpDestination` API | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/http-destinations)
  > For Java version below 17, refer to [Use Destinations To Connect To Other Systems and Services | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/v4/features/connectivity/destination-service) on how to set up destinations in SAP Cloud SDK v4.<br>

- From [Service Bindings | github.com](https://github.com/SAP/btp-environment-variable-access/wiki/Fundamentals#service-binding):
  - Via the [`ServiceBindingDestinationLoader` API | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/service-bindings)

Once a [`Destination` | sap.github.io](https://sap.github.io/cloud-sdk/java-api/v5/com/sap/cloud/sdk/cloudplatform/connectivity/Destination.html) has been retrieved, it can be used to connect to the system or service it represents.
This is done by converting the given `Destination` into an HTTP client, which is then used to send requests to the system or service.

There are differences in destination usage between the SAP BTP, Neo environment and the SAP BTP, Cloud Foundry environment. For the Cloud Foundry environment, the SAP Cloud SDK offers an API to use destinations created in the SAP BTP cockpit.

## Refactoring Guidelines for Destinations Usage

Here are some guidelines to follow when refactoring your code.<br>

- `ConnectivityConfiguration` and `AuthenticationHeaderProvider`:<br>

  If a Java application in the Neo environment uses `ConnectivityConfiguration` and `AuthenticationHeaderProvider` APIs, **remove** them from `src/main/webapp/WEB-INF/web.xml`. 

  The following `<resource-ref>` must be removed:
  ```xml
  <resource-ref>
      <res-ref-name>[your-res-ref-name]</res-ref-name>
      <res-type>com.sap.core.connectivity.api.configuration.ConnectivityConfiguration</res-type>
  </resource-ref>

  <resource-ref>
      <res-ref-name>[your-res-ref-name]</res-ref-name>
      <res-type>com.sap.core.connectivity.api.authentication.AuthenticationHeaderProvider</res-type>
  </resource-ref>
  ```

- Destination handling:
  - **Neo environment**:
      - Use `com.sap.core.connectivity.api.configuration.ConnectivityConfiguration` and `com.sap.core.connectivity.api.configuration.DestinationConfiguration` with JNDI lookups for destination handling.
      - Use `com.sap.core.connectivity.api.DestinationFactory` with JNDI look up for destination handling.
  - **Cloud Foundry environment**: Use `com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination` retrieved from `com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor`.

 >Note:<br> 
 > * For Java 17 and above, use [`Cloud SDK v5 DestinationAccessor` | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/btp-destination-service) and [`Cloud SDK v5 HttpDestination` | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/http-destinations).<br>
 >
 > * For Java 8 and 11, use `Cloud SDK v4 DestinationAccessor` and `Cloud SDK v4 HttpDestination`. For more information, see [Use Destinations To Connect To Other Systems and Services | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/v4/features/connectivity/destination-service).
    
- HTTP Client handling:
  - **Neo environment**: Use `org.apache.http.impl.client.CloseableHttpClient` with `org.apache.http.impl.client.HttpClients`.
  - **Cloud Foundry environment**: Use `com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor` for retrieving preconfigured [`org.apache.http.client.HttpClient` | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/http-client).

- Authentication headers (if applicable):
  - **Neo environment**: Use `com.sap.core.connectivity.api.authentication.AuthenticationHeaderProvider` to obtain application-to-application SSO headers.
  - **Cloud Foundry environment**: The Cloud SDK handles authentication internally, eliminating the need for manual header management.

> Note: Ensure that you have removed all unused variables, functions, and import statements.

## Destination Management
You can manage destinations using the SAP BTP cockpit or programmatically.

### Using SAP BTP Cockpit
You can create destinations directly from the SAP BTP cockpit. For more information, see [Create Destinations from Scratch | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/create-destinations-from-scratch).

### Programmatic Management
For more information on how to create destinations programmatically, see [Destination Service (Cloud Foundry) REST API | SAP Business Accelerator Hub](https://api.sap.com/api/SAP_CP_CF_Connectivity_Destination/path/post_instanceDestinations).

## Related Information
- How to retrieve destinations in the Neo environment using `com.sap.core.connectivity.api.DestinationFactory`: [Retrieving HTTP Destinations Using DestinationFactory | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-neo/httpdestination-api-and-destinationfactory?locale=en-US#retrieving-http-destinations-using-destinationfactory)
- [`com.sap.cloud.sdk.cloudplatform.connectivity` API | sap.github.io](https://sap.github.io/cloud-sdk/java-api/v5/com/sap/cloud/sdk/cloudplatform/connectivity/package-summary.html)
- [Tutorial: Create a Destination in the SAP BTP Cockpit | SAP Learning](https://developers.sap.com/tutorials/cp-cf-create-destination.html)

## [Additional Scenarios](../../README.md#7-additional-scenarios)
