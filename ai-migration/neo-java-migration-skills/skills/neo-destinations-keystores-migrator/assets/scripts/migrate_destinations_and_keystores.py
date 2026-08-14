"""
Migrate destinations and keystores directly from SAP BTP Neo to Cloud Foundry.

Data is never written to disk — all Neo items are held in memory and uploaded
to CF in the same process. No intermediate files are created.

Usage:
  TOKEN=<neo-platform-api-token>
  REGION_HOST=<landscape>        e.g. hana.ondemand.com
  SUBACCOUNT=<neo-subaccount>
  APP_MAPPING=<json>             e.g. '{"neo-app1": "cf-app1", "neo-app2": "cf-app1"}'

Optional overrides:
  KEYSTORE_BASE       override for NEO_KEYSTORE_BASE (set by neo-api-setup-template.sh)
  CONFIG_BASE         override for NEO_CONFIG_BASE (set by neo-api-setup-template.sh)
  WORKERS             thread count for parallel app processing (default: 20)
"""
import os, json, re, base64, subprocess, time, sys, uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urlencode

REQUIRED_SCOPES = {"hcp.readKeystores", "hcp.readDestination", "hcp.readJavaApplications"}

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
NEO_TOKEN = os.environ["TOKEN"].strip()

# Validate token scopes — must be exactly the three required scopes, no more, no less.
try:
    _payload = json.loads(base64.b64decode(NEO_TOKEN.split(".")[1] + "==").decode("utf-8"))
    _sub = _payload.get("sub", {})
    if isinstance(_sub, dict):
        _token_scopes = set(_sub.get("scopes", []))
    else:
        _scope_val = _payload.get("scope", _payload.get("scopes", []))
        if isinstance(_scope_val, str):
            _token_scopes = set(_scope_val.split())
        else:
            _token_scopes = set(_scope_val)
    _extra = _token_scopes - REQUIRED_SCOPES
    _missing = REQUIRED_SCOPES - _token_scopes
    if _missing:
        print(f"ERROR: Token is missing required scopes: {', '.join(sorted(_missing))}\n"
              f"Required scopes are exactly: {', '.join(sorted(REQUIRED_SCOPES))}\n"
              f"Register a Platform API client with exactly these three scopes and generate a new token.",
              file=sys.stderr)
        sys.exit(1)
    if _extra:
        print(f"ERROR: Token has scopes beyond the required set: {', '.join(sorted(_extra))}.\n"
              f"Required scopes are exactly: {', '.join(sorted(REQUIRED_SCOPES))}.\n"
              f"Register a new, separate Platform API client with only these three scopes, "
              f"generate a new token from that client, and retry.",
              file=sys.stderr)
        sys.exit(1)
except (IndexError, ValueError, KeyError):
    pass  # If token is opaque or unparseable, skip static check and let API calls fail naturally.
HOST = os.environ.get("REGION_HOST", os.environ.get("HOST", "")).strip()
if not HOST:
    print("ERROR: REGION_HOST environment variable is required (e.g. hana.ondemand.com)", file=sys.stderr)
    sys.exit(1)
ACCOUNT = os.environ.get("SUBACCOUNT", os.environ.get("ACCOUNT", "")).strip()
if not ACCOUNT:
    print("ERROR: SUBACCOUNT environment variable is required", file=sys.stderr)
    sys.exit(1)
APP_MAPPING = {} if "--list-apps" in sys.argv else json.loads(os.environ["APP_MAPPING"])
WORKERS = int(os.environ.get("WORKERS", "20"))
MIGRATE_KEYSTORES = os.environ.get("MIGRATE_KEYSTORES", "").strip().lower() == "yes"
MIGRATE_OAUTH_CREDENTIALS = os.environ.get("MIGRATE_OAUTH_CREDENTIALS", "").strip().lower() == "yes"

def _require_env(name):
    val = os.environ.get(name, "").strip()
    if not val:
        print(f"ERROR: {name} is not set — source neo-api-setup-template.sh before running this script.", file=sys.stderr)
        sys.exit(1)
    return val

