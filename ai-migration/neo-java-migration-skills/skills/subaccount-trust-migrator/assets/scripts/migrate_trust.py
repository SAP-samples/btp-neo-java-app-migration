"""
Migrate SAML trust configuration from SAP BTP Neo to Cloud Foundry.

Data is never written to disk — all Neo trust data is held in memory and posted
directly to the XSUAA apiaccess REST API in the same process. No intermediate
files are created and the model never sees the raw trust configuration.

Usage:
  TOKEN=<neo-platform-api-bearer-token>
  NEO_SUBACCOUNT=<neo-subaccount-technical-name>
  NEO_REGION_HOST=<region>.hana.ondemand.com
  NEO_TRUST_URL=<url>              # set by neo-api-setup-template.sh; telemetry params appended by script if consent=yes
  CF_SUBACCOUNT_ID=<cf-subaccount-guid>
  # SP signing key consent is checked via ~/.neo-migration-consents/$NEO_SUBACCOUNT/trust-sp-signing-consent.txt
"""
import json, os, re, subprocess, sys

# ---------------------------------------------------------------------------
# Configuration — validated at startup; script exits before any API call
# if required env vars are missing or invalid.
# ---------------------------------------------------------------------------

def _require(name):
    v = os.environ.get(name, "").strip()
    if not v:
        print(f"ERROR: {name} environment variable is required", file=sys.stderr)
        sys.exit(1)
    return v

TOKEN = _require("TOKEN")
NEO_SUBACCOUNT = _require("NEO_SUBACCOUNT")
NEO_REGION_HOST = _require("NEO_REGION_HOST")
CF_SUBACCOUNT_ID = _require("CF_SUBACCOUNT_ID")
NEO_TRUST_URL = _require("NEO_TRUST_URL")

# Verify telemetry consent was collected before any NEO API call
_neo_migration_home = os.environ.get("XDG_DATA_HOME") or os.environ.get("APPDATA") or os.path.expanduser("~")
_neo_consents_home = os.path.join(_neo_migration_home, ".neo-migration-consents")
_neo_migration_home = os.path.join(_neo_migration_home, ".neo-migration")
_consent_file = os.path.join(_neo_consents_home, NEO_SUBACCOUNT, "neo-telemetry-consent.txt")
if not os.path.isfile(_consent_file):
    print(
        f"ERROR: Telemetry consent file not found: {_consent_file}\n"
        "Run Step 1b (telemetry setup) before invoking the migration script.",
        file=sys.stderr,
    )
    sys.exit(1)

def _read_file_value(path, key):
    """Read a key=value line from a file, return value or empty string."""
    try:
        with open(path) as f:
            for line in f:
                if line.startswith(key + "="):
                    return line.strip().split("=", 1)[1]
    except OSError:
        pass
    return ""

def _append_telemetry(url):
    """Append telemetry query params to url if consent=yes."""
    consent = _read_file_value(_consent_file, "consent")
    if consent != "yes":
        return url
    cf_subaccount_id = os.environ.get("CF_SUBACCOUNT_ID", "").strip()
    _telemetry_file = os.path.join(_neo_consents_home, "neo-telemetry-installation.txt")
    _session_file = os.path.join(_neo_consents_home, NEO_SUBACCOUNT, cf_subaccount_id, "neo-telemetry-session.txt")
    installation_id = _read_file_value(_telemetry_file, "neo_cf_migration_installation_id")
    session_id = _read_file_value(_session_file, "neo_cf_migration_session_id")
    if not installation_id or not session_id:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}neo_cf_migration_installation_id={installation_id}&neo_cf_migration_session_id={session_id}"

NEO_TRUST_URL = _append_telemetry(NEO_TRUST_URL)

_sp_signing_consent_file = os.path.join(_neo_consents_home, NEO_SUBACCOUNT, "trust-sp-signing-consent.txt")
_sp_signing_consent = ""
try:
    with open(_sp_signing_consent_file) as _f:
        for _line in _f:
            if _line.startswith("consent="):
                _sp_signing_consent = _line.strip().split("=", 1)[1].lower()
