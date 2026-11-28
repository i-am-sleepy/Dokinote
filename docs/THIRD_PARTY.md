# Third-party components

| Component | Version | Licence | Source | Used for |
|---|---|---|---|---|
| MuPDF (`com.artifex.mupdf:fitz`) | 1.28.5 | AGPL-3.0 | https://mupdf.com, Maven `maven.ghostscript.com` | PDF engine (pdf-app) |
| AndroidX / Compose / Kotlin | see `gradle/libs.versions.toml` | Apache-2.0 | https://developer.android.com | UI and platform |
| LibreOffice Viewer native libs + assets (`liblo-native-code.so`, NSS, `assets/`) | 26.2.7.3 (versionCode 135, F-Droid build) | MPL-2.0 (plus the bundled third-party licences inside LibreOffice) | https://f-droid.org/packages/org.documentfoundation.libreoffice/ , source tag `libreoffice-26.2.7.3` at https://github.com/LibreOffice/core | **Office track P0 spike only**: unpacked from the pinned APK by `tools/fetch_lok_apk.sh` at build time, not committed |
| `org.libreoffice.kit.*` Java classes (`LibreOfficeKit`, `Office`, `Document`, `DirectBufferAllocator`) | tag `libreoffice-26.2.7.3`, `android/Bootstrap/src` | MPL-2.0 | https://github.com/LibreOffice/core | JNI binding to LibreOfficeKit. `LibreOfficeKit.init` modified to take a `Context` (marked in the file) |
| Office test corpus | see `office-app/testdata/manifest.tsv` | Apache-2.0 (POI) / MPL-2.0 (LibreOffice) | github.com/apache/poi, github.com/LibreOffice/core | Test fixtures, not shipped |

Before any release: ship the full LibreOffice licence texts and the third-party notices from the LibreOffice source
(`license.txt` / `NOTICE` in the source tag), and offer the corresponding source for the MPL-covered files.
