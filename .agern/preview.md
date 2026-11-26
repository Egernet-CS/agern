# Previewing Agern's UI for review

Read by Agern's ux and ui-design reviewers before they run the app (docs/ARCHITECTURE.md §6 "Screenshots").

The web frontend has a **mock mode**: it runs entirely in the browser against simulated data, so it
needs no server, no database, no GitHub and no login.

```sh
cd web
npm ci
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
npm run dev:mock -- --host 127.0.0.1 --port "$PORT" --strictPort > /tmp/agern-preview-$PORT.log 2>&1 &
```

Wait until `http://127.0.0.1:$PORT/` answers (a few seconds), then take screenshots. Stop the dev server
(`kill %1`, or the PID of `vite`) when you are done.

- Never use port 8080 or start `./gradlew :server:run`: that is where a real Agern listens.
- You are logged in as `alice` (an admin) by default. Scenarios via `?mock=<name>`, remembered per tab:
  `default`, `fresh` (new user, nothing set up), `noclaude`, `observer` (logged in as `bob`),
  `loggedout` (the login page: a known user such as `alice`, any password, any six-digit code but
  `000000`), `nosandbox`,
  `usage-none`, `usage-high`, `usage-limit`.
- Useful pages: `/` (projects), `/p/web-shop` (a project with issues and runs), `/agents`
  (live agents; click a run to open its run page), `/settings`, `/admin`. A merged run with the
  warning "UI change reviewed without screenshots" is in `/p/design-system` (issue #12).
  A workspace project (several repositories side by side) is `/p/shop-workspace`: its members are in
  the "agents & runs" pane, its issues show their member, and issue #5 has a merged workspace run.
  As `observer` (bob) the member `ios` is marked "no access".
- Sandboxes: `web-shop` has an approved sandbox. Its merged run of issue #47 shows "review sandbox ✓"
  in the run page's sandbox line; the `ux` stage's transcript has a command block with the `review`
  chip (the app started in the read-only review sandbox).
- The app is a single-page app: with Chromium's `--screenshot`, pass `--virtual-time-budget=8000`,
  or the page is captured before it renders. With Playwright, wait for `networkidle` and a selector.
- There is one (dark) theme. Check narrow layouts at 390 px width.
- `web/scripts/screenshots.mjs` is a Playwright tour of the mock app that shows how to reach most screens.
