---
name: subaccount-roles-export
description: >-
  Export application roles, groups, and user assignments from a Neo subaccount. Uses
  the public Neo Authorization Management REST API to fetch all application roles with
  their user assignments, all groups with their role and user assignments, and saves a
  structured JSON report to .migration/neo-roles.json with migration notes for CF.
  Invoke when migrating authorization configuration from a Neo subaccount to Cloud
  Foundry, or when you need to understand what roles and groups are configured.
disable-model-invocation: false
allowed-tools: Read, Write, Grep, Glob, Bash(curl *), Bash(python3 *), Bash(btp *), Bash(echo *), Bash(cat *), Bash(ls *), Bash(mkdir *)
---

# Subaccount Roles Export

Export Neo subaccount application roles, groups, and user assignments for Cloud Foundry migration.

## Purpose

This skill exports all application roles, groups, and their user and role assignments from a Neo subaccount using the public **Neo Authorization Management REST API**. It:

- Lists all deployed applications and fetches their roles
- Fetches user assignments for each role
- Lists all groups and fetches their role and user assignments
- Generates migration notes explaining the Neo → CF authorization model differences
- Saves structured output to `$MIGRATION_DIR/neo-roles.json` for downstream skills

This skill is **read-only** — it does NOT create anything in CF. The companion `subaccount-roles-import` skill consumes the output.

> **Neo vs. CF authorization model difference.** Neo uses flat per-application roles — you assign users directly to a role on a specific application. CF uses a layered model: XSUAA defines *scopes* and *role-templates* per application, which are assembled into *role collections* at the subaccount level, and role collections are assigned to users. This skill exports the Neo model as-is; the import skill creates role collections as a best-effort mapping. A full redesign using the `authentication-xsuaa` skill is required per application to define proper XSUAA scopes.

> **The "Everyone" role is implicit in Neo.** Any user authenticated to the subaccount automatically has access via the Everyone role. In CF, all access must be explicitly granted via role collections. This is flagged in the migration summary.

## Prerequisites

Before running this skill, ensure:

1. **Tools available on PATH**:
   - `curl` (required)
   - `jq` (recommended)

2. **Neo Platform API OAuth client** with the following scope:
   - `readAuthorizationSettings` — to read roles, groups, and assignments

3. **Neo subaccount technical name** and **region host**

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
  else
    echo '.migration/' > .gitignore
  fi
fi
```

## Step 1: Obtain Bearer Token

**Base URLs for this skill:**
- Token endpoint: `https://api.${REGION_HOST}/oauth2/apitoken/v1`
- Authorization API: `https://api.${REGION_HOST}/authorization/v1`
- Lifecycle API (for app list): `https://api.${REGION_HOST}/lifecycle/v1`

**If using client credentials:**

```bash
export MSYS_NO_PATHCONV=1

TOKEN_RESPONSE=$(curl -s -X POST \
  "https://api.${REGION_HOST}/oauth2/apitoken/v1?grant_type=client_credentials" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "${CLIENT_ID}:${CLIENT_SECRET}")

BEARER_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
```

If `BEARER_TOKEN` is empty, check the response for errors:

| HTTP Status | Cause | Solution |
|-------------|-------|----------|
| 401 | Invalid credentials | Verify client ID and secret in Neo cockpit > OAuth |
| 400 | Missing scope | Ensure OAuth client has `readAuthorizationSettings` scope |
| Network error | Cannot reach API | Verify region host and connectivity |

**If using pre-issued token:** Use it directly. Warn about 25-minute expiry.

## Step 2: Fetch Application List

Use the Neo Lifecycle API to get all deployed applications:

```bash
export MSYS_NO_PATHCONV=1

APPS_RESPONSE=$(curl -s -w "\n%{http_code}" \
  "https://api.${REGION_HOST}/lifecycle/v1/accounts/${SUBACCOUNT}/apps" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")

HTTP_STATUS=$(echo "$APPS_RESPONSE" | tail -1 | tr -d '\r')
APPS_BODY=$(echo "$APPS_RESPONSE" | sed '$d' | tr -d '\r')
```

