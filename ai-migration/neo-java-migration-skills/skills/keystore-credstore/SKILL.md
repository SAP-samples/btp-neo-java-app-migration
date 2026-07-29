---
name: keystore-credstore
description: Use this skill whenever migrating an SAP BTP Neo Java app that uses keystores, password storage, or any JNDI-bound credential lookup to Cloud Foundry. Triggers on `com.sap.cloud.crypto.keystore.api.KeyStoreService`, `com.sap.cloud.security.password.PasswordStorage`, `<resource-ref>` blocks for `KeyStoreService` or `PasswordStorage` in `web.xml`, `InitialContext.lookup("java:comp/env/KeyStoreService"|"PasswordStorage")`, or `@Resource(name = "KeyStoreService"|"PasswordStorage")`. Even if the user just says "migrate this app to CF" without naming credentials explicitly, invoke this skill the moment any of those symbols appear in the source. Replaces JNDI lookups with the SAP Credential Store REST API over mTLS, ships an offline-friendly Lombok-free Java client, and emits a Step 4 mtad that BINDS to a pre-existing, manually-provisioned credstore instance via `org.cloudfoundry.existing-service` (do NOT create or delete the credstore from the MTA).
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

Copy the following helper classes to your project from [assets/](assets/). All assets declare
`package com.example.document;`, so place them flat under `src/main/java/com/example/document/`:

> **Note on the package**: `com.example.document` is a placeholder. Rename to your project's package
> (e.g. `com.acme.security`) when you copy the assets in, and update the imports below to match.

| File |
|------|
| `ServiceCredentials.java` |
| `ServiceCredentialsAccessor.java` |
| `CertificateParser.java` |
| `KeyParser.java` |
| `SSLContextProvider.java` |
| `CredStoreResponse.java` |
| `CredStoreRequestBuilder.java` |
| `CredStoreClient.java` |

These classes handle:
- Reading service credentials from VCAP_SERVICES via `DefaultServiceBindingAccessor`
- Parsing PEM certificates (X.509) and PKCS#1 private keys (BouncyCastle PEMParser)
- Creating an `SSLContext` for mTLS (PKCS12 keystore in memory)
- REST API calls to Credential Store using `java.net.http.HttpClient`

The classes have no Lombok dependency — getters are written by hand so they build cleanly under JDK 25.

#### Required Maven dependencies

Add these to your application's `pom.xml`. The `cf-tomcat-bom` + `sdk-modules-bom` BOMs already
manage versions for `scp-cf` (which transitively brings in `service-binding-api`, BouncyCastle,
Apache HttpClient, Jackson, and SLF4J), so you only need the `scp-cf` dependency itself plus
`jakarta.servlet-api` for the servlet:

```xml
<properties>
    <!-- sap_java_buildpack_jakarta supports SapMachine 17, 21, and 25.
         Always pin the runtime JRE explicitly via JBP_CONFIG_SAP_MACHINE_JRE in
         mtad.yaml / manifest.yml, and make sure this compile target matches that
         major version — a higher class file version yields
         java.lang.UnsupportedClassVersionError at servlet load time (HTTP 500). -->
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>

    <!-- Resolve via sdk-replacement Step 3. -->
    <cf-tomcat-bom-version>RESOLVED_CF_TOMCAT_BOM_VERSION</cf-tomcat-bom-version>
    <sdk-modules-bom-version>RESOLVED_SDK_MODULES_BOM_VERSION</sdk-modules-bom-version>
    <jakarta.servlet-api.version>6.1.0</jakarta.servlet-api.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.sap.cloud.sjb.cf</groupId>
            <artifactId>cf-tomcat-bom</artifactId>
            <version>${cf-tomcat-bom-version}</version>
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

<dependencies>
    <dependency>
        <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
        <artifactId>scp-cf</artifactId>
    </dependency>
    <dependency>
        <groupId>jakarta.servlet</groupId>
        <artifactId>jakarta.servlet-api</artifactId>
        <version>${jakarta.servlet-api.version}</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

> **Pin the runtime JRE explicitly.** `sap_java_buildpack_jakarta` supports SapMachine
> 17, 21, and 25. The buildpack's implicit JRE choice changes over time, so don't rely on
> it — always pin the version in `mtad.yaml` (or `manifest.yml`) and keep the compile
> target in `pom.xml` aligned with it. A class file newer than the runtime JRE causes
> Tomcat to fail loading the servlet with
> `java.lang.UnsupportedClassVersionError: ... class file version N.0, this version of the
> Java Runtime only recognizes class file versions up to M.0` and every request returns
> HTTP 500.
>
> For Java 25 (the recommended target for new migrations) add this to your module
> `properties:` block in `mtad.yaml`:
>
> ```yaml
> JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
> JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }'
> ```
>
> Same form for any other supported major version — substitute `17.+` or `21.+`.
> Build locally with a matching JDK (e.g. SapMachine 25 from https://sapmachine.io/).
> The Lombok-free helper classes in `assets/` compile cleanly on any supported version.

### Step 3: Update Application Code

> **The HTTP URL contract changes — do NOT preserve the old Neo query parameters.**
>
> The Neo `KeyStoreServlet` was driven by params shaped around its JNDI API:
> `?method=getKeyStore&keyStoreName=<file>&password=<password>`. None of those map
> onto SAP Credential Store, which is namespace-scoped and addresses each credential
> by alias. The migrated servlet **MUST** accept the new contract:
>
> | URL | Behaviour |
> |-----|-----------|
> | `GET /keystore?namespace=<ns>` | List all credentials in `<ns>` (calls `CredStoreClient.retrieveCredentials(ns)`) |
> | `GET /keystore?namespace=<ns>&alias=<name>` | Retrieve a single credential by alias (calls `CredStoreClient.retrieveCredential(alias, ns)`) |
> | `GET /keystore` (no `namespace`) | Reject with `400 Bad Request`, body `Namespace is required!` |
>
> A Neo password-storage app likewise drops to: `GET /?namespace=<ns>&alias=<name>`
> calling `CredStoreClient.retrievePassword(alias, ns)`.
>
> Why this matters: do **NOT** carry over the `method=getKeyStore` /
> `keyStoreName=` / `password=` validation block from the original Neo servlet.
> Doing so makes the migrated app keep returning `400` for the actual contract a
> credstore-aware caller will use, and silently breaks integration tests. Replace
> the old guard with the namespace check shown below.

**Before (Neo `KeyStoreServlet` — JNDI lookup, `method=…&keyStoreName=…&password=…`):**
```java
import com.sap.cloud.crypto.keystore.api.KeyStoreService;
import java.security.KeyStore;
import javax.naming.InitialContext;
import javax.servlet.http.*;

public class KeyStoreServlet extends HttpServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String method = request.getParameter("method");
        if (method == null || !"getKeyStore".equals(method)) {
            // Neo-shaped guard. DELETE THIS BLOCK in the migrated version —
            // the new contract does not use a "method" parameter.
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "method invalid");
            return;
        }
        KeyStoreService svc = (KeyStoreService) new InitialContext().lookup("java:comp/env/KeyStoreService");
        KeyStore keyStore = svc.getKeyStore(
            request.getParameter("keyStoreName"),
            request.getParameter("password").toCharArray());
        // ... iterate keyStore.aliases() ...
    }
}
```

**After (Cloud Foundry — `?namespace=<ns>[&alias=<name>]`):**
```java
package com.example.document;

// CredStoreClient and CredStoreResponse live in the same package — no import needed.
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;

public class KeyStoreServlet extends HttpServlet {

    private static final String PARAM_ALIAS = "alias";
    private static final String PARAM_NAMESPACE = "namespace";

    private final CredStoreClient credStoreClient;

    public KeyStoreServlet() throws GeneralSecurityException, IOException {
        this.credStoreClient = new CredStoreClient();
    }

