# MA patch log

Each entry records the exact change, version/artifact, verification performed, outcome, and what that outcome does not prove. Add an entry after every source, build, or release patch; do not mark a phone result as verified unless a phone result was actually observed.

## 2026-11-19 — AutoUpdate release diagnosis and validation gate

### Version-by-version findings

| Version | APK identity | Update artifact result | Finding |
| --- | --- | --- | --- |
| 1.0.004 | `com.doumi.helper`, versionCode 1005, original signer SHA-256 `5f5951336912c1c8772c96fccc88799ba96beba2488fe3296d22c23469da8987` | Historical signed APK verified; matching release record says the user confirmed automatic update to 1.0.004 | Last phone-confirmed automatic-update version in the recovered records. |
| 1.0.005 | `com.doumi.helper`, versionCode 1006, original signer SHA-256 `5f5951336912c1c8772c96fccc88799ba96beba2488fe3296d22c23469da8987` | APK and archived latest.json verified; original signing identity retained | Last known-good release artifact before the break. The recovered 1.0.005 handoff explicitly marks phone installation/automatic install as not verified; do not count it as a phone-confirmed update. |
| 1.0.006 | `com.siny.apps.ma_3c44c3e1b8`, versionCode 5 | Manifest size and SHA-256 match; APK signature verification fails (`Missing META-INF/MANIFEST.MF`; no valid v1/v2/v3 signature). The old-format `latest.json` is absent. | **First known broken MA release.** It changed package identity and reset Android versionCode below 1006, and its APK was unsigned. The 1.0.005 updater cannot accept it as an in-place update. |
| 1.0.007 | `com.siny.apps.ma_3c44c3e1b8`, versionCode 6 | Manifest size/SHA match; APK is unsigned; old-format `latest.json` absent | Same break continued. |
| 1.0.008 | `com.siny.apps.ma_3c44c3e1b8`, versionCode 7 | Manifest size/SHA match; APK is unsigned | First archived public MA release; same break continued. |
| 1.0.015–1.0.019 | `com.siny.apps.ma_3c44c3e1b8`, versionCodes 14–18 | Every manifest size/SHA matches its APK; every APK fails signature verification | The displayed SHA/signature/package error was not caused by a bad SHA. The downloaded bytes matched the manifest, but Android could not find a valid APK signature. |
| 1.0.020 | `com.siny.apps.ma_3c44c3e1b8`, versionCode 19, signer SHA-256 `d2206ba8af559c091b82d7dd65e16f597629c361c623f93f0da10a5a43c84679` | Manifest size/SHA and package/version match; v1 and v2 signatures pass | First verified signed artifact in this release chain. It updates only an installation with this newer package and signer; it cannot upgrade the old `com.doumi.helper` installation. |

The root cause starts at **1.0.006**, immediately after the last known-good 1.0.005. Releases 1.0.006–1.0.007 are present in the old `ma-updates` channel; no 1.0.009–1.0.014 assets were found. The evidence identifies the first bad release, rather than claiming an unobserved source-code commit caused it. The 1.0.015–1.0.019 Git tags also all point to the same source commit while their APK binaries differ, so those tags do not provide reliable per-build source provenance.

### Patch record — v1.0.020 (`c06b99a133b37c1f04cf69e6311d4d2c8195eaa9`)

- `AutoUpdateModule`: retain exact installed-signer comparison and add the legacy Android signature metadata fallback when `signingInfo` is absent; do not relax hash, package, version, or certificate checks.
- Build signing: enable v1 and v2 signatures for the MA candidate APK.
- Release publisher: refuse to publish an APK unless the v1/v2 signatures and the pinned current-MA certificate validate; support publishing the already-built current version.
- Built and published GitHub release `v1.0.020` (display version `1.0.20`, Android versionCode 19). APK size: 1,668,427 bytes. SHA-256: `9864458c17f6156352ef8057357456e838984008f8dcc5bdae379f1e603f317e`.
- Existing result: `:app:testDebugUnitTest` and `assembleDebug` passed before publishing; release APK package/version, manifest pair, SHA-256, v1/v2 signatures, and signer were verified. The release tag points to the build source commit.
- Limit: this validates the new `com.siny.apps.ma_3c44c3e1b8` install line on the PC. It does not make that package/signing identity compatible with the historical `com.doumi.helper` 1.0.005 installation, nor is it a phone installation result.

### Validation-gate patch — 2026-11-19 (no app version change)

