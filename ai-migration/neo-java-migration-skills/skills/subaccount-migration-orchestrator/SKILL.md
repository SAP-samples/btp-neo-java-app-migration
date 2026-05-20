---
name: subaccount-migration-orchestrator
description: >-
  Orchestrate complete Neo subaccount configuration migration to Cloud Foundry. Collects
  all required inputs upfront, then invokes the subaccount migration skills in sequence:
  trust export/import and roles export. Destinations and keystores migration is deferred
  to neo-destinations-keystores-migrator (run after all CF apps are deployed). Produces a
  consolidated migration report. Use when you want a single command to migrate all
  platform-level configuration from a Neo subaccount to CF. Individual skills can also
  be invoked standalone for partial migrations.
disable-model-invocation: false
allowed-tools: Read, Write, Glob, Bash(curl *), Bash(python3 *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *)
---

# Subaccount Migration Orchestrator

Orchestrate complete Neo subaccount configuration migration to Cloud Foundry.

## Purpose

This orchestrator collects all required inputs upfront, then invokes the subaccount migration skills in the correct sequence to migrate all platform-level configuration from a Neo subaccount to a CF subaccount:

1. **Trust** — `subaccount-trust-export` → `subaccount-trust-import`
2. **Destinations & Keystores** — `neo-destinations-keystores-migrator` (deferred — run after all CF apps are deployed)
3. **Roles** — `subaccount-roles-export` → `subaccount-roles-import`

After all skills complete, it produces a consolidated report summarizing results across all domains.

> **Individual skills can be invoked standalone.** If you only need to migrate destinations and keystores, invoke `neo-destinations-keystores-migrator` directly — you do not need the orchestrator.

