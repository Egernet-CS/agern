"""Post-process a raw Blender render into the final web asset.

    python3 art/post.py hero        # art/build/hero_raw.png  -> web/public/art/hero.webp
    python3 art/post.py empty       # art/build/empty_raw.png -> web/public/art/empty.webp
    python3 art/post.py hero --preview   (uses *_raw_preview.png, writes art/build only)

Steps: composite over the page colour #0b0e14, add bloom around emissive parts,
fade everything to exactly #0b0e14 towards the edges (and the calm left third of
the hero), add a touch of dither against banding, encode WebP with cwebp.
Needs Python 3 with Pillow + numpy, and `cwebp` on PATH.
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BG = np.array([0x0B, 0x0E, 0x14], dtype=np.float32) / 255.0

CONFIG = {
    # mask: elliptical falloff centred at (cx, cy) in 0..1 image coords with radii (rx, ry);
    # left: (x0, x1) ramp that darkens the left part of the frame for headline text.
    "hero": dict(cx=0.60, cy=0.50, rx=0.56, ry=0.62, left=(0.08, 0.40), left_floor=0.22,
                 edge=0.05, bloom=0.55, quality=90, out="hero.webp"),
    "empty": dict(cx=0.5, cy=0.52, rx=0.58, ry=0.62, left=None, left_floor=1.0,
                  edge=0.06, bloom=0.5, quality=90, out="empty.webp"),
}


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def main():
    name = sys.argv[1]
    preview = "--preview" in sys.argv
    cfg = CONFIG[name]
    src = os.path.join(HERE, "build", f"{name}_raw{'_preview' if preview else ''}.png")
    im = Image.open(src).convert("RGBA")
    w, h = im.size
    a = np.asarray(im, dtype=np.float32) / 255.0
    rgb, alpha = a[..., :3], a[..., 3:4]
    img = rgb * alpha + BG * (1 - alpha)          # premultiplied-style over

    # bloom: threshold bright pixels, blur at a few radii, add back
    lum = img @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    bright = img * smoothstep(0.45, 0.95, lum)[..., None]
    b8 = Image.fromarray((np.clip(bright, 0, 1) * 255).astype(np.uint8))
    glow = np.zeros_like(img)
    for r, wgt in ((w * 0.003, 0.6), (w * 0.010, 0.5), (w * 0.030, 0.45)):
        glow += wgt * np.asarray(b8.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255.0
    img = 1 - (1 - img) * (1 - np.clip(glow * cfg["bloom"], 0, 1))   # screen blend

    # fade mask -> exactly BG at the borders
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    u, v = xs / (w - 1), ys / (h - 1)
    d = np.sqrt(((u - cfg["cx"]) / cfg["rx"]) ** 2 + ((v - cfg["cy"]) / cfg["ry"]) ** 2)
    mask = 1 - smoothstep(0.55, 1.0, d)
    e = cfg["edge"]
    border = (smoothstep(0, e, u) * smoothstep(0, e, 1 - u)
              * smoothstep(0, e * w / h, v) * smoothstep(0, e * w / h, 1 - v))
    mask *= border
    if cfg["left"]:
        x0, x1 = cfg["left"]
        lf = cfg["left_floor"]
        mask *= lf + (1 - lf) * smoothstep(x0, x1, u)
    img = BG + (img - BG) * mask[..., None]

    # gentle dither (only where there is content) against banding in dark gradients
    rng = np.random.default_rng(7)
    img += (rng.random(img.shape, dtype=np.float32) - 0.5) / 255.0 * 1.5 * mask[..., None]
    out8 = (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)
    out8[mask < 1e-3] = (BG * 255 + 0.5).astype(np.uint8)

    png = os.path.join(HERE, "build", f"{name}{'_preview' if preview else ''}.png")
    Image.fromarray(out8).save(png)
    print("wrote", png, out8.shape)
    if preview:
        return
    dst_dir = os.path.join(ROOT, "web", "public", "art")
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, cfg["out"])
    subprocess.run(["cwebp", "-quiet", "-q", str(cfg["quality"]), "-sns", "0", "-m", "6",
                    "-sharp_yuv", "-af", png, "-o", dst], check=True)
    # verify: decoded border must be exactly the page colour
    dec = np.asarray(Image.open(dst).convert("RGB")).astype(int)
    band = np.concatenate([dec[:4].reshape(-1, 3), dec[-4:].reshape(-1, 3),
                           dec[:, :4].reshape(-1, 3), dec[:, -4:].reshape(-1, 3)])
    dev = int(np.abs(band - np.array([0x0B, 0x0E, 0x14])).max())
    print("wrote", dst, f"{dec.shape[1]}x{dec.shape[0]}", os.path.getsize(dst) // 1024, "KB",
          "border max deviation from #0b0e14:", dev)


if __name__ == "__main__":
    main()
