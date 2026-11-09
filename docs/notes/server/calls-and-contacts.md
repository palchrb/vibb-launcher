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
- **The policy** (`device_api::build_call_policy`): always `calls_mode`, `sms_mode`, the derived three, `sms_lock_text`,
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
- **The SMS settings** (`#sms-settings`, `POST /devices/{id}/calls/sms`, one form): "Vis meldingstekst på
  låseskjermen" (`sms_lock_text`) and "Telefonens nummer, SIM 1/2" (`device_own_numbers`, `phone::normalize` with the
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
  - **A live request** (H1) is `state = 'open' AND received_at > live_since(now)` (7 days), the window the card, the
    policy and the phone keep. Only a live request counts for `known` and the five open, is prefilled, answered by
    Add Contact (`from_request`, `mark_added`) and counted by the digest (`parent_events::waiting_count`). An older
    open row has lapsed and blocks nothing; retention removes it at 30 days.
  - **A family contact keeps its name** (M1): when the requested number is already in the family's address book,
    "Legg til…" prefills that contact's name, read-only, with "Finnes allerede som «Mamma» - navnet beholdes. Anna
    kalte det «…»"; and a save that carries a live `request` never renames an existing contact (`ON CONFLICT … SET
    name = contacts.name`) - the kid's words never reach the other phones. **A later photo follows the same rule:**
    from a request it never replaces an existing contact's photo.
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
