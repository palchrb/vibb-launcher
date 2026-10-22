# Code QA - design 58 step 2: the music app on the server's Storytel JWT

- Commit: `ed055a8c` on branch `design-58-step2`, on top of `fa33001d` (`git diff fa33001d..ed055a8c`, 40 files).
  Reviewed on branch `58s2-qa`.
- Read: design 58 §5, §6's table, §7.1-7.2, §8, §9, §10 step 2 and "Step 2 status"; `qa-58-step1-code.md`; the whole
  diff. Around it: `Bridge.withBridge`, `SonosPoller`, `SonosController.mintFor`, the server's `phone_jwt` fast path
  (`S/storytel.rs:1585-1593`, `PHONE_MIN_LEFT_CAP` `:105`).
- Ran, under `~/build.lock`:
  - music app `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1191 tests, 0 failures,
    0 errors, 0 skipped**;
  - `python3 -I scripts/fake-storytel.py --selftest`: OK, parts 1-7;
  - 12 one-change mutations, each reverted (table below). One throwaway probe test for S2-1, deleted afterwards.
    `git status` is clean.
- Contract files: `storytel_token_answers.json` is byte-identical in all three places (md5 `867598ee...`), and so is
  `bridge_contract.json` in both apps (`8b8028ed...`). The bridge AIDL directories are identical (`diff -r`).
- CI filters: `server-ci.yml:51` drops `music/app/src/test/resources/storytel/`, and no server test reads that
  directory any more (`grep`). `music.yml:69` still watches `server/testdata/storytel/`, the server's answers file and
  the fake, and the fake's selftest pins the music fixture directory to the two asset files. Correct.

## Findings

No High. 1 Medium, 3 Low, 2 test gaps (Low), 2 nits.

**S2-1 (Medium) - the fake's `/fake/jwt` never ages and never renews, so §6i A's room-advance check can't pass.**
`scripts/fake-storytel.py:258-284`; `M/storytel/StorytelSession.kt:132`, `:253`; `docs/testing/emulator.md:927-931`.
- The fake answers a plain ask with the same `FAKE-JWT-<n>` and a constant `expires_in_s` (`--jwt-ttl`). Only a
  `stale` naming the current session issues the next JWT.
- The server does neither: its `expires_in_s` counts down, and a plain ask below `min(15 min, life / 2)` left gets a
  new JWT (`S/storytel.rs:1590`). The phone's rules rely on that:
  - the prefetch counts "the same JWT back" as a failed prefetch (`:132`);
  - `keep()` ignores a value equal to the kept one (`:253`), even when the kept one has expired.
- **Failure** (checked with a probe test that answers as the fake does, `--jwt-ttl 300`, ticks to 360 s): the
  prefetch at 200 s gets `FAKE-JWT-1` back, which counts as a failure and starts the 2-minute backoff. The JWT expires at
  300 s. The advance at 360 s asks (`advanceAsked=1`), and so does every mint after it: the expired JWT stays in `kept`,
  and each answer with its value is dropped (`nextMintAsked=1`). §6i A's "no `jwt` line just before `asset 222`"
  fails, and with it step 6's emulator check of QA 58-1. Real devices are not affected.
- **Fix** (in the fake, so that it models the server):
  - keep an issue time per JWT, and answer `expires_in_s = ttl - age`;
  - on a plain ask below `min(900, ttl / 2)` left, issue the next JWT and log `jwt #n (renewed)`;
  - add a selftest line in part 7.
  - Optional belt in `keep()`: compare with the kept JWT only while it is unexpired
    (`kept?.takeIf { now < it.expiresAtMs }?.value`). Then a same-value answer with a later expiry replaces an expired
    JWT.

**S2-2 (Low, design level) - in a long outage the download job comes back every 2 minutes, with no growth.**
`M/sync/DownloadRun.kt:146`, `StorytelSession.kt:183`, `:292`; design §5.3 and §6, row 3.
- Row 3 (old launcher, new app) is bounded per job:
  - two asks of 10 s (+ the bind, each at most 20 s), the 5 s retry between them, and no server request (the old
    launcher drops the call);
  - then `LoginFailed("unreachable", +2 min)`.
- It is not bounded in time. While any Storytel book is wanted, that is about 30 jobs an hour, each awake for about
  25 s with two launcher binds, until the launcher is updated. Doze defers the jobs while the phone is idle, but not
  while it charges or is in use.
- The same happens with rows 1 and 7, and with a new launcher while the Pi is unreachable for a weekend. Before design
  58, a failed login waited for the next sync.
