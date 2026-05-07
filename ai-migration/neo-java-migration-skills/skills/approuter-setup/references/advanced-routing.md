# Advanced Routing, Docker Deployment, and Multi-Tenancy

This reference provides additional approuter configuration patterns beyond the standard and extended setups covered in the main SKILL.md.

## Advanced Route Patterns

### Route Pattern Reference

| Pattern | Matches | Example |
|---------|---------|---------|
| `^(/.*)`  | Everything (catch-all) | `/api/users`, `/index.html` |
| `^/api(/.*)?$` | `/api` and any sub-path | `/api`, `/api/users` |
| `^/protected(/.*)?$` | `/protected` and any sub-path | `/protected/data` |
| `^/public(/.*)?$` | `/public` and any sub-path | `/public/health` |

### Multi-Destination Routing

Route different paths to different backend services:

```json
{
    "authenticationMethod": "route",
    "routes": [
        {
            "source": "^/api(/.*)?$",
            "target": "$1",
            "destination": "backend-api",
            "authenticationType": "xsuaa"
        },
        {
            "source": "^/admin(/.*)?$",
            "target": "$1",
            "destination": "admin-service",
            "authenticationType": "xsuaa"
        },
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "ui-service",
            "authenticationType": "none"
        }
    ]
}
```

Wire multiple destinations in `mtad.yaml`:

```yaml
- name: <app-name>-approuter
  type: nodejs
  path: approuter
  requires:
    - name: <app-name>-xsuaa
    - name: backend-api-module
      group: destinations
      properties:
        name: backend-api
        url: '~{url}'
        forwardAuthToken: true
    - name: admin-service-module
      group: destinations
      properties:
        name: admin-service
        url: '~{url}'
        forwardAuthToken: true
```

### Static File Serving

Serve static files directly from the approuter without proxying to a backend:

```json
{
    "routes": [
        {
            "source": "^/static(/.*)?$",
            "target": "$1",
            "localDir": "webapp"
        },
        {
            "source": "^(/.*)",
            "target": "$1",
            "destination": "backend-api",
            "authenticationType": "xsuaa"
        }
    ]
}
```

Place static files in `approuter/webapp/`.

### WebSocket Support

Enable WebSocket connections through the approuter:

```json
{
    "routes": [
        {
            "source": "^/ws(/.*)?$",
            "target": "$1",
            "destination": "backend-ws",
            "authenticationType": "xsuaa"
        }
    ],
    "websockets": {
        "enabled": true
    }
}
```

## Docker-Based Approuter Deployment

Instead of deploying the approuter as a `nodejs` module, you can deploy it from a Docker image.

### 1. Create Dockerfile

Create `approuter/Dockerfile`:

```dockerfile
FROM node:slim

WORKDIR /usr/src/approuter
COPY ./ ./

RUN npm install

EXPOSE 7000
CMD [ "npm", "start" ]
```

> **Note:** Replace the `node` version with one compatible with your `@sap/approuter` version. See [Docker Hub Node images](https://hub.docker.com/_/node) for available tags.

### 2. Build the Docker Image

```bash
cd approuter
docker build -t <approuter-image-name> .
```

### 3. Deploy from Docker Image

In `mtad.yaml`, configure the approuter module to use the Docker image:

```yaml
- name: <app-name>-approuter
  type: application
  requires:
    - name: <app-name>-xsuaa
    - name: <app-name>-java-app
      group: destinations
      properties:
        name: backend-app-destination
        url: '~{java_app_url}'
        forwardAuthToken: true
  parameters:
    docker:
      image: <approuter-image-name>
    routes:
      - route: '${protocol}://<app-name>.${default-domain}'
        protocol: http1
    disk-quota: 256M
    memory: 256M
  properties:
    XS_APP_LOG_LEVEL: info
```

> **Note:** The Docker image must be pushed to a container registry accessible by Cloud Foundry (e.g., Docker Hub, SAP Container Registry).

## Multi-Tenancy Configuration

For multi-tenant SaaS applications, the approuter needs additional configuration.

### TENANT_HOST_PATTERN

Set the `TENANT_HOST_PATTERN` environment variable in the approuter module:

```yaml
- name: <app-name>-approuter
  type: nodejs
  path: approuter
  properties:
    TENANT_HOST_PATTERN: '^(.*)-<app-name>.cfapps.<landscape>.hana.ondemand.com'
```

### xs-security.json for Multi-Tenancy

```json
{
    "xsappname": "<app-name>",
    "tenant-mode": "shared",
    "scopes": [...],
    "role-templates": [...]
}
```

> **Note:** Use `"tenant-mode": "shared"` instead of `"dedicated"` for multi-tenant applications.

### SaaS Registry Integration

For applications that need tenant onboarding/offboarding, bind the SaaS Provisioning service:

```yaml
resources:
  - name: <app-name>-saas-registry
    type: org.cloudfoundry.managed-service
    parameters:
      service: saas-registry
      service-plan: application
      config:
        xsappname: <app-name>
        appName: <app-name>
        displayName: <app-display-name>
        description: <app-description>
        category: '<category>'
        appUrls:
          getDependencies: '~{<app-name>-approuter/url}/callback/v1.0/dependencies'
          onSubscription: '~{<app-name>-backend/url}/callback/v1.0/tenants/{tenantId}'
```

## Session Management

### Session Timeout

Configure the session timeout in the approuter module:

```yaml
properties:
  SESSION_TIMEOUT: 30  # minutes
```

### External Session Management

For applications requiring sticky sessions or distributed session management:

```yaml
properties:
  EXT_SESSION_MGT:
    instanceName: <redis-instance-name>
    storageType: redis
    sessionSecret: <session-secret>
```

## Related Information

- [Application Router Configuration Syntax | SAP Help Portal](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-developer-guide-for-cloud-foundry-multitarget-applications-sap-web-ide-full-stack/application-router-configuration-syntax)
- [SaaS Provisioning Service | SAP Help Portal](https://help.sap.com/docs/btp/sap-business-technology-platform/using-saas-provisioning-service-to-develop-multitenant-application)
- [@sap/approuter NPM Package | npmjs.com](https://www.npmjs.com/package/@sap/approuter)