except OSError:
    pass
if _sp_signing_consent != "yes":
    print(
        f"ERROR: SP signing key consent file not found or consent not given: {_sp_signing_consent_file}\n"
        "Run Step 1b and answer the SP signing key disclaimer before invoking the script.",
        file=sys.stderr,
    )
    sys.exit(1)

# ---------------------------------------------------------------------------
# Result tracking
# ---------------------------------------------------------------------------

class Results:
    def __init__(self):
        self.imported = []           # {name, ias_host, origin_key}
        self.already_configured = [] # {name, ias_host, origin_key}
        self.skipped = []            # {name, reason}
        self.failed = []             # {name, error}
        self.groups_created = []     # "{idp_name}/{group_name}"
        self.manual_steps = []       # plain strings
        self.xsuaa_tenant_url = ""

RESULTS = Results()

# ---------------------------------------------------------------------------
# Helpers — curl via subprocess so credentials never enter shell string expansion
# ---------------------------------------------------------------------------

def _curl(*args):
    """Run curl with the given args, return (body: str, http_status: str)."""
    r = subprocess.run(
        ["curl", "-s", "-w", "\n__STATUS__%{http_code}"] + list(args),
        capture_output=True,
    )
    raw = r.stdout.decode("utf-8", errors="replace")
    body, _, status = raw.rpartition("\n__STATUS__")
    return body.strip(), status.strip()


def neo_get_trust():
    """Fetch raw trust config JSON from Neo. Returns parsed dict."""
    body, status = _curl(NEO_TRUST_URL, "-H", f"Authorization: Bearer {TOKEN}", "-H", "Accept: application/json")
    if status == "401":
        print("ERROR: Neo API returned 401 — token expired or invalid. Obtain a fresh token.", file=sys.stderr)
        sys.exit(1)
    if status == "403":
        print("ERROR: Neo API returned 403 — OAuth client is missing hcp.readTrustSettings scope.", file=sys.stderr)
        sys.exit(1)
    if status == "404":
        print("No custom IdP configuration found — subaccount uses the platform default IdP.\n"
              "No trust import is needed.")
        sys.exit(0)
    if status != "200":
        print(f"ERROR: Neo Trust API returned {status}:\n{body[:300]}", file=sys.stderr)
        sys.exit(1)
    try:
        return json.loads(body)
    except json.JSONDecodeError as e:
        print(f"ERROR: Could not parse Neo trust API response as JSON: {e}\n{body[:200]}", file=sys.stderr)
        sys.exit(1)


def fetch_ias_metadata(ias_host):
    """Fetch IAS SAML metadata XML (public, no auth). Returns XML string or None on failure."""
    url = f"https://{ias_host}/saml2/metadata"
    body, status = _curl(url)
    if status != "200" or not body.strip().startswith("<"):
        return None, f"IAS metadata fetch failed (HTTP {status}) for {ias_host}"
    return body, None