- **Fix:** double the come-back for consecutive `unreachable`/`launcher` stops (2, 4, 8, up to 30 min), counted in the
  session's memory and reset by any `Ok`. A process restart starting at 2 min again is fine. Or accept it explicitly
  in §6's row 3.

**S2-3 (Low) - the prefetch's launch has no catch, on a scope without a handler.**
`M/sonos/SonosRenderer.kt:305` (`actorScope.launch { StorytelAccount.get(context).prefetchIfDue() }`), `:83`
(`SupervisorJob() + Dispatchers.IO`).
- The mint path catches every `Exception` (`SonosController.mintFor`, "never another exception", vibb 4).
- The new prefetch runs on every poll tick of a playing book, and it runs `StorytelAccount.get` (which can build the
  account: the OkHttp client, the endpoints) and `generation()` (`Bridge.config`) with no guard.
- Anything unexpected thrown there is uncaught in a `SupervisorJob` scope, and that crashes the process mid-play. The
  risk is small, but the call is new and runs often.
- **Fix:** `try { ... } catch (e: CancellationException) { throw e } catch (_: Exception) {}` around the body, as
  `mintFor` does.

**S2-4 (Low) - `debugSwitchJwt` isn't applied on the fake's own route, although the code and the docs say it is.**
`M/storytel/StorytelAccount.kt:24`, `:83-88`; `music/CLAUDE.md` ("whichever way it came"); design §5.6.
- The unmanaged branch returns `api.debugJwt(...)` unfiltered. Only the bridge branch is filtered.
- There is no leak: that JWT came from the same loopback or `10.0.2.2` host it goes back to.
- The stated rule is still false, and the two-branch shape is more code than one filter.
- **Fix:** `val answer = if (...) runInterruptible { api.debugJwt(fake, stale) } else StorytelBridgeToken.get(app, stale)`,
  then `if (fake == null) answer else debugSwitchJwt(answer)`.

**S2-5 (Low, test gap) - the generation guards on an answer are untested (M3, M9 survived).**
`StorytelSession.kt:167` (the re-read after an answer), `:250` (`keep()` drops an older generation's answer).
- `bearer`'s kept check (`:156`) doesn't look at the generation. It relies on `keep()` and `settle()`.
- Without `:250`, an ask that was in flight across a parent's save leaves the old login's JWT kept under the new
  generation. Without `:167`, the waiter uses it once.
- **Fix:** one test: hold an ask on a gate, set `gen = 2`, call `state()`, then complete the gate with `ok(1)`. The
  waiter asks again, the next mint uses `FAKE-JWT-2`, and `asks` has two plain entries.

**S2-6 (Low, test gap) - `await`'s `ensureActive` is untested (M4 survived).**
`StorytelSession.kt:197`; `StorytelSessionTest` "a late answer is kept...".
- The behaviour is right: a cancelled caller's `CancellationException` goes up, and a cancelled ask (a debug rebuild)
  is `Unreachable("launcher")`. So a room's actor never ends on an ask's cancellation: the actor never cancels its jobs,
  so `ensureActive` passes there, and its catch turns the ask's cancellation into an outage (test `:105`).
- Without `ensureActive`, a cancelled download falls through into the prompt retry. In production `delay` then throws,
  so it is a belt. The test passes only because its `sleep` doesn't suspend.
- **Fix:** in that test, assert `slept.isEmpty()` after `job.cancel()`.

**Nits.**
1. `validCid` is checked twice, in `StorytelAccount.mint:42` and `StorytelApi.assetUrl:73`. Keep the one in `assetUrl`.
2. A force that waits on a prefetch which ends `unreachable` meets the hold at once (`:157`, `retried = false`), so
   it gets no prompt retry. That is acceptable (the hold is the point), but the "Step 2 status" doesn't mention it.

## The focus points

- **`bearer`'s loop:**
  - The not-ours wait is bounded by the deadline: `wait` is recomputed each turn, and below 1 s the loop ends with
    `deadline`.
  - The retry skips only the hold, never a server wait.
  - A download fits ask (10 s), 5 s, ask (10 s) and the 15 s asset call inside its 60 s. A room's 4 s never retries.
  - A deadline exit starts no ask.
  - The generation re-read is correct but untested (S2-5).
- **Cancellation:** correct (S2-6). The asset call is `runInterruptible`, and the ask lives in the session's scope.
- **`keep()`:**
  - A force's named JWT is never kept, and a same-value answer doesn't reset the backoff (M6 caught).
  - An older generation's answer is dropped, untested (S2-5).
  - The same-value rule and the fake: S2-1.
- **The prefetch:** the due check reads the hold and every wait, it backs off 2/4/8 minutes (M7 caught), and the 240 s
  guard holds (M12 caught). At most one runs at once. A failed prefetch's hold also blocks a tap for 30 s, as designed.
- **The 401 rule:** one forced re-ask per mint, and a second 401 is `Failed(401)` (M2 caught). Across runs, the server's
  `FORCE_MIN_AGE` and its login cap bound the loop. The worker comes back at +2 min (M10 caught).
- **`StorytelLegacy`:** the four files and the alias; once per process (M5 caught); from `writeReport` only, which
  every sync, download run and the update's sync reach; nothing reads the files (`StorytelLogin` is gone, and
  `SourceScanTest` pins it).
