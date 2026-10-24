# raw-view and raw-recv

**`raw-view`** is the PC viewer for the switch-frame-tap stream: libusb +
libavcodec + SDL2 in one process. It waits for the console to appear on USB
(`1209:5f1e`), decodes the H.264 stream live, and shows the frame rate, the
bitrate, frames lost, and two latencies in the title bar: `console` (the game
presenting a frame -> its header leaving the Switch) and `pc` (the header
arriving -> the frame on screen).

```
sudo apt install libusb-1.0-0-dev libsdl2-dev libavcodec-dev libavutil-dev
make                                   # must say "raw-view built WITH H.264"
./raw-view                             # live
./raw-view --record s.sft              # also save the stream (large: ~0.5-1 GB/min)
./raw-view --file s.sft                # replay a recording
./raw-view --threads N                 # decode threads (default 2; 0 = one per core)
./raw-view --low-latency               # one decode thread, frames out immediately
./raw-view --app                       # the desktop launcher: wait, reconnect, log
./raw-view --check                     # session, drivers, udev, USB and network
./raw-view --check --net 10.0.0.252    # test the Switch's TCP 9950 stream path
./raw-view --video-driver wayland      # force wayland (or x11) for one run
./raw-view --no-zero-copy              # fork: copy VAAPI frames to memory (upstream's path)
./raw-view --audio-fixed               # fork: upstream's fixed 40 ms sound cushion
```

### Fork: Linux viewer measurements (i3-8130U, UHD 620, iHD VAAPI)

