---
name: btp-cli-reference
description: Reference skill for all BTP CLI operations — session management, command taxonomy, JSON output patterns, and scope boundaries. Intended to be read by other skills before issuing BTP CLI commands.
disable-model-invocation: false
allowed-tools: Read, Bash
---

# BTP CLI Reference

Complete reference for the SAP BTP CLI (`btp`) — session management, full command taxonomy, JSON output patterns, and scope boundaries. Use this skill as a toolset when other skills need to issue BTP CLI commands.

## CLI Version and Architecture

```bash
btp --version
# Example: SAP BTP command line interface (client v2.x.x)
```

The BTP CLI operates against the **BTP Account API** — it manages the account layer (subaccounts, entitlements, security, service manager). It does NOT manage Cloud Foundry runtime objects (apps, CF service instances, CF spaces) — those require the `cf` CLI.

The CLI uses a **session file** stored in `~/.btp/` that caches the login token. A session is valid for a limited time (roughly 12 hours for SSO, longer for some flows). Commands fail silently with "Authorization failed" or "Unknown session" when expired.

---

## Session Management

### Login Methods

#### SSO Login (browser-based, recommended for interactive use)
```bash
btp login --sso
# Opens browser → SAP ID Service login → returns to CLI
```

#### User/Password Login (non-interactive environments)
```bash
btp login --user <email> --password <password>
# Not recommended for CI/CD — prefer service key + REST APIs
```

#### Custom IDP Login
```bash
btp login --sso --idp <custom-idp-origin>
# Use when your user is managed by a custom IdP (not SAP ID Service)
```

> **IMPORTANT:** There is NO client-credentials login for the BTP CLI. For headless/automated workflows, use the underlying BTP REST APIs directly with OAuth2 service keys instead.

### Targeting

After login, set the default subaccount scope so you don't need `--subaccount` on every command:

```bash
btp target --subaccount <subaccount-id>
# e.g. btp target --subaccount 2f964175-0856-47b2-b77a-469c74df0cca
```

Check current target:
```bash
btp target
# Shows: global account, subaccount, org, space
```

Clear target:
```bash
btp target --reset
```

### JSON Output (always use for scripting)

```bash
# Set JSON as default for all commands
btp set config --format json

# Or per-command
btp --format json list security/role-collection
```

### Session Health Check

Before running commands in a script, verify the session is active:
```bash
btp --format json list accounts/subaccount 2>&1 | grep -q '"subaccounts"' \
  && echo "Session OK" || echo "Session expired — run: btp login"
```

### Gotchas

- **Session expires silently** — commands return "Authorization failed. Unknown session." with no hint. Always check first.
- **`--subaccount` is optional when targeted** — if you ran `btp target --subaccount <id>`, omit it. If targeting a different subaccount, pass it explicitly.
- **`btp` vs `cf`** — BTP CLI manages the account layer; `cf` CLI manages the CF runtime layer. Neither can substitute for the other.
- **Interactive-only login** — `btp login` requires a browser or interactive terminal; cannot be used in CI pipelines with stored credentials. Use REST APIs with service keys instead.
- **Global account vs. subaccount scope** — some commands apply at global account level (entitlements, provisioning), others at subaccount level (security, services). Use `--global-account` or `--subaccount` to specify.
- **`--of-idp` vs `--origin`** — when assigning role collections to users from a custom IdP, use `--of-idp <idp-origin>` (not `--origin`). Example: `btp assign security/role-collection "MyRC" --to-user user@example.com --of-idp sap.default`.
- **`btp update security/trust --set-default` does not exist** — setting the default IdP in a CF subaccount must be done manually in BTP Cockpit → Security → Trust Configuration.
- **JSON output structure** — top-level key names vary by command. Always inspect output with `jq keys` on first use.
- **`--format json` placement** — must come right after `btp`, before the verb: `btp --format json list ...` (not `btp list ... --format json`).

---

## Full Command Taxonomy

See [references/command-quick-reference.md](references/command-quick-reference.md) for a single-table quick reference of all 70+ commands.

### Accounts Group

Commands for managing the global account, subaccounts, directories, and environment instances.

