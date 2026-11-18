# Emulator gate: app push held while the apps have no network (design 115), 2026-11-18

Design 115 §6's rows on the branch `up-held-115`, run overnight under the user's standing OK (one emulator at a time,
under `flock -o ~/emulator.lock` and the build lock). **`jelly36`** (Android 16) restored clean from
`~/kid-look-1-avd-pre-start` (22 of 22 sums), started without `-wipe-data`, and restored again afterwards (22 of 22).
**`compat34`** (Android 14) started with `-wipe-data -dns-server 8.8.8.8`. Android 17 not run (no API 37 image renders
on this VM, §6as) - owed.

**The rig** (`docs/testing/emulator.md` §6ay's): the branch's debug server (`BIND_ADDR=0.0.0.0:3108`,
`DEVICE_BIND_ADDR=0.0.0.0:3109`), §6ay's data (device 1, the public origin `https://up.test`) migrated to 0156; the
branch's debug launcher as Device Owner, enrolled with a typed code; `scripts/fake-homeserver.py notify` posting
Synapse-shaped pushes to the probe's endpoint. The probe is §6ay's `me.vibb.probe.up` with three additions for this
gate: each MESSAGE's `event_id` logged and **one "fetch" per MESSAGE on one worker thread, one after another, each at
least 500 ms** (`GET http://10.0.2.2:3109/`, 3 s timeouts, logged `net=ok|fail`; QA M3 (a): Element X's pace), and a
`notify` command posting a plain notification titled T or a MessagingStyle one whose conversation is T. Wi-Fi off and on
with `svc wifi disable|enable` (with the screen off the emulator reconnects only on `cmd wifi connect-network
AndroidWifi open`). The emulator's cellular is the always-present emulated LTE, so Wi-Fi off under Bare Vibb is the
user's case. Database reads by SQLite on the server's file (`up_messages`, `up_delivery`, `device_status.up_held` and
`mobile_data_json`'s `sink`).

**One fix came out of the gate** (row 4 on 16, commit c5e70a40a): the kid's own tap asks a sync, and that sync's pull
ran 60 ms after the tunnel left the sink - before the app's network was usable: the push it handed out was sent and
the probe's fetch failed (`ConnectException`), which is Element X's fallback. Now, while a hold was seen, a pull or a
send within 1.5 s of the last shape change counts as held (`UpRuntime.SETTLE_MS`), and the "net" pull 2 s after the
change delivers. Rows 3, 4 and 6 on 16 were run (again) on the fixed build, and every row on 14; rows 1, 2, 5, 7, 10-13 on 16 ran on
the build before it (f8d1d19bb), whose code differs only by that window. The code QA's Lows that followed (one refusal
asks the "net" pull when only the network's view held; `writeAlwaysOn` tells app push only of a real change) were not
run on the emulator: CI and the unit tests cover them.

| Row | `jelly36` (16) | `compat34` (14) |
|---|---|---|
| 1 baseline (Wi-Fi on, Bare Vibb on) | **Passed.** MESSAGE 33 ms after the push, `net=ok`, acked by the next pull | **Passed.** MESSAGE 49 ms after the push, `net=ok` |
| 2 held (Wi-Fi off) | **Passed.** `Tunnel: sink`, lockdown on; 5 pushes accepted (`201`), no pull and no MESSAGE, all 5 rows `fetched_ms` NULL; the next report `up_held = 'no_network'` with `mobile_data.sink = 'parent'` | **Passed.** The same: 5 rows unfetched, every pull refused (`Pull for sse held (no_network)`), the report `no_network` / `parent`. The baseline's ack, due 0.6 s after the sink started, waited and rode the first pull after the hold |
| 3 back (Wi-Fi on) | **Passed.** `Tunnel: filter`, the "net" pull 2.00 s later with `held=true`; five MESSAGEs `$fakeEvent0001`..`0005` in order, each `net=ok`; one raise window for the probe (`BOUND_TOP` 5.0 s, screen on); the ack pull 6 s after the sends acked all five (`held=false`); queue empty; `up_delivery` unchanged (20 -> 20); the next report `up_held` NULL. Again on the fixed build: a reopen's pull at +1.59 s delivered, `net=ok` | **Passed** (fixed build). The "net" pull 2.0 s after `Tunnel: filter` (`held=true`, carrying the baseline's ack); five MESSAGEs in order, each `net=ok`; one raise window (`BOUND_TOP` 5.0 s); the ack pull acked the five; the five left no delivery sample, the baseline's one did (26 -> 27); the next report `up_held` NULL |
| 4 the kid's switch | Bare Vibb off, bit 32, Wi-Fi off. The kid's Off: `Tunnel: sink`, every pull refused (`Pull for sync/sse_open/sse held (no_network)`), the report `up_held = 'no_network'`, `sink = 'kid'`. The kid's On, first build: **failed** - the tap's sync pulled 60 ms after `Tunnel: filter`, the fetch `fail(ConnectException)` (the fix above). **Fixed build: passed** - the sync's pull at +49 ms and the reopen's at +1.6 s held (the settle), the "net" pull at +2.25 s delivered, `net=ok` | **Passed** (fixed build). The kid's Off: held, the report `no_network` / `kid`. The kid's On: the sync's pull at +63 ms and the reopen's at +1.6 s held (the settle), the "net" pull at +2.3 s delivered, `net=ok` |
| 5 the parent's choice | **Passed.** Held as row 2; the PWA's Mobildata "På": `Tunnel: filter` 0.2 s into the save's sync, the first pull 1.5 s later (`held=true`) delivered, `net=ok` | - |
| 6 a pause | **Passed** (fixed build). Held as row 2; the PWA's start-pause: always-on none (the VPN stopped), the "net" pull 2.0 s later (`held=true`) delivered, `net=ok`. The pause ended with Wi-Fi still off: `Tunnel: sink` again, the next push held (`Pull for sync held`, unfetched) | - |
| 7 no network at all | **Passed**, with a caveat: the emulator's airplane switch left the radio powered off while the setting read 0, and Wi-Fi was off - no network at all: the command stream fails, no pull, the push stays unfetched. Wi-Fi on: the reopen's pull delivered, `net=ok` | **Passed**, the same caveat (radio off, setting 0): no pull, unfetched; Wi-Fi on: the reopen's pull delivered, `net=ok` |
| 8 abroad | Not run (needs §6ak D's private `-sysdir` with the roaming SIM profile) - owed | - |
| 9 TTL | - | **Passed.** Wi-Fi off, `up --ttl 60`, 90 s: the row past `expires_at_ms` (still in `up_messages` until the hourly prune); Wi-Fi on: the "net" pull and the reopen's handed out nothing |
| 10 Wi-Fi drops mid-pull | 20 pushes and `svc wifi disable` 0.1 s apart: **4 MESSAGEs went out 0.34 s before `Tunnel: sink`** and their fetches failed (`SocketException`/`ConnectException`) - the race §9 names as inherent (no view said the sink yet, so neither the pull nor the send could hold them); the other 16 were never pulled. Wi-Fi on: the first pull (`held=true`) acked the 4, the 16 came with `net=ok`. **None lost** (all 20 sent once, all acked) | The same: **5 MESSAGEs went out 0.47 s before `Tunnel: sink`**, fetches failed (the inherent race); 15 never pulled. Wi-Fi on: the first pull (`held=true`) acked the 5, the 15 came `net=ok`. **None lost** |
| 11 Attention quiet | **Passed.** Held as row 2; the push backdated 40 minutes; three syncs (three served policies, three reports `up_held = 'no_network'`, `sink = 'parent'`); 2 minutes on: no `app-push-waiting` on the summary, the Devices list's badge unchanged (2). Controls: the three reports' `up_held` set NULL -> **raised** (badge 3); `up_held` back and their `sink` set `off` -> **raised** (QA M2); restored -> quiet. Wi-Fi on: delivered, still no row | - |
| 12 the glance name | **Passed.** The PWA names the probe "Chat", ticked at "Full text"; Android's PIN (the Child PIN). Plain "UP Probe" (its own label) -> the row reads **"Chat"**; plain "Mamma" -> "Mamma"; MessagingStyle with the conversation "UP Probe" -> "UP Probe" (a conversation's name kept, "Group UP Probe, latest from Lily"). Android's shade keeps `android.title` "UP Probe" | **Passed**, the same three rows (nb: "Gruppe UP Probe, siste fra Lily"); the shade keeps "UP Probe" |
| 13 the burst (QA M3) | **Passed.** Screen off, Wi-Fi off, 20 pushes 200 ms apart, Wi-Fi on: 20 MESSAGEs in one raise window (`FOREGROUND_SERVICE` 5.0 s), the probe's sequential fetches ran 10 s - **all 20 `net=ok`**, the last five after the probe was back at `CACHED_EMPTY` (the emulator didn't cut a cached app's network within 5 s). The pacing (§2.5) is not built | **Passed.** Screen on: 20 MESSAGEs in one raise window (`BOUND_TOP` 5.0 s), all 20 `net=ok` over 10 s, the last ones with the probe `CACHED_EMPTY` |

Owed: the Jelly Star with Element X (R1-E: Bare Vibb, Wi-Fi off, a few chat messages, Wi-Fi on - the real notifications,
no "Du har N nye meldinger", the burst against Android's update rate and Android 16's background network rule on real
hardware), row 8 (abroad), Android 17.
