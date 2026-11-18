# Security and privacy review: the Android apps (2026-11-17)

Scope: `launcher/` (Vibb One, home app + Device Owner) and `music/` (Vibb music) at `cb71e028`. The commits after it
up to `658045917` touch only `PLAN.md` and the server review, so the line numbers hold there too. The focus is what came
after the last reviews:
- `security-review-2026-10-21-launcher.md` at `95afa2ea`: 479 launcher commits since then;
- `security-review-2026-10-21-music.md` at `ff61b0aa`: 53 music commits since then.

New surfaces covered:
- the UnifiedPush distributor (108, 108b, 108c);
- the SMS/MMS engine and Meldinger (77, 80, 81, 82, 83);
- contact requests and app logos (87);
- the server issuer pin and its recovery address (76);
- the Wi-Fi sign-in window and Chrome as the login browser (102);
- Flymodus through the assistant role (98) and the parent code's rule pause (98 §4.4);
- notification channels (107);
- calendar feeds and the kid's CalDAV events (72, 72b);
- the lock glance;
- the VPN sink, Bare Vibb and roaming (94, 95, 97);
- self-update, the update fence and the setup QR.

The open items of the 10-21 reviews are re-checked at the end. The server is
`security-review-2026-11-17-server.md` (cited as "server ..."). Findings whose server half needs a look say so.

Read-only. No code was changed, Gradle was not run, and nothing ran on an emulator or a phone.

Paths:
- Plain paths are relative to `launcher/app/src/main/java/com/kidslauncher/mdm/`. The launcher has its own `server/`
  and `music/` packages, so plain `server/...` and `music/...` are launcher code.
- `M/` is the music app, `music/app/src/main/java/me/vibb/music/`.
- `S/` is the Rust server, `server/` at the repo root.
- Other paths start at the repo root (`launcher/...`, `docs/...`).

Android 14-17: no finding below differs by version unless it says so.

## Summary

| Severity | Security | Privacy |
|---|---|---|
| High | 0 | 0 |
| Medium | 3 (S-M1 to S-M3) | 1 (P-M1) |
| Low | 7 | 1 |
| Info | 12 | 6 |

There are no High findings. The new surfaces are built defensively:
- **Identity checks use Android's identity.** UnifiedPush trusts `sentFromPackage`, never the extras.
- **PendingIntents.** Every one is explicit. The mutable ones are what the platform requires: PackageInstaller,
  RemoteInput, the proximity alert and SMS sent/delivered.
- **Isolation.** MMS PDUs are parsed in an isolated process.
- **The server transport.** It is one fenced, pinned client. Redirects are off, the bearer goes only to the same
  origin, and the pin can be switched off only over a connection that passes it.
- **Message text** never leaves the phone.
- **Debug hooks.** Every one, including `MmsDebugReceiver` and `UpDebugReceiver`, is caught by the release build's
  `CheckDebugHook`.

The four Mediums are not new code. Three are 10-21 items that are still open, and one is an item whose last step was
never built:
- **S-M1:** the phone half of server M1. The launcher still fetches the family's Storytel password and passes it on.
- **S-M2:** the parent still isn't told when the content filter is down. This is partly fixed.
- **S-M3:** the planted Sonos speaker in the music app.
- **P-M1:** location still falls back to a fix every 10 minutes under a fallback policy.

**Not on PLAN's queue** (checked 2026-11-17):
- P-M1;
- music S-M1 (here S-M3);
- design 58 step 3 (S-M1).

All three were proposed or approved on 2026-10-21/22 and have slipped off the list. S-M2's remaining work sits in
PLAN's C2 ("filter on, tunnel down").

| ID | Severity | Finding |
|---|---|---|
| S-M1 | Medium (security + privacy) | The launcher still fetches the family's Storytel e-mail and password and hands them over the bridge (design 58 step 3 never built; the phone half of server M1) |
| S-M2 | Medium | The parent is still not told when the filter is down; a failed `establish()` of the filter isn't retried (10-21 S-M2, partly fixed) |
| S-M3 | Medium (security + privacy) | Music: a planted Sonos speaker, from any LAN device at any address, is never forgotten (10-21 music S-M1, unchanged) |
| P-M1 | Medium (privacy) | A fallback policy still means a fresh fix every 10 minutes, whatever the parent chose (10-21 P-M1, unchanged) |
| S-L1 | Low | A setup QR whose enrolment fails still re-points the phone, turns the pin off and sends the old bearer to the new host |
| S-L2 | Low | The Wi-Fi sign-in window reaches any number of sites on a network built for it, not "one site" |
| S-L3 | Low | Under Bare Vibb or "I utlandet", a filter-only phone can still share mobile data through the hotspot |
| S-L4 | Low | With Spotify on, every Spotify host is unblockable for every app, browsers included |
| S-L5 | Low | A browser's built-in proxy "VPN" (Opera) goes around the DNS filter (needs a device check) |
| S-L6 | Low | Encrypted DNS outside the browsers' managed switch still goes around the filter (10-21 S-M3, partly fixed, lowered) |
| S-L7 | Low | Music: the web player's CSP allows any `https:`/`wss:` host (10-21 music S-L3, unchanged) |
| P-L1 | Low (privacy) | The calendar report sends calendar display names, which are often e-mail addresses, the kid's and other people's |

## Medium

### S-M1. The Storytel password still reaches the phone (phone half of server M1)

- **Evidence:**
  - `music/MusicBridgeService.kt:263-286` (`answerStorytel`) calls `GET /api/devices/music/storytel`
    (`music/MusicServer.kt:59-60`, `:96`). It parses `email` and `password` and calls
    `callback.onLogin(generation, email, password)` over the bridge (`:286`).
  - The music app no longer asks. Design 58 step 2 moved it to a JWT courier, and `M/bridge/StorytelLegacy.kt` purges
    the old sealed file and key. So the phone no longer *stores* the password, but the whole path is still live on both
    sides.
  - The server still serves it to any device token (server M1: `S/src/handlers/music_api.rs:233-293`,
    `S/src/device_routes.rs:139`).
  - Design 58 step 3, the retirement (`docs/design/58-storytel-jwt-courier.md:304-319`), was planned in
    `docs/plan/plan-archive.md:799`. It is in neither PLAN's queue nor the code.
- **Risk:** anyone with a device token (a lost phone, a leaked token, a music app build that still asks) gets the
  family's Storytel login in clear.
- **Fix:** one commit with server M1. `answerStorytel` always answers generation 0, and `MusicServer.storytel()` goes
  (`music/MusicBridgeService.kt`, `music/MusicServer.kt`). Size S. Not visible.

### S-M2. The parent is still not told when the filter is down (10-21 S-M2, partly fixed)

- **Fixed since 10-21:**
  - `dns_upstream` is left out while the tunnel is down (`server/KidVpnService.kt:621-622`;
    `server/dto/StatusReportRequest.kt:96-100`).
  - A dead read loop retries itself (`server/VpnForeground.kt:103-114`).
- **Still open:**
  - The report has no filter-state field. The server only sees `dns_upstream` disappear. Server half: check whether
    anything reads a missing `dns_upstream` as "down".
  - A failed `establish()` is retried only toward the sink (`server/MobileNetwork.kt:241-242`,
    `retriesFailedEstablish`). A failed switch to the filter shape (`server/KidVpnService.kt:224`) stays down until
    the next `apply()`.
  - `onRevoke` stops the service until the next `apply()` (`server/KidVpnService.kt:573-584`).
  - The retry still runs on the main `Handler`'s uptime clock, which stops in deep sleep (`:489`).
- **Risk:** as on 10-21. Lockdown is off in the filter shape, so while the tunnel is down every app's DNS is
  unfiltered, and the parent sees nothing. It ends at the next SSE event or the 30-minute backstop, later in Doze.
- **Fix:** retry every failed `establish()`, not only the sink's, on an `ELAPSED_REALTIME` alarm
  (`server/MobileNetwork.kt`, `server/KidVpnService.kt`). Send a `vpn_filter {state, since_ms}` field, with a server
  event after N minutes down (PLAN C2). Size M. Visible to the parent (a new Attention row).

### S-M3. Music: a planted Sonos speaker (10-21 music S-M1, unchanged)

- **Evidence:**
  - `M/sonos/Ssdp.kt:42-49` still takes any reply whose `SERVER` contains "Sonos". It takes the reply's `LOCATION`
    literal on any port and never compares it with the sender (`:93` drops `packet.address`).
  - `M/sonos/Topology.kt:62-66` takes any non-empty uid, without the `RINCON_` shape, and `:73-80` any IP literal.
  - Players are merged and never pruned (`M/sonos/SpeakerCache.kt:102`). They are asked in uid order (`:294`), and
    `round()` has no total budget (`:350-361`).
- **Mitigated since 10-21:**
  - Round M2's S-L1 caps a SOAP reply at 1 MiB with a deadline.
  - P-L2 reports only the home household.
  - The persistence and the public-address reach are unchanged.
- **Risk:** as on 10-21. A device on a hostile Wi-Fi plants a "room" at a public address. The phone then asks that
  address at every sheet opening, from home too. A play there hands over the item's URL, including a Storytel minted
  URL with its token.
- **Android 17:** once `targetSdk` reaches 37, the local-network permission will gate SSDP. Today it is 36.
- **Fix:** do 10-21's steps 1-4. Accept a reply only when the sender equals `LOCATION` and is in `speakerLiteral`'s
  ranges, accept only `RINCON_[0-9A-F]{12,20}` uids, and tie the cache to the network and prune it
  (`M/sonos/Ssdp.kt`, `Topology.kt`, `SpeakerCache.kt`, `Soap.kt`). Size M. Barely visible: a friend's speakers drop
  out of the kid's sheet.

## Medium: privacy

### P-M1. A fallback policy still means a fix every 10 minutes (10-21 P-M1, unchanged)

- **Evidence:**
  - `server/MdmSyncWorker.kt:301`: `decision.policy?.let { it.locationPolicy ?: LEGACY_LOCATION_POLICY }`.
  - `LastEnforcedPlan` (`server/PolicyGate.kt:142-196`) still carries no `locationPolicy`. So a `Fallback` becomes
    `LEGACY_LOCATION_POLICY`, an interval of 10 minutes (`server/LocationPolicies.kt:40`).
  - A parent's "Off" no longer clears the cached fix under a fallback (`server/MdmSyncWorker.kt:303`).
  - Round L4b's P-L2 now switches Location back off after each fix (`server/LocateCommands.kt:141-148`). That limits
    other apps, not the tracking.
  - Zones are safe: `zones/ZoneWatch.kt:81-90` needs a real `locationPolicy`.
- **Risk:** a corrupt or missing cache together with a refused, undecodable or 5xx policy turns "off" or "on request"
  into tracking every 10 minutes. The server is reachable in those cases, so the fixes are uploaded.
- **Fix:** carry `locationPolicy` in `LastEnforcedPlan`, written and read like `hardening`. With nothing known, use
  `on_request`, never the legacy interval (`server/PolicyGate.kt`, `server/MdmSyncWorker.kt`). Size S. Not visible.
- **Fixed 2026-11-18** (`sec-location-fallback`): C1 had already made "nothing known" `on_request`
  (`LocationPolicy()`), but a fallback still lost the parent's choice: "Off" became "on request" (a `locate` answered
  with a fix, the shutdown and low-battery fixes sent, the cached fix kept, Location not forced off) and "Regularly"
  lost its zones. `LastEnforcedPlan` now carries `locationPolicy` (zones included), and one pure
  `enforcedLocationPolicy` (`server/LocationPolicies.kt`) is what the sync, the backstop, the shutdown's position and
  the Wi-Fi page read. A plan written before the fix and `NOTHING_ALLOWED_FALLBACK` read as `on_request`; a phone that
  never had a policy has none. `LocationPolicyTest` covers each path. No server change: the server always sends
  `location_policy`.

## Low: security

| ID | Finding | Evidence | Fix (file, size, visible?) |
|---|---|---|---|
| S-L1 | **A failed setup-QR enrolment still re-points the phone.** The QR's URL is stored, the pin flag turned off, the recovery address cleared and the tailnet key stored, all before `Enrollment.enroll`. Nothing is rolled back on failure (an expired code, no answer). `Enrollment` replaces the token only on success, so the next sync and SSE send the **old** server's bearer to the QR's host, unpinned. Needs the parent PIN (the in-app scan is in Settings), so this is a parent's accident: a stale QR, another phone's QR, a typo. | `server/Provisioning.kt:36-55`; `server/Enrollment.kt:35-39`; `ui/settings/launcher/SettingsFragmentLauncher.kt:983` | Keep the old URL, flag, recovery and key; enrol against the QR's URL with an unpinned client; commit the new values only on success (`server/Provisioning.kt`, `server/Enrollment.kt`). S. Not visible |
| S-L2 | **The sign-in window reaches more than "one site".** A page move with no gesture from a portal site may go anywhere (`PAGE`), and so may a main-frame POST and its redirect hops. The target then joins the portal set. A travel router or laptop hotspot with a form that posts to any URL is therefore a general browser for 10 minutes per opening, limited by the filter's lists. With the code switched off (the parent's choice) that is 20 minutes a day, under a time rule too. Design 102 §7 and D3 say the residue is one site. The parent sees up to 20 domains. | `portal/PortalRules.kt:143-151`, `:161`, `:165`; `portal/PortalWindowActivity.kt:444`; `docs/design/102-captive-portals-and-logins.md:827` | Cap a session at 3 portal sites and 5 distinct sites; add rows to `login_rules.json`; correct 102 §7/D3 (`portal/PortalRules.kt`). S. Visible only on odd multi-domain portals |
| S-L3 | **Tethering under Bare Vibb on a filter-only phone.** `restrictTethering` follows the allowlist only. Hotspot traffic never passes the VPN, so the sink doesn't cover it. A phone with the filter but no allowlist, under "I utlandet: Bare Vibb" or the parent's Bare Vibb, shares mobile data (roaming data abroad) through the hotspot. "Ingen data" is safe (`DISALLOW_DATA_ROAMING`). | `server/EnforcementPlan.kt:293` | `restrictTethering = appsManaged \|\| vpnInputs.anyArmed`, unless lifted (`server/EnforcementPlan.kt`). S. Visible to the kid (the Hotspot tile greys out) |
| S-L4 | **Spotify hosts are open to every app.** While `music.spotify` is on, `classifyDomain` exempts every subdomain of `spotify.com`, `scdn.co`, `spotifycdn.*` and `pscdn.co` before any list, for every query. In an allowed browser the kid reaches open.spotify.com's catalogue and podcasts, and a parent's block can't stop it. | `server/DnsFilterEngine.kt:70`; `music/SpotifyHosts.kt:22-27` | Apply the exemption only when `ConnectivityManager.getConnectionOwnerUid` of the query is the music app's uid (`server/KidVpnService.kt`, `server/DnsFilterEngine.kt`). M. Visible to the kid only if the parent blocks Spotify |
| S-L5 | **A browser's proxy "VPN" goes around the filter (device check needed).** Opera is on `BROWSER_DOH_PACKAGES`, but only `DnsOverHttpsMode` is pushed. Opera for Android's free VPN is an in-browser proxy, not a `VpnService`, so `DISALLOW_CONFIG_VPN` doesn't stop it, and names resolve at the proxy. If the parent allows Opera, it is one switch, the same class as 10-21 S-M3. | `server/BrowserConfig.kt:36`; `launcher/app/src/main/assets/dns/doh_hosts.txt` (no proxy hosts) | Capture Opera's proxy hosts and add them to `doh_hosts.txt` and `S/testdata/doh_hosts.txt` in one commit, or mark such browsers "not filtered" in the PWA. S. Visible to the kid (Opera's VPN fails) |
| S-L6 | **Encrypted DNS outside the managed switch (10-21 S-M3, partly fixed).** Design 67 built the managed config for 12 browsers, the built-in DoH host list, Firefox's canary and DDR as NODATA. Still around the filter, because the tun routes only `192.0.2.2/32`: DoT/DoQ on 853 to public resolver IPs, IP-literal DoH templates, Firefox's "Max protection" with an unlisted host, and apps with hard-coded resolvers. That needs a specific app or setting, so it is lowered to Low. | `server/BrowserConfig.kt:28-47`; `server/DnsFilterEngine.kt:71`, `:135-141`; `server/KidVpnService.kt:253`, `:519-520` | 10-21's step 3: route the public resolvers' IPs into the tun and drop 853/443/QUIC to them; answer UDP/53 as the original destination (`server/KidVpnService.kt`). M. Visible only to apps that use them |
| S-L7 | **Music: the web player's CSP is wider than its host list (10-21 music S-L3, unchanged).** Still `connect-src https: wss:`. It waits for the DNS capture. | `M/spotify/WebPolicy.kt:125`; `music/app/src/main/assets/spotify/player.html:11` | Generate `connect-src`/`media-src`/`img-src` from `SpotifyHosts`, plus `base-uri 'none'; form-action 'none'` (`M/spotify/WebPolicy.kt`, `player.html`). S. Not visible |

## Low: privacy

| ID | Finding | Evidence | Fix (file, size, visible?) |
|---|---|---|---|
| P-L1 | **Calendar names that are e-mail addresses.** The status report sends the display name of every syncing calendar on every account, cut to 100 characters. A Google account's main calendar is named after its address. Calendars others share carry their names or addresses. So the kid's Google address and other people's are stored on the parent's server. `day/CalendarKey.kt:7-9` says the account name never leaves the phone. | `calendar/CalendarStore.kt:412-414` | When the display name equals the account name or looks like an e-mail, send it masked (`k…@gmail.com`) or the account type's label (`calendar/CalendarStore.kt`). S. Visible to the parent (labels in the calendar picker) |

## Info

Security:

1. **UnifiedPush wakes apps a time rule suspended.** This was decided (design 108 §5.7, Q5 "deliver"). A suspended
   app counts as installed (`push/up/UpDelivery.kt:40`). Its RAISE_TO_FOREGROUND service is bound with
   `BIND_AUTO_CREATE` (`push/up/UpRuntime.kt:445-447`), and the broadcasts carry `FLAG_INCLUDE_STOPPED_PACKAGES`
   (`:371`, `:473`, `:482`).
   - The app gets about 5 s to sync at bedtime.
   - It gets no activity start: no `BIND_ALLOW_ACTIVITY_STARTS`, and suspension and lock task hold.
   - Whether it may start a foreground service in that window is the open per-version gate check (14-17).
   - Optional fix: skip the bind while `isPackageSuspended` (`push/up/UpRuntime.kt`). S. The user's call (it changes
     Q5).
2. **MESSAGE_ACK flood.** The ack is accepted on its token alone, with one phone-wide limit of 30 reads a minute
   (`push/up/UpRules.kt:219-220`, `push/up/UpEngine.kt:280-286`).
   - A hostile allowed app can starve other apps' ordinary acks.
   - It can't get a working app unregistered. Every ping and its daily resend re-add the ping's id to the exempt set
     (`push/up/UpEngine.kt:427-428`, `UpRules.PING_RESEND_MS`), so the answer gets through.
   - No fix needed.
3. **Meldinger's compose entry is open to any app and to web links.** `ComposeActivity` takes SEND, SENDTO and VIEW of
   `sms:`, `smsto:`, `mms:` and `mmsto:`, BROWSABLE (`sms/SmsReceivers.kt:220-239`).
   - Every recipient goes through `SmsRules.decideSend` (`sms/engine/SmsRules.kt:141-153`).
   - The draft is capped, and nothing is sent without the kid's tap.
   - `EXTRA_STREAM` is never read, so there is no confused deputy.
   - What remains: a pre-filled text to a known contact (social engineering), and a weak oracle for "is this number a
     texting contact".
   - Optional: drop the draft text unless the caller is a system app or an allowed browser (`sms/SmsReceivers.kt`).
     S. Visible.
4. **The issuer pin pins roots, not keys** (`server/ServerPins.kt:32-41`). Any Let's Encrypt or Sectigo certificate
   for the name passes, and a passing connection may send `enforce: false` (`:96-100`). This is inherent to design 76
   D1.
   - Fix: recommend a CAA record with `accounturi` in `server/DEPLOY.md`. S. Not visible.
   - Server half: the auto-switch-on after 72 h (`S/src/server_tls.rs:34`).
5. **10-21 Info 5's nit is still there.** `signerMatches` is true when our own signers can't be read
   (`server/SelfUpdatePlan.kt:202-203`, `server/SelfUpdate.kt:151-161`). The platform's same-signer rule still holds.
   - Fix: fail closed when `ourSigners` is empty (`server/SelfUpdatePlan.kt`). S. Not visible.