- Changed `tools/autoupdate/publish-github-release.py`: after downloading the published GitHub APK, `--verify-only` now checks its actual package name, version name/code, v1+v2 signatures, and pinned signer in addition to manifest size/SHA.
- Regression check: v1.0.020 passes; archived unsigned v1.0.019 is rejected by the signature gate.
- Verification performed: `python -m py_compile tools/autoupdate/publish-github-release.py` passed; `python build/diagnosis/test_release_gate.py` passed both assertions; `python tools/autoupdate/publish-github-release.py --verify-only` passed against the public v1.0.020 release and confirmed it remains latest.
- This patch changes release verification only; it does not create or publish another APK.

### Compatibility bridge patch — v1.0.21 candidate

**What changed:**

- Restored MA's last known-good Android package ID, `com.doumi.helper`, in the app metadata and packaged metadata. The 1.0.006 release changed this to `com.siny.apps.ma_3c44c3e1b8`, which made it a different Android app.
- Restored the original 1.0.005 signing certificate for the release build and raised Android `versionCode` from 1006 to 1007. The bridge's displayed version is 1.0.21.
- Changed the release publisher to build a signed release APK, pin the signing certificate by package identity, and record the signer digest in the release manifest.
- Added `tools/autoupdate/publish-legacy-bridge.py`: by default it only validates; with `--publish` it verifies assets, publishes the old-format bridge, confirms remote downloads, and updates the old numeric version pointer last.
- Updated the module-contract and local-feed checks for the restored package ID.
- Added PC-only compatibility checks against the actual 1.0.005 APK and the archived 1.0.005 `UpdatePolicy.java`, plus a generated old-channel `latest.json` using the exact historical six-field schema.

**Verification performed:**

- `:app:testDebugUnitTest` and `assembleRelease`: passed.
- APK identity comparison: phone-confirmed 1.0.004 (`com.doumi.helper`, code 1005) and the 1.0.005 release artifact (code 1006) → candidate 1.0.21 (same package, code 1007, original signer `5f5951…`): passed.
- Historical 1.0.005 `UpdatePolicy` Java checks: it accepts the bridge from both 1.0.004 and 1.0.005 when package, higher version code, tag, actual APK SHA/size, and signer match; a reset version code, mismatched tag, and changed signer were rejected.
- Sequential historical-artifact simulation: 1.0.002 → 1.0.003 → 1.0.004 → 1.0.005 → 1.0.21 passed package/code/tag/SHA/signer policy checks. Direct 1.0.21 acceptance from each archived 1.0.002–1.0.005 original-package artifact also passed. This is PC policy simulation; it does not claim those steps were installed one by one on the phone.
- Historical `latest.json` field schema: exact match.
- Final candidate APK: 1,241,274 bytes; SHA-256 `9560f109c75668e1a13081e8eafdea32b2a2a4604487c869464651d5abdec207`.

**Publication and outcome:**

- Public `siny-dev/MA` release `v1.0.21` published. The remote APK was downloaded again and its exact size/SHA, `com.doumi.helper` package, version `1.0.21`/code 1007, v1/v2 signature, and original signer were verified.
- Compatibility release `v1.0.21` published to the historical `siny-dev/ma-updates` channel with the old APK asset name and old six-field `latest.json`. Authenticated remote downloads exactly match the locally verified APK and manifest.
- Only after the private release assets passed those checks, the old channel's `main/version.txt` was advanced from 1006 to exactly `1007\n`; read-back verification passed.
- The 1.0.005 handoff confirms the old updater had previously received a configured read token, so no new credential was added or requested.
- Source and patch record commit: `afe03b9` (`Restore-MA-AutoUpdate-compatibility-bridge`), pushed to `siny-dev/MA` main before publishing.
- The follow-up sequential-version comparison and its result were added to this log and pushed as `b4ed67a` (`Record-MA-1.0.21-bridge-outcome`).

**Status:** The old 1.0.004/1.0.005 package identity now has a verified update candidate staged in its existing channel. The recovered records do not establish that the 1.0.005 app itself was installed on the phone; this log does not claim a new phone-side install result. A newer `com.siny.apps.ma_3c44c3e1b8` installation remains a distinct Android package and cannot be converted in place by this bridge.

### Signature metadata fallback correction — 2026-11-20

