# Status: track/office
Updated: 2026-11-28 (P0 spike code written, first CI build pending)

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
