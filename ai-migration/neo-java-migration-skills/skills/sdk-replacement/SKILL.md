---
name: sdk-replacement
description: Invoke this skill to replace Neo Java Web API with SAP Cloud SDK. Detects neo-java-web-api or scp-neo dependencies in pom.xml, or com.sap.cloud.*/com.sap.core.connectivity.* imports. Required foundation skill - invoke after jakarta-java25-migration.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Replace Neo Java Web API with SAP Cloud SDK

Replace the Neo-specific Java Web API with SAP Cloud SDK for Cloud Foundry compatibility.

## Purpose

The Neo Java Web API (`com.sap.cloud:neo-java-web-api`) is not available in Cloud Foundry. This skill replaces it with SAP Cloud SDK dependencies that provide equivalent functionality for the CF environment.

## Detection

This skill applies if any of these patterns are found:

### In pom.xml
```xml
<dependency>
    <groupId>com.sap.cloud</groupId>
    <artifactId>neo-java-web-api</artifactId>
</dependency>

<!-- OR -->
<dependency>
    <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
    <artifactId>scp-neo</artifactId>
</dependency>
```

### In Java source files
```java
import com.sap.cloud.account.*;
import com.sap.core.connectivity.api.*;
import com.sap.security.um.user.*;
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **jakarta-java25-migration** - `Use the jakarta-java25-migration skill`
   - Migrates to Java 25 and Jakarta EE 10
   - REQUIRED before this skill

## Transformation Steps

### Step 1: Remove Neo SDK Dependencies

Remove from `pom.xml`:

```xml
<!-- REMOVE this dependency -->
<dependency>
    <groupId>com.sap.cloud</groupId>
    <artifactId>neo-java-web-api</artifactId>
    <version>${neo.version}</version>
    <scope>provided</scope>
</dependency>

<!-- ALSO REMOVE if present -->
<dependency>
    <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
    <artifactId>scp-neo</artifactId>
    <version>${sdk.version}</version>