```bash
# Global Account
btp get accounts/global-account
btp update accounts/global-account --display-name <name>

# Subaccounts
btp list accounts/subaccount
btp get accounts/subaccount <id>
btp create accounts/subaccount \
  --display-name <name> \
  --region <region> \
  --subdomain <subdomain>
btp update accounts/subaccount <id> --display-name <name>
btp delete accounts/subaccount <id>
btp move accounts/subaccount <id> --to-directory <dir-id>

# Directories
btp list accounts/directory
btp get accounts/directory <id>
btp create accounts/directory --display-name <name>
btp update accounts/directory <id> --display-name <name>
btp delete accounts/directory <id>
btp enable accounts/directory <id> --for entitlements
btp enable accounts/directory <id> --for authorizations

# Environment Instances (CF Org provisioning)
btp list accounts/environment-instance [--subaccount <id>]
btp get accounts/environment-instance <id> [--subaccount <id>]
btp create accounts/environment-instance \
  --environment cloudfoundry \
  --service cloudfoundry \
  --plan standard \
  --subaccount <id> \
  --display-name <name> \
  --parameters '{"instance_name":"<org-name>"}'
btp update accounts/environment-instance <id> \
  --subaccount <id> \
  --parameters '{"memory":<MB>}'
btp delete accounts/environment-instance <id> [--subaccount <id>]

# Labels
btp list accounts/label [--subaccount <id>]
btp set accounts/label --key <key> --value <val> [--subaccount <id>]
btp delete accounts/label --key <key> [--subaccount <id>]

# Resource providers
btp list accounts/resource-provider
btp get accounts/resource-provider <provider>/<key>
btp create accounts/resource-provider \
  --provider <AWS|AZURE|GCP> \
  --technical-name <key>
btp delete accounts/resource-provider <provider>/<key>

# Custom Properties
btp list accounts/custom-property [--subaccount <id>]
btp set accounts/custom-property --key <key> --value <val>
```

### Entitlements Group

```bash
# List what's available and assigned
btp list accounts/entitlement [--subaccount <id>]
btp list accounts/entitlement --show-technical-info

# Assign entitlement quota to a subaccount
btp assign accounts/entitlement \
  --service-name <service> \
  --plan-name <plan> \
  --subaccount <id> \
  --amount <N>
# For CF Runtime (unlocks memory/routes quota):
btp assign accounts/entitlement \
  --service-name APPLICATION_RUNTIME \
  --plan-name MEMORY \
  --subaccount <id> \
  --amount <MB>

# Assign entitlement to a directory
btp assign accounts/entitlement \
  --service-name <service> \
  --plan-name <plan> \
  --directory <id> \
  --amount <N> \
  --distribute

# Subscriptions (SaaS applications)
btp list accounts/subscription [--subaccount <id>]
btp subscribe accounts/subaccount \
  --app-name <app> \
  --plan <plan> \
  [--subaccount <id>]
btp unsubscribe accounts/subaccount \
  --app-name <app> \
  [--subaccount <id>]
```

### Security Group

Commands for managing roles, role collections, users, and trust.

