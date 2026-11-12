# TapOver — Docker-based Android build
#
# No local JDK/Android SDK required: builds run inside a pinned cirruslabs android-sdk
# image, with Gradle caches kept in a named volume for fast rebuilds.
#
# Shared/remote daemon support (see CLAUDE.md):
#   DOCKER_HOST  inherited automatically by the docker CLI when set (local socket otherwise)
#   REPO_ROOT    bind-mount source as the DAEMON sees it; defaults to this directory
#
# Run `make` with no target to see this help.

# Pinned by digest (multi-arch index the :35 tag pointed to on 2026-11-12). To bump deliberately:
#   docker pull ghcr.io/cirruslabs/android-sdk:<tag>; docker buildx imagetools inspect <image>:<tag>
# then replace the digest here AND in tools/release/common.sh, and rebuild + re-verify.
IMAGE           ?= ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9
GRADLE_VOLUME   ?= tapover-gradle
ANDROID_DIR     ?= .android-debug
ANDROID_SDK_DIR ?= /opt/android-sdk-linux
REPO_ROOT       ?= $(CURDIR)
# Debug builds: versionCode = git commit count (ever-increasing); empty → static defaultConfig values.
DEBUG_VERSION_CODE ?= $(shell git -C "$(CURDIR)" rev-list --count HEAD 2>/dev/null)

DEBUG_APK   := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-unsigned.apk
TEST_INSTALLER_APK := tools/testinstaller/build/outputs/apk/debug/testinstaller-debug.apk

# The repo's local.properties points sdk.dir at a host path that doesn't exist in the
# container, and Gradle prefers it over $ANDROID_HOME. Shadow it with a container-correct
# file mounted on top — the real working-tree file is never modified.
SDK_OVERRIDE := tmp/local.properties.docker

# Persist /root/.android (the debug keystore) across builds. Without it, every ephemeral
# container regenerates a random debug.keystore, so each APK is signed with a different key
# and `adb install -r` fails with "app not installed" (forcing uninstall + loss of settings).
# It lives in a git-ignored project folder ($(ANDROID_DIR)/), not a Docker volume, so a
# `docker volume prune` can't silently replace the key. Back that folder up.
DOCKER_RUN = docker run --rm \
	-v "$(REPO_ROOT)":/work \
	-v "$(REPO_ROOT)/$(SDK_OVERRIDE)":/work/local.properties:ro \
	-v $(GRADLE_VOLUME):/root/.gradle \
	-v "$(REPO_ROOT)/$(ANDROID_DIR)":/root/.android \
	-e GRADLE_USER_HOME=/root/.gradle \
	-w /work $(IMAGE)

# Run a Gradle command in the container, then hand every file it may have written back to
# the owner of the project folder (as seen inside the container). With a rootful daemon the
# build runs as real root, and root-owned files in app/build etc. would otherwise need sudo
# to delete; with a rootless daemon the owner is the container's root, so the chown is a no-op.
GRADLE = $(DOCKER_RUN) sh -c './gradlew "$$@"; rc=$$?; \
	chown -R "$$(stat -c %u:%g /work)" /work/app/build /work/build /work/.gradle /root/.android 2>/dev/null; \
	exit $$rc' gradlew

.DEFAULT_GOAL := help
.PHONY: help debug release sign keygen fingerprint verification-metadata clean shell apk-path test-installer

help: ## Show this help
	@echo "TapOver — Docker Android build"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Override vars: IMAGE, GRADLE_VOLUME, ANDROID_DIR, DEBUG_VERSION_CODE, ANDROID_SDK_DIR, REPO_ROOT"
	@echo "Signing vars:  KDBX (set in .env, see .env.example), KDBX_ENTRY, KDBX_ATTACHMENT, KEY_ALIAS, DNAME, ALLOW_DIRTY, SECRET_TMP (RAM disk; default /dev/shm)"

$(ANDROID_DIR):
	@mkdir -p $(ANDROID_DIR)

$(SDK_OVERRIDE):
	@mkdir -p tmp
	@echo "sdk.dir=$(ANDROID_SDK_DIR)" > $(SDK_OVERRIDE)

debug: $(SDK_OVERRIDE) | $(ANDROID_DIR) ## Build the debug APK (debug-signed; versionCode = git commit count)
	$(GRADLE) assembleDebug --no-daemon $(if $(DEBUG_VERSION_CODE),-PdebugVersionCode=$(DEBUG_VERSION_CODE))
	@echo ""
	@echo "==> Debug APK: $(REPO_ROOT)/$(DEBUG_APK)"

release: $(SDK_OVERRIDE) | $(ANDROID_DIR) ## Build the unsigned release APK
	$(GRADLE) assembleRelease --no-daemon
	@echo ""
	@echo "==> Release APK (unsigned): $(REPO_ROOT)/$(RELEASE_APK)"

sign: release ## Sign the release APK with the KeePassXC key (needs clean tree at tag v<version>; ALLOW_DIRTY=1 for a test signing)
	IMAGE="$(IMAGE)" REPO_ROOT="$(REPO_ROOT)" tools/release/sign.sh

keygen: ## One-time: create the signing key inside the KeePassXC entry (DNAME="CN=Your Name")
	IMAGE="$(IMAGE)" tools/release/keygen.sh "$(DNAME)"

verification-metadata: $(SDK_OVERRIDE) | $(ANDROID_DIR) ## Regenerate gradle/verification-metadata.xml (sha256) after changing dependencies; review the diff!
	$(GRADLE) --write-verification-metadata sha256 assembleDebug assembleRelease --no-daemon
	$(GRADLE) -PwithTestInstaller --write-verification-metadata sha256 :testinstaller:assembleDebug --no-daemon

fingerprint: ## Read the existing signing key from KeePassXC and (re)write tools/release/signing-cert.sha256
	IMAGE="$(IMAGE)" tools/release/fingerprint.sh

clean: $(SDK_OVERRIDE) | $(ANDROID_DIR) ## Gradle clean (remove build outputs)
	$(GRADLE) clean --no-daemon

test-installer: $(SDK_OVERRIDE) | $(ANDROID_DIR) ## Build the dev-only test installer APK (installs an APK with a chosen package source)
	$(GRADLE) -PwithTestInstaller :testinstaller:assembleDebug --no-daemon
	@echo ""
	@echo "==> Test installer APK: $(REPO_ROOT)/$(TEST_INSTALLER_APK)"

apk-path: ## Print the debug APK path (no build)
	@echo "$(REPO_ROOT)/$(DEBUG_APK)"

shell: $(SDK_OVERRIDE) | $(ANDROID_DIR) ## Open an interactive shell in the build container
	docker run --rm -it \
		-v "$(REPO_ROOT)":/work \
		-v "$(REPO_ROOT)/$(SDK_OVERRIDE)":/work/local.properties:ro \
		-v $(GRADLE_VOLUME):/root/.gradle \
		-v "$(REPO_ROOT)/$(ANDROID_DIR)":/root/.android \
		-e GRADLE_USER_HOME=/root/.gradle \
		-w /work $(IMAGE) bash
