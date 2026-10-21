# Design 58 - revision check

Checked on `main` at d8ea2611 (2026-10-21): `docs/design/58-storytel-jwt-courier.md` as revised (1020 lines, "Revision
for qa-58-design" at the end) against `docs/review/qa-58-design.md` (4 Medium, 13 Low, 9 nits), the user's decisions,
`CLAUDE.md`, PLAN.md ("Storytel login to the phone"), and the code the note cites. I read the whole body, not only the
revision table. Nothing was built or run; the note was not edited. Line numbers are the revised note's. `S/` =
`server/src/`, `M/` = `music/app/src/main/java/me/vibb/music/`.

## Per finding

Every row below is fixed in the body, not only in the table.

| # | Verdict | Evidence in the body |
|---|---|---|
| 58-1 (M) cold login at a room's advance; "no regression" wrong | **Fixed** | §1:53-54 ("up to the whole mint budget; the asset call takes what is left") replaces the false claim. §5.1:538-553: prefetch while `showsPlaying` (`PREFETCH_LEFT` = min(10 min, life/3), 2 min retries, "same back" by value) and at a room start. §2.1:169-171: `PHONE_MIN_LEFT` = min(15 min, life/2) "sits above the phone's prefetch point". Tests §8:816-818, mutation :840, §9:889-892 (advance with no `jwt` line before it). The arithmetic holds for life above 240 s (N-6 for below). |
| 58-2 (M) downloads stop after `unreachable`/`busy` | **Fixed** | §5.1:533-536 (one prompt retry at 5 s). §5.3:602-608: `storytelBackAt` replaces `storytelRefusedUntil` (`DownloadRun.kt:85`, `:139`; `Downloads.kt:172-174` all exist as described). Tests §8:826-828, §9:881-885. One path is left open: a 401 "same value back" (N-1). |
| 58-3 (M) 20 logins an hour; no bound on failed logins | **Fixed** | §2.3:237-257: backoff 1 -> 30 min for every outage (5xx, 408, 429, transport, timeout, `no_jwt`); server-wide cap `max(6, 2 x ceil(3600 / life))`; `busy` with the wait inside both. §5.1:572 `holdUntil` 30 s. §7.3:719-724 corrected ("that cap is the whole bound"). Tests :780-783. The save's probe is exempt (:245), and truly is: `login_with` touches no database and no `Account` state (`S/storytel.rs:684-705`). The cap is not a DoS lever: a hostile phone reaches about 3 forces + 1 refresh an hour, under 6. |
| 58-4 (M) e-mail inside the JWT found only after release | **Fixed** | §2.7:355-407: one `info` line per login with `life_s`, `left_at_login_s`, `claims`, `payload_has_email` (boolean only, never a value or claim name); the card shows it; step 1 may release alone; the line gates step 2 (§10:935-937). Q-A's two branches are in §2.7 (a)/(b). §7.1:693-694 and §7.2:707 state the possibility. `info` is visible by default (`main.rs:176`). Gaps: N-5, N-7. |
| 58-5 (L) session collides after restart | **Fixed** | §2.1:152-154 `"<generation>:<16 hex>"`, random per login. §5.1:545, :558 compare the JWT value. §3, §4 regexes agree. Test :784. |
| 58-6 (L) late answer vs cut timeout | **Fixed** | §5.1:526-531: the ask always runs 10 s in the account's scope as one shared `Deferred`; only the waiter waits `min(...)`. Test :813-814. |
| 58-7 (L) sweep force without `used`; failed force drops a good JWT | **Fixed** | §2.3:260-274 (session re-checked under `login_lock`, the sweep forces only while the kept session got the 401, counted only when it logs in), §2.1:161-162 (failed force keeps the JWT). Tests :777-779. The code facts are right (`storytel.rs:894`, `:910`, `:872`). |
| 58-8 (L) 5xx/429 stops every phone for an hour | **Fixed** (with Q-B) | §2.3:235-251 splits refusal (4xx except 408/429) from outage, with the recorded deviation. §2.5:322-329 keeps "refused" and "can't be reached" apart. See N-4 for the one status class left undefined. |
| 58-9 (L) `key` check wrote a row and warning per ask | **Fixed** | §2.2:205-209 (fingerprint only, no open, no record; `storytel_key` at most once per generation) and the error table :212-219. Left over: N-8. |
| 58-10 (L) parent can't see why it stalls | **Fixed** | §2.5:322-329 (four ordered cases, capability `storytel_jwt_v1`, §3.4, §4). §6 row 3 (:665) now says the report says `ok` and `waiting` gives no `login` wait. |
| 58-11 (L) `STORYTEL_BASE` needs `Reach` and a guard | **Fixed** | §2.6:336-353: loopback only, `Reach::Any` only for the overridden login and bookshelf, login only for `.example.invalid` (the save's probe too), `FORCE_MIN_AGE` 10 s while set. Test :786. Precedent checks: `spotify.rs:563-575`. |
| 58-12 (L) fixture and fake details | **Fixed in §8, ordering broken in §10** | `password_vectors` inline (:803), `fake_login_*` names avoid the existing bare `server/testdata/storytel/login_ok.json`, `--refuse` on both, `--revoke-after K` is "after the K-th accepted asset call" and matches §9:886, the fake's login issues `n+1`. But "step 4 can go with step 1" now breaks (N-2). |
| 58-13 (L) no shared answers file | **Fixed** | §8:753-767: the file, three byte-identical copies, readers on every side, CI paths in all three workflows, root `CLAUDE.md`. See "Contract file and CI" below. |
| 58-14 (L) IP-bound JWT unseen | **Fixed** | §9:917-922 (mobile data; the parent's own app logging in), §12:975-977. |
| 58-15 (L) purge inside `StorytelAccount.get` | **Fixed** | §5.5:628-639: from `Sync.writeReport` behind an `AtomicBoolean`, never on the mint path (`StorytelAccount.kt:57-65` `get` is `synchronized`, as QA said). Test :829, :837. |
| 58-16 (L) `exp - iat` overstates; 300 s floor vs force | **Fixed** | §2.1:134-140 (`min(exp - iat, exp - wall_now)` with a sane clock), §2.3:262 `FORCE_MIN_AGE` = min(5 min, life/2), `left_at_login_s` in the line. New edge: N-6. |
| 58-17 (L) §7.2 misstates a thief's take; no remedy | **Fixed** | §7.2:696-711 says "the same shared JWT", that removal rotates nothing, and names the remedy; the ⓘ gets it (§2.5:318-319). |
| Nits (9) | **Fixed** | AIDL comment :419; `mint` plain suspend :516-518; hand-built `RequestBody` :479-480 and `Bytes` :186; `DecodePaddingMode::Indifferent` :131; order spelled out :202-209; `offUntil` 2 min :568, :524; no startup nudge :308; version :673, :933 (`Cargo.toml` is 0.33.0, so 0.34.0 is right); `the_life_fits_the_launchers_bound` :785; old scope cancelled :519. |
| QA's tests, mutations, device checks | **Fixed** | All in §8:769-846 and §9:873-923. |

## The user's decisions

| Decision | In the note | Verdict |
|---|---|---|
| The password never leaves the server | §0.1, §7.1, §2.7 (a); (b) keeps the JWT on the server too | Correct. The e-mail caveat is stated, not hidden. |
| Q1: bump the generation at the update | §2.4:293-311. The SQL is right: `login_refused_generation` exists (`0072:14`), SQLite evaluates every `SET` against the old row, `saved_at` untouched, 0085 is the next number (last is 0084). Cost and "no startup nudge" stated | Correct. |
| Q2: log line with lifetime and yes/no `payload_has_email`, gating step 2 | §2.7:357-379, §10:935-937, §9:915 | Correct, but "no" is wrong when `claims=no` (N-5). |
| Q3: loopback-only `STORYTEL_BASE` with a fake-login guard | §2.6 | Correct. |
| Q-A decided after step 1, both branches written | §2.7 (a), (b), §10 step 2/2b, §11 | (a) is complete. (b) is a sketch (N-3). |
| Q-B: 5xx/408/429 is an outage with a short backoff, recorded deviation | §2.3:235-251, §5.1:570-572, §2.5; 26b §9 row owed in step 5 (:950) | Correct. 3xx and other non-200s are not classified (N-4). |

## New issues

### High

None.

### Medium

**N-1. A forced ask that the server declines is answered with the old JWT, so the phone stops the run with no way back.**
- **What the note says.** §2.3:267-269: when a force is not allowed (JWT younger than `FORCE_MIN_AGE`, the phone's 3
  forces used, backoff, cap or cooldown in the way), "the server answers what it holds ... the same JWT". §5.1:559-561
  turns that into `Failed(401)`, and `mintStopsRun` stops the run (`M/logic/Storytel.kt:115-116`).
- **The gap.** §5.3 puts only `Refused` and `LoginFailed` in `storytelBackAt`. A `Failed(401)` stop schedules
  nothing, so the worker waits for the next sync (6 h at worst). This is 58-2's failure again, on the 401 path.
- **It is not rare in the scenario §12 itself fears.** The parent's own Storytel app logs in elsewhere and revokes our
  JWT; the phone's first force falls inside the 5 min age, or the backoff or cap stands.
- **Also contradictory.** Rule 3 (§2.1:172-173) says that inside a cooldown, backoff or cap the answer is
  `refused`/`busy` with a wait. §2.3:267 says the same case answers "what it holds". A cooldown, backoff or cap is a
  wait the phone can honour; only "kept JWT is the same one and is too young" is not.
- **Fix.** For a declined force, answer `busy` with `retry_after_s` = the time to the earliest allowed force (age,
  backoff end, window end), or `refused` inside a cooldown. Keep "same JWT back" only for a stale that differs from
  the kept one (a newer JWT) and a spent per-phone force budget. Add `Failed(401)`-with-wait to `storytelBackAt`.
  Test: a revoked young JWT makes `busy`, and the worker returns at its end.

**N-2. "Step 4 can go with step 1" moves fixtures the music app still reads until step 2.**
- **What the note says.** §8:850-854 moves `login_request.json`, `login_ok.json`, `login_no_jwt.json` and
  `aes_vectors.json` out of `music/app/src/test/resources/storytel/` to `server/testdata/storytel/` under new names.
  §10:943-944 lets step 4 "go with step 1".
- **What breaks.** Until step 2 the music app still has `encryptPassword`/`logIn`, and `StorytelApiTest.kt:39`, `:53`,
  `:89`, `:202` read those four files. `scripts/fake-storytel.py:88-90`, `:262-263` reads them too, and runs in
  `music.yml` after the build. Step 1 is one commit across `server/`, `launcher/` and `music/`: moved with it, the
  music suite and the fake's selftest fail; kept in both places, the "move" is a copy.
- **Fix.** Say the fixtures are copied under the new names in step 1/4 and the music originals are deleted in step 2
  with the code that reads them. Or move step 4's fixture part to step 2. The fake's login half also has to serve the
  old app's login until step 2.

**N-3. Branch (b) is a sketch, not a written branch, and the probe script contradicts itself.**
- **What is missing in (b)** (§2.7:381-395): the `POST .../asset` route has no per-phone limit, no budget, no gate lane,
  no rows in `storytel_token_answers.json`, no capability, no `IStorytelAssetCallback.aidl` text, and its 10 s
  room mint "is 58-1's case again" with the fix "left to 2b". The user said both branches are written; this one defers
  its hard part (a room's advance) to an addendum that does not exist.
- **The probe script.** §2.7:399-404 says `scripts/storytel-mint-probe.py` is "stdlib" and prints "a QR code". The
  Python stdlib has no QR encoder. Either print the text only (a short URL with a signed query does not need a QR) or
  allow one pinned, vendored encoder.
- **Fix.** Add a short "2b in brief" list (route, limits, rows, callback, advance mitigation) so the user can choose
  (b) on the evidence. Drop the QR.

### Low

**N-4. Non-200 login answers other than 4xx/5xx/408/429 are unclassified.** Today any non-200 is `Refused`
(`storytel.rs:703-705`). §2.3:236-238 lists a refusal as "a 4xx other than 408 and 429" and the outage as 5xx, 408, 429,
transport, timeout or `no_jwt`. A 3xx the redirect policy did not follow, a 204, or a 1xx has no home. Say "any other
non-200 is an outage". Also say that `login_with` (the save's probe) classifies with the same function but touches no
backoff, to settle the wording at :237 ("every login the server makes") against :245.

**N-5. `payload_has_email=no` is false when the JWT could not be read.** §2.7:363-367 defines `yes` only; with
`claims=no` (opaque token, other base64, no dots) the boolean has nothing to read. Print `payload_has_email=unknown`
then, and say branch (a)'s "no" needs `claims=yes`. Otherwise an unreadable payload clears the gate.

**N-6. Short lives break two edges.**
- **The prefetch arithmetic** needs `(L - 60 s)/3 + 60 s < L/2`, i.e. `L > 240 s`. The sane-clock floor is 60 s
  (§2.1:136), so a still-valid older token with a short remainder gets "the same back" from `PHONE_MIN_LEFT`.
- **A fresh login with `left <= MARGIN`** answers `expires_in_s` 0 (§2.1:174-175, "whatever its life"), and the
  launcher's bound is 1..86 400 (`SpotifyTokenCourier.kt:64`, the Storytel parser copies it), so the answer becomes
  `launcher`.
- **Fix.** Floor the sane-clock life at 300 s too, or answer `unreachable` for a login that would leave under 2 x
  `MARGIN`. Test it with the other bounds.

**N-7. The measurement has no trigger and a thin sample.** §2.7:371-374 relies on "the sweep's next login prints the
line". With no ticked Storytel series the sweep does not log in; re-saving the login does (the probe), but the probe
(`login_with`) prints no §2.7 line as written, which says "at every login" under the `Account`. State that the save's
probe logs the line too, or tell the user to tick a series. Also keep `left_at_login_s` over two logins.

**N-8. The kept-JWT fast path still opens the sealed password.** §2.1:168 rule 1 calls `credentials(state)`, which
opens the row with the key on every call (`storytel.rs:620-637`). Rule 2 answers a kept JWT, which needs the generation
and the cooldown only. Read those two columns for the fast path and open the row only to log in. This also keeps the
password out of memory on every ask, in the spirit of decision 1.

**N-9. A still-valid kept JWT is withheld during a backoff, cooldown or cap.** Rule 2 answers a kept JWT only above
`PHONE_MIN_LEFT`; rule 3 then answers `busy`/`refused` below it even when the JWT has 10 valid minutes. A second phone,
or a restarted app, asking in an outage gets nothing although the server holds a working JWT (the sweep, via
`bearer`'s 60 s margin, would use it). Answer the kept JWT while it is above `MARGIN` whenever rule 4 cannot log in.

**N-10. Prefetch retries can outrun the per-phone limit.** `PREFETCH_RETRY_MS` is 2 min (§5.1:544): 30 asks an hour
against 20 (§7.3:715). `holdUntil` (30 s) does not apply to it. The server's backoff and the phone's `busyUntil`
usually stop it first, but a 502 `unreachable` with no backoff (the 8 s budget case) does not. Make the prefetch
honour `holdUntil` and back off (2, 4, 8 min), or count it in §7.3.

**N-11. The parent sees "None" for a new launcher with an old music app.** §6 row 2 (:664): the row reads the old
app's `none`, with no hint to update the music app. Show "Update Vibb Music" from the app's reported version, or
accept it explicitly.

**N-12. `STORYTEL_BASE` host parsing.** §2.6:339 accepts `localhost`/`127.0.0.1`/`::1`. Say the URL is parsed and the
*host* compared exactly (`http://127.0.0.1.evil.example` must be refused), as Spotify's `Config::from_env` does; add it
to `the_override_sends_only_a_fake_login`.

**N-13. PLAN.md's entry is already stale.** It says the server shares its JWT with "one Storytel login an hour at most,
only in hours with use", and lists the lifetime item as "small, any time". The note makes the cadence one login per 45
min of life plus a cap of 6 an hour, and step 5 takes the lifetime item. Update the PLAN entry with the revised design
(Q-A open, Q-B decided) now, not at step 5, since PLAN is the status page.

**N-14. Unnamed reader for `aes_vectors.json`.** §8:854 moves it to `server/testdata/storytel/`, but no server test
reads it (`password_vectors` stays inline, :803). Either `fake_login_fixtures_match` reads it, or it is deleted.

## Contract file and CI

| Check | Result |
|---|---|
| Byte-identical copies in `server/testdata/`, `launcher/app/src/test/resources/`, `music/app/src/test/resources/` | Stated (§8:753-756); each side compares the copies (:762). |
| `launcher.yml` build filter | Needs `server/testdata/storytel_token_answers.json` and `music/app/src/test/resources/storytel_token_answers.json`; stated (:764). Today's regex (`launcher.yml:74`) lists only `bridge_contract\|spotify_hosts` for music's resources, so the edit is real. |
| `music.yml` build filter | Needs the server's and the launcher's copies and `server/testdata/storytel/`; stated (:765). Today it has `server/testdata/spotify/` only. `scripts/fake-storytel.py` is already listed. |
| `server-ci.yml` build filter | Needs both app copies; stated (:766). Today's regex (`server-ci.yml:50`) lists `(launcher\|music)/.../spotify_hosts.json` and the launcher's three report files. |
| Root `CLAUDE.md` | Step 5 (:951) updates it. Wording owed: a sentence like the `power_report.json` one, and the three path lists. |
| The API change in one commit across `server/`, `launcher/`, `music/` | Yes: step 1 (§10:927-934) is one commit with the route, the courier, the AIDL pair, `bridge_contract.json`, the capability and the answers file. N-2 is the only thing that would split it. |
| `bridge_contract.json` / AIDL | Appended, never reordered; covered by the existing path lists (§3.6, `launcher.yml:74`, `music.yml:67`). `getStorytelLogin` and `IStorytelCallback.aidl` stay. |

## Where the JWT could hide

I checked every place the note and the code give:

| Place | Result |
|---|---|
| Server log | §2.2:226-231 and §2.7: device id and status at `debug`; the §2.7 line holds numbers and a boolean; the existing `login refused` line holds a status only. No JWT, payload, session or body. `PhoneJwt` and `KeptJwt` join the no-`Debug` probe. reqwest is held at `info`, so the `pwd=` URL is not logged (`main.rs:152-176`). |
| Server database, events | No security event per ask; `login_error` holds a code. The card's "lives N min / e-mail yes/no" must come from memory, not a column (the note does not say; add that line, N-7). |
| Launcher | In memory for one call; redacted `toString`; "no cache, no store, no log"; exception class names only (§4:483-486). |
| Music app | Memory only; redacted `toString`s with a `SourceScanTest` list; no `Log.` in `storytel/`; the `FAKE-JWT-` filter covers the bridge path (§5.6). The old login's files are purged off the mint path. |
| Room | No Room table is added or touched (§7.1). |
| `music_state` | Unchanged; `storytel` stays `none`/`ok`/`refused` (§3.7). |
| The (b) URL | A bearer for one book; "never logged or stored"; shown only on the terminal by the probe. |

No leak path. The session string is random and is not derived from the JWT.

## Migration order

The order holds: server (step 1 alone, or 1+3), then launcher, then music app (§6). The table's rows 1-7 are consistent
with the code (an older launcher turns the empty 404 into "delete yours", `MusicBridgeService.kt:236-239`; an unknown
oneway code is dropped). Step 3 carries the generation bump with the route's retirement, so a step-1-only server never
moves it. The one note: row 1 and 2 assume steps 1+3 together, as the text beside the table says.

## Spot checks (all hold unless noted)

- `S/device_routes.rs:104-107` the old route; `S/storytel.rs:58` `JWT_TTL`; `:684-705` any non-200 is `Refused`;
  `:865-882` the login's keep and the error arm (`session.jwt = None` at :872); `:892-912` the unconditional force;
  `:620-637` the per-call open and `storytel_key` record; `:1734`, `:1783` are the only `Reach::Any` in this file (tests).
- `S/main.rs:389-398` Spotify's `Config::from_env` and its loopback warning; `:705-707` the empty 404.
- `S/spotify.rs:1228-1250` `phone_ask` (a refused ask is not counted). `music_net.rs` exists; `PhoneAsks` is new there.
- `S/handlers/music.rs:4132-4138` `music_v1` is read from the status capabilities: the precedent for `storytel_jwt_v1`.
- `M/storytel/StorytelApi.kt:125-135`, `:184-195` the asset budget and the login's `min(LOGIN_TIMEOUT_MS, ...)`: §1's
  correction of the "no regression" claim is right.
- `M/sync/Downloads.kt:172-174`, `M/sync/DownloadRun.kt:85`, `:139` the refusal-only come-back.
- `M/sonos/SonosRenderer.kt:184`, `SonosController.kt:2376`, `:2384` the 6 s and 10 s.
- `M/spotify/TokenCache.kt:200` `PROMPT_RETRY_MS` = 5 s. `EpisodeArtTest.kt:232-233` `takeLast(2)`.
- `server/migrations/0072_music_storytel.sql:14`, `0049_music.sql:92` (`key_fingerprint`); the last migration is 0084.
- `server/testdata/storytel/` holds `bookshelf.json` and a bare `login_ok.json`; the music originals are `{status, body}`.
- `StatusCapabilities.kt:18-30` has no `storytel_jwt_v1` yet, as expected.

## Verdict

**Approve with changes (bounded). No blocker for starting step 1.** Every QA finding is fixed in the body, every user
decision is carried (Q-A is open by design, with its branches written), and there is no High. The password and the
JWT have no path into a log, file, Room table or `music_state`.

Fold in before the implementer starts (a short amendment to the note, no redesign):
1. **N-1** (Medium): a declined force answers `busy`/`refused` with a wait, not the old JWT; the worker returns.
2. **N-2** (Medium): the fixture move is a copy in step 1 and a delete in step 2.
3. **N-3** (Medium): a short "2b in brief" for branch (b); drop the QR from the stdlib probe.
4. **N-5** and **N-4** (Low): `payload_has_email=unknown`; any other non-200 is an outage.
5. **N-6**, **N-8**, **N-9**, **N-13** (Low) are cheap and worth doing in the same pass.

The rest can go to the implementer as notes. A code QA at step 1 should look at N-1, N-4 and N-8 first.
