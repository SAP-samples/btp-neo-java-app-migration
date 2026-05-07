---
name: dependency-compatibility
description: Invoke this skill to resolve third-party library compatibility issues during Neo to CF migration. Detects libraries that break on Java 25, Jakarta EE 10, or HANA Cloud — including schema migration tools (Liquibase, Flyway), DI frameworks (Guice, CDI), and other known problematic dependencies. Run after jakarta-java25-migration and sdk-replacement.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Third-Party Dependency Compatibility

Resolve third-party library compatibility issues that arise during the Neo to Cloud Foundry migration.

## Purpose

When migrating from Neo (Java 8, Java EE) to CF (Java 25, Jakarta EE 10, HANA Cloud), many third-party libraries break due to:

- **Java 25 module system** — removed `javax.xml.bind`, `javax.xml.ws`, and other `javax.*` modules from the JDK
- **javax → jakarta namespace** — libraries compiled against `javax.servlet`, `javax.ws.rs`, etc. won't link against Jakarta EE 10
- **Database changes** — schema migration tools may not recognize HANA Cloud
- **DI framework conflicts** — listener ordering, JNDI assumptions, and initialization sequences break when the platform changes

This skill scans the project's dependencies, identifies known compatibility issues, and applies targeted fixes.

> **Note:** The `jakarta-java25-migration` skill handles the core javax→jakarta migration and Java 25 language upgrade. This skill handles **third-party library-specific** issues that the core migration doesn't cover.

## Detection

This skill applies if any of these patterns are found:

### In pom.xml
```xml
<!-- Schema migration tools -->
<artifactId>liquibase-core</artifactId>
<artifactId>flyway-core</artifactId>

<!-- DI frameworks -->
<artifactId>guice</artifactId>
<artifactId>guice-servlet</artifactId>
<artifactId>weld-servlet-core</artifactId>

<!-- Known problematic libraries -->
<artifactId>poi-ooxml</artifactId>
<artifactId>itextpdf</artifactId>
<artifactId>ehcache</artifactId>
<artifactId>hazelcast</artifactId>
<artifactId>quartz</artifactId>
<artifactId>retrofit</artifactId>
```

### In Java source files
```java
// DI framework imports
import com.google.inject.*;
import com.google.inject.servlet.GuiceServletContextListener;

// Liquibase programmatic usage
import liquibase.Liquibase;
import liquibase.integration.servlet.LiquibaseServletListener;

// CDI annotations
import javax.enterprise.context.*;
import jakarta.enterprise.context.*;
```

### In web.xml
```xml
<!-- Liquibase listener -->
<listener-class>liquibase.integration.servlet.LiquibaseServletListener</listener-class>

<!-- Guice filter/listener -->
<listener-class>com.google.inject.servlet.GuiceServletContextListener</listener-class>
<filter-class>com.google.inject.servlet.GuiceFilter</filter-class>
```

## Prerequisites

Before invoking this skill, ensure you have invoked:

1. **jakarta-java25-migration** - `Use the jakarta-java25-migration skill`
   - Java 25 and Jakarta EE 10 migration must be done first
   - REQUIRED before this skill

2. **sdk-replacement** - `Use the sdk-replacement skill`
   - SAP Cloud SDK setup must be done first
   - REQUIRED before this skill

## Transformation Steps

### Step 1: Schema Migration Tools (Conditional)

> **This step is conditional.** Only apply if schema migration tools are detected.

#### Detect

```bash
# Liquibase
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "liquibase" {} \;

# Flyway
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "flyway" {} \;
```

If either returns output, apply the relevant fix below.

#### 1a: Liquibase — Upgrade to 4.x for Java 25 + HANA Cloud

Liquibase 3.x has **two critical issues** on CF:

1. **JAXB dependency** — Liquibase 3.x internally uses `javax.xml.bind` for parsing XML changelogs. Java 25 removed `javax.xml.bind` from the JDK, causing:
   ```
   NoClassDefFoundError: javax/xml/bind/annotation/XmlElement
   ```

2. **Unknown database: HDB** — Liquibase 3.x doesn't recognize HANA Cloud's database identifier. It falls back to `UnsupportedDatabase`, which can fail on schema-specific operations.

**Fix:** Upgrade Liquibase to 4.x and add the HANA extension.

