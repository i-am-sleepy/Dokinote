# Track: Office suite (Writer / .docx first)

Branch `track/office` · app `office-app` (`app.dokinote.office`, currently a placeholder) · status `status/office.md`.
Own only: `office-app/`, `office-*` modules, this file, `status/office.md`.

## Goal
A full-featured, offline, open-source Office suite for Android that is faster and better than Collabora Office mobile and a good WPS Office replacement. **Milestone 1: Word (.docx) viewing and editing with maximum performance and Microsoft-file compatibility.** PowerPoint viewing next (editing later). Spreadsheets: view-only only if nearly free; no Calc editing. Any FOSS component may be used; we are not tied to LibreOffice.

## Decisions (user-approved)
1. Engine for now: LibreOfficeKit (LOK) Writer, behind an engine-neutral page-source interface so another engine (ONLYOFFICE, our own lite engine) can be added later.
2. LOK runs in its own process (`:lok`), started on demand and killed when idle. Tiles cross via shared memory plus a small binder/message interface. Packaging (bundled / companion APK / in-app download) is decided AFTER the spike.
3. Viewing uses live LOK tiles (the user rejected a LibreOffice-to-PDF cache), but keep the page source pluggable. Fall back to a LO->PDF cache displayed by MuPDF only if tile latency on the reference device is poor.
4. Word editing offers a page-faithful view and a reflowed mobile view. Reflow is the target default but is gated on feasibility (risks R1-R3).
5. Edited .docx are saved in place after an automatic backup of the original (content-addressed snapshot; write to temp, copy, verify; journal for crash recovery; "restore original").
6. Success means measured wins on the reference device against Collabora Office, LibreOffice Viewer and WPS Office (the user can install all three).
7. Our own UI and tile pipeline. Do not reuse the LibreOffice Android app's Firefox-derived gfx layer.

## Evidence so far (V verified in source/by viewing, I inference, U unverified)
- V: LibreOffice core `android/` is actively maintained (commits through Nov 2026). The Android app is a Firefox-for-Android-derived GLES tile layer over LOK. The whole core is one native library `liblo-native-code.so`. Reusable JNI binding: `android/Bootstrap/src/org/libreoffice/kit/{Document,Office,LibreOfficeKit}.java`; `Document` has `paintTile(ByteBuffer, w, h, twipX, twipY, twipW, twipH)`, `setPart`, `saveAs`, `postUnoCommand`, `getCommandValues`. The app uses 256 px tiles in a 4-byte-per-pixel direct ByteBuffer. Its README says LOK JNI calls must come from one thread.
- V: `desktop/source/lib/init.cxx` `doc_paintTile` holds a `SolarMutexGuard`: painting is serialized with layout and edits, so extra threads cannot speed LOK up.
- V: the LOK header has 71 callback types (state changed, cursor/selection rectangles, tile invalidation, comments, redlines, content controls, form fields, search results, clipboard, hyperlink...). `postUnoCommand(command, JSON args)`. The command config lists about 550 Writer and about 1000 generic `.uno:` commands (not all enabled or sensible on mobile).
- V: dialogs/popups are exposed as "windows" (`paintWindow`, `postWindow*`, `LOK_CALLBACK_WINDOW`): bitmaps plus forwarded input. No JSON dialog protocol in the upstream header, so build our own native controls that issue commands with JSON args.
- V (weak): no LOK API found that reflows Writer to the client width; `setClientVisibleArea` only informs painting. Writer has an internal browse/web layout. Whether it reflows under tiled rendering is UNTESTED.
- V (F-Droid listing): TDF "LibreOffice Viewer" (`org.documentfoundation.libreoffice`) is on F-Droid, MPL-2.0, arm64 APK about 83 MiB, editing "experimental", front end based on Firefox for Android, regularly released (26.2.x in 2026).
- V: Android 14+ requires dynamically loaded dex to be read-only; apps targeting Android 17 must also make native libraries loaded with `System.load` read-only or loading fails. F-Droid permits downloading executable binaries only with explicit user consent.
- V (viewed rendered images): MuPDF 1.28.6 renders .docx and .pptx as TEXT ONLY (no table grid, images, bullets, shapes or backgrounds). It is not a fidelity engine for Office; usable only for text extraction/indexing.
- V: ONLYOFFICE `core`, `sdkjs`, `web-apps`, `build_tools` are AGPL-3.0 **with additional terms** (retain notices, prominent modified-version notice, show ONLYOFFICE attribution/legal notices in the UI, no trademark rights, CC BY-SA for icons/docs). Active (Oct 2026). `build_tools` has Android targets (arm64-v8a, armv7, x86, x86_64) and a mobile deploy step (x2t converter libs, `doctrenderer` JS host, PdfFile, DocxRenderer = PDF->DOCX, fonts). `X2tConverter/build/Android` is a gradle lib project. Their offline Android editor app source was not found (`editors-webview-android` is a server-based demo). The editor layout runs in JS in a WebView; performance on the reference device unknown.
- V: docxjs (`VolodymyrBaydalka/docxjs`) is Apache-2.0, updated Nov 2026: DOCX->HTML for a WebView, view-only, approximate pagination. Candidate fast-first-paint layer, UNTESTED.
- U: why Collabora mobile is slow. Their GitHub repo is issue-tracker only (development on gerrit.collaboraoffice.com) and their mobile UI is web-based. Hypotheses (WebView/JS bridge, PNG tile encoding) are unverified, so measure the baselines.
- U: LibreOffice cold start, tile latency and memory on the Snapdragon 685; whether unused modules can be stripped; which fonts the Android build bundles; DOCX round-trip changes; whether the 83 MiB can shrink.

## First task: P0 spike (measure LOK on the reference device)
Needs from the user: the proper F-Droid LibreOffice Viewer APK (arm64-v8a, `org.documentfoundation.libreoffice`; the first upload was corrupted) and about 10 real .docx files they care about, plus a 30-slide .pptx.
Plan: spike app in `office-app` that bundles the `org.libreoffice.kit` Java classes (copy from LO core `android/Bootstrap`, MPL-2.0; keep the package so the JNI symbols bind), the APK's `lib/arm64-v8a/liblo-native-code.so` and assets, replicating what the Viewer's `LibreOfficeKit.init` does to unpack assets/config; run LOK in a `:lok` process. Measure: process cold start to init done; `documentLoad` time; first tile; per-256px `paintTile` latency (median/p90) at several zooms; concurrency (confirm parallel calls serialize); PSS peak and after close; kill/restart time; `saveAs` docx and pdf time; `.uno:Web` (browse layout) reflow test; typing latency (`postKeyEvent` -> invalidate callback -> tile).
Baselines on the same docs: Collabora Office, LibreOffice Viewer, WPS (time to first page, scroll smoothness by screen recording or `dumpsys gfxinfo` framestats via Termux adb over wireless debugging (unverified), memory via `dumpsys meminfo`).
Cheap extra: docxjs-in-WebView fast-open experiment on the same corpus.
Deliver a numbers table plus a recommendation: bundle vs companion APK vs download, page source choice, reflow R1/R2/R3.

## Risks and gated decisions
- R1: does LibreOffice's web/browse layout reflow through LOK? (test) R2: our own reflow editor from exported content (big, lossy). R3: ship page-faithful editing first, reflow later.
- Single-threaded engine, cold start, memory, size, dialogs need our own UI, DOCX round-trip fidelity, fonts (Calibri/Cambria need metric-compatible Carlito/Caladea).
- Strategic: building LibreOffice from source in CI would allow our own native entry points (structure dumps, selection->anchor, custom export), module stripping, pinned versions, reproducible FOSS builds. Feasibility on GitHub runners (6 h/job, about 14 GB disk) is UNVERIFIED.

## Roadmap
P0 spike -> P1 `:lok` process + shared-memory tile pipeline + Writer/Impress viewer with our gestures and tile cache -> P2 Impress polish (slides, notes, slideshow) -> P3 Writer editing (IME, caret/selection handles, formatting UI via `.uno:` commands with JSON, tables, images, comments, track changes, undo, safe save) -> P4 decide from numbers whether a native lite engine or ONLYOFFICE pieces earn a place (ONLYOFFICE DocxRenderer for PDF->DOCX is a WPS-style feature) -> later Calc view/edit.

## Integration notes (later)
Note/annotation anchors for Office documents must be text-quote + context selectors (pagination differs by engine and device); slide documents use slide index + shape id. Do not build the notes system in this track.
