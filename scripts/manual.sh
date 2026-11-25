#!/usr/bin/env bash
# Build the getting-started manual (docs/manual/) as a PDF, with fresh screenshots of the mock app.
# Used by .github/workflows/{ci,release}.yml and locally, so a local manual is built like a released one.
#
#   scripts/manual.sh [--pdf-only | --shots-only] [version]
#
#   version        the Agern version on the title page; default: scripts/main-version.sh, else 0.0.0-dev.
#                  A leading 'v' is stripped; it must be a semantic version.
#   --pdf-only     reuse the screenshots already in build/manual/screenshots/ (no browser, no dev server)
#   --shots-only   only take the screenshots and check the manual's references to them (no TeX needed)
#   -h, --help     this text
#
# Output in build/manual/ (git-ignored):
#   screenshots/                  the tour's PNGs (web/scripts/screenshots.mjs against `npm run dev:mock`)
#   tex/                          latexmk's auxiliary files and log
#   agern-manual-<version>.pdf    the manual
#
# Needs Node.js with web/node_modules (installed with 'npm ci' when missing) and Playwright's Chromium
# (cd web && npx playwright install chromium), or CHROMIUM_PATH=<chromium binary>; for the PDF TeX Live
# with latexmk and pdflatex (Debian/Ubuntu: see README.md, "Manual").
#
# Checks, each failing the build: a screenshot that the tour could not take, a screenshot the manual
# includes that was not produced, a screenshot label (\screenshot{…}{…}{label}) that the text never
# references (\ref{fig:label}), a LaTeX error, and undefined references.

set -euo pipefail
export LC_ALL=C # sort and comm must agree on the order

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
die() { printf 'manual: error: %s\n' "$*" >&2; exit 1; }
info() { printf 'manual: %s\n' "$*"; }
usage() { sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; }

