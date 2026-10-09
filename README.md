# Open Lounge – Android wrapper (standalone)

Android WebView wrapper for Open Lounge. Package/app ID: `com.donnydey.openlounge`.
It loads **https://openlounge.app** in a WebView (web app + Node server live in the main repo).
Current Android version: **0.2.8** (versionCode 10); shared chat UI/protocol remain in the main web repository.

Extracted with full history from `/workspace/openlounge` (`android/` subdirectory) via `git filter-repo --subdirectory-filter android`.
Branches touching android are included (e.g. `release-signing`, `firebase-push`).

Secrets are NOT in this repo: signing keystore lives in `~/.secrets`, `keystore.properties` / `local.properties` / `*.jks` are gitignored.
Note: `app/google-services.json` (Firebase client config) is tracked upstream and therefore present.

Build and unit-test: `./gradlew testDebugUnitTest assembleDebug` (or `assembleRelease` with keystore.properties configured).

## Sync
`donnydey22/openlounge` (`android/`) is the source of truth. Keep this repository's Android source in exact sync with that directory; land substantive changes there first, then mirror them here.
