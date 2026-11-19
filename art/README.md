# Art

Procedural sources for the Agern ("the software factory") visual identity.
The 3D scenes are built entirely in Python with Blender's `bpy` API, so every
render is reproducible from these scripts — there are no `.blend` files.

## Outputs (`web/public/art/`)

| File | Size | What |
|---|---|---|
| `hero.webp` | 2400×1200, ~117 KB | Isometric night scene of the factory line: specs arrive as cyan wireframe acorns, a drafting station, a robot arm capping a molten acorn, four parallel review gates, a corner turntable, the approval press and the shipping dock. Left third is kept dark for headline text. |
| `empty.webp` | 800×600, ~19 KB | Idle conveyor with empty carriers, one parked robot arm and a single quietly glowing acorn — for empty states. |
| `logo.svg` | 32×32 viewBox | The mark: an acorn whose cap is a sawtooth factory roof (stem = chimney) with a `>_` prompt cut into the nut. Uses `currentColor`, defaults to amber `#ffb347` via the root `color` attribute — set CSS `color` to recolour when inlined. Mark only; the wordmark is set in text. |
| `favicon.svg` | 32×32 viewBox | Same mark in amber on a `#0b0e14` rounded tile (legible on light tab bars). |

Both WebPs fade to exactly `#0b0e14` (the page background) at every edge;
`post.py` decodes the final WebP and prints the maximum border deviation
(expected `0`).

## Requirements

- Blender 5.2 (tested with 5.2.2, EEVEE, AgX view transform with the "AgX - Punchy" look)
- Python 3 with Pillow and numpy (the macOS system `python3` works)
- `cwebp` on `PATH` (`brew install webp`)

## Re-render

From the repository root:

```sh
BLENDER=/Applications/Blender.app/Contents/MacOS/Blender

# hero (about 15 s on an M1 Max)
$BLENDER -b -P art/scene_hero.py
python3 art/post.py hero

# empty state
$BLENDER -b -P art/scene_empty.py
python3 art/post.py empty
```

Quick look while iterating (half resolution, fewer samples; writes only to `art/build/`):

```sh
$BLENDER -b -P art/scene_hero.py -- --preview
python3 art/post.py hero --preview
```

The SVGs are hand-written and edited directly.

## Files

- `factory_kit.py` — shared kit: palette/materials, primitives (bevelled boxes,
  cylinders, lathe), conveyor, acorn capsule, robot arm, lights, isometric camera.
- `scene_hero.py`, `scene_empty.py` — scene layouts; write RGBA renders with a
  transparent background to `art/build/*_raw.png`.
- `post.py` — composites over `#0b0e14`, adds bloom, fades the edges (and the
  hero's left third) to the page colour, dithers lightly, encodes WebP (q90) and
  verifies the border colour. Per-scene mask/bloom settings live in `CONFIG`.
- `build/` — intermediate renders (git-ignored).

## Concept video (`docs/media/`)

| File | What |
|---|---|
| `agern-concept.mp4` | 1920×1080, 24 fps, H.264 with music, about 67 s. Title, the line animated in 3D (spec → build → four parallel reviews → CHANGES and one fix round → all PASS → approval stamp → merge), then the real UI from the frontend's mock mode (chat, a live pipeline run with its fix round, the agents overview, observing a colleague) and an end card. |
| `agern-concept-poster.webp` | Poster frame (the title over the establishing shot). |
| `agern-concept-loop.webp` | 10 s looping animated WebP, 960 px wide (the review, CHANGES and fix-round part). |

Music: "Technology" by Eliveta, from Pixabay (https://pixabay.com/music/electronic-technology-474054/),
used under the Pixabay Content License. The track itself is not in the repository (the licence does
not allow redistributing it on its own); `render.sh` produces a silent video. To add the music,
download the MP3 and run:

```sh
ffmpeg -i silent.mp4 -i eliveta-technology-474054.mp3 -map 0:v -map 1:a -c:v copy \
  -af "atrim=0:67.625,loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=in:d=0.8,afade=t=out:st=63.6:d=4" \
  -c:a aac -b:a 192k -shortest -movflags +faststart docs/media/agern-concept.mp4
```

Sources in `art/video/`:

- `scene_anim.py` — Blender: runs `scene_hero.py` without its still render, removes the static
  acorns and animates one issue through the line (camera rig, robot-arm IK on the kit's arm,
  gate verdicts, press, door). Writes transparent RGBA frames; resumes if interrupted.
- `capture_ui.mjs` — Playwright drives the mock frontend and records it through the Chrome
  DevTools screencast (JPEG q94 at 1920×1080 device pixels, crisper than `recordVideo`). Hides the
  `mock` flag in the top bar.
- `overlays.mjs` — renders title, captions and end card as transparent PNGs in the UI's fonts.
- `storyboard.json` — the cut: segment order, UI time remaps (the run is shown at 3×), caption
  texts and times.
- `compose.py` — colour/bloom pass on the 3D frames (as `post.py`), UI clips in a window on
  `#0b0e14`, crossfades, captions; encodes with ffmpeg and writes the poster and the loop.
- `render.sh` — runs all of the above.

Requirements on top of the ones above: Node.js with `npm ci` done in a `web/` checkout (Playwright
and the fonts come from its `node_modules`; Chromium via `npx playwright install chromium` or
`CHROMIUM_PATH`), `ffmpeg` with libx264, and `img2webp` (part of `brew install webp`).

Re-render from the repository root (about 45 min for the 3D frames on an M1 Max, a few minutes
for the rest):

```sh
art/video/render.sh --preview     # half-res 3D, every 2nd frame -> art/build/video/preview.mp4
art/video/render.sh               # final -> docs/media/
art/video/render.sh --skip-3d     # re-cut only (captions, timing, UI), reusing rendered frames
```

`render.sh` starts the mock frontend from `WEB_DIR` (default `web/`) on port 5175 unless `UI_URL`
points at one already running; use a checkout of the commit you want to show. Intermediates go to
`art/build/video/` (git-ignored); `compose.py` also writes a contact sheet there
(`sheet.png`, one frame every 2 s) for checking timing.

## Palette

| Role | Hex |
|---|---|
| Page background | `#0b0e14` |
| Molten amber (primary emissive) | `#ffb347` |
| Ember (secondary warm) | `#ff8a3d` |
| Cyan (specs, review gates) | `#4fd6ff` |
