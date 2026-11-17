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

## Palette

| Role | Hex |
|---|---|
| Page background | `#0b0e14` |
| Molten amber (primary emissive) | `#ffb347` |
| Ember (secondary warm) | `#ff8a3d` |
| Cyan (specs, review gates) | `#4fd6ff` |
