# Persistence

## Table of Contents
- [Configuration](#configuration)
- [Example](#example)
- [Related Information](#related-information)
- [Additional Scenarios](#additional-scenarios)

## Configuration
1. **Remove** the following `<resource-ref>` from `src/main/webapp/WEB-INF/web.xml`:<br>
```xml
<resource-ref>
    <res-ref-name>[your-res-ref-name]</res-ref-name>
    <res-type>javax.sql.DataSource</res-type>
</resource-ref>
```

2. **Create** an SAP HANA Cloud service instance and a service binding, choose type `schema` or specify it as a resource in `mtad.yaml`.<br>
> Add the following resource in `mtad.yaml` to declare the SAP HANA Cloud service instance:
```yaml
resources:
  ...
  - name: <service-instance-name>
    type: com.sap.xs.hana-schema
  ...
```
> Note: We suggest `<service-instance-name>` to follow the `<app-name>-hana` structure, but you can choose your own name.
- **Create** the following file structure `webapp/META-INF/sap_java_buildpack/config/resource_configuration.yml`, and put the following content:
```yaml
---
tomcat/webapps/ROOT/META-INF/context.xml:
  service_name_for_DefaultDB: <service-instance-name>
```
> Note: Provide the name of the created SAP HANA Cloud service instance.

3. Under `webapp/META-INF`, create a `context.xml` file:<br>
```xml
<?xml version='1.0' encoding='utf-8'?>

<Context>
    <Resource name="[resource-name]"
              auth="Container"
              type="javax.sql.DataSource"
              factory="com.sap.xs.jdbc.datasource.tomcat.TomcatDataSourceFactory"
              service="${service_name_for_DefaultDB}"/>
</Context>
```
> Note: Choose a name for the resource, for example `jdbc/DefaultDB`, as it is on the SAP BTP, Neo environment by default.

4. **Add** the following line to the application module's properties in the `mtad.yaml` file. This is how the name of the service instance is passed to the application.<br>
```yaml
JBP_CONFIG_RESOURCE_CONFIGURATION: [ "tomcat/webapps/ROOT/META-INF/context.xml": { "service_name_for_DefaultDB": "<service-instance-name>" } ]
```
> Note: Place the name of the SAP HANA Cloud service instance here.<br>
> Note: For more details about creating and configuring the `mtad.yaml` file, see [Prepare the MTA Deployment Descriptor File](../../README.md#81-prepare-the-mta-deployment-descriptor-file).

## Example

- [Example for the Neo environment](./neo) (before the refactoring)
- [Example for the Cloud Foundry environment](./cf) (after the refactoring)

## Related Information
- [Configure a Database Connection for the Tomcat Application Container | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/configure-database-connection-for-tomcat-application-container)

## [Additional Scenarios](../../README.md#7-additional-scenarios)
