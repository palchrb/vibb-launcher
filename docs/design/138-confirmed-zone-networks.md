# 138 - A zone's Wi-Fi: the parent confirms it, the phone learns it, both bound to the router's address

Status: **design**, 2026-11-26 (branch `wifi-link-confirm`), read against `main` 3e84d466f. Approved by the owner on
2026-11-26 as A + B + the router's address; this note designs exactly that. It blocks the next launcher RC and the
stable release. Both directories, one migration (0180), one new contract file. Android 14-17: every API used is at or
under minSdk 34 (§9); the emulator rows run on 14 and 16.

## 0. Short answer

- **The finding.** Design 131 made a Wi-Fi fix without a check never fresh. Indoors on the home router GPS never
  fixes, and a zone link is taught only from GPS fixes, so a phone that lies at home never gets a `link` check. After
  rc.4's install restarted the process (a new process starts `MOVED`), every heartbeat try found a good Apple fix
  (`attempt path=TOGETHER|LEARNED_FIRST result=vibb_apple`, `wifi fix check=none step=USE`, later `step=GPS`), logged
  `FIX result=none` and backed off 20, 40, 90, 190 min; the PWA showed an old position. A real motion fire indoors
  does the same.
- **A - the parent confirms the zone's network** on the zone's page: the network the phone is on now (its name, when
  it is a saved WPA2/WPA3 personal network), one tap. It counts at once. Removable, more than one per zone, the router
  bound by its address. Moving the zone further than its radius clears them. Good GPS far outside the zone with that
  router connected or heard blocks it, and the zone shows one orange line.
- **B - the phone also learns the link from Wi-Fi fixes** surely inside the zone while it is on a PSK/SAE network:
  the same link row and counters as GPS teaching (3 occasions on 2 days), behind a gate - the fix must still lie
  surely inside without the connected network's own results.
- **Every link is bound to the router's address (BSSID)**, not only to the name, security and Android's network id:
  a hotspot elsewhere with the same name and password confirms nothing. The set of addresses grows while the link
  holds: an affiliated link of the same multi-link router at once, a mesh node or the other band of the same name
  once it is a settled AP of the zone's own rows (design 134 rev 4's rules, reused). A new router stops confirming
  until the parent confirms it (the orange line says so).
- **How the PWA learns the address**: the status report's `zone_watch` gains `wifi` - the connected network's name,
  BSSID and MLO address - while the policy has zones. A confirm binds to what the newest report says.
- **A process restart still counts as `MOVED`** (D11): with A or B the first try at home is `link`-checked and fresh,
  so the restart costs one attempt; persisting the motion flags would vouch a phone that moved while no process ran.
- **Server**: migration **0180** (`zone_networks`; the branches waiting for after the release renumber at their
  merge); the policy's `location_policy.zones[]` gains `networks`. The contract is `contracts/core/zone_networks.json`
  (new) and `zone_status_report.json` (grown), both sides in one commit.

## 1. Today (`main` 3e84d466f)

- `places/PlacesPlan.kt`: a `RowKind.LINK` row per zone and network (key = the zone's HMAC of id, centre and radius,
  `network` = the SSID's key, `device` = design 131's binding: the security class and Android's network id).
  `teachLinks` runs only at a GPS teach (`teachWith`): surely inside (`d + a <= r`) while connected, an occasion
  (>= 30 min apart) and a day; surely 200 m outside, every *heard network name* with a row of that zone gets a strike
  (the first revokes, a second within 60 days blocks 60 days). `PlaceRow.linked` = 3 occasions on 2 days, not
  blocked. `linkConfirms` (the `link` check), `linkRenews` (D12's still renewal) and the leave-one-out `ZONE` class
  read the same rows. No router address anywhere in the link.
- `wifiFresh(fix, check) = fix.fresh && check != null`; `MdmSyncWorker` stamps a fresh fix only when
  `!places.notFresh`. An unchecked Wi-Fi fix fails the heartbeat try.
- The status report carries no connected network. `zone_watch` is a heartbeat field (`HEARTBEAT_FIELDS`), stored per
  report in `device_status.zone_watch_json` (`zones::sanitize_zone_watch`, known keys only, at most 512 bytes).
- The policy's zones are `{id, latitude, longitude, radius_m}` (pinned by `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`); the fences' fingerprint and the zone key read only the circle.

## 2. Decisions

### D1. A link is a zone, a network name and a set of router addresses

A link confirms only while the phone is connected to an address in the zone's set. The set has three sources, and
one pure function answers for all of them (`zoneLink`, `PlacesPlan.kt`):

```
enum class LinkBy { CONFIRMED, GROWN, LEARNED }
fun zoneLink(conn: LinkConn, zone: LinkZone, confirmed: List<ConfirmedAp>, struck: Set<Long>,
             rows: List<PlaceRow>, nowMin: Int): LinkBy?
```

1. **CONFIRMED** - a parent's confirmation for the zone (D2) that isn't struck (D7): the same network key (the SSID's)
   and personal class, and the connected AP is its address (`keys.ap(bssid)`) or an affiliated link of the same MLO
   router (`keys.device(bssid, mld)` equal, `mld` known on both).
2. **GROWN** - a `LINK_AP` row (D5) of the zone with the connected AP's key or MLO key, the same network, not
   blocked, while the zone has an unstruck confirmation of that network name.
3. **LEARNED** - a `LINK_AP` row as in 2, while the zone's `LINK` row of that network is `linked` under the
   connection's design 131 binding.

`LinkConn` (design 131's `linkConn`, unchanged: unmetered, Android's saved-network id >= 0) now needs a readable
BSSID for any link (its `apKey` non-null) and gains `mldKey`. The security part stays `linkSecurityHolds`: PSK/SAE
always; enterprise only when its AP is one of the fix's own rows (131's rule - and only through LEARNED, D2); open,
OWE and unknown never. `linkConfirms`, `linkRenews`, the `ZONE` leave-one-out class, the report's `links` (D3) and
the strikes (D7) all call `zoneLink`; nothing else reads `LINK` rows. A locally administered BSSID may be in the set
(a mesh's extra radios use them; the occasion or the parent's tap is what puts it there, and a metered hotspot never
has a `LinkConn`), but it never grows a set by the settled rule (it has no rows).

`linkConfirms(fix, ...)` keeps its shape: some zone the fix is surely inside (`d + a <= r`) has a `zoneLink`. So a
confirmation made for the wrong zone confirms nothing: no fix at home is surely inside School.

### D2. A: the parent's confirmation

- **What can be confirmed**: the network the phone reported in its newest report (D3), when that report is at most
  1 h old: a saved WPA2/WPA3 personal network (PSK or SAE), unmetered, with a visible name and a readable BSSID. An
  open or OWE network is never reported, so it can never be confirmed; an enterprise network is never reported either
  - it keeps design 131's rule (B and GPS teaching, its AP among the fix's own rows).
- **One row per router address** (`zone_networks`, D12): the zone, the name, the BSSID, the MLO address when the
  phone had one. A mesh with its own names is several rows of several names. At most 8 per zone
  (`MAX_ZONE_NETWORKS`, code's).
- **It counts at once**: the save nudges the phone (`command_notify`, as every zone save), the policy carries the row
  (D13), and the next attempt's `linkConfirms` answers `CONFIRMED` - no occasions, no days.
- **Not bound to Android's network id**: the address is the stronger binding. The network saved anew (a new password)
  at the same router still confirms; a router with a new address does not.
- **Remove**: the zone's page removes a name - every row of that name for the zone. It ends the parent's
  confirmation only; the phone's own learned link (B) for the same network stands, as a learned link does today
  (open question Q1).
- **Replace**: there is no edit. A new router is confirmed (a new row) and the old name's rows removed when the
  parent wants; a blocked row is replaced by confirming its router again (a new id, the block gone).

### D3. How the PWA learns the network: `zone_watch.wifi`

The status report's `zone_watch` (a heartbeat field already, so a changed network never forces a full report and
`HEARTBEAT_FIELDS`/`fields_tag` stay as they are) gains:

```json
"wifi": {"ssid": "Net-A", "bssid": "00:00:5e:00:53:01", "mld": "00:00:5e:00:53:10", "links": [12]},
"wifi_struck": [7]
```

- `wifi` is sent only while the policy has zones (Regularly with zones), Location is on, the phone is device owner,
  and the connection is a saved (`networkId >= 0`), unmetered PSK/SAE network with a UTF-8 name (Android's quoted
  form; a hidden or hex-only name is left out) and a readable BSSID. `mld` only when Android gives one. Otherwise no
  `wifi` key at all.
- `links`: the zone ids for which `zoneLink` holds for this connection now (any of the three sources), at most 10.
  The server needs it for one thing: the "Unknown router" line (§4) must not show for a mesh node the phone has
  already grown into the set.
- `wifi_struck`: the confirmation ids the phone struck (D7) that the policy still carries, at most 80.
- Read on the report's path by a new `WifiPlaces.reportWifi(context)` (suspend, in the sync before the report is
  built): `connectedNow` - design 134 D25's read, reused as it is (D10).
- Server: `sanitize_zone_watch` keeps the new keys only in their shape - `ssid` 1-32 bytes, no control characters;
  `bssid` and `mld` six lowercase hex pairs, unicast, never `00:00:00:00:00:00` or `02:00:00:00:00:00` (Android's
  redacted value); `links` and `wifi_struck` positive integers within their caps. A bad `wifi` is dropped alone, the
  rest of `zone_watch` kept. `ZONE_WATCH_MAX` 512 -> 2048. `ZoneWatch` keeps its fields and its `Copy`; the network is
  read apart (`zones::reported_wifi(stored) -> Option<ReportedWifi>`, with the report's `reported_at`).
- What the server holds: the name and address of the saved network the phone is on, only while it has zones, in the
  report rows it already prunes after 48 h, and the confirmed rows until removed. It already holds the phone's
  positions, so the name of the router at a position adds little. The security log never names a network (D12).

### D4. The confirm binds to the phone's report

- The zone's page draws the current network's row from the phone's newest report (since its enrolment) when it
  carries `wifi` and is at most 1 h old, and the BSSID isn't already one of this zone's unblocked rows.
- The form posts the BSSID the page showed (`bssid`, hidden). The server confirms only when the phone's newest
  report, at most 1 h old, still has `wifi` with that BSSID; it stores the name and MLO address from that report -
  never from the form. Anything else: no row, the page again at `#zone-wifi` with the flash "The phone is on another
  network now".
- The same BSSID twice (two parents): one row, the second tap a no-op that still says Saved. A blocked row of that
  BSSID: deleted and a new row made, in one transaction (a new id, so the phone's struck id no longer matches it).
- The zone must be this phone's (404 otherwise); the 9th row is refused ("At most 8 routers").

### D5. The set grows while the link holds (`LINK_AP` rows)

- **New row kind** `RowKind.LINK_AP(6)` in the sealed table: `key` = the zone's key, `device` = an AP's key cut to 8
  bytes, or an MLO router's device key (`keys.device(bssid, mld)`), `network` = the network key; its id `p/key/device`.
  The table's version stays 1 (an older launcher would drop the table; no backward compatibility is owed - root
  `CLAUDE.md`); it is dropped with its zone's key like a `LINK` row (a moved or resized zone, a zone gone, the mode
  off Regularly).
