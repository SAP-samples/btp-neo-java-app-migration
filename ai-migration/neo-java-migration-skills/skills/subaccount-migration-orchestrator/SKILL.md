---
name: subaccount-migration-orchestrator
description: >-
  Orchestrate complete Neo subaccount configuration migration to Cloud Foundry. Collects
  all required inputs upfront, then invokes the subaccount migration skills in sequence:
  trust migration and roles export. Destinations and keystores migration is deferred
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

1. **Trust** — `subaccount-trust-migrator` (single pass: export + import in-memory)
2. **Destinations & Keystores** — `neo-destinations-keystores-migrator` (deferred — run after all CF apps are deployed)
3. **Roles** — `subaccount-roles-export` → `subaccount-roles-import`

After all skills complete, it produces a consolidated report summarizing results across all domains.

> **Individual skills can be invoked standalone.** If you only need to migrate destinations and keystores, invoke `neo-destinations-keystores-migrator` directly — you do not need the orchestrator.

## Orchestration Algorithm

This orchestrator follows the same **Orchestrator-Worker pattern** as `neo-to-cf-migration-orchestrator`. Each migration skill is dispatched to a `general-purpose` subagent so the orchestrator's context never holds raw trust data, role JSON, or shell output from the underlying scripts.

### Dispatch tool

Every step (trust migration, roles-export) is invoked via the `Agent` tool with `subagent_type: "general-purpose"`:

```
Agent(
  subagent_type: "general-purpose",
  description: "<step name>",
  prompt: <step-specific prompt — see each step below>
)
```

Inline-only work (collecting inputs, building the consolidated report) stays in the orchestrator. Trust migration produces no disk files — the subagent returns a plain-text summary. Roles export writes `$MIGRATION_DIR/neo-roles.json`; the orchestrator reads only the small summary fields it needs.

### Concurrency policy

| Step | Mode | Why |
|------|------|-----|
| Step 0 input collection | Inline | User Q&A and config file writes — no large output. |
| Step 1 trust migration | Single subagent | Export and import run in-memory inside one script; no intermediate files. |
| Step 3 roles export | Sequential (after Step 1) | Roles import is deferred to post-deploy and lives in `subaccount-roles-import`. |
| Step 4 consolidated report | Inline | Read roles JSON + subagent summaries, render report. |

No fan-out is appropriate here — the steps form a short dependency chain.

### Failure & retry policy

- **Max retries per step: 2.** After 2 failed subagent attempts on the same step, surface to the user.
- **Trust migration failure** → hard stop. Do not proceed to roles export.
- **Roles export failure** → log and surface to user. Roles export runs only after trust migration succeeds, but a roles-export failure does not affect trust (they are independent in failure handling).
- **Token expiry mid-run** → re-spawn the subagent with the same prompt (the trust script and roles export are idempotent; already-existing resources are treated as success).


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

**0g. Setup NEO API (telemetry consent + functions):**

> This must run **before** any subagent is dispatched. Do not proceed to Step 1 until all telemetry files exist.

**Resolve telemetry paths:**

```bash
_NEO_MIGRATION_HOME="${XDG_DATA_HOME:-${APPDATA:-$HOME}}/.neo-migration"
_NEO_CONSENTS_HOME="${XDG_DATA_HOME:-${APPDATA:-$HOME}}/.neo-migration-consents"
_CF_ORG=$(cf target 2>/dev/null | awk '/^org:/{print $2}')
CF_SUBACCOUNT_GUID=$(btp list accounts/subaccount 2>/dev/null | awk -v org="${_CF_ORG}" '
  NR > 2 { guid=$1; subdomain=$3; if (index(org, subdomain) > 0) { print guid; exit } }
')
[ -n "${CF_SUBACCOUNT_GUID}" ] || { echo "ERROR: Could not resolve CF_SUBACCOUNT_GUID — check btp login and cf target." >&2; exit 1; }
_SUBACCOUNT_DIR="${_NEO_MIGRATION_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}"
mkdir -p "${_NEO_MIGRATION_HOME}/${SUBACCOUNT}"
mkdir -p "${_SUBACCOUNT_DIR}"
mkdir -p "${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}"
_TELEMETRY_FILE="${_NEO_CONSENTS_HOME}/neo-telemetry-installation.txt"
_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/neo-telemetry-consent.txt"
_SESSION_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}/neo-telemetry-session.txt"
_SP_SIGNING_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/trust-sp-signing-consent.txt"
_KEYSTORES_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/dest-keystores-consent.txt"
_OAUTH_CREDENTIALS_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/dest-oauth-credentials-consent.txt"
```

