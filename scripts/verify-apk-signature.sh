#!/usr/bin/env bash
# Verify that a Flux mobile APK is signed with the historically distributed
# certificate, so it installs as an in-place OTA update over the app already
# on the user's device (same applicationId, same signer, versionCode >= vc57).
#
# Usage:
#   scripts/verify-apk-signature.sh [options]
#
# Options:
#   --apk PATH                 APK to inspect
#                              (default: apps/mobile-controller/app/build/outputs/apk/debug/app-debug.apk)
#   --expected-sha256 HEX      Expected signer certificate SHA-256
#   --application-id ID        Expected package name
#   --min-version-code N       Minimum acceptable versionCode
#   --apksigner PATH           apksigner binary (auto-detected from ANDROID_HOME/PATH)
#   --aapt2 PATH               aapt2 binary (auto-detected from ANDROID_HOME/PATH)
#   --self-test                Run the normal and failure paths against the
#                              reference APK (FLUX_REFERENCE_APK overrides its path)
#   -h, --help                 Show this help
#
# Exit codes: 0 = verified, 1 = verification failed, 2 = usage/tooling error.
set -u
set -o pipefail

readonly TAG="[FluxUx]"
readonly EXPECTED_SHA256_DEFAULT="ec560d8d7ae3a15cc06c876a339e8e7e6190d37aca744e105a839710ba470c05"
readonly EXPECTED_APP_ID_DEFAULT="top.qiuli55.flux.mobile"
readonly MIN_VERSION_CODE_DEFAULT=57

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_APK="${REPO_ROOT}/apps/mobile-controller/app/build/outputs/apk/debug/app-debug.apk"

log() {
    printf '%s %s\n' "${TAG}" "$*"
}

warn() {
    printf '%s WARNING: %s\n' "${TAG}" "$*" >&2
}

die() {
    local code="$1"
    shift
    printf '%s ERROR: %s\n' "${TAG}" "$*" >&2
    exit "${code}"
}

usage() {
    sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# Resolve a tool from an explicit override, then PATH, then the newest
# Android SDK build-tools directory.
find_tool() {
    local override="$1"
    local name="$2"
    local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

    if [[ -n "${override}" ]]; then
        [[ -x "${override}" ]] || die 2 "${name} not executable: ${override}"
        printf '%s\n' "${override}"
        return 0
    fi
    if command -v "${name}" >/dev/null 2>&1; then
        command -v "${name}"
        return 0
    fi
    if [[ -n "${sdk_root}" && -d "${sdk_root}/build-tools" ]]; then
        local candidate
        candidate="$(find "${sdk_root}/build-tools" -mindepth 2 -maxdepth 2 -type f -name "${name}" 2>/dev/null \
            | sort -V | tail -1)"
        if [[ -n "${candidate}" && -x "${candidate}" ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    fi
    die 2 "${name} not found; set --${name} or ANDROID_HOME"
}

normalize_sha256() {
    printf '%s' "$1" | tr -d ':' | tr '[:upper:]' '[:lower:]'
}

self_test() {
    local reference="${FLUX_REFERENCE_APK:-/workspace/flux-mobile-0.9.0-vc57.apk}"

    [[ -f "${reference}" ]] || die 2 \
        "--self-test: reference APK not found at ${reference}; set FLUX_REFERENCE_APK"

    log "self-test: normal path (reference APK must pass)"
    if ! "$0" --apk "${reference}"; then
        die 1 "self-test: normal path failed for ${reference}"
    fi

    log "self-test: error path (wrong expected digest must be rejected)"
    if "$0" --apk "${reference}" --expected-sha256 "0000000000000000000000000000000000000000000000000000000000000000" >/dev/null 2>&1; then
        die 1 "self-test: wrong digest was accepted"
    fi
    log "self-test: error path passed (digest mismatch rejected)"

    log "self-test: error path (versionCode below minimum must be rejected)"
    if "$0" --apk "${reference}" --min-version-code 999999 >/dev/null 2>&1; then
        die 1 "self-test: too-low versionCode was accepted"
    fi
    log "self-test: error path passed (versionCode rejection works)"

    log "self-test: all checks passed"
}

apk=""
expected_sha256="${EXPECTED_SHA256_DEFAULT}"
expected_app_id="${EXPECTED_APP_ID_DEFAULT}"
min_version_code="${MIN_VERSION_CODE_DEFAULT}"
apksigner_override=""
aapt2_override=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) apk="${2:-}"; shift 2 ;;
        --expected-sha256) expected_sha256="${2:-}"; shift 2 ;;
        --application-id) expected_app_id="${2:-}"; shift 2 ;;
        --min-version-code) min_version_code="${2:-}"; shift 2 ;;
        --apksigner) apksigner_override="${2:-}"; shift 2 ;;
        --aapt2) aapt2_override="${2:-}"; shift 2 ;;
        --self-test) self_test; exit 0 ;;
        -h|--help) usage; exit 0 ;;
        *) die 2 "unknown argument: $1 (try --help)" ;;
    esac
done

[[ -n "${apk}" ]] || apk="${DEFAULT_APK}"
[[ -f "${apk}" ]] || die 1 "APK not found: ${apk}"
[[ -s "${apk}" ]] || die 1 "APK is empty: ${apk}"

expected_sha256="$(normalize_sha256 "${expected_sha256}")"
[[ "${expected_sha256}" =~ ^[0-9a-f]{64}$ ]] || die 2 "invalid --expected-sha256: ${expected_sha256}"

apksigner="$(find_tool "${apksigner_override}" apksigner)" || exit 2
aapt2="$(find_tool "${aapt2_override}" aapt2)" || exit 2

log "verifying APK: ${apk}"
log "using apksigner: ${apksigner}"

cert_output="$("${apksigner}" verify --print-certs "${apk}" 2>&1)" \
    || die 1 "apksigner failed to verify ${apk}: ${cert_output}"

actual_sha256="$(printf '%s\n' "${cert_output}" \
    | sed -nE 's/^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+).*/\1/p' \
    | head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
[[ -n "${actual_sha256}" ]] || die 1 "could not read signer #1 SHA-256 digest from apksigner output"

if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
    die 1 "signer cert mismatch: expected ${expected_sha256}, got ${actual_sha256}" \
        " (APK would be rejected as an in-place update)"
fi
log "PASS signer cert SHA-256 = ${actual_sha256}"

badging="$("${aapt2}" dump badging "${apk}" 2>&1)" \
    || die 1 "aapt2 failed to read badging from ${apk}: ${badging}"
package_line="$(printf '%s\n' "${badging}" | sed -n '1p')"
actual_app_id="$(printf '%s\n' "${package_line}" | sed -nE "s/.*package: name='([^']+)'.*/\1/p")"
actual_version_code="$(printf '%s\n' "${package_line}" | sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p")"
[[ -n "${actual_app_id}" ]] || die 1 "could not read applicationId from aapt2 output"
[[ -n "${actual_version_code}" ]] || die 1 "could not read versionCode from aapt2 output"

if [[ "${actual_app_id}" != "${expected_app_id}" ]]; then
    die 1 "applicationId mismatch: expected ${expected_app_id}, got ${actual_app_id}"
fi
log "PASS applicationId = ${actual_app_id}"

if (( actual_version_code < min_version_code )); then
    die 1 "versionCode ${actual_version_code} < required ${min_version_code}; an in-place update would fail"
fi
log "PASS versionCode = ${actual_version_code} (>= ${min_version_code})"

log "OK: APK is signed with the distributed certificate and can update in place"
