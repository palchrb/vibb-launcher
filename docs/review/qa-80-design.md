# Design QA: design 80 - a simpler Calls & Contacts page (`docs/design/80-calls-contacts-simpler.md`)

Checked against `design-80-calls-simpler` (691c04d2, on `main` 23a92f3a): the note and `80-mockups.html`;
`server/templates/device_calls.html`; `server/src/handlers/calls.rs` (`contact_summary`, `texts_line`, `load_page`,
`save_settings`, `add_contact`, `update_contact`, `sms_findings`); `calls_sms.rs` (`load`, `save_sms`);
`static/autosave.js`, `scroll-restore.js`, `auto-reload.js`, `pull-refresh.js` and `reconnect.js`;
`docs/notes/server/calls-and-contacts.md`; design 77 D2, D3, D12, D16, D17, §6.8, §8.5 and §14; design 31 §5.6;
migrations 0022 and 0130; the launcher's `CallRules.kt` on `main` and `sms-mms`; on `sms-mms` (a2c53603)
`SmsRuntime.ownNumbers`, `SmsRuntime.report`, `MmsGate.group`, `MmsGate.ownNumbers`, `MmsFlow` (the group drop),
`OwnPermissions.kt` and the manifest; and on `sms-contact-request` (58b7f07c) the diff to `calls.rs` and the template.
No build, no browser.

**Verdict: the right shape for the user's request.** Most contacts get four plain switches, the rare cases go under
Avansert, and the own number is asked for only when the phone can't tell. The core rule, D3, is sound for the
contact form with autosave. Every save there is a full `form.submit()` navigation, so `*_shown` is fresh after each
save. Older pages land on today's behaviour. The `/calls/settings` split follows design 31 §5.6. The own-number
analysis is correct: no SMS PDU carries the recipient, and learning "us" from MMS `To` is rightly rejected.

The problems:
- The closed contact list stops saying which way a split contact goes. Today it says "Bare innkommende"; the design
  says only "Tilpasset" (H1).
- In Add Contact, the script's "Tilpasset" swap and `*_shown` disagree. Narrowing one direction then stores both
  directions off (H2).
- A "Tilpasset" contact's Avansert is drawn open inside a closed row. That stops three page-wide scripts on every
  page with such a contact: auto-reload, pull-to-refresh and reconnect (M1).
- Several setup items get less visible: RCS once SMS is managed, and the missing own number (M4, M5). The read-only SIM
  number takes away the parent's override (M3). Step 6's accepted Matrix ID becomes invisible (M6).

Counts: **0 Critical, 2 High, 6 Medium, 8 Low, 6 Nit.**

## What holds (verified)

- **§1's inventory is right.** It lists the page's groups and order, the contact form (6 switches, the picker and an
  always-shown Matrix ID), and Add Contact's six switches with `switches=on`. `HELD_ADD_SWITCHES` is sms_in alone. On
  `sms-contact-request`, `REQUEST_ADD_SWITCHES` is `[true, true, false, true, true, false]`, which matches D5's request
  row. The row's two lines are today's `contact_summary` + `texts_line`.
- **Autosave is a full navigation.** `autosave.js` calls `form.submit()`, so the page is drawn again after every
  change, and `calls_shown`/`texts_shown` always describe the state the parent is looking at. In a quick
  double tap, the second submit carries the whole DOM, the first tap's change included, so one consistent row of the
  table applies.
- **Controls in a closed `<details>` are posted.** The pair is always in the post, as D3 says, and the hidden Matrix
  ID keeps `update_contact`'s write-all.
- **An older page** posts no `*_shown`. It lands on "the pair as posted", which is exactly today's `update_contact`.
  An older Add Contact (`switches=on`, six switches) is unchanged too.
- **The `/calls/settings` split** follows design 31 §5.6: a marker, write-only-that-section, no marker means write-all,
  and a test per section.
- **The own numbers (§4):**
  - `MmsGate.ownNumbers` takes the parent's number per slot and else the SIM's.
  - `MmsGate.group` blocks every group when no own number is known. A recipient set of one is `OneToOne`.
  - `READ_PHONE_NUMBERS` is in the manifest and `OwnPermissions`. minSdk is 34. The mismatch finding exists
    (`Finding::OwnNumberMismatch`, "Nummeret i Vibb stemmer ikke med SIM-kortet").
  - An SMS-DELIVER carries no recipient, and MMS `To`/`Cc` are sender-written. Rejecting a learned "us" is right: it
    would be D12's hole again.
- **D1's Home sub-line** matches `CallRules.homeContacts` (`outbound && showOnHome`).
- **Scroll position.** `scroll-restore.js` keys its record by path, so `?advanced=1` and `&more=` don't break it.
  Every return target D9 names is an ancestor of its form.
- **i18n.** Every new word in §7 has nb and en. The keys to drop are used only where the design replaces them.

## Findings

**H1 High - The contact list hides which way a split contact goes.** (D6, D4)
- *Today:* the closed row says "Bare innkommende" / "Bare utgående" and "Melding inn" / "Melding ut". The mockup's
  "Før" shows Mormor as "Bare innkommende · Melding inn".
- *After:* D6 shows "Tilpasset" for any split. Which way it goes (D4's "Bare Mormor kan ringe Emma") is only in the
  opened form.
- *Why it matters:* a contact who may call the kid but isn't in the kid's phone book is exactly the case a parent
  scanning the list must see. Design 77's migration made every "Bare innkommende" contact split on both channels, so
  this is common, not rare. It also breaks the note's own §0 promise that every state shows as it is.
- *Fix:* keep D6's short words for the equal states. For a split channel, name the direction in the row itself:
  - calls: "Ringer inn" / "Ringer ut" ("Calls in" / "Calls out"), or reuse today's `in_only`/`out_only`;
  - texts: "Melding inn" / "Melding ut";
  - for example "Ringer inn · Melding inn" for Mormor.

  "Tilpasset" can stay as the label inside the form and on its Avansert row. Rewrite
  `a_contact_row_says_which_way_calls_go` for the new table instead of deleting it.

**H2 High - Add Contact's script swap disagrees with `*_shown`, and narrowing one direction stores both off.** (D3,
D5, D10)
- *The mechanism:* a checkbox posts `on` or nothing. In D3's table, "two-way switch off" is therefore the *absence* of
  `calls`/`texts`, read against `*_shown`. D10's Add Contact script replaces the two-way switch with "Tilpasset" when a
  one-way change splits the channel. If that removes or disables the input and leaves `calls_shown=on` (the form's
  default), the post reads "on → off" and stores **both off**.
- *Example:* the parent wanted Mormor to call Emma without being in the phone book. Mormor can't call at all, and
  nothing says so.
- *It also fails with the input kept:* the parent first flips the two-way switch off (the script turns the pair off),
  then turns `outbound` on in Avansert. The post has no `calls`, `calls_shown=on`, and the server stores both off again.
- *The claim:* D10's "D3 resolves the post the same way with or without it" holds only if the script keeps `*_shown`
  in step. Without JS it does hold, because nothing is swapped.
- *Fix:* specify the script:
  - When it swaps in "Tilpasset", it sets that channel's `*_shown` to `custom`.
  - When the pair becomes equal again and it draws the switch, it sets `*_shown` and the switch to the pair's value.

  With that, every Add Contact post resolves to "the pair as posted", as without JS. Add the cases to
  `jstest/contact-form.test.js`, plus a server test that `calls_shown=custom` with no `calls` stores the pair.

**M1 Medium - An Avansert drawn open stops auto-reload, pull-to-refresh and reconnect for the whole page.** (D2, D4)
- `auto-reload.js:66`, `pull-refresh.js:98` and `reconnect.js:649/806` all refuse while
  `doc.querySelector("details[open]")` matches anything. D2 draws a contact's `<details class="adv">` open for every
  "Tilpasset" contact and every "Mangler Matrix-ID", even while the contact's own row is closed, so the open element
  isn't visible.
- *Effect:* on any phone with one split contact (common after design 77's migration, H1), the Calls page's "waiting
  for the phone" refresh after a channel change never runs. Pull-to-refresh and the reconnect reload silently do
  nothing.
- *Fix, either:*
  - (a) the server sets `open` on a contact's Avansert only while that contact's row is drawn open (`?open=`, a
    refusal, `more`), and a small script opens Avansert when the parent opens a split contact; or
  - (b) the three scripts count only a *visible* open `<details>` (no closed `<details>` ancestor), with a jstest each.

  (b) is the more general fix. Say which, and add a server or jstest case "a split contact on a fresh load doesn't stop
  auto-reload".

**M2 Medium - The calls row claims things the phone doesn't do while calls aren't "Styrt av Vibb".** (D1, D4)
- *What D1 says:* the calls row gets no sub-line because "`outbound` also decides the phone book while calls aren't
  managed". That holds only for calls "Ikke styrt" with SMS restricted.
- *What the launcher does* (`phoneBookView`, `main` and `sms-mms`):
  - both channels "Ikke styrt" is `Unmanaged`: no phone book at all;
  - calls "Av" shows only emergency-number contacts, and nobody can call.
- *What the page would say:* under "Ikke styrt", D4's sub-line "Bare Mormor kan ringe Emma" is false, because anyone
  can call Emma. Under "Av", an "on" "Kan ringe og ringes" switch is false.
- *Fix:* give the calls row (and D4's "Bare …" lines) a sub-line by the mode, as the texts rows get "Gjelder når SMS
  er styrt av Vibb":
  - "Av": "Samtaler er av" / "Calls are off";
  - "Ikke styrt": "Gjelder når samtaler er styrt av Vibb" / "Applies while calls are managed by Vibb", with, while SMS
    keeps the phone book, "{kid} ser {contact} i telefonboken".

  Never print "Bare …" while the channel isn't managed.

**M3 Medium - The read-only SIM number takes away the parent's override, and may rest on a momentary reading.** (§4)
- *No override.* When the SIM reports a number and none is stored, §4 draws a row with no field. Today the parent
  can type a number for that slot, and it wins (`MmsGate.ownNumbers`). If the SIM's number is wrong, the parent can no
  longer correct it from here. A recycled SIM's old MSISDN record or a stale carrier field would do it. The kid's real
  number then reads as a non-contact "other" and every group is blocked, or a stranger's number is taken for "us".
  §1.4's "Nothing is removed: every control of today is still on the page" doesn't hold for this row.
- *A momentary reading.* By the note's own account the likely Norwegian source is IMS, "filled while VoLTE/VoWiFi is
  registered". The launcher reads `getPhoneNumber` live on every judgement (`SmsRuntime.judge`'s `simNumbers`) and
  caches nothing. A report that happened to carry the number would hide the field, while a group MMS that arrives in a
  gap is dropped (`MmsFlow`: `drop(.., GROUP)`; no hold, no allow path).
- *Fix:* the row "Telefonens nummer · +47 … · Fra SIM-kortet" gets two actions:
  - "Bruk dette nummeret" ("Use this number"): one tap stores the reported number for the slot, so the policy carries
    it whatever the SIM says later. The parent confirms a number the phone told; nothing is typed, so the "typed once"
    goal holds.
  - "Endre…" ("Change…"): opens the field.

  Step 0's "from which source" then decides whether to suggest the tap. Test both.

**M4 Medium - Once SMS is managed, the RCS step goes under Avansert just when its second path is needed.** (D7, D8;
design 77 §8.5)
- §8.5's path 2, Google's deregistration form behind the ⓘ, is "for a phone already locked down". That is the state
  after "Styrt av Vibb".
- Nothing tells the PWA whether RCS was turned off, and a contact's RCS message is lost silently: Messages is hidden
  and the sender sees it delivered.
- A parent who switched without doing the step first loses the only on-view reminder at the moment it matters.
- *Fix:* keep the line and its ⓘ under the SMS row while managed until the parent marks it done (a one-tap "Gjort",
  stored per phone), then move it to Avansert. Or keep it on view for a fixed time after `roles_changed_at`. Reword it
  for after the switch ("Slå av chatfunksjoner i Meldinger" without "først", N2).

**M5 Medium - A missing own number is told only at the page's foot, and the blocked groups don't say why.** (D8, §4)
- *What is lost.* With SMS managed and no own number, every group MMS is dropped for good (M3). For a kid, the class's
  group chat is the commonest group.
- *Where it is told.* Only in the orange line of a closed group under the contacts and the emergency menu. A
  parent who just chose "Styrt av Vibb" at the top has no reason to scroll there.
- *What the blocked list shows.* `MmsFlow` records the drop as `CleanSender(GROUP, "")`, so the list shows a group with
  no sender and no reason.
- *Fix:*
  - While managed and a SIM's number is missing, add one quiet line under the SMS row: "Telefonens nummer mangler -
    gruppemeldinger blokkeres", linking to `?advanced=1#sms-settings`. Keep the Avansert line.
  - Give a blocked group row the reason "Telefonens nummer mangler" while that holds.
  - This is not an Attention alarm, as §4 says.

**M6 Medium - Step 6's "Bruk Matrix-ID-en" becomes invisible and unused under D2's "Matrix ID only for Element".**
(D2, D5, design 77 §14)
- *What step 6 stores.* On `sms-contact-request`, `add_contact` stores the kid's suggested ID in `message_address`
  when ticked, and leaves `message_app` NULL, which means the phone's default (`sms` by migration 0022).
- *What D2 does with it.* It draws the Matrix ID field only while the button opens Element X. The Avansert row's value
  names only a contact's own button.
- *Effect:*
  - The ID the parent reviewed and accepted is stored, unused and shown nowhere.
  - This contradicts D2's own "a closed Avansert never hides a value that isn't the default".
  - Ticking the switch looks like it did nothing.
- *Fix, either:*
  - ticking "Bruk Matrix-ID-en" also sets the contact's `message_app` to `element`, and the switch's sub-line says
    so; or
  - D2 shows the Matrix ID row (read-only "Lagret, brukes når knappen åpner Element X") whenever one is stored.

  Decide it with step 6, since it is that branch's switch. Also show "Bruk Matrix-ID-en" only when it would take
  effect, or say what it does.

**L1 Low - A stale page or second tab can still rewrite a "Tilpasset" contact; the note claims it never does.** (§0,
D4)
- *The case:* tab A was drawn with `calls_shown=on`. In tab B the parent turns `inbound` off. Tab A then saves
  "Bilder": it posts `on`, unchanged, and the pair `(on, on)`, so `inbound` is on again. The same happens after Back
  from the bfcache.
- *Not a regression:* this is design 31's write-all and is identical today.
- *But:* "never rewrites a contact by itself" overclaims.
- *Fix:* qualify the claim. Optionally add a cheap guard: when `*_shown` doesn't match the stored pair's class and the
  posted pair is exactly what `*_shown` implies (`on` = both on, `off` = both off), the page was stale and untouched,
  so keep the stored pair for that channel. Test it.

**L2 Low - A refusal must be drawn again from D3's resolved pairs, not the raw posted pairs.** (D3, D5)
- `load_page` redraws a refused contact form from `sent.contains_key("inbound")` and the others (`calls.rs:666-672`).
  Add Contact's refusal (`ADD_SWITCHES.map(contains_key)`, also on step 6's branch) does the same.
- *The case:* without JS, a parent flips a two-way switch, and the same post carries a bad Matrix ID or a bad number.
  The redraw shows the old pair, so the flip is lost.
- *Fix:* run `both_ways` on the sent form before redrawing, and derive the redraw's `*_shown` from that. Add a test.

**L3 Low - D9 and question 5 disagree about `more`.**
- D9: the script sets `more=1` whenever the contact's Avansert is open, so a save from a main switch with Avansert
  open comes back open.
- Q5: "only a save from inside it".
- D9's behaviour is the right one: the page comes back as it was. Drop or reword Q5.

**L4 Low - The page's Avansert summary hides a non-default, privacy-relevant switch.** (D8)
- "Vis meldingstekst på låseskjermen" (`sms_lock_text`, default off, design 77 §6.8) moves under a closed Avansert.
  Its sub-line names only the button and the names count.
- *Fix:* apply D2's rule to the page too. While the switch is on, the sub-line adds "Meldingstekst på låseskjermen" /
  "Message text on the lock screen".

**L5 Low - A stored number for a SIM slot that is no longer active is never drawn, so it can't be removed.** (§4)
- *The rule:* "One field per active SIM", and absent fields keep their slot.
- *The case:* a phone that once had two SIMs keeps slot 2's stored number. It is still sent as `own_numbers` and taken
  for "us", with no field to clear it.
- *Fix:* draw a field for every stored slot as well ("SIM 2 - ikke i telefonen nå"). Add a test.

**L6 Low - Question 4's fallback costs more than it says.** (§9 Q4)
- *When it fires:* with the kid's own number in `To`, the honest shape, there are two unknown addresses and it blocks.
  It admits a stranger only when the kid was Bcc'd, **or when the kid's own number is a texting contact on its own
  phone**.
- *How the second can happen:* "Legg til fra familien" offers a sibling's contact "Emma" on Emma's phone. Then every
  `[kid, contact, stranger]` group is admitted with the stranger hidden.
- *What the note should add to Q4:*
  - The guess is per message and never stored.
  - The server refuses, or flags, a contact whose number is the phone's own (typed or reported). That guard is cheap
    and useful even under strict D12.
- Deciding after step 0 is right.

**L7 Low - Words.**
- "{count} avsendernavn" needs a `t_count` pair (en "1 sender name" / "2 sender names"; `i18n::tests` checks the
  pairs). The sub-line with no names needs its own key.
- "Kontakten" / "kontakten" is two keys in each language, by position.
- The own-number finding's en text changes too ("under SMS" → "under Advanced").

**L8 Low - A save from inside Avansert shifts the switch just tapped by a line.** (D9)
- Turning a channel "Tilpasset" replaces a one-line switch row above Avansert with a value row that has a sub-line.
- `scroll-restore.js` pins the nearest ancestor of the form that has an id, which is the contact cell, not Avansert.
  The tapped switch comes back about 18 px lower.
- *Fix:* keep the main row one line (the sub-line on Avansert's row instead), or put it on the browser pass's list.

**N1 Nit - "Tilpasset" in the orange text colour.** The mockup's `.val-custom` uses `--orange-text`, the PWA's
attention colour. A deliberate choice reads as a problem. Use `--label-2`, or keep orange only if that is intended.

**N2 Nit - The RCS row keeps "først" after the switch.** Under Avansert, "Slå av chatfunksjoner i Meldinger først" is
read after SMS is already managed. Drop "først" there.

**N3 Nit - "One tap" in D5 for the held-message path.** In "Tilpasset" there is no two-way switch to tap. The tap is
"{kid} kan sende melding til kontakten" in the open Avansert. Say that.

**N4 Nit - An unknown `*_shown` value.** Say that anything but `on`/`off`/`custom` reads as absent (today's rule), and
test it.

**N5 Nit - "Bilder i meldinger" with no texts either way has no effect.** It could get a sub-line like Home's: "Gjelder
når {kid} og {contact} sender meldinger".

**N6 Nit - An emergency-number contact in D6.** Its texts are kid → contact only (`sms_out`). Say that D6 reads its
texts from `sms_out` alone, so "Anrop og meldinger" doesn't imply texts in.

## Steps and tests

- **The two-commit split and the order after step 6's server part are sound.** Step 6's `REQUEST_ADD_SWITCHES` and
  its refusal path move into the new Add Contact form. M6 must be decided before either branch builds that form.
- **Tests to add to §8:**
  - `jstest/contact-form.test.js`: the `*_shown` sync (H2).
  - A split contact on a fresh load doesn't stop auto-reload (M1; a jstest for each script if fixed there).
  - The calls row's sub-line per mode (M2).
  - "Bruk dette nummeret" and "Endre…" (M3).
  - A refusal redrawn from resolved pairs (L2).
  - A stored slot for an inactive SIM drawn and removable (L5).
  - The plural names count (L7).
  - `a_contact_row_says_which_way_calls_go` rewritten for H1's row.
- **The browser pass** should also check:
  - the shift in L8;
  - that pull-to-refresh works on a page with a split contact;
  - that `?advanced=1` and `&more=` survive reconnect's reload.
