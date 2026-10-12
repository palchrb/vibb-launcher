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
  null` (a signed URL expires, so it is never listed), the shelf's `duration_ms`, the book's http cover as `art`.
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
  `Mutex` around the login so the two never log in twice at once.
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
  redirected upstream URI in the cache index (`exo_redir`) when the CDN redirects; the minted URL itself is never
  written there (the cache key is the item), but a CDN redirect target could be - accepted (app-private
  `noBackupFilesDir`, a short-lived token), and the emulator's §6h check greps the cache index for `token=`.

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
| `_LOGIN_COOLDOWN_S`, `_cooldown_*` | server `music_storytel.login_refused_at` + `_generation`; phone `noBackupFilesDir/storytel_refused.json` | 1 h, active while `0 < now - at < 1 h` and the generation is the stored login's |
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
  - `login(state) -> Result<String, _>`: cooldown first (`music_storytel.login_refused_at` within 1 h of `Sweep::now()`
    and `login_refused_generation = generation` -> `Cooldown { until }` with **no request**); then GET
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
    shelf whatever its age (vibb "stale beats dead"), else the error. The cache's age and the JWT's expiry run on
    `tokio::time::Instant` (the tests pause it); only the DB stamps use `Sweep::now()`.
  - `forget()`: drops the JWT and the shelf (save and clear call it; the generation check covers a race).
- **Errors** carry codes for the card and the sweep: `storytel_off` (no login or no key), `storytel_key` (sealed with
  another key: "enter the login again", QA L4), `login_refused` (`Refused`/`Cooldown`), `no_jwt`, `network`, `timeout`,
  `http_<code>`, `too_big`, `bad_shelf`, `not_on_shelf` (§2.4). `music_storytel.login_error` holds such a code, never a
  message. **No message ever holds a URL**: `music_net::network_error` builds its text from `err.without_url()` for
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
- **The card `#storytel`** gains a status line from `login_ok_at` / `login_refused_at` / `login_error`: "Storytel
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
  Wi-Fi"). **The device card** (`#music`): with ticked Storytel entries and the phone's Storytel switch off, "Storytel
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
  A routine check reads `Account::shelf(300 s)`, **Check now `shelf(0)`** (§2.1).
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

### 2.8 Migration `0066_music_storytel.sql` (renumbered after the final rebase; main ends at 0064, 1b holds 0065)
```sql
-- The server's own Storytel session (design 26b §2.1): the refused-login cooldown (vibb
-- storytel-login-refused.json) for the login generation it refused, and the card's last outcome
-- (a code, never text: `login_error`).
-- Also from 0066 on: a Storytel entry's music_items rows are state 'ok' with url NULL (listed and
-- playable; the phone mints the audio URL), which 0051's comment ("ok (it has a URL)") predates.
ALTER TABLE music_storytel ADD COLUMN login_refused_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_refused_generation INTEGER;
ALTER TABLE music_storytel ADD COLUMN login_ok_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_error TEXT;
-- A phone's library holds Storytel entries only with its switch on and a login stored (§2.7).
CREATE TRIGGER device_policy_storytel_revision AFTER UPDATE OF music_storytel ON device_policy
WHEN OLD.music_storytel IS NOT NEW.music_storytel
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER music_storytel_presence_revision AFTER UPDATE OF ciphertext ON music_storytel
WHEN (OLD.ciphertext IS NULL) IS NOT (NEW.ciphertext IS NULL)
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
```
`music_entries.source` already allows `storytel` (0049). `music_items.state 'ok'` with `url NULL` is new usage, not a
schema change: the note goes into 0066's header (above) and `music_sweep::Item`'s doc. **Applied migrations are never
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
  most 6 h): every attempt mints a fresh URL, so a 403 from an expired token heals on the next attempt.
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
/** The one HTTP seam (vibb `_request`): blocking, any status returned, IOException = unreachable. */
interface StorytelHttp { fun send(request: StorytelRequest): StorytelResponse }
data class StorytelRequest(val method: String, val url: HttpUrl, val headers: List<Pair<String, String>>, val follow: Boolean, val timeoutMs: Long)
data class StorytelResponse(val status: Int, val location: String?, val body: ByteArray) // body capped at 64 KB