> **Note:** Check [Maven Central](https://mvnrepository.com/artifact/org.liquibase/liquibase-core) for the latest 4.x version at the time of migration.

**Before:**
```xml
<dependency>
    <groupId>org.liquibase</groupId>
    <artifactId>liquibase-core</artifactId>
    <version>3.8.2</version>  <!-- or any 3.x -->
</dependency>

<!-- Old HANA community extension (may be present or commented out) -->
<dependency>
    <groupId>com.github.lbitonti</groupId>
    <artifactId>liquibase-hana</artifactId>
    <version>3.0</version>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>org.liquibase</groupId>
    <artifactId>liquibase-core</artifactId>
    <version>4.30.0</version>
</dependency>

<!-- Official HANA extension for Liquibase 4.x -->
<dependency>
    <groupId>org.liquibase.ext</groupId>
    <artifactId>liquibase-hanadb</artifactId>
    <version>4.30.0</version>
</dependency>

<!-- SLF4J bridge for Liquibase logging -->
<dependency>
    <groupId>com.mattbertolini</groupId>
    <artifactId>liquibase-slf4j</artifactId>
    <version>5.1.0</version>
</dependency>
```

> **Important:** Remove the old `com.github.lbitonti:liquibase-hana` dependency if present (even if commented out). It is incompatible with Liquibase 4.x. See the [Liquibase HANA Extension](https://github.com/liquibase/liquibase-hanadb) for details.

#### 1b: Flyway — Upgrade for Jakarta EE 10 + HANA Cloud

Flyway 9.x+ supports Jakarta EE and HANA Cloud natively.

> **Note:** Flyway 10.x requires Java 17+. Check [Flyway docs](https://documentation.red-gate.com/fd/release-notes-for-flyway-engine-179732572.html) for the latest version.

```bash
# Check current Flyway version
find . -name "pom.xml" -not -path "*/target/*" -exec grep -A1 "flyway" {} \;
```

If Flyway version is < 9.0, upgrade:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>10.20.1</version>
</dependency>

<!-- For HANA support -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-saphana</artifactId>
    <version>10.20.1</version>
</dependency>
```

### Step 2: DI Framework Integration (Conditional)

> **This step is conditional.** Only apply if a DI framework is detected.

#### Detect

```bash
# Google Guice
grep -r "com.google.inject\|GuiceServletContextListener\|GuiceFilter" --include="*.java" .
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "guice" {} \;

# CDI (Weld)
grep -r "javax.enterprise.context\|jakarta.enterprise.context\|@RequestScoped\|@ApplicationScoped" --include="*.java" .
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "weld" {} \;

# HK2
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "hk2" {} \;
```

If any commands return output, check for the common issues below.

#### 2a: Listener Ordering with Schema Migration Tools

If the application uses **both** a DI framework **and** a schema migration tool (Liquibase, Flyway), listener ordering in `web.xml` becomes critical.

**Problem:** Schema migration tools like Liquibase's `LiquibaseServletListener` perform their own JNDI lookup for the `DataSource`. This doesn't work when:
- The `DataSource` is provided by a DI framework that must initialize first
- The DI container manages database connections

**The required initialization order in `web.xml` is:**
```
1. DI Framework Listener    →  Initializes DI container, creates DataSource
2. Schema Migration Listener →  Gets DataSource from DI, runs migrations
3. Application Listener      →  Application starts with migrated schema
```

**Solution:** Replace Liquibase's built-in `LiquibaseServletListener` (or Flyway's equivalent) with a custom `ServletContextListener` that retrieves the `DataSource` from your DI container.

**web.xml listener ordering:**
```xml
<!-- Order matters! DI must initialize before schema migration -->
<listener>
    <!-- 1. DI framework initializes first -->
    <listener-class>com.example.app.GuiceContextListener</listener-class>
</listener>
<listener>
    <!-- 2. Schema migration runs after DI provides the DataSource -->
    <listener-class>com.example.app.CFLiquibaseRunner</listener-class>
</listener>
<listener>
    <!-- 3. Application initializes after schema is ready -->
    <listener-class>com.example.app.AppInitializer</listener-class>
</listener>
```

**Custom migration runner template (adapt to your DI framework):**

```java
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import java.sql.Connection;

/**
 * Custom Liquibase runner that retrieves the DataSource from the DI container
 * instead of performing a direct JNDI lookup.
 *
 * ADAPT: Replace getDataSourceFromDI() with your DI framework's lookup method.
 */
public class CFLiquibaseRunner implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            // ADAPT: Get DataSource from your DI framework
            // For Guice:  Injector injector = (Injector) sce.getServletContext().getAttribute(Injector.class.getName());
            //              DataSource ds = injector.getInstance(DataSource.class);
            // For CDI:    DataSource ds = CDI.current().select(DataSource.class).get();
            DataSource dataSource = getDataSourceFromDI(sce);

            try (Connection conn = dataSource.getConnection()) {
                Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));

                try (Liquibase liquibase = new Liquibase(
                        "db/changelog/db.changelog-master.xml",
                        new ClassLoaderResourceAccessor(),
                        database)) {
                    liquibase.update("");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Liquibase migration failed", e);
        }
    }

    private DataSource getDataSourceFromDI(ServletContextEvent sce) {
        // TODO: Implement based on your DI framework
        throw new UnsupportedOperationException("Adapt this method to your DI framework");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // no-op
    }
}
```

> **Note:** This is a template — you must adapt `getDataSourceFromDI()` to your specific DI framework. The key principle is: let the DI container manage the DataSource, and have the migration runner retrieve it from the container rather than doing its own JNDI lookup.

#### 2b: Jersey + CDI (Weld) on Tomcat — Missing Bean Discovery

When using **Jersey** with **CDI (Weld)** on **Tomcat** (not TomEE), the `jersey-cdi2-se` module bootstraps Weld in SE (standalone) mode. Weld SE scans for `META-INF/beans.xml` inside classpath entries to discover bean archives. However, a WAR's standard CDI descriptor at `WEB-INF/beans.xml` is **not** recognized by Weld SE — only by Weld Servlet.

**Symptom:** All `@Inject` points fail at deployment with:
```
WELD-001408: Unsatisfied dependencies for type UserDAO with qualifiers @Default
WELD-001408: Unsatisfied dependencies for type Notifier with qualifiers @Default
...
```
The errors appear for **every** injected bean even though `WEB-INF/beans.xml` exists with `bean-discovery-mode="all"`.

**Detection:**
```bash
# Check if using jersey-cdi2-se with Weld on Tomcat
grep -l "jersey-cdi2-se" pom.xml
grep -l "weld-servlet" pom.xml
grep -l "cf-tomcat-bom" pom.xml

