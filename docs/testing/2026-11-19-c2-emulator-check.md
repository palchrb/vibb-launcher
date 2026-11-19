# Emulator check: design 116 (C2), failures made visible (branch `c2-build`)

`docs/testing/emulator.md` §6bc, design 116 §3.3 (the gate's steps 0-4), with code QA L1 and the PWA's parts (§2, §3.4,
§4). Run 2026-11-19 07:41-09:38 UTC with the user's OK ("Ja"): `compat34` 07:47-08:44, `jelly36` 08:45-09:38, one
emulator at a time.

**Build.** `c2-build` at d7eebcaea: the debug server (`cargo build -j 1`, 0.84.0) and the debug launcher (`assembleDebug
-PwarningsAsErrors=true`). Every step ran on d7eebcaea; the one fix (below, a5c0e70e9) was then checked on `jelly36`.

**Rig.** Under `flock -o ~/emulator.lock` (and `~/build.lock` while an emulator ran), port 5556. The server on a fresh
`data/` with `BIND_ADDR=0.0.0.0:3120`, `DEVICE_BIND_ADDR=0.0.0.0:3121`, the debug `LAUNCHER_ADMIN_COMPONENT`, a dummy
`LAUNCHER_SIGNATURE_CHECKSUM`. Phones "Testtelefon" (device 1, `compat34`) and "Testtelefon J" (device 2, `jelly36`),
Parent PIN set, Block USB debugging off, the debug launcher made device owner with §4's grants, enrolled with a typed
setup code; 114's setup hold ended with the PWA's "Lås igjen". The filter on (the default), Mobildata "På", "I
utlandet" "Bare Vibb" (the default), on the emulator's Wi-Fi.
- The deny: `adb shell appops set me.vibb.launcher.debug ACTIVATE_VPN deny` (`allow` to lift it).
- The kill: `adb shell run-as me.vibb.launcher.debug kill -9 <pid>` (`am crash` is refused for a device owner; Home
  restarts the process at once).
- Doze: `dumpsys battery unplug`, `input keyevent SLEEP`, `dumpsys deviceidle force-idle` (deep state `IDLE` checked
  before and after).
- What was read at each step: the stored report's `vpn_filter_json`, `parent_events`, `adb shell dumpsys connectivity`
  (`ni{VPN CONNECTED`), `appops get`, the pending `VPN_RETRY` alarm in `dumpsys alarm`, notification 1003 in `dumpsys
  notification`, the launcher's `vpn_*` prefs, the tagged logcat (`KidVpnService`, `VpnRetryAlarm`, `MobileNetwork`,
  `SyncRunner`).

Versions:
- **Android 14:** `compat34` (API 34, `-wipe-data`).
- **Android 16:** `jelly36` (API 36, 480x854 @ 240 dpi). At the start it equalled `~/kid-look-1-avd-pre-start` (22 of
  22 sums). It was restored from that copy afterwards (22 of 22 sums, the same file list, `jelly36.ini` equal). No
  snapshot was made. Battery 100 % on AC (back on AC after each Doze step).
- **Android 17:** not run (no API 37 image boots on this VM).

**Two interruptions.** Another agent's `cargo check --all-targets -j 1` in the `maps` worktree (clippy-driver at 3.2
GB, outside `~/build.lock`) got the emulator OOM-killed twice: `compat34` at about 07:58, during step 1's first
10-minute wait, and `jelly36` at about 09:10, right after step 2. Each emulator came back with a cold boot once the
check had ended. The lost step 1 on 14 was run again. Each cold boot counts as an unplanned restart: the filter was
up, the reports said `up`, there was no event, and the stored period was cleared.

## Results

