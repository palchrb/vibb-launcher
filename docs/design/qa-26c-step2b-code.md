# QA 26c step 2b code review - the merge of main, then entries, the PWA and the binding (2026-10-13)

Scope: branch `music-3-spotify`. First `5d6bd5b` (the merge of `origin/main` `a5d30b9` into `914b993`), then `9413ff7`
(step 2b, one commit) under `server/`: `src/handlers/music_spotify.rs`, `src/handlers/music.rs`, `src/handlers/music_import.rs`,
`src/models.rs`, `src/music.rs`, `src/music_import.rs`, `src/music_sweep.rs`, `src/spotify.rs`, `src/main.rs`, the
templates `music_spotify.html`, `music.html`, `partials/music_card.html`, `music_entry.html`, `device_detail.html`, the
tests `src/tests/music_spotify.rs` (`entries`), `src/tests/music.rs`, `music_import::tests`, `music::tests`. Read against
root and `server/CLAUDE.md`, design 26c (§2.5, §3.1-§3.7, §7, §10, §11 step 2b, the design QA fix round, every status
section to "Step 2b implementation status" with its deviations and spec findings), `qa-26c-step1-code.md`,
`qa-26c-step2a-code.md`, the 26b templates (`qa-26b-step2b-code.md`, `qa-26b-merge-code.md`), design 31 §3.0, §3.4, §4,
§5.1, §5.2, §5.7, §8 steps 1 and 12, and the shell code on main (`style.css`, `settings_hub.html`, `tests/shell.rs`).
Line numbers are `9413ff7`'s.

Runs, here (Rust 1.99, `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` 12 warnings - `models.rs`
(9), `dns_engine.rs` (2), `handlers/devices.rs:1321` (1), the known ones, none new; `cargo test` twice: run 1 743
passed, 1 failed (`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`), run 2 **744 passed, 0 failed**.
The `parent_pin` test is still timing-dependent on main (see "Notes"); it failed alone three times in a row and passed
under the full suite's load - nothing in 2b touches it. Every `music_spotify` test (2a's listing tests through the new
`ReaderTurns` included), `tests::shell`, `music_library_snapshot`, `music_listing_snapshot`,
`policy_json_keys_snapshot` and the `music_state.json` test green in both runs. `Cargo.toml`/`Cargo.lock`,
`migrations/`, `testdata/` (and `launcher/`, `music/`) untouched by 2b.

