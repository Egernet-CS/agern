#!/usr/bin/env bash
# Install smoke test for the agern .deb. Runs as root on a clean, throwaway Debian/Ubuntu system
# (a container: it installs packages, creates users and deletes things). Used by CI and locally:
#
#   docker run --rm -v "$PWD":/src:ro debian:12 bash /src/packaging/deb/smoke-test.sh \
#       /src/build/release/agern_<version>_all.deb [--server]
#
# Checks: apt resolves the dependencies; files, permissions and the 'agern' user; 'agern version'
# and 'agern doctor'; the configuration survives upgrades; 'agern upgrade --file', upgrades from
# a release directory (stable and main channel, checksum verification, refusal on mismatch);
# 'agern rollback'; dpkg ordering of main builds; remove and purge. Higher versions are made by
# repacking the given package with another version number.
#
# Sandboxes: only what the package scripts do without systemd and Podman — files, 'agern sandbox
# enable' without Podman (exact install hint, nothing changed), the subordinate id allocation
# (the real function, against other users' ranges and uids), the settings surviving upgrades,
# 'agern sandbox disable', purge. Rootless Podman itself needs a real systemd host:
# packaging/deb/sandbox-host-test.sh (CI runs it on an Ubuntu 24.04 VM).
#
# --server also starts the server against a native PostgreSQL (without systemd: 'agern run' as
# the agern user), checks /api/health and /api/internal/activity, and drives the automatic
# upgrade: postponed while an agent is busy, given up after the maximum wait, installed when idle.

# shellcheck disable=SC2015 # 'test && pass || fail': pass always succeeds
set -euo pipefail

DEB=${1:?usage: smoke-test.sh path/to/agern_<version>_all.deb [--server]}
SERVER=0
[ "${2:-}" = --server ] && SERVER=1
SRC_DIR=$(cd -P "$(dirname "$0")/../.." && pwd -P)

PASS=0
FAIL=0
pass() { printf 'PASS  %s\n' "$*"; PASS=$((PASS + 1)); }
fail() { printf 'FAIL  %s\n' "$*"; FAIL=$((FAIL + 1)); }
check() { # check "description" command...
    local d=$1
    shift
    if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi
}
eq() { # eq "description" expected actual
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$2', got '$3')"; fi
}
section() { printf '\n== %s\n' "$*"; }
stat_of() { stat -c '%U:%G %a' "$1"; }

export DEBIAN_FRONTEND=noninteractive
[ "$(id -u)" -eq 0 ] || { echo "run as root (in a throwaway container)" >&2; exit 2; }
# shellcheck source=/dev/null
. /etc/os-release
echo "smoke test on $PRETTY_NAME ($(uname -m))"

WORK=$(mktemp -d /tmp/agern-smoke.XXXXXX)
cp "$DEB" "$WORK/a.deb"
A_DEB=$(dpkg-deb -f "$WORK/a.deb" Version)
A_SEMVER=$(dpkg-deb --fsys-tarfile "$WORK/a.deb" | tar -xO ./usr/share/agern/VERSION)
echo "package under test: $A_DEB (semver $A_SEMVER)"

# Same mapping as packaging/agern (taken from there, so the real function is tested).
eval "$(sed -n '/^semver_to_deb() {/,/^}/p' "$SRC_DIR/packaging/agern")"

repack() { # repack SEMVER OUT — the package under test with another version number
    local v=$1 out=$2 d="$WORK/repack"
    rm -rf "$d"
    dpkg-deb -R "$WORK/a.deb" "$d"
    sed -i "s/^Version: .*/Version: $(semver_to_deb "$v")/" "$d/DEBIAN/control"
    printf '%s\n' "$v" >"$d/usr/share/agern/VERSION"
    if [ -f "$d/DEBIAN/md5sums" ]; then
        (cd "$d" && find usr -type f | LC_ALL=C sort | xargs md5sum) >"$d/DEBIAN/md5sums"
    fi
    dpkg-deb --build --root-owner-group -Zxz "$d" "$out" >/dev/null
    rm -rf "$d"
}

# A release directory with GitHub's layout, served over file://.
REL="$WORK/releases"
publish() { # publish DIR SEMVER [corrupt]
    local dir="$REL/$1" v=$2 f="agern_$2_all.deb"
    rm -rf "$dir"
    mkdir -p "$dir"
    repack "$v" "$dir/$f"
    (cd "$dir" && sha256sum "$f" >SHA256SUMS)
    if [ "${3:-}" = corrupt ]; then printf '%064d  %s\n' 0 "$f" >"$dir/SHA256SUMS"; fi
}
export AGERN_RELEASE_BASE_URL="file://$REL"

section "version ordering (dpkg --compare-versions)"
while read -r lo hi; do
    if dpkg --compare-versions "$(semver_to_deb "$lo")" lt "$(semver_to_deb "$hi")"; then
        pass "$lo < $hi"
    else
        fail "$lo < $hi"
    fi