Extract application names:

```bash
APP_NAMES=$(echo "$APPS_BODY" | jq -r '.[] | .applicationName // .name // empty' 2>/dev/null)
```

**Note:** If the Lifecycle API is unavailable or returns an empty list, still proceed to fetch groups (Step 4). The roles export will have an empty applications array, which is valid.

## Step 3: Fetch Roles and User Assignments per Application

For each application name from Step 2:

**3a. Fetch roles for the application:**

```bash
ROLES_RESPONSE=$(curl -s -w "\n%{http_code}" \
  "https://api.${REGION_HOST}/authorization/v1/accounts/${SUBACCOUNT}/apps/${APP_NAME}/roles" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")
```

The response is a JSON array of role objects:
```json
[
  { "name": "Admin", "applicationRole": true, "shared": false }
]
```

**3b. For each role, fetch its user assignments:**

```bash
USERS_RESPONSE=$(curl -s \
  "https://api.${REGION_HOST}/authorization/v1/accounts/${SUBACCOUNT}/apps/${APP_NAME}/roles/users?roleName=${ROLE_NAME}" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")
```

The response is a JSON array of user objects:
```json
[
  { "userId": "john.doe@example.com" }
]
```

Extract user IDs into an array for the role.

**Error handling per role fetch:** If any individual role or user fetch returns a non-200 status, log a warning and continue — do not stop the entire export.

## Step 4: Fetch Groups and Their Assignments

**4a. Fetch all groups:**

```bash
GROUPS_RESPONSE=$(curl -s -w "\n%{http_code}" \
  "https://api.${REGION_HOST}/authorization/v1/accounts/${SUBACCOUNT}/groups" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")
```

Response: JSON array of group objects:
```json
[
  { "name": "managers" },
  { "name": "viewers" }
]
```

**4b. For each group, fetch role assignments:**

```bash
GROUP_ROLES_RESPONSE=$(curl -s \
  "https://api.${REGION_HOST}/authorization/v1/accounts/${SUBACCOUNT}/groups/roles?groupName=${GROUP_NAME}" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")
```

Response: JSON array of role objects:
```json
[
  { "name": "Admin", "applicationName": "myapp", "providerAccount": "myaccount" }
]
```

**4c. For each group, fetch user assignments:**

```bash
GROUP_USERS_RESPONSE=$(curl -s \
  "https://api.${REGION_HOST}/authorization/v1/accounts/${SUBACCOUNT}/groups/users?groupName=${GROUP_NAME}" \
  -H "Authorization: Bearer ${BEARER_TOKEN}" \
  -H "Accept: application/json")
```

Response: JSON array of user objects.

## Step 5: Build and Save Output

Construct the output JSON and save to `$MIGRATION_DIR/neo-roles.json` using the Write tool:

```json
{
  "sourceSubaccount": "<subaccount technical name>",
  "sourceRegion": "<region host>",
  "exportTimestamp": "<ISO 8601 timestamp>",
  "applications": [
    {
      "name": "myapp",
      "roles": [
        {
          "name": "Admin",
          "applicationRole": true,
          "shared": false,
          "userAssignments": ["user1@example.com", "user2@example.com"]
        },
        {
          "name": "Viewer",
          "applicationRole": true,
          "shared": false,
          "userAssignments": []
        }
      ]
    }
  ],
  "groups": [
    {
      "name": "managers",
      "roleAssignments": [
        { "applicationName": "myapp", "roleName": "Admin" }
      ],
      "userAssignments": ["user3@example.com"]
    }
  ],
  "migrationSummary": {
    "totalApplications": 0,
    "totalRoles": 0,
    "totalGroups": 0,
    "totalUserRoleAssignments": 0,
    "totalGroupRoleAssignments": 0,
    "totalGroupUserAssignments": 0,
    "requiresManualSteps": true,
    "manualSteps": [
      "Neo uses flat per-application roles. CF requires scopes + role-templates + role-collections. Run authentication-xsuaa skill per application to define proper XSUAA scopes, then add roles to the collections created by subaccount-roles-import.",
      "The implicit 'Everyone' role in Neo must be explicitly replicated in CF by assigning the appropriate role collections to all authenticated users."
    ]
  }
}
```

