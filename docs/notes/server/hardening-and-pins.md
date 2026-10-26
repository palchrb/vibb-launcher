# Server notes: hardening-and-pins

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Hardening (handy step 4, design `docs/design/04-hardening.md`, at the monorepo root)

- **`device_policy` has eleven switches** (migrations `0023`/`0024`/`0034`/`0089`, `models::Hardening`, flattened into `DevicePolicy`): `disallow_factory_reset`, `disallow_add_user`, `disallow_modify_accounts`, `disallow_config_vpn`, `disallow_usb_file_transfer`, `disallow_debugging_features`, `disallow_safe_boot`, `lock_location`, `disallow_airplane_mode`, `disallow_config_locale`, `disallow_screen_capture` (`tests::hardening::SWITCHES`). All default on except `disallow_safe_boot`, `disallow_airplane_mode` and `disallow_screen_capture` (`Hardening::default()` mirrors the column defaults). `PolicyResponse.hardening` always carries all of them explicitly; the launcher sets the matching Android user restrictions while the phone is managed and its override/pause never lift them. `disallow_screen_capture` (0089, 2026-10-23, "Block screenshots") is the launcher's `setScreenCaptureDisabled` - no screenshots, screen recording or casting, new Recents cards blank (`docs/design/04-hardening.md` "Screen capture").
- **Advanced › Restrictions** (`/devices/{id}/advanced`; the device page's "Phone hardening" card until design 31 step 3): one auto-submitting form to `POST /devices/{id}/hardening` (`devices::update_hardening`; a missing checkbox is off, 404 for an unknown device, 500 and no nudge on a DB error; back to `/devices/{id}/advanced#restrictions`). Its footers and ⓘ explain that blocking USB debugging (the default) removes adb as the repair path, that safe mode repairs nothing, and the real recovery paths. A managed device (allowlist or managed calls) without an override PIN gets a warning (`managed_without_pin`). Deploy order: this server first (migration 0023 defaults existing rows to "debugging blocked"), set the switches and a PIN, then the launcher. Switch debugging off before enrolling a phone that still needs adb work (e.g. granting the call-redirection and call-screening roles).
- **A corrupt stored allowlist is a warning on the device page** (`allowlist_corrupt`), not silently "nothing allowed" (QA step 1 #11).
- **Backup to Google** (Google account on the phone, runbook `docs/setup/google-account.md` at the monorepo root; migration `0041_backup_service.sql`): status `backup_service_enabled` -> `device_status.backup_service_enabled` (missing from an older launcher, or `null` = NULL). Advanced › Restrictions shows the latest report: `false` a quiet "Backup to Google · Off" row, `true` "On" plus a warning above the group (and an Attention alarm), NULL nothing. No switch: the launcher always keeps Android's backup service off while managed (override and pause included). Tests: `src/tests/hardening.rs` `backup_service_*`.

## Handy's own PIN lock (handy step 10, design `docs/design/10-lock-and-call-ui.md`, at the monorepo root)

- **Migration `0030_kid_lock.sql`**: `device_policy.kid_pin_hash/kid_pin_salt/kid_pin_length` (NULL hash = no lock), `device_status.lock_state_json`.
- **Kid PIN** (`src/kid_lock.rs`, pure + unit tests): 4-6 ASCII digits, hashed with `security::hash_pin` like the parent (override) PIN. Set/remove on Lock Screen & PINs (design 31 step 4; the device page's "Screen lock" card before; `POST /devices/{id}/kid-lock`, `new_kid_pin` / `clear_kid_pin`; 404 unknown device, 500 nothing written on a DB error; security events `kid_pin_changed`/`kid_pin_cleared`; nudges). Refused (nothing written, `?notice=<code>` flash on the device page, `kid_lock::flash_text`) when not 4-6 digits, **without an override PIN** (it is the lock screen's "Parent code") or when it verifies against the override hash (`security::verify_pin`). The other way round in `update_policy`: a new override PIN that verifies against the kid hash is refused, and the override PIN can't be removed while a kid PIN is set. PBKDF2 for these checks runs in `spawn_blocking`; the kid-PIN `UPDATE` only applies `WHERE override_pin_hash IS NOT NULL` (0 rows = needs_override) and `update_policy`'s SQL keeps the override while a kid PIN is set, so parallel saves can't leave a kid PIN without its parent code. The **first** kid PIN also sets `disallow_safe_boot` (safe mode skips the launcher's lock); later changes leave the switch as the parent set it.
- **Wire**: `PolicyResponse.kid_lock = {pin_hash, pin_salt, pin_length} | null`, always sent (`policy_json_keys_snapshot`); a half-written row (length outside 4-6, a NULL column) is sent as null - a privacy lock fails open. The kid hash appears only there (never in a status, log or page). Status `lock_state` is stored **re-serialized through `kid_lock::LockState`** (`sanitize_lock_state(value, now_ms)`: only `active, inactive, locked, failures, backoff_until_ms, exempt_yields, voip_fsi_denied, voip_rang`, `inactive` capped at 64 chars), so no unlock times or PIN material can reach the DB. Capability `pin_lock_v1`. **`voip_rang`** (launcher round 7, design `r7-launcher-small-items.md` §1.1.3): `[{package, last_at_ms}]`, the apps whose call rang over the LOCKED lock in the last 7 days; parsed leniently (`#[serde(default)]` + a per-entry `deserialize_with`: a list that isn't one is empty, an entry that doesn't parse is dropped - never the whole `lock_state`), then `valid_package_name`, a time > 0 and at most 5 min past the server's `now_ms` (clock skew), one per package (the newest), newest first, at most 8. Lock Screen & PINs' Child PIN group shows one quiet row per entry, "Calls from <name> · Rang over the lock · 14:02" (`kid_lock::rang_notes`, `LockNote::RangOverLock`: the name the Apps page shows - catalog or the phone's label - else the package; the time as the page's other times, `Zone::when`), only with a Child PIN set.
- **PWA** (Lock Screen & PINs › Child PIN since design 31 step 4): the footer's ⓘ has the privacy text (since the ⓘ cut, pwa.md: the back-off, the Parent PIN opens it, it doesn't encrypt the phone's data, some bank apps won't work), the phone's state as rows and warnings as typed `kid_lock::LockNote`s: launcher without `pin_lock_v1`, safe boot allowed, every `inactive` but `no_pin` (`kid_lock::Inactive`; `android_credential`'s adb removal behind the warning's ⓘ), a VoIP app without full-screen intents (its adb fix behind the ⓘ). `call_state.in_call_ui_failed_at` (the in-call screen couldn't come up over the lock) is a call warning for 7 days. The Locate tab's ⓘ under the actions explains what Lock does with and without a Child PIN (the answers "locked (handy lock)" / "locked (Android)" read "Locked with the Child PIN" / "Screen off (no Child PIN set)").
- Tests: `src/tests/step10.rs`, `kid_lock::tests`, `security::tests::pin_hash_shared_vector` (same vector as the launcher's `PinHashTest`).

## Parent PIN (design 24, `docs/design/24-parent-pin-hardening.md`, migrations `0056_parent_pin_easy.sql`, `0057_device_pin_events.sql`)

- **Its own group and save**: `#parent-pin` on Lock Screen & PINs (design 31 step 4; a device page card before); `POST /devices/{id}/parent-pin` (`handlers::parent_pin::update_parent_pin`, full auth) takes `new_pin` or `clear_pin`. `update_policy` (the quick controls and the web filter switch) no longer touches the PIN columns. Refused with a notice and nothing written: not 6-10 ASCII digits (`parent_pin_invalid` - silently ignored before), the kid's PIN (`override_is_kid_pin`), removing it while a kid PIN is set (`override_needed_by_lock`, the SQL `CASE` keeps it even against a parallel kid-PIN save). Security events `override_pin_changed`/`_cleared`; nudges the phone, which then takes the new PIN - and a fresh lockout state with it. Every outcome redirects to `/devices/{id}/lock?notice=<code>#parent-pin`.
- **Easy PINs are warned about, never refused** (user's answer; no date check): `parent_pin::easy` - all one digit, every step +1 or -1 mod 10 (890123, 1234567890), or `COMMON_PINS`. Saved with `override_pin_easy = 1` and `parent_pin_saved_easy`; Lock Screen & PINs shows the warning and the summary an Attention row. PINs stored before the check get it once from a startup task (`parent_pin::run_easy_sweep` with `easy_candidates()`, the 187 PINs `easy` flags: PBKDF2 per candidate, one device at a time, `UPDATE ... WHERE override_pin_hash = ?` so a newer save wins). `override_pin_easy` is not in the policy JSON.
- **Flashes inside their groups** (design 31 §3.0): the `?notice=` codes of the Parent PIN render in `#parent-pin` (`parent_pin::flash_text`), the Child PIN's in `#child-pin` (`kid_lock::flash_text`, `(text, warning)`), under the group's rows; a refusal opens the form again. Nothing at the top of the page.
- **The phone's events** (`POST /api/devices/pin-events`, `handlers::parent_pin::pin_events`): read leniently field by field (`parent_pin::clean_event`) - a bad id (not 32 hex) or an unknown `kind`/`entry` is skipped and logged; a time outside 2020..now + 1 day is kept with the received time and `clock_off = 1`; an implausible `until_ms`/`failures`/`level` becomes null. `INSERT OR IGNORE` on `(device_id, event_id)`; only a newly stored `unlocked`/`lockout` also writes `parent_pin_used`/`parent_pin_lockout` to the security log, in the same transaction, its detail built only from the checked enums and numbers and saying when it happened on the phone (`parent_pin::log_line`: `device 3, 2026-10-09 14:02 UTC: ...`, the window's end dated) - the row's own time is its arrival. Since 0.55.0 (security review L1) that line and every other a phone's
  report writes (`boot_unseen`, `app_installed`, `play_install_mode`, `phone_app_blocked`, `phone_foreign_access`,
  `phone_relock_findings`, `storytel_login_sent`) go through `security::record_phone_event`: at most
  `PHONE_LINES_AN_HOUR` (60) a rolling hour per phone (counted by the lines' `device {id}:`/`device {id},` start), the
  next one a `phone_lines_dropped` row, the rest of the hour none - the phone's own pages keep what they list. Wrong tries stay in the PWA (Lock Screen & PINs › Recent use; the summary's count went with its Now, 2026-10-20). **No `command_notify`**: a sync would end the override. Retention: 90 days by `received_at` (`retention::prune`).
- **PWA**: Lock Screen & PINs › "Recent use" lists the last 10 events as rows (`parent_pin::event_row`: a short title, the details under it, the local time with the exact one in `title`; a lockout red), or says Vibb doesn't report yet (no `parent_pin_events_v1`). The PIN warnings are Lock Screen & PINs' only: the summary's notices (Now, design 48; Today before - the latest use, a lockout or the count of wrong tries) went with Now (the user, 2026-10-20) and `parent_pin::notices`/`Notice` with them; a use or a lockout is an event in the summary's Activity (design 35/48). `device_policy.override_pin_set_at_ms` is still written on a save, read by nothing now. The old `offline_override_used` flag shows (as a warning on Lock Screen & PINs) only without the capability. The security log keeps its UTC lines (`parent_pin::log_line`).
- Tests: `parent_pin::tests` (every easy rule, the full 6-digit loop against the candidates, `clean_event`, every line), `src/tests/parent_pin.rs`.

## Windows that lock again, unseen boots (design 24b, `docs/design/24b-override-relock.md`, migrations `0063_window_relock.sql`, `0064_unseen_boots.sql`, `0067_relock_blocks.sql`)

- **The phone's parent-PIN windows** (an override, a pause) lock down again by themselves; the launcher's `relock` report (status, capability `window_relock_v1`) says what it reset and what it couldn't undo. Stored re-serialized through the known fields only (`window_relock::sanitize_relock`: known trigger/windows/step names, valid package names, at most 8 per list, counts 0..1000) in `device_status.relock_json`. The summary's Locked Again group shows for 7 days after a relock that found something (`window_relock::findings`, typed `Findings`, design 31 step 10): when (local, with its age) and why, what it removed (Wi-Fi proxies, certificates, an Android screen lock), what is still on (accessibility services, notification access, device admins by the phone's name for them, a proxy on a network, a screen lock it couldn't remove), system apps turned off, Vibb's notifications off, the failed steps in words. An older launcher's relock `blocked` apps (with their keys from `blocked_signers`) are the Blocked Apps group's for that week (`window_relock::relock_blocked`): one with another key than the catalog's pin - `tracked_apps.signer_sha256` - stays blocked ("Not the catalog's app: uninstall it"), "Allow these apps…" lets out the others until the phone answered one pressed after the relock. A new `at_ms` with findings writes `phone_relock_findings` (`window_relock::log_line`, the phone's time in the detail).
- **Commands** (`handlers::window_relock`, full auth, plain forms): `POST /devices/{id}/end-windows` ("Lock again", a quick action on the summary while the latest report has a pause or an install mode still running and the capability), `POST /devices/{id}/allow-blocked` ("Allow these apps…", a `<details>` confirm under the Blocked Apps list that names the apps) and `POST /devices/{id}/start-pause` ("Unlock for 2 h…", a quick action behind a `<details>` confirm, the phone's own pause without the PIN on it; shown while no pause is reported). Each is a row in the summary's Activity (design 48), with its status word and the phone's answer reworded; the old Today answer lines (`window_relock::answer`) are gone. Their own insert into `device_commands` - `locate::queue_command`'s "replace the undelivered one" skips them (`window_relock::OWN_INSERT_COMMANDS`) and they delete nothing (QA #8); the phone syncs once more after each. Security event `device_command_queued`; redirect to the summary group the form is in, the flash there (`window_relock::flash`): `/devices/{id}?notice=…#quick`, `…#blocked-apps` for Allow.
- **Do Not Disturb** at the last sync is an Attention alarm linking to About This Phone › Sound, where it is a red value (`sound_mode::state`, typed).
- **Current blocks** (fix of 2026-10-12): status `relock_blocks` `[{package_name, signer, reason, found, at_ms}]` - every app the phone keeps hidden whatever its tick, from a relock or the install check outside windows (`sanitize_relock_blocks`: valid names, a valid key or none, known reason `outside_play|other_key|catalog_key|unknown` and place `relock|install`, at most 16; `[]` = none, NULL = an older launcher) -> `device_status.relock_blocks_json` (0067), with `relock_blocks_total` (how many in all, stored only with the list - 0068; the group's footer says "And N more the phone doesn't list ...", `Blocks::more`, QA of cmd-expiry Low 2). The summary's Blocked Apps group (`#blocked-apps`, the Attention row "N blocked apps wait" lands there) lists each by the phone's name for it (else its package), why and when, its package and key behind the row's ⓘ (`window_relock::blocks`, typed `BlockItem`/`BlockWhy`) and whether it is allowed under Apps ("Allowed" / "Not allowed", the ⓘ linking to its row on the phone's Apps page, `/devices/{id}/apps#app-<package>`; nothing for an imposter or unmanaged apps - QA of cmd-expiry Low 3), with "Allow these apps…" while one can be let out; one with another key than the catalog's pin stays ("Not the catalog's app: uninstall it"). The button posts the listed apps with the keys shown (hidden `blocks` = `package:key,...`, `allow_form_value`/`parse_allow_form`; nothing valid -> 400) into `device_commands.payload` (0068), and the pop sends them as `pending_command.allow` `[{package_name, signer}]` (left out for other commands): the phone lets out only those, each while its key is still the one shown - never a block that came after the parent looked (QA of cmd-expiry, Low 1); a row from before 0068 has none and lets out every block, as before. `device_command_queued` names the apps. When the phone reports them, the last relock's `blocked` gives way to this list. `phone_app_blocked` in the security log for each newly reported block.
- **Foreign access** (the fix round, QA High 1 (b)): status `foreign_access` `{admins, accessibility}` (apps outside the allowlist with a device admin or an accessibility service on; valid package names, at most 8 each, `sanitize_foreign_access`) -> `device_status.foreign_access_json` (in 0063). While the latest report has one, the phone refuses the parent PIN and the summary shows a red warning (`#foreign-access`, the Attention row lands there) with the steps (`window_relock::foreign_view`, typed: the apps by the phone's name, the packages behind its ⓘ; Unlock for 2 h, turn them off and uninstall them on the phone, Lock again, change the Parent PIN); `phone_foreign_access` in the security log when it appears, changes or goes.
- **Unseen boots** (H4, `boot_witness`): status `unseen_boots` `{count, last_at_ms}` (read leniently) -> `device_status.unseen_boots_count`/`_last_at_ms`; `boot_unseen` in the security log when the count rose; for 30 days an Attention alarm and About This Phone's "Starts without Vibb One" row (`boot_witness::unseen`, typed: count, last time, `BootAdvice`) say so ("safe mode, or a start cut short"), advising "Block safe mode" or, with it on since before the boot was noticed (`device_policy.disallow_safe_boot_set_at_ms`, in 0064: set when the hardening card or the first kid PIN turns it on, cleared when off), "probably a start cut short" - otherwise both explanations. `disallow_safe_boot` by default (design 24b S4) waits for device check 6 on the Jelly Star.
- Tests: `window_relock::tests` (the typed views in Oslo time), `boot_witness::tests`, `src/tests/window_relock.rs` (the summary's groups), `src/tests/hardening.rs` (`unseen_boots_*`).

## The lock's glance page (design 25, `docs/design/25-lock-glance.md`, migration `0066_lock_glance.sql`)

- **What it is**: the phone's PIN lock always comes up on a first page (clock, date, the music tile, up to three
  notification rows, "Lås opp", "Nødsamtale"): missed calls always, and messages from the apps the parent ticks here,
  sender only unless that app's text switch is on (design 51). A row's tap only reaches the keypad; nothing is answered, marked read or
  dismissed from the lock. The phone reads the notifications in memory only - **no row, name, text or count ever
  comes to this server** (only the capability `lock_glance_v1`).
- **Data** (`src/lock_glance.rs`): `device_lock_glance_apps` (device_id, package_name, `show_text` since 0083 -
  default 0, the sender only; one row per tick, cascades with the device - a table, like `device_tracked_apps`, so
  there is no corrupt state). An untick deletes the row, so a new tick and a re-tick start at the sender only. The
  migration ticks Element X for every existing device; new ones get it from the `devices_get_lock_glance_default`
  trigger (as `devices_get_builtin_wallpapers`), so every path that creates a device has the default.
  `device_policy.lock_glance_text` (0066's one switch) is unused since 0083 copied it to every tick of its phone.
- **Wire**: `PolicyResponse.lock_glance = {apps: [{package, show_text}]}` (design 51; before it `{apps: [pkg],
  show_text}`), always sent; `apps` sorted, only valid package names (`app_display::valid_package_name`, the
  launcher's grammar). Cosmetic like the wallpapers: a failing query sends `apps: []` (missed calls only on the lock)
  and logs, never a 500. An older server's missing key reads as Element X, sender only on the phone; an older
  server's strings read as sender only on a launcher with design 51. **Release note**: a launcher from before design
  51 drops the objects and shows missed calls only on its lock until it updates (design 25's fail-safe; the gap
  between the server and launcher releases is accepted, design 51 §1.3) - say so in both release notes.
- **PWA** (`handlers::lock_glance`, full auth, CSRF as every form; Lock Screen & PINs since design 31 step 4, the
  Apps card's "On the lock screen" before): the explanation and Low 8's warning (names show to anyone holding the
  phone even with the text off, missed-call names included; with an SMS app's text on its login codes show too) in
  the Lock screen group's ⓘ, the warnings (typed `lock_glance::Note`: no Child PIN, `notification_listener_enabled`
  false, a reporting launcher without `lock_glance_v1`) and, under "Notifications on the lock screen", one
  auto-saving switch per app (`POST /devices/{id}/lock-screen/apps`, `package_name` + checkbox `on`, in the cell
  `#lock-screen-app-<package>`) and, under each ticked one, its own small form "Show message text" (design 51: `POST
  /devices/{id}/lock-screen/apps/text`, `package_name` + checkbox `show_text`; sub-line "Text shown on the lock" /
  "Sender's name only"); the footer "Anyone holding the phone can read the message text." is the group's. **Since
  2026-10-21 both are one select per app** (Off / Name only / Full text, `show` on the same route, the text route gone;
  `docs/notes/server/pwa.md`, "One select per app on the lock"); the stored data and the policy are unchanged. The global
  switch (`POST /devices/{id}/lock-screen`, `#lock-screen-text`) is gone. Listed: with an allowlist the allowed apps (the "Names and
  icons" set), without one every app the phone reports (QA Low 7: a calls-only phone with a kid PIN has the lock
  too), never Play core or the launcher; every ticked package outside that list is added - "Not allowed"
  when the allowlist lacks it, else "Not on the phone" (`lock_glance::unlisted_note`) - so any tick can be
  removed; an empty list says why (`empty_text`: no report yet, nothing allowed, nothing reported). 404 unknown device
  (the text route: also an app that isn't ticked - `UPDATE ... WHERE device_id AND package_name` touched no row, so a
  stale page writes nothing), 400 a package that isn't a package name or is Play core, 500 on a DB error - nothing
  written; security events `lock_screen_app_changed` (`lock_glance::log_line`: "device 3: io.element.android.x on the
  lock screen: on") / `lock_screen_text_changed` (`text_log_line`: "device 3: io.element.android.x's message text on
  the lock screen: on"); nudges; redirect to the app's row, `/devices/{id}/lock#lock-screen-app-<package>` from both
  forms (scroll-restore keeps the place). The
  Home Screen's notification-access warning and the Lock screen group's ⓘ name the lock's first page.
- Tests: `lock_glance::tests`, `src/tests/lock_glance.rs` (both migrations on an old database - 0083's backfill per
  phone -, the trigger, the cascade, the policy incl. a dropped table, both routes - the text per app, a re-tick
  resetting it, the 404 for an unticked app -, refusals, the page and its notes), `device_pages`'s form table,
  `policy_json_keys_snapshot` (`lock_glance` non-null, a fresh phone's `{"apps": [{"package":
  "io.element.android.x", "show_text": false}]}`; the launcher's `PolicyResponseCompatTest` decodes the same literal),
  `music_spotify::migrations_73_to_95_in_order`.

## Android's lock screen (design 59, `docs/design/59-android-keyguard-design.md`, migration `0085_kid_keyguard.sql`)

Step 1 (the contract and the storage; step 2, the PWA's side, below):

- **Columns**: `device_policy.kid_keyguard` (`'own'` default, `'android'`), `kid_pin_sealed`, `kid_pin_prev_sealed`,
  `kid_pin_prev_rev`, `kid_pin_rev` (0). **Policy**: `kid_lock` always carries `keyguard` (`"own"` for anything
  stored but `"android"`) and `pin_rev` (`kid_lock::policy_kid_lock`; inner keys pinned by
  `step10::kid_pin_saved_sent_and_safe_boot_blocked`, the launcher's `PolicyResponseCompatTest`).
- **The key**: `data/keys/kid-pin.key` (`kid_pin::load`: `keys::load_or_create` + `MusicKey::from_bytes`, the music
  key's file format, its own file), `AppState.kid_pin_key`; unreadable = logged once in Child-PIN words, the PIN is
  then saved without a copy (`kid_pin_sealed` NULL, flash `saved_not_kept`), never the lock. No backup holds it. Tests
  make it in their temp dir. **Sealed**: `v1:<fingerprint>:<nonce b64>:<ciphertext b64>`, AAD
  `kid-pin-v1:<fingerprint>:<device id>` (`kid_pin::seal`/`open`; `open` returns only a valid Child PIN). The digits
  travel as `kid_pin::KidPin` (redacted `Debug`, transparent `Serialize`); no log line takes one.
- **Save** (`update_kid_lock`, one transaction): the new sealed copy, `kid_pin_rev + 1`, and the PIN the phone last
  confirmed kept in `prev` - both prev columns keyed on `kid_pin_prev_rev IS NULL` (qa-59-revision M-2: a legacy PIN
  saved before 0085 stays `prev_rev` 0 with no sealed copy, "an earlier PIN", over any number of saves), then the
  queue rule. **Clear**: hash, sealed and prev NULL, rev + 1, `kid_keyguard = 'own'`, a waiting `set_kid_pin`
  cancelled (`kid_pin_cleared`), one transaction. **Status intake** (`kid_lock::after_lock_report`, a launcher with
  `android_keyguard_v1` only): prev NULLed once the phone reports `lock_state.pin_rev` = the current rev.
- **`set_kid_pin`** (`kid_lock::queue_pin_command`, one `INSERT ... SELECT`): android, a sealed PIN, the last report's
  capabilities have `android_keyguard_v1` (no row at all for an older launcher) and no row waiting (never delivered and
  `models::COMMAND_OPEN`, the pop's own fragment). At the intake also only when the phone needs it - it reports
  `keyguard: own` (a phone updated after the switch) or a `pin_rev` number other than the current (never `null`, open
  question 4) - and the newest row is neither in flight (delivered within `REDELIVER_WINDOW_MINUTES`, no answer:
  qa-59-revision L-2) nor answered (any answer: a refusal, or a success the phone's report didn't follow, e.g. an
  unmanaged phone reporting its own lock - no loop; step 2's "Send again" retries). In flight excludes a row used up
  by a later pop. Only reports and rows since `devices.enrolled_at` (`models::ENROLLED_AT`) count, in
  `device_api::last_capabilities` too - a phone enrolled into the row anew isn't the install that reported before
  (qa-59-step1 S59-2/S59-3). The pop (`device_api::pop_command`) hands a `set_kid_pin` out only while the stored
  `kid_pin_rev` is the one the answer read: a save racing a poll leaves the row waiting for the next poll, so an
  answered row always carried its rev's PIN (S59-1). Never expires. **Delivery**
  (`build_policy`, `kid_lock::deliverable_pin`): `pending_command.pin` opened from the same `device_policy` row as the
  answer's `kid_lock`, only in android mode and with the capability in the last report; otherwise left out (the phone
  answers `refused: no_pin`), logged. Every device answer is `no-store` already (`limits`).
- **`lock_state`** gains `keyguard` (the two words, else dropped), `pin_rev` (>= 0), `reset_token`,
  `keyguard_relocks` (0..=1000). While a rollback waits for the token the phone reports `keyguard: android` and
  `inactive: android_credential` - `keyguard` names the gate, `inactive` why Vibb One's lock is off (qa-59-revision
  N-C); step 2 words that pair as "Switching back…" (below). **Power** `on.unlocked_at_ms` is parsed (anything but a whole
  number = `None`); step 2 words it.
- Tests: `kid_pin::tests`, `kid_lock::tests::the_keyguard_fields_are_kept_typed`, `src/tests/kid_keyguard.rs` (the
  legacy, B, C sequence, clear, no key, the queue rule per condition, delivery and the `pending_command` keys with and
  without `pin`, the intake), `power::a_launcher_report_raises_both` (`testdata/power_report.json`).

Step 2 (Lock Screen & PINs; `docs/notes/server/pwa.md`, "Lock Screen & PINs", has the page's forms):

- **The switch** (`devices::update_kid_keyguard`, `POST /devices/{id}/kid-lock/keyguard`, `keyguard` = `own` |
  `android`, else 400): only with a Child PIN (`UPDATE ... WHERE kid_pin_hash IS NOT NULL`, else flash
  `keyguard_no_pin`); to android the queue rule runs in the same transaction (a kept PIN migrates the phone at once),
  to own a waiting `set_kid_pin` is cancelled (`keyguard_own`); a change logs `kid_keyguard_changed` ("device 3:
  Android's lock"), every save nudges. Labelled for the test phase: Android's lock is the destination (PLAN).
- **Show Child PIN** (`device_pages::lock_action`, `POST /devices/{id}/lock#show-pin` with `action=reveal_kid_pin` and
  `password`; another action 400): `admin_auth::reauth` (the gated Argon2; three wrong in a row end every session of the
  account, the count - `admin_users.reauth_wrong` - shared with the password and 2FA forms), then the page rendered with `kid_lock::shown_pin` over
  `kid_lock::KeptPin` (the four columns in one read): the PIN; for a phone with the capability reporting another rev,
  the confirmed PIN kept in `prev` when its rev is the phone's ("New Child PIN" + "Until the phone gets it, its PIN is
  still 1357."), else "The phone still has an earlier PIN"; a reported `pin_rev: null` on Android's lock says the PIN
  was changed on the phone; a launcher without the capability gets the newest alone. Nothing to show: "Not stored on
  this server" (legacy, or saved without the key), "Not readable on this server" (another key), "can't read its key
  file" (`kid_pin_key` none). Never a redirect or a URL: `no_store_path` has `["devices", _, "lock"]`; the security log
  gets `kid_pin_shown` / `kid_pin_reveal_refused` with "device N" only. The password field is `autocomplete="off"`.
  The PIN hides after a minute (CSS) and when the page is left (`static/reveal.js`, which also makes a reload a GET);
  "the phone still has" reads only a report since the enrolment (pwa.md, "Lock Screen & PINs").
- **Send the Child PIN again** (`devices::send_kid_pin`, `POST /devices/{id}/kid-pin/send`): the queue rule with no
  report; `pin_not_kept` without a sealed copy, `pin_not_sent` when it made no row (own, no capability, one waiting),
  else `pin_sent` + `kid_pin_sent` + a nudge. Offered (`kid_lock::offers_send_again`) while Android's lock is chosen,
  a copy is kept, the phone (its last report since the enrolment, S2P-4) has the capability and doesn't run the current PIN (`keyguard: own`, `pin_rev` null or
  another), nothing waits and the newest row isn't in flight (sent, or taken without an answer).
- **The group's notes** (`kid_lock::lock_card(policy, &PhoneLock, now)`; `PhoneLock` = the report, its two
  capabilities, a waiting row): Android's lock chosen and an older launcher - "Update Vibb One for Android's lock";
  chosen and the phone on Vibb One's - row "Android's lock · Waiting" / "Sending the PIN"; on Android's lock with
  `pin_rev: null` - "Phone's PIN isn't the Child PIN" (also an Attention action row, `Kind::ChildPinChanged`,
  `kid_lock::pin_changed_on_phone`); without the token - "Vibb One can't change Android's PIN"; Vibb One's chosen
  and the phone on Android's - row "Switching back…", or the warning "Android's PIN is still on" without the token or
  with `inactive: android_credential` (qa-59-revision N-C: own mode's "remove Android's lock" - and the lock-off alarm - whenever the phone
  runs Vibb One's lock, whichever lock is chosen: an Android PIN Vibb One didn't set leaves its lock off;
  qa-59-step2-code S2P-2); `pin_rev` another than the current - row "Child PIN on the phone · An earlier
  one"; relocks - "Locked again by Vibb One · N times"; "On the phone · Locked · Android's lock · 10:59". `lock_off`
  (the Attention alarm) reads the same notes. The newest `set_kid_pin` of this enrollment (`kid_lock::pin_send`, while
  Android's lock is chosen): "Child PIN to the phone · Waiting / Sent · 14:02 / Sent 14:02 · taken 14:03 / Set ·
  14:03 / Refused · 14:03" with the reason behind ⓘ (`kid_lock::PinAnswer`, also `locate::reword`'s words in the
  summary's Activity, where the command is "Child PIN to the phone").
- **Locate**: "locked (android pin)" reads "Locked with the Child PIN (fingerprint off until it's typed)"; with
  Android's lock chosen the ⓘ under the actions says Locate, Ring and Erase wait for the PIN after a restart.
  **Power**: a start's `unlocked_at_ms` more than 2 minutes after it adds "· unlocked 07:15" to the "turned on" /
  "restarted" event (`parent_events::UNLOCK_SAID_AFTER`).
- Tests: `kid_lock::tests` (`the_card_words_android_lock_case_by_case`, `the_pin_command_reads_as_its_state`,
  `send_again_is_offered_only_when_it_can_help`, `the_shown_pin_names_what_the_phone_has`),
  `src/tests/android_lock_page.rs`, `device_pages::lock_forms_return_to_the_page`, `admin_auth::tests::no_store_paths`,
  `parent_events::tests::the_power_words`, `locate::tests::the_phones_answers_are_reworded`.
