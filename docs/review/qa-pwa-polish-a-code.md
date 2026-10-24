# QA (code): PWA polish A - the ⓘ cut, Back from Apps › a phone, the start-up splash

Branch `pwa-polish-a` on `main` ed588e91, four commits: 96b64ddf (the ⓘ cut), cf412c71 (Back from Apps › a phone),
98575231 (the start-up splash), 9b2fceef (docs). Server, templates, one script, docs. Reviewed 2026-10-24 on branch
`qa-pwa-polish-a` (`git diff ed588e91..pwa-polish-a`). No Gradle, no emulator, nothing pushed, nothing fixed.

## Summary

- **Back from Apps › a phone is right.** `AppsFrom::parse` takes only `apps` and `app:<digits>` with a positive id.
  The back link is built from the device id and the parsed id only, never from raw input, so it can't be an open
  redirect. Every form on the page carries the hidden `from`, and every redirect keeps it before the fragment.
  `scroll-restore.js` keys its form record by path only, so the query doesn't affect it. `auto-reload.js` drops only
  `notice`/`app`; its other user, Calls, never puts `open=` together with `notice=`. The shell test's exception is
  one exact line for one file.
- **The start-up splash is right.** The script's ring paths are byte-identical to `splash_vibb_breathe.xml`'s
  `ring0..3_path`. The scales (0.94/0.9), the colours, the alpha 0.55 and the stroke 2.6 match too. The 0.43 share
  is 2 x 0.94 x 90 dp / 393 dp = 0.4305. Regenerating in a scratch tree gives byte-identical PNGs and head.html.
  The 13 media queries are Apple's current portrait sizes.
- **The ⓘ cut is mostly right, but it lost three facts that matter and wrote one that is false.**
  - The Storytel ⓘ now says "The phones never get the password". Design 58's step 3, which retires the route that
    still hands it out, isn't built yet (Q-1).
  - Android's lock lost "the alarms may not ring after a restart" (Q-2).
  - The Parent PIN lost the two-hour lift and the lockout's remedy (Q-3).
- **All suites are green.** Three mutations survived: the refused switch's `from` (Q-7), the caching branch's scope
  (Q-8) and the script's drawing (Q-9).

Counts: Blocker 0, High 0, Medium 3, Low 8, Note 6.

## Runs

