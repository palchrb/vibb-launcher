# Design 97: Mobilnett revision 4 - "I utlandet", the roaming notice, and the kid's Mobildata switch

Status: design, **revision 2 (design QA)**, 2026-11-14 (branch `mobilnett-r4`). It revises what design 94 and design 95
revision 3 built (server 0.79.0, launcher 0.40.0-rc.1). Both directories, migration `0145_abroad.sql` (0144 is
`device_report_base`; recheck at merge), and the shared contract `server/testdata/mobile_network.json` grows. Size:
launcher **S-M**, server **M**. The design QA is `docs/review/qa-97-design.md` (1 H, 8 M, 7 L); §12 lists how each
finding was handled.

The user (2026-11-14) found the page confusing. "Telefonens valg" with "Telefonens valg: av" read as roaming *on*, and
roaming couldn't be turned on, because the settings lock also locks Android's roaming switch. What matters abroad is
control over mobile data. Approved: one choice "I utlandet" with three values, a notice when roaming starts, clearer
words, and the kid's own Mobildata switch (95's D6/N4). Not in scope: automatic EU/EØS by country, per-app exceptions,
"Helt av" from the PWA.

## 1. What changes from revision 3

| | Revision 3 (built) | Revision 4 |
|---|---|---|
| Roaming choice | "Dataroaming": Av (låst) / Telefonens valg | **"I utlandet"**: Ingen data / Bare Vibb / Som hjemme |
| Android's roaming switch | never touched by Vibb | still never written by Vibb (D9). For Bare Vibb and Som hjemme the parent turns it on once during Åpne for oppsett, the page says when it is off, and the settings lock keeps it on |
| Abroad | `roaming` from `NOT_ROAMING` | `where`: `home` / `abroad` / `unknown`. National roaming is home. A 901 (ship or plane) network is always abroad |
| Sink reasons | `parent`, `roaming` (lingering session) | + `abroad` (Ingen data or Bare Vibb while abroad), + `kid` (the kid's Off) |
| Notice | none | `roaming_started`: "Emma er på roaming i Sverige", once per trip |
| Kid | nothing | "Mobildata" On/Off in the kid's settings. Off = only Vibb uses mobile data |

These stay as built: the filter/sink/pass shapes and the single owner of always-on (95 §2.2), the relock (94 §2.4.2),
"Lås mobilnett-innstillingene", `mobile_data.enabled`, and the pause and override lifts.

## 2. The device-owner facts this rests on (95 §1, measured)

- A device owner can turn roaming on only on a one-modem phone, through the bare `Settings.Global.DATA_ROAMING`. A
  dual-SIM phone reads `data_roaming<subId>`, and the device owner gets `SecurityException` for it.
- `DISALLOW_DATA_ROAMING` writes `0` on every SIM when it is set, and nothing when it is cleared.
- `DISALLOW_CONFIG_MOBILE_NETWORKS` takes away Settings' SIM page, the roaming switch included. Åpne for oppsett lifts
  it.

**The Jelly Star.** Unihertz's spec lists two nano SIMs (SIM 1 plus a hybrid SIM/microSD slot) on a Helio G99. Its
one integrated LTE modem runs both SIMs in dual standby ([unihertz.com](https://www.unihertz.com/products/jelly-star),
[notebookcheck](https://www.notebookcheck.net/Unihertz-Jelly-Star.746485.0.html)). Android counts phones, not
chips, so dual standby gives `getActiveModemCount() = 2` and the per-SIM key, even with one SIM in. A device-owner
write could never turn roaming on there. That is why this revision has no writer (D9). It can be checked today,
without an RC: rc.1 already reports `data_roaming.modems`
(`SELECT data_roaming_json FROM device_status WHERE device_id = ? AND data_roaming_json IS NOT NULL ORDER BY id DESC
LIMIT 1`). After the r4 RC, the check is the report: `enabled` reads `true` after the parent's one step and stays
`true` across a restart.

## 3. Policy, report, contract

### 3.1 Policy

- **New** top-level `abroad`: `"off"` (Ingen data), `"vibb"` (Bare Vibb) or `"home"` (Som hjemme). It is always sent.
- `data_roaming` stays, **derived** for an older launcher: `off` -> `"off"`, `vibb` -> `"off"`, `home` -> `"phone"`.
  An older launcher never gets more than the parent chose.
- `quick_controls_mask` bit **32** (`QUICK_CONTROL_MOBILE_DATA`): the kid's Mobildata row shows. An older launcher
  ignores the bit.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin `abroad`.

The launcher reads the choice with `abroadChoice(abroad, dataRoaming)`, which fails closed:

| `abroad` | `data_roaming` | Choice |
|---|---|---|
| `"off"`, `"vibb"`, `"home"` | any | that value |
| any other string | any | `OFF` |
| absent (older server) | `"phone"` or absent | `LEGACY`: revision 3's "phone" (no lock, no abroad sink) |
| absent | anything else | `OFF` |

The contract's existing `shapes` rows carry only `data_roaming`. They are read through `abroadChoice` as an older
server's rows, so `"phone"` reads as `LEGACY`.

### 3.2 Report

- `data_roaming` gains **`where`**: `"home"`, `"abroad"` or `"unknown"`, the launcher's own judgement (§4.1). It
  replaces revision 1's `domestic`. A national-roaming network reads `roaming: true, where: "home"`.
- `mobile_data.sink` gains `"abroad"` and `"kid"`. The server's `clean_mobile` takes both; an older server stores them
  as `"off"`.
- `mobile_data` gains `kid`: `"on"`, `"off"`, or `null` while the row is hidden.
- Capability **`mobile_network_v2`**: the launcher enforces `abroad`, reports `where` and `kid`, and shows the kid's
  row. The kid's switch on the page and the notice wait for it. The "I utlandet" radios need only `data_roaming_v1`,
  because the derived value works there. On such a phone the Bare Vibb row adds "Ingen data i utlandet til Vibb One er
  oppdatert" / "No data abroad until Vibb One is updated".

### 3.3 Contract `mobile_network.json` (byte-identical in `launcher/app/src/test/resources/`)

Added:
- `capabilities.mobile_network`;
- `policy` rows for the three choices, with the derived `data_roaming`;
- `abroad_choice`, the table above;
- `where` (`roaming, airplane, operator, sim_cc, net_cc, net_mcc, sim_mcc -> where`), with multi-MCC rows (US 310/311,
  UK 234/235) and 901 rows with and without the roaming flag;
- `shapes` rows with `abroad`, `where`, `kid`, the lift, `cellular: null` with a persisted sink, and 901 without the
  roaming flag under each choice;
- `kid_row` (`shown, kid, lift, parent_vibb, where, choice, data_enabled -> row`);
- `report` with `where`, `kid` and `sink: "abroad"`;
- `clean` and `clean_mobile` rows for the new fields.

The migration and the trip latch belong to the server alone and are covered by its unit tests.

## 4. The launcher's rule

### 4.1 Where the phone is

`where(...)` is a pure function, read on the default data SIM. None of the reads needs a permission.
- `unknown` when airplane mode is on (`Settings.Global.AIRPLANE_MODE_ON`) or there is no registered network
  (`getNetworkOperator()` isn't 5-6 digits).
- `abroad` when the network's MCC is **901**, roaming flag or not (Q97-H1).
- `abroad` when roaming and not domestic. Domestic: `getSimCountryIso() == getNetworkCountryIso()` when both are
  known, which handles multi-MCC countries (Q97-L3), else equal MCCs. Roaming with neither known counts as abroad
  (fail closed toward the sink).
- `home` otherwise.

The watcher's `BestNet` carries `abroad = where == "abroad"`.

### 4.2 The rule

Inputs: the choice, `lift` (the pause, Åpne for oppsett, the offline override), the parent's `apps_mobile`, the kid's
switch (it counts only while bit 32 is set), and the best non-VPN network.

- **Roaming lock** (`DISALLOW_DATA_ROAMING`): `choice == OFF && !lift`. Moving back to Ingen data sets it again, so
  Android writes `0` on every SIM and the parent's step is needed again later. The page says so under that choice.
- **No write.** Android's switch is the parent's, on the phone (D9).
- **Sink reasons**, on cellular only. The first that holds is reported, and `sinkReason`, `applyShape` and
  `reportedSink` use the same order (Q97-M2):
  1. `parent`: `apps_mobile == "vibb" && !lift`;
  2. `abroad`: `choice in {OFF, VIBB} && !lift && net.abroad`. Under Ingen data this is a 901 network Android doesn't
     flag as roaming (the lock can't cover it), or a session from before the lock;
  3. `roaming`: the lock is on and `net.roaming` while not abroad (a national-roaming session from before the lock);
  4. `kid`: the row is shown and the kid's switch is Off. The pause does not lift it (D4).
- **Keep the sink on a doubt** (Q97-M2). With no network seen and SINK persisted, the sink stays while any configured,
  unlifted reason that doesn't need the network holds: `parentSink || abroadArmed || kidOff`, where `abroadArmed` is
  `choice in {OFF, VIBB} && !lift` (it covers `roamingLocked`). This applies to `apply()` and to the service's cold
  start alike. A flap (a tunnel, a modem restart, airplane on and off, a reboot abroad) never writes lockdown off. The
  next network then decides.
- **Pass shape** (filter off): when any reason can arise: `parentSink || abroadArmed || kidOff`.
- **The persisted inputs** (Q97-M3). rc.1's record `vpn_inputs` (`filter,parentSink,roamingLocked`) stays exactly as it
  is, written as before, so a rollback to rc.1 reads what it knows. A second record, `vpn_inputs_r4`, holds
  `abroadArmed,kidOff`. An absent second record (the first start after the upgrade) reads as both false, which is rc.1's
  own behaviour until the first `apply()`. The kid's tap writes `kidOff` into `vpn_inputs_r4` before it re-decides
  through `applyShape`, so the service, `apply()` and `sinkWanted` read one source. Unit test: upgrade (no second
  record), rollback (rc.1 decoding the untouched first record).

| Choice | Network (`where`) | Lift | Parent | Kid | Roaming lock | Shape on cellular / reported sink |
|---|---|---|---|---|---|---|
| any | Wi-Fi | - | - | - | per choice | filter (or pass), Wi-Fi unaffected |
| Ingen data | home | no | På | On | **on** | filter / `off` |
| Ingen data | abroad, roaming | no | any | any | **on** | no roaming data; a session from before -> sink / `abroad` |
| Ingen data | abroad, 901 not flagged | no | any | any | on (doesn't apply) | **sink / `abroad`** |
| Bare Vibb | home (national roaming too) | no | På | On | off | filter / `off` |
| Bare Vibb | abroad (901 too) | no | any | any | off | **sink / `abroad`** (or `parent`) |
| Som hjemme | abroad | no | På | On | off | filter / `off`, full data |
| Som hjemme | abroad | no | Bare Vibb | any | off | sink / `parent` |
| any | home | no | På | **Off** | per choice | sink / `kid` |
| any | any | **yes** | any | On | off | none (the filter is lifted too) |
| any | any | **yes** | any | Off | off | sink / `kid` (pass on Wi-Fi) |
| any | none seen, SINK persisted | no | as armed | as armed | per choice | **sink kept** |
| `LEGACY` | any | no | per policy | as above | off | as revision 3 |

### 4.3 The kid's row (pure `kidMobileRow`, in "Tilkobling og skjerm", after Wi-Fi)

`controlsSection` gains the bit: `Rows.mobileData`, `describe()`, and a mask of only 32 is a card, not `NoneEnabled`
(Q97-L1).

| State | Switch | Line under it (nb / en) |
|---|---|---|
| bit 32 off | no row | - |
| the parent decides now, no lift: `apps_mobile == "vibb"`, or abroad under Ingen data or Bare Vibb | Off, 50 %, not clickable | "Bestemt av mamma og pappa" / "Set by mum and dad" |
| Android's mobile data off | Off, 50 %, not clickable | "Mobildata er slått av på telefonen" / "Mobile data is off on the phone" |
| the kid's On | On | - |
| the kid's Off | Off | "Bare Vibb One bruker mobildata. Wi-Fi virker som før." / "Only Vibb One uses mobile data. Wi-Fi works as before." |

A tap stores the choice, re-decides the shape, and calls `CommandListenerService.requestSync(context, "kid mobile
data")`. When bit 32 goes, the stored choice resets to On (D7).

## 5. The PWA (`/devices/{id}/mobile`)

This is a phone sub-page: no bell, and each group saves to its own anchor, never the top. The groups are
**Mobildata** (`#mobile-data`), **I utlandet** (`#abroad`, replacing `#data-roaming`, which nothing links to), and the
lock (`#mobile-lock`). The kid's switch is a row of its own in Mobildata (`group=kid-mobile`, which writes bit 32
alone). The Home Screen page's quick-controls form keeps bit 32 on both `section=quick` and `section=all`
(`(quick_controls_mask & 32) | ?`), and its footer gains "Mobildata-bryteren styres under Mobilnett."

```
Mobilnett                                                   (nb)
MOBILDATA
  ( ) På               Alle tillatte apper kan bruke mobildata.
  (•) Bare Vibb        Apper og nettleser stopper. Du styrer telefonen, ser posisjon og får varsler som før.
  ⓘ Hva Bare Vibb stopper
  Barnets bryter for mobildata                       [on]
      Emma kan slå mobildata av på telefonen. Da bruker bare Vibb mobildata. Den kan bare stramme inn.
      · Emma har slått mobildata av                      (state line, kid = "off")
I UTLANDET
  (•) Ingen data   Ingen mobildata i utlandet. På et skips- eller flynett som telefonen ikke regner som roaming,
                   får bare Vibb data.
  ( ) Bare Vibb    Vibb holder kontakten med deg; apper og nettleser venter på Wi-Fi. Utenfor EU/EØS kan det
                   starte en dagspris.
  ( ) Som hjemme   Mobildata som hjemme. Kan koste mye utenfor EU/EØS.
  · Androids roamingbryter: av og låst
  · Roamer i Sverige
  ⓘ Om utlandet
  Gjelder utenfor landet SIM-kortet hører til. Wi-Fi virker alltid.
LÅS MOBILNETT-INNSTILLINGENE                                [on]
```

```
Mobile network                                              (en)
ABROAD
  (•) No data      No mobile data abroad. On a ship or plane network the phone doesn't count as roaming,
                   only Vibb gets data.
  ( ) Only Vibb    Vibb keeps in touch with you; apps and the browser wait for Wi-Fi. Outside the EU/EEA it can
                   start a day price.
  ( ) Like at home Mobile data as at home. Can cost a lot outside the EU/EEA.
  · Android's roaming switch: off and locked
  Applies outside the SIM card's country. Wi-Fi always works.
```

The `{name}` in these lines is the phone's own (`device.name`); the examples use Emma. There is no "anbefalt" mark
unless the user chooses one in D1 (Q97-M8).

**The switch line** (`mobile.abroad.switch.*`) is built from the choice and the report's `enabled`, `locked` and
`settings_locked`, plus the pause. It replaces `phone_on`/`phone_off`. No line ever asks the parent to wait (Q97-M6):

| Case | nb | en |
|---|---|---|
| Ingen data, locked | Androids roamingbryter: av og låst | Android's roaming switch: off and locked |
| Bare Vibb/Som hjemme, `enabled`, settings lock on | Androids roamingbryter: på – låst, {name} kan ikke endre den | Android's roaming switch: on – locked, {name} can't change it |
| … `enabled`, settings lock off | Androids roamingbryter: på – {name} kan endre den i Innstillinger | Android's roaming switch: on – {name} can change it in Settings |
| … not `enabled` | Androids roamingbryter: av – slå den på én gang under Åpne for oppsett: Innstillinger › Nettverk og internett › SIM-kort › Roaming. Låsen holder den på etterpå. | Android's roaming switch: off – turn it on once during Open for setup: Settings › Network & internet › SIMs › Roaming. The lock keeps it on afterwards. |
| paused | Androids roamingbryter: {på/av} – åpnet for oppsett nå | Android's roaming switch: {on/off} – open for setup now |

While Android's switch is on, Ingen data's sub-line adds "Velger du noe annet senere, må roaming slås på igjen på
telefonen." / "If you choose something else later, roaming must be turned on again on the phone."

**The ⓘ "Om utlandet":** Bare Vibb abroad still uses a little data: Vibb's own, a few hundred KB a day, plus Android's
own checks. Ingen data is zero, except on a ship or plane network that Android doesn't count as roaming; there only Vibb
gets data. An app that asks for mobile data while the phone is on Wi-Fi: see §8. Calls and SMS are unaffected, and
MMS waits for Wi-Fi under Ingen data.

**Other words.**
- The row value: "Bare Vibb · Utlandet: som hjemme · Mobildata av (Emma)", only the parts away from the defaults.
- The summary: "Mobildata: bare Vibb – Emma har slått av" / "Mobile data: only Vibb – Emma turned it off", and
  "Mobildata: bare Vibb i utlandet" / "Mobile data: only Vibb abroad".
- National roaming under Ingen data (`roaming && where == "home"`): "Nasjonal roaming: Ingen data stenger mobildata her
  – velg Bare Vibb eller Som hjemme" / "National roaming: No data cuts mobile data here – choose Only Vibb or Like at
  home".
- Every string is in `en.json` and `nb.json`. Revision 1's "the settings log names the new choice" is dropped:
  `settings_log` records routes, not values (Q97-L2).

**The country phrases are restructured, not doubled** (Q97-L4). The 32 `data_roaming.in.<cc>` keys become
`data_roaming.place.<cc>` ("i Sverige", "på Island"; "in Sweden", "in the UK"). The page uses them through
`data_roaming.in` "Roamer {place}", and the notice uses them through its own title. The skipped-key prefix in
`i18n::tests::every_key_is_used` and the test `every_named_country_has_its_phrase_in_both_languages` move to
`data_roaming.place.`.

## 6. The notice: `Kind::RoamingStarted` (`roaming_started`)

- **Parameters** `{name, place, at}`. `place` is stored as a **code**: a country code, `"ship"`, or absent. It is
  worded at read time in the reader's language, as every kind since design 70.
- **Titles**:
  - `events.roaming_started.title`: "{name} er på roaming {place}" / "{name} is roaming {place}";
  - `.ship`: "{name} er på roaming på et skips- eller flynett";
  - `.elsewhere`: "{name} er på roaming ({CC})";
  - `.plain`: "{name} er på roaming".
  The summary and the push body are the clock alone. The target is `/devices/{id}/mobile#abroad`.
- **Once per trip** (Q97-M4). The latch is `devices.roaming_trip_at` and `devices.roaming_home_at`, driven by the pure
  `trip_edge(trip_at, home_at, where, now)` and only by reports from a `mobile_network_v2` launcher:
  - `abroad` with no trip open: raise the notice and open the trip;
  - `abroad` with a trip open: clear `home_at`;
  - `home`: set `home_at` if it is empty, and close the trip once `home_at` is **6 h** old;
  - `unknown` (airplane mode, no service, no SIM): **nothing**.
  So a night in airplane mode, a border cell flipping back and forth, a ferry, or Sweden then Denmark is one notice. The
  update is conditional and in one transaction with the raise, as `battery_report`; `dedup_key` is the trip's start.
  Unit rows cover each case.
- **Retention** (Q97-M5): pruned with the phone's `location_retention_days`, as zone events are, not after 90 days.
  Under location mode Off the notice carries no place (D8).
- **Switch** `push_roaming` ("Roaming", on by default) in Notify Me About. TTL 43200, normal, foldable, no
  `PushBudget` (at most one per trip).
- A phone under Ingen data reports abroad only once it is on Wi-Fi, so its notice comes then.

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

**The rule:**
- `off` -> Ingen data.
- `phone` -> Som hjemme **only** if the newest stored roaming report says Android's switch was on. That phone had full
  data abroad and keeps exactly that.
- Every other `phone` (switch off, unreadable, never reported) -> Ingen data, which is what it really had abroad.

No phone gains data abroad. `data_roaming` stays as a column and is no longer read or written; the server serves the
derived value. A server test covers the four `phone` cases (`enabled` true, false, null, never reported).

## 8. Risks

- **The parent's step, on every phone (D9).** It is needed after setup, after each move back to Ingen data, and after a
  new SIM (a new per-SIM key starts at the carrier's default). The switch line says so each time.
- **The border:** under Bare Vibb, the first roaming network carries every app's traffic until the watcher's callback
  establishes the sink. 95 measured the tunnel re-established 64 ms after Wi-Fi was lost. Android's validation probe
  always goes out. Outside the EU/EØS that can start a day price, and the sub-line says so. A flap after that keeps the
  sink (§4.2).
- **A cellular session next to Wi-Fi** (Q97-M7). With Android's switch on abroad, an app that asks for cellular while
  the phone is on Wi-Fi might get roaming data, as might Android's "switch to mobile data when Wi-Fi is poor". The
  expectation: our VPN is not bypassable, so netd refuses a covered uid's socket on another network (EPERM), and only
  system uids could. **This is measured in the build's emulator step (§10 test 6), not assumed.** If a normal app gets
  through, the build stops there and the measured result goes to the user as a decision before anything ships. It
  affects Bare Vibb and Som hjemme only: under Ingen data, Android refuses roaming data to everyone.
- **National to foreign roaming without a network change:** no capabilities callback may come, so the abroad sink
  waits for the next `apply()` (at most 30 min). This is rare (a national-roaming area at the border). If a report
  shows it, add 95 §2.2's `ServiceStateListener`.
- **Ingen data in a national-roaming area** cuts data at home (unchanged). The warning line names it.
- **An older launcher under Bare Vibb** has no data abroad (the derived `"off"`), and the page says so.
- **The Home Screen form** must keep bit 32, which a test pins.

## 9. Build steps and sizes

1. **Contract, both directories, one commit, S.** `abroad` and the derived `data_roaming`; `where`, `kid`, the new sink
   words, `mobile_network_v2`; DTOs, `clean_*`, the snapshot tests, and both copies of `mobile_network.json`.
2. **Launcher rules and runtime, S-M** (M in revision 1; the writer is gone). `abroadChoice`, `where` and
   `BestNet.abroad`, the two new sink reasons in one order, keep-on-doubt with every armed reason, the pass rule,
   `vpn_inputs_r4`, and the report fields. `MobileNetworkTest` runs every new contract row, including the
   upgrade/rollback test.
3. **Launcher kid row, S.** Bit 32 in `controlsSection`, `kidMobileRow`, the row and its strings (nb + en), the stored
   choice and its reset, the re-decide and the sync. `KidSettingsModelTest` covers a mask of 32 alone.
4. **Server page and migration, M.** 0145 with its four-case test, the "I utlandet" radios (`data_roaming_v1`), the
   kid switch, the switch lines, the restructured country phrases and their tests, the row value and summary lines, and
   the Home form keeping bit 32 on both sections. Every page test: anchors, no bell, nb + en.
5. **Server notice, S.** `Kind::RoamingStarted`, `trip_edge` with `unknown`, the latch, pruning with
   `location_retention_days`, D8's place-less form, the switch, and the words in both languages.
6. **Emulator run (§10), S-M** (S in revision 1). It now includes the Wi-Fi bypass measurement; a leak stops the build
   (§8).

## 10. Emulator tests (`jelly36`, 95 §1.1's private image with a Norwegian SIM, `docs/testing/emulator.md` §6ak)

Android's roaming switch is turned on by hand during Åpne for oppsett, as the parent will do.

1. **Abroad, Bare Vibb:** network `311740`, `gsm data roaming` + `gsm voice roaming`. The sink reads `abroad`,
   another app gets RSTs, the launcher syncs, and the report reads `where: "abroad"`. One notice. Flip `gsm data home`
   / `roaming` three times: still one notice.
2. **A flap and a reboot:** under the sink, airplane on and off, `gsm data unregistered` and back, and a reboot.
   `always_on_vpn_lockdown` stays 1 throughout, and `vpn_shape` stays `sink` before the first sync.
3. **The upgrade:** rc.1 installed with the parent's Bare Vibb on cellular (SINK persisted), the r4 build installed
   over it. Before the first sync, `vpn_shape` is `sink` and lockdown is on.
4. **Android's switch:** Ingen data -> `settings get global data_roaming` 0 and locked. Choose Bare Vibb -> the page
   says "turn it on once". Turn it on during Åpne for oppsett; after the pause it stays on, the page reads "på – låst".
   It still reads on after a restart and after a SIM reload (`adb emu gsm` SIM off and on). Back to Ingen data -> 0.
5. **National roaming:** a second image copy with `CIMI 311480…` (MCC 311 as the network, another name) reads
   `roaming: true, where: "home"`. Under Bare Vibb: no sink and no notice. Airplane mode overnight while abroad
   (test 1's image) reads `unknown` and leaves the trip open.
6. **The Wi-Fi bypass (Q97-M7):** abroad, Bare Vibb, on Wi-Fi with roaming data up. A throwaway app with
   `CHANGE_NETWORK_STATE` does `requestNetwork(CELLULAR)` and fetches over it with `bindSocket`. Expected: EPERM. Run it
   under Som hjemme too, as the control.
7. **The kid's switch:** bit 32 alone set, then Off. On Wi-Fi the filter; on cellular the sink `kid`; the report
   `kid: "off"`; the PWA line. The parent's Bare Vibb -> 50 % "Bestemt av mamma og pappa". Hide the bit -> the row goes
   and the switch is On again.
8. **The pause:** the abroad sink and the roaming lock lift within a second, and the kid's Off stays.
9. **The page:** both languages, each save on its anchor, and the Home form keeps bit 32 on `quick` and `all`.

Not on the emulator (it has one modem): the dual-SIM per-SIM key. That comes from the Jelly Star's reports after the RC.
No adb and no debug build on the kid's phone.

## 11. Decisions for the user

- **D1. The default, and the "anbefalt" mark:** (a) Ingen data by default with no mark (recommended: zero bytes, and
  Bare Vibb's day-price line sits next to it for the parent to weigh); (b) Ingen data by default with Bare Vibb marked
  "anbefalt"; (c) Bare Vibb by default (it can start a day price outside the EU/EØS, and on the Jelly Star it needs the
  parent's step anyway).
- **D2. The migration:** `phone` becomes Som hjemme only where the phone reported Android's switch on, else Ingen data
  (recommended: no phone gains data abroad).
- **D3. The kid's switch:** hidden until the parent turns it on, for new and existing phones (recommended, as 95
  §4.1), or shown on new phones.
- **D4. Does Åpne for oppsett lift the kid's own Off?** No (recommended: it is the kid's choice, and whoever holds the
  phone can flip it).
- **D5. Where the parent shows or hides the kid's switch:** on the Mobilnett page, with a line on the Home Screen
  page (recommended).
- **D6. One notice per trip:** a new trip only after 6 h reported **in service** at home (recommended); not per
  country, and not for a ship network in the middle of a trip.
- **D7. Hiding the kid's switch resets it to On** (recommended).
- **D8. Location Off and the notice:** under location mode Off the notice says "Emma er på roaming" without the
  country (recommended: the phone tells the kid it doesn't share where it is; the page's live line still names the
  country, which is the cost fact, and is kept only for hours), or the country always.
- **D9. Vibb turning Android's roaming switch on itself (one-modem phones only):** cut (recommended: the only real
  phone, the Jelly Star, is dual standby, where a device owner can't; the writer added an avoidable leak window and a
  "wait" state; the parent's one step works on every phone). Or build it, which adds about S-M to the launcher plus an
  emulator step, worth doing once a one-modem phone shows up in `modems`.

## 12. Design QA (`docs/review/qa-97-design.md`): how each finding was handled

| Finding | Handled |
|---|---|
| H1 a 901 network not flagged roaming | §4.1: 901 is always abroad. §4.2: the `abroad` sink also under Ingen data. §5: Ingen data's text no longer promises zero there. §3.3: contract rows. §6: it counts for the trip |
| M1 the write before the sink | Gone with the writer (D9) |
| M2 keep-on-doubt and the order | §4.2: one order for all three functions, and the sink kept for every armed reason. Contract rows with `cellular: null`. §10 test 2 |
| M3 the persisted inputs | §4.2: rc.1's record untouched plus `vpn_inputs_r4`. Upgrade and rollback unit tests. §10 test 3 |
| M4 a positive home signal | §3.2/§4.1: `where` with `unknown`. §6: `unknown` is neither home nor abroad. §10 test 5 |
| M5 the notice's retention | §6: `location_retention_days`. D8 |
| M6 a "wait" forever | No writer, so no wait line. "Turn it on once" whenever the switch reads off. §10 test 4 covers a restart and a SIM reload |
| M7 cellular next to Wi-Fi | §8 and the ⓘ name it. §10 test 6 measures it, and a leak stops the build for a user decision |
| M8 the cost behind the ⓘ | The day price is in Bare Vibb's sub-line, and the "anbefalt" mark moves into D1 |
| L1 a mask of 32 alone | §4.3, `KidSettingsModelTest` |
| L2 spec inconsistencies | Radios on `data_roaming_v1` with the older-launcher line (§3.2). The lift row is none, or the kid's sink (§4.2). `kid_row` has `lift`. Old `shapes` rows are read via `abroadChoice`. The settings-log sentence is dropped |
| L3 multi-MCC countries, the Norway wording | Country ISO first, MCC as fallback (§4.1). "Utenfor landet SIM-kortet hører til" |
| L4 the phrase tests, the notice's keys | §5's moved tests. §6: own keys for ship, elsewhere and plain, and `place` stored as a code |
| L5 the kid row's words | "Mobildata er slått av på telefonen" and "bruker mobildata" (§4.3) |
| L6 cut the one-modem write | Cut, and put to the user as D9 |
| L7 the test gaps | §10 tests 2-6 and 9; unit rows for 901, `trip_edge`, the migration's four cases, and the Home form's two sections |
