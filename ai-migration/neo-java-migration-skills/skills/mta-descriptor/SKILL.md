---
name: mta-descriptor
description: Invoke this skill to generate MTA deployment descriptor (mtad.yaml) for Cloud Foundry. Creates mtad.yaml with proper service bindings based on migration skills applied. Always invoke LAST after all other migration skills.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---

# MTA Descriptor Generation

Generate a Multi-Target Application deployment descriptor (`mtad.yaml`) for deploying Neo-migrated applications to SAP BTP Cloud Foundry.

## Usage


```
Generate an mtad.yaml for my migrated application
Create MTA descriptor with XSUAA and HANA services
```

## Overview

The MTA descriptor (`mtad.yaml`) defines:
- Application modules (Java app, approuter)
- Required Cloud Foundry services (XSUAA, HANA, Destination, etc.)
- Service bindings between modules and services
- Build and deployment parameters

This skill analyzes which migration skills were applied to your application and generates the appropriate `mtad.yaml` configuration.

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked all required migration skills:

1. **jakarta-java25-migration** - `Use the jakarta-java25-migration skill` (REQUIRED)
2. **sdk-replacement** - `Use the sdk-replacement skill` (REQUIRED)
3. Any feature skills that apply to your application

Also required:
- Application successfully compiles: `mvn clean compile`
- Understanding of which CF services your application requires

## Determine Required Services

Based on the migration skills you've applied, identify required CF services:

| Migration Skill Applied | Required CF Service | Service Type |
|------------------------|---------------------|--------------|
| authentication-xsuaa | XSUAA | `org.cloudfoundry.managed-service` |
| destinations | Destination | `org.cloudfoundry.managed-service` |
| connectivity-onpremise | Destination + Connectivity | `org.cloudfoundry.managed-service` |
| persistence-hana | HANA Schema | `com.sap.xs.hana-schema` |
| document-management-sdm | SDM | `org.cloudfoundry.managed-service` |
| mail-destinations | (uses Destination service) | - |
| keystore-credstore | Credential Store | `org.cloudfoundry.managed-service` |
| monitoring-logging | (built-in CF logging) | - |
| tomee-runtime | (requires TARGET_RUNTIME property) | - |

## MTA Templates

### Template Selection

Choose the appropriate base template:

| Scenario | Template | When to Use |
|----------|----------|-------------|
| Basic app without web authentication | [mtad-base.yaml](assets/mtad-base.yaml) | Internal services, background jobs, APIs with technical auth |
| App with XSUAA and Approuter | [mtad-with-approuter.yaml](assets/mtad-with-approuter.yaml) | User-facing web applications requiring authentication |
| TomEE application | Add `TARGET_RUNTIME: tomee` | Apps using EJB or requiring TomEE runtime |

### Template: Basic Application (mtad-base.yaml)

For applications without user authentication:

```yaml
_schema-version: "3.3"
ID: my-app
version: 1.0.0
parameters:
parameters:
  enable-parallel-deployments: true


modules:
  - name: my-app-backend
    type: java.tomcat
    path: target/ROOT.war

    parameters:
      memory: 1024M
      disk-quota: 1024M

      buildpack: sap_java_buildpack_jakarta
    properties:
      # Pin SapMachine JRE 25 explicitly. Always set this — never rely on the buildpack's
      # implicit JRE choice, and keep maven.compiler.target in pom.xml at the same major
      # version. Use SAPMachineJRE (bundled, offline) — NOT SAPMachineJDK (heavyweight,
      # online-only).
      JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
      JBP_CONFIG_SAP_MACHINE_JRE: "{ version: 25.+ }"
      TARGET_RUNTIME: tomcat
    build-parameters:
      builder: maven
      build-result: target/*.war
    provides:
      - name: backend-api
        properties:
          url: ${default-url}
    requires:
      # Add service bindings here based on needs
      - name: my-app-hana          # If using persistence-hana
      - name: my-app-destination   # If using destinations

resources:
  # Add required services here
```

### Template: Application with XSUAA (mtad-with-approuter.yaml)

For user-facing web applications:

```yaml
_schema-version: "3.2"
ID: my-app
version: 0.0.1

parameters:
  enable-parallel-deployments: true

modules:
  # Approuter module for authentication
  - name: my-app-approuter
    type: nodejs
    path: approuter
    parameters:
      memory: 256M
      disk-quota: 256M
      routes:
        - route: '${protocol}://my-app.${default-domain}'
          protocol: http1
    properties:
      XS_APP_LOG_LEVEL: debug
      TENANT_HOST_PATTERN: '(.*).cfapps.sap.hana.ondemand.com'
      CF_NODEJS_LOGGING_LEVEL: "info"
    requires:
      - name: my-app-xsuaa
      - name: my-app-java-app
        group: destinations
        properties:
          name: backend-app-destination
          url: '~{neo-app-url}'
          forwardAuthToken: true

  # Backend Java application
  - name: my-app-backend
    type: java.tomcat
    path: target/ROOT.war
    parameters:
      memory: 1024M
      disk-quota: 1024M
      buildpack: sap_java_buildpack_jakarta
    properties:
      ENABLE_SECURITY_JAVA_API_V2: true
      # Pin SapMachine JRE 25 explicitly. Always set this — never rely on the buildpack's
      # implicit JRE choice, and keep maven.compiler.target in pom.xml at the same major
      # version. Use SAPMachineJRE (bundled, offline) — NOT SAPMachineJDK (heavyweight,
      # online-only).
      JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
      JBP_CONFIG_SAP_MACHINE_JRE: "{ version: 25.+ }"
      TARGET_RUNTIME: tomcat
      SET_LOGGING_LEVEL: 'ROOT: INFO'
    provides:
      - name: my-app-java-app
        properties:
          neo-app-url: '${default-url}'
    requires:
      - name: my-app-xsuaa
      - name: my-app-destination

resources:
  - name: my-app-xsuaa
    type: org.cloudfoundry.managed-service
    parameters:
      service: xsuaa
      service-plan: application
      path: ./xs-security.json

  - name: my-app-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite

```

> **Critical:** The Java backend `path` must point to `target/ROOT.war` (not `target/myapp.war`). See "WAR context path" in Troubleshooting below.

> **Note on `type: nodejs` vs `type: approuter.nodejs`:** Use `type: nodejs` when deploying the approuter with your own `approuter/` directory and `package.json` (which is the standard pattern for migrated Neo applications). The type `approuter.nodejs` is for the "managed approuter" pattern which is used only in specific multi-tenant scenarios.

## Service Configuration Examples

Add these resource definitions based on services your application needs:

### XSUAA (Authentication)

```yaml
resources:
  - name: ${app-name}-xsuaa
    type: org.cloudfoundry.managed-service
    parameters:
      service: xsuaa
      service-plan: application
      path: ./xs-security.json  # Created by authentication-xsuaa skill
```

**Module requires:**
```yaml
requires:
  - name: ${app-name}-xsuaa
```

### Destination Service

```yaml
resources:
  - name: ${app-name}-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite
```

**Module requires:**
```yaml
requires:
  - name: ${app-name}-destination
```

### Connectivity Service (On-Premise)

```yaml
resources:
  - name: ${app-name}-connectivity
    type: org.cloudfoundry.managed-service
    parameters:
      service: connectivity
      service-plan: lite
```

**Module requires:**
```yaml
requires:
  - name: ${app-name}-connectivity
  - name: ${app-name}-destination  # Connectivity requires Destination
```

### HANA Schema

```yaml
resources:
  - name: ${app-name}-hana
    type: com.sap.xs.hana-schema
    parameters:
      schema-name: ${app-name}
    properties:
      hdi-service-name: ${service-name}
```

**Module requires:**
```yaml
requires:
  - name: ${app-name}-hana
    properties:
      TARGET_CONTAINER: ~{hdi-service-name}
```

### Document Management (SDM)

```yaml
resources:
  - name: ${app-name}-sdm
    type: org.cloudfoundry.managed-service
    parameters:
      service: sdm
      service-plan: standard
```

**Module requires:**
```yaml
requires:
  - name: ${app-name}-sdm
```

### Credential Store

```yaml
resources:
  - name: ${app-name}-credstore
    type: org.cloudfoundry.managed-service
    parameters:
      service: credstore
      service-plan: standard
      config:
        encryption:
          user_key_name: "credstore-key"
```

**Module requires:**
```yaml
requires:
  - name: ${app-name}-credstore
```

## Generation Steps

### 1. Analyze Application Requirements

Identify which services your application needs:

```bash
# Check for XSUAA dependencies
grep -r "com.sap.cloud.security.xsuaa" pom.xml

# Check for destination dependencies
grep -r "com.sap.cloud.sdk.cloudplatform.connectivity" pom.xml

# Check for HANA dependencies
grep -r "javax.sql.DataSource\|jakarta.persistence" src/main/java/

# Check for SDM dependencies
grep -r "com.sap.ecm.api" src/main/java/
```