KEYSTORE_BASE = os.environ.get("KEYSTORE_BASE") or _require_env("NEO_KEYSTORE_BASE")
CONFIG_BASE = os.environ.get("CONFIG_BASE") or _require_env("NEO_CONFIG_BASE")
NEO_LIFECYCLE_URL = _require_env("NEO_LIFECYCLE_URL")

# ---------------------------------------------------------------------------
# Telemetry — correlation IDs
# ---------------------------------------------------------------------------

def _load_correlation_params():
    neo_migration_home = (
        os.environ.get("XDG_DATA_HOME")
        or os.environ.get("APPDATA")
        or os.path.expanduser("~")
    )
    neo_migration_home = os.path.join(neo_migration_home, ".neo-migration")
    neo_consents_home = os.path.join(
        os.environ.get("XDG_DATA_HOME") or os.environ.get("APPDATA") or os.path.expanduser("~"),
        ".neo-migration-consents"
    )
    cf_guid = os.environ.get("CF_SUBACCOUNT_GUID", "").strip()
    if not cf_guid:
        # Resolve from cf target + btp list accounts/subaccount
        try:
            cf_org = subprocess.run(
                ["cf", "target"], capture_output=True, text=True
            ).stdout
            org_name = next(
                (l.split(":", 1)[1].strip() for l in cf_org.splitlines() if l.strip().startswith("org:")),
                ""
            )
            btp_out = subprocess.run(
                ["btp", "list", "accounts/subaccount"], capture_output=True, text=True
            ).stdout
            for line in btp_out.splitlines()[2:]:
                parts = line.split()
                if len(parts) >= 3 and parts[2] in org_name:
                    cf_guid = parts[0]
                    break
        except Exception:
            pass
    if not cf_guid:
        print("ERROR: CF_SUBACCOUNT_GUID is not set and could not be resolved automatically.\n"
              "       Run 'cf target' and 'btp list accounts/subaccount' to verify your login state.",
              file=sys.stderr)
        sys.exit(1)
    subaccount_dir = os.path.join(neo_migration_home, ACCOUNT, cf_guid)
    telemetry_file = os.path.join(neo_consents_home, "neo-telemetry-installation.txt")
    consent_file = os.path.join(neo_consents_home, ACCOUNT, "neo-telemetry-consent.txt")
    session_file = os.path.join(neo_consents_home, ACCOUNT, cf_guid, "neo-telemetry-session.txt")

    try:
        # --- consent (per subaccount) ---
        if not os.path.isfile(consent_file):
            print("\n=== Telemetry Notice ===")
            print("This migration appends two non-PII random UUIDs (neo_cf_migration_installation_id, neo_cf_migration_session_id)")
            print("as query parameters to every NEO API call to support usage tracking and troubleshooting.")
            print("No personal data, subaccount names, or credentials are included.")
            print()
            consent = input("Enable telemetry? (yes/no): ").strip().lower()
            os.makedirs(os.path.join(neo_consents_home, ACCOUNT), exist_ok=True)
            with open(consent_file, "w") as f:
                f.write(f"consent={consent}\n")
            print(f"INFO: Telemetry {'enabled' if consent == 'yes' else 'disabled'}.")
        else:
            consent = ""
            with open(consent_file) as f:
                for line in f:
                    k, _, v = line.strip().partition("=")
                    if k.strip() == "consent":
                        consent = v.strip().lower()

        if consent != "yes":
            return ""

        # --- installation_id (once per machine) ---
        installation_id = ""
        if os.path.isfile(telemetry_file):
            with open(telemetry_file) as f:
                for line in f:
                    k, _, v = line.strip().partition("=")
                    if k.strip() == "neo_cf_migration_installation_id":
                        installation_id = v.strip()
        if not installation_id:
            installation_id = str(uuid.uuid4())
            os.makedirs(neo_consents_home, exist_ok=True)
            with open(telemetry_file, "w") as f:
                f.write(f"neo_cf_migration_installation_id={installation_id}\n")

        # --- session_id (per subaccount — reuse if present, new if not) ---
        migration_id = ""
        if os.path.isfile(session_file):
            with open(session_file) as f:
                for line in f:
                    k, _, v = line.strip().partition("=")
                    if k.strip() == "neo_cf_migration_session_id":
                        migration_id = v.strip()
            if migration_id:
                print(f"INFO: Resuming migration session. neo_cf_migration_session_id: {migration_id}")
        if not migration_id:
            migration_id = str(uuid.uuid4())
            os.makedirs(os.path.join(neo_consents_home, ACCOUNT, cf_guid), exist_ok=True)
            with open(session_file, "w") as f:
                f.write(f"neo_cf_migration_session_id={migration_id}\n")
            print(f"INFO: New migration session started. neo_cf_migration_session_id: {migration_id}")

    except OSError as e:
        print(f"WARNING: Telemetry disabled — could not read/write telemetry file: {e}", file=sys.stderr)
        return ""

    if not installation_id or not migration_id:
        return ""
    return urlencode({"neo_cf_migration_installation_id": installation_id,
                      "neo_cf_migration_session_id": migration_id})

