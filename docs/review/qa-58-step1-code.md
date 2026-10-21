# Code QA - design 58 steps 1 and 4: the server's Storytel session, the courier, `STORYTEL_BASE`

- Commit: `8f6bd7d8` on branch `design-58-step1`, on top of `626ecbca` (`git diff 626ecbca..8f6bd7d8`, 46 files).
  Reviewed in the worktree `.claude/worktrees/agent-a3f7806ae613da040`.
- Read:
  - root, launcher, music and server `CLAUDE.md`;
  - `docs/design/58-storytel-jwt-courier.md`, all of it, with its "Step 1 status";
  - `docs/review/qa-58-design.md` and `qa-58-revision-check.md`;
  - the whole diff. Around it I read `storytel.rs` (the account, the probe, the shelf), `music_net.rs` (`Gated`,
    `Reach`), the token route, `save_storytel`, the launcher's `MusicBridgeService`/`MusicServer` and the music app's
    `SpotifyBridgeToken`/`Bridge.withBridge`.
- Ran (each suite alone, under `~/build.lock`):
  - server: `cargo test`, **1131 passed, 0 failed**. `cargo fmt --check` is clean. `cargo clippy --all-targets`
    shows the 13 known warnings and none new (`storytel.rs:144`'s `chunks_exact` is the old `encrypt_password`).
  - launcher: `testDebugUnitTest -PwarningsAsErrors=true`, **1506 tests, 0 failures, 0 errors, 0 skipped**.
  - music app: `testDebugUnitTest --rerun -PwarningsAsErrors=true`, **1170 tests, 0 failures, 0 errors, 0 skipped**.
  - `python3 -I scripts/fake-storytel.py --selftest`: OK (parts 1-6).
  - 31 one-change mutations, each reverted (table below). One probe run against the guard (finding 5), reverted.
    `git status` is clean.

## Findings

No High. No Medium. 5 Low, 9 nits.

### High

None.

### Medium

None.

### Low

**1 (Low) - a plain ask waits behind a login that sits in the fetch gate, although a usable JWT is kept.**
`S/storytel.rs:1568-1575` (the slow path takes `login_lock`), `:1633` (the login), `music_net.rs` `Gated::get` (the
gate is held for the whole request: a feed up to 120 s, the login up to 20 s after its wait).
- The fast path answers only a kept JWT with at least `PHONE_MIN_LEFT` = `min(15 min, life / 2)` left.
- Rule 3's "the kept JWT while it has over 2 minutes" applies only when **no login can be made** (a cooldown, the
  backoff or the cap).
- So with the kept JWT between 121 s and 15 min left (the normal refresh window, the last quarter of every life):
  1. one ask takes `login_lock` and logs in;
  2. its login waits in `music_fetch` behind a sweep's feed fetch, up to about 2 minutes;
  3. every other plain ask that needs a refresh queues on `login_lock`;
  4. the route's 8 s budget answers them all `unreachable`, although a JWT with up to 15 minutes left is in memory.
- Step 2 makes this visible:
  - a fresh music process (no JWT in memory) whose first mint is a room play fails (`login_failed`);
  - a download's mint fails twice (the prompt retry meets the same wait), and the worker comes back in 2 minutes.
- It is rare: a feed fetch must hold the gate at the same moment. The spawned task keeps the new JWT, so it heals
  itself.
- `a_kept_jwt_is_answered_while_a_shelf_read_waits` covers only a kept JWT above `PHONE_MIN_LEFT`.
- **Fix.** Treat "a login is in flight" like rule 3's "no login now", for a plain ask only:
  - in `phone_jwt`, before `self.login_lock.lock().await`, try `self.login_lock.try_lock()`;
  - when it is taken and the ask doesn't force, answer `kept_if(gen == plain.generation && left >= PHONE_FLOOR)` if
    there is one, else wait for the lock as now.
  - Test: hold the fetch with a login in flight, and check that a second phone still gets the kept JWT with 10
    minutes left within 1 s.

