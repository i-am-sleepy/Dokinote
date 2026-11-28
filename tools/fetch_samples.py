#!/usr/bin/env python3
"""Download the Office-track sample documents listed in office-app/testdata/manifest.tsv and verify sha256.
Usage: python3 tools/fetch_samples.py [outdir]   (default: dokinote-samples/)"""
import hashlib, os, sys, urllib.request

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1] if len(sys.argv) > 1 else "dokinote-samples"
bad = 0
for i, line in enumerate(open(os.path.join(root, "office-app/testdata/manifest.tsv"))):
    if i == 0 or not line.strip():
        continue
    kind, name, size, sha, url = line.rstrip("\n").split("\t")
    dest = os.path.join(out, kind, name)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    urllib.request.urlretrieve(url, dest)
    ok = hashlib.sha256(open(dest, "rb").read()).hexdigest() == sha
    bad += not ok
    print(("ok  " if ok else "BAD ") + dest)
sys.exit(1 if bad else 0)