# Check if META-INF/beans.xml exists in resources
ls src/main/resources/META-INF/beans.xml 2>/dev/null
```

If all three dependencies are present AND `src/main/resources/META-INF/beans.xml` does NOT exist, apply this fix.

**Fix:** Add a `beans.xml` to `src/main/resources/META-INF/` so it is packaged as `WEB-INF/classes/META-INF/beans.xml` in the WAR. This makes Weld SE treat the classes directory as a bean archive:

```bash
mkdir -p src/main/resources/META-INF
```

Create `src/main/resources/META-INF/beans.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
       bean-discovery-mode="all"
       version="4.0">
</beans>
```

> **Note:** Keep the existing `WEB-INF/beans.xml` as well — it is still needed for Weld Servlet mode. The WAR should contain BOTH `WEB-INF/beans.xml` and `WEB-INF/classes/META-INF/beans.xml`. This issue does NOT affect TomEE-based applications because TomEE provides its own CDI container (OpenWebBeans) and does not use `jersey-cdi2-se`.

#### 2c: Jersey + CDI (Weld) on Tomcat — Ambiguous HttpServletRequest

After fixing the bean discovery issue (Step 2b), a second deployment error can occur: `WELD-001409: Ambiguous dependencies` for `HttpServletRequest`. With `bean-discovery-mode="all"`, Weld SE discovers multiple providers for `HttpServletRequest` — typically two Jersey `SupplierBeanBridge` instances and one Weld built-in bean.

**Symptom:**
```
WELD-001409: Ambiguous dependencies for type HttpServletRequest with qualifiers @Default
  at injection point [BackedAnnotatedField] @Inject private com.example.filter.MyFilter.request
  Possible dependencies:
    - org.glassfish.jersey.ext.cdi1x.internal.CdiComponentProvider$SupplierBeanBridge
    - org.glassfish.jersey.ext.cdi1x.internal.CdiComponentProvider$SupplierBeanBridge
    - Built-in Bean
