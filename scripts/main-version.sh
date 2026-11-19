#!/usr/bin/env bash
# Print the version of a build of the checked-out commit for the rolling 'main' channel.
#
#   scripts/main-version.sh            e.g. 0.3.1-main.20261119101500.g1a2b3c4
#
# Base: the latest v<semver> tag reachable from HEAD (none: 0.0.0).
#   latest tag a release      X.Y.Z      -> X.Y.(Z+1)-main.<time>.g<sha>
#   latest tag a pre-release  X.Y.Z-pre  -> X.Y.Z-pre.main.<time>.g<sha>
# <time> is the commit time in UTC (YYYYMMDDHHMMSS), so rebuilding a commit gives the same
# version and later commits sort later. The .deb version replaces the first '-' with '~'
# (0.3.1~main.20261119101500.g1a2b3c4), which dpkg sorts after the base release, before the next
# release (0.3.1) and in commit-time order. Needs the tags (actions/checkout: fetch-depth: 0).

set -euo pipefail

die() { printf 'main-version: error: %s\n' "$*" >&2; exit 1; }

cd "$(git rev-parse --show-toplevel 2>/dev/null)" || die "not inside a git repository"

# versionsort.suffix=- sorts 1.2.0-rc.1 before 1.2.0.
latest=$(git -c versionsort.suffix=- tag --list 'v[0-9]*' --merged HEAD --sort=-v:refname |
    grep -E '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' | head -n 1 || true)
base=${latest#v}
[ -n "$base" ] || base=0.0.0

time=$(TZ=UTC git log -1 --format=%cd --date=format-local:%Y%m%d%H%M%S HEAD)
sha=$(git rev-parse --short=7 HEAD)
[[ $time =~ ^[0-9]{14}$ ]] || die "unexpected commit time '$time'"

case $base in
    *-*) printf '%s.main.%s.g%s\n' "$base" "$time" "$sha" ;;
    *)
        IFS=. read -r major minor patch <<<"$base"
        printf '%s.%s.%s-main.%s.g%s\n' "$major" "$minor" "$((patch + 1))" "$time" "$sha"
        ;;
esac
