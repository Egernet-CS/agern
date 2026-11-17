#!/usr/bin/env bash
# Build agern.jar and assemble the release files. Used by .github/workflows/release.yml and
# locally, so a local package is built exactly like a released one.
#
#   scripts/package.sh <version>
#
# Output in build/release/:
#   agern-<version>.tar.gz   agern-<version>/{agern.jar, agern, install.sh, VERSION, .env.example,
#                            service/agern.service, service/dk.egernet.agern.plist}
#   install.sh               the installer on its own (for the one-line bootstrap)
#   SHA256SUMS               checksums of the two files above
#
# Environment:
#   AGERN_WEB_DIST=<dir>     use an already built frontend instead of running npm ci + build in web/
#   AGERN_SKIP_JAR=1         reuse server/build/libs/agern.jar (must have been built with this version)
#
# Tests are not run here; CI runs them before calling this script.

set -euo pipefail

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
die() { printf 'package: error: %s\n' "$*" >&2; exit 1; }
info() { printf 'package: %s\n' "$*"; }

[ $# -eq 1 ] || die "usage: scripts/package.sh <version>"
VERSION=${1#v}
[[ $VERSION =~ $SEMVER_RE ]] || die "not a semantic version: $1"

ROOT=$(cd -P "$(dirname "$0")/.." && pwd -P)
OUT="$ROOT/build/release"
cd "$ROOT"

# --- frontend --------------------------------------------------------------------------------
if [ -n "${AGERN_WEB_DIST:-}" ]; then
    WEB_DIST=$(cd -P "$AGERN_WEB_DIST" && pwd -P)
else
    info "building the frontend"
    (cd web && npm ci && npm run build)
    WEB_DIST="$ROOT/web/dist"
fi
[ -f "$WEB_DIST/index.html" ] || die "$WEB_DIST has no index.html"

# --- server ----------------------------------------------------------------------------------
JAR="$ROOT/server/build/libs/agern.jar"
if [ "${AGERN_SKIP_JAR:-}" != 1 ]; then
    info "building agern.jar $VERSION"
    # -PwebDist is resolved relative to server/; an absolute path works too.
    ./gradlew --no-daemon :server:shadowJar -Pversion="$VERSION" -PwebDist="$WEB_DIST"
fi
[ -f "$JAR" ] || die "$JAR was not built"
jar_version=$(unzip -p "$JAR" agern-version.txt | tr -d '[:space:]')
[ "$jar_version" = "$VERSION" ] || die "agern.jar reports version '$jar_version', expected '$VERSION'"
unzip -l "$JAR" 'web/index.html' >/dev/null || die "agern.jar has no embedded frontend (web/index.html)"

# --- assemble --------------------------------------------------------------------------------
NAME="agern-$VERSION"
rm -rf "$OUT"
mkdir -p "$OUT/$NAME/service"
cp "$JAR" "$OUT/$NAME/agern.jar"
cp packaging/agern packaging/install.sh "$OUT/$NAME/"
cp packaging/service/agern.service packaging/service/dk.egernet.agern.plist "$OUT/$NAME/service/"
cp .env.example "$OUT/$NAME/.env.example"
printf '%s\n' "$VERSION" >"$OUT/$NAME/VERSION"
chmod 0755 "$OUT/$NAME/agern" "$OUT/$NAME/install.sh"
chmod 0644 "$OUT/$NAME/agern.jar" "$OUT/$NAME/VERSION" "$OUT/$NAME/.env.example" "$OUT/$NAME"/service/*

# Entries owned by root (no build-machine user names); COPYFILE_DISABLE keeps macOS tar from
# adding ._ metadata files.
if tar --version 2>/dev/null | grep -q 'GNU tar'; then
    owner=(--owner=0 --group=0 --numeric-owner)
else
    owner=(--uid 0 --gid 0 --uname root --gname root)
fi
(cd "$OUT" && COPYFILE_DISABLE=1 tar "${owner[@]}" -czf "$NAME.tar.gz" "$NAME")
cp packaging/install.sh "$OUT/install.sh"
chmod 0755 "$OUT/install.sh"
rm -rf "${OUT:?}/$NAME"

(
    cd "$OUT"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$NAME.tar.gz" install.sh >SHA256SUMS
    else
        shasum -a 256 "$NAME.tar.gz" install.sh >SHA256SUMS
    fi
)
info "done:"
(cd "$OUT" && ls -l && cat SHA256SUMS)
