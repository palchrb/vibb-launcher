# 51 - "Show the text" per app on the lock's glance page (proposal)

Asked 2026-10-20 ("definere per enhet som får poste push meldinger på lock screen hvorvidt full tekst skal vises
eller ikke ... for en klokke app er det lite å tape på det, samtidig som fulle meldinger vil man kanskje ikke vise på
låst skjerm"). Server + launcher in
one commit (the policy's shape changes: root CLAUDE.md "A change to the API goes into both directories in the same
commit"). Builds on design 25 §2.1 (`lock_glance`), `lock_glance.rs`, `device_lock.html` `#notifications`, the
launcher's `GlanceRows.lockGlancePolicy`/`GlancePolicy`, `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

## 0. Today

`lock_glance` = `{"apps": ["pkg", …], "show_text": bool}`: `device_lock_glance_apps` holds the ticked packages,
`device_policy.lock_glance_text` the one switch (migration 0066). The Lock page has the ticked apps and one "Show the
text" switch (`#lock-screen-text`). The launcher reads the policy field by field (`lockGlancePolicy`): a bad shape never
fails the policy; the fallback is missed calls only.

## 1. Decisions

1. **Per app, no global switch**: `device_lock_glance_apps` gets `show_text INTEGER NOT NULL DEFAULT 0` (migration 0083 -
   the next free number; `server/CLAUDE.md` "Data model" moves to 0084), backfilled from the phone's `lock_glance_text`.
   After that backfill `lock_glance_text` is unused (said in the migration's comment and the data-model line). A newly
   ticked app starts at "sender only" (`show_text = 0`); unticking and re-ticking resets it.
2. **The policy**: `{"apps": [{"package": "pkg", "show_text": bool}, …]}` only; the top-level `show_text` key is
   dropped. `policy_json_keys_snapshot` pins it.
3. **The launcher** (`lockGlancePolicy`): an element that is a string = that package, sender only (the old shape, so a
   launcher with the new code reads an old server); an object with a valid `package` = that package with its own
   `show_text` (missing = false); anything else dropped. `GlancePolicy` becomes `apps: Map<String, Boolean>` (package ->
   show text; `UNKNOWN`/`SERVER_DEFAULT` become maps). `showText` is read in `GlanceReader.kt:114` (`glanceLevel(…)`
   takes `policy.apps[pkg] == true`) and logged in `GlanceStore.kt:146`; `glanceRows`' `packageName in policy.apps` still
   works on the map's keys. An **old launcher** given the new shape drops the objects and shows missed calls only until
   it updates - design 25's fail-safe. The capability-gated shape (strings for a launcher without a new capability) is
   not taken: one gap of a few days between the two releases is accepted, and the release notes of both say so.
4. **The Lock page**: the `#lock-screen-text` group goes. Each ticked app's row is already its own form (tick save, back
   to `#lock-screen-app-<pkg>`); the text switch is a separate small form per ticked row posting to a new route
   `POST /devices/{id}/lock-screen/apps/text` (the existing convention: `/lock-screen/apps`, `main.rs:1058`, with
   `package_name` as a form field) carrying `package_name` and `show_text`; the handler runs `UPDATE
   device_lock_glance_apps SET show_text = ? WHERE device_id = ? AND package_name = ?`, logs it like `text_log_line`, and
   comes back to `#lock-screen-app-<pkg>` (the no-jump rule). No row = a 404 with nothing written (a decision: the row
   is gone, the page is stale - the same answer the tick's own routes give for a missing device). `save_text`
   (`lock_glance.rs:65`) and its route `POST /devices/{id}/lock-screen` are removed with the global switch. The switch shows only on ticked rows; the sub-line says "Text shown on the lock" /
   "Sender's name only". The footer "Anyone holding the phone can read the message text." moves under the Notifications
   group.
5. **Fossify Clock** is listed like any allowed app (`card_apps`) and harmless: its timer/stopwatch notifications are
   ongoing / alarm-category and `glanceCandidate` skips them, and design 42's tile reads its own channels, so it is
   unaffected. The need that drove this stays valid for any app the parent ticks (a calendar, a weather app).

## 2. Tests (required)

Server: the policy JSON with two apps, one with text and one without; a newly ticked app is sender only; untick and
re-tick resets the flag; the text route changes only a ticked app and returns 404 with nothing written for an unticked
one; the per-app log line; the migration backfills from the old switch; `migrations_73_to_82_in_order` extended to 0083
(renamed `migrations_73_to_83_in_order`); the contract pins move in the one commit: `tests/device_api.rs:446-451` and
`PolicyResponseCompatTest:495-515` (its line 505 asserts the keys `{apps, show_text}` and changes to `{apps}`; the fresh phone's default is `{"apps": [{"package": "io.element.android.x",
"show_text": false}]}`); `check_forms` on the Lock page. Launcher: `GlanceRowsTest`: the old shape (strings) and the new
(objects), a mixed list, an object without a package dropped, a row's text shown or hidden per app; `LockGlanceGuardTest`
unchanged.

## 3. Device check

Emulator: tick two apps, text on for one; a message from each; the lock shows the sender for both and the text for one;
neither the tick nor the text switch jumps the page.

## Revision (2026-10-20, after qa-r6-server-design.md)

- 51-1: no global switch; new tick = sender only; `lock_glance_text` used only by 0083's backfill; top-level `show_text`
  dropped; `#lock-screen-text` goes; release-note line for the old launcher.
- 51-2: per-row text form to `POST /devices/{id}/lock/glance/{package}/text`, back to `#lock-screen-app-<pkg>`, 404 for
  an unticked package.
- 51-3: capability-gated shape not taken; the gap accepted and stated.
- 51-4: `showText` read in `GlanceReader.kt:114` and `GlanceStore.kt:146`, named.
- 51-5: the global row and its footer handled in §1.4.
- 51-6: false Fossify sentence replaced (listed if allowed, harmless, design 42 unaffected).
- 51-7: contract pins named (`tests/device_api.rs:446-451`, `PolicyResponseCompatTest:495-515`); migrations test extended
  and renamed.
