# 91 - Find My Device before the first unlock after a reboot (a design note)

Status: design note by the architect, 2026-11-12, on `main` at 17f4aab7, branch `design-91`. A note to decide on,
not a build order: nothing here is approved. The user, 2026-11-12: "ja lag et designnotat på sletting uten opplåsing,
men usikker på om det er verdt det!"

What is read and what is inferred. Read in this tree: `AndroidManifest.xml`, `calls/BootCallReceiver.kt`,
`server/SealedPrefs.kt`, `server/LocateCommands.kt`, `server/FindMyDeviceRing.kt`, `server/MdmSyncWorker.kt`
(`dispatchPendingCommand`), `server/ServerPinStore.kt`, `lock/BootCoverGuard.kt`; design 59's assessment (§2 is the
first sketch of this note: "a BFU beacon", "not recommended for the first step"); the notes `security.md` (wipe M8,
receipts L12, design 76), `calls.md` (direct boot, task 15), `zones-and-power.md` (design 38), `gotchas-and-history.md`
(the boot lockouts), `dns-and-vpn.md`; the server's `architecture.md` (Find My Device, the command queue, the
stream). Read upstream: `siesta5787/kids-launcher-mdm` 745fcbfc2c ("Keep Find My Device working before the first
unlock", launcher 0.52.0) and 0d4201dab9 (a fresh tsnet after a failed connect), `siesta5787/kid-phone-server`
75eab24ef1 (`POST /api/devices/locked-report`, 0.20.0). Read from Android's docs: the direct boot page and Android
15's `BOOT_COMPLETED` foreground-service rule. Marks: **[aosp]** read from AOSP source by design 59 (its line
references), **[docs]** developer.android.com, **[upstream]** the commits above, **[device]** must be checked on the
Jelly Star before it is trusted, **[inference]** reasoned, not read.

BFU = before the first unlock after a boot. AFU = after it. CE = credential-encrypted storage (unlocked by the PIN),
DE = device-protected storage (readable from boot).

## 0. The answer in short

- **What it buys**: a phone in android mode that restarted and nobody has unlocked can again be **located and rung**,
  says "restarted 14:02, not unlocked since", and takes Lock and Erase. Today it is silent until the PIN is typed.
- **Erase is the weakest part.** BFU is when the data is safest: CE's keys are not in memory and the PIN is
  rate-limited in the TEE. An erase BFU adds little protection and ends Find for good. The value is Locate and Ring.
- **The gap grows.** Phase D (the own lock removed) puts every phone in android mode, so every restart opens the gap.
  Google Play services' inactivity reboot (three days locked) [unverified on the Jelly Star] would make BFU the
  normal state of any phone lost for more than three days.
- **Cost**: server about one round (S-M), launcher about one and a half (M-L), plus a device check round. Bigger than
  upstream's 400 lines, because we keep least privilege, a separate process and our test discipline.
- **Risk**: a credential in DE storage (narrow: it can do five things), a new BFU process next to call screening (own
  process, so a crash costs only the beacon), and a new auth path on the public server.
- **Recommendation: build it, smaller than upstream, but not now.** A narrow "locked beacon": its own token, its own
  routes, https only, the five Find commands, nothing else. Time it before phase D ships, after today's queue. If the
  reason to build is Erase alone: don't.

## 1. The problem, precisely

### 1.1 When BFU happens, and to whom

- **Own mode** (no Android credential): CE unlocks by itself at boot (`unlockUserKeyIfUnsecured`, design 59 §1)
  [aosp]. BFU lasts seconds. Find already works after a restart. **This note doesn't apply.**