**Collect telemetry consent (per subaccount — ask only if not already recorded):**

> **STOP — do not run any bash here.** Check whether `$_CONSENT_FILE` exists:
> - If it exists and contains a valid `consent=` line, read the value from there (skip asking the user).
> - If it does not exist or `consent=` is missing, use the `AskUserQuestion` tool to ask the user:
>   **"Enable telemetry? This migration appends two non-PII random UUIDs (neo_cf_migration_installation_id, neo_cf_migration_session_id) as query parameters to every NEO API call for usage tracking. No personal data, subaccount names, or credentials are included. Enable? (yes/no)"**
>   Wait for the user's answer before continuing.

```bash
if [ ! -f "${_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_CONSENT_FILE}"; then
  _CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_CONSENT}" > "${_CONSENT_FILE}"
fi
_CONSENT=$(grep "^consent=" "${_CONSENT_FILE}" | cut -d= -f2)
```

**Ensure installation_id exists and generate new session_id:**

```bash
if [ "${_CONSENT}" = "yes" ]; then
  # installation_id — once per machine
  if [ ! -f "${_TELEMETRY_FILE}" ] || ! grep -q "^neo_cf_migration_installation_id=" "${_TELEMETRY_FILE}"; then
    _INSTALLATION_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
    echo "neo_cf_migration_installation_id=${_INSTALLATION_ID}" > "${_TELEMETRY_FILE}"
    echo "INFO: New installation ID generated: ${_INSTALLATION_ID}"
  fi

  # session_id — ALWAYS new (orchestrator = new migration attempt)
  _MIGRATION_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
  echo "neo_cf_migration_session_id=${_MIGRATION_ID}" > "${_SESSION_FILE}"
  echo "INFO: New migration session started. neo_cf_migration_session_id: ${_MIGRATION_ID}"
fi
```

**Collect SP signing key consent (per subaccount — ask only if not already recorded):**

> **STOP — do not run any bash here.** Check whether `$_SP_SIGNING_CONSENT_FILE` exists:
> - If it exists and contains a valid `consent=` line, read the value from there (skip asking the user).
> - If it does not exist or `consent=` is missing, use the `AskUserQuestion` tool to present this disclaimer and ask the user:
>   **"The Neo trust configuration includes the signing key of the local service provider (SP). This key flows through the migration script in memory and there is a small risk it may reach this LLM model. Do you want to proceed with the trust migration? (yes/no)"**
>   Wait for the user's answer before continuing.

```bash
if [ ! -f "${_SP_SIGNING_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_SP_SIGNING_CONSENT_FILE}"; then
  _SP_SIGNING_CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_SP_SIGNING_CONSENT}" > "${_SP_SIGNING_CONSENT_FILE}"
fi
_SP_SIGNING_CONSENT=$(grep "^consent=" "${_SP_SIGNING_CONSENT_FILE}" | cut -d= -f2)
```

**Collect OAuth credentials consent (per subaccount — ask only if not already recorded):**

> **STOP — do not run any bash here.** Check whether `$_OAUTH_CREDENTIALS_CONSENT_FILE` exists:
> - If it exists and contains a valid `consent=` line, read the value from there (skip asking the user).
> - If it does not exist or `consent=` is missing, use the `AskUserQuestion` tool to present this disclaimer and ask the user:
>   **"Two destination authentication types (OAuth2SAMLBearerAssertion and OAuth2ClientCredentials with mTLS token retrieval) have credentials that can be extracted from Neo. There is a risk they may reach this LLM model. Do you want to migrate these destinations including their credentials? (yes/no)"**
>   Wait for the user's answer before continuing.

