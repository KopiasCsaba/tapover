# Releasing

Maintainer notes for publishing a new TapOver version. Everything here runs from the repo root.

## One-time setup

1. Copy `.env.example` to `.env` and set `KDBX` to the KeePassXC database that holds the release key.
2. Signing key:
   1. New key: `make keygen DNAME="CN=Your Name"` creates the key in KeePassXC and writes
      `tools/release/signing-cert.sha256`.
   2. Existing key: `make fingerprint` writes `tools/release/signing-cert.sha256` from it.
3. Commit `tools/release/signing-cert.sha256` and put the same value in `AllowedAPKSigningKeys`
   in `metadata/net.kopias.tapover.yml`. `make sign` refuses any APK whose signer doesn't match it.
4. Back up the KeePassXC database. Losing the key means existing installs can never be updated.

## Releasing a new version

Example: 1.0.0 (versionCode 2) → 1.1.0 (versionCode 3).

1. Start from a clean tree on the branch you release from.
2. `app/build.gradle`: set `versionName '1.1.0'` and increase `versionCode` by 1. The versionCode
   must always grow, or Android won't install the APK as an update.
3. `CHANGELOG.md`: add a `## [1.1.0] - YYYY-MM-DD` section above the previous one (or replace
   `Unreleased` with the date if the section already exists, as for 1.0.0).
4. `fastlane/metadata/android/en-US/changelogs/3.txt` (named after the new versionCode): a short
   "what's new" text for F-Droid, max 500 characters.
5. `metadata/net.kopias.tapover.yml`: append an entry to `Builds:` (versionName, versionCode,
   `commit: v1.1.0`, `subdir: app`, `gradle: [yes]`) and update `CurrentVersion` /
   `CurrentVersionCode`. Once the app is in fdroiddata, its bot does this automatically from new tags
   (`UpdateCheckMode: Tags`); this copy is the reference.
6. Commit: `git commit -am "release: 1.1.0"` (add the new changelog file first).
7. Tag that commit: `git tag -s v1.1.0 -m "TapOver 1.1.0"` (`-a` instead of `-s` without a GPG key).
   The tag must be exactly `v<versionName>`.
8. `make sign`. It builds the release APK and signs it into `dist/tapover-1.1.0.apk`. It refuses to
   run if the tree is dirty, HEAD isn't at tag `v<versionName>`, or the signer certificate doesn't
   match the pinned one. `ALLOW_DIRTY=1 make sign` is for local tests only: it writes a `-dirty` APK
   that must never be published.
9. Push: `git push && git push origin v1.1.0`.
10. On GitHub, create a release from tag `v1.1.0`, paste the changelog section, and upload
    `dist/tapover-1.1.0.apk` under exactly that name. F-Droid's `Binaries:` line downloads it from
    there and only publishes it if its own build of the same tag is identical.

## When dependencies or the Android Gradle Plugin change

1. Run `make verification-metadata` and review the diff of `gradle/verification-metadata.xml`.
2. To move to a newer build image: pull `ghcr.io/cirruslabs/android-sdk:<tag>`, take its digest
   (`docker buildx imagetools inspect ghcr.io/cirruslabs/android-sdk:<tag>`), and update `IMAGE` in
   both `Makefile` and `tools/release/common.sh`.