sealed interface Mint {
    data class Url(val url: String) : Mint          // the signed CDN URL: used once, never stored or logged
    data object NotConfigured : Mint                 // no login on the phone (or it can't be opened)
    data class Refused(val untilWallMs: Long) : Mint // refused now or inside the cooldown
    data class LoginFailed(val reason: String) : Mint // login unreachable, timed out, or 200 without a JWT: no cooldown
    data class NoAudio(val status: Int) : Mint       // 3xx without Location, 403, 404, 410, 451: item error `no_audio`
    data class Failed(val status: Int) : Mint        // the asset call: 401 after the re-login, 408, 429, 5xx, other: `http_<status>`
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
- `login()`: the cooldown first (`CooldownStore.refusedAt(generation)`: `0 < wall - at < 1 h` -> `Refused`, **no
  request**); then GET `<LOGIN_URL>?m=1&uid=<email>&pwd=<hex>` built with `HttpUrl.Builder.addQueryParameter` in that
  order, follow on, 20 s (less when the deadline is nearer). Not 200 -> `CooldownStore.setRefused(generation, wall)` and
  `Refused`; 200 without a non-empty `accountInfo.jwt` -> `LoginFailed("no jwt")` (no cooldown); `IOException` or the
  deadline -> `LoginFailed` (no cooldown). The JWT is kept **in memory only** with `elapsed + 1 h` and the login's
  generation.
- `bearer(force)`: the kept JWT unless forced, expired or of another generation; else `login()`.
- `assetUrl(cid, timeoutMs)`: GET `API/assets/v2/consumables/<cid>/abook`, `Authorization: Bearer <jwt>`, `Accept:
  */*`, **follow = false**; a 401 -> `bearer(force = true)` and one retry; then a `Location` (absolute http(s)) is
  `Url`; else the status decides (QA M7): a 3xx without a usable `Location`, 403, 404, 410 or 451 -> `NoAudio(status)`
  (locked, geo, gone: permanent for a day); 401 after the re-login, 408, 429, 5xx and anything else -> `Failed(status)`
  (transient); an `IOException` or the deadline -> `Unreachable`. The deadline bounds the whole call (login included).
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
`SystemClock.elapsedRealtime` and `System.currentTimeMillis`, and a `Mutex` around the login and the JWT it guards (the
download worker and a Sonos actor never log in twice at once; an asset call holds no lock). The wait for that `Mutex`
counts against the caller's deadline (a download's login may hold it up to 20 s; a Sonos mint then fails at its 10 s, QA
L6). The one signature: `suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint` (IO) - downloads `(15
s, now + 60 s)`, the Sonos path `(6 s, now + 10 s)`. `fun state(): String` = `none` (no login stored) / `refused` (the
cooldown runs for the stored generation) / `ok`. A new generation from the bridge needs no call: the JWT and the
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
     no item error, the entries report `waiting: login` (§4.5) and the report's `storytel` says why; `NoAudio`: item
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
  (`checkReleaseHasNoDebugFeed`), so it stays `null` there.
- `storytel.json` `{"email", "password"}` seeds a login (generation 1) for a run without a launcher - **fake values
  only** (`login_request.json`'s `k@example.invalid` / `hemmelig`), and the receiver deletes the file once read (it sits
  on shared external storage; QA L13). The library and the listing come from `server/testdata/` (the snapshot's Storytel
  entry, §8 step 2b) or by hand, as §6f.
- `music.yml` watches `scripts/fake-storytel.py` and runs its `--selftest` after the build.

## 5. Storytel on Sonos (1b's renderer axis)

### 5.1 The body and the late mint (`PlayBody.kt`, `SonosController.kt`)
- `PlayBodies.speakerDropReason`: a Storytel item is speaker-playable, downloaded or not (vibb's exemption of the
  http-only filter, 1693-1699); everything else as 1b.
- `PlayBodies.build` for a Storytel item: `PlayBody(kind = URL, uri = "", mint = cid, title, album = entry.name, art =
  item.art when http, durationS = the listing's)` - the shelf's length rides along, since a speaker reads 0:00 for an
  extension-less signed URL (vibb 9).
