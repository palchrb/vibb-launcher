# Emulator check: real roaming with a Norwegian SIM (designs 95 §1.1 and 97), 2026-11-19

The night's check, run under the user's OK for the emulator: design 95 §1.1's private image rebuilt with a Norwegian
SIM, and Vibb's abroad rules (design 97) run against networks Android itself reads as Norwegian, Swedish, Danish,
American and a ship's. One emulator at a time under `flock -o ~/emulator.lock`.

**Rig.** `jelly36` (Android 16, the Google Play image `android-36;google_apis_playstore;x86_64`, emulator 37.2.13),
restored from `~/kid-look-1-avd-pre-start` afterwards (22 of 22 sums, the same file tree). Main's debug launcher and
debug server (`ccfe28afd`; the code is `24b7c7967`'s, the commits after it touch only PLAN.md), the launcher as Device
Owner, enrolled with a typed code to a phone made through the PWA's Add Phone - so a new phone's defaults: Roaming
"Bare Vibb" (`abroad: vibb`), Mobildata "På", mask 239 (the kid's switch shown), the mobile-network lock on. The setup
hold ended from the PWA ("Lås igjen"). Android's roaming switch reads on (`data_roaming` 1) on this image. The other
app is a throwaway probe (`me.vibb.probe.roam`, debug-signed, let out with Allow these apps) fetching
`http://10.0.2.2:3130/` (an IP literal) and `https://www.google.com/generate_204` (a name). Wi-Fi off unless a row says
otherwise. Android 14 and 17 not run.

## How roaming is made now

**What changed since design 95 §1.1 and emulator.md §6al.** Emulator 37.2.13's modem simulator registers on the
**SIM's own PLMN** when the image's `misc/modem_simulator/etc/modem_simulator/files/numeric_operator.xml` names it:
with the SIM `24201` and a `24201` row in that file, the network is `24201`, `no`, home. Without a row for the SIM's
PLMN it falls back to its first built-in operator, `311740` (`us`) - which is §1.1's old picture (SIM `24201` on
`311740`). So "home" is now a real Norwegian network, and the built-in list is the only way to another country.

**What does not work** (tried): `modem_nvram.json`'s manual selection (`network_selection_mode` 1 with an
`operator_numeric`) - Android sets automatic selection at every boot (`setNetworkSelectionModeAutomatic`, no saved
manual network), and an operator not in the built-in list never registers; Settings › SIM › Choose network - "Couldn't
find networks" (its scan is built with `allowedNetworkTypes = []`, `ERROR_INVALID_SCAN`); `cmd phone cc set-value`
(Permission denied on this user build) and `cmd phone set-allowed-network-types-for-users` (failed); the console has no
operator command, and the gRPC modem service's `CellIdentity` carries names only.

