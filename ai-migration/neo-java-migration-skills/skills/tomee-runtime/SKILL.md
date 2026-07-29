---
name: tomee-runtime
description: Invoke this skill to configure TomEE container for EJB applications. Detects @Stateless, @Singleton, @EJB annotations, javax.ejb/jakarta.ejb imports, or neo-javaee7-wp-api dependency. Use instead of Tomcat for EJB support.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# TomEE Runtime

Configure TomEE container for JavaEE/EJB applications in Cloud Foundry.

## Purpose

Migrate Neo applications using TomEE runtime (with EJB support) to Cloud Foundry's TomEE 10 container instead of Tomcat.

## Detection

This skill applies if any of these patterns are found:

### In pom.xml
```xml
<dependency>
    <groupId>com.sap.cloud</groupId>
    <artifactId>neo-javaee7-wp-api</artifactId>
</dependency>
```

### In Java source files
```java
import javax.ejb.Stateless;
import javax.ejb.Singleton;
import javax.ejb.EJB;
import javax.ejb.Schedule;

@Stateless
public class MyService { }

@Singleton
public class MySingleton { }
```

### In web.xml or deployment descriptors
```xml
<!-- EJB references -->
<ejb-local-ref>
    <ejb-ref-name>ejb/MyService</ejb-ref-name>
    <local>com.example.MyService</local>
</ejb-local-ref>
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **jakarta-java25-migration** - `Use the jakarta-java25-migration skill`
   - Migrates to Jakarta EE 10
   - REQUIRED before this skill

> **Note:** TomEE is an alternative to the Tomcat migration path. Choose TomEE if your application uses EJB features.

## Transformation Steps

### Step 1: Replace Neo Dependencies

**Before (Neo):**
```xml
<dependency>
    <groupId>com.sap.cloud</groupId>
    <artifactId>neo-javaee7-wp-api</artifactId>
    <version>${neo.version}</version>
    <scope>provided</scope>
</dependency>
```

**After (Cloud Foundry TomEE):**

See [assets/pom-cf-tomee.xml](assets/pom-cf-tomee.xml) for a complete template.

Key changes:
```xml
<dependencyManagement>
    <dependencies>
        <!-- CF TomEE BOM instead of cf-tomcat-bom -->
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
</dependencyManagement>

<properties>
    <!-- Resolve via sdk-replacement Step 3 (substitute `cf-tomee-bom` for `cf-tomcat-bom`). -->
    <cf-tomee-bom-version>RESOLVED_CF_TOMEE_BOM_VERSION</cf-tomee-bom-version>
    <sdk-modules-bom-version>RESOLVED_SDK_MODULES_BOM_VERSION</sdk-modules-bom-version>
    <jakarta.persistence-api-version>3.2.0</jakarta.persistence-api-version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
        <artifactId>scp-cf</artifactId>
    </dependency>

    <!-- Jakarta EE API provided by TomEE -->
    <dependency>
        <groupId>jakarta.platform</groupId>
        <artifactId>jakarta.jakartaee-api</artifactId>
        <version>10.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Step 2: Migrate EJB Annotations

**Before (Java EE 7):**
```java
import javax.ejb.Stateless;
import javax.ejb.EJB;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.annotation.PostConstruct;

@Stateless
public class MyBusinessService {

    @EJB
    private OtherService otherService;

    public String doWork() {
        return otherService.process();
    }
}

@Singleton
@Startup
public class ApplicationInitializer {

    @PostConstruct
    public void init() {
        System.out.println("Application started");
    }

    @Schedule(hour = "*", minute = "*/5", persistent = false)
    public void scheduledTask() {
        System.out.println("Running scheduled task");
    }
}
```

**After (Jakarta EE 10):**
```java
import jakarta.ejb.Stateless;
import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.annotation.PostConstruct;

@Stateless
public class MyBusinessService {

    @EJB
    private OtherService otherService;

    public String doWork() {
        return otherService.process();
    }
}

@Singleton
@Startup
public class ApplicationInitializer {

    @PostConstruct
    public void init() {
        System.out.println("Application started");
    }

    @Schedule(hour = "*", minute = "*/5", persistent = false)
    public void scheduledTask() {
        System.out.println("Running scheduled task");
    }
}
```

### Step 3: Create resources.xml for Data Sources