```

**Cause:** JAX-RS filters and resource classes that use `@Inject` (CDI) for `HttpServletRequest` instead of `@Context` (JAX-RS). When both Jersey and Weld can provide `HttpServletRequest`, CDI cannot resolve the ambiguity.

**Detection:**
```bash
# Find Java files that @Inject HttpServletRequest
grep -rn "@Inject" --include="*.java" -A1 . | grep "HttpServletRequest"
```

**Fix:** Changing `@Inject` to `@Context` resolves this specific ambiguity error, but will lead to a **runtime** failure (WELD-000710) when the proxy is actually invoked. See **Step 2e** for the complete solution that removes servlet type field injection entirely.

> **Note:** Only change `HttpServletRequest` injection. Other CDI beans (DAOs, services) must remain `@Inject`. This issue does NOT affect TomEE-based applications.

#### 2d: Jersey + CDI (Weld) on Tomcat — Ambiguous Configuration from JAX-RS Application

After fixing bean discovery (Step 2b) and HttpServletRequest ambiguity (Step 2c), a third deployment error can occur: `WELD-001409: Ambiguous dependencies` for `Configuration`. With `bean-discovery-mode="all"`, Weld SE discovers the JAX-RS `Application` subclass (typically extending `ResourceConfig`) as a CDI managed bean. Since `ResourceConfig` implements `jakarta.ws.rs.core.Configuration`, it conflicts with Jersey's own `InstanceBean` that also provides `Configuration`.

**Symptom:**
```
WELD-001409: Ambiguous dependencies for type Configuration with qualifiers @Default
  at injection point [BackedAnnotatedParameter] Parameter 2 of [BackedAnnotatedConstructor] @Inject public org.glassfish.jersey.internal.inject.ParamConverters$AggregatedProvider(@Context InjectionManager, @Context Configuration)
  Possible dependencies:
    - org.glassfish.jersey.inject.cdi.se.bean.InstanceBean
    - Managed Bean [class com.example.rest.MyApplication] with qualifiers [@Any @Default]
```

**Detection:**
```bash
# Find JAX-RS Application subclass
grep -rn "extends ResourceConfig\|extends Application" --include="*.java" src/

# Check if it has @Vetoed
grep -B5 "extends ResourceConfig\|extends Application" --include="*.java" src/ | grep -c "Vetoed"
```

**Fix:** Add `@jakarta.enterprise.inject.Vetoed` to the Application subclass to exclude it from CDI bean discovery:

**Before:**
```java
import org.glassfish.jersey.server.ResourceConfig;

public class MyApplication extends ResourceConfig {
```

**After:**
```java
import jakarta.enterprise.inject.Vetoed;
import org.glassfish.jersey.server.ResourceConfig;

@Vetoed
public class MyApplication extends ResourceConfig {
```

> **Note:** The `@Vetoed` annotation tells Weld to completely ignore this class during bean discovery. Jersey manages its own Application instance — it should never be a CDI managed bean. This issue does NOT affect TomEE-based applications.

#### 2e: Jersey + CDI (Weld) on Tomcat — WELD-000710: Cannot Inject Servlet Types

After fixing the HttpServletRequest ambiguity (Step 2c) and Configuration ambiguity (Step 2d), a **runtime** error occurs when any code invokes a method on an injected `HttpServletRequest`, `HttpServletResponse`, or `HttpSession`. The `@Context` annotation resolves the CDI ambiguity, but the underlying Weld SE proxy still cannot provide the actual servlet object.

**Symptom:**
```
WELD-000710: Cannot inject HttpServletRequest outside of a Servlet request
  at com.example.filter.MyFilter.getCurrentUser(MyFilter.java:85)
```
This error occurs at **request time** (not deployment), when the proxy created by `@Context HttpServletRequest` tries to delegate to Weld SE's built-in `HttpServletRequestBean`, which requires an active servlet request context that Weld SE does not have.

**Root cause:** `jersey-cdi2-se` redirects all `@Context` injection through CDI. For servlet types (`HttpServletRequest`, `HttpServletResponse`, `HttpSession`), Weld's built-in beans take precedence over Jersey's CDI extension beans. These built-in beans only work in Weld Servlet mode, not in Weld SE mode. The same issue affects `HttpServletResponse` and `HttpSession`.

**Detection:**
```bash
# Find all field-level @Context HttpServletRequest/HttpServletResponse/HttpSession
grep -rn "@Context" --include="*.java" -A1 src/ | grep -E "HttpServletRequest|HttpServletResponse|HttpSession"
```

**Fix:** Remove all field-level `@Context` injection of servlet types. Replace with JAX-RS alternatives depending on the class type:

**For `ContainerRequestFilter` classes** — use `ContainerRequestContext` (passed to the `filter()` method):

**Before:**
```java
public class MyFilter implements ContainerRequestFilter {
    @Context
    private HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String userId = request.getUserPrincipal().getName();
        HttpSession session = request.getSession();
        User user = (User) session.getAttribute("user");
    }
}
```

**After:**
```java
public class MyFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext requestContext) {
        String userId = requestContext.getSecurityContext().getUserPrincipal().getName();
        // Store resolved user as a request-scoped property for downstream resource methods
        User user = resolveUser(userId);
        requestContext.setProperty("user", user);
        // Downstream resource methods can read it via:
        // User user = (User) requestContext.getProperty("user");
    }
