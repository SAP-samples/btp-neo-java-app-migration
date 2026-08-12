---
name: subaccount-trust-migrator
description: >-
  Migrate application IdP trust configuration from a Neo subaccount to Cloud Foundry in a
  single pass. Fetches the SAML trust config from Neo via the Trust Management REST API,
  classifies each identity provider as IAS or third-party, creates SAML trust in the CF
  subaccount via the XSUAA apiaccess REST API, and assigns assertion-based group rules via
  BTP CLI. Data flows entirely in memory — nothing is written to disk and the model never
  sees the raw trust configuration. Invoke when migrating IdP/trust configuration from
  Neo to Cloud Foundry as part of subaccount migration.
disable-model-invocation: false
allowed-tools: Bash(curl *), Bash(python3 *), Bash(btp *), Bash(cf *), Bash(echo *), Bash(cat *), Bash(ls *)
---

# Subaccount Trust Migrator

Migrate Neo application IdP trust configuration to Cloud Foundry in a single pass. No intermediate files are written — data flows from the Neo Trust Management API directly to the XSUAA API in memory.

## What this skill does

1. Fetches the SAML trust configuration from Neo (local service provider + all application IdPs)
2. Classifies each IdP as IAS or third-party
3. Acquires temporary XSUAA API credentials via BTP CLI
4. Creates SAML trust in XSUAA for each IAS IdP (fetching IAS metadata automatically)
5. Creates role collections and assigns `equals` assertion-based group rules via BTP CLI
6. Cleans up temporary API credentials
7. Prints a summary — counts and manual steps only; no trust data visible to the model

> **Scope: Application IdPs only.** This skill migrates identity providers used for end-user authentication to applications. Platform IdP configuration (BTP cockpit and CLI access) is out of scope.

> **IAS IdPs only — automated.** Third-party IdPs (e.g. Microsoft Azure AD) cannot be directly registered as application IdPs in a CF subaccount — they must be configured as corporate IdPs inside an IAS tenant. If you consent to migrating third-party IdPs, their signing certificates will be included in the script's input and uploaded to CF; otherwise they are listed as manual steps.

## Security disclaimers

**Present this disclaimer BEFORE asking the user for any inputs. Do NOT proceed until it has been acknowledged.**

### 1. Neo SP signing key — explicit consent required

Present this and wait for the user to reply before continuing:

> **The Neo trust configuration includes the signing key of the local service provider (SP).**
> This key is fetched from the Neo Trust API as part of the full trust configuration and flows through the migration script in memory. There is a small risk that it may reach this LLM model (for example, if an error occurs and the raw response appears in output).
>
> Do you want to proceed with the trust migration?
> - **yes** — I accept the risk
> - **no** — cancel

If the user replies **no**, stop.

## Prerequisites

Before running this skill, ensure:

1. **BTP CLI installed and logged in:**
   ```bash
   btp target
   ```
   If not logged in, instruct the user to run `btp login --sso`. Do not prompt for credentials — only SSO login is supported.

2. **`curl` available on PATH**

3. **Neo Platform API OAuth client** with the `hcp.readTrustSettings` scope — OR a pre-issued Bearer token (valid for 25 minutes)

4. **Neo subaccount technical name** (not the display name — find it in Neo cockpit > subaccount Overview)

5. **Neo region host** (e.g., `eu1.hana.ondemand.com`)

6. **CF subaccount GUID** — find it in BTP cockpit > subaccount Overview, or run `btp list accounts/subaccount`

## Inputs Required

After the disclaimer is acknowledged, collect the following inputs. Check for existing values in `.migration/neo-migration-config.json` and `.migration/cf-migration-config.json` before asking.

| Input | Description | Example |
|-------|-------------|---------|
| `TOKEN` | Neo Platform API Bearer token (valid 25 min) | `eyJhbGci...` |
| `NEO_SUBACCOUNT` | Neo subaccount technical name | `mysubaccount` |
| `NEO_REGION_HOST` | Neo region host | `eu1.hana.ondemand.com` |
| `CF_SUBACCOUNT_ID` | CF subaccount GUID | `a1b2c3d4-...` |

### How to obtain a Neo Platform API token

**Step 1 — Register a Platform API client**

1. Open **SAP BTP Cockpit** → navigate to the Neo subaccount
2. Go to **Security → OAuth → Platform API** tab
3. Click **Register New Platform Client**
4. Select exactly this scope:
   - **Trust** → Read Trust Settings (`hcp.readTrustSettings`)
