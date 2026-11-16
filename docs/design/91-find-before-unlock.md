# 91 - Find My Device before the first unlock after a reboot (a design note)

**Revision 3, decided 2026-11-16: the design QA of revision 2 and the user's answers.** Every decision of §13 is
taken (the user answered the last open ones "Høres bra ut!"), except D25, the SIM PIN (§21), recommended in this
revision's last edit. Branch `design-91`, merged with
`main` at 58fa27119. The QA is `docs/review/qa-91-design-r2.md` (12a6af2f8: 2 High, 9 Medium, 15 Low). §20 says how
each finding was handled. The user's answers of 2026-11-16:

- **The connection (D4r): a permanent one.** After the 2-minute grace a locked phone holds the same stream as an
  unlocked phone at rest, so Ring, Lock and a Lost mark arrive in seconds. Revision 2's two tiers of check-ins, its
  `schedule` table, the hold and the waiting-Ring expiry are gone (§14.1). GPS and scans still run only on a request
  or in Lost mode. A restart unlocked within 2 minutes still sends nothing.
- **The mode is "Lost" / "Mistet"** (the user: "Lost er sikkert et bedre navn"). One mode; revision 2's Savnet
  umbrella and its Mistet/Stjålet kinds are gone. One switch is recommended, "Trolig stjålet" / "Probably stolen",
  which keeps every screen silent (D8b, open).
- **Nothing but Android's PIN pad at an ordinary boot, ever.** The finder line, the call button and the cover's
  pad-on-a-tap come only in Lost mode. Revision 2's "Alltid" is dropped (§16).
- **"Ring <name>", not "Ring Mamma":** the parent picks a recovery contact at the mark, remembered for next time. The
  button, the finder line and the SMS use it. With "Trolig stjålet" there is no button and no number (§14.6).
- **H1:** in Lost mode, and only before the unlock, the beacon may briefly lift Vibb's own `DISALLOW_SMS` and a time
  rule's `DISALLOW_OUTGOING_CALLS`, for its own SMS and for "Ring <name>". It lifts nothing else, puts them back at once
  and at the unlock, and the security log records it (§14.7). D24 asks about the same lift on the Lost screen after the
  unlock.
- **H2:** in Lost mode the boot cover shows the finder line and "Ring <name>", and opens the PIN pad only on a tap
  (§16).
- **D20 (a): the Lost screen.** After a correct PIN while Lost, a full-screen lock: "Telefonen er meldt mistet", an
  emergency call and "Ring <name>", every app blocked, ended only by a parent (the PWA, or the parent code on the
  phone). It replaces M6's calm notice; every parent gets a push "låst opp - trolig funnet". (b), a remote PIN change,
  is dropped. (c), biometrics off, is dropped: **a fingerprint ends Lost** instead, with a push and a log line (§14.8,
  §14.9).
- **M9's cuts:** hiding the status bar (`setStatusBarDisabled`) is cut, and so is learning every scanned Wi-Fi inside
  the zones (D11). The joined network and the public IP stay. The call button stays.
- **M5:** Lost mode's restrictions are liftable: a parent ends Lost, the restrictions pause, or the parent code
  (§14.7).
- **M8:** Android 14 to 17 is now a standing project rule (root `CLAUDE.md`). Every platform claim is checked against
  14-17 (§3), and the version-dependent emulator gates run on API 34, 36 and 37 (§9, §15.6).
- **Sections changed in revision 3:** this header, §0, §3, §4, §5, §8, §9, §10-§19. §20 (the QA's findings, one by
  one) is new.

**Revision 2, 2026-11-16 (for the record): the user's D1-D7 and the cost concern; more sources.** Merged with `main`
at 60ee2a86b. What changed then:

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

Revision 3 (§14-§20 hold the details):

- **An ordinary restart costs nothing and shows nothing new.** A phone unlocked within two minutes of getting a network
  sends nothing, and the boot shows Android's PIN pad as today.
- **After that, a locked phone holds the same stream as an unlocked phone at rest.** Ring, Lock, Erase, Locate and
  a Lost mark arrive in seconds. That costs what every unlocked phone's stream already costs: about 150 KB a day and
  6-10 % of the battery a day on mobile data (much less on Wi-Fi), about a tenth of an unlocked phone's server traffic
  (§14.2). No GPS and no scan run unless a parent asks, or the phone is marked Lost.
- **Lost mode ("Lost" / "Mistet") is the full beacon, and only a parent turns it on**, from Finn telefonen, with a
  recovery contact. It sends every source of "where" it has: GPS, the Wi-Fi it joined, the Wi-Fi networks and cells
  around it, and the public IP. It also sends "who": a new SIM, its operator, and its number when readable. With no
  data it sends a tagged SMS to the recovery contact, and on a new SIM it always does, since the SMS's sender number
  is the new SIM's. Before the PIN the boot cover shows "Denne telefonen er meldt mistet. Ring <navn>" and a call
  button; after a PIN, the Lost screen blocks every app until a parent ends it, or the kid's fingerprint does. It
  blocks airplane mode and, if chosen, safe mode. Everything is liftable. "Trolig stjålet" makes every screen silent
  and sends no SMS. On top of the stream about 90 KB and 2 % of the battery a day (§14.4-§14.9).
- **GPS alone is often nothing indoors before the unlock.** On the API 36 Play emulator, fused and network were not
  bound before the unlock. The network the phone came back on and the public IP, matched on our own server against
  what our phones reported inside the parent's zones, give "on Home's Wi-Fi" without any third party. OpenCellID
  offline and BeaconDB opt-in are an optional later step (§15).
- **Cost** about 5.5 rounds before QA (§10). **Recommendation:** steps 1-4 after the current queue and before phase D.
  The Jelly Star checks of §15.6 come first, and the emulator gates run on API 34, 36 and 37. A signed SMS command
  channel goes in its own note later (§17.2). Every decision is taken but D25, the SIM PIN (§13, §21).

Revision 2 (for the record; the cadence and the numbers are replaced by revision 3's):

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

Revision 3: the target is **Android 14-17** (the root `CLAUDE.md`'s standing rule; the launcher's `minSdk` is 34 and
its `targetSdk` 36). The Jelly Star runs Android 16. Every row below was checked against Android 14, 15, 16 and 17's
behaviour-change pages (developer.android.com, read 2026-11-16: 17's "all apps" and "targeting 17" pages). A row says
so where a version differs. The [device] checks are on the Jelly Star's Android 16 (§15.6). The version-dependent ones
also run on an API 34 and an API 37 emulator (§9).

| Part | BFU | Source |
|---|---|---|
| Our code | Only `android:directBootAware` components; `LOCKED_BOOT_COMPLETED` comes to them after boot | [docs] |
| Storage | DE only (`createDeviceProtectedStorageContext()`); CE throws or is empty. Our APK and its resources are readable | [docs] |
| Wi-Fi | Saved networks added through Settings are "shared" and kept in the system's DE store, so Wi-Fi joins BFU. A network an app suggested is per-user (CE) and waits | [inference from AOSP's WifiConfigStore layout; device] |
| Mobile data | Works, unless the SIM has a SIM PIN (typed at boot, separate from the screen lock) | [inference; device] |
| Our VPN filter | Down: `KidVpnService` is not DBA. Normally always-on **without** lockdown (`dns-and-vpn.md`), so traffic flows unfiltered; nothing the kid can use runs BFU. Revision 3 (QA L7): **the sink's shape** (Bare Vibb, or the roaming sink, design 95) sets always-on **with lockdown**, which persists BFU while the VPN can't start, so every app is cut. The always-on package's own UID is exempt from lockdown, so the beacon still gets through | [code; aosp, from memory; device: §15.6] |
| DNS, TLS | The system resolver and the global private DNS (settings, DE). System CAs ship with the OS; design 76's built-in set B is in our code | [inference] |
| AndroidKeyStore | A plain key (no user authentication, no `UnlockedDeviceRequired`) works BFU; an auth-bound one answers `LOCKED` (`keystore2/enforcements.rs:687-717`, `super_key.rs:675-693`) | [aosp, design 59 §2.4; device] |
| Foreground service | A DBA `specialUse` service may start from the boot receiver: Android 15 forbids only `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` from `BOOT_COMPLETED`. A device owner is exempt from the background-start limits anyway. Android 16 and 17 list no new rule for this | [docs; inference] |
| Notifications | Our channels exist (the system keeps them in DE); a notification shows on the lock screen | [inference; device] |
| Alarms | `AlarmManager` works for a DBA receiver ("triggering a scheduled alarm" is the docs' own example). `USE_EXACT_ALARM` is granted at install. Android 17 lists no change | [docs] |
| Network callback by `PendingIntent` | `registerNetworkCallback(request, PendingIntent)` reaches a manifest receiver in a dead process. ConnectivityService fills the intent with the `Network`, so the `PendingIntent` is `FLAG_MUTABLE`, and since targetSdk 34 a mutable one must hold an explicit intent (our receiver's component). Android 17 lists no change | [docs; QA L2] |
| Doze | The battery-optimization exemption (design 19b) is the system's device-idle allowlist, kept in `/data/system` (DE), so it holds BFU | [inference; device] |
| Location | `LocationManager` is a system service; the location switch and our runtime grants are DE. **GPS** works. **NETWORK** and **FUSED** come from Play services' providers on the Jelly Star, which may not answer BFU. **Measured on the API 36 Play emulator (revision 2's brief): fused and network were not bound before the unlock, so GPS only.** Indoors without them a fix may not come at all. A-GPS assistance (SUPL) is the modem's and needs only mobile data | [emulator; inference; device - the most important check] |
| Location from a service (Android 14+) | Background location (`ACCESS_BACKGROUND_LOCATION`, granted) lets a `specialUse` service read location without the `location` service type | [docs] |
| The joined network | The SSID and BSSID come only with `ACCESS_FINE_LOCATION` and the location switch on: `NetworkCapabilities.getTransportInfo()` from a callback registered with `FLAG_INCLUDE_LOCATION_INFO` (Android 12+; `getConnectionInfo` is deprecated). Otherwise `<unknown ssid>` and `02:00:00:00:00:00` | [docs; device] |
| Wi-Fi scan | `startScan` needs fine location, `CHANGE_WIFI_STATE` and the location switch on. A foreground service counts as foreground for the throttle: four scans per two minutes. Only importance above `IMPORTANCE_FOREGROUND_SERVICE` is "background", which is one scan per 30 minutes for all background apps together (`ScanRequestProxy.shouldScanRequestBeThrottledForApp`). `getScanResults` returns the last scan, the system's own included, at no cost | [docs; aosp; device] |
| Cells | `getAllCellInfo` and `requestCellInfoUpdate` need fine location and the location switch on. Telephony runs BFU, because emergency calls do. LTE and NR neighbours usually carry only a PCI and a channel, not a global cell id, so only the serving cell can be looked up | [docs; inference; device] |
| SIM | The SIM loads at boot, because a SIM PIN is asked for BFU. The operator (`getSimOperator`, MCC+MNC, no permission). The ICCID (`SubscriptionInfo.getIccId`) and IMSI: a device owner with `READ_PHONE_STATE` may read non-resettable identifiers ("Restrictions on non-resettable device identifiers"). The line number (`getLine1Number`, `READ_PHONE_NUMBERS`, in our manifest) is often empty | [docs; device] |
| SMS out | `SmsManager.sendTextMessage` (`SEND_SMS`, in our manifest) is a call into the telephony process, which runs BFU. The telephony provider is direct-boot aware, because SMS arrive BFU. On a dual-SIM phone the beacon uses the subscription's own `SmsManager` (`createForSubscriptionId`). Blocked by our own `DISALLOW_SMS` (the row above) | [inference; device] |
| The lock-screen line | `setDeviceOwnerLockScreenInfo` is kept by the system (lock settings, DE) and shown in SystemUI's face BFU, **not on the bouncer** (design 59d §5). In android mode the boot cover occludes that face and asks for the pad at once, so the cover has to draw the line itself and hold the pad back (§16, QA H2) | [inference; 59d; device] |
| User restrictions | `DISALLOW_AIRPLANE_MODE`, `DISALLOW_SAFE_BOOT` and the rest are DPMS state in `/data/system` (DE), so they hold BFU. A device owner can add and clear them from `:locked` BFU | [inference; device] |
| SMS that look like one-time codes | Not the beacon's (it only sends), but §17.2's inbound idea: **Android 17**, all apps, holds WebOTP- and SMS-Retriever-format SMS from apps other than the default SMS app for three hours. At target 37 this extends to standard SMS that contain a code. (Revision 2 wrongly put it on Android 16.) | [docs] |
| Ring | `AudioManager` works. The alarm sound is MediaProvider's, which may not serve BFU [upstream]: play a sound from our APK instead (§4.4). `ZenRuleRuntime.holdForRing` reads CE, so a silent time rule's Do Not Disturb isn't lifted BFU. Revision 3 lifts it from DE (§4.4, QA M2). **Android 17's background-audio hardening**, for all apps: playback, audio focus and volume changes from the background fail silently unless a visible activity or a foreground service that is not `shortService` runs. The beacon's `specialUse` service is that. Once the launcher targets 37, the service must also have while-in-use capabilities, unless the app has the exact-alarm permission and plays `USAGE_ALARM`. We have `USE_EXACT_ALARM`, and the ring uses `USAGE_ALARM` on `STREAM_ALARM`, so the ring passes on 14-17 and at target 37 | [upstream; code; docs: "Background audio hardening"; device: API 37 AVD] |
| Outgoing calls | Revision 3 (QA H1): `DISALLOW_OUTGOING_CALLS` (our `restrictOutgoingCalls`: calls off, a time rule blocking calls, calls failing closed, our dialer not in place) is a user restriction and holds BFU. Every outgoing call also passes `KidCallRedirectionService`, which is DBA and reads `CallPolicyStore` from DE | [code; device] |
| SMS restriction | Revision 3 (QA H1): `DISALLOW_SMS` (our `restrictSms`: SMS "Av", or "Styrt av Vibb" without our SMS role) holds BFU. It is enforced through AppOps (`OP_SEND_SMS`), with no device-owner exemption, so our own `sendTextMessage` fails while it is set | [code; aosp, from memory; device] |
| TLS at target 37 | Certificate transparency is on by default for apps that target 37. A public CA's chain (Let's Encrypt, Caddy's default) carries SCTs, so nothing changes. A server on a private CA would need a network-security-config exception for the whole launcher, not for the beacon alone. Encrypted Client Hello is used when OkHttp and the server support it, and is harmless | [docs] |
| Local network at target 37 | `ACCESS_LOCAL_NETWORK` (in the `NEARBY_DEVICES` group) is required for LAN addresses. The beacon is https to the server's public name. If a home's split DNS resolves that name to a LAN address, the launcher as a whole needs the permission, which a device owner grants itself; that is the launcher's question at target 37, not the beacon's | [docs] |
| Lock | BFU is the strongest lock there is: the PIN is required and CE's keys are not in memory. `lockNow` only turns the screen off | [design 59 §4] |
| Erase | `DevicePolicyManager.wipeDevice(0)` has no unlocked-user check in its path; the keyguard's own "too many failed PINs -> erase" runs the same wipe while BFU | [inference from AOSP's DPMS; device - §7] |
| Child PIN reset (`resetPasswordWithToken`) | Out of scope: it would need the reset token readable BFU (§7.3) | - |

## 4. The minimal design: the locked beacon

### 4.1 The pieces on the phone

- **No new boot receiver.** `BootCallReceiver` stays the one `LOCKED_BOOT_COMPLETED` receiver: the call policy first,
  then the boot cover, then `LockedBeacon.atLockedBoot(context)` - each in its own `try`, so neither delays screening.
- **Revision 3, no service while waiting (QA L1).** `atLockedBoot` only registers the network callback by
  `PendingIntent` (§3) and returns. A validated network sets an exact-while-idle alarm for the grace's end (at once in
  Lost mode). Only that alarm starts the service, which then holds the stream until the unlock. A phone in a drawer
  with no network runs no foreground service at all. An unlock before the alarm cancels it.
