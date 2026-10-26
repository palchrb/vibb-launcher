# Code QA - round S1, server small items + server dead code (`server-small-e`)

Branch `server-small-e`, 10 commits e8e536b6..5ae9cc34 on main 380fc2c8 (60 files, +1494/-758). Read, tested,
mutated and reverted, and checked in a headless browser. No code changed.

## Verdict

**Fix first** (a short fix round; no High).
- M1: a refusal answered with a 303 + `?notice=` is logged as "Settings saved". Seen live: a refused Parent PIN ("12") wrote a row. That breaks decision 10.
- M2: inside the installed iPhone PWA there is no way to turn the timing on, so the planned iPhone numbers can't be taken there.
- Everything else verified holds: Q27's digest refuses on the streamed bytes, Q26's per-host count, Q25's precedence, the one red alert, the dead code and the test-helper fold.
- 7 of 8 planned mutations are caught (the 8th was caught once a second guard was also removed: M6). My extra M9 is not caught (L4).

## Findings

**Medium**
- **M1 - refusals by redirect are logged as saves** (`server/src/settings_log.rs:84`, `page_of` :95). Many handlers refuse with a 303 back to the page plus a `notice=` code. Examples:
  - `parent_pin.rs:76` (`parent_pin_invalid`, `override_is_kid_pin`) and the Child PIN's `devices.rs:2285`/`2536`;
  - `devices.rs:553` (`revoke_unconfirmed`) and `devices.rs:1885` (`toggle_app`'s refusals, `from_play`);
  - `dns_filter.rs:733`/`812` (`bad_list`, `not_a_domain_*`) and `music.rs:4696`/`4729`/`4871` (`music_notice=`).

  Live, the server wrote row 11: `/devices/1/lock · /devices/{id}/parent-pin (id 1)` for a refused PIN, so the Security Log says "Settings saved" for a PIN that was refused. Skipping every `notice=` would drop real saves, because `notice=uploaded`, `access_revoked` and `package_confirmed` are successes.
  - *Fix:* add a marker type `settings_log::Refused` that a refusal puts in the response's extensions (in the shared refusal closures: `back`/`refused`/`to_list`, and the DNS ones). `log_saves` skips any response that carries it. Add one test: a refused Parent PIN writes nothing.
- **M2 - no way to turn the timing on in the iPhone PWA** (`server/static/nav-timing.js:3`, `docs/notes/server/pwa.md` "Tab-switch timing"). An iOS home-screen app keeps its own localStorage, separate from Safari's, and has no address bar. So `?timing=1` typed in Safari never turns it on in the installed PWA, and nothing inside the PWA links to it. The measurement step exists for those numbers (the round's "Checks left").
  - *Fix:* add a plain row on an existing Settings page (e.g. Account or About: "Page timing: On/Off") that links `?timing=1` / `?timing=0`, and say so in pwa.md.

**Low**
- **L1 - `NOT_SAVES` misses two routes** (`settings_log.rs:25-55`). See the table below.
- **L2 - a second row for saves that already log their own event.** Live: `/music/sweep` wrote `music_sweep_saved` ("New-episode check changed") and then `settings_saved`. The same happens for the Parent PIN (`override_pin_changed`), parents, zones, wallpapers and Spotify. This is noise in a log meant for reading.
  - *Fix:* either note in pwa.md that the generic row comes on top of the specific one (accept it), or set a `Logged` marker the same way as M1's `Refused`.
- **L3 - with several dead hosts, the art pass no longer stops early** (`server/src/music_sweep.rs:3641`). The old rule stopped the whole pass after 3 network failures in a row. Per host, a pass whose pictures sit on many hosts that are all failing (a DNS or uplink fault after a good listing) asks every host up to 3 times. Only the check's budget (`job.spent()`) bounds that, which is the "10 minutes" code QA M2 removed.
  - *Fix:* add a pass-wide backstop. End the pass after, say, 2 x `ART_NETWORK_FAILURES` failures when no host has answered in it.
- **L4 - no test covers the QR payload's address** (`server/src/handlers/provisioning.rs:295`). Mutation M9 (the payload back to `settings.server_url`) passed all 16 provisioning tests. `the_env_server_url_loses_to_the_saved_one` checks the function and both pages, not `provision_qr`'s JSON.
  - *Fix:* in that test, post `/devices/{id}/provision` with only the env value set and assert `server_url` in the extras.
