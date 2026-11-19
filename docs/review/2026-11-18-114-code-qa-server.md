# Design 114 - code QA of the server half (read-only)

Branch `design-114-build` vs `main` (merge base d14cace65), `server/` only, the two shared contract files excluded.
Read against design 114 rev. 3/3b, `docs/review/113-114-design-qa.md`, `114-r3-design-qa.md`, root and server
CLAUDE.md ("Words in the UI", fail closed, a save never jumps). Nothing built or run (CI green).

**Counts: H 0 · M 3 · L 7.**

---

## M1 - Warning lines show at choices the parent turned off (app push, the notification filter, Wi-Fi sign-in, Locate)

`server/src/setup_status.rs:1035` (`warnings`: `.or_else(|| here.first())`) and `:1072-1080` (`feature_on`: `_ => true`).

A step's line goes to the first of its features on the page that is on, and **when none is on, to the first one
anyway**. `feature_on` only knows touch/clock_format/airplane/calls/map_links; every other feature reads as on. For
the `when: always` steps nothing else gates the line, so the line shows at a choice that is off:

- **Apps › `#app-push`** (`partials/app_push_group.html:7`): `background` ("Bakgrunnstilkobling er av") and
  `data_saver` ("Datasparing stopper Vibb på mobildata") show with app push **off**. The group's own lines
  (`app_push.missing`) are already gated on `on && origin_set` (`handlers/app_push.rs:541`); these aren't.
- **Advanced › `#updates`** (feature `notification_filter`): "Varseltilgang er av" shows with Fjern varsler **off**.
  This is a regression: the note it replaces, `EscapeNote::FilterWithoutAccess`, was raised only when
  `notification_auto_cancel` was on (removed at `kiosk_escapes.rs` ~l. 456). Phones created before 2026-10-21 have the
  filter off by default.
- **Sign-in › `#logins-wifi`** (`wifi_signin`): "Vibb One mangler posisjonstilgang" with Wi-Fi sign-in off
  (`captive_portal_enabled` false).
- **Locate › `#location`** (`locate`): the same line with Locate Off (`location_mode == "off"`).

That breaks "a choice the parent made is never a warning", and design §5.4's own sentence ("The lines are said only for a
choice that is on ... a feature the parent turned off raises nothing"). The design assumes `when` covers it, which is
true only for the conditional steps. Nothing in the design argues for warning at an off choice. The steps are still
counted in `Setup(n)` and listed on Device › Oppsett and About, so dropping these lines hides nothing.

**Fix:**
- In `warnings`, drop the `.or_else(|| here.first())` fallback, so a step whose features on this page are all off says nothing there.
- Make `feature_on` read the real switches. Pass a small `Choices` built by `page_warnings`, or the `DevicePolicy`
  plus the app-push switch:
  - `notification_filter` → `policy.notification_auto_cancel`;
  - `wifi_signin` → `policy.captive_portal_enabled`;
  - `locate` → `policy.location_mode != "off"`;
  - `app_push` → the phone's app-push switch (`unified_push::switch`, as `AppPushGroup.on`).
  The Apps page already holds `app_push.on`, so the minimal fix there is `{% if app_push.on %}` around the loop in
  `app_push_group.html`, but the rule belongs in `setup_status`.
- `badges`, `glance` and `music_card` have no switch in the policy, so they stay on.
- Tests: for each gated feature, render its page with the step missing and the choice off. Expect no
  `data-setup-warning`.

## M2 - The summary's "Oppsett" row says "Fullført" for a phone not set up, or before its first report

`server/src/handlers/devices.rs:2626-2645` (`setup_value`) and `templates/device_detail.html:568`.

The Sections list renders for an un-enrolled phone too, by design ("Every sub-page answers for a phone not set up
too"). Without a report, `readings_of` holds only `parent_pin`, which is `own_row` and never counted. So `count == 0`
and the row reads **"Oppsett · Fullført"** / "Setup · Complete" on every new phone, and on an enrolled phone until its
first report. Device › Oppsett says "Venter på telefonen" for the same state, so the summary claims a state nothing
has read.

**Fix:** have `setup_value` return `Option<String>`, `None` (or `setup.no_report`) when `latest` is `None`. Render it as
`{% if let Some(value) = setup_value %}`, as `about_value` already is. Test: an un-enrolled phone's summary has no
"Complete" on `data-row="setup"`.

## M3 - The optional steps' ⓘ sits alone on a row of its own under each switch

`server/templates/partials/setup_tick_row.html:9-12`.

Each parent step is two rows: the switch row, then a `<details class="info cell">` whose summary is a whole
`.row.row-note` holding only the icon, left-aligned (`details.info > summary.row .info-icon { margin-left }`). On every
phone of the user's (all five parent steps are optional and unticked), "Valgfritt" becomes ten rows, half of them an
empty row with a lone ⓘ and a hairline between them. It reads as a layout fault and doubles the group's height. The
"Avkrysset" sheet repeats the same pattern.

**Fix:** use the existing pattern for an ⓘ beside a switch: `.on-home` from `partials/app_display_form.html` and
`style.css` ~l. 2800. That puts the summary absolutely positioned just left of the switch, in the same row, with the
panel opening under the row. Or put the ⓘ inside the label row before the switch. One row per step either way.
Include it in gate row 16's screenshots.

---

## L1 - An easy Parent PIN saved from Connect loses its warning

`server/src/handlers/parent_pin.rs:172`. With `back=provision`, every successful save goes to `/provision#qr` without
a notice, including `parent_pin_saved_easy`. An un-enrolled phone has no Attention rows, so the parent who types
123456 on the way to the QR is told nothing until `EasyParentPin` appears after enrolment.

**Fix:** when `easy`, keep today's landing: `lock?back=provision&notice=parent_pin_saved_easy#parent-pin`, where the
form is open, `back` is kept, and the next save goes on to the QR. Or carry the notice to `#qr`.

## L2 - The PIN can still be removed while a live setup code exists

The gate is only at code creation: `provision_qr`, `regenerate_code`. A parent who makes the QR, then removes the
Parent PIN on Lock Screen & PINs (allowed while no Child PIN is set), then scans, enrols a phone without a PIN. That
is the lockout §5.8 closes. It is unlikely, but it is the exact path the user asked to close.

**Fix:** for an un-enrolled phone, `clear_pin` also voids a live enrollment code (`enrollment_code_hash = NULL`).
Alternatively, refuse `clear_pin` while one is live, with a notice.

## L3 - Design 107's per-channel consequences are no longer said anywhere

`device_pages.rs:620` (deviation 4). About's Notifications row becomes a link and `step_link` clears its `detail`.
That follows §5.5, so the deviation is sound. But Device › Oppsett's `vibb_notifications` row has `why: None`, so 107's
effect sentences (what turning off Meldinger or Tapte anrop costs) are now said nowhere while the step is missing.

**Fix:** in `setup_status::row`, set `why` for `vibb_notifications` from `notification_channels::about(..).detail`.
That says it once, on Oppsett.

## L4 - A pre-114 launcher's stale `default_apps_changed` counts until the launcher updates

`setup_status.rs:686-689`. The server reads `default_apps` as `default_apps_changed.is_empty()`. Design §2.2.1 drops
`call_screening` and `call_redirection` from row 14, and the phone filters them (`AssistantSetup.kt:155`). A phone on
the older launcher still reports its never-cleared list (QA H1's case), so the server deploy alone shows
"Oppsett: 1 steg igjen" and lists Anropsfilter/Viderekobling twice, once there and once under `call_filter`.

**Fix:** filter those two roles out on the server too, in `readings_of` and in the row's value. It is the same rule,
not a compat branch.

## L5 - Copy says things twice

- `setup.why.assistant` "Velg Vibb One - trengs for Flymodus" is followed in the same ⓘ by `setup.how.assistant`
  "Velg Vibb One, og trykk OK." Make the why "Trengs for Flymodus." / "Needed for airplane mode."
- `security.event.setup_tick_phone` "Oppsettsteg avkrysset på telefonen" has the detail "telefon {device}: {step}
  avkrysset på telefonen". Make the detail "telefon {device}: {step}" (nb and en).

## L6 - Ticks outlive a factory reset

`device_setup_ticks` is keyed by the device row. A recovery re-enrolment after a reset (same row) keeps "Vibrasjon",
"Klokke på én linje" and the rest ticked, though Android's settings went back to their defaults. The steps are
optional and the parent can untick them, so this is low.

**Fix:** clear the phone's ticks in the enroll handler when the phone is enrolled again with a new code. The phone
already clears its pending set (§4.4).

## L7 - An empty `.group` in "På telefonen" when every row there is an alarm row

`templates/device_setup.html` (the `#phone` section). The alarm rows (`parent_pin`, `device_owner`) render before the
`<div class="group">`, and the group renders even when no non-alarm row is left. An un-enrolled phone without a PIN
shows `#phone` with only Foreldre-PIN, then a 0-height group that adds a 20 px gap.

**Fix:** render the group only if `page.phone` has a non-alarm row. A small `Page::phone_rows()` helper does it.

---

## Checked and sound (no finding)

- **Migration 0157:** the two `ALTER TABLE ... ADD COLUMN`s are metadata-only on SQLite, so they are fast on a large
  `device_status`. The new table has `ON DELETE CASCADE`, `foreign_keys(true)` is on (`main.rs:1976`), and the primary
  key covers its reads. It is the next free number, nothing on `main` collides, and `map-links` is at 0159. The dead
  columns (`setup_card_hidden`, and `clock_format_applied` left out of the struct) are harmless with `FromRow`.
  server/CLAUDE.md is bumped to 0158.
- **The report's `setup`:** cleaned to the six readings (bool or null) plus the two hold fields as non-negative
  integers. Unknown keys and types are dropped, a non-object becomes NULL, and a bad value never refuses the report.
  `hold_until` takes the earlier of the two clocks with `saturating_add`. It is a full-report field, and the phone's
  hash covers `hold_left_ms` (`SetupReadings.kt`), so no heartbeat expands a stale `hold_left_ms`.
- **`POST /api/devices/setup/ticks`:**
  - It sits behind `require_device_token` with a 256 B limit (413 is tested).
  - Only `parent` steps are taken. Anything else, or a body that doesn't parse, is a 400.
  - `INSERT OR IGNORE` makes a replay a 204 that writes nothing. The log line and the nudge come only for a new tick.
  - A phone can only tick optional steps, so no tick hides a required one (the `the_steps_are_the_contracts` assert).
- **Deviation: a security-log line instead of `settings_log`.** Sound. `settings_log` is the admin middleware with the
  parent as actor and route-only entries. A phone's act belongs in `phone_lines!` (as `TimeLiftPhone`), with the phone
  retention and the hourly budget. The PWA's own tick and untick are still logged by `settings_log`.
- **`paused_at` and the hold:**
  - A held report stays paused until `min(until, reported_at + 4 h)`. Without the hold, the 2-hour rule applies as before.
  - `in_hold` and `hold_end` go through `paused_at`, so the summary's row, `#open`, `setup_value` and the quick sheet's
    end agree. The failure modes are in the safe direction:
    - a silent phone that rebooted shows as open until the hold's end, never longer than the phone itself could be open;
    - a missed hold can only come from a report without `setup` (a stale launcher), which §2.4 accepts.
- **`Setup(n)` and `NotDeviceOwner`:**
  - `Setup(n)` is held back in the hold and for a phone that isn't device owner, and placed first among the Action rows.
  - `NotDeviceOwner` is placed after `EasyParentPin`.
  - `MobileDataOff`, `NoNotificationAccess`, `NotificationsOff` and `Calls(NoRedirection)` are gone.
  - The role-lost line is gone.
  - No double row: Mobilnett's 113 `data-off` line, Home's two notes, the Flymodus orange sub-line, the glance notes,
    `FilterWithoutAccess`, `NoRedirection` and the screening hint were each replaced by exactly one setup line.
- **The Parent PIN gate:**
  - The only code makers are `enrollment::new_code`'s two callers (`provision_qr`, `regenerate_code`). Both refuse
    without a PIN, and Add Phone makes none.
  - The GET fails closed on a read error.
  - The summary, Advanced › Connection and Connect each show the one line in place of New Setup Code.
  - `?back=provision` opens the PIN form (`parent_open`) and is kept on a refusal. A save lands on `/provision#qr`.
  - No self-lockout from the gate itself; L2 is the remaining hole.
- **Device › Oppsett and "Lås igjen":** `end-windows`/`start-pause` with `back=setup` land on
  `/setup?notice=setup_*#open`, and `open_shown()` keeps `#open` for the flash. The forms carry their fragment, so
  scroll-restore and swap keep the place. A tick lands on `#step-<key>`.
- **Deviation: one line per page, in the first section whose choice is on.** Sound. It is QA M1's "a step warns once
  per page", and it picks `#quick-controls` or `#layout` by what is actually used, with the M1 fix above.
- **The adb texts:** the five keys named in §5.7, `LAUNCHER_FOR_ADB`, `device.calls.screening_sub` and the glance
  notes' pointers to Home are gone. What `adb` is left in i18n (`devices.qr.info` for GrapheneOS,
  `advanced.spotify_diag.info`, the lock notes' `*_fix`, `calls_cant_ring_fix` for VoIP apps) is outside this design
  and refers to nothing removed.

---

## Resolved (2026-11-18, on `design-114-build`)

| Finding | Resolution |
|---|---|
| M1 | RESOLVED. `setup_status::warnings` has no fallback any more: a step whose features on the page are all off says nothing there. `feature_on` reads `Choices`: `notification_filter` (`notification_auto_cancel`), `wifi_signin` (`captive_portal_enabled`), `locate` (`location_mode != "off"`) and `app_push` (the phone's switch, through `page_warnings_app_push` on the Apps page). `badges`, `glance` and `music_card` stay on. Test: `tests::setup_status::no_line_at_a_choice_that_is_off`. |
| M2 | RESOLVED. `setup_value` returns `None` before the first report, and the summary's row shows no value then. Test: `the_setup_row_says_nothing_before_a_report`. |
| M3 | RESOLVED. `partials/setup_tick_row.html` puts the ⓘ left of the switch with the `.on-home` pattern, its panel opening under the row. Each step is one row. |
| L1 | RESOLVED. An easy PIN saved from Connect stays on `lock?back=provision&notice=parent_pin_saved_easy#parent-pin`. Tested in `no_setup_code_without_the_parent_pin`. |
| L2 | RESOLVED. `clear_pin` voids a live setup code (hash, kind and expiry to NULL, then the cache refreshed). Tested in the same test. |
| L3 | RESOLVED. The `vibb_notifications` row's ⓘ starts with design 107's effect sentences (`notification_channels::effects`). |
| L4 | NO CHANGE (older launchers only). Since round 114 the phone drops `call_screening`/`call_redirection` from its live list (`AssistantSetup.defaultAppsChanged`), so a stale list comes only from a pre-114 launcher. The user's rule of 2026-11-18 is no backwards compatibility on the phones yet. |
| L5 | RESOLVED. `setup.why.assistant` is "Trengs for Flymodus." / "Needed for airplane mode.". The phone tick's detail is "telefon {device}: {step}" / "phone {device}: {step}". |
| L6 | RESOLVED. The enroll handler deletes the phone's `device_setup_ticks` at each enrolment. |
| L7 | RESOLVED. "På telefonen"'s group renders only with a non-alarm row (`Page::has_phone_rows`). |