- Found a defect in the prior `signingInfo` fallback: when `PackageManager` returned a non-null `SigningInfo` object with an empty signer array, the code fetched legacy `PackageInfo.signatures` but the final comparison still selected the empty `SigningInfo` array. That caused a valid same-signer update to be rejected at the signature/package verification stage.
- Updated `AutoUpdateModule.verifyApk` to select the modern signer array only when it contains signer bytes, otherwise use the legacy signature metadata. The exact signer-set comparison remains mandatory; missing signer data still rejects the APK.
- Added regression coverage for empty/null modern signer arrays, valid modern signer precedence, and rejecting absent signer data.
- Verification: all **334** JUnit tests passed. The normal Gradle test worker initially reported `ClassNotFoundException` for all test classes when launched from the Korean-named project path. Re-running the same `:app:testDebugUnitTest` task in an ASCII-path Git worktree passed all 334 tests; updated test sources compiled successfully.
- Public `siny-dev/MA` release `v1.0.22` was created by the publisher's previously unhandled `--help` argument falling through to its default publish action. Remote verification passed for exact manifest size/SHA, package `com.doumi.helper`, version `1.0.22`/code `1008`, and pinned v1/v2 signer; the downloaded APK contains the signer-fallback fix. Its tag targets the pre-fix source commit `a3e0cfd`, so it is not the source-aligned release record. No phone installation or actual on-device patch is claimed.
- Source-aligned release `v1.0.23` was then built from committed source `dd7e67f` in an ASCII-path worktree. `:app:testDebugUnitTest` and `assembleRelease` passed. The public GitHub release is latest and remote verification passed for package `com.doumi.helper`, version `1.0.23`/code `1009`, APK size `1,241,425`, SHA-256 `04ae30dd8b73fe6c2445f265de96314a4e3e7235c3388653c3f51d4a603b63b3`, and the pinned v1/v2 signer. The release tag resolves to the source commit containing this fix.
- The approved ARM64 SDK image was installed after its license was accepted. This x86_64 Windows host rejects ARM64 AVDs, and `emulator -accel-check` reports no Android Emulator Hypervisor Driver; therefore no Android runtime patch test could be performed. No physical phone was requested or used.
- The publisher treated an unrecognized `--help` argument as its normal publish action and created the verified v1.0.22 release. The CLI guard added in this patch now returns help before any release work and rejects unknown options, preventing that side effect on future help queries.

### Sequential runtime attempt — 2026-11-19

- The historical APK and updater-policy sequence was re-run for 1.0.002 → 1.0.003 → 1.0.004 → 1.0.005 → 1.0.21 and passed for each package/code/tag/SHA/signer transition; direct bridge acceptance from each archived original-package APK also passed.
- The `MA_A34_Sim` AVD exists, but this PC has no attached ADB device and no Android Emulator Hypervisor Driver. The x86_64 AVD could not boot with acceleration; CPU-only startup remained offline and crashed.
- An ARM system image would allow a different emulator path, but `sdkmanager` stopped at Google's SDK license prompt. The license was not accepted. No actual Android runtime install or patch was performed in this attempt.
- Result: artifact and policy sequence **PASS**; Android runtime sequence **NOT RUN** because this PC's emulator lacks acceleration. No phone action was requested or performed.

### Sequential emulator runtime attempt — 2026-11-20

- Installed Android Emulator Hypervisor Driver 2.2 locally. `emulator -accel-check` returned `AEHD (version 2.2) is installed and usable`; booted `MA_A34_Sim` (Android 14, x86_64).
- Installed historical MA 1.0.003 into the emulator only. Android reports package `com.doumi.helper`, versionCode `1004`, versionName `1.0.003`, and the original pinned signer. The app's home and Settings screens opened.
- Reproduced the legacy update setup path on a clean emulator: Update check opens the repository connection dialog. A tokenless connection attempt to public `siny-dev/MA` was rejected by that legacy app with “저장소 연결을 저장하지 못했어요. 입력 내용을 확인해 주세요.” The installed version remained 1.0.003 / code 1004. This is a failed bootstrap test; it does not verify an APK download or update install.
- Enabled MA's Android “Install unknown apps” permission in the emulator. No update was installed as a result. No phone was connected or changed.
- Finding to resolve: the historical app's first connection requires a read-only token even for the public MA repository, so a clean legacy install cannot bootstrap through the no-token public-release path. The current 1.0.23 release and its artifact checks do not by themselves prove this legacy path works.

### MA 1.0.23 update screen runtime check — 2026-11-20

- Installed MA 1.0.23 (versionCode 1009) over the historical 1.0.003 installation in `MA_A34_Sim`; Android retained package `com.doumi.helper` and launched the updated app successfully. This was an emulator-only in-place ADB install, not an AutoUpdate install.
- Opened the running app's AutoUpdate screen. It displayed installed version `1.0.23 (1009)`, the configured public source `GitHub Releases (siny-dev/MA)`, and latest published version `1.0.23 (1009)`. This confirms the app loaded its public repository configuration and obtained the current release metadata without asking for a token.
- Tapped **지금 업데이트 확인**. The screen remained at latest version `1.0.23 (1009)` with no SHA/signature or repository-connection error. Android still reports installed version `1.0.23` / code `1009`.
- Because there is no newer release in the channel, this run did not exercise APK download, signature verification of a newer candidate, Android install prompt, or the resulting version change. It therefore does not verify the complete patch-install path or phone behavior.

### MA 1.0.24 patch — AutoUpdate installer session fix — 2026-11-20

