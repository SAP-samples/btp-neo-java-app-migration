# CF CLI JSON / API Output Reference

CF CLI has no `--format json` flag. Use `cf curl` with the CF API v3 for JSON output.
The base URL is always the CF API endpoint, which you can get from `cf api`.

## Get GUIDs (needed for most API calls)

```bash
# App GUID
cf app <APP_NAME> --guid

# Space GUID
cf space <SPACE_NAME> --guid

# Org GUID
cf org <ORG_NAME> --guid

# Service instance GUID
cf curl "/v3/service_instances?names=<INSTANCE_NAME>" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d['resources'][0]['guid'])"
```

---

## Apps API

### List apps in a space

```bash
SPACE_GUID=$(cf space <SPACE> --guid)
cf curl "/v3/apps?space_guids=$SPACE_GUID"
```

```json
{
  "pagination": { "total_results": 2, "total_pages": 1 },
  "resources": [
    {
      "guid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "name": "nonosgi-auth",
      "state": "STARTED",
      "lifecycle": {
        "type": "buildpack",
        "data": { "buildpacks": ["sap_java_buildpack_jakarta"], "stack": "cflinuxfs4" }
      },
      "relationships": {
        "space": { "data": { "guid": "<space-guid>" } }
      },
      "metadata": { "labels": {}, "annotations": {} }
    }
  ]
}
```

**jq pattern:**
```bash
cf curl "/v3/apps?space_guids=$SPACE_GUID" | jq '.resources[] | {name, guid, state}'
```

---

### App process stats (instances status)

```bash
APP_GUID=$(cf app nonosgi-auth --guid)
cf curl "/v3/apps/$APP_GUID/processes/web/stats"
```

```json
{
  "resources": [
    {
      "type": "web",
      "index": 0,
      "state": "RUNNING",
      "usage": {
        "cpu": 0.003,
        "mem": 314572800,
        "disk": 134217728,
        "time": "2024-06-01T10:00:00Z"
      },
      "host": "10.x.x.x",
      "instance_ports": [{ "external": 61001, "internal": 8080 }],
      "uptime": 3600,
      "mem_quota": 1073741824,
      "disk_quota": 1073741824,
      "fds_quota": 16384
    }
  ]
}
```

**jq pattern — check all instances running:**
```bash
cf curl "/v3/apps/$APP_GUID/processes/web/stats" | \
  jq '.resources[] | {index, state, uptime}'
```

---

### App environment variables

```bash
APP_GUID=$(cf app nonosgi-auth --guid)
cf curl "/v3/apps/$APP_GUID/environment_variables"
```

```json
{
  "var": {
    "ENABLE_SECURITY_JAVA_API_V2": "true",
    "JBP_CONFIG_COMPONENTS": "jres: ['com.sap.xs.java.buildpack.jdk.SAPMachineJDK']",
    "TARGET_RUNTIME": "tomcat"
  }
}
```

**Set an environment variable via API:**
```bash
cf curl "/v3/apps/$APP_GUID/environment_variables" -X PATCH \
  -d '{"var":{"MY_VAR":"new-value"}}'
```

---

### VCAP_SERVICES (service bindings in app environment)

```bash
cf env <APP_NAME>
# Includes VCAP_SERVICES block with all bound service credentials

# Or via API:
cf curl "/v3/apps/$APP_GUID/env"
```

```json
{
  "environment_variables": {},
  "staging_env_json": {},
  "running_env_json": {},
  "system_env_json": {
    "VCAP_SERVICES": {
      "xsuaa": [
        {
          "label": "xsuaa",
          "plan": "application",
          "name": "nonosgi-auth-xsuaa",
          "credentials": {
            "clientid": "sb-nonosgi-auth!t12345",
            "clientsecret": "...",
            "url": "https://subdomain.authentication.eu11.hana.ondemand.com",
            "xsappname": "nonosgi-auth!t12345",
            "uaadomain": "authentication.eu11.hana.ondemand.com"
          }
        }
      ]
    }
  }
}
```

**Extract XSUAA client ID from running app:**
```bash
cf env <APP_NAME> | grep '"clientid"'
# Or:
cf curl "/v3/apps/$APP_GUID/env" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); \
  svcs=d['system_env_json']['VCAP_SERVICES']; \
  print(svcs['xsuaa'][0]['credentials']['clientid'])"
```

---

## Services API

### List service instances in space

```bash
SPACE_GUID=$(cf space <SPACE> --guid)
cf curl "/v3/service_instances?space_guids=$SPACE_GUID"
```

```json
{
  "resources": [
    {
      "guid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "name": "nonosgi-auth-xsuaa",
      "type": "managed",
      "last_operation": { "type": "create", "state": "succeeded" },
      "relationships": {
        "service_plan": { "data": { "guid": "<plan-guid>" } },
        "space": { "data": { "guid": "<space-guid>" } }
      }
    }
  ]
}
```

---

### Service credential bindings (bound apps)

```bash
INSTANCE_GUID=<service-instance-guid>
cf curl "/v3/service_credential_bindings?service_instance_guids=$INSTANCE_GUID"
```

```json
{
  "resources": [
    {
      "guid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "type": "app",
      "name": null,
      "relationships": {
        "app": { "data": { "guid": "<app-guid>" } },
        "service_instance": { "data": { "guid": "<instance-guid>" } }
      },
      "last_operation": { "type": "create", "state": "succeeded" }
    }
  ]
}
```

---

## Routes API

### List routes in space

```bash
SPACE_GUID=$(cf space <SPACE> --guid)
cf curl "/v3/routes?space_guids=$SPACE_GUID"
```

```json
{
  "resources": [
    {
      "guid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "host": "nonosgi-auth",
      "path": "",
      "protocol": "http",
      "url": "nonosgi-auth.cfapps.eu11.hana.ondemand.com",
      "relationships": {
        "space": { "data": { "guid": "<space-guid>" } },
        "domain": { "data": { "guid": "<domain-guid>" } }
      }
    }
  ]
}
```

**jq pattern:**
```bash
cf curl "/v3/routes?space_guids=$SPACE_GUID" | \
  jq '.resources[] | {host, url}'
```

---

## Processes API

### List processes for an app

```bash
APP_GUID=$(cf app <APP> --guid)
cf curl "/v3/apps/$APP_GUID/processes"
```

```json
{
  "resources": [
    {
      "guid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "type": "web",
      "instances": 1,
      "memory_in_mb": 1024,
      "disk_in_mb": 1024,
      "health_check": {
        "type": "port",
        "data": { "timeout": null, "invocation_timeout": null }
      }
    }
  ]
}
```

---

## MTA Operations (via cf curl)

The MultiApps plugin stores operation metadata. To inspect raw MTA operation status:

```bash
# The cf mta-ops command is easier, but for scripting:
# MTA operations are stored in the CF API under custom endpoints
# exposed by the MultiApps controller (deploy-service app)
# Use: cf mta-ops (built-in plugin command — no JSON flag but parseable text)
cf mta-ops
# Output:
# id                                     type    state    ...
# d0fc7581-31ba-11f1-b14b-eeee0a93759f  DEPLOY  RUNNING  ...
```

**Extract operation ID for abort:**
```bash
cf mta-ops | awk 'NR>2 {print $1}' | head -1
# Then: cf deploy -i <OP_ID> -a abort
```

---

## Script Template: Deploy MTA and Verify

```bash
#!/bin/bash
set -e

APP_NAME="nonosgi-auth"
SPACE="dev"
ORG="my-org"
API="https://api.cf.eu11.hana.ondemand.com"

# 1. Verify session
cf target | grep -q "$API" || { echo "Not logged in to correct endpoint"; exit 1; }
cf target -o "$ORG" -s "$SPACE"

# 2. Build
mvn clean package -DskipTests

# 3. Deploy
cf deploy . -f

# 4. Verify apps started
echo "=== App Status ==="
cf apps | grep "$APP_NAME"

# 5. Verify services bound
echo "=== Services ==="
cf services

# 6. Check logs for errors
echo "=== Recent Logs ==="
cf logs "$APP_NAME" --recent | tail -50

# 7. Verify route
echo "=== Routes ==="
cf routes | grep "$APP_NAME"
```

---

## Common API Patterns

```bash
# Check org quota (to diagnose SUBSCRIPTION_QUOTA issue)
ORG_GUID=$(cf org <ORG> --guid)
cf curl "/v3/organizations/$ORG_GUID/usage_summary"

# List domains for current org
cf curl "/v3/domains"

# Get build for latest package
APP_GUID=$(cf app <APP> --guid)
cf curl "/v3/packages?app_guids=$APP_GUID&order_by=-created_at" | \
  jq '.resources[0] | {guid, state, type}'

# List buildpacks
cf curl "/v3/buildpacks" | jq '.resources[] | {name, position, enabled, locked}'
```
