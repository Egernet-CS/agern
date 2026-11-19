#!/usr/bin/env bash
# Install or update Agern (the software factory) on this host.
#
# From a fresh host (downloads the latest release, verifies its checksum, installs):
#   curl -fsSLO https://github.com/Egernet-CS/agern/releases/latest/download/install.sh
#   sudo bash install.sh                  # or: sudo bash install.sh --version 1.2.3
#
# From an unpacked release tarball:
#   sudo ./install.sh
#
# What it does (as root):
#   - creates the unprivileged service user 'agern' (never root) if it does not exist
#   - unpacks the release to /opt/agern/versions/<version> and points /opt/agern/current at it
#     (the version that was current becomes /opt/agern/previous, for 'agern rollback')
#   - creates /etc/agern/agern.env on first install, with a freshly generated FACTORY_MASTER_KEY
#     (mode 0600; never overwritten afterwards, the key is never printed)
#   - creates the data directory (/var/lib/agern on Linux, /usr/local/var/agern on macOS)
#   - registers a systemd service (Linux) or a launchd daemon (macOS)
#   - on an update: restarts and checks /api/health, switching back automatically on failure
#
# Environment overrides:
#   AGERN_PREFIX       install prefix (default /opt/agern). Without root it is required, and the
#                      install is self-contained under it: config in <prefix>/etc, data in
#                      <prefix>/data, logs in <prefix>/log, no service manager (pid file).
#   AGERN_CONF_DIR, AGERN_DATA_DIR, AGERN_LOG_DIR, AGERN_USER, AGERN_SERVICE (systemd|launchd|none),
#   AGERN_SERVICE_PATH (PATH for the service), AGERN_RELEASE_BASE_URL (download location).

set -euo pipefail

DEFAULT_BASE="https://github.com/Egernet-CS/agern/releases"
LAUNCHD_LABEL="dk.egernet.agern"
SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'

info() { printf 'install: %s\n' "$*"; }
warn() { printf 'install: warning: %s\n' "$*" >&2; }
die()  { printf 'install: error: %s\n' "$*" >&2; exit 1; }

UPGRADE=0
WANT_VERSION=${AGERN_VERSION:-}
while [ $# -gt 0 ]; do
    case $1 in
        --upgrade) UPGRADE=1 ;;
        --version) shift; WANT_VERSION=${1:?--version needs a value} ;;
        --version=*) WANT_VERSION=${1#--version=} ;;
        -h | --help) sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done
WANT_VERSION=${WANT_VERSION#v}

SRC_DIR=$(cd -P "$(dirname "$0")" && pwd -P)
OS=$(uname -s)
IS_ROOT=0
[ "$(id -u)" -eq 0 ] && IS_ROOT=1
if [ "$IS_ROOT" = 0 ] && [ -z "${AGERN_PREFIX:-}" ]; then
    die "run as root (sudo), or set AGERN_PREFIX for a self-contained unprivileged install"
fi

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    fi
}

# ---------------------------------------------------------------------------------------------
# Bootstrap: this script was downloaded on its own. Fetch the release it belongs to and run the
# installer inside it.

