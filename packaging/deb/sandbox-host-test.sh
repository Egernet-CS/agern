#!/usr/bin/env bash
# Sandbox test on a real systemd host (a throwaway VM: it installs packages, creates users, starts
# services and purges the package at the end). Used by CI on GitHub's Ubuntu 24.04 runners and by
# hand on Debian 12/13 VMs, which hosted runners do not offer:
#
#   sudo bash packaging/deb/sandbox-host-test.sh build/release/agern_<version>_all.deb [--it .]
#
# What it proves, under the packaged units: the .deb and rootless Podman install with apt;
# 'agern sandbox enable' allocates subordinate ids, starts agern-sandbox.socket/.service and passes
# its self-test (image pull, private network with a sidecar reached by name, limits in the
# container's cgroup, bind mount ownership, a memory hog killed); the socket is the agern user's
# alone; 'agern doctor' has no sandbox problem; the SERVER, started by the hardened agern.service
# (NoNewPrivileges, empty capability bounding set), reaches the socket and removes leftover
# sandboxes at startup; with --it REPO, the PodmanRuntime integration test
# (server/src/test/.../PodmanIntegrationTest.kt) runs as the agern user INSIDE agern.service
# (ExecStart replaced by a runtime drop-in), against the unit's socket; an upgrade keeps sandboxes
# on without restarting the sandbox service; status and prune; 'agern sandbox disable'; purge.
#
# Environment: AGERN_SANDBOX_TEST_IMAGE / AGERN_PODMAN_IT_IMAGE (default docker.io/library/busybox:1.36.1);
# AGERN_IT_GRADLE_CACHE = a Gradle home whose downloaded dependencies seed the agern user's.

# shellcheck disable=SC2015 # 'test && pass || fail': pass always succeeds
set -euo pipefail

DEB=${1:?usage: sandbox-host-test.sh path/to/agern_<version>_all.deb [--it REPO_DIR]}
shift
IT_SRC=""
while [ $# -gt 0 ]; do
    case $1 in
        --it) shift; IT_SRC=$(cd -P "${1:?--it needs the repository directory}" && pwd -P) ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
done

