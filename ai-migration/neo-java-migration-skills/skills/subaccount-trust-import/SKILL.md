---
name: subaccount-trust-import
description: >-
  Import application identity provider trust configuration into a CF subaccount.
  Reads the structured export from .migration/neo-trust-config.json (produced by
  subaccount-trust-export) and creates SAML trust for IAS IdPs via the XSUAA
  apiaccess REST API (fetching IAS SAML metadata automatically). Also creates role
  collections and assigns assertion-based group rules (equals operations) via BTP CLI.
  Generates a manual checklist for third-party IdPs, attribute mappings, regexp group
  rules, and the IAS-side SP registration that must be done manually. Invoke after
  subaccount-trust-export.
disable-model-invocation: false
allowed-tools: Read, Write, Glob, Bash(curl *), Bash(python3 *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *)
---

# Subaccount Trust Import

Import Neo application IdP trust configuration into a Cloud Foundry subaccount.

## Purpose

This skill reads the structured trust export produced by `subaccount-trust-export` and creates the equivalent trust configuration in a CF subaccount. It:

- Acquires XSUAA API credentials via `btp security api-credential create`
- Fetches the IAS SAML metadata for each IAS IdP (public endpoint, no auth required)
- Creates SAML trust in XSUAA for each IAS IdP via the XSUAA `apiaccess` REST API (`POST /sap/rest/identity-providers`)
- Creates role collections for assertion-based group rules and assigns `equals` attribute conditions via BTP CLI
- Generates a manual checklist for everything that cannot be automated
- Saves an import report to `.migration/neo-trust-import-report.json`

> **Scope: Application IdPs only.** This skill mirrors the scope of `subaccount-trust-export` — it configures identity providers used for end-user authentication to applications. Platform IdP configuration (used for BTP cockpit and CLI access) is out of scope.

> **IAS IdPs only — automated via XSUAA SAML trust API.** SAML trust with IAS tenants is created via the internal XSUAA `apiaccess` REST API, not the `btp` CLI. The `btp create security/trust` command handles OIDC only; this skill uses the SAML path which is what Neo uses. Third-party IdPs (e.g. Microsoft Azure AD) cannot be directly registered as application IdPs in a CF subaccount — they must be configured as corporate IdPs inside an IAS tenant, which then acts as a proxy. This skill will flag all third-party IdPs as manual steps.

> **IAS-side SP registration is manual.** After this skill creates SAML trust in XSUAA (registering IAS as the IdP), you must also register XSUAA as a Service Provider (SP) in the IAS admin console. This skill will fetch the XSUAA SAML metadata URL and provide exact instructions, but cannot automate the IAS-side configuration.

## Prerequisites

1. **`subaccount-trust-export` must have run** — `.migration/neo-trust-config.json` must exist

2. **BTP CLI installed and logged in:**
   ```bash
   btp target
   ```
   If not logged in, instruct the user to run:
   ```bash
   btp login --sso
   ```
   Do not prompt for credentials — only SSO login is supported to avoid credentials passing through the LLM.

3. **`curl` and `jq` available on PATH** — used for REST API calls and JSON parsing

4. **Target CF subaccount ID** — the GUID of the CF subaccount to import trust into (find in BTP cockpit > subaccount Overview, or via `btp list accounts/subaccount`)

No Neo credentials are required for this skill — it only reads from the local export file and writes to CF via the XSUAA REST API and BTP CLI.

## Input Resolution

### Step 0: Resolve Required Parameters

**0a. Read the export file:**

```bash
cat .migration/neo-trust-config.json
```

If the file does not exist, stop and instruct the user to run `subaccount-trust-export` first.

**0b. Check for existing CF config:**

```bash
if [ -f .migration/cf-migration-config.json ]; then
  cat .migration/cf-migration-config.json
fi
```

Read `cfSubaccountId` and `cfRegion` if present.

**0c. Check the user's prompt** for any values provided inline.

**0d. Ask the user** for any values still missing:

1. "What is the GUID of the target CF subaccount?" (find it in BTP cockpit > subaccount Overview, or run `btp list accounts/subaccount`)

**0e. Save CF config** if not already present, using the template from [assets/cf-trust-config-template.json](assets/cf-trust-config-template.json).