_CORRELATION_PARAMS = _load_correlation_params()


def _neo_url(url):
    """Append correlation query parameters to a NEO API URL."""
    if not _CORRELATION_PARAMS:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}{_CORRELATION_PARAMS}"

# ---------------------------------------------------------------------------
# Result tracking
# ---------------------------------------------------------------------------

class Results:
    def __init__(self):
        self.account_dest_ok = []
        self.account_dest_fail = []
        self.account_dest_skip = []
        self.account_ks_ok = []
        self.account_ks_fail = []
        self.apps = {}  # app -> {dest_ok, dest_fail, dest_skip, ks_ok, ks_fail}

    def app(self, name):
        if name not in self.apps:
            self.apps[name] = {"dest_ok": [], "dest_fail": [], "dest_skip": [], "ks_ok": [], "ks_fail": []}
        return self.apps[name]

RESULTS = Results()

# ---------------------------------------------------------------------------
# Neo API helpers
# ---------------------------------------------------------------------------

def neo_get_text(url):
    r = subprocess.run(
        ["curl", "-s", "-w", "\n__STATUS__%{http_code}", url,
         "-H", f"Authorization: Bearer {NEO_TOKEN}"],
        capture_output=True)
    try:
        raw = r.stdout.decode("utf-8")
    except UnicodeDecodeError:
        raise RuntimeError(f"Binary response received for text endpoint — use neo_get_binary instead (url: {url})")
    body, _, status = raw.rpartition("\n__STATUS__")
    status = status.strip()
    if status == "401":
        raise RuntimeError(f"NEO TOKEN EXPIRED (401) — provide a fresh token (url: {url})")
    if status == "404":
        return ""  # Resource not found — treat as empty (app/account has no items)
    if status.startswith("4") or status.startswith("5"):
        raise RuntimeError(f"NEO API error {status} fetching {url}")
    return body


def neo_get_json(url):
    return json.loads(neo_get_text(url))


def neo_get_binary(url):
    """Return raw bytes from Neo API."""
    r = subprocess.run(
        ["curl", "-s", "-w", "\n__STATUS__%{http_code}", url,
         "-H", f"Authorization: Bearer {NEO_TOKEN}"],
        capture_output=True)
    raw = r.stdout
    # Split off the status suffix (ASCII) from the binary body
    marker = b"\n__STATUS__"
    idx = raw.rfind(marker)
    if idx == -1:
        return raw
    body = raw[:idx]
    status = raw[idx + len(marker):].decode("ascii", errors="replace").strip()
    if status == "401":
        raise RuntimeError(f"NEO TOKEN EXPIRED (401) — provide a fresh token (url: {url})")
    if status == "404":
        return b""  # Resource not found — treat as empty (app/account has no items)
    if status.startswith("4") or status.startswith("5"):
        raise RuntimeError(f"NEO API error {status} fetching {url}")
    return body


def neo_get_headers(url):
    r = subprocess.run(
        ["curl", "-s", "-D", "-", "-o", "/dev/null", url, "-H", f"Authorization: Bearer {NEO_TOKEN}"],
        capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"curl failed fetching headers for {url}: {r.stderr.decode('utf-8', errors='replace').strip()}")
    return r.stdout.decode("utf-8")


def is_error_text(text):
    low = text.lower()
    return any(x in low for x in ["exception", "not found", "<html",
                                    "forbidden", "description\":"])


