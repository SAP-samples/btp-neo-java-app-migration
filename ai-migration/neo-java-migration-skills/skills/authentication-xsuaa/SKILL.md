---
name: authentication-xsuaa
description: Invoke this skill to set up XSUAA authentication and Application Router. Detects <auth-method>FORM</auth-method> in web.xml, security-constraint definitions, or UserProvider usage in Java code. Replaces Neo built-in auth with CF XSUAA.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Authentication and Authorization

Set up XSUAA-based authentication and Application Router for Cloud Foundry.

## Purpose

Replace Neo's built-in FORM authentication and `UserManagementAccessor` with Cloud Foundry's XSUAA service and Application Router for secure web application access.

## Detection

This skill applies if any of these patterns are found:

### In web.xml
```xml
<auth-method>FORM</auth-method>

<!-- OR -->
<security-constraint>
    <web-resource-collection>
        <web-resource-name>Protected</web-resource-name>
        <url-pattern>/protected/*</url-pattern>
    </web-resource-collection>
</security-constraint>

<!-- OR -->
<security-role>
    <role-name>Everyone</role-name>
</security-role>
```

### In Java source files
```java
import com.sap.security.um.user.UserProvider;
import com.sap.security.um.user.User;
// OR
request.getUserPrincipal();
request.isUserInRole("Everyone");
```

## Prerequisites

> **Working directory:** This skill must run inside the `-cf-migration` copy of your app, created by `jakarta-java25-migration` or `neo-to-cf-migration-orchestrator`. If your current directory does not end in `-cf-migration`, switch to it before proceeding.


Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

## Transformation Steps

### Step 1: Create xs-security.json

Create `xs-security.json` in your project root (or `cf/` folder) - see [assets/xs-security.json](assets/xs-security.json) for template:

```json
{
    "xsappname": "${app-name}",
    "tenant-mode": "dedicated",
    "scopes": [
        {
            "name": "$XSAPPNAME.Everyone",
            "description": "Everyone scope for authenticated users"
        }
    ],
    "role-templates": [
        {
            "name": "Everyone",
            "scope-references": [
                "$XSAPPNAME.Everyone"
            ]
        }
    ],
    "role-collections": [
        {
            "name": "${app-name}-Everyone",
            "role-template-references": [
                "$XSAPPNAME.Everyone"
            ]
        }
    ]
}
```

> **Customize:** Replace `${app-name}` with your application name and `Everyone` with each actual role name from your Neo app. The naming convention for role collections is `<app-name>-<role-name>` (e.g. `myapp-Admin`, `myapp-Viewer`). Add one entry per role-template in the `role-collections` array. `subaccount-roles-import` reads these names to link deployed role-templates and assign users.

### Step 2: Update web.xml Authentication Method

**Before:**
```xml
<login-config>
    <auth-method>FORM</auth-method>
    <form-login-config>
        <form-login-page>/login.html</form-login-page>
        <form-error-page>/login-error.html</form-error-page>
    </form-login-config>
</login-config>
```

**After:**
```xml
<login-config>
    <auth-method>XSUAA</auth-method>
</login-config>
```
### Step 3: Add Security Library Dependency

Add to `pom.xml`:

```xml
<!-- SAP Cloud Security Java API (non-Spring applications) -->
<dependency>
    <groupId>com.sap.cloud.security</groupId>
    <artifactId>java-api</artifactId>
</dependency>

<!-- For Spring Boot applications, use instead:
<dependency>
    <groupId>com.sap.cloud.security</groupId>
    <artifactId>resourceserver-security-spring-boot-starter</artifactId>
</dependency>
-->
```

> **Note:** The `java-api` artifact is managed by the `cf-tomcat-bom` BOM, so no version is needed.

### Step 4: Create Application Router

Create `approuter/` directory with these files:

#### approuter/package.json

Copy from [assets/package.json](assets/package.json):

```json
{
    "name": "approuter",
    "dependencies": {
        "@sap/approuter": "^16.0.0"
    },
    "scripts": {
        "start": "node node_modules/@sap/approuter/approuter.js"
    }
}
```

#### approuter/xs-app.json

See [assets/xs-app.json](assets/xs-app.json) for a complete template:

```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^/protected(/.*)?$",
            "target": "/protected$1",
            "destination": "backend-app-destination",
            "authenticationType": "xsuaa",
            "scope": "$XSAPPNAME.Everyone",
            "csrfProtection": false
        },
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "backend-app-destination",
            "authenticationType": "none",
            "csrfProtection": false
        }
    ],
    "logout": {
        "logoutEndpoint": "/logout",
        "logoutPage": "/"
    }
}
```

