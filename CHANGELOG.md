# MA change log

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