```

**For JAX-RS resource classes (`@Path`)** — replace `@Context HttpServletRequest` with `@Context SecurityContext` (a JAX-RS type, not a servlet type — Weld SE does not intercept it):

**Before:**
```java
@RequestScoped
@Path("api/v1")
public class MyService {
    @Context
    private HttpServletRequest httpRequest;

    @GET @Path("/data")
    public Response getData() {
        String userId = httpRequest.getUserPrincipal().getName();
        boolean isAdmin = httpRequest.isUserInRole("Admin");
    }
}
```

**After:**
```java
@RequestScoped
@Path("api/v1")
public class MyService {
    @Context
    private SecurityContext securityContext;

    @GET @Path("/data")
    public Response getData() {
        String userId = securityContext.getUserPrincipal().getName();
        boolean isAdmin = securityContext.isUserInRole("Admin");
    }
}
```

**For `HttpServletResponse`** — move to method parameter injection (resolved by Jersey at invocation time, not by CDI):

**Before:**
```java
@Context
private HttpServletResponse httpResponse;

@GET @Path("/report")
public HttpServletResponse getReport() {
    httpResponse.setContentType("text/csv");
    // ...
    return httpResponse;
}
```

**After:**
```java
@GET @Path("/report")
public HttpServletResponse getReport(@Context HttpServletResponse httpResponse) {
    httpResponse.setContentType("text/csv");
    // ...
    return httpResponse;
}
```

**For non-resource CDI beans** (factories, utilities) — pass the needed values from callers instead of injecting `HttpServletRequest`:

**Before:**
```java
@RequestScoped
public class MyFactory {
    @Context
    private HttpServletRequest httpRequest;

    public void doWork() {
        boolean isAdmin = httpRequest.isUserInRole("Admin");
    }
}
```

**After:**
@RequestScoped
public class MyFactory {

    public void doWork(boolean isAdmin) {
        // use isAdmin directly — no field injection needed
    }
}
```

**For `IdentityAdapter` or similar classes** that accept `HttpServletRequest` as a method parameter to extract user info — change to accept `java.security.Principal`:

**Before:**
```java
public String getUserName(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    // extract claims from principal...
}
```

**After:**
```java
public String getUserName(Principal principal) {
    // extract claims from principal...
}
```

> **Key insight:** `jakarta.ws.rs.core.SecurityContext` is a JAX-RS type, NOT a servlet type. Weld SE does not have a built-in bean for it, so Jersey's CDI extension provides it correctly. The affected servlet types are: `HttpServletRequest`, `HttpServletResponse`, `HttpSession`, and `ServletContext`. This issue does NOT affect TomEE-based applications.

#### 2f: Guice javax → jakarta Namespace

If the application uses Google Guice, check the version:

```bash
find . -name "pom.xml" -not -path "*/target/*" -exec grep -A1 "guice" {} \;
```

Guice 7.x+ supports `jakarta.inject` and `jakarta.servlet`. If on Guice < 7:

