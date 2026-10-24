#!/usr/bin/env bash
# headless_bench_test.sh - the decode benchmark of the fork:
#   raw-view --file S --headless --paced --frames N   decodes N packets at 60 fps
#   with no window (before, --paced always opened the live window and looped
#   the file forever) and reports avg/p99/max; decbench builds where libva is.
set -u
cd "$(dirname "$0")/../raw-recv"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
make -s raw-view >/dev/null 2>&1 || { echo "raw-view does not build"; exit 1; }
t0=$(date +%s%N)
out=$(SDL_VIDEODRIVER=dummy timeout 20 ./raw-view --file ../../logs/m83-runI/stream-first10.sft --headless --paced --frames 30 2>"$tmp/err") || { echo "did not finish: $(tail -3 "$tmp/err")"; exit 1; }
ms=$(( ($(date +%s%N) - t0) / 1000000 ))
[ "$out" = "packets=30 frames=30 undecoded=0 lost=0" ] || { echo "unexpected: $out"; exit 1; }
grep -q 'decode avg [0-9.]* ms, p99 [0-9.]* ms, max' "$tmp/err" || { echo "no p99 in: $(tail -2 "$tmp/err")"; exit 1; }
# 30 frames at 60 fps: about half a second, not as fast as it decodes
[ "$ms" -ge 450 ] || { echo "not paced: 30 frames in $ms ms"; exit 1; }
if pkg-config --exists libva; then
    make -s -B decbench >/dev/null 2>&1 || { echo "decbench does not build"; exit 1; }
fi
exit 0
