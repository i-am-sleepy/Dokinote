#!/usr/bin/env bash
# Fetch the pinned LibreOffice Viewer APK and unpack its arm64 native libs + assets for office-app (Office track spike).
# Usage: tools/fetch_lok_apk.sh            (CI: downloads, verifies sha256)
#        LOK_APK_LOCAL=/path/to.apk tools/fetch_lok_apk.sh   (use a local copy, still verified)
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source office-app/lok-apk.lock
WORK="build/lok-apk"; mkdir -p "$WORK"; APK="$WORK/$NAME"

verify() { [ -f "$1" ] && [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$SHA256" ]; }

if [ -n "${LOK_APK_LOCAL:-}" ]; then cp -f "$LOK_APK_LOCAL" "$APK"; fi
if ! verify "$APK"; then
  for u in $URLS; do
    echo "fetch: $u"
    if curl -fSL --retry 2 --connect-timeout 20 --max-time 900 -o "$APK" "$u" && verify "$APK"; then break; fi
    rm -f "$APK"
  done
fi
if ! verify "$APK" && command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
  echo "fetch: release asset lok-inputs/$NAME"
  gh release download lok-inputs --pattern "$NAME" --dir "$WORK" --clobber || true
fi
if ! verify "$APK"; then
  echo "error: could not obtain $NAME with sha256 $SHA256 (tried F-Droid mirrors and release lok-inputs)." >&2
  exit 1
fi
echo "ok: $NAME verified"

OUT="office-app/src/main"
rm -rf "$OUT/jniLibs" "$OUT/assets"; mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/assets" "$WORK/x"
rm -rf "$WORK/x"; mkdir -p "$WORK/x"
unzip -q -o "$APK" 'lib/arm64-v8a/*.so' 'assets/*' -d "$WORK/x"
cp "$WORK"/x/lib/arm64-v8a/*.so "$OUT/jniLibs/arm64-v8a/"
cp -r "$WORK"/x/assets/. "$OUT/assets/"
rm -rf "$OUT/assets/dexopt"          # Viewer's own baseline profile, irrelevant here
echo "unpacked: $(ls "$OUT/jniLibs/arm64-v8a" | wc -l) libs, $(find "$OUT/assets" -type f | wc -l) asset files"