done <<'EOF'
0.2.0 0.2.1-main.20261119101500.g1a2b3c4
0.2.1-main.20261119101500.g1a2b3c4 0.2.1-main.20261119101501.g0000000
0.2.1-main.20261119235959.gffffff0 0.2.1-main.20261120000000.g0123456
0.2.1-main.20261119101500.g1a2b3c4 0.2.1
0.2.1-main.20261119101500.g1a2b3c4 0.3.0-rc.1
0.3.0-rc.1 0.3.0-rc.1.main.20261119101500.g1a2b3c4
0.3.0-rc.1.main.20261119101500.g1a2b3c4 0.3.0-rc.2
0.3.0-rc.2 0.3.0
EOF

section "install with apt (dependencies resolve)"
apt-get update -qq
if apt-get install -y -q "$WORK/a.deb" >"$WORK/install.log" 2>&1; then pass "apt-get install ./agern.deb"; else fail "apt-get install ./agern.deb"; tail -n 40 "$WORK/install.log"; exit 1; fi
grep -q 'not configured yet' "$WORK/install.log" && pass "postinst explains the next steps" || fail "postinst next-steps message"
eq "package installed" "install ok installed $A_DEB" "$(dpkg-query -W -f='${Status} ${Version}' agern)"
check "java is installed" command -v java
java -version 2>&1 | head -n 1
check "git is installed" command -v git
check "curl is installed" command -v curl
check "postgresql (Recommends) is installed" dpkg-query -W postgresql

section "files, permissions, user"
eq "/usr/bin/agern" "root:root 755" "$(stat_of /usr/bin/agern)"
eq "/usr/share/agern/agern.jar" "root:root 644" "$(stat_of /usr/share/agern/agern.jar)"
check "agern.service at /usr/lib/systemd/system" test -f /usr/lib/systemd/system/agern.service
check "agern-upgrade.timer at /usr/lib/systemd/system" test -f /usr/lib/systemd/system/agern-upgrade.timer
for u in agern-sandbox.socket agern-sandbox.service agern-sandbox-prune.service agern-sandbox-prune.timer; do
    check "$u at /usr/lib/systemd/system" test -f "/usr/lib/systemd/system/$u"
done
eq "/usr/lib/agern/sandbox-service" "root:root 755" "$(stat_of /usr/lib/agern/sandbox-service)"
eq "/usr/share/agern/podman/containers.conf" "root:root 644" "$(stat_of /usr/share/agern/podman/containers.conf)"
eq "/usr/share/agern/podman/storage.conf" "root:root 644" "$(stat_of /usr/share/agern/podman/storage.conf)"
check "no sandbox unit enabled by the install" test ! -e /etc/systemd/system/sockets.target.wants/agern-sandbox.socket
eq "/etc/agern" "root:root 755" "$(stat_of /etc/agern)"
eq "/etc/agern/agern.env" "root:agern 640" "$(stat_of /etc/agern/agern.env)"
eq "/var/lib/agern" "agern:agern 750" "$(stat_of /var/lib/agern)"
eq "/var/cache/agern" "root:root 755" "$(stat_of /var/cache/agern)"
check "master key generated (44 base64 chars)" grep -Eq '^FACTORY_MASTER_KEY=[A-Za-z0-9+/]{43}=$' /etc/agern/agern.env
check "FACTORY_DATA_DIR=/var/lib/agern" grep -qx 'FACTORY_DATA_DIR=/var/lib/agern' /etc/agern/agern.env
check "FACTORY_GITHUB_ORG left empty" grep -qx 'FACTORY_GITHUB_ORG=' /etc/agern/agern.env
check "FACTORY_DB_URL left empty" grep -qx 'FACTORY_DB_URL=' /etc/agern/agern.env
check "AGERN_UPGRADE_CHANNEL=stable" grep -qx 'AGERN_UPGRADE_CHANNEL=stable' /etc/agern/agern.env
IFS=: read -r _ _ uid _ _ home shell < <(getent passwd agern)
check "agern is a system user (uid $uid < 1000)" test "$uid" -lt 1000
eq "agern home" /var/lib/agern "$home"
case $shell in */nologin) pass "agern shell $shell" ;; *) fail "agern shell $shell" ;; esac
eq "agern groups (not shadow, no sudo)" agern "$(id -nG agern)"
KEY_BEFORE=$(grep '^FACTORY_MASTER_KEY=' /etc/agern/agern.env)

