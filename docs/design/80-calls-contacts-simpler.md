# 80 - A simpler Calls & Contacts page: four plain choices per contact, the rest under "Avansert"

**Asked by the user, 2026-11-09:** "Ang alle sms settings i pwa nå så synes jeg det er mye og rotete alt vi har lagt til
der nå; vi må skrive inn to telefonnr, våre egne, vi må tikke av veldig mange valg og rader nå. Er det noe vi kan
forbedre og strømlinjeformet her? Noe kunne vi kanskje gjemt under advanced, feks 95% av kontaktene man velger at skal
sms og ringe, så skal nok det gjelde både inn og ut fra tlf. Bare en tanke".

**Status:** revision 2 (2026-11-09).
- It folds in the design QA (`docs/review/qa-80-design.md`, 0/2/6/8/6) and the user's answers, including the two
  decided after the QA:
  - the phone's number is learned from a message (§4.4);
  - a "Gruppemeldinger" switch is added (D13).

  §10 lists where each finding went.
- Not approved for building. No question is open. (Approved by the user 2026-11-09, "Bygg 80".)
- Mockups: `docs/design/80-mockups.html`, before and after at 390 px, in light and dark.
- **As built** (branch `calls-simpler`, 2026-11-09): steps 1-2 in `server/src/handlers/calls_form.rs`,
  `handlers::calls`, `handlers::calls_sms`, `templates/device_calls.html`, `static/contact-form.js`; step 3 with
  migration 0134, `call_policy.sms_groups`, `sms_state.own_learned` and the launcher's `sms/engine/OwnLearning.kt`.
  Details: `docs/notes/server/calls-and-contacts.md` ("A simpler Calls & Contacts page") and
  `docs/notes/launcher/calls.md` ("Group texts switch and the learned own number"). Built with the user's addition
  of 2026-11-09: a kid's request for a short number starts with calls and texts off, saying the parent chooses.

**What it changes:**
- **Steps 1 and 2 (server and template):** no policy key, no contract file, no migration and no launcher change.
  Three page-wide scripts get one shared rule (D11).
- **Step 3 (both directories, one commit for the API):**
  - the policy key `call_policy.sms_groups` and the report key `sms_state.own_learned`;
  - `sms_contract.json`'s `policy` and `report` rows, in both copies;
  - one migration;
  - the launcher's group rule and the learning (§8).

