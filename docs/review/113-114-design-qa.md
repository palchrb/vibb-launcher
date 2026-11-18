# Design QA: 113 (PWA copy round) and 114 (phone setup status)

Read-only QA on `main` at a65da6857. Paths: `S/` = `server/src/`, `T/` = `server/templates/`. Ranked H (blocks the
build or breaks a settled rule), M (wrong or incomplete; fix in the note before that step is built), L (tidy).

What checked out (no finding): every key in 113's tables exists in both catalogs today and no "added" key exists yet;
every deleted key is used only where the note removes it (two shared ones - `device.advanced.setup.line`,
`device.emergency.always` - are removed in both places by the note); the swap.js soft keys in D6 match
`static/swap.js:34-35`; no form `action`/`#anchor` changes and no landed-on section id goes (`#banned` stays for its
flash - `S/handlers/admin.rs:200-203`; `#add` stays on Spotify; the dropped `<h2>`s carry no id); the Attention row
`MobileDataOff` fits `attention.rs` (Action level, after `NotificationsOff`, all Actions after all Alarms, the title is 7
words with no jargon, `read_from_report` true by default, `facts_of` serves both the summary and the badge - 
`S/attention.rs:226-308, 529-640`); 114's feature anchors all exist (`#mobile-data`, `#abroad`, `#quick-controls`,
`#layout`, `#lock-screen`, `#on-phone`, `#settings`, `#app-push` in `T/partials/app_push_group.html`, `#location`,
`#logins-wifi`); 114's Android actions are the right constants and API levels for minSdk 34.

---

## 113 - PWA copy round

### H1. A KEEP is broken: the web-filter log's deletion warning goes behind the (i)
- Evidence: 113:740 moves `device.filter.log_footer` («… Slår du den av, slettes loggen til denne telefonen.») into a
  footer (i) "KEEP: the deletion, moved behind the (i)". The audit's KEEP (`docs/review/2026-11-17-pwa-words-audit.md:239`)
  says: "It is irreversible. Either keep it visible as one short line, or make switching off confirm." The switch
  autosaves with no confirm (`T/device_filter.html:136-143`, `data-autosave`). D4 also lists "data-deletion notes".
- Fix: keep one visible line, as 113 already does for Posisjonsinnstillinger (113:942-946): a new key
  `device.filter.log_off_erases` «Av sletter loggen.» / "Off deletes the log." as the visible footer text, with
  `log_footer` (the retention) behind the (i) beside it (the KEEP exception shape of 113:946). Add the key to the table.

### M1. Roaming: the new orange home "switch off" line hides the place/national line
- Evidence: `card()` drops the `roaming` line whenever any `switch` line is orange (`S/mobile_network.rs:569-574`,
  `place_in_switch = any(key == "switch" && warn)`). Today only the abroad variant is orange, and it names the place.
  113:619-625 makes the plain `off` line (no place) orange too, so at home on national roaming, or with `where` unknown
  and `roaming` true, the switch line now suppresses the grey «Nasjonal roaming…»/«Roamer i …» line.
- Fix (state logic, exact): `place_in_switch` is true only when the switch line is the `off_abroad` variant
  (`report.where_is == Some("abroad")`). Add a test row: På, at home, national roaming, switch off -> both the orange
  switch line and the national line.

### M2. Add Contact's texts sub says «Ikke styrt» when SMS is Off
- Evidence: 113:395 `texts`: «Ikke styrt» when `sms_mode != "managed"`. `sms_mode` is `managed`/`unmanaged`/`off`
  (`server/testdata/sms_contract.json` `modes`; `S/handlers/calls_form.rs:208-209`). Under Off, SMS isn't "not
  managed" - it is off and messages are lost.
- Fix: `texts`: `unmanaged` -> «Ikke styrt» / "Not managed"; `off` -> a new `device.calls.sub.sms_off` «SMS av» /
  "SMS off"; `managed` -> `None`. Add the row to `calls_form::tests::the_sub_lines_follow_the_modes`.

### M3. ASK 3 (taken) silences the multi-device "not confirmed" receipt
- Evidence: 113:1660-1661, 1670-1672 delete `many_opened` and `many_shown` and keep only `not_sent`, `pushed`,
  `sending`. In `delivery_line` (`S/handlers/notifications.rs:886-898`) `many_shown` with `shown == 0` is the
  several-devices form of `pushed` ("sent, nobody confirmed") - the wrong state 113 keeps for one device.
- Fix: keep a line when several devices got it and none showed or opened it: reuse `many_shown` (it then reads «… · vist
  på 0») or add `delivery.many_pushed` «Sendt til {devices} enheter, ikke bekreftet»; `None` once one is shown/opened.

### M4. The user's ASK answers are not folded into the tables the build reads
- Evidence: ASK 2 = KEEP, but 113:1424-1425 still mark `settings.notifications.row.since_opened`/`not_opened`
  "**ASK** deleted" and 113:1450-1452 describes the change; the index 113:1855 says "ASK only"; ASK 5 = TAKEN but
  113:1711-1713 is still an ASK paragraph; ASK 3's rows still say "**ASK**"; the Counts (113:1893-1895) assume all ASKs open.
- Fix: rewrite those rows as decided: ASK 2 rows -> "unchanged" (out of the deleted count, and drop the
  `device_row` state bullet and its test bullet 113:1457-1458); ASK 3 rows -> "deleted" (with M3's exception); ASK 5 ->
  a Konto template line ("`passkeys_still_work` moves into the password group's (i); Reset 2FA's confirm keeps it");
  ASK 1 -> "not built". Recount.

### M5. P1 hides consequences some titles don't carry
- Evidence: 113 limits `Note.sub` to Anrop's three KEEPs (113:23-27, 336, 392) and says every other title "reads
  alone" (113:410, 461, 710, 883). The audit allowed "a 3-6-word consequence where the title doesn't carry it"
  (audit:142, C1). Titles that lose the news once their detail goes behind the (i):
  - `device.calls.problem.needs_update` "The phone needs an update" - detail "SMS is lost and calls are contacts-only
    until then" (`S/handlers/calls.rs:1437-1446`);
  - `device.time.ledger_unreadable` "Screen-time record unreadable" - "The phone counts today's screen time as used up";
  - `device.lock.note.needs_update` "Vibb One needs an update" - "This version has no lock";
  - `device.lock.note.bad_hash` "Phone can't read the Child PIN" - "Only the Parent PIN … opens it now".
- Fix: give each a short `sub` (nb/en in the tables), e.g. «SMS går tapt til da» / «Bare kontakter kan ringe til da»,
  «Dagens skjermtid regnes som brukt opp», «Ingen lås til da», «Bare foreldre-PIN-en åpner den» - or reword the
  titles. List them under D1 instead of "every other caller passes `None`".

### M6. Home's Flymodus row turns orange when the switch isn't even on
- Evidence: `airplane_mode::home_sub` adds `no_role` whenever `!report.assistant`, whatever bit 64 is
  (`S/airplane_mode.rs:284-289`). 113:263 makes exactly that case orange. With the kid's Flymodus switch off nothing is
  wrong (114's `when: airplane` agrees) - "speak up only when something is wrong".
- Fix: orange `no_role` only when bit 64 is on; with bit 64 off show nothing for the role (the (i)'s new sentence,
  113:245, says when the switch shows). Add that row to `tests/airplane_mode.rs::the_home_form_keeps_bit_64_without_its_row`.

### M7. An (i) inside a link card
- Evidence: the summary's no-Parent-PIN card is an `<a>` (`T/device_detail.html:80-83`); 113:178 puts a
  `<details>` (i) in its `attention-sub` - interactive content inside `<a>` (invalid; a tap on the (i) navigates). The
  summary's `#mobile-state` lines are `<a>`s too (`T/device_detail.html:135-136`) and render `line.sub` visibly; 113
  turns the `lingering_fix`/`still_on_sub` subs into (i) text (113:594-596) but says nothing for the summary.
- Fix: link cards render the title only (and the chevron); the (i) lives on the target page (the Lock page keeps
  `no_parent_pin_sub` visible, KEEP). Say so for `no_parent_pin` and `summary_lines`.

### M8. An (i) inside a switch's `<label class="row">`
- Evidence: Regel › Stille gets "an inline (i) after the label" (113:1484-1486) but the switch row is
  `<label class="row" data-row="silent">` (`T/schedule_rule.html:53-55`); Konto's «Lås med Face ID» gets "a row (i)"
  (113:1696) on `<label class="row">` rows (`T/account.html:283-290`). `<details>` isn't phrasing content (invalid in
  `<label>`), and a tap on it can toggle the switch, which autosaves.
- Fix: put each panel outside the label: a `details.info.cell` row right after the label row, or the group's footer (i)
  (as Mobilnett does). State the rule once in D1 P2: "never inside a `<label>`".

### M9. Tests that pin changed strings or removed hooks are missing (item f)
- Not listed:
  - `S/tests/shell.rs:1107` asserts `data-hint="spotify-streams"`, which 113:1270 removes - switch it to `data-hint="spotify-order"`;
  - `S/tests/music_storytel.rs:329` (`save_probes_then_seals`) pins `storytel_refused_until`'s old sentence (113 lists
    the test only for `storytel_accepted`); `:504` (`picker_lists_groups`) pins "Nothing on the Storytel shelf yet." (`music.storytel.empty`);
  - `S/tests/android_lock_page.rs:662` (`the_child_pin_stays_while_androids_lock_is_chosen`) pins `remove_needs_pin`'s
    full sentence on `data-row="remove-needs-pin"` (113 lists the test for `own_lock` only).
- Dropped parameters missing from the Tests list (113:1860-1862): `device.summary.qa.mode_line`/`_none` and
  `warn_setup`/`_plain` lose `{rule}`; `device.calls.sub.pictures_no_texts` loses `{kid}`/`{contact}`;
  `device.requests.short_choose` loses `{name}`. Name them, and drop the arguments at the call sites
  (`S/handlers/quick_actions.rs:151-155, 465-471`; `calls_form.rs:211`).
- Fix: add these to the page bullets; resolve 113:206-207's "grep before the build": `report_age`'s text is pinned in
  `S/tests/window_relock.rs`.

### L1. 113:25 lists `tracked_apps.rs` among `device_pages::Note` builders and says "35 literals". `tracked_apps.rs` has its
own `enum Note` (`S/handlers/tracked_apps.rs:3413`); the `device_pages::Note` sites are in `device_pages.rs`, `calls.rs`,
`music.rs`, `schedules.rs`, `kiosk_escapes.rs`, `kid_lock.rs`, `lock_glance.rs` (about 26 struct literals plus the
`kid_lock`/`music` helper closures). Fix the list; the count doesn't matter.

### L2. `MobileDataOff` is temporary: 114 turns step 5 into `Setup(n)`. Add one line under 113's Mobilnett state logic
("replaced by 114's `Setup(n)` when 114 is built; 114 removes the kind") so the two notes point at each other (see 114 H1).

### L3. Varselinnstillinger: `#push-home-screen`/`#push-blocked` as `details.info cell` "with summary row label = the
existing JS state word" (113:1438-1440) shows the state word twice - it is already `#push-state`'s value
(`T/notifications_settings.html:38-43`). Keep the two `<p id hidden>` inside one row (i) on the Varsler row (JS still
toggles `hidden` by id), or give the summary a fixed label («Slik gjør du»).

### L4. `dns_filter::phone_line`: once `custom_in_use` returns `None`, an ended outage alone still renders
`"{name} · DNS {ended}"` (`S/handlers/dns_filter.rs:470-475`). Say its tone (grey: it is over) in 113:1539-1541.

### L5. `i18n::tests::every_key_is_used` counts any quoted occurrence in `src/` and `src/handlers/`, in-file test
modules included (`S/i18n.rs:904-916`), so a "deleted" key still quoted in a `#[cfg(test)]` module passes silently.
Add to 113:1860: grep each deleted key after the build.

### L6. Say it once on the summary: with the Attention row, the Sections row value still appends «Mobildata av»
(`mobile_network::row_value`, `S/mobile_network.rs:399-401`). Drop `mobile.value.data_off` there or note why it stays.

### L7. `app_push.settings.save_note`'s new words «… slås app-push på for alle telefonene» are broader than the handler:
"every phone with its switch on" (`S/handlers/app_push.rs:198-200`). Keep the KEEP accurate: «… for alle telefonene
med bryteren på» / "… for every phone with its switch on".

### L8. 113 reworks the summary's Oppsett card (`SetupStep { value, detail }`, 113:167-172, 192) which 114 removes
(114 §5.2, §8). Keep 113's card change to words (moved texts) so the build doesn't spend a step on code 114 deletes;
likewise 113:245's «(Oppsett på telefonsiden)» will point at a card that 114 removes - say «under Oppsett».

### L9. Doc comments quoting old words to update with the strings: `S/handlers/mobile_page.rs:9`,
`S/handlers/logins.rs:8, 72`, `S/handlers/device_pages.rs:896`, `S/handlers/calls_mmsc.rs:32`, `S/handlers/app_push.rs:4,
299`, `S/handlers/contact_requests.rs:326`.

---

## 114 - phone setup status

### H1. Mobile data off is counted twice once 114 lands on top of 113
- Evidence: 113 adds `attention::Kind::MobileDataOff` (113:635-640, the user's call). 114 makes `mobile_data` a
  `summary: count` step (114:70) and folds `NoNotificationAccess`, `NotificationsOff`, `Calls(NoRedirection)` into
  `Setup(n)` (114:532) - but not `MobileDataOff`. Its §8 row 534 only replaces the summary line. Result: two Action rows
  for one fact and two in the Devices badge.
- Fix: §8 adds "Attention `MobileDataOff` (design 113) -> folded into `Setup(n)` (the kind, its key, title and
  `facts.mobile_data_off` go)"; §9 step 3 and §10 ("no `MobileDataOff` row beside `Setup(n)`") follow. Update §5.2/§8's
  "`#mobile-state`'s line (S/mobile_network.rs:353-361)" - 113 already removes it.

### M1. Lines that will say the same thing twice after 114 (fold list incomplete)
- Evidence:
  - Home `#quick-controls`: 113 makes the Flymodus row's sub «Vibb One er ikke digital assistent» orange and keeps
    «Helt uten nett i flymodus» (`airplane_mode::home_sub`, `S/airplane_mode.rs:279-296`); 114 §5.4/§5.6 add
    `setup.warn.assistant` with the same words and `wifi_airplane` above the same section.
  - Lock `#lock-screen`: `device.lock.glance.no_access` / `no_access_messages` ("Missed calls only", sub "The fix is under
    Home Screen…", `S/lock_glance.rs`) stays beside the new `notification_access` line.
  - Advanced `#updates`: `escape.filter_no_access` (`S/kiosk_escapes.rs:521-523`) is another notification-access warning,
    and its sub is adb-only (`en.json:4197`: "Grant it with adb before setting the phone up") - against no-adb-beyond-QR,
    and missing from §5.7's adb list.
  - Mobilnett: `mobile_data` warns at both `#mobile-data` and `#abroad` (§5.4) - the same line twice on one page.
- Fix: add these to §5.4's "These lines replace" and §8: `home_sub`'s role/Wi-Fi parts go (the row keeps «Blokkert …»);
  the glance `no_access*` notes go; `filter_no_access` becomes the `notification_access` line via a new feature
  `notification_filter` (`page: advanced`, `anchor: updates`) and its adb text goes; on Mobilnett `mobile_data` warns
  once (at `#mobile-data`).

### M2. The user's answers are not applied to the table and the contract
- Evidence: the user made `vibration` OPTIONAL (114:677), but §2.2 row 16 still reads "ASK (recommended: required)",
  114:87-88 says "apart from `vibration`", and the contract sample keeps `"need": "required"` for it with
  `needed[0].count: 6` (114:160-191) - optional makes that count 5 and the step quiet. ASK 3: PLAN.md:137 records the
  answer ("the two map steps in the list only while Organic Maps or CoMaps is installed"); the note still says
  "waiting" (114:678-680).
- Fix: row 16 `optional`; recount the sample; add rows 21-22 with a `when: map_app` that depends on the report's
  `installed_apps` - so §2.3's "both sides run the same rule from the policy they hold" needs one report-derived `when`
  (state it: the server reads `installed_apps_json`, the phone its package list), and a `needed` row for it.

### M3. Two ASKs with a visible cost were not put to the user
- Evidence: 114:683-684 lets ASK 2 and ASK 5 stand unasked. ASK 5 removes the adb shortcut from
  `device.home.no_notification_access_fix`, which the words audit marked KEEP ("KEEP: the adb command in
  `no_notification_access_fix`, behind the (i)"), and §5.7 names its own cost. The memory rule "visible-cost choices
  first" says such a choice goes to the user before building. (The no-adb rule itself allows adb "as a shortcut in the
  docs", so the recommendation is consistent - it just needs the word.)
- Fix: list ASK 5 (and ASK 2) under "still to ask" with the KEEP conflict named; build with the adb lines kept until
  answered, or keep them in the new ⓘ texts as the audit KEEP says.

### M4. `device_owner`'s alarm row has no kind
- Evidence: §5.2 (114:289-291) raises "Vibb One doesn't manage the phone" but names no `Kind`; `level()`, `title()`,
  `target()`, `key()` and `alarms_come_first_in_the_specs_order` are exhaustive (`S/attention.rs:310-455, 865-922`).
- Fix: name it (`Kind::NotDeviceOwner`, key `not-device-owner`, Alarm, placed after `AccessRevoked`), and say whether
  the other report-derived rows still show when the phone isn't device owner.

### L1. §2.2 has no `features` column; the step -> feature map only exists by section in §5.4 and for four steps in the
sample. Add the column so `SetupStep`, the contract and `the_contract` test read one table.

### L2. Migration number: "design 110 reserves 0155" (114:245) is stale - `0155_catalog_app_logo.sql` is on main
(a65da6857); 110's own note (`docs/design/110-map-links.md:478`) needs moving to the next free number too.

### L3. `clean` "drops … non-booleans" (114:211-212) also drops `null`, so the `report` row's `"fingerprint": null` is
stored absent and §2.2's null-vs-absent distinction is lost on the server (harmless: both are "not shown"). Say so in a
`clean` row, or keep `null`.

### L4. `mobile_data`'s reading is `TelephonyManager.isDataEnabled` (`L/server/MobileNetworkRuntime.kt:351`), which is
false also for carrier, thermal or policy reasons; the parent would be told to turn on a switch that is on. Consider
`isDataEnabledForReason(DATA_ENABLED_REASON_USER)` (API 31, no gate on 34+), or note the limit.

### L5. `when: calls_managed` is `calls_mode != "unmanaged"` (`S/models.rs:459-462`), so calls Off also requires
`full_screen_intent` and `call_filter`. Confirm that is meant (no calls ring when Off).

### L6. Line references will be stale once 113 lands (436 keys and several functions change): §8's
`nb/en.json:1695-1697, 1715-1717, 1841, 3956, 4535-4554`, `S/mobile_network.rs:353-361`, `S/handlers/device_pages.rs:1518-1559`.
Name keys and functions instead.

### L7. §10: say that `setup` is a full-report field outside `status_heartbeat.json`'s `heartbeat_fields`, and that
`StatusReportsTest` / `tests::status_heartbeat` need no row (or add one).

### Checked, no finding
- Contract shape: one table, `needed` rows run through each side's own source mapping then one rule - sound; the
  `report`/`policy`/`clean` rows follow the house pattern; CI path filters and CLAUDE.md are in step 1.
- Need rule: `airplane_role` (bit 64 + assistant done), `write_settings_used` (bit 16 or a clock format) and
  `calls_managed` match today's server rule (`S/airplane_mode.rs:330-420`) and the phone's `AssistantSetupPlan.kt:40-82`.
- Android 14-17: `canUseFullScreenIntent`/`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` are API 34 (no gate);
  `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` API 30; `getRestrictBackgroundStatus` API 24; `isDeviceOwnerApp`; the
  restricted-setting check and the OEM page names are correctly left to the gate; Android 17 is honestly owed.
- No-adb-beyond-QR: every phone/parent step has an on-phone path except `device_owner` (the QR, by nature);
  notification access gets its first on-phone path; adb appears only in the gate's state setup.
- Build order vs C1: the dependency on C1 removing `airplane_mode_v1` and `window_relock_v1` gates is stated and
  correct (`S/airplane_mode.rs:170-175`, `S/handlers/device_pages.rs:1626-1631`, `S/handlers/quick_actions.rs:619-623`).
