# C1 step 16: the emulator check (2026-11-18)

Round C1 ("compatibility code out"), branch `c1-compat-out` at `4a8b83cda`, on the x86 VM.

- **Images**: `compat34` (Android 14, google_apis) and `handy36` (Android 16, Play image), one at a time under
  `flock -o ~/emulator.lock`, headless, `-wipe-data`, battery set to 100 after boot. `compat37` not run (Android 17
  does not boot on this VM).
- **Builds** (from `4a8b83cda`, debug only): the server (`cargo build`, debug), the debug launcher at versionCode 116
  (`0.23.6-debug`), 200 and 201 (`-PversionCode`, for Install Now), the debug music app (code 1). The launcher and the
  music app carry the same local debug key.
- **Server**: a throwaway database, `LAUNCHER_ADMIN_COMPONENT` = the debug component, `STORYTEL_BASE` =
  `scripts/fake-storytel.py` on 8730 (books `160661..160663`, the shelf fixture's series 26175). The phones reached it as
  `http://10.0.2.2:3216` through a local TCP forwarder that recorded each phone's bearer token and request lines (so
  the policy could be read with the phone's own token). Both phones were enrolled with a setup code in Vibb One's
  Settings, `dpm set-device-owner` first.
- **PWA**: every device page fetched with the admin session in en and nb (`?lang=`): the summary, Calls & SMS,
  Calendar, Time, Mobilnett, Apps, Lock Screen & PINs, Web Filter, Music, Advanced, About, Home Screen, Logins,
  Activity, Find, Locations, Zones, plus the launcher's catalog page, Locate and Devices - 20 pages x 2 languages. Each
  checked for "Oppdater Vibb One", "Update Vibb One", "after Vibb One is updated", "Needs a newer Vibb One", "newer/nyere
  Vibb One", "Vibb One er oppdatert". One save per page with a settings form, posted with the form's own values.

## Results

| # | Check | compat34 (Android 14) | handy36 (Android 16) |
|---|---|---|---|
| 1 | Enrol as Device Owner, launcher + music installed | PASS - `dpm list-owners`: `me.vibb.launcher.debug/...MdmDeviceAdminReceiver,DeviceOwner`; music sideload held (`outside_play`), let out by "Allow these apps", then `hidden=false suspended=false` | PASS - the same |
| 2 | Sync + status report; policy keys | PASS - every `device_status` row (27) `policy_state` `ok`, `capabilities_json` `["android_keyguard_v1"]`; the served policy has `server_tls: {}` and `unified_push: {on: false, origin: null, regs: [], stopped: [], refresh: []}` | PASS - 31 rows, the same values (58 rows over both phones, all `ok`, all exactly `["android_keyguard_v1"]`) |
| 3 | Every PWA page 200, no update-Vibb-One text; a save lands on its `#anchor` | PASS - 40/40 GETs 200, 0 hits; 12 saves, every 303 to the form's own fragment (e.g. `/devices/1/mobile#mobile-data`, `/schedules/device/1` -> `/devices/1/time#screen-time`) | PASS - 40/40, 0 hits; 12 saves, all on their fragments |
| 4 | Install Now offers and installs a newer launcher | PASS - manual launcher row, `step16-200` uploaded; the row offered Install Now over 0.23.6; command `installed: 0.0.200-step16-debug`, report code 200 | PASS - 200 had arrived by the ordinary self-update first (screen-off during check 7; an Install Now then answered `already running`); `0.0.201-step16-debug` uploaded, Install Now: `installed: 0.0.201-step16-debug` 10 s later, report code 201, the row "0.0.201-step16-debug · Install Now · Done" with no button |
| 5 | Ring from the PWA, its receipt | PASS - `ring` delivered, received and acknowledged in the same second, `succeeded=1`, `ringing` | PASS - the same (`ringing`) |
| 6 | Music over the bridge; Storytel token through the bridge; old route 404 | PASS - `music_state.bridge` `bound`, `library_version` `3425baaf5ace2b87`; the phone's `POST /api/devices/music/storytel/token`, then the fake's `asset 16066x: 302` and CDN 200s; policy `music` with `stream_mobile_data`/`download_mobile_data`, no `mobile_data`; `GET /api/devices/music/storytel` 404 | PASS - the same; the report's downloads `have 3, want 3`, `audio_bytes` 6000132; old route 404 |
| 7 | Child PIN on Android's lock | PASS - `set_kid_pin` delivered, answer `pin set`; `lock_state.keyguard` `android`, `pin_rev` 1, `reset_token` true; `locksettings verify --old 2468` succeeds; screen off/on: SystemUI's bouncer "Enter your PIN", 2468 unlocks | PASS - the same; the glance's swipe up asks Android's pad ("Enter PIN"), 2468 unlocks |
| 8 | SMS on the lock glance (Meldinger "Name only") | PASS - calls and SMS "Styrt av Vibb", Mamma added; `adb emu sms send +4791234567 ...`: glance row "Mamma / New message" (`New message from Mamma, 3:06`) | PASS - the same row |

## Fixed

Nothing: no check failed on C1's code.

## Notes

- The launcher's catalog row on a manual upload offers Install Now again while the label differs from the phone's
  build name (`step16-200` vs `0.0.200-step16-debug` on compat34) - design 37 §2.2's accepted behaviour, unchanged by
  C1 (`install_now::release`); with the label equal to the build name (handy36's `0.0.201-step16-debug`) the row
  reads up to date.
- On a phone restarted with Android's PIN, `uiautomator dump` must write under `/data/local/tmp` until the first
  unlock (`/sdcard` is not mounted before it).
