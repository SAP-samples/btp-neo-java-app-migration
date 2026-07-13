# BTP CLI JSON Output Schemas

Reference for the JSON output structure of key BTP CLI commands. Use with `btp --format json <command>`.

## accounts/subaccount

```bash
btp --format json list accounts/subaccount
```

```json
{
  "value": [
    {
      "guid": "2f964175-0856-47b2-b77a-469c74df0cca",
      "displayName": "My Subaccount",
      "subdomain": "my-subdomain",
      "region": "eu11",
      "state": "OK",
      "stateMessage": "",
      "globalAccountGUID": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "parentGUID": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "parentType": "ROOT",
      "description": "",
      "betaEnabled": false,
      "usedForProduction": "USED_FOR_PRODUCTION",
      "createdDate": "2024-01-15T10:00:00Z",
      "modifiedDate": "2024-06-01T08:00:00Z",
      "labels": {}
    }
  ]
}
```

**jq to extract ID by name:**
```bash
btp --format json list accounts/subaccount | \
  jq -r '.value[] | select(.displayName == "My Subaccount") | .guid'
```

---

## accounts/environment-instance

```bash
btp --format json list accounts/environment-instance --subaccount <id>
```

```json
{
  "environmentInstances": [
    {
      "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "displayName": "My CF Org",
      "environmentType": "cloudfoundry",
      "serviceId": "cloudfoundry",
      "planId": "standard",
      "state": "OK",
      "stateMessage": "",
      "subaccountGUID": "2f964175-0856-47b2-b77a-469c74df0cca",
      "labels": "{\"API Endpoint\":\"https://api.cf.eu11.hana.ondemand.com\",\"Org Name\":\"my-org\",\"Org ID\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
      "customLabels": {},
      "createdDate": "2024-01-15T10:00:00Z",
      "modifiedDate": "2024-06-01T08:00:00Z"
    }
  ]
}
```

**jq to extract CF API endpoint:**
```bash
btp --format json list accounts/environment-instance --subaccount <id> | \
  jq -r '.environmentInstances[] | select(.environmentType == "cloudfoundry") | .labels' | \
  jq -r '."API Endpoint"'
```

> **Note:** `labels` is a JSON string, not an object — pipe through `jq -r` then `jq` again.

---

## security/role-collection (list)

```bash
btp --format json list security/role-collection --subaccount <id>
```

```json
[
  {
    "name": "nonosgi-auth-Everyone",
    "description": "Everyone role collection",
    "isReadOnly": false,
    "roleReferences": [
      {
        "roleTemplateName": "Everyone",
        "roleTemplateAppId": "nonosgi-auth!t12345",
        "name": "Everyone"
      }
    ],
    "userReferences": [
      {
        "value": "user@example.com",
        "origin": "sap.default"
      }
    ],
    "groupReferences": []
  }
]
```

**jq to check if role collection exists:**
```bash
btp --format json list security/role-collection | \
  jq --arg name "My-RC" 'any(.[]; .name == $name)'
```

**jq to list all role-template references in a collection:**
```bash
btp --format json get security/role-collection "My-RC" | \
  jq '.roleReferences[] | {roleTemplateName, roleTemplateAppId}'
```

---

## security/app (list)

```bash
btp --format json list security/app --subaccount <id>
```

```json
[
  {
    "appid": "nonosgi-auth!t12345",
    "xsappname": "nonosgi-auth",
    "displayName": "nonosgi-auth",
    "description": "",
    "planName": "application"
  }
]
```

**jq to find appId by app name prefix:**
```bash
btp --format json list security/app | \
  jq -r '.[] | select(.xsappname | startswith("nonosgi-auth")) | .appid'
```

---

## security/app (get — with role-templates)

```bash
btp --format json get security/app "nonosgi-auth!t12345"
```

```json
{
  "appid": "nonosgi-auth!t12345",
  "xsappname": "nonosgi-auth",
  "scopes": [
    {
      "name": "$XSAPPNAME.Everyone",
      "description": "Everyone scope"
    },
    {
      "name": "$XSAPPNAME.Developer",
      "description": "Developer scope"
    }
  ],
  "roleTemplates": [
    {
      "name": "Everyone",
      "description": "Everyone role",
      "scopeReferences": ["$XSAPPNAME.Everyone"],
      "isReadOnly": false
    },
    {
      "name": "Developer",
      "description": "Developer role",
      "scopeReferences": ["$XSAPPNAME.Developer"],
      "isReadOnly": false
    }
  ]
}
```

**jq to list role-template names:**
```bash
btp --format json get security/app "nonosgi-auth!t12345" | \
  jq '.roleTemplates[].name'
```

---

## security/trust (list)

```bash
btp --format json list security/trust --subaccount <id>
```

```json
[
  {
    "origin": "sap.default",
    "type": "Platform",
    "name": "SAP ID Service",
    "description": "SAP ID Service",
    "identityProvider": "accounts.sap.com",
    "status": "active",
    "readOnly": false,
    "availableForUserLogon": true,
    "protocol": "oidc1.0"
  },
  {
    "origin": "nss-accounts-ondemand-com",
    "type": "Application",
    "name": "NSS IAS Tenant",
    "description": "",
    "identityProvider": "nss.accounts.ondemand.com",
    "status": "active",
    "readOnly": false,
    "availableForUserLogon": true,
    "protocol": "oidc1.0"
  }
]
```

**jq to check if trust with IAS host already exists:**
```bash
btp --format json list security/trust | \
  jq --arg host "nss.accounts.ondemand.com" 'any(.[]; .identityProvider == $host)'
```

---

## services/instance (list)

```bash
btp --format json list services/instance --subaccount <id>
```

```json
{
  "items": [
    {
      "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "name": "nonosgi-auth-xsuaa",
      "serviceOfferingId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "servicePlanId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "offeringName": "xsuaa",
      "planName": "application",
      "state": "succeeded",
      "stateMessage": ""
    }
  ]
}
```

**jq to list instances with offering and plan:**
```bash
btp --format json list services/instance | \
  jq '.items[] | {name, offeringName, planName, state}'
```

---

## services/binding (get — credentials)

```bash
btp --format json get services/binding <binding-id>
```

```json
{
  "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "name": "my-binding",
  "serviceInstanceId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "credentials": {
    "clientid": "sb-nonosgi-auth!t12345",
    "clientsecret": "...",
    "url": "https://tenant.authentication.eu11.hana.ondemand.com",
    "xsappname": "nonosgi-auth!t12345"
  }
}
```

**jq to extract credentials:**
```bash
btp --format json get services/binding <id> | jq '.credentials'
```

---

## accounts/entitlement (list)

```bash
btp --format json list accounts/entitlement --subaccount <id>
```

```json
[
  {
    "serviceName": "APPLICATION_RUNTIME",
    "planName": "MEMORY",
    "amount": 3072,
    "remainingAmount": 2048,
    "unit": "MB"
  },
  {
    "serviceName": "xsuaa",
    "planName": "application",
    "amount": 1,
    "remainingAmount": 0,
    "unit": "INSTANCES"
  }
]
```

**jq to check CF Runtime quota:**
```bash
btp --format json list accounts/entitlement | \
  jq '.[] | select(.serviceName == "APPLICATION_RUNTIME") | {planName, amount, remainingAmount, unit}'
```