- **What joins** (`growLinkAps`, pure, run where `zoneLink` is first asked in an attempt and at every teach; the
  table saved when it changed), for a zone with a live link of the connection's network (an unstruck confirmation of
  that name, or the `linked` `LINK` row under the binding):
  - **an affiliated link** - the connected AP shares its MLO router with an address in the set (design 134 rev 4's
    `sameAp`, which treats another link of the same MLO device as the same AP): it confirms at once, and its key joins;
  - **a settled AP of the zone's place rows** - the connected AP's device (design 125's device key) is settled
    (`deviceSettled`, design 134 D20.5: a trusted own Wi-Fi row on >= 2 local days, none moved or blocked) and every
    such row of the device lies inside the zone (`d <= r` from the row's position): a mesh node or the other band of
    the same name. Its AP key (and MLO key when known) joins.
  - **an AP at an occasion** (D6): the connected AP of a counted inside occasion joins its learned link's set.
- Another name never joins by growth: a mesh with its own names is confirmed per name (D2).
- A new router has no rows yet and no occasion: it confirms nothing until the parent confirms it, or, unconfirmed,
  until B's 3 occasions on 2 days.
- A link learned before this build has no addresses: it confirms nothing until its next occasion adds the connected
  one (no backward compatibility is owed; the parent's Confirm is the at-once path).

### D6. B: the link learned from Wi-Fi fixes - the same row, one gate

**The same `LINK` row and counters** as GPS teaching. The row means one thing - this network was connected inside
the zone on N occasions over D days - whatever placed the phone inside; a sibling kind would double the binding,
rebind, clear and strike code for the same meaning. The difference in grade is handled at the entry, and strikes
still come only from GPS (D7).

A Wi-Fi occasion (`wifiOccasion`, pure; run once at an attempt's end, `WifiPlaces.attempt`, on the snapshot the fix
was judged on) counts when all hold:

1. the attempt judged a Wi-Fi fix (`vibb_wifi` or `vibb_apple`, own, Apple or mixed) from a fresh scan view
   (`fix.fresh`), its verdict not `REFUSE`, and its check not `link` (a link never teaches itself);
2. the attempt marked no disagreement (no overruling satellites, no impossible jump, no learned contradiction);
3. the fix is surely inside the zone (`d + a <= r`);
4. **without the connected network**: `learnedFix` over the same view without the connected network's results gives
   a fix that is also surely inside the zone - the neighbours place the phone inside, not the router's own position
   (which would follow a copied router address anywhere);
5. the connection has a `LinkConn` whose security holds (PSK/SAE; enterprise with its AP in the fix's rows).

Then `teachLinks(..., strikes = false)` adds the inside occasion under the binding (131's rules: another binding
starts the occasions over, the rebind after a boot), and the connected AP (and its MLO key) joins the set (D5). The
occasion gap (30 min) and the day rule are the same; GPS and Wi-Fi occasions add up. The heartbeat's 30-min interval
gives the 3 occasions on 2 days in about a day and a night at home.

### D7. Strikes: by address, one blocks

At a GPS teach (teaching quality: <= 25 m, >= 5 satellites, not mock in a release build) surely outside a zone by
200 m (`d - a >= r + 200 m`, as today), an address of that zone's set **connected now, or heard in the results paired
with the teacher**, is struck:

- **a confirmation**: its id joins the phone's struck list (CE prefs `wifi_places` `confirm_struck_v1`); from then on
  `zoneLink` never answers `CONFIRMED` for it, and the report's `wifi_struck` carries it until the policy no longer
  does (the server blocks it, D12). Logged `WifiPlaces: zone link struck by=confirmed` (no id, no name).
- **a `LINK_AP` row**: blocked 60 days (`blockedUntilMin`, `BLOCK_MIN`); the `LINK` row's counters stay. Logged
  `by=learned`. When it was heard there, the same teach marks its device's own rows moved, so it can't come back by
  the settled rule either.

One strike blocks: an address of the router heard 200 m beyond the zone's edge with good satellites means that router
(or a copy of its address) is there - stronger evidence than today's strike by a heard *name*, which needed two. The
name-based strike goes: a same-named network elsewhere can't confirm anything any more, so striking on it only cost
relearning. Wi-Fi fixes never strike; good outdoor GPS always overrules a Wi-Fi fix (`gpsOverrules`, unchanged).

The struck list is cleared with every Wi-Fi places clear (Location Off, unenrol, a broken key); the server's block
outlives it.

### D8. Zone edits and the phone's other changes

- **Moved further than its radius** (the distance between the old and new centre > the old radius): every
  confirmation of the zone is deleted in the save's transaction; the `zone_changed` line gains "Wi-Fi removed". A
  smaller move or a radius change keeps them. On the phone any circle change drops the zone's `LINK` and `LINK_AP`
  rows (its key changes, as today); the confirmations come back from the policy under the same id.
- **Deleted zone**: its rows go by cascade.
- **A new phone enrolled into the row**: the confirmations stay (they describe the router, not the phone), blocked
  ones too.
- **Off or Only when asked**: the policy carries no zones, so no `networks` and no `wifi` report; the rows are kept.
- **After design 135** (family zones, waiting after the release): the confirmations belong to the place, so 135's
  rebase moves `zone_networks.zone_id` to the family zone (one row per family zone and BSSID, the oldest unblocked
  kept) and serves them to every phone linked to it; the family zone's editor shows each linked phone's current
  network. A strike from one phone blocks it for all. 135's migration does the move; 138 is built against `main`.

### D9. What the check changes downstream

Nothing new: a `link`-checked fix is what design 131 already handles - it stands (`wifiVerdict`), is fresh
(`wifiFresh`), may become the still vouch, lets Locate stop at 20 s, and on the server is a fine fix that starts and
confirms a side. The owner's case: `MOVED`, `TOGETHER`, the Apple fix judged `check=link step=USE`, GPS silent,
`attemptKeep` keeps the Wi-Fi fix with `link`, `notFresh` false, `FIX result=fresh`, no back-off.

### D10. The connection read in the background

Reuse design 134 D25's `connectedNow` as it is: `WifiManager.getConnectionInfo()` first; when it gives no readable
BSSID (the Jelly's `02:00:00:00:00:00` in the background) while the active network carries Wi-Fi, one
`NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` for Wi-Fi, up to 1 s, then unregistered. The attempt's snapshot and
`teachWith` already read through it; the report's `reportWifi` calls the same function. Unreadable: no `wifi` in the
report and no link this attempt (logged `zone wifi report no reason=unreadable|none|metered|security|unsaved|hidden`,
never a name or address). No new permission: fine and background location are self-granted, as for 134.

### D11. A process restart still counts as `MOVED`

Recommend **no change**. The flags live in memory because the significant-motion trigger is armed by the process: an
update, a crash or a kill leaves a gap in which a move is never seen. Carrying the flags across would let a phone
that moved during that gap vouch its old position `STILL` for up to 4 h - a wrong "Here now". The finding was not the
restart but the unchecked fix: with A or B the restart's `MOVED` try at home is `link`-checked and fresh, so it costs
one attempt and nothing more. Elsewhere indoors, without a confirmed or learned link, the try runs GPS and the
back-off follows - design 131's intended behaviour, unchanged.

### D12. Server: migration 0180, the routes, the log

No existing table fits: `device_zones` is one row per zone with its rule state, and a set of addresses per zone
needs its own rows. `main`'s newest migration is 0179; the branches waiting for after the release (phase D's 0180,
135's 0181, and the others up to 0183) renumber at their merge (the order hazard, `server/CLAUDE.md`).

```sql
-- Design 138: Wi-Fi routers a parent confirmed for a zone, one row per router address (BSSID).
-- `ssid` the name the phone reported, `mld` the router's multi-link address when it had one;
-- `blocked_at` set when the phone reported the router seen far outside the zone (then never served).
CREATE TABLE zone_networks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    zone_id INTEGER NOT NULL REFERENCES device_zones(id) ON DELETE CASCADE,
    ssid TEXT NOT NULL,
    bssid TEXT NOT NULL,
    mld TEXT,
    confirmed_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    confirmed_at TEXT NOT NULL DEFAULT (datetime('now')),
    blocked_at TEXT
);
CREATE UNIQUE INDEX idx_zone_networks_ap ON zone_networks(zone_id, bssid);
```

- **Routes** (session + 2FA, as the zone's page; both land on `/devices/{id}/zones/{zone_id}#zone-wifi`, so a save
  never jumps to the top): `POST /devices/{id}/zones/{zone_id}/wifi` (`bssid`; D4) and
  `POST /devices/{id}/zones/{zone_id}/wifi/{network_id}/delete` (every row of that row's name in the zone).
- **The strike intake** (`zones::intake`, after the fix): `wifi_struck` ids that are this phone's zones' rows and not
  yet blocked get `blocked_at`, one security line each, and a nudge; another phone's ids are ignored.
- **Security log** (the zone's name, never the network's name or address): `zone_wifi_confirmed` "Wi-Fi confirmed",
  `zone_wifi_removed` "Wi-Fi removed", `zone_wifi_blocked` "Wi-Fi seen elsewhere"; `zone_changed` gains the detail
  "Wi-Fi removed" when a move cleared them.
- **The policy** (`zones::policy_zones`): each zone gains `networks`, its unblocked rows (D13).

### D13. The contract

- `location_policy.zones[]` gains `networks` - always present, `[]` for none:
  `[{"id": 7, "ssid": "Net-A", "bssid": "00:00:5e:00:53:01", "mld": null}]`. Design 64's "never a name" is the
  zone's name; a network's name is already on the phone. The launcher's `PolicyZone` gains
  `networks: List<ZoneNetwork> = emptyList()` (a missing key decodes as none). `fenceFingerprint` and
  `PlaceKeys.zone` read only the circle, so a confirmation never re-registers fences or drops learned links.
- **Files, one commit, both sides**: `contracts/core/zone_networks.json` (new: the policy's `networks` rows; the
  report's `wifi`/`wifi_struck` rows the server keeps and drops - the redacted and zero BSSIDs, a 33-byte name,
  a control character, too many links; a README row: 138, by hand, server `tests::zone_networks`, launcher
  `ZoneNetworksContractTest`), `contracts/core/zone_status_report.json` (one report with `wifi` and `wifi_struck`; its
  README row gains 138), `server/src/tests/device_api.rs` `policy_json_keys_snapshot`, the launcher's
  `PolicyResponseCompatTest`.

### D14. What doesn't change

`gpsOverrules`, the jump gate, the still rules, design 134's witness (it keeps its own same-AP rule and reads no
`LINK` row), the Apple lookup, the server's `FixClass` and the fix's `check` on the wire. The kid's phone shows
nothing new.

## 3. Faking "home": what it takes

- **A confirmed link** needs, at once: the network's password (to connect - the phone needs a saved network), the
  router's own address (a network elsewhere with the same name and password has another address), and a Wi-Fi fix
  surely inside the zone (the neighbours' routers heard - a phone elsewhere hears elsewhere's). A copied router
  address, connected far away, is struck by the first good GPS fix there (D7) and the zone's page shows it.
- **The child may know the home password.** That is why the address is bound: the child's own hotspot named like
  home, with the home password, has another address and confirms nothing (and a phone's hotspot is metered, so it has
  no `LinkConn` at all).
- **B adds little**: an unconfirmed network learns only from fixes surely inside the zone that hold without the
  connected network's own results (D6.4), on 3 occasions 30 min apart over 2 days, with no disagreement in any of
  them - a network elsewhere would have to be inside the zone to teach.
- **A wrong confirmation** (the parent taps Confirm while the phone is elsewhere) confirms nothing: no fix there is
  surely inside the zone, and good GPS there with that router connected blocks it and shows the orange line.
- **Good outdoor GPS always overrules** a far Wi-Fi fix, checked or not (`gpsOverrules`), and the server still decides
  every arrival.

## 4. PWA words (nb and en)

On the zone's page (`device_zone.html`), a `#zone-wifi` section after the zone's form (its own forms; not on a new
zone). The rows: each confirmed name (`{name}`), a "Remove..." confirm on it; the current network's row (`{name}` and
a Confirm button) when D4 lets it show. One orange line at most, under the group, "Seen elsewhere" before "Unknown
router". The kid's phone shows nothing.

| Where | nb | en |
|---|---|---|
| Section header | Wi-Fi | Wi-Fi |
| Current network's button | Bekreft | Confirm |
| Remove (summary and button) | Fjern | Remove |
| Remove's question | Fjerne {name}? | Remove {name}? |
| (i) on the group | Et bekreftet nettverk gjør Wi-Fi-posisjonen sikker i sonen, uten GPS. Bare ruteren telefonen var på da du bekreftet, teller. Telefonens nettverk vises her når det har passord. | A confirmed network makes the Wi-Fi position certain in the zone, without GPS. Only the router the phone was on when you confirmed counts. The phone's network shows here when it has a password. |
| Orange line: a confirmation blocked (the server's `blocked_at`) | Sett et annet sted | Seen elsewhere |
| Its (i) | Telefonen var på {name} langt utenfor sonen, så det teller ikke lenger. Bekreft det igjen når telefonen er på det her. | The phone was on {name} far outside the zone, so it no longer counts. Confirm it again when the phone is on it here. |
| Orange line: the phone on a confirmed name, its router not in `wifi.links` or the rows, its newest fix not surely outside the zone | Ukjent ruter | Unknown router |
| Its (i) | Telefonen er på {name}, men på en ruter Vibb ikke kjenner. Står den her, trykk Bekreft. | The phone is on {name}, but on a router Vibb doesn't know. If it is here, tap Confirm. |
| Flash, D4's refusal | Telefonen er på et annet nettverk nå | The phone is on another network now |
| Flash, the 9th row | Maks 8 rutere | At most 8 routers |
| Flash, a confirm or remove | the existing "Lagret" | the existing "Saved" |
| Security log labels | Wi-Fi bekreftet / Wi-Fi fjernet / Wi-Fi sett et annet sted | Wi-Fi confirmed / Wi-Fi removed / Wi-Fi seen elsewhere |
| `zone_changed` detail | Wi-Fi fjernet | Wi-Fi removed |

A confirmed name is a choice: it shows as a plain row, no state word. A blocked row stays listed (so the parent can
remove or re-confirm it) with the orange line under the group.

## 5. Tests (lean)

**Launcher** (pure; `PlacesTest`'s fixture `wifi_places.json` gains `zone_links` rows, and a `ZoneNetworksTest`):

- `zoneLink`: confirmed by BSSID; by an affiliated MLO link; the same name at another address - none; struck - none;
  open, OWE, enterprise - never `CONFIRMED`; metered, `networkId` -1, an unreadable BSSID - none; a locally
  administered address confirmable; `GROWN` only with an unstruck confirmation of that name, `LEARNED` only with the
  `LINK` row linked under the binding.
- `linkConfirms`: a confirmation and a fix surely inside - `link`; straddling - none; inside another zone than the
  confirmed one - none. The owner's case end to end: `MOVED`, `TOGETHER`, an Apple fix inside, a confirmation, GPS
  silent - `attemptKeep` keeps the Wi-Fi fix with `LINK`, `wifiFresh` true.
- `growLinkAps`: a settled device with every row inside joins; unsettled, a row outside the zone, another name, no
  live link - not; an MLO sibling joins at once.
- `teachLinks`: an occasion adds the connected AP and its MLO key; a strike by a confirmed address connected or heard
  returns its id; a `LINK_AP` row struck is blocked 60 days and the `LINK` counters stay; a same-name network at
  another address heard outside strikes nothing; `strikes = false` never strikes.
- `wifiOccasion`: each of D6's five conditions failing alone gives no occasion; all holding gives one; the fix
  without the connected network outside the zone - none.
- `reportWifi`'s pure half (`zoneWifiReport`): PSK/SAE, unmetered, saved, a quoted name, a readable BSSID, zones on -
  reported, each one failing - not; `links` and `wifi_struck` within their caps; the struck list dropped by the policy.
- The table: a `LINK_AP` row round-trips (`encodeTable`/`decodeTable`).
- Contract: `ZoneNetworksContractTest` (`zone_networks.json`), `ZoneReportTest` (`zone_status_report.json`),
  `PolicyResponseCompatTest` (`networks` decode, a missing key none).

**Server** (`src/tests/zone_networks.rs`, `zones::tests`):

- `sanitize_zone_watch` over the contract's rows: kept and dropped, a bad `wifi` dropped alone.
- Confirm: binds the newest report's name and MLO address; a report over 1 h old, another BSSID, no `wifi` - nothing
  written, the flash, at `#zone-wifi`; twice - one row; a blocked BSSID - a new id; the 9th - refused; another
  phone's zone - 404. Remove: every row of the name. Both land on `#zone-wifi`
  (`shell::every_form_on_every_page_lands_on_its_own_group` with a confirmed row).
- The strike intake: `blocked_at` once, one log line, the policy no longer serves it; another phone's id ignored.
- A move over the radius clears, one under keeps, a radius change keeps; the `zone_changed` detail.
- The page: "Seen elsewhere" for a blocked row; "Unknown router" only for a confirmed name, an address outside the
  rows and `links`, and a newest fix not surely outside; never both.
- `policy_json_keys_snapshot`: `networks` always present; blocked rows and the non-Regularly modes serve none.
- No security line carries a name or a BSSID.

## 6. Emulator rows (a new section after §6bu, "Confirmed zone networks (design 138)"; Android 14 and 16)

The setup of §6bn: the debug launcher, Regularly, a zone Home (150 m), Google's location accuracy off, the made-up
scan and connection through `PlacesDebugReceiver`, the satellite feed for teaching, a second local day by the time
zone. The receiver's `connect` gains an optional sixth field, the MLO address. Names `Net-A`/`Net-B`, documentation
addresses `00:00:5e:00:53:xx` (and their own device prefixes for neighbours, as §6bn's setup notes say). The real
background read (the Jelly's redacted BSSID and the callback) is not reachable on the emulator, whose own network is
open: it is a device row on the Jelly Star.

| # | Step | Expect |
|---|---|---|
| a | Nothing learned (`--ez clear true`, a fresh table), GPS silent, home's scan, `connect "00:00:5e:00:53:01,Net-A,psk,7,5180"`; a sync | the report's `zone_watch.wifi` has `Net-A` and the BSSID (`device_status.zone_watch_json`); the zone's page shows `Net-A` with Bekreft; with `open` instead, no row |
| b | Tap Bekreft; Locate | the policy's zone has `networks` with id, name, BSSID; `wifi fix check=link step=USE`; the report's fix `check: "link"`; Locate shows "At Home", no "Wi-Fi estimate" |
| c | The finding's path: `adb install -r` the same debug APK (a new process, `MOVED`), then the heartbeat's own `FIX` (wait for it) | `attempt path=TOGETHER`, `check=link`, `Location heartbeat: FIX result=fresh`; no back-off |
| d | `connect "00:00:5e:00:53:09,Net-A,psk,7,5180"` (same name, another router, untaught) | `check=none`; the zone's page "Ukjent ruter" with its (i) and Bekreft on `Net-A`; Bekreft - `check=link` |
| e | `connect` an address of another link of the confirmed router, the same MLO address as in (a) | `check=link` at once, no tap |
| f | A mesh node: its own device prefix, `Net-A`, taught by the satellite feed at home on 2 local days; then connected to it | `growLinkAps` joins it (`zone link grew by=settled`); `check=link`; no orange line |
| g | The satellite feed 3 km away (8 satellites) with `Net-A` (a) still connected | `zone link struck by=confirmed`; the report's `wifi_struck` has the id; the zone's page "Sett et annet sted"; the security log "Wi-Fi seen elsewhere" (the zone's name, no network name); the policy drops the row; back home `check=none`; Bekreft again - a new id, `check=link` |
| h | B: no confirmation, GPS silent, home's scan (three networks) and `Net-B` psk; Locate 3 times 30 min apart over 2 local days | the third occasion makes the link (`link taught by=wifi occasions=3 days=2`); the next Wi-Fi fix `check=link`; with a scan whose fix holds only with `Net-B`'s own results - no occasion |
| i | Move the zone 200 m (radius 150); then confirm again and move it 50 m | the first clears the rows (`zone_changed` "Wi-Fi removed"); the second keeps them |

Device row (the Jelly Star, Android 16, the owner's check): at home, screen off, an hour after the install - the
report's `wifi` present (`connection read by callback yes` when the first read was redacted), the parent's Confirm,
then `FIX result=fresh` at the next heartbeat.

## 7. Docs to update with the build

`docs/notes/launcher/wifi-places.md` (a section for 138; the zone link paragraph of D12 and 131's link paragraph point
to it), `launcher/CLAUDE.md`'s index line, `docs/notes/server/zones.md` (a section: the table, the routes, the intake,
the policy key), `docs/notes/server/pwa.md` ("Locate: zones": the Wi-Fi group), `server/CLAUDE.md` (the data model's
0180 and the next free number, the policy's and the report's fields), `contracts/README.md`.

## 8. Open question for the owner

- **Q1. Does "Fjern" also stop the phone's own learned link for that network?** Today's design (D2): no - removing
  ends the parent's confirmation; if the phone has itself learned the same network inside the zone (B or GPS, 3
  occasions on 2 days, at the same addresses), that link still confirms. The alternative: a removal also drops the
  phone's learned link and its addresses for that zone and name (the policy would carry the removed names for a day
  so the phone can drop them). Recommendation: no - the learned link is the phone's own evidence, as it has been since
  design 125, and a parent who removes a home network rarely means "never trust it"; but if the owner reads "Fjern"
  as "this network is not this zone's", the alternative is a small addition to D2 and D13.
