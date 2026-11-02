# Security code QA - passkeys-3 (2177a962): the app lock's client and idle clock

Scope: `static/app-lock.js`, the lock-aware `auto-reload.js` / `install-now.js` / `pull-refresh.js` / `reconnect.js`,
the step-3 parts of `passkey.js` (the unlock, Account's control, 423), `templates/lock.html`, `partials/head.html`, the
`#app-lock` group in `account.html`, `style.css`'s veil, the client-facing part of `lock.rs` (`Facts::is_use`, the
page mark, `EXEMPT` as the client sees it), i18n, and the jstests. Server enforcement is the other reviewer's. Read
only; no cargo or node.

**Verdict: not quite mergeable - one Medium to fix first (M1, a page from the HTTP cache shows after the lock is
due).** Everything else holds up:
- The idle clock is relative and never trusts the phone's wall clock against the server's.
- No script keeps `used_at` fresh by itself.
- The veil and lock order on load and bfcache is right.
- Unlocking replaces the lock in history, and the lock page is clean.

Counts: **High 0, Medium 1, Low 2, Nit 5.**

---

## Medium

### M1 - A page loaded from the HTTP cache (Back past an evicted bfcache entry) shows while the lock is due
`server/static/app-lock.js:88-93`, `server/src/admin_auth.rs:986-988` (`private, no-cache` for HTML),
`server/src/lock.rs:373-415` (`mark_page`).

`app-lock.js` takes the last use from the page's `data-lock-idle`, which says how long before *serving* the session
was last used. For a page that comes out of the HTTP cache, that number is old, but `loaded` is now, so the client
computes `last = now - idle_then`: a recent use that never happened. The stored `handy-used` (the real last input)
only raises `last`, never lowers it.

Chromium loads back/forward navigations with `LOAD_SKIP_CACHE_VALIDATION`: an entry cached as `private, no-cache` is
used without asking the server. Only `no-store` keeps it out. Its bfcache entries live 10 minutes. So:

1. A parent on Android opens Devices, then a device page, and leaves the PWA in the background. The lock is 15
   minutes.
2. Later the kid opens it. The device page becomes visible, `shown()` sees the lock is due, and
   `location.replace("/lock?next=…")` runs. That part works.
3. The kid presses Back (system back or gesture). The Devices entry left the bfcache long ago, so Chrome loads it
   from the HTTP cache. The server sees nothing, so its 303 to `/lock` never happens.
4. `app-lock.js` reads `data-lock-idle` = a few seconds (from when it was served), decides the lock isn't due, and
   shows the page. Its timer then gives it nearly the full 15 minutes.
5. Each further Back shows the next cached page the same way.

Pages cached *before* the lock was turned on have no `data-lock-ms` at all: no client lock, no timer, no beacon.

How much leaks:
- The stale HTML of every page in the tab's history (names, phone states, whatever the page printed), readable
  without touching the screen.
- The first touch sends the beacon, which gets 423, which locks. The data fetches (`locations.json`, polls) get 423.
  So there is no fresh data and no action, but this is exactly the "nothing private shows" the client is for (D3.3),
  and the server's guarantee doesn't apply to responses it never sees.
- Android is the case shown. iOS standalone has little Back navigation, and Safari's history loads revalidate more
  often; I didn't verify Safari.

Fix: the robust one is server-side.
- **Serve every HTML response of a lock-on session as `Cache-Control: no-store`.** `mark_page` already touches
  exactly those responses, so set the header there. Every history load then goes to the server: a locked session
  gets a 303 to `/lock`, an open one a freshly marked page.
  - The cost is the bfcache and scroll restore while the lock is on. Chrome doesn't bfcache `no-store` pages by
    default. The lock is opt-in, and pageshow/bfcache handling stays for sessions without it.
- **Send `Clear-Site-Data: "cache"` on the `POST /account/lock` answer that turns the lock on**, so pages cached
  before it are gone (Chrome; Safari partly).
- If keeping the bfcache matters more, add the client check too:
  - When `performance.getEntriesByType("navigation")[0]` says `type === "back_forward"`, or `transferSize === 0` with
    a body (from the cache), don't use `data-lock-idle`. Use the stored `handy-used` only; if there is none, treat
    the page as due.
  - Also keep `handy-lock-ms` in sessionStorage from a marked page, so an unmarked cached page in a lock-on tab
    still locks.
  - This is weaker than `no-store`: it is per tab, and it doesn't cover a page cached before the lock went on.
