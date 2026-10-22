# 58 - Storytel's session from the server: the family's password never leaves it

Architect draft (2026-10-21), revised the same day for the design QA `docs/review/qa-58-design.md` and the user's
answers to it (§11, and "Revision for qa-58-design" at the end).

The user approved server review M3's fix on 2026-10-21 (`docs/review/security-review-2026-10-21-server.md` M3;
PLAN.md, Fix backlog, "Storytel login to the phone"). This design builds on:
- design 26b (`26b-storytel.md`): the server's Storytel client §2.1, the phone's §4.1-§4.2, the room's mint §5.1;
- 26c's Spotify token courier (`26c-spotify.md` §2.6, §5.5, §6.1, §8), copied wherever it fits.

It touches the server, the launcher, the music app, and the bridge contract in both apps. `S/` = `server/src/`, `L/` =
`launcher/app/src/main/java/com/kidslauncher/mdm/`, `M/` = `music/app/src/main/java/me/vibb/music/`. Line numbers are
`main` at 7b5a9a4b.

## 0. The user's decisions (2026-10-21)

1. **The family's Storytel e-mail and password never leave the server again.** No route, bridge call or file on a
   phone carries them. Whether the e-mail rides inside Storytel's JWT is measured in step 1 and decided then (§2.7).
2. **The phone asks for a Storytel JWT when it needs one.** The JWT travels from the server to the launcher over the
   device bearer, then through a new bridge method shaped like `getSpotifyToken` to the music app.
3. **The server answers with its own cached JWT** (`storytel::Account`), shared by all phones and its own sweep, plus
   how long the JWT still lives.
4. **The music app keeps the JWT in memory only**, until it expires.
5. **The JWT's lifetime comes from its own claims** (storytel-player's rule, §2.1), not a fixed hour.
6. **Old copies go.** The music app deletes `storytel.bin` and its Keystore key. An older music app on a newer server
   fails safe: no Storytel, never the login.
7. **The answers to this design's questions:**
   - The login's generation moves at the update (§2.4).
   - The lifetime and `payload_has_email` log line goes in, and it gates step 2's release (§2.7).
   - A loopback-only `STORYTEL_BASE`, with a fake-login guard (§2.6).
8. **The answers to QA's questions:**
   - **Q-A** (an e-mail inside the JWT) is decided after step 1's measurement. Branch (a) is this design. Branch (b) is outlined in §2.7 and would be its own design 58b.
   - **Q-B:** a Storytel 5xx, 408 or 429 at login is an outage with a short backoff, not vibb's hour-long cooldown
     (§2.3). It is a recorded deviation from vibb (§2.3, "Deviation").

## 1. Facts (checked)

**Today's path:**

- `GET /api/devices/music/storytel` (`S/device_routes.rs:104-107`, `S/handlers/music_api.rs:225-291`) opens the
  sealed row and answers `{generation, email, password}` with `no-store`. It writes a `storytel_login_sent` security
  event on every fetch (`:274-281`; server review I11 objects to that).
- The launcher fetches the login only when the music app's generation differs from the policy's (`!=`). It answers
  through `IStorytelCallback` (`L/music/MusicBridgeService.kt:89-93`, `:223-253`):
  - **a 404 becomes `onLogin(0, null, null)`, "delete yours"** (`:236-239`);
  - any other non-2xx answers nothing (`:248`).
- The music app pulls the login in every sync (`M/bridge/BridgeSync.kt:41`, `:96-109`). It seals it under Keystore
  alias `vibb_storytel` into `noBackupFilesDir/storytel.bin`, written through `storytel.bin.tmp`
  (`M/bridge/StorytelLogin.kt:21`, `:29`, `:43-51`).

**The phone's Storytel client logs in itself** (`M/storytel/StorytelApi.kt:105-123`, `:162-215`):

- **The login:** AES `pwd=`, then `login.action`, which gets `min(LOGIN_TIMEOUT_MS, deadline - now)`. That is up to the
  whole mint budget; the asset call takes what is left (`:132`, `:193`).
- **The JWT:** kept 1 h (`:241`), with one forced re-login after a 401.
- **The `used` rule:** a forced call that finds another JWT already takes that one (`:184-188`). A fresh JWT that is
  refused too is dropped (`:118-120`).
- **The cooldown:** in `noBackupFilesDir/storytel_refused.json` (`M/storytel/StorytelAccount.kt:71`, `:86-103`).
- **`state()`:** `none`/`refused`/`ok` (`:125-129`). The report reads it (`M/sync/Sync.kt:303`), and so do the storage
  floor (`Sync.kt:217`, `M/sync/Downloads.kt:151`) and `storytelWaiting` (`M/logic/Storytel.kt:167-171`).

**The phone calls Storytel only through `StorytelAccount.mint`, in two places:**

- **A download** (`M/sync/Downloads.kt:207-209`): 15 s for the asset call, 60 s in all (`:213-214`).
  - A refused login re-enqueues the worker at the cooldown's end (`Downloads.kt:172-174`, `M/sync/DownloadRun.kt:85`,
    `:139`).
  - Any other stop waits for the next sync. The sync's backstop is periodic, every 6 h (`Sync.kt:103`).
- **A Sonos play or advance** (`M/sonos/SonosRenderer.kt:184`): 6 s and 10 s (`M/sonos/SonosController.kt:2376`,
  `:2384`). A failed mint drops a step or an advance (26b §5.1).

The brief mentions a stream and a bookmark sync, but neither exists:

- The phone plays a book only from its downloaded copy, with no request (`ItemTag.copyOnly`; `music/CLAUDE.md`,
  "Storytel is download-only").
- Position mirroring was not ported: user decision of 2026-10-09 (26b §0 "Out", §9; `StorytelApi.kt:24-25`).

This design serves the two mints. Any later caller of a JWT gets it from the same source (§5.1).

**The server:**

- **The JWT and the lock.** `storytel::Account` (`S/storytel.rs:806-1077`) keeps the JWT in `Session`, under one tokio
  `Mutex`, for `JWT_TTL` = 1 h (`:58`, `:865`, `:1062`).
  - The shelf read holds that lock across `fetch_shelf` (`:969-982`, `:997-1006`), and so across the server-wide fetch
    gate. A feed fetch can hold that gate for 120 s (`docs/notes/server/music.md`, "PWA").
  - **So a phone asking for the JWT during a sweep would wait behind the shelf read.** §2.1 takes the JWT out of that
    lock.
- **A failed login.** `login` drops the JWT on any failure (`:870-880`).
  - Only a non-200 answer stamps the cooldown, and that includes a 5xx: vibb's rule (`:684-705`).
  - A transport error, a timeout or `no_jwt` leaves neither a JWT nor a cooldown. The next ask logs in again.
- **The sweep's force.** `fetch_shelf`'s second attempt forces a login whatever JWT is kept (`:894`, `:910`).
- **The generation.**
  - The cooldown is stamped per generation (`:594-605`, `:848-882`).
  - A save or a clear moves the generation (`S/handlers/music.rs:1498`, `:1542`).
  - The policy's `storytel_generation` is the row's generation while this phone's switch is on and a login is stored,
    else 0 (`S/music.rs:1353-1355`, `:1370-1378`).
- **The key.** `credentials()` warns and records `storytel_key` on every call when the key can't open the row. With no
  music key at all it answers `NotConfigured` (`:620-637`).
- **Unknown paths** answer an empty 404 on both listeners (`S/main.rs:705-707`, `:715`).
- **Every Storytel request is `Reach::Public`** (`:693-697`).

**The Spotify courier to copy:**

- the route `spotify_token` (`S/handlers/music_api.rs:307-395`): an 8 s budget, a spawned refresh that is kept
  (`S/spotify.rs:1563-1590`), a per-phone rolling limit (`S/spotify.rs:121-133`, `:1228-1250`);
- the launcher's `answerSpotify` (`L/music/MusicBridgeService.kt:163-185`) and `SpotifyTokenCourier.kt`;
- the music app's `SpotifyBridgeToken.kt` and `TokenCache`: the prefetch ahead of expiry, and the prompt retry
  `PROMPT_RETRY_MS` = 5 s (`M/spotify/TokenCache.kt:200`).

**storytel-player (debba/storytel-player 2ca1e2d, 2026-10-18, `server/storytelApi.ts`):**

- After a password login it takes `max(300, exp - iat)` from the payload, and 1800 s when the claims are missing or
  unreadable (`:310-321`, `:355-363`).
- It reuses the JWT until 60 s before that (`:295`).
- On a 401 it drops the cached JWT, logs in again once and retries once (`:226-254`).

**Tests that pin the bridge's method list:**

- `MusicPlanTest.kt:51-52` (the list) and `:688` (13 methods check their caller);
- `music/.../bridge/BridgeTest.kt:40-41`;
- `music/.../logic/EpisodeArtTest.kt:232-233`, which asserts `takeLast(2) == [wantArt, getSpotifyToken]`.

The launcher's capabilities are listed in `L/server/StatusCapabilities.kt:18`. The server reads `music_v1` from them
(`S/handlers/music.rs:4138`).

## 2. Server

### 2.1 The JWT (`S/storytel.rs`)

**The lifetime** comes from `jwt_claims(jwt)`:
- It decodes the JWT's second dot-separated part with a URL-safe `GeneralPurpose` engine set to
  `DecodePaddingMode::Indifferent`. `URL_SAFE_NO_PAD` would refuse padding; `base64` 0.22 is in the tree.
- It reads `exp` and `iat` as JSON numbers.

The life, counted from the login's own monotonic instant:
- **When the Pi's wall clock is sane** (year at least 2026): `min(exp - iat, exp - wall_now)`, at least 60 s. A
  still-valid older token handed back with an old `iat` doesn't outlive itself (QA 58-16).
- **Otherwise:** `exp - iat`, at least `JWT_LIFE_FLOOR` (300 s).
- **Always:** at most `JWT_LIFE_CAP` (24 h, ours).
- **Fallback:** `JWT_LIFE_FALLBACK` (30 min) when there are no dots, the base64 or JSON is bad, or a claim is missing
  or not a number.

The payload is parsed into the life and one boolean (§2.7), then dropped. It is never logged, stored or kept.

`JWT_TTL` goes. The probe's `keep` (`:1055-1071`) and `login` (`:865`) both use the claims.

**The JWT leaves the session's lock:**

- **The new fields on `Account`:**
  - `jwt: std::sync::Mutex<Option<KeptJwt>>`, never held across an `.await`;
  - `login_lock: tokio::sync::Mutex<()>`, so one login runs at a time server-wide;
  - the login backoff and the phone-login window of §2.3.
- **`KeptJwt`** gains `made: Instant`, `life: Duration` and `session: String`.
  - The session is `"<generation>:<16 hex>"`. The hex is drawn at random per login, so a restart never repeats one
    (QA 58-5). It names the JWT and nothing else.
- **`bearer` (the fast path).** It reads the kept JWT under the std lock first. A JWT of this generation with more than
  `MARGIN` (60 s) left is cloned out. `Jwt` gains `Clone`, still with no `Debug` or `Display`.
- **The slow path.** Only a login takes `login_lock`. Under it, the code reads the kept JWT again before it logs in.
- **The shelf.** `Session` keeps only the shelf cache and `sent`. The shelf read still holds the session lock, but no
  phone needs it. Nothing takes the session lock while holding `login_lock`.
- **`keep` and `forget`.** `keep` never waits (26b QA L1 holds). `forget` clears both.
- **A forced login that fails without a refusal keeps the old JWT** (QA 58-7). A plain login on an expired JWT finds
  nothing to keep.