**Read with:** design 77 (D2 the channel modes, D3 `sms_in`/`sms_out`/`mms`, D12 the own numbers, D16 held messages,
D17 sender names, §6.8, §7 the PWA, §8.5 RCS, §14 the kid's contact request), design 73 (the emergency menu), design
31 (§5.1 a save never jumps, §5.2 without JS, §5.6 forms that write every field),
`docs/notes/server/calls-and-contacts.md`.

## 0. The shape

- **A contact's form has four switches:**
  - "Kan ringe og ringes" and "Kan sende og få meldinger" each set **both** of their flags (`allow_inbound` +
    `allow_outbound`, `sms_in` + `sms_out`);
  - "Bilder i meldinger";
  - "På Hjem-skjermen".
- **Each contact has its own "Avansert"**, with the four one-way switches and the Message button (and the Matrix ID).
- **A contact whose two directions differ shows "Tilpasset"** in place of that switch, with its Avansert open.
  - The closed list row names the direction, as today ("Anrop inn · Melding inn").
  - The page draws no two-way switch that is only half true.
  - It never changes a contact on its own.
- **Every line tells the truth for the channel's mode.** While calls or SMS isn't "Styrt av Vibb", the contact's rows
  say what applies instead (D1).
- **The page keeps on view what needs a decision or an action:**
  - "Meldinger som venter";
  - the kid's requests;
  - the two channel modes (Av / Styrt av Vibb / Ikke styrt), with the RCS step while it is needed and a quiet line
    while the phone's number is missing;
  - the contacts.
- **One "Avansert" at the page's foot holds the rest:**
  - the emergency menu (the user, 2026-11-09);
  - the default Message button;
  - the lock-screen text switch;
  - **"Gruppemeldinger"**, a new switch (the user, 2026-11-09). Off blocks every group MMS; on, the default, is
    design 77's D12 rule (D13 below);
  - the phone's own number;
  - the RCS help, once its time under SMS is over;
  - the sender names.

  The Avansert row says what it holds and every value that isn't the default, so nothing hides.
- **The phone's own number is typed only when the phone can't tell** (§4). The order, decided by the user:
  1. the SIM's number, which the launcher already reads and reports;
  2. failing that, the number **learned from the first MMS from an approved contact addressed to the kid alone**;
  3. failing that, typed under Avansert.

  The PWA shows the number the phone has, with its source ("Fra SIM-kortet" / "Lært fra en melding fra Mamma"),
  "Bruk dette nummeret" (one tap stores it) and "Endre…" to correct it. A field appears only while the phone knows
  nothing.
- **Add Contact uses the same four switches.** It is also what opens from a held message (D16) and from a kid's
  request (§14).

## 1. Today (verified against `main` 23a92f3a, and `sms-contact-request` 58b7f07c..c2fa0147 for step 6)

### 1.1 The page, top to bottom (`server/templates/device_calls.html`)

| # | Group | What it holds | Controls |
|---|---|---|---|
| 1 | Warnings | the call report's findings | - |
| 2 | Meldinger som venter (`#held`) | while rows wait: each row's Opprett kontakt / Tillat / Slett nå; Slett alle; Blokkerte meldinger, folded | actions only |
| 3 | Forespørsler (`#requests`, step 6, on its branch) | the kid's requests: Legg til… / Avslå | actions only |
| 4 | Nødmeny (`#emergency`) | 1-6 numbers with ↑↓ and Fjern, "Legg til nummer…", then **Forslag** (113, 110) | 1 form + 2 suggestion buttons |
| 5 | (no header, `#settings`) | **Samtaler** segmented ×3, **SMS** segmented ×3 with the RCS line and its ⓘ, **Meldingsknapp** select, the status rows (waiting, Anropsfiltrering, MMS rows), the footer's ⓘ | 2 segmented + 1 select |
| 6 | SMS (`#sms-settings`) | "Vis meldingstekst på låseskjermen", **"Telefonens nummer, SIM 1"**, **"Telefonens nummer, SIM 2"** | 1 switch + 2 text fields |
| 7 | Avsendernavn som slipper gjennom (`#sms-names`) | the names with Fjern, "Legg til navn…" | 1 form + 1 per name |
| 8 | Kontakter (`#contacts`) | a row per contact; Legg til kontakt; Legg til fra familien | see below |

### 1.2 A contact's form (one `<details class="edit">` per contact, one form, every field written)

| Row | Field | Means |
|---|---|---|
| Kan ringe {kid} | `inbound` → `allow_inbound` | the contact may call the kid |
| I telefonboken | `outbound` → `allow_outbound` | the kid may call the contact; it is in the phone book |
| På Hjem-skjermen | `show_on_home` | on Home (only with `outbound`) |
| *Meldinger* (a quiet label) | - | - |
| Kan sende melding til {kid} | `sms_in` | the contact may text the kid (disabled for an emergency number) |
| Kan få melding fra {kid} | `sms_out` | the kid may text the contact |
| Bilder i meldinger | `mms` | pictures both ways |
| Meldingsknapp | `message_app` (none/sms/element/signal, empty = the phone's default) | what the contact sheet's button opens |
| Matrix-ID (bare Element) | `message_address` | always shown, even when the button isn't Element |
| Nytt bilde / Juster utsnitt / Fjern bildet | the photo form | - |
| Fjern kontakten… | a confirm | - |

So: **6 switches, 1 select and 1 text field per contact**, plus the photo and Remove. The closed row says which way
calls go and, on a second line, which way texts go ("Bare innkommende" / "Melding inn").

### 1.3 Add Contact

- Name and number, then the same **6 switches**, all on.
- From a held message, only "Kan sende melding til" is on (R2-M1).
- On step 6's branch, §14's defaults (`REQUEST_ADD_SWITCHES`), plus "Bruk Matrix-ID-en". When ticked, it stores
  the kid's ID in `message_address` and leaves `message_app` at the phone's default.

### 1.4 The count

A phone with SMS "Styrt av Vibb" and five contacts:

| | Today | After (on view) | After (under Avansert) |
|---|---|---|---|
| Page-level settings | 2 segmented, 1 select, 1 switch, **2 number fields**, the names card, the emergency menu with 2 suggestions | 2 segmented | the emergency menu, 1 select, 2 switches (lock text, the new Gruppemeldinger), the phone's number (a row with its source and "Bruk dette nummeret", or one field while the phone knows nothing), the names |
| Groups before the contacts | 4-5 | 1 (the channels) | - |
| One contact's form | 6 switches + 1 select + 1 field = **8** | **4** switches | 4 one-way switches + 1 select (+ the Matrix ID while one is used or stored) |
| Five contacts | **40** | 20 | - |
| Add Contact | 2 fields + **6** switches | 2 fields + **4** switches | 4 one-way switches |

To add an ordinary contact the parent reads 4 switches, already right for the 95 % case, instead of 8 rows and 6
switches. Nothing is removed: every control of today is still on the page, in the same form or route (§6). The
read-only SIM number keeps its override ("Endre…", M3).

## 2. Decisions: a contact's form

**D1. Four plain choices**, in this order:

| Row (nb / en) | Field(s) | On means |
|---|---|---|
| "Kan ringe og ringes" / "Calls both ways" | `allow_inbound` and `allow_outbound` | the contact may call the kid, and the kid may call the contact (the phone book) |
| "Kan sende og få meldinger" / "Texts both ways" | `sms_in` and `sms_out` | texts both ways |
| "Bilder i meldinger" / "Pictures in texts" | `mms` | unchanged |
| "På Hjem-skjermen" / "On the home screen" | `show_on_home` | unchanged |

The user approved the four labels (2026-11-09).

**Sub-lines.** A switch whose value doesn't do what it says in the phone's present state says so. Values are always
kept and sent as today.

- **The calls row, by the channel modes** (what the launcher does: `phoneBookView`, design 77 D2):

| `calls_mode` | `sms_mode` | Calls row sub-line (nb / en) |
|---|---|---|
| Styrt av Vibb | any | none |
| Av | any | "Samtaler er av - bare nødnumre kan ringes" / "Calls are off - only emergency numbers can be called" |
| Ikke styrt | Styrt av Vibb or Av | "Samtaler er ikke styrt - alle kan ringe. {contact} står i telefonboken" (or "står ikke i telefonboken", from `outbound`) / "Calls aren't managed - anyone can call. {contact} is in the phone book" ("isn't in") |
| Ikke styrt | Ikke styrt | "Gjelder når samtaler eller SMS er styrt av Vibb" / "Applies while calls or SMS are managed by Vibb" (no contact reaches the phone then) |

- **The texts row**, while SMS isn't "Styrt av Vibb": "Gjelder når SMS er styrt av Vibb" / "Applies while SMS is
  managed by Vibb".
- **"Bilder i meldinger"**, while SMS isn't managed: the same line. While SMS is managed but no text goes either
  way: "Gjelder når {kid} og {contact} sender meldinger" / "Applies when {kid} and {contact} text" (N5).
- **"På Hjem-skjermen"**, while `outbound` is off: "Vises når {kid} kan ringe {contact}" / "Shows when {kid} can call
  {contact}" (`CallRules.homeContacts`: `outbound && showOnHome`).
- **An emergency-number contact** (`emergency::is_emergency`) can never text in (R2-H1).
  - Its texts row reads "{kid} kan sende melding til {contact}" and maps to `sms_out` alone, with today's reason as
    its sub-line.
  - It is never "Tilpasset" because of `sms_in`, and D6 reads its texts from `sms_out` alone (N6).

**D2. "Avansert" per contact:** a `<details class="adv">` inside the contact's body, under the four switches and above
the photo rows. Opened:

| Group | Row (nb / en) | Field |
|---|---|---|
| Samtaler | "{contact} kan ringe {kid}" / "{contact} can call {kid}" | `inbound` |
| | "{kid} kan ringe {contact}" / "{kid} can call {contact}", sub "I telefonboken" / "In the phone book" | `outbound` |
| Meldinger | "{contact} kan sende melding til {kid}" / "{contact} can text {kid}" | `sms_in` (disabled for an emergency number, with the reason) |
| | "{kid} kan sende melding til {contact}" / "{kid} can text {contact}" | `sms_out` |
| - | "Meldingsknapp" / "Message button" | `message_app`: the same picker, as a stacked row, since at 390 px its label and "Telefonens standard (SMS)" don't fit on one line |
| - | "Matrix-ID" | `message_address`: shown **while the button opens Element X** (the contact's choice, or the phone's default when it has none) **or while an ID is stored** (M6). While it is stored but unused, its sub-line reads "Brukes når knappen åpner Element X" / "Used when the button opens Element X" |

- Both names are in every one-way label, so the direction can't be misread. Add Contact has no contact name yet, so
  it uses "Kontakten" at a sentence's start and "kontakten" inside one: two keys per language (L7).
- **The Avansert row says what it hides.** A closed Avansert never hides a value that isn't the default. Its value is:
  - "Tilpasset" while a channel is split;
  - else the contact's own Message button ("Element X", "Signal / Molly", "Ingen meldingsknapp", "SMS");
  - else "Matrix-ID lagret" / "Matrix ID stored" while one is stored but unused;
  - else nothing.
- **It is drawn open** when:
  - a channel is "Tilpasset";
  - the button opens Element X and the Matrix ID is empty (its row says "Mangler Matrix-ID" / "Matrix ID missing");
  - a Matrix ID was refused (the 400 render);
  - the post said it was open (`more`, D9).
- **An Avansert drawn open inside a closed contact row stops nothing** on the page (D11, M1).

**D3. How a two-way switch maps to its two flags.** This is the one rule the design adds: a pure function
`calls::both_ways` with a test table. For each channel the form posts:
- `calls_shown` / `texts_shown` (hidden): what the main row showed when the page was drawn, `on`, `off` or `custom`;
- `calls` / `texts`: the two-way switch, only when it is drawn;
- the pair from Avansert: `inbound`/`outbound`, `sms_in`/`sms_out`. Avansert is inside the same form, so the pair is
  always posted, open or closed.

| `*_shown` | two-way switch in the post | the pair stored |
|---|---|---|
| `on` | on (unchanged) | **the pair as posted**, so a one-way switch changed in Avansert is taken |
| `on` | absent (the parent turned it off) | **both off** |
| `off` | on (the parent turned it on) | **both on** |
| `off` | absent (unchanged) | **the pair as posted** |
| `custom` | - (not drawn) | **the pair as posted** |
| absent, or any other value (an older page, an old tab) | - | **the pair as posted**: today's behaviour (N4) |

- **With autosave**, every save carries one change, so one row of the table applies.
- **Without JS** (the `<noscript>` Save), a parent who changed both the two-way switch and its pair in one post gets the
  two-way switch, the larger visible change. That rule is documented and has a test.
- **A stale page** (L1): a second tab, or Back from the bfcache. The page was drawn with `*_shown` = `on` (or `off`), but
  the stored pair has changed class since. If the post leaves the two-way switch unchanged and its pair is exactly what
  `*_shown` implies (both on, or both off), the page was untouched in that channel. **The stored pair is kept** for
  that channel, with a test. This catches the case the QA found: tab A saving "Bilder" undoing tab B's split. Other
  stale writes are design 31's write-all, as today.
- **Then, as today:**
  - `sms_in` is never stored for an emergency number;
  - `show_on_home`, `mms`, `message_app` and `message_address` are written from the post;
  - every field of the form is written (design 31 §5.6's write-all holds, since every field is still in one form).
- **A refusal is drawn from the resolved pairs** (L2). A refused Matrix ID, number or name redraws the form from
  `both_ways` applied to the sent form, never from the raw posted pair. Without JS, a two-way flip sent with a bad
  Matrix ID survives the redraw. The same holds for Add Contact's refusal.
- **How the page draws it:**
  - the two-way switch is `on` when both flags are on, and `off` when both are off;
  - otherwise it is "Tilpasset" (`custom`);
  - for an emergency-number contact, the texts row follows `sms_out` alone (D1).
- **No `v=2` marker is needed:** an older page has no `*_shown` and lands in the table's last row.

**D4. Mixed flags show truthfully.** A channel whose two directions differ:
- shows in the form as a one-line value row, **not a switch**: "Kan ringe og ringes · Tilpasset". It has no sub-line,
  so a save from Avansert that turns a channel "Tilpasset" moves nothing on the screen (L8). The value is in the plain
  second-label colour, not the attention colour (N1);
- opens Avansert, where the one-way switches show the truth and change it;
- names its direction in the closed list row (D6, H1);
- is never rewritten by the page (D3, stale pages included). It becomes a two-way switch again when both directions
  are set the same in Avansert.

Design 77's migration (`sms_in = inbound`, `sms_out = outbound`) left every "Bare innkommende" contact split on both
channels. Such contacts show as they are; nothing is converted.

**D5. Add Contact** has the same shape:
- name and number;
- the four switches;
- an Avansert inside the form, with the one-way switches. The Message button is set after adding, as today, except
  through step 6's Matrix ID (below).

It posts `switches=on` as today, plus D3's fields, and D3 resolves the pairs. **A refusal** is drawn from the resolved
pairs (L2). **The phone's own number is refused as a contact** (L6): "Det er telefonens eget nummer" / "That's the
phone's own number". The check is against the stored own numbers and the SIMs' last reported ones, and "Legg til fra
familien" leaves such a contact out of its list. The three ways in:

| Way in | Calls | Texts | Pictures | Home | Avansert | Extra |
|---|---|---|---|---|---|---|
| "Legg til kontakt" | on | on | on | on | closed | - |
| "Opprett kontakt…" from a held message (D16, R2-M1: "an allow grants exactly one thing") | off | **Tilpasset**: only "Kontakten kan sende melding til {kid}" | off | off | **open** | - |
| "Legg til…" from a kid's request (§14) | on | on | off | off | closed | "Bruk Matrix-ID-en" (M6, below) |

- **From a held message** the parent sees exactly what is granted. To grant more, the parent turns on "{kid} kan sende
  melding til kontakten" in the open Avansert. That is the parent's choice, never a default (N3).
- **"Bruk Matrix-ID-en" (M6, decided with step 6):**
  - Ticking it stores the kid's ID in `message_address` **and sets the contact's `message_app` to `element`**, so the
    ID is used: the contact sheet's button opens Element X with it.
  - The switch's sub-line says so: "{kid} foreslo {id}. Meldingsknappen åpner Element X med den. Hvem som helst kan
    stå bak en Matrix-ID: sjekk at det er riktig person." / "{kid} suggested {id}. The message button opens Element X
    with it. Anyone can be behind a Matrix ID: check it's the right person."
  - Unticked, nothing of the ID is stored, as on the branch today.
  - Afterwards the contact's Avansert shows "Element X" on its row and the Matrix ID field (D2).
  - **The change** is one bind in step 6's `add_contact`, `message_app = 'element'` when `use_matrix` is ticked, plus
    the sub-line's words. It goes in step 6's fix round if that runs first, else in this design's step 1. Either way
    it lands before the user's phones get step 6.
- **Add from the family** stays as it is: every flag on, the contact's name, number and photo.

**D6. The closed contact row** names every state, in the same two lines as today (H1):

| Calls | Texts | Value | Second line |
|---|---|---|---|
| both | both | "Anrop og meldinger" / "Calls and texts" | the extras |
| both | none | "Bare anrop" / "Calls only" | the extras |
| none | both | "Bare meldinger" / "Texts only" | the extras |
| none | none | "Ingen anrop eller meldinger" / "No calls or texts" | the extras |
| any other | | the calls part: "Anrop begge veier", "Anrop inn", "Anrop ut" or "Ingen anrop" ("Calls both ways", "Calls in", "Calls out", "No calls"), short and parallel to the texts part so the row fits beside the name at 390 px | the texts part: "Melding begge veier", "Melding inn", "Melding ut" or "Ingen meldinger" ("Texts both ways", "Texts in", "Texts out", "No texts"), then the extras |

- **The extras:** "Hjem" (`outbound && show_on_home`, as today), and "uten bilder" / "no pictures" (texts on in
  either direction and `mms` off), joined with " · ".
- **"In" and "out"** are from the kid's side, as today: "Anrop inn" means the contact calls the kid.
  Mormor, migrated from "Bare innkommende", reads "Anrop inn" / "Melding inn".
- **An emergency-number contact's** texts come from `sms_out` alone (N6).
- `a_contact_row_says_which_way_calls_go` is rewritten for this table, not deleted.

## 3. Decisions: the page

**D7. The order:**
1. the warnings (unchanged);
2. **Meldinger som venter**, with Blokkerte meldinger (unchanged; needs action). A blocked group row's sub-line states
   why group texts are blocked, if anything blocks them all now (M5):
   - "Gruppemeldinger er slått av" / "Group texts are turned off", while the D13 switch is off;
   - else "Mens telefonens nummer mangler, blokkeres alle gruppemeldinger" / "While the phone's number is missing,
     every group text is blocked", while SMS is managed and the phone has no number (§4.5's "number missing").
   - **Why it states a condition, not a cause:** `MmsFlow` records every dropped group as `group` with no sender, so
     the server can't tell a stranger's group from the others.
   - **A distinct class was weighed and set aside.** A new `sender_class` such as `group_off` would make an older
     server refuse the phone's whole `sms-held` post (one bad row refuses all, design 77 §7).
   - So a row from before a change can show today's condition; the sub-line says "now" in its words;
3. **Forespørsler** (step 6, unchanged; needs action);
4. **the channels:** Samtaler and SMS, as today's segmented control with their sub-lines, the status rows (waiting,
   Anropsfiltrering, the MMS rows) and the footer's ⓘ. The Message button picker leaves this group (D8). Under the SMS
   row:
   - **The RCS step**, with its ⓘ and Google's form, stays on view while it is needed (M4):
     - before "Styrt av Vibb" on a phone that could take it, as today: "Før du velger Styrt av Vibb: slå av
       chatfunksjoner i Meldinger.";
     - **for 14 days after the last channel change** (`roles_changed_at`) while SMS is managed: "Slå av
       chatfunksjoner i Meldinger hvis du ikke har gjort det." / "Turn off chat features in Messages if you haven't."
       (no "først", N2). The ⓘ then leads with Google's form, the path for a phone already locked down;
     - after that it is the row "Chatfunksjoner (RCS)" in Avansert, with the same ⓘ.
     - A one-tap "Gjort" was the alternative. It needs a stored flag, so a migration, and the reminder costs nothing
       to keep for two weeks. A later calls change shows it again, which is harmless.
   - **A missing number** (M5): a quiet line "Telefonens nummer mangler - gruppemeldinger blokkeres", linking to
     `?advanced=1#sms-settings`, while all of these hold:
     - SMS is managed;
     - Gruppemeldinger is on;
     - the phone has no number from any source (the parent, a SIM, or learned).

     It is not an Attention alarm. With groups off, nothing is lost for want of the number, so nothing is said.
5. **Kontakter**, moved up from the bottom: the page's main work;
6. **Avansert**, a `details.section` row at the foot, closed (D8).

**D8. The page's Avansert.**

**The row says what it holds** (the user, on the Nødmeny: "easy to check"; L4):
- the title "Avansert" / "Advanced";
- line 1, **the emergency menu, always**: "Nødmeny: 112, 113, 110" / "Emergency menu: 112, 113, 110". These are the
  numbers in order, at most 6, so a parent sees what the kid's emergency button offers without opening anything. While
  any menu number isn't an emergency number to the server (design 73's orange sub-line), it adds in orange: "1 nummer
  følger anropsreglene" / "1 number follows the call rules" (a `t_count` pair);
- line 2: "Meldingsknapp: {app}", then:
  - " · {count} avsendernavn" while there is one (a `t_count` pair, L7);
  - " · Meldingstekst på låseskjermen" while `sms_lock_text` is on;
  - " · Gruppemeldinger av" / " · Group texts off" while D13's switch is off (not the default);
- in orange, only while it matters: "Telefonens nummer mangler - gruppemeldinger blokkeres", on the same condition as
  D7's line.

**Opened:**

| Group | Rows | Form (route) |
|---|---|---|
| Nødmeny | design 73's rows unchanged: ↑↓, Fjern, "Legg til nummer…", the footer, then Forslag | `/calls/emergency…` (unchanged routes) |
| Meldinger | "Meldingsknapp", the phone's default (today's select) | `/calls/settings` with `section=button` |
| | "Vis meldingstekst på låseskjermen" | `/calls/sms` (unchanged form) |
| | **"Gruppemeldinger"** (D13) | `/calls/sms` |
| | "Telefonens nummer" (§4.5) | `/calls/sms`, and "Bruk dette nummeret" |
| | "Chatfunksjoner (RCS)" with its ⓘ, once its time under SMS is over (D7) | - |
| Avsendernavn som slipper gjennom | the names, Fjern, "Legg til navn…", D17's warning under it | `/calls/names`, `/calls/names/remove` (unchanged) |

