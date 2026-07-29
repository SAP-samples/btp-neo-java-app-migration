---
name: destinations
description: Invoke this skill to configure Destination service for outbound HTTP connections. Detects ConnectivityConfiguration or DestinationConfiguration in web.xml or Java code. Replaces Neo JNDI lookups with SAP Cloud SDK DestinationAccessor.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Destinations

Configure the Destination service for outbound HTTP connections.

## Purpose

Replace Neo's `ConnectivityConfiguration` and `DestinationConfiguration` JNDI lookups with SAP Cloud SDK's `DestinationAccessor` API for managing outbound connections.

## Detection

This skill applies if any of these patterns are found:

### In web.xml
```xml
<resource-ref>
    <res-ref-name>connectivityConfiguration</res-ref-name>
    <res-type>com.sap.core.connectivity.api.configuration.ConnectivityConfiguration</res-type>
</resource-ref>
```

### In Java source files
```java
import com.sap.core.connectivity.api.configuration.ConnectivityConfiguration;
import com.sap.core.connectivity.api.configuration.DestinationConfiguration;

// JNDI lookup pattern
Context ctx = new InitialContext();
ConnectivityConfiguration config =
    (ConnectivityConfiguration) ctx.lookup("java:comp/env/connectivityConfiguration");
DestinationConfiguration destConfig = config.getConfiguration("my-destination");
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

## Transformation Steps

### Step 0: Detect existing JSON and HTTP conventions

Before generating any code, scan for existing library usage:

```bash
# JSON libraries
grep -E "fasterxml\.jackson|com\.google\.code\.gson|org\.json|jakarta\.json" pom.xml
grep -rl "ObjectMapper\|new Gson()\|new JSONObject\|Json\.create" --include="*.java" src/main/java/ 2>/dev/null | head -5

# HTTP client libraries
grep -E "httpclient|httpclient5|okhttp" pom.xml
```

**Rule:** Reuse what is found. Only introduce a new library if nothing is present. Default: SAP Cloud SDK `HttpClientAccessor` for HTTP (already present after `sdk-replacement`).

### Step 1: Remove Resource References from web.xml

**Remove these from web.xml:**
```xml
<resource-ref>
    <res-ref-name>connectivityConfiguration</res-ref-name>
    <res-type>com.sap.core.connectivity.api.configuration.ConnectivityConfiguration</res-type>
</resource-ref>
```

Also remove AuthenticationHeaderProvider if present:
```xml
<resource-ref>
    <res-ref-name>authenticationHeaderProvider</res-ref-name>
    <res-type>com.sap.core.connectivity.api.authentication.AuthenticationHeaderProvider</res-type>
</resource-ref>
```

> **Note:** The SAP Cloud SDK handles authentication automatically - no manual header management needed.

### Step 2: Update Import Statements

**Before:**
```java
import javax.naming.Context;
import javax.naming.InitialContext;
import com.sap.core.connectivity.api.configuration.ConnectivityConfiguration;
import com.sap.core.connectivity.api.configuration.DestinationConfiguration;
```

**After:**
```java
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.exception.DestinationNotFoundException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;
import static com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient;
```

### Step 3: Replace Destination Lookup Code

**Before (Neo):**
```java
public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

    String destinationName = "my-destination";

    try {
        // JNDI lookup for connectivity configuration
        Context ctx = new InitialContext();
        ConnectivityConfiguration configuration =
            (ConnectivityConfiguration) ctx.lookup("java:comp/env/connectivityConfiguration");

        // Get destination configuration
        DestinationConfiguration destConfiguration =
            configuration.getConfiguration(destinationName);

        if (destConfiguration == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Destination not found: " + destinationName);
            return;
        }

        // Get URL from destination
        String url = destConfiguration.getProperty("URL");
        URL targetUrl = new URL(url);

        // Get proxy settings
        String proxyType = destConfiguration.getProperty("ProxyType");
        Proxy proxy = getProxy(proxyType);

        // Open connection with proxy
        HttpURLConnection urlConnection = (HttpURLConnection) targetUrl.openConnection(proxy);

        // Copy response
        InputStream instream = urlConnection.getInputStream();
        // ... handle response

    } catch (NamingException e) {
        throw new ServletException(e);
    }
}
```

**After (Cloud Foundry):**
```java
public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

    String destinationName = "my-destination";

    try {
        // Get destination using SAP Cloud SDK
        HttpDestination destination;
        try {
            destination = DestinationAccessor.getDestination(destinationName).asHttp();
        } catch (DestinationNotFoundException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Destination not found: " + destinationName);
            return;
        }

        // Get HTTP client configured for the destination
        // Automatically handles proxy, authentication, etc.
        HttpClient httpClient = getHttpClient(destination);

        // Make request
        HttpGet httpGet = new HttpGet(destination.getUri());
        HttpResponse destinationResponse = httpClient.execute(httpGet);

        // Check status
        int statusCode = destinationResponse.getStatusLine().getStatusCode();
        if (statusCode != HttpServletResponse.SC_OK) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Destination returned: " + statusCode);
            return;
        }

        // Write response
        destinationResponse.getEntity().writeTo(response.getOutputStream());

    } catch (Exception e) {
        throw new ServletException("Connectivity failed: " + e.getMessage(), e);
    }
}
```

### Step 4: Handle Destination Properties

If you need to access destination properties directly:

**Before (Neo):**
```java
DestinationConfiguration destConfig = config.getConfiguration(destinationName);
String url = destConfig.getProperty("URL");
String user = destConfig.getProperty("User");
String proxyType = destConfig.getProperty("ProxyType");
```

**After (Cloud Foundry):**
```java
import com.sap.cloud.sdk.cloudplatform.connectivity.Destination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationProperty;

