---
name: subaccount-trust-export
description: >-
  Export and analyze trust configuration from a Neo subaccount. Fetches the complete
  SAML trust config via the Neo Trust Management REST API, classifies each identity
  provider as IAS or third-party, and saves a structured JSON report to
  .migration/neo-trust-config.json with migration notes for CF. Invoke when migrating
  trust/IdP configuration from Neo to Cloud Foundry, or when you need to understand
  what identity providers are configured in a Neo subaccount.
disable-model-invocation: false
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(curl *), Bash(python3 *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *)
---

# Subaccount Trust Export

Export and analyze Neo subaccount trust configuration for Cloud Foundry migration.

## Purpose

This skill exports the complete SAML trust configuration from a Neo subaccount using the Trust Management REST API. It:

- Fetches the full trust config (local service provider + all identity providers)
- Classifies each IdP as **IAS** (SAP Cloud Identity) or **third-party**
- Generates per-IdP migration notes highlighting what needs manual attention in CF
- Produces a migration complexity summary
- Saves structured output to `$MIGRATION_DIR/neo-trust-config.json` for downstream skills

This skill is **read-only** — it does NOT create trust in CF. A future `subaccount-trust-import` skill will consume the output.

> **Scope: Application IdPs only.** This skill exports the identity providers used for **end-user authentication to applications** (the `applicationIdentityProviders` section of the Neo SAML trust config). These can be the default SAP identity provider (`accounts.sap.com`), an IAS tenant, or a third-party IdP (e.g. Microsoft Azure AD). This is distinct from the **platform IdP**, which governs access to platform tools (BTP cockpit, CLI) and is configured separately — that is out of scope for this skill.

## Prerequisites

Before running this skill, ensure:

1. **Tools available on PATH**:
   - `curl` (required)
   - `jq` (recommended — install with `choco install jq` on Windows or `brew install jq` on macOS; if unavailable, the skill will parse JSON directly)

2. **Neo Platform API OAuth client** created in the Neo subaccount cockpit with the `hcp.readTrustSettings` scope — OR a pre-issued Bearer token (valid for 25 minutes)

3. **Neo subaccount technical name** (not the display name — find it in Neo cockpit > Overview)

4. **Neo region host** (e.g., `eu1.hana.ondemand.com`, `us1.hana.ondemand.com`)

No dependency on other skills — this skill can run standalone.

## Input Resolution

### Step 0: Resolve Required Parameters

**0a. Determine the migration directory:**

```bash
# If running inside an app migration copy, use its .migration/ folder.
# Otherwise use a temp directory — subaccount migration is not tied to any single app repo.
if [ -f .migration/cf-migration-config.json ]; then
  MIGRATION_DIR="$(pwd)/.migration"
else
  MIGRATION_DIR="${TMPDIR:-/tmp}/neo-subaccount-migration"
  mkdir -p "$MIGRATION_DIR"
fi
echo "Migration directory: $MIGRATION_DIR"
```

**0b. Check for existing config file:**

```bash
if [ -f "$MIGRATION_DIR/neo-migration-config.json" ]; then
  cat "$MIGRATION_DIR/neo-migration-config.json"
fi
```

Read the following fields if present:
- `neoSubaccount` → subaccount technical name
- `neoRegionHost` → region host
- `auth.method` → `"clientCredentials"` or `"bearerToken"`
- `auth.clientId` + `auth.clientSecret` → if method is clientCredentials
- `auth.bearerToken` → if method is bearerToken

**0c. Check the user's prompt** for any values provided inline.

**0d. If any required value is still missing**, invoke the **`subaccount-migration-orchestrator`** skill to collect all inputs and write `$MIGRATION_DIR/neo-migration-config.json`, then return here and continue from Step 1.

> Do not ask the user for individual values directly — the orchestrator owns input collection and ensures all three export skills share a consistent config file.

**0e. If `$MIGRATION_DIR` is inside a git repo, ensure it is in `.gitignore`:**

```bash
if [[ "$MIGRATION_DIR" != /tmp* ]]; then
  if [ -f .gitignore ]; then
    grep -q '^\\.migration' .gitignore 2>/dev/null || echo '.migration/' >> .gitignore
    grep -q '^\.migration' .gitignore 2>/dev/null || echo '.migration/' >> .gitignore

    echo '.migration/' > .gitignore
  fi
fi
```

## Step 1: Obtain Bearer Token

**If using client credentials** (auth.method = "clientCredentials"):

