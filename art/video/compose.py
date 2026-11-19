"""Assemble the Agern concept video from the 3D render, the UI captures and the overlays.

    python3 art/video/compose.py --frames3d <dir> --ui <dir> --overlays <dir> --out <file.mp4>
        [--poster <file.webp>] [--loop <file.webp>] [--sheet <file.png>] [--draft]

Inputs: scene_anim.py frames (RGBA PNG, transparent background) + timeline.json,
capture_ui.mjs clips (<ui>/<clip>/frames.json + JPEGs), overlays.mjs PNGs; the cut itself
(segment order, UI time remaps, caption times) is art/video/storyboard.json.

3D frames get the same treatment as the stills in art/post.py: composited over the page colour
#0b0e14, bloom on the emissive parts, a soft vignette that reaches exactly #0b0e14 at the border,
light dither. UI clips are shown as a window on the page colour with the caption band below.
Frames are streamed to ffmpeg (libx264, yuv420p, 24 fps). Needs numpy, Pillow, ffmpeg, and
cwebp/img2webp for the poster and the looping preview.
"""
import argparse
import bisect
import json
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1920, 1080
BG = np.array([0x0B, 0x0E, 0x14], np.float32) / 255.0
BG8 = tuple(int(round(c * 255)) for c in BG)

ap = argparse.ArgumentParser()
ap.add_argument("--frames3d", required=True)
ap.add_argument("--ui", required=True)
ap.add_argument("--overlays", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--poster")
ap.add_argument("--loop")
ap.add_argument("--sheet")
ap.add_argument("--draft", action="store_true", help="fast, lower quality encode for checking")
ap.add_argument("--crf", type=int, default=21)
A = ap.parse_args()

SB = json.load(open(os.path.join(HERE, "storyboard.json")))
FPS = SB["fps"]
FADE = SB["fade"]


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def ease(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


# ------------------------------------------------------------------ 3D frames
TL = json.load(open(os.path.join(A.frames3d, "timeline.json")))
_files3d = sorted(int(f[:4]) for f in os.listdir(A.frames3d) if f[:4].isdigit() and f.endswith(".png")
                  and os.path.getsize(os.path.join(A.frames3d, f)) > 0)
if not _files3d:
    sys.exit("no 3D frames in " + A.frames3d)

ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
U, V = xs / (W - 1), ys / (H - 1)
_d = np.sqrt(((U - 0.5) / 0.62) ** 2 + ((V - 0.48) / 0.66) ** 2)
VIGNETTE = 1 - 0.6 * smoothstep(0.55, 1.15, _d)
_e = 0.035
VIGNETTE *= (smoothstep(0, _e, U) * smoothstep(0, _e, 1 - U)
             * smoothstep(0, _e * W / H, V) * smoothstep(0, _e * W / H, 1 - V))
# darker floor under the caption band, so captions read on any background
SCRIM = 1 - 0.55 * smoothstep(0.70, 0.97, V) * (1 - 0.35 * U)
TITLE_SCRIM = 1 - 0.62 * (1 - smoothstep(0.0, 0.62, np.sqrt(((U - 0.5) / 0.55) ** 2 + ((V - 0.5) / 0.5) ** 2)))
RNG = np.random.default_rng(7)
LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)
_cache3d = {}


def frame3d(t):
    f = 1 + int(round(t * FPS))
    i = bisect.bisect_right(_files3d, f) - 1
    f = _files3d[max(i, 0)]
    if f in _cache3d:
        return _cache3d[f]
    im = Image.open(os.path.join(A.frames3d, f"{f:04d}.png")).convert("RGBA")
    if im.size != (W, H):
        im = im.resize((W, H), Image.BICUBIC)
    a = np.asarray(im, np.float32) / 255.0
    img = a[..., :3] * a[..., 3:] + BG * (1 - a[..., 3:])
    # bloom at quarter resolution (same idea as art/post.py)
    lum = img @ LUMA
    bright = img * smoothstep(0.42, 0.95, lum)[..., None]
    small = Image.fromarray((np.clip(bright, 0, 1) * 255).astype(np.uint8)).resize((W // 4, H // 4), Image.BILINEAR)
    glow = np.zeros((H, W, 3), np.float32)
    for r, wgt in ((W * 0.003 / 4, 0.6), (W * 0.010 / 4, 0.5), (W * 0.030 / 4, 0.45)):
        g = small.filter(ImageFilter.GaussianBlur(max(r, 0.6))).resize((W, H), Image.BILINEAR)
        glow += wgt * np.asarray(g, np.float32) / 255.0
    img = 1 - (1 - img) * (1 - np.clip(glow * 0.6, 0, 1))
    img = BG + (img - BG) * (VIGNETTE * SCRIM)[..., None]
    _cache3d.clear()
    _cache3d[f] = img
    return img


# ------------------------------------------------------------------ UI clips
WIN_X, WIN_Y = 160, 22
WIN_W = W - 2 * WIN_X
WIN_H = round(WIN_W * 9 / 16)
RADIUS = 14


def _window_bg():
    """Page-colour backdrop with the window's shadow and border, plus the corner mask."""
    base = Image.new("RGB", (W, H), BG8)
    sh = Image.new("L", (W, H), 0)
    ImageDraw.Draw(sh).rounded_rectangle((WIN_X, WIN_Y + 14, WIN_X + WIN_W, WIN_Y + WIN_H + 14), RADIUS, fill=150)
    sh = sh.filter(ImageFilter.GaussianBlur(26))
    base = Image.composite(Image.new("RGB", (W, H), (2, 3, 6)), base, sh)
    ImageDraw.Draw(base).rounded_rectangle((WIN_X - 1, WIN_Y - 1, WIN_X + WIN_W, WIN_Y + WIN_H), RADIUS + 1,
                                           outline=(0x2D, 0x37, 0x4B), width=2)
    m = Image.new("L", (WIN_W, WIN_H), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, WIN_W - 1, WIN_H - 1), RADIUS, fill=255)
    return np.asarray(base, np.float32) / 255.0, np.asarray(m, np.float32)[..., None] / 255.0


WIN_BG, WIN_MASK = _window_bg()


class Clip:
    def __init__(self, name, remap):
        d = json.load(open(os.path.join(A.ui, name, "frames.json")))
        self.dir = os.path.join(A.ui, name)
        self.files = [f["file"] for f in d["frames"]]
        self.ts = [f["t"] for f in d["frames"]]
        self.remap = remap
        self.duration = sum((b - a) / s for a, b, s in remap)
        self._key, self._img = None, None

    def src_time(self, t):
        for a, b, s in self.remap:
            d = (b - a) / s
            if t <= d:
                return a + t * s
            t -= d
        return self.remap[-1][1]

    def frame(self, t):
        st = self.src_time(t)
        i = max(bisect.bisect_right(self.ts, st) - 1, 0)
        if i != self._key:
            im = Image.open(os.path.join(self.dir, self.files[i])).convert("RGB")
            ui = np.asarray(im.resize((WIN_W, WIN_H), Image.LANCZOS), np.float32) / 255.0
            out = WIN_BG.copy()
            reg = out[WIN_Y:WIN_Y + WIN_H, WIN_X:WIN_X + WIN_W]
            out[WIN_Y:WIN_Y + WIN_H, WIN_X:WIN_X + WIN_W] = ui * WIN_MASK + reg * (1 - WIN_MASK)
            self._key, self._img = i, out
        return self._img


# ------------------------------------------------------------------ overlays
_ov = {}


def overlay(cid):
    if cid not in _ov:
        a = np.asarray(Image.open(os.path.join(A.overlays, f"{cid}.png")).convert("RGBA"), np.float32) / 255.0
        _ov[cid] = (a[..., :3], a[..., 3:])
    return _ov[cid]


# ------------------------------------------------------------------ timeline
segments = []
t = 0.0
END_BG = None
for seg in SB["segments"]:
    if seg["kind"] == "3d":
        dur = seg["end"] - seg["start"]
        src = (lambda s0: (lambda tt: frame3d(s0 + tt)))(seg["start"])
    elif seg["kind"] == "ui":
        clip = Clip(seg["id"], seg["remap"])
        dur = clip.duration
        src = clip.frame
    else:
        dur = seg["duration"]
        src = None
    if segments:
        t -= FADE                       # crossfade with the previous segment
    segments.append(dict(id=seg["id"], kind=seg["kind"], t0=t, dur=dur, src=src))
    t += dur
TOTAL = t

captions = []
seg_by_id = {s["id"]: s for s in segments}
for c in SB["captions"]:
    s = seg_by_id[c["seg"]]
    a, b = c["at"]
    a = a if a >= 0 else s["dur"] + a
    b = b if b > 0 else s["dur"] + b
    captions.append(dict(id=c["id"], t0=s["t0"] + a, t1=s["t0"] + b, kind=c.get("kind", "caption")))


def end_background():
    """The end card sits on a dim, blurred wide shot of the factory."""
    img = frame3d(0.0)
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(6))
    a = np.asarray(pil, np.float32) / 255.0
    return BG + (a - BG) * 0.45


def seg_image(s, tt):
    if s["kind"] == "card":
        global END_BG
        if END_BG is None:
            END_BG = Image.fromarray((np.clip(end_background(), 0, 1) * 255 + 0.5).astype(np.uint8))
        # slow push-in so the card never sits on a frozen frame
        z = 1.0 + 0.05 * tt / s["dur"]
        cw, ch = W / z, H / z
        box = ((W - cw) / 2, (H - ch) / 2 + 0.02 * H * tt / s["dur"], (W + cw) / 2, (H + ch) / 2 + 0.02 * H * tt / s["dur"])
        return np.asarray(END_BG.resize((W, H), Image.BICUBIC, box=box), np.float32) / 255.0
    img = s["src"](tt)
    if s["id"] == "factory":
        # title scrim over the establishing shot
        for c in captions:
            if c["kind"] == "title":
                k = alpha_at(c, s["t0"] + tt, 0.8)
                if k > 0:
                    img = BG + (img - BG) * (1 - k * (1 - TITLE_SCRIM))[..., None]
    return img


def alpha_at(c, tt, fade=0.35):
    if tt < c["t0"] or tt > c["t1"]:
        return 0.0
    return ease(min((tt - c["t0"]) / fade, (c["t1"] - tt) / fade, 1.0))


def render(tt):
    active = [s for s in segments if s["t0"] <= tt < s["t0"] + s["dur"]] or [segments[-1]]
    img = None
    for s in active:
        local = tt - s["t0"]
        im = seg_image(s, min(local, s["dur"] - 1e-3))
        k = ease(local / FADE) if (s is not segments[0] and local < FADE) else 1.0
        img = im if img is None else img * (1 - k) + im * k
    for c in captions:
        fade = 0.8 if c["kind"] in ("title", "end") else 0.35
        k = alpha_at(c, tt, fade)
        if c["kind"] == "end":
            k = ease((tt - c["t0"] - 0.3) / 0.9)
        if k > 0:
            rgb, al = overlay(c["id"])
            img = img * (1 - al * k) + rgb * (al * k)
    # fade in from / out to the page colour
    k = ease(min(tt / 0.6, (TOTAL - tt) / 0.8, 1.0))
    img = BG + (img - BG) * k
    img = img + (RNG.random(img.shape, dtype=np.float32) - 0.5) / 255.0 * 1.2
    return (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)


# ------------------------------------------------------------------ encode
n = int(round(TOTAL * FPS))
print(f"duration {TOTAL:.2f} s, {n} frames", flush=True)
for s in segments:
    print(f"  {s['t0']:6.2f}  {s['id']:<8} {s['dur']:5.2f} s")
for c in captions:
    print(f"  {c['t0']:6.2f}-{c['t1']:6.2f}  caption {c['id']}")
os.makedirs(os.path.dirname(os.path.abspath(A.out)), exist_ok=True)
cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
       "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "veryfast" if A.draft else "slow",
       "-crf", str(A.crf + (6 if A.draft else 0)), "-pix_fmt", "yuv420p", "-profile:v", "high",
       "-movflags", "+faststart", "-an", A.out]
enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
sheet, poster_frame, loop_frames = [], None, []
LOOP = (14.0, 24.0) if A.loop else None
tmp = tempfile.mkdtemp(prefix="agern_loop_")
for i in range(n):
    tt = i / FPS
    fr = render(tt)
    enc.stdin.write(fr.tobytes())
    if A.sheet and i % (2 * FPS) == 0:
        sheet.append((tt, Image.fromarray(fr).resize((480, 270), Image.BILINEAR)))
    if abs(tt - 3.2) < 0.5 / FPS:
        poster_frame = fr.copy()
    if LOOP and LOOP[0] <= tt < LOOP[1] and i % 2 == 0:
        p = os.path.join(tmp, f"{len(loop_frames):04d}.png")
        Image.fromarray(fr).resize((960, 540), Image.LANCZOS).save(p)
        loop_frames.append(p)
    if i % 120 == 0:
        print(f"  frame {i}/{n}", flush=True)
enc.stdin.close()
if enc.wait() != 0:
    sys.exit("ffmpeg failed")
print("wrote", A.out, os.path.getsize(A.out) // 1024, "KB")

if A.poster and poster_frame is not None:
    png = os.path.join(tmp, "poster.png")
    Image.fromarray(poster_frame).save(png)
    subprocess.run(["cwebp", "-quiet", "-q", "88", "-m", "6", "-sharp_yuv", png, "-o", A.poster], check=True)
    print("wrote", A.poster, os.path.getsize(A.poster) // 1024, "KB")
if A.loop and loop_frames:
    # 12 fps animated WebP, loops forever
    args = ["img2webp", "-loop", "0", "-lossy", "-q", "62", "-m", "6", "-d", str(round(2000 / FPS))]
    subprocess.run(args + loop_frames + ["-o", A.loop], check=True, stdout=subprocess.DEVNULL)
    print("wrote", A.loop, os.path.getsize(A.loop) // 1024, "KB")
if A.sheet and sheet:
    cols = 4
    rows = (len(sheet) + cols - 1) // cols
    S = Image.new("RGB", (cols * 480, rows * 290), (0, 0, 0))
    d = ImageDraw.Draw(S)
    for k, (tt, im) in enumerate(sheet):
        x, y = (k % cols) * 480, (k // cols) * 290
        S.paste(im, (x, y))
        d.text((x + 6, y + 272), f"{tt:5.1f}s", fill=(255, 200, 120))
    S.save(A.sheet)
    print("wrote", A.sheet)
