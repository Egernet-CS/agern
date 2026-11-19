#!/usr/bin/env bash
# Build agern.jar and assemble the release files. Used by .github/workflows/{ci,release}.yml and
# locally, so a local package is built exactly like a released one.
#
#   scripts/package.sh [--deb | --no-deb] <version>
#
# Output in build/release/:
#   agern-<version>.tar.gz   agern-<version>/{agern.jar, agern, install.sh, VERSION, .env.example,
#                            service/agern.service, service/dk.egernet.agern.plist}
#   install.sh               the installer on its own (for the one-line bootstrap)
#   agern_<version>_all.deb  the Debian/Ubuntu package (see below)
#   SHA256SUMS               checksums of the files above
#
# The .deb is built with 'dpkg-deb' (package 'dpkg' on Debian/Ubuntu, 'brew install dpkg' on
# macOS). Without a flag it is built when dpkg-deb is available and skipped with a note
# otherwise; --deb makes it required (CI), --no-deb skips it. Its file name carries the semantic
# version; the package's own (dpkg) version replaces the '-' that starts a pre-release with '~',
# so pre-releases sort before the release: 1.3.0-rc.1 -> 1.3.0~rc.1 < 1.3.0.
#
# Environment:
#   AGERN_WEB_DIST=<dir>     use an already built frontend instead of running npm ci + build in web/
#   AGERN_SKIP_JAR=1         reuse server/build/libs/agern.jar (must have been built with this
#                            version; it carries the frontend, so no frontend is built either)
#
# Tests are not run here; CI runs them before calling this script.

set -euo pipefail

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
die() { printf 'package: error: %s\n' "$*" >&2; exit 1; }
info() { printf 'package: %s\n' "$*"; }

DEB=auto
VERSION=""
for arg in "$@"; do
    case $arg in
        --deb) DEB=yes ;;
        --no-deb) DEB=no ;;
        -*) die "unknown option $arg" ;;
        *) [ -z "$VERSION" ] || die "only one version, please"; VERSION=${arg#v} ;;
    esac
done
[ -n "$VERSION" ] || die "usage: scripts/package.sh [--deb | --no-deb] <version>"
[[ $VERSION =~ $SEMVER_RE ]] || die "not a semantic version: $VERSION"

if [ "$DEB" != no ] && ! command -v dpkg-deb >/dev/null 2>&1; then
    if [ "$DEB" = yes ]; then
        die "dpkg-deb not found: install it (Debian/Ubuntu: apt install dpkg; macOS: brew install dpkg) or build inside a Debian container, e.g.
  docker run --rm -v \"\$PWD\":/src -w /src debian:12 bash -c 'apt-get update && apt-get install -y unzip && AGERN_SKIP_JAR=1 scripts/package.sh --deb $VERSION'"
    fi
    info "note: dpkg-deb not found, skipping the .deb (--deb makes it required)"
    DEB=no
fi

ROOT=$(cd -P "$(dirname "$0")/.." && pwd -P)
OUT="$ROOT/build/release"
cd "$ROOT"

# --- server (with the frontend embedded) -------------------------------------------------------
JAR="$ROOT/server/build/libs/agern.jar"
if [ "${AGERN_SKIP_JAR:-}" != 1 ]; then
    if [ -n "${AGERN_WEB_DIST:-}" ]; then
        WEB_DIST=$(cd -P "$AGERN_WEB_DIST" && pwd -P)
    else
        info "building the frontend"
        (cd web && npm ci && npm run build)
        WEB_DIST="$ROOT/web/dist"
    fi
    [ -f "$WEB_DIST/index.html" ] || die "$WEB_DIST has no index.html"
    info "building agern.jar $VERSION"
    # -PwebDist is resolved relative to server/; an absolute path works too.
    ./gradlew --no-daemon :server:shadowJar -Pversion="$VERSION" -PwebDist="$WEB_DIST"
fi
[ -f "$JAR" ] || die "$JAR was not built"
jar_version=$(unzip -p "$JAR" agern-version.txt | tr -d '[:space:]')
[ "$jar_version" = "$VERSION" ] || die "agern.jar reports version '$jar_version', expected '$VERSION'"
unzip -l "$JAR" 'web/index.html' >/dev/null || die "agern.jar has no embedded frontend (web/index.html)"

# --- tarball ---------------------------------------------------------------------------------
umask 022
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
FILES=("$NAME.tar.gz" install.sh)

# --- .deb ------------------------------------------------------------------------------------
# semver -> dpkg version (same rule as semver_to_deb in packaging/agern).
deb_version() {
    local core=${1%%+*} build=""
    [ "$core" != "$1" ] && build="+${1#*+}"
    case $core in *-*) core="${core%%-*}~${core#*-}" ;; esac
    printf '%s%s\n' "$core" "$build"
}

