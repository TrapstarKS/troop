# Publishing troop v12.0.0

For v12.0.0, publication was authorized without a private Android signing key. The delivery uses
locally validated Apple artifacts and a separately named public-testing-key Android APK, with
`SHA256SUMS`. It uses `gh release create` after validating and pushing merged main; it does not run
the complete stable workflow or create signing keys/secrets. The stable APK is omitted. See the
[release notes](releases/v12.0.0.md) for the actual downloads. The original complete stable-channel
plan below remains a reference for a future version with private Android signing configured.

Phase 1 prepares source and notes only. Phase 2 starts after the coordinator merges and pushes
all ports to `TrapstarKS/troop` main and authorizes publication. No release, remote tag, workflow
dispatch or main merge is part of preparation.

## Source and artifacts

The fork uses `.github/workflows/fork-release.yml` as its stable publisher. `Tools/release.sh`
deliberately exits without publishing. `Tools/forgejo-release.sh` targets a different host and
must not be used for this GitHub release. The testing workflow recreates `testing-latest` with
public-key APKs; it is not the stable channel.

The merged source must contain version **12.0.0** on both platforms, Android versionCode **551**,
iOS-family build **432**, macOS build **199**, and matching in-app What's New entries generated
from [the release notes](releases/v12.0.0.md). These are increments from the fork's counters;
the three upstream testing-build commits are intentionally omitted. The upstream AltStore commit
`9f98f811a` is also omitted: it advertises upstream download URLs. The fork's initially empty source
is populated by its publisher only after the actual fork IPA is available and verified.

Phase-1 checks passed: 35 release-tool/changelog tests, Swift changelog typecheck, XcodeGen project
generation, workflow YAML/embedded shell syntax, source hygiene, matching generated release copy
and Android task-graph dry run. No app artifacts were compiled. The full i18n audit has one existing
failure: `Strand/Resources/Localizable.xcstrings:zh-Hant` has 81 missing translations against a 79
allowance. The catalogue and allowance are unchanged from `b21cc8103`, where the same counts occur;
all new-copy and focus-locale checks pass. Resolve this existing gate on merged main before release.

| Published asset | Identity and signing |
|---|---|
| `NOOP-android-v12.0.0.apk` | Full Release; `com.trapstarks.troop`; durable private Android key |
| `NOOP-android-v12.0.0-metadata.json` | Package, versionCode, signer fingerprint and APK digest |
| `NOOP-macos-v12.0.0.zip` | Universal Intel/Apple Silicon Release; `NOOP Staging.app`; ad-hoc signed, not notarized |
| `NOOP-ios-unsigned-v12.0.0.ipa` | Device Release; `NOOP.app`; fork bundle `com.trapstarks.troop.noop`; user re-signing required |
| `SHA256SUMS` | Digests of the four assets above |

The IPA keeps the widget extension/capability templates and strips the Watch app. No maintainer
Apple certificate, provisioning profile or paid team is required for these sideload downloads.
Actual HealthKit/widgets/Live Activities eligibility depends on the user's provisioning.
Stable Android cannot be unsigned or signed with the tracked public testing key.

## Required setup

At preparation time GitHub authentication has administrative access to this fork, but the repository
has no releases, origin tags or signing secrets. No local `android/keystore.properties` was found
in this worktree or the main checkout. The maintainer must supply or authorize creation of a durable
private Android key, retain an encrypted offline backup and configure these five secrets:

```sh
gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --repo TrapstarKS/troop
gh secret set ANDROID_RELEASE_STORE_PASSWORD --repo TrapstarKS/troop
gh secret set ANDROID_RELEASE_KEY_ALIAS --repo TrapstarKS/troop
gh secret set ANDROID_RELEASE_KEY_PASSWORD --repo TrapstarKS/troop
gh secret set ANDROID_RELEASE_CERT_SHA256 --repo TrapstarKS/troop
```

These commands prompt for values; never put passwords or private key contents in tracked files or
command arguments. The certificate digest accepts hex with optional colons. Actions is enabled
for this fork with a read-only default; the release jobs explicitly request `contents: write`.
Branch protection must permit the post-publication feed commit to main. Missing signing secrets stop
the workflow before it changes source or creates a draft.

## Local preflight and optional artifact builds

The installed Xcode is 26.6 with the iOS 26.5 SDK. The active system developer directory points at
Command Line Tools, so select Xcode per command using `DEVELOPER_DIR`; no global switch is needed.
JDK 17 and Android SDK platform/build-tools 35 are installed in these locations (the actual fork
uses compileSdk 35, despite older upstream documentation mentioning 34):

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
xcodegen generate

