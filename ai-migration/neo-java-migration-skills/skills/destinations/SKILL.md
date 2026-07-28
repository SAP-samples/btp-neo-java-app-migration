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

### Step 5: Update MTA Descriptor

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

### Step 6: Create Destinations in BTP Cockpit

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

### Issue: DestinationNotFoundException
**Cause:** Destination not created or name mismatch.
**Solution:**
1. Verify destination exists in BTP Cockpit
2. Check destination name matches exactly (case-sensitive)
3. Ensure destination service is bound to application

### Issue: 403 Forbidden when accessing destination
**Cause:** Authentication configuration issue.
**Solution:** Check destination authentication settings and credentials.

### Issue: Connection timeout
**Cause:** Network or firewall issue.
**Solution:**
- For Internet destinations: Check URL is reachable
- For OnPremise: Check Cloud Connector configuration (see connectivity-onpremise skill)

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
