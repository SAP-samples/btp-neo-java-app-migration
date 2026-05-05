---
name: connectivity-onpremise
description: Invoke this skill to enable on-premise connectivity via Cloud Connector. Detects HC_OP_HTTP_PROXY_* environment variables, manual proxy config, or ProxyType=OnPremise. Replaces Neo proxy handling with SAP Cloud SDK automatic connectivity.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Connectivity (On-Premise)

Enable connectivity to on-premise systems via Cloud Connector.

## Purpose

Replace Neo's manual proxy configuration (`HC_OP_HTTP_PROXY_*` environment variables) with SAP Cloud SDK's automatic handling of on-premise connectivity through Cloud Connector.

## Detection

This skill applies if any of these patterns are found:

### In Java source files
```java
// Manual proxy configuration
String proxyHost = System.getenv("HC_OP_HTTP_PROXY_HOST");
String proxyPort = System.getenv("HC_OP_HTTP_PROXY_PORT");

// OnPremise proxy type handling
if ("OnPremise".equals(proxyType)) {
    // Configure proxy...
}

// Consumer account header
urlConnection.setRequestProperty("SAP-Connectivity-ConsumerAccount", accountId);
```

### In destination configuration
```
ProxyType=OnPremise
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

2. **destinations** - `Use the destinations skill`
   - Configures Destination service
   - REQUIRED before this skill

Also required:
- Cloud Connector installed and configured

## Transformation Steps

### Step 1: Remove AuthenticationHeaderProvider from web.xml

If present, remove the AuthenticationHeaderProvider resource reference:
```xml
<resource-ref>
    <res-ref-name>authenticationHeaderProvider</res-ref-name>
    <res-type>com.sap.core.connectivity.api.authentication.AuthenticationHeaderProvider</res-type>
</resource-ref>
```

> **Note:** The SAP Cloud SDK handles authentication headers automatically for on-premise connections.

### Step 2: Remove Manual Proxy Handling

The SAP Cloud SDK handles all proxy configuration automatically when `ProxyType=OnPremise` is detected in the destination.

**Before (Neo) - REMOVE ALL THIS CODE:**
```java
private Proxy getProxy(String proxyType) {
    Proxy proxy = Proxy.NO_PROXY;
    String proxyHost = null;
    String proxyPort = null;

    if (ON_PREMISE_PROXY.equals(proxyType)) {
        // Get proxy for on-premise destinations
        proxyHost = System.getenv("HC_OP_HTTP_PROXY_HOST");
        proxyPort = System.getenv("HC_OP_HTTP_PROXY_PORT");
    } else {
        proxyHost = System.getProperty("https.proxyHost");
        proxyPort = System.getProperty("https.proxyPort");
    }

    if (proxyPort != null && proxyHost != null) {
        int proxyPortNumber = Integer.parseInt(proxyPort);
        proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPortNumber));
    }

    return proxy;
}

private void injectHeader(HttpURLConnection urlConnection, String proxyType) {
    if (ON_PREMISE_PROXY.equals(proxyType)) {
        urlConnection.setRequestProperty("SAP-Connectivity-ConsumerAccount",
            tenantContext.getTenant().getAccount().getId());
    }
}
```

**After (Cloud Foundry) - Simple and automatic:**
```java
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import org.apache.http.client.HttpClient;
import static com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor.getHttpClient;

public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

    String destinationName = request.getParameter("destname");
    if (destinationName == null) {
        destinationName = "on-premise-destination";
    }

    try {
        // SAP Cloud SDK handles all proxy configuration automatically!
        HttpDestination destination = DestinationAccessor.getDestination(destinationName).asHttp();

        // Get HTTP client - SDK automatically:
        // - Configures proxy for on-premise destinations
        // - Adds required headers (SAP-Connectivity-ConsumerAccount, etc.)
        // - Handles Cloud Connector integration
        HttpClient httpClient = getHttpClient(destination);
        HttpGet httpGet = new HttpGet(destination.getUri());

        HttpResponse destinationResponse = httpClient.execute(httpGet);

        int statusCode = destinationResponse.getStatusLine().getStatusCode();
        if (statusCode != HttpServletResponse.SC_OK) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Destination returned: " + statusCode);
            return;
        }

        destinationResponse.getEntity().writeTo(response.getOutputStream());

    } catch (Exception e) {
        throw new ServletException("Connectivity failed: " + e.getMessage(), e);
    }
}
```

### Step 2: Remove Resource References

Remove from `web.xml`:
```xml
<!-- REMOVE -->
<resource-ref>
    <res-ref-name>connectivityConfiguration</res-ref-name>
    <res-type>com.sap.core.connectivity.api.configuration.ConnectivityConfiguration</res-type>
</resource-ref>

<!-- REMOVE if present -->
<resource-ref>
    <res-ref-name>TenantContext</res-ref-name>
    <res-type>com.sap.cloud.account.TenantContext</res-type>