if [ "$DEB" != no ]; then
    DEB_VERSION=$(deb_version "$VERSION")
    DEB_FILE="agern_${VERSION}_all.deb"
    STAGE="$OUT/deb-root"
    rm -rf "$STAGE"
    mkdir -p "$STAGE/DEBIAN" "$STAGE/usr/bin" "$STAGE/usr/share/agern" "$STAGE/usr/share/doc/agern" \
        "$STAGE/usr/lib/systemd/system"
    cp packaging/agern "$STAGE/usr/bin/agern"
    cp "$JAR" "$STAGE/usr/share/agern/agern.jar"
    cp .env.example "$STAGE/usr/share/agern/env.example"
    printf '%s\n' "$VERSION" >"$STAGE/usr/share/agern/VERSION"
    cp packaging/deb/README.Debian "$STAGE/usr/share/doc/agern/README.Debian"
    cp packaging/deb/agern.service packaging/deb/agern-upgrade.service packaging/deb/agern-upgrade.timer \
        "$STAGE/usr/lib/systemd/system/"
    cp packaging/deb/postinst packaging/deb/prerm packaging/deb/postrm "$STAGE/DEBIAN/"
    find "$STAGE" -type d -exec chmod 0755 {} +
    find "$STAGE" -type f -exec chmod 0644 {} +
    chmod 0755 "$STAGE/usr/bin/agern" "$STAGE/DEBIAN/postinst" "$STAGE/DEBIAN/prerm" "$STAGE/DEBIAN/postrm"
    # DEBIAN/md5sums: lets 'dpkg --verify agern' (and 'agern upgrade', before it rebuilds a copy of the
    # installed package for rollback) detect changed files.
    (
        cd "$STAGE"
        find usr -type f | LC_ALL=C sort | while IFS= read -r f; do
            if command -v md5sum >/dev/null 2>&1; then sum=$(md5sum "$f" | cut -d' ' -f1); else sum=$(md5 -q "$f"); fi
            printf '%s  %s\n' "$sum" "$f"
        done
    ) >"$STAGE/DEBIAN/md5sums"
    chmod 0644 "$STAGE/DEBIAN/md5sums"
    size=$(du -sk "$STAGE/usr" | awk '{print $1}')
    sed -e "s|@VERSION@|$DEB_VERSION|" -e "s|@INSTALLED_SIZE@|$size|" packaging/deb/control >"$STAGE/DEBIAN/control"
    chmod 0644 "$STAGE/DEBIAN/control"
    # Reproducible timestamps: the commit time, when there is a git checkout.
    if [ -z "${SOURCE_DATE_EPOCH:-}" ] && git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
        SOURCE_DATE_EPOCH=$(git -C "$ROOT" log -1 --format=%ct)
        export SOURCE_DATE_EPOCH
    fi
    info "building $DEB_FILE (package version $DEB_VERSION)"
    COPYFILE_DISABLE=1 dpkg-deb --build --root-owner-group -Zxz "$STAGE" "$OUT/$DEB_FILE" >/dev/null
    rm -rf "$STAGE"
    [ "$(dpkg-deb -f "$OUT/$DEB_FILE" Version)" = "$DEB_VERSION" ] || die "$DEB_FILE does not carry version $DEB_VERSION"
    FILES+=("$DEB_FILE")
fi

(
    cd "$OUT"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "${FILES[@]}" >SHA256SUMS
    else
        shasum -a 256 "${FILES[@]}" >SHA256SUMS
    fi
)
info "done:"
(cd "$OUT" && ls -l && cat SHA256SUMS)
