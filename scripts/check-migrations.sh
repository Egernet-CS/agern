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

# Current files: regular files in the working tree (tracked or not), sorted.
current=()
if [ -d "$dir" ]; then
    while IFS= read -r -d '' f; do current+=("$f"); done < <(find "$dir" -type f -print0 | LC_ALL=C sort -z)
fi

# Rule 1: names. The others only look at well-named files.
declare -A version_of=() files_of=()
versions=()
for f in "${current[@]}"; do
    name=${f##*/}
    if [[ $name =~ $name_re ]]; then
        v=$(normalize "${BASH_REMATCH[1]}")
        version_of[$f]=$v
        if [ -z "${files_of[$v]+set}" ]; then
            files_of[$v]=$f
            versions+=("$v")
        else
            files_of[$v]+=" and $f"
        fi
    else
        problem "$f: not a versioned migration (expected V<number>__<name>.sql)"
    fi
done

# Rule 2: duplicate versions.
highest=""
for v in "${versions[@]}"; do
    if [[ ${files_of[$v]} == *" and "* ]]; then problem "duplicate migration version $v: ${files_of[$v]}"; fi
    if [ -z "$highest" ] || lower "$highest" "$v"; then highest=$v; fi
done

if [ -n "$base" ]; then
    # The base's tree under the directory: every path, and the blob of each well-named file.
    declare -A on_base=() base_blob=()
    base_highest="" base_highest_path=""
    while IFS= read -r -d '' entry; do
        meta=${entry%%$'\t'*}
        path=${entry#*$'\t'}
        on_base[$path]=1
        [[ ${path##*/} =~ $name_re ]] || continue
        read -r _ type blob <<<"$meta"
        [ "$type" = blob ] || continue
        base_blob[$path]=$blob
        v=$(normalize "${BASH_REMATCH[1]}")
        if [ -z "$base_highest" ] || lower "$base_highest" "$v"; then
            base_highest=$v
            base_highest_path=$path
        fi
    done < <(git ls-tree -r -z "$base" -- "$dir")

    # Rule 3: a new file must not be lower than the base's highest (equal is a duplicate, rule 2).
    if [ -n "$base_highest" ]; then
        for f in "${current[@]}"; do
            if [ -z "${version_of[$f]+set}" ] || [ -n "${on_base[$f]+set}" ]; then continue; fi
            v=${version_of[$f]}
            if lower "$v" "$base_highest"; then
                problem "$f has version $v, which is not higher than $base_highest_path (version $base_highest) on the base branch"
            fi
        done
    fi

    # Rule 4: migrations on the base are neither removed nor changed.
    while IFS= read -r -d '' path; do
        if [ ! -f "$path" ]; then
            problem "$path exists on the base branch and was removed or renamed; an applied migration must not change"
        elif [ "$(git hash-object -- "$path")" != "${base_blob[$path]}" ]; then
            problem "$path exists on the base branch and was changed; an applied migration must not change"
        fi
    done < <(for p in "${!base_blob[@]}"; do printf '%s\0' "$p"; done | LC_ALL=C sort -z)
fi

if [ "$problems" -gt 0 ]; then exit 1; fi
printf 'Migrations OK: %d files, highest version %s\n' "${#version_of[@]}" "${highest:-none}"