| Side | Command | Result |
|---|---|---|
| server | `cargo test` (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-pwa-polish-a`, jobs 2, no debug info, `flock ~/build.lock`) | `test result: ok. 1213 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 373.94s` |
| server | `cargo fmt --check` | clean (exit 0) |
| server | `cargo clippy --all-targets` | `Finished`; 13 warnings, all on lines this branch doesn't touch (`models.rs`, `storytel.rs:144`, `dns_engine.rs:54-55`, `devices.rs:1322`) - the same as `main` |
| js | `node --test jstest/*.test.js` (node v20.19.2) | `# tests 140`, `# pass 140`, `# fail 0` |
| script | `python3 -I scripts/pwa-startup-images.py --selftest`, from the repo root and from `/tmp` | `ok: 13 screens`, exit 0, both |
| script | a regeneration in a scratch copy of the tree | all 13 PNGs and head.html byte-identical to the committed ones; 239485 bytes in all |

## 1. The ⓘ cut

I counted `class="info-panel"`: 83 on `main` and 74 on the branch. Every `<details class="info…">` still has a
non-empty panel (I checked by script), and none is left empty. The tests assert the new words, and no test still
holds an old wordy sentence (`music_art` asserts the absence of "at most 400 a day"). The register is plain.

Checked against the brief:

- **Lock › Child PIN, Vibb One's lock**: still true. The back-off, never erasing itself and "Parent code" are kept.
  It lost the consequence that this lock doesn't protect the phone's data and that some bank apps won't work (Q-4).
- **Lock › Child PIN, Android's lock**: "can't be located, rung or erased until the Child PIN is typed" and "The
  Parent PIN doesn't open this lock" are true. The alarms are lost (Q-2).
- **Lock › Parent PIN**: see Q-3.
- **Lock screen**: the panel is gone, and the footer now reads "What the lock shows, anyone near the phone can
  see." That carries the one fact.
- **"SMS login codes"**: moved to the Notifications footer ("Full text: the message too, SMS login codes included,
  which anyone holding the phone can read"). That is right, and the test follows it.
- **Advanced › Restrictions**: Block screenshots is true and complete: recording and casting stop, and the
  recent-apps previews go blank. The recovery reset line is kept. "Keep location on" no longer says that Locate
  needs it (table, Q-5).
- **Safe mode**: "unfiltered" and "repairs nothing" are true. It no longer says that the restrictions still apply
  in safe mode (table).
- **USB debugging, Backup to Google, App screens**: true. Shorter than before, see the table.
- **Storytel**: see Q-1.

## 2. Back from Apps › a phone

- **`AppsFrom::parse`** (`device_pages.rs:1580`):
  - It accepts `apps` and `app:` followed by 1-18 ASCII digits whose value is > 0. `app:007` reads as `App(7)` and
    is written back as `app:7`.
  - It refuses `+1`, `-1`, `0`, spaces, case variants, 20 digits and `app:1&from=apps` (the unit test, and the page
    test through the real query).
- **`apps_url`**: the refusal's parameters, then `from`, then the fragment. The unit test pins the order.
- **`apps_nav`** (`:1629`): a deleted app reads as the list. A failed read of the app's name is a 500 through
  `read_failed`, the same as every other read on this page (the allowlist, the names). That is right: falling back
  silently would hide a database fault on one page only (N-1).
- **The tab**:
  - `Tab` has two variants, and `name()` is a closed match, so the template can only get a tab name.
  - The shell test's exception is `file == "device_apps.html"` plus an exact match of
    `{% let tab = nav_tab.name() %}`.
  - Every other template still goes through the literal rule (the `continue` is inside that one `if`).
- **The forms**:
  - The switches (`device_app_row.html`), Name and icon (`app_display_form.html`, `AppDisplayForm::from`, `None` on
    the catalog page) and Downloads each carry the hidden `from`.
  - The redirects keep it before the fragment: `toggle_app` (the page, `#allowed`/`#not-allowed`, the refusal),
    `save_app_display` and `update_app_updates`.
  - A refused Name and icon answer (400) renders with the Apps tab and the right back link, and keeps `from` in its
    forms.
  - The page test posts every form and checks `assert_lands` and the tab where it lands.
- **The ids**:
  - `#phone-<id>` sits on the Apps list's rows and on the catalog page's plain rows.
  - On the catalog page, the Install Now rows had that id already. The two branches exclude each other, so the id
    stays unique.
  - No other `phone-<n>` id exists. The `phones` ids are different strings.
- **Open redirect**: none. `back_href` is a `format!` of two `i64`s. The hidden `from` is `AppsFrom::value()` of a
  parsed value and is escaped by Askama.
- **`auto-reload.js`**:
  - It is bumped to `?v=4` in both templates, and the test pins the tag.
  - Calls is the only other page that uses it. Its `?open=` never comes together with a `notice=`
    (`calls.rs:896-901`), so keeping the rest of the query changes nothing there.
- **The browser's Back** is untouched, and so is the path-keyed place record.
- **A gap**: Back to a catalog app's page after unticking that app lands at the top of the page. The phone's row
  isn't listed there any more (Q-10).

## 3. Start-up splash

- **The script**:
  - It is `-I`-safe: it uses `ROOT` from `__file__` and imports only the standard library and PIL.
  - It is committed as 100755.
  - It writes only the PNGs and the block between the two markers. The markers are Askama comments that each appear
    once, and `current_block` asserts start < end.
- **The geometry**:
  - It is copied, not read, as documented. I compared it with the drawable: identical.
  - `reach()` = 25.45 + 1.3 = 26.75, and 90 / 26.75 = 3.3645, the drawable's group scale (the self-test checks this).
  - The stroke is scaled with the ring, as Android's group scale does. The core is 4.30 x 0.9.
- **The committed PNGs**: measured through the centre row, the mark is 274-565 px wide against round(0.43 x
  shorter side) = 275-568. The difference is the anti-aliased edge. The centre is the core's cream
  `(251, 228, 220)`, and the corners are night.
- **`pwa_startup_screens.json`**: the 13 rows are correct, each a CSS size, a dpr and portrait.

  | Row | Size, dpr | Pixels |
  |---|---|---|
  | SE 1 | 320x568@2 | |
  | SE 2/3 and 6s-8 | 375x667@2 | |
  | 6s-8 Plus | 414x736@3 | 1242x2208 |
  | X/XS/11 Pro/12-13 mini | 375x812@3 | |
  | XR/11 | 414x896@2 | |
  | XS Max/11 Pro Max | 414x896@3 | |
  | 12/13/14/16e | 390x844@3 | |
  | 12-13 Pro Max/14 Plus | 428x926@3 | |
  | 14 Pro to 16 | 393x852@3 | |
  | 14 Pro Max to 16 Plus | 430x932@3 | |
  | 16 Pro/17/17 Pro | 402x874@3 | |
  | Air | 420x912@3 | |
  | 16-17 Pro Max | 440x956@3 | |

  None is missing up to the 2025 models. For later models and Display Zoom, see N-2.
- **head.html**: 13 links with `?v=1`, in the JSON's order. The shell test checks each media query, href, size and
  body, that the folder holds exactly these files, and that the total is under 300 KB.
- **Caching** (`admin_auth.rs:758`):
  - Only `/static/startup/` with a 2xx gets `public, max-age=31536000, immutable`.
  - `no_store_path` comes first, and nothing under `/static` is no-store.
  - Nothing private can leak: the files are public brand images, served before any session check, and ServeDir
    refuses `..`.
  - `?v=` is on every link, and nothing else references these files.
  - A 304 doesn't carry the header, which is harmless: the stored header stays.
  - Nothing tests the negative: the rest of `/static`, and a 404 (Q-8).
- **The manifest and the theme**: `background_color` is `#0C0C14`, the same as `theme_color` and the one
  `<meta name="theme-color">`. The test pins all three.
- **Packaging**: `server-release.yml` copies `static/` whole into the tarball and `dist/static`, and the Dockerfile
  copies `dist/static`, so the new folder ships.
- **NOTICE.md**: names `server/static/startup/` and the script as the Vibb mark © palchrb, used by its owner.
- **The docs**: pwa.md's "Start-up splash" is accurate (the share, 64 colours, about 240 KB, the test, how to
  regenerate). On a PC browser nothing shows, which the user knows.

## 4. Docs

- **emulator.md §3/§5b**: both AVD paths and the `find` hint are given. See N-3 on the order inside the
  `avdmanager` block.
- **59d §8.16**: the user's `jellystar2` result is accurate and short.
- **server/CLAUDE.md**: the tab sentence names the one exception.
- **Root CLAUDE.md**: the script is listed with its `--selftest`.
- **PLAN**:
  - The two "Done (not released yet)" entries and the backlog's "Done" are terse and accurate.
  - Line 781 is wrong about where the night band was built (Q-11).
  - A blank line now splits the "Done" list after the two new entries (nit, folded into Q-11).
- **The area notes outside pwa.md** still describe the old ⓘ contents (Q-6).

## 5. Attribution

All four commits carry `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and the session line (N-5). The
merger decides.

## 6. Mutations

Each mutation was applied to the file and restored from a copy, with `git status` clean after each one. The script
mutations ran on a scratch copy of the tree.

| # | Mutation | Run | Result |
|---|---|---|---|
| R1 | `parse`: id `>= 0` (accepts `app:0`) | `cargo test apps_from` | caught (both tests) |
| R2 | `parse`: digit check removed (`app:+1`) | same | caught (`apps_from_takes_only_its_two_shapes`) |
| R3 | `parse`: `apps` case-insensitive | same | caught (both) |
| R4 | `apps_url`: `from` before the refusal's params | same | caught (unit) |
| R5 | `save_app_display` success drops `from` | same | caught (`apps_from_the_apps_tab_go_back_there`) |
| R6 | `update_app_updates` drops `from` | same | caught (page test) |
| R7 | `toggle_app` success drops `from` | same | caught (page test) |
| R8 | `apps_nav`: a deleted app goes to the phone's page | same | caught (page test) |
| R9 | `toggle_app` **refusal** (`?notice=…&app=…`) drops `from` (`devices.rs:1851`) | `cargo test apps`, then the full suite | **missed**: `1213 passed` (Q-7) |
| R10 | immutable on all of `/static/` | `cargo test shell::` | **missed** (Q-8) |
| R11 | immutable without the 2xx check | `cargo test startup` | **missed** (Q-8) |
| J1 | `auto-reload.js` keeps `app=` | `node --test jstest/auto-reload.test.js` | caught (1 fail) |
| J2 | `auto-reload.js` drops `from=` too | same | caught (1 fail) |
| P1 | script: pixel w/h swapped | `--selftest` | caught (links differ) |
| P2 | script: the share of the longer side | same | **missed** |
| P3 | script: rings at rest 1.0 | same | **missed** |
| P4 | script: stroke 2.0 | same | caught (reach) |
| P5 | script: ring alpha 1.0 | same | **missed** |
| P6 | script: core colour changed | same | **missed** (the check compares with the constant itself) |
| P7 | script: core not drawn | same | caught |
| P8 | script: link dpr fixed at 2 | same | caught |
| P9 | script: no supersampling | same | **missed** |

P2, P3, P5, P6 and P9 are Q-9.

## Findings

### Q-1 (Medium) - the Storytel ⓘ says the phones never get the password; until design 58 step 3 they can

`server/templates/music_settings.html:97`.

- **Old text**: "Used by the shelf here and by the phones where Storytel is on (each phone's Music page). Kept
  encrypted on this server, never in a log. Saving checks it with Storytel first." / "Use a password you use only
  for Storytel: each phone keeps it to log in. Two phones ..."
- **New text**: "The phones never get the password. Two phones listening at the same time need Storytel Family,
  which isn't supported yet."

**Why it is false today.** `GET /api/devices/music/storytel` still answers `{generation, email, password}` to any
enrolled phone with Storytel on (`device_routes.rs:105`, `music_api.rs:232-291`). The launcher still fetches it for
an older music app (`MusicServer.kt:60`, `MusicBridgeService.answerStorytel`). Design 58 puts the route's
retirement and this text change in step 3 (§2.5, §10 step 3 "the PWA rows (§2.5)"), and step 3 isn't built. A phone
with a music app from before step 2 still gets and keeps the password.

**Also missing.** Design 58 §2.5's remedy is missing: "If a phone is lost, remove it here, then change the password
at Storytel and save it here again."

**What to do.** Until step 3, write something true, e.g. "Phones with the current music app never get the password;
an older one still keeps it." Add the lost-phone remedy, which is true either way. Or leave the line for step 3's
commit. Keep "Kept encrypted on this server", which is the fact a parent asks about.

### Q-2 (Medium) - Android's lock: the alarms after a restart are gone

`server/templates/device_lock.html:112`.

- **Old text**: "After a restart the phone can't be found, rung or erased until the PIN is typed, and Fossify
  Clock's alarms may not ring until then. Android waits 30 seconds after 5 wrong PINs and longer after more, up to a
  day." / "The Parent PIN doesn't open Android's lock: type the Child PIN (Show Child PIN above), then the Parent PIN
  in Settings. A fingerprint works ..."
- **New text**: "After a restart the phone can't be located, rung or erased until the Child PIN is typed. The Parent
  PIN doesn't open this lock."

**Why it matters.** The alarm is the consequence a parent most needs: a phone that restarted at night may not wake
the child (design 59 l.163). The new text also says what doesn't open the lock but not what does.

**What to do.** Use, for example: "After a restart the phone can't be located, rung or erased, and the alarms may
not ring, until the Child PIN is typed. The Parent PIN doesn't open this lock: use Show Child PIN above." The
back-off can stay out (Android shows it on the lock).

### Q-3 (Medium) - the Parent PIN lost the two-hour lift and the way out of a lockout

`server/templates/device_lock.html:166`.

- **Old text**: "It opens Vibb One's Settings on the phone (and there, a pause and Play install mode) [and the lock's
  'Parent code']. On a time lock, 'Enter unlock code' lifts the time rules and app limits for up to two hours. Calls
  and Restrictions stay." / "After 5 wrong tries it waits 15 minutes, then longer each time, up to a day. Saving a
  new PIN here ends the wait once the phone checks in." / "Typed it where your child could see? Change it here."
- **New text**: "It never lifts the call limits or Restrictions. If your child saw it typed, change it here."

**What was lost.** These are exactly the rule's categories:

- a number: up to two hours;
- a "needs the phone online": the lockout ends when a new PIN is saved and the phone checks in.

The footer ("lifts limits, even offline") doesn't say for how long.

**"call limits".** This is a new term. The Calls page speaks of who can call ("only the contacts below can call or
text"), not of limits.

**What to do.** Use, for example: "On a time lock it lifts the rules for up to two hours; never Calls or
Restrictions. Locked out after wrong tries? Save a new PIN here: the wait ends once the phone checks in."

### Q-4 (Low) - Vibb One's lock no longer says what it doesn't protect

`server/templates/device_lock.html:114`.

- **Old text**: "This lock keeps people nearby out. It does not protect the phone's data from a thief with forensic
  tools, and some bank or wallet apps won't work. Android's lock (the Lock row, a test for now) also encrypts the
  data with the PIN."
- **New text**: (gone)

This is the one fact behind the Lock row's choice: why Android's lock exists. The row's sub only says "Test phase".

**What to do.** Add one sentence, e.g. "It doesn't encrypt the phone's data, and some bank apps won't work with it."

### Q-5 (Low) - smaller consequences the cut lost

Each of these is one short clause. Put back the ones the implementer agrees with; the rest can go.

| Where | Old | New |
|---|---|---|
| `device_calls.html:53` | "Turning SMS off also hides the Messages app, since it can chat over RCS." | (gone) |
| `device_calls.html:149` | "Photos (JPEG, PNG or WebP, up to 10 MB) are cropped square ..." | "(up to 10 MB)" - the formats are gone |
| `schedule_budget.html:29` | "A phone with its own rules has its own limits, on its Time page." | (gone): editing the family budget silently skips such a phone |
| `schedule_rule.html:72` | "The same start and end lasts 24 hours." | (gone) |
| `tracked_app_detail.html:89` | "For about a minute the child sees the phone's own home screen, and calls aren't filtered for that minute: anyone can ring through. Update fence ... hides the home screen once it is checked" | "... calls aren't filtered for that minute." - "that minute" refers to no minute; the remedy (Update fence) is gone |
| `device_advanced.html:97` | "the restrictions above still apply there" (safe mode) | (gone) |
| `device_advanced.html:173` | "Notification filter: allowed apps, calls, alarms and emergency alerts always stay." / "Boot cover: from the next restart." | (gone) |
| `device_advanced.html` restrictions | "Keep location on also locks the location settings: Locate needs it." | (gone) |
| `device_locate.html:168` | "Off switches location off on the phone ... The phone shows its location icon while it takes a fix." | (gone) |
| `provisioning_settings.html:24` | "Without one, you enter it on each phone by hand." / "One key sets up one phone" | (gone) |
| `backups.html:59` | "Sign-ins and the phones' Tailscale key are left out." | (gone): after a restore the key must be entered again |
| `wallpapers.html:38` | "Without 'Also on the lock screen', the lock screen shows a colour instead." | (gone) |

### Q-6 (Low) - area notes outside pwa.md still describe the old ⓘ texts

- `docs/notes/server/hardening-and-pins.md:17`: the Child PIN's ⓘ "has ... calls and emergency calls work, ... not
  forensic reading, some bank/wallet apps".
- `docs/notes/server/play-and-updates.md:50`: "reads only app, channel and type, reports only names and counts".
- `docs/notes/server/play-and-updates.md:191`: "about a minute ..., Update fence hides the home screen once checked".
- `docs/notes/server/dns.md:45-47`: "the certificate check, ..., the path id, that Cloudflare/Quad9 fall back ...".
- `docs/notes/server/music.md:405-406`: "the detail and the site-wide blocks it overrides behind the ⓘ".

**What to do.** Bring each in line with the cut, or point it at pwa.md's "The ⓘ cut".

### Q-7 (Low) - the refused switch's address with `from` is untested

`server/src/handlers/devices.rs:1851`, the `refuse` closure: `?notice=…&app=…&from=…#row`. Mutation R9 dropped
`from` there, and the full suite stayed green (1213 passed).

The page test's "refused switch" posts `tracked_app_id=99999`. That takes the `Ok(None)` path (`#not-allowed`), not
this one. pwa.md:370 says the test covers "the refusals".

**What to do.** Add a refused toggle that goes through `refuse` (a code from `toggle_refusal`, e.g. the
`from_play`/`not_an_app` case the redirect table already uses) with `from=app:<id>`. Assert
`?notice=…&app=…&from=app:<id>#<row>`.

### Q-8 (Low) - the long caching is pinned only from the inside

`server/src/admin_auth.rs:758`. Mutations R10 (immutable on all of `/static/`) and R11 (no 2xx check) survived. The
first would freeze `style.css`, the scripts and the icons in every parent's browser for a year, where today only the
`?v=` habit protects them.

**What to do.** In `every_startup_image_has_its_screens_size`, or next to it, assert that `/static/style.css?v=…`,
`/static/manifest.webmanifest` and `/static/icons/icon-192.png` don't get `immutable`, and that a 404 under
`/static/startup/` doesn't either.

### Q-9 (Low) - the script's self-test doesn't check the drawing

`scripts/pwa-startup-images.py:255` (`selftest`). It checks the links, the sizes, the file set, `90/reach()`, a
night corner and the core's centre against the `CORE` constant itself.

Five drawing mutations passed it:

- the share of the longer side;
- the rings at rest 1.0;
- the ring alpha 1.0;
- another core colour;
- no supersampling.

A drift between the script and the committed PNGs (an edit without a rerun) is caught by nothing. Rendering is
deterministic: my regeneration was byte-identical.

**What to do.** Have `--selftest` render each screen in memory and compare its pixels (`Image.tobytes()` of the
quantized image) with the committed PNG's. That catches P2/P3/P5/P6/P9 and any edit not followed by a rerun.

Optional: the self-test could read `splash_vibb_breathe.xml` when it is present, and compare the four path strings
and the numbers. The script would still never need the launcher to draw.

### Q-10 (Low) - Back to a catalog app after unticking it lands at the top

`server/src/handlers/device_pages.rs:1629` (`apps_nav`). From `/apps/tracked/<id>`, open a phone, untick that app,
and tap Back. The link is `/apps/tracked/<id>#phone-<n>`, but the app's page lists only the phones with it ticked,
so the anchor doesn't exist and the page opens at the top.

**What to do.** When the phone no longer has the app (it isn't in `device_tracked_apps` and the app isn't the
launcher), use `#phones` (the group, always rendered with its id). Or accept the top, and say so in pwa.md.

### Q-11 (Low) - PLAN: the night band wasn't built in the identity round

`PLAN.md:781`: "**Done 2026-10-22** (built and merged in the identity round, `pwa-identity-d`; ...)". The night band
is branch `pwa-night-band`, merged in a1be85d1 (35185da3). It isn't in `pwa-identity-d`: `git merge-base
--is-ancestor 35185da3 c148c3b7^2` is false.

Write "branch `pwa-night-band`, merged a1be85d1". Nit in the same place: the new "Done (not released yet)" entries
leave a blank line before "Every release build ...", which splits the list.

### N-1 (Note) - the 500 on a failed name read is right

`device_pages.rs:1753`. Every read on Device › Apps answers `read_failed`'s 500. Falling back to the list on this one
read would hide a database fault that the other reads show. Keep it.

### N-2 (Note) - the iPhone sizes: what the user should check

- The 13 rows match Apple's portrait sizes through the iPhone 17 Pro Max and the Air.
- I can't confirm any model released after mid-2026 (a 17e, the 18 Pro line). A new size shows a plain white
  launch until a row is added.
- Display Zoom changes the CSS size; for example a 16 Pro zoomed reports a smaller width. A zoomed phone matches
  another row (scaled) or none (white).
- Landscape and iPad are left out, as documented.
- On a PC browser nothing shows (known).

### N-3 (Note) - emulator.md's `avdmanager` block names the Studio path first

`docs/testing/emulator.md:69-70`. The block creates the AVD with `avdmanager`, whose default home is
`~/.android/avd` unless `ANDROID_AVD_HOME`/`ANDROID_USER_HOME` say otherwise. `~/.config/.android/avd` is where the
user's Android Studio put `jellystar2`. Both are named, and §5b's `find` finds the file, so this is ordering only.

### N-4 (Note) - 13 links on every page

The head partial now adds about 2.6 KB of `<link>`s to all 45 templates' answers, signed-in pages too. That's fine
for a phone PWA. Mentioned only because it is per request and uncached.

### N-5 (Note) - attribution

96b64ddf, cf412c71, 98575231 and 9b2fceef all end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
and `Claude-Session: https://claude.ai/code/session_011Hd78FugUqFxMYs1oDkE6R`. The merger decides.

### N-6 (Note) - the night splash meets a light page

In light mode the iPhone's and Android's launch goes from the night splash to the grey `#F2F2F7` page. That is the
user's choice ("bare en stor logo og natt bakgrunnen"). The status-bar band is night already, so only the content
area changes.

## Verdict

**Merge after a fix round**:

- Q-1: the Storytel line is false today.
- Q-2 and Q-3: the alarms after a restart, the two-hour lift and the lockout's remedy.
- Q-7 to Q-9: the three missing tests the mutations found.

Q-4 to Q-6, Q-10 and Q-11 can go in the same round. Back from Apps › a phone (the parse, the redirects, the tab,
the ids) and the splash (geometry, sizes, caching, packaging) are correct as they are.
