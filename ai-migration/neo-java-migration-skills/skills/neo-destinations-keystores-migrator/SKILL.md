---
name: neo-destinations-keystores-migrator
description: Invoke this skill to migrate destination and keystore DATA (configuration entries, certificates, credentials) that are stored at subaccount level or app level in a SAP BTP Neo environment to Cloud Foundry — this transfers platform-level configuration, NOT application source code. Use when the user wants to transfer existing Neo destination configurations or keystore entries to CF (e.g. 'migrate my BTP destinations to CF', 'copy Neo keystores to Cloud Foundry', 'transfer destination configs from Neo account', 'upload keystores to CF'). Do NOT invoke for source code changes that replace ConnectivityConfiguration/DestinationConfiguration Java API usage with SAP Cloud SDK — use the destinations skill for that instead.
disable-model-invocation: false
allowed-tools: Bash(curl *), Bash(python3 *), Bash(cf *)
---

# Neo Destinations and Keystores Migrator

Migrates all destinations and keystores from SAP BTP Neo to Cloud Foundry in a single pass. No intermediate files are written — data flows directly from Neo APIs to CF Destination Service in memory.

## Clarification — confirm the right skill before starting

If the user's request is ambiguous (e.g. "migrate my destinations", "migrate destinations to CF" with no further context), ask this question **before doing anything else**:

> **Which type of destinations migration do you need?**
>
> **A — Transfer destination configurations** stored in your Neo subaccount (names, URLs, auth types, properties) to the CF Destination Service. This does NOT touch your Java source code.
>
> **B — Migrate application source code** that uses the Neo Connectivity/Destination Java API (`ConnectivityConfiguration`, `DestinationConfiguration` JNDI lookups) to use SAP Cloud SDK `DestinationAccessor` instead.
>
> Reply **A** or **B**.

- If the user replies **A** (or the request is clearly about transferring subaccount-level configs) → continue with this skill.
- If the user replies **B** (or the request is clearly about changing Java source code) → stop and tell the user: *"For source code migration, use the **destinations** skill instead."*

## What this skill does

1. Fetches all Neo applications
2. Reads account-level and app-level destinations and keystores from Neo (held in memory)
3. Creates CF Destination Service instances as needed
4. Uploads everything directly to CF — data never touches disk

## Security disclaimers

**Present the two disclaimers strictly one at a time, in order, BEFORE asking the user for any inputs. Do NOT show the next disclaimer until the user has explicitly replied to the current one. Do NOT proceed until both have been acknowledged.**

### 1. Credentials — two-part disclaimer

**Part A — information only, no reply needed:**

Present this message first, without waiting for a reply:

> **Credentials will not be migrated.**
> For destinations that contain credentials, the Neo API does not allow them to be extracted — they will be created in CF without credentials and you will need to re-enter them manually in BTP Cockpit after migration.
> The only exceptions are the two authentication types described below.

**Part B — explicit consent required:**

Immediately after Part A, present this and wait for the user to reply:

> **Two destination authentication types have credentials that can be extracted from Neo:**
> - `OAuth2SAMLBearerAssertion` with mTLS token retrieval
> - `OAuth2ClientCredentials` with mTLS token retrieval
>
> Unlike other destination authentication types, the credentials for these can be extracted from Neo and uploaded to CF. However, there is a risk that they may reach this LLM model during the process.
>
> If your subaccount and/or applications contain destinations of these two authentication types, do you want to migrate them including their credentials?
> - **yes** — extract and upload credentials (I accept the risk)
> - **no** — skip these destinations entirely (they will be listed in the summary for manual handling)

**Do not show disclaimer 2 until the user has replied.** Based on the reply, write to `$_OAUTH_CREDENTIALS_CONSENT_FILE`:
- **yes** → `consent=yes`
- **no** → `consent=no`

### 2. Keystores — explicit consent required

Present this disclaimer regardless of the answer to disclaimer 1:

