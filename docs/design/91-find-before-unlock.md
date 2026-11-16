# 91 - Find My Device before the first unlock after a reboot (a design note)

**Revision 2, 2026-11-16: the user's D1-D7 and the cost concern; more sources.** Branch `design-91`, merged with
`main` at 60ee2a86b. What changed:

- **Decided as recommended:** D1 (build the narrow beacon after the current queue, before phase D), D2 (all five
  commands), D3 (a Keystore-sealed random token), D6 (a switch per phone, on by default) and D7 (no push on a locked
  restart). The user: "alle dine forslag gir mening for meg".
- **Changed, back to the user:** D4 (the stream for all of BFU) and D5 (a fix at every restart). The user's concern:
  "om det blir mye bråk og databruk etc med design 91 mtp at dette skjer ved hver eneste oppstart, og det er jo bare i
  1 av 1000 oppstarter at du virkelig trenger det". The user then added: maybe the parent should mark the phone lost
  or stolen first, before it gathers GPS and the rest at every boot. So revision 2 has a **lean default** that sends
  nothing on a restart unlocked within two minutes, and a **lost mode** ("Savnet") that the parent turns on and that
  runs the full beacon (§14). They come back as D4r and D5r (§13).
- **New:** every source of "where" and "who" beyond GPS (§15), the finder line and the honest finder (§16), an SMS
  fallback (§17), the other ideas with a yes or no (§18), and what a powered-off phone can do (§19). New decisions
  D8-D19 (§13).
- **Brought up to date with main:** design 76's pins (built), 95's roaming restriction and heartbeat, 98's airplane
  switch, 100's one Vibb notification and 102's `:portal` (§8).
- **Sections changed:** §0, §1.3, §3, §4.4, §4.5, §4.7, §5, §8, §9 and §10-§13. Sections §14-§19 are new; read them
  before §10-§13.

Revision 1 status: design note by the architect, 2026-11-12, on `main` at 17f4aab7, branch `design-91`. A note to
decide on, not a build order. The user, 2026-11-12: "ja lag et designnotat på sletting uten opplåsing, men usikker på
om det er verdt det!"

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

Revision 2 (§14-§19 hold the details):

- **The lean default costs nothing on an ordinary restart.** A phone unlocked within two minutes of getting a network
  sends nothing. After that, while it stays locked: one small check-in (about 4 KB), then one every 30 minutes for 12
  hours, then one every 2 hours. A whole day locked costs about 130 KB and about 0.5 % of the battery. A Ring reaches
  it at its next check-in (30 minutes at most in the first 12 hours), and then within seconds for two hours (§14.1,
  §14.2).
- **Lost mode ("Savnet") is the full beacon, and only the parent turns it on.** It is marked in Finn telefonen and
  reaches the phone through the policy while it is on, or at the next check-in while it is locked. From the moment
  the phone boots it holds the stream (Ring in seconds) and sends every source of "where" it has: GPS, the Wi-Fi it
  joined, the Wi-Fi networks and cells around it, and the public IP. It also sends "who": a new SIM and its operator,
  and its number when readable. With no data it sends an SMS to the parent, and on a new SIM it always does (the SMS's
  sender number is the new SIM's number). It shows a finder line and blocks airplane mode. About 140 KB and about 8 %
  of the battery a day (§14.4).
- **GPS alone is often nothing indoors before the unlock.** On the API 36 Play emulator, fused and network were not
  bound before the unlock. The network the phone came back on, the public IP, and the nearby Wi-Fi and cells, matched
  on our own server against what our phones saw inside the parent's zones, give "on the same Wi-Fi as Hjemme" without
  any third party. OpenCellID offline and BeaconDB opt-in come later (§15).
- **Cost** about four rounds before QA, up from 2.5 (§10). **Recommendation:** the lean beacon with lost mode,
  matching and the SMS fallback (steps 1-4), after the current queue and before phase D. The Jelly Star checks of
  §15.6 come first, because they decide how much "where" there is. A signed SMS command channel goes in its own note
  later (§17.2).

Revision 1 (still true unless revision 2 says otherwise):

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
| Thief turns on airplane mode from the locked quick settings | Silent, unless the parent's "Blokker flymodus" (design 98 §2.4, `DISALLOW_AIRPLANE_MODE`) is on | In lost mode the restriction is on, so airplane mode can't be turned on (§14.4). Otherwise silent |
| Thief powers it on with the SIM in, out of known Wi-Fi | Silent | Reports over mobile data (no SIM PIN set) |
| Thief puts in their own SIM | Silent | Normal mode: the next check-in says "another SIM" and the parent gets a push. Lost mode: the same at once, plus one SMS from the new SIM, so the parent learns its number (§17) |
| Thief's SIM has no data, or data is off | Silent | Lost mode: an SMS with what the phone knows (§17) |
| The kid restarted it and hasn't typed the PIN yet | Silent for minutes | Nothing for 2 minutes, then "Restarted 14:02, not unlocked yet" |

A thief who knows phones defeats it as easily as Find AFU: power off, the SIM out and no known Wi-Fi, or a metal box.
What it helps is the ordinary lost phone, and the thief who doesn't think that far.

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

The target is Android 14-16 (the launcher's `minSdk` is 34 and its `targetSdk` 36). The Jelly Star runs Android 16.
Every rule below is read for those versions. The [device] checks are on the Jelly Star's Android 16, and §15.6 lists
them.

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
| Location | `LocationManager` is a system service; the location switch and our runtime grants are DE. **GPS** works. **NETWORK** and **FUSED** come from Play services' providers on the Jelly Star, which may not answer BFU. **Measured on the API 36 Play emulator (revision 2's brief): fused and network were not bound before the unlock, so GPS only.** Indoors without them a fix may not come at all. A-GPS assistance (SUPL) is the modem's and needs only mobile data | [emulator; inference; device - the most important check] |
| Location from a service (Android 14+) | Background location (`ACCESS_BACKGROUND_LOCATION`, granted) lets a `specialUse` service read location without the `location` service type | [docs] |
| The joined network | The SSID and BSSID come only with `ACCESS_FINE_LOCATION` and the location switch on: `NetworkCapabilities.getTransportInfo()` from a callback registered with `FLAG_INCLUDE_LOCATION_INFO` (Android 12+; `getConnectionInfo` is deprecated). Otherwise `<unknown ssid>` and `02:00:00:00:00:00` | [docs; device] |
| Wi-Fi scan | `startScan` needs fine location, `CHANGE_WIFI_STATE` and the location switch on. A foreground service counts as foreground for the throttle: four scans per two minutes. Only importance above `IMPORTANCE_FOREGROUND_SERVICE` is "background", which is one scan per 30 minutes for all background apps together (`ScanRequestProxy.shouldScanRequestBeThrottledForApp`). `getScanResults` returns the last scan, the system's own included, at no cost | [docs; aosp; device] |
| Cells | `getAllCellInfo` and `requestCellInfoUpdate` need fine location and the location switch on. Telephony runs BFU, because emergency calls do. LTE and NR neighbours usually carry only a PCI and a channel, not a global cell id, so only the serving cell can be looked up | [docs; inference; device] |
| SIM | The SIM loads at boot, because a SIM PIN is asked for BFU. The operator (`getSimOperator`, MCC+MNC, no permission). The ICCID (`SubscriptionInfo.getIccId`) and IMSI: a device owner with `READ_PHONE_STATE` may read non-resettable identifiers ("Restrictions on non-resettable device identifiers"). The line number (`getLine1Number`, `READ_PHONE_NUMBERS`, in our manifest) is often empty | [docs; device] |
| SMS out | `SmsManager.sendTextMessage` (`SEND_SMS`, in our manifest) is a call into the telephony process, which runs BFU. The telephony provider is direct-boot aware, because SMS arrive BFU | [inference; device] |
| The lock-screen line | `setDeviceOwnerLockScreenInfo` is kept by the system (lock settings, DE) and shown in SystemUI's face BFU. In android mode the boot cover occludes that face (design 59d §5), so the cover has to draw the line itself (§16) | [inference; 59d; device] |
| User restrictions | `DISALLOW_AIRPLANE_MODE` and the rest are DPMS state in `/data/system` (DE), so they hold BFU | [inference; device] |
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

Revision 2 adds:

- `grace_s` and `schedule` - the served grace and the fallback cadence (§14.1), used only when no answer comes;
- `lost` - null, or `{kind, since_ms, finder: {mode, text}?, numbers: [..]}` (§14.4). It is written by the unlocked
  app from the policy, and by the beacon from a check's answer. It is cleared only by a policy or an answer that says
  so: the parent's alone;
- `finder` - the finder line when the parent chose "Alltid" (§16), outside lost mode;
- `sims` - `{salt, hashes}`: a random per-phone salt and the SHA-256 of salt + ICCID for each SIM the unlocked phone
  had (§15.4). Never the ICCID itself;
- `next_check_at_ms`, `last_ok_ms`, `sms_count` - the beacon's own bookkeeping for one boot.

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

Revision 2 changes steps 1 and 2 (the lean default and lost mode, §14.1 and §14.4). Steps 3 and 4 stand.

1. **At start**: waits for a validated network (a `ConnectivityManager` callback, not a fixed delay - upstream's
   0d4201dab9 lesson). In **normal mode** it then waits out the grace (2 minutes, §14.1) and stops at once if the
   phone is unlocked meanwhile. Then one **check**: `POST /api/devices/locked/check` with `booted_at_ms` (now -
   `elapsedRealtime`), the battery, the SIM state and the cheap network facts (§14.5), but no GPS fix. In **lost
   mode** (the DE flag, §14.4) there is no grace, and the first check carries everything.
2. **Then, in normal mode, sparse checks, not the stream.** Each check comes from an exact-while-idle alarm to the
   beacon's DBA receiver, which starts the service for one request and stops it. The answer's `next_check_s` sets the
   next alarm. The stream `GET /api/devices/locked/stream` is held only in lost mode, or for the two hours after a
   check delivered a Find command (the hold). While held, every nudge runs a check.
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

**Replaced by §14.1-§14.2 in revision 2** (the lean default, the hold and lost mode, with the numbers). Revision 1's
text, kept for the record:

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

- `off` in the mirror means no fix is taken or sent, and no network facts either: a BSSID, a cell and the joined
  Wi-Fi are location too. The server also drops a locked fix while the phone's location setting is Off (as the status
  intake does), and it doesn't match the public IP. **The one exception is lost mode** (D9): the parent's explicit
  mark turns location on for that phone until the parent ends it, and the PWA says so at the mark (§14.4).
