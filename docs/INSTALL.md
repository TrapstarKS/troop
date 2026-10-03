# Install troop

Checked 2026-10-02. troop is a fork of [NOOP](https://github.com/ryanbr/noop). It stores strap data on-device, without a troop account or telemetry. Installation tools contact Apple, GitHub, or their own signing services; those services are separate from troop. A real phone and strap are required for Bluetooth.

**Distribution is being prepared locally. No new stable release or source has been published by this change.** The fork's source starts with no versions rather than advertising upstream downloads. Use the source route after the maintainer publishes its first validated stable release; until then, build from source. Old fork prereleases are staging builds, not the private-key stable channel described below.

## iPhone: SideStore (primary)

Requires iOS 17+, a free Apple Account, a computer for initial USB setup, and Wi-Fi. No paid Apple Developer membership is required. SideStore's current setup uses iloader and LocalDevVPN; consult its [prerequisites](https://docs.sidestore.io/docs/installation/prerequisites) for OS-specific downloads.

1. Install iloader on your computer and LocalDevVPN on your iPhone from the links in SideStore's prerequisites. Enable the local VPN when installing, upgrading, or refreshing through SideStore. It serves SideStore's local provisioning; troop does not use it to upload health data.
2. Connect the unlocked phone by USB, trust the computer, open iloader, sign in with your Apple Account, select the phone, and choose **Install SideStore (Stable)**.
3. On the phone, trust the developer under Settings → General → VPN & Device Management. Enable Developer Mode under Privacy & Security and complete the restart/confirmation when required. Wording differs by iOS version.
4. Connect to Wi-Fi, enable LocalDevVPN, open SideStore, and sign in with the same Apple Account. In **My Apps**, manually refresh **SideStore itself** immediately to finish setup. These steps follow the [SideStore installation guide](https://docs.sidestore.io/docs/installation/install).
5. After a stable fork release is available, open SideStore's Browse/Sources screen, choose Add Source, and paste the raw JSON URL:

   `https://raw.githubusercontent.com/TrapstarKS/troop/main/altstore-source.json`

6. Install **troop (NOOP)** from that source. Alternatively download `NOOP-ios-unsigned-v<VERSION>.ipa` and `SHA256SUMS` from [this fork's releases](https://github.com/TrapstarKS/troop/releases), verify the checksum below, transfer the IPA to Files, and select it with SideStore's **+** button.
7. Keep the widget extension when asked. The IPA includes an ad-hoc **capability template**, not an Apple developer distribution signature; SideStore replaces it with your provisioning. HealthKit and the shared App Group must be provisioned for health access, widgets, and Live Activities. The download excludes the Watch app. Grant only the permissions you want in troop.

Free-account provisioning normally expires after seven days. Refresh both SideStore and troop before their displayed expiration dates. Background refresh is best effort; check **My Apps** manually. Refresh renews signing for the installed version; installing a newer release is a separate update action. Wi-Fi and LocalDevVPN are needed for these operations. Extensions also consume App IDs, and free-account app/ID limits may prevent installation; consult [SideStore's FAQ](https://docs.sidestore.io/docs/faq).

If signing expires, re-provision the same app with the same account/identity; avoid deleting it. If pairing breaks after an iOS update/reset, use SideStore's [troubleshooting guide](https://docs.sidestore.io/docs/troubleshooting). Export `.noopbak` while troop still opens. Do not assume that removing/reinstalling an app, changing accounts or bundle IDs, or switching sideloaders preserves its data container. Physical-device installation, refresh, HealthKit, widgets, and BLE still need validation for this fork.

## iPhone alternatives

- **Xcode free Personal Team:** on a compatible Mac, install Xcode 26 and XcodeGen, clone this fork, run `xcodegen generate`, open `Strand.xcodeproj`, select **NOOPiOS**, and choose your connected/trusted iPhone. Copy `Config/BundleIdSecrets.example.xcconfig` to the gitignored `Config/BundleIdSecrets.xcconfig`, set your `DEVELOPMENT_TEAM` and a unique prefix you control, regenerate, then choose your Personal Team for the app and embedded targets. Enable Developer Mode and Run. Free provisioning expires after seven days; rebuild/reinstall with the same identity. HealthKit/App Group eligibility depends on your provisioning; inspect signing errors rather than promising all capabilities on a free team. See [Apple account limits](https://developer.apple.com/help/account/basics/about-your-developer-account) and [iOS build details](IOS.md#build-from-source).
- **AltStore Classic:** follow [AltServer setup](https://faq.altstore.io/altstore-classic/altserver), install Classic with your account, add the same raw source, or import the IPA. Traditional refresh uses a running AltServer over USB/same Wi-Fi; [Remote AltServers](https://faq.altstore.io/altstore-classic/remote-altservers) are another tooling option. Check expiry manually; refresh and upgrading are separate. AltStore PAL is a different notarized distribution channel and cannot install this ordinary IPA as a PAL app.
- **Sideloadly:** install from [the official site](https://sideloadly.io/), trust/connect the phone, select it and your Apple Account, load the verified IPA, and start provisioning. Preserve extensions/capabilities and the bundle identity for updates. Its refresh daemon needs the computer/device reachable; a new version requires its new IPA. See the [official FAQ](https://sideloadly.io/faq). Extension/HealthKit compatibility requires device testing.

## Android: signed Full APK

Requires Android 8+. Download **`NOOP-android-v<VERSION>.apk`** and **`SHA256SUMS`** from [stable fork releases](https://github.com/TrapstarKS/troop/releases). Select a fixed `vX.Y.Z` release, not `testing-latest`, Demo, or a debug APK. Verify the checksum, allow Install unknown apps for your downloader/file manager, open the APK, and follow the package installer. Managed-device/OEM policy can restrict sideloading. No troop or Play account is needed by the app.

The stable package is **`com.trapstarks.troop`**, signed with one durable private maintainer key. Each release includes `NOOP-android-v<VERSION>-metadata.json` with the public signing certificate SHA-256, versionCode, package, and APK digest. Verify against the established certificate fingerprint before updates; obtaining the APK and its checksum from the same compromised source does not establish publisher authenticity. A private signature establishes update continuity, not reproducible-build equivalence. Android signing/updates have no seven-day refresh. Keep the same package/signer and increase versionCode; export `.noopbak` before upgrading.

**Migrating from upstream or the old public-key staging app:** first export a `.noopbak` from the old app and copy it somewhere accessible outside that app. Install the stable fork beside it, import the backup using troop's existing backup/restore flow, relaunch as directed, and verify your history/settings before removing the old app. The previous `com.noop.whoop.staging` public-key build cannot update in place to this new identity/signer. New staging builds use `com.trapstarks.troop.staging`; debug uses `.debug`. Neither is the stable channel. A backup is the supported migration boundary, not a guarantee that OS permissions or pairing transfer.

### Optional Obtainium updates

Install [Obtainium](https://github.com/ImranR98/Obtainium), add `https://github.com/TrapstarKS/troop`, turn **prereleases off**, and use this APK filter:

`^NOOP-android-v[0-9]+\.[0-9]+\.[0-9]+\.apk$`

Confirm that exactly one stable Full APK is selected. The minimal [distribution/obtainium.json](../distribution/obtainium.json) uses Obtainium's single-app import/deep-link fields; see [import links](https://wiki.obtainium.imranr.dev/deep_links/). The quick source-add link is `obtainium://add/https://github.com/TrapstarKS/troop`; apply the settings above manually. Background checks depend on Android scheduling and GitHub rate limits, and installation can require your approval. Obtainium imports/updates have not been exercised on a physical device here.

### Android platform policy

As of this guide's date, Google's [developer verification rollout](https://developer.android.com/developer-verification) initially names participating stores in selected regions and describes wider future enforcement. Device/region policies can change: check current guidance before distribution. This fork has not enrolled a maintainer identity with Google or added a Play channel. Do not infer that all direct APK installs are currently blocked or permanently exempt; [Android's alternative-distribution guidance](https://developer.android.com/distribute/marketing-tools/alternative-distribution) remains the reference.

## Check downloads

Download files and `SHA256SUMS` from the **same fixed release**. On macOS use `shasum -a 256 <filename>`; on Linux use `sha256sum <filename>`; on Windows PowerShell use `Get-FileHash <filename> -Algorithm SHA256`. Compare the entire digest with that filename's line in `SHA256SUMS`. Stop if it differs. Re-signing the iOS app changes its bytes; verify the downloaded IPA before handing it to a sideloader.

## Maintainer: one-time setup and release boundary

1. Confirm the chosen stable identities before the first publication: iOS **`com.trapstarks.troop.noop`**, widget **`.widgets`**, source **`com.trapstarks.troop.altstore`**; Android **`com.trapstarks.troop`**. Local Xcode prefix overrides change identity/data containers. Fork users must not be offered upstream releases; the existing update checker now points at this fork. Theme/product names remain NOOP in the app until coordinated branding changes.
2. Create a durable private Android release key **outside the repository**, keep an offline encrypted backup of the key/passwords, and record its public SHA-256 certificate fingerprint. Never use `android/fork-debug.keystore` for stable distribution. No key is generated by the workflow. The legacy Tools/release.sh publisher is disabled in this fork so it cannot bypass these checks. Configure repository secrets `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`, and `ANDROID_RELEASE_CERT_SHA256` (hex, colon separators allowed). Restrict secret access to trusted release runs. The workflow writes gitignored `android/keystore.properties` and temporary key material, then removes them even on failure. A local stable build uses the same four properties in that file; see [Android build instructions](../android/README.md).
3. Allow the authorized release workflow to write releases and bump/source commits; satisfy branch protection deliberately. Dispatch only from trusted source containing the fork manifest/identity changes and maintained `docs/releases/vX.Y.Z.md`. This implementation never dispatches it. No Apple maintainer certificate/identity is needed for user-re-signed IPA distribution.
4. The edited workflow creates a **draft**, builds all three platforms, checks APK identity/version/signer (rejects the public staging certificate and requires continuity with the previous stable APK’s verified signer), checks the device IPA, compares downloaded draft assets to the producers’ hashes, publishes checksums, and only then publishes stable/latest. Remote tag collisions are rejected before version changes, and tag/source identity is checked again before publication. Assets are immutable: no stable `--clobber`. A failed run leaves a draft/incomplete delivery; use a new version after resolving it, or deliberately recover the draft outside this automated path. Feed update is a required post-publication job: it fetches the anonymous public URL, checks the digest, generates metadata from those bytes, and fails if its commit/push fails. Publication and Git commits are not atomic; a source failure requires maintainer repair and is never reported as successful delivery.
5. Before recommending installation, validate physical iPhone initial install, refresh/expiry recovery, upgrade preserving data, permissions/widgets/Live Activity and BLE. Validate Android fresh install, stable same-signer upgrade preserving data, wrong-signer rejection, `.noopbak` migration, and Obtainium filtering. Record OS/tool versions and results. Local builds do not prove these behaviors.

Local tooling examples (no publication):

```sh
Tools/package-ios-ipa.sh 'build/Build/Products/Release-iphoneos/NOOP Staging.app' \
  NOOP-ios-unsigned-v11.8.0.ipa com.trapstarks.troop.noop 11.8.0
Tools/update-altstore-source.sh --repo TrapstarKS/troop --source /tmp/altstore-source.json \
  --asset-url https://github.com/TrapstarKS/troop/releases/download/v11.8.0/NOOP-ios-unsigned-v11.8.0.ipa \
  --version 11.8.0 --ipa NOOP-ios-unsigned-v11.8.0.ipa
python3 Tools/verify-release-artifacts.py checksums NOOP-ios-unsigned-v11.8.0.ipa
```

Copy the tracked empty manifest to `/tmp/altstore-source.json` before the example. Packaging copies the app, removes Watch/personal profiles/debug artifacts, scrubs builder home paths while unsigned, then applies the existing replaceable app/widget capability templates and verifies device metadata. It refuses to overwrite an output IPA. The generator requires explicit repo/source/asset arguments, rejects version/identity mismatches and version/build regression, and never downloads or publishes anything. Python 3.11+ is required for the scripts.
