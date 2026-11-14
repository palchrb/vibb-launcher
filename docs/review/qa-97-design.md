# Design QA: Mobilnett revision 4 (`docs/design/97-mobilnett-r4.md`)

Checked against `mobilnett-r4` (5cbc0ee3, = `main` 9228b85c + the note). Read: design 94 and 95 (rev. 3, the built
round), the launcher's `server/MobileNetwork.kt`, `server/MobileNetworkRuntime.kt`, `server/KidVpnService.kt:184-370`,
`server/AppEnforcer.kt:340-370,595-625,1448-1460`, `server/QuickControls.kt`, `ui/kidsettings/KidSettingsModel.kt`; the
server's `src/mobile_network.rs`, `handlers/mobile_page.rs`, `templates/device_mobile.html`, migrations 0143/0144,
`handlers/devices.rs:1012-1027,2881-2912` (the Home Screen form), `parent_events.rs` (Kind, switches, the battery
latch), `retention.rs`, `status_heartbeat.rs`, `settings_log.rs`, `i18n.rs:896-925`, `i18n/en.json` (`mobile.*`,
`data_roaming.*`), `docs/notes/server/notifications.md`, and the contract `server/testdata/mobile_network.json` (both
copies identical).

**Verdict: the right shape.** One choice with a derived `data_roaming` for an older launcher, `LEGACY` for an older
server, and the kid's switch as one more sink reason all hold up against the code. The older-launcher and older-server
paths never give more data abroad than chosen (checked below). The problems are in the edges of the sink:
a ship network Android doesn't flag as roaming, the order of the roaming write against the sink, the "keep the sink
on a doubt" rule and the persisted inputs. The notice's latch and its retention need two fixes.

Counts: **1 High, 8 Medium, 7 Low.**

## Findings

### Q97-H1 High - a ship or plane network that Android doesn't flag as roaming gets full data under both "Ingen data" and "Bare Vibb"

Design 94 already knows this case (94 §2.5 and the line `data_roaming.ship_home`: "carrier config can mark one as
home", "the lock doesn't cover it"). Revision 4 now promises the opposite on the page: Ingen data reads "Ingen mobildata
i utlandet, **heller ikke på skips- og flynett**", the ⓘ says "A ship's or plane's network is always abroad", and §5
says "Ingen data is the only zero". As designed:

- Ingen data: `DISALLOW_DATA_ROAMING` doesn't apply to a network Android counts as home, and the `roaming` sink needs
  `net.roaming` (`sinkReason`, `MobileNetwork.kt`). Full data for every app at maritime prices.
- Bare Vibb: `abroad = roaming && !domestic` is false, so no `abroad` sink. Full data again.
- The notice doesn't fire either.

Scenario: a Color Line or Hurtigruten crossing on a carrier whose config lists the maritime 901 network as home. The
parent chose Ingen data because the radio promised no ship data, and the phone uses data at sea prices.

Change: in the launcher, `abroad = (roaming && !domestic) || networkMcc == "901"` (the network MCC is already read for
`domestic`). Under every choice except Som hjemme, sink on cellular while `abroad && !roaming` (a new reason, or
`abroad` for both). The lock can't cover that case, so the Ingen data text should say what happens there ("på skips- og
flynett bruker bare Vibb data" or similar), not promise zero. Add contract `shapes` rows for 901 with
`roaming: false` under each choice, and count 901 as abroad for the trip latch too.

### Q97-M1 Medium - the roaming write and the kick come before the sink

`AppEnforcer.apply()` runs `MobileNetworkRuntime.applyDataRoaming(...)` at `AppEnforcer.kt:611`, before
`applyVpnRestrictions(...)` at `:625`, and `vpnShapeNow` is decided from `bestNet()` before the write. Abroad with
Android's switch off there is no cellular network, so `net` is `null` and the decided shape under Bare Vibb is PASS or
FILTER, with no lockdown. The write plus the kick (`requestNetwork(CELLULAR)`) then bring roaming data up. Every app has
it until the watcher's `onCapabilitiesChanged` reshapes to SINK (95 measured 64-385 ms for comparable switches).

Unlike the border case in §8, Vibb causes this window itself, and it is avoidable. Scenario: the parent switches from
Ingen data to Bare Vibb while the kid is in the US (one-modem phone). Background sync from any app goes out in that
window, outside the EEA.

Change: do the write after `applyVpnRestrictions`. When the choice is VIBB, there is no network, and telephony reports
`isNetworkRoaming && !domestic` (registration comes before the data attach), decide SINK before the write. Lockdown with
no network costs nothing. Kick only once the sink's tunnel is established. Add it to §10 test 2: a second app's
connection attempts during the write must all fail.

### Q97-M2 Medium - the "never lockdown off on a doubt" rule doesn't know the new sink reasons

`applyShape` and `reportedSink` (`MobileNetwork.kt`) keep the sink with `net == null` only while
`inputs.parentSink || inputs.roamingLocked`. Under Bare Vibb abroad (`parentSink` false, `roamingLocked` false) or the
kid's Off, a moment with no network (a cell change, a tunnel, a modem restart, or `apply()` running just then) makes
`apply()` decide FILTER or PASS and write lockdown off (`writeAlwaysOn`). The next roaming network then comes up
without the sink, which is the flap window the user asked about, repeated at every flap. `serviceShape` has the same
problem before the watcher's first callback, for example after a reboot abroad.

Change: the keep condition becomes "any configured, unlifted sink reason that doesn't need the network": parent,
`choice == VIBB`, roaming lock, and the kid's Off with bit 32 set. `reportedSink` names `abroad`/`kid` in the same
order as `sinkReason`. Add contract `shapes` rows with `cellular: null` for VIBB-abroad-persisted-sink and kid-Off.

### Q97-M3 Medium - the persisted VPN inputs can't carry the new inputs, and an upgrade drops the sink until the first `apply()`

`MobileNetworkRuntime.encodeInputs`/`decodeInputs` store three flags as `"1,0,1"`, and `decodeInputs` returns `null`
unless there are exactly 3 fields. The service's decision (`KidVpnService.wantedShape` → `storedInputs()`) needs the
choice (VIBB, unlifted) and the kid's state (bit 32 + Off). Without them in the record, the service and `sinkWanted`
(`AppDownloads`) can't decide the new sinks at all. When they are added:

- The new decoder must still read rc.1's 3-field record. Otherwise, after the self-update, `storedInputs()` falls back
  to `parentSink = false` and the cold-started service leaves a persisted SINK before the first `apply()`.
- An rc.1 launcher reading a longer record (a rollback) returns `null` and does the same.

Scenario: the RC installs over rc.1 while the phone sits on cellular under the parent's Bare Vibb. The new process
starts the always-on VPN, reads no inputs, and writes lockdown off until the next sync.

Change: say in §4 that the record gains the fields, with a tolerant decode (3 fields → the new ones false, more than
known → the known prefix). The kid's tap writes the kid field into the same record before it calls `applyShape`, so the
service and `apply()` read one source. Add a unit test for both directions.

### Q97-M4 Medium - "home" for the trip latch includes no service and airplane mode, so a trip can produce a notice a day

The report's `roaming` is `false` "with no SIM or no service" (`DataRoamingReport.roaming`), and airplane mode reads
the same. §6 counts every `!(roaming && !domestic)` report as home. Scenario: a family in Spain whose kid's phone goes
into airplane mode every night on hotel Wi-Fi (a common way to avoid roaming costs). The night's reports read home,
`home_at` passes 6 h, the trip closes, and the next morning raises "Emma er på roaming i Spania" again. That is one
notice a day for the whole holiday, and there is no `PushBudget` behind it. A family living at a border (Halden,
Kirkenes) gets one each time the phone catches a foreign cell more than 6 h after the last one.

Change: report a positive home signal, for example `data_roaming.in_service` (from `ServiceState`) or a three-way
`where: home|abroad|unknown`. Only an in-service, not-abroad report counts toward `home_at`, and no service is neither.
Optionally raise only when two consecutive reports (or ≥ 10 min) read abroad. Put the rule in `trip_edge` with unit
rows.

### Q97-M5 Medium - the notice keeps the country for 90 days, longer than any other location record

§6 says "Retention as the other kinds (90 days)" for an event whose parameters name the country. The project's rule is
the opposite for location-bearing events. Zone events are pruned with `location_retention_days` (`retention.rs:384-399`,
design 64 decision 6). `phone_off`'s position is cleared after the location retention (notifications note). The
report's `country` itself lives only `STATUS_HISTORY_HOURS` in `device_status`. Under location mode Off (the kid's
phone says it doesn't share where it is: `sharesLocation`), the notice would still keep a 90-day country trail.

Change: prune `roaming_started` (or clear its `place` parameter) with the phone's `location_retention_days`, as zone
events are. Put "Location Off: the notice without the country" to the user as a decision (D8).

### Q97-M6 Medium - the write's retry budget and the switch line can leave the parent waiting for a write that never comes

The write runs "at most once per policy change and once per process start", and the report carries no result of it.
The line "av – Vibb One slår den på ved neste kontakt" is chosen from `enabled == false && modems == 1` alone. §8 says a
refusing OEM gets the "turn it on once" line, but the server can't tell a refused write from a pending one. Cases
where the parent is told to wait:

- An OEM that refuses the bare write.
- The 5 s `ACTION_USER_RESTRICTIONS_CHANGED` wait timing out (95 §1.2 marks it not measured).
- Telephony resetting the bare key to the carrier default on a SIM or carrier-config load, which isn't a "user action".
  Verify this on the emulator.
- An rc.1 phone with `modems == 1` under Som hjemme, which never writes.

Each case shows "turns it on at its next contact" indefinitely while the kid is abroad without data. This fails closed,
but the parent's choice is silently not honoured.

Change: add `data_roaming.write: "done" | "refused" | null` to the report (contract `report` row). The "turn it on once"
line shows on `refused`, or when no `mobile_network_v2` is claimed. Re-check `isDataRoamingEnabled` on each `apply()` and
on the watcher's abroad edge, capped (for example three tries an hour), instead of once per process. Add a restart and a
SIM reload to §10 test 2.

### Q97-M7 Medium - a cellular session next to Wi-Fi bypasses Bare Vibb abroad, now with Vibb's own switch on

The sink exists only while the best network is cellular. On Wi-Fi the shape is FILTER or PASS, without lockdown
(`alwaysOnFor`). Rev. 3's own note (`vpnShape`, code QA M2) says a cellular session survives a stay on Wi-Fi ("mobile
data always active"). Any app with `CHANGE_NETWORK_STATE` (a normal permission) can `requestNetwork(TRANSPORT_CELLULAR)`
and use it, and so can Android's "switch to mobile data when Wi-Fi is poor" before the default network changes. Under
Bare Vibb, revision 4 now turns Android's roaming switch on itself, so abroad on hotel Wi-Fi such an app gets roaming
data. Before revision 4, Ingen data was the only behaviour Vibb set up. §8 doesn't name this.

Change: measure it in §10 (a test app requesting cellular while on Wi-Fi under Bare Vibb abroad) and name it in §8 and
the ⓘ. If it leaks, consider dropping the cellular session while on Wi-Fi under VIBB abroad (`DATA_ROAMING` back to 0 on
Wi-Fi and 1 when Wi-Fi drops, at the cost of the write's latency), or accept it as a decision for the user.

### Q97-M8 Medium - "Bare Vibb · anbefalt" hides its cost behind the ⓘ

Bare Vibb's sub-line reads "Vibb holder kontakten med deg. Apper og nettleser venter på Wi-Fi.". Only the ⓘ (and §8)
says that Vibb's own data plus Android's checks start a day price outside the EU/EØS on Norwegian plans (95 §4.4). The
choice marked "anbefalt" can therefore cost about 100 kr a day in the US while the parent believes only Vibb's KB go
out. The user's standing rule: a design's own costly rule goes to the user before it is built (visible-cost choices
first).

Change: add the cost to Bare Vibb's sub-line ("Utenfor EU/EØS kan det starte en dagspris" / en). Put the "anbefalt"
mark in D1, so the user decides it with the cost in view.

### Q97-L1 Low - a mask with only bit 32 shows no kid row

`controlsSection` (`KidSettingsModel.kt:52-63`) returns `ControlsSection.NoneEnabled` unless one of the five old rows is
set, so a parent who turns off every other quick control and keeps the Mobildata switch gets no row. `Rows` and
`describe()` need the new field, and `NoneEnabled` must test it. Add a `KidSettingsModelTest` row for mask `32`.

### Q97-L2 Low - spec inconsistencies to settle before the build

- §3.1 says an older launcher under Bare Vibb has no data abroad "and the page says that it needs a newer Vibb One". §3.2
  says the "I utlandet" radios wait for `mobile_network_v2`, and today's template disables radios when the capability is
  missing (`device_mobile.html`). Decide one: radios enabled for `data_roaming_v1` (the derived value works) with the
  Bare Vibb row noting "no data abroad until Vibb One is updated", or disabled.
- §4's table row "Bare Vibb | abroad | yes | … | filter / off": under a lift `vpnFilterEnabled` is false
  (`AppEnforcer.kt:358`), so the shape is none (or PASS with the kid's Off), not filter.
- The `kid_row` contract inputs leave out `lift`, but the "parent decides" state depends on it (§4's row table).
- Existing contract `shapes` rows carry `data_roaming` and no `abroad`. Say that they are read through `abroadChoice`
  as an older server's rows (`LEGACY` for `"phone"`).
- §7 "The settings log names the new choice": `settings_log` never records a form's values (`settings_log.rs:7-8`), only
  the route. Drop the sentence or write a security event of its own.

### Q97-L3 Low - `domestic` by MCC misses multi-MCC countries and hard-codes Norway in the words

`getNetworkOperator().take(3) == getSimOperator().take(3)` reads national roaming as abroad where a country has several
MCCs: US 310-316, UK 234/235, India 404/405/406, Japan 440/441. That gives a false `abroad` sink and a false notice.
The ⓘ "Gjelder når telefonen er utenfor Norge … Nasjonal roaming i Norge" names Norway, which is wrong for an English
UI elsewhere (the i18n-everywhere rule).

Change: compare `getSimCountryIso()` with `getNetworkCountryIso()` first (neither needs a permission), with MCC as the
fallback and 901 always abroad. Word the ⓘ as "utenfor landet SIM-kortet hører til" (en "outside the SIM's country").

### Q97-L4 Low - the country-phrase restructuring needs its tests and the notice's parameters spelled out

- `i18n::tests::every_key_is_used` skips keys by the prefix `"data_roaming.in."` (`i18n.rs:916`), and
  `mobile_network::tests::every_named_country_has_its_phrase_in_both_languages` reads `data_roaming.in.{cc}`. Both
  move to `data_roaming.place.`.
- The ship, unnamed-country and no-country notice titles need keys of their own: today's `data_roaming.ship`,
  `elsewhere` and `plain` are whole "Roaming …" phrases, not places.
- The notice's `place` must be stored as a code (`cc`, `"ship"` or absent), not as words, so that `told` words it in
  the reader's language. Say so in §6.

### Q97-L5 Low - the kid row's words

"Bestemt av mamma og pappa" for "Android's mobile data off" is wrong. The parent has no control that turns it off
("Helt av" from the PWA is out of scope), so it was the phone, the kid, or Settings during setup. Use something like
"Mobildata er slått av på telefonen". The Off line "Bare Vibb One bruker mobilnettet" can read as calls being cut, and
calls and SMS go on. Say "mobildata" ("Bare Vibb One bruker mobildata. Wi-Fi virker som før.").

### Q97-L6 Low - possible cut: the one-modem write machinery may never run on the target phone

§2 expects the Jelly Star to be dual-standby, so `modems == 2`, and the write never applies there. The clear, the wait
for the broadcast, the write, the read-back, the kick, the retry budget and their switch line are M-sized work and are
the source of M1 and part of M6, for the emulator and an unknown one-modem phone. Cut option: the parent's one step on
every phone (one switch line, no writer). Or build the write only once rc.1's stored `modems` (the SQL in §2) shows a
one-modem phone in the family.

### Q97-L7 Low - test-plan gaps

§10 and §9 don't cover:

- a network flap (airplane on/off, `gsm data unregistered`) under Bare Vibb abroad keeping the sink (M2);
- an upgrade from an rc.1 install with a persisted sink, with `vpn_shape` checked before the first sync (M3);
- reboot persistence of `data_roaming = 1` and a SIM reload (M6);
- another app's traffic during the write and the kick (M1);
- airplane mode overnight abroad → still one notice (M4);
- unit rows for a 901 network without the roaming flag (H1);
- a server test of the migration's four `phone` cases (enabled true, false, null, never reported);
- the Home Screen form keeping bit 32 for both `section=quick` and `section=all` (`devices.rs:2906-2912`).

## Checked, no finding

- **An older launcher on a new server:** `vibb` → `"off"` and `home` → `"phone"`. rc.1's `dataRoamingLocked` locks on
  `"off"` and never writes on `"phone"`, so it never gets more than chosen. An rc.1 launcher ignores bit 32
  (`controlsSection` reads only bits 1-16).
- **A new launcher on an older server:** an absent `abroad` gives `LEGACY` for `"phone"` and no write, and `OFF`
  otherwise. A server downgrade after 0145 doesn't start: the default `sqlx::migrate!` refuses a database with an
  applied migration it doesn't know (`main.rs:1917`, no `ignore_missing`), so a stale `data_roaming` column is never
  served.
- **The write racing the restriction:** a write of `1` while `DISALLOW_DATA_ROAMING` is set is refused, and setting it
  writes `0` on every SIM. Whichever order a concurrent `apply()` lands in, the lock wins.
- **Bit 32:** free on both sides (server `QUICK_CONTROL_*` 1-16, `devices.rs:1015-1024`; launcher `QuickControlFeature`
  1-16). The only writer that rebuilds the mask is the Home Screen form (`devices.rs:2881-2896`), which §5 covers. New
  phones get `DEFAULT_QUICK_CONTROLS` = 15 (hidden, D3).
- **Migration 0145:** `ADD COLUMN … NOT NULL DEFAULT 'off' CHECK (...)` passes on existing rows (0143 did the same). A
  JSON `true` reads as `1` from `json_extract`. The newest `device_status` row per phone survives retention
  (`retention.rs:291-298`), so the subquery has its row. `parent_events.kind` has no CHECK, so a new kind needs no
  rebuild.
- **The heartbeat:** `data_roaming` and `mobile_data` are heartbeat fields, and an expanded heartbeat runs the full
  intake (`status_heartbeat.rs:9-14`), so the latch also sees heartbeats.
- **`#data-roaming`:** nothing outside the page links to it. The summary's ` data-roaming>` attribute is a different
  hook.
- **The contract:** `mobile_network.json` is already in both CI path lists (CLAUDE.md), and both copies are identical
  today.
