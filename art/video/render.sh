#!/usr/bin/env bash
# Re-render the Agern concept video (docs/media/agern-concept.mp4, poster, looping preview).
#
#   art/video/render.sh [--preview] [--skip-3d] [--skip-ui]
#
# Environment:
#   WORK           scratch directory for intermediates   (default: art/build/video)
#   WEB_DIR        a web/ checkout with node_modules, used for Playwright, the fonts and the mock
#                  frontend                              (default: web)
#   UI_URL         a running mock frontend; if unset, one is started from WEB_DIR on port 5175
#   BLENDER        Blender 5.2 binary                    (default: the macOS app bundle)
#   CHROMIUM_PATH  Chromium / chrome-headless-shell for Playwright (optional)
#
# --preview renders the 3D part at half resolution, 12 samples, every 2nd frame (about 10 min on
# an M1 Max) and writes art/build/video/preview.mp4 plus a contact sheet instead of docs/media.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${WORK:-$ROOT/art/build/video}"
WEB_DIR="${WEB_DIR:-$ROOT/web}"
BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"
PREVIEW=0 SKIP3D=0 SKIPUI=0
for a in "$@"; do
  case "$a" in
    --preview) PREVIEW=1 ;;
    --skip-3d) SKIP3D=1 ;;
    --skip-ui) SKIPUI=1 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done
mkdir -p "$WORK"
export WEB_DIR

# 1. 3D sequence (resumable: existing frames are kept)
if [ "$SKIP3D" = 0 ]; then
  if [ "$PREVIEW" = 1 ]; then
    "$BLENDER" -b -P "$ROOT/art/video/scene_anim.py" -- --out "$WORK/3d_preview" --preview --step 2
  else
    "$BLENDER" -b -P "$ROOT/art/video/scene_anim.py" -- --out "$WORK/3d" --samples 16
  fi
fi

# 2. UI captures from the mock frontend, and 3. the text overlays
VITE_PID=""
if [ -z "${UI_URL:-}" ] && [ "$SKIPUI" = 0 ]; then
  (cd "$WEB_DIR" && VITE_MOCK=1 npx vite --port 5175 --strictPort >"$WORK/vite.log" 2>&1) &
  VITE_PID=$!
  trap '[ -n "$VITE_PID" ] && pkill -P "$VITE_PID" 2>/dev/null; true' EXIT
  until curl -sf http://localhost:5175/ >/dev/null; do sleep 1; done
fi
if [ "$SKIPUI" = 0 ]; then
  rm -rf "$WORK/ui"
  node "$ROOT/art/video/capture_ui.mjs" "$WORK/ui" "${UI_URL:-http://localhost:5175}"
fi
node "$ROOT/art/video/overlays.mjs" "$WORK/overlays"

# 4. compose and encode
if [ "$PREVIEW" = 1 ]; then
  python3 "$ROOT/art/video/compose.py" --frames3d "$WORK/3d_preview" --ui "$WORK/ui" \
    --overlays "$WORK/overlays" --out "$WORK/preview.mp4" --sheet "$WORK/preview_sheet.png" --draft
else
  python3 "$ROOT/art/video/compose.py" --frames3d "$WORK/3d" --ui "$WORK/ui" \
    --overlays "$WORK/overlays" --out "$ROOT/docs/media/agern-concept.mp4" \
    --poster "$ROOT/docs/media/agern-concept-poster.webp" --loop "$ROOT/docs/media/agern-concept-loop.webp" \
    --sheet "$WORK/sheet.png"
fi