```bash
export MSYS_NO_PATHCONV=1

TOKEN_RESPONSE=$(curl -s -X POST \
  "https://api.${REGION_HOST}/oauth2/apitoken/v1?grant_type=client_credentials" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "${CLIENT_ID}:${CLIENT_SECRET}")
```

Extract the token:

```bash
BEARER_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
```

If `jq` is not available, read the response and extract `access_token` from the JSON manually.

**Verify the token was obtained:**

If `BEARER_TOKEN` is empty, check the response for error details:

| HTTP Status | Cause | Solution |
|-------------|-------|----------|
| 401 | Invalid client_id or client_secret | Verify Platform API OAuth client credentials in Neo cockpit |
| 400 | Bad request | Ensure the OAuth client is properly configured with `hcp.readTrustSettings` scope |
| Network error | Cannot reach API | Verify region host is correct (e.g., `eu1.hana.ondemand.com`) and network connectivity |

If the token fetch fails, show the error to the user and stop.

**If using pre-issued token** (auth.method = "bearerToken"):

Skip this step — use the token provided by the user. Warn: "Note: Your Bearer token expires 25 minutes after it was issued."

## Step 2: Fetch Trust Configuration

```bash
export MSYS_NO_PATHCONV=1

TRUST_RESPONSE=$(curl -s -w "\n%{http_code}" \
  "https://apissecurity.${REGION_HOST}/trust/v2/accounts/${SUBACCOUNT}" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")

# Separate body and HTTP status code
HTTP_STATUS=$(echo "$TRUST_RESPONSE" | tail -1)
TRUST_BODY=$(echo "$TRUST_RESPONSE" | sed '$d')
```

On Windows, if parsing fails due to `\r\n` line endings:
```bash
TRUST_BODY=$(echo "$TRUST_RESPONSE" | sed '$d' | tr -d '\r')
HTTP_STATUS=$(echo "$TRUST_RESPONSE" | tail -1 | tr -d '\r')
```

**Verify the response:**

| HTTP Status | Cause | Solution |
|-------------|-------|----------|
| 200 | Success | Proceed to parsing |
| 401 | Token expired or invalid | If using pre-issued token, ask user for a fresh one. If using client credentials, retry token fetch |
| 403 | Insufficient scopes | Ensure OAuth client has `hcp.readTrustSettings` scope |
| 404 | Subaccount not found | Use the **technical name** (not display name). Find it in Neo cockpit > subaccount Overview page |
| 500 | Server error | Wait a moment and retry. If persistent, check SAP system status |

If the status is not 200, show the error and response body to the user and stop.

**Save the raw response** for reference:

```bash
echo "$TRUST_BODY" > $MIGRATION_DIR/neo-trust-raw-response.json
```

## Step 3: Parse and Classify Identity Providers

Read the trust configuration response and classify each identity provider.

**Classification logic:**

For each IdP in `applicationIdentityProviders.identityProviders[]`:

1. If `type` equals `"SCI"` → classify as **IAS**, use `host` field as `iasHost`
2. Else if `host` contains `.accounts.ondemand.com`, `.accounts.cloud.sap`, or `.accounts400.ondemand.com` → classify as **IAS**, use `host` as `iasHost`
3. Else if `ssoUrl` or `sloUrl` contains `.accounts.ondemand.com`, `.accounts.cloud.sap`, or `.accounts400.ondemand.com` → classify as **IAS**, extract the hostname from the URL as `iasHost` (e.g. `https://mytenant.accounts.ondemand.com/saml2/idp/sso` → `mytenant.accounts.ondemand.com`)
4. Otherwise → classify as **ThirdParty**, set `iasHost` to `null`

> **Why URL-based detection matters:** Some Neo subaccounts have IAS IdPs where the `type` field is absent or not `SCI`, and the `host` field may also be missing. In these cases the IAS tenant host can still be reliably extracted from the SSO or SLO URLs, which always contain the IAS tenant hostname as the domain.

**Extract per-IdP data:**