Synthetic 1080p60 clips (`bench-clip.sh`: testsrc2 + noise, High, one slice,
IDR + P, no B - the console's shape), paced at 60 fps. Not live game footage.

| decoder thread, per frame | avg | p99 | max |
|---|---|---|---|
| VAAPI + `av_hwframe_transfer_data` (upstream) | 7.1 ms | 12.9 | 19.4 |
| VAAPI + `vaSyncSurface` only (zero-copy, `zerocopy.h`) | 2.1 ms | 4.8 | 7.2 |
| software, 1 thread | 6.9 ms | 10.5 | 26.3 |

(`decbench`, 8 Mbps.) In the viewer itself (`--file --paced`, window): decode
4.70 ms (max 13.2) with the copy, 1.74 ms (max 3.7) zero-copy; same colours.

Kept: zero-copy VAAPI -> EGL/OpenGL (default on Wayland/EGL), the adaptive
audio cushion (`audiopace.h`, model: 53 -> 6 underruns in 120 s for +12.6 ms
queued; a clean link stays at 40 ms), the 70- udev rule name.

Tried and dropped (so they are not chased again):
- `av_hwframe_map` + memcpy instead of `av_hwframe_transfer_data`: faster in
  `decbench` (6.8 -> 3.9 ms flat, 7.1 -> 5.7 ms paced) but not measurably in
  the viewer (3 x 1200 frames alternated: 9.65/7.87/8.79 vs 9.07/8.58/8.91 ms).
- reusing one destination frame for the transfer: 6.68 vs 6.88 ms, no gain.
- software decoding: no faster than the GPU and worse p99 at 20 Mbps (25.9
  ms); `AV_CODEC_FLAG2_FAST` no gain; slice threads no gain (the console sends
  one slice); frame threads cost ~0 per call but hold 1-2 frames (16-33 ms).
- decoding on the display thread: it is not - `decoder_main` is its own
  thread; the display only uploads and waits for vsync.
- trimming the standing display queue (drop a frame after N vsyncs with one
  waiting): in a model with 30-60 ms link stalls each second, latency 26 ->
  11-12 ms but skipped frames 1.7 -> 2.8 per second - smoothness lost, kept
  upstream's cap of two.
- measuring the display live on a hidden Hyprland workspace: the compositor
  throttles hidden windows (~19 fps, draw+vsync ~50 ms) - display numbers
  need a visible window.

`--app` writes `$XDG_STATE_HOME/switch-frame-tap/viewer.log` (normally
`~/.local/state/switch-frame-tap/viewer.log`), keeping the previous run as
`viewer.prev.log`. On KDE Wayland the viewer prefers the native Wayland driver
and falls back to X11 only if it cannot start.

`python3 ../sft_tool.py stats|last|artifacts|head` inspects recordings.

macOS: [Metal FSR 1](METAL-FSR.md) integrates with the Upscaling setting (U);
F9 selects RCAS strength 40/70/100 percent. Filtering is off by default.

### Fork: the console side (branch `melhorias-switch`)

**What "on the Switch" (`console`) measures:** the game's `queueBuffer` (the
present, recorded by the vi mitm) -> the frame's header about to go out. It
is NOT all capture cost: a game queues a frame before its GPU has drawn it,
and the capture must wait for that fence like the console's own screen does.
A GPU-bound game (BOTW unlocked, 35-45 fps) spends most of those 45-55 ms
there. The sysmodule now splits the age in every H.264 header (offset 24,
`block_h_log2`, unused for H.264 before; `tier4/applet-mitm/source/sft_frame_timing.h`):

    game  = present -> slot read starts (GPU still drawing, or the loop busy:
            the HUD's "already drawn" share says how often it was the loop)
    read  = svcReadDebugProcessMemory of the slot (+ cache flush)
    VIC   = read done -> NVENC submitted (RGBA -> NV12 + setup)
    enc   = NVENC submitted -> its end noticed
    send  = the rest: previous USB transfer, audio packet, bitstream copy

raw-view shows it in the full HUD (`game / capture`, `read VIC enc send`) and
prints `console split avg ms: ...` every second and at the end. Older viewers
and the Android app ignore the field.

Switches in `sdmc:/config/switch-frame-tap/config.ini` (all on by default;
`= 0` turns one off; the manager app rewrites config.ini without them, so a
setting made there turns them back on):

| key | what | expected |
|---|---|---|
| `frame_timing` | the split above | diagnosis only |
| `usb_idle_send` | 720p: the previous frame's USB transfer is completed and the audio packet sent while waiting for the next present, not between this frame's encode and its header | -1 to -2.5 ms console latency |
| `present_wake` | the capture wakes on the present (condition variable) instead of polling every 1 ms | -0.5 ms avg, less jitter |
| `usb_keyframe_interval` | USB keyframes every N frames (default 240, max 250; never shorter than `keyframe_interval`) plus on request (SFTK); raw-view now also asks after a resync | no once-a-second IDR spike (~20x a P frame) |

Considered, not done (risk or no gain at 720p): the VIC reading the game's
buffer through its nvmap handle (would drop the read and the page-table lock,
the likely cause of 50-200 ms hitches, but needs the game's handle imported -
can hang the GPU); pipelining 720p like 1080p (capture is ~5-8 ms of a 16-28
ms frame: no gain); moving the capture off core 3 / raising its priority
(competes with the game, IPC starvation risk); rate control over USB (the
link has room; size is not the latency).

---

The original raw-frame tooling follows.

# raw-recv

Reads raw captured frames from `switch-frame-tap` over USB bulk and writes them
to disk. This is the **option A** transport: no codec, no protocol negotiation.

The sysmodule sends a 32-byte header then the raw Tegra block-linear slot; this
tool writes the payload to a `.bin` that `tools/deswizzle.py` turns into a PNG.

`switch-stream/receiver/` is a different thing — it speaks `sw_hdr_t` and pipes
through libavcodec, so it expects an **encoded** stream. That is option B and
needs NVENC.

## Build

```
cc -O2 -Wall -Wextra -o raw-recv raw-recv.c $(pkg-config --cflags --libs libusb-1.0)
```

## Permissions (one-time, avoids sudo)

```
sudo cp 70-switch-frame-tap.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
```

## Use

Start it before the probe fires — the sysmodule posts a chunk and waits for the
host to drain it. If nothing is listening it times out after 5 s and logs
`completion wait timed out`; the game is already resumed by then, so a mistimed
run costs a cycle and nothing worse.

```
until lsusb -d 1209:5f1e >/dev/null 2>&1; do sleep 1; done
./raw-recv -o /tmp/sft
python3 ../../tools/deswizzle.py /tmp/sft_000.bin /tmp/frame.png
```

Device is `1209:5f1e` (pid.codes open range), interface 0, bulk IN `0x81`.
The header layout must stay byte-identical to `SftHdr` in
`tier4/applet-mitm/source/applet_mitm_nv.cpp`.

## raw-view — live viewer (M60)

libusb + SDL2 in one process. No pipe, no codec: an external player would add
latency, which is the thing this prototype exists to minimise.

```
cc -O2 -o raw-view raw-view.c $(pkg-config --cflags --libs libusb-1.0 sdl2)
./raw-view --scale 2        # window is 2x the stream size
./raw-view --swap           # flip R and B if colours look wrong
```

Start it BEFORE the probe fires. The window opens on the first frame and its
title shows live fps and worst frame gap.

Pixel order: M60 sends the capture's native bytes (R,G,B,A), so the default is
`SDL_PIXELFORMAT_ABGR8888`. `--swap` selects ARGB for a VIC-produced stream,
which transposes R and B.

## raw-view — the H.264 stream (M83, `nvstream`)

Build (needs `libusb-1.0-0-dev libsdl2-dev libavcodec-dev libavutil-dev`):

```
make                      # says "raw-view built WITH H.264 (libavcodec)"
make test                 # replays real NVENC output through raw-view (no console needed)
```

Start it **before** the console's probe fires (the module waits up to 5 s for
a host, then gives up on the stream):

```
until lsusb -d 1209:5f1e >/dev/null 2>&1; do sleep 1; done
./raw-view --record /tmp/run.sft --h264 /tmp/run.h264
```

- Each SFTR packet with `flags & 2` is a complete H.264 access unit: SPS +
  PPS + one IDR slice, 1280x720, BT.709 limited range (the SPS's VUI says so,
  and the viewer sets SDL's YUV conversion to BT.709). `kind` is the
  console's frame number; gaps are counted as lost.
- `--record FILE` keeps every packet (header + payload) for later replay with
  `--file FILE`; `--h264 FILE` keeps the elementary stream, which any player
  opens (`ffplay FILE`).
- Decoding an IDR-only stream at ~150-170 Mbps is heavy. By default the
  viewer uses FFmpeg's frame threads (throughput, a few frames of latency);
  `--low-latency` uses one thread and shows each frame the moment it decodes.
- The last stdout line is machine-readable:
  `packets=N frames=N undecoded=N lost=N`.

The old `raw-view` binary that used to be committed here predates all of
this; build from source.