**2 (Low) - a login started before a save can overwrite the save's newer JWT, or drop it.**
`S/storytel.rs:1488` (`*lock(&self.jwt) = Some(kept)`), `:1493` (`lock(&self.jwt).take()` on a refusal), `:1797`
(`gate.last`), `:1794-1806` (`keep`).
- A phone's login takes `login_lock` and reads the credentials of generation g. The save's probe (`login_with`) never
  takes that lock.
- So a parent who saves while that login is in flight gets this order:
  1. `keep(g + 1, ..)` stores the probe's JWT;
  2. the phone's login then finishes and stores its g JWT over it, or, refused under the old password, `take()`s
     the probe's JWT;
  3. `gate.last` gets the old login's facts.
- Nothing wrong is ever handed out, because every reader compares the generation. The cost:
  - one extra Storytel login at the next ask;
  - the card shows the old login's facts until then;
  - a refusal of the old password drops the new JWT. Its `record(Refused)` for g is a no-op (`WHERE generation =
    g`), so no cooldown is stamped.
- **Fix.** In `login`, store only when no newer generation is kept:
  `if lock(&self.jwt).as_ref().is_none_or(|k| k.generation <= generation)`, and the same guard on the refusal's
  `take()` and on `gate.last`. Or re-read `Plain` after the login and keep nothing for a stale generation.

**3 (Low) - the route's `Cooldown -> refused` mapping is unpinned (mutation 16 survives).**
`S/handlers/music_api.rs:524`.
- The contract test produces `refused` from a fresh 403 (`StorytelError::Refused` -> 3600).
- The commonest refused answer is every ask inside the hour after it: `Cooldown { until }` -> 409 with the seconds
  left. No route test reaches it. Mapped to `busy`, it passes all 67 Storytel tests.
- `a_declined_force_answers_a_wait` pins `Cooldown` only at `phone_jwt`'s level.
- Mutation 17 (`Backoff -> refused`) survives too, but that arm is unreachable: `phone_jwt` turns the backoff into
  `Busy` under the lock, and `login` checks the same gate after it.
- **Fix.** In `the_token_route_answers_the_shared_contract` (or the check-order test), ask once more after the 403.
  Assert 409 `refused` with a `retry_after_s` in 3590..=3600, and that no login was sent. Leave the `Backoff` arm, with
  a comment that it is defensive.

**4 (Low) - `server-ci.yml` doesn't watch the music fixtures the server's test reads.**
`.github/workflows/server-ci.yml:51`, `S/tests/music_storytel.rs:2697-2741`.
- `fake_login_fixtures_match` compares four files in `music/app/src/test/resources/storytel/` with the server's
  copies: `aes_vectors.json`, `login_request.json`, `login_ok.json` and `login_no_jwt.json`.
- `server-ci.yml`'s path filter doesn't include that directory. A music-only change to one of them runs `music.yml`
  (its selftest checks the same equality, so it is caught there) but never the server's test.
- The root `CLAUDE.md` paragraph doesn't say the server reads them either.
- It ends with step 2, which deletes the originals and that half of the test.
- **Fix.** Add `music/app/src/test/resources/storytel/` to `server-ci.yml`'s regex with a comment "(design 58 step 1;
  gone in step 2)". Or drop the music-original half of `fake_login_fixtures_match`: the fake's selftest already
  asserts it.

**5 (Low) - under `STORYTEL_BASE`, the guard's refused logins count in the phone-login cap: after six asks, `busy`
for an hour.**
`S/storytel.rs:1629` (`phone_logins.push_back(now)` before `login`), `:1093` (the guard returns `NotConfigured`).
- Probed (reverted). A real e-mail stored with the override set, then eight phone asks:
  - asks 1-6: `NotConfigured` (route: `404 off`);
  - asks 7 and 8: `Busy { wait: 3599.99 s }`.