For TomEE, use `resources.xml` instead of Tomcat's `context.xml`.

Create `src/main/webapp/WEB-INF/resources.xml`:

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

> **Note:** TomEE uses `xs.openejb:XS Default JDBC Database` provider instead of Tomcat's `TomcatDataSourceFactory`. The `service` placeholder is specified as element content, not as an XML attribute.

### Step 4: Configure persistence.xml for TomEE JTA

If the application uses JPA, the `persistence.xml` must be updated for TomEE's JTA environment. There are four critical changes:

1. **File location** — Move `persistence.xml` from `src/main/resources/META-INF/` to `src/main/webapp/META-INF/`. TomEE processes DDL generation correctly only when the persistence.xml is at the WAR root `META-INF/` location, not at `WEB-INF/classes/META-INF/`.
2. **Explicit EclipseLink provider** — TomEE defaults to OpenJPA. Without an explicit `<provider>`, EclipseLink may not be properly initialized as the JPA provider.
3. **Short JNDI datasource name** — TomEE resolves datasources differently than Neo. Use `jdbc/DefaultDB` without the `java:comp/env/` prefix.
4. **HANA Cloud NCLOB for large text columns** — HANA Cloud limits `NVARCHAR` to 5000 characters. Any `@Lob` field with `length > 5000` must use `columnDefinition = "NCLOB"` instead of `length`, otherwise DDL generation silently fails for those tables.

**Before (Neo):**
```xml
<!-- Location: src/main/resources/META-INF/persistence.xml -->
<persistence xmlns="http://java.sun.com/xml/ns/persistence" version="2.0">
    <persistence-unit name="MyApp" transaction-type="JTA">
        <jta-data-source>java:comp/env/jdbc/DefaultDB</jta-data-source>
        <class>com.example.MyEntity</class>
        <properties>
            <property name="eclipselink.ddl-generation" value="create-tables" />
            <property name="eclipselink.ddl-generation.output-mode" value="database" />
        </properties>
    </persistence-unit>
</persistence>
```

**After (Cloud Foundry TomEE):**
```xml
<!-- Location: src/main/webapp/META-INF/persistence.xml (MUST be under webapp, not resources) -->
<persistence xmlns="https://jakarta.ee/xml/ns/persistence"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence
                         https://jakarta.ee/xml/ns/persistence/persistence_3_0.xsd"
    version="3.0">
    <persistence-unit name="MyApp" transaction-type="JTA">
        <provider>org.eclipse.persistence.jpa.PersistenceProvider</provider>
        <jta-data-source>jdbc/DefaultDB</jta-data-source>
        <class>com.example.MyEntity</class>
        <properties>
            <property name="eclipselink.ddl-generation" value="create-or-extend-tables" />
            <property name="eclipselink.target-database" value="HANA"/>
        </properties>
    </persistence-unit>
</persistence>
```

**Key changes:**
- **File moved** from `src/main/resources/META-INF/` to `src/main/webapp/META-INF/` — TomEE's DDL generation only runs correctly when persistence.xml is at the WAR root `META-INF/` location. Placing it under `resources/META-INF/` (which maps to `WEB-INF/classes/META-INF/` in the WAR) causes DDL generation to silently fail.
- `<provider>org.eclipse.persistence.jpa.PersistenceProvider</provider>` — explicit EclipseLink provider (TomEE defaults to OpenJPA)
- `<jta-data-source>jdbc/DefaultDB</jta-data-source>` — short JNDI name without `java:comp/env/` prefix
- `eclipselink.ddl-generation=create-or-extend-tables` — use this value instead of `create-tables` for robustness during schema evolution

> **Warning:** If `persistence.xml` is placed under `src/main/resources/META-INF/` instead of `src/main/webapp/META-INF/`, EclipseLink's DDL generation will **silently fail** in TomEE — no tables will be created and no error will be logged at startup. The failure only becomes visible at runtime when queries hit missing tables. Always verify by adding `<property name="eclipselink.logging.level" value="FINE"/>` temporarily and checking for `CREATE TABLE` statements in the logs.

#### HANA Cloud @Lob Column Migration

HANA Cloud enforces a maximum of **5000 characters** for `NVARCHAR` columns. EclipseLink maps `@Lob @Column(length = N)` to `NVARCHAR(N)` — if `N > 5000`, the `CREATE TABLE` statement fails silently.