```bash
# Role Collections
btp list security/role-collection [--subaccount <id>]
btp get security/role-collection "<name>" [--subaccount <id>]
btp create security/role-collection "<name>" \
  --description "<desc>" \
  [--subaccount <id>]
btp update security/role-collection "<name>" \
  --description "<new-desc>" \
  [--subaccount <id>]
btp delete security/role-collection "<name>" [--subaccount <id>]

# Adding roles to a role collection
btp add security/role "<role-name>" \
  --to-role-collection "<rc-name>" \
  --of-app "<appid>" \
  --of-role-template "<template-name>" \
  [--subaccount <id>]
# Note: <appid> is the XSUAA xsappname!application suffix, e.g. "nonosgi-auth!t12345"
# Get it from: btp --format json list security/app | jq '.[] | select(.xsappname=="<appname>") | .appid'

# Removing roles from a role collection
btp remove security/role "<role-name>" \
  --from-role-collection "<rc-name>" \
  --of-app "<appid>" \
  --of-role-template "<template-name>" \
  [--subaccount <id>]

# Users
btp list security/user [--subaccount <id>]
btp get security/user <email> [--of-idp <idp-origin>] [--subaccount <id>]
btp create security/user <email> [--of-idp <idp-origin>] [--subaccount <id>]
btp delete security/user <email> [--of-idp <idp-origin>] [--subaccount <id>]

# Assign/unassign role collections to users
btp assign security/role-collection "<rc-name>" \
  --to-user <email> \
  [--of-idp <idp-origin>] \
  [--subaccount <id>]
btp unassign security/role-collection "<rc-name>" \
  --from-user <email> \
  [--of-idp <idp-origin>] \
  [--subaccount <id>]

# Assign/unassign role collections to user groups
btp assign security/role-collection "<rc-name>" \
  --to-group <group-name> \
  [--of-idp <idp-origin>] \
  [--subaccount <id>]

# XSUAA Applications (inspect app scopes/roles deployed by MTA)
btp list security/app [--subaccount <id>]
btp get security/app <appid> [--subaccount <id>]
# Lists all scopes and role-templates available from a deployed XSUAA app

# Trust Configuration
btp list security/trust [--subaccount <id>]
btp get security/trust <origin> [--subaccount <id>]
btp create security/trust \
  --idp <ias-tenant-id-or-host> \
  [--subaccount <id>]
btp update security/trust <origin> \
  --description "<desc>" \
  [--subaccount <id>]
btp delete security/trust <origin> [--subaccount <id>]
btp migrate security/trust "<origin>" [--subaccount <id>]
# migrate: convert a SAML trust to OIDC (for IAS tenants only)

# Settings
btp list security/setting [--subaccount <id>]
btp get security/setting <key> [--subaccount <id>]
btp set security/setting <key>=<value> [--subaccount <id>]
btp unset security/setting <key> [--subaccount <id>]
```

### Services Group

Commands for managing services via Service Manager.

```bash
# Service Offerings and Plans
btp list services/offering [--subaccount <id>]
btp get services/offering <id> [--subaccount <id>]
btp list services/plan [--subaccount <id>]
btp get services/plan <id> [--subaccount <id>]

# Service Instances
btp list services/instance [--subaccount <id>]
btp get services/instance <id> [--subaccount <id>]
btp create services/instance \
  --offering-name <svc> \
  --plan-name <plan> \
  --display-name <name> \
  [--parameters '{"key":"value"}'] \
  [--subaccount <id>]
btp update services/instance <id> \
  --display-name <new-name> \
  [--parameters '{"key":"value"}'] \
  [--subaccount <id>]
btp delete services/instance <id> [--subaccount <id>]

# Service Bindings (service keys)
btp list services/binding [--subaccount <id>]
btp get services/binding <id> [--subaccount <id>]
btp create services/binding \
  --instance-id <instance-id> \
  --display-name <binding-name> \
  [--parameters '{"key":"value"}'] \
  [--subaccount <id>]
btp delete services/binding <id> [--subaccount <id>]

# Brokers
btp list services/broker [--subaccount <id>]
btp get services/broker <id> [--subaccount <id>]
btp create services/broker \
  --name <name> \
  --url <url> \
  --user <user> \
  --password <password> \
  [--subaccount <id>]
btp update services/broker <id> \
  --url <new-url> \
  [--subaccount <id>]
btp delete services/broker <id> [--subaccount <id>]

# Platforms (service manager platforms)
btp list services/platform [--subaccount <id>]
btp get services/platform <id> [--subaccount <id>]
btp register services/platform \
  --name <name> \
  --type <type> \
  [--subaccount <id>]
btp unregister services/platform <id> [--subaccount <id>]
```

### Connectivity Group (Experimental)

```bash
btp list connectivity/destination [--subaccount <id>]
btp get connectivity/destination <name> [--subaccount <id>]
# Note: these are early commands, do NOT rely on them for production automation
# Use the Destination Service REST API for reliable destination management
```

### Disaster Recovery Group

```bash
btp list accounts/backup-configuration
btp get accounts/backup-configuration <id>
btp update accounts/backup-configuration <id> --enable
```

---

## JSON Output Patterns

See [references/json-output-schemas.md](references/json-output-schemas.md) for full schema examples.

### Common jq Patterns

