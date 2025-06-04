# Storing Passwords

## Table of Contents

- [Overview](#overview)

- [Refactoring Guidelines](#refactoring-guidelines)

  [1. Remove Password Storage](#1-remove-password-storage)

  [2. Add Credential Store Service](#2-add-credential-store-service)

    [2.1 Retrieve Service Credentials](#21-retrieve-service-credentials)

    [2.2 Set Up mTLS for Secure Communication](#22-set-up-mtls-for-secure-communication)

    [2.3 Retrieve the Password](#23-retrieve-the-password)

    [2.4 Decrypt Keys](#24-decrypt-keys)

- [Migrating Passwords](#migrating-passwords)

- [Example](#example)

- [Related Information](#related-information)

- [Additional Scenarios](../../../README.md#7-additional-scenarios)

## Overview

In the Neo environment, you can securely persist passwords and key phrases using the [Password Storage API | SAP Business Accelerator Hub](https://api.sap.com/api/SCP_PasswordStorage/resource/Passwords_on_application_level). Once stored, the passwords:
- Can be accessed from different application computing units;

- Survive application restarts and updates;

- Are subject to automatic backup;

- Persist until explicitly deleted via the API or when the application is undeployed.

This service has been succeeded by the [SAP Credential Store | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/sap-credential-store) in the multi-cloud foundation.

## Refactoring Guidelines

Here are some guidelines to follow when refactoring your code:

### 1. Remove Password Storage

1.1. Remove the following `<resource-ref>` from `WEB-INF/web.xml:

```xml
<resource-ref>
  <res-ref-name>PasswordStorage</res-ref-name>
  <res-type>com.sap.cloud.security.password.PasswordStorage</res-type>
</resource-ref>
```

1.2. Remove the lookup of the `javax.naming.InitialContext` object and the usage of `com.sap.cloud.security.password.*` objects.

```java
import javax.naming.InitialContext;
import javax.naming.NamingException;
 
import com.sap.cloud.security.password.PasswordStorage;
import com.sap.cloud.security.password.PasswordStorageException;
.......
 
   private PasswordStorage getPasswordStorage() throws NamingException {
    InitialContext ctx = new InitialContext();
    PasswordStorage passwordStorage = (PasswordStorage) ctx.lookup("java:comp/env/PasswordStorage");
    return passwordStorage;
  }
 
  private void setPassword(String alias, char[] password) throws PasswordStorageException, NamingException {
    PasswordStorage passwordStorage = getPasswordStorage();
    passwordStorage.setPassword(alias, password);
  }
 
  private char[] getPassword(String alias) throws PasswordStorageException, NamingException {
    PasswordStorage passwordStorage = getPasswordStorage();
    return passwordStorage.getPassword(alias);
  }
 
  private void deletePassword(String alias) throws PasswordStorageException, NamingException {
    PasswordStorage passwordStorage = getPasswordStorage();
    return passwordStorage.deletePassword(alias);
  }
```

### 2. Add Credential Store Service

The Cloud Foundry environment uses the [SAP Credential Store | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/sap-credential-store) to manage credentials securely.

#### 2.1 Retrieve Service Credentials

2.1.1 To connect to the Credential Store, you need to create a service instance of the `Credential Store` service in the Cloud Foundry environment. You can use the `cf` CLI to create a service instance of the `Credential Store` service and bind it to your application.

2.1.2 Once bound, you can use the `VCAP_SERVICES` environment variable to retrieve the service credentials. You can do this by using the `com.sap.cloud.environment.servicebinding.api.DefaultServiceBindingAccessor` class:

```java
public ServiceBinding loadServiceBinding() throws ServiceBindingAccessException {
    logger.debug("Getting Credstore service binding");
    List<ServiceBinding> allServiceBindings = DefaultServiceBindingAccessor.getInstance().getServiceBindings();

    return allServiceBindings.stream()
            .filter(binding -> "credstore".equalsIgnoreCase(binding.getServiceName().orElseThrow( () -> new ServiceBindingAccessException( String.format("Service binding with name [%s] not found", "credstore")))))
            .findFirst()
            .orElseThrow(() -> new ServiceBindingAccessException(
                    String.format("Failed to find %s service binding!", "credstore")));
}
```

#### 2.2 Set Up mTLS for Secure Communication

The binding for the service instance contains the following specific properties:

- `key` – PEM format, PKCS#1 encoded RSA private key

- `certificate` – PEM format, chain of X.509 certificates

The client has to use the private key and the certificate chain to perform the mTLS authentication.

> Note: You can find a **full code example** demonstrating the setup of mTLS at
> [../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/authentication/SSLContextProvider.java](../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/authentication/SSLContextProvider.java).


#### 2.3 Retrieve the Password

To retrieve the password from the Credential Store, you need to make a REST API call to its `/password` endpoint.

Example:

```java
    public HttpResponse<String> getPassword(String name, String namespace)  {
  logger.debug("Fetching password for name: [{}] from namespace: [{}].", name, namespace);
  HttpClient httpClient = HttpClient.newBuilder()
          .sslContext(prepareSSLContext())
          .build();
  try {
    HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(credentials.getUrl() + "/password?name=" + name))
            .setHeader("sapcp-credstore-namespace", namespace)
            .setHeader("Content-Type", "application/jose")
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  } catch (URISyntaxException | InterruptedException | IOException e) {
    throw new RuntimeException(e);
  } finally {
    destroyPrivateKey();
  }
}
```
>Note: You can find a **full code example** at
>[../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/client/CredStoreClient.java](../credential-store-client/src/main/java/com/sap/cloud/sample/credstore/client/CredStoreClient.java).

#### 2.4 Decrypt Keys

SAP Credential Store provides [Encrypting Payloads | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/encrypting-payloads), which are enabled by default.
The [Code Samples (Java) | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/code-samples-java) demonstrates how to encrypt and decrypt the payload.

## Migrating Passwords

To migrate passwords from the Neo environment to the Cloud Foundry environment, you can use the [SAP Credential Store API for managing passwords and keys | SAP Business Accelerator Hub](https://api.sap.com/package/CredentialStore/rest).

## Example
- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)

## Related Information
- [Storing Passwords | SAP Help Portal](https://help.sap.com/docs/btp/sap-btp-neo-environment/storing-passwords)
- [SAP Credential Store | SAP Help Portal](https://help.sap.com/docs/credential-store/sap-credential-store/sap-credential-store)
- [SAP Credential Store API for managing passwords and keys | SAP Business Accelerator Hub](https://api.sap.com/package/CredentialStore/rest)

## [Additional Scenarios](../../../README.md#7-additional-scenarios)