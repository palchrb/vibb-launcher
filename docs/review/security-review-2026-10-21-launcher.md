# Security and privacy review: launcher (2026-10-21)

Scope: `launcher/` (Vibb One, home app + Device Owner) at `95afa2ea`, with the focus on what changed since the last
full review (`security-review-2026-10-10.md`, launcher wave `qa-launcher-security-wave.md`): 218 launcher commits -
designs 24/24b (parent PIN, vault, relock), 25/25b-f (glance page), 26a-c/27/28/30 (music bridge and courier), 32
(lock flash, keyguard cover), 34 (polish), 35/38 (battery, power events), 36 (custom DNS upstream), 37 (Install Now),
41 (flashlight), 42 (the clock on the lock), 51 (glance text per app), 52 (Bluetooth by reflection), launcher rounds
6/7 (VoIP from any allowed app, notice wake), and today's `lfix-notifs` (the VPN drops its foreground state and heals
itself). The earlier review's open launcher items are re-checked at the end.

Read-only: no code changed. `testDebugUnitTest` on `95afa2ea`: 1494 tests, 0 failures. Line numbers are those of
`95afa2ea`; paths below are relative to `launcher/app/src/main/java/com/kidslauncher/mdm/` unless they start with
`launcher/`, `server/` (the Rust server) or `docs/`. Nothing here was run on an emulator or a phone.

## Summary

| Severity | Security | Privacy |
|---|---|---|
| High | 0 | 0 |
| Medium | 4 (S-M1 to S-M4; S-M3 and S-M4 need the user's decision) | 1 (P-M1) |
| Low | 11 | 5 |
| Info | 9 | 5 |

There are no High findings. The exported surface is tight: no outside app can relax enforcement through an
intent, every PendingIntent of ours is explicit and immutable (except PackageInstaller's two, which must be mutable),
the debug hooks can't reach a release build, M10's catalog APK checks are in place, and the listener's readers keep
message content in memory only. The weak spots are the transport (M11 is still open), the content filter's blind
spots and its silence towards the parent, and the reach of a VoIP ring over the PIN lock.

Tailscale verdict: **keep it as it is.** With no auth key and no earlier join, no Go class is initialized,
`libgojni.so` is never loaded and nothing listens. Three small hardening items are listed under S-L10.

| ID | Severity | Finding |
|---|---|---|
| S-M1 | Medium | Cleartext allowed app-wide, no https check on the server URL, and a silent fallback past tsnet (review M11, still open) |
| S-M2 | Medium | The parent is never told when the content filter is down, and `dns_upstream` can show a dead tunnel as healthy |
| S-M3 | Medium (decision) | One switch in an allowed browser ("Use secure DNS") bypasses the whole filter (accepted risk 2026-10-10, re-raised: cheap mitigations exist) |
| S-M4 | Medium (decision) | A VoIP call from anyone opens the whole calling app, with the kiosk on also other allowed apps, over the PIN lock for whoever holds the phone (round 7 §1 accepted it for the kid, not for a finder) |
| P-M1 | Medium (privacy) | Location falls back to "a fresh fix every 10 minutes" whenever the phone enforces a fallback policy, overriding a parent's "off" |

## Medium

### S-M1. Cleartext, no https check, silent fallback past tsnet (review M11, still open)

- **Evidence:**
  - `launcher/app/src/main/AndroidManifest.xml:112` has `android:usesCleartextTraffic="true"`, and there is no
    `networkSecurityConfig`.
  - `server/Provisioning.kt:28` stores the QR's server URL with no scheme check, and so does the Settings edit
    (`ui/settings/launcher/SettingsFragmentLauncher.kt:340`).
  - `createMdmApi` (`server/MdmApi.kt:129`), the SSE client (`server/CommandListenerService.kt:102`) and the music
    client (`music/MusicServer.kt:72`) use the proxy only when `TsnetClient.proxy()` is non-null. Otherwise they go
    out over the ordinary network, even on a phone with a key or a joined node (crash guard skip, a failed `up`,
    before the first Home resume).
  - Design 22's L1 (`EndpointPolicy`, mode-bound transport) and L3 (strict NSC) are still "to come"
    (`docs/design/22-connectivity-and-docker.md:1193`).
- **Risk:** with an `http://` URL (a LAN IP, or `http://100.x` on mobile data, which is carrier CGNAT space) anyone
  on the path reads the bearer token, the app list and the location. They can also answer with a policy that opens
  everything or a command such as `start_pause`. The documented `https://…ts.net` setup fails closed, so the risk
  depends on configuration.
- **Fix:** design 22 L1 + L3 as designed:
  - Release refuses a non-https URL at provisioning and in Settings.
  - A phone with a key or `tsnet_node_joined` never builds a server client without the proxy: it fails and retries.
  - `network_security_config.xml` with `cleartextTrafficPermitted="false"` and system CAs only, plus a debug overlay
    for the emulator.

### S-M2. The parent is never told when the filter is down

- **Evidence:**
  - `StatusReportRequest` has no filter-state field (`server/dto/StatusReportRequest.kt`; the only VPN field is
    `dnsUpstream`, :96-99).
  - `KidVpnService.dnsUpstreamReport()` returns `live?.report()` (`server/KidVpnService.kt:411-415`). `live` is
    cleared only in `stopSelfCleanly` (:390), not in `tunnelLost` (:268), so a phone whose tunnel died keeps reporting
    `in_use: custom`.
  - The heal paths have gaps:
    - A failed `establish()` (null or a throw, :159-174) schedules no retry; only a read loop that ends does
      (:268-294).
    - `onRevoke` (:362-371) stops the service until the next `apply()`.
    - The retry runs on a main-`Handler` (uptime) clock that stops in deep sleep, with a 5 min cap.
  - Lockdown is off by necessity (`server/AppEnforcer.kt:1040-1076`), so while the tunnel is down every app's DNS goes
    unfiltered to the network.
  - The only sign is the "Content filter connecting…" notification, which the kid sees and the parent doesn't.
- **Risk:** filtering can stop silently for the parent. Recovery then waits for the next `apply()`: an SSE event, or
  the 30-min backstop, later in Doze. Today's `lfix-notifs` change is sound on its own (the system's
  `BIND_FOREGROUND_SERVICE` binding and the anchor FGS keep the process at foreground priority; a throw in
  `isAlwaysOn` keeps the notification). It made the tunnel-down state *less* visible on the phone, but it is no
  regression.
- **Fix:**
  - Status `vpn_filter {state: up|down|off|paused, since_ms, always_on, list_version, list_size}` from
    `VpnTunnelState`, `isAlwaysOn` and `DnsFilterEngine`, with a server event after N minutes down. This is an API
    change: both directories and a contract file in one commit.
  - Send `dns_upstream` only while a tunnel is up.
  - Arm the retry on every failed `startVpn`, request a sync's `apply()` from `onRevoke`/`tunnelLost` while the filter
    is wanted, and use an `ELAPSED_REALTIME` alarm for back-offs over a minute.

### S-M3. "Use secure DNS" in an allowed browser bypasses the filter (accepted risk, re-raised)

- **Evidence:**
  - The tunnel routes only `192.0.2.2/32` and answers only UDP/53 (`server/KidVpnService.kt:147-149`, :298-300).
  - The Private DNS lock (`server/AppEnforcer.kt:1100-1106`) covers only the system resolver.
  - Chrome's Settings › Privacy › "Use secure DNS" is one switch, and its DoH host (`chrome.cloudflare-dns.com`,
    `dns.google`) resolves through our filter and is allowed. Firefox TRR works the same way.
  - The launcher pushes no browser managed config: the only `setApplicationRestrictions` call is
    `music/MusicSync.kt:123`.
- **Status:** the 2026-10-10 review lists "apps with their own DoH go around it" as an accepted risk, and
  `launcher/CLAUDE.md` calls it "one known hole". It is re-raised because the kid needs no skill for it, and because
  most of it closes cheaply now that the filter is the product. Don't change it without the user's yes.
