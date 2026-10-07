# Open Lounge – Android wrapper (standalone)

Android WebView wrapper for Open Lounge. Package/app ID: `com.donnydey.openlounge`.
It loads **https://openlounge.app** in a WebView (web app + Node server live in the main repo).

Extracted with full history from `/workspace/openlounge` (`android/` subdirectory) via `git filter-repo --subdirectory-filter android`.
Branches touching android are included (e.g. `release-signing`, `firebase-push`).

Secrets are NOT in this repo: signing keystore lives in `~/.secrets`, `keystore.properties` / `local.properties` / `*.jks` are gitignored.
Note: `app/google-services.json` (Firebase client config) is tracked upstream and therefore present.

Build: `./gradlew assembleDebug` (or `assembleRelease` with keystore.properties configured).

## Sync
Do not edit here long-term; the main repo is the source of truth. Run `/workspace/sync-platform-repos.sh`
to re-extract (branches are rewritten from upstream; this README is re-applied as the top commit on `main`).
Local only — no remote configured.
