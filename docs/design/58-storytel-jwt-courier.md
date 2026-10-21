# 58 - Storytel's session from the server: the family's password never leaves it

Architect draft (2026-10-21). The user approved server review M3's fix the same day
(`docs/review/security-review-2026-10-21-server.md` M3; PLAN.md, Fix backlog, "Storytel login to the phone"). It builds
on design 26b (`26b-storytel.md`: the server's Storytel client §2.1, the phone's §4.1-§4.2, the room's mint §5.1) and on
26c's Spotify token courier (`26c-spotify.md` §2.6, §5.5, §6.1, §8), which this note copies wherever it can. The change
touches the server, the launcher and the music app, and the bridge contract in both apps. `S/` = `server/src/`, `L/` =
`launcher/app/src/main/java/com/kidslauncher/mdm/`, `M/` = `music/app/src/main/java/me/vibb/music/`. Line numbers are
`main` at 7b5a9a4b.

## 0. The user's decision (2026-10-21)

1. **The family's Storytel e-mail and password never leave the server again.** No route, bridge call or file on a
   phone carries them.
2. **The phone asks for a Storytel JWT when it needs one.** The JWT travels from the server to the launcher over the
   device bearer, then through a new bridge method shaped like `getSpotifyToken` to the music app.
3. **The server answers with its own cached JWT** (`storytel::Account`), shared by all phones and its own sweep, plus
   how long the JWT still lives.
4. **The music app keeps the JWT in memory only**, until it expires.
5. **The JWT's lifetime is its own `exp - iat`** (storytel-player's rule), not a fixed hour.
6. **Old copies go.** The music app deletes `storytel.bin` and its Keystore key on its first start. An older music app
   on a newer server fails safe: no Storytel, never the login.

## 1. Facts (checked)

**Today's path:**

- `GET /api/devices/music/storytel` (`S/device_routes.rs:104-107`, `S/handlers/music_api.rs:225-291`) opens the
  sealed row and answers `{generation, email, password}` with `no-store`. It writes a `storytel_login_sent` security
  event on every fetch (`:274-281`; server review I11 objects to that).
- The launcher fetches the login only when the music app's generation differs from the policy's (`!=`). It answers
  through `IStorytelCallback` (`L/music/MusicBridgeService.kt:89-93`, `:223-253`):
  - **a 404 becomes `onLogin(0, null, null)`, "delete yours"** (`:236-239`);
  - any other non-2xx answers nothing (`:248`).
- The music app pulls the login in every sync (`M/bridge/BridgeSync.kt:41`, `:96-109`). It seals the login under
  Keystore alias `vibb_storytel` into `noBackupFilesDir/storytel.bin`, written through `storytel.bin.tmp`
  (`M/bridge/StorytelLogin.kt:21`, `:29`, `:43-51`). Generation 0 deletes the file.

**The phone's Storytel client:**

- `StorytelApi` logs in itself: AES `pwd=`, `login.action`, a 1 h `JWT_TTL_MS`, one forced re-login after a 401
  (`M/storytel/StorytelApi.kt:105-123`, `:162-215`, `:241`).
- The `used` rule: a forced call that finds another JWT already takes that one (`:184-188`).
- A fresh JWT that is refused too is dropped (`:118-120`).
- The refused-login cooldown lives in `noBackupFilesDir/storytel_refused.json` (`M/storytel/StorytelAccount.kt:71`,
  `:86-103`; `StorytelApi.kt:222-226`).
- `state()` answers `none`/`refused`/`ok` (`:125-129`). The report reads it (`M/sync/Sync.kt:303`), and so do the
  storage floor (`Sync.kt:217`, `M/sync/Downloads.kt:151`) and `storytelWaiting` (`M/logic/Storytel.kt:167-171`).

**The phone calls Storytel in exactly two places,** both through `StorytelAccount.mint`:

- **a download** (`M/sync/Downloads.kt:207-209`): 15 s for the asset call, 60 s in all (`:213-214`);
- **a Sonos play** (`M/sonos/SonosRenderer.kt:184`): 6 s and 10 s (`M/sonos/SonosController.kt:2376`, `:2384`).

The brief mentions a stream and a bookmark sync, but neither exists:

- The phone plays a book only from its downloaded copy. `ItemTag.copyOnly` never makes a request (`music/CLAUDE.md`,
  "Storytel is download-only").
- Position mirroring (outbox, bookmark POST) was not ported: user decision of 2026-10-09 (26b §0 "Out", §9;
  `StorytelApi.kt:24-25`).

This design therefore serves the two mints. Any later caller of a JWT gets it from the same place (§5.1).

**The server:**

- `storytel::Account` (`S/storytel.rs:806-1077`) keeps the JWT inside `Session`, under one tokio `Mutex`, for
  `JWT_TTL` = 1 h (`:58`, `:865`, `:1062`).
- The shelf read holds that lock across `fetch_shelf` (`:969-982`, `:997-1006`), and so across the server-wide fetch
  gate. A feed fetch can hold that gate for 120 s (`docs/notes/server/music.md`, "PWA").
- **So a phone asking for the JWT during a sweep would wait behind the shelf read.** §2.1 takes the JWT out of that
  lock.
- The refused-login cooldown is stamped per generation in the database (`:594-605`, `:848-882`). A save or a clear
  moves the generation (`S/handlers/music.rs:1498`, `:1542`).
- The policy's `storytel_generation` is the row's generation while this phone's switch is on and a login is stored,
  else 0 (`S/music.rs:1353-1355`, `:1370-1378`).
- Unknown paths answer an empty 404 on both listeners (`S/main.rs:705-707`, `:715`).

**The Spotify courier to copy:**

- the route `spotify_token` (`S/handlers/music_api.rs:307-395`): checks in order, an 8 s budget, a spawned refresh
  that is kept (`S/spotify.rs:1563-1590`), a per-phone rolling limit (`S/spotify.rs:121-133`, `:1228-1250`);
- the launcher's `answerSpotify` (`L/music/MusicBridgeService.kt:163-185`) and `SpotifyTokenCourier.kt`;
- the music app's `SpotifyBridgeToken.kt` and `TokenCache`.

