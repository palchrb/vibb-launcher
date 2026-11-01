# 73 - The emergency menu: 112 first, the parent's numbers after it

Status: design note, 2026-11-01, on `main` at 92e8d228; built on branch `emergency-menu` (round B2, queue item Q12,
`docs/plan/2026-10-25-queue-rounds.md` decision 12). Both directories. The user, 2026-10: "burde mer enn 112 være
tilatt er spm? politi, sykebil, brann?", "Eventuel kunne man åpnet 3 alternativ når man trykker på nødnr på låst
skjerm?", "her kunne man latt det være konfigurerbart i pwa; 112 er utgangspunktet (som kan endres) og så kan man
legge til flere nr. Da dukker det opp som en valg-meny." Mockup variant A (the list) approved: "jeg er ok med A, men
ønsker ikke legevakten som alternativ" - so the PWA suggests 113 Ambulanse and 110 Brann only.

## 1. The shape

**Phone.** Every "Nødsamtale" - the PIN lock's keypad stage and its glance (which clicks the keypad's button), the
VoIP ring card, the time-rule screen (`LockActivity`) and the phone book's 112 tile (fail closed) - calls
`EmergencyCall.confirm`. With one number on the menu (the default: 112) it is exactly today's dialog, "Ringe 112?"
Ring / Avbryt (with that number). With more, a bottom sheet "Ring nødnummer" in Vibb night (`KidSheetDialog`): the
**first real emergency number** of the list as a large red row, then the others in the parent's order as rows
(number, name, a sub-line), Avbryt. **The sheet is the confirm**: one tap on a row calls. Sub-lines: "Virker alltid"
for a real emergency number; "Følger anropsreglene" in peach for any other, the row dimmed and disabled while the
call rules block it now (calls off, not in the phone book, a time rule without calls, fail closed, another call
live). A short number (3-6 digits) shows in the number column; a long one shows only its name (the number when it has
none).

**PWA.** Device › Anrop og kontakter gets a "Nødmeny" group at the top: one row per number (number, name, a
sub-line), ↑↓ like Dagen's title rules, Fjern; "Legg til nummer…" (number + name); a "Forslag" group with 113
Ambulanse and 110 Brann, "Legg til" each, shown only while the default country code is 47, the number isn't on the
list and the list has room. Sub-lines: the phone's large row "Øverst og størst på telefonen"; another real emergency
number "Nødnummer: virker alltid"; any other (orange) "Ikke et nødnummer: følger anropsreglene".

## 2. Which numbers pass (the decision table)

"Emergency" is `Emergency.isEmergencyOutgoing` as today: the platform's `TelephonyManager.isEmergencyNumber` ORed
with the fixed list (112 and 911 everywhere, plus the default country's: 47 → 110, 112, 113; 46 → 112; 45 → 112,
114), so a telephony failure never blocks 112. The fixed list is now a shared contract (§4).

| The row's number | Calls managed and on, in the phone book | Not in the phone book / calls off / time rule / fail closed | Unmanaged |
|---|---|---|---|
| Real emergency number | calls (Telecom, else `EmergencyDialer`) | calls (Telecom, else `EmergencyDialer`) | calls |
| Any other number | calls through Telecom, our redirection and in-call services check it again | **dimmed, not callable** | calls |

A non-emergency row is decided exactly like a phone-book call (`decideOutgoing` over `CallPolicyStore.effectiveState()`,
`isEmergency = false`): no new loophole. It never falls back to the emergency dialer. On the lock it calls without
the kid's PIN when the rules allow it, as the mockup draws "Mamma" there: the parent put the number on the menu, and
the call rules - not the screen lock - are what limits calls. The lock's "emergency flow" window
(`PinLockRuntime.emergencyFlowStarted`) opens for any call the sheet places, so the call screen isn't covered.

## 3. Defaults chosen

- **Never without an emergency number.** The server refuses a removal that would leave no number of the fixed list
  (the row then has no Fjern); the list can't be empty. The launcher puts 112 first if a list it receives has no
  emergency number at all (an older or broken server), and reads a missing or empty list as `[112]`.
- **No rows = `[112]`.** A phone has no rows until the parent first changes the menu; the first write stores 112
  first, then applies the change. 112 can be moved down or removed once another emergency number is on the list.
- **At most 6 numbers** (the menu always fits the Jelly Star). No duplicates. A name is optional, at most 40
  characters; an empty name reads "Nødnummer" on the phone for an emergency number.
- **The large row is the first real emergency number**, whatever its place: a non-emergency number moved to the
  top is never the big red row (it may be blocked).
- Numbers are normalised with `phone::normalize` and the default country code, as contacts are.
- The suggestions' names are written in the parent's language when added.

## 4. The contract

- **Policy:** `call_policy.emergency_menu: [{number, name}]`, always present (managed or not), at least one entry,
  normalised numbers, the parent's order. An older launcher ignores it (today's 112); an older server sends none (the
  launcher shows 112). Pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
- **The fixed list:** `server/testdata/emergency_numbers.json` (`always`, `by_country`, `vectors` of raw number +
  country code → emergency), byte-identical in `launcher/app/src/test/resources/`. The server's
  `emergency::tests` and the launcher's `EmergencyMenuTest` check their constants against it, run every vector and
  compare the copies; both CI path filters include it.
- **Storage:** migration 0114 (0113 is `reminder-ping`'s), `device_emergency_numbers (id, device_id, number, name, sort_order)`, unique per
  phone and number, deleted with the phone.
- **Routes:** `POST /devices/{id}/calls/emergency` (add; a refusal renders the page 400 with the form open),
  `.../emergency/{entry}/move` (`dir` up/down, back to `#emergency-<id>`), `.../emergency/{entry}/remove` (back to
  `#emergency`). Every write nudges the phone. No save jumps to the top (anchors, as the rest of the page).
- **Launcher store:** the menu is read with the CE policy cache in `CallPolicyStore.refresh` (the cached policy's
  list, else the one stored with `last_call_rules`); every entry point runs after the first unlock, so the DE boot
  copy is unchanged.

## 5. Tests

Launcher (`EmergencyMenuTest`): the default and an empty list read `[112]`; a list without an emergency number gets
112 first; bad numbers, duplicates and a seventh entry are dropped; the large row is the first emergency number; one
entry is today's dialog; only real emergency numbers pass calls off / fail closed / a time rule / another call; any
other number follows the rules (allowed in the phone book with calls on, blocked otherwise, allowed unmanaged); a tap
on a blocked row does nothing, on an emergency row calls the emergency path, on another the rules' path; the fixed
list equals the contract and its vectors; the copies are identical. `PolicyResponseCompatTest`: the key decodes.

Server: the policy carries `[112]` by default, for managed and unmanaged phones; add (normalised, the cap, a
duplicate, a bad number), move, remove (the last emergency number refused), suggestions shown and hidden; each
redirect keeps its anchor; the contract vectors and the copies; the snapshot.

Emulator (left for QA, never a real call): the sheet from every entry point (the glance, the keypad, the ring card,
the time-rule screen, the fail-closed 112 tile); the emulator's own emergency number (the emulator treats 112/911 as
emergency and connects them to its fake network - check the call log, not a real line); a non-emergency row dimmed
under a no-calls time rule and callable with it lifted; one number gives "Ringe 112?".