**Always include in `manualSteps`** (these are structural limitations, not configuration gaps):
- "Neo uses flat per-application roles. CF requires scopes + role-templates + role-collections. Run the `authentication-xsuaa` skill per application to define proper XSUAA scopes, then add application roles to the collections created by `subaccount-roles-import`."
- "The implicit 'Everyone' role in Neo must be explicitly replicated in CF by assigning the appropriate role collections to all authenticated users."

## Step 6: Display Summary

```
Roles Export Complete
=====================
Source: <subaccount> (<region>)

Applications: <count>
  Total roles:              <count>
  Total user-role assigns:  <count>

Groups: <count>
  Total group-role assigns: <count>
  Total group-user assigns: <count>

Note: Neo roles are flat per-application. CF requires a redesign into
      scopes + role-templates + role-collections (done via authentication-xsuaa).
      subaccount-roles-import will create role collections as a best-effort mapping.

Output saved to: $MIGRATION_DIR/neo-roles.json
```

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `neo-migration-config.json` | `$MIGRATION_DIR` | Shared input config — Neo subaccount details and auth credentials |
| `neo-roles.json` | `$MIGRATION_DIR` | Output — roles, groups, and user assignments with migration notes |

## CF Services

None — this skill is read-only.

## Verification

1. Verify output file exists:
   ```bash
   ls -la $MIGRATION_DIR/neo-roles.json
   ```

2. List all applications and their roles:
   ```bash
   jq '.applications[] | {app: .name, roles: [.roles[].name]}' $MIGRATION_DIR/neo-roles.json
   ```

3. List all groups:
   ```bash
   jq '.groups[] | {group: .name, roles: [.roleAssignments[].roleName], users: .userAssignments}' $MIGRATION_DIR/neo-roles.json
   ```

4. Check migration summary:
   ```bash
   jq '.migrationSummary' $MIGRATION_DIR/neo-roles.json
   ```

## Common Issues

### Issue: "Authorization API returns 403"
**Cause:** OAuth client does not have `readAuthorizationSettings` scope.
**Solution:** In Neo cockpit, edit the Platform API OAuth client and add the `readAuthorizationSettings` scope.

### Issue: "Lifecycle API returns 404 or empty array"
**Cause:** No applications deployed, or the Lifecycle API endpoint differs for this region.
**Solution:** Verify the subaccount has deployed applications in Neo cockpit. The export will proceed with an empty applications list — groups are still exported.

### Issue: "Role fetch returns empty array for an app"
**Cause:** The application has no custom roles defined (only the implicit Everyone role).
**Solution:** This is normal — the application will appear in the output with an empty `roles` array. The Everyone role is noted in the migration summary.

### Issue: Token expires mid-export (25-minute limit)
**Cause:** Large subaccounts with many applications can take more than 25 minutes to export.
**Solution:** Use client credentials authentication (not pre-issued token) — the skill can re-fetch the token if it detects a 401 response during iteration.

### Issue: curl URL path munging on Windows
**Solution:** Set `export MSYS_NO_PATHCONV=1` before running curl commands (this skill does this automatically).

## Next Steps

After completing this skill:

- **[subaccount-roles-import](../subaccount-roles-import/SKILL.md)** — reads `$MIGRATION_DIR/neo-roles.json` and creates CF role collections with user assignments
- **[authentication-xsuaa](../authentication-xsuaa/SKILL.md)** — for each application, define proper XSUAA scopes and role-templates; the role collections created by `subaccount-roles-import` can then be extended with application scopes
- **[subaccount-trust-export](../subaccount-trust-export/SKILL.md)** — if not done, export IdP trust configuration (group-based access rules may depend on the IdP configuration)