> **What this orchestrator does NOT migrate:**
> - Application source code (use `neo-to-cf-migration-orchestrator` for that)
> - Keystore and password data (requires cryptographic operations not supported via shell)
> - Platform members / cockpit access (use Neo Cloud Cockpit's member export/import)
> - Cloud Connector tunnel configuration (SCC Admin UI only)
> - HANA data migration (out of scope for this tooling)

## Prerequisites

**Neo side:**
- Neo Platform API OAuth client with scopes: `hcp.readTrustSettings`, `readDestination`, `readAuthorizationSettings`
- Neo subaccount technical name and region host

**CF side:**
- BTP CLI installed and logged in (`btp login --sso`)
- CF CLI installed and logged in (`cf login --sso`)
- CF subaccount GUID (find in BTP cockpit > subaccount Overview)

**Tools:**
- `curl` on PATH
- `jq` on PATH (recommended)

## Step 0: Collect All Inputs

Before invoking any skill, resolve all required parameters so the migration can run uninterrupted.

**0a. Determine the migration directory:**

```bash
# If running inside an app migration copy (created by jakarta-java25-migration),
# use its .migration/ folder so all config lives alongside the app.
# Otherwise use a temp directory — subaccount migration is not tied to any single app repo.
if [ -f .migration/cf-migration-config.json ]; then
  MIGRATION_DIR="$(pwd)/.migration"
  echo "Using app migration directory: $MIGRATION_DIR"
else
  MIGRATION_DIR="${TMPDIR:-/tmp}/neo-subaccount-migration"
  mkdir -p "$MIGRATION_DIR"
  echo "Using temp migration directory: $MIGRATION_DIR"
fi
```

**0b. Check for existing config files:**

```bash
if [ -f "$MIGRATION_DIR/neo-migration-config.json" ]; then
  echo "=== Neo config found ==="
  cat "$MIGRATION_DIR/neo-migration-config.json"
fi

if [ -f "$MIGRATION_DIR/cf-migration-config.json" ]; then
  echo "=== CF config found ==="
  cat "$MIGRATION_DIR/cf-migration-config.json"
fi
```

**0c. Ask the user** for any missing values:

**Neo subaccount:**
1. "What is the technical name of your Neo subaccount?"
2. "What is the Neo region host?" (e.g., `eu1.hana.ondemand.com`)
3. "How would you like to authenticate to the Neo APIs? (a) Platform API OAuth client credentials, or (b) pre-issued Bearer token?"
   - If (a): client ID and client secret
   - If (b): bearer token (warn: 25-minute expiry — may not be sufficient for full migration)

**CF subaccount:**
4. "What is the GUID of the target CF subaccount?"

**0d. Save config files:**

Save `$MIGRATION_DIR/neo-migration-config.json` if it doesn't exist.
Save `$MIGRATION_DIR/cf-migration-config.json` if it doesn't exist.

**0e. If `$MIGRATION_DIR` is inside a git repo, ensure it is in `.gitignore`:**

```bash
# Only relevant when MIGRATION_DIR is inside the app copy (.migration/)
if [[ "$MIGRATION_DIR" != /tmp* ]]; then
  if [ -f .gitignore ]; then
    grep -q '^\\.migration' .gitignore 2>/dev/null || echo '.migration/' >> .gitignore
  else
    echo '.migration/' > .gitignore
  fi
fi
```

**0f. Verify BTP CLI and CF CLI sessions:**

```bash
btp target
cf target
```

If either fails, stop and tell the user which CLI needs login.

## Step 1: Trust Migration

Inform the user:
> "Step 1/3: Migrating trust configuration (IdP)..."

Invoke skill: **`subaccount-trust-export`**

After export completes, invoke skill: **`subaccount-trust-import`**

Record results:
- Read `$MIGRATION_DIR/neo-trust-config.json` — note IdP count and types
- Read `$MIGRATION_DIR/neo-trust-import-report.json` — note imported/failed/manual steps

If trust-import fails critically (BTP CLI unavailable, CF subaccount ID wrong), stop and report the error. Do not proceed to subsequent steps.

## Step 2: Destinations and Keystores Migration

> **Deferred — run AFTER all apps are deployed to CF.**
> App-level destinations and keystores are bound to specific CF app instances. If the CF apps do not exist yet, the migration script cannot create or bind the per-app Destination Service instances.
>
> After completing app code migration (`neo-to-cf-migration-orchestrator`) and deploying all apps (`cf deploy . -f`), invoke skill: **`neo-destinations-keystores-migrator`**

Record in the consolidated report that destinations and keystores migration is deferred. When `neo-destinations-keystores-migrator` is later run in post-deploy Phase 5, its summary output (uploaded/failed counts per account and per app) should be appended to the report manually.

If destinations migration fails, log the error in the consolidated report but **continue** to Step 3 — roles migration is independent.

## Step 3: Roles Export

Inform the user:
> "Step 3/3: Exporting application roles and groups..."

Invoke skill: **`subaccount-roles-export`**

Record results:
- Read `$MIGRATION_DIR/neo-roles.json` — note role/group counts

> **Roles import is deferred.** `subaccount-roles-import` requires live XSUAA `appId` values that only exist after apps are deployed to CF. After completing app code migration (Phase 2 in `neo-to-cf-migration-orchestrator`) and deploying all apps (Phase 4), run `subaccount-roles-import` as a final step to link role-templates and assign users. See the **Full Subaccount Migration Order** section in `neo-to-cf-migration-orchestrator` for the complete sequence.

## Step 4: Build Consolidated Report

Read all individual reports and build a summary. Save to `$MIGRATION_DIR/subaccount-migration-report.json` using the Write tool:

```json
{
  "migrationDir": "<MIGRATION_DIR>",
  "sourceSubaccount": "<Neo subaccount name>",
  "targetSubaccount": "<CF subaccount GUID>",
  "migrationTimestamp": "<ISO 8601 timestamp>",
  "trust": {
    "status": "completed|failed|skipped",
    "iasIdPsImported": 0,
    "thirdPartyIdPs": 0,
    "reportFile": "<MIGRATION_DIR>/neo-trust-import-report.json"
  },
  "destinations": {
    "status": "deferred — run neo-destinations-keystores-migrator after all apps are deployed"
  },
  "roles": {
    "exportStatus": "completed",
    "importStatus": "deferred — run subaccount-roles-import after all apps are deployed",
    "totalApplications": 0,
    "totalRoles": 0,
    "totalGroups": 0,
    "reportFile": "<MIGRATION_DIR>/neo-roles.json"
  },
  "requiresManualSteps": true,
  "consolidatedManualSteps": [
    "<step from trust>",
    "<step from destinations>",
    "<step from roles>"
  ]
}
```

## Step 5: Display Consolidated Summary

```
========================================
Neo Subaccount Migration Complete
========================================
Source:  <Neo subaccount> (<region>)
Target:  <CF subaccount GUID>

TRUST
  IAS IdPs imported:   <count>
  Third-party IdPs:    <count> [manual config required]
  Status: <completed|failed>

DESTINATIONS & KEYSTORES
  ⚠ Deferred: run neo-destinations-keystores-migrator after all apps are deployed

ROLES (export only — import deferred)
  Applications: <count>
  Roles:        <count>
  Groups:       <count>
  ⚠ Import pending: run subaccount-roles-import after all apps are deployed

========================================
MANUAL STEPS REQUIRED
========================================
The following items could not be automated and must be completed manually:

TRUST:
  1. <trust manual step>

DESTINATIONS:
  2. Re-enter password for 'dest1' in CF Destination Service cockpit
  3. Re-enter client secret for 'dest2'

ROLES:
  4. Run subaccount-roles-import after all apps are deployed to CF

Full report: $MIGRATION_DIR/subaccount-migration-report.json
Individual reports:
  Trust:        $MIGRATION_DIR/neo-trust-import-report.json
  Roles export: $MIGRATION_DIR/neo-roles.json

NEXT STEPS:
  1. Complete all manual steps listed above
  2. Migrate application code: use neo-to-cf-migration-orchestrator for each app
  3. Deploy all apps to CF: cf deploy . -f (from each app directory)
  4. Run neo-destinations-keystores-migrator to migrate destinations and keystores
  5. Run subaccount-roles-import to assign role-templates and users
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `neo-migration-config.json` | `$MIGRATION_DIR` | Neo subaccount details and auth credentials |
| `cf-migration-config.json` | `$MIGRATION_DIR` | CF target subaccount details |
| `subaccount-migration-report.json` | `$MIGRATION_DIR` | Consolidated migration report |
| `neo-trust-config.json` | `$MIGRATION_DIR` | Trust export output |
| `neo-trust-import-report.json` | `$MIGRATION_DIR` | Trust import results |
| `neo-roles.json` | `$MIGRATION_DIR` | Roles export output |
| `neo-roles-import-report.json` | `$MIGRATION_DIR` | Roles import results |

## CF Services

| Service | Plan | Purpose | Created by |
|---------|------|---------|------------|
| `destination` | `lite` | Subaccount-level destinations | `neo-destinations-keystores-migrator` |

## Common Issues

### Issue: Migration interrupted before completing all steps
**Cause:** Token expiry, network interruption, or CLI session expiry.
**Solution:** Re-invoke the orchestrator — completed steps are idempotent (already-existing resources are treated as success). The migration will resume from where it failed.

### Issue: BTP CLI session expires mid-migration
**Cause:** BTP CLI sessions have limited validity.
**Solution:** Run `btp login --sso` and re-invoke the orchestrator.

### Issue: Pre-issued Bearer token expires during export
**Cause:** Neo API Bearer tokens are valid for only 25 minutes.
**Solution:** Use client credentials authentication (not pre-issued token) for the full orchestrated migration, as it supports automatic token refresh.

### Issue: One step fails but others succeed
**Cause:** Independent failures per domain (e.g., Destination Service not entitled but trust works fine).
**Solution:** Fix the specific issue and re-invoke only the affected skill directly (e.g., `neo-destinations-keystores-migrator`). The orchestrator can also be re-run — idempotent operations will skip already-completed work.

## Next Steps

After completing subaccount configuration migration:

- **Complete all manual steps** listed in the consolidated report
- **[neo-to-cf-migration-orchestrator](../neo-to-cf-migration-orchestrator/SKILL.md)** — migrate application source code and runtime configuration for each Neo application; see its **Full Subaccount Migration Order** section for the complete multi-app sequence
- **Deploy all apps** — `cf deploy . -f` from each app directory (Phase 4 in the full migration order)
- **[subaccount-roles-import](../subaccount-roles-import/SKILL.md)** — run this **after all apps are deployed** to assign role-templates and users to the role collections created by `authentication-xsuaa`
- **Test end-to-end** — deploy a migrated application and verify authentication, destination connectivity, and authorization using a fresh browser session