## Step 1: Validate Sessions and Input

**1a. Verify BTP CLI session:**

```bash
btp target
```

If this fails or shows no target, stop and tell the user:
> "No active BTP CLI session found. Please run `btp login --sso` in your terminal, then re-invoke this skill."

**1b. Validate the export file has IdPs to process:**

Read `identityProviders[]` from the export. If empty, inform the user:
> "The Neo subaccount uses the default SAP identity provider (accounts.sap.com) with no custom IdPs configured. No trust import is needed."

Then stop — this is a successful no-op.

**1c. Check configurationType:**

Read `configurationType` from the export:
- `"Custom"` — subaccount has custom IdP configuration; proceed with import
- `"Default"` or absent — subaccount uses the platform default IdP; no import needed, stop

## Step 2: Classify IdPs for Processing

For each IdP in `identityProviders[]`, classify into one of three queues:

| Condition | Queue |
|-----------|-------|
| `enabled == false` | **skip** — disabled in Neo, not imported |
| `type == "IAS"` | **automate** — create SAML trust via XSUAA apiaccess API |
| `type == "ThirdParty"` | **manual** — cannot automate; add to checklist |

Keep track of which IdP was the `defaultIdentityProviderName` in the export's `localServiceProvider` — it will need to be set as default after import (manually, in BTP Cockpit).

## Step 3: Acquire XSUAA API Credentials

The XSUAA SAML trust API requires credentials from a BTP `security/api-credential` — this is an XSUAA service instance with the `apiaccess` plan, managed at the BTP account level.

**3a. Check for existing credentials:**

```bash
btp --format json list security/api-credential --subaccount "${CF_SUBACCOUNT_ID}" 2>/dev/null | \
  jq -r '.[] | select(.name == "migration-api-credential") | .name'
```

**3b. Delete stale credential if it exists** (to ensure fresh secrets):

```bash
btp delete security/api-credential migration-api-credential \
  --subaccount "${CF_SUBACCOUNT_ID}" \
  --confirm true 2>/dev/null || true
```

**3c. Create a fresh credential:**

```bash
API_CRED=$(btp --format json create security/api-credential \
  --name migration-api-credential \
  --subaccount "${CF_SUBACCOUNT_ID}")
```

Extract fields from the JSON output:
```bash
CLIENT_ID=$(echo "$API_CRED" | jq -r '.clientid')
CLIENT_SECRET=$(echo "$API_CRED" | jq -r '.clientsecret')
TOKEN_URL=$(echo "$API_CRED" | jq -r '.tokenurl')
API_URL=$(echo "$API_CRED" | jq -r '.apiurl')
```

If `jq` is unavailable, parse the JSON fields manually from the output.

> **Security note:** These credentials are temporary and will be deleted at the end of this skill (Step 8). They are valid only for this migration session.

**3d. Acquire a Bearer token from XSUAA:**

```bash
export MSYS_NO_PATHCONV=1

TOKEN_RESPONSE=$(curl -s -X POST "${TOKEN_URL}" \
  -u "${CLIENT_ID}:${CLIENT_SECRET}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=${CLIENT_ID}&grant_type=client_credentials")

BEARER_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
```

If `BEARER_TOKEN` is empty, show the error response and stop.

**3e. Derive the XSUAA tenant URL** (needed for XSUAA SP metadata):

The XSUAA tenant URL is the `tokenurl` with the `/oauth/token` path stripped:
```bash
XSUAA_TENANT_URL=$(echo "$TOKEN_URL" | sed 's|/oauth/token||')
```

For example: `https://my-subdomain.authentication.eu10.hana.ondemand.com`

## Step 4: Create SAML Trust for IAS IdPs

For each IdP in the **automate** queue:

### 4a. Fetch IAS SAML metadata

The IAS SAML metadata is publicly available — no authentication required:

```bash
IAS_HOST="${idp.iasHost}"  # e.g. mytenant.accounts.ondemand.com

export MSYS_NO_PATHCONV=1

IAS_METADATA=$(curl -s "https://${IAS_HOST}/saml2/metadata")
```

