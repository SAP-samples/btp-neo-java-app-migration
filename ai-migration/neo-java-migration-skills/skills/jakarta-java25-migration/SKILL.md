---
name: jakarta-java25-migration
description: Invoke this skill to migrate Java 8/11 to Java 25 and javax.* to jakarta.* namespaces. Detects Java version < 25 in pom.xml or javax.* imports in source files. Foundation skill - invoke before sdk-replacement.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Jakarta EE 10 and Java 25 Migration

Migrate your Neo Java application from Java 8/11 and Java EE to Java 25 and Jakarta EE 10.

## Purpose

Upgrade the Java runtime and migrate from `javax.*` namespaces to `jakarta.*` namespaces for forward compatibility with modern application servers and Cloud Foundry buildpacks.

## Detection

This skill applies if any of these patterns are found:

### In pom.xml
```xml
<!-- Java 8 or 11 source/target -->
<maven.compiler.source>1.8</maven.compiler.source>
<maven.compiler.target>1.8</maven.compiler.target>

<!-- OR -->
<properties>
    <java.version>11</java.version>
</properties>
```

### In Java source files
```java
// javax.* imports indicate pre-Jakarta EE
import javax.servlet.*;
import javax.persistence.*;
import javax.annotation.*;
import javax.mail.*;
import javax.ejb.*;
```

## Prerequisites

- None (this is typically the first migration step)
- Maven 3.6+ installed
- Java 25+ available on PATH

## Transformation Steps

### Step 0: Create Migration Copy

Before making any changes, create a copy of the application directory as a sibling folder. All migration work is done on this copy — the original stays untouched.

```bash
APP_DIR=$(pwd)
APP_NAME=$(basename "$APP_DIR")
COPY_DIR="$(dirname "$APP_DIR")/${APP_NAME}-cf-migration"

if [ -d "$COPY_DIR" ]; then
  echo "Migration copy already exists at $COPY_DIR — using it."
else
  cp -r "$APP_DIR" "$COPY_DIR"
  echo "Created migration copy at $COPY_DIR"
fi
cd "$COPY_DIR"
```

Now that we are inside the copy, create the `.migration/` directory and save the config there:

```bash
mkdir -p .migration
```

Save the paths to `.migration/cf-migration-config.json` (create or update the file):

```json
{
  "sourceAppDir": "<original APP_DIR>",
  "migrationAppDir": "<COPY_DIR>"
}
```

> **All subsequent steps in this skill and all downstream skills (`sdk-replacement`, `authentication-xsuaa`, `mta-descriptor`, etc.) must operate inside `$COPY_DIR`. The `.migration/` directory is inside the copy, not the original.**

### Step 1: Update Java Version in pom.xml

**Before:**
```xml
<properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
</properties>
```

**After:**
```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>
```

> **Pin the runtime JRE explicitly.** `sap_java_buildpack_jakarta` supports SapMachine
> 17, 21, and 25, but its implicit JRE choice changes over time — never rely on it.
> Always pin Java 25 in your `mtad.yaml` (or `manifest.yml`) so the runtime matches the
> compile target you set above; otherwise Tomcat fails to load the servlet with
> `java.lang.UnsupportedClassVersionError: ... class file version 69.0, this version of the
> Java Runtime only recognizes class file versions up to N.0` and every request returns
> HTTP 500. The deployed module **must** include:
>
> ```yaml
> properties:
>   JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
>   JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }'
> ```
>
> The `mta-descriptor` skill's `mtad-base.yaml` template already sets these — keep them.
> Rule of thumb: **`maven.compiler.target` must equal the major version in
> `JBP_CONFIG_SAP_MACHINE_JRE`.**

### Step 2: Run OpenRewrite Migration

Execute the OpenRewrite recipes to automatically migrate code:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:RELEASE \
    -Drewrite.activeRecipes=org.openrewrite.java.migrate.UpgradeToJava25,org.openrewrite.java.migrate.jakarta.JakartaEE10 \
    -Drewrite.exportDatatables=true
