# Server notes: calls-and-contacts

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## Calls & SMS (handy step 2, design `docs/design/02-calls.md`, at the monorepo root)

- **`/devices/{id}/calls`** (`handlers::calls`, Calls & Contacts since design 31 step 8 - see that section below; linked from the device page's "Calls & Contacts" row): per-device switches `calls_managed`, `calls_enabled`, `sms_enabled` and `default_message_app` (one auto-submitting form, so a missing checkbox means off), the device's contacts with In/Out/Home flags and a Message button choice (`device_contacts.message_app`: none/sms/element/signal, NULL = device default; `message_address` = Matrix ID for Element), and Add Contact. The global default country code (`POST /settings/calls`, `call_settings` singleton) is on Settings › Phone Setup (`handlers::settings::save_country_code`). Every write runs in a transaction, answers 500 on a DB error, nudges the device (`command_notify`) and redirects back. Flag/photo/remove routes are scoped `WHERE device_id = ? AND contact_id = ?`: an unknown phone is a 404, a contact not on it (removed meanwhile) returns to the page's `#contacts` with nothing written; remove deletes an address-book entry no device has any more (so does deleting a device).
- **`contacts` is a global address book** keyed by the normalised number (`src/phone.rs`): adding a number another device already has reuses the row and renames it for every device (the page says so). Changing the default country code does not renormalise stored numbers.
- **Number normalisation is a shared contract with the launcher**: `src/phone.rs` and the launcher's `calls/PhoneNumbers.kt` run the same `testdata/phone_vectors.json` (the launcher keeps a byte-identical copy; each test suite compares the two when the sibling repo is checked out next to it). ASCII digits only; `*`/`#`/`,`/`;` rejected (MMI/USSD never allowlisted); 3-6 digits = short number, matched exactly; no suffix matching. Change both sides and both copies together.
- **`PolicyResponse.call_policy` is always sent, with an explicit `managed`** (QA blocker 3, `device_api::build_call_policy`): unmanaged devices get `{"managed": false, ...defaults, "contacts": []}`. The launcher rejects a whole response without the key once its calls were managed, so a rolled-back server can't silently unmanage calls - never make this key optional. Contacts are ordered by `sort_order, name`; `message_app` is resolved against the device default server-side.
- **Status reports carry `capabilities` (`["call_policy_v1"]`) and `call_state`** (the launcher's `CallState` object: role holders, restrictions, fail-closed state, last error, last emergency call, callback window), stored capped as JSON in `device_status.capabilities_json`/`call_state_json` - since 0.55.0 (security review L10) `call_state` through its known keys (`device_api::CALL_STATE_FIELDS`, every key the launcher's `CallState` sends: each of its shape or `null`, else dropped; `last_error` clipped to 300 characters, as the launcher keeps it, a package or time longer than its field dropped; any other key dropped; `intake_limits::call_and_time_state_keep_their_known_keys`). `calls::call_report` turns them into typed `calls::Finding`s (design 31 step 8; each one's `CallProblem` is an Attention row, its `note` the calls page's warning): launcher can't enforce calls (or stopped reporting it - an older launcher), dialer/call-redirection role missing, whether incoming calls are still filtered without the dialer role (`screening_role_held`, security review L7: the call-screening role's holder screens them; neither = "not filtered - anyone can call"), the call-screening role missing while the launcher is the phone app - a plain hint, not an error (`CallReport.screening_hint`, the "Call screening · Not granted" row: the launcher hangs up calls that aren't allowed, but another app could screen first - the phone can't read who holds the role -, so grant it with adb, the command behind the row's ⓘ; absent from older launchers = nothing; no "accept the prompt": Android refuses exclusive-role prompts under the launcher's DISALLOW_CONFIG_DEFAULT_APPS), fail-closed, a reported error, an unreadable call log (`call_log_readable: false`), the Messages app allowlisted while SMS is off, and an emergency call / an open callback window. The calls page keeps an emergency call as a warning for 7 days; its Attention row (`CallReport::problems`) goes once any parent has read the call's `emergency_call` inbox event (`parent_events::emergency_call_read`: the event's dedup key is the call's instant) and at the latest 24 h after the call (`EMERGENCY_ATTENTION_HOURS`; with no event the day alone).
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
  `<details>` confirm, M6), Add Contact as a disclosure at the end, then **Add from the family** (2026-11-03) while
  the address book has a contact this phone lacks: a `<select>` of "name · number" posting `contact_id` to the same
  `POST /devices/{id}/contacts` (`calls::add_family_contact`) - every flag on, the end of the order, its name, number
  and photo the contact's; back to `#contact-<id>`, one already on the phone to its row (`?open=`), one gone to
  `?notice=contact_gone#contacts` (both refusals, nothing written), an unknown phone 404. No country code here.
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

## The emergency menu (design 73, `docs/design/73-emergency-menu.md`)

- **Storage:** `device_emergency_numbers` (0114): a phone's numbers in order (`sort_order`), unique per phone and
  number, deleted with the phone. No rows = 112 alone (`emergency::device_menu`); the first write stores 112 first
  (`emergency::store_default`), then applies the change.
- **Policy:** `call_policy.emergency_menu: [{number, name}]` (`emergency::policy_menu`), always, managed or not, never
  empty (`policy_json_keys_snapshot`; the launcher's `PolicyEmergencyEntry`).
- **The fixed list:** `emergency::ALWAYS`/`BY_COUNTRY` = `testdata/emergency_numbers.json`, shared with the launcher
  (its `Emergency.staticSet`, which the phone ORs with the platform's own answer). `is_emergency` judges a normalised
  number; it decides the sub-lines and the removal rule only - the phone decides every call.
- **Device › Calls › Emergency Menu** (at the top of the page): a row per number (the number in bold, the name, a
  sub-line - "First and largest on the phone" for the first number on the fixed list, the phone's large red row;
  "Emergency number: always works"; orange "Not an emergency number to the server: follows the call rules (the phone may still know it as one)", plus
  "Not in the phone book - dimmed on the phone" on a managed phone for a number that isn't an outbound contact), ↑↓
  (`POST .../calls/emergency/{entry}/move`, back to `#emergency-<entry>`), Remove (`.../remove`, back to
  `#emergency`; not shown, and refused with `?notice=emergency_kept`, when it would leave no number of the fixed
  list), "Add Number…" (`POST .../calls/emergency`: normalised with the default country code, a name of at most 40
  characters, no duplicates, at most `emergency::MAX_ENTRIES` = 6; a refusal renders the page, 400, the form open;
  back to the new row), and "Suggestions" (`emergency::SUGGESTIONS`: 113 Ambulance and 110 Fire while the default
  country code is 47, each while it isn't on the list and the list has room; the name in the parent's language).
  A new default country code that leaves a menu without an emergency number puts 112 first
  (`emergency::repair_menus`, in `settings::save_country_code`'s transaction; a seventh number goes).
  A row gone (another tab) lands on `?notice=emergency_gone#emergency`. Tests: `tests::emergency_menu`, `emergency::tests`,
  and the page's form table in `tests::calls::every_form_on_the_calls_page_returns_to_its_place`.


## SMS in Vibb One, step 1: the server's side (design 77, `docs/design/77-sms-mms-client.md` §7)

- **Two channels, three states each (D2, migration 0130).** `device_policy.calls_mode`/`sms_mode` (`off` / `managed` /
  `unmanaged`, `models::CHANNEL_MODES`) replace `calls_managed`/`calls_enabled`/`sms_enabled`, which stay only for a
  rollback (the Calls page writes their derived values). The migration maps every phone to what it did (D2's table, the
  contract's `migration` rows; no phone goes to SMS `managed`); a phone created since starts as before 0130, both
  channels `unmanaged` (code QA M2: the setup QR installs the newest stable launcher, which may not have the engine, and
  an older launcher reads SMS `managed` as SMS off and calls `unmanaged` beside it as contacts-only). The parent picks
  "Styrt av Vibb" once the phone reports `sms_engine_v1` (the gate below). `DevicePolicy::managed()`/`calls_enabled()`/`sms_enabled()` are D2's derived old keys
  (`models::derived_old_keys`: `managed` while either channel isn't `unmanaged`, calls on unless `off`, SMS on only while
  `unmanaged` - fail closed for a launcher without `sms_engine_v1`); `calls_managed()` is the calls channel alone. The
  readers that mean the calls channel use `calls_managed()`: the call report's role/screening/call-log findings and the
  B1 "waiting" rule's early return (a launcher with the engine reports `call_state.state` for calls alone), "Dialer not
  allowed", the emergency menu's phone-book check, Attention's `calls_enforced` and "calls-off rule while calls aren't
  managed" (Attention, the summary, Device › Time). The ones that mean "the phone is managed" use `managed()`: the red
  "managed without a Parent PIN" card and the summary's contact count. "Messages hidden" now reads `sms_mode ==
  unmanaged` (restricted, the launcher suspends every SMS app but itself). `roles_changed_at` is stamped when either
  mode changes. **A phone with either channel not "Ikke styrt" is "managed" from its first sync** (code QA N6): the
  hardening switches, the update fence's night window, the backup lock and the red "managed without a Parent PIN" card
  all read `managed()` on the server and `CallPolicyStore.state.managed` on the phone - so picking SMS "Styrt av Vibb"
  on an otherwise unmanaged phone turns those on too.
- **The policy** (`device_api::build_call_policy`): always `calls_mode`, `sms_mode`, the derived three, `sms_lock_text`
  (since design 82 derived from Meldinger's lock level, true only when `lock_glance.sms` is `text`;
  `docs/notes/server/hardening-and-pins.md`, "The lock's glance page"),
  `sms_names` (`device_sms_names`, by fold), `own_numbers` (`device_own_numbers`, `[{slot, number}]`), `mmsc_hosts`
  (`mmsc_hosts.rs`, compiled in, empty until step 0) and `sms_held_drop` (`device_sms_held` ids with a `dropped_at` in
  the last 24 h, still `held`/`dropped`, newest first, at most 100) - also with both channels unmanaged (then no
  contacts, as before). Per contact `sms_in` (never for an emergency number, `emergency::is_emergency` - R2-H1),
  `sms_out`, `mms` (`device_contacts`, backfilled from the call flags, on for a new contact).
- **`sms_state`** in the status report (`device_status.sms_state_json`), through `sms::sanitize_state`: the flat keys by
  `SMS_STATE_FIELDS`, `blocked` counts by class, at most two `own_numbers`, `hold_full`. No content.
- **`POST /api/devices/sms-held`** (`sms::held`, 16 KiB, at most 50 rows): `{rows: [{id, at, kind, sender_class,
  sender, expires_at, state}]}`, instants epoch ms. Each row checked first (id 16-64 lowercase hex; kind; class; sender
  by class - `sms::sender_ok`, the contract's `intake` rows; `at` at most 5 min ahead, `expires_at - at` 0..24 h + 1
  min; state); one refused row answers 400 `{refused, reason}` and stores nothing. Forward-only (`held` ->
  `released`/`expired`/`dropped`, the rest final; a re-post never changes sender or time; any other change counted in
  `devices.sms_ignored_transitions`). At most 500 held rows a phone (`HELD_ROWS_A_PHONE`, every state but `blocked`)
  and, apart, 500 `blocked` rows (`BLOCKED_ROWS_A_PHONE`, code QA L5: a flood of blocked senders never hides a later
  held message): a new id past its bound isn't stored, `"capped": true`, one `sms_held_capped` line
  (`PhoneLine::SmsHeldCapped`, in the hourly budget). Retention drops a row 30 days after its `at`.
- **The names (D17)**: `sms::valid_name` (1-11 characters after `calendar::clean`, not cut, and a letter -
  `char::is_alphabetic`) and `sms::fold` (NFC, `to_lowercase`); `device_sms_names.fold` is unique per phone. Tests run
  the contract's `names` and `name_matches` rows.
- **The Calls page, the minimum until step 4**: the three switches became two rows, "Samtaler"/"SMS" ("Calls"/"SMS"),
  each a picker Off / Managed by Vibb / Not managed with D2's sub-line; the form posts `calls_mode` and `sms_mode` (an
  unknown or missing one is a 400). **The capability gate** (step 4's, brought forward by code QA M2): until the
  phone's last report claims `sms_engine_v1` (`sms::has_engine`), a pair an older launcher can't do
  (`sms::older_launcher_expresses`: anything but calls managed/off with SMS off/not managed, or both not managed) shows
  disabled, "<choice> - Oppdater Vibb One først", and a save of one is refused in place (the page again, 400, "Oppdater
  Vibb One først: telefonens versjon kan ikke dette ennå." under the rows; the stored pair is always taken back). With
  the capability SMS's choice reads "Styrt av Vibb (anbefalt)"; no phone is switched by itself. A stored pair the
  phone can't do (set up again with an older build) keeps the fail-closed derived keys and the calls report's finding
  "Telefonen må oppdateres" with what it costs (`Finding::NeedsUpdate`: "SMS går tapt og samtaler er bare til kontakter
  til da.", or the half that applies). Built in step 4 (the next section): the segmented control, the contact form's "Meldinger" group, the own-number fields, the lock-text switch, "Meldinger som venter",
  the blocked list, the sender-name card, the `sms_held` event with its push and `PushBudget`, the findings.
- Contract: `testdata/sms_contract.json`, byte-identical in the launcher's test resources (`sms::tests`). Tests:
  `sms::tests`, `tests::sms`.

## SMS in Vibb One, step 4: the PWA (design 77 §7, D2, D3, D12, D16, D17; `handlers::calls_sms`)

- **The two channel rows are a segmented control** (D2): three radios per channel (`calls_mode`/`sms_mode`, the same
  form and handler as step 1), styled as the phone switcher's segments (`.segmented`, `.segment`, `.segment-face`;
  sibling selectors only, no `:has()`), each saving on change (`data-autosave`, the `<noscript>` Save; a radio checked
  from the keyboard saves on Enter/Space or when the focus leaves the group, never on each arrow - code QA L6,
  `autosave.js` v=3). The chosen state's sub-line under it. SMS's RCS step (§8.5, code QA M1): before "Styrt av Vibb"
  is chosen ("Ikke styrt" or "Av" on a phone with the engine) "Før du velger Styrt av Vibb: slå av chatfunksjoner i
  Meldinger.", under it "Slå av chatfunksjoner i Meldinger først."; its ⓘ links Google's form
  (`calls::RCS_FORM_URL`, messages.google.com/disable-chat) for a phone whose Messages is already hidden. With
  `sms_engine_v1`, SMS's managed segment carries a quiet "anbefalt"; without it the capability gate is step 1's: a
  choice the phone can't do is a disabled radio, with "<choice> - Oppdater Vibb One først" under the row.
- **Each contact's "Meldinger" group** (D3): "Kan sende melding til {kid}" (`sms_in`), "Kan få melding fra {kid}"
  (`sms_out`), "Bilder i meldinger" (`mms`), in the contact's one form with the call switches (a missing one is off).
  For an emergency number (`emergency::is_emergency`) "Kan sende melding til" is disabled with "Nødnumre kan ikke sende
  meldinger hit - alle kan utgi seg for dem", and the save never stores it on (R2-H1). The row's summary gains
  "· Melding begge veier" / "· Melding inn" / "· Melding ut".
- **Add Contact shows every switch** (R2-M1), with `switches=on` in the form: all on for a new contact; "Opprett
  kontakt…" from a held message is a link `?add_number=<encoded>#add-contact` that opens it with the number (escaped
  by the template) and "Kan sende melding til" alone on. A post without `switches` (an older page) gets every flag on,
  as before. Never `sms_in` for an emergency number.
- **"Meldinger som venter"** (`#held`, at the top while it has rows; the section is always there so a redirect can
  land): `device_sms_held` rows but `blocked`, newest first, the last 7 days, at most 100, each `#held-<id>`: "Nummer
  {number}" / "Avsendernavn «{name}»", SMS/Bilde, when, and its state - "Venter til {time}", "Tillatt - leveres når
  telefonen henter reglene", "Levert", "Utløpt - meldingen er slettet", "Slettet" (a parent's drop too, before the
  phone confirms), or "Utløpt" for a row past `expires_at` by the server's clock (R2-L2). Actions only while it
  waits: "Opprett kontakt…" (a number not in the phone book), "Tillat meldinger fra {contact}" (a contact without
  `sms_in`), "Tillat avsendernavnet…" (behind a confirm with D17's warning), "Slett nå…" (behind a confirm, code QA
  L1); an emergency number or a name
  without a letter gets "Slett nå" alone with its reason. `POST /devices/{id}/calls/held/{held_id}/allow` decides from
  the stored row, never the form (a contact's `sms_in` alone, or the name added, at most 50); `…/drop` sets
  `dropped_at` (the policy's `sms_held_drop`, rows inside their window only - code QA L5); `…/held/drop-all` drops
  every row still inside its window ("Slett alle som venter…", behind a confirm with the count). Each marks the rows'
  `sms_held` events read for every parent (code QA L4). A row no longer waiting answers `?notice=held_gone#held`, a
  number without a contact `?notice=held_no_contact#held` (refusals, nothing written). "Blokkerte meldinger" (`#blocked`, a folded `details.section`): the `blocked` rows of 30 days, sender
  label (or what the phone couldn't read), kind, when.
- **The SMS settings** (`#sms-settings`, `POST /devices/{id}/calls/sms`, one form): until design 82 "Vis meldingstekst
  på låseskjermen" (`sms_lock_text`) - **gone since design 82 D8**: Meldinger's level is a row under Notifications on
  Lock Screen & PINs, and in the switch's place, only while `sms_mode` is `managed`, one link row "On the lock screen"
  / "På låseskjermen" with the level as its value (`SmsPage.lock_level`, `data-row="sms-lock-level"`) to
  `/devices/{id}/lock#lock-screen-app-me.vibb.builtin.messages`; the handler no longer reads `sms_lock_text`, so a
  stale page's post changes nothing, and the Avansert line's "Message text on the lock screen" is gone
  (`sms_page::the_sms_settings_save_in_place`) - and "Telefonens nummer, SIM 1/2" (`device_own_numbers`, `phone::normalize` with the
  default country code, a full `+` number of at most 16 characters - never a short code or an emergency number, code
  QA L7; empty removes it). A number that isn't one renders in place (400) with
  what was sent.
- **"Avsendernavn som slipper gjennom"** (`#sms-names`): the names by fold, Remove (`…/names/remove`, by fold), "Legg
  til navn…" (`…/names`: `sms::valid_name` - 1-11 characters after the shared cleaner, a letter -, unique by
  `sms::fold`, at most `calls_sms::NAMES_MAX` 50; a refusal renders in place, 400, with what was typed), and D17's
  warning "En falsk avsender med samme navn kommer også gjennom." under the card and in every allow's confirm.
- **The MMSC host table** stays `mmsc_hosts.rs`, compiled in and empty until step 0 (§7: not parent-editable in v1);
  with no row every MMS fails closed, and the phone's `mmsc_known: false` shows as the row "MMS kan ikke sjekkes på
  dette SIM-kortet".
- **The findings** (`calls::sms_findings`, from a launcher with the engine): while SMS is managed - "SMS-appen er
  ikke Vibb One - meldinger går tapt" (`role_held` false; not while a change is pending), "Ventelisten er full" with
  "Nye ukjente avsendere blir ikke holdt før {time}" (the earliest `expires_at` of the full pool's held rows), "Nummeret
  i Vibb stemmer ikke med SIM-kortet" (a SIM's reported number differs from the typed one): warnings and Attention rows;
  "Meldinger kunne ikke vises" (the count), "MMS kan ikke sjekkes på dette SIM-kortet", "Bilder i meldinger kommer ikke
  fram uten mobildata": rows in the settings group, not Attention (`CallProblem::needs_attention`). While SMS isn't
  managed: "Vibb One er fortsatt SMS-appen" (code QA L3), a row.
- **The device page's row** "Meldinger": "2 meldinger venter · 3 blokkert denne uken" (`calls_sms::summary_value`),
  under Calls & Contacts while either isn't nought, to `#held`.
- Every write is one transaction scoped to the phone, 500 on a database error, and nudges the phone (`calls::saved`:
  `command_notify`), so an allow reaches it with the policy within seconds while it is online. Tests: `tests::sms_page`
  (each allow path's defaults, the escaped prefill, Slett nå / Slett alle, the names' rule, the SMS settings, the
  Meldinger group, the push budget, the findings and the device-page row), `tests::sms` (the gate on the radios),
  `tests::shell` (every new form lands on its own group), `calls::tests` (the findings' words, the summary).

## The kid's contact request (design 77 §14, step 6; `handlers::contact_requests`)

A phone book and contacts feature, not an SMS one: the kid asks from the phone book for a new contact, and a
parent decides on the Calls page. It follows design r13's time request.
- **The intake** `POST /api/devices/contact-request` (`intake`, 1 KiB): `{id, name, number, matrix_id?, asked_at_ms}`,
  the shapes in `testdata/contact_request.json` (shared with the launcher). `check` (QA R2-L5): the name through
  `calendar::clean`, 1-40 characters; the number through `phone::normalize` again, never an emergency number
  (`emergency::is_emergency`); an empty Matrix ID none, any other one through `calls::valid_matrix_id`. Refused:
  `invalid`. Then in one `BEGIN IMMEDIATE` transaction: a stored id answers `accepted` again (a retry: no second row or
  event); the number already a contact on the phone, or in another open request, `known`; three received in the
  phone's own day (`device_api::phone_zone`, as r13's cap; counted by the server's `received_at`) or five open,
  `capped`; else the row and the event. An `asked_at` in the future counts as now; one over 7 days old is `invalid`.
- **The table** `device_contact_requests` (0133): `state` `open`, `declined` or `added`; purged 30 days after
  `received_at` (`retention::prune`, `Pruned::contact_requests`).
- **The policy** `call_policy.contact_requests`: `[{id, state}]` of the last 7 days, newest first, at most 50. An
  approval needs no row for the phone (the contact arrives), but `added` is sent too, so the phone forgets a request
  the parent added under another number (an addition to §14, which named `open` and `declined`).
- **The event** `contact_request` (`Kind::ContactRequest`), key the phone's id, words from its parameters ("Anna asks
  for a new contact: Ola" / "+4791234567 · 14:02"), target `/devices/{id}/calls#request-{id}`, its own push switch
  `push_contact_request` (on by default, 0133) and a `PushBudget` of 3 an hour (the day's cap keeps it under it but
  across midnight; the digest counts the open requests).
- **The card** "Forespørsler" ("Requests", `#requests`, each row `#request-<id>`) at the top of the Calls page, under
  "Meldinger som venter": the name and number the server checked, when, the number's mark ("Kortnummer - kan koste
  penger"; "Nødnummer" only for a row stored before the country code changed - the intake refuses them), the kid's
  Matrix ID as "Foreslått Matrix-ID", and for an open row "Legg til…" and "Avslå"; a decided row reads "Lagt til" or
  "Avslått" for the rest of its 7 days.
  - **"Legg til…"** is `?request=<id>#add-contact`: Add Contact opened from the stored row of this phone (`prefill`;
    never the link's words), with §14's switches (`REQUEST_ADD_SWITCHES`: "Kan ringe", "I telefonboken", texts both
    ways on; Home and pictures off) and, when the kid gave one, the Matrix ID behind its own unticked "Bruk
    Matrix-ID-en" (R2-M1). The form carries `request`; the save (`calls::add_contact`, its transaction) answers every
    open request for the saved number and that request `added` (`mark_added`), reads their events for every parent, and
    stores the request's Matrix ID as the contact's `message_address` only with `use_matrix` ticked - taken from the
    stored row of this phone, never the form. Any Add Contact (and "Add from the family") with the number answers an
    open request too.
  - **"Avslå"** (`decline`): `declined`, the event read for every parent, back to the row; a row no longer open lands
    on `?notice=request_gone#requests`.
- **Not built yet** (the user, 2026-11-09): the kid's side follows design 79 (the phone book as a list, with a photo
  the kid picks or takes), and the PWA's contact form design 80 (fewer switches, "Avansert"), which will restyle the
  card and the prefilled Add Contact. **A photo fits without breaking the API:** the intake ignores unknown keys, so
  the body can gain an optional `photo` (the SHA-256 of a picture the phone first uploads on a route of its own, e.g.
  `POST /api/devices/contact-request/photo`, re-encoded and size-checked like a contact photo), a nullable
  `device_contact_requests.photo_hash` column, and "Legg til…" offering it as the contact's photo behind its own
  switch, as the Matrix ID. An older server takes the request without it; the 1 KiB body limit is untouched.
- **The code QA's fix round** (`docs/review/qa-contact-request.md`):
  - **A live request** (H1) is `state = 'open' AND asked_at > live_since(now)` (7 days from the kid's ask: `asked_at`
    is clamped to the server's now and refused past 7 days at intake, so this is exactly when the phone drops its
    record), the window the card and the policy use too. Only a live request counts for `known` and the five open, is
    prefilled, answered by Add Contact (`from_request`, `mark_added`), declined and counted by the digest
    (`parent_events::waiting_count`). An older open row has lapsed and blocks nothing; its "Avslå" lands on
    `?notice=request_gone#requests` (never an anchor that isn't there); retention removes it 30 days after
    `received_at`. The day's cap counts by `received_at`, the server's clock.
  - **A family contact keeps its name** (M1): when the requested number is already in the family's address book,
    "Legg til…" prefills that contact's name with "Finnes allerede som «Mamma» - navnet beholdes. Anna kalte det «…»".
    The hint and the short-number warning follow the number in the field (`add_kept`, `add_short`), as the save judges
    it: a save carrying a live `request` with another typed name for an existing number is refused in place (400) with
    the kept name in the field and "fra en forespørsel beholdes navnet" - never silently ignored - and saving again
    adds it under the kept name; the transaction also never renames (`ON CONFLICT … SET name = contacts.name`). The
    kid's words never reach the other phones. **A later photo follows the same rule:** from a request it never replaces
    an existing contact's photo.
  - **One prefill source** (L1): a held number's link wins over `?request=`; a refused form carries its own `request`
    or none.
  - **A retry first** (L2): a stored id answers `accepted` before any check, so a changed country code or a retry past
    day 7 never answers `invalid` for a request the parent has.
  - **A short number** (L3) starts with "{kid} kan sende melding til kontakten" off and repeats "Kortnummer - kan
    koste penger" under the number field; §14's other defaults stay. Raised with the user against §14's defaults.
  - **"Bruk Matrix-ID-en" sets the Message button to Element X** (design 80 M6): ticked, the stored ID becomes the
    contact's `message_address` and `message_app = 'element'`; the switch's sub-line says so.
  - **The event's time** (N2) is when the server got the request (the inbox sorts and words it by that); the card
    keeps `asked_at`.
- Tests: `tests::contact_requests` (the contract's answers, the caps and limits, the card, "Legg til…" with and without
  the tick and a forged one, "Avslå", the push and its switch, retention; the fix round's lapsed requests, a family
  contact's kept name across two phones and Element X, the prefill and retry rules, decisions on their own phone and
  "Add from the family", escaping and the push budget's digest), `handlers::contact_requests::tests` (the shared
  vectors), `parent_events::tests` (the words and target), `i18n_pages` (the card and "Legg til…" in Norwegian).
- **A short number from a request** (the user, 2026-11-09, built with design 80 step 1; replaces QA L3's "texting
  off"): "Legg til…" for a short number starts with **every** switch off - calls and texts both ways, Home, pictures -
  and the line under the number field says the parent chooses: "Kortnummer - kan koste penger. Velg selv om barnet
  skal kunne ringe og sende melding til det." (`device.requests.short_choose`; it follows the number in the field, as
  before). The card keeps "Kortnummer - kan koste penger".

## A simpler Calls & Contacts page (design 80, `docs/design/80-calls-contacts-simpler.md`)

Steps 1 and 2: server and template only - no policy key, contract file, migration or launcher change.
- **A contact's four plain choices** (`handlers::calls_form`, D1-D4): "Kan ringe og ringes" (`calls`: `allow_inbound`
  + `allow_outbound`), "Kan sende og få meldinger" (`texts`: `sms_in` + `sms_out`), "Bilder i meldinger", "På
  Hjem-skjermen". A channel whose two directions differ is the one-line "Tilpasset" row (no switch, no sub-line, the
  second-label colour), and the contact's Avansert is drawn open. **`calls_form::both_ways`** (D3) resolves each
  channel from `calls_shown`/`texts_shown` (what the main row showed: `on`, `off`, `custom`), the two-way switch and
  the one-way pair (always posted - Avansert is in the same form): a changed two-way switch sets both; otherwise the
  pair as posted, except that a post leaving the switch alone with the pair exactly as shown implies keeps the
  **stored** pair (L1: a stale tab never undoes a split made meanwhile); `custom`, absent or any other value (an older
  page, N4) is the pair as posted. Without JS a post that changed both the switch and its pair takes the switch.
  `calls_form::resolve` applies it to a form; an emergency number's texts row is `sms_out` alone ("{kid} kan sende
  melding til {contact}") and `sms_in` is never stored (R2-H1). A refusal (a bad Matrix ID, Add Contact's name or
  number) is redrawn from the resolved pairs (L2).
- **Sub-lines by the channel modes** (`calls_form::subs`, D1, M2): the calls row while calls aren't "Styrt av Vibb"
  ("Samtaler er av - bare nødnumre kan ringes"; "Samtaler er ikke styrt - alle kan ringe. {contact} står (ikke) i
  telefonboken"; with both "Ikke styrt" "Gjelder når samtaler eller SMS er styrt av Vibb"), texts and pictures while SMS
  isn't ("Gjelder når SMS er styrt av Vibb"), pictures with no text either way, Home while `outbound` is off.
- **The contact's Avansert** (`details.adv`, D2): "{contact} kan ringe {kid}", "{kid} kan ringe {contact}" (sub "I
  telefonboken"), the two text directions (the incoming one disabled with why for an emergency number), the Message
  button (stacked), and the Matrix ID only while the button opens Element X (the contact's or the phone's default) or
  one is stored - "Mangler Matrix-ID" (orange) or "Brukes når knappen åpner Element X" under it. **A post without the
  field keeps the stored ID** (it isn't drawn while unused; an empty field removes it). Its row says what it hides:
  "Tilpasset", else the contact's own Message button, else "Matrix-ID lagret". Drawn open for a split, Element X
  without an ID, a refused Matrix ID, or `&more=<id>`.
- **The closed row** (`calls_form::contact_row`, D6, H1): "Anrop og meldinger" / "Bare anrop" / "Bare meldinger" /
  "Ingen anrop eller meldinger" while both channels are alike, else the calls part ("Anrop begge veier", "Anrop inn",
  "Anrop ut", "Ingen anrop") with the texts part on the second line; the extras "Hjem" and "uten bilder".
- **`more` and the returns** (D9): a hidden `more` in each contact form, kept in step with its Avansert by
  `static/contact-form.js` (`toggle`); a save with `more=1` returns to `?open=<id>&more=<id>#contact-<id>` and comes
  back with the Avansert open. Without JS `more` keeps its drawn value.
- **Add Contact** (D5): the same four choices and an Avansert with the one-way switches, "Kontakten"/"kontakten" in the
  words; posts `switches=on`, `calls_shown`/`texts_shown` and `more`. `static/contact-form.js` keeps it agreeing with
  itself (a two-way flip sets its pair; `*_shown` is the pair's class; the switch and "Tilpasset" swap by `hidden`,
  never removed - H2). The three ways in: plain (all on), "Opprett kontakt…" (calls off, texts "Tilpasset" with only
  "Kontakten kan sende melding til", Avansert open), "Legg til…" (§14's). **The phone's own number is refused** ("Det
  er telefonens eget nummer", L6): the stored own numbers and the SIMs' last reported ones
  (`calls_sms::phone_numbers`); "Legg til fra familien" leaves such a contact out, and a forged post of one is refused
  (`?notice=contact_gone`).
- **D11:** `auto-reload.js` (v6), `pull-refresh.js` (v3) and `reconnect.js` (v11) count only a shown open `<details>`
  (`shownOpen`: none of its `<details>` ancestors closed), so a contact's Avansert drawn open inside its closed row
  stops no reload, pull or reconnect.
- **The page's order** (D7): the warnings, "Meldinger som venter" (a blocked group row says "Mens telefonens nummer
  mangler, blokkeres alle gruppemeldinger" while SMS is managed and no number is known - M5), "Forespørsler", the
  channels (their own form, `section=channels`), Kontakter, then **Avansert** (`details.section#advanced`, D8).
  Under SMS: the RCS step before "Styrt av Vibb" as before, and for 14 days after `roles_changed_at` while managed
  ("Slå av chatfunksjoner i Meldinger hvis du ikke har gjort det.", its ⓘ leading with Google's form; M4), after that
  the row "Chatfunksjoner (RCS)" in Avansert; and the quiet line "Telefonens nummer mangler - gruppemeldinger
  blokkeres" (to `?advanced=1#sms-settings`) while SMS is managed and no number is known.
- **The page's Avansert** (D8): its row names the emergency menu ("Nødmeny: 112, 113"), in orange the count that
  follows the call rules, "Meldingsknapp: {app}" with " · {n} avsendernavn" and " · Meldingstekst på låseskjermen", and
  in orange the missing number. Opened: the emergency menu (design 73's routes), "Meldinger" (`#sms-settings`: the
  phone's default Message button - `/calls/settings` with `section=button`, `#message-button` -, the SMS form, the RCS
  row), the sender names. Every return there carries `?advanced=1` (also a notice's), which draws it open; a refusal
  inside it draws it open too.
- **`/calls/settings` split** (design 31 §5.6): `section=channels` writes only the modes, `section=button` only
  `default_message_app` (back to `?advanced=1#message-button`), no marker is the old write-all.
- **The phone's own number** (§4.5, `calls_sms::own_rows`): a row per active SIM in the last report
  (`calls_sms::reported_own`) and per stored slot. The SIM knows it and nothing is stored: "Telefonens nummer · {n}",
  "Fra SIM-kortet", **"Bruk dette nummeret"** (`POST /devices/{id}/calls/own-number/use`, `source=sim:<slot>`: stores
  the number of the phone's last report, never one from the form, by `save_sms`'s checks; a source the report no
  longer has lands on `?advanced=1&notice=own_number_gone`, nothing stored) and **"Endre…"** (a plain `<details>`
  holding the field with the stored number, empty for none - so a save of the switch never stores the SIM's number).
  Stored and agreeing (or the SIM doesn't know): "· Lagret" with "Endre…". Stored and the SIM says another: the field
  (and the mismatch finding, now "Sjekk Telefonens nummer under Avansert"). A stored slot for a SIM not in the phone:
  its field, "SIM 2 - ikke i telefonen nå" (L5). Nothing known: the field, "Telefonen vet ikke nummeret sitt ennå. Uten
  det blokkeres gruppemeldinger." "SIM 1"/"SIM 2" only on a phone reporting two or a stored slot 2. **`/calls/sms`
  writes only the slots it is sent** (an absent field keeps its slot, an empty one removes it). "Bruk dette nummeret" is a `<button form="own-use-<slot>">` of a small form after the SMS form, so Enter in
  a field never posts it.
- **Step 3, "Gruppemeldinger" and the learned number** (D13, §4.4; one commit with the launcher):
  `device_policy.sms_groups` (migration 0134, default on), sent as `call_policy.sms_groups` always
  (`policy_json_keys_snapshot`, `sms_contract.json`'s `policy` and `groups_off`). The switch sits in the SMS form
  (Avansert › Meldinger) with a hidden `groups_shown`: only a post with it writes `sms_groups` (an older page keeps
  it), and only for a phone whose last report claims `sms_groups_v1` (`sms::SMS_GROUPS_CAPABILITY`,
  `sms::has_capability`) - before, the switch is drawn disabled with "Oppdater Vibb One først". Off: Avansert's row adds
  "Gruppemeldinger av", a blocked group row says "Gruppemeldinger er slått av", and the missing-number lines are silent
  (nothing is lost for want of the number). `sms_state.own_learned` (`sms::sanitize_state`: `null`, or every number by
  `sender_ok` and a full `+` number, `at` >= 0, at most two in `conflict`; anything else drops the key) gives the
  learned rows of §4.5 on the first slot while nothing is stored and no SIM knows the number: "Lært fra en melding fra
  {contact}" (the teacher named by the contact, else the number) with "Bruk dette nummeret" (`source=learned`) and
  "Endre…"; a conflict is the field with "To meldinger viste ulike numre: {a} og {b}. Skriv inn det riktige." and "Bruk
  {number}" beside each (`source=conflict:<0|1>`). Those store the reported number into the first active slot with no
  stored number. A learned number counts for "number missing" only while it is in use, and is refused as a contact
  (`calls_sms::phone_numbers`). Tests: `tests::calls_simpler::the_group_switch_needs_the_capability_and_its_marker`,
  `the_learned_number_and_a_conflict`, `tests::sms` (the policy with `sms_groups` both ways), `sms::tests` (the report's
  `own_learned`, its conflict row and the hostile ones).
- **The code QA's fix round** (`docs/review/qa-80-code.md`; these replace what the bullets above say):
  - `groups_shown` is drawn only with an enabled switch (`sms_groups_v1` claimed), so a page drawn before the
    capability never turns groups off (M1).
  - **A learned number is no bar to a contact** (L1): `phone_numbers` (Add Contact's refusal, the family list) holds
    only the stored and the SIM-reported numbers - anyone can send the message that taught the phone. Added as a
    contact, it reaches the phone, which drops the learned number; the page no longer offers a learned number that
    is a contact.
  - **A stale SMS form never writes a slot it didn't change** (L3): every own-number field posts
    `own_number_<n>_shown` (what the page drew), and `save_sms` writes a slot only when its field differs; an older
    page without it is as before. The Avansert drawn open from `?advanced=1` carries `data-reopens`, which
    `auto-reload.js` (v7), `pull-refresh.js` (v4) and `reconnect.js` (v12) skip in `shownOpen` - it comes back open -
    so the page reloads in place again; a `<details>` opened inside it still blocks. Second pass (V1):
    `auto-reload.js` (v8) also waits while a text field has the focus or any field differs from what the page came
    with (reconnect's `edited` rule, its `typing`) and reloads once the edit is saved or undone, so a number typed into
    that Avansert during the 3 s refresh is never lost and the refresh never stops for good.
  - **One rule for "Bruk dette nummeret"** (L4): `usable_own` (`phone::normalize` leaves the number as it is, and a full
    `+` number that isn't short or an emergency number) filters every SIM and learned number in `reported_own`; the
    row is offered by it and the route stores only what it kept. A SIM reporting `113`, `90000009` or `+4712` reads as
    unknown. Second pass (V2): one helper, `learned_offer`, decides both whether the page offers the learned number
    and whether the tap stores it (nothing stored, no SIM reporting a number, not a contact); a stale tap lands on
    `?notice=own_number_gone` and stores nothing.
  - **Add Contact of an emergency number without JS** follows the two-way texts switch (`resolve` runs `both_ways` on
    `(sms_out, sms_out)`), and the refusal redraw judges the sent number (L5).
  - An unknown `section` on `/calls/settings` is a 400 (`error.unknown_section`), never the write-all (N2). No free
    slot for a learned or conflict number lands on `?notice=own_number_no_slot` with its own words (N4). The short-number
    line names the kid (N5). "Tilpasset" takes the switch's box as its minimum (`flex: 0 0 auto; min-width: 51px`, no
    gap of its own; second pass V3): at the default size its label wraps as beside the switch, so a save moves nothing;
    at a larger font the word takes room from the label and is never clipped (N3). Avansert › Meldinger has hairlines between its rows (`.group-lines`).
  - Tests: `tests::calls_simpler::a_page_drawn_before_the_capability_never_turns_groups_off`,
    `a_learned_number_can_be_added_as_a_contact`, `a_stale_sms_form_keeps_a_number_stored_meanwhile`,
    `bruk_dette_nummeret_only_for_a_number_the_tap_stores`, `an_emergency_number_added_without_js_follows_the_texts_switch`,
    `an_unknown_settings_section_is_refused`; `contact_requests::the_prefill_and_retry_rules` (every switch, the name);
    the reconnect jstest for `data-reopens`; second pass: `a_stale_learned_offer_stores_nothing` and the auto-reload
    jstest "a typed or focused field in an open data-reopens Avansert holds the reload until it is undone".
- Tests: `tests::calls_simpler` (D3 through the form, L1, H2, N4, `more`, L2, the Avansert row and the Matrix ID, the
  sub-lines, Add Contact's ways in and the own-number refusal, the split settings form, the own number by its source,
  the page's order and Avansert row, the RCS line's 14 days), `calls_form::tests` (`both_ways`' table, the emergency
  number, the sub-lines), `calls::tests::a_contact_row_says_which_way_calls_go` (rewritten for D6), the rewritten form
  table in `tests::calls::every_form_on_the_calls_page_returns_to_its_place`, `i18n_pages` (Avansert open, a held
  prefill, the seeded split Mormor), and the jstests `contact-form.test.js` and the D11 cases in `auto-reload`,
  `pull-refresh` and `reconnect`.
