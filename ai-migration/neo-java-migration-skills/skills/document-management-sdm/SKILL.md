---
name: document-management-sdm
description: Invoke this skill to migrate from Neo ECM Service to SAP Document Management Service. Detects com.sap.ecm.api.EcmService resource-ref in web.xml or EcmService JNDI lookups. Uses OpenCMIS client library for CMIS protocol.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Document Management Service

Migrate from Neo's ECM Service to SAP Document Management Service (SDM).

## Purpose

Replace Neo's `com.sap.ecm.api.EcmService` JNDI resource with SAP Document Management Service using the CMIS protocol via OpenCMIS client library.

## Detection

This skill applies if any of these patterns are found:

### In web.xml
```xml
<resource-ref>
    <res-ref-name>EcmService</res-ref-name>
    <res-type>com.sap.ecm.api.EcmService</res-type>
</resource-ref>
```

### In Java source files
```java
import com.sap.ecm.api.EcmService;
import com.sap.ecm.api.RepositoryOptions;

@Resource(name = "EcmService")
private EcmService ecmService;

// OR JNDI lookup
EcmService ecmService = (EcmService) ctx.lookup("java:comp/env/EcmService");
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

Also required:
- SDM service entitlement in subaccount

## Transformation Steps

### Step 1: Remove Resource Reference from web.xml

**Remove this from web.xml:**
```xml
<resource-ref>
    <res-ref-name>EcmService</res-ref-name>
    <res-type>com.sap.ecm.api.EcmService</res-type>
</resource-ref>
```

### Step 2: Add OpenCMIS Dependencies

Add to `pom.xml`:

```xml
<dependencies>
    <!-- OpenCMIS Client -->
    <dependency>
        <groupId>org.apache.chemistry.opencmis</groupId>
        <artifactId>chemistry-opencmis-client-impl</artifactId>
        <version>1.1.0</version>
    </dependency>
    <dependency>
        <groupId>org.apache.chemistry.opencmis</groupId>
        <artifactId>chemistry-opencmis-client-api</artifactId>
        <version>1.1.0</version>
    </dependency>

    <!-- HTTP Client for REST calls -->
    <dependency>
        <groupId>org.apache.httpcomponents</groupId>
        <artifactId>httpclient</artifactId>
    </dependency>

    <!-- JSON processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

> **Important:** OpenCMIS libraries have NOT been migrated to Jakarta EE. They still use `javax.*` namespaces. This is acceptable as they are self-contained.

### Step 3: Create Service Binding Accessor

Copy [assets/ServiceBindingAccessor.java](assets/ServiceBindingAccessor.java) to your project:

Key features:
- Reads SDM service binding from VCAP_SERVICES
- Retrieves OAuth2 token using client credentials
- Caches token with automatic refresh

### Step 4: Create Document Service Client

Copy [assets/DocumentServiceClient.java](assets/DocumentServiceClient.java) to your project:

Key features:
- Creates CMIS sessions for repositories
- Repository management (create, list, check existence)
- OAuth2 authentication integration

### Step 5: Update Application Code

**Before (Neo):**
```java
import com.sap.ecm.api.EcmService;
import com.sap.ecm.api.RepositoryOptions;
import org.apache.chemistry.opencmis.client.api.Session;

@Resource(name = "EcmService")
private EcmService ecmService;

public void handleDocument() {
    // Create repository
    RepositoryOptions options = new RepositoryOptions();
    options.setUniqueName("my-repo");
    options.setRepositoryKey("secret-key");
    String repoId = ecmService.createRepository(options);

    // Get CMIS session
    Session session = ecmService.connect(repoId, "secret-key");

    // Work with documents...
}
```

**After (Cloud Foundry):**
```java
import com.example.document.DocumentServiceClient;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;

public class DocumentServlet extends HttpServlet {

    private final DocumentServiceClient documentClient = new DocumentServiceClient();

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String repoName = "my-repo";

        // Create repository if not exists
        if (!documentClient.repositoryExists(repoName)) {
            documentClient.createRepository(repoName);
        }

        // Get CMIS session
        Session session = documentClient.getSession(repoName);

        // Get root folder
        Folder rootFolder = session.getRootFolder();

        // List documents
        response.setContentType("application/json");
        response.getWriter().println("{\"documents\": [");

        boolean first = true;
        for (org.apache.chemistry.opencmis.client.api.CmisObject obj : rootFolder.getChildren()) {
            if (!first) response.getWriter().println(",");
            response.getWriter().printf("{\"name\": \"%s\", \"type\": \"%s\"}",
                obj.getName(), obj.getType().getId());
            first = false;
        }

        response.getWriter().println("]}");
    }

    // Upload document example
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        Session session = documentClient.getSession("my-repo");
        Folder rootFolder = session.getRootFolder();

        // Create document
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:objectTypeId", "cmis:document");
        properties.put("cmis:name", "example.txt");

        byte[] content = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ContentStream contentStream = session.getObjectFactory()
            .createContentStream("example.txt", content.length, "text/plain",
                new ByteArrayInputStream(content));

        Document doc = rootFolder.createDocument(properties, contentStream,
            VersioningState.MAJOR);

        response.getWriter().println("Created document: " + doc.getId());
    }
}
```