```

> **Note:**
> - Make sure you have Java SE 25 and the latest Maven version installed
> - The recipe artifact coordinates ensure the latest migration recipes are downloaded
> - **`-Drewrite.activeRecipes` must be passed exactly once.** Maven turns each `-D` into a Java system property, and a property has only one value per key — passing two `-D rewrite.activeRecipes=...` flags silently keeps only the last one and the first recipe is dropped without a warning. Always pass a single comma-separated list. See [the OpenRewrite Maven plugin reference](https://docs.openrewrite.org/reference/rewrite-maven-plugin) for the canonical syntax.

This command will:
- Update Java language features to Java 25
- Replace `javax.*` imports with `jakarta.*`
- Update deprecated API usages
- Migrate web.xml namespace declarations

### Step 3: Handle OpenCMIS Caveat

> **Important:** The `org.apache.chemistry.opencmis` libraries have NOT been migrated to Jakarta EE. If your application uses OpenCMIS (Document Management), you must exclude it from the Jakarta migration.

**Check for OpenCMIS usage:**
```bash
grep -r "opencmis" pom.xml
grep -r "org.apache.chemistry" src/main/java/
```

**If OpenCMIS is used, add these specific exclusions:**
```xml
<dependency>
    <groupId>org.apache.chemistry.opencmis</groupId>
    <artifactId>chemistry-opencmis-client-impl</artifactId>
    <version>${opencmis.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.apache.cxf</groupId>
            <artifactId>cxf-rt-frontend-jaxws</artifactId>
        </exclusion>
        <exclusion>
            <groupId>org.apache.cxf</groupId>
            <artifactId>cxf-rt-transports-http</artifactId>
        </exclusion>
        <exclusion>
            <groupId>org.apache.cxf</groupId>
            <artifactId>cxf-rt-ws-policy</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> **Note:** Add the excluded libraries as separate dependencies using their latest versions, if applicable.

### Step 4: Update web.xml Namespace (if not auto-migrated)

**Before:**
```xml
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
                             http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd"
         version="4.0">
```

**After:**
```xml
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                             https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
         version="6.0">
```

### Step 5: Common Import Migrations

| Before (javax.*) | After (jakarta.*) |
|------------------|-------------------|
| `javax.servlet.*` | `jakarta.servlet.*` |
| `javax.servlet.http.*` | `jakarta.servlet.http.*` |
| `javax.persistence.*` | `jakarta.persistence.*` |
| `javax.annotation.*` | `jakarta.annotation.*` |
| `javax.ejb.*` | `jakarta.ejb.*` |
| `javax.mail.*` | `jakarta.mail.*` |
| `javax.ws.rs.*` | `jakarta.ws.rs.*` |
| `javax.json.*` | `jakarta.json.*` |
| `javax.validation.*` | `jakarta.validation.*` |
| `com.fasterxml.jackson.jaxrs.*` | `com.fasterxml.jackson.jakarta.rs.*` |

> **Important — Jackson JAX-RS providers:** The Jackson JAX-RS provider artifact changed from `jackson-jaxrs-json-provider` (javax) to `jackson-jakarta-rs-json-provider` (jakarta). In addition to the package rename, the class `JacksonJaxbJsonProvider` was renamed to `JacksonXmlBindJsonProvider`. The simpler `JacksonJsonProvider` (which supports JAXB annotations too) is the recommended replacement. OpenRewrite will update Java `import` statements, but it does **not** update class references in `web.xml`. If your `web.xml` configures JAX-RS providers via `<init-param>`, you must update those manually.

#### Update web.xml JAX-RS Provider References

**Detect:**
```bash
grep -r "com.fasterxml.jackson.jaxrs" --include="*.xml" src/main/webapp/
```

If this returns results, update the class name:

**Before:**
```xml
<init-param>
    <param-name>jaxrs.providers</param-name>
    <param-value>
        com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider
    </param-value>
</init-param>
```

**After:**
```xml
<init-param>
    <param-name>jaxrs.providers</param-name>
    <param-value>
        com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
    </param-value>
</init-param>
```

> **Note:** The available classes in the Jakarta artifact are `JacksonJsonProvider` (recommended) and `JacksonXmlBindJsonProvider`. The old name `JacksonJaxbJsonProvider` does **not** exist in the Jakarta artifact — using it will cause `ClassNotFoundException` at runtime even though the JAR is in the WAR.

Also update the Maven dependency:

**Before:**
```xml
<dependency>
    <groupId>com.fasterxml.jackson.jaxrs</groupId>
    <artifactId>jackson-jaxrs-json-provider</artifactId>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>com.fasterxml.jackson.jakarta.rs</groupId>
    <artifactId>jackson-jakarta-rs-json-provider</artifactId>
</dependency>
```

### Step 6: Migrate CXF Swagger 2 to OpenAPI v3 (Conditional)

When migrating to Jakarta EE 10, CXF must be upgraded from 3.x to 4.x (CXF 3.x uses `javax.ws.rs.*`, which is incompatible). In CXF 4.x, the Swagger 2 module (`cxf-rt-rs-service-description-swagger`) was removed and replaced by the OpenAPI v3 module.

> **This step is conditional.** Only apply if the detection check below returns results.

#### Detect

```bash
# Check for Swagger 2 CXF module in pom.xml
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "cxf-rt-rs-service-description-swagger" {} \;

# Check for Swagger 2 annotations in Java code
grep -r "io.swagger.annotations" --include="*.java" .

# Check for Swagger2Feature in Java code or web.xml
grep -r "Swagger2Feature" --include="*.java" --include="*.xml" .
```

If any of the above commands return results, apply the transformations below.

#### 6a: Replace Maven Dependency

**Before:**
```xml
<dependency>
    <groupId>org.apache.cxf</groupId>
    <artifactId>cxf-rt-rs-service-description-swagger</artifactId>
    <version>${cxf.version}</version>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>org.apache.cxf</groupId>
    <artifactId>cxf-rt-rs-service-description-openapi-v3</artifactId>
    <version>${cxf.version}</version>
</dependency>
```

> **Note:** Ensure `${cxf.version}` is 4.x or later (e.g., `4.0.5`). CXF 3.x does not have the openapi-v3 module.

#### 6b: Replace Annotations

| Before (Swagger 2) | After (OpenAPI v3) |
|---------------------|--------------------|
| `import io.swagger.annotations.Api` | `import io.swagger.v3.oas.annotations.tags.Tag` |
| `import io.swagger.annotations.ApiOperation` | `import io.swagger.v3.oas.annotations.Operation` |
| `import io.swagger.annotations.ApiParam` | `import io.swagger.v3.oas.annotations.Parameter` |
| `import io.swagger.annotations.ApiResponse` | `import io.swagger.v3.oas.annotations.responses.ApiResponse` |
| `@Api(value = "...")` | `@Tag(name = "...")` |
| `@ApiOperation(value = "...")` | `@Operation(summary = "...")` |
| `@ApiParam(value = "...")` | `@Parameter(description = "...")` |

#### 6c: Replace Feature Class (if used programmatically)

If CXF features are registered in code (e.g., `ServiceRegistry` or `Application` subclass):

**Before:**
```java
import org.apache.cxf.jaxrs.swagger.Swagger2Feature;

Swagger2Feature feature = new Swagger2Feature();
feature.setBasePath("/api");
```

**After:**
```java
import org.apache.cxf.jaxrs.openapi.OpenApiFeature;

OpenApiFeature feature = new OpenApiFeature();
```

#### 6d: Fix jakarta.xml.ws-api Scope for CXF OpenApiFeature

CXF's `OpenApiFeature` transitively depends on `jakarta.xml.ws.WebServiceFeature`. In many projects, `jakarta.xml.ws-api` is declared with `<scope>provided</scope>`, assuming the application server or buildpack supplies it. However, `sap_java_buildpack_jakarta` does **not** provide `jakarta.xml.ws-api`. This causes a runtime crash:

```
NoClassDefFoundError: jakarta/xml/ws/WebServiceFeature
```

**Detect:**
```bash
# Check if jakarta.xml.ws-api is at provided scope
find . -name "pom.xml" -not -path "*/target/*" -exec grep -B2 -A2 "jakarta.xml.ws-api" {} \;
```

If the dependency exists with `<scope>provided</scope>`, **remove the scope** (or change to `compile`) so it gets packaged into the WAR:

**Before:**
```xml
<dependency>
    <groupId>jakarta.xml.ws</groupId>
    <artifactId>jakarta.xml.ws-api</artifactId>
    <version>4.0.2</version>
    <scope>provided</scope>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>jakarta.xml.ws</groupId>
    <artifactId>jakarta.xml.ws-api</artifactId>
    <version>4.0.2</version>
</dependency>
```

> **Note:** This applies specifically when deploying to `sap_java_buildpack_jakarta`. Other application servers (e.g., full Jakarta EE servers like TomEE) may provide this API, but the SAP Java Buildpack with Tomcat does not.

#### 6e: Update web.xml Servlet Configuration (if applicable)

If the application configures OpenAPI/Swagger via `web.xml` servlet:

**Before:**
```xml
<servlet>
    <servlet-name>Swagger</servlet-name>
    <servlet-class>io.swagger.jaxrs.listing.ApiListingResource</servlet-class>
</servlet>
```

**After:**
```xml
<servlet>
    <servlet-name>OpenApi</servlet-name>
    <servlet-class>io.swagger.v3.jaxrs2.integration.OpenApiServlet</servlet-class>
    <init-param>
        <param-name>openApi.configuration.resourcePackages</param-name>
        <param-value>your.api.package</param-value>
    </init-param>
</servlet>
<servlet-mapping>
    <servlet-name>OpenApi</servlet-name>
    <url-pattern>/openapi/*</url-pattern>
</servlet-mapping>
```

### Step 7: Migrate commons-fileupload to fileupload2 (Conditional)

The original `commons-fileupload` library depends on `javax.servlet` and cannot compile against Jakarta Servlet 6.0. The replacement is `commons-fileupload2-jakarta-servlet6`, which has **breaking API changes**.

> **This step is conditional.** Only apply if the detection check below returns results.

#### Detect

```bash
# Check for old commons-fileupload dependency in pom.xml
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "<artifactId>commons-fileupload</artifactId>" {} \;

# Check for old fileupload imports in Java code
grep -r "org.apache.commons.fileupload\." --include="*.java" . | grep -v "fileupload2"
```

If any of the above commands return results, apply the transformations below.

#### 7a: Replace Maven Dependency

**Before:**
```xml
<dependency>
    <groupId>commons-fileupload</groupId>
    <artifactId>commons-fileupload</artifactId>
    <version>1.4</version>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-fileupload2-jakarta-servlet6</artifactId>
    <version>2.0.0-M2</version>
</dependency>
```

> **Note:** Check [Maven Central](https://mvnrepository.com/artifact/org.apache.commons/commons-fileupload2-jakarta-servlet6) for the latest version.

#### 7b: Update Import Statements

| Before | After |
|--------|-------|
| `org.apache.commons.fileupload.FileItem` | `org.apache.commons.fileupload2.core.FileItem` |
| `org.apache.commons.fileupload.FileItemFactory` | `org.apache.commons.fileupload2.core.DiskFileItemFactory` |
| `org.apache.commons.fileupload.FileUploadException` | `org.apache.commons.fileupload2.core.FileUploadException` |
| `org.apache.commons.fileupload.disk.DiskFileItemFactory` | `org.apache.commons.fileupload2.core.DiskFileItemFactory` |
| `org.apache.commons.fileupload.servlet.ServletFileUpload` | `org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload` |

#### 7c: Update Factory Construction

The `DiskFileItemFactory` constructor changed to a builder pattern:

**Before:**
```java
FileItemFactory factory = new DiskFileItemFactory();
ServletFileUpload upload = new ServletFileUpload(factory);
```

**After:**
```java
DiskFileItemFactory factory = DiskFileItemFactory.builder().get();
JakartaServletFileUpload upload = new JakartaServletFileUpload(factory);
```

#### 7d: Handle IOException on FileItem Methods

`FileItem.get()` and `FileItem.delete()` now throw `IOException` in fileupload2. Any code calling these methods must add exception handling:

**Before:**
```java
byte[] data = fileItem.get();
fileItem.delete();
```

**After:**
```java
try {
    byte[] data = fileItem.get();
} catch (IOException e) {
    throw new RuntimeException("Failed to read uploaded file", e);
}

try {
    fileItem.delete();
} catch (IOException e) {
    // log and ignore, or handle as needed
}
```

Alternatively, if the calling method can propagate `IOException`, add it to the `throws` clause.

### Step 8: Update Dependency Versions

Ensure dependencies use Jakarta EE 10 compatible versions:

```xml
<dependencies>
    <!-- Servlet API -->
    <dependency>
        <groupId>jakarta.servlet</groupId>
        <artifactId>jakarta.servlet-api</artifactId>
        <version>6.0.0</version>
        <scope>provided</scope>
    </dependency>

    <!-- JPA (if used) — do NOT use provided scope, Tomcat does not provide JPA -->
    <dependency>
        <groupId>jakarta.persistence</groupId>
        <artifactId>jakarta.persistence-api</artifactId>
        <version>3.1.0</version>
    </dependency>

    <!-- Annotations -->
    <dependency>
        <groupId>jakarta.annotation</groupId>
        <artifactId>jakarta.annotation-api</artifactId>
        <version>2.1.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Step 9: Fix Test Code — Jakarta Servlet 6.0 Interface Evolution

After OpenRewrite runs, test code with hand-written servlet mocks or stubs will fail to compile for **two reasons**:
1. **New abstract methods** added in Jakarta Servlet 6.0 — your mock class is missing implementations
2. **Removed deprecated methods** — your mock class has `@Override` on methods that no longer exist in the interface

> **Note:** OpenRewrite does not automatically fix either of these issues in hand-written mock classes.

#### Detect Affected Files

```bash
# Find test classes implementing servlet interfaces directly
# Search from project root to handle multi-module projects
grep -rl "implements.*HttpServletRequest\|implements.*HttpServletResponse\|implements.*ServletContext\|implements.*ServletRequest\|implements.*HttpSession" --include="*.java" .
```

If this returns results, those files need fixing.

#### Part A: Remove @Override for Removed/Deprecated Methods

Jakarta Servlet 6.0 removed several deprecated methods. If your mock overrides these, the `@Override` annotation will cause a compile error because the method no longer exists in the interface. **Remove the `@Override` annotation** (you can keep the method body if your tests call it directly, or delete the method entirely).

**Methods removed from `HttpServletRequest` (inherited from `ServletRequest`):**
```java
// REMOVE @Override — method removed in Servlet 6.0
// @Override  <-- delete this line
public String getRealPath(String path) {
    return null;
}
```

**Methods removed from `HttpServletRequest`:**
```java
// REMOVE @Override — method removed in Servlet 6.0
// @Override  <-- delete this line
public boolean isRequestedSessionIdFromUrl() {
    return false;
}
```

**Methods removed from `ServletContext`:**
```java
// REMOVE @Override — method removed in Servlet 6.0
// @Override  <-- delete this line
public String getRealPath(String path) {
    return null;
}
```

> **Tip:** Compile first (`mvn test-compile`) and look for errors like `method does not override or implement a method from a supertype`. Each such error means `@Override` must be removed.

#### Part B: Add Stub Methods for New Abstract Methods

For each affected class, add the missing method implementations that throw `UnsupportedOperationException` (or return a sensible default). These are the new abstract methods added in Jakarta Servlet 6.0:

**HttpServletRequest** (new in Servlet 6.0 — also satisfies `ServletRequest`):
```java
@Override
public String getRequestId() {
    throw new UnsupportedOperationException();
}

@Override
public String getProtocolRequestId() {
    throw new UnsupportedOperationException();
}

@Override
public jakarta.servlet.ServletConnection getServletConnection() {
    throw new UnsupportedOperationException();
}
```

**ServletContext** (new in Servlet 6.0):
```java
@Override
public String getRequestCharacterEncoding() {
    return null;
}

@Override
public void setRequestCharacterEncoding(String encoding) {
    // no-op
}

@Override
public String getResponseCharacterEncoding() {
    return null;
}

@Override
public void setResponseCharacterEncoding(String encoding) {
    // no-op
}

@Override
public int getSessionTimeout() {
    return 0;
}

@Override
public void setSessionTimeout(int sessionTimeout) {
    // no-op
}

@Override
public jakarta.servlet.ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
    return null;
}
```

**HttpServletResponse** (new in Servlet 6.0):
```java
@Override
public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
    throw new UnsupportedOperationException();
}
```

**HttpSession** (new in Servlet 6.0):
```java
@Override
public Accessor getAccessor() {
    throw new UnsupportedOperationException();
}
```

> **Note:** The exact set of missing methods depends on which interfaces your test code implements and which methods were already overridden. The compiler error messages will tell you exactly which methods are missing.

#### Option C: Replace with Mockito 5.x (Recommended for Maintainability)

If the project uses Mockito (or can add it), replace hand-written mock classes entirely. This is future-proof against further interface evolution.

> **CRITICAL:** If the project already uses Mockito 1.x (`mockito-all`), you **must** upgrade to Mockito 5.x. Mockito 1.x uses **cglib** for bytecode generation, which is blocked by Java 25's module system (`InaccessibleObjectException` / `IllegalAccessError` at test runtime). Mockito 5.x uses **ByteBuddy**, which supports Java 25+.

#### Detect Mockito Version Issue

```bash
# Check for old mockito-all (Mockito 1.x bundled cglib)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "mockito-all" {} \;

