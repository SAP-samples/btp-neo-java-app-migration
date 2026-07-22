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

> **Prerequisite — `sdk-modules-bom` and `scp-cf` must already be present from `sdk-replacement`.** The `ServiceBindingAccessor` asset (Step 3) calls `DefaultServiceBindingAccessor.getInstance()` from `com.sap.cloud.environment.servicebinding.api.*`, which is supplied by `scp-cf` and version-managed by `sdk-modules-bom` at `provided` scope (the buildpack ships its own — newer — implementation at runtime). Both are added by the `sdk-replacement` prerequisite skill; this skill does **not** add them. If they are missing, rerun `sdk-replacement` rather than patching them in by hand.
>
> **Do NOT** add `com.sap.cloud.environment.servicebinding:*` as a direct `compile`-scope dependency. Bundling a different version of those classes inside the WAR causes a runtime `ServiceConfigurationError: SapVcapServicesServiceBindingAccessor not a subtype` because the buildpack-provided interface and the WAR-bundled implementation are loaded by different classloaders.

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

> **What about `scp-cf`?** The `ServiceBindingAccessor` asset depends on
> `com.sap.cloud.sdk.cloudplatform:scp-cf` (which transitively brings the
> `com.sap.cloud.environment.servicebinding.api.*` classes used by
> `DefaultServiceBindingAccessor.getInstance()`). That dependency — along with
> `sdk-modules-bom` — is the declared responsibility of the **`sdk-replacement`**
> skill, which is a prerequisite of this one. Do **not** add `scp-cf` again
> here. If your `pom.xml` is missing it, that means the `sdk-replacement` step
> didn't complete; rerun it instead of patching the dependency in by hand.

> **Important:** OpenCMIS libraries have NOT been migrated to Jakarta EE. They still use `javax.*` namespaces. This is acceptable as they are self-contained.

### Step 3: Create Service Binding Accessor

Copy [assets/ServiceBindingAccessor.java](assets/ServiceBindingAccessor.java) to your project:

Key features:
- Reads SDM service binding from VCAP_SERVICES
- Retrieves OAuth2 token using client credentials
- Caches token with automatic refresh

### Step 4: Create Document Service Client

Copy these two files to your project (both declare `package com.example.document;`):

- [assets/DocumentServiceClient.java](assets/DocumentServiceClient.java) — main client
- [assets/RepositoryAlreadyExistsException.java](assets/RepositoryAlreadyExistsException.java) — checked exception thrown when `createRepository` is called for an existing repo

Key features:
- Creates CMIS sessions for repositories
- Repository management — create, delete, list, check existence
- OAuth2 authentication integration
- Pre-checks existence before POSTing a create, because SDM is idempotent at the HTTP layer (see Common Issues below) — the alternative is silently double-creating, which the Neo `EcmService` never did.

> **Identifier rule — the two endpoints take DIFFERENT identifiers, do not collapse them:**
>
> - **CMIS browser binding** (`SessionParameter.REPOSITORY_ID`) uses the repository **name**.
>   Verify by hitting `GET {ecmServiceUrl}/browser` — the response is a map keyed by names.
>   The REST GET response also has a `cmisRepositoryId` field, but that is the *root folder
>   ID*, not the CMIS repository id; using it produces `Repository 'xxx' not found!` from
>   OpenCMIS.
>
> - **REST DELETE** (`DELETE /rest/v2/repositories/{X}`) uses the SDM internal **UUID** —
>   the `id` field returned by `GET /rest/v2/repositories/`, e.g. `f3023e03-81da-4be4-...`.
>   Passing the name produces HTTP 500 with body
>   `{"message":"Repository with id:<name> is invalid. Please enter a valid repository ID."}`.
>
> The asset has **two** lookup methods to keep this distinction explicit:
> `getRepositoryId(name)` → returns the name (for CMIS) and `getRepositoryUuid(name)` →
> returns the UUID (for DELETE). Do not "simplify" them into one.

If the Neo source called `ecmService.deleteRepository(name, ...)` or
`ecmService.forceDeleteRepository(name, ...)`, route it to
`documentClient.deleteRepository(name)`. The Neo `EcmService` distinguishes empty vs.
non-empty deletion; SDM's REST API does not — use one method on the migrated side and let
SDM return the appropriate error if the repo is non-empty.

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

> **Note on the package**: the assets declare `package com.example.document;` as a placeholder.
> Rename to your project's package (e.g. `com.acme.docs`) when you copy them in, and update the
> imports below to match.
>
> **Note on servlet imports**: this is the Jakarta migration target — use `jakarta.servlet.*`,
> not `javax.servlet.*`. The Neo originals will be `javax.*`; the `jakarta-java25-migration`
> skill should have already rewritten them.