- **L5 - two deploy templates lack `PHONE_SERVER_URL`.** Only `server/.env.example` and DEPLOY.md got it. `server/deploy/env.example` (Docker, where an env default helps most) and the `.env` that `server/deploy/install.sh:266-284` writes do not have it.
  - *Fix:* add a commented `# PHONE_SERVER_URL=` with the same two lines to both.

**Nit**
- N1 `server/src/tests/music.rs:3591`: the DST fix widens the assertion to "12:00 or 11:00" instead of using a fixed instant. It still rules out UTC's 10:00, so it is a real check; a fixed `reported_at` (e.g. 2026-07-01T10:00Z) would be exact.
- N2: `cargo clippy --all-targets` still prints `chunks_exact_to_as_chunks` at `server/src/storytel.rs:144`. It was already on main and is outside the review's 3 warnings, but it is the only warning left.
- N3 `nav-timing.js:178`: a back-forward-cache restore appends a second `.timing-line` at the same absolute spot, over the first one. Replacing the text instead would keep one line.
- N4: the fold changed the status body in 6 files from `"lock_reason": "none"` to the launcher's real `"NONE"`. The input is more correct now, and no assertion changed. Noted because the brief says the fold "changed no assertion" and this is a changed input.

## `NOT_SAVES` - only the rows I disagree with

All 147 POST routes in `build_admin_router` were classified (`/auth/*` and `/logout` sit outside `require_full_auth` or redirect to `/login`).

| Route | Branch does | Should be | Why |
|---|---|---|---|
| `/account/dismiss-warning` | logged (live row 12: `/devices · /account/dismiss-warning`) | NOT_SAVES | It hides a session banner, like a read mark; it changes no setting. |
| `/backups/upload` | logged | NOT_SAVES | It adds a backup file, the same kind of action as `/backups/create` and `/restore`, which are skipped. |
| `/devices/{id}/parent-pin`, Child PIN, `revoke`, `apps/toggle`, the DNS and music refusals | logged on refusal | logged only on success | M1 - a marker, not a list change. |

Fine as they are:
- `/devices/{id}/provision`, `/music/import`, `regenerate-code` and `/account/parents` answer 200, so they are never logged.
- `/music/spotify/login` redirects off the app.
- `sign-out-everywhere` and the reveal's sign-out redirect to `/login`.

## The critical paths, verified

1. **Q27 (GitHub digests)**
   - `github_digest` (`tracked_apps.rs:62`) takes only `sha256:` + 64 hex. Null, sha512, short or non-hex values, a bare hex string and a missing field all become `None` (test `a_github_asset_reads_its_sha256_digest`).
   - The compare (`:424`) runs on the digest of the chunks as they were written, before `sync_all` and the rename. On a mismatch, `TempDownload`'s drop removes the `.part` file, and the function returns before `apk_identity` and any pin. The test asserts no tag, no `signer_sha256` and an empty directory.
   - The digest covers catalog APKs only, which includes the launcher and music rows. The server never downloads its own tarballs (`system_update` reads only the tag); `update.sh` and `install.sh` keep minisign and `.sha256`.
2. **Q26 (per-host art count)**
   - The per-host map is local to one pass and resets on any answer (`*host = (0, true)`), so no host is held across checks. A dead host's tried pictures wait a day; its untried ones go first at the next check.
   - The test really uses two hosts (`img.` and `old-cdn.example.org`); the dead host's 3 newest pictures come first.
   - The r5 finding matches the code: `art_failed` touches the item only, the listing's `failures` stays 0, and the hold was the old `break` with `came == false`.
3. **Q29 (saves in the Security Log)**
   - Placement: inside `require_full_auth` on both the admin and the host routers (`main.rs:1431`, `:1477`). A signed-out POST never reaches it, and `/login` and off-app locations (including `//`) are excluded too.
   - A global `tokio::sync::Mutex` makes the read-modify-write atomic in the single process, so `×N` cannot lose a count.
   - The detail is built from the matched route, the path's ids and the Location's path only, all through `clean()`. No form value is used.
   - Retention (`retention.rs:124`) prunes every kind by `created_at` (a merged row ages from its first save).
   - The Security Log shows "Settings saved" with the ⓘ detail (screenshot).
