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

### Precondition — `pom.xml` MUST configure `maven-war-plugin` with `<warName>${project.artifactId}</warName>`

> **This skill owns the WAR filename rule (next section), which only holds
> if `pom.xml` actually writes the WAR under `<artifactId>.war`.** Maven's
> default WAR filename is `<artifactId>-<version>.war`, which does NOT
> match what this skill writes into `mtad.yaml` (`target/<artifactId>.war`)
> — the deploy then fails with:
>
> > `Error retrieving MTA: Error building MTA Archive:`
> > `file path .../target/<artifactId>.war not found`
>
> The other migration skills (`sdk-replacement`, `tomee-runtime`, …) are
> dependency-management skills and do not own build packaging. Verify and,
> if needed, add the `<build>` block below to `pom.xml` **before** emitting
> the descriptor.

Required `<build>` block in `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-war-plugin</artifactId>
            <version>3.4.0</version>
            <configuration>
                <warName>${project.artifactId}</warName>
                <failOnMissingWebXml>false</failOnMissingWebXml>
            </configuration>
        </plugin>
    </plugins>
</build>
```

> **Why `3.4.0` specifically:** the default `maven-war-plugin:2.2` is
> incompatible with Java 25 and fails with `Cannot access defaults field
> of Properties`. The Common Issues section below has the same fix.

**Self-check before continuing** — run this from the project root; it
must print exactly `1`:

```bash
grep -A 3 'maven-war-plugin' pom.xml | grep -c '<warName>${project.artifactId}</warName>'
```

If the count is `0`, the plugin is missing or `<warName>` is set to
something else (often the leftover `<warName>ROOT</warName>` from older
templates). Fix `pom.xml` before emitting `mtad.yaml`; the build will
otherwise succeed but the descriptor and the WAR will disagree at deploy
time.

If `pom.xml` legitimately needs a different `<warName>` (e.g. the project
must serve at `/` and uses `<warName>ROOT</warName>`), update the
descriptor's `path:` to match — the rule is "descriptor follows pom",
not "pom follows descriptor".

### WAR filename rule — read it from `pom.xml`, write it literally

The templates below show `path: target/<artifactId>.war` as a placeholder.
**Before emitting `mtad.yaml`, open `pom.xml`, read the `<artifactId>`
value, and substitute it literally** — for a project whose `<artifactId>`
is `connectivity`, write `path: target/connectivity.war`.

Why this matters in concrete terms:

- `cf deploy` reads `path:` as a **literal filesystem path** under the
  descriptor's directory. It does NOT evaluate Maven property
  expressions, so a descriptor that says `path: target/${project.artifactId}.war`
  fails at deploy time with `Error building MTA Archive: file path
  target/${project.artifactId}.war not found`.
- `maven-war-plugin` in the shipped pom templates is configured with
  `<warName>${project.artifactId}</warName>` — Maven writes a WAR named
  after the artifactId (e.g. `target/connectivity.war`). The descriptor
  must match what Maven actually writes, not a placeholder string.

**Consequence to communicate downstream:** the SAP Java buildpack serves
a WAR at the Tomcat context path derived from its filename. A WAR
named `connectivity.war` is served at `/connectivity` — **not at `/`**.
Integration tests that target the app and any approuter destinations
must include the `/<artifactId>` prefix in their URLs. The
`mta-descriptor` skill does not paper over this — it leaves the WAR
naming aligned with `pom.xml` and surfaces the prefix in the deploy log.

This is a deliberate choice. Previously the descriptor mandated
`target/ROOT.war` to get `/`-served apps, which created the inverse
failure mode whenever `pom.xml` didn't have `<warName>ROOT</warName>`
(the more common case in practice). The current rule — descriptor
follows pom — never has a mismatch.

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
    path: target/<artifactId>.war      # ← substitute the literal artifactId from pom.xml

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
    path: target/<artifactId>.war      # ← substitute the literal artifactId from pom.xml
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

> **Critical:** The Java backend `path` must point to the actual WAR file Maven produces — read the `<artifactId>` from `pom.xml` and substitute it literally (e.g. `path: target/connectivity.war` for a project whose `artifactId` is `connectivity`). See the "WAR filename rule" callout above this section. The app will serve at `/<artifactId>`; ensure tests and approuter destinations use that prefix.

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

> **CRITICAL — do NOT add `service: hana` or `service-plan: hdi-shared`
> under `type: com.sap.xs.hana-schema`.**
>
> ❌ Anti-pattern that breaks deploy:
> ```yaml
> - name: ${app-name}-hana
>   type: com.sap.xs.hana-schema
>   parameters:
>     service: hana              # ← WRONG for hana-schema
>     service-plan: hdi-shared   # ← WRONG for hana-schema
> ```
> `service:` and `service-plan:` are keys for the GENERIC
> `type: org.cloudfoundry.managed-service` (xsuaa, destination, credstore,
> sdm, …). `com.sap.xs.hana-schema` is a dedicated MTA resource type that
> already implies the broker, the service, and the plan. Setting them
> explicitly causes the controller to reject the create-service call:
>
> > `CF-UnprocessableEntity(10008): Invalid service plan. ... Ensure that
> > the service plan is visible in your current space ...`

```yaml
resources:
  - name: ${app-name}-hana
    type: com.sap.xs.hana-schema
```

