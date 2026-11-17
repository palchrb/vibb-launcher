# PWA words audit - against CLAUDE.md "Words in the UI" (2026-11-17)

Read-only audit of `server/templates/` (every page and partial), `server/src/i18n/nb.json` and the handler code that picks
state lines. Quotes are nb. Rules: 1 = a heading/choice is a word or two, no sub-line under a choice; 2 = explanation
behind an (i) alone, no "Om ..." label or sentence beside it; 3 = a state line says the state, how-to behind the (i);
4 = say it once; 5 = speak up only when something is wrong (fine = nothing or a quiet value; needs the parent = one
short orange line, fix behind the (i)). Each finding is tagged **[words]** (keys/template only) or **[state logic]** (which
states show; the handler fn is named). **KEEP** marks load-bearing text (safety, security, irreversible actions, data
deleted) that must survive, at most moved or shortened. Not built, not run; the stored values and the shared contracts
(`server/testdata/*.json`) are untouched by every proposal here unless a finding says otherwise.

Total: **320 findings on 62 pages/partials** (table at the end). Per page, findings are ranked worst first.

## Cross-cutting (fix once, many pages)

- **C1. `partials/note_warning.html` shows the warning's sentence in the open.** `note.detail` renders as visible orange
  `.attention-sub` text; only the expert `note.fix` sits behind the (i). Rule 5. Proposal: render `detail` inside the
  (i) panel (then `fix`), leaving the orange title alone. One template change fixes device_apps, device_advanced,
  device_home, device_lock, device_music, device_time and device_calls. Check each caller's title still reads on its own
  (some titles lean on the sentence). KEEP visible: the safety notes on Anrop (`emergency_call`, `callback`,
  `screen_failed`), which should keep a short visible consequence. [words, template]
- **C2. "Footer sentence + (i)".** `<div class="group-footer">text <details class="info">` is the commonest breach across
  the PWA (dozens of instances, listed per page). Rule 2: fold the sentence into the panel and leave the (i) alone
  beside the group header or the row. Design 112's own footer (`app_push.settings.footer` + ⓘ2) has the same issue.
- **C3. Choice/row sub-lines.** Radios and switches carry a sentence under the label on most device pages (Mobilnett,
  Anrop, Låseskjerm, Kalender, Nettfilter, Innlogging, Hjem-skjerm). Rule 1: label alone; content into the group's (i).
- **C4. Fine-state lines (rule 5, state logic).** Lines that report "all is well" should go: Mobilnett's switch
  lines and "Mobildata: bare Vibb"; the phone page's grey mobile summary lines (`mobile_network::summary_lines`);
  `kiosk_escapes::escapes_card` «Siste oppdatering · Installert», «Neste oppstart · Klar»; `dns_filter::phone_line`
  «Serveren din er i bruk»; `dns_filter::list_scope` «På for alle telefoner» under an on switch; Innlogging «På
  telefonen · Klar»; `device_pages::play_card` «Play Butikk · Oppdaterer apper»; «Installert» under every app row
  (`status_label`); `app_push.signed`; Varsler' per-event delivery line (`delivery_line`, only on failure);
  Varselinnstillinger' device-row sub (`device_row`); Spotify accounts' login dates and «Storytel godtok innloggingen»;
  Telefon › Musikk «Logg inn igjen før …» while far off; the time page's «Fra telefonens siste melding, …» (only when
  stale); Finn › Soner' ~35-word footer when all works.
- **C5. Said too often.** «Nødanrop virker alltid» on about five time pages (once, behind an (i), is enough - KEEP the fact);
  «Telefonens nummer mangler» three times on Anrop; Spotify's play order 2-3 times on one music entry.

## Needs the user's call before building

- Phone header «På nett» beside the green dot (`phone_status::header`): the 2026-11-01 pick C put it there; the rule says drop it.
- Anrop › Avansert summary lists default values on purpose (design 80 D8, "so nothing hides"); the rule says show only changed ones.
- Mobilnett «Mobildata er slått av på telefonen»: orange (Vibb has no cellular) or grey (the lock footer's own recipe turns it off on purpose)?
- Kalender's lock-screen privacy caution: behind the (i), or kept as the page's one footer line?

## Stale words found on the way (bugs, fix regardless)

- `device.summary.foreign.step_unlock` «Åpne telefonen med Lås opp 2 t nedenfor»: that button is gone from the phone's page; «Åpne for oppsett» lives on Avansert.
- `calendar.calendars.setup` points to «Kalenderlenker over»; the group is now «Hentes av serveren».
- A Varselinnstillinger clock-warning sentence still speaks of a Pi; the server runs on a VM (see that page's findings).
- `mobile.abroad.national` names «Ingen data»/«Som hjemme», which the approved Roaming rename turns into «Av»/«På».

# Phone list and a phone

## Telefoner (`devices_list.html`)
1. **Push offer's sub-line** - `partials/push_offer.html` / `devices.push_offer.sub` - Today: «Få vite med en gang når en telefon trenger deg - en nødsamtale, lavt batteri, en forespørsel om mer tid. Du kan endre det senere under varselinnstillingene.» (27 words under a 5-word question) - Rule 1, 2 - Proposal: drop the sub-line; the title «Få varsler på denne telefonen?» and the two buttons carry it. If one example must stay: «Nødsamtaler, lavt batteri, ønsker om mer tid.» and drop «Du kan endre det senere …» - [words]
2. **Push warning's sub repeats the link** - `devices_list.html` `#push-warning` / `devices.warning.push_sub` (built in `handlers/notifications.rs` `push_warning`) - Today: «Sjekk det under Varsler» under e.g. «Varsler vises kanskje ikke på iPhone» - Rule 4 (the whole card is a link with a chevron to Varsler) - Proposal: no sub (`sub: None` for the list's card; the single-device detail `push_unconfirmed`/failure stays on Varsler' own page) - [words; `WarningRow.sub` becomes optional]
3. **Push offer's done flash** - `partials/push_offer.html` / `devices.push_offer.done` - Today: «Varsler er slått på for denne telefonen.» - Rule 4 - Proposal: «Varsler på» - [words]

Not flagged (KEEP): `devices.warning.live*`/`login*` («Noen kan passordet ditt - bytt det») - a security warning, already one line + a short sub.

Summary: 3 findings, size S

## Telefonens side (`device_detail.html`)
1. **Blocked apps: footer + the "more" sentence** - keys `device.summary.blocked.footer`, `device.summary.blocked.more.*` - Today: «Skjult på telefonen selv om de er tillatt under Apper. Og {count} til som telefonen ikke lister opp: se etter dem under Innstillinger › Apper på telefonen, og avinstaller det du ikke kjenner.» - Rule 2, 4, 5 - Proposal: drop the footer sentence (the header «Blokkerte apper» and each row's reason say it); the "more" part becomes its own orange row-note «{count} til som ikke vises» with the how-to (Innstillinger › Apper, avinstaller) behind an (i) - [words] - (KEEP: `blocked.confirm_one/_many` «Vise {apps} igjen … Bare hvis du kjenner appen og nøkkelen er den du venter» - a security confirmation, stays as is)
2. **Oppsett card: a sub-line under every step + a how-to footer** - `device_detail.html` `#oppsett`; steps built in `airplane_mode::setup_steps` / `device.summary.setup.background_sub` «Endringer kan komme sent mens telefonen sover.», `device.about.write_settings_sub` «Bryterne for berøringslyder og klokkeformatet trenger den.», `device.summary.setup.assistant_sub` «Velg Vibb One - trengs for Flymodus», `.wifi_sub` «Ikke satt opp. Uten det er telefonen helt uten nett i flymodus.», `.default_apps_sub` + `.default_apps_sms` «Endret under oppsettet: {apps}. Meldinger som kom mens en annen app hadde SMS-rollen, ligger i den appen.», `.call_filter_sub` «Vibb One trenger anropsfilter og viderekobling for å styre samtalene.»; footer `device.summary.setup.footer` «Fullfør oppsettet på telefonen: åpne den for oppsett, og trykk på varselet «Fullfør oppsettet av Vibb One».» - Rule 1, 2, 3 - Proposal: each step is its label alone (+ a quiet value where it is a state: «Standardapper endret · SMS-app, Telefonapp»); the why of each step behind a per-row (i) (`details.info.cell` row, as `note_row.html`); the footer's how-to behind one (i) by the «Åpne for oppsett i 2 t» button - [words; `SetupStep.sub` → a detail behind (i), `default_apps` keeps the role names as the value]
3. **Quick actions › Slå på regel: the explanation lines under the form** - keys `device.summary.qa.mode_line` / `.mode_line_none` «Telefonen låses som i {rule}: {list} virker. Nødanrop virker alltid.», `device.summary.qa.pause_note` «Reglene kommer tilbake av seg selv, eller med Avslutt nå. «Alle regler» finnes bare for pause.» - Rule 2 - Proposal: `pause_note` goes (the tile's own words and «Avslutt nå» on the running row say it); `mode_line` behind an (i) on the «Regel» row (JS fills the panel instead of `data-qa-line`) - [words; `static/quick-sheets.js` fills the (i)'s text] - (KEEP: «Nødanrop virker alltid» stays in the (i) text - the emergency reassurance must not be lost)
4. **Quick actions: the warnings before Slå på are sentences** - keys `device.summary.qa.warn_setup` «Telefonen er åpnet for oppsett til {at}. Det avsluttes når du slår på {rule}.», `.warn_setup_plain`, `.warn_paused` «{rule} er på pause til {at}. Pausen avsluttes.», `.warn_all_paused`, `.warn_already` - Rule 5 (one short orange line) - Proposal: «Avslutter oppsettet (til {at})», «Avslutter oppsettet», «Avslutter pausen av {rule}», «Avslutter pausen av alle regler», «{rule} gjelder til {at}» - [words] - (KEEP the lines themselves: they say what the button will undo)
5. **Today's flashes explain delivery** - keys `device.summary.flash.extra` «Lagt til. Telefonen får det i løpet av sekunder når den er på nett.», `.pause` «Satt på pause. Regelen kommer tilbake av seg selv.», `.ended` «Avsluttet. Regelen er tilbake når telefonen synkroniserer.», `.on` «{rule} er slått på til {at}. Telefonen får det i løpet av sekunder når den er på nett.», `.on_ended`, `.end_windows_sent` «Sendt. Telefonen låses igjen i løpet av sekunder når den er på nett.», `.start_pause_sent`, `.allow_blocked_sent` «Sendt. Appene som er tillatt under Apper, vises igjen i løpet av sekunder når telefonen er på nett.», `.gone` «Den regelen gjelder ikke lenger for denne telefonen. Ingenting ble satt på pause.», `.on_gone` - Rule 4 (the lift/lock row right under the flash already says «Venter på telefonen»/«På telefonen») - Proposal: «Lagt til», «Satt på pause», «Avsluttet», «{rule} på til {at}», «Avsluttet», «Sendt», «Sendt», «Sendt»; refusals «Regelen gjelder ikke lenger» - [words]
6. **Foreign-access alarm: sub + three-step list visible** - `#foreign-access` / `device.summary.foreign.sub_one/_many`, `.step_paused`, `.step_unlock`, `.off_both/_admins/_accessibility`, `.uninstall_*`, `.step_pin.*` - Today: title «Ikke skriv foreldre-PIN-en på telefonen» + «{apps} kan styre telefonen eller lese skjermen, så telefonen avviser foreldre-PIN-en til appen er borte.» + an `<ol>` of three long steps - Rule 5 (one line, the fix behind (i)) - Proposal: title + «{apps} kan lese skjermen.»; the three steps behind an (i) labelled «Slik fikser du det» (the packages' (i) merges into it) - [words] - (KEEP: the title stays visible and red - a safety warning. Also a bug: `step_unlock` «Åpne telefonen med Lås opp 2 t nedenfor» names a button the summary no longer has - «Åpne for oppsett i 2 t» lives on Avansert since design 93/98 §6.6; reword «Åpne for oppsett under Avansert» whatever the layout)
7. **Access (phone not set up): footer how-to + sub-lines** - `#access` / `device.summary.access.footer` «Skriv inn koden under Innstillinger i Vibb One på telefonen, eller skann QR-koden under oppsettet. En ny kode erstatter den forrige.», `.paused_sub.*` «{count} feil koder ble prøvd denne timen. En QR-kode for oppsett virker fortsatt.», `device.summary.no_parent_pin_sub` «Uten den kan ingenting åpnes eller oppheves på selve telefonen.» - Rule 2, 5 - Proposal: footer behind an (i) on the header (the code and QR pages say where to type/scan); `paused_sub` → «QR-koden virker» (the count behind (i)); `no_parent_pin_sub` behind (i), the title «Velg en foreldre-PIN før oppsettet» alone - [words]
8. **Relock group: footer and a doubled label** - `#relock` / `device.summary.relock.footer` «Det telefonen fant da den ble låst igjen. Vises i en uke.», `device.summary.relock.title` and `.at` both «Låst igjen», `.turned_off_sub` «{names}. Slå dem på i telefonens innstillinger under en pause.» - Rule 4, 3 - Proposal: footer goes (or behind an (i) on the header); the first row's label becomes the trigger alone («Tiden var ute · 14:02»), not «Låst igjen» under «Låst igjen»; `turned_off_sub` → «{names}», the how-to behind (i) - [words]
9. **Quick actions' «Til» sub-line** - `device.summary.qa.until_sub` (twice: extend and Slå på) - Today: «I dag, aldri over midnatt» under «Til» - Rule 1 - Proposal: drop; the refusal flash `flash.past_midnight` says it when it matters - [words]
10. **Screen time sheet's note** - `device.summary.qa.extra_note` «Kommer i tillegg til dagens grense.», `.extra_used` «Kommer i tillegg til dagens grense. Brukt i dag: {used} av {budget}.» - Rule 1, 4 (the tile is «Skjermtid», the button «Gi ekstra tid») - Proposal: only the quiet value «Brukt {used} av {budget}» when there is a budget, else nothing - [words; `quick_actions.rs` `extra_note` becomes `Option`]
11. **Mobile lines on the summary for a state the parent chose** - `#mobile-state` / built in `mobile_network::summary_lines`: `mobile.state.only_vibb` «Mobildata: bare Vibb» + `only_vibb_sub` «Apper får bare data på Wi-Fi.», `only_vibb_kid`, `only_vibb_abroad`, `mobile.state.data_off` «Mobildata er slått av på telefonen» + «Bare Wi-Fi, også for Vibb.» - Rule 5 (grey cards for a chosen state; the Sections row «Mobilnett · Bare Vibb · Mobildata av» already says it) - Proposal: show only the `warn` lines (roaming warnings) on the summary; grey lines only on the Mobilnett page - [state logic: `mobile_network::summary_lines` keeps `warn` lines only]
12. **Setup-open row's sub repeats its title** - `#setup-open` / `device.summary.qa.setup_sub` (built in `quick_actions.rs` `SetupRow`) - Today: «Åpnet for oppsett til 15:30» + «Apper og Play Butikk er åpne» - Rule 4 - Proposal: no sub; keep `pause_ends_old` but shorter: «Kan ikke låses igjen herfra» - [words]
13. **Ask card's explanation lines** - `#ask` / `device.summary.ask.pauses` «Knappene setter reglene på pause.», `.ask.adds` «Ingen regel låser nå: knappene gir mer skjermtid.» - Rule 2 - Proposal: behind one (i) after the sub-line (they say what the buttons do, not a state) - [words]
14. **Picture menu note** - `device.summary.picture_note` - Today: «Bildet vises bare for foreldrene.» - Rule 2 - Proposal: drop, or behind an (i) in the menu - [words]
15. **Stale-report note** - `device.summary.report_age` - Today: «Ifølge telefonens siste rapport, {age}.» - Rule 3 - Proposal: «Siste rapport {age}» - [words]
16. **Header says «På nett» beside a green dot** - `phone_status::header` / `devices.status.online` - Today: «● På nett · Hjemme siden 14:02 · 80 %» - Rule 5 (the dot says it; the module's own doc says "no 'seen 7 min ago' … the dot says that") - Proposal: no lead when online; the line starts with the zone or the battery. Pick C was approved 2026-11-01, so ask before changing - [state logic: `phone_status::header`]

Summary: 16 findings, size L

## Om telefonen (`device_about.html`)
1. **Connection footer explains the row above, plus an (i)** - `device.about.connection_footer` «På nett betyr at telefonen har en direkte forbindelse til denne serveren. Endringer når den i løpet av sekunder.», `.connection_info` - Rule 2, 4 - Proposal: drop the visible footer; its sentence and `connection_info` become the Status row's detail (the row opens as an (i), `Row::detail` in `device_pages.rs`) - [words]
2. **Crash footer** - `device.about.crashes_footer` «Vibb One starter på nytt av seg selv etter et krasj. En rapport inneholder bare hvor i koden det skjedde.» - Rule 2 - Proposal: the Krasjrapporter row's (i) (`Row::detail`), no footer - [words]
3. **Sound footer** - `device.about.at_last_report` «Ved siste melding.» - Rule 4 (low) - Proposal: drop, or fold it into the rows' (i)/`title` - [words]

Summary: 3 findings, size S

## Aktivitet (`device_activity.html`)
1. **Footer** - `device.activity.footer` «De siste 7 dagene, nyeste først.» - Rule 4 (the empty state already says «Ingenting de siste 7 dagene»; newest-first is evident) - Proposal: drop - [words]

Summary: 1 finding, size S

# Device sub-pages

## Hjem-skjerm (`device_home.html`)
1. **Hurtigkontroller: three footer sentences + (i)** - `device_home.html` / keys `device.home.quick_footer`, `device.home.quick_footer_shortcuts`, `device.home.quick_mobile` - Today: «Vises når barnet sveiper til venstre på Hjem-skjermen. Bryterne gjelder både Innstillinger og Snarveier. Mobildata-bryteren styres under Mobilnett.» + (i) - Rule 2, 4 - Proposal: no visible footer; the three sentences go into the existing (i) panel (first in it) beside the group header or as a footer (i) alone. - [words]
2. **Snarveier row: hidden / chosen sub-lines are sentences** - `device_home.html` / keys `device.home.shortcuts_hidden`, `device.home.shortcuts_chose`, `device.home.shortcuts_none` (built in `shortcuts::home_sub`) - Today: «{name} har skjult Snarveier i varsellisten. {name} kan vise dem igjen under Innstillinger › Snarveier.» / «{name} har valgt: {items}» / «Ingen snarveier vises nå.» - Rule 3, 5 - Proposal: quiet values: «Skjult av {name}» (how to show again behind the group's (i)), «{items}» alone, «Ingen». - [words] (the `home_sub` keys only; which states show is unchanged)
3. **Flymodus row: explanatory sub-lines** - `device_home.html` / keys `device.home.airplane_no_role`, `device.home.airplane_blocked`, `device.home.airplane_offline` (built in `airplane_mode::home_sub`, joined into one sub) - Today: «Vises for {name} når Vibb One er valgt som digital assistent. Se Oppsett på telefonsiden.» / «Flymodus er blokkert under Avansert › Begrensninger.» / «Uten Wi-Fi i flymodus er telefonen helt uten nett når flymodus er på.» - Rule 3, 5 - Proposal: no_role → one short orange line «Vibb One er ikke digital assistent», the step behind (i); blocked → quiet «Blokkert i Begrensninger»; offline → «Helt uten nett i flymodus» (short, the why behind the group (i)). - [state logic: `airplane_mode::home_sub` must mark no_role as a warning (orange) and the rest quiet; today all three are one plain row-sub] - (KEEP: offline - the kid is unreachable in flymodus; keep a short visible line.)
4. **Språk/klokke footer explains** - `device_home.html` / key `device.home.layout_footer` - Today: «Språket i Vibb Ones egne skjermer på telefonen. Klokkeformatet gjelder hele telefonen: …» - Rule 2 - Proposal: move behind a footer (i) alone (no visible sentence). - [words]
5. **Warning notes carry a second sentence** - `partials/note_warning.html` (shared by every sub-page) / keys `device.home.no_notification_access_sub`, `device.home.clock_not_applied_sub` (`device_pages::notification_access_note`, `clock_format_note`) - Today: «Varseltilgang er av» + «Antall uleste og musikkortet er av, og låseskjermen viser bare tapte anrop.»; «Klokkeformatet er ikke tatt i bruk» + «Telefonen kan ikke endre klokkeformatet ennå.» - Rule 5 (one short orange line), 4 (clock sub restates the title) - Proposal: title only; `_sub` moves into the (i) with `_fix`; drop `clock_not_applied_sub`. Consider changing the partial so `detail` always renders inside the (i). - [words + partial markup] - (KEEP: the adb command in `no_notification_access_fix`, behind the (i).)
6. **Bakgrunnsbilder footer** - `device_home.html` / keys `device.home.wallpapers_footer.before/.link/.after` - Today: «Barnet velger blant disse på telefonen. Legg til bilder under Innstillinger › Bakgrunnsbilder.» - Rule 4 - Proposal: keep only the link: «Flere bilder: Innstillinger › Bakgrunnsbilder». - [words]
7. **Alle Bluetooth-enheter: sub explains the switch** - `device_home.html` / key `device.home.bluetooth_any_sub` - Today: «Av: bare hodetelefoner og høyttalere kan pares» - Rule 1 - Proposal: drop the sub; text into the group's (i). - [words] (security review S-L5 switch: the meaning stays reachable in the (i))
8. **Two words for "system default"** - `device_home.html` / keys `device.home.language_system`, `device.home.clock_system` - Today: «Samme som telefonen» / «Følg telefonen» - Rule 1 (consistency) - Proposal: both «Som telefonen». - [words]
Summary: 8 findings, size M

## Apper (telefonens, `device_apps.html` + `partials/device_app_row.html`)
1. **Every app row says "Installert"** - `partials/device_app_row.html` / keys `device.apps.installed`, `device.apps.preinstalled`, `device.apps.from_play` (`devices::unified_app_rows`, `status_label`) - Today: «Installert» / «Forhåndsinstallert» / «Installert fra Play» under each of ~20-40 rows - Rule 5 - Proposal: show nothing when installed and current; keep the line only when something is going on (installing, failed, update waiting, not installed, «Ingen utgivelse ennå»). «fra Play» may stay as a quiet word only where it matters (the catalog-conflict refusal names it anyway). - [state logic: `status_label` empty for the plain installed/preinstalled states; the template must skip an empty row-sub]
2. **Footers: sentence + (i) twice** - `device_apps.html` / keys `device.apps.footer`, `device.apps.downloads_footer` - Today: «Slår du av en app, skjules den, og den avinstalleres der det går.» + (i); «Slå den av når en app trengs nå.» + (i) - Rule 2 - Proposal: (i) alone; both sentences go first in their panels (`device.apps.info`, `device.apps.downloads_info`). - [words]
3. **Corrupt-list warning: second sentence** - `device_apps.html` via `note_warning.html` / key `device.apps.corrupt_sub` - Today: «Telefonen beholder den siste listen den hadde, og å slå apper av eller på her mislykkes til det er fikset.» - Rule 5 - Proposal: title «Applisten kan ikke leses» alone, sub into the (i) with `corrupt_fix`. - [words] - (KEEP: the title; it explains why every switch fails.)
4. **Managed Phone app row sub** - `partials/device_app_row.html` / keys `apps.dialer.managed_sub`, `apps.dialer.off_sub` (`device_pages` dialer link row) - Today: «Skjult på telefonen - barnet ringer fra Telefonbok på Hjem» / «Skjult på telefonen - bare nødnumre kan ringes» - Rule 5, 1 - Proposal: managed → no sub (the label «{app} → Vibbs telefonbok» says it); off → «Bare nødnumre». - [words] - (KEEP: off_sub's "only emergency numbers" is safety: keep the short form.)
5. **Meldinger/Kalender "En del av Vibb One"** - `partials/device_app_row.html` / keys `apps.builtin.part_of_vibb`, `apps.builtin.sms_unmanaged` - Today: «En del av Vibb One · SMS er ikke styrt av Vibb» - Rule 5 - Proposal: drop «En del av Vibb One» (the missing switch shows it); keep only «SMS ikke styrt av Vibb» when that is the state. - [state logic: `device_pages` builtin rows' `status_label`]
6. **"Oppdater Vibb One" note is not orange** - `device_apps.html` / key `apps.perms.update_note` - Today: «Oppdater Vibb One for å styre appenes tillatelser» (plain row-note) - Rule 5 - Proposal: one short orange line «Vibb One må oppdateres» with the why behind (i). - [words + tone]
7. **Chrome login-only sub** - `partials/device_app_row.html` / key `logins.apps.chrome_row` - Today: «Brukes bare til innlogging (Innlogging-siden)» - Rule 1 - Proposal: «Bare innlogging» (still the link). - [words]
8. **Refusal flash for the dialer** - `partials/device_app_row.html` / key `apps.dialer.refused_dialer` (`device.apps.refused_dialer`) - Today: «Samtaler er styrt av Vibb: Telefon-appen følger Vibbs telefonbok. Endre det under Anrop og kontakter.» - Rule 3 - Proposal: «Styrt av Vibbs telefonbok - se Anrop og kontakter». - [words]
Summary: 8 findings, size M

## Navn og ikon (`partials/app_display_form.html`, on Device › Apps and the catalog app page)
1. **Name field label carries its rules** - key `apps.display.label_field` - Today: «Navn på telefonen (høyst 20 tegn; tomt = appens eget)» - Rule 1 - Proposal: «Navn»; the placeholder already shows the app's own name and the 20-character limit is the error (`apps.display.error.label_long`). - [words]
2. **"Farge bak ikonet"** - key `apps.display.colour` - Today: «Farge bak ikonet» - Rule 1 - Proposal: «Farge». - [words]
3. **Catalog default hint** - key `apps.display.catalog_default` - Today: «Katalogens standard: {default}.» shown on every phone's form - Rule 5 - Proposal: show only when the phone has its own value differing from the catalog's (i.e. when Tilbakestill is offered). - [state logic: `app_display::AppDisplayForm.catalog_default` set only with `can_reset()`]
Summary: 3 findings, size S

## Tillatelser (`partials/app_permissions_form.html`)
1. **Camera switch sub is two sentences** - key `apps.perms.camera_help` (`app_permissions::words::camera_help`) - Today: «Av: {app} kan ikke bruke kameraet - en videosamtale eller QR-skanning slutter å virke. Å slå det av lukker {app} …» - Rule 1 - Proposal: no sub; text behind an (i) beside «Kamera». - [words + markup]
2. **Lock-screen media switch sub** - key `apps.perms.lock_media_help`, label `apps.perms.lock_media` - Today: «Mens appen spiller, vises tittel og bilde på låseskjermen - før koden er tastet. …» / label «Vis på låseskjermen som mediespiller» - Rule 1 - Proposal: label «Mediespiller på låseskjermen», sub behind (i). - [words] - (KEEP in the (i): "before the code is typed" is a privacy point.)
3. **Unapproved access: path as a sub-line, not orange** - keys `apps.perms.access_state`, `apps.perms.how`, `apps.perms.how_alarm` (`PermsView.accesses`) - Today: «Medieadministrering: ikke godkjent» + «På telefonen: Innstillinger (foreldrekode) › Sett alle begrensninger på pause › Tilgang for apper › …» - Rule 3, 5 - Proposal: the state line orange and short («Medieadministrering ikke godkjent»), the path behind (i). Approved accesses: quiet «godkjent» value or nothing. - [words + tone; `PermsView.accesses` keeps the split]
4. **Camera-off line while paused** - key `apps.perms.camera_paused` - Today: «Kamera av · på igjen mens begrensningene er satt på pause» - Rule 3 - Proposal: «Kamera av · pause». - [words]
Summary: 4 findings, size S

## Push for apper - gruppen på Apper (`partials/app_push_group.html`; only what design 112's Device › Apps section misses)
1. **Long warning lines with the fix inline** - keys `app_push.missing`, `app_push.missing_open`, `app_push.outside`, `app_push.waiting`, `app_push.noisy`, `app_push.vapid_refused`, `app_push.vapid_refused_time`, `app_push.vapid_refused_address`, `app_push.refused_fix` (`app_push::AppPushGroup`) - Today e.g. «Varslene til {app} går gjennom en ekstern tjeneste (som regel unifiedpush.org), utenfor familiens server. Vibb One ber {app} sjekke adressen på nytt … se «Sist sett utenfra» …» (55 words); «{app} bruker ikke Vibb for varsler. I {app}: Innstillinger › Varsler › Avansert › Push-leverandør › Vibb One, én gang for hver konto.» - Rule 5, 3 - Proposal: one short orange line each, the rest behind an (i): missing «{app} bruker ikke Vibb for varsler»; outside «Går via en ekstern tjeneste»; waiting «Varsler venter på serveren siden {when}»; noisy «Rundt {count} push om dagen»; refused «Push avvist, sist {when}» (time/address cause and refused_fix's advice in the (i)). - [words + markup: the template needs an (i) per warning line]
2. **Switch sub always shown** - keys `app_push.switch_sub`, `app_push.needs_update`, `app_push.no_origin` - Today: «Apper på listen kan få varsler gjennom Vibb, uten Google» under the switch, on or off; «Sett telefonenes offentlige adresse i Innstillinger › Push for apper først» plain - Rule 1, 5 - Proposal: no sub when on and working; switch_sub behind the group's footer (i); needs_update and no_origin as short orange lines («Vibb One må oppdateres», «Mangler adressen - se Innstillinger › App-push»). - [state logic: template branch; tone of needs_update/no_origin]
3. **"Signed" good state shown** - key `app_push.signed` - Today: «Bare push signert av appens server» on each app row - Rule 5 - Proposal: show nothing (it is as it should be); the fact goes into the footer (i). - [state logic: drop the `app.signed` sub]
4. **Delivery line wordier than 112's settings rows** - keys `app_push.delivery_line_p95`, `app_push.delivery_line.one/.other` - Today: «Vanligvis levert på {median}, 95 % innen {p95} ({count} push mens den var tilkoblet, siste døgn)» - Rule 1, 3 - Proposal: align with 112: «Levert på {median} · {count} push siste døgn» (p95 and "while connected" into the (i)). - [words]
5. **Confirmations** - keys `app_push.off_question`, `app_push.stop_question` - Today: «Apper som bruker Vibb for varsler, får ingen mens dette er av, til hver av dem settes tilbake til Google … (Element X: Innstillinger › …)» - Rule 1 - Proposal: trim to the consequence: «Appene får ingen varsler før de settes tilbake til Google i sine egne innstillinger.» / «{app} får ingen varsler gjennom Vibb før du tillater det igjen.» - [words] - (KEEP: both are confirmations of actions that silence the kid's apps; the consequence sentence stays.)
Summary: 5 findings, size M

## Anrop og kontakter (`device_calls.html`)
1. **Every warning shows its how-to in the open** - `partials/note_warning.html` (used by `warnings` here, and by Låseskjerm, Tidsregler and other pages) / `device.calls.finding.*` via `Finding::note` (`handlers/calls.rs`) - Today: title + visible sentence, e.g. «Vibb One er ikke telefonappen» + «Den kan bare ringe nødnumre, og skjulte numre kan ringe gjennom. Åpne Hjem-skjermen på telefonen og godta «Angi som standard telefonapp».»; the same shape for `dialer_not_allowed`, `anyone_can_call`, `messages_hidden`, `sms_app_allowed`, `sms_not_ours`, `hold_full_until/_day`, `mms_unchecked(+_allow)`, `mms_no_data`, `own_number`, `needs_update*`, `call_log`, `restart_*`, `too_old`, `stopped_screening`, `sms_role_kept`, `unconfirmed` - Rule 5 - Proposal: the partial shows the orange title alone (plus at most a 3-6-word consequence where the title doesn't carry it, e.g. «Bare nødanrop virker»); `detail` joins `fix` behind the (i). One template change covers every page - [words + template; `Note` gets no new states] - (KEEP visible: `emergency_call` «Nødanrop fra denne telefonen», `callback` «Alle kan ringe denne telefonen til {until}…» and `screen_failed` - safety events whose time is the news; keep their sentence visible.)
2. **Channel sub-line under every segmented choice** - `device_calls.html` l.203 `channel.sub` / `device.calls.channel.calls_off`, `.calls_managed`, `.calls_unmanaged`, `.sms_off`, `.sms_managed`, `.sms_unmanaged` - Today: «Bare kontaktene du har tillatt kan ringe og bli ringt.», «Alle kan sende SMS, og alle meldingsapper virker. Vibb filtrerer ikke.» - Rule 1 (restates the chosen segment), Rule 5 - Proposal: drop the line; one sentence per mode goes into the group's (i) (`device.calls.info`) - [state logic: `channel_row` stops returning `sub`] - (KEEP: «SMS er av. Meldinger til telefonen går tapt.» in the (i) - it says what is lost.)
3. **Contacts footer + photo privacy paragraph** - l.484 / `device.calls.contacts_footer`, `device.calls.photo_privacy` - Today: «Navnet og bildet til en kontakt (opptil 10 MB) deles av alle telefoner med det nummeret. Serveren tar også vare på en mindre kopi av originalbildet, …» (55 words, always) - Rule 2, 4 - Proposal: no footer; both behind one (i) beside «Legg til bilde»/«Nytt bilde» - [words] - (KEEP the privacy fact - copy kept, in backups - inside the (i).)
4. **Nødmeny footer** - l.540 / `device.emergency.footer` - Today: «Barnet får menyen når det trykker Nødsamtale; ett trykk ringer. Med bare ett nummer spør telefonen «Ringe 112?» som før. Bare nødnumre virker alltid; andre nummer følger anropsreglene …» (45 words) - Rule 2 - Proposal: behind an (i) on the «Nødmeny» header; visible nothing (the full-menu refusal already says «høyst 6») - [words] - (KEEP: «andre nummer … virker bare når telefonen er låst opp» stays in the (i) and as the orange row line, item 5.)
5. **Every emergency row has a quiet sub-line** - l.509 `row.sub` / `device.emergency.big` «Øverst og størst på telefonen», `device.emergency.always` «Nødnummer: virker alltid» (also under every suggestion, l.546) - Rule 5 - Proposal: no sub on a real emergency number; only the orange `device.emergency.not_emergency` row, shortened to «Ikke et nødnummer - følger anropsreglene» (the bracket «(telefonen kan likevel kjenne det som nødnummer)» behind the (i)); `not_in_phone_book` → «Ikke i telefonboken» - [state logic: `emergency_rows` gives `sub` only when `warn`; suggestions lose the line] - (KEEP the orange not-emergency line: safety.)
6. **Meldinger som venter footer** - l.87 / `device.sms.held.footer` - Today: «Meldinger fra avsendere du ikke har tillatt, venter på telefonen i et døgn uten å vises. Du ser hvem og når, aldri hva de sier. …» (37 words) - Rule 2 - Proposal: behind an (i) on the header - [words].
7. **Advanced summary lists default values** - l.493 / `device.calls.adv.emergency` «Nødmeny: 112, 113», `device.calls.adv.message_button` «Meldingsknapp: Telefonens standard (…)», `adv.names`, `adv.groups_off` - Rule 5 (quiet values even when nothing is changed) - Proposal: show only what differs from the default (names count, «Gruppemeldinger av», the orange `adv.emergency_rules` and `number_missing`); drop the always-on «Nødmeny: …» and a default «Meldingsknapp» - [state logic: `adv_emergency`/`adv_messages` in the calls handler; design 80 D8 chose "every value, so nothing hides" - a user call].
8. **Contact switch sub-lines when the channel isn't managed** - l.271/288/294/298, 408-423 `contact.subs.*` / `device.calls.sub.calls_unmanaged_in_book` «Samtaler er ikke styrt - alle kan ringe. {contact} står i telefonboken», `.calls_applies`, `.sms_applies` «Gjelder når SMS er styrt av Vibb» (under three switches at once), `.pictures_no_texts`, `.home_needs_out` - Rule 1, 4 - Proposal: one quiet word per switch: «Ikke styrt» / «Samtaler av»; drop the «står (ikke) i telefonboken» clause and the repeat on Bilder (show it on Meldinger only) - [state logic: `calls_form::subs`].
9. **Calls footer repeats the channel lines** - l.228 / `device.calls.footer` «Når det styres, kan bare kontaktene nedenfor ringe eller sende meldinger. Nødnumre virker alltid.» + (i) - Rule 2, 4 - Proposal: (i) alone at the group's end; its text gains the per-mode lines from item 2 - [words] - (KEEP in the (i): ##004# and «Ikke legg til mobilsvarnummeret eller 1881 og 1880» - a real bypass warning.)
10. **Forespørsler footer** - l.181 / `device.requests.footer` «{name} har bedt om disse fra telefonboken. Ingenting legges til før du lagrer kontakten selv. Avslå gir beskjeden «Ikke lagt til» …» - Rule 2 - Proposal: behind an (i) on the header - [words].
11. **Blokkerte meldinger footer** - l.102 / `device.sms.blocked.footer` «De siste 30 dagene. Disse avsenderne kan ikke tillates: …» - Rule 2 - Proposal: «Siste 30 dager» as the header's value; the reason behind (i) - [words].
12. **Gruppemeldinger sub + SMS-settings footer** - l.599, 653 / `device.sms.settings.groups_sub` «Bare når alle andre i gruppen er kontakter med meldinger slått på, og telefonens nummer er kjent.», `device.sms.settings.footer` «Dette gjelder når SMS er styrt av Vibb. Uten telefonens eget nummer blokkeres gruppemeldinger.» - Rule 1, 4 (number_missing already says it, twice) - Proposal: sub and footer behind one (i) on «Gruppemeldinger»; «Gjelder når SMS er styrt av Vibb» only as a quiet sub when `sms_mode != managed` - [words; footer condition is state logic].
13. **Phone's number said three times** - `device.calls.number_missing` (l.217 under SMS and l.493 in the Avansert summary) + `device.sms.own.unknown_hint` «Telefonen vet ikke nummeret sitt ennå. Uten det blokkeres gruppemeldinger.» + `own_number_hint` «La det stå tomt hvis SIM-kortet vet nummeret sitt.» - Rule 4 - Proposal: keep the orange line under SMS (with its link); the Avansert summary drops it; the field hint → «Ukjent» or nothing - [state logic: `number_line` drawn once].
14. **Emergency-sender refusal said twice** - l.279 and l.315 / `device.sms.emergency_reason` «Nødnumre kan ikke sende meldinger hit - alle kan utgi seg for dem.» - Rule 4 - Proposal: once, on the main row, «Nødnumre kan ikke sende hit»; the reason behind (i) - [words] - (KEEP: anti-spoofing rule stays visible in short form.)
15. **RCS row in Avansert, permanent nag** - l.646 / `device.calls.rcs_title` + `device.calls.channel.sms_rcs` «Slå av chatfunksjoner i Meldinger hvis du ikke har gjort det.» - Rule 5 (shown forever while managed, the server can't know it's done) - Proposal: the row label alone with its (i); no sub - [words].
16. **Matrix suggestion hint** - l.434 / `device.requests.matrix_hint` «{name} foreslo {id}. Meldingsknappen åpner Element X med den. Hvem som helst kan stå bak en Matrix-ID: sjekk at det er riktig person.» - Rule 1 - Proposal: sub «{id} - sjekk at det er riktig person»; the rest behind (i) - [words] - (KEEP the impersonation caution, short.)
17. **«Anbefalt» under a segment** - l.200 `option.note` / `device.calls.mode.recommended` «anbefalt» - Rule 1 (design 97 D1 dropped the same mark on Mobilnett) - Proposal: drop - [state logic: `channel_row` note].
18. **Bildeadresser full / gone lines** - l.125, 129, 701 / `device.calls.mmsc.full` «Åtte adresser er tillatt for dette SIM-kortet. Fjern én nederst på siden for å tillate denne.», `device.calls.mmsc.gone` «Bildet er ikke lenger tilgjengelig. Neste bilde hentes hvis du tillater adressen.» - Rule 3 - Proposal: «Fullt: 8 av 8» / «Bildet er borte»; how-to behind (i) - [words].
19. **(i) labelled «Mer»** - l.139 / `device.calls.mmsc.more` - Rule 2 - Proposal: the (i) icon alone after the question - [words/template].
20. **Add Contact field hints** - l.395, 398, 478 / `device.calls.number_hint` «Uten landskode brukes +{code} (Innstillinger › Telefonoppsett).», `device.requests.short_choose` «Kortnummer - kan koste penger. Velg selv om {name} skal kunne ringe …», `device.calls.family_hint` «En kontakt fra en annen telefon: navnet, nummeret og bildet er felles.» - Rule 1, 3 - Proposal: «Uten landskode: +{code}»; «Kortnummer - kan koste penger»; family hint gone (or (i)) - [words] - (KEEP the cost warning.)
21. **Empty states that explain** - l.383, 670 / `device.calls.none` «Ingen kontakter ennå. Når anrop styres, virker bare nødnumre.», `device.sms.names.none` «Ingen avsendernavn. Meldinger fra navn som Posten eller DNB venter på deg.», `device.sms.names.hint` «Nøyaktig slik det vises på en telefon, 1-11 tegn med en bokstav. …» - Rule 3, 4 - Proposal: «Ingen kontakter ennå.», «Ingen.», hint «1-11 tegn, som på telefonen» - [words].
Summary: 21 findings, size L

## Låseskjerm og PIN-koder (`device_lock.html`)
1. **Lock-screen notifications footer** - l.319 / `device.lock.notifications_footer` «Bare navn: avsenderens navn for chatter, ellers appens navn og hvor mange. Hele teksten: meldingen også, innloggingskoder på SMS inkludert, som alle som holder telefonen, kan lese. …» (37 words) - Rule 2 - Proposal: behind an (i) on the header; visible only when some app is on «Hele teksten»: one orange line «Hele teksten viser også innloggingskoder» - [state logic: a flag from the lock-glance rows] - (KEEP the SMS-code privacy warning.)
2. **Weather group words** - l.258, 266/271, 284 / `device.weather.lock_sub` «Yr-symbol og temperatur ved datoen; trykk for dagen. Fra MET Norge, for der telefonen er (omtrent 2 km).», `device.weather.zone` «Når posisjonen ikke er fersk, bruk», `device.weather.zone_sub` «Posisjonen er fersk når Posisjon står på Jevnlig. …», `device.weather.footer` - Rule 1, 4 (2 km twice) - Proposal: no subs; zone label «Reservesone»; zone_sub behind (i); footer keeps only «Værvarsel fra MET Norge (CC BY 4.0), symboler © Yr/NRK.» - [words] - (KEEP the licence attribution: legal.)
3. **Parent-PIN alarms with paragraph subs** - l.133, 138, 143 / `device.lock.no_parent_pin_sub` «Uten den kan ingenting åpnes eller oppheves på selve telefonen. Hvis denne serveren går tapt, er det bare en tilbakestilling som får telefonen tilbake.», `device.lock.easy_sub`, `device.lock.used_offline_sub` - Rule 5 - Proposal: title alone, the sub behind (i) - [words] - (KEEP: no-parent-PIN alarm visible - losing the server means a factory reset.)
4. **Lock-glance app notes with how-to** - l.305 `app.note`/`app.hint` / `device.lock.glance.no_access_sub` «Vibb One har ikke varseltilgang, så meldinger vises ikke. Løsningen står under Hjem-skjerm på telefonens side.», `.no_access_messages_sub`, `.needs_update_sub`, `.no_child_pin_sub` «Telefonen åpner rett på Hjem-skjermen og viser ingenting av dette.» - Rule 5 - Proposal: the short title only («Bare tapte anrop», «Ingen lås uten barne-PIN»); the rest behind (i) - [words; `lock_glance` notes].
5. **Child-PIN group: footer + screen footer say the same** - l.115, 203 / `device.lock.footer_own` «Låser telefonen når skjermen slås av.», `device.lock.screen_footer` «Med en barne-PIN låses telefonen når skjermen slås av.», `device.lock.footer_android` «Androids lås: PIN-tastaturet, og fingeravtrykk hvis du legger til et.» - Rule 4, 2 - Proposal: drop `screen_footer`; the child-PIN footer → the (i) alone (its `child_info_*` panel already there) - [words] - (KEEP `child_info_android` in the (i): after a restart the phone can't be found and alarms may not ring.)
6. **Wake/buzz sub-lines** - l.223, 230 / `device.lock.wake_sub` «For appene på låsen nedenfor og tapte anrop», `device.lock.buzz_sub` «Én kort vibrering, ingen lyd, for appene på låsen nedenfor og tapte anrop» - Rule 1 - Proposal: drop both; one (i) on «Låseskjerm» holds them with `lock_screen_footer` (item 7) - [words].
7. **Lock-screen footer** - l.236 / `device.lock.lock_screen_footer` «Det låsen viser, kan alle i nærheten av telefonen se. Et varsel kan sveipes bort der …» - Rule 2 - Proposal: behind (i) - [words].
8. **Lock notes' visible subs (through note_warning)** - `child_warnings` / `device.lock.note.android_credential_sub` «Fjern den så Vibb Ones lås kan ta over: tast inn foreldre-PIN-en på telefonen, deretter Innstillinger › Sikkerhet › Skjermlås › Ingen.», `.safe_mode_sub`, `.crash_guard_sub`, `.bad_hash_sub`, `.needs_update_sub`, `.unmanaged_sub`, `.android_needs_update_sub`, `.calls_cant_ring_sub` - Rule 5 - Proposal: covered by Anrop item 1 (title alone, sub + fix behind (i)) - [words/template].
9. **Remove-needs-PIN note** - l.99 / `device.lock.remove_needs_pin` «Androids lås trenger en barne-PIN. Vil du bytte den, lagrer du en ny ovenfor. Vil du fjerne den, velger du Vibb Ones lås først.» - Rule 3 - Proposal: drop the row (the field above changes it) or «Androids lås trenger en barne-PIN» with the rest behind (i) - [words].
10. **Parent-PIN footer + (i)** - l.170 / `device.lock.parent_footer` «Åpner telefonens innstillinger og opphever grenser, også uten nett.» - Rule 2 - Proposal: into the (i) with `parent_info`; (i) alone - [words].
11. **Long field hints** - l.156, 41, 84 / `device.lock.parent_pin_hint` «6 til 10 sifre, ikke barne-PIN-en. 8 eller flere er mye vanskeligere å knekke; bursdager og årstall er lette å gjette.», `device.lock.child_pin_hint` «4 til 6 sifre; 4 er enklest for et barn.», `device.lock.reveal_hint` «Det du logger inn her med. PIN-en vises bare på denne siden, i et minutt; tre feil passord logger deg ut.» - Rule 1 - Proposal: «6-10 sifre, ikke barne-PIN-en», «4-6 sifre», «Tre feil logger deg ut»; the advice behind a field (i) - [words] - (KEEP «tre feil passord logger deg ut»: a consequence the parent must know first.)
12. **Option with an explanation** - l.49 / `device.lock.own_lock` «Vibb Ones lås (fases ut)» - Rule 1 - Proposal: «Vibb Ones lås»; the phase-out in the group (i) - [words].
Summary: 12 findings, size M

## Tidsregler, a phone's (`device_time.html`)
1. **Visible «Om foreldrekoden» label beside the (i)** - l.40 / `device.time.rule_pause_info_label` (shown as text, not only aria) + `device.time.rule_pause_info` - Rule 2 (exactly the "About …" case) - Proposal: the (i) alone, at the «Tidslås» row or the «Nå» header - [words/template].
2. **Own-rules footer** - l.90 / `device.time.own_footer` «Gjelder i tillegg til reglene for alle som er på.» + `device.time.emergency` «Nødanrop virker alltid.» + `device.time.own_off_footer` «En regel som er av, ble tatt vare på fra da telefonen fulgte reglene for alle: slå den på for å bruke eller endre den.» - Rule 2, 4 - Proposal: footer gone; one (i) on the header - [words] - (KEEP «Nødanrop virker alltid» once per page, in the (i) - safety reassurance; see item 6.)
3. **Everyone's-rules footer** - l.69 / `device.time.everyone_footer` «Av betyr at regelen ikke gjelder for {name}. En regel som legges til senere, er på.» + link `everyone_link` «Endre reglene for alle» `everyone_link_after` «under Innstillinger.» - Rule 2 - Proposal: a link row «Endre reglene for alle ›» in the group; the two sentences behind (i) - [words].
4. **Limit footers repeat the rows** - l.123 / `device.time.limit_off_footer` «Av: {name} har den daglige grensen for alle, satt under Innstillinger.» (the «Grensen for alle» row shows it), `device.time.limit_footer` «Minutter per dag. Tomt betyr ingen grense.» (the «min» unit and «Ingen grense» placeholder show it) - Rule 4 - Proposal: drop both - [words].
5. **«Fra telefonens siste melding, …» always** - l.38 / `device.time.reported.before/after` - Rule 5 - Proposal: show only when the report is stale (e.g. older than 15 min), orange - [state logic: `reported` in the time page handler, `schedules.rs`].
6. **Bedtime footer** - l.55 / `device.time.bedtime_footer` «Hver natt etter telefonens klokke.» - Rule 4 - Proposal: drop - [words].
7. **Warnings' subs** - `warnings` / `device.time.incoming_not_blocked_sub` «Anrop styres ikke på denne telefonen, så en regel uten anrop stopper bare utgående anrop. Styr anrop under Anrop og kontakter.», `too_old_sub`, `ledger_unreadable_sub` - Rule 5 - Proposal: Anrop item 1 (title alone, rest behind (i)) - [template].
Summary: 7 findings, size M

## Leggetid (`device_bedtime.html`)
1. **«Stille» sub-line** - l.51 / `device.bedtime.silent_sub` «Ingen lyder eller varsler ved leggetid. Alarmer ringer fortsatt, apper som er tillatt ved leggetid har lyd, og anrop som er tillatt ved leggetid, ringer.» - Rule 1 - Proposal: behind an (i) on the row - [words].
2. **Nights footer** - l.40 / `device.bedtime.nights_footer` «Fra kveld til morgen, etter telefonens klokke. Fre-lør er nettene før lørdag og søndag. La en rad stå tom …» - Rule 2 - Proposal: behind an (i) - [words].
3. **Calls footer** - l.55 / `device.bedtime.calls_footer` «Telefonen er låst ved leggetid. Nødanrop virker alltid.» - Rule 4 - Proposal: into the (i) of item 1/2 - [words] - (KEEP emergency fact in the (i).)
4. **Save flash explains sync** - l.21 / `common.saved_online` «Lagret. Telefonen får det i løpet av sekunder når den er på nett.» - Rule 4, 5 - Proposal: «Lagret.» (the phone's own state shows if it lags) - [words; key is shared - check other users].
5. **Corrupt alarm's how-to** - l.26 / `device.bedtime.corrupt_sub` «{parts}, så de vises tomme her og lagres slik du ser dem: fyll dem inn igjen og trykk Lagre - eller fjern leggetiden.» - Rule 5 - Proposal: sub «{parts}»; how-to behind (i) - [words].
Summary: 5 findings, size S

## Unntak, partial (`partials/exceptions.html`, on Leggetid and Regel)
1. **Footer** - `rule.exceptions.footer` → `device.exceptions.footer` «Bare apper som telefonen tillater under Apper, vises her.», `device.exceptions.footer_everyone` «Bare apper som er tillatt på minst én telefon, vises her. En telefon som ikke tillater appen, holder den låst.» - Rule 2 - Proposal: behind an (i) on «Unntak» - [words].
2. **Kalender sub-line** - `device.exceptions.calendar_sub` «Kalender på telefonen, også nye avtaler» - Rule 1 - Proposal: drop - [words].
Summary: 2 findings, size S

## Mobilnett (`device_mobile.html`) - only what the approved entry (PLAN, "Device › Mobile network: too many words") does not already name

The approved entry covers: "I utlandet" -> "Roaming" with "På"/"Av"/"Bare Vibb" and no sub-lines, "Om utlandet" goes, Android's roaming switch shown only when off. The gaps below are the same rule applied to the rest of the page and to `mobile_network::card`'s state lines.

1. **Roaming switch lines in every other state** - `mobile_network::switch_line` / `mobile.abroad.switch.locked`, `.on_locked`, `.on_open`, `.paused_on`, `.paused_off`, `mobile.state.pending` - Today: «Androids roamingbryter: av og låst», «… på – låst, {name} kan ikke endre den», «… på – {name} kan endre den i Innstillinger», «… på – åpnet for oppsett nå» - Rule 5 - Proposal: none of these show. Under "Av" the switch is off-and-locked by design (that is the choice working); on is the good state. Only `off` (grey today) and `off_abroad` (orange) stay, merged into one orange line «Androids roamingbryter er av» (abroad: «{place} - Androids roamingbryter er av»), the path (`off_sub`/`off_abroad_sub`) behind the (i). Note the off line is grey at home today - the user wants it orange ("si ifra om denne IKKE er skrudd på"); but under "Av" it is expected off, so: orange only when the choice is "På" or "Bare Vibb" - [state logic: `switch_line`, `warn` true for `off` when abroad != "off"; the "locked"/"on_*"/"paused_*" arms return None]
2. **"Mobildata: bare Vibb" restates the chosen radio** - `only_vibb_line` / `mobile.state.only_vibb`, `.only_vibb_sub` - Today: «Mobildata: bare Vibb» + «Apper får bare data på Wi-Fi.» under the group whose checked radio already says "Bare Vibb" - Rule 4/5 - Proposal: drop the `"parent"` arm on this page (keep it on the summary, where it is the only mention). `only_vibb_abroad` likewise drops here when the Roaming choice is "Bare Vibb" and the phone is abroad (the roaming line says where) - [state logic: `card` data_lines]
3. **Kid's switch: label + 17-word sub-line** - `mobile.kid.label`, `mobile.kid.sub` - Today: «Barnets bryter for mobildata» / «{name} kan slå mobildata av på telefonen. Da bruker bare Vibb mobildata. Den kan bare stramme inn.» - Rule 1 - Proposal: label «Barnets bryter» (the group header says Mobildata), sub-line moves behind an (i) beside the label - [words]
4. **Mobildata choice sub-lines** - `mobile.data.on_sub`, `mobile.data.vibb_sub` - Today: «Alle tillatte apper kan bruke mobildata.» / «Apper og nettleser stopper. Du styrer telefonen, ser posisjon og får varsler som før.» - Rule 1 - Proposal: no sub-lines; "På" / "Bare Vibb" alone. `vibb_sub`'s content joins `mobile.data.info` - [words]
5. **"Hva Bare Vibb stopper" label row** - `mobile.data.info_label` - Today: «Hva Bare Vibb stopper» as a row with the (i) - Rule 2 - Proposal: the (i) alone, placed on the group header (or beside "Bare Vibb"); the label becomes the aria-label only - [words]
6. **Three footers** - `mobile.data.footer`, `mobile.abroad.footer`, `mobile.lock.footer` - Today: «Gjelder bare mobilnett: Wi-Fi virker som før. Samtaler og SMS virker med begge valgene, bildemeldinger (MMS) også.» / «Gjelder utenfor landet SIM-kortet hører til. Wi-Fi virker alltid.» / «Slik slår du mobildata helt av: …» (43 words) - Rule 4 (data footer repeats `mobile.data.info`'s "Samtaler, SMS og bildemeldinger (MMS) virker som før") and Rule 3 (lock footer is a how-to) - Proposal: all three go; data's and roaming's into their (i) (data's is already there), the lock's how-to into an (i) on the lock row - [words]
7. **Lock row sub-line** - `mobile.lock.sub` - Today: «Mobildata og SIM-innstillingene kan ikke endres på telefonen.» - Rule 1 - Proposal: label «Lås innstillingene» (the page is Mobilnett), sub-line into the lock's (i) with the footer's how-to - [words]
8. **"Mobildata er slått av på telefonen" is grey** - `mobile.state.data_off`, `.data_off_sub` - Today: «Mobildata er slått av på telefonen» + «Bare Wi-Fi, også for Vibb.» - Rule 5 - Proposal: this is a state that stops Vibb reaching the phone on cellular - one orange line «Mobildata er av på telefonen», the sub behind the (i). Ask the user whether a deliberately-off phone (the lock footer's own recipe) counts as "wrong"; if not, show it grey with no sub-line - [state logic: `card`, `warn`]
9. **Pending / lifted lines are long** - `mobile.state.pending`, `mobile.state.lifted` - Today: «Ikke på plass på telefonen ennå - kommer når telefonen henter innstillingene» - Rule 3 - Proposal: «Ikke på telefonen ennå» - [words]
10. **Lingering / still-on warnings: title + how-to sub** - `mobile.state.lingering*`, `.lingering_fix*`, `.still_on`, `.still_on_sub`, `mobile.abroad.national` - Today: «Bruker fortsatt roamingdata fra før låsen» + «Slå flymodus på og av, eller start telefonen på nytt, for å stoppe det.»; national «Nasjonal roaming: Ingen data stenger mobildata her – velg Bare Vibb eller Som hjemme» - Rule 3/5 - Proposal: keep as orange lines (they are the wrong states), fix moves behind the (i); national → «Ingen mobildata her (nasjonal roaming)», the advice behind the (i). Note `national`'s words name "Ingen data"/"Som hjemme", which the approved rename turns into "Av"/"På" - must change with it - [words]
11. **"Ingen data i utlandet til Vibb One er oppdatert" and the update lines** - `mobile.abroad.vibb_update`, `mobile.state.needs_update` - fine as short orange hints; only rename "utlandet" → keep, or «Virker etter oppdatering av Vibb One» to match - [words, optional]

Summary: 11 findings, size M (items 1, 2, 8 touch `mobile_network::card`/`switch_line`; the contract `mobile_network.json` is unchanged - the server's card lines are not in it, but check `tests::mobile_network` for line-key assertions)

## Telefon › Avansert (`device_advanced.html`)
1. **Sertifikatsjekk: state line, recovery row and footer, ~110 words always shown** - `device_advanced.html` / `server_tls.on`, `.watching`, `.stopped`, `.recovery`, `.recovery_unknown`, `.footer` - Today: «Sertifikatsjekk: på - bare Let's Encrypt eller ZeroSSL» (the header says «Sertifikatsjekk»), then «Gjenopprettingsadresse: {url} - avviser telefonen serverens sertifikat, sett den som Serveradresse …», then the footer «Når den er på, snakker telefonen … Flytter serveren til en annen utsteder: …» - Rules 3, 4, 2 - Proposal: one value row «Sertifikatsjekk · På» / «Følger med · fra {from}» / «Av siden {since}» (drop the header's word from the line). Make «Gjenopprettingsadresse · {url}» a value row with the how-to behind its (i). `recovery_unknown` (DEPLOY/PHONE_RECOVERY_URL) goes behind that (i). The whole footer goes behind one (i). `stopped`/`on_cannot`/`on_phone_off` stay as the only orange lines - [words + state logic: `server_tls::card` splits line/detail] - (KEEP: the turn-off confirm `server_tls.turn_off_confirm` «… Alle foreldre får beskjed.»; it is a security downgrade.)
2. **Oppdateringer og varsler: three switch sub-lines + footer** - keys `device.advanced.fence_sub`, `notification_filter_sub`, `boot_cover_sub`, `updates_footer` - Today: «Skjuler Androids hjem-skjerm mens Vibb One oppdateres.», «Fjerner varsler som åpner blokkerte skjermer.», «Viser Vibb-logoen mens telefonen starter.», footer «Vibb One installerer sine egne oppdateringer om natten, med skjermen av.» - Rules 1, 2 - Proposal: drop the three sub-lines and the footer sentence. Their text goes into the group's existing (i) (`updates_info`), so the (i) stands alone - [words]
3. **Escape state rows that report "all is well"** - `kiosk_escapes.rs` `EscapeNote::note` via `partials/note_row.html` / `device.advanced.escape.last`+`installed`, `cover_next`+`cover_ready`, `cover_last`+`cover_shown` - Today: «Siste oppdatering · Installert (i)», «Neste oppstart · Klar (i)», «Siste oppstart · Vist {at} (i)» - Rule 5 - Proposal: show none of these when nothing is wrong. Keep only `waiting` («Oppdatering venter · 0.37.0») as a quiet value, and the failures - [state logic: `kiosk_escapes::escapes_card` stops pushing `LastRelease(Replaced)`, `BootCoverArmed`, `BootCoverShown`]
4. **Escape/Play warnings print their whole explanation in orange** - `partials/note_warning.html` (shared with every page that uses `Note`) / e.g. `device.advanced.escape.needs_update_sub` (≈40 words), `install_failed_sub`, `partial_sub`, `filter_no_access_sub`, `device.advanced.play.restart_sub` - Today: «Vibb One på denne telefonen har verken oppdateringssperren eller varselfilteret ennå. Til den er oppdatert, viser …» - Rule 5 - Proposal: the partial shows the title only (one orange line), and `note.detail` goes behind an (i) (as `note.fix` already does). This is one partial change; every page that uses it benefits - [words, partial structure]
5. **Restrictions: three footers each beside their own (i)** - keys `device.advanced.restrictions_footer`, `debugging_footer`, `safe_boot_footer` - Today: «En pause eller foreldre-PIN-en opphever ikke disse.», «Slå den av før du jobber med telefonen fra en datamaskin.», «Slås på med den første barne-PIN-en: sikker modus hopper over låsen.» - Rule 2 - Proposal: move each sentence into its (i), so each (i) stands alone after its switch - [words] - (KEEP the substance of `debugging_info` «… er det bare en tilbakestilling som får den tilbake» inside its (i). It is the one irreversible consequence on this page.)
6. **Google backup: the alarm and a row say the same thing** - keys `device.advanced.backup_on`, `backup_on_sub`, `backup`, `backup_info` - Today: when on, the alarm «Sikkerhetskopi til Google er på» plus a 35-word sub, and below it the row «Sikkerhetskopi til Google · På». When off, the row «… · Av (i)» shows forever - Rules 4, 5 - Proposal: no row at all when off. When on, only the alarm line «Sikkerhetskopi til Google er på», with `backup_on_sub` and `backup_info` behind its (i) - [state logic: template `backup_service_enabled` match]
7. **Tilkobling: the access-removed alarm's sub + footer** - keys `device.advanced.access_removed_sub`, `connection_footer`, `device.summary.access.paused_sub` - Today: «Den når ikke denne serveren før den kobles til igjen med en ny oppsettkode. Den beholder de siste innstillingene.» (it repeats `disconnect_confirm` word for word), footer «En ny kode kobler til telefonen igjen etter en tilbakestilling og erstatter den nåværende tilkoblingen.» - Rules 4, 2, 5 - Proposal: alarm title only, sub behind (i); footer behind an (i) beside «Ny oppsettkode»; `paused_sub` behind (i) - [words] - (KEEP: `disconnect_confirm`, `leave_tailnet_confirm`, `remove_confirm` «Dette kan ikke angres.»; these are irreversible-action confirmations.)
8. **Wi-Fi-innlogging group repeats its header and explains** - keys `logins.code.label`, `logins.code.sub`, `logins.code.footer` - Today: header «Wi-Fi-innlogging», switch «Krev foreldrekode for Wi-Fi-innlogging», sub «Gjelder også å åpne påloggingssiden igjen», footer «Koden tastes på telefonen hver gang. Resten av Wi-Fi-innloggingen er på [Innlogging]» - Rules 4, 1, 2 - Proposal: switch «Krev foreldrekode». The sub and the first footer sentence go into an (i). The footer becomes just the link «Innlogging ›» - [words]
9. **Spotify diagnostic footer** - keys `advanced.spotify_diag.footer` - Today: «For å finne ut hvorfor nettspilleren stopper. Musikkappen skriver hva den gjør i telefonens egen logg; ingenting sendes hit.» - Rule 2 - Proposal: into the existing (i) - [words]
10. **Appskjermer footer** - key `device.advanced.block_screens_footer` - Today: «Slå den bare av hvis noe slutter å virke.» - Rule 2 - Proposal: into the (i) - [words]
11. **Play note rows that report normal state** - `device_pages::play_card` / `device.advanced.play.store`+`updating`+`updating_sub` - Today: «Play Butikk · Oppdaterer apper (i)» during the nightly window - Rule 5 - Proposal: show only while it explains a blocked Play (install mode is fine as a value) - [state logic: `play_card`]
12. **Setup sheet's line** - key `device.advanced.setup.line` - Today: «Apper, innstillinger og Play åpnes i 2 timer.» - Rule 1 (mild) - Proposal: put the button «Åpne i 2 t» (`device.advanced.setup.button` already exists) and drop the line - [words]
Summary: 12 findings, size L

## Telefon › Nettfilter (`device_filter.html`)
1. **Filter footer restates the page** - keys `device.filter.footer`, `footer_spotify` - Today: «Blokkerer listene og domenene nedenfor på denne telefonen.» - Rule 4 - Proposal: drop it, leaving the (i) alone after the switch. The Spotify variant's exception is already in the (i) (`spotify_hosts.*`) - [words]
2. **Allowed footer** - key `device.filter.allowed_footer` - Today: «Slippes gjennom selv om en liste blokkerer det - men ikke de sikre DNS-serverne som er bygd inn i Vibb One, de forblir blokkert.» - Rule 2 - Proposal: behind an (i) beside the header. The `doh_allowed` flash already says it when it matters - [words]
3. **Log footer** - key `device.filter.log_footer` - Today: «Lagres i {count} dager. Slår du den av, slettes loggen til denne telefonen.» - Rule 2 - Proposal: (i) - [words] - (KEEP «Slår du den av, slettes loggen». It is irreversible. Either keep it visible as one short line, or make switching off confirm.)
4. **Lists footer** - key `device.filter.lists_footer` - Today: «Som for alle følger bryteren under Innstillinger › Nettfilter.» - Rule 2/4 (the option «Som for alle (på)» says it) - Proposal: drop it, or move it behind an (i) on the header - [words]
5. **Spotify sub-line under a blocked domain** - key `device.filter.spotify_override` - Today: «Brukes ikke mens denne telefonen spiller Spotify» - Rule 1 (mild) - Proposal: «Av mens Spotify spiller» - [words]
Summary: 5 findings, size S

## Nettfilter › Innlogging (`device_logins.html`)
1. **Sites footer, 45 words** - key `logins.sites.note` - Today: «Siden appen viser kan sende Chrome videre til andre sider. Står de ikke her, sier Chrome at siden er sperret …» - Rule 2 - Proposal: (i) - [words]
2. **Wi-Fi switch sub-line + header/label repeat** - keys `logins.wifi.header`, `logins.wifi.label`, `logins.wifi.sub` - Today: header «Wi-Fi-innlogging (hotell, kafé, fly)», switch «Wi-Fi-innlogging», sub «Vibb viser innloggingssiden til nettet, uten adressefelt, og lukker den når nettet virker. Nettfilterets lister gjelder, ikke din egen DNS-tjener.» - Rules 1, 4 - Proposal: header «Wi-Fi», switch «Innloggingsside», sub behind an (i) - [words]
3. **Grant button's note + footer limits** - keys `logins.wifi.grant_sub`, `logins.wifi.footer` - Today: «Virker når telefonen har nett, for eksempel mobildata. Én innlogging på 10 minutter uten kode, høyst 3 om dagen.» and footer «Hver innlogging varer høyst 10 minutter. Uten kode høyst 4 innlogginger og 20 minutter om dagen.» - Rule 2 - Proposal: both into one (i) - [words]
4. **Code row says how to change it** - keys `logins.wifi.code_on`, `code_off` - Today: «Krever foreldrekode – endres under Avansert» - Rule 3 - Proposal: link row «Foreldrekode · På/Av ›» - [words]
5. **Chrome switch sub + label repeats header** - keys `logins.apps.header`, `logins.apps.label`, `logins.apps.sub` - Today: header «Innlogging i apper», switch «Innlogging i apper med Chrome», sub «Apper kan åpne innloggingssider i Chrome. Chrome vises ikke på telefonen og åpner bare sidene under.» - Rules 1, 4 - Proposal: switch «Chrome», sub behind an (i) - [words]
6. **"På telefonen · Klar" when all is well** - key `logins.apps.state` + `.on` - Rule 5 - Proposal: show the row only for `waiting`/`absent`/`failed`, and those in orange - [state logic: the `apps_state` builder in the logins handler]
7. **Chrome-clear footer shown always** - key `logins.apps.clear` - Today: «Når du slår dette på, slettes det som er lagret i Chrome på telefonen (historikk og innlogginger).» - Rule 4/5 - Proposal: show it only while the switch is off. After turning on it says nothing new - [state logic] - (KEEP: data deletion on turning on.)
8. **Chrome-ticked note** - key `logins.apps.ticked` - Today: «Chrome er tillatt som vanlig app. Fjern haken under Apper for å bruke den bare til innlogging.» - Rule 5 - Proposal: orange «Chrome er tillatt som vanlig app», with the fix behind an (i) - [words]
9. **Today line wording** - key `logins.wifi.today` - Today: «I dag uten kode: 2 av 4 innlogginger, 12 av 20 minutter» - Rule 3 (mild) - Proposal: a value row «I dag uten kode · 2 av 4 · 12 av 20 min» - [words]
Summary: 9 findings, size M

## Telefon › Kalender (`device_calendar.html`)
1. **"Hentes av serveren": footer + (i) of ~200 words** - keys `calendar.sources.footer`, `sources.info`, `sources.info_privacy` - Today: «Kalendere fra en lenke eller en CalDAV-konto. Serveren henter dem hver halvtime, og telefonen viser dem i Kalender.» - Rules 4, 2 - Proposal: drop the footer sentence and keep the (i) alone. Trim `sources.info` (the app-password advice belongs on the Add page's field) - [words] - (KEEP `info_privacy` behind the (i): what the server stores and that passwords are sealed.)
2. **Setup footer, 60 words, with a stale name** - key `calendar.calendars.setup` - Today: «Lim inn lenken til kalenderen under Kalenderlenker over - det er alt. Eller med en synkroniseringsapp: … ICSx5 … DAVx5 …» - Rule 2 - Proposal: (i). Also «Kalenderlenker» no longer exists; the group is «Hentes av serveren» - [words, stale wording]
3. **Title rules footer repeats the privacy line a third time** - key `calendar.rules.footer` - Today: «Første regel som passer, gjelder. Telefonen bruker dem når den tegner dagen. Hendelsene i en kalenderlenke hentes av serveren; …» - Rules 4, 2 - Proposal: keep «Første regel som passer, gjelder.» (or move it to (i)) and drop the rest - [words]
4. **Lock group: two sub-lines + a footer** - keys `calendar.lock.next_sub`, `reminders_sub`, `lock.footer`, `lock.reminders` - Today: ««Neste: Fotball 17:30» under datoen», «En stille rad før hendelsen - uten lyd, eller med én varsellyd når den kommer», footer «Alle i nærheten av telefonen kan se låseskjermen.»; label «Påminnelser på låseskjermen» under header «På låseskjermen» - Rules 1, 4, 2 - Proposal: drop both subs (the (i) has the example picture), rename the label «Påminnelser», and move the footer into the (i) - [words] - (KEEP the footer's privacy caution, in the (i) or as the one footer line. The user decides.)
5. **Dagen: switch sub + footer** - keys `calendar.dagen.on_home_sub`, `dagen.footer` - Today: «Av skjuler flisen, linjen på låseskjermen og påminnelsene; reglene står», «Barnets dag i Vibb One, én dag om gangen.» - Rules 1, 4 - Proposal: both into the (i), so the (i) stands alone - [words]
6. **"Påminnelser er av" repeated under every calendar** - key `calendar.calendars.reminders_off` - Today: «Påminnelser er av for denne telefonen» under each calendar's Påminnelse row - Rule 4 - Proposal: one line above the group (or disable the selects), said once - [template]
7. **No-report/unreadable/none as grey footers** - keys `calendar.calendars.no_report`, `unreadable`, `none` - Today: «Telefonen har ikke sagt hvilke kalendere den har ennå. Det gjør den i neste melding.», «Telefonen får ikke lest kalenderne sine. Vibb One trenger kalendertillatelsen, …» - Rules 3, 5 - Proposal: «Venter på telefonen»; unreadable as orange «Får ikke lest kalenderne», with the rest behind (i) - [words + presentation]
8. **Calendars footer** - key `calendar.calendars.footer` - Today: «Fra telefonens siste melding.» - Rule 2 - Proposal: into the (i) - [words]
9. **Attention subs** - keys `calendar.not_capable_sub`, `calendar.links.key_new_sub` - Today: «Det du velger her, når telefonen når Vibb One er oppdatert.», «Lim inn hver lenke som er merket nedenfor på nytt med Legg til kalender - …» - Rule 5 - Proposal: title only, with the sub (the fix) behind (i) - [words]
10. **No-key line** - key `calendar.sources.no_key` - Today: «Kalendere fra lenker og kontoer er av: serveren har ingen nøkkel å lagre dem kryptert med. Sjekk serverloggen (MUSIC_SECRET_KEY).» - Rule 5 - Proposal: orange «Av: serveren mangler nøkkel», with MUSIC_SECRET_KEY behind (i) - [words]
Summary: 10 findings, size L

## Kalender › Legg til kalender (`device_calendar_add.html`)
1. **App-password footer** - key `calendar.add.caldav_footer` - Today: «Bruk et app-passord, ikke det vanlige passordet. iCloud lager det på account.apple.com.» - Rule 2 (the label already says «App-passord») - Proposal: a field (i) on «App-passord» - [words]
2. **Link footer** - key `calendar.add.link_footer` - Today: «Lim inn lenken til en kalender. Google: bruk kalenderens hemmelige adresse i iCal-format.» - Rules 4, 2 - Proposal: drop the first sentence and put the Google hint behind a field (i) - [words]
3. **Option label explains** - key `calendar.add.kind_link` - Today: «Lenke (https eller webcal)» - Rule 1 - Proposal: «Lenke» (the placeholder shows webcal://) - [words]
Summary: 3 findings, size S

## Kalender › CalDAV-konto (`device_calendar_caldav.html`)
1. **Hosts footer always shown** - keys `calendar.caldav.hosts`, `calendar.links.status.lan_http` - Today: «Passordet sendes bare til: {hosts}» (+ «Ren http på hjemmenettet: trygt bare hvis …») - Rule 2 - Proposal: behind the Server row's (i). `lan_http` stays as one orange line when it applies - [words] - (KEEP the hosts list in the (i). It is the security disclosure for the password.)
2. **Password hint restates "(valgfritt)"** - key `calendar.caldav.new_password_hint` - Today: «La feltet stå tomt for å teste med det lagrede.» - Rule 4 - Proposal: drop it - [words]
(KEEP: `calendar.caldav.remove_confirm`.)
Summary: 2 findings, size S

## Kalender › Kalenderlenke (`device_calendar_link.html`)
1. **Footer restates the rows** - key `calendar.link.footer` - Today: «Serveren henter lenken hver halvtime. Telefonen får den ved neste synk.» (the rows show «Sist hentet»/«Neste») - Rule 4 - Proposal: drop it - [words]
2. **Remove confirm's second sentence** - key `calendar.links.remove_confirm` - Today: «Kalenderen forsvinner fra denne telefonen. Kalendere i ICSx5 blir ikke rørt.» - Rule 4 (mild) - Proposal: «Kalenderen forsvinner fra denne telefonen.» - [words] - (KEEP the confirm.)
Summary: 2 findings, size S

## Kalender › Tittelregel (`device_calendar_rule.html`, `partials/calendar_preview.html`)
1. **Preview footer, 35 words, the privacy line again** - key `calendar.preview.footer` - Today: «Lim inn en ekte tittel for å se hva telefonen vil vise. Bare det du limer inn, sjekkes: ingen hendelse forlater noen gang telefonen - med mindre …» - Rules 4, 2 - Proposal: drop it (the label is «En ekte tittel») or move it behind an (i) - [words]
2. **"Da" footer** - key `calendar.rule.then_footer` - Today: «Vis som med «Behold resten»: teksten, så tittelen uten det som passet. Uten: bare teksten. En regel på beskrivelsen kan bare skjule hendelsen.» - Rule 2 - Proposal: (i). The live preview shows the result - [words]
3. **Match footer** - key `calendar.rule.match_footer` - Today: «Store bokstaver og mellomrom spiller ingen rolle: «Romerriket» finner også «R omerriket».» - Rule 2 - Proposal: an (i) on «Tekst» - [words]
(KEEP: `calendar.rule.delete_confirm`.)
Summary: 3 findings, size S

## Telefon › Musikk (`device_music.html`)
1. **A footer under every group** - `device.music.app_footer`, `library_footer.*`, `mobile_footer`, `storage_footer`, `storytel_footer`, `spotify_footer`, `no_speakers_footer`, `add_to_catalog` - Today: «Installeres som alle andre apper fra katalogen. Slås den av, avinstalleres appen.» / «Legg til podkaster, NRK-serier og egne filer under Musikk.» / «Aldri ved roaming. På Wi-Fi virker begge alltid.» / «Avspilte episoder utover dem som beholdes, fjernes først.» / «Gir musikkappen familiens innlogging. Slås den av, fjernes den fra telefonen.» / «Én konto per telefon: barnets egen Premium-konto, aldri din.» / «Til da kan telefonen spille på alle Sonos-høyttalere den finner, også hos venner.» / «Legg den til i katalogen under Musikk › Innstillinger først.» - Rule 2/3/4 - Proposal: drop `library_footer` and `add_to_catalog` (the row/link says it); fold the others into one (i) per group (mobile and storage already have one: merge into it). KEEP the consequences «Slås den av, avinstalleres appen» and «fjernes den fra telefonen» inside the (i). - [words]
2. **Warnings show the fix as visible text** - `partials/note_warning.html` via `handlers/music.rs` (`music_warnings`, spotify card) / `device.music.unreachable_sub`, `cant_deliver_sub`, `older_phone_sub`, `over_limit_sub`, `storytel_refused_sub`, `device.music.spotify.*_sub`, `log_in_again`, `log_in_before`, `shared_*_sub`, `refused_sub`, `not_premium_sub` - Today e.g.: «Over lagringsgrensen» + «Episoder og bøker som beholdes, bruker alene {used} av grensen på {gb} GB. Senk hvor mye et innslag laster ned, eller hev grensen.» - Rule 5 - Proposal: orange title only; sub behind the (i) (the partial change above). - [words/template]
3. **Spotify login date always under "Spiller som"** - `handlers/music.rs` (spotify card `login`) / `device.music.spotify.login_line` - Today: «Logg inn igjen før 3. nov.» in the fine state - Rule 5 - Proposal: `login = None` until the 14-day warning (`spotify.ends`) applies. - [state logic: music.rs spotify binding card]
4. **Sonos state footer** - `device.music.sonos_all`, `sonos_some`, `sonos_none` - Today: «På alle Wi-Fi-nett, også venners høyttalere.» / «Bare rommene som er slått på.» / «Ingen rom på: musikken spilles bare på telefonen og hodetelefoner.» - Rule 4/5 - Proposal: drop `sonos_some` (repeats the switches); `sonos_all` into the (i); `sonos_none` → «Ingen rom på». - [state logic: template branch]
5. **"Not sent" lines are not orange** - `device.music.storytel_unsent.*`, `device.music.spotify.unsent*` - Today: «2 Storytel-innslag sendes ikke» / «De 3 Spotify-innslagene sendes ikke.» as plain grey sub-lines - Rule 5 - Proposal: orange («sendes ikke»), short, why in (i). - [template class]
6. **Storytel none footer** - `device.music.storytel_none.*` - Today: «Ingen innlogging er lagret ennå: legg den til under Musikk › Innstillinger.» - Rule 3 - Proposal: quiet value «Ingen innlogging» on the switch row, linking there. - [words]

Summary: 6 findings, size M

# Locate (Finn)

## Finn (`device_locate.html`)
1. **Location-off attention, three sentences** - keys `locate.off.title`, `off.sub`, `off.zones`, `off.choose` - Today: «Posisjon er av for denne telefonen» + «Den tar ingen posisjon, heller ikke når du spør. Sonene kan ikke se hvor telefonen er. Velg «Bare når du spør» under Posisjonsinnstillinger.» - Rule 5 - Proposal: one orange line «Posisjon er av» linking to Posisjonsinnstillinger, with the rest behind (i) - [words]
2. **"Alle" footer explains the legend** - key `locate.all.footer` - Today: «En posisjon er ny så lenge telefonen holder sin egen innstilling: to ganger intervallet ved «Jevnlig», en time ellers.» - Rule 2 - Proposal: an (i) on the legend - [words]
3. **Mock row's sub-line** - key `locate.mock.sub` - Today: «En app på telefonen lot som den var et annet sted. Vibb One holdt dem utenfor kartet og sonene.» - Rules 1, 5 - Proposal: the row «{n} oppdiktede posisjoner ignorert» in orange, with the sub behind (i) - [words]
4. **Wait line** - key `locate.wait.no_answer` - Today: «Ikke noe svar ennå. Telefonen kan være frakoblet; den får forespørselen hvis den er tilbake innen en time.» - Rule 3 - Proposal: «Ikke noe svar ennå», with the rest behind (i) (or drop it; the Find footer says it) - [words]
5. **Nav row sub** - key `locate.find.sub` - Today: «Ring, lås, slett» under «Finn telefonen» - Rule 1 (mild) - Proposal: drop it - [words]
6. **No-fix sub while off** - key `locate.fix.none_off` - Today: «Ingen posisjon lagres mens posisjon er av.» - Rule 4 (the attention says it) - Proposal: drop it while `location_off` - [template]
Summary: 6 findings, size M

## Finn › Finn telefonen (`device_find.html`)
1. **Footer beside the Lock (i)** - key `locate.actions.footer` - Today: «En frakoblet telefon får Ring og Oppdater posisjon innen en time, Lås og Slett når den er tilbake.» - Rule 2 - Proposal: into the (i), so it stands alone - [words]
2. **Wipe-not-ready sub** - key `locate.wipe_not_ready.sub` - Today: «Til da avviser Android en sletting. Dette kan skje etter en oppdatering av Vibb One.» - Rule 5 - Proposal: behind (i) - [words]
(KEEP: `locate.erase.question` «… Dette kan ikke angres …» and the type-the-name confirm.)
Summary: 2 findings, size S

## Finn › Posisjonsinnstillinger (`device_location_settings.html`)
1. **Footer, two sentences, ~45 words** - keys `locate.settings.footer`, `locate.settings.last_position` - Today: «Hver posisjon bruker litt batteri; den nyeste posisjonen beholdes til en nyere kommer. Av slår av posisjon på telefonen og sletter posisjonene. En telefon som slås av …» - Rule 2 - Proposal: keep only «Av sletter posisjonene.» visible, with the rest behind (i) - [words] - (KEEP the deletion warning for «Av». It is irreversible.)
Summary: 1 finding, size S

## Finn › Posisjoner (`device_locations.html`)
1. **Footer explains a setting on another page** - key `locate.locations.footer` - Today: «Nyeste først. Behold historikk i, under Posisjonsinnstillinger i Finn, bestemmer hvor lenge de lagres; den nyeste lagres alltid.» - Rules 2, 4 - Proposal: drop «Nyeste først», with the rest behind (i) or dropped - [words]
Summary: 1 finding, size S

## Finn › Soner (`device_zones.html`)
1. **Footer, ~35 words when all is well** - `locate.rs` zones view (`footer`, `confirm_within`, `watch_line`) / keys `locate.zones.footer_watched`, `footer_interval`, `within`, `locate.watch.watching` - Today: «Telefonen følger med på sonene selv og sender en posisjon så snart den merker at den krysser en grense. Ankomster og avreiser vises etter to posisjoner på samme side. Følger med på 3 soner på telefonen.» - Rule 5 - Proposal: nothing visible when watching. Put the explanation behind one (i). `locate.watch.location_off`, `no_background`, `not_set_up` and `footer_off` become one short orange line («Telefonen kan ikke følge med på sonene»), with the reason behind (i) - [state logic: the zones view builder in `handlers/locate.rs` and `watch_line`]
2. **Location-unlocked row is a sentence + a how-to sub** - keys `locate.zones.unlocked`, `unlocked_sub` - Today: «Barnet kan slå av posisjon på telefonen; da ser sonene ingenting.» / «Hold posisjon på, under Avansert» - Rules 5, 3 - Proposal: orange «Posisjon er ikke låst ›» (the link to Avansert), with the sentence behind (i) - [words]
3. **Needs-regularly attention sub** - key `locate.zones.need_regularly_sub` - Today: «Med Bare når du spør melder telefonen en posisjon bare når du trykker Oppdater posisjon.» - Rule 5 - Proposal: behind (i). The title + «Finn jevnlig» button is enough - [words]
4. **Empty-state footer** - key `locate.zones.footer_empty` - Today: «En sone sier fra når telefonen kommer eller drar, for eksempel Hjemme eller Skolen.» - Rule 2 (mild) - Proposal: (i) or drop it - [words]
Summary: 4 findings, size M

## Finn › Sone (`device_zone.html`)
1. **Map footer** - key `locate.zone.map_footer` - Today: «Trykk på kartet for å flytte midten. En ankomst eller avreise teller etter to posisjoner på samme side, minst ett minutt fra hverandre.» - Rule 2 - Proposal: keep «Trykk på kartet for å flytte midten.» and move the second sentence behind (i) - [words]
2. **Notify footer** - key `locate.zone.notify_footer` - Today: «For alle foreldre. Hver av dere kan slå av sonevarsler under Varsle meg om.» - Rule 2 - Proposal: (i) - [words]
3. **Switch labels repeat the header** - keys `locate.zone.notify_arrive`, `notify_leave` under «Varsler» - Today: «Varsle når de kommer», «Varsle når de drar» - Rule 4 - Proposal: «Kommer», «Drar» - [words]
(KEEP: `locate.zone.delete_question`.)
Summary: 3 findings, size S

# Apps tab and catalog

## Apper (fanen, `apps_list.html`)
1. **Catalog footer** - key `apps.list.footer` - Today: «Appene her holdes oppdatert på alle telefoner som har dem.» - Rule 4 - Proposal: drop (or behind a footer (i)). - [words]
Summary: 1 finding, size S

## Legg til app (`tracked_app_add.html`)
1. **Wait field: long label + two-sentence footer** - keys `apps.add.wait`, `apps.wait.footer`, `apps.wait.first_footer` - Today: «Vent før en oppdatering kommer til telefonene» + «Nye versjoner venter så lenge før telefonene får dem, så en ødelagt eller kapret versjon kan bli oppdaget og trukket først. 0 = med en gang.» + «Første versjon venter også - …» - Rule 1, 2 - Proposal: label «Ventetid»; both footers behind an (i) beside it. - [words]
2. **Source footers** - keys `apps.add.source_footer`, `apps.add.fdroid_one_source` - Today: «Telefonene henter hver nye versjon selv.»; «F-Droid signerer apper med sin egen nøkkel: en app fra F-Droid kan ikke senere bytte til GitHub eller Play uten å installeres på nytt.» - Rule 4, 2 - Proposal: drop `source_footer`; F-Droid: «Kan ikke bytte kilde senere.» with the reason behind (i). - [words] - (KEEP: the F-Droid line warns of an irreversible choice; keep a short visible form.)
3. **Pre-releases footer** - key `apps.add.prereleases_footer` - Today: «For et repo som bare publiserer forhåndsutgivelser.» - Rule 1, 2 - Proposal: behind an (i) on the switch. - [words]
Summary: 3 findings, size S

## App i katalogen (`tracked_app_detail.html`)
1. **"About" label rows beside the (i)** - keys `apps.update.held_about`, `apps.update.wait_about`, `apps.app.switches_about` - Today: rows «Hva {version} ber om» (i), «Om ventetiden» (i), «Om disse bryterne» (i) - Rule 2 - Proposal: no label rows; the (i) sits alone on the held row, the waiting row and the Aktiv/launcher rows (panel text unchanged). - [markup + words]
2. **Switch/field sub-lines in Oppdateringer** - keys `apps.app.prereleases_sub`, `apps.app.always_ask_sub`, `apps.wait.footer` (as the wait row's sub), labels `apps.app.wait`, `apps.app.is_launcher` - Today: «For et repo som bare publiserer forhåndsutgivelser»; «Av: en oppdatering som ikke ber om noe nytt, kommer til telefonene av seg selv.»; the two-sentence wait text under «Vent før oppdateringer»; «Dette er launcher-appen» - Rule 1 - Proposal: no subs (behind one (i) per row or the group's (i)); labels «Ventetid», «Launcher». - [words]
3. **Waiting release sub restates** - keys `apps.update.published.*`, `apps.update.found.*` - Today: «Publisert {when} · oppdateringer av denne appen venter {count} timer» under «{version} kommer til telefonene {when}» - Rule 4 - Proposal: «Publisert {when}» only. - [words]
4. **F-Droid / other-repo note under Kilde, always** - keys `apps.app.fdroid_note`, `apps.app.other_repo_note` - Today: «Én kilde per app: F-Droid signerer den med sin egen nøkkel, så den kan ikke bytte til GitHub eller Play uten å installeres på nytt.» - Rule 1, 5 - Proposal: behind an (i) on the Kilde row (it is no longer a choice here). - [words + markup]
5. **Footers that explain** - keys `apps.app.launcher_footer`, `apps.app.display_footer`, `apps.app.no_phones_footer`, `apps.install.about` (+ its (i)), `apps.app.forget_footer` - Today: «Vibb One selv: på alle telefoner, og den kan ikke fjernes.»; «Telefonene bruker dette med mindre en har sitt eget.»; «Slå den på under en telefons apper for å installere den.»; ««Installer nå» installerer den nyeste utgivelsen … Den venter bare på en samtale.» + (i); «Glem dem bare ved feil app, eller når utvikleren har byttet nøkkel.» - Rule 4, 2 - Proposal: drop `launcher_footer`; `display_footer`, `no_phones_footer` and `install.about` behind (i) (install: (i) alone); `forget_footer` into the forget confirm body. - [words] - (KEEP: forget_footer's advice - it guards the signer pin - moves into the confirmation, not dropped.)
6. **Header duplicates the form** - key `apps.app.display_header` - Today: «Vis på telefonene som» above a row «Navn og ikon» - Rule 4 - Proposal: drop the header (the row names it). - [words]
7. **"Checking" sub tells how** - key `apps.app.checking_since` - Today: «Startet {when}. Last inn siden på nytt for å se resultatet.» - Rule 3 - Proposal: «Startet {when}». - [words]
8. **Package candidates note** - key `apps.app.candidates` - Today: «Utgivelsen sa ikke hvilken app det er. Vibb One installerte disse på en telefon som har den - velg den som er denne appen.» - Rule 5 - Proposal: orange «Velg hvilken app dette er», the reason behind (i). - [words + tone]
9. **No-package display note** - key `apps.app.display_no_package` - Today: «Gjelder når en utgivelse sier hvilken app det er.» - Rule 3 - Proposal: «Venter på første utgivelse». - [words]
Summary: 9 findings, size M
(KEEP everywhere on this page: `apps.app.remove_confirm`, `apps.app.forget_confirm`, `apps.app.forget_holds`, `apps.update.keeps_until` - irreversible-action confirmations and the held-update consequence.)

# Music

## Musikk (`music.html`)
1. **Library footer on every visit** - `music.html` / `music.tab.footer.*` - Today: «{count} av {max} innslag. Hver telefon får dem som er huket av for den.» - Rule 4/5 - Proposal: no footer; show «{count} av {max}» only near the cap (e.g. ≥ 90 %). - [state logic: template condition on `entry_count`/`max_entries`]
2. **Edit-mode footer** - `music.tab.edit_footer` - Today: «Rekkefølgen her er rekkefølgen på omslagene på telefonene.» - Rule 2 - Proposal: drop (or (i) beside "Ferdig"). - [words]
3. **Empty library line repeats the Add group** - `music.tab.empty` - Today: «Ingenting ennå. Legg til en podkast, en NRK-serie eller egne filer nedenfor.» - Rule 4 - Proposal: «Ingenting ennå». - [words]
4. **"Sjekket for … siden" on every card** - `partials/music_status.html` via `handlers/music.rs::SweepView::status` / `music.status.checked` - Today: «Sjekket for 2 timer siden» (+ «Sjekk nå» button) - Rule 5 (quiet value is allowed; this is the mildest item) - Proposal: on the library cards show the status only while busy or failed; keep «Sjekket …» on the entry page. - [state logic: `status()` returns no block for tone "ok" on cards]

Summary: 4 findings, size S

## Musikk › innslag (`music_entry.html`)
1. **Spotify order said up to three times** - `music_entry.html`, `handlers/music.rs` (sweep notes) / `music.card.spotify_picks`, `music.card.artist_context`, `music.spotify.context`, `music.entry.spotify_order_artist`, `music.entry.spotify_order_context` - Today: card «Spotify velger rekkefølgen», note «En artist spilles slik Spotify velger: ingen sporliste, og den starter fra Spotifys egen begynnelse hver gang.», hint «Spotify velger rekkefølgen: ingen innlogget konto får vise sporene her, så telefonene spiller det slik Spotify gjør.», and for a playlist the 55-word `music.spotify.context` («Ingen innlogget konto eier denne spillelisten …») - Rule 4/2 - Proposal: one quiet line where the order select would be: «Rekkefølge: som i Spotify» with an (i) holding the why/how (context text, artist text); drop the duplicate notes. - [state logic: which notes `SweepView` pushes for Spotify; words]
2. **Spotify streaming hint** - `music.entry.spotify_streams` - Today: «Spotify strømmes: ingenting lagres på telefonen for lytting uten nett. Det spilles på Wi-Fi, med mindre telefonen får strømme på mobildata (telefonens Musikk-side).» - Rule 2 - Proposal: move into the (i) of item 1. - [words]
3. **Spotify audiobook note** - `music.card.audiobook` - Today: «Dette er en lydbok. En lydbok fra Spotify spilles bare med lyttetid … «Audiobooks+ for Plan Members» … Uten den sier telefonen at boka trenger lyttetid.» (≈40 words) - Rule 5 - Proposal: orange «Lydbok: trenger lyttetid på barnets konto», the rest behind (i). - [words]
4. **Cover footer** - `music.entry.cover_footer` - Today: «JPEG, PNG eller WebP på opptil 10 MB, beskåret kvadratisk; ingenting skjult i bildet tas vare på.» - Rule 2 - Proposal: (i) on the "Bilde" field; the format line only shows as the refusal. - [words]
5. **Files footer** - `music.entry.files_footer` - Today: «mp3, m4a, m4b, ogg, opus, flac eller wav, opptil 1 GB hver. Hold siden åpen mens filene lastes opp.» - Rule 2 - Proposal: (i) on "Lydfiler"; «Hold siden åpen» only while an upload runs (music JS) or in the (i). - [words]
6. **Own-files footer** - `music.entry.own_footer` - Today: «Egne filer spilles i sporrekkefølge (etter filnavn når sporet mangler nummer), og alle ligger alltid på telefonen.» - Rule 2 - Proposal: (i) beside the settings header, or drop. - [words]
7. **No phones + footer** - `music.entry.no_phones`, `music.entry.no_phones_footer` - Today: «Ingen telefon har det ennå.» + «Huk det av på en telefons side (Musikk) for å sende det dit.» - Rule 3 - Proposal: keep «Ingen telefoner ennå», drop the footer (or (i)). - [words]
8. **Resume switch sub-line** - `music.entry.resume_sub` - Today: «Av: starter fra begynnelsen.» - Rule 1 - Proposal: drop. - [words]
9. **Home-network note** - `music.entry.lan` - Today: «På hjemmenettet eller tailnettet.» (own note row; the card already says «hjemmenett») - Rule 4 - Proposal: quiet value on the "Hos kilden" row («· hjemmenett») or drop. - [words]
- KEEP: `music.entry.delete_own_question`, `delete_question`, `delete_file_question` and `file_missing_keep` (deletes from server and phones).

Summary: 9 findings, size M

## Kategorier (`music_categories.html`)
1. **Footer** - `music.categories.footer` - Today: «De runde knappene under omslagene på telefonen, i denne rekkefølgen. «Alle» er alltid der.» - Rule 2 - Proposal: (i) beside the list, or drop. - [words]
2. **Header and button say the same** - `music.categories.add_title`, `music.categories.add` - Today: «Legg til en kategori» / «Legg til kategori» - Rule 4 - Proposal: header «Ny kategori» (button stays). - [words]

Summary: 2 findings, size S

## Importer fra Vibb (`music_import.html`)
1. **Footer + (i)** - `music.import.footer` - Today: «library.json fra en Vibb-boks, maks 1 MB. Ingenting lagres før du bekrefter.» - Rule 2 - Proposal: fold into the (i) (`music.import.info_*`), (i) alone on the file field. - [words]
2. **Preview summary instructs** - `music.import.summary*` - Today: «{count} innslag kan legges til, {skipped} kan ikke (grunnen står ved hvert). Fjern haken på det du ikke vil ha.» - Rule 3/4 - Proposal: «{count} kan legges til · {skipped} kan ikke». - [words]
3. **Phones header is a sentence** - `music.import.phones` - Today: «Huk dem av på disse telefonene» - Rule 1 - Proposal: «Telefoner». - [words]
4. **Size-cap footer always** - `music.import.phones_footer` - Today: «En telefon der musikkbiblioteket ville blitt større enn 3 MB, får bare det som er plass til.» - Rule 5 - Proposal: (i), or show only when a ticked phone would hit the cap. - [words, or state logic in `handlers/music_import.rs` if made conditional]
5. **No phones line** - `music.import.no_phones` - Today: «Ingen telefoner ennå - huk av innslagene på en telefons side senere.» - Rule 3 - Proposal: «Ingen telefoner ennå». - [words]

Summary: 5 findings, size S

## Musikk › Innstillinger (`music_settings.html`, `partials/spotify_setup.html`)
1. **Every Spotify account shows its login dates** - `spotify_setup.html` / `handlers/music_spotify.rs` (`account_line`, `plays_on`) / `music.setup.account_line`, `music.setup.plays_on_phones` - Today: «Logget inn 3. mai - logg inn igjen før 3. nov.» + «Spiller på Anna» under each account, above a "Spiller på" select - Rule 5/4 - Proposal: no `line` when fine (the orange `account_ends` warning already comes 14 days before); drop the `plays_on` sub (the select below shows it). - [state logic: `music_spotify.rs` account view]
2. **Storytel "accepted" status in the fine state** - `music_settings.html` / `handlers/music.rs::storytel_status` / `music.settings.storytel_accepted` - Today: «Storytel godtok innloggingen (14:02).» below «Innlogging · Lagret 3. nov.» - Rule 5 - Proposal: show nothing when accepted; only the error lines. Error lines also shorten: e.g. `storytel_refused_until` «Storytel avviste innloggingen ({when}). Ingenting prøves igjen før {until} - lagre passordet på nytt for å prøve nå.» → «Storytel avviste innloggingen» orange, the retry rule behind (i). - [state logic: `storytel_status` returns None for ok]
3. **Footers beside their (i)** - `music.settings.sweep_footer`, `music.settings.storytel_footer`, `music.setup.app_footer`, `music.setup.accounts_footer` - Today: ««Sjekk nå» på et innslag sjekker det med en gang.» / «Én innlogging for familien; den vises aldri igjen.» / «Serveren har ingen Spotify-app med seg: registrer familiens egen én gang.» / «Én konto per telefon, barnets egen - aldri din.» - Rule 2 - Proposal: fold each into its (i) panel, (i) alone by the group header. («aldri din» is guidance worth keeping inside the (i).) - [words]
4. **Music app footers** - `music.settings.app_footer`, `music.settings.app_footer_none` - Today: «Slå den på for en telefon på telefonens side.» / «Følger prosjektets music-v-utgivelser; ingenting installeres før du slår den på for en telefon.» - Rule 3 - Proposal: drop the first; second into an (i). - [words]
5. **Stale-login line with explanation** - `music.settings.stale`, `music.settings.stale_sub` - Today: «Skriv inn innloggingen på nytt» + «Den ble låst med en annen nøkkel (en gjenopprettet sikkerhetskopi eller en ny nøkkelfil), så telefonene får den ikke.» - Rule 5 - Proposal: keep the orange line, sub behind (i). - [words]
6. **Redirect URI row sub-line** - `music.setup.paste_this`, `music.setup.paste_this_env` - Today: «Lim inn nøyaktig dette under appens Redirect URIs (satt av serverens konfigurasjon):» - Rule 1/3 - Proposal: label «Redirect URI», the instruction in the setup steps (already step 2). Likewise `music.setup.from_env` sub under Client ID → quiet value «fra .env». - [words]
7. **Not-configured note repeats the open steps** - `music.setup.not_configured` - Today: «Ingen Spotify-app er satt opp på serveren ennå. Følg stegene nedenfor, og lagre så appens Client ID.» - Rule 4 - Proposal: «Ingen app ennå». - [words]
8. **Replace-accounts checkbox wording** - `music.setup.replace_accounts` - Today: «Lagrer du en annen Client ID, fjernes de innloggede kontoene nedenfor (innloggingene hører til den gamle appen).» - Rule 1 - Proposal: «Fjern kontoene (de hører til den gamle appen)». KEEP its meaning: it confirms a destructive change. - [words]
9. **Refused-app and no-redirect subs** - `music.setup.refused_sub`, `music.setup.no_redirect_sub` - Today: «Appen ble slettet, eller Spotify skrudde den av. Sjekk den på developer.spotify.com, …» / «Lagre en nedenfor.» - Rule 5 - Proposal: orange title only, fix behind (i); drop «Lagre en nedenfor.» - [words]
10. **Orphans footer** - `music.settings.orphans_footer` - Today: «Mest sannsynlig rester etter at en eldre sikkerhetskopi ble gjenopprettet.» - Rule 2 - Proposal: (i). - [words]
- KEEP: the setup steps behind "Slik setter du den opp" (`music.setup.step1`-`step6`), in particular step 5's «Spotifys Developer Policy forbyr apper «targeted to children» - å bruke dette for barnet ditt er på egen risiko» (a legal/risk notice); all remove confirms (`remove_app_*`, `remove_account_question`, `remove_login_question`, `delete_files_question`); `storytel_no_key`/`no_key` (behind the (i) already).

Summary: 10 findings, size M

## Legg til fra Spotify (`music_spotify.html`)
1. **Long context note after adding** - `music.spotify.context` - Today: «Ingen innlogget konto eier denne spillelisten eller samarbeider på den, så telefonene kan ikke vise sporene: … bli med fra barnets konto.» (55 words) - Rule 5/2 - Proposal: «Spilles i Spotifys rekkefølge» + (i) with the fix. - [words]
2. **"Not ready" said twice** - `music.spotify.no_reader`, `music.spotify.not_ready.*_sub` - Today: attention «Ingen Spotify-app er lagret - sett den opp først» + «Familiens egen Spotify-app, én gang.», then in the form «Du må sette opp Spotify først (over).» - Rule 4/5 - Proposal: drop `no_reader`; attention line only, no sub (`no_app` → «Ingen Spotify-app ennå»; `no_key_sub`, `renew_sub`, `no_account_sub` behind the row's chevron/page). - [words]
3. **Phones footer** - `music.spotify.phones_footer` - Today: «Bare telefoner som spiller som en Spotify-konto, får det (Musikk › Innstillinger, eller hver telefons Musikk-side).» - Rule 2/4 (each row already says «Spotify er av på denne telefonen») - Proposal: drop. - [words]
4. **Setup footer repeats the row** - `music.spotify.setup_footer` - Today: «Familiens egen Spotify-app og hvert barns konto, under Musikk › Innstillinger.» - Rule 4 - Proposal: drop. - [words]
5. **Per-phone sub in the fine state** - `music.plays_as` - Today: «Spiller som Anna» under each phone checkbox - Rule 5 (mild) - Proposal: show only «Spotify er av» (orange, disabled row) when off. - [template condition]
6. **Header repeats the page title** - `music.spotify.add_link` - Today: «Legg til en Spotify-lenke» under page «Legg til fra Spotify» - Rule 4 - Proposal: drop the header (the field says «Spotify-lenke»). - [words]
7. **Added flash instructs** - `music.spotify.added.none` - Today: «Slå det på for en telefon på telefonens Musikk-side.» - Rule 3 - Proposal: «Lagt til {name}.» only. - [words]

Summary: 7 findings, size S

## Spotify-innlogging ferdig (`music_spotify_done.html`)
Complies («Tilbake til Spotify-siden.»).

Summary: 0 findings, size S

## Storytel-hylla (`music_storytel.html`)
1. **Intro sentence beside the (i) at the top** - `music.storytel.intro` - Today: «Legg til en serie eller en bok, og huk den av på en telefons side.» - Rule 2/3 - Proposal: drop the sentence, (i) alone by «På hylla». - [words]
2. **Add footer** - `music.storytel.add_footer` - Today: «Hver legges til med alle bøkene, eldste først, og fortsetter der den stoppet - endre det på siden dens.» - Rule 2 - Proposal: into the same (i). - [words]
3. **Added flash instructs** - `music.storytel.added.*` - Today: «Lagt til 2 - slå dem på for en telefon på telefonens Musikk-side.» - Rule 3 - Proposal: «Lagt til 2». - [words]
4. **No-login row sub** - `music.storytel.no_login_sub` - Today: «Lagre familiens innlogging først.» - Rule 5 - Proposal: drop (the row links to the login). - [words]
5. **Empty shelf** - `music.storytel.empty` - Today: «Ingenting på Storytel-hylla ennå. Bøker du legger på hylla i Storytel-appen, vises her.» - Rule 3 - Proposal: «Hylla er tom», the how behind (i). - [words]

Summary: 5 findings, size S

# Settings and its sub-pages

## Innstillinger (`settings_hub.html`)
Complies: rows with quiet values only.

Summary: 0 findings, size S

## Varselinnstillinger (`notifications_settings.html`)
1. **Events footer** - `settings.notifications.events_footer` - Today: «Bare for deg - hver forelder velger selv. Av: fortsatt i innboksen, men uten varsel. Hendelser kommer så snart telefonen melder seg neste gang.» - Rule 2 - Proposal: (i) alone by «Varsle meg om». - [words]
2. **Device row sub in the fine state** - `handlers/notifications.rs::device_row` / `settings.notifications.row.since_opened`, `row.not_opened` - Today: «Sist vist 14:02 · 3 siden sist et ble åpnet» - Rule 5 - Proposal: fine state: «Sist vist 14:02» (quiet) or nothing; drop the opened count. - [state logic: `device_row`]
3. **Getting-notifications footer** - `settings.notifications.shown_footer`, `settings.notifications.added_again` - Today: «Vist: enheten bekreftet at den viste varselet. Lagt appen på Hjem-skjermen på nytt? Slå på varsler i den nye kopien, og fjern den gamle enheten her.» - Rule 2/5 - Proposal: `shown_footer` into an (i); `added_again` only while a device is `MayNotShow`/`Gone`. - [state logic: template condition on device health]
4. **How-to notes for this device** - `settings.notifications.home_screen`, `settings.notifications.blocked` - Today: «Trykk Del i Safari og så Legg til på Hjem-skjerm. Åpne Vibb Phone fra Hjem-skjermen og slå på varsler der.» / «Tillat varsler for Vibb Phone i innstillingene på denne enheten, og kom så tilbake hit.» - Rule 3 - Proposal: the row value already says «Legg den på Hjem-skjermen først» / «Blokkert»; the steps behind an (i) on that row. - [words; `static/notifications.js` shows them]
5. **Clock warning sub** - `settings.notifications.clock_warning_sub` - Today: «Uten riktig tid kommer ingen varsler fram. Pi-en har ingen egen klokke: den trenger internett (NTP) etter et strømbrudd.» - Rule 5 - Proposal: (i). Words also out of date: the server is a VM, not a Pi («Serveren trenger riktig tid (NTP).»). - [words]
6. **Footers for this device** - `settings.notifications.this_device_footer`, `needs_js_footer` - Today: «Hver enhet slår på varsler for seg selv.» / «Varsler trenger JavaScript.» (the row value already says «Trenger JavaScript») - Rule 4 - Proposal: drop both. - [words]
7. **No devices line** - `settings.notifications.no_devices` - Today: «Ingen enhet får varsler ennå. Slå dem på under Denne enheten, på hver enhet du vil ha dem på.» - Rule 3 - Proposal: «Ingen ennå». - [words]
8. **No-key note** - `settings.notifications.no_key` - Today: «Varsler er av på denne serveren: nøkkelen kunne ikke leses. Serverloggen sier hvilken fil.» - Rule 5 - Proposal: orange «Varsler er av på serveren», the rest behind (i). - [words]
- KEEP: `settings.notifications.remove_confirm` (first sentence is the confirm).

Summary: 8 findings, size M

## Tidsregler, Innstillinger (`schedules.html`)
1. **Rules footer** - l.29 / `settings.time_rules.rules_footer` «Hver regel er på for alle telefoner til den slås av på telefonens Tidsregler-side.» - Rule 2 - Proposal: behind (i) on the header (the rows already say «Av for {names}») - [words].
2. **Emergency footer under the phone list** - l.56 / `device.time.emergency` «Nødanrop virker alltid.» - Rule 4 (unrelated to the group; said on every time page) - Proposal: drop here; keep in the rules (i) - [words].
Summary: 2 findings, size S

## Regel (`schedule_rule.html`)
1. **«Når» footer** - l.74 / `settings.time_rules.when_footer` «Etter telefonens klokke, fra start til slutt samme dag; en slutt på 00:00 er midnatt, og 00:00-23:59 er hele dagen. La en dag stå tom når regelen er av. En natt over midnatt hører hjemme under Leggetid, …» (45 words) - Rule 2 - Proposal: behind an (i) on «Når»; the refusals already say «For en natt, bruk Leggetid» - [words].
2. **«Stille» sub-line** - l.54 / `time_rules.silent_sub` «Ingen lyder, varsler, musikk eller alarmer mens regelen er på. Anrop som regelen tillater, ringer fortsatt.» (and `device.bedtime.silent_sub` for a bedtime kind) - Rule 1 - Proposal: behind an (i) on the row - [words] - (KEEP «alarmer ringer ikke» in the (i): a parent must be able to find it.)
3. **Rule footer** - l.58 / `settings.time_rules.rule_footer` «Telefonen er låst mens regelen gjelder. Nødanrop virker alltid.» - Rule 4 - Proposal: into the (i) - [words].
4. **Corrupt alarm's how-to** - l.29 / `settings.time_rules.corrupt_sub` «Telefonene får en feil i stedet for reglene sine. {parts}, så de vises tomme her …» - Rule 5 - Proposal: sub «{parts}»; rest behind (i) - [words].
Summary: 4 findings, size S

## Daglig grense (`schedule_budget.html`)
1. **Footer sentence + (i)** - l.27 / `settings.time_rules.budget_footer` «Minutter per dag. Tomt betyr ingen grense. En telefon med egne regler har sin egen grense, på telefonens Tidsregler-side.» - Rule 2, 4 (unit and placeholder already say the first two) - Proposal: the (i) alone; its text gains the own-limit sentence - [words].
2. **Save flash explains sync** - l.25 / `settings.time_rules.budget_saved` «Lagret. Telefoner som følger reglene for alle, får det i løpet av sekunder.» - Rule 4 - Proposal: «Lagret.» - [words].
3. **Header repeats the title** - l.18 / `settings.time_rules.everyone_screen_time` «Alle · Skjermtid» under the band «Daglig grense» - Rule 4 - Proposal: no header, or «Alle telefoner» - [words].
Summary: 3 findings, size S

## Innstillinger › Nettfilter (`dns_filter.html`)
1. **Lists footer, 45 words** - key `settings.web_filter.lists_footer` - Today: «En telefons egen nettfilterside kan stille en liste annerledes … Firefox og Cromite kan fortsatt stilles inn manuelt på en annen sikker DNS-server.» - Rule 2 - Proposal: behind an (i) alone - [words] - (KEEP the Firefox/Cromite bypass caveat inside the (i). The per-phone `browser.*` lines cover the live case.)
2. **Every list row has two sub-lines, one restating its switch** - `dns_filter::list_scope` / `settings.web_filter.scope.on_all`, `off_all` - Today: «På for alle telefoner» under a switch that is on, plus «12 345 domener» - Rules 1, 4 - Proposal: show the scope only for an exception («Unntatt {names}», «På for {names}»), and keep the count as the only sub-line - [state logic: `list_scope` returns None for `on_all`/`off_all`]
3. **"Hva skjedde" label row beside the (i)** - keys `settings.web_filter.what_happened`, `problem_label` - Today: a separate row «Hva skjedde (i)» under a failing list - Rule 2 - Proposal: put the (i) on the orange sub-line itself («Siste nedlasting feilet (i)») and drop the row - [words, template]
4. **Per-phone DNS lines that say all is well** - `dns_filter::phone_line` / `settings.web_filter.line.custom_in_use` - Today: «{name} · Serveren din er i bruk» for every phone, under the footer - Rule 5 - Proposal: no line when the phone uses what was chosen. `backup`, `custom_down`, `provider_down`, `update_for_custom` and `filter_off` become short orange lines, with the reasons (`backup_reason.*`) behind an (i) - [state logic: `phone_line` returns None for in-use]
5. **Bypass lines are grey footer bullets** - `dns_filter::bypass_lines` / `settings.web_filter.browser.*`, `line.update_for_bypass` - Today: «Firefox: nettleserens egen sikre DNS kan gå utenom filteret» in grey under «Hver telefon» - Rule 5 - Proposal: make it an orange line «{name}: Firefox kan gå utenom filteret», with the fix behind an (i) - [words + presentation]
6. **Stale-server footer** - key `settings.web_filter.stale` - Today: «De lagrede adressene godtas ikke lenger: skriv dem inn igjen. Til da bruker telefonene Cloudflare.» in grey, in place of the footer - Rule 5 - Proposal: one orange line «Egen server må skrives inn igjen», with the rest behind (i) - [words + presentation]
7. **DNS server footer** - key `settings.web_filter.server_footer` - Today: «Hvor telefonene slår opp nettsteder som ikke er blokkert, kryptert.» - Rule 2 - Proposal: into the existing (i) (`server_info`) - [words]
8. **Backup field hints** - keys `settings.web_filter.backup_hint` (≈30 words), `backup_ip` - Today: «Svarer mens serveren din ikke kan nås. https://1.1.1.3/dns-query er Cloudflare for Families …» - Rule 2 - Proposal: a field (i) (`details.info.field-info`) on «Reserveserver». Keep `backup_ip` as one hint line only when it applies - [words]
9. **Built-in DoH row's sub-line** - key `settings.web_filter.doh_servers_sub` - Today: «Bygd inn i Vibb One, på sammen med filteret» - Rule 1 - Proposal: the value «Alltid» or «Blokkert», with the explanation behind the row's (i) - [words]
10. **Blocked footer restates the header** - key `settings.web_filter.blocked_footer` - Today: «Blokkert på alle telefoner, også når ingen liste har det.» - Rule 4 - Proposal: drop it - [words]
11. **Allowed footer** - key `device.filter.allowed_footer` (same as the device page) - Rule 2 - Proposal: (i) - [words]
12. **"Hver telefon" footer** - key `settings.web_filter.each_phone_footer` - Today: «En liste som er av for én telefon, telefonens egne domener og loggen.» - Rule 2 - Proposal: drop it (the rows are links) or move it behind an (i) - [words]
13. **List URL hint** - key `settings.web_filter.list_hint` - Today: «En liste over domener, ett per linje, som i en hosts-fil.» - Rule 2 - Proposal: a field (i) - [words]
14. **Custom server: "set/not set" said twice** - keys `settings.web_filter.not_set` (row sub), `settings.hint_set`/`hint_not_set` (field hint) - Today: «Egen server / Ikke satt», then the field hint «Ikke satt.» - Rule 4 - Proposal: keep the row sub and drop the field hint - [words]
Summary: 14 findings, size M

## Innstillinger › Blokkert aktivitet (`dns_log.html`)
1. **Footer restates the rows** - key `settings.dns_log.footer` - Today: «Loggen er av til du slår den på for en telefon. Lagres i 7 dager.» - Rules 4, 2 - Proposal: drop the first sentence (each row shows Av/På), and move «Lagres i 7 dager» behind an (i) or drop it - [words]
2. **Page duplicates Nettfilter › Hver telefon** - `dns_log.html` / `settings_hub.html` row - Rule 4 (say it once) - Proposal: consider dropping the hub row. Both lists link to `/devices/{id}/filter` - [structure, optional]
Summary: 2 findings, size S

## Bakgrunnsbilder (`wallpapers.html`)
1. **Add footer beside (i)** - `settings.wallpapers.add_footer` - Today: «Et nytt bilde er ikke på noen telefon før du huker det av på telefonens Hjem-skjerm.» - Rule 2/3 - Proposal: into the (i) (`settings.wallpapers.info`), (i) alone. - [words]
2. **Lock-screen switch sub-line** - `settings.wallpapers.lock_screen_sub` - Today: «Alle som holder den låste telefonen, ser det.» - Rule 1 - Proposal: behind the (i). KEEP the meaning (a privacy point) in the (i). - [words]
3. **List footer** - `settings.wallpapers.all_footer` - Today: «Velg hvilke en telefon kan bruke på Hjem-skjermen sin.» - Rule 3 - Proposal: drop. - [words]
4. **Name hint** - `settings.wallpapers.name_hint` - Today: «Leses opp for barnet ditt.» - Rule 2 - Proposal: (i). - [words]
5. **Header repeats the page** - `settings.wallpapers.all` - Today: «Alle bakgrunnsbilder» under «Bakgrunnsbilder» - Rule 4 - Proposal: «Bilder» or no header. - [words]
- KEEP: `settings.wallpapers.delete_confirm`.

Summary: 5 findings, size S

## Telefonoppsett (`provisioning_settings.html`, `partials/launcher_check.html`)
1. **Launcher warnings carry the full fix** - `launcher_check.html` / `settings.provisioning.launcher.older_sub`, `wrong_signer_sub`, `wrong_package_sub`, `address_sub` - Today e.g.: «Fra denne serveren: {url}. En telefon ville stoppet ved «Can't set up device». Sjekk LAUNCHER_APK_URL i serverens .env (DEPLOY.md) og serverens internettforbindelse, og trykk så Sjekk på nytt. Ingen QR-kode før da.» - Rule 5 - Proposal: title + «Ingen QR-kode før det er rettet.» visible; .env names and steps behind (i). KEEP: that a phone would refuse and no QR is shown (safety). - [words]
2. **Server address hints** - `settings.provisioning.server_url_hint`, `from_env`, `saved_wins` - Today: «Https-adressen til denne serveren, slik telefonene når den (vanlig http bare til en Tailscale-IP-adresse).» / «Fra .env (PHONE_SERVER_URL): {url}. En adresse som lagres her, går foran.» / «Lagret her; den går foran {url} fra .env. Tøm feltet for å bruke den.» - Rule 2/3 - Proposal: an (i) on the field with these; visible only a quiet «Fra .env» when it applies. - [words]
3. **Key hint always** - `settings.provisioning.key_hint` - Today: «Lag en ny engangsnøkkel før hvert telefonoppsett.» - Rule 2 - Proposal: into `key_info`. - [words]
4. **Saved flashes explain** - `settings.provisioning.saved`, `country_code_saved` - Today: «Lagret. Telefoner som settes opp fra nå av, får disse.» / «Lagret. Hver telefon får den neste gang den er på nett.» - Rule 4 - Proposal: «Lagret». - [words]
5. **Footers** - `settings.provisioning.footer`, `numbers_footer` - Today: «QR-koden for oppsett tar disse med til hver nye telefon.» / «For alle telefoner. Numre som allerede er lagret, beholder sin.» - Rule 2 - Proposal: (i) by the header, or drop the first. - [words]
6. **Country-code hint** - `settings.provisioning.country_code_hint` - Today: «Et nummer som legges til uten landskode, får +{code}.» - Rule 2 - Proposal: (i). - [words]
7. **Android-version line always** - `settings.provisioning.launcher.needs_android`, `needs_api` - Today: «En ny telefon må ha Android {version} eller nyere: oppdater den før tilbakestillingen.» - Rule 5 - Proposal: behind an (i) on the launcher row. - [words]

Summary: 7 findings, size M

## App-push (`settings_push.html`) - only what design 112 misses under today's rules

112 predates the "Words in the UI" section by hours; four points no longer fit it:

1. **The visible footer sentence beside ⓘ2** - 112's `app_push.settings.footer` «Varsler fra apper som Element X går gjennom Vibb One og denne serveren, uten Google.» - Rule 2 (explanation goes behind the (i) alone) - Proposal: no footer; ⓘ2 sits on the band/group header alone with the sentence as its first line - [words]
2. **The field's sub-line** - 112's `origin_hint` «Må kunne nås fra internett.» - Rule 1/2 (explains the field; ⓘ1 on the label already says it) - Proposal: no sub-line; the placeholder shows the form, ⓘ1 says why. Prefilled/refused/old-address lines stay (states) - [words]
3. **The how-to under Save** - 112's `clear_note` «Tøm feltet og lagre for å slå av for alle telefonene.» - Rule 3 - Proposal: behind ⓘ1/ⓘ2. Keep `save_note` (not-set state) as one short line «Slås på for alle telefonene.» - (KEEP: it says a save acts on every phone at once) - [words]
4. **"Sist sett utenfra" shown when fine** - 112's single row with a time - Rule 5 - acceptable as a quiet value; but «Ikke ennå» after a registration exists is the wrong state and should be one orange line «Ikke nådd fra internett ennå» (fix behind ⓘ3) - [state logic: `app_push::render_settings`, orange when there are registrations but no discovery/push]

Also: 112 keeps `no_listener` with the env var and DEPLOY.md reference in the visible line; under rule 5 it is the right kind of line (the server can't serve app push), but trim to «App-push virker ikke: serveren har ingen lytter for telefonene», the DEVICE_BIND_ADDR/DEPLOY.md pointer behind an (i).

Summary: 5 findings, size S (on top of 112)

## Sikkerhetskopier (`backups.html`)
1. **Footer beside (i)** - `settings.backups.footer` - Today: «Ha en kopi et annet sted også: last ned en nå og da.» - Rule 2 - Proposal: into the (i). KEEP `info_holds` («inneholder alt, også tofaktorhemmeligheten …») in the (i): a security warning. - [words]
2. **Schedule day fields both shown, labels in parentheses** - `settings.schedule.day_of_week`, `settings.schedule.day_of_month` - Today: «Dag (hver uke)», «Dag i måneden (hver måned, 1-28)» shown together whatever is picked - Rule 1 - Proposal: show only the field the frequency needs, label «Dag». - [state logic: template/JS on `frequency`]
3. **Schedule footers** - `settings.backups.schedule_footer_docker`, `schedule_footer` - Today: «Sikkerhetskopiene blir liggende i datavolumet: kopier dem selv av verten.» / «Etter klokken på serveren.» - Rule 2 - Proposal: (i). - [words]
4. **Upload footer** - `settings.backups.upload_footer` - Today: «En du har lastet ned tidligere.» - Rule 4 - Proposal: drop. - [words]
5. **Keep hint** - `settings.backups.keep_hint` - Today: «Eldre slettes. 0 beholder alle.» - Rule 2 - Proposal: (i) (minor). - [words]
- KEEP: `settings.backups.restore_confirm` + `backups.restore.accounts`, the typed-name check, `restore_ssh`, `delete_confirm` (irreversible).

Summary: 5 findings, size S

## Oppdateringer (`updates.html`)
1. **"Slik oppdaterer du" as a label row with (i)** - `settings.updates.how` - Today: a row «Slik oppdaterer du ⓘ» always - Rule 2 - Proposal: no label row; show the (i) on the «Versjon … er ute» row only when a newer version is out (the whole row opens). - [state logic: template]
2. **"Up to date" as its own row** - `settings.updates.up_to_date` - Today: «Du har nyeste versjon» - Rule 5 - Proposal: nothing (the Version row is the quiet value); only «Versjon {version} er ute» when there is one. - [state logic: template]
3. **Footer** - `settings.updates.footer` - Today: «Vibb Phone endrer aldri noe på maskinen den kjører på: å oppdatere den, systemet og Tailscale er opp til deg.» - Rule 2 - Proposal: into the (i). - [words]
4. **Timing footer** - `settings.updates.timing_footer` - Today: «Bare på denne enheten: en linje øverst på hver side med hvor lang tid den brukte.» - Rule 2 - Proposal: (i). - [words]

Summary: 4 findings, size S

# Notifications, Account, Security log, sign-in

## Varsler, innboks (`notifications.html`)
1. **Delivery line under every event** - `handlers/notifications.rs::delivery_line` / `settings.notifications.delivery.*` - Today: «Vist på iPhone · Safari», «Åpnet på …», «Levert til …» on each row - Rule 5 - Proposal: show only when it went wrong: «Ikke sendt», «Sendt til {label}, ikke bekreftet», «Sendes»; nothing for opened/shown/delivered/summary. - [state logic: `delivery_line` returns None for the good states]
2. **Footer** - `settings.notifications.footer` - Today: «Nyeste først, tas vare på i 90 dager.» - Rule 4 - Proposal: drop (or the 90 days behind an (i)). - [words]
3. **Empty line** - `settings.notifications.empty` - Today: «Ingenting ennå - det telefonene melder, kommer her» - Rule 4 - Proposal: «Ingenting ennå». - [words]
4. **Warning rows carry a how-to sub** - `handlers/notifications.rs::warning_of` / `devices.warning.push_gone_sub`, failure text - Today: «Varsler stoppet på {label}» + «Slå dem på igjen på den enheten, eller fjern den under Varsler» - Rule 5 - Proposal: title only (the row links to the fix). - [words]

Summary: 4 findings, size S

## Konto og foreldre (`account.html`, partials `passkey_confirm`, `passkeys_still_work`, `reauth_passkey`)
1. **Footers beside (i) or explaining** - `account.totp.footer`, `account.password.off_footer`, `passkeys.footer`, `passkeys.footer_host`, `passkeys.only.footer_last`, `lock.footer`, `account.sessions.footer`, `account.parents.footer`, `account.language.footer` - Today: «Låst ute og logget ut? Det kan tilbakestilles på serveren.» / «Kun passkey er på: du logger inn med passkey. Låst ute? …» / «Logg inn med Face ID, fingeravtrykk eller en sikkerhetsnøkkel i stedet for passord og kode. Passkeys du legger til her virker bare på {host}. …» / «Den siste passkeyen kan ikke fjernes mens «Kun passkey» er på. Slå på passord først.» / «Appen låses når den har vært ubrukt så lenge.» / «For en mistet telefon eller datamaskin som fortsatt er logget inn.» / «Hver forelder logger inn med sitt eget passord …, og styrer alle telefonene. Opptil 8.» / «Språket for kontoen din. Hver forelder velger sitt eget.» - Rule 2/4 - Proposal: drop `off_footer` (the row says «Av»), `footer_last` (the confirm says it), `lock.footer`, `language.footer`; the rest into (i)s by their headers (TOTP's server (i) stays, alone). KEEP `footer_host`'s fact (passkeys only work on {host}) inside the (i). - [words]
2. **Name hint (≈40 words)** - `account.name.hint` - Today: «Det de andre foreldrene ser, for eksempel «Mamma». Tomt: {username}. Navnet vises også på telefonen når du svarer på en forespørsel …» - Rule 2 - Proposal: «For eksempel «Mamma»», the rest behind (i). - [words]
3. **Sub-lines under switches** - `passkeys.only.sub`, `lock.sub` - Today: «Ingen passord eller kode – bare passkey» under «Kun passkey»; «Gjelder denne enheten. Lås opp med Face ID, fingeravtrykk eller telefonens kode.» under «Lås med Face ID» - Rule 1 - Proposal: drop `only.sub` (the confirm says it); `lock.sub` behind (i). The disabled rows' reasons (`passkeys.only.add_first`, `lock.add_first`, `passkeys.not_here`) stay. - [words]
4. **Add-parent hints** - `account.add.username_hint`, `account.add.name_hint`, `account.add.password_hint` - Today: «For innlogging, for eksempel «pappa»: små bokstaver, tall, . _ eller -.» / «Det dere alle ser, for eksempel «Pappa». Den nye forelderen kan endre det selv.» / «Det du logger inn her med. Du får et engangspassord å gi dem; tre feil passord logger deg ut.» - Rule 2 - Proposal: «Små bokstaver, tall, . _ -» / «For eksempel «Pappa»» / «Tre feil logger deg ut.» KEEP the three-wrong-passwords warning. - [words]
5. **Passkeys-still-work line under every password change** - `partials/passkeys_still_work.html` / `passkeys.still_work.*` - Today: «Passkeyene dine virker fortsatt: X, Y – fjern dem under Passkeys om en er på avveie.» - Rule 5 - Proposal: show it in the flash after a change (and in Reset 2FA's confirm), not under the open form. KEEP the content (security). - [state logic: template]
6. **Two-factor header repeated in its row** - `account.totp.title`, `account.totp.row` - Today: header «Tofaktorinnlogging», row «Tofaktorinnlogging · På» - Rule 4 - Proposal: row label «Status», or the value in the header row. - [words]
7. **Passkey-only confirm: last sentence** - `passkeys.only.any_time` - Today: «Du kan slå på passord igjen når du vil.» - Rule 4 - Proposal: drop. KEEP `only.what`, `warn_one*`, `way_back`, `hosts` (lock-out risk). - [words]
8. **Password footer** - `account.password.footer` - Today: «Et nytt passord logger ut alle andre nettlesere og telefoner og slår av varsler på alle andre enheter.» - Rule 1 (long) - Proposal: «Logger ut alle andre enheter.» KEEP the consequence. - [words]
- KEEP: `account.warning.*` (security alarms: someone knows the password), `account.otp.hint` (shown once), every reset/remove/withdraw/sign-out confirm, `account.totp.reset_confirm`, `account.password.new_hint` («Minst 12 tegn.»).

Summary: 8 findings, size M

## Sikkerhetslogg (`security.html`)
1. **Retention footer** - `security_log.activity.footer` - Today: «200 per side, nyeste først, lagret i 180 dager; det en telefon rapporterte i 30 dager, bruk av foreldre-PIN i 90 og navnet som ble skrevet ved en feil innlogging i ett døgn.» - Rule 2 - Proposal: (i) by the header. - [words]
2. **Banned group shown when empty** - `security_log.banned.none`, `security_log.banned.footer` - Today: «Ingen adresser er sperret.» + «For mange feil passord sperrer en adresse en stund.» - Rule 5 - Proposal: the group only when an address is banned; footer into an (i). - [state logic: template on `bans`]
3. **Exact time as a second sub-line** - `security.html` (`e.exact`) - Today: an event row with sub + exact time line + «when» value - Rule 4 - Proposal: exact time in `title` on the value, as `info_row` does. - [template]

Summary: 3 findings, size S

## Innlogging og passordsider (`login.html`, `change_password.html`, `password_sign_in.html`, `setup_2fa.html`, `verify_2fa.html`)
1. **Lede repeats the groups below** - `password_sign_in.html` / `password_on.lede` - Today: «Velg et nytt passord, sett opp tofaktorinnlogging og bekreft med passkey. Passkeyene dine blir.» - Rule 4 - Proposal: «Passkeyene dine blir.» or drop. - [words]
2. **Footer** - `password_on.footer` - Today: «Da logger du inn med passord og kode, eller med passkey.» - Rule 4 - Proposal: drop. - [words]
3. **Login tagline** - `login.html` / `auth.login.sub` - Today: «Logg inn for å passe på familiens telefoner.» - Rule 4 - Proposal: drop. - [words]
- Complies: `change_password.html` (`auth.change_password.sub` says why the page is forced), `verify_2fa.html`, `setup_2fa.html` (`password_on.totp_hint` is a step left to do).

Summary: 3 findings, size S

# Setup / onboarding

## Legg til telefon (`device_add.html`)
1. **Footer tells the next step** - `devices.add.footer` «Deretter kobler du til telefonen med en QR-kode eller en oppsettkode.» - Rule 2, 4 - Proposal: drop (the next page is that choice) - [words]

Summary: 1 finding, size S

## Koble til {name} (`provision_qr.html`)
1. **Launcher check shown even when all is well** - `partials/launcher_check.html` included from `provision_qr.html` (`handlers/provisioning.rs` sets `launcher` always when there is a checksum) / `settings.provisioning.launcher.title` «Launcher for nye telefoner», `.works` «Nye telefoner får Vibb One {version}», `.needs_android` «En ny telefon må ha Android {version} eller nyere: oppdater den før tilbakestillingen.», `.again` «Sjekk på nytt» - Rule 5 - Proposal: on the QR page show the group only when `state` is refused/older/checking; «må ha Android 14» behind the QR footer's (i). Settings › Telefonoppsett keeps the full group - [state logic: the include's condition in `provision_qr.html`]
2. **Choice headings with sub-lines** - `devices.qr.qr` «QR-kode» + `devices.qr.qr_sub` «Ny eller tilbakestilt telefon»; `devices.qr.code` «Kode» + `devices.qr.code_sub` «Telefonen er allerede satt opp» - Rule 1 - Proposal: the heading says the case: «Ny telefon» and «Satt opp fra før» (the QR code and the button name the means), no `choice-sub` - [words]
3. **Code footer repeats the next page** - `devices.qr.code_footer` «Du skriver den inn i innstillingene til Vibb One på telefonen.» (+ (i) `code_info`) - Rule 4 (the code page's footer says «På {name}: Innstillinger i Vibb One › Registrer med kode.») - Proposal: drop the sentence, keep the (i) alone after the button - [words]
4. **Keep-open note** - `devices.qr.keep_open` «La denne siden være åpen til telefonen er tilkoblet. Lastes den inn på nytt, lages en ny kode.» - Rule 2 - Proposal: «La siden stå åpen til telefonen er tilkoblet.»; the reload sentence into the QR (i) - [words]
5. **Paused sub** - `devices.qr.paused_sub` «For mange feil koder ble prøvd denne timen. QR-koden virker nå.» - Rule 5 - Proposal: «QR-koden virker»; the reason behind (i) - [words]
6. **Replaces warning, long** - `devices.qr.replaces` «En telefon som bruker en ny QR-kode eller oppsettkode, tar over, og den som er tilkoblet nå, kobles fra.» - Rule 5 - Proposal: «En ny kode kobler fra {name}.» - [words] - (KEEP: an irreversible-action warning; stays red and visible, only shorter)
7. **Launcher-check subs carry the admin's procedure** (shared partial, also Settings › Telefonoppsett) - `settings.provisioning.launcher.older_sub`, `.wrong_signer_sub`, `.wrong_package_sub`, `.address_sub` (each 25-40 words with env names and DEPLOY.md) - Rule 5 - Proposal: one short sub («En telefon ville avvist den.» / «Nye telefoner får den eldre.»), the env var steps behind an (i) in the sub (as `note_warning.html`'s `fix`) - [words] - (KEEP: the env var names - they are what the admin acts on - inside the (i))

Not flagged: `devices.qr.footer` «Trykk 6 ganger …» (the page's one instruction), `password_hint` «Bare i QR-koden, lagres aldri.» (privacy reassurance on a password field).

Summary: 7 findings, size M

## Oppsettkode (`enroll_code.html`)
1. **Paused sub** - `devices.code.paused_sub` «For mange feil koder ble prøvd denne timen. Denne koden virker etter det; en QR-kode for oppsett virker nå.» - Rule 5 - Proposal: «Virker etter {until}. QR-koden virker nå.»; the reason behind (i) - [words]
2. **Replaces warning** - `devices.code.replaces` «Telefonen som bruker denne koden, tar over, og den som er tilkoblet nå, kobles fra.» - Rule 5 (under «{name} er tilkoblet nå») - Proposal: «Brukes koden, kobles {name} fra.» - [words] - (KEEP: an irreversible-action warning, stays visible)

Summary: 2 findings, size S

# Pages with no findings

## Vibb er låst (`lock.html`), `partials/rule_switch.html`, `partials/budget_rows.html`, `partials/confirm_cancel.html`
No findings: one title, one hint, errors only on failure; the sign-out confirm (KEEP: «Logge ut? Varsler på denne telefonen slås også av.»). The rule switch's sub is the rule's times (a value).
Summary: 0 findings, size S

## Shared partials (`status_line`, `note_row`, `note_warning`, `flash_note`, `group_flash`, `info_row`, `recent`, `band`, `bell`, `vibb_breath`, `js_words`)
No findings of their own beyond `note_warning.html` (see Cross-cutting C1: it does show `note.detail` visibly - verified in the template). The others render a title · value with the sentence behind an (i), or one flash line. Their wording comes from the callers' keys (covered above and in the other pages). `js.save_unknown` «Fikk ikke svar fra serveren. Her er det som er lagret nå.» is an error line and stays.

Summary: 0 findings, size S

# Summary

| Page | Findings | Size |
|---|---|---|
| Telefoner (`devices_list.html`) | 3 | S |
| Telefonens side (`device_detail.html`) | 16 | L |
| Om telefonen (`device_about.html`) | 3 | S |
| Aktivitet (`device_activity.html`) | 1 | S |
| Hjem-skjerm (`device_home.html`) | 8 | M |
| Apper (telefonens, `device_apps.html` + `partials/device_app_row.html`) | 8 | M |
| Navn og ikon (`partials/app_display_form.html`, on Device › Apps and the catalog app page) | 3 | S |
| Tillatelser (`partials/app_permissions_form.html`) | 4 | S |
| Push for apper - gruppen på Apper (`partials/app_push_group.html`; only what design 112's Device › Apps section misses) | 5 | M |
| Anrop og kontakter (`device_calls.html`) | 21 | L |
| Låseskjerm og PIN-koder (`device_lock.html`) | 12 | M |
| Tidsregler, a phone's (`device_time.html`) | 7 | M |
| Leggetid (`device_bedtime.html`) | 5 | S |
| Unntak, partial (`partials/exceptions.html`, on Leggetid and Regel) | 2 | S |
| Mobilnett (`device_mobile.html`) | 11 | M |
| Telefon › Avansert (`device_advanced.html`) | 12 | L |
| Telefon › Nettfilter (`device_filter.html`) | 5 | S |
| Nettfilter › Innlogging (`device_logins.html`) | 9 | M |
| Telefon › Kalender (`device_calendar.html`) | 10 | L |
| Kalender › Legg til kalender (`device_calendar_add.html`) | 3 | S |
| Kalender › CalDAV-konto (`device_calendar_caldav.html`) | 2 | S |
| Kalender › Kalenderlenke (`device_calendar_link.html`) | 2 | S |
| Kalender › Tittelregel (`device_calendar_rule.html`, `partials/calendar_preview.html`) | 3 | S |
| Telefon › Musikk (`device_music.html`) | 6 | M |
| Finn (`device_locate.html`) | 6 | M |
| Finn › Finn telefonen (`device_find.html`) | 2 | S |
| Finn › Posisjonsinnstillinger (`device_location_settings.html`) | 1 | S |
| Finn › Posisjoner (`device_locations.html`) | 1 | S |
| Finn › Soner (`device_zones.html`) | 4 | M |
| Finn › Sone (`device_zone.html`) | 3 | S |
| Apper (fanen, `apps_list.html`) | 1 | S |
| Legg til app (`tracked_app_add.html`) | 3 | S |
| App i katalogen (`tracked_app_detail.html`) | 9 | M |
| Musikk (`music.html`) | 4 | S |
| Musikk › innslag (`music_entry.html`) | 9 | M |
| Kategorier (`music_categories.html`) | 2 | S |
| Importer fra Vibb (`music_import.html`) | 5 | S |
| Musikk › Innstillinger (`music_settings.html`, `partials/spotify_setup.html`) | 10 | M |
| Legg til fra Spotify (`music_spotify.html`) | 7 | S |
| Spotify-innlogging ferdig (`music_spotify_done.html`) | 0 | S |
| Storytel-hylla (`music_storytel.html`) | 5 | S |
| Innstillinger (`settings_hub.html`) | 0 | S |
| Varselinnstillinger (`notifications_settings.html`) | 8 | M |
| Tidsregler, Innstillinger (`schedules.html`) | 2 | S |
| Regel (`schedule_rule.html`) | 4 | S |
| Daglig grense (`schedule_budget.html`) | 3 | S |
| Innstillinger › Nettfilter (`dns_filter.html`) | 14 | M |
| Innstillinger › Blokkert aktivitet (`dns_log.html`) | 2 | S |
| Bakgrunnsbilder (`wallpapers.html`) | 5 | S |
| Telefonoppsett (`provisioning_settings.html`, `partials/launcher_check.html`) | 7 | M |
| App-push (`settings_push.html`) | 5 | S |
| Sikkerhetskopier (`backups.html`) | 5 | S |
| Oppdateringer (`updates.html`) | 4 | S |
| Varsler, innboks (`notifications.html`) | 4 | S |
| Konto og foreldre (`account.html`, partials `passkey_confirm`, `passkeys_still_work`, `reauth_passkey`) | 8 | M |
| Sikkerhetslogg (`security.html`) | 3 | S |
| Innlogging og passordsider (`login.html`, `change_password.html`, `password_sign_in.html`, `setup_2fa.html`, `verify_2fa.html`) | 3 | S |
| Legg til telefon (`device_add.html`) | 1 | S |
| Koble til {name} (`provision_qr.html`) | 7 | M |
| Oppsettkode (`enroll_code.html`) | 2 | S |
| Vibb er låst (`lock.html`), `partials/rule_switch.html`, `partials/budget_rows.html`, `partials/confirm_cancel.html` | 0 | S |
| Shared partials (`status_line`, `note_row`, `note_warning`, `flash_note`, `group_flash`, `info_row`, `recent`, `band`, `bell`, `vibb_breath`, `js_words`) | 0 | S |

Size: S = words only or a few keys; M = several groups or one handler's line logic; L = a page-wide rewrite with
several state-logic changes. Biggest: Anrop og kontakter (21), Telefonens side (16), Innstillinger › Nettfilter (14),
Låseskjerm (12), Avansert (12), Mobilnett gaps (11), Kalender (10), Musikk › Innstillinger (10).
