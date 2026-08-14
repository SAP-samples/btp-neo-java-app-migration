#!/usr/bin/env bash
# neo-api-setup-template.sh
#
# Source this file after writing the telemetry files:
#   source "path/to/shared/neo-api-setup-template.sh"
#
# Requires these variables to be set in the calling environment:
#   REGION_HOST    e.g. hana.ondemand.com
#   SUBACCOUNT     Neo subaccount technical name
#   BEARER_TOKEN   valid Neo Platform API bearer token (set before calling fetch_neo_*())
#
# Requires these files to exist (written by the skill before sourcing):
#   ~/.neo-migration-consents/neo-telemetry-installation.txt                          ← installation_id
#   ~/.neo-migration-consents/$SUBACCOUNT/neo-telemetry-consent.txt                   ← consent (per Neo subaccount)
#   ~/.neo-migration-consents/$SUBACCOUNT/$CF_SUBACCOUNT_GUID/neo-telemetry-session.txt ← session_id (per Neo+CF pair)
#
# Provides:
#   - NEO API base URL exports built from REGION_HOST + SUBACCOUNT
#   - CORRELATION_PARAMS export (populated if consent=yes, empty if consent=no)
#   - fetch_neo_*() functions for all NEO API calls

# ---------------------------------------------------------------------------
# Validate required env vars
# ---------------------------------------------------------------------------

if [ -z "${REGION_HOST}" ]; then
    echo "ERROR: REGION_HOST is not set — cannot build NEO API URLs." >&2
    echo "       Set REGION_HOST to the Neo landscape host (e.g. hana.ondemand.com) and retry." >&2
    return 1
fi
if [ -z "${SUBACCOUNT}" ]; then
    echo "ERROR: SUBACCOUNT is not set — cannot build NEO API URLs." >&2
    echo "       Set SUBACCOUNT to the Neo subaccount technical name and retry." >&2
    return 1
fi

# ---------------------------------------------------------------------------
# Resolve paths
# ---------------------------------------------------------------------------

_NEO_MIGRATION_HOME="${XDG_DATA_HOME:-${APPDATA:-$HOME}}/.neo-migration"
_NEO_CONSENTS_HOME="${XDG_DATA_HOME:-${APPDATA:-$HOME}}/.neo-migration-consents"

if [ -z "${CF_SUBACCOUNT_GUID}" ]; then
    echo "ERROR: CF_SUBACCOUNT_GUID is not set — resolve it before sourcing this script." >&2
    echo "       Run the following to resolve it automatically:" >&2
    echo "         _CF_ORG=\$(cf target 2>/dev/null | awk '/^org:/{print \$2}')" >&2
    echo "         CF_SUBACCOUNT_GUID=\$(btp list accounts/subaccount 2>/dev/null | awk -v org=\"\${_CF_ORG}\" 'NR>2{guid=\$1;subdomain=\$3;if(index(org,subdomain)>0){print guid;exit}}')" >&2
    return 1
fi

_SUBACCOUNT_DIR="${_NEO_MIGRATION_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}"
mkdir -p "${_NEO_MIGRATION_HOME}/${SUBACCOUNT}"
mkdir -p "${_SUBACCOUNT_DIR}"
mkdir -p "${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}"

_TELEMETRY_FILE="${_NEO_CONSENTS_HOME}/neo-telemetry-installation.txt"
_CONSENT_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/neo-telemetry-consent.txt"
_SESSION_FILE="${_NEO_CONSENTS_HOME}/${SUBACCOUNT}/${CF_SUBACCOUNT_GUID}/neo-telemetry-session.txt"

# ---------------------------------------------------------------------------
# Read telemetry files — must be written by the skill before sourcing
# ---------------------------------------------------------------------------

if [ ! -f "${_CONSENT_FILE}" ]; then
    echo "ERROR: ${_CONSENT_FILE} not found." >&2
    echo "       Run the telemetry consent block in Step 1b first, then re-run this source command." >&2
    return 1