- `SonosController.playIndex`'s actor job resolves the mint **inside the job, right before** `transport.play` (vibb:
  "milliseconds before the SOAP /play"): `StorytelMinter.mint(cid, 6_000, elapsed + MINT_BUDGET_MS)` - the account's
  one signature (§4.2): the asset call 6 s (vibb's Sonos timeout), the whole mint, the login and the login `Mutex`'s
  wait included, within `MINT_BUDGET_MS = 10_000`; a `Url` that passes `safeUrl` and isn't a private literal (unless the
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

### 5.5 Art (`M/data/MusicDb.kt`, `PlayItem`, `Sync.applyListing`; step 4a)
Room's `items` gains `art` (nullable): `@Database(version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)])`,
`app/schemas/me.vibb.music.data.MusicDb/2.json` exported - never a destructive migration (positions live in Room).
`applyListing` stores the listing's `art`; `PlayItem.art`; `PlayBodies.build` sends `item.art` when http **for every
source** (vibb `_sonos_body`: `art_url or art`; 1b choice 7 passed null only for want of the column). The phone's own
screens keep the entry cover (phase 1).

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
the session's "not-ours?" line today (`SonosSession.kt:327`).

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
| 10b a refused login parks a 1 h cooldown, no request; a new password retries at once | `storytel::tests::refused_login_cooldown` (stamp, no request inside, a save's new generation clears) | `StorytelApiTest` (the store's stamp, a new generation clears) |
| 11 unconfigured degrades cleanly | `storytel::tests::not_configured` (no login, no key, another key) | `StorytelApiTest` (`NotConfigured`, no request) |
| `storytel_expand.py` 1 only downloaded books, reading order, keyed by id | - | `StorytelRulesTest` (`forRenderer(false)`) |
| 2 per-book art beats the series cover | the listing's `art` per book (`music_sweep` test) | `PlayBodyTest` (the book's art to the speaker); the phone's screens keep the entry cover (§9) |
| 3/4 prune keeps the dir while in the library, removes it once not | - | `OfflineTest` (Storytel copies kept while listed, pruned when the library drops the entry or the list drops the book) |
| 5 `collection_image` local only | n/a (covers come through the bridge, phase 1) | n/a |
| 6 natural order oldest first | - | `StorytelRulesTest` |
| 7 a never-synced target expands to nothing | - | `StorytelRulesTest` (no listing = hidden) |
| 8 newest book = the NY trigger | - | `NewItemsTest` (a new book key joins the tray; eligible though `url == null`; the first listing acknowledges all) |
| `storytel_routes.py` 1 the three POSTs privileged | `tests::music_storytel::routes_need_a_session` | - |
| 2 save validates with a probe, never echoes the password | `tests::music_storytel::save_probes_then_seals` (accepted, refused - old login kept, unreachable - saved with the notice), the page never holds the password | - |
| 3 0600 at rest, the open status leaks nothing | phase 1's sealed-login tests; `the_card_shows_no_login` (no email, no JWT on any page) | - |
| 4 the picker groups the shelf, in-library vs downloaded | `tests::music_storytel::picker_lists_groups` (kids filter, `?all=1`, in-library rows disabled); per-phone downloads on the card | - |
| 4b the artwork proxy never serves audio | n/a (the server holds no Storytel audio; the phone's copies are app-private) | - |
| 4c a raw target is privileged | `tests::music_storytel::add_takes_only_shelf_targets` | - |
| 5 logout clears the account and the books; a bad login is refused and forgotten | `tests::music_storytel::clear_drops_storytel_from_libraries` (the revision moves; the library loses the entries) | `OfflineTest` (the dropped entry's books pruned); `BridgeTest` (generation 0 deletes the login, phase 1) |
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
| 2 for Sonos every book; the queue key stays local; the http cover rides | - | `StorytelRulesTest` (`forRenderer(true)`), `PlayBodyTest` (art; `storedUri` is the marker) |
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
  `src/debug` and read only by §4.6's four readers, M2; the redacted `toString`s, L9; the strings in nb), a schema test
  (`app/schemas/.../1.json` -> `2.json` differ only by the nullable `items.art`; the database declares the
  auto-migration).
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
   `music_entry.html`, `migrations/0066_music_storytel.sql`, `Cargo.toml` (`aes = "0.9"`), `server/testdata/storytel/`,
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
   `docs/testing/emulator.md` §6h. Tests: `StorytelDownloadTest`, `OfflineTest`, `NewItemsTest`, `ReportTest`,
   `LibraryViewTest`, `GuardsTest`, `SourceScanTest`. Acceptance: the music suite green, `python3 -I
   scripts/fake-storytel.py --selftest` OK. Emulator (§6h): a 3-book series downloads in reading order with the
   bookmark's book first; `--drop-at 40 --token-ttl 30`: the book resumes with Range against a fresh token (the fake's
   log), the bytes complete; `--locked 222`: `no_audio` for it, the others download; `--refuse`: `storytel: refused`,
   `waiting: login`, one login in an hour; airplane mode: the downloaded books play, the others are hidden, the tile
   still says "del N av 3"; the switch off in the library (a hand-edited library without the entry): the books deleted;
   logcat and the cache index (`no_backup/media/*.exo` metadata) hold no `FAKE-JWT`, `pwd=`, `token=` or the email.
   Owed [device check]: §"Device checks" download items, "Downloads under Doze".
4. **a. Music: item art in Room** (QA M8; independent of Storytel). Files: `M/data/MusicDb.kt` (`items.art`, version 2,
   `@AutoMigration(from = 1, to = 2)`), `app/schemas/me.vibb.music.data.MusicDb/2.json`, `M/model/PlayItem.kt`,
   `M/data/Snapshot.kt` (the art into `PlayItem`), `M/sync/Sync.kt` (`applyListing` stores it), `M/sonos/PlayBody.kt`
   and `SonosController.kt` (`item.art` when http, for every source). Tests: the schema test, `PlayBodyTest`, a
   `SourceScanTest` pin (no destructive migration). Acceptance: the music suite green. Emulator: an upgrade from a
   step-3 build keeps every position and bookmark; an RSS episode on Stua shows its art in the fake's DIDL. Owed: none.
   **b. Music: Storytel on Sonos.** Files: `M/sonos/{PlayBody,SonosController,SonosRenderer,SonosLifecycle,
   SonosTransport,SonosSuspension,SonosSession}.kt` (the mint body, the job's token, `sameStream`, adopt's live uri,
   `storedUri` on every write, the duration lookup, the redacted log), `M/storytel/StorytelAccount.kt` (the minter the
   controller gets), `M/playback/PlaybackService.kt` (§5.6's call sites, `toRoom`'s re-plan, `lookAtRoom`'s write,
   coming home with a book not on the phone, `HANDOFF_MS`), `M/ui/MusicViewModel.kt`/`MusicRoot.kt` (the remote
   snapshot on a room). Tests: `PlayBodyTest`, `SonosControllerTest`, `SonosLifecycleTest`, `SonosSessionTest`,
   `StorytelRulesTest`, `GuardsTest`. Acceptance: the music suite green, both fakes' selftests OK. Emulator (§6g + §6h
   together): a book not downloaded plays on Stua (one mint in the fake's log, the DIDL's duration and art),
   next/previous mint again, pause/resume doesn't, a step mashed during a slow mint (`--slow-mint 5`) sends one
   `SetAVTransportURI`, a stopped Stua plays from the bookmark with a new mint, phone -> Stua with books 1 and 3 of five
   downloaded queues all five, the app killed and restarted while Stua plays an undownloaded book (adopted by
   `consumableId`, no transport command, the right book, no Stop), room -> phone with that book not downloaded (paused,
   "Ikke lastet ned"), `renderer.json` holds `storytel:book:<cid>` after the play and after the adopt. Owed [device
   check]: the Sonos items below.