    @Override
    public void destroy() {
        // Release the mTLS private key when the servlet is taken out of service.
        // CredStoreClient holds the key for its whole lifetime (the SSLContext
        // reuses it across requests), so it must only be destroyed here — never
        // per request — otherwise subsequent mTLS handshakes fail.
        credStoreClient.close();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String alias = request.getParameter(PARAM_ALIAS);
        String namespace = request.getParameter(PARAM_NAMESPACE);

        if (isBlank(namespace)) {
            sendError(response, "Namespace is required!", HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        CredStoreResponse credstoreResponse = isBlank(alias)
                ? credStoreClient.retrieveCredentials(namespace)               // list all keys
                : credStoreClient.retrieveCredential(alias, namespace);        // single key

        if (credstoreResponse.isSuccess()) {
            sendOk(response, isBlank(alias)
                    ? "Successfully retrieved credentials."
                    : "Successfully retrieved credential.");
        } else {
            sendError(response, credstoreResponse.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static void sendOk(HttpServletResponse response, String body) throws IOException {
        try (PrintWriter out = response.getWriter()) { out.println(body); }
    }

    private static void sendError(HttpServletResponse response, String body, int status) throws IOException {
        response.setStatus(status);
        try (PrintWriter out = response.getWriter()) { out.println(body); }
    }
}
```

The corresponding `web.xml` must drop the `<resource-ref>` (Step 1) AND map the
servlet at `/keystore` (or `/` for password apps) so the URL above resolves. Any
Neo `ErrorUtil` helper that imported `javax.servlet.*` must be ported to
`jakarta.servlet.*` or inlined as private helpers like above.

**Before (Neo Password Storage — JNDI lookup, no namespace param):**
```java
import com.sap.cloud.security.password.PasswordStorage;

@Resource(name = "PasswordStorage")
private PasswordStorage passwordStorage;

public void usePassword(HttpServletRequest request) throws Exception {
    String alias = request.getParameter("alias");                     // alias only
    char[] password = passwordStorage.getPassword(alias);
    // ... use password ...
}
```

**After (Cloud Foundry — `?namespace=<ns>&alias=<name>`):**
```java
@Override
protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String alias = request.getParameter("alias");
    String namespace = request.getParameter("namespace");      // NEW required param

    if (isBlank(alias) || isBlank(namespace)) {
        response.getWriter().println("Alias and namespace must be provided as query parameters.");
        return;
    }

    CredStoreResponse r = credStoreClient.retrievePassword(alias, namespace);
    response.getWriter().println(r.isSuccess()
            ? "Password retrieved successfully."
            : "Failed to retrieve password.");
}
```

### Step 4: Update MTA Descriptor

> 🛑 **HARD RULE — the credstore resource MUST be `org.cloudfoundry.existing-service`.**
> Do **not** emit `type: org.cloudfoundry.managed-service` for credstore, and do
> **not** add a `parameters.service: credstore` / `service-plan:` block. Getting
> this wrong is the single most common failure of this migration:
>
> | If you emit… | What happens at `cf deploy` |
> |---|---|
> | `managed-service` (`service: credstore`) | Broker rejects it — `Service broker credstore failed with: Illegal parameters or arguments used` — because the mTLS `config` is set out-of-band, not in the MTA. Deploy fails. |
> | `existing-service` ✅ | Binds to the operator-provisioned instance. Correct. |
>
> The credstore instance is created and mTLS-configured **outside** the MTA
> (by the space operator, or by CI before deploy). The MTA only *binds* to it.

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
      # Pin SapMachine JRE 25 explicitly. The WAR is compiled to Java 25 (class file
      # version 69); without these env vars the buildpack runs whichever JRE is the
      # implicit default at deploy time (currently 21, class file 65), and Tomcat
      # rejects the servlet with java.lang.UnsupportedClassVersionError. The compile
      # target in pom.xml MUST equal the major version pinned here. Both env vars
      # are required — the first activates SapMachineJRE, the second selects 25.
      JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
      JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }'
    requires:
      - name: credstore-service

resources:
  # The credstore service instance and its stored credentials are MANUALLY
  # provisioned and shared across applications in the space — do NOT declare
  # this as a managed-service. SAP BTP caps credstore standard at 1 instance
  # per space, and any namespaces/aliases (the actual secret material) are
  # operator-owned. An MTA that creates the credstore would be deleted by
  # `cf undeploy --delete-services`, taking every other app's credentials
  # with it.
  #
  # Bind to it with `org.cloudfoundry.existing-service`. The resource `name:`
  # below MUST match the actual service-instance name in the target space —
  # by convention `credstore-service`, but verify with `cf services | grep
  # credstore` if you're unsure.
  - name: credstore-service
    type: org.cloudfoundry.existing-service
```

> **What this skill does NOT do:** create the credstore instance, define its
> mTLS configuration, or seed namespaces/aliases. Those are one-time
> per-space setup steps performed by the space operator.
>
> **Before provisioning, ask the user:**
> > "Is this a development/testing environment or a production environment?
> > - For **development/testing**: use the `free` plan (10 credentials, 0.1 MB — sufficient for testing)
> > - For **production**: use the `standard` plan (100,000 credentials, 100 MB)"
>
> Then provision accordingly:
>
> ```bash
> # Development/testing:
> cf create-service credstore free credstore-service -c '{"authentication":{"type":"mtls"}}'
>
> # Production:
> cf create-service credstore standard credstore-service -c '{"authentication":{"type":"mtls"}}'
> ```
>
> The migrated app only consumes credentials that are already there.
>
> **Plan guidance:** The `free` plan requires the Credential Store entitlement to be configured in the subaccount — add it via BTP Cockpit → Global Account → Entitlements → Add Service Plans → Credential Store → free.

### Step 5: Create Credentials in Credential Store

Using BTP Cockpit (Service Marketplace → Credential Store → Manage Instance) or
the [Credential Store API](https://api.sap.com/package/CredentialStore/rest):

1. **Create the namespace.** Pick a name that matches what your servlet expects.
2. **Add credentials inside that namespace** — each addressed by an alias. Add a
   `Password` (name + value) for password-storage migrations, or a `Key` (name +
   PEM certificate + PEM private key) for keystore-API migrations.

> **For the integration tests in this repo to pass**, use the fixtures the tests
> expect:
>
> | Migration scenario | Namespace | Alias |
> |---|---|---|
> | `storing-passwords` | `pass-storage-app` | password named `test` (any value) |
> | `keystore-api`      | `keystore-app`     | key named `keystore-app-key` (any cert/key pair) |
>
> The validate-migration prompt and `KeystoreIntegrationTest` /
> `PassStoreIntegrationTest` both hit these names verbatim. If your namespace or
> alias differs, the deployed app will return `404 credential_not_found` from the
> credstore broker (servlet status 200 with body `Failed to retrieve credential`)
> and the tests will fail — even though the migration itself is correct.

## Configuration Files

No new configuration files required. Credentials are accessed via service binding.

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `credstore` | `free` | Secure credential storage (development and testing — 10 credentials, 0.1 MB) |
| `credstore` | `standard` | Secure credential storage (production — 100,000 credentials, 100 MB) |

## Verification

### 1. Compile Check
```bash
mvn clean install
```

### 2. Verify Service Binding
```bash
cf env ${app-name} | grep -A 20 "credstore"
# Should show url, certificate, and key under VCAP_SERVICES.credstore[0].credentials
```

### 3. Verify the Container Picked Up JRE 25
```bash
cf ssh ${app-name} -c 'cat app/META-INF/.sap_java_buildpack/sap_machine_jre/release | head -1'
# Expected: JAVA_VERSION="25.0.x"
# If you see 21.x or 17.x, the JBP_CONFIG_SAP_MACHINE_JRE env var from Step 4
# wasn't set or wasn't picked up — the servlet will fail to load with
# UnsupportedClassVersionError on the first request.
```

### 4. Test Credential Access
The migrated servlet uses the new namespace-scoped contract from Step 3.
The URL must include `?namespace=…` — without it the servlet correctly returns 400.

```bash
# List all keys in a namespace (alias omitted)
curl -i "https://${app-url}/keystore?namespace=my-app-namespace"
#   → HTTP 200, body: "Successfully retrieved credentials."

# Retrieve a specific key by alias
curl -i "https://${app-url}/keystore?namespace=my-app-namespace&alias=my-certificate"
#   → HTTP 200, body: "Successfully retrieved credential."

# Missing namespace — sanity-check the validation
curl -i "https://${app-url}/keystore"
#   → HTTP 400, body: "Namespace is required!"
```

For password-storage apps: the URL is `?namespace=<ns>&alias=<password-name>`
returning `Password retrieved successfully.` on 200.

## Common Issues

These are real failure modes we have hit on this codebase. The cures are listed
in roughly the order you would try them.

### Common runtime errors → root cause

| Runtime error | Root cause | Fix |
|---|---|---|
| `NoClassDefFoundError: com/sap/cloud/security/...` | `java-api` or security library not packaged in WAR | Remove `<scope>provided</scope>` from the security dep in `pom.xml` |
| `NullPointerException` in `CredStoreClient` | `VCAP_SERVICES` env var not set — app not bound to credstore service | Check `cf env <app>` — bind the credstore service instance |
| `401 Unauthorized` from credstore REST API | mTLS certificate not passed correctly — using plain HTTP client instead of mTLS | Ensure the client uses the certificate and key from `VCAP_SERVICES.credstore[0].credentials` |
| `404 Not Found` from credstore REST API | Namespace or alias does not exist | Create namespace/alias via BTP Cockpit or credstore REST API before the app tries to read it |

### `java.lang.UnsupportedClassVersionError: ... class file version 69.0, this version of the Java Runtime only recognizes class file versions up to 65.0`

**Symptom:** App deploys and starts, but every request hits HTTP 500 (first request)
followed by 404 ("servlet marked unavailable"). The stack trace appears in
`cf logs <app-name> --recent`.

**Cause:** The WAR is compiled to Java 25 (class file version 69) but the deployed
container runs SapMachine 21 (class file 65). The buildpack's implicit JRE choice
does not match `<maven.compiler.target>`.

**Fix:** Add the two `JBP_CONFIG_*` env vars from Step 4 to the module's
`properties:` block in `mtad.yaml`. The compile target in `pom.xml` MUST equal the
major version in `JBP_CONFIG_SAP_MACHINE_JRE`. Re-deploy.

Confirm at runtime with `cf ssh <app-name> -c 'cat app/META-INF/.sap_java_buildpack/sap_machine_jre/release | head -1'` — it should say `JAVA_VERSION="25.0.x"`.

### `Service broker error ... Quota is not sufficient for this request, up to 1 standard instance/s for space`

**Symptom:** Deploy fails during `Processing service "credstore-service"...`
with the quota broker error above.

**Cause:** The mtad declares the credstore as `org.cloudfoundry.managed-service`,
which asks the broker to create a fresh instance. SAP BTP caps credstore
standard at 1 instance per space, so the broker rejects the create.

**Fix:** Change the resource type to `org.cloudfoundry.existing-service` and
remove the `parameters:` block — the credstore is operator-managed and shared,
not owned by this MTA. The result should look exactly like:

```yaml
  - name: credstore-service
    type: org.cloudfoundry.existing-service
```

See Step 4 for the full mtad context. If `credstore-service` doesn't exist in
the space at all, ask the user whether this is a development/testing or production
environment, then provision accordingly:

```bash
# Development/testing:
cf create-service credstore free credstore-service -c '{"authentication":{"type":"mtls"}}'

# Production:
cf create-service credstore standard credstore-service -c '{"authentication":{"type":"mtls"}}'
```

This skill does NOT create credstore instances or credentials.

### `Controller operation failed: 404 Not Found: Service instance credstore-service not found`

**Symptom:** Deploy fails almost immediately with the 404 above (or a similar
404 naming whatever value is in the resource's `name:`), repeated 4× then
`Process failed`.

**Cause:** Mtad declares `org.cloudfoundry.existing-service` (correctly), but no
instance with that exact `name:` exists in the target space. Either the
operator hasn't provisioned the shared credstore yet, or the name in `mtad.yaml`
doesn't match what's actually in the space.

**Fix:** Run `cf services | grep credstore` in the target space. If you see an
instance under a different name, update the resource's `name:` (and every
matching `requires.name:` in the module) to that exact string. If you see no
credstore at all, that's an operator setup gap — ask whoever owns the space to
provision it as described in Step 5 of this skill. The migration does NOT
auto-create credstore instances; the credentials inside are operator-seeded.

### `Error collecting system parameters: A higher version of your MTA is already deployed`

**Symptom:** Deploy fails immediately after `Detected new MTA version`.

**Cause:** Someone deployed the same MTA ID at a higher `version:` previously
(often during local debugging). The MTA deployer rejects downgrades by default.

**Fix (preferred):** Bump the `version:` in `mtad.yaml` above whatever's currently
deployed (e.g. `0.0.1` → `1.0.0`).

**Alternative:** Pass `--version-rule ALL` to `cf deploy`, but that's a one-off
escape hatch — fix the version field for permanence.

### `Error detaching services from MTA ... CF-ServiceInstanceNotFound: <name>-credstore` (4 retries, then `Process failed`)

**Symptom:** App and bind both succeed; the deploy script then tries to detach an
old service from the previous MTA manifest, retries 4×, and exits 1.

**Cause:** A prior version of the MTA listed a service the current `mtad.yaml` no
longer references (e.g. `pass-store-credstore`, `keystore-credstore`). The MTA's
persisted manifest still remembers it and tries to "detach" it on every deploy.
The orphan service is in `create failed` state, so the cleanup hangs.

**Fix:** Detach the orphan service from the MTA manifest WITHOUT deleting it.
The shared `credstore-service` is operator-managed and may hold credentials
that other apps depend on — `--delete-services` would destroy it.

Detach the specific orphan only:
```bash
cf v3-unbind-service <orphan-name> <app-name> 2>/dev/null || true
cf delete-service <orphan-name> -f    # ONLY if you confirmed the orphan is a
                                       # leftover *owned* by this MTA (e.g.
                                       # `pass-store-credstore`), NOT the shared
                                       # `credstore-service`.
```

For the persisted MTA manifest, use the targeted form:
```bash
cf undeploy <mta-id> --delete-service-keys --delete-service-brokers -f
```
Note the **absence of `--delete-services`** — keep the operator-owned
`credstore-service` intact. The next deploy will re-bind to it via
`existing-service` from the descriptor.

### SSL handshake failure during `CredStoreClient` init

**Cause:** mTLS certificate or key parsing failed.

**Fix:**
1. Confirm the service binding actually has both `certificate` and `key` keys
   (`cf env <app-name> | grep -A 20 credstore`).
2. The `key` must be PEM-encoded PKCS#1 (begins `-----BEGIN RSA PRIVATE KEY-----`)
   — the `KeyParser` in `assets/` only accepts PKCS#1. PKCS#8 (`-----BEGIN PRIVATE KEY-----`)
   would need a converter; if you see this, regenerate the binding with
   `authentication.type: mtls` (see Step 4) — the broker emits PKCS#1 by default.

### `cannot find symbol: method getUrl()/getKey()/getCertificate()/getMessage()/isSuccess()`

**Cause:** Lombok was added back to a class that was previously Lombok-free.
Lombok versions older than ~1.18.36 produce no bytecode under JDK 25, so
`@Getter`-annotated fields silently lose their accessor methods.

**Fix:** Don't reintroduce Lombok in this skill's helper classes. The shipped
versions in `assets/` write getters by hand for exactly this reason. If you must
use Lombok elsewhere in the project, pin `>= 1.18.40`.

## Security Best Practices

1. **Namespace Isolation:** Use separate namespaces for different environments
2. **Key Rotation:** Regularly rotate keys and certificates
3. **Least Privilege:** Only request credentials that are needed
4. **Audit Logging:** Enable audit logging in Credential Store
5. **Memory Clearing:** Clear sensitive data from memory when done

## Next Steps

After completing this skill, proceed to other applicable skills:
- [../monitoring-logging/SKILL.md](../monitoring-logging/SKILL.md) - Monitor credential access
