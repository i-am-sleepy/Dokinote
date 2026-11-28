# Office track test corpus

25 files (18 .docx, 7 .pptx) fetched from public GitHub test-data directories, listed with sha256 and source URL in
`manifest.tsv`; the files themselves are NOT committed (`python3 tools/fetch_samples.py` rebuilds them).

- Sources: Apache POI `test-data/` (Apache-2.0) and LibreOffice core `sw/qa/extras/ooxml*/data`, `sd/qa/unit/data` (MPL-2.0).
- These are **test fixtures, not typical user documents**: several are deliberately malformed or exercise one odd feature
  (text boxes, linked frames, ToC fields, change tracking, chart parts). Use them for crash/fidelity probing and rough
  timing; the user's own real .docx/.pptx files are the decision-grade corpus.
- Sizes: docx 12 KB to 2.9 MB (fdo76316.docx has 344 media files); pptx 0.3 to 2.3 MB, 10 to 48 slides
  (KEY02.pptx is a one-slide file with a big image, customGeo.pptx has 48 slides).