- Test (jstest): a page loaded with `type: "back_forward"`, `data-lock-idle="2000"` and an old stored input -> locks
  at once.
- Test (server): a lock-on session's page answers `no-store`.

---

## Low

### L1 - A clock set back while a page is open delays the client's lock
`server/static/app-lock.js:67,98,149`.

`due()` compares `Date.now()` values. If the phone's wall clock steps back (NTP correcting a fast clock, a manual
change) while the page sits open, `now() - last` drops by the step. The timer (monotonic) fires, `check()` sees it
isn't due and sets a new timer for the step's extra time. The server still locks on time, but the open page keeps
showing until the next request.

The load-time handling (`Math.min(stored, loaded)`) is already right.

Fix: measure time inside the page with `performance.now()`. Keep `Date.now()` only for the sessionStorage times that
cross pages, and clamp those as now.

### L2 - The lock screen's prefetched challenge goes stale while the page stays on screen
`server/static/passkey.js` (`installUnlock`, `AHEAD_MS = 240000`).

The challenge is fetched at load, on `visibilitychange` and on `pageshow`. A lock page left visible for more than 4
minutes (an iPad on a stand, a desktop) has no ready options. The next tap fetches first and then prompts, outside
the user gesture. iOS refuses (`NotAllowedError`), so the parent sees "Couldn't unlock"; the refetch then makes the
second tap work.

Fix: a timer that refetches at about 3.5 minutes while visible, as the sign-in page's autofill refresh does. Test:
options 4+ minutes old -> fresh before the tap.

---

## Nit

- **N1** `app-lock.js:126-135` - `input()` doesn't check `event.isTrusted`. No script here dispatches pointer or key
  events (checked), so nothing synthesizes use today. `if (e && e.isTrusted === false) return;` would make "only real
  input moves the clock" true by construction (defense in depth against a future script or an injected one).
- **N2** `app-lock.js:110-111` - `sent` is recorded before the beacon is answered. A beacon lost to the network means
  no retry for 60 s, so the server's `used_at` can lag the client's last input by more than the 1-minute slack. That
  errs safe (the server locks first, and a save or tap then lands on `/lock` a little early). Record `sent` only on a
  2xx, or accept it.
- **N3** `app-lock.js` - `locked`/`veiled` hide the body, but `document.title` stays the page's own (e.g. a device's
  name). It can show in Android's recents or a desktop tab while veiled. Set the title to "Vibb" when locking or
  veiling, and put it back on `shown()`.
- **N4** `account.html` `#app-lock` - the error under the control is `passkeys.browser_failed`. After a *cancelled*
  prompt nothing shows and the control reverts, which is right. After a 423 mid-change, `post` goes to `/lock` and
  the change is lost; the parent sets it again after the unlock. Fine. The control's off state (a switch whose value
  is `lock_first` = 5) and the picker with Off when on match D3.1 and the mockup. Noted only.
- **N5** Tests - the jstests are good: load-time lock, the timer, inputs, the stored time across reloads, an
  unreadable store, the veil, bfcache before and after due, an unlock in the tab, the beacon throttle across pages
  and its 423, the lock page never locking, ES5, the unlock within the tap, cancel/refusal, Account's revert, and 423
  in passkey.js, reconnect.js, install-now.js, auto-reload.js and pull-refresh.js. Missing:
  - the M1 history-load case;
  - a `pagehide` with `persisted` setting the veil (only `visibilitychange` is tested);
  - L1's clock step.

---

## Checked and fine

- **The idle clock is relative.**
  - The server marks `<html data-lock-ms data-lock-idle>` with the lock time and the milliseconds since its own
    `used_at` (after this request moved it). The client adds that to its own `Date.now()` at load. No absolute
    server time meets the phone's clock, so skew doesn't matter.
  - The server locks at `used_at + N + 60 s`, the client at the last input + N. The beacon sends at most once a
    minute and always moves `used_at` (`facts.beacon` skips the 30-s rule), so the server lags by under 60 s and
    the client locks first. A tapped navigation within 30 s of the last write doesn't move `used_at`, but its
    pointerdown sends the beacon when one is due, so the lag stays under the slack.