| Step | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| 0. The deny through a shape change | **The deny holds through a shape change that doesn't write always-on, and not through one that does.** With the filter on and the deny set, filter off (PASS: "I utlandet" Bare Vibb keeps the watcher) and on again: the op stays `deny`. The PASS switch fails (`The pass tunnel failed`), and always-on stays the launcher's in both shapes, so nothing is written. Through NONE ("I utlandet" `home`, then the filter off and on): `Always-on: none` puts the op to `default`, and `Always-on: me.vibb.launcher.debug` puts it to `allow`. **A write re-grants**, and so does a boot (the op read `allow` after the cold boot). So the gate used the crash path with the deny set just before it, as §3.3 step 0 says | the same (`The pass tunnel failed` at 08:48:21; through NONE: `default`, then `allow`) |
| 1. A dead tunnel that can't come back | **Pass.** Deny, kill at 08:09:51. The new process's `establish()` returns null, the period `2:101692` starts at once, and the alarm is armed. Report `{"state":"down","down_ms":6141,"period":"2:101692"}`. `dumpsys connectivity`: no VPN network. Notification 1003 ("connecting") shown. One `Filter down: telling the server` at 08:23:42 (`SyncRunner: Sync for [vpn_filter]`), report `down_ms` 826 108. The summary's red row "Nettfilteret er nede" (`attention-alarm`, to `/devices/1/filter#filter`), the filter page's "Nede i 15 min". One `filter_down` event (dedup `2:101692`, `happened_at` the period's start); the later reports added none | **Pass.** Kill at 08:49:46, period `2:273994`, report `down` (3 208 ms), no VPN network, 1003 shown. One `telling the server` at 09:03:32, report 825 867 ms. The row "Nettfilteret er nede", "Nede i 14 min", `/dns`'s line "Testtelefon J: nede". One event (dedup `2:273994`). Activity: "Nettfilteret er nede på Testtelefon Js mobil · 09:49" (the outage's start, local time) |
| - The row's timing | The row came at 13 min 46 s, not 10. The 10 minutes are seen by the next retry: the fast chain is at 5 min by then, and the alarm is inexact | the same, 13 min 46 s |
| - The push | Not seen: the rig has no push subscriber (`push_subscriptions` empty). The event row is there once; its push is owed on a real browser | the same |
| 2. Recovery in Doze | **Pass.** Deep `IDLE` at 08:25:20, allow at 08:25:22. **The alarm** fired at 08:27:27: the receiver's check (`Filter down: a retry in 5 min`), then `Tunnel: filter`, `Filter up: telling the server`, `foreground state dropped`. 2 min 5 s after the allow. Report `up` at 08:27:28, the row gone, 1003 gone, the alarm cancelled, still `IDLE` | **Pass.** `IDLE`, allow at 09:04:55. `Tunnel: filter` at 09:08:34, 3 min 39 s after the allow, from **the Handler's** 5-minute retry. The emulator doesn't suspend its CPU in forced Doze, so the Handler isn't held back; the alarm was then cancelled. Report `up` at 09:08:35, the row gone |
| 3. PASS that fails to become FILTER | **As written, the premise doesn't hold: a pause's shape is NONE, not PASS.** The pause lifts every sink reason (`vpnShape`), so Bare Vibb's PASS doesn't apply. With Mobildata "Bare Vibb": start a pause (`Always-on: none`), deny, end it. The always-on write re-grants (step 0), so `Tunnel: filter` and report `up`. That is true. **The PASS case through the parent's switch: pass.** With Bare Vibb, filter off: `Tunnel: pass`, report `off`. Deny, filter on: `The filter tunnel failed`, the pass tunnel stays (the VPN network is there), and the report says `{"state":"down","down_ms":86,"period":"2:1276936"}`, not `up`. Allow: `Tunnel: filter` at the next fast retry, report `up` | the same: the pause gives `up` after the re-grant; the PASS case gives `down` (85 ms, `3:198147`) |
| 4. A reboot doesn't start a period | **Pass.** `adb reboot` at 08:42:45 with the filter up. `Tunnel: filter` at 08:43:31. First reports at 08:43:55: `{"state":"up"}`. No alarm armed, no event (only `phone_on`) | **Pass.** Reboot at 09:27:10, `Tunnel: filter` at 09:28:10, reports `up` from 09:28:30, only `phone_on` |
| - The "connecting" notification | Shown while the filter is down after a kill. Gone with the tunnel (`foreground state dropped`). **Not shown** in step 3's PASS-left `down`: the foreground settle sees a tunnel. Seen, not changed: design §6 keeps today's notification | the same |
| - One sync per down period | One `Filter down: telling the server` per period. One `Filter up: telling the server` at the first `up` after a reported `down` (also after step 3's 86 ms period, as §3.1 says) | the same |
| - Code QA L1: a stale alarm with the filter off | **Pass.** A stale alarm left on purpose: deny + kill (process B arms it), then allow + kill (process C comes up with B's alarm and its own `armed = false`). Then the filter off with no sink reason (Mobildata På, "I utlandet" `home`: NONE). The report's check kept the alarm (KEEP), and it fired at about 08:41:11 (the wakeup in `dumpsys alarm`'s stats). No `KidVpnService` started, no `Start proc`, no notification 1003. While the alarm was armed in the running process, the filter-off report's check cancelled it at once | **Pass**, the same: the alarm fired at about 09:25:03, and no service and no 1003 |
| The PWA: one `Integration` row | **Pass** (server-side, both phones). A blocklist "Testliste" at `http://127.0.0.1:8799/list.txt`, a local server that 404s. Its add fetched it (`http_404`, `parent`, failures 1). Off and on again fetched it twice more (failures 3), which is `Fix`. Each phone's summary then showed the orange "1 blokkeringsliste oppdateres ikke" to `/dns`, and `/dns` shows "Ikke lastet ennå" at the list | the same row on device 2 |
| The PWA: the backup check | **Pass.** "Ta sikkerhetskopi nå": the newest row reads "47,1 KB · kontrollert". A corrupted copy uploaded through the page (the zip's `kidphone.db` with pages 3-12 overwritten): the upload's check ran at once (code QA L4) and stored `integrity` (`stop`). Backups shows the orange "Sikkerhetskopien kan ikke leses" with its (i), and the Devices list shows the family row "Sikkerhetskopien kan ikke leses" to `/backups`. "kontrollert" is gone, since the newest file is the bad one. Deleting the bad file removes the line and the row and prunes the `file:` row, and "kontrollert" is back on the good copy | - |

## The bug found and fixed (on the branch, with a test)

**The fast retry's wait doubled with every failed start, not every failed retry** (14 and 16). After a kill with the
deny set, the new process's start commands (`onCreate`, each `onStartCommand`, `apply()`'s start) each failed at once,
and each failure doubled `reshapeRetryMs`. The wait went from 5 s to 160 s within two seconds:

```
08:09:57.131 The filter tunnel failed, retrying in 5000 ms
08:09:57.147 ... retrying in 10000 ms
08:09:57.161 ... retrying in 20000 ms
08:09:57.181 ... retrying in 40000 ms
08:09:58.557 ... retrying in 80000 ms   (then 160000, then 300000)
```

So a failure that lasted ten seconds kept the filter down for almost three minutes, against §3.1's "5 s doubling to 5
min".

The fix (a5c0e70e9): `reshapeRetryStep(retryPending, delayMs)` in `MobileNetwork.kt`. A failure while a retry is
already waiting (`Handler.hasCallbacks`, API 29) leaves that retry where it is. Only the retry's own failure schedules
the next one and doubles the wait. The log names only `retryMs`, which `CustomUpstreamPrefTest` allows. Test:
`MobileNetworkTest` "only the retry's own failure doubles the wait" (a burst of five failures schedules one 5 s retry,
then 5, 10, 20, 40, 80, 160, 300, 300 s).

Checked on `jelly36` with a5c0e70e9 installed over the enrolled phone. Deny, kill at 09:34:30:
- `retrying in 5000 ms`, then five `a retry is already due`.
- Retries at 09:34:37 (10 000), 09:34:47 (20 000), 09:35:07 (40 000) and 09:35:47 (80 000).
- Allow at 09:36:16: `Tunnel: filter` at 09:37:07, at the next retry. Report `up`, 1003 gone.

## Seen, not changed

- **Step 0's answer.** `setAlwaysOnVpnPackage` re-grants ACTIVATE_VPN (and `null` resets it to `default`), and so does a
  boot. A deny survives only shape changes that keep always-on as it is (FILTER and PASS, lockdown off).
- **Design §3.3 step 3 needs another trigger.** A pause gives NONE (it lifts Bare Vibb), so ending it writes always-on
  and re-grants. The PASS case comes from the parent's filter switch with Bare Vibb on, as run above. The design's text
  wasn't changed here.
- **The while-idle alarm is up to 3 min 45 s late.** An inexact `setAndAllowWhileIdle` gets a 75 % window. Measured: due
  08:14:57, fired 08:18:42 (14); due 08:54:46, fired 08:58:31 (16). So "every 5 minutes" is every 5 to 8 min 45 s.
  Doze recovery took 2-4 minutes here, and the row came at about 13 min 46 s.
- **The connecting notification** is not posted when a PASS tunnel is left after a failed switch (step 3), though the
  state is `down`. Today's foreground rule counts any tunnel. Design §6 says nothing is new on the kid's phone.
- **The blocklist refresh loop** picks its wait before it sleeps. A feed that starts failing while the loop sleeps its
  hour is retried after that hour, not after 5 minutes (pre-existing, security review M6's loop). The gate's second
  fetch came from a toggle.
- **The rig's own row:** "1 app får ikke oppdateringer" (`github` `NoRelease` on Vibb One's row). The rig's launcher row
  is pinned to the debug package, which has no release. Not a branch fault.
- On `compat34`'s first cold boot, under the other agent's memory pressure: `Timeout executing service: KidVpnService`
  (08:09:07, with no ANR dialog). The filter came up at 08:09:11.

## Not run, owed

- Android 15 and 17 (no images run), and the Jelly Star.
- The push itself: the rig has no subscribed browser. The `filter_down` event row is there exactly once per period on
  both phones.
- Step 2 on Android 16 was recovered by the Handler, not the alarm (the emulator keeps its CPU awake in forced Doze).
  The alarm's own recovery in Doze was seen on 14 only. On a phone in real Doze the Handler is held back and the alarm
  is what wakes it.

## Screenshots

On `jelly36`'s server, nb, at 390 px, for the user (not committed):
- `summary-filter-down-nb.png`: the summary's red row, with the Integration row.
- `filter-page-down-nb.png`: "Nede i 14 min" and the Testliste "Ikke lastet ennå".
- `dns-filter-down-nb.png` and `dns-blocklist-nb.png`.
- `devices-list-nb.png` and `devices-list-backup-nb.png`: the family row.
- `backups-checked-failed-nb.png`: the orange line.
- `backups-checked-nb.png`: "kontrollert".
