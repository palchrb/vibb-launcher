# Code QA: the lock screen asks on its own again (branch `lock-auto`) - 2026-11-03

Scope: `git diff main...lock-auto` (f8cc9c13, 3ef3a914): `server/static/passkey.js` `installUnlock`, its jstests, the
`?v=8` bump, `handlers/lock.rs`'s doc line, `lock.html`'s comment, connectivity.md "The lock screen" and design 74's
D3.5 note. Read-only on code. `node --test server/jstest/`: 275 pass, 0 fail. Cargo not run (CI 41674331408 passed).

Counts: 0 H, 2 M, 2 L.

## Findings

### M1 - repeated showings evict the tap's challenge; a genuine Face ID is then refused and counted as a wrong try

`server/static/passkey.js:540` (`every`, fetched once), `:616-620` (`autoStart` → `start(first, …)`, a new challenge per
showing), `:643-653` (`shown(fresh)` refetches `every` only when it aged out); server `passkeys.rs:40-42, 253-256`
(`PER_SESSION = 8`, a session at its cap drops its oldest).

On main a showing issued no challenge (the tap's was fetched ahead and kept). Now each new showing posts
`/lock/options` for its automatic try, while the tap's `every` options stay "ready" on the client for 4 minutes. The
server keeps the session's 8 newest and drops the oldest, so after 8 new showings within those 4 minutes (each one
under reconnect.js's 60 s, else M2's reload refreshes everything) `every`'s challenge is gone server-side while the
client still treats it as ready. Scenario: the iPhone PWA on the lock screen, the tab's free try spent, the parent
flicks to Messages and back 8 times in 3 minutes (every return refused quietly, as designed), then taps: Face ID
succeeds, `POST /lock` fails (unknown challenge) → `app_unlock_failed`, `reauth_wrong` +1, red line "Couldn't unlock".
The next tap works (`failed()` refetches), but three such events end every session. Simulated with the jstest harness:
8 hide/show cycles → 10 options requests, and the tap prompts with the 2nd one without a fetch. Unlikely in daily use,
but it is the property the brief requires, and it touches the wrong-try counter.

Fix: keep the tap's challenge the newest at every showing that issues one. In `shown(fresh)` (after `autoStart()`),
call `every.fetch().then(null, function () {})` instead of `prefetch()`: `fetch()` keeps the old options ready until
the new ones arrive (only `spend()` clears them), so a tap in between still prompts within the gesture. The refresh
timer can do the same instead of `spend()` + `prefetch()`. A jstest: N hide/show cycles, then the tap's options are the
last ones fetched before the prompt (or count: one `""` options request per fresh showing).

### M2 - back on the lock screen after more than a minute: reconnect.js's 423 navigates the page under the automatic try

`server/static/reconnect.js:721-736` (`cameBack` → `start("return")` → `check` → `/ping` answers 423 while locked,
`tests/app_lock.rs:351`) → `:168-174` `toLock` → app-lock.js's `lock()` → `location.replace("/lock?next=…")`, racing
`server/static/passkey.js:643-653` (`shown(true)` → automatic try) on the same `visibilitychange`.