Destination destination = DestinationAccessor.getDestination(destinationName);

// Get URI
URI uri = destination.asHttp().getUri();

// Get custom properties
Optional<String> customProperty = destination.get("customProperty");

// Check proxy type
ProxyType proxyType = destination.asHttp().getProxyType();
boolean isOnPremise = proxyType == ProxyType.ON_PREMISE;
```

### Step 5: Add runtime dependency to pom.xml

The SAP Cloud SDK's `DestinationAccessor` requires `connectivity-destination-service` on the runtime classpath. Without it the SDK compiles fine but throws `DestinationNotFoundException` for every destination at runtime.

Check if it is already present:

```bash
grep -q "connectivity-destination-service" pom.xml && echo "already present" || echo "MISSING — will add"
```

If missing, add it to the `<dependencies>` section of `pom.xml`:

```xml
<!-- Required at runtime — DestinationAccessor fails without this -->
<dependency>
    <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
    <artifactId>connectivity-destination-service</artifactId>
    <scope>runtime</scope>
</dependency>
```

> Add this inside the existing `<dependencies>` block, alongside other SAP Cloud SDK dependencies added by the `sdk-replacement` skill. No version needed — it is managed by `sdk-modules-bom`.

### Step 6: Update MTA Descriptor

Add destination service to `mtad.yaml`:

```yaml
modules:
  - name: ${app-name}
    type: java.tomcat
    path: target/<artifactId>.war
    parameters:
      buildpack: sap_java_buildpack_jakarta
      disk-quota: 1024M
      memory: 1024M
    properties:
      ENABLE_SECURITY_JAVA_API_V2: true
      SET_LOGGING_LEVEL: 'ROOT: INFO'
    requires:
      - name: ${app-name}-destination

resources:
  - name: ${app-name}-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite
```

### Step 7: Create Destinations in BTP Cockpit

Create destinations via BTP Cockpit or programmatically:

#### Option A: BTP Cockpit
1. Navigate to your subaccount
2. Go to Connectivity → Destinations
3. Click "New Destination"
4. Configure:
   - **Name:** `my-destination`
   - **Type:** HTTP
   - **URL:** `https://api.example.com`
   - **Proxy Type:** Internet (or OnPremise)
   - **Authentication:** NoAuthentication / BasicAuthentication / OAuth2...

#### Option B: Destination Service REST API
```bash
# Get access token
TOKEN=$(cf oauth-token | sed 's/bearer //')

# Create destination
curl -X POST \
  "https://destination-configuration.cfapps.${region}.hana.ondemand.com/destination-configuration/v1/subaccountDestinations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "my-destination",
    "Type": "HTTP",
    "URL": "https://api.example.com",
    "ProxyType": "Internet",
    "Authentication": "NoAuthentication"
  }'
```