section "agern version / doctor"
out=$(agern version 2>&1) && pass "agern version (root)" || fail "agern version (root)"
printf '%s\n' "$out"
printf '%s' "$out" | grep -q "installed: $A_SEMVER" && pass "reports $A_SEMVER" || fail "reports $A_SEMVER"
check "agern version (as agern)" runuser -u agern -- agern version
set +e
doc=$(agern doctor 2>&1)
rc=$?
set -e
printf '%s\n' "$doc"
eq "doctor exits 1 (problems found, no crash)" 1 "$rc"
printf '%s' "$doc" | grep -q 'Agern doctor (deb install' && pass "doctor detects the deb layout" || fail "doctor detects the deb layout"
printf '%s' "$doc" | grep -q 'ok    owner root:agern, mode 640' && pass "doctor: env file permissions ok" || fail "doctor: env file permissions"
printf '%s' "$doc" | grep -q 'ok    FACTORY_MASTER_KEY is 32 bytes' && pass "doctor: master key read" || fail "doctor: master key read"
printf '%s' "$doc" | grep -q 'ok    agern is in group(s): agern$' && pass "doctor: agern only in its own group" || fail "doctor: agern groups"
printf '%s' "$doc" | grep -q 'FAIL  claude not found.*claude.ai/install.sh' && pass "doctor: claude missing, with install hint" || fail "doctor: claude hint"
printf '%s' "$doc" | grep -q 'FAIL  gh not found.*apt install gh' && pass "doctor: gh missing, with install hint" || fail "doctor: gh hint"
printf '%s' "$doc" | grep -q 'FAIL  FACTORY_GITHUB_ORG is not set' && pass "doctor: org not set" || fail "doctor: org not set"
printf '%s' "$doc" | grep -q "ok    sandboxes off ('sudo agern sandbox enable' turns them on)" && pass "doctor: sandboxes off, one line" || fail "doctor: sandboxes off"
# chromium is a Recommends: Debian installs the browser, Ubuntu the chromium-browser launcher for the snap.
browser=$(command -v chromium chromium-browser google-chrome google-chrome-stable 2>/dev/null | head -n 1 || true)
if [ -n "$browser" ] && grep -qs '/snap/' "$browser"; then
    printf '%s' "$doc" | grep -q "warn  $browser is Ubuntu's launcher for the chromium snap" && pass "doctor: snap launcher $browser flagged" || fail "doctor: snap launcher not flagged"
elif [ -n "$browser" ]; then
    printf '%s' "$doc" | grep -q "ok    headless browser for the UI reviewers: $browser" && pass "doctor: headless browser $browser (Recommends) found" || fail "doctor: headless browser $browser not found"
else
    printf '%s' "$doc" | grep -q 'warn  no headless browser (chromium, .*apt install chromium fonts-noto-core' && pass "doctor: no headless browser, with install hint" || fail "doctor: headless browser hint"
fi

sed -i 's/^AGERN_UPGRADE_CHANNEL=stable$/AGERN_UPGRADE_CHANNEL=main/' /etc/agern/agern.env
agern auto-upgrade status | grep -q '^channel: *main$' && pass "auto-upgrade status (root) reads the channel" || fail "auto-upgrade status as root"
nobody_out=$(runuser -u nobody -- agern auto-upgrade status 2>&1 || true)
printf '%s' "$nobody_out" | grep -q '^channel: *unknown (cannot read' && pass "auto-upgrade status without root says it cannot read the channel" || fail "auto-upgrade status without root: $nobody_out"
sed -i 's/^AGERN_UPGRADE_CHANNEL=main$/AGERN_UPGRADE_CHANNEL=stable/' /etc/agern/agern.env

section "sandboxes: package scripts without systemd and Podman"
out=$(agern sandbox status 2>&1) || true
printf '%s\n' "$out"
printf '%s' "$out" | grep -q '^sandboxes: *off$' && pass "sandbox status (root): off" || fail "sandbox status: $out"
out=$(runuser -u nobody -- agern sandbox status 2>&1) || true
printf '%s' "$out" | grep -q '^sandboxes: *unknown (cannot read' && pass "sandbox status without root says it cannot read the setting" || fail "sandbox status as nobody: $out"
printf '%s' "$out" | grep -q '^containers: *unknown (only root and agern' && pass "sandbox status without root says it cannot query Podman" || fail "sandbox status as nobody (containers): $out"
command -v podman >/dev/null 2>&1 && fail "podman is installed in the test image (the hint test needs it absent)"
ENV_SUM=$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)
SUBID_SUM=$(cat /etc/subuid /etc/subgid 2>/dev/null | sha256sum | cut -d' ' -f1)
set +e
out=$(agern sandbox enable 2>&1)
rc=$?
set -e
printf '%s\n' "$out"
[ "$rc" != 0 ] && pass "sandbox enable without Podman fails" || fail "sandbox enable without Podman (rc $rc)"
printf '%s' "$out" | grep -qx '  sudo apt install podman uidmap netavark aardvark-dns crun slirp4netns' && pass "sandbox enable names the exact packages to install" || fail "sandbox enable install hint"
printf '%s' "$out" | grep -q 'nothing was changed' && pass "sandbox enable says nothing was changed" || fail "sandbox enable: nothing changed message"
eq "agern.env unchanged by the failed enable" "$ENV_SUM" "$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)"
eq "subordinate ids unchanged by the failed enable" "$SUBID_SUM" "$(cat /etc/subuid /etc/subgid 2>/dev/null | sha256sum | cut -d' ' -f1)"
check "socket not enabled by the failed enable" test ! -e /etc/systemd/system/sockets.target.wants/agern-sandbox.socket
out=$(runuser -u nobody -- agern sandbox enable 2>&1) || true
printf '%s' "$out" | grep -q 'needs root' && pass "sandbox enable without root refused" || fail "sandbox enable as nobody: $out"

