# Dokinote

An offline-first, open-source Android PDF reader and editor. Goal: feature parity with the best commercial PDF apps, minus ads, accounts, analytics and cloud lock-in, plus more file formats.

**Status:** pre-alpha. Right now the app is an *engine spike* that tests MuPDF on a real phone (render speed, redaction, Office to PDF). See [docs/ROADMAP.md](docs/ROADMAP.md).

## Decisions
- Engine: [MuPDF](https://mupdf.com) (AGPL-3.0). The whole app is licensed **AGPL-3.0-or-later**.
- UI: Kotlin + Jetpack Compose (Material 3), Android only. minSdk 29 (Android 10), targetSdk 36 (Android 16). Native ABI: arm64-v8a only (32-bit ARM and x86 dropped).
- No network permission in the app. No analytics, ads, accounts or paywall.
- Cloud storage works through the Android document picker (Drive, Dropbox, OneDrive, Nextcloud apps), not through SDKs.

## Getting a build (no PC needed)
Every push to `main` builds an APK with GitHub Actions and attaches it to the [`nightly` pre-release](../../releases/tag/nightly).
If a build fails, the tail of the log is pushed to the `ci-logs` branch (`latest.txt`).

The APK is signed with the **public** development key `dev.keystore` so each build installs over the previous one. It is not a release key.

## Licence
AGPL-3.0-or-later. MuPDF is Copyright Artifex Software, Inc.