**Before (works on Neo HANA on-premise, fails on HANA Cloud):**
```java
@Lob
@Column(nullable = false, length = 16 * 1024)  // 16384 > 5000 limit
private String longText = "";

@Lob
@Column(length = 512 * 1024)  // 524288 > 5000 limit
private String introduction = "";
```

**After (works on HANA Cloud):**
```java
@Lob
@Column(nullable = false, columnDefinition = "NCLOB")
private String longText = "";

@Lob
@Column(columnDefinition = "NCLOB")
private String introduction = "";
```

Replace `length = N` with `columnDefinition = "NCLOB"` on every `@Lob` field where the original length exceeds 5000. Keep other `@Column` attributes (`nullable`, `updatable`) as-is.

> **Note:** `NCLOB` has no practical size limit on HANA Cloud and is the correct type for large text fields. Fields with `length <= 5000` (e.g., `@Column(length = 1024)`) can remain as `NVARCHAR` and do not need this change.

> 🛑 **HARD RULE — a JPA/EJB app REQUIRES a HANA schema service. "No services
> required" is wrong for any app with a `persistence.xml` or a JPA datasource.**
> The `resources.xml` from Step 3 binds `jdbc/DefaultDB` to
> `service=${service_name_for_DefaultDB}`. If the mtad does not bind a
> `com.sap.xs.hana-schema` service (Steps 5–6), TomEE cannot initialise the
> datasource / persistence unit at startup, and the app **stages successfully
> but crashes on boot** (`cf deploy` → "Some instances have crashed", no useful
> HTTP response). Steps 5 and 6 are **mandatory** whenever the app uses JPA —
> do not skip them and do not declare "no services required".
>
> ✅ **Verify before deploy:** the mtad must contain a
> `type: com.sap.xs.hana-schema` resource AND the module must `requires:` it.
> `grep -q 'com.sap.xs.hana-schema' <mtad>` must succeed for a persistence app.

### Step 5: Create resource_configuration.yml

Create `src/main/webapp/META-INF/sap_java_buildpack/config/resource_configuration.yml`:

```yaml
---
tomee/webapps/ROOT/WEB-INF/resources.xml:
  service_name_for_DefaultDB: ${app-name}-hana
```

> **Note:** The path is `tomee/webapps/ROOT/WEB-INF` instead of `tomcat/webapps/ROOT`.
>
> 🛑 **SUBSTITUTE `${app-name}` with the REAL app name — it is a placeholder, not
> a literal.** This value MUST exactly equal the `hana-schema` resource name you
> bind in the mtad (Step 6). If you leave it literal, the buildpack looks up a
> service called `app-name-hana` that does not exist, logs
> `Could not find service with name 'app-name-hana', known services
> '[<real>-hana]'`, the JDBC pool fails to initialise, and the app **crashes on
> startup**. Example for an app named `persistence-with-ejb`:
>
> ```yaml
> ---
> tomee/webapps/ROOT/WEB-INF/resources.xml:
>   service_name_for_DefaultDB: persistence-with-ejb-hana
> ```
>
> ✅ **Cross-check:** the value here must be identical to the `resources:` entry
> `name:` in the mtad (Step 6). `grep service_name_for_DefaultDB
> resource_configuration.yml` and `grep -A1 'type: com.sap.xs.hana-schema' mtad*`
> must show the SAME service name.

### Step 6: Update MTA Descriptor

```yaml
_schema-version: "3.2"
ID: ${app-name}
version: 0.0.1

modules:
  - name: ${app-name}
    type: java.tomcat  # Still use java.tomcat type
    path: target/<artifactId>.war
    parameters:
      buildpack: sap_java_buildpack_jakarta
      disk-quota: 1024M
      memory: 1024M
    properties:
      # Enable TomEE runtime
      TARGET_RUNTIME: tomee
      ENABLE_SECURITY_JAVA_API_V2: true
      SET_LOGGING_LEVEL: 'ROOT: INFO'
      # Resource configuration for TomEE path (YAML list format)
      JBP_CONFIG_RESOURCE_CONFIGURATION:
        - tomee/webapps/ROOT/WEB-INF/resources.xml:
            service_name_for_DefaultDB: ${app-name}-hana
    requires:
      - name: ${app-name}-hana

resources:
  - name: ${app-name}-hana
    type: com.sap.xs.hana-schema
```

