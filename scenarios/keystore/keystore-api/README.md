# Keystore API

## Table of Contents

- [Overview](#overview)

- [Refactoring Guidelines](#refactoring-guidelines)

  [1. Remove `KeyStoreService`](#1-remove-keystoreservice)

  [2. Configure Credential Store Service](#2-configure-credential-store-service)

    - [Steps to Use the Credential Store](#steps-to-use-the-credential-store)

    - [Retrieving Service Binding Programmatically](#retrieving-service-binding-programmatically)

  [3. Set Up mTLS for Secure Communication](#3-set-up-mtls-for-secure-communication)

  [4. Retrieve Keys](#4-retrieve-keys)

    - [Prerequisites](#prerequisites)

    - [Retrieving All Keys](#retrieving-all-keys)

        - [Example for Building the API Request](#example-for-building-the-api-request)

    - [Retrieving Specific Key](#retrieving-specific-key)

        - [Example for Building the API Request](#example-for-building-the-api-request-1)

  [5. Decrypt Keys](#5-decrypt-keys)

- [Example](#example)

- [Related Information](#related-information)

- [Additional Scenarios](../../../README.md#7-additional-scenarios)

## Overview
The Neo environment provides the SAP Keystore API, allowing applications to securely
store and manage cryptographic keys and certificates. In the Cloud Foundry (CF) environment, the
Credential Store service replaces the Keystore API, providing a flexible way to
store and manage keys.

## Refactoring Guidelines
Here are some guidelines to follow when refactoring your code:

### 1. Remove `KeyStoreService`
If a Java application in the Neo environment uses `KeyStoreService` API, follow these steps to **remove** it:

1.1 Delete from `web.xml`. <br>

Remove the following `<resource-ref>` from `src/main/webapp/WEB-INF/web.xml`:

  ```xml
  <resource-ref>
      <res-ref-name>KeyStoreService</res-ref-name>
      <res-type>com.sap.cloud.crypto.keystore.api.KeyStoreService</res-type>
  </resource-ref>
  ```

1.2 Remove JNDI lookup.<br>

Delete the JNDI lookup code:
  ```java
    InitialContext ctx = new InitialContext();
    KeyStoreService keyStoreService = (KeyStoreService) ctx.lookup("java:comp/env/KeyStoreService");
  ```

### 2. Configure Credential Store Service

The Cloud Foundry environment uses the [SAP Credential Store | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/sap-credential-store) to manage credentials securely.
Unlike the Neo environment, credentials are organized in **namespaces** and accessed through REST APIs.

#### Steps to Use the Credential Store

1. Create a service instance of the Credential Store service.
2. Bind the service to the application.<br>
   Once bound, the service credentials will be available inside the **VCAP_SERVICES** environment variable.
3. Access the service credentials. <br>
   Instead of directly parsing `VCAP_SERVICES`, use the `com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor`.

#### Retrieving Service Binding Programmatically

Here is how to retrieve the service binding programmatically:

```java
public class ServiceBindingAccessor {
    static final String SERVICE_BINDING_NAME = "credstore";

    public static Optional<ServiceBinding> getServiceBinding() {
        List<ServiceBinding> allServiceBindings = DefaultServiceBindingAccessor.getInstance().getServiceBindings();

        return allServiceBindings.stream()
                .filter(binding -> SERVICE_BINDING_NAME.equalsIgnoreCase(binding.getServiceName().orElse(null)))
                .findFirst();
    }
}
```

### 3. Set Up mTLS for Secure Communication

This [example](cf) uses mTLS. If you prefer to use a different method, see
[Authentication | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/authentication).

#### Steps to Establish an SSL Context

1. Retrieve properties from service binding.

    - `key` – PEM format, PKCS#1 encoded RSA private key

    - `certificate` – PEM format, chain of X.509 certificates

   > Note: You can find example code to retrieve the properties at
   > [ ../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/service/ServiceCredentialsAccessor.java]( ../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/service/ServiceCredentialsAccessor.java).

2. Parse and add properties to a `KeyStore` object.

    - The `key` and `certificate` must be parsed.

    - Add them to a `java.security.KeyStore` object.

3. Create an SSLContext.

   Use the `java.security.KeyStore` object to create a `javax.net.ssl.SSLContext`.

> Note: You can find a **full code example** demonstrating the setup of mTLS at
> [../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/authentication/SSLContextProvider.java](../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/authentication/SSLContextProvider.java).

### 4. Retrieve Keys

#### Prerequisites

Remove these methods provided by the Neo environment and replace them with REST API requests to the Credential Store service.

- `getKeyStoreNames()`: Retrieves a list of all available keystores.

- `getKeyStore(String keyStoreName)`: Fetches a specific keystore.

#### Retrieving All Keys

- **Namespace header** (`sapcp-credstore-namespace`): Identifies the logical group where the key belongs.

- **Construct request URL** : `<credential-store-url>/keys`

> Note:
> - The `<credential-store-url>` is retrieved from the service binding.
> - You can find a code example for retrieving the `<credential-store-url>` at
    > [ ../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/service/ServiceCredentialsAccessor.java]( ../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/service/ServiceCredentialsAccessor.java).

#### Example for Building the API Request

Ensure to include the namespace header and construct the URL:
```java
private static final String HEADER_NAMESPACE = "sapcp-credstore-namespace";
private static final String CONTENT_TYPE = "Content-Type";
private static final String APPLICATION_JOSE = "application/jose";

public static HttpRequest buildRequest(String requestUrl, String namespace) throws URISyntaxException {
    return HttpRequest.newBuilder()
            .uri(new URI(requestUrl))
            .header(HEADER_NAMESPACE, namespace) // include namespace header
            .header(CONTENT_TYPE, APPLICATION_JOSE)
            .GET()
            .timeout(java.time.Duration.ofSeconds(10))
            .build();
}
String requestUrl = credentialStoreUrl + "/keys"; // construct URL
HttpRequest request = buildRequest(requestUrl, namespace);
```

### Retrieving Specific Key

- **Namespace header** (`sapcp-credstore-namespace`): Identifies the logical group where the key belongs.

- **Construct request URL** : `<credential-store-url>/key?name=<alias>`

> Note: <br>
> - The `<credential-store-url>` is retrieved from the service binding. <br>
> - You can find a code example for retrieving the `<credential-store-url>` at
    > [ ../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/service/ServiceCredentialsAccessor.java]( ../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/service/ServiceCredentialsAccessor.java).
> - Each key in the Cloud Foundry environment is identified by an `<alias>`, serving as a unique name within a namespace.

#### Example for Building the API Request

Ensure to include the namespace header and construct the URL:
```java
private static final String HEADER_NAMESPACE = "sapcp-credstore-namespace";
private static final String CONTENT_TYPE = "Content-Type";
private static final String APPLICATION_JOSE = "application/jose";

public static HttpRequest buildRequest(String requestUrl, String namespace) throws URISyntaxException {
    return HttpRequest.newBuilder()
            .uri(new URI(requestUrl))
            .header(HEADER_NAMESPACE, namespace) // include namespace header
            .header(CONTENT_TYPE, APPLICATION_JOSE)
            .GET()
            .timeout(java.time.Duration.ofSeconds(10))
            .build();
}
String requestUrl = credentialStoreUrl + "/key?name=" + alias; // construct URL
HttpRequest request = buildRequest(requestUrl, namespace);
```

### 5. Decrypt Keys

This [example](cf) verifies whether the retrieval request is successful.
However, the response body contains an encrypted payload that needs to be processed and decrypted before use.

The Credential Store encrypts credentials by default. Decryption is required to access the retrieved data.
You can find a Java example demonstrating how to encrypt and decrypt the payload at
[Code Samples (Java) | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/code-samples-java).

## Example
- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)

## Related Information
- [Keystore API | SAP Help Portal](https://help.sap.com/docs/btp/sap-btp-neo-environment/keystore-api)
- [Keystore API Documentation | api.hana.ondemand.com](https://api.hana.ondemand.com/keystore/v1/documentation)
- [Credential Store REST API for Applications | SAP Business Accelerator Hub](https://api.sap.com/api/credentials_api_for_applications/resource/)
- [Encrypting Payloads | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/encrypting-payloads)

## [Additional Scenarios](../../../README.md#7-additional-scenarios)