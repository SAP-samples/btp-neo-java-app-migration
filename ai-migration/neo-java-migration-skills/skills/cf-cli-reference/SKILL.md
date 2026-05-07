---
name: cf-cli-reference
description: Reference skill for all CF CLI operations — session management, full command taxonomy (apps, services, routes, orgs/spaces, admin, quotas), MultiApps plugin (cf deploy/mta), JSON output patterns, and scope boundaries. Intended to be read by other skills before issuing CF CLI commands.
disable-model-invocation: false
allowed-tools: Read, Bash
---

# CF CLI Reference

Complete reference for the Cloud Foundry CLI (`cf`) — session management, full command taxonomy, MultiApps plugin commands, JSON output patterns, and scope boundaries. Use this skill as a toolset when other skills need to issue CF CLI commands.

## CLI Version and Architecture

```bash
cf --version
# Example: cf version 8.x.x+xxxxxxx.yyyy-mm-dd

cf plugins
# List installed plugins — MultiApps plugin required for cf deploy
```

The CF CLI operates against the **CF API** — it manages apps, services, routes, spaces, and orgs within a Cloud Foundry organization. It does NOT manage the BTP account layer (subaccounts, entitlements, trust, global security) — those require the `btp` CLI or BTP REST APIs.

Two distinct plugin ecosystems:
- **Built-in CF CLI** — core app and service management
- **MultiApps CLI plugin** — MTA deployment (`cf deploy`, `cf mta`, etc.) — REQUIRED for SAP BTP MTA deployments

---

## Session Management

### Login

```bash
# Standard login — prompts for email/password
cf login -a <API_ENDPOINT> -u <email> -p <password> -o <org> -s <space>

# SSO login (opens browser for SAP ID Service)
cf login -a <API_ENDPOINT> --sso

# SSO with one-time passcode (copy from browser)
cf login -a <API_ENDPOINT> --sso-passcode <PASSCODE>

# Login with custom IdP origin
cf login -a <API_ENDPOINT> -u <email> --origin <idp-origin>

# Skip SSL validation (non-production environments only)
cf login -a <API_ENDPOINT> --skip-ssl-validation
```

**SAP BTP CF API endpoints by region:**
| Region | API Endpoint |
|--------|-------------|
| EU10 (Frankfurt) | `https://api.cf.eu10.hana.ondemand.com` |
| EU11 (Frankfurt) | `https://api.cf.eu11.hana.ondemand.com` |
| EU20 (Netherlands) | `https://api.cf.eu20.hana.ondemand.com` |
| US10 (Virginia) | `https://api.cf.us10.hana.ondemand.com` |
| US20 (WA) | `https://api.cf.us20.hana.ondemand.com` |
| US21 (Virginia) | `https://api.cf.us21.hana.ondemand.com` |
| AP10 (Sydney) | `https://api.cf.ap10.hana.ondemand.com` |
| AP11 (Singapore) | `https://api.cf.ap11.hana.ondemand.com` |
| AP12 (Seoul) | `https://api.cf.ap12.hana.ondemand.com` |
| AP20 (Sydney) | `https://api.cf.ap20.hana.ondemand.com` |
| AP21 (Singapore) | `https://api.cf.ap21.hana.ondemand.com` |
| JP10 (Tokyo) | `https://api.cf.jp10.hana.ondemand.com` |
| BR10 (São Paulo) | `https://api.cf.br10.hana.ondemand.com` |
| CA10 (Canada) | `https://api.cf.ca10.hana.ondemand.com` |
| CH20 (Switzerland) | `https://api.cf.ch20.hana.ondemand.com` |
| IN30 (India) | `https://api.cf.in30.hana.ondemand.com` |

### Non-Interactive Auth (for scripts)

```bash
# Auth with client credentials (service account)
cf auth <CLIENT_ID> <CLIENT_SECRET> --client-credentials

# Target org and space after auth
cf target -o <ORG> -s <SPACE>
```

### Targeting

```bash
# Show current target
cf target

# Set org and space
cf target -o <ORG> -s <SPACE>

# Set API endpoint (without login)
cf api https://api.cf.eu11.hana.ondemand.com

# Show current API endpoint
cf api

# Logout
cf logout
```

### Session Health Check

```bash
# Verify session is active and targeted correctly
cf target
# Shows: api endpoint, user, org, space — fails if session expired

# Get OAuth token (useful for API calls)
cf oauth-token
```

### Environment Variables

| Variable | Purpose |
|----------|---------|
| `CF_HOME` | Override config directory (default: `~/.cf/`) |
| `CF_TRACE=true` | Enable diagnostic HTTP tracing |
| `CF_COLOR=false` | Disable output colors |
| `CF_DIAL_TIMEOUT` | Timeout for TCP connections |
| `CF_STAGING_TIMEOUT` | Max time for staging (default: 15 min) |
| `CF_STARTUP_TIMEOUT` | Max time for app start (default: 5 min) |
| `CF_PLUGIN_HOME` | Override plugin directory |

### Gotchas

- **Session expires** — `cf target` fails with "Not logged in." Run `cf login` again.
- **`cf login` vs `cf auth`** — `cf login` is interactive (sets org/space); `cf auth --client-credentials` is for scripts (still requires `cf target` separately).
- **Two spaces to manage** — CF org/space context is separate from BTP subaccount context. `cf target` does not know about your BTP subaccount.
- **`--format json` does NOT exist in CF CLI** — CF CLI has no global JSON output flag. Use `cf curl` for raw API JSON, or parse text output with `grep`/`awk`.
- **MultiApps plugin required** — `cf deploy` is NOT a built-in command; it requires the MultiApps CLI plugin installed separately.
- **`cf push` vs `cf deploy`** — `cf push` deploys a single app from a manifest.yml; `cf deploy` deploys an MTA (Multi-Target Application) from mtad.yaml using the MultiApps plugin.
- **Stuck MTA operations** — check with `cf mta-ops`, abort with `cf deploy -i <operation-id> -a abort`.
- **`cf restage` vs `cf restart`** — `cf restart` stops and starts without re-staging; `cf restage` re-runs the buildpack (needed after changing env vars that affect staging).
- **WAR filename = Tomcat context path** — The SAP Java buildpack deploys `ROOT.war` at `/`, `myapp.war` at `/myapp`. Always use `ROOT.war`.
- **`cf logs --recent`** — only shows the last ~1000 lines from the Loggregator buffer; for full logs use `cf logs <app> --recent` or stream with `cf logs <app>`.

---

## Apps Group

### Core App Lifecycle

```bash
# List all apps in targeted space
cf apps
# Aliases: cf a

# Show details for one app
cf app <APP_NAME>
cf app <APP_NAME> --guid    # Print only the GUID (suppresses other output)

# Push app (single app from manifest.yml or flags)
cf push <APP_NAME> [flags]
  -b <BUILDPACK>            # Buildpack name or Git URL
  -c <COMMAND>              # Startup command
  -f <MANIFEST_PATH>        # Path to manifest file (default: manifest.yml)
  -i <INSTANCES>            # Number of instances
  -k <DISK>                 # Disk quota (256M, 1G, etc.)
  -m <MEMORY>               # Memory limit
  -o <DOCKER_IMAGE>         # Docker image
  -p <PATH>                 # App directory or zip file
  -s <STACK>                # Stack name
  -t <TIMEOUT>              # Seconds to wait for start (default: 60)
  -u <HEALTH_CHECK_TYPE>    # http | port | process
  --endpoint <PATH>         # HTTP health check endpoint
  --no-manifest             # Ignore manifest.yml
  --no-route                # Do not map route
  --no-start                # Do not stage and start
  --no-wait                 # Return after first instance healthy
  --random-route            # Create a random route
  --strategy rolling        # Rolling deployment strategy
  --task                    # Push as task-only app
  --var <KEY=VALUE>         # Variable substitution
  --vars-file <FILE>        # Variable substitution from file

# Scale app
cf scale <APP_NAME>
  -i <INSTANCES>            # Number of instances
  -k <DISK>                 # Disk limit
  -m <MEMORY>               # Memory limit
  --process <PROCESS>       # Process type (default: web)
  -f                        # Force restart without prompt

# Start / Stop / Restart / Restage
cf start <APP_NAME>
cf stop <APP_NAME>
cf restart <APP_NAME>
  --strategy rolling        # Rolling restart
cf restage <APP_NAME>       # Re-run buildpack (needed after staging env var changes)

# Delete app
cf delete <APP_NAME>
  -f                        # Force without prompt
  -r                        # Delete mapped routes too

# Rename
cf rename <APP_NAME> <NEW_NAME>

# Restart a single instance
cf restart-app-instance <APP_NAME> <INSTANCE_INDEX>
  --process <PROCESS>

# Cancel a rolling deployment
cf cancel-deployment <APP_NAME>
```

### Logs and Diagnostics

```bash
# Tail logs (stream)
cf logs <APP_NAME>

# Recent logs (last ~1000 lines)
cf logs <APP_NAME> --recent

# Show recent events (crashes, scaling, etc.)
cf events <APP_NAME>

# Environment variables (running + staging)
cf env <APP_NAME>

# Set environment variable (triggers restage if staging var)
cf set-env <APP_NAME> <VAR_NAME> <VALUE>

# Unset environment variable
cf unset-env <APP_NAME> <VAR_NAME>
```

### Manifest and Health Checks

```bash
# Generate a manifest.yml from a running app
cf create-app-manifest <APP_NAME>
  -p <PATH>                 # Output path (default: ./<app_name>_manifest.yml)

# Get health check type
cf get-health-check <APP_NAME>
  --process <PROCESS>

# Set health check type
cf set-health-check <APP_NAME> <TYPE>   # http | port | process
  --endpoint <PATH>         # HTTP health check path (requires http type)
  --invocation-timeout <N>  # Seconds for health check
  --process <PROCESS>
```

### SSH

```bash
# SSH into app instance
cf ssh <APP_NAME>
  -i <INDEX>                # Instance index (default: 0)
  -c <COMMAND>              # Command to run
  -L <LOCAL:REMOTE>         # Local port forwarding
  --process <PROCESS>       # Process type (default: web)
  -t                        # Request pseudo-tty
  -T                        # Disable pseudo-tty
  --force-pseudo-tty
  -k                        # Skip host key validation
  -N                        # Do not execute remote command (for port forwarding only)

# Get one-time SSH passcode
cf ssh-code

# Enable/disable SSH for an app
cf enable-ssh <APP_NAME>
cf disable-ssh <APP_NAME>

# Check if SSH is enabled
cf ssh-enabled <APP_NAME>
```

### Tasks and Packages

```bash
# Run a one-off task
cf run-task <APP_NAME> --command "<CMD>"
  --name <TASK_NAME>
  -m <MEMORY>
  -k <DISK>
  --process <PROCESS>

# List tasks
cf tasks <APP_NAME>

# Terminate a task
cf terminate-task <APP_NAME> <TASK_ID>

# List packages
cf packages <APP_NAME>

# Create package from app source
cf create-package <APP_NAME>
  -p <PATH>                 # Path to app zip or directory

# List droplets (staged app artifacts)
cf droplets <APP_NAME>

# Set the active droplet for an app
cf set-droplet <APP_NAME> <DROPLET_GUID>

# Download a droplet
cf download-droplet <APP_NAME>
  --path <PATH>

# List available stacks
cf stacks
cf stack <STACK_NAME>
```

### Revisions (Experimental)

```bash
cf revisions <APP_NAME>
cf revision <APP_NAME> <REVISION_NUMBER>
cf rollback <APP_NAME> --version <REVISION_NUMBER>
```

---

## Services Group

### Service Instances

```bash
# List available services in marketplace
cf marketplace
cf marketplace -e <SERVICE_OFFERING>   # Filter by offering
cf marketplace -s <SPACE>              # (v6/v7 only) filter by space

# List service instances in current space
cf services
# Alias: cf s

# Show details for one service instance
cf service <SERVICE_INSTANCE_NAME>

# Create service instance
cf create-service <OFFERING> <PLAN> <INSTANCE_NAME>
  -b <BROKER>               # Specific broker (when ambiguous)
  -c '{"key":"val"}'        # JSON configuration parameters
  -t "tag1,tag2"            # User-supplied tags
  -w                        # Wait for async operation to complete

# Update service instance
cf update-service <INSTANCE_NAME>
  -c '{"key":"val"}'        # New configuration
  -p <NEW_PLAN>             # Change plan
  -t "tag1,tag2"            # New tags
  -w                        # Wait for async operation

# Upgrade service instance to latest plan version
cf upgrade-service <INSTANCE_NAME>
  -f                        # Force without confirmation

# Delete service instance
cf delete-service <INSTANCE_NAME>
  -f                        # Force without prompt
  -w                        # Wait for async deletion

# Rename service instance
cf rename-service <INSTANCE_NAME> <NEW_NAME>
```

### Service Keys (Credentials)

```bash
# List service keys
cf service-keys <INSTANCE_NAME>

# Show service key credentials
cf service-key <INSTANCE_NAME> <KEY_NAME>
  --guid                    # Print only GUID

# Create service key
cf create-service-key <INSTANCE_NAME> <KEY_NAME>
  -c '{"key":"val"}'        # Configuration parameters

# Delete service key
cf delete-service-key <INSTANCE_NAME> <KEY_NAME>
  -f                        # Force without prompt
  -w                        # Wait
```

### Service Binding

```bash
# Bind service to app
cf bind-service <APP_NAME> <INSTANCE_NAME>
  -c '{"key":"val"}'        # Binding parameters
  --binding-name <NAME>     # Custom binding name

# Unbind service from app
cf unbind-service <APP_NAME> <INSTANCE_NAME>

# Bind route to service
cf bind-route-service <DOMAIN> <INSTANCE_NAME>
  -f                        # Force without prompt
  -n <HOSTNAME>             # Hostname
  --path <PATH>

# Unbind route from service
cf unbind-route-service <DOMAIN> <INSTANCE_NAME>
  -f
  -n <HOSTNAME>
  --path <PATH>

# Share service instance to another space
cf share-service <INSTANCE_NAME>
  -s <SPACE>
  -o <ORG>                  # If in different org

# Unshare
cf unshare-service <INSTANCE_NAME>
  -s <SPACE>
  -o <ORG>
  -f                        # Force
```

### User-Provided Services

```bash
# Create user-provided service instance
cf create-user-provided-service <NAME>
  -p "param1,param2"        # Prompt for named params (interactive)
  -p '{"key":"val"}'        # JSON credentials
  -l <SYSLOG_URL>           # Syslog drain URL
  -r <ROUTE_SERVICE_URL>    # Route service URL
  -t "tag1,tag2"            # Tags
# Alias: cf cups

# Update user-provided service instance
cf update-user-provided-service <NAME>
  -p '{"key":"val"}'
  -l <SYSLOG_URL>
  -r <ROUTE_SERVICE_URL>
  -t "tag1,tag2"
# Alias: cf uups
```

---

## Routes and Domains Group

```bash
# List domains
cf domains

# Create private domain (org-scoped)
cf create-private-domain <ORG> <DOMAIN>

# Delete private domain
cf delete-private-domain <DOMAIN>
  -f

# Create shared domain (platform-wide)
cf create-shared-domain <DOMAIN>
  --internal                # Internal domain (no external access)
  --router-group <GROUP>    # For TCP routing

# Delete shared domain
cf delete-shared-domain <DOMAIN>
  -f

# List router groups
cf router-groups

# List all routes in space
cf routes
  --orglevel                # Show routes for all spaces in org

# Show route details
cf route <HOSTNAME> <DOMAIN>
  --path <PATH>

# Check if route exists
cf check-route <HOSTNAME> <DOMAIN>
  --path <PATH>

# Create route (without mapping to app)
cf create-route <DOMAIN>
  -n <HOSTNAME>
  --path <PATH>
  --port <PORT>             # TCP routes

# Map route to app
cf map-route <APP_NAME> <DOMAIN>
  -n <HOSTNAME>             # Hostname (required for shared domains)
  --path <PATH>             # URL path
  --port <PORT>             # TCP port
  --destination-protocol <http1|http2>

# Unmap route from app
cf unmap-route <APP_NAME> <DOMAIN>
  -n <HOSTNAME>
  --path <PATH>
  --port <PORT>

# Delete a route
cf delete-route <DOMAIN>
  -n <HOSTNAME>
  --path <PATH>
  --port <PORT>
  -f

# Delete routes not mapped to any app
cf delete-orphaned-routes
  -f
```

---

## Orgs and Spaces Group

```bash
# List orgs
cf orgs

# Show org details
cf org <ORG>
  --guid                    # Print only GUID

# Create org
cf create-org <ORG>

# Delete org
cf delete-org <ORG>
  -f

# Rename org
cf rename-org <ORG> <NEW_NAME>

# List spaces
cf spaces

# Show space details
cf space <SPACE>
  --guid
  --security-group-rules    # Show associated security group rules

# Create space
cf create-space <SPACE>
  -o <ORG>
  --quota <SPACE_QUOTA>

# Delete space
cf delete-space <SPACE>
  -o <ORG>
  -f

# Rename space
cf rename-space <SPACE> <NEW_NAME>

# Apply manifest to space
cf apply-manifest
  -f <MANIFEST_PATH>

# SSH access for space
cf allow-space-ssh <SPACE>
cf disallow-space-ssh <SPACE>
cf space-ssh-allowed <SPACE>
```

---

## Network Policies Group

```bash
# List network policies
cf network-policies
  --source <APP>            # Filter by source app

# Add network policy (allow internal traffic between apps)
cf add-network-policy <SOURCE_APP> --destination-app <DEST_APP>
  --protocol tcp|udp        # Default: tcp
  --port <PORT>|<START>-<END>  # Port or range

# Remove network policy
cf remove-network-policy <SOURCE_APP> --destination-app <DEST_APP>
  --protocol tcp|udp
  --port <PORT>
```

---

## Admin Group

### Users and Roles

```bash
# List users in org
cf org-users <ORG>
  -a                        # List all users (not just those with roles)

# Set org-level role
cf set-org-role <USER> <ORG> <ROLE>
  --client                  # USERNAME is a client (service account)
  --origin <IDP_ORIGIN>     # Custom IdP origin
# ROLE: OrgManager | OrgAuditor | BillingManager

# Unset org-level role
cf unset-org-role <USER> <ORG> <ROLE>
  --client
  --origin <IDP_ORIGIN>

# List users in space
cf space-users <ORG> <SPACE>

# Set space-level role
cf set-space-role <USER> <ORG> <SPACE> <ROLE>
  --client
  --origin <IDP_ORIGIN>
# ROLE: SpaceManager | SpaceDeveloper | SpaceAuditor | SpaceSupporter

# Unset space-level role
cf unset-space-role <USER> <ORG> <SPACE> <ROLE>
  --client
  --origin <IDP_ORIGIN>

# Create user (platform user)
cf create-user <USERNAME> [<PASSWORD>]
  --origin <IDP_ORIGIN>     # Specify IdP

# Delete user
cf delete-user <USERNAME>
  -f
```

### Quotas

```bash
# Org quotas
cf org-quotas                                    # List all org quotas
# Alias: cf quotas

cf org-quota <QUOTA_NAME>                        # Show quota details
cf create-org-quota <QUOTA_NAME>
  -m <TOTAL_MEMORY>         # e.g. 10G
  -i <INSTANCE_MEMORY>      # Max memory per app instance (-1 for unlimited)
  --reserved-route-ports <N>
  -r <ROUTES>               # Total routes
  -s <SERVICES>             # Total service instances
  --allow-paid-service-plans
  -a <APP_INSTANCES>        # Total app instances (-1 for unlimited)
  --log-rate-limit <N>      # Bytes per second (-1 for unlimited)

cf update-org-quota <QUOTA_NAME> [same flags as create]
cf set-org-quota <ORG> <QUOTA_NAME>
cf delete-org-quota <QUOTA_NAME>
  -f

# Space quotas
cf space-quotas                                  # List space quotas in current org
cf space-quota <QUOTA_NAME>
cf create-space-quota <QUOTA_NAME> [same flags as create-org-quota]
cf update-space-quota <QUOTA_NAME> [same flags]
cf set-space-quota <SPACE> <QUOTA_NAME>
cf unset-space-quota <SPACE> <QUOTA_NAME>
cf delete-space-quota <QUOTA_NAME>
  -f
```

### Service Brokers

```bash
cf service-brokers
cf create-service-broker <NAME> <USER> <PASSWORD> <URL>
  --space-scoped             # Visible only in current space
cf update-service-broker <NAME> <USER> <PASSWORD> <URL>
cf delete-service-broker <NAME>
  -f
cf rename-service-broker <NAME> <NEW_NAME>

# Control which plans are visible
cf service-access
  -b <BROKER>
  -e <SERVICE_OFFERING>
  -o <ORG>
cf enable-service-access <SERVICE_OFFERING>
  -b <BROKER>
  -p <PLAN>
  -o <ORG>
cf disable-service-access <SERVICE_OFFERING>
  -b <BROKER>
  -p <PLAN>
  -o <ORG>

# Purge stale service metadata
cf purge-service-offering <SERVICE_OFFERING>
  -b <BROKER>
  -f
cf purge-service-instance <INSTANCE_NAME>
  -f
```

### Buildpacks

```bash
cf buildpacks
cf create-buildpack <NAME> <PATH_OR_URL> <POSITION>
  --disable                 # Disable immediately after creation
cf update-buildpack <NAME>
  -p <PATH_OR_URL>
  -i <POSITION>
  --enable | --disable
  --lock | --unlock
  -s <STACK>
cf delete-buildpack <NAME>
  -f
  -s <STACK>
```

### Security Groups

```bash
cf security-groups
cf security-group <GROUP_NAME>
cf create-security-group <GROUP_NAME> <RULES_JSON_FILE>
cf update-security-group <GROUP_NAME> <RULES_JSON_FILE>
cf delete-security-group <GROUP_NAME>
  -f

# Bind to lifecycle stage
cf bind-staging-security-group <GROUP_NAME>
cf unbind-staging-security-group <GROUP_NAME>
cf staging-security-groups

cf bind-running-security-group <GROUP_NAME>
cf unbind-running-security-group <GROUP_NAME>
cf running-security-groups

# Space-scoped binding
cf bind-security-group <GROUP_NAME> <ORG> [<SPACE>]
  --lifecycle staging|running
cf unbind-security-group <GROUP_NAME> <ORG> <SPACE>
  --lifecycle staging|running
  -f
```

### Feature Flags

```bash
cf feature-flags
cf feature-flag <FLAG_NAME>
cf enable-feature-flag <FLAG_NAME>
cf disable-feature-flag <FLAG_NAME>
```

### Isolation Segments

```bash
cf isolation-segments
cf create-isolation-segment <SEGMENT_NAME>
cf delete-isolation-segment <SEGMENT_NAME>
  -f
cf enable-org-isolation <ORG> <SEGMENT_NAME>
cf disable-org-isolation <ORG> <SEGMENT_NAME>
cf set-org-default-isolation-segment <ORG> <SEGMENT_NAME>
cf reset-org-default-isolation-segment <ORG>
cf set-space-isolation-segment <SPACE> <SEGMENT_NAME>
cf reset-space-isolation-segment <SPACE>
cf org-isolation-segments <ORG>
```

### Environment Variable Groups

```bash
# Staging env vars (available during buildpack run)
cf staging-environment-variable-group
cf set-staging-environment-variable-group '{"KEY":"value"}'
# Alias: cf ssevg

# Running env vars (available at runtime)
cf running-environment-variable-group
cf set-running-environment-variable-group '{"KEY":"value"}'
# Alias: cf srevg
```

---

## MultiApps Plugin (MTA Deployment)

> **Installation required:** `cf install-plugin multiapps` or download from [GitHub releases](https://github.com/cloudfoundry/multiapps-cli-plugin/releases)

### Core MTA Commands

```bash
# Deploy MTA from current directory (reads mtad.yaml)
cf deploy [MTA_ARCHIVE_OR_DIRECTORY] [flags]
  -e <EXT_DESCRIPTOR>       # Extension descriptor file(s), comma-separated
  -f                        # Force deploy without confirmation prompts
  -m <MODULE>               # Deploy only specific module(s)
  -r <RESOURCE>             # Deploy only specific resource(s)
  --retries <N>             # Number of retries on network errors (default: 3)
  --skip-testing-phase      # Skip testing phase
  --no-start                # Do not start apps after deployment
  --abort-on-error          # Abort on first error (default: rollback)
  -t <TIMEOUT>              # Overall deployment timeout in seconds
  --version-rule LATEST|SAME_HIGHER|ALL  # MTA version check rule
  --delete-services         # Delete services removed from mtad.yaml
  --delete-service-keys     # Delete service keys removed from mtad.yaml
  --keep-files              # Keep uploaded files on server
  --no-confirm              # Skip interactive confirmations
  -i <OPERATION_ID>         # Resume/act on existing MTA operation
  -a abort|retry|resume|monitor  # Action for existing operation
  --namespace <NS>          # Apply namespace prefix to MTA resources
  --use-namespaces          # Enable namespace support
  --strategy blue-green     # Blue-green deployment
  --skip-idle-start         # (blue-green) Skip starting idle app

# Undeploy (delete) an MTA
cf undeploy <MTA_ID>
  -f                        # Force without confirmation
  --delete-services         # Also delete service instances
  --delete-service-keys     # Also delete service keys
  --delete-service-brokers  # Also delete service brokers
  -t <TIMEOUT>

# Blue-green deploy
cf bg-deploy [MTA_ARCHIVE_OR_DIRECTORY] [flags]
  # Same flags as cf deploy, plus:
  --skip-idle-start         # Don't start idle apps during deployment

# List all MTAs in current space
cf mtas
  --namespace <NS>          # Filter by namespace

# Show MTA health and status
cf mta <MTA_ID>
  --namespace <NS>

# List active MTA operations
cf mta-ops
  -m <MTA_ID>               # Filter by MTA
  --namespace <NS>

# Download operation logs
cf download-mta-op-logs <OPERATION_ID>
# Alias: cf dmol
  -d <DIRECTORY>            # Output directory

# Purge stale configuration
cf purge-mta-config
  -f
```

### Typical MTA Deployment Workflow

```bash
# 1. Build MTA archive
mbt build
# Produces: mta_archives/<mta-id>_<version>.mtar

# 2. Verify archive contents
unzip -l mta_archives/*.mtar

# 3. Deploy
cf deploy mta_archives/<mta-id>_<version>.mtar -f

# --- OR: deploy directly from directory ---
cf deploy . -f
# Reads mtad.yaml in current directory, builds and deploys in one step

# 4. Monitor
cf apps
cf services
cf logs <APP_NAME> --recent

# 5. Abort stuck operation
cf mta-ops                  # Find operation ID
cf deploy -i <OP_ID> -a abort
```

---

## Advanced / Utility Commands

```bash
# Make raw API calls (returns JSON)
cf curl <PATH>
  -X <METHOD>               # HTTP method (default: GET)
  -d <DATA>                 # Request body
  -H "<HEADER>"             # HTTP header
  -i                        # Include response headers in output
  --output <FILE>           # Write body to file
# Example:
cf curl "/v3/apps" -X GET
cf curl "/v3/apps/<guid>/environment_variables" -X PATCH \
  -d '{"var":{"MY_VAR":"value"}}'

# Set default values
cf config
  --color true|false
  --locale <LOCALE>
  --trace true|false|<FILE>

# Get current OAuth token (for use in API calls)
cf oauth-token
# Example use:
curl -H "Authorization: $(cf oauth-token)" https://api.cf.eu11.hana.ondemand.com/v3/apps

# Plugin management
cf plugins                  # List installed plugins
cf install-plugin <NAME_OR_URL>
  -r <REPO>                 # Install from plugin repo
  -f                        # Force reinstall
cf uninstall-plugin <NAME>
cf add-plugin-repo <NAME> <URL>
cf remove-plugin-repo <NAME>
cf list-plugin-repos
cf repo-plugins
  -r <REPO>
```

---

## JSON Output Patterns

CF CLI has no `--format json` flag. Use `cf curl` for JSON output:

```bash
# List apps as JSON
cf curl "/v3/apps?space_guids=$(cf space <SPACE_NAME> --guid)"

# Get app GUID by name
APP_GUID=$(cf app <APP_NAME> --guid)

# Get app environment
cf curl "/v3/apps/$APP_GUID/environment_variables"

# Get app state
cf curl "/v3/apps/$APP_GUID/processes" | python3 -m json.tool

# Get service instance GUID
cf curl "/v3/service_instances?names=<INSTANCE_NAME>" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d['resources'][0]['guid'])"

# Get space GUID
SPACE_GUID=$(cf space <SPACE_NAME> --guid)

# List routes as JSON
cf curl "/v3/routes?space_guids=$SPACE_GUID"

# List service bindings
cf curl "/v3/service_credential_bindings?app_guids=$APP_GUID"

# Check app instances status
cf curl "/v3/apps/$APP_GUID/processes/web/stats"
```

See [references/curl-api-patterns.md](references/curl-api-patterns.md) for full CF API v3 patterns.

---

## Common Patterns for Neo → CF Migration

### After Deploying an MTA

```bash
# Verify all modules started
cf apps

# Verify services created and bound
cf services

# Check app logs for startup errors
cf logs <APP_NAME> --recent

# Verify routes
cf routes | grep <APP_NAME>

# Inspect environment (includes VCAP_SERVICES bindings)
cf env <APP_NAME>

# Tail logs live during first test
cf logs <APP_NAME>
```

### Diagnosing a 404 After Deployment

```bash
# Check app is running
cf app <APP_NAME>

# Check routes are correctly mapped
cf routes | grep <APP_NAME>

# Check logs for startup errors
cf logs <APP_NAME> --recent

# Confirm WAR filename (must be ROOT.war for root context path)
# In mtad.yaml: path: target/ROOT.war
# In pom.xml: <warName>ROOT</warName> in maven-war-plugin config
```

### Diagnosing a Staging Failure

```bash
# Logs during push/deploy
cf logs <APP_NAME> --recent

# Check buildpack used
cf app <APP_NAME>

# Force re-stage after env var change
cf restage <APP_NAME>
```

### Diagnosing Quota / Memory Errors

```bash
# Check space quota
cf space <SPACE>
cf space-quotas

# Check org quota
cf org <ORG>
cf org-quotas

# Scale down if over quota
cf scale <APP_NAME> -m 512M -f

# Note: CF space quotas cannot override org-level route limits.
# If org quota is SUBSCRIPTION_QUOTA (0 routes, 0 memory):
# Fix via BTP Cockpit → Entitlements → assign CF Runtime memory
# See btp-cli-reference skill for btp assign accounts/entitlement command.
```

---

## Common Issues

| Symptom | Cause | Solution |
|---------|-------|---------|
| `Not logged in` | Session expired | `cf login` |
| `No org targeted` | Missing `cf target -o/-s` | `cf target -o <ORG> -s <SPACE>` |
| `Command 'deploy' not found` | MultiApps plugin not installed | `cf install-plugin multiapps` |
| `App failed to start` | Buildpack error or insufficient memory | `cf logs <app> --recent` to diagnose |
| `Routes quota exceeded` | Org quota has 0 routes | Assign CF Runtime in BTP Cockpit entitlements — see btp-cli-reference |
| `Cannot bind service` | Service in different space | Service must be in same space or shared |
| `Staging error: Cannot access defaults field of Properties` | maven-war-plugin 2.2 on Java 25 | Pin `maven-war-plugin` to `3.4.0` in pom.xml |
| 404 on all routes after deployment | WAR deployed with non-root context path | Rename to `ROOT.war` — set `<warName>ROOT</warName>` in maven-war-plugin |
| `MultipleServiceBindingsException` at runtime | Same service type bound twice | Ensure only one binding per service type |
| `cf mta-ops` shows stuck operation | Previous deploy didn't clean up | `cf deploy -i <op-id> -a abort` |
| App restarts repeatedly | Memory limit too low | `cf scale <app> -m 1024M -f` |
| `VCAP_SERVICES is empty` | Service not bound | `cf bind-service <app> <service>`, then `cf restage <app>` |

---

## Scope Boundary: What CF CLI Cannot Do

| Task | Reason | Alternative |
|------|--------|-------------|
| Manage BTP subaccounts, entitlements, trust | Account layer, not CF runtime | `btp` CLI |
| Create CF Runtime quota / assign memory | BTP Cockpit entitlement assignment | `btp assign accounts/entitlement` |
| Manage XSUAA roles and role collections | BTP security layer | `btp` CLI |
| Destination service management | No CF CLI support | Destination Service REST API |
| Export JSON from most commands | No `--format json` flag | `cf curl /v3/...` |
| Non-interactive auth with client secret (login) | `cf login` is interactive | `cf auth <id> <secret> --client-credentials` |
| Deploy from Neo `neo-app.xml` | Different descriptor format | Convert to `mtad.yaml` first |
| Access Neo platform APIs | Neo is a different platform | `neo.sh` CLI or Neo REST APIs |

---

## See Also

- [btp-cli-reference/SKILL.md](../btp-cli-reference/SKILL.md) — BTP account layer: entitlements, trust, role collections, BTP CLI
- [mta-descriptor/SKILL.md](../mta-descriptor/SKILL.md) — mtad.yaml structure and service configuration
- [authentication-xsuaa/SKILL.md](../authentication-xsuaa/SKILL.md) — XSUAA deployment and role collection setup

## References

- [CF CLI v8 Reference](https://cli.cloudfoundry.org/en-US/v8/)
- [CF CLI GitHub](https://github.com/cloudfoundry/cli)
- [MultiApps CLI Plugin](https://github.com/cloudfoundry/multiapps-cli-plugin)
- [CF API v3 Reference](https://v3-apidocs.cloudfoundry.org/)
- [SAP BTP CF Environment Docs](https://help.sap.com/docs/BTP/65de2977205c403bbc107264b8eccf4b/9c7092c7b7ae4d49bc8ae35fdd0e0b18.html)