> **HANA: do NOT add `service: hana` or `service-plan: hdi-shared`** under
> `type: com.sap.xs.hana-schema`. Those keys are for the GENERIC
> `org.cloudfoundry.managed-service` type, not for `hana-schema` — adding
> them triggers `CF-UnprocessableEntity(10008): Invalid service plan` at
> deploy time. See `persistence-hana` / `mta-descriptor` for the same rule.

**Key differences:**
1. `TARGET_RUNTIME: tomee` property enables TomEE instead of Tomcat
2. `JBP_CONFIG_RESOURCE_CONFIGURATION` uses YAML list format (not inline string)
3. The path uses `tomee/webapps/ROOT/WEB-INF` instead of `tomcat/webapps/ROOT`

### Step 7: Remove CXFNonSpringJaxrsServlet from web.xml (Critical for CDI/EJB Injection)

If the Neo application declared `CXFNonSpringJaxrsServlet` in `web.xml` to configure JAX-RS, you **must remove it** when using TomEE. `CXFNonSpringJaxrsServlet` instantiates JAX-RS endpoint classes directly (via `new`), completely bypassing TomEE's CDI container. This means `@Inject`, `@EJB`, and `@Resource` fields in endpoint classes will remain `null`, causing `NullPointerException` at runtime.

TomEE has built-in CXF/JAX-RS integration that is CDI-aware. If your JAX-RS `Application` subclass has `@ApplicationPath("/rest")`, TomEE will auto-discover it and wire endpoints through the CDI container.

#### Detect

```bash
# Check for CXFNonSpringJaxrsServlet in web.xml
grep -n "CXFNonSpringJaxrsServlet" src/main/webapp/WEB-INF/web.xml
```

If found, apply the fix below.

#### Fix

**Remove the entire `<servlet>` and `<servlet-mapping>` block for CXF from web.xml:**

```xml
<!-- REMOVE: CXF servlet bypasses TomEE CDI injection -->
<servlet>
    <servlet-name>CXFServlet</servlet-name>
    <servlet-class>
        org.apache.cxf.jaxrs.servlet.CXFNonSpringJaxrsServlet
    </servlet-class>
    <init-param>
        <param-name>jakarta.ws.rs.Application</param-name>
        <param-value>com.example.RestActivator</param-value>
    </init-param>
    <init-param>
        <param-name>jaxrs.providers</param-name>
        <param-value>com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider</param-value>
    </init-param>
    <load-on-startup>1</load-on-startup>
</servlet>
<servlet-mapping>
    <servlet-name>CXFServlet</servlet-name>
    <url-pattern>/rest/*</url-pattern>
</servlet-mapping>
```

**Move provider registration to the JAX-RS Application class:**

If providers (e.g., Jackson) were registered via `jaxrs.providers` init-param, register them in the `Application.getClasses()` method instead:

```java
```java
@ApplicationPath("/rest")
public class RestActivator extends Application {
    private final Set<Class<?>> classes = new HashSet<>();

    public RestActivator() {
        classes.add(MyEndpoint.class);
        classes.add(com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider.class); // <-- register providers here
    }

    @Override
    public Set<Class<?>> getClasses() {
        return classes;
    }
}
```
```

> **Note:** This only applies to TomEE. On plain Tomcat (without built-in JAX-RS), `CXFNonSpringJaxrsServlet` is required because there is no container-managed CXF integration. If migrating to Tomcat instead of TomEE, keep the servlet but be aware that CDI injection will not work in endpoint classes.

### Step 8: Update beans.xml to Jakarta EE Namespace

If the project has a `beans.xml` with the old `javax` namespace, update it to Jakarta CDI 4.0. The old namespace may cause CDI bean discovery issues on TomEE 10.

**Before:**
```xml
<beans xmlns="http://java.sun.com/xml/ns/javaee"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="
      http://java.sun.com/xml/ns/javaee
      http://java.sun.com/xml/ns/javaee/beans_1_0.xsd">
```

**After:**
```xml
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="
      https://jakarta.ee/xml/ns/jakartaee
      https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
    bean-discovery-mode="all"
    version="4.0">
```