- **Android mode** (design 59: Android's keyguard with the Child PIN): after every boot the phone is BFU until the PIN
  is typed. Power-on, a restart, a crash restart, a flat battery charged again, a night-time OTA restart
  [unverified on the Jelly Star], and possibly Play services' inactivity restart after three days locked [unverified].
- BFU only our direct-boot-aware (DBA) parts run: call screening, the in-call screen, `BootCallReceiver` and the boot
  cover. The device token, the server URL, the policy, the sync, the stream and the location cache are all CE.

### 1.2 What the parent sees today

- **Before the phone goes off**: design 38's shutdown report, "off since 21:14, battery was 2 %", with the last stored
  fix (`POST /api/devices/power`'s `location`). A flat battery sends it too (Android's `ACTION_SHUTDOWN`).
- **After it comes back on, BFU**: nothing. "Seen" stops at the shutdown. Ring, Update Location, Lock and Erase wait
  in the queue. Ring and Update Location expire after an hour (`EXPIRING_COMMANDS`); Lock and Erase wait.
- **After the unlock**: the start record (DE, written BFU already) goes up, the queue runs.

### 1.3 The cases, and which ones the beacon helps

| Case | Today | With the beacon |
|---|---|---|
| Lost at home, school or a friend's, restarted (OTA, inactivity restart, a flat battery charged) | Silent until found | Located, rung - the main case |
| Found by a stranger, flat, charged at a lost-and-found desk | Silent | Reports where it is once it has Wi-Fi it knows or mobile data |
| Thief switches it off and keeps it off, or pulls the SIM | Last position at shutdown | The same: no radio, no beacon |
| Thief turns on airplane mode from the locked quick settings (`DISALLOW_AIRPLANE_MODE` is off by default) | Silent | Silent |
| Thief powers it on with the SIM in, out of known Wi-Fi | Silent | Reports over mobile data (no SIM PIN set) |
| The kid restarted it and hasn't typed the PIN yet | Silent for minutes | "Restarted 14:02, not unlocked yet" |

A thief who knows phones defeats it as easily as Find AFU. What it helps is the ordinary lost phone.

## 2. What upstream did, and what we take

Upstream (launcher 0.52.0, server 0.20.0) [upstream]:
- `LockedBootReceiver` (DBA, `LOCKED_BOOT_COMPLETED`) starts the always-on `CommandListenerService`, now DBA, in a
  "locked mode": it brings up tsnet from the node identity, holds the stream, and on each nudge fetches the **full
  policy** only for `pending_command`, runs it through the normal dispatch, and posts a fix to
  `POST /api/devices/locked-report` (location only; bumps `last_seen_at`, never writes `device_status`).
- DE holds the server URL, **the full device token**, tsnet's node identity (moved from CE) and tsnet's crash guard.
  "Security trade-off accepted knowingly", mitigated by the tailnet ACLs.
- Ring: the alarm sound is a MediaProvider `content://` URI that may not be readable BFU, so it falls back to a
  `ToneGenerator` tone; `StopRingReceiver` became DBA.
- Location BFU skips the CE throttle and cache and always takes a fresh fix.
- 0d4201dab9: at `LOCKED_BOOT_COMPLETED` Wi-Fi comes a few seconds later; tsnet started before the network stayed
  stuck. (Already in our `upstream-fixes` round.)
- On unlock the same service switches to normal mode in place.

What we take: the trigger, "the network comes a few seconds after the broadcast", the tone fallback, the DBA stop
receiver, fresh fixes only, a report that never writes the status row, the in-place handover.

What we don't:
- **The full device token in DE.** Ours opens far more than theirs (§6.2): the policy carries the contacts, the zones'
  coordinates (home, school), the parent PIN's hash and salt, `set_kid_pin`'s Child PIN, calendar feeds; the token
  also opens Storytel's token route and the music accounts. Android's own guidance: "Don't migrate private user
  information, such as passwords or authorization tokens, from credential encrypted storage to device encrypted
  storage" [docs]. A narrow token instead (§4.3).
- **tsnet BFU.** Moving the node identity to DE puts a tailnet member's key behind no PIN (design 59 §2.2 said no).
  We have a public https endpoint, so the beacon is https only (§4.6).
- **The full policy fetch BFU.** The beacon has its own routes that return only a Find command.
- **The beacon inside the always-on service.** Ours runs in its own process (§4.1): a BFU crash must not take call
  screening with it.

## 3. What a phone can and cannot do before the first unlock

| Part | BFU | Source |
|---|---|---|
| Our code | Only `android:directBootAware` components; `LOCKED_BOOT_COMPLETED` comes to them after boot | [docs] |
| Storage | DE only (`createDeviceProtectedStorageContext()`); CE throws or is empty. Our APK and its resources are readable | [docs] |
| Wi-Fi | Saved networks added through Settings are "shared" and kept in the system's DE store, so Wi-Fi joins BFU. A network an app suggested is per-user (CE) and waits | [inference from AOSP's WifiConfigStore layout; device] |
| Mobile data | Works, unless the SIM has a SIM PIN (typed at boot, separate from the screen lock) | [inference; device] |
| Our VPN filter | Down: `KidVpnService` is not DBA. Always-on **without** lockdown (`dns-and-vpn.md`), so traffic flows unfiltered; nothing the kid can use runs BFU | [code; design 59 §2.2] |
| DNS, TLS | The system resolver and the global private DNS (settings, DE). System CAs ship with the OS; design 76's built-in set B is in our code | [inference] |
| AndroidKeyStore | A plain key (no user authentication, no `UnlockedDeviceRequired`) works BFU; an auth-bound one answers `LOCKED` (`keystore2/enforcements.rs:687-717`, `super_key.rs:675-693`) | [aosp, design 59 §2.4; device] |
| Foreground service | A DBA `specialUse` service may start from the boot receiver: Android 15 forbids only `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` from `BOOT_COMPLETED`. A device owner is exempt from the background-start limits anyway | [docs; inference] |
| Notifications | Our channels exist (the system keeps them in DE); a notification shows on the lock screen | [inference; device] |
| Alarms | `AlarmManager` works for a DBA receiver ("triggering a scheduled alarm" is the docs' own example). `USE_EXACT_ALARM` is granted at install | [docs] |
| Doze | The battery-optimization exemption (design 19b) is the system's device-idle allowlist, kept in `/data/system` (DE), so it holds BFU | [inference; device] |
| Location | `LocationManager` is a system service; the location switch and our runtime grants are DE. **GPS** works. **NETWORK** and **FUSED** come from Play services' providers on the Jelly Star, which may not answer BFU. Indoors without them a fix may not come at all | [inference; device - the most important check] |
| Ring | `AudioManager` works. The alarm sound is MediaProvider's, which may not serve BFU [upstream]: play a sound from our APK instead (§4.4). `ZenRuleRuntime.holdForRing` reads CE: a silent time rule's Do Not Disturb isn't lifted BFU | [upstream; code] |
| Lock | BFU is the strongest lock there is: the PIN is required and CE's keys are not in memory. `lockNow` only turns the screen off | [design 59 §4] |
| Erase | `DevicePolicyManager.wipeDevice(0)` has no unlocked-user check in its path; the keyguard's own "too many failed PINs -> erase" runs the same wipe while BFU | [inference from AOSP's DPMS; device - §7] |
| Child PIN reset (`resetPasswordWithToken`) | Out of scope: it would need the reset token readable BFU (§7.3) | - |