```java
import com.example.document.DocumentServiceClient;
import com.example.document.RepositoryAlreadyExistsException;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class DocumentServlet extends HttpServlet {

    private final DocumentServiceClient documentClient = new DocumentServiceClient();

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String repoName = "my-repo";

        // Create repository if not exists. createRepository will also throw
        // RepositoryAlreadyExistsException on its own pre-check; the explicit
        // repositoryExists() guard here just avoids the throw on the happy path.
        if (!documentClient.repositoryExists(repoName)) {
            try {
                documentClient.createRepository(repoName);
            } catch (RepositoryAlreadyExistsException e) {
                // Race: someone else created it between our check and call.
                // For the typical "ensure exists" flow this is fine — proceed.
            }
        }

        // Get CMIS session
        Session session = documentClient.getSession(repoName);

        // Get root folder
        Folder rootFolder = session.getRootFolder();

        // List documents
        response.setContentType("application/json");
        response.getWriter().println("{\"documents\": [");

        boolean first = true;
        for (CmisObject obj : rootFolder.getChildren()) {
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

    // Delete repository example — replaces ecmService.deleteRepository(name, ...)
    // and ecmService.forceDeleteRepository(name, ...) from the Neo API.
    // The asset's deleteRepository() resolves the SDM internal UUID for you;
    // you pass the NAME at the API boundary.
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String repoName = request.getParameter("uniqueName");
        try {
            documentClient.deleteRepository(repoName);
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (CmisObjectNotFoundException e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        }
    }

    // Create-repository handler — mirrors what Neo's ecmService.createRepository did,
    // including the "already exists" branch. The Neo EcmService threw on duplicate;
    // SDM does NOT (POST /rest/v2/repositories returns 201 even on duplicate). The
    // asset closes that gap by pre-checking and throwing RepositoryAlreadyExistsException,
    // which we map here to HTTP 412 — matching the contract of the original Neo app and
    // any integration test that expected PRECONDITION_FAILED on duplicate creation.
    protected void doPostCreateRepository(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String repoName = request.getParameter("uniqueName");
        try {
            documentClient.createRepository(repoName);
            response.setStatus(HttpServletResponse.SC_CREATED);
        } catch (RepositoryAlreadyExistsException e) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, e.getMessage());
        }
    }
}
```

### Step 6: Update MTA Descriptor

Bind the SDM service to your application in `mtad.yaml`. **Only add what this skill owns** —
the application module's `parameters:`/`properties:` block is set up by the
`tomee-runtime` / `jakarta-java25-migration` skills; do not overwrite it here.

Add the `requires:` entry to your existing app module:

```yaml
modules:
  - name: ${app-name}
    # ... existing parameters/properties ...
    requires:
      - name: ${app-name}-sdm
```

And add the resource:

```yaml
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

### Issue: `ServiceConfigurationError: SapVcapServicesServiceBindingAccessor not a subtype` at runtime
**Cause:** The WAR ships its own copy of `com.sap.cloud.environment.servicebinding.*` (typically because a direct compile-scope dependency on `java-sap-service-operator`, `java-sap-vcap-services`, or the `java-modules-bom` was added). The buildpack already provides those classes, so two versions end up in different classloaders and `ServiceLoader` rejects the in-WAR provider as "not a subtype" of the buildpack-provided interface. The error first surfaces as HTTP 500 on every SDM call, with the stacktrace bottoming out in `DefaultServiceBindingAccessor.<clinit>`.
**Solution:** Remove any direct dependency on `com.sap.cloud.environment.servicebinding:*` from `pom.xml`. Rely on the `sdk-modules-bom` import (added by `sdk-replacement`) which manages those artifacts at `provided` scope. After fixing the pom, run `mvn dependency:tree -Dincludes=com.sap.cloud.environment.servicebinding` and confirm every line ends in `:provided`.

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

### Issue: Integration test expects HTTP 412 on duplicate create, got 201
**Cause:** The Neo `EcmService.createRepository(...)` threw on duplicate, and the migrated REST layer in many apps maps that to HTTP 412 Precondition Failed. SDM's REST API does NOT preserve that behaviour — `POST /rest/v2/repositories/` returns **201 Created** whether or not a repository with the same name exists. So a naive port of `createRepository` that just forwards SDM's status produces 201 in both cases, and tests like `whenCreate_givenExistingRepoName_thenReturnErrorResponse` start failing with `expected: <412> but was: <201>`.
**Solution:** Pre-check existence with `repositoryExists(name)` before POSTing, and throw a checked exception (the asset uses `RepositoryAlreadyExistsException`) when it returns true. Map that exception to HTTP 412 in the REST/servlet layer. The asset's `createRepository(...)` already does the pre-check and the throw; the migrated `DocumentServiceRest` just needs the matching `catch` branch — see the Step 5 example.

### Issue: 406 "Repository Name is missing" when creating repository
**Cause:** SDM REST API v2 requires a nested JSON format: `{"repository": {"name": "...", "displayName": "...", ...}}`. A flat format like `{"repository": "name"}` is rejected.
**Solution:** The asset's `createRepository()` method uses the correct nested `ObjectNode` format.

### Issue: POST returns HTTP 500 with body `Repository created but response missing repository name: { ... "name": "...", "id": "...", ... }`
**Cause:** The request and response shapes for `POST /rest/v2/repositories/` are NOT symmetric. The **request** body wraps everything in `{"repository": {...}}`, but the **response** is a FLAT JSON document — `name`, `id`, `cmisRepositoryId`, `repositoryType` sit at the top level, with no `"repository"` envelope. Code that mirrors the request shape and reads `created.path("repository").path("name")` always sees null and throws — even though SDM did create the repository. The error then cascades: the next test sees the repo exists on the server but the in-process state thinks it doesn't, so cleanup-on-teardown calls DELETE → 404, and every test that depends on a clean slate fails.
**Solution:** Read fields directly off the top of the response (`created.path("name")`, `created.path("id")`). The asset does this correctly; do not "fix" it to walk through a `repository` envelope.

> **Asymmetry rule:** SDM's repository endpoints have an asymmetric envelope. POST request: wrapped (`{"repository": {...}}`). POST response: flat. GET response: wrapped, inside `repoAndConnectionInfos` (and that field is a single object when there's only one repo, an array otherwise). When in doubt, log the raw response body before parsing.

### Issue: 412 "Please add entitlements for Document Management, repository option"
**Cause:** The BTP subaccount is missing the "Document Management, repository option" entitlement.
**Solution:** Add the entitlement in BTP Cockpit before creating internal repositories.

### Issue: Repository not found after creation
**Cause:** Eventual consistency delay.
**Solution:** Add a small delay or retry logic after creation.

### Issue: Repository lookup fails when only one repository exists
**Cause:** SDM returns `repoAndConnectionInfos` as a single JSON object (not an array) when there is only one repository, and as an array otherwise. Code that does `for (JsonNode r : repoAndConn)` over the ObjectNode iterates over its field *values* (the inner `repository` and `connection` nodes), not over repository entries — silently missing the only entry.
**Solution:** The asset uses `JsonNode.findValues("repository")`, which recursively walks the tree and yields every `repository` node regardless of whether the parent is an array or a single object. `repositoryExists` similarly uses `findValuesAsText("name")`. This is also why the asset has no `if (repoAndConn.isArray())` branching — `findValues` handles both shapes uniformly.

### Issue: CMIS session fails with "Repository 'xxx' not found!"
**Cause:** The CMIS `SessionParameter.REPOSITORY_ID` was set to either `id` (SDM internal UUID) or `cmisRepositoryId` (root folder ID) from the REST response. Neither is what the CMIS browser binding wants — it keys on the repository **name**.
**Solution:** Use the asset's `getRepositoryId(name)`, which returns the name back to you. Verify the contract independently with `GET {ecmServiceUrl}/browser` — the response is a JSON map keyed by repository names. (For the REST DELETE endpoint, the rule is opposite — use `getRepositoryUuid(name)` instead.)

### Issue: DELETE returns HTTP 500 with body `Repository with id:<name> is invalid. Please enter a valid repository ID.`
**Cause:** `DELETE /rest/v2/repositories/{X}` was called with the repository **name** in the path. SDM's DELETE endpoint expects the SDM internal **UUID** there (the `id` field from `GET /rest/v2/repositories/`, e.g. `f3023e03-81da-4be4-...`). Note the wording in SDM's error: it says "with id:<name>" — i.e. SDM is reading what you passed *as* an id, sees that it's not a UUID it knows, and rejects it. This is the OPPOSITE of the CMIS session rule above (which uses the name); the two endpoints take different identifiers.
**Solution:** Look up the UUID first, then DELETE. The asset does this with `getRepositoryUuid(name)` → `DELETE /rest/v2/repositories/{uuid}`. If a single shared `getRepositoryId` helper exists and returns the name, do not reuse it for DELETE — call `getRepositoryUuid` (or fetch `repository.path("id")` directly) instead. The cascade is recognizable: after the first DELETE 500, the test's per-method cleanup keeps DELETEing and seeing 500 (or 404 if the first one happened to succeed server-side), and `whenConnect_givenNotExistingRepoName` etc. all fail because the test environment is no longer in a clean state.

## Next Steps

After completing this skill, proceed to other applicable skills:
- [../persistence-hana/SKILL.md](../persistence-hana/SKILL.md) - Database configuration
- [../keystore-credstore/SKILL.md](../keystore-credstore/SKILL.md) - Credential storage
