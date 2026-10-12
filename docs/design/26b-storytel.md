# 26b - Vibb music phase 2: Storytel (buildable spec)

Architect draft (2026-10-12), the first step of design 26's phase 2. Inputs: design 26 (the brief: "### 2 - Storytel",
"How to work", "Coordination with the main session", review item 4 **decided: option A** with its "Accounts and
sessions" paragraph, item 5), design 20 (its decision sections win: "Storytel and the source fetching", "Outputs", "New
content", "Offline marks"; user decision 2: no position mirroring), design 21 (§1.4, §2 Storytel, the step 3 bridge),
21b (§2.7 addresses, §4-§6), 26a (the renderer axis this plugs into) and its QA files, `music/CLAUDE.md`, and vibb
**`pipewire` `1fcadac`** (`storytel.py` and `bookmarks.py` are identical on `main`): `pi/vibb/storytel.py` (828 lines,
all of it), `pi/vibb/bookmarks.py`, the Storytel parts of `pi/vibb/library.py` (87-89, 862-883, 948),
`pi/vibb/content.py` (334-340, 365-376, 500-509, 999-1007), `pi/daemon.py` (1423-1476 `_sonos_body`, 1477-1560
`_sonos_play_entry`, 1669-1715 `sonos_start_target`, 2084-2105 `_sonos_known_duration`, 3424-3431 and 3824-3881 the
routes, 4497-4570 adopt, 4657-4672 the duration lookup, 4774-4833 the mirror) and
`tests/storytel_{api,expand,routes,sync,bookmark_sync,subprocess,sonos}.py` plus `pi/web/app.js` 505-535 and 746-990
(the picker). **This is a port**: every behaviour names its vibb source; a deviation is listed in §9 with its reason.
`M/` = `music/app/src/main/java/me/vibb/music/`, `S/` = `server/src/`, `L/` =
`launcher/app/src/main/java/com/kidslauncher/mdm/`. [device check] = needs the Jelly Star and the family's real account.