if [ ! -f "$SRC_DIR/agern.jar" ] || [ ! -f "$SRC_DIR/VERSION" ]; then
    if [ -n "$WANT_VERSION" ] && ! [[ $WANT_VERSION =~ $SEMVER_RE ]]; then die "not a version: $WANT_VERSION"; fi
    command -v curl >/dev/null 2>&1 || die "curl is required"
    command -v tar >/dev/null 2>&1 || die "tar is required"
    base=${AGERN_RELEASE_BASE_URL:-$DEFAULT_BASE}
    base=${base%/}
    if [ -n "$WANT_VERSION" ]; then dl="$base/download/v$WANT_VERSION"; else dl="$base/latest/download"; fi
    tmp=$(mktemp -d "${TMPDIR:-/tmp}/agern-install.XXXXXX")
    trap 'rm -rf "$tmp"' EXIT
    info "fetching $dl/SHA256SUMS"
    curl -fsSL --retry 3 -o "$tmp/SHA256SUMS" "$dl/SHA256SUMS" || die "download failed: $dl/SHA256SUMS"
    tarball=$(awk '{sub(/^\*/, "", $2); print $2}' "$tmp/SHA256SUMS" | grep -E '^agern-.+\.tar\.gz$' | head -n 1 || true)
    [ -n "$tarball" ] || die "SHA256SUMS lists no agern-<version>.tar.gz"
    version=${tarball#agern-}
    version=${version%.tar.gz}
    [[ $version =~ $SEMVER_RE ]] || die "unexpected release file name: $tarball"
    info "downloading $dl/$tarball"
    curl -fsSL --retry 3 -o "$tmp/$tarball" "$dl/$tarball" || die "download failed: $dl/$tarball"
    expected=$(awk -v f="$tarball" '{n=$2; sub(/^\*/, "", n); if (n == f) {print $1; exit}}' "$tmp/SHA256SUMS")
    actual=$(sha256_of "$tmp/$tarball")
    if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then die "checksum mismatch for $tarball (expected ${expected:-none}, got $actual)"; fi
    info "checksum OK"
    mkdir "$tmp/x"
    tar -xzf "$tmp/$tarball" -C "$tmp/x"
    [ -f "$tmp/x/agern-$version/install.sh" ] || die "$tarball does not look like an Agern release"
    args=()
    [ "$UPGRADE" = 1 ] && args+=(--upgrade)
    bash "$tmp/x/agern-$version/install.sh" ${args[@]+"${args[@]}"}
    exit $?
fi

VERSION=$(tr -d '[:space:]' <"$SRC_DIR/VERSION")
[[ $VERSION =~ $SEMVER_RE ]] || die "bad VERSION file: $VERSION"
if [ -n "$WANT_VERSION" ] && [ "$WANT_VERSION" != "$VERSION" ]; then
    die "this is release $VERSION, not $WANT_VERSION"
fi

# ---------------------------------------------------------------------------------------------
# Settings: explicit environment > existing <prefix>/agern.conf > defaults.

PREFIX=${AGERN_PREFIX:-/opt/agern}
case $PREFIX in /*) ;; *) PREFIX="$(pwd -P)/$PREFIX" ;; esac
PREFIX=${PREFIX%/}

env_conf_dir=${AGERN_CONF_DIR:-}
env_env_file=${AGERN_ENV_FILE:-}
env_data_dir=${AGERN_DATA_DIR:-}
env_log_dir=${AGERN_LOG_DIR:-}
env_user=${AGERN_USER:-}
env_service=${AGERN_SERVICE:-}
unset AGERN_ENV_FILE AGERN_DATA_DIR AGERN_LOG_DIR AGERN_USER AGERN_SERVICE
if [ -r "$PREFIX/agern.conf" ]; then
    # shellcheck source=/dev/null
    . "$PREFIX/agern.conf"
elif [ "$UPGRADE" = 1 ]; then
    die "no existing installation under $PREFIX"
fi

if [ "$IS_ROOT" = 1 ]; then
    d_conf=/etc/agern
    d_user=agern
    if [ "$OS" = Darwin ]; then
        d_data=/usr/local/var/agern
        d_log=/usr/local/var/log/agern
        d_service=launchd
    else
        d_data=/var/lib/agern
        d_log=/var/log/agern
        if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then d_service=systemd; else d_service=none; fi
    fi
else
    d_conf="$PREFIX/etc"
    d_data="$PREFIX/data"
    d_log="$PREFIX/log"
    d_user=$(id -un)
    d_service=none
fi

if [ -n "$env_env_file" ]; then ENV_FILE=$env_env_file
elif [ -n "$env_conf_dir" ]; then ENV_FILE="$env_conf_dir/agern.env"
else ENV_FILE=${AGERN_ENV_FILE:-$d_conf/agern.env}; fi
DATA_DIR=${env_data_dir:-${AGERN_DATA_DIR:-$d_data}}
LOG_DIR=${env_log_dir:-${AGERN_LOG_DIR:-$d_log}}
SVC_USER=${env_user:-${AGERN_USER:-$d_user}}
SERVICE=${env_service:-${AGERN_SERVICE:-$d_service}}

[ "$SVC_USER" != root ] || die "the service user must not be root (agents run as that user)"
case $SERVICE in systemd | launchd | none) ;; *) die "AGERN_SERVICE must be systemd, launchd or none" ;; esac
if [ "$SERVICE" != none ] && [ "$IS_ROOT" = 0 ]; then die "registering a $SERVICE service needs root"; fi
if [ "$IS_ROOT" = 0 ] && [ "$SVC_USER" != "$(id -un)" ]; then die "without root the service user can only be $(id -un)"; fi

if [ "$OS" = Darwin ]; then
    d_path=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
else
    d_path=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
fi
SERVICE_PATH=${AGERN_SERVICE_PATH:-$d_path}

info "installing Agern $VERSION into $PREFIX (service user $SVC_USER, service manager $SERVICE)"

# ---------------------------------------------------------------------------------------------
# Prerequisites (warnings only — 'agern doctor' checks everything in detail)

java_bin=${JAVA_HOME:+$JAVA_HOME/bin/java}
[ -n "$java_bin" ] && [ -x "$java_bin" ] || java_bin=$(command -v java || true)
if [ -z "$java_bin" ]; then
    warn "java not found; Agern needs Java 17+ (set AGERN_JAVA in $ENV_FILE if it is not on the PATH)"
fi
command -v curl >/dev/null 2>&1 || warn "curl not found; 'agern upgrade' needs it"

# ---------------------------------------------------------------------------------------------
# Service user (root only)

if [ "$IS_ROOT" = 1 ] && ! id "$SVC_USER" >/dev/null 2>&1; then
    info "creating system user $SVC_USER"
    if [ "$OS" = Darwin ]; then
        used=$( (dscl . -list /Users UniqueID; dscl . -list /Groups PrimaryGroupID) | awk '{print $2}')
        new_id=""
        for i in $(seq 499 -1 400); do
            if ! printf '%s\n' "$used" | grep -qx "$i"; then new_id=$i; break; fi
        done
        [ -n "$new_id" ] || die "no free id in 400-499 for the $SVC_USER user"
        if ! dscl . -read "/Groups/$SVC_USER" >/dev/null 2>&1; then
            dscl . -create "/Groups/$SVC_USER"
            dscl . -create "/Groups/$SVC_USER" PrimaryGroupID "$new_id"
            dscl . -create "/Groups/$SVC_USER" RealName "Agern"
            dscl . -create "/Groups/$SVC_USER" Password '*'
        fi
        gid=$(dscl . -read "/Groups/$SVC_USER" PrimaryGroupID | awk '{print $2}')
        dscl . -create "/Users/$SVC_USER"
        dscl . -create "/Users/$SVC_USER" UniqueID "$new_id"
        dscl . -create "/Users/$SVC_USER" PrimaryGroupID "$gid"
        dscl . -create "/Users/$SVC_USER" UserShell /usr/bin/false
        dscl . -create "/Users/$SVC_USER" NFSHomeDirectory "$DATA_DIR"
        dscl . -create "/Users/$SVC_USER" RealName "Agern"
        dscl . -create "/Users/$SVC_USER" Password '*'
        dscl . -create "/Users/$SVC_USER" IsHidden 1
    else
        nologin=/usr/sbin/nologin
        [ -x "$nologin" ] || nologin=/sbin/nologin
        getent group "$SVC_USER" >/dev/null 2>&1 || groupadd --system "$SVC_USER"
        useradd --system --gid "$SVC_USER" --home-dir "$DATA_DIR" --no-create-home \
            --shell "$nologin" --comment "Agern" "$SVC_USER"
    fi
fi
SVC_GROUP=$(id -gn "$SVC_USER")

# ---------------------------------------------------------------------------------------------
# Files

umask 022
mkdir -p "$PREFIX/versions" "$PREFIX/bin" "$PREFIX/run"
dest="$PREFIX/versions/$VERSION"
staging="$PREFIX/versions/.staging-$VERSION.$$"
rm -rf "$staging"
mkdir -p "$staging/service"
cp "$SRC_DIR/agern.jar" "$SRC_DIR/VERSION" "$staging/"
cp "$SRC_DIR/agern" "$SRC_DIR/install.sh" "$staging/"
cp "$SRC_DIR/.env.example" "$staging/env.example"
cp "$SRC_DIR"/service/* "$staging/service/"
chmod 0644 "$staging/agern.jar" "$staging/VERSION" "$staging/env.example" "$staging"/service/*
chmod 0755 "$staging/agern" "$staging/install.sh"
if [ "$(readlink "$PREFIX/current" 2>/dev/null || true)" = "versions/$VERSION" ]; then
    # Reinstalling the running version: replace its files in place.
    rm -rf "$dest.old"
    [ -d "$dest" ] && mv "$dest" "$dest.old"
    mv "$staging" "$dest"
    rm -rf "$dest.old"
else
    rm -rf "$dest"
    mv "$staging" "$dest"
fi
if [ "$IS_ROOT" = 1 ]; then
    # Code is root-owned and read-only to the service user (and thus to agents).
    chown -R root "$PREFIX/versions" "$PREFIX/bin"
fi

mkdir -p "$DATA_DIR" "$LOG_DIR"
chmod 0750 "$DATA_DIR" "$LOG_DIR"
if [ "$IS_ROOT" = 1 ]; then
    chown "$SVC_USER:$SVC_GROUP" "$DATA_DIR" "$LOG_DIR" "$PREFIX/run"
fi

cat >"$PREFIX/agern.conf.tmp" <<EOF
# Written by install.sh — paths of this Agern installation (read by the agern command).
AGERN_ENV_FILE='$ENV_FILE'
AGERN_DATA_DIR='$DATA_DIR'
AGERN_LOG_DIR='$LOG_DIR'
AGERN_USER='$SVC_USER'
AGERN_SERVICE='$SERVICE'
EOF
chmod 0644 "$PREFIX/agern.conf.tmp"
mv "$PREFIX/agern.conf.tmp" "$PREFIX/agern.conf"

# ---------------------------------------------------------------------------------------------
# Configuration file: created once, never overwritten.

FIRST_INSTALL=0
if [ ! -e "$ENV_FILE" ]; then
    FIRST_INSTALL=1
    conf_dir=$(dirname "$ENV_FILE")
    mkdir -p "$conf_dir"
    chmod 0755 "$conf_dir"
    if command -v openssl >/dev/null 2>&1; then
        AGERN_NEW_KEY=$(openssl rand -base64 32)
    else
        AGERN_NEW_KEY=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
    fi
    [ ${#AGERN_NEW_KEY} -eq 44 ] || die "could not generate a master key"
    export AGERN_NEW_KEY AGERN_NEW_DATA_DIR="$DATA_DIR"
    (
        umask 077
        # The key travels through the environment, not the argument list.
        awk '
            /^FACTORY_MASTER_KEY=/ { print "FACTORY_MASTER_KEY=" ENVIRON["AGERN_NEW_KEY"]; next }
            /^FACTORY_DATA_DIR=/   { print "FACTORY_DATA_DIR=" ENVIRON["AGERN_NEW_DATA_DIR"]; next }
            { print }
        ' "$SRC_DIR/.env.example" >"$ENV_FILE.tmp"
        cat >>"$ENV_FILE.tmp" <<'EOF'

# --- Installation -----------------------------------------------------------------------------
# Java used by the service (default: $JAVA_HOME/bin/java, then java on the PATH).
# AGERN_JAVA=/usr/lib/jvm/java-17-openjdk/bin/java
# Extra JVM options.
# AGERN_JAVA_OPTS=-Xmx2g
EOF
        mv "$ENV_FILE.tmp" "$ENV_FILE"
    )
    unset AGERN_NEW_KEY
    grep -q '^FACTORY_MASTER_KEY=.\{44\}$' "$ENV_FILE" || die "$ENV_FILE has no master key (bad .env.example?)"
    info "created $ENV_FILE with a new FACTORY_MASTER_KEY (back it up: losing it makes stored credentials unreadable)"
fi
chmod 0600 "$ENV_FILE"
if [ "$IS_ROOT" = 1 ]; then
    if [ "$SERVICE" = systemd ]; then
        chown root:root "$ENV_FILE"          # read by systemd (EnvironmentFile), not by the service user
    else
        chown "$SVC_USER:$SVC_GROUP" "$ENV_FILE"   # read by 'agern run' as the service user
    fi
fi

# ---------------------------------------------------------------------------------------------
# Service registration

render() { # render TEMPLATE > output
    sed -e "s|@PREFIX@|$PREFIX|g" -e "s|@USER@|$SVC_USER|g" -e "s|@GROUP@|$SVC_GROUP|g" \
        -e "s|@ENV_FILE@|$ENV_FILE|g" -e "s|@DATA_DIR@|$DATA_DIR|g" -e "s|@LOG_DIR@|$LOG_DIR|g" \
        -e "s|@PATH@|$SERVICE_PATH|g" -e "s|@LABEL@|$LAUNCHD_LABEL|g" "$1"
}

case $SERVICE in
    systemd)
        unit=/etc/systemd/system/agern.service
        render "$dest/service/agern.service" >"$unit.tmp"
        if ! cmp -s "$unit.tmp" "$unit"; then
            mv "$unit.tmp" "$unit"
            chmod 0644 "$unit"
            systemctl daemon-reload
            info "installed $unit"
        else
            rm -f "$unit.tmp"
        fi
        systemctl enable agern.service >/dev/null
        ;;
    launchd)
        plist="/Library/LaunchDaemons/$LAUNCHD_LABEL.plist"
        render "$dest/service/$LAUNCHD_LABEL.plist" >"$plist.tmp"
        if ! cmp -s "$plist.tmp" "$plist"; then
            mv "$plist.tmp" "$plist"
            chown root:wheel "$plist"
            chmod 0644 "$plist"
            # A loaded daemon keeps its old definition until it is unloaded.
            if launchctl print "system/$LAUNCHD_LABEL" >/dev/null 2>&1; then
                launchctl bootout "system/$LAUNCHD_LABEL" || true
            fi
            info "installed $plist"
        else
            rm -f "$plist.tmp"
        fi
        ;;
esac

if [ "$IS_ROOT" = 1 ] && [ -d /usr/local/bin ] && [ "${AGERN_LINK_BIN:-1}" = 1 ]; then
    ln -sfn "$PREFIX/bin/agern" /usr/local/bin/agern
fi

# ---------------------------------------------------------------------------------------------
# Activate

if [ "$FIRST_INSTALL" = 1 ]; then
    "$dest/agern" activate "$VERSION" --no-start
    agern_cmd="$PREFIX/bin/agern"
    [ -L /usr/local/bin/agern ] && agern_cmd=agern
    sudo_=""
    [ "$IS_ROOT" = 1 ] && sudo_="sudo "
    cat <<EOF

Agern $VERSION is installed. Next steps:

  1. Create the PostgreSQL role and database (native PostgreSQL 16), e.g. on Linux:
       sudo -u postgres createuser --pwprompt factory
       sudo -u postgres createdb --owner=factory factory
  2. Edit $ENV_FILE: FACTORY_DB_PASSWORD, FACTORY_PUBLIC_URL (the URL people
     type in their browser), FACTORY_GITHUB_ORG, FACTORY_ADMINS, FACTORY_AUTH.
  3. Make sure 'claude', 'git' and 'gh' are installed for the service (on its PATH:
     $SERVICE_PATH, or set FACTORY_AGENT_PATH).
  4. ${sudo_}$agern_cmd doctor
  5. ${sudo_}$agern_cmd start

EOF
else
    "$dest/agern" activate "$VERSION"
fi
