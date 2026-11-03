# Light code QA: pwa-home-after-away (f994eab2)

Reviewed: `git diff main...pwa-home-after-away` plus reconnect.js and app-lock.js around it. `node --test server/jstest/`: 307 pass, 0 fail.

## Medium

M1. The "due lock after >10 min goes to `/lock?next=/`" half does not happen on a real page that has app-lock.js.
- Place: reconnect.js `cameBack()` (about line 752-756), against app-lock.js `lock()` (line 116-121, `if (going) return`), `due()` (line 165, `going || ...`), its `visibilitychange` (about line 248) and `pageshow` (about line 256).
- Cause: app-lock.js is a plain script and reconnect.js is `defer`, so app-lock's visibilitychange/pageshow listener runs first. Its `shown()` finds the lock due and calls `lock()` with no argument, so `next` is `here(win)`, and it sets `going = true`. Then reconnect's `cameBack()` runs: `lockDue()` is true (`going`), and `toLock(win, "/")` calls `handyLock.lock("/")`, which returns at once on `going`.
- Failure scenario: lock time 5 min, parent away 12 min, returns on /devices/1. They get `/lock?next=/devices/1` and land on that page after the passkey, not on Devices. The new tests pass only because `withLock` is a fake whose `lock` records every call and has no `going`.
- Only the bare path (no app-lock.js, tested as `bare`) and the server's 423 during the wait (`w.home`) actually carry `next=/`. The 423 route is only reached when app-lock did not lock first.
- Fix options: (a) app-lock.js's own `shown()`/`pageshow` pick `/` when the time since hidden is over 10 min (it would need its own hidden-since, or `lock(next)` re-callable until the navigation starts); (b) drop the lock half and word the docs, PLAN and the header as "lock keeps `next` = the page they were on". Ask the user which; the PLAN entry promises the first.
- Docs say it works: pwa.md (the new paragraph, "a due app lock after more than 10 minutes goes to `/lock?next=/`"), the reconnect.js header, and the PLAN entry. They must follow the decision.

## Low

L1. Item 2, the lock and the guards. Not a regression: before this change a due lock always did `location.replace("/lock?...")` with no save/typed-field/details guard, in reconnect.js and in app-lock.js alike, and still does. Only the destination after the passkey changes (see M1). Open input is lost either way. Judged as matching today's behaviour.

L2. A pending wait ignores the new age. In `cameBack()`, `if (wait) {...}` runs before `start(..., home)`. A "return" wait started after 2 min away (ping unanswered, server down), then hidden again for 15 min, resumes as a reload in place, not Devices. The reason is that `w.home` was fixed when the wait started. This is rare and harmless.

L3. Guards are checked at ping-answer time only. This is as before for the reload path. A field typed during the following "load" wait (3 s chip, up to 12 s restart) is lost by a navigation to `/`. The reload in place keeps the form record (scroll-restore.js), but `/` does not. The window is the same size as before. The cost is higher, because the whole page is gone and not only the place.

L4. `location.assign("/")` pushes a history entry. `location.replace` would keep Back from returning to the page just left. Taste only.

L5. Missing test: a 423 ping during a home "return" wait (`next: "/"`, reconnect.js line 628) and during the "load" wait for `/` (`pathOf`). The code is correct on reading. The jstest suite has only the bare-redirect case for locks.

## Checked, nothing found

1. Split. `away` is `> 60 s`, `home` is `> 10 min`, both from `hiddenAt`, which is wall-clock `Date.now()` set on visibilitychange-hidden or `pagehide`. A frozen page or a timer that never fired does not matter, because the age is computed at show time. A clock set back gives a negative age, so reload or nothing. `hiddenAt` is set once, and `pageshow persisted` goes through the same `cameBack()`. Guards on the home path are the same code as on the reload path: `!saving && !postAnswer()` at start, then `saving || postAnswer() || details[open] || edited` at the answer. The tests cover save, open details, POST answer and already-on-`/`.
3. No loop. On `/`, `hiddenAt` is null and nothing is pending, so the new page starts clean. `toHome` is false when `pathname === "/"`, so the reload in place applies there. The "load" wait to `/` is created with `home` false, so its 423 uses `pathOf(w.href)` = `/`. `go()` with a due lock gives `/`. On `/login` the return goes to `/` and the server redirects back to login, and `checkSignIn` handles the rest. `/lock` is untouched (early return).
4. Tests are lean: two jstest tests for the split and the guards, one for the lock `next`. They exercise every branch except as noted in M1 and L5.
5. Docs are English and consistent. `?v=10` is in head.html and the three pins in shell.rs. The header's change from "10 minutes" to "a minute (AWAY_MS)" is correct. The only wrong claim is the lock `next=/` (M1).
