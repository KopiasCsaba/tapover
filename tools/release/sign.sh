#!/usr/bin/env bash
# Sign the release APK with the key stored in KeePassXC. The key never lands in the repo:
# it is exported to a private RAM-backed temp dir, streamed into the container's tmpfs, then deleted.
# Refuses to run on a dirty tree or off the v<VERSION> tag, and checks the signer certificate
# against tools/release/signing-cert.sha256. ALLOW_DIRTY=1 bypasses the git checks for a local
# test signing; the output is then named dist/tapover-<version>-dirty.apk.
set -euo pipefail
cd "$(dirname "$0")/../.."
IN=app/build/outputs/apk/release/app-release-unsigned.apk
VERSION=$(sed -nE "s/^[[:space:]]*versionName '([^']+)'.*/\1/p" app/build.gradle)
[ -n "$VERSION" ] || { echo "error: could not read versionName from app/build.gradle" >&2; exit 1; }
OUT=dist/tapover-$VERSION.apk   # release asset name; must match Binaries: in metadata/net.kopias.tapover.yml

dirty=$(git status --porcelain)
tag_commit=$(git rev-parse -q --verify "refs/tags/v$VERSION^{commit}" || true)
problems=
[ -z "$dirty" ] || problems+=$'\n  - working tree is not clean (git status --porcelain is non-empty)'
[ -n "$tag_commit" ] || problems+=$'\n  - tag v'"$VERSION"$' does not exist'
if [ -n "$tag_commit" ] && [ "$tag_commit" != "$(git rev-parse HEAD)" ]; then
    problems+=$'\n  - HEAD is not at tag v'"$VERSION"
fi
if [ -n "$problems" ]; then
    if [ "${ALLOW_DIRTY:-}" = 1 ]; then
        OUT=dist/tapover-$VERSION-dirty.apk
        echo "!!!!!!!! WARNING: ALLOW_DIRTY=1, signing an unreleasable build; output: $OUT$problems" >&2
        echo "!!!!!!!! DO NOT PUBLISH THIS APK" >&2
    else
        echo "error: refusing to sign a release:$problems" >&2
        echo "Commit and tag (git tag -s v$VERSION), or set ALLOW_DIRTY=1 for a local test signing." >&2
        exit 1
    fi
fi

[ -f "$IN" ] || { echo "error: $IN missing; run 'make release' first" >&2; exit 1; }
. tools/release/common.sh

[ -s "$CERT_PIN_FILE" ] || die "$CERT_PIN_FILE is missing or empty; run 'make fingerprint' (existing key) and commit the file"
PINNED=$(tr -d ' \r\n' < "$CERT_PIN_FILE" | tr 'A-F' 'a-f')
[[ "$PINNED" =~ ^[0-9a-f]{64}$ ]] || die "$CERT_PIN_FILE must hold a 64-char hex SHA-256 (run 'make fingerprint')"

mkdir -p dist
kp attachment-export "$KDBX" "$KDBX_ENTRY" "$KDBX_ATTACHMENT" "$SECRET_DIR/ks.p12" >/dev/null
# Keystore password on stdin ahead of the keystore (not env/argv: `docker inspect` would show it).
# The container prints the signature check's cert info on stdout; its exit code (set -e) counts.
CERTS=$({ printf '%s\n' "$KS_PASS"; cat "$SECRET_DIR/ks.p12"; } | docker run --rm -i -u "$(id -u):$(id -g)" \
    -e KEY_ALIAS="$KEY_ALIAS" --tmpfs /keys -v "$REPO_ROOT":/work -w /work "$IMAGE" sh -ec '
        IFS= read -r KS_PASS; export KS_PASS
        cat > /keys/ks.p12
        [ -s /keys/ks.p12 ] || { echo "error: empty keystore from KeePassXC" >&2; exit 1; }
        '"$BUILD_TOOLS"'/zipalign -f -p 4 "$1" /keys/aligned.apk >&2
        '"$BUILD_TOOLS"'/apksigner sign --alignment-preserved true --ks /keys/ks.p12 --ks-type PKCS12 --ks-key-alias "$KEY_ALIAS" \
            --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "$2" /keys/aligned.apk >&2
        '"$BUILD_TOOLS"'/apksigner verify --print-certs "$2"' sh "$IN" "$OUT") \
    || { rm -f "$OUT" "$OUT.idsig"; die "signing or signature verification failed"; }
rm -f "$SECRET_DIR/ks.p12" "$OUT.idsig"

[ "$(grep -c '^Signer #' <<<"$CERTS")" -eq 1 ] || { rm -f "$OUT"; die "expected exactly one signer in $OUT"; }
GOT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$CERTS" | tr 'A-F' 'a-f')
if [ "$GOT" != "$PINNED" ]; then
    rm -f "$OUT"
    die "signer certificate $GOT does not match the pinned $PINNED ($CERT_PIN_FILE); wrong key? (run 'make fingerprint' only if the key was replaced on purpose)"
fi
grep -E 'DN|SHA-256' <<<"$CERTS"

echo ""
echo "==> Signed APK: $OUT"
(cd dist && sha256sum "$(basename "$OUT")")