- **Secrets:** no `Log.` in `storytel/`. `Jwt`, `JwtResult.Ok`, `Mint.Url`, `StorytelRequest` and `StorytelResponse`
  are redacted. Legacy's only line is fixed and debug-only. `stale` goes only to the fake's loopback.
- **The fake:** its rows come from the shared answers file, and the selftest covers `--refuse`, `--off`, `--busy`,
  `--jwt-down` and a stale ask after a revoke. Its JWT model is S2-1.

## The deviations

| # | Deviation | Verdict |
|---|---|---|
| 1 | The 401 rule in `StorytelSession.mint` with the asset call passed in | Accepted: pure and tested, and the callers are unchanged. |
| 2 | `backAtWallMs: Long`, with `deadline` at 2 min | Accepted: every stop has a time. |
| 3 | Every `unreachable` answer sets the 30 s hold, and the retry passes it | Accepted. It also covers taps during the retry's 5 s. |
| 4 | A waiter's timeout is `unreachable` for the mint, with no hold | Accepted: the ask goes on, and the next mint shares it. |
| 5 | A force waits for a plain ask in flight, then asks with `stale` | Accepted: still one ask, and bounded by the deadline. See nit 2. |
| 6 | The start's prefetch is in `load(playNow)`/`play()` | Accepted. The launch races the actor's job, but both share one ask. See S2-3. |
| 7 | §9's room check uses `--jwt-ttl 300` | The reasoning is right (N-6), but the check still fails with this fake: S2-1. |
| 8 | No log line for a real JWT under the switch; Legacy logs only when something was there | Accepted. See S2-4 for the filter itself. |

## Mutations (each reverted, run with its targeted test class)

| # | Mutation | Result |
|---|---|---|
| M1 | the hold off (`waiting`'s `hold && ...` to `false`) | caught - prompt retry/hold, the prefetch in the hold |
| M2 | the 401's second `bearer(first)` to `bearer(null)` (no forced re-ask) | caught - 4 tests |
| M3 | the generation re-read after an answer removed | **survived** (S2-5) |
| M4 | `await`'s `ensureActive()` removed | **survived** (S2-6) |
| M5 | the purge's `compareAndSet` to `set(true)` | caught - `StorytelLegacyTest` |
| M6 | `keep()`'s same-value rule removed | caught - the prefetch backoff |
| M7 | the prefetch due check without `prefetchAt` | caught - the prefetch backoff |
| M8 | the deadline `wait < MIN_WAIT_MS` to `wait <= 0` | caught - under a second to wait |
| M9 | `keep()`'s older-generation drop removed | **survived** (S2-5) |
| M10 | `storytelBackAt` after a 401 stop removed | caught - `StorytelDownloadTest` L4 |
| M11 | the prompt retry's fit check removed | caught - 2 tests |
| M12 | the prefetch's 240 s life guard removed | caught - QA N-6 test |

## Fixed

- S2-1: Fixed in 11b1751c - the fake's `/fake/jwt` ages its JWT and renews it below `min(900, ttl / 2)` left (selftest
  part 7); `keep()` and the prefetch's "same back" compare only with an unexpired kept JWT (test "an expired JWT is
  replaced by the same value with a later expiry").
- S2-2: Fixed in 11b1751c - the outage come-back doubles 2, 4, 8, 16, 30 min over consecutive outage stops, reset by
  any `ok` (test "the come-back after outages grows..."); recorded in design 58's Step 2 status.
- S2-3: Fixed in 11b1751c - the prefetch launch catches every `Exception`, cancellation rethrown (`GuardsTest` pins it).
- S2-4: Fixed in 11b1751c - `debugSwitchJwt` applies to both branches of the ask.
- S2-5: Fixed in 11b1751c - test "an ask in flight across a parent's save" (M3, M9).
- S2-6: Fixed in 11b1751c - `slept.isEmpty()` after the cancel (M4).
- Nit 1: Fixed in 11b1751c - the consumable id is checked in `StorytelApi.assetUrl` only.
- Nit 2: Fixed in 11b1751c - recorded in design 58's Step 2 status (accepted: the hold is the point).