- Cause confirmed on Android: PackageInstaller.Session.commit() ran before the openWrite() output stream closed. The platform rejected the session with SecurityException: Files still open, which the app incorrectly surfaced as a generic update-file access error.
- Code change: flush/fsync and close both APK streams before committing the still-open installer session.
- Unit verification: :app:testDebugUnitTest passed, 334/334.
- End-to-end emulator verification: built matching debug-signed 1.0.22 and 1.0.23 variants, served the candidate through a temporary local manifest, and exercised download → SHA/package/signer verification → PackageInstaller confirmation → install. Android displayed its normal update confirmation; after confirming, the emulator reported MA 1.0.23 (1009). This is an emulator test, not a phone result.
- Published GitHub release `v1.0.24` was remotely verified: package `com.doumi.helper`, versionCode `1010`, 1,241,530 bytes, SHA-256 `a7dde096cea16aeaa803e13f99043acf724749a15cb73e4a7b2af5ed9118b8ad`, pinned v1/v2 signer. The local release APK is byte-for-byte the verified asset.
- Emulator production-path check: installed the official signed `v1.0.23` (1009), allowed its Android install-from-source setting, downloaded and verified official `v1.0.24`, then pressed `업데이트 적용`. The emulator remained at 1.0.23 and showed the same generic security error; no installer confirmation appeared. The v1.0.23 tag source confirms why: it invokes `Session.commit()` before closing the `openWrite()` stream. That installed binary cannot apply the fix contained in 1.0.24 through its own broken installer.
- Installed the exact verified `v1.0.24` APK by ADB on the emulator only; Android reported versionCode 1010 and the MA app launched. This checks the released binary itself, but is not an AutoUpdate transition. No physical phone was connected or changed. Opening AutoUpdate in that installation displayed `설치 버전 1.0.24 (1010) · 최신 배포 1.0.24 (1010)`.
- Provenance note: remote tag `v1.0.24` currently points at the pre-fix source commit, although the remotely verified APK was built from the fixed working tree. The source fix and this record are now recorded on main for reproducibility; the existing release tag was not rewritten.

### MA 1.0.25 installed-package recovery — 2026-11-20

- Reproduced the user screenshot on the exact official v1.0.20 app line in `MA_A34_Sim`: package `com.siny.apps.ma_3c44c3e1b8`, versionCode 19, Android Debug signer `d2206ba8af559c091b82d7dd65e16f597629c361c623f93f0da10a5a43c84679`. Its updater displayed the same “GitHub update app identity does not match” error against v1.0.24.
- Cause: v1.0.20 and v1.0.24 share `app_id=ma-3c44c3e1b8`, but v1.0.20 is installed as `com.siny.apps.ma_3c44c3e1b8` while v1.0.24 targets `com.doumi.helper`. The preceding v1.0.23→v1.0.24 test used the other package line and did not represent this installed-app path.
- Prepared the current source as version `1.0.25` / versionCode `1011` while restoring the installed v1.0.20 package and its matching signer. Release build, APK package/version inspection, and v1/v2/v3 signer verification passed. `:app:testDebugUnitTest` passed 334/334.
- Published GitHub release v1.0.25 with package com.siny.apps.ma_3c44c3e1b8, versionCode 1011, signer SHA-256 d2206ba8af559c091b82d7dd65e16f597629c361c623f93f0da10a5a43c84679, APK SHA-256 6b4e1f070649044bb547146bf36beef5f2cc0524296b07aaa85fa18476de2417. The manifest package and signer now match the archived v1.0.008 and v1.0.20 package line; v1.0.24's com.doumi.helper identity was the wrong line for these installs.
- Started with archived official v1.0.008 (versionCode 7) in the emulator and checked the live v1.0.25 release. It failed at the old verifier with the generic SHA/signature/package error. Source inspection confirms v1.0.008 required non-empty modern SigningInfo arrays and had no legacy-signature fallback. This is an actual failed update attempt, not a passing simulation.
- Repeated on official v1.0.20 (versionCode 19): after correcting the package identity it reached the Android installation-permission flow, but the installed code commits while openWrite() is still open; the old installer path cannot apply its own fix. Both failures are in the old installed binaries, before the patched v1.0.25 code can run.
- Installed the exact published v1.0.25 artifact over v1.0.008 on the emulator with adb install -r; Android accepted it in place and the app launched. Settings then displayed installed 1.0.25 (1011) and latest release 1.0.25 (1011). This proves package/signature compatibility and the published app's launch/update-feed metadata, but is not proof that the old app's in-app updater can install its own repair.
- The user phone is not connected in this run, so its exact installed version and on-device update result are not established. The archival v1.0.008 and v1.0.20 packages were tested on the emulator only.
