#!/usr/bin/env bash
# bench-clip.sh OUT.h264 [MBPS] [SECONDS] - a synthetic 1080p60 H.264 clip shaped
# like the console's stream (High profile, one slice, IDR then P frames only, no
# B frames) for decbench and raw-view --file benchmarks. Motion: testsrc2 with
# temporal noise. Also writes OUT.sft (SFTR packets, frame numbers in kind).
set -eu
out=$1; mbps=${2:-8}; secs=${3:-10}
ffmpeg -v error -y -f lavfi -i "testsrc2=s=1920x1080:r=60,noise=alls=12:allf=t+u" -t "$secs" \
    -c:v libx264 -threads 2 -preset veryfast -tune zerolatency -profile:v high -bf 0 -g 600 \
    -x264-params slices=1:sliced-threads=0 -b:v "${mbps}M" -maxrate "${mbps}M" -bufsize "${mbps}M" \
    -pix_fmt yuv420p -bsf:v h264_metadata=aud=insert -f h264 "$out"
python3 -I - "$out" "$out.sft" <<'PY'
import re, struct, sys
data = open(sys.argv[1], 'rb').read()
idx = [m.start() for m in re.finditer(b'\x00\x00\x00\x01\x09', data)] + [len(data)]
with open(sys.argv[2], 'wb') as o:
    for n in range(len(idx) - 1):
        au = data[idx[n]:idx[n + 1]]
        key = 4 if b'\x00\x00\x01\x65' in au[:4096] else 0
        o.write(struct.pack('<IHHIIIIII', 0x52544653, 2, 2 | key, 1920, 1080, 0, len(au), 0, n) + au)
print(f'{sys.argv[2]}: {len(idx) - 1} packets')
PY