**The phone's JWT:** `Account::phone_jwt(&self, state, device, stale: Option<&str>) -> Result<PhoneJwt,
StorytelError>`.
- `PhoneJwt` is `{jwt, left, session}`, with no `Debug` and no `Display`.
- Rules in order:
  1. **The generation and the cooldown** come from one query of the row's plain columns:
     - `generation`, `login_refused_at`, `login_refused_generation`, and whether `ciphertext` is set;
     - **never an open of the sealed login** (QA N-8);
     - only rule 4's login calls `credentials(state)`, so the password is in memory only for a login;
     - the key is the route's check (§2.2).
  2. **The kept JWT is answered** while it is of this generation and has at least `PHONE_MIN_LEFT` left, unless
     §2.3's force applies. `PHONE_MIN_LEFT` = `min(15 min, life / 2)`. It sits above the phone's prefetch point
     (§5.1, at most 10 min plus the 60 s margin), so a prefetch gets a new JWT, not the same one (QA 58-1).
  3. **No login now** (inside the cooldown or the backoff, or past the cap). No request is made.
     - **A plain ask still gets the kept JWT** of this generation while it has more than `2 × MARGIN` (120 s) left
       (QA N-9). Every phone keeps working on a valid JWT through an outage.
     - **Otherwise:** `Cooldown { until }` inside the cooldown, `Busy { until }` inside the backoff or past the cap.
     - A declined force never gets the kept JWT (§2.3).
  4. **Otherwise one login** (§2.2 runs it as a task), counted in the phone-login window.
     - A fresh login is answered when it leaves more than `2 × MARGIN`.
     - **Less than that** (Storytel handed back a token about to expire, QA N-6): `Outage("short_life")`, which starts
       the backoff (§2.3), and the phone gets `unreachable`. So `expires_in_s` is never below 61, and the launcher's
       1..86 400 bound always holds.
- **How often the server logs in:**
  - With an hour's life, a login is due 45 minutes after the last while phones use Storytel.
  - There are none while no phone or sweep does.
  - §2.3 bounds the rest.

### 2.2 `POST /api/devices/music/storytel/token`

The route sits behind the device bearer (`require_device_token`, `S/device_routes.rs` beside `spotify/token`), with
`DefaultBodyLimit::max(256)`.

- **The body** is read as `Bytes`, not as `Json`, so a missing or wrong content type is never a 415.
  - It is `{}`, or `{"stale": "<g>:<16 hex>"}` (§2.3).
  - It is read leniently: empty, or a `stale` that isn't `[0-9]{1,18}:[0-9a-f]{16}`, is a plain ask.
- **The answer** is always JSON with its `status` and `Cache-Control: no-store`. The JWT appears only with `ok`.
- **The shapes are pinned** in `server/testdata/storytel_token_answers.json`, the shared contract (§8).

| Status | Body | When |
|---|---|---|
| 200 | `{"status": "ok", "jwt": "FAKE-JWT-1", "expires_in_s": 3540, "session": "7:00000000000000a1"}` | `expires_in_s` = what is left minus `MARGIN` (60 s) |
| 404 | `{"status": "off"}` | this phone's `music_storytel` switch is off, or no login is stored |
| 409 | `{"status": "refused", "retry_after_s": 2400}` | Storytel refused the login now (3600), or the cooldown runs (until its end, at least 1) |
| 429 | `{"status": "busy", "retry_after_s": 30}` | this phone's asks are over the limit; the login backoff runs or the phone-login cap is reached and no kept JWT has 2 min left; or a force is declined (§2.3) |
| 502 | `{"status": "unreachable"}` | the login failed as an outage (§2.3), or the budget ran out |
| 503 | `{"status": "key"}` | no music key on this server, or the row was sealed with another key |
| 500 | `{"status": "unreachable"}` | a database error |

**The order of the checks.** Spotify's order is binding and app, then the limit, then the mint. Storytel's is:
1. The switch, then whether a login is stored. Either one fails as `off`, and the ask isn't counted.
2. The per-phone limit.
3. **The key, by fingerprint only** (QA 58-9).
   - `state.music_key` is `None`: `key`.
   - The row's `key_fingerprint` differs from the key's: `key`.
   - No open and no record. `phone_jwt` records `storytel_key` at most once per generation, should the open still
     fail.
4. `phone_jwt`. Its errors map as follows:

| `phone_jwt` error | Answer |
|---|---|
| `NotConfigured` | `off` |
| `KeyUnusable` | `key` |
| `Cooldown` or `Refused` | `refused` |
| `Busy` | `busy` |
| `Fetch`, `NoJwt`, `Outage` (§2.3) or the budget | `unreachable` |
| `Database` | 500 `unreachable` |

**The budget.** `PHONE_JWT_BUDGET` = 8 s, under the launcher's 10 s.
- `phone_jwt` runs as a `tokio::spawn`ed task (`AppState.storytel` is an `Arc`, `S/main.rs:126`).
- A login still running when the route gives up finishes and is kept, so the next ask gets it at once (Spotify's
  `MintJob` rule).

**Logging:**
- No security event per ask (I11).
- One `tracing::debug!` per answer: the device id and the status.
- An `info` at every login: §2.7's line.
- Never logged: the JWT, its payload, the session or the body.
- `PhoneJwt` and `KeptJwt` join the `secret_types_have_no_debug` probe (`S/storytel.rs:1174`).

### 2.3 Failed logins, the force, and the bounds (QA 58-3, 58-7, 58-8)

**A refusal and an outage are now different** (the user's Q-B):
- **One function decides:** `classify_login(status, body)` in `login_with`, which every login uses, the save's probe
  included.
- **A refusal is a 4xx other than 408 and 429.** It stamps the hour-long cooldown, as today.
- **An outage, for every login made through `Account`** (the sweep's and the phones'):
  - which: a 5xx, a 408, a 429, **any other non-200** (a 1xx, a 204, a 3xx the redirect policy didn't follow, ...;
    QA N-4), a transport error, a timeout, 200 without a JWT (`no_jwt`), or a too-short life (§2.1);
  - `Outage(code)`: no cooldown stamp; `login_error` records the code (`http_503`, `network`, `no_jwt`, ...);
  - **the login backoff** starts: no login for 1 min, doubling to 30 min (`LOGIN_BACKOFF_MIN` .. `LOGIN_BACKOFF_MAX`);
  - the backoff resets on a successful login or a new generation.
- **Inside the backoff:**
  - a phone gets `busy` with `retry_after_s` to the backoff's end, and nothing is sent;
  - the sweep's check fails with `login_backoff` ("Storytel couldn't be reached - trying again at HH:MM").
- **What the backoff doesn't cover:** the parent's save probe. A save is the parent's own act.
  - It uses the same classification but touches neither the backoff nor the cooldown.
  - It stores nothing on any non-200, as today.
  - Its wording follows the split: "Storytel refused the login" for a refusal, "Storytel answered with an error - try
    again later" for an outage.
- **Deviation from vibb** (26b §9 gains the row): vibb treats any non-200 login as a refusal and waits an hour.
  - That was right for one device logging in for itself.
  - Here one shared login serves every phone. A Storytel outage of a minute would stop Storytel on every phone for an
    hour.
  - A backoff that doubles still bounds the requests (below).

**The phone-login cap.** Logins a phone's ask causes count in a server-wide rolling hour:
- the bound is `max(6, 2 × ceil(3600 / last_life_s))`, so the normal refresh cadence always fits;
- `last_life_s` falls back to 30 min before the first login;
- past it, `busy` until the oldest login leaves the window.
- The sweep's and the save's logins don't count. They are bounded by the sweep's schedule and the parent.

**A phone's 401: the force.** The phone got a 401 from the asset call and asks with `stale` = the session it used.
- **Under `login_lock` the server logs in again only if all of these hold:**
  - the kept JWT's session equals `stale` (re-checked under the lock);
  - the kept JWT is at least `FORCE_MIN_AGE` old: `min(5 min, life / 2)`, or 10 s while `STORYTEL_BASE` is set
    (§2.6);
  - this phone has caused fewer than `PHONE_FORCES_PER_WINDOW` (3) forced logins in the rolling hour;
  - no cooldown, backoff or cap stands in the way.
- **A force counts against the phone only when it logs in.**
- **When the kept JWT is newer than `stale`** (a concurrent re-login), it is answered: the phone's `used` rule,
  without a login.
- **A declined force never gets the old JWT back** (QA N-1). It gets a wait the phone honours:
  - `refused`, with `retry_after_s` to the cooldown's end, inside a cooldown;
  - otherwise `busy`, with `retry_after_s` to the earliest moment the force would be allowed: the JWT reaching
    `FORCE_MIN_AGE`, the backoff's end, the cap's window freeing a slot, or this phone's oldest force leaving its
    window (at least 1 s).
  - The phone drops the refused JWT and comes back after the wait (§5.1, §5.3).
- **A forced login that fails as an outage keeps the old JWT** (§2.1) for the plain asks of other phones. The phone
  that forced gets `unreachable`.

**The sweep's own 401 follows the same rule** (QA 58-7). `fetch_shelf` forces only while the kept session is the one
that got the 401; a newer one is used as it is. That is Spotify's server rule: "only when the rejected token is still
the kept one."

**The bounds, together:**
- The normal case is one login per JWT lifetime while Storytel is used.
- Phones add at most the cap: 6 an hour with an hour's life, whatever the number of phones, tokens or forces.
- During an outage, logins are 1, 2, 4 ... 30 min apart.
- After a refusal, nothing goes out for an hour.

### 2.4 Retiring `GET /api/devices/music/storytel`, and moving the generation once

- **The route goes in step 3:**
  - the handler and its `StorytelLogin` struct (`S/handlers/music_api.rs:224-291`);
  - the route (`S/device_routes.rs:104-107`);
  - the `storytel_login_sent` event.
- **What an older launcher then gets:** the empty 404 of the fallback (`S/main.rs:705-707`), which it turns into
  "delete yours" (§1).
  - **That empty 404 is the retirement answer, for good.** A test pins it, and the path must never be reused.
  - The route needs no grace period: its only callers are told to delete.
  - The admin label `storytel_login_sent` (`S/handlers/admin.rs:130`) stays, so old rows still read.
- **Migration `0085_storytel_jwt_courier.sql`** (the next free number when it is written; the user's yes, 2026-10-21):

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
    once after a save" doesn't fire. 0072's triggers watch only `ciphertext` and the switch.
  - **No nudge is sent at startup.** An older music app pulls at the launcher's next policy fetch: the policy's
    `storytel_generation` changes, the managed config changes, and the launcher nudges.
  - **The cost:** an older music app loses new Storytel downloads until it is updated. Books on the phone still play.
  - Frozen like every applied migration.

### 2.5 The PWA and the texts

- **The Storytel footer** (`server/templates/music_settings.html:97`):
  - "Use a password you use only for Storytel: each phone keeps it to log in." becomes "The phones never get the
    password: they ask this server for a short Storytel session when they download a book."
  - The ⓘ gains §7.2's remedy: "If a phone is lost, remove it here, then change the password at Storytel and save it
    here again."
  - The Family sentence stays.
- **The phone's Music page** (`S/handlers/music.rs:4372-4380`, `:4145`, `templates/device_music.html:137`), the
  "Storytel login" row (QA 58-8, 58-10). The first that applies wins:
  1. **"Update Vibb One"**: Storytel is on for this phone, and the launcher's capabilities lack `storytel_jwt_v1` (§3,
     `L/server/StatusCapabilities.kt`). Without the courier no book comes.
  2. **"Refused"**, in red: the server's own cooldown runs for the current generation (`storytel::cooldown_until`,
     `S/storytel.rs:594`), or the phone reports `refused`. The warning above the switch keeps the save's split: "Storytel
     refused the login - save the password again" for a refusal.
  3. **"Storytel can't be reached"**: the login backoff runs (§2.3). There is no "wrong password" wording here.
  4. **"Ready"** (was "Logged in"), or **"None"**, from the phone's `ok`/`none`.
- **The server's state is the authority.** The phone's word is in memory only (§5.2), and a fresh process reports `ok`
  until its first ask.
- **DEPLOY.md:517:** "the phones get 503" becomes "a phone's Storytel token ask answers `key`".
- **Notes:** the module doc (`S/storytel.rs:1-4`) and `docs/notes/server/music.md` (Storytel's first bullet and
  "Secrets") say the phones get the server's JWT and mint the audio URLs themselves.

### 2.6 `STORYTEL_BASE`: the emulator chain (the user's yes, with QA 58-11's guard)

`STORYTEL_BASE` in `.env` is Spotify's `Config::from_env` (`S/main.rs:389-398`) for Storytel:
- **Taken only for a loopback URL.** The value is parsed as a URL, and its **host** is compared exactly with
  `127.0.0.1`, `::1` or `localhost`, as Spotify's `Config::from_env` does (QA N-12). `http://127.0.0.1.evil.example`
  is refused. Anything else is ignored with a warning.
- **While it is set:**
  - a warning at every start;
  - the login and the bookshelf go to `<base>/api/login.action` and `<base>/libraries/bookshelf`;
  - those requests use `Reach::Any`. Every other Storytel request stays `Reach::Public`.