## 4. The minimal design: the locked beacon

### 4.1 The pieces on the phone

- **No new boot receiver.** `BootCallReceiver` stays the one `LOCKED_BOOT_COMPLETED` receiver: the call policy first,
  then the boot cover, then `LockedBeacon.atLockedBoot(context)` - each in its own `try`, so neither delays screening.
- **Start condition** (pure `beaconStartPlan`): the DE android marker (`BootCoverGuard`'s, written by design 59's
  android mode) is there, the DE beacon store says enabled and has a server URL and a token, and
  `UserManager.isUserUnlocked` is false. Own mode never has the marker, so it never starts.
- **`LockedBeaconService`**: DBA, `specialUse`, in **its own process `:locked`** (`Application.onCreate` returns at
  once there, as for `:bootcover`). No `LauncherPreferences`, no CE, no tsnet, no lock task, no native code.
  `startForeground` first, as every service of ours. A crash guard in DE: two crashes in one boot and it stays down
  until the next boot.
- **Why its own process**: BFU, call screening lives in the main process. Upstream's lockout history
  (`gotchas-and-history.md`) is about exactly a BFU-started part crash-looping. In `:locked` a crash costs the beacon,
  never a call.
- **The handover at unlock**: the service listens for `ACTION_USER_UNLOCKED` and stops itself; the main process's
  normal start (as today) starts `CommandListenerService` and syncs at once. A ring still playing is stopped at the
  handover (the normal app can't stop a ring in another process); the parent rings again if needed.
- **The rest of the launcher is unchanged.** The rule in `calls.md` ("never start kiosk, enforcement, services or
  tsnet before unlock") gets one written exception: this service, in its own process.

### 4.2 What goes to DE, and what never does

One DE prefs file, `locked_beacon`, written by the unlocked app with `commit()` only when it differs (as
`boot_call_policy`), strict versioned JSON (`v:1`), missing or corrupt = beacon off:

- `enabled` - the policy's `locked_beacon.enabled` (§5.4);
- `server_url` - only when its verdict is `https` (`server_url_vectors.json`); a tailnet address writes none;
- `pin_enforce` - design 76's stored flag, mirrored (the beacon never changes it);
- `location` - `off`, `on_request` or `regularly` and the served interval, from `location_policy`;
- `token` - the locked token, sealed (`ks1:` + AES-GCM) under a **new** plain AndroidKeyStore key `locked_v1`, AAD
  `locked_beacon/token`; never the device token's key `secrets_v1`;
- `ran` - the newest eight "ran it already" keys (§4.4).

Never in DE: the device token, the policy, contacts, zones, the PIN hashes, the Child PIN, the tailnet key or
identity, any account. A source scan pins the file's keys (§9).

### 4.3 The locked token: least privilege

- **Made on the phone**, AFU: 32 random bytes, hex. Sent once over the normal channel, `POST
  /api/devices/locked-token` (device bearer) `{token}` -> 204; the server keeps only its SHA-256 (as device tokens,
  `security::hash_token`). Sealed into DE only after the 204.
- **What it opens**: only the four `locked/*` routes (§5.1). The normal routes refuse it; the locked routes refuse
  the device token. Two middlewares, two route groups: the narrow scope is structural, not a check in each handler.
- **Lifetime**: a new enrollment clears the hash (and the phone makes a new one); the PWA switch off clears it; the
  phone re-registers when its stored token was refused (401) once it is unlocked again. No expiry otherwise: a lost
  phone may sit BFU for weeks.
- **Hardware binding** (decision D3): the sealing key binds the stored blob to this phone's TEE - a copy of the DE
  file is useless elsewhere. It does **not** stop an attacker with code running on the phone BFU from asking the
  Keystore to open it and carrying the token away. The stronger form is a non-exportable P-256 key in the Keystore:
  the server keeps its public key and each request is signed (method, path, a server nonce, the body's hash). Then
  nothing usable ever leaves the phone. Cost: S on each side (the server has `ring`'s ECDSA verify from design 74);
  it is design 60's idea in small. Recommended: the sealed token now, the signed key with design 60 (§6.3 says why).

### 4.4 What it does

1. **At start**: waits for a validated network (a `ConnectivityManager` callback, not a fixed delay - upstream's
   0d4201dab9 lesson), then one **check**: `POST /api/devices/locked/check` with `booted_at_ms` (now -
   `elapsedRealtime`), the battery, and a fresh fix when `location` isn't `off`.
2. **Then holds the stream** `GET /api/devices/locked/stream` (the same nudge, no data). Every nudge runs a check
   without a fix. A 30-minute exact-while-idle alarm runs a check as the backstop (as AFU's).
3. **The check's answer** is at most one Find command: `ring`, `stop_ring`, `locate`, `lock` or `wipe`. The beacon:
   - keys it as `CommandAnswers` does (id + `requested_at`), against DE `ran`; sends the receipt
     (`POST /api/devices/locked/answer` `{command_id, requested_at, receipt: true}`) before it runs - a wipe's only
     answer when it works;
   - **ring**: alarm stream to the maximum, our own sound from the APK (`res/raw`, looped; a `ToneGenerator` tone if
     that fails), the Stop notification on the lock screen (`StopRingReceiver` made DBA, in `:locked`), the usual
     timeout and restore; answer "ringing";
   - **stop_ring**: stops it; answer "stopped";
   - **locate**: a fresh fix (fused high accuracy first, then GPS and network, the bound of the `upstream-fixes` round;
     no CE cache, no throttle), sent with the next check; answer with its accuracy and age, or "no fix";
   - **lock**: `lockNow` (the screen off); answer the new key `locked_bfu` ("Locked: not unlocked since the restart");
   - **wipe**: `LocateCommands.wipe` as today (`wipeDevice(0)`, FRP kept, `wipeOutcome`); only a refusal answers.
4. **At unlock**: the service stops, and the unlocked app merges DE `ran` into `CommandAnswers` before its first
   sync, so a command the server offers again is confirmed, never run twice. `ran` is then emptied.

The answers that don't get through wait in memory and go with the next check; nothing more (the beacon has no
outbox of its own - a lost answer shows as "received" on the Requests list, as today for a wipe).

### 4.5 How often, and for how long

- **The connection** costs what it costs AFU today: one SSE stream with a keepalive every 240 s and a check every 30
  minutes. BFU the screen is off and nothing else of ours runs, so the drain is that of an idle unlocked phone.
- **Fixes**: one at the start; one per `locate` or `ring`; and with `regularly`, one per served interval (at least 30
  minutes, as design 64's floor). With `on_request`, none beyond the first and the asked ones. With `off`, none.
- **For how long**: as long as the phone stays BFU. A phone lost for days runs it until the battery is flat - which
  is also what an unlocked lost phone does. Decision D4 offers a cheaper tail (the stream only for the first 24 hours,
  then a check every 30 minutes).
- The lock-screen notification of the foreground service is the price of the stream: one quiet "Vibb One" line.

### 4.6 The server address and the certificate pin

- **https only.** A phone whose server address is a tailnet IP (`tailnet_http`) gets no beacon: tsnet stays CE-only.
  The PWA says so (§5.5).
- **The same transport rules**: the origin fence, no redirects, and design 76's pinner when `pin_enforce` is on,
  through a `toServer` variant that takes the flag as a parameter (today's reads it from the CE default prefs through
  `Application.instance`). The built-in set B is code, so it is there BFU. The beacon never moves the flag; the
  unlocked sync's `nextFlag` stays its only writer.

### 4.7 Location rules

- `off` in the mirror = no fix is taken or sent, ever. The server also drops a locked fix while the phone's location
  setting is Off (as the status intake does).
- If Android's location switch is off, the beacon switches it on for the fix and back after, as the unlocked code
  does for an asked fix; the "I switched it on" mark is in DE, so a crash still restores it at the next start.
- The fix goes in through the same intake as any other (`zones::store_fix`): stored once, kept by the retention,
  judged for zones (design 64) - an arrival home after a restart is a real arrival.

## 5. The server

### 5.1 The routes (phone listener)

All under a new middleware `AuthedLockedDevice` (the locked token's hash -> the device), mounted only on:
- `GET /api/devices/locked/stream` - the same broadcast as `commands/stream`; counted in `command_streams` with a
  `locked` flag (the PWA's "Instant changes" line can say "locked");
- `POST /api/devices/locked/check` - `{booted_at_ms, battery: {level, charging}, location?}` -> `200 {command?:
  {id, command, requested_at}}`. Sets `last_seen_at`, `devices.locked_since` (the boot, from `booted_at_ms`), stores
  the fix (§4.7), **never** writes `device_status`, the policy or the power events. At most one per 20 s per phone
  (429 otherwise);
- `POST /api/devices/locked/answer` - a receipt or a result for one of this phone's Find commands only (the same
  rules as `command-receipt`/`command-result`, through the same functions); another command id is 404;
- registration is on the normal side: `POST /api/devices/locked-token` (device bearer).

### 5.2 The filtered pop

- `pop_command` gains a filter: the locked check pops only `ring`, `stop_ring`, `locate`, `lock`, `wipe`. Every other
  command (`set_kid_pin` - which carries the PIN -, `install_update`, `end_windows`, `start_pause`, `allow_blocked`,
  `leave_tailnet`) stays queued for the unlock; the expiring ones expire as today.
- "Once a command goes out, every older unconfirmed one is never offered again" (QA Low 1) applies **within the Find
  set** for a locked delivery, so a locked `ring` never kills an older waiting `end_windows`.
- Redelivery (2 min, 3 deliveries, 15 minutes) and expiry are unchanged; the redelivery nudge goes to both streams.

### 5.3 Storage

One migration (the next free number): `devices.locked_token_hash`, `locked_token_at`, `locked_since` (the boot the
last locked check named; cleared by the first status report after it). An enrollment clears all three, as it ends
the pending commands today.

### 5.4 The policy and the capability

- `locked_beacon: {enabled}` in the policy (pinned by `policy_json_keys_snapshot` / `PolicyResponseCompatTest`); the
  phone's switch (D6). `enabled` is false for a phone in own mode or with a tailnet address (pointless there).
- Capability `locked_beacon_v1` in the status report: the server offers the switch only to a launcher that has it.

### 5.5 What the parent sees (nb + en)

- **The summary and Locate**, while `locked_since` is set: "Restarted 14:02 · not unlocked yet" / "Startet på nytt
  14:02 · ikke låst opp ennå", and "Seen 2 min ago" is true again. The fix shows as any other.
- **Finn telefonen** (design 88), while locked: one line above the buttons, "The phone is locked since the restart.
  Ring, Update Location, Lock and Erase work; other changes wait until it is unlocked." Erase's confirmation adds: "Its
  data is already locked behind the PIN. Erasing ends Find for this phone."
- **Lock's answer**: "Locked: not unlocked since the restart".
- **Posisjonsinnstillinger** (or Finn telefonen; D6): the switch "Find the phone after a restart, before the PIN".
  For a phone with a tailnet address: "Needs the server's https address" and no switch.
- **The security log**: `locked_token_registered`, and one `locked_beacon_seen` per boot ("restarted, reached the
  server before its unlock"). No push (D7).

## 6. The security trade-off

### 6.1 Who gains what

| Who | Gains from the DE file | Can then |
|---|---|---|
| A finder or thief holding the phone, no exploit | Nothing (DE is encrypted at rest; no adb, debugging is off) | Defeat the beacon as they defeat Find AFU: power off, airplane mode, SIM out |
| An attacker with a BFU file-read exploit (forensic tools read DE BFU on many cheap SoCs [inference]) | The server's address, a sealed blob | Nothing without the TEE on this phone |
| An attacker with BFU code execution as our app or root | The locked token (asks the Keystore to open the blob) | Post false "alive" checks and false fixes (a false trail); take the queued Find commands - confirm a wipe or lock and never run it; learn that one is queued |
| A remote attacker, no phone | Nothing new | A 256-bit token hashed on the server; the new routes rate-limited |

### 6.2 What the narrow token protects, against upstream's full token

With the device token in DE, the third row would also read: the contacts (names, numbers), the zones' coordinates -
the family's home and school -, the parent PIN's hash and salt (a 4-6 digit PIN falls offline in seconds), a
`set_kid_pin` command's Child PIN, the calendar feeds and the kid's events, the SMS policy, the Storytel token route,
the music accounts; and it could post status reports, crash reports and contact requests as the phone. The narrow
token keeps all of that behind the PIN, where it is today.

### 6.3 Is it worth binding to the hardware now?

An attacker with code execution on this phone BFU can already do everything in §6.1's third row on the phone
itself, without carrying the token away; a deep enough exploit likely also brute-forces a short PIN in a weak TEE
and has CE. Binding to the hardware would only stop the use of the token **after** the phone is gone. That is a small
gain for S on each side now, so: the sealed token (D3), and the signed key later with design 60.

### 6.4 What stays true

- The DE file breaks Android's guidance on purpose, for one narrow credential. Written down here, in `security.md`
  and in the PLAN entry.
- The PWA switch off, a new enrollment, or "Remove phone" revokes it at once on the server.
- The beacon never weakens the PIN: it never touches the keyguard, the reset token or CE.

## 7. Erase before the first unlock

### 7.1 Does it work?

- `wipeDevice(0)` from the device owner (security review M8) runs in `system_server`: it writes the recovery command
  and reboots. Nothing in the path needs the user's CE [inference from AOSP's `DevicePolicyManagerService`]. The
  platform does the same BFU itself: `setMaximumFailedPasswordsForWipe` erases from the keyguard, before any unlock.
- The device-owner state and the declared `<wipe-data/>` policy are read from `/data/system` (DE) at boot, so
  `remote_wipe_ready` holds BFU as it did before the restart.
- **[device]**: on an emulator with a screen PIN first (an AVD with a PIN has real BFU; the test AVD today has none,
  `calls.md`), then once on a spare phone. Not on a kid's phone.

### 7.2 Is it worth it?

Little. BFU the data is behind the PIN with CE's keys out of memory, rate-limited in the TEE (design 59 §4.6) -
the state a forensic lab likes least. An erase BFU protects against a later PIN brute force on a weak TEE, and it
leaves a found phone factory-reset (FRP still asks for the Google account). It also ends Find for that phone. It is
in the beacon because it costs nothing once the channel exists, not because it is the reason to build.

### 7.3 Not in scope: resetting a forgotten Child PIN BFU

Design 59 §4.5's hard case (the kid forgets the PIN after a restart) would need `resetPasswordWithToken` BFU, and so
the reset token readable in DE. That turns the BFU gate into one file read. Never.

## 8. Interplay

- **Own mode**: no android marker, no beacon (§4.1). Nothing changes there.
- **The music app**: not involved; not DBA; nothing.
- **The boot cover** (design 59 F3, `:bootcover`): untouched. The beacon starts after it in the same receiver, in
  another process; neither waits for the other.
- **Kiosk, lock task, time rules, the VPN filter**: not started BFU, as today.
- **Call screening BFU**: unchanged; in the main process, which the beacon never runs in.
- **Design 38 (power)**: unchanged. The start record still goes up after the unlock; `locked_since` shows the restart
  at once and is cleared by the first status report after it.
- **Design 76 (pin)**: §4.6.
- **Design 60 (hardware-bound token)**: the signed variant of §4.3 is its first, small user.
- **`CommandAnswers`**: §4.4 step 4.
- **The `upstream-fixes` round** (in build): its fix ordering for indoor fixes is reused by the beacon's fix.
- **A tailnet-addressed phone**: no beacon; the PWA says why (§5.5).
- **Phase D** (the own lock removed): makes this note apply to every phone. The natural time to build is before it.

## 9. The contract and the tests

- **`server/testdata/locked_beacon.json`**, byte-identical in `launcher/app/src/test/resources/`: `capability`
  (`locked_beacon_v1`), `register` (the exact `locked-token` body), `check` (the exact check body, with and without a
  fix), `answers` (none, each of the five commands, 401, 429), `answer` (receipt and result bodies), `policy` (the
  `locked_beacon` rows), `commands` (the Find set). The server's `tests::locked_beacon::the_contract` posts them and
  produces every answer; the launcher's `LockedBeaconTest` encodes and decodes them exactly; both compare the copies.
  Added to the CI path lists of `launcher.yml` and `server-ci.yml` and to the root `CLAUDE.md`'s contract list.
- **Server tests**: the locked token is refused on every normal route and the device token on the locked ones (a
  route table scan); the pop leaves `set_kid_pin`, `end_windows` and the rest queued; the "older ones never again"
  rule stays within the Find set; a check never writes `device_status`; a fix is dropped while location is Off;
  enrollment and the switch clear the hash; 429 after 20 s; the PWA lines in nb and en.
- **Launcher tests**: `beaconStartPlan` (own mode, no marker, no token, tailnet URL, unlocked -> no start); the DE
  codec (strict, versioned, missing = off); a source scan: nothing in `locked/` imports `LauncherPreferences`,
  `TsnetClient`, `SealedPrefs`' device-token fields or any CE path, and the DE file's keys are exactly §4.2's; the
  `ran` merge into `CommandAnswers`; the answers' words; the fix plan per `location` mode.
- **Emulator** (`docs/testing/emulator.md`, a new section): an AVD with a screen PIN, reboot, don't unlock: the
  check arrives, ring and stop, locate (the emulator's GPS), lock's answer, the unlock handover, an erase on a
  throwaway AVD.
- **Jelly Star** [device], the user's check: Wi-Fi and mobile data BFU; which location providers answer BFU indoors
  and out; the ring through a silent time rule; the Keystore key BFU; the battery over 24 hours BFU; an incoming
  blocked call still screened while the beacon runs.

## 10. Size

| Step | Side | Size |
|---|---|---|
| 1. The migration, the locked middleware and routes, the filtered pop, the registration, the policy key, the contract, tests | server | S-M |
| 2. The PWA lines and the switch (nb + en), the security log events | server | S |
| 3. The DE store and its mirror, the token and its registration, the `:locked` service, the receiver hook, the BFU ring/locate/lock/wipe, the handover and merge, tests | launcher | M-L |
| 4. Emulator pass with a PIN AVD; the Jelly Star check | both | S + the user's time |

Server about one round (steps 1-2), launcher about one and a half (step 3), then design QA, code QA and the device
check as usual. Upstream's version was some 400 launcher lines and 40 server lines; ours is likely three times that,
for least privilege, the own process and the tests.

## 11. Risks

- **Location BFU may simply not come indoors** if Play services' providers don't answer before the unlock [device].
  Then the beacon still says "restarted, not unlocked" and rings, but its map pin is old. Check this first: if it
  fails, the value drops by half.
- **A new BFU process.** Mitigated by its own process and the crash guard; still the area of the two past boot
  lockouts. Nothing in it touches HOME, lock task or native code.
- **A credential in DE.** Narrow (§6); revocable; documented.
- **A new public auth path.** Small, rate-limited, structurally separate.
- **Two command consumers.** The filtered pop and the DE `ran` merge must be right, or a command runs twice (ring
  twice - harmless) or is skipped (a lock confirmed BFU and lost - shown as "received" without an answer).
- **The lock-screen notification** BFU might puzzle the kid. One neutral line.

## 12. Verdict

| | |
|---|---|
| **Benefit** | Real but narrow today, wider after phase D. Locate and Ring of a restarted lost phone; "restarted, not unlocked" for the parent. Erase adds little. |
| **Cost** | About 2.5 rounds plus device checks. |
| **Risk** | Moderate and contained: a narrow DE credential, an own BFU process, four small routes. |

**Recommendation: build the narrow beacon (this note's §4-§5), but after today's queue and before phase D ships.**
Not upstream's version (the full token in DE costs too much of what the PIN protects). If the Jelly Star check shows
no fix BFU indoors, reconsider: a beacon that can't locate is mostly "it's on, and it can ring". If the reason is
Erase alone: don't build it.

## 13. Decisions for the user

- **D1 - Build it, and when?** Recommended: yes, the narrow beacon, after the current queue and before phase D.
  Alternatives: now; or not at all (Find keeps waiting for the PIN after a restart; design 38's last position stays
  the parent's only clue).
- **D2 - Which commands BFU?** Recommended: all five (ring, stop, locate, lock, erase) - erase costs nothing extra.
  Alternative: locate and ring only.
- **D3 - The credential.** Recommended: a random token sealed by a plain Keystore key (§4.3), the signed key later
  with design 60. Alternative: the signed key now (+S on each side).
- **D4 - The connection while BFU.** Recommended: the stream as AFU, for as long as the phone is BFU (instant ring;
  the drain of an idle unlocked phone). Alternatives: the stream for 24 hours, then a check every 30 minutes; or only
  the 30-minute checks (a ring may wait half an hour).
- **D5 - Location BFU.** Recommended: follow the phone's location setting (one fix at the restart, the asked ones,
  and the Regularly interval; none when Off). Alternative: only the asked ones.
- **D6 - A switch per phone.** Recommended: yes, on by default for android-mode phones, in Posisjonsinnstillinger.
  Alternative: always on, no switch.
- **D7 - Tell the parent when a phone comes back on locked?** Recommended: no push; the summary's line and the
  security log. Alternative: a push "restarted, waiting for its PIN" (noisy with night-time updates).