### 2. Select Base Template

Copy the appropriate template:

```bash
# For basic app
cp skills/mta-descriptor/assets/mtad-base.yaml mtad.yaml

# For app with authentication
cp skills/mta-descriptor/assets/mtad-with-approuter.yaml mtad.yaml
```

### 3. Customize Application Properties

Update the `mtad.yaml` with your application details:

```yaml
ID: my-application-name          # Unique identifier
version: 1.0.0                    # Your app version

modules:
  - name: my-app-backend          # Your module name
    path: target/ROOT.war          # ALWAYS use ROOT.war — see note below
    parameters:
      memory: 1024M                # Adjust based on needs
      disk-quota: 1024M            # Minimum 1GB — 512M causes deployment errors
```

> **WAR naming:** The SAP Java buildpack deploys the WAR using its filename as the Tomcat context path. A WAR named `auth.war` is served at `/auth`, not `/`. Always configure `maven-war-plugin` with `<warName>ROOT</warName>` so the app is served at the root path, and reference `target/ROOT.war` in `mtad.yaml`.

### 4. Add Required Services

Based on your analysis in step 1, add the necessary service resources and module requirements.

**Example: App with XSUAA, Destination, and HANA:**

```yaml
modules:
  - name: my-app-backend
    # ... other properties ...
    requires:
      - name: my-app-xsuaa
      - name: my-app-destination
      - name: my-app-hana

resources:
  - name: my-app-xsuaa
    type: org.cloudfoundry.managed-service
    parameters:
      service: xsuaa
      service-plan: application
      path: ./xs-security.json

  - name: my-app-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite

  - name: my-app-hana
    type: com.sap.xs.hana-schema
```

### 5. Configure TomEE Runtime (if applicable)

If you used the tomee-runtime skill:

```yaml
modules:
  - name: my-app-backend
    properties:
      TARGET_RUNTIME: tomee        # Change from 'tomcat' to 'tomee'
```

### 6. Validate the Descriptor

Check the syntax and structure:

```bash
# Validate YAML syntax
yamllint mtad.yaml

# Or use basic validation
python3 -c "import yaml; yaml.safe_load(open('mtad.yaml'))"
```

## Build and Deploy

After generating the `mtad.yaml`:

### Build MTA Archive

```bash
# Build the MTA archive
mbt build

# This creates: mta_archives/my-app_1.0.0.mtar
```

### Deploy to Cloud Foundry

```bash
# Deploy using MultiApps plugin
cf deploy mta_archives/my-app_1.0.0.mtar

# Or deploy directly from current directory
cf deploy .
```

### Verify Deployment

```bash
# Check application status
cf apps

# Check service bindings
cf services

# View recent logs
cf logs my-app-backend --recent
```

## Common Configurations

### Multi-Module Application

```yaml
modules:
  - name: my-app-ui
    type: html5
    path: ui-module

  - name: my-app-backend
    type: java
    path: backend-module
    provides:
      - name: backend-api
        properties:
          url: ${default-url}

  - name: my-app-worker
    type: java
    path: worker-module
    requires:
      - name: backend-api
```

### Environment Variables

```yaml
modules:
  - name: my-app-backend
    properties:
      MY_CUSTOM_VAR: "production-value"
      SPRING_PROFILES_ACTIVE: "cloud"
      JBP_CONFIG_RESOURCE_CONFIGURATION: "[tomcat/webapps/ROOT/WEB-INF/web.xml: {'web-app/session-config/session-timeout': 60}]"
```

### Health Check Configuration

```yaml
modules:
  - name: my-app-backend
    parameters:
      health-check-type: http
      health-check-http-endpoint: /actuator/health
      health-check-timeout: 180
```

## Troubleshooting

### Issue: Service binding fails

**Symptom:** Application fails to bind to services during deployment

**Solution:** Verify service names in `requires` match service names in `resources`:

```yaml
# Resource name
resources:
  - name: my-app-xsuaa  # This name...

# Must match in module requires
modules:
  - name: my-app-backend
    requires:
      - name: my-app-xsuaa  # ...must match exactly here
```

### Issue: Module fails to build

**Symptom:** Build errors during `cf deploy`

**Solution:** Verify build parameters:

```yaml
modules:
  - name: my-app-backend
    build-parameters:
      builder: maven               # Or 'npm', 'custom'
      build-result: target/*.war   # Verify path is correct
```

