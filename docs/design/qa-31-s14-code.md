# QA 31 step 14 (code) - the rest of Settings

Reviewer: code QA, 2026-10-14. Branch `design-31-s14`, `c8d0ad44..30eaa5c5` (one commit, 33 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.9, §1.10, §1.12, §3.0, §3.6 (Settings), §4, §5 (§5.1 incl. §5.1.4, §5.2, §5.5, §5.6), §8 steps 14 and 15, §9;
- step 13's status and fix-round table, and "Step 14 implementation status" with its seven choices and the deferrals;
- `server/CLAUDE.md`, `server/DEPLOY.md` (admin auth, backups, updates, release signing);
- `qa-31-s5-code.md` and `qa-31-s13-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/auth.rs` (the Account handlers, `live_warning`, the forced `/auth/change-password`), `admin_auth.rs`
  (`bump_epoch`, `load_active_admin` in `security.rs`), `handlers/admin.rs`, `handlers/settings.rs`,
  `handlers/dns_filter.rs`, `handlers/backups.rs`, `handlers/updates.rs`, `system_update.rs`,
  `system_maintenance.rs`, `handlers/wallpapers.rs`, the routes in `main.rs`;
- all eight templates, `partials/group_flash.html`, and the removed `partials/login_warning.html`;
- every `security_events` writer in `src/` (to check `event_label`'s coverage);
- `tests/settings_pages.rs` in full and the repointed tests (`admin_auth.rs`, `docker.rs`, `wallpapers.rs`,
  `device_pages.rs`, `intake_limits.rs`).

Line numbers are `30eaa5c5`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a short fix round.** The pages are what §3.6 and §8 step 14 ask for, and the parts that matter
most on these pages held up:
- **The one-UPDATE password change and 2FA reset are right.** The new hash (or the cleared TOTP), the lock reset and
  `session_epoch + 1` are one statement with `RETURNING session_epoch`; this session stores the returned epoch, so it
  stays signed in and every other session fails `load_active_admin`'s epoch check. A failed UPDATE is a 500 that
  changes nothing - no path leaves a stale session with a "Password changed" flash, and none locks the parent out
  (the worst case, a failed `session.insert`, signs out only this session). Both are pinned (mutations 1 and 9).
- **Sign Out Everywhere…** now keeps this session and answers 500 when the epoch can't move (it used to sign out
  only this one); success still flushes the session and goes to `/login`. Its `<details>` confirm needs no JS.
- **Security is unchanged where it must be:** `main.rs` is untouched, so every form stays behind `require_full_auth`
  and `csrf_guard`; the password checks (`state.passwords.change` / `verify`, the busy gate, the 12-character
  minimum) and the 2FA reset's current-password demand are the same calls; the update signature check
  (`update.sh`/minisign) and `update_blocked_reason` are untouched; the restore still needs the file in a fresh
  listing plus its typed name, the format a fresh drive list plus the typed size.
- **Nothing secret is echoed:** the password inputs carry no `value`, and `AccountTemplate` has no field that could
  hold one; Phone Setup's refusal re-renders `Entered` (URL, language, zone only) - the key never comes back (pinned,
  mutation 3); no password or typed confirm reaches a URL.
- **Escaping:** event details, ban reasons, list names, domains, backup names and drive paths all go through askama's
  escaping; a stored `<img onerror>` in an event's detail is pinned (mutation 8).
- **Local time:** the lock's end on Account, ban ends, event rows and backup rows are local through `Zone`; the
  stored details keep their own text behind ⓘ (choice 5, reasonable for an audit trail). Pinned (mutations 7, 13).
- **Robustness:** every read on Web Filter, Blocked Activity, Phone Setup and the Security Log is a 500 on a DB
  error; every Web Filter write too; an unknown upstream is a 400, an unknown list's toggle a 404.
- **Save never jumps to the top** for the redirecting pages: every action on Web Filter, Wallpapers, Phone Setup and
  the Security Log is in the redirect table built from the rendered forms; with JS `scroll-restore.js` defers to the
  fragment, without JS the fragment lands on the group. Refusals reopen their disclosure or confirm (Add a List…,
  the restore and format confirms, Upload a Backup…, Reset Two-Factor Login…) with `autofocus` on the field.
- **The brand bar:** removing it from these sub-pages is what §3.0 says ("the brand header goes away on inner pages,
  page by page as each is rebuilt"); step 15 removes it from the tab landing pages, the last pages that keep it. No
  conflict with §8 or the mockups.
- **`login_warning.html`** had two users (the old Account and Devices headers); both now render their own rows
  (`account.html`'s red rows, `login_warning_row` on Devices). Nothing includes it any more; deleting it is right.

What needs fixing:
- **M1:** Backups, Software Updates and Account keep their answer at a POST-only URL: a reload re-sends the POST
  (restart the Pi again, restore again, update again), and the copy itself tells the parent to "open this page again".
- **M2:** about 20 music event ids (`music_*`, written on every music edit) have no label and read as their raw words.
- **L1-L4:** the restore and format confirms and Sign Out Everywhere's 500 are pinned by nothing; the forced password
  change still uses two statements; a refused Phone Setup drops a typed key without saying so; the form-fragment check
  doesn't check the group.

Counts: 0 High, 2 Medium, 4 Low, 3 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-14`), under `~/build.lock`.
- **`cargo test`, unchanged:** 795 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main. None is in the step's code.
- **`node --test server/jstest/*.test.js`:** 16 passed.
- **The mutations.** Each was run against the named filter, then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | the password UPDATE without `session_epoch = session_epoch + 1` | `admin_auth::a_password_change_signs_out_every_other_session` |
  | 2 | Sign Out Everywhere ignores a failed `bump_epoch` (`… .is_none() && false`) | **nothing** (L1) |
  | 3 | Phone Setup's refusal puts the typed key into the re-rendered form | `settings_pages::phone_setup_refuses_under_the_field_and_flashes_a_save` |
  | 4 | the restore's typed-name check removed (`if false`) | **nothing** (L1) |
  | 5 | Unban's redirect without `#banned` | `settings_pages::every_form_on_the_settings_pages_returns_to_its_group` |
  | 6 | `event_label`'s fallback returns the raw id | `settings_pages::the_security_log_reads_as_labelled_rows` |
  | 7 | the live warning's lock end as `%Y-%m-%d %H:%M UTC` | `settings_pages::account_answers_in_its_groups` |
  | 8 | the event detail rendered `\|safe` | `settings_pages::the_security_log_reads_as_labelled_rows` |
  | 9 | the 2FA reset UPDATE without the epoch | `admin_auth::a_2fa_reset_signs_out_every_other_session` |
  | 10 | the password form posting to `#two-factor` | **nothing** (L4) |
  | 11 | a refused global domain back without `#blocked`/`#allowed` | `settings_pages::the_web_filter_refuses_in_its_groups` |
  | 12 | Blocked Activity counting since 1970 instead of local midnight | `settings_pages::blocked_activity_lists_the_phones` |
  | 13 | a ban's end as raw UTC | `settings_pages::the_security_log_reads_as_labelled_rows` |

  Three of thirteen survived (2, 4, 10).

## Medium

### M1. Backups, Software Updates and Account leave the parent on a POST-only URL; a reload repeats the action
`src/handlers/backups.rs` (`create_backup`, `restore_backup` `:862-917`, `restore_in_container` `:922`,
`format_drive` `:790-834`), `src/handlers/system_update.rs:203-266`, `src/handlers/system_maintenance.rs:229-317`,
`src/handlers/auth.rs:648-744`; choice 1.

Choice 1 keeps these pages rendering their answer in the POST's response "since after a restore, an update or a
restart the server goes away, so a redirect's GET could fail". The result is the opposite of safe:
- The browser is left on `/system/reboot#os`, `/backups/backup-…zip/restore#backups`, `/update/restart#app`,
  `/backups/create#backups`, `/account/password#password` - routes that only take POST (`main.rs:1093-1173`).
- The success flashes tell the parent to come back: "Restarting the Pi. Vibb Phone is back in about a minute.",
  "Restarting. Open this page again in a few seconds.", "Formatting the drive. … open this page again then." The
  natural move, a reload or pull-to-refresh, **re-sends the POST**. Browsers ask "Confirm form resubmission?", and a
  parent who taps through **restarts the Pi again**, starts another restart or update, **restores the same backup
  again** (in Docker the server exits a second later and restores on start, discarding anything changed since the
  first restore), or makes another backup. The typed confirms don't help: the typed name is in the re-sent body,
  and the Origin check passes.
- A GET of those URLs (an iOS standalone app restored from the background, a copied link) is a 405.
- §5.1 rule 2 says "A form's POST redirects to the one page its form lives on".

The fear in choice 1 is backwards: a 303's GET is sent straight away, before the root helper picks up the request
file (it polls) and before `restart_soon`'s 1 s delay; if it ever did fail, the parent sees a connection error and a
reload is then a harmless GET.

**Fix:** keep rendering in the answer only for **refusals** (they write nothing, so resending one is harmless, and they
need the reopened confirm). On success, redirect with a typed flash in the query, the way Phone Setup and Unban do
already: `/backups?done=backup|restore|delete|format|schedule|upload#<group>`,
`/updates?started=update|restart|os_check|os_upgrade|tailscale|reboot#<group>` (plus `saved=app-schedule|os-schedule`),
and `/account?changed=password#password` (the password stays in the POST body). Map each value to its `Flash` in the
GET handler (unknown values show nothing), and move these rows into the redirect table. `not_requested`
("Nothing was started") and `update_blocked_reason` stay rendered (nothing happened). Reword choice 1 in the status.

### M2. The music events have no labels: the log shows "Music phone entry", "Music sweep saved"
`src/handlers/admin.rs:58-123` (`event_label`); writers `src/handlers/music.rs:60` (`log_event`) and its ~27 callers.

The status says `event_label` covers "every event id this server writes, an unknown one as its words". It doesn't:
`music::log_event` writes `music_app_catalog_added`, `music_category_added|changed|deleted|moved`,
`music_check_requested`, `music_cover_changed`, `music_entry_added|changed|deleted|moved|saved`, `music_file_deleted`,
`music_files_added`, `music_library_imported`, `music_orphans_deleted`, `music_phone_entry`, `music_phone_settings`,
`music_sonos_rooms_changed` and `music_sweep_saved` (only the three `storytel_login_*` it writes are labelled). Music
edits are likely the most frequent rows in the log, and they read as the raw id with spaces - "Music orphans deleted",
"Music phone entry" - which §2.2b and §4 replace with labels. The unit test and
`the_security_log_reads_as_labelled_rows` even use `music_entry_moved` as the example of "an id from an older
version".

**Fix:** add the music labels in the §4 voice ("Music entry added", "Music moved", "Music files added", "Music library
imported", "Unused music files deleted", "Music on a phone changed", "Sonos rooms changed", "Music check started",
…), and pin coverage cheaply: a unit test with a list of every id written (the literals above plus the
`record_security_event`/`audit.record` ones) asserting `event_label(id) != words_of(id)`. Use a made-up id
(`"retired_event"`) for the fallback example.

## Low

### L1. The restore and format confirms and Sign Out Everywhere's 500 are pinned by nothing
`src/handlers/backups.rs:807, 881`; `src/handlers/auth.rs:824-838`; deferral "Making a backup … write to `data/` in
the working directory".

Mutations 2 and 4 passed every test: the typed-name check that keeps a restore from running on a bare POST, and
Sign Out Everywhere's "keep this session, say 500" branch. `backups_answer_in_their_groups` only reaches the "backup
isn't there" refusal, and its `check_forms("/backups")` sees the Restore/Delete/Format forms only if the developer's
own `server/data/backups` has files and the VM has a USB drive - in CI it sees neither, so their fragments are
checked by nothing either. Both are cheap to fix:
- `BACKUP_DIR`, `SCHEDULE_FILE`, `FLAG_FILE` and `data/restore_request` are CWD-relative constants. `AppState` already
  carries `photo_dir`, `wallpaper_dir`, `music_cover_dir` etc. as temp dirs in tests (`tests/mod.rs:163`); a `data_dir`
  there makes the restore confirm (wrong name → 400, confirm open, no request file; right name → the request file
  says `restore_backup <name>`), Back Up Now, Delete, the schedule save and the Updates request files testable, and
  lets `check_forms` see a backup's forms. The Updates GETs need one more seam: `latest_release_tag` hard-codes
  `https://api.github.com`; a base URL on `Config` (or a `None` repo in tests) lets those POSTs render without the
  network.
- Sign Out Everywhere's failure: a `BEFORE UPDATE OF session_epoch ON admin_users` trigger that `RAISE(ABORT)`s makes
  `bump_epoch` fail without touching the sign-in path; assert 500 and that the same cookie still loads `/devices`.

### L2. The forced password change still moves the epoch in a second statement and ignores both errors
`src/handlers/auth.rs:429-435` (`change_password`, the first-sign-in `/auth/change-password`).

The step fixed this for Account's change and the 2FA reset, but the onboarding change is the same pattern:
`UPDATE … password_hash = ?, must_change_password = 0` with `.ok()`, then `signed_out_elsewhere` (a separate
`bump_epoch` whose failure is ignored), then `Redirect::to("/")` as if done. A failed second statement leaves any
other session signed in with the old epoch, and a failed first one redirects to `/` and loops back to the form with no
reason. Same fix as Account: one UPDATE with `session_epoch = session_epoch + 1 … RETURNING session_epoch`, a 500 on
error; `signed_out_elsewhere` then has no caller and goes.

### L3. A refused Phone Setup save drops a typed Tailscale key without saying so
`src/handlers/settings.rs:180-190`; `templates/provisioning_settings.html:24-34`.

Keeping the key out of the re-rendered form is right, but the parent who pasted a new key and mistyped the zone sees
"Use a name like Europe/Oslo. Nothing was saved." under Time zone, and the key field empty with "Set
(tskey-auth-...abcd)." - the old key's hint - above it. Fixing the zone and pressing Save keeps the **old** key; the
new one-time key is silently lost until a phone's setup fails. The "Remove the stored key" tick is lost the same way.
**Fix:** when the refused form carried a key (or the remove tick), add under the key field "Paste the key again - it
isn't kept when the form is refused." (and keep the tick checked; it's not secret). Pin it in
`phone_setup_refuses_under_the_field_and_flashes_a_save`.

### L4. `check_forms` checks that a fragment exists, not that it is the form's own group
`src/tests/settings_pages.rs:39-66`.

For the pages that render their answer (Account, Backups, Software Updates) the redirect table can't post, so
`check_forms` is the only check on where the answer lands. It accepts any fragment that names some element:
mutation 10 (the password form posting to `#two-factor`) passed, and the answer would land a group below the result.
**Fix:** for each form, find the nearest enclosing `<section id="…">` (or `<form id>` for Phone Setup) and assert
the action's fragment equals it (or a listed exception such as `#wallpaper-{id}`). If M1 moves the successes to
redirects, the table covers them and this check guards the refusals.

## Nits

- **N1.** `/security?unbanned=<anything>` shows "<anything> is unbanned." whether or not that address was banned (it is
  escaped, so only the words are wrong). Unban could redirect with `?unbanned=1` and the flash read "Unbanned."; or
  the GET shows it only for an address with no current ban.
- **N2.** The post-sign-in warning (`admin_auth::login_warning`, `:268-296`) still says "failed 2FA attempt(s) since
  your last sign-in" under Account's "Wrong codes before this sign-in"; §4 words it "wrong two-factor code(s)", as the
  live warning beside it already does.
- **N3.** A refused add on Web Filter (a name that isn't a domain, a list without an http(s) address) redirects, so
  what was typed is gone; Phone Setup keeps the typed values on its refusal. Passing the typed text back
  (`&typed=…`, escaped into `value`) would let the parent fix one character instead of retyping a URL.

## Checked and fine

- **Choices 2-7:** the schedule summaries gone with the values in the form (2); a schedule's result at the top of its
  form (3); Blocked Activity's per-phone "12 today" counted from local midnight like the Settings row (4, mutation 12);
  stored details as stored behind ⓘ (5); `/dns/blocklists/{id}/delete` without a button, back to `#lists` (6); an
  idempotent wallpaper delete and a built-in's plain 400 (7).
- **Refusals in their groups:** a global domain that isn't one → `/dns?notice=…#blocked|allowed` (was a 400); a bad
  list → `#lists` with Add a List… open and `autofocus`; the password fields, the 2FA reset, Restore's and Format's
  confirms and the upload say why under their field with the confirm/disclosure open (400).
- **Web Filter:** `create_blocklist` now refuses a non-http(s) URL and caps name/URL length; `delete_custom_domain`
  takes its group from the deleted row, falling back to the form's `list_type` only for an already-gone row; the
  phone page's `back=filter` still returns there without a fragment, as step 5 settled.
- **Wallpapers:** an upload lands on the new photo's row (`last_insert_rowid` of the insert), the switch on its own
  row, Delete… is a `<details>` confirm; no `confirm()` left on any of the eight pages (`check_forms`).
- **Security Log:** the raw id appears only in the ⓘ panel ("Logged as …"), the paging links carry `#activity`, every
  label in `event_label` is an id something writes (I grepped each), the 54-word intro is behind the footer's ⓘ.
- **Typed flashes:** `Flash` + `group_flash::show` render only in the named group; red with the reason behind ⓘ;
  `not_requested` says nothing started.
- **Docs:** `server/CLAUDE.md`, `DEPLOY.md` and `emulator.md` name the new controls; the country code stays with step 8
  (§8 step 8 moves it to Phone Setup as a second form), as the status says.
- **CSS:** `style.css?v=19`, appended; the merge with step 6's `v=18` is the known conflict.