> **Scopes in routes:** Use `"scope": "$XSAPPNAME.<ScopeName>"` on each protected route to enforce XSUAA scope checks at the approuter level. The `$XSAPPNAME` placeholder is resolved at runtime to the bound XSUAA service's `xsappname`. Routes without a `scope` field only require authentication (when `authenticationType` is `xsuaa`).

### Step 5: Update User Access Code

#### Neo UserProvider → CF XSUAA Token/TokenClaims mapping

| Neo `UserProvider` / `User` API | CF XSUAA equivalent |
|----------------------------------|---------------------|
| `user.getName()` | `principal.getName()` — returns `user_uuid` (IAS) or `user_name` (XSUAA) |
| `user.getAttribute("email")` | `token.getClaimAsString(TokenClaims.EMAIL)` |
| `user.getAttribute("firstname")` | `token.getClaimAsString(TokenClaims.GIVEN_NAME)` |
| `user.getAttribute("lastname")` | `token.getClaimAsString(TokenClaims.FAMILY_NAME)` |
| `user.getAttribute("displayName")` | `token.getClaimAsString(TokenClaims.SAP_GLOBAL_USER_DISPLAY_NAME)` |
| `user.getId()` (unique ID) | `token.getClaimAsString(TokenClaims.SAP_GLOBAL_USER_ID)` |
| `request.isUserInRole("Admin")` | `request.isUserInRole("Admin")` — unchanged, maps to XSUAA scope |
| Neo tenant / account name | `token.getClaimAsString(TokenClaims.SAP_GLOBAL_ZONE_ID)` |
| `user.getAttribute("logon_name")` | `token.getClaimAsString(TokenClaims.USER_NAME)` |
| XSUAA scopes list | `token.getClaimAsStringList(TokenClaims.XSUAA.SCOPES)` — XSUAA-specific, not available for IAS tokens |

**Before (Neo):**
```java
import com.sap.security.um.user.UserProvider;
import com.sap.security.um.user.User;

@Resource
private UserProvider userProvider;

public void doGet(HttpServletRequest request, HttpServletResponse response) {
    User user = userProvider.getUser(request);
    String userName = user.getName();
    String email = user.getAttribute("email");
    String firstName = user.getAttribute("firstname");
    boolean isAdmin = request.isUserInRole("Admin");
}
```

**After (Cloud Foundry — in JAX-RS endpoints or servlets):**
```java
import com.sap.cloud.security.token.SecurityContext;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

    // 1. Basic identity — works with servlet security constraint in web.xml
    Principal principal = request.getUserPrincipal();
    if (principal == null) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
    }
    String userName = principal.getName();  // user_uuid or user_name depending on IdP

    // 2. Read claims from the JWT token for richer user attributes
    Token token = SecurityContext.getToken();
    if (token != null) {
        String email      = token.getClaimAsString(TokenClaims.EMAIL);
        String firstName  = token.getClaimAsString(TokenClaims.GIVEN_NAME);
        String lastName   = token.getClaimAsString(TokenClaims.FAMILY_NAME);
        String userId     = token.getClaimAsString(TokenClaims.SAP_GLOBAL_USER_ID);
        String logonName  = token.getClaimAsString(TokenClaims.USER_NAME);

        // XSUAA scopes — only available for XSUAA tokens, null for IAS tokens
        List<String> scopes = token.getClaimAsStringList(TokenClaims.XSUAA.SCOPES);

        // Tenant (zone ID) — identifies the subaccount
        String zoneId = token.getClaimAsString(TokenClaims.SAP_GLOBAL_ZONE_ID);
    }

    // 3. Role / scope check — unchanged from Neo
    boolean isAdmin = request.isUserInRole("Admin");
}
```

> **Why `SecurityContext.getToken()`?** The SAP Java Buildpack's `XSSecurityAuthenticator` Catalina valve validates the JWT and stores the parsed `Token` object in a thread-local via `SecurityContext`. This is the correct and recommended way to access the token. `request.getAttribute(Token.class.getName())` does NOT work in this context.

> **`Token` vs `Principal`:** `request.getUserPrincipal().getName()` is sufficient for identifying the user. Only reach for `Token` when you need claims that are not exposed through the standard servlet API — email, given name, family name, zone ID.

> **Null safety:** `token.getClaimAsString(...)` returns `null` if the claim is absent (e.g., the IdP did not include it). Always null-check before use in production code.

### Step 6: Create MTA Descriptor with Approuter

Create or update `mtad.yaml`:

```yaml
_schema-version: "3.2"
version: 0.0.1
ID: ${app-name}

parameters:
  enable-parallel-deployments: true

modules:
  # Java Backend Application
  - name: ${app-name}
    type: java.tomcat
    path: target/<artifactId>.war   # substitute literal artifactId from pom.xml; app serves at /<artifactId>
    parameters:
      buildpack: sap_java_buildpack_jakarta
      disk-quota: 1024M
      memory: 1024M
    properties:
      ENABLE_SECURITY_JAVA_API_V2: true
      JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
      JBP_CONFIG_SAP_MACHINE_JRE: "{ version: 25.+ }"
      TARGET_RUNTIME: tomcat
      SET_LOGGING_LEVEL: 'ROOT: INFO'
    requires:
      - name: ${app-name}-xsuaa
      - name: ${app-name}-destination
    provides:
      - name: ${app-name}-java-app
        properties:
          neo-app-url: '${default-url}'

  # Application Router
  - name: ${app-name}-approuter
    type: nodejs
    path: approuter
    parameters:
      disk-quota: 256M
      memory: 256M
      routes:
        - route: '${protocol}://${app-name}.${default-domain}'
          protocol: http1
    properties:
      XS_APP_LOG_LEVEL: debug
      TENANT_HOST_PATTERN: '(.*).cfapps.sap.hana.ondemand.com'
      CF_NODEJS_LOGGING_LEVEL: "info"
    requires:
      - name: ${app-name}-xsuaa
      - name: ${app-name}-java-app
        group: destinations
        properties:
          name: backend-app-destination
          url: '~{neo-app-url}'
          forwardAuthToken: true

resources:
  # XSUAA Service
  - name: ${app-name}-xsuaa
    type: org.cloudfoundry.managed-service
    parameters:
      service: xsuaa
      service-plan: application
      path: ./xs-security.json

  # Destination Service
  - name: ${app-name}-destination
    type: org.cloudfoundry.managed-service
    parameters:
      service: destination
      service-plan: lite
```

> **Key points:**
> - `path: target/<artifactId>.war` — read the `<artifactId>` from `pom.xml` and substitute it literally (the pom assets ship `maven-war-plugin` with `<warName>${project.artifactId}</warName>`, so the WAR is named after the artifactId). The app will serve at `/<artifactId>` — approuter destinations and tests must use that prefix. See `mta-descriptor` → "WAR filename rule" for the full guidance.
> - `ENABLE_SECURITY_JAVA_API_V2: true` — required for XSUAA JWT validation via the `java-api` library.
> - `JBP_CONFIG_COMPONENTS` + `JBP_CONFIG_SAP_MACHINE_JRE` — pin to SAPMachineJRE 25.
> - `provides` on the backend uses a custom property name (e.g. `neo-app-url`) and the approuter `requires` references it with `~{neo-app-url}`. The `url` shorthand only works if the `provides` block uses a property literally named `url`.
> - `disk-quota: 1024M` minimum — 512M causes deployment failures with the SAP Java buildpack.

### Step 7: Remove Neo-Specific Login Pages (Optional)

If you had custom login pages for FORM authentication, you can remove them as the Approuter handles authentication via redirect to the identity provider.

Files to consider removing:
- `login.html`
- `login-error.html`
- Related CSS/JS for login

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `xs-security.json` | Project root | XSUAA security configuration |
| `package.json` | approuter/ | Approuter Node.js dependencies |
| `xs-app.json` | approuter/ | Approuter routing configuration |

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `xsuaa` | application | OAuth 2.0 authorization server |
| `destination` | lite | Internal routing (for approuter) |

## Verification

### 1. Build and Deploy
```bash
mvn clean package
cf deploy . -f
```

### 2. Check Services
```bash
cf services
# Should show xsuaa and destination services bound
```

### 3. Test Authentication
1. Open the approuter URL: `https://${app-name}.${domain}`
2. Should redirect to identity provider login
3. After login, should access protected resources

### 4. Verify Token
Add debug endpoint to verify JWT token is received:

```java
@WebServlet("/debug/token")
public class TokenDebugServlet extends HttpServlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        Principal principal = req.getUserPrincipal();
        resp.getWriter().println("User: " + (principal != null ? principal.getName() : "null"));
        resp.getWriter().println("Is Everyone: " + req.isUserInRole("Everyone"));
    }
}
```

## Common Issues

### Issue: 401 Unauthorized after login
**Cause:** Role collection not assigned to user.
**Solution:** In BTP Cockpit, assign the role collection to your user.