## Configuration Files

No new configuration files in the application. Destinations are managed in BTP Cockpit.

## Required Runtime Dependency

The SAP Cloud SDK's `DestinationAccessor` requires `connectivity-destination-service` on the runtime classpath. Add it to `pom.xml`:

```xml
<!-- Required at runtime — DestinationAccessor fails with DESTINATION_NOT_FOUND without this -->
<dependency>
    <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
    <artifactId>connectivity-destination-service</artifactId>
    <scope>runtime</scope>
</dependency>
```

> **Why `runtime` scope?** The artifact contains the CF-specific `DestinationService` implementation that the SDK loads via ServiceLoader at runtime. The compilation succeeds without it (the API is in `scp-cf`), but `DestinationAccessor.getDestination()` silently falls back to a no-op loader and throws `DestinationNotFoundException` for every call.

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `destination` | lite | Destination configuration service |

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Test Destination Access
Add a test endpoint:

```java
@WebServlet("/test/destination")
public class DestinationTestServlet extends HttpServlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String destName = req.getParameter("name");
        try {
            HttpDestination dest = DestinationAccessor.getDestination(destName).asHttp();
            resp.getWriter().println("Destination found: " + dest.getUri());
        } catch (DestinationNotFoundException e) {
            resp.sendError(404, "Destination not found: " + destName);
        }
    }
}
```

### 3. Deploy and Test
```bash
mvn clean package
cf deploy . -f

# Test
curl "https://${app-url}/test/destination?name=my-destination"
```

## Common Issues

### Issue: `DestinationNotFoundException` — destination exists in BTP Cockpit but SDK throws anyway

**Root cause:** Missing `connectivity-destination-service` runtime dependency in `pom.xml`. The SDK compiles without it (the API is in `scp-cf`), but at runtime the CF-specific destination loader is not on the classpath. `DestinationAccessor` falls back to a no-op loader and throws `DestinationNotFoundException` for every destination — even ones that exist.

**Fix:** Add to `pom.xml`:
```xml
<dependency>
    <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
    <artifactId>connectivity-destination-service</artifactId>
    <scope>runtime</scope>
</dependency>
```

Verify after redeploy:
```bash
# Should show the destination properties, not an error
cf ssh <app-name> -c 'env | grep VCAP_SERVICES' | python3 -m json.tool | grep -A5 destination
```

### Issue: `DestinationNotFoundException` — name mismatch
**Cause:** Destination name in code doesn't match exactly what is configured in BTP Cockpit (case-sensitive).
**Fix:** Verify destination name in BTP Cockpit → Connectivity → Destinations. Names are case-sensitive.

### Issue: 403 Forbidden when accessing destination
**Cause:** Authentication configuration issue in the destination definition.
**Fix:** Check destination authentication settings and credentials in BTP Cockpit.

### Issue: Connection timeout
**Cause:** Network or firewall issue.
**Fix:**
- For Internet destinations: check URL is reachable from CF
- For OnPremise: check Cloud Connector configuration (see `connectivity-onpremise` skill)

## API Reference

### DestinationAccessor
```java
// Get destination by name
Destination dest = DestinationAccessor.getDestination("name");

// Get as HTTP destination
HttpDestination httpDest = dest.asHttp();

// Try to get destination (returns Optional)
Option<Destination> maybeDest = DestinationAccessor.tryGetDestination("name");
```

### HttpDestination
```java
// Get URI
URI uri = httpDest.getUri();

// Get proxy type
ProxyType proxyType = httpDest.getProxyType();

// Get headers
Collection<Header> headers = httpDest.getHeaders();
```

### HttpClientAccessor
```java
// Get HTTP client for destination
HttpClient client = HttpClientAccessor.getHttpClient(httpDest);

// Execute request
HttpResponse response = client.execute(new HttpGet(httpDest.getUri()));
```

## Next Steps

After completing this skill, proceed to:
- [../connectivity-onpremise/SKILL.md](../connectivity-onpremise/SKILL.md) - For on-premise connectivity
- [../mail-destinations/SKILL.md](../mail-destinations/SKILL.md) - For mail configuration via destinations
