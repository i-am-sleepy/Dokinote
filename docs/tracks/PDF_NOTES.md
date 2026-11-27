# Track: PDF reader/editor + Obsidian-style notes

Branch `track/pdf-notes` · app `pdf-app` (`app.dokinote.pdf`), engine module `pdf-engine` (WIP) · status `status/pdf-notes.md`.
Own only: `pdf-app/`, `pdf-engine/`, `notes-*` modules, this file, `PDF_NOTES_FEATURES.md`, `status/pdf-notes.md`.

## Goal
An offline PDF reader/editor with Xodo-class features (minus ads, cloud, analytics) and an Obsidian-compatible notes system where PDF annotations become structured Markdown notes, without AI. Feature inventory: `docs/tracks/PDF_NOTES_FEATURES.md`. Priority note: the user shifted the main effort to the Office track; this track continues in parallel.

## Decisions (user-approved)
- Engine: MuPDF 1.28.5 (AGPL). UI: Kotlin + Compose with a custom `View` for the document surface.
- PDF annotations are written into the PDF itself (standard annotations, UUID in the annotation's `/NM`, optional custom keys) and mirrored in the app database. MuPDF 1.28.5's Java API exposes the raw annotation object plus colour, quad points, ink lists, dates, author, contents, trailer access and save-to-stream (verified in the Java sources).
- Notes: Markdown files are the source of truth; SQLite is only a rebuildable index (search, links, tags, annotation map). Use bundled SQLite with FTS5 (availability UNVERIFIED, check).
- Vault: a user-chosen shared-storage folder, accessed with All-files access and the direct file API (same as Obsidian on Android). The user runs Obsidian (Android) on the same phone, so the vault must be a device-storage folder. Obsidian "app storage" vaults are private and unusable.
- Generated notes: one companion note per document; each excerpt is a managed block with an Obsidian block ID (`^dk-...`) so other notes can embed a single highlight; regeneration touches only managed blocks (never clobber user edits; the vault is live-shared with Obsidian). Chapters come from the PDF outline. Handwriting representation (SVG strokes vs PNG crop vs both) is open, discuss later.
- Document identity: our UUID registry in a hidden `.dokinote/` folder inside the vault, one append-only journal per device (sync-safe). Resolve by PDF `/ID[0]` + content hash + fuzzy match; relink flow when a file moves.
- Anchors: engine-neutral W3C-style selectors (text quote + prefix/suffix, text position, page + rect) plus the annotation UUID.
- Obsidian links: Obsidian understands `[[file.pdf#page=N&selection=a,b,c,d]]`; the numbers are PDF.js text-layer indices that MuPDF cannot reproduce reliably (inference). PDF++ adds optional `&color=` and `&rect=`; other plugins use `#annotation=ID` or `#markId=`. Emit `#page=N` plus our own annotation-UUID parameter. Whether Obsidian tolerates unknown parameters is UNTESTED. An Obsidian companion plugin is deferred; plain compatibility first.
- Spreadsheet/Office content is out of scope here.

## Verified measurements (vivo V2434, Android 16, MuPDF 1.28.5 AAR, spike app)
- libmupdf_java.so is 16 KB page-aligned (static ELF check). arm64-only debug APK about 37 MB.
- Render at 1080 px wide, median ms/page: text book (594 pages, 47 MB): AA8 141.7, AA4 84.7, AA0 84.8. Scanned book (293 pages, 51 MB): AA8 304, AA4 70, AA0 75. First page cold 230-316 ms; document open 8-33 ms; text extraction of 24 pages 52-108 ms.
- Parallel rendering with one Document per worker, 24 pages incl. opens, AA8: text book x1 3662 ms, x2 2264, x4 1701; scanned x1 7670, x2 6133, x4 5900 (poor scaling). The "AA=4 parallel" numbers are INVALID: AA is a per-thread MuPDF setting (inference), so call `Context.setAntiAliasLevel` on every render thread.
- Native heap after the tests: 270-282 MB (cause unverified, likely MuPDF's store).
- Redaction PASS on the user's PDF: 5 hits on 3 pages redacted in 380 ms; word gone from the text layer; box brightness 201 -> 22.7; file grew 16.5 MB -> 18.7 MB.
- MuPDF opens docx/xlsx/pptx/epub on the phone as reflowable text; rendered docx/pptx are text-only (see the Office track).
- PDFium (desktop test): API has no redaction/encryption/reflow; annotations written through its raw API were inline dictionaries without appearance streams and MuPDF did not list them.
- UNTESTED: AA4 vs AA8 visual quality; parallel scaling at AA4; MuPDF signature verification (needs an OpenSSL-enabled build?); PDF JavaScript; appearance streams across viewers; memory cap.

## Viewer design (planned; `pdf-engine` is written but has never been compiled)
- Custom `View` (not Compose Canvas) with `ScaleGestureDetector`, `GestureDetector`, `OverScroller`; Compose for chrome. Fit-width pages; doc space in doubles; binary search over page tops; anchor preserved when page sizes arrive late.
- Tiles 512 px at zoom levels {1, 1.5, 2, 3, 4, 6, 8, 12} (choose the smallest level >= zoom) plus half-width whole-page thumbnails as fallback; `LruCache` bitmap budget about min(160 MB, memoryClass/2). Do NOT `recycle()` bitmaps (hardware canvas may still reference them).
- `TileRenderer`: 2-4 worker threads, each with its own Document, per-thread AA level, small LRU of loaded pages; each submit replaces the pending queue and aborts stale running jobs via `Cookie.abort()`. `AndroidDrawDevice(bitmap, x, y)` origin = tile offset in the page raster; matrix scales page points to page pixels.
- Night mode via an inverting `ColorMatrixFilter` at draw time (no re-render). Fast render = AA 4, best = AA 8 (user toggle) until visually compared.
- Open flow: copy the Uri into the cache (extension kept for MuPDF), later switch to fd-backed streaming; password dialog; recents with persisted URI permission and first-page thumbnail; intent filters for VIEW.

## Next tasks
1. Make `pdf-engine` compile in CI; write `DocumentView`, Home (recents) and Viewer screens; outline sheet; page slider.
2. Visual AA4 vs AA8 comparison; per-thread AA fix verified; memory cap investigation.
3. Text selection, search, links, then annotations (highlight first) with correct appearance streams, then forms, page tools, redaction UI.
4. Notes platform per the decisions above (vault access, index, companion notes), after the reader is usable.