- **Nothing keeps `used_at` fresh without the parent.** I checked every `fetch` and `submit` in `static/` and
  `sw.js`:
  - The polls are GETs without `Sec-Fetch-User`: reconnect's `/ping` and `/api/session`, Locate's
    `locations.json`/`zones.json`, Install Now's poll, music cards, `subscriptions.json`.
  - The scripted reloads carry no `Sec-Fetch-User`.
  - Every scripted POST follows a user action: calendar preview on `change`, push on/off on `click`, sign-out,
    passkey re-auth options on `pointerdown`/`focus`, autosave on `change`.
  - The lock page's own `POST /lock/options` (at load, `visibilitychange`, `pageshow`) is `EXEMPT`, so it is neither
    checked nor counted.
  - The service worker has no fetch handler, and its receipt is `credentials: "omit"` on an exempt route. There is
    no prefetch or speculation rule.
  - The beacon fires only on `pointerdown`/`keydown` while visible. An open page on the table locks, on the client
    by its timer and on the server by `used_at`.
- **Load order.**
  - `app-lock.js` is a classic, non-deferred script in the head, after the render-blocking `style.css`. A page that
    is due at load gets `html.locked` before the body is parsed, so nothing paints.
  - The server serves a page only while its own lock isn't due, and that lock is later than the client's.
- **bfcache.**
  - `pagehide` with `persisted` sets `veiled`, so the restored DOM paints veiled. That part is guaranteed.
  - `pageshow` with `persisted` re-reads the stored input (an unlock in this tab counts), then locks if due or
    removes the veil.
  - `visibilitychange` to hidden veils, and shown again it checks. The iOS app-switcher snapshot can be taken before
    `visibilitychange`, so that part is best effort, as D3.3 says.
  - `lock()` uses `location.replace`, so a locked page has no history entry to come back to.
- **The scripted reloads ask `due()` first**: auto-reload's tick, install-now's tick and its reload, pull-refresh's
  reload, and reconnect's `go`, `cameBack`, the shared `reload()`, the return reload. A ping or session check
  answered 423 goes to `/lock` with the right `next`; `pathOf` drops a foreign origin, and null falls back to this
  page. `passkey.js`'s `post` answers a 423 by going to the lock and never resolving, so no flow continues
  half-locked.
- **The lock screen.**
  - No name, phone or count, no tab bar, `no-store`, `<title>Vibb</title>`; `next` is only a local path (the
    server's `local_next`, never `/lock`, `/login`, `/logout` or `/auth*`).
  - Unlock fetches its challenge ahead, so the tap calls `credentials.get` synchronously within the gesture, which
    iOS standalone needs. A cancelled or refused try shows the red line and refetches.
  - Success writes `handy-used` for this tab, then `location.replace(next)`: Back never returns to the lock, and the
    entry before it is a page app-lock.js checks again (M1 aside).
  - Sign Out is Settings' form (`data-sign-out`, the push endpoint included) with `next` kept.
- **Account's control** (D3.1-D3.2): shown only with a passkey for this host (else the reason, and passkey.js's own
  for http or no support). Off it is a switch turning on at 5 minutes; on it is a picker with Off. Every change runs a
  re-auth assertion for `/account/lock` first and reverts on cancel or refusal; the re-auth challenge is prefetched on
  the control's first touch.
- **CSP**: no inline scripts or handlers in `lock.html`, `account.html`'s group or `head.html` (external files
  only).
- **i18n**: the en and nb key sets are identical (2094 each), and every `lock.*` key the templates use exists in
  both.

---

## Revision check - 7658c641 (fix round; covers this review and the server review)

Read only: `git diff 2177a962..7658c641`. No cargo or node.

**Verdict: mergeable. No High or Medium is left.** One Low remains (RL1, both clocks fooled on purpose) and one Nit
(RN1).

### Client M1 (a page from the HTTP cache): fixed, with the server as the guarantee and the client as a belt

**Server, `no-store`:** `lock::mark_page` now sets `Cache-Control: no-store` on every `text/html` answer it marks.
- It marks every HTML answer of a lock-on session that passes `require_session` or `require_full_auth` (both call it),
  handlers' own error pages (`i18n::error`) included. The security-headers layer only fills in `private, no-cache`
  when no `Cache-Control` is set, so it doesn't override this.
- The lock screen and the other `/lock` answers set `no-store` themselves (`handlers/lock.rs`).
- Exempt and signed-out routes aren't marked (`shown` is `None`). They show nothing of a session.
- Redirects and JSON aren't HTML, and nothing caches them for history.
- So a history load of a lock-on page always reaches the server, which sends a locked session to `/lock`.