- **Revision 3, components (QA L14).** Every new direct-boot-aware component goes into `DirectBootComponents`
  (`calls.md`, `DirectBootComponentsTest`): the service, the alarm receiver, the network receiver and the beacon's own
  stop receiver `LockedStopRingReceiver` in `:locked`. `StopRingReceiver` stays as it is, in the main process, for
  the unlocked ring.
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
  handover (the normal app can't stop a ring in another process); the parent rings again if needed. Revision 3: before
  it stops, it puts back anything it lifted (§14.7: `DISALLOW_SMS`, `DISALLOW_OUTGOING_CALLS`, our zen rule's
  condition) and writes that it did.
- **The rest of the launcher is unchanged.** The rule in `calls.md` ("never start kiosk, enforcement, services or
  tsnet before unlock") gets one written exception: this service, in its own process.

### 4.2 What goes to DE, and what never does

**Revision 3 (QA M3): two files, one writer each, never SharedPreferences.** A SharedPreferences file caches per
process and rewrites its whole map on every commit. With the main process, `:locked` and `:bootcover` all touching
one file, the main process (alive from the locked boot through the unlock) would write back a stale copy. So:

- **`locked_beacon.json`** (the config): written only by the unlocked app, only when it differs. Read by `:locked`,
  `:bootcover` and the main process's `beaconStartPlan`.
- **`locked_beacon_state.json`** (what happened this boot): written only by `:locked`. Read by `:bootcover` (the Lost
  flag an answer brought, §16) and by the main process after the unlock.
- Both are small, strict, versioned JSON (`v:1`) files, written to a temp file and renamed over the old one. Every
  read across processes is a fresh read of the file, never a cached copy. Missing or corrupt means the beacon is off.
- The main process merges `ran` into `CommandAnswers` only after `:locked` has stopped (at `ACTION_USER_UNLOCKED`) and
  before its first sync (a unit test pins the order). It then empties the state file.

The config file, `locked_beacon.json`:

- `enabled` - the policy's `locked_beacon.enabled` (§5.4);
- `server_url` - only when its verdict is `https` (`server_url_vectors.json`); a tailnet address writes none;
- `pin_enforce` - design 76's stored flag, mirrored (the beacon never changes it);
- `location` - `off`, `on_request` or `regularly` and the served interval, from `location_policy`;
- `token` - the locked token, sealed (`ks1:` + AES-GCM) under a **new** plain AndroidKeyStore key `locked_v1`, AAD
  `locked_beacon/token`; never the device token's key `secrets_v1`;
- (revision 1 had `ran` here; revision 3 moves it to the state file.)

Revision 2 added, and revision 3 sorts into the two files (config, then state):

- config: `grace_s` - the served grace (§14.1). (Revision 2's `schedule` is gone with the permanent stream.)
- config: `lost` - null, or the policy's `lost` (§14.5): `{since_ms, probably_stolen, contact: {name, number}?,
  sms_language, airplane_block, safe_boot_block}`, and `lost_ended_since_ms` (a mark the phone has ended, so a stale
  policy can't arm it again, §14.4). Cleared only by a policy that says so: a parent's alone.
- config: `sims` - one HMAC-SHA256 of each ICCID the unlocked phone has seen, under a plain AndroidKeyStore HMAC key
  `locked_sim_v1` (QA L6: a salted hash next to its salt can be brute-forced over the ICCID space; a Keystore key
  can't be read from the file). Never the ICCID itself (§15.4).
- config: `sms_key` - the per-phone key that tags the SMS (§17.1), sealed under `locked_v1`.
- config: our zen rule's id and its `silences_alarms` bit (§4.4, QA M2).
- state: `ran` (the newest eight keys), `lost` as the last answer brought it (it wins over the config's for this boot
  only; the unlocked app's next config write is the parent's word), `lifted` (what the beacon has lifted and must put
  back, §14.7), `sms_sent` per SIM (§17.1), `last_ok_ms`.

(Revision 2's `finder` for "Alltid" is gone: the user dropped "Alltid" in revision 3, §16.)

Never in DE: the device token, the policy, contacts, zones, the PIN hashes, the Child PIN, the tailnet key or
identity, any account. A source scan pins the file's keys (§9).

### 4.3 The locked token: least privilege

- **Made on the phone**, AFU: 32 random bytes, 64 hex digits. (Test tokens are `7e57` repeated 16 times: valid in shape,
  plainly fake. QA L10.) Sent once over the normal channel, `POST
  /api/devices/locked-token` (device bearer) `{token}` -> 204; the server keeps only its SHA-256 (as device tokens,
  `security::hash_token`). Sealed into DE only after the 204.
- **What it opens**: only the three `locked/*` routes (§5.1; revision 3 corrects revision 1's "four", QA L15). The
  normal routes refuse it; the locked routes refuse the device token. Two middlewares, two route groups: the narrow
  scope is structural, not a check in each handler.
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

Revision 3 (the user's permanent connection, §14.1). Revision 2's sparse checks and hold are gone.

1. **At start**: a validated network (the `PendingIntent` callback, not a fixed delay - upstream's 0d4201dab9
   lesson), then the grace (2 minutes, §14.1; none in Lost mode), then the alarm starts the service. Then one
   **check**: `POST /api/devices/locked/check` with `elapsed_ms` (`elapsedRealtime`; the server computes the boot as
   its own now minus it, since the phone's wall clock may be wrong after a flat battery - QA L3), the battery, the SIM
   state and the cheap network facts (§14.5), but no GPS fix. In **Lost mode** (§14.4) the first check carries
   everything.
2. **Then it holds the stream** `GET /api/devices/locked/stream`, as AFU: a keepalive every 240 s, every nudge runs a
   check, and a 30-minute check is the backstop. It reconnects with backoff when the network changes.
3. **The check's answer** is at most one Find command: `ring`, `stop_ring`, `locate`, `lock` or `wipe`. The beacon:
   - keys it as `CommandAnswers` does (id + `requested_at`), against DE `ran`; sends the receipt
     (`POST /api/devices/locked/answer` `{command_id, requested_at, receipt: true}`) before it runs - a wipe's only
     answer when it works;
   - **ring**: alarm stream to the maximum, our own sound from the APK (`res/raw`, looped, `USAGE_ALARM`; a
     `ToneGenerator` tone if that fails), the Stop notification on the lock screen (`LockedStopRingReceiver`, the
     beacon's own, in `:locked`), the usual timeout and restore; answer "ringing". Revision 3 (QA M2): if our own time
     rule's zen rule silences alarms (the config file's id and bit), the beacon sets that rule's condition to false
     with `setAutomaticZenRuleState` for the ring and back when it ends or at the handover. That is our own rule
     (the owner may set its state), so a "Skole" or "Leggetid" in force at the restart no longer mutes the Ring
     [device: §15.6]. Nothing else of Do Not Disturb is touched;
   - **stop_ring**: stops it; answer "stopped";
   - **locate**: a fresh fix (fused high accuracy first, then GPS and network, the bound of the `upstream-fixes` round;
     no CE cache, no throttle), plus a Wi-Fi scan and the cells (§15), sent at once in a check of its own (QA L13);
     answer with its accuracy and age, "no fix", or "location off" when location is Off and the phone isn't Lost;
   - **lock**: `lockNow` (the screen off); answer the new key `locked_bfu` ("Locked: not unlocked since the restart");
   - **wipe**: `LocateCommands.wipe` as today (`wipeDevice(0)`, FRP kept, `wipeOutcome`); only a refusal answers.
4. **At unlock**: the service stops, and the unlocked app merges DE `ran` into `CommandAnswers` before its first
   sync, so a command the server offers again is confirmed, never run twice. `ran` is then emptied.

The answers that don't get through wait in memory and go with the next check; nothing more (the beacon has no
outbox of its own - a lost answer shows as "received" on the Requests list, as today for a wipe).

### 4.5 How often, and for how long

**Revision 3: §14.1-§14.2** (the permanent stream, with the numbers). It is close to revision 1's text below, plus a
2-minute grace and no fix at the start. Revision 1's text, kept for the record:

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
  intake does), and it doesn't match the public IP. **The one exception is Lost mode** (D9): the parent's explicit
  mark turns location on for that phone until a parent ends it, and the PWA says so at the mark (§14.4). The server
  decides that exception by its own `devices.lost_since`, never by anything in the check's body, so a stale flag on
  the phone or a token holder can't make it keep fixes the parent said no to (QA L4).
- Revision 3: outside Lost mode no GPS fix and no scan run BFU unless a parent asks (`locate`, `ring`). `regularly`
  doesn't run BFU. The network facts (the joined Wi-Fi, the serving cell) ride on the checks when location isn't Off:
  they cost no GPS.
- If Android's location switch is off, the beacon switches it on for the fix and back after, as the unlocked code
  does for an asked fix; the "I switched it on" mark is in DE, so a crash still restores it at the next start.
- The fix goes in through the same intake as any other (`zones::store_fix`): stored once, kept by the retention,
  judged for zones (design 64) - an arrival home after a restart is a real arrival.

## 5. The server

### 5.1 The routes (phone listener)

Revision 3: the check's body and answer are §14.5's (`elapsed_ms` replaces `booted_at_ms`; revision 2's
`next_check_s` and `hold_s` are gone with the permanent stream). The routes are these three, plus the registration.

**Rate limits (QA L5).** `AuthedLockedDevice` reuses `require_device_token`'s `limits`: the failure bucket per client,
the device bucket, and an in-flight class for the locked stream. It has a second `TokenIndex` for the locked hashes.
The 20-second rule doesn't answer an event check (`shutdown`, plug-in, SIM change, a locate's fix) with 429: those are
merged into the check in flight or queued for 20 s on the phone. The SIM push (D16) goes once per SIM state per boot,
however often a token holder posts it. BeaconDB is asked at most 6 times an hour per phone (§15.3).

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
- Revision 3 (QA M1): revision 2's locked expiry is gone. With the stream a connected locked phone gets a command in
  seconds, so the one-hour clock applies as for every phone, and `end_expiring_at_start` cancels a waiting one at a
  server restart as it does for every phone (the Requests list says "avbrutt: serveren startet på nytt" /
  "cancelled: the server restarted").
- **A Ring that waited too long isn't played (QA M1).** When a phone connects and a `ring` has waited more than 5
  minutes (it was off or had no network), the server doesn't hand it out. It marks it "ikke ringt: telefonen var ikke
  på nett" / "not rung: the phone was offline" and pushes the parent who asked: "Telefonen er på nett nå - trykk Ring"
  / "The phone is online now - tap Ring". A Ring pressed now plays in seconds, while they listen. This push answers the
  parent's own request, so it fits D7. Locate, Lock and Erase run when they arrive, however late.

### 5.3 Storage

One migration (the next free number): `devices.locked_token_hash`, `locked_token_at`, `locked_since` (the boot the
last locked check named; cleared by the first status report after it). An enrollment clears all three, as it ends
the pending commands today.

### 5.4 The policy and the capability

- `locked_beacon: {enabled}` in the policy (pinned by `policy_json_keys_snapshot` / `PolicyResponseCompatTest`); the
  phone's switch (D6). `enabled` is false for a phone in own mode or with a tailnet address (pointless there).
- Capability `locked_beacon_v1` in the status report: the server offers the switch only to a launcher that has it.
- Revisions 2 and 3: the top-level `lost` (§14.5) and the capability `lost_mode_v1`. Lost mode is offered for every
  phone with the capability, android mode or own mode: AFU it works in both, and BFU only android mode has a BFU to
  speak of. (Revision 2's `locked_beacon.finder` is gone with "Alltid", §16.)
- **One writer for each restriction (QA M4).** The policy keeps the parent's `hardening.disallow_airplane_mode` and
  `disallow_safe_boot` as they are, and `lost` carries `airplane_block` and `safe_boot_block`. On the phone
  `AppEnforcer.applyHardening` stays the one writer AFU: it sets each restriction when the parent's switch is on, or
  when Lost asks for it and no lift is in force (§14.7). BFU the beacon only ever **adds** them from an answer, never
  clears them.

### 5.5 What the parent sees (nb + en)

- Revisions 2-3: the lines per phase are in §14.3, Lost mode's in §14.6, the sources' in §15.5. "Not unlocked yet"
  gets "· sist tilkoblet 14:40" / "· last connected 14:40" while the stream is down, so a phone switched off while
  locked doesn't look connected (QA L15).
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

Revisions 2-3 add to the third row: the token also reads a Lost mark's answer, which holds the recovery contact's name
and number for the finder line and the SMS (§14.5; none with "Trolig stjålet"). That is what the lock screen shows a
finder anyway. It can also post false network facts and a false "another SIM" (a false trail, as with fixes). The DE
file now also holds the Lost flag, the recovery contact's name and number, and (revision 3) ICCID HMACs under a Keystore
key and the SMS key sealed under `locked_v1` (§4.2): nothing a BFU file read gains much from.

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
- **Design 95 (mobile network).** Revision 3 corrects revision 2 (QA L7). The roaming restriction
  (`DISALLOW_DATA_ROAMING`, the parent's "Ingen data" abroad) is DPMS state, so it holds BFU. A phone abroad under it
  sends **zero** bytes over mobile data BFU too, so the beacon reaches the server only over Wi-Fi, and in Lost mode
  the SMS fallback (§17) is the channel; an SMS while roaming in the EEA costs the home price. Under "Bare Vibb" (and
  under "vibb" abroad) Vibb itself, and so the beacon, still uses mobile data. `mobile_data` is a report, not a
  switch. The sink's lockdown persists BFU and cuts every other app, while the beacon, as the always-on package, gets
  through (§3) [device]. The heartbeat (95-T2) is the status report's; a check is not a report, and the
  first full report after the unlock goes up as today.
- **Design 98 (airplane mode).** The parent's "Blokker flymodus" holds BFU (a user restriction). Lost mode asks for
  the same restriction while it lasts (§14.4), through the one writer `applyHardening` AFU (§5.4, QA M4), so
  `AirplaneRoute.turnOff`'s add-and-clear and the switch's clear can't undo it. Setting it ends an airplane mode that
  is already on, so a Lost mark also brings a phone out of the kid's Flymodus. The kid's Flymodus route (the assistant
  role) is not used BFU, and nothing in the beacon touches the role.
- **Design 100 (one Vibb notification).** BFU the anchor isn't running, so the beacon's service posts its own plain
  notice. It uses the anchor's MIN channel `launcher:command_listener`, the round mark `ic_stat_vibb` and
  `VISIBILITY_SECRET`, so nothing shows on the lock screen [device: §15.6]. With the permanent stream (revision 3) the
  service lives until the unlock, as the anchor does AFU. At the unlock the beacon stops before the anchor starts, so
  there is still one Vibb notification at a time.
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
  Revisions 2-3 add the rows `check_lost` (a Lost check with every fact: network, scan, cells, SIM, fix, `sms_sent`,
  `lifted`, `shutdown`), `answers_lost` (with `lost` and `want_fix`), `policy_lost` (the top-level `lost`: none, Lost
  with a recovery contact, Lost with `probably_stolen` and no contact), `lost_ended` (the report's end by the parent
  code and by the fingerprint, and a stale policy with the ended `since_ms` that must not arm it again), `lost_screen`
  (the launcher's alone: which buttons the Lost screen and the cover show per row, no number when `probably_stolen`),
  `sms` (the facts to the SMS text in nb and en with its tag, each GSM-7 only and at most 160 characters; a row with
  SMS "Av" to `lifted: ["sms"]`; QA H1, L8), `sms_tag` (a text and key to its tag, for the PWA's "Check an SMS"),
  `sim` (the ICCID HMACs to `same`, `other`, `none`), `report_network` (the AFU report's `network` and its
  `want_network` answer, §15.2) and `report_imei` (§15.4). The new status keys are in `intake_limits`; neither
  `network` nor `imei` is a heartbeat field, so `status_heartbeat.json`'s `fields_tag` is unchanged (QA L10, L11).
  Test cells use MCC 001 / MNC 01 (the test network), test BSSIDs the locally administered `02:00:5e:..` range, and
  test tokens and keys `7e57` repeated to their length.
- **Server tests**: the locked token is refused on every normal route and the device token on the locked ones (a
  route table scan); the pop leaves `set_kid_pin`, `end_windows` and the rest queued; the "older ones never again"
  rule stays within the Find set; a check never writes `device_status`; a fix is dropped while location is Off;
  enrollment and the switch clear the hash; 429 after 20 s; the PWA lines in nb and en. Revision 3: the sheet saves
  the recovery contact (a normalised number from the phone's contacts or a parent) and remembers it as the phone's
  default; `probably_stolen` serves `contact: null`; a `lost_ended` report clears the mark, logs who and pushes every
  parent; a `ring` that waited over 5 minutes is not handed out and pushes its parent (D23); a lift in a check writes
  `lost_restriction_lifted`; the D9 exception follows `lost_since`, never the body's `mode`; the SMS tag check.
- **Launcher tests**: `beaconStartPlan` (own mode, no marker, no token, tailnet URL, unlocked -> no start); the DE
  codec (strict, versioned, missing = off); a source scan: nothing in `locked/` imports `LauncherPreferences`,
  `TsnetClient`, `SealedPrefs`' device-token fields or any CE path, the DE files' keys are exactly §4.2's and each key
  is in exactly one file, and no SharedPreferences is used for them (QA M3); the `ran` merge into `CommandAnswers`
  after `:locked` has stopped; the answers' words; the fix plan per `location` mode; the network `PendingIntent` is
  mutable with an explicit component (QA L2); every new DBA component is in `DirectBootComponents` (QA L14); every
  lift is put back on each exit path, and at the next start from `lifted` (§14.7); the cover's plan (an ordinary boot
  asks for the pad at once, as today; Lost asks only on a tap; "Trolig stjålet" is an ordinary boot); the Lost
  screen's plan (its buttons per row, the end by the parent code and by `BIOMETRIC_STRONG` only, an ended `since_ms`
  never armed again); the redirection service allows exactly the contact's number while Lost; no number or name in
  any string or screen when `probably_stolen`.
- **Emulator** (`docs/testing/emulator.md`, a new section): an AVD with a screen PIN, reboot, don't unlock: the
  check arrives, ring and stop, locate (the emulator's GPS), lock's answer, the unlock handover, an erase on a
  throwaway AVD. Revision 3 (QA M8, the 14-17 rule): the version-dependent parts (the boot and the `PendingIntent`
  callback, the alarm, the ring under background-audio hardening, the SMS send under `DISALLOW_SMS`, the Lost lock in
  lock task, §14.8) run on **API 34, 36 and 37**. On 37 the ring also runs with `adb shell cmd audio
  set-enable-hardening enable`, which enforces the while-in-use rule even for alarms, to see the limit. The Jelly Star
  (16) stays the check for the radios.
- **Jelly Star** [device], the user's check (revision 2: §15.6's list extends this one): Wi-Fi and mobile data BFU;
  which location providers answer BFU indoors and out; the ring through a silent time rule; the Keystore key BFU; the
  battery over 24 hours BFU; an incoming blocked call still screened while the beacon runs.

## 10. Size

Revision 3. Revision 2 said about four rounds for steps 1-4; the QA (Q91-M9) put them at five or six. Revision 3
takes out the two-tier cadence, the status bar and the scan learning, and adds the Lost screen (D20), the fingerprint
check, the recovery contact, the lifts (H1) and the QA's fixes.

| Step | Side | Size |
|---|---|---|
| 1. The beacon with the permanent connection: revision 1's migration, locked middleware and three routes, filtered pop, registration, policy key, token, `:locked` service, receiver hook, BFU ring/locate/lock/wipe, handover and merge; plus the grace by `PendingIntent` and alarm, the two DE files, the zen rule's lift for the ring, the late-Ring push, the rate limits and the Android 17 audio rules (§4, §5, §14.1) | server S-M, launcher M-L | ~2 rounds |
| 2. Lost mode: the `lost` key and capability, the PWA's sheet (with the recovery contact and "Trolig stjålet"), banner, events and police card, the pushes, the security log (§14.6); the phone's full facts (scan, cells, joined network, SIM HMACs), the airplane and safe-boot blocks through `applyHardening`, the lifts and their restore (§14.7), charging and shutdown checks; the Lost screen on the existing lock screen, its parent code and its fingerprint button (§14.8, §14.9) | server M, launcher M-L | ~2 rounds |
| 3. Matching: the joined network and the IP inside zones (`zone_networks`, the `want_network` answer), the matcher and the parent's lines (§15.2, §15.5) | server S, launcher S | ~0.3 round |
| 4. The boot cover in Lost mode (the line, "Ring <name>", the pad on a tap), the owner-info line BFU, the call through the redirection service, the SMS fallback with its tag, and the PWA's "Check an SMS" (§16, §17); its own BFU device run, because the cover is where the two past boot lockouts were | launcher M, server S | ~1 round |
| 5. Optional: the OpenCellID import, the BeaconDB lookup (§15.3) | server S each | ~0.5 round |
| 6. Emulator passes on API 34, 36 and 37 with a PIN AVD; the Jelly Star checks (§15.6) | both | S + the user's time |

**About 5.5 rounds before design QA and code QA for steps 1-4**, and half a round more for step 5. Recommended order:
the Jelly Star checks of §15.6 first (an afternoon; they decide how much "where" there is BFU and whether H1's lift,
the cover's pad and the fingerprint prompt behave), then step 1, then 2, then 3 and 4 together, and step 5 only if
the parent's own sources fall short.

## 11. Risks

- **Location BFU may simply not come indoors** if Play services' providers don't answer before the unlock. The API 36
  Play emulator already showed GPS only (§3). The joined network and the public IP, matched against the zones (§15.2),
  are the answer at home, at school and at friends'. Far from any zone, indoors, without step 5, a Lost phone has only
  "on mobile data, Telia".
- **A new BFU process.** Mitigated by its own process and the crash guard; still the area of the two past boot
  lockouts. Nothing in it touches HOME, lock task or native code. Revision 3 adds an alarm receiver and a network
  `PendingIntent` receiver to `:locked`, both DBA and both one-line handoffs to the service.
- **The boot cover changes in Lost mode** (§16, Q91-H2): it holds the pad back until a tap. Only in Lost mode, so an
  ordinary boot is exactly today's. Its own BFU device run (step 4).
- **A credential in DE.** Narrow (§6); revocable; documented. Revisions 2-3 add the Lost flag, the recovery contact's
  name and number, ICCID HMACs under a Keystore key and the SMS key sealed under `locked_v1` (§4.2).
- **A new public auth path.** Small, rate-limited (§5.1), structurally separate.
- **Two command consumers.** The filtered pop and the `ran` merge (now across two files with one writer each, §4.2)
  must be right, or a command runs twice (ring twice - harmless) or is skipped (a lock confirmed BFU and lost).
- **The battery of the permanent connection.** 6-10 % a day on mobile data for a phone that stays locked (§14.2). It is
  what the same phone pays at rest unlocked, but it is new for a locked one. The Jelly Star measures it (§15.6).
- **Lifting our own restrictions** (H1, §14.7). The beacon lifts `DISALLOW_SMS` and `DISALLOW_OUTGOING_CALLS` for
  one send or one call, in Lost mode. A crash between lift and restore is caught by the `lifted` mark at the next
  start and at the unlock. The worst case is a few seconds in which nothing of the kid's can run anyway (BFU), or the
  Lost screen is in front (AFU).
- **Lost mode as a tracking tool.** It turns location on even where the parent set it Off (D9). Mitigated: it is a
  mark visible to every parent, logged with who set it, ended by any parent, and since revision 3 the kid sees the
  Lost screen whenever the phone is unlocked while Lost (§14.8): it is never covert to the person holding the phone.
- **A mistaken Lost mark locks the kid out of their phone's apps** (the Lost screen, D20) until a parent ends it, the
  kid's fingerprint ends it (§14.9), or the parent code is typed. "Ring <name>" and the emergency call stay.
- **SMS from a SIM that isn't ours.** In Lost mode a thief's or an honest finder's SIM pays for at most five ordinary
  SMS per SIM (§17.1). They go only to the recovery contact, and carry a tag the PWA can check. Not legal advice; the
  sheet says it may happen. With "Trolig stjålet" there is no SMS at all.
- **Third parties** (step 5 only): OpenCellID's token is a secret in the database, never in the repo; BeaconDB sees
  the server's IP and a Lost phone's nearby networks. Both off by default.
- **The sources may be blocked BFU** in ways the docs don't say (§15.6). Each source fails alone: the check goes up
  without it.

## 12. Verdict

| | |
|---|---|
| **Benefit** | A locked phone after a restart is reachable in seconds, as an unlocked one: "restarted, not unlocked", Ring, Locate, Lock, Erase. In Lost mode, a lost or stolen phone that is on reports where it is from more than GPS, who has put a SIM in it and its number even with no data, tells an honest finder whom to call, and shows a Lost screen to anyone who unlocks it with the PIN. Wider still after phase D. Erase adds little. |
| **Cost** | About 5.5 rounds plus the device checks (step 5 half a round more). On the phone: nothing for a restart unlocked within 2 minutes; for a phone that stays locked, the stream an unlocked phone at rest already holds (about 150 KB and 6-10 % of the battery a day on mobile data); in Lost mode about 90 KB and 2 % a day more. |
| **Risk** | Moderate and contained: a narrow DE credential, an own BFU process, three small routes, a boot cover that differs only in Lost mode, and a Lost mode that is the parent's visible, logged choice, liftable as every restriction. |

**Recommendation: build steps 1-4 after today's queue and before phase D ships, starting with the Jelly Star checks of
§15.6 and the emulator passes on API 34, 36 and 37.** Step 5 (OpenCellID, BeaconDB) only if the own sources fall
short in use. The signed SMS command channel gets its own note later (§17.2). If the reason is Erase alone: don't
build it.

## 13. Decisions for the user

**Decided** (the user, 2026-11-16):

- **D1 - Build it, and when?** Yes, after the current queue and before phase D.
- **D2 - Which commands BFU?** All five: ring, stop, locate, lock, erase.
- **D3 - The credential.** A random token sealed by a plain Keystore key (§4.3), the signed key later with design 60.
- **D4r - The connection.** A permanent one: after the 2-minute grace, the same stream as an unlocked phone at rest
  (§14.1). Revision 2's two tiers of check-ins are gone.
- **D5r - Location outside Lost mode.** No GPS and no scan unless a parent asks; the cheap network facts ride on the
  checks while location isn't Off (§4.7).
- **D6 - A switch per phone.** Yes, on by default for android-mode phones, in Posisjonsinnstillinger.
- **D7 - Push on an ordinary locked restart?** No.
- **D8 - The mode's name.** "Lost" (en) / "Mistet" (nb). One mode; revision 2's Savnet, Mistet and Stjålet are gone.
- **D10 - Match the joined Wi-Fi and the public IP against the zones.** Yes (§15.2).
- **D11 - Learning inside zones.** Only the joined network and the public IP. Learning every scanned Wi-Fi is cut.
- **D15 - The finder line and the call button.** Only in Lost mode, never at an ordinary boot ("Alltid" is dropped).
  The boot cover shows the line and "Ring <name>" and opens the pad only on a tap (§16). The name and number are a
  **recovery contact the parent picks** at the mark, remembered for next time (§14.6).
- **D17 - The SMS fallback.** Yes, in Lost mode, to the recovery contact; the beacon may briefly lift our own
  `DISALLOW_SMS` for it (H1, §14.7, §17.1).
- **D18 - Hardening.** The airplane block in Lost mode stays. Hiding the status bar is cut (the user's M9 answer).
- **H1 - Lifting our own restrictions.** Before the unlock, in Lost mode only, for the beacon's own SMS and "Ring
  <name>": `DISALLOW_SMS` and `DISALLOW_OUTGOING_CALLS`, nothing else, put back at once and at the unlock, logged
  (§14.7).
- **M5 - Liftable.** Lost mode's restrictions are lifted by ending Lost (the PWA, or the parent code on the phone),
  and the restrictions pause lifts them for its length (§14.7).
- **D20 - Keep a Lost phone from being unlocked.** (a) yes: Vibb's own Lost screen before the PIN (the cover) and
  after it (a full-screen lock with only an emergency call and "Ring <name>"), ended only by a parent (the PWA or the
  parent code), replacing M6's calm notice. (b) no remote PIN change. (c) biometrics stay on, and **a fingerprint
  unlock ends Lost** by itself (§14.8, §14.9).

**Decided as recommended** (the user, "Høres bra ut!", 2026-11-16):

- **D8b - One switch, "Trolig stjålet" / "Probably stolen".** Yes, off by default. It keeps the lock
  screen silent: no finder line, no "Ring <name>", no number on any screen, and no SMS (an SMS from the thief's SIM
  would show them the contact's number). Everything else in Lost mode is the same. Alternative: no switch.
- **D9 - Lost mode turns location on even where the parent set it Off.** Yes, said at the mark, logged.
- **D12 - OpenCellID offline** (step 5, off by default, the parent's own token). Yes, later.
- **D13 - BeaconDB** (step 5, opt-in, server-side, Lost phones only, at most 6 queries an hour). Yes, later.
- **D14 - "Who" from the SIM.** ICCID HMACs compared on the phone; only `same`/`other`/`none`, the
  operator and the line number leave it; the IMEI once, for the police card (§15.4).
- **D16 - Pushes.** A PIN unlock while Lost and a fingerprint end (D20), and also a Lost
  phone is on again, and a SIM change in any mode (once per SIM state per boot).
- **D19 - The other ideas (§18).** Charging reports and the last check at shutdown yes; front-camera
  photos and nearby Bluetooth names no; the signed command SMS later, in its own note.
- **D21 - A tag on every SMS, and "Sjekk en SMS" in the PWA** (Q91-M7). Yes.
- **The SMS's recipient** (§17.1): the chosen recovery contact only. The coordinator is confirming this with the
  user; it can be widened to the parents later (a list in place of one contact).
- **D22 - Block safe mode while Lost** (`DISALLOW_SAFE_BOOT`, Q91-L9). Yes, through the same one writer
  and lifted the same way as the airplane block.
- **D23 - A Ring that waited more than 5 minutes for the phone to connect isn't played; the parent gets a push to
  ring now** (Q91-M1). Yes.
- **D24 - "Ring <name>" on the Lost screen after the PIN.** D20 puts the button there, and H1 allowed the lift only
  before the unlock. Yes: the same brief lift of `DISALLOW_OUTGOING_CALLS` for that one call AFU, by the one writer
  `AppEnforcer` (§14.7).

**Recommended, not yet answered** (from the user's question on the SIM PIN):

- **D25 - The SIM PIN** (§21). Recommended: PIN off on the kid's SIM, with the operator's spending limits, as a setup
  choice in `docs/setup/`; and a line on the phone's Advanced page when the SIM asked for its PIN at the last restart.

## 14. The permanent connection and Lost mode (revisions 2-3)

### 14.1 The default: the permanent connection (D4r)

| Phase | When | What runs | A Ring reaches it |
|---|---|---|---|
| No network | from the boot until a validated network | nothing: the `PendingIntent` network callback is registered, no service runs (§4.1) | not yet; see below |
| Grace | the first 2 minutes after the first validated network | nothing: no request, no fix, no scan. An unlock in this time ends it all: **0 bytes** | at the first connection |
| Connected | from the grace's end to the unlock | the stream, as AFU (a keepalive every 240 s, a 30-minute backstop check), and one check at the start with the restart, the battery, the SIM and the cheap network facts. No GPS, no scan unless a parent asks | **seconds** |

- **In Lost mode there is no grace**: the alarm fires at the first validated network.
- **The grace is served** (`grace_s`, 0-10 minutes), so it can be tuned without a release.
- **Before the first connection**, a parent's command waits in the queue as for any phone that is off. Locate, Lock
  and Erase run when the phone connects, within their own expiry. A Ring that waited more than 5 minutes isn't played;
  the parent gets "Telefonen er på nett nå - trykk Ring" / "The phone is online now - tap Ring" (§5.2, D23).
- **What the PWA shows while it waits.** Under the pressed button and on the Requests list: "Venter på at telefonen
  kobler til (ikke på nett siden 21:14). Den ringer hvis den kobler til innen 5 minutter; ellers får du beskjed." /
  "Waiting for the phone to connect (offline since 21:14). It rings if it connects within 5 minutes; otherwise you'll
  be told."

### 14.2 The numbers, against a phone at rest

The comparison the user asked for is **an unlocked phone at rest** (screen off, nothing used). It holds the same
stream today: the same keepalive every 240 s, the same radio wake-ups. A locked phone with the beacon pays that, and
less than the unlocked one, because it makes no 30-minute syncs (policy, status, apps: about 1.4 MB a day today,
design 95 §3.4) and carries no filter traffic. Against today's silent locked phone, it is new.

Assumptions: the stream's start is a new TCP and full TLS 1.3 connection, ~3.6 KB with Caddy's ECDSA chain (design 95
§3.3), plus ~1 KB of request and first check. A keepalive is ~140 B on the wire (95 §3.3). A check on the held HTTP/2
connection is ~1 KB, ~1.7 KB with Lost mode's full facts [inference: OkHttp shares one connection per host].
Reconnects (network changes, NAT timeouts) about ten a day, ~5 KB each [estimate]. Battery, all estimates until the
Jelly Star measures them: a radio wake-up on LTE 0.3-0.5 mAh (the QA's range), much less on Wi-Fi; a GPS attempt up
to 60 s ~0.8 mAh; a Wi-Fi scan ~0.05 mAh. The Jelly Star's battery is 2000 mAh.

| Case | Bytes | Battery |
|---|---|---|
| A restart unlocked within 2 minutes (most restarts) | **0** | **0** |
| Each hour connected and locked | ~2 KB of keepalives + ~2 KB of backstop checks | 5-8 mAh on LTE (0.25-0.4 %); far less on Wi-Fi |
| A night-time update at 03:00, unlocked at 07:00, on Wi-Fi | ~20 KB | a few mAh |
| The same on mobile data | ~20 KB | 20-32 mAh (1-1.6 %) |
| A whole day locked | ~150 KB (including ~50 KB of reconnects) | 6-10 % on LTE; roughly 1-2 % on Wi-Fi |
| The same phone unlocked at rest, for comparison | ~1.4 MB (design 95 §3.4, before its 304 and heartbeat) | the same stream's 6-10 %, plus the syncs |
| Lost mode, on top: the first hour after the boot | ~10 KB (6 full checks) | ~5 mAh (6 GPS attempts, 6 scans) |
| Lost mode, on top: a whole day | ~90 KB | ~45 mAh (~2 %) |
| One SMS (Lost mode, §17) | ~140 B, billed as one SMS | negligible |

A locked phone's own idle drain is likely 0.5-1 % an hour [unverified]. That, not the beacon, decides how long a lost
phone lasts.

### 14.3 What the parent sees, per phase (nb / en)

| Phase | The phone's summary and Finn telefonen |
|---|---|
| Grace, or no network | Unchanged: design 38's "Av siden 21:14 · batteri 2 %" / "Off since 21:14 · battery 2 %", or the last "Sett" |
| Connected | "Startet på nytt 14:02 · ikke låst opp ennå · tilkoblet nå" / "Restarted 14:02 · not unlocked yet · connected now" |
| The stream down again while locked | "... · sist tilkoblet 14:40" / "... · last connected 14:40" (Q91-L15) |
| Where, when known | A line from §15.5, such as "På Wi-Fi-en til Hjemme" / "On Home's Wi-Fi" |
| A button pressed before the first connection | §14.1's waiting line |
| The unlock | The start record as today; the locked lines go |

### 14.4 Lost mode ("Lost" / "Mistet")

**How the phone knows.**

- **Marked while the phone is on and unlocked:** the policy's top-level `lost` carries it. The launcher writes the DE
  config at once and applies Lost mode's unlocked parts: the Lost screen (§14.8), the airplane and safe-boot blocks
  through `applyHardening` (§5.4), and the location interval. The next boot starts Lost mode BFU without asking.
- **Marked while the phone is locked and connected:** the stream's nudge and the check's answer carry it, in seconds.
  The beacon writes its state file, and adds the airplane and safe-boot restrictions (it never clears them).
- **Marked while the phone is off:** at its first connection, which in that boot has the 2-minute grace, because the
  phone doesn't know yet.
- **Ending is a parent's alone:** "Avslutt Mistet" / "End Lost" in the PWA, or the parent code on the phone's Lost
  screen (§14.8). The kid's fingerprint is the one other end (§14.9). An ended mark is remembered by its `since_ms`, so
  a stale policy can't arm it again; a new mark can. "Remove phone" and a new enrolment clear it too.

**What it does, from the moment the beacon comes up.**

- No grace. The first check goes at the first validated network, with everything (§14.5), and a first GPS attempt
  starts at once (up to 120 s: the first fix after a boot has no assistance data).
- **Where:** GPS, a Wi-Fi scan, the cells, the joined Wi-Fi, and the public IP on the server (§15). A check with a fix
  and a scan every **10 minutes for the first hour** (after the boot, or after the mark arrives), then every **30
  minutes**. Each GPS attempt stops at 60 s. A phone lying still (the same facts, a fix younger than 30 minutes) skips
  the GPS. Also a check at once on a SIM change, at plug-in and on a new network.
- **Who:** the SIM state at every check (§15.4). A new SIM sends its operator, its number when readable, and one SMS
  (§17) whose sender number is the new SIM's, unless "Trolig stjålet" is on.
- **Charging:** `ACTION_POWER_CONNECTED` (a runtime receiver while the service lives) runs a check: "Lades siden
  14:20" / "Charging since 14:20".
- **The last gasp:** at `ACTION_SHUTDOWN` one last check with `shutdown: true` and the battery: "Slått av 15:40 ·
  batteri 3 %" / "Switched off 15:40 · battery 3 %".
- **The battery budget:** below 20 % a fix at most every 60 minutes; below 10 % no GPS, network facts every 60 minutes,
  the stream kept.
- **Hardening:** `DISALLOW_AIRPLANE_MODE` (it also ends an airplane mode already on) and, if D22 is taken,
  `DISALLOW_SAFE_BOOT`, through the one writer (§5.4). Liftable (§14.7).
- **Before the PIN:** the boot cover shows the Lost line and "Ring <name>", and the pad on a tap (§16). With "Trolig
  stjålet", the cover behaves exactly as at an ordinary boot.
- **Quiet otherwise:** no sound unless a parent rings; no notice on the lock screen (`VISIBILITY_SECRET`, §8).
- **After the PIN:** the Lost screen (§14.8). After a fingerprint: Lost ends (§14.9).
- **Unlocked while Lost** (the Lost screen in front): the server serves a 10-minute location interval, over the
  parent's chosen interval and design 64's for that phone.

**"Trolig stjålet" / "Probably stolen"** (D8b, one switch, off by default). No finder line, no "Ring <name>", no
number on any screen, no SMS. The boot cover is the ordinary one. The Lost screen after a PIN reads "Denne telefonen
er sperret." / "This phone is locked." with only the emergency call, the parent code and the fingerprint button. All
else is the same.

### 14.5 The wire

The check body (every key optional but `elapsed_ms` and `mode`; outside Lost mode no `scan` and no `location` unless
asked; nothing under `network` when location is Off and the phone isn't Lost):

```json
{
  "elapsed_ms": 185000,
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
  "lifted": [{"what": "sms", "at_ms": 1763291000000, "restored_ms": 1763291004000}],
  "shutdown": false
}
```

The answer: `{"command": {...}?, "lost": {...} | null, "want_fix": bool}`. The policy's top-level `lost`, and the same
object in the answer:

```json
{
  "since_ms": 1763290000000,
  "probably_stolen": false,
  "contact": {"name": "Mamma", "number": "+4700000000"},
  "sms_language": "nb",
  "airplane_block": true,
  "safe_boot_block": true
}
```

- `contact` is the recovery contact (§14.6). It is null when `probably_stolen` is true, so the phone never holds a
  number it may not show. The server checks it with `phone::normalise` (`phone_vectors.json`).
- `sms_language` is the parent's language, not the phone's (Q91-L8).
- The scan is cut to the 12 strongest BSSIDs. Hidden networks and SSIDs ending in "_nomap" (the opt-out convention)
  are dropped on the phone.
- The status report gets `lost_ended: {since_ms, by: "parent_code" | "fingerprint", at_ms}` (§14.8, §14.9), and the
  Lost screen's events (`lost_screen_unlocked_at_ms`).
- Pinned by `locked_beacon.json` (§9), `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

The server: `devices.lost_since`, `lost_by`, `lost_probably_stolen`, `lost_contact_name`, `lost_contact_number`, and
per phone the last contact as the default (`lost_contact_default`); `device_events` rows for the events list;
`zone_networks` (§15.2).

### 14.6 The PWA (nb / en)

- **The place:** Locate › Finn telefonen (design 88), a fifth row under Ring, Oppdater posisjon, Lås and Slett: "Meld
  mistet" / "Mark as lost".
- **The sheet:**
  - "Hvem skal finneren ringe?" / "Who should a finder call?": the recovery contact, picked from the phone's contacts
    (the call policy's) or the account's parents with a number. The last one chosen for this phone is preselected.
    Under it: "Navnet og nummeret vises på telefonen, og SMS-en går dit." / "The name and number show on the phone,
    and the SMS goes there."
  - "Trolig stjålet" / "Probably stolen", off: "Ingenting på skjermen viser navn eller nummer, og telefonen sender
    ingen SMS." / "Nothing on the screen shows a name or number, and the phone sends no SMS." When it is on, the
    contact picker is hidden.
  - The note: "Posisjonen slås på til du avslutter. Telefonen sender posisjon, nettverk og SIM-kort hver gang den er
    på, og en SMS hvis den ikke har data. Den som låser opp med PIN-koden ser Mistet-skjermen til du avslutter." /
    "Location is turned on until you end this. The phone sends its position, networks and SIM card whenever it is on,
    and an SMS when it has no data. Whoever unlocks it with the PIN sees the Lost screen until you end this."
  - "Meld mistet" / "Mark as lost". Every control here, the Posisjonsinnstillinger switch, the OpenCellID token and
    the BeaconDB switch keep the scroll position on save (the PWA's save rule, Q91-L15).
- **The banner** on the summary and in Finn telefonen: "Meldt mistet siden 14:10 · tilkoblet nå" / "Marked lost since
  14:10 · connected now", or "· ikke vært på siden 21:14" / "· not on since 21:14".
- **Hendelser / Events:** restarts, SIM changes, networks, places, charging, SMS sent, lifts, unlocks, switch-offs with
  the battery, newest first.
- **Til politiet / For the police:** the model, the IMEI (§15.4), the phone's own number, the SIM changes with operator
  and number, and the last positions with their times, as text to copy.
- **"Sjekk en SMS" / "Check an SMS"** (D21): paste an SMS; "Fra telefonen" / "From the phone" or "Ikke fra telefonen"
  / "Not from the phone".
- **"Avslutt Mistet" / "End Lost"**, for any parent.
- **The security log:** `lost_on`, `lost_off` (with who: a parent in the PWA, the parent code, the fingerprint),
  `sim_changed`, `lost_sms_sent`, `lost_restriction_lifted` (H1).
- **Pushes:** a PIN unlock while Lost, to every parent: "Emmas telefon ble låst opp 15:02 - trolig funnet.
  Mistet-skjermen vises til du avslutter." / "Emma's phone was unlocked at 15:02 - probably found. The Lost screen
  shows until you end it." A fingerprint: "Emmas telefon ble låst opp med fingeravtrykk 15:02 - Mistet er avsluttet."
  / "Emma's phone was unlocked with a fingerprint at 15:02 - Lost has ended." The parent code: "Mistet ble avsluttet
  på telefonen med foreldrekoden 15:02." / "Lost was ended on the phone with the parent code at 15:02." D16's other
  two if taken.

### 14.7 What may be lifted, and by whom

**What the beacon lifts (H1, decided).** Our own `DISALLOW_SMS` (SMS "Av", or no SMS role) and a time rule's or the
call settings' `DISALLOW_OUTGOING_CALLS` would block the beacon's SMS and "Ring <name>". So, in Lost mode, before the
unlock:

- For one SMS: write `lifted: sms` to the state file, clear `DISALLOW_SMS`, send, and put it back in a `finally` once
  the send's result comes (at most 30 s).
- For one call to the recovery contact: write `lifted: calls`, clear `DISALLOW_OUTGOING_CALLS`, place the call to
  the contact's number only, and put it back when the call has ended (Telecom's call state, polled every 5 s) or after
  30 s if it never connected. `KidCallRedirectionService` allows the contact's number while the config's `lost` names
  it, and still refuses every other number. The keyguard's emergency dialer only dials emergency numbers [aosp], so
  nothing else can use the gap.
- Nothing else is ever lifted. At the next start and at `ACTION_USER_UNLOCKED`, anything still in `lifted` is put back
  first. Each lift goes in the next check (`lifted`), and the server writes `lost_restriction_lifted` to the security
  log.
- AFU, on the Lost screen (D24, decided): `AppEnforcer`, the one writer AFU, does the same brief lift for the one call.

**How Lost mode's own restrictions are lifted (M5, decided).** They are the airplane block, the safe-boot block (D22),
the location override (D9), the 10-minute interval and the Lost screen.

- **A parent ends Lost:** in the PWA (seconds, over the stream), or with the parent code on the Lost screen (offline).
  Everything goes.
- **The restrictions pause** (the PWA's, or the parent code's pause from the phone, `restrictions_pause.json`) lifts
  them all for its length, as every other restriction. The mark and the beacon stay, and they come back when the pause
  ends.
- **The kid's fingerprint** ends Lost (§14.9).
- Not the override's exception list: these are not hardening switches. The parent's own "Blokker flymodus" and
  "Blokker sikker modus" stay the server's alone, as today.

### 14.8 The Lost screen (D20 (a), decided)

- **Before the PIN:** the boot cover (§16).
- **After a correct PIN while Lost:** a full-screen lock in front of everything. It is the existing lock screen
  (the time rules' `decideTimeLock` screen in lock task) with a new reason above all others, "Lost". It shows:
  - "Telefonen er meldt mistet" / "This phone is marked lost";
  - "Mamma og pappa er varslet." / "Your parents have been told.";
  - "Ring <navn>" / "Call <name>" (the recovery contact; D24 for the restriction);
  - "Nødsamtale" / "Emergency call" (design 73's emergency menu);
  - "Er det din telefon? Bruk fingeravtrykket" / "Is it your phone? Use your fingerprint" (§14.9);
  - "Foreldrekode" / "Parent code" (the parent's offline code ends Lost on the phone).
- **Every app is blocked**, as under a time rule's lock, and the shade's Snarveier with it. Calls in are screened as
  usual.
- **It ends only** when a parent ends Lost (the PWA or the parent code), when the kid's fingerprint ends it (§14.9), or
  while the restrictions pause runs (§14.7).
- **It replaces M6's calm notice.** It is never covert: whoever holds the unlocked phone sees that it is marked lost
  and that it is shared with the parents.
- **(b), a remote PIN change, is dropped** (the user). It could only act after an unlock anyway: the reset token is
  CE and §7.3 keeps it there. The existing manual Child PIN change in the PWA stays as it is.
- **(c), biometrics off, is dropped** (the user). Before the boot's first PIN they don't work anyway. AFU only enrolled
  fingers work, and a fingerprint is the kid's proof (§14.9).
- **The honest limit:** a thief without the PIN can't get past Android's pad anyway. The Lost screen is for whoever
  knows the PIN: a friend who found it, or someone who watched the kid type it. They can still switch the phone off,
  or erase it from recovery (FRP then asks for the Google account).

### 14.9 Telling a fingerprint unlock from a PIN unlock (D20 (c))

The first unlock after a boot is always the PIN, so this applies only to later unlocks.

| Candidate | Verdict |
|---|---|
| `DeviceAdminReceiver.onPasswordSucceeded` (`ACTION_PASSWORD_SUCCEEDED`) | **Not reliable.** DPMS sends it from `reportSuccessfulPasswordAttempt` only when there were failed attempts before, or a password owner is set; a plain first-try PIN sends nothing. A biometric unlock calls `reportSuccessfulBiometricAttempt`, which sends nothing either [aosp, from memory; device]. Its absence proves nothing |
| `ACTION_USER_PRESENT` without a preceding password success | **Not reliable**, for the same reason |
| `KeyguardManager` | **No.** It says locked or not, secure or not, never how it was opened [docs] |
| The security log (`setSecurityLoggingEnabled`): `TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT` carries the method's strength (1 strong = the credential, 0 otherwise) [docs] | **It can tell, but late.** The logs come in batches through `onSecurityLogsAvailable`, possibly hours apart [aosp, from memory; device], and it turns on a device-wide log of everything for one bit. Not recommended |
| Own mode (design 59's own lock, no Android credential) | **Yes, directly.** Our own lock knows whether its PIN or its fingerprint opened it |
| **Our own fingerprint prompt on the Lost screen** (`BiometricPrompt`, `BIOMETRIC_STRONG` only, no device-credential fallback; `USE_BIOMETRIC` is in the manifest) | **Yes, reliably, on 14-17** [docs]. Only an enrolled finger passes, so a PIN-knowing stranger can't |

**Recommended:** in own mode, a fingerprint unlock skips the Lost screen and ends Lost. In android mode the keyguard's
method can't be told reliably, so the safe fallback holds: every unlock is treated as a PIN unlock and the Lost screen
shows. The screen's "Is it your phone? Use your fingerprint" ends Lost with one touch. On success: Lost ends on the
phone at once, `lost_ended {by: "fingerprint"}` goes to the server, which clears the mark, writes the security log
and pushes "låst opp med fingeravtrykk - Mistet er avsluttet". An enrolled parent's finger counts the same.
[device: that the prompt shows over our lock-task screen, and that the Jelly Star's sensor is `BIOMETRIC_STRONG`.]

## 15. Where and who: the sources (revisions 2-3)

### 15.1 GPS

As revision 1, and the weakest BFU source indoors (§3). The first fix after a boot has no assistance data on the
phone, but the modem's A-GPS (SUPL) works over mobile data BFU [inference]. Outdoors a fix in 30-60 s is likely;
indoors often none. Outside Lost mode only on a parent's request (D5r).

### 15.2 The network it came back on, and the public IP

- **BFU:** saved Wi-Fi joins BFU (§3) [device]. Its SSID and BSSID are readable with fine location and the switch on.
  The public IP needs nothing from the phone: the server reads it from the request (`net::NetConfig::client`, with
  `TRUSTED_PROXIES`).
- **The public IP is matched only for a check over Wi-Fi.** On mobile data it is an operator's shared (CGNAT) address.
  IPv4 exact, IPv6 by its /64. Private, CGNAT (100.64/10) and proxy addresses are never stored. Only rows seen in the
  last 7 days match, since a home's dynamic IPv4 address passes to another customer within days, and the line says
  "trolig" / "probably" (Q91-L12).
- **Learning, while unlocked (D11, revision 3: the joined network and the IP only).** The server's answer to a status
  report carries `want_network: true` when the latest fix lay inside a zone with accuracy of 100 m or better. The phone
  then sends `network` (the joined SSID and BSSID, the serving cell) once, on its next report (Q91-L11): no extra
  bytes on every heartbeat. The server keeps it in `zone_networks (zone_id, kind, value, first_seen, last_seen,
  count)`, with the public IP of that report when it came over Wi-Fi. Caps: 10 BSSIDs, 10 cells and 4 IPs per zone,
  the least recently seen goes. Rows unseen for 90 days are pruned, and a zone's delete deletes its rows. With location
  Off the phone sends no `network`. (Revision 2's learning of up to 12 scanned BSSIDs is cut.)
- **Matching, BFU:** the joined BSSID in a zone's set; the same SSID with another BSSID (weaker); the serving cell (a
  kilometre or more, weakest); the IP. The lines are in §15.5.
- **Privacy:** the family's own access points, cells and internet lines, on the family's own server next to zone
  coordinates it already has. Nothing leaves the server.

### 15.3 The nearby Wi-Fi and cells, and how they are looked up

- **BFU:** a scan and the cells need fine location and the switch on (§3). In Lost mode the beacon turns the switch on
  if it is off (§4.7, the DE mark restores it). The service is foreground, so four scans per two minutes are allowed.
  Outside Lost mode only a `locate` scans.
- **The ladder:** (1) the zones' learned joined networks and IPs (§15.2), always and free; (2) OpenCellID offline for
  the serving cell (D12, step 5); (3) BeaconDB for the scan and the cells, Lost phones only, opt-in (D13, step 5); (4)
  GPS whenever it comes. The best answer is shown with its source and radius. Since revision 3 a Lost phone's scan
  matches nothing of ours unless one of its BSSIDs is a zone's joined one.
- **OpenCellID** [docs: opencellid.org's downloads page; Wikipedia]: owned by Unwired Labs, licensed CC BY-SA 4.0;
  "Products or services using data derived from OpenCelliD need to visibly credit OpenCelliD." The download needs a
  free account's API token. There are per-country exports, a worldwide file and daily changes, and the exports hold
  the last 18 months of observations. The worldwide file has been cited as ~105 MB gzipped (an unofficial source).
  Norway's file (MCC 242) is likely a few MB [unverified: download it once and measure]. The design: Innstillinger ›
  Posisjon › "Mobilmaster offline (OpenCelliD)". The parent pastes their own token, stored in the database as a
  secret, shown masked, never logged. The countries default to the phones' MCCs (design 83's `sim_operators`). The
  server fetches the files monthly in the background, as it fetches MET's weather: the server's own job, not a host
  action from the PWA. It imports them into `ocid_cells` and shows "© OpenCelliD (CC BY-SA 4.0)" wherever a cell's
  position is used. No third party is asked at lookup time. The repo holds no OpenCellID data: the tests use made-up
  cells under MCC 001 and a token of `7e57` repeated.
- **BeaconDB** [beacondb.net, read 2026-11-16]: the open successor of Mozilla Location Service (shut down in 2024).
  "A public domain wireless geolocation database" of ~140 M Wi-Fi networks and ~6.8 M cells; experimental. `POST
  https://api.beacondb.net/v1/geolocate`, Ichnaea-compatible, no key; clients should send a User-Agent. Its privacy
  notice: server logs (IP, time, URL, User-Agent) kept 28 days; the request body not recorded. No data dumps yet.
  Coverage in Norway [unverified: one query from the Jelly Star's home scan]. The design: a server switch "Spør
  beaconDB (tredjepart)" / "Ask beaconDB (third party)", off by default; asked only by the server, only for a Lost
  phone's check with no GPS fix and no learned match, once per changed set of facts, at most 6 times an hour per phone
  (Q91-L5); the User-Agent is a generic `handy-server`.

### 15.4 Who: the SIM, the battery, the IMEI

- **The SIM.** The unlocked app writes to DE an HMAC of each SIM's ICCID it has seen, under the plain Keystore key
  `locked_sim_v1` (§4.2; Q91-L6). A device owner with `READ_PHONE_STATE` may read the ICCID [docs]. BFU the beacon
  reads the SIM, computes the same HMAC and sends only `same`, `other`, `none` or `unknown`, the operator (MCC, MNC,
  name) and the line number when readable. The number (`getLine1Number`, `READ_PHONE_NUMBERS`, which a device owner
  grants itself) is often empty, because operators rarely write it to the SIM [unverified on a Norwegian SIM]. The SMS
  of §17 is the sure way, except under "Trolig stjålet".
- **Raw ICCID and IMSI: possible, never sent (D14).** For a thief's SIM they are a stranger's subscriber identity. The
  operator, the number and the phone's own IMEI are enough for a police report.
- **Outside Lost mode too:** the SIM state rides on every check at no cost (D16's push, once per SIM state per boot).
- **The battery and charging:** `level`, `charging` and `plugged` on every check.
- **The IMEI** (`getImei`, a device owner with `READ_PHONE_STATE` [docs]) is reported once by the unlocked app
  (`report_imei`, not a heartbeat field), for the police card only.

### 15.5 What the parent sees, per source (nb / en)

| Source | Line |
|---|---|
| The joined BSSID in a zone | "På Wi-Fi-en til Hjemme" / "On Home's Wi-Fi" |
| The same SSID, another BSSID | "På et Wi-Fi med samme navn som hos Hjemme" / "On a Wi-Fi named like Home's" |
| The IP (seen in the last 7 days) | "Trolig samme internettlinje som Hjemme" / "Probably the same internet line as Home" |
| The serving cell in a zone | "Samme mobilmast som ved Skolen" / "Same cell tower as at School" |
| OpenCellID | "Mobilmast ca. 1 km unna dette punktet (OpenCelliD)" / "Cell tower about 1 km from this point (OpenCelliD)", a circle on the map |
| BeaconDB | "Omtrent her, ca. 80 m (beaconDB)" / "About here, about 80 m (beaconDB)", a circle |
| GPS | As any fix |
| Nothing | "På mobildata (Telia) · ingen posisjon" / "On mobile data (Telia) · no position" |
| A new SIM | "Nytt SIM-kort: Telia, 4x xx xx xx" / "New SIM card: Telia, 4x xx xx xx", or "nummer ukjent" / "number unknown" |

### 15.6 The Jelly Star checks (Android 16) that settle the [unverified] points

One afternoon with the debug build, a screen PIN and a reboot, not unlocked. The version-dependent ones (marked V)
also run on the API 34 and API 37 emulators (§9):

1. Which location providers answer BFU (`dumpsys location`), and the time to a GPS fix indoors and out.
2. That a Wi-Fi saved through Settings joins BFU, and that mobile data comes up; with the sink's lockdown on (Bare
   Vibb), that the beacon's check still goes through (Q91-L7).
3. That the joined SSID/BSSID, `getScanResults`, `startScan` (from the foreground service) and `getAllCellInfo`
   answer BFU with the switch on, and that `setLocationEnabled` works BFU.
4. That `SubscriptionInfo.getIccId` answers BFU, and what `getLine1Number` returns with the user's SIM.
5. (V) That `SmsManager.sendTextMessage` from `:locked` sends BFU **with SMS "Av"** (the lift of H1) and without it,
   and where the sent SMS shows after the unlock.
6. That the owner-info line shows on SystemUI's face, and **what a finder sees on the first power press BFU in Lost
   mode**: the cover's line and button, no pad until a tap (Q91-H2). And that an ordinary boot is exactly today's.
7. (V) That "Ring <name>" places the call BFU **with a call-blocking time rule in force** (the lift of H1), through the
   redirection service, and that no other number gets through.
8. That `DISALLOW_AIRPLANE_MODE` and `DISALLOW_SAFE_BOOT` hold at the keyguard BFU (the power menu's safe-mode entry).
9. (V) The ring BFU **with Skole in force** (the zen rule's lift, Q91-M2), and on API 37 under background-audio
   hardening (`cmd audio set-enable-hardening enable` too).
10. (V) That the alarm and the `PendingIntent` network callback reach `:locked` BFU in Doze.
11. That a `VISIBILITY_SECRET` notice on the MIN channel stays off the lock screen.
12. That `ACTION_SHUTDOWN` reaches the `:locked` service's runtime receiver.
13. The battery over 24 hours locked on Wi-Fi and on mobile data, in normal and Lost mode, against an idle locked phone
    without the beacon and the same phone unlocked at rest.
14. (V) The Lost screen after a PIN: every app blocked, the emergency call, "Ring <name>", the parent code; and that
    the fingerprint prompt (`BIOMETRIC_STRONG`) shows over it and ends Lost (§14.9).
15. Whether Settings has a scheduled power on/off, or a "require unlock to power off" (§19).

## 16. The finder line, and "Ring <name>" (revisions 2-3)

**Only in Lost mode, and not with "Trolig stjålet"** (D15, the user). An ordinary boot shows Android's PIN pad and
nothing else of ours, exactly as today. Revision 2's "Alltid" is dropped.

- **The text:** "Denne telefonen er meldt mistet. Ring <navn>: <nummer>" / "This phone is marked lost. Please call
  <name>: <number>". The name and number are the recovery contact's (§14.6), from the DE config.
- **Where it shows:**
  - **The boot cover, BFU** (android mode). Normally the cover asks for the pad as soon as it is resumed (design 59d
    §5), so a finder would see only the bouncer, which doesn't show the owner-info line (Q91-H2). **In Lost mode the
    cover doesn't ask on its own.** It shows the mark, the line, "Ring <navn>" / "Call <name>" and "Trykk for å låse
    opp" / "Tap to unlock", 59d §5's "Cancelled" layout, and asks only on a tap. It reads the Lost flag with a fresh
    read of both DE files (the config, and the state file the beacon writes when an answer brings the mark), at every
    resume.
  - **Android's own face** (`setDeviceOwnerLockScreenInfo`), the fallback when the cover can't start. The beacon writes
    it BFU when an answer turns Lost mode on; the unlocked `AndroidLock` writes it AFU. The finder line goes first and
    the restart line second [device: whether two lines show].
  - **The Lost screen** after a PIN (§14.8).
- **"Ring <name>":** one button, one fixed number from DE, no keypad. It goes through our dialer role and
  `KidCallRedirectionService`, which allows exactly the contact's number while Lost. The lift of
  `DISALLOW_OUTGOING_CALLS` for the one call is H1's (§14.7). Whether Telecom places a non-emergency call BFU from the
  cover is [unverified; §15.6 item 7]. If not, the line alone stays. The worst abuse is someone calling the contact.

## 17. The SMS fallback (revisions 2-3)

### 17.1 Out: the phone tells the recovery contact by SMS

- **When (Lost mode only, not with "Trolig stjålet"; D17):** (a) at once on a new SIM (`sim: other`), even with data,
  because the SMS's sender number is the new SIM's number, which `getLine1Number` rarely gives; (b) after 15 minutes
  with no server reached while the SIM is in service (no data on that SIM, data off, roaming blocked abroad), then at
  most one every 6 hours. **At most five per SIM**, counted per ICCID HMAC in the state file, so a restart gives no
  new five (Q91-L8). Each is reported at the next check (`sms_sent`) and shown in Events.
- **To whom:** the recovery contact only (§14.6), the one number the parent chose for the screen. One SMS per event
  keeps the cost to a SIM's owner low. A parent who wants the SMS themself picks themself; the sheet says where it
  goes.
- **Our own `DISALLOW_SMS`:** lifted for the one send (H1, §14.7).
- **The text,** GSM-7 only, at most 160 characters, in the parent's language (`sms_language`), with a tag: "Vibb: Mistet
  telefon er på (14:02). Nytt SIM: Telia. Posisjon 59.9123,10.7501 ca. 40 m 14:05. Batteri 54 %. K7Q4M2XA" / "Vibb:
  Lost phone is on (14:02). New SIM: Telia. Position 59.9123,10.7501 about 40 m 14:05. Battery 54 %. K7Q4M2XA". No
  "±": it isn't in GSM-7 and would make the SMS UCS-2, two or three billed parts (Q91-L8). Without a fix: "Wi-Fi:
  <SSID>" or "Mobilmast <MCC>-<MNC>-<area>-<cell>".
- **The tag (D21, Q91-M7):** 8 base32 characters, an HMAC-SHA256 over the text under the per-phone `sms_key`. The phone
  makes the key while unlocked and registers it with the locked token. The server keeps it sealed, as it keeps the kid
  PIN key. The PWA's "Sjekk en SMS" checks it, and the sheet says that an SMS that fails the check isn't from the
  phone, since anyone who knows the contact's number could send a false trail.
- **On a dual-SIM phone** the SMS goes from the subscription that has service, through its own `SmsManager`.
- **Can it be done BFU:** `SEND_SMS` is in our manifest, and a device owner grants itself the runtime permission.
  `SmsManager` calls the telephony process, which runs BFU [inference; §15.6 item 5].
- **The cost to the SIM's owner:** one ordinary SMS each, usually inside the plan; home price roaming in the EEA, a few
  kroner outside [unverified: operator]. At most five per SIM. Only to the recovery contact, never to the thief's
  contacts. In "Trolig stjålet" none, because it would show the thief the contact's number in their own sent messages
  and bill.

### 17.2 In: a signed command SMS from the parent (later, its own note)

- **The idea:** with no data, the parent sends an SMS such as "VIBB RING 7Q4K-9XZ2-M3" and the phone rings, or answers
  with its position by SMS.
- **Spoofing:** an SMS sender can be forged. The code must be a MAC (HMAC-SHA256 under a per-phone key sealed in DE,
  over the command and a counter, cut to 60 bits), made by the PWA, accepted only from the recovery contact's number
  with a counter above the last, and stopped after five bad codes.
- **Why later:** a new inbound attack surface BFU (a DBA `SMS_RECEIVED` receiver parsing strangers' text), a second key
  in DE, replay state and a PWA flow. **Android 17** (revision 2 wrongly said 16) holds SMS that look like one-time
  codes from apps other than the default SMS app for three hours: WebOTP and SMS Retriever formats for all apps, and
  at target 37 standard SMS with a code too [docs]. Unless Vibb holds the SMS role (design 82), a code SMS would wait
  three hours. §17.1 already covers the main case. It belongs with the "Bare Vibb" SMS idea, in its own note (D19).

## 18. Other ideas, with a yes or a no (revisions 2-3)

| Idea | Verdict | Why |
|---|---|---|
| Photo with the front camera at a wrong PIN | **No** | A background camera needs a `camera` foreground service, which Android 15+ forbids from the boot and which needs while-in-use access. Above all, the person who types a wrong PIN is almost always the kid or a friend: covert photos of children on a parent's server is a privacy problem (GDPR) and against the calm, never sneaky phone. |
| Nearby Bluetooth names | **No** | Often off BFU; bystanders' data ("Ola sin iPhone"); randomised addresses; little beyond Wi-Fi. |
| The honest finder's flow | **Yes** | The finder line and "Ring <name>" on the cover (§16), Lost mode only. |
| Charging reports | **Yes** | `charging` and `plugged` on every check; a check at plug-in in Lost mode (§14.4). |
| The last check at shutdown | **Yes** | Lost mode only, best effort (§14.4). |
| Block airplane mode while Lost | **Yes (decided)** | `DISALLOW_AIRPLANE_MODE` through the one writer (§5.4); liftable (§14.7). |
| Block safe mode while Lost | **Yes (D22, decided)** | A thief can restart into safe mode from the power menu, where our app doesn't run [aosp, from memory]. `DISALLOW_SAFE_BOOT` is off by default today (`Hardening.SAFE_BOOT(false)`). Through the one writer, liftable as the airplane block (Q91-L9). |
| Hide the quick settings on the lock screen | **Cut** (the user, M9) | Three writers on one DPM state (Q91-M4), unverified over a secure keyguard, and little beyond the airplane block. |
| Block power-off | **Can't** | No device-owner API hides the lock screen's power menu outside lock task, and there is no lock task BFU. A long press forces a restart anyway. AOSP has no "require unlock to power off" [unverified whether the Jelly Star's build adds one]. Honest promise: a switched-off phone is silent; a restart only brings it back into Lost mode. |
| Biometrics off while Lost | **No (the user, D20 (c))** | A fingerprint is the kid's proof and ends Lost (§14.9). |
| A remote PIN change | **No (the user, D20 (b))** | §14.8. |
| The SIM's ICCID and IMSI | **Compare on the phone, don't send** | D14. |
| The signed command SMS | **Later** | §17.2. |

## 19. A powered-off phone (revision 2)

- **It can't be woken over the network.** With the power off, the modem and Wi-Fi are off. No SMS, call or packet
  reaches software that isn't running.
- **Pixel's powered-off finding** (Pixel 8 and later) keeps Bluetooth beacons going for hours after power-off in
  dedicated hardware, for Google's Find Hub network. It is Google's only and not on the Jelly Star. Google's Find Hub
  itself runs in Play services, which waits for the unlock as ours does [inference].
- **Scheduled power-on.** Many MediaTek builds used to ship "Schedule power on & off" (the `com.mediatek.schpwronoff`
  app, an RTC alarm the power controller keeps). Whether the Jelly Star's Android 16 build still has it is
  [unverified]. Check: search Settings for "Planlagt på/av" or "Schedule power", or `adb shell pm list packages | grep
  -i -e schpwr -e poweron`. Even if it is there, our app can't set it, since it is a system app's own alarm. A parent
  could set it by hand (say 07:00 daily), which helps only a phone someone switched off with battery left. If it
  exists, add one line to `docs/setup/android-settings.md`. No code.

## 20. The design QA of revision 2, finding by finding (revision 3)

`docs/review/qa-91-design-r2.md` (12a6af2f8). Every finding is fixed, or the line says why it no longer applies.

| Finding | How revision 3 handles it |
|---|---|
| H1 SMS and the call blocked by our own restrictions | The user's decision: the brief, logged lift in Lost mode BFU (§14.7); D24 asks about the same lift AFU; §3 rows, §15.6 items 5 and 7, the `sms` "blocked" row (§9). |
| H2 the finder line hidden behind the pad | The cover holds the pad until a tap in Lost mode only (§16); the beacon writes the owner info BFU; fresh DE reads; §15.6 item 6. |
| M1 the Ring's timing | No longer applies in its first three points: the permanent stream (D4r) delivers in seconds, and the one-hour clock is every phone's. The late-Ring push is D23 (§5.2); §14.1's "No network" row is corrected. |
| M2 a silent time rule mutes the ring | The beacon sets our own zen rule's condition false for the ring (§4.4); §15.6 item 9. |
| M3 one SharedPreferences file, three processes | Two JSON files, one writer each, atomic rename, fresh reads; the merge after `:locked` stops (§4.2, §9). |
| M4 a third writer on two DPM states | The status bar is cut (the user). The airplane block (and D22's safe-boot block) go through `applyHardening` as the one writer AFU; the beacon only adds BFU (§5.4). |
| M5 no offline lift | Ending Lost (the PWA or the parent code), the restrictions pause, and the fingerprint (§14.7). |
| M6 Lost mode invisible to the kid AFU | Replaced by the Lost screen (D20 (a), §14.8), never covert; the push goes to every parent. |
| M7 forged SMS; the SMS tells a thief | The tag and "Sjekk en SMS" (D21, §17.1); with "Trolig stjålet" no SMS at all (D8b). |
| M8 nothing checked against Android 17 | §3 checked against 14-17's behaviour-change pages (background audio, OTP SMS, CT, local network, ECH; no change for FGS starts, alarms or `PendingIntent`); emulator gates on API 34, 36 and 37 (§9, §15.6 "V"). |
| M9 the estimate and the cuts | Size restated at about 5.5 rounds (§10). Cut: the status bar, the scan learning, the two-tier cadence (replaced by the user's stream). Kept: "Ring <name>" (the user). |
| L1 a long-lived service while waiting | The `PendingIntent` callback and an alarm; no service before the grace's end (§4.1). |
| L2 mutable `PendingIntent` | `FLAG_MUTABLE` with an explicit component, pinned in a test (§3, §9). |
| L3 the wall clock | `elapsed_ms`; the server computes the boot (§4.4, §14.5). |
| L4 trusting `"mode": "lost"` | The server's own `lost_since` decides D9's exception (§4.7). |
| L5 rate limits | Reused buckets, a second `TokenIndex`, event checks merged, the SIM push debounced, BeaconDB capped (§5.1, §15.3). |
| L6 brute-forcing salted ICCID hashes | An HMAC under a Keystore key (§4.2, §15.4). |
| L7 the VPN row and design 95 | Corrected (§3, §8); §15.6 item 2. |
| L8 the SMS text | No "±"; the parent's language; five per SIM; the dual-SIM `SmsManager` (§17.1, §3). |
| L9 safe mode | D22 (§18). |
| L10 contract hygiene | Test tokens `7e57` repeated; the IMEI and `network` rows, `intake_limits`, no heartbeat change (§9). |
| L11 `network` on every report | `want_network` from the server; sent once (§15.2). |
| L12 false IP hits | 7 days, "trolig" (§15.2, §15.5). |
| L13 when a locate's fix goes | In a check of its own at once; "location off" (§4.4). |
| L14 components, the stop receiver | `DirectBootComponents`; `LockedStopRingReceiver` in `:locked` (§4.1). |
| L15 small text issues | "Three routes" (§4.3); "last connected" (§5.5, §14.3); the save rule for every control (§14.6). |

## 21. The SIM PIN (revision 3)

The user: "all ringing og sms før opplåsing henger sikkert fort sammen med at sim kort ikke har pin-kode?" Yes.

- **A SIM with its PIN on stays locked after every boot until the PIN is typed** (Android asks for it on the lock
  screen, separately from the screen PIN). Until then the phone has no mobile data, no SMS and no calls but emergency
  ones. The BFU beacon then has **Wi-Fi only**: no stream over mobile data, no SMS fallback, no new-SIM SMS. A phone
  lost outside a known Wi-Fi is silent, as today.
- **A device owner can't type it.** `TelephonyManager.supplyPin` and its kin need `MODIFY_PHONE_STATE`
  (signature|privileged), and no `DevicePolicyManager` method supplies a SIM PIN on Android 14-17 [aosp, from memory;
  docs]. Storing the SIM PIN to type it would be one more secret in DE anyway. So the SIM's PIN is the parent's setup
  choice, not something Vibb can work around.
- **The trade-off for the kid's own SIM.** PIN off: after a restart the beacon has mobile data and SMS, and Find works
  anywhere with coverage. But someone who takes the SIM out can use it in another phone (calls, data, premium SMS on
  the parent's bill) until the operator blocks it. PIN on: the SIM is useless elsewhere, but the phone has Wi-Fi only
  after every restart until the kid types the PIN.
- **Recommended (D25): PIN off on the kid's SIM, paired with the operator's own limits:** a spending cap, premium and
  content services blocked, and the operator's "block the SIM" once Find has had its chance on a lost phone. It is a
  setup choice. The build writes it into `docs/setup/` (a short `sim-card.md`, linked from `android-settings.md`): how
  to turn the SIM PIN off (Settings › Security › SIM card lock), which limits to ask the operator for, and when to
  block the SIM.
- **A thief's own SIM with a PIN:** they type it, so the phone gets their network, and the beacon, the SIM report and
  the new-SIM SMS work on it (§15.4, §17.1).
- **Showing the state (recommended: yes, a line on Advanced).** `TelephonyManager.getSimState()` is public and needs no
  permission, and `SIM_STATE_PIN_REQUIRED` is readable BFU [docs; device]. Whether the PIN is *enabled*, rather than
  required right now, is a privileged read [unverified], so the phone reports what it saw at the locked boot: the
  beacon writes `sim_pin_at_boot` to its state file, and the first status report after the unlock carries it (a
  `report_sim_pin` row in `locked_beacon.json`, the key in `intake_limits`, not a heartbeat field). The phone's
  Advanced page in the PWA then shows: "SIM-kortet har PIN-kode. Etter en omstart har telefonen bare Wi-Fi til koden
  er tastet, så Finn telefonen virker bare på kjent Wi-Fi." / "The SIM card has a PIN. After a restart the phone has
  Wi-Fi only until the PIN is typed, so Find works only on a known Wi-Fi." With no report yet, nothing. Phone Setup
  gets no line: the state is known only after the first restart.
- **Jelly Star check** (with §15.6): reboot with the SIM PIN on and don't type it; `getSimState()` from `:locked`; the
  beacon connects over Wi-Fi only and sends no SMS.