```bash
# List all role collection names
btp --format json list security/role-collection | jq '.[].name'

# Get appId for a specific XSUAA app by display name
btp --format json list security/app | \
  jq '.[] | select(.xsappname | startswith("nonosgi-auth")) | {xsappname, appid}'

# List all role-templates available for an app
btp --format json get security/app "<appid>" | \
  jq '.roleTemplates[] | {name, description}'

# Check if a role collection exists
btp --format json list security/role-collection | \
  jq 'any(.[]; .name == "MyRoleCollection")'

# Get CF org details from environment instance
btp --format json list accounts/environment-instance | \
  jq '.environmentInstances[] | select(.environmentType == "cloudfoundry") | {displayName, labels}'

# List users in a role collection
btp --format json get security/role-collection "MyRC" | \
  jq '.userReferences[] | .value'

# Extract subaccount ID by display name
btp --format json list accounts/subaccount | \
  jq '.value[] | select(.displayName == "MySubaccount") | .guid'

# Get binding credentials (service key)
btp --format json get services/binding <binding-id> | \
  jq '.credentials'
```

### Script Template: Safe BTP CLI Execution

```bash
#!/bin/bash
set -e

# 1. Verify session is active
if ! btp --format json list accounts/global-account &>/dev/null; then
  echo "ERROR: BTP CLI session expired. Run: btp login"
  exit 1
fi

# 2. Target subaccount
SUBACCOUNT_ID="2f964175-0856-47b2-b77a-469c74df0cca"
btp target --subaccount "$SUBACCOUNT_ID"

# 3. Check if role collection already exists before creating
RC_NAME="MyApp-Users"
EXISTS=$(btp --format json list security/role-collection | jq --arg name "$RC_NAME" 'any(.[]; .name == $name)')
if [ "$EXISTS" = "false" ]; then
  btp create security/role-collection "$RC_NAME" --description "Users of MyApp"
  echo "Created role collection: $RC_NAME"
else
  echo "Role collection already exists: $RC_NAME"
fi
```

---

## Auth/Trust Domain Reference

### XSUAA App IDs

After deploying an MTA with XSUAA, get the `appId` (needed for `btp add security/role`):

```bash
# List all XSUAA apps — appId has the format "<xsappname>!t<number>"
btp --format json list security/app | jq '.[] | {xsappname, appid}'
```

### Assigning Role-Templates to Role Collections After Deployment

Role collections created before XSUAA app deployment have no roles. After deploying, add them:

```bash
# 1. Get the appId
APP_ID=$(btp --format json list security/app | \
  jq -r '.[] | select(.xsappname | startswith("nonosgi-auth")) | .appid')

# 2. List available role-templates from the app
btp --format json get security/app "$APP_ID" | jq '.roleTemplates[] | .name'

# 3. Add each role-template to the appropriate role collection
btp add security/role "Everyone" \
  --to-role-collection "nonosgi-auth-Everyone" \
  --of-app "$APP_ID" \
  --of-role-template "Everyone"
```

### Creating Trust with IAS

```bash
# List existing trust configurations
btp list security/trust

# Create OIDC trust with IAS tenant
btp create security/trust \
  --idp <ias-tenant-hostname-or-id> \
  --subaccount <subaccount-id>
# e.g. --idp nss.accounts.ondemand.com

# After creation, set it as default in BTP Cockpit manually
# (btp update security/trust --set-default does NOT exist)
```

---

## Entitlements and Provisioning Reference

### CF Runtime Quota (most common blocker)

When `cf push` or `cf deploy` fails with `SUBSCRIPTION_QUOTA — total memory: 0, routes: 0`:

```bash
# Check current entitlements
btp list accounts/entitlement --subaccount <id>

# Assign CF Runtime memory — triggers BTP to create and apply a new org quota
btp assign accounts/entitlement \
  --service-name APPLICATION_RUNTIME \
  --plan-name MEMORY \
  --subaccount <id> \
  --amount 3072    # MB — 3 GB minimum for typical apps

# After assignment, BTP automatically:
# 1. Creates a new org quota named "<guid>-t-<subaccount-guid>"
# 2. Applies it to the CF org (replaces SUBSCRIPTION_QUOTA)
# Note: btp assign can take 30-60 seconds to propagate to CF
```

> **Gotcha:** Space quotas (`cf create-space-quota`) do NOT override org-level route limits. The org quota must be fixed. Only BTP Cockpit entitlement assignment (or the `btp assign accounts/entitlement` command above) creates the correct org quota automatically.

### Provisioning a CF Org