> **Keystores contain sensitive data.**
> Keystores contain sensitive cryptographic information such as private keys. There is a risk that they may reach this LLM model during the process.
>
> Do you want to migrate keystores? (yes/no)

**Do not run the migration script until the user has replied.** Based on the reply, write to `$_KEYSTORES_CONSENT_FILE`:
- **yes** → `consent=yes`
- **no** → `consent=no`

## Inputs Required

After both disclaimers are acknowledged, ask the user for the required inputs:

| Input | Description | Example |
|-------|-------------|---------|
| `TOKEN` | Neo Platform API token (valid 25 min) | `eyJhbGci...` |
| `REGION_HOST` | Neo landscape host | `hana.ondemand.com` |
| `SUBACCOUNT` | Neo subaccount technical name | `wu44p26apd` |

### How to obtain a Platform API token

**Step 1 — Register a Platform API client**

1. Open **SAP BTP Cockpit** → navigate to the Neo subaccount
2. Go to **Security → OAuth → Platform API** tab
3. Click **Register New Platform Client**
4. Select exactly these three scopes (no more, no less):
   - **Lifecycle Management** → Read Applications
   - **Keystore** → Read Keystores
   - **Configuration Service** → Read Destination
5. Note the generated **Client ID** and **Client Secret**

**Step 2 — Request a token**

Getting a token is a 3-step process:

Always present all three options below to the user:

---

**Option A — Mac / Linux / WSL / Git Bash:**
```bash
CREDENTIALS=$(echo -n "<client_id>:<client_secret>" | base64) && \
curl -s -X POST "https://oauthasservices.<landscape>/oauth2/apitoken/v1?grant_type=client_credentials" -H "Authorization: Basic ${CREDENTIALS}"
```

**Option B — Windows PowerShell:**
```powershell
# 1. Encode credentials
$credentials = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("<client_id>:<client_secret>"))

# 2. Request token
$response = Invoke-WebRequest -Method POST "https://oauthasservices.<landscape>/oauth2/apitoken/v1?grant_type=client_credentials" -Headers @{"Authorization" = "Basic $credentials"}

# 3. Extract access token
($response.Content | ConvertFrom-Json).access_token
```

**Option C — REST client (Bruno, Postman, Insomnia, etc.):**

| Field | Value |
|-------|-------|
| Method | `POST` |
| URL | `https://oauthasservices.<landscape>/oauth2/apitoken/v1?grant_type=client_credentials` |
| Auth type | Basic Auth |
| Username | `<client_id>` |
| Password | `<client_secret>` |

Send the request and copy the `access_token` from the response.

---

**Step 3 — Copy the token**

From the JSON response, copy the value of `access_token`. The token is valid for **25 minutes**.

Required scopes — **exactly and only these three**:

| API Name | Scope | Technical Name |
|----------|-------|----------------|
| Keystore | Read Keystores | `hcp.readKeystores` |
| Configuration Service | Read Destination | `hcp.readDestination` |
| Lifecycle Management | Read Applications | `hcp.readJavaApplications` |

> **Critical: The Platform API client must be registered with exactly these three scopes — no more, no less.**
>
> The migration script validates the token's scope list at startup and **will refuse to run** if:
> - Any of the three scopes is missing (`ERROR: Token is missing required scopes: ...`)
> - Any additional scope is present (`ERROR: Token has scopes beyond the required set: ...`)
>
> If the token was issued for a client that has extra scopes (e.g. `hcp.manage*` or `hcp.write*`), you must **register a new, separate Platform API client** with exactly the three scopes above, generate a new token from that client, and use that token.

### Verify CF target and resolve CF_SUBACCOUNT_GUID

Run `cf target` and show the output to the user. Confirm the correct org and space before continuing.

Then resolve `CF_SUBACCOUNT_GUID` automatically — do NOT ask the user for it:

```bash
_CF_ORG=$(cf target 2>/dev/null | awk '/^org:/{print $2}')
CF_SUBACCOUNT_GUID=$(btp list accounts/subaccount 2>/dev/null | awk -v org="${_CF_ORG}" '
  NR > 2 {
    guid=$1; subdomain=$3
    if (index(org, subdomain) > 0) { print guid; exit }
  }
')
if [ -z "${CF_SUBACCOUNT_GUID}" ]; then
  echo "ERROR: Could not resolve CF_SUBACCOUNT_GUID from org '${_CF_ORG}' — check btp login and cf target." >&2
  exit 1
fi
echo "Resolved CF_SUBACCOUNT_GUID: ${CF_SUBACCOUNT_GUID}"
```

## Collect Neo → CF app mapping

After both disclaimers are acknowledged and all required inputs have been collected, collect telemetry consent and fetch the Neo app list via the migration script:

**Collect consent and setup session (per Neo subaccount + CF subaccount pair):**

> **STOP — do not run any bash here.** Check whether `~/.neo-migration-consents/$SUBACCOUNT/neo-telemetry-consent.txt` exists:
> - If it exists and contains a valid `consent=` line, read the value from there and skip to the next step.
> - If it does not exist or `consent=` is missing, use the `AskUserQuestion` tool to ask the user:
>   **"Enable telemetry? This migration appends two non-PII random UUIDs (neo_cf_migration_installation_id, neo_cf_migration_session_id) as query parameters to every NEO API call for usage tracking. No personal data, subaccount names, or credentials are included. Enable? (yes/no)"**
>   Wait for the user's answer before continuing.
>
> Similarly, check `~/.neo-migration-consents/$SUBACCOUNT/dest-oauth-credentials-consent.txt` and `~/.neo-migration-consents/$SUBACCOUNT/dest-keystores-consent.txt`. If either is missing, ask the user using the same disclaimers shown in the Security Disclaimers section above before writing the files.

```bash
_NEO_MIGRATION_HOME="${XDG_DATA_HOME:-${APPDATA:-$HOME}}/.neo-migration"
_NEO_CONSENTS_HOME="${XDG_DATA_HOME:-${APPDATA:-$HOME}}/.neo-migration-consents"
_SUBACCOUNT_DIR="${_NEO_MIGRATION_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}"
mkdir -p "${_NEO_MIGRATION_HOME}/${SUBACCOUNT}"
mkdir -p "${_SUBACCOUNT_DIR}"
mkdir -p "${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}"
_TELEMETRY_FILE="${_NEO_CONSENTS_HOME}/neo-telemetry-installation.txt"
_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/neo-telemetry-consent.txt"
_SESSION_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}/neo-telemetry-session.txt"
_OAUTH_CREDENTIALS_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/dest-oauth-credentials-consent.txt"
_KEYSTORES_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/dest-keystores-consent.txt"

# Telemetry consent — per Neo subaccount
if [ ! -f "${_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_CONSENT_FILE}"; then
  _CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_CONSENT}" > "${_CONSENT_FILE}"
fi
_CONSENT=$(grep "^consent=" "${_CONSENT_FILE}" | cut -d= -f2)

# OAuth credentials consent — ask if not already recorded (standalone run)
if [ ! -f "${_OAUTH_CREDENTIALS_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_OAUTH_CREDENTIALS_CONSENT_FILE}"; then
  _OAUTH_CREDENTIALS_CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_OAUTH_CREDENTIALS_CONSENT}" > "${_OAUTH_CREDENTIALS_CONSENT_FILE}"
fi
_OAUTH_CREDENTIALS_CONSENT=$(grep "^consent=" "${_OAUTH_CREDENTIALS_CONSENT_FILE}" | cut -d= -f2)

# Keystores consent — ask if not already recorded (standalone run)
if [ ! -f "${_KEYSTORES_CONSENT_FILE}" ] || ! grep -q "^consent=" "${_KEYSTORES_CONSENT_FILE}"; then
  _KEYSTORES_CONSENT="<yes or no from user answer above>"   # replace with actual value
  echo "consent=${_KEYSTORES_CONSENT}" > "${_KEYSTORES_CONSENT_FILE}"
fi
_KEYSTORES_CONSENT=$(grep "^consent=" "${_KEYSTORES_CONSENT_FILE}" | cut -d= -f2)

if [ "${_CONSENT}" = "yes" ]; then
  # installation_id — once per machine
  if [ ! -f "${_TELEMETRY_FILE}" ] || ! grep -q "^neo_cf_migration_installation_id=" "${_TELEMETRY_FILE}"; then
    _INSTALLATION_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
    echo "neo_cf_migration_installation_id=${_INSTALLATION_ID}" > "${_TELEMETRY_FILE}"
  fi

  # session_id — per Neo+CF pair; reuse if present (Phase 5 continues the session started in Phase 1)
  if [ ! -f "${_SESSION_FILE}" ]; then
    _MIGRATION_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
    echo "neo_cf_migration_session_id=${_MIGRATION_ID}" > "${_SESSION_FILE}"
    echo "INFO: New migration session started. neo_cf_migration_session_id: ${_MIGRATION_ID}"
  else
    _MIGRATION_ID=$(grep "^neo_cf_migration_session_id=" "${_SESSION_FILE}" | cut -d= -f2)
    echo "INFO: Resuming migration session. neo_cf_migration_session_id: ${_MIGRATION_ID}"
  fi
fi
```

