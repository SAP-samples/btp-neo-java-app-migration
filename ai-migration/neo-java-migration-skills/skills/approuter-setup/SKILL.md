---
name: approuter-setup
description: Invoke this skill to set up the SAP Application Router (approuter) for Cloud Foundry ONLY for apps that serve static UI (HTML/CSS/JS under webapp/) or require authentication (web.xml <auth-method>/<security-constraint>, or XSUAA). A bare web.xml on an API-only, no-auth backend (a servlet returning JSON/text) does NOT need an approuter — do not invoke this skill for it. Creates the approuter directory with package.json, xs-app.json, and optional extended middleware for SAML2/Basic Auth handling.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Bash, Grep, Glob
---


# Application Router Setup

Set up the SAP Application Router (approuter) as the single entry-point for your Cloud Foundry application.

## Purpose

The SAP Application Router is a key infrastructure component for web-facing applications running in Cloud Foundry on SAP BTP. It is used to:

- **Serve static content:** Deliver HTML, CSS, and JavaScript files directly to the client.
- **Authenticate users:** Trigger authentication processes via XSUAA to ensure secure access.
- **Rewrite URLs:** Modify URLs of requests to match routing rules.
- **Forward or proxy requests:** Redirect client requests to the appropriate backend services.
- **Propagate user information:** Include user data (user ID, roles) when forwarding requests to backend services.

In the Neo environment, the platform handled routing and authentication implicitly. In Cloud Foundry, you must explicitly set up the approuter as a separate Node.js module.

## Detection

This skill applies if any of these conditions are true:

### Web-facing application
```bash
# Has a webapp directory with web content
find . -path "*/webapp/*" -type f | head -5

# Has HTML files served to users
find . -name "*.html" -path "*/webapp/*" | head -5

# Has web.xml (servlet-based app)
find . -name "web.xml" -path "*/WEB-INF/*" | head -5
```

### Authentication is present
```xml
<!-- web.xml contains auth-method -->
<auth-method>FORM</auth-method>

<!-- OR security-constraint definitions -->
<security-constraint>
    <web-resource-collection>
        <web-resource-name>Protected</web-resource-name>
        <url-pattern>/protected/*</url-pattern>
    </web-resource-collection>
</security-constraint>
```

### User explicitly requests approuter setup
- "Set up approuter"
- "Add application router"
- "Configure approuter for my app"

## When this skill does NOT apply — do not add an approuter

> 🛑 **The presence of `web.xml` alone does NOT mean you need an approuter.**
> An approuter's job is serving static UI content and/or fronting XSUAA
> authentication. A **standalone, API-only backend with no authentication** does
> not need one — and adding it is actively harmful: the standard approuter's
> `xs-app.json` binds XSUAA, so the module fails to boot with
> `Cannot find service uaa in environment: No service matches uaa`
> (and `ias`), taking down an otherwise-working app.
>
> **Skip this skill (no approuter) when ALL of these hold:**
> - No authentication: `web.xml` has no `<auth-method>` and no
>   `<security-constraint>`, and there is no XSUAA `<resource-ref>`.
> - No served UI: no `.html`/`.css`/`.js` under `webapp/` (a servlet that only
>   returns JSON/text is API-only).
> - The app is reached directly (e.g. a mail-sender, a REST/JDBC backend).
>
> In that case the migrated app is a single `java.tomcat` module bound directly
> to whatever services it needs (destination, connectivity, credstore, …) —
> **no `approuter/` module, no `xs-app.json`, no uaa/ias resources.** The
> `mail`, `persistence`, and `document-management` scenarios are exactly this
> shape (JSON/REST backends with a `web.xml` but no auth and no served UI).
>
> ⚠️ **If you add an approuter anyway, you own the route mapping.** The standard
> `xs-app.json` rewrites the public path onto the backend destination. If the
> WAR's context root is non-root (e.g. `document-management.war` serves at
> `/document-management`, not `/`) and the `xs-app.json` route target does not
> account for it, **every request 404s** — the approuter forwards `/ecm` to the
> backend as `/document-management/ecm`, which does not exist. This is silent:
> the app is healthy, the approuter is healthy, and every call returns 404.
> Skipping the approuter entirely (the app is reached directly) avoids the
> whole class of mismatch.

## Prerequisites

Before invoking this skill, ensure you have invoked:

1. **sdk-replacement** - `Use the sdk-replacement skill`
   - Sets up SAP Cloud SDK
   - REQUIRED before this skill

## Decision: Standard vs Extended Approuter

