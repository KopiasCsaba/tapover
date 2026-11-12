# Shared settings for the signing scripts. Settings come from .env (gitignored, see .env.example);
# a variable already set in the environment (e.g. `make sign KDBX=...`) wins over .env.
die() { echo "error: $*" >&2; exit 1; }

# Key material and the keystore password are streamed to the Docker daemon, so it must be local.
case "${DOCKER_HOST:-}" in
    ''|unix://*) ;;
    *) die "DOCKER_HOST=$DOCKER_HOST is not a local daemon; the signing scripts only run against a local unix socket" ;;
esac
if [ -z "${DOCKER_HOST:-}" ]; then
    _ctx=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)
    case "$_ctx" in
        ''|unix://*) ;;
        *) die "the active Docker context points to $_ctx, not a local socket; refusing to handle signing keys" ;;
    esac
fi

if [ -f .env ]; then
    _keep=$(for v in KDBX KDBX_ENTRY KDBX_ATTACHMENT KEY_ALIAS; do
        if [ -n "${!v:-}" ]; then printf '%s=%q\n' "$v" "${!v}"; fi; done)
    set -a; . ./.env; set +a
    eval "$_keep"
fi
[ -n "${KDBX:-}" ] || die "KDBX is not set; copy .env.example to .env and set the database path"
KDBX_ENTRY="${KDBX_ENTRY:-tapover-release}"
KDBX_ATTACHMENT="${KDBX_ATTACHMENT:-tapover-release.p12}"
KEY_ALIAS="${KEY_ALIAS:-tapover}"
# Keep in sync with IMAGE in the Makefile (digest-pinned).
IMAGE="${IMAGE:-ghcr.io/cirruslabs/android-sdk:35}"
REPO_ROOT="${REPO_ROOT:-$PWD}"
BUILD_TOOLS=/opt/android-sdk-linux/build-tools/35.0.0

command -v keepassxc-cli >/dev/null || die "keepassxc-cli not found (install KeePassXC 2.7 or newer)"
keepassxc-cli --help </dev/null 2>&1 | grep -q "^attachment-export" \
    || die "keepassxc-cli has no attachment commands; KeePassXC 2.7 or newer is needed (found $(keepassxc-cli --version))"
[ -f "$KDBX" ] || die "database not found: $KDBX (create it in KeePassXC first, or fix KDBX in .env)"

# Ask for the master password once; every keepassxc-cli call reads it from stdin.
read -rsp "Master password for $KDBX: " DB_PASS; echo
kp() { local cmd=$1; shift; printf '%s\n' "$DB_PASS" | keepassxc-cli "$cmd" -q "$@"; }

# Private scratch dir for key material, deleted on exit: RAM-backed /dev/shm by default.
# (keepassxc-cli's --stdout mangles binary attachments, so the keystore must go through a file.)
# No silent fallback to the repo: without /dev/shm, set SECRET_TMP yourself to a RAM disk or
# an encrypted volume (e.g. SECRET_TMP=/path/to/ramdisk make sign).
if [ -z "${SECRET_TMP:-}" ]; then
    [ -d /dev/shm ] && [ -w /dev/shm ] \
        || die "/dev/shm is not available; set SECRET_TMP to a RAM disk or encrypted volume for the key material"
    SECRET_TMP=/dev/shm
fi
[ -d "$SECRET_TMP" ] && [ -w "$SECRET_TMP" ] || die "SECRET_TMP '$SECRET_TMP' is not a writable directory"
SECRET_DIR=$(mktemp -d "$SECRET_TMP/tapover-key.XXXXXX")
chmod 700 "$SECRET_DIR"
trap 'rm -rf "$SECRET_DIR"' EXIT

# Read the keystore password from the entry. With CREATE_ENTRY=1 (keygen), a missing entry is
# created first with a generated 40-character password.
unset KS_PASS   # never inherit/export it: it only goes to containers via stdin
ks_err="$SECRET_DIR/err"
if ! KS_PASS=$(kp show -a password "$KDBX" "$KDBX_ENTRY" 2>"$ks_err"); then
    if [ "${CREATE_ENTRY:-0}" = 1 ] && grep -q "Could not find entry" "$ks_err"; then
        kp add -g -L 40 -l -U -n "$KDBX" "$KDBX_ENTRY" >/dev/null || die "could not create entry '$KDBX_ENTRY'"
        echo "Created entry '$KDBX_ENTRY' with a generated password."
        KS_PASS=$(kp show -a password "$KDBX" "$KDBX_ENTRY") || die "cannot read the new entry '$KDBX_ENTRY'"
    else
        cat "$ks_err" >&2
        die "cannot read entry '$KDBX_ENTRY' from $KDBX"
    fi
fi
rm -f "$ks_err"
[ ${#KS_PASS} -ge 12 ] || die "entry '$KDBX_ENTRY' needs a password of at least 12 characters"

# Print the lowercase, colon-free SHA-256 of the keystore's certificate (F-Droid AllowedAPKSigningKeys
# format). The password goes to the container on stdin ahead of the keystore, never via env/argv.
# usage: ks_fingerprint <keystore file>
ks_fingerprint() {
    local out
    out=$({ printf '%s\n' "$KS_PASS"; cat "$1"; } | docker run --rm -i -u "$(id -u):$(id -g)" \
        -e KEY_ALIAS="$KEY_ALIAS" --tmpfs /keys "$IMAGE" sh -ec '
            IFS= read -r KS_PASS; export KS_PASS
            cat > /keys/ks.p12
            keytool -list -v -keystore /keys/ks.p12 -storepass:env KS_PASS -alias "$KEY_ALIAS"') \
        || die "keytool failed to read the keystore"
    printf '%s\n' "$out" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n1 | tr -d ':' | tr 'A-F' 'a-f'
}

CERT_PIN_FILE=tools/release/signing-cert.sha256
