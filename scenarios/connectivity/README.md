# Connectivity

## Table of Contents
- [Overview](#overview)
- [Refactoring Guidelines for Connectivity Usage](#refactoring-guidelines-for-connectivity-usage)
- [Example](#example)
- [Related Information](#related-information)
- [Example](#example)
- [Additional Scenarios](#additional-scenarios)

## Overview

In the SAP BTP, Cloud Foundry environment, we can use the SAP Cloud SDK to establish a connection between an on-premise system and Cloud Connector. The SAP Cloud SDK provides an API to consume destinations created in the SAP BTP cockpit.<br>

If a Java application in the SAP BTP, Neo environment uses ConnectivityConfiguration and AuthenticationHeaderProvider APIs, **remove** them from `src/main/webapp/WEB-INF/web.xml`.

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

## Refactoring Guidelines for Connectivity Usage

Here are some guidelines to follow when refactoring your code:<br>

- Injection and initialization:
    - **Neo environment**: Manually inject `com.sap.cloud.account.TenantContext` and initialize `com.sap.core.connectivity.api.configuration.ConnectivityConfiguration` via JNDI lookup.
    - **Cloud Foundry environment**: No manual resource injection, relying on SDK-internal handling.

- Remove proxy configuration:
    - **Neo environment**: Configure proxy using `System.getenv("HC_OP_HTTP_PROXY_HOST")` and `System.getenv("HC_OP_HTTP_PROXY_PORT")`.
    - **Cloud Foundry environment**: No explicit proxy configuration needed.

- Destination handling:
    - **Neo environment**: Use `com.sap.core.connectivity.api.configuration.ConnectivityConfiguration` and `com.sap.core.connectivity.api.configuration.DestinationConfiguration` for destination handling obtained through JNDI.
    - **Cloud Foundry environment**: Use `com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor.getDestination` for direct access to destinations.

- HTTP client handling:
    - **Neo environment**: Use standard Java HTTP connection classes with manual configuration for proxies and request properties.
    - **Cloud Foundry environment**: Use Cloud SDK API, which provides methods for retrieving preconfigured `org.apache.http.client.HttpClient` (`com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient`).


> **Note:** Ensure that you have removed all unused variables, functions, and import statements.

## Example

- [Example for the Neo environment](neo)
    - **Purpose**: Illustrates a pre-refactored connectivity setup.

- [Example for Cloud Foundry environment](cf)
    - **Purpose**: Demonstrates a connectivity setup after refactoring.
    - **Key Features**:
        - Covers `Outbound Internet` and `Cloud to On-Premise` connectivity scenarios.
        - Provides detailed steps on how to:
            - Deploy the application to the Cloud Foundry environment.
            - Create destinations for different connectivity types. See the [Destination Management section](../destinations/README.md#destination-management) for more details.
            - Set up [Cloud Connector | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/cloud-connector) for on-premise connectivity.
        - Includes example configuration for destinations and system mappings.
  > **Note:** For detailed instructions, including deployment commands, destination JSON templates, and Cloud Connector configuration, see **[Running the Refactored Application for Connectivity Usage in the Cloud Foundry Environment](cf/README.md)**.

## Related Information
- [On-Premise Connectivity | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/on-premise)<br>
- [Use the HttpClient Accessor To Configure Requests To Remote Services | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/http-client)
- [SAP BTP Connectivity | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/connectivity)
- [Using the Destinations Editor in the Cockpit | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/using-destinations-editor-in-cockpit)
- [Using Destinations | SAP Learning](https://learning.sap.com/learning-journeys/administrating-sap-business-technology-platform/using-destinations)
- [Destination Examples | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/destination-examples)
- [Operating Cloud Connector | SAP Learning](https://learning.sap.com/learning-journeys/administrating-sap-business-technology-platform/operating-cloud-connector)

## [Additional Scenarios](../../README.md#7-additional-scenarios)