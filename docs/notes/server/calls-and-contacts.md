# Server notes: calls-and-contacts

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Calls & SMS (handy step 2, design `docs/design/02-calls.md`, at the monorepo root)

- **`/devices/{id}/calls`** (`handlers::calls`, Calls & Contacts since design 31 step 8 - see that section below; linked from the device page's "Calls & Contacts" row): per-device switches `calls_managed`, `calls_enabled`, `sms_enabled` and `default_message_app` (one auto-submitting form, so a missing checkbox means off), the device's contacts with In/Out/Home flags and a Message button choice (`device_contacts.message_app`: none/sms/element/signal, NULL = device default; `message_address` = Matrix ID for Element), and Add Contact. The global default country code (`POST /settings/calls`, `call_settings` singleton) is on Settings › Phone Setup (`handlers::settings::save_country_code`). Every write runs in a transaction, answers 500 on a DB error, nudges the device (`command_notify`) and redirects back. Flag/photo/remove routes are scoped `WHERE device_id = ? AND contact_id = ?`: an unknown phone is a 404, a contact not on it (removed meanwhile) returns to the page's `#contacts` with nothing written; remove deletes an address-book entry no device has any more (so does deleting a device).
- **`contacts` is a global address book** keyed by the normalised number (`src/phone.rs`): adding a number another device already has reuses the row and renames it for every device (the page says so). Changing the default country code does not renormalise stored numbers.
- **Number normalisation is a shared contract with the launcher**: `src/phone.rs` and the launcher's `calls/PhoneNumbers.kt` run the same `testdata/phone_vectors.json` (the launcher keeps a byte-identical copy; each test suite compares the two when the sibling repo is checked out next to it). ASCII digits only; `*`/`#`/`,`/`;` rejected (MMI/USSD never allowlisted); 3-6 digits = short number, matched exactly; no suffix matching. Change both sides and both copies together.
- **`PolicyResponse.call_policy` is always sent, with an explicit `managed`** (QA blocker 3, `device_api::build_call_policy`): unmanaged devices get `{"managed": false, ...defaults, "contacts": []}`. The launcher rejects a whole response without the key once its calls were managed, so a rolled-back server can't silently unmanage calls - never make this key optional. Contacts are ordered by `sort_order, name`; `message_app` is resolved against the device default server-side.
- **Status reports carry `capabilities` (`["call_policy_v1"]`) and `call_state`** (the launcher's `CallState` object: role holders, restrictions, fail-closed state, last error, last emergency call, callback window), stored capped as JSON in `device_status.capabilities_json`/`call_state_json`. `calls::call_report` turns them into typed `calls::Finding`s (design 31 step 8; each one's `CallProblem` is an Attention row, its `note` the calls page's warning): launcher can't enforce calls (or stopped reporting it - an older launcher), dialer/call-redirection role missing, whether incoming calls are still filtered without the dialer role (`screening_role_held`, security review L7: the call-screening role's holder screens them; neither = "not filtered - anyone can call"), the call-screening role missing while the launcher is the phone app - a plain hint, not an error (`CallReport.screening_hint`, the "Call screening · Not granted" row: the launcher hangs up calls that aren't allowed, but another app could screen first - the phone can't read who holds the role -, so grant it with adb, the command behind the row's ⓘ; absent from older launchers = nothing; no "accept the prompt": Android refuses exclusive-role prompts under the launcher's DISALLOW_CONFIG_DEFAULT_APPS), fail-closed, a reported error, an unreadable call log (`call_log_readable: false`), the Messages app allowlisted while SMS is off, and for 7 days an emergency call / an open callback window.
- Emergency numbers are never configured here: the launcher always allows them. The override PIN and "pause" don't open calls beyond the call rules (decision after QA review); since step 6 they do lift a time rule's call block (school), back to the normal call rules.

## Emulator fixes (handy step 9 part B, design `docs/design/09-sms-and-fixes.md`, at the monorepo root)

Part A (SMS allowlist) is **postponed (user, 2026-10-05)**: `sms_enabled` stays the on/off switch.
- **Migration `0029_step9_fixes.sql`**: `device_policy.roles_changed_at` (stamped by `calls::save_settings` only when `calls_managed` changes), `device_policy.block_activity_start` (default 1), `device_status.play_store_suspendable`.
- **Pending rule** (B1, QA 09 #5, pure `calls::role_report`): a latest report older than `roles_changed_at` shows "Waiting for the phone to confirm" (the last report's local time and age behind its ⓘ) instead of the role/call-log/fail-closed warnings **only while the change is < 5 minutes old** (the calls page reloads every 3 s for 2 minutes, `static/auto-reload.js`); later the real warnings plus "Calls change not confirmed yet"; a report newer than the change is always taken as is (a disagreeing one is a warning, never "waiting"). `call_report` returns the findings + `auto_refresh`.
- **Kiosk app block** (B4): `PolicyResponse.block_activity_start` (always sent) - the launcher ORs `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` in itself together with the system helpers it pins; `lock_task_features` is always sent with bit 64 masked out, so a launcher without the helpers never gets it. Switch "Block other apps' screens" on Advanced › App screens (the device page's "Play and kiosk" card until design 31 step 3; `POST /devices/{id}/kiosk-block`, missing checkbox = off, 404 unknown device, nudges, back to `/devices/{id}/advanced#app-screens`, where scroll-restore keeps the position). The group warns when the phone reports `play_store_suspendable: false` (Play is then blocked only in kiosk, or not at all with the block off).
- Fix round (qa-09-code.md): the calls page never auto-refreshes a refusal's render (it would lose the typed contact); `valid_matrix_id` follows the Matrix grammar (localpart `a-z0-9._=-/+`, server host/IPv4/`[IPv6]` + optional port) like the launcher's `isMatrixId`, which percent-encodes the id into Element X's URIs. A launcher on a server without this key treats the kiosk app block as off.
- Tests: `src/tests/step9.rs`, `calls::tests::role_report_pending_expires`; `policy_json_keys_snapshot` has `block_activity_start`.

## Calls & Contacts (design 31 step 8, `docs/design/31-pwa-redesign.md` §3.2, §8)

- **The page** (`GET /devices/{id}/calls`, `handlers::calls::show_calls`, `device_calls.html`; the device page's row
  "Calls & Contacts · 8 contacts" / "Not managed" / "Couldn't read" replaced the Calls & SMS card): the call report's
  warnings above the groups (`partials/note_warning.html`: the problem's title, a sentence, an expert detail - an adb
  command with the real package, a package name, the phone's own error - behind the ⓘ only); **Calls and SMS** (one
  form, H1: Manage calls and SMS, Calls, SMS, Message button; "waiting" and "Call screening · Not granted" as rows in
  it; the emergency, RCS, voicemail and spoofing facts behind the footer's ⓘ); **Contacts** (one row per contact: the
  photo or the initial, the name, the number, "Both ways · Home"; the row opens its form - Can call, In the phone book,
  On the home screen, Message button, Matrix ID, one form (§5.6); New photo / Remove Photo; **Remove Contact…** as a
  `<details>` confirm, M6), Add Contact as a disclosure at the end. No country code here.
- **Where the forms return** (§5.1, `tests::calls::every_form_on_the_calls_page_returns_to_its_place`, built from the
  rendered forms): `/calls/settings` → `#settings` (scroll-restore keeps the place);
  a contact's save, photo and photo removal → `?open=<id>#contact-<id>` (its row, open again, with or without JS);
  Add Contact → `#contact-<new>`; Remove Contact… → `#contacts`, also for a second tap. A contact that isn't on the
  phone (another tab removed it) → `?notice=contact_gone#contacts` ("nothing was saved", at the top of the group); an
  unknown phone 404. **Refusals render the page (400)** with that form open, what was typed and the reason under the
  field (`aria-invalid`, `autofocus`): Add Contact's name or number, a contact's Matrix ID (the switches and picker as
  sent), a photo. Every read the page needs is a 500 on a DB error.
- **Typed (M5)**: `calls::CallReport { findings, screening_hint, auto_refresh }`; `Finding` (with its times and the
  phone's strings) → `problem()` (`CallProblem`, the Attention row) and `note(zone, now)` (local times, report times
  with their age; no package name, adb or raw code in the visible text, §2.2b). The old sentences, `warnings` and
  `hints` are gone; the device page reads only `problems()`.
- **The reload** while the phone confirms a change is `static/auto-reload.js` (was a `<meta refresh>`, which could
  cancel a save on its way or lose a typed contact): every 3 s, never once a save started, never while a `<details>` is
  open, never on a refusal's page. It keeps the place (qa-31-s8-code M1): before reloading it sets
  `history.scrollRestoration = "auto"` (scroll-restore leaves "manual" on the entry after a save) and writes
  scroll-restore's own record (`handy-scroll`), so the reloaded page restores the position. The Waiting row says
  "Reload the page in a minute." (true with and without JS).
- **A contact row's "· Home"** shows only for a phone-book contact (`outbound && show_on_home`, as the launcher's
  `CallRules.kt` puts it on Home). Every form for a contact that is gone (a save, a refused Matrix ID, a photo, a photo
  removal) lands on `?notice=contact_gone#contacts`; only Remove Contact… says nothing (it is done). The device page's
  row says "Couldn't read" when the call report can't be read (its Attention rows would be missing).
- **Phone Setup's second form** (L6): the default country code (`POST /settings/calls`, `#country-code`, Phone
  Numbers group) → `/settings/provisioning?saved=country-code#country-code` (the flash in its group); 1-3 digits, a
  leading + dropped; a refusal renders Phone Setup (400) with what was typed under the field; an unreadable code is a
  500 on the page. Every phone is nudged. No `back` field: one form posts there (an older page's `device_id` lands on
  Phone Setup too).
- Tests: `src/tests/calls.rs` (the redirect table, refusals in place, gone contacts, the confirm, escaping, the
  warnings and the screening row, local times, the country code), `calls::tests` (every finding's words),
  `settings_pages` (Phone Setup's table), repointed `launcher_ui.rs`, `step9.rs`, `step10.rs`, `catalog_pins.rs`,
  `cleanup.rs`, `attention.rs`.