# Task-graph dry run only, using the explicit testing identity without a private signer.
(cd android && ./gradlew assembleFullRelease -PstagingRelease --dry-run --no-build-cache --rerun-tasks)
```

The following are phase-2 builds, not preparation commands. Run from a clean merged-main checkout
without personal `Config/BundleIdSecrets.xcconfig` prefix overrides. A stable local Android build
requires the same private key via gitignored `android/keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`), as described in [INSTALL.md](INSTALL.md).

```sh
(cd android && ./gradlew assembleFullRelease --no-build-cache --rerun-tasks)
mkdir -p build/release
cp android/app/build/outputs/apk/full/release/*.apk build/release/NOOP-android-v12.0.0.apk

xcodebuild -project Strand.xcodeproj -scheme Strand -configuration Release \
  -destination 'generic/platform=macOS' ARCHS='x86_64 arm64' ONLY_ACTIVE_ARCH=NO \
  -derivedDataPath build/release-macos \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build
TROOP_MAC_APP='build/release-macos/Build/Products/Release/NOOP Staging.app'
lipo -archs "$TROOP_MAC_APP/Contents/MacOS/NOOP Staging"
Tools/anonymize-macos-app.sh "$TROOP_MAC_APP"
codesign --force --deep --sign - "$TROOP_MAC_APP"
ditto -c -k --keepParent "$TROOP_MAC_APP" build/release/NOOP-macos-v12.0.0.zip

xcodebuild -project Strand.xcodeproj -scheme NOOPiOS -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/release-ios \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build
Tools/package-ios-ipa.sh build/release-ios/Build/Products/Release-iphoneos/NOOP.app \
  build/release/NOOP-ios-unsigned-v12.0.0.ipa com.trapstarks.troop.noop 12.0.0
```

Local artifacts can validate the merged source before dispatch; the stable workflow builds its own
artifacts from that same source SHA and performs the Android/IPA checks, macOS architecture check,
producer/download digest comparison and checksum publication. No duplicate local publisher is needed.

## Exact publication commands — phase 2 only

After the coordinator pushes merged main, start in a clean checkout of it. Do not pre-create or push
`v12.0.0`: existing tags/releases are rejected. The counters and What's New are already prepared,
so request `bump=none` explicitly; otherwise the default patch bump would request 12.0.1.

```sh
git fetch origin main
test "$(git branch --show-current)" = main
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"
gh secret list --repo TrapstarKS/troop
Tools/require-release-absent.sh TrapstarKS/troop v12.0.0
git ls-remote --tags origin refs/tags/v12.0.0 'refs/tags/v12.0.0^{}'

gh workflow run fork-release.yml --repo TrapstarKS/troop --ref main \
  -f bump=none -f version=12.0.0
gh run list --repo TrapstarKS/troop --workflow fork-release.yml --branch main \
  --event workflow_dispatch --limit 5 \
  --json databaseId,createdAt,headSha,status,conclusion,url
```

Set `TROOP_RELEASE_RUN_ID` to the new run's databaseId after checking its headSha against the intended
merged commit. Dispatch authorizes the entire publication: the workflow creates a draft, publishes
stable/latest only when every platform succeeds, then validates the public IPA and commits the fork
SideStore / AltStore Classic source to main. The tag points to the build SHA, before that feed commit.

```sh
gh run watch "$TROOP_RELEASE_RUN_ID" --repo TrapstarKS/troop --exit-status
gh api repos/TrapstarKS/troop/releases/tags/v12.0.0 \
  --jq '{tag_name,draft,prerelease,target_commitish,assets:[.assets[].name]}'
gh api repos/TrapstarKS/troop/releases/latest --jq .tag_name
TROOP_RELEASE_DIR=$(mktemp -d /tmp/troop-v12.0.0.XXXXXX)
gh release download v12.0.0 --repo TrapstarKS/troop --dir "$TROOP_RELEASE_DIR"
(cd "$TROOP_RELEASE_DIR" && shasum -a 256 -c SHA256SUMS)
python3 Tools/verify-release-artifacts.py ipa \
  "$TROOP_RELEASE_DIR/NOOP-ios-unsigned-v12.0.0.ipa" \
  --bundle-id com.trapstarks.troop.noop --version 12.0.0
git fetch origin main
git show origin/main:altstore-source.json
```

Require a successful run including the feed job, a non-draft/non-prerelease v12.0.0 release with
exactly the five assets, matching tag/build SHA and hashes, and a fork feed entry pointing at the
validated fork download. A failed run may leave a draft; do not overwrite stable assets or blindly
redispatch the colliding version. Resolve/recover the draft deliberately. Device tests remain
separate from build success: this fork has not yet established real-phone/strap install, upgrade,
alarm, HealthKit or BLE behavior.

After successful publication, update the README and installation guide's preparation-only wording
to point at the verified first release; keep the real-device validation limits explicit.