SHOTS=yes
PDF=yes
VERSION=""
for arg in "$@"; do
    case $arg in
        --pdf-only) SHOTS=no ;;
        --shots-only) PDF=no ;;
        -h | --help) usage; exit 0 ;;
        -*) die "unknown option $arg" ;;
        *) [ -z "$VERSION" ] || die "only one version, please"; VERSION=${arg#v} ;;
    esac
done
[ "$SHOTS" = yes ] || [ "$PDF" = yes ] || die "--pdf-only and --shots-only exclude each other"

ROOT=$(cd -P "$(dirname "$0")/.." && pwd -P)
SRC="$ROOT/docs/manual"
OUT="$ROOT/build/manual"
SHOT_DIR="$OUT/screenshots"
TEX_DIR="$OUT/tex"
cd "$ROOT"

if [ -z "$VERSION" ]; then
    if VERSION=$("$ROOT/scripts/main-version.sh" 2>/dev/null); then
        info "version $VERSION (scripts/main-version.sh)"
    else
        VERSION=0.0.0-dev
        info "note: scripts/main-version.sh found no version (no git checkout?); using $VERSION"
    fi
fi
# Validated before it is handed to TeX: nothing but a semantic version ever reaches the PDF.
[[ $VERSION =~ $SEMVER_RE ]] || die "not a semantic version: $VERSION"

# --- tools, checked before any long work ----------------------------------------------------
if [ "$PDF" = yes ]; then
    for tool in latexmk pdflatex makeindex; do
        command -v "$tool" >/dev/null 2>&1 || die "$tool not found: install TeX Live with latexmk (Debian/Ubuntu: sudo apt-get install latexmk texlive-latex-base texlive-latex-recommended texlive-latex-extra texlive-fonts-recommended texlive-pictures lmodern), or use --shots-only"
    done
fi
if [ "$SHOTS" = yes ]; then
    command -v node >/dev/null 2>&1 || die "node not found: install Node.js (24, as CI uses)"
    command -v npm >/dev/null 2>&1 || die "npm not found: install Node.js (24, as CI uses)"
    if [ ! -d web/node_modules ]; then
        info "installing the frontend's dependencies (npm ci)"
        (cd web && npm ci)
    fi
    # Launching it is the only reliable test (a headless-shell install has no full Chromium).
    if ! (cd web && node -e '
        const { chromium } = require("playwright");
        const p = process.env.CHROMIUM_PATH;
        chromium.launch(p ? { executablePath: p } : {}).then((b) => b.close(), (e) => { console.error(e.message.split("\n")[0]); process.exit(1); });
    ' >/dev/null 2>&1); then
        die "Playwright cannot start Chromium: install it (cd web && npx playwright install chromium; on a bare Linux add --with-deps) or set CHROMIUM_PATH to a Chromium binary"
    fi
fi

# --- screenshot references in the manual ----------------------------------------------------
# Every UI image goes through \screenshot or \phoneshot, one call per line, label last:
#   \screenshot[<width>]{<file without .png>}{<caption>}{<label>}
SHOT_LINES=$(grep -rhE '^[[:space:]]*\\(screenshot|phoneshot)\b' "$SRC" --include='*.tex' || true)
[ -n "$SHOT_LINES" ] || die "no \\screenshot in $SRC"
bad=$(printf '%s\n' "$SHOT_LINES" | grep -vE '^[[:space:]]*\\(screenshot|phoneshot)(\[[^]]*\])?\{[^}]+\}.*\}\{[A-Za-z0-9:_-]+\}[[:space:]]*(%.*)?$' || true)
[ -z "$bad" ] || die "write each \\screenshot on one line, ending with its label:
$bad"
FIGURES=$(printf '%s\n' "$SHOT_LINES" | sed -E 's/^[[:space:]]*\\(screenshot|phoneshot)(\[[^]]*\])?\{([^}]+)\}.*/\3/' | sort -u)
# (A trailing comment is dropped; an escaped \% in a caption is not a comment.)
LABELS=$(printf '%s\n' "$SHOT_LINES" | sed -E 's/([^\\])%.*$/\1/; s/[[:space:]]+$//; s/.*\}\{([A-Za-z0-9:_-]+)\}$/\1/' | sort)
dups=$(printf '%s\n' "$LABELS" | uniq -d)
[ -z "$dups" ] || die "screenshot labels used twice: $(printf '%s' "$dups" | tr '\n' ' ')"
REFS=$(grep -rhoE '\\(ref|autoref|cref|Cref|pageref|nameref)\{fig:[A-Za-z0-9:_-]+\}' "$SRC" --include='*.tex' |
    sed -E 's/.*\{fig:([^}]+)\}/\1/' | sort -u || true)
unreferenced=$(comm -23 <(printf '%s\n' "$LABELS" | sort -u) <(printf '%s\n' "$REFS"))
[ -z "$unreferenced" ] || die "screenshot labels never referenced in the text (add \\ref{fig:…}): $(printf '%s' "$unreferenced" | tr '\n' ' ')"

check_figures() {
    local missing="" f
    for f in $FIGURES; do
        [ -f "$SHOT_DIR/$f.png" ] || missing="$missing $f"
    done
    [ -f "$SHOT_DIR/logo.png" ] || missing="$missing logo"
    [ -z "$missing" ] || die "screenshots the manual includes but $SHOT_DIR lacks:$missing"
}

# --- screenshots -----------------------------------------------------------------------------
if [ "$SHOTS" = yes ]; then
    PORT=$(node -e 'const s = require("net").createServer(); s.listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close(); });')
    [[ $PORT =~ ^[0-9]+$ ]] && [ "$PORT" != 8080 ] || die "could not find a free port"
    BASE="http://127.0.0.1:$PORT"
    LOG="$OUT/dev-server.log"
    rm -rf "$SHOT_DIR"
    mkdir -p "$SHOT_DIR"
    info "starting the mock app on $BASE"
    # What 'npm run dev:mock' runs, started directly so that the trap stops exactly this process.
    (cd web && VITE_MOCK=1 exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port "$PORT" --strictPort) >"$LOG" 2>&1 &
    DEV_PID=$!
    trap 'kill "$DEV_PID" 2>/dev/null || true; wait "$DEV_PID" 2>/dev/null || true' EXIT
    up=no
    for _ in $(seq 60); do
        if node -e 'fetch(process.argv[1]).then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))' "$BASE/" 2>/dev/null; then up=yes; break; fi
        kill -0 "$DEV_PID" 2>/dev/null || break
        sleep 1
    done
    [ "$up" = yes ] || die "the mock app did not answer on $BASE within 60 s (see $LOG)"
    info "taking the screenshots (a few minutes)"
    (cd web && node scripts/screenshots.mjs "$SHOT_DIR" "$BASE") || die "the screenshot tour failed (see above; *-error.png in $SHOT_DIR)"
    kill "$DEV_PID" 2>/dev/null || true
    wait "$DEV_PID" 2>/dev/null || true
    trap - EXIT
fi
check_figures
info "$(printf '%s\n' "$FIGURES" | wc -l | tr -d ' ') screenshots referenced and present"
[ "$PDF" = yes ] || { info "done (--shots-only): $SHOT_DIR"; exit 0; }

# --- PDF -------------------------------------------------------------------------------------
# \include writes chapters/<name>.aux into the output directory.
mkdir -p "$TEX_DIR/chapters"
info "building the PDF (version $VERSION)"
# The version is defined before the document is read; manual.tex falls back to "development build".
(cd "$SRC" && latexmk -pdf -halt-on-error -interaction=nonstopmode -file-line-error \
    -outdir="$TEX_DIR" -usepretex="\\def\\agernversion{$VERSION}" manual.tex >"$TEX_DIR/latexmk.out" 2>&1) || {
    grep -E '^(.*:[0-9]+: |! )' -A 3 "$TEX_DIR/manual.log" 2>/dev/null | head -n 40 >&2 || tail -n 40 "$TEX_DIR/latexmk.out" >&2
    die "LaTeX failed (log: $TEX_DIR/manual.log)"
}
undefined=$(grep -E "(Reference|Citation) .* undefined|There were undefined references|Label\(s\) may have changed" "$TEX_DIR/manual.log" || true)
[ -z "$undefined" ] || die "undefined references in the manual:
$undefined"
PDF_FILE="$OUT/agern-manual-$VERSION.pdf"
cp "$TEX_DIR/manual.pdf" "$PDF_FILE"
size=$(du -k "$PDF_FILE" | cut -f1)
[ "$size" -le 40960 ] || die "$PDF_FILE is $((size / 1024)) MB, more than the 40 MB limit"
info "done: $PDF_FILE ($((size / 1024)) MB, ${size} KB)"