```bash
# Check if CF environment already exists
btp --format json list accounts/environment-instance --subaccount <id> | \
  jq '.environmentInstances[] | select(.environmentType == "cloudfoundry")'

# Create CF environment (provisions a CF org)
btp create accounts/environment-instance \
  --environment cloudfoundry \
  --service cloudfoundry \
  --plan standard \
  --subaccount <id> \
  --display-name "My CF Org" \
  --parameters '{"instance_name":"my-org-name"}'
```

---

## Verification Commands

After any BTP CLI operation, verify with:

```bash
# Verify role collection was created
btp --format json list security/role-collection | jq '.[].name'

# Verify role was added to collection
btp --format json get security/role-collection "<rc-name>" | \
  jq '.roleReferences[] | {roleTemplateName, roleTemplateAppId}'

# Verify user assignment
btp --format json get security/role-collection "<rc-name>" | \
  jq '.userReferences[] | .value'

# Verify trust created
btp list security/trust

# Verify entitlement applied
btp --format json list accounts/entitlement | \
  jq '.[] | select(.serviceName == "APPLICATION_RUNTIME")'

# Verify service instance created
btp --format json list services/instance | jq '.[] | {name, offering, plan, state}'
```

---

## Common Issues

| Symptom | Cause | Solution |
|---------|-------|---------|
| `Authorization failed. Unknown session` | BTP CLI session expired | Run `btp login` (browser SSO) |
| `btp add security/role` fails with "app not found" | Wrong `--of-app` format | Use full XSUAA appId (`xsappname!tXXXX`), get from `btp --format json list security/app \| jq '.[] \| .appid'` |
| `btp assign` with `--of-idp` fails | Wrong flag name | Use `--of-idp` (not `--origin`) for custom IdP users |
| Role collection shows empty after `btp add security/role` | Race condition or targeting wrong subaccount | Verify with `btp get security/role-collection <name>`, check `--subaccount` param |
| `btp create security/trust` fails | IAS tenant not yet connected | Use IAS tenant hostname (not GUID), verify IAS tenant exists |
| `btp assign accounts/entitlement` appears to do nothing | BTP Cockpit hasn't reflected the change yet | Wait 60 seconds; then check `btp list accounts/entitlement` |
| CF quota still 0 after entitlement assignment | BTP creates quota async | Wait 1-2 minutes, then run `cf quotas` to see new quota appear |
| `--set-default` flag not recognized by `btp update security/trust` | This subcommand does not exist | Set default IdP manually in BTP Cockpit → Security → Trust Configuration |

---

## Scope Boundary: What BTP CLI Cannot Do

| Task | Reason | Alternative |
|------|--------|-------------|
| Manage CF apps, spaces, routes, services | These are CF runtime layer objects | `cf` CLI |
| Create CF service instances | CF service instances live in CF org/space | `cf create-service` |
| Manage destinations (create, update, delete) | No reliable BTP CLI support for destinations | Destination Service REST API |
| Headless/CI login with client credentials | Login is always interactive (browser/password) | BTP REST APIs with service keys |
| Set default IdP in trust configuration | No `--set-default` flag exists | BTP Cockpit → Security → Trust |
| Abort stuck MTA operations | MTA operations run in CF layer | `cf mta-ops`, `cf deploy -i -a abort` |
| Access Neo subaccount resources | BTP CLI targets CF subaccounts only | Neo CLI (`neo.sh`) or Neo REST APIs |

---

## See Also

- [authentication-xsuaa/SKILL.md](../authentication-xsuaa/SKILL.md) — XSUAA scopes, role-templates, xs-security.json
- [subaccount-trust-import/SKILL.md](../subaccount-trust-import/SKILL.md) — automated trust creation via BTP CLI
- [subaccount-roles-import/SKILL.md](../subaccount-roles-import/SKILL.md) — automated role collection creation via BTP CLI
- [subaccount-destinations-import/SKILL.md](../subaccount-destinations-import/SKILL.md) — destination import (REST API, not BTP CLI)

## References

- [BTP CLI Documentation](https://help.sap.com/docs/BTP/65de2977205c403bbc107264b8eccf4b/7c6df2db6332419ea7a862191525a447.html)
- [BTP CLI Releases](https://github.com/SAP/btp-cli-releases/releases)
- [BTP Account Administration APIs](https://api.sap.com/package/SAPCloudPlatformCoreServices)
