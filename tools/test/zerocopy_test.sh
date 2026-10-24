#!/usr/bin/env bash
# zerocopy_test.sh - the fork's zero-copy VAAPI -> OpenGL path (tools/raw-recv/zerocopy.h)
#  always: raw-view builds with it (libva found) and without it (ZEROCOPY=0)
#  SFT_GUI_TESTS=1 on a Wayland desktop with VAAPI: a synthetic clip is shown
#  for a few seconds with and without zero-copy (two windows open briefly);
#  the colour bars must match (BT.709 limited range, same as SDL's NV12 path)
set -u
cd "$(dirname "$0")/../raw-recv"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"; make -s -B raw-view >/dev/null 2>&1' EXIT
make -s -B raw-view ZEROCOPY=0 >/dev/null 2>&1 || { echo "does not build with ZEROCOPY=0"; exit 1; }
if grep -qa "zero-copy (VAAPI" raw-view; then echo "ZEROCOPY=0 still has zero-copy"; exit 1; fi
if pkg-config --exists libva; then
    make -s -B raw-view >/dev/null 2>&1 || { echo "does not build with zero-copy"; exit 1; }
    grep -qa "zero-copy (VAAPI" raw-view || { echo "libva found but no zero-copy in the build"; exit 1; }
fi
[ "${SFT_GUI_TESTS:-0}" = 1 ] && [ -n "${WAYLAND_DISPLAY:-}" ] || exit 0
command -v ffmpeg >/dev/null || exit 0
bash bench-clip.sh "$tmp/c.h264" 8 4 >/dev/null || exit 1
for m in zc nozc; do
    extra=; [ $m = nozc ] && extra=--no-zero-copy
    XDG_DATA_HOME="$tmp/data" timeout 30 ./raw-view --file "$tmp/c.h264.sft" --paced --seconds 4 --shot "$tmp/$m.bmp" $extra >/dev/null 2>"$tmp/$m.log"
done
grep -q 'decode: zero-copy (VAAPI' "$tmp/zc.log" || { echo "zero-copy did not start: $(grep zero-copy "$tmp/zc.log")"; exit 0; }
grep -q 'zero-copy off' "$tmp/zc.log" && { echo "zero-copy failed mid-run: $(grep 'zero-copy off' "$tmp/zc.log")"; exit 1; }
python3 -I - "$tmp/zc.bmp" "$tmp/nozc.bmp" <<'PY'
import struct, sys
def load(p):
    b = open(p, 'rb').read()
    off, w, h, bpp = struct.unpack_from('<I', b, 10)[0], *struct.unpack_from('<ii', b, 18), struct.unpack_from('<H', b, 28)[0]
    stride = (w * bpp // 8 + 3) & ~3
    def px(x, y):   # y from the top
        row = (h - 1 - y) if h > 0 else y
        i = off + row * stride + x * bpp // 8
        return b[i + 2], b[i + 1], b[i]
    return abs(w), abs(h), px
wa, ha, a = load(sys.argv[1]); wb, hb, b = load(sys.argv[2])
assert (wa, ha) == (wb, hb), ((wa, ha), (wb, hb))
# testsrc2's colour bars, sampled away from the moving parts (left 6/7 of the picture, middle rows)
worst = 0
for k in range(6):
    x = int(wa * (k + 0.5) / 7.2); y = int(ha * 0.47)
    pa, pb = a(x, y), b(x, y)
    worst = max(worst, max(abs(p - q) for p, q in zip(pa, pb)))
if worst > 3:
    sys.exit(f'colours differ by up to {worst}')
PY
