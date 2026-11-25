# MA change log

## 1.0.034 (2026-11-25)

- Close the APK input and PackageInstaller session output before committing the install session. Android rejects `commit()` with `Files still open` otherwise, so the downloaded update never reached installation.
- Report Android installer rejection separately from APK SHA/signature verification failures.
- Add an Android 14 emulator test that downloads a signed-equivalent next build, commits it without user action, and checks the installed version code.

## 1.0.033 (2026-11-25)

- Declare Android's normal `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission so eligible self-updates can install without a repeated system approval after this bootstrap update is installed.
- Keep the verified PackageInstaller fallback for Android/device cases that still request user action.

## 1.0.032 prior stable release (2026-11-25)

- Start verified Android updates automatically after download on launch and while MA is open; keep the Android installer confirmation when the OS requires it.
- Rename the settings action to “최신 버전 확인” and remove the separate in-app install button.
- Add a rollback-version picker backed by validated `rollback_versions` entries in the stable feed.
- Add release tooling to register verified historical-source candidates in the rollback list and discard entries superseded by a newer stable version.

This release was superseded by 1.0.033 after the first update prompt exposed the missing Android self-update permission.

## 1.0.031 development candidate (2026-11-25)

- Convert NAVER Local Search WGS84 coordinates from its documented integer 10⁻⁷-degree representation before range validation and Naver Maps handoff.

This is a local development candidate. It has not been published to the MA update feed.

## 1.0.030 development candidate (2026-11-25)

- Speak only the recognized neighborhood and place name for a destination confirmation while showing the full NAVER address on screen.
- Let HyperCLOVA interpret natural yes/no/correction phrases at the confirmation turn, while only the app can open an already verified route.
- Prevent AI ranking from confirming NAVER places whose verified category or requested administrative area does not match; require a real neighborhood for nearby/general clinic searches and do not infer physician specialist credentials from Local Search metadata.
- Expire an unanswered destination confirmation after two minutes, support natural affirmative directions, stop capture on touch cancellation or app pause, and show concise voice choices with full addresses on screen.
- Clear an unsuccessful search before the next destination request so a failed query cannot silently contaminate a new request.

This is a local development candidate. It has not been published to the MA update feed.

## 1.0.029 development candidate (2026-11-25)

- Replaced the generic shell screen with the MA home layout: MA title, Mother’s Assistant subtitle, settings gear, large press-and-hold microphone, and a small taxi entry point.
- Added bounded 16 kHz audio capture that records only while the microphone is held, sends the clip to NAVER CLOVA CSR, and uses HyperCLOVA X to interpret the request and rank only real NAVER Local Search results.
- Added one verified-place confirmation before opening the NAVER Maps public-transit route scheme. A rejection clears the candidate; corrections start a fresh search, and unclear confirmation can be repeated only once.
- Added per-device Android Keystore encryption for CLOVA Studio and NAVER API HUB credentials, without putting keys in APK assets.
- Kept location/category context when the user adds a natural follow-up, and stop microphone capture immediately when the user releases the button.
- Adjusted the MA header alignment, microphone size, and taxi shortcut to follow the supplied screen reference more closely.
- Added regression coverage for positive/negative confirmation, destination corrections, ambiguous replies, voice selection, coordinate validation, and NAVER public-transit route links.

This is a local development candidate. It has not been published to the MA update feed.

## 1.0.027 baseline (2026-11-21)

- Recreated the MA app from the Mobile Basic Tool while retaining app ID, Android package, existing signer fingerprint, versionName, and versionCode so the installed app can receive a compatible update.
- Kept AutoUpdate and AutoSync pinned to their shared module repositories; assigned this MA app its own update feed and AutoSync data path.
- Left prior MA code and Git history in the legacy backup; only the app identity, signer continuity, and product requirements carry forward.

## 1.0.2 (2026-11-21)

- Made schema-guard migration atomic and retryable, and protected old records when legacy hard deletes are followed by ID reuse.
- Preserved additive fields recursively inside JSON objects, treated arrays as replaceable leaves, and retained explicit JSON null values without confusing them with the string `"null"`.
- Added Android SQLite regression coverage for migration rollback/retry, nested merge behavior, stable array restoration, and legacy ID reuse.
- Corrected template release-feed guidance and removed the duplicate AutoSync URL property; new app generation still starts at version 1.0.0 / versionCode 1.

## 1.0.1 (2026-11-20)

- Added a rollback candidate command that rebuilds an older Git source at the next Android versionCode, keeping the stable update feed unchanged until promotion.
- Reject candidate versionCodes equal to or below the stable feed before contacting GitHub.
- Bumped the template app version to 1.0.1 (versionCode 2); the app generator explicitly resets new programs to 1.0.0 (versionCode 1).
- Verified the signed release build, release metadata, APK package/version identity, candidate promotion flow, and rollback version selection with local tests.