> **Note:** `bean-discovery-mode="all"` ensures all classes are discoverable as CDI beans, including those without explicit CDI annotations. This matches the implicit behavior of CDI 1.0 (`beans_1_0.xsd`).

### Step 9: Update EJB JNDI Lookups

**Before (Neo-specific JNDI):**
```java
Context ctx = new InitialContext();
MyService service = (MyService) ctx.lookup("java:comp/env/ejb/MyService");
```

**After (Standard Jakarta EE JNDI):**
```java
Context ctx = new InitialContext();
// Use standard Jakarta EE JNDI names
MyService service = (MyService) ctx.lookup("java:module/MyService");
// OR
MyService service = (MyService) ctx.lookup("java:global/${app-name}/MyService");
```

### Step 10: Handle Timer Service

TomEE Timer Service works similarly to Neo, but configuration may differ:

```java
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Timer;
import jakarta.ejb.Timeout;
import jakarta.annotation.Resource;
import jakarta.ejb.TimerService;

@Singleton
public class ScheduledTasks {

    @Resource
    private TimerService timerService;

    // Declarative timer
    @Schedule(hour = "*", minute = "*/10", persistent = false)
    public void periodicTask() {
        // Runs every 10 minutes
    }

    // Programmatic timer
    public void scheduleTask(long delay) {
        timerService.createSingleActionTimer(delay, null);
    }

    @Timeout
    public void handleTimeout(Timer timer) {
        // Handle programmatic timer expiration
    }
}
```

> **Note:** Set `persistent = false` for timers in Cloud Foundry as persistent timers require database storage.

## Directory Structure

```
src/main/
├── java/
│   └── com/example/
│       ├── ejb/
│       │   └── MyBusinessService.java
│       └── servlet/
│           └── MyServlet.java
└── webapp/
    ├── META-INF/
    │   ├── persistence.xml            # JPA config (MUST be here, not resources/META-INF)
    │   └── sap_java_buildpack/
    │       └── config/
    │           └── resource_configuration.yml
    └── WEB-INF/
        ├── resources.xml              # TomEE data source config
        ├── web.xml
        └── openejb-jar.xml (optional)
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `resources.xml` | src/main/webapp/WEB-INF/ | TomEE data source configuration |
| `resource_configuration.yml` | src/main/webapp/META-INF/sap_java_buildpack/config/ | Service name mapping |
| `openejb-jar.xml` | src/main/webapp/WEB-INF/ | EJB deployment configuration |

## CF Services

Same services as Tomcat-based deployment, but with TomEE runtime.

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Deploy
```bash
mvn clean package
cf deploy . -f
```

### 3. Verify TomEE Runtime
```bash
cf logs ${app-name} --recent | grep -i tomee
# Should show TomEE startup messages
```

### 4. Test EJB Access
```bash
curl "https://${app-url}/api/service"
# Should execute EJB method
```

## Common Issues

### Issue: EJB not found
**Cause:** JNDI name mismatch.
**Solution:** Use standard Jakarta EE JNDI names:
- `java:module/BeanName`
- `java:global/app/module/BeanName`

### Issue: Timer not firing
**Cause:** Persistent timer without database.
**Solution:** Set `persistent = false` on @Schedule.

### Issue: "Ambiguous EJB reference"
**Cause:** Multiple beans implement same interface.
**Solution:** Use `@EJB(beanName = "SpecificBean")`.

### Issue: ClassCastException with EJB proxies
**Cause:** Incorrect interface casting.
**Solution:** Ensure you're casting to the interface, not the implementation.

### Issue: TomEE startup fails with "serviceName is null" — TomEEDataSourceFactory NullPointerException
**Cause:** The `resources.xml` data source placeholder uses XML attribute syntax (`service="${service_name_for_DefaultDB}"`) instead of element body text. The SAP Java Buildpack's placeholder substitution does not work with XML attributes — only with element body text.
**Diagnosis:** Stack trace shows `NullPointerException: Cannot invoke "String.equals(Object)" because "serviceName" is null` in `VcapServices.getService()`.
**Solution:** Use element body text for the service placeholder in `resources.xml`:
```xml
<!-- WRONG — attribute syntax, placeholder not substituted -->
<Resource id="jdbc/DefaultDB"
          provider="xs.openejb:XS Default JDBC Database"
          type="javax.sql.DataSource"
          service="${service_name_for_DefaultDB}" />