fi

_CONSENT=$(grep "^consent=" "${_CONSENT_FILE}" | cut -d= -f2 | tr '[:upper:]' '[:lower:]')

if [ "${_CONSENT}" = "yes" ]; then
    if [ ! -f "${_SESSION_FILE}" ]; then
        echo "ERROR: ${_SESSION_FILE} not found." >&2
        echo "       Run the session ID block in Step 1b first, then re-run this source command." >&2
        return 1
    fi
    _INSTALLATION_ID=$(grep "^neo_cf_migration_installation_id=" "${_TELEMETRY_FILE}" 2>/dev/null | cut -d= -f2)
    _MIGRATION_ID=$(grep "^neo_cf_migration_session_id=" "${_SESSION_FILE}" | cut -d= -f2)
    if [ -n "${_INSTALLATION_ID}" ] && [ -n "${_MIGRATION_ID}" ]; then
        export CORRELATION_PARAMS="neo_cf_migration_installation_id=${_INSTALLATION_ID}&neo_cf_migration_session_id=${_MIGRATION_ID}"
    else
        echo "WARNING: telemetry files are missing correlation IDs — telemetry disabled for this run." >&2
        export CORRELATION_PARAMS=""
    fi
else
    export CORRELATION_PARAMS=""
fi

# ---------------------------------------------------------------------------
# NEO API base URLs
# ---------------------------------------------------------------------------

export NEO_LIFECYCLE_URL="https://api.${REGION_HOST}/lifecycle/v1/accounts/${SUBACCOUNT}/apps"
export NEO_AUTHORIZATION_BASE="https://api.${REGION_HOST}/authorization/v1/accounts/${SUBACCOUNT}"
export NEO_TRUST_URL="https://apissecurity.${REGION_HOST}/trust/v2/accounts/${SUBACCOUNT}"
export NEO_CONFIG_BASE="https://configapi.${REGION_HOST}/configuration/api/rest/oauth/SPACES/${SUBACCOUNT}"
export NEO_KEYSTORE_BASE="https://api.${REGION_HOST}/keystore/v1"

# ---------------------------------------------------------------------------
# URL helper
# ---------------------------------------------------------------------------

_neo_append_params() {
    local url="$1"
    if [ -n "${CORRELATION_PARAMS}" ]; then
        if [[ "${url}" == *"?"* ]]; then
            echo "${url}&${CORRELATION_PARAMS}"
        else
            echo "${url}?${CORRELATION_PARAMS}"
        fi
    else
        echo "${url}"
    fi
}

# ---------------------------------------------------------------------------
# Lifecycle API
# ---------------------------------------------------------------------------

fetch_neo_apps() {
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_LIFECYCLE_URL}")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}

# ---------------------------------------------------------------------------
# Authorization API
# ---------------------------------------------------------------------------

fetch_neo_roles() {
    local app="$1"
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_AUTHORIZATION_BASE}/apps/${app}/roles")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}

fetch_neo_role_users() {
    local app="$1" role="$2"
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_AUTHORIZATION_BASE}/apps/${app}/roles/users?roleName=${role}")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}

fetch_neo_groups() {
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_AUTHORIZATION_BASE}/groups")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}

fetch_neo_group_roles() {
    local group="$1"
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_AUTHORIZATION_BASE}/groups/roles?groupName=${group}")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}

fetch_neo_group_users() {
    local group="$1"
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_AUTHORIZATION_BASE}/groups/users?groupName=${group}")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}

# ---------------------------------------------------------------------------
# Trust API
# ---------------------------------------------------------------------------

fetch_neo_trust() {
    curl -s -w "\n%{http_code}" \
        "$(_neo_append_params "${NEO_TRUST_URL}")" \
        -H "Authorization: Bearer ${BEARER_TOKEN}" \
        -H "Accept: application/json"
}
