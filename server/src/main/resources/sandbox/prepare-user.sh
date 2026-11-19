# shellcheck shell=sh
# Prepares the account sandbox commands run as (`user:` in .agern/sandbox.yml; docs/ARCHITECTURE.md §5
# "Sandboxes"). Run by the server as root inside a new sandbox container, before the setup script, with
# plain POSIX sh tools only (busybox is enough):
#   sh -c <this script> agern-user <uid> <name> [<cache path> ...]
# <name> is empty when the definition gave a uid. The account is the image's own when it has one that fits,
# otherwise it is added to /etc/passwd and /etc/group. Its home directory and the cache directories (and
# their parents inside the home directory) are given to it, so a non-root user can use the caches.
# Prints "AGERN_USER <name> <home>" on success; exits 3 with a message when the image contradicts the request.
set -eu
uid=$1
want=$2
shift 2
look=${want:-sandbox}
by_name=
by_uid=
fail() {
    echo "agern: $*" >&2
    exit 3
}
if [ -f /etc/passwd ]; then
    while IFS=: read -r n _ u _ _ h _ || [ -n "$n" ]; do
        if [ "$n" = "$look" ] && [ -z "$by_name" ]; then by_name="$u:$h"; fi
        if [ "$u" = "$uid" ] && [ -z "$by_uid" ]; then by_uid="$n:$h"; fi
    done </etc/passwd
fi
create=
if [ -n "$want" ]; then
    if [ -n "$by_name" ]; then
        [ "${by_name%%:*}" = "$uid" ] ||
            fail "the image already has a user '$want' with uid ${by_name%%:*}, not $uid; write 'user: ${by_name%%:*}' in .agern/sandbox.yml to run as it"
        name=$want
        home=${by_name#*:}
    elif [ -n "$by_uid" ]; then
        fail "uid $uid already belongs to the image's user '${by_uid%%:*}'; write 'user: ${by_uid%%:*}' in .agern/sandbox.yml to run as it"
    else
        name=$want
        home=/home/$want
        create=1
    fi
elif [ -n "$by_uid" ]; then
    name=${by_uid%%:*}
    home=${by_uid#*:}
else
    [ -z "$by_name" ] || fail "the image has a user 'sandbox' with uid ${by_name%%:*}; write 'user: ${by_name%%:*}' in .agern/sandbox.yml to run as it"
    name=sandbox
    home=/home/sandbox
    create=1
fi
if [ -n "$create" ]; then
    # Keep the files line-oriented when they lack a final newline.
    for f in /etc/passwd /etc/group; do
        if [ -s "$f" ] && [ -n "$(tail -c 1 "$f")" ]; then echo >>"$f"; fi
    done
    printf '%s:x:%s:%s::%s:/bin/sh\n' "$name" "$uid" "$uid" "$home" >>/etc/passwd
    if ! grep -q "^[^:]*:[^:]*:$uid:" /etc/group 2>/dev/null; then
        printf '%s:x:%s:\n' "$name" "$uid" >>/etc/group
    fi
fi
[ -n "$home" ] || home=/home/$name
mkdir -p "$home"
[ "$home" = / ] || chown "$uid:$uid" "$home"
for p in "$@"; do
    mkdir -p "$p"
    chown "$uid:$uid" "$p"
    d=${p%/*}
    while [ -n "$d" ] && [ "$d" != "$home" ]; do
        case "$d" in
        "$home"/*) chown "$uid:$uid" "$d" ;;
        *) break ;;
        esac
        d=${d%/*}
    done
done
echo "AGERN_USER $name $home"