- §6i B's guard step says "a phone's ask answers `404 off`". That holds only for the first six asks in an hour.
- Worse, the six entries stay in the window. If the tester then saves `k@example.invalid` again, every phone's ask
  gets `busy` for up to an hour until the window frees up, and the chain looks broken.
- Only the emulator chain is affected. Production never has the override.
- **Fix.** Check the guard before counting: in `phone_jwt`, after `open_login`, return `NotConfigured` when
  `self.config.fake_base.is_some() && !fake_login(&credentials.login.email)`, before `push_back`. Or push only when
  `login_with` sent a request. Extend `the_override_sends_only_a_fake_login` with eight asks.

### Nits

1. **The card's backoff wording.** `S/music.rs:667` says "the server tries again in a few minutes" for a backoff of up
   to 30 minutes. Write "a little later" or "within half an hour". The picker's "trying again at HH:MM" is right.
2. **The guard's wording.** `S/main.rs:410` and the `Config` doc (`S/storytel.rs:171`) say "a login ending
   .example.invalid". The rule (rightly, deviation 3) is "the domain `example.invalid` or a name under it". Say that.
3. **The save's notice under the guard.** `save_storytel` (`S/handlers/music.rs:1518`) says "Saved, but Storytel
   couldn't be reached to check it." when the guard didn't ask at all. The card's new line explains it, but the
   notice could say "Saved; STORYTEL_BASE is set, so Storytel wasn't asked."
4. **The security event for an outage.** An outage at the save (`503`, `no_jwt`) is still logged as
   `storytel_login_refused` (`S/handlers/music.rs:1491`). Its detail says "Storytel answered 503". Consider
   `storytel_login_failed` for `outage: true`. The admin label for the old event stays.
5. **`toString`s print the session.** `StorytelTokenAnswer.toString` (`L/music/StorytelTokenCourier.kt:48`) and
   `JwtResult.Ok.toString` (`M/bridge/StorytelBridgeToken.kt:23`) print the session. §7.1 says the session "is not
   logged". It isn't a secret, but `session=<set>` would keep the rule simple for step 2's callers.
6. **A clock ahead.** With a sane-year wall clock that runs ahead of Storytel's by more than `life - 121 s`, every
   login is `short_life`, which leads to the 30-minute backoff (`S/storytel.rs:307-308`). This follows §2.1 and is
   unlikely: fake-hwclock restores an *older* time. The line's `left_at_login_s` would show it. Worth one sentence in
   §12, or a `warn` when `exp - now < exp - iat - 300` on a fresh login.