**In:** the server's Storytel client (login, bookshelf, `normalize_shelf`, the JWT cache, the refused-login cooldown,
AES), the parent's shelf picker (series and standalone books) and Storytel entries in the library, the server listing
each Storytel entry like an NRK/RSS one (21b), the phone's own Storytel client (login with the sealed login it already
receives, the CDN URL minted per download and per Sonos play), downloads (download-only), local playback from the copy,
positions (phase 1's `bookmarks.py` port, unchanged), NY/Nytt for a new book, "Lydbok · del 3 av 5", Storytel on Sonos
(the late mint, adopt by `consumableId`), the import's Storytel entries, a fake Storytel for the emulator. **Out:**
position mirroring (outbox, bookmark POST, `deviceId`: user decision 2026-10-09), a login per phone (Storytel Family:
designed in §6.6, not built), picking single books out of a series (vibb can't either, §10), streaming a book on the
phone (download-only), any launcher code.

## 0. The shape

Option A splits vibb's one process in two (design 26 review item 4):

- **The server** logs in with the family's login (the sealed `music_storytel` singleton, phase 1) and reads the
  **bookshelf only** - never bookmarks, never audio. It normalises the shelf with vibb's `normalize_shelf` for two
  readers: the parent's picker (`/music/storytel`) and the sweep, which **lists** every Storytel entry exactly as it
  lists NRK and RSS (21b: the server is the brain for metadata, the phone never lists a source). The listing is the
  existing document (`GET /api/devices/music/entries/{id}/items`): one item per book, key = the book's `model.id`, `url:
  null` (a signed URL expires, so it is never listed), the shelf's `duration_ms`, the book's cover as the item's
  `art_url` (server-side; since design 27 the listing's `art` is the 64-hex hash of the item's own stored picture -
  step 4a has the art pass fetch a book's cover so it arrives as that hash).
- **The phone** gets the library and the listing through the launcher as today (no launcher change: `MusicPlan`'s
  `libraryHead` already passes every non-`own` entry's `items`). It logs in by itself with the login the bridge sealed
  into the Keystore (phase 1, `bridge/StorytelLogin.kt`), mints the CDN URL (`assets/v2/consumables/<id>/abook`, the 302
  captured, never followed) **per download attempt and per Sonos play, at the last moment**, downloads into the one
  `SimpleCache` under the item's cache key (a re-minted URL resumes the same bytes), plays only from the copy, and keeps
  positions in Room (phase 1). It never reads the shelf and never sends a position.
- **Two independent logins, one account** (design 26 "Accounts and sessions"): `login.action` carries no `deviceId` and
  returns a JWT with no refresh token, so the server and each phone hold their own JWT. Both sides port vibb's two
  guards: the JWT cached for its TTL (1 h, monotonic) with one re-login on a 401, and the **refused-login cooldown**
  (1 h, persisted, cleared by a new login generation) so a stale password never hammers Storytel. The server needs no
  `deviceId` at all (design 26's "its own stable deviceId" predates that paragraph, §11).
- **Threading.** Server: one `storytel::Account` in `AppState` (a tokio `Mutex` so one login runs at a time; the JWT and
  a 5-minute shelf cache in memory); every request goes through `AppState.music_fetch` (`music_net::Gated`: one request
  in flight server-wide, 200 ms apart). Phone: one process-wide `StorytelAccount` (Android) over the pure `StorytelApi`;
  its callers are the download worker (WorkManager, IO) and a Sonos room's actor (IO) - **never the main thread**; a
  lock around the login inside `StorytelApi` (§4.2) so the two never log in twice at once.
- **Secrets** (design 20 §5, design 21 §1.4): the password, the encrypted password (`pwd=` is AES with a public key and
  IV: as good as the password), the email, the JWT and a minted CDN URL (its `token=` is a bearer for that book) never
  reach a log, `music_state`, an event, a page, Room, a file other than the sealed login, or an error message. The PWA
  never shows the login back (phase 1). Fake tokens in tests never look like real ones (`FAKE-JWT-1`, `FAKE-SIG-1`,
  `*.example.invalid`; never `eyJ...`).
- **Secrets in `toString` and `Debug`** (QA L9): every type that holds one prints it redacted - phase 1's
  `StorytelLogin.Login` (a data class whose generated `toString` prints the email and the password: overridden), the new
  `StorytelRequest` (the `pwd=` URL and the bearer header), `StorytelResponse` (the login body holds the JWT) and
  `Mint.Url`; `SourceScanTest` pins an explicit `toString()` on each. On the server neither `StoredLogin` nor the JWT
  holder derives `Debug` (a hand-written redacting one where a `Debug` bound needs it).
- **Logs we don't write ourselves** (QA L10): reqwest logs each redirect's URLs at `debug`, so `RUST_LOG=debug` would
  print `login.action?...pwd=`: the server's default filter floors `reqwest=info` whatever `RUST_LOG` says for the rest
  (`main.rs`'s tracing setup), and `server/CLAUDE.md` says why (`DEPLOY.md` is the main session's). Media3 keeps a
  request's upstream URI in the cache's metadata (`exo_redir`, in `databases/exoplayer_internal.db`) whenever it
  differs from the one it asked for; so a Storytel book's CDN request **never follows a redirect** (vibb's `urlopen`
  does; §9) and the minted URL is OkHttp's canonical form (`StorytelApi.mintedUrl`, what `OkHttpDataSource.getUri()`
  returns): nothing with a token is written there. A CDN that redirects anyway fails the attempt as a transient
  `http_30x` (the next attempt mints again) - [device check]: the real CDN answers the minted URL without a redirect.
  The emulator's §6i check greps the app's whole data folder (the databases and their WAL files included) for
  `token=`. (Step 3; qa-26b-step3-code choice 6 and L5.)

## 1. Behaviour map (vibb -> Rust / Kotlin)

### 1.1 `pi/vibb/storytel.py`

| vibb | Home | Notes |
|---|---|---|
| CONSTANTS 67-86: `LOGIN_URL`, `API`, `BOOKSHELF`, `ASSET`, `AES_KEY`, `AES_IV`, `UA`, `JWT_TTL_S`, `TARGET_RE` | `S/storytel.rs` consts; `M/storytel/StorytelApi.kt` consts | one block per side, the UPSTREAM note kept (storytel-player 1.3.0); `BOOKMARK`, `_DEVICE_FILE`, `_OUTBOX_*` not ported (§9) |
| `UA` (one honest agent) | server `kid-phone-server (self-hosted; vibb music library)` (music_net's), phone `VibbMusic/<version>` (MediaCache's) | honest, never a pinned app version (vibb's rule) |
| `_log` (stderr) | `tracing` / `Log` | outcomes, statuses and consumable ids only (§0 secrets) |
| `is_storytel`, `parse_target`, `series_target`, `book_target` | `S/storytel.rs` `parse_target`/`series_target`/`book_target`; `M/logic/Storytel.kt` `storytelTarget` | the grammar `^storytel:(series\|book):([A-Za-z0-9_-]+)$` |
| `cache_dir` (`storytel-<sha1[:12]>`) | the item cache key `<entry key>:<consumable id>` (`itemCacheKey`, phase 1) | the entry key is `state_key(target)` = the same sha1[:12] |
| `credentials`, `configured` | server `Account::credentials` (the sealed row + `MUSIC_SECRET_KEY`, phase 1's `StoredLogin`); phone `StorytelLogin.load` (phase 1) | |
| `_write_private` | n/a | the DB (sealed), the Keystore file (phase 1), atomic JSON writes |
| `forget_downloads` (logout) | the phone's library drops Storytel entries when its switch is off or no login is stored (§2.7) -> phase 1's prune | the books go with the account, as on the Pi |
| `save_credentials` (store/clear, `_jwt = None`, cooldown cleared) | `handlers::music::save_storytel`/`clear_storytel` + the generation; phone: a new generation from the bridge | cooldown and JWT are tied to the login generation, so a new login clears both |
| `device_id` | not ported | only the bookmark POST carried it (§9) |
| `_encrypt_password` (openssl) | `storytel::encrypt_password` (`aes` crate, CBC by hand); `StorytelApi.encryptPassword` (`javax.crypto` `AES/CBC/PKCS5Padding`) | UPPERCASE hex; vibb's vector (§7) |
| `_NoRedirect`, `_request` (the one HTTP seam) | server `music_net::SourceRequest` (method, headers, body, follow); phone `StorytelHttp` (the seam the tests fake) | follow=false only for the asset call |
| `_jwt`, `login`, `_bearer`, `_api` | `storytel::Account::{login, bearer, api}`; `StorytelApi.{login, bearer, call}` | TTL 1 h, re-login once on 401, non-200 login = refused + cooldown, 200 without `accountInfo.jwt` = error, no cooldown |
| `_LOGIN_COOLDOWN_S`, `_cooldown_*` | server `music_storytel.login_refused_at` + `_generation`; phone `noBackupFilesDir/storytel_refused.json` | 1 h, active while `0 <= now - at < 1 h` (vibb's `0 <` is float seconds; at ms resolution a mint right after a refusal would log in again - step 1, both sides) and the generation is the stored login's |
| `bookshelf` | `storytel::Account::bookshelf` (server only) | POST, body `{"items":[]}` under `Content-Type: application/x-www-form-urlencoded`, bearer, `Accept: */*` |
| `bookshelf_cached`, `RAW_SHELF_TTL_S` | `Account::shelf(max_age)` (in memory, 300 s; a failed fetch falls back to the last good shelf) | the sweep reads it, so a pass fetches the shelf once |
| `asset_url` | `StorytelApi.assetUrl(cid, timeoutMs)` (phone only) | GET with bearer, no redirect followed, the `Location` or a status class (§4.1: `NoAudio`/`Failed`); 6 s on the Sonos path |
| `_cover`, `normalize_shelf` | `storytel::normalize_shelf` (server only) | `model.id`, abook format only, series grouping in first-seen order, books by `(orderInSeries, title)`, kids/locked/geo as data |
| `write_shelf`, `read_shelf`, `_SHELF_JSON` | the listing: `music_items` (server), Room `items` (phone) | |
| `shelf_durations` | not ported | mirroring only (§9); the listing's `duration_ms` serves the display |
| `book_path`, `local_cover` | the cache key; the entry cover (the sweep's cover store, 21b §2.6) | |
| `entries_for(remote)` | `Snapshot.visible(entry)` with the renderer (§3.2) | local: downloaded books only; a Sonos room: every book |
| `newest_book_id` | phase 1's NY/Nytt (`markNewSeen` per key) | every book is eligible news (§3.4) |
| `downloaded_count` | `music_state.downloads` have/want per phone (21b §4.3) on the parent's card | |
| `_outbox_*`, `outbox_note`, `outbox_pending`, `push_bookmark`, `outbox_flush` | not ported | user decision 2026-10-09 (§9) |
| `DISK_FLOOR`, `_free_bytes` | `logic/Storytel.kt` `STORYTEL_FREE_FLOOR` = 1.5 GB, checked before each book | beside WorkManager's storage-not-low |
| `_download` (Range resume of a `.part`) | Media3 `ProgressiveDownloader` into `MediaCache` with `customCacheKey` | the stored download row names the book, never the minted URL (§4.3) |
| `sync(target, count)` | the sweep's Storytel check (the list) + the phone's `downloadPlan`/`DownloadWorker` (the books) | every book attempted (the locked flag isn't trusted), one at a time, re-mint per attempt, a failure skips that book only, first `count` in reading order |
| `__main__` (bare subprocess) | n/a | one process (§9) |

### 1.2 `pi/vibb/bookmarks.py`, `library.py`, `content.py`

| vibb | Home | Notes |
|---|---|---|
| `bookmarks.py` (`RESUME_MIN_S`, `save_state` with the `done` record = duration, `episode_pos`, `clear_state` keeping `done`, `rotate_to_bookmark`) | `M/logic/Bookmarks.kt` (phase 1, unchanged) | positions per (entry key, item key) in Room; ms internally |
| `library.py:87-89` cache 0 -> -1 for Storytel | server save/import/library; `LibraryParser` | download-only: "in the library" means downloaded |
| `library.py:948` `_natural_order` storytel = oldest_first | `logic/PlayOrder.kt` | book 1 first |
| `library.py:862-883` the sweep's storytel branch, `_mark_new_seen` | the server sweep (§2.4) + the phone's sync (§3.4, §4.3) | |
| `content.py:334-340` `newest_episode_id`, `:365-376` `cache_key_for`, `:500-509` `collection_image`, `:999-1007` `expand_entries` + `PREFER_REMOTE` | NY (§3.4); the cache key + phase 1's prune; the entry cover; `Snapshot.visible(entry)` per renderer | the renderer passed explicitly (26a §9), no global, no expand cache |

### 1.3 `pi/daemon.py`

| vibb | Home | Notes |
|---|---|---|
| `_sonos_body` storytel branch (1446-1473): mint `asset_url(id, timeout=6)`, `art_url`, `duration_s`, never raises | `PlayBodies.build` (a `mint` body) + `SonosController.playIndex`'s actor job resolves it right before `SetAVTransportURI` | a failed mint is a failed play, never an exception out of the actor |
| `_sonos_play_entry` body once, seed `dur_s` + `dur_from_shelf` + `uri` | 1b's `playIndex`/`played` (unchanged) | one mint per play |
| `sonos_start_target` `PREFER_REMOTE = True` + the storytel exemption of the http-only filter (1687-1699) | `queueFor(..., SONOS)` on a remote snapshot; `PlayBodies.speakerDropReason` passes Storytel items | |
| `_sonos_known_duration` (2084-2105) | `logic/Storytel.kt` `durationByConsumableId`, used by the poll's duration carry and adopt | the `consumableId` query parameter, never a substring |
| adopt (4527-4570): match on `consumableId`, restore the length from the queue row, adopt with the snapshot's uri | `SonosLifecycle.adoptIndex`/`roomVerdict` via `sameStream`; `SonosTransport.adoptLive` takes the live uri | renderer.json keeps a marker, never the signed URL (§5.3) |
| poller "authoritative first" (4657-4672) | `SonosController.onPoll`'s duration carry | |
| `/storytel/credentials` (probe login + shelf, 401 forgets, 502 keeps), `/storytel/shelf`, `/storytel/logout`, `/storytel/status` | `POST /music/storytel` (probe), `GET /music/storytel` (picker), `POST /music/storytel/add`, `POST /music/storytel/clear`; the card's status line | the session-authenticated PWA (default-deny, as vibb's token) |
| `_storytel_bookmarker`, `_storytel_mirror_tick`, `_storytel_wake`, `_STORYTEL_MIRRORED`, `storytel_sync` | not ported | §9 |
| `_stop_child` comment: a storytel play is a local file, not a network start | local playback reads the cache only (§4.4) | |
| `pi/web/app.js` 513-535 (storytel cache choices: all / first 5 / 10 / 20) and 746-990 (the picker: kids-only filter on by default, one row per group, "in the library" vs downloaded, add checked with `order: oldest_first, cache: -1, resume: true`) | `templates/music_storytel.html`, `handlers::music::{storytel_shelf, add_storytel}`, the entry page | categories instead of sections, per-phone ticks (design 20 decision 6) |

## 2. Server

### 2.1 `S/storytel.rs` (new; pure parts unit-tested, I/O through `AppState`)
- **AES** `encrypt_password(pw) -> String`: AES-128-CBC with vibb's key `VQZBJ6TD8M9WBUWT` and IV `joiwef08u23j341a`,
  PKCS#7, UPPERCASE hex. The `aes` crate (0.9, already in `Cargo.lock` through `aes-gcm`; added as a direct dependency,
  no new crate) for the block cipher, CBC chaining and padding by hand (15 lines; there is no `cbc` crate in the tree).
- **Targets**: `parse_target(&str) -> Option<(TargetKind, String)>` (`Series`/`Book`, the id `[A-Za-z0-9_-]+`, nothing
  else), `series_target(id)`, `book_target(id)`.
- **`normalize_shelf(raw: &[u8]) -> Result<Vec<Group>, ShelfError>`**, vibb 424-480 line by line: `raw.items` is an
  **object read in document order** (a `serde::de::MapAccess` visitor into `Vec<(String, Value)>`; `serde_json::Map` is
  a `BTreeMap` here and would sort by key, and the `preserve_order` feature must not be switched on - it reorders every
  map the server serialises); `model = item.model || item`; the first format with `type == "abook"`, else skip (ebook
  only); `Book { consumable_id: model.id as a string (a JSON number in decimal, a string as is; skipped when absent or
  not [A-Za-z0-9_-]{1,64} - vibb writes "None", §9), title, order: seriesInfo.orderInSeries as f64 (else 0),
  duration_ms: the format's durationInMilliseconds (else 0), cover: the format's cover.url else model.cover.url, kids:
  model.kidsBook, locked: the abook format's isLockedContent, geo: the abook format's isGeoRestricted (vibb 459-460) }`;
  group key `("series", sid)` else `("book", consumable_id)`, where `sid` is `seriesInfo.id` **stringified the same way
  as `model.id`** (a number in decimal, a string as is - never `Value::to_string()`, whose quotes would fail the
  grammar) and **Python-falsy rules** decide "no series": absent, null, `""` or `0` -> a standalone book (QA L1;
  likewise a null, `0` or `""` `orderInSeries` is 0, a missing or empty `seriesInfo.name` falls back to the title,
  booleans are `bool(...)` of whatever is there); a new group takes `kind`, `series_id`, `target`, `name =
  seriesInfo.name || model.title`, `cover` (the first-seen book's); groups in first-seen order; books sorted by `(order,
  title)` (`f64::total_cmp`, then the title's code points); `kids = any(book.kids)`. **A consumable id seen twice keeps
  the first** (21b's dedupe rule; `music_items`' primary key `(entry_id, key)` would fail the check's write). A group
  whose target fails `parse_target` is dropped (logged). Nothing is filtered for locked or geo: they are data (vibb).
- **The account** `pub struct Account` (`AppState.storytel: Arc<storytel::Account>`):
  - `credentials(state) -> Result<StoredLogin, StorytelError>` reads the `music_storytel` row and opens it with
    `state.music_key` (`NotConfigured` without a login or a key, `KeyUnusable` when sealed with another key).
  - `login_with(state, email, password)`: the request and its outcome without touching the DB (the save's probe uses it
    with the entered login, §2.3); `login(state)` = `credentials` + the cooldown + `login_with` + the stamps.
  - `login(state) -> Result<String, _>`: cooldown first (`music_storytel.login_refused_at` within 1 h of `Sweep::now()`,
    i.e. `0 <= now - at < 1 h` as the phone's (§1.1; a stamp in the future is none) - **for the step 2a implementer: the
    server matches the phone's boundary** - and `login_refused_generation = generation` -> `Cooldown { until }` with **no request**); then GET
    `LOGIN_URL?m=1&uid=<email>&pwd=<encrypt_password(pw)>` (`form_urlencoded::Serializer`, so `@` is `%40` as in
    Python's `urlencode`), `Kind::Login`, redirects followed (vibb). Not 200 -> stamp the cooldown for this generation
    and `Refused(status)` (a 5xx counts too, as vibb's "a refusal, not an outage"); 200 without a non-empty
    `accountInfo.jwt` -> `NoJwt` (no cooldown); a transport error -> `Fetch` (no cooldown). On success the JWT is kept
    with `tokio::time::Instant::now() + 1 h` and the generation it was made for; `login_ok_at` stamped, `login_error`
    cleared.
  - `bearer(state, force)`: the kept JWT when not forced, not expired and of the current generation; else `login`.
  - `api(state, request)`: adds `Authorization: Bearer`, `Accept: */*`; a 401 -> `bearer(force)` and one retry, never
    two (vibb `_api`).
  - `bookshelf(state) -> Result<Vec<Group>, _>`: POST `BOOKSHELF`, body exactly `{"items":[]}`, `Content-Type:
    application/x-www-form-urlencoded` (a JSON content type is a 400, field-verified), `Kind::Shelf`; not 200 ->
    `Status(code)`; truncated -> `TooBig`; then `normalize_shelf`. Stored in the shelf cache.
  - `shelf(state, max_age)`: the cache when younger than `max_age` (300 s for a routine sweep check, **0 for Check now**
    so a book added in Storytel's app a minute ago shows, QA L5), else `bookshelf`; a failed fetch returns the last good
    shelf whatever its age (vibb "stale beats dead"), else the error - **for the picker's Add only**: the sweep reads
    through `shelf_counted`, which never falls back (a failed read is the check's error; the sweep keeps its own last
    good list, 21b §2.4; qa-26b-step2b-code M1). The cache's age and the JWT's expiry run on
    `tokio::time::Instant` plus a per-account offset the tests move (`Account::advance`; a paused runtime would
    auto-advance while sqlx waits on its worker thread); only the DB stamps use `Sweep::now()`.
  - `forget()`: drops the JWT and the shelf (save and clear call it; the generation check covers a race).
- **Errors** carry codes for the card and the sweep: `storytel_off` (no login or no key), `storytel_key` (sealed with
  another key: "enter the login again", QA L4), `login_refused` (`Refused`/`Cooldown`), `no_jwt`, `network`, `timeout`,
  `http_<code>`, `too_big`, `bad_shelf`, `not_on_shelf` (§2.4). `music_storytel.login_error` holds such a code, never a
  message, and `login_error_at` its time (code QA, the main session's decision). **No message ever holds a URL**: `music_net::network_error` builds its text from `err.without_url()` for
  every request (the login URL carries the email and `pwd=`; reqwest's `Display` prints the URL).

### 2.2 `music_net` (extended, not forked)
- `SourceRequest` gains `method` (GET default), `headers: Vec<(HeaderName, String)>`, `body: Option<Vec<u8>>`;
  `SourceRequest::new` keeps today's defaults, so every existing caller and `CannedFetch` answer stays as is.
- `Kind::Login` (64 KB, 20 s - vibb's login timeout) and `Kind::Shelf` (8 MB, 30 s). Storytel requests are
  `Reach::Public` and go through the one gate.
- `CannedFetch` records each request's method, headers and body (`requests`), so the tests pin the bookshelf's shape.
  It never logs them. Nothing else in the server logs request headers.

### 2.3 The PWA (`handlers::music`, `templates/music.html`, new `templates/music_storytel.html`)
- **Save (`POST /music/storytel`)**, vibb's probe (`/storytel/credentials`, 3824-3845): after phase 1's field checks and
  **before anything is stored**, one probe with the entered login - `Account::login_with` with these credentials (the
  cooldown doesn't apply to a probe; a probe never stamps it) and one `bookshelf` - **within 45 s in all**, the wait for
  the one gate included (it can sit behind a feed fetch of up to 120 s; QA L4): past it the probe counts as unreachable.
  Outcomes:
  - accepted: sealed and stored (generation + 1, `login_refused_at` cleared), the probe's JWT and shelf kept for the new
    generation, the phones nudged, `storytel_login_saved`; the card says "Saved - Storytel shows N series and books".
  - refused (not 200) or `NoJwt`: **nothing stored, the saved login unchanged** (§9: vibb forgets the account here), the
    error by the field "Storytel refused that e-mail and password. The saved login is unchanged.", event
    `storytel_login_refused` (no detail beyond the status).
  - unreachable (a transport error, the shelf failing, or the 45 s passed): stored as in "accepted" (vibb saves, then
    502s), the notice "Saved, but Storytel couldn't be reached to check it".
- **Clear (`POST /music/storytel/clear`)**: as phase 1 (generation + 1) plus `Account::forget`; the phones' libraries
  drop their Storytel entries (§2.7), which deletes the books (vibb's logout `forget_downloads`).
- **The card `#storytel`** gains a status line from `login_ok_at` / `login_refused_at` / `login_error` (with
  `login_error_at`, the time on the error lines): "Storytel
  accepted the login (12 Oct, 10:02 UTC)", "Storytel refused the login at 10:02 UTC. Nothing is tried again before 11:02
  UTC - save the password again to try now.", "Storytel couldn't be reached (10:02 UTC)"; the link "Pick books from the
  Storytel shelf" (`/music/storytel`) while a usable login is stored; two hint lines: "One Storytel login for the
  family. Two phones listening at the same time need Storytel Family, which isn't supported yet." and "Use a password
  you use only for Storytel: each phone keeps it to log in."
- **The picker (`GET /music/storytel[?all=1]`)**, vibb's `/storytel/shelf` + `renderStorytelShelf`: a fresh
  `bookshelf` (it refreshes the cache). Without a usable login: a pointer to `#storytel`. An error: its text (refused,
  waiting until HH:MM UTC, unreachable). Else one form, one row per group, in shelf order; **kids' books only** unless
  `?all=1` (vibb's checkbox, on by default; a link toggles it). A row: a checkbox `target`, the name, "Series · 5 books"
  or "Book", "for kids" when `kids`, "N books marked locked by Storytel - each is tried anyway" / "N books not available
  in this country (per Storytel)" when flagged, and a `<details>` with the books (order, title, duration h:mm, the
  flags). A group already in the library is checked and disabled with "In the library" linking to `/music#entry-<id>`.
  Below: the category select (default the first category whose `default_kind` is `audiobook` - Lydbøker; else the
  first) and "Add checked". Text only: no remote images (the page's CSP; covers come later through the sweep).
- **Add (`POST /music/storytel/add`, `target` repeated, `category`)**: the form is read as `Form<Vec<(String, String)>>`
  like `save_device_sonos_rooms` (`FormMap` is a `HashMap` and keeps one `target`; QA L3); the shelf from
  `Account::shelf(300 s)`; each target must name a group on it (an arbitrary target is refused - vibb's 4c rule that a
  raw target is privileged); skipped when already in the library; within `MAX_ENTRIES` (the INSERT's own `WHERE`, phase
  1); the entry: source `storytel`, target, key `state_key(target)`, name = the group's name cut to `MAX_NAME_CHARS`,
  the category, order `auto` (= oldest first for Storytel), cache `-1`, resume on, sort next; one `music_entry_added`
  event each ("Storytel: <name>"); the sweep woken; back to `/music/storytel?added=N#added` ("Added N - tick them for a
  phone on its Music card"). No phone is ticked by itself (design 20 decision 6).
- **The entry page** for a Storytel entry: name, category, order, resume, cover, delete, Check now; offline depth offers
  vibb's Storytel choices only - "All books" (-1), "The first 5", "10", "20" (and the stored value when it is another
  one, from an import); a stored or posted 0 is saved as -1 (vibb 87-89) - on the entry page **and** in the library
  card's offline select (`save_offline`, which offers the same Storytel choices; QA M3). No "add by link" for Storytel:
  a pasted link stays `parse_link`'s (refused as today); the add form gets a hint "Storytel: pick from the shelf".
- **The library card** (`partials/music_card.html`): `kind_label` "Storytel series" / "Storytel book"; "N books"; the
  sweep's error texts (§2.4); per-phone lines from `music_state.downloads` ("3 of 5 books on the phone · waiting for
  Wi-Fi"); a ticked phone the gate (§2.7) excludes reads "Ella: not sent - Storytel is off on this phone" / "... - no
  Storytel login is saved" and isn't counted in "on N phones"; without a login a failed check says "The phones don't
  get it until a login is saved." instead of "The phones keep the list" (qa-26b-step2b-code L2). **The device card** (`#music`): with ticked Storytel entries and the phone's Storytel switch off, "Storytel
  is off on this phone: its N Storytel entries aren't sent"; with the switch on and no login, "No Storytel login is
  saved"; the phone's `music_state.storytel` as words (`none` "no login on the phone", `refused` "Storytel refused the
  phone's login - it waits an hour; saving the password again retries at once"). The mobile-data switch's help text
  (`device_detail.html`, "Music may use mobile data: ...") gains: "This includes Storytel books, often several hundred
  MB each." (QA L14; the main session's decision: Storytel follows the parent's switch like every other download).

### 2.4 Entries and the listing (`music.rs`, `music_sweep.rs`)
- `Link` gains `Storytel { kind, id }`; `music::entry_link(source, target)` returns it for a `storytel` entry (and
  `parse_link`'s result for the others); `schedule_one`, `check_entry`, `run_check`, `cover` and every `match link` use
  it. `Link::target()` is the grammar's string, `source()` "storytel". `parse_link` (pasted links) is unchanged.
- **Every place that filters to NRK/RSS today takes Storytel too** (QA M3): `music_sweep::schedule`'s `WHERE e.source
  IN ('nrk', 'rss')` (else a Storytel entry is never scheduled), `check_entry`'s source check, `device_listing`'s `IN`,
  `handlers::music`'s `SweepView::card` (the status, "N books", the per-phone lines), `check_now` (today "Only NRK and
  RSS entries are checked"), `cards` (`GET /music/cards`) and `save_offline` (§2.3); plus §2.7's three gates.
- **The Storytel check** (vibb `sync` minus the downloads): `Account::shelf(300 s)` - so a pass with several Storytel
  entries fetches the shelf once (vibb `tests/storytel_sync.py` 6b) - then the group whose target is the entry's. Not on
  the shelf -> the check fails `not_on_shelf` and the last good list stays (21b §2.4; vibb leaves the old `shelf.json`).
  Else the list **is the group as it is now**: one item per book in reading order (`seq` = the group's order), key =
  `consumable_id`, title (`cut_title`), `state 'ok'`, `url NULL`, `hls 0`, `duration_ms`, `art_url` = the book's cover
  when `media_url_ok`; a book that left the shelf leaves the list (no `gone`: Storytel books aren't episodes that were
  withdrawn, and the phone prunes what the list no longer names). Every book is listed - locked and geo included (vibb
  1b: the server decides at download). The listing's `title` = the group's name, `cover_candidate` = the group's cover
  (fetched by 21b §2.6's machinery, `Reach::Public` plus `public_media`), **`keep_end = 'first'`** - `finish()` and
  `device_listing` derive the anchor from it, so a cut drops the end (QA L2; `effective_order` reacts only to `/serie/`
  targets, so the order is untouched) - and `capped` unset. Requests count against the check's budget like any other.
  A routine check reads `Account::shelf(300 s)`, **Check now `shelf(0)`** (§2.1). **The cooldown first** (code QA of
  2a): `Account::shelf` falls back to the last good shelf of this generation on *every* fetch error, `Refused` and
  `Cooldown` included (vibb `bookshelf_cached` too), so the Storytel check reads the row's cooldown
  (`storytel::credentials(..).cooldown_until(now)`) before calling `shelf` and fails `login_refused` without a request
  while it is active; otherwise a kept shelf would hide the refusal until a restart or a save. **The sweep's read never
  falls back to the kept shelf** (`Account::shelf_counted`; code QA of 2b, M1): a refused login after the cooldown, an
  outage or a bad answer fails the check with its code (`login_refused`, `network`, `http_<code>`, ...) and the last
  good list stays (`finish`), so the card never says "Checked" over a refusal. Saving the login makes due at once
  every Storytel entry failing `storytel_off`/`login_refused`/`storytel_key`/`no_jwt` since before the save, and
  wakes the sweep (L1).
- Cadence: `interval()` already gives a non-NRK entry the setting `I` (vibb sweeps Storytel with everything else);
  Check now works as for NRK/RSS. A login failure fails the check (`login_refused`, `storytel_off`) and backs off;
  inside the cooldown it costs no request.
- `flag_reports` skips Storytel entries (there is nothing to re-resolve; a mint failure is the phone's, §4.3).
- `entry_error_text`: `not_on_shelf` "no longer on the Storytel shelf", `login_refused` "Storytel refused the login",
  `storytel_off` "no Storytel login on this server", `no_jwt`, `too_big`, `bad_shelf`.

### 2.5 Status (`music.rs`)
- `item_error_code` also accepts `no_audio` (the mint got no audio URL: locked, not in this country, gone), shown as
  "Storytel gave no audio for this book".
- `Download.waiting` also accepts `login` ("waiting for the Storytel login on the phone").
- `music_state.storytel` stays a word: the phone sends `none`, `ok` or `refused` (§4.5).
  `server/testdata/music_state.json` is unchanged (every field is already there); the sanitizer test gains the two new
  values.

### 2.6 The import (`music_import.rs`, design 21a)
`Skip::Storytel` goes: a `storytel:` target that `parse_target` accepts imports as a Storytel entry (name, order,
resume; cache 0 -> -1, anything else valid kept); one it refuses is `Skip::BadLink` as before. Nothing is fetched: the
sweep lists it (and reports `storytel_off` without a login).

### 2.7 What a phone gets
- **The library** (`LIBRARY_ENTRIES`): its `e.source IN ('nrk', 'rss', 'own')` gains `'storytel'`, and Storytel entries
  are included **only while this phone's `music_storytel` switch is on and a login is stored** (`ciphertext IS NOT
  NULL`): `AND (e.source != 'storytel' OR EXISTS (SELECT 1 FROM device_policy p, music_storytel s WHERE p.device_id = ?
  AND p.music_storytel = 1 AND s.id = 1 AND s.ciphertext IS NOT NULL))`. So the switch off, or the login cleared, drops
  them from the phone's library - and phase 1's prune deletes the books (vibb: the books go with the account).
  `LibEntry` for Storytel: `source "storytel"`, `target`, `cache` (0 sent as -1), `items` = the listing version, `cover`
  = the parent's or the listing's, `lan false`.
- `device_listing` and `cover_in_library` use the same condition (an entry the phone can't have is a 404).
- Revision triggers (migration, §2.8) on the switch and on the login's presence, so the cached library is rebuilt.
- The policy's `storytel_generation` is unchanged (phase 1).

### 2.8 Migration `0072_music_storytel.sql` (renumbered at the merge of music-1b-sonos and main: main holds 0065-0069, 0070 is reserved for design 27, 1b holds 0071)
```sql
-- The server's own Storytel session (design 26b §2.1): the refused-login cooldown (vibb
-- storytel-login-refused.json) for the login generation it refused, and the card's last outcome
-- (a code, never text: `login_error`, with its time `login_error_at`).
-- Also from 0072 on: a Storytel entry's music_items rows are state 'ok' with url NULL (listed and
-- playable; the phone mints the audio URL), which 0051's comment ("ok (it has a URL)") predates.
--
-- Written as 0066 on the branch `music-2-storytel`; renumbered 0072 at the merge of music-1b-sonos
-- (with main) on 2026-10-12: main's 0065-0069 are the security wave's and design 25's, 0070 is
-- reserved for design 27 by the main session, 0071 is 26a's Sonos rooms. If 0070 or 0071 reaches a
-- server after this one ran, sqlx applies it out of order and `startup::pre_migration_backup`
-- (MAX(version) only) takes no backup first: 26a's and design 27's servers ship before or together
-- with this one (server/CLAUDE.md, "Data model").
ALTER TABLE music_storytel ADD COLUMN login_refused_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_refused_generation INTEGER;
ALTER TABLE music_storytel ADD COLUMN login_ok_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_error TEXT;
ALTER TABLE music_storytel ADD COLUMN login_error_at TEXT;
-- A phone's library holds Storytel entries only with its switch on and a login stored (§2.7).
CREATE TRIGGER device_policy_storytel_revision AFTER UPDATE OF music_storytel ON device_policy
WHEN OLD.music_storytel IS NOT NEW.music_storytel
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER music_storytel_presence_revision AFTER UPDATE OF ciphertext ON music_storytel
WHEN (OLD.ciphertext IS NULL) IS NOT (NEW.ciphertext IS NULL)
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
```
`music_entries.source` already allows `storytel` (0049). `music_items.state 'ok'` with `url NULL` is new usage, not a
schema change: the note goes into 0072's header (above) and `music_sweep::Item`'s doc. **Applied migrations are never
edited** (QA H1): sqlx checks the checksum of every applied file, so one changed comment in 0051 stops every upgraded
server at start, and no test catches it (they all run on fresh databases). Stamps are `Sweep::now()` text (the tests'
fake clock). The JWT and the shelf are never stored.

## 3. The phone: data and rules (`M/model`, `M/logic`, `M/data`; pure)

### 3.1 The library
`Source.STORYTEL` (`"storytel"`): the target must pass `storytelTarget`, `cache` 0 is read as -1 (vibb 87-89), `items`
as for NRK/RSS, `lan` false. An older app keeps dropping it (`else -> return null`) with its id kept, so nothing is
pruned (qa-21-step2-code H1). `publicOnly(entry)` is true (Storytel's API and CDN are public).

### 3.2 Visibility and order (`Snapshot`, `logic/PlayOrder.kt`, `logic/Storytel.kt`)
- `naturallyNewestFirst(STORYTEL) = false` (book 1 first); `order` settings apply as for the others.
- **Visibility follows the renderer** (vibb `entries_for(remote)`, `PREFER_REMOTE`): `Snapshot.forRenderer(remote:
  Boolean)` returns a copy whose `visible`, `order` and `entries` use it. A Storytel item is visible **locally only with
  a copy on the phone** (download-only: never streamed from an expiring URL - phase 1's url-null rule already says so,
  since a Storytel item has `url == null`) and **on a Sonos room always** (the speaker fetches from the CDN). Every
  other source is unchanged. Callers pass the renderer explicitly (26a §9): the service at each call site named in §5.6
  (adopt and phone -> room included, QA H2), the UI from `PlayerHub.State` (the shown output is a Sonos room), the
  standalone suspension `true`, the Nytt notifier `false`.
- "N av M lastet ned" and the list's offline marks keep phase 1's rules over the visible items (on a room they show what
  is on the phone).

### 3.3 Keep, prune, downloads (`logic/Offline.kt`, `logic/Storytel.kt`)
- **Fetchable**: a listed item can be downloaded when it has a `url`, or when its entry is Storytel (minted). `KeepItem`
  gains `fetchable`; `wantedDownloads` and the plan's `wantable` count use it instead of `hasUrl`. Visibility is not
  fetchability: `visibleKeys` keeps `url != null || copy` (§3.2).
- **Keep set for Storytel** (vibb `sync`: `books[:count]`): the **first** `cache` books of the reading order (the
  listing order, whatever the entry's play order), all for -1; plus the bookmarked book and the next in play order
  (phase 1's rule for every entry). Download order: the bookmarked book first, then reading order (vibb `for b in
  books`), not newest first.
- **The source id** `sourceId(entry, item)`: the item's url, or `storytel:book:<cid>` for a Storytel book. The download
  row's `url`, the failure's `failedUrl` and `mayRetry` use it, so a minted URL (new every attempt) never reads as "a
  new url" and never discards a partial copy (§4.3).
- **Retry** (QA M7): a Storytel `no_audio` is permanent (a day, `permanentFailure`); a Storytel `http_<code>` - from the
  asset call (401 after the re-login, 408, 429, 5xx) or from the CDN (any status) - is transient (2 min doubling, at
  most 6 h): every attempt mints a fresh URL, so a 403 from an expired token heals on the next attempt. A failure after
  the partial copy grew by at least max(1 MiB, 1 %) since the last one counts as the first again (`grewEnough`; the
  main session's review of step 3, Low 1, with qa-26b-merge-code Low 2's floor), for every source: a slow but
  progressing download stays at 2 min, a host that drips a few KB and resets still backs off.
- **Room**: `STORYTEL_FREE_FLOOR` = 1.5 GB (vibb `DISK_FLOOR`, which is Storytel's own: `storytel.py:701, 789-791`): no
  Storytel book starts below it (the Storytel books of the run stop, `waiting: storage`); NRK/RSS downloads keep
  phase 1's rule (storage-not-low).

### 3.4 NY and Nytt
`applyListing`'s news eligibility (`items with url != null`) is "fetchable" instead, so a new book in a Storytel series
joins the tray (vibb `_mark_new_seen(newest_book_id)`); the first listing of an entry acknowledges everything (phase 1).
The notification counts and the Nytt rows show only visible items (phase 1), so a new book appears there once
downloaded; the NY badge follows the tray (phase 1).

### 3.5 Words (`logic/LibraryView.kt`, strings in `values` and `values-nb`)
`Kind.AUDIOBOOK` for Storytel: `kind_audiobook` "Lydbok" / "Audiobook"; the hint `hint_part` "%1$s · del %2$d av %3$d" /
"%1$s · part %2$d of %3$d" (design 20: "Lydbok · del 3 av 5"); the player's line `part_of` "Del %1$d av %2$d" / "Part
%1$d of %2$d"; the Nytt row `new_book` "Ny bok" / "New book". Shuffle stays off (`canShuffle`: own files only).
**For Storytel, N, M, `started` and the thin overall-progress line - and the player's "Del N av M" - count the series'
reading order (`snap.listed`, every book), not the visible list** (QA M5): book 3 of 5 with books 1 and 3 downloaded
reads "del 3 av 5", and a bookmark on a book not on the phone (heard on a room) still reads as started with its line.
Resume itself is phase 1's (the queue is the visible books).

## 4. The phone: its Storytel client, downloads, playback

### 4.1 `M/storytel/StorytelApi.kt` (pure, Android-free; `StorytelApiTest`)
```kotlin
// M/storytel/StorytelHttp.kt - the seam and its types (step 1); each prints redacted (L9).
/** The one HTTP seam (vibb `_request`): blocking, any status returned, IOException = unreachable. */
fun interface StorytelHttp { fun send(request: StorytelRequest): StorytelResponse }
data class StorytelRequest(val method: String, val url: HttpUrl, val headers: List<Pair<String, String>>, val follow: Boolean, val timeoutMs: Long)
class StorytelResponse(val status: Int, val location: String?, val body: ByteArray) // body capped at 64 KB; plain class (a ByteArray)

// M/storytel/StorytelApi.kt

sealed interface Mint {
    class Url(val url: String) : Mint               // the signed CDN URL: used once, never stored or logged
    data object NotConfigured : Mint                 // no login on the phone (or it can't be opened)
    data class Refused(val untilWallMs: Long) : Mint // refused now or inside the cooldown
    data class LoginFailed(val reason: String) : Mint // login unreachable, timed out, or 200 without a JWT: no cooldown
    data class NoAudio(val status: Int) : Mint       // 3xx without Location, 403, 404, 410, 451: item error `no_audio`
    data class Failed(val status: Int) : Mint        // the asset call: 401 after the re-login, 408, 429, 5xx, other (a non-3xx with a Location too): `http_<status>`
    data class Unreachable(val reason: String) : Mint // the asset call's transport failure or timeout: `network`
}

class StorytelApi(
    http: StorytelHttp,
    login: () -> StorytelLogin.Login?,   // phase 1's sealed login (generation, email, password)
    cooldown: CooldownStore,              // refusedAt(generation): Long?, setRefused(generation, wallMs)
    elapsedMs: () -> Long, wallMs: () -> Long,
    endpoints: Endpoints = Endpoints.STORYTEL,
) {
    /** [timeoutMs] bounds the asset request; [deadlineMs] (elapsed time) bounds the whole mint, login included. */
    fun assetUrl(cid: String, timeoutMs: Long, deadlineMs: Long): Mint
}
```
- Constants as vibb's block (`LOGIN_URL`, `API`, `ASSET = /assets/v2/consumables/{cid}/abook`, the AES key and IV,
  `JWT_TTL_MS = 3_600_000`, `LOGIN_COOLDOWN_MS = 3_600_000`), with the UPSTREAM note.
- `encryptPassword(pw)`: `Cipher.getInstance("AES/CBC/PKCS5Padding")` (PKCS#5 in JCA is PKCS#7 for AES), UTF-8 in,
  UPPERCASE hex out.
- `login()`: the cooldown first (`CooldownStore.refusedAt(generation)`: `0 <= wall - at < 1 h` -> `Refused`, **no
  request**); then GET `<LOGIN_URL>?m=1&uid=<email>&pwd=<hex>` built with `HttpUrl.Builder.addQueryParameter` in that
  order, follow on, 20 s (less when the deadline is nearer). Not 200 -> `CooldownStore.setRefused(generation, wall)` (a failed
  write is swallowed: a mint never throws) and `Refused`; 200 without a non-empty `accountInfo.jwt` -> `LoginFailed("no jwt")` (no cooldown); `IOException` or the
  deadline -> `LoginFailed` (no cooldown). The JWT is kept **in memory only** with `elapsed + 1 h` and the login's
  generation.
- `bearer(force)`: the kept JWT unless forced, expired or of another generation; else `login()`.
- `assetUrl(cid, timeoutMs)`: GET `API/assets/v2/consumables/<cid>/abook`, `Authorization: Bearer <jwt>`, `Accept:
  */*`, **follow = false**; a 401 -> `bearer(force = true)` and one retry (a second 401 drops that JWT and stops the
  run, `mintStopsRun`); then **on a 3xx only** a `Location` that `StorytelApi.mintedUrl` accepts (OkHttp's
  `toHttpUrlOrNull`, http(s), a host, no userinfo, at most 8 KB; the canonical form) is `Url`; else the status decides
  (QA M7): a 3xx without a usable `Location`, 403, 404, 410 or 451 -> `NoAudio(status)` (locked, geo, gone: permanent
  for a day); 401 after the re-login, 408, 429, 5xx and anything else (a 4xx/5xx carrying a Location included) ->
  `Failed(status)` (transient); any exception from the seam or the deadline -> `Unreachable`. The deadline bounds the
  whole call (login included). A cid outside `[A-Za-z0-9_-]{1,64}` is `NoAudio(0)` without a request.
- **The login lock** (QA L6; step 1): a `ReentrantLock` in `StorytelApi`, taken with `tryLock` for what is left of the
  deadline; under it the stored login and the kept JWT are read again, so a waiter is served by the login it waited
  for; an asset call holds no lock. `state()` (`none`/`refused`/`ok`) is here too.
- Endpoints: `Endpoints.STORYTEL` (the real hosts); the debug feed may point both at the fake (§4.6).
- `M/storytel/StorytelHttp.kt`: `OkHttpStorytelHttp(client: OkHttpClient, userAgent: String)` - the client is a
  parameter (QA M2): `StorytelAccount` passes `MediaCache.guarded(...)` (the public-only client: Storytel's API is
  public), the debug switch (§4.6) the LAN client, `StorytelHttpTest` a plain client against `TinyHttp` on 127.0.0.1.
  `followRedirects(false)` + `followSslRedirects(false)` for `follow = false`, the call timeout per request, the body
  read up to 64 KB, the `User-Agent` set on every request (`VibbMusic/<version>`: MediaCache sets it on its
  `OkHttpDataSource.Factory`, not on the client, so without it the API would see `okhttp/x`; QA L8). Never a logging
  interceptor.

### 4.2 `M/storytel/StorytelAccount.kt` (Android)
The process-wide account: `StorytelApi` over `StorytelLogin.load(context)` (phase 1's Keystore-sealed login, read per
login), a `CooldownStore` in `noBackupFilesDir/storytel_refused.json` (`{generation, at}`, tmp + rename), the clocks
`SystemClock.elapsedRealtime` and `System.currentTimeMillis`. The lock around the login and the JWT it guards lives in
`StorytelApi` (§4.1, built in step 1; the download worker and a Sonos actor never log in twice at once; an asset call
holds no lock); its wait counts against the caller's deadline (a download's login may hold it up to 20 s; a Sonos mint
then fails at its 10 s, QA L6). **Two conditions for step 3** (qa-26b-step1-code): `StorytelAccount` holds **exactly
one `StorytelApi` for the process** (the lock and the JWT live in the instance; one per worker run, or per read of
`debugBase`, would lose both and log in every run), and `mint` is `runInterruptible(Dispatchers.IO) {
api.assetUrl(...) }` (the lock blocks a thread for up to the deadline; interruptible since the main session's review of
step 3, Low 2: a download job WorkManager stops ends its lock wait or request at once. A room's actor never cancels a
job - a superseded Sonos play is dropped by its token and a running mint finishes, qa-26b-merge-code Low 1). The one signature, reached through `StorytelAccount.get(context)` (the process's
instance): `suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint` (IO) - downloads `(15 s, now + 60 s)`, the Sonos path `(6 s, now + 10 s)`. `fun state():
String` delegates to `StorytelApi.state()` (off main: it opens the sealed login) = `none` (no login stored) /
`refused` (the cooldown runs for the stored generation) / `ok`. A new generation from the bridge needs no call: the JWT and the
cooldown are per generation (vibb `save_credentials` clears both). Generation 0 deletes the login (phase 1); the same
sync's library has no Storytel entries any more (§2.7).

### 4.3 Downloads (`M/sync/DownloadPlan.kt`, `Downloads.kt`)
- The plan (§3.3): Storytel books are fetchable; the keep set is the first N in reading order plus the bookmark and the
  next; the bookmarked book first, then reading order; `sourceId` in the rows and in `mayRetry`.
- `DownloadWorker.fetchOne` for a Storytel book, **one book at a time** (vibb's radio budget; the worker is sequential
  already):
  1. `storytelRoom(free space of noBackupFilesDir)` - below 1.5 GB the run stops (`waiting: storage`).
  2. `StorytelAccount.mint(cid, 15 s, now + 60 s)` - **a fresh URL every attempt** (vibb `sync`: the token is
     single-use and short-lived; the path is stable). `NotConfigured`, `Refused` or `LoginFailed`: no Storytel book is
     tried again this run (vibb's `sync` stops on a login failure: it catches only `OSError`, `storytel.py:802-807`),
     no item error; after `NotConfigured` or `Refused` the entries report `waiting: login` (§4.5) and the report's
     `storytel` says why (`none`, `refused`), while a `LoginFailed` run (unreachable, no JWT) reads as the shared rule
     says (M4); `NoAudio`: item
     error `no_audio` (the status goes to the log only), the next book goes on (vibb: a genuine failure skips that
     book); `Failed(status)`: item error `http_<status>` (transient); `Unreachable`: item error `network`.
  3. `Downloads.fetch` with the minted URL: `ProgressiveDownloader`, `customCacheKey = itemCacheKey(entry.key, cid)`,
     `MediaCache.downloadFactory(publicOnly = true, ...)`. MediaCache's clients never add an `Authorization` header, so
     no bearer reaches the CDN (vibb's rule; `SourceScanTest`).
  4. The row's `url` is `sourceId` (`storytel:book:<cid>`): phase 1's "a partial copy from another url may be another
     file" (qa-21-step2-code L7) compares source ids, so the partial survives a re-mint and the next attempt resumes
     with Range from the cached bytes against the new token. `http_416` drops the partial and fetches once from 0
     (phase 1) **with a freshly minted URL** - phase 1's retry reuses its URL, and vibb treats the token as single-use
     (`storytel.py:799-801`; QA L7). An interrupted download (the network gate, WorkManager stopping a job at its
     10-minute limit, a dropped connection) leaves the partial; the next job mints again and continues - design 20 §5's
     "a 300 MB book over interrupted Wi-Fi resumes after its URL expires".
- Gates unchanged: UNMETERED unless the parent's mobile-data switch, storage not low, never roaming (phase 1). **With
  the switch on, Storytel books download over mobile data like every other download** (the main session's decision on
  QA L14: the parent chose it; design 20 decision 4 says "downloads and streaming"). A book is often several hundred MB,
  so the switch's help text on the device card says so (§2.3), and `music/CLAUDE.md` notes it.
- A book the listing no longer names (it left the shelf) and every book of an entry the library dropped (switch off,
  login cleared, entry deleted) are pruned by phase 1's rules.

### 4.4 Local playback (`M/playback/PlaybackService.kt`, `M/media/`)
A Storytel book plays **only from its copy** (vibb: download-only; the play path never dials out): `mediaItem` gives it
the uri `storytel:book:<cid>` (the source id; no network scheme) and `ItemTag(cacheKey, hls = false, publicOnly = true,
copyOnly = true)`; `ItemMediaSourceFactory` builds a `CacheDataSource` with **no upstream** for `copyOnly` (Media3 then
uses a placeholder that fails to open), so a missing part is an error, never a request. Phase 1 already lets an item
with `url == null` play only with a copy (`playable`) and gives a copy `WAKE_MODE_LOCAL`. Positions, resume, done marks,
the sleep timer, calls: phase 1/1b, unchanged. `audioAttributes(speech = entry.resume)`: speech for a book.

### 4.5 The report (`M/logic/Report.kt`, `M/sync/Sync.kt`)
`storytel` = `StorytelAccount.state()` (`none`, `ok`, `refused`). Never the email, a JWT or a URL. `item_errors` for
Storytel books: `no_audio`, `network`, `http_<code>` (the asset call's transient statuses and the CDN's).
**`waiting` per Storytel entry** (QA M4): `Sync.writeReport` computes one `waiting` for every entry today (the network
caps and `LOW_STORAGE_BYTES` 500 MB); a Storytel entry with books still wanted gets the pure
`storytelWaiting(account, freeBytes, shared)` instead: `login` when `StorytelAccount.state() != "ok"` (no login, or
refused and waiting out the cooldown), `storage` below the 1.5 GB floor, else the shared rule (a `LoginFailed` run is a
network matter and reads as the shared rule says). `ReportTest` pins each. §4.3's "`waiting: login`" means this.

### 4.6 The emulator: a fake Storytel (`scripts/fake-storytel.py`, debug feed)
No account can be used in the sandbox or by QA, so the chain is exercised against a fake, built like
`scripts/fake-sonos.py` (GPL, Python 3 stdlib, `-I`, `--selftest`), answering from the **same fixtures the JVM tests
read** (`music/app/src/test/resources/storytel/`: `login_request.json` with the email, password and its `pwd` hex,
`login_ok.json`, `login_no_jwt.json`, `asset_302.json`, `asset_locked.json`; stdlib Python has no AES, so the fake
compares `pwd` with the fixture's hex):
- `/api/login.action` (200 with `FAKE-JWT-1`; `--refuse` = 403; `--no-jwt`), `/assets/v2/consumables/<cid>/abook`
  (the bearer checked; 302 to `http://10.0.2.2:<port>/cdn/<cid>.wav?consumableId=<cid>&token=FAKE-SIG-<n>`; a cid in
  `--locked` = 403 without Location), `/cdn/<cid>.wav` (a generated silent WAV of `--books 111:300,222:5` MB, Range, the
  token valid `--token-ttl` seconds after its mint, then 403; `--drop-at 40` closes each book's first transfer at 40 %;
  `--slow-mint 5` delays every 302 by 5 s, for step 4b's mint-in-flight checks).
- **One debug-only switch** (QA M2; the fake is on `10.0.2.2`, a private address the address guard refuses):
  `StorytelEndpoints.debugBase: String?` in main code, `null` unless the debug feed's `config.json` sets
  `"storytel_base": "http://10.0.2.2:8730"`. Exactly four readers, all of that one field: (a) the login and API
  endpoints point at it; (b) `StorytelAccount` builds `OkHttpStorytelHttp` over `MediaCache`'s LAN client instead of the
  guarded one; (c) Storytel downloads use `publicOnly = false`; (d) §5.1's minted-URL check accepts a private literal.
  `SourceScanTest` pins that only `src/debug` assigns it and that nothing else reads it; a release build has no feed
  (`checkReleaseHasNoDebugFeed`), so it stays `null` there. The four readers are functions in `StorytelEndpoints.kt`
  (`endpoints()`, `apiOverLan()`, `publicDownloads()`, `privateMintAllowed()`), and they take the base **only for
  `10.0.2.2` or loopback** (the fake's homes; a `config.json` left behind on a real phone running a debug build must
  never send the real login over http to another host): anything else means Storytel's hosts. The receiver applies
  it, and so does `DebugStartup` (a debug-only, non-exported provider with `android:initOrder="1000"`, created before
  WorkManager's) at every process start - a download job often runs in a fresh process, where a receiver-only switch
  would be null and the fake's login would go to the real Storytel.
- `storytel.json` `{"email", "password"}` seeds a login (the next generation: 1 on a fresh install, and a re-push ends
  a refused login's hour like a new save) for a run without a launcher - **fake values
  only** (`login_request.json`'s `k@example.invalid` / `hemmelig`), and the receiver deletes the file once read (it sits
  on shared external storage; QA L13); a malformed file logs one fixed line, never the parser's message (which quotes
  the input). The library and the listing come from `server/testdata/` (the snapshot's Storytel
  entry, §8 step 2b) or by hand, as §6f.
- `music.yml` watches `scripts/fake-storytel.py` and runs its `--selftest` after the build.

## 5. Storytel on Sonos (1b's renderer axis)

### 5.1 The body and the late mint (`PlayBody.kt`, `SonosController.kt`)
- `PlayBodies.speakerDropReason`: a Storytel item is speaker-playable, downloaded or not (vibb's exemption of the
  http-only filter, 1693-1699); everything else as 1b.
- `PlayBodies.build` for a Storytel item: `PlayBody(kind = URL, uri = "", mint = cid, title, album = entry.name, no art
  (design 27: the listing carries a hash the server serves only to the phone's bearer - 26a §9; vibb sends `art_url`),
  durationS = the listing's)` - the shelf's length rides along, since a speaker reads 0:00 for an
  extension-less signed URL (vibb 9).
- `SonosController.playIndex`'s actor job resolves the mint **inside the job, right before** `transport.play` (vibb:
  "milliseconds before the SOAP /play"): `StorytelMinter.mint(cid, 6_000, elapsed + MINT_BUDGET_MS)` - the account's
  one signature (§4.2): the asset call 6 s (vibb's Sonos timeout), the whole mint, the login and the login `Mutex`'s
  wait included, within `MINT_BUDGET_MS = 10_000`; a `Url` (already through `StorytelApi.mintedUrl`, §4.1) that isn't a private literal (unless the
  debug switch, §4.6 (d)) becomes `body.copy(uri = url, mint = null)`; anything else fails the job with
  `StorytelUnavailable(code)`, which is 1b's failed play (a first start is Lost: "Kan ikke spilles på Sonos", the phone,
  paused; a step or an advance is dropped). **One mint per play** (vibb 5: two mints meant a second failure after the
  speaker played); **never an exception out of the actor** (vibb 4).
- **The play is gated again after the mint** (QA M6): the mint adds up to 10 s between `playIndex`'s `mayPlay()` (on
  main) and the SOAP, during which a newer play, a release or a suspension can arrive. The job carries a thread-safe
  token - the generation it was queued at (an `AtomicLong` the controller bumps with `gen`), the renderer's `closed`
  (`@Volatile`, set by `abandon`/`close`/`release`) and the `mayPlay` lambda (`playAllowed(suspendedNow())`, safe off
  main) - and checks it **before the mint** (a superseded job doesn't mint at all) and **again after the mint, before
  `SetAVTransportURI`**; a failed check ends the job as superseded (no SOAP, no failed-play path). So a step during a
  mint gives one `SetAVTransportURI`, and a play queued before a suspension or `onDestroy` never sounds after it (the
  actor's drain, `SonosActor.DRAIN_MS` 30 s, outlasts the standalone suspension's 5 s wait for the tail; the token
  makes the drained job a no-op). `DRAIN_MS` 30 s still covers a mint (10 s) plus a hushed play with its settled seek
  (~10 s), so it stays.
- `PlaybackService.HANDOFF_MS` 15 s -> 25 s: a hand-off can now queue behind a play that mints. On the service path that
  is longer than the suspension receiver's `goAsync` budget (`SUSPEND_BUDGET_MS` 9 s, `MusicApp.kt`): the receiver
  finishes, the service's `suspendStop` runs on in its own scope (1b), and the token above keeps a queued play silent.

### 5.2 Re-minting: exactly vibb's
A URL is minted for every play the controller sends - a start, a full start from the tile, play on a STOPPED or
not-ours speaker (vibb 16), a step, a natural advance, phone -> room, room -> room - and **never** for a resume from
PAUSED (`Play`), the stall retry (1b: `Stop`, `Play`, the settled seek on the speaker's own uri, vibb
`_stall_bookkeeping`), the hush and the settled seek of a play, a seek, the sleep ramp, adopt (zero transport commands)
or a migration follow. A token that expires under a long pause makes the speaker fail on resume; it then reads STOPPED
or lost, and the kid's next play starts from the bookmark with a fresh URL (vibb's heal). [device check]

### 5.3 What is persisted, and adopt (`RendererChoice`, `SonosLifecycle.kt`, `SonosTransport.kt`, `SonosSuspension.kt`)
- `renderer.json`'s `uri` for a Storytel play is the marker **`storytel:book:<cid>`**, never the signed URL (vibb keeps
  signed URLs out of its state files: `entries_for`'s docstring). **Every write of `RendererChoice.uri` goes through
  `storedUri(entry, item, uri)`** (`logic/Storytel.kt`; QA M1): `host.played` (`SonosRenderer.kt:92-94`) and
  `lookAtRoom`'s rewrite after an adopt (`PlaybackService.kt:1224`, which today copies the live uri in - and on its
  no-stored-uri branch, `:1210`, already would); `followed` keeps the stored uri (fine). `GuardsTest` pins that no other
  line constructs or copies a `RendererChoice` with a `uri`; `SonosLifecycleTest` checks renderer.json still holds
  `storytel:book:<cid>` after a Storytel adopt. `Playing.uri` and the session's `uri` (memory) keep the minted URL, so a
  live session is ours by `normUri` equality as in 1b (vibb's sidecar).
- Every reading of the stored room compares through **`sameStream(stored, live)`**: a marker matches when the live
  TrackURI's `consumableId` query parameter (parsed, exact - never a substring: the token can hold another book's
  digits, vibb 7/14) is the marker's cid; anything else is 1b's `normUri` equality. Users:
  `roomVerdict`/`ourLiveSession`, `adoptIndex` (a Storytel queue item whose key is the `consumableId`), the standalone
  suspension, the look before a play (`lookAgainBeforePlaying`), the room scope's leave.
- **Adopt takes the session uri from the live TrackURI** (vibb posts `snap.uri` to `/adopt`), so the polls after an
  adopt are ours by `normUri`; for NRK/RSS nothing changes (the two are equal). A live URI without `consumableId` is not
  ours: nothing is adopted, the phone, paused (1b). [device check: Storytel's Location carries it]
- The migration follow's hint carries the session's (minted) uri; the new room's `renderer.json` gets the marker.

### 5.4 Durations (`SonosController.onPoll`, adopt)
For a Storytel session the duration comes first from the queue item named by the snapshot's `consumableId`
(`durationByConsumableId`, vibb `_sonos_known_duration`: "authoritative first"), with `durFromListing`; then 1b's carry
(same uri only, a falsy speaker value never erases one). Adopt restores the length the same way (vibb 13). A listing
duration never decides "done" (1b, vibb 19).

### 5.5 Art (server: `S/music_sweep.rs`, `S/music.rs`; step 4a - rebuilt on design 27 at the merge of 2026-10-12)
A book's own cover reaches the phone the way design 27 brings an NRK/RSS episode's picture: the server's art pass
fetches it (the book's `art_url`, the shelf's cover) into `photos::MUSIC_ART` and the listing's item `art` is its
64-hex hash; the phone's want set (`sync/ArtPlan.kt`'s `artWant`, a Storytel series' kept books in download order
since the merge) sends it as `wantArt`, the launcher's `syncArt` fetches it into the shared covers dir, and every
surface shows `artHash ?: entry.cover` (the carousel, the player's art, the session's artwork, `setNowPlaying`, Nytt,
search). **A book is judged against nothing** (qa-26b-step4a-code M1): design 27's `same` ("the series picture",
show the entry cover) is wrong for books - a Storytel series' cover is only the shelf's first-seen book's, so a
`same` book would show another book's face once that moves, under a parent's cover, or with templated covers. Every
fetched book cover is `own` with its own hash (no reference prints, no step 1, no twin merge; one request more per
series). **The phone needs no change and Room no column** (design 27 owns Room version 2, `items.artHash`). **The
speaker gets no art** for any source (26a §9: a hash has no LAN-reachable http URL; the cheapest later path, a frozen
`art_src` beside the hash, fits Storytel's public cover URL unchanged). The abandoned first 4a (its own `items.art`
URL column and its own Room version 2, branch `abandoned/26b-step4a`) is not applied.

### 5.6 Visibility on a room, and coming home (`PlaybackService`)
`load(kind)` returns `store.snapshot().forRenderer(kind == SONOS)` (vibb `PREFER_REMOTE`, set by the play path itself,
1687; 26a §9's "renderer passed explicitly"). **Every call site, with the renderer it passes** (QA H2):

| Call site | Snapshot | Why |
|---|---|---|
| `playEntry`, `playItem`, `restore`, `resumeLast`, `restoreThen` | `renderer.kind` | the queue for whoever plays now |
| `queueFor` / `activeFor` | the `kind` they are given | as today, never re-read from the renderer |
| `lookAtRoom` and `adoptedQueue` (adopt at start) | `true` | the stored room is a room: an undownloaded book playing there must be found, or `lookAtRoom` sends `stopUnadopted()` and stops the kid's book |
| `toRoom` (phone -> room) | `true` | for a Storytel entry it **re-plans over the entry's remote order** (`homePlan`-like: the current item first, at its position), never `a0.items`; other entries keep 1b's `a0.items` plan (their local and remote visibility are the same, and a shuffled queue keeps its order) (the local queue holds only the downloaded books, so the room would skip the others and end early, where 1b's natural end clears the partial positions) |
| `comeHome`'s `homePlan` (room -> phone) | `false` | it is the phone |
| `SonosSuspension.entryMatcher` / `bookmark` (standalone) | `true` | the stored room |
| `leaveDisallowedRoom`, `lost` | `false` for the queue the phone takes | it comes home |
| the UI (`MusicViewModel`) | the shown output is a Sonos room | |

**Room -> phone** (1b §4.6) with the current book not on the phone: the hand-off and its bookmark as 1b, then the phone
**paused**, no queue started, "Ikke lastet ned - trenger Wi-Fi" (`NOT_DOWNLOADED`) - not another book from 0 (§9).
Tests: a Storytel adopt of an undownloaded book keeps playing (no Stop); phone -> room with books 1 and 3 of five
downloaded puts all five on the room, at the current book and place.

### 5.7 Logs
Every Sonos log line that prints a uri goes through `redactUri` (scheme, host and path; the query - the token - cut):
the "not-ours?" line in `SonosSession.classify` today.

## 6. Phone-specific rules

1. **Secrets** (§0): `SourceScanTest` pins that `Authorization`/`Bearer` appear only in `M/storytel/StorytelApi.kt`; the
   asset client sets `followRedirects(false)` and `followSslRedirects(false)`; no log call in `M/storytel/` or `M/sync/`
   interpolates `password`, `email`, `jwt`, `bearer`, `token`, `location` or a minted url;
   `fallbackToDestructiveMigration` appears nowhere.
2. **Network.** Storytel's API and CDN through the public-only client; downloads Wi-Fi-only unless the parent's switch
   (phase 1). The launcher's `KidVpnService` filters DNS: `www.storytel.com`, `api.storytel.net` and the CDN host must
   not be on a blocklist the family uses. [device check]
3. **Background.** Downloads stay WorkManager jobs (21 QA #1: never a dataSync FGS); a long book spans several jobs and
   resumes in each (§4.3). Logins and mints never run on the main thread. A job runs at most 10 minutes; with the screen
   off only in Doze's maintenance windows, and under App Standby's job quotas if the app falls to the rare or restricted
   bucket (the launcher has seen WorkManager not fire overnight, `launcher/CLAUDE.md`). Whether a multi-GB series gets
   onto the phone in a reasonable time is measured, not assumed: the device check "Downloads under Doze" (QA M9). If it
   stalls, that is a user decision (downloads only while charging, or design 19b's battery exemption for the music app).
4. **Storage.** The 1.5 GB floor and storage-not-low. A 20-book series is several GB: the parent's "first N" is the
   lever (vibb's choice set).
5. **Kiosk.** Nothing opens: no Storytel app, no links, no web views (`SourceScanTest`, phase 1).
6. **Accounts and sessions; later: a login per phone (Storytel Family), designed, not built.** Storytel allows one
   listening at a time per account; two kids listening at once need Storytel Family, one login per kid. Server only, one
   step, one migration: `music_storytel_logins (id, label, ciphertext, nonce, key_fingerprint, saved_at, generation,
   login_refused_at, login_refused_generation, login_ok_at, login_error)` replacing the singleton (row 1 migrated as
   "Family"); `device_policy.music_storytel_login_id` (NULL = off) replacing the 0/1 switch; `music_entries.
   storytel_login_id` (the shelf it was picked from: the sweep reads that login's shelf, and a phone's library holds
   it only on that login); the picker and the card per login; `storytel_generation` = the phone's login's;
   `GET /api/devices/music/storytel` answers the phone's login; `storytel::Account` keyed by login id. The phone, the
   launcher and the AIDL already handle one login per phone and stay as they are.

## 7. Tests (vibb's tests ported, one row per check; then the new ones)

Fixtures: `server/testdata/storytel/bookshelf.json` (vibb `storytel_api.py`'s `SHELF` - two Kokosbananas books whose
format ids differ from `model.id`, a locked standalone book, an ebook-only row - plus `storytel_sync.py`'s locked third
book, a geo-flagged book and a numeric `model.id`, with `items` keys deliberately **not** in sorted order) and
`login_ok.json`; `music/app/src/test/resources/storytel/` (§4.6, shared with the fake). The AES vectors were printed by
`openssl enc -aes-128-cbc` with vibb's key and IV on 2026-10-12: `"hemmelig"` -> `AC6C4AAACBF142D09469371FC6BB8BE6`
(vibb's own), `""` -> `CC463B52007F99B86F54B5776C8018CF`, `"0123456789abcdef"` ->
`2C9375205F4CAAD6C54EE8424656C741FE9BB4A407D56646E9CD60C083BF84A3`, `"pass æøå"` -> `4A2C4FFEE84E168CEAEB27B612E2EB30`.

| vibb test | Rust | Kotlin |
|---|---|---|
| `storytel_api.py` 1 AES vector, uppercase | `storytel::tests::password_vectors` (all four) | `StorytelApiTest` (all four, `javax.crypto`) |
| 2 target parsing pure, junk refused | `storytel::tests::targets` | `StorytelRulesTest` (`storytelTarget`) |
| 3 bookshelf: POST, body `{"items":[]}`, form content type, bearer | `storytel::tests::bookshelf_request_shape` (CannedFetch's recorded request) | n/a (the phone never reads the shelf) |
| 4 asset 302 captured, never followed, the CDN never fetched | n/a (the server never mints) | `StorytelApiTest` (the seam saw `follow = false`, one request) + `StorytelHttpTest` (two `TinyHttp` servers: the 302 isn't followed, the CDN server sees nothing) |
| 5 a 401 re-logins exactly once and retries once | `storytel::tests::a_401_relogs_once` | `StorytelApiTest` (on the asset call) |
| 6 grouping: `model.id` not the format id, series in reading order, standalone own group, ebook dropped, kids/locked as data | `storytel::tests::normalize_*` (+ first-seen order with unsorted keys, numeric ids, a missing id skipped) | n/a |
| 7-10 outbox, flush, `push_bookmark` (ms, deviceId, action), offline queue | not ported (no mirroring, §9) | not ported |
| 10b a refused login parks a 1 h cooldown, no request; a new password retries at once | `storytel::tests::refused_login_cooldown` (stamp, no request inside, a save's new generation clears) | `StorytelApiTest` (the store's stamp, a new generation clears: the store is keyed by generation, so the old stamp no longer applies; step 3's file may keep one `{generation, at}` record) |
| 11 unconfigured degrades cleanly | `storytel::tests::not_configured` (no login, no key, another key) | `StorytelApiTest` (`NotConfigured`, no request) |
| `storytel_expand.py` 1 only downloaded books, reading order, keyed by id | - | `StorytelRulesTest` (`Snapshot.visible` in step 1; `forRenderer(false)` from step 3) |
| 2 per-book art beats the series cover | the listing's `art` hash per book (step 4a, `music_storytel` tests) | none needed: every surface shows `artHash ?: entry.cover` (design 27); no art to the speaker (§9) |
| 3/4 prune keeps the dir while in the library, removes it once not | - | `OfflineTest` (Storytel copies kept while listed, pruned when the library drops the entry or the list drops the book) |
| 5 `collection_image` local only | n/a (covers come through the bridge, phase 1) | n/a |
| 6 natural order oldest first | - | `StorytelRulesTest` |
| 7 a never-synced target expands to nothing | - | `StorytelRulesTest` (no listing = hidden) |
| 8 newest book = the NY trigger | - | `NewItemsTest` (a new book key joins the tray; eligible though `url == null`; the first listing acknowledges all) |
| `storytel_routes.py` 1 the three POSTs privileged | `tests::music_storytel::routes_need_a_session` | - |
| 2 save validates with a probe, never echoes the password | `tests::music_storytel::save_probes_then_seals` (accepted, refused - old login kept, unreachable - saved with the notice), the page never holds the password | - |
| 3 0600 at rest, the open status leaks nothing | phase 1's sealed-login tests; `the_card_shows_no_login` (no email, no JWT on any page) | - |
| 4 the picker groups the shelf, in-library vs downloaded | `tests::music_storytel::picker_lists_groups` (2a: kids filter, `?all=1`, in-library rows disabled); **2b**: per-phone downloads on the card | - |
| 4b the artwork proxy never serves audio | n/a (the server holds no Storytel audio; the phone's copies are app-private) | - |
| 4c a raw target is privileged | `tests::music_storytel::add_takes_only_shelf_targets` | - |
| 5 logout clears the account and the books; a bad login is refused and forgotten | `tests::music_storytel::clear_drops_storytel_from_libraries` (2a: the revision moves, the session forgotten; **2b**: the library loses the entries) | `OfflineTest` (the dropped entry's books pruned); `BridgeTest` (generation 0 deletes the login, phase 1) |
| `storytel_sync.py` 1/1b every book attempted (the locked flag isn't trusted), a genuine failure skips only that book, the list keeps all | `music_sweep` test: locked and geo books listed | `StorytelDownloadTest` (a `NoAudio` book gets `no_audio`, the next downloads) |
| 2 the URL re-minted per book, the download resumable | - | `StorytelDownloadTest` (one mint per attempt; the row's url is the source id; the partial survives a new URL) |
| 3 a re-sync skips books on disk | - | `OfflineTest` (phase 1's `wantedDownloads`) |
| 4 count N = the first N in reading order | - | `StorytelRulesTest` (`keepSet`: first N + bookmark + next; download order) |
| 5 a low disk stops before downloading, the list still written | - | `StorytelRulesTest` (`storytelRoom`), `StorytelDownloadTest` (the run stops, `waiting: storage`) |
| 6 `_sync_one` runs the right module | n/a (one process, §9) | n/a |
| 6b one sweep fetches the shelf once | `music_sweep` test: two Storytel entries in a pass, one `bookshelf` request | - |
| 7 cache 0 coerced to -1, a podcast keeps 0 | `music::tests::storytel_cache_zero_is_all` (save, import, library) | `StorytelRulesTest` (`LibraryParser`) |
| `storytel_subprocess.py` 1-3 | n/a (no subprocess, §9) | n/a |
| `storytel_bookmark_sync.py` 1-6, 8c (the mirror) | not ported (§9) | not ported |
| 7 a finished book keeps a `done` record holding the duration; 8 it re-taps from 0 | - | `BookmarksTest` 4 and "an item that ends by itself" (phase 1) |
| 9 a completion survives the end-of-queue clear; 10 nothing finished = nothing left | - | `BookmarksTest` "clear_state at a queue's end keeps only the done marks" (phase 1) |
| `storytel_sonos.py` 1 local: only downloaded, local paths | - | `StorytelRulesTest` |
| 2 for Sonos every book; the queue key stays local; the http cover rides | - | `StorytelRulesTest` (`forRenderer(true)`), `PlayBodyTest` (no art - §9; `storedUri` is the marker) |
| 3 one mint, an http cover, a short timeout | - | `SonosControllerTest` (a fake minter: one call with 6 s, the body's uri the minted URL) |
| 4 a failed mint (unreachable, refused) never raises, no uri handed over | - | `SonosControllerTest` (no `SetAVTransportURI`, the failed-play path, the actor alive) |
| 5 the body built once per play | - | `SonosControllerTest` (one mint per play, also for a step and an advance) |
| 6 the http-only filter lets Storytel through | - | `PlayBodyTest` |
| 7 adopt by the `consumableId` parameter, not a substring | - | `SonosLifecycleTest` (vibb's ambiguous token: `331854` adopted, never `331767`) |
| 8 the expand cache cleared on a switch | n/a (no cache: visibility is computed per renderer) | `StorytelRulesTest` |
| 9 the known length rides in the body | - | `PlayBodyTest` |
| 10/10b a zero duration never erases one, never across a track change | - | `SonosControllerTest` (1b's rows + a Storytel row) |
| 10c a short wrong duration would lose the place | - | `SonosBookmarksTest` (1b: `durFromListing` never decides done) |
| 11 pause freezes the bar; 12 the refused-seek hold; 15 the regression guard; 16 play on STOPPED starts from the bookmark; 17 the seek hold; 18 the renderer known at play time; 19 only a speaker duration decides done; 20 a tick never kills the poller | - | ported in 1b (`SonosControllerTest`, `SonosBookmarksTest`), unchanged |
| 13 adopt restores the length from the queue row | - | `SonosLifecycleTest` / `SonosControllerTest` (a Storytel adopt with a speaker 0:00) |
| 14 the length looked up by `consumableId`, scoped to Storytel | - | `StorytelRulesTest` (`durationByConsumableId`: book 2 by its parameter while the token holds book 1's digits, unknown -> null, non-Storytel -> null) + `SonosControllerTest` (the poll prefers it) |

New (no vibb counterpart, from this design):
- Rust: `storytel::tests::no_secret_in_errors` (a failing login over a loopback server: the `FetchError` text holds
  neither the email nor `pwd=`), `music_net` existing tests green with `without_url`,
  `music::tests::library_gates_storytel` (switch off / no login / both on; the revision triggers),
  `device_listing`/`cover_in_library` 404 for a gated phone, `music_sweep` tests (the list: reading order, `url` null,
  art, a book leaving the shelf leaves the list, `not_on_shelf` keeps the last good list, `login_refused` inside the
  cooldown sends nothing, a cover fetched, `flag_reports` ignores Storytel), `music_import::tests` (a Storytel entry
  imported, a bad target skipped), the sanitizer (`no_audio`, `login`), `music_library_snapshot` with a Storytel entry.
- Kotlin: `StorytelApiTest` (the login URL exactly `...login.action?m=1&uid=k%40example.invalid&pwd=AC6C...`; the JWT
  kept 1 h, re-login after, per generation; `NoJwt` without cooldown; a relative or missing Location = `NoAudio`; the
  deadline), `StorytelRulesTest` (`sameStream`, `storedUri`, `redactUri`, `sourceId`, retry classes),
  `StorytelDownloadTest` (the plan and `fetchOne`'s decisions with a fake minter and a fake fetch: `NotConfigured` stops
  Storytel without item errors, the report says `none`/`refused` and `waiting: login`), `ReportTest` (`storytel`
  values), `LibraryViewTest` (`Kind.AUDIOBOOK`, "del N av M"), `ContractFilesTest` (the snapshot's Storytel entry
  parses), `SonosLifecycleTest` (`roomVerdict` and the standalone suspension with a marker; adopt sets the session uri
  from the live one; renderer.json keeps the marker after a Storytel adopt, M1), `SonosSessionTest` (the not-ours log
  redacted), `GuardsTest` (a Storytel copy plays `copyOnly`; the mint only inside the actor job; every
  `RendererChoice.uri` write through `storedUri`, M1), `SourceScanTest` (§6.1; the debug base assigned only in
  `src/debug` and read only by §4.6's four readers, M2; the redacted `toString`s, L9; the strings in nb), (design 27's schema test covers Room version 2,
  `items.artHash`; this design adds no column).
- After QA (2026-10-12): the service's adopt keeps an undownloaded Storytel book playing (no Stop) and phone -> room
  puts all five books of a series with books 1 and 3 downloaded on the room (H2, a `GuardsTest` pin on each call site's
  `forRenderer` plus `RendererGate`-style behavioural tests where the service's pure plan helpers allow);
  `SonosControllerTest`: a step during a mint sends one `SetAVTransportURI`, a suspension (or `release`) during a mint
  sends no `Play`, a superseded job never mints (M6); `StorytelApiTest`/`StorytelDownloadTest`: no JWT and an
  unreachable login stop Storytel for the run with no item error, each asset status maps to its class, the `http_416`
  retry mints again (M7, L7); `ReportTest`: `storytelWaiting` gives `login`, `storage` between 0.5 and 1.5 GB, else the
  shared rule (M4); `LibraryViewTest`: "del 3 av 5" with books 1 and 3 downloaded, a bookmark on an undownloaded book is
  started (M5); `StorytelHttpTest`: the `User-Agent` on every request (L8); Rust: `seriesInfo.id` as a number and as a
  string, `""` and `0` as no series, a duplicate consumable id kept once (L1), the anchor from `keep_end` (L2), Add with
  two targets (L3), the probe's 45 s bound and `storytel_key` (L4), Check now reads a fresh shelf (L5), `schedule`,
  `check_now`, `SweepView::card`, `/music/cards` and `save_offline` take Storytel (M3).

## 8. Steps (each one implementer, one sitting, its tests, the suites green; branch `music-2-storytel`)

Suites: server `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`; music `./gradlew assembleDebug
assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`; launcher `./gradlew assembleDebug
testDebugUnitTest` when a file its tests read changes (step 2b: `MusicPlanTest` reads
`server/testdata/music_library.json`). No launcher code changes in phase 2.

1. **Music core, Android-free** (no visible change). Files: `M/storytel/StorytelApi.kt` (with `Mint`'s classes, M7),
   `M/storytel/StorytelHttp.kt` (the client and the User-Agent as parameters), `M/storytel/StorytelEndpoints.kt` (the
   real hosts; `debugBase` declared, unread until step 3), `M/logic/Storytel.kt` (grammar, fetchable, the Storytel keep
   set and download order, `sourceId`, retry classes, `storytelRoom`, `storytelWaiting`, `consumableIdOf`,
   `sameStream`, `storedUri`, `redactUri`, `durationByConsumableId`), `M/model/Library.kt` (`Source.STORYTEL`),
   `M/logic/PlayOrder.kt`, `M/logic/LibraryView.kt` + `M/ui/LibraryScreen.kt`, `PlayerScreen.kt`, `ListScreens.kt` words
   (`Kind.AUDIOBOOK`; strings in `values` and `values-nb`), `M/bridge/StorytelLogin.kt` (the redacted `toString`, L9),
   the fixtures in `src/test/resources/storytel/`. `DownloadPlan` is **not** wired yet (it keeps `url != null`, so no
   Storytel book is fetched and every Storytel item stays hidden). Tests: `StorytelApiTest`, `StorytelHttpTest`,
   `StorytelRulesTest`, `LibraryViewTest`, `SourceScanTest` (strings, `toString`s). Acceptance: the music suite green; a
   library with a Storytel entry parses and shows nothing. Owed: none.
2. **a. Server: the client, the login, the picker.** Files: `S/storytel.rs`, `S/music_net.rs` (§2.2, `without_url`),
   `S/main.rs` (`AppState.storytel`, the routes `GET /music/storytel`, `POST /music/storytel/add`, the tracing filter's
   `reqwest=info` floor, L10), `S/handlers/music.rs` (the probe with its 45 s bound, clear, the card, the picker, add
   with a `Vec` form, the entry page's choices, cache 0 -> -1), `templates/music.html`, `music_storytel.html`,
   `music_entry.html`, `migrations/0072_music_storytel.sql` (0066 when built), `Cargo.toml` (`aes = "0.9"`), `server/testdata/storytel/`,
   `S/tests/music_storytel.rs` (+ `tests/mod.rs`, `CannedFetch`'s request record). The library, the sweep and the cards
   still leave Storytel out. Acceptance: the server suite green; the PWA pages at 360 px (no horizontal scroll, a 400 by
   its field, no jump). Owed [device check]: the real login saved (accepted, "N series"), a wrong password refused with
   the old one kept, the picker on the real shelf (kids filter), a series and a standalone book added.
   **b. Server: the listing, the library, the cards, the contract, the import.** Files: `S/music.rs` (`Link::Storytel`,
   `entry_link`, `LIBRARY_ENTRIES`' `IN` and its gate, `cover_in_library`, `kind_label`, `item_error_code`, the waiting
   values, the texts), `S/music_sweep.rs` (`schedule`'s SQL, `check_entry`, the Storytel check with `keep_end = 'first'` and Check
   now's fresh shelf, `device_listing`, `flag_reports`), `S/music_import.rs`, `S/handlers/music.rs` (`SweepView::card`,
   `check_now`, `cards`, `save_offline`, the device card's Storytel lines; M3), `templates/partials/music_card.html`,
   `templates/device_detail.html` (the Storytel lines, the mobile-data help text, L14). **The shared-contract commit**:
   `server/testdata/music_library.json` regenerated (`MUSIC_LIBRARY_SNAPSHOT_WRITE=1`) with a Storytel entry (resume on,
   cache -1, so the string replacements below still hit only what they meant) and copied byte-identically to
   `music/app/src/test/resources/`, with the tests that read it: the server's `music_library_snapshot`, music's
   `ContractFilesTest` (the Storytel entry parses; `entryIds` and the entry count now four), `OfflineTest` ("an entry
   the app can't read keeps its copies", a `"cache": 3` replacement) and `BookmarksTest` (6, `"resume"` replacements)
   re-checked against the new file (L12), and the launcher's `MusicPlanTest` (the Storytel entry's listing version
   passes through `libraryHead` - the proof that the courier carries Storytel listings - and the cover count) - **one
   commit** (root CLAUDE.md). Acceptance: the server, music and launcher suites green; Check now on a Storytel card
   lists the series (CannedFetch). Owed [device check]: the sweep lists a real series (Check now); a book added to the
   series in Storytel's app shows on the next Check now.
3. **Music: the account, downloads, local playback, the report, the fake.** Files: `M/storytel/StorytelAccount.kt`,
   `M/storytel/StorytelEndpoints.kt` (the four readers of `debugBase`, M2), `M/logic/Offline.kt` (`KeepItem.fetchable`,
   the Storytel keep set wired), `M/sync/{DownloadPlan,Downloads,Sync}.kt` (the plan, `fetchOne`'s classes, the re-mint
   on 416, the news eligibility, `storytelWaiting` in `writeReport`), `M/media/{MediaCache,ItemMediaSourceFactory}.kt`
   (`copyOnly`), `M/data/Snapshot.kt` (`forRenderer`; the service and the UI pass `false` until step 4b),
   `M/playback/PlaybackService.kt` (`mediaItem`), `M/ui/UiModel.kt` + `M/logic/LibraryView.kt` (the series' reading
   order for "del N av M" and the line, M5), `M/logic/Report.kt`, `src/debug/.../DebugFeedReceiver.kt` (`storytel_base`,
   `storytel.json` read then deleted), `scripts/fake-storytel.py`, `.github/workflows/music.yml`,
   `docs/testing/emulator.md` §6i. Tests: `StorytelDownloadTest`, `OfflineTest`, `NewItemsTest`, `ReportTest`,
   `LibraryViewTest`, `GuardsTest`, `SourceScanTest`. Acceptance: the music suite green, `python3 -I
   scripts/fake-storytel.py --selftest` OK. Emulator (§6i): a 3-book series downloads in reading order; the
   bookmark's book first (before step 4b a bookmark sits on a book that isn't on the phone only after its copy went:
   play book 3, drop the entry from the library and add it again - bookmarks survive, copies don't); `--drop-at 40 --token-ttl 30`: the book resumes with Range against a fresh token (the fake's
   log), the bytes complete; `--locked 222`: `no_audio` for it, the others download; `--refuse`: `storytel: refused`,
   `waiting: login`, one login in an hour; airplane mode: the downloaded books play, the others are hidden, the tile
   still says "del N av 3"; the switch off in the library (a hand-edited library without the entry): the books deleted;
   logcat and the app's data folder - the cache's metadata in `databases/exoplayer_internal.db`, Room's `music.db`,
   both with their `-wal` files, the report, `no_backup/` - hold no `FAKE-JWT`, `pwd=`, `token=` or the email (one
   recursive `grep -raE`); a job stopped at its 10-minute limit (`cmd jobscheduler timeout`) resumes its book with a
   fresh mint.
   Owed [device check]: §"Device checks" download items, "Downloads under Doze".
4. **a. Server: a book's own cover as its `art` hash** (QA M8; rebuilt on design 27 at the merge of music-1b-sonos
   with design 27, 2026-10-12 - see that section). Files: `S/music_sweep.rs` - `art_candidate` takes a Storytel book
   (`ok`, `url` NULL, `art_url` = the shelf's cover; it needs the entry's source or a flag, the `Item` alone can't
   tell a book from a withdrawn episode), `art_pass`'s order reads Storytel as oldest first (reading order: today the
   `auto` arm makes every non-series link newest first), and `storytel_check` carries each kept book's `art_*` fields
   (`art_from`, `art_hash`, `art_print`, `art_tone`, `art_state`, `art_at`, `art_fails`) over from the old item instead
   of `..Item::new(..)` (else every check drops the hash and fetches the picture again); `S/music.rs` -
   `art_in_library` serves `source IN ('nrk', 'rss', 'storytel')`. (The other way, the Storytel check storing the
   hash itself through `photos::MUSIC_ART`'s store and lock, duplicates the pass's fetch, judging and budgets; take it
   only if the pass can't be widened cleanly.) The judging is not design 27's for books (qa-26b-step4a-code M1, §5.5):
   every fetched book cover is `own` - no reference prints, no `same`, no twin merge -; budgets `ART_PER_CHECK`/`ART_PER_DAY`
   unchanged (a 40-book series fills over two checks). **The shared-contract commit**: `server/testdata/music_listing.json`
   regenerated (`MUSIC_LISTING_SNAPSHOT_WRITE=1`) only if its fixture gains a Storytel item, and then copied
   byte-identically to `music/app/src/test/resources/` (the launcher's `MusicPlanTest` version pin with it), one commit.
   Tests: `src/tests/music_storytel.rs` (a series check fetches each book's cover once, the listing's `art` is the hash
   for an own cover and null for the series' own, a second check keeps the hashes - that pins the result -, a failed
   cover's second check the same day sends no request and keeps `art_fails` 1 - that pins the carry-over,
   qa-26b-merge27-code M1 -, the cover route serves a ticked phone the book's picture and 404s an unticked one),
   `src/tests/music_art.rs` (a Storytel book is a candidate), `the_sweep_lists_a_series_like_a_feed`: `items[0]`'s
   `art` becomes the hash, `items[1]` stays null (`same`, the series cover; L2). No music or launcher code.
   Acceptance: the server suite green (the music and launcher suites only if a shared file changed). Emulator (§6i):
   an upgrade from a step-3 build (Room 1 -> main's 2) keeps every position and bookmark (the `ReapplyListings`
   migration re-applies the listings, with the hashes). The covers themselves are a device check (the server's
   bookshelf URL is a constant and its art pass refuses a private host, so no fake shelf reaches it; L3). Owed
   [device check]: "Per-book covers".
   **b. Music: Storytel on Sonos.** Files: `M/sonos/{PlayBody,SonosController,SonosRenderer,SonosLifecycle,
   SonosTransport,SonosSuspension,SonosSession}.kt` (the mint body, the job's token, `sameStream`, adopt's live uri,
   `storedUri` on every write, the duration lookup, the redacted log), `M/storytel/StorytelAccount.kt` (the minter the
   controller gets), `M/playback/PlaybackService.kt` (§5.6's call sites, `toRoom`'s re-plan, `lookAtRoom`'s write,
   coming home with a book not on the phone, `HANDOFF_MS`), `M/ui/MusicViewModel.kt`/`MusicRoot.kt` (the remote
   snapshot on a room). Tests: `PlayBodyTest`, `SonosControllerTest`, `SonosLifecycleTest`, `SonosSessionTest`,
   `StorytelRulesTest`, `GuardsTest`. Acceptance: the music suite green, both fakes' selftests OK. Emulator (§6h + §6i
   together): a book not downloaded plays on Stua (one mint in the fake's log, the DIDL's duration; no art, 26a §9),
   next/previous mint again, pause/resume doesn't, a step mashed during a slow mint (`--slow-mint 5`) sends one
   `SetAVTransportURI`, a stopped Stua plays from the bookmark with a new mint, phone -> Stua with books 1 and 3 of five
   downloaded queues all five, the app killed and restarted while Stua plays an undownloaded book (adopted by
   `consumableId`, no transport command, the right book, no Stop), room -> phone with that book not downloaded (paused,
   "Ikke lastet ned"), `renderer.json` holds `storytel:book:<cid>` after the play and after the adopt. Owed [device
   check]: the Sonos items below.
5. **Docs and release.** `music/CLAUDE.md` (the `storytel/` package, download-only, visibility per renderer, the mint
   rules and the job's token, the secrets rule, the debug switch, the fake, Storytel over mobile data with the parent's
   switch), `server/CLAUDE.md` (the Storytel client, the picker, the listing, the gates, migration 0072, the `reqwest`
   log floor), `launcher/CLAUDE.md` (one line: Storytel listings ride the courier unchanged), root `CLAUDE.md`
   (`scripts/fake-storytel.py`, `music.yml`'s paths), `NOTICE.md` (the ports of `storytel.py`, the Storytel parts of
   `library.py`, `content.py` and `daemon.py`, crediting vibb (MIT, palchrb) and its upstream storytel-player 1.3.0 by
   debba (MIT), with its notice beside `MIT-vibb.txt`), design 21's "Out: Storytel playback (2)" -> points here, design
   26's status, `PLAN.md`. Then the release below.

Shared contracts: step 2b changes one (the library snapshot, three directories, one commit), and step 4a the listing
snapshot if its fixture gains a Storytel item (both copies, one commit). No step touches the AIDL,
`bridge_contract.json`, `music_state.json` or the policy shape. Each step is one sitting:
1 (pure Kotlin), 2a (one new module plus its PWA), 2b (wiring Storytel through existing paths plus the contract
commit), 3 (the phone's download path), 4a (the server's art pass for books), 4b (the Sonos wiring) - 4 was split for that (M8).

## 9. Deviations from vibb (each with its reason)

| vibb | Here | Why |
|---|---|---|
| one box: shelf, list, downloads, positions | the server reads the shelf and lists (§2), the phone logs in, mints, downloads and keeps positions (§4) | option A (user, 2026-10-11): the parent picks in the PWA, the phone fetches from the source |
| `_encrypt_password` shells out to `openssl` | `aes` (server), `javax.crypto` (phone); vibb's vector plus three more | no subprocess on Android; no openssl dependency on the server |
| `storytel.py` runs as a bare subprocess per entry (`_sync_one`, stderr tail; `storytel_subprocess.py`) | one process each side; downloads are WorkManager jobs | there is no Python; the failure it guarded (an import crash, silently) can't happen |
| `storytel-session.json` (the JWT, 0600, written, never read) | the JWT in memory only, both sides | nothing reads it on the Pi either; a JWT on disk is one more secret |
| the cooldown file in `STATE_DIR`, cleared by `save_credentials` | the server's `music_storytel.login_refused_at`/`_generation`; the phone's file with the generation | one process per side; a new login generation clears it on both without a second writer |
| a refused probe at save forgets the account (save, refuse, clear) | nothing is stored, the saved login unchanged | the singleton feeds every phone: a typo must not cut them all off |
| `bookshelf_cached` on disk across subprocesses | in memory, 300 s, the last good shelf on failure | one process; the sweep also keeps its last good list (21b) |
| the bookmark mirror: outbox, `push_bookmark`, `deviceId`, `storytel_sync`, `shelf_durations` | not ported | user decision 2026-10-09: no position mirroring; nothing leaves the phone |
| `_download`'s `.part` + Range | Media3's `ProgressiveDownloader` into the shared cache, keyed by the item; the row names the book, not the URL | phase 1's download machinery; a URL-keyed row would discard every partial at the next mint |
| `_download` follows a CDN redirect (`urlopen`) | a Storytel book's CDN request never follows one: a 3xx fails the attempt as a transient `http_30x`, the next attempt mints again; [device check] that the real CDN answers without a redirect | Media3 writes a followed redirect's target (a token) into the cache's metadata (`exo_redir`, §0); if the real CDN does redirect, the fix is a thin `DataSource` whose `getUri()` returns the requested URI, never following blindly (qa-26b-step3-code choice 6) |
| `shelf.json` per target dir, the mp3 named by cid | the listing in `music_items` and Room; the copy keyed `<entry key>:<cid>` | 21b: the server lists, the phone downloads |
| a book that left the shelf stays on disk, unlisted | it leaves the list and its copy is pruned | the same for the kid (unplayable either way); no orphaned gigabytes |
| per-book `<cid>.jpg` on the box's screen | per-book covers on the phone's screens (carousel, player, Nytt, the session) from step 4a, through design 27's item art; none to the speaker | design 27 gave the phone item art; the speaker can't fetch a hash (26a §9) |
| `_sonos_body` sends the book's `art_url` as `albumArtURI` | no art in the DIDL | the listing carries a hash the server serves only to the phone's bearer (design 27 §1.5); 26a §9's later path (`art_src`) would carry Storytel's public cover unchanged |
| `count` = the first N books only | the first N plus the bookmarked book and the next | phase 1's keep rule for every entry: the book in progress is never deleted |
| `newest_book_id` changes -> NY | every new book key joins Nytt (and NY) | phase 1's per-key port of `_mark_new_seen` |
| a pasted link | the shelf picker; a raw target is refused unless it is on the shelf | vibb's picker is the only way on the Pi too; 4c's rule |
| sections | categories, Lydbøker by default; per-phone ticks | design 20 decisions |
| `PREFER_REMOTE` global, `_EXPAND_CACHE` cleared on switches | `Snapshot.forRenderer(remote)` passed explicitly | 26a §9 (the thread race at daemon.py:1529) |
| renderer.json holds no uri; adopt posts the live `snap.uri` | 1b stores the built uri - for Storytel the marker `storytel:book:<cid>` - and adopt takes the live uri | vibb keeps signed URLs out of state files; 1b's readers need a stable key |
| `asset_url(timeout=6)` on the Sonos path, a login inside at 20 s | the whole mint within 10 s; `HANDOFF_MS` 25 s | the room's actor is shared with the polls and the hand-off (26a §0) |
| room -> box with a book not downloaded: the rotate fails, book 1 plays from 0 | the phone comes home paused, "Ikke lastet ned" | vibb's result is an accident; a phone in a pocket must not start another book (design 20) |
| (no item errors on the Pi; 21b's codes `http_*`, `bad_media`, `network`) | `no_audio` and `waiting: login` added | the phone's own mint failure and login wait must be visible to the parent |
| log lines carry what they carry | Storytel logs carry cids and statuses; Sonos logs redact the query | design 20 §5: no credentials or JWT in logs, and a signed URL is a bearer |
| an honest UA `vibb/1 (+codeberg...)` | the server's and the app's own honest UAs | vibb's rule (never a pinned app version), our names |
| a book is never deleted on its own: only the whole target at logout or removal (`prune_cache`, `forget_downloads`) | phase 1's keep rule deletes a finished book outside the first N + the bookmarked book + the next | phase 1's keep/prune applies to every entry; with the default "All books" nothing is pruned (QA L11) |
| the picker adds `order: oldest_first` (`app.js:978`) | `auto` | `auto` is oldest first for Storytel (§3.2) and keeps the parent's order select meaningful; the effect is the same (QA L11) |
| a missing `model.id` becomes the id `"None"` (`str(None)`, `storytel.py:453`) | the book is skipped (logged) | its asset call could only fail; a `"None"` key would be a book nobody can play (QA L1/L11) |

## 10. Questions for the user - decided (main session, 2026-10-12)

1. **Single books out of a series: no, keep vibb's rule.** vibb's picker adds a series or a standalone book, never one
   book of a series (`normalize_shelf` has no group for it, so `sync` would say "not on the shelf"); the "first N"
   setting limits what is downloaded.
2. **Storytel Family (a login per phone): not now.** §6.6 designs it; it matters only when two kids listen at the same
   time, and it is one server step when the family takes Storytel Family.
3. (From QA L14, decided by the main session) **Storytel follows the parent's mobile-data switch** like every other
   download; the switch's help text says books are large (§2.3, §4.3).

## 11. Corrections to the brief and the code (found while writing)

- The brief orders "Android-free Kotlin cores (the Storytel API client with canned HTTP, **the shelf normaliser**, the
  target grammar)" first. Under option A the phone never reads the shelf: the normaliser is Rust only (`S/storytel.rs`),
  and the phone's core is the client, the grammar and the rules (step 1).
- Design 26 item 4 says the server logs in "with its own stable `deviceId`"; its later "Accounts and sessions" paragraph
  is right: `login.action` takes none, and without the bookmark POST nothing needs one.
- `music_net::network_error` puts reqwest's error text into `FetchError`, and reqwest prints the URL: harmless for
  feeds, but the login URL carries the email and `pwd=` (§2.1: `without_url`).
- `serde_json::Map` sorts keys in this build (no `preserve_order`): vibb's first-seen group order and each group's cover
  need a document-order read of `items` (§2.1).
- Phase 1's `fetchOne` drops a partial copy when the row's URL differs (qa-21-step2-code L7); with a URL minted per
  attempt that would restart every book from 0 - the source id fixes it (§3.3, §4.3).
- Phase 1's `applyListing` makes only items with a `url` eligible news; a Storytel book has none (§3.4).
- 1b's `RendererChoice.uri` stores the built uri; for Storytel that would persist a signed URL (§5.3). 1b's
  `SonosSession` logs both uris on "not-ours?" (§5.7).
- 1b's `PlayBodies.build` never gets art (Room has no column, 1b choice 7) though vibb sends it (§5.5).
- `music_items.state 'ok'` is documented as "it has a URL"; a Storytel book is listed and playable without one (§2.8).
- Design 20 §5's check "the position shows in Storytel's app" and its §2.5 "the server mints URLs" are void (no
  mirroring; option A).
- `music_import`'s `Skip::Storytel` ("a later version of the music app") is lifted (§2.6).

## Device checks (Jelly Star, the family's account; after the step that owes them)

- **Two logins in a row** (design 26 "Accounts and sessions"): the server's picker opened (its JWT), then the phone
  downloads (its own login), then the picker again without a new login - both still work; the PWA's Save again (the
  server re-logs in), then the phone mints - still works (design 26's "both JWTs stay valid": the phone's is exercised
  by the mint, the only call it makes); the family's own Storytel app stays logged in throughout.
- The login: accepted (the card's "accepted" line), a wrong password refused with the old one kept, the phone's
  `storytel` "ok" on the device card; a changed password on Storytel's side: the server and the phone each refuse once,
  then wait an hour (no hammering in the server log or logcat); saving it again retries at once on both.
- The picker on the real shelf: kids' filter, locked and geo flags shown, a series and a standalone book added.
- Downloads: a series downloads on Wi-Fi in reading order (the parent's card "N of M books on the phone"); with mobile
  data off they wait; **a 300 MB book with Wi-Fi off midway and back after more than an hour** (the URL long expired)
  resumes from its partial with a fresh mint and plays to the end; airplane mode plays what is downloaded; a book
  Storytel refuses shows `no_audio` on the card while the others download.
- A new book added to a series (in Storytel's app): after Check now, NY on the cover and "Ny bok" in Nytt once it is
  downloaded.
- "Lydbok · del 2 av 3" on the tile, "Del 2 av 3" in the player, resume after a reboot (paused), a finished book starts
  from 0 on a re-tap.
- The phone's Storytel switch off: the books and the login go; on again: they come back.
- **No secrets**: `adb logcat -d | grep -E 'pwd=|eyJ|Bearer |token=|<the email>'` (a real JWT starts `eyJ`) and the
  same over the server's `journalctl` find nothing after a day of use; `music_state` on the device page holds none of
  them.
- The DNS filter on (`KidVpnService`): the login, the mint and the CDN all work.
- **Downloads under Doze** (QA M9): a 5-book series (several GB) ticked in the evening, the phone left screen off
  overnight, once on the charger and once off it; `adb shell am get-standby-bucket me.vibb.music` before and after, and
  `adb shell dumpsys jobscheduler | grep -A5 me.vibb.music` for the jobs' runs and stops. Expected: on the charger the
  series completes overnight in 10-minute jobs (each resuming its book with a fresh mint); off the charger it advances in
  maintenance windows, slower but steadily, with the parent's card showing have/want rising. If it stalls (the bucket
  falls to rare or restricted, or no job runs for hours), the user decides between downloads only while charging and
  design 19b's battery exemption for the music app.
- **Per-book covers** (step 4a; qa-26b-merge27-code L3): with the real server and the real shelf, a series whose books
  have their own covers shows each book's cover in the player's carousel, in Nytt and on the lock's tile - the first
  book's (the series cover) included -, and so does **a series with templated covers** (one layout, another number or
  title per book: each book its own picture, none shows another's); the server log's check line says
  `, art N (N own, 0 same)`.
- Sonos: a book not downloaded plays in a room (title and progress bar in the Sonos app; no cover, §9), next/previous, pause for
  two hours then play (if the speaker fails, one more tap starts from the bookmark), the app killed while a book plays
  (reopen: adopted, the right book and place), the signed Location carries `consumableId` (logcat's redacted line and
  the parsed id), room -> phone with the book downloaded (continues) and not downloaded (paused, "Ikke lastet ned").

## Release

- **Server**: phase 2's server part (migration **0072**, the client, the picker, the listing) needs a version after
  whatever `main` has tagged when this merges: today (checked 2026-10-12 at the merge of music-1b-sonos with design
  27) `server/Cargo.toml` is 0.24.0 untagged (the highest server tag is `server-v0.19.0`) and main's 0065-0070 (0070 is
  design 27's episode art) and 1b's 0071 ride in it; if 0.24.0 is tagged first, bump `server/Cargo.toml` and
  `Cargo.lock` to the next minor. Step 4a (a book's cover as its `art` hash) adds no migration: it uses 0070's columns.
  **The backup hazard** (as 26a's Release section, qa-26a-merge-code M1): `startup::pre_migration_backup` compares
  only `MAX(version)` with the highest compiled one, so a lower-numbered migration reaching a server that has already
  run 0072 is applied out of order by sqlx with no pre-migration backup (design 22 §6's "no migration without its
  backup" broken, silently). Every build of this branch since the merge of 2026-10-12 carries 0070-0072 and applies
  them in order; never deploy one from before it (0071/0072 without 0070). 1b's and design 27's servers ship before or
  together with this one, or the backup check compares the set of applied versions (a main-session change;
  documented here and in `server/CLAUDE.md`, not made). Deploy the
  server first: an older server sends no Storytel entries, so an older or newer music app simply has none.
- **Launcher**: no code change; nothing to release for phase 2.
- **Music**: `music-v0.3.0-rc.1` on the merge commit on `main`, after 1b's `music-v0.2.0` line (26a "Release"):
  versionName `0.3.0-rc.1`, versionCode **2999** (`0*1_000_000 + 3*1_000 + 0`, an RC minus 1), above 1b's
  `music-v0.2.0-rc.1` (1999) and the released `music-v0.0.1`/`music-v0.0.2` (1 and 2; checked 2026-10-12 at the merge
  of music-1b-sonos), so it installs over all of them through the catalog. A prerelease, never "latest". The stable
  `music-v0.3.0` after the emulator checks (`docs/testing/emulator.md` §6i, and §6h with Storytel) and the device
  checks above. An older music app on a phase-2 server hides Storytel entries and prunes nothing (§3.1).

## 12. QA review (2026-10-12, design QA)

Verdict: buildable after a short fix round (spec edits; M8 is a step split, M9 a device check). Evidence and fixes for
each line: `docs/design/qa-26b-design.md`. The AES vectors, the behaviour map and the test table hold against vibb but
for the gaps below.

- **H1** §2.8: the comment to change lives only in applied migration 0051; sqlx's checksum then stops every upgraded server.
- **H2** §3.2/§5.6: adopt (`lookAtRoom`, `adoptedQueue`) and phone -> room (`toRoom`) read the local snapshot: a restart stops an undownloaded book; the room skips undownloaded books.
- **M1** §5.3: `lookAtRoom`'s renderer.json write (`PlaybackService.kt:1224`) would store the signed URL once adopt takes the live uri.
- **M2** §4.6: the fake at 10.0.2.2 is refused by the API client, the downloads and §5.1's private-literal check; specify one debug-only switch.
- **M3** §2.4/step 2b: Storytel is still filtered out by `schedule`'s SQL, `SweepView::card`, `check_now`,
  `/music/cards`, `save_offline`, `LIBRARY_ENTRIES`' `IN`.
- **M4** §2.5/§4.5: `waiting: login` and the 1.5 GB floor never reach the report (`writeReport` has one `waiting` for
  all entries).
- **M5** §3.5: "del N av M" and the progress line count the downloaded books, not the series.
- **M6** §5.1: nothing gates a play between the mint and the SOAP (superseded plays, the actor's drain after a
  suspension).
- **M7** §4.1/§4.3: no JWT means a login per book; every asset status becomes `no_audio` for a day.
- **M8** §8: step 4 is too big; split the `items.art` migration out (4a/4b).
- **M9** §4.3: no device check of download progress for gigabytes under Doze and job quotas.
- **L1** §2.1: `normalize_shelf` details (series id stringified, falsy rules, the format's geo flag, duplicate ids).
- **L2** §2.4: the listing's anchor comes from `keep_end == "first"`, not a separate flag.
- **L3** §2.3: Add's repeated `target` needs a `Vec` form, not `FormMap`.
- **L4** §2.3: bound the probe's time; give it its own credentials; `login_error` a code; a code for `KeyUnusable`.
- **L5** §2.4: Check now reads a fresh shelf; name the shelf cache's clock.
- **L6** §5.1: one minter signature; the login mutex wait inside the deadline.
- **L7** §4.3: re-mint before the `http_416` retry.
- **L8** §1.1: the Storytel client sets the phone's UA itself.
- **L9** §0: redacted `toString` / no `Debug` on types that hold secrets.
- **L10** §0: reqwest's debug-level redirect logs; Media3's `exo_redir` cache metadata.
- **L11** §9: three missing deviation rows (prune beyond first N, `auto` vs `oldest_first`, the missing-id skip).
- **L12** §8: name the step 2b test churn (string replacements over the library snapshot).
- **L13** §4.6: the debug feed's plaintext login file: fake values, deleted once read.
- **L14** §4.3: GB-sized books over mobile data when the parent's switch is on: confirm with the user.

## 13. Decisions after QA review (2026-10-12)

Every finding is taken; where the fix differs from QA's proposal the line says how and why. The main session decided H1,
M8, M9, L14 and §10's two questions.

- **H1**: §2.8 no longer touches 0051; the note sits in 0066's (now 0072's) header comment and `music_sweep::Item`'s doc, and §2.8
  says applied migrations are never edited (sqlx checksums).
- **H2**: §5.6 is a table of every call site and the snapshot it passes - adopt (`lookAtRoom`, `adoptedQueue`) and the
  standalone suspension read `forRenderer(true)`, `toRoom` re-plans over the entry's remote order at the current item
  and place (never `a0.items`), `comeHome` and the leave read `false`; §3.2 points there; §7 and step 4b carry the two
  tests.
- **M1**: §5.3: every write of `RendererChoice.uri` goes through `storedUri` (`host.played` and `lookAtRoom`'s rewrite,
  both of its branches); a `GuardsTest` pin and a `SonosLifecycleTest` check after an adopt.
- **M2**: §4.6: one debug-only `StorytelEndpoints.debugBase` with exactly four readers (endpoints, the LAN client for
  the API, `publicOnly = false` for Storytel downloads, §5.1's private-literal check); `OkHttpStorytelHttp` takes its
  client; `SourceScanTest` pins the writer (`src/debug` only) and the readers.
- **M3**: §2.4 lists every NRK/RSS-only filter (`schedule`'s SQL, `check_entry`, `device_listing`, `SweepView::card`,
  `check_now`, `cards`, `save_offline`), §2.7 adds `'storytel'` to `LIBRARY_ENTRIES`' `IN`, §2.3 gives `save_offline`
  the Storytel choices; step 2b's files include `handlers/music.rs` and `device_detail.html`, its acceptance "Check now
  lists the series".
- **M4**: §4.5: `storytelWaiting` per Storytel entry in `writeReport` (`login` while the account isn't `ok`, `storage`
  below 1.5 GB, else the shared rule); §3.3 says the floor stops Storytel books only. Difference from QA: an unreachable
  login (`LoginFailed`) is not reported as `login` - it is a network matter, and the shared rule already says `wifi`
  when the network is the cause.
- **M5**: §3.5: for Storytel, "del N av M", `started`, the progress line and the player's "Del N av M" count the
  series' reading order (`snap.listed`); `LibraryViewTest` rows; done in step 1 (`UiModel.kt`, `LibraryView.kt`;
  the listing order, not the play order - qa-26b-step1-code L5).
- **M6**: §5.1: the play job carries a thread-safe token (generation, `closed`, `mayPlay`) checked before the mint (a
  superseded job never mints) and after it, before `SetAVTransportURI`; `DRAIN_MS` 30 s rechecked and kept; the
  receiver's 9 s vs `HANDOFF_MS` 25 s explained. `SonosControllerTest` rows; the fake's `--slow-mint` for the emulator.
- **M7**: §4.1: `Mint.LoginFailed` (no JWT, login unreachable or past the deadline) stops Storytel for the run like
  `Refused`, without item errors; asset statuses split into `NoAudio` (3xx without `Location`, 403, 404, 410, 451:
  permanent) and `Failed` (401 after the re-login, 408, 429, 5xx, other: transient `http_<code>`); §3.3 and §4.3 follow.
- **M8**: step 4 is split into 4a (`items.art`: Room v2, the schema, `PlayItem`, `applyListing`, art to the speaker for
  every source) and 4b (Storytel on Sonos). (4a was rebuilt on design 27 as server work at the merge of 2026-10-12:
  §8, and "Merge of music-1b-sonos with design 27".)
- **M9**: the device check "Downloads under Doze" (a 5-book series overnight on and off the charger, the standby bucket
  and `dumpsys jobscheduler`, the expected outcome, and the user decision if it stalls); §6.3 points to it.
- **L1**: §2.1: `seriesInfo.id` stringified like `model.id`, Python's falsy rules, `geo` from the abook format, a
  duplicate consumable id kept once; the missing-id skip is a §9 row.
- **L2**: §2.4: Storytel listings set `keep_end = 'first'` (the anchor `finish()` and `device_listing` read); no
  separate flag.
- **L3**: §2.3: Add reads `Form<Vec<(String, String)>>`.
- **L4**: §2.3: the probe is bounded at 45 s in all (the gate's wait included) and uses `Account::login_with`;
  `login_error` holds a code; `KeyUnusable` has its own code `storytel_key` and card text.
- **L5**: §2.1/§2.4: Check now reads `shelf(0)`; the cache and the JWT run on `tokio::time::Instant`.
- **L6**: one minter signature, `mint(cid, timeoutMs, deadlineMs)` - downloads `(15 s, +60 s)`, Sonos `(6 s, +10 s)`;
  the login `Mutex`'s wait counts against the deadline (§4.2, §5.1); §7's "one call with 6 s" matches it.
- **L7**: §4.3: the `http_416` retry from 0 mints a fresh URL.
- **L8**: §4.1: `OkHttpStorytelHttp` sets the `User-Agent` itself; `StorytelHttpTest` checks it.
- **L9**: §0: redacted `toString` on `StorytelLogin.Login`, `StorytelRequest`, `StorytelResponse`, `Mint.Url` (pinned);
  no derived `Debug` on the server's `StoredLogin` or JWT holder.
- **L10**: §0: the server floors `reqwest=info` in its tracing filter (`server/CLAUDE.md` says why; not `DEPLOY.md`, the
  main session's file); Media3's `exo_redir` is named and accepted, and step 3's emulator check greps the cache index.
- **L11**: §9 gains three rows (a finished book pruned beyond first N + bookmark + next; `auto` for vibb's
  `oldest_first`; the missing-id skip).
- **L12**: step 2b names the tests that read the library snapshot (`music_library_snapshot`, `ContractFilesTest`,
  `OfflineTest`'s and `BookmarksTest`'s string replacements, the launcher's `MusicPlanTest`), and the new entry's
  values are chosen so those replacements still hit only what they meant.
- **L13**: §4.6: the debug feed's `storytel.json` holds fake values only and is deleted once read.
- **L14**: §4.3 and §10: Storytel follows the parent's mobile-data switch (the main session's decision); the switch's
  help text on the device card says books are often several hundred MB (§2.3, step 2b).
- **§10**: both questions recorded as decided (single books: no, vibb's rule; Family: not now).
- **§8 re-checked**: each step's file list is complete for what it changes (M3's handlers and templates, M2's endpoints
  file, L9's `StorytelLogin.kt`, M5's `UiModel.kt`), the only shared-contract commit is named (2b), and each step is one
  sitting (4 split).

## Step 1 implementation status (2026-10-12)

Built on branch `music-2-storytel`, compiled and tested in the sandbox with the real Android build (AGP 8.13, JDK 17,
`-PwarningsAsErrors=true`): `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug` and CI's `./gradlew
build` green - **411 JVM tests** (debug and release), 0 failures; lint adds nothing in the touched files (its one
warning there, `PluralsCandidate` on `some_downloaded`, predates this step). No visible change: the download plan is not
wired, so no Storytel book is fetched and every Storytel item stays hidden; no `server/`, launcher or contract file
changed (`ContractFilesTest`, `BridgeTest` green unchanged).
- **In** (`M/storytel/`, new): `StorytelApi.kt` (the constants with the UPSTREAM note, `encryptPassword` via
  `javax.crypto`, the login with the cooldown first, the JWT in memory for 1 h per generation, one re-login on a 401,
  `assetUrl(cid, timeoutMs, deadlineMs)` with `follow = false`, `Mint`'s seven classes per M7, `CooldownStore`,
  `state()`), `StorytelHttp.kt` (the seam `StorytelHttp`, `StorytelRequest`, `StorytelResponse`, and
  `OkHttpStorytelHttp(client, userAgent)`: both redirect switches per request, the call timeout, the body cut at 64 KB,
  the User-Agent last on every request, no interceptor), `StorytelEndpoints.kt` (`Endpoints.STORYTEL`, the debug
  switch `StorytelEndpoints.debugBase` declared, read by nothing). `M/logic/Storytel.kt` (new): `storytelTarget`,
  `isStorytel`, `seriesTarget`/`bookTarget`, `fetchable`, `storytelKeepSet`, `storytelWanted`, `sourceId`, `NO_AUDIO`,
  `storytelPermanentFailure`/`storytelRetryDelayMs`/`retryDelayFor`, `mintItemError`/`mintStopsRun`,
  `STORYTEL_FREE_FLOOR`/`storytelRoom`, `storytelWaiting`, `consumableIdOf`, `sameStream`, `storedUri`, `redactUri`,
  `durationByConsumableId`. Changed: `M/model/Library.kt` (`Source.STORYTEL`: the grammar, cache 0 -> -1, never `lan`),
  `M/logic/PlayOrder.kt` (oldest first), `M/logic/NetGate.kt` (`Waiting.LOGIN`), `M/logic/LibraryView.kt`
  (`Kind.AUDIOBOOK`, `countedOrder`), `M/ui/{UiModel,MusicRoot,LibraryScreen,PlayerScreen,ListScreens}.kt` (the words;
  `playerNumber`), `res/values{,-nb}/strings.xml` (`kind_audiobook`, `hint_part`, `part_of`, `new_book`),
  `M/bridge/StorytelLogin.kt` (`Login.toString` redacted, L9).
- **Fixtures** (`music/app/src/test/resources/storytel/`, for step 3's fake too): `login_request.json` (email
  `k@example.invalid`, password `hemmelig`, its `pwd` hex and the expected query), `login_ok.json` (`FAKE-JWT-1`),
  `login_no_jwt.json`, `asset_302.json` (a `cdn.example.invalid` Location with `consumableId` and `FAKE-SIG-1`),
  `asset_locked.json` (403, no Location) - each answer a `{status, location?, body?}` object - and `aes_vectors.json`
  (the four vectors, re-printed with `openssl enc -aes-128-cbc` here: all four as §7 says).
- **Tests** (49 new): `StorytelApiTest` 16 (`storytel_api.py` 1, 4, 5, 10b, 11; the exact login URL, follow and 20 s;
  the JWT per hour on the elapsed clock and per generation; a 5xx login refused, the cooldown's end; no JWT and an
  unreachable login without a cooldown; every asset status to its class; the deadline over the login and the asset
  call; L6 with two threads: a Sonos mint gives up at its deadline while a download logs in, a waiter reuses that JWT,
  one login in all; a 401 whose token another mint already replaced; the redacted toStrings), `StorytelHttpTest` 6
  (two `TinyHttp` servers: the 302 captured, the CDN sees nothing; follow on for the login; the User-Agent on every
  request; the 64 KB cut; the call timeout; a mint end to end over sockets), `StorytelRulesTest` 19 (`storytel_api.py`
  2, `storytel_expand.py` 1, 6, 7, `storytel_sync.py` 4, 5, 7, `storytel_sonos.py` 7 and 14, the acceptance: a listed
  Storytel entry shows nothing, the unwired plan fetches and prunes nothing), `LibraryViewTest` +3 (`Kind.AUDIOBOOK`,
  "del 3 av 5" with books 1 and 3 downloaded, a bookmark on an undownloaded book started, the player's "Del 3 av 5"),
  `SourceScanTest` +5 (the nb words; an explicit `toString()` on `Login`, `StorytelRequest`, `StorytelResponse`,
  `Mint.Url` and the JWT holder - a mutation dropping one turns it red; `Authorization`/`Bearer` only in
  `StorytelApi.kt`, the two redirect switches, no interceptor, no `fallbackToDestructiveMigration`; no log line in
  `storytel/` or `sync/` interpolating a secret, no log in `storytel/` at all; `debugBase` declared once and read
  nowhere). `TinyHttp` (test) can send response headers.

Choices made while building (each against the spec text, with its reason):
1. **The login lock lives in `StorytelApi`** (a `ReentrantLock` taken with `tryLock` for what is left of the deadline),
   not as a coroutine `Mutex` in `StorytelAccount` (§4.2): the client is blocking and runs on IO anyway, and so L6 (the
   wait counted against the deadline, a waiter served by the login it waited for) is tested on the JVM now. Step 3's
   `StorytelAccount.mint` is `withContext(IO) { api.assetUrl(...) }` and needs no lock of its own. A forced re-login
   whose token another mint has already replaced takes the new one (no second login).
2. **`state()` is on `StorytelApi`** (pure: `none`, `refused`, `ok` from the stored login and the cooldown); §4.2's
   `StorytelAccount.state()` delegates to it.
3. **The cooldown runs while `0 <= now - at < 1 h`**, vibb's `0 <` widened by one point: vibb's float seconds never
   see 0, our milliseconds do (a mint right after a refusal would log in again). A stamp in the future (the wall clock
   went back) still counts as none, as in vibb.
4. **A `Location` counts only on a 3xx**: vibb takes a Location from any status; §4.1 reads "then a Location is `Url`;
   else the status decides". A 4xx/5xx carrying one is decided by its status (`Failed`/`NoAudio`), never a mint. The
   Location must also pass phase 1's `safeUrl` (absolute http(s), a host, no userinfo, at most 2000 characters).
5. **A malformed consumable id** (not `[A-Za-z0-9_-]{1,64}`) is `NoAudio(0)` with no request: the spec is silent; a
   listing key can't fail it, and nothing is ever requested for a path that isn't a book's.
6. **Any exception from the seam** (not only an `IOException`) is `Unreachable` (asset call) or
   `LoginFailed("unreachable")` (login): a mint never throws (vibb `storytel_sonos.py` 4). Reasons are fixed words
   (`deadline`, `unreachable`, `no jwt`), never an exception message.
7. **Where the types live**: §4.1's code block shows `StorytelRequest`/`StorytelResponse` with `StorytelApi`; they sit
   with the seam in `StorytelHttp.kt` (the file §8 names for it), `Endpoints` in `StorytelEndpoints.kt`.
   `StorytelResponse` is a plain class (a `ByteArray` in a data class compares by identity) and `Mint.Url` too (its
   `toString` is the redacted one either way). Requests have no body: the phone sends none (the shelf is the server's).
8. **M5 is wired already** (§13 puts it in step 3): `countedOrder` (pure, `LibraryView.kt`) feeds `entryUi`'s hint,
   `started` and progress line, and `playerNumber` (`UiModel.kt`, used by `MusicRoot`) the player's "Del N av M"; the
   NY badge still looks at the visible items only. No effect until a book can be downloaded (step 3).
9. **`Waiting.LOGIN` is added now** (`storytelWaiting` returns it); nothing reports it until step 3 wires
   `writeReport`.
10. **The retry helpers are separate functions** (`storytelRetryDelayMs`, `retryDelayFor(entry, code, attempts)`) and
    phase 1's `permanentFailure`/`mayRetry` are untouched: step 3 routes a Storytel row through `retryDelayFor` and
    `sourceId` when it wires the plan.
11. **`storedUri(entry, itemKey, uri)`** takes the item key, and for Storytel without one names the book from the uri's
    `consumableId`; with neither it returns null - never the signed URL. `durationByConsumableId` returns ms (the
    listing's unit); the controller divides by 1000 as 1b's listing carry does.
12. **SourceScanTest goes beyond step 1's list**: §6.1's pins (bearer, redirects, logs, no destructive migration) cost
    nothing now and guard steps 3-4 from their first line. The `debugBase` pin is step 1's form ("read nowhere"); step 3
    replaces it with the four-readers pin (M2).

Found in the spec while building (no change made to §0-§13):
- §4.1/§8: the request and response types are drawn inside `StorytelApi`'s block but the seam has its own file (7).
- §4.1: whether a Location counts on a non-3xx answer is open (4); §4.1's NoAudio list implies 3xx only.
- §4.2 vs L6: a coroutine `Mutex` in the Android class makes L6 untestable in step 1 (1).
- §1.1's cooldown "active while `0 < now - at < 1 h`" is vibb's float rule; at ms resolution it leaks (3).
- §13 M5 says step 3, §8 step 1 names the screens' words; done here (8).
- §7 maps `storytel_expand.py` 1 to `forRenderer(false)`, which arrives in step 3; step 1 tests `Snapshot.visible`
  (the local half, unchanged code).
- §7 10b's "a new generation clears" holds through the generation-keyed store: the phone's file keeps the old
  generation's stamp, which no longer applies (step 3's `CooldownStore` may overwrite or ignore it).

Owed: none for step 1. For step 3 (from these choices): `StorytelAccount` = `StorytelApi` over `StorytelLogin.load`, the
file `CooldownStore`, `SystemClock.elapsedRealtime`/`System.currentTimeMillis` and `OkHttpStorytelHttp(guarded client,
"VibbMusic/<version>")`; route Storytel rows through `retryDelayFor` and `sourceId`; replace the `debugBase` pin.

### Fix round after QA's code review (qa-26b-step1-code, 2026-10-12)

All eight Lows fixed; QA accepted the twelve choices (1, 3, 4, 7, 8 now also in §1.1, §2.1, §4.1, §4.2, §7 and §13's
text). Suite after the round: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` green, **413 JVM tests**, 0 failures (`StorytelRulesTest` 20, `LibraryViewTest` 11; the
others' new rows sit in existing tests); lint unchanged.
- **L1** (a refused login could throw): `cooldown.setRefused` runs in `runCatching`; the mint still returns `Refused`.
  `StorytelApiTest`: a store whose write throws gives `Refused`, no exception.
- **L2** (the `toString` pin could borrow the next class's body): the header regex now ends at the class's own `{`
  (`[^)]*\)\s*(:[^{\n]*)?\{`) and a class without a body fails. Mutation: `StorytelRequest` as a bare `data class`
  turns the pin red ("no body for ..."), reverted.
- **L3** (the minted Location went through the listing's `safeUrl`): `StorytelApi.mintedUrl` - OkHttp's
  `toHttpUrlOrNull`, http(s), a host, no username or password, at most 8 KB (`MINTED_URL_MAX`), the canonical form;
  §5.1's check uses it. Rows: a `|` and a space are a `Url` (the space comes back `%20`), a 3000-character token is a
  `Url`, userinfo (with or without a password), relative, `ftp:`, empty and over-8 KB stay `NoAudio`.
- **L4** (a persistent 401 cost one login per book): a 401 on the retry with a fresh JWT drops that JWT (when it is
  still the kept one), and `mintStopsRun(Failed(401))` is true (that book still gets `http_401`). Rows in
  `StorytelApiTest` (the next mint starts with a login) and `StorytelRulesTest`.
- **L5** ("Del N av M" counted the play order): `entryUi` and `playerNumber` pass the listing order (the reading order)
  to `countedOrder`; with newest first, book 5 reads "del 5 av 5" on the tile and in the player. `LibraryViewTest` row.
- **L6** (a listed 0 came back as a length): `durationByConsumableId` returns only a value > 0 (vibb's `if known:`).
  Rows: a listed 0 and a missing length are null.
- **L7** (`mayRetry` decided with phase 1's classes only): `mayRetry(..., delayMs = ::retryDelayMs)`, and
  `downloadPlan` passes `retryDelayFor(entry, ...)` to it and to `nextRetryAt` (unchanged for NRK/RSS: `retryDelayFor`
  falls back to `retryDelayMs`). `StorytelRulesTest`: `http_403` may retry after 2 min for a Storytel book, not for an
  RSS item (a day), the default stays phase 1's, `no_audio` waits a day.
- **L8** (the ported tests didn't credit vibb): the MIT credit line in `StorytelApiTest`, `StorytelHttpTest` and
  `StorytelRulesTest`.
- **Spec text** (no behaviour change): §0's threading line and §4.2 (the lock in `StorytelApi`; step 3's two conditions:
  one `StorytelApi` for the process, `mint` on IO), §4.1 (the types in `StorytelHttp.kt`, `Mint.Url` a plain class, a
  Location only on a 3xx through `mintedUrl`, the failed cooldown write swallowed, the malformed cid, the lock),
  §1.1 and §2.1 (`0 <= now - at < 1 h`, with the note that the step 2a server matches it), §5.1 (`mintedUrl`), §7
  (`storytel_expand.py` 1: `Snapshot.visible` now, `forRenderer(false)` from step 3; 10b through the
  generation-keyed store), §13 (M5 done in step 1, in reading order).

## Step 2a implementation status (2026-10-12)

Built on branch `music-2-storytel-2a` (from `685a6ae`), three commits: `music_net` (`b92b1ef`), the client and
migration 0066 (`623183b`; 0072 since the merge of music-1b-sonos), the PWA (`7baa750`). Server only; the library, the sweep, the cards, the import and the
contract snapshot still leave Storytel out (step 2b).
- **In**: `S/storytel.rs` (new, vibb `storytel.py` credited in its header): `encrypt_password` (the `aes` block cipher,
  CBC and PKCS#7 by hand, UPPERCASE hex), `login_url` (`form_urlencoded`, `@` as `%40`), the target grammar,
  `normalize_shelf` (a `MapAccess` visitor keeps `items` in document order; no `preserve_order`), `StorytelError` with
  its codes and picker texts, `StoredLogin` (moved here from `handlers::music_api`), `credentials`, `login_with`,
  `probe` (login + one bookshelf within `PROBE_BUDGET` = 45 s), and `Account` (`AppState.storytel`: a tokio `Mutex`, the
  JWT for an hour with one re-login on a 401, `bookshelf` = a fresh read, `shelf(max_age)` = 300 s or 0 with the last
  good shelf of the same generation on failure, `keep`, `forget`; the cooldown and the outcome stamped in the row, only
  for the generation they belong to). `S/music_net.rs`: `SourceRequest.{method, headers, body}`, `Kind::Login`/`Shelf`,
  `network_error` from `err.without_url()`. `S/main.rs`: `AppState.storytel`, `GET /music/storytel`, `POST
  /music/storytel/add`, `log_filter` (reqwest floored at `info`). `S/handlers/music.rs`: the save's probe (refused: 400,
  nothing stored, `storytel_login_refused`; accepted/unreachable: sealed, generation + 1, the cooldown cleared, the
  probe's JWT/shelf kept, a session-flash notice), clear (also `Account::forget`), the card's status line, the picker
  and Add, the entry page's Storytel choices (0 saved as -1). Templates `music.html` (the card, the add form's hint),
  new `music_storytel.html`, `music_entry.html`. `migrations/0066_music_storytel.sql` (now `0072_music_storytel.sql`) exactly as §2.8.
  `testdata/storytel/{bookshelf,login_ok}.json`. `server/CLAUDE.md`: a section on the client, the picker and the
  reqwest log floor (L10). `Cargo.toml`: `aes = "0.9"` (already in the lock).
- **Tests**: the server suite 560 (540 before), fmt clean, clippy's 12 warnings are the branch's own (none added).
  New: `storytel::tests` 12 - `password_vectors` (the four vectors, the login URL), `targets`,
  `normalize_groups_by_model_id_in_reading_order` (the fixture: unsorted keys, format ids differing from `model.id`, a
  locked third book, a geo book with numeric ids, an ebook-only row, the group cover from the first-seen book),
  `normalize_follows_pythons_rules` (string and numeric series id in one group, `""`/`0` = standalone, null order,
  duplicate id kept once, missing/bad ids skipped, a series id failing the grammar dropped, falsy/broken shelves),
  `bookshelf_request_shape` (CannedFetch's record: POST, the body, the form type, the bearer, `Accept`),
  `a_401_relogs_once` (once and always-401, the JWT kept, renewed after an hour and per generation),
  `refused_login_cooldown` (the stamp, no request inside the hour, a 5xx, a new generation retries, `no_jwt` and an
  outage without cooldown), `not_configured` (no login, no key, another key = `storytel_key` stored),
  `the_shelf_cache_and_stale_beats_dead`, `the_probe_is_bounded`, `no_secret_in_errors` (a redirect loop and a dropped
  connection over loopback; red without `without_url`), `secret_types_have_no_debug` (L9). `tests::music_storytel` 8 -
  `routes_need_a_session`, `save_probes_then_seals`, `the_card_shows_no_login`, `picker_lists_groups`,
  `add_takes_only_shelf_targets` (two targets in one form, L3), `clear_drops_storytel_from_libraries` (2a's half: the
  revision triggers and the forgotten session; the library's gate is 2b's), `the_entry_page_offers_storytels_choices`,
  `the_log_floors_reqwest_at_info`. The phase-1 Storytel tests pass unchanged (their saves now probe: no canned answer
  = unreachable = saved). `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` failed in some runs, also on
  `685a6ae` without these changes (time-dependent, not Storytel's); the last full run was green.

Choices made while building (each against the spec text, with its reason):
1. **The card's error lines carry no time** ("Storytel couldn't be reached at the last try."): §2.8's columns (kept
   exactly) have no time for `login_error`. *Superseded in the fix round: 0066 (now 0072) gained `login_error_at` (the main
   session's decision) and the lines show it.* The refused line has its times (`login_refused_at`), the accepted line
   `login_ok_at`. Times read "10:02 UTC" today and "12 Oct 10:02 UTC" otherwise (the cards' `utc_time`).
2. **`login_error` also holds the shelf's last outcome** (`http_503`, `too_big`, `bad_shelf`, `network`), cleared by a
   good shelf: the probe's "unreachable" includes the shelf failing, and with the JWT kept for an hour a login-only code
   would go stale.
3. **The cooldown is active while `0 <= now - at < 1 h`** (§1.1 says `0 < ...`): the stamps are whole seconds, so a
   retry in the same second would otherwise slip through.
4. **The JWT's and the shelf's clock is `tokio::time::Instant` plus a per-account offset the tests move**
   (`Account::advance`), not a paused runtime: a paused clock auto-advances while sqlx waits on its worker thread.
5. **No generic `api(state, request)`**: the bookshelf is the server's only authenticated call, so `fetch_shelf` holds
   vibb `_api`'s rule (one re-login and one retry on a 401). `credentials` returns the login with its generation and
   the cooldown stamps, not a bare `StoredLogin`.
6. **`normalize_shelf` edge cases**: a boolean or list id is no id (Python would key "True", which passes the
   grammar); a shelf row that isn't an object is skipped (Python fails the whole shelf); a series id sent as `77` and
   as `"77"` is one group (Python makes two with the same target); a duplicate consumable id is dropped across the
   whole shelf.
7. **Add refuses the whole form when any target isn't on the shelf** (400, nothing added; vibb 4c), and when nothing is
   ticked; an unreadable shelf is a 503 with the reason; a full library part-way redirects with `full=1` (the page says
   so), none added = 400. The redirect keeps `all=1`. Already-in-library targets are skipped silently.
8. **The save's notice is a session flash** (the import's pattern), so the redirect stays `/music#storytel`; a refused
   probe is a 400 with the text by the e-mail field (autofocus), as the other refused saves.
9. **The picker right after a refusal** says "Nothing is tried again for an hour" (`Refused` carries no stamp); from the
   next view on it names the time ("before 11:02 UTC") without a request.
10. **The entry page**: a stored value outside vibb's choices shows as "The first N (from Vibb)"; the Storytel target
    is plain text with a link to the shelf (a `storytel:` href isn't a link). An empty group name shows the target in
    the picker; an entry gets "Storytel series"/"Storytel book" then.
11. **Left for 2b** (its files): `music_sweep::Item`'s doc note (§2.8; 0066's - now 0072's - header has it), `not_on_shelf`,
    `kind_label`'s "Storytel series/book", and `music::tests::storytel_cache_zero_is_all`'s import and library parts
    (2a pins the save; the helper is `storytel::cache_value`, with `storytel::CACHE_CHOICES`).

Found in the spec: §2.3's "couldn't be reached (10:02 UTC)" needs a column §2.8 doesn't add (1 above); §1.1's `0 <`
with whole-second stamps (3); §2.1's "the tests pause it" (4); §7 row 4's "per-phone downloads on the card" and row
5's "the library loses the entries" are 2b's, not 2a's.

Owed [device check] (unchanged from §8 2a, plus the PWA): the real login saved (accepted, "Storytel shows N series and
books"), a wrong password refused with the old one kept, the picker on the real shelf (kids filter, locked and geo
lines), a series and a standalone book added; `/music#storytel`, `/music/storytel` and a Storytel entry's page at 360
px (no horizontal scroll, the 400s by their field, no jump) - no browser in the sandbox; `journalctl` with
`RUST_LOG=debug` holds no `pwd=`, e-mail or JWT after a save and a picker visit.

### Fix round after QA's code review (qa-26b-step2a-code, 2026-10-12)

All seven findings fixed, and the main session's decision on choice 1. The choices list above has **11** items (the
handback said 10). `cargo test` 561 of 562 - the one failure is the pre-existing, time-dependent
`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` (left to its owner); the Storytel tests 24 of 24 (2
new). `cargo fmt --check` clean, clippy's 12 warnings are the branch's own.
- **M1** (the login URL in a `Referer`): `HttpSource::new` builds both clients with `.referer(false)`.
  `storytel::tests::the_login_sends_no_referer`: a loopback login URL redirecting to `localhost:<port>/next`, whose
  request carries no `Referer` (red with `.referer(true)`: it held `pwd=`).
- **L1** (the picker's read unbounded; `keep` waiting behind it): `Account::bookshelf` = `bookshelf_within(READ_BUDGET
  = 45 s)`, the session's lock and the gate's wait included; past it `timeout` ("Storytel didn't answer in time").
  `keep` is synchronous and uses `try_lock`: while a read holds the session it keeps nothing (the generation check
  makes the old session harmless; the cost is one login). `storytel::tests::the_read_is_bounded_and_keep_never_waits`.
- **L2** (the log floor): `log_filter` drops every `reqwest`/`reqwest::*` directive from `RUST_LOG` (any spacing, with
  or without a span filter) before adding `reqwest=info`; the test now emits real events through the filter and a
  capturing layer (`debug`/`trace` at `reqwest::async_impl::client`, `reqwest::connect::verbose`, `reqwest` dropped
  under `debug`, `trace`, `reqwest=trace`, `reqwest`, `reqwest::async_impl=debug`, `debug,reqwest::connect=trace`,
  ...; `info` at `reqwest::connect` and our own `debug` kept) - red without the stripping. The comment in `main.rs` and
  `server/CLAUDE.md` now say what it is: defence in depth for reqwest's own events (0.12 logged redirect URLs; the 0.13
  in the lock doesn't).
- **L3** (the text scan): `secret_types_have_no_debug` is a compile-time autoref probe - `StoredLogin`, `Jwt`,
  `Credentials`, `SourceRequest` have neither `Debug` nor `Display` (the probe checked on `String`).
- **L4** (`SourceResponse`'s `Debug` held the body - a login's JWT): a hand-written `Debug` with `status`,
  `body_len`, `truncated` and the validators; pinned in the same test.
- **L5** (an outage worded as a wrong password): the save's refusal text by status - 401, 403 and no JWT keep
  "Storytel refused that e-mail and password. The saved login is unchanged."; any other status "Storytel answered
  503 - nothing was saved; the saved login is unchanged. Try again later." Nothing is stored either way
  (`save_probes_then_seals`: 503 and 429).
- **L6** (Add's unbounded `target`s, quadratic dedupe): more than `MAX_ENTRIES` target fields (repeats counted) is a
  400 ("More than 200 rows are ticked - nothing was added."), read with a `HashSet` beside the `Vec`
  (`add_takes_only_shelf_targets`: 201 refused, 200 repeats of one target add one entry).
- **Choice 1** (the main session): 0066 (unreleased; now 0072) gains `login_error_at TEXT`; every write of `login_error` sets
  or clears it (the account's outcomes, the save, clear), and the card's error lines read "Storytel couldn't be
  reached (10:02 UTC).", "Storytel answered 503 (10:02 UTC)." (`utc_time`; "at the last try" only for a row without
  the time). §2.8's block is byte-identical to the file again.
- **Spec text**: §2.1 (the offset clock instead of "the tests pause it"; `login_error_at`), §2.3 (the error lines'
  time and its column), §2.4 (the rule for 2b: the Storytel check reads the row's cooldown before `Account::shelf`,
  which falls back to the last good shelf on `Refused`/`Cooldown` too), §2.8 (the column), §7 rows 4 and 5 (their 2b
  parts marked).

## Step 2b implementation status (2026-10-12)

Built on branch `music-2-storytel` from `0854ed9`, three commits: the server (`fca26c4`: the sweep, the library's
gate, the cards, the import), the shared-contract commit (`70a332f`: the snapshot on all three sides) and a wording
fix (`6e36ea2`). No migration (0066 - now 0072 - as 2a applied it), no launcher code, no change to `music_listing.json`,
`music_state.json`, the AIDL or the policy shape.
- **The listing** (`S/music_sweep.rs`, §2.4): `storytel_check` - the row's cooldown first
  (`storytel::credentials(..).cooldown_until(now)`: inside it `login_refused`, no request, the last good list kept;
  2a's code-QA rule), then `Account::shelf_counted(300 s)`, `shelf_counted(0)` for Check now (QA L5), the group whose
  target is the entry's; not on the shelf fails `not_on_shelf` (last good list kept). The list is the group now: one
  `ok` item per book in reading order, key = the consumable id, `url` NULL, `hls` 0, the shelf's `duration_ms` (0 =
  unknown = NULL), the book's cover as `art_url` when `media_url_ok`, `first_seen_at` kept for a book already listed;
  a book that left the shelf leaves the list. The listing's title and `cover_candidate` are the group's (fetched by
  21b §2.6's `cover()`, `Reach::Public` + `public_media`), `keep_end = 'first'`, `capped`/`fallback` off, `lan` false.
  `schedule`'s SQL and `schedule_one`, `check_entry` (through `music::entry_link`), `device_listing` (+ the gate) and
  `flag_reports` (skips Storytel entries) take it; `Item`'s doc says Storytel items are `ok` with no URL.
  `S/storytel.rs`: `Account::shelf_counted` (the session counts the logins and shelves it sends, so the check adds a
  read's 0-4 requests to its budget; `shelf` is `shelf_counted(..).0`).
- **Entries** (`S/music.rs`): `Link::Storytel { kind, id }` (`target()` the grammar's string, `source()`
  "storytel"; `check_link` refuses it - never pasted), `entry_link(source, target)`, `kind_label` "Storytel series" /
  "Storytel book", `CheckError::Storytel(code)` with its sentences (`storytel_check_message`), `entry_error_text`'s
  Storytel codes (`no_audio`, `not_on_shelf`, `login_refused`, `storytel_off`, `storytel_key`, `no_jwt`,
  `bad_shelf`) and `sweep_error_text(source, code)` for the codes a feed shares (below, 2); `item_error_code` takes
  `no_audio`, `Download.waiting` takes `login`.
- **The gate** (§2.7): `storytel_gate!()` (an exported macro, `concat!`ed into the SQL) in `LIBRARY_ENTRIES` (its
  `IN` gains `'storytel'`), `device_listing` and `cover_in_library` (both of its entry branches); a Storytel entry's
  `cache` 0 goes out as -1. The revision triggers are 2a's 0066 (now 0072).
- **Cards** (`S/handlers/music.rs`, M3): `SweepView::card` ("N books", no "newest" line, Storytel's offline choices
  with a stored 0 shown as all), the status texts (`sweep_error_text`, and "Couldn't list it: ..." from
  `CheckError::Storytel` for a never-listed entry), the per-phone lines ("1 of 3 books on the phone · waiting for the
  Storytel login on the phone", `no_audio` "Storytel gave no audio for this book"), `check_now`, `/music/cards`,
  `save_offline` (vibb's choices, 0 saved as -1, a stored other value kept). The entry page shows the sweep block and
  Check now for a Storytel entry (it reads `SweepView::card`). The device card (`#music`): "Storytel is off on this
  phone: its N Storytel entries aren't sent." / "No Storytel login is saved: ..." under the switch while the phone has
  ticked Storytel entries, the phone's `storytel` as words, the mobile-data help text's "This includes Storytel books,
  often several hundred MB each." (L14).
- **The import** (`S/music_import.rs`, §2.6): `Skip::Storytel` is gone; a `storytel:` target `parse_target` accepts is
  a Storytel entry (name, order, resume; cache 0 -> -1, other valid values kept), one it refuses is `Skip::BadLink`
  ("That Storytel target isn't storytel:series:<id> or storytel:book:<id>."). The preview words a Storytel row's depth
  "every book downloaded" / "the first N books downloaded".
- **The contract** (`70a332f`, L12): `server/testdata/music_library.json` gained entry 5 "Kokosbananas"
  (`storytel:series:26175`, Lydbøker, order auto, cache -1, resume on, cover `d…`, items `00ff00ff00ff00ff`; the
  phone's switch on and a fake ciphertext `x'00'` stored), copied byte-identically to
  `music/app/src/test/resources/`; `music_library_snapshot` and `library_lan_only_for_a_lan_rss_feed` (server),
  `ContractFilesTest` (music: version, three categories, four entries with `Source.STORYTEL`, `entryIds`
  `{1, 2, 3, 5}`, the Storytel entry's fields, four covers, the lan/unknown-fields/sonos counts), `MusicPlanTest`
  (launcher: the Storytel listing version in `libraryHead.listings` and in `listingsToFetch`, four covers) in the same
  commit. `OfflineTest`'s `"cache": 3` and `BookmarksTest`'s `resume` replacements re-checked: they still hit only the
  RSS and own entries.
- **Tests**: server `cargo test` 571 of 571 (562 before, +9; `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`
  passed in this run), `cargo fmt --check` clean, `cargo clippy --all-targets` the 12 pre-existing warnings, none
  added. New: `music::tests::storytel_entries_have_their_own_link_and_label`, `music::tests::storytel_cache_zero_is_all`
  (the library, the card's offline select, the entry page's save, the import; a podcast keeps 0),
  `music_import::tests::storytel_targets_import_as_storytel_entries`, and in `tests::music_storytel`
  `the_sweep_lists_a_series_like_a_feed` (reading order, `model.id` keys, url null, per-book art, locked and geo books
  listed, the series' cover fetched and served, `keep_end`, the listing route and the library's `items`, the cards'
  "3 books"/"1 book", Check now, Storytel's choices), `check_now_follows_the_shelf` (a routine check reads the kept
  shelf, Check now a fresh one with one request; a book leaving and coming back; `not_on_shelf` keeps the list and the
  card says so; the card's Check now and `/music/cards` take Storytel), `a_refused_login_fails_the_check_without_a_request`
  (inside the cooldown zero requests and the last good list; an hour later one login and one shelf; no login =
  `storytel_off`, the never-listed text), `one_shelf_serves_a_pass` (vibb 6b: three entries, one login and one shelf in
  a `run_pass`), `library_gates_storytel` (switch off / on / login cleared, the library, listing and cover routes 404,
  the revision moves, the device card's notes and help text), `the_cards_show_the_phones_storytel` (have/want with
  `login`, `no_audio`, the phone's `refused` as a warning, a Storytel `http_403` never flagged); 2b's half of
  `clear_drops_storytel_from_libraries` (the phone's library loses the series, the tick stays) and the sanitizer
  (`no_audio`, `login`) in the existing tests; `a_vibb_library_is_previewed_then_imported_without_a_fetch` now imports
  its Storytel row (cache 0 -> -1). Checked by removal (reading): without the cooldown-first rule
  `a_refused_login_fails_the_check_without_a_request` fails (the kept shelf answers), without `shelf(0)` for Check now
  `check_now_follows_the_shelf` does, without the gate `library_gates_storytel` does. Music: `./gradlew assembleDebug
  assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` green, 413 of 413 (assertions added to
  `ContractFilesTest`, no new test functions). Launcher: `./gradlew assembleDebug testDebugUnitTest` green, 900 of 900.

Choices made while building (each against the spec text, with its reason):
1. **The check's budget**: the shelf read runs through `Account` (its lock, the JWT, the kept shelf), not the check's
   `Job`, so a spent budget refuses the read before it starts (`timeout`) and the requests it sent are added after
   (`Account::shelf_counted`, a per-session counter). A read is at most four requests.
2. **`sweep_error_text(source, code)` beside `entry_error_text`**: §2.4 puts `too_big` among the Storytel texts, but
   `entry_error_text("too_big")` already says "the feed is too big" (and `network`, `timeout`, `http_<code>` speak of
   "the site"/"the source"); the card knows the entry's source, so a Storytel entry gets "the Storytel shelf is too
   big", "Storytel couldn't be reached", "Storytel answered 503". The phone's entry errors keep `entry_error_text`.
   `storytel_key` has a text too ("the Storytel login must be entered again"), which §2.4's list leaves out.
3. **"N books" without the "newest:" part** of an NRK/RSS card: a series is in reading order, its last book is no news.
4. **The device card's lines**: §2.3's "No Storytel login is saved" reads "No Storytel login is saved: its N Storytel
   entries aren't sent." (the switch's own label already says "none saved yet"; the line adds what it costs), both
   notes only while the phone has ticked Storytel entries, under the switch. The phone's `refused` is a warning (red,
   the parent acts on it), `none` "Storytel: no login on the phone", `ok` stays "Storytel on the phone: ok".
5. **A shelf duration of 0 is NULL** in the listing (vibb's `or 0` means "unknown"; the phone must not read a 0-long
   book), titles are cut like a feed's (200 characters), the listing title is the group's name.
6. **The import doesn't look at the shelf** (§2.6: nothing fetched), unlike Add (4c): a Vibb box's target is the
   parent's own file; the sweep reports `not_on_shelf` if the family's shelf doesn't have it, `storytel_off` without a
   login.
7. **The snapshot's Storytel entry is in Lydbøker** (where Add puts it), so the snapshot's categories grew from two to
   three; `ContractFilesTest`'s category assertions follow.

Found in the spec: §2.4's `entry_error_text` list puts `too_big` in a function whose `too_big` already means the feed
(2 above); L12's "cache -1 so the string replacements still hit only what they meant" holds for `OfflineTest` and
`BookmarksTest`, but `ContractFilesTest`'s own `"cache": -1` -> `-2` replacement ("an entry the app can't use is
hidden") now hides the Storytel series too - harmless (the test is about hiding; `entryIds` gains 5), noted so nobody
reads it as a gap; §8's "entry count now four" is the phone's four (ids 1, 2, 3, 5; entry 4 is another phone's).

Owed [device check] (§8 2b): the sweep lists a real series (Check now on its card: the books in reading order, the
series' cover), a book added to the series in Storytel's app shows on the next Check now; plus the PWA at 360 px with
Storytel cards (the "N books" line, the offline select, the per-phone lines) and the device card's Storytel notes - no
browser in the sandbox.

### Fix round after QA's code review (qa-26b-step2b-code, 2026-10-12)

All seven findings fixed. The status above lists **7** choices (the handback said 8: it counted `shelf_counted`
separately; it is choice 1). `cargo test` 574 of 574 (571 before, +3 tests; the `parent_pin` test passed),
`cargo fmt --check` clean, `cargo clippy --all-targets` the 12 pre-existing warnings. Each new or extended test was run
red with its rule removed (M1's `stale_ok`, L1's due rule, L2's gate in the lines, L3's wording, L4's bound, L5's
duration rule, the gate in `cover_in_library`'s first branch, the login counter).
- **M1** (the sweep's read fell back to the kept shelf, so a refused login or an outage showed "Checked"):
  `Account::read_shelf` takes `stale_ok`; `shelf` (the picker's Add) passes `true` (vibb's "stale beats dead"),
  `shelf_counted` (the sweep) `false`, so `storytel_check` fails with the read's code. The cooldown-first rule stays
  (inside the hour no request). `the_sweeps_read_never_falls_back_to_a_kept_shelf`: six hours on (JWT, shelf and any
  cooldown over) a refused login fails the check `login_refused` with one login request and the list kept, the card
  says "Storytel refused the login. The phones keep the list"; after a new save, Check now with the shelf unreachable
  fails `network` ("Storytel couldn't be reached"); Add's read still falls back.
- **L1** (a save didn't wake the entries waiting for a login): `schedule_one` makes a Storytel entry due (`Rework`,
  `shelf(300 s)` = the probe's kept shelf) when its last error is `storytel_off`/`login_refused`/`storytel_key`/`no_jwt`
  stamped before `music_storytel.saved_at` of a stored login; the save writes `saved_at` with the sweep's clock
  (`Sweep::now()`, as every other Storytel stamp; it was `datetime('now')`) and wakes the sweep.
  `saving_a_login_lists_the_entries_waiting_for_one`: an entry failing `storytel_off`, the save, the wake, the entry
  due, the next pass lists it without a Storytel request.
- **L2** (the library card didn't know the gate): `SweepView` loads whether a login is stored and the phones whose
  switch is off; `phone_lines` gives a gated phone "<name>: not sent - Storytel is off on this phone" / "... - no
  Storytel login is saved", "on N phones" counts only the phones that get the entry (`SweepView::phone_count`; the
  page's own tick count query is gone), and a listed Storytel entry's failed check without a login says "The phones
  don't get it until a login is saved." (`storytel_key` keeps "The phones keep the list": the login is stored, the gate
  is open). Asserted in `library_gates_storytel` (before and after the clear).
- **L3**: the per-phone failing item goes through `sweep_error_text` ("2 failing (Storytel answered 403)";
  `the_cards_show_the_phones_storytel` puts the `http_403` first).
- **L4**: `storytel::id_ok` takes 1 to 64 characters (targets and consumable ids alike); `targets` asserts 64 kept, 65
  and 100 000 refused, the import test a 65-character series as `BadLink`.
- **L5**: `a_book_without_a_duration_is_listed_without_one` (a fourth book with `durationInMilliseconds: 0` is served
  with `duration_ms: null`); `library_gates_storytel` puts the listing's cover on the entry as the parent's own
  (`cover_in_library`'s first branch: 200 for the phone with the switch on, 404 for the other); `report.requests` is 2
  after the hour in `a_refused_login_fails_the_check_without_a_request`, and `one_shelf_serves_a_pass` checks that the
  checks' counted requests add up to every request of the pass (the login included); `sweep_error_text`'s `network`,
  `timeout`, `http_503` (and the feed's `http_503`, `no_audio`) in `storytel_entries_have_their_own_link_and_label`.
- **L6**: `music_sweep.rs`'s credit names `pi/vibb/storytel.py` (`sync` minus the downloads, `write_shelf`).
- **Spec text**: §2.1 (the fallback is the picker's only), §2.3 (the per-phone line for a gated phone, the failed
  check's text without a login), §2.4 (the sweep's read never falls back; a save makes the waiting entries due).

## Step 3 implementation status (2026-10-12)

Built on branch `music-2-storytel` from `83026a0`, three commits: the music app (`8a5850f`: the account, the plan, the
run, local playback, the report, the debug feed, the tests), the fake (`18596d9`: `scripts/fake-storytel.py` and
`music.yml`) and `docs/testing/emulator.md` §6i (`cb4809d`). No `server/` or launcher code, no contract file, no AIDL
(`ContractFilesTest`, `BridgeTest` green unchanged); `music_state.json` unchanged (the report sends the same fields;
`storytel` now says `refused` too and a Storytel entry's `waiting` can be `login`, both already in the server's
sanitizer since 2b). Compiled and tested here with the real Android build (AGP 8.13, `-PwarningsAsErrors=true`; the
sandbox has JDK 21 only, as for the earlier steps): `./gradlew assembleDebug assembleRelease testDebugUnitTest
lintDebug` and CI's `./gradlew build` green - **443 JVM tests** (debug and release each; 413 before), 0 failures; lint
adds nothing in the touched files (the two warnings left there, `UsableSpace` on the 500 MB line in `Sync.kt` and
`UseKtx` on the HLS `Uri.parse` in `Downloads.kt`, predate the step). `python3 -I scripts/fake-storytel.py --selftest`
OK (about 3 s).
- **In** - `M/storytel/StorytelAccount.kt` (new): the process's one `StorytelApi` (`StorytelAccount.get(context)`)
  over `StorytelLogin.load`, `FileCooldownStore` (`noBackupFilesDir/storytel_refused.json`, one `{generation, at}`
  record, tmp + rename), `SystemClock.elapsedRealtime`/`System.currentTimeMillis` and `OkHttpStorytelHttp(
  MediaCache.httpClient(publicOnly), "VibbMusic/<version>")`; `suspend fun mint(cid, timeoutMs, deadlineMs)` =
  `withContext(Dispatchers.IO) { api.assetUrl(...) }`, `state()`. `StorytelEndpoints.kt`: the four readers of
  `debugBase` - (a) `endpoints()` (the fake's `/api/login.action` and root), (b) `apiOverLan()`, (c)
  `publicDownloads()`, (d) `privateMintAllowed()` (for step 4b's §5.1 check). `M/logic/Offline.kt`:
  `KeepItem.fetchable` (`wantedDownloads` and the plan's `wantable` use it; `visibleKeys` keeps `hasUrl`).
  `M/sync/DownloadPlan.kt`: per Storytel entry `storytelKeepSet`, `storytelWanted` (the bookmarked book first, then
  reading order), `sourceId` in `mayRetry` with `retryDelayFor`. `M/sync/DownloadRun.kt` (new, pure): `fetchOne`'s
  decisions behind a `DownloadIo` seam (below, choice 1). `M/sync/Downloads.kt`: `Downloads.fetch(context, w, url)`
  (a Storytel book: `publicDownloads()`, never a followed redirect), the worker over `DownloadRun` (its `WorkerIo`:
  Room, the cache, Media3, the item errors, `noBackupFilesDir`'s free space, the account's mint `(15 s, elapsed +
  60 s)`), a job back at a refused login's cooldown end. `M/sync/Sync.kt`: news eligibility through `newsEligible`
  (`M/logic/NewItems.kt`), the report's `storytel` = `StorytelAccount.state()` and the per-entry waiting through the
  pure `reportWaiting` (`M/logic/Report.kt`: `storytelWaiting` for a Storytel entry with books wanted, the shared rule
  for the others). `M/media/MediaCache.kt`: `httpClient(publicOnly)`, `playbackFactory(..., copyOnly)` (no upstream),
  `downloadFactory(..., followRedirects)` (no-redirect clients). `M/media/ItemMediaSourceFactory.kt`:
  `ItemTag.copyOnly`. `M/data/Snapshot.kt`: `remote` and `forRenderer(remote)` (on a room every Storytel book shows,
  on the phone only with its copy; other sources unchanged). `M/playback/PlaybackService.kt`: `mediaItem` gives a
  Storytel book the uri `storytel:book:<cid>` and `ItemTag(cacheKey, hls = false, publicOnly = true, copyOnly =
  true)`; `load()` reads `forRenderer(false)`. `M/ui/MusicViewModel.kt` and `NewNotifier.update`: `forRenderer(false)`.
  M5 (the series' reading order for "del N av M") stays as step 1 built it. `src/debug`: `DebugStorytel.kt` (new:
  `storytel_base` from `config.json`, `storytel.json` seeded then deleted, and `DebugStartup`, a non-exported provider
  that applies the base at process start), `DebugFeedReceiver` calls both, the debug manifest declares the provider.
- **The fake** (`scripts/fake-storytel.py`, GPL, stdlib, `-I`): `/api/login.action` (only `login_request.json`'s
  email and `pwd` hex; 200 with `login_ok.json`'s body; `--refuse` 403; `--no-jwt` `login_no_jwt.json`'s),
  `/assets/v2/consumables/<cid>/abook` (the bearer checked, 401 without; `asset_302.json`'s status with a Location
  carrying the fixture's query names `consumableId` and `token=FAKE-SIG-<n>`; `--locked` and unknown cids
  `asset_locked.json`'s 403 without Location; `--slow-mint`), `/cdn/<cid>.wav` (a silent 16-bit WAV of `--books
  cid:MB`, Range with 206/416, the token per book and valid `--token-ttl` s, `--drop-at` cuts each book's first
  transfer); `--print-listing ENTRY` writes the debug feed's listing for the books. `--selftest`: the fixtures agree
  (the `pwd` is an AES vector, fake values only, no `eyJ`), then the fake against itself over loopback.
- **Tests** (+30): `StorytelDownloadTest` 17 (new: the plan's reading order with the bookmark first and the counts, a
  re-sync and the first N, the retry classes on the source id; the run: one mint per book and the source id in the
  rows, `no_audio` skips one book, `Failed`/`Unreachable`, each login failure stops Storytel with no item error and no
  row while an RSS episode goes on, the 401 (L4), the floor before any book and before each book, a dropped transfer
  resumes against a new token without dropping the partial, the 416 re-mint (L7) and its failed re-mint, a CDN 403
  counted per source, phase 1's rules for NRK/RSS, the report's waiting, the cooldown file), `StorytelRulesTest` 21
  (step 1's "not wired" row replaced: `forRenderer` both ways, the wired plan), `OfflineTest` +4 (fetchable vs
  visible; copies kept while listed, pruned when the list or the library drops them; first N + bookmark + next),
  `NewItemsTest` +1 (vibb `storytel_expand.py` 8), `ReportTest` +1 (M4, the three words), `LibraryViewTest` +1 (NY and
  the bell once a new book is on the phone, the tile's "del 5 av 5"), `GuardsTest` +3 (copy-only playback; the mint
  only in the download run, on IO, through the one `StorytelApi`; `forRenderer(false)` in the service, the screens and
  the notifier), `SourceScanTest` +2 (the `debugBase` pin replaced by the four-readers pin: declared once, read four
  times and only in `StorytelEndpoints.kt`, each reader called only by its file, assigned only by `DebugStorytel.kt`;
  Storytel downloads never follow a redirect; the seeded login deleted in a `finally`, no email in a log line, the
  provider not exported). Mutations (each reverted): the 416 without a re-mint turns two `StorytelDownloadTest` rows
  red; the plan keyed by `item.url` instead of `sourceId` turns four rows red.

Choices made while building (each against the spec text, with its reason):
1. **`fetchOne`'s decisions live in a new pure `M/sync/DownloadRun.kt`** behind `DownloadIo` (rows, the cache, the
   fetch, the item errors, the clock, the free space, the mint); §8 names only `DownloadPlan`/`Downloads`/`Sync`. Only
   so can `StorytelDownloadTest` drive "the plan and `fetchOne`'s decisions with a fake minter and a fake fetch" (§7)
   on the JVM; the worker is a thin loop over it and NRK/RSS go through the same code (phase 1's rules, tested).
2. **The four readers are functions in `StorytelEndpoints`** (`endpoints()`, `apiOverLan()`, `publicDownloads()`,
   `privateMintAllowed()`), so "exactly four readers" is one countable thing; (d) has no caller until step 4b (the
   pin allows `SonosController.kt` only).
3. **`StorytelAccount` keeps its instance while (a) and (b) answer the same.** In a release build that is once per
   process (the switch is always null); in a debug build a changed `storytel_base` builds one new session. QA's
   condition (no instance per run or per read) holds: the rebuild follows a change of answer, not a read.
4. **`DebugStartup`** (a debug-only, non-exported `ContentProvider`) applies `storytel_base` from the feed's
   `config.json` at every process start. §4.6 has the receiver set it; but the download worker often runs in a fresh
   process, where a receiver-only switch is null - the fake's login would go to the real storytel.com (a refusal and
   an hour's cooldown at best). Only `DebugStorytel.kt` assigns the field (pinned).
5. **`storytel.json` seeds the next generation** (`StorytelLogin.generation + 1`; 1 on a fresh install), not always
   1: pushing it again ends a refused login's hour, as a new save on the server does (§6i's refused check uses it).
6. **Storytel's CDN requests never follow a redirect** (no-redirect copies of MediaCache's two clients), as the brief
   for this step asks; §0 accepted a CDN redirect's target in `exo_redir`. A CDN that redirects anyway fails the
   attempt as `http_30x` (transient: the next attempt mints again). vibb's field notes and the fixture show the
   Location is the CDN's own URL; a [device check] below confirms the real CDN answers without a redirect.
7. **A refused login brings a download job back at the cooldown's end** (`DownloadRun.storytelRefusedUntil`, merged
   with phase 1's `nextRetryAt`), so the next login comes an hour later rather than at the 6 h backstop; any sync in
   between enqueues a job that mints with no request (the cooldown). `NotConfigured`, `LoginFailed` and the floor wait
   for the next sync (no per-item state to retry on; a timer would bring back one login per run at 2-minute
   doubling, which L4 and M7 avoid).
8. **`Unreachable` on the asset call goes on to the next book** (vibb parity; the QA note's optional stop not taken):
   the worker's own network check before each item ends the run when the network is gone.
9. **`Failed(401)` gets its `http_401` and stops the run** (step 1's `mintStopsRun`, L4); a mint failure before any
   bytes creates a not-done row holding the failure, so `mayRetry` waits by Storytel's classes (`no_audio` a day,
   `http_*`/`network` 2 min doubling); a login stop writes no row.
10. **The report** asks `StorytelAccount.state()` on every write (phase 1 said `ok` whenever a login file existed),
    and the floor reads `noBackupFilesDir`'s `usableSpace` (vibb `_free_bytes`: `disk_usage().free`), through one
    `Downloads.freeBytes` with lint's `UsableSpace` suppressed and the reason given; the shared 500 MB rule is
    untouched. `reportWaiting` is pure (`Report.kt`).
11. **`NewNotifier.count` takes the snapshot it is given**; `update` (the notification) passes `forRenderer(false)`.
    The bell on the screens follows the screens' snapshot, so step 4b's room view counts what plays there.
12. **No log line in `StorytelAccount`** for a Keystore failure (step 1's QA notes suggested one): `SourceScanTest`
    pins no `Log.` in `storytel/`; a failure reads as `none` on the card until the next run.
13. **The fake's defaults**: books `111:20,222:5,333:5` (MB) to keep an emulator run short (`--books 111:300,...` for
    §6i's long book); wrong credentials are refused like `--refuse`; an unknown cid answers like a locked one;
    `--print-listing` (with the library snapshot's version `00ff00ff00ff00ff`) feeds entry 5 "Kokosbananas".

Found in the spec (no change made to §0-§13):
- §8 step 3's secret check greps "the cache index (`no_backup/media/*.exo` metadata)": the `.exo` files hold audio
  spans; Media3's cache metadata (`exo_len`, `exo_redir`) lives in `databases/exoplayer_internal.db`
  (`StandaloneDatabaseProvider`). §6i greps the database, the cache folder, Room's `music.db` and the report.
- §4.6 lets only the receiver set the debug switch, which a process restart loses (choice 4).
- §0 accepts a CDN redirect's target in `exo_redir`; this step's brief forbids following one (choice 6).
- §4.3 step 2 says `LoginFailed` makes the entries report `waiting: login`; §4.5 (M4's decision) says an unreachable
  login reads as the shared rule. Built as §4.5 says; §4.3's sentence should name `NotConfigured` and `Refused` only.
- §8 step 3's "a 3-book series downloads ... with the bookmark's book first": before step 4b a bookmark sits on a book
  that isn't on the phone only after its copy went; §6i does it by dropping and re-adding the entry (bookmarks
  survive, copies don't).
- §4.2 writes `mint` as context-free; the account is reached through `StorytelAccount.get(context)`.

Owed - emulator (§6i, no emulator in the sandbox): a 3-book series in reading order, one mint per book; the
bookmark's book first after the entry is dropped and re-added (and the drop deletes the books: the switch-off case);
`--books 111:50,... --drop-at 40 --token-ttl 30`: the second transfer is a `206 Range bytes=N-` with a new token and
the book completes; `--locked 222`: `no_audio` for it, the others download; `--refuse`: `storytel: refused`, `waiting:
login`, one login in the hour, one again after it; airplane mode: the downloaded books play, the others hidden, the
tile and the player "del N av 3"; a book plays with the fake stopped (copy only); logcat, the cache folder,
`exoplayer_internal.db`, `music.db` and the report hold no `FAKE-JWT`, `FAKE-SIG`, `token=`, `pwd=` or the email.
Owed - [device check]: §"Device checks" downloads (a series on Wi-Fi with the card's "N of M books", waiting without
mobile data, the 300 MB book with Wi-Fi off for more than an hour resuming with a fresh mint, airplane mode, a refused
book's `no_audio`), "Downloads under Doze", the login lines (the phone's `storytel` word, a changed password refused
once and an hour's wait), the switch off and on, no secrets in logcat; plus, from choice 6, that the real CDN answers
the minted URL without a redirect (a redirect would show as `http_302` on the card for every book). For step 5:
`music/CLAUDE.md` and root `CLAUDE.md` (the fake, `music.yml`'s new path, the debug switch and `DebugStartup`).

### Fix round after QA's code review (qa-26b-step3-code, 2026-10-12)

All seven findings fixed (the "not worth fixing" list left as QA judged it); QA accepted the 13 choices above (the
handback said 10: the status lists **13**). Suite after the round: `./gradlew assembleDebug assembleRelease
testDebugUnitTest lintDebug -PwarningsAsErrors=true` green, **446 JVM tests** (443 before), 0 failures; lint unchanged
in the touched files; `python3 -I scripts/fake-storytel.py --selftest` OK.
- **M1** (a new book never posted the Nytt notification until the next sync): `DownloadWorker.doWork` calls
  `NewNotifier.update(applicationContext, store.snapshot())` beside both `Sync.writeReport` calls, so the "N nye i
  Musikk" notification and the launcher's badge count a book as soon as its copy is on the phone (`update` returns
  early when the count is unchanged). `GuardsTest` pins one update per report in the job.
- **L1** (a malformed `storytel.json` logged its content): `DebugStorytel.seedLogin` parses inside `runCatching`;
  a bad file is one fixed line ("not a JSON object with an email and a password - ignored"), still deleted, and the
  rest of the broadcast (Sonos seed, library, listings, sync) applies. `SourceScanTest` pins the `runCatching`.
- **L2** (`DebugStartup`'s order; a leftover base on a real phone): the provider has `android:initOrder="1000"`
  (created before WorkManager's provider can start a job); `StorytelEndpoints`' readers take the base only for
  `10.0.2.2`, `127.0.0.1`, `localhost` or `::1` (no userinfo), anything else is Storytel's hosts. `StorytelApiTest`:
  the four readers for each accepted host and for a LAN address, another host, a look-alike name, userinfo, no
  scheme, junk, empty and null; `SourceScanTest` pins the `initOrder`.
- **L3** (§6i): one recursive `grep -raE -l` over the app's private data (Room's and Media3's WAL files,
  `shared_prefs`, the report, `no_backup/`) replaces the `cat` with the `*.exi` glob; "Copy only" is checked with the
  fake running (no `asset`/`cdn` line while a downloaded book plays and seeks) and then stopped (it still plays); a new
  line for the 10-minute job limit (`adb shell cmd jobscheduler timeout me.vibb.music.debug` mid-book: the next job
  mints a new token and resumes with `206 Range bytes=<N>-`). §6i's intro names the host rule.
- **L4** (three wiring lines unpinned): one `GuardsTest` row - `newsEligible` in `Sync.applyListing`'s `markSeen`,
  `storytelEntries`/`reportWaiting`/`StorytelAccount.get(context).state()` in `writeReport`,
  `listOfNotNull(nextRetryAt, run.storytelRefusedUntil)` in the worker (with M1's lines).
- **L5** (the clean cache index rests on the canonical URL too): `StorytelApiTest` pins `mintedUrl(x)` as OkHttp's
  canonical form (re-parsing gives the same string) over a `|`, a space, an upper-case host with the default port, the
  fake's URL, dot segments and the fixture's Location; the `MediaCache` comment says no-redirect and the canonical
  form together keep tokens out of `exo_redir`.
- **L6**: `Snapshot.kt` carries the vibb MIT credit (`content.py` `expand_entries`/`PREFER_REMOTE`, `storytel.py`
  `entries_for(remote)`).
- **Spec text** (the six problems the status found, confirmed by QA): §0 (Storytel CDN requests never follow a
  redirect, the canonical URL, a 3xx is a transient `http_30x`, the device check; the §6i grep over the data folder),
  a new §9 row (vibb's `urlopen` follows; the `DataSource` wrapper as the fix if the real CDN redirects), §4.2 (`mint`
  through `StorytelAccount.get(context)`), §4.3 (`waiting: login` after `NotConfigured` or `Refused`; `LoginFailed`
  reads as the shared rule, M4), §4.6 (the four reader functions, the 10.0.2.2/loopback rule, `DebugStartup` with its
  `initOrder`, the next generation, the malformed file), §8 step 3 (the bookmark-first check by dropping and re-adding
  the entry; the secret grep over `databases/exoplayer_internal.db`, `music.db` and their WAL files; the job-limit
  check).

Owed: unchanged from the status above (the §6i emulator checks, now with the job-limit line, and the device checks).

## Merge of music-1b-sonos and main (2026-10-12)

`origin/music-1b-sonos` at `39b59dd` (which holds `origin/main` at `444ca58`) merged into `music-2-storytel` at
`7d93478` (`49770d8`, one merge commit, no rebase), then four commits: the migration (`e14bf36`), the emulator
section (`24ce749`), the adaptation (`6566d8b`), the two Lows (`d5da1c2`), and this section. Written here at the end,
after step 3's status, because this design's "Release" sits before §12 rather than last.

**What came in:** 1b's correction round after the main session's review and its fix round (26a: no socket bound to
the Wi-Fi `Network`, the volume cap at a session's start and on a lowered cap, the bedtime stop's looks and park
budget, the hidden position while grouped away, `PlayBody`'s tailnet filter, a `Room`'s members with unknown uids,
the fake speaker's fixture answers and `--volume`), and 1b's merge of main: the launcher security wave, designs 25
and 25f with migrations 0065-0069, design 27's spec (0070 reserved for it), the music app's instant switch adapted to
the renderer axis (`PlayerHub.State.waiting`, `SWITCH_WAIT_MAX_MS` 25 s, `playSwitch`/`skipSwitch` with `onRoom`,
`playerNumber`), the Gradle verification metadata (1b and this branch add no dependency), 1b's migration as 0071 and
its emulator section as §6h. No AIDL, `bridge_contract.json` or shared snapshot changed on the way in.

**Conflicts and how they were resolved:**
- `ui/UiModel.kt`: both branches add `playerNumber(snap, now)` in the same place. Storytel's body is kept (a Storytel
  entry counts the series' reading order through `countedOrder`, §3.5 and §13 M5; every other entry the list order,
  which is 1b's body), with 1b's note that it serves the player and a tap's switch alike, on the phone or a room; both
  imports (`countedOrder`, main's `coverTapStart`).
- `ui/MusicRoot.kt`: the `PlayerScreen` call takes main's `position`/`skip` and 1b's `onRoom` and `hub.waiting` code;
  the `playerNumber` call is the same on both sides.
- `ui/PlayerScreen.kt`: Storytel's "Del/Spor/Episode N av M" line with main's `now.itemTitle`.
- Merged by themselves and read through: `docs/testing/emulator.md` (three sections after §6f now), `music.yml` (both
  fakes' selftests and path filters beside main's wrapper validation), root and music `CLAUDE.md`, `server/CLAUDE.md`,
  `PlaybackService.kt`, `MusicViewModel.kt`, `LibraryScreen.kt`, `DebugFeedReceiver.kt`, `SourceScanTest`,
  `GuardsTest`, `server/src/{main,music}.rs`, `device_detail.html`, PLAN.md, design 26.

**`git bisect skip 49770d8`**: the merge commit alone has two 0066 migrations (main's `0066_lock_glance.sql` and ours)
and fails `cargo test` (`UNIQUE constraint failed: _sqlx_migrations.version`); `e14bf36` fixes it, and CI only ever
built the head (qa-26b-merge-code Low 3).

**A dev database from before the merge won't open**: a `server/data/kidphone.db` that ran either music branch's server
before 2026-10-12 holds their old 65/66 with other checksums, so the merged server stops at `VersionMismatch(65)`;
delete it or restore a backup taken on `main` (`docs/testing/emulator.md` §2; qa-26b-merge-code Low 4). No deployed
server ran either branch.

**The migration is `0072_music_storytel.sql`** (was 0066, which main's `0066_lock_glance.sql` now has; main holds
0065-0069, 0070 is reserved for design 27, 1b holds 0071). Content unchanged but the header (the 0072 wording, the
renumbering and the backup hazard); §2.8's block is the file byte for byte. `server/CLAUDE.md` (the Storytel section's
title and the Data model's hazard paragraph), the comments in `music.rs`, `music_sweep.rs` and the Storytel server
test, this design's steps and the 2a/2b statuses, PLAN.md and design 26 follow. The hazard is 26a's: a lower number
(0070, or 0071 were 1b's server not deployed first) reaching a server that has run 0072 is applied out of order with
no pre-migration backup (`pre_migration_backup` compares only `MAX(version)`); "Release" says so.

**The emulator section is §6i** (`docs/testing/emulator.md`: main's §6g security checks, 1b's §6h fake speaker, ours
§6i fake Storytel). This design's references follow (its §6h -> §6i, its mentions of the Sonos section §6g -> §6h),
`music.yml`'s selftest comment, `fake-storytel.py`'s header (it gains the reference), `DebugStorytel.kt` and
`DebugFeedReceiver.kt`. QA review records keep the numbers they were written against. `music/CLAUDE.md` and root
`CLAUDE.md` don't name the Storytel section yet (step 5's docs).

**The adaptation (`6566d8b`).** A tap's switch on a room counted the screens' snapshot, which is the phone's
(`forRenderer(false)`) until step 4b; the room's queue after 4b holds every book (`forRenderer(true)`, §5.6).
`playSwitch` now reads `snap.forRenderer(onRoom)` itself: the entry, its order and the cover tap's or pick's start come
from the renderer's view, then on a room the speaker filter. Today `PlayBodies.speakerDropReason` drops every Storytel
book ("withdrawn": no url), so a Storytel tap on a room is no switch - and the service says "Kan ikke spilles på
Sonos" for the same reason: they agree. With 4b's filter (§5.1: a Storytel book is speaker-playable, downloaded or
not) the view queues the whole series, starts a cover tap at a bookmark that isn't on the phone, at its place, and
numbers it in the series. (The filter was a parameter here so that seam could be tested now; the fix round below
dropped it - 4b adds those assertions with the real filter.)
`skipSwitch` counts in the hub's queue (the service's `Active.items`): unchanged, right for both renderers.
`playerNumber` is renderer-independent for Storytel (`snap.listed`) and the same on both views for every other
source. Checked and left: `PlayBody`'s tailnet filter (a minted CDN URL is public, never a tailnet literal; the fake's
10.0.2.2 is a private literal, §5.1's `privateMintAllowed()` covers it), the start cap (nothing to do before 4b), the
grouped-away hidden position and `storedUri`/`consumableIdOf` (unwired until 4b; notes below). Tests: `SwitchViewTest`
+2, a `GuardsTest` pin.

**The two Lows from the main session's review of step 3 (`d5da1c2`):**
1. `DownloadRun.fail()`: a failure after the partial copy grew counts as the first again (attempts 1, the 2-minute
   wait), so a slow but progressing download never backs off towards 6 h while it gets further; only failures that get
   nowhere double. A not-done row's `bytes` hold the partial's size at its last failure (carried across an attempt's
   row write, 0 when the partial is dropped); `DownloadIo.cachedBytes` reads `MediaCache.cachedBytes`. No Room schema
   change (the column exists; its KDoc says both meanings). NRK/RSS get the same rule (their partials resume from the
   same url too). `StorytelDownloadTest` +1; dropping the growth check turns it red (checked). (The fix round below
   adds a floor: growth counts from max(1 MiB, 1 %).)
2. `StorytelAccount.mint` is `runInterruptible(Dispatchers.IO) { api.assetUrl(...) }`: a cancelled caller (a download
   job WorkManager stops - **not** 4b's superseded play: a room's actor never cancels a job, a superseded play is
   dropped by its generation and token and a mint already running finishes; corrected in the fix round below)
   interrupts the blocked thread - the login lock's `tryLock` wait ends at
   once, an OkHttp call at its next read or write (Okio checks the flag; a socket read blocked mid-packet still ends
   only at its timeout) - and gets its `CancellationException`, never the mint's answer. `lockWithin` already keeps the
   interrupt; an interrupted login is a `LoginFailed` inside, never a refusal (no cooldown). `StorytelApiTest` +1 (a
   waiter on another's login and that login's own blocked request each end within a second when cancelled, no cooldown
   stamp, the next mint logs in); `GuardsTest`'s pin follows.

**For step 4b** (what 1b's changes since `9ecdad8` mean for it):
- **The late mint inside the room's actor**: the body is built on main (`PlayBodies.build`) before `work.submit`;
  Storytel's goes inside the job (IO) - the token check (M6), the mint, the token check again, then `transport.play`.
  The start cap and the hush are inside `transport.play` (after the mute, before `SetAVTransportURI`), so a failed
  mint sends nothing and touches no volume; nothing to change for the cap. The superseded branch (`g != gen`) keeps
  `Playing(..., res.uri)`: in memory only - any write of it goes through `storedUri`. The mint's HTTP uses
  MediaCache's guarded client, never a socket bound to a `Network` (1b's correction 1 holds for it).
- **Adopt by `consumableId`**: `SonosSession.classify` (`ours = normUri(trackUri) == normUri(uri)`),
  `SonosLifecycle` (`ours` against the stored uri, and the built-uri match) and the standalone bedtime path's new
  looks (`adoptLive(..., aux = false)` with its `keep`) compare normalised uris. With `renderer.json` holding
  `storytel:book:<cid>` (`storedUri`), each must use `sameStream` for a Storytel entry, and the adopted session's uri
  must be the live signed one (§5.3), or every adopted book reads as not ours. **The stakes rose**: since 1b's
  "grouped 2" a fresh, reachable, not-ours snapshot shows position 0 with no duration (`hidesPosition`) and is never
  bookmarked - a wrong comparison now shows 0:00 at once instead of the last position.
- **The migration probe** ("migrate 1"): an adopt goes out with the hint's uri when it differs from the session's -
  exactly a re-signed Storytel URL; its write to `renderer.json` goes through `storedUri` like `host.played` and
  `lookAtRoom`'s (§5.3's list gains it), and `volOpt` resets (1b's rule).
- **`speakerDropReason`**: Storytel needs its branch before `item.url ?: return "withdrawn"` (§5.1); the minted URL then
  gets the private-literal check unless `privateMintAllowed()`, and the tailnet check like any url.
- **The screens**: the switch reads the room's view now (above); 4b gives `MusicViewModel` the renderer's snapshot
  (§3.2), so the carousel, the list and a stored queue on a room (`nowView` from the stored row filters by the
  screens' order) show undownloaded books.
- **The start cap on a Storytel play**: a mint of up to 10 s delays the cap and Play; the switch's hold isn't affected
  (the controller moves the index on main before the job; `PlayerHub.State.waiting` counts the command, not the job).
- **The serial actor behind a mint** (qa-26b-merge-code Low 1, Low 6): the room's `SonosActor` runs one job at a time,
  so every job behind a mint - polls, pause, the volume keys, a room switch's hand-off - waits up to its 10 s, and
  nothing interrupts it: `runInterruptible` helps only a cancelled caller, and the actor never cancels a job. Decide
  whether that is acceptable or the mint moves before `submit` with the token checks around it. **Trap**: don't add
  per-job cancellation (a child `Job` cancelled on supersede) as is - the mint's `CancellationException` would come out
  of `job()`, `SonosActor.run` rethrows it and the consumer loop ends silently (later `submit`s queue forever, `call`
  never completes, the poller hangs: a dead room with no log line). If a job must be cancellable, run it in its own
  child and turn its cancellation into a `Result.failure` inside the job while the consumer is active, with a
  `SonosActorTest` row ("a job cancelled from outside, the next job still runs").
- **`SWITCH_WAIT_MAX_MS`** (25 s, `logic/Switch.kt`) is sized for `HANDOFF_MS` 15 s (main's switch came in with this
  merge, after §5.1 raised `HANDOFF_MS` to 25 s for the mint): raise it with `HANDOFF_MS` and update `SwitchTest`'s
  `SWITCH_WAIT_MAX_MS > 15_000` (it names `HANDOFF_MS`), or a tap during a room switch can run out into the old item.
- **`SonosSuspension.kt`** (`entryMatcher` and `bookmark`, the standalone bedtime path) still read `store.snapshot()`,
  the phone's view; §5.6's table says `forRenderer(true)` there.
- **The minted URL's address checks go in the actor job**, after the mint: `speakerDropReason` runs when the queue is
  built, before any URL exists, so its Storytel branch can't check one. The private-literal check (unless
  `privateMintAllowed()`) and the tailnet check of the minted URL share one helper with `speakerDropReason`'s literal
  checks.
- **`SwitchViewTest`**: when `speakerDropReason` lets Storytel through, its room case gains the whole series (every
  book in the queue, a cover tap at the bookmark's book not on the phone at its place, "Del 2 av 5", an entry with
  nothing on the phone switching on a room) with the real filter.

**Tests after the merge** (local Rust 1.97, the sandbox's JDK 21): server `cargo test` **628 passed, 0 failures**,
`cargo fmt --check` clean, `cargo clippy --all-targets` the same 12 warnings (`models.rs`, `dns_engine.rs`,
`handlers/devices.rs`; none in Storytel or Sonos code); music `assembleDebug assembleRelease testDebugUnitTest
lintDebug -PwarningsAsErrors=true` **499 JVM tests, 0 failures** (446 at step 3's fix round; 495 with the merge alone,
+2 for the adaptation, +2 for the Lows), lint 0 errors and the same 40 warnings as 1b's merge;
`python3 -I -B scripts/fake-sonos.py --selftest` and `python3 -I -B scripts/fake-storytel.py --selftest` OK; launcher
`assembleDebug testDebugUnitTest` (the tsnet stub) **1012 tests, 0 failures** - its `assembleRelease` needs
`app/libs/tsnet.aar` (`checkReleaseHasTsnet`), which CI builds and this sandbox can't: not run. The AIDL copies,
`bridge_contract.json` and the shared snapshots are byte-identical (`ContractFilesTest`, `BridgeTest`, `MusicPlanTest`
green).

### Fix round after QA's code review (qa-26b-merge-code, 2026-10-12)

`docs/design/qa-26b-merge-code.md`: the merge is clean; 0 High, 0 Medium, 6 Low, all fixed (its "not worth fixing"
list left as QA judged it). `music/` and docs only; no server or launcher change.
- **L1, `runInterruptible` and the actor.** `StorytelAccount.mint`'s KDoc and this section's Lows item 2 no longer
  claim it covers 4b's superseded play: it helps a download job WorkManager stops; a room's actor never cancels a job
  (a superseded play is dropped by its generation and token, a running mint finishes). The trap (per-job cancellation
  would end `SonosActor.run`'s loop silently) and the serial-actor cost are in the 4b list above; §4.2 says it too.
- **L2, a growth floor.** `DownloadRun.fail()` resets the count only when the partial grew by at least
  max(1 MiB, 1 %) since the last failure (`grewEnough`, `GROWTH_FLOOR_BYTES`): a host that drips a few KB and resets
  backs off as before, a real resume starts at 2 min again. `StorytelDownloadTest` +1 (three 10 KB drips count up to
  4 and the plan waits 16 min; 400 KB on 40 MB counts up; 1 MiB past it resets; the floor's edges, 1 % of 300 MB, a
  dropped partial).
- **L3**: the `git bisect skip 49770d8` note above.
- **L4**: the dev-database line above and in `docs/testing/emulator.md` §2.
- **L5, the spec text.** §4.2 (`mint` is `runInterruptible`, and what it does and doesn't cover), §3.3 (the growth
  reset with its floor, for every source), §5.7 (the not-ours line named by function, `SonosSession.classify`, not by
  line number).
- **L6.** `playSwitch` lost its test-only `speakerPlayable` parameter (back to 1b's signature; the filter is
  `PlayBodies.speakerPlayable`, as the service's). `SwitchViewTest`'s room case now tests the real filter (no switch on
  a room today, with or without books on the phone; the phone's view whatever the snapshot; next on a playing room from
  0 with "Del 2 av 5" -> "Del 3 av 5"); the whole-series assertions wait for 4b (its list says so). `GuardsTest` still
  pins the switch's `forRenderer(onRoom)`. The 4b list gains the serial actor, `SWITCH_WAIT_MAX_MS` with `HANDOFF_MS`
  and `SwitchTest`'s 15 s assumption, `SonosSuspension`'s phone view, and the minted-URL checks in the actor job.

Tests: music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **500 JVM tests, 0
failures** (499 before: +1 `StorytelDownloadTest`), lint 0 errors and the same 40 warnings;
`python3 -I -B scripts/fake-sonos.py --selftest` and `python3 -I -B scripts/fake-storytel.py --selftest` OK. Server and
launcher untouched in this round (no file their tests read changed).

## Merge of music-1b-sonos with design 27 (2026-10-12)

`origin/music-1b-sonos` at `50958b0` (26a's merge of `origin/main` at `620e578` - design 27 and its QA fix round - with
its own QA and fix round; 36 commits after `39b59dd`, the 1b head this branch merged last) merged into
`music-2-storytel` at `7ad8b26` as one merge commit, `45ca74e` (no rebase), with the adaptation and its tests in it; this section
and the spec's edits for it (§0, §5.1, §5.5, §7, §8, §9, §13, the device checks, "Release") in the next commit.

**What came in:** design 27 (`docs/design/27-music-episode-art.md`, `qa-27-code.md`) through 1b: migration
`0070_music_episode_art.sql` and the server's art pass (`music_sweep.rs`'s `art_pass`/`art_candidate`,
`photos::MUSIC_ART`, `music::art_in_library` behind the covers route, `music_sources.rs`' 512 px pick,
`src/tests/music_art.rs`); the listing's item `art` as the 64-hex hash of the item's own 512 px JPEG
(`server/testdata/music_listing.json` and the music copy); Room version 2 (`items.artHash`, `AutoMigration(1, 2)` with
`ReapplyListings`, `schemas/.../2.json`); `wantArt` appended to `IMusicBridge.aidl` and `want_art` in both
`bridge_contract.json` copies; the launcher's courier (`MusicPlan.artToFetch`/`artToDelete`, `MusicSync.syncArt`,
`MusicBridgeService.wantArt`); the music app's `logic/EpisodeArt.kt`, `sync/ArtPlan.kt`, `ui/EpisodeCarousel.kt`, the
player's art, `Cover`'s fallback, `Store.coversVersion`; and 26a's adaptation of it to the renderer axis (`SKIP_TO` /
`THEN_SKIP_TO` under `switching` and in `Commands.MOVES`, `goTo` -> `renderer.seekTo`; `Renderer.itemAt` /
`replaceItem` for the late picture; `Switch.kt`'s `via`; `swipeSwitch`/`toIndex` with `onRoom`; `playerNumberOf`;
`toRoom`'s lookup by key, qa-26a-merge27-code L1), 0071 frozen (L2), 26a §9's no-art-to-the-speaker row (L3). Also
main's lock and wallpaper fixes that 1b took with design 27, and the launcher's tests for them.

**Conflicts and how they were resolved** (eight files; git merged the rest - `MusicRoot.kt`, `Snapshot.kt`,
`music/CLAUDE.md`, `music.yml`, `docs/testing/emulator.md`, `PLAN.md`, `GuardsTest`, `SourceScanTest`, `Fixtures`,
`Sync.kt`, `music.rs`, `handlers/music*.rs` - and each was read through):
- `ui/UiModel.kt`: the numbering, resolved by qa-26a-merge27-code's instruction ("The Storytel seam"): one private
  `countedKeys(snap, now)` = `countedOrder(now.entry, listOrder(snap, now).map { it.key }, snap.listed[now.entry.id]
  .orEmpty().map { it.key })`; `playerNumberOf(snap, now, item) = countedKeys(snap, now).indexOf(item.key) + 1`;
  `playerNumber(snap, now) = playerNumberOf(snap, now, now.item) to countedKeys(snap, now).size`. Storytel's order
  inside, 1b's two-function shape outside, so the carousel's landing page and the player can't disagree.
- `ui/PlayerScreen.kt`: 1b's `numberOf: (PlayItem) -> Int` (main's signature) replaces Storytel's `number: Int`;
  main's `PlayerArt`/`Crossfade` block replaces the old cover + `PrefetchCovers` (the carousel prefetches now), and its
  number line takes Storytel's `when (kindOf(now.entry))` (`track_of`/`part_of`/`episode_of`), so the landing page says
  "Del N av M" for a book. `MusicRoot.kt` merged by itself to 1b's call (`{ item -> playerNumberOf(s, n, item) }, of`).
- `playback/PlaybackService.kt`: one import hunk (`smallArtMissing` and `sourceId` both). The rest merged by itself and
  was read: Storytel's `mediaItem` branch (`copyOnly`, the source id as uri) gets design 27's `artUri(a, item)` like
  every item; `load()` keeps `forRenderer(false)` (the service's snapshot is the phone's until 4b); `refreshArt`,
  `skipTo`, `sendWantArt` are 1b's renderer versions.
- `ui/SwitchViewTest.kt`: both sides' tests kept (Storytel's two, 1b's three swipe tests).
- `src/debug/.../DebugFeedReceiver.kt`: 1b's "cover JPEGs and episode pictures" line with Storytel's
  `storytel_base`/`storytel.json` lines, and the §6h/§6i reference.
- `server/CLAUDE.md`: both new sections (Storytel's and design 27's episode art); the migrations paragraph takes 1b's
  text (0070 is design 27's, the frozen-file rule) plus 0072's line: every build of this branch since this merge
  carries 0070-0072 in order; never deploy one from before it.
- `server/src/music_sweep.rs`: the module header credits both `storytel.py` and the `pipewire` branch's art helpers.
- `server/src/tests/mod.rs`: `mod music_art;` and `mod music_storytel;`.
- Byte-identical, checked after the merge: both AIDL copies and `bridge_contract.json` (main's, via 1b: `want_art`
  beside 1b's names; this branch appends nothing), `music_library.json` (this branch's Storytel entry; main didn't
  change it), `music_listing.json` (main's, with the art hash; this branch didn't change it) and `music_icons.json`.
  No snapshot needed regenerating: `music_library_snapshot` and `music_listing_snapshot` pass on the merged tree (the
  listing fixture has no Storytel item, so 27's hash and this branch's entry don't meet in one file).

**The adaptation:**
- **The numbering seam** (above), with a `SwitchViewTest` case: books 1 and 3 of five on the phone, each with its own
  art hash so the player shows the carousel; the landing page for book 3 says "3 av 5" (`playerNumberOf` over the view
  that shows), the swipe's switch says "3 av 5", the player after arrival "3 av 5", and book 1's page from there "1".
  With `playerNumberOf` over `listOrder` alone the landing page would say "2 av 5".
- **Storytel's switches vs 1b's swipe.** `playSwitch` reads `snap.forRenderer(onRoom)` (this branch's); `skipSwitch`,
  `swipeSwitch(..., onRoom)` and `toIndex(..., onRoom)` count in the hub's queue (`now.queue`, the service's
  `Active.items`), right for both renderers as before. A swipe on a room of a Storytel queue goes through the same
  `SKIP_TO` -> `goTo` -> `renderer.seekTo` (today no Storytel book reaches a room's queue: `speakerDropReason`, 4b).
- **`Snapshot.forRenderer` vs 27's art and 1b's hidden position.** `forRenderer` is a `copy(remote = ...)` of the
  snapshot, so `PlayItem.artHash` (now filled by `buildSnapshot` from `ItemRow.artHash`) is the same on both views;
  `hidesPosition` is the controller's (a not-ours speaker shows 0 with no duration) and never reads the snapshot.
- **The art want set for a Storytel series** (`sync/ArtPlan.kt`'s `artWant`): it walked every non-own entry with
  phase 1's `keepSet`/`wantedDownloads` (newest `cache` items, newest first), so a series kept as "the first 5 books"
  would want the art of the newest five. It now uses `downloadPlan`'s own rule for a Storytel entry
  (`storytelKeepSet` + `storytelWanted`: the bookmarked book first, then reading order), so the pictures wanted are
  the books on the phone. Today a Storytel item has no `artHash` (the server serves none until 4a), so nothing
  changes until then. `EpisodeArtTest` +1 (five books, cache 2, the bookmark on book 4: wants 4, 1, 2, 5; not 3).
- **No double booking of the network gate.** The phone never fetches art itself: `artWant` only names hashes,
  `wantArt` carries them over the AIDL, and the launcher's `syncArt` fetches them in its own sync (≤ 1 MB each,
  hash-checked). `DownloadRun`/`DownloadPlan` (audio, Storytel's mints included) are the only network the music app
  plans; nothing in `ArtPlan` touches the network gate, WorkManager or `DownloadRun`'s backoff.
- **Room and `SourceScanTest`.** Room is main's version 2 (`schemas/.../2.json` untouched, no 3.json); this branch had
  no "version 1" pin (its pins are `fallbackToDestructiveMigration` absent, `SourceScanTest`, and design 27's
  `EpisodeArtTest` schema/migration pins, both green). The abandoned 4a (`items.art`, its own Room 2, branch
  `abandoned/26b-step4a`) is not applied.
- **The server's Storytel listing test.** Design 27 made the listing's `art` the hash of a stored picture and the art
  pass takes only items with a `url`; a Storytel book's `art_url` (its cover, still stored) therefore reaches no phone
  until 4a. `the_sweep_lists_a_series_like_a_feed` now expects a null `art` (it expected the URL) - the check still
  makes one request (the series cover) and the stored `art_url`s are unchanged.
- **Spec text.** §0, §5.1, §5.5, §7's rows, §8's 4a and 4b, "Shared contracts", §9 (a row for `_sonos_body`'s art
  and the per-book covers row), §13 M8, the Sonos device check and "Release" follow: no art to the speaker (26a §9), and
  4a is server work (below).

**Step 4a, rebuilt on design 27** (from 26a's "For the Storytel branch" list; §8 step 4a and §5.5 now say this):
- **Where a book's cover comes from**: the item's `art` hash in its listing, nothing else. The phone needs nothing:
  `PlayItem.artHash` is "the item's own art" (`ItemRow.artHash`, Room 2) and every surface does `artHash ?:
  entry.cover` (the carousel - `showsCarousel` wants two different pictures on its pages, the entry's cover counting
  as one -, the player, the session's artwork, `setNowPlaying`'s `artOrCover`, Nytt, search); the courier (`artWant` ->
  `wantArt` -> the launcher's `syncArt` -> `GET /api/devices/music/covers/{hash}`) carries it; the prune follows the
  want set. Room gets no column; no AIDL or `bridge_contract.json` change.
- **Server, one step with its tests**: widen `art_candidate` to a Storytel book (`ok`, `url` NULL, `art_url` = the
  shelf's cover; it needs the source or a flag - the `Item` alone can't tell a book from a withdrawn episode);
  `art_pass`'s order reads a Storytel entry as oldest first (reading order; the `auto` arm makes every non-series link
  newest first today); `storytel_check` carries the kept book's `art_*` fields over from the old item (it builds each
  `Item` from `..Item::new(..)`, which would drop the hash every check and refetch the picture); `art_in_library`
  serves `source IN ('nrk', 'rss', 'storytel')`. Design 27's judging applies unchanged (a book whose cover is the
  series' is `same`: no hash, the entry cover shows; budgets as for NRK/RSS). The alternative - the Storytel check
  storing `music_items.art_hash` itself through `photos::MUSIC_ART`'s store and lock - duplicates the pass's fetch,
  judging, budget and prune; only if the pass can't be widened cleanly. Tests in `music_storytel.rs` and
  `music_art.rs` (§8); `the_sweep_lists_a_series_like_a_feed`'s null `art` becomes the hash. The listing snapshot is
  regenerated (`MUSIC_LISTING_SNAPSHOT_WRITE=1`) in both copies, one commit, only if its fixture gains a Storytel item.
- **The speaker gets no art** (26a §9); 26a's later `art_src` path would carry Storytel's public cover URL unchanged.
- **Owed emulator checks** (§6i): a series' books show their own covers in the carousel and in Nytt; an upgrade from a
  step-3 build keeps every position and bookmark (Room 1 -> 2 is main's `AutoMigration(1, 2)`; `ReapplyListings`
  re-applies the listings with their hashes at the next sync).

**A dev database from before this merge won't open** (qa-26b-merge27-code Low 4): a `server/data/kidphone.db` that ran
this branch at `7ad8b26` or earlier holds 1b's older 0071 header and 0072 without 0070, so the merged server stops at
`VersionMismatch(71)`; delete `server/data/` first (or restore a backup taken on `main`), as the note above says for
65/66. No deployed server ran this branch.

**Tests after the merge** (local Rust, the sandbox's JDK 21): server `cargo test` **655 passed, 0 failures** (628 before; +27 from 1b's merge of design 27, the Storytel listing test adapted; the time-dependent `parent_pin` passed this run), `cargo fmt --check`
clean, `cargo clippy --all-targets` the same 12 warnings (`models.rs`, `dns_engine.rs`, `handlers/devices.rs`); music
`assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **542 JVM tests, 0 failures** (500
before; 540 with the merge alone, +2 for the adaptation: `SwitchViewTest` "3 av 5", `EpisodeArtTest` a Storytel
series' want set), lint 0 errors and the same 40 warnings; `python3 -I -B scripts/fake-sonos.py --selftest` and
`python3 -I -B scripts/fake-storytel.py --selftest` OK; launcher `assembleDebug testDebugUnitTest` (the tsnet stub)
**1035 tests, 0 failures** (1012 before; 1b's count) - its `assembleRelease` needs `app/libs/tsnet.aar`, which CI
builds: not run.

## Step 4a implementation status (2026-10-12)

Built on `music-2-storytel-4a` from `c431b61`; the code in `acaf179`, this section in the next commit. Server only: no
migration (0070-0072 untouched), no contract file, no music or launcher code.

**What was built** (§5.5, §8 step 4a, with qa-26b-merge27-code's M1 and L1-L4 folded in):
- `music_sweep::art_candidate(item, link)`: an `ok` item with an `art_url` that is playable from the list - an NRK/RSS
  item with its `url`, or any item of a `Link::Storytel` entry (`url` NULL by design). The entry's link decides, not
  `url` being NULL (an NRK/RSS item without a URL stays out and takes no place in `ART_WINDOW`).
- `art_pass`'s order: `auto` is now per link - Storytel from book 1 (the phone's `naturallyNewestFirst(STORYTEL) =
  false`), an NRK series as before (oldest first unless it keeps the newest), a podcast or feed newest first; an
  explicit `newest_first`/`oldest_first` applies to every source, as on the phone (§3.2).
- `storytel_check`: a book already listed starts from its old row (`plan.items[at].clone()`, as `merge_feed` keeps a
  feed's known item) with the shelf's fields set over it (`state`, `title`, `url` NULL, `hls`, `duration_ms`,
  `art_url`, `attempts` 0); a new book from `Item::new`. So every `art_*` field - `art_fetched_at`, `art_fails` and
  `art_tone` included - and the first sighting survive a check. A changed cover URL is the pass's, by design 27's
  rule: the same path under a new query is adopted with no request; another path is fetched again once its judged
  result is a week old (`ART_MOVED_RETRY_DAYS`); a cover that leaves the shelf makes the book untried (no hash).
- `music::art_in_library`: `source IN ('nrk', 'rss', 'storytel')` plus `storytel_gate!()` (the phone's switch and a
  stored login), so the cover route serves a book's cover only to a phone that gets the entry.
- `music_sweep::restamp_versions`: Storytel listings too (L1), so after a restore forgets a book's cover the library
  names the listing the route serves.
- Docs: `server/CLAUDE.md`'s episode-art section (the Storytel paragraph, the test file); §8 step 4a's test and
  emulator text, the "Per-book covers" device check, the merge section's `VersionMismatch(71)` note (L4).

**Design 27's machinery for a book cover** - all applies unchanged; nothing needed a Storytel branch beyond the above:
- The judging: the series' cover is the first-seen book's (`normalize_shelf`), so that book's `art_url` equals the
  listing's `cover_url` and is `same` at step 1 (no request, no hash, the phone shows the entry cover); a re-encoded
  copy under another URL is `same` by print. Every other book with a cover is `own`.
- The budgets (`ART_PER_CHECK` 20, `ART_PER_DAY` 400, shared with NRK/RSS) and `ART_WINDOW`: a 40-book series fills
  over two checks, in reading order.
- The 16:9 reference fetch: makes no sense for a book (it is NRK's root `image`); a Storytel check never sets
  `alt_candidate`, so the pass reads no 16:9 picture and sends no request for one. Nothing to do.
- The twin merge: right for books - series covers are often one template with another title, and `twins` (2 bits, the
  same tone, not `low_detail`) is exactly design 27's guard against merging "titles on one background".
- The backlog: rarely trips (books seldom share the series picture), comes back monthly as for feeds.
- The restamp: a new hash changes `build_listing`'s version, so the check reports `changed` and nudges the phones; a
  forgotten one restamps through `restamp_versions`.
- Not done: the PWA card's "· N with episode art" stays off Storytel cards ("N books" only); a one-line follow-up if
  wanted.

**Tests**: server `cargo test` **660 passed, 0 failed** (655 before; +5: `music_storytel.rs`
`a_check_keeps_a_books_cover_and_a_moved_cover_is_fetched_again`, `the_art_pass_reads_a_series_in_reading_order` (the
restamp and the nudge when a cover arrives, the library naming the new version), `a_books_cover_is_served_only_behind_the_gate`
(ticked + switch on 200; unticked, switch off, login cleared 404), `a_lost_books_cover_restamps_the_listing_and_is_fetched_again`,
`a_failed_books_cover_waits_a_day_like_a_feeds` (M1's pin: a 404 cover, a routine check and Check now the same day
send no image request, `art_fails` stays 1, the row unchanged; a day later the second try); `music_art.rs`'s
`items_without_a_source_url_or_a_picture_url_are_skipped` rewritten for the link argument (a Storytel book is a
candidate, an NRK/RSS item without a URL isn't); `the_sweep_lists_a_series_like_a_feed`: two requests (the series
cover, book 1's), `items[0]`'s `art` the hash and served by the cover route byte for byte, `items[1]` null (`same`),
`items[2]` null (no cover). The time-dependent `parent_pin` passed this run. `cargo fmt --check` clean, `cargo clippy
--all-targets` the same 12 warnings (`models.rs`, `dns_engine.rs`, `handlers/devices.rs`). Mutations (reverted):
rebuilding a kept book from `Item::new` turns 4 `music_storytel` tests red (the failed-cover and keep tests among them);
`auto` newest first for Storytel turns the reading-order test red; `art_in_library` without the gate turns the gate test
red.

**Deviations, with reasons**:
- The reading-order case lives in `music_storytel.rs`, not `music_art.rs` (it needs the Storytel login and shelf
  helpers); `music_art.rs` holds the candidate rule. `music_art.rs`'s `picture`/`png` are `pub(super)` for it.
- No listing snapshot change: `music_listing.json`'s fixture stays NRK/RSS (its own-art hash already pins `art`), the
  Storytel cases have their own tests - so no music or launcher run was needed and none was done.
- The test shelf with changed covers is edited as text (`shelf_covers`): re-serialising it through `serde_json::Value`
  sorts the keys in this build, which makes book 1 the first-seen book and its cover the series' - the existing
  `shelf_with`/`shelf_without` helpers have that property too, harmless for their tests.

**Where the plan was wrong**: as qa-26b-merge27-code found - (1) "a second check fetches nothing" doesn't pin the
carry-over (`known_art` restores an own/same hash from the old row with no request); the failed-cover test does; (2)
`restamp_versions` was a second `IN ('nrk', 'rss')`; (3) in `the_sweep_lists_a_series_like_a_feed` it is `items[0]`
that gains the hash, `items[1]` stays null; (4) the emulator can't show a book's own cover (the server's shelf URL is a
constant and its art pass refuses a private host). Also: "the `auto` arm treats non-series links as newest first" made
a Storytel entry `auto` newest first - fixed per link, not by treating Storytel "like an NRK series" (the same result
today, since its `keep_end` is `first`, but not tied to that column).

**Owed**: emulator §6i - an upgrade from a step-3 build (Room 1 -> main's 2) keeps every position and bookmark
(`ReapplyListings` re-applies the listings, with the hashes at the next sync). Device check "Per-book covers" (the
real server and shelf: each book's cover in the carousel, Nytt and the lock's tile; the series' book shows the series
cover). Chosen over a `fake-storytel.py --art` option because the fake's listing never passes through the server's
art pass, so it would test the phone's design-27 path (already covered by design 27's carousel check) rather than this
step.

### Fix round after QA's code review (qa-26b-step4a-code, 2026-10-12)

- **Medium 1 - a book is judged against nothing.** `art_pass` for a `Link::Storytel` entry: an empty reference set,
  step 1 (the cover's own URL -> `same`) skipped, and `art_own`'s twin merge off (a new `merge` argument, `!storytel`);
  any `same` row of a Storytel entry (an earlier 4a build's) is made untried at the start of the pass, so it is fetched
  again as its own. Every fetched book cover is now `own` with its own hash - the first-seen book's too (one more
  request per series, the cover's URL fetched once for the listing cover and once as that book's art; reusing the
  cover's JPEG wasn't worth the coupling). The status above is superseded where it says the first-seen book is `same`
  and that the twin merge and backlog apply to books: with no references nothing is `same`, so the backlog never turns
  off, and no two books merge. Tests: `the_sweep_lists_a_series_like_a_feed` (3 requests, `(own, same) = (2, 0)`,
  `items[1]` its own hash, served byte for byte), `a_check_keeps_...`, `the_art_pass_reads_...` (one picture a check:
  book 1, then 2, then 3), `a_lost_books_cover_...` follow; new `every_book_keeps_its_own_cover_whatever_the_series_cover`
  (the shelf's first book changes - the listing's cover moves to book 1's - and every book keeps its hash with no
  request; a row an earlier build judged `same` is fetched again and is `own`; book 3's cover is book 1's picture at
  another size - the same print and tone, a feed's twins - and keeps its own hash). Mutation (reverted): `storytel`
  forced false in `art_pass` turns 5 `music_storytel` tests red. Docs: §5.5, §8 step 4a, `server/CLAUDE.md`'s Storytel
  bullet, the "Per-book covers" device check (every book its own, a series with templated covers added).
- **Low 1 - the card line.** Chose the doc: `server/CLAUDE.md` now says the count line is not on a Storytel card
  ("N books" only); the card is unchanged.
- **Runs:** `cargo test` **661 passed, 0 failed** (660 + the new case; `parent_pin` passed this run), `cargo fmt
  --check` clean, `cargo clippy --all-targets` the same 12 warnings.