def btp_json(*args):
    """Run a btp CLI command with --format json, return parsed output or None."""
    r = subprocess.run(["btp", "--format", "json"] + list(args), capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return None


def btp_run(*args):
    """Run a btp CLI command, return (stdout, returncode)."""
    r = subprocess.run(["btp"] + list(args), capture_output=True, text=True)
    return r.stdout + r.stderr, r.returncode

# ---------------------------------------------------------------------------
# Step 1: Classify IdPs
# ---------------------------------------------------------------------------

IAS_DOMAINS = (".accounts.ondemand.com", ".accounts.cloud.sap", ".accounts400.ondemand.com")

def _extract_ias_host_from_url(url):
    """Extract IAS tenant hostname from an SSO/SLO URL."""
    m = re.search(r'https?://([^/]+)', url or "")
    if m:
        host = m.group(1)
        if any(host.endswith(d) for d in IAS_DOMAINS):
            return host
    return None


def classify_idps(idps):
    """
    Returns list of dicts:
      {name, type ("IAS"|"ThirdParty"), ias_host, enabled, raw_idp}
    raw_idp contains the full IdP dict from Neo — kept in memory, never printed.
    """
    classified = []
    for idp in idps:
        name = idp.get("name", "")
        enabled_raw = idp.get("enabled", True)
        enabled = enabled_raw if isinstance(enabled_raw, bool) else str(enabled_raw).lower() == "true"

        idp_type = idp.get("type", "")
        host = idp.get("host", "")
        sso_url = idp.get("ssoUrl", "")
        slo_url = idp.get("sloUrl", "")

        ias_host = None
        if idp_type == "SCI":
            ias_host = host
        elif any(host.endswith(d) for d in IAS_DOMAINS):
            ias_host = host
        else:
            ias_host = _extract_ias_host_from_url(sso_url) or _extract_ias_host_from_url(slo_url)

        classified.append({
            "name": name,
            "type": "IAS" if ias_host else "ThirdParty",
            "ias_host": ias_host,
            "enabled": enabled,
            "raw_idp": idp,
        })
    return classified

# ---------------------------------------------------------------------------
# Step 2: Acquire XSUAA API credentials via BTP CLI
# ---------------------------------------------------------------------------

CRED_NAME = "migration-api-credential"

def acquire_xsuaa_credentials():
    """Create a temporary BTP security/api-credential and return credential dict."""
    # Delete stale credential if exists
    btp_run("delete", "security/api-credential", CRED_NAME,
            "--subaccount", CF_SUBACCOUNT_ID, "--confirm", "true")

    cred_json = btp_json("create", "security/api-credential",
                         "--name", CRED_NAME,
                         "--subaccount", CF_SUBACCOUNT_ID)
    if not cred_json:
        print("ERROR: Failed to create XSUAA API credential via BTP CLI.\n"
              "Ensure BTP CLI is logged in (btp login --sso) and the subaccount ID is correct.",
              file=sys.stderr)
        sys.exit(1)

    client_id = cred_json.get("clientid", "")
    client_secret = cred_json.get("clientsecret", "")
    token_url = cred_json.get("tokenurl", "")
    api_url = cred_json.get("apiurl", "")

    if not all([client_id, client_secret, token_url, api_url]):
        print(f"ERROR: Incomplete XSUAA API credential fields: {list(cred_json.keys())}", file=sys.stderr)
        sys.exit(1)

    return {"client_id": client_id, "client_secret": client_secret,
            "token_url": token_url, "api_url": api_url}


def get_xsuaa_token(creds):
    """Acquire a Bearer token from XSUAA using client credentials."""
    r = subprocess.run(
        ["curl", "-s", "-X", "POST", creds["token_url"],
         "-u", f"{creds['client_id']}:{creds['client_secret']}",
         "-d", "grant_type=client_credentials"],
        capture_output=True, text=True,
    )
    try:
        token = json.loads(r.stdout).get("access_token", "").strip()
    except json.JSONDecodeError:
        token = ""
    if not token:
        print(f"ERROR: Failed to acquire XSUAA Bearer token from {creds['token_url']}:\n{r.stdout[:300]}", file=sys.stderr)
        sys.exit(1)
    return token


def cleanup_xsuaa_credentials():
    """Delete the temporary API credential. Warn if deletion fails."""
    out, rc = btp_run("delete", "security/api-credential", CRED_NAME,
                      "--subaccount", CF_SUBACCOUNT_ID, "--confirm", "true")
    if rc != 0:
        print(
            f"\nWARNING: Could not delete temporary API credential '{CRED_NAME}'.\n"
            f"Delete it manually: btp delete security/api-credential {CRED_NAME} "
            f"--subaccount {CF_SUBACCOUNT_ID} --confirm true"
        )

# ---------------------------------------------------------------------------
# Step 3: Create SAML trust for IAS IdPs
# ---------------------------------------------------------------------------

def create_saml_trust(idp, creds, bearer_token):
    """
    POST SAML trust to XSUAA for a single IAS IdP.
    Returns updated bearer_token (refreshed on 401).
    """
    ias_host = idp["ias_host"]
    name = idp["name"]

    metadata, err = fetch_ias_metadata(ias_host)
    if not metadata:
        RESULTS.failed.append({"name": name, "error": err})
        return bearer_token

    tenant_name = ias_host.split(".")[0]
    origin_key = f"{tenant_name}.migrated"

    # Build payload via python — avoids shell injection of XML metadata content
    payload = json.dumps({
        "name": "Neo migrated IAS Tenant",
        "type": "saml",
        "originKey": origin_key,
        "isActive": True,
        "config": {
            "idpEntityAlias": ias_host,
            "metaDataLocation": metadata,
            "addShadowUserOnLogin": True,
            "attributeMappings": {
                "given_name": "first_name",
                "family_name": "last_name",
                "email": "mail",
            },
        },
    })

    r = subprocess.run(
        ["curl", "-s", "-w", "\n__STATUS__%{http_code}",
         "-X", "POST", f"{creds['api_url']}/sap/rest/identity-providers",
         "-H", f"Authorization: Bearer {bearer_token}",
         "-H", "Content-Type: application/json",
         "-d", payload],
        capture_output=True, text=True,
    )
    raw = r.stdout
    body, _, status = raw.rpartition("\n__STATUS__")
    status = status.strip()

    # Refresh token on 401 and retry once
    if status == "401":
        bearer_token = get_xsuaa_token(creds)
        r = subprocess.run(
            ["curl", "-s", "-w", "\n__STATUS__%{http_code}",
             "-X", "POST", f"{creds['api_url']}/sap/rest/identity-providers",
             "-H", f"Authorization: Bearer {bearer_token}",
             "-H", "Content-Type: application/json",
             "-d", payload],
            capture_output=True, text=True,
        )
        raw = r.stdout
        body, _, status = raw.rpartition("\n__STATUS__")
        status = status.strip()

    if status in ("200", "201"):
        RESULTS.imported.append({"name": name, "ias_host": ias_host, "origin_key": origin_key})
    elif status == "409":
        RESULTS.already_configured.append({"name": name, "ias_host": ias_host, "origin_key": origin_key})
    else:
        RESULTS.failed.append({"name": name, "error": f"XSUAA API returned {status}: {body.strip()[:200]}"})

    return bearer_token

# ---------------------------------------------------------------------------
# Step 4: Assign assertion-based group rules (equals only)
# ---------------------------------------------------------------------------

def assign_group_rules(idp, origin_key):
    """Create role collections and assign equals-based assertion group rules via BTP CLI."""
    groups = idp["raw_idp"].get("assertionBasedGroups", [])
    for group_entry in groups:
        group_name = group_entry.get("group", "")
        rc_name = f"{group_name}-Group"
        rules = group_entry.get("rules", [])

        has_equals = any(r.get("operation") == "equals" for r in rules)
        has_regexp = any(r.get("operation") == "regexp" for r in rules)

        if has_equals:
            btp_run("create", "security/role-collection", rc_name,
                    "--subaccount", CF_SUBACCOUNT_ID)
            for rule in rules:
                if rule.get("operation") != "equals":
                    continue
                attr = rule.get("assertionAttribute", "")
                value = rule.get("value", "")
                btp_run("assign", "security/role-collection", rc_name,
                        "--to-user-attribute", attr,
                        "--attribute-value", value,
                        "--of-idp", origin_key,
                        "--subaccount", CF_SUBACCOUNT_ID)
            RESULTS.groups_created.append(f"{idp['name']}/{group_name}")

        if has_regexp:
            RESULTS.manual_steps.append(
                f"'{idp['name']}' / group '{group_name}': assertion-based group rule with 'regexp' "
                f"operation cannot be automated — configure it manually in the IAS admin console "
                f"under Applications > <app> > Authentication > Groups."
            )

# ---------------------------------------------------------------------------
# Step 5: Build manual checklist
# ---------------------------------------------------------------------------

def build_manual_steps(classified_idps, default_idp_name):
    has_ias_imported = bool(RESULTS.imported or RESULTS.already_configured)

    if has_ias_imported and RESULTS.xsuaa_tenant_url:
        RESULTS.manual_steps.insert(0,
            f"Register XSUAA as Service Provider in IAS — for each IAS IdP, open "
            f"https://<iasHost>/admin, go to Applications > Create Application > Upload Metadata, "
            f"and upload XSUAA SP metadata from: {RESULTS.xsuaa_tenant_url}/saml/metadata"
        )
        RESULTS.manual_steps.insert(1,
            f"Set default IdP — the BTP CLI cannot set a default IdP. If '{default_idp_name}' "
            f"should be the default, go to BTP Cockpit → subaccount → Security → Trust Configuration "
            f"→ find the IdP → Edit → enable 'Default Identity Provider'."
        )

    for idp in classified_idps:
        name = idp["name"]
        raw = idp["raw_idp"]

        if idp["type"] == "ThirdParty" and idp["enabled"]:
            RESULTS.manual_steps.append(
                f"'{name}' is a third-party IdP and cannot be directly registered in a CF subaccount. "
                f"To migrate it: (1) Configure it as a corporate IdP inside your IAS tenant via the IAS admin console, "
                f"(2) Ensure the IAS tenant is trusted in this subaccount, "
                f"(3) Route authentication through IAS."
            )

        if raw.get("assertionBasedAttributes"):
            n = len(raw["assertionBasedAttributes"])
            RESULTS.manual_steps.append(
                f"'{name}': {n} assertion-based attribute mapping(s) must be configured in the IAS "
                f"admin console under Applications > <app> > Authentication > Attributes."
            )
        if raw.get("defaultAttributes"):
            n = len(raw["defaultAttributes"])
            RESULTS.manual_steps.append(
                f"'{name}': {n} default attribute(s) must be configured in the IAS admin console "
                f"under Applications > <app> > Authentication > Default Attributes."
            )
        if raw.get("defaultGroups"):
            n = len(raw["defaultGroups"])
            RESULTS.manual_steps.append(
                f"'{name}': {n} default group(s) must be mapped to role collections in CF manually."
            )
        if raw.get("onlyForIdpInitiatedSSO") is True or str(raw.get("onlyForIdpInitiatedSSO", "")).lower() == "true":
            RESULTS.manual_steps.append(
                f"'{name}': IdP-initiated SSO requires explicit configuration in IAS. "
                f"Verify the application SSO endpoint is registered and the flow is enabled."
            )
        sig_alg = raw.get("signatureAlgorithm", "")
        if "sha-1" in sig_alg.lower() or sig_alg == "SHA1":
            RESULTS.manual_steps.append(
                f"'{name}': uses SHA-1 signature algorithm. Consider upgrading to SHA-256 "
                f"in the IAS admin console."
            )

    for idp in classified_idps:
        if not idp["enabled"]:
            RESULTS.manual_steps.append(
                f"'{idp['name']}': was disabled in Neo and was not imported. "
                f"Enable it manually in CF trust configuration if needed."
            )

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    # Step 1: Fetch Neo trust config (raw data stays in local variable, never printed)
    trust_config = neo_get_trust()

    configuration_type = trust_config.get("configurationType", "")
    if configuration_type == "Default":
        print("No custom IdP configuration found — subaccount uses the platform default IdP.\n"
              "No trust import is needed.")
        return

    local_sp = trust_config.get("localServiceProvider", {})
    default_idp_name = local_sp.get("defaultIdentityProviderName", "")

    raw_idps = (trust_config
                .get("applicationIdentityProviders", {})
                .get("identityProviders", []))

    if not raw_idps:
        print("No application identity providers found in the Neo trust configuration.\n"
              "No trust import is needed.")
        return

    classified = classify_idps(raw_idps)

    # Step 2: Separate into queues
    disabled = [i for i in classified if not i["enabled"]]
    ias_idps = [i for i in classified if i["enabled"] and i["type"] == "IAS"]
    third_party_idps = [i for i in classified if i["enabled"] and i["type"] == "ThirdParty"]

    for idp in disabled:
        RESULTS.skipped.append({"name": idp["name"], "reason": "disabled in Neo"})

    if not ias_idps and not third_party_idps:
        print("No enabled IdPs to import (all were disabled).")
        _print_summary(classified, default_idp_name)
        return

    if not ias_idps:
        # Only third-party IdPs — no XSUAA API calls needed
        for idp in third_party_idps:
            RESULTS.skipped.append({"name": idp["name"], "reason": "third-party IdP — manual migration required"})
        build_manual_steps(classified, default_idp_name)
        _print_summary(classified, default_idp_name)
        return

    # Step 3: Acquire XSUAA credentials
    creds = acquire_xsuaa_credentials()

    try:
        bearer_token = get_xsuaa_token(creds)

        # Derive XSUAA tenant URL for SP metadata instructions
        RESULTS.xsuaa_tenant_url = re.sub(r"/oauth/token$", "", creds["token_url"])
        # Step 4: Create SAML trust for IAS IdPs
        for idp in ias_idps:
            bearer_token = create_saml_trust(idp, creds, bearer_token)

        # Step 5: Assign group rules for successfully imported IdPs
        imported_origins = {
            item["name"]: item["origin_key"]
            for item in RESULTS.imported + RESULTS.already_configured
        }
        for idp in ias_idps:
            origin_key = imported_origins.get(idp["name"])
            if origin_key:
                assign_group_rules(idp, origin_key)

        # Third-party IdPs: cannot be directly registered in CF — add to manual checklist only
        for idp in third_party_idps:
            RESULTS.skipped.append({"name": idp["name"], "reason": "third-party IdP — manual migration required"})

        # Step 6: Build manual checklist
        build_manual_steps(classified, default_idp_name)

    finally:
        cleanup_xsuaa_credentials()

    _print_summary(classified, default_idp_name)


def _print_summary(classified, default_idp_name):
    total = len(classified)
    print("=== Trust Migration Summary ===")
    print(f"\nSource subaccount : {NEO_SUBACCOUNT} ({NEO_REGION_HOST})")
    print(f"Target subaccount : {CF_SUBACCOUNT_ID}")
    print(f"Total IdPs found  : {total}")
    print()
    print(f"  Imported           : {len(RESULTS.imported)}")
    for item in RESULTS.imported:
        host_str = f" ({item['ias_host']})" if item.get('ias_host') else ""
        print(f"    + {item['name']}{host_str}  →  origin: {item['origin_key']}")
    print(f"  Already configured : {len(RESULTS.already_configured)}")
    for item in RESULTS.already_configured:
        print(f"    ~ {item['name']}  →  origin: {item['origin_key']}")
    print(f"  Skipped            : {len(RESULTS.skipped)}")
    for item in RESULTS.skipped:
        print(f"    - {item['name']} ({item['reason']})")
    print(f"  Failed             : {len(RESULTS.failed)}")
    for item in RESULTS.failed:
        print(f"    ! {item['name']}: {item['error']}")

    if RESULTS.groups_created:
        print(f"\nRole collections created: {len(RESULTS.groups_created)}")
        for g in RESULTS.groups_created:
            print(f"    {g}")

    if RESULTS.manual_steps:
        print(f"\nManual steps required ({len(RESULTS.manual_steps)}):")
        for i, step in enumerate(RESULTS.manual_steps, 1):
            print(f"  {i}. {step}")
        if RESULTS.xsuaa_tenant_url:
            print(f"\nXSUAA SP metadata URL (needed for IAS registration):")
            print(f"  {RESULTS.xsuaa_tenant_url}/saml/metadata")
    else:
        print("\nNo manual steps required.")

    print("\nTemporary XSUAA API credentials: deleted")


if __name__ == "__main__":
    main()