- The group footer "Dette gjelder når SMS er styrt av Vibb." stays. "Tillat avsendernavnet…" on a held row stays the
  usual way a name gets on the list.
- **The emergency menu moves from the top to Avansert** (the user, 2026-11-09). That changes design 73's placement.
  - Its truth stays easy to check: the menu itself is on Avansert's row, and so is any number that isn't an emergency
    number.
  - Its routes, refusals and anchors are design 73's. Every return gets `?advanced=1` (D9), and a refused "Legg til
    nummer…" draws Avansert open.
  - Nothing about the phone changes: the menu works untouched (112 alone).
- **Splitting `/calls/settings`** follows design 31 §5.6: a form is split only with a section marker, and its handler
  then writes only that section.
  - `section=channels`: `calls_mode` and `sms_mode`. A missing or unknown mode is a 400, as today.
  - `section=button`: `default_message_app` alone.
  - No marker (an older page, the tests): today's write-all.
  - Each section gets the "saving one leaves the other unchanged" test.

**D13. "Gruppemeldinger": whether group texts are allowed at all** (the user, 2026-11-09: "ønsker egentlig en toggle på
call contacts i pwa på om gruppesamtaler er lov i det hele tatt? Det er vel en litt sånn advanced greie og").

**In the PWA:**
- The row "Gruppemeldinger" / "Group texts" sits in the page's Avansert, in the Meldinger group, as a switch. Its
  sub-line: "Bare når alle andre i gruppen er kontakter med meldinger slått på, og telefonens nummer er kjent." /
  "Only when everyone else in the group is a contact with texts on, and the phone's number is known."
- It is saved by the `/calls/sms` form. That form writes every field, and an older page has no such switch, so the
  form posts a hidden `groups_shown=1`. Only a post with it writes `sms_groups`, and a missing switch then means off.
  A post without it (an older page) leaves `sms_groups` as stored. Test both.
- The group footer's "Dette gjelder når SMS er styrt av Vibb" covers it.

**What it does:**
- **Off:** every group MMS is blocked, whatever its members, as `group` (no allow path, as D12 of design 77). A
  one-to-one MMS and every SMS are untouched. In an existing group conversation, the kid's compose bar says
  "Gruppemeldinger er slått av" / "Group texts are turned off" and sends nothing.
- **On** (the default): design 77 D12 as today. Every other member must be a contact with a text switch in effect,
  and the phone's number must be known (§4).
- **The default is on,** so today's behaviour holds for every phone. Design 77 D12's members rule already admits only groups made
  of the kid's own texting contacts, so off isn't needed for safety. It is a parent's choice, and it stays liftable
  from here.

**The wire, in both directories in one commit** (the API rule):
- **`call_policy.sms_groups: bool`, always sent**, as every SMS key (design 77 QA R2-I1).
  - It is pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
  - `sms_contract.json`'s `policy` rows gain it, both copies.
  - Stored in `device_policy.sms_groups INTEGER NOT NULL DEFAULT 1`. It needs a migration: the next free number when
    it is built. Step 6 holds 0133 on its branch, so likely 0134; re-check then.
- **The launcher decodes an absent key as `true`.** That is today's behaviour, so an older server changes nothing.
  - This is a deliberate exception to "absent decodes as false": the key loosens nothing a server ever sent. The
    members rule beneath it still fails closed.
  - `MmsGate.group` takes it: off makes any `Many` result `Blocked`; `OneToOne` is unchanged.
- **Capability `sms_groups_v1`** in the launcher's status report. Until the phone claims it, the PWA draws the switch
  disabled with "Oppdater Vibb One først" (the capability gate of design 77 D2). An older launcher with the engine
  would otherwise ignore an "off".

## 4. The phone's own number (design 77 D12)

### 4.1 What it is for

It is for one thing: design 77 D12's group rule (`MmsGate.group`, step 3b on `sms-mms`).
- An MMS with more than one recipient address is a group.
- To accept a group, the phone tells "us" from the other members. Every other member must be a contact with a text
  switch on, or the group is dropped with no allow path.
- With no own number at all, every message addressed to more than one address is dropped (counted as `group`).
- **Plain SMS and one-to-one MMS never need it.** A group "SMS" from an iPhone or Google Messages goes out as MMS, so
  this is all of group texting, and nothing else.

### 4.2 What the phone already does (launcher, verified)

- `SmsRuntime.ownNumbers` reads `SubscriptionManager.getPhoneNumber(subId)` for each active SIM. It reports the
  number, normalised, as `sms_state.own_numbers: [{slot, number | null}]`.
- `MmsGate.ownNumbers` takes the parent's number for a slot, and **the SIM's number for a slot the parent left
  empty**, read live at each judgement.
- `READ_PHONE_NUMBERS` is in the manifest and held by a Device Owner grant (`OwnPermissions.kt`, `POLICY_FIXED`).
  minSdk is 34.
- The PWA already shows a mismatch between a typed number and the SIM's.

### 4.3 How reliable the SIM's number is, and what can't tell it

- `getPhoneNumber(subId)` (API 33) returns the first non-empty of three sources:
  - the carrier's (`setCarrierPhoneNumber`, only a carrier-privileged app);
  - the SIM's MSISDN record;
  - IMS: the number in the network's IMS registration (the P-Associated-URI), filled while VoLTE/VoWiFi is registered.

  [verify on device: the order of the three]
- **Norwegian SIMs often leave the MSISDN record empty.**
- **The IMS source is the likely one** (Telia, Telenor and Ice run VoLTE), but it can come and go with the
  registration. Whether the Jelly Star registers IMS on each carrier is unknown until step 0, which already checks
  "the own number on each SIM" and now also "from which source" (`getPhoneNumber(subId, PHONE_NUMBER_SOURCE_IMS)` and
  `_UICC` separately).
- **The setup QR can't carry it.** The QR is made on the server before the phone exists, and it carries the server's
  address and enrolment code, never a SIM fact.
- **A received SMS can't tell it.** An SMS-DELIVER PDU carries the sender (TP-OA) and no recipient: the destination
  exists only in the sender's SMS-SUBMIT.
- **A received MMS's `To` in general is no source.** The MMSC delivers by the kid's subscription, but `To`/`Cc` are
  written by the sender, and a Bcc'd recipient isn't listed. §4.4 looks at a narrow variant.

### 4.4 Decided: learn it from an approved contact's one-to-one MMS

**The user** (2026-11-09) asked whether group texts work anyway, and whether the number can't come from the QR or a
received SMS. Neither can carry it (§4.3). The user accepted this order ("Det høres greit ut mtp gruppe mms"):
1. **the SIM's number** (§4.3);
2. **learned from the first MMS from an approved contact addressed to the kid alone.** It is used at once and shown in
   the PWA as "Lært fra en melding fra {contact}", so the parent can correct it;
3. **typed** under Avansert.

The parent's own number for a slot always wins (as today). Until one of the three exists, group MMS are dropped, as
today.

**The rule, on the phone** (`MmsFlow`, after design 77 D10's checks and after the retrieve-conf's `From` matched the
notification's). The phone learns only when every condition holds:
- **No own number is known** from the parent or a SIM, and **no learned number or open conflict** exists. Learning
  only fills a gap.
- **The sender is allowed by D4 rule 5:** a `/TYPE=PLMN` number that is a contact with `sms_in` and isn't an emergency
  number.
- **The retrieve-conf has exactly one `To` address and no `Cc`.**
- **That address is a `/TYPE=PLMN` number** that normalises to a full `+` number: not a short code, not an emergency
  number, not the sender, and **not any contact on this phone**.

**Once learned:**
- **It is "us"** for `MmsGate.ownNumbers` while the parent's and the SIMs' numbers are all missing, and it is stored
  in the engine's store (CE storage).
- **A conflict fails closed.** A later message that meets every condition but names another `To` address drops the
  learned number: no own number again, so groups are dropped. Both numbers are reported as the conflict.
  - Nothing more is learned until the parent stores a number or the SIMs change, so an attacker can't make it flip.
- **A change of the phone's active SIMs** (the set of subscription ids) clears the learned number and the conflict, since
  a new SIM has another number.
- **A contact added later with the learned number** clears it, as a number that is a contact can't be "us". The server
  also refuses the phone's own number as a contact (D5).

**The report:** `sms_state.own_learned: {number, from, at, conflict}` or `null`.
- `number`: in use, or `null` during a conflict.
- `from`: the teaching sender's normalised number.
- `at`: epoch ms.
- `conflict`: `[]`, or the two numbers seen.

The server keeps it through `sms::sanitize_state`: numbers by `sms::sender_ok("number", …)`, at most two in `conflict`.
The PWA names the sender by its contact ("Lært fra en melding fra Mamma").

**The security analysis:**

- **What the learned number gives:** "us" for the group rule, nothing else. With it, a group `[us, contact B]` from
  allowed contact A is accepted, and a group with any other unknown address is still dropped. It opens no SMS rule, no
  call rule and no allow path, and the number goes no further than the phone and the parent's page.

- **A hostile allowed contact,** or someone who can send through the real MMSC as an allowed number (design 77 §3's
  residual), can teach a wrong number:
  - **How:** send a one-to-one MMS with `To = [stranger]` and the kid in Bcc, first, while the phone knows no number.
    The stranger's number is learned as "us".
  - **Gain 1, one hidden member in the attacker's own groups.** A later group `[stranger, contact B]` with the kid
    Bcc'd passes, with the stranger taken for "us". This is small:
    - the stranger gets nothing from the kid: a reply goes to the conversation's members, and the stranger is "us";
    - the stranger can't reach the kid: their own messages fail D4 rule 5 and wait for the parent;
    - the content the kid sees is the allowed contact's, which that contact could send one-to-one anyway.

    The gain over today is that the kid doesn't see that one more person got the allowed contact's group message.
  - **Gain 2, a denial of group texts.** The kid's real number is now "other", so every real group, which lists it in
    `To`, is dropped. That costs what having no number costs today.
  - **The parent sees it:** Avansert shows "Lært fra en melding fra {contact}" with the number, and a number that
    isn't the kid's is easy to spot and correct ("Endre…").
  - **The parent can stop it:** "Gruppemeldinger" off (D13) closes groups whatever was learned.
- **The honest shape is never fooled.** A real one-to-one MMS from a contact lists the kid's own number in `To`. A
  message whose `To` names someone else and reaches the kid only by Bcc is the attack shape. Its single address is
  learned only when it is the first such message.
- **A hostile MMSC** is the carrier. Design 77 D10 already trusts the SIM's MMSC host for every MMS byte, and it can
  deliver any headers. It gains nothing new.
- **A race** is decided by the first matching message. A conflict afterwards fails closed (groups dropped, both shown)
  and is never resolved by the phone itself.
- **Weighed and set aside (revision 2 draft, 2a): confirm before use.** That would hold groups dropped until the
  parent's tap. The user chose use-at-once with correction. The residual above is small and visible, and the switch is
  the parent's brake.

**Where it lives:** on the launcher side, with the phone reporting what it learned. It is design 80's step 3 (§8), after
design 77 step 3b's fix round (which owns `MmsFlow` and `MmsGate`). Step 0's IMS check decides how often it is needed:
when the SIMs report their number, (1) covers the phone and (2) never runs.

### 4.5 The PWA, for every source

For each active SIM in the phone's last report, and for every slot with a stored number (L5):

| What is known | Avansert shows |
|---|---|
| the SIM reports a number, nothing stored | "Telefonens nummer · +47 912 34 567", sub "Fra SIM-kortet", with **"Bruk dette nummeret"** and **"Endre…"** (M3) |
| stored, equal to the SIM's | "… · Lagret" / "… · Saved", with "Endre…" |
| stored, the SIM reports another | the field with the stored number, and today's mismatch finding above the groups |
| the SIMs report `null`, nothing stored, a learned number (§4.4) | "Telefonens nummer · +47 …", sub "Lært fra en melding fra {contact}" / "Learnt from a message from {contact}", with "Bruk dette nummeret" and "Endre…" |
| the SIMs report `null`, nothing stored, a conflict (§4.4) | the field, hint "To meldinger viste ulike numre: {a} og {b}. Skriv inn det riktige." / "Two messages showed different numbers: {a} and {b}. Type the right one.", with "Bruk dette nummeret" beside each |
| the SIM reports `null`, nothing stored, nothing learned | the field "Telefonens nummer", hint "Telefonen vet ikke nummeret sitt ennå. Uten det blokkeres gruppemeldinger." / "The phone doesn't know its number yet. Without it, group texts are blocked." |
| stored, the SIM reports `null` | "… · Lagret", with "Endre…" |
| stored, for a slot no longer in the phone (L5) | the field, labelled "SIM 2 - ikke i telefonen nå" / "SIM 2 - not in the phone now", so it can be cleared (it is still sent and taken for "us") |

**"Number missing"** (D7's lines, Avansert's orange line, the blocked group rows) means: no stored number, no SIM
number and no learned number in the last report.

- **"Bruk dette nummeret"** is a POST to `/devices/{id}/calls/own-number/use`, with `source` = `sim:<slot>`,
  `learned` or `conflict:<0|1>`.
  - It stores the number **from the phone's last report** for that source, never a number from the form, with
    `save_sms`'s checks: a full `+` number, not a short code and not an emergency number.
  - A SIM's number goes into its slot. A learned or conflict number goes into the first active slot with no stored
    number (slot 1 on a phone with one SIM).
  - The policy then carries it whatever the SIM or a message says later. This covers a momentary IMS reading (M3).
  - It returns to `?advanced=1#sms-settings`.
  - A source whose report changed meanwhile lands on `?notice=own_number_gone#sms-settings`, with nothing stored.
- **"Endre…"** opens the field (a `details.edit`). Without JS it is a plain `<details>`, so it works anyway.
- **One field per SIM in question, not two.** "SIM 1" / "SIM 2" is said only on a phone that reports two, or for a
  stored slot 2. With no report yet (an older launcher, a new phone), there is one field for SIM 1.
- **`/calls/sms` writes only the slots it was sent.** An absent `own_number_N` keeps its slot, and an empty one
  removes it. It gets the "an absent field keeps its slot" test.
- **Steps 1 and 2 draw the SIM rows. The learned rows come with step 3**, which brings the report key.
- **It stays under Avansert.** The rule can live without it: only group MMS are lost, and they are dropped (nothing
  reaches the kid). The missing number is said on view, quietly, under the SMS row (D7), on Avansert's row (D8) and
  on blocked group rows (D7). It is not an Attention alarm.
- **The finding's words move with it:** "Sjekk Telefonens nummer under SMS" becomes "under Avansert"; en: "under SMS"
  becomes "under Advanced" (L7).

## 5. Where saves return, what stays open, and the scripts (design 31 §5.1)

**D9. Returns.**
- **A contact's save** returns to `?open=<id>#contact-<id>`, as today, plus `&more=<id>` when the form posted `more=1`.
  - `more` is a hidden field in the contact's form. The script (D10) sets it to `1` while that contact's Avansert is
    open, and clears it when it is closed. So **any** save from that contact's form comes back as it was: Avansert
    open if it was open (L3).
  - Without JS the field keeps the value it was drawn with, and Avansert comes back as drawn.
- **The page's Avansert forms** always return with `?advanced=1`:
  - `/calls/sms` and "Bruk dette nummeret" → `#sms-settings`;
  - `/calls/settings` with `section=button` → `#message-button`;
  - `/calls/names` and `/calls/names/remove` → `#sms-names`;
  - the emergency routes → `#emergency`, `#emergency-<id>` or `#add-emergency`.

  The template draws Avansert open with `advanced=1`. These forms sit only inside it, so no script is needed.
- **A refusal (400) inside an Avansert** renders it open: a Matrix ID, an own number, a name, an emergency number.
- **Unchanged:**
  - Add Contact → `#contact-<new>` (its Avansert open when D2 says so);
  - "Opprett kontakt…" → `?add_number=…#add-contact`;
  - "Legg til…" → `?request=<id>#add-contact`;
  - the held and request rows' own anchors;
  - Remove → `#contacts`.
- `every_form_on_the_calls_page_returns_to_its_place` gains every new target. scroll-restore keeps the place: its
  record is keyed by path, and every target is an ancestor of its form.

**D10. `static/contact-form.js`** (ES5, loaded on the calls page beside `photo-crop.js`). It changes nothing that is
saved:
- **Every contact form:** it keeps `more` in step with that contact's Avansert (D9).
- **Add Contact only** (no autosave there; H2). After any change to a channel's switches it makes the form agree with
  itself:
  - flipping the two-way switch sets both one-way switches to its value;
  - after any change, `*_shown` is set to the pair's class (`on`, `off` or `custom`);
  - the main row shows the two-way switch with the pair's value when the pair is equal, and "Tilpasset" when it isn't.
    The switch is only hidden (`hidden`), never removed, so the DOM keeps one input per name.

  So with the script, **every Add Contact post has `*_shown` equal to the pair's class, and D3 resolves it to the pair
  as posted**, the same as without the script (nothing swaps then).
- **Not on the contact edit forms:** each autosave there navigates at once with one change, and D3 resolves it. A
  mirror would race the submit.
- **Tested with node** (`jstest/contact-form.test.js`):
  - `more` follows the toggle;
  - in Add Contact, narrowing one direction posts `custom` with the pair;
  - flipping the two-way off then one direction on posts `custom` with that direction;
  - flipping back to equal draws the switch and posts `on`/`off`.
- **The server test:** `calls_shown=custom` with no `calls` stores the posted pair.

**D11. An open `<details>` inside a closed one blocks nothing** (M1). `auto-reload.js`, `pull-refresh.js` and
`reconnect.js` refuse while `details[open]` matches, which today also matches an Avansert drawn open inside a closed
contact row.
- All three count only a **shown** open `<details>`: one with no closed `<details>` ancestor.
- The test is the same few ES5 lines in each, `shownOpen(doc)`. Walking `parentNode` is enough, with no layout read.
- A jstest for each script covers it: a closed contact holding an open Avansert doesn't block the reload, the pull or
  the reconnect, and an opened one still does.
- **Why this fix and not "draw Avansert open only inside an open row":** it fixes the rule wherever a nested disclosure
  is drawn open, and it needs no script to open Avansert when a split contact's row is opened.

## 6. What changes where, and what doesn't

**Steps 1 and 2 (server and template only):**
- **The policy, the phone and the contracts don't change.**
  - The `call_policy` keys and values, `policy_json_keys_snapshot`, `PolicyResponseCompatTest`, `sms_contract.json`;
  - the launcher's rules (D4/D5) and the group rule.

  A two-way switch writes the same two columns a parent could set today with two switches.
- **The routes:** none is renamed, so CSRF and the security-log lines stay. The additions:
  - `/calls/settings`' `section`;
  - `/calls/sms`' per-slot write;
  - `/calls/own-number/use`;
  - the `*_shown` fields and `more` on `/contacts/{cid}` and `/contacts`;
  - step 6's `use_matrix` also setting `message_app`.

**Step 3 (both directories, the API rule's one commit for the wire):**

| Side | Change |
|---|---|
| Policy | `call_policy.sms_groups` (D13), always sent; `policy_json_keys_snapshot`, `PolicyResponseCompatTest`, the launcher's `CallPolicy` DTO (absent = `true`) |
| Report | `sms_state.own_learned` (§4.4); the server's `sms::sanitize_state` and its field table (`intake_limits::the_field_tables_keep_every_contract_key`); the launcher's `SmsState` DTO |
| Contract | `server/testdata/sms_contract.json` and its launcher copy: `policy` rows with `sms_groups` true and false; the `report` row with `own_learned` (one learned, one conflict); `capability` gains `sms_groups_v1` |
| Server | migration (`device_policy.sms_groups`, next free number), `/calls/sms`'s `groups_shown`, the capability gate, the learned rows of §4.5, the blocked group rows' first reason |
| Launcher | `MmsGate.group` reads `sms_groups`; `MmsGate.ownNumbers` takes the learned number last; `MmsFlow` learns, clears on a conflict, on a SIM change and on a matching contact; the compose bar's "Gruppemeldinger er slått av"; the capability `sms_groups_v1` |

**In every step:**
- **Every capability:** every switch, picker and field of §1 is still on the page, in the same form and column.
  - The SIM's number keeps the parent's override ("Endre…").
  - Every restriction stays liftable from here: the channel modes are on view, every contact's switches are in its
    form, and the group switch is in Avansert.
- **Autosave:** each switch and picker saves its form on change (`data-autosave`), and each form keeps its
  `<noscript>` Save.
- **i18n:** every new word is in `en.json` and `nb.json` (§7), and the launcher's new string is in both `strings.xml`.
  `i18n::tests` and `tests::i18n_pages` cover them.
- **The device page's rows and Attention** are unchanged, apart from the own-number finding's words.

## 7. Words (nb / en; new keys only, the build names them)

| Where | nb | en |
|---|---|---|
| contact | Kan ringe og ringes | Calls both ways |
| contact | Kan sende og få meldinger | Texts both ways |
| contact, Avansert row | Tilpasset | Custom |
| calls row, by mode | Samtaler er av - bare nødnumre kan ringes | Calls are off - only emergency numbers can be called |
| calls row, by mode | Samtaler er ikke styrt - alle kan ringe. {contact} står i telefonboken / står ikke i telefonboken | Calls aren't managed - anyone can call. {contact} is in the phone book / isn't in the phone book |
| calls row, by mode | Gjelder når samtaler eller SMS er styrt av Vibb | Applies while calls or SMS are managed by Vibb |
| texts, pictures | Gjelder når SMS er styrt av Vibb | Applies while SMS is managed by Vibb |
| pictures | Gjelder når {kid} og {contact} sender meldinger | Applies when {kid} and {contact} text |
| Home | Vises når {kid} kan ringe {contact} | Shows when {kid} can call {contact} |
| Avansert | {contact} kan ringe {kid} / {kid} kan ringe {contact} | {contact} can call {kid} / {kid} can call {contact} |
| Avansert | {contact} kan sende melding til {kid} / {kid} kan sende melding til {contact} | {contact} can text {kid} / {kid} can text {contact} |
| Avansert | Mangler Matrix-ID · Matrix-ID lagret · Brukes når knappen åpner Element X | Matrix ID missing · Matrix ID stored · Used when the button opens Element X |
| Add Contact | Kontakten (a sentence's start) · kontakten (inside one) | The contact · the contact |
| Add Contact | Det er telefonens eget nummer | That's the phone's own number |
| step 6 | {kid} foreslo {id}. Meldingsknappen åpner Element X med den. Hvem som helst kan stå bak en Matrix-ID: sjekk at det er riktig person. | {kid} suggested {id}. The message button opens Element X with it. Anyone can be behind a Matrix ID: check it's the right person. |
| row | Anrop og meldinger · Bare anrop · Bare meldinger · Ingen anrop eller meldinger | Calls and texts · Calls only · Texts only · No calls or texts |
| row | Anrop begge veier · Anrop inn · Anrop ut | Calls both ways · Calls in · Calls out |
| row | Ingen meldinger · uten bilder | No texts · no pictures |
| page | Avansert | Advanced |
| page | Nødmeny: {numbers} | Emergency menu: {numbers} |
| page | {count} nummer følger anropsreglene (`t_count`) | 1 number follows the call rules / {count} numbers follow the call rules |
| page | Meldingsknapp: {app} | Message button: {app} |
| page | {count} avsendernavn (`t_count`) | 1 sender name / {count} sender names |
| page | Meldingstekst på låseskjermen | Message text on the lock screen |
| page | Gruppemeldinger · Gruppemeldinger av | Group texts · Group texts off |
| page | Bare når alle andre i gruppen er kontakter med meldinger slått på, og telefonens nummer er kjent. | Only when everyone else in the group is a contact with texts on, and the phone's number is known. |
| page | Telefonens nummer mangler - gruppemeldinger blokkeres | The phone's number is missing - group texts are blocked |
| page | Fra SIM-kortet · Lagret · Bruk dette nummeret · Endre… | From the SIM card · Saved · Use this number · Change… |
| page | Lært fra en melding fra {contact} | Learnt from a message from {contact} |
| page | To meldinger viste ulike numre: {a} og {b}. Skriv inn det riktige. | Two messages showed different numbers: {a} and {b}. Type the right one. |
| page | Telefonen vet ikke nummeret sitt ennå. Uten det blokkeres gruppemeldinger. | The phone doesn't know its number yet. Without it, group texts are blocked. |
| page | SIM {slot} - ikke i telefonen nå | SIM {slot} - not in the phone now |
| page | Slå av chatfunksjoner i Meldinger hvis du ikke har gjort det. | Turn off chat features in Messages if you haven't. |
| page | Chatfunksjoner (RCS) | Chat features (RCS) |
| blocked row | Gruppemeldinger er slått av | Group texts are turned off |
| blocked row | Mens telefonens nummer mangler, blokkeres alle gruppemeldinger | While the phone's number is missing, every group text is blocked |
| launcher, compose bar | Gruppemeldinger er slått av | Group texts are turned off |

- **Existing keys stay:**
  - `device.sms.pictures`, `device.calls.on_home`, `device.calls.matrix`;
  - `device.calls.phone_book`, now as the sub-line;
  - `device.calls.texts_in` and `texts_out`, reused by D6.
- **Keys nothing uses any more go:**
  - `device.calls.both_ways`, `in_only`, `out_only` and `no_calls`, replaced by D6's calls parts;
  - `texts_both_ways`, `device.sms.messages` and `device.calls.can_call`;
  - `device.sms.texts_in` and `texts_out`.

  `i18n::tests` finds unused keys.

## 8. Steps and tests

Two server commits (**M** together), then one launcher and server step (**M**):

1. **The contact form and Add Contact** (server).
   - `calls::both_ways` (D3, with the stale-page guard) and the `*_shown` fields in `update_contact` and `add_contact`;
     refusals redrawn from the resolved pairs;
   - the template's four switches with D1's sub-lines by mode, the one-line "Tilpasset" row, and the per-contact
     Avansert with its open rule, its row value and the Matrix ID rule (D2);
   - the three Add Contact ways in (D5), with the own-number refusal and step 6's `use_matrix` → `element` unless step
     6's fix round did it;
   - the closed row (D6), `more` and `static/contact-form.js` (D10), and D11 in the three scripts.
   - **Coordination with step 6** (`sms-contact-request`, built; it also changes Add Contact and `add_contact`): build
     this on main **after** step 6 merges, and move "Bruk Matrix-ID-en" into the new form. M6's one bind goes in
     step 6's fix round if that runs first.
2. **The page** (server).
   - The new order (D7) with the RCS line's 14 days and the missing-number line;
   - the page's Avansert (D8) with the emergency menu and the row's summary;
   - `/calls/settings` split by `section`, and `/calls/sms` writing only the slots it was sent;
   - the SIM rows of §4.5 with "Bruk dette nummeret" and "Endre…", and the blocked group rows' number line;
   - the `advanced=1` returns (D9) and the finding's words.
3. **Group texts and the learned number** (launcher and server; §6's table; the wire in one commit).
   - D13's switch;
   - §4.4's learning;
   - §4.5's learned and conflict rows;
   - the blocked rows' "Gruppemeldinger er slått av".

   **When:** after design 77 step 3b's fix round merges (it owns `MmsGate` and `MmsFlow`). **Release:** in the same
   launcher release as 3b, if possible. The capability gate covers it otherwise. **Review:** a code QA of its own,
   since it touches the reviewed group rule.

**Tests, steps 1 and 2** (lean; server `src/tests/calls.rs`, `sms_page.rs`, `calls::tests`; jstest):

- **`both_ways`:**
  - every row of D3's table, with an unknown `*_shown` read as absent (N4);
  - the stale-page guard keeps a stored split (L1);
  - the emergency number's `sms_in`.
- **`update_contact`:**
  - a two-way switch turned off stores both off;
  - a one-way change under `on` stores the pair;
  - a `custom` channel keeps its pair when the other channel or `mms` is saved;
  - `custom` with no switch stores the pair (H2);
  - a post without `*_shown` is today's behaviour;
  - a refusal is redrawn from the resolved pairs (L2).
- **Rendering:**
  - a mixed contact shows the one-line "Tilpasset" and Avansert open;
  - an equal contact shows the switch, with Avansert closed;
  - D1's sub-lines for each mode pair (M2), and never a "Bare …" while a channel isn't managed;
  - the Avansert row's value for a contact with its own button, and for a stored but unused Matrix ID;
  - "Mangler Matrix-ID";
  - `a_contact_row_says_which_way_calls_go`, rewritten for D6 (H1).
- **Add Contact:**
  - each way in renders D5's defaults;
  - posting the held prefill unchanged stores exactly `sms_in`;
  - the request prefill stores §14's flags, and ticked "Bruk Matrix-ID-en" stores the ID and `element` (M6);
  - the phone's own number is refused, and is missing from the family list (L6).
- **`/calls/settings`:**
  - `section=channels` keeps `default_message_app`;
  - `section=button` keeps both modes;
  - no section is today's.
- **The own number:**
  - **`/calls/sms`:** an absent own-number field keeps its slot; an empty one removes it; a refused one renders with
    Avansert open.
  - **The SIM row:** a SIM-reported number shows the row with "Bruk dette nummeret" and "Endre…"; the tap stores the
    reported number, never a posted one; a changed report lands on the notice (M3).
  - **A missing number:** it shows the field, the line under the SMS row, Avansert's orange line and the blocked group
    rows' line while SMS is managed (M5).
  - **A stored slot for a SIM no longer in the phone** is drawn and can be cleared (L5).
  - **A mismatch** shows the field and the finding.
- **The page:**
  - Avansert's row names the emergency numbers and the orange count;
  - every emergency form returns with `advanced=1`, and a refused "Legg til nummer…" draws Avansert open;
  - the lock-text item shows on the row while on (L4);
  - the names count is plural-correct in both languages (L7);
  - the RCS line is under SMS for 14 days after `roles_changed_at` while managed, then in Avansert (M4).
- **Form returns:** `every_form_on_the_calls_page_returns_to_its_place` covers every new target, `tests::shell` checks
  that every new form lands on its own group, and `i18n_pages::nb_pages_have_no_english` runs with a mixed contact and
  Avansert open.
- **jstest:**
  - `contact-form.test.js` (D10);
  - `auto-reload`, `pull-refresh` and `reconnect`: a closed contact holding an open Avansert blocks nothing, and an
    opened one still blocks (D11).
- **The browser pass** (an agent, before QA):
  - 390 px, light and dark, against the mockups;
  - a save inside each Avansert comes back open and in place, with no shift (L8);
  - pull-to-refresh works on a page with a split contact;
  - `?advanced=1` and `&more=` survive reconnect's reload;
  - iOS Safari and Chrome on Android.

**Tests, step 3:**

- **The launcher (JVM, `MmsGate`/`MmsFlow` pure parts):**
  - `group` with `sms_groups` off blocks every `Many` and keeps `OneToOne`;
  - the key absent decodes as on;
  - the learning rule learns from an allowed contact's MMS with one `To` and no `Cc`. It doesn't learn from:
    - a sender that isn't allowed, or an emergency number;
    - two `To` addresses, or any `Cc`;
    - a short code, an emergency number, the sender or a contact as the `To`;
    - a phone that already knows a number (the parent's or a SIM's).
  - a second message with another `To` drops the number, reports both, and stops learning;
  - a change of the active SIMs clears it;
  - a contact added with the learned number clears it;
  - the parent's and the SIMs' numbers win over the learned one;
  - a group is judged with the learned number;
  - the compose bar refuses a group send while off;
  - the capability is claimed.
- **The contract, both sides:** `sms_contract.json`'s new `policy` and `report` rows, encoded and decoded exactly, and
  the copies compared.
- **The server:**
  - the policy carries `sms_groups` (the snapshot);
  - `/calls/sms` with `groups_shown` writes the switch, and without it keeps the stored value;
  - the switch is disabled without `sms_groups_v1`;
  - `own_learned` is sanitized (a bad number dropped, at most two in the conflict);
  - the learned and conflict rows render with their sources;
  - "Bruk dette nummeret" for `learned` and `conflict:<n>` stores the reported number into the first free slot;
  - the blocked group row says "Gruppemeldinger er slått av" while off, and the number line while it is missing;
  - the missing-number lines are silent while groups are off.

**After this revision:** a delta QA if the user wants one, then the build (steps 1 and 2; step 3 after 3b's fix round)
and the code QA.

## 9. Open questions for the user

None. The user's answers (2026-11-09):
- the four labels are kept;
- the emergency menu goes under Avansert, with its numbers on Avansert's row;
- the default Message button goes under Avansert;
- the phone's number: SIM, then learned from a message, then typed (§4.4);
- a "Gruppemeldinger" switch under Avansert, on by default (D13). On keeps today's behaviour, and design 77 D12's
  members rule already admits only the kid's own texting contacts, so off isn't proposed as the default;
- a contact's Avansert comes back as it was after any save (D9, which settles QA L3).

## 10. Revision 2: where each QA finding went

| Finding | Outcome |
|---|---|
| **H1** the list hides a split contact's direction | Fixed: D6 names both directions in the row's two lines; the test is rewritten |
| **H2** Add Contact's swap disagrees with `*_shown` | Fixed: D10 keeps `*_shown` equal to the pair's class and only hides the switch, so every Add post resolves to the pair; jstest and server test |
| **M1** an open Avansert stops auto-reload, pull-to-refresh, reconnect | Fixed with option (b): D11, the three scripts count only a shown open `<details>`; a jstest each |
| **M2** the calls row claims things under "Ikke styrt"/"Av" | Fixed: D1's sub-line by mode pair, and no "Bare …" while a channel isn't managed |
| **M3** the read-only SIM number takes away the override | Fixed: §4.5's "Bruk dette nummeret" (stores the reported number, from the report) and "Endre…" |
| **M4** the RCS step goes under Avansert just when needed | Fixed: D7 keeps it under SMS for 14 days after the change while managed, reworded, then in Avansert. A "Gjort" tap was weighed and set aside (it needs a migration) |
| **M5** a missing number is told only at the foot; blocked groups say no why | Fixed: D7's quiet line under SMS, Avansert's orange line, and the blocked group rows' line |
| **M6** "Bruk Matrix-ID-en" becomes invisible and unused | Decided: ticking it also sets `message_app = element` (one bind in step 6's `add_contact`, its fix round or this step 1), with the sub-line saying so; and D2 shows a stored Matrix ID whenever one exists |
| **L1** a stale page can rewrite a split | Fixed: the claim is qualified, and D3 adds the stale-page guard with a test |
| **L2** a refusal redrawn from raw pairs | Fixed: D3 and D5 redraw from the resolved pairs; test |
| **L3** D9 and Q5 disagree | Fixed: D9's behaviour stands (any save comes back as it was), and Q5 is settled by the user's "OK" |
| **L4** the lock-text switch hidden under Avansert | Fixed: D8's row names it while on |
| **L5** a stored slot for an inactive SIM can't be cleared | Fixed: §4.5 draws every stored slot; test |
| **L6** Q4's fallback cost; a contact numbered as the phone's own | The old Q4 fallback (a per-message guess) is dropped. §4.4's decided rule learns once, only from an allowed contact's one-to-one MMS, never a contact's number, and fails closed on a conflict. The guard is in D5: the phone's own number (stored, reported or learned) is refused as a contact and left out of the family list |
| **L7** words | Fixed: `t_count` pairs for the names and the emergency count; two keys for "Kontakten"/"kontakten"; the en finding |
| **L8** a save in Avansert shifts the tapped switch | Fixed: D4's "Tilpasset" row is one line with no sub-line; the direction is in Avansert and the list row. It is also on the browser pass's list |
| **N1** "Tilpasset" in orange | Fixed: the second-label colour (the mockups too) |
| **N2** "først" after the switch | Fixed: "… hvis du ikke har gjort det" |
| **N3** "one tap" in D5 | Fixed: D5 names the one-way switch |
| **N4** an unknown `*_shown` | Fixed: D3 reads it as absent; test |
| **N5** pictures with no texts | Fixed: D1's sub-line |
| **N6** an emergency-number contact in D6 | Fixed: D1 and D6 read its texts from `sms_out` alone |

**Decided by the user after the QA** (2026-11-09):
- **The phone's number** is the SIM's, else learned from an approved contact's one-to-one MMS, else typed: §4.4, with
  its security analysis.
- **A "Gruppemeldinger" switch** under Avansert: D13. It adds `call_policy.sms_groups`, so step 3 changes both
  directories (§6, §8).
