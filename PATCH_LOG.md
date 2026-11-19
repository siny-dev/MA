# MA patch log

Each entry records the exact change, version/artifact, verification performed, outcome, and what that outcome does not prove. Add an entry after every source, build, or release patch; do not mark a phone result as verified unless a phone result was actually observed.

## 2026-11-19 — AutoUpdate release diagnosis and validation gate

### Version-by-version findings

| Version | APK identity | Update artifact result | Finding |
| --- | --- | --- | --- |
| 1.0.005 | `com.doumi.helper`, versionCode 1006, original signer SHA-256 `5f5951336912c1c8772c96fccc88799ba96beba2488fe3296d22c23469da8987` | v1/v2/v3 signature verification passes | Last known-good baseline. The existing handoff and build-verification record says the 1.0.004 → 1.0.005 update was confirmed on the phone. |
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
- Updated the module-contract and local-feed checks for the restored package ID.
- Added PC-only compatibility checks against the actual 1.0.005 APK and the archived 1.0.005 `UpdatePolicy.java`, plus a generated old-channel `latest.json` using the exact historical six-field schema.

**Verification performed:**

- `:app:testDebugUnitTest` and `assembleRelease`: passed.
- APK identity comparison: 1.0.005 (`com.doumi.helper`, code 1006, signer `5f5951…`) → candidate 1.0.21 (same package, code 1007, same signer): passed.
- Historical 1.0.005 `UpdatePolicy` Java checks: matching package, higher version code, tag, actual APK SHA/size, and signer accepted; a reset version code, mismatched tag, and changed signer were rejected.
- Historical `latest.json` field schema: exact match.
- Candidate APK: 1,241,269 bytes; SHA-256 `1d1fa360cc8fc0f639ec3790d9ffee10afc6d234b9bcb92a342e2273d8f03634`.

**Status:** Local artifact and legacy policy checks pass. Remote GitHub release and old-channel version pointer have not yet been published; no phone installation is claimed by this entry.
