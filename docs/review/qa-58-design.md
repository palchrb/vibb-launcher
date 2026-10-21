# Design QA - 58 Storytel's session from the server: the family's password never leaves it

Reviewed on `main` at 7b5a9a4b (2026-10-21), with the note untracked in the working tree. This is the one design QA
round. I read `docs/design/58-storytel-jwt-courier.md` against:
- `CLAUDE.md`, `launcher/CLAUDE.md` (the bridge, the Spotify token courier), `music/CLAUDE.md` (the Storytel
  account, downloads, a room, secrets, the debug switch), `server/CLAUDE.md` and `docs/notes/server/music.md`.
- `docs/design/26b-storytel.md` (§1.1, §4.1-§4.2, §5.1) and `docs/design/26c-spotify.md` (§2.6, §5.5, §6.1).
- `docs/review/security-review-2026-10-21-server.md` (M3, I11) and the Storytel parts of
  `security-review-2026-10-21-music.md` (debug item 8, privacy 2, the tables).
- PLAN.md, "Storytel login to the phone".
- The code the note cites, every line reference opened (list in "What holds").
- storytel-player `server/storytelApi.ts` at 2ca1e2d, fetched raw into the session scratchpad (`qa58/`).

Paths: `S/` = `server/src/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `M/` =
`music/app/src/main/java/me/vibb/music/`. "Note" = the design note, line numbers as in the untracked file. I only read;
nothing was built or run.

## What holds

- **The user's decisions are carried faithfully** (note §0): the password never leaves the server, the phone asks
  through the bridge, the server shares its cached JWT, memory only on the phone, `exp - iat`, old copies go.
- **Every code claim in §1 checks out**, to the line or within a line or two:
  - Server: `device_routes.rs:104-107`; `music_api.rs:225-291` (the event at `:274-281`, `music_api.rs:276`);
    `storytel.rs:58/594-605/806-1077/848-882/865/901-946/969-982/997-1006/1055-1071/1126/1174`;
    `handlers/music.rs:1498/1542`; `music.rs:1353-1355/1370-1378`; `main.rs:126/389-398/705-707/715`;
    `spotify.rs:121-133/1228-1250/1563-1590`; `music_api.rs:307-395`; `handlers/admin.rs:130`.
  - Launcher: `MusicBridgeService.kt:89-93/163-185/223-253` (the 404 -> `onLogin(0, null, null)` at `:236-239`, other
    non-2xx answers nothing at `:248`); `MusicServer.kt:31-32/55-56/84`; `SpotifyTokenCourier.kt`.
  - Music app: `StorytelApi.kt:24-25/41-64/105-123/118-120/125-129/162-215/184-188/193/222-226/241`;
    `StorytelAccount.kt:71/86-103`; `StorytelEndpoints.kt:18-31`; `StorytelLogin.kt:21/29/43-51`;
    `BridgeSync.kt:41/96-109`; `BridgeConfig.kt:91/115`; `Sync.kt:217/303`; `Downloads.kt:151/207-209/213-214`;
    `SonosRenderer.kt:184`; `SonosController.kt:2376/2384`; `logic/Storytel.kt:115-116/125-131/167-171`;
    `logic/Spotify.kt:74`.
  - Tests: `MusicPlanTest.kt:51-52/688` (13 methods), `BridgeTest.kt:40-41`, `EpisodeArtTest.kt:232-233`
    (`takeLast(2) == [wantArt, getSpotifyToken]`), `SourceScanTest.kt:290-295/344-362/620`,
    `StorytelDownloadTest.kt:415-424`.
  - CI: `launcher.yml:74` watches `music/app/src/main/aidl/` and music's `bridge_contract.json`; `music.yml:67` the
    launcher's. A new `IStorytelTokenCallback.aidl` is covered.
- **storytel-player is quoted correctly** (`storytelApi.ts:295` `expiresAt - 60 > now`; `:310-321` and `:355-363`
  `max(300, exp - iat)`, 1800 s fallback, counted from its own `now`; `:226-254` one re-login and one retry on a 401).
- **The AIDL rules hold.** Appending a oneway method is safe both ways:
  - An older launcher's generated `Stub.onTransact` falls through to `Binder.onTransact` for an unknown code and
    returns false. A oneway caller gets no reply, so the music app sees its own timeout. This is `wantArt`'s and
    `getSpotifyToken`'s precedent.
  - A newer launcher with an older music app is never called on the new method.
  - Keeping `getStorytelLogin` and `IStorytelCallback.aidl` for the transaction codes is right.
- **No order fails open.** I checked all eight server/launcher/music combinations, plus a server at step 1 alone:
  - Every one either works or ends with no new Storytel books.
  - None ever puts the password on a phone that didn't already hold it. The only one that keeps a stored password is
    "old server, new launcher, old app", which is today's state.
  - The retired route's empty 404 is exactly what an older launcher turns into "delete yours" (`MusicBridgeService.kt:236-239`).
- **The migration (§2.4) is right:**
  - SQLite evaluates every `SET` against the old row, so the `CASE` keeps a running cooldown on the new generation.
  - `saved_at` is untouched, so the sweep's "due at once after a save" doesn't fire.
  - 0072's triggers fire only on `ciphertext` presence or the switch (`0072_music_storytel.sql:19-24`), so the
    library revision doesn't move.
  - 0085 is the next free number.
- **The status words and HTTP codes don't collide:**
  - The fallback's empty 404 and the route's `{"status":"off"}` 404 mean the same thing to a new launcher.
  - Nothing in the device API answers 409.
  - The device bucket's 429 and `require_device_token`'s 401 carry no word, so they become `launcher`.
- **The lock split (§2.1) is sound.**
  - Nothing takes the session lock while holding `login_lock`, so there is no lock-order inversion.
  - The std mutex never spans an `.await`.
  - A kept JWT no longer waits behind the shelf read.
- **The privacy fence is complete for the JWT itself:**
  - Redacted `toString`s on every holder.
  - No logging in `storytel/`; the launcher logs exception classes only.
  - Retrofit in `createMusicServer` has no logging interceptor (`MusicServer.kt:67-79`).
  - Not in `music_state`, Room or files.
  - The `FAKE-JWT-` guard closes music review debug item 8 for the bridge path too.

## Findings

**58-1 (Medium) - A room's mint gives the ask 4 s. A cold server login doesn't fit, so a room drops the next book
at the end of a book. The note's "no regression" is wrong.**
- **The note's claim is wrong.** The note (note:417-419) says "Today's phone login has the same bound
  (`StorytelApi.kt:193`, inside the same 10 s)". It doesn't.
  - Today the login gets `min(LOGIN_TIMEOUT_MS, deadline - now)`, so up to the whole 10 s. The asset call then takes
    what is left (`StorytelApi.kt:132`, `:193`).
  - §5.1 caps the ask at `deadline - now - timeoutMs` = 4 s (note:360-361).
- **The new path is longer.** Inside those 4 s it now runs:
  - a bind (`Bridge.withBridge`, up to 10 s on its own, `Bridge.kt:88`);
  - the launcher, then tsnet to the Pi;
  - the server's FIFO gate. A feed fetch can hold it 120 s (`docs/notes/server/music.md`, "PWA");
  - the login, with redirects followed;
  - and the way back.
- **A cold login is the common case for a room.**
  - The phone keeps a JWT at most `left - 60 s` (§5.1).
  - A book on a room runs for hours, so at every natural advance the phone's JWT has expired.
  - The server's copy has expired too unless the sweep happened to log in.
  - 26b §5.1 drops an advance whose mint fails ("a step or an advance is dropped").
- **"The kid's second tap" covers a tap, not an advance.** Nobody taps at an advance.

Failure scenario:
- A series plays on the living-room Sonos while the sweep is checking feeds.
- Book 3 ends. The advance's mint asks, and the server's login waits at the gate past 4 s.
- The advance is dropped, and the room stops at the end of book 3.
- Today the phone's own login had up to about 10 s and no gate.

Fix:
- **Prefetch, as Spotify does** (26c §5.5 `prefetchDue`). While a room plays a Storytel entry (the renderer knows),
  the account asks in the background when the kept JWT has less than `PREFETCH_LEFT` (say 10 min) left. A failed
  prefetch is retried every 2 min until expiry.
- **Ask at a start too.** A room's first Storytel play of a process also asks before its mint, outside the actor.
  The actor's mint then finds a JWT in memory, and the 4 s ask is the rare path.
- **Correct note:417-419.**
- Tests:
  - `StorytelApiTest`: prefetch is due at 10 min left while a room plays, and not otherwise.
  - An advance's mint makes no ask when a prefetch kept a JWT.
  - Mutation: drop the prefetch.

**58-2 (Medium) - After an `unreachable` or `busy` ask, Storytel downloads stop until the next sync (6 h at worst).
Spotify's prompt retry was dropped.**
- **A failed ask stops the run.** §5.1 maps `Unreachable(*)` and `Busy` to `LoginFailed` and keeps nothing
  (note:376-378). `LoginFailed` stops every Storytel book of the run (`logic/Storytel.kt:115-116`).
- **Nothing schedules the next run.** `DownloadWorker` re-enqueues only at `nextRetryAt` or
  `storytelRefusedUntil` (`Downloads.kt:173`). The next run is the next sync, and the backstop is
  `PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)` (`Sync.kt:103`).
- **The gate makes this common.** A cold server login that waits at the gate passes the 8 s budget and answers 502
  (§2.2). The login is still kept, so an ask 5-10 s later would succeed.
- **26c built in the retry for exactly this.** 26c §2.6: "The phone's side of this case is §5.5's one prompt retry
  (A second gate lane ... not needed with the retry)". See `TokenCache.PROMPT_RETRY_MS` = 5 s (`TokenCache.kt:200`).
  58 copies the 8 s budget but not the retry.

Failure scenario:
- The parent ticks a new series at 18:00. The sync wants book 1, and the worker's ask meets a sweep.
- The server answers `unreachable` at 8 s, and the run stops with no item error.
- Nothing downloads until the next sync. If no nudge comes, that is the 6 h backstop.

Fix:
- **One prompt retry inside the mint.** An `Unreachable` ask is asked once more `PROMPT_RETRY_MS` (5 s) later when
  that still fits the deadline. A download's 60 s always fits it; a room's doesn't, and 58-1 covers the room.
- **The worker comes back.** After a `LoginFailed("unreachable")` stop, `DownloadWorker` re-enqueues once at about
  2 min. After `busy`, it re-enqueues at `busyUntil`.
- Tests:
  - `StorytelApiTest`: `unreachable` then `ok` 5 s later gives one URL.
  - `StorytelDownloadTest`: the run that stopped on `unreachable` schedules a run.
  - Mutation: drop the retry.

**58-3 (Medium) - A hostile or buggy phone can make the server log in to Storytel 20 times an hour, not 3. So can
every phone together during a Storytel incident.**
- **What the note claims.** §7.3 says "One malicious token therefore costs Storytel at most 3 logins an hour beyond
  the normal one per JWT lifetime" (note:529).
- **Rule 4 is "otherwise one login"** (note:130). That applies to every plain ask when no kept JWT is fresh and no
  cooldown runs.
- **A failed login leaves neither a JWT nor a cooldown.** Only `Refused` stamps the cooldown. A transport error, a
  timeout or `no_jwt` sets `session.jwt = None` and records an error (`storytel.rs:870-880`, `:704`).
- **So while logins fail that way, every ask causes another one.**
  - A hostile phone's 20 asks an hour are 20 logins with the family's password.
  - With N honest phones it is every process start, every room tap and every download run across the fleet.
- **`no_jwt` is the worrying case.** 200 without a JWT is the shape of a captcha, a consent or a "verify your
  account" answer. Repeated password logins then look like credential stuffing to Storytel, and could lock the
  family's account.
- **This is worse than today.** Today each phone's failure stops its own run. Now one server login serves every
  phone's ask, so the fleet's asks all turn into server logins.

Failure scenario:
- Storytel starts answering `login.action` with 200 and no `accountInfo.jwt` (a new verification step).
- Three phones each run a download job every sync, and the kid taps a book on the room a few times.
- The server sends a dozen password logins an hour, with no backoff, until Storytel locks the account.

Fix:
- **A login backoff on the server**, beside the cooldown:
  - After any failed login that isn't a refusal, no phone-caused login for 1 min, doubling to 30 min.
  - It resets on a successful login or a new generation.
  - Inside it, a phone gets 429 `busy` with `retry_after_s` to its end, so the phone waits and doesn't spin.
- **A server-wide cap on phone-caused logins**, say 6 an hour, whatever the number of phones and forces. Past it,
  `busy`.
- **On the phone, `unreachable` holds a room's asks** for 30 s (not a download's, which 58-2 retries once), so a
  kid's taps don't burn the 20-ask budget during an outage.
- **Correct §7.3's bound** to "at most the cap".
- Tests:
  - `a_failed_login_backs_off_for_every_phone`: no second login request inside the backoff, `busy` with its wait.
  - `phone_logins_are_capped_server_wide`.
  - Mutation: drop the backoff.

**58-4 (Medium) - If the JWT's payload holds the e-mail, decision 1 can't be met, and the note finds out only after
release.**
- **Decision 1 covers the e-mail too.** It says "The family's Storytel e-mail and password never leave the server
  again. No route, bridge call or file on a phone carries them" (note:13-14).
- **The note leaves the check to a debug line read on the Pi after the update.** Q2's last bullet (note:697-698)
  admits the phones "would still carry it inside the JWT".
- **The risk is real.** JWTs commonly carry `sub`/`email` claims. Anyone with root on the phone, or the device token,
  base64-decodes the payload.
- **Not verified.** Nobody has looked at a real Storytel JWT's claims (§12, note:707).

Failure scenario:
- Steps 1-3 ship. Every Storytel phone now holds the family's e-mail in the JWT.
- The note's §7.2 "after it" list says the phone gets the JWT only. It is then quietly wrong, and the user's
  decision is half met.

Fix:
- **Make it a check in step 1, not a reading after release.** When `jwt_life` decodes the payload, the server also
  computes one boolean: does any string claim equal the stored e-mail (case-insensitive) or contain `@`.
  - It logs once per login at `info`: `life_s` and `payload_has_email` (the boolean only; never a value or a claim
    name).
  - The PWA's Storytel card can show it under ⓘ.
- **Gate step 2's release on it.** If it is true, the user decides (Q-A):
  - accept it (the e-mail rides inside the JWT, the password never does); or
  - **the server mints the asset URL itself.** The phone gets only the per-book signed CDN URL, and no JWT leaves
    the server. The open risk of that variant: whether a CDN URL minted from the Pi's IP plays from the phone's
    mobile IP.
- **State the possibility in §7.2.**

## Low

**58-5 (Low) - The `session` collides after a server restart, and the phone then refuses a JWT that really is new.**
- **The serial resets.** It is "never reset; it lives in `Account`" (note:115-116), but `Account` is in memory, so a
  restart starts it again.
- **The pair repeats.** After a restart, `"7:3"` can name a different JWT than before. §5.1 step 4 treats "the same
  session back" as `Failed(401)` without a retry (note:367).
- **What it leaks.** The pair says how many saves and logins there were. That is harmless, but it carries no
  identity.

Failure: the Pi restarts. Its third login is `7:3` again. A phone holding the old `7:3` JWT gets a 401, asks with
`stale = 7:3`, and gets a new JWT labelled `7:3`. The phone doesn't retry, and the run stops.

Fix:
- Make the session `"<generation>:<random u64>"`, drawn per login (or the first 16 hex of the JWT's SHA-256).
- The phone compares the **JWT value** for "the same back"; it has it in memory.
- Test: two logins across an `Account` rebuild never share a session.

**58-6 (Low) - "A late answer is kept" contradicts "the ask gets `min(TIMEOUT_MS, ...)`".**
- The two rules are at note:358-361.
- If the 4 s is the ask's own `withTimeoutOrNull` (as in `SpotifyBridgeToken.ask`, `:82`), the callback at 6 s
  completes a deferred nobody keeps.
- Fix (`TokenCache`'s shape):
  - The ask always runs `TIMEOUT_MS` (10 s) in the account's scope, as a shared `Deferred`.
  - Only the **waiter** waits `min(...)`.
  - Say so in §5.1.
  - Test: a 6 s answer to a 4 s waiter is kept for the next mint.

**58-7 (Low) - The sweep's own force has no `used` check, and a failed force drops a JWT that still works.**
- **Double logins.** `fetch_shelf`'s second attempt calls `bearer(force = true)` (`storytel.rs:910`), which logs in
  unconditionally (`:894`). Under the split, a phone's force may already have replaced the JWT. If Storytel revokes
  on login (§12), that can ping-pong with a phone's force.
- **A dropped JWT.** `login`'s error arm sets `jwt = None` (`:872`). A hostile phone's allowed force during a
  Storytel blip therefore drops a still-valid JWT for every phone, and every later ask logs in (58-3).
- Fix:
  - The sweep forces only while the kept serial is the one that got the 401. That is the phone's rule, and the
    server's own Spotify rule ("only when the rejected token is still the kept one").
  - A **forced** login that fails without a refusal keeps the old JWT.
  - Re-check `stale` under `login_lock`, and count a phone's force only when it logs in.
  - Tests:
    - two 401s on one JWT make one login;
    - a failed force leaves the kept JWT.

**58-8 (Low) - One Storytel 5xx or 429 at login now stops every phone for an hour, and the PWA calls it a
refusal.**
- **Any non-200 login is a refusal.** `login_with` treats any non-200 as `Refused`, "a 5xx too"
  (`storytel.rs:684-705`). That is vibb's rule and today's for the sweep.
- **Now it covers every phone.** Every phone's ask goes through this cooldown, where each phone used to keep its
  own.
- **The PWA's wording.** §2.5 shows the phone's refused warning off the server's cooldown. Today's save wording
  says "wrong password" only for 401/403/no JWT (`docs/notes/server/music.md`, "PWA").
- Fix:
  - The card's warning keeps that split: "Storytel refused the login" vs. "Storytel answered with an error".
  - Whether a 5xx or 429 should start the hour at all is the user's call under "follows vibb" (Q-B). With 58-3's
    backoff it could be an outage instead.

**58-9 (Low) - The `key` check isn't counted, but each ask writes a database row and a warning.**
- `credentials()` on an unusable key logs a `warn` and records `login_error = storytel_key` on every call
  (`storytel.rs:628-637`).
- §2.2 puts `key` before the limit and doesn't count it (note:155-156). That isn't Spotify's order: Spotify's key
  error comes from inside `access_token`, after `phone_ask`.
- With no music key at all, `credentials()` answers `NotConfigured`, not `KeyUnusable` (`:628-629`). So the table's
  "no music key -> `key`" needs the route's own check, and the note doesn't say how.
- Fix:
  - The route checks the key by fingerprint only (no open, no record).
  - `phone_jwt` records `storytel_key` at most once per generation.
  - Spell out the `NotConfigured` vs. `KeyUnusable` mapping.

**58-10 (Low) - The parent can't see why Storytel stalls.**
- **An old launcher.** With an old launcher, the report says `ok`, so `storytelWaiting` returns the shared rule:
  no `login` wait at all. The note's "waiting stays" (note:477) is wrong. Device › Music says "Ready" while nothing
  downloads.
- **A running cooldown.** With the server's cooldown running, the row says "Ready" right under the refused warning.
- **An old server.** `off` from an old server flips the report between `none` and `ok` every 10 min.
- Fix:
  - The "Storytel login" row reads the server's cooldown too ("Refused").
  - With Storytel on and the launcher's `app_version_code` below the courier's release, show "Update Vibb One"
    (the server has the version).
  - Correct row 3 of §6.

**58-11 (Low) - §2.6's `STORYTEL_BASE` needs Spotify's `Reach` rule and a fake-login guard.**
- **`Reach`.** Every Storytel request is `Reach::Public` (`storytel.rs:693-697`), which refuses loopback. Spotify
  switches to `Reach::Any` only while a loopback override is set (`docs/notes/server/music.md`, Spotify).
- **A leftover override.** A leftover `STORYTEL_BASE` on the production Pi sends the **real** stored login, `pwd=`
  included, over plain HTTP to whatever listens on that loopback port. This is the server twin of music review debug
  item 8.
- **The chain can't test the revoke path quickly.** `FORCE_MIN_AGE` makes §6i B's revoke path wait 5 min: a fresh
  JWT that 401s is answered "the same", and the run stops.
- Fix:
  - `Reach::Any` only with the override.
  - With the override set, a login is sent only for an e-mail ending `.example.invalid` (else `NotConfigured`,
    logged).
  - Say that §6i B's revoke check needs either the wait or a loopback-only shorter `FORCE_MIN_AGE`.

**58-12 (Low) - Fixture and fake details are wrong or collide.**
- **`password_vectors`.** It doesn't read `aes_vectors.json`; its vectors are inline (`storytel.rs:1126-1145`).
  Note:626 is wrong.
- **A name collision.** `server/testdata/storytel/login_ok.json` already exists, in another shape (a bare body vs.
  music's `{status, body}`). The move (note:624-627) would collide.
- **`--refuse`.** It now answers 409 on `/fake/jwt` (note:617). The chain (§2.6) still needs the fake's
  `login.action` to answer 403. Keep both: `--refuse` refuses the login, and `/fake/jwt` then reports `refused`.
- **`--revoke-after`.** It is defined as "the K-th asset call ... answers 401", but §9's check expects the 401 on
  the second book with `--revoke-after 1` (note:619, :640). Pick one meaning.
- **The chain's revoke.** For §6i B, the fake's **login** must also issue `FAKE-JWT-<n+1>` after a revoke, so the
  server's force gets a new one.

**58-13 (Low) - The route's answers have no shared contract file between the server and the launcher.**
- **Where the fixture lives.** `jwt_answers.json` lives in the music app only (note:613-614), for its debug parser
  and the fake.
- **What it misses.** The route's server tests and the launcher's `StorytelTokenCourierTest` each spell the shape
  on their own. This is the API between server and launcher that the standing rule wants pinned "with the contract
  files". Spotify's route had none either, but this one is new.
- Fix:
  - Put `server/testdata/storytel_token_answers.json` byte-identical in the launcher's and the music app's test
    resources.
  - The server's route test produces each row and compares the keys; the launcher's courier and the music app's
    debug parser read each; the fake answers from it.
  - Add it to `launcher.yml`, `server-ci.yml` and `music.yml`'s path lists and to the root `CLAUDE.md`.

**58-14 (Low) - The device checks can't see an IP-bound JWT.**
- The JWT is now issued to the Pi's public IP and used from the phone's.
- At home both share the NAT address, so §9's Jelly Star checks would pass even if Storytel bound the JWT (or the
  asset call) to the issuing IP.
- Fix: add to §9:
  - a download **over mobile data**, with the JWT from the server;
  - a book on a room while the parent's own Storytel app is in use (a login elsewhere revoking ours, §12).

**58-15 (Low) - The purge runs inside `StorytelAccount.get`.**
- **Where it runs.** `build` runs under `synchronized(this)` (`StorytelAccount.kt:57-65`). The first `get` of a
  process can be the Sonos actor's minter (`SonosRenderer.kt:184`).
- **Why that matters.** `AndroidKeyStore` `load`/`containsAlias`/`deleteEntry` are keystore binder calls. They would
  run inside a room's 10 s mint.
- **The debug rebuild.** A debug base change rebuilds the account, so "once per process" needs its own flag.
- Fix: `StorytelLegacy.purge` runs from `Sync.writeReport` (and `PackageReplacedReceiver`'s sync) behind an
  `AtomicBoolean`, never on the mint path.

**58-16 (Low) - `exp - iat` overstates the life left when `iat` is old, and the 300 s floor meets the 5 min force
rule.**
- **An old `iat`.** If `login.action` hands back a still-valid earlier token (an old `iat`), `exp - iat` counted
  from now outlives the token.
- **The floor and the force rule.** A token living under 300 s is reused past its end, and the 5 min `FORCE_MIN_AGE`
  then refuses the force. Every phone fails until it ages out.
- Fix:
  - When the Pi's wall clock is sane (year at least 2026), life = `min(exp - iat, exp - wall_now)`, floored at 60 s.
    Otherwise `exp - iat`.
  - Log `left_at_login_s` beside `life_s` (Q2).

**58-17 (Low) - §7.2 misstates what a thief gets and gives the parent no remedy.**
- **The same JWT, not new ones.** "New JWTs at up to 20 asks an hour" (note:511) is really the same shared JWT the
  family's phones use, for as long as the phone is enrolled.
- **No remedy.** Removing the phone stops new ones but doesn't rotate the JWT, and the note offers the parent
  nothing.
- Fix:
  - Say it as it is.
  - Name the remedy in §7.2 and the PWA's ⓘ: change the Storytel password at Storytel and save it here. Whether that
    revokes the old JWT is §12's open check.

## Nits

- **The AIDL comment.** §3's reworded `getStorytelLogin` comment (note:259-262) describes step 3's launcher, but it
  lands in step 1, when the launcher still asks the server. Word it "retired from design 58 step 3".
- **`StorytelAccount.mint`'s body changes.** `runInterruptible { api.assetUrl(..) }` can't wrap a suspend function.
  Its signature stays.
- **Retrofit has no converter** (`MusicServer.kt:75-78`). `storytelToken(stale)` must take a hand-built
  `RequestBody` (`application/json`). The server reads `Bytes`, not `Json`, so a wrong content type is never a 415.
- **Padding.** `base64` 0.22 "with or without padding" needs `DecodePaddingMode::Indifferent`. `URL_SAFE_NO_PAD`
  refuses padding.
- **The check order.** "Order of the checks (Spotify's)" isn't Spotify's: see 58-9.
- **The 10 min `offUntil`.** It is keyed to a generation that a quick switch off and on doesn't move, so a phone can
  stay off up to 10 min after the switch is back on. Clear `offUntil` when the policy's generation reads 0 and then
  non-zero, or shorten it to 2 min.
- **The migration doesn't nudge.** No SSE is sent at startup, so "an older music app pulls at once" (note:213) means
  "at the launcher's next policy fetch".
- **The server version.** Step 1 sets 0.34.0 (note:667). Say no `server-v*` tag goes out between steps 1 and 3, or
  step 3 bumps again.
- **Pin the life against the launcher's bound.** `JWT_LIFE_CAP - MARGIN` must stay within the launcher's
  `expires_in_s` 1..86 400. A server test that pins it saves a later cap change from turning every answer into
  `launcher`.
- **A leaked scope.** The account's ask scope leaks on a debug rebuild. Cancel the old one.

## Tests - add to §8

Covered above, collected:
- **58-1:**
  - the prefetch is due at 10 min left while a room plays;
  - an advance makes no ask after a prefetch.
- **58-2:**
  - `unreachable` then `ok` 5 s later is one URL;
  - a stopped run schedules the next.
- **58-3:**
  - the server-wide login backoff;
  - the phone-login cap;
  - `busy` with its wait inside it.
- **58-4:** `payload_has_email` from a run-time `FAKE-JWT-HEAD.<b64>.FAKE-SIG` with and without an `@` claim (the
  log captured; no value in it).
- **58-5:** sessions are unique across an `Account` rebuild; the phone compares the JWT value.
- **58-6:** a late answer to a short waiter is kept.
- **58-7:**
  - two 401s on one JWT, one login (sweep and phone);
  - a failed force keeps the kept JWT.
- **58-13:** the shared answers file, read by the server route test, the courier test, the debug parser and the
  fake's selftest.

Mutation targets to add:
- the prefetch;
- the prompt retry;
- the login backoff;
- the force's serial check;
- the 5 min age rule;
- `PHONE_MIN_LEFT`;
- the `FAKE-JWT-` filter on the bridge path;
- the purge's `AtomicBoolean`.

## Device checks - add to §9

- **58-14:**
  - a book downloads over mobile data (not Wi-Fi) with the server's JWT;
  - a room play while the parent's Storytel app is open and logs in.
- **58-1:**
  - a series on a room with the phone's JWT expired at the book's end (a short `--jwt-ttl` on the emulator first);
  - the advance plays.
- **58-4:** the step-1 log line's `life_s`, `left_at_login_s` and `payload_has_email`, before step 2 ships.

## Questions for the user

- **Q-A (58-4).** If Storytel's JWT turns out to carry the family's e-mail, every phone holds it (never the
  password). Accept that, or should the server mint each book's audio link itself, so no Storytel session leaves the
  server? The latter is unverified from a phone on mobile data.
  - Recommend: decide after step 1's line.
  - Until then, keep the courier as designed.
- **Q-B (58-8).** A Storytel 5xx or 429 at login counts as a refusal today (vibb's rule): no Storytel on any phone
  for an hour. Keep vibb's rule, or treat those as an outage with the short backoff of 58-3?
  - Recommend: an outage. That is a deviation from vibb, and it is yours to make.
- **The note's open questions:**
  - Q1 (move the generation): agree, yes.
  - Q2: yes, extended as 58-4 and 58-16.
  - Q3 (the emulator chain): yes, with 58-11's guard.

## Verdict

**Approve with changes (bounded).** There are no Highs:
- the user's decisions are carried;
- no update order fails open;
- the AIDL and the status words are safe for older apps on both sides;
- the migration is right.

The four Mediums change behaviour the note promises, and each has a contained fix:
- 58-1: a room's advance after a cold login;
- 58-2: downloads after a slow ask;
- 58-3: the login bound;
- 58-4: the e-mail inside the JWT.

Before step 1:
1. Revise §2.1-§2.3, §5.1, §5.3, §7.2-§7.3 and §8 for 58-1 to 58-3, and the Lows.
2. Add 58-4's check to step 1, as the gate before step 2 ships.
3. Get answers to Q-A and Q-B.
