# Design 97: Mobilnett revision 4 - "I utlandet", the roaming notice, and the kid's Mobildata switch

Status: design, 2026-11-14 (branch `mobilnett-r4`). Revises what design 94 and design 95 revision 3 built (server
0.79.0, launcher 0.40.0-rc.1). Both directories, migration `0145_abroad.sql` (0144 is `device_report_base`; recheck at
merge), the shared contract `server/testdata/mobile_network.json` grows. Size: launcher **M**, server **M**.

The user (2026-11-14) found the page confusing. "Telefonens valg" with "Telefonens valg: av" read as roaming *on*, and
roaming could not be turned on, because the settings lock also locks Android's roaming switch. What matters abroad
is control over mobile data. Approved: one choice "I utlandet" with three values, a notice when roaming starts,
clearer words, and the kid's own Mobildata switch (95's D6/N4). Not in scope: automatic EU/EØS by country, per-app
exceptions, "Helt av" from the PWA.

## 1. What changes from revision 3

| | Revision 3 (built) | Revision 4 |
|---|---|---|
| Roaming choice | "Dataroaming": Av (låst) / Telefonens valg | **"I utlandet"**: Ingen data / Bare Vibb (anbefalt) / Som hjemme |
| Android's roaming switch | never turned on by Vibb | turned on by the launcher where a device owner can (one modem). Otherwise the PWA asks the parent to turn it on once, during Åpne for oppsett |
| Sink reasons | `parent`, `roaming` (lingering session) | + `abroad` (Bare Vibb abroad), + `kid` (the kid's Off) |
| National roaming | not told apart | `domestic` (roaming on the SIM's own MCC) counts as home for Bare Vibb and for the notice |
| Notice | none | `roaming_started`: "Emma er på roaming i Sverige", once per trip |
| Kid | nothing | "Mobildata" On/Off in the kid's settings. Off = only Vibb on cellular |

What stays: the sink/pass/filter shapes and the single owner of always-on (95 §2.2), the lingering-session sink, the
relock (94 §2.4.2), "Lås mobilnett-innstillingene", `mobile_data.enabled`, the pause and override lifts.

## 2. The device-owner facts this rests on (95 §1, measured)

- `DevicePolicyManager.setGlobalSetting(admin, Settings.Global.DATA_ROAMING, "1")` is allowed. Telephony reads the
  **bare** key only when `getSimCount() > 1` is false, i.e. on a one-modem phone. A write brings nothing up by
  itself; a `requestNetwork(TRANSPORT_CELLULAR, INTERNET)` afterwards brought roaming data up in 119 ms.
- A dual-SIM phone reads `data_roaming<subId>`, and the device owner gets `SecurityException` for that key. **On two
  modems only a person can turn roaming on** (Android's Settings › Nettverk og internett › SIM-kort › Roaming).
- `DISALLOW_DATA_ROAMING` writes `0` on every SIM when set, and nothing when cleared. A write of `1` while it is set
  is refused silently. A write straight after the clear may still be refused: UMS posts the change, so the launcher
  waits for `ACTION_USER_RESTRICTIONS_CHANGED` first (95 §1.2, "not measured").
- `DISALLOW_CONFIG_MOBILE_NETWORKS` takes away Settings' whole SIM page, the roaming switch included. Åpne for
  oppsett lifts it, and while it is set nobody can change the switch.

**The Jelly Star.** Unihertz's spec: two nano SIMs (SIM 1 + a hybrid SIM/microSD slot) on a Helio G99, whose one
integrated LTE modem runs both SIMs in dual standby ([unihertz.com](https://www.unihertz.com/products/jelly-star),
[notebookcheck](https://www.notebookcheck.net/Unihertz-Jelly-Star.746485.0.html)). Android counts phones, not chips:
dual standby gives `getActiveModemCount() = 2`, so telephony reads the per-SIM key **even with one SIM in**. Expected:
the launcher **cannot** turn roaming on there, and the parent does it once. **The check needs no new RC:** rc.1
already reports `data_roaming.modems`. The server's page shows it from step 4 on (the switch line names the case, §5).
Until then the stored report has it: `SELECT data_roaming_json FROM device_status WHERE device_id = ? AND
data_roaming_json IS NOT NULL ORDER BY id DESC LIMIT 1`. After the r4 RC, the definitive check is the report of the
choice at work: `enabled` reading `true` after the parent's one step, and staying `true` across a restart.

## 3. Policy, report, contract

### 3.1 Policy (`GET /api/devices/policy`)

- **New** top-level `abroad`: `"off"` (Ingen data) | `"vibb"` (Bare Vibb) | `"home"` (Som hjemme). Always sent.
- `data_roaming` stays, **derived** for an older launcher (which knows only `data_roaming_v1`): `off` -> `"off"`,
  `vibb` -> `"off"`, `home` -> `"phone"`. An older launcher never gets more than the parent chose. Under Bare Vibb it
  gets no data abroad, and the page says that it needs a newer Vibb One.
- `quick_controls_mask` bit **32** (`QUICK_CONTROL_MOBILE_DATA`): the kid's Mobildata row shows. An older launcher
  ignores the bit.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin `abroad`.

The launcher reads the choice like this (`abroadChoice(abroad, dataRoaming)`, fail closed):

| `abroad` | `data_roaming` | Choice |
|---|---|---|
| `"off"`, `"vibb"`, `"home"` | any | that |
| any other string | any | `OFF` |
| absent (older server) | `"phone"` or absent | `LEGACY` (revision 3's "phone": no lock, **no write**, no abroad sink) |
| absent | anything else | `OFF` |

`LEGACY` matters: a new launcher on a 0.79 server must not start writing `data_roaming = 1` behind a "Telefonens
valg" that never promised it.

### 3.2 Report (`POST /api/devices/status`)

- `data_roaming` gains `domestic: bool`. It is true while roaming on a network whose MCC equals the SIM's
  (`getNetworkOperator().take(3) == getSimOperator().take(3)`, default data SIM, no permission), and false otherwise,
  an unknown MCC included. No country leaves the phone for it.
- `mobile_data.sink` gains `"abroad"` and `"kid"`. The server's `clean_mobile` takes the two new words, and an older
  server stores them as `"off"`.
- `mobile_data` gains `kid`: `"on"` | `"off"` | `null`, the kid's switch (`null` while the row is hidden).
- Capability **`mobile_network_v2`**: the launcher enforces `abroad`, reports `domestic` and `kid`, and shows the
  kid's row. The PWA's "I utlandet" radios, the kid's switch and the notice wait for it. A phone without it gets the
  "Virker etter at Vibb One er oppdatert" line, as today.

### 3.3 Contract `mobile_network.json` (byte-identical in `launcher/app/src/test/resources/`)

Added: `capabilities.mobile_network` (`"mobile_network_v2"`); `policy` rows for the three choices (with the derived
`data_roaming`); `abroad_choice` (the table above); `roaming_on` (`choice, override, modems, enabled -> write`);
`shapes` rows gain `abroad`, `domestic`, `kid` (new rows below); `kid_row` (`shown, kid, parent_vibb, abroad,
choice, data_enabled -> row`); `report` with `domestic`, `kid` and `sink: "abroad"`; `clean`/`clean_mobile` rows for
the new fields. The migration and the trip latch are the server's alone (unit tests).

## 4. The launcher's rule

Inputs: the choice (§3.1), `lift` (the pause, Åpne for oppsett, the offline override: `overrideActive`), the parent's
`apps_mobile`, the kid's switch (counts only while bit 32 is set), and the best non-VPN network.
`BestNet(cellular, roaming, abroad)` with `abroad = roaming && !domestic`. An unknown MCC while roaming counts as
abroad (fail closed toward the sink, and Vibb still works).

- **Roaming lock** (`DISALLOW_DATA_ROAMING`): `choice == OFF && !lift`. Moving back to Ingen data sets it again,
  so Android writes `0` on every SIM and greys the switch out. On two modems that `0` stays when the choice moves
  away again, so the parent's one step is needed again (§5 says so at the choice).
- **The write** (`setGlobalSetting(DATA_ROAMING, "1")`): `choice in {VIBB, HOME} && !lift && modems == 1 &&
  isDataRoamingEnabled() == false`. If the restriction was set until now, the launcher clears it, waits for
  `ACTION_USER_RESTRICTIONS_CHANGED` (5 s, the relock's wait), writes, and reads back. Then comes the kick
  (`requestNetwork` cellular + internet, released after `onAvailable` or 10 s), only when roaming and the best
  network isn't Wi-Fi. At most once per policy change and once per process start, so a refusing OEM costs nothing.
  Never during a lift: Åpne for oppsett is the parent's hands on the phone. Never with `modems != 1` (the bare key does
  nothing there).
- **Sink reasons** (on cellular only; the first that holds is reported):
  1. `parent`: `apps_mobile == "vibb" && !lift` (as today);
  2. `abroad`: `choice == VIBB && !lift && net.abroad`;
  3. `roaming`: the lock is on and `net.roaming` (the lingering session, as today);
  4. `kid`: the row is shown and the kid's switch is Off (**not** lifted by the pause, D4).
- **Pass shape** (filter off): when any reason above can arise: `parentSink || roamingLocked || (choice == VIBB &&
  !lift) || kidOff`.
- The settings lock (`DISALLOW_CONFIG_MOBILE_NETWORKS`) is unchanged. It is what keeps Android's switch where it is
  after the write or the parent's step.

| Choice | Network | Lift | Parent's mobile data | Kid | Roaming lock | Write `1` | Shape on cellular / sink |
|---|---|---|---|---|---|---|---|
| any | Wi-Fi | - | - | - | per choice | per choice | filter (or none/pass) - Wi-Fi unaffected |
| Ingen data | home | no | På | On | **on** | no | filter / `off` |
| Ingen data | abroad, no session | no | På | On | **on** | no | no roaming data at all |
| Ingen data | abroad, session from before | no | any | any | **on** | no | sink / `roaming` |
| Ingen data | any | **yes** | any | Off | off | no | sink / `kid` (the kid's own) |
| Bare Vibb | home or `domestic` | no | På | On | off | 1 modem, if off | filter / `off` |
| Bare Vibb | abroad | no | På | On | off | 1 modem, if off | **sink / `abroad`** |
| Bare Vibb | abroad | **yes** | På | On | off | no | filter / `off` |
| Som hjemme | abroad | no | På | On | off | 1 modem, if off | filter / `off` (full data) |
| Som hjemme | abroad | no | Bare Vibb | any | off | 1 modem, if off | sink / `parent` |
| any | home | no | På | **Off** | per choice | per choice | sink / `kid` |
| `LEGACY` | any | no | per policy | as above | off | **never** | as revision 3 |
| unknown value | any | no | - | - | **on** | no | as Ingen data |

**The kid's row** (pure `kidMobileRow`, in "Tilkobling og skjerm" after Wi-Fi):

| State | Switch | Line under it (nb / en) |
|---|---|---|
| bit 32 off | no row | - |
| the parent decides now: `apps_mobile == "vibb"` and no lift; abroad with Ingen data or Bare Vibb and no lift; Android's mobile data off | Off, 50 %, not clickable | "Bestemt av mamma og pappa" / "Set by mum and dad" |
| the kid's On | On | - |
| the kid's Off | Off | "Bare Vibb One bruker mobilnettet. Wi-Fi virker som før." / "Only Vibb One uses the mobile network. Wi-Fi works as before." |

A tap stores the choice (`LauncherPreferences.mdm()`), re-decides the shape through `KidVpnService` (the same
`applyShape`), and calls `CommandListenerService.requestSync(context, "kid mobile data")` so the parent sees it. When
bit 32 goes, the stored choice resets to On (D7). The string "Spotify" never appears: the line names Vibb One only.

## 5. The PWA (`/devices/{id}/mobile`, a phone sub-page: no bell; each group saves to its own anchor, never the top)

Groups: **Mobildata** (`#mobile-data`), **I utlandet** (`#abroad`, replacing `#data-roaming`, which nothing outside the page links to), then the lock
(`#mobile-lock`). The kid's switch is a row of its own in Mobildata (`group=kid-mobile`, writes bit 32 alone). The
Home Screen page's quick-controls form keeps bit 32 when it saves (`(quick_controls_mask & 32) | ?`), and its footer
gains one line: "Mobildata-bryteren styres under Mobilnett."

```
Mobilnett                                                   (nb)
MOBILDATA
  ( ) På               Alle tillatte apper kan bruke mobildata.
  (•) Bare Vibb        Apper og nettleser stopper. Du styrer telefonen, ser posisjon og får varsler som før.
  ⓘ Hva Bare Vibb stopper
  Barnets bryter for mobildata                       [on]
      Emma kan slå mobildata av på telefonen (da bruker bare Vibb mobilnettet). Den kan bare stramme inn.
      · Emma har slått mobildata av                      (state line, when kid = "off")
I UTLANDET
  ( ) Ingen data            Ingen mobildata i utlandet, heller ikke på skips- og flynett.
  (•) Bare Vibb · anbefalt  Vibb holder kontakten med deg. Apper og nettleser venter på Wi-Fi.
  ( ) Som hjemme            Mobildata som hjemme. Kan koste mye utenfor EU/EØS.
  · Androids roamingbryter: på – låst, Emma kan ikke endre den
  · Roamer i Sverige
  ⓘ Om utlandet
  Gjelder når telefonen er utenfor Norge. Wi-Fi virker alltid. Nasjonal roaming i Norge regnes som hjemme.
LÅS MOBILNETT-INNSTILLINGENE                                [on]
```

```
Mobile network                                              (en)
MOBILE DATA
  ( ) On               Every allowed app can use mobile data.
  (•) Only Vibb        Apps and the browser stop. You manage the phone, see where it is and get alerts as before.
  Child's mobile data switch                         [on]
      Emma can turn mobile data off on the phone (then only Vibb uses the mobile network). It can only restrict more.
ABROAD
  ( ) No data               No mobile data abroad, nor on ship and plane networks.
  (•) Only Vibb · recommended  Vibb keeps in touch with you. Apps and the browser wait for Wi-Fi.
  ( ) Like at home          Mobile data as at home. Can cost a lot outside the EU/EEA.
  · Android's roaming switch: on – locked, Emma can't change it
```

The phone's name in these lines is the phone's own (`device.name`). The examples use Emma.

**The switch line** (`mobile.abroad.switch.*`, one line, from `choice`, the report's `enabled`, `modems`, `locked`,
`settings_locked` and the pause; it replaces `phone_on`/`phone_off`, which go):

| Case | nb | en |
|---|---|---|
| Ingen data, locked | Androids roamingbryter: av og låst | Android's roaming switch: off and locked |
| Bare Vibb/Som hjemme, `enabled`, settings lock on | Androids roamingbryter: på – låst, {name} kan ikke endre den | Android's roaming switch: on – locked, {name} can't change it |
| … `enabled`, settings lock off | Androids roamingbryter: på – {name} kan endre den i Innstillinger | Android's roaming switch: on – {name} can change it in Settings |
| … not `enabled`, `modems == 1`, no pause | Androids roamingbryter: av – Vibb One slår den på ved neste kontakt | Android's roaming switch: off – Vibb One turns it on at its next contact |
| … not `enabled`, `modems != 1` (or the write failed) | Androids roamingbryter: av – slå den på én gang under Åpne for oppsett: Innstillinger › Nettverk og internett › SIM-kort › Roaming. Låsen holder den på etterpå. | Android's roaming switch: off – turn it on once during Open for setup: Settings › Network & internet › SIMs › Roaming. The lock keeps it on afterwards. |
| paused | Androids roamingbryter: {på/av} – åpnet for oppsett nå | Android's roaming switch: {on/off} – open for setup now |

Under "Ingen data", while the switch is on and `modems != 1`, the sub-line gains "På denne telefonen må roaming slås
på igjen for hånd hvis du senere velger noe annet." / "On this phone roaming must be turned on again by hand if you
later choose something else." No surprise when the switch goes off.

The ⓘ "Om utlandet": Bare Vibb abroad still uses a little data (Vibb's own, a few hundred KB a day, plus Android's
own checks). Outside the EU/EØS that can start a day price. Ingen data is the only zero. A ship's or plane's network
is always abroad. Calls and SMS are unaffected, and MMS waits for Wi-Fi under Ingen data.

Other words: the row value on the phone's page (`mobile.value.*`) is "Bare Vibb · Utlandet: som hjemme · Mobildata av
(Emma)", showing only the parts away from the defaults. On the summary, a kid's Off reads "Mobildata: bare Vibb – Emma
har slått av" and an abroad sink "Mobildata: bare Vibb i utlandet" (en "Mobile data: only Vibb – Emma turned it
off" / "Mobile data: only Vibb abroad"). National roaming under Ingen data (report `roaming && domestic`) gets one
warning line: "Nasjonal roaming: Ingen data stenger mobildata her – velg Bare Vibb eller Som hjemme" / "National
roaming: No data cuts mobile data here – choose Only Vibb or Like at home". Every string is in `en.json` and
`nb.json`. No Microsoft or Teams wording anywhere.

**The country phrases are restructured, not doubled.** Today's 32 `data_roaming.in.<cc>` keys ("Roamer i Sverige")
become `data_roaming.place.<cc>` ("i Sverige", "på Island"; en "in Sweden", "in the UK"). They are used by
`data_roaming.in` "Roamer {place}" and by the notice. The same count of strings, and nb's prepositions stay per
country (94 rev. 2).

## 6. The notice: `Kind::RoamingStarted` (`roaming_started`)

- **Words** (parameters `{name, place, at}`, worded at read as every kind since design 70): inbox and push title
  "{name} er på roaming {place}" / "{name} is roaming {place}" ("Emma er på roaming i Sverige"). A ship network gives
  "{name} er på roaming på et skips- eller flynett", an unnamed country "… ({CC})", and no country "{name} er på
  roaming". The summary and the push body are the clock alone (notifications note's rule). Target
  `/devices/{id}/mobile#abroad`.
- **Once per trip**, a latch like the battery's: `devices.roaming_trip_at` and `devices.roaming_home_at`. Pure
  `trip_edge(trip_at, home_at, abroad, now)`:
  - An abroad report (`roaming && !domestic`, from a `mobile_network_v2` launcher) with no trip open **raises** the
    notice and opens the trip. With a trip open it only clears `home_at`.
  - A home report sets `home_at` if it is empty, and closes the trip once `home_at` is **6 h** old (D6).
  - So a border cell flipping back and forth, a ferry, and Sweden then Denmark are one notice. A conditional UPDATE in
    one transaction with the raise, as `battery_report`. `dedup_key` = the trip's start.
- **Switch** `push_roaming` ("Roaming", default on) in Notify Me About; TTL 43200, normal, foldable, no `PushBudget`
  (at most one a trip). Retention as the other kinds (90 days).
- A phone under Ingen data reports only on Wi-Fi abroad, so its notice comes then, dated by that report. The inbox
  shows when it was raised.

## 7. Migration `0145_abroad.sql`

```sql
ALTER TABLE device_policy ADD COLUMN abroad TEXT NOT NULL DEFAULT 'off' CHECK (abroad IN ('off', 'vibb', 'home'));
-- 'phone' becomes 'home' only where the phone's last roaming report read Android's switch on; every other row 'off'.
UPDATE device_policy SET abroad = 'home' WHERE data_roaming = 'phone' AND (
    SELECT json_extract(s.data_roaming_json, '$.enabled') FROM device_status s
    WHERE s.device_id = device_policy.device_id AND s.data_roaming_json IS NOT NULL ORDER BY s.id DESC LIMIT 1) = 1;
ALTER TABLE devices ADD COLUMN roaming_trip_at TEXT;
ALTER TABLE devices ADD COLUMN roaming_home_at TEXT;
ALTER TABLE notification_prefs ADD COLUMN push_roaming INTEGER NOT NULL DEFAULT 1;
```

**The rule:** `off` -> Ingen data. `phone` -> Som hjemme **only** if the newest stored roaming report says Android's
switch was on (`enabled: true`). The phone then had full data abroad and keeps exactly that. Every other `phone`
(switch off, unreadable, never reported) -> Ingen data, which is what that phone really had abroad: no data. No
phone gains data abroad. The one possible loss (a `phone` row whose switch was on but never reported) shows on the page
as "Ingen data". `data_roaming` stays as a column and is no longer read or written (no table rebuild); the server
serves the derived value (§3.1). The settings log names the new choice.

## 8. Risks

- **Two modems (the Jelly Star, expected):** the parent's one step is needed after setup, after each move back to
  Ingen data, and after a new SIM (a new per-SIM key starts at the carrier's default). The switch line says it each
  time. Nothing silent.
- **The border:** under Bare Vibb a new roaming network carries every app's traffic until the watcher's callback
  establishes the sink (95 measured the tunnel re-established 64 ms after Wi-Fi was lost; the pass, ~385 ms). Android's validation probe
  always goes out. Outside the EU/EØS that can start a day price; the ⓘ says so, and Ingen data is the zero.
- **National to foreign roaming without a network change:** both lack `NOT_ROAMING`, so no capabilities callback may
  come and the abroad sink waits for the next `apply()` (30 min at most) or network change. This is rare: it needs a
  national-roaming area at the border. If the report shows it, add 95 §2.2's `ServiceStateListener`.
- **Ingen data in a national-roaming area** cuts data at home (94/95 §6 item 4, unchanged). The new warning line names
  it.
- **An OEM that refuses the bare write on one modem:** the read-back fails, the report keeps `enabled: false`, and the
  page shows the "turn it on once" line.
- **An older launcher** under Bare Vibb has no data abroad (derived `"off"`), which is less than chosen. The page says it.
- **The Home Screen form** must keep bit 32 (a test), or a save there would hide the kid's switch.

## 9. Build steps and sizes

1. **Contract, both directories, one commit, S.** `abroad` in the policy (derived `data_roaming`), `domestic`, `kid`,
   the new sink words, `mobile_network_v2`; DTOs, `clean_*`, the snapshot tests; `mobile_network.json` both copies
   (already in both CI path lists).
2. **Launcher rules and runtime, M.** `abroadChoice`, `BestNet.abroad` (the MCCs read in the watcher and in
   `bestNet`), the two new sink reasons and the pass rule, the write with its wait, read-back and kick, the report
   fields; `MobileNetworkTest` runs every new contract row.
3. **Launcher kid row, S.** Bit 32, `kidMobileRow`, the row and its strings (nb + en), the stored choice and its reset,
   the re-decide and sync; `KidSettingsModelTest`.
4. **Server page and migration, M.** 0145, the "I utlandet" radios, the kid switch (`group=kid-mobile`), the switch
   lines, the restructured country phrases, the row value and summary lines, the Home form's bit 32 kept, nb + en,
   page tests (anchors, no bell, every line in both languages).
5. **Server notice, S.** `Kind::RoamingStarted`, `trip_edge`, the latch, the switch, words in both languages
   (`every_kind_is_worded_in_both_languages`).
6. **Emulator run (§10), S.** Then code QA, fixes, and the RC. The Jelly Star checks ride on the RC's reports.

## 10. Emulator tests (`jelly36`, 95 §1.1's private image with a Norwegian SIM, `docs/testing/emulator.md` §6ak)

1. **Abroad, Bare Vibb:** network `311740`, `adb emu gsm data roaming` + `gsm voice roaming`. Expect the sink with
   `abroad`, another app RSTs, the launcher syncs, the report `domestic: false`. One notice. Flip `gsm data home` /
   `roaming` three times within minutes: still one.
2. **The write (one modem):** Ingen data -> `settings get global data_roaming` 0, locked. Choose Bare Vibb -> 1 after
   the wait, and roaming data comes up within a second on cellular (the kick). Back to Ingen data -> 0, locked.
3. **Som hjemme:** every app has roaming data. With the parent's Bare Vibb the sink reads `parent`.
4. **National roaming:** a second image copy with `CIMI 311480…` (the network's MCC 311, another name) reads
   roaming with `domestic: true`. Under Bare Vibb: no sink, no notice.
5. **The kid's switch:** bit 32 on, Off: on Wi-Fi the filter, on cellular the sink `kid`, the report `kid: "off"`, the
   PWA line. The parent's Bare Vibb gives the row 50 % "Bestemt av mamma og pappa". Hiding the bit: the row goes, the
   switch is back On, the sink goes.
6. **The pause:** the abroad sink and the roaming lock lift within a second, the kid's Off stays (D4), and the switch
   line reads "åpnet for oppsett nå".
7. **The page:** both languages, each save on its anchor, the Home form keeps bit 32.

Not on the emulator (one modem): the dual-SIM refusal and the per-SIM wipe. These come from the Jelly Star's reports
after the RC (`modems`, `enabled` before and after the parent's step and a restart). No adb and no debug build on
the kid's phone.

## 11. Decisions for the user

- **D1. The default for new phones:** Ingen data (recommended: zero bytes and no bill surprise; Bare Vibb is marked
  "anbefalt" and is one tap away), or Bare Vibb.
- **D2. The migration:** `phone` -> Som hjemme only where the phone reported Android's switch on, else Ingen data
  (recommended: no phone gains data abroad).
- **D3. The kid's switch for new and existing phones:** hidden until the parent turns it on (recommended, as 95 §4.1),
  or shown on new phones.
- **D4. Does Åpne for oppsett lift the kid's own Off?** No (recommended: it is the kid's choice, and whoever holds the
  phone can flip it). The parent's own lifts are unchanged.
- **D5. Where the parent shows or hides the kid's switch:** on the Mobilnett page, with a line on the Home Screen page
  (recommended: everything about mobile data in one place).
- **D6. One notice per trip:** a new trip after 6 h reported at home (recommended), not per country, and not for a
  ship network mid-trip.
- **D7. Hiding the kid's switch resets it to On** (recommended: no old Off comes back when the row returns).
