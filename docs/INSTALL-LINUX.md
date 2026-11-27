# Installing Agern on Debian or Ubuntu

From an empty virtual machine to a running Agern, using the Debian package, on a host with systemd:

| Distribution | Status |
|---|---|
| **Debian 13 (trixie)** | **Recommended.** Tested on a real machine before each release, including sandboxes. |
| Ubuntu 24.04 LTS | Supported. Tested by CI on every pull request, including sandboxes. |
| Debian 12 (bookworm) | Not tested any more. The package installs (CI still checks that), and sandboxes passed on it once (Podman 4.3), but nothing is verified for new releases: use Debian 13 for a new machine. |

The package is `Architecture: all` (amd64 and arm64 alike). The tarball installer (`install.sh`, also for macOS)
is described in the [README](../README.md#production-install); do not mix the two on one host.

- [1. The machine](#1-the-machine)
- [2. PostgreSQL](#2-postgresql)
- [3. Install the package](#3-install-the-package)
- [4. Database and configuration](#4-database-and-configuration)
- [5. Claude CLI and gh for the agern user](#5-claude-cli-and-gh-for-the-agern-user)
- [6. Start](#6-start)
- [7. Network: firewall and TLS](#7-network-firewall-and-tls)
- [8. Upgrades, rollback and automatic upgrades](#8-upgrades-rollback-and-automatic-upgrades)
- [9. Backup and restore](#9-backup-and-restore)
- [10. Removing Agern](#10-removing-agern)
- [11. Security boundary](#11-security-boundary)
- [12. Developing Agern with Agern](#12-developing-agern-with-agern)
- [13. Sandboxes](#13-sandboxes)
- [Appendix: Proxmox](#appendix-proxmox)

## 1. The machine

Agern itself is light (one JVM, ~0.5 GB). The agents are not: every agent is a `claude` process
that clones repositories, runs builds and tests. Size for the agents.

| | Minimum | Recommended (a few people, several pipeline runs at once) |
|---|---|---|
| vCPU | 4 | 8 |
| RAM | 8 GB | 16–32 GB |
| Disk | 64 GB | 128 GB+ (workspaces and tool caches grow: Gradle, npm, Docker images) |

Use a fresh, minimal Debian or Ubuntu server install with SSH, a fixed address or DHCP reservation
and a DNS name on the LAN (e.g. `agern.lan`). Keep the OS updated (`unattended-upgrades`).

## 2. PostgreSQL

Agern needs PostgreSQL 15 or newer, **installed natively** from the distribution (Debian 12: 15,
Ubuntu 24.04: 16, Debian 13: 17). There is no Docker in the production setup.

```sh
sudo apt update
sudo apt install postgresql
```

The Agern package *recommends* `postgresql` and `chromium`, so `apt install ./agern_….deb` installs them
too unless you pass `--no-install-recommends` (e.g. for a database on another host). `chromium` is the
headless browser the UX and UI design reviewers screenshot the app under review with (section 5). On Ubuntu
it resolves to `chromium-browser`, only a launcher for the chromium snap; `agern doctor` points that out.

## 3. Install the package

Download the latest release's package and its checksums, verify and install. `apt` (not `dpkg -i`)
resolves the dependencies: a Java 17+ runtime (`default-jre-headless`: OpenJDK 17 on Debian 12,
21 on Debian 13 and Ubuntu 24.04), `git`, `curl`, `ca-certificates`.

```sh
cd /tmp
base=https://github.com/Egernet-CS/agern/releases/latest/download
curl -fsSLO "$base/SHA256SUMS"
deb=$(grep -o 'agern_[^ ]*_all\.deb' SHA256SUMS)
curl -fsSLO "$base/$deb"
sha256sum --check --ignore-missing SHA256SUMS
sudo apt install "./$deb"
```

The package installs:

| Path | What |
|---|---|
| `/usr/bin/agern` | the control command (`agern help`) |
| `/usr/share/agern/agern.jar` | the server, frontend embedded |
| `/etc/agern/agern.env` | configuration, `root:agern 0640`. Created on the first install with a freshly generated `FACTORY_MASTER_KEY` (never printed); **never overwritten** by upgrades |
| `/var/lib/agern` | data: git workspaces, per-user homes; home of the `agern` user (`agern:agern 0750`) |
| `/var/cache/agern` | the installed and the previous package, for rollback (root only) |
| `agern.service` | the server, as the unprivileged system user `agern`; enabled, but not started until configured |
| `agern-upgrade.timer` | automatic upgrades, **off** by default |
| logs | journald: `agern logs [-f]` or `journalctl -u agern` |

A fresh install is not started: the configuration is incomplete. Even if something starts it, the
server exits with status 2 on a configuration error and systemd does not restart it in a loop.

## 4. Database and configuration

Create the database role and database on the local PostgreSQL. `agern setup-db` does it as the
`postgres` superuser, generates a password, and writes `FACTORY_DB_URL`, `FACTORY_DB_USER` and
`FACTORY_DB_PASSWORD` into `/etc/agern/agern.env`. Running it again changes nothing.

```sh
sudo agern setup-db                    # role and database 'factory'; --db / --user to change
```

Then edit the configuration:

```sh
sudoedit /etc/agern/agern.env
```

| Variable | Set it to |
|---|---|
| `FACTORY_GITHUB_ORG` | the GitHub organization whose repositories are the projects (for developing Agern itself: the organization that holds the `agern` repository) |
| `FACTORY_PUBLIC_URL` | the URL people type, e.g. `http://agern.lan:8080`. Browser requests from any other origin are rejected, so `localhost` does not work from the LAN. The session cookie is `Secure` only when this is `https://` |
| `FACTORY_ADMINS` | comma list of usernames that are admins |
| `FACTORY_AUTH` | `os` (accounts on this machine, via PAM) and/or `ldap` (see the `FACTORY_LDAP_*` lines) |

Every variable is described in [ARCHITECTURE.md §3](ARCHITECTURE.md#3-configuration-environment-variables).
The file is `KEY=value` lines, no shell syntax.

**Two-factor authentication is mandatory** (TOTP from an authenticator app, enrolled at the first
login). There is no switch to turn it off in production.

**`os` login and the `shadow` group.** With `FACTORY_AUTH=os`, PAM's `pam_unix` checks passwords
against `/etc/shadow`, which the `agern` user can only read as a member of the `shadow` group.
The package does **not** add it, because every agent runs as `agern` too and could then read all
password hashes on the machine. Your choices:

- Use LDAP (`FACTORY_AUTH=ldap`) — no shadow access needed. Preferred when you have a directory.
- Accept it on a machine that has no accounts worth protecting except the Agern users:
  `sudo usermod -aG shadow agern && sudo agern restart`. Give those accounts passwords that are
  used nowhere else.

**Restricting LDAP logins to a group.** With `FACTORY_AUTH=ldap` alone, everyone with an account
in the directory can log in. Whenever the directory is wider than the team (e.g. a whole
organisation), set a required group. Agern checks it after the user's own bind, as that user, so
no service account (and no password for one on this host) is needed; it works with the DN template
as well as with search-then-bind.

```sh
# groupOfNames (member holds user DNs) — the defaults
FACTORY_LDAP_REQUIRED_GROUP=cn=agern,ou=groups,dc=example,dc=com

# posixGroup (memberUid holds login names)
FACTORY_LDAP_REQUIRED_GROUP=cn=agern,ou=groups,dc=example,dc=com
FACTORY_LDAP_GROUP_MEMBER_ATTRIBUTE=memberUid
FACTORY_LDAP_GROUP_MEMBER_VALUE=uid
```

Use `uniqueMember` for `groupOfUniqueNames`. Several groups are separated by `;` (membership of any
one is enough). The directory must let authenticated users compare or read the member attribute of
these group entries; otherwise every login is refused. A refused user sees "Wrong username or
password"; the reason is in the log (`journalctl -u agern | grep 'ldap:'`), e.g.
`ldap: bob is not in cn=agern,ou=groups,dc=example,dc=com`. Membership is checked at every login,
so removing someone from the group takes effect at their next login (sessions last up to 14 days).
`FACTORY_ADMINS` still decides who is an admin, and admins must be in the group too.

## 5. Claude CLI and gh for the agern user

Agents are `claude` processes; they use `git` (installed with the package) and `gh`. Install them
where the service finds them. The service's `PATH` is
`/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/var/lib/agern/.local/bin`.

```sh
# Claude Code, native installer, as the agern user (ends up in /var/lib/agern/.local/bin):
sudo -u agern -H bash -c 'curl -fsSL https://claude.ai/install.sh | bash'
# (without sudo: sudo-less systems can use  runuser -u agern -- env HOME=/var/lib/agern bash -c '…')

# GitHub CLI (Debian 12 ships an old 2.23; GitHub's apt repository has current versions:
# https://github.com/cli/cli/blob/trunk/docs/install_linux.md)
sudo apt install gh

# A headless browser for the UX and UI design reviewers, who run the app under review and attach
# screenshots of it to the run (recommended by the package, so usually installed already on Debian;
# on Ubuntu it is only a launcher for the chromium snap: use Google Chrome's .deb or a project's Playwright):
sudo apt install chromium fonts-noto-core
```

Nobody logs in to `claude` or `gh` as the `agern` user: each person registers their own Claude
credential and GitHub token in Agern's Settings, and agents run with the credentials of the
person who started them. `sudo agern doctor` reports what is missing and how to install it.

## 6. Start

```sh
sudo agern doctor     # configuration, Java, PostgreSQL login, claude/git/gh, headless browser, privileges, …
sudo agern start      # waits until /api/health answers
agern status
```

Open `FACTORY_PUBLIC_URL` in a browser, log in, enrol the second factor, then add your Claude
credential and GitHub token under Settings.

## 7. Network: firewall and TLS

Agern is made for a **trusted LAN**. Do not expose it to the internet. Allow port 8080 (and SSH)
from your LAN only, e.g. with `ufw`:

```sh
sudo apt install ufw
sudo ufw default deny incoming
sudo ufw allow from 192.168.1.0/24 to any port 22 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp
sudo ufw enable
```

**TLS.** Plain HTTP works on a LAN (the second factor is TOTP, which does not need HTTPS). For
HTTPS put a reverse proxy (Caddy, nginx) on the same machine in front of it, set
`FACTORY_PUBLIC_URL=https://agern.lan` (this also makes the session cookie `Secure`) and
`FACTORY_HOST=127.0.0.1` so only the proxy reaches the server. The proxy must pass WebSockets
(`/api/ws/…`) and should send `X-Forwarded-For` (Caddy does by default; nginx:
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`) — besides logging, that header is
what keeps the host-local endpoint `/api/internal/activity` from answering through the proxy.
The proxy must also pass `POST /mcp` (personal MCP tokens: Claude Code on users' own computers reaches
Agern's factory tools through `FACTORY_PUBLIC_URL`, docs/ARCHITECTURE.md §7 "Personal tokens") and must not
buffer its responses: slow tool calls are answered as an event stream that can run for minutes (Caddy
streams by default; nginx: `proxy_buffering off;` and a `proxy_read_timeout` of at least `30m` for
`location /mcp`).

What is exposed on the network (the `Origin` checks, `/mcp`, personal MCP tokens sent as bearer
headers) is summarised in [README.md, "Security: read this first"](../README.md#security-read-this-first).

## 8. Upgrades, rollback and automatic upgrades

```sh
sudo agern upgrade                       # latest release (stable channel)
sudo agern upgrade --version 1.4.0       # a specific release
sudo agern upgrade --channel main        # the newest build of main (see §12)
sudo agern upgrade --file ./agern_1.4.0_all.deb   # a package you have (no checksum check)
sudo agern rollback                      # reinstall the previous package
agern version
```

`agern upgrade` downloads `SHA256SUMS` and the package from the GitHub release (anonymously; the
repository is public), verifies the SHA-256 (refusing on a mismatch before anything changes),
installs with `apt-get install`, so new dependencies resolve, and waits until `/api/health`
reports the new version — reinstalling the previous package automatically if it does not come up.
It refuses while pipeline runs or agents are active (a restart fails runs in progress); `--force`
overrides that, reinstalls the installed version and allows a downgrade. The installed package and
the one before it are kept in `/var/cache/agern` for `agern rollback`. When the version being
replaced was installed some other way (the first `apt install`, a main build that has since moved
on), `agern upgrade` downloads it again from its release if it can, and otherwise rebuilds the
package from the installed files (only when `dpkg --verify agern` finds them unchanged). If neither
works it says that no rollback copy could be kept, and `agern rollback` then refuses and points to
`agern upgrade --version X.Y.Z --force`. Database migrations run on startup and are not undone by a
rollback.

You can also upgrade with plain `apt install ./agern_….deb`; the configuration is never touched
either way.

**Automatic upgrades** (opt-in):

```sh
sudo agern auto-upgrade on                  # channel from AGERN_UPGRADE_CHANNEL (default stable)
sudo agern auto-upgrade on --channel main   # also stores the channel in agern.env
agern auto-upgrade status                   # channel, next check, waiting/given up, recent log
sudo agern auto-upgrade off
```

`agern-upgrade.timer` runs every 15 minutes (as root). When the channel has a newer version, it
asks the server (on loopback) whether anybody's work is in progress: runs in `queued`, `spec`,
`building`, `reviewing` or `approving`, or agents that are starting or mid-turn. Runs waiting for a
human and chats between turns survive a restart and do not count. While busy it postpones to the
next tick; after `AGERN_UPGRADE_MAX_WAIT_HOURS` (default 24) of waiting for the same version it
gives up on that version with a warning in the journal (`journalctl -u agern-upgrade -p warning`)
— a newer version starts a new wait, and `sudo agern upgrade` installs it whenever you like.

## 9. Backup and restore

Back up two things, and keep them apart:

1. **The database**, e.g. nightly: `sudo -u postgres pg_dump -Fc factory > /var/backups/agern-db-$(date +%F).dump`
2. **`/etc/agern/agern.env`** — above all `FACTORY_MASTER_KEY`. Every stored Claude credential,
   GitHub token and 2FA secret in the database is encrypted with it; a database backup without
   the key is useless, and the key plus the database gives access to everyone's credentials.
   Store it in your password manager or secrets store, not next to the database dumps.

`/var/lib/agern` holds clones and caches that agents recreate; include it only if you want
interrupted chat sessions to resume after a restore. On a VM, snapshots/backups of the whole
machine (e.g. Proxmox `vzdump`) complement but do not replace a database dump.

Restore on a new machine: install the package (do not start it), put back `agern.env`
(`root:agern 0640`), run `sudo agern setup-db` (creates the role with the password from that file
and an empty database), then
`sudo -u postgres pg_restore --no-owner --role=factory -d factory agern-db-….dump` and
`sudo agern start`.

## 10. Removing Agern

```sh
sudo apt remove agern    # stops and disables the service and timer; keeps config, data, user, database
sudo apt purge agern     # also removes /etc/agern/agern.env, /var/cache/agern and the agern user/group
```

Purge first saves a root-only copy of `agern.env` in `/var/backups/agern.env.purged-<time>`
(the master key is needed to use the database again). It never deletes `/var/lib/agern` or the
database; it prints what it left behind:

```sh
sudo rm -rf /var/lib/agern
sudo -u postgres dropdb factory && sudo -u postgres dropuser factory
```

## 11. Security boundary

The overview (trust model, GitHub token scopes, what is merged without a human, personal MCP tokens)
is in [README.md, "Security: read this first"](../README.md#security-read-this-first); this section
covers the machine. Agents can read `/etc/agern/agern.env` and other users' workspaces, so the users
of one instance must trust each other.

Agents run as the unprivileged `agern` user with Claude Code's permission prompts bypassed: treat
everything the `agern` user can do as something an agent can do. The design keeps root out of
reach:

- **No way up.** The `agern` user has no sudo rules and no login shell; the unit sets
  `NoNewPrivileges`, an empty capability bounding set and `RestrictSUIDSGID`, so setuid programs
  (`sudo`, `su`) cannot elevate from inside the service. `agern doctor` fails when `agern` is in
  `sudo`/`wheel`/`admin`/`root` and warns about root-equivalent groups (`docker`, `lxd`, `incus`,
  `libvirt`, `disk`). Adding `agern` to `docker` gives every agent root on the host: use rootless
  Docker or Podman for agents instead.
- **Sandboxes add no privilege.** The optional sandbox unit (§13) runs rootless Podman as `agern`
  without `NoNewPrivileges`, so that `newuidmap`/`newgidmap` can map the subordinate ids root gave
  `agern` — ids that belong to no account. Its socket is `agern`'s alone. No rootful Podman, no
  `docker` group, no privileged helper, no sudo rule.
- **Only root installs packages**, by hand or through the root-owned timer, and only an
  `agern_*.deb` from a GitHub release whose SHA-256 matches the release's `SHA256SUMS`, or a file
  the administrator names with `--file`. An agent can build a `.deb` but cannot get it installed.
- **Nothing the `agern` user can write is executed or sourced by root** on the upgrade path: the
  units and `/usr/bin/agern` are root-owned package files; `/etc/agern/agern.env` is
  `root:agern 0640` (readable by the service, which has these values in its environment anyway)
  and is parsed as data — only known keys, and only if it is root-owned and not group/world
  writable; downloads and state live in root-only `/var/cache/agern`; the maintainer scripts never
  follow paths inside `/var/lib/agern`.
- **The idle signal is advisory.** `/api/internal/activity` only decides *when* the restart
  happens. An agent that fakes it can at most get its own work interrupted; it cannot choose what
  is installed.

## 12. Developing Agern with Agern

This machine can run the factory that develops the factory. Point `FACTORY_GITHUB_ORG` at the
organization that contains the `agern` repository, and the loop is:

1. An issue in `agern` is a task. Someone starts a **pipeline run** for it in Agern: spec, build,
   four reviewers and an approver take it to a pull request and merge it into `main`.
2. **CI** on `main` builds, tests and smoke-tests a `.deb` on Debian 12, Debian 13 and Ubuntu 24.04
   and publishes it as the rolling pre-release **`main-latest`**, versioned
   `<next>-main.<commit time UTC>.g<sha>` (package version `<next>~main.<time>.g<sha>`, so it sorts
   after the last release, before the next one, and in commit order).
3. With `sudo agern auto-upgrade on --channel main`, the **timer** notices the new build within
   15 minutes and installs it **as soon as no run or agent is busy** — including the run that
   produced it, which has finished by then.
4. A tag `v1.5.0` (`scripts/release.sh 1.5.0`) publishes a normal release; the main channel moves
   on to `1.5.1~main.…`, and a stable host upgrades to `1.5.0`.

**`main` or `stable`?**

| | `main` | `stable` |
|---|---|---|
| What you run | every merged change, minutes after the merge | tagged releases only |
| Risk | a bad merge reaches the factory itself; it may break the very runs that would fix it | releases are cut deliberately |
| Recovery | `sudo agern rollback` (the previous build is kept), or `agern upgrade --version <last release> --force` | `sudo agern rollback` |
| Good for | a factory dedicated to developing Agern, watched by its developers | a factory others depend on |

Practical advice for `main`: keep at least one person able to reach the machine over SSH (the
web UI may be what broke), keep `AGERN_UPGRADE_MAX_WAIT_HOURS` moderate so long runs are not
interrupted, and remember that database migrations are not undone by a rollback — a migration
merged to `main` is effectively released on this machine.

## 13. Sandboxes

**What they are.** A project can describe a container for its builds and tests in
`.agern/sandbox.yml` (image, setup script, sidecar services such as a database, cache directories,
CPU and memory). Once an admin has approved that file, the builder agent of each run runs its builds
and tests in that run's own container through two MCP tools, with the run's worktree mounted at
`/workspace` and nothing else from the host. Projects then need no toolchain installed on the
machine, a test database is per run, and a memory-hungry test is killed in its container instead of
taking the machine down. How a project writes its `.agern/sandbox.yml` (platforms, services and their
readiness checks, a non-root user, caches, examples) is in [SANDBOXES.md](SANDBOXES.md); the server's
side is in [ARCHITECTURE.md §5](ARCHITECTURE.md#sandboxes).

**What they are not, in this release.** The agent itself still runs on the host as the `agern`
user, with its permissions bypassed. It can reach everything that user can — including Podman and
the other runs' worktrees. A sandbox is a toolchain, sidecar and resource boundary, **not** a
security boundary against the agent; containing the agent is a later release. What does hold: the
containers are rootless (they belong to `agern`, never to root), never privileged, and Agern — not
the project — decides their mounts, network, capabilities and limits. See also
[README.md, "Security: read this first"](../README.md#security-read-this-first).

**How it runs.** Podman runs as the `agern` user in a unit of its own,
`agern-sandbox.service`, started through `agern-sandbox.socket` (`/run/agern-podman/podman.sock`,
which only `agern` can open). `agern.service` keeps its hardening (`NoNewPrivileges`, no
capabilities): rootless Podman cannot start under it, so the server talks to the sandbox service
over that socket instead. The sandbox unit gives Podman only what rootless containers need, and its
cgroup is delegated to `agern` so per-container limits work. Images, containers and volumes are
stored in `/var/lib/agern/containers`.

### Enabling

Sandboxes are off after installing the package. Install rootless Podman (the package only
*suggests* it), then enable:

```sh
sudo apt install podman uidmap netavark aardvark-dns crun slirp4netns
sudo agern sandbox enable
```

The same package names exist on Debian 13, Ubuntu 24.04 and Debian 12 (the last one is no longer tested, see the top of this guide):

| | Podman | crun | netavark / aardvark-dns | rootless network |
|---|---|---|---|---|
| Debian 12 | 4.3.1 | 1.8.1 | 1.4.0 | `slirp4netns` 1.2.0 |
| Ubuntu 24.04 | 4.9.3 | 1.14.1 | 1.4.0 | `slirp4netns` 1.2.1 |
| Debian 13 | 5.4.2 | 1.21 | 1.14.0 | `slirp4netns` 1.2.1 (not Podman 5's default pasta, see below) |

`agern sandbox enable` never installs packages itself: if one is missing it prints the exact
`apt install` line and changes nothing. Then it

1. gives the `agern` user a range of 65536 subordinate uids and gids (`usermod --add-subuids
   --add-subgids`), choosing a block that overlaps no other user's range and no uid or gid the
   system knows; an existing range is kept. If your directory (LDAP, SSSD) has uids ≥ 100000 that
   `getent passwd` does not list, give `agern` a range yourself first (`usermod --add-subuids
   FIRST-LAST --add-subgids FIRST-LAST agern`) — a range that maps onto real accounts would let
   container files belong to them;
2. enables `agern-sandbox.socket` and starts the service;
3. runs a **self-test as the `agern` user through the socket**: pulls a small image
   (`docker.io/library/busybox`; set `AGERN_SANDBOX_TEST_IMAGE` in `agern.env` when that registry is
   not allowed or not reachable), starts a sidecar and a sandbox on a private network exactly as the
   server does, checks that the sidecar is reachable by name, that the limits are in the container's
   cgroup, that a file written in `/workspace` belongs to `agern`, and that a process allocating more
   than the memory limit is killed — then removes everything again;
4. only if all of that passed, sets `FACTORY_SANDBOX=podman`, `FACTORY_SANDBOX_PODMAN_URL` and
   `FACTORY_SANDBOX_CGROUP_PARENT` in `/etc/agern/agern.env`, enables the daily clean-up timer and
   restarts Agern — if nobody's work is in progress; otherwise it tells you to `sudo agern restart`
   later.

Running it again is harmless (it re-runs the self-test). `sudo agern sandbox selftest` runs only
the self-test. Then approve a project's `.agern/sandbox.yml` in the web UI; runs of that project use
its sandbox from then on.

### Limits and settings

All in `/etc/agern/agern.env` (restart Agern after a change):

| Variable | Default | |
|---|---|---|
| `FACTORY_SANDBOX_MAX_CPUS` | `2` | CPUs per container (cap and default) |
| `FACTORY_SANDBOX_MAX_MEMORY` | `4g` | memory per container, no swap on top |
| `FACTORY_SANDBOX_MAX_CONCURRENT` | `2` | containers that run commands at the same time, over all runs: a run's sandbox counts one, and one more per platform it uses; a builder over the cap is told to wait (a run that holds several gives up its own idle ones first) |
| `FACTORY_SANDBOX_MAX_PLATFORMS` | `4` | platforms a definition may name besides its default image (`0` forbids them) |
| `FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES` | `30` | longest single command |
| `FACTORY_SANDBOX_REGISTRIES` | `docker.io,ghcr.io,quay.io,mcr.microsoft.com` | registries images may come from |
| `AGERN_SANDBOX_PRUNE_HOURS` | `168` | age after which unused images and volumes are removed |
| `FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS` | `7` | days a prepared image (a project's sandbox after its setup script ran) is reused before setup runs again; `0` runs setup in every new sandbox |

Every container also gets a limit of 4096 processes. Sidecars and platform containers have the same
limits as the sandbox, so a run with one database uses up to twice the memory, and three times when
it also tests on a platform. Sidecars do not take a slot of `FACTORY_SANDBOX_MAX_CONCURRENT`, so size
the machine for about `(FACTORY_SANDBOX_MAX_CONCURRENT + concurrent runs × sidecars) ×
FACTORY_SANDBOX_MAX_MEMORY` on top of the agents. The admin page shows, per run, its containers and
the memory they may use together.
To cap all sandboxes together, give their unit a limit, e.g. `sudo systemctl edit agern-sandbox`:

```ini
[Service]
MemoryMax=12G
CPUQuota=400%
```

Under memory pressure the kernel kills sandbox processes before the server and PostgreSQL, and the
server gets the CPU first.

### Disk use

Images, per-project cache volumes (e.g. Gradle or npm caches) and prepared images (each project's
sandbox image after its setup script ran, reused by later runs so setup does not run every time) live
in `/var/lib/agern/containers`; plan several GB per project image, and about as much again per prepared
image (one per project, platform and approved definition). The admin page lists the prepared images
with their sizes. `agern sandbox status` shows the
sandboxes that exist, the cache volumes and Podman's disk use; `agern doctor` warns below 10 GB free
and fails below 2 GB.

`agern-sandbox-prune.timer` runs daily (as `agern`) and removes images no container uses that were
*built* more than `AGERN_SANDBOX_PRUNE_HOURS` ago — Podman does not record when an image was last
used, so an idle old base image is removed and simply pulled again on the next run — plus unused
prepared images older than `FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS` (the shorter of the two ages wins)
and unused volumes of that age that are not caches. Cache volumes are kept until you remove them:

```sh
sudo agern sandbox prune              # what the timer does
sudo agern sandbox prune --prepared   # also every unused prepared image (setup runs again next time)
sudo agern sandbox prune --caches     # also unused cache volumes and prepared images
sudo agern sandbox prune --all        # every unused image and volume, regardless of age
```

### Troubleshooting

```sh
sudo agern sandbox status
sudo agern doctor                     # section "sandboxes"
sudo agern sandbox selftest
journalctl -u agern-sandbox -n 100
```

- *"packages … not installed"*: install exactly the packages named.
- *The self-test cannot create a user namespace*: `sysctl user.max_user_namespaces` must be above 0
  and, on Debian, `kernel.unprivileged_userns_clone` 1. On Ubuntu,
  `kernel.apparmor_restrict_unprivileged_userns=1` (the default) needs the AppArmor profiles that come
  with Ubuntu's Podman and crun packages.
- *"limits not applied"* / doctor: *controllers not enabled*: the unit's cgroup is not delegated
  (a local override removed `Delegate=yes`?) or the host boots with cgroups v1; sandboxes need
  cgroups v2, the default on all supported releases.
- *"setting up Pasta … Permission denied"*, or an `apparmor="DENIED"` line that `agern sandbox
  selftest` or `agern doctor` shows: a distribution AppArmor profile refuses one of the sandbox
  service's paths. Debian 13 confines pasta (Podman 5's default network helper) to `/run/user/<uid>`
  and `/tmp`, and its profile has no `local/` include to extend it, so Agern's Podman configuration
  uses slirp4netns instead (`default_rootless_network_cmd` in `/usr/share/agern/podman/containers.conf`).
  If you see it, check that the package's copy of that file is unmodified (`dpkg --verify agern`) and
  that `slirp4netns` is installed.
- *An image cannot be pulled*: the registry must be in `FACTORY_SANDBOX_REGISTRIES` and reachable
  from the machine; Docker Hub limits anonymous pulls per address.
- *After changing the subordinate ids by hand*: `sudo systemctl restart agern-sandbox` (this stops
  running sandboxes).

### Disabling

```sh
sudo agern sandbox disable            # refuses while runs or agents are busy; --force overrides
```

It sets `FACTORY_SANDBOX=off`, restarts Agern, removes all sandbox containers and networks, and
stops and disables the sandbox units. Images, cache volumes and the subordinate ids are kept, so
enabling again is quick. To free the space as well: `sudo rm -rf /var/lib/agern/containers`
after disabling (the files belong to `agern`'s subordinate ids, so only root can delete them).
`apt purge agern` removes that directory too (and says so), and deleting the `agern` user drops its
subordinate ids.

### Testing a host

The checks CI runs on Ubuntu 24.04 can be run on any machine with the package; this is how
Debian 13 is verified before a release. On a **throwaway** VM — it installs packages, starts services, and purges Agern at the
end — from a checkout of the repository:

```sh
sudo bash packaging/deb/sandbox-host-test.sh ./agern_<version>_all.deb --it .
```

It prints `PASS`/`FAIL` per check: enabling and the self-test, `agern doctor`, the server (under its
hardened unit) reaching the sandbox service, the Podman integration test run as `agern` inside
`agern.service` (needs a JDK; one is installed if missing), an upgrade keeping sandboxes on,
pruning, disabling and purging.

## Appendix: Proxmox

Nothing Proxmox-specific is required; any VM with a supported distribution works. Two ways:

**From an installer ISO.** Upload the Debian netinst or Ubuntu Server ISO, create a VM (machine
`q35`, BIOS `OVMF (UEFI)` or SeaBIOS, CPU type `host` — the JVM and build tools benefit from the
host's instruction set), SCSI controller `VirtIO SCSI single`, a disk with *Discard* and *IO thread*
on, a `virtio` network card on the LAN bridge, and the sizes from §1. Install with only "SSH
server" and "standard system utilities", then `apt install qemu-guest-agent` and enable the
*QEMU Guest Agent* option.

**From a cloud image with cloud-init** (repeatable, also as a template):

```sh
# on the Proxmox host; VM id 9000, storage local-lvm, bridge vmbr0 — adjust
wget https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-amd64.qcow2
qm create 9000 --name agern --memory 16384 --cores 8 --cpu host --net0 virtio,bridge=vmbr0 \
  --scsihw virtio-scsi-single --agent enabled=1 --ostype l26
qm importdisk 9000 debian-13-genericcloud-amd64.qcow2 local-lvm
qm set 9000 --scsi0 local-lvm:vm-9000-disk-0,discard=on,iothread=1 --boot order=scsi0 \
  --ide2 local-lvm:cloudinit --serial0 socket --vga serial0
qm resize 9000 scsi0 128G
qm set 9000 --ciuser admin --sshkeys ~/.ssh/authorized_keys --ipconfig0 ip=dhcp
qm start 9000          # or: qm template 9000, then qm clone 9000 <new id> --full
```

Then follow this guide from §2 inside the VM. Proxmox backups (`vzdump`) of the VM are a good
addition to the database dump in §9.
