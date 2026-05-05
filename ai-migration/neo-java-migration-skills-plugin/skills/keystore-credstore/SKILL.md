---
name: keystore-credstore
description: Invoke this skill to migrate keystore and password storage to SAP Credential Store. Detects KeyStoreService or PasswordStorage resource-refs in web.xml. Replaces Neo keystore with Credential Store using mTLS authentication.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Keystore Service

Migrate keystore and password storage to SAP Credential Store.

## Purpose

Replace Neo's `KeyStoreService` and `PasswordStorage` JNDI resources with SAP Credential Store service using mTLS authentication and REST API.

## Detection

This skill applies if any of these patterns are found:

### In web.xml
```xml
<resource-ref>
    <res-ref-name>KeyStoreService</res-ref-name>
    <res-type>com.sap.cloud.crypto.keystore.api.KeyStoreService</res-type>
</resource-ref>

<!-- OR -->
<resource-ref>
    <res-ref-name>PasswordStorage</res-ref-name>
    <res-type>com.sap.cloud.security.password.PasswordStorage</res-type>
</resource-ref>
```

### In Java source files
```java
import com.sap.cloud.crypto.keystore.api.KeyStoreService;
import com.sap.cloud.security.password.PasswordStorage;

@Resource(name = "KeyStoreService")
private KeyStoreService keyStoreService;

@Resource(name = "PasswordStorage")
private PasswordStorage passwordStorage;
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

Also required:
- Credential Store entitlement in subaccount

## Transformation Steps

### Step 1: Remove Resource References from web.xml

**Remove these from web.xml:**
```xml
<resource-ref>
    <res-ref-name>KeyStoreService</res-ref-name>
    <res-type>com.sap.cloud.crypto.keystore.api.KeyStoreService</res-type>
</resource-ref>

<resource-ref>
    <res-ref-name>PasswordStorage</res-ref-name>
    <res-type>com.sap.cloud.security.password.PasswordStorage</res-type>
</resource-ref>
```

### Step 2: Copy Client Classes

Copy the following helper classes to your project from [assets/](assets/):

1. `ServiceCredentialsAccessor.java` - Reads Credential Store service binding
2. `SSLContextProvider.java` - Sets up mTLS authentication
3. `CredStoreClient.java` - Credential Store REST API client

These classes handle:
- Reading service credentials from VCAP_SERVICES
- Parsing PEM certificates and private keys
- Creating SSL context for mTLS
- REST API calls to Credential Store

### Step 3: Update Application Code

**Before (Neo - KeyStore):**
```java
import com.sap.cloud.crypto.keystore.api.KeyStoreService;
import java.security.KeyStore;

@Resource(name = "KeyStoreService")
private KeyStoreService keyStoreService;

public void useKeyStore() {
    KeyStore keyStore = keyStoreService.getKeyStore();
    PrivateKey key = (PrivateKey) keyStore.getKey("my-alias", password);
    Certificate cert = keyStore.getCertificate("my-alias");
}
```

**After (Cloud Foundry):**
```java
import com.example.credstore.client.CredStoreClient;

public class KeyStoreServlet extends HttpServlet {

    private static final String NAMESPACE = "my-app-namespace";
    private CredStoreClient credStoreClient;

    @Override
    public void init() throws ServletException {
        try {
            credStoreClient = new CredStoreClient();
        } catch (Exception e) {
            throw new ServletException("Failed to initialize CredStore client", e);
        }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String alias = request.getParameter("alias");

        try {
            // Retrieve key from Credential Store
            CredStoreClient.KeyCredential keyCredential =
                credStoreClient.getKey(alias, NAMESPACE);

            response.setContentType("application/json");
            response.getWriter().printf(
                "{\"name\": \"%s\", \"hasCertificate\": %b}",
                keyCredential.getName(),
                keyCredential.getCertificate() != null
            );

        } catch (Exception e) {
            response.sendError(500, "Failed to retrieve key: " + e.getMessage());
        }
    }
}
```

**Before (Neo - Password Storage):**
```java
import com.sap.cloud.security.password.PasswordStorage;

@Resource(name = "PasswordStorage")
private PasswordStorage passwordStorage;

public void usePassword() {
    char[] password = passwordStorage.getPassword("my-password-alias");
    // Use password...
    Arrays.fill(password, '0'); // Clear password
}
```

**After (Cloud Foundry):**
```java
import com.example.credstore.client.CredStoreClient;

public void usePassword() {
    CredStoreClient client = new CredStoreClient();
    String password = client.getPassword("my-password-alias", "my-namespace");
    // Use password...
    // Note: String passwords cannot be securely cleared in Java
}
```

### Step 4: Update MTA Descriptor

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
      - name: ${app-name}-credstore

resources:
  - name: ${app-name}-credstore
    type: org.cloudfoundry.managed-service
    parameters:
      service: credstore
      service-plan: standard
      config:
        authentication:
          type: mtls
```

### Step 5: Create Credentials in Credential Store

Using BTP Cockpit or Credential Store API:

#### Create Namespace
1. Open Credential Store dashboard
2. Create namespace: `my-app-namespace`

#### Add Password
1. Select namespace
2. Add Password:
   - **Name:** `database-password`
   - **Value:** `secret123`

#### Add Key
1. Select namespace
2. Add Key:
   - **Name:** `my-certificate`
   - **Certificate:** (paste PEM certificate)
   - **Private Key:** (paste PEM private key)

## Configuration Files

No new configuration files required. Credentials are accessed via service binding.

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `credstore` | standard | Secure credential storage |

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Verify Service Binding
```bash
cf env ${app-name} | grep -A 20 "credstore"
# Should show url, certificate, and key
```

### 3. Test Credential Access
```bash
curl "https://${app-url}/keystore?alias=my-certificate"
```

## Common Issues

### Issue: "Credential Store service not bound"
**Cause:** Service not bound to application.
**Solution:** Check mtad.yaml requires section.

### Issue: SSL handshake failure
**Cause:** mTLS certificate parsing issue.
**Solution:**
1. Verify service binding has certificate and key
2. Check PEM format is correct

### Issue: 401 Unauthorized
**Cause:** Invalid namespace or missing credential.
**Solution:**
1. Verify namespace exists in Credential Store
2. Check credential name matches exactly

### Issue: "Key not found"
**Cause:** Credential doesn't exist in namespace.
**Solution:** Create the credential in Credential Store dashboard.

## Security Best Practices

1. **Namespace Isolation:** Use separate namespaces for different environments
2. **Key Rotation:** Regularly rotate keys and certificates
3. **Least Privilege:** Only request credentials that are needed
4. **Audit Logging:** Enable audit logging in Credential Store
5. **Memory Clearing:** Clear sensitive data from memory when done

## Next Steps

After completing this skill, proceed to other applicable skills:
- [../monitoring-logging/SKILL.md](../monitoring-logging/SKILL.md) - Monitor credential access