- Revision 2: in normal mode there is no fix at the restart (D5r). A fix comes for a `locate` or a `ring`, and with
  `regularly` after the phone has been locked for 30 minutes, at the served interval. The network facts (the joined
  Wi-Fi, the serving cell) ride on every check when location isn't Off: they cost no GPS.
- If Android's location switch is off, the beacon switches it on for the fix and back after, as the unlocked code
  does for an asked fix; the "I switched it on" mark is in DE, so a crash still restores it at the next start.
- The fix goes in through the same intake as any other (`zones::store_fix`): stored once, kept by the retention,
  judged for zones (design 64) - an arrival home after a restart is a real arrival.

## 5. The server

### 5.1 The routes (phone listener)

Revision 2: the check's body and answer grow (the network facts, the SIM, `lost`, `next_check_s`, `hold_s`; §14.5),
and the server owns the cadence (§14.1). The routes stay these four.

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
- Revision 2, expiry for a locked phone: while `locked_since` is set and the phone isn't holding the stream, `ring`,
  `stop_ring` and `locate` don't expire on the one-hour clock. They expire 15 minutes after the phone's next expected
  check (`devices.locked_next_check_at`, which the server set in its last answer). A sparse check therefore never
  misses a Ring, and a Ring never goes off hours later.

### 5.3 Storage

One migration (the next free number): `devices.locked_token_hash`, `locked_token_at`, `locked_since` (the boot the
last locked check named; cleared by the first status report after it). An enrollment clears all three, as it ends
the pending commands today.

### 5.4 The policy and the capability

- `locked_beacon: {enabled}` in the policy (pinned by `policy_json_keys_snapshot` / `PolicyResponseCompatTest`); the
  phone's switch (D6). `enabled` is false for a phone in own mode or with a tailnet address (pointless there).
- Capability `locked_beacon_v1` in the status report: the server offers the switch only to a launcher that has it.
- Revision 2: the top-level `lost` (§14.5) and `locked_beacon.finder` (§16), and the capability `lost_mode_v1`. Lost
  mode is offered for every phone with the capability, android mode or own mode: AFU it works in both, and BFU only
  android mode has a BFU to speak of.

### 5.5 What the parent sees (nb + en)

- Revision 2: the lines per phase are in §14.3, lost mode's in §14.6, the sources' in §15.5.
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

Revision 2 adds to the third row: the token also reads a lost mark's answer, which holds the finder text and the
parent's number(s) for the finder line and the SMS (§14.5). That is what the lock screen shows a finder anyway. It
can also post false network facts and a false "another SIM" (a false trail, as with fixes). The DE file now also
holds the lost flag, the finder text and those numbers, and salted ICCID hashes (§4.2): nothing a BFU file read
gains much from.

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

What main built since revision 1 (revision 2):

- **Design 76 (the pins), built.** §4.6 stands: the beacon uses `ServerPins`' built-in set and the mirrored
  `pin_enforce`, and never moves the flag. The tailnet recovery address (`server_tls.recovery_url`) is tailnet http,
  so the beacon never uses it.
- **Design 95 (mobile network).** The roaming restriction is DPMS state, so it holds BFU. A phone abroad with
  `abroad` off sends **zero** bytes over mobile data BFU too. The beacon then reaches the server only over Wi-Fi, and
  in lost mode the SMS fallback (§17) is the channel; an SMS while roaming in the EEA costs the home price. The parent's
  "Mobildata av" (`mobile_data`) means Wi-Fi only BFU as well. The "Bare Vibb" sink lives in the VPN, which is down
  BFU, so it neither helps nor hinders. The heartbeat (95-T2) is the status report's; a check is not a report, and the
  first full report after the unlock goes up as today.
- **Design 98 (airplane mode).** The parent's "Blokker flymodus" holds BFU (a user restriction). Lost mode sets the
  same restriction while it lasts (§14.4). The kid's Flymodus route (the assistant role) is not used BFU, and nothing
  in the beacon touches the role.
- **Design 100 (one Vibb notification).** BFU the anchor isn't running, so the beacon's service posts its own plain
  notice. It uses the anchor's MIN channel `launcher:command_listener`, the round mark `ic_stat_vibb` and
  `VISIBILITY_SECRET`, so nothing shows on the lock screen. The sparse checks of normal mode finish within Android's
  ~10 s deferral of a non-IMMEDIATE notice (design 100's M8), so normally none is ever drawn. At the unlock the beacon
  stops before the anchor starts, so there is still one Vibb notification at a time.
- **Design 102 (`:portal`).** A network behind a captive portal never validates, and the sign-in window is not DBA, so
  BFU the beacon doesn't use such a Wi-Fi. It waits for a validated network, mobile data included. `:portal` and
  `:locked` never meet.

## 9. The contract and the tests

- **`server/testdata/locked_beacon.json`**, byte-identical in `launcher/app/src/test/resources/`: `capability`
  (`locked_beacon_v1`), `register` (the exact `locked-token` body), `check` (the exact check body, with and without a
  fix), `answers` (none, each of the five commands, 401, 429), `answer` (receipt and result bodies), `policy` (the
  `locked_beacon` rows), `commands` (the Find set). The server's `tests::locked_beacon::the_contract` posts them and
  produces every answer; the launcher's `LockedBeaconTest` encodes and decodes them exactly; both compare the copies.
  Added to the CI path lists of `launcher.yml` and `server-ci.yml` and to the root `CLAUDE.md`'s contract list.
  Revision 2 adds the rows `check_lost` (a lost check with every fact: network, scan, cells, SIM, fix, `sms_sent`,
  `shutdown`), `answers_lost` (with `lost`, `next_check_s`, `hold_s`, `want_fix`), `policy_lost` (the top-level `lost`:
  none, Mistet, Stjålet, and `locked_beacon.finder`), `schedule` (`{since_boot_s, lost, battery, held} ->
  {next_check_s, fix_every_s}`: the server makes each row and the phone's fallback copy runs every one), `sms` (the
  facts to the SMS text in nb and en, each at most 160 GSM-7 characters), `sim` (the ICCID hashes to `same`, `other`,
  `none`) and `report_network` (the AFU fix's `network`, §15.2). Test cells use MCC 001 / MNC 01 (the test network),
  test BSSIDs the locally administered `02:00:5e:..` range, and test tokens read `test-...-not-real`.
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
- **Jelly Star** [device], the user's check (revision 2: §15.6's list extends this one): Wi-Fi and mobile data BFU;
  which location providers answer BFU indoors and out; the ring through a silent time rule; the Keystore key BFU; the
  battery over 24 hours BFU; an incoming blocked call still screened while the beacon runs.

## 10. Size

Revision 2 (revision 1's table was steps 1-4 below without lost mode, the sources and the SMS: about 2.5 rounds).

| Step | Side | Size |
|---|---|---|
| 1. The lean beacon: revision 1's migration, locked middleware and routes, filtered pop, registration, policy key, DE store, token, `:locked` service, receiver hook, BFU ring/locate/lock/wipe, handover and merge, plus revision 2's grace, sparse alarm checks, hold, server-owned schedule and locked expiry rule (§14.1) | server S-M, launcher M-L | ~2 rounds |
| 2. Lost mode: the `lost` key and capability, the PWA's sheet, banner, events and police card, the pushes, the security log (§14.6); the phone's DE flag, the lost cadence and battery budget, the full facts (scan, cells, joined network, SIM), the airplane restriction and status bar, charging and shutdown checks (§14.4) | server M, launcher M | ~1 round |
| 3. Matching and learning: `zone_networks`, the IP prefixes, the AFU fix's `network`, the matcher and the parent's lines (§15.2, §15.5) | server S-M, launcher S | ~0.5 round |
| 4. The finder line (owner info, the boot cover, the glance, "Ring Mamma") and the SMS fallback (§16, §17) | launcher S-M, server S | ~0.5 round |
| 5. Optional: the OpenCellID import, the BeaconDB lookup (§15.3) | server S each | ~0.5 round |
| 6. Emulator pass with a PIN AVD; the Jelly Star checks (§15.6) | both | S + the user's time |

About four rounds before design QA and code QA for steps 1-4, and half a round more for step 5. Recommended order:
the Jelly Star checks of §15.6 first (an afternoon; they decide how much "where" there is BFU), then steps 1 and 2 as
one build, then 3 and 4, and step 5 only if the parent's own sources fall short.

## 11. Risks

- **Location BFU may simply not come indoors** if Play services' providers don't answer before the unlock. The API 36
  Play emulator already showed GPS only (§3). Revision 2's answer is §15: the joined network, the public IP and the
  nearby Wi-Fi and cells, matched against the zones. A phone far from any zone, indoors, with neither OpenCellID nor
  BeaconDB turned on, still has only "on mobile data, Telia".
- **A new BFU process.** Mitigated by its own process and the crash guard; still the area of the two past boot
  lockouts. Nothing in it touches HOME, lock task or native code. Revision 2 adds an alarm receiver and a network
  `PendingIntent` receiver to `:locked`, both DBA and both one-line handoffs to the service.
- **A credential in DE.** Narrow (§6); revocable; documented. Revision 2 adds the lost flag, the finder text, the
  parent's numbers and salted ICCID hashes (§4.2).
- **A new public auth path.** Small, rate-limited, structurally separate.
- **Two command consumers.** The filtered pop and the DE `ran` merge must be right, or a command runs twice (ring
  twice - harmless) or is skipped (a lock confirmed BFU and lost - shown as "received" without an answer).
- **Lost mode as a tracking tool.** It turns location on even where the parent set it Off (D9), and it gathers more
  than the normal setting. Mitigated: it is a mark visible to every parent, logged in the security log with who set
  it, ended by any parent, and its banner stays on the phone's summary while it lasts.
- **SMS from a SIM that isn't ours.** In lost mode a thief's (or an honest finder's) SIM pays for at most five
  ordinary SMS per boot (§17.1). The texts go only to the parent's numbers. The cost is small, and the purpose is to
  recover the owner's stolen phone. This is not legal advice; the PWA's sheet says plainly that it may happen.
- **Third parties** (step 5 only): OpenCellID needs an account and its token on the server (a secret in the database,
  never in the repo). BeaconDB sees the server's IP and the lost phone's nearby networks. Both are off by default.
- **A status bar left disabled** (§14.4) if lost mode's end never reaches the phone. The unlocked app clears it at the
  first apply whose policy has no `lost`, so a stale flag lasts at most until the next sync.
- **The sources may be blocked BFU** in ways the docs don't say (§15.6). Each source fails alone: the check goes up
  without it.
- **The lock-screen notification** BFU might puzzle the kid. Revision 2: it is `VISIBILITY_SECRET` and normally never
  drawn in normal mode (§8).

## 12. Verdict

| | |
|---|---|
| **Benefit** | Real but narrow in normal mode: "restarted, not unlocked" for the parent, Ring and Locate within 30 minutes. Wide in lost mode: a lost or stolen phone that is switched on reports where it is, from more than GPS, who has put a SIM in it, and what its number is, even with no data. Wider still after phase D. Erase adds little. |
| **Cost** | About four rounds plus the device checks (step 5 half a round more). On the phone: nothing for a restart unlocked within 2 minutes, ~130 KB and ~0.5 % of the battery for a whole day locked, ~140 KB and ~8 % a day in lost mode. |
| **Risk** | Moderate and contained: a narrow DE credential, an own BFU process, four small routes, and a lost mode that is the parent's visible, logged choice. |

**Recommendation: build steps 1-4 after today's queue and before phase D ships, starting with the Jelly Star checks of
§15.6.** Lost mode is what makes the beacon worth its cost: the default stays almost silent, and the full beacon runs
only when a parent says the phone is gone. Step 5 (OpenCellID, BeaconDB) only if the own sources fall short in use.
The signed SMS command channel gets its own note later (§17.2). If the Jelly Star check shows no GPS fix, no joined
Wi-Fi and no cells BFU, lost mode is still worth it for the SIM, the SMS and "it is on". If the reason is Erase alone:
don't build it.

## 13. Decisions for the user

Decided on 2026-11-16 as recommended (the user: "alle dine forslag gir mening for meg"):

- **D1 - Build it, and when?** Yes, the narrow beacon, after the current queue and before phase D.
- **D2 - Which commands BFU?** All five: ring, stop, locate, lock, erase.
- **D3 - The credential.** A random token sealed by a plain Keystore key (§4.3), the signed key later with design 60.
- **D6 - A switch per phone.** Yes, on by default for android-mode phones, in Posisjonsinnstillinger. (Lost mode is
  its own mark, D8: with the switch off the phone has no BFU beacon, and lost mode works only after the unlock.)
- **D7 - Push when a phone comes back on locked?** No push for an ordinary restart; the summary's line and the
  security log. (D16 adds pushes for lost mode and a SIM change, which are not ordinary restarts.)

Changed by revision 2, back to the user:

- **D4r - The connection while BFU (replaces D4's "the stream for all of BFU").** Recommended: the lean default of
  §14.1. Nothing for 2 minutes after the network. Then one check, then a check every 30 minutes for 12 hours after the
  boot, then every 2 hours, as short bursts with no notification. The stream is held only for 2 hours after a
  delivered Find command, and for as long as lost mode lasts. Numbers in §14.2. Alternatives: D4 as decided (the
  stream throughout: Ring in seconds always, but 360 radio wakes a day, ~5 % of the battery a day on LTE, for the 999
  restarts in 1000 that don't need it); or no checks at all outside lost mode (cheapest, but a parent who hasn't
  marked the phone lost learns nothing, and a mark made while the phone is locked never reaches it).
- **D5r - Location BFU outside lost mode (replaces D5's "one fix at the restart").** Recommended: no GPS fix at the
  restart. The cheap network facts (the joined Wi-Fi and the serving cell) ride on every check while location isn't
  Off. A fix comes for a `locate` or a `ring`, and with Regularly only after 30 minutes locked, at the served interval.
  Alternative: D5 as decided (a GPS fix at every restart: up to a minute of GPS per boot, mostly for phones about to be
  unlocked).

New:

- **D8 - Lost mode: its name, place and what it turns on.** Recommended: **"Savnet" / "Lost"**, marked from Locate ›
  Finn telefonen ("Meld savnet" / "Report lost"), with the kind Mistet / Stjålet (Lost / Stolen). It turns on the
  stream, every source of §15 at lost mode's cadence and battery budget, the SIM report, the SMS fallback (D17), the
  airplane restriction and, if the check passes, the hidden status bar (D18). For Mistet it also shows the finder
  line and "Ring Mamma" (D15). Quiet: no sound unless the parent rings, and no notice on the lock screen. Ended only
  by a parent. Alternative: no mode, everything always (the cost the user didn't want), or one kind only (simpler,
  but a finder line on a stolen phone may make the thief switch it off sooner).
- **D9 - Lost mode turns location on even where the parent set it Off.** Recommended: yes. It is said at the mark,
  logged, and lasts until the mark ends. Alternative: lost mode follows the setting (then a phone with location Off
  reports only "on" and its SIM).
- **D10 - Match the joined Wi-Fi and the public IP against the zones.** Recommended: yes (§15.2). Free, no third
  party, follows the location setting.
- **D11 - Learn the networks inside zones.** Recommended: yes. The AFU fix carries its network facts, and the server
  keeps only those of fixes inside a zone (40 BSSIDs, 10 cells and 4 IPs per zone, pruned after 90 days unseen).
  Alternative: learn from every fix (better coverage, but a network map of the kid's whole life on the server).
- **D12 - OpenCellID offline.** Recommended: yes, but as the optional step 5, off by default. The parent's own token,
  per-country files, refreshed monthly, credit shown (§15.3). Alternative: never.
- **D13 - BeaconDB.** Recommended: opt-in, off by default, server-side only, and only for a lost phone's check with
  no GPS fix and no learned match (§15.3). Alternative: never; or for normal checks too (more queries to a third party
  for little gain).
- **D14 - "Who" from the SIM.** Recommended: the phone compares salted ICCID hashes itself and sends only `same`,
  `other` or `none`, the operator and the line number when readable. The raw ICCID and IMSI never leave the phone. The
  phone's own IMEI is reported once while unlocked, for the police card (§15.4). Alternative: send the raw ICCID and
  IMSI (more for the police, but a stranger's subscriber identity on the family's server).
- **D15 - The finder line.** Recommended: a PWA choice of Av / Bare når meldt savnet / Alltid (Off / Only when
  reported lost / Always), default "Bare når meldt savnet". It is shown on the boot cover, the lock glance and
  Android's own lock screen, and in Mistet a "Ring Mamma" button on the boot cover if the device check passes (§16).
  The user's "only after a Lock command" maps to "when reported lost": Lock does nothing more BFU, and the lost mark is
  the parent's signal. Alternative: always by default.
- **D16 - Pushes beyond D7.** Recommended: yes, three. A lost phone is on again; a SIM change in any mode; a lost
  phone was unlocked (with "Avslutt savnet-modus"). Alternative: none (only the summary and the events).
- **D17 - The SMS fallback out.** Recommended: yes, lost mode only. One SMS at a new SIM; otherwise only after 15
  minutes with no server and the SIM in service, then at most one every 6 hours; at most five per boot (§17.1).
  Alternative: no SMS.
- **D18 - Lost mode hardening.** Recommended: block airplane mode (`DISALLOW_AIRPLANE_MODE`) while lost, yes. Hide the
  status bar on the lock screen (`setStatusBarDisabled`), yes if the Jelly Star check shows it works over a secure
  keyguard. Say honestly that power-off can't be blocked (§18).
- **D19 - The other ideas (§18).** Recommended: charging reports yes; the last check at shutdown yes; the honest
  finder's "Ring Mamma" yes (D15); front-camera photos on wrong PINs **no**; nearby Bluetooth names **no**; the signed
  command SMS **later, in its own note** (§17.2).

## 14. The lean default and lost mode (revision 2)

### 14.1 The lean default (not marked lost)

| Phase | When | What runs | A Ring reaches it |
|---|---|---|---|
| No network | from the boot until a validated network | nothing | not yet: it waits in the queue (§5.2's locked expiry) |
| Grace | the first 2 minutes after the first validated network | nothing: no request, no fix, no scan. An unlock in this time ends it all, and the restart has cost **0 bytes** | at the first check |
| First check | 2 minutes after the network, still BFU | one `locked/check`: the restart, the battery, the SIM state, and the cheap network facts (the joined Wi-Fi and the serving cell, while location isn't Off). It answers the waiting Find command and whether the phone is lost | - |
| Quiet | until the unlock | a check every 30 minutes up to 12 hours after the boot, then every 2 hours. Each is a short burst: an exact-while-idle alarm, the DBA receiver, the service for one request, then stop | within 30 minutes (2 hours after the first 12) |
| Hold | 2 hours after a check that delivered a Find command | the stream as AFU, so the next Ring is instant | seconds |

- **The server owns the cadence.** Every answer carries `next_check_s` and `hold_s`. The phone clamps them to 5
  minutes-4 hours, and keeps the schedule above in DE (`schedule`) only for when no answer comes. A check that fails
  backs off 1, 2, 4 and then 8 minutes, then goes back to the schedule.
- **A network that comes back** after none (a found phone in a drawer gets Wi-Fi, a SIM goes in) wakes the beacon:
  `ConnectivityManager.registerNetworkCallback(request, PendingIntent)` delivers to a manifest receiver even when the
  process is gone [docs]. It runs a check at most once per 10 minutes, and only when the last check failed or the
  network changed.
- **Why 2 minutes.** A kid who restarted the phone types the PIN within a minute. A night-time update's restart and
  a lost phone stay locked for hours. A thief who switches the phone on and off within 2 minutes is missed in normal
  mode; lost mode has no grace. The value is served (`grace_s`, 0-10 minutes), so it can be tuned without a release.
- **Why no stream outside the hold.** Bytes are not the cost: a held stream is ~2 KB an hour. Radio wake-ups are.
  A keepalive every 240 s is 360 wake-ups a day. 30-minute checks are 24 in the first 12 hours, then 12 a day.
- **No notification is seen.** Each burst finishes within Android's ~10 s deferral of a non-IMMEDIATE notice (design
  100 M8). In a hold, the notice is `VISIBILITY_SECRET` on the MIN channel, so nothing shows on the lock screen (§8).

### 14.2 The numbers

Assumptions. A check in normal mode is a new TCP and full TLS 1.3 connection: ~3.6 KB with Caddy's ECDSA chain
(design 95 §3.3). A process that dies between checks keeps no session ticket, and none is put in DE. Add ~0.5 KB of
request and answer: **~4 KB per check**. A keepalive is ~140 B on the wire (95 §3.3). In lost mode the checks ride
the held HTTP/2 connection (OkHttp shares one connection per host) [inference]: ~1.7 KB each with the full facts. The
battery side, all estimates until the Jelly Star check measures them: a radio wake-up on LTE ~0.3 mAh (connected
state plus its ~10 s tail; far less on Wi-Fi), a GPS attempt of up to 60 s ~0.8 mAh, a Wi-Fi scan ~0.05 mAh. The
Jelly Star's battery is 2000 mAh.

| Case | Bytes | Battery |
|---|---|---|
| A restart unlocked within 2 minutes (most restarts) | **0** | **0** |
| Unlocked within 30 minutes | 1 check, ~4 KB | ~0.3 mAh |
| A night-time update at 03:00, unlocked at 07:00 | 8 checks, ~35 KB | ~2.5 mAh (0.1 %) |
| A whole day locked (the first 24 hours) | 30 checks, ~130 KB | ~9 mAh (0.5 %) |
| Each further day locked | 12 checks, ~50 KB | ~4 mAh (0.2 %) |
| A hold (2 hours) | ~4 KB to open, ~2 KB an hour | ~4.5 mAh an hour (0.2 %) |
| Lost mode, the first hour after the boot | ~16 KB (the stream, 6 full checks) | ~11 mAh (0.55 %): 6 GPS attempts, 6 scans, the wake-ups |
| Lost mode, each further hour | ~5.5 KB | ~7 mAh (0.35 %) |
| Lost mode, a whole day | ~140 KB | ~165 mAh (~8 %) |
| One SMS (lost mode, §17) | ~140 B, billed as one SMS | negligible |

For scale: an unlocked phone's server line today is about 1.4 MB a day (design 95 §3.4). A locked phone's own idle
drain is likely 0.5-1 % an hour [unverified]. That drain, not the beacon, decides how long a lost phone lasts.

### 14.3 What the parent sees, per phase (nb / en)

| Phase | The phone's summary and Finn telefonen |
|---|---|
| Grace, or no network | Unchanged: design 38's "Av siden 21:14 · batteri 2 %" / "Off since 21:14 · battery 2 %", or the last "Sett" |
| After a check | "Startet på nytt 14:02 · ikke låst opp ennå" / "Restarted 14:02 · not unlocked yet", and "Sjekker inn hver halvtime mens den er låst · neste innen 14:34" / "Checks in every half hour while locked · next by 14:34" (after 12 hours: "hver annen time" / "every two hours") |
| Where, when known | A source line from §15.5, such as "På Wi-Fi-en til Hjemme" / "On Home's Wi-Fi" |
| A Find button pressed while quiet | Under the button: "Når telefonen ved neste innsjekk, innen 14:34" / "Reaches the phone at its next check-in, by 14:34" |
| Hold | "Tilkoblet nå · låst siden omstarten" / "Connected now · locked since the restart" |
| The unlock | The start record as today; the locked lines go |

### 14.4 Lost mode ("Savnet")

**How the phone knows.**

- **Marked while the phone is on and unlocked:** the policy's top-level `lost` carries it. The launcher writes the DE
  flag at once (`commit()`) and applies lost mode's unlocked parts: the finder line, the airplane restriction, and the
  location interval. The next boot starts lost mode BFU without asking anyone.
- **Marked while the phone is off or locked:** the next check's answer carries `lost`. The beacon writes the DE flag
  and switches at once. That check is the lean default's: one per boot after the grace, then every 30 minutes, so the
  mark waits at most 30 minutes (2 hours after the first 12). Without a push channel (FCM went with design 19, and
  Play services isn't up BFU anyway) it can't be faster.
- **Clearing is the parent's alone.** "Avslutt savnet-modus" / "End lost mode" sets `lost: null`. The policy or the
  next answer carries it, and the phone clears the flag, the finder line, the restriction and the status bar. An
  unlock never clears it. A lost phone that is unlocked sends the parent a push (D16) with the end button. "Remove
  phone" and a new enrolment clear it too.

**What it does, from the moment the beacon comes up.**

- No grace. The first check goes at the first validated network, with everything (§14.5). A first GPS attempt starts
  at once (up to 120 s, since the first fix after a boot has no assistance data).
- The stream is held throughout, so Ring, Locate, Lock and Erase come in seconds.
- **Where:** GPS, a Wi-Fi scan, the cells, the joined Wi-Fi, and the public IP on the server (§15). A check with a
  fix and a scan every **10 minutes for the first hour** (after the boot, or after the mark arrives), then every **30
  minutes**. Each GPS attempt stops at 60 s. A phone lying still (the same facts, a fix younger than 30 minutes)
  skips the GPS. Also a check at once on a SIM change, at plug-in, and on a new network.
- **Who:** the SIM state at every check (§15.4). A new SIM sends its operator and its number when readable, and one SMS
  (§17), whose sender number is the new SIM's.
- **Charging:** `ACTION_POWER_CONNECTED` (a runtime receiver while the service lives) runs a check: "Lades siden
  14:20" / "Charging since 14:20". Somebody has it plugged in.
- **The last gasp:** at `ACTION_SHUTDOWN` the service posts one last check with `shutdown: true` and the battery, as
  design 38 does unlocked: "Slått av 15:40 · batteri 3 %" / "Switched off 15:40 · battery 3 %".
- **The battery budget:** below 20 % a fix at most every 60 minutes; below 10 % no GPS, network facts every 60
  minutes, the stream kept. Lost mode stays within ~0.5 % an hour (§14.2).
- **Hardening:** `DISALLOW_AIRPLANE_MODE` while lost, set by the launcher from the policy and by the beacon from an
  answer, and cleared at the end unless the parent's "Blokker flymodus" holds it (design 98 §2.4). The kid's Flymodus
  row reads "Bestemt av mamma og pappa" meanwhile, as with the switch. `setStatusBarDisabled(true)` while lost hides
  the quick settings on the lock screen [unverified over a secure keyguard; §15.6]. It is cleared at the end, and by
  the unlocked app's first apply without `lost`.
- **Quiet:** no sound unless a parent rings. No notice on the lock screen (`VISIBILITY_SECRET`, §8). The boot cover
  looks as always. In Stjålet no finder line by default, so nothing tells a thief the phone is being watched.
- **Unlocked while lost** (the kid found it, or someone knows the PIN): the normal app runs, plus lost mode's unlocked
  parts. The server serves a 10-minute location interval while lost, over the parent's chosen interval and design 64's
  served one for that phone. The status report carries the SIM and the network as usual.

**The two kinds.**

| | Mistet / Lost | Stjålet / Stolen |
|---|---|---|
| The finder line (§16) | On, with the chosen number | Off by default; the parent may turn it on |
| "Ring Mamma" on the boot cover (§16) | On, if the device check passes | Off |
| Stream, sources, SIM, SMS fallback, airplane block | Yes | Yes |
| The police card (§14.6) | Shown | Shown first |

### 14.5 The wire

The check body (every key optional but `booted_at_ms` and `mode`; in normal mode no `scan` and no `location` unless
asked; nothing under `network` when location is Off and the phone isn't lost):

```json
{
  "booted_at_ms": 1763290920000,
  "mode": "lost",
  "battery": {"level": 54, "charging": true, "plugged": "usb"},
  "sim": {"state": "other", "operator": {"mcc": "001", "mnc": "01", "name": "Test"}, "number": null},
  "network": {
    "type": "wifi",
    "wifi": {"ssid": "test-net", "bssid": "02:00:5e:00:00:01"},
    "cells": [{"radio": "lte", "mcc": "001", "mnc": "01", "area": 1, "cell": 1001, "serving": true, "dbm": -95}],
    "scan": [{"bssid": "02:00:5e:00:00:02", "dbm": -61}]
  },
  "location": {"lat": 0.0, "lon": 0.0, "accuracy_m": 35, "at_ms": 1763290980000, "provider": "gps"},
  "sms_sent": [{"at_ms": 1763291000000, "reason": "new_sim"}],
  "shutdown": false
}
```

The answer: `{"command": {...}?, "lost": {"kind": "lost" | "stolen", "since_ms", "finder": {"name", "number"}?,
"numbers": [..], "airplane_block": true} | null, "next_check_s", "hold_s", "want_fix"}`.

The scan is cut to the 12 strongest BSSIDs. Hidden networks and BSSIDs with the "_nomap" SSID suffix are dropped on
the phone (the opt-out convention Google and MLS honour). The policy (AFU) gets the top-level `lost` (the same shape)
and `locked_beacon.finder: {mode: "off" | "lost" | "always", name, number}` and `grace_s`. The phone formats the
finder line and the SMS from `name` and `number` with its own strings, in its own language. All of it is pinned by
`locked_beacon.json` (§9), `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

The server: `devices.lost_kind`, `lost_since`, `lost_by`, `lost_numbers`, `locked_next_check_at`; `device_events` rows
for the events list; `zone_networks` (§15.2). The SIM state, the network facts and the SMS reports go into the events
list, which is kept as long as lost mode lasts plus the retention.

### 14.6 The PWA (nb / en)

- **The place:** Locate › Finn telefonen (design 88), a fifth row under Ring, Oppdater posisjon, Lås and Slett: "Meld
  savnet" / "Report lost".
- **The sheet:** "Mistet" / "Lost" or "Stjålet" / "Stolen". "Nummer for finneren og SMS" / "Number for the finder and
  the SMS": up to two, picked from the kid's contacts or typed. "Vis en melding på låseskjermen" / "Show a message on
  the lock screen" (on for Mistet, off for Stjålet). The note: "Posisjonen slås på til du avslutter. Telefonen sender
  posisjon, nettverk og SIM-kort hver gang den er på, og en SMS hvis den ikke har data. Et nytt SIM-kort sender én
  SMS til deg." / "Location is turned on until you end this. The phone sends its position, networks and SIM card
  whenever it is on, and an SMS when it has no data. A new SIM card sends you one SMS." The save keeps the scroll
  position (the PWA's save rule).
- **The banner** on the summary and in Finn telefonen: "Meldt savnet siden 14:10 · tilkoblet nå" / "Reported lost
  since 14:10 · connected now"; while waiting, "· når telefonen ved neste innsjekk, innen 14:32" / "· reaches the
  phone at its next check-in, by 14:32", or "· ikke vært på siden 21:14" / "· not on since 21:14".
- **Hendelser / Events:** restarts, SIM changes, networks, places, charging, SMS sent, unlocks, switch-offs with the
  battery, newest first.
- **Til politiet / For the police:** the model, the IMEI (§15.4), the phone's own number, the SIM changes with
  operator and number, and the last positions with their times, as text to copy.
- **"Avslutt savnet-modus" / "End lost mode"**, for any parent.
- **The security log:** `lost_mode_on` and `lost_mode_off` (with who), `sim_changed`, `lost_sms_sent`.

## 15. Where and who: the sources (revision 2)

### 15.1 GPS

As revision 1, and the weakest BFU source indoors (§3). The first fix after a boot has no assistance data on the
phone, but the modem's A-GPS (SUPL) works over mobile data BFU [inference]. Outdoors a fix in 30-60 s is likely;
indoors often none.

### 15.2 The network it came back on, and the public IP

- **BFU:** saved Wi-Fi joins BFU (§3) [device]. Its SSID and BSSID are readable with fine location and the switch on.
  The public IP needs nothing from the phone: the server reads it from the request (`net::NetConfig::client`, with
  `TRUSTED_PROXIES`).
- **The public IP is matched only for a check over Wi-Fi.** On mobile data it is an operator's shared (CGNAT) address
  and says nothing. IPv4 exact, IPv6 by its /64. Private, CGNAT (100.64/10) and proxy addresses are never stored.
- **Learning, while unlocked (D11).** The AFU fix (the status report's location) gets `network`: the joined SSID and
  BSSID, the serving cell, and up to 12 BSSIDs from `getScanResults()`, the system's last scan, at no cost. The server
  keeps them only when the fix lies inside a zone with accuracy of 100 m or better, as `zone_networks (zone_id, kind,
  value, first_seen, last_seen, count)`. Everything else is dropped at the intake. The public IP of a status report
  sent over Wi-Fi while the latest fix is inside a zone is kept the same way. Caps: 40 BSSIDs, 10 cells and 4 IPs per
  zone, the least recently seen goes. Rows unseen for 90 days are pruned, and a zone's delete deletes its rows. With
  location Off the phone sends no `network` at all.
- **Matching, BFU:** the joined BSSID in a zone's set; two or more scan BSSIDs in a zone's set; the serving cell in a
  zone's set (a cell covers a kilometre or more, so it is the weakest); the IP. The lines are in §15.5.
- **Privacy:** neighbours' BSSIDs and the family's own internet lines, stored on the family's own server next to zone
  coordinates it already has. Nothing leaves the server. Low.
- **Recommendation:** yes (D10, D11). It is the source most likely to say something indoors, at home, at school and
  at a friend's: the places a phone is most often lost.

### 15.3 The nearby Wi-Fi and cells, and how they are looked up

- **BFU:** a scan and the cells need fine location and the switch on (§3). In lost mode the beacon turns the switch on
  if it is off, as the unlocked code does for an asked fix (§4.7, the DE mark restores it). The service is foreground,
  so four scans per two minutes are allowed. Normal mode sends no scan unless a `locate` asks.
- **The ladder:** (1) the zones' learned sets (§15.2), always and free; (2) OpenCellID offline for the serving cell
  (D12); (3) BeaconDB for the scan and the cells, in lost mode, opt-in (D13); (4) GPS whenever it comes. The best
  answer is shown with its source and radius.
- **OpenCellID** [docs: opencellid.org's downloads page; Wikipedia]: owned by Unwired Labs, licensed CC BY-SA 4.0.
  "Products or services using data derived from OpenCelliD need to visibly credit OpenCelliD." The download needs a
  free account's API token. There are per-country exports, a worldwide file and daily changes, and the exports hold
  the last 18 months of observations. The worldwide file has been cited as ~105 MB gzipped (an unofficial source).
  Norway's file (MCC 242) is likely a few MB [unverified: download it once and measure]. The design: Innstillinger ›
  Posisjon › "Mobilmaster offline (OpenCelliD)". The parent pastes their own token, which is stored in the database as
  a secret, shown masked and never logged. The countries default to the phones' MCCs (design 83's `sim_operators`). The
  server fetches the files monthly in the background, as it fetches MET's weather. That is the server's own job, not a
  host action from the PWA. It imports them into `ocid_cells` and shows "© OpenCelliD (CC BY-SA 4.0)" wherever a
  cell's position is used. No third party is asked at lookup time, and nothing about the kid leaves the server. The
  repo holds no OpenCellID data: the tests use made-up cells under MCC 001 and a token `test-ocid-token-not-real`.
- **BeaconDB** [beacondb.net, read 2026-11-16]: the open successor of Mozilla Location Service (shut down in 2024).
  "A public domain wireless geolocation database" of ~140 M Wi-Fi networks and ~6.8 M cells; experimental, "may be
  inaccurate". `POST https://api.beacondb.net/v1/geolocate`, Ichnaea-compatible, no key; it asks clients to send a
  User-Agent. Its privacy notice: server logs (IP, time, URL, User-Agent) kept 28 days, and the request body (the
  BSSIDs asked about, the answer) not recorded. No data dumps yet. Its coverage in Norway [unverified: one query from
  the Jelly Star's home scan]. The design: a server switch "Spør beaconDB (tredjepart)" / "Ask beaconDB (third
  party)", off by default. It is asked only by the server, never the phone, only for a lost phone's check with no GPS
  fix and no learned match, and once per changed set of facts. The User-Agent is a generic `handy-server`.
- **Privacy:** a scan is a location. Learned sets and OpenCellID keep it on the server. BeaconDB sends it to a third
  party, which is why it is opt-in and for lost phones only.

### 15.4 Who: the SIM, the battery, the IMEI

- **The SIM.** The unlocked app writes to DE the salted SHA-256 of each SIM's ICCID it has seen (§4.2). A device owner
  with `READ_PHONE_STATE` may read it [docs]. BFU the beacon reads the SIM again and sends only `same`, `other`,
  `none` or `unknown` (no hashes yet), the operator (MCC, MNC and the name) and the line number when readable. The
  number (`getLine1Number`, `READ_PHONE_NUMBERS` in our manifest, which a device owner grants itself) is often empty,
  because operators rarely write it to the SIM [unverified on a Norwegian SIM]. The SMS of §17 is the sure way.
- **Raw ICCID and IMSI: possible, not sent (D14).** For a thief's SIM they are a stranger's subscriber identity. The
  operator, the number and the phone's own IMEI are enough for a police report: the police get the rest from the
  operator.
- **In normal mode too.** The SIM state rides on every check at no cost, so "another SIM" reaches the parent (D16's
  push) even when nobody has marked the phone lost.
- **The battery and charging:** `level`, `charging` and `plugged` on every check (§14.4).
- **The IMEI** (`getImei`, a device owner with `READ_PHONE_STATE` [docs]) is reported once by the unlocked app, for
  the police card only. It is the phone's own identifier, not a person's.

### 15.5 What the parent sees, per source (nb / en)

| Source | Line |
|---|---|
| The joined BSSID in a zone | "På Wi-Fi-en til Hjemme" / "On Home's Wi-Fi" |
| The same SSID, another BSSID | "På et Wi-Fi med samme navn som hos Hjemme" / "On a Wi-Fi named like Home's" |
| Scan BSSIDs in a zone | "Nær Hjemme (3 av Wi-Fi-nettene der)" / "Near Home (3 of its Wi-Fi networks)" |
| The IP | "Samme internettlinje som Hjemme" / "Same internet line as Home" |
| The serving cell in a zone | "Samme mobilmast som ved Skolen" / "Same cell tower as at School" |
| OpenCellID | "Mobilmast ca. 1 km unna dette punktet (OpenCelliD)" / "Cell tower about 1 km from this point (OpenCelliD)", a circle on the map |
| BeaconDB | "Omtrent her, ±80 m (beaconDB)" / "About here, ±80 m (beaconDB)", a circle |
| GPS | As any fix |
| Nothing | "På mobildata (Telia) · ingen posisjon" / "On mobile data (Telia) · no position" |
| A new SIM | "Nytt SIM-kort: Telia, 4x xx xx xx" / "New SIM card: Telia, 4x xx xx xx", or "nummer ukjent" / "number unknown" |

### 15.6 The Jelly Star checks (Android 16) that settle the [unverified] points

One afternoon with the debug build, a screen PIN and a reboot, not unlocked:

1. Which location providers answer BFU (`dumpsys location`), and the time to a GPS fix indoors and out.
2. That a Wi-Fi saved through Settings joins BFU, and that mobile data comes up.
3. That the joined SSID/BSSID, `getScanResults`, `startScan` (from the foreground service) and `getAllCellInfo` answer
   BFU with the switch on, and that `setLocationEnabled` works BFU.
4. That `SubscriptionInfo.getIccId` answers BFU, and what `getLine1Number` returns with the user's SIM.
5. That `SmsManager.sendTextMessage` from `:locked` sends BFU, and where the sent SMS shows after the unlock.
6. That the owner-info line shows on SystemUI's lock screen BFU and AFU, whether two lines show, and the boot cover's
   own drawing of it.
7. That `setStatusBarDisabled(true)` takes over a secure keyguard, BFU and AFU.
8. That `DISALLOW_AIRPLANE_MODE` holds at the keyguard's quick settings BFU.
9. That a non-emergency outgoing call (`TelecomManager.placeCall`, our dialer role) is allowed BFU from the boot
   cover (§16).
10. That the exact-while-idle alarm and the `PendingIntent` network callback reach `:locked` BFU in Doze.
11. That a `VISIBILITY_SECRET` notice on the MIN channel stays off the lock screen.
12. That `ACTION_SHUTDOWN` reaches the `:locked` service's runtime receiver.
13. The battery over 24 hours BFU in normal mode and in lost mode, against an idle locked phone without the beacon.
14. Whether Settings has a scheduled power on/off, or a "require unlock to power off" (§19).

## 16. The finder line, and the honest finder (revision 2)

- **The text:** in lost mode (Mistet) "Denne telefonen er meldt savnet. Ring Mamma: <nummer>" / "This phone is
  reported lost. Please call Mamma: <number>". With "Alltid": "Funnet denne telefonen? Ring Mamma: <nummer>" /
  "Found this phone? Please call Mamma: <number>". The name is the parent's display name (design 62), and the
  number is the one chosen in the sheet.
- **Where it shows.** Android's own lock screen (`setDeviceOwnerLockScreenInfo`, which `AndroidLock` already writes
  with the restart line). In android mode BFU the boot cover hides that face (design 59d §5), so the cover draws the
  line itself under the mark, read from DE. Unlocked-and-locked, the lock glance (design 59b) or the own lock shows
  it. The restart line and the finder line share Android's one owner-info slot: in lost mode the finder line goes
  first and the restart line second [device: whether two lines show; §15.6 item 6].
- **Always, or only after Lock (the user's question).** Recommended: a choice of Av / Bare når meldt savnet / Alltid,
  default "Bare når meldt savnet" (D15). "Only after a Lock command" doesn't fit, because Lock BFU only turns the
  screen off: the lost mark is the parent's signal. "Alltid" is right for a parent who wants the line even on a phone
  that never reaches a network (no data, unknown Wi-Fi), which no mark can reach. Its cost is small: a parent's number
  that classmates see.
- **"Ring Mamma" for the honest finder.** A finder can't call anyone but emergency numbers from a locked phone. In
  Mistet the boot cover shows one button, "Ring Mamma" / "Call Mamma". It places a call to the fixed number from DE
  through our dialer role. There is no keypad and no other number, and the call screening is unchanged. Whether
  Telecom allows a non-emergency outgoing call BFU is [unverified; §15.6 item 9]. If not, the line alone stays.
  Unlocked, the lock glance gets the same button. The worst case of abuse is someone calling Mamma.

## 17. The SMS fallback (revision 2)

### 17.1 Out: the phone tells the parent by SMS

- **When (lost mode only, D17):** (a) at once on a new SIM (`sim: other`), even with data, because the SMS's sender
  number is the new SIM's number, which `getLine1Number` rarely gives; (b) after 15 minutes with no server reached
  while the SIM is in service (no data on that SIM, data off, roaming blocked abroad by design 95), then at most one
  every 6 hours. At most five per boot, counted in DE. Each is reported at the next check (`sms_sent`) and shown in
  Events.
- **To whom:** the lost mark's numbers (up to two), mirrored in DE with the flag.
- **The text,** at most 160 GSM-7 characters (æ, ø and å are in GSM-7): "Vibb: Telefonen som er meldt savnet er på
  (14:02). Nytt SIM: Telia. Posisjon 59.9123,10.7501 ±40 m 14:05. Batteri 54 %." / "Vibb: The phone reported lost is
  on (14:02). New SIM: Telia. Position 59.9123,10.7501 ±40 m 14:05. Battery 54 %." Without a fix: "Wi-Fi:
  <SSID>" or "Mobilmast <MCC>-<MNC>-<area>-<cell>", which the parent can paste into the PWA's search. Zone names
  aren't in DE, so the SMS never says "Hjemme".
- **Can it be done BFU:** `SEND_SMS` is in our manifest, and a device owner grants itself the runtime permission.
  `SmsManager` calls the telephony process, which runs BFU, and the telephony provider is direct-boot aware
  [inference; §15.6 item 5]. Vibb needn't hold the SMS role to send. Android's SMS rate check only warns above many
  messages in 30 minutes, or for premium short codes, which a parent's mobile number is not [inference].
- **The cost to the SIM owner:** one ordinary SMS each, usually inside the plan; at home price roaming in the EEA, a
  few kroner outside [unverified: operator]. At most about five per boot. It goes only to the parent, never to the
  thief's contacts.

### 17.2 In: a signed command SMS from the parent (recommended later, its own note)

- **The idea:** with no data, the parent sends an SMS such as "VIBB RING 7Q4K-9XZ2-M3" and the phone rings, or answers
  with its position by SMS.
- **Spoofing:** an SMS sender can be forged, so the sender check is worthless alone. The code must be a MAC: an
  HMAC-SHA256 under a per-phone key (sealed in DE as the token is), over the command and a counter, cut to 60 bits.
  The PWA makes the exact text for the parent to send. The phone accepts it only from a lost mark's number, with a
  counter above the last, and stops after five bad codes.
- **Why later:** it is a new inbound attack surface BFU (a DBA `SMS_RECEIVED` receiver parsing strangers' text), a
  second key in DE, replay state, and a PWA flow. Android 16's protection that holds back SMS that look like one-time
  codes from apps other than the default SMS app for hours [unverified] may also delay it unless Vibb holds the SMS
  role (design 82). 17.1's outgoing SMS already covers the main case (a thief's SIM without data, the new number). It
  belongs with the "Bare Vibb" SMS idea, in its own note (D19).

## 18. Other ideas, with a yes or a no (revision 2)

| Idea | Verdict | Why |
|---|---|---|
| Photo with the front camera at a wrong PIN | **No** | BFU an admin receiver may hear `onPasswordFailed` [unverified]. But a background camera needs a `camera` foreground service, which Android 15+ forbids from the boot and which needs while-in-use access. Above all, the person who types a wrong PIN is almost always the kid or a friend. Covert photos of children on a parent's server is a privacy problem (GDPR) and goes against the calm, never sneaky phone. |
| Nearby Bluetooth names | **No** | Bluetooth is often off BFU. Names are bystanders' data ("Ola sin iPhone"), addresses are randomised, and they add little to Wi-Fi. |
| The honest finder's flow | **Yes** | The finder line and "Ring Mamma" (§16). |
| Charging reports | **Yes** | `charging` and `plugged` on every check, and a check at plug-in in lost mode (§14.4). |
| The last check at shutdown | **Yes** | Lost mode only, best effort (§14.4). |
| Block airplane mode on the lock screen | **Yes, while lost** | `DISALLOW_AIRPLANE_MODE` is DPMS state, held BFU [device: §15.6 item 8]. Outside lost mode it is the parent's "Blokker flymodus" (design 98), off by default. |
| Hide the quick settings on the lock screen | **Yes, while lost, if the check passes** | `setStatusBarDisabled` [unverified over a secure keyguard]. |
| Block power-off | **Can't** | No device-owner API hides the lock screen's power menu outside lock task, and there is no lock task BFU. A long press forces a restart anyway. AOSP has no "require unlock to power off" [unverified whether the Jelly Star's build adds one]. Honest promise: a switched-off phone is silent; a restart only puts it back into lost mode BFU. |
| The SIM's ICCID and IMSI | **Compare on the phone, don't send** | D14. |
| The signed command SMS | **Later** | §17.2. |

## 19. A powered-off phone (revision 2)

- **It can't be woken over the network.** With the power off, the modem and Wi-Fi are off. No SMS, call or packet
  reaches software that isn't running.
- **Pixel's powered-off finding** (Pixel 8 and later) keeps Bluetooth beacons going for hours after power-off in
  dedicated hardware, for Google's Find Hub network. It is Google's only and not on the Jelly Star. Google's Find Hub
  itself runs in Play services, which waits for the unlock as ours does [inference].
- **Scheduled power-on.** Many MediaTek builds used to ship "Schedule power on & off" (the `com.mediatek.schpwronoff`
  app, an RTC alarm the modem's power controller keeps). Whether the Jelly Star's Android 16 build still has it is
  [unverified]. Check: search Settings for "Planlagt på/av" or "Schedule power", or `adb shell pm list packages | grep
  -i -e schpwr -e poweron`. Even if it is there, our app can't set it, since it is a system app's own alarm. A parent
  could set it by hand (say 07:00 daily), which helps only a phone someone switched off with battery left. If it
  exists, add one line to `docs/setup/android-settings.md`. No code.
