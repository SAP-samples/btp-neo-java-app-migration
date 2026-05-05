# TomEE Runtime

## Table of Contents
- [Overview](#overview)
- [Refactoring](#refactoring)
- [Deployment](#deployment)
- [Example of Persistence with Enterprise JavaBeans (EJB)](#example-of-persistence-with-enterprise-javabeans-ejb)
- [Related Information](#related-information)


## Overview
You can migrate your application from the TomEE runtime in the Neo environment to the TomEE runtime in the Cloud Foundry environment.

Web applications deployed with SAP Java Buildpack 2 can run in an Apache TomEE 10 container.

## Refactoring
To migrate your application to the TomEE runtime in the Cloud Foundry environment, replace the Neo Java Web API with the following dependencies:
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.sap.cloud.sjb.cf</groupId>
      <artifactId>cf-tomee-bom</artifactId>
      <version>${cf-tomee-bom-version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    ...
  </dependencies>
</dependencyManagement>
```
>Note: You can find the full list of dependencies needed for the migration in [Replace the Neo Java Web API with the SAP Cloud SDK](../../README.md#5-replace-the-neo-java-web-api-with-the-sap-cloud-sdk).

## Deployment

Applications can explicitly define the target application container by using the TARGET_RUNTIME environment variable in the application's mtad.yml file.

Example:
```yaml
modules:
- name: myapp
  ...
  parameters:
    buildpack: sap_java_buildpack_jakarta
    ...
  properties:
    TARGET_RUNTIME: tomee
```

You can find more information about the TomEE 10 container in [TomEE-10 documentation | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/tomee-10).

## Example of Persistence with Enterprise JavaBeans (EJB)
This example shows how to configure the SAP HANA Cloud service instance in the TomEE 10 container.

- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)


  The following steps are executed in the `cf` folder:
1. Remove the `neo-javaee7-wp-api` dependency from the `pom.xml` file:
```xml
<dependencies>
  <dependency>
    <groupId>com.sap.cloud</groupId>
    <artifactId>neo-javaee7-wp-api</artifactId>
    <version>${neo.javaee7.version}</version>
    <scope>provided</scope>
  </dependency>
  ...
</dependencies>
```

2. Add the following dependencies in the `<dependencyManagement>` section:
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.sap.cloud.sjb.cf</groupId>
      <artifactId>cf-tomee-bom</artifactId>
      <version>${cf-tomee-bom-version}</version>
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
  ...
</dependencyManagement>
```

3. Create an SAP HANA Cloud service instance and a service binding by adding the `com.sap.xs.hana-schema` resource in the `mtad.yaml` file:
```yaml
resources:
  ...
  - name: <service-instance-name>
    type: com.sap.xs.hana-schema
  ...
```

4. Create the file structure `webapp/META-INF/sap_java_buildpack/config/resource_configuration.yml` with the following content:
```yaml
---
tomee/webapps/ROOT/WEB-INF/resources.xml:
  service_name_for_DefaultDB: <service-instance-name>
```

5. Under `webapp/WEB-INF`, create a `resources.xml` file:
```xml
<?xml version='1.0' encoding='utf-8'?>

<resources>
  <Resource id="jdbc/DefaultDB"
            provider="xs.openejb:XS Default JDBC Database"
            type="javax.sql.DataSource">
    service=${service_name_for_DefaultDB}
  </Resource>
</resources>
```

6. Add the following line to the application module's properties in the `mtad.yaml` file. This is how the name of the service instance is passed to the application.
```yaml
JBP_CONFIG_RESOURCE_CONFIGURATION:
  - tomee/webapps/ROOT/WEB-INF/resources.xml:
      service_name_for_DefaultDB: <service-instance-name>
```
## Related Information
- [TomEE-10 documentation| SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/tomee-10)
- [Configure a Database Connection for TomEE Application Container | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/configure-database-connection-for-tomee-7-application-container?q=tomee)