def parse_item_list(raw):
    if not raw or not raw.strip() or is_error_text(raw):
        return []
    return [x.strip() for x in raw.strip().splitlines()
            if x.strip() and len(x.strip()) < 256 and " " not in x.strip()
            and not x.strip().startswith("com.sap")]


def parse_properties(text):
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            key, _, value = line.partition("=")
            props[key.strip()] = value.replace("\\:", ":").replace("\\/", "/").replace("\\=", "=")
    return props


# ---------------------------------------------------------------------------
# CF helpers
# ---------------------------------------------------------------------------

def cf_cmd(args):
    r = subprocess.run(["cf"] + args, capture_output=True, text=True)
    return r.stdout + r.stderr


def cf_get_token(token_url, client_id, client_secret):
    r = subprocess.run([
        "curl", "-s", "-X", "POST", token_url,
        "-u", f"{client_id}:{client_secret}",
        "-d", "grant_type=client_credentials"
    ], capture_output=True, text=True)
    try:
        return json.loads(r.stdout)["access_token"].strip()
    except (json.JSONDecodeError, KeyError) as e:
        raise RuntimeError(f"Failed to get CF token from {token_url}: {e}\n{r.stdout[:300]}")


def cf_get_service_key_credentials(instance_name, key_name):
    r = subprocess.run(["cf", "service-key", instance_name, key_name],
                       capture_output=True, text=True)
    lines = r.stdout.strip().splitlines()
    json_start = next((i for i, l in enumerate(lines) if l.strip().startswith("{")), None)
    if json_start is None:
        return None, r.stdout
    try:
        creds = json.loads("\n".join(lines[json_start:]))["credentials"]
    except (json.JSONDecodeError, KeyError) as e:
        return None, f"Failed to parse service key JSON: {e}\n{r.stdout[:300]}"
    auth_url = creds["url"].rstrip("/")
    return {
        "client_id": creds["clientid"],
        "client_secret": creds["clientsecret"],
        "token_url": auth_url + "/oauth/token",
        "dest_api_url": creds["uri"],
    }, None


def cf_post(dest_api_url, token, endpoint, payload, name, ok_list, fail_list):
    r = subprocess.run([
        "curl", "-s", "-w", "\n%{http_code}", "-X", "POST",
        f"{dest_api_url}{endpoint}",
        "-H", f"Authorization: Bearer {token}",
        "-H", "Content-Type: application/json",
        "-d", json.dumps(payload)
    ], capture_output=True, text=True)
    lines = r.stdout.strip().split("\n")
    status = lines[-1]
    if status in ("200", "201", "207", "409"):
        ok_list.append(name)
    else:
        fail_list.append(name)


def cf_ensure_instance_and_creds(instance_name, key_name, cf_app_name=None):
    """Create CF Destination Service instance, optionally bind a CF app, return credentials dict."""
    out = cf_cmd(["create-service", "destination", "lite", instance_name])
    if "already exists" not in out.lower():
        for _ in range(12):
            status_out = cf_cmd(["service", instance_name])
            if "create succeeded" in status_out.lower():
                break
            if "create failed" in status_out.lower() or "failed to create" in status_out.lower():
                raise RuntimeError(f"Service '{instance_name}' failed to create")
            time.sleep(5)
        else:
            raise RuntimeError(f"Service '{instance_name}' did not reach 'create succeeded' within 60 s")

    if cf_app_name:
        out = cf_cmd(["bind-service", cf_app_name, instance_name])
        if "error" in out.lower() or "failed" in out.lower() or "not found" in out.lower():
            if "already bound" not in out.lower():
                raise RuntimeError(f"Failed to bind '{cf_app_name}' → '{instance_name}': {out[:300]}")

    cf_cmd(["create-service-key", instance_name, key_name])
    creds, err = cf_get_service_key_credentials(instance_name, key_name)
    if creds is None:
        raise RuntimeError(f"Could not parse service key credentials: {err[:300]}")
    return creds


def cf_delete_service_key(instance_name, key_name):
    cf_cmd(["delete-service-key", instance_name, key_name, "-f"])


# ---------------------------------------------------------------------------
# Step 1: Fetch Neo application list
# ---------------------------------------------------------------------------

def has_sensitive_oauth_credentials(props):
    """Return True if this destination carries credentials that require explicit consent."""
    if "tokenService.KeyStorePassword" not in props:
        return False
    auth = props.get("Authentication", "")
    return auth in ("OAuth2SAMLBearerAssertion", "OAuth2ClientCredentials")


def fetch_neo_apps():
    all_apps = []
    url = _neo_url(NEO_LIFECYCLE_URL)
    while url:
        d = neo_get_json(url)
        page_apps = d.get("apps", [])
        all_apps.extend(page_apps)
        next_path = d.get("nextUrl")
        url = _neo_url(next_path if next_path.startswith("http") else f"https://{HOST}{next_path}") if isinstance(next_path, str) and next_path else None
    return list(dict.fromkeys(
        a["entity"]["applicationName"] for a in all_apps
        if "entity" in a and "applicationName" in a["entity"]
    ))


# ---------------------------------------------------------------------------
# Step 2: Fetch account-level items from Neo (in memory)
# ---------------------------------------------------------------------------

def fetch_account_items():
    """Return (destinations: dict[name->props], keystores: dict[name->bytes])."""
    destinations = {}
    keystores = {}

    # Configuration API — destinations and keystores attached to destinations
    items = parse_item_list(neo_get_text(_neo_url(f"{CONFIG_BASE}/connectivity/")))
    for name in items:
        if re.search(r'\.(jks|p12|pem|jceks)$', name, re.IGNORECASE):
            if MIGRATE_KEYSTORES:
                data = neo_get_binary(_neo_url(f"{CONFIG_BASE}/connectivity/{name}"))
                if data:
                    keystores[name] = data
        else:
            content = neo_get_text(_neo_url(f"{CONFIG_BASE}/connectivity/{name}"))
            if is_error_text(content):
                continue
            props = parse_properties(content)
            if props:
                destinations[name] = props

    # Keystore API — standalone keystores registered at account level
    if MIGRATE_KEYSTORES:
        try:
            ks_data = neo_get_json(_neo_url(f"{KEYSTORE_BASE}/accounts/{ACCOUNT}"))
            for ks in ks_data.get("keystores", []):
                name = ks["name"]
                hdrs = neo_get_headers(_neo_url(f"{KEYSTORE_BASE}/accounts/{ACCOUNT}/{name}"))
                ext = next(
                    (l.split(":", 1)[1].strip() for l in hdrs.splitlines()
                     if l.lower().startswith("x-sap-keystore-type:")),
                    "jks"
                ).strip() or "jks"
                data = neo_get_binary(_neo_url(f"{KEYSTORE_BASE}/accounts/{ACCOUNT}/{name}"))
                if data:
                    keystores[f"{name}.{ext}"] = data
        except Exception:
            pass

    return destinations, keystores


# ---------------------------------------------------------------------------
# Step 3: Fetch app-level items from Neo (in memory, parallel)
# ---------------------------------------------------------------------------

def fetch_app_items(app):
    """Return (destinations: dict[name->props], keystores: dict[name->bytes])."""
    destinations = {}
    keystores = {}

    # Keystore API
    if MIGRATE_KEYSTORES:
        try:
            ks_data = neo_get_json(_neo_url(f"{KEYSTORE_BASE}/accounts/{ACCOUNT}/apps/{app}"))
            for ks in ks_data.get("keystores", []):
                name = ks["name"]
                hdrs = neo_get_headers(_neo_url(f"{KEYSTORE_BASE}/accounts/{ACCOUNT}/apps/{app}/{name}"))
                ext = next(
                    (l.split(":", 1)[1].strip() for l in hdrs.splitlines()
                     if l.lower().startswith("x-sap-keystore-type:")),
                    "jks"
                ).strip() or "jks"
                data = neo_get_binary(_neo_url(f"{KEYSTORE_BASE}/accounts/{ACCOUNT}/apps/{app}/{name}"))
                if data:
                    keystores[f"{name}.{ext}"] = data
        except Exception:
            pass

    # Destination API
    items = parse_item_list(
        neo_get_text(_neo_url(f"{CONFIG_BASE}/appliances/{app}/components/web/base/connectivity/")))
    for name in items:
        if re.search(r'\.(jks|p12|pem|jceks)$', name, re.IGNORECASE):
            if MIGRATE_KEYSTORES:
                data = neo_get_binary(
                    _neo_url(f"{CONFIG_BASE}/appliances/{app}/components/web/base/connectivity/{name}"))
                if data:
                    keystores[name] = data
        else:
            content = neo_get_text(
                _neo_url(f"{CONFIG_BASE}/appliances/{app}/components/web/base/connectivity/{name}"))
            if is_error_text(content):
                continue
            props = parse_properties(content)
            if props:
                destinations[name] = props

    return destinations, keystores


# ---------------------------------------------------------------------------
# Step 4: Upload account-level items to CF
# ---------------------------------------------------------------------------

def upload_account_items(destinations, keystores, creds):
    token = cf_get_token(creds["token_url"], creds["client_id"], creds["client_secret"])
    dest_api_url = creds["dest_api_url"]

    for name, props in sorted(destinations.items()):
        if props.get("Authentication") == "InternalSystemAuthentication":
            RESULTS.account_dest_skip.append(name)
            continue
        if not MIGRATE_OAUTH_CREDENTIALS and has_sensitive_oauth_credentials(props):
            RESULTS.account_dest_skip.append(name)
            continue
        cf_post(dest_api_url, token, "/destination-configuration/v1/subaccountDestinations",
                props, name, RESULTS.account_dest_ok, RESULTS.account_dest_fail)

    for name, data in sorted(keystores.items()):
        if not data:
            RESULTS.account_ks_fail.append(name)
            continue
        cf_post(dest_api_url, token, "/destination-configuration/v1/subaccountCertificates",
                {"Name": name, "Content": base64.b64encode(data).decode("utf-8")},
                name, RESULTS.account_ks_ok, RESULTS.account_ks_fail)


# ---------------------------------------------------------------------------
# Step 5: Upload app-level items to CF
# ---------------------------------------------------------------------------

def upload_app_items(app, destinations, keystores, cf_app_name):
    """Returns True if a service key was created (caller must delete it), False otherwise."""
    r = RESULTS.app(app)

    if not destinations and not keystores:
        return False

    instance_name = f"{app}-destination"
    key_name = f"{instance_name}-key"

    try:
        creds = cf_ensure_instance_and_creds(instance_name, key_name, cf_app_name)
    except RuntimeError as e:
        for name, props in destinations.items():
            auth = props.get("Authentication", "")
            if auth == "InternalSystemAuthentication" or (
                    not MIGRATE_OAUTH_CREDENTIALS and has_sensitive_oauth_credentials(props)):
                r["dest_skip"].append(name)
            else:
                r["dest_fail"].append(name)
        for name in keystores:
            r["ks_fail"].append(name)
        return False

    token = cf_get_token(creds["token_url"], creds["client_id"], creds["client_secret"])
    dest_api_url = creds["dest_api_url"]

    for name, props in sorted(destinations.items()):
        auth = props.get("Authentication", "")
        if auth == "InternalSystemAuthentication":
            r["dest_skip"].append(name)
            continue
        if not MIGRATE_OAUTH_CREDENTIALS and has_sensitive_oauth_credentials(props):
            r["dest_skip"].append(name)
            continue
        if auth == "AppToAppSSO":
            props = dict(props)
            props["Authentication"] = "OAuth2UserTokenExchange"
            props["clientId"] = creds["client_id"]
            props["clientSecret"] = creds["client_secret"]
            props["tokenServiceURL"] = creds["token_url"]
            props["tokenServiceURLType"] = "Dedicated"
        cf_post(dest_api_url, token,
                "/destination-configuration/v1/instanceDestinations",
                props, name, r["dest_ok"], r["dest_fail"])

    for name, data in sorted(keystores.items()):
        if not data:
            r["ks_fail"].append(name)
            continue
        cf_post(dest_api_url, token,
                "/destination-configuration/v1/instanceCertificates",
                {"Name": name, "Content": base64.b64encode(data).decode("utf-8")},
                name, r["ks_ok"], r["ks_fail"])

    return True


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    try:
        app_names = fetch_neo_apps()
    except RuntimeError as e:
        print(f"ERROR fetching Neo app list: {e}", file=sys.stderr)
        sys.exit(1)
    try:
        account_destinations, account_keystores = fetch_account_items()
    except RuntimeError as e:
        print(f"ERROR fetching account-level items: {e}", file=sys.stderr)
        sys.exit(1)

    app_data = {}
    with ThreadPoolExecutor(max_workers=WORKERS) as executor:
        futures = {executor.submit(fetch_app_items, app): app for app in app_names}
        for future in as_completed(futures):
            app = futures[future]
            try:
                app_data[app] = future.result()
            except Exception as e:
                print(f"ERROR fetching data for app '{app}': {e}", file=sys.stderr)
                app_data[app] = ({}, {})  # treat as empty; error already printed

    created_keys = []  # (instance_name, key_name) — deleted after all uploads

    try:
        # Account-level upload
        if account_destinations or account_keystores:
            account_instance = os.environ.get("ACCOUNT_INSTANCE_NAME") or f"{ACCOUNT}-account-destinations"
            account_key = f"{account_instance}-key"
            try:
                creds = cf_ensure_instance_and_creds(account_instance, account_key, None)
                upload_account_items(account_destinations, account_keystores, creds)
                created_keys.append((account_instance, account_key))
            except Exception as e:
                for name in account_destinations:
                    RESULTS.account_dest_fail.append(name)
                for name in account_keystores:
                    RESULTS.account_ks_fail.append(name)

        # App-level upload
        for app in sorted(app_data.keys()):
            if app not in APP_MAPPING:
                continue
            destinations, keystores = app_data[app]
            if destinations or keystores:
                key_created = upload_app_items(app, destinations, keystores, APP_MAPPING[app])
                if key_created:
                    instance_name = f"{app}-destination"
                    created_keys.append((instance_name, f"{instance_name}-key"))

    finally:
        # Delete all service keys — credentials are no longer needed
        for instance_name, key_name in created_keys:
            cf_delete_service_key(instance_name, key_name)

    # Summary output
    print("=== Migration Summary ===")
    print(f"\nAccount level:")
    print(f"  Destinations — OK: {len(RESULTS.account_dest_ok)}, SKIPPED: {len(RESULTS.account_dest_skip)}, FAILED: {len(RESULTS.account_dest_fail)}")
    if RESULTS.account_dest_skip:
        for name in RESULTS.account_dest_skip:
            print(f"    - {name} (skipped)")
        if not MIGRATE_OAUTH_CREDENTIALS:
            print(f"    Note: some destinations may have been skipped because OAuth2SAMLBearerAssertion "
                  f"and OAuth2ClientCredentials destinations with extractable credentials were not migrated.")
    if RESULTS.account_dest_fail:
        for name in RESULTS.account_dest_fail:
            print(f"    - {name}")
    print(f"  Keystores    — OK: {len(RESULTS.account_ks_ok)}, FAILED: {len(RESULTS.account_ks_fail)}")
    if RESULTS.account_ks_fail:
        for name in RESULTS.account_ks_fail:
            print(f"    - {name}")

    print(f"\nApplication level:")
    for app in sorted(RESULTS.apps.keys()):
        r = RESULTS.apps[app]
        print(f"  {app}:")
        print(f"    Destinations — OK: {len(r['dest_ok'])}, SKIPPED: {len(r['dest_skip'])}, FAILED: {len(r['dest_fail'])}")
        if r["dest_skip"]:
            for name in r["dest_skip"]:
                print(f"      - {name} (skipped)")
            if not MIGRATE_OAUTH_CREDENTIALS:
                print(f"      Note: some destinations may have been skipped because OAuth2SAMLBearerAssertion "
                      f"and OAuth2ClientCredentials destinations with extractable credentials were not migrated.")
        if r["dest_fail"]:
            for name in r["dest_fail"]:
                print(f"      - {name}")
        print(f"    Keystores    — OK: {len(r['ks_ok'])}, FAILED: {len(r['ks_fail'])}")
        if r["ks_fail"]:
            for name in r["ks_fail"]:
                print(f"      - {name}")


if __name__ == "__main__":
    if "--list-apps" in sys.argv:
        try:
            app_names = fetch_neo_apps()
        except RuntimeError as e:
            print(f"ERROR fetching Neo app list: {e}", file=sys.stderr)
            sys.exit(1)
        for name in app_names:
            print(name)
    else:
        main()
