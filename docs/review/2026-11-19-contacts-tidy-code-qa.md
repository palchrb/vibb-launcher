# Code QA: `contacts-tidy` (2026-11-19)

Reviewed by reading only (no cargo, Gradle or emulator before 22:00 UTC): `git diff main...contacts-tidy`, commits
acdcb308b (Calls page tidy), 6f347caeb (avatars), 15b556a92 (Location Settings back arrow), cb051fdd0 (catalog logo on a
phone's Apps page). Against design 77 (D16, §7, §14), design 87 (§5.3, §8.1, Q3), `docs/notes/server/calls-and-contacts.md`,
the launcher's `HomeModel.kt`/`KidAvatars.kt`/`KidPhotos.kt`/`SmsEngine.kt`, and CLAUDE.md's "Words in the UI".
Server CI 44605290031 was still running at the time of review.

Counts: **1 H, 3 M, 10 L.**

## High

### H1. `device.sms.held.allowed` is now unused: `i18n::tests::every_key_is_used` fails (server CI red)

- `server/src/handlers/calls_sms.rs:529-561` dropped the only use of `device.sms.held.allowed` (the allowed row's
  status). The key is still in `server/src/i18n/en.json:1842` and `nb.json:1842`. `i18n.rs`'s `every_key_is_used` scans
  `src/`, `src/handlers` and `templates`, and nothing else quotes the key, so the test fails.
- Fix: remove the key from both catalogues, or keep the allowed row (see M1/M3, which may change that decision).

## Medium

### M1. "Slett alle som venter" also deletes the allowed messages the card now hides

- `calls_sms.rs:529-561`: an open row whose sender is already allowed (a contact with `sms_in`, or a listed name) is
  hidden and left out of `waiting`. `drop_all_held` (`calls_sms.rs:933`) still selects every
  `state='held' AND dropped_at IS NULL AND expires_at > now` row, the hidden allowed ones too.
- Scenario: the parent taps "Tillat meldinger fra Mamma" (the row leaves the card). The phone is offline or hasn't synced
  yet, and two strangers' texts are still waiting. The confirm says "Slett de 2 meldingene som venter?". The save puts
  Mamma's held message in `sms_held_drop` as well, and the phone's `releasePass` applies drops *before* releases, so the
  message the parent just let through is deleted. Before this branch the allowed row was visible and counted in the
  confirm. Now it is invisible and still deleted.
- Fix: give the card, drop-all and the counts one notion of "waiting". For example, a `waiting_ids` helper that applies
  the same contact/fold/emergency judgement `load` does, used by `drop_all_held` too. Add a test: allow a contact, then
  drop all, and that row is not in `sms_held_drop`.

### M2. The logo fallback goes beyond the ask and reverses design 87 Q3 for installed apps (and the Lock page)

- `app_logos.rs:704` `phone_or_catalog_logos` fills **every** package the phone has no own logo for with
  `catalog_logos`. Despite its name, `catalog_logos` returns the majority logo of *any* package any phone reported, not
  only catalog rows. It is now used by `render_apps` (`device_pages.rs:2209`) and `lock_tiles` (`device_pages.rs:1910`).
- The ask was "a catalog app's kept logo on a phone that hasn't got it". Design 87 Q3 (decided) is "per phone - a
  phone's rows show only that phone's own logo (an app can look different on two phones - another version or another
  Android)". §5.3/R2-L4 also limited another phone's logo to the Apps tab's catalog rows: a hostile phone's logo for a
  package should not reach the other phones' pages.
- Scenarios:
  - (a) An app installed on Lily's phone whose logo isn't uploaded yet, or isn't wanted because the 300-file or 12 MB
    per-phone cap is full, now shows Emma's logo on Lily's page instead of the letter.
  - (b) The Lock page's lock-screen media rows also take other phones' logos.
  - No test covers Q3 on an installed app. `the_pages_show_the_logos` would not catch the reversal.
- Fix:
  - Merge only for rows that are catalog apps (`tracked_app_id` set) not installed on this phone: pass the needed
    packages in, or filter in `render_apps`.
  - Keep `lock_tiles` on `phone_logos`.
  - Add a test that an installed app without its own logo still shows the letter while a sibling phone has a logo for
    it.
  - Or, if the user wants the broader rule, get that decision recorded against design 87 Q3 first.

### M3. The device page row, Attention and the push digest still count the hidden allowed rows

- `calls_sms.rs:639` (`summary_value`) and `parent_events.rs:1492` (`waiting_count`, the `sms_held` digest) count every
  open held row, the allowed ones included. The card no longer lists them.
- Scenario: a parent allows a sender while the kid's phone is off for the night. The phone's page says "1 melding
  venter" and links to `#held`, but the card is empty. The digest push says the same. An allow made through the
  contact's own switch (not the held row's button) also leaves the `sms_held` event unread in Attention.
- Fix: the same shared "waiting" rule as M1, used for `summary_value` and `waiting_count` too.

## Low

- **L1. Lapsed rows are kept 30 days though the page treats them as decided.**
  - Where: `retention.rs:530`.
  - Problem: a `held` row past `expires_at` that the phone never reported `expired` (the phone was reset, or its token
    re-enrolled) is not "decided" in the new query. It stays 30 days, although the card already treats it as decided.
  - Fix: add `OR (at < ?7d AND expires_at < ?now)`, or simply `expires_at < ?7d`. Nothing needs a migration: the change
    is a query and a constant only, and `idx_device_sms_held_at` serves it as before.