7. **The cap's life from a probe's short JWT.** `cap_wait` uses `gate.last.left_s`. A probe that kept a JWT with 60 s
   left (`keep` doesn't apply `PHONE_FLOOR`) raises the bound to 120 an hour until the next login. The backoff after
   its `short_life` still bounds it. Use `max(left_s, PHONE_FLOOR)`, or skip `last` for a short life.
8. **The version.** `server/Cargo.toml` 0.35.0, skipping 57's held 0.34.0, is fine. But if this releases first, 57's
   prep must move to 0.36.0: `update.sh` installs the newest tag, so a later 0.34.0 would never be installed. Put it
   in the merge note.
9. **Docs owed in step 5.** `server/CLAUDE.md`'s "Device-facing API" list doesn't name `POST
   /api/devices/music/storytel/token` yet, although the route is live from this commit. One line now would help the
   next agent.

## The adversarial checks

- **Statuses and HTTP codes against `storytel_token_answers.json`.**
  - 200 `ok`, 404 `off`, 409 `refused`, 429 `busy`, 502 `unreachable`, 503 `key` and 500 `unreachable` are produced
    by `the_token_route_answers_the_shared_contract`: the status, the keys, their types and `no-store`.
  - `retry_after_s` is always 1..3600:
    - the cooldown is at most 3600 (whole-second stamps, `now >= at`);
    - the backoff is at most 1800 (+1);
    - the cap, the force window and the phone limit are at most 3600;
    - `FORCE_MIN_AGE` is at most 300;
    - every value is rounded up and at least 1.
  - `expires_in_s` is `left - 60`, at least 61 (every path answers at least `PHONE_FLOOR`) and at most 86 340: inside
    the launcher's 1..86 400.
  - The three copies are byte-identical. The server, `MusicPlanTest` and `ContractFilesTest` compare them (mutations
    21 and 25). All three workflows list them.
  - Gap: finding 3.
- **The bridge.**
  - `getStorytelToken` is appended last. The import doesn't move any transaction code (`FIRST_CALL_TRANSACTION +
    13`). `IStorytelTokenCallback.aidl`, `IMusicBridge.aidl` and `bridge_contract.json` are byte-identical (`cmp`).
  - `MusicPlanTest` covers the 4 AIDL files, the 14 methods and 14 `check()`s, and the contract's statuses and
    timeout. So do `BridgeTest` and `EpisodeArtTest`'s last three (mutation 26).
  - **An old launcher with a new music app:** the unknown oneway transaction is dropped by the old `Stub.onTransact`
    (`false`, no reply). From step 2 the app's `withTimeoutOrNull(10 s)` makes it `Unreachable("launcher")`. In step
    1 nothing calls it.
  - **A new launcher with an old music app:** the method is never called. The capability goes out unread (the server
    reads it in step 3), and 21 capabilities stay well inside the server's 4096-byte cap.
- **The login machinery.**
  - The JWT lives in a std `Mutex` that is never held across an `.await`. `login_lock` is never taken while holding
    `session` in the phone path. The sweep holds `session` and then `login_lock`, and nothing takes them the other
    way round, so there is no deadlock.
  - The spawned task is detached on the route's timeout and stores its login (`a_login_past_the_budget_is_kept...`).
  - **Double logins:**
    - two forces with the same `stale` make one login, because the second re-reads under the lock and gets the newer
      JWT;
    - the save's probe can race a phone's login (finding 2).
  - The backoff doubles 1 -> 30 minutes per generation, and a login or a new generation resets it (mutations 2 and
    3).
  - The cap is `max(6, 2 x ceil(3600 / life))` (mutations 4 and 23).
  - The per-phone limit is 20 an hour, counted after `off` and before `key` (mutation 18).
  - **The force:**
    - the session is re-checked under the lock (mutation 13);
    - `FORCE_MIN_AGE` holds (mutation 5);
    - each phone has 3 forces an hour, counted only when it logs in (mutation 19);
    - the cooldown is checked first and answers `refused`;
    - a declined force never gets the kept JWT (mutation 6);
    - a forced login that fails as an outage keeps the JWT for the others (mutation 20).
  - **During a backoff** a plain ask gets a kept JWT with at least 121 s left (mutation 11).
  - **The kept path reads the plain columns only.** The key is checked by fingerprint, and `open_login` runs only
    before a login (mutation 12).
- **Lifetime.**
  - `min(exp - iat, exp - now)` with a sane clock, floored at 60 s; `exp - iat` floored at 300 s otherwise; capped at
    24 h; 30 minutes as the fallback (mutation 14). Floats are read.
  - A fresh life under 121 s is `ShortLife`, an outage (mutation 7).
  - The skew corner is nit 6.
- **Privacy.**
  - Every `tracing`/`Log.`/`log(` line in the diff was grepped for `jwt`, `token`, `session`, `stale` and `body`.
    They carry device ids, status words, codes, numbers and exception class names only. The fake logs `jwt #<n>`,
    never a value.
  - The login line is exactly `life_s`, `left_at_login_s`, `claims` and `payload_has_email` (yes/no/unknown). Its
    test captures every level and finds neither the e-mail, nor the claim name, nor the JWT.
  - `Jwt`, `Login`, `KeptJwt` and `PhoneJwt` have no `Debug`/`Display` (probe test). `StorytelTokenAnswer` and
    `JwtResult.Ok` redact the JWT (mutations 27 and 31).
  - Nothing is written to a column, `security_events` or `music_state`. The card's facts come from memory.
- **`STORYTEL_BASE`.**
  - It is parsed as a URL, with the host compared exactly to `127.0.0.1`, `[::1]` or `localhost`, http only, a port,
    no user, path `/` (mutation 10).
  - `Reach::Any` applies only to the login and the bookshelf.
  - Every login goes through `login_with` (`grep login_url`), and its guard runs before the URL with `pwd=` is built.
    That covers the sweep, the phones and the save's probe (mutation 9). A real password can't reach the override.
  - The override is read once at start, so no real JWT can exist in memory under it.
  - Finding 5 is the guard's one side effect.
- **Fixtures.**
  - The only token values are `FAKE-JWT-<n>`, and the claims JWTs are built at run time (no `eyJ`; the contract test
    and the selftest assert it).
  - `fake_login_*` and `aes_vectors.json` are copies: the music originals are still there, and equal (mutation 22).
- **The PWA.** No form changed. Only a `<p>` inside the Storytel ⓘ, Askama-escaped, so there is no save jump to
  check.

## The implementer's deviations

| Deviation | Verdict |
| --- | --- |
| Server 0.35.0 | Accepted; the release order is nit 8. |
| §2.5's PWA rows and the reading of `storytel_jwt_v1` wait for step 3 | Accepted. "Update Vibb One" and "the phones never get the password" would be false while the music app still logs in itself. The launcher already sends the capability, so step 3 has data. |
| The guard takes `example.invalid` or any name under it | Accepted. The literal "ending `.example.invalid`" refuses the fixture's own `k@example.invalid`. The test covers `evilexample.invalid`, `example.invalid.example.org` and a missing `@`. The wording is nit 2. |
| `payload_has_email=unknown` for `FAKE-JWT-1` | Correct. §2.7 since QA N-5; §9's `no` is stale. |
| The check card's backoff wording | Accepted as a code-only card; the words are nit 1. |
| An oversized body is a plain ask | Accepted. The answer stays JSON, and the launcher's body is under 50 bytes, so a real force is never cut. The test covers it. |
| The 121 s minimum on every path | Accepted, stricter than the note: `expires_in_s >= 61` holds for the fast path too. |
| The guard's card line (`storytel_off`) | Accepted. Only the guard records `storytel_off` while a login is stored. The save's notice is nit 3. |
| The probe's wording for 200 without a session | Correct. `no_jwt` is an outage (§2.3), no longer "refused that e-mail and password". |
| Spotify's `phone_ask` moved to `music_net::PhoneAsks` | Accepted. Same logic line for line, the Spotify tests are green, and `phones()` replaces the map peek. |
| The test capture made `pub(crate)` | Accepted. It is inside `#[cfg(test)] mod tests`, and the route tests run on the current-thread runtime, so the spawned task's lines are captured. |

## Mutations

Server: `cargo test --bin kid_phone_server -- <filter>` (`storytel` = 67 tests unless named). Apps:
`testDebugUnitTest --tests <class>`. Each mutation is one change, reverted.

| # | Mutation | Result |
| --- | --- | --- |
| 1 | `classify_login`: 429 is a refusal | caught - `classify_login_splits_refusals_from_outages`, `save_probes_then_seals` |
| 2 | the backoff doesn't double | caught - `a_failed_login_backs_off_for_every_phone` |
| 3 | the backoff without its 30-minute cap | caught - `a_failed_login_backs_off_for_every_phone` |
| 4 | the cap without `2 x` | caught - `phone_logins_are_capped_server_wide` |
| 5 | a force without `FORCE_MIN_AGE` | caught - `a_declined_force_answers_a_wait`, `the_stale_body_is_read_leniently` |
| 6 | a declined force gets the kept JWT (`kept_on` without `!force`) | caught - 3 tests |
| 7 | a fresh life checked against `MARGIN`, not `PHONE_FLOOR` | caught - `a_short_fresh_life_is_an_outage` |
| 8 | `payload_has_email`: any decoded payload is `no` | caught - `payload_has_email_is_a_word` |
| 9 | the guard's `.example.invalid` without the dot | caught - `the_override_sends_only_a_fake_login` |
| 10 | the loopback host by prefix (`127.0.0.1.evil.example` passes) | caught - `the_override_sends_only_a_fake_login` |
| 11 | no kept JWT through a backoff | caught - `a_kept_jwt_is_handed_out_during_a_backoff` |
| 12 | the fast path opens the sealed login first | caught - `a_kept_jwt_needs_no_open` |
| 13 | the fast path without the `stale` re-check | caught - 4 tests |
| 14 | the life ignores `exp - now` | caught - `jwt_life_from_claims`, `a_short_fresh_life_is_an_outage` |
| 15 | `expires_in_s` without the margin | caught - `the_token_route_answers_the_shared_contract` |
| 16 | the route: `Cooldown` -> `busy` | **NOT caught** (finding 3) |
| 17 | the route: `Backoff` -> `refused` | **NOT caught** (unreachable arm, finding 3) |
| 18 | 21 asks an hour | caught - `the_token_route_checks_in_order` |
| 19 | a force not recorded against the phone | caught - `a_declined_force_answers_a_wait` |
| 20 | an outage drops the kept JWT | caught - `a_force_relogs_only_for_the_stale_kept_old_jwt`, `a_kept_jwt_is_handed_out_during_a_backoff` |
| 21 | the music copy of the answers file drifts | caught - `the_token_route_answers_the_shared_contract` |
| 22 | the music original `login_request.json` drifts | caught - `fake_login_fixtures_match` |
| 23 | the cap reached at `>` instead of `>=` | caught - `phone_logins_are_capped_server_wide` |
| 24 | launcher `toString` prints the JWT | caught - `StorytelTokenCourierTest` (never prints its JWT) |
| 25 | the server copy of the answers file drifts (launcher side) | caught - `MusicPlanTest` (the copies) |
| 26 | a method appended in music's AIDL only | caught - `BridgeTest` (byte-identical) |
| 27 | music `Ok.toString` prints the JWT | caught - `StorytelBridgeTokenTest` |
| 28 | launcher waits bounded `0..3600` | caught - `StorytelTokenCourierTest` (waits) |
| 29 | launcher: a 404 without a word is `launcher` | caught - `StorytelTokenCourierTest` (no status word) |
| 30 | launcher: `ok` accepted on any HTTP status | caught - `StorytelTokenCourierTest` (an ok that isn't whole) |
| 31 | music: `ok` with a life of 0 | caught - `StorytelBridgeTokenTest` (an ok that isn't whole) |

29 caught, 2 survive (16, 17; 17 is an unreachable arm).

## Verdict

**Approved with fixes.**
- The contract is right on all three sides, and the copies and CI paths hold. The bridge is appended safely both
  ways.
- The login machinery does what §2.1-§2.3 say: the lock split, the spawned login, the backoff, the cap, the force's
  rules, the declined force's wait, the kept JWT through an outage, and the plain-column fast path. The tests pin
  nearly all of it.
- No secret reaches a log, a column or a `toString`. The override can't send a real password.
- Before the merge, in one fix commit:
  - findings 3, 4 and 5 (a test, a path, a guard check);
  - finding 2's generation guard, a few lines;
  - finding 1, which matters from step 2: fix it now, or put it in step 2's brief.
- The nits can go in the same commit; nit 8 goes in the merge note.
- Step 1's server can release alone for §2.7's measurement once the fixes are in.