# The allocation of subordinate ids: the real functions from packaging/agern, against other users'
# ranges in /etc/subuid and /etc/subgid and a real uid inside a free-looking block.
eval "$(sed -n -e '/^subid_range() {/,/^}/p' -e '/^subid_free() {/,/^}/p' -e '/^sandbox_ensure_subids() {/,/^}/p' "$SRC_DIR/packaging/agern")"
# shellcheck disable=SC2034 # used by the functions taken from packaging/agern
AGERN_USER=agern SANDBOX_SUBID_COUNT=65536 SUBIDS_CHANGED=0
# shellcheck disable=SC2317,SC2329 # called by those functions (taken in with eval above)
info() { printf 'agern: %s\n' "$*"; }
# shellcheck disable=SC2317,SC2329
die() { printf 'agern: error: %s\n' "$*" >&2; return 1; }
printf 'alice:100000:65536\nbob:165536:65536\ncarol:300000:1000\n' >/etc/subuid
printf 'alice:100000:65536\ndave:231072:65536\n' >/etc/subgid
useradd --uid 362144 --no-create-home bigid
sandbox_ensure_subids
eq "subuid range avoids other ranges and uid 362144" "agern:427680:65536" "$(grep '^agern:' /etc/subuid)"
eq "subgid range is the same block" "agern:427680:65536" "$(grep '^agern:' /etc/subgid)"
eq "SUBIDS_CHANGED after allocating" 1 "$SUBIDS_CHANGED"
SUBID_SUM=$(cat /etc/subuid /etc/subgid | sha256sum | cut -d' ' -f1)
SUBIDS_CHANGED=0
sandbox_ensure_subids
eq "allocation again: nothing changes" "$SUBID_SUM" "$(cat /etc/subuid /etc/subgid | sha256sum | cut -d' ' -f1)"
eq "SUBIDS_CHANGED when kept" 0 "$SUBIDS_CHANGED"
sed -i '/^agern:/d' /etc/subgid
sandbox_ensure_subids
eq "only the missing subgid range is added, clear of all ranges" "agern:493216:65536" "$(grep '^agern:' /etc/subgid)"
eq "subuid range kept" "agern:427680:65536" "$(grep '^agern:' /etc/subuid)"
eq "other users' ranges untouched" "alice:100000:65536 bob:165536:65536 carol:300000:1000" "$(grep -v '^agern:' /etc/subuid | tr '\n' ' ' | sed 's/ $//')"
userdel bigid
unset -f info die

# An installation with sandboxes on (as 'agern sandbox enable' leaves agern.env): doctor's checks,
# then the settings must survive the upgrades below.
printf 'FACTORY_SANDBOX=podman\nFACTORY_SANDBOX_PODMAN_URL=unix:///run/agern-podman/podman.sock\nFACTORY_SANDBOX_CGROUP_PARENT=/system.slice/agern-sandbox.service/containers\n' >>/etc/agern/agern.env
set +e
doc=$(agern doctor 2>&1)
set -e
printf '%s\n' "$doc" | sed -n '/^sandboxes$/,/^service$/p'
printf '%s' "$doc" | grep -q 'FAIL  packages for rootless Podman missing: podman uidmap netavark aardvark-dns crun slirp4netns (sudo apt install' && pass "doctor (sandboxes on): missing packages with the install command" || fail "doctor: sandbox packages"
printf '%s' "$doc" | grep -q 'ok    subordinate ids of agern: uids 427680+65536, gids 493216+65536' && pass "doctor: subordinate ids" || fail "doctor: subordinate ids"
printf '%s' "$doc" | grep -q 'ok    FACTORY_SANDBOX_PODMAN_URL=unix:///run/agern-podman/podman.sock' && pass "doctor: podman url" || fail "doctor: podman url"
printf '%s' "$doc" | grep -q 'FAIL  systemd is not running: the sandbox service cannot run here' && pass "doctor: no systemd for the sandbox service" || fail "doctor: sandbox without systemd"
printf '%s' "$doc" | grep -Eq '(ok|warn|FAIL) .*(GB|MB) free for images and caches' && pass "doctor: free disk space" || fail "doctor: free disk space"

