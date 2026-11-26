# Security policy

## Supported versions

Security fixes go into `main` (published as the `main-latest` pre-release) and the latest release.
Older releases get no fixes: upgrade to the latest release.

## Reporting a vulnerability

Report it privately through GitHub's private vulnerability reporting for `Egernet-CS/agern`:
<https://github.com/Egernet-CS/agern/security/advisories/new> (Security tab → "Report a
vulnerability").

Do not open a public issue, pull request or discussion with details. If the form is not available
to you, open a public issue that only asks for a private contact, without any details.

## What to include

- the Agern version (`agern version`, or `version` from `/api/health`);
- how it is installed (`.deb` or tarball) and on which OS;
- relevant configuration without secrets: login provider, sandboxes on or off, reverse proxy;
- what an attacker needs: no account, a user account, admin, or the ability to write issues or
  comments in a repository;
- steps to reproduce or a proof of concept;
- the impact;
- logs, with tokens and other secrets removed.

## What to expect

We acknowledge the report, prepare a fix privately in a GitHub security advisory, and publish it
with the release that fixes it. We credit you in the advisory if you want.

## Known limits that are not vulnerabilities

Agern documents what it does not protect against in
[README.md, "Security: read this first"](README.md#security-read-this-first). In particular, all
agents run as the same OS user with Claude Code's permission checks bypassed, and sandboxes are not
a security boundary against the agent. Reports about those limits as such are not vulnerabilities.

Reports that get around a boundary the README does claim are welcome, for example: one user driving
or approving another user's agents, runs or releases; reading stored credentials through the API; a
sandbox definition widening its own container (mounts, capabilities, privileges); a personal MCP
token acting outside its user or project; or cross-site request forgery against the web UI.