<!-- CORRECT — element body text, placeholder substituted -->
<Resource id="jdbc/DefaultDB"
          provider="xs.openejb:XS Default JDBC Database"
          type="javax.sql.DataSource">
    service=${service_name_for_DefaultDB}
</Resource>
```
Also verify that the path in `resource_configuration.yml` and `JBP_CONFIG_RESOURCE_CONFIGURATION` matches the actual `resources.xml` location (`tomee/webapps/ROOT/WEB-INF/resources.xml`, not `META-INF`).

### Issue: Tables not created — DDL generation silently fails in TomEE JTA environment
**Cause:** The `persistence.xml` is placed at `src/main/resources/META-INF/` (packaged to `WEB-INF/classes/META-INF/` in the WAR). TomEE's DDL generation only works correctly when `persistence.xml` is at the WAR root `META-INF/` location (`src/main/webapp/META-INF/`). No error is logged at startup — the failure only surfaces at runtime as `Could not find table/view` errors.
**Diagnosis:** Application starts normally but all JPA queries fail with `DatabaseException: invalid table name: Could not find table/view TABLENAME in schema`. To confirm DDL is being attempted, add `<property name="eclipselink.logging.level" value="FINE"/>` and check logs for `CREATE TABLE` statements.
**Solution:** See **Step 4** above. Move `persistence.xml` from `src/main/resources/META-INF/` to `src/main/webapp/META-INF/` and ensure all three configuration fixes are applied:
1. File location: `src/main/webapp/META-INF/persistence.xml`
2. `<provider>org.eclipse.persistence.jpa.PersistenceProvider</provider>`
3. `<jta-data-source>jdbc/DefaultDB</jta-data-source>` (without `java:comp/env/` prefix)

### Issue: CREATE TABLE fails silently — HANA Cloud NVARCHAR 5000 limit
**Cause:** HANA Cloud enforces a maximum of 5000 characters for `NVARCHAR` columns. Entity fields annotated with `@Lob @Column(length = 16384)` or similar cause EclipseLink to generate `NVARCHAR(16384)`, which HANA rejects with error code 267: `specified length too long for its datatype`. EclipseLink logs this as a warning but continues — no tables are created for any entity that hits this limit, and the error is easy to miss unless logging is set to FINE.
**Diagnosis:** `cf logs --recent` shows `[EL Warning]` with `Error Code: 267` and `specified length too long for its datatype` during startup. Or, if logging is not at FINE level, all JPA queries fail with `Could not find table/view`.
**Solution:** Replace `length = N` (where N > 5000) with `columnDefinition = "NCLOB"` on all `@Lob` fields. See **Step 4 — HANA Cloud @Lob Column Migration** above.

### Issue: NullPointerException — @Inject/@EJB fields are null in JAX-RS endpoints
**Cause:** `CXFNonSpringJaxrsServlet` is declared in `web.xml`. This servlet instantiates JAX-RS endpoint classes directly via `new`, bypassing TomEE's CDI container entirely. All `@Inject`, `@EJB`, and `@Resource` fields remain `null`. The error manifests as `NullPointerException` when any injected service method is called (e.g., `"Cannot invoke ... because this.myService is null"`).
**Solution:** See **Step 7** above. Remove the `CXFNonSpringJaxrsServlet` `<servlet>` and `<servlet-mapping>` from `web.xml`. TomEE's built-in CXF integration auto-discovers `@ApplicationPath` and manages endpoint lifecycle through CDI. Move any `jaxrs.providers` init-param registrations to the `Application.getClasses()` method.

### Issue: AuthorizationException or isUserInRole() returns false — getUserPrincipal() returns null
**Cause:** The **most common cause** is that the REST API URL patterns (e.g., `/rest/*`, `/api/*`) are not covered by a `<security-constraint>` in `web.xml`. The `XSSecurityAuthenticator` valve only validates JWT tokens for URLs that match a security constraint. Without a matching constraint, the valve skips JWT validation entirely — `getUserPrincipal()` returns `null` and all `isUserInRole()` calls return `false`, regardless of injection method (`@Context`, `@Inject`, or `SessionContext`). This is easy to miss when Neo-era constraints covered only static pages while REST APIs were reached via auth-method-specific URL prefixes (`/s/api/*`, `/b/api/*`) that were removed during migration.
**Diagnosis:** Check if `getUserPrincipal()` returns `null`. If it does, this is a missing security constraint issue, not an injection issue.
**Solution:** Add a `<security-constraint>` covering `/rest/*` (or `/*`) with `<role-name>Everyone</role-name>`. See the **authentication-xsuaa** skill, Step 5.

### Issue: AuthorizationException or isUserInRole() returns false in @Stateless EJBs — principal IS set but roles not recognized
**Cause:** If `getUserPrincipal()` returns a valid principal but `isUserInRole()` still returns `false`: `@Context HttpServletRequest` is a JAX-RS annotation. It only works reliably in JAX-RS resource classes (endpoints). In `@Stateless` EJBs that are **not** JAX-RS resources — such as service providers, CDI producers, or utility beans — the `@Context` request is a CXF-internal wrapper that doesn't delegate `isUserInRole()` to the Catalina/XSUAA security realm. This causes `isUserInRole("admin")` to return `false` even though the user has the role assigned, resulting in authorization failures.

**Important:** `SessionContext.isCallerInRole()` also does **not** work in this scenario. TomEE's `OpenEJBSecurityListener$RequestCapturer` does not fully propagate XSUAA roles from the Catalina security realm to the OpenEJB security context, so `sessionContext.isCallerInRole()` returns `false` as well.

**Solution:** Replace `@Context` (JAX-RS injection) with `@Inject` (CDI injection) for `HttpServletRequest`. In Jakarta EE 10 (CDI 4.0), `HttpServletRequest` is a built-in CDI bean. CDI injects a request-scoped proxy that correctly delegates to the actual Catalina request — which has the XSUAA security context set by `XSSecurityAuthenticator`. Both `isUserInRole()` and `getUserPrincipal()` then work correctly.

**Before (broken — @Context in non-JAX-RS EJB):**
```java
@Stateless
public class MyUserContext {
    @Context  // JAX-RS injection — gives CXF wrapper, no XSUAA context
    HttpServletRequest httpRequest;

    public boolean isAdmin() {
        return httpRequest.isUserInRole("admin"); // returns false!
    }
}
```

**Also broken — SessionContext doesn't get XSUAA roles either:**
```java
@Stateless
public class MyUserContext {
    @Resource
    SessionContext sessionContext;

    public boolean isAdmin() {
        return sessionContext.isCallerInRole("admin"); // also returns false!
    }
}
```

**After (works — @Inject CDI injection):**
```java
@Stateless
public class MyUserContext {
    @Inject  // CDI injection — gives real Catalina request with XSUAA context
    HttpServletRequest httpRequest;

    public boolean isAdmin() {
        return httpRequest.isUserInRole("admin"); // correct!
    }

    public String getEmail() {
        Principal p = httpRequest.getUserPrincipal();
        if (p instanceof Token) {
            return ((Token) p).getClaimAsString(TokenClaims.EMAIL);
        }
        return null;
    }
}
```

> **Note:** `@Context` continues to work correctly in JAX-RS resource classes (`@Path`-annotated endpoints). This issue only affects `@Stateless`/`@Singleton` EJBs that are injected via CDI into JAX-RS endpoints.

### Issue: CDI beans not discovered on TomEE 10
**Cause:** `beans.xml` uses the old `javax` namespace (`http://java.sun.com/xml/ns/javaee/beans_1_0.xsd`). TomEE 10 may not process bean discovery correctly with the legacy namespace.
**Solution:** See **Step 8** above. Update `beans.xml` to the Jakarta CDI 4.0 namespace (`https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd`) with `bean-discovery-mode="all"` and `version="4.0"`.

## TomEE vs Tomcat Comparison

| Feature | Tomcat | TomEE |
|---------|--------|-------|
| EJB Support | No | Yes |
| JPA/JTA | Manual setup | Built-in |
| CDI | via extension | Built-in |
| Timer Service | No | Yes |
| Data Source Config | context.xml | resources.xml |
| Property | - | `TARGET_RUNTIME: tomee` |
| BOM | cf-tomcat-bom | cf-tomee-bom |

## Next Steps

After completing TomEE migration:
- Apply other applicable skills (Authentication, Destinations, etc.)
- The workflow is the same as Tomcat-based apps