**Verdict: good to merge after a short fix round.** Counts: 0 High, 1 Medium, 5 Low. The merge is clean. Step 2b does
what §3.2, §3.6 and §2.5 ask: the add route refuses in the right order with nothing stored, shares one 45 s budget
between the short link and the check, inserts and ticks in one `BEGIN IMMEDIATE` transaction within the entry limit
and each phone's library size, names (never silently drops) a picked phone that doesn't play Spotify, and agrees with
the sweep on what a refusal is through the shared `ReaderTurns`; the gate is right on all four device routes; the
library and `music_state` bytes are unchanged for every phone without Spotify; the cards, the entry page, the binding
and the import behave as specified. The Medium is a cancellation hazard the add route's timeout introduces into the
token refresh (and that step 3's token route would make routine); the Lows are the `gated`/gate disagreement the
implementer found (decided below), a binding read before the network, a DNS note that is true only from step 3, copy,
and test gaps.

## The merge (`5d6bd5b`)

Right. `git show --remerge-diff 5d6bd5b` shows one conflict, `PLAN.md`'s music paragraph: the resolution keeps this
branch's "Phase 3, Spotify" text and appends main's design 29 sentence verbatim; main's one-line "Phase 3 (Spotify)
follows design 26; its first step, the engine spike, ..." is dropped, correctly - the 26c text supersedes it and names
the spike's record. `templates/music_spotify.html` gains `{% let tab = "music" %}` on the line before the header include,
which is what `tests::shell::every_template_with_the_bar_sets_its_tab` reads (it passes); the page had no inline
`onchange`. `templates/music.html`, `music_entry.html`, `partials/music_card.html` merged by themselves with main's
shell edits intact (no Apps|Music switch, `tab` set, `data-autosave`). The status's claim that main touched nothing in
`server/src/music*.rs`, `handlers/music.rs`, `migrations/` or `testdata/` holds (`git diff 914b993...a5d30b9 --stat --
server/`: main's server changes are the shell, `settings_hub`, the tests' helpers and `tests/music.rs`'s 41 lines,
taken as they are); main added no migration, so 0073 is still the next number.

## Medium

1. **Medium: the add route's 45 s timeout can drop a token refresh mid-flight and lose a rotated refresh token - the
   account then needs a new login.**
   - Where: `src/handlers/music_spotify.rs:1097` and `:1142` (`tokio::time::timeout_at(deadline, ...)` around
     `resolve_short_link` and `spotify::add_check`); `src/spotify.rs:1474-1478` (`mint` takes the in-memory
     `slot.unsaved` token out), `:1509` (the refresh request, awaited), `:1525-1529` (the rotated token's write,
     awaited).
   - `add_check` reads through `api_get`, which mints through `Accounts::mint` when the cached access token is stale -
     the add is often the first Spotify request in an hour, so it usually refreshes. `timeout_at` drops the whole future
     at the deadline wherever it is. Dropped after the token request reached Spotify (the gate may have held the add
     for up to 45 s - a feed fetch holds it for up to 120 s - so the refresh can start late in the budget), the answer's
     rotated `refresh_token` is never written; dropped between `:1509` and the write, the same; and when a previous
     write had failed, `kept` (the **only** copy, taken out of `slot.unsaved` at `:1474`) is dropped with the future.
     §2.4 promises the rotated token "is sealed and written before the access token is used" and that a failed write
     "keeps the new refresh token in `Access.unsaved`" - neither holds across a cancel. If Spotify revokes the old
     token on rotation (the design assumes PKCE tokens rotate, [unverified] whether the old one dies), the next refresh
     is `invalid_grant` -> `needs_login`, and every phone bound to that account stops at its next token.
   - The window is small in 2b (one add, the gate busy, the refresh landing in the last seconds), but the pattern is
     the one step 3 needs: qa-26c-step1-code's notes ask for a timeout around the token route's `access_token`, the
     phone's bridge gives up after 10 s, and hyper drops a handler whose client went away.
   - Required change: make the refresh cancel-safe in `mint` itself, so no caller can lose a token: run the request,
     the rotated token's write and the `refresh_ok_at` stamp as one `tokio::spawn`ed task that owns what it needs
     (the refresh token, the row's generation, a clone of `AppState`) and puts its outcome into the account's slot
     (`access`, or `unsaved` on a failed write) before it ends; the caller awaits the `JoinHandle` (dropping it doesn't
     cancel the task). Keep the slot lock held across the await so a second caller still waits for the one refresh. Test
     (CannedFetch's `hold`/`delay_ms`): a refresh whose answer carries `FAKE-REFRESH-2`, the caller's future dropped
     while the answer is held, then released - the row's sealed token opens to `FAKE-REFRESH-2` and the next
     `access_token` uses the cached access token without a request; and the same with a failed first write (the
     `unsaved` copy survives the drop).

## Low

1. **Low: the card says a Spotify entry isn't sent while the library still sends it - the `gated`/gate disagreement
   (the status's spec finding), decided: `gated` follows the binding, as the SQL gate does.**
   - Where: `src/handlers/music.rs:2858-2877` (`gated`: "no Spotify app is set up" for every phone when no app),
     `:2880-2887` (`phone_count`); `src/music.rs:891-897` (`spotify_gate!()`: the binding only);
     `src/tests/music_spotify.rs:3409-3420` (pins "Ella: not sent - no Spotify app is set up" and "on 0 phones" for a
     bound Ella who still gets the entry).
   - The state is reachable only when the client ID came from `SPOTIFY_CLIENT_ID` and the variable is gone after a
     restart with accounts and bindings left (Clear and a new client ID unbind every phone). Then the phones get the
     entries (and step 3's token route answers `off`), while the card says "not sent" and counts none.
   - Which way to fix: not the SQL - the gate can't see the environment's client ID, and dropping the entries would
     prune the phone's tiles (and their place in the carousel) over a configuration slip that the device card already
     warns about ("No Spotify app is set up on this server: Spotify can't play"). Keeping the tiles is §3.6's own rule
     for a login that needs renewing; the tiles then say why they can't play. So the card must say what the gate does.
   - Required change: `gated` for `spotify` = `None` when the phone is bound; else "no Spotify app is set up" when there
     is no app, else "Spotify is off on this phone". A bound phone's line without an app reads "Ella: plays as Ella -
     no Spotify app is set up, so it can't play" (`spotify_phone_line`). Change the test's last block to the new
     lines and "on 1 phone"; amend §3.6's sentence ("a `spotify` entry is gated on a phone with no account bound;
     without an app the bound phones still get it and their lines say it can't play").

2. **Low: whether a picked phone plays as an account is read before the 45 s of network, not in the transaction.**
   - Where: `src/handlers/music_spotify.rs:1069-1090` (`bound`, read up front), `:1180-1197` (the ticks use it).
   - A phone bound or unbound during the check is ticked or skipped by the old answer, and the notice says so
     ("Ola: not ticked - Spotify is off on this phone" for a phone that now plays). Harmless for what the phone gets
     (the gate decides that), but §3.2 step 4 says "bound ... in the same transaction".
   - Fix: keep the up-front read for the unknown-phone refusal, and decide `on`/`off` inside the `BEGIN IMMEDIATE`
     transaction with the gate's own `EXISTS` per phone (one query each, as the up-front one).

3. **Low: the device page's DNS note says something that is true only from step 3.**
   - Where: `templates/device_detail.html:762-764` ("Spotify's hosts are allowed on this phone while Spotify is on
     ... a block of spotify.com or scdn.co ... doesn't apply to this phone then").
   - In 2b nothing exempts the hosts (no `music.spotify`, no blocklist drop, no `dns_filter_version` change - the
     status says so: "the device card's DNS note is true only from then on"). Between 2b and step 3 a parent reading the
     page is told the filter doesn't apply when it does.
   - Fix: move the note (and its test lines in `the_device_card_binds_a_phone_to_an_account`) into step 3's contract
     commit, beside the change that makes it true.

4. **Low: copy - raw or wrong sentences on the add form, the entry page and the device card.**
   - `src/spotify.rs:2075-2093` (`add_refusal`'s fallback `other.message()`): a quota pause reads "Spotify's limit for
     this app was reached - it tries again later." on a form that tries nothing again; a 401 after the refresh, a
     `Status(400)` or `Status(429)` without a body reason reach the parent as "Spotify answered 401." - a debug-like
     text (design 31 §4.2). Fix: `Quota` -> "Spotify's limit for this app was reached - try again in an hour.";
     `Status(_)`, `TooBig`, `BadAnswer` -> "Spotify couldn't open it just now - try again."; a row each in
     `the_add_form_refuses_with_nothing_stored` (one is enough for the fallback).
   - `templates/music_entry.html:34`: the status card is titled "Tracks" for a Spotify podcast or episode too. Fix:
     "Episodes" for `show`/`episode` (the kind is known: `kind_label` already tells them apart).
   - `templates/device_detail.html:489`, `src/handlers/music.rs:4006-4009`: the binding hint is 45 words of footer text,
     and "no Widevine" is an implementation word (§4.2's list). Fix: "On the phone: this phone can't play Spotify (its
     web player lacks the copy protection Spotify needs)."; the hint's second half ("its private playlists included")
     can stay - design 31 step 7 rewrites this card - but keep it to the two sentences the parent needs.

5. **Low: §10's 2b rows have gaps the nine tests don't pin.**
   - The tick refused for size (`big`: "... would be bigger than 3 MB") and the full library's refusal
     ("The library has 200 entries ...") through the add route.
   - The shared budget: a short link or an add check that outlasts it answers "Spotify couldn't be reached - try
     again." with nothing stored (CannedFetch's `hold` and `tokio::time::pause`, or a test-sized budget).
   - The binding card's three-phone wording ("Ella's, Ola's and Kari's phones all play as Ella") and the other phone
     words (`refused`, `quota`, `no_widevine`, `reauthorize`, `unreachable`, `ok`) - one is tested.
   - `/music/spotify` in `tests::shell::every_page_marks_its_own_tab`'s page list (the template check passes, the
     rendered `aria-current` isn't checked for this page).
   - Fix: rows in `the_add_form_refuses_with_nothing_stored`, `a_link_is_added_and_ticked_on_the_phones_that_play_spotify`
     and `the_device_card_binds_a_phone_to_an_account`; the page in the shell test.

## Confirmed

- **The add route** (`handlers/music_spotify.rs:993-1249`): more than 200 fields, no app, no usable account ("every
  login ended" told apart), a non-numeric or unknown phone, the link's grammar (and its §3.1 sentences), the short link
  (hop by hop, within the budget), already in the library (after the canonical target is known - the status's second
  spec finding is right, it can't come earlier) with a link to `/music#entry-<id>`, the category (picked, else
  `default_category_for` by the kind: a show or episode to Podkast) - all before any Spotify request (the refusal test
  pins `calls` empty through all of them). Then the check, then one `BEGIN IMMEDIATE` transaction: the INSERT's own
  `BELOW_ENTRY_LIMIT`, `cache 0`, `resume 1`, `auto`, `SpotifyRef::key`, the name cut by `cut_title(.., 60)`; each
  bound phone through `music::tick_fits` on the same transaction. A picked phone that doesn't play Spotify is
  **skipped and named** in the notice, never refused and never silent; a phone too big is named too. A lost race on
  the target's `UNIQUE` answers "Already in the library." The sweep is woken, the ticked phones nudged, the event
  carries the name and the canonical target only. 303 to `/music/spotify?added=<id>...#added`; every refusal a 400 with
  the link, category and phones as entered. CSRF: the admin listener's global `csrf_guard` layer covers both new POSTs;
  `routes_need_a_session` covers them.
- **The budget**: one `deadline` set before the short link, shared by `resolve_short_link` and `add_check`; the gate's
  wait is inside both futures. (Cancel safety: Medium 1.)
- **`ReaderTurns`**: the sweep's `first` is the same logic moved - `refused` now `refusals == tried` instead of
  `== ids.len()`, equal whenever `end()` is reached (every reader tried); `NoReader`, the "unusable" preference and the
  `NoAccount` default unchanged. 2a's listing tests (including `a_transient_after_a_403_keeps_the_listed_playlist` and
  `the_artist_options_albums_page_by_ten`) green both runs. The add check uses the same turns for the metadata and for
  the playlist's items page, so a transient after a 403 adds the playlist without the context notice (deviation 2) and
  never calls it a refusal.
- **The gate** (`music.rs:891-897`, `spotify_gate!()`): `LIBRARY_ENTRIES` (a fourth bind, in order), `cover_in_library` (both entry
  branches; ten binds for ten placeholders, own files' branch ungated as before) and `art_in_library` all AND the
  parenthesised `(e.source != 'spotify' OR EXISTS (... p.device_id = ?))`, so no other source's row is affected and a
  Spotify row needs the phone bound to an existing account (the JOIN, and 0073's `AUTOINCREMENT`, mean a removed
  account's id can't pass). `needs_login` doesn't close it (tested). The device binding's `UPDATE` checks the account's
  existence in its own `WHERE`; device ids come from the path, single tenant, nothing crosses phones.
- **Bytes unchanged**: `LibEntry.spotify` and `MusicState.spotify` are `skip_serializing_if = "Option::is_none"`; the
  library, listing, policy and `music_state.json` snapshot tests pass unchanged. A Spotify entry not listed yet has
  neither `spotify` nor `items` (the phone hides it); `cache` 0, `lan` false.
- **Words**: `kind_label`'s six labels; `item_error_code` takes `unplayable` with its sentence; `sanitize_music_state`
  keeps exactly the eight `SPOTIFY_WORDS` and drops any other text (a non-text value drops the report, as every other
  field's wrong type does - pinned).
- **Cards**: `spotify_card` ("N tracks · read as <name>", episodes for a show, context mode's line and notice per kind,
  the left-out note, the caps), "on N phones" from `phone_count` through `gated`, per-phone lines with `unplayable` at the
  current version, no offline form (`SweepCard.offline`), Check now and `/music/cards` take `spotify`, `save_offline`
  stores 0 whatever is posted. **"Open in Spotify"** is built only from `parse_target(entry.target)` ->
  `SpotifyRef::open_url()` (`https://open.spotify.com/<kind enum>/<[A-Za-z0-9]{1,64}>`), never from posted or Spotify
  text, askama-escaped, `rel="noopener noreferrer"`; the entry page shows the target as text, not a link.
- **The entry page**: no offline select (cache saved 0, the "pick one of the choices" check skipped for Spotify), no
  order select in context mode and a save without `order` keeps the stored one (tested); resume stays the parent's.
- **The binding** (`handlers/music.rs:4043-4122`): `off`/"" or an id; junk and unknown ids 303 back to
  `#music-spotify` with `spotify_unknown`, nothing changed; an unknown phone 404; the trigger moves the revision; the
  phone nudged; `music_spotify_binding_changed` with the display name (names only in events, §2.4). The two-phones
  warning on every bound card, the account's 6-month line and warnings, the phone's outcome in words, the ticked-but-off
  note. The select is `data-autosave` with a `<noscript>` Save.
- **Due at once**: `schedule` reads the app's `saved_at` and the newest `authorized_at` (both `stamp()`'s format, which
  `parse_stamp` reads); a failure stamps `error_at` anew every time, so a cured entry that fails again backs off - no
  loop. `complete_login` and the app's save wake the sweep. Tested both ways, and a login older than the failure cures
  nothing.
- **The import**: `parse_link` decides; a URI or `open.spotify.com` link (with `intl-xx`, `?si=`) is a Spotify entry,
  cache 0, never ticked (the confirm's tick loop skips it); short links `SpotifyShortLink`; audiobooks and Liked Songs
  `BadLink` with §3.1's sentence; a `spotify:` text that isn't a link `BadLink`; a look-alike host stays a feed; no
  request (pinned). The changed tests are honest: `spotify:playlist:1` is a valid target by the grammar
  (`[A-Za-z0-9]{1,64}`), so the row is now offered; the test leaves it unticked, so it is neither imported nor
  "skipped" - "5 skipped" is the right count. Had it been ticked, the entry would be harmless: on no phone, its first
  check `spotify_off`/`no_account`/`not_found`. The attacker-controlled `library.json` path gains no new size or count:
  `parse_link` bounds the text at 2000 characters, the existing file and entry limits stand.
- **Nothing device-facing beyond the gate**: no `music.spotify` key, no token endpoint, no host list, no
  `dns_filter_version` change (grep of `device_api.rs`, `music_api.rs`, `dns_engine.rs`); no dependency, no migration.
- **Secrets**: no new `tracing` line; the pages show display names and the client ID's last four only; events carry
  ids, names and canonical targets. Fixtures are `FAKE-*`.
- **Design 31**: every touched template sets `tab` (`music` for the Music pages, `devices` already for the device
  page); no inline `onchange` (shell test green); the new controls use `data-autosave` + `<noscript>`; the add form is a
  plain POST that works without JS; the refusals mark the field (`aria-invalid`, `autofocus`); every POST lands on its
  own page and anchor (`#added` - the flash's id, `#music-spotify`, `/music#entry-<id>`) - §5.1 has no redirect table
  on main yet (step 1 built `assert_redirect` only), and the new tests pin each `Location` exactly. The "From Spotify ›"
  row is main's navigation row markup (`a.row`, `.row-label`, `.row-value`, `.chev`, `data-row`), `id="spotify"` keeps
  the old anchor, and its value ("Signed in" with an app and an account that can read, else "No login", else "No app")
  is tested through all three. The new CSS uses tokens only (`--color-border` aliases `--sep-light`, which dark mode
  redefines), no `:has()`, `color-mix()`, nesting or `dvh`; the only fixed sizes are the checkboxes' 22 px.

## The deviations

(The status numbers eight, not nine.)

1. **No cover candidate written at add**: accept - the first check, first in line as `Why::New` within a minute,
   writes the same picture from the same metadata; a listing row without a version would change `schedule`.
2. **The items read only for the notice**: accept; the sweep's first check decides the mode for good.
3. **303 + `spotify_unknown` instead of a 400**: accept - the device page has no 400 re-render and the Sonos rooms do
   the same; nothing is changed and the select is focused.
4. **`key` and `app_refused` cured too**: accept; a cure costs one check, after which the entry backs off again.
5. **The left-out count as its own note**: accept.
6. **More phone words, the 6-month line on the Spotify page**: accept (Low 4 for one word).
7. **The import ticks no phone; `spotify:` junk is `BadLink`; other Spotify-host text falls to `parse_link`**: accept.
8. **Unnamed entries named by their kind; `cut_title`**: accept.

## The spec findings

- **§3.6's `gated` vs `spotify_gate!()`**: right, and decided in Low 1 - the card follows the gate (the binding), the
  spec sentence changes; the SQL stays as it is.
- **"Already in the library" after the parse**: right - only the canonical target can be compared; §3.2 step 1 already
  reads "Parse (and resolve a short link). A target already in the library ...", so no text change is needed.

## What the fix round must do

- [ ] Medium 1: the refresh in `Accounts::mint` as a spawned task that writes the rotated token and fills the slot even
      when the caller is dropped; tests for a dropped caller with a rotated token and with a pending `unsaved` one.
- [ ] Low 1: `gated` follows the binding (no app: bound phones sent, their line says it can't play; unbound phones "no
      Spotify app is set up"); the test's last block; §3.6's sentence.
- [ ] Low 2: `on`/`off` decided inside the add's transaction.
- [ ] Low 3: the DNS note moves to step 3.
- [ ] Low 4: `add_refusal`'s fallbacks, "Episodes" for a Spotify podcast/episode, the `no_widevine` line, the hint
      shortened.
- [ ] Low 5: tests for `big`, the full library, the budget, the three-phone wording and the other phone words;
      `/music/spotify` in `every_page_marks_its_own_tab`.
- [ ] Re-run `cargo fmt --check`, `cargo clippy --all-targets` (12, none new), `cargo test`.

## Notes (not findings)

- `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` (main's) is not fixed: its events are stamped
  `now + 1 s` and `now + 2 s`, so the second PIN save must come more than 2 s after `now` for them to fall before it.
  A fast run (the test alone: about 1.5 s between) fails, a loaded one passes. Stamp them in the past
  (`now - 2_000`, `now - 1_000`) on main.
- For design 31 step 12 (not 2b's to fix): the add card's ⓘ sits in a `div.subtitle` after the sentence, so the
  `<details>` (a block) puts the ⓘ on its own line under it; the flash is above the form rather than under its rows.
  Both are the old card layout this page keeps until step 12 moves the login/app part to `/music/settings`.
- An app given by `SPOTIFY_CLIENT_ID` has no `saved_at`, so a server restarted with the variable newly set cures
  waiting `spotify_off` entries only after their backoff (at most the backoff's cap); not worth a column.
- Step 3: the token route should call `access_token` under its own timeout (qa-26c-step1-code's note) only once
  Medium 1's fix is in.

## Not worth fixing

- Re-saving the same binding logs `music_spotify_binding_changed` and nudges again (the trigger doesn't move the
  revision); harmless.
- `MAX_ADD_FIELDS` counts the link and category too, so "at most 200 phones" is 198; no family has that many.
- `?added=..&on=..` names whatever device ids the URL carries - an admin-only page reading its own redirect.
