# The phone setup status (design 114)

`docs/design/114-setup-status.md` is the spec; this note says where the server's parts are.

- **One table, one rule** (`src/setup_status.rs`): `STEPS` and `FEATURES` are `testdata/setup_steps.json`'s `steps` and
  `features` (byte-identical in the launcher's test resources; `setup_status::tests` runs every row and compares the
  copies). `inputs_of` and `readings_of` map the policy, the latest report and the integrations' part
  (`Integrations::setup`: design 110's switch as the `map_links` input and rows 15-16's readings from its own report,
  114 §2.6; `setup_steps.json`'s `map_readings` pins those readings against the launcher's) to the rule's inputs and
  readings;
  `missing`/`count`/`setup_row` are the rule. The server reads `parent_pin` itself; the report's `setup` carries only
  `device_owner`, `child_pin` (114b), `mobile_data`, `location`, `data_saver`, `full_screen_intent`, `fingerprint`,
  `location_private` (design 125, below) and the hold's
  `hold_until_ms`/`hold_left_ms` (`clean`); every other reading comes from the field that already carries it.
  `write_settings_used` is bit 16, bit 8 or a clock format in use (as `airplane_mode::write_settings_needed`).
- **Design 114b** (`docs/design/114b-setup-pin-and-roles.md`):
  - `child_pin` (required, counted, read from the report's `setup.child_pin`). On Device › Oppsett it is an orange link
    to `/devices/{id}/lock#child-pin` (`Row::pwa_link`), and it never makes the page "openable" (`on_phone`).
  - Lock Screen & PINs gets one orange note at `#child-pin` while the step is missing and no Child PIN is stored
    (`child_pin_missing`).
  - `call_filter_early` (optional, `When::CallsUnmanaged`) reads `call_state`'s two roles, as `call_filter` does.
- **`location_private`** (2026-11-22, design 125: Google Location Accuracy off, so Vibb learns its Wi-Fi places itself):
  optional, `When::LocationInterval` (`location_mode` `interval`, the input `location_interval`), read from the
  report's `setup.location_private`. A quiet row under Valgfritt with its `setup.why.location_private` behind the ⓘ;
  never counted, never a line elsewhere.
- **Google's four switches** (design 126 B1-B4: `number_verification`, `ad_id`, `diagnostics`, `play_protect_upload`,
  after `location_private`): optional `parent` steps, `When::Always` - Play services' own state, which no public API
  reads. Quiet rows under Valgfritt with a switch that ticks them, `setup.why.<key>` then `setup.how.<key>` behind the
  ⓘ; never counted, never orange, nothing on any other page. They ride in the existing `setup.ticked` and the tick
  routes; the contract's `needed` rows list them as missing unless ticked, and one row ticks all four.
- **Storage** (migration 0157): `device_status.setup_json`, `setup_hold_until_ms` (`min(hold_until_ms, arrival +
  hold_left_ms)`), `device_setup_ticks` (the parent steps ticked, `by_phone`).
- **The hold** (`models::DeviceStatus::paused_at`): a report with `setup_hold_until_ms` stays paused until it, never past
  `reported_at + SETUP_HOLD_MAX_MS` (4 h, `restrictions_pause.json`'s `setup_hold_max_ms`). `setup_status::hold_end`
  is the hold's end for the pages; Attention's `RestrictionsPaused` says "I oppsett, uten begrensninger" and leads to
  `/devices/{id}/setup#open` then; `Setup(n)` waits until the hold ends.
- **Routes**: `GET /devices/{id}/setup` and `POST /devices/{id}/setup/ticks` (`handlers::setup_page`; the PWA's ticks,
  logged by `settings_log`); `POST /api/devices/setup/ticks` (`setup_status::tick`, 256 bytes, the phone line
  `setup_tick_phone`); the policy's `setup: {ticked}`.
- **Warning lines**: `setup_status::page_warnings(lang, policy, latest, part, page)` gives a page's lines by section
  (`Warnings::at(anchor)`), rendered by `partials/setup_warning.html`; a step warns once per page, at the first of its
  sections whose choice is on. The Apps page hands its lines to the integrations' groups: the map links' group shows
  `#map-links`'s (rows 15-16) in place of its own step lines. About's rows of a missing step link to it (`device_pages::Row::step_link`).
- **The Parent PIN before a code** (§5.8): `devices::parent_pin_set`; `provision_qr` and `regenerate_code` make no code
  without it; the PIN form carries `back=provision` and its save lands on `/devices/{id}/provision#qr`.