- **Fix (proposal):**
  1. Push `DnsOverHttpsMode = "off"` through `setApplicationRestrictions` to allowlisted Chrome, Edge, Brave and
     Samsung Internet.
  2. Add a server category "encrypted-DNS bypass": the public DoH/DoT host names, plus `use-application-dns.net` as
     NXDOMAIN (Firefox's canary).
  3. Route the well-known public resolver IPs (8.8.8.8/8.8.4.4, 1.1.1.1/1.0.0.1, 9.9.9.9, 149.112.112.112,
     94.140.14.14, 208.67.222.222 and their IPv6 /128s) into the tun. UDP/53 to them is then answered by our filter
     (`buildResponsePacket` already answers as the original destination), and 853/443/QUIC to them is dropped. Our own
     upstream sockets are protected and unaffected. Needs a device check.

### S-M4. A VoIP ring from anyone opens the calling app over the PIN lock (round 7 §1; decision)

- **Evidence:**
  - Since round 7 §1 every effective-allowlisted app is a VoIP candidate (`lock/VoipCallPlan.kt:53-80`).
  - A RINGING record makes the re-front yield to whatever is in front (`lock/RefrontPolicy.kt:86`). The lock sends
    the app's full-screen intent with the background-start opt-in (`lock/VoipCalls.kt:401-410`) and, at every
    resume during the call, the call notification's content intent (`reopenCall`, :387-390).
  - The in-call phase lasts up to 3 h per record, and a new ring starts a new record.
  - With the kiosk on, the lock-task list stays the whole kiosk list while LOCKED
    (`server/LockTaskHelpers.kt:188-190`).
- **Risk:** design r7 §1.1.2 lists this residual and the user accepted it on 2026-10-20 with the reasoning "the lock
  is the kid's own PIN, so a faked ring gains the kid nothing". That reasoning covers the kid. It does not cover the
  PIN lock's other job: keeping a **finder or thief** out. Anyone who holds the locked phone and gets a WhatsApp,
  Teams or Signal call through to it (the caller can be the thief, since these apps' contacts aren't managed by Vibb)
  then has the app's full UI, chats included, for the call's length. With the kiosk on they also reach other allowed
  apps the call app starts. Android's own keyguard shows only a show-when-locked call screen in that situation.
- **Fix (for the user to decide):** any one of these narrows it:
  - "Rings over the lock" as a parent-ticked per-app set, defaulting to the known messengers.
  - While a VoIP exemption runs with the kiosk on, narrow lock task to the call app plus the helpers, with the
    app-block bit, as the kiosk-off path already does.
  - After a call answered over the lock, re-front the lock as soon as the app leaves its call screen. This needs a
    device check of what "call screen" means per app.

## Medium: privacy

### P-M1. Location falls back to every 10 minutes under a fallback policy

- **Evidence:**
  - `server/MdmSyncWorker.kt:196`: `decision.policy?.let { it.locationPolicy ?: LEGACY_LOCATION_POLICY }`.
  - `LastEnforcedPlan` (`server/PolicyGate.kt:113-147`) doesn't carry `locationPolicy`, so a `Fallback` - and
    `NOTHING_ALLOWED_FALLBACK` (:199) - has `locationPolicy == null`, which becomes LEGACY, an interval of 10 minutes
    (`server/LocationPolicies.kt:27`, :35).
  - Every fresh fix also switches system Location on (`server/LocateCommands.kt:115-117`).
- **When:** a corrupt or missing cache together with a rejected, undecodable or 5xx fresh policy. The server is
  reachable in those cases, so the fixes are uploaded.
- **Risk:** a parent's explicit "off" or "on request" becomes tracking every 10 minutes, the opposite of fail-closed
  for privacy.
- **Fix:** carry `locationPolicy` in `LastEnforcedPlan`, written and read like `hardening`. With nothing known, use
  `on_request` (the server's default) or `off`, never the legacy interval.

## Low: security

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| S-L1 | Third-party PendingIntents are sent with the lock's (or Home's) background-start privilege without a creator check. An allowed app can put another app's PendingIntent into its CallStyle ring and have the lock fire it. The glance already checks this (`intentFromApp`). | `lock/VoipCalls.kt:401-410` (FSI, answer, decline, `reopenCall`); `music/MusicSessions.kt:206-208` (`sessionActivity`); `badges/VoipCallReader.kt:62-71` keeps intents of every app's call-shaped notification | Keep and send an intent only when `creatorPackage == notice.packageName` (and `isActivity`, or broadcast for decline); `sessionActivity` only from `controller.packageName`; drop notices of ineligible packages at post time |
| S-L2 | The PIN-lock crash guard counts **any** crash in the process while `PinLockActivity` exists (on any thread, e.g. a listener reader), not only the lock's. 3 within 2 min switch the lock (and the keyguard cover) off until a sync at least 10 min later, never while offline. Conditional: no outside crash trigger is known today. | `Application.kt:214-222`, `lock/RefrontPolicy.kt:152-172`, `lock/PinLockRuntime.kt:1216-1226` | Count only throwables with `lock.*` frames or from the lock's lifecycle; report the trip as an event; consider the system keyguard as the tripped state rather than no lock |
| S-L3 | The time-rule lock spares "the alarm app" = the creator of the next `AlarmClockInfo.showIntent`, so any allowlisted app that calls `setAlarmClock` stays usable at bedtime (with the kiosk off, via the shade or Recents). The PIN-lock side was already narrowed by design 42. | `server/AppEnforcer.kt:181`, `server/EnforcementPlan.kt:198` | Spare only the system clock app or `ClockApp.verified`, as `lockClockPackages` does |
| S-L4 | The notification rule (when switched on; default off) cancels or snoozes Play services' and Play Store's notifications too: Play Protect "harmful app", account security alerts. | `badges/NotificationRule.kt:60-70` (gms/vending not essential), `server/AppEnforcer.kt:514` | Keep gms/vending security channels (as `PLATFORM_NAG_CHANNELS` keeps `critical_warning`), or forward their occurrence to the parent |
| S-L5 | Bluetooth (design 52): `clearRadioRestrictions` clears `DISALLOW_BLUETOOTH`/`_CONFIG_BLUETOOTH` on every apply, pairing takes any device class, and `DISALLOW_BLUETOOTH_SHARING` is never set, so file transfer to and from a friend's phone or PC is open. The reflection itself is safe: only `BLUETOOTH_CONNECT` APIs, every `getMethod` inside a `try`, addresses only from the bonded list or scan, names never logged. The open Bluetooth page doesn't re-check the mask. | `server/AppEnforcer.kt:1025-1030`, `server/QuickControls.kt:670`, `ui/quickcontrols/BluetoothDevicesActivity.kt:94-103` | Set `DISALLOW_BLUETOOTH_SHARING` while managed; offer pairing only for audio-class / A2DP-sink devices unless the parent allows any; `finish()` the page when the mask bit goes |
| S-L6 | Kid PIN: the back-off stops growing at 15 min a try (a 4-digit PIN falls in weeks of patient tries), and a failed save of the count is ignored (`saveBackoff`'s result), unlike `ParentPin`. | `lock/PinBackoff.kt:18-27`, `lock/PinLockRuntime.kt:1562` | Keep doubling to 24 h, or parent code only after N failures with an event; refuse the try when the count can't be committed |
| S-L7 | `TimeRuleReceiver` is exported without a permission and handles its own alarm action `com.kidslauncher.mdm.action.TIME_RULE_BOUNDARY` (not protected). Any app can force `TimeRulesRuntime.recheck` (accrual, apply, relock check) at will. It only tightens, but it is a wake and work lever. `BOOT_COMPLETED` is protected and `BootWitness`-guarded. | `launcher/app/src/main/AndroidManifest.xml:475-483`, `timerules/TimeRuleReceiver.kt:65-73` | Aim the alarm at a new non-exported receiver; ignore the boundary action in the exported one |
| S-L8 | "The music app" is trusted by package name only, and release trusts `me.vibb.music.debug` too. That covers the `POST_NOTIFICATIONS`/`BLUETOOTH_CONNECT` grants, the managed config, the nudge, session pick and the **budget exemption** (usable after screen time runs out). The bridge's per-call `check()` is name-only too (the signature permission on the service is the real guard). Mitigated by the allowlist and the relock's catalog-pin signer check. | `music/MusicPlan.kt:27`, :287; `music/MusicSync.kt:61-78`; `timerules/TimeRules.kt:189`; `music/MusicBridgeService.kt:45-48` | Release: `MUSIC_PACKAGES = ["me.vibb.music"]`; require `checkSignatures(ours, pkg) == SIGNATURE_MATCH` before grants, config, exemption and in `check()` |
| S-L9 | VPN engine robustness: every UDP/53 packet is its own coroutine on the shared `Dispatchers.IO`, each blocking up to 2.5-10 s (a fresh TLS connection per DoT query; `readFully` resets the timeout per read). Any app can flood `192.0.2.2:53` and starve the sync's IO or grow memory. Also: the block-list write isn't atomic (a kill mid-write leaves a truncated list used offline after a reboot); only the first question is classified but a QDCOUNT>1 query is forwarded whole; `BlockedEventLog.record` commits synchronously before the answer and its dedup map grows without bound. | `server/KidVpnService.kt:75`, :260-261, :308-321; `server/UpstreamTransport.kt:119-166`; `server/DnsFilterEngine.kt:138-149`; `server/BlockedEventLog.kt:32`, :59-71 | Bounded queue + `limitedParallelism(16)`, drop when ~256 wait; an absolute deadline in `readFully`; reuse DoT connections; `writeAtomically` for the list; drop QDCOUNT != 1; record after answering, LRU the map |
| S-L10 | tsnet (only once configured): `connect` catches `Exception`, not `UnsatisfiedLinkError`/`ExceptionInInitializerError` - the aar is arm64-only - so a bad `.so` crashes the process from an IO coroutine; a Go panic after `connect` returned isn't counted by the crash guard (the flag is cleared in `finally`); nothing ever clears `tsnet_node_joined` or `files/tailscale`, so a phone once joined starts tsnet on every Home resume and sync until wiped (30 s blocks under the sync mutex if its node is gone). | `server/TsnetClient.kt:115`, :221-231, :255 | Catch `LinkageError`; count a process death within N min of a connect; a "Leave tailnet" action that clears the mark and the state dir |
| S-L11 | `DISALLOW_CONFIG_VPN` is set only while hardening is managed (an allowlist or managed calls), so a phone with only the Web filter doesn't get it. AOSP still greys out always-on for an admin-set package, but the lockdown toggle and the VPN dialog's Disconnect need a device check. | `launcher/CLAUDE.md:61`, `server/Hardening.kt` | Set it whenever `vpnFilterEnabled` (the override lifts the filter anyway) |

## Low: privacy

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| P-L1 | The notification rule logs and reports raw channel ids. Conversation channels are `"<parent> : <shortcutId>"`, and messaging apps' shortcut ids are often phone numbers or JIDs, so contacts of the kid in other apps reach logcat and the server. | `badges/NotificationRuleRuntime.kt:111` (log), :122 (report); server `kiosk_escapes.rs:120` | Report the part before `" : "` (or `parentChannelId`), else a hash; drop the channel from the log line |
| P-L2 | A `locate` or `ring` switches system Location on and leaves it on, so under `on_request` every app with location permission can use it afterwards. A `ring` also forces a fresh fix, although a ring isn't a request for the location. | `server/LocateCommands.kt:115-117`, `server/MdmSyncWorker.kt:195`, `server/AppEnforcer.kt:578` | Restore the previous Location state after the fix unless the policy needs it on; force a fix only for `locate` |
| P-L3 | The cached fix (`cachedLocationJson`) is never cleared, not even when the mode goes to `off`. A failed fresh fix falls back to it (a days-old fix can be uploaded), and interval mode re-sends the same fix on every sync, which the server stores as duplicate rows. | `server/LocateCommands.kt:156`, :177-197; server `device_api.rs:1356-1371` | Clear it when the mode is `off`; never report a cached fix older than the interval; the server skips an equal `captured_at` |
| P-L4 | Nearby Wi-Fi SSIDs (first 5 of every scan) and the joined SSID on failure are logged at INFO/WARN, and release keeps every `Log` call (no `-assumenosideeffects`). | `server/QuickControls.kt:400-404`, :469, :476; `launcher/app/build.gradle.kts:124` | Log counts only; optionally strip `Log.d`/`Log.i` in release |
| P-L5 | Credentials in CE prefs in the clear: the device token (`device_token`), and the **kid** PIN's PBKDF2 hash + salt in the cached policy (`redactedPolicy` strips only the parent PIN). With handy's lock replacing the Android credential, CE keys aren't bound to any user secret, so these are protected by the sandbox only. | `preferences/LauncherPreferences$Config.java:44`, `server/dto/PolicyResponse.kt:136-138`, `server/PinVault.kt:51` | Wrap the token with a Keystore AES key; vault the kid PIN with the parent PIN's HMAC scheme |

## Info

Security:

1. The exported components (`AndroidManifest.xml`) are as follows:
   - HomeActivity reads only `EXTRA_BROUGHT`.
   - SettingsActivity is PIN-gated at every onCreate/onStart.
   - PhoneBookActivity always confirms, and checks `decideOutgoing` while calls are managed.
   - PlayLinkBlockedActivity forwards a fresh intent with no extras or grants.
   - MissedCallReceiver relies on a protected broadcast (optional: `android:permission="android.permission.MODIFY_PHONE_STATE"` after a device check).
   - Every other component is guarded by a system bind permission or not exported.
   - PinLockActivity, LockActivity, VoipWakeActivity, InCallActivity, InstallModeReceiver, TorchOffReceiver, StopRingReceiver and WindowRelockReceiver are all not exported.
2. The debug hooks (LockTaskOverride, PinVaultKey, HeadsetDebug, LockUpDebug, KeyguardCoverDebug) live in `src/debug` only, are guarded by DUMP, and `checkReleaseHasNoDebugHook` scans the merged release manifest and dex. Release has pass-through stubs.
3. Every PendingIntent is explicit and `FLAG_IMMUTABLE`, except `AppInstaller.kt:106-121`/`:168-182`. Those are `FLAG_MUTABLE` with an explicit component, as PackageInstaller requires. Any app holding notification-listener access can fire our notification actions; all of them tighten except Answer, and the relock already reports foreign listeners.
4. Self-grants are limited to what the code uses and skip permissions already granted. The only grants to other apps are the call-permission denials and the music app's two grants (S-L8).
5. M10 (phone side) is implemented: `catalogApkCheck` before install, `setAppPackageName`, and no install without both pins. Self-update runs `pendingApkCheck` (size, SHA-256, package, signer, newer versionCode). Nit: `signerMatches` is true when our own signers can't be read (`server/SelfUpdatePlan.kt:138-139`). Android's same-signer rule still holds, but fail closed for consistency.
6. The music bridge is protected by a signature permission the launcher defines. A squatter can only make the launcher's install fail, which fails safe. Inputs are bounded (hash regex, ready set, `Long` ids), files go out as read-only file descriptors, and the Storytel login and Spotify token are memory only. The launcher never binds to the music app.
7. New lock surfaces checked, nothing found:
   - Glance rows open only after the right code, only the app's own activity intent (`intentFromApp`), never an action or reply.
   - The clock's Stopp is broadcast-only with background starts denied (`lock/ClockActions.kt`), and Fossify is trusted only with its pinned signer.
   - The music tile offers transport controls only.
   - The torch opens nothing, and its receiver is not exported.
   - The notice wake starts only our lock and wake activity, gated by the switch, a 30 s gap, 20 an hour and proximity.
   - Emergency goes only to `isLockTaskPermitted` system components.
   - Round 7 §4 removed the inherited app-details escape.
8. VPN TLS: system CAs only. DoH uses OkHttp's verifier, `NO_PROXY`, no redirects and protected sockets. DoT by IP verifies the IP. DoT by name relies on Conscrypt's `endpointIdentificationAlgorithm`; add an explicit hostname-verifier call as the IP branch has. Upstream failures fail closed per query, never falling back to plain or system DNS.
9. Outdated comments: `server/MdmSyncWorker.kt:79-82` says tsnet starts from `Application.onCreate`, and `server/PackageReplacedReceiver.kt:26-30` says only from Home, but the sync this receiver requests can start it. `launcher/app/build.gradle.kts:142` says "x86_64 only", but the aar is arm64-only. Dead code: `Functions.kt:122` `openInBrowser` (an implicit VIEW).

Privacy:

1. Design 51's "Show the text" ignores `VISIBILITY_PRIVATE` and the system's "hide sensitive content" setting (by design: the parent's switch decides; `badges/GlanceRows.kt:160-175`). Combined with round 7's notice wake, up to 200 characters of a message appear on a screen that lights up by itself. The PWA should say so next to the switch.
2. `time_state.zone` and `app_downloads.network = "roaming"` reveal coarse location and travel even with location "off". This is acceptable, but the parent-facing privacy text should mention it.
3. `call_state.last_error` carries a raw exception message (`server/AppEnforcer.kt:703`, :715; capped at 300 characters). `call_state.default_dialer`, `sms_restricted` and `outgoing_restricted` are never read by the server.
4. `installed_apps` is sent in full on every report; sending it on change would be enough.
5. By default every allowed DNS name goes to Cloudflare (DoT/DoH) together with the phone's public IP. Quad9 or the parent's own server are the alternatives.

## Tailscale / tsnet

Question: does anything of tsnet run on a phone with no auth key that never joined?

- **How the library loads.** gomobile's generated `tsembed.Tsembed`/`tsembed.Client` load `libgojni.so` (via
  `go.Seq`) in their static initializers. Those run only at the first static call or `new`, which happens at
  `Tsembed.new_` (`server/TsnetClient.kt:104`) and the `Client` calls after it (:108-110, :293). Nothing else calls
  `System.loadLibrary`.
- **`connectFromPreferences`** (`server/TsnetClient.kt:169-235`) reads the crash-guard prefs, then the key and
  `tsnet_node_joined`. `tailnetConnect` (`server/TailnetKey.kt:26-31`) returns `Nothing`, and it returns before the
  pending flag and `connect()`. When unconfigured it makes no writes.
- **Entry points:** none reaches `Tsembed` when unconfigured.

| Entry point | Unconfigured |
|---|---|
| Process start (`Application.initUnlocked`/`initRest`) | `PowerEvents.init` sets `TsnetClient.proxyListener`, Kotlin only |
| `HomeActivity.onResume` (`ui/HomeActivity.kt:468`) | `connectFromPreferences` on IO, returns at once |
| The sync (`server/MdmSyncWorker.kt:83`) | the same |
| `PackageReplacedReceiver` (through its sync request) | the same |
| `PowerEvents` (`server/PowerEvents.kt:280`, :306) | reads `proxyAddress`, null |
| The SSE client (`server/CommandListenerService.kt:102`) | `TsnetClient.proxy()`, null |
| `createMdmApi` (`server/MdmApi.kt:129`), `MusicServer` | `TsnetClient.proxy()`, null |
| Boot receivers, `:bootcover` | not touched |

- **No listener.** Configured and up, `Loopback()` opens one listener on `127.0.0.1:<random>` for SOCKS5 and the
  LocalAPI:
  - SOCKS5 takes user `tsnet` and the per-server random credential, kept in memory only (`TsnetClient.kt:78-80`,
    :280-288).
  - The LocalAPI credential never leaves Go.
  - Any app can connect to the port (loopback is shared), but without the 128-bit credential it gets nothing.
  - With the credential it would get the launcher's tailnet identity and network as a proxy. Info.
- **Crash-loop guard:** a committed pending flag around `connect`; 2 unfinished attempts within 15 min skip one
  launch. It never disables tsnet for good, and a skip leaves the sync on the direct path, so it can't brick the
  sync. Gaps: S-L10.
- **Key storage:** CE default prefs, deleted together with the joined mark after the first connect (M12). It is never
  logged or reported. The node state is in CE `filesDir/tailscale` and log upload is off.

**Recommendation: keep as is.** It is already dormant when unconfigured, with no class load. A build flavor would
double the release matrix and rework L13's `checkReleaseHasTsnet`, the build-tsnet action and the release workflow,
for APK size only. The `.so` is shipped and mapped from the APK but never run while unconfigured. The supply chain is
pinned (`go.sum`, `go mod verify`).

Do S-L10's three items. Consider a `noTsnet` release flavor only if APK size or the Go supply chain starts to matter.

## Privacy inventory

| Data | Read from | Kept where | Sent to the server | Needed? |
|---|---|---|---|---|
| Location (lat/lng, accuracy, time) | `LocationManager.getCurrentLocation`, then last known (`server/LocateCommands.kt:119-156`) | memory; last fix in CE prefs `cachedLocationJson` | status `location`, per the parent's policy | Yes, per policy. P-M1, P-L2, P-L3 |
| Curated contacts (name, number, Matrix ID, photo hash) | policy `call_policy` | CE cached policy; photos in CE files; **numbers only** in DE `boot_call_policy` | no | Yes. DE numbers are needed to screen before the first unlock |
| System contacts | none (`READ_CONTACTS` self-granted only so screening sees all numbers) | – | – | – |
| Call log | `calls/CallSystem.kt:117`, `calls/MissedCallsRepo.kt:114` (number, type, date, duration) | memory; emergency-window times in DE `call_boot_state`; missed-call marks in CE | `call_state.last_emergency_call_at`, `callback_window_until`, `call_log_readable` | Yes |
| SMS | not read (only the default SMS package) | – | `call_state.default_sms_package` | Yes (warnings) |
| Notification content | `GlanceReader` (ticked apps: sender, and text only with `show_text`; capped 60/200), `ClockReader` (Fossify, 3 channels), `ElementDmReader` (keys only), `VoipCallReader` (flags, intents) | glance and clock: **memory only**; Element MXID -> room in CE `element_rooms`; `voip_rang` (package, time) in CE | `notification_cancels` (package, channel, counts), `voip_rang`, `voip_fsi_denied`, `notification_listener_enabled` | Yes; the channel id is broader than needed (P-L1) |
| Installed apps | `controllablePackages`, labels, installer (`server/MdmSyncWorker.kt:921-943`) | memory | `installed_apps` on every report | Yes (the Apps list); on change would do |
| Screen time and rules | `ScreenTimeTracker`, `TimeRulesRuntime` | CE `time_rules_state`; no-calls windows in DE `boot_call_blocks` | `time_state` (incl. `zone`) | Yes; `zone` is coarse location (Info) |
| Wi-Fi scans, SSIDs, the password the kid types | `QuickControls.kt:322-462`, `WifiNetworksActivity` | memory and UI; the password goes straight into `WifiConfiguration`, never stored or logged | no | Yes; SSIDs logged (P-L4) |
| Wi-Fi proxies, user CAs (relock snapshot) | `WifiManager`, `KeyStore` | CE `window_relock` (SSID -> proxy, CA SHA-256) | `relock` report (counts, packages) | Yes |
| Bluetooth devices | bonded list, scan (`QuickControls.kt:558-694`) | memory | no | Yes; never logged |
| DNS queries | the tun (every app's UDP/53) | memory; blocked names only with `dns_log_enabled` (off by default), at most 200 in CE prefs; block list in CE `filesDir`; the parent's server names in CE `noBackupFilesDir/dns_bootstrap_cache.txt` | opt-in `POST dns-events {domain, category, blocked_at}`; `dns_upstream` (no address) | Yes; allowed names go encrypted to the chosen resolver |
| Battery, power on/off | sticky `ACTION_BATTERY_CHANGED`, `ACTION_SHUTDOWN`, `BOOT_COUNT` | DE `power_events`, `boot_witness` | `battery`, `POST /power`, `unseen_boots` | Yes |
| Music state, Sonos room names | the music app over the bridge | CE `MusicStore`, `filesDir/music` | `music_state` (allowlisted keys, `sonos_rooms`) | Yes |
| Storytel login, Spotify token | server endpoints | memory only, handed over the bridge | no | Yes |
| Network type, roaming | `ConnectivityManager` | memory | `app_downloads.network` | Yes; roaming hints at travel |
| Device settings (sound, DND, timeout, clock format, backup, roles, restrictions, kiosk, admin policies, wipe readiness, relock, foreign access, boot cover, fence, downloads) | DPM, Settings, `AudioManager` | various CE prefs | the matching status fields | Yes; `call_state.default_dialer`, `sms_restricted`, `outgoing_restricted` are unused by the server (Info) |
| Crashes | the uncaught throwable | DE `crash_reports`: class names and frames, **never messages** | `POST /crashes` | Yes |
| Parent PIN use | the PIN dialogs | CE `parent_pin_state` (queue) | `POST /pin-events` (kind, entry, times, counts; no PIN material) | Yes |
| Kid PIN state | the lock | CE `pin_lock_state`; hash + salt in the CE cached policy (P-L5) | `lock_state` (no unlock times, no PIN material) | Yes |
| Device token | the enroll response | CE default prefs, plaintext (P-L5) | the bearer header, same origin only (`server/ServerAuth.kt`) | Yes |
| Parent PIN hash | the policy | Keystore-HMAC tag + salt in CE `pin_vault`; the cached policy is redacted | – | Yes |
| Tailscale key, node | QR or Settings | CE; key deleted after joining; node state in CE `filesDir/tailscale` | no | Optional |
| Command results | – | CE `command_answers` outbox | `command-receipt`, `command-result` (locate: accuracy and age; a failed wipe: class + message) | Yes |
| IMEI, serial, own number, ANDROID_ID, accounts, usage stats, media | **not read** | – | – | – |

- **Everything in DE storage:** allowed numbers, calls on/off, country code (`boot_call_policy`); no-calls windows;
  emergency-window times; crash signatures; boot counts; power records; the Install Now record (ids, tag); the boot
  cover's flags. No names, tokens, PIN material or message text.
- **Backup:** `allowBackup="false"`, and both rule files exclude everything.
- **Logs:** no HTTP logging interceptor and no remote log upload. Logs carry no tokens, PINs, numbers, names, message
  text or locations; the exceptions are SSIDs (P-L4) and channel ids (P-L1).

## Earlier review: launcher items re-checked

| ID (2026-10-10) | Status on `95afa2ea` |
|---|---|
| H3 (parent PIN lockout) | Fixed (design 24): one gate, the try committed before PBKDF2 (210,000 rounds), escalating lockout to 24 h, constant-time compare |
| H4 (safe mode) | Open by decision: `DISALLOW_SAFE_BOOT` still defaults off (`server/Hardening.kt:25-27`) until device check 6. Note that safe mode also stops the VPN, so an allowlisted system browser browses unfiltered; unseen boots are reported (design 24b S3) |
| M8 (wipe) | Fixed (`wipeDevice(0)`); emulator check §6g still open |
| M9 (override opens everything) | Fixed as decided (design 24b relock) |
| M10 (catalog APKs) | Fixed, phone side checked (Info 5) |
| M11 (cleartext, fallback) | **Open**: S-M1 |
| M12 (Tailscale key) | Fixed on the phone (key deleted after joining); the console part is the user's |
| L6 (PIN hashes) | Parent PIN fixed (vault); the kid PIN stays as decided, see P-L5 |
| L7, L8, L12, L13, L9 (Gradle) | Fixed; L8's same-origin bearer and L13's release check verified |
| Accepted: own DoH bypasses the filter | Re-raised as S-M3 |
| Accepted: self-managed VoIP isn't screened | Unchanged, widened by round 7 to every allowed app; see S-M4 |