section "configuration survives an upgrade (agern upgrade --file)"
sed -i 's/^FACTORY_GITHUB_ORG=$/FACTORY_GITHUB_ORG=smoke-test-org/' /etc/agern/agern.env
printf '# local edit by the smoke test\n' >>/etc/agern/agern.env
SUM_BEFORE=$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)
B_SEMVER=${A_SEMVER%%[-+]*}
B_SEMVER="${B_SEMVER%.*}.$((${B_SEMVER##*.} + 1))"
repack "$B_SEMVER" "$WORK/b.deb"
# Installed with apt, so there is no cached copy of the installed version, and its release is not
# downloadable (as for a main build in CI): the upgrade rebuilds a copy from the installed files.
check "installed files match the package (dpkg --verify)" test -z "$(dpkg --verify agern 2>&1 | grep -v -E '^missing +/usr/share/doc/')"
if agern upgrade --file "$WORK/b.deb" >"$WORK/up1.log" 2>&1; then pass "agern upgrade --file b.deb"; else fail "agern upgrade --file b.deb"; cat "$WORK/up1.log"; fi
grep -q 'rebuilt from the installed files' "$WORK/up1.log" && pass "a copy of the installed version was rebuilt for rollback" || { fail "rebuilt copy"; cat "$WORK/up1.log"; }
eq "now $B_SEMVER" "$(semver_to_deb "$B_SEMVER")" "$(dpkg-query -W -f='${Version}' agern)"
eq "env file unchanged" "$SUM_BEFORE" "$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)"
eq "env file permissions kept" "root:agern 640" "$(stat_of /etc/agern/agern.env)"
eq "previous.deb is $A_DEB" "$A_DEB" "$(dpkg-deb -f /var/cache/agern/previous.deb Version 2>/dev/null || echo none)"
eq "current.deb is the new version" "$(semver_to_deb "$B_SEMVER")" "$(dpkg-deb -f /var/cache/agern/current.deb Version 2>/dev/null || echo none)"
out=$(agern upgrade --file "$WORK/b.deb" 2>&1) && printf '%s' "$out" | grep -q 'already installed' && pass "same version again: nothing to do" || fail "same version again"
set +e
agern upgrade --file "$WORK/a.deb" >"$WORK/down.log" 2>&1
rc=$?
set -e
[ "$rc" != 0 ] && grep -q 'older than the installed' "$WORK/down.log" && pass "downgrade without --force refused" || fail "downgrade without --force refused"
check "a file that is not an agern package is refused" bash -c '! agern upgrade --file /usr/share/agern/VERSION'

section "rollback"
if agern rollback >"$WORK/rb.log" 2>&1; then pass "agern rollback"; else fail "agern rollback"; cat "$WORK/rb.log"; fi
eq "rolled back to $A_DEB" "$A_DEB" "$(dpkg-query -W -f='${Version}' agern)"
eq "previous.deb is now $B_SEMVER" "$(semver_to_deb "$B_SEMVER")" "$(dpkg-deb -f /var/cache/agern/previous.deb Version 2>/dev/null || echo none)"
eq "env file unchanged after rollback" "$SUM_BEFORE" "$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)"
check "the rebuilt package reinstalled the original files (dpkg --verify)" test -z "$(dpkg --verify agern 2>&1 | grep -v -E '^missing +/usr/share/doc/')"
eq "VERSION after rollback" "$A_SEMVER" "$(cat /usr/share/agern/VERSION)"
check "agern works after rollback" agern version
agern version

section "upgrade from a release (stable channel, checksum)"
C_SEMVER="${B_SEMVER%.*}.$((${B_SEMVER##*.} + 1))"
publish latest/download "$C_SEMVER" corrupt
set +e
agern upgrade >"$WORK/bad.log" 2>&1
rc=$?
set -e
[ "$rc" != 0 ] && grep -q 'checksum mismatch' "$WORK/bad.log" && pass "checksum mismatch refused" || { fail "checksum mismatch refused"; cat "$WORK/bad.log"; }
eq "nothing changed after the mismatch" "$A_DEB" "$(dpkg-query -W -f='${Version}' agern)"
publish latest/download "$C_SEMVER"
if agern upgrade >"$WORK/up2.log" 2>&1; then pass "agern upgrade (stable)"; else fail "agern upgrade (stable)"; cat "$WORK/up2.log"; fi
eq "now $C_SEMVER" "$(semver_to_deb "$C_SEMVER")" "$(dpkg-query -W -f='${Version}' agern)"
out=$(agern upgrade 2>&1) && printf '%s' "$out" | grep -q 'already installed' && pass "stable again: already installed" || fail "stable again"