</dependency>
```

### Step 2: Add SAP Cloud SDK BOM Dependencies

Add to `<dependencyManagement>` section:

```xml
<dependencyManagement>
    <dependencies>
        <!-- CF Tomcat BOM - provides servlet container and runtime -->
        <dependency>
            <groupId>com.sap.cloud.sjb.cf</groupId>
            <artifactId>cf-tomcat-bom</artifactId>
            <version>${cf-tomcat-bom-version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>

        <!-- SAP Cloud SDK BOM - provides SDK modules -->
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

### Step 3: Add Version Properties

Add to `<properties>` section:

```xml
<properties>
    <!-- Latest stable versions as of 2025 -->
    <cf-tomcat-bom-version>2.22.0</cf-tomcat-bom-version>
    <sdk-modules-bom-version>5.14.0</sdk-modules-bom-version>
</properties>
```

> **Note:** Check for latest versions at:
> - [cf-tomcat-bom on Maven Central](https://mvnrepository.com/artifact/com.sap.cloud.sjb.cf/cf-tomcat-bom)
> - [sdk-modules-bom on Maven Central](https://mvnrepository.com/artifact/com.sap.cloud.sdk/sdk-modules-bom)

### Step 4: Add SCP-CF and Security Dependencies

Add to `<dependencies>` section:

```xml
<dependencies>
    <!-- SAP Cloud SDK for Cloud Foundry -->
    <dependency>
        <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
        <artifactId>scp-cf</artifactId>
    </dependency>

    <!-- SAP Cloud Security Java API (required for XSUAA integration) -->
    <dependency>
        <groupId>com.sap.cloud.security</groupId>
        <artifactId>java-api</artifactId>
    </dependency>

    <!-- Servlet API (provided by buildpack) -->
    <dependency>
        <groupId>jakarta.servlet</groupId>
        <artifactId>jakarta.servlet-api</artifactId>
    </dependency>
</dependencies>
```

> **Note:** The `jakarta.servlet-api` does not need an explicit `<scope>provided</scope>` as this is inherited from the BOM.

### Step 5: Add Additional Dependencies (as needed)

Depending on what Neo APIs you were using, add these dependencies:

```xml
<!-- If using OpenCMIS (Document Management) -->
<dependency>
    <groupId>org.apache.chemistry.opencmis</groupId>
    <artifactId>chemistry-opencmis-client-impl</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.chemistry.opencmis</groupId>
    <artifactId>chemistry-opencmis-client-api</artifactId>
</dependency>

<!-- If using Mail -->
<dependency>
    <groupId>com.sun.mail</groupId>
    <artifactId>jakarta.mail</artifactId>
</dependency>

<!-- If using JSP -->
<dependency>
    <groupId>jakarta.servlet.jsp</groupId>
    <artifactId>jakarta.servlet.jsp-api</artifactId>
    <scope>provided</scope>
</dependency>

<!-- If using EL -->
<dependency>
    <groupId>jakarta.el</groupId>
    <artifactId>jakarta.el-api</artifactId>
    <scope>provided</scope>
</dependency>

<!-- If using WebSocket -->
<dependency>
    <groupId>jakarta.websocket</groupId>
    <artifactId>jakarta.websocket-api</artifactId>
    <scope>provided</scope>
</dependency>
```

### Step 6: Migrate SAPUI5 ResourceServlet (Conditional)

In Neo, the platform-provided `com.sap.ui5.resource.ResourceServlet` served SAPUI5 libraries at `/resources/*`. This servlet class does **not exist** in Cloud Foundry. If HTML files reference SAPUI5 via a relative `resources/sap-ui-core.js` path, the page will load but render **blank** because the UI5 library returns 404.

#### Detect

```bash
# Check for ResourceServlet in web.xml
grep -r "ResourceServlet\|com.sap.ui5.resource" --include="*.xml" src/main/webapp/

# Check for relative SAPUI5 loading in HTML files
grep -r 'src="resources/sap-ui-core.js"' --include="*.html" src/main/webapp/
```

If either returns results, apply the fixes below.

#### 6a: Remove ResourceServlet from web.xml

Remove the servlet declaration and mapping:

```xml
<!-- REMOVE: Neo-specific SAPUI5 resource servlet -->
<servlet>
    <servlet-name>ResourceServlet</servlet-name>
    <servlet-class>com.sap.ui5.resource.ResourceServlet</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>ResourceServlet</servlet-name>
    <url-pattern>/resources/*</url-pattern>
</servlet-mapping>
```

#### 6b: Update HTML Files to Use SAPUI5 CDN

Replace the relative path with the SAPUI5 CDN URL in **all** HTML files.

> **CRITICAL:** The old CDN domain `sapui5.hana.ondemand.com` no longer serves older SAPUI5 versions (pre-1.71) and may be fully retired. Always use the current CDN domain `ui5.sap.com`.

**Before:**
```html
<script id="sap-ui-bootstrap" type="text/javascript"
    src="resources/sap-ui-core.js"
    ...></script>
```

**After:**
```html
<script id="sap-ui-bootstrap" type="text/javascript"
    src="https://ui5.sap.com/resources/sap-ui-core.js"
    ...></script>
```

**Bulk fix (relative paths):**
```bash
grep -rl 'src="resources/sap-ui-core.js"' --include="*.html" src/main/webapp/ | \
    xargs sed -i 's|src="resources/sap-ui-core.js"|src="https://ui5.sap.com/resources/sap-ui-core.js"|g'
```

**Bulk fix (old CDN domain):**
```bash
grep -rl 'sapui5.hana.ondemand.com' --include="*.html" src/main/webapp/ | \
    xargs sed -i 's|sapui5.hana.ondemand.com|ui5.sap.com|g'
```

> **Note:** If the application requires a specific SAPUI5 version, pin it in the URL: `https://ui5.sap.com/1.120.0/resources/sap-ui-core.js`. Older versions (pre-1.71) are no longer available on the CDN — if the app pins an old version, update to a supported LTS version (e.g., 1.108.x or 1.120.x).

#### 6c: Update Deprecated SAPUI5 Themes (Conditional)

The `sap_bluecrystal` theme was removed in SAPUI5 1.40+. If your application references it, it will fail to load the theme CSS, causing either a broken layout or a blank page.

**Detect:**
```bash
grep -r "sap_bluecrystal" --include="*.html" --include="*.json" src/main/webapp/
```

**Fix:** Replace with `sap_fiori_3` (Quartz Light) or `sap_horizon` (latest):

```bash
# Update in HTML files
grep -rl "sap_bluecrystal" --include="*.html" src/main/webapp/ | \
    xargs sed -i 's|sap_bluecrystal|sap_fiori_3|g'

# Update in manifest.json
grep -rl "sap_bluecrystal" --include="*.json" src/main/webapp/ | \
    xargs sed -i 's|sap_bluecrystal|sap_fiori_3|g'
```

| Removed Theme | Replacement |
|---------------|------------|
| `sap_bluecrystal` | `sap_fiori_3` or `sap_horizon` |
| `sap_belize` | `sap_fiori_3` or `sap_horizon` |

### Step 7: Verify Servlet Mappings

If the Neo web.xml had `<servlet-mapping>` entries (e.g., for CXF/JAX-RS servlets), ensure they are preserved in the CF web.xml. A common oversight is copying the `<servlet>` declaration without its `<servlet-mapping>`, which causes REST APIs to return 404.

**Detect:**
```bash
# Check if web.xml has servlet declarations without corresponding mappings
grep "<servlet-name>" src/main/webapp/WEB-INF/web.xml
```

For each `<servlet-name>`, verify a corresponding `<servlet-mapping>` exists. For JAX-RS applications using CXF:

```xml
<servlet-mapping>
    <servlet-name>CXFServlet</servlet-name>
    <url-pattern>/rest/*</url-pattern>
</servlet-mapping>
```

> **Note:** If the JAX-RS Application class has `@ApplicationPath("/rest")` and you are using TomEE, auto-discovery may work without an explicit mapping. However, if `CXFNonSpringJaxrsServlet` is explicitly declared in web.xml, the `<servlet-mapping>` is required — the explicit declaration overrides auto-discovery.

> **Warning (TomEE only):** If the application uses TomEE (EJB support), `CXFNonSpringJaxrsServlet` **must be removed entirely** from `web.xml` — not just have its mapping added. This servlet bypasses TomEE's CDI container, causing `@Inject` and `@EJB` fields in JAX-RS endpoints to remain `null` (`NullPointerException` at runtime). See the **tomee-runtime** skill, Step 6 for details.

### Step 8: Update Import Statements

Replace Neo-specific imports with SAP Cloud SDK equivalents:

| Neo Import | SAP Cloud SDK Import |
|------------|---------------------|
| `com.sap.core.connectivity.api.configuration.DestinationConfiguration` | `com.sap.cloud.sdk.cloudplatform.connectivity.Destination` |
| `com.sap.core.connectivity.api.configuration.ConnectivityConfiguration` | `com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor` |
| `com.sap.cloud.account.TenantContext` | `com.sap.cloud.sdk.cloudplatform.tenant.TenantAccessor` |
| `com.sap.security.um.user.UserProvider` | Via XSUAA token (see authentication skill) |

### Step 9: Complete pom.xml Example

See [assets/pom-cf-tomcat.xml](assets/pom-cf-tomcat.xml) for a complete template.

## Configuration Files

No new configuration files required for this skill alone. The MTA descriptor will be created after all skills are applied.

## CF Services

No CF services required for this skill alone. Services are defined by feature-specific skills.

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Verify Dependencies
```bash
mvn dependency:tree | grep -E "scp-cf|cf-tomcat-bom|sdk-modules-bom"
```

Expected output should show the new SDK dependencies.

### 3. Check for Remaining Neo Imports
```bash
grep -rh "com.sap.cloud.account\|com.sap.core.connectivity\|com.sap.security.um" src/main/java/
```
Should return results that need to be replaced with SDK equivalents.

## Common Issues

### Issue: Missing dependency versions
**Cause:** BOM import not set up correctly.
**Solution:** Ensure `<dependencyManagement>` section includes both BOMs with `<type>pom</type>` and `<scope>import</scope>`.

### Issue: Conflicting dependencies
**Solution:** Use `mvn dependency:tree` to identify conflicts, then add exclusions as needed.

### Issue: NoClassDefFoundError at runtime
**Cause:** Dependency scope issue.
**Solution:** Verify `jakarta.servlet-api` has `<scope>provided</scope>` (it's provided by the buildpack).

### Issue: Blank/empty page after deployment — SAPUI5 app renders nothing
**Cause:** Three common causes, all resulting in the UI5 framework failing to load (the HTML page returns 200 but the `<div>` stays empty):
1. In Neo, `com.sap.ui5.resource.ResourceServlet` served SAPUI5 at `/resources/*`. This servlet doesn't exist in CF. HTML files loading `src="resources/sap-ui-core.js"` get a 404.
2. The old CDN domain `sapui5.hana.ondemand.com` no longer serves older SAPUI5 versions — pinned versions like `1.38.x` return 404.
3. The `sap_bluecrystal` theme was removed in SAPUI5 1.40+ — if referenced, the theme CSS fails to load.

**Diagnosis:** Open browser developer tools (F12 → Network tab). Look for 404 responses on `sap-ui-core.js` or theme CSS files.
**Solution:** See **Step 6** above. Update HTML files to use the current CDN domain `ui5.sap.com`, pin a supported SAPUI5 version (1.108.x or 1.120.x), replace `sap_bluecrystal` with `sap_fiori_3`, and remove the ResourceServlet declaration from web.xml.

### Issue: REST API endpoints return 404 after deployment
**Cause:** The CXF/JAX-RS servlet was declared in web.xml but the `<servlet-mapping>` was missing. Without a mapping, the servlet is registered but unreachable.
**Solution:** See **Step 7** above. Add a `<servlet-mapping>` for the servlet (e.g., `<url-pattern>/rest/*</url-pattern>`).

## Next Steps

After completing this skill, proceed to applicable scenario skills:
- [../authentication-xsuaa/SKILL.md](../authentication-xsuaa/SKILL.md) - If your app uses web authentication
- [../destinations/SKILL.md](../destinations/SKILL.md) - If your app uses destinations
- [../persistence-hana/SKILL.md](../persistence-hana/SKILL.md) - If your app uses database