For each IdP, extract and normalize:
- `name`, `enabled` (convert string `"true"`/`"false"` to boolean)
- `ssoUrl`, `ssoBinding`
- `signatureAlgorithm`
- `onlyForIdpInitiatedSSO`, `onlyForOAuthSAMLBearerFlow` (convert to boolean)
- `userIdSource` (object with `type` and optional `value`)
- `assertionBasedAttributes[]` (keep full array)
- `defaultAttributes[]` (keep full array)
- `assertionBasedGroups[]` (keep full array including rules)
- `defaultGroups[]` (keep full array)
- `signingCertificate` — include **only for ThirdParty IdPs** (it is the IdP's public certificate, required to configure trust in CF); omit for IAS IdPs (connection is established automatically via `btp create security/trust`)

**SECURITY: Do NOT include** `signingKey` or `signingCertificate` from `localServiceProvider` in the output (these are the SP's private key and certificate).

## Step 4: Generate Migration Notes

For each IdP, generate a `cfMigrationNotes` array of human-readable strings:

| Condition | Migration Note |
|-----------|---------------|
| `enabled` is `false` | "Currently disabled in Neo — skip unless needed in CF" |
| `userIdSource.type` is `"Attribute"` | "Custom user ID source (Attribute: {value}) — must be configured in IAS" |
| `assertionBasedAttributes` is non-empty | "Has {N} assertion-based attribute mappings that need IAS configuration" |
| `defaultAttributes` is non-empty | "Has {N} default attributes — configure in IAS" |
| `assertionBasedGroups` is non-empty | "Has assertion-based group rules ({N} groups) that need manual IAS configuration" |
| `defaultGroups` is non-empty | "Has {N} default groups — map to role collections in CF" |
| `onlyForIdpInitiatedSSO` is `true` | "IdP-initiated SSO only — verify CF/IAS support for this flow" |
| `onlyForOAuthSAMLBearerFlow` is `true` | "OAuth SAML Bearer flow only — configure in CF destination service" |
| `signatureAlgorithm` is `"SHA-1"` | "Uses SHA-1 signature algorithm — consider upgrading to SHA-256" |
| Type is `ThirdParty` | "Third-party IdP — must be manually registered in IAS or CF trust configuration" |
| Type is `IAS` | "IAS tenant ({iasHost}) — can be connected to CF subaccount via btp CLI or XSUAA API" |

## Step 5: Build and Save Output

Construct the output JSON with the following structure and save using the Write tool:

```json
{
  "sourceSubaccount": "<subaccount technical name>",
  "sourceRegion": "<region host>",
  "exportTimestamp": "<ISO 8601 timestamp>",
  "configurationType": "<from response, e.g. 'Custom'>",
  "localServiceProvider": {
    "name": "<SP name>",
    "principalPropagationEnabled": "<true|false>",
    "forceAuthenticationEnabled": "<true|false>",
    "useCustomApplicationDomains": "<true|false>",
    "defaultIdentityProviderName": "<default IdP name>"
  },
  "identityProviders": [
    {
      "name": "<idp-name>",
      "type": "IAS|ThirdParty",
      "iasHost": "<host or null>",
      "enabled": true,
      "ssoUrl": "<url>",
      "ssoBinding": "HTTP-POST|HTTP-REDIRECT",
      "signatureAlgorithm": "SHA-256|SHA-1",
      "onlyForIdpInitiatedSSO": false,
      "onlyForOAuthSAMLBearerFlow": false,
      "userIdSource": {
        "type": "Subject|Attribute",
        "value": "<attribute-name or null>"
      },
      "signingCertificate": "<base64-DER — only present for ThirdParty IdPs, omitted for IAS>",
      "hasAssertionBasedAttributes": true,
      "assertionBasedAttributes": [],
      "hasDefaultAttributes": false,
      "defaultAttributes": [],
      "hasAssertionBasedGroups": true,
      "assertionBasedGroups": [],
      "hasDefaultGroups": false,
      "defaultGroups": [],
      "cfMigrationNotes": []
    }
  ],
  "migrationSummary": {
    "totalIdPs": 0,
    "iasIdPs": 0,
    "thirdPartyIdPs": 0,
    "idpsWithCustomAttributes": 0,
    "idpsWithGroupRules": 0,
    "requiresManualSteps": false,
    "manualSteps": []
  }
}
```

**Save the file:**

```bash
# Directory should already exist from Step 0
# Use the Write tool to save to $MIGRATION_DIR/neo-trust-config.json
```

**Build the `migrationSummary`:**

- Count totals by type
- `requiresManualSteps` = `true` if `manualSteps` is non-empty
- Aggregate unique manual steps:
  - If any third-party IdPs: "Re-enter IdP signing certificates in CF trust configuration" (IAS IdPs do not require this — certificates are handled automatically when connecting the IAS tenant via btp CLI)
  - If any IdP has assertion-based attributes: "Configure assertion-based attribute mappings in IAS admin console"
  - If any IdP has assertion-based groups: "Configure assertion-based group rules in IAS admin console"
  - If any IdP has default groups: "Map default groups to role collections in CF"
  - If any IdP is OAuth SAML Bearer only: "Set up OAuth SAML Bearer trust in CF destination service"
  - If any IdP uses SHA-1: "Review SHA-1 signature algorithms — consider upgrading to SHA-256"
  - If any third-party IdPs: "Manually register third-party identity providers in IAS or CF trust"

## Step 6: Display Summary

After saving the output file, display a summary to the user:

```
Trust Configuration Export Complete
====================================
Source: <subaccount> (<region>)
Configuration Type: <type>
Default IdP: <name>
Principal Propagation: <enabled/disabled>

Identity Providers: <total>
  IAS:         <count>
  Third-Party: <count>

[For each IdP:]
  - <name> (<IAS|ThirdParty>) [ENABLED|DISABLED]
    Migration notes:
    - <note 1>
    - <note 2>

Manual Steps Required: <yes/no>
[If yes, list steps]

Output saved to: $MIGRATION_DIR/neo-trust-config.json
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `neo-migration-config.json` | `$MIGRATION_DIR` | Shared input config — Neo subaccount details and auth credentials |
| `neo-trust-config.json` | `$MIGRATION_DIR` | Output — parsed trust config with IdP classifications and migration notes |
| `neo-trust-raw-response.json` | `$MIGRATION_DIR` | Raw API response (for debugging) |

## Verification

1. Verify output file exists:
   ```bash
   ls -la $MIGRATION_DIR/neo-trust-config.json
   ```

2. Verify JSON is valid:
   ```bash
   jq . $MIGRATION_DIR/neo-trust-config.json
   ```
   Or if jq is unavailable:
   ```bash
   python3 -c "import json; json.load(open('$MIGRATION_DIR/neo-trust-config.json'))"
   ```

3. Verify migration summary:
   ```bash
   jq '.migrationSummary' $MIGRATION_DIR/neo-trust-config.json
   ```

4. List all IdPs with classification:
   ```bash
   jq '.identityProviders[] | {name, type, enabled}' $MIGRATION_DIR/neo-trust-config.json
   ```

## Common Issues

### Issue: "Failed to obtain OAuth token"
**Cause:** Invalid Platform API OAuth client credentials.
**Solution:** Verify the client ID and secret in Neo cockpit > OAuth. Ensure the OAuth client has the `hcp.readTrustSettings` scope assigned.

### Issue: "Trust API returned 404"
**Cause:** Wrong subaccount name — the API requires the **technical name**, not the display name.
**Solution:** Find the technical name in Neo cockpit > subaccount Overview page. It is typically lowercase with no spaces.

### Issue: "Trust API returned 401"
**Cause:** Bearer token expired (25-minute validity) or invalid.
**Solution:** If using a pre-issued token, obtain a fresh one. If using client credentials, verify them and retry.

### Issue: "Trust API returned 403"
**Cause:** OAuth client does not have `hcp.readTrustSettings` scope.
**Solution:** In Neo cockpit, edit the Platform API OAuth client and add the `hcp.readTrustSettings` scope.

### Issue: curl URL path munging on Windows
**Cause:** Git Bash on Windows converts `/paths` to Windows paths in URLs.
**Solution:** Set `export MSYS_NO_PATHCONV=1` before running curl commands (this skill does this automatically).

### Issue: "jq: command not found"
**Cause:** jq is not installed.
**Solution:** Install jq (`choco install jq` on Windows, `brew install jq` on macOS). The skill will still work by parsing JSON directly, but jq makes verification easier.

### Issue: Empty identity providers array
**Cause:** The subaccount uses the default SAP ID Service trust configuration with no custom IdPs.
**Solution:** This is valid — the output file will contain an empty `identityProviders` array. No further trust migration is needed.

## Next Steps

After completing this skill:

- **[subaccount-trust-import](../subaccount-trust-import/SKILL.md)** — reads `$MIGRATION_DIR/neo-trust-config.json` and creates SAML trust for IAS IdPs in the CF subaccount via the XSUAA apiaccess REST API
- **[authentication-xsuaa](../authentication-xsuaa/SKILL.md)** — configure XSUAA for the application; can be informed by the IdP classifications in the trust export
- **[subaccount-roles-export](../subaccount-roles-export/SKILL.md)** — export Neo roles and map them to CF role collections; group rules from the trust export can inform role collection design
