# Roadmap

## Settled decisions
| Topic | Decision |
|---|---|
| PDF engine | MuPDF (AGPL-3.0), whole app AGPL-3.0-or-later |
| UI | Kotlin + Jetpack Compose, Android only |
| minSdk / targetSdk | 29 (Android 10) / 36 (Android 16) |
| PDF to Word/Excel/PPT | Offline best-effort, plus an optional self-hosted converter endpoint |
| E-signing | Local signing only (drawn/image + certificate). No multi-party service |
| Cloud | Android document picker only; no cloud SDKs, accounts, analytics, ads or paywall |

## Phases
0. **Skeleton + engine spike** (current): CI APK builds, MuPDF on-device checks.
1. **Reader parity**: file browser, viewer (tiled, fast), search, outline, bookmarks, colour/reading modes, print, share.
2. **Annotations and forms** with correct appearance streams, undo/redo, customisable toolbars.
3. **Page and security tools**: reorder/rotate/extract/merge/crop, passwords, signing, watermark, redaction, compression, compare.
4. **Scan, OCR, conversions**: CameraX + OpenCV scanner, Tesseract OCR, images/HTML/Office to PDF first, PDF to Office best-effort.
5. **Beyond the reference app**: extra formats (EPUB, FB2, MOBI, XPS, CBZ and more), library search, text-to-speech, editing existing text.
6. **Performance and visual polish.**

The full feature inventory (derived from analysing the reference app) lives in the Stage 1 design document and will be copied here as `docs/FEATURES.md`.