- **The fake-login guard.** While the override is set, a login is sent **only for an e-mail ending
  `.example.invalid`**.
  - Any other stored login is `NotConfigured`, logged as "STORYTEL_BASE is set: the stored login isn't a fake one, not
    sent".
  - That covers the save's probe too: a real password never goes to a loopback port, even with a leftover `.env` line
    on the Pi. This is the server twin of music review debug item 8.
- **`FORCE_MIN_AGE` is 10 s while it is set**, so §9's revoke check doesn't wait 5 minutes.
- **The fake grows** a bookshelf (`POST /libraries/bookshelf`, answered from `server/testdata/storytel/bookshelf.json`)
  and the login fixtures (§8).
- **The phone's own asset calls** on the emulator still go through the music app's debug switch.

### 2.7 The lifetime line, the e-mail check, and Q-A (QA 58-4, 58-16)

**At every login the server logs one `info` line.** It is logged in `login_with`, so the save's probe logs it too
(QA N-7):

```
storytel: logged in (life_s=3600, left_at_login_s=3600, claims=yes, payload_has_email=no)
```

- `claims` says whether `exp`/`iat` were read, or the fallback applied.
- `payload_has_email` is a word, never a value or a claim name:
  - **yes** when any string value in the payload, at any depth, equals the e-mail just used (case-insensitive) or
    contains `@`;
  - **no** when the payload decoded to a JSON object and none does;
  - **unknown** when the payload couldn't be decoded: no dots, other base64, not JSON (QA N-5).
- The PWA's Storytel card shows the same facts under its ⓘ: "Session: lives N min; holds an e-mail address:
  yes/no/unknown".
  - They come from the account's memory: the last login's two facts, never a database column.
  - After a restart the card says "not known yet" until the next login.

**Step 1 measures. Its result decides Q-A before step 2 ships.** Step 1's server can go out on its own release:
- **Its new route has no caller yet.**
- **The trigger.** The user saves the Storytel login again in Music › Settings: the probe logs in and prints the
  line. A sweep login prints it too, but only while a Storytel series is ticked.
- **The sample.** The user reads **two** lines at least an hour apart: the save's, then a later sweep or phone login.
  `left_at_login_s` shows whether Storytel hands back a fresh token each time.
  - On the Pi: `journalctl -u kid-phone-server | grep "storytel: logged in"`.
- **Only `payload_has_email=no` clears the gate.** It implies a decoded payload. `unknown` is decided by the user like
  `yes`.

**(a) `payload_has_email=no`, or the user accepts it:**
- Steps 2 and 3 go on as written.
- If accepted with `yes`, §7.2 records that every Storytel phone carries the family's e-mail inside the JWT (never the
  password), and decision 1 gets that exception, written down.

**(b) `yes` or `unknown`, and the user won't accept it: the server mints each book's audio link itself.**

**Not built in this design.** It would be **design 58b**, written only if the measurement below says (b) is possible
and the user picks it (QA N-3). Then step 2 is replaced by 58b's steps. Step 1's token route and `getStorytelToken`
stay but answer `off` to every phone, since AIDL methods are never removed.

The outline below lets the user choose on the evidence. 58b fixes the numbers.

- **The route:** `POST /api/devices/music/storytel/asset` with `{"cid": "<id>"}`.
  - The server makes the asset call with its JWT, without following the 302, with §2.3's 401 rule.
  - It answers `{"status": "ok", "url": "<the signed CDN URL>"}`.
  - The URL is a bearer for one book, as today: never logged or stored.
- **Its limits:**
  - per phone, about 60 mints a rolling hour (a series' downloads and a room's plays, not the token route's 20);
  - an asset call is no login, so §2.3's login backoff, cap and cooldown still bound the logins behind it;
  - an 8 s budget with the spawned task kept, as §2.2.
- **Its answer rows** go into a `storytel_asset_answers.json` contract file, the token file's twin:
  - `ok`;
  - `off`, `refused`, `busy`, `unreachable`, `key`, as §2.2;
  - `no_audio` for the asset call's 403/404/410/451, with the status;
  - `failed` for any other status.
- **The bridge:**
  - `oneway void getStorytelAsset(String cid, IStorytelAssetCallback callback)`, appended;
  - `onAsset(String status, String url, int httpStatus, long waitMs)`;
  - a capability `storytel_asset_v1`;
  - `bridge_contract.json`'s `storytel_asset` with the statuses and a 10 s timeout.
- **The phone:** `StorytelAccount.mint` asks the bridge for the URL instead of making the asset call. Everything after
  the mint is unchanged: the CDN download, Range, a room's play.
- **The room's advance (58-1's case without a JWT on the phone).** There is nothing to prefetch on the phone.
  - **Proposed:** while a room plays a book, the phone mints the **next** book's URL in the background a few minutes
    before the end, keeps it in memory, and the advance uses it if it is still valid.
  - Check 2 gives the URL's validity.
  - If the URL lives less than a book's tail, the advance mints and the server's JWT is kept warm by the prefetch-like
    asks of the room's phone.

**The measurement that decides whether (b) is possible:**
- **Check 1:** does a CDN URL minted from the Pi's IP play on a phone on mobile data, with another IP and carrier NAT?
  - With step 1's server and `STORYTEL_BASE` unset, the user runs `scripts/storytel-mint-probe.py` on the Pi. It is
    new, Python 3 stdlib only, run with `-I`.
  - The script asks for the family's login at a prompt (never stored, never in argv). It logs in, mints one book's
    URL, and prints it **only to the terminal**, as text. There is no QR code: the stdlib has no encoder.
  - On the parent's own phone, with Wi-Fi off, the user opens it, typed or copied there by hand. It grants one book
    and expires. A 200 or 206 with audio means yes. The script never writes a file and never logs.
- **Check 2:** how long the URL stays valid. The script fetches the first byte again every 10 minutes for 2 hours and
  prints only the status.
- **Both answers go into this note.** 58b starts from them.

## 3. The bridge contract (one commit in both apps, step 1)

The AIDL stays byte-identical in `launcher/` and `music/`, with methods appended and never reordered
(`IMusicBridge.aidl:1-4`).

1. **`IMusicBridge.aidl`:**
   - Add the import `import me.vibb.bridge.IStorytelTokenCallback;` after `IStorytelCallback`'s.
   - Reword `getStorytelLogin`'s doc comment. The method itself stays: its transaction code is fixed by its place.

     ```aidl
     /** Retired from design 58 step 3: the launcher then answers generation 0 ("delete yours") whenever
      *  [haveGeneration] isn't the policy's, and never asks the server; the music app no longer calls it after step 2.
      *  Kept for the transaction codes. */
     oneway void getStorytelLogin(long haveGeneration, IStorytelCallback callback);
     ```

   - Append at the end:

     ```aidl
     /** A Storytel session (JWT) of the family's account (design 58 §2.2, §4), through [callback]. [stale] is the
      *  session ("<generation>:<16 hex>") Storytel answered 401 to, else null. The launcher never caches or logs it. */
     oneway void getStorytelToken(String stale, IStorytelTokenCallback callback);
     ```

2. **New `IStorytelTokenCallback.aidl`:**

   ```aidl
   // The Storytel session, answered by the launcher (design 58 §4). Byte-identical in launcher/ and music/.
   package me.vibb.bridge;

   interface IStorytelTokenCallback {
       /** [status]: one of bridge_contract.json's storytel_token.statuses; [jwt] only with "ok", else null;
        *  [expiresInMs] its use left from now with "ok", the wait before asking again with "refused" and "busy" (the
        *  server's retry_after_s), else 0; [session] "<generation>:<16 hex>" with "ok", else null. */
       oneway void onToken(String status, String jwt, long expiresInMs, String session);
   }
   ```