### Issue: Approuter returns 502 Bad Gateway
**Cause:** Backend app not reachable.
**Solution:** Check that the backend URL in provides/requires is correct.

### Issue: All requests return 404 after successful deployment

**Cause:** The WAR is named `<artifactId>.war` (the default in this skill's pom templates), so the SAP Java buildpack serves it at the Tomcat context path `/<artifactId>` — not `/`. Requests to `/` or `/currentuser` return 404 because Tomcat only serves at `/<artifactId>/*`. This is the expected behavior, not a deployment bug.

**Solution:** Update the caller — approuter routes (`xs-app.json`), integration tests, and any frontend code — to include the `/<artifactId>` prefix. For example, if the app's `<artifactId>` is `auth`, the deployed servlet at `/currentuser` is reachable at `/auth/currentuser`. The approuter destination's `url` should point at `~{neo-app-url}/auth` (or whatever the artifactId is).

If you genuinely need the app to serve at `/` rather than `/<artifactId>` (e.g. legacy clients pin to root-relative paths and can't be updated), change the pom's `<warName>` to `ROOT` and the descriptor's `path:` to `target/ROOT.war` together — both must move in lockstep. The skill's defaults choose the artifactId-named WAR because it avoids the descriptor-vs-pom mismatch that breaks `cf deploy` outright; serving at `/<artifactId>` is the deliberate trade-off.

### Issue: CORS errors
**Solution:** Add CORS configuration to approuter or backend:

```json
// xs-app.json
{
    "cors": {
        "allowedOrigins": ["*"],
        "allowedMethods": ["GET", "POST", "PUT", "DELETE"]
    }
}
```

## Extended Approuter (Basic Auth Support)

For scenarios requiring Basic Authentication (e.g., API access), see [references/extended-approuter.md](references/extended-approuter.md) for an extended approuter implementation.

### Issue: getUserPrincipal() returns null and isUserInRole() always returns false — even though the user is authenticated via the approuter
**Cause:** The `XSSecurityAuthenticator` Catalina valve only validates JWT tokens for URLs that match a `<security-constraint>` in `web.xml`. If the REST API URL patterns (e.g., `/rest/*`, `/api/*`) are not covered by any security constraint, the valve skips JWT validation entirely. The approuter forwards a valid JWT token in the `Authorization` header, but the backend never processes it. As a result, `getUserPrincipal()` returns `null` and all `isUserInRole()` calls return `false` — regardless of whether you use `@Context`, `@Inject`, or `SessionContext`. This is the **most common cause** of authorization failures after migration and is easy to miss because the Neo-era constraints often covered only static pages (e.g., `/index.html`) while REST APIs were covered by auth-method-specific URL prefixes (`/s/api/*`, `/b/api/*`) that were removed during migration.
**Diagnosis:** Add a debug endpoint and check whether `getUserPrincipal()` returns `null`. If it does, the issue is missing security constraints, not the injection method.
**Solution:** See **Step 5** above. Add a `<security-constraint>` covering `/rest/*` (or `/*` for all paths) with an `<auth-constraint>` requiring the `Everyone` role. Fine-grained role checks (admin, manager) should be done in Java code, not via URL-level constraints.

### Issue: isUserInRole() returns false in @Stateless EJBs on TomEE — user has role but gets AuthorizationException
**Cause:** First verify this is not the missing security constraint issue above (check if `getUserPrincipal()` returns `null`). If the principal IS set but `isUserInRole()` still returns `false`: `@Context HttpServletRequest` (JAX-RS injection) does not carry the XSUAA security context when used in `@Stateless` or `@Singleton` EJBs that are not JAX-RS resources. The request object is a CXF-internal wrapper that doesn't delegate `isUserInRole()` to the Catalina/XSUAA security realm. `SessionContext.isCallerInRole()` also fails because TomEE's `OpenEJBSecurityListener` does not fully propagate XSUAA roles to the OpenEJB security context. This is commonly seen in CDI producer beans or service provider EJBs that check roles before returning a service implementation.
**Solution:** Replace `@Context` with `@Inject` for `HttpServletRequest`. CDI injection provides a request-scoped proxy that delegates to the real Catalina request with the XSUAA security context. Both `isUserInRole()` and `getUserPrincipal()` then work correctly. See also the **tomee-runtime** skill for details.

## Next Steps

After completing this skill, proceed to:
- [../destinations/SKILL.md](../destinations/SKILL.md) - Configure external destinations
- [../connectivity-onpremise/SKILL.md](../connectivity-onpremise/SKILL.md) - Enable on-premise connectivity
