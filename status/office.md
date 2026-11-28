# Status: track/office
Updated: 2026-11-28 (A/B on phone confirms CRC cause; 0.0.6 adds zip preflight repair, cold-start/watchdog fixes)

## Done (code written, NOT yet compiled by CI, NOT run on the phone)
- `office-app` is now the P0 spike app (versionName 0.0.2-office-p0-spike):
  - Engine = the pinned TDF LibreOffice Viewer 26.2.7.3 (versionCode 135) libs + assets. `tools/fetch_lok_apk.sh` (run by CI, pinned by sha256 in `office-app/lok-apk.lock`) unpacks `lib/arm64-v8a/*.so` and `assets/` into `office-app/src/main/{jniLibs,assets}` (git-ignored; the main .so is 196 MB).
  - `org.libreoffice.kit.*` Java bindings copied from tag libreoffice-26.2.7.3 (same package, so the JNI symbols bind); only `LibreOfficeKit.init` changed (Context instead of Activity, marked in the file).
  - Engine runs in service `:lok` (`lok/LokBenchService`), all LOK calls on one worker thread. UI process never loads LOK. Protocol: Messenger (`lok/LokProtocol`).
  - `lok/LokBench`: cold start, per-document load/first screen/256 px tile latency (p50/p90) at fit-width and 2x zoom, mid/end-of-document jump, slide sweep for presentations (twice), PDF and docx/pptx export time+size, `.uno:Web` reflow probe (R1), PSS at several points, close time, LOK callback counts, PNG of the first screen (print and web layout, slide 1).
  - Crash safety: progress journalled to disk; if the native code kills `:lok`, the UI restarts it, records "CRASH at step X" for that document and continues.
  - Report: in-app text (Copy / Share) from `ReportBuilder`.
- Test corpus: 18 docx + 7 pptx from Apache POI / LibreOffice test data (`office-app/testdata/manifest.tsv`, `tools/fetch_samples.py`). They are fixtures, not typical user files.
- Shared files touched (additive): `.github/workflows/android.yml` (fetch step + `tee -a`), `.gitignore`, `docs/THIRD_PARTY.md` (new).

## First phone report (vivo V2434, Android 16, 4 KB pages, 720x1608, 7.6 GB RAM)
- 27 documents imported, but the report had no engine start and no results: either the benchmark was not started yet or the `:lok` process died before logging (native crash in init). The 0.0.2 report could not tell which, so 0.0.3 adds app log, init step/inprogress markers, Android's process-exit records (with tombstone strings for native crashes) and own-uid logcat to the report.
- User's own test file `All_anatomy_written_questions.docx` (5 MB, 2608 paragraphs, two scanned-page PNGs 2550x3346 and 2484x3264, Google-Docs-style fonts): both stored PNG entries have CRC-32 = 0 in the zip (data-descriptor flag, PNG data itself is valid). Word accepts such files; LibreOffice's zip reader may not. A/B pair prepared (original vs same content with correct CRCs) to see whether LOK loads the original. If it fails, a tolerant zip pre-repair step is a product feature.

## Root cause of the first crash (found with 0.0.3 diagnostics)
- `LibreOfficeKit.initializeNative` aborted with an uncaught `com::sun::star::uno::DeploymentException` (SIGABRT in `libreofficekit_hook`). Cause: `assets/program/unorc` sets `UNO_TYPES=file://$APP_DATA_DIR/program/{udkapi,offapi,oovbaapi}.rdb`, and those files only exist in `assets/unpack/`. The TDF Viewer copies `assets/unpack/**` into the app data dir before starting LOK (`LibreOfficeMainActivity.updatePreferences` -> `copyFromAssets(assets, "unpack", dataDir)`); our spike skipped that. 0.0.4 does the same in `LokBench.unpackAssets` (once per app version, timed). Lesson: the Viewer's own init is more than `LibreOfficeKit.init`.
- Verified on the phone (screenshot): 0.0.4 gets past init (libload 154 ms, init total 1584 ms, first_ever=true).
- Then the first document (`All_anatomy_written_questions.docx`, the user's file with CRC-32 = 0 PNG entries, alphabetical first) ran for minutes with no result: unknown whether `documentLoad`, layout or a tile call. 0.0.5: UI-side watchdog kills the `:lok` process when one step exceeds 120 s, records status HANG with the step name, and continues; live "now: doc | step | seconds" line; Refresh report works while busy.

## P0 results, first full run (vivo V2434, SD-class 8 cores, 7.6 GB RAM, Android 16, 720x1608; engine-only times, no UI/IPC/scroll; user's eyes confirmed only MRCP.docx)
- Engine start: libs+init 0.8 s warm process (1.6 s the very first time), plus 0.2 s one-off unpack of 38 files / 11 MB; 103 MB PSS idle. Cold-start end-to-end test (button 3) NOT run yet.
- 26/27 documents OK, no engine crash in the run. Writer: documentLoad 0.08-0.8 s typical (MRCP 10 pages/3 MB: 1.3 s + first screen 94 ms; 62 pages: 2.3 s + initRender 0.9 s; 178 pages: 16.8 s). Layout is done at load: jumping to the end tile afterwards cost 1-26 ms and the document height never changed.
- 256 px tile paint: p50 0.4-7 ms, p90 <= 21 ms, one screen (21 tiles) 10-260 ms; single first-screen paint 6-300 ms (heaviest: image/chart docs).
- Impress: load 1-11 s (themes.pptx 11.3 s for 10 slides, 28 slides 10.3 s, 48 slides 6.7 s, 23 slides 2.5 s); per-slide paint p50 13-73 ms, worst 1.1 s; revisit sweep roughly 2-6x faster; PDF export of the 48-slide deck 30.9 s, others 0.3-2.3 s; pptx re-save 0.5-4.9 s.
- Memory (PSS): docx 180-330 MB loaded, pptx 200-535 MB; after closing a doc 250-450 MB stays in the process (supports killing :lok when idle). Not separated from accumulation across 26 documents in one process.
- Export round trips produce files of plausible size (docx 6 KB-1.5 MB); validity in Word NOT checked.
- R1 reflow probe: `.uno:Web` via postUnoCommand changed the document size in 0/19 Writer files (only +-1..18 twips on two). No reflow this way; command acceptance itself unverified.
- Anatomy docx (CRC-32 = 0 on stored PNG entries): desktop LibreOffice 24.2.7.2 in the sandbox rejects the original in 0.6 s ("source file could not be loaded") and converts the CRC-fixed copy to PDF in 2.6 s. On the phone the original blocked `documentLoad` for minutes (no error): hypothesis = LOK waits on a "repair?" interaction nobody can answer. Needs: preflight zip check/repair before handing files to LOK (Word opens such files). A/B run on the phone (0.0.5): CRC-fixed copy loads in 2.9 s (initRender 6 ms, first screen 591 ms) and renders fine by eye; the original blocks `documentLoad` for >120 s (watchdog kill) -> CRC cause confirmed, hang (not an error) is the engine's behaviour on this file. The fixed copy then stalled in `export-pdf` for >120 s (desktop LO converts it in 2.6 s; cause unknown: image re-encode? page count unknown). 0.0.6: `ZipPreflight` re-writes zips with bad CRC before load; save-as docx/pptx is now measured before PDF; exports get a 240 s limit.
- Cold-start test hung in 0.0.5: it picked the alphabetically first .docx = the hanging original, and the watchdog only covered the benchmark. 0.0.6: median-size .docx among known-good docs, watchdog covers cold mode, import is additive (+ "Clear documents" button).

## P0 results, second run (0.0.6, same phone)
- Zip preflight works: the anatomy ORIGINAL (2 stored PNG entries with CRC-32 = 0) is repaired in 92 ms and then loads in 2.2 s (hung before); the CRC-fixed copy needs no repair (check costs 25 ms for 5 MB) and loads in 2.8 s. 59 pages, first screen 0.6-0.7 s, tiles p50 ~12 ms (image-heavy), docx save 0.85-0.9 s, PSS 200-260 MB loaded, 165 MB after close. Rendering looked fine by eye (user).
- PDF export of that doc took 175-176 s on the phone (desktop LibreOffice 24.2 converted the whole file, load included, in 2.6 s). Other documents exported in 0.03-3.3 s (48-slide pptx 31 s). Hypothesis (untested): per-page re-encoding of two ~8 MP scanned-page images. Needs a background/cancellable export in the product and possibly image downscaling.
- Cold start test (3 runs, median-size docx = 234 KB): engine init 0.66-0.70 s, documentLoad 0.51-0.65 s, first screen 87-147 ms; service -> first screen 1.37-1.61 s; UI-side bind -> first screen 1.53-1.78 s (engine only; nothing drawn on screen). PSS ~155 MB. Engine init was ~2 s on the first start after a force stop and 0.55-0.7 s on later restarts (file cache), so a background pre-warm at app start is worth it.
- Watchdog confirmed on a real hang (0.0.5): killed `load` and `export-pdf` after 120 s, recorded HANG, run continued.
- Still open from the P0 list: typing latency (probe added in 0.0.7), baselines (Collabora/Viewer/WPS) on the same docs, docxjs experiment, R1 (reflow) alternatives, size/bundling recommendation. Concurrency deliberately skipped (LOK README: single thread).

## Verified
- (by inspection of the uploaded APK) arm64 only, versionName 26.2.7.3, 14 native libs, `extractNativeLibs=true`, no INTERNET permission, 86 asset files; the exported JNI names of `liblo-native-code.so` match 1:1 the `native` methods of the Java bindings at tag 26.2.7.3. Exact argument types are NOT verified by name matching.

## Untested / unknown (do not trust until the phone run)
- That the CI build compiles and that the app starts the engine at all (asset unpack by `initializeNative`, library loading from our APK).
- Every number in the report. First-run numbers include asset unpacking. Build is a non-debuggable debug-signed APK; whether SAL logging is compiled into the F-Droid libs is unknown.
- Not covered yet: typing latency, concurrency (LOK README says single thread; skipped on purpose), baselines in Collabora / Viewer / WPS, docxjs experiment.

## Blocked on the user
- Install `Dokinote-office-dev.apk` from pre-release `nightly-track-office`, extract the sample zip, run the app and send back the report text (Copy button) plus a screenshot of the rendered first screens.
- About 10 real .docx files and a 30-slide .pptx still wanted for decision-grade numbers.

## Next
Read the first report; fix crashes/failed loads; then the baselines and the P0 recommendation (bundle vs companion vs download, page source, reflow R1/R2/R3).