> **NO `parameters:` block on the hana-schema resource.** Specifically, do
> NOT set `schema-name:`, `service:`, or `service-plan:` here. MTA's
> `com.sap.xs.hana-schema` type does not support any of these at the
> resource level — the deployer drops them with
> `Parameter(s) "{<name>-hana=[schema-name]}" are not supported in the
> specified scope, or referenced by any other entities. These parameters
> are not processed and will be lost`. The deploy still completes, but
> the warning is noisy and signals a malformed descriptor. The reference
> scenarios use only the two-line form above.

**Module requires:**
```yaml
requires:
  - name: ${app-name}-hana
```

The bare `- name:` is enough — MTA binds the resource into `VCAP_SERVICES`
under the resource's `name:`, and Tomcat's `context.xml` (or
`resources.xml` for TomEE) looks up the DataSource by that same name via
`service_name_for_DefaultDB`. No `~{...}` cross-references or
`TARGET_CONTAINER` parameters are needed for the standard persistence
path.

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
    path: target/<artifactId>.war  # Substitute the literal artifactId from pom.xml
    parameters:
      memory: 1024M                # Adjust based on needs
      disk-quota: 1024M            # Minimum 1GB — 512M causes deployment errors
```

> **WAR naming:** The descriptor's `path:` must match exactly what Maven writes to `target/`. The pom templates ship `maven-war-plugin` with `<warName>${project.artifactId}</warName>`, so the WAR is named after the artifactId (e.g. `target/connectivity.war`). Open `pom.xml`, read the `<artifactId>` value, and write it literally into `mtad.yaml` — Maven property expressions like `${project.artifactId}` are NOT evaluated by `cf deploy` and will fail with "file path … not found".
>
> **Side effect:** the SAP Java buildpack serves a WAR at the Tomcat context path matching its filename, so the app will be reachable at `/<artifactId>`, not `/`. Integration tests and approuter destinations must include that prefix.

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

### Deploy to Cloud Foundry

#### Primary flow — `cf deploy .` (recommended)

```bash
# Deploy directly from current directory
# cf deploy reads mtad.yaml and builds the MTA archive in-process
cf deploy . -f
```

> **This is the correct flow for migrated Neo applications.** This skill generates `mtad.yaml` (a deployment descriptor), which `cf deploy .` reads directly. All 8 reference scenarios use this command.

#### CI/CD / archive flow — `mbt build` (requires extra step)

If you need a versioned `.mtar` archive (e.g. for CI/CD pipelines or distributing to other teams), you must first manually create an `mta.yaml` source descriptor — `mbt build` requires it and will fail without it. This skill does **not** generate `mta.yaml`.

```bash
# 1. Manually create mta.yaml (not generated by this skill)
# 2. Build the MTA archive
mbt build
# This creates: mta_archives/<app>_<version>.mtar

# 3. Deploy the archive
cf deploy mta_archives/*.mtar -f
```

> **`mta.yaml` vs `mtad.yaml`:** `mta.yaml` is a source descriptor read by `mbt build` to compile and package the app. `mtad.yaml` is a deployment descriptor read directly by `cf deploy`. They serve different purposes — having one does not substitute for the other.

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

### Issue: `cf deploy` fails immediately with `Error building MTA Archive: file path … not found`

**Symptom:** `cf deploy` exits non-zero within seconds, before any service is touched, with:

> `FAILED`
> `Error retrieving MTA: Error building MTA Archive:`
> `file path <project>/cf-ai-migrated/target/<X>.war not found`

The Maven build step earlier in the pipeline reported `BUILD SUCCESS`. The build log line `[INFO] Building war: …/target/<Y>.war` shows a different filename than the descriptor declares.

**Cause:** `mtad.yaml`'s `path:` doesn't match what Maven actually writes. Two flavors:

1. The descriptor literally contains `path: target/${project.artifactId}.war` — Maven property expressions are NOT evaluated by `cf deploy`, so the literal string `${project.artifactId}` is looked up on disk and not found.
2. The descriptor is left over from an older template that hard-coded `target/ROOT.war`, but the pom is configured with `<warName>${project.artifactId}</warName>` (the current default in this skill's templates) and writes `target/<artifactId>.war`.

**Solution:** Open `pom.xml`, read the `<artifactId>` value, and substitute it literally into `mtad.yaml`'s `path:`. For a project whose `<artifactId>` is `connectivity`, the descriptor must say `path: target/connectivity.war` — not `target/${project.artifactId}.war`, not `target/ROOT.war`. After fixing the descriptor, re-run `cf deploy` (no rebuild needed).

> **The app will serve at `/<artifactId>`, not `/`.** This is the deliberate consequence of leaving the WAR named after the artifactId. Integration tests and approuter destinations must include the `/<artifactId>` prefix in their URLs.

### Issue: 404 Not Found at runtime — app deployed but routes return 404

**Symptom:** Deploy succeeded; the app starts; every request to `/<something>` returns 404. Logs show Tomcat started and the WAR loaded.

**Cause:** The WAR is named `<artifactId>.war`, so the SAP Java buildpack serves it at the Tomcat context path `/<artifactId>` — not `/`. The 404 isn't a deployment bug; it's the request URL missing the context path prefix.

**Solution:** Update the caller (integration tests, approuter destinations, frontend code) to prefix request URLs with `/<artifactId>`. For example, if the app's `<artifactId>` is `connectivity` and a servlet is mapped at `/api/echo`, the deployed URL is `https://<route>/connectivity/api/echo`. The Tomcat access log (visible via `cf logs <app>`) shows the actual context path the WAR was deployed under.

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
