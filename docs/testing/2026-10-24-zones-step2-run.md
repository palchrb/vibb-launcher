# Safe zones step 2 - emulator run, 2026-10-24

Main after the merge of `design-64-step2` (e91aa0db: the contract, the fences, the crossing's two fixes, the queue,
the passive feed, the heartbeat, the moving series, the mock rule; code QA `docs/review/qa-design-64-step2-code.md`
with two fix rounds). The debug launcher of main on `jelly36` (the user's profile, Android 16 Play image), main's
server (0.46.0 + step 2, migrations 0092/0093 applied on the r11 test database), the phone enrolled with Android's
lock, Location Regularly every 10 min, one zone "Home" (59.9139, 10.7522, r = 150 m, both switches on). The fused test
provider drives the platform fence (`cmd location providers add-test-provider fused`), `adb emu geo fix` the GPS path,
as emulator.md §6t says. A short run: §6t items 2-4 and the return.

## Results

| § | Check | Result | Evidence |
|---|---|---|---|
| - | Enrolment on this build | PASS | `AndroidLock: apply ... secure=true face=true`, `ZoneWatch: Fences: 0 removed, 1 of 1 added`, `Passive feed on` at the first sync. |
| 2 | Registered: one geofence of ours, the fused request at 30 min | PASS | `dumpsys location` Geofence Manager: `Request[@+30m0s0ms BALANCED ... WorkSource{10217 me.vibb.launcher.debug}]`. |
| 2 | The Locate page says what the phone does | PASS | "Watching 1 zone on the phone. It has no motion sensor, so it takes a location every 30 min." and "Every" gets "30 min while the phone has zones" (the parent's 10 min kept in the select). |
| 3 | The registration's echo is dropped | PASS | first `home` after the registration: `Fence of zone 1 (entering=true): ECHO`; no sync, no event. |
| 3 | Inside, silent (the first side) | PASS, with a note | two `home` test-provider fixes reach only the fence, not the server (nothing asks the phone for a location until a heartbeat or a crossing), so the side stayed unset here; two Update Location taps then put two GPS fixes inside: "Here now", "Within 5 m · At Home". The note: §6t step 3 should say the inside fixes need the heartbeat, a crossing or Update Location to reach the server. |
| 4 | Leave: the fence's exit, the two fixes, the event | PASS | `Fence of zone 1 (entering=false): CROSSING`, `Moving series: started, first check in 180 s`, `Sync for [zone]`, 2 min later `Sync for [zone_confirm]`; "left Home" in the inbox; "Here now" gone from the page. |
| - | Return: arrive again | PASS | `entering=true: CROSSING`, `[zone]` + `[zone_confirm]`, "arrived at Home" in the inbox, "Here now" back. |
| - | Latency crossing -> event | ~2 min | the confirming fix at +2 min is the second sure fix on the new side. |
| 5-9 | straddle, no-flap, re-register on radius change / delete / Only when asked, reboot + Doze, the heartbeat/queue, the still phone | not run | pinned by `ZonePlanTest`, `zone_watch.rs` and the step-1 tests; the heartbeat's 30 min and the motion sensor need the Jelly Star (no significant-motion sensor on the emulator - `zone_watch.motion_sensor` false on the page). |

## Notes

- The first event after enrolment came from the earlier, unseeded crossing: with no side yet, the first confirmed side
  ("outside") was set silently, as designed; the next arrival then raised "arrived at Home".
- `jelly36` lost its userdata again when a start failed for disk space (6.1 GB free; the emulator needs 7.4 GB) -
  the AVD recreates the partition. Keep 10 GB free before a run.
- The debug build accepted the test provider's fused fixes (mock) and the emulator's GPS fixes (not mock), as
  §6t's reading of AOSP says; the release build's mock rule is pinned by tests.
