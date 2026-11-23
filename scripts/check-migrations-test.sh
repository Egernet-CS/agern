#!/usr/bin/env bash
# Tests for scripts/check-migrations.sh: runs the real script in throwaway git repositories.
# Prints PASS/FAIL per case; exits non-zero on any failure.
#
#   scripts/check-migrations-test.sh

set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migrations.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

dir=server/src/main/resources/db/migration
failures=0
n=0

# A fresh repository with one commit holding the given migrations (names under $dir), at "base".
repo() {
    n=$((n + 1))
    local r="$work/repo$n"
    mkdir -p "$r/$dir"
    cd "$r" || exit 1
    git init -q
    git config user.name "Test"
    git config user.email "test@example.invalid"
    git config commit.gpgsign false
    local f
    for f in "$@"; do printf 'select %s;\n' "$f" >"$dir/$f"; done
    printf 'readme\n' >README
    git add -A
    git commit -q -m base
    git tag base
}

# check <name> <expected-status> <base-ref or ""> [<fragment>...]: runs the script, checks the status and that
# one output line contains all fragments.
check() {
    local name=$1 want=$2 base=$3
    shift 3
    local out status=0
    if [ -n "$base" ]; then out=$("$script" "$base" 2>&1) || status=$?; else out=$("$script" 2>&1) || status=$?; fi
    local ok=1
    [ "$status" -eq "$want" ] || ok=0
    if [ "$#" -gt 0 ]; then
        local line found=0
        while IFS= read -r line; do
            local all=1 frag
            for frag in "$@"; do [[ $line == *"$frag"* ]] || all=0; done
            if [ "$all" -eq 1 ]; then found=1; fi
        done <<<"$out"
        [ "$found" -eq 1 ] || ok=0
    fi
    if [ "$ok" -eq 1 ]; then
        printf 'PASS %s\n' "$name"
    else
        printf 'FAIL %s (exit %s, expected %s)\n%s\n' "$name" "$status" "$want" "$out"
        failures=$((failures + 1))
    fi
}

repo V1__init.sql V2__next.sql
check "clean tree without base" 0 "" "Migrations OK: 2 files, highest version 2"
check "clean tree with base" 0 base "Migrations OK"

repo V1__init.sql V2__next.sql
printf 'x\n' >"$dir/V2__other.sql"
check "duplicate version" 1 "" "duplicate migration version 2" "$dir/V2__next.sql and $dir/V2__other.sql"

repo V1__init.sql V11__a.sql
printf 'x\n' >"$dir/V011__b.sql"
check "numeric equality (V011 = V11)" 1 "" "duplicate migration version 11" "$dir/V011__b.sql and $dir/V11__a.sql"

repo V1__init.sql
printf 'x\n' >"$dir/v2_bad.sql"
check "bad name" 1 "" "$dir/v2_bad.sql: not a versioned migration"
rm "$dir/v2_bad.sql"
mkdir -p "$dir/sub"
printf 'x\n' >"$dir/sub/notes.txt"
check "bad name in a subdirectory" 1 "" "$dir/sub/notes.txt: not a versioned migration"

repo V1__init.sql V5__five.sql
printf 'x\n' >"$dir/V3__three.sql"
git add -A && git commit -q -m lower
check "added lower than the base's highest" 1 base \
    "$dir/V3__three.sql has version 3, which is not higher than $dir/V5__five.sql (version 5) on the base branch"
check "added lower without base passes" 0 ""

repo V1__init.sql V5__five.sql
printf 'x\n' >"$dir/V6__six.sql"
git add -A && git commit -q -m higher
check "added higher passes" 0 base "Migrations OK: 3 files, highest version 6"

repo V1__init.sql V2__next.sql
printf 'changed\n' >>"$dir/V1__init.sql"
check "changed base migration" 1 base "$dir/V1__init.sql exists on the base branch and was changed"

repo V1__init.sql V2__next.sql
rm "$dir/V2__next.sql"
check "removed base migration" 1 base "$dir/V2__next.sql exists on the base branch and was removed or renamed"

repo V1__init.sql V2__next.sql
git mv "$dir/V2__next.sql" "$dir/V3__next.sql"
git commit -q -m rename
check "renamed base migration" 1 base "$dir/V2__next.sql exists on the base branch and was removed or renamed"

repo V1__init.sql V2__next.sql
printf 'x\n' >"$dir/V2__untracked.sql"
check "untracked new file is checked" 1 "" "duplicate migration version 2" "$dir/V2__untracked.sql"

repo
rm -rf "$dir"
check "missing directory" 0 "" "Migrations OK: 0 files, highest version none"
check "missing directory with base" 0 base "Migrations OK"

repo V1__init.sql
check "invalid base ref" 2 "no-such-ref" "does not resolve to a commit"

cd "$work" || exit 1
GIT_CEILING_DIRECTORIES=$(dirname "$work")
export GIT_CEILING_DIRECTORIES
check "not inside a git repository" 2 "" "not inside a git repository"

if [ "$failures" -gt 0 ]; then
    printf '%d case(s) failed\n' "$failures"
    exit 1
fi
printf 'All cases passed\n'