### Step 6: Update MTA Descriptor

Add SDM service to `mtad.yaml`:

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
      - name: ${app-name}-sdm

resources:
  - name: ${app-name}-sdm
    type: org.cloudfoundry.managed-service
    parameters:
      service: sdm
      service-plan: standard
```

## Configuration Files

No additional configuration files required. Service binding is read at runtime.

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `sdm` | standard | Document Management Service |

## Verification

### 1. Compile Check
```bash
mvn clean compile
```

### 2. Deploy and Test
```bash
mvn clean package
cf deploy . -f

# Test document operations
curl "https://${app-url}/documents"
```

### 3. Check Service Binding
```bash
cf env ${app-name} | grep -A 30 "sdm"
```

## Common Issues

### Issue: "SDM service not bound"
**Cause:** SDM service not bound to application.
**Solution:** Check mtad.yaml requires section and redeploy.

### Issue: OAuth token error
**Cause:** XSUAA credentials issue.
**Solution:** Verify SDM service binding includes UAA credentials.

### Issue: ECM service URL - ecmservice endpoint is a Map, not a String
**Cause:** On CF, the SDM binding's `ecmservice` endpoint may be returned as a nested Map `{"timeout": 900000, "url": "https://..."}` instead of a plain String.
**Solution:** `ServiceBindingAccessor.getEcmServiceUrl()` handles both formats with an `instanceof` check. The URL also has a trailing slash that must be stripped to avoid double-slash in URL construction.

### Issue: URL construction - missing slash between base URL and path
**Cause:** After stripping the trailing slash from the ECM URL, concatenating `"rest/v2/repositories/"` without a leading `/` produces a malformed URL like `https://api-sdm-di.cfapps.eu11.hana.ondemand.comrest/v2/repositories/`.
**Solution:** Always use `+ "/rest/v2/repositories/"` (with leading `/`) or use the `normalizeUrl()` helper.

### Issue: 406 "Repository Name is missing" when creating repository
**Cause:** SDM REST API v2 requires a nested JSON format: `{"repository": {"name": "...", "displayName": "...", ...}}`. A flat format like `{"repository": "name"}` is rejected.
**Solution:** The asset's `createRepository()` method uses the correct nested `ObjectNode` format.

### Issue: 412 "Please add entitlements for Document Management, repository option"
**Cause:** The BTP subaccount is missing the "Document Management, repository option" entitlement.
**Solution:** Add the entitlement in BTP Cockpit before creating internal repositories.

### Issue: Repository not found after creation
**Cause:** Eventual consistency delay.
**Solution:** Add a small delay or retry logic after creation.

### Issue: `getRepositoryId` fails with single repository
**Cause:** SDM REST API returns `repoAndConnectionInfos` as a single JSON object (not an array) when there is only one repository. Iterating over an ObjectNode yields field values, not repository entries.
**Solution:** The asset's `getRepositoryId()` checks `isArray()` vs `isObject()` and handles both cases.

### Issue: CMIS session fails with "Repository 'xxx' not found!"
**Cause:** The SDM REST API has two different ID fields: `id` (SDM internal ID) and `cmisRepositoryId` (root folder ID). Neither of these is the correct value for OpenCMIS `SessionParameter.REPOSITORY_ID`. The CMIS browser binding uses the **repository name** as the repositoryId.
**Solution:** `getRepositoryId()` returns the repository name, which is what the CMIS browser binding expects. This can be verified by querying `GET {ecmServiceUrl}/browser` which returns a map keyed by repository names.

## Next Steps

After completing this skill, proceed to other applicable skills:
- [../persistence-hana/SKILL.md](../persistence-hana/SKILL.md) - Database configuration
- [../keystore-credstore/SKILL.md](../keystore-credstore/SKILL.md) - Credential storage
