#!/usr/bin/env bash
# Cut a release: tag v<version> on main and push the tag. GitHub Actions (release.yml) then builds,
# tests and publishes the GitHub Release. Nothing is built locally.
#
#   scripts/release.sh [--dry-run] <version>      e.g. scripts/release.sh 1.2.0
#
# Checks: the version is semantic (MAJOR.MINOR.PATCH[-pre]), the working tree is clean, the
# current branch is main and identical to origin/main, and tag v<version> exists neither locally
# nor on origin. --dry-run runs the checks and prints what it would do.
# AGERN_RELEASE_REMOTE overrides the remote name (default origin).

set -euo pipefail

SEMVER_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$'
REMOTE=${AGERN_RELEASE_REMOTE:-origin}
BRANCH=main

die() { printf 'release: error: %s\n' "$*" >&2; exit 1; }
info() { printf 'release: %s\n' "$*"; }

DRY=0
VERSION=""
for arg in "$@"; do
    case $arg in
        --dry-run | -n) DRY=1 ;;
        -h | --help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) die "unknown option $arg" ;;
        *) [ -z "$VERSION" ] || die "only one version, please"; VERSION=$arg ;;
    esac
done
[ -n "$VERSION" ] || die "usage: scripts/release.sh [--dry-run] <version>"
case $VERSION in v*) die "give the version without 'v' (the tag will be v${VERSION#v})" ;; esac
[[ $VERSION =~ $SEMVER_RE ]] || die "'$VERSION' is not a semantic version (MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-pre)"
TAG="v$VERSION"

cd "$(git rev-parse --show-toplevel 2>/dev/null)" || die "not inside a git repository"

branch=$(git symbolic-ref --quiet --short HEAD || true)
[ "$branch" = "$BRANCH" ] || die "on '${branch:-detached HEAD}', releases are cut from $BRANCH"

[ -z "$(git status --porcelain)" ] || die "the working tree is not clean (commit or stash first):
$(git status --short)"

git remote get-url "$REMOTE" >/dev/null 2>&1 || die "no remote '$REMOTE'"
info "fetching $REMOTE"
git fetch --quiet "$REMOTE" "$BRANCH" || die "git fetch $REMOTE $BRANCH failed"
local_head=$(git rev-parse HEAD)
remote_head=$(git rev-parse FETCH_HEAD)
if [ "$local_head" != "$remote_head" ]; then
    ahead=$(git rev-list --count "$remote_head..$local_head")
    behind=$(git rev-list --count "$local_head..$remote_head")
    die "$BRANCH is not identical to $REMOTE/$BRANCH (ahead $ahead, behind $behind); push or pull first"
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then die "tag $TAG already exists locally"; fi
if [ -n "$(git ls-remote --tags "$REMOTE" "refs/tags/$TAG")" ]; then die "tag $TAG already exists on $REMOTE"; fi

latest=$(git tag --list 'v[0-9]*' --sort=-v:refname | head -n 1 || true)
if [ -n "$latest" ]; then
    highest=$(printf '%s\n%s\n' "${latest#v}" "$VERSION" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)
    if [ "$highest" != "$VERSION" ]; then
        info "note: $TAG is not newer than the latest tag $latest"
    fi
fi

short=$(git rev-parse --short HEAD)
if [ "$DRY" = 1 ]; then
    info "dry run — all checks passed. Would run:"
    printf '  git tag -a %s -m "Agern %s" %s\n' "$TAG" "$VERSION" "$short"
    printf '  git push %s %s\n' "$REMOTE" "$TAG"
    exit 0
fi

git tag -a "$TAG" -m "Agern $VERSION" HEAD
git push "$REMOTE" "refs/tags/$TAG"
info "pushed $TAG ($short). GitHub Actions builds and publishes the release:"
url=$(git remote get-url "$REMOTE")
case $url in
    git@github.com:*) url="https://github.com/${url#git@github.com:}" ;;
esac
info "  ${url%.git}/actions"
