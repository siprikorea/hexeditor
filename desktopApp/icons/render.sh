#!/bin/sh
# Regenerates the icon files from icon.svg with headless Chrome (macOS: also needs sips, iconutil).
set -e
cd "$(dirname "$0")"
FONT="$(cd ../../shared/src/commonMain/composeResources/font && pwd)/jetbrains_mono_regular.ttf"
TMP="$(mktemp -d)"
sed "s#<defs>#<defs><style>@font-face{font-family:'JetBrains Mono';src:url('file://$FONT');}</style>#" icon.svg > "$TMP/icon.svg"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
"$CHROME" --headless=new --user-data-dir="$TMP/profile" --default-background-color=00000000 --hide-scrollbars \
  --allow-file-access-from-files --virtual-time-budget=2000 --window-size=1024,1024 \
  --screenshot="$TMP/icon1024.png" "file://$TMP/icon.svg" >/dev/null 2>&1
cp "$TMP/icon1024.png" icon.png
mkdir -p "$TMP/icon.iconset"
for s in 16 32 128 256 512; do
  sips -z $s $s icon.png --out "$TMP/icon.iconset/icon_${s}x${s}.png" >/dev/null
  d=$((s * 2)); sips -z $d $d icon.png --out "$TMP/icon.iconset/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$TMP/icon.iconset" -o icon.icns
python3 - "$TMP" <<'PY'
# Windows .ico with PNG-compressed entries (supported since Vista).
import struct, subprocess, sys, os
tmp = sys.argv[1]
sizes = [16, 32, 48, 64, 128, 256]
blobs = []
for s in sizes:
    out = os.path.join(tmp, f"ico{s}.png")
    subprocess.run(["sips", "-z", str(s), str(s), "icon.png", "--out", out], check=True, capture_output=True)
    blobs.append(open(out, "rb").read())
header = struct.pack("<HHH", 0, 1, len(sizes))
offset = 6 + 16 * len(sizes)
entries = b""
for s, b in zip(sizes, blobs):
    entries += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(b), offset)
    offset += len(b)
open("icon.ico", "wb").write(header + entries + b"".join(blobs))
PY
sips -z 32 32 icon.png --out ../../webApp/src/webMain/resources/favicon.png >/dev/null
sips -z 180 180 icon.png --out ../../webApp/src/webMain/resources/apple-touch-icon.png >/dev/null
cp icon.png ../src/main/resources/icon.png
rm -rf "$TMP"
echo "icons written"