PASS=0
FAIL=0
pass() { printf 'PASS  %s\n' "$*"; PASS=$((PASS + 1)); }
fail() { printf 'FAIL  %s\n' "$*"; FAIL=$((FAIL + 1)); }
check() { local d=$1; shift; if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi; }
eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$2', got '$3')"; fi; }
section() { printf '\n== %s\n' "$*"; }
as_agern() { (cd / && runuser -u agern -- env -i HOME=/var/lib/agern PATH=/usr/sbin:/usr/bin:/sbin:/bin "$@"); }
spodman() { as_agern podman --url unix:///run/agern-podman/podman.sock "$@"; }
setenv() { # setenv KEY VALUE in agern.env
    if grep -q "^$1=" /etc/agern/agern.env; then sed -i "s|^$1=.*|$1=$2|" /etc/agern/agern.env; else printf '%s=%s\n' "$1" "$2" >>/etc/agern/agern.env; fi
}
journal_since() { journalctl -u "$1" --since "@$2" -o cat --no-pager 2>/dev/null || true; }

export DEBIAN_FRONTEND=noninteractive
[ "$(id -u)" -eq 0 ] || { echo "run as root (on a throwaway VM)" >&2; exit 2; }
[ -d /run/systemd/system ] || { echo "needs a host booted with systemd" >&2; exit 2; }
# shellcheck source=/dev/null
. /etc/os-release
echo "sandbox host test on $PRETTY_NAME ($(uname -m), kernel $(uname -r))"
for s in user.max_user_namespaces kernel.unprivileged_userns_clone kernel.apparmor_restrict_unprivileged_userns; do
    printf '  %s = %s\n' "$s" "$(sysctl -n "$s" 2>/dev/null || echo n/a)"
done
WORK=$(mktemp -d /tmp/agern-sandbox-test.XXXXXX)
cp "$DEB" "$WORK/a.deb"
TEST_IMAGE=${AGERN_SANDBOX_TEST_IMAGE:-docker.io/library/busybox:1.36.1}

section "install the package (apt) and PostgreSQL"
apt-get update -qq
if apt-get install -y -q "$WORK/a.deb" postgresql >"$WORK/install.log" 2>&1; then pass "apt-get install agern postgresql"; else fail "apt-get install"; tail -n 40 "$WORK/install.log"; exit 1; fi
echo "installed: agern $(dpkg-query -W -f='${Version}' agern)"
check "no sandbox unit enabled by the install" test ! -e /etc/systemd/system/sockets.target.wants/agern-sandbox.socket
if ! command -v podman >/dev/null 2>&1; then
    set +e
    out=$(agern sandbox enable 2>&1)
    rc=$?
    set -e
    [ "$rc" != 0 ] && printf '%s' "$out" | grep -q 'sudo apt install podman' && pass "enable without Podman: install hint" || fail "enable without Podman: $out"
fi

section "rootless Podman packages"
# passt too, although Agern does not use it: it is what 'apt install podman' brings on Debian 13, and
# the sandboxes must work with it present (its AppArmor profile there does not allow our paths).
PKGS="podman uidmap netavark aardvark-dns crun slirp4netns passt"
# shellcheck disable=SC2086 # a list of package names
if apt-get install -y -q $PKGS >"$WORK/podman.log" 2>&1; then pass "apt-get install $PKGS"; else fail "apt-get install $PKGS"; tail -n 40 "$WORK/podman.log"; exit 1; fi
# shellcheck disable=SC2086
dpkg-query -W -f='  ${Package} ${Version}\n' $PKGS conmon golang-github-containers-common systemd 2>/dev/null || true

section "configure: native PostgreSQL, organization, LDAP placeholder (no logins needed)"
systemctl enable --now postgresql >/dev/null 2>&1 || true
if agern setup-db >"$WORK/db.log" 2>&1; then pass "agern setup-db"; else fail "agern setup-db"; cat "$WORK/db.log"; fi
setenv FACTORY_GITHUB_ORG sandbox-test-org
setenv FACTORY_PUBLIC_URL http://127.0.0.1:8080
setenv FACTORY_AUTH ldap
setenv FACTORY_LDAP_URL ldap://127.0.0.1:1
setenv FACTORY_LDAP_USER_DN_TEMPLATE 'uid={0},dc=example'
[ -n "${AGERN_SANDBOX_TEST_IMAGE:-}" ] && setenv AGERN_SANDBOX_TEST_IMAGE "$AGERN_SANDBOX_TEST_IMAGE"
case $TEST_IMAGE in docker.io/* | ghcr.io/* | quay.io/* | mcr.microsoft.com/*) ;; *) setenv FACTORY_SANDBOX_REGISTRIES "docker.io,ghcr.io,quay.io,mcr.microsoft.com,${TEST_IMAGE%%/*}" ;; esac
systemctl stop agern.service >/dev/null 2>&1 || true

section "agern sandbox enable"
SUBUID_BEFORE=$(grep -c . /etc/subuid 2>/dev/null || echo 0)
if agern sandbox enable >"$WORK/enable.log" 2>&1; then pass "agern sandbox enable"; ENABLED=1; else fail "agern sandbox enable"; ENABLED=0; fi
cat "$WORK/enable.log"
if [ "$ENABLED" = 0 ]; then
    # Everything after this needs sandboxes on: report and stop instead of a cascade of failures.
    section "diagnostics (enable failed)"
    journalctl -u agern-sandbox.service -n 60 --no-pager -o cat || true
    journalctl -k --no-pager -o cat 2>/dev/null | grep -i 'apparmor="DENIED"' | tail -n 20 || true
    printf '\n%s (sandbox host test): %d passed, %d failed (stopped after a failed enable)\n' "$PRETTY_NAME" "$PASS" "$FAIL"
    exit 1
fi
grep -q '  FAIL' "$WORK/enable.log" && fail "the self-test reported a problem" || pass "self-test without problems"
for s in "rootless Podman" "cgroups v2" "network backend netavark" "limits applied in the container's cgroup" \
    "belongs to agern on the host" "reachable by name" "is killed" "survived the killed process"; do
    grep -q "ok    .*$s" "$WORK/enable.log" && pass "self-test: $s" || fail "self-test: $s"
done
check "FACTORY_SANDBOX=podman" grep -qx 'FACTORY_SANDBOX=podman' /etc/agern/agern.env
check "FACTORY_SANDBOX_PODMAN_URL" grep -qx 'FACTORY_SANDBOX_PODMAN_URL=unix:///run/agern-podman/podman.sock' /etc/agern/agern.env
CG=$(systemctl show -p ControlGroup --value agern-sandbox.service)
eq "FACTORY_SANDBOX_CGROUP_PARENT" "FACTORY_SANDBOX_CGROUP_PARENT=$CG/containers" "$(grep '^FACTORY_SANDBOX_CGROUP_PARENT=' /etc/agern/agern.env)"
eq "agern.env permissions" "root:agern 640" "$(stat -c '%U:%G %a' /etc/agern/agern.env)"
check "agern has a subuid range" grep -Eq '^agern:[0-9]+:65536$' /etc/subuid
check "agern has a subgid range" grep -Eq '^agern:[0-9]+:65536$' /etc/subgid
eq "other subuid entries untouched" "$((SUBUID_BEFORE + 1))" "$(grep -c . /etc/subuid)"
check "agern-sandbox.socket enabled" systemctl is-enabled --quiet agern-sandbox.socket
check "agern-sandbox.service running" systemctl is-active --quiet agern-sandbox.service
check "agern-sandbox-prune.timer enabled" systemctl is-enabled --quiet agern-sandbox-prune.timer
eq "socket owner and mode" "agern:agern 600" "$(stat -c '%U:%G %a' /run/agern-podman/podman.sock)"
check "the agern user can use the socket" as_agern curl -fsS --unix-socket /run/agern-podman/podman.sock http://d/_ping
check "another user cannot" bash -c '! runuser -u nobody -- curl -fsS --unix-socket /run/agern-podman/podman.sock http://d/_ping'
eq "sandbox service runs as agern" agern "$(ps -o user= -p "$(systemctl show -p MainPID --value agern-sandbox.service)")"
eq "cgroup delegated to agern" agern "$(stat -c %U "/sys/fs/cgroup$CG")"
sc=$(cat "/sys/fs/cgroup$CG/cgroup.subtree_control")
for c in cpu memory pids; do case " $sc " in *" $c "*) pass "controller $c enabled below $CG" ;; *) fail "controller $c not enabled below $CG ($sc)" ;; esac; done
eq "agern.service keeps NoNewPrivileges" yes "$(systemctl show -p NoNewPrivileges --value agern.service)"
eq "agern.service keeps an empty capability bounding set" "" "$(systemctl show -p CapabilityBoundingSet --value agern.service)"
eq "the sandbox unit has no NoNewPrivileges (newuidmap)" no "$(systemctl show -p NoNewPrivileges --value agern-sandbox.service)"
check "storage owned by agern, 700" test "$(stat -c '%U %a' /var/lib/agern/containers)" = "agern 700"
check "images stored under /var/lib/agern/containers" test -d /var/lib/agern/containers/storage
out=$(agern sandbox enable 2>&1) && printf '%s' "$out" | grep -q 'already had these settings' && pass "enable again: idempotent" || fail "enable again: $out"
eq "subuid unchanged by the second enable" "$((SUBUID_BEFORE + 1))" "$(grep -c . /etc/subuid)"

section "agern doctor"
set +e
doc=$(agern doctor 2>&1)
set -e
printf '%s\n' "$doc" | sed -n '/^sandboxes$/,/^service$/p'
sbx=$(printf '%s\n' "$doc" | sed -n '/^sandboxes$/,/^service$/p')
printf '%s' "$sbx" | grep -q 'ok    no AppArmor denials for agern' && pass "doctor: no AppArmor denials for agern" || fail "doctor: AppArmor denials"
printf '%s' "$sbx" | grep -q 'FAIL' && fail "doctor: sandbox problems" || pass "doctor: no sandbox problem"
printf '%s' "$sbx" | grep -q 'ok    controllers cpu, memory, pids enabled' && pass "doctor: limits effective" || fail "doctor: limits"
printf '%s' "$sbx" | grep -q 'ok    agern reaches the Podman service' && pass "doctor: agern reaches the service" || fail "doctor: reach"

section "the server, under the hardened agern.service, reaches the sandbox service"
spodman pull --quiet "$TEST_IMAGE" >/dev/null
LEFT=$(cat /proc/sys/kernel/random/uuid)
spodman network create --label "agern.run=$LEFT" "agern-run-$LEFT" >/dev/null
spodman run --detach --name "agern-run-$LEFT" --label "agern.run=$LEFT" --network "agern-run-$LEFT" "$TEST_IMAGE" sleep 600 >/dev/null
check "a leftover sandbox of an unknown run exists" spodman container exists "agern-run-$LEFT"
T0=$(date +%s)
if agern start >"$WORK/start.log" 2>&1; then pass "agern start (health)"; else fail "agern start"; cat "$WORK/start.log"; journal_since agern.service "$T0" | tail -n 60; fi
gone=0
for _ in $(seq 1 60); do
    if ! spodman container exists "agern-run-$LEFT" && ! spodman network exists "agern-run-$LEFT"; then gone=1; break; fi
    sleep 1
done
eq "the server's reaper removed the leftover container and network" 1 "$gone"
journal_since agern.service "$T0" | grep -q 'Removed 1 leftover sandbox container(s) and 1 network(s)' && pass "server log: reaper" || { fail "server log: reaper"; journal_since agern.service "$T0" | tail -n 40; }
PID=$(systemctl show -p MainPID --value agern.service)
eq "server process: NoNewPrivs" 1 "$(awk '/^NoNewPrivs:/ {print $2}' "/proc/$PID/status")"
eq "server process: capability bounding set" 0000000000000000 "$(awk '/^CapBnd:/ {print $2}' "/proc/$PID/status")"
eq "server process user" agern "$(ps -o user= -p "$PID")"

if [ -n "$IT_SRC" ]; then
    section "PodmanRuntime integration test as agern inside agern.service"
    agern stop >/dev/null
    jdk=""
    for j in /usr/lib/jvm/temurin-17-jdk-* /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/temurin-21-jdk-* /usr/lib/jvm/java-21-openjdk-*; do
        [ -x "$j/bin/javac" ] && { jdk=$j; break; }
    done
    if [ -z "$jdk" ]; then
        apt-get install -y -q default-jdk-headless >/dev/null 2>&1
        jdk=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
    fi
    echo "JDK for Gradle: $jdk"
    IT=/var/lib/agern/sandbox-it
    rm -rf "$IT"
    as_agern mkdir -p "$IT"
    # Optional: a Gradle home with the dependencies already downloaded (CI passes the runner's, which
    # gradle/actions/setup-gradle caches), so the agern user does not fetch them again.
    if [ -n "${AGERN_IT_GRADLE_CACHE:-}" ] && [ -d "$AGERN_IT_GRADLE_CACHE/caches/modules-2" ]; then
        as_agern mkdir -p /var/lib/agern/.gradle/caches
        tar -C "$AGERN_IT_GRADLE_CACHE" -cf - caches/modules-2 wrapper 2>/dev/null | as_agern tar -C /var/lib/agern/.gradle -xf - || true
        echo "seeded the agern user's Gradle home from $AGERN_IT_GRADLE_CACHE"
    fi
    tar -C "$IT_SRC" --exclude=./.git --exclude=./web --exclude=./build --exclude=./server/build --exclude=./.gradle --exclude=./node_modules -cf - . |
        as_agern tar -C "$IT" -xf -
    mkdir -p /run/systemd/system/agern.service.d
    cat >/run/systemd/system/agern.service.d/zz-sandbox-it.conf <<EOF
[Service]
ExecStart=
ExecStart=$IT/gradlew --no-daemon --console=plain -p $IT :server:test --tests dk.egernet.factory.PodmanIntegrationTest
Restart=no
Environment=AGERN_PODMAN_IT=1
Environment=AGERN_PODMAN_IT_DIR=/var/lib/agern
Environment=AGERN_PODMAN_IT_IMAGE=${AGERN_PODMAN_IT_IMAGE:-$TEST_IMAGE}
Environment=JAVA_HOME=$jdk
EOF
    systemctl daemon-reload
    T0=$(date +%s)
    systemctl reset-failed agern.service >/dev/null 2>&1 || true
    systemctl start agern.service
    for _ in $(seq 1 1800); do
        systemctl is-active --quiet agern.service || break
        sleep 1
    done
    status=$(systemctl show -p ExecMainStatus --value agern.service)
    journal_since agern.service "$T0" | tail -n 120
    xml="$IT/server/build/test-results/test/TEST-dk.egernet.factory.PodmanIntegrationTest.xml"
    eq "gradle (as agern in agern.service) exit status" 0 "$status"
    if [ -f "$xml" ]; then
        head -n 2 "$xml" | tail -n 1
        sed -n '/<system-out>/,/<\/system-out>/p' "$xml" | head -n 40
        grep -qE 'tests="[1-9][0-9]*" skipped="0" failures="0" errors="0"' "$xml" && pass "PodmanIntegrationTest ran and passed" || { fail "PodmanIntegrationTest"; sed -n '/<failure/,/<\/failure>/p' "$xml" | head -n 60; }
    else
        fail "no test report at $xml"
    fi
    echo "--- kernel OOM messages during the test:"
    journalctl -k --since "@$T0" --no-pager -o cat 2>/dev/null | grep -iE 'oom|killed process' | tail -n 20 || true
    rm -f /run/systemd/system/agern.service.d/zz-sandbox-it.conf
    systemctl daemon-reload
    systemctl reset-failed agern.service >/dev/null 2>&1 || true
    agern start >/dev/null 2>&1 && pass "server back up after the integration test" || fail "server back up"
fi

section "an upgrade keeps sandboxes on (and leaves the sandbox service running)"
SPID=$(systemctl show -p MainPID --value agern-sandbox.service)
ENV_SUM=$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)
SUBID_SUM=$(cat /etc/subuid /etc/subgid | sha256sum | cut -d' ' -f1)
A_SEMVER=$(cat /usr/share/agern/VERSION)
# A real upgrade: the package under test as a higher version, with the version inside agern.jar
# changed too (the health check after the restart compares it).
B_SEMVER=${A_SEMVER%%[-+]*}
B_SEMVER="${B_SEMVER%.*}.$((${B_SEMVER##*.} + 1))"
rm -rf "$WORK/repack"
dpkg-deb -R "$WORK/a.deb" "$WORK/repack"
sed -i "s/^Version: .*/Version: $B_SEMVER/" "$WORK/repack/DEBIAN/control"
printf '%s\n' "$B_SEMVER" >"$WORK/repack/usr/share/agern/VERSION"
command -v python3 >/dev/null 2>&1 || apt-get install -y -q python3 >/dev/null
python3 -I - "$WORK/repack/usr/share/agern/agern.jar" "$B_SEMVER" <<'PY'
import sys, zipfile, shutil
jar, version = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as src, zipfile.ZipFile(jar + ".new", "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        data = src.read(item.filename)
        if item.filename == "agern-version.txt":
            data = (version + "\n").encode()
        dst.writestr(item, data)
shutil.move(jar + ".new", jar)
PY
(cd "$WORK/repack" && find usr -type f | LC_ALL=C sort | xargs md5sum) >"$WORK/repack/DEBIAN/md5sums"
dpkg-deb --build --root-owner-group -Zxz "$WORK/repack" "$WORK/b.deb" >/dev/null
SRV_PID=$(systemctl show -p MainPID --value agern.service)
T0=$(date +%s)
if agern upgrade --file "$WORK/b.deb" >"$WORK/upgrade.log" 2>&1; then pass "agern upgrade --file (to $B_SEMVER)"; else fail "agern upgrade"; fi
grep -v -E '^\(Reading database|^$' "$WORK/upgrade.log" || true
eq "now $B_SEMVER" "$B_SEMVER" "$(dpkg-query -W -f='${Version}' agern)"
echo "--- agern.service around the upgrade:"
journal_since agern.service "$T0" | grep -E 'Stopp|Start|Deactivated|exited' || true
echo "--- agern.service: MainPID $SRV_PID before, $(systemctl show -p MainPID --value agern.service) after; is-enabled $(systemctl is-enabled agern.service 2>&1)"
check "the server was restarted by the upgrade" test "$SRV_PID" != "$(systemctl show -p MainPID --value agern.service)"
eq "agern.env unchanged by the upgrade" "$ENV_SUM" "$(sha256sum /etc/agern/agern.env | cut -d' ' -f1)"
eq "subordinate ids unchanged by the upgrade" "$SUBID_SUM" "$(cat /etc/subuid /etc/subgid | sha256sum | cut -d' ' -f1)"
check "socket still enabled" systemctl is-enabled --quiet agern-sandbox.socket
eq "sandbox service not restarted by the upgrade" "$SPID" "$(systemctl show -p MainPID --value agern-sandbox.service)"
if agern sandbox selftest >"$WORK/selftest.log" 2>&1; then pass "agern sandbox selftest after the upgrade"; else fail "selftest after upgrade"; fi
cat "$WORK/selftest.log"
[ "$(curl -fsS http://127.0.0.1:8080/api/health 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')" = "$B_SEMVER" ] && pass "server healthy on $B_SEMVER" || fail "server after upgrade"

section "status and prune"
out=$(agern sandbox status 2>&1) || true
printf '%s\n' "$out"
printf '%s' "$out" | grep -q '^sandboxes: *podman$' && pass "status: podman" || fail "status: mode"
printf '%s' "$out" | grep -q '^service: *Podman ' && pass "status: service version" || fail "status: service"
out=$(runuser -u nobody -- agern sandbox status 2>&1) || true
printf '%s' "$out" | grep -q '^containers: *unknown (only root and agern' && pass "status without root: says what it cannot read" || fail "status as nobody: $out"
spodman volume create --label agern.repo=it --label agern.cache=/root/.cache agern-cache-it-test >/dev/null
spodman volume create agern-dangling-test >/dev/null
setenv AGERN_SANDBOX_PRUNE_HOURS 0
T0=$(date +%s)
systemctl start agern-sandbox-prune.service && pass "agern-sandbox-prune.service (as agern)" || fail "prune service"
journal_since agern-sandbox-prune.service "$T0"
check "prune removed the unused anonymous-style volume" bash -c '! runuser -u agern -- env -i HOME=/var/lib/agern PATH=/usr/bin:/bin podman --url unix:///run/agern-podman/podman.sock volume exists agern-dangling-test'
check "prune kept the cache volume" spodman volume exists agern-cache-it-test
check "prune removed the unused image" bash -c "! runuser -u agern -- env -i HOME=/var/lib/agern PATH=/usr/bin:/bin podman --url unix:///run/agern-podman/podman.sock image exists $TEST_IMAGE"
sed -i '/^AGERN_SANDBOX_PRUNE_HOURS=/d' /etc/agern/agern.env

section "agern sandbox disable"
spodman run --detach --name agern-run-disable-test --label agern.run=disable-test "$TEST_IMAGE" sleep 600 >/dev/null
if agern sandbox disable >"$WORK/disable.log" 2>&1; then pass "agern sandbox disable"; else fail "agern sandbox disable"; fi
cat "$WORK/disable.log"
check "FACTORY_SANDBOX=off" grep -qx 'FACTORY_SANDBOX=off' /etc/agern/agern.env
check "URL removed" bash -c '! grep -q "^FACTORY_SANDBOX_PODMAN_URL=" /etc/agern/agern.env'
check "socket disabled" bash -c '! systemctl is-enabled --quiet agern-sandbox.socket'
check "socket stopped" bash -c '! systemctl is-active --quiet agern-sandbox.socket'
check "service stopped" bash -c '! systemctl is-active --quiet agern-sandbox.service'
check "prune timer disabled" bash -c '! systemctl is-enabled --quiet agern-sandbox-prune.timer'
grep -q 'removed 1 sandbox container' "$WORK/disable.log" && pass "disable removed the sandbox container" || fail "disable: containers"
check "storage kept (cache volumes)" test -d /var/lib/agern/containers/storage/volumes/agern-cache-it-test
check "server healthy with sandboxes off" curl -fsS http://127.0.0.1:8080/api/health

section "purge"
apt-get purge -y -q agern >"$WORK/purge.log" 2>&1 && pass "apt-get purge agern" || { fail "apt-get purge"; cat "$WORK/purge.log"; }
grep '^agern:\|^  - ' "$WORK/purge.log" || true
check "sandbox storage removed" test ! -e /var/lib/agern/containers
check "subuid range removed" bash -c '! grep -q "^agern:" /etc/subuid'
check "units removed" test ! -e /usr/lib/systemd/system/agern-sandbox.service
check "no agern unit left running" bash -c '! systemctl is-active --quiet agern.service agern-sandbox.service agern-sandbox.socket'

printf '\n%s (sandbox host test): %d passed, %d failed\n' "$PRETTY_NAME" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