> **Note:** Guice 7.x is a breaking change from Guice 4.x/5.x — it requires `jakarta.inject` instead of `javax.inject`. Since the `jakarta-java25-migration` skill already migrates `javax.inject` → `jakarta.inject`, Guice 7.x is the correct target. See the [Guice 7.0 Release Notes](https://github.com/google/guice/wiki/Guice700) for the full change list.

**Before:**
```xml
<dependency>
    <groupId>com.google.inject</groupId>
    <artifactId>guice</artifactId>
    <version>4.2.3</version>  <!-- or any < 7.x -->
</dependency>
<dependency>
    <groupId>com.google.inject.extensions</groupId>
    <artifactId>guice-servlet</artifactId>
    <version>4.2.3</version>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>com.google.inject</groupId>
    <artifactId>guice</artifactId>
    <version>7.0.0</version>
</dependency>
<dependency>
    <groupId>com.google.inject.extensions</groupId>
    <artifactId>guice-servlet</artifactId>
    <version>7.0.0</version>
</dependency>
```

#### 2g: Jersey + CDI (Weld) on Tomcat — WELD-001303: @SessionScoped Not Available

After fixing WELD-000710 (Step 2e), a runtime error can occur if any CDI bean uses `@SessionScoped`. Weld SE mode does not provide an active session context, so any `@SessionScoped` bean will fail when its proxy is first accessed.

**Symptom:**
```
WELD-001303: No active contexts for scope type jakarta.enterprise.context.SessionScoped
  at com.example.adapter.IdentityAdapter$Proxy$_$$_WeldClientProxy.getUserName()
```
This error occurs at **request time** when the CDI proxy for a `@SessionScoped` bean tries to find an active session context.

**Root cause:** `jersey-cdi2-se` runs Weld in SE mode, which does not activate the session scope. On Neo with a full servlet container, `@SessionScoped` works because the servlet container manages HTTP sessions. On CF with Weld SE, there is no servlet session lifecycle.

**Detection:**
```bash
# Find all @SessionScoped beans
grep -rn "@SessionScoped" --include="*.java" src/
```

**Fix:** Change `@SessionScoped` to an appropriate alternative scope:

- **`@ApplicationScoped`** — for stateless services, adapters, and utilities that don't hold per-user state. This is the most common replacement.
- **`@RequestScoped`** — for beans that need per-request isolation but not cross-request session state.

Also remove `Serializable` interface and `serialVersionUID` if they were only there for session serialization.

**Before:**
```java
@SessionScoped
public class IdentityAdapter implements Serializable {
    private static final long serialVersionUID = 1L;
    // ...
}
```

**After:**
```java
@ApplicationScoped
public class IdentityAdapter {
    // ...
}
```

> **Key insight:** If the bean was using `@SessionScoped` to cache user-specific data across requests (e.g., storing user info in a session), replace that caching with per-request property storage via `ContainerRequestContext.setProperty()`/`getProperty()` (see Step 2e) or pass the values explicitly from the caller. This issue does NOT affect TomEE-based applications.

### Step 3: Known Library Compatibility Table

For other third-party libraries found during detection, check this table:

| Library | Breaking Version | Minimum Compatible Version | Issue | Fix |
|---------|-----------------|---------------------------|-------|-----|
| Apache POI (OOXML) | 3.x, 4.x | 4.x (with JAXB deps) or 5.x | Uses `javax.xml.bind` internally for OOXML parsing | Add `jaxb-api` + `jaxb-impl` at appropriate scope (see `jakarta-java25-migration` Step 10) or upgrade to POI 5.x |
| iText | 5.x | 7.x+ (or OpenPDF 1.3+) | iText 5.x uses removed Java APIs | Upgrade or switch to OpenPDF |
| EhCache | 2.x | 3.10+ | `javax.cache` namespace | Upgrade to EhCache 3.x with `jakarta.cache` |
| Hazelcast | < 5.0 | 5.0+ | `javax.cache` namespace | Upgrade to 5.x |
| Quartz Scheduler | 2.3.x | 2.5.0+ | Java 25 reflection issues | Upgrade to 2.5.x+ |
| Retrofit | 1.x | 2.9+ | OkHttp 3.x incompatibility | Upgrade to Retrofit 2.x |
| Jackson | < 2.13 | 2.15+ | Java 25 module access warnings | Upgrade to 2.15+ |
| Hibernate | 5.x | 6.x+ | `javax.persistence` namespace | Upgrade to Hibernate 6.x (uses `jakarta.persistence`) |
| Log4j | 1.x | 2.x (Log4j2) | EOL, security vulnerabilities, Java 25 issues | Migrate to Log4j2 or SLF4J + Logback |
| ModelMapper | 2.x, 3.x | 3.x (with fix) | `PropertyMap.configure()` proxy NPE on enum fields with Java 25 | Replace `PropertyMap` with `Converter` for mappings that access enum fields (see Common Issues) |
| EclipseLink (MOXy) | 4.x | 4.x (with deps) | MOXy JSON provider needs `jakarta.json-api` not bundled transitively | Add `jakarta.json-api` + `parsson` (Eclipse JSON-P impl) to pom.xml (see Common Issues) |

> **Note:** This table covers commonly encountered libraries. For libraries not listed, check their release notes for Java 25 and Jakarta EE 10 compatibility.

### Step 4: Generic Compatibility Check

For any dependency not in the known table above:

```bash
# Check if any dependency still uses javax.* at runtime
# Build the WAR first
mvn clean package -DskipTests

# List all JARs in the WAR and check for javax.* usage
jar tf target/*.war | grep "WEB-INF/lib/" | sort
```

**Heuristic:** If a library's latest version was released **before 2021**, it likely has Java 25 or Jakarta EE issues. Check the library's documentation for migration guides.

For each flagged library:
1. Check if a Jakarta-compatible version exists → upgrade
2. If no compatible version exists → check for an alternative library
3. If no alternative exists → add bridge dependencies (`javax.xml.bind`, etc.) at the appropriate scope

## Configuration Files

No new configuration files are created by this skill. Changes are limited to `pom.xml` dependency updates, `web.xml` listener ordering, and optional custom Java classes.

## CF Services

No CF services required for this skill.

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Test Compile Check
```bash
mvn test-compile
```

### 3. Run Tests
```bash
mvn test
```

### 4. Check for Remaining javax.xml.bind Runtime Issues
```bash
# Search for libraries that might still need JAXB at runtime
mvn dependency:tree | grep -i "jaxb\|xml.bind"
```

## Common Issues

### Issue: NoClassDefFoundError: javax/xml/bind after Liquibase upgrade
**Cause:** Another library (not Liquibase) still depends on `javax.xml.bind`.
**Solution:** Run `mvn dependency:tree | grep -B5 "xml.bind"` to find which dependency pulls it. Add `jaxb-api` + `jaxb-impl` at the appropriate scope, or upgrade the offending library.

### Issue: Liquibase "Unknown database: HDB"
**Cause:** Missing `liquibase-hanadb` extension.
**Solution:** Add `org.liquibase.ext:liquibase-hanadb` at the same version as `liquibase-core`.

### Issue: DI container not initialized when Liquibase runs
**Cause:** Listener ordering in `web.xml` — Liquibase listener runs before DI framework listener.
**Solution:** Reorder listeners in `web.xml`: DI framework first, then schema migration, then application init. See Step 2a above.

### Issue: WELD-001408 Unsatisfied dependencies for all beans on Tomcat with Jersey
**Cause:** `jersey-cdi2-se` bootstraps Weld SE which looks for `META-INF/beans.xml` in classpath entries, not `WEB-INF/beans.xml`.
**Solution:** Add `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="all"`. See Step 2b above.

### Issue: WELD-001409 Ambiguous dependencies for HttpServletRequest after adding META-INF/beans.xml
**Cause:** With `bean-discovery-mode="all"`, both Jersey and Weld provide `HttpServletRequest` as CDI beans, creating ambiguity for `@Inject` injection points.
**Solution:** Change `@Inject` to `@Context` for `HttpServletRequest` in JAX-RS filters and resource classes. See Step 2c above.

### Issue: WELD-001409 Ambiguous dependencies for Configuration after adding META-INF/beans.xml
**Cause:** With `bean-discovery-mode="all"`, the JAX-RS Application subclass (extending `ResourceConfig`) is discovered as a CDI bean that implements `Configuration`, conflicting with Jersey's own `InstanceBean`.
**Solution:** Add `@jakarta.enterprise.inject.Vetoed` to the Application subclass. See Step 2d above.

### Issue: WELD-000710 Cannot inject HttpServletRequest outside of a Servlet request
**Cause:** `jersey-cdi2-se` runs Weld in SE mode. Weld SE's built-in beans for servlet types (`HttpServletRequest`, `HttpServletResponse`, `HttpSession`) require an active servlet request context that doesn't exist in SE mode. This occurs even with `@Context` (not just `@Inject`) because `jersey-cdi2-se` routes all injection through CDI.
**Solution:** Remove all field-level `@Context`/`@Inject` of servlet types. Use `@Context SecurityContext` (JAX-RS type) for principal/role access, `ContainerRequestContext` in filters, and method parameter `@Context` for `HttpServletResponse`. See Step 2e above.

### Issue: WELD-001303 No active contexts for scope type SessionScoped
**Cause:** `jersey-cdi2-se` runs Weld in SE mode, which does not activate the session scope. Any `@SessionScoped` bean proxy will throw this error when first accessed at request time.
**Solution:** Change `@SessionScoped` to `@ApplicationScoped` (for stateless beans) or `@RequestScoped` (for per-request beans). Remove `Serializable` interface if it was only there for session serialization. See Step 2g above.

### Issue: ModelMapper PropertyMap NullPointerException on enum fields with Java 25
**Cause:** ModelMapper's `PropertyMap.configure()` creates CGLIB/ByteBuddy proxies to record property access. When a getter returns an enum type (e.g., `getType()` returns `BenefitType`), the proxy returns `null` because enums cannot be proxied. Calling `.toString()` on the null enum causes an NPE. This manifests as `NullPointerException: Cannot invoke "MyEnum.toString()" because the return value of "MyEntity.getType()" is null` during `PropertyMap.configure()`, not during actual mapping.
**Solution:** Replace the `PropertyMap` with a `Converter` (using `AbstractConverter`) that maps objects manually at mapping time instead of at configuration time. In the converter, access the real source object's enum field directly. Update callers from `mapper.addMappings(propertyMap)` to `mapper.addConverter(converter)`.

**Before:**
```java
public static PropertyMap<MyEntity, MyDTO> entityMap = new PropertyMap<>() {
    protected void configure() {
        map().setName(source.getName());
        String typeStr = source.getType().toString(); // NPE — proxy returns null for enum
        map().setType(typeStr);
    }
};
// Usage: mapper.addMappings(Mappings.entityMap);
```

**After:**
```java
public static Converter<MyEntity, MyDTO> entityConverter = new AbstractConverter<>() {
    protected MyDTO convert(MyEntity source) {
        MyDTO dto = new MyDTO();
        dto.setName(source.getName());
        dto.setType(source.getType() != null ? source.getType().toString() : null);
        // Map all other fields manually
        return dto;
    }
};
// Usage: mapper.addConverter(Mappings.entityConverter);
```

### Issue: NoClassDefFoundError: jakarta/json/JsonException with EclipseLink MOXy 4.x
**Cause:** EclipseLink MOXy 4.x (used as the JSON provider via `MOXyJsonProvider`) requires Jakarta JSON Processing (JSON-P) API at runtime for JSON deserialization. The `eclipselink` all-in-one artifact does not transitively include `jakarta.json-api`. This manifests as `ClassNotFoundException: jakarta.json.JsonException` during JSON request/response processing (e.g., POST with JSON body).
**Solution:** Add both the Jakarta JSON-P API and an implementation to `pom.xml`:
```xml
<!-- Jakarta JSON Processing API + impl (needed by EclipseLink MOXy 4.x) -->
<dependency>
    <groupId>jakarta.json</groupId>
    <artifactId>jakarta.json-api</artifactId>
    <version>2.1.3</version>
</dependency>
<dependency>
    <groupId>org.eclipse.parsson</groupId>
    <artifactId>parsson</artifactId>
    <version>1.1.5</version>
</dependency>
```

### Issue: Guice "No implementation for jakarta.servlet.http.HttpServletRequest" after migration
**Cause:** Guice version doesn't support `jakarta.servlet` namespace.
**Solution:** Upgrade Guice to 7.x. See Step 2f above.

### Issue: Library compiled against javax.servlet won't link
**Cause:** Library uses `javax.servlet.*` but the container provides `jakarta.servlet.*`.
**Solution:** Check for a Jakarta-compatible version. If not available, the library must be replaced or wrapped.

## Next Steps

After completing this skill, proceed to the remaining feature skills:
- [../approuter-setup/SKILL.md](../approuter-setup/SKILL.md) - Application Router setup
- [../authentication-xsuaa/SKILL.md](../authentication-xsuaa/SKILL.md) - XSUAA authentication
- [../persistence-hana/SKILL.md](../persistence-hana/SKILL.md) - HANA Cloud database
- [../mta-descriptor/SKILL.md](../mta-descriptor/SKILL.md) - MTA deployment descriptor