4. **Q25 (`PHONE_SERVER_URL`)**
   - A saved address beats the env value.
   - The env validator is stricter than the page, which accepts any text: http/https, a host, no spaces. A trailing slash or a port is kept as typed, the same as the page.
   - The QR, the Connect page's "Server setup needed" and Phone Setup all go through `provisioning::server_url`. Clearing the saved value brings the env value back, and the hint says so.
5. **Q28 (`nav-timing.js`)**
   - It loads on every page, deferred, after `reconnect.js` (`head.html:55`).
   - When off it only reads: no listeners, no writes. In the browser, localStorage was empty after `?timing=0` and no line was shown.
   - The switch stays in localStorage, so a 303 that drops `?timing=1` changes nothing.
   - ES5: no `=>`, `?.`, `??`, `let` or `const` outside comments; it parses as a classic script.
6. **The one red alert**
   - Exactly one alarm element (the Attention row, linking to `/devices/1#quick`) in light and dark at 390 px.
   - The note's colour is `--label-2` with no background.
   - `style.css` braces balance (0) and `head.html` has `style.css?v=57`.
7. **Dead code and the test-helper fold**
   - The fold was spot-checked in 8 files (calls, summary, step10, notifications, play, sound_mode, time_rules, lock_glance). The only assertions removed are the copies' own status asserts, which `TestApp::policy` and `post_status` keep.
   - D4's route and handler are gone. `remove_journal_media`, `JOURNAL_MEDIA_DIR`, the FCM warning and `DeviceBlocklistOverride` have no references left.
   - `tokio-util` is gone from `Cargo.toml` and from our package's entry in the lock; it stays in the lock only through other crates.
8. **Version and notes**
   - 0.54.0 in `Cargo.toml` and `Cargo.lock`.
   - `server/CLAUDE.md` names the env var and the event kind; `music.md` has the per-host rule and the r5 finding; `.env.example` holds a placeholder only.

## Tests

- **Local:** `cargo test` 1356 passed, 0 failed (567 s); `cargo fmt --check` clean; `cargo clippy --all-targets` 1 warning, the old one (N2).
- **CI:** run 40325088204 green (server-build 17m24s, both image jobs).
- **jstest:** `node --test jstest/` 187/187, 7 of them in `nav-timing.test.js`.

## Mutations (each reverted with `git checkout -- .`)

| # | Mutation | Result |
|---|---|---|
| M1 | Skip the digest compare | caught: `a_github_download_must_match_its_digest` (:4320) |
| M2 | Treat no digest as a mismatch | caught: same test, the no-digest sync (:4360) |
| M3 | One global host key in the art pass | caught: `a_bad_picture_or_a_dead_host_holds_no_other_picture` (:987) |
| M4 | `MERGE_MINUTES = 0` | caught: `saves_on_one_page_are_one_row_for_ten_minutes` |
| M5 | Log a 4xx as a save | caught: `a_refusal_or_a_command_is_not_a_save` (:106) |
| M6 | Middleware outside `require_full_auth`, actor "nobody", `/login` kept as a page | caught: both settings_log tests; the signed-out row `("nobody", "/login · /music/sweep")` (:112) |
| M7 | The env value wins | caught: `the_env_server_url_loses_to_the_saved_one` (:745) |
| M8 | The red `attention attention-alarm` tint back on the note | caught: `lock_again_follows_the_status_and_the_capability` (:238) |
| M9 | (mine) The QR payload ignores the env value | **not caught**: 16/16 provisioning tests pass (L4) |

M6 note: moving the middleware out alone is not caught by the signed-out assertion, because the `/login` exclusion still stops the row. The two guards cover each other.

## Browser pass (Chromium 1140; WebKit isn't installed on this VM - the iPhone check stays the user's)

Server 0.54.0 on 127.0.0.1:3199. Setup: one phone enrolled with `window_relock_v1`, Unlock for 2 h sent from the PWA, then the phone reported `restrictions_paused`.

- The summary in light and dark: `chromium-light-summary-paused.png`, `chromium-dark-summary-paused.png`, `chromium-light-quick.png`, `chromium-dark-quick.png`.
- The timing line after `?timing=1`, then after tapping a link (it showed `tap 76 · wait 7 · ...`): `chromium-timing-on.png`, `chromium-timing-after-tap.png`.
- `?timing=0`: no line, the storage key removed (`chromium-timing-off.png`).
- The Security Log (`chromium-security-log.png`).

All are in `scratchpad/s1qa/`, with `setup.py`, `pass.js` and `mutate.py`.
