# Code QA of server round D (`pwa-fixes-d`: sign-in after an outage, Locate's answer row, the Home Screen line)

Branch `pwa-fixes-d` at dcc7ed95 (3 commits on 604cccd0: 7b3b923a the sign-in logging, `GET /api/session` and
`reconnect.js?v=5`; 6bfab84d Locate's answer row; dcc7ed95 the Notifications footer line), reviewed on
`qa-pwa-fixes-d`. Read against `docs/notes/server/pwa.md` ("The sign-in page with a live session", "The answer row"),
`notifications.md` ("Adding the app to the Home Screen again") and PLAN.md's three queue items. Paths are under
`server/` unless they start with `docs/` or `PLAN.md`.

**Verdict**: not mergeable until Q-1 is fixed; with it, good. The server side is careful: `log_sign_in_redirects`
sits on the admin router only, inside `gate::admin_gate` (a refused request never reaches it) and outside the session
layer; it logs the path without the query, a yes/no for the cookie and two clipped `Sec-Fetch-*` values, never a
cookie value. The reason set is complete for every redirect to `/login` in the code (`require_session`,
`require_full_auth`, the re-auth sign-out, Sign Out; a sign-in step's own redirect reads "a sign-in step") and each
reason is truthful - `why_signed_out` tells "expired" from "no row" correctly, because the store's `load` asks for
`expiry_date > now` and tower-sessions drops the id when nothing loads (checked in tower-sessions-core 0.14
`get_record` and the sqlx store's query). `GET /api/session` is on the admin listener only, 204/401 with `no-store` and
no body, and the session layer here has no `always_save`, so a read never extends the session. A cross-site page gets
nothing from it: the cookie is SameSite=Strict (always 401 cross-site), there are no CORS headers, and an
`<img>`/`<script>` probe sees an empty answer either way. Locate's `answer_state` is a clean pure function with an
exhaustive match; the footer line shows only with a device listed.

**One High**: `loginReturn` is an open redirect for a signed-in admin. A same-host URL whose path starts with `//`
(`/login?next=/.//evil.example`) passes `appPath`'s origin check and goes to `location.replace("//evil.example")`,
which is a scheme-relative URL to another site. **Two Medium**: two privacy promises have no tests (no cookie value
and no query in the log lines; the session check writes nothing). Three mutations survive. The rest is Low.

**Merge with main**: not clean. `PLAN.md` conflicts: main's 747fc7ae re-laid the queue in rounds and waves
(and added `docs/plan/2026-10-25-queue-rounds.md`). The two-dot `git diff main..HEAD` shows that file as deleted by
the branch, but it isn't: the branch was cut before that commit. Rebase, then put the three "Built" notes into main's
new layout. Nothing else conflicts.

## Suites

- `cargo test` (CARGO_TARGET_DIR `qa-pwa-fixes-d`): **1346 passed**, 0 failed (run before 22:00 UTC; see N-2).
- `cargo fmt --check`: clean. `cargo clippy --all-targets`: the 13 known warnings (models, devices, dns_engine,
  storytel), none in a changed file.
- `node --test jstest/*.test.js`: **174 passed**, 0 failed.
- Playwright (`scratchpad/pwa-d/`, Chromium and WebKit, light and dark): signed out, `/login` stays; signed in,
  `/login` goes back (to `/settings`, the referrer); the Locate row has no row / asking / found / failed; the footer
  line shows. The logs have one console error per signed-out sign-in page, the 401 from `/api/session` (see below).
  The server log shows the expected lines (`session check status=401 reason="no session cookie"`, `sent to the sign-in
  page with a session cookie reason="the cookie isn't a session id"`) and no cookie value.
  Screenshots viewed: `webkit-locate-found.png` (see Q-8: "40 min ago · Within 12 m" above "Found within 5 m · 22:45")
  and `webkit-notifications-settings.png` (the sentence follows "Shown: …" in the footer, wraps cleanly, footer grey).

## Findings

### Q-1 (High) `loginReturn` redirects a signed-in admin to another site
Evidence: `static/reconnect.js:97-111` (`appPath` compares the parsed protocol and host, then returns
`path + to.search + to.hash`), `:132` (`next`/`from` from the address), `:473` (`win.location.replace(target)`).
Measured in node against the module:
`loginReturn("https://admin.example.ts.net/login?next=/.//evil.com/x", "", null, null, now)` returns `"//evil.com/x"`.
`new URL("//evil.com/x", here)` is `https://evil.com/x`. The same happens with `/%2e//evil.com`, `/a/..//evil.com` and
`https://<own host>//evil.com`, and through the referrer and the `wanted` record too.
Attack: send the parent a link to `https://<admin>/login?next=/.//evil.example`. A cross-site navigation sends no
SameSite=Strict cookie, but the page's own same-origin `fetch('/api/session')` does, so it gets 204 and the
sign-in page sends the parent to the attacker's page. That page can be a look-alike "session expired" form that
collects the password and the TOTP code. The attacker has to know the admin host name (a tailnet name). On the other
side, this is the admin's own sign-in page vouching for the redirect.
Nothing in the server ever sets `next` or `from`: every redirect is a plain `/login`. So that branch is only
attack surface.
Fix: (a) remove the `next`/`from` branch; (b) in `appPath`, refuse a path that starts with `//` (and `/\`), or return
`to.href`, the absolute same-origin URL, after the origin check, so the browser never reads a scheme-relative path;
(c) jstest rows for `next=/.//evil`, `next=https://<host>//evil`, a referrer `https://<host>//evil` and a `wanted`
`//evil`, each expecting `/` (or null).

### Q-2 (Medium) The log lines' privacy is unpinned
Evidence: `src/security.rs:575-613` (`log_sign_in_redirects`), `src/gate.rs:124-132`, `src/security.rs:637-643`. No
test reads a log line. Mutation R1 (the warning also logs the raw `Cookie` header) passes the whole suite, and so
would logging `uri()` with its query. R15 (the gate's warning for every refusal, not only one with a session cookie)
passes too.
Fix: one test on the capture subscriber that `tests/music_spotify.rs` already has (`CAPTURE`, process-wide, per
thread). Send `GET /devices?q=SECRETQ` with `Cookie: __Host-handy=SECRETVALUE` (and `handy=` for the insecure
name). Through the gate, send one refused request with a session cookie and one without. Assert that the lines hold
the reason, the path and `cookie=true`, never `SECRETVALUE` or `SECRETQ`, and that the gate logs no line for the
request without a cookie.

### Q-3 (Medium) "The session check writes nothing" is unpinned
Evidence: `src/security.rs:620-644`, `src/tests/admin_auth.rs` `the_session_check_answers_204_or_401_and_changes_nothing`
(it counts `tower_sessions` rows). Mutation R2 (`session.insert("checked", 1)` before the check) passes. The session
becomes modified, so it is saved with a fresh expiry and a `Set-Cookie`: that is a check that extends the session,
which the note says it never does. The row count doesn't move, so the test can't see it.
Fix: on the 204 and on each 401, assert that there is no `set-cookie` header and that the row's `expiry_date` is
unchanged (read before and after).

### Q-4 (Low) The gate's new warning is unthrottled
Evidence: `src/gate.rs:124-132`. Every refused request that carries a cookie named `handy` or `__Host-handy` writes
a WARN line. The `admin_refused` event beside it goes through `EventThrottle`; this line doesn't. Refused clients
are by definition the ones the gate protects against (an internet client after a proxy mistake), so they control
how much gets logged with a header they can make up.
Fix: log it only for a tailnet client (`Refusal::TailscaleUser`, which is the parent's own phone) and use debug for
the others; or log it only when the throttle writes a row (one per client per minute).

### Q-5 (Low) The bounce guard lives only in sessionStorage
Evidence: `static/reconnect.js:448-475`. The script says that an installed iPhone app can lose sessionStorage
(`wanted()`, `:428-431`, falls back to `window.name` for that reason). If the store is lost or throws, the 30 s
guard never holds. Then a server that redirects the navigation while the fetch sees 204 (the Strict-cookie
mismatch this code is built for) loops `/login` → page → `/login`.
Fix: also write the bounce time into the `window.name` record (or keep a small try counter in both), and read
whichever of the two is newer.

### Q-6 (Low) The `window.name` fallback drops the query, and a stale sessionStorage record hides a fresh one
Evidence: `static/reconnect.js:311-312` (`placeRecord` keeps `pathname` only), `:432-445`. A reload in place on
`/devices/locate?device=2` comes back to `/devices/locate`, which is the first phone's page. That is the "stale record
from another phone's page" case. It can't be another tab's or another site's record: `window.name` belongs to the
tab, and Safari and Firefox clear it on a cross-site navigation. But it can be the wrong phone. Also, `wanted()`
returns the sessionStorage record even when it is older than 2 minutes, which hides a fresh `window.name` record.
Fix: keep `pathname + search` in the place record (scroll-restore matches on the path, so compare the path there),
and use the fresher of the two records.

### Q-7 (Low) The sign-in check runs only when the script loads
Evidence: `static/reconnect.js:665`. A `/login` page that is resumed instead of loaded doesn't ask: back from the
background within `AWAY_MS` (shorter than that, the reload in place doesn't happen), or restored from the
back-forward cache (`pageshow` with `persisted`). The user said "when I reconnect and open Vibb Phone again", which
can be exactly a resumed page.
Fix: also call `checkSignIn()` on `visibilitychange` to visible and on `pageshow` while the path is `/login`. The
bounce guard already covers repeats.

### Q-8 (Low) "Found within 5 m" can contradict the fix row above it
Evidence: `src/handlers/locate.rs:430-443`, `webkit-locate-found.png` ("40 min ago · Within 12 m" above "Found within
5 m · 22:45"). In the Playwright run, the fixture stored no fix. But in real use the answer and the location upload
are separate requests: an upload that lags or fails, or a mock fix kept out of the map (design 64 decision 14), gives
the same picture.
Fix: show `found` only when the newest fix is newer than the ask (`fixed_since(asked)`), and otherwise keep
"Asking the phone…" for a short grace or show no row.

### Q-9 (Low) A cancelled Update Location hides the failure before it
Evidence: `src/handlers/locate.rs:1175-1177` (the newest `locate` of any status), `:429` (`Cancelled => None`).
Example: "Location is off on the phone", then the parent presses Update Location and cancels it. The row is gone,
but location is still off. Requests skips cancelled commands for exactly this reason ("it never counts as the
newest, so it can't hide the last real one").
Fix: `AND cancelled_at IS NULL` in the query (or skip parent cancels as `requests` does), plus a test row.

### Q-10 (Low) PLAN.md conflicts with main
See "Merge with main" above.

### N-1 (Nit) `has_session_cookie` accepts both names whatever the setting
`src/security.rs:466-473`. On a secure deployment, a leftover `handy` cookie (an `INSECURE_COOKIES` test on the same
host) reads as `cookie=true` with reason "the cookie isn't a session id", which is misleading. Accept only the
configured name.

### N-2 (Nit, pre-existing) `device_pages::the_lock_state_says_when_it_was_reported` fails on the night the clocks change
It failed in every mutation run after 22:00 UTC on 2026-10-25. `datetime('now', '-1 day')` lands at 00:06 CEST
on the 25th, which is "today" in Oslo, not "yesterday". This is not this branch's test or code. Pin the clock or use
`-2 days`.

### N-3 (Nit) `role="status"` on a row that is there from page load is never announced
`templates/device_locate.html:52`. A live region announces changes, not content present at load. It does no harm, and
`data-state` is the test hook. The page has no other accessibility issues: the time keeps its exact `title`, the text
is plain and the row isn't a control.

### N-4 (Nit) A redirected ping asks `/api/session` for nothing new
`static/reconnect.js:222-227`. The redirect's own answer already showed that the server is there. It costs one
round trip and does no harm.

## Questions from the brief

- **Always asking `/api/session` on the sign-in page: keep it** (once Q-1 is fixed). If it asked only with a hint (a
  `next`/`from`, a place record, an in-app referrer), it would miss the likely case: iOS relaunches the PWA into a
  fresh browsing context (empty sessionStorage, empty `window.name`, no referrer), and the server's redirect is a
  plain `/login` with no `next`. "Killing and restarting works" fits a cookie that a navigation didn't send but a
  fetch does. The cost is one console line in devtools, which nobody sees on the phone, and one info line in the
  server log per signed-out visit, on a tailnet-only listener. A browser always logs a failed status for a fetch, so
  the only way to hide the console line is to answer signed-out with a 2xx, which blurs the 204/401 contract. Not
  worth it. Remove the `next`/`from` branch whichever way you decide (Q-1).
- **The brief sign-in flash**: acceptable. Hiding the form while the check runs would delay every signed-out visit by
  up to 4 s.
- **Log volume**: the no-cookie info line is on the admin listener only (it doesn't exist on the phone listener or
  the device router). With the default tailnet-only gate, only tailnet devices can cause it. With `ADMIN_PUBLIC=on`,
  every scanner request to a protected path writes one info line (there's no other request log). That's acceptable;
  the unthrottled warning is Q-4.
- **The wording**: "No position - the phone didn't answer" reads right for both cases (received but no answer in
  time; sent but never confirmed). "Asking the phone…", "Found within 5 m" and the failure texts are plain and calm.
  "Answer to" is gone (the test reads the template). The `?requested=1` page leaves the asking row to `#fix_wait`
  (checked by the page test).
- **Docs**: the pwa.md and notifications.md notes and the three PLAN lines match the code: the reason list, warning
  vs info, the gate's own line, the route on the admin listener only, `?v=5`, 10 minutes, a failure until a later fix.
  Two parts are out of date: pwa.md's "a `next`/`from` in the address" (remove it with Q-1) and the "never another
  origin" claim, which Q-1 disproves.

## Mutations

Each one applied to the branch, the suite run (Rust: the whole `cargo test`, R4 again on its own with `locate`), then
reverted (`git status` clean afterwards).

| # | Mutation | Result |
|---|---|---|
| R1 | the sign-in warning logs the raw `Cookie` header | **NOT CAUGHT** (Q-2) |
| R2 | the session check calls `session.insert` (saved, expiry extended, `Set-Cookie`) | **NOT CAUGHT** (Q-3) |
| R3 | the found row for 600 minutes | caught (`the_answer_row_comes_and_goes`, `the_answer_row_shows_only_while_it_says_something`) |
| R4 | a failure cleared by a fix up to a day older than it | caught (`the_answer_row_comes_and_goes`, `the_fix_and_the_answer_read_in_local_time`) |
| R5 | the footer line without a device | caught (`the_device_list_says_what_to_do_after_adding_the_app_again`) |
| R6 | `/api/session` on the phone listener | caught (`the_session_check_…`: 500, not 404) |
| R8 | any cookie name containing `handy` counts | caught (`the_session_cookie_is_found_by_its_name_alone`) |
| R9 | "expired" and "no row" swapped | caught (`a_redirect_to_the_sign_in_page_says_why`) |
| R10 | `require_full_auth`'s redirect without its reason | caught (`a_redirect_to_the_sign_in_page_says_why`) |
| R11 | received-overdue shown as asking | caught (`the_answer_row_comes_and_goes`) |
| R12 | the asking row on the `?requested=1` page too | caught (unit and page test) |
| R14 | the session check answers 204 before onboarding is finished | caught (`the_session_check_…`) |
| R15 | the gate's warning for every refusal, cookie or not | **NOT CAUGHT** (Q-2) |
| J1 | `appPath` accepts another origin | caught |
| J2 | the 30 s bounce guard off | caught |
| J3 | a submit in progress ignored by the 204 | caught |
| J4 | `/auth/…` allowed as a way back | caught |
| J5 | a `wanted` record of any age | caught |
| J6 | a ping redirected to the login counted without the check | caught |
| J7 | the session check sent without credentials | caught |
| J8 | a POST's answer (refused password) asks too | caught |

**18 of 21 caught** (Rust 10/13, JS 8/8). The gap that matters most is not a mutation: the open redirect (Q-1) passes
every test as the branch stands, because the "never another origin" rows use only a different host.