section "main channel (and keeping a copy by downloading the installed release again)"
NEXT=${C_SEMVER%.*}.$((${C_SEMVER##*.} + 1))
M1="$NEXT-main.20261119101500.g1a2b3c4"
publish download/main-latest "$M1"
rm -f /var/cache/agern/current.deb
publish "download/v$C_SEMVER" "$C_SEMVER"
if agern upgrade --channel main >"$WORK/up3.log" 2>&1; then pass "agern upgrade --channel main"; else fail "agern upgrade --channel main"; cat "$WORK/up3.log"; fi
grep -q 'downloaded and verified' "$WORK/up3.log" && pass "the installed release was downloaded again for rollback" || { fail "download copy"; cat "$WORK/up3.log"; }
eq "previous.deb is $C_SEMVER" "$(semver_to_deb "$C_SEMVER")" "$(dpkg-deb -f /var/cache/agern/previous.deb Version 2>/dev/null || echo none)"
eq "now the main build" "$(semver_to_deb "$M1")" "$(dpkg-query -W -f='${Version}' agern)"
eq "VERSION file" "$M1" "$(cat /usr/share/agern/VERSION)"
set +e
out=$(agern upgrade 2>&1)
rc=$?
set -e
[ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'older than the installed' && pass "stable $C_SEMVER is older than the main build: refused" || fail "stable older than main refused ($out)"
# No copy can be kept: not cached, not downloadable (main-latest moved on) and an installed file
# was changed. The upgrade says so, and rollback refuses with a way out.
rm -f /var/cache/agern/current.deb
rm -rf "$REL/download/main-latest"
echo '# changed' >>/usr/share/agern/env.example
publish latest/download "$NEXT"
if agern upgrade >"$WORK/up4.log" 2>&1; then pass "release $NEXT replaces the main build"; else fail "release replaces main build"; cat "$WORK/up4.log"; fi
grep -q 'differ from the package' "$WORK/up4.log" && pass "changed installed files are not packed up" || fail "changed files detected"
grep -q "no copy of the installed version $M1 could be kept" "$WORK/up4.log" && pass "upgrade says no rollback copy could be kept" || { fail "no-copy warning"; cat "$WORK/up4.log"; }
check "no previous.deb" test ! -e /var/cache/agern/previous.deb
set +e
agern rollback >"$WORK/rb2.log" 2>&1
rc=$?
set -e
[ "$rc" != 0 ] && grep -q 'nothing to roll back to.*--version X.Y.Z --force' "$WORK/rb2.log" && pass "rollback fails with a helpful message" || { fail "rollback without a copy"; cat "$WORK/rb2.log"; }
check "the upgrade replaced the changed file (dpkg --verify)" test -z "$(dpkg --verify agern 2>&1 | grep -v -E '^missing +/usr/share/doc/')"
eq "now $NEXT" "$NEXT" "$(dpkg-query -W -f='${Version}' agern)"
eq "env file unchanged after all upgrades" "$SUM_BEFORE" "$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)"
eq "master key unchanged" "$KEY_BEFORE" "$(grep '^FACTORY_MASTER_KEY=' /etc/agern/agern.env)"
LAST=$NEXT

section "sandboxes: settings kept by the upgrades, then 'agern sandbox disable'"
check "FACTORY_SANDBOX=podman kept" grep -qx 'FACTORY_SANDBOX=podman' /etc/agern/agern.env
eq "subuid range kept by the upgrades" "agern:427680:65536" "$(grep '^agern:' /etc/subuid)"
eq "subgid range kept by the upgrades" "agern:493216:65536" "$(grep '^agern:' /etc/subgid)"
if out=$(agern sandbox disable 2>&1); then pass "agern sandbox disable"; else fail "agern sandbox disable"; fi
printf '%s\n' "$out"
check "FACTORY_SANDBOX=off" grep -qx 'FACTORY_SANDBOX=off' /etc/agern/agern.env
check "FACTORY_SANDBOX_PODMAN_URL removed" bash -c '! grep -q "^FACTORY_SANDBOX_PODMAN_URL=" /etc/agern/agern.env'
check "FACTORY_SANDBOX_CGROUP_PARENT removed" bash -c '! grep -q "^FACTORY_SANDBOX_CGROUP_PARENT=" /etc/agern/agern.env'
check "the commented example lines are kept" grep -q '^# FACTORY_SANDBOX_PODMAN_URL=' /etc/agern/agern.env
eq "env file permissions after disable" "root:agern 640" "$(stat_of /etc/agern/agern.env)"
eq "master key unchanged by disable" "$KEY_BEFORE" "$(grep '^FACTORY_MASTER_KEY=' /etc/agern/agern.env)"
printf '%s' "$out" | grep -q 'systemd is not running here' && pass "disable says there are no units to stop here" || fail "disable without systemd message"
printf '%s' "$out" | grep -q 'kept: images and' && pass "disable says what it kept" || fail "disable: kept message"
set +e
doc=$(agern doctor 2>&1)
set -e
printf '%s' "$doc" | grep -q "ok    sandboxes off" && pass "doctor after disable: off" || fail "doctor after disable"

if [ "$SERVER" = 1 ]; then
    section "server against native PostgreSQL (no systemd: agern run as agern)"
    pgver=$(find /usr/lib/postgresql -mindepth 1 -maxdepth 1 -printf '%f\n' | sort -n | tail -n 1)
    pg_ctlcluster "$pgver" main start
    if agern setup-db >"$WORK/db.log" 2>&1; then pass "agern setup-db"; else fail "agern setup-db"; cat "$WORK/db.log"; fi
    cat "$WORK/db.log"
    check "FACTORY_DB_URL written" grep -Eq '^FACTORY_DB_URL=jdbc:postgresql://localhost:[0-9]+/factory$' /etc/agern/agern.env
    check "FACTORY_DB_PASSWORD generated" grep -Eq '^FACTORY_DB_PASSWORD=[A-Za-z0-9]{32}$' /etc/agern/agern.env
    PW1=$(grep '^FACTORY_DB_PASSWORD=' /etc/agern/agern.env)
    if agern setup-db >"$WORK/db2.log" 2>&1; then pass "agern setup-db again (idempotent)"; else fail "agern setup-db again"; cat "$WORK/db2.log"; fi
    eq "password kept on the second run" "$PW1" "$(grep '^FACTORY_DB_PASSWORD=' /etc/agern/agern.env)"
    eq "env file permissions after setup-db" "root:agern 640" "$(stat_of /etc/agern/agern.env)"
    sed -i 's/^FACTORY_AUTH=os$/FACTORY_AUTH=ldap/' /etc/agern/agern.env
    printf 'FACTORY_LDAP_URL=ldap://127.0.0.1:1\nFACTORY_LDAP_USER_DN_TEMPLATE=uid={0},dc=example\n' >>/etc/agern/agern.env

    mkdir -p /run/agern
    start_server() {
        runuser -u agern -- agern run >"$WORK/server.log" 2>&1 &
        echo $! >/run/agern/agern.pid
        for _ in $(seq 1 90); do
            curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1 && return 0
            sleep 1
        done
        return 1
    }
    stop_server() {
        kill "$(cat /run/agern/agern.pid)" 2>/dev/null || true
        for _ in $(seq 1 30); do kill -0 "$(cat /run/agern/agern.pid)" 2>/dev/null || break; sleep 1; done
    }
    if start_server; then pass "server answers /api/health"; else fail "server did not start"; tail -n 60 "$WORK/server.log"; fi
    curl -fsS http://127.0.0.1:8080/api/health && echo
    jowner=""
    for p in /proc/[0-9]*; do
        if grep -qa 'agern.jar' "$p/cmdline" 2>/dev/null; then jowner=$(stat -c %U "$p"); break; fi
    done
    eq "server (java) runs as agern" agern "$jowner"
    act=$(curl -fsS http://127.0.0.1:8080/api/internal/activity || true)
    echo "$act"
    case $act in *'"idle":true'*) pass "/api/internal/activity: idle" ;; *) fail "/api/internal/activity: $act" ;; esac
    code=$(curl -s -o /dev/null -w '%{http_code}' -H 'X-Forwarded-For: 192.0.2.1' http://127.0.0.1:8080/api/internal/activity)
    eq "activity refused through a proxy" 404 "$code"
    set +e
    doc=$(agern doctor 2>&1)
    set -e
    printf '%s\n' "$doc" | sed -n '/postgresql/,/agent tools/p;/^service/,$p'
    printf '%s' "$doc" | grep -q "ok    logged in to database 'factory' as 'factory'" && pass "doctor: database login ok" || fail "doctor: database login"
    printf '%s' "$doc" | grep -q 'ok    activity: idle' && pass "doctor: activity idle" || fail "doctor: activity"

    section "automatic upgrade (agern auto-upgrade run)"
    db_pw=$(sed -n 's/^FACTORY_DB_PASSWORD=//p' /etc/agern/agern.env)
    psql_factory() { PGPASSWORD=$db_pw psql -X -h localhost -U factory -d factory -Atqc "$1"; }
    psql_factory "INSERT INTO users(id, username, display_name, is_admin) VALUES ('00000000-0000-0000-0000-000000000001', 'smoke', 'Smoke', false)" >/dev/null
    psql_factory "INSERT INTO agent_sessions(id, repo, kind, model, status, owner_id, title) VALUES ('00000000-0000-0000-0000-000000000002', 'demo', 'chat', 'sonnet', 'running', '00000000-0000-0000-0000-000000000001', 'busy')" >/dev/null
    act=$(curl -fsS http://127.0.0.1:8080/api/internal/activity || true)
    case $act in *'"idle":false'*'"busyAgents":1'*) pass "activity: busy with one agent" ;; *) fail "activity busy: $act" ;; esac
    NEXT2=${LAST%.*}.$((${LAST##*.} + 1))
    publish latest/download "$NEXT2"
    out=$(agern auto-upgrade run 2>&1) || true
    printf '%s\n' "$out"
    printf '%s' "$out" | grep -q 'postponing the upgrade' && pass "auto-upgrade postponed while busy" || fail "auto-upgrade postponed"
    eq "still on $LAST" "$LAST" "$(dpkg-query -W -f='${Version}' agern)"
    check "state file records the pending upgrade" grep -q "^pending_target=$NEXT2$" /var/cache/agern/auto-upgrade.state
    sed -i 's/^AGERN_UPGRADE_MAX_WAIT_HOURS=.*/AGERN_UPGRADE_MAX_WAIT_HOURS=0/' /etc/agern/agern.env
    out=$(agern auto-upgrade run 2>&1) || true
    printf '%s\n' "$out"
    printf '%s' "$out" | grep -q 'giving up on the automatic upgrade' && pass "auto-upgrade gives up after the maximum wait" || fail "auto-upgrade gives up"
    out=$(agern auto-upgrade run 2>&1) || true
    printf '%s' "$out" | grep -q 'gave up waiting' && pass "the given-up version is not retried" || fail "given-up version not retried ($out)"
    sed -i 's/^AGERN_UPGRADE_MAX_WAIT_HOURS=.*/AGERN_UPGRADE_MAX_WAIT_HOURS=24/' /etc/agern/agern.env
    NEXT3=${NEXT2%.*}.$((${NEXT2##*.} + 1))
    publish latest/download "$NEXT3"
    psql_factory "UPDATE agent_sessions SET status = 'idle'" >/dev/null
    out=$(agern auto-upgrade run 2>&1) || true
    printf '%s\n' "$out"
    eq "a newer release is installed once idle" "$NEXT3" "$(dpkg-query -W -f='${Version}' agern)"
    out=$(agern auto-upgrade run 2>&1) || true
    printf '%s' "$out" | grep -q 'up to date' && pass "auto-upgrade: up to date" || fail "auto-upgrade up to date ($out)"
    stop_server
    LAST=$NEXT3
fi

section "remove"
apt-get remove -y -q agern >"$WORK/remove.log" 2>&1 && pass "apt-get remove agern" || { fail "apt-get remove agern"; cat "$WORK/remove.log"; }
check "program removed" test ! -e /usr/bin/agern
check "jar removed" test ! -e /usr/share/agern/agern.jar
check "configuration kept" test -f /etc/agern/agern.env
eq "configuration unchanged" "$KEY_BEFORE" "$(grep '^FACTORY_MASTER_KEY=' /etc/agern/agern.env)"
check "data kept" test -d /var/lib/agern
check "user kept" getent passwd agern
eq "package state" "deinstall ok config-files" "$(dpkg-query -W -f='${Status}' agern)"

section "reinstall after remove keeps the configuration"
apt-get install -y -q "$WORK/a.deb" >/dev/null 2>&1 && pass "reinstall" || fail "reinstall"
eq "master key kept" "$KEY_BEFORE" "$(grep '^FACTORY_MASTER_KEY=' /etc/agern/agern.env)"

section "purge"
# Sandbox storage as rootless Podman leaves it: owned by agern, with files of its subordinate ids.
runuser -u agern -- mkdir -p -m 0700 /var/lib/agern/containers/storage/overlay
runuser -u agern -- touch /var/lib/agern/containers/storage/overlay/layer-file
chown 427680:427680 /var/lib/agern/containers/storage/overlay/layer-file
runuser -u agern -- touch /var/lib/agern/keep-me
apt-get purge -y -q agern >"$WORK/purge.log" 2>&1 && pass "apt-get purge agern" || { fail "apt-get purge agern"; cat "$WORK/purge.log"; }
grep '^agern:\|^  - ' "$WORK/purge.log" || true
grep -q 'removing the sandbox container storage /var/lib/agern/containers' "$WORK/purge.log" && pass "purge says it removes the sandbox storage" || fail "purge: sandbox storage message"
check "sandbox storage removed" test ! -e /var/lib/agern/containers
check "the rest of the data directory is kept" test -e /var/lib/agern/keep-me
check "subuid range of agern removed with the user" bash -c '! grep -q "^agern:" /etc/subuid'
check "subgid range of agern removed with the user" bash -c '! grep -q "^agern:" /etc/subgid'
check "other users' ranges kept" grep -q '^alice:100000:65536$' /etc/subuid
for u in agern-sandbox.socket agern-sandbox.service agern-sandbox-prune.timer; do
    check "$u removed" test ! -e "/usr/lib/systemd/system/$u"
done
check "configuration removed" test ! -e /etc/agern/agern.env
check "/etc/agern removed" test ! -e /etc/agern
check "package cache removed" test ! -e /var/cache/agern
check "user removed" bash -c '! getent passwd agern'
check "group removed" bash -c '! getent group agern'
check "data directory left in place" test -d /var/lib/agern
backup=$(find /var/backups -maxdepth 1 -name 'agern.env.purged-*' | head -n 1)
check "a root-only copy of the configuration is in /var/backups" test -n "$backup"
[ -n "$backup" ] && eq "backup permissions" "root:root 600" "$(stat_of "$backup")"
[ -n "$backup" ] && eq "backup has the master key" "$KEY_BEFORE" "$(grep '^FACTORY_MASTER_KEY=' "$backup")"
grep -q 'left in place' "$WORK/purge.log" && pass "purge says what it left behind" || fail "purge message"
eq "package gone" "" "$(dpkg-query -W -f='${Status}' agern 2>/dev/null || true)"

printf '\n%s: %d passed, %d failed\n' "$PRETTY_NAME" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