Verify the response is valid XML (starts with `<?xml` or `<md:EntitiesDescriptor` or `<EntityDescriptor`). If not, mark this IdP as failed and continue.

### 4b. Build the SAML trust payload

Construct the JSON payload for the XSUAA trust API:

```bash
IDP_NAME="${idp.name}"
TENANT_NAME=$(echo "$IAS_HOST" | cut -d. -f1)  # e.g. "mytenant" from "mytenant.accounts.ondemand.com"
ORIGIN_KEY="${TENANT_NAME}.migrated"

PAYLOAD=$(cat <<EOF
{
  "name": "Neo migrated IAS Tenant",
  "type": "saml",
  "originKey": "${ORIGIN_KEY}",
  "isActive": true,
  "config": {
    "idpEntityAlias": "${IAS_HOST}",
    "metaDataLocation": $(echo "$IAS_METADATA" | python3 -c "import sys, json; print(json.dumps(sys.stdin.read()))"),
    "addShadowUserOnLogin": true,
    "attributeMappings": {
      "given_name": "first_name",
      "family_name": "last_name",
      "email": "mail"
    }
  }
}
EOF
)
```

> **Note on `metaDataLocation`**: The IAS metadata XML must be embedded as a JSON string (with quotes escaped). The `python3 -c` call above handles this encoding. If python3 is unavailable, use `jq -Rs .` instead:
> ```bash
> META_ENCODED=$(echo "$IAS_METADATA" | jq -Rs .)
> ```
> Then embed `$META_ENCODED` directly in the JSON without the outer quotes from the `cat` heredoc.

### 4c. POST trust to XSUAA

```bash
export MSYS_NO_PATHCONV=1

TRUST_RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "${API_URL}/sap/rest/identity-providers" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${PAYLOAD}")

HTTP_STATUS=$(echo "$TRUST_RESPONSE" | tail -1 | tr -d '\r')
RESPONSE_BODY=$(echo "$TRUST_RESPONSE" | sed '$d')
```

**Handle responses:**

| HTTP Status | Action |
|-------------|--------|
| `200` or `201` | Mark as `imported` in the report |
| `409 Conflict` | Mark as `already_configured` — treat as success (trust already exists with this originKey) |
| Any other status | Mark as `failed`, capture `$RESPONSE_BODY` as error, continue with remaining IdPs |

### 4d. Refresh the Bearer token if needed

The token expires after ~12 hours but migrations are typically fast. If you receive a `401` on the trust API call, re-fetch the token (Step 3d) and retry once.

## Step 5: Assign Assertion-Based Group Rules

For each successfully imported IAS IdP, check its `assertionBasedGroups` array.

For each group entry where at least one rule has `operation == "equals"`:

**5a. Create a role collection for the group** (skip if it already exists):

```bash
btp create security/role-collection "${GROUP_NAME}-Group" \
  --subaccount "${CF_SUBACCOUNT_ID}" 2>/dev/null || true
```

**5b. Assign each `equals` rule as an attribute condition:**

```bash
btp assign security/role-collection "${GROUP_NAME}-Group" \
  --to-user-attribute "${ASSERTION_ATTRIBUTE}" \
  --attribute-value "${VALUE}" \
  --of-idp "${ORIGIN_KEY}" \
  --subaccount "${CF_SUBACCOUNT_ID}"
```

Where:
- `GROUP_NAME` = `assertionBasedGroups[].group`
- `ASSERTION_ATTRIBUTE` = `assertionBasedGroups[].rules[].assertionAttribute`
- `VALUE` = `assertionBasedGroups[].rules[].value`
- `ORIGIN_KEY` = the `originKey` used when creating the trust (e.g. `mytenant.migrated`)

Rules with `operation == "regexp"` cannot be automated — add them to the manual checklist.

> **Note:** The flag for specifying the IdP origin in role-collection assignment is `--of-idp`, not `--origin`.

## Step 6: Build Manual Checklist

Collect all items that could not be automated:

| Condition | Manual Step |
|-----------|-------------|
| Any IAS IdP was imported | "**Register XSUAA as Service Provider in IAS** — for each imported IAS IdP, open the IAS admin console (https://{iasHost}/admin), navigate to Applications > Create Application > Upload Metadata, and upload XSUAA's SP metadata from: `{XSUAA_TENANT_URL}/saml/metadata`" |
| Any IAS IdP was imported | "**Set default IdP** — the `btp` CLI cannot set a default IdP. If `{defaultIdentityProviderName}` should be the default, go to BTP Cockpit → subaccount → Security → Trust Configuration → find `{defaultIdentityProviderName}` → Edit → enable 'Default Identity Provider'." |
| Any `ThirdParty` IdP | "**{name}** is a third-party IdP and cannot be directly configured in a CF subaccount. To migrate it: (1) Configure it as a corporate IdP inside your IAS tenant via the IAS admin console, (2) Ensure the IAS tenant is trusted in this subaccount, (3) Route authentication through IAS." |
| Any IdP with `assertionBasedAttributes` non-empty | "**{name}**: {N} assertion-based attribute mapping(s) must be configured in the IAS admin console under Applications > {app} > Authentication > Attributes." |
| Any IdP with `defaultAttributes` non-empty | "**{name}**: {N} default attribute(s) must be configured in the IAS admin console under Applications > {app} > Authentication > Default Attributes." |
| Any `regexp` group rules | "**{name}** / group **{group}**: assertion-based group rule with `regexp` operation cannot be automated — configure it manually in the IAS admin console under Applications > {app} > Authentication > Groups." |
| Any IdP with `onlyForIdpInitiatedSSO == true` | "**{name}**: IdP-initiated SSO requires explicit configuration in IAS. Verify the application's SSO endpoint is registered and the flow is enabled." |
| Any IdP with `signatureAlgorithm == "SHA-1"` | "**{name}**: uses SHA-1 signature algorithm. Consider upgrading to SHA-256 in the IAS admin console." |
| Any disabled IdP that was skipped | "**{name}**: was disabled in Neo and was not imported. Enable it manually in CF trust configuration if needed." |

## Step 7: Save Import Report

Save to `.migration/neo-trust-import-report.json` using the Write tool:

```json
{
  "targetSubaccount": "<CF subaccount GUID>",
  "importTimestamp": "<ISO 8601 timestamp>",
  "sourceFile": ".migration/neo-trust-config.json",
  "xsuaaTenantUrl": "<XSUAA_TENANT_URL>",
  "imported": [
    {
      "name": "<idp-name>",
      "iasHost": "<host>",
      "originKey": "<tenant>.migrated",
      "groupCollectionsCreated": ["<group>-Group"]
    }
  ],
  "alreadyConfigured": [
    {
      "name": "<idp-name>",
      "iasHost": "<host>",
      "originKey": "<tenant>.migrated"
    }
  ],
  "skipped": [
    {
      "name": "<idp-name>",
      "reason": "disabled in Neo"
    }
  ],
  "failed": [
    {
      "name": "<idp-name>",
      "error": "<error message>"
    }
  ],
  "manualSteps": [
    "<step 1>",
    "<step 2>"
  ],
  "requiresManualSteps": true
}
```

## Step 8: Clean Up API Credentials

Delete the temporary API credential created in Step 3:

```bash
btp delete security/api-credential migration-api-credential \
  --subaccount "${CF_SUBACCOUNT_ID}" \
  --confirm true
```

Inform the user: "Temporary XSUAA API credentials have been deleted."

If deletion fails, warn the user:
> "Warning: Could not delete the temporary API credential `migration-api-credential`. Please delete it manually in BTP Cockpit → subaccount → Security → API credentials, or run: `btp delete security/api-credential migration-api-credential --subaccount ${CF_SUBACCOUNT_ID} --confirm true`"

## Step 9: Display Summary

After saving the report and cleaning up, display a summary to the user:

```
Trust Import Complete
=====================
Target subaccount: <ID>
Source: .migration/neo-trust-config.json

Results:
  Imported:           <count>
  Already configured: <count>
  Skipped (disabled): <count>
  Failed:             <count>

[For each imported IdP:]
  ✓ <name> (<iasHost>) — origin key: <tenant>.migrated
    Role collections created: <list or "none">

[For each failed IdP:]
  ✗ <name>: <error>

Manual Steps Required: <yes/no>
[If yes:]
  The following items could not be automated and require manual configuration:
  1. <step>
  2. <step>
  ...

  XSUAA SP metadata URL (needed for IAS registration):
  <XSUAA_TENANT_URL>/saml/metadata

Report saved to: .migration/neo-trust-import-report.json
Temporary API credentials: deleted
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `neo-trust-config.json` | `.migration/` | Input — trust export from `subaccount-trust-export` |
| `cf-migration-config.json` | `.migration/` | CF target subaccount details |
| `neo-trust-import-report.json` | `.migration/` | Output — import results and manual checklist |

## Verification

After the skill completes, verify trust was created via BTP CLI:

```bash
btp --format json list security/trust --subaccount <CF_SUBACCOUNT_ID>
```

Verify via XSUAA API (using a fresh token if needed):

```bash
curl -s "${API_URL}/sap/rest/identity-providers" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" | jq '.[].originKey'
```

Verify role collections were created:

```bash
btp list security/role-collection --subaccount <CF_SUBACCOUNT_ID>
```

## Common Issues

### Issue: "btp: command not found"
**Cause:** BTP CLI is not installed or not on PATH.
**Solution:** Download and install from [SAP BTP Tools](https://tools.hana.ondemand.com/#cloud). Ensure it is on PATH.

### Issue: "No active session / not logged in"
**Cause:** BTP CLI session has expired.
**Solution:** Run `btp login --sso` in your terminal. The skill will not prompt for credentials.

### Issue: `btp create security/api-credential` fails with "not found"
**Cause:** The `security api-credential` sub-command requires BTP CLI 2.x. Older versions may use a different path.
**Solution:** Update BTP CLI to the latest version. Alternatively, create an `xsuaa` service instance with `apiaccess` plan manually in CF and obtain the service key — extract `clientid`, `clientsecret`, `url` (→ tokenurl), and `apiurl` from the binding credentials.

### Issue: XSUAA token fetch returns 401
**Cause:** The API credential was already deleted or expired.
**Solution:** Re-run from Step 3 — delete and recreate the credential.

### Issue: Trust API returns 409 Conflict
**Cause:** A SAML trust with the same `originKey` already exists in this subaccount.
**Solution:** This is treated as success — the existing trust is left unchanged and marked `already_configured` in the report. If you need to update it, delete the existing trust in BTP Cockpit first.

### Issue: IAS metadata fetch fails / returns HTML
**Cause:** Wrong IAS host, or the IAS tenant does not exist.
**Solution:** Verify the `iasHost` in `.migration/neo-trust-config.json`. Test manually: `curl https://<iasHost>/saml2/metadata`. The endpoint is public and requires no authentication.

### Issue: Trust API returns 400 with "invalid metadata"
**Cause:** The IAS metadata XML failed validation in XSUAA (e.g. encoding issue, truncated response).
**Solution:** Save the metadata to a file and inspect it: `curl -o /tmp/ias-meta.xml https://<iasHost>/saml2/metadata`. Ensure it is complete and starts with `<?xml`.

### Issue: Role collection creation fails
**Cause:** A role collection with that name already exists.
**Solution:** The skill continues — existing role collections are not modified.

### Issue: `--of-idp` flag not recognized
**Cause:** The role-collection assignment command uses `--of-idp` for custom IdP origins. An older BTP CLI may not support this flag.
**Solution:** Update BTP CLI to the latest version. Alternatively, configure attribute-based assignment manually in BTP Cockpit.

## Next Steps

After completing this skill:

- **Complete manual steps** — the most critical is registering XSUAA as SP in the IAS admin console; authentication will not work until this is done
- **Set the default IdP** — manually in BTP Cockpit → Security → Trust Configuration → edit the IdP → enable "Default Identity Provider"
- **[authentication-xsuaa](../authentication-xsuaa/SKILL.md)** — configure XSUAA for individual applications; the imported IdP trust is subaccount-wide and will be available to all apps
- **Future `subaccount-roles` skill** — export Neo roles and create CF role collections; group collections created by this skill can be extended with application roles
- **Test the authentication flow** — deploy an app with approuter and verify end-to-end login using the imported IdP; a fresh browser session (incognito) is recommended to avoid cached tokens