```bash
if [ ! -f "${_OAUTH_CREDENTIALS_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_OAUTH_CREDENTIALS_CONSENT_FILE}"; then
  _OAUTH_CREDENTIALS_CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_OAUTH_CREDENTIALS_CONSENT}" > "${_OAUTH_CREDENTIALS_CONSENT_FILE}"
fi
_OAUTH_CREDENTIALS_CONSENT=$(grep "^consent=" "${_OAUTH_CREDENTIALS_CONSENT_FILE}" | cut -d= -f2)
```

**Collect keystores consent (per subaccount — ask only if not already recorded):**

> **STOP — do not run any bash here.** Check whether `$_KEYSTORES_CONSENT_FILE` exists:
> - If it exists and contains a valid `consent=` line, read the value from there (skip asking the user).
> - If it does not exist or `consent=` is missing, use the `AskUserQuestion` tool to present this disclaimer and ask the user:
>   **"Keystores contain sensitive cryptographic information such as private keys. There is a risk they may reach this LLM model during migration. Do you want to migrate keystores? (yes/no)"**
>   Wait for the user's answer before continuing.

```bash
if [ ! -f "${_KEYSTORES_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_KEYSTORES_CONSENT_FILE}"; then
  _KEYSTORES_CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_KEYSTORES_CONSENT}" > "${_KEYSTORES_CONSENT_FILE}"
fi
_KEYSTORES_CONSENT=$(grep "^consent=" "${_KEYSTORES_CONSENT_FILE}" | cut -d= -f2)
```

**Source the NEO API setup script:**

```bash
_NEO_SETUP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && cd ../../shared && pwd)/neo-api-setup-template.sh"
[ -f "${_NEO_SETUP}" ] || _NEO_SETUP="ai-migration/neo-java-migration-skills/shared/neo-api-setup-template.sh"
source "${_NEO_SETUP}" || exit 1
echo "INFO: NEO API setup complete. fetch_neo_*() functions are ready."
```

**Guard — do not dispatch any subagent without all consent files:**

```bash
[ -f "${_CONSENT_FILE}" ] || {
  echo "ERROR: Telemetry consent file not written. Cannot dispatch subagents." >&2
  exit 1
}
if [ "${_CONSENT}" = "yes" ]; then
  [ -f "${_SESSION_FILE}" ] || {
    echo "ERROR: Session file not written. Cannot dispatch subagents." >&2
    exit 1
  }
fi
[ -f "${_SP_SIGNING_CONSENT_FILE}" ] || {
  echo "ERROR: SP signing key consent file not written. Cannot dispatch subagents." >&2
  exit 1
}
[ -f "${_OAUTH_CREDENTIALS_CONSENT_FILE}" ] || {
  echo "ERROR: OAuth credentials consent file not written. Cannot dispatch subagents." >&2
  exit 1
}
[ -f "${_KEYSTORES_CONSENT_FILE}" ] || {
  echo "ERROR: Keystores consent file not written. Cannot dispatch subagents." >&2
  exit 1
}
```

## Step 1: Trust Migration

Inform the user:
> "Step 1/3: Migrating trust configuration (IdP)..."

Dispatch a single subagent via the `Agent` tool:

```
Agent(
  subagent_type: "general-purpose",
  description: "subaccount trust migration",
  prompt: <prompt below>
)
```

Prompt:

```
You are running the subaccount-trust-migrator skill of the Neo→CF subaccount migration.

Neo subaccount: <NEO_SUBACCOUNT>
Neo region host: <NEO_REGION_HOST>
CF subaccount ID: <CF_SUBACCOUNT_ID>

Your task:
1. Invoke the subaccount-trust-migrator skill and follow it exactly.
   - Collect the Neo Bearer token from the user if not already available.
2. Run the migration script. Do NOT write any files.
3. Return a concise report (≤ 15 lines):
   - IAS IdPs imported / already_configured / failed counts
   - Third-party IdPs skipped (if any)
   - Manual steps count
   - Status: SUCCESS | FAILED | PARTIAL

Your final message IS the return value. Do NOT return trust configuration data.
```

If the subagent returns FAILED critically (BTP CLI unavailable, CF subaccount ID wrong, Neo API unreachable), stop and report the error to the user. Do not proceed to Step 3.

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