**storytel-player (debba/storytel-player 2ca1e2d, 2026-10-18, `server/storytelApi.ts`):**

- After a password login it takes `expiresIn = max(300, exp - iat)` from the JWT's payload, and 1800 s when the claims
  are missing or unreadable (`:310-321`, again `:355-363`).
- It reuses the JWT until 60 s before that (`:295`).
- On a 401 it drops the cached JWT, logs in again once and retries once (`:226-254`).
- The 1800 s fallback is *shorter* than our fixed hour.

**Tests that pin the bridge's method list:**

- `MusicPlanTest.kt:51-52` (the list) and `:688` (13 methods check their caller);
- `music/.../bridge/BridgeTest.kt:40-41`;
- `music/.../logic/EpisodeArtTest.kt:232-233`, which asserts `takeLast(2) == [wantArt, getSpotifyToken]`. Appending
  breaks it, so it must change too.

## 2. Server

### 2.1 The JWT (`S/storytel.rs`)

- **The lifetime:** `jwt_life(jwt)`.
  - Decode the JWT's second dot-separated part as base64url, with or without padding (`base64` 0.22 is in the tree).
  - Read `exp` and `iat` as JSON numbers. The life is `exp - iat`, at least `JWT_LIFE_FLOOR` (300 s) and at most
    `JWT_LIFE_CAP` (24 h, ours: a sanity bound on reuse).
  - With no dots, bad base64, bad JSON, or a missing or non-numeric claim, the life is `JWT_LIFE_FALLBACK` (30 min).
  - The life counts from the login's own monotonic instant, never from `iat` (clock skew, as storytel-player does).
  - The payload is never logged, stored or kept. It is parsed and dropped.
  - `JWT_TTL` goes. The probe's `keep` (`:1055-1071`) and `login` (`:865`) both use `jwt_life`.
- **The JWT leaves the session's lock:**
  - `Account` gets `jwt: std::sync::Mutex<Option<KeptJwt>>`. It is never held across an `.await`.
  - `Account` gets `login_lock: tokio::sync::Mutex<()>`, so one login runs at a time server-wide.
  - `KeptJwt` gains `made: Instant`, `life: Duration` and `serial: u64`. The serial counts the account's logins and is
    never reset; it lives in `Account`, outside `forget`.
  - `bearer` reads the kept JWT under the std lock first. A JWT that is fresh, of this generation and has more than
    `MARGIN` (60 s) left is cloned out. `Jwt` gains `Clone`, still with no `Debug` or `Display`.
  - Only a login takes `login_lock`. Under it, the code checks again before logging in.
  - `Session` keeps only the shelf cache and `sent`. The shelf read still holds the session lock, but no phone needs it.
  - `keep` never waits (QA L1 of 26b still holds). `forget` clears both.