**What works: `scripts/emulator-roaming.sh`.** A private copy of the image's `data/` (`-sysdir`, symlinks for the
rest) holds the SIM (`<CIMI>`, `EF_AD`'s MNC length) and the name list; a private copy of the emulator (symlinks, its
own `emulator` launcher and `qemu-system-x86_64-headless`) has the first built-in operator replaced. In 37.2.13 that
operator is built in place as a libc++ short string (size byte, `movl "3117"`, `movw "40"`); the script rewrites those
bytes to the wanted PLMN, 5 or 6 digits. `start PLMN [SIM]` is a cold boot: the phone is turned on in that country.
There is no live crossing between countries (the operator is fixed for a run); within a country `adb emu gsm data
roaming` + `gsm voice roaming` sets Android's roaming flag and `... home` clears it, live. Without them a foreign
network is registered as home and Android reads `isNetworkRoaming() = false` (an **unflagged** foreign network,
design 97 code QA M2's case). The status bar names a patched network "Android Virtual Operator". Each SIM needs an APN
(the image has none for these SIMs): the script adds one with `content insert`, as §6al did. The SDK is not changed.

Paths of this run (outside the repo): `~/roaming-avd/sysdir` and `~/roaming-avd/emulator` (the private copies),
`~/roaming-avd/rig` (the server's data, the helpers, the logs), `~/roaming-avd/shots` (screenshots). The AVD itself was
`jelly36`, put back as it was.

## What Vibb did

"Probe" is the other app: IP literal, then the name. "Report" is the next status report's `data_roaming` /
`mobile_data`. "Notice" is a `roaming_started` parent event.

| Row | Network and choice | Vibb |
|---|---|---|
| 1 home | Telenor `24201` (`no`), Bare Vibb | **Passed.** `where: home`, `roaming: false`, `sink: off`, `Tunnel: filter`, lockdown 0. Probe 200 in 5 ms, 204 in 73 ms. `gsm data/voice roaming` on the home network: Android keeps `roaming=false` |
| 2 the US, unflagged | `311740` (`us`, the fallback), Bare Vibb, cold boot | **Passed.** Android `roaming=false`; Vibb `where: abroad`, `country: us`, `network: 311740`, `sink: abroad`, `Always-on: ... lockdown true (sink)` then `Tunnel: sink` 3 s after the start sync. Probe `ConnectException` 17 ms, `UnknownHostException` 23 ms. Notice (`place: us`); the summary "Roamer (US)" |
| 3 Sweden, unflagged | Telia `24001` (`se`), Bare Vibb, cold boot | **Passed.** `where: abroad`, `country: se`, `network: 24001`, `roaming: false`, `data_active: false`, `sink: abroad`; the sink held through the boot. Probe refused. Notice "Emma er på roaming i Sverige"; the summary and the Roaming group "Roamer i Sverige" |
| 4 Sweden, flagged | `gsm data/voice roaming`, Bare Vibb | **Passed.** `roaming: true`, `data_active: true`, `sink: abroad`; probe refused in 1 and 3 ms; no second notice. The kid's Mobildata: Off at 50 %, "Set by mum and dad" (`r4-se-bare-vibb-kid-row.png`) |
| 5 Roaming På | flagged, PWA "På" | **Passed.** `Tunnel: filter`, lockdown 0, `sink: off`; probe 200 in 8 ms, 204 in 98 ms; the kid's row On, enabled |
| 6 the kid's Off abroad | flagged, På, the kid taps Off | **Passed.** "Only Vibb One uses mobile data. Wi-Fi works as before." (`r6-se-paa-kid-off.png`); `sink: kid`, `kid: off`, lockdown 1, probe refused. On again: filter |
| 7 Roaming Av | flagged, PWA "Av" | **Passed.** `no_data_roaming`, `data_roaming` 0, report `locked: true`, `enabled: false`. The roaming session stays up (Android's gap, 95 §1.2 E1), the sink (`abroad`) holds it, probe refused; the PWA "Bare Vibb bruker roamingdata fra før låsen" with its sub-line, the summary "Roaming av". Flag cleared (unflagged): data stays up - Android's lock doesn't apply to a network it doesn't count as roaming - and the sink still refuses the probe. Flag set again: the session torn down, no default network, the sink kept |
| 8 Bare Vibb, Android's switch off | `data_roaming` 0 left by Av, PWA "Bare Vibb" | Flagged: no data at all (Vibb can't report either). Unflagged: data up, the sink holds, the report `enabled: false, roaming: false, where: abroad` - **the page says "Emma har ingen mobildata nå, heller ikke Vibb"** although that report came over the cellular network (finding 2). The switch turned on (as the parent's one step): flagged roaming data up, `sink: abroad`, the page "Roamer i Sverige" |
| 9 Denmark | TDC `23801` (`dk`), cold boot | **Passed.** `where: abroad`, `country: dk`, `sink: abroad`; a second notice (`place: dk`) |
| 10 back to Sweden | `24001`, cold boot, within the hour | **Passed.** The place `se` again, no notice (D6's guard) |
| 11 airplane mode abroad | Sweden, airplane on, then Wi-Fi on | **Passed.** Airplane: sink and lockdown kept, no network. With Wi-Fi: `where: unknown`, `country: null`, `Tunnel: filter`, `sink: off`; the place kept; airplane off: no notice. Seen once here: names failed through the filter (finding 3) |
| 12 a ship | Telenor Maritime `90112`, cold boot, unflagged | **Passed** for the phone: `where: abroad` (901, no flag), `country: null`, `network: 90112`, `sink: abroad`, probe refused; notice "Emma er på roaming på et skips- eller flynett". **The summary's header** says in orange "På et skips- eller flynett som ikke regnes som roaming: låsen dekker det ikke" while the Mobilnett page (rightly) says nothing (finding 1) |
| 13 home again | `24201`, cold boot | **Passed.** `where: home`, the place cleared, no notice (four in all: us, se, dk, ship), filter, probe 200 / 204 |
| 14 Roaming Av at home | `24201`, PWA "Av" | **Passed.** `no_data_roaming`, `data_roaming` 0, home data untouched: filter, probe 200 / 204 |
| 15 national roaming | SIM Ice `24214` on Telenor `24201` (`start 24201 24214`), flagged, Bare Vibb | **Passed.** `roaming: true`, `where: home`, `country: no`, filter, `sink: off`, probe 200 / 204, no notice |
| 16 national roaming, Av | as 15, PWA "Av" | **Passed.** The lock; the lingering session under `sink: roaming`; the PWA "Ingen mobildata her (nasjonal roaming)" and its sub-line. `gsm data home` / `roaming`: the session torn down, the sink kept |

Not run: design 97 §10 test 6 (the Wi-Fi bypass, run on 2026-11-14), the upgrade, a live crossing between two countries
(the emulator can't), the plain notice (abroad with no country), Android 14 and 17, the PWA in a browser (its words were
read from the HTML).

## Findings (not fixed)

1. **The summary's header contradicts the Mobilnett page on a ship network** (server, Low). `roaming_words`
   (`server/src/mobile_network.rs`, ~l.290) picks `data_roaming.ship_home` ("... låsen dekker det ikke", orange) for
   every unflagged 901 report, while the Mobilnett page's line checks `sink_covers_ship` (~l.395) and shows nothing
   under Bare Vibb or Av, where the abroad sink covers the ship. The header should take the same check (a state as it
   should be shows nothing).
2. **"Ingen mobildata nå, heller ikke Vibb" on an unflagged foreign network** (server, Low). `switch_line` (~l.512)
   shows `mobile.abroad.switch.off_abroad` whenever `enabled: false` and `where: abroad`. Android's roaming switch only
   stops data on a network it flags as roaming; with `roaming: false` the phone has data (Vibb's own report came over
   it). The line could require `roaming: true`.
3. **Names failed through the filter once** (launcher, needs a look). After a cold boot in Sweden under the sink,
   airplane on, Wi-Fi on: the filter shape on Wi-Fi, and later on cellular, answered no name (`UnknownHostException`
   after 10 s, IP literals fine) for ~4 minutes, until a pause and its end built a new tunnel. Not reproduced in two
   tries (sink to filter on cellular; a boot in the sink, then the filter).

**For emulator.md:** §6ak's and §6al's recipe (a private `-sysdir` with `CIMI 242010000000000` reads network `311740`)
still holds only while the name list doesn't name the SIM's PLMN; §6al's "a second country can't be faked" is overtaken
by this script.