The parent leaves the lock screen (phone to sleep, another app) for over a minute and comes back. reconnect.js's
listener (registered first) pings; passkey.js's starts the automatic try. The ping's 423 navigates to a new `/lock`
document while the first try's options are on their way or its sheet is just up, and the new document asks again.
Android Chrome / the WebAPK: the passkey sheet appears, is torn down by the navigation, and appears again (two
prompts, the very thing the change rules out); extra challenges are spent too. iOS mostly gets away with it (the old
document's try is refused for want of a gesture, the new document has a fresh free try), but where the old one's free
try was unspent the iOS sheet comes twice as well. Pre-existing side effect, outside the diff, made worse by it: the
new address is `/lock?next=%2Flock%3Fnext%3D…`, which `lock::local_next` refuses, so after the unlock the parent lands
on `/` instead of the page they were on (see L2).

Fix: the lock screen is the one page reconnect.js must leave alone on a return: in `cameBack`, return early when
`win.location.pathname === "/lock"` (no return reload, no ping; passkey.js's showing does the work, and a dead session
shows up as `/lock/options`'s 401 → the sign-in page). A reconnect jstest: on `/lock`, back after 2 minutes, no ping
and no navigation. If the reload is wanted after all, passkey.js should then skip the automatic try while reconnect.js
has a "return" wait running - but dropping it is simpler.

### L1 - the automatic try's options that fail on the network show the red line on their own

`server/static/passkey.js:612-613`. `if (auto && cancelled(err)) return prefetch(); failed(…)`: a fetch `TypeError` (the
PWA back from the background, Tailscale not up yet - the reason reconnect.js exists) or a 5xx on the automatic try's
`/lock/options` shows "Couldn't unlock" and "Tap to try again" with no touch from the parent. Connectivity.md says the
automatic try stays quiet when cancelled, timed out or refused for want of a gesture and shows the red line for a
refused unlock; it does not cover this case. Fix: either keep the automatic try quiet for anything that is not a
refused unlock (`failed()` only from `unlockWith`, or for `err.name === "refused"` from the server), or say in
connectivity.md that a failed request shows the red line too. Quiet is the better fit: the tap is there and its own
failure shows the line.

### L2 - (pre-existing, outside the diff) the lock's `next` is lost when the lock page is sent to the lock again

`server/static/reconnect.js:168-174` with `herePath` = `/lock?next=%2Fdevices%2F3`: on `/lock` the 423 path builds
`/lock?next=/lock?next=…`; `lock::local_next` (`server/src/lock.rs:346`) refuses a `/lock` path, so the page's `next`
is empty and the unlock goes to `/`. M2's fix removes the trigger; otherwise `toLock` should keep the current page's
own `next` when it is already on `/lock`.

## Verified OK

- No prompt loop: a try open while the page is hidden leaves `away` false (`hidden()` = `away = !open`), so a sheet
  that hides the page (Android) is no new showing whether its cancel lands before or after the page is visible again
  (open → `stale`, no automatic try; settled → `autoTried` stays true). iOS's sheet and WebKit's "Continue" should not hide
  the page (from memory; on-device check owed with the iPhone QA) - and if they do, the open try keeps it safe. Safari's gesture-free refusal is `NotAllowedError` → quiet, no retry. Pinned by "a passkey sheet that hides
  the page is no new showing".
- Once per showing: load while visible, else the first `visibilitychange` to visible; bfcache `pagehide` + `pageshow`
  + `visibilitychange` give one try (`back()` clears `away`). A showing with a try open starts nothing and aborts
  nothing.
- No dead screen: every quiet end of the automatic try calls `prefetch()`, every `failed()` too; the tap's `every` is
  ready unless spent and not yet refetched (the pre-existing short window right after load or a refresh). A
  synchronous throw becomes a rejection.
- One prompt per tap: a tap during the automatic try's options fetch aborts its signal, and `promptWith` checks
  `signal.aborted` before `get` (pinned, "early"); a tap during its `get` aborts it first (WebKit also cancels an older
  request on a new one - from memory). Once answered (`running.auto = false`) a tap is dropped while it posts. A late result of a
  replaced try returns at `open !== running`; a late refusal of the post is gated by `open === running`.
- Stale rules: a showing marks an open try stale; the next tap aborts it and asks afresh (QA M1 test, now with the
  automatic try in front).
- Red line: never for the automatic try's cancel, timeout or no-gesture refusal; for a tap's cancel or failure and for
  any refused unlock; cleared with the hint text at a fresh showing (except L1).
- Security: challenges are spent before each `get` and never reused (the aborted automatic try's dies unused);
  `handlers::lock::offered` unchanged (doc line only), the hint narrows within the parent's own passkeys, the tap always
  asks with every passkey; no template content changed, nothing private shown before unlock; a cancel posts nothing, so
  only a real bad assertion counts in `reauth_wrong` (but see M1).
- `?v=8` in all six `<script>` tags that load passkey.js (`login.html` x2, `account.html`, `password_sign_in.html`,
  `lock.html`, `device_lock.html`); no service-worker fetch cache.
- Jstests: lean and on the real risks (once per showing incl. bfcache, the sheet-hides-page loop, abort with one prompt,
  the early tap, stale, post-time drop, throw, refresh counts). Not pinned: M1 (the tap's challenge newest).
- Docs: connectivity.md, design 74's note, `lock.html`'s comment and the module header match the code, except L1's
  case; "back from the back-forward cache" is a new showing only when no try was open at `pagehide`, as the code does
  ("with no try open" in the same sentence).
