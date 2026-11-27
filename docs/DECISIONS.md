# Shared decisions (all tracks)

Working name: **Dokinote** (placeholder, rename later).

## Product
An offline-first, open-source Android document suite that is faster and better than WPS Office / Xodo-class apps: PDF reader/editor, Office documents (Word first), Obsidian-compatible notes. The core works fully offline; optional online features only if clearly valuable. No ads, accounts, analytics.

## Platform and build
- Android only. Kotlin + Jetpack Compose (Material 3). minSdk 29 (Android 10), targetSdk 36 (Android 16). arm64-v8a only (32-bit ARM and x86 dropped on purpose).
- No INTERNET permission in the default build.
- Reference device: vivo Y29 4G (Snapdragon 685, 8 GB RAM, Android 15/16; the app reports `vivo V2434`, Android 16, 8 cores, 4 KB pages). Every performance claim must be measured there.
- Build only via GitHub Actions. APKs are signed with the public dev key `dev.keystore` so updates install in place. One app per track (different applicationIds) so both install side by side.

## Licensing and distribution
- App: AGPL-3.0-or-later (MuPDF is AGPL-3.0). LibreOffice is MPL-2.0, which allows combining into a GPL-family larger work. ONLYOFFICE, if ever used, is AGPL-3.0 plus additional terms (see the Office track).
- Maintain `docs/THIRD_PARTY.md`: name, version, licence, URL, how it is used.
- Distribution: GitHub releases and FOSS repositories (F-Droid). F-Droid allows downloading executable code only with explicit user consent, and everything must be FLOSS.
- The reference-app analysis (Xodo) was clean-room: copy no Xodo/Apryse code, strings, icons or layouts.

## Engines
- PDF: MuPDF 1.28.5 via the official Android bindings (Maven repo `https://maven.ghostscript.com/`, `com.artifex.mupdf:fitz`). Chosen over PDFium because redaction, signing and extra formats are needed.
- Office: LibreOfficeKit (LibreOffice core) in a separate process; packaging decided after a spike. See the Office track.
- Two-process design: main process + a `:lok` engine process that is started on demand and killed when idle (the only way to unload LibreOffice).
- Intended module boundaries (for later integration): `core-model` (document identity, anchors, annotations; no engine dependencies), `core-storage` (vault IO, index), `engine-api` (PageSource / TextSource / AnnotationSource), `engine-mupdf`, `engine-lok`, `feature-*`, `app`.

## Cross-track compatibility (keep in mind, do not build yet)
- Anchors are engine-neutral selectors (text quote + prefix/suffix, page + rect, slide + shape id), W3C Web Annotation style, because pagination differs between engines and devices.
- Document identity = our UUID resolved by file fingerprint (PDF `/ID[0]`, content hash) with relink and fuzzy re-anchoring.

## Working rules
- Evidence first; label verified / inferred / untested; never claim a fix works before the user tested it on the phone.
- Offline by default. Keep usage efficient.
