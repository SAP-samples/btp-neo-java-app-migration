# Mail Session

## Table of Contents
- [Overview](#overview)
- [Refactoring Guidelines](#refactoring-guidelines)
- [Example](#example)
- [Related Information](#related-information)
- [Additional Scenarios](#additional-scenarios)

## Overview
In the Neo environment, you can obtain a mail session resource using resource injection or a JNDI lookup.

The properties of the mail session are specified by a mail destination configuration and the names of the destination configuration and the mail session resource must be the same.

In the Cloud Foundry environment, this creation does not occur automatically.

## Refactoring Guidelines
Here are some guidelines to follow when refactoring your code.<br>

### Mail Session Resource

If you have directly injected the mail session resource using the annotations shown below, **remove** it.

```java
@Resource(name = "mail/Session")
private javax.mail.Session mailSession;
```

If you have obtained a resource of type `javax.mail.Session` by declaring a JNDI resource reference in the `WebContent/WEB-INF/web.xml` deployment descriptor, as shown below, **remove** that resource reference.
```xml
<resource-ref>
    <res-ref-name>mail/Session</res-ref-name>
    <res-type>javax.mail.Session</res-type>
</resource-ref>
```

Also **remove** the lookup of the `javax.naming.InitialContext` object.
```java
InitialContext ctx = new InitialContext();
Session mailSession = (Session)ctx.lookup("java:comp/env/mail/Session");
```

#### Required Dependencies

In addition to adding [SAP Cloud SDK](../../README.md#5-replace-the-neo-java-web-api-with-the-sap-cloud-sdk) dependencies, also **add** the `javax.mail` dependency to your `pom.xml`:
```xml
<dependency>
    <groupId>com.sun.mail</groupId>
    <artifactId>javax.mail</artifactId>
    <version>[check-latest-version]</version>
</dependency>
```
> Note: Check the latest version in [JavaMail API | Maven Repository](https://mvnrepository.com/artifact/com.sun.mail/javax.mail).

### Destination Handling
Use `com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor.getDestination` to directly access the destination.

### Retrieval of Properties
Use `com.sap.cloud.sdk.cloudplatform.connectivity.Destination.get` to retrieve specific properties associated with the destination.

#### Authentication
Use `javax.mail.Authenticator` to authenticate with the mail server. Retrieve the username and password from the destination properties, and pass them to the `Authenticator` to establish secure access for sending and receiving emails.

#### HTTP Client Handling
Use `javax.mail.Session.getInstance` to create a mail session using the retrieved destination properties and authentication credentials. This session allows interaction with the mail server.

### On-Premise Scenario
- Set the provider using `session.setProvider()` with `javax.mail.Provider` of type `javax.mail.Provider.Type.TRANSPORT`. This ensures that the mail session can communicate properly with the on-premise mail server.
- Use the [TCP Protocol | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/using-tcp-protocol-for-cloud-applications) to establish a secure connection with the internal mail server.

> Note: Ensure that you have removed all unused variables, functions, and import statements.

## Example

- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)
  - Covers `Internet` and `On-Premise` mail server scenarios.
  >Note: For the on-premise scenario, you need to set up the [Cloud Connector | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/cloud-connector).

## Related Information
- [Configure an SMTP Mail Destination | SAP Help Portal](https://help.sap.com/docs/build-process-automation/sap-build-process-automation/configuring-smtp-mail-destination)
- [JavaMail API | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-neo/javamail-api)
- [On-Premise Connectivity | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/on-premise)<br>
- [Using the TCP Protocol for Cloud Applications | SAP Help Portal](https://help.sap.com/docs/connectivity/sap-btp-connectivity-cf/using-tcp-protocol-for-cloud-applications)

## [Additional Scenarios](../../README.md#7-additional-scenarios)