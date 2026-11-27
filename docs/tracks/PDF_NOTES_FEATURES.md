# Feature inventory (from clean-room analysis of Xodo PDF Reader & Editor 11.3.2)

Tags: [S] seen in the APK's manifest/classes/strings, [C] cloud in the original, [?] inferred. Plan: how we do it offline/open source. The analysis covered the manifest, class names, strings and bundled libs only (no method bodies; x86_64 build).

## Original app facts
Engine was Apryse/PDFTron (proprietary, cannot be reused). No ad-network SDK found; monetization = paywall, free-tier limits, watermark on shared copies, promo banners. Trackers: Firebase, Heap, Contentsquare, AppCenter, Iterable, Sprig, Osano. Cloud: Xodo Drive/Teams/Sign (eversign), Xodo Actions (S3 upload + poll), Google Drive/Dropbox/OneDrive SDKs, Google Translate hand-off, AutoDraw web endpoint. On-device ML: ML Kit OCR (Latin), Translate, Document Scanner. Opens pdf, doc/docx, ppt/pptx, xls/xlsx, images, cbz/cbr, md, txt.

## A. Viewing and navigation
Continuous/single/facing layouts, thumbnail slider, page indicator, jump to page; reading mode/reflow; colour modes (day, night, custom); auto/manual crop; text search with options, result list, voice search; bookmarks, outline (view/edit), layers (OCG), page labels, file attachments, rich media, sound; links (open/create); JavaScript toggle; text selection, copy, share, translate; signature verification; tabs; print; share screenshot.
Plan: MuPDF tiled viewer; GPU colour-matrix modes; MuPDF text search; own paragraph rebuild for reflow.

## B. Annotations
Highlight/underline/squiggly/strikeout (+ smart highlighter), free highlighter, ink with smoothing and ink-only eraser, shapes (line, arrow, rect, oval, polygon, polyline, cloud), callout, free text (rich text), sticky notes, stamps (standard/custom), signatures (multiple, saved), file attachment, sound, link annotations, redaction (rect/text/area/page/search/apply), measurement (ruler, perimeter, area, count, calibration), multi-select, style panel, undo/redo, customisable toolbars, AutoDraw shape recognition [C], flatten, XFDF.
Plan: standard PDF annotations with generated appearance streams; own smoothing/shape recogniser ($P/Protractor-style) offline; MuPDF redaction (verified).

## C. Forms
Fill text/checkbox/radio/combo/list/signature fields, create fields, flatten, import/export data, "detect form fields" [C][?].

## D. Security and signing
Open password; copy with changed/removed password (AES-256); digital signatures (local PKCS#12/Keystore); watermark tool; multi-party e-signing (Xodo Sign) is DROPPED (decision).

## E. Page and document editing
Rotate, delete, extract, reorder, duplicate, insert (blank/image/document/scan/web page), merge, split, permanent crop, page labels, outline edit, flatten, compress/optimise, compare documents. Plan: engine page grafting (+ qpdf-style tools if needed).

## F. Conversion and export
To PDF: Word/Excel/PPT, HTML, images, text/Markdown. From PDF: Word/Excel/PPT/HTML/JPG/PNG/PDF-A (cloud in the original). Decision: offline best-effort plus an optional self-hosted converter endpoint. Hard problems: PDF->Office, PDF/A.

## G. OCR, scanning, language
OCR to searchable PDF (Tesseract behind an interface; PaddleOCR later), document scanner (CameraX + OpenCV), offline translation (Bergamot/Argos, optional), language ID.

## H. Files and sharing
File browser (list/grid, recents, favourites, folders, search, sort, multi-select actions), VIEW/EDIT/SEND intents, open from link; cloud storage via the Android document picker only (no SDKs).

## I. Not carried over
Accounts, Xodo cloud services, Play Billing, paywall, free-tier limits and watermark, all analytics/messaging/survey/consent SDKs, advertising ID.

## Beyond the original (ideas)
More formats (EPUB, FB2, MOBI, XPS, CBZ/CBR/CB7, SVG, HEIC/WebP/AVIF, RTF/ODT, MD, DjVu), library full-text search, tags/collections, offline text-to-speech with highlighting, batch tools, PDF repair, editing existing text/images.