6. **Cleartext is allowed app-wide again** (`launcher/app/src/main/res/xml/network_security_config.xml`, design 102
   D15, the user's decision of 2026-11-15). The sign-in window needs http.
   - S-M1's 2026-11-04 rule now rests on code alone: `ServerTransport.kt:127-147` refuses a plain-http server address
     before a socket or the bearer.
   - `ServerTransportTest`'s source scan fails the build on any new HTTP client.
   - Re-check this whenever a new HTTP library comes in. Nothing to fix now.
7. **The VPN service isn't direct-boot aware** (`launcher/app/src/main/AndroidManifest.xml:972-976`).
   - Before the first unlock after a reboot, direct-boot apps (Play services) resolve unfiltered in the filter shape.
   - A persisted sink keeps Android's lockdown, so Bare Vibb holds.
8. **A calendar link's document is read whole, with no size cap** (`calendar/CalendarFeeds.kt:128`). It trusts the
   server's limit. Server half: check that limit.
   - Fix: read at most the server's cap (`calendar/CalendarFeeds.kt`). S. Not visible.
9. **New phones still start in Vibb's own lock.** The server's `kid_keyguard` defaults to `'own'`
   (`S/migrations/0085_kid_keyguard.sql:12`), and clearing the Child PIN sets it back.
   - In own mode Android's keyguard is set to "None" (`lock/KeyguardCoverPlan.kt:21`), CE storage isn't bound to a
     user secret, and 10-21 S-M4 still applies.
   - Phase D (PLAN C3) removes own mode. Until then a parent who never switches gets the weaker lock. Server half.
10. **10-21 S-M4 (a VoIP call over the lock).** In Android-lock mode, Android's keyguard holds. Only the call app's
    own `showWhenLocked` screen shows, and anything it opens behind (WhatsApp's arrow into its chats) lands on the
    PIN. This was not re-tested on a device here. It stays open for own mode until phase D.
11. **Music, still open from 10-21:**
    - Info 3: Storytel's minted URL may be `http` (`M/storytel/StorytelApi.kt:140-144`). Fix: require https in
      release. S.
    - Info 5: NRK's `programId` and serial are inlined unescaped (`M/sonos/NrkService.kt:58-59`). Fix: validate them
      as `[A-Za-z0-9_-]+`. S.
    - Info 9: `checkReleaseHasNoDebugFeed` hangs on `assemble*` only (`music/app/build.gradle.kts:208`, `:216`). Fix:
      also on `bundle*`. S.
    - None of these is visible.
12. **Notification Reply** is a `FLAG_MUTABLE` PendingIntent for `RemoteInput` (`sms/SmsNotifier.kt:296-305`).
    - The ids are in the data URI, which a fill-in can't change (`sms/SmsReceivers.kt:172-174`).
    - Every send passes the send check.
    - Only Vibb and the system assistant hold the notification. Nothing to fix.

Privacy:

1. **Message text on the lock.** At the parent's "Full text" level (default Off, `S/src/lock_glance.rs:112-117`),
   the glance shows message text to whoever holds the locked phone (`sms/SmsGlance.kt:46-60`).
   - Android's "hide sensitive content" setting is not consulted. This is by design; 10-21 privacy Info 1 still
     applies.
   - Fix: the PWA's (i) next to "Full text" says the text shows on the locked screen. S. Visible.
2. **Calendar on the lock.** With the parent's `glance_next` on (default off, `day/CalendarPolicy.kt:56`), the locked
   glance shows the next event's title and time (`day/Reminders.kt:90-95`). A finder learns where the kid will be.
   - Fix: the PWA's (i) says so. S. Visible.
3. **The sign-in window's WebView keeps cookies, DOM storage and cache across openings, indefinitely**
   (`portal/PortalWindowActivity.kt:54`, `:329`, `:339`). Nothing in `portal/` clears them. A hotel portal's session
   or an SSO cookie stays and is sent again later.
   - Fix: at `end()`, `CookieManager.removeAllCookies`, `WebStorage.deleteAllData` and `clearCache`
     (`portal/PortalWindowActivity.kt`). S. Not visible.
4. **The sign-in check asks the family server over the hotspot** when only one of Google and Cloudflare answers 204
   (`portal/PortalCheck.kt:52`). The hotspot sees the server's name (DNS and SNI). No bearer is sent.
   - Fix: accept it, or require both public checks (`portal/PortalCheck.kt`). S. Barely visible.
5. **"Lagre" puts a received picture in shared storage** (`ui/messages/PictureShare.kt:28`, `:41-68`,
   `Pictures/Vibb One`). Any app with READ_MEDIA_IMAGES, or a photo backup, gets it. EXIF is stripped and the name is
   random. It is the kid's action.
   - Fix: note it in the privacy notes, or put it behind a parent switch. S. Visible.
6. **Music:** Spotify's diagnostic lines (release, 15 min) go to logcat only, in fixed shapes (`M/spotify/WebPolicy.kt`
   `diagLine`). Clean.

## What looked clean

UnifiedPush (108, 108b, 108c):
- Only `sentFromPackage` is trusted (`push/up/UpReceiver.kt:97`; API 34+, so on all of 14-17). The `pi` and
  `application` extras are never trusted (`:44-66`).
- A token held by another package is refused (`push/up/UpRules.kt:147`). UNREGISTER needs the owner and the token
  (`:210-211`).
- Every answer is `setPackage(pkg)` (`push/up/UpRuntime.kt:371`, `:470-482`).
- `UpReceiver` and `UpLinkActivity` are installed disabled and enabled only while the policy has push on (manifest
  `:686-702`). `UpLinkActivity` answers only a `callingPackage` that may register (`push/up/UpLinkActivity.kt:24-26`).
- Caps:
  - 100 registrations per app and 300 per phone;
  - 20 new ones an hour per app and 60 per phone (`push/up/UpRules.kt:67-109`);
  - the USER_PRESENT receiver takes a protected broadcast only (`push/up/UpRuntime.kt:146`).
- Registrations are sealed under the Keystore key (`push/up/UpStore.kt`). No message waits on the phone.

SMS/MMS (77, 80, 81, 83):
- Every receiving component is permission-guarded (manifest `:759-785`).
- RespondViaMessage and Reply go through the send check (`sms/engine/SmsEngine.kt:531-560`).
- Pictures:
  - only from the system photo picker, pinned to the system package (`ui/pictures/PicturePick.kt:52-62`);
  - `file://` and our own providers refused (`ui/messages/MessagesModel.kt:840-846`).
- `MmsFileProvider` (`sms/mms/MmsFileProvider.kt:65-70`, `:156-161`, `:199-207`):
  - not exported;
  - `in/` write-only, `out/` read-only;
  - fixed name shapes;
  - downloads cut at their cap;
  - grants revoked.
- `MmsGate` checks before any fetch (`sms/engine/MmsGate.kt:31-126`):
  - the sender shape;
  - the operator's exact MMSC host list, with no userinfo and no loopback;
  - the size;
  - the retrieved sender equals the notified one.
- PDUs are parsed only in the isolated `:mmsparse` process, size-capped, with at most 4 pictures of 8 MP
  (`sms/parse/MmsParseService.kt:39-97`, `ParseWire.kt:75-100`).
- Storage:
  - CE `messages.db` with `secure_delete`, bound arguments only (`sms/store/SqliteMessageStore.kt:549-561`);
  - a held body (at most 1600 characters) is wiped when it ends (`sms/engine/SmsEngine.kt:405`, `:431`, `:457`).
- The server gets sender, class, times and state for held messages, never text (`sms/engine/SmsEngine.kt:466-474`).
- Logs carry no body or number. One parse warning logs `e.message` from the isolated parser
  (`sms/parse/MmsParser.kt:216`); it is protocol text, not content.
- In Android-lock mode, Android's lock screen shows no notifications
  (`KEYGUARD_DISABLE_SECURE_NOTIFICATIONS`, `lock/AndroidLockPlan.kt:24`). Ours are `VISIBILITY_PRIVATE` with a
  public "New message" version (`sms/SmsNotifier.kt:151-152`).

Debug-only parts:
- All six debug receivers are DUMP-guarded and in `src/debug` only (`launcher/app/src/debug/AndroidManifest.xml`).
- `CheckDebugHook` fails `assembleRelease` on any of their names in the merged manifest or the dex
  (`launcher/app/build.gradle.kts:313-317`).
- `src/release/.../sms/MmsDebug.kt` is an empty stub.
- No reflection reaches debug classes.

Transport and pins (76):
- Every server client is built from `ServerClients.base` (`server/ServerTransport.kt:222-235`): the API, SSE, music,
  downloads, logos, feeds, weather, UP and the rule pause.
- No TrustManager or HostnameVerifier override exists.
- Redirects are off (`:182-183`, `:323-324`). The origin fence throws before a socket (`:326-330`, `:372-378`).
- The pin turns off only over PASSED or TAILNET (`server/ServerPins.kt:96-100`). A flag change rebuilds the pool.
- `recovery_url` is taken only over such a connection and only as tailnet http (`:176-189`). It is only displayed,
  never applied.

Self-update and catalog:
- APKs are kept in `noBackupFilesDir` (`server/AppDownloads.kt:195`, `server/SelfUpdate.kt:166-169`).
- The checks run in this order: hash, package and signer, then a session pinned with `setAppPackageName`
  (`server/AppInstaller.kt:79`).
- The two PackageInstaller PendingIntents are mutable but explicit, to non-exported receivers.

Setup QR:
- The address passes the shared rule (`server/Provisioning.kt:31`).
- The tailnet key is deleted once joined, and the enrolment code is not kept.
- The in-app scan is behind the Settings parent-PIN gate. The provisioning activities need `BIND_DEVICE_ADMIN`.

App logos (87):
- Drawn from resources only, size-capped, OOM caught (`appdisplay/AppLogos.kt:124-141`).
- Only hashes the phone drew are uploaded (`:156-173`).

Sign-in window and Chrome (102):
- `CaptivePortalEntry` needs `NETWORK_STACK` and is enabled only with the feature on.
- The parent code, lockout and budget are checked in the main process (`portal/CaptivePortalEntry.kt:101-121`).
- The WebView:
  - no JS interface, no file or content access;
  - no multiple windows, downloads or file chooser;
  - permissions and geolocation denied;
  - TLS errors final;
  - the filter's lists on every request (`portal/PortalWindowActivity.kt:233-237`, `:326-470`).
- Chrome as the login browser gets `URLBlocklist ["*","chrome://*"]` plus exact hosts, with incognito, downloads,
  devtools and search off (`logins/LoginBrowser.kt:34-60`).

Flymodus (98):
- `VibbRecognizer` is exported without a permission, but it is a stub that answers `ERROR_CLIENT` and reads nothing
  (`assistant/VibbRecognizer.kt`).
- The session acts only on a one-use 128-bit nonce (`assistant/AirplaneRoute.kt:157-162`, `:288-292`), with no assist
  data.
- `supportsLaunchVoiceAssistFromKeyguard="false"`.
- Settings is unhidden for at most a 1 s window (`assistant/AirplaneRoute.kt:42`).
- Every airplane period is reported.

Rule pause (98 §4.4):
- It goes through `ParentPin.check` with the shared lockout (`server/ParentPin.kt:60-102`).
- Each lift is bounded by the elapsed clock and the boot count (`timerules/RulePause.kt:172-182`).
- The server can end a pause but can't extend it.

Other exported components:
- `SettingsSinkActivity` reads only the action and finishes (`server/SettingsSinkActivity.kt:20-26`).
- `PhoneBookEntryActivity` passes on a cleaned ask only, and the page checks the number
  (`calls/PhoneBookEntryActivity.kt:20-38`, `calls/PhoneBookAsk.kt:38-44`).
- `TimeRuleReceiver` ignores the boundary action, so 10-21 S-L7 is fixed (`timerules/TimeRuleAlarm.kt:80-105`).

Runtime receivers:
- Every `RECEIVER_EXPORTED` registration takes protected system broadcasts only:
  - Bluetooth (`ui/quickcontrols/BluetoothDevicesActivity.kt:379`, `server/QuickControls.kt:735`);
  - airplane mode (`assistant/AirplaneRuntime.kt:333`);
  - ECBM, sticky read only (`server/SelfUpdate.kt:263`, `assistant/AirplaneRuntime.kt:101`);
  - managed profile (`Application.kt:442`);
  - USER_PRESENT (`push/up/UpRuntime.kt:146`);
  - the channel block states (`notify/NotificationChannels.kt:197`).
- The rest are `NOT_EXPORTED`.

PendingIntents:
- 23 are `FLAG_IMMUTABLE`.
- The mutable ones:
  - `server/AppInstaller.kt:116`, `:174` (PackageInstaller);
  - `zones/ZoneFences.kt:45` (proximity alert, explicit to a non-exported receiver);
  - `sms/SmsReceivers.kt:157`, `:163`, `sms/mms/MmsRuntime.kt:369` (sent/delivered/MMS result, explicit);
  - `sms/SmsNotifier.kt:299` (RemoteInput).

Snarveier (99): the shade panel's actions re-check the mask, the keyguard and the lock at the tap
(`shortcuts/ShortcutsRuntime.kt:362-373`). Flymodus turns on only through its own window (`:392-397`).

Notification channels (107): only Vibb One's own channels, as fixed keys (`notify/NotificationChannels.kt:93-115`).

Bare Vibb and roaming (94, 95, 97):
- The sink routes `0.0.0.0/0` and `::/0` with lockdown and an empty allowlist (`server/MobileNetwork.kt:320-324`).
- It fails closed even when `establish()` fails (`:237-243`).
- `DISALLOW_DATA_ROAMING`, `DISALLOW_CONFIG_MOBILE_NETWORKS` and `DISALLOW_CONFIG_VPN` are set with any VPN shape.
- The fallback plan keeps these choices (`server/PolicyGate.kt:160-173`).
- An OEM's own mobile-data tile on the Jelly Star is still to be checked on the device.

Storage:
- The device token, the kid PIN's hash and salt, the reset-password token and the UP registrations are sealed
  (`server/SealedPrefs.kt`, `secrets_v1`), so 10-21 P-L5 is fixed.
- Device-protected storage holds no names, text or tokens: airplane and assistant windows, time requests, boot
  blocks, crash signatures.

Logs: no message text, numbers, SSIDs or raw conversation channel ids. 10-21 P-L1 and P-L4 hold
(`badges/NotificationRuleRuntime.kt:190-193`).

Music: the exported components are the same as on 10-21. The Storytel JWT is in memory only. Round M2's S-L1, S-L2,
S-L4, S-L5, P-L1, P-L2 and P-L3 are all still in place.

## Privacy inventory: what is new since 10-21

| Data | Read from | Kept where | Sent to the server | Needed? |
|---|---|---|---|---|
| SMS/MMS text, pictures (re-encoded, no EXIF), addresses | SMS_DELIVER, MMS download | CE `messages.db` (`secure_delete`), `filesDir/sms/pictures` | no | Yes |
| Held strangers' messages (body at most 1600 characters) | the same | CE db, at most 24 h (7 days for an MMS in transit); body wiped | sender, class, times, state; never text | Yes |
| Blocked senders | the same | CE db, at most 30 days | rows | Yes (the parent's log) |
| Own numbers, SIM operator, unlisted MMSC hosts | SIM, messages | CE db | `sms_state` | Yes |
| Contact request (name, number, Matrix ID, photo) | the kid's form | CE prefs and files until settled | yes | Yes |
| Lock glance message text | the engine | memory | no | Parent's choice (default Off) |
| UP registrations (package, token, secret, endpoint, VAPID key) | connector REGISTER | sealed CE prefs | reg, secret, package, VAPID key (no token) | Yes |
| UP message bodies | server pull | memory only | pulled (Matrix bodies cut to ids on the server) | Yes |
| Calendar list | CalendarContract, all accounts | memory | key hash, account type, **display name** (P-L1), source host, access level | Mask e-mails |
| Calendar events | CalendarContract | memory (LRU) | only "Min kalender"'s, while a CalDAV calendar is marked | Yes |
| TLS sightings (host, anchor CN + SPKI, network type, time) | handshakes | prefs, at most 8 | `server_tls` | Yes |
| Portal sessions (SSID, first host, up to 20 domains, times, outcome) | sign-in window | `noBackupFilesDir`, last 10 | `captive_portal` | Yes |
| Portal WebView cookies and storage | sign-in window | `app_webview_portal`, indefinitely | no | No (privacy Info 3) |
| App logos (128 px PNG) | PackageManager resources | `noBackupFilesDir/app_logos` | only hashes the server asks for | Cosmetic |
| Airplane periods (at most 10, 7 days) | airplane runtime | DE prefs | `airplane_mode` | Yes |
| Rule pauses | rule pause | local | rule-pause POST, pin events | Yes |
| `data_roaming`, `mobile_data` | telephony | memory | country and network only while roaming or abroad | Yes |
| Notification channels | our own NotificationManager | no | our own channel keys | Yes |
| Storytel password | server | not stored | flows server -> launcher -> bridge if asked (S-M1) | No: remove |

Everything else is as in the 10-21 inventory. Location is unchanged except for P-M1 and round L4b's P-L2/P-L3.

## Earlier reviews: items re-checked

Launcher (10-21):

| ID | Status on `cb71e028` |
|---|---|
| S-M1 (cleartext, https) | Fixed 2026-11-04. App-wide cleartext came back with design 102 D15; the server rule holds in code (Info 6) |
| S-M2 (filter down, parent not told) | Partly fixed; still Medium here (S-M2) |
| S-M3 (secure DNS in browsers) | Partly fixed by design 67; the rest is S-L6, plus S-L5 |
| S-M4 (VoIP over the lock) | Holds in Android-lock mode by Android's rules (not device-tested); open in own mode until phase D (Info 9, 10) |
| P-M1 (location fallback) | Open (P-M1 here) |
| S-L1 to S-L3, S-L5 to S-L11 | Fixed (rounds L4 and L4b); spot-checked: S-L6's 1 h cap (`lock/PinBackoff.kt:24`), S-L7 (`timerules/TimeRuleAlarm.kt:99-105`) |
| S-L4 (gms/vending channels) | Waits for a device capture of the channel ids (PLAN, round L4b) |
| P-L1 to P-L5 | Fixed (P-L5 by `SealedPrefs`) |
| Info 5 (`signerMatches`) | Open (Info 5 here) |

Music (10-21):

| ID | Status |
|---|---|
| S-M1 (planted speaker) | Open (S-M3 here) |
| S-L1, S-L2, S-L4, S-L5, P-L1, P-L2, P-L3 | Fixed (round M2) and still in place |
| S-L3 (CSP) | Open (S-L7 here) |
| P-L4 (test data) | Done 2026-10-21 |
| Info 3, 5, 9 | Open (Info 11 here) |
| Info 8 (Storytel debug base) | Fixed by design 58 (no login on the phone) |
| Privacy Info 2 / server M3 (Storytel login) | Partly: not stored any more, still served and forwarded (S-M1) |

## Fixes in priority order

"Visible" means the parent or the kid would notice the change. Those are for the user to decide.

1. **S-M1:** retire the Storytel login fetch, together with server M1 (`music/MusicBridgeService.kt`,
   `music/MusicServer.kt` + server). S. Not visible.
2. **P-M1:** `locationPolicy` in `LastEnforcedPlan`, `on_request` when unknown (`server/PolicyGate.kt`,
   `server/MdmSyncWorker.kt`). S. Not visible.
3. **S-M2:** retry every failed `establish()` on an elapsed-time alarm, plus a `vpn_filter` field and server event
   (PLAN C2) (`server/MobileNetwork.kt`, `server/KidVpnService.kt` + server). M. **Visible** to the parent.
4. **S-M3:** Sonos SSDP sender = LOCATION, LAN only, `RINCON_` uids, the cache tied to the network and pruned
   (`M/sonos/Ssdp.kt`, `Topology.kt`, `SpeakerCache.kt`, `Soap.kt`). M. Barely visible.
5. **S-L1:** commit a setup QR's values only after a successful enrolment (`server/Provisioning.kt`,
   `server/Enrollment.kt`). S. Not visible.
6. **S-L3:** restrict tethering whenever a sink reason is armed (`server/EnforcementPlan.kt`). S. **Visible** to the
   kid.
7. **S-L2:** cap the sign-in window's sites per session (`portal/PortalRules.kt`). S. Rarely visible.
8. **P-L1:** mask calendar names that are e-mails (`calendar/CalendarStore.kt`). S. **Visible** to the parent.
9. **Privacy Info 3:** clear the portal WebView's cookies, storage and cache at the window's end
   (`portal/PortalWindowActivity.kt`). S. Not visible.
10. **S-L5:** Opera's proxy hosts into `doh_hosts.txt` (both copies), after a device capture. S. **Visible** to the
    kid.
11. **S-L4:** the Spotify exemption only for the music app's uid (`server/KidVpnService.kt`,
    `server/DnsFilterEngine.kt`). M. **Visible** to the kid if the parent blocks Spotify.
12. **S-L6:** the public resolvers' IPs into the tun (`server/KidVpnService.kt`). M. Visible only to apps that use
    them.
13. **S-L7:** the web player's CSP from `SpotifyHosts`, after the DNS capture (`M/spotify/WebPolicy.kt`,
    `player.html`). S. Not visible.
14. **Small Infos, each S, none visible:**
    - `signerMatches` fails closed (`server/SelfUpdatePlan.kt`);
    - a cap on the feed read (`calendar/CalendarFeeds.kt`);
    - Storytel's minted URL https-only (`M/storytel/StorytelApi.kt`);
    - NRK id validation (`M/sonos/NrkService.kt`);
    - the bundle debug check (`music/app/build.gradle.kts`);
    - a CAA note (`server/DEPLOY.md`).
15. **Copy, S, visible:** the PWA's (i) for "Full text" and "Neste" on the lock (privacy Info 1 and 2).
16. **Decisions for the user:** UP wakes suspended apps (Info 1); drafts from links (Info 3); "Lagre" to shared
    storage (privacy Info 5); the own-mode default (Info 9, phase D).