3. **`bridge_contract.json`** (both apps' test resources) gains:
   `"storytel_token": {"statuses": ["ok", "off", "refused", "busy", "unreachable", "key", "launcher"], "timeout_ms":
   10000}`.
4. **A new launcher capability `storytel_jwt_v1`** (`L/server/StatusCapabilities.kt`) tells the server this launcher
   carries the courier (§2.5). Same commit: the server reads it.
5. **The tests that pin it:**
   - `MusicPlanTest`: the four AIDL files byte-identical, 14 methods with `getStorytelToken` last, and
     `storytel_token` checked against `STORYTEL_TOKEN_STATUSES` and `STORYTEL_TOKEN_TIMEOUT_MS`.
   - `every bridge method checks its caller first` counts 14 (`:688`).
   - `BridgeTest`: the 14-method list and `StorytelBridgeToken`'s constants against the contract.
   - `EpisodeArtTest.kt:232-233`: the last three are `wantArt`, `getSpotifyToken`, `getStorytelToken`.
6. **CI:** both workflows already watch both `aidl/` directories and both `bridge_contract.json` copies
   (`.github/workflows/launcher.yml:74`, `music.yml:67`). The new answers file adds paths (§8).
7. **Unchanged:**
   - the policy (`policy_json_keys_snapshot`, `PolicyResponseCompatTest`);
   - `music_state.json` (`storytel` stays `none`/`ok`/`refused`);
   - the restrictions (`storytel_generation` stays);
   - the library and listing snapshots.

## 4. The launcher: the courier

- **`L/music/StorytelTokenCourier.kt`** (new, pure; `SpotifyTokenCourier.kt` copied):
  - Constants: `STORYTEL_TOKEN_TIMEOUT_MS` = 10 000 and `STORYTEL_TOKEN_STATUSES`. A `busy` without a number waits
    30 000; a `refused` without one waits 3 600 000.
  - `data class StorytelTokenAnswer(status, jwt, expiresInMs, session)`. Its `toString` prints `jwt=<redacted>`.
    `LAUNCHER` is the answer when the launcher gets none.
  - `storytelTokenAnswer(httpStatus, body)`:
    - `ok` needs HTTP 200, a `jwt` matching `[\x21-\x7e]{1,8192}`, `expires_in_s` in 1..86 400 and a `session`
      matching `[0-9]{1,18}:[0-9a-f]{16}`. An `ok` missing any of them is `launcher`.
    - `refused` and `busy` carry `retry_after_s` in 1..3600 as ms.
    - Any other word passes through as is.
    - With no word: a 404 is `off` (a server without the route), a 5xx is `unreachable`, anything else `launcher`.
  - `staleBody(stale)` is a hand-built `RequestBody` (`application/json`): `{"stale": ...}` only for a valid session,
    else `{}`. `createMusicServer`'s Retrofit has no converter (`MusicServer.kt:75-78`).
- **`MusicServer.storytelToken(stale)`:** `POST api/devices/music/storytel/token` with that body. `storytel()` goes in
  step 3 (`:31-32`, `:55-56`, `:84`).
- **`MusicBridgeService.getStorytelToken(stale, callback)`:**
  - `check()` comes first.
  - `answerStorytelToken` is `answerSpotify` (`:163-185`) with the new parser.
  - **No cache, no store, no log** of the body, the JWT or the session. Log lines name the exception class only.
- **`getStorytelLogin` (step 3):** `answerStorytel` keeps its `want == have` return. Otherwise it calls
  `callback.onLogin(0, null, null)` and never contacts the server.
- **The capability** `storytel_jwt_v1` goes into `StatusCapabilities` (step 1).
- **The docs:** the class doc (`MusicBridgeService.kt:37-38`) and `launcher/CLAUDE.md:441`, `:447`, beside the Spotify
  courier bullet (`:449`).

## 5. The music app

### 5.1 The JWT source, the asset call, the prefetch (`M/storytel/`)

**What goes:**
- `encryptPassword`, `AES_KEY`/`AES_IV`, `logIn`, `cooldownUntil`, `CooldownStore`;
- `JWT_TTL_MS`, `LOGIN_COOLDOWN_MS`, `LOGIN_TIMEOUT_MS`;
- `Endpoints.login` (`StorytelEndpoints.kt:18-31`).

**What stays:**
- the asset call (its request, `classify`, `NO_AUDIO_STATUSES`, `mintedUrl`, `CID`);
- the `Mint` classes (`StorytelApi.kt:41-64`). `LoginFailed` gains `backAtWallMs: Long?`, the time the download worker
  comes back (§5.3).

**The shape:**
- `StorytelApi(http, elapsedMs, wallMs, endpoints)` keeps only the asset call: `suspend fun assetUrl(cid, jwt,
  timeoutMs, deadlineMs)`. Its HTTP send runs in `runInterruptible(Dispatchers.IO)`, so a cancelled job still ends a
  blocked request at once.
- **`StorytelSession`** (new, pure) is the JWT holder: `TokenCache`'s shape for Storytel.
  - It takes `generation: () -> Long`, `ask: suspend (stale: String?) -> JwtResult`, the clocks, a `CoroutineScope`
    and `sleep`.
  - It holds the kept `Jwt(value, expiresAtMs, generation, session)` (redacted `toString`) and the waits.
- **`StorytelAccount`** holds one `StorytelSession` and one `StorytelApi` for the process.
  - **`mint(cid, timeoutMs, deadlineMs)`** keeps its signature, so `Downloads.kt:207-209` and `SonosRenderer.kt:184`
    don't change. It calls `session.bearer(...)`, then `api.assetUrl(...)`; a plain suspend call, with no
    `runInterruptible` around it.
  - **On a debug rebuild** (`builtFor` changed) the old instance's scope is cancelled.

**The bearer:**
- A JWT is kept while `generation()` equals its generation and `elapsedMs() < expiresAtMs`. The server already took its
  margin off.
- Generation 0 is `NotConfigured`, with no ask; it drops the JWT and clears `offUntil`.

**One ask at a time; a late answer is kept** (QA 58-6):
- The ask runs in the session's own scope as one shared `Deferred`, always with its full
  `StorytelBridgeToken.TIMEOUT_MS` (10 s). Its answer is kept whoever still waits.
- Only the **waiter** waits `min(10 s, deadline - now - timeoutMs)`. That reserves the asset call's own time: a room's
  `(6 s, +10 s)` waits up to 4 s, a download's `(15 s, +60 s)` 10 s.
- Below 1 s it is `LoginFailed("deadline")`, and no new ask starts.

**The prompt retry** (QA 58-2; `TokenCache`'s rule, 26c §5.5):
- An `Unreachable` answer is asked once more `PROMPT_RETRY_MS` (5 s) later, inside the same mint, when that still fits
  the waiter's deadline.
- A download's 60 s always fits. A room's mostly doesn't; the prefetch covers the room.

**The prefetch while a room plays a book** (QA 58-1):
- **The trigger.** While a Sonos room shows playing (`showsPlaying`) an item of a Storytel entry, `SonosRenderer`'s
  poll tick (on main) calls `StorytelAccount.prefetchIfDue()`, launched on IO. It never blocks the actor.
- **When it is due:**
  - the kept JWT has less than `PREFETCH_LEFT` left, `min(10 min, its life on the phone / 3)`, and hasn't expired;
  - or there is none;
  - and no ask is in flight, and no wait runs (`holdUntil`, `busyUntil`, `refusedUntil`, `offUntil`, `keyUntil`);
  - and the prefetch's own backoff has passed (below).
- **A short life is never prefetched** (QA N-6).
  - The arithmetic needs `(L - 60 s) / 3 + 60 s < L / 2`, so only a JWT whose life on the phone `L` (at receipt) is
    above 240 s is prefetched.
  - Below that, the advance asks as any mint does. The server never hands out less than 61 s (§2.1).
- **The prefetch's backoff** (QA N-10): after a failed or "same back" prefetch the next one waits 2, then 4, then 8
  minutes (`PREFETCH_RETRY_MS`, doubling, at most 8). A new JWT resets it.
  - So a room playing through an outage spends at most about 10 asks an hour on prefetches, inside the server's 20.
  - The server's `busy` and the phone's `holdUntil` stop it earlier.
- **"The same JWT back"** is told by the JWT's value, not by the session or the expiry. It is no refresh and counts as
  a failed prefetch. §2.1's `PHONE_MIN_LEFT` makes it rare.
- **At a room start:** when the service sends a Storytel play to a room, it calls `prefetchIfDue()` before the actor
  job is queued. An in-flight ask is shared with the actor's mint, and a late answer is kept, so a second tap is
  quick.
- **The effect:** at a book's natural end on a room, the advance's mint finds a JWT in memory and makes no ask. The 4 s
  ask is the rare path: the first play of a process on a cold server.
- **The room's budget is unchanged.** Lengthening it would move `HANDOFF_MS`, `SonosTail.MAX_MS` and
  `SWITCH_WAIT_MAX_MS` (`music/CLAUDE.md`, "Storytel on a room").

**The 401 (Storytel's rule, kept):**
1. The asset call answers 401.
2. The phone asks once with `stale` = the JWT's session.
3. A JWT with a **different value** back is retried once.
4. **A declined force** answers `busy` or `refused` with a wait (§2.3, QA N-1).
   - The 401'd JWT is dropped. The mint is `LoginFailed("busy", wall + wait)` or `Refused(wall + wait)`, and the
     waits of the table below apply.
   - The download worker comes back at the wait's end (§5.3).
5. **The same value back** should no longer happen, since the server never answers a force with the JWT it names. It
   stays a belt: `Failed(401)` at once, with no second request, the JWT dropped, and the worker back in 2 minutes
   (§5.3).
6. A second 401 on the new JWT drops it (`:118-120`). `mintStopsRun` stops the run (`M/logic/Storytel.kt:115-116`).
   The worker comes back in 2 minutes, as in step 5.

**What the ask's answers become:**

| `JwtResult` | `Mint` | Kept in memory |
|---|---|---|
| `Ok` | the asset call | the JWT |
| `Off` | `NotConfigured` | `offUntil` = 2 min; cleared when the generation reads 0 or changes |
| `Refused(wait)` | `Refused(wall + wait)` | `refusedUntil` = now + wait, for this generation |
| `Busy(wait)` | `LoginFailed("busy", wall + wait)` | `busyUntil` = now + wait |
| `Unreachable("key")` | `LoginFailed("key", wall + 10 min)` | `keyUntil` = 10 min |
| `Unreachable(other)`, after the prompt retry | `LoginFailed("unreachable", wall + 2 min)` | `holdUntil` = 30 s: no new ask, so a kid's taps don't burn the 20-ask budget during an outage (QA 58-3) |

Inside a wait there is no ask, and `mint` answers the same `Mint`. A generation change clears every wait and the JWT.

### 5.2 Generation, nudge, cooldown: re-checked

- **The generation:**
  - It is read live at every mint and every `state()`: `Bridge.config(context).storytelGeneration` while managed
    (`M/bridge/BridgeConfig.kt:91`, `:115`).
  - A parent's save or clear moves it, so the phone drops its JWT and every wait at its next mint.
  - The phone tags a JWT with the generation it believed at the ask. Until the policy catches up, one extra ask hits
    the server's cache.
- **The nudge:** `BridgeSync.pullStorytel` goes (`M/bridge/BridgeSync.kt:41`, `:96-109`). `BRIDGE_CHANGED` and the
  restrictions are unchanged, and `storytel_generation` stays the phone's on/off and "new login" signal.
- **`storytel_refused.json` is no longer needed:**
  - The server's cooldown and backoff stop every Storytel request (§2.3).
  - The per-phone limit stops a phone that asks in a loop.
  - The phone's waits in memory save the bridge round trips within a process.
  - A new process asks once and is told again, at no cost to Storytel.
  - The file is deleted (§5.5).
- **`state()`:**
  - `none` while the generation is 0 or `offUntil` runs;
  - `refused` while `refusedUntil` runs;
  - else `ok`.
  - `storytelWaiting` and the storage floor read it unchanged. The PWA ORs in the server's own state (§2.5).

### 5.3 Offline, outages, and the download worker coming back (QA 58-2)

- **Downloaded books play** from their copy with no request, as today. No JWT is involved.
- **A download** waits for its network before it mints (`downloadWaiting` precedes `mint`).
- **When an ask fails after the prompt retry:**
  - `LoginFailed` stops the run with no item error, as today.
  - **The worker now comes back on its own.** `DownloadRun.storytelRefusedUntil` (`DownloadRun.kt:85`, `:139`)
    becomes `storytelBackAt`. It is set from:
    - a `Refused`'s end;
    - a `LoginFailed`'s `backAtWallMs`;
    - **a 401 stop** (`Failed(401)`, §5.1 steps 5-6): now + 2 min (QA N-1).
  - `Downloads.kt:172-174` re-enqueues at the earliest of it and `nextRetryAt`: 2 min after `unreachable` or a 401
    stop, the server's wait after `busy` or a declined force, 10 min after `key`.
  - Nothing waits for the 6 h backstop.
- **A room** needs Wi-Fi anyway.
  - A failed mint is the room's failed play, `login_failed` (`sonosMintCode`, `M/logic/Storytel.kt:125-131`).
  - With the prefetch (§5.1) an advance rarely asks at all.
- **No bookmark outbox exists** (§1). A future position mirror would get its JWT from the same `StorytelSession` and
  keep its outbox until an `ok`.

### 5.4 The bridge call (`M/bridge/StorytelBridgeToken.kt`, new; `SpotifyBridgeToken.kt` copied)

- `sealed interface JwtResult { Ok(jwt, expiresAtMs, session), Off, Refused(retryAfterMs), Busy(retryAfterMs),
  Unreachable(word) }`. `Ok.toString()` prints `jwt=<redacted>`.
- `jwtResult(status, jwt, expiresInMs, session, nowMs)` (pure):
  - `ok` needs a non-empty JWT, a life above 0 and a valid session. A missing part is `Unreachable("launcher")`.
  - `refused` and `busy` take their wait (default 1 h and 30 s).
  - Every other word is `Unreachable(word)`.
- `StorytelBridgeToken.ask(bridge, stale)`: one `getStorytelToken`, a `CompletableDeferred`, and
  `withTimeoutOrNull(TIMEOUT_MS)`.
  - An older launcher drops the unknown oneway call. That is a timeout, `Unreachable("launcher")`.
  - `TIMEOUT_MS` and `STATUSES` are checked against `bridge_contract.json`.

### 5.5 The old login goes (`M/bridge/StorytelLegacy.kt`, replacing `StorytelLogin.kt`; QA 58-15)

- **`StorytelLegacy.purge(context)`** deletes:
  - `noBackupFilesDir/storytel.bin`, `storytel.bin.tmp`, `storytel_refused.json` and `storytel_refused.json.tmp`;
  - the Keystore entry `vibb_storytel` (`containsAlias` then `deleteEntry`).
- **When it runs:** from `Sync.writeReport` behind a process-wide `AtomicBoolean`, on the sync's IO.
  - Every sync reaches it, the update's own sync too (`PackageReplacedReceiver`).
  - **Never on the mint path:** not in `StorytelAccount.get`/`build`, whose first call can be a room's 10 s mint. A
    debug rebuild doesn't run it again.
- **Failures:** never thrown, never logged with a path's content. A debug build logs one fixed line: "storytel: the old
  login deleted".
- **`AndroidKeyStore` and `storytel.bin`** appear in `main` only in this file (`SourceScanTest`).

### 5.6 The debug switch and the fake

- **The fifth reader** in `StorytelEndpoints.kt` is `debugJwt()`: the fake's `GET /fake/jwt`.
  - It is taken only while the app is unmanaged. A managed phone's JWT always comes through the bridge, as Spotify's
    `debugTokens()`.
  - The generation is then the constant `DEBUG_GENERATION` 1.
  - Its JSON is read by `jwtAnswer`, the route's shape (§8's shared answers file).
- **`debugSwitchJwt`:** with the switch set, only a JWT starting `FAKE-JWT-` goes on, whichever path it came by (the
  bridge too). Anything else is `Unreachable("launcher")`.
  - This is Spotify's `debugSwitchToken` (`M/logic/Spotify.kt:74`).
  - It closes the music review's debug item 8 (first bullet).
- **`DebugStorytel.seedLogin`** goes (`M/debug/.../DebugStorytel.kt:44-60`). A `storytel.json` still in the feed is
  deleted unread, with one fixed log line.

## 6. Migration and update order

**Release in this order:** server (step 1 may go alone, for §2.7's line; steps 1 and 3 together otherwise), then
launcher, then music app. Every other order fails safe: no new Storytel books, the downloaded ones still play, and
never a login on a phone.

| Server | Launcher | Music app | What happens |
|---|---|---|---|
| new | old | old | The generation moved (§2.4). The old app asks for the login, the old launcher gets the empty 404 and answers 0, and the app deletes `storytel.bin`. Storytel stops on this phone until the app is updated. Device › Music: "Update Vibb One" (§2.5). |
| new | new | old | The same through the new launcher's immediate 0. The row says "None", from the old app's `none`: accepted (QA N-11, below). |
| new | old | new | The old launcher drops the app's `getStorytelToken`: a 10 s timeout, `LoginFailed("unreachable")`, the worker back every 2 min. **The report says `ok`, and `waiting` gives no `login` wait** (QA 58-10). The parent sees "Update Vibb One", from the missing `storytel_jwt_v1`. |
| new | new | new | Works. |
| old | new | new | The launcher's POST meets an empty 404, which is `off`. The app reports `none`, and its old login is already purged. No new books come until the server is updated. |
| old | new | old | Equal generations: the old app keeps its stored login and works. On the first parent save after that, the launcher answers 0 and the app deletes it. |
| old | old | new | Like row 3, without the PWA row (an old server). |

- **Row 2's "None" is accepted** (QA N-11).
  - The old app reports `none` honestly, and Device › Music shows the music app's version beside it.
  - The case lasts only from the server and launcher update until the music app's.
  - An "Update Vibb Music" hint would need a `music_state` contract change for a gap of days.
- **The old route needs no grace period:** its only callers (older launchers) are told "delete yours" by the fallback.
- **The `getStorytelLogin` AIDL method and `IStorytelCallback.aidl` stay forever** (transaction codes).
- **No `server-v*` tag goes out between steps 1 and 3 unless step 1 releases alone** for §2.7's measurement. Then step
  3 bumps the version again.
- **DEPLOY.md** gets the order line under Storytel, next to "Order" (`server/DEPLOY.md:505-509`).

## 7. Privacy and security

### 7.1 Secrets

- **The JWT is a bearer** for the family's whole Storytel account.
  - Server: `Jwt`, `KeptJwt` and `PhoneJwt` have no `Debug` or `Display`, with a probe test. Not in the database, a
    log, an event, an error text or `music_state`. The payload is decoded into the life and one boolean, then dropped.
  - Launcher: in memory for one call. `StorytelTokenAnswer.toString` is redacted.
  - Music app: in memory only. Never in Room, `renderer.json`, the report, a file or a log.
    - `Jwt`, `JwtResult.Ok`, `StorytelRequest`/`Response` and `Mint.Url` keep or get redacted `toString`s
      (`SourceScanTest`).
    - `Authorization`/`Bearer` stay only in `StorytelApi.kt` and `SpotifyApi.kt`.
    - There is no `Log.` in `storytel/`.
- **The minted CDN URLs are unchanged:** per download attempt and per room play, used once, never stored, never
  followed.
- **The session string** is random per login and names the JWT only. It is not logged.
- **The e-mail may sit inside the JWT** (§2.7). Step 1 measures that, and Q-A decides it before step 2 ships. Until
  then this list is true only for the password.

### 7.2 What a stolen or rooted phone gets (QA 58-17)

- **Before this design:** the family's e-mail and password, which are reusable elsewhere, for good, readable from
  `storytel.bin` with root, from the bridge and from the route with the device token.
- **After it:**
  - The JWT in the music app's memory. That is **the same shared JWT the family's phones and the server use**, valid
    until its `exp`.
  - With the device token, while the phone stays enrolled: the then-current shared JWT, asked up to 20 times an hour.
    The asks give no new logins beyond §2.3's cap.
  - Removing the phone in the PWA (its token dies), or switching its Storytel off (`off` at once), stops further JWTs.
    **Neither rotates the one it holds.**
  - With branch (a) and `payload_has_email=yes`: the family's e-mail too, inside the JWT.
- **The remedy** (the PWA's ⓘ, §2.5): remove the phone, change the password at Storytel, and save the new one here.
  - The save moves the generation, and the server logs in with the new password.
  - Whether Storytel then revokes the old JWT is §12's open check. If it doesn't, the old JWT lives until its `exp`:
    at most one life (`JWT_LIFE_CAP` bounds our reuse, not Storytel's validity).

### 7.3 Rate limits

- **Per phone:** `PHONE_JWT_ASKS_PER_WINDOW` = 20 asks a rolling hour, then `busy` with the wait.
  - Counted after `off`, before the key check (§2.2).
  - Spotify's `Accounts::phone_ask` (`S/spotify.rs:1228-1250`) is lifted unchanged into `music_net::PhoneAsks`, and
    both routes use one each.
- **Logins** (§2.3):
  - one per JWT lifetime while Storytel is used;
  - the phone-caused ones server-wide at most `max(6, 2 × ceil(3600 / life))` a rolling hour, whatever the number of
    phones, tokens or forces;
  - none during the backoff or the cooldown.
  - **That cap is the whole bound for a hostile or buggy phone.** It replaces the first draft's "3 an hour" claim.
- **On the phone:**
  - `holdUntil` (30 s after an `unreachable`) and the waits keep a kid's taps and a looping job off the 20 asks;
  - the prefetch asks about once per JWT lifetime while a room plays; failed ones back off 2, 4, 8 minutes, so a room
    in an outage stays under about 10 asks an hour (§5.1, QA N-10).
- **Other limits that still apply:** the device API's bucket (`S/limits.rs:25-26`) and the 256-byte body limit.

### 7.4 The reviews

- **Server review** (`security-review-2026-10-21-server.md`):
  - M3 becomes **Fixed (design 58)**, with the commit, in its section and the status table (`:309` row style).
  - The data table's "every Storytel-on phone in plaintext" (`:411`) becomes "the phones get the server's JWT (memory
    only)". If Q-A ends at (a) with `yes`: "and the e-mail inside it".
  - Lines `:387`, `:441` and `:450` follow.
  - I11 is fixed by the route's removal.
- **Music review** (`security-review-2026-10-21-music.md`):
  - Privacy 2 (`:183-186`) is fixed.
  - The data table's "Storytel login" row (`:252`) goes, and "Secrets at rest" (`:232`) drops `storytel.bin` and
    `storytel_refused.json`.
  - Debug item 8's first bullet is fixed (§5.6).
  - The `toString` list (`:214`) drops `Login`.

## 8. Tests and fixtures

**Fake values only:**
- JWTs are `FAKE-JWT-<n>`, sessions `"1:00000000000000<nn>"`, e-mails `*.example.invalid`.
- A JWT whose claims a test must read is **built at run time**, so no `eyJ` literal is ever committed:
  `"FAKE-JWT-HEAD." + base64url({...}) + ".FAKE-SIG"`.
- `BridgeTest`'s seal test with `ella@example.org` goes with `StorytelLogin`.

**The shared answers file (QA 58-13):** `server/testdata/storytel_token_answers.json` holds one object per §2.2 row,
fake values only.
- It is byte-identical with `launcher/app/src/test/resources/storytel_token_answers.json` and
  `music/app/src/test/resources/storytel_token_answers.json`.
- Who reads it:
  - the server's route test produces each row and compares its keys and types;
  - the launcher's `StorytelTokenCourierTest` reads each;
  - the music app's debug parser test reads each;
  - `fake-storytel.py` answers `/fake/jwt` from it;
  - all three sides compare the copies.
- CI paths:
  - `launcher.yml` adds `server/testdata/storytel_token_answers.json` and music's copy;
  - `music.yml` adds the server's and the launcher's copies, and `server/testdata/storytel/`;
  - `server-ci.yml` adds both app copies;
  - the root `CLAUDE.md`'s shared-files and CI paragraphs gain it.

**Server:**

- `storytel::tests`:
  - `jwt_life_from_claims`: `min(exp - iat, exp - now)` with a sane clock, `exp - iat` without, the floors, the cap,
    the fallback;
  - `payload_has_email`: yes for the e-mail used, yes for any `@`, no for a decoded payload without either, unknown for
    an undecodable one; the captured line holds neither a value nor a claim name, and the save's probe logs it too;
  - `classify_login`: a 4xx other than 408/429 is a refusal; 5xx, 408, 429, 1xx, 204, an unfollowed 3xx, transport,
    timeout, `no_jwt` and a too-short life are outages; the probe touches no backoff or cooldown;
  - `a_kept_jwt_needs_no_open`: the fast path reads the plain columns only (a key that can't open the row still
    answers a kept JWT; no `login_error` write);
  - `a_kept_jwt_is_handed_out_during_a_backoff`: above 120 s left it is answered, below it `busy`;
  - `a_short_fresh_life_is_an_outage`: a login leaving under 120 s answers `unreachable` and starts the backoff;
  - `a_declined_force_answers_a_wait`: a too-young JWT means `busy` to its `FORCE_MIN_AGE`; a spent force budget
    means `busy` to the window; inside a cooldown, `refused`; never the named JWT;
  - `a_kept_jwt_is_answered_while_a_shelf_read_waits`;
  - `a_force_relogs_only_for_the_stale_kept_old_jwt`: the session re-checked under the lock, a newer kept JWT answered
    without a login, a force that fails as an outage keeping the JWT for the others;
  - `the_sweeps_401_follows_the_kept_session`: two 401s on one JWT make one login;
  - `a_failed_login_backs_off_for_every_phone`: no second login inside the backoff, `busy` with its wait, doubling,
    reset on success and on a new generation;
  - `a_5xx_login_is_an_outage_not_a_refusal`: the backoff, no cooldown stamp, `login_error = http_503`;
  - `phone_logins_are_capped_server_wide`;
  - `sessions_are_unique_across_an_account_rebuild`;
  - `the_life_fits_the_launchers_bound`: `JWT_LIFE_CAP - MARGIN <= 86 400`;
  - `the_override_sends_only_a_fake_login`: a real e-mail with `STORYTEL_BASE` set sends nothing; `Reach::Any` only
    with the override; the host compared exactly (`http://127.0.0.1.evil.example` refused);
  - `secret_types_have_no_debug` gains `KeptJwt` and `PhoneJwt`.
- `src/tests/music_storytel.rs`:
  - each row of `storytel_token_answers.json` produced and compared;
  - the check order: `off` and `key` by fingerprint, with no `login_error` write per ask;
  - the spawned login kept after the 8 s budget;
  - the per-phone limit;
  - the stale body (valid, junk, too long, no content type);
  - no JWT in a captured log line, `security_events` or the database;
  - the retired route: a bearer GET is a bodiless 404;
  - the migration: the generation +1, a running cooldown kept, `saved_at` unchanged, the policy moved;
  - the PWA row's four cases (§2.5), the capability included.
- `the_storytel_login_is_write_only_sealed_and_generational` (`S/tests/music.rs:3669`) loses its GET and
  `storytel_login_sent` parts (step 3).
- `policy_json_keys_snapshot` is unchanged.
- `fake_login_fixtures_match` (QA N-14) reads `aes_vectors.json`, checks `encrypt_password` on every vector in it, and
  checks that the copied login request's `pwd` hex is `encrypt_password(<its password>)`. That is what lets the
  stdlib fake compare a hex it can't compute. `password_vectors` (`S/storytel.rs:1126-1145`) keeps its inline
  vectors.

**Launcher:**
- `StorytelTokenCourierTest`: the shared answers, an incomplete `ok` as `launcher`, the waits' bounds and defaults, a
  404 with no word as `off`, a 5xx as `unreachable`, the redacted `toString`, `staleBody`.
- `MusicPlanTest`: the contract (§3.5), the three answers copies equal, the capability. A source scan that
  `answerStorytel` never calls `createMusicServer` and `MusicServer` has no `storytel()` (step 3).

**Music app:**
- `StorytelSessionTest` (new):
  - one shared ask;
  - a 6 s answer to a 4 s waiter kept for the next mint;
  - the prompt retry (`unreachable` then `ok` 5 s later gives one JWT, once only);
  - the prefetch: due at `PREFETCH_LEFT` while a room plays, not otherwise, not twice, never for a life of 240 s or
    less, not inside `holdUntil` or a wait, backing off 2, 4, 8 minutes after a failure or the same value back, and
    reset by a new JWT;
  - an advance's mint makes no ask after a prefetch;
  - each row of §5.1's table, with no ask inside a wait;
  - `holdUntil`;
  - a generation change and generation 0;
  - the 401's cases: a different value (one retry), a declined force's `busy`/`refused` (the JWT dropped, the wait
    kept), the same value (`Failed(401)`).
- `StorytelApiTest` (the asset call): the 302 captured and never followed, the status classes, the deadline, a
  cancelled call ending at once, the debug base for `10.0.2.2` and loopback only.
- `StorytelBridgeTokenTest`: `jwtResult`, the contract's statuses and timeout.
- `StorytelDownloadTest`:
  - the `FileCooldownStore` test (`:415-424`) goes;
  - **a run stopped on `unreachable` or `busy` re-enqueues at `storytelBackAt`**.
- `StorytelLegacyTest`: the file part on a temp dir; the alias delete through a seam; the `AtomicBoolean`.
- `BridgeTest` and `EpisodeArtTest`: §3.5.
- `SourceScanTest`:
  - the redacted list (`StorytelLogin.kt` out; `StorytelBridgeToken.kt` `Ok(` and `StorytelSession.kt` `class Jwt(`
    in);
  - the readers (`debugJwt` to `StorytelAccount.kt`; one declaration and five reads);
  - `seedLogin` (`:620`) becomes "`storytel.json` is deleted unread";
  - `AndroidKeyStore` and `storytel.bin` only in `StorytelLegacy.kt`;
  - `StorytelLegacy.purge` called only from `Sync.kt`.

**Mutation targets:**
- the prefetch, the prompt retry, the worker's come-back;
- the server's login backoff, the cap, the force's session re-check, `FORCE_MIN_AGE`, `PHONE_MIN_LEFT`;
- the outage/refusal split;
- `payload_has_email`;
- the `FAKE-JWT-` filter on the bridge path;
- the override's fake-login guard;
- the purge's `AtomicBoolean`;
- the declined force's wait (QA N-1) and the 401 stop in `storytelBackAt`;
- the fast path's plain-column read (QA N-8);
- the kept JWT through a backoff (QA N-9);
- `payload_has_email=unknown` (QA N-5);
- the prefetch's 240 s guard and its backoff (QA N-6, N-10).

**Fixtures and the fake** (`scripts/fake-storytel.py`, `--selftest`, CI `music.yml`; QA 58-12):

- **The login fixtures are copied in step 1, and the originals are deleted in step 2** (QA N-2). Their new names
  don't collide with the server's existing `login_ok.json` (a bare body):
  - `server/testdata/storytel/fake_login_request.json` (the e-mail, the password, the `pwd` hex);
  - `fake_login_answers.json` (`ok` and `no_jwt`, each `{status, body}`);
  - `aes_vectors.json`.
- **The originals stay until step 2:** `login_request.json`, `login_ok.json`, `login_no_jwt.json` and `aes_vectors.json`
  in `music/app/src/test/resources/storytel/`.
  - `StorytelApiTest.kt:39`, `:53`, `:89`, `:202` read them while the music app still logs in itself.
  - Step 2 deletes them with the login code and those tests.
- **The fake switches to the server copies in step 1.** Its `login.action` serves both readers from the same values:
  the server in the chain, and the old music app on §6i A until step 2.
- **The fake's login** (for §2.6's chain):
  - `login.action` accepts only that request, answering `FAKE-JWT-<n>`;
  - `--refuse` answers it 403;
  - `--no-jwt` answers 200 without a JWT;
  - `--login-5xx` answers 503 (Q-B's outage).
- **The fake's `/fake/jwt`** (the phone alone, from `storytel_token_answers.json`):
  - `ok` with the current `FAKE-JWT-<n>`, and `--jwt-ttl S` for `expires_in_s` (default 3540);
  - `--refuse` also answers it `refused` (409);
  - `--off`, `--busy S`, `--jwt-down` (502).
- **`--revoke-after K`:** after the K-th accepted asset call, the current JWT answers 401 until a new one is issued.
  - With `--revoke-after 1` that is the second book.
  - The next `/fake/jwt` with `stale` naming it, or the next `login.action` (the chain), issues `FAKE-JWT-<n+1>`,
    logged as "jwt #2 (stale ...)".
  - The asset route accepts only the current JWT.
- **The selftest** covers each option against the fixtures and the answers file.

## 9. Emulator and device checks

**`docs/testing/emulator.md` §6i A (the phone alone, on the debug feed):**
- `storytel.json` and the "push it again" lines go. `config.json`'s `storytel_base` gives the JWT and the CDN.
- **Unchanged checks:** the reading order, the bookmark's book first, the drop and resume, `--locked`, the 10-minute
  limit, copy only, and offline. Each run asks `/fake/jwt` once per process, not once per book.
- **A refused session:** `--refuse`.
  - The report says `refused`, and the entry's `waiting` is `login`.
  - One `jwt` line, and none more in that process within the wait.
  - The worker comes back at the wait's end.
- **An outage:** `--jwt-down`. Then:
  - one `jwt` line and a second 5 s later (the prompt retry);
  - the run stops;
  - restart the fake without the flag: about 2 minutes later the worker comes back and downloads, with no sync
    nudged.
- **A revoked JWT:** `--revoke-after 1 --books 111:5,222:5`.
  - A 401 on book 222, one `jwt #2 (stale ...)` line, the same asset retried once with `FAKE-JWT-2`.
  - The book completes.
- **A room's advance on an expired JWT (58-1):** `--jwt-ttl 120` and §6h's fake speakers, a series on Stua with
  books 1 and 2 of 3 minutes.
  - While book 1 plays, `jwt` lines arrive about every 80 s (the prefetch).
  - At book 1's end the advance's `asset` line has **no** `jwt` line just before it, and book 2 plays.
- **The old login goes:** install the previous debug music app, seed a login as today's §6i, install the new one and
  sync.
  - `run-as ... ls no_backup` names no `storytel.bin` or `storytel_refused.json`.
  - Logcat has "storytel: the old login deleted".
- **No secrets:** the two greps unchanged; they also find no `jwt` file.

**§6i B, the chain (§2.6):**
- **Set-up:**
  - the server's `.env` has `STORYTEL_BASE=http://127.0.0.1:8730` (the start logs the warning);
  - the PWA saves `k@example.invalid` (the probe hits the fake's login and bookshelf), ticks the series and turns on
    the phone's Storytel;
  - the debug launcher and the managed music app run, as §6l A1.
- **What to check:**
  - The fake logs logins only from the server: one at the save, none per book. The server's §2.7 line reads
    `claims=no` (no dots in `FAKE-JWT-1`) and `payload_has_email=no`.
  - A second phone (or `pm clear` and a re-enrol) gets the same JWT with no new login.
  - `--revoke-after 1`: the server's force logs in once after `FORCE_MIN_AGE`'s 10 s, and the run continues.
  - **A declined force (QA N-1):** `--revoke-after 1` within 10 s of the save's login.
    - The phone's force gets `busy` with the few seconds left, not the old JWT.
    - The download job comes back after the wait, the force logs in, and the book completes.
    - There is no sync nudge in between.
  - `--login-5xx` after a revoke: no second login within a minute; the PWA says "Storytel can't be reached".
  - **The guard:** save a non-`.example.invalid` e-mail with the override set. The fake logs nothing, and the server
    logs "the stored login isn't a fake one".

**Device checks (Jelly Star, the family's account; QA 58-14):**
- **§2.7's line**, read before step 2 ships: `life_s`, `left_at_login_s`, `claims`, `payload_has_email`.
- **Branch (b)'s two checks**, if needed (§2.7).
- **A 300 MB book downloads with the server's JWT over mobile data** (Wi-Fi off), not only at home: the JWT was issued
  to the Pi's IP and is used from the carrier's.
- **A room play** of a book not on the phone, cold (the server restarted) and warm. Then a series on a room through a
  book's end, with the advance playing.
- **A room play while the parent's own Storytel app is open and logs in.** A login elsewhere must not revoke ours
  (§12).
- **After the update,** `storytel.bin` is gone.

## 10. Steps (each one implementer, one sitting, its tests, the suites green)

1. **The contract commit** (`server/`, `launcher/`, `music/` in one commit):
   - Server: §2.1, §2.2, §2.3, `PhoneAsks` (§7.3) and §2.7's line and card facts. The old route stays.
   - Both apps: §3, with the capability.
   - Launcher: §4's courier and `MusicServer.storytelToken`.
   - Music app: §5.4's call and its result. Nothing mints with it yet.
   - The shared answers file and its CI paths.
   - The login fixtures **copied** to `server/testdata/storytel/` (§8), the fake reading them, and
     `fake_login_fixtures_match`. The music originals stay.
   - Every test of §3.5, §8's server and launcher parts. Server version 0.34.0.
   - **This server may release on its own** (§6). §2.7's line then gates step 2.
2. **The music app on the JWT** (`music/` and the fake): §5.1-§5.3, §5.5, §5.6, the fake's `/fake/jwt`, §6i A and
   the music tests of §8.
   - It deletes the music app's login fixtures with the code and tests that read them.
   - **Built after Q-A is answered.**
   - With (b), this step is replaced by design 58b (§2.7).
3. **The retirement** (`server/` and `launcher/`, one commit):
   - Server: the old route, its event and `StorytelLogin`; the migration (§2.4); the PWA rows (§2.5).
   - Launcher: `getStorytelLogin`'s 0; `MusicServer.storytel()` removed.
   - Their tests. Server version bump, again if step 1 went out alone.
4. **The chain:** §2.6's `STORYTEL_BASE` with its guard, the fake's bookshelf, §6i B.
   - It can go with step 1: the fixtures are already copied there and nothing of the music app's is moved.
5. **Docs:**
   - `music/CLAUDE.md`: `:66-70`, `:110-114`, `:189-191`, `:235-245`, `:258-263`, `:264-271`, and "Storytel on a
     room": the prefetch.
   - `launcher/CLAUDE.md:441`, `:447`; `server/CLAUDE.md:226`; `docs/notes/server/music.md`'s Storytel section;
     DEPLOY.md (§2.5, §6, `STORYTEL_BASE`).
   - 26b §9 gains the outage row (§2.3).
   - The root `CLAUDE.md` (the shared answers file).
   - The reviews (§7.4).
   - PLAN.md: the backlog entry, and its "small, any time" lifetime item done.
6. **Code QA, fix all findings,** §9's emulator checks, then the Jelly Star.

**Release:** server, then launcher, then music app (§6).

## 11. Decisions and the remaining question

**Decided by the user (2026-10-21):**
- **Move the generation:** yes (§2.4).
- **The lifetime line with `payload_has_email`:** yes, and it gates step 2 (§2.7).
- **`STORYTEL_BASE` with the fake-login guard:** yes (§2.6).
- **QA Q-B:** an outage, not a refusal (§2.3).

**Open: QA Q-A.** If `payload_has_email=yes`, accept the e-mail inside the JWT (a), or have the server mint each
book's link (b)?
- Decided after step 1's line.
- (b) needs its two checks first (§2.7): a Pi-minted link playing on a phone on mobile data, and how long the link
  lives. It would then be designed as 58b (§2.7's outline).

## 12. Not verified here

- The real JWT's claims, its life and whether it holds the e-mail. Step 1's line measures them.
- **Whether Storytel binds a JWT or a minted link to an IP.** This is §9's mobile-data check and (b)'s check 1.
- **Whether Storytel accepts one JWT from several devices at once,** or revokes older JWTs at a new login (the
  parent's own app, §9). Today each phone logs in by itself, so a shared JWT is new usage.
- Whether a Storytel password change revokes issued JWTs (§7.2's remedy).
- Which status codes Storytel's `login.action` really sends for an outage versus a refusal. §2.3's split follows HTTP;
  vibb saw only 200 and refusals in the field.
- The ask's 4 s on a room against a cold server login over tsnet on the real phone (§5.1; the prefetch makes it rare).
- That `AndroidKeyStore.deleteEntry` of `vibb_storytel` succeeds on the Jelly Star's Android build. A failure leaves an
  unused key, never a login.

## Revision for qa-58-design (2026-10-21)

Every finding of `docs/review/qa-58-design.md` and how it is fixed.

| Finding | Fix |
|---|---|
| **58-1 (M)** room advance after a cold login; "no regression" was wrong | §1: today's login gets up to the whole 10 s (`StorytelApi.kt:132`, `:193`). §5.1: the prefetch while a room plays a book (`PREFETCH_LEFT` = min(10 min, life/3), 2 min retries, "the same back" by value) and at a room start. §2.1: `PHONE_MIN_LEFT` = min(15 min, life/2), so a prefetch gets a new JWT. Tests, a mutation target, §9's emulator check of an advance. |
| **58-2 (M)** downloads stop until the next sync after `unreachable`/`busy` | §5.1: one prompt retry after 5 s inside the mint. §5.3: `LoginFailed.backAtWallMs` and `DownloadRun.storytelBackAt`, so the worker re-enqueues at 2 min, `busy`'s wait or 10 min for `key`. `StorytelDownloadTest`, §9's outage check. |
| **58-3 (M)** a phone could cause 20 logins an hour; nothing bounded failed logins | §2.3: a server login backoff (1 min doubling to 30 min) for every outage, and a server-wide cap on phone-caused logins of max(6, 2 × logins per hour of life). `busy` with the wait inside both. §5.1: the phone's 30 s `holdUntil` after `unreachable`. §7.3's bound corrected. Tests. |
| **58-4 (M)** the e-mail may ride inside the JWT, found only after release | §2.7: `payload_has_email` (a boolean only) and `life_s`/`left_at_login_s` in one `info` line per login, plus the card. Step 1 may release alone; the line gates step 2. Q-A's two branches written out, with (b)'s two checks and `storytel-mint-probe.py`. §0, §7.1, §7.2, §7.4 say so. |
| **58-5 (L)** the session collides after a restart | §2.1: `"<generation>:<16 random hex>"` per login. §5.1: "the same back" compared by the JWT's value. §3/§4 regexes. Test across an `Account` rebuild. |
| **58-6 (L)** "a late answer is kept" against the ask's cut timeout | §5.1: the ask always runs 10 s in the session's scope as one shared `Deferred`; only the waiter waits min(...). Test. |
| **58-7 (L)** the sweep's force had no `used` check; a failed force dropped a good JWT | §2.3: the sweep forces only while the kept session got the 401; the phone's session re-checked under `login_lock`; a force counts only when it logs in. §2.1: a forced login that fails as an outage keeps the old JWT. Tests. |
| **58-8 (L)** a 5xx/429 at login stopped every phone for an hour as a "refusal" | The user's Q-B: §2.3's outage/refusal split, the backoff instead of the cooldown, and the recorded deviation from vibb (26b §9 row, step 5). §2.5: the PWA's wording keeps the split. |
| **58-9 (L)** the `key` check wrote a row and a warning per ask; the mapping was unclear | §2.2: the key is checked by fingerprint only (no open, no record), after the limit; `storytel_key` recorded at most once per generation; the `NotConfigured`/`KeyUnusable` mapping spelled out; the wrong "Spotify's order" claim corrected. |
| **58-10 (L)** the parent couldn't see why Storytel stalls | §2.5: the row's four cases (the capability `storytel_jwt_v1` → "Update Vibb One", the server's cooldown → "Refused", the backoff → "can't be reached", then the phone's word). §3.4 and §4 add the capability. §6 row 3 corrected. `offUntil` is 2 min and cleared at generation 0. |
| **58-11 (L)** `STORYTEL_BASE` needs `Reach::Any` and a fake-login guard | §2.6: `Reach::Any` only for the overridden login and bookshelf; with the override, a login only for an `.example.invalid` e-mail (the save's probe too); `FORCE_MIN_AGE` 10 s while set. Test, §9's guard check. |
| **58-12 (L)** fixture and fake details | §8: `password_vectors` stays inline; the moved fixtures are renamed `fake_login_*`; `--refuse` refuses the login and `/fake/jwt` both; `--revoke-after K` is "after the K-th accepted asset call" (the second book with K=1, matching §9); the fake's login issues `FAKE-JWT-<n+1>` after a revoke; `--login-5xx` added. |
| **58-13 (L)** no shared contract file for the route's answers | §8: `server/testdata/storytel_token_answers.json`, byte-identical in both apps, read by the server's route test, the courier test, the debug parser and the fake; CI paths and the root `CLAUDE.md`. |
| **58-14 (L)** device checks can't see an IP-bound JWT | §9: a download over mobile data, and a room play while the parent's own Storytel app logs in. §12. |
| **58-15 (L)** the purge ran inside `StorytelAccount.get` | §5.5: from `Sync.writeReport` behind an `AtomicBoolean`, never on the mint path, not again on a debug rebuild. `SourceScanTest` pins the caller. |
| **58-16 (L)** `exp - iat` overstates an old token; the 300 s floor against the force | §2.1: `min(exp - iat, exp - wall_now)` with a sane clock, floored at 60 s. §2.3: `FORCE_MIN_AGE` = min(5 min, life/2). §2.7: `left_at_login_s` in the line. |
| **58-17 (L)** §7.2 misstated what a thief gets; no remedy | §7.2: the same shared JWT, not new ones; removal stops new ones but rotates nothing; the remedy (remove, change the password at Storytel, save here), also in the PWA's ⓘ (§2.5); whether it revokes is in §12. |
| Nit: the AIDL comment's timing | §3: "Retired from design 58 step 3". |
| Nit: `mint` can't wrap a suspend call in `runInterruptible` | §5.1: `mint` is a plain suspend call; only the HTTP send is interruptible. |
| Nit: Retrofit has no converter; `Json` vs `Bytes` | §4: a hand-built `RequestBody`. §2.2: the server reads `Bytes`. |
| Nit: base64 padding | §2.1: a URL-safe `GeneralPurpose` engine with `DecodePaddingMode::Indifferent`. |
| Nit: "Spotify's order" | §2.2: Storytel's order spelled out, and how it differs from Spotify's. |
| Nit: 10 min `offUntil` | §5.1: 2 min, cleared when the generation reads 0 or changes. |
| Nit: the migration doesn't nudge | §2.4: "at the launcher's next policy fetch". |
| Nit: the server version between steps | §6, §10: no `server-v*` tag between steps 1 and 3 unless step 1 releases alone; then step 3 bumps again. |
| Nit: the life against the launcher's 86 400 bound | §8: `the_life_fits_the_launchers_bound`. |
| Nit: the ask scope leaks on a debug rebuild | §5.1: the old instance's scope is cancelled. |
| Tests to add (QA's list) | All in §8, with the mutation targets. |
| Device checks to add (QA's list) | All in §9. |
| QA's questions | Q-B, Q1, Q2 and Q3 answered (§0, §11). Q-A stays open until step 1's line, with both branches written (§2.7). |

## Revision for qa-58-revision-check (2026-10-21)

The fixes for `docs/review/qa-58-revision-check.md`.

| Finding | Fix |
|---|---|
| **N-1 (M)** a declined force got the old JWT back, and the run stopped with no way back | §2.3: a declined force answers `refused` (inside a cooldown) or `busy` with the wait to the earliest allowed force: the age, the backoff, the cap's window, or the phone's force window. Never the named JWT. §2.2's `busy` row. §5.1 401 steps 4-6: the JWT is dropped and the wait kept. "The same value back" stays only as a belt. §5.3: `storytelBackAt` also takes a 401 stop (+2 min). Tests, a mutation target, a §9 chain check. |
| **N-2 (M)** the fixture move broke the music suite and the fake before step 2 | §8 and §10: step 1 **copies** the login fixtures to `server/testdata/storytel/` under the `fake_login_*` names, and the fake reads the copies. The music originals stay for `StorytelApiTest` until step 2 deletes them with the login code. Step 4 moves nothing of the music app's. |
| **N-3 (M)** branch (b) was a sketch; the probe's QR wasn't stdlib | §2.7: (b) is marked **not built in this design: a design 58b if the measurement says so**, with an outline to choose on: route, limits, answer rows and their contract file, AIDL method and callback, capability, and the advance's mitigation (mint the next book's URL ahead). The probe prints text only. §0, §10 step 2 and §11 say 58b. |
| **N-4 (L)** other non-200 login answers unclassified | §2.3: `classify_login` in `login_with`. Any non-200 that isn't a refusal (a 4xx other than 408/429) is an outage. The save's probe classifies the same way but touches no backoff or cooldown and stores nothing, as today. Test. |
| **N-5 (L)** `payload_has_email=no` when the payload couldn't be read | §2.7: yes, no or `unknown`. Only `no` clears the gate; `unknown` is decided like `yes`. Test. |
| **N-6 (L)** short lives: the prefetch arithmetic, `expires_in_s` 0 | §2.1 rule 4: a fresh login leaving under 2 × `MARGIN` is `Outage("short_life")` (backoff, `unreachable`), so `expires_in_s` is at least 61. §5.1: no prefetch for a life on the phone of 240 s or less. Tests. |
| **N-7 (L)** the measurement had no trigger and one sample | §2.7: the line is logged in `login_with`, so re-saving the login triggers it. Read two lines an hour apart. The card's facts come from memory, never a column. |
| **N-8 (L)** the fast path opened the sealed password | §2.1 rule 1: the plain columns only; `credentials()` only for a login. Test. |
| **N-9 (L)** a valid kept JWT was withheld in a backoff, cooldown or cap | §2.1 rule 3: a plain ask gets the kept JWT while it has over 2 × `MARGIN` left. §2.2's row. Test. |
| **N-10 (L)** prefetch retries could outrun 20 an hour | §5.1: the prefetch honours `holdUntil` and every wait, and backs off 2, 4, 8 minutes, reset by a new JWT. §7.3. Test. |
| **N-11 (L)** "None" for a new launcher with an old music app | Accepted, with its reason, in §6: the old app reports honestly, its version shows beside it, and the gap is days. A hint would need a `music_state` change. |
| **N-12 (L)** `STORYTEL_BASE` host parsing | §2.6: parsed, with the host compared exactly; `127.0.0.1.evil.example` refused. Test. |
| **N-13 (L)** PLAN.md's entry is stale | Not edited here: the coordinator updates the PLAN entry. |
| **N-14 (L)** no reader for `aes_vectors.json` | §8: `fake_login_fixtures_match` reads it, checks every vector, and checks the copied request's `pwd` hex. |
| "Where the JWT could hide": the card's facts | §2.7: from the account's memory, never a database column. |

## Step 1 status (2026-10-21, branch `design-58-step1`; steps 1 and 4 in one commit)

**Built:**
- **Server 0.35.0** (not 0.34.0: design 57's prep release takes that number in parallel).
  - §2.1: `jwt_payload`/`jwt_life` (URL-safe base64, padding indifferent; `min(exp - iat, exp - now)` with a sane
    clock, at least 60 s, else `exp - iat` at least 300 s, at most 24 h, 30 min fallback), `JWT_TTL` gone. The JWT is
    out of the session's lock: `jwt` a std `Mutex<Option<KeptJwt>>` (`made`, `life`, `generation`, a random
    `session`), `login_lock` for one login at a time, `Session` keeps only the shelf and `sent`. `Account::phone_jwt`
    follows rules 1-4 (the plain columns only; `PHONE_MIN_LEFT`; the kept JWT through a cooldown, backoff or cap while
    it has 121 s or more; one login counted in the cap).
  - §2.2: `POST /api/devices/music/storytel/token` (256-byte body limit, the body read as bytes; the check order
    off -> limit -> key by fingerprint -> the spawned `phone_jwt` within 8 s). The old route stays.
  - §2.3: `classify_login` in `login_with`; the login backoff (1 min doubling to 30, per generation); the phone-login
    cap; the force's rules (session re-checked under the lock, `FORCE_MIN_AGE`, 3 forces an hour per phone, counted
    only when it logs in); a declined force answers `refused` or `busy` with the wait; a forced login that fails as
    an outage keeps the old JWT; the sweep's 401 forces only while the refused session is the kept one; the save's
    probe classifies the same way and touches no backoff or cooldown (its wording follows the split).
  - §7.3: `music_net::PhoneAsks`, used by Spotify's and Storytel's token routes.
  - §2.7: one `info` line per login (`login_with`, the probe's too) and the card's facts under the Storytel ⓘ
    ("Session: lives N min; holds an e-mail address: yes/no/unknown", "not known yet" after a start).
  - Step 4: `STORYTEL_BASE` (`storytel::Config::from_env`: loopback only, the host compared exactly; `Reach::Any`
    for the login and the bookshelf only; the fake-login guard, the probe included; `FORCE_MIN_AGE` 10 s; a warning at
    every start).
- **Both apps**: §3's AIDL (the import, `getStorytelLogin`'s comment, `getStorytelToken` appended),
  `IStorytelTokenCallback.aidl`, `bridge_contract.json`'s `storytel_token`, all byte-identical.
- **Launcher**: `StorytelTokenCourier.kt` (§4's parser, `staleBody`, `STORYTEL_JWT_CAPABILITY`),
  `MusicServer.storytelToken`, `MusicBridgeService.getStorytelToken`/`answerStorytelToken`, the capability appended
  to `STATUS_CAPABILITIES`.
- **Music app**: `bridge/StorytelBridgeToken.kt` (§5.4's `JwtResult`, `jwtResult`, `ask`, `get`); nothing calls it.
- **Shared**: `server/testdata/storytel_token_answers.json` and its two copies; the login fixtures copied to
  `server/testdata/storytel/` (`fake_login_request.json`, `fake_login_answers.json`, `aes_vectors.json`; the music
  originals stay); `scripts/fake-storytel.py` reads them, and gains the bookshelf, `--login-5xx` and
  `--revoke-after K` (the next JWT issued at a login); the CI path lists and the root `CLAUDE.md`;
  `docs/testing/emulator.md` §6i B.

**Deviations from the note:**
- §2.5's PWA rows (the Music page's "Storytel login" cases, the footer's new sentence and remedy) and the server's
  reading of `storytel_jwt_v1` wait for step 3: until the music app stops logging in itself, "Update Vibb One" would
  be wrong and "the phones never get the password" untrue. The launcher already sends the capability.
- The fake-login guard reads the e-mail's whole domain: `example.invalid` or a name under it. "Ending
  `.example.invalid`" would refuse the fixtures' own `k@example.invalid`.
- §9's `payload_has_email=no` for `FAKE-JWT-1` is `unknown` (no dots), as §2.7 says since QA N-5.
- A check card's backoff wording is "Storytel couldn't be reached - the server tries again in a few minutes." (the
  card keeps a code only); the picker's error has the time ("trying again at HH:MM").
- A body over the 256-byte limit is a plain ask (the limit's rejection reads as no `stale`), so the answer stays JSON.
- The answered life is at least `PHONE_FLOOR` (121 s) also on the fast path, so `expires_in_s` is never below 61
  for a short-lived JWT either.
- The card shows "Storytel wasn't asked: STORYTEL_BASE is set and this login isn't a fake one." when the guard
  refused the save's probe (`storytel_off` in `login_error`).
- The fake's `/fake/jwt` is step 2's; its `--revoke-after` issues the next JWT at a login only.

**Code QA fixes** (`docs/review/qa-58-step1-code.md`, all findings):
- L1: while a login is in flight (it may wait in the fetch gate), a plain ask gets the kept JWT that still has 121 s
  (`try_lock` on `login_lock`); a force waits for the lock as before.
- L2: a login that finishes after a save keeps nothing over the save's newer generation, drops nothing of it on a
  refusal, and leaves the card's facts alone.
- L3: the route's cooldown answer (409 with what is left, no login) is pinned; the `Backoff` arm is marked
  defensive.
- L4: `server-ci.yml` watches `music/app/src/test/resources/storytel/` until step 2.
- L5: under `STORYTEL_BASE` a login that isn't a fake one is refused before it counts in the phone-login cap.
- Nits: the check card says "tries again within 30 minutes"; the guard's wording names the domain rule; the save's
  notice under the guard says Storytel wasn't asked; an outage at the save is the event `storytel_login_failed`; both
  apps' `toString`s print `session=<set>`; a fresh login whose claims say it is more than 5 minutes old logs a warning
  (the server's clock may be ahead, §12); the phone-login cap counts a life of at least 121 s; `server/CLAUDE.md`'s
  device API lists the route.
- **Release order** (nit 8): if this server (0.35.0) releases before design 57's prep, that prep must take 0.36.0 -
  `update.sh` installs the newest tag, so a later 0.34.0 would never be installed.
- After the fixes: server 1133 tests (two new: a login in flight, a save during a login), fmt clean, clippy's 13 known
  warnings; launcher 1506 and music app 1170 unit tests; the fake's selftest OK.

**Tests** (each suite run whole before the commit):
- server: 1131 (`cargo test`; the two `tests::summary` tests that read the wall clock fail in the minutes after
  Oslo's midnight whatever the change, and pass after it), `cargo fmt --check`, `cargo clippy --all-targets` (the
  13 known warnings, none new);
- launcher: 1506 unit tests, `assembleDebug` and `assembleRelease` (a local tsnet stub) with `-PwarningsAsErrors=true`;
- music app: 1170 unit tests, `assembleDebug` and `assembleRelease` with `-PwarningsAsErrors=true`;
- `python3 -I scripts/fake-storytel.py --selftest` OK.

Not built here: §8's retired-route, migration and PWA-row tests (step 3), the music tests of step 2.


## Step 2 status (2026-10-22, branch `design-58-step2`)

Built on Q-A's measurement, branch (a): `life_s=86400, left_at_login_s=86400, payload_has_email=no` - a fresh
24-hour JWT per login, no e-mail inside.

**Built:**
- **§5.1** `M/storytel/StorytelSession.kt` (new, pure): the JWT in memory (`Jwt`, redacted), one ask in the session's
  scope with the bridge's whole 10 s and a waiter's `min(10 s, deadline - now - timeoutMs)`, a late answer kept, the
  waits of §5.1's table, the prompt retry, the 401 rule (`stale`, a declined force's wait, the same JWT back as
  `Failed(401)`, a second 401 dropping the new JWT), `prefetchIfDue` (`min(10 min, life / 3)`, never for a life of
  240 s or less, the 2-4-8 minute backoff reset by a new JWT). `StorytelApi` keeps only the asset call (its send in
  `runInterruptible(Dispatchers.IO)`), the fake's `debugJwt` and `jwtAnswer`; the login, the AES, the cooldown store,
  `JWT_TTL_MS`, `LOGIN_*` and `Endpoints.login` are gone. `StorytelAccount` holds one session and one API, reads the
  generation live (`storytelGeneration`: the managed config's, `DEBUG_GENERATION` 1 unmanaged with the switch), cancels
  the old scope on a debug rebuild. `SonosRenderer` prefetches at a start (`load` with `playNow`, `play()`) and at every
  poll tick while a Storytel book shows playing, launched on the renderer's IO scope.
- **§5.2** `BridgeSync.pullStorytel` goes; `state()` is `none`/`refused`/`ok` from the generation and the waits.
- **§5.3** `Mint.LoginFailed(reason, backAtWallMs)`; `DownloadRun.storytelBackAt` (a refusal's end, the back time, a
  401 stop + 2 min) and the worker re-enqueues at the earliest of it and `nextRetryAt`.
- **§5.5** `M/bridge/StorytelLegacy.kt` replaces `StorytelLogin.kt`: the four files and the `vibb_storytel` key, once
  per process (`AtomicBoolean`) from `Sync.writeReport`, before the report's `state()`.
- **§5.6** `StorytelEndpoints.debugJwt()` (the fifth reader, `StorytelAccount.kt` only), `debugSwitchJwt` and
  `FAKE_JWT_PREFIX` in `M/logic/Storytel.kt`, the bridge path filtered too; `DebugStorytel.seedLogin` goes and an old
  `storytel.json` is deleted unread.
- **The fake**: `/fake/jwt[?stale=]` from `storytel_token_answers.json`'s rows, `--jwt-ttl`, `--off`, `--busy S`,
  `--jwt-down`, `--refuse` for it too, a stale naming the current session issuing the next JWT (the revoke's way out
  for the phone alone); the selftest's part 7. `docs/testing/emulator.md` §6i A rewritten for the JWT (§9's checks),
  §6i B's step-2 checks.
- **The music app's login fixtures** (`login_request.json`, `login_ok.json`, `login_no_jwt.json`, `aes_vectors.json`)
  are deleted with `StorytelApiTest`'s login tests and `BridgeTest`'s seal test; `fake_login_fixtures_match` no longer
  compares them, and `server-ci.yml` no longer watches `music/app/src/test/resources/storytel/`; the root `CLAUDE.md`
  says so.
- **Tests**: `StorytelSessionTest` (new, §8's list), `StorytelLegacyTest` (new), `StorytelApiTest` (the asset call,
  the cancelled request, the fake's route, the five readers), `StorytelHttpTest` (a mint end to end through the
  session), `StorytelBridgeTokenTest` (`jwtAnswer` on every shared row), `StorytelDownloadTest` (`storytelBackAt` for
  each stop, the 401 stop; the cooldown file test gone), `StorytelRulesTest` (`storytelGeneration`, `debugSwitchJwt`),
  `SourceScanTest` (the redacted list, the five readers, `storytel.json` unread, the old login named only by its purge
  and the purge called only from `Sync.kt`), `GuardsTest` (the mint's line, the renderer's prefetch wiring,
  `storytelBackAt`).
- `music/CLAUDE.md`'s Storytel lines (§10 step 5's music part).

**Deviations from the note:**
- The 401 rule lives in `StorytelSession.mint(timeoutMs, deadlineMs, asset)`, the asset call passed in, so it is pure
  and tested; `StorytelAccount.mint` checks the consumable id (no ask for a malformed one) and delegates. Its signature
  is unchanged, so `Downloads.kt` and `SonosRenderer.kt`'s minter are untouched.
- `LoginFailed.backAtWallMs` is a `Long`, not `Long?`: every `LoginFailed` has a time to come back; `deadline` is 2
  minutes, as an outage.
- The 30 s hold is set by every `unreachable` answer itself (any word but `key`), and the prompt retry passes it. The
  mint behaves as §5.1 says; besides, other mints during the 5 s, and an `unreachable` whose waiter had given up, ask
  nothing for 30 s either.
- A waiter whose time runs out before the answer counts as `unreachable` for the prompt retry and the mint
  (`LoginFailed("unreachable")`) but sets no hold: the ask goes on and its answer is kept.
- A force while a plain ask (a prefetch) is in flight waits for that answer first, then asks with `stale` unless it
  brought another JWT: still one ask at a time. A waiter that shares a force answered with the named JWT gets
  `Failed(401)` too.
- The prefetch at a room start is in `SonosRenderer.load(playNow)`/`play()` (the renderer is the service's room path),
  launched before `controller.load`/`playTile` queue the actor's job; `prefetchIfDue` reads the generation only when a
  prefetch looks due, so the poll ticks cost a lock.
- §9's room check uses `--jwt-ttl 300`, not 120: QA N-6's guard never prefetches a life of 240 s or less, so 120 s
  would show the advance asking.
- No log line for a real JWT under the debug switch (Spotify logs one): `storytel/` has no logger, and the answer is
  `Unreachable("launcher")`. `StorytelLegacy`'s fixed debug line is written only when something was there.
- `Endpoints` keeps one field, `api`.

**For the code QA to look at hardest:** `StorytelSession.bearer`'s loop (the shared ask, the not-ours wait, the
generation re-read after an answer, the retry and the hold, the deadline), the scope's cancellation path (`await`'s
`ensureActive`, so a cancelled ask is never the caller's cancellation in the room's actor), `keep()`'s
same-value and older-generation rules, the prefetch's backoff against the hold and the waits, and `storytelBackAt`
with the update-order table's row 3 (an old launcher: the job every 2 minutes, two 10 s asks each).

**Tests** (each suite run whole before the commit): music app 1191 unit tests and `assembleDebug` with
`-PwarningsAsErrors=true`; `python3 -I scripts/fake-storytel.py --selftest` OK; server `cargo test` 1134 and
`cargo fmt --check` (`fake_login_fixtures_match` without the music half).

**Code QA fixes** (`docs/review/qa-58-step2-code.md`, all findings):
- S2-1: the fake's `/fake/jwt` ages its JWT (`expires_in_s` is the life left) and, as the server's `PHONE_MIN_LEFT`,
  answers a plain ask below `min(15 min, life / 2)` left with the next JWT (`jwt #n (renewed)`; selftest part 7). The
  belt: `keep()` compares only with an unexpired kept JWT (and the prefetch's "same back" too), so the same value
  with a later expiry replaces an expired one. §6i A's room-advance check now holds with `--jwt-ttl 300`.
- S2-2: **the come-back after an outage backs off**: `LoginFailed("unreachable")`'s `backAtWallMs` is 2 minutes,
  then 4, 8, 16 and 30 over consecutive outage stops (`COME_BACK_MAX_MS`), counted in the session's memory and reset by
  any `ok` answer or a new generation; the hold and `deadline` answer the current step without growing it. A new
  process starts at 2 minutes. So row 3 of §6 (an old launcher) and a server away for a weekend cost a few jobs an
  hour, not 30. A 401 stop stays 2 minutes.
- S2-3: the prefetch's launch in `SonosRenderer` catches every `Exception` (cancellation rethrown), as `mintFor`.
- S2-4: `debugSwitchJwt` filters both branches of the ask, so "whichever way it came" is true.
- S2-5, S2-6: tests for an ask in flight across a generation change, and for the cancelled waiter's `slept.isEmpty()`.
- Nit 1: the consumable id is checked once, in `StorytelApi.assetUrl` (a malformed one, which a listing key never is,
  costs an ask). Nit 2: a force that waits on a prefetch ending `unreachable` meets that answer's hold at once and gets
  no prompt retry - accepted, the hold is the point.
- After the fixes: music app 1194 unit tests and `assembleDebug` with `-PwarningsAsErrors=true`; the fake's selftest OK.