**After collecting consent, source NEO API setup (exports `NEO_KEYSTORE_BASE`, `NEO_CONFIG_BASE`, `NEO_LIFECYCLE_URL`):**

```bash
_NEO_SETUP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && cd ../../shared && pwd)/neo-api-setup-template.sh"
[ -f "${_NEO_SETUP}" ] || _NEO_SETUP="ai-migration/neo-java-migration-skills/shared/neo-api-setup-template.sh"
source "${_NEO_SETUP}" || exit 1
```

**Fetch Neo app list:**

```bash
[ -n "${NEO_KEYSTORE_BASE}" ] || {
  echo "ERROR: NEO API env vars not set — collect telemetry consent and source neo-api-setup-template.sh before making any NEO API calls." >&2
  exit 1
}

_SCRIPT="assets/scripts/migrate_destinations_and_keystores.py"
[ -f "${_SCRIPT}" ] || { echo "ERROR: Migration script not found: ${_SCRIPT}" >&2; exit 1; }

mkdir -p "${MIGRATION_DIR}"

TOKEN="${TOKEN}" REGION_HOST="${REGION_HOST}" SUBACCOUNT="${SUBACCOUNT}" \
  CF_SUBACCOUNT_GUID="${CF_SUBACCOUNT_GUID}" \
  MIGRATION_DIR="${MIGRATION_DIR}" \
  python3 "${_SCRIPT}" --list-apps
```

**Auto-match by name:** Compare the Neo app names against the CF app names (from `cf apps`). For any Neo app whose name exactly matches a CF app name, pre-fill that mapping and ask the user to confirm in a single question, e.g.:

> The following Neo apps have a CF app with the same name. Should I map them automatically?
>
>   example → example
>   neoauthnauthzdemo2 → neoauthnauthzdemo2
>
> Confirm? (yes/no)

Only ask about unmapped apps individually. Present like this:

```
Found N Neo apps. For each, which CF app should receive its destinations?
(Run `cf apps` to see available CF apps. Type "skip" to skip a Neo app.)

  <neo-app1> → ?   (no matching CF app found)
  <neo-app2> → ?
```

**Do not run the migration script until the user has confirmed all mappings.**

## Migration

Once all inputs are confirmed, tell the user: "Running the migration script..." and then run:

```bash
_SCRIPT="assets/scripts/migrate_destinations_and_keystores.py"
[ -f "${_SCRIPT}" ] || { echo "ERROR: Migration script not found: ${_SCRIPT}" >&2; exit 1; }

mkdir -p "${MIGRATION_DIR}"

TOKEN="${TOKEN}" REGION_HOST="${REGION_HOST}" SUBACCOUNT="${SUBACCOUNT}" \
  CF_SUBACCOUNT_GUID="${CF_SUBACCOUNT_GUID}" \
  MIGRATION_DIR="${MIGRATION_DIR}" \
  APP_MAPPING='{"<neo-app1>": "<cf-app1>", "<neo-app2>": "<cf-app1>"}' \
  MIGRATE_KEYSTORES="${_KEYSTORES_CONSENT}" \
  MIGRATE_OAUTH_CREDENTIALS="${_OAUTH_CREDENTIALS_CONSENT}" \
  python3 "${_SCRIPT}"
```

Do NOT show the full command to the user — only the short status message above.

> **Note:** No files are written to disk. All Neo data is fetched into memory and posted directly to CF. The user's credentials and destination content are never stored locally.

## What gets migrated

| Neo Level | Item | CF Target |
|-----------|------|-----------|
| Account | Destinations | `subaccountDestinations` (instance: `<account>-account-destinations`, overridable via `ACCOUNT_INSTANCE_NAME`) |
| Account | Keystores | `subaccountCertificates` (same instance) |
| App | Destinations | `instanceDestinations` (per-app instance: `<neo-app>-destination`) |
| App | Keystores | `instanceCertificates` (same per-app instance) |

### Authentication type mapping

| Neo | CF | Notes |
|-----|----|-------|
| `NoAuthentication` | `NoAuthentication` | Direct copy |
| `BasicAuthentication` | `BasicAuthentication` | Credentials not migrated — re-enter manually in BTP Cockpit |
| `OAuth2ClientCredentials` | `OAuth2ClientCredentials` | Credentials not migrated unless mTLS token retrieval (`tokenService.KeyStorePassword`) is present and user consented — see security disclaimer above |
| `AppToAppSSO` | `OAuth2UserTokenExchange` | Auth type changed + credentials injected |
| `ClientCertificateAuthentication` | `ClientCertificateAuthentication` | Direct copy |
| `PrincipalPropagation` | `PrincipalPropagation` | Direct copy (requires Cloud Connector) |
| `InternalSystemAuthentication` | — | Neo-specific — skipped automatically, review manually |

## After migration

Present a summary:

```
=== Migration Summary ===
Account destinations : N uploaded, N skipped (InternalSystem)
Account keystores    : N uploaded
Apps migrated        : N
  <neo-app> → <cf-app> (<instance-name>)
    Destinations: N uploaded, N skipped
    Keystores   : N uploaded

Manual review required:
  - InternalSystemAuthentication destinations: N — no CF equivalent, handle manually
```

## Common Issues

### Token expired mid-run
The Platform API token is valid for 25 minutes. If it expires, ask the user for a fresh token and re-run. The script is idempotent — already-uploaded items return HTTP 409 and are skipped.

### `cf create-service` quota exceeded
The CF space may have a limit on Destination Service instances. Check with `cf marketplace -e destination`.

### CF app not found in mapping
Run `cf apps` to list available apps. The target CF app must exist before the script can bind a Destination Service instance to it.

### No account-level items
Normal — not all Neo subaccounts have account-level destinations. The script skips the account-level CF instance creation in that case.

### Destination Service instance stuck in `create in progress`
If a previous run was interrupted mid-way, a Destination Service instance may be left in a non-terminal state. In this case the script will skip `cf create-service` (the instance already exists) but immediately attempt to create a service key, which will fail with a confusing error. To recover, wait for the instance to reach a terminal state (`cf service <instance-name>`), or delete it manually (`cf delete-service <instance-name> -f`) and re-run.
