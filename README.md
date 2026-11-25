# PortWatch

Simple Android app to watch your servers.

- Ping and TCP port checks (like `telnet host port`)
- Background monitoring at any interval (10s and up)
- Notification when a check goes down, and when it comes back
- History of every up/down change, with outage duration
- One tap "Check all", or tap a server card to check just that server

## Build

Push to GitHub. The workflow in `.github/workflows/build.yml` builds `PortWatch.apk`
and publishes it under Releases as `v<versionName>` (from `app/build.gradle.kts`).
Bump `versionName` and `versionCode` for each new version.

Local build: Android Studio → Open this folder → Run.

## Signing key (required)

Every release must be signed with the same key, or Android refuses to install an update
over the old app. The key is kept in GitHub Secrets, not in the repo:

Settings → Secrets and variables → Actions → New repository secret

- `SIGNING_KEYSTORE_BASE64`: the keystore file, Base64-encoded
- `SIGNING_PASSWORD`: the keystore password

Keep a backup of the keystore. If it is lost, users must uninstall and reinstall once.