- **L2. Add and edit treat "phone default" plus a Matrix ID differently.**
  - Where: `calls.rs:2234`.
  - Problem: on Add, "phone default" plus a typed Matrix ID stores `message_app = 'element'`. The edit form
    (`update_contact`) stores `None` (the phone's default) for the same input, so the two forms are not validated and
    saved alike. The Add form also never shows the edit form's orange "Mangler Matrix-ID" when Element X is picked
    without an ID, so that warning only appears after the save.
  - Fix: decide one rule and note it. The validation itself (`MESSAGE_APPS`, `valid_matrix_id`, the refusal kept in
    place with what was typed and Avansert open) matches the edit form.
- **L3. From a kid's request, two controls set the same value.**
  - Where: `device_calls.html:453` and `:490`.
  - Problem: the "Bruk Matrix-ID-en" switch and the new free Matrix ID field both set the contact's ID. A typed ID wins
    silently over a ticked switch.
  - Fix: hide the free field's value conflict. For example, show the switch only while the field is empty, or say which
    one wins.
- **L4. The emergency avatar differs from the launcher's.**
  - Where: `calls.rs:796`, `device_calls.html:274-278`, `style.css:2208`.
  - Problem: on the phone (`avatarStyle`, `shownPhoto`), an emergency number shows the number itself ("112"), never a
    photo, and has a red ring. The PWA shows a stored photo first, then the name's letter, with no ring. On the dark
    scheme the #3A1E24 disc is close to invisible: the launcher adds the ring for exactly that reason.
  - Checked and correct: `rem_euclid` equals `floorMod` for negative ids, and the id is the policy's `contacts[].id`
    (`device_api.rs:688`).
- **L5. Nothing re-runs the avatar test when the launcher's palette changes.**
  - Where: `tests/calls_simpler.rs`, `the_avatar_palette_is_the_launchers`.
  - Problem: the test reads `launcher/.../HomeModel.kt`, but `server-ci.yml`'s build filter doesn't list that file. A
    launcher-only palette change is not caught until the next server change.
  - Fix: add the path to the filter (and CLAUDE.md's list), or note it.
- **L6. Notes and comments are stale.**
  - `docs/notes/server/calls-and-contacts.md:140` still says "Retention drops a row 30 days" with no 7-day rule.
  - The same file's `:172-185` and `:241-245` still describe the decided states listed on the card.
  - Template comments `device_calls.html:26-29` and `:164-167` still say "each row … its forms return there".
  - Doc comments that are now wrong:
    - `allow_held`: "Back to the row (`#held-<id>`)".
    - `drop_held`: "which then reads Slettet".
    - `contact_requests::load`: "the open ones first".
    - `decline`: "back to the row" (in the notes).
  - The migration `0130` comment still says 30 days, which is fine to leave in a migration.
- **L7. Dead code remains.**
  - In `calls_sms::load`, the status branches for released/expired/dropped, `done` and `can_drop` are now always the
    open case. `held_row` has an unused `_held_id`.
  - `RequestView.status` and `open` are only reachable through dead branches, and they are what keeps
    `device.requests.added`/`declined` "used" for the i18n test.
  - Fix: trim the code together with H1.
- **L8. Other ways into Location Settings still go back to Locate.**
  - Where: `device_lock.html:253`.
  - Problem: the phone's own Lock page links to Location Settings without `from`, so its back arrow goes to Locate.
    That is consistent with the ask ("only from the phone's page") but may surprise.
  - Checked and correct: `from` is accepted only as `device` (`from_suffix`, the GET's check), kept by both forms and
    both redirects, and `back=zones` still wins.
- **L9. Every Apps and Lock page render now checks every package's logo file.**
  - Where: `app_logos.rs:722`.
  - Problem: `catalog_logos` now runs on every render of a phone's Apps page and Lock page. It reads every package of
    every phone and checks each candidate's file on disk (up to hundreds of checks a render), where before this ran only
    on the Apps tab.
  - Fix: restrict the lookup to the packages that are missing (this goes with M2's fix). The failure path is fine: a
    failed catalog read is logged and the phone's own logos stand.
- **L10. RCS wording is imprecise.**
  - `sms_rcs_info_after` (en/nb `:1355`): "let it through" / "slipp den gjennom" reads as letting the *code* through,
    while the parent lets the *sender* through.
  - Avansert's RCS row (`device_calls.html:695`) now opens with "Messages is hidden, so use <link>", with no word of
    what the form does or why.
  - Suggested wording: "…: allow its sender under Messages Waiting" / "…: slipp avsenderen gjennom under Meldinger som
    venter".
  - Otherwise the lines meet "Words in the UI".

## Checked, no finding

- Dedup: a re-post of a known id only moves forward. A decided row purged at 7 days that the phone posts again (only
  after a lost 2xx; the phone deletes a final row once it is reported) comes back in its final state, or as `held` past
  its window. Either way it is never open, never on the card, and never pushed (`raise_held` gives `Push::No`, and only
  for `held`).
- Redirects to `#held` and `#requests`: both sections are always rendered, even when empty, so the anchor exists after
  the last decision and the page doesn't jump to the top. Notices still land on the same anchors.
- Requests: the card's SQL is exactly the "live" rule (`state='open' AND asked_at > live_since`).
- Add Contact is one form and one save. A refused name, number, kept name or Matrix ID re-renders with every typed
  value, the Message button and Avansert open. Strings are i18n'd in nb and en, and the server/launcher API is
  unchanged.