- **The phone's JWT:** `Account::phone_jwt(&self, state, device, stale: Option<&str>) -> Result<PhoneJwt,
  StorytelError>`.
  - `PhoneJwt` is `{jwt, left, session}`, with no `Debug` and no `Display`; `session` is `"<generation>:<serial>"`.
  - Rules in order:
    1. `credentials(state)` gives the generation and the cooldown. Its errors pass through.
    2. **The kept JWT is answered** when it is of this generation and has at least `PHONE_MIN_LEFT` (120 s) left,
       unless §2.3's force applies.
    3. Inside the cooldown it is `Cooldown { until }`, with no request.
    4. Otherwise one login (§2.2 runs it as a task). A fresh login is answered whatever its life.
  - A phone needs the JWT only for the asset call. The CDN URL it mints carries its own `token=`, so 2 minutes left is
    enough.
- **Logins:** one per JWT lifetime, only in periods when a phone or the sweep uses Storytel. Forces add at most §2.3's.

### 2.2 `POST /api/devices/music/storytel/token`

The route sits behind the device bearer (`require_device_token`, `S/device_routes.rs` beside `spotify/token`), with
`DefaultBodyLimit::max(256)`.

- **The body:** `{}`, or `{"stale": "<g>:<n>"}` (§2.3). It is read leniently: an empty body, or a `stale` that isn't
  `[0-9]{1,18}:[0-9]{1,18}`, is a plain ask.
- **The answer:** always JSON with its `status` and `Cache-Control: no-store`. The JWT appears only with `ok`.

| Status | Body | When |
|---|---|---|
| 200 | `{"status": "ok", "jwt": "FAKE-JWT-1", "expires_in_s": 3540, "session": "7:3"}` | `expires_in_s` = what is left minus `MARGIN` (60 s), so at least 60 |
| 404 | `{"status": "off"}` | this phone's `music_storytel` switch is off, or no login is stored |
| 409 | `{"status": "refused", "retry_after_s": 2400}` | Storytel refused the login now (3600), or the cooldown runs (until its end, at least 1) |
| 429 | `{"status": "busy", "retry_after_s": 30}` | this phone's asks are over the limit (§7.3) |
| 502 | `{"status": "unreachable"}` | the login's transport failed, 200 came without a JWT (`no_jwt`), or the budget ran out |
| 503 | `{"status": "key"}` | no music key on this server, or the login was sealed with another key (`storytel_key`) |
| 500 | `{"status": "unreachable"}` | a database error |

- **Order of the checks** (Spotify's, `music_api.rs:321-356`):
  1. the switch, then whether a login is stored (both `off`, not counted);
  2. the key (`key`, not counted);
  3. the per-phone limit;
  4. `phone_jwt`.
- **The budget:** `PHONE_JWT_BUDGET` = 8 s, under the launcher's 10 s.
  - `phone_jwt` runs as a `tokio::spawn`ed task (`AppState.storytel` is an `Arc`, `S/main.rs:126`).
  - A login still running when the route gives up finishes and is kept, so the next ask gets it at once (Spotify's
    `MintJob` rule).
- **No security event per ask** (I11). One `tracing::debug!` per answer (device id and status). An `info` when a phone
  causes a login.
- **What is never logged:** the JWT, its payload, the stale session and the body. The answer type's `Debug` is
  hand-written or absent: a probe test like `secret_types_have_no_debug` (`S/storytel.rs:1174`) covers `PhoneJwt` and
  `KeptJwt`.

### 2.3 A phone's 401: the force

The phone got a 401 from the asset call and asks with `stale` = the session it used.

- **When the server logs in again:** only if all three hold:
  - the kept JWT's session equals `stale`;
  - it is at least `FORCE_MIN_AGE` (5 min) old;
  - this phone has forced fewer than `PHONE_FORCES_PER_WINDOW` (3) times in the rolling hour.
- **Otherwise the server answers what it holds:**
  - A newer JWT is a concurrent re-login: the phone's `used` rule, taken without a login.
  - The same JWT, when it is too young or this phone's forces are spent: the phone's own rule ends the run (§5.1).
- **The cooldown still decides first:** a force inside it is `refused`, with no request.
- **The server's own sweep** keeps its own 401 rule: one re-login and one retry (`S/storytel.rs:901-946`). That login
  moves the serial, so phones get the new JWT at their next ask.

### 2.4 Retiring `GET /api/devices/music/storytel`, and moving the generation once

- **The route goes in step 3:**
  - the handler and its `StorytelLogin` struct (`S/handlers/music_api.rs:224-291`);
  - the route (`S/device_routes.rs:104-107`);
  - the `storytel_login_sent` event.
- **What an older launcher then gets:** the empty 404 of the fallback (`S/main.rs:705-707`). It turns that into "delete
  yours" (§1).
  - **That empty 404 is the retirement answer, for good.** A test pins it, and the path must never be reused.
  - So the route is not kept for some period. There is nothing to keep it for: every launcher that calls it is told to
    delete.
  - The admin label `storytel_login_sent` (`S/handlers/admin.rs:130`) stays, so old rows still read (events are kept
    180 days).
- **Migration `0085_storytel_jwt_courier.sql`** (the next free number when it is written; 0084 is the last on main):

  ```sql
  -- Design 58: the phones get the server's Storytel JWT, never the login. The login's generation moves once, so an
  -- older music app sees a new one and asks its launcher, which answers "none" (an older launcher through the retired
  -- route's empty 404, a newer one at once): the app deletes its sealed copy. A cooldown running now keeps running.
  UPDATE music_storytel
  SET login_refused_generation = CASE WHEN login_refused_generation = generation THEN generation + 1
                                      ELSE login_refused_generation END,
      generation = generation + 1
  WHERE id = 1 AND ciphertext IS NOT NULL;
  ```

  - **How it reads:** SQLite evaluates every `SET` against the old row. `saved_at` is untouched, so the sweep's "due at
    once after a save" rule doesn't fire.
  - **The policy:** `storytel_generation` moves for every phone with Storytel on. Its managed config changes, the
    launcher nudges (`BRIDGE_CHANGED`), and an older music app pulls at once.
  - **The cost:** an older music app loses new Storytel downloads until it is updated. Books on the phone still play.
    This is §0.6's "fail safe", and it is open question 1.
  - Frozen like every applied migration.

### 2.5 The PWA and the texts

- **The Storytel footer** (`server/templates/music_settings.html:97`), "Use a password you use only for Storytel: each
  phone keeps it to log in.", becomes: "The phones never get the password: they ask this server for a short Storytel
  session when they download a book." The Family sentence stays.
- **The phone's Music page:**
  - The "Storytel login" row's "Logged in" becomes "Ready" (`S/handlers/music.rs:4372-4380`): the phone no longer
    logs in.
  - The refused warning (`:4145`, `templates/device_music.html:137`) shows while the phone reports `refused` **or**
    the server's own cooldown runs for the current generation and this phone's switch is on
    (`storytel::cooldown_until`, `S/storytel.rs:594`, "the card reads the row itself").
  - The phone's word is now in memory only (§5.2), and a fresh process reports `ok` until its first ask. The server's
    cooldown is the authority.
- **DEPLOY.md:517:** "the phones get 503" becomes "a phone's Storytel token ask answers `key`".
- **The module doc** (`S/storytel.rs:1-4`) and the notes (`docs/notes/server/music.md`, Storytel's first bullet and
  "Secrets") say: the phones get the server's JWT and mint the audio URLs themselves.

### 2.6 A Storytel base for the emulator chain (open question 3)

Spotify's `Config::from_env` (`S/main.rs:389-398`) lets §6l A1 run the whole chain: the PWA, the server, the launcher
and the managed music app. Storytel has only constants today (`S/storytel.rs:47-53`), so the courier's real path,
bridge -> launcher -> tsnet -> server, would be checked only on the Jelly Star.

- **Proposal:** `STORYTEL_BASE` in `.env`.
  - It is taken **only for a loopback URL** and logged as a warning at every start.
  - It points the login and the bookshelf at `scripts/fake-storytel.py`.
- **The fake grows:**
  - `POST /libraries/bookshelf`, answered from `server/testdata/storytel/bookshelf.json`;
  - its login fixtures move to `server/testdata/storytel/` (§8).
- **The phone's own asset calls** on the emulator still go through the music app's debug switch, so the chain runs
  with both.

## 3. The bridge contract (one commit in both apps)

The AIDL stays byte-identical in `launcher/` and `music/`, with methods appended and never reordered
(`IMusicBridge.aidl:1-4`).

1. **`IMusicBridge.aidl`:**
   - Add the import `import me.vibb.bridge.IStorytelTokenCallback;` after `IStorytelCallback`'s.
   - Reword `getStorytelLogin`'s doc comment. The method itself stays: its transaction code is fixed by its place.

     ```aidl
     /** Retired (design 58): the launcher answers generation 0 ("delete yours") whenever [haveGeneration] isn't the
      *  policy's, and never asks the server; the music app no longer calls it. Kept for the transaction codes. */
     oneway void getStorytelLogin(long haveGeneration, IStorytelCallback callback);
     ```

   - Append at the end:

     ```aidl
     /** A Storytel session (JWT) of the family's account (design 58 §2.2, §4), through [callback]. [stale] is the
      *  session ("<generation>:<serial>") Storytel answered 401 to, else null. The launcher never caches or logs it. */
     oneway void getStorytelToken(String stale, IStorytelTokenCallback callback);
     ```

2. **New `IStorytelTokenCallback.aidl`:**

   ```aidl
   // The Storytel session, answered by the launcher (design 58 §4). Byte-identical in launcher/ and music/.
   package me.vibb.bridge;

   interface IStorytelTokenCallback {
       /** [status]: one of bridge_contract.json's storytel_token.statuses; [jwt] only with "ok", else null;
        *  [expiresInMs] its use left from now with "ok", the wait before asking again with "refused" and "busy" (the
        *  server's retry_after_s), else 0; [session] "<generation>:<serial>" with "ok", else null. */
       oneway void onToken(String status, String jwt, long expiresInMs, String session);
   }
   ```

3. **`bridge_contract.json`** (both apps' test resources) gains:
   `"storytel_token": {"statuses": ["ok", "off", "refused", "busy", "unreachable", "key", "launcher"], "timeout_ms":
   10000}`.
4. **The tests that pin it:**
   - `MusicPlanTest`: the four AIDL files byte-identical, 14 methods with `getStorytelToken` last, and
     `storytel_token` checked against `STORYTEL_TOKEN_STATUSES` and `STORYTEL_TOKEN_TIMEOUT_MS`.
   - `every bridge method checks its caller first` counts 14 (`:688`).
   - `BridgeTest`: the 14-method list and the music app's `StorytelBridgeToken` constants against the contract.
   - `EpisodeArtTest.kt:232-233`: the last three are `wantArt`, `getSpotifyToken`, `getStorytelToken`.
5. **CI:** both workflows already watch both `aidl/` directories and both `bridge_contract.json` copies
   (`.github/workflows/launcher.yml:74`, `music.yml:67`). Nothing is added for the contract itself.
6. **Unchanged:**
   - the policy (`policy_json_keys_snapshot`, `PolicyResponseCompatTest`);
   - `music_state.json` (`storytel` stays `none`/`ok`/`refused`);
   - the restrictions (`storytel_generation` stays);
   - the library and listing snapshots.

## 4. The launcher: the courier

- **`L/music/StorytelTokenCourier.kt`** (new, pure; `SpotifyTokenCourier.kt` copied):
  - Constants: `STORYTEL_TOKEN_TIMEOUT_MS` = 10 000, `STORYTEL_TOKEN_STATUSES`, and `STORYTEL_WAIT_DEFAULT_MS` =
    30 000 for a `busy` without a number. A `refused` without a number waits 3 600 000.
  - `data class StorytelTokenAnswer(status, jwt, expiresInMs, session)`. Its `toString` prints `jwt=<redacted>`.
    `LAUNCHER` is the answer when the launcher gets none.
  - `storytelTokenAnswer(httpStatus, body)`:
    - `ok` needs HTTP 200, a `jwt` matching `[\x21-\x7e]{1,8192}`, `expires_in_s` in 1..86 400 and a `session`
      matching `[0-9]{1,18}:[0-9]{1,18}`. An `ok` missing any of them is `launcher`.
    - `refused` and `busy` carry `retry_after_s` in 1..3600 as ms.
    - Any other word passes through as is.
    - With no word: a 404 is `off` (a server without the route), a 5xx is `unreachable`, anything else `launcher`.
  - `staleBody(stale)`: `{"stale": ...}` only for a valid session, else `{}`.
- **`MusicServer.storytelToken(stale)`:** `POST api/devices/music/storytel/token` with that JSON body
  (`L/music/MusicServer.kt`). `storytel()` and its `@GET` go (`:31-32`, `:55-56`, `:84`), because nothing calls the old
  route any more.
- **`MusicBridgeService.getStorytelToken(stale, callback)`:**
  - `check()` comes first, like every method.
  - `answerStorytelToken` is `answerSpotify` (`:163-185`) with the new parser.
  - **No cache, no store, no log** of the body, the JWT or the session. Log lines name the exception class only.
- **`getStorytelLogin` (step 3):** `answerStorytel` keeps its `want == have` return. Otherwise it calls
  `callback.onLogin(0, null, null)` and never contacts the server.
- **The docs:** the class doc (`MusicBridgeService.kt:37-38`) and `launcher/CLAUDE.md:441`, `:447`, beside the
  Spotify courier bullet (`:449`).

## 5. The music app

### 5.1 The JWT source and the asset call (`M/storytel/StorytelApi.kt`)

- **What goes:**
  - `encryptPassword`, `AES_KEY`/`AES_IV`, `logIn`, `cooldownUntil`, `CooldownStore`;
  - `JWT_TTL_MS`, `LOGIN_COOLDOWN_MS`, `LOGIN_TIMEOUT_MS`;
  - `Endpoints.login` (`StorytelEndpoints.kt:18-31`).
- **What stays:**
  - the asset call (`assetUrl`'s request, `classify`, `NO_AUDIO_STATUSES`, `mintedUrl`, `CID`);
  - the `Mint` classes (`StorytelApi.kt:41-64`). `Refused(untilWallMs)` and `LoginFailed(reason)` keep their meaning:
    "no session now".
- **The constructor:**
  `StorytelApi(http, generation: () -> Long, ask: suspend (stale: String?, timeoutMs: Long) -> JwtResult, elapsedMs,
  wallMs, endpoints)`.
- **`assetUrl` becomes `suspend`:**
  - Only the HTTP send runs in `runInterruptible(Dispatchers.IO)`, so a cancelled download job still ends a blocked
    request at once.
  - `StorytelAccount.mint`'s signature is unchanged, so `Downloads.kt:207-209` and `SonosRenderer.kt:184` don't
    change.
- **The bearer:**
  - `Jwt(value, expiresAtMs, generation, session)`, `toString` redacted.
  - Kept while `generation()` equals its generation and `elapsedMs() < expiresAtMs`. The server already took its
    margin off.
  - Generation 0 is `NotConfigured`, with no ask, and drops the kept JWT.
- **One ask at a time:**
  - A kotlinx `Mutex` replaces the `ReentrantLock`, and its wait counts against the deadline (`withTimeoutOrNull`).
    After taking it, the code checks again.
  - The ask itself runs in the account's own scope, so **a late answer is kept**: a waiter that gave up doesn't lose
    the JWT (`TokenCache`'s rule, 26c §5.5).
  - The ask gets `min(StorytelBridgeToken.TIMEOUT_MS, deadline - now - timeoutMs)`. That reserves the asset call's own
    time: a room's `(6 s, +10 s)` leaves the ask 4 s, a download's `(15 s, +60 s)` 10 s. Below 1 s it is
    `LoginFailed("deadline")`, with no ask.
- **The 401 (Storytel's rule, kept):**
  1. The asset call answers 401.
  2. The phone asks once with `stale` = the JWT's session.
  3. A **different** session back is retried once.
  4. **The same session back** (the server judged it too young, §2.3) is `Failed(401)` at once. No second request.
  5. A second 401 on the new JWT drops it (`:118-120`). `mintStopsRun` stops the run as today (`M/logic/Storytel.kt:115-116`).
- **What the ask's answers become:**

| `JwtResult` | `Mint` | Kept in memory |
|---|---|---|
| `Ok` | the asset call | the JWT |
| `Off` | `NotConfigured` | `offUntil` = 10 min, for this generation |
| `Refused(wait)` | `Refused(wall + wait)` | `refusedUntil` = now + wait, for this generation |
| `Busy(wait)` | `LoginFailed("busy")` | `busyUntil` = now + wait |
| `Unreachable("key")` | `LoginFailed("key")` | `keyUntil` = 10 min |
| `Unreachable(other)` (`unreachable`, `launcher`, a timeout, an unknown word) | `LoginFailed("unreachable")` | nothing: the next mint asks again |

Inside a wait there is no ask, and `mint` answers the same `Mint`. A generation change clears every wait and the JWT.

### 5.2 Generation, nudge, cooldown: re-checked

- **The generation:**
  - It is read live at every mint and every `state()`: `Bridge.config(context).storytelGeneration` while managed
    (`M/bridge/BridgeConfig.kt:91`, `:115`).
  - A parent's save or clear moves it (§1), so the phone drops its JWT and every wait at its next mint. A refused
    login's hour then ends with the new save, as today.
  - The phone tags a JWT with the generation it believed at the ask, not the answer's. Until the policy catches up,
    one extra ask hits the server's cache.
- **The nudge:**
  - `BridgeSync.pullStorytel` goes (`M/bridge/BridgeSync.kt:41`, `:96-109`). There is nothing to pull.
  - `BRIDGE_CHANGED` and the restrictions are unchanged. The launcher still writes `storytel_generation`, which is the
    phone's only on/off and "new login" signal.
- **`storytel_refused.json` is no longer needed:**
  - It existed so the phone would not log in to Storytel inside an hour after a refusal. The phone no longer logs in.
  - The server's cooldown stops every Storytel request (`S/storytel.rs:853-856`).
  - The per-phone limit (§7.3) stops a phone that asks in a loop.
  - The phone's `refusedUntil` in memory saves the bridge round trips within a process.
  - A new process asks once and gets `refused` again, which costs the server nothing.
  - The file is deleted (§5.5).
- **`state()`:**
  - `none` while the generation is 0 or `off` runs;
  - `refused` while `refusedUntil` runs;
  - else `ok`.
  - `storytelWaiting` and the storage floor read it unchanged. A fresh process says `ok` until it hears otherwise, so
    the PWA ORs in the server's cooldown (§2.5).

### 5.3 Offline

- **Downloaded books play** from their copy with no request, as today (`copyOnly`). No JWT is involved.
- **A download** waits for its network before it mints (`Downloads.kt`'s `downloadWaiting` precedes `mint`). If the
  network drops between the two, the ask times out to `LoginFailed("unreachable")`. The run stops with no item error,
  and the next run asks again.
- **A room** needs Wi-Fi anyway. A failed ask is the room's failed play, `login_failed` (`sonosMintCode`,
  `M/logic/Storytel.kt:125-131`), and the next tap asks again.
- **The cost for a room:**
  - A cold server login can need more than the ask's 4 s. The server keeps it (§2.2), so the kid's second tap works.
  - Today's phone login has the same bound (`StorytelApi.kt:193`, inside the same 10 s), so this is no regression.
  - Lengthening the room's mint budget would move `HANDOFF_MS`, `SonosTail.MAX_MS` and `SWITCH_WAIT_MAX_MS`
    (`music/CLAUDE.md`, "Storytel on a room"). Not done.
- **No bookmark outbox exists** (§1). A future position mirror would get its JWT from the same source and keep its
  outbox until an `ok`.

### 5.4 The bridge call (`M/bridge/StorytelBridgeToken.kt`, new; `SpotifyBridgeToken.kt` copied)

- `sealed interface JwtResult { Ok(jwt, expiresAtMs, session), Off, Refused(retryAfterMs), Busy(retryAfterMs),
  Unreachable(word) }`. `Ok.toString()` prints `jwt=<redacted>`.
- `jwtResult(status, jwt, expiresInMs, session, nowMs)` (pure):
  - `ok` needs a non-empty JWT, a life above 0 and a valid session. A missing part is `Unreachable("launcher")`.
  - `refused` and `busy` take their wait (default 1 h and 30 s).
  - Every other word is `Unreachable(word)`.
- `StorytelBridgeToken.ask(bridge, stale, timeoutMs)`: one `getStorytelToken`, a `CompletableDeferred`, and
  `withTimeoutOrNull`.
  - An older launcher drops the unknown oneway call. That is a timeout, `Unreachable("launcher")`.
  - `TIMEOUT_MS` and `STATUSES` are checked against `bridge_contract.json`.

### 5.5 The first start: the old login goes (`M/bridge/StorytelLegacy.kt`, replacing `StorytelLogin.kt`)

- **`StorytelLegacy.purge(context)`** deletes:
  - `noBackupFilesDir/storytel.bin`, `storytel.bin.tmp`, `storytel_refused.json` and `storytel_refused.json.tmp`;
  - the Keystore entry `vibb_storytel` (`containsAlias` then `deleteEntry`).
- **When it runs:**
  - It is idempotent and runs once per process from `StorytelAccount.build`.
  - Every sync reaches that build through `writeReport`'s `state()` (`Sync.kt:303`). So the first sync after the
    update, or the update's own sync (`PackageReplacedReceiver`), deletes the copy.
- **Failures:** never thrown, never logged with a path's content. A debug build logs one fixed line: "storytel: the old
  login deleted".
- **`AndroidKeyStore` and `storytel.bin`** appear in `main` only in this file (`SourceScanTest`).

### 5.6 The debug switch and the fake

- **The fifth reader** in `StorytelEndpoints.kt` is `debugJwt()`: the fake's `GET /fake/jwt`.
  - It is taken only while the app is unmanaged. A managed phone's JWT always comes through the bridge, as Spotify's
    `debugTokens()`.
  - `StorytelAccount.build` asks through it when it isn't null. The generation is then the constant
    `DEBUG_GENERATION` 1.
  - The fake's JSON is read by `StorytelApi.jwtAnswer`. That is the route's shape, the launcher courier's twin, kept
    in the music app for the debug route only.
- **`debugSwitchJwt`:** with the switch set, only a JWT starting `FAKE-JWT-` goes on; anything else is
  `Unreachable("launcher")`.
  - This is Spotify's `debugSwitchToken` (`M/logic/Spotify.kt:74`) for Storytel.
  - It closes the music review's debug item 8 (first bullet): a real JWT from a managed debug build with a leftover
    `config.json` never reaches a loopback port.
- **`DebugStorytel.seedLogin`** goes (`M/debug/.../DebugStorytel.kt:44-60`). A `storytel.json` still in the feed is
  deleted unread, with one fixed log line, so a fake password never sits on shared storage.

## 6. Migration and update order

**Release in this order:** server (steps 1 and 3), then launcher, then music app. Every other order fails safe: no new
Storytel books, the downloaded ones still play, and never a login on a phone.

| Server | Launcher | Music app | What happens |
|---|---|---|---|
| new | old | old | The generation moved (§2.4). The old app asks for the login, the old launcher gets the empty 404 and answers 0, and the app deletes `storytel.bin`. Storytel stops on this phone ("None") until the app is updated. |
| new | new | old | The same through the new launcher's immediate 0. |
| new | old | new | The app's `getStorytelToken` is dropped by the old launcher: a 10 s timeout, `LoginFailed`, no new books. The report says `ok` but `waiting` stays. Device › Music shows the launcher's version. |
| new | new | new | Works. |
| old | new | new | The launcher's POST meets an empty 404, which is `off`. The app reports `none`, the old login is already purged, and no new books come until the server is updated. |
| old | new | old | Equal generations: the old app keeps its stored login and works. The first parent save after that: the launcher answers 0 and the app deletes it. |
| old | old | new | Like row 3. |

- **The old route needs no grace period:** its only callers (older launchers) are told "delete yours" by the fallback.
- **The `getStorytelLogin` AIDL method and `IStorytelCallback.aidl` stay forever** (transaction codes). The launcher's
  implementation answers 0.
- **DEPLOY.md** gets the order line under Storytel, next to "Order" (`server/DEPLOY.md:505-509`).

## 7. Privacy and security

### 7.1 Secrets

- **The JWT is a bearer** for the family's whole Storytel account.
  - Server: `Jwt`, `KeptJwt` and `PhoneJwt` have no `Debug` or `Display`, with a probe test. Not in the database, a
    log, an event, an error text or `music_state`. The payload is decoded and dropped.
  - Launcher: in memory for one call. `StorytelTokenAnswer.toString` is redacted.
  - Music app: in memory only. Never in Room, `renderer.json`, the report, a file or a log.
    - `Jwt`, `JwtResult.Ok`, `StorytelRequest`/`Response` and `Mint.Url` keep or get redacted `toString`s
      (`SourceScanTest`).
    - `Authorization`/`Bearer` stay only in `StorytelApi.kt` and `SpotifyApi.kt`.
    - There is no `Log.` in `storytel/`.
- **The minted CDN URLs are unchanged:** per download attempt and per room play, used once, never stored, never
  followed.
- **The session string** (`"7:3"`) is no secret but carries no meaning outside the courier. It is not logged.

### 7.2 What a stolen or rooted phone gets

- **Before this design:** the family's e-mail and password, which are reusable elsewhere, for good, readable from
  `storytel.bin` with root, from the bridge and from the route with the device token.
- **After it:**
  - The JWT in the music app's memory, valid at most one JWT lifetime (`exp - iat`) from its login.
  - With the device token, new JWTs at up to 20 asks an hour, for as long as the phone stays enrolled.
  - Removing the phone in the PWA (its token dies), or switching its Storytel off (`off` at once), ends that. The last
    JWT stays valid until its `exp`.
- **Not verified:**
  - Storytel's real `exp - iat` (open question 2). Our reuse cap of 24 h does not shorten the token's validity at
    Storytel.
  - Whether a new login, or a password change, revokes older JWTs (§12).

### 7.3 Rate limits (so one device token can't make the server hammer Storytel)

- **Per phone:** `PHONE_JWT_ASKS_PER_WINDOW` = 20 asks a rolling hour, then `busy` with the wait.
  - These are Spotify's numbers: an ask once per process and JWT lifetime, plus retries.
  - Counted after `off` and `key`.
  - Spotify's `Accounts::phone_ask` (`S/spotify.rs:1228-1250`) is lifted unchanged into `music_net::PhoneAsks`, and
    both routes use one each.
- **Logins:** a plain ask never logs in while the kept JWT has 2 minutes left.
  - A force logs in at most 3 times an hour per phone, and only for a JWT at least 5 minutes old (§2.3).
  - The cooldown holds for every path. Once Storytel refuses, nothing is sent for an hour, for that generation.
  - One malicious token therefore costs Storytel at most 3 logins an hour beyond the normal one per JWT lifetime.
- **Other limits that still apply:** the device API's bucket (`S/limits.rs:25-26`) and the 256-byte body limit.

### 7.4 The reviews

- **Server review** (`security-review-2026-10-21-server.md`):
  - M3 becomes **Fixed (design 58)**, with the commit, in its section and the status table (`:309` row style).
  - The data table's "every Storytel-on phone in plaintext" (`:411`) becomes "the phones get the server's JWT (memory
    only)".
  - Lines `:387`, `:441` and `:450` follow.
  - I11 (`storytel_login_sent` per fetch) is fixed by the route's removal.
- **Music review** (`security-review-2026-10-21-music.md`):
  - Privacy 2 ("The Storytel login at rest", `:183-186`) is fixed.
  - The data table's "Storytel login" row (`:252`) goes.
  - "Secrets at rest" drops `storytel.bin` and `storytel_refused.json` (`:232`).
  - Debug item 8's first bullet is fixed (§5.6).
  - The `toString` list (`:214`) drops `Login`.

## 8. Tests and fixtures

**Fake values only** (the standing rule):

- JWTs are `FAKE-JWT-<n>`, sessions `"1:<n>"`, e-mails `*.example.invalid`.
- A JWT whose claims a test must read is **built at run time**, so no `eyJ` literal is ever committed:
  `"FAKE-JWT-HEAD." + base64url({"iat":..,"exp":..}) + ".FAKE-SIG"`.
- `BridgeTest`'s seal test with `ella@example.org` goes with `StorytelLogin` (music review P-L4's kind of name).

**Server:**

- `storytel::tests`:
  - `jwt_life_from_claims`: `exp - iat`, the 300 s floor, the 24 h cap, the 30 min fallback for no dots, bad base64,
    bad JSON or a missing claim;
  - `a_kept_jwt_is_answered_while_a_shelf_read_waits` (the shelf parked on `CannedFetch`'s gate, the phone answered
    at once);
  - `a_force_relogs_only_for_the_stale_kept_old_jwt` (two phones with one stale session make one login; a stale
    that isn't kept gets the newer one; too young gets the same back; the fourth force in an hour gets the same back);
  - `the_cooldown_holds_for_phones` (no request, `retry_after_s` to its end);
  - `secret_types_have_no_debug` gains `KeptJwt` and `PhoneJwt`.
- `src/tests/music_storytel.rs`:
  - the route's statuses (each row of §2.2, `no-store`, `expires_in_s` = left - 60);
  - the spawned login kept after the 8 s budget;
  - the per-phone limit, with `off` and `key` not counted;
  - the stale body (valid, junk, too long);
  - no JWT in a captured log line, `security_events` or the database;
  - **the retired route:** a bearer GET of `/api/devices/music/storytel` is a bodiless 404;
  - **the migration:** the generation +1, a running cooldown kept, `saved_at` unchanged, the policy's
    `storytel_generation` moved.
- `the_storytel_login_is_write_only_sealed_and_generational` (`S/tests/music.rs:3669`) loses its GET and
  `storytel_login_sent` parts. The PWA's row and warning (§2.5) get a test.
- `policy_json_keys_snapshot` is unchanged.

**Launcher:**

- `StorytelTokenCourierTest`: each status, an incomplete `ok` as `launcher`, the waits' bounds and defaults, a 404 with
  no word as `off`, a 5xx as `unreachable`, the redacted `toString`, `staleBody`.
- `MusicPlanTest`:
  - the contract (§3.4);
  - a source scan that `answerStorytel` never calls `createMusicServer`, and that `MusicServer` has no `storytel()`.

**Music app:**

- `StorytelApiTest`, rewritten:
  - the 302 captured and never followed;
  - the 401 with one forced ask carrying the stale session and one retry;
  - the same session back means no retry and `Failed(401)`;
  - two mints share one ask, and a late answer is kept;
  - a generation change drops the JWT and the waits;
  - each `JwtResult` row of §5.1, with no ask inside a wait;
  - the deadline's reserve for the asset call, and the ask's cut;
  - a cancelled mint ends the blocked request;
  - the debug base for `10.0.2.2` and loopback only.
- `StorytelBridgeTokenTest`: `jwtResult` and the contract's statuses and timeout.
- `StorytelLegacyTest`: the file part on a temp dir; the alias delete through a seam.
- `StorytelDownloadTest`: the `FileCooldownStore` test (`:415-424`) goes.
- `BridgeTest`: the method list; the seal test goes.
- `EpisodeArtTest`: §3.4.
- `SourceScanTest`:
  - the redacted list (`:290-295`: `StorytelLogin.kt` out, `StorytelBridgeToken.kt` `Ok(` in);
  - readers (`:344-362`: `debugJwt` to `StorytelAccount.kt`, one declaration and five reads);
  - `seedLogin` (`:620`) becomes "`storytel.json` is deleted unread";
  - new: `AndroidKeyStore` and `storytel.bin` only in `StorytelLegacy.kt`.

**Fixtures and the fake** (`scripts/fake-storytel.py`, `--selftest`, CI `music.yml`):

- **New:** `music/app/src/test/resources/storytel/jwt_answers.json`, the route's answers (ok, off, refused, busy,
  unreachable, key). The music app's debug-route parser test and the fake's `/fake/jwt` answer from it.
- **The fake gains:**
  - `GET /fake/jwt`: `FAKE-JWT-<n>`, `--jwt-ttl S` for `expires_in_s`, default 3540;
  - `--refuse`, which now answers 409 `refused`;
  - `--off`, `--busy S`, `--jwt-down` (502);
  - `--revoke-after K`: the K-th asset call with JWT n answers 401, and the next `/fake/jwt` with `stale=1:n` issues
    n+1, logged as "jwt #2 (stale 1:1)".
  - The asset route accepts only the current JWT.
- **`--no-jwt`** goes, or with §2.6 stays for the server's login.
- **The selftest** covers each of these against `jwt_answers.json`.
- **The login fixtures** (`login_request.json`, `login_ok.json`, `login_no_jwt.json`, `aes_vectors.json`) leave the
  music app:
  - With §2.6 they move to `server/testdata/storytel/`. The server's `password_vectors` (`S/storytel.rs:1126`) reads
    `aes_vectors.json`, and `music.yml` watches `server/testdata/storytel/`; the root `CLAUDE.md` CI list follows.
  - Without §2.6 they are deleted.

## 9. Emulator and device checks

**`docs/testing/emulator.md` §6i (the phone alone, on the debug feed):**

- `storytel.json` and the "push it again" lines go. `config.json`'s `storytel_base` gives the JWT and the CDN.
- What to check:
  - The reading order, the bookmark's book first, the drop and resume, `--locked`, the 10-minute limit, copy only,
    offline and the room: unchanged. Each run asks `/fake/jwt` once per process, not once per book.
  - **A refused session:** `--refuse`. The report says `refused`, and the entry's `waiting` is `login`. One `jwt` line
    in the fake's log, none more in that process within the wait. `am force-stop` then a sync: one more line.
  - **A revoked JWT:** `--revoke-after 1 --books 111:5,222:5`. A 401 on book 222, one `jwt #2 (stale 1:1)` line, the
    same asset retried once with `FAKE-JWT-2`, and the book completes.
  - **The old login goes:** install the previous debug music app, seed a login as today's §6i, install the new one and
    sync. `run-as ... ls no_backup` names no `storytel.bin` or `storytel_refused.json`, and logcat has "storytel: the
    old login deleted".
  - **No secrets:** the two greps unchanged (`FAKE-JWT|FAKE-SIG|...`). They also find no `jwt` file.
- **§6i B, the chain** (with §2.6):
  - the server's `.env` `STORYTEL_BASE=http://127.0.0.1:8730`;
  - the PWA saves the fake login (the probe hits the fake's login and bookshelf), ticks the series and turns on the
    phone's Storytel;
  - the debug launcher and the managed music app (as §6l A1).
  - The fake's log shows logins only from the server: one at the save, none per book.
  - The music app's downloads mint with the server's `FAKE-JWT-1`. A second phone (or `pm clear` and a re-enrol)
    gets the same JWT with no new login.
- **Device checks (Jelly Star, the family's account):**
  - a 300 MB book downloads through the courier;
  - a room play of a book not on the phone, cold (the server restarted) and warm;
  - Storytel's real `exp - iat` read from the server's debug line (§11 Q2);
  - after the update, `storytel.bin` gone.

## 10. Steps (each one implementer, one sitting, its tests, the suites green)

1. **The contract commit** (`server/`, `launcher/`, `music/` in one commit):
   - Server: §2.1, §2.2, §2.3 and `PhoneAsks` (§7.3). The old route stays.
   - Both apps: §3.
   - Launcher: §4's courier and `MusicServer.storytelToken`.
   - Music app: §5.4's call and its result. Nothing mints with it yet, as 26c step 3 did.
   - Every test of §3.4, §8's server and launcher parts. Server version 0.34.0.
2. **The music app on the JWT** (`music/` only): §5.1-§5.3, §5.5, §5.6, the fake and `jwt_answers.json`, §6i A, and
   the music tests of §8.
3. **The retirement** (`server/` and `launcher/`, one commit):
   - Server: the old route, its event and `StorytelLogin`; the migration (§2.4); the PWA (§2.5).
   - Launcher: `getStorytelLogin`'s 0 and `MusicServer.storytel()` removed.
   - The tests of §8 for both.
4. **The chain** (only with Q3's yes): §2.6's `STORYTEL_BASE`, the fake's bookshelf, the fixtures' move and
   `music.yml`, §6i B.
5. **Docs:**
   - `music/CLAUDE.md`: layout `storytel/` (`:66-70`), the fake (`:110-114`), the bridge (`:189-191`), the account
     (`:235-245`), the secrets (`:258-263`) and the debug switch (`:264-271`).
   - `launcher/CLAUDE.md:441`, `:447`; `server/CLAUDE.md:226`; `docs/notes/server/music.md`'s Storytel section;
     DEPLOY.md (§2.5, §6).
   - The reviews (§7.4). PLAN.md: the backlog entry built, its "small, any time" lifetime item done.
6. **Code QA, fix all findings,** §9's emulator checks, then the Jelly Star.

**Release:** server (steps 1+3), launcher, then music app (§6).

## 11. Open questions for the user

1. **Move the generation at the update (§2.4)?**
   - **Proposal: yes.** Every older music app deletes the family's password at the server's update, and its new
     Storytel books wait until the music app is updated. Books on the phone still play.
   - Without the move, older apps keep the password and keep logging in with it until the parent updates the music
     app, or saves the login again.
2. **Storytel's real JWT lifetime.**
   - We need it for two numbers: what a stolen phone gets at most, and how often the server logs in.
   - Proposal: step 1's server logs, once per login at `debug`, the computed life in seconds (never the token or a
     claim). You read one line on the Pi (`RUST_LOG=kid_phone_server=debug`) after the update.
   - If the payload turns out to hold the account's e-mail, say so: the phones would still carry it inside the JWT
     (never the password).
3. **The emulator chain (§2.6, step 4).** Add a loopback-only `STORYTEL_BASE` to the server, as Spotify's bases, so
   the whole chain - PWA, server, launcher, music app - runs against the fake on the emulator?
   - **Proposal: yes.**
   - The alternative is the phone alone on the emulator (§6i A) and the chain only on the Jelly Star with the real
     account.

## 12. Not verified here

- The real JWT's `exp`/`iat` and its other claims (Q2). The fixtures only have `FAKE-JWT-1`.
- Whether Storytel accepts one JWT from several devices at once, or whether a new login revokes older JWTs. Today's
  phones each log in on their own, so the shared JWT is new usage. The PLAN entry's storytel-player check doesn't cover
  it. A Jelly Star check while the server's sweep and the phone run together answers it.
- Whether a Storytel password change revokes issued JWTs: what the parent could do after a theft (§7.2).
- The ask's 4 s on a room against a cold server login over tsnet on the real phone (§5.3).
- That `AndroidKeyStore.deleteEntry` of `vibb_storytel` succeeds on the Jelly Star's Android build. A failure leaves
  an unused key, never a login.