</resource-ref>
```

### Step 3: Update MTA Descriptor

Add both destination and connectivity services to `mtad.yaml`:

```yaml
modules:
  - name: ${app-name}
    type: java.tomcat
    path: target/${app-name}.war
    parameters:
      buildpack: sap_java_buildpack_jakarta
      disk-quota: 512MB
      memory: 512MB
    properties:
      ENABLE_SECURITY_JAVA_API_V2: true
      SET_LOGGING_LEVEL: 'ROOT: INFO'
    requires:
      - name: ${app-name}-destination
      - name: ${app-name}-connectivity

resources:
  - name: ${app-name}-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite

  - name: ${app-name}-connectivity
    type: org.cloudfoundry.managed-service
    parameters:
      service: connectivity
      service-plan: lite
```

### Step 4: Configure Cloud Connector

In Cloud Connector Administration:

1. **Add Subaccount**
   - Region: Your CF region
   - Subaccount: Your subaccount ID
   - Display Name: Descriptive name

2. **Add Virtual Host Mapping**
   - Click "Cloud to On-Premise"
   - Add new mapping:
     - **Back-end Type:** Non-SAP System / HTTP
     - **Protocol:** HTTP or HTTPS
     - **Internal Host:** `your-onprem-server.local`
     - **Internal Port:** `8080`
     - **Virtual Host:** `onprem-virtual`
     - **Virtual Port:** `8080`

3. **Add Resources**
   - Path: `/` (or specific paths)
   - Access Policy: Allow

### Step 5: Create On-Premise Destination

In BTP Cockpit, create destination:

| Property | Value |
|----------|-------|
| Name | `on-premise-destination` |
| Type | HTTP |
| URL | `http://onprem-virtual:8080` |
| Proxy Type | **OnPremise** |
| Authentication | NoAuthentication (or as needed) |
| Location ID | (Optional) Cloud Connector location ID |

For destinations with Location ID:
```json
{
    "Name": "on-premise-destination",
    "Type": "HTTP",
    "URL": "http://onprem-virtual:8080",
    "ProxyType": "OnPremise",
    "Authentication": "NoAuthentication",
    "CloudConnectorLocationId": "my-location-id"
}
```

### Step 6: Check Proxy Type Programmatically (if needed)

```java
import com.sap.cloud.sdk.cloudplatform.connectivity.ProxyType;

HttpDestination destination = DestinationAccessor.getDestination(name).asHttp();

// Check if on-premise
if (destination.getProxyType() == ProxyType.ON_PREMISE) {
    // On-premise handling (SDK handles proxy automatically)
    logger.info("Using on-premise connectivity");
}
```

## Configuration Files

No new configuration files in the application. Cloud Connector and destinations are configured externally.

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `destination` | lite | Destination configuration |
| `connectivity` | lite | On-premise connectivity proxy |

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Verify Cloud Connector Connection
In Cloud Connector Administration:
- Check connection status (should show "Connected")
- Verify virtual host mappings

### 3. Test On-Premise Connectivity
```bash
# Deploy application
cf deploy . -f

# Test on-premise destination
curl "https://${app-url}/connectivity?destname=on-premise-destination"
```

### 4. Check Logs for Connectivity
```bash
cf logs ${app-name} --recent | grep -i "connectivity\|proxy"
```

## Common Issues

### Issue: Connection refused
**Cause:** Cloud Connector not connected or misconfigured.
**Solution:**
1. Check Cloud Connector status
2. Verify virtual host mapping
3. Check Location ID in destination matches Cloud Connector

### Issue: 403 Forbidden on on-premise resource
**Cause:** Cloud Connector resource access policy.
**Solution:** Add the path to allowed resources in Cloud Connector.

### Issue: Timeout connecting to on-premise
**Cause:** Network issue or Cloud Connector firewall.
**Solution:**
1. Verify on-premise server is reachable from Cloud Connector host
2. Check firewall rules
3. Increase timeout in destination if needed

### Issue: "No connectivity service bound"
**Cause:** Connectivity service not bound to application.
**Solution:** Verify `${app-name}-connectivity` is in requires section of mtad.yaml.

## Architecture Overview

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  CF Application │────▶│ Connectivity     │────▶│ Cloud Connector │
│  (Your App)     │     │ Service Proxy    │     │ (On-Premise)    │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                                                          ▼
                                                 ┌─────────────────┐
                                                 │ On-Premise      │
                                                 │ Backend System  │
                                                 └─────────────────┘
```

The SAP Cloud SDK automatically:
1. Detects that the destination has `ProxyType=OnPremise`
2. Routes the request through the connectivity service proxy
3. Adds required headers (`SAP-Connectivity-ConsumerAccount`)
4. Cloud Connector forwards to the actual on-premise system

## Next Steps

After completing this skill, proceed to:
- [../mail-destinations/SKILL.md](../mail-destinations/SKILL.md) - For on-premise mail server configuration
