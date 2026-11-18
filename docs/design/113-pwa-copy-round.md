# 113 - PWA copy round

Status: design, 2026-11-17; design QA applied (`qa-113-114.md`, the 113 part: H1, M1-M9, L1-L9) and the user's
answers to the five ASKs folded into the tables. Server only (`server/templates/`, `server/src/i18n/{nb,en}.json`, the handlers named below);
no launcher or music change, so nothing here depends on the Android version.

Inputs: CLAUDE.md "Words in the UI" (the rules, the user's words); design 112 (Settings › App push, approved - folded in,
with its own footer fixed); PLAN.md "Device › Mobile network: too many words" (approved) and "A PWA-wide words-and-state
pass" with the user's calls of 2026-11-17; the audit `docs/review/2026-11-17-pwa-words-audit.md` (320 findings, its KEEP
marks binding, its four stale wordings fixed regardless).

Out of scope: stored values, the policy/report contracts (`server/testdata/*.json` untouched), new features (112's
optional "Telefoner" group is not built), the launcher's and the music app's own words.

## Decisions

### D1. The five patterns, as code

- **P1 - a warning is its orange title; the rest is behind its ⓘ.** `partials/note_warning.html` renders `note.title`;
  then, when `note.sub` is set, that short visible line; then, when `note.detail` or `note.fix` is set, one
  `<details class="info">` (aria-label `common.more_info`; `common.how_to_fix` where a panel holds only steps) whose
  panel holds `detail` and `fix` as two `<p>`s. Today
  the partial shows `detail` in the open and hides `fix` inside it (and never shows `fix` without a `detail`).
  `device_pages::Note` gains `pub sub: Option<String>` - a visible consequence of at most six words, set only where a
  KEEP needs one (Anrop's `emergency_call`, `callback`, `screen_failed`, where the time is the news) or where the title
  alone doesn't carry the news (QA M5, the four below); every other caller passes `None`. The four:

  | Note (title key) | Visible `sub` | nb | en | Kind |
  |---|---|---|---|---|
  | `device.calls.problem.needs_update` | the finding's own sentence, `device.calls.finding.needs_update` / `_sms` / `_calls` (already short), moved from the ⓘ to `sub`; the fix stays in the ⓘ | SMS går tapt til da. | SMS is lost until then. | unchanged keys |
  | `device.time.ledger_unreadable` | `device.time.ledger_unreadable_sub` (already short), visible as `sub` | Telefonen regner dagens skjermtid som brukt opp. | The phone counts today's screen time as used up. | unchanged |
  | `device.lock.note.needs_update` | new `device.lock.note.needs_update_short`; `needs_update_sub` stays in the ⓘ | Ingen lås til da | No lock until then | added |
  | `device.lock.note.bad_hash` | new `device.lock.note.bad_hash_short`; `bad_hash_sub` stays in the ⓘ | Bare foreldre-PIN-en åpner den nå | Only the Parent PIN opens it | added |

  `Note` is built in
  `device_pages.rs`, `calls.rs`, `music.rs`, `schedules.rs`, `kiosk_escapes.rs`, `kid_lock.rs`, `lock_glance.rs` (about
  26 struct literals plus the `kid_lock`/`music` helper closures; `tracked_apps.rs` has its own `enum Note`, untouched):
  each gets `sub: None` or its short line. Every page
  whose orange line carries a fix uses the same shape (`attention attention-action`, title, `attention-sub` with the
  ⓘ): Mobilnett's lines, App push's missing/no-listener lines, the phone page's alarms. Titles that leaned on their
  detail get their own wording in the page tables.
- **P2 - an explanation is an ⓘ alone.** A group footer sentence goes into an ⓘ:
  `<div class="group-footer"><details class="info"><summary aria-label="…">ⓘ</summary><div class="info-panel"><p>…</p></div></details></div>`
  (the shape Device › Apps uses today; CSS `.group-footer details.info` exists). A visible "Om …"/"Hva …" label row
  beside an ⓘ becomes that footer ⓘ; its key stays as the aria-label (an aria-label is not a visible label). A field's
  explanation is a field ⓘ (`details.info.field-info` after the `<label>`, as `tracked_app_add.html`); a row's is a row
  ⓘ (`details.info.cell` with `summary.row`, as `partials/note_row.html`). A sentence moved unchanged keeps its key (the
  panel renders it). No new CSS, no JS.
  Two places never hold an ⓘ (QA M7, M8): a link card or link row (`<a>`: interactive content inside `<a>` is invalid,
  and a tap on the ⓘ would navigate) renders its title alone (and the chevron) - the ⓘ lives on the page it links to;
  and a `<label>` switch row (a `<details>` isn't phrasing content, and a tap on it can toggle the switch, which
  autosaves) - its ⓘ is a `details.info.cell` row right after the label row, or the group's footer ⓘ.
- **P3 - no sub-line under a choice or a switch.** The `row-sub` goes; its text joins the group's ⓘ (P2). A sub-line
  that is a state (an update hint, a refusal) stays and is orange.
- **P4 - a fine state shows nothing, or a quiet value.** The handler stops building the line; each is listed under
  "State logic" with its function and exact condition.
- **P5 - say it once.** A footer that repeats the page, a sentence that repeats its label, a flash that explains
  delivery the page already shows: deleted.

### D2. Keys

- A changed text keeps its key. A sentence moved behind an ⓘ unchanged keeps its key ("moved", not counted as changed).
- A key no code uses any more leaves both catalogs (`i18n::tests::every_key_is_used`); a new key goes into both
  (`both_catalogs_have_every_key_with_the_same_parameters`); a parameter dropped from a text is dropped in both.
- en keeps en.json's style; nb keeps nb.json's (" - ", «»).
- Every changed key is in a table below with its new nb and en; "Today nb" may be cut with «…».

### D3. The user's calls (settled, 2026-11-17)

- «På nett» beside the green dot stays (`phone_status::header` untouched).
- Anrop › Avansert keeps listing defaults (design 80 D8): its summary is untouched.
- Kalender's lock-screen privacy line (`calendar.lock.footer`) goes, not into an ⓘ.
- «Mobildata er slått av på telefonen» (Android's own mobile-data switch off) is orange on Mobilnett and raises an
  Attention row; the lock footer's "slå mobildata helt av" recipe goes.
- The kid's own switch under Bare Vibb (`only_vibb_kid`) stays a quiet value, no Attention.
- Mobilnett: "I utlandet" → "Roaming" with «På», «Av», «Bare Vibb», no sub-lines, the ⓘ alone; Android's roaming switch
  shown only when off, as one short orange line, the how-to behind the ⓘ.

### D4. KEEP

Every audit KEEP survives, at most shortened or behind an ⓘ: every delete/erase/remove/restore confirmation, the
foreign-access red title, «tre feil passord logger deg ut», the wrong-2FA alarms and `account.warning.*`, data-deletion
notes, Spotify's «egen risiko», the MET/Yr credit, the 1881/1880 and SMS-code warnings, the backup restore and
2FA-secret notes, and the note that a save turns app push on for every phone. Where a table moves a KEEP, its row says
where it went.

### D5. The four stale wordings (fixed regardless)

`device.summary.foreign.step_unlock` (names a button the summary no longer has) - Telefonens side;
`calendar.calendars.setup` («Kalenderlenker over») - Telefon › Kalender; `settings.notifications.clock_warning_sub` (a
Pi) - Varselinnstillinger; `mobile.abroad.national` («Ingen data»/«Som hjemme») - Mobilnett.

### D6. A save still never jumps

No form's `action` or `#anchor` changes and no section id a form lands on goes. The ⓘs are `<details>` (no JS). The
hooks `static/swap.js` swaps by (`data-flash`, `data-warning`, `data-mobile-line`, `data-choice`, `data-row`,
`data-setup-step`) stay on the elements that replace today's. The no-JS `<noscript>` Save buttons stay.

### D7. Build order

One branch, one commit per step, each with its tests: (1) P1 - the partial, `Note.sub` and every caller; (2) Mobilnett
and the Attention row; (3) App push (112 + this note); (4) Telefonens side; (5)-(n) the remaining pages, a commit per
area (device sub-pages, Finn, catalog, music, Settings, account/sign-in/setup). The five ASKs are answered (end of the
note) and folded into the tables: ASK 1 not built, ASK 2 and ASK 4 kept as today, ASK 3 and ASK 5 built.

## Phones: the list and a phone

### Telefoner (`devices_list.html`, `partials/push_offer.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `devices.push_offer.sub` | Få vite med en gang når en telefon trenger deg - en nødsamtale, lavt batteri, … Du kan endre det senere under varselinnstillingene. | - | - | 1, 2 | deleted |
| `devices.push_offer.done` | Varsler er slått på for denne telefonen. | Varsler på. | Notifications on. | 4 | words |
| `devices.warning.push_sub` | Sjekk det under Varsler | - | - | 4 (the card is a link to Varsler) | deleted; state: `notifications::push_warning` |

Template:
- `push_offer.html`: the title row keeps its `<span class="row-sub" data-offer-line></span>`, empty: `static/notifications.js` (`offer`) writes the blocked/failed text into it. `style.css`: `.row-sub[data-offer-line]:empty { display: none; }` (bump `head.html`'s `?v=`).
- `devices_list.html`: the three warning cards render `<span class="attention-sub">` only `{% if let Some(sub) = warning.sub %}`.

State logic:
- `auth::WarningRow.sub` becomes `Option<String>`; `live_warning_row` and `login_warning_row` give `Some` (KEEP: «Noen kan passordet ditt»), `notifications::push_warning` gives `None`.

Tests:
- `tests/web_push.rs::the_first_opening_offers_notifications_on_devices`: "Notifications are on for this phone." -> "Notifications on.".

### Telefonens side (`device_detail.html`)

"På nett" beside the green dot stays (the user's call). The mobile lines are in the Mobilnett section above.

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.summary.blocked.footer` | Skjult på telefonen selv om de er tillatt under Apper. | - | - | 4 (the header and each row's reason say it) | deleted |
| `device.summary.blocked.more.one` | Og {count} til som telefonen ikke lister opp: se etter dem under Innstillinger › Apper på telefonen, og avinstaller det du ikke kjenner. | {count} til som telefonen ikke viser | {count} more the phone doesn't show | 5 (one orange row-note) | words |
| `device.summary.blocked.more.other` | (as `.one`) | {count} til som telefonen ikke viser | {count} more the phone doesn't show | 5 | words |
| `device.summary.blocked.more_info` | - | Se etter dem under Innstillinger › Apper på telefonen, og avinstaller det du ikke kjenner. | Look for them in the phone's Settings › Apps and uninstall what you don't know. | 3 (behind the row's ⓘ) | added |
| `device.summary.setup.background_sub` | Endringer kan komme sent mens telefonen sover. | (moved, unchanged: the step row's ⓘ) | (moved) | 1 | moved |
| `device.summary.setup.default_apps_sub` | Endret under oppsettet: {apps}. | (moved, unchanged: the step row's ⓘ, before `default_apps_sms`) | (moved) | 3 (QA L8: words only, the card is 114's) | moved |
| `device.about.write_settings_sub` | Bryterne for berøringslyder og klokkeformatet trenger den. | (moved, unchanged: the step row's ⓘ; About keeps its own use) | (moved) | 1 | moved |
| `device.summary.setup.assistant_sub` | Velg Vibb One - trengs for Flymodus | (moved, unchanged) | (moved) | 1 | moved |
| `device.summary.setup.wifi_sub` | Ikke satt opp. Uten det er telefonen helt uten nett i flymodus. | Uten den er telefonen helt uten nett i flymodus. | Without it the phone is fully offline in airplane mode. | 4 (the step itself says "not set up") | words (behind ⓘ) |
| `device.summary.setup.default_apps_sms` | Meldinger som kom mens en annen app hadde SMS-rollen, ligger i den appen. | (moved, unchanged: the step row's ⓘ) | (moved) | 3 | moved |
| `device.summary.setup.call_filter_sub` | Vibb One trenger anropsfilter og viderekobling for å styre samtalene. | (moved, unchanged) | (moved) | 1 | moved |
| `device.summary.setup.footer` | Fullfør oppsettet på telefonen: åpne den for oppsett, og trykk på varselet «Fullfør oppsettet av Vibb One». | (moved, unchanged: a footer ⓘ alone) | (moved) | 2 | moved |
| `device.summary.qa.mode_line` | Telefonen låses som i {rule}: {list} virker. Nødanrop virker alltid. | {list} og nødanrop virker. | {list} and emergency calls work. | 1, 4 (KEEP: the emergency fact stays, visible) | words |
| `device.summary.qa.mode_line_none` | Telefonen låses som i {rule}. Nødanrop virker alltid. | Bare nødanrop virker. | Only emergency calls work. | 1, 4 (KEEP) | words |
| `device.summary.qa.pause_note` | Reglene kommer tilbake av seg selv, eller med Avslutt nå. «Alle regler» finnes bare for pause. | - | - | 5 (the tile and «Avslutt nå» say it) | deleted |
| `device.summary.qa.warn_setup` | Telefonen er åpnet for oppsett til {at}. Det avsluttes når du slår på {rule}. | Avslutter oppsettet (til {at}) | Ends the setup opening (until {at}) | 5 (KEEP: says what the button undoes) | words |
| `device.summary.qa.warn_setup_plain` | Telefonen er åpnet for oppsett. Det avsluttes når du slår på {rule}. | Avslutter oppsettet | Ends the setup opening | 5 | words |
| `device.summary.qa.warn_paused` | {rule} er på pause til {at}. Pausen avsluttes. | Avslutter pausen av {rule} (til {at}) | Ends the pause of {rule} (until {at}) | 5 | words |
| `device.summary.qa.warn_all_paused` | Alle regler er på pause til {at}. Pausen avsluttes. | Avslutter pausen av alle regler (til {at}) | Ends the pause of all rules (until {at}) | 5 | words |
| `device.summary.qa.warn_already` | {rule} gjelder allerede til {at}. | {rule} gjelder til {at} | {rule} runs until {at} | 1 | words |
| `device.summary.qa.until_sub` | I dag, aldri over midnatt | - | - | 1 (the `past_midnight` refusal says it) | deleted |
| `device.summary.qa.extra_note` | Kommer i tillegg til dagens grense. | - | - | 1, 4 | deleted |
| `device.summary.qa.extra_used` | Kommer i tillegg til dagens grense. Brukt i dag: {used} av {budget}. | Brukt i dag: {used} av {budget} | Used today: {used} of {budget} | 3 (a quiet value) | words |
| `device.summary.qa.setup_sub` | Apper og Play Butikk er åpne | - | - | 4 (the row's text says it) | deleted |
| `device.summary.pause_ends_old` | Pausen slutter av seg selv; denne versjonen av Vibb One kan ikke låses igjen herfra. | Kan ikke låses igjen herfra | Can't be locked again from here | 3 | words |
| `device.summary.flash.extra` | Lagt til. Telefonen får det i løpet av sekunder når den er på nett. | Lagt til | Added | 4 | words |
| `device.summary.flash.pause` | Satt på pause. Regelen kommer tilbake av seg selv. | Satt på pause | Paused | 4 | words |
| `device.summary.flash.ended` | Avsluttet. Regelen er tilbake når telefonen synkroniserer. | Avsluttet | Ended | 4 | words |
| `device.summary.flash.on` | {rule} er slått på til {at}. Telefonen får det i løpet av sekunder når den er på nett. | {rule} på til {at} | {rule} on until {at} | 4 | words |
| `device.summary.flash.on_ended` | Avsluttet. Telefonen får det når den synkroniserer. | Avsluttet | Ended | 4 | words |
| `device.summary.flash.end_windows_sent` | Sendt. Telefonen låses igjen i løpet av sekunder når den er på nett. | Sendt | Sent | 4 | words |
| `device.summary.flash.start_pause_sent` | Sendt. Telefonen låses opp i 2 timer i løpet av sekunder når den er på nett. | Sendt | Sent | 4 | words |
| `device.summary.flash.allow_blocked_sent` | Sendt. Appene som er tillatt under Apper, vises igjen i løpet av sekunder når telefonen er på nett. | Sendt | Sent | 4 | words |
| `device.summary.flash.gone` | Den regelen gjelder ikke lenger for denne telefonen. Ingenting ble satt på pause. | Regelen gjelder ikke lenger | The rule no longer applies | 1 | words |
| `device.summary.flash.on_gone` | Den regelen gjelder ikke lenger for denne telefonen. Ingenting ble slått på. | Regelen gjelder ikke lenger | The rule no longer applies | 1 | words |
| `device.summary.foreign.sub_one` | {apps} kan styre telefonen eller lese skjermen, så telefonen avviser foreldre-PIN-en til appen er borte. | {apps} kan styre telefonen eller lese skjermen. | {apps} can control the phone or read its screen. | 5 (KEEP: the red title stays visible) | words |
| `device.summary.foreign.sub_many` | (as `.sub_one`, «appene») | {apps} kan styre telefonen eller lese skjermen. | {apps} can control the phone or read its screen. | 5 | words |
| `device.summary.foreign.refuses` | - | Telefonen avviser foreldre-PIN-en til appen er borte. | The phone refuses the Parent PIN until the app is gone. | 5 (the consequence, first in the ⓘ) | added |
| `device.summary.foreign.step_unlock` | Åpne telefonen med Lås opp 2 t nedenfor: ingen foreldre-PIN trengs. | Åpne telefonen med Åpne for oppsett under Avansert: ingen foreldre-PIN trengs. | Open the phone with Open for setup on Advanced: no Parent PIN needed. | stale wording | words |
| `device.summary.foreign.which` | Hvilke apper | (aria-label of the merged ⓘ: replaced by `common.how_to_fix`) | - | 2 | deleted |
| `device.summary.access.footer` | Skriv inn koden under Innstillinger i Vibb One på telefonen, eller skann QR-koden under oppsettet. En ny kode erstatter den forrige. | (moved, unchanged: a footer ⓘ alone) | (moved) | 2 | moved |
| `device.summary.access.paused_sub.*`, `device.summary.access.qr_works` | (see Telefon › Avansert, which shares the split) | - | - | 5 | see Avansert |
| `device.summary.no_parent_pin_sub` | Uten den kan ingenting åpnes eller oppheves på selve telefonen. | - (the summary's card is a link: title and chevron only, QA M7; the Lock page's own `device.lock.no_parent_pin_sub` stays visible there, KEEP) | - | 5 | deleted |
| `device.summary.relock.footer` | Det telefonen fant da den ble låst igjen. Vises i en uke. | (moved, unchanged: a footer ⓘ alone) | (moved) | 2 | moved |
| `device.summary.relock.turned_off_sub` | {names}. Slå dem på i telefonens innstillinger under en pause. | {names} | {names} | 3 | words |
| `device.summary.relock.turned_off_info` | - | Slå dem på i telefonens innstillinger under en pause. | Turn them on in the phone's Settings during a pause. | 3 (behind the row's ⓘ) | added |
| `device.summary.ask.pauses` | Knappene setter reglene på pause. | (moved, unchanged: one ⓘ after the card's sub-line) | (moved) | 2 | moved |
| `device.summary.ask.adds` | Ingen regel låser nå: knappene gir mer skjermtid. | (moved, unchanged: the same ⓘ) | (moved) | 2 | moved |
| `device.summary.picture_note` | Bildet vises bare for foreldrene. | - | - | 2, 4 | deleted |
| `device.summary.report_age` | Ifølge telefonens siste rapport, {age}. | Siste rapport {age} | Last report {age} | 3 | words |

Template (`device_detail.html`):
- Oppsett card (QA L8: words only - design 114 replaces this card, so no struct change here): `SetupStep { id, label,
  sub }` stays; a step with a `sub` is `details.info.cell` with `summary.row` (label, the ⓘ icon) and the `sub` in the
  panel - the `note_row.html` shape (for `default_apps:*` the panel holds `default_apps_sub`, then `default_apps_sms`
  when SMS changed, as the sub carries them today); a step without a sub is a plain row. The footer becomes a footer ⓘ
  alone. `data-setup-step` stays.
- Link cards (QA M7): the no-Parent-PIN card (`<a … data-warning="no-parent-pin">`) shows its title and chevron only;
  the `#mobile-state` lines (`<a … data-mobile-line>`) render `line.title` only - the template drops `line.sub` there
  (their ⓘ text is on Mobilnett).
- Blocked apps: no footer sentence; when `relock.blocks.more` is set, one `details.info.cell` row in the group,
  `summary.row` with the orange label (`text-alarm`) `blocked.more` and the ⓘ, panel `blocked.more_info`.
- Foreign access: the red title stays; `attention-sub` holds `sub_one/_many` and one `details.info` (aria
  `common.how_to_fix`) whose panel holds `foreign.refuses`, the packages (`packages_*`), then the `<ol>` of steps -
  the `<ol class="attention-steps">` moves into the panel.
- Access: `no_parent_pin` card: title and chevron only (a link, M7); paused:
  title + `attention-sub` `device.summary.access.qr_works` + ⓘ with `paused_sub` (as Avansert); footer ⓘ alone.
- Quick actions: the setup-open row without `row-sub` when `lock_again`, with `pause_ends_old` as the `row-sub` when
  not; both «Til» rows without `row-sub`; `pause_note` `<p>` goes; the `extra_note` `<p>` only when it is `Some`.
- Ask card: the two `ask-sub` sentences go into one `details.info` after `ask.sub` (aria `common.more_info`).
- Relock: the first row's label is the trigger (`f.trigger`, e.g. «Tiden var ute») with the time as value, and
  `relock.at` only when there is no trigger; the turned-off row becomes `details.info.cell` with
  `relock.turned_off_info` in its panel; footer ⓘ alone.
- Picture menu: the `pic-note` `<p>` goes.

State logic:
- `quick_actions.rs` (`QuickActions.extra_note` → `Option<String>`): `Some(extra_used)` when the screen-time state has a
  budget, else `None` (the line is gone).
- `quick_actions.rs` setup row: `sub` → `Option<String>`: `None` when `lock_again`, `Some(pause_ends_old)` otherwise.
- `airplane_mode::setup_steps`: unchanged (L8); only `wifi_sub`'s words change.
- `quick_actions.rs`: the dropped parameters - `mode_line`/`mode_line_none` and `warn_setup`/`warn_setup_plain` lose
  `{rule}` - leave the call sites' argument lists too (`quick_actions.rs:151-155, 465-471`, QA M9).

Tests:
- `tests/window_relock.rs`: line 286 "Apps and Play Store are open" → assert absent; 331 `pause_ends_old` text → "Can&#39;t
  be locked again from here"; 455 "Sent. The phone locks again within seconds" → "Sent"; 514/536 "And 17 more the phone
  doesn&#39;t list" → "17 more the phone doesn&#39;t show"; 899 "Spy &#60;i&#62;Kit can control the phone or read its
  screen" still matches (the sentence now ends there).
- `tests/play.rs` 523/532 "Changes may arrive late": still in the HTML (now in the step's ⓘ panel) - no change needed.
- `tests/lock_now.rs` 406 "The phone locks as in Skole: org.fossify.clock work." → "org.fossify.clock and emergency
  calls work."
- `tests/time_request.rs` 799/840: the two ask sentences unchanged (now in the ⓘ panel) - still match.
- `tests/time_rules.rs` 683 "That rule no longer applies to this phone. Nothing was paused." → "The rule no longer
  applies".
- `report_age`'s text is pinned in `tests/window_relock.rs` (QA M9): "As of the phone's last report, …" → "Last report
  …" (the `data-report-age` hook stays).
- `tests/airplane_mode.rs` 335 above: with L8 the "Changed during setup: …" sentence stays (now in the step's ⓘ panel) -
  `contains` holds.

## A phone's pages

### Om telefonen (`device_about.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.about.connection_footer` | På nett betyr at telefonen har en direkte forbindelse til denne serveren. Endringer når den i løpet av sekunder. | (moved, unchanged) | (moved, unchanged) | 2 | moved: first `<p>` of the Connection footer (i) |
| `device.about.crashes_footer` | Vibb One starter på nytt av seg selv etter et krasj. En rapport inneholder bare hvor i koden det skjedde. | (moved, unchanged) | (moved, unchanged) | 2 | moved: a new footer (i) under Diagnostics, aria `common.more_info` |
| `device.about.at_last_report` | Ved siste melding. | - | - | 4 | deleted |

Template:
- Connection: `<div class="group-footer">` keeps only the `<details class="info">` (aria `device.about.connection_info_label`); panel `<p>connection_footer</p><p>connection_info</p>`.
- Diagnostics: `{% if !crashes.is_empty() %}` the footer becomes P2 (i) alone with `crashes_footer`.
- Sound: the `<p class="group-footer">` goes.

### Aktivitet (`device_activity.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.activity.footer` | De siste 7 dagene, nyeste først. | - | - | 4 | deleted |

### Hjem-skjerm (`device_home.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.home.quick_footer` | Vises når barnet sveiper til venstre på Hjem-skjermen. | (moved, unchanged) | (moved, unchanged) | 2 | moved: first `<p>` of the quick controls (i) |
| `device.home.quick_footer_shortcuts` | Bryterne gjelder både Innstillinger og Snarveier. | (moved, unchanged) | (moved, unchanged) | 2 | moved: same panel, still only with `shortcuts_row` |
| `device.home.quick_mobile` | Mobildata-bryteren styres under Mobilnett. | (moved, unchanged) | (moved, unchanged) | 2 | moved: same panel |
| `device.home.bluetooth_any_sub` | Av: bare hodetelefoner og høyttalere kan pares | (moved, unchanged) | (moved, unchanged) | 1 (S-L5: meaning stays in the (i)) | moved: same panel, after `quick_info` |
| `device.home.shortcuts_hidden` | {name} har skjult Snarveier i varsellisten. {name} kan vise dem igjen under Innstillinger › Snarveier. | Skjult av {name} | Hidden by {name} | 3 | words |
| `device.home.shortcuts_chose` | {name} har valgt: {items} | - | - | 3 (quiet value: the items alone) | deleted; state: `shortcuts::home_sub` returns the `names` string itself |
| `device.home.shortcuts_none` | Ingen snarveier vises nå. | Ingen | None | 3 | words |
| `device.home.shortcuts_info` | Snarveier er et fast varsel øverst i varsellisten med inntil fire knapper. … viser den også tiden som er igjen. | Snarveier er et fast varsel øverst i varsellisten med inntil fire knapper. {name} velger dem blant bryterne her og lommelykten, og kan skjule dem og vise dem igjen under Innstillinger › Snarveier. Det vises aldri på låseskjermen. Når {name} har skjermtid i dag, viser den også tiden som er igjen. | Shortcuts is a fixed notification at the top of the notifications with up to four buttons. {name} picks them from the switches here and the flashlight, and can hide and show them again under Settings › Shortcuts. It never shows on the lock screen. When {name} has screen time today, it also shows the time left. | 3 (the how-to of `shortcuts_hidden`) | words |
| `device.home.airplane_no_role` | Vises for {name} når Vibb One er valgt som digital assistent. Se Oppsett på telefonsiden. | Vibb One er ikke digital assistent | Vibb One isn't the digital assistant | 3, 5 | words (both catalogs drop `{name}`); state: `airplane_mode::home_sub` (orange, only with bit 64 on) |
| `device.home.airplane_blocked` | Flymodus er blokkert under Avansert › Begrensninger. | Blokkert under Begrensninger | Blocked under Restrictions | 3 | words |
| `device.home.airplane_offline` | Uten Wi-Fi i flymodus er telefonen helt uten nett når flymodus er på. | Helt uten nett i flymodus | Fully offline in airplane mode | 3 (KEEP: short, visible) | words |
| `device.home.airplane_info` | Flymodus slår av mobilnettet. Nødsamtaler virker fortsatt, og etter en nødsamtale kan barnet ikke slå på flymodus igjen med en gang. | Flymodus slår av mobilnettet. Nødsamtaler virker fortsatt, og etter en nødsamtale kan barnet ikke slå på flymodus igjen med en gang. Bryteren vises for barnet når Vibb One er valgt som digital assistent (under Oppsett). Uten Wi-Fi i flymodus er telefonen helt uten nett mens flymodus er på. | Airplane mode turns off the mobile network. Emergency calls still work, and right after one the child can't turn airplane mode back on. The switch shows for the child once Vibb One is chosen as the digital assistant (under Setup). Without Wi-Fi in airplane mode the phone is fully offline while airplane mode is on. | 3 (the how-to and the why of the sub-lines; QA L8: «under Oppsett», not a card 114 removes) | words |
| `device.home.layout_footer` | Språket i Vibb Ones egne skjermer på telefonen. Klokkeformatet gjelder hele telefonen: … | (moved, unchanged) | (moved, unchanged) | 2 | moved: P2 (i) alone under Språk/klokke, aria `common.more_info` |
| `device.home.no_notification_access_sub` | Antall uleste og musikkortet er av, og låseskjermen viser bare tapte anrop. | (moved, unchanged) | (moved, unchanged) | 5 | moved: P1 (i), before `_fix` |
| `device.home.clock_not_applied_sub` | Telefonen kan ikke endre klokkeformatet ennå. | - | - | 4 (restates the title) | deleted; `device_pages::clock_format_note` gives `detail: None`, `_fix` alone in the (i) |
| `device.home.wallpapers_footer.before` | Barnet velger blant disse på telefonen. Legg til bilder under | Flere bilder: | More photos: | 4 | words |
| `device.home.wallpapers_footer.after` | . | - | - | 4 | deleted (template drops it) |
| `device.home.language_system` | Samme som telefonen | Som telefonen | Same as the phone | 1 (one word for "system") | words |
| `device.home.clock_system` | Følg telefonen | Som telefonen | Same as the phone | 1 | words |

Template:
- Quick controls footer: `<div class="group-footer">` keeps only the `<details class="info">` (aria `device.home.quick_info_label`); panel order: `<p>quick_footer {shortcuts: quick_footer_shortcuts} quick_mobile</p>`, `shortcuts_info` (with shortcuts_row), `quick_info`, `<p>bluetooth_any_sub</p>`, `airplane_info` (with airplane_row). The Bluetooth row loses its `row-sub`.
- Snarveier row: `data-shortcuts-sub` stays (the quiet value); the `data-shortcuts-footer` span moves into the panel.
- Flymodus row: `<span class="row-sub{% if warn %} tone-orange{% endif %}" data-airplane-sub>`.
- Språk/klokke: footer -> P2 (i) alone.
- Bakgrunnsbilder: `<p class="group-footer">{before} <a href="/wallpapers">{link}</a></p>`.

State logic:
- `shortcuts::home_sub`: hidden -> «Skjult av {name}»; bit off -> `None` (unchanged); chosen -> the item names joined (no key); none -> «Ingen».
- `airplane_mode::home_sub` returns `Option<(String, bool)>` (QA M6): `no_role` is a part only when bit 64 is on and
  `!report.assistant` - with bit 64 off nothing is said about the role (the (i)'s sentence says when the switch shows);
  the bool (orange) is true exactly when `no_role` is among the parts; blocked and offline alone are grey. The parts
  still join with " · " (was " ").

Tests:
- `tests/shortcuts.rs::the_home_row_says_what_the_kid_did` (hidden en/nb, none en/nb, chose nb «har valgt:»), `::the_home_form_keeps_bit_128_without_its_row` («har valgt:», `quick_footer_shortcuts` now inside the (i) - an `contains` still holds, a position check doesn't).
- `tests/airplane_mode.rs::the_home_form_keeps_bit_64_without_its_row` (no_role en/nb, blocked, offline texts; add a check for `tone-orange` on no_role; add the row: bit 64 off, no assistant -> no `no_role` text and no `tone-orange`).
- `tests/launcher_ui.rs::clock_format_form_keeps_a_missing_field` and the other `clock_format_*` tests: "Follow the phone" -> "Same as the phone"; `::clock_format_not_applied_is_a_warning`: no `_sub` text.
- `models::command_label` mentions "Follow the phone" (check: if it is a test string, update).

### Apper (`device_apps.html`, `partials/device_app_row.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.apps.preinstalled` | Forhåndsinstallert | - | - | 5 | deleted; state: `devices::app_rows` |
| `device.apps.from_play` | Installert fra Play | - | - | 5 | deleted; state: `devices::app_rows` |
| `device.apps.installed` | Installert | (unchanged; still used by the launcher row's fallback, `device_pages`, music) | | 5 | state: `devices::app_rows` stops using it for a plain installed app |
| `device.apps.footer` | Slår du av en app, skjules den, og den avinstalleres der det går. | (moved, unchanged) | (moved, unchanged) | 2 | moved: first `<p>` of its (i) (`device.apps.info`) |
| `device.apps.downloads_footer` | Slå den av når en app trengs nå. | (moved, unchanged) | (moved, unchanged) | 2 | moved: first `<p>` of `downloads_info`'s (i) |
| `device.apps.corrupt_sub` | Telefonen beholder den siste listen den hadde, og å slå apper av eller på her mislykkes til det er fikset. | (moved, unchanged) | (moved, unchanged) | 5 (KEEP the title) | moved: P1 (i), before `corrupt_fix` |
| `apps.dialer.managed_sub` | Skjult på telefonen - barnet ringer fra Telefonbok på Hjem | - | - | 5 (the label «{app} → Vibbs telefonbok» says it) | deleted; state: `device_pages::render_apps` (status_label empty) |
| `apps.dialer.off_sub` | Skjult på telefonen - bare nødnumre kan ringes | Bare nødnumre | Emergency numbers only | 1 (KEEP: short form) | words |
| `apps.builtin.part_of_vibb` | En del av Vibb One | - | - | 5 | deleted; state: `device_pages::render_apps` (Meldinger, Kalender rows) |
| `apps.builtin.sms_unmanaged` | SMS er ikke styrt av Vibb | SMS ikke styrt av Vibb | SMS not managed by Vibb | 3 | words |
| `logins.apps.chrome_row` | Brukes bare til innlogging (Innlogging-siden) | Bare innlogging | Sign-in only | 1 | words |
| `device.apps.refused_dialer` | Samtaler er styrt av Vibb: Telefon-appen følger Vibbs telefonbok. Endre det under Anrop og kontakter. | Styrt av Vibbs telefonbok - se Anrop og kontakter | Managed by Vibb's phone book - see Calls & Contacts | 3 | words |
| `apps.perms.update_note` | Oppdater Vibb One for å styre appenes tillatelser | (unchanged) | (unchanged) | 5 (tone) | template: orange |

Template:
- `device_apps.html`: both footers -> P2 (i) alone (the sentence first in the existing panel). The update note: `<p class="row row-note tone-orange" data-row="update-note">`.
- `device_app_row.html`: the first `row-sub` renders only when there is something: `own_label`, a non-empty `status_label`, or `show_no_release_hint`; the parts join with " · " only between non-empty parts (today `{own} · {status}` would leave a dangling " · ").

State logic:
- `devices::app_rows` (first pass): `status_label` is `""` for a preinstalled app, a Play-installed app and a plain installed one; an app with a waiting/refused update keeps `app_downloads::update_label`. Installing, failed, not installed, «Ingen utgivelse ennå» unchanged. The launcher row keeps its download line (fallback «Installert» unchanged).
- `device_pages::render_apps`: the managed dialer row's `status_label` is `""` (off: «Bare nødnumre», grey as today); Meldinger's is `""` when `sms_mode == "managed"`, else «SMS ikke styrt av Vibb»; Kalender's is `""`.

Tests:
- `tests/play.rs::status_stores_install_mode_installer_and_logs`: "Installed from Play" goes (assert its absence or the row without a sub).
- `tests/app_display.rs::meldinger_has_a_row_and_a_name_and_icon_of_its_own` (l.581/630/662: "Part of Vibb One · SMS is not managed by Vibb" -> "SMS not managed by Vibb"; "Part of Vibb One" -> no sub; "Messages · Part of Vibb One" -> "Messages"), `::an_apps_row_shows_the_name_the_kid_sees` ("Installed").
- `tests/calendar.rs::every_calendar_route_needs_a_session` (l.1353 "Part of Vibb One").
- `tests/dialer_row.rs::the_managed_row_links_to_calls` (managed_sub en/nb gone; off_sub -> "Emergency numbers only"/«Bare nødnumre»), `::a_managed_dialer_is_never_changed` (refused_dialer en/nb).
- `tests/install_now.rs::the_answer_shows_in_the_row`, `tests/tracked_apps.rs::app_downloads_are_stored_and_shown`, `tests/lock_glance.rs::a_reserved_name_is_never_an_installed_app`, `tests/intake_limits.rs::a_status_report_is_stored_within_limits`: check each "Installed" - a row label of a plain installed app is now empty.
- `tests/device_pages.rs` l.2193/2199 (`data-row="update-note"`): hook unchanged.

### Navn og ikon (`partials/app_display_form.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `apps.display.label_field` | Navn på telefonen (høyst 20 tegn; tomt = appens eget) | Navn | Name | 1 (the placeholder shows the app's own; `error.label_long` says the limit) | words |
| `apps.display.colour` | Farge bak ikonet | Farge | Colour | 1 | words |
| `apps.display.catalog_default` | Katalogens standard: {default}. | (unchanged) | (unchanged) | 5 | state: `device_pages::render_apps` |

State logic:
- `device_pages::render_apps` (l.2345): `form.catalog_default` is set only when `form.can_reset()` (the phone has its own value, so Tilbakestill is offered); else `None`. The catalog app page (`tracked_apps`) never sets it - unchanged.

### Tillatelser (`partials/app_permissions_form.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `apps.perms.camera_help` | Av: {app} kan ikke bruke kameraet - en videosamtale eller QR-skanning slutter å virke. Å slå det av lukker {app} … | (moved, unchanged) | (moved, unchanged) | 1 | moved: the Permissions (i) |
| `apps.perms.lock_media` | Vis på låseskjermen som mediespiller | Mediespiller på låseskjermen | Media player on the lock screen | 1 | words |
| `apps.perms.lock_media_help` | Mens appen spiller, vises tittel og bilde på låseskjermen - før koden er tastet. … | (moved, unchanged) | (moved, unchanged) | 1 (KEEP "before the code is typed" in the (i)) | moved: the Permissions (i) |
| `apps.perms.how`, `apps.perms.how_alarm` | På telefonen: Innstillinger (foreldrekode) › … | (moved, unchanged) | (moved, unchanged) | 3 | moved: each unapproved access row's (i) |
| `apps.perms.camera_paused` | Kamera av · på igjen mens begrensningene er satt på pause | Kamera av · pause | Camera off · paused | 3 | words |

Template:
- The camera and lock-media rows lose their `row-sub`; after the last form, inside `details.app-display`, one P2 footer (i) (aria `common.more_info`) whose panel holds `<p>camera help</p>` (when `perms.camera`) and `<p>lock_media_help</p>` (when `perms.lock_media`).
- An access row with `how`: `<details class="info cell"><summary class="row"><span class="row-label tone-orange">{text}</span>(i icon)</summary><div class="info-panel"><p>{how}</p></div></details>`; an approved access stays a plain row (quiet «… : godkjent»).

Tests:
- `app_permissions::tests::the_line_and_the_details_say_what_the_report_says` ("Camera off · back on while res…" -> "Camera off · paused").
- `tests/device_pages.rs::the_permissions_form_returns_to_its_open_details` ("Show as a media player on the lock screen" -> "Media player on the lock screen"); `models::command_label` quotes the same label (check whether it is a test string).

### Anrop og kontakter (`device_calls.html`)

P1 does the warnings: every `Finding::note` warning shows its orange title (`device.calls.problem.*`, all already short and readable alone) and its `device.calls.finding.*` sentence moves behind the (i) with the fix - those keys are `moved`, not listed one by one. KEEP visible: `emergency_call`, `callback`, `screen_failed` put their sentence in `Note.sub` (state: `calls::Finding::note`); so does the needs-update finding (QA M5, D1).

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.calls.finding.*` (all but the three below) | e.g. Den kan bare ringe nødnumre, og skjulte numre kan ringe gjennom. Åpne Hjem-skjermen … | (moved, unchanged) | (moved, unchanged) | 5 | moved: P1 (i) |
| `device.calls.finding.emergency_call` | Telefonen ringte et nødnummer ({at}). | (unchanged) | (unchanged) | KEEP | state: `calls::Finding::note` -> `Note.sub` |
| `device.calls.finding.callback` | Alle kan ringe denne telefonen til {until}, så nødetatene kan ringe tilbake. | Alle kan ringe til {until}. | Anyone can call until {until}. | KEEP, shortened to a `sub` | words; state: `calls::Finding::note` -> `Note.sub` |
| `device.calls.finding.screen_failed` | Et anrop ringte ({at}), men kunne ikke vises over låsen, så barnet kan ha vært ute av stand til å svare. | Ringte {at}, men ble ikke vist over låsen. | Rang {at} but didn't show over the lock. | KEEP, shortened to a `sub` | words; state: `calls::Finding::note` -> `Note.sub` (`_fix` stays in the (i)) |
| `device.calls.channel.calls_off` … `sms_unmanaged` (6 keys) | Bare kontaktene du har tillatt kan ringe og bli ringt. / SMS er av. Meldinger til telefonen går tapt. … | (moved, unchanged) | (moved, unchanged) | 1 (KEEP «SMS er av. Meldinger … går tapt» in the (i)) | moved: the chosen modes' two sentences become the first `<p>`s of the calls (i) |
| `device.calls.footer` | Når det styres, kan bare kontaktene nedenfor ringe eller sende meldinger. Nødnumre virker alltid. | (moved, unchanged) | (moved, unchanged) | 2, 4 (KEEP `device.calls.info`'s ##004#/1881/1880) | moved: the calls (i), after the mode lines |
| `device.calls.mode.recommended` | anbefalt | - | - | 1 | deleted; state: `calls::channel_row` (`note: None`) |
| `device.calls.contacts_footer` | Navnet og bildet til en kontakt (opptil 10 MB) deles av alle telefoner med det nummeret. | (moved, unchanged) | (moved, unchanged) | 2 | moved: Contacts' footer (i) alone |
| `device.calls.photo_privacy` | Serveren tar også vare på en mindre kopi av originalbildet, … Telefonene får bare utsnittet. | (moved, unchanged) | (moved, unchanged) | 2 (KEEP the copy/backup fact) | moved: same panel |
| `device.emergency.footer` | Barnet får menyen når det trykker Nødsamtale; ett trykk ringer. … Høyst 6. | (moved, unchanged) | (moved, unchanged) | 2 (KEEP «andre nummer … bare når telefonen er låst opp») | moved: Nødmeny's footer (i) alone |
| `device.emergency.big` | Øverst og størst på telefonen | - | - | 5 | deleted; state: `calls::emergency_rows` |
| `device.emergency.always` | Nødnummer: virker alltid | - | - | 5 | deleted; state: `calls::emergency_rows`, template (suggestions) |
| `device.emergency.not_emergency` | Ikke et nødnummer for serveren: følger anropsreglene (telefonen kan likevel kjenne det som nødnummer) | Ikke et nødnummer - følger anropsreglene | Not an emergency number - follows the call rules | 3 (KEEP: orange, visible) | words |
| `device.emergency.not_in_phone_book` | Ikke i telefonboken - nedtonet på telefonen | Ikke i telefonboken | Not in the phone book | 3 | words |
| `device.sms.held.footer` | Meldinger fra avsendere du ikke har tillatt, venter på telefonen i et døgn uten å vises. … | (moved, unchanged) | (moved, unchanged) | 2 | moved: Meldinger som venter's footer (i) alone |
| `device.calls.sub.calls_off` | Samtaler er av - bare nødnumre kan ringes | Samtaler av | Calls off | 1 | words |
| `device.calls.sub.calls_unmanaged_in_book` | Samtaler er ikke styrt - alle kan ringe. {contact} står i telefonboken | - | - | 1, 4 | deleted; state: `calls_form::subs` |
| `device.calls.sub.calls_unmanaged_not_in_book` | Samtaler er ikke styrt - alle kan ringe. {contact} står ikke i telefonboken | - | - | 1, 4 | deleted; state: `calls_form::subs` |
| `device.calls.sub.calls_applies` | Gjelder når samtaler eller SMS er styrt av Vibb | - | - | 1 | deleted; state: `calls_form::subs` |
| `device.calls.sub.sms_applies` | Gjelder når SMS er styrt av Vibb | - | - | 1 | deleted; state: `calls_form::subs` |
| `device.calls.sub.not_managed` | - | Ikke styrt | Not managed | 1 | added |
| `device.calls.sub.sms_off` | - | SMS av | SMS off | 1 (QA M2: under Off SMS is off, not unmanaged) | added |
| `device.calls.sub.pictures_no_texts` | Gjelder når {kid} og {contact} sender meldinger | Meldinger av | Texts off | 1 | words (both catalogs drop `{kid}`/`{contact}`) |
| `device.requests.footer` | {name} har bedt om disse fra telefonboken. Ingenting legges til før du lagrer kontakten selv. … | (moved, unchanged) | (moved, unchanged) | 2 | moved: Forespørsler' footer (i) alone |
| `device.sms.blocked.footer` | De siste 30 dagene. Disse avsenderne kan ikke tillates: … | (moved, unchanged) | (moved, unchanged) | 2 | moved: Blokkerte meldinger' footer (i) alone |
| `device.sms.settings.groups_sub` | Bare når alle andre i gruppen er kontakter med meldinger slått på, og telefonens nummer er kjent. | (moved, unchanged) | (moved, unchanged) | 1 | moved: the SMS settings footer (i) |
| `device.sms.settings.footer` | Dette gjelder når SMS er styrt av Vibb. Uten telefonens eget nummer blokkeres gruppemeldinger. | (moved, unchanged) | (moved, unchanged) | 2, 4 | moved: same panel |
| `device.sms.own.unknown_hint` | Telefonen vet ikke nummeret sitt ennå. Uten det blokkeres gruppemeldinger. | Telefonen vet ikke nummeret sitt ennå. | The phone doesn't know its number yet. | 4 (the orange line under SMS says the consequence) | words |
| `device.sms.emergency_reason` | Nødnumre kan ikke sende meldinger hit - alle kan utgi seg for dem. | Nødnumre kan ikke sende hit - alle kan utgi seg for dem. | Emergency numbers can't text here - anyone can pretend to be one. | 4 (KEEP the anti-spoofing line, once) | words; template: once |
| `device.requests.matrix_hint` | {name} foreslo {id}. Meldingsknappen åpner Element X med den. Hvem som helst kan stå bak en Matrix-ID: sjekk at det er riktig person. | {name} foreslo {id} - sjekk at det er riktig person. | {name} suggested {id} - check it's the right person. | 1 (KEEP the impersonation caution) | words |
| `device.calls.mmsc.full` | Åtte adresser er tillatt for dette SIM-kortet. Fjern én nederst på siden for å tillate denne. | Fullt: 8 av 8 adresser | Full: 8 of 8 addresses | 3 | words |
| `device.calls.mmsc.gone` | Bildet er ikke lenger tilgjengelig. Neste bilde hentes hvis du tillater adressen. | Bildet er borte | The picture is gone | 3 | words |
| `device.calls.mmsc.more` | Mer | (unchanged, aria-label only) | (unchanged) | 2 | template |
| `device.calls.number_hint` | Uten landskode brukes +{code} (Innstillinger › Telefonoppsett). | Uten landskode: +{code} | No country code: +{code} | 1 | words |
| `device.requests.short_choose` | Kortnummer - kan koste penger. Velg selv om {name} skal kunne ringe og sende melding til det. | Kortnummer - kan koste penger. | Short number - may cost money. | 1 (KEEP the cost warning) | words (both catalogs drop `{name}`) |
| `device.calls.family_hint` | En kontakt fra en annen telefon: navnet, nummeret og bildet er felles. | - | - | 2 (the shared fact is in Contacts' (i)) | deleted |
| `device.calls.none` | Ingen kontakter ennå. Når anrop styres, virker bare nødnumre. | Ingen kontakter ennå. | No contacts yet. | 4 | words |
| `device.sms.names.none` | Ingen avsendernavn. Meldinger fra navn som Posten eller DNB venter på deg. | Ingen avsendernavn. | No sender names. | 3 | words |
| `device.sms.names.hint` | Nøyaktig slik det vises på en telefon, 1-11 tegn med en bokstav. Store og små bokstaver er det samme. | 1-11 tegn, som på telefonen | 1-11 characters, as on the phone | 1 | words |

Not touched (the user's call): the Avansert summary (`adv_emergency`, `adv_messages`, its `number_missing` line); `device.calls.number_missing` stays where it is.

Template:
- Channels: `<span class="row-sub segmented-sub">{{ channel.sub }}</span>` goes. The calls footer becomes P2 (i) alone (aria `device.calls.info_label`), panel: `<p>{calls channel.sub} {sms channel.sub}</p><p>device.calls.footer</p><p>device.calls.info</p>` (the chosen modes' sentences, so the (i) says what each choice does now). The RCS line, the number line and `update_first` stay (states).
- Contacts: `<p class="group-footer">contacts_footer photo_privacy</p>` -> P2 (i) alone, two `<p>`.
- Nødmeny: footer -> P2 (i) alone. Rows: `{% if let Some(sub) = row.sub %}<span class="row-sub tone-orange">` (only the warn row); suggestions (l.546) lose `device.emergency.always`.
- Held, Blocked, Requests, SMS settings: each footer -> P2 (i) alone; the Gruppemeldinger row loses `groups_sub` (it becomes the panel's first `<p>`, then `settings.footer`). The `groups_update_first` orange hint stays.
- `device.sms.emergency_reason`: shown on the main «… kan sende melding til …» row only; the Avansert one-way row (l.315, `texts_in_refused`) drops it.
- RCS row in Avansert (l.646): the label alone, its `row-sub` (`device.calls.channel.sms_rcs`) goes (the key stays: the channel's 14-day line uses it).
- Bildeadresser' "Mer": `<summary class="row">` becomes the (i) icon alone (`summary aria-label="{{ device.calls.mmsc.more }}"` with the info-icon svg, as a field (i)); the panel unchanged.
- Add Contact family form: the `family_hint` `<p>` goes.

State logic:
- `device_pages::Note` gains `sub: Option<String>`; `calls::Finding::note` puts `emergency_call`, `callback`, `screen_failed` and the needs-update finding in `sub` (visible under the orange title), every other finding's sentence in `detail` (in the (i)).
- `calls::channel_row`: `Segment.note` is always `None`; `ChannelRow.sub` stays (now read only by the (i)).
- `calls::emergency_rows`: `EmergencyRow.sub` becomes `Option<&'static str>`, `Some("device.emergency.not_emergency")` only when `!real` (orange); `extra` (`not_in_phone_book`) unchanged.
- `calls_form::subs`: `calls`: managed -> `None`; unmanaged (any sms mode) -> «Ikke styrt»; off -> «Samtaler av». `texts` (QA M2): `unmanaged` -> «Ikke styrt», `off` -> «SMS av» (`device.calls.sub.sms_off`), `managed` -> `None`. `pictures`: `None` when `sms_mode != "managed"` (said once, on Meldinger); else «Meldinger av» when `!texts_any`. `home` unchanged.

Tests:
- `tests/calls.rs::calls_page_shows_role_problems_and_fail_closed`, `::allowlisted_messages_app_with_sms_off_is_flagged`, `tests/catalog_pins.rs::a_new_phone_keeps_its_phone_and_sms_apps`, `tests/sms.rs::a_stored_pair_an_older_launcher_cant_do_is_explained`, `tests/sms_page.rs::the_pools_and_the_findings`: finding texts now inside `<div class="info-panel">` - `contains` holds; any check of `attention-sub">…` text fails: update to the panel.
- `tests/sms.rs::the_calls_page_saves_both_channels` (channel sub texts now in the (i)); `::the_calls_page_gates_what_an_older_launcher_cant_do` (l.742 `segment-note">recommended` -> no note; l.698 already asserts none).
- `tests/emergency_menu.rs::adding_keeps_112_first_and_normalises_the_number` (big/always gone; not_emergency new text), `::a_number_outside_the_phone_book_says_it_is_dimmed` (-> "Not in the phone book").
- `calls_form::tests::the_sub_lines_follow_the_modes`, `tests/calls_simpler.rs::the_sub_lines_follow_the_channel_modes`: every row of the new `subs`, with the M2 row (`sms_mode` off -> `texts` «SMS av» / "SMS off").
- Dropped parameters (QA M9): `device.calls.sub.pictures_no_texts` loses `{kid}`/`{contact}` and
  `device.requests.short_choose` loses `{name}` - the call sites' argument lists go too (`calls_form.rs:211`, the
  contact-request prefill).
- QA M5: `calls::Finding::note` for the needs-update finding puts its sentence (`device.calls.finding.needs_update` /
  `_sms` / `_calls`) in `Note.sub` (visible); a test asserts it outside the `info-panel`.
- `tests/calls_simpler.rs::the_page_keeps_on_view_what_needs_a_decision`, `tests/sms_page.rs::the_rcs_step_shows_before_and_under_managed`: `sms_rcs` gone from the Avansert row (still on the channel for 14 days).
- `tests/launcher_ui.rs::photo_is_reencoded_and_reaches_the_device` (names `device.calls.photo_privacy`: still rendered, in the panel).
- `tests/contact_requests.rs::the_prefill_and_retry_rules` ("may call and text it." -> gone; `data-request-short` hook stays), `::add_from_a_request` (matrix hint).
- `tests/mmsc_hosts.rs` l.321 (`class="info cell" data-mmsc-more>` - the summary's markup changes).
- A test for `screen_failed` / `callback` text (grep "couldn't show over the lock", "so the emergency services can call back") - none found; add one for `Note.sub` visible.

### Låseskjerm og PIN-koder (`device_lock.html`)

P1 does the lock notes (`kid_lock.rs`, `device.lock.note.*_sub` -> the (i)) and the lock-glance notes (`lock_glance.rs`, `device.lock.glance.*_sub` -> the (i)); their titles read alone, except `needs_update` and `bad_hash`, which get the short visible `sub` of D1 (QA M5: `device.lock.note.needs_update_short`, `bad_hash_short`). Those `_sub` keys are `moved`.

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.lock.note.*_sub` (warnings), `device.lock.glance.no_access_sub`, `.no_access_messages_sub`, `.needs_update_sub`, `.no_child_pin_sub` | e.g. Fjern den så Vibb Ones lås kan ta over: tast inn foreldre-PIN-en … | (moved, unchanged) | (moved, unchanged) | 5 | moved: P1 (i) |
| `device.lock.notifications_footer` | Bare navn: avsenderens navn for chatter, … innloggingskoder på SMS inkludert, som alle som holder telefonen, kan lese. … | (moved, unchanged) | (moved, unchanged) | 2 (KEEP the SMS-code warning, in the (i)) | moved: Varsler' footer (i) alone |
| `device.weather.lock_sub` | Yr-symbol og temperatur ved datoen; trykk for dagen. Fra MET Norge, for der telefonen er (omtrent 2 km). | - | - | 1 | deleted (into `device.weather.info`) |
| `device.weather.info` | - | Yr-symbol og temperatur ved datoen; trykk for dagen. Været gjelder en rute på omtrent 2 km der telefonen er, aldri den nøyaktige posisjonen. | Yr's symbol and the temperature by the date; tap for the day. The weather is for a square of about 2 km where the phone is, never the exact position. | 1, 4 (2 km said once) | added |
| `device.weather.zone` | Når posisjonen ikke er fersk, bruk | Reservesone | Fallback zone | 1 | words |
| `device.weather.zone_sub` | Posisjonen er fersk når Posisjon står på Jevnlig. Ellers gjelder været for denne sonen. | (moved, unchanged) | (moved, unchanged) | 1 | moved: the weather (i) |
| `device.weather.footer` | Værvarsel fra MET Norge (CC BY 4.0), symboler © Yr/NRK. Været gjelder en rute på omtrent 2 km, aldri den nøyaktige posisjonen. | Værvarsel fra MET Norge (CC BY 4.0), symboler © Yr/NRK. | Forecast from MET Norway (CC BY 4.0), symbols © Yr/NRK. | KEEP (licence, visible); 4 | words |
| `device.lock.no_parent_pin_sub` | Uten den kan ingenting åpnes eller oppheves på selve telefonen. Hvis denne serveren går tapt, … | (unchanged, visible) | (unchanged) | KEEP (a lost server means a reset) | - |
| `device.lock.easy_sub` | Den er ett og samme siffer, en rekke som 123456, eller en vanlig PIN. … | (moved, unchanged) | (moved, unchanged) | 5 | moved: the alarm's (i) |
| `device.lock.used_offline_sub` | Tidslåsen ble opphevet med den siden telefonen sist sjekket inn. | (moved, unchanged) | (moved, unchanged) | 5 | moved: the alarm's (i) |
| `device.lock.footer_own`, `device.lock.footer_android` | Låser telefonen når skjermen slås av. / Androids lås: PIN-tastaturet, og fingeravtrykk hvis du legger til et. | (moved, unchanged) | (moved, unchanged) | 2 | moved: first `<p>` of the child-PIN (i) |
| `device.lock.child_info_own` | Etter 5 feil PIN-koder venter telefonen 30 s, … noen bankapper virker ikke med den. | Vibb Ones lås fases ut til fordel for Androids lås. Etter 5 feil PIN-koder venter telefonen 30 s, så lenger; foreldre-PIN-en din («Foreldrekode» på låsen) åpner den alltid. Den krypterer ikke dataene på telefonen, og noen bankapper virker ikke med den. | Vibb One's lock is being phased out for Android's lock. After 5 wrong PINs the phone waits 30 s, then longer; your Parent PIN ("Parent code" on the lock) always opens it. It doesn't encrypt the phone's data, and some bank apps won't work with it. | 1 (the phase-out leaves the option) | words |
| `device.lock.own_lock` | Vibb Ones lås (fases ut) | Vibb Ones lås | Vibb One's lock | 1 | words |
| `device.lock.screen_footer` | Med en barne-PIN låses telefonen når skjermen slås av. | - | - | 4 | deleted |
| `device.lock.wake_sub` | For appene på låsen nedenfor og tapte anrop | - | - | 1 | deleted (into `lock_screen_footer`) |
| `device.lock.buzz_sub` | Én kort vibrering, ingen lyd, for appene på låsen nedenfor og tapte anrop | - | - | 1 | deleted (into `lock_screen_footer`) |
| `device.lock.lock_screen_footer` | Det låsen viser, kan alle i nærheten av telefonen se. Et varsel kan sveipes bort der, … ingenting kan besvares. | Skjermen våkner og telefonen vibrerer kort, uten lyd, for appene på låsen og tapte anrop. Det låsen viser, kan alle i nærheten av telefonen se. Et varsel kan sveipes bort der, som på Androids egen låseskjerm; ingenting kan besvares. | The screen wakes and the phone buzzes once, no sound, for the apps on the lock and missed calls. What the lock shows, anyone near the phone can see. A notification can be swiped away there, as on Android's own lock screen; nothing can be answered. | 1, 2 | words; moved: Låseskjerm's footer (i) alone |
| `device.lock.remove_needs_pin` | Androids lås trenger en barne-PIN. Vil du bytte den, lagrer du en ny ovenfor. Vil du fjerne den, velger du Vibb Ones lås først. | Androids lås trenger en barne-PIN | Android's lock needs a Child PIN | 3 (the field above changes it) | words |
| `device.lock.parent_footer` | Åpner telefonens innstillinger og opphever grenser, også uten nett. | (moved, unchanged) | (moved, unchanged) | 2 | moved: first `<p>` of the parent-PIN (i) |
| `device.lock.parent_pin_hint` | 6 til 10 sifre, ikke barne-PIN-en. 8 eller flere er mye vanskeligere å knekke; … | 6-10 sifre, ikke barne-PIN-en. | 6-10 digits, not the Child PIN. | 1 | words |
| `device.lock.parent_pin_advice` | - | 8 eller flere sifre er mye vanskeligere å knekke; bursdager og årstall er lette å gjette. | 8 or more digits are much harder to crack; birthdays and years are easy to guess. | 1 | added (field (i)) |
| `device.lock.child_pin_hint` | 4 til 6 sifre; 4 er enklest for et barn. | 4-6 sifre | 4-6 digits | 1 | words |
| `device.lock.reveal_hint` | Det du logger inn her med. PIN-en vises bare på denne siden, i et minutt; tre feil passord logger deg ut. | Passordet du logger inn med. Tre feil passord logger deg ut. | The password you sign in with. Three wrong passwords sign you out. | 1 (KEEP «tre feil passord logger deg ut») | words |

Template:
- Child PIN: `<div class="group-footer">` keeps only the (i) (aria `device.lock.child_info_label`); panel `<p>footer_android|footer_own</p><p>child_info_android|child_info_own</p>` (KEEP `child_info_android`; `data-privacy` stays on the panel).
- Parent PIN: footer -> P2 (i) alone, panel `<p>parent_footer</p><p>parent_info</p>`. `easy` and `used_offline` alarms: title, then `<div class="attention-sub">` holding only a `details.info` with the `_sub` (as P1). `no_parent_pin` unchanged (KEEP, red, sub visible).
- Parent PIN field: after its `<label>`, a field (i) (`details.info.field-info`) with `device.lock.parent_pin_advice`.
- Screen: the `screen_footer` `<p>` goes.
- Låseskjerm: the wake and buzz rows lose their `row-sub` (the buzz `needs_update` orange hint stays); the footer becomes P2 (i) alone with `lock_screen_footer`.
- Weather: the switch row loses `lock_sub`, the zone row loses `zone_sub`; the footer is `<div class="group-footer">{weather.footer} <details class="info">` with panel `<p>device.weather.info</p><p>zone_sub</p>`. The licence credit is the one visible sentence (KEEP), the (i) beside it.
- Varsler: footer -> P2 (i) alone.

Tests:
- `tests/lock_glance.rs::the_page_lists_the_allowed_apps_with_their_ticks` (notifications_footer, now in the panel - `contains` holds), `::the_wake_switch_saves_logs_nudges_and_reaches_the_policy`, `::the_buzz_switch_saves_logs_nudges_and_reaches_the_policy` (wake_sub/buzz_sub gone), `::the_home_screen_warning_and_the_lock_page_name_the_lock_screen` (lock_screen_footer's new first sentence; the old text still follows).
- `tests/step10.rs::lock_page_warns_about_the_lock_state` (child_info_own now starts with the phase-out sentence; the old text still follows).
- `tests/android_lock_page.rs::a_new_phone_gets_androids_lock_once_the_child_pin_is_set`, `::the_child_pin_stays_while_androids_lock_is_chosen` ("Vibb One's lock (being phased out)"/«Vibb Ones lås (fases ut)» -> without the bracket; `data-privacy` hook unchanged; QA M9: l.662 pins `remove_needs_pin`'s full sentence on `data-row="remove-needs-pin"` -> "Android&#39;s lock needs a Child PIN").
- QA M5: a lock-page test asserts `needs_update_short` / `bad_hash_short` visible (outside the `info-panel`).
- The weather tests (grep `device.weather` / "When the position isn't fresh" in `tests/launcher_ui.rs`, `weather_page`): none found by text; check `tests/*weather*` for "about 2 km".

ASK 1 (an orange «Hele teksten viser også innloggingskoder» line under Varsler): dropped by the user - not built; the
SMS-code warning stays in Varsler' (i) (KEEP).

### Tidsregler, a phone's (`device_time.html`)

P1 does the warnings (`device.time.incoming_not_blocked_sub`, `too_old_sub` -> the (i), `moved`; `ledger_unreadable_sub` stays visible as the note's `sub`, QA M5).

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.time.rule_pause_info_label` | Om foreldrekoden (visible) | (unchanged, aria-label only) | (unchanged) | 2 | template |
| `device.time.own_footer` | Gjelder i tillegg til reglene for alle som er på. | (moved, unchanged) | (moved, unchanged) | 2 | moved: Own rules' footer (i) alone |
| `device.time.emergency` | Nødanrop virker alltid. | (moved, unchanged) | (moved, unchanged) | 4 (KEEP once per page, in the (i)) | moved: same panel |
| `device.time.own_off_footer` | En regel som er av, ble tatt vare på fra da telefonen fulgte reglene for alle: … | (moved, unchanged) | (moved, unchanged) | 2 | moved: same panel, still only with `own_off` |
| `device.time.everyone_footer` | Av betyr at regelen ikke gjelder for {name}. En regel som legges til senere, er på. | (moved, unchanged) | (moved, unchanged) | 2 | moved: Everyone's footer (i) alone |
| `device.time.everyone_link` | Endre reglene for alle | (unchanged; now a link row) | (unchanged) | 2 | template |
| `device.time.everyone_link_after` | under Innstillinger. | - | - | 4 | deleted |
| `device.time.limit_footer` | Minutter per dag. Tomt betyr ingen grense. | - | - | 4 (the unit and the placeholder say it) | deleted |
| `device.time.limit_off_footer` | Av: {name} har den daglige grensen for alle, satt under Innstillinger. | - | - | 4 (the «Grensen for alle» row says it) | deleted |
| `device.time.bedtime_footer` | Hver natt etter telefonens klokke. | - | - | 4 | deleted |
| `device.time.reported.before` / `.after` | Fra telefonens siste melding, … . | (unchanged) | (unchanged) | 5 | state: `schedules::render_device_time` |

Template:
- «Nå»: `data-rule-pause-info` div keeps only the `details.info` (the visible label text goes; aria stays `rule_pause_info_label`).
- Everyone: the group gains a last row `<a class="row" href="/schedules#rules"><span class="row-label">{everyone_link}</span>(chev)</a>`; the footer -> P2 (i) alone with `everyone_footer`.
- Own rules: footer -> P2 (i) alone, panel `<p>own_footer</p><p>emergency</p>{% if own_off %}<p>own_off_footer</p>{% endif %}`.
- Screen time: the footer `<p>` goes. Bedtime group: the footer `<p>` goes.

State logic:
- `schedules::render_device_time`: `reported` is `Some` only when the report is older than `window_relock::REPORT_AGE_NOTE_MS` (35 min, the summary's `report_age` threshold); grey as today. (The lock badge keeps its own wording.)

Tests:
- `tests/time_pages.rs::device_time_rules_has_the_rules_alone` (own_footer in the panel; limit_footer gone), `::time_rules_lists_rules_the_limit_and_the_phones` ("Emergency calls always work." on Settings › Time rules - another fork's page; here it moves into the panel).
- `tests/rule_pause.rs` l.322 (`data-rule-pause-info` hook stays; a check of the visible "About the parent code" text would fail).
- A test of the "From the phone's last report" footer (grep `reported.before`/"From the phone's last report"): none found; add one for the 35-min condition.

### Leggetid (`device_bedtime.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.bedtime.silent_sub` | Ingen lyder eller varsler ved leggetid. Alarmer ringer fortsatt, … | (moved, unchanged) | (moved, unchanged) | 1 | moved: the rule group's footer (i) |
| `device.bedtime.calls_footer` | Telefonen er låst ved leggetid. Nødanrop virker alltid. | (moved, unchanged) | (moved, unchanged) | 4 (KEEP emergency, in the (i)) | moved: same panel |
| `device.bedtime.nights_footer` | Fra kveld til morgen, etter telefonens klokke. Fre-lør er nettene før lørdag og søndag. … | (moved, unchanged) | (moved, unchanged) | 2 | moved: Nights' footer (i) alone |
| `common.saved_online` | Lagret. Telefonen får det i løpet av sekunder når den er på nett. | - | - | 4 | deleted; the flash uses `common.saved` («Lagret.») |
| `device.bedtime.corrupt_sub` | {parts}, så de vises tomme her og lagres slik du ser dem: fyll dem inn igjen og trykk Lagre - eller fjern leggetiden. | - | - | 5 | deleted |
| `device.bedtime.corrupt_fix` | - | De vises tomme her og lagres slik du ser dem: fyll dem inn igjen og trykk Lagre - eller fjern leggetiden. | They show empty here and are saved as you see them: set them again, then Save - or remove the bedtime. | 5 | added |

Template:
- Corrupt alarm: `<div class="attention-sub">{{ parts }} <details class="info">(i)<div class="info-panel"><p>corrupt_fix</p></div></details></div>` (red title unchanged).
- Nights: footer -> P2 (i) alone. Rule group: the Stille row loses its `row-sub`; the footer -> P2 (i) alone, panel `<p>silent_sub</p><p>calls_footer</p>`.
- Flash: `common.saved`.

Tests:
- `tests/time_pages.rs::the_silent_switch_saves_serves_and_shows` (silent_sub, now in the panel).
- Any test reading "Saved. The phone gets it within seconds" (none found by text) -> "Saved.".

### Unntak, partial (`partials/exceptions.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.exceptions.footer`, `device.exceptions.footer_everyone` | Bare apper som telefonen tillater under Apper, vises her. / … En telefon som ikke tillater appen, holder den låst. | (moved, unchanged) | (moved, unchanged) | 2 | moved: Unntak's footer (i) alone |
| `device.exceptions.calendar_sub` | Kalender på telefonen, også nye avtaler | - | - | 1 | deleted |

Tests:
- `tests/time_pages.rs::the_exceptions_offer_the_vibb_calendar_and_the_allowed_apps` (both footers, now in the panel - `contains` holds; calendar_sub gone).

### Telefon › Mobilnett (`device_mobile.html`, `mobile_network.rs`)

The approved entry (PLAN, "Device › Mobile network: too many words") and the user's calls of 2026-11-17, plus the audit's
Mobilnett gaps. Stored values (`off`/`vibb`/`home`, `apps_mobile`, the lock) and `mobile_network.json` stay.

Page, top to bottom (nb), a phone at home under Bare Vibb with everything as it should be:

```
MOBILDATA
  På
  Bare Vibb                        ✓
  Barnets bryter                  [on]
                                   ⓘ
ROAMING
  På
  Av
  Bare Vibb                        ✓
                                   ⓘ
  Lås innstillingene              [on]
                                   ⓘ
```

Under a wrong state one orange line above its group, its fix behind the line's own ⓘ: «Mobildata er av på telefonen»,
«Androids roamingbryter er av» (abroad: «Roamer i Sverige – Androids roamingbryter er av»), «Ingen mobildata her
(nasjonal roaming)», the lingering/still-on lines.

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `mobile.abroad.header` | I utlandet | Roaming | Roaming | 1 | words |
| `mobile.abroad.off` | Ingen data | Av | Off | 1 | words |
| `mobile.abroad.vibb` | Bare Vibb | Bare Vibb | Only Vibb | 1 | unchanged |
| `mobile.abroad.home` | Som hjemme | På | On | 1 | words |
| `mobile.abroad.off_sub` | Ingen mobildata i utlandet. På et skips- … får bare Vibb data. | - | - | P3 (into `mobile.abroad.info`) | deleted |
| `mobile.abroad.off_sub_again` | Velger du noe annet senere, må roaming slås på igjen på telefonen. | - | - | P3 (into `mobile.abroad.info`) | deleted |
| `mobile.abroad.vibb_sub` | Vibb holder kontakten med deg; apper og nettleser venter på Wi-Fi. Utenfor EU/EØS kan det starte en dagspris. | - | - | P3 (into `mobile.abroad.info`) | deleted |
| `mobile.abroad.home_sub` | Mobildata som hjemme. Kan koste mye utenfor EU/EØS. | - | - | P3 (into `mobile.abroad.info`) | deleted |
| `mobile.abroad.footer` | Gjelder utenfor landet SIM-kortet hører til. Wi-Fi virker alltid. | - | - | P5 (into `mobile.abroad.info`) | deleted |
| `mobile.abroad.info_label` | Om utlandet (a visible label row) | Om roaming (aria-label only) | About roaming (aria-label only) | 2 | words |
| `mobile.abroad.info` | Bare Vibb bruker fortsatt litt data i utlandet: … Åpne for oppsett opphever Ingen data og Bare Vibb mens det varer. | Roaming gjelder utenfor landet SIM-kortet hører til; Wi-Fi virker alltid. Av: ingen mobildata i utlandet, unntatt på et skips- eller flynett som Android ikke regner som roaming - der får bare Vibb data. Bare Vibb: Vibb holder kontakten med deg, med noen hundre KB om dagen pluss Androids egne sjekker; apper og nettleser venter på Wi-Fi. På: mobildata som hjemme. Utenfor EU/EØS kan Bare Vibb starte en dagspris på et norsk abonnement, og På kan koste mye. En app får ikke mobildata ved siden av Wi-Fi under Av eller Bare Vibb. Samtaler og SMS påvirkes ikke; en bildemelding (MMS) venter på Wi-Fi under Av. Velger du Av og senere noe annet, må Androids roamingbryter slås på igjen på telefonen. Åpne for oppsett opphever Av og Bare Vibb mens det varer. | Roaming applies outside the SIM card's country; Wi-Fi always works. Off: no mobile data abroad, except on a ship or plane network that Android doesn't count as roaming - there only Vibb gets data. Only Vibb: Vibb keeps in touch with you, with a few hundred KB a day plus Android's own checks; apps and the browser wait for Wi-Fi. On: mobile data as at home. Outside the EU/EEA Only Vibb can start a day price on a Norwegian plan, and On can cost a lot. An app can't use mobile data next to Wi-Fi under Off or Only Vibb. Calls and SMS are not affected; a picture message (MMS) waits for Wi-Fi under Off. If you choose Off and later something else, Android's roaming switch must be turned on again on the phone. Open for setup lifts Off and Only Vibb while it runs. | 2, 4 | words |
| `mobile.abroad.vibb_update` | Ingen data i utlandet til Vibb One er oppdatert | (unchanged) | (unchanged) | 5 (a wrong state, already one short orange hint) | unchanged |
| `mobile.abroad.national` | Nasjonal roaming: Ingen data stenger mobildata her – velg Bare Vibb eller Som hjemme | Ingen mobildata her (nasjonal roaming) | No mobile data here (national roaming) | 3, stale | words |
| `mobile.abroad.national_sub` | - | Av stenger mobildata også på et annet nett i hjemlandet. Velg Bare Vibb eller På under Roaming for å få data her. | Off cuts mobile data on another network in the home country too. Choose Only Vibb or On under Roaming to get data here. | 3 (behind the line's ⓘ) | added |
| `mobile.abroad.switch.off` | Androids roamingbryter: av | Androids roamingbryter er av | Android's roaming switch is off | 5 | words + state |
| `mobile.abroad.switch.off_sub` | Slå den på én gang før dere reiser, under Åpne for oppsett: Innstillinger › Nettverk og internett › SIM-kort › Roaming. | Uten den har telefonen ingen mobildata i utlandet, heller ikke Vibb. Slå den på én gang før dere reiser, under Åpne for oppsett: Innstillinger › Nettverk og internett › SIM-kort › Roaming. | Without it the phone has no mobile data abroad, not even Vibb. Turn it on once before you travel, under Open for setup: Settings › Network & internet › SIMs › Roaming. | 3 (behind the line's ⓘ) | words |
| `mobile.abroad.switch.off_abroad` | {place} – Androids roamingbryter er av | (unchanged) | (unchanged) | 5 | unchanged |
| `mobile.abroad.switch.off_abroad_sub` | {name} har ingen mobildata nå, heller ikke Vibb. Slå den på under Åpne for oppsett: … › Roaming. | (moved, unchanged: behind the line's ⓘ) | (moved, unchanged) | 3 | moved |
| `mobile.abroad.switch.locked` | Androids roamingbryter: av og låst | - | - | 5 (Av working as chosen) | deleted |
| `mobile.abroad.switch.on_locked` | Androids roamingbryter: på – låst, {name} kan ikke endre den | - | - | 5 (on is the good state) | deleted |
| `mobile.abroad.switch.on_open` | Androids roamingbryter: på – {name} kan endre den i Innstillinger | - | - | 5 | deleted |
| `mobile.abroad.switch.paused_on` | Androids roamingbryter: på – åpnet for oppsett nå | - | - | 5, 4 (the setup-open row says it) | deleted |
| `mobile.abroad.switch.paused_off` | Androids roamingbryter: av – åpnet for oppsett nå | - | - | 5, 4 | deleted |
| `mobile.value.abroad_off` | Utlandet: ingen data | Roaming av | Roaming off | 1 (follows the rename) | words |
| `mobile.value.abroad_home` | Utlandet: som hjemme | Roaming på | Roaming on | 1 | words |
| `mobile.value.data_off` | Mobildata av | - | - | 5 (QA L6: the Attention row says it on the summary, once) | deleted; state: `mobile_network::row_value` |
| `mobile.data.on_sub` | Alle tillatte apper kan bruke mobildata. | - | - | P3 | deleted |
| `mobile.data.vibb_sub` | Apper og nettleser stopper. Du styrer telefonen, ser posisjon og får varsler som før. | - | - | P3 (into `mobile.data.info`) | deleted |
| `mobile.data.info_label` | Hva Bare Vibb stopper (a visible label row) | Om mobildata (aria-label only) | About mobile data (aria-label only) | 2 | words |
| `mobile.data.info` | På mobilnett får bare Vibb One data. Varsler fra chat-apper stopper, … Åpne for oppsett opphever Bare Vibb mens det varer. | Bare Vibb: på mobilnett får bare Vibb One data. Du styrer telefonen, ser posisjonen og får varsler som før, men apper og nettleser venter på Wi-Fi: varsler fra chat-apper stopper, Finn telefonen bruker bare GPS (tregere, og svakt innendørs) fordi Googles nettverksposisjon stopper, og Play-oppdateringer og musikkappen venter. Wi-Fi virker som før, og samtaler, SMS og bildemeldinger (MMS) virker med begge valgene. Med barnets bryter kan barnet slå mobildata av for alt utenom Vibb på telefonen; den kan bare stramme inn. Åpne for oppsett opphever Bare Vibb mens det varer. | Only Vibb: on the mobile network only Vibb One gets data. You manage the phone, see where it is and get alerts as before, but apps and the browser wait for Wi-Fi: chat apps' notifications stop, Find the phone uses GPS alone (slower, and weak indoors) because Google's network location stops, and Play's updates and the music app wait. Wi-Fi works as before, and calls, SMS and picture messages (MMS) work with both choices. With the child's switch your child can turn mobile data off for everything but Vibb on the phone; it can only tighten. Open for setup lifts Only Vibb while it runs. | 2, 4 | words |
| `mobile.data.footer` | Gjelder bare mobilnett: Wi-Fi virker som før. Samtaler og SMS virker med begge valgene, bildemeldinger (MMS) også. | - | - | 4 (into `mobile.data.info`) | deleted |
| `mobile.kid.label` | Barnets bryter for mobildata | Barnets bryter | The child's switch | 1 | words |
| `mobile.kid.sub` | {name} kan slå mobildata av på telefonen. Da bruker bare Vibb mobildata. Den kan bare stramme inn. | - | - | P3 (into `mobile.data.info`) | deleted |
| `mobile.lock.label` | Lås mobilnett-innstillingene | Lås innstillingene | Lock the settings | 1 | words |
| `mobile.lock.sub` | Mobildata og SIM-innstillingene kan ikke endres på telefonen. | - | - | P3 (into `mobile.lock.footer`, now the lock's ⓘ) | deleted |
| `mobile.lock.footer` | Slik slår du mobildata helt av: slå den av på telefonen under Åpne for oppsett (…). Låsen holder den av. Et nytt SIM-kort har sin egen bryter, slått på. | Mobildata, roaming og SIM-innstillingene kan ikke endres på telefonen. | Mobile data, roaming and the SIM settings can't be changed on the phone. | 3; the user's call (the "turn mobile data off" recipe goes) | words (now the lock's ⓘ text) |
| `mobile.state.data_off` | Mobildata er slått av på telefonen | Mobildata er av på telefonen | Mobile data is off on the phone | 5 (now orange) | words + state |
| `mobile.state.data_off_sub` | Bare Wi-Fi, også for Vibb. | Telefonen har ingen mobildata, heller ikke for Vibb: den kan bare nås på Wi-Fi. Slå mobildata på under Åpne for oppsett: Innstillinger › Nettverk og internett › SIM-kort › Mobildata. | The phone has no mobile data, not even for Vibb: it can only be reached on Wi-Fi. Turn mobile data on under Open for setup: Settings › Network & internet › SIMs › Mobile data. | 3 (behind the line's ⓘ) | words |
| `device.attention.mobile_data_off` | - | Mobildata er av på telefonen | Mobile data is off on the phone | 5 (the user's call: an Attention row) | added |
| `mobile.state.only_vibb` | Mobildata: bare Vibb | - | - | 4, 5 (restates the chosen radio; the Sections row says «Bare Vibb») | deleted |
| `mobile.state.only_vibb_abroad` | Mobildata: bare Vibb i utlandet | - | - | 4, 5 | deleted |
| `mobile.state.only_vibb_sub` | Apper får bare data på Wi-Fi. | - | - | 4 | deleted |
| `mobile.state.only_vibb_kid` | Mobildata: bare Vibb – {name} har slått av | (unchanged; summary only, no sub-line) | (unchanged) | the user's call: a quiet value | unchanged |
| `mobile.state.kid_off` | {name} har slått mobildata av | (unchanged; grey row on the page) | (unchanged) | quiet value | unchanged |
| `mobile.state.pending` | Ikke på plass på telefonen ennå - kommer når telefonen henter innstillingene | Ikke på telefonen ennå | Not on the phone yet | 3 | words |
| `mobile.state.lifted` | Opphevet mens telefonen er åpnet for oppsett | Opphevet under oppsett | Lifted during setup | 1 | words |
| `mobile.state.lingering_fix` | Slå flymodus på og av, eller start telefonen på nytt, for å stoppe det. | (moved, unchanged: behind the line's ⓘ) | (moved) | 3 | moved |
| `mobile.state.lingering_fix_restart` | Start telefonen på nytt for å stoppe det. | (moved, unchanged) | (moved) | 3 | moved |
| `mobile.state.still_on_sub` | Sjekk i Androids innstillinger. | (moved, unchanged) | (moved) | 3 | moved |

Unchanged and still shown: `mobile.data.header`, `mobile.data.on`, `mobile.data.vibb`, `mobile.state.needs_update`,
`mobile.state.lingering`, `mobile.state.lingering_vibb`, `mobile.state.still_on`, `mobile.value.vibb`,
`mobile.value.kid_off`, every `data_roaming.*`.

Template (`device_mobile.html`):
- Orange lines (`card.*_lines` with `warn`): `<div class="attention attention-action" data-mobile-line=…><div
  class="attention-text">{{ line.title }}` and, when `line.sub`, `<div class="attention-sub"><details class="info">`
  with aria-label `common.how_to_fix` and the sub in the panel - the P1 markup. Grey lines carry no sub any more (the
  `row-sub` branch goes).
- Mobildata group: the two radios without `row-sub`; the `details.info.cell` "Hva Bare Vibb stopper" row leaves the
  form; after the kid's form one `<div class="group-footer"><details class="info">` (aria-label `mobile.data.info_label`,
  panel `mobile.data.info`) replaces `<p class="group-footer">mobile.data.footer`. The kid's switch row: label only (the
  `data-hint` update line stays).
- Roaming group: the radios in the order På (`home`), Av (`off`), Bare Vibb (`vibb`) - the user's order; values and
  `data-choice` unchanged; no `row-sub` (the `vibb-update` hint stays under Bare Vibb). The "Om utlandet" cell leaves the
  form; one footer ⓘ (aria `mobile.abroad.info_label`, panel `mobile.abroad.info`) replaces the footer sentence.
- Lock: the switch label alone (the update hint stays); footer ⓘ (aria `common.more_info`, panel `mobile.lock.footer`)
  replaces the footer sentence.
- Every form still posts to `#mobile-data` / `#abroad` / `#mobile-lock`; the section ids stay (save never jumps).

State logic (exact):
- `mobile_network::switch_line`: one rule for every launcher - nothing when the choice is Av (`off`) and the report says
  `locked` (the lock working as chosen); the grey `pending` line («Ikke på telefonen ennå») when Av is chosen, the report
  is not `locked` and the phone is not paused; nothing when Bare Vibb is chosen on a launcher without
  `mobile_network_v2` (code QA L5, kept); otherwise, for På or Bare Vibb: nothing when `enabled` is `true` or unknown;
  when `enabled == false` one orange line (`warn: true`) - `off_abroad` with the place when `where == "abroad"`, else
  `off` - with `off_abroad_sub` / `off_sub` as its ⓘ. The pause no longer has its own lines (the paused arms go; a
  switch off under På/Bare Vibb is orange during a pause too). `Card.switch_on` goes (it only fed `off_sub_again`).
- `mobile_network::card`, data lines: `data-off` (`enabled == false`) is `warn: true`; the `only_vibb_line` lines are
  not pushed on the page at all (the parent's `only-vibb` restates the radio, `only-vibb-abroad` the Bare Vibb choice;
  `only-vibb-kid` was already filtered); `kid-off` stays grey; `lifted` and `needs-update` stay grey.
- `mobile_network::card`, roaming lines: the `national` line gets `sub: national_sub`; the rest unchanged. QA M1:
  `place_in_switch` (which drops the grey `roaming` place/national line) is true only when the switch line is the
  `off_abroad` variant (`report.where_is == Some("abroad")`, the line that names the place) - the plain orange `off`
  line at home or with `where` unknown keeps the place/national line below it.
- `mobile_network::row_value` (the Sections row's value on the summary): no «Mobildata av» part (QA L6).
- `mobile_network::only_vibb_line`: only the `"kid"` arm stays (summary), without its sub-line; the `"parent"` and
  `"abroad"` arms return `None`.
- `mobile_network::summary_lines`: the `data-off` line goes (the Attention row says it, once); the `only-vibb` and
  `only-vibb-abroad` lines go (the Sections row's value says «Bare Vibb»); `only-vibb-kid` stays grey (the user's call:
  a quiet value, no Attention); the roaming warnings stay orange.
- `attention.rs`: new `Kind::MobileDataOff`, `Level::Action` (orange), title `device.attention.mobile_data_off`, target
  `/devices/{id}/mobile#mobile-data`, `read_from_report` true (it says "at last report" when stale); `Facts` gains
  `mobile_data_off: bool`, set in `attention::facts_of` from `s.latest.and_then(mobile_network::mobile_report)` with
  `enabled == Some(false)` (a launcher with `mobile_data_v1`; `None`/unknown raises nothing). Raised in `items` after
  `NotificationsOff`. The kid's own switch (`kid == "off"`, Android's switch still on) never raises it. It counts in the
  Devices list's badge like every Action row (`devices.rs` `facts`). Temporary (QA L2): design 114 folds it into its
  `Setup(n)` row and removes the kind, its key, its title and `facts.mobile_data_off` when 114 is built.

Tests (`server/src/tests/mobile_network.rs`):
- `the_page_and_the_summary_say_what_the_phone_reports`: the page under Bare Vibb no longer shows `only-vibb` (drop it
  from the loop, keep `roaming`, `lingering`); the summary no longer shows `only-vibb`; mobile data off: the summary
  shows `data-attention="mobile-data-off"` and no `data-mobile-line="data-off"`; the page shows `data-off` inside an
  `attention-action`.
- `the_page_saves_on_its_anchors_and_the_home_form_keeps_bit_32`: "on – locked, Emma can" goes - assert no
  `data-mobile-line="switch"` under På with the switch on; the nb words list: "I utlandet" → "Roaming"; "har ingen
  mobildata nå, heller ikke Vibb" is now inside the ⓘ panel (still in the HTML); the summary's
  "Utlandet: som hjemme · Mobildata av (Emma)" → "Roaming på · Mobildata av (Emma)"; "Mobildata: bare Vibb – Emma har
  slått av" stays.
- `src/attention.rs` tests (the alarm-before-action order test) and `src/tests/attention.rs`: add a row for
  `MobileDataOff` where they enumerate kinds.
- QA M1, a new row: På, at home, national roaming, Android's switch off -> the orange `switch` line and the grey
  `roaming` national line both show.
- QA L6: the summary's Mobilnett row value has no «Mobildata av» / "Mobile data off" (assert where a test reads it).
- `i18n::tests::every_key_is_used` covers the deleted keys.

### Telefon › Avansert (`device_advanced.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.advanced.setup.line` | Apper, innstillinger og Play åpnes i 2 timer. | - | - | P5 (the sheet's title and button say it) | deleted |
| `device.advanced.setup.open_now` | Åpne nå | Åpne i 2 t | Open for 2 h | 1 | words |
| `device.advanced.backup_on_sub` | Telefonen slår den av ved neste synkronisering … meld fra om det. | (moved, unchanged) - into the alarm's (i), first `<p>` | (moved, unchanged) | P1 form, 5 | moved |
| `device.advanced.backup_info` | Når den er på, kan Android kopiere appdata … | (moved, unchanged) - into the alarm's (i), second `<p>` | (moved, unchanged) | P5 (the row said the alarm again) | moved |
| `device.advanced.backup` | Sikkerhetskopi til Google | - | - | P4/P5 (the «… · Av» row forever, «… · På» under the alarm) | deleted; state: template `backup_service_enabled` |
| `device.advanced.restrictions_footer` | En pause eller foreldre-PIN-en opphever ikke disse. | (moved, unchanged) - first `<p>` of its (i) (`restrictions_info`) | (moved, unchanged) | P2 | moved |
| `device.advanced.debugging_footer` | Slå den av før du jobber med telefonen fra en datamaskin. | (moved, unchanged) - first `<p>` of its (i); `debugging_info` (KEEP: only a reset gets it back) stays in that panel | (moved, unchanged) | P2 | moved |
| `device.advanced.safe_boot_footer` | Slås på med den første barne-PIN-en: sikker modus hopper over låsen. | (moved, unchanged) - first `<p>` of its (i) | (moved, unchanged) | P2 | moved |
| `device.advanced.block_screens_footer` | Slå den bare av hvis noe slutter å virke. | (moved, unchanged) - first `<p>` of its (i) | (moved, unchanged) | P2 | moved |
| `advanced.spotify_diag.footer` | For å finne ut hvorfor nettspilleren stopper. … ingenting sendes hit. | (moved, unchanged) - first `<p>` of its (i) | (moved, unchanged) | P2 | moved |
| `device.advanced.fence_sub` | Skjuler Androids hjem-skjerm mens Vibb One oppdateres. | (moved, unchanged) - into `updates_info`'s (i) | (moved, unchanged) | P3 | moved |
| `device.advanced.notification_filter_sub` | Fjerner varsler som åpner blokkerte skjermer. | (moved, unchanged) - same (i) | (moved, unchanged) | P3 | moved |
| `device.advanced.boot_cover_sub` | Viser Vibb-logoen mens telefonen starter. | (moved, unchanged) - same (i) | (moved, unchanged) | P3 | moved |
| `device.advanced.updates_footer` | Vibb One installerer sine egne oppdateringer om natten, med skjermen av. | (moved, unchanged) - first `<p>` of the same (i) | (moved, unchanged) | P2 | moved |
| `device.advanced.escape.cover_next` | Neste oppstart | - | - | P4 | deleted; state: `kiosk_escapes::boot_cover_notes` |
| `device.advanced.escape.cover_ready` | Klar | - | - | P4 | deleted (same) |
| `device.advanced.escape.cover_ready_sub` | Den vises ved telefonens neste avslåing … | - | - | P4 | deleted (same) |
| `device.advanced.escape.cover_last` | Siste oppstart | - | - | P4 | deleted (same) |
| `device.advanced.escape.cover_shown` | Vist {at} | - | - | P4 | deleted (same) |
| `device.advanced.escape.cover_by_itself` | Den overlot til Vibb One av seg selv. | - | - | P4 | deleted (same) |
| `device.advanced.escape.cover_when_started` | Den overlot da Vibb One startet. | - | - | P4 | deleted (same) |
| `device.advanced.play.store` | Play Butikk | - | - | P4 | deleted; state: `device_pages::play_notes` |
| `device.advanced.play.updating` | Oppdaterer apper | - | - | P4 | deleted (same) |
| `device.advanced.play.updating_sub` | Oppdateringsvinduet om natten er åpent mens skjermen er av. | - | - | P4 | deleted (same) |
| `device.advanced.access_removed_sub` | Den når ikke denne serveren før den kobles til igjen … (repeats `disconnect_confirm`) | (moved, unchanged) - behind the alarm's (i) | (moved, unchanged) | P1 form, 4 | moved |
| `device.summary.access.paused_sub.one` | {count} feil kode ble prøvd denne timen. En QR-kode for oppsett virker fortsatt. | {count} feil kode ble prøvd denne timen. | {count} wrong code was tried this hour. | 5 (now behind the (i)) | words |
| `device.summary.access.paused_sub.other` | {count} feil koder ble prøvd denne timen. En QR-kode for oppsett virker fortsatt. | {count} feil koder ble prøvd denne timen. | {count} wrong codes were tried this hour. | 5 | words |
| `device.summary.access.qr_works` | - | QR-koden virker fortsatt | The QR code still works | 5 (the one visible consequence) | added |
| `device.advanced.connection_footer` | En ny kode kobler til telefonen igjen etter en tilbakestilling … | (moved, unchanged) - footer (i) alone after the Tilkobling group (aria `common.more_info`) | (moved, unchanged) | P2 | moved |
| `logins.code.label` | Krev foreldrekode for Wi-Fi-innlogging | Krev foreldrekode | Require the parent code | 1, 4 (the header says Wi-Fi-innlogging) | words |
| `logins.code.sub` | Gjelder også å åpne påloggingssiden igjen | (moved, unchanged) - into the group's footer (i) | (moved, unchanged) | P3 | moved |
| `logins.code.footer` | Koden tastes på telefonen hver gang. Resten av Wi-Fi-innloggingen er på | Koden tastes på telefonen hver gang. | The code is typed on the phone each time. | P2, 4 (the link becomes a row) | words |
| `server_tls.on` | Sertifikatsjekk: på - bare Let's Encrypt eller ZeroSSL | På | On | 3, 4 (the header says Sertifikatsjekk; the issuers are in the (i)) | words |
| `server_tls.watching` | Sertifikatsjekk: følger med - på fra {from} hvis alle sertifikater er fra Let's Encrypt eller ZeroSSL | Følger med - på fra {from} | Watching - on from {from} | 3, 4 | words |
| `server_tls.stopped` | Sertifikatsjekk: ikke på - telefonen så et sertifikat fra {issuer} på {network} {when} - slås ikke på av seg selv | Ikke på: {issuer} på {network} {when} | Not on: {issuer} on {network} {when} | 3, 5 (orange) | words |
| `server_tls.stopped_unnamed` | Sertifikatsjekk: ikke på - telefonen så et sertifikat fra utenfor … | Ikke på: et sertifikat fra en annen utsteder | Not on: a certificate from another issuer | 3, 5 (orange) | words |
| `server_tls.on_pending` | Sertifikatsjekk: på - telefonen sier at sjekken fortsatt er av, og tar den ved neste synkronisering | På - venter på telefonen | On - waiting for the phone | 3 | words |
| `server_tls.on_phone_off` | Sertifikatsjekk: på her - telefonen sier at sjekken fortsatt er av | På her - av på telefonen | On here - off on the phone | 3, 5 (orange) | words |
| `server_tls.on_cannot` | Sertifikatsjekk: på her, men denne telefonen kan ikke ta den - tilkoblingen … kommer fra {issuer}, ikke … | På her - telefonen kan ikke ta den ({issuer}) | On here - the phone can't take it ({issuer}) | 3, 5 (orange) | words |
| `server_tls.recovery` | Gjenopprettingsadresse: {url} - avviser telefonen serverens sertifikat, sett den som Serveradresse … Den virker bare over tailnettet. | Gjenopprettingsadresse | Recovery address | 3 (a value row: label · {url}) | words (param `{url}` dropped) |
| `server_tls.recovery_info` | - | Avviser telefonen serverens sertifikat, sett denne som Serveradresse på telefonen (Innstillinger, med foreldrekoden). Den virker bare over tailnettet. | If the phone refuses this server's certificate, set this as its Server URL (Settings, with the parent code). It works over the tailnet only. | 3 (the how-to behind the row's (i)) | added |
| `server_tls.recovery_unknown` | Gjenopprettingsadresse: serveren kan ikke se sin egen tailnet-adresse. Det er http:// … (DEPLOY.md, «Tailnet recovery address»). | Ukjent | Unknown | 3 (the row's value) | words |
| `server_tls.recovery_unknown_info` | - | Serveren kan ikke se sin egen tailnet-adresse. Det er http:// og Tailscale-IP-en til lytteren for telefonene, med porten, f.eks. http://100.101.102.103:3101 - sett den som PHONE_RECOVERY_URL for å vise den her (DEPLOY.md, «Tailnet recovery address»). | This server can't tell its own tailnet address. It is http:// and the Tailscale IP of the phones' listener, with its port, e.g. http://100.101.102.103:3101 - set it as PHONE_RECOVERY_URL to show it here (DEPLOY.md, "Tailnet recovery address"). | 3 | added |
| `server_tls.footer` | Når den er på, snakker telefonen med denne serveren over https bare når … | (moved, unchanged) - footer (i) alone (aria `common.more_info`) | (moved, unchanged) | P2 | moved |

Template:
- `#setup` sheet: the `row-note` with `device.advanced.setup.line` goes; the button keeps its form (`start-pause#setup`), text `open_now`.
- `#restrictions`: the `google-backup` alarm (`Some(true)`) is the title alone with one (i) in an `attention-sub` holding `backup_on_sub` then `backup_info` (P1 markup, aria `common.more_info`). The `{% if let Some(on) = backup_service_enabled %}` value-row group (`data-row="google-backup"`) is removed in every state.
- The four restrictions/debugging/safe-boot/app-screens footers and Updates' and Spotify's: `<div class="group-footer">` keeps only its `<details class="info">`; the old footer key becomes the panel's first `<p>` (aria-labels `*_info_label` unchanged).
- Updates: the three switches lose their `row-sub`; the panel order is `updates_footer`, `fence_sub`, `notification_filter_sub`, `boot_cover_sub`, `updates_info`.
- Escape/Play warnings use `note_warning.html` (P1): every title on this page reads alone (`escape.needs_update`, `escape.install_failed`, `escape.commit_failed`, `escape.orphan`, `escape.partial`, `escape.filter_no_access`, `escape.cover_needs_update`, `escape.cover_tripped`, `device.attention.boot_cover_not_shown`, `play.install_mode`, `play.restart`, `play.not_suspendable`), so no title changes; their `*_sub` go behind the (i) unchanged.
- `#wifi-signin`: the switch `logins.code.label` without its `row-sub` (the orange `warning` hint stays); a link row `<a class="row" href="/devices/{id}/logins#logins-wifi"><span class="row-label">{{ logins.page }}</span> chev</a>` inside the group (after the form, not inside it); the footer is the (i) alone with `logins.code.sub` then `logins.code.footer`.
- `#connection`: `access-removed` alarm: title + (i) (`access_removed_sub`). `typed-paused`: title (`device.summary.access.paused`) + visible `attention-sub` `device.summary.access.qr_works` + (i) holding `paused_sub` (counted). Footer: (i) alone with `connection_footer`. The confirms (`disconnect_confirm`, `leave_tailnet_confirm`, `remove_confirm`) unchanged (KEEP).
- `#server-tls`: row `data-row="server-tls"`: `tls.line` as the label; with `tls.detail` the row becomes `details.info cell` (detail in the panel); the label gets `tone-orange` when `tls.warn`. Row `data-row="server-tls-recovery"`: `details.info cell`, label `server_tls.recovery`, `row-value` the url (or `recovery_unknown`), panel `recovery_info` (or `recovery_unknown_info`). Footer: (i) alone with `server_tls.footer`. `turn_off_confirm` unchanged (KEEP).

State logic:
- `kiosk_escapes::boot_cover_notes`: the fine states give no note - `(None, _)` (armed, not yet shown) and `(Some(armed), Some(shown)) if shown >= armed` return `vec![]`; the variants `EscapeNote::BootCoverArmed`, `EscapeNote::BootCoverShown` and `enum Handover` go with their `note()` arms. Shown: only `BootCoverNeedsUpdate`, `BootCoverTripped` (orange), `BootCoverNotShown` (orange).
- `kiosk_escapes::escapes_card`: `fence.last_release == Release::Replaced` pushes nothing (the good outcome); `InstallFailed`, `CommitFailed`, `Orphan` (orange) and `Rebooted`, `Expired`, `Other` (grey row "Siste oppdatering · …") still push. `UpdateWaiting` (grey "Oppdatering venter · 0.37.0") stays. The `Release::Replaced` arm in `note()` stays for exhaustiveness (keys `escape.installed`/`installed_sub` stay; `releases` test unchanged).
- `device_pages::play_notes`: `status.play_window_active` no longer pushes; `PlayNote::UpdateWindow` and its arm go. Install mode (orange), restart once, not suspendable stay.
- Template `device_advanced.html`: the Google-backup row group is gone in every state; the alarm shows only for `Some(true)`.
- `server_tls::card`: `Card` gains `warn: bool` (true for `stopped`, `stopped_unnamed`, `on_cannot`, `on_phone_off`; false for `on`, `on_pending`, `watching`, `off_since`) and `recovery` splits into `recovery_value: String` (the url or `t("server_tls.recovery_unknown")`) and `recovery_info: String` (`server_tls.recovery_info` or `server_tls.recovery_unknown_info`). `server_tls.recovery` loses `{url}` (both catalogs, same params).

Tests:
- `src/server_tls.rs::the_card_follows_the_phone`, `::adoption_is_told_and_the_card_says_what_the_phone_reports`: compare against the new `server_tls.on`/`stopped`/`stopped_unnamed`/`on_pending`/`on_phone_off` words; assert `warn`; the recovery fields.
- `src/tests/server_tls.rs::a_launcher_report_moves_the_state` ("Certificate check: on - only Let&#39;s Encrypt or ZeroSSL", "Recovery address: http://100.101.102.103:3101") and `::a_fresh_phone_is_watched_and_shows_when_it_switches_on` ("Certificate check: watching - on from"): the row label is now "On" / "Watching - on from"; the recovery row "Recovery address" with the url as its value.
- `src/kiosk_escapes.rs::boot_cover_state_is_sanitized_and_explained`: the shown and armed cases now return `vec![]` (the "Shown 01:00"/"by itself" asserts go); `releases` unchanged.
- `src/handlers/device_pages.rs::play_notes_follow_the_report`: no `PlayNote::UpdateWindow`; drop its "Play Store"/"Play Butikk" asserts.
- `src/tests/hardening.rs::backup_service_state_is_stored_and_shown`, `::backup_service_state_is_per_device`, helper `backup_row`: no `data-row="google-backup"` row in any state; the alarm text "Backup to Google is on" is still there for `true`.
- `src/tests/lock_now.rs::the_advanced_page_opens_for_setup_and_lands_back_there` (`device.advanced.setup.line`, `open_now`) and `src/tests/window_relock.rs::open_for_setup_is_the_advanced_pages_first_row` ("Apps, Settings and Play open f…", "Open now"): no line; button "Open for 2 h".
- `src/tests/device_pages.rs::a_live_code_shows_on_connection_without_the_code` (`typed-paused`): reads the title; check it doesn't assert the old sub sentence.
- Shared with Telefonens side: `device.summary.access.paused_sub` and the new `qr_works` are also the summary's `#access` group (Telefonens side uses the same split).

### Telefon › Nettfilter (`device_filter.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.filter.footer` | Blokkerer listene og domenene nedenfor på denne telefonen. | - | - | P5 | deleted |
| `device.filter.footer_spotify` | Blokkerer listene og domenene nedenfor, unntatt Spotifys mens denne telefonen spiller Spotify. | - | - | P5 (the (i)'s `spotify_hosts` says the exception) | deleted |
| `device.filter.lists_footer` | Som for alle følger bryteren under Innstillinger › Nettfilter. | - | - | P5 (the option «Som for alle (på)» says it) | deleted |
| `device.filter.allowed_footer` | Slippes gjennom selv om en liste blokkerer det - men ikke de sikre DNS-serverne … | (moved, unchanged) - footer (i) alone under Tillatte domener (aria `common.more_info`) | (moved, unchanged) | P2 | moved |
| `device.filter.log_footer.one` / `.other` | Lagres i {count} dag(er). Slår du den av, slettes loggen til denne telefonen. | Lagres i {count} dag. / Lagres i {count} dager. - in the footer's (i) | Kept {count} day. / Kept {count} days. | P2 (the deletion moves to the visible line below) | words |
| `device.filter.log_off_erases` | - | Av sletter loggen. | Off deletes the log. | KEEP (QA H1: irreversible, the switch autosaves with no confirm - one visible line) | added |
| `device.filter.spotify_override` | Brukes ikke mens denne telefonen spiller Spotify | Av mens Spotify spiller | Off while Spotify plays | 1 | words |

Template:
- `#filter`'s footer: `<div class="group-footer" data-note="filter">` keeps only its `<details class="info">` (no `spotify_on` branch).
- `#lists`: the footer `<p>` goes. `#allowed`: footer becomes the (i) alone with the old key as the panel text. `#log`
  (QA H1, the KEEP exception shape of Posisjonsinnstillinger): `<div class="group-footer">{{ device.filter.log_off_erases }}
  <details class="info">` with `log_footer` (`self.tc`, the retention) in the panel.

Tests:
- `src/tests/music_spotify.rs::the_web_filter_page_says_what_spotify_overrides` (`footer_spotify`, `spotify_override` "Not applied while this phone plays Spotify"): drop the footer assert; "Off while Spotify plays".
- `src/tests/device_pages.rs::web_filter_holds_the_phones_parts` ("As for everyone follows the switch"): drop.
- `src/tests/custom_dns.rs::an_allowed_secure_dns_host_says_it_stays_blocked` (`allowed_footer` text): still in the page (in the panel) - unchanged unless it anchors on `group-footer">Let through`.

### Nettfilter › Innlogging (`device_logins.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `logins.wifi.header` | Wi-Fi-innlogging (hotell, kafé, fly) | Wi-Fi | Wi-Fi | 1, 4 | words |
| `logins.wifi.label` | Wi-Fi-innlogging | Innloggingsside | Sign-in page | 1, 4 | words |
| `logins.wifi.sub` | Vibb viser innloggingssiden til nettet, uten adressefelt … ikke din egen DNS-tjener. | (moved, unchanged) - into the Wi-Fi group's footer (i) | (moved, unchanged) | P3 | moved |
| `logins.wifi.code_on` | Krever foreldrekode – endres under Avansert | Krever foreldrekode | Needs the parent code | 3 (the chevron row goes to Avansert) | words |
| `logins.wifi.code_off` | Uten foreldrekode – endres under Avansert | Uten foreldrekode | No parent code needed | 3 | words |
| `logins.wifi.today` | I dag uten kode: {openings} av {day_openings} innlogginger, {minutes} av {day_minutes} minutter | I dag uten kode: {openings} av {day_openings} · {minutes} av {day_minutes} min | Today without a code: {openings} of {day_openings} · {minutes} of {day_minutes} min | 3 | words |
| `logins.wifi.grant_sub` | Virker når telefonen har nett, for eksempel mobildata. Én innlogging på 10 minutter uten kode, høyst 3 om dagen. | (moved, unchanged) - into the Wi-Fi footer (i) | (moved, unchanged) | P2 | moved |
| `logins.wifi.footer` | Hver innlogging varer høyst 10 minutter. Uten kode høyst 4 innlogginger og 20 minutter om dagen. | (moved, unchanged) - into the Wi-Fi footer (i) | (moved, unchanged) | P2 | moved |
| `logins.apps.label` | Innlogging i apper med Chrome | Chrome | Chrome | 1, 4 (header «Innlogging i apper») | words |
| `logins.apps.sub` | Apper kan åpne innloggingssider i Chrome. Chrome vises ikke på telefonen … | (moved, unchanged) - into the apps group's footer (i) | (moved, unchanged) | P3 | moved |
| `logins.apps.state.on` | Klar | - | - | P4 | deleted; state: `logins::view` |
| `logins.apps.ticked` | Chrome er tillatt som vanlig app. Fjern haken under Apper for å bruke den bare til innlogging. | Chrome er tillatt som vanlig app | Chrome is allowed as a normal app | 5 (orange line) | words |
| `logins.apps.ticked_fix` | - | Fjern haken under Apper for å bruke den bare til innlogging. | Untick it under Apps to use it only for sign-in. | 5 (behind the line's (i)) | added |
| `logins.apps.clear` | Når du slår dette på, slettes det som er lagret i Chrome på telefonen (historikk og innlogginger). | (unchanged text, KEEP) - shown only while the switch is off | (unchanged) | 4/5 | state: template (`apps_on`) |
| `logins.sites.note` | Siden appen viser kan sende Chrome videre til andre sider. … | (moved, unchanged) - footer (i) alone under Sider Chrome kan åpne | (moved, unchanged) | P2 | moved |

Template:
- `#logins-wifi`: the switch without its `row-sub` (the orange `needs_update` hint stays); the `row-note` with `grant_sub` goes; the footer is the (i) alone with `logins.wifi.sub`, `grant_sub`, `logins.wifi.footer` (aria `common.more_info`). The `wifi-code` link row keeps its chevron to `/advanced#wifi-signin`.
- `#logins-apps`: the switch without its `row-sub`; `chrome-ticked` becomes an orange row (i): `<details class="info cell" data-row="chrome-ticked"><summary class="row"><span class="row-label tone-orange">{{ logins.apps.ticked }}</span> (i)</summary><div class="info-panel"><p>{{ logins.apps.ticked_fix }}</p></div></details>`; the `apps-state` row gets `row-value-action` for `absent`/`failed`. The footer: `{% if !apps_on %}<p class="group-footer">{{ logins.apps.clear }}</p>{% endif %}`, then the (i) alone with `logins.apps.sub` as its own `<div class="group-footer">` (one footer: when off it reads clear-note + (i), the KEEP exception).
- `#logins-sites`: the footer is the (i) alone with `logins.sites.note`. The orange `presets_blocked`/`sites.blocked` lines stay (a wrong state with its link).

State logic:
- `logins::view`: `apps_state` is `None` when `apps_state(...) == "logins.apps.state.on"` (shown only for `waiting` grey, `needs_update` grey, `absent`/`failed` orange).
- Template `device_logins.html`: `logins.apps.clear` only while `!apps_on` (KEEP: it warns before the data is cleared; after turning on it says nothing new).

Tests:
- `src/tests/logins.rs::the_pages_and_their_saves`: `data-row="chrome-ticked"` stays (now on the `details`); any assert on the old ticked sentence, the "Ready"/"Klar" row, the header "(hotels, cafés, planes)" or `Sign-in in apps with Chrome` updates; the clear note only before the switch is on.

### Telefon › Kalender (`device_calendar.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `calendar.not_capable_sub` | Det du velger her, når telefonen når Vibb One er oppdatert. | (moved, unchanged) - behind the alarm's (i) | (moved, unchanged) | P1 form | moved |
| `calendar.dagen.on_home_sub` | Av skjuler flisen, linjen på låseskjermen og påminnelsene; reglene står | (moved, unchanged) - into the Kalender-appen (i) | (moved, unchanged) | P3 | moved |
| `calendar.dagen.footer` | Barnets dag i Vibb One, én dag om gangen. | (moved, unchanged) - first `<p>` of the same (i) | (moved, unchanged) | P2 | moved |
| `calendar.lock.next_sub` | «Neste: Fotball 17:30» under datoen | (moved, unchanged) - into the lock (i), before `lock.info` | (moved, unchanged) | P3 | moved |
| `calendar.lock.reminders` | Påminnelser på låseskjermen | Påminnelser | Reminders | 1, 4 (header «På låseskjermen») | words |
| `calendar.lock.reminders_sub` | En stille rad før hendelsen - uten lyd, eller med én varsellyd når den kommer | (moved, unchanged) - into the lock (i), before `info_sound` | (moved, unchanged) | P3 | moved |
| `calendar.lock.footer` | Alle i nærheten av telefonen kan se låseskjermen. | - | - | the user's call 2026-11-17 («den kan tas bort») | deleted |
| `calendar.sources.footer` | Kalendere fra en lenke eller en CalDAV-konto. Serveren henter dem hver halvtime, og telefonen viser dem i Kalender. | - | - | P5 (its fact joins `sources.info`) | deleted |
| `calendar.sources.info` | «Hent nå» på lenkens side henter den med en gang. … Bruk et app-passord … Google virker bare som lenke … | Serveren henter kalenderne hver halvtime; «Hent nå» på lenkens side henter med en gang. Hver telefon får hendelsene i en egen kalender som barnet ikke kan endre; ingenting eldre enn 60 dager sendes. Ingenting fra telefonen skrives tilbake - med mindre barnets avtaler skrives til en CalDAV-kalender (velges under Kalender-appen). | The server fetches the calendars every half hour; Fetch Now on a link's page fetches at once. Each phone gets the events in a calendar of its own, which the kid can't change; nothing more than 60 days old is sent. Nothing from the phone is written back - unless the kid's events are written to a CalDAV calendar (chosen under Calendar app). | 2, 4 (the app-password and Google advice go to the Add page's field (i)s) | words |
| `calendar.sources.no_key` | Kalendere fra lenker og kontoer er av: serveren har ingen nøkkel å lagre dem kryptert med. Sjekk serverloggen (MUSIC_SECRET_KEY). | Av: serveren mangler nøkkel | Off: the server has no key | 5 (orange) | words |
| `calendar.sources.no_key_info` | - | Serveren har ingen nøkkel å lagre lenker og passord kryptert med. Sjekk serverloggen (MUSIC_SECRET_KEY). | The server has no key to store links and passwords encrypted. Check the server log (MUSIC_SECRET_KEY). | 5 (behind the (i)) | added |
| `calendar.links.key_new_sub` | Lim inn hver lenke som er merket nedenfor på nytt med Legg til kalender - … | (moved, unchanged) - behind the alarm's (i) | (moved, unchanged) | P1 form | moved |
| `calendar.calendars.no_report` | Telefonen har ikke sagt hvilke kalendere den har ennå. Det gjør den i neste melding. | Venter på telefonen | Waiting for the phone | 3 | words |
| `calendar.calendars.unreadable` | Telefonen får ikke lest kalenderne sine. Vibb One trenger kalendertillatelsen, … | Får ikke lest kalenderne | Can't read the calendars | 5 (orange) | words |
| `calendar.calendars.unreadable_info` | - | Vibb One trenger kalendertillatelsen, som den gir seg selv mens telefonen er styrt. | Vibb One needs the calendar permission, which it gives itself while the phone is managed. | 5 (behind the (i)) | added |
| `calendar.calendars.reminders_off` | Påminnelser er av for denne telefonen | Påminnelser er av | Reminders are off | 4 (said once, above the calendars) | words |
| `calendar.calendars.footer` | Fra telefonens siste melding. | (moved, unchanged) - first `<p>` of the calendars (i) | (moved, unchanged) | P2 | moved |
| `calendar.calendars.info` | «Kalenderens egne påminnelser» er dem kalenderen selv har, høyst to timer før. Står «Påminnelser på låseskjermen» på Av, … | «Kalenderens egne påminnelser» er dem kalenderen selv har, høyst to timer før. Står Påminnelser under På låseskjermen på Av, blir det ingen påminnelse, uansett hva du velger her. | "The calendar's own reminders" are the ones the calendar itself has, at most two hours before. With Reminders under On the lock screen set to Off there is no reminder, whatever you pick here. | follows the `calendar.lock.reminders` rename | words |
| `calendar.calendars.setup` | Lim inn lenken til kalenderen under Kalenderlenker over - det er alt. Eller med en synkroniseringsapp: … | Legg til lenken med Legg til kalender under Hentes av serveren over - det er alt. Eller med en synkroniseringsapp: legg til ICSx5 (feeder) eller DAVx5 (CalDAV) fra katalogens F-Droid-kilde, kryss den av for denne telefonen, slå av «På Hjem» under Apper, og åpne den i en pause og legg til feeden eller logg inn. DAVx5: bruk et app-passord og skrivebeskyttede delinger; synk hver 1.-4. time sparer batteri. | Add the calendar's link with Add Calendar under Fetched by the server above - that's all. Or, with a sync app: add ICSx5 (feeds) or DAVx5 (CalDAV) from the catalog's F-Droid source, tick it for this phone, switch On Home off under Apps, then in a pause open it and add the feed or log in. DAVx5: use an app password and read-only shares; sync every 1-4 hours saves battery. | stale wording (audit: fixed regardless), P2 | words |
| `calendar.rules.footer` | Første regel som passer, gjelder. Telefonen bruker dem når den tegner dagen. Hendelsene i en kalenderlenke hentes av serveren; … | Første regel som passer, gjelder. | First matching rule wins. | 4 (the privacy line a third time) | words |

Template:
- `calendar-not-capable` and `links-key-new` alarms: title + one (i) in the `attention-sub` (P1 markup) holding the sub.
- `#dagen`: the switch without its `row-sub`; footer `data-footer="dagen"` = (i) alone, panel `dagen.footer`, `dagen.on_home_sub`, `dagen.info`, `kid_caldav.info_tag` (as today).
- `#lock`: both switches without `row-sub` (`ping_older` stays as the reminders row's sub: a state); footer `data-footer="lock"` = (i) alone, panel `lock.next_sub`, `lock.reminders_sub`, `lock.info`, `lock.info_sound`, the example (as today). `calendar.lock.footer` is gone from the page.
- `#sources`: footer `data-footer="sources"` = (i) alone (`sources.info`, `sources.info_privacy` - KEEP). `data-sources="off"` becomes an orange row (i): `<details class="info cell" data-sources="off"><summary class="row"><span class="row-label tone-orange">{{ no_key }}</span> (i)</summary><div class="info-panel"><p>{{ no_key_info }}</p></div></details>`.
- `#calendars`: `no-report` a grey `row-note` «Venter på telefonen»; `unreadable` an orange row (i) as above with `unreadable_info`; `none` unchanged. `reminders_off`: one `<p class="row row-note" data-reminders-off>` at the top of the calendars group when `reminders == "off"`, not under each calendar's Påminnelse row. Footer `data-footer="calendars"` = (i) alone (`calendars.footer`, `info`, `colors_info`). `calendars.setup` (when not Listed) = a footer (i) alone (aria `common.more_info`).
- `#rules`: footer shows `rules.footer` (one sentence, no (i)).

Tests:
- `src/tests/calendar.rs::the_help_is_behind_the_i_and_the_reminders_say_what_they_are` (`calendar.lock.reminders`, `reminders_sub`; `data-reminders-off` counted 3 times): "Reminders"; the sub inside the panel; `data-reminders-off` now once (lines ~1019, and ~225 in `the_page_lists_the_groups_and_the_reported_calendars`).
- `src/tests/calendar.rs::the_rule_editors_examples_follow_the_language` (`calendar.lock.reminders`): new label.
- `src/tests/calendar.rs::the_page_lists_the_groups_and_the_reported_calendars` (`data-calendars="no-report"`): keep the hook on the new row-note.
- `src/tests/calendar_feeds.rs` and `src/tests/caldav.rs` (`data-sources="off"`): the hook moves to the `details`; text "Off: the server has no key".

### Kalender › Legg til kalender (`device_calendar_add.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `calendar.add.kind_link` | Lenke (https eller webcal) | Lenke | Link | 1 (the placeholder shows webcal://) | words |
| `calendar.add.link_footer` | Lim inn lenken til en kalender. Google: bruk kalenderens hemmelige adresse i iCal-format. | Google: bruk kalenderens hemmelige adresse i iCal-format. | Google: use the calendar's secret address in iCal format. | 2, 4 (field (i)) | words |
| `calendar.add.caldav_footer` | Bruk et app-passord, ikke det vanlige passordet. iCloud lager det på account.apple.com. | Bruk et app-passord, ikke det vanlige passordet. iCloud lager det på account.apple.com, Fastmail og Nextcloud i innstillingene for passord og sikkerhet. | Use an app password, not your usual password. iCloud makes one at account.apple.com, Fastmail and Nextcloud in their password and security settings. | 2 (field (i); takes `sources.info`'s providers) | words |

Template: the two footers become field (i)s (`details.info.field-info` after the label): `link_footer` on `calendar.links.url`'s label, `caldav_footer` on `calendar.caldav.password`'s label (aria `common.more_info`); their `<p class="group-footer">` go. `data-sources="off"` on this page shows `calendar.sources.no_key` (now short) as an orange line with `no_key_info` behind its (i) (P1 markup).

Tests: `src/tests/calendar_feeds.rs::add_calendar_picks_its_kind_and_a_gone_source_goes_back` ("Link (https or webcal)"): "Link".

### Kalender › CalDAV-konto (`device_calendar_caldav.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `calendar.caldav.hosts` | Passordet sendes bare til: {hosts} | (moved, unchanged) - behind the Server row's (i) (KEEP: the password's destinations) | (moved, unchanged) | P2 | moved |
| `calendar.caldav.new_password_hint` | La feltet stå tomt for å teste med det lagrede. | - | - | P5 (the label says «(valgfritt)») | deleted |

Template: the Server row (`calendar.caldav.server_row` · provider) becomes `details.info cell` with `hosts` in the panel; `lan_http`, when it applies, is an orange `row-note` (`tone-orange`) in the account group (text unchanged). The `data-footer="hosts"` footer goes; the `field-hint` under the new password goes (and its `aria-describedby`). `remove_confirm` unchanged (KEEP).

Tests: `src/tests/caldav.rs::test_lists_the_calendars_and_where_the_password_goes`, `::the_servers_own_addresses_never_reach_the_lan` ("The password goes only to:"): the text is still in the page (panel); update any anchor on `data-footer="hosts"`.

### Kalender › Kalenderlenke (`device_calendar_link.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `calendar.link.footer` | Serveren henter lenken hver halvtime. Telefonen får den ved neste synk. | - | - | P5 (the rows «Sist hentet»/«Neste» show it) | deleted |
| `calendar.links.remove_confirm` | Kalenderen forsvinner fra denne telefonen. Kalendere i ICSx5 blir ikke rørt. | Kalenderen forsvinner fra denne telefonen. | The calendar goes off this phone. | 4 (KEEP the confirm) | words |

### Kalender › Tittelregel (`device_calendar_rule.html`, `partials/calendar_preview.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `calendar.rule.match_footer` | Store bokstaver og mellomrom spiller ingen rolle: «Romerriket» finner også «R omerriket». | (moved, unchanged) - footer (i) alone under the match group | (moved, unchanged) | P2 | moved |
| `calendar.rule.then_footer` | Vis som med «Behold resten»: … En regel på beskrivelsen kan bare skjule hendelsen. | (moved, unchanged) - footer (i) alone under «Da» | (moved, unchanged) | P2 | moved |
| `calendar.preview.footer` | Lim inn en ekte tittel for å se hva telefonen vil vise. Bare det du limer inn, sjekkes: … | Bare det du limer inn, sjekkes. | Only what you paste is checked. | 4 (the label says «En ekte tittel»; the privacy line a fourth time) | words |

Template: `match_footer` and `then_footer` footers become the (i) alone (aria `common.more_info`); `preview.footer` stays a one-line visible footer (no (i)). `delete_confirm` unchanged (KEEP).

### Telefon › Musikk (`device_music.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `device.music.app_footer` | Installeres som alle andre apper fra katalogen. Slås den av, avinstalleres appen. | (moved, unchanged) - footer (i) alone (KEEP: «Slås den av, avinstalleres appen») | (moved, unchanged) | P2 | moved |
| `device.music.add_to_catalog` | Legg den til i katalogen under Musikk › Innstillinger først. | - | - | P5 (the row «Ikke i katalogen ›» links there) | deleted |
| `device.music.library_footer.before` | Legg til podkaster, NRK-serier og egne filer under | - | - | P5 | deleted |
| `device.music.library_footer.after` | . | - | - | P5 | deleted |
| `device.music.mobile_footer` | Aldri ved roaming. På Wi-Fi virker begge alltid. | (moved, unchanged) - first `<p>` of `mobile_info`'s (i) | (moved, unchanged) | P2 | moved |
| `device.music.storage_footer` | Avspilte episoder utover dem som beholdes, fjernes først. | (moved, unchanged) - first `<p>` of `storage_info`'s (i) | (moved, unchanged) | P2 | moved |
| `device.music.storytel_footer` | Gir musikkappen familiens innlogging. Slås den av, fjernes den fra telefonen. | (moved, unchanged) - footer (i) alone (KEEP: «fjernes den fra telefonen») | (moved, unchanged) | P2 | moved |
| `device.music.storytel_none.before` | Ingen innlogging er lagret ennå: legg den til under | Ingen innlogging: | No login: | 3 (a quiet state with its link) | words |
| `device.music.spotify_footer` | Én konto per telefon: barnets egen Premium-konto, aldri din. | (moved, unchanged) - footer (i) alone | (moved, unchanged) | P2 | moved |
| `device.music.spotify.login_line` | Logg inn igjen før {date} | - | - | P4 (the orange `spotify.ends` comes 14 days before) | deleted; state: `music::spotify_binding_card` |
| `device.music.no_speakers_footer` | Til da kan telefonen spille på alle Sonos-høyttalere den finner, også hos venner. | (moved, unchanged) - footer (i) alone | (moved, unchanged) | P2 | moved |
| `device.music.sonos_some` | Bare rommene som er slått på. | - | - | P5 (the switches say it) | deleted |
| `device.music.sonos_all` | På alle Wi-Fi-nett, også venners høyttalere. | (moved, unchanged) - first `<p>` of `sonos_info`'s (i), only when `music.sonos.all` | (moved, unchanged) | P2 | moved |
| `device.music.sonos_none` | Ingen rom på: musikken spilles bare på telefonen og hodetelefoner. | Ingen rom på | No room on | 3 (a quiet row-note in the group) | words |

Template:
- Warnings through `note_warning.html` (P1); every title here reads alone (`unreachable`, `cant_deliver`, `older_phone*`, `over_limit`, `storytel_refused`, `spotify.no_app`/`refused`/`not_premium`/`ends`/`ended`/`shared*`), so no title changes; `*_sub`, `log_in_again`, `log_in_before` go behind the (i) unchanged.
- `#music-app`: footer = (i) alone (`app_footer`); the not-in-catalog footer goes.
- Library: the footer goes.
- Mobile/storage: the footer's sentence becomes its (i)'s first `<p>`.
- Storytel: stored → footer (i) alone (`storytel_footer`); not stored → the visible footer «Ingen innlogging: Musikk › Innstillinger» (link unchanged). `data-storytel-unsent` gets `tone-orange`.
- Spotify: no `data-row="spotify-login"` sub; `data-row="spotify-off"` sub gets `tone-orange` when it is the unsent note; footer = (i) alone (`spotify_footer`).
- Sonos: `no-speakers` footer = (i) alone; rooms: `sonos_none` as `<p class="row row-note" data-row="sonos-none">` inside the form group when `none_allowed()`; the footer = (i) alone (`sonos_all` when all, then `sonos_info`).

State logic:
- `music::spotify_binding_card`: in the not-ending branch `login` stays `None` (no "Log in again before …" in the fine state); the `login` field and its template span go. Within 14 days the orange `spotify.ends` warning shows as today.

Tests:
- `src/tests/music_spotify.rs::the_device_card_binds_a_phone_to_an_account` (`data-row="spotify-login">Log in again before`, `spotify_footer`): the login line is absent; footer text in the panel.
- `src/tests/music.rs::the_music_page_scopes_sonos_rooms` (`sonos_none`, `no_speakers_footer`): "No room on" row-note; footer text in the panel.
- `src/tests/music_storytel.rs::library_gates_storytel` (`storytel_none.before`): "No login:".
- `src/tests/music.rs::the_page_shows_two_switches_and_the_limit_and_saves_back_to_music_settings` (`mobile_footer`): text now in the panel - update only if it anchors on the footer.

### Finn (`device_locate.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `locate.off.title` | Posisjon er av for denne telefonen | Posisjon er av | Location is off | 5, 4 (it is this phone's page) | words |
| `locate.off.sub` | Den tar ingen posisjon, heller ikke når du spør. | (moved, unchanged) - behind the alarm's (i) | (moved, unchanged) | P1 form | moved |
| `locate.off.zones` | Sonene kan ikke se hvor telefonen er. | (moved, unchanged) - same (i), with zones | (moved, unchanged) | P1 form | moved |
| `locate.off.choose` | Velg «Bare når du spør» under Posisjonsinnstillinger. | (moved, unchanged) - same (i) | (moved, unchanged) | P1 form | moved |
| `locate.all.footer` | En posisjon er ny så lenge telefonen holder sin egen innstilling: … | (moved, unchanged) - footer (i) alone under «Alle» | (moved, unchanged) | P2 | moved |
| `locate.mock.sub` | En app på telefonen lot som den var et annet sted. Vibb One holdt dem utenfor kartet og sonene. | (moved, unchanged) - behind the mock row's (i) | (moved, unchanged) | 1, 5 | moved |
| `locate.wait.no_answer` | Ikke noe svar ennå. Telefonen kan være frakoblet; den får forespørselen hvis den er tilbake innen en time. | Ikke noe svar ennå | No answer yet | 3 (Finn telefonen's (i) says the offline hour) | words |
| `locate.find.sub` | Ring, lås, slett | - | - | 1 | deleted |
| `locate.fix.none_off` | Ingen posisjon lagres mens posisjon er av. | - | - | 4 (the alarm says it) | deleted; state: template |

Template:
- `location-off`: title + one (i) in the `attention-sub` (P1 markup) holding `off.sub`, `off.zones` (with zones), `off.choose`.
- `mock-ignored`: the row becomes `details.info cell` with `data-row="mock-ignored"`, label `tone-orange` (the count line), panel `mock.sub`.
- Find row: no `row-sub`.
- Fix row: `data-fix-none` only when `!view.location_off` (then `locate.fix.none`).
- «Alle»'s footer = (i) alone.

Tests:
- `src/tests/locate_page.rs::update_location_is_the_first_row_and_the_map_has_no_box` (`<span class="row-sub" data-fix-none>No position kept while location is off.</span>`): no `data-fix-none` while off.
- `src/tests/cleanup.rs::off_erases_every_fix_and_on_keeps_the_last_known` (`fix.none_off` text): drop that assert (the off alarm shows instead).
- `src/tests/locate_page.rs::the_rows_and_their_pages` ("Ring, lock, erase"): drop.
- `src/tests/time_rules.rs::location_policy_form_round_trip` ("Location is off for this phone"): "Location is off".
- `src/tests/zone_watch.rs::the_locate_page_tells_the_floor_the_watch_and_the_mock_count` (`data-row="mock-ignored"`): hook stays on the `details`.
- `src/tests/zones.rs::the_zones_page_says_what_the_location_setting_means_for_zones` ("Zones can&#39;t see where the phone is."): still in the page (panel).

### Finn › Finn telefonen (`device_find.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `locate.actions.footer` | En frakoblet telefon får Ring og Oppdater posisjon innen en time, Lås og Slett når den er tilbake. | (moved, unchanged) - first `<p>` of the Lock (i), the footer (i) alone | (moved, unchanged) | P2 | moved |
| `locate.wipe_not_ready.sub` | Til da avviser Android en sletting. Dette kan skje etter en oppdatering av Vibb One. | (moved, unchanged) - behind the alarm's (i) | (moved, unchanged) | P1 form | moved |

KEEP: `locate.erase.question` and the type-the-name confirm unchanged. Tests: none pin the moved texts by position (`src/tests/command_receipts.rs::find_phone_says_when_a_wipe_would_be_refused` reads the title, unchanged).

### Finn › Posisjonsinnstillinger (`device_location_settings.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `locate.settings.off_erases` | - | Av sletter posisjonene. | Off erases the locations. | KEEP (irreversible, visible) | added |
| `locate.settings.footer` | Hver posisjon bruker litt batteri; … Av slår av posisjon på telefonen og sletter posisjonene. | (moved, unchanged) - into the footer's (i) | (moved, unchanged) | P2 | moved |
| `locate.settings.last_position` | En telefon som slås av eller får lite batteri, sender en siste posisjon, … | (moved, unchanged) - same (i) (`data-note="last-position"` on its `<p>`) | (moved, unchanged) | P2 | moved |

Template: `<div class="group-footer">{{ locate.settings.off_erases }} <details class="info">…footer, last_position…</details></div>` - the KEEP exception to P2: the one irreversible consequence stays visible.

### Finn › Posisjoner (`device_locations.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `locate.locations.footer` | Nyeste først. Behold historikk i, under Posisjonsinnstillinger i Finn, bestemmer hvor lenge de lagres; den nyeste lagres alltid. | Behold historikk i, under Posisjonsinnstillinger, bestemmer hvor lenge de lagres; den nyeste lagres alltid. | Keep history for, under Location Settings, decides how long they are kept; the newest is always kept. | 2, 4 | words |

Template: the footer = (i) alone (aria `common.more_info`).

### Finn › Soner (`device_zones.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `locate.zones.need_regularly_sub` | Med Bare når du spør melder telefonen en posisjon bare når du trykker Oppdater posisjon. | (moved, unchanged) - behind the alarm's (i) | (moved, unchanged) | P1 form | moved |
| `locate.zones.unlocked` | Barnet kan slå av posisjon på telefonen; da ser sonene ingenting. | Posisjon er ikke låst | Location isn't locked | 5 (orange link row to Avansert) | words |
| `locate.zones.unlocked_info` | - | Barnet kan slå av posisjon på telefonen; da ser sonene ingenting. Slå på Hold posisjon på under Avansert. | Your child can turn Location off on the phone; zones then see nothing. Turn on Keep location on under Advanced. | 3 (into the footer (i)) | added |
| `locate.zones.unlocked_sub` | Hold posisjon på, under Avansert | - | - | 3 (the link row goes there) | deleted |
| `locate.watch.cant` | - | Telefonen kan ikke følge med på sonene | The phone can't watch the zones | 5 (one orange line) | added |
| `locate.watch.location_off` | Posisjon er av på telefonen, så den kan ikke følge med på sonene. | Posisjon er av på telefonen. | Location is off on the phone. | 4 (the reason behind the (i)) | words |
| `locate.watch.no_background` | Telefonen kan ikke bruke posisjonen i bakgrunnen ennå, så den kan ikke følge med på sonene. | Telefonen kan ikke bruke posisjonen i bakgrunnen ennå. | The phone can't use its location in the background yet. | 4 | words |
| `locate.zones.footer_off` | Posisjon er av for denne telefonen, så sonene ser ingenting. | Posisjon er av for denne telefonen. | Location is off for this phone. | 4 (the reason behind the (i)) | words |
| `locate.zones.footer_watched` | Telefonen følger med på sonene selv og sender en posisjon … | (moved, unchanged) - footer (i) alone | (moved, unchanged) | P2, 5 | moved |
| `locate.zones.footer_interval` | Ankomster og avreiser vises etter to posisjoner på samme side. | (moved, unchanged) - footer (i) | (moved, unchanged) | P2 | moved |
| `locate.zones.footer_on_request` | Sonene vurderes ut fra posisjonene Oppdater posisjon henter. | (moved, unchanged) - footer (i) | (moved, unchanged) | P2 | moved |
| `locate.zones.within` | Det vil si innen omtrent to intervaller (her {within}). | (moved, unchanged) - footer (i) | (moved, unchanged) | P2 | moved |
| `locate.watch.watching.*`, `locate.watch.pending`, `locate.watch.no_motion`, `locate.watch.not_set_up` | Følger med på {count} soner på telefonen. … | (moved, unchanged) - footer (i), last `<p data-zone-watch>` | (moved, unchanged) | 5 (fine or pending: quiet, behind the (i)) | moved |
| `locate.zones.footer_empty` | En sone sier fra når telefonen kommer eller drar, for eksempel Hjemme eller Skolen. | (moved, unchanged) - footer (i) alone | (moved, unchanged) | P2 | moved |

Template:
- `zones-need-regularly`: title + (i) (P1 markup).
- `location-unlocked`: `<a class="row" … data-note="location-unlocked"><span class="row-label tone-orange">{{ zones.unlocked }}</span> chev</a>`, no sub (link unchanged: `/advanced#restrictions`).
- New orange line above the group when `zones.cant_watch` is `Some(reason)`: `<div class="attention attention-action" data-warning="zones-cant-watch">` title `locate.watch.cant`, (i) with the reason (P1 markup).
- The footer: `<div class="group-footer"><details class="info">` with, in order: `footer_empty` (no zones) or `zones.footer` (+ `within`), `unlocked_info` (when `location_unlocked`), `<p data-zone-watch>{{ watch_line }}</p>` (when set).

State logic:
- `locate::zones_view`: `ZonesView` gains `cant_watch: Option<String>` - `Some(t("locate.zones.footer_off"))` when `some && mode == "off"`; `Some(watch reason)` when `some && mode == "interval"` and the watch says `!location_on` (`locate.watch.location_off`) or `!background_location` (`locate.watch.no_background`); else `None`. `footer` is `None` for `mode == "off"` (said by the orange line).
- `locate::watch_line`: returns `None` for the two problem cases (they are `cant_watch`); `not_set_up`, the count, `pending`, `no_motion` stay as the quiet text behind the (i).

Tests:
- `src/tests/zone_watch.rs::the_locate_page_tells_the_floor_the_watch_and_the_mock_count` (`data-zone-watch>Watching 1 zone on the phone.<`, `locate.watch.location_off`): the watch text is now in the panel (`<p data-zone-watch>`); the location-off case asserts `data-warning="zones-cant-watch"` and "Location is off on the phone.".
- `src/tests/zones.rs::the_zones_page_says_what_the_location_setting_means_for_zones` ("within about two intervals (here 1 hour)", `data-note="location-unlocked"`, `off.zones`): texts move into the panel; Off with zones shows `zones-cant-watch`.

### Finn › Sone (`device_zone.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `locate.zone.map_footer` | Trykk på kartet for å flytte midten. En ankomst eller avreise teller etter to posisjoner på samme side, minst ett minutt fra hverandre. | Trykk på kartet for å flytte midten. | Tap the map to move the centre. | 2 (the instruction stays, the rule goes to the notify (i)) | words |
| `locate.zone.counts_info` | - | En ankomst eller avreise teller etter to posisjoner på samme side, minst ett minutt fra hverandre. | An arrival or a departure counts after two locations on the same side, a minute or more apart. | 2 | added |
| `locate.zone.notify_footer` | For alle foreldre. Hver av dere kan slå av sonevarsler under Varsle meg om. | (moved, unchanged) - footer (i) alone under Varsler, with `counts_info` | (moved, unchanged) | P2 | moved |
| `locate.zone.notify_arrive` | Varsle når de kommer | Kommer | Arrive | 4 (the header says Varsler) | words |
| `locate.zone.notify_leave` | Varsle når de drar | Drar | Leave | 4 | words |

KEEP: `locate.zone.delete_question` unchanged. Tests: `src/tests/locate_page.rs::the_locate_tab_speaks_norwegian` (`notify_arrive` «Varsle når de kommer»): «Kommer».

## Apps tab and catalog

### Apper (fanen) (`apps_list.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `apps.list.footer` | Appene her holdes oppdatert på alle telefoner som har dem. | - | - | 4 | deleted |

Tests: `src/tests/tracked_apps.rs::the_apps_tab_lists_the_phones_and_the_catalog` ("Apps here are kept up to date"): drop.

### Legg til app (`tracked_app_add.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `apps.add.wait` | Vent før en oppdatering kommer til telefonene | Ventetid | Wait | 1 | words |
| `apps.wait.footer` | Nye versjoner venter så lenge før telefonene får dem, … 0 = med en gang. | (moved, unchanged) - field (i) on «Ventetid» (here and the catalog page's (i)) | (moved, unchanged) | P2 | moved |
| `apps.wait.first_footer` | Første versjon venter også - Oppdater nå på appens side sender den med en gang. | (moved, unchanged) - same field (i), when `first_waits` | (moved, unchanged) | P2 | moved |
| `apps.add.source_footer` | Telefonene henter hver nye versjon selv. | - | - | 4 | deleted |
| `apps.add.fdroid_one_source` | F-Droid signerer apper med sin egen nøkkel: en app fra F-Droid kan ikke senere bytte til GitHub eller Play uten å installeres på nytt. | En app fra F-Droid kan ikke senere bytte til GitHub eller Play uten å installeres på nytt. | An app from F-Droid can't later switch to GitHub or Play without a reinstall. | 2 (KEEP: an irreversible choice, short and visible) | words |
| `apps.add.prereleases_footer` | For et repo som bare publiserer forhåndsutgivelser. | (moved, unchanged) - footer (i) alone under the switch | (moved, unchanged) | P2 | moved |

Template: the wait row gets a field (i) (`details.info.field-info` after its label; the row is a `label.row` with a number input, so the (i) sits after the `row-unit` span) with `wait.footer` (+ `first_footer`); its footer `<p>` goes. Source footer goes. `data-note="one-source"` keeps its visible line.

Tests: `src/tests/fdroid.rs::an_fdroid_app_is_added_with_its_repo_and_package` ("F-Droid signs apps with its own key: …"): the new line.

### App i katalogen (`tracked_app_detail.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `apps.app.switches_about` | Om disse bryterne | - | - | 2 (the label row goes; `switches_aria` stays the aria-label) | deleted |
| `apps.update.held_about` | Hva {version} ber om | (unchanged) - now only the aria-label of the held row's (i) | (unchanged) | 2 | moved |
| `apps.update.wait_about` | Om ventetiden | (unchanged) - now only the aria-label of the waiting row's (i) | (unchanged) | 2 | moved |
| `apps.app.prereleases_sub` | For et repo som bare publiserer forhåndsutgivelser | (moved, unchanged) - into the Oppdateringer footer (i) | (moved, unchanged) | P3 | moved |
| `apps.app.always_ask_sub` | Av: en oppdatering som ikke ber om noe nytt, kommer til telefonene av seg selv. | (moved, unchanged) - same (i) | (moved, unchanged) | P3 | moved |
| `apps.wait.footer` (as the wait row's sub) | Nye versjoner venter så lenge … | (moved, unchanged) - same (i) | (moved, unchanged) | P3 | moved |
| `apps.app.wait` | Vent før oppdateringer | Ventetid | Wait | 1 | words |
| `apps.app.is_launcher` | Dette er launcher-appen | Launcher | Launcher | 1 | words |
| `apps.update.published.one` / `.other` | Publisert {when} · oppdateringer av denne appen venter {count} time(r) | - | - | 4 | deleted |
| `apps.update.found.one` / `.other` | Funnet {when} · oppdateringer av denne appen venter {count} time(r) | - | - | 4 | deleted |
| `apps.update.published_at` | - | Publisert {when} | Published {when} | 4 | added |
| `apps.update.found_at` | - | Funnet {when} | Found {when} | 4 | added |
| `apps.app.fdroid_note` | Én kilde per app: F-Droid signerer den med sin egen nøkkel, … | (moved, unchanged) - behind the Kilde row's (i) | (moved, unchanged) | 1, 5 | moved |
| `apps.app.other_repo_note` | Én kilde per app: å bytte den til GitHub eller Play krever ny installasjon … | (moved, unchanged) - behind the Kilde row's (i) | (moved, unchanged) | 1, 5 | moved |
| `apps.app.launcher_footer` | Vibb One selv: på alle telefoner, og den kan ikke fjernes. | - | - | 4 (the Launcher switch and the missing Remove say it) | deleted |
| `apps.app.display_header` | Vis på telefonene som | - | - | 4 (the row «Navn og ikon» names it) | deleted |
| `apps.app.display_footer` | Telefonene bruker dette med mindre en har sitt eget. | (moved, unchanged) - footer (i) alone | (moved, unchanged) | P2 | moved |
| `apps.app.display_no_package` | Gjelder når en utgivelse sier hvilken app det er. | Venter på første utgivelse | Waits for the first release | 3 | words |
| `apps.app.no_phones_footer` | Slå den på under en telefons apper for å installere den. | (moved, unchanged) - footer (i) alone | (moved, unchanged) | P2 | moved |
| `apps.install.about` | «Installer nå» installerer den nyeste utgivelsen «Sjekk nå» fant, … Den venter bare på en samtale. | (moved, unchanged) - first `<p>` of its (i), the (i) alone | (moved, unchanged) | P2 | moved |
| `apps.app.forget_footer` | Glem dem bare ved feil app, eller når utvikleren har byttet nøkkel. | (moved, unchanged) - into the forget confirm body, before `forget_confirm` (KEEP) | (moved, unchanged) | 2 | moved |
| `apps.app.checking_since` | Startet {when}. Last inn siden på nytt for å se resultatet. | Startet {when} | Started {when} | 3 | words |
| `apps.app.candidates` | Utgivelsen sa ikke hvilken app det er. Vibb One installerte disse på en telefon som har den - velg den som er denne appen. | Velg hvilken app dette er | Pick which app this is | 5 (orange) | words |
| `apps.app.candidates_info` | - | Utgivelsen sa ikke hvilken app det er. Vibb One installerte disse på en telefon som har den. | The release didn't say which app it is. Vibb One installed these on a phone that has it. | 5 (behind the (i)) | added |

Template:
- Held release: the held row itself becomes `details.info cell` (summary = the row: orange title, sub, (i) icon with aria `h.about`), panel the existing asks text; the separate `row-label-quiet` «Hva {version} ber om» row goes.
- Waiting release: the waiting row becomes `details.info cell` (aria `apps.update.wait_about`), panel `wait_info`; the separate «Om ventetiden» row goes. `w.sub` is `published_at`/`found_at` (no hours: the title says when it comes).
- Oppdateringer: the switches and the wait row lose their `row-sub`; the `row-label-quiet` «Om disse bryterne» row is replaced by a footer (i) alone after the group (aria `switches_aria`), panel: `switches_info`, `prereleases_sub`, `always_ask_sub`, `wait.footer`.
- Kilde: the static row (`data-row="source"`) becomes `details.info cell` (label · value, panel `fdroid_note`); the editable select row gets a field (i) with `fdroid_note`. `data-note="one-source"` moves to the panel `<p>`.
- The `display_header` `<h2>` goes; the display footer = (i) alone (`display_footer`) or, without a package, the visible quiet line `display_no_package`.
- `no_phones_footer` = (i) alone; launcher footer goes; `data-install-about` footer = (i) alone with `install.about` then `install.about_info`.
- `candidates`: `<details class="info cell"><summary class="row"><span class="row-label tone-orange">{{ candidates }}</span> (i)</summary><div class="info-panel"><p>{{ candidates_info }}</p></div></details>` above the candidate rows.
- Forget: the footer goes; `forget_footer` is the confirm body's first `<p>`. KEEP `remove_confirm`, `forget_confirm`, `forget_holds`, `update.keeps_until` unchanged.

State logic:
- `tracked_apps::update_group`: the waiting row's sub uses `t_params("apps.update.published_at"/"found_at", [("when", …)])` instead of the counted `published`/`found` (the hours are gone).

Tests:
- `src/tests/tracked_apps.rs::add_app_keeps_the_extras_under_more_options`, `::the_apps_tab_lists_the_phones_and_the_catalog` ("Show on phones as"): drop the header assert.
- `src/tests/fdroid.rs::the_app_page_shows_the_fdroid_source` ("One source per app: F-Droid signs it …", `data-note="one-source"`): the text is in the Kilde row's panel.
- No test pins "updates of this app wait" or "Reload to see the result". Not a test but a log line: `tracked_apps::ensure_launcher_row`'s two warnings name the switch «This is the launcher app» - say «Launcher» with the rename.

## App push (design 112 folded in)

### App push: Settings › App-push (`settings_push.html`) and Device › Apps' group (`partials/app_push_group.html`)

Design 112 folded in, with the four points it breaks under "Words in the UI" (the audit's App-push section) fixed:
112's visible footer sentence becomes ⓘ2's first line (ⓘ2 alone); 112's `origin_hint` sub-line goes (ⓘ1 says it);
`clear_note` moves into ⓘ2; `save_note` stays as the one line under the field while no address is saved (KEEP: a save
turns app push on for every phone); "Sist sett utenfra" turns orange when the address isn't reached; `no_listener` is one
short orange line with the env var behind its ⓘ. 112's merged "Levering" group, its four ⓘs' texts, the "App-push" name
and its `render_settings`/`DeliveryRows` changes stand as 112 wrote them unless a row below says otherwise. 112's optional
"Telefoner" group is not built (no new features).

Settings › App-push, nb, typical state:

```
App-push                                              ‹ Innstillinger
┌ Telefonenes offentlige adresse ⓘ1
│ [ https://phones.example.com ]
│ [ Lagre ]
└                                                      ⓘ2
LEVERING
┌ Sist sett utenfra                                 14:05  ⓘ3
│ Vanligvis levert på                               2 s    ⓘ4
└   95 % innen 9 s · 38 push siste døgn
```

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `app_push.settings.title` | Push for apper | App-push | App push (unchanged) | 112 | words |
| `app_push.group` | Push for apper | App-push | App push (unchanged) | 112 | words |
| `app_push.switch` | Push for apper | App-push | App push (unchanged) | 112 | words |
| `app_push.error.read` | Kunne ikke lese push for apper - sjekk serverloggen. | Kunne ikke lese app-push - sjekk serverloggen. | (unchanged) | 112 | words |
| `app_push.turn_off_dots` | Slå av push for apper… | Slå av app-push… | (unchanged) | 112 | words |
| `app_push.settings.origin_hint` | Adressen appenes servere sender til, … uten noe etter navnet. | - | - | 1, 2 (ⓘ1 says it; the placeholder shows the form) | deleted |
| `app_push.settings.prefilled` | Fylt inn fra Telefonoppsett - ikke lagret ennå. | Fra Telefonoppsett - ikke lagret ennå. | From Phone Setup - not saved yet. | 112 | words |
| `app_push.settings.previous` | {origin} virker også til {when}, mens appene flytter til den nye adressen. | {origin} virker også til {when}, mens appene flytter over. | {origin} also works until {when}, while the apps move over. | 112 | words |
| `app_push.settings.save_note` | Når du lagrer, får alle telefonene push for apper (du kan slå det av per telefon) | Når du lagrer, slås app-push på for alle telefonene med bryteren på. | Saving turns app push on for every phone with its switch on. | KEEP (QA L7: as accurate as the handler); shown only while no address is saved | words + state |
| `app_push.settings.clear_note` | Tøm feltet og lagre for å slå av push for apper på alle telefonene. | - | - | 3 (into `app_push.settings.info`) | deleted |
| `app_push.settings.footer` | Med push for apper får apper på en telefons liste (Element X først) … får ikke push for apper. | - | - | 2 (112's visible footer goes; its sentence opens ⓘ2) | deleted |
| `app_push.settings.no_listener` | Denne serveren har ingen egen lytter for telefonene (DEVICE_BIND_ADDR), så push for apper blir ikke servert. Se DEPLOY.md, «App push». | App-push virker ikke: serveren har ingen lytter for telefonene | App push doesn't work: the server has no phone listener | 5 | words |
| `app_push.settings.no_listener_info` | - | Sett DEVICE_BIND_ADDR, serverens port for telefonene. Se DEPLOY.md, «App push». | Set DEVICE_BIND_ADDR, the server's port for the phones. See DEPLOY.md, “App push”. | 5 (behind the line's ⓘ) | added |
| `app_push.settings.origin_about` | - | Om adressen | About the address | 112 ⓘ1 (aria-label) | added |
| `app_push.settings.origin_info` | - | Appens server sender varslene til denne adressen, så den må kunne nås fra internett. En server som bare er på tailnettet, kan ikke brukes. Skriv https:// og navnet, ingenting etter. | The app's server sends its notifications to this address, so it must be reachable from the internet. A server only on the tailnet can't be used. Type https:// and the name, nothing after it. | 112 ⓘ1 | added |
| `app_push.settings.info_label` | - | Om app-push | About app push | 112 ⓘ2 (aria-label; also Device › Apps' footer ⓘ) | added |
| `app_push.settings.info` | - | Varsler fra apper som Element X går gjennom Vibb One og denne serveren, uten Google. Hver app på telefonen får sin egen adresse på denne serveren. Serveren lagrer ikke meldingsteksten og holder et varsel i høyst et døgn. Hver telefon kan slå av app-push under Apper › App-push. Tøm feltet og lagre for å slå det av for alle telefonene. | Notifications from apps like Element X come through Vibb One and this server, without Google. Each app on the phone gets its own address on this server. The server keeps no message text and holds a notification for at most a day. Each phone can turn app push off under Apps › App push. Empty the field and save to turn it off for every phone. | 2 (112 ⓘ2 + its footer sentence + `clear_note`) | added |
| `app_push.settings.seen` | Sist sett utenfra | (unchanged; now a row label) | (unchanged) | 112 | unchanged |
| `app_push.settings.never` | Ikke ennå | (unchanged; orange when the address isn't reached, below) | (unchanged) | 5 | state |
| `app_push.settings.seen_info` | - | Sist en app eller appens server nådde adressen fra internett. Serveren sjekker aldri seg selv. Står det «Ikke ennå» lenge etter at en app er koblet til, når de ikke adressen. | When an app or the app's server last reached the address from the internet. The server never checks itself. If it says “Not yet” long after an app has connected, they can't reach the address. | 112 ⓘ3 | added |
| `app_push.settings.discovery` | Adressen sjekket av en app | - | - | 112 | deleted |
| `app_push.settings.pushed` | Push mottatt | - | - | 112 | deleted |
| `app_push.settings.seen_footer` | Bare tidspunkter, for alle telefonene samlet. Serveren sjekker aldri seg selv utenfra. | - | - | 112 | deleted |
| `app_push.delivery.group` | Levering til telefonene | Levering | Delivery | 112 | words |
| `app_push.delivery.usually` | Vanligvis | Vanligvis levert på | Usually delivered in | 112 | words |
| `app_push.delivery.none` | Ingen push levert mens telefonen var tilkoblet, siste døgn | Ingen push levert siste døgn | No pushes delivered in the last 24 hours | 112 | words |
| `app_push.delivery.info` | - | Fra serveren tar imot et varsel til telefonen har fått det, for alle telefonene samlet. Bare varsler som kom mens telefonen var tilkoblet, er talt med. | From the server receiving a notification to the phone having it, for every phone together. Only notifications that came while the phone was connected are counted. | 112 ⓘ4 | added |
| `app_push.delivery.sub_p95` | - | 95 % innen {p95} · {count} push siste døgn | 95% within {p95} · {count} pushes in the last 24 hours | 112 | added |
| `app_push.delivery.sub.one` | - | {count} push siste døgn | {count} push in the last 24 hours | 112 | added |
| `app_push.delivery.sub.other` | - | {count} push siste døgn | {count} pushes in the last 24 hours | 112 | added |
| `app_push.delivery.p95`, `.pushes`, `.pushes_value`, `.pushes_capped`, `.footer` | (112's deletions) | - | - | 112 | deleted (5 keys) |

Device › Apps' App-push group:

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `app_push.switch_sub` | Apper på listen kan få varsler gjennom Vibb, uten Google | - | - | 1 (into `app_push.footer`) | deleted |
| `app_push.needs_update` | Trenger en nyere Vibb One på telefonen | Vibb One må oppdateres | Vibb One needs an update | 5 (orange `row-sub`) | words + tone |
| `app_push.no_origin` | Sett telefonenes offentlige adresse i Innstillinger › Push for apper først | Mangler adressen - se Innstillinger › App-push | No address - see Settings › App push | 5 (orange `row-sub`) | words + tone |
| `app_push.missing_title` | - | {app} bruker ikke Vibb for varsler | {app} isn't using Vibb for notifications | 5 (one orange line) | added |
| `app_push.missing` | {app} bruker ikke Vibb for varsler. I {app}: Innstillinger › Varsler › Avansert › Push-leverandør › Vibb One, én gang for hver konto. | I {app}: Innstillinger › Varsler › Avansert › Push-leverandør › Vibb One, én gang for hver konto. | In {app}: Settings › Notifications › Advanced › Push provider › Vibb One, once for each account. | 3 (behind the line's ⓘ) | words |
| `app_push.missing_open` | {app} bruker ikke Vibb for varsler. Åpne {app} én gang, så velger den Vibb One selv. | Åpne {app} én gang, så velger den Vibb One selv. | Open {app} once: it picks Vibb One by itself. | 3 (behind the line's ⓘ) | words |
| `app_push.waiting` | Varsler til appene på telefonen har ventet på serveren siden {when}, selv om telefonen har vært i kontakt etterpå. Blir det stående, start telefonen på nytt. | Varsler venter på serveren siden {when} | Notifications waiting on the server since {when} | 5 | words |
| `app_push.waiting_info` | - | Telefonen har vært i kontakt etterpå. Blir det stående, start telefonen på nytt. | The phone has been in touch since. If it stays, restart the phone. | 3 (behind the row's ⓘ) | added |
| `app_push.delivery_line_p95` | Vanligvis levert på {median}, 95 % innen {p95} ({count} push mens den var tilkoblet, siste døgn) | - | - | 1 (p95 lives on Settings › App-push) | deleted |
| `app_push.delivery_line.one` | Vanligvis levert på {median} ({count} push mens den var tilkoblet, siste døgn) | Vanligvis levert på {median} · {count} push siste døgn | Usually delivered in {median} · {count} push in the last 24 hours | 1, 3 ("while connected" into the footer ⓘ) | words |
| `app_push.delivery_line.other` | (as `.one`) | Vanligvis levert på {median} · {count} push siste døgn | Usually delivered in {median} · {count} pushes in the last 24 hours | 1, 3 | words |
| `app_push.noisy` | Mange push: rundt {count} om dagen - hvert av dem vekker telefonen, og det tapper batteriet | Rundt {count} push om dagen | About {count} pushes a day | 5 | words |
| `app_push.noisy_info` | - | Hvert push vekker telefonen, og det tapper batteriet. | Each push wakes the phone, which drains the battery. | 3 (behind the row's ⓘ) | added |
| `app_push.signed` | Bare push signert av appens server | - | - | 5 (the good state; the fact moves into `app_push.footer`) | deleted |
| `app_push.outside` | Varslene til {app} går gjennom en ekstern tjeneste (som regel unifiedpush.org), … se «Sist sett utenfra» under Innstillinger › Push for apper. | Går via en ekstern tjeneste | Goes via an outside service | 5 | words (param `{app}` leaves both catalogs) |
| `app_push.outside_info` | - | Varslene til {app} går gjennom en ekstern tjeneste (som regel unifiedpush.org), utenfor familiens server. Vibb One ber {app} sjekke adressen på nytt når telefonen er i bruk. Blir dette stående i flere dager, når ikke appen adressen: se «Sist sett utenfra» under Innstillinger › App-push. | {app}'s notifications go through an outside gateway (usually unifiedpush.org), outside the family's server. Vibb One asks {app} to check the address again when the phone is in use. If this stays for days, the app can't reach the address: see “Last seen from outside” in Settings › App push. | 3 (behind the row's ⓘ) | added |
| `app_push.vapid_refused` | Push som ikke var signert av appens server, ble avvist, sist {when} | Push avvist, sist {when} | Pushes refused, last {when} | 5 (every refusal's visible line) | words |
| `app_push.vapid_refused_unsigned` | - | Pushen var ikke signert av appens server. | The push wasn't signed by the app's server. | 3 (behind the row's ⓘ) | added |
| `app_push.vapid_refused_time` | Push signert av appens server ble avvist fordi serverens klokke ikke stemmer, sist {when}. Sjekk klokken på serveren | Serverens klokke stemmer ikke. Sjekk klokken på serveren. | This server's clock doesn't match. Check the server's clock. | 3 (behind the row's ⓘ; `{when}` leaves both catalogs) | words |
| `app_push.vapid_refused_address` | Push signert av appens server ble avvist fordi den var sendt til en annen adresse enn serverens, sist {when}. Sjekk serverens adresse i Innstillinger | Pushen var sendt til en annen adresse enn serverens. Sjekk telefonenes offentlige adresse i Innstillinger › App-push. | The push was sent to another address than this server's. Check the phones' public address in Settings › App push. | 3 (behind the row's ⓘ; `{when}` leaves) | words |
| `app_push.refused_fix` | Push som ikke var signert av appens server, ble avvist, sist {when}. Dette har vart i over et døgn. Å åpne {app} … gir Stopp… og Tillat igjen appen en ny adresse. | Dette har vart i over et døgn. Å åpne {app} på telefonen én gang fikser det som regel; hvis ikke, gir Stopp… og Tillat igjen appen en ny adresse. | This has gone on for over a day. Opening {app} on the phone once usually fixes it; if not, Stop… and Allow Again gives it a new address. | 3 (behind the row's ⓘ; `{when}` leaves) | words |
| `app_push.footer` | En tillatt app kobler seg til Vibb One av seg selv og får en privat adresse; … og kontonavnene blir på telefonen. | Apper på listen kan få varsler gjennom Vibb, uten Google: en tillatt app kobler seg til Vibb One av seg selv og får en privat adresse, og varslene vekker den gjennom denne serveren. Har appen en nøkkel, tas bare push signert av appens server imot. Ingen meldingstekst lagres, push holdes i høyst et døgn, og kontonavnene blir på telefonen. «Vanligvis levert på» teller bare push som kom mens telefonen var tilkoblet. | Allowed apps can get notifications through Vibb, without Google: an allowed app connects to Vibb One by itself and gets a private address, and its notifications wake it through this server. When the app has a key, only pushes signed by the app's server are taken. No message text is kept, pushes are held at most a day, and the account names stay on the phone. “Usually delivered in” counts only pushes that came while the phone was connected. | 2 (a footer ⓘ alone, aria `app_push.settings.info_label`) | words |
| `app_push.off_question` | Apper som bruker Vibb for varsler, får ingen mens dette er av, til hver av dem settes tilbake til Google i sine egne innstillinger (Element X: Innstillinger › Varsler › Avansert › Push-leverandør). | Appene får ingen varsler før hver av dem settes tilbake til Google i sine egne innstillinger (Element X: Innstillinger › Varsler › Avansert › Push-leverandør). | The apps get no notifications until each is switched back to Google in its own settings (Element X: Settings › Notifications › Advanced › Push provider). | 1 (KEEP: the consequence stays) | words |
| `app_push.stop_question` | {app} får ingen varsler gjennom Vibb før du tillater det igjen. Appen prøver å koble til på nytt og kan vise barnet en melding om at varsler ikke virker; … tilbake til Google. | {app} får ingen varsler gjennom Vibb før du tillater det igjen, og kan vise barnet at varsler ikke virker. | {app} gets no notifications through Vibb until you allow it again, and may show your child that notifications don't work. | 1 (KEEP: the consequence stays) | words |

Unchanged: `app_push.hub.on/off`, `app_push.accounts.*`, `app_push.last_delivered`, `app_push.never_delivered`,
`app_push.no_delivery`, `app_push.stop*`, `app_push.stopped`, `app_push.allow_again`, `app_push.delivery.cap_note`,
`app_push.delivery.under_second/seconds/minutes/hours`, `app_push.settings.origin`, `.origin_example`, `.refused`,
every `device.attention.app_push_*` title.

Template (`settings_push.html`), on top of 112's:
- `no_listener`: `<div class="attention attention-action" data-warning="no-listener">` with the title and an ⓘ
  (`common.how_to_fix`, panel `no_listener_info`) - the P1 markup.
- The field hint slot: the error, else the prefill line, else nothing (no `origin_hint`); ⓘ1 (`details.info.field-info`
  after the label, aria `origin_about`, panel `origin_info`).
- `save_note` only when `!set`, before Save, `data-row="save-note"` kept; no `clear_note` anywhere.
- After the form group: `<div class="group-footer"><details class="info">` (aria `app_push.settings.info_label`, panel
  `app_push.settings.info`) - no sentence beside it. The `#saved` flash and the form's `#push` landing unchanged.
- 112's one `<section id="delivery">` (header `app_push.delivery.group`): row `data-row="seen"` as a `details.info.cell`
  (ⓘ3, panel `seen_info`) whose value is the time, or `never` - with `row-value-action` (orange) when `seen_late`
  (below); row `data-row="delivery-usually"` as a `details.info.cell` (ⓘ4) with its `row-sub`; or the `delivery-none`
  note. No footer.

Template (`partials/app_push_group.html`):
- `missing`: each a `<div class="attention attention-action" data-warning="up-missing">` with `missing_title` and an ⓘ
  holding `missing`/`missing_open` (P1 markup), above the group as today.
- The switch row: on - `row-sub tone-orange` only for `needs_update`, else for `no_origin` when no address is set, else
  no sub; off - no sub.
- `waiting`: one `details.info.cell` row (`data-warning="up-waiting"`), `summary.row` with the line in `text-alarm` and
  the ⓘ, panel `waiting_info`.
- The delivery row-note stays (`data-row="app-push-delivery"`).
- An app row with any of `noisy`, `outside`, `refused` becomes `details.info.cell` with `summary.row` (tile, label, its
  sub-lines, the ⓘ icon) and a panel of their details in that order (`noisy_info`, `outside_info`, the refusal's cause);
  the short orange sub-lines keep their `data-warning` (`noisy`, `outside`, `vapid-refused`); `no-delivery` stays a plain
  orange sub-line. No `signed` line. The Stop… sheet stays its own cell below.
- `<p class="group-footer">app_push.footer</p>` → a footer ⓘ alone (aria `app_push.settings.info_label`, panel
  `app_push.footer`).

State logic:
- `app_push::render_settings`: 112's `seen` (the later of `last_discovery_at` and `MAX(last_push_at)`) and
  `DeliveryRows { usually, sub }`; new `seen_late: bool` = `seen` is `None` and an `up_registrations` row has
  `created_at` older than one hour (`SELECT 1 FROM up_registrations WHERE created_at < datetime('now', '-1 hour') LIMIT
  1`) - the value «Ikke ennå» turns orange; a server with no registration, or one younger than an hour, keeps it grey.
  `set` now also picks `save_note` (shown only while `!set`).
- `app_push::group`: `missing` → `Vec<(String, String)>` (`missing_title`, fix); the delivery line is always
  `t_count("app_push.delivery_line", …)` (no p95 branch), the cap note appended as today; `AppPushRow.signed` and its
  key check go; `noisy` → `(short, noisy_info)`; `outside` → `(short, outside_info)`; `refused` → `(short, cause)`:
  short is `vapid_refused` with `{when}` from the stamp in every case, cause is `refused_fix` (raised, with `{app}`),
  else `vapid_refused_time` / `vapid_refused_address` / `vapid_refused_unsigned` by `why`.

Tests (`server/src/tests/unified_push.rs`):
- `the_apps_group_lines_of_108b`: drop the three `data-row="signed"` asserts (the line is gone); "UP-Example&#39;s
  notifications go through an outside gateway" still matches (now `outside_info` in the panel); "Pushes not signed by
  the app" → "Pushes refused" (visible) and "wasn&#39;t signed by the app" (panel); in the `time`/`address` loop "clock
  doesn" and "another address" still match, and the negative assert becomes `!contains("wasn&#39;t signed")`;
  "Open FluffyChat once" still matches.
- `attention_rows_of_108c` (1835): "This has gone on for over a day" still matches.
- `delivery_time_on_the_pages`: "Delivery to the phones" → "Delivery"; drop the `delivery-p95` assert; "3 in the last
  24 hours" → "3 pushes in the last 24 hours"; the apps line → "Usually delivered in 2 s · 3 pushes in the last 24
  hours".
- `the_apps_group`: `data-warning="up-missing"` stays on the new line.
- Add to `delivery_time_on_the_pages` (or a sibling): with a registration two hours old and no discovery/push, the seen
  row's value carries `row-value-action`; with one ten minutes old it doesn't.
- `tests/i18n_pages.rs::nb_pages_have_no_english` reads `/settings/push`: «App-push» must pass its word list (check).
- Designs 108 §3.1/§3.10 and 108c §7.4 get a one-line "words changed (design 113)" note; `docs/notes/server/pwa.md` /
  `unified-push.md` where they list the page's groups.

## Music, Settings, account, sign-in and setup

### Musikk (`music.html`, `partials/music_card.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.tab.footer.one` | {count} av {max} innslag. Hver telefon får dem som er huket av for den. | {count} av {max} innslag | {count} of {max} entries | 4/5 | state: template (see below) |
| `music.tab.footer.other` | (same) | {count} av {max} innslag | {count} of {max} entries | 4/5 | state: template |
| `music.tab.edit_footer` | Rekkefølgen her er rekkefølgen på omslagene på telefonene. | - | - | P5 | deleted |
| `music.tab.empty` | Ingenting ennå. Legg til en podkast, en NRK-serie eller egne filer nedenfor. | Ingenting ennå | Nothing yet | P5 | words |

Template:
- The library's `<p class="group-footer">`: the edit-mode branch goes; the count shows only near the cap:
  `{% if !edit && entry_count * 10 >= max_entries * 9 %}`. No anchor changes.
- `partials/music_card.html`: a card's status block for tone `ok` ("Sjekket for 2 timer siden") is hidden on the
  library cards only: the block stays in the DOM (`static/music-cards.js` swaps blocks by `data-entry`), hidden by CSS
  `.music-card .music-status-ok { display: none; }` scoped to the library's card (not `music_entry.html`'s
  `.row.music-card`: give the library card a class, e.g. `music-card-tile`, and scope the rule to it). Busy and error
  blocks show as today. `music.status.checked` is unchanged and still shown on the entry page.

### Musikk › innslag (`music_entry.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.entry.spotify_order` | - | Rekkefølge: som i Spotify | Order: as in Spotify | 4 | added |
| `music.entry.spotify_order_artist` | Spotify velger rekkefølgen: en artist spilles slik Spotify gjør det. | - | - | P5 | deleted |
| `music.entry.spotify_order_context` | Spotify velger rekkefølgen: ingen innlogget konto får vise sporene her, så telefonene spiller det slik Spotify gjør. | - | - | P5 | deleted |
| `music.card.artist_context` | En artist spilles slik Spotify velger: ingen sporliste, … | (moved, unchanged) into the order line's (i) | | 2 | moved; state: `music.rs::SweepView::spotify_card` |
| `music.spotify.context` | Ingen innlogget konto eier denne spillelisten … (55 words) | (moved, unchanged) into the order line's (i), and on `music_spotify.html` | | 2 | moved; state: `spotify_card` |
| `music.entry.spotify_streams` | Spotify strømmes: ingenting lagres på telefonen … | (moved, unchanged) into the order line's (i), last `<p>` | | 2 | moved |
| `music.card.audiobook_short` | - | Lydbok: trenger lyttetid på barnets konto | Audiobook: needs listening time on the child's account | 5 | added |
| `music.card.audiobook` | Dette er en lydbok. En lydbok fra Spotify spilles bare med lyttetid … | (moved, unchanged) behind the audiobook line's (i) | | 5 | moved; state: `spotify_card` |
| `music.entry.cover_footer` | JPEG, PNG eller WebP på opptil 10 MB, beskåret kvadratisk; … | (moved, unchanged) into a field (i) on the picture field | | 2 | moved |
| `music.entry.files_footer` | mp3, m4a, m4b, ogg, opus, flac eller wav, opptil 1 GB hver. Hold siden åpen … | (moved, unchanged) into a field (i) on the files field | | 2 | moved |
| `music.entry.own_footer` | Egne filer spilles i sporrekkefølge (…), og alle ligger alltid på telefonen. | (moved, unchanged) into a footer (i) of that group | | 2 | moved |
| `music.entry.no_phones` | Ingen telefon har det ennå. | Ingen telefoner ennå | No phones yet | 3 | words |
| `music.entry.no_phones_footer` | Huk det av på en telefons side (Musikk) for å sende det dit. | - | - | P5 | deleted |
| `music.entry.resume_sub` | Av: starter fra begynnelsen. | - | - | P3 | deleted |
| `music.entry.lan` | På hjemmenettet eller tailnettet. | - | - | P5 (the card's "hjemmenett" says it) | deleted; state: template + `SweepCard.lan` |

Template:
- Spotify, in the form where the order select would be (both the artist and the context branch): one
  `<p class="field-hint" data-hint="spotify-order">` with `music.entry.spotify_order` and a
  `<details class="info">` (aria `common.more_info`) holding `music.card.artist_context` (artist) or
  `music.spotify.context` (playlist), then `music.entry.spotify_streams`. A non-context Spotify entry (the select shows):
  the streams sentence goes into a field (i) on the select's label (as `music.entry.offline_info_label`'s).
  `data-hint="spotify-streams"` goes.
- The resume switch loses its `row-sub`. `<p class="group-footer">{{ own_footer }}` → P2 footer (i). Cover and files
  footers → `details.info.field-info` after the field labels. The `#phones` group's footer goes.
- `{% if sweep.lan %}` row goes; drop `SweepCard.lan` if nothing else reads it.

State logic:
- `handlers/music.rs::SweepView::spotify_card`: in context mode it no longer pushes `artist_context`/`spotify.context`
  into `notes` (the form's order line carries them); `artist_refused` stays a note. The audiobook note becomes
  `SweepCard.audiobook: bool` instead of a note; `music_entry.html` renders it as one orange line
  (`attention attention-action`, title `music.card.audiobook_short`, `music.card.audiobook` behind its (i), P1 markup)
  at the top of `#status`.

Tests:
- `tests/shell.rs:1107` asserts `data-hint="spotify-streams"` (QA M9) -> `data-hint="spotify-order"`.
- `tests/music_spotify.rs::an_artist_entry_page_has_the_artist_hint_not_the_playlist_one`: asserts
  "Spotify picks the order: an ar…" - now the artist text in the (i) (`music.card.artist_context`) and
  `data-hint="spotify-order"` with "Order: as in Spotify".
- `tests/music_spotify.rs::context_playlists_short_links_and_kinds`, `::the_cards_and_check_now`: "No logged-in account
  owns this" - still on the entry page (inside the (i)); check they read the entry page or the add page, not a note row.
- `tests/music_sweep.rs::the_cards_say_what_the_sweep_knows`: asserts "On your home network or tailnet" - drop.
- `tests/music.rs::the_music_tab_speaks_norwegian`: "Ingen telefon har det ennå." → "Ingen telefoner ennå".

### Kategorier (`music_categories.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.categories.footer` | De runde knappene under omslagene på telefonen, i denne rekkefølgen. «Alle» er alltid der. | (moved, unchanged) into a P2 footer (i), aria `common.more_info` | | 2 | moved |
| `music.categories.add_title` | Legg til en kategori | Ny kategori | New category | 4 | words |

Tests: `tests/music.rs::the_music_tab_speaks_norwegian` reads "Legg til en kategori" → "Ny kategori".

### Importer fra Vibb (`music_import.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.import.footer` | library.json fra en Vibb-boks, maks 1 MB. Ingenting lagres før du bekrefter. | (moved, unchanged) first `<p>` of the existing (i) (`music.import.info_label`) | | P2 | moved |
| `music.import.summary.one` | {count} innslag kan legges til. Fjern haken på det du ikke vil ha. | {count} kan legges til | {count} can be added | 3/4 | words |
| `music.import.summary.other` | (same) | {count} kan legges til | {count} can be added | 3/4 | words |
| `music.import.summary_skipped.one` | {count} innslag kan legges til, {skipped} kan ikke (grunnen står ved hvert). Fjern haken … | {count} kan legges til · {skipped} kan ikke | {count} can be added · {skipped} can't | 3/4 | words |
| `music.import.summary_skipped.other` | (same) | {count} kan legges til · {skipped} kan ikke | {count} can be added · {skipped} can't | 3/4 | words |
| `music.import.phones` | Huk dem av på disse telefonene | Telefoner | Phones | 1 | words |
| `music.import.phones_footer` | En telefon der musikkbiblioteket ville blitt større enn 3 MB, får bare det som er plass til. | (moved, unchanged) into a P2 footer (i), aria `common.more_info` | | 2 | moved |
| `music.import.no_phones` | Ingen telefoner ennå - huk av innslagene på en telefons side senere. | Ingen telefoner ennå | No phones yet | 3 | words |

Template: the file footer becomes `<div class="group-footer"><details class="info">` with the moved sentence as the
panel's first `<p>`, then `info_before`/`info_after` as today. Tests: none pin these strings (grepped).

### Musikk › Innstillinger (`music_settings.html`, `partials/spotify_setup.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.setup.account_line` | Logget inn {since} - logg inn igjen før {until} | - | - | P4 (the orange `account_ends` comes 14 days before) | deleted; state: `music_spotify.rs` account view |
| `music.settings.storytel_accepted` | Storytel godtok innloggingen ({when}). | - | - | P4 | deleted; state: `music.rs::storytel_status` |
| `music.settings.storytel_refused_until` | Storytel avviste innloggingen ({when}). Ingenting prøves igjen før {until} - lagre passordet på nytt … | - | - | 5 | deleted (split below) |
| `music.settings.storytel_refused_retry` | Storytel avviste innloggingen ({when}). Den prøves igjen neste gang hylla leses - … | - | - | 5 | deleted (split below) |
| `music.settings.storytel_refused_at` | - | Storytel avviste innloggingen ({when}) | Storytel refused the login ({when}) | 5 | added |
| `music.settings.storytel_retry_until` | - | Prøves igjen etter {until}. Lagre passordet på nytt for å prøve nå. | Tried again after {until}. Save the password again to try now. | 3 | added (behind the (i)) |
| `music.settings.storytel_retry_next` | - | Prøves igjen neste gang hylla leses. Lagre passordet på nytt hvis det er endret. | Tried again the next time the shelf is read. Save the password again if it changed. | 3 | added (behind the (i)) |
| `music.settings.sweep_footer` | «Sjekk nå» på et innslag sjekker det med en gang. | (moved, unchanged) first `<p>` of its (i) | | P2 | moved |
| `music.settings.storytel_footer` | Én innlogging for familien; den vises aldri igjen. | (moved, unchanged) first `<p>` of its (i) | | P2 | moved |
| `music.setup.app_footer` | Serveren har ingen Spotify-app med seg: registrer familiens egen én gang. | (moved, unchanged) first `<p>` of its (i) | | P2 | moved |
| `music.setup.accounts_footer` | Én konto per telefon, barnets egen - aldri din. | (moved, unchanged) first `<p>` of its (i) | | P2 | moved |
| `music.settings.app_footer` | Slå den på for en telefon på telefonens side. | - | - | P5 | deleted |
| `music.settings.app_footer_none` | Følger prosjektets music-v-utgivelser; ingenting installeres før du slår den på for en telefon. | (moved, unchanged) into a P2 footer (i), aria `common.more_info` | | 2 | moved |
| `music.settings.stale_sub` | Den ble låst med en annen nøkkel (…), så telefonene får den ikke. | (moved, unchanged) behind the stale line's (i) | | P1 | moved |
| `music.setup.paste_this` | Lim inn nøyaktig dette under appens Redirect URIs: | (moved, unchanged) into a row (i) on the Redirect URI row | | 1/3 | moved |
| `music.setup.paste_this_env` | Lim inn nøyaktig dette under appens Redirect URIs (satt av serverens konfigurasjon): | (moved, unchanged) same row (i) | | 1/3 | moved |
| `music.setup.from_env` | Satt av serverens konfigurasjon | Fra .env | From .env | 3 | words |
| `music.setup.not_configured` | Ingen Spotify-app er satt opp på serveren ennå. Følg stegene nedenfor, og lagre så appens Client ID. | Ingen Spotify-app ennå | No Spotify app yet | 4 | words |
| `music.setup.replace_accounts` | Lagrer du en annen Client ID, fjernes de innloggede kontoene nedenfor (innloggingene hører til den gamle appen). | Fjern de innloggede kontoene (de hører til den gamle appen) | Remove the logged-in accounts (they belong to the old app) | 1 (KEEP: confirms a destructive change) | words |
| `music.setup.refused_sub` | Appen ble slettet, eller Spotify skrudde den av. Sjekk den på developer.spotify.com, … | (moved, unchanged) behind the refused line's (i) | | P1 | moved |
| `music.setup.no_redirect_sub` | Lagre en nedenfor. | - | - | P5 | deleted |
| `music.settings.orphans_footer` | Mest sannsynlig rester etter at en eldre sikkerhetskopi ble gjenopprettet. | (moved, unchanged) into a P2 footer (i), aria `common.more_info` | | 2 | moved |

Template:
- Every `<div class="group-footer">{{ X_footer }} <details class="info">…` on this page and in `spotify_setup.html`
  (sweep, storytel, app, accounts): the sentence leaves the footer and becomes the panel's first `<p>`.
- `data-row="stale"` and `data-row="app-refused"`: the `row-sub` becomes a `<details class="info">` (aria
  `common.more_info`) after the alarm text, panel = the moved `_sub`. `data-row="no-redirect"` loses its sub.
- The Redirect URI row (`data-row="redirect-uri"`): the `row-sub` goes; the row becomes `details.info.cell` with
  `paste_this` / `paste_this_env` in the panel; the `<code>` row under it stays.
- Each account row: `account.line` goes; the `data-plays-on` sub shows only when `spotify.phones.is_empty()` (otherwise
  the "Spiller på" select below says it). `music.setup.plays_on_phones`/`plays_on_none` stay (that branch).
- The storytel status row (`data-row="status"`): its text, and when there is one the retry rule behind an inline (i).
- KEEP untouched: `music.setup.step1`-`step6` (step 5's "egen risiko"), every remove/delete question,
  `storytel_no_key`/`no_key`.

State logic:
- `handlers/music.rs::storytel_status` returns `None` when there is no error (today: `storytel_accepted`); the
  `login_refused` arm returns the title `storytel_refused_at` (or `storytel_refused` without a time) and a detail
  `storytel_retry_until`/`storytel_retry_next` for the (i): its type becomes `Option<(String, Option<String>, bool)>`.
- `handlers/music_spotify.rs` (the account view built in `spotify_setup`'s loader, ~l.360): `AccountView.line` goes.

Tests:
- `tests/music_spotify.rs::the_six_month_line` ("- log in again before") - the line is gone; assert the 14-day
  `account_ends` warning instead (the test also reads `refused_sub` "The app was deleted, or Spotif…": still in the
  page, inside the (i)).
- `tests/music_storytel.rs::save_probes_then_seals` ("Storytel accepted the login (") - assert no status row; QA M9: it
  also pins `storytel_refused_until`'s old sentence (l.329) -> the visible `storytel_refused_at` and the panel's
  `storytel_retry_until`.
- `tests/music_spotify.rs::the_environment_wins` ("Set by the server's configurat…") → "From .env".
- `tests/music_spotify.rs::not_configured_shows_the_steps`, `::remove_clear_and_a_new_client_id` → "No Spotify app yet".

### Legg til fra Spotify (`music_spotify.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.spotify.context` | Ingen innlogget konto eier denne spillelisten … (55 words) | (moved, unchanged) behind the added line's (i) | | 5/2 | moved |
| `music.spotify.no_reader` | Du må sette opp Spotify først (over). | - | - | P5 (the attention line says it) | deleted |
| `music.spotify.not_ready.no_app` | Ingen Spotify-app er lagret - sett den opp først | Ingen Spotify-app ennå | No Spotify app yet | 4 | words |
| `music.spotify.not_ready.no_key_sub` | Spotify-innlogginger kan ikke holdes trygge uten den. | - | - | P5 (the row links to the fix) | deleted |
| `music.spotify.not_ready.no_app_sub` | Familiens egen Spotify-app, én gang. | - | - | P5 | deleted |
| `music.spotify.not_ready.renew_sub` | Spotify avsluttet innloggingen. | - | - | P5 | deleted |
| `music.spotify.not_ready.no_account_sub` | Logg inn hvert barns egen konto. | - | - | P5 | deleted |
| `music.spotify.phones_footer` | Bare telefoner som spiller som en Spotify-konto, får det (…). | - | - | P5 (each row says «Spotify er av») | deleted |
| `music.spotify.setup_footer` | Familiens egen Spotify-app og hvert barns konto, under Musikk › Innstillinger. | - | - | P5 | deleted |
| `music.spotify.add_link` | Legg til en Spotify-lenke | - | - | P5 (the band says it; the field says «Spotify-lenke») | deleted |
| `music.spotify.added.none` | Slå det på for en telefon på telefonens Musikk-side. | - | - | 3 | deleted |

Template:
- `#added`: the context note row becomes `music.entry.spotify_order` («Rekkefølge: som i Spotify», added above) with an
  inline (i) holding `music.spotify.context`. The flash ends after the name when no phone was ticked (the
  `added.none` branch goes; `added.on` stays).
- The not-ready attention: title only (`missing.sub` field and its keys go; the struct building `not_ready` drops
  `sub`).
- The phones' checkboxes: the `row-sub` shows only `music.spotify_off_phone` (when `plays_as` is None); «Spiller som …»
  goes from this page (`music.plays_as` stays: `music.rs` uses it elsewhere).
- The `add_link` header goes; the form keeps `id="add"` (a refused add lands on `#add`).

Tests:
- `tests/music_spotify.rs::the_add_form_refuses_with_nothing_stored` ("No Spotify app is saved - set …") → "No Spotify
  app yet".
- `tests/music_spotify.rs::context_playlists_short_links_and_kinds` ("Turn it on for a phone on that…") - drop that
  assert; it also reads "No logged-in account owns this" (still in the page, in the (i)).

### Storytel-hylla (`music_storytel.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `music.storytel.intro` | Legg til en serie eller en bok, og huk den av på en telefons side. | - | - | 2/3 | deleted |
| `music.storytel.add_footer` | Hver legges til med alle bøkene, eldste først, og fortsetter der den stoppet - endre det på siden dens. | (moved, unchanged) into the top (i) (`music.storytel.info_label`), after `music.storytel.info` | | 2 | moved |
| `music.storytel.added.one` | Lagt til {count} - slå det på for en telefon på telefonens Musikk-side. | Lagt til {count} | Added {count} | 3 | words |
| `music.storytel.added.other` | Lagt til {count} - slå dem på for en telefon på telefonens Musikk-side. | Lagt til {count} | Added {count} | 3 | words |
| `music.storytel.no_login_sub` | Lagre familiens innlogging først. | - | - | P5 (the row links to the login) | deleted |
| `music.storytel.empty` | Ingenting på Storytel-hylla ennå. Bøker du legger på hylla i Storytel-appen, vises her. | Hylla er tom | The shelf is empty | 3 | words |
| `music.storytel.empty_info` | - | Bøker du legger på hylla i Storytel-appen, vises her. | Books added to the shelf in Storytel's app show here. | 2 | added (a row (i) on the empty row) |

Template: the top `group-footer` keeps only its `<details class="info">` (the intro sentence goes, `add_footer` joins
its panel); the bottom `add_footer` footer goes. The empty row becomes `details.info.cell` with `empty_info`.

Tests: `tests/music_storytel.rs::add_takes_only_shelf_targets` ("- turn them on for a phone on") → "Added 2";
`::picker_lists_groups` (l.504, QA M9) pins "Nothing on the Storytel shelf yet." → "The shelf is empty".

### Varselinnstillinger (`notifications_settings.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.notifications.events_footer` | Bare for deg - hver forelder velger selv. Av: fortsatt i innboksen, men uten varsel. … | (moved, unchanged) into a P2 footer (i) under «Varsle meg om», aria `common.more_info` | | 2 | moved |
| `settings.notifications.shown_footer` | Vist: enheten bekreftet at den viste varselet. | (moved, unchanged) into a P2 footer (i) under «Får varsler» | | 2 | moved |
| `settings.notifications.added_again` | Lagt appen på Hjem-skjermen på nytt? Slå på varsler i den nye kopien, og fjern den gamle enheten her. | (unchanged) | | 5 | state: `notifications.rs::settings_page` |
| `settings.notifications.home_screen` | Trykk Del i Safari og så Legg til på Hjem-skjerm. … | (moved, unchanged) into a row (i) | | 3 | moved |
| `settings.notifications.blocked` | Tillat varsler for Vibb Phone i innstillingene på denne enheten, og kom så tilbake hit. | (moved, unchanged) into a row (i) | | 3 | moved |
| `settings.notifications.clock_warning_sub` | Uten riktig tid kommer ingen varsler fram. Pi-en har ingen egen klokke: den trenger internett (NTP) etter et strømbrudd. | Uten riktig tid kommer ingen varsler fram. Serveren trenger riktig tid (NTP). | Without the right time no notification gets through. The server needs the right time (NTP). | 5 + stale (the server is a VM) | words; behind the (i) (P1 markup) |
| `settings.notifications.this_device_footer` | Hver enhet slår på varsler for seg selv. | - | - | P5 | deleted |
| `settings.notifications.needs_js_footer` | Varsler trenger JavaScript. | - | - | P5 (the row value says «Trenger JavaScript») | deleted |
| `settings.notifications.no_devices` | Ingen enhet får varsler ennå. Slå dem på under Denne enheten, på hver enhet du vil ha dem på. | Ingen ennå | None yet | 3 | words |
| `settings.notifications.no_key` | Varsler er av på denne serveren: nøkkelen kunne ikke leses. Serverloggen sier hvilken fil. | Varsler er av på serveren | Notifications are off on the server | 5 | words (orange line) |
| `settings.notifications.no_key_why` | - | Nøkkelen kunne ikke leses. Serverloggen sier hvilken fil. | Its key couldn't be read. The server log says which file. | 5 | added (behind the (i)) |

Template:
- `#push-home-screen` and `#push-blocked` (JS-toggled `hidden`, keep both ids): each becomes
  `<details class="info cell" id="…" hidden>` whose summary row label is the fixed `common.how_to_fix` («Slik løser du
  det») - not the state word, which `#push-state`'s value already shows (QA L3) - and the panel the moved sentence;
  `static/notifications.js` keeps toggling `hidden` on the same ids.
- The clock warning: `attention-sub` holds only the (i) with `clock_warning_sub`.
- `#this-device`'s footer goes (with its `<noscript>`). The no-key row: `attention attention-action` with `no_key`,
  `no_key_why` behind its (i).
- `#devices`' footer: `<div class="group-footer">` with the (i) (`shown_footer`); `added_again` stays a visible
  `<span data-note="added-again">` but only when `devices_unhealthy` (below).

State logic:
- `handlers/notifications.rs::settings_page`: a new `devices_unhealthy: bool` = any subscription whose `health` is
  `MayNotShow` or `Gone`; the template shows `added_again` only then (today: whenever there is a device).
- `handlers/notifications.rs::device_row` is unchanged: the user kept the opened count (ASK 2).

Tests:
- `tests/parents.rs::notify_me_about_is_each_parents` ("Just for you - each parent cho…") - still in the page (in the
  (i)); passes if it reads the HTML text.
- `tests/web_push.rs::the_device_list_says_what_to_do_after_adding_the_app_again` ("Added the app to the Home Scre…") -
  seed a `MayNotShow` or `Gone` device; add the negative case for a healthy list.

### Tidsregler, Innstillinger (`schedules.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.time_rules.rules_footer` | Hver regel er på for alle telefoner til den slås av på telefonens Tidsregler-side. | (moved, unchanged) into a P2 footer (i) under the rules, aria `common.more_info` | | 2 | moved |
| `device.time.emergency` | Nødanrop virker alltid. | (moved, unchanged) second `<p>` of the rules (i) (KEEP: the fact once per page); the footer under the phone list goes | | 4 | moved |

Tests: `tests/time_pages.rs::time_rules_lists_rules_the_limit_and_the_phones` ("Each rule is on…", "Emergency calls
always work.") and `tests/settings_pages.rs::the_settings_pages_speak_norwegian` ("Nødanrop virker alltid.") - both
still in the page, inside the (i); check they don't pin the `group-footer` markup.

### Regel (`schedule_rule.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.time_rules.when_footer` | Etter telefonens klokke, fra start til slutt samme dag; … (45 words) | (moved, unchanged) into a P2 footer (i) under «Når» | | 2 | moved |
| `time_rules.silent_sub` | Ingen lyder, varsler, musikk eller alarmer mens regelen er på. Anrop som regelen tillater, ringer fortsatt. | (moved, unchanged) into a `details.info.cell` row after «Stille» (KEEP: alarms don't ring; M8) | | P3 | moved |
| `device.bedtime.silent_sub` | Ingen lyder eller varsler ved leggetid. Alarmer ringer fortsatt, … | (moved, unchanged) the same row (i) for a bedtime rule (Leggetid moves it the same way) | | P3 | moved |
| `settings.time_rules.rule_footer` | Telefonen er låst mens regelen gjelder. Nødanrop virker alltid. | (moved, unchanged) into a P2 footer (i) of that group | | 4 | moved |
| `settings.time_rules.corrupt_sub` | Telefonene får en feil i stedet for reglene sine. {parts}, så de vises tomme her og lagres slik du ser dem: … | {parts}. | {parts}. | 5 | words |
| `settings.time_rules.corrupt_fix` | - | Telefonene får en feil i stedet for reglene sine. Det som mangler, vises tomt her og lagres slik du ser det: fyll det inn igjen og trykk Lagre - eller slett regelen. | The phones get an error instead of their rules. What is missing shows empty here and is saved as you see it: set it again, then Save - or delete the rule. | 5 | added (behind the alarm's (i)) |

Template: the Stille switch's `row-sub` goes; its (i) is a `details.info.cell` row right after the switch's
`<label class="row" data-row="silent">` (QA M8: never inside the label): its summary is the ⓘ icon alone (aria
`common.more_info`), its panel the kind's `silent_sub`. The label row and its
`data-row` stay as they are. The corrupt alarm
(`data-warning="rule-corrupt"`, red, kept): `attention-sub` = `corrupt_sub` + the (i) with `corrupt_fix`.

Tests: `tests/time_pages.rs::the_silent_switch_saves_serves_and_shows` ("No sounds, notifications, musi…") - still in
the page inside the (i).

### Daglig grense (`schedule_budget.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.time_rules.budget_footer` | Minutter per dag. Tomt betyr ingen grense. En telefon med egne regler har sin egen grense, … | En telefon med egne regler har sin egen grense, på telefonens Tidsregler-side. | A phone with its own rules has its own limit, on its Time page. | 2/4 | words; moved into the (i) after `budget_info` |
| `settings.time_rules.budget_saved` | Lagret. Telefoner som følger reglene for alle, får det i løpet av sekunder. | Lagret. | Saved. | 4 | words |
| `settings.time_rules.everyone_screen_time` | Alle · Skjermtid | Alle telefoner | Every phone | 4 | words |

Template: the footer keeps only its `<details class="info">`; `budget_footer` is the panel's second `<p>`.
Tests: `tests/settings_pages.rs::the_settings_pages_speak_norwegian` ("Alle · Skjermtid") → "Alle telefoner".

### Innstillinger › Nettfilter (`dns_filter.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.web_filter.lists_footer` | En telefons egen nettfilterside kan stille en liste annerledes … Firefox og Cromite … | (moved, unchanged) into a P2 footer (i) under the lists (KEEP the bypass caveat) | | 2 | moved |
| `settings.web_filter.scope.on_all` | På for alle telefoner | - | - | P4 | deleted; state: `dns_filter.rs::list_scope` |
| `settings.web_filter.scope.off_all` | Av for alle telefoner | - | - | P4 | deleted; state: `list_scope` |
| `settings.web_filter.what_happened` | Hva skjedde | - | - | 2 | deleted (the (i) moves onto the failing sub-line) |
| `settings.web_filter.line.custom_in_use` | Serveren din er i bruk | - | - | P4 | deleted; state: `dns_filter.rs::phone_line` |
| `settings.web_filter.browser.can_bypass` | {browser}: nettleserens egen sikre DNS kan gå utenom filteret | {browser} kan gå utenom filteret | {browser} can get around the filter | 5 | words (orange) |
| `settings.web_filter.browser.may_bypass` | {browser}: nettleserens egen sikre DNS kan kanskje gå utenom filteret | {browser} kan kanskje gå utenom filteret | {browser} may get around the filter | 5 | words (orange) |
| `settings.web_filter.browser.cromite` | {browser}: slå av Sikker DNS i nettleseren på telefonen, ellers laster den ikke sider | {browser}: Sikker DNS må slås av i nettleseren | {browser}: its Secure DNS must be off | 3/5 | words (orange) |
| `settings.web_filter.stale` | De lagrede adressene godtas ikke lenger: skriv dem inn igjen. Til da bruker telefonene Cloudflare. | Egen server må skrives inn igjen | Custom server must be typed again | 5 | words (orange) |
| `settings.web_filter.stale_why` | - | De lagrede adressene godtas ikke lenger. Til da bruker telefonene Cloudflare. | The saved addresses aren't accepted any more. Until then the phones use Cloudflare. | 5 | added (behind the (i)) |
| `settings.web_filter.server_footer` | Hvor telefonene slår opp nettsteder som ikke er blokkert, kryptert. | (moved, unchanged) first `<p>` of the `server_info` (i) | | P2 | moved |
| `settings.web_filter.backup_hint` | Svarer mens serveren din ikke kan nås. https://1.1.1.3/dns-query er Cloudflare for Families, … | (moved, unchanged) into a field (i) on «Reserveserver» | | 2 | moved |
| `settings.web_filter.doh_servers_value` | - | Blokkert | Blocked | 1 | added (the row's value) |
| `settings.web_filter.doh_servers_sub` | Bygd inn i Vibb One, på sammen med filteret | (moved, unchanged) into the row's (i) | | 1 | moved |
| `settings.web_filter.blocked_footer` | Blokkert på alle telefoner, også når ingen liste har det. | - | - | P5 | deleted |
| `device.filter.allowed_footer` | Slippes gjennom selv om en liste blokkerer det - men ikke de sikre DNS-serverne … | (moved, unchanged) into a P2 footer (i) (the device page does the same) | | 2 | moved |
| `settings.web_filter.each_phone_footer` | En liste som er av for én telefon, telefonens egne domener og loggen. | - | - | P5 (the rows are links) | deleted |
| `settings.web_filter.list_hint` | En liste over domener, ett per linje, som i en hosts-fil. | (moved, unchanged) into a field (i) on the list URL field | | 2 | moved |

Template:
- Lists: the `data-scope` sub only when `row.scope` is Some. A failing list: the `what_happened` row goes; the orange
  `row.sub` gets an inline `<details class="info">` (aria `settings.web_filter.problem_label`, kept) with the detail.
- `data-list="builtin-doh"`: `details.info.cell`, label `doh_servers`, value `doh_servers_value`, panel `doh_servers_sub`.
- `#phones` footer: no sentence; the bypass `<ul class="phone-lines">` stays, each `<li>` orange (`tone-orange`).
- `#server` footer: `stale` (orange, `stale_why` in the (i)) or nothing; the `server.phones` list stays, orange lines
  for every line but `waiting` (grey); `server_footer` joins `server_info`'s panel.
- Custom server: the server field's `data-hint="server"` (set/not set) goes - the row sub above says it; the backup
  field's stays (`settings.hint_set`/`hint_not_set` stay: provisioning uses them too). `backup_hint` → field (i);
  `backup_ip` stays a conditional hint.

State logic:
- `handlers/dns_filter.rs::list_scope` returns `Option<String>`: `None` for (on, all) and (off, all); the exceptions
  (`on_except`, `off_but`) stay.
- `handlers/dns_filter.rs::phone_line` returns `None` for `custom` in use with no outage (today `custom_in_use`); its
  result becomes `(String, bool)` - warn for every line but `line.waiting` (the backup, the outages, `update_for_custom`,
  `filter_off`) - and `phone_lines` passes the flag to the template. QA L4: an ended outage alone (custom in use, the
  outage over: `"{name} · DNS {ended}"`) is grey - it is over.

Tests:
- `tests/device_pages.rs::web_filter_holds_the_phones_parts` ("On for every phone", "På for alle telefoner", "Off for
  every phone") → assert no scope line on an all-phones list, the exception text where one is set.
- `tests/custom_dns.rs::the_footer_says_per_phone_which_server_is_in_use`, `::the_lines_say_when_dns_cant_be_reached`
  ("Your server in use") → no line for that phone.
- `tests/custom_dns.rs::the_secure_dns_bypass_on_the_page` ("Built into Vibb One, on with t…" - still in the (i);
  ": its own secure DNS can get a…" → "Firefox can get around the filter").
- `tests/custom_dns.rs::an_allowed_secure_dns_host_says_it_stays_blocked` ("Let through even when a list b…") - still
  in the page (in the (i)).

### Innstillinger › Blokkert aktivitet (`dns_log.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.dns_log.footer.one` | Loggen er av til du slår den på for en telefon. Lagres i {count} dag. | Lagres i {count} dag. | Kept {count} day. | 4 | words; into a P2 footer (i) |
| `settings.dns_log.footer.other` | … Lagres i {count} dager. | Lagres i {count} dager. | Kept {count} days. | 4 | words; into a P2 footer (i) |

ASK 4 (drop the Settings hub's «Blokkert aktivitet» row): the user KEEPS the row - unchanged.

### Bakgrunnsbilder (`wallpapers.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.wallpapers.add_footer` | Et nytt bilde er ikke på noen telefon før du huker det av på telefonens Hjem-skjerm. | (moved, unchanged) into the existing (i) after `settings.wallpapers.info` | | P2 | moved |
| `settings.wallpapers.lock_screen_sub` | Alle som holder den låste telefonen, ser det. | (moved, unchanged) into the same (i) (KEEP the privacy point) | | P3 | moved |
| `settings.wallpapers.all_footer` | Velg hvilke en telefon kan bruke på Hjem-skjermen sin. | - | - | P5 | deleted |
| `settings.wallpapers.name_hint` | Leses opp for barnet ditt. | (moved, unchanged) into a field (i) on the name field | | 2 | moved |
| `settings.wallpapers.all` | Alle bakgrunnsbilder | Bilder | Photos | 4 | words |

Tests: `tests/settings_pages.rs::the_settings_pages_speak_norwegian` ("Alle bakgrunnsbilder") → "Bilder". KEEP
`settings.wallpapers.delete_confirm`.

### Telefonoppsett (`provisioning_settings.html`, `partials/launcher_check.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.provisioning.launcher.refused_short` | - | En telefon ville avvist den. Ingen QR-kode før det er rettet. | A phone would refuse it. No QR code until it's fixed. | 5 (KEEP visible) | added |
| `settings.provisioning.launcher.older_short` | - | Nye telefoner får den eldre. | New phones get the older one. | 5 | added |
| `settings.provisioning.launcher.older_sub` | En telefon som settes opp med den, starter på den eldre launcheren. For å sette opp med {newest}: … | For å sette opp med {newest}: la LAUNCHER_APK_URL i serverens .env peke på kids-launcher-mdm.apk i den utgivelsen, og start serveren på nytt (DEPLOY.md). | To set up with {newest}, point LAUNCHER_APK_URL in the server's .env at that release's kids-launcher-mdm.apk and restart the server (DEPLOY.md). | 5 | words; behind the (i) |
| `settings.provisioning.launcher.wrong_signer_sub` | En telefon ville avvist den og bare sagt «Can't set up device». Sjekk … Ingen QR-kode før de stemmer. | En telefon sier bare «Can't set up device». Sjekk LAUNCHER_SIGNATURE_CHECKSUM og LAUNCHER_APK_URL i serverens .env (DEPLOY.md). | A phone says only “Can't set up device”. Check LAUNCHER_SIGNATURE_CHECKSUM and LAUNCHER_APK_URL in the server's .env (DEPLOY.md). | 5 (KEEP env names) | words; behind the (i) |
| `settings.provisioning.launcher.wrong_package_sub` | En telefon ville avvist den. Sjekk LAUNCHER_APK_URL og LAUNCHER_ADMIN_COMPONENT … Ingen QR-kode før det er launcheren. | Sjekk LAUNCHER_APK_URL og LAUNCHER_ADMIN_COMPONENT i serverens .env (DEPLOY.md). | Check LAUNCHER_APK_URL and LAUNCHER_ADMIN_COMPONENT in the server's .env (DEPLOY.md). | 5 | words; behind the (i) |
| `settings.provisioning.launcher.address_sub` | Fra denne serveren: {url}. En telefon ville stoppet ved «Can't set up device». Sjekk … Ingen QR-kode før da. | Fra denne serveren: {url}. En telefon stopper ved «Can't set up device». Sjekk LAUNCHER_APK_URL i serverens .env (DEPLOY.md) og serverens internettforbindelse, og trykk så Sjekk på nytt. | From this server: {url}. A phone stops at “Can't set up device”. Check LAUNCHER_APK_URL in the server's .env (DEPLOY.md) and this server's internet connection, then Check Again. | 5 | words; behind the (i) |
| `settings.provisioning.launcher.needs_android` | En ny telefon må ha Android {version} eller nyere: oppdater den før tilbakestillingen. | (moved, unchanged) into the launcher row's (i) | | 5 | moved |
| `settings.provisioning.launcher.needs_api` | (same, API level) | (moved, unchanged) same | | 5 | moved |
| `settings.provisioning.server_url_hint` | Https-adressen til denne serveren, slik telefonene når den (…). | (moved, unchanged) into a field (i) on the address field | | 2 | moved |
| `settings.provisioning.source_env` | - | Fra .env | From .env | 3 | added (visible hint while the address comes from .env) |
| `settings.provisioning.source_saved` | - | Lagret her, går foran .env | Saved here, wins over .env | 3 | added (visible hint while a saved address overrides .env) |
| `settings.provisioning.from_env` | Fra .env (PHONE_SERVER_URL): {url}. En adresse som lagres her, går foran. | (moved, unchanged) into the field (i) | | 3 | moved |
| `settings.provisioning.saved_wins` | Lagret her; den går foran {url} fra .env. Tøm feltet for å bruke den. | (moved, unchanged) into the field (i) | | 3 | moved |
| `settings.provisioning.key_hint` | Lag en ny engangsnøkkel før hvert telefonoppsett. | (moved, unchanged) into the `key_info` field (i), first `<p>` | | 2 | moved |
| `settings.provisioning.saved` | Lagret. Telefoner som settes opp fra nå av, får disse. | Lagret. | Saved. | 4 | words |
| `settings.provisioning.country_code_saved` | Lagret. Hver telefon får den neste gang den er på nett. | Lagret. | Saved. | 4 | words |
| `settings.provisioning.footer` | QR-koden for oppsett tar disse med til hver nye telefon. | (moved, unchanged) into a P2 footer (i), aria `common.more_info` | | 2 | moved |
| `settings.provisioning.numbers_footer` | For alle telefoner. Numre som allerede er lagret, beholder sin. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |
| `settings.provisioning.country_code_hint` | Et nummer som legges til uten landskode, får +{code}. | (moved, unchanged) into a field (i) on the country code | | 2 | moved |

Template (`partials/launcher_check.html`, also on the QR page):
- The attention (refused red / older orange): `launcher.text`, then `attention-sub` = `launcher.sub` (now the short
  visible line) + an inline `<details class="info">` (aria `common.how_to_fix`) with `launcher.fix`.
- The works row: `details.info.cell` with `launcher.needs` in the panel when it has one (the `launcher-needs` note row
  goes); without `needs` a plain row as today.
- Provisioning page: the address field shows `source_env`/`source_saved` as its visible hint when .env sets one, the
  field (i) holds `server_url_hint` + `from_env`/`saved_wins`; `key_hint` leaves the hint line (the `key_dropped` error
  stays).

State logic:
- `launcher_check.rs::words` (and `Line`): `Line` gains `fix: Option<String>`; `sub` becomes the short visible line
  (`refused_short` for the refused states - wrong signer, wrong package, not an APK, unreachable, timed out -,
  `older_short` for older), `fix` the long `*_sub` (none for `not_an_apk`: `refused_short` alone).
- `provision_qr.html` includes the partial only when `launcher.state != "works"` (refused, older, checking); in the
  works state the QR footer's (i) gets `launcher.needs` as an extra `<p>`. The QR still waits on `blocks_qr()` as today.

Tests:
- `src/launcher_check.rs::tests::the_android_a_new_phone_needs` (needs_android/needs_api text) - unchanged words.
- grep `tests/provisioning.rs` for `data-launcher="works"` / `launcher-needs` on the QR page and switch them to the
  Settings page (the QR page no longer shows the works row).

### Sikkerhetskopier (`backups.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.backups.footer` | Ha en kopi et annet sted også: last ned en nå og da. | (moved, unchanged) first `<p>` of the existing (i) (KEEP `info_holds` there) | | P2 | moved |
| `settings.schedule.day_of_week` | Dag (hver uke) | Ukedag | Weekday | 1 | words |
| `settings.schedule.day_of_month` | Dag i måneden (hver måned, 1-28) | Dag i måneden | Day of the month | 1 | words (the field's `max="28"` keeps the range) |
| `settings.backups.schedule_footer_docker` | Sikkerhetskopiene blir liggende i datavolumet: kopier dem selv av verten. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |
| `settings.backups.schedule_footer` | Etter klokken på serveren. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |
| `settings.backups.upload_footer` | En du har lastet ned tidligere. | - | - | P5 | deleted |
| `settings.backups.keep_hint` | Eldre slettes. 0 beholder alle. | (moved, unchanged) into a field (i) on «Behold» | | 2 | moved |

Both day fields stay shown (hiding the one the frequency doesn't use needs JS and a `<noscript>` path; not worth it
here). KEEP: `restore_confirm`, `backups.restore.accounts`, the typed-name check, `restore_ssh`, `delete_confirm`.
Tests: none pin these (grepped).

### Oppdateringer (`updates.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.updates.how` | Slik oppdaterer du | - | - | 2 | deleted (the how-to opens from the Version row; `how_label` «Kommandoene» stays its aria-label) |
| `settings.updates.up_to_date` | Du har nyeste versjon | - | - | P4 | deleted; state: template |
| `settings.updates.footer` | Vibb Phone endrer aldri noe på maskinen den kjører på: … | (moved, unchanged) last `<p>` of the Version row's (i) | | P2 | moved |
| `settings.updates.timing_footer` | Bare på denne enheten: en linje øverst på hver side med hvor lang tid den brukte. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |

Template / state: the `data-row="version"` row becomes `details.info.cell` (whole row opens) holding today's
`data-row="how"` panel and `settings.updates.footer`; the `how` row goes. `data-row="latest"` shows only for checking,
check failed or a newer version (the up-to-date row goes).
Tests: `tests/docker.rs::the_updates_page_says_how_to_update` ("You have the latest version") → assert no latest row;
the docker commands still in the page (the (i)); check it looks for `data-row="how"` and move that to `"version"`.

### Varsler, innboks (`notifications.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `settings.notifications.delivery.opened` | Åpnet på {label} | - | - | P4 (ASK 3, taken) | deleted; state: `notifications.rs::delivery_line` |
| `settings.notifications.delivery.shown` | Vist på {label} | - | - | P4 (ASK 3) | deleted |
| `settings.notifications.delivery.delivered` | Levert til {label} | - | - | P4 (ASK 3) | deleted |
| `settings.notifications.delivery.summary` | I en oppsummering | - | - | P4 (ASK 3) | deleted |
| `settings.notifications.delivery.many_opened` | Sendt til {devices} enheter · åpnet på {opened} | - | - | P4 (ASK 3) | deleted |
| `settings.notifications.delivery.many_shown` | Sendt til {devices} enheter · vist på {shown} | - | - | P4 (ASK 3) | deleted |
| `settings.notifications.delivery.many_pushed` | - | Sendt til {devices} enheter, ikke bekreftet | Pushed to {devices} devices, not confirmed | 5 (QA M3: the several-devices form of `pushed`) | added |
| `settings.notifications.footer` | Nyeste først, tas vare på i 90 dager. | - | - | P5 | deleted |
| `settings.notifications.empty` | Ingenting ennå - det telefonene melder, kommer her | Ingenting ennå | Nothing yet | 4 | words |
| `devices.warning.push_gone_sub` | Slå dem på igjen på den enheten, eller fjern den under Varsler | - | - | 5 (the row links to the fix) | deleted; state: `notifications.rs::warning_of` |

State logic:
- `handlers/notifications.rs::warning_of` returns `(String, Option<String>)`: `None` sub for `Gone`; the
  `MayNotShow` (`push_unconfirmed`) and `NotGettingThrough` (the failure) subs stay. The template drops an empty
  `attention-sub`.
- `handlers/notifications.rs::delivery_line` (ASK 3, taken): only the wrong states return a line - `not_sent`,
  `pushed` (one device, sent, not confirmed), `sending`, and (QA M3) `many_pushed` when several devices got it and none
  showed or opened it (the case `many_shown` with `shown == 0` covers today); opened/shown/delivered/summary and several
  devices with one shown or opened return `None`.

Tests: `tests/notifications.rs::an_empty_inbox_and_older_pages` ("Nothing yet - what the phones…") → "Nothing yet".
ASK 3 (taken; add the M3 row: several devices, none shown -> "Pushed to 2 devices, not confirmed"):
`handlers/notifications.rs::tests::the_delivery_line`, `tests/web_push.rs::an_apple_acceptance_is_the_delivery`,
`::inbox_delivery_line_is_each_parents`, `::the_page_carries_no_secret_and_every_form_lands`,
`::switches_the_emergency_call_and_a_burst`, `tests/power.rs::a_newer_shutdown_supersedes_an_older_held_push` all pin
"Delivered to"/"In a summary".

### Konto og foreldre (`account.html`, partials `passkey_confirm`, `passkeys_still_work`, `reauth_passkey`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `account.totp.footer` | Låst ute og logget ut? Det kan tilbakestilles på serveren. | (moved, unchanged) first `<p>` of its (i) (`account.totp.server_info`) | | P2 | moved |
| `account.password.off_footer` | Kun passkey er på: du logger inn med passkey. Låst ute? Det kan tilbakestilles på serveren. | - | - | P5 (the row says «Av»; the (i) says how to reset) | deleted |
| `passkeys.footer` | Logg inn med Face ID, fingeravtrykk eller en sikkerhetsnøkkel i stedet for passord og kode. | (moved, unchanged) into a P2 footer (i) under Passkeys | | 2 | moved |
| `passkeys.footer_host` | Passkeys du legger til her virker bare på {host}. … | (moved, unchanged) the same (i) (KEEP) | | 2 | moved |
| `passkeys.only.footer_last` | Den siste passkeyen kan ikke fjernes mens «Kun passkey» er på. Slå på passord først. | - | - | P5 (the remove confirm says it) | deleted |
| `lock.footer` | Appen låses når den har vært ubrukt så lenge. | - | - | P5 | deleted |
| `account.sessions.footer` | For en mistet telefon eller datamaskin som fortsatt er logget inn. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |
| `account.parents.footer` | Hver forelder logger inn med sitt eget passord … Opptil 8. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |
| `account.language.footer` | Språket for kontoen din. Hver forelder velger sitt eget. | - | - | P5 | deleted |
| `account.name.hint` | Det de andre foreldrene ser, for eksempel «Mamma». Tomt: {username}. Navnet vises også på telefonen … | For eksempel «Mamma». Tomt: {username}. | For example "Mamma". Empty: {username}. | 2 | words |
| `account.name.info` | - | Det de andre foreldrene ser. Navnet vises også på telefonen når du svarer på en forespørsel om mer tid eller gir mer tid; uten navn står det ikke noe navn der. | What the other parents see. It also shows on the phone when you answer a request for more time or give time; without a name, the phone names nobody. | 2 | added (field (i)) |
| `passkeys.only.sub` | Ingen passord eller kode – bare passkey | - | - | P3 (the confirm says it) | deleted |
| `lock.sub` | Gjelder denne enheten. Lås opp med Face ID, fingeravtrykk eller telefonens kode. | (moved, unchanged) into a `details.info.cell` row after «Lås med Face ID» (M8) | | P3 | moved |
| `account.add.username_hint` | For innlogging, for eksempel «pappa»: små bokstaver, tall, . _ eller -. | Små bokstaver, tall, . _ - | Lowercase letters, digits, . _ - | 2 | words |
| `account.add.name_hint` | Det dere alle ser, for eksempel «Pappa». Den nye forelderen kan endre det selv. | For eksempel «Pappa» | For example "Pappa" | 2 | words |
| `account.add.password_hint` | Det du logger inn her med. Du får et engangspassord å gi dem; tre feil passord logger deg ut. | Ditt eget passord. Tre feil logger deg ut. | Your own password. Three wrong ones sign you out. | 2 (KEEP «tre feil … logger deg ut») | words |
| `account.totp.row` | Tofaktorinnlogging | Status | Status | 4 | words |
| `passkeys.only.any_time` | Du kan slå på passord igjen når du vil. | - | - | 4 | deleted |
| `account.password.footer` | Et nytt passord logger ut alle andre nettlesere og telefoner og slår av varsler på alle andre enheter. | Logger ut alle andre enheter og slår av varslene der. | Signs out every other device and turns their notifications off. | 1 (KEEP the consequence) | words |

Template: the footers above lose their sentence (the (i) stays alone, the sentence first in the panel); new
`<div class="group-footer"><details class="info">` for passkeys, sessions and parents (aria `common.more_info`); the
`off_footer`/`footer_last`/`lock.footer`/`language.footer` lines go. The name field gets a `details.info.field-info`
with `account.name.info`. `passkeys.only.sub` leaves both "Kun passkey" rows. KEEP untouched: `account.warning.*`,
`account.otp.hint`, `account.password.new_hint`, every reset/remove/withdraw/sign-out confirm (incl.
`account.totp.reset_confirm`, `passkeys.only.what`, `warn_one*`, `way_back`, `hosts`).

ASK 5 (taken): `partials/passkeys_still_work.html` leaves its place under the open password form and moves into the
password group's footer ⓘ (the passkeys-that-still-work list as a `<p>` of that panel); Reset 2FA's confirm keeps it as
today.

QA M8: «Lås med Face ID» (and any other switch on this page that gets an ⓘ) is a `<label class="row">`: its ⓘ is a
`details.info.cell` row right after the label row (ⓘ icon alone, aria `common.more_info`, panel `lock.sub`), never
inside the label.

Tests:
- `account.totp.row` → "Status": `tests/admin_auth.rs::a_2fa_reset_signs_out_every_other_session`,
  `::the_sign_in_pages_read_in_norwegian` ("Tofaktorinnlogging" - the header still says it; check which it pins),
  `tests/passkey_only.rs::the_shell_says_passkey_only_is_off`, `tests/settings_pages.rs::account_answers_in_its_groups`
  ("Two-factor login": the header is "Two-Factor Login", so these pin the row - update to the `data-row="two-factor"`
  row's "Status").
- `tests/passkey_only.rs::the_switch_needs_a_passkey_and_takes_the_password_and_code_away` ("The last passkey can't be
  remo…") - check the remove confirm or the refusal carries it; else assert the refusal.
- `src/i18n.rs::tests::a_count_picks_one_or_other` - `account.name.hint` keeps `{username}`: unchanged.

### Sikkerhetslogg (`security.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `security_log.activity.footer` | 200 per side, nyeste først, lagret i 180 dager; … | (moved, unchanged) into a P2 footer (i) | | 2 | moved |
| `security_log.banned.none` | Ingen adresser er sperret. | - | - | P4 | deleted; state: template |
| `security_log.banned.footer` | For mange feil passord sperrer en adresse en stund. | (moved, unchanged) into a P2 footer (i) | | 2 | moved |

Template / state: `#banned` renders only when `!bans.is_empty() || unbanned.is_some()` (the Unban flash lands on
`#banned`, so the section stays for that request). An event's `e.exact` leaves its second `row-sub` and becomes the
`title=` of the row's time value (as `partials/info_row.html` does).
Tests: `tests/settings_pages.rs::the_security_log_reads_as_labelled_rows` ("For mange feil passord sperrer") - seed a
ban, or assert the text inside the (i).

### Innlogging og passordsider (`login.html`, `password_sign_in.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `password_on.lede` | Velg et nytt passord, sett opp tofaktorinnlogging og bekreft med passkey. Passkeyene dine blir. | Passkeyene dine blir. | Your passkeys stay. | 4 | words |
| `password_on.footer` | Da logger du inn med passord og kode, eller med passkey. | - | - | P5 | deleted |
| `auth.login.sub` | Logg inn for å passe på familiens telefoner. | - | - | P5 | deleted |

Tests: `tests/admin_auth.rs::the_sign_in_pages_read_in_norwegian` ("Logg inn for å passe på famili…") - drop the line.

### Legg til telefon (`device_add.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `devices.add.footer` | Deretter kobler du til telefonen med en QR-kode eller en oppsettkode. | - | - | P5 | deleted |

### Koble til {name} (`provision_qr.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `devices.qr.qr` | QR-kode | Ny eller tilbakestilt telefon | New or reset phone | 1 | words |
| `devices.qr.qr_sub` | Ny eller tilbakestilt telefon | - | - | P3 | deleted |
| `devices.qr.code` | Kode | Allerede satt opp | Already set up | 1 | words |
| `devices.qr.code_sub` | Telefonen er allerede satt opp | - | - | P3 | deleted |
| `devices.qr.code_footer` | Du skriver den inn i innstillingene til Vibb One på telefonen. | - | - | P5 (the code page says where) | deleted |
| `devices.qr.keep_open` | La denne siden være åpen til telefonen er tilkoblet. Lastes den inn på nytt, lages en ny kode. | La siden stå åpen til telefonen er tilkoblet. | Keep this page open until the phone has connected. | 2 | words |
| `devices.qr.reload_info` | - | Lastes siden inn på nytt, lages en ny kode. | Reloading the page makes a new code. | 2 | added (a `<p>` in the QR footer's (i)) |
| `devices.qr.paused_sub` | For mange feil koder ble prøvd denne timen. QR-koden virker nå. | QR-koden virker. | The QR code works. | 5 | words |
| `devices.qr.paused_why` | - | For mange feil koder ble prøvd denne timen. | Too many wrong codes were tried this hour. | 5 | added (behind the (i); both pages) |
| `devices.qr.replaces` | En telefon som bruker en ny QR-kode eller oppsettkode, tar over, og den som er tilkoblet nå, kobles fra. | En ny QR-kode eller oppsettkode kobler den fra. | A new QR code or setup code cuts it off. | 5 (KEEP: red, visible) | words |

Template: `choice-sub` spans go; `#code`'s footer keeps only its (i) (`code_info`); the paused attention's sub =
`paused_sub` + inline (i) with `paused_why`; the QR footer's (i) gets `reload_info` (and, per Telefonoppsett,
`launcher.needs` in the works state). The launcher group is included only when `launcher.state != "works"`.

### Oppsettkode (`enroll_code.html`)

| Key | Today nb | New nb | New en | Rule | Kind |
|---|---|---|---|---|---|
| `devices.code.paused_sub` | For mange feil koder ble prøvd denne timen. Denne koden virker etter det; en QR-kode for oppsett virker nå. | En QR-kode for oppsett virker nå. | A setup QR code works now. | 5 (the title has the time) | words; `devices.qr.paused_why` behind its (i) |
| `devices.code.replaces` | Telefonen som bruker denne koden, tar over, og den som er tilkoblet nå, kobles fra. | Brukes denne koden, kobles den fra. | Using this code cuts it off. | 5 (KEEP: red, visible) | words |

Tests for the last three pages: none pin these strings (grepped `src/tests` for keys, en and nb text).


## State-logic changes (all pages)

Words-only changes are in the tables; these change which lines show or their colour. Each page's "State logic" bullets
hold the exact condition; this is the index.

Cross-cutting
- `partials/note_warning.html` + `device_pages::Note.sub` (D1 P1): every `Note` warning shows its title alone; `detail`
  and `fix` behind one ⓘ; `sub` visible only for `calls::Finding::note`'s `EmergencyCall`, `CallbackWindow`,
  `CallScreenFailed` (KEEP) and its needs-update finding, `device.time.ledger_unreadable`, and the lock notes
  `needs_update` and `bad_hash` (QA M5).
- Link cards and `<label>` switch rows hold no ⓘ (QA M7, M8): the summary's no-Parent-PIN card and `#mobile-state` lines
  render their title only.
- `attention.rs`: new `Kind::MobileDataOff` (orange, `/devices/{id}/mobile#mobile-data`), fact `mobile_data_off` from
  the latest `mobile_data.enabled == false`; counts in the Devices list badge (temporary: 114's `Setup(n)` replaces it).

A phone
- `mobile_network::switch_line`: Android's roaming switch shown only when off under På/Bare Vibb (orange; abroad with
  the place); nothing when on or unknown; under Av nothing when locked, the grey pending line when not; the pause's own
  lines go.
- `mobile_network::card`: `data-off` orange; no `only-vibb`/`only-vibb-abroad` lines on the page; `national` gets its ⓘ;
  `place_in_switch` only for the `off_abroad` switch line (QA M1).
- `mobile_network::row_value`: no «Mobildata av» part (QA L6).
- `mobile_network::summary_lines` / `only_vibb_line`: the summary drops `data-off` (Attention says it), `only-vibb` and
  `only-vibb-abroad`; `only-vibb-kid` stays grey (the user's call).
- `quick_actions.rs`: `extra_note` only with a budget («Brukt i dag: …»); the setup-open row's sub only for an old
  launcher (`pause_ends_old`).
- `airplane_mode::setup_steps`: unchanged (QA L8); the template puts a step's `sub` behind its row ⓘ.
- `airplane_mode::home_sub`: «Vibb One er ikke digital assistent» orange, only with bit 64 on and `!report.assistant`
  (QA M6); blocked/offline grey.
- `shortcuts::home_sub`: quiet values («Skjult av {name}», the items, «Ingen»).
- `auth::WarningRow.sub` → `Option`; `notifications::push_warning` gives `None` on the Devices list.
- `devices::app_rows` / `status_label`: no line for installed, preinstalled and Play-installed apps; busy, failed,
  update-waiting and not-installed keep theirs.
- `device_pages::render_apps`: managed dialer row no sub, off «Bare nødnumre»; Meldinger's sub only while SMS isn't
  managed; Kalender no sub; `catalog_default` only when `can_reset()`.
- `calls::channel_row`: no sub under the segments and no «anbefalt» (the mode sentences go into the calls ⓘ).
- `calls::emergency_rows`: a sub only on a number that isn't an emergency number (orange); suggestions none.
- `calls_form::subs`: one quiet word per switch («Ikke styrt», «Samtaler av», «SMS av», «Meldinger av») only when it
  applies (QA M2: `texts` under SMS Off is «SMS av»).
- `schedules::render_device_time`: «Fra telefonens siste melding …» only when the report is older than 35 min
  (`window_relock::REPORT_AGE_NOTE_MS`).
- `kiosk_escapes::boot_cover_notes` / `escapes_card`: nothing for an armed or shown boot cover, or a good install.
- `device_pages::play_notes`: no row for the nightly update window.
- `device_advanced.html`: no Google-backup row; on = the alarm alone, its text behind the ⓘ.
- `server_tls::card`: orange `warn` for `stopped`, `stopped_unnamed`, `on_cannot`, `on_phone_off`; the recovery
  address a value row with its how-to behind the ⓘ.
- `logins::view`: no Chrome state row when ready; absent/failed orange. `device_logins.html`: `logins.apps.clear` only
  while the Chrome switch is off.
- `music::spotify_binding_card`: no login-date line until the 14-day warning.
- `device_locate.html`: no `fix.none_off` line while location is off.
- `locate::zones_view` / `watch_line`: one orange «Telefonen kan ikke følge med på sonene» when zones exist and the
  phone can't watch; nothing when it watches.

Catalog
- `tracked_apps::update_group`: the waiting release's sub is «Publisert {when}» (uncounted keys).

App push
- `app_push::render_settings`: 112's `seen` and `DeliveryRows`; «Ikke ennå» orange when a registration is over an hour
  old and nothing has reached the address; `save_note` only while no address is saved.
- `app_push::group`: missing as title + ⓘ; delivery line without p95; no `signed` line; noisy/outside/refused as short
  orange lines with their detail behind the app row's ⓘ.

Music, Settings, account
- `music.html`: the count footer only at 90 % of the cap or more; library cards hide a fine status.
- `music::SweepView::spotify_card`: one «Rekkefølge: som i Spotify» line with its ⓘ instead of the context notes; the
  audiobook note one orange line; no home-network row.
- `music::storytel_status`: nothing when the login was accepted; a refusal one orange line, the retry rule behind ⓘ.
- `music_spotify.rs` account view: no login-date line when fine; «Spiller på …» only when the select can't show it.
- `music_spotify.html`: «Spotify er av» only, no «Spiller som» per phone.
- `notifications.rs::settings_page`: `added_again` only while a device is `MayNotShow`/`Gone`.
- `notifications.rs::warning_of`: no second line for `Gone`.
- `dns_filter::list_scope` → `Option`: only exceptions; `dns_filter::phone_line`: nothing when the custom server is in
  use; the problems and the browser bypass lines orange; an ended outage alone grey (QA L4).
- `device_filter.html`: the log's «Av sletter loggen.» stays visible beside its ⓘ (QA H1, KEEP).
- `launcher_check::words`: `Line.fix` behind the ⓘ; `provision_qr.html` shows the launcher group only when its state
  isn't "works".
- `updates.html`: no «Du har nyeste versjon» row; the how-to from the Version row.
- `security.html`: «Sperret» only when an address is banned or its flash shows.
- `notifications.rs::delivery_line` (ASK 3): only not sent, pushed and not confirmed (one device or several -
  `many_pushed`, QA M3), and sending; `notifications.rs::device_row` unchanged (ASK 2).
- `account.html` (ASK 5): `passkeys_still_work` in the password group's ⓘ, not under the open form.

## Tests

Each page's "Tests" bullets name the tests that pin its strings or rows. Across the round:
- `i18n::tests::every_key_is_used` fails until every deleted key leaves both catalogs;
  `both_catalogs_have_every_key_with_the_same_parameters` checks the added keys and the dropped parameters
  (`app_push.outside`, `app_push.vapid_refused_time/_address`, `app_push.refused_fix`, `server_tls.recovery`).
- `tests::i18n_pages::nb_pages_have_no_english` reads every page in nb; its English-only word list must still find its
  words after the en catalog shrinks (it asserts each is English-only).
- `src/attention.rs`'s order test and `tests/attention.rs` gain `MobileDataOff`.
- Tests that read `data-*` hooks keep working where the hook moved with its element (D6); the per-page bullets say
  where a hook goes (`data-row="signed"`, `data-mobile-line="only-vibb"`, `data-mobile-line="data-off"` on the summary,
  `delivery-p95`, the boot-cover and Play rows).
- Dropped parameters (QA M9) also leave the call sites: `device.summary.qa.mode_line`/`_none` and
  `warn_setup`/`_plain` lose `{rule}` (`quick_actions.rs:151-155, 465-471`); `device.calls.sub.pictures_no_texts` loses
  `{kid}`/`{contact}` (`calls_form.rs:211`); `device.requests.short_choose` loses `{name}`;
  `device.home.airplane_no_role` loses `{name}`.
- QA L5: `every_key_is_used` counts any quoted occurrence in `src/`, in-file test modules included, so after the build
  grep `src/` for each deleted key; a deleted key still quoted in a `#[cfg(test)]` module is removed there too.
- QA L9: doc comments that quote old words follow the strings: `handlers/mobile_page.rs:9`, `handlers/logins.rs:8, 72`,
  `handlers/device_pages.rs:896`, `handlers/calls_mmsc.rs:32`, `handlers/app_push.rs:4, 299`,
  `handlers/contact_requests.rs:326`.

## ASK (answered - see below)

1. Låseskjerm › Varsler: an orange «Hele teksten viser også innloggingskoder» while an app shows full text
   (`device.lock.full_text_warn`, added only if taken).
2. Varselinnstillinger: drop the device row's «… siden sist et ble åpnet» / «… ikke åpnet ennå» count
   (`notifications.rs::device_row`; server round 5 added it on purpose).
3. Varsler (innboks): show the per-event delivery line only when it went wrong; drop the good receipts
   (`notifications.rs::delivery_line`, 6 keys; seven tests pin "Delivered to"/"In a summary").
4. Settings hub: drop the «Blokkert aktivitet» row (the same phones are under Nettfilter › Hver telefon) - structure,
   not words.
5. Konto: move `partials/passkeys_still_work.html` from under the open password form into the password group's ⓘ
   (design 74 D1.3b put it in view on purpose).

## The user's answers (2026-11-17, 21:55 CET)

- ASK 1 (the full-text codes line): DROPPED ("Dropp den!") - no new line.
- ASK 2 (the device row's opened count): KEEP it ("Behold den").
- ASK 3 (the inbox's delivery line only when it went wrong): TAKEN.
- ASK 4 (the Settings hub's «Blokkert aktivitet» row): KEEP the row.
- ASK 5 (the passkeys note behind the password group's ⓘ): TAKEN.

## Counts

After the design QA and the user's answers, counted from the tables (a key shared by two pages counted once, a
`.one`/`.other` pair as written): about 228 keys with new text, 162 deleted (ASK 3's six and `mobile.value.data_off`,
`device.summary.no_parent_pin_sub` among them; ASK 2's two kept), 58 added (`device.calls.sub.sms_off`,
`settings.notifications.delivery.many_pushed`, `device.filter.log_off_erases` and D1's `needs_update_short` /
`bad_hash_short` among them; ASK 1's key not built) - about 448 changed keys in all. A further ~194 keys keep their text
and only move behind an ⓘ.