### Step 3a: Dispatch roles export to a subagent

Invoke the `Agent` tool:

```
Agent(
  subagent_type: "general-purpose",
  description: "subaccount roles export",
  prompt: <prompt below>
)
```

Prompt:

```
You are running the subaccount-roles-export skill of the Neo→CF subaccount migration.

Subaccount directory: <SUBACCOUNT_DIR>
Neo subaccount config: <MIGRATION_DIR>/neo-migration-config.json

Your task:
1. Invoke the subaccount-roles-export skill and follow it exactly.
2. Write the export JSON to <SUBACCOUNT_DIR>/neo-roles.json.
3. Return a concise report (≤ 20 lines):
   - Output file path
   - totalApplications, totalRoles, totalGroups
   - Status: SUCCESS | FAILED | PARTIAL
   - Any auth/token/network errors encountered

Do NOT return the full roles JSON. The orchestrator reads it from disk if needed.
Your final message IS the return value.
```

### Step 3b: Read summary fields inline

```bash
jq '.totalApplications, .totalRoles, .totalGroups' "${_SUBACCOUNT_DIR}/neo-roles.json"
```

> **Roles import is deferred.** `subaccount-roles-import` requires live XSUAA `appId` values that only exist after apps are deployed to CF. After completing app code migration (Phase 2 in `neo-to-cf-migration-orchestrator`) and deploying all apps (Phase 4), run `subaccount-roles-import` as a final step to link role-templates and assign users. See the **Full Subaccount Migration Order** section in `neo-to-cf-migration-orchestrator` for the complete sequence.

## Step 4: Build Consolidated Report

Read all individual reports and build a summary. Save to `${_SUBACCOUNT_DIR}/subaccount-migration-report.json` using the Write tool:

```json
{
  "subaccountDir": "<_SUBACCOUNT_DIR>",
  "sourceSubaccount": "<Neo subaccount name>",
  "targetSubaccount": "<CF subaccount GUID>",
  "migrationSessionId": "<neo_cf_migration_session_id>",
  "migrationTimestamp": "<ISO 8601 timestamp>",
  "trust": {
    "status": "completed|failed|skipped",
    "iasIdPsImported": 0,
    "thirdPartyIdPs": 0
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
    "reportFile": "<_SUBACCOUNT_DIR>/neo-roles.json"
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

Full report: ${_SUBACCOUNT_DIR}/subaccount-migration-report.json
Individual reports:
  Roles export: ${_SUBACCOUNT_DIR}/neo-roles.json

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
| `neo-telemetry-installation.txt` | `~/.neo-migration-consents/` | Machine-level installation ID |
| `neo-telemetry-consent.txt` | `~/.neo-migration-consents/$SUBACCOUNT/` | Telemetry consent for this subaccount |
| `neo-telemetry-session.txt` | `~/.neo-migration-consents/$SUBACCOUNT/$CF_SUBACCOUNT_GUID/` | Session ID for the current migration attempt |
| `trust-sp-signing-consent.txt` | `~/.neo-migration-consents/$SUBACCOUNT/` | SP signing key consent for trust migration |
| `dest-keystores-consent.txt` | `~/.neo-migration-consents/$SUBACCOUNT/` | Keystores migration consent |
| `dest-oauth-credentials-consent.txt` | `~/.neo-migration-consents/$SUBACCOUNT/` | OAuth credentials migration consent |
| `neo-migration-config.json` | `$MIGRATION_DIR` | Neo subaccount details and auth credentials |
| `cf-migration-config.json` | `$MIGRATION_DIR` | CF target subaccount details |
| `subaccount-migration-report.json` | `~/.neo-migration/$SUBACCOUNT/$CF_SUBACCOUNT_GUID/` | Consolidated migration report |
| `neo-roles.json` | `~/.neo-migration/$SUBACCOUNT/$CF_SUBACCOUNT_GUID/` | Roles export output |
| `neo-roles-import-report.json` | `~/.neo-migration/$SUBACCOUNT/$CF_SUBACCOUNT_GUID/` | Roles import results |

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