5. **Docs and release.** `music/CLAUDE.md` (the `storytel/` package, download-only, visibility per renderer, the mint
   rules and the job's token, the secrets rule, the debug switch, the fake, Storytel over mobile data with the parent's
   switch), `server/CLAUDE.md` (the Storytel client, the picker, the listing, the gates, migration 0066, the `reqwest`
   log floor), `launcher/CLAUDE.md` (one line: Storytel listings ride the courier unchanged), root `CLAUDE.md`
   (`scripts/fake-storytel.py`, `music.yml`'s paths), `NOTICE.md` (the ports of `storytel.py`, the Storytel parts of
   `library.py`, `content.py` and `daemon.py`, crediting vibb (MIT, palchrb) and its upstream storytel-player 1.3.0 by
   debba (MIT), with its notice beside `MIT-vibb.txt`), design 21's "Out: Storytel playback (2)" -> points here, design
   26's status, `PLAN.md`. Then the release below.

Shared contracts: only step 2b changes one (the library snapshot, three directories, one commit). No step touches the
AIDL, `bridge_contract.json`, `music_listing.json`, `music_state.json` or the policy shape. Each step is one sitting:
1 (pure Kotlin), 2a (one new module plus its PWA), 2b (wiring Storytel through existing paths plus the contract
commit), 3 (the phone's download path), 4a (one column), 4b (the Sonos wiring) - 4 was split for that (M8).

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
| `shelf.json` per target dir, the mp3 named by cid | the listing in `music_items` and Room; the copy keyed `<entry key>:<cid>` | 21b: the server lists, the phone downloads |
| a book that left the shelf stays on disk, unlisted | it leaves the list and its copy is pruned | the same for the kid (unplayable either way); no orphaned gigabytes |
| per-book `<cid>.jpg` on the box's screen | per-book covers go to the speaker only; the phone's screens keep the entry cover | phase 1's screens show the entry cover and have no image loader; the mockup is locked |
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
- Sonos: a book not downloaded plays in a room (title, cover and progress bar in the Sonos app), next/previous, pause for
  two hours then play (if the speaker fails, one more tap starts from the bookmark), the app killed while a book plays
  (reopen: adopted, the right book and place), the signed Location carries `consumableId` (logcat's redacted line and
  the parsed id), room -> phone with the book downloaded (continues) and not downloaded (paused, "Ikke lastet ned").

## Release

- **Server**: phase 2's server part (0066, the client, the picker, the listing) needs a version after whatever `main`
  has tagged when this merges: today `server/Cargo.toml` is 0.24.0 untagged and 1b's 0065 rides in it; if 0.24.0 is
  tagged first, bump `server/Cargo.toml` and `Cargo.lock` to the next minor. Renumber 0066 after the final rebase (main
  ends at 0064, 1b holds 0065). Deploy the server first: an older server sends no Storytel entries, so an older or newer
  music app simply has none.
- **Launcher**: no code change; nothing to release for phase 2.
- **Music**: `music-v0.3.0-rc.1` on the merge commit on `main`, after 1b's `music-v0.2.0` line (26a "Release"); the
  stable `music-v0.3.0` after the emulator checks (§6h, §6g with Storytel) and the device checks above. An older music
  app on a phase-2 server hides Storytel entries and prunes nothing (§3.1).

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

- **H1**: §2.8 no longer touches 0051; the note sits in 0066's header comment and `music_sweep::Item`'s doc, and §2.8
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
  series' reading order (`snap.listed`); `LibraryViewTest` rows; done in step 3 (`UiModel.kt`, `LibraryView.kt`).
- **M6**: §5.1: the play job carries a thread-safe token (generation, `closed`, `mayPlay`) checked before the mint (a
  superseded job never mints) and after it, before `SetAVTransportURI`; `DRAIN_MS` 30 s rechecked and kept; the
  receiver's 9 s vs `HANDOFF_MS` 25 s explained. `SonosControllerTest` rows; the fake's `--slow-mint` for the emulator.
- **M7**: §4.1: `Mint.LoginFailed` (no JWT, login unreachable or past the deadline) stops Storytel for the run like
  `Refused`, without item errors; asset statuses split into `NoAudio` (3xx without `Location`, 403, 404, 410, 451:
  permanent) and `Failed` (401 after the re-login, 408, 429, 5xx, other: transient `http_<code>`); §3.3 and §4.3 follow.
- **M8**: step 4 is split into 4a (`items.art`: Room v2, the schema, `PlayItem`, `applyListing`, art to the speaker for
  every source) and 4b (Storytel on Sonos).
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

## Step 2a implementation status (2026-10-12)

Built on branch `music-2-storytel-2a` (from `685a6ae`), three commits: `music_net` (`b92b1ef`), the client and
migration 0066 (`623183b`), the PWA (`7baa750`). Server only; the library, the sweep, the cards, the import and the
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
  new `music_storytel.html`, `music_entry.html`. `migrations/0066_music_storytel.sql` exactly as §2.8.
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
   exactly) have no time for `login_error`. The refused line has its times (`login_refused_at`), the accepted line
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
11. **Left for 2b** (its files): `music_sweep::Item`'s doc note (§2.8; 0066's header has it), `not_on_shelf`,
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
