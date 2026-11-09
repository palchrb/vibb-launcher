# 80 - A simpler Calls & Contacts page: four plain choices per contact, the rest under "Avansert"

**Asked by the user, 2026-11-09:** "Ang alle sms settings i pwa nå så synes jeg det er mye og rotete alt vi har lagt til
der nå; vi må skrive inn to telefonnr, våre egne, vi må tikke av veldig mange valg og rader nå. Er det noe vi kan
forbedre og strømlinjeformet her? Noe kunne vi kanskje gjemt under advanced, feks 95% av kontaktene man velger at skal
sms og ringe, så skal nok det gjelde både inn og ut fra tlf. Bare en tanke".

**Status:** design only, not approved. Mockups: `docs/design/80-mockups.html` (before and after at 390 px, light and
dark). Server and template only: **no policy key, no contract file, no migration and no launcher change** (§6; one
optional launcher change is an open question, §9 Q4).

**Read with:** design 77 (D2 the channel modes, D3 `sms_in`/`sms_out`/`mms`, D12 the own numbers, D16 held messages,
D17 sender names, §7 the PWA, §8.5 RCS, §14 the kid's contact request), design 31 (§5.1 a save never jumps, §5.2
without JS, §5.6 forms that write every field), `docs/notes/server/calls-and-contacts.md`.

## 0. The shape

- **A contact's form has four switches:** "Kan ringe og ringes", "Kan sende og få meldinger", "Bilder i meldinger" and
  "På Hjem-skjermen". Each two-way switch sets **both** of its flags (`allow_inbound` + `allow_outbound`, `sms_in` +
  `sms_out`).
- **Each contact has its own "Avansert"** with the four one-way switches and the Message button (with the Matrix ID).
- **A contact whose two directions differ shows "Tilpasset"** in place of that switch, with its Avansert open. The
  page never shows a two-way switch that is only half true, and never rewrites a contact by itself.
- **The page keeps on view what needs a decision or an action:** "Meldinger som venter", the kid's requests, the two
  channel modes (Av / Styrt av Vibb / Ikke styrt), the contacts and the emergency menu.
- **One "Avansert" at the page's foot** holds the rest: the default Message button, the lock-screen text switch, the
  phone's own number, the RCS help once SMS is managed, and the sender names.
- **The phone's own number is typed only when the phone can't tell.** The launcher already reads each SIM's number and
  reports it (`sms_state.own_numbers`). The PWA shows that number, and a field appears only for a SIM that doesn't
  know its number. That field is under Avansert, and Avansert's own row says when the number is missing.
- **Add Contact uses the same four switches**, also when it is opened from a held message (D16) or from a kid's
  request (§14).

## 1. Today (verified against `main` 23a92f3a, and `sms-contact-request` 58b7f07c for step 6)

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

So: **6 switches, 1 select and 1 text field per contact**, plus the photo and Remove.

### 1.3 Add Contact

Name and number, then the same **6 switches** (all on; only "Kan sende melding til" from a held message, R2-M1; on
step 6's branch, §14's defaults and "Bruk Matrix-ID-en").

### 1.4 The count

A phone with SMS "Styrt av Vibb" and five contacts:

| | Today | After this design (on view) | After (under Avansert) |
|---|---|---|---|
| Page-level settings | 2 segmented, 1 select, 1 switch, **2 number fields**, the names card, the emergency menu with 2 suggestions | 2 segmented, the emergency menu | 1 select, 1 switch, 0-2 number fields (§4), the names |
| Groups before the contacts | 4-5 (emergency, suggestions, settings, SMS, names) | 1 (the channels) | - |
| One contact's form | 6 switches + 1 select + 1 field = **8** | **4** switches | 4 one-way switches + 1 select (+ the Matrix ID only for Element) |
| Five contacts | **40** | 20 | - |
| Add Contact | 2 fields + **6** switches | 2 fields + **4** switches | 4 one-way switches |

What the parent has to look at to add an ordinary contact: today 8 rows to read and 6 switches to judge; after, 4
switches already right for the 95 % case. Nothing is removed: every control of today is still on the page, in the same
form or route (§6).

## 2. Decisions: a contact's form

**D1. Four plain choices** (in this order):