5. Note the generated **Client ID** and **Client Secret**

**Step 2 — Request a token** (present all three options):

**Option A — Mac / Linux / WSL / Git Bash:**
```bash
CREDENTIALS=$(echo -n "<client_id>:<client_secret>" | base64) && \
curl -s -X POST "https://oauthasservices.<region>.hana.ondemand.com/oauth2/apitoken/v1?grant_type=client_credentials" \
  -H "Authorization: Basic ${CREDENTIALS}"
```

**Option B — Windows PowerShell:**
```powershell
$credentials = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("<client_id>:<client_secret>"))
$response = Invoke-WebRequest -Method POST `
  "https://oauthasservices.<region>.hana.ondemand.com/oauth2/apitoken/v1?grant_type=client_credentials" `
  -Headers @{"Authorization" = "Basic $credentials"}
($response.Content | ConvertFrom-Json).access_token
```

**Option C — REST client (Bruno, Postman, Insomnia, etc.):**

| Field | Value |
|-------|-------|
| Method | `POST` |
| URL | `https://oauthasservices.<region>.hana.ondemand.com/oauth2/apitoken/v1?grant_type=client_credentials` |
| Auth type | Basic Auth |
| Username | `<client_id>` |
| Password | `<client_secret>` |

Copy the `access_token` from the response. The token is valid for **25 minutes**.

### Verify BTP CLI session

```bash
btp target
```

Show the output to the user and confirm the correct global account and subaccount are targeted before continuing.

## Migration

Once the disclaimer is acknowledged and all inputs are collected, tell the user: "Running the trust migration script..." and then run:

```bash
TOKEN="${TOKEN}" \
  NEO_SUBACCOUNT="${NEO_SUBACCOUNT}" \
  NEO_REGION_HOST="${NEO_REGION_HOST}" \
  CF_SUBACCOUNT_ID="${CF_SUBACCOUNT_ID}" \
  CONSENT_SP_SIGNING_KEY="true" \
  python3 assets/scripts/migrate_trust.py
```

Do NOT show the full command to the user — only the short status message above.

> **Note:** No files are written to disk. All Neo trust data is fetched into memory and posted directly to XSUAA. The model never sees the raw trust configuration.

## After migration

Display the script's stdout output to the user verbatim. It contains:
- Counts of IAS IdPs imported / already configured / failed
- Third-party IdPs listed by name (no certificate data)
- Manual steps required
- XSUAA SP metadata URL for IAS registration

## Common Issues

### "ERROR: CONSENT_SP_SIGNING_KEY must be set to 'true'"
**Cause:** The script was invoked without setting the consent env var. Re-run the disclaimer section and ensure the user has answered before invoking the script.

### Token expired mid-run
The Neo Bearer token is valid for 25 minutes. Ask the user for a fresh token and re-run. The script is idempotent — already-existing SAML trust returns HTTP 409 and is treated as success.

### "No active BTP CLI session"
Run `btp login --sso` in the terminal and re-invoke the skill.

### Trust API returns 404
Use the **technical name** of the Neo subaccount, not the display name. Find it in Neo cockpit > subaccount Overview page.

### Trust API returns 403
The OAuth client is missing the `hcp.readTrustSettings` scope. Edit the Platform API client in Neo cockpit and add the scope.

### `btp create security/api-credential` fails
Requires BTP CLI 2.x. Update to the latest version, or create an XSUAA service instance with `apiaccess` plan manually and extract `clientid`, `clientsecret`, `url`, and `apiurl` from the binding credentials.

### IAS metadata fetch fails
Verify the IAS tenant host. Test manually: `curl https://<iasHost>/saml2/metadata`. The endpoint is public and requires no authentication.

### Trust API returns 409 Conflict
A SAML trust with the same `originKey` already exists. The script treats this as success (`already_configured`). To replace it, delete the existing trust in BTP Cockpit first.

## Next Steps

After completing this skill:

- **Complete manual steps** — the most critical is registering XSUAA as Service Provider in the IAS admin console; authentication will not work until this is done
- **Set the default IdP** — manually in BTP Cockpit → Security → Trust Configuration → edit the IdP → enable "Default Identity Provider"
- **[authentication-xsuaa](../authentication-xsuaa/SKILL.md)** — configure XSUAA for individual applications; the imported IdP trust is subaccount-wide
- **[subaccount-roles-export](../subaccount-roles-export/SKILL.md)** — export Neo roles and map them to CF role collections