# Check for outdated mockito-core (pre-5.x)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -A1 "mockito-core" {} \; | grep -E "<version>[1-4]"
```

If either returns results, upgrade:

**Before:**
```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-all</artifactId>
    <version>1.10.19</version>
    <scope>test</scope>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.14.2</version>
    <scope>test</scope>
</dependency>
```

> **Note:** `mockito-all` was a fat JAR that bundled cglib and other dependencies. It was discontinued. `mockito-core` is the correct artifact for Mockito 5.x. The core API (`spy`, `doReturn`, `when`, `mock`) is unchanged, but several supporting APIs have breaking changes — see below.

**Replace hand-written mocks (optional but recommended):**

**Before (hand-written mock):**
```java
public class MockHttpServletRequest implements HttpServletRequest {
    private Map<String, String> parameters = new HashMap<>();
    // ... dozens of manually implemented methods ...
}
```

**After (Mockito):**
```java
import static org.mockito.Mockito.*;

HttpServletRequest request = mock(HttpServletRequest.class);
when(request.getParameter("name")).thenReturn("value");
when(request.getMethod()).thenReturn("GET");
```

#### Mockito 5.x API Breaking Changes

Mockito 5.x removed and relocated several deprecated APIs. These cause **compile errors** after upgrading from Mockito 1.x/2.x/3.x:

##### `org.mockito.Matchers` → `org.mockito.ArgumentMatchers`

The `Matchers` class was removed in Mockito 5.x. All static imports must be updated:

**Detect:**
```bash
grep -r "org.mockito.Matchers" --include="*.java" src/test/
```

**Fix (bulk replacement):**
```bash
grep -rl "org.mockito.Matchers" --include="*.java" src/test/ | xargs sed -i 's/org\.mockito\.Matchers/org.mockito.ArgumentMatchers/g'
```

##### `org.mockito.runners.MockitoJUnitRunner` → `org.mockito.junit.MockitoJUnitRunner`

The `runners` sub-package was removed:

**Detect:**
```bash
grep -r "org.mockito.runners.MockitoJUnitRunner" --include="*.java" src/test/
```

**Fix (bulk replacement):**
```bash
grep -rl "org.mockito.runners.MockitoJUnitRunner" --include="*.java" src/test/ | xargs sed -i 's/org\.mockito\.runners\.MockitoJUnitRunner/org.mockito.junit.MockitoJUnitRunner/g'
```

##### Strict Stubbing — `UnnecessaryStubbingException`

Mockito 5.x defaults to **strict stubbing** when using `@RunWith(MockitoJUnitRunner.class)`. Tests that set up `when(...)` stubs that are never called will fail with `UnnecessaryStubbingException` at runtime.

**Detect:** Run `mvn test` and look for errors containing `unnecessary Mockito stubbings`.

**Fix:** Switch affected test classes to lenient mode:

**Before:**
```java
@RunWith(MockitoJUnitRunner.class)
public class MyServiceTest {
```

**After:**
```java
@RunWith(MockitoJUnitRunner.Silent.class)
public class MyServiceTest {
```

> **Note:** The ideal fix is to remove the unnecessary stubs, but `Silent.class` is a safe quick fix that preserves test behavior. Only use it for tests that actually have unused stubs — do not apply it to all test classes preemptively.

#### EqualsVerifier 3.x Breaking Changes (Conditional)

If the project uses `nl.jqno.equalsverifier` version 3.x+, several API changes may break tests:

**Detect:**
```bash
# Check for equalsverifier in pom.xml
grep -A1 "equalsverifier" pom.xml | grep "<version>"

# Check for removed API usage
grep -r "allFieldsShouldBeUsed" --include="*.java" src/test/
```

##### `allFieldsShouldBeUsed()` removed

In EqualsVerifier 3.x, `allFieldsShouldBeUsed()` was removed because it is now the default behavior. Remove the call:

**Before:**
```java
EqualsVerifier.forClass(MyClass.class)
    .allFieldsShouldBeUsed()
    .usingGetClass()
    .suppress(Warning.NONFINAL_FIELDS)
    .verify();
```

**After:**
```java
EqualsVerifier.forClass(MyClass.class)
    .usingGetClass()
    .suppress(Warning.NONFINAL_FIELDS)
    .verify();
```

##### New stricter field validation

EqualsVerifier 3.x may flag fields not used in `equals()` or classes that inherit `equals()` directly from `Object`. Suppress with the appropriate `Warning`:

```java
// If equals() doesn't use all fields:
.suppress(Warning.ALL_FIELDS_SHOULD_BE_USED)

// If the class doesn't override equals() at all:
.suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT)
```

#### Verify Test Compilation

```bash
mvn test-compile
```

This must succeed before proceeding. If it fails, read the compiler errors — they will list the exact missing methods for each class.

### Step 10: Add JAXB Dependencies for Java 25 (Conditional)

Java 25 removed the `javax.xml.bind` module (JAXB) from the JDK. Libraries that depend on JAXB — such as **Apache POI** (OOXML/PPTX/XLSX processing), **JAXB-based XML marshalling**, or **older SOAP clients** — will fail at runtime with `ClassNotFoundException: javax.xml.bind.JAXBException`.

> **This step is conditional.** Only apply if the detection check below returns results.

#### Detect

```bash
# Check for libraries known to need javax.xml.bind
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l -E "apache.*poi|jaxb|jaxws|javax.xml.bind" {} \;

# Check for direct javax.xml.bind usage in Java code
grep -r "javax.xml.bind" --include="*.java" .

# Check for POI OOXML (commonly needs JAXB)
find . -name "pom.xml" -not -path "*/target/*" -exec grep -l "poi-ooxml" {} \;
```

If any commands return results, the application likely needs explicit JAXB dependencies.

#### Add JAXB API and Runtime

Add both the API and implementation — the API alone is not sufficient for runtime operation:

```xml
<!-- javax.xml.bind API — removed from JDK 17 -->
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>

<!-- JAXB runtime implementation -->
<dependency>
    <groupId>com.sun.xml.bind</groupId>
    <artifactId>jaxb-impl</artifactId>
    <version>2.3.9</version>
</dependency>
```

> **Scope guidance:**
> - If JAXB is used in **main code** (XML marshalling, SOAP clients): use default scope (compile)
> - If JAXB is only needed by **test dependencies** (e.g., POI in test code): use `<scope>test</scope>`
> - If JAXB is needed by a **runtime dependency** that doesn't expose JAXB types in its API: use `<scope>runtime</scope>`

#### Why Both API and Implementation?

The `jaxb-api` artifact provides the `javax.xml.bind.*` interfaces and annotations. The `jaxb-impl` artifact provides the actual marshalling/unmarshalling engine. Without both, libraries like Apache POI will either throw `ClassNotFoundException` (missing API) or silently fail to parse XML content (missing implementation, e.g., PPTX slides returning 0 pages).

> **Note:** If the project has already migrated to Jakarta XML Binding (`jakarta.xml.bind.*`), this step is not needed. This is specifically for **third-party libraries that still use the old `javax.xml.bind` package** (like Apache POI 3.x/4.x).

No new configuration files required for this skill.

### Step 11: Deep Reflection Under Java 25 (Conditional)

Java 25 tightens reflective access to JDK internals and annotation proxies. Code that calls `Method.invoke()` on annotation proxy objects, uses `setAccessible(true)` on JDK-internal fields, or relies on split-package reflective access will throw `InaccessibleObjectException` or `IllegalAccessException` at runtime — even if it compiled and ran fine on Java 8/11.

> **This step is conditional.** Only apply if the detection check below returns results.

#### Detect at-risk idioms

```bash
# Method.invoke on annotation proxies or arbitrary objects
grep -rn "\.invoke(" --include="*.java" src/main/java/ src/test/java/

# setAccessible — broad reflective access
grep -rn "setAccessible(true)" --include="*.java" src/main/java/ src/test/java/

# getDeclaredField / getDeclaredMethod — often paired with setAccessible
grep -rn "getDeclaredField\|getDeclaredMethod" --include="*.java" src/main/java/ src/test/java/

# --add-opens already present — signals prior workaround
grep -rn "add-opens" pom.xml
```

Review each hit. Hits in **test code** are lower risk (tests run with the build JDK, not the buildpack runtime). Hits in **main code** are the ones that will break in production.

#### Affected idiom 1 — `Method.invoke` on annotation proxies

Annotation instances returned by reflection are JDK proxy objects. In Java 25, invoking their methods via `Method.invoke` requires the caller's module to have reflective access to `java.lang.reflect.Proxy` internals, which is no longer granted by default.

**Before (breaks on Java 25):**
```java
Annotation annotation = MyClass.class.getAnnotation(MyAnnotation.class);
Method method = annotation.annotationType().getDeclaredMethod("value");
String value = (String) method.invoke(annotation);  // InaccessibleObjectException
```

**After — use `AnnotatedElement` API directly:**
```java
MyAnnotation annotation = MyClass.class.getAnnotation(MyAnnotation.class);
String value = annotation.value();  // direct call — no reflection needed
```

If the annotation type is only known at runtime:

```java
Annotation annotation = MyClass.class.getAnnotation(annotationType);
// Use annotationType().getMethod() + cast, or switch to MethodHandles:
MethodHandles.Lookup lookup = MethodHandles.publicLookup();
MethodHandle handle = lookup.findVirtual(
    annotationType, "value", MethodType.methodType(String.class));
String value = (String) handle.invoke(annotation);
```

#### Affected idiom 2 — `setAccessible(true)` on JDK-internal fields/methods

**Before (breaks on Java 25):**
```java
Field field = String.class.getDeclaredField("value");
field.setAccessible(true);  // InaccessibleObjectException on JDK internals
byte[] chars = (byte[]) field.get(someString);
```

**After — use the public API instead:**
```java
// For String internals: just use the public API
byte[] chars = someString.getBytes(StandardCharsets.UTF_8);
```

For your own classes (non-JDK), `setAccessible(true)` still works as long as the class is in an unnamed module (which all WAR-deployed code is). Only JDK-internal fields are restricted.

#### Affected idiom 3 — `getDeclaredMethod` + `invoke` as a general dispatch pattern

Frameworks that use reflection for plugin dispatch or annotation-driven invocation often use this pattern:

**Before (may break when target method is on a JDK type):**
```java
Method m = targetClass.getDeclaredMethod(methodName, paramTypes);
m.setAccessible(true);
m.invoke(target, args);
```

**After — switch to `MethodHandles` for JDK types:**
```java
MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup());
MethodHandle handle = lookup.findVirtual(targetClass, methodName,
    MethodType.methodType(returnType, paramTypes));
