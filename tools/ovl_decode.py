#!/usr/bin/env python3
"""Decode overlay snapshots (Tegra block-linear) to PNGs with their real alpha.

  ovldec.py FILE OUT_PREFIX --w W --h H --bpp 4|2 [--bh 16] [--count N] [--padded-h PH]

bpp 4: R,G,B,A bytes (A8B8G8R8).  bpp 2: libtesla RGBA4444 (r = low nibble of
byte 0, g = high nibble, b = low nibble of byte 1, a = high nibble).
Writes OUT_PREFIX-<n>.png (alpha kept), OUT_PREFIX-<n>-ongray.png (composited
over mid grey) and prints alpha statistics.
"""
import sys, argparse
import numpy as np
from PIL import Image


def bl_index(pitch, padded_h, bh):
    """byte offset in the block-linear surface of every (y, xbyte)."""
    gobs_x = (pitch + 63) // 64
    y = np.arange(padded_h)[:, None]
    x = np.arange(gobs_x * 64)[None, :]
    block_sz = 512 * bh
    off = (y // (8 * bh)) * (gobs_x * block_sz) + (x // 64) * block_sz + ((y % (8 * bh)) // 8) * 512
    xx, yy = x % 64, y % 8
    off = off + (xx // 32) * 256 + (yy // 2) * 64 + ((xx % 32) // 16) * 32 + (yy % 2) * 16 + (xx % 16)
    return off[:, :pitch], gobs_x * 64 * padded_h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file"); ap.add_argument("out")
    ap.add_argument("--w", type=int, required=True); ap.add_argument("--h", type=int, required=True)
    ap.add_argument("--bpp", type=int, default=4); ap.add_argument("--bh", type=int, default=16)
    ap.add_argument("--padded-h", type=int, default=0); ap.add_argument("--count", type=int, default=1)
    ap.add_argument("--linear", action="store_true")
    a = ap.parse_args()
    data = np.fromfile(a.file, dtype=np.uint8)
    pitch = a.w * a.bpp
    ph = a.padded_h or ((a.h + 8 * a.bh - 1) // (8 * a.bh)) * 8 * a.bh
    if a.linear:
        idx = np.arange(ph)[:, None] * pitch + np.arange(pitch)[None, :]
        surf = pitch * ph
    else:
        idx, surf = bl_index(pitch, ph, a.bh)
    for n in range(a.count):
        base = n * surf
        if base + surf > len(data):
            print(f"  surface {n}: past the end ({base + surf} > {len(data)})"); break
        img = data[base:base + surf][idx][:a.h]
        if a.bpp == 4:
            rgba = img.reshape(a.h, a.w, 4).astype(np.uint8)
        else:
            px = img.reshape(a.h, a.w, 2)
            b0, b1 = px[..., 0], px[..., 1]
            r, g, b, al = b0 & 15, b0 >> 4, b1 & 15, b1 >> 4
            rgba = (np.stack([r, g, b, al], -1) * 17).astype(np.uint8)
        al = rgba[..., 3]
        vis = al > 0
        n_vis = int(vis.sum())
        print(f"  surface {n}: alpha 0 {100 * (al == 0).mean():5.1f}%  255 {100 * (al == 255).mean():5.1f}%  partial {100 * ((al > 0) & (al < 255)).mean():5.1f}%")
        if n_vis:
            ys, xs = np.nonzero(vis)
            print(f"     visible box x {xs.min()}-{xs.max()}  y {ys.min()}-{ys.max()}")
            # premultiplied test: in partially transparent pixels, is max(rgb) <= alpha?
            part = (al > 0) & (al < 255)
            if part.any():
                mx = rgba[..., :3].max(-1)
                over = (mx[part].astype(int) > al[part].astype(int) + (17 if a.bpp == 2 else 2)).mean()
                print(f"     partial pixels with RGB above alpha: {100 * over:5.1f}%  (0% = premultiplied)")
        # transparent pixels' colour: zero or not?
        tr = al == 0
        if tr.any():
            print(f"     transparent pixels with non-zero RGB: {100 * (rgba[tr][:, :3].max(-1) > 0).mean():5.1f}%")
        Image.fromarray(rgba, "RGBA").save(f"{a.out}-{n}.png")
        bg = Image.new("RGBA", (a.w, a.h), (96, 96, 96, 255))
        bg.alpha_composite(Image.fromarray(rgba, "RGBA"))
        bg.convert("RGB").save(f"{a.out}-{n}-ongray.png")


if __name__ == "__main__":
    main()
