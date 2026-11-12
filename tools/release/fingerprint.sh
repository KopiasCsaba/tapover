#!/usr/bin/env bash
# (Re)write tools/release/signing-cert.sha256 from the existing signing key in KeePassXC.
# Use it once for a key made before the pin existed, or after deliberately replacing the key.
set -euo pipefail
cd "$(dirname "$0")/../.."
. tools/release/common.sh

kp attachment-export "$KDBX" "$KDBX_ENTRY" "$KDBX_ATTACHMENT" "$SECRET_DIR/ks.p12" >/dev/null
[ -s "$SECRET_DIR/ks.p12" ] || die "empty keystore from KeePassXC"
FP=$(ks_fingerprint "$SECRET_DIR/ks.p12")
[[ "$FP" =~ ^[0-9a-f]{64}$ ]] || die "could not read the certificate fingerprint (wrong password or alias '$KEY_ALIAS'?)"
if [ -s "$CERT_PIN_FILE" ] && [ "$(tr -d ' \r\n' < "$CERT_PIN_FILE")" != "$FP" ]; then
    echo "warning: replacing a different pinned fingerprint ($(cat "$CERT_PIN_FILE"))" >&2
fi
printf '%s\n' "$FP" > "$CERT_PIN_FILE"
echo "Wrote $CERT_PIN_FILE (commit it)."
echo "F-Droid metadata line:  AllowedAPKSigningKeys: $FP"