handle.invoke(target, args);
```

> `MethodHandles.privateLookupIn` requires the caller's module to have `opens` access to the target's package. For your own classes in unnamed modules this is automatic. For JDK types it still requires `--add-opens`.

#### Affected idiom 4 — `--add-opens` as a JVM flag workaround

If the project already works around reflective access restrictions via JVM flags, those flags need to be declared in the SAP Java Buildpack manifest so they are passed to the JVM at startup.

**Detect:**
```bash
grep -rn "add-opens\|add-exports" pom.xml src/
```

**Pass `--add-opens` via the buildpack's `JBP_CONFIG_JAVA_OPTS` env var in `mtad.yaml`:**

```yaml
properties:
  JBP_CONFIG_JAVA_OPTS: '[java_opts: "--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED"]'
```

> **Note:** `--add-opens` is a last-resort workaround. Prefer rewriting to the public API (idioms 1–3 above). Each `--add-opens` is a maintenance liability and may stop working in a future Java release.

#### Verify

```bash
# After fixing: compile and run tests
mvn clean test

# Check that no reflective-access warnings appear in test output
# Java 25 prints WARNING: ... illegal reflective access for any remaining violations
mvn test 2>&1 | grep -i "InaccessibleObjectException\|IllegalAccessException\|illegal reflective"
```

## CF Services

No CF services required for this skill.

## Verification

### 1. Compile Check (Main Code)
```bash
mvn clean compile
```
Should complete without errors.

### 2. Compile Check (Test Code)
```bash
mvn test-compile
```
Should complete without errors. If it fails, see **Step 9** above for fixing Jakarta Servlet 6.0 interface evolution issues in test mocks.

### 3. Verify No javax.* Imports Remain
```bash
# Should return no results (except for allowed exceptions like javax.sql)
grep -rh "^import javax\." src/main/java/ | grep -v "javax.sql" | grep -v "javax.naming"
```

### 4. Run Unit Tests
```bash
mvn test
```

## Common Issues

### Issue: OpenRewrite recipe not found
**Solution:** Ensure you have internet access and Maven can download plugins.

### Issue: Compilation errors after migration
**Cause:** Some APIs have changed between Java EE and Jakarta EE.
**Solution:** Check specific API documentation for breaking changes.

### Issue: Test code fails to compile after OpenRewrite migration
**Cause:** Jakarta Servlet 6.0 added new abstract methods to interfaces like `HttpServletRequest`, `ServletContext`, and `HttpServletResponse`. It also removed deprecated methods like `getRealPath()` and `isRequestedSessionIdFromUrl()`. Hand-written mock/stub classes in test code that implement these interfaces will have both missing new methods and invalid `@Override` annotations on removed methods.
**Solution:** See **Step 9** above. Remove `@Override` on removed methods, add stubs for new methods, or replace hand-written mocks with Mockito.

### Issue: CXF Swagger module not found after Jakarta migration
**Cause:** CXF 4.x (required for `jakarta.ws.rs.*`) removed `cxf-rt-rs-service-description-swagger`. The Swagger 2 module no longer exists in CXF 4.x.
**Solution:** See **Step 6** above. Replace with `cxf-rt-rs-service-description-openapi-v3` and migrate annotations from `io.swagger.annotations` to `io.swagger.v3.oas.annotations`.

### Issue: commons-fileupload compilation errors after Jakarta migration
**Cause:** The original `commons-fileupload` depends on `javax.servlet` and cannot compile against Jakarta Servlet 6.0. The replacement library `commons-fileupload2-jakarta-servlet6` has breaking API changes: different package names, builder-pattern factory construction, and `FileItem.get()`/`delete()` now throw `IOException`.
**Solution:** See **Step 7** above. Replace the dependency and update all import statements, factory construction, and exception handling.

### Issue: javax.sql imports flagged
**Note:** `javax.sql.*` is part of the JDK, not Java EE. These imports should NOT be changed.

### Issue: ClassNotFoundException: com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider at runtime
**Cause:** The Jackson JAX-RS provider package changed from `com.fasterxml.jackson.jaxrs` (javax) to `com.fasterxml.jackson.jakarta.rs` (jakarta). OpenRewrite updates Java `import` statements but does **not** update class references in `web.xml` `<init-param>` values. The CXFServlet fails to initialize because it cannot find the old class name.
**Solution:** See **Step 5** above. Update the `jaxrs.providers` init-param in `web.xml` and the Maven dependency from `jackson-jaxrs-json-provider` to `jackson-jakarta-rs-json-provider`. **Important:** The class was also renamed — `JacksonJaxbJsonProvider` does **not** exist in the Jakarta artifact. Use `com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider` instead. If you first update only the package (to `com.fasterxml.jackson.jakarta.rs.json.JacksonJaxbJsonProvider`), you will get a second `ClassNotFoundException`.

### Issue: WELD-001474 — CDI beans ignored because `jakarta.persistence.RollbackException` not found
**Cause:** `jakarta.persistence-api` is set to `<scope>provided</scope>` in `pom.xml`. Tomcat is a servlet container, not a full Jakarta EE server — it does **not** provide JPA classes. With `provided` scope, the JPA API JAR is excluded from the WAR, so Weld cannot load any class that references `jakarta.persistence.*` types (e.g., `RollbackException`, `NoResultException`). All DAO classes are silently skipped, causing cascading `WELD-001408: Unsatisfied dependencies` errors for every injected DAO.
**Solution:** Remove `<scope>provided</scope>` from the `jakarta.persistence-api` dependency. The servlet API (`jakarta.servlet-api`) should remain `provided` (Tomcat provides it), but JPA, CDI, and other Jakarta EE APIs that Tomcat does not provide must be packaged in the WAR.

### Issue: Mockito tests fail with InaccessibleObjectException or IllegalAccessError on Java 25
**Cause:** Mockito 1.x (`mockito-all`) uses **cglib** for bytecode generation. Java 25's module system blocks cglib's reflective access, causing all mocked tests to fail at runtime.
**Solution:** See **Step 9 Option C** above. Replace `mockito-all` with `mockito-core:5.14.2` which uses ByteBuddy (Java 25 compatible). The test API (`spy`, `when`, `doReturn`, `mock`) is unchanged.

### Issue: ClassNotFoundException: javax.xml.bind.JAXBException on Java 25
**Cause:** Java 25 removed the `javax.xml.bind` module from the JDK. Libraries like Apache POI that use JAXB internally will fail at runtime.
**Solution:** See **Step 10** above. Add `jaxb-api:2.3.1` and `jaxb-impl:2.3.9` as dependencies. Both are required — API alone causes silent parsing failures.

### Issue: NoClassDefFoundError: jakarta/xml/ws/WebServiceFeature at runtime
**Cause:** CXF's `OpenApiFeature` transitively depends on `jakarta.xml.ws.WebServiceFeature`. If `jakarta.xml.ws-api` is at `provided` scope, it won't be packaged into the WAR. The `sap_java_buildpack_jakarta` (Tomcat) does not provide this API, unlike full Jakarta EE servers.
**Solution:** See **Step 6d** above. Remove `<scope>provided</scope>` from `jakarta.xml.ws-api` so it ships in the WAR.

### Issue: Test compilation fails with "cannot find symbol: method anyString()" or "method eq()"
**Cause:** Mockito 5.x removed the `org.mockito.Matchers` class. Static imports like `import static org.mockito.Matchers.any` no longer resolve.
**Solution:** See **Step 9 — Mockito 5.x API Breaking Changes** above. Replace `org.mockito.Matchers` with `org.mockito.ArgumentMatchers` in all test files.

### Issue: Test compilation fails with "cannot find symbol: MockitoJUnitRunner"
**Cause:** Mockito 5.x moved `MockitoJUnitRunner` from `org.mockito.runners` to `org.mockito.junit`.
**Solution:** See **Step 9 — Mockito 5.x API Breaking Changes** above. Replace `org.mockito.runners.MockitoJUnitRunner` with `org.mockito.junit.MockitoJUnitRunner`.

### Issue: Tests fail at runtime with UnnecessaryStubbingException
**Cause:** Mockito 5.x defaults to strict stubbing. Tests with `when(...)` stubs that are never invoked during the test are rejected.
**Solution:** See **Step 9 — Strict Stubbing** above. Switch the `@RunWith` to `MockitoJUnitRunner.Silent.class` for affected test classes, or remove the unused stubs.

### Issue: EqualsVerifier tests fail with "allFieldsShouldBeUsed()" not found
**Cause:** EqualsVerifier 3.x removed the `allFieldsShouldBeUsed()` method because it is now the default.
**Solution:** See **Step 9 — EqualsVerifier 3.x Breaking Changes** above. Remove the `allFieldsShouldBeUsed()` call.

### Issue: EqualsVerifier fails with "Significant fields: equals does not use X" or "Equals is inherited directly from Object"
**Cause:** EqualsVerifier 3.x is stricter about field usage and equals() inheritance.
**Solution:** Suppress with `Warning.ALL_FIELDS_SHOULD_BE_USED` or `Warning.INHERITED_DIRECTLY_FROM_OBJECT` as appropriate.

### Issue: InaccessibleObjectException or IllegalAccessException at runtime on Java 25
**Cause:** Java 25 tightens reflective access to JDK internals and annotation proxies. Code using `Method.invoke()` on annotation proxy objects, `setAccessible(true)` on JDK-internal fields, or `getDeclaredMethod`/`getDeclaredField` on JDK types will fail.
**Solution:** See **Step 11** above. Replace `Method.invoke` on annotation proxies with direct annotation API calls or `MethodHandles`. Replace `setAccessible(true)` on JDK types with the public API equivalent. As a last resort, pass `--add-opens` via `JBP_CONFIG_JAVA_OPTS` in `mtad.yaml`.

## Next Steps

After completing this skill, proceed to:
- [sdk-replacement](../sdk-replacement/SKILL.md) - Replace Neo SDK with SAP Cloud SDK
