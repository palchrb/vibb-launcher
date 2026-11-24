# Design 131: a Wi-Fi fix carries what confirmed it

Status: built on branch `wifi-confirm` (2026-11-24); the emulator rows are `docs/testing/emulator.md` §6bm (written, not
run). Android 14-17: every API used is within minSdk 34 (`WifiInfo.getCurrentSecurityType` API 31,
`DevicePolicyManager.setConfiguredNetworksLockdownState` API 30).

A Wi-Fi fix that no satellite fix or linked network confirms never starts an arrival.

## On the phone (`places/PlacesPlan.kt`, `PlacesTest`)

- **The check** (`FixCheck`): every `vibb_wifi`/`vibb_apple` fix carries `check: "gnss"` (a satellite fix of at most
  50 m within 2 min lies within both accuracies of it) or `check: "link"` (the phone is on a saved WPA2/WPA3 network -
  an enterprise one only when its AP is one of the fix's own rows - that holds a live zone link, and the fix is surely
  inside that zone); absent: the Wi-Fi alone. The link is bound to the saved network's security class (WPA2 and WPA3
  personal are one, enterprise another) and Android's network id (-1: no link): saved anew, it learns again (3
  occasions on 2 days); the first teach after a boot rebinds a link when exactly one saved network has the name and the
  saved networks' count is unchanged. It rides in the fix's extras to every report, the cached and
  kept fix, the queue, the shutdown fix and every renewal, which re-sends the vouch with the vouch's own check.
- **Good satellites overrule a far Wi-Fi fix**: a `gps` fix of teaching quality (<= 25 m, >= 5 satellites) further than
  `max(500 m, 3 x both accuracies)` is kept and the attempt counts a disagreement; any other pair is the usual choice by
  accuracy.
- **Still means still for every unchecked Wi-Fi fix**: while the phone is `STILL`, an unchecked Wi-Fi fix is compared
  with the last fresh fix when that is satellites of teaching quality or a checked fix (an unchecked Wi-Fi fix is never
  fresh); outside both accuracies it is not reported, GPS runs and a disagreement is counted. A `link`-checked fix
  stands and may become the next vouch. With no vouch, or `STILL_SINCE_TRY`, an unchecked fix runs GPS and is
  reported unchecked only when GPS gives nothing.
- **The jump from the last satellite fix** (kept across a reboot, its age by elapsed time in its boot, else the
  decisions' clock; a satellite fix newer than the Wi-Fi fix judges nothing): over 60 m/s a fix without a link check
  is refused and counted; over 8 m/s (60 m/s after a satellite fix faster than
  8 m/s in the last 15 min) an unchecked fix runs GPS, not counted.
- **Bands and names**: a result heard on another band than its row's is left out, counted when two or more are or the
  one would have carried the fix; one heard under another
  network name than its row's is left out, not counted.
- **Locate** stops at 20 s only on a `link`-checked fix within 60 m; else GPS up to 60 s.
- **`zone_watch.wifi_disagreed`**: a running count, at most one rise an attempt (and one per learned contradiction of a
  passive fix).
- **Saved networks**: `setConfiguredNetworksLockdownState(true)` while managed or while the zones and Wi-Fi places
  watch (its own plan flag, lifted with the override and the pause): Android's screens can't change or forget a network Vibb saved; Vibb's own Wi-Fi page never edits one in
  place.

## On the server (`zones.rs`, migration 0176)

- `zones::FixClass`: `coarse` (cell fixes), `wifi_alone` (a Wi-Fi fix without a check), `fine` (the rest).
  `device_locations.fix_check` keeps the check on a Wi-Fi provider only (`models::fix_check`).
- A Wi-Fi fix alone surely inside never starts a pending side, never drops a pending outside and never seeds a zone; it
  confirms a pending inside a fine fix started from 60 s to 10 min after it. Outside it counts as a fine fix. It never
  keeps "Here now" fresh, nor does its still re-send.
- Locate calls it "Wi-Fi estimate" / "Wi-Fi-anslag" in place of the zone badge; a checked fix shows no word.
- The count's rises are one quiet security-log line, "Wi-Fi and GPS disagreed" / "Wi-Fi og GPS var uenige", at most one
  a day with the rises between summed (`devices.wifi_disagreed_logged`/`_at`, cleared at re-enrolment). No Attention
  row, no orange line.

## Contract

`zone_status_report.json` (a `vibb_wifi` location with `check: "link"`, a `vibb_apple` extra without one,
`zone_watch.wifi_disagreed`) and `power_report.json` (`off.location` with `check: "gnss"`), byte-identical in both
directories.