**Server, `Clear-Site-Data`:** `POST /account/lock` turning the lock on sends `Clear-Site-Data: "cache"` on its 303,
which removes pages kept from before (Chrome; Safari partly).

**Client:** `oldPage()` is true when the navigation entry says `type === "back_forward"`, or `transferSize === 0`
with a body, or (legacy) `performance.navigation.type === 2`.
- On such a load only the stored input counts, and with none stored the page is due.
- With `no-store` such a page is usually fresh, so this only ever locks earlier, never later: the stored input is
  this tab's real last input.
- Browsers without `transferSize` read it as `undefined`, never as a false "cached".
- A fresh load still takes the later of the server's last use and the stored input (`min` of the two "ago"s), as
  before.
- Tests added (jstest and `a_lock_on_sessions_pages_are_never_kept`).

### Client L1 (clocks): fixed - neither clock alone can hold the lock

- `elapsed()` = `max(performance.now() - lastMono, Date.now() - lastWall)`.
- **Wall clock set back while the page is open:** the page's own clock keeps counting, so it locks on time.
- **Phone asleep** (where `performance.now()` stops): the wall clock counts the sleep, so it locks on time.
- **Wall clock stepped forward:** the wall term grows by the step, so the page can lock *early*. That errs safe: an
  unlock, never a page left open.
- `reread()` now takes another page's newer stored input (an unlock) only when it is newer than this page's own, and
  maps it onto both clocks.
- `isTrusted === false` input is ignored (my N1). The title shows "Vibb" while veiled or locked (my N3).

**RL1 (Low, what is left):** the page stays open only if *both* clocks under-count. That needs the phone asleep (the
page clock frozen, on iOS/macOS) *and* the wall clock set back afterwards. Someone would have to unlock the parent's
phone and change its time on purpose before opening the PWA. The page in memory then shows until the first touch: the
beacon gets 423 and it locks, and every data fetch and navigation is the server's 423/303 anyway.
- Optional: on `shown()`, have app-lock.js fire `GET /api/session` and call `lock()` on a 423, so the server's clock
  decides each return. Today only the sign-in page asks it (`reconnect.js` `checkSignIn`).
- L2 (the lock page's stale challenge) is in the fix commit's scope per the review copy; I didn't re-check it beyond
  the passkey.js diff (+13 lines).

### Server L1 (a sign-in clears the old lock): fixed on every completion path

There are three places that write `admin_id` and `SIGNED_IN_AT_KEY`:
- `verify_2fa` (password + TOTP) and the passkey sign-in both call `lock::carry`, which now does `set(session, 0)`
  first and then copies the cookie's minutes only with a passkey for this RP ID.
- The password sign-in that goes straight in (a one-time password's onboarding, no TOTP yet) calls `set(&session, 0)`.

No other path signs in. A failed `set(0)` in `carry` only warns; since this would leave another parent's lock on the
session (fails closed), that is acceptable.

### Server L2 (the router walk): fixed

The test reads main.rs's `.route(` calls with their methods, pins the router's `merge`/`nest_service` targets (a new
way into the router fails the test), checks every `device_routes.rs` path is under `/api/devices/`, and probes each
exempt route by its own methods. Parsing source text is a bit fragile, but the pinned list makes a miss loud.
Acceptable.

### Server N3 (a locked POST goes back to its Referer): no open redirect

`referer_path` keeps only the Referer's path and query (the fragment dropped) when its host equals `Host` or the
first `X-Forwarded-Host` value. The result then always goes through `lock_url` -> `local_next`, which takes:
- one leading `/`, never `//` or `/\`;
- printable ASCII without a backslash, 512 bytes at most;
- never `/lock`, `/login`, `/logout` or `/auth*`.

So `next` is always a path on this host: no scheme or host can get through. `https://host//evil.example/x` gives
`//evil.example/x`, which is refused. The unlock's `next` is validated again before the JSON `next` that passkey.js
`location.replace`s.

**RN1 (Nit):** `referer_path` compares with `X-Forwarded-Host` whatever the peer. Elsewhere it is believed only from a
trusted proxy (`Ceremony::of`, `csrf_check`). It is harmless here, since only a local path ever comes out and only
for the requester's own redirect, but use the trusted-peer rule for consistency.
