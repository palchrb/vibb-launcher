# The phone setup status (design 114)

`docs/design/114-setup-status.md` is the spec; this note says where the server's parts are.

- **One table, one rule** (`src/setup_status.rs`): `STEPS` and `FEATURES` are `testdata/setup_steps.json`'s `steps` and
  `features` (byte-identical in the launcher's test resources; `setup_status::tests` runs every row and compares the
  copies). `inputs_of` and `readings_of` map the policy and the latest report to the rule's inputs and readings;
  `missing`/`count`/`setup_row` are the rule. The server reads `parent_pin` itself; the report's `setup` carries only
  `device_owner`, `mobile_data`, `location`, `data_saver`, `full_screen_intent`, `fingerprint` and the hold's
  `hold_until_ms`/`hold_left_ms` (`clean`); every other reading comes from the field that already carries it.
  `write_settings_used` is bit 16, bit 8 or a clock format in use (as `airplane_mode::write_settings_needed`).
- **Storage** (migration 0157): `device_status.setup_json`, `setup_hold_until_ms` (`min(hold_until_ms, arrival +
  hold_left_ms)`), `device_setup_ticks` (the parent steps ticked, `by_phone`).
- **The hold** (`models::DeviceStatus::paused_at`): a report with `setup_hold_until_ms` stays paused until it, never past
  `reported_at + SETUP_HOLD_MAX_MS` (4 h, `restrictions_pause.json`'s `setup_hold_max_ms`). `setup_status::hold_end`
  is the hold's end for the pages; Attention's `RestrictionsPaused` says "I oppsett, uten begrensninger" and leads to
  `/devices/{id}/setup#open` then; `Setup(n)` waits until the hold ends.
- **Routes**: `GET /devices/{id}/setup` and `POST /devices/{id}/setup/ticks` (`handlers::setup_page`; the PWA's ticks,
  logged by `settings_log`); `POST /api/devices/setup/ticks` (`setup_status::tick`, 256 bytes, the phone line
  `setup_tick_phone`); the policy's `setup: {ticked}`.
- **Warning lines**: `setup_status::page_warnings(lang, policy, latest, page)` gives a page's lines by section
  (`Warnings::at(anchor)`), rendered by `partials/setup_warning.html`; a step warns once per page, at the first of its
  sections whose choice is on. About's rows of a missing step link to it (`device_pages::Row::step_link`).
- **The Parent PIN before a code** (§5.8): `devices::parent_pin_set`; `provision_qr` and `regenerate_code` make no code
  without it; the PIN form carries `back=provision` and its save lands on `/devices/{id}/provision#qr`.
