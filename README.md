# PortWatch

Simple Android app to watch your servers.

- Ping and TCP port checks (like `telnet host port`)
- Background monitoring at any interval (10s and up)
- Notification when a check goes down, and when it comes back
- History of every up/down change, with outage duration
- One tap "Check all", or tap a server card to check just that server

## Build

Push to GitHub. The workflow in `.github/workflows/build.yml` builds `PortWatch.apk`
(Actions → latest run → Artifacts). Push a tag like `v1.0.0` to also publish a Release.

Local build: Android Studio → Open this folder → Run.
