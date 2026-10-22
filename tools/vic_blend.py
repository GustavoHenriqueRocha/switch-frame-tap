#!/usr/bin/env python3
"""Read the VIC blend card (probe/vic-blend.bin, v0.7.14 ovl2) and say which
blend variant composites an overlay correctly.

The module ran one VIC job per variant: slot 0 a 64x32 background card, slot 1
an overlay card of eight 4-row bands with known alphas, output RGBA (pitch).
For each variant this prints, per band, the worst channel error against two
models of "overlay over background":

  premultiplied:  out = ov.rgb + bg.rgb * (1 - ov.a)     (what the overlays are)
  straight:       out = ov.rgb * ov.a + bg.rgb * (1 - ov.a)

and writes vic-blend.png (every variant's output, scaled up, one per row).

Usage:  python3 tools/vic_blend.py vic-blend.bin [out.png]
"""
import struct, sys

HDR = struct.Struct("<8I")
REC = struct.Struct("<16s6I8B")
BANDS = ["transparent", "white", "50% white", "25% red", "50% black", "rgb, a=0", "opaque black", "75% orange"]


def load(path):
    d = open(path, "rb").read()
    magic, ver, count, w, h = HDR.unpack_from(d, 0)[:5]
    if magic != 0x434C4256:
        sys.exit(f"{path}: not a blend card (magic {magic:#x})")
    off = HDR.size
    n = w * h * 4
    bg, ov = d[off:off + n], d[off + n:off + 2 * n]
    off += 2 * n
    variants = []
    for _ in range(count):
        if off + REC.size + n > len(d):
            break
        f = REC.unpack_from(d, off)
        off += REC.size
        name = f[0].split(b"\0")[0].decode()
        rec = dict(name=name, completed=f[1], enable=f[2], constant=f[3], slot0=f[4], scaled=f[5], bl=f[6],
                   k1=f[7] | (f[8] << 8), src_c=f[9], dst_c=f[10], src_a=f[11], dst_a=f[12])
        variants.append((rec, d[off:off + n]))
        off += n
    return w, h, bg, ov, variants


def px(buf, w, x, y):
    i = (y * w + x) * 4
    return buf[i:i + 4]


def expect(bg, ov, model):
    a = ov[3] / 255.0
    if model == "premul":
        return [min(255, round(ov[c] + bg[c] * (1 - a))) for c in range(3)]
    return [min(255, round(ov[c] * a + bg[c] * (1 - a))) for c in range(3)]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    w, h, bg, ov, variants = load(sys.argv[1])
    print(f"blend card {w}x{h}, {len(variants)} variants")
    for rec, out in variants:
        flags = [k for k in ("constant", "slot0", "scaled", "bl") if rec[k]]
        print(f"\n[{rec['name']}] {'done' if rec['completed'] else 'DID NOT COMPLETE'}  slot1={'on' if rec['enable'] else 'off'}"
              f"  K1={rec['k1']} src_c={rec['src_c']} dst_c={rec['dst_c']} src_a={rec['src_a']} dst_a={rec['dst_a']} {' '.join(flags)}")
        if not rec["completed"]:
            continue
        if all(b == 0xAB for b in out[:64]):
            print("   output untouched (poison 0xAB)")
            continue
        worst = {"premul": 0, "straight": 0, "bg": 0}
        for band in range(8):
            y = band * 4 + 1
            errs = {"premul": 0, "straight": 0, "bg": 0}
            sample = None
            for x in range(4, w - 4, 4):
                o = px(out, w, x, y)
                b = px(bg, w, x, y)
                if rec["scaled"]:
                    # 32x16 of the overlay -> 48x24: the band at output row y is overlay row y/1.5
                    if x >= 48 or y >= 24:
                        v = px(ov, w, 0, 0)  # outside: no overlay
                        v = bytes([0, 0, 0, 0])
                    else:
                        v = px(ov, w, int(x / 1.5), int(y / 1.5))
                else:
                    v = px(ov, w, x, y)
                if not rec["enable"]:
                    v = bytes([0, 0, 0, 0])
                for m in ("premul", "straight"):
                    e = expect(b, v, m)
                    errs[m] = max(errs[m], max(abs(o[c] - e[c]) for c in range(3)))
                errs["bg"] = max(errs["bg"], max(abs(o[c] - b[c]) for c in range(3)))
                if x == 32:
                    sample = (bytes(o), bytes(b), bytes(v))
            for k in worst:
                worst[k] = max(worst[k], errs[k])
            o, b, v = sample
            print(f"   band {band} {BANDS[band]:12s} out {o.hex()} bg {b.hex()} ov {v.hex()}   err premul {errs['premul']:3d}  straight {errs['straight']:3d}  bg-only {errs['bg']:3d}")
        verdict = min(worst, key=worst.get)
        print(f"   worst error: premultiplied {worst['premul']}, straight {worst['straight']}, background only {worst['bg']} -> closest: {verdict}")

    if len(sys.argv) > 2 or True:
        try:
            from PIL import Image
        except ImportError:
            return
        scale = 4
        rows = [bg, ov] + [out for _, out in variants]
        img = Image.new("RGB", (w * scale, h * scale * len(rows)), (40, 40, 40))
        for r, buf in enumerate(rows):
            tile = Image.frombytes("RGBA", (w, h), bytes(buf)).convert("RGB").resize((w * scale, h * scale), Image.NEAREST)
            img.paste(tile, (0, r * h * scale))
        out_path = sys.argv[2] if len(sys.argv) > 2 else "vic-blend.png"
        img.save(out_path)
        print(f"\nwrote {out_path} (rows: background, overlay, then each variant's output)")


if __name__ == "__main__":
    main()