> **Default to Standard.** The extended approuter is rarely needed. XSUAA handles SAML, OIDC, and Basic auth challenges natively. Only choose Extended if you have **confirmed** that your backend application explicitly sends `WWW-Authenticate` or `com.sap.cloud.security.logout` headers in HTTP responses that require client-side redirect interception.

Before proceeding, determine which approuter variant your application needs:

| Scenario | Variant | Start Command |
|----------|---------|---------------|
| All endpoints require XSUAA auth | **Standard** | `node node_modules/@sap/approuter/approuter.js` |
| Mix of authenticated + public endpoints | **Standard** | `node node_modules/@sap/approuter/approuter.js` |
| Neo app used SAML2 + Basic Auth (common) | **Standard** | `node node_modules/@sap/approuter/approuter.js` |
| Backend explicitly sends `WWW-Authenticate` headers that need client-side redirect | **Extended** | `node server.js` |
| Backend explicitly sends `com.sap.cloud.security.logout` headers | **Extended** | `node server.js` |

> **Rule of thumb:** Use **Standard** in almost all cases. The fact that the Neo application used SAML2 or Basic Auth does **not** mean you need the extended approuter — XSUAA handles these authentication methods natively. Only choose Extended if the user explicitly confirms the backend triggers `WWW-Authenticate` challenge headers that must be intercepted and redirected by middleware (this is very rare in CF).

## Transformation Steps

### Step 1: Create the approuter Directory

Create a new `approuter/` directory in the project root:

```bash
mkdir -p approuter
```

### Step 2: Create package.json

#### Standard Approuter

Create `approuter/package.json` — see [assets/package.json](assets/package.json):

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

#### Extended Approuter

If you need the extended approuter, use a custom start script instead:

```json
{
    "name": "approuter",
    "dependencies": {
        "@sap/approuter": "^16.0.0"
    },
    "scripts": {
        "start": "node server.js"
    }
}
```

> **Note:** The version of `@sap/approuter` may be updated over time. Check the [NPM package page](https://www.npmjs.com/package/@sap/approuter) for the latest version during the actual migration.

### Step 3: Create xs-app.json (Routing Configuration)

The `xs-app.json` file defines how the approuter routes requests to your backend application.

#### Standard xs-app.json

Create `approuter/xs-app.json` — see [assets/xs-app.json](assets/xs-app.json):

```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "xsuaa",
            "csrfProtection": false
        }
    ]
}
```

> **Note:** Replace `<destination-name>` with the name of the destination you will wire in `mtad.yaml` (e.g., `backend-app-destination`). This destination proxies requests from the approuter to your Java backend.

#### xs-app.json with Protected and Public Routes

If your application has both protected and public endpoints:

```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^/protected(/.*)?$",
            "target": "/protected$1",
            "destination": "<destination-name>",
            "authenticationType": "xsuaa",
            "csrfProtection": false
        },
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
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

> **Important:** Routes are matched top-to-bottom. Place more specific routes (e.g., `/protected`) above the catch-all route (`^(/.*)`).

#### Preserving Servlet Path Prefixes

If the backend's `web.xml` maps the JAX-RS/Jersey servlet to a specific prefix (e.g., `/webapi/*`), the approuter route **must** preserve that prefix in the target. A common mistake is writing a route that strips the prefix:

```json
// WRONG — strips /webapi, backend servlet mapping won't match
{ "source": "^/webapi(/.*)?$", "target": "$1" }

// CORRECT — preserves /webapi, backend servlet mapping matches
{ "source": "^/webapi(/.*)?$", "target": "/webapi$1" }
```

On Neo there was no approuter — the browser called the backend directly and the servlet mapping matched naturally. On CF, the approuter sits in front, so its target must forward the path the backend expects. If the backend also serves static content (HTML, CSS, JS) from the WAR's `webapp/` directory, do NOT change the servlet mapping to `/*` as this causes Jersey to intercept static resource requests, returning "Not Found".

#### Extended xs-app.json (Rare — Backend-Initiated Auth Challenges Only)

> **Warning:** Only use this template if you have explicitly verified the need for extended approuter (see decision table above). In most migrations, the standard xs-app.json is sufficient. Using the extended configuration unnecessarily introduces routing complexity and potential crashes.

For applications where the backend explicitly sends `WWW-Authenticate` or `com.sap.cloud.security.logout` headers — see [assets/xs-app-extended.json](assets/xs-app-extended.json):

```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^/protected(/.*)?$",
            "target": "/protected$1",
            "destination": "<destination-name>",
            "authenticationType": "xsuaa",
            "csrfProtection": false
        },
        {
            "source": "^/authentication/endpoint(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "xsuaa",
            "csrfProtection": false
        },
        {
            "source": "^/basic/authentication/endpoint(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "basic",
            "csrfProtection": false
        },
        {
            "source": "^/logout/callback",
            "target": "/logout",
            "destination": "<destination-name>",
            "authenticationType": "none",
            "csrfProtection": false
        },
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "<destination-name>",
            "authenticationType": "none",
            "csrfProtection": false
        }
    ],
    "logout": {
        "logoutEndpoint": "/logout",
        "logoutPage": "/logout/callback"
    }
}
```

### Step 4: Create Extended Approuter Files (if needed)

Skip this step if using the standard approuter.

#### approuter/server.js

Create `approuter/server.js` — see [assets/server.js](assets/server.js):

```javascript
var approuter = require('@sap/approuter');
var ar = approuter();

ar.start({
    extensions: [
        require('./authentication-challenge-handler.js')
    ]
});
```

#### approuter/authentication-challenge-handler.js

Create `approuter/authentication-challenge-handler.js` — see [assets/authentication-challenge-handler.js](assets/authentication-challenge-handler.js).

This custom middleware handles:
- **SAML2 authentication challenges:** Intercepts `WWW-Authenticate: SAML2 realm="Identity Authentication Service"` headers from the backend and redirects the user to the XSUAA-authenticated endpoint.
- **Basic authentication challenges:** Intercepts `WWW-Authenticate: Basic realm="SAP HANA Cloud Platform"` headers and redirects to a Basic-authenticated endpoint.
- **Logout handling:** Intercepts `com.sap.cloud.security.logout` headers from the backend and processes logout redirect flows.

```javascript
function redirect(response, locationURL) {
    console.log('Redirecting to: [' + locationURL + ']');
    response.setHeader('Location', locationURL);
    response.statusCode = 303;
    response.end();
}

function handleAuthenticationChallenge(context, authenticateHeader) {
    let incomingRequest = context.incomingRequest;
    let incomingResponse = context.incomingResponse;

    if (authenticateHeader.includes('SAML2 realm="Identity Authentication Service"')) {
        console.log('Handling SAML2 (OIDC) authentication challenge.');
        incomingResponse.setHeader('Location', '/authentication/endpoint' + incomingRequest.url);
        incomingResponse.statusCode = 303;
    } else if (authenticateHeader.includes('Basic realm="SAP HANA Cloud Platform"')) {
        console.log('Handling BASIC (OIDC) authentication challenge.');
        incomingResponse.setHeader('Location', '/basic/authentication/endpoint' + incomingRequest.url);
        incomingResponse.statusCode = 303;
    }
}

function handleLogout(context, logoutRequest) {
    let incomingRequest = context.incomingRequest;
    let incomingResponse = context.incomingResponse;

    if (logoutRequest.includes('logout-request')) {
        console.log('Triggering logout.');
        incomingResponse.setHeader('Location', '/logout/endpoint?originalURL=' + incomingRequest.url);
        incomingResponse.statusCode = 303;
    }
}

module.exports = {
    insertMiddleware: {
        beforeRequestHandler: [
            {
                handler: function authenticationChallengeHandler(request, response, callNextHandler) {
                    console.log('Handling request with path: [' + request.url + ']');

                    if (request.url.startsWith('/authentication/endpoint')) {
                        let locationURL = request.url.substring('/authentication/endpoint'.length);
                        redirect(response, locationURL);
                    } else if (request.url.startsWith('/basic/authentication/endpoint')) {
                        let locationURL = request.url.substring('/basic/authentication/endpoint'.length);
                        redirect(response, locationURL);
                    } else if (request.url.startsWith('/logout/callback?originalURL=')) {
                        let locationURL = request.url.substring('/logout/callback?originalURL='.length);
                        redirect(response, locationURL);
                    } else {
                        request.afterRequestHandler = function (context, done) {
                            let outgoingResponse = context.outgoingResponse;
                            let authenticateHeader = outgoingResponse.headers['www-authenticate'];
                            let logoutRequest = outgoingResponse.headers['com.sap.cloud.security.logout'];

                            if (authenticateHeader !== undefined) {
                                handleAuthenticationChallenge(context, authenticateHeader);
                            }

                            if (logoutRequest !== undefined) {
                                handleLogout(context, logoutRequest);
                            }

                            done(null, context.incomingResponse);
                        };

                        callNextHandler();
                    }
                }
            }
        ]
    }
};
```

### Step 5: Wire Approuter into mtad.yaml

Add the approuter module to your `mtad.yaml`. The Java backend must **provide** its URL, and the approuter **requires** it to create a destination:

```yaml
modules:
  # Java Backend Application
  - name: <app-name>
    type: java.tomcat
    path: <path-to-war-file>
    parameters:
      buildpack: sap_java_buildpack_jakarta
      disk-quota: 512MB
      memory: 512MB
    properties:
      ENABLE_SECURITY_JAVA_API_V2: true
      SET_LOGGING_LEVEL: 'ROOT: INFO'
    requires:
      - name: <app-name>-xsuaa
    provides:
      - name: <app-name>-java-app
        properties:
          java_app_url: '${default-url}'

  # Application Router
  - name: <app-name>-approuter
    type: nodejs
    path: approuter
    parameters:
      disk-quota: 256M
      memory: 256M
      routes:
        - route: '${protocol}://<app-name>.${default-domain}'
          protocol: http1
    properties:
      XS_APP_LOG_LEVEL: debug
    requires:
      - name: <app-name>-xsuaa
      - name: <app-name>-java-app
        group: destinations
        properties:
          name: <destination-name>
          url: '~{java_app_url}'
          forwardAuthToken: true
```

> **Note:** Replace `<app-name>` with your application name and `<destination-name>` with the destination name used in `xs-app.json` (e.g., `backend-app-destination`).

> **Note:** The `group: destinations` property creates a destination environment variable for the approuter at deployment time — no need to create a separate destination service instance for this internal routing.

### Step 6: Verify Approuter Directory Structure

After completing all steps, verify the directory structure:

#### Standard Approuter
```
approuter/
├── package.json
└── xs-app.json
```

#### Extended Approuter
```
approuter/
├── authentication-challenge-handler.js
├── package.json
├── server.js
└── xs-app.json
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `package.json` | approuter/ | Node.js dependencies and start script |
| `xs-app.json` | approuter/ | Routing configuration (routes, auth types) |
| `server.js` | approuter/ | Extended approuter entry point (optional) |
| `authentication-challenge-handler.js` | approuter/ | Custom middleware for auth challenges (optional) |

## CF Services

| Service | Plan | Purpose |
|---------|------|---------|
| `xsuaa` | application | OAuth 2.0 authorization (bound to approuter) |

> **Note:** The approuter itself requires an XSUAA service binding. The `authentication-xsuaa` skill handles creating the `xs-security.json` and XSUAA service resource.

## Verification

### 1. Check File Structure
```bash
ls -la approuter/
# Should show package.json, xs-app.json, and optionally server.js + handler
```

### 2. Validate xs-app.json
```bash
cat approuter/xs-app.json | python3 -m json.tool
# Should parse without errors
```

### 3. Build and Deploy
```bash
mvn clean package
cf deploy . -f
```

### 4. Test Routing
1. Open the approuter URL: `https://<app-name>.<cf-domain>`
2. For authenticated routes: should redirect to identity provider login
3. For unauthenticated routes: should proxy directly to backend
4. Check approuter logs: `cf logs <app-name>-approuter --recent`

### 5. Verify Destination Wiring
```bash
cf env <app-name>-approuter
# Should show destinations environment variable with backend URL
```

## Common Issues

### Issue: 502 Bad Gateway
**Cause:** Backend application not reachable from the approuter.
**Solution:** Verify the `provides`/`requires` URL wiring in `mtad.yaml`. Check that the backend app is started: `cf apps`

### Issue: "No destination found" error
**Cause:** Destination name in `xs-app.json` does not match the destination name in `mtad.yaml`.
**Solution:** Ensure `<destination-name>` is identical in both files.

### Issue: CORS errors in browser
**Solution:** Add CORS headers configuration to `xs-app.json`:
```json
{
    "cors": [
        {
            "uriPattern": "^/api/(.*)$",
            "allowedOrigin": [{ "host": "*" }],
            "allowedMethods": ["GET", "POST", "PUT", "DELETE"],
            "allowedHeaders": ["Authorization", "Content-Type"]
        }
    ]
}
```

### Issue: Infinite redirect loop with extended approuter
**Cause:** Route patterns in `xs-app.json` conflict with middleware redirect paths.
**Solution:** Ensure the `/authentication/endpoint` and `/basic/authentication/endpoint` routes in `xs-app.json` match the paths handled by `authentication-challenge-handler.js`.

### Issue: TENANT_HOST_PATTERN errors (multi-tenant apps)
**Solution:** Add the pattern as a property in the approuter module in `mtad.yaml`:
```yaml
properties:
  TENANT_HOST_PATTERN: '(.*).cfapps.sap.hana.ondemand.com'
```

### Issue: Blank/empty page — approuter routes to backend but page renders nothing
**Cause:** The approuter forwards the request correctly, and the HTML page loads (200 OK), but the page appears blank. This is typically caused by **SAPUI5 not loading**:
1. In Neo, `com.sap.ui5.resource.ResourceServlet` served SAPUI5 at `/resources/*`. In CF, that servlet doesn't exist, so `<script src="resources/sap-ui-core.js">` returns 404 silently.
2. The old CDN domain `sapui5.hana.ondemand.com` no longer serves older SAPUI5 versions (pre-1.71) — pinned versions like `1.38.x` return 404.
3. The `sap_bluecrystal` theme was removed in SAPUI5 1.40+ and won't load.

**Diagnosis:** Open the browser developer tools (F12 → Network tab). Look for a 404 on `sap-ui-core.js` or theme CSS files.
**Solution:** Update all HTML files to load SAPUI5 from the current CDN domain, use a supported version, and replace deprecated themes:
```html
<script src="https://ui5.sap.com/1.120.0/resources/sap-ui-core.js" ...
    data-sap-ui-theme="sap_fiori_3" ...>
```
See the `sdk-replacement` skill, **Step 6: Migrate SAPUI5 ResourceServlet** for full instructions and bulk-fix commands.

### Issue: REST API calls from the UI return 404 after migration
**Cause:** The backend's JAX-RS/CXF servlet is declared in web.xml but has no `<servlet-mapping>`. The Neo web.xml may have had a mapping that was accidentally omitted during the copy to the CF project. Without the mapping, API endpoints like `/rest/*` are unreachable.
**Diagnosis:** Check `src/main/webapp/WEB-INF/web.xml` — if there is a `<servlet>` for CXFServlet but no `<servlet-mapping>`, that's the problem.
**Solution:** Add the missing servlet mapping:
```xml
<servlet-mapping>
    <servlet-name>CXFServlet</servlet-name>
    <url-pattern>/rest/*</url-pattern>
</servlet-mapping>
```
See the `sdk-replacement` skill, **Step 7: Verify Servlet Mappings** for details.

### Issue: REST API calls return 404 — approuter strips servlet path prefix
**Cause:** The approuter's `xs-app.json` route uses a target pattern like `"target": "$1"` that strips a path prefix (e.g., `/webapi`). On Neo there was no approuter, so the browser called the backend directly at `/webapi/cnbrest/v1/...` and the servlet mapping `/webapi/*` matched. On CF, the approuter strips the prefix and the request arrives at the backend as `/cnbrest/v1/...`, which doesn't match the old servlet mapping.
**Diagnosis:**
```bash
# Check if xs-app.json strips a prefix
cat approuter/xs-app.json
# A route like: "source": "^/webapi(/.*)?$", "target": "$1"
# strips /webapi — request arrives at backend without it

# Check servlet mapping
grep -A2 "servlet-mapping" src/main/webapp/WEB-INF/web.xml
```
**Solution:** Fix the approuter route to **preserve** the path prefix by including it in the target:
```json
{
    "source": "^/webapi(/.*)?$",
    "target": "/webapi$1",
    "destination": "backend-app-destination"
}
```
This way the request path arrives at the backend unchanged and the existing servlet mapping `/webapi/*` continues to work.

> **Warning:** Do NOT fix this by changing the servlet mapping from `/webapi/*` to `/*`. While that makes API calls work, it causes Jersey to intercept ALL requests including static content (HTML, CSS, JS), returning "Not Found" for non-API paths. The backend typically serves both static content (via Tomcat's default servlet) and REST APIs (via the Jersey servlet). Mapping Jersey to `/*` breaks static content serving.

## Next Steps

After completing this skill, proceed to:
- [../authentication-xsuaa/SKILL.md](../authentication-xsuaa/SKILL.md) - Set up XSUAA security configuration (xs-security.json, auth-method, user access code)
- [../mta-descriptor/SKILL.md](../mta-descriptor/SKILL.md) - Generate the full MTA deployment descriptor

## Related Information

- [Application Router | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/application-router)
- [Extending the Application Router | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/extending-application-router)
- [Routing Configuration File | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/routing-configuration-file)
- [@sap/approuter NPM Package | npmjs.com](https://www.npmjs.com/package/@sap/approuter)
- [Custom Middleware Injection | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/extending-application-router#custom-middleware-injection)
- For advanced routing patterns, Docker deployment, and multi-tenancy, see [references/advanced-routing.md](references/advanced-routing.md)