| Row (nb / en) | Field(s) | On means |
|---|---|---|
| "Kan ringe og ringes" / "Calls both ways" | `allow_inbound` and `allow_outbound` | the contact may call the kid, and the kid may call the contact (the phone book) |
| "Kan sende og få meldinger" / "Texts both ways" | `sms_in` and `sms_out` | texts both ways |
| "Bilder i meldinger" / "Pictures in texts" | `mms` | unchanged |
| "På Hjem-skjermen" / "On the home screen" | `show_on_home` | unchanged |

- **"På Hjem-skjermen" while the kid can't call the contact** (`outbound` off) has no effect on the phone
  (`CallRules.kt`: `outbound && showOnHome`). The row keeps its switch and value, with the sub-line "Vises når {kid}
  kan ringe {contact}" / "Shows when {kid} can call {contact}".
- **While SMS isn't "Styrt av Vibb",** the texts and pictures rows get the sub-line "Gjelder når SMS er styrt av
  Vibb" / "Applies while SMS is managed by Vibb". Their values are kept and sent as today. The calls row gets none,
  because `outbound` also decides the phone book while calls aren't managed (design 77 D2).
- **An emergency-number contact** (`emergency::is_emergency`) can never text in (R2-H1). Its texts row reads "{kid}
  kan sende melding til {contact}" and maps to `sms_out` alone, with today's reason as its sub-line. It is never
  "Tilpasset" because of `sms_in`.

**D2. "Avansert" per contact:** a `<details class="adv">` row inside the contact's body, under the four switches and
above the photo rows. Opened, it shows:

| Group | Row (nb / en) | Field |
|---|---|---|
| Samtaler | "{contact} kan ringe {kid}" / "{contact} can call {kid}" | `inbound` |
| | "{kid} kan ringe {contact}" / "{kid} can call {contact}", sub "I telefonboken" / "In the phone book" | `outbound` |
| Meldinger | "{contact} kan sende melding til {kid}" / "{contact} can text {kid}" | `sms_in` (disabled for an emergency number, with the reason) |
| | "{kid} kan sende melding til {contact}" / "{kid} can text {contact}" | `sms_out` |
| - | "Meldingsknapp" / "Message button" | `message_app` (the same picker, as a stacked row: at 390 px its label and "Telefonens standard (SMS)" don't fit on one line inside Avansert) |
| - | "Matrix-ID" | `message_address`, **only while the button opens Element X** (the contact's choice, or the phone's default when it has none) |

- Both names are in each one-way label, so the direction can't be misread. Add Contact has no contact name yet, so
  it says "Kontakten" / "kontakten" ("The contact" / "the contact").
- **The Avansert row says what it hides** (a closed Avansert never hides a value that isn't the default): its value is
  "Tilpasset" when a channel is split, else the Message button's choice when the contact has its own ("Element X",
  "Signal / Molly", "Ingen meldingsknapp", "SMS"), else nothing.
- **It is rendered open** when:
  - a channel is "Tilpasset" (D4);
  - the button opens Element X and the Matrix ID is empty: the button can't work, and the row says "Mangler
    Matrix-ID" / "Matrix ID missing";
  - a Matrix ID was refused (the 400 render);
  - the save came from inside it (`more`, D9).
- **Hiding the Matrix ID field when the button isn't Element** loses nothing. The stored address stays stored and is
  posted as a hidden field with its value. Today an address typed while the button is SMS is kept and unused, and
  that stays so.

**D3. How a two-way switch maps to its two flags** (the one rule this design adds, a pure function
`calls::both_ways`, with a test table). For each channel the form posts:
- `calls_shown` / `texts_shown` (hidden): what the main row showed when the page was drawn, `on`, `off` or `custom`;
- `calls` / `texts`: the two-way switch, only when it is drawn (`on`/`off`);
- the pair from Avansert: `inbound`/`outbound`, `sms_in`/`sms_out`. Avansert is inside the same form, so the pair is
  always posted, opened or not.

| `*_shown` | two-way switch in the post | the pair stored |
|---|---|---|
| `on` | on (unchanged) | **the pair as posted**: a one-way switch changed in Avansert is taken |
| `on` | off (the parent turned it off) | **both off** |
| `off` | on (the parent turned it on) | **both on** |
| `off` | off (unchanged) | **the pair as posted** |
| `custom` | not drawn | **the pair as posted** |
| absent (an older page, an old tab) | - | **the pair as posted**: today's behaviour, unchanged |

- With autosave, every save carries one change, so one row of the table applies. Without JS (the `<noscript>` Save), a
  parent who changed both the two-way switch and its pair in one save gets the two-way switch: the larger, visible
  change wins. This is the documented rule, with a test.
- Then, as today: `sms_in` is never stored for an emergency number, `show_on_home`, `mms`, `message_app` and
  `message_address` are written from the post, and every field of the form is written (design 31 §5.6's "write all"
  holds: all fields are still in one form).
- The page shows the two-way switch as `on` when both flags are on, `off` when both are off, and **"Tilpasset"**
  (`custom`) otherwise. For an emergency-number contact the texts row follows `sms_out` alone (D1).
- **No `v=2` marker is needed:** an older page has no `*_shown` and lands in the last row, which is exactly today's
  rule.

**D4. Mixed flags show truthfully.** A channel whose two directions differ:
- shows in the main part as a value row, **not a switch**: "Kan ringe og ringes · Tilpasset", with the sub-line saying
  which way works ("Bare Mormor kan ringe Emma" / "Only Mormor can call Emma"; "Bare Emma kan sende melding til
  Mormor"; and so on);
- opens Avansert, where the one-way switches show the truth and change it;
- is never rewritten by the page. Loading, saving another field or saving the other channel leaves its pair as stored
  (D3's `custom` row). It becomes a two-way switch again when the parent sets both directions the same in Avansert.

The migration of design 77 (`sms_in = inbound`, `sms_out = outbound`) left a contact that only receives calls (Kan
ringe on, I telefonboken off) as "Tilpasset" for both channels. Such contacts show as they are; nothing is converted.

**D5. Add Contact** has the same shape: name, number, the four switches, an Avansert inside the form with the one-way
switches (the Message button and the Matrix ID are set after adding, as today). It posts `switches=on` as today, plus
D3's fields; D3 resolves the pairs. The three ways in:

| Way in | Calls | Texts | Pictures | Home | Avansert | Extra |
|---|---|---|---|---|---|---|
| "Legg til kontakt" | on | on | on | on | closed | - |
| "Opprett kontakt…" from a held message (D16, R2-M1: "an allow grants exactly one thing") | off | **Tilpasset**: only "Kontakten kan sende melding til {kid}" | off | off | **open** | - |
| "Legg til…" from a kid's request (§14) | on | on | off | off | closed | the kid's Matrix ID as today: "Bruk Matrix-ID-en" with the suggestion as its sub-line, unticked, in the main part (it needs a decision) |

- From a held message the parent sees exactly what is granted, and turning "Kan sende og få meldinger" on is one tap
  if the parent wants more. That tap is the parent's choice, never a default.
- A refused Add (400) renders with what was posted, the two-way rows recomputed from the posted pairs.
- **Add from the family** stays as it is: every flag on, the contact's name, number and photo.

**D6. The contact row** (the list, closed) says it in one line, with a second only when needed:

| Calls | Texts | Value (nb / en) |
|---|---|---|
| both | both | "Anrop og meldinger" / "Calls and texts" |
| both | none | "Bare anrop" / "Calls only" |
| none | both | "Bare meldinger" / "Texts only" |
| none | none | "Ingen anrop eller meldinger" / "No calls or texts" |
| either split | | "Tilpasset" / "Custom" |

Under it, when any applies: "Hjem" (`outbound && show_on_home`, as today) and "uten bilder" / "no pictures" (texts on
in either direction and `mms` off). Today's two lines ("Begge veier · Hjem" and "Melding begge veier") go.

## 3. Decisions: the page

**D7. The order:**
1. the warnings (unchanged);
2. **Meldinger som venter** with Blokkerte meldinger (unchanged, needs action);
3. **Forespørsler** (step 6, unchanged, needs action);
4. **the channels**: Samtaler and SMS as today's segmented control with their sub-lines, the status rows (waiting,
   Anropsfiltrering, the MMS rows) and the footer's ⓘ. The Message button picker leaves this group (D8).
   - **The RCS step** stays here while it is a setup step: "Før du velger Styrt av Vibb: slå av chatfunksjoner i
     Meldinger." with its ⓘ, as today, while SMS isn't managed on a phone that could be.
   - Once SMS is "Styrt av Vibb", today's permanent "Slå av chatfunksjoner i Meldinger først." moves to Avansert as
     the row "Chatfunksjoner (RCS)", which keeps the same ⓘ and Google's form.
5. **Kontakter**, moved up from the bottom (the page's main work);
6. **Nødmeny**, moved down from the top, unchanged (design 73's rows, Legg til nummer…, Forslag). It works
   without a touch (112 alone), so it doesn't need to come first; it stays on view because it is about safety (§9 Q2);
7. **Avansert**, a `details.section` row at the foot, closed (D8).

**D8. The page's Avansert.** Its summary row says what it holds and what is set:

- the title "Avansert" / "Advanced";
- the sub-line, from the stored state: "Meldingsknapp: SMS · 2 avsendernavn" (the default button's name, and the names
  count while there is one);
- **in orange, only while it matters**: "Telefonens nummer mangler - gruppemeldinger blokkeres" / "The phone's number
  is missing - group texts are blocked", while SMS is "Styrt av Vibb" and some active SIM has no number from the SIM or
  the parent (§4).

Opened:

| Group | Rows | Form (route) |
|---|---|---|
| Meldinger | "Meldingsknapp" - the phone's default (today's select) | `/calls/settings` with `section=button` (§5.6, below) |
| | "Vis meldingstekst på låseskjermen" | `/calls/sms` (unchanged form) |
| | "Telefonens nummer", per SIM (§4) | `/calls/sms` |
| | "Chatfunksjoner (RCS)" with its ⓘ, while SMS is managed | - |
| Avsendernavn som slipper gjennom | the names, Fjern, "Legg til navn…", D17's warning under it | `/calls/names`, `/calls/names/remove` (unchanged) |

The group footer "Dette gjelder når SMS er styrt av Vibb." stays. "Tillat avsendernavnet…" on a held row is still the
usual way a name gets on the list, so the list itself is maintenance.

- **Splitting `/calls/settings`** (design 31 §5.6: a form is split only with a section marker, and its handler then
  writes only that section):
  - `section=channels`: `calls_mode` and `sms_mode`, a missing or unknown mode a 400 as today;
  - `section=button`: `default_message_app` alone;
  - no marker (an older page, the tests): today's write-all.
  - Each section gets the "saving one leaves the other unchanged" test.

## 4. The phone's own number (D12): what it is for, what the phone knows, and the answer

**What it is for.** One thing only: D12's group rule (`MmsGate.group`, step 3b on `sms-mms`). An MMS with more than one
recipient address is a group. To accept it, the phone must tell "us" from the other members. Every other member must
be a contact with a text switch on, or the group is blocked with no allow path. With no own number at all, every
message addressed to more than one address is blocked (counted as `group`). One-to-one SMS and MMS don't need it. A
group "SMS" from an iPhone or Google Messages goes out as MMS, so this is all group texting.

**What the phone already does** (launcher, verified):
- `SmsRuntime.ownNumbers` reads `SubscriptionManager.getPhoneNumber(subId)` for each active SIM and reports it,
  normalised, as `sms_state.own_numbers: [{slot, number | null}]`.
- `MmsGate.ownNumbers` uses the parent's number for a slot and **the SIM's number for a slot the parent left empty**.
  So a phone whose SIM knows its number needs nothing typed, today.
- `READ_PHONE_NUMBERS` is requested in the manifest and held as a Device Owner grant (`OwnPermissions.kt`, fixed
  `POLICY_FIXED` so a role hand-back can't take it).
- The PWA shows a mismatch between a typed number and the SIM's ("Nummeret i Vibb stemmer ikke med SIM-kortet").

**How reliable the SIM's number is:**
- `getPhoneNumber(subId)` (API 33; the launcher's minSdk is 34) returns the first non-empty of three sources: the
  carrier's (`setCarrierPhoneNumber`, which only a carrier-privileged app can set), the SIM's MSISDN record (the old
  `getLine1Number`), and IMS: the number in the network's IMS registration (the P-Associated-URI), filled while
  VoLTE/VoWiFi is registered. [verify on device: the order of the three]
- **Norwegian SIMs often leave the MSISDN record empty**, so the UICC source is unreliable here.
- **The IMS source is the likely one:** Telia, Telenor and Ice run VoLTE, and an IMS registration usually carries the
  subscriber's `tel:` URI. Whether the Jelly Star registers IMS on each carrier (VoLTE on a niche phone depends on the
  carrier's configuration for it), and whether the URI holds the number, is **unknown until step 0**. Design 77's step
  0 already lists "the own number on each SIM"; this design adds "and from which source"
  (`getPhoneNumber(subId, PHONE_NUMBER_SOURCE_IMS)` and `_UICC` separately).
- **A received SMS can't tell it.** An SMS-DELIVER PDU carries the sender (TP-OA) and no recipient: the destination
  address exists only in the sender's SMS-SUBMIT.
- **A received MMS's `To` is no source either.** The MMSC delivers by the kid's subscription, but `To`/`Cc` are
  written by the sender. Learning "us" from them would let any allowed sender (or a spoofer through the real MMSC)
  teach the phone that a stranger is "us", which is D12's hole in another form.

**The answer:**
- The PWA asks only when the phone can't tell. For each active SIM in the phone's last report:

| SIM reports a number | Parent's number for the slot | Avansert shows |
|---|---|---|
| yes | none | a read-only row "Telefonens nummer · +47 912 34 567", sub "Fra SIM-kortet" / "From the SIM card". No field |
| yes | the same | the same row (the stored copy is harmless) |
| yes | a different one | the field with the parent's number, and today's mismatch finding above the groups |
| no (`null`) | none | the field "Telefonens nummer", hint "SIM-kortet vet ikke nummeret sitt. Uten det blokkeres gruppemeldinger." / "The SIM card doesn't know its number. Without it, group texts are blocked." |
| no | one | the field with it |

- **One field per active SIM, not two.** "SIM 1" / "SIM 2" is said only on a phone that reports two. With no report
  yet (an older launcher, a new phone), one field for SIM 1, plus SIM 2's only if one is stored.
- **The field lives under Avansert.** The group rule can live without it: only group MMS are lost, and they are
  blocked (no allow path, nothing reaches the kid). So it isn't needed at setup, and Avansert's own row says so in
  orange while it matters (D8). The device page and Attention get nothing new: this is not an alarm.
- **`/calls/sms` writes only the slots it was sent.** An own-number field the page didn't draw (a SIM that knows its
  number) is absent from the post, and that slot's row must stay. An empty field still removes its slot. Today the
  handler deletes every slot and writes the posted ones; it becomes "for each `own_number_N` in the post: set or
  remove slot N". It gets the "an absent field keeps its slot" test.
- **The finding's words move with it:** "Sjekk Telefonens nummer under SMS" becomes "under Avansert".
- **No insecure fallback is added.** A fallback that guesses "us" exists (§9 Q4) and is not proposed for building now.

## 5. Where saves return, and what stays open (design 31 §5.1)

**D9.**
- **A contact's save** returns to `?open=<id>#contact-<id>` as today, plus `&more=<id>` when the form posted `more=1`.
  - `more` is a hidden field in the contact's form. A few lines of script (`static/contact-form.js`, D10) set it to
    `1` when that contact's Avansert is opened and clear it when it is closed. So a switch flipped inside Avansert
    comes back with Avansert open.
  - Without JS the field keeps the value it was drawn with: Avansert comes back as it was drawn (open when D2 says
    so). Nothing depends on the script for what is saved.
- **The page's Avansert forms** (`/calls/sms`, `/calls/settings` with `section=button`, `/calls/names`,
  `/calls/names/remove`) always return with `?advanced=1`: to `#sms-settings`, `#message-button` and `#sms-names`.
  The template draws Avansert open with `advanced=1`. They sit only inside it, so no script is needed.
- **A refusal (400) inside an Avansert** renders it open: a Matrix ID, an own number, a name. The table test
  `every_form_on_the_calls_page_returns_to_its_place` gains the new targets.
- **Unchanged:**
  - Add Contact → `#contact-<new>` (its Avansert open when D2 says so);
  - "Opprett kontakt…" → `?add_number=…#add-contact`;
  - "Legg til…" → `?request=<id>#add-contact`;
  - the held and request rows' own anchors;
  - Remove → `#contacts`;
  - the emergency rows.
- **scroll-restore** keeps the place on every save as today: every new target is inside the form's own ancestors.

**D10. The small script** `static/contact-form.js`, ES5, loaded on the calls page beside `photo-crop.js`. It changes
nothing that is saved:
- it keeps a contact's `more` field in step with its Avansert (D9);
- in Add Contact (no autosave there), flipping a two-way switch sets its two one-way switches in Avansert, and a
  one-way change that splits a channel replaces the two-way switch with "Tilpasset". D3 resolves the post the same way
  with or without it.
- Tested with node like `autosave.js` (`jstest/contact-form.test.js`).

## 6. What doesn't change

- **The policy, the phone and the contracts:** `call_policy` keys and values, `policy_json_keys_snapshot`,
  `PolicyResponseCompatTest`, `sms_contract.json`, the launcher's rules (D4/D5), the group rule. A two-way switch
  writes the same two columns a parent could set today with two switches.
- **The routes**, except the additions: `/calls/settings`' `section`; `/calls/sms`' per-slot write;
  `/contacts/{cid}`' and `/contacts`' `*_shown` fields and `more`. No route is renamed (CSRF and the security-log
  lines stay).
- **Every capability:** every switch, picker and field of §1 is still on the page, in the same form and column. Every
  restriction stays liftable from here: the channel modes are on view, and every contact's switches in its form.
- **Autosave:** each switch and picker saves its form on change (`data-autosave`), and each form keeps its
  `<noscript>` Save.
- **i18n:** every new word is in `en.json` and `nb.json` (§7). `i18n::tests` and `tests::i18n_pages` cover them.
- **The device page's rows and Attention** (the "Meldinger" summary row, the findings): unchanged, except the
  own-number finding's words (§4).

## 7. Words (nb / en; new keys only, the build names them)

| Where | nb | en |
|---|---|---|
| contact | Kan ringe og ringes | Calls both ways |
| contact | Kan sende og få meldinger | Texts both ways |
| contact | Tilpasset | Custom |
| contact, custom | Bare {contact} kan ringe {kid} / Bare {kid} kan ringe {contact} | Only {contact} can call {kid} / Only {kid} can call {contact} |
| contact, custom | Bare {contact} kan sende melding til {kid} / Bare {kid} kan sende melding til {contact} | Only {contact} can text {kid} / Only {kid} can text {contact} |
| contact | Vises når {kid} kan ringe {contact} | Shows when {kid} can call {contact} |
| contact | Gjelder når SMS er styrt av Vibb | Applies while SMS is managed by Vibb |
| Avansert | {contact} kan ringe {kid} / {kid} kan ringe {contact} | {contact} can call {kid} / {kid} can call {contact} |
| Avansert | {contact} kan sende melding til {kid} / {kid} kan sende melding til {contact} | {contact} can text {kid} / {kid} can text {contact} |
| Avansert | Mangler Matrix-ID | Matrix ID missing |
| Add Contact | Kontakten / kontakten | The contact / the contact |
| row | Anrop og meldinger · Bare anrop · Bare meldinger · Ingen anrop eller meldinger | Calls and texts · Calls only · Texts only · No calls or texts |
| row | uten bilder | no pictures |
| page | Avansert | Advanced |
| page | Meldingsknapp: {app} · {count} avsendernavn | Message button: {app} · {count} sender names |
| page | Telefonens nummer mangler - gruppemeldinger blokkeres | The phone's number is missing - group texts are blocked |
| page | Fra SIM-kortet | From the SIM card |
| page | SIM-kortet vet ikke nummeret sitt. Uten det blokkeres gruppemeldinger. | The SIM card doesn't know its number. Without it, group texts are blocked. |
| page | Chatfunksjoner (RCS) | Chat features (RCS) |

Existing keys stay (`device.sms.pictures`, `device.calls.on_home`, `device.calls.matrix`, `device.calls.phone_book` as
the sub-line). Keys nothing uses any more go (`device.calls.both_ways`, `in_only`, `out_only`, `no_calls`,
`texts_*`, `device.sms.messages`, `device.calls.can_call`, `device.sms.texts_in`/`texts_out`). `i18n::tests` finds
unused keys.

## 8. Steps and tests

One server round, **M**, in two commits:

1. **The contact form and Add Contact.**
   - `calls::both_ways` (D3) and the `*_shown` fields in `update_contact` and `add_contact`;
   - the template's four switches, the "Tilpasset" row, the per-contact Avansert with its open rule and its row value
     (D2), the Matrix ID only for Element;
   - the three Add Contact ways in (D5), the row summary (D6), `more` and `static/contact-form.js`.
   - **Coordination with step 6** (`sms-contact-request`, which also changes Add Contact and `add_contact`): build
     this on main **after** step 6's server part merges, and move §14's "Bruk Matrix-ID-en" row into the new form. If
     step 6 is held back, the order flips: step 6 rebases onto this.
2. **The page.**
   - The new order (D7) and the page's Avansert (D8);
   - `/calls/settings` split by `section`; `/calls/sms` writing only the slots it was sent;
   - the own-number rows from `sms_state.own_numbers` (§4), the RCS row once managed, the `advanced=1` returns (D9);
   - the finding's words.

**Tests** (lean, server only; `src/tests/calls.rs`, `sms_page.rs`, `calls::tests`):
- `both_ways`: every row of D3's table, and the emergency number's `sms_in`.
- `update_contact`:
  - a two-way switch turned off stores both off;
  - a one-way change under `on` stores the pair;
  - a `custom` channel keeps its pair when the other channel or `mms` is saved;
  - a post without `*_shown` is today's behaviour.
- Rendering:
  - a mixed contact shows "Tilpasset", no two-way switch for that channel, and Avansert open;
  - an equal contact shows the switch and Avansert closed;
  - the Avansert row's value for a contact with its own Message button;
  - "Mangler Matrix-ID".
- Add Contact:
  - each way in renders D5's defaults;
  - posting the held prefill unchanged stores exactly `sms_in`;
  - the request prefill stores §14's flags, and the Matrix ID only when ticked.
- `/calls/settings`: `section=channels` keeps `default_message_app`; `section=button` keeps both modes; no section is
  today's.
- `/calls/sms`: an absent own-number field keeps its slot, an empty one removes it, a refused one renders with
  Avansert open.
- Own number: a SIM-reported number shows the read-only row and no field; `null` shows the field and Avansert's orange
  line while SMS is managed; a mismatch shows the field and the finding.
- `every_form_on_the_calls_page_returns_to_its_place`: every new target. `tests::shell`: every new form lands on its
  own group. `i18n_pages::nb_pages_have_no_english` with a mixed contact and Avansert open.
- `jstest/contact-form.test.js`: `more` follows the toggle; the Add form's mirror.
- **The browser pass** (an agent, before QA): 390 px light and dark against the mockups, a save inside each Avansert
  coming back open and in place, iOS Safari and Chrome on Android.

**After this design:** QA of the design (Opus), then the build, code QA, and the user's look at the mockups and the
live page.

## 9. Open questions for the user

1. **Are the four words right?** "Kan ringe og ringes", "Kan sende og få meldinger", "Bilder i meldinger", "På
   Hjem-skjermen". Shorter alternatives: "Ringe", "Meldinger", "Bilder", "Hjem-skjermen", with "begge veier" as the
   sub-line.
2. **The emergency menu:** below the contacts and on view (proposed), or under Avansert as well? It works untouched
   (112), but it is about safety.
3. **The default Message button under Avansert** (proposed), or on view under the channels? It matters at setup for a
   family that uses Element or Signal for everyone.
4. **Group texts without the phone's number:** keep D12 strict (proposed), so every group MMS is blocked until the
   number is known, or add a launcher fallback?
   - **The fallback:** with no own number, a group with **at most one** address that is neither the sender nor a texting
     contact takes that address for the phone's own.
   - **Its cost:** the kid can be in a group with one hidden stranger. The stranger never gets the kid's replies (a
     reply goes to the conversation's members), and the stranger's own messages still wait for the parent.
   - It changes the reviewed `MmsGate.group` and needs its own QA. Step 0 may make it moot (if the SIMs report their
     number over IMS), so the proposal is to decide after step 0.
5. **Should the contacts' Avansert remember being open** across a save from the main switches? The proposal: no, only a
   save from inside it, and a "Tilpasset" contact, bring it back open.
