#!/usr/bin/env bash
# Check the Flyway migrations in server/src/main/resources/db/migration (no database needed).
#
#   scripts/check-migrations.sh               names and duplicate versions only
#   scripts/check-migrations.sh <base-ref>    also against a base branch, e.g. origin/main or HEAD^1
#
# Works on the git repository containing the current directory. Rules (docs/ARCHITECTURE.md §6
# "Database migrations"):
#   1. every file in the directory (any depth) is named V<number>__<name>.sql;
#   2. no two files share a version (compared numerically: V011 equals V11);
#   3. with a base: a file that is not on the base has a version no lower than the base's highest;
#   4. with a base: every migration on the base is still there, unchanged.
# Problems go to stderr, one per line, exit 1; none: one line on stdout, exit 0. Usage errors: exit 2.

set -euo pipefail

usage_error() { printf 'check-migrations: %s\n' "$*" >&2; exit 2; }

[ "$#" -le 1 ] || usage_error "usage: scripts/check-migrations.sh [<base-ref>]"
top=$(git rev-parse --show-toplevel 2>/dev/null) || usage_error "not inside a git repository"
cd "$top" || usage_error "cannot enter $top"

base=""
if [ "$#" -eq 1 ]; then
    base=$(git rev-parse --verify --quiet "$1^{commit}") || usage_error "base ref '$1' does not resolve to a commit"
fi

dir=server/src/main/resources/db/migration
name_re='^V([0-9]+)__[A-Za-z0-9_]+\.sql$'
problems=0
problem() { printf 'error: %s\n' "$*" >&2; problems=$((problems + 1)); }

# A version without leading zeros (V011 and V11 are both 11).
normalize() {
    local v=${1#"${1%%[!0]*}"}
    printf '%s' "${v:-0}"
}

# True when normalized version $1 is lower than $2 (as numbers of any length).
lower() {
    if [ "${#1}" -ne "${#2}" ]; then [ "${#1}" -lt "${#2}" ]; else [[ $1 < $2 ]]; fi
}

# Scratch files (bash 3.2, the macOS default, has no associative arrays).
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Rule 1: names. Current files are the regular files in the working tree (tracked or not), sorted.
# Well-named ones go to $tmp/current as "<version><TAB><path>" lines; the other rules only look at those.
: >"$tmp/current"
count=0
highest=""
if [ -d "$dir" ]; then
    while IFS= read -r -d '' f; do
        name=${f##*/}
        if [[ $name =~ $name_re ]]; then
            v=$(normalize "${BASH_REMATCH[1]}")
            printf '%s\t%s\n' "$v" "$f" >>"$tmp/current"
            count=$((count + 1))
            if [ -z "$highest" ] || lower "$highest" "$v"; then highest=$v; fi
        else
            problem "$f: not a versioned migration (expected V<number>__<name>.sql)"
        fi
    done < <(find "$dir" -type f -print0 | LC_ALL=C sort -z)
fi

# Rule 2: duplicate versions, one line per version naming all its files (in path order).
while IFS= read -r line; do
    problem "duplicate migration version ${line%%$'\t'*}: ${line#*$'\t'}"
done < <(awk '
    { v = substr($0, 1, index($0, "\t") - 1); p = substr($0, index($0, "\t") + 1) }
    v in files { files[v] = files[v] " and " p; dup[v] = 1; next }
    { files[v] = p; order[++n] = v }
    END { for (i = 1; i <= n; i++) if (order[i] in dup) print order[i] "\t" files[order[i]] }
' "$tmp/current")

if [ -n "$base" ]; then
    # The base's tree under the directory: every path ($tmp/base-paths), and "<blob><TAB><path>" for each
    # well-named file ($tmp/base-blobs).
    : >"$tmp/base-paths"
    : >"$tmp/base-blobs"
    base_highest="" base_highest_path=""
    while IFS= read -r -d '' entry; do
        meta=${entry%%$'\t'*}
        path=${entry#*$'\t'}
        printf '%s\n' "$path" >>"$tmp/base-paths"
        [[ ${path##*/} =~ $name_re ]] || continue
        read -r _ type blob <<<"$meta"
        [ "$type" = blob ] || continue
        printf '%s\t%s\n' "$blob" "$path" >>"$tmp/base-blobs"
        v=$(normalize "${BASH_REMATCH[1]}")
        if [ -z "$base_highest" ] || lower "$base_highest" "$v"; then
            base_highest=$v
            base_highest_path=$path
        fi
    done < <(git ls-tree -r -z "$base" -- "$dir")

    # Rule 3: a new file must not be lower than the base's highest (equal is a duplicate, rule 2).
    if [ -n "$base_highest" ]; then
        while IFS= read -r line; do
            v=${line%%$'\t'*}
            f=${line#*$'\t'}
            if grep -Fxq -- "$f" "$tmp/base-paths"; then continue; fi
            if lower "$v" "$base_highest"; then
                problem "$f has version $v, which is not higher than $base_highest_path (version $base_highest) on the base branch"
            fi
        done <"$tmp/current"
    fi

    # Rule 4: migrations on the base are neither removed nor changed.
    while IFS= read -r line; do
        blob=${line%%$'\t'*}
        path=${line#*$'\t'}
        if [ ! -f "$path" ]; then
            problem "$path exists on the base branch and was removed or renamed; an applied migration must not change"
        elif [ "$(git hash-object -- "$path")" != "$blob" ]; then
            problem "$path exists on the base branch and was changed; an applied migration must not change"
        fi
    done < <(LC_ALL=C sort -t "$(printf '\t')" -k 2 "$tmp/base-blobs")
fi

if [ "$problems" -gt 0 ]; then exit 1; fi
printf 'Migrations OK: %d files, highest version %s\n' "$count" "${highest:-none}"
