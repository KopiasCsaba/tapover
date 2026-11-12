#!/usr/bin/env bash
# Generate the release signing key and store it as an attachment of a KeePassXC entry.
# The keystore password is the entry's password. Usage: keygen.sh "CN=Your Name"
set -euo pipefail
cd "$(dirname "$0")/../.."
DNAME="${1:-}"
[ -n "$DNAME" ] || { echo "usage: $0 \"CN=Your Name\"  (public: anyone can read it from the APK)" >&2; exit 1; }
# One or more NAME=value parts, comma-separated, starting with CN= (no stray commas).
[[ "$DNAME" =~ ^CN=[^,=]+(,[A-Za-z]+=[^,=]+)*$ ]] \
    || { echo "error: malformed DNAME '$DNAME' (expected e.g. \"CN=Your Name\" or \"CN=Your Name,C=HU\")" >&2; exit 1; }
CREATE_ENTRY=1
. tools/release/common.sh

existing=$(kp show --show-attachments "$KDBX" "$KDBX_ENTRY") || die "cannot inspect entry '$KDBX_ENTRY' in $KDBX"
if grep -qF "$KDBX_ATTACHMENT" <<<"$existing"; then
    die "entry '$KDBX_ENTRY' already has '$KDBX_ATTACHMENT'; refusing to overwrite a signing key (use 'make fingerprint' to refresh the pinned certificate)"
fi

# Generate in a RAM-only tmpfs inside the container, hand the bytes out on stdout.
# The keystore password arrives on stdin (not env/argv, so it is not visible in `docker inspect`).
printf '%s\n' "$KS_PASS" | docker run --rm -i -u "$(id -u):$(id -g)" -e DNAME="$DNAME" -e KEY_ALIAS="$KEY_ALIAS" \
    --tmpfs /keys "$IMAGE" sh -ec '
        IFS= read -r KS_PASS; export KS_PASS
        keytool -genkeypair -storetype PKCS12 -keystore /keys/ks.p12 -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 4096 -validity 10000 -dname "$DNAME" \
            -storepass:env KS_PASS -keypass:env KS_PASS >&2
        cat /keys/ks.p12' > "$SECRET_DIR/ks.p12"
[ -s "$SECRET_DIR/ks.p12" ] || die "key generation produced no keystore"

kp attachment-import "$KDBX" "$KDBX_ENTRY" "$KDBX_ATTACHMENT" "$SECRET_DIR/ks.p12"
echo "Stored '$KDBX_ATTACHMENT' in entry '$KDBX_ENTRY' of $KDBX."

# Verify by reading the stored keystore back with the password, and pin its certificate.
FP=$(ks_fingerprint "$SECRET_DIR/ks.p12")
[[ "$FP" =~ ^[0-9a-f]{64}$ ]] || die "could not read the certificate fingerprint from the new keystore"
printf '%s\n' "$FP" > "$CERT_PIN_FILE"
echo "Certificate SHA-256: $FP"
echo "Pinned in $CERT_PIN_FILE (commit it). For F-Droid metadata:  AllowedAPKSigningKeys: $FP"
echo "Back up $KDBX now. Losing it means you can never update the app again."
