[![REUSE status](https://api.reuse.software/badge/github.com/SAP-samples/btp-neo-java-app-migration)](https://api.reuse.software/info/github.com/SAP-samples/btp-neo-java-app-migration)


# Migration Guide for Java Applications from the Neo Environment to the Cloud Foundry Environment
Please visit the following links to check the migration process for the classic BTP Java application including the following parts
- Cloud SDK
- Security
- Connectivity
- and more

1) [SAP Java Buildpack v1 sample](https://github.com/SAP-samples/btp-neo-java-app-migration/tree/migration-example-old)
2) [SAP Java Buildpack v2 sample](https://github.com/SAP-samples/btp-neo-java-app-migration/tree/migration-example-new)

## Table of Contents
1. [Purpose](#1-purpose)

2. [Prerequisites](#2-prerequisites)

    2.1 [Install the Migration Tools](#21-install-the-migration-tools)

3. [Gather All Application Sources](#3-gather-all-application-sources)

    3.1 [Clone the Repo Containing the Source Code](#31-clone-the-repo-containing-the-source-code)

    3.2 [Make Sure Your Code Compiles](#32-make-sure-your-code-compiles)

    3.3 [Analyze the Dependencies of Your Java Application](#33-analyze-the-dependencies-of-your-java-applications)


4. [Migrate to Jakarta EE 10 and Java 25](#4-migrate-to-jakarta-ee-10-and-java-25)
  
5. [Replace the Neo Java Web API with the SAP Cloud SDK](#5-replace-the-neo-java-web-api-with-the-sap-cloud-sdk)

6. [Set Up Authentication and Authorization](#6-set-up-authentication-and-authorization)

7. [Additional Scenarios](#7-additional-scenarios)

   - [Destinations](scenarios/destinations)

   - [Connectivity](scenarios/connectivity)
   
   - [Mail Session](scenarios/mail)

   - [Persistence](scenarios/persistence)

   - [Document Management Service](scenarios/document-management)

   - [Keystore Service](scenarios/keystore)
   
      - [Keystore API](scenarios/keystore/keystore-api)
     
      - [Storing Passwords](scenarios/keystore/storing-passwords)

   - [Custom Domain Setup](scenarios/custom-domain)

   - [Monitoring Applications](scenarios/monitoring)
   
   - [TomEE Runtime](scenarios/tomee)

8. [Deploy the Java Application](#8-deploy-the-java-аpplication)

    8.1 [Prepare the MTA Deployment Descriptor File](#81-prepare-the-mta-deployment-descriptor-file)

    8.2 [Deploy the Java Application to the Cloud Foundry Environment](#82-deploy-the-java-application-to-the-cloud-foundry-environment)

9. [Access the Java Application](#9-access-the-java-application)

10. [AI Assisted Migration](#10-ai-assisted-migration)

## 1. Purpose

This guide describes how you should refactor your Java applications to migrate them from the SAP BTP, Neo environment to the SAP BTP, Cloud Foundry environment.


## 2. Prerequisites

### 2.1 Install the Migration Tools

- Java
- [Maven | maven.apache.org](https://maven.apache.org/)
- [GIT | git-scm.com](https://git-scm.com/)
- [Cloud Foundry CLI | Cloud Foundry Documentation](https://docs.cloudfoundry.org/cf-cli/install-go-cli.html)
- [MultiApps CF CLI Plugin | github.com](https://github.com/cloudfoundry/multiapps-cli-plugin)

## 3. Gather All Application Sources

To begin the refactoring process, gather the whole source code of your Java applications.

### 3.1 Clone the Repo Containing the Source Code

If the source code is stored in a Git repository, you can use the following command to create a local copy of the repository:
```sh
git clone <repository URL>
```

### 3.2 Make Sure Your Code Compiles 
To do so, navigate to the root directory of the project and run the following command:
```sh
mvn clean install
```

If the code does not compile after running the command, follow these steps to identify and resolve the issues:

1. Review the error messages.

    To understand why you have received these error messages, read them carefully in the terminal. Look for specific file names, line numbers, and error descriptions.

2. Check the dependencies of your Java applications.
    - Verify that the versions of these dependencies are compatible with your local development environment (Java, Maven).
    - If there are any conflicting dependencies, resolve the conflicts by excluding the conflicting versions or updating the affected dependencies to compatible versions.

3. Check the compatibility of your Java version.

    Ensure that the Java version specified in the `pom.xml` file is compatible with the version installed on your local file system. The Java version specified in the project can be the same, or older than the one you have installed.

4. Check if the codebase is incomplete.
    
    The repository might not include all the necessary source files, or there might be uncommitted changes not reflected in the cloned repository.

### 3.3 Analyze the Dependencies of Your Java Applications

Check if there is a `com.sap.cloud:neo-java-web-api` dependency. Ensure that it is included in the source code, not just in build configurations. You can find the project dependencies in the `pom.xml` file, or use the following command:
```sh
mvn dependency:list
```
If any necessary dependencies or sources are missing, search for additional repositories using the GitHub API or by manually searching within any approved corporate or public GitHub repositories. These repositories can serve as parent repositories or dependencies. Once you find the target repository, clone it, and analyze its dependencies.<br>

> Note: Ensure that the versions of the source components match the required dependency versions. If the versions do not match, locate the correct version in the repository's history and clone it. You may need to checkout a specific commit, which contains the required version.<br>

> Note: Please have in mind that you may also need to clone some other dependencies. These are usually dependencies whose `groupId` starts with the same top-level domain and first subdomain as the project's `groupId`. For example, if the project's `groupId` starts with `org.example`, you may also need to clone all dependencies that start with `org.example`.

## 4. Migrate to Jakarta EE 10 and Java 25

You can migrate your Java application to Java 25 to improve its performance, security, and maintainability. OpenRewrite recipes are used to perform the migration. For more information, see [Migration to Jakarta EE 10 and Java 25](scenarios/jakarta10-and-java25-migration).

## 5. Replace the Neo Java Web API with the SAP Cloud SDK
The Neo Java Web API dependency is not provided in Cloud Foundry, so it must be replaced by SAP Cloud SDK dependencies.<br>

### 5.1 **Remove** the following dependency from the `pom.xml` file: <br>
```xml
<dependency>
    <groupId>com.sap.cloud</groupId>
    <artifactId>neo-java-web-api</artifactId>
    <version>[your version]</version>
    <scope>provided</scope>
</dependency>
```

### 5.2 If you find the following dependency in the `pom.xml`, **removed** it:
```xml
<dependency>
    <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
    <artifactId>scp-neo</artifactId>
    <version>[your version]</version>
</dependency>
```

### 5.3 **Add** the following dependencies in the `<dependencyManagement>` section:<br>
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.sap.cloud.sjb.cf</groupId>
            <artifactId>cf-tomcat-bom</artifactId>
            <version>${cf-tomcat-bom-version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.sap.cloud.sdk</groupId>
            <artifactId>sdk-modules-bom</artifactId>
            <version>${sdk-modules-bom-version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 5.4 **Add** the versions of the dependencies in the `<properties>` section:<br>
```xml
<properties>
    ...
    <cf-tomcat-bom-version>Add version here</cf-tomcat-bom-version>
    <sdk-modules-bom-version>Add version hеrе</sdk-modules-bom-version>
    ...
</properties>
```

> Note: The `cf-tomcat-bom` file controls the versions of the CF Tomcat BOM. For more information about the versions, see [https://mvnrepository.com/artifact/com.sap.cloud.sjb.cf/cf-tomcat-bom](https://mvnrepository.com/artifact/com.sap.cloud.sjb.cf/cf-tomcat-bom). Note that v1.*.* is compatible with Java 8.<br>

> Note: The `sdk-modules-bom` file is the Bill of Materials (BOM) of the SAP Cloud SDK modules. For more information about the versions, see [https://mvnrepository.com/artifact/com.sap.cloud.sdk/sdk-modules-bom](https://mvnrepository.com/artifact/com.sap.cloud.sdk/sdk-modules-bom). Note that v4.*.* and below are compatible with Java 8.<br>

### 5.5 **Add** the following dependency in the `<dependencies>` section:<br>
```xml
<dependencies>
    <dependency>
        <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
        <artifactId>scp-cf</artifactId>
    </dependency>
    ...
</dependencies>
```

> Note: You may need to add some of the dependencies provided by the `neo-java-web-api`.
> - `org.apache.chemistry.opencmis:chemistry-opencmis-commons-api`<br>
> - `org.apache.chemistry.opencmis:chemistry-opencmis-client-api`<br>
> - `jakarta.servlet:jakarta.servlet-api`<br>
> - `jakarta.servlet.jsp:jakarta.servlet.jsp-api`<br>
> - `jakarta.annotation:jakarta.annotation-api`<br>
> - `jakarta.mail:jakarta.mail-api`<br>
> - `jakarta.el:jakarta.el-api`<br>
> - `jakarta.websocket:jakarta.websocket-api`<br>
>
> Keep in mind that the `jakarta.*` dependencies are used starting from version `5.x` of the `neo-java-web-api`.
> If you are using a version below `5.x`, the `javax.*` dependencies apply instead.
> It is recommended to migrate to `jakarta.*` dependencies where possible for forward compatibility.

## 6. Set Up Authentication and Authorization

For more information, see [Authentication and Authorization](scenarios/authentication-and-authorization).

### Related Information
- [SAP Cloud SDK for Java | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/overview-cloud-sdk-for-java)
- [Bill Of Materials | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/bill-of-materials-bom)
- [SAP Cloud SDK BOM | sap.github.io](https://sap.github.io/cloud-sdk/docs/java/guides/manage-dependencies)

## 7. Additional Scenarios

This section contains a list of additional scenarios that depending on your business case may or may not be relevant to the migration process of your Java application. Each scenario contains guidelines and examples for refactoring your application.

- [Destinations](scenarios/destinations)

  You can use destinations to define connections for outbound communication from your Java application to remote systems.

- [Connectivity](scenarios/connectivity)

  In the Cloud Foundry environment, you can use the SAP Cloud SDK to establish a connection between an on-premise system and Cloud Connector. The SAP Cloud SDK provides an API to consume destinations created in the SAP BTP cockpit.

- [Mail Session](scenarios/mail)

  You can create Internet or on-premise mail sessions to a mail server.

- [Persistence](scenarios/persistence)

  You can configure your Java application to use a database connection so that the application can persist its data. 

- [Document Management Service](scenarios/document-management)

  You can use the Document Management service to manage the complete lifecycle of documents, from storage and retrieval to version control and secure sharing.
- [Keystore Service](scenarios/keystore)

  The Keystore service provides a repository for cryptographic keys and certificates to the applications in the Neo environment of SAP BTP.

  - [Keystore API](scenarios/keystore/keystore-api)

    The Кeystore API provides a repository for cryptographic keys and certificates to the applications in the Neo environment. It allows you to manage keystores at subaccount, application or subscription level.

  - [Storing Passwords](scenarios/keystore/storing-passwords)

    Using the Password Storage API, you can securely persist passwords and key phrases such as passwords for keystore files.
  
- [Custom Domain Setup](scenarios/custom-domain)

  You can use the SAP Custom Domain service to configure a custom domain for your Java application.

- [Monitoring Applications](scenarios/monitoring)

  You can use the SAP Cloud Logging service to monitor the performance of your Java application.

- [TomEE Runtime](scenarios/tomee)

  You can use the TomEE 10 container to run your JavaEE application in the Cloud Foundry environment.


## 8. Deploy the Java Аpplication
### 8.1 Prepare the MTA Deployment Descriptor File

The `mtad.yaml` file is the deployment descriptor for multitarget applications (MTA). It is a YAML file that describes the structure of the MTA, including the modules, resources, and dependencies. The `mtad.yaml` file is used by the Cloud Foundry CLI to deploy the MTA to the Cloud Foundry environment.

For more information on how to create a `mtad.yaml` deployment file, see [Multitarget Application Structure | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/multitarget-application-structure).
You can find an example of an MTA deployment descriptor in [MTA Deployment Descriptor Examples | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/mta-deployment-descriptor-examples).

- MTA Deployment Descriptor Template

  This is a template for an MTA deployment descriptor for a Java application:

  ```yaml
  _schema-version: "3.2"
  version: 0.0.1
  ID: <id>
  modules:
    - name: <app-name>
      type: java.tomcat
      path: <path-to-war-file> # relative to the location of the mtad.yaml
      parameters:
        buildpack: sap_java_buildpack_jakarta
        disk-quota: <disk-quota>
        memory: <memory>
      properties:
        ENABLE_SECURITY_JAVA_API_V2: true
        SET_LOGGING_LEVEL: 'ROOT: INFO'
        ...
      requires:
        - name: <service-name>
        ...
      provides:
        - name: <app-name>-java-app
        ...
    ...

  resources:
    - name: <service-name>
      type: <service-type>
      parameters:
        ...
    ...
  ```

- MTA Deployment Descriptor Example

  Here you can see a sample `mtad.yaml` file that outlines the necessary configurations for deploying a Java application along with an approuter module. It includes the declaration of service instances for authentication, destinations, connectivity, SAP HANA database, and Document Management service, as well as the required properties and parameters for each module.

  ```yaml
  _schema-version: "3.2"
  version: 0.0.1
  ID: <id>
  modules:
    - name: <app-name>
      type: java.tomcat
      path: <path-to-war-file> # relative to the location of the mtad.yaml
      parameters:
        buildpack: sap_java_buildpack_jakarta
        disk-quota: 512MB
        memory: 512MB
      properties:
        ENABLE_SECURITY_JAVA_API_V2: true
        SET_LOGGING_LEVEL: 'ROOT: INFO'
        # Needed for persistence:
        JBP_CONFIG_RESOURCE_CONFIGURATION:
          - tomcat/webapps/ROOT/META-INF/context.xml:
              service_name_for_DefaultDB: <app-name>-hana
      requires:
        - name: <app-name>-xsuaa        # Needed for authentication
        - name: <app-name>-destination  # Needed for authentication or connectivity
        - name: <app-name>-connectivity # Needed for connectivity
        - name: <app-name>-sdm          # Needed for document management
        - name: <app-name>-hana         # Needed for persistence
      provides:
        - name: <app-name>-java-app
          properties:
            java_app_url: '${default-url}'
    # Needed for authentication:
    - name: <app-name>-approuter
      type: nodejs
      path: approuter
      parameters:
        disk-quota: 256M
        memory: 256M
        # Optional: this sets a custom route (domain) for the application
        routes:
          - route: '${protocol}://<app-name>.${default-domain}' # Can be adjusted
            protocol: http1
      properties:
        XS_APP_LOG_LEVEL: debug
      requires:
        - name: <app-name>-xsuaa
        - name: <app-name>-java-app
          group: destinations
          properties:
            name: <app-name>-destination
            url: '~{java_app_url}' # The URL to the Java application
            forwardAuthToken: true

  resources:
    # Needed for authentication:
    - name: <app-name>-xsuaa
      type: org.cloudfoundry.managed-service
      parameters:
        service: xsuaa
        service-plan: application
        path: ./xs-security.json
    # Needed for authentication or connectivity:
    - name: <app-name>-destination
      type: org.cloudfoundry.managed-service
      parameters:
        service: destination
        service-plan: lite
    # Needed for connectivity:
    - name: <app-name>-connectivity
      type: org.cloudfoundry.managed-service
      parameters:
        service: connectivity
        service-plan: lite
    # Needed for document management:
    - name: <app-name>-sdm
      type: org.cloudfoundry.managed-service
      parameters:
        service: sdm
        service-plan: standard
    # Needed for persistence:
    - name: <app-name>-hana 
      type: com.sap.xs.hana-schema
  ```

### 8.2 Deploy the Java Application to the Cloud Foundry Environment
You can deploy the application by executing the following commands:

1. Build the application:
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

## 9. Access the Java Application
You can access the application in a browser using the application URL. You can find the application URL on the **Overview** page of your application in the SAP BTP cockpit.
The format of the application URL is `https://<app-name>.cfapps.<cf-app-domain>`.

## 10. AI Assisted Migration
You can perform an AI assisted migration using the [AI Assisted Migration](./ai-migration/README.md) guide.