### Issue: Memory errors

**Symptom:** Application crashes with out-of-memory errors

**Solution:** Increase memory allocation:

```yaml
modules:
  - name: my-app-backend
    parameters:
      memory: 2048M      # Increase from 1024M
      disk-quota: 1024M  # Never use 512M — minimum is 1024M for Java buildpack
```

### Issue: 404 Not Found — app deployed but all routes return 404

**Symptom:** App starts successfully but every request returns 404. Logs show Tomcat started but requests don't match any servlet.

**Cause:** WAR is named `myapp.war` so Tomcat serves it at `/myapp`, not `/`. All servlet URL patterns are relative to the context path.

**Solution:** Configure `maven-war-plugin` to produce `ROOT.war`:

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-war-plugin</artifactId>
    <version>3.4.0</version>
    <configuration>
        <warName>ROOT</warName>
    </configuration>
</plugin>
```

Update `mtad.yaml` path to `target/ROOT.war` and rebuild.

> **Note:** `<finalName>` in `<build>` does NOT control the WAR name — only `<warName>` in the plugin configuration does.

### Issue: Routes quota exceeded

**Symptom:** `cf deploy` fails with `Routes quota exceeded for organization '<org>'. quota: SUBSCRIPTION_QUOTA — total memory: 0, routes: 0`

**Cause:** The CF org uses `SUBSCRIPTION_QUOTA` which has 0 routes and 0 memory by default. This is set by SAP's entitlement system.

**Solution:** A Global Account Administrator must assign CF Runtime memory in BTP Cockpit:
1. BTP Cockpit → Global Account → Entitlements → Entity Assignments
2. Select the subaccount → Configure Entitlements → Cloud Foundry Runtime → assign memory (e.g. 3GB)
3. BTP automatically creates a new org quota named `<guid>-t-<subaccount-guid>` and applies it

**Note:** `cf create-space-quota` and `cf create-quota` do NOT fix this — org-level route limits cannot be overridden by space quotas, and creating new org quotas requires CF admin rights.

### Issue: maven-war-plugin fails with `Cannot access defaults field of Properties`

**Symptom:** Maven build fails with this error when using Java 25.

**Cause:** Default `maven-war-plugin:2.2` is incompatible with Java 25.

**Solution:** Explicitly pin `maven-war-plugin` to `3.4.0` in `pom.xml`.

## Integration with CF Plugin

After generating the `mtad.yaml`, use the cf-plugin skills for deployment:

```
# Use cf-plugin to deploy the MTA
/sap-btp-cf:cf-push-app my-app

# View deployment logs
/sap-btp-cf:cf-get-app-logs my-app
```

## Post-Deployment Configuration

### Assign Role Collections (XSUAA)

If using authentication-xsuaa:

1. Open SAP BTP Cockpit
2. Navigate to Security → Role Collections
3. Assign role collection to users/user groups
4. Verify by logging into the application

### Create Destinations (Destination Service)

If using destinations skill:

1. Open SAP BTP Cockpit
2. Navigate to Connectivity → Destinations
3. Create destinations as defined in the destinations skill
4. Test connectivity from the application

### Configure Cloud Connector (Connectivity)

If using connectivity-onpremise:

1. Install and configure Cloud Connector
2. Add virtual hosts matching destination configuration
3. Map to on-premise systems
4. Test connectivity

## See Also

- [authentication-xsuaa/SKILL.md](../authentication-xsuaa/SKILL.md) - For xs-security.json and xs-app.json
- [destinations/SKILL.md](../destinations/SKILL.md) - For destination configuration
- [connectivity-onpremise/SKILL.md](../connectivity-onpremise/SKILL.md) - For Cloud Connector setup
- [persistence-hana/SKILL.md](../persistence-hana/SKILL.md) - For HANA configuration
- [tomee-runtime/SKILL.md](../tomee-runtime/SKILL.md) - For TomEE-specific configuration

## References

- [MTA Development and Deployment](https://help.sap.com/docs/BTP/65de2977205c403bbc107264b8eccf4b/d04fc0e2ad894545aebfd7126384307c.html)
- [MTA Deployment Descriptor Syntax](https://help.sap.com/docs/BTP/65de2977205c403bbc107264b8eccf4b/4050fee4c469498ebc31b10f2ae15ff2.html)
- [SAP Java Buildpack](https://help.sap.com/docs/BTP/65de2977205c403bbc107264b8eccf4b/0eca8c87a5f94f3ba8c83c6ff2d5e463.html)
