# FluffyChat vs Element X as Vibb One's Matrix app (2026-10-26)

Research only, nothing built. Sources read as code, not blog posts:

- FluffyChat `main` [`85efb515`][fc] (2026-10-25, = v2.11.3, Flutter 3.47.8), `pubspec.lock`: matrix 14.x, flutter_vodozemac 0.8.1,
  flutter_callkit_incoming 3.1.6, flutter_local_notifications 22.3.x, unifiedpush 6.2, livekit_client 2.13.
- matrix-dart-sdk [`d5881af7`][sdk] (v14.2.0, 2026-10-20).
- flutter_callkit_incoming [`27cd33c1`][ck] (3.1.6), flutter_local_notifications [`65fce50d`][fln].
- Element X Android `main` [`4b62a99d`][ex] (26.10.1, 2026-10-23); its call findings from design 57 / PLAN "S-M4".
- GitHub issues (state on 2026-10-26), the F-Droid metadata ([fdroiddata `chat.fluffy.fluffychat.yml`][fdroid]).

Package `chat.fluffy.fluffychat`. Licence AGPL-3.0-or-later (we only install it, no linking: no issue).

## Summary

| | FluffyChat 2.11.3 | Element X 26.10.1 |
|---|---|---|
| Crypto primitives | vodozemac (Rust) via flutter_vodozemac since v2.0.0 (2025-06); libolm gone from app and SDK | vodozemac inside matrix-sdk-crypto (Rust) |
| Protocol layer (key sharing, device lists, backup, SSSS, verification) | Dart, famedly's matrix-dart-sdk | Rust matrix-sdk-crypto, shared by all Element X/Web clients |
| Verification | emoji + numbers; **no QR**; first-run backup/verification **skippable** | emoji + QR; first run **must** verify (other device / recovery key / reset); no skip |
| Key backup default | bootstrap screen after every login, skippable with a warning | auto-enabled backup + cross-signing |
| Keys to unsigned devices | default `ShareKeysWith.all`; verified-only modes exist (Settings > Security) | default shares too (`ERROR_ON_VERIFIED_USER_PROBLEM`); "exclude insecure devices" is a dev flag, default off |
| Open UTD / backup bugs | several open, incl. a backup data-loss report (#3593) | fewer, different class (not audited here) |
| Calls | MatrixRTC + LiveKit, **native** (livekit_client), since v2.10.0 (2026-10-02); legacy 1:1 VoIP removed | Element Call WebView (`ElementCallActivity`); native MatrixRTC behind a dev flag |
| Ring over lock | CallKit plugin: FSI to `CallkitIncomingActivity` (showWhenLocked, turnScreenOn), incoming **CallStyle** (API 34+), **self-managed Telecom call** | FSI to `IncomingCallActivity`, incoming CallStyle, no Telecom |
| Ongoing call notification | FGS, category `call`, **plain "Hang up" action** (not CallStyle) | FGS, plain, **no hang-up** |
| Call screen | an overlay inside `MainActivity` (same activity as the chats), movable to a 256 px tile, no PiP | own task (`io.element.android.features.call`), PiP or hang-up |
| Message notification | MessagingStyle, one per room, own user + sender `Person` keys = MXIDs, `shortcutId` = room id, **tag null** | MessagingStyle, one per room, same keys, **tag** = room id, `shortcutId` = `session-room` |
| Push | FCM via push.fluffychat.im (Play/GitHub APK) or UnifiedPush (auto-detects ntfy's own Matrix gateway); F-Droid build UP only; no background polling | FCM via Element's gateway or UnifiedPush |
| Per-room nick/avatar | shows others'; `/myroomnick` + `/myroomavatar <mxc>` work; own room nick leaks into settings (#1179) | shows others'; `/myroomnick` only behind the dev flag `feature.slash_command` (off), `/myroomavatar` "Not yet implemented" |
| Lockdown | managed config (since 2.10.0) sets **defaults** of any app setting; no switch for room creation, invites, directory | none relevant |
| Third parties by default | push.fluffychat.im (FCM builds), OSM tiles when viewing a location, homeserver list from GitHub at sign-in; no Giphy, no link previews, no analytics; crash reports opt-in | Element's push gateway (FCM build), analytics opt-in |
| Maintainers | effectively one (Christian Kußowski: ~830 of ~880 code commits in 12 months); SDK has a few famedly devs | Element, a team |
| **Verdict** | not usable as the default today: calls are three weeks old with open audio/hang-up/interop bugs, and the call screen is the chat screen (design 57's rule can't fence it) | **stay**; re-test FluffyChat after a few releases, on the device, never as a silent swap |

## 1. Encryption

**Crypto library.** matrix-dart-sdk 1.0.0 (2025-06-10) "Migrate to Vodozemac" / "Remove olm dependency"
([SDK CHANGELOG][sdk-cl], "LibOlm deprecated in favor of Vodozemac"); FluffyChat v2.0.0 (2025-06-20) "migrates to Vodozemac
and Matrix Dart SDK 1.0.0" ([FC CHANGELOG][fc-cl]). Today: `flutter_vodozemac: ^0.8.1` ([pubspec][fc-pubspec]),
the SDK on `vodozemac: ^0.9.0` (14.2.0). No `olm` package in either `pubspec.lock`/`lib/` (grep, both trees). So the
libolm deprecation (2024, timing side channels) no longer applies. What remains Dart is everything above the primitives:
Megolm session sharing, device-list tracking, key requests, online key backup, SSSS, dehydrated devices
(`enableDehydratedDevices: true`, [client_manager.dart][fc-cm]) and verification. Element X runs the same primitive
(vodozemac) inside matrix-sdk-crypto, the Rust state machine every current Element client shares - many more users
and maintainers on the protocol layer, which is where Matrix's real-world bugs have been.

**Verification.** FluffyChat enables only `KeyVerificationMethod.numbers` and `.emoji`
([client_manager.dart L123-127][fc-cm]); the SDK knows `qrShow`/`qrScan`/`reciprocate`
([key_verification.dart L121][sdk-kv]) but FluffyChat doesn't offer them. Cross-signing is supported (self-sign, user
verification; "display verified icon next to verified rooms and users" in v2.10.0). Since 2026-05-25 a chat shows an
"unverified devices in the chat" line and a trust dialog ([`b852d4b7`][fc-b852]); users asked to hide it
([#3185][i3185], [#3140][i3140]).

**Key backup.** Every login routes to `/backup` (SSSS + cross-signing + online backup;
[login.dart L90][fc-login], [matrix.dart L168][fc-matrix]); the screen has "Skip chat backup" with a warning
([bootstrap_page.dart L56][fc-boot]). The maintainer on 2026-06-29/30 ([#3185][i3185]): "In the future the chat backup
will be mandatory anyway and unsigned devices will be ignored" and "This will be the default very soon together with
making chat backup mandatory" - not done in 2.11.3 (`shareKeysWith` still defaults to `all`,
[setting_keys.dart L48][fc-sk]).

**Who gets the room keys.** `ShareKeysWith` ([client.dart L4340-4359][sdk-skw]): `all` (default: every device not
explicitly blocked), `crossVerifiedIfEnabled`, `crossVerified`, `directlyVerifiedOnly` - so a verified-only mode exists,
user-selectable (Settings > Security) and settable as a default by managed config (§5). Incoming key requests are
answered for our own verified devices or for devices that were already allowed that session
([key_manager.dart L1029-1055][sdk-km], [matrix.dart L223-235][fc-matrix-krq]) - sane.
Element X ([RustMatrixClientFactory.kt L169-186][ex-rmcf]): `autoEnableBackups`/`autoEnableCrossSigning`,
`roomKeyRecipientStrategy = ERROR_ON_VERIFIED_USER_PROBLEM` (shares with unsigned devices of unverified users, refuses
to send when a verified user has unsigned devices or changed identity) and `IDENTITY_BASED_STRATEGY` +
`CROSS_SIGNED_OR_LEGACY` decryption only under `feature.onlySignedDeviceIsolationMode`, default false
([FeatureFlags.kt L39-46][ex-ff]). First run: only "another device", "recovery key" or sign-out/reset
(`ChooseSelfVerificationModeState`, [choosemode/][ex-ftue]) - no skip. Element X also shares history on invite
(`enableShareHistoryOnInvite(true)`, MSC4268); the Dart SDK has no MSC4268 (grep). "Invisible crypto" (exclude insecure
devices by default) is therefore still a flag in Element X too: in Oct 2026 neither app excludes unsigned devices by
default.

**UTD / backup history.** Open: [#963][i963] "Chat backup is broken on android" (2024), [#866][i866] "Unable to open
SSSS with recovery key", [#2871][i2871] "Chat backup keys never work!" (2.5.0), [#3047][i3047] backup with recovery key
not possible, [#3593][i3593] "[DATA LOSS] backup keys with new session" (2.9.0: enabling backup after a failed
verification silently replaces the old backup), [#2818][i2818] attachment UTD (2.5.1), [#3189][i3189] UTD on redacted
events, [#3762][i3762] cross-verification with Fractal fails. [#1209][i1209] "Unable to decrypt previously encrypted chat"
was closed by the stale bot, not fixed. Recent SDK fixes in the right area: 14.0.0 "decrypt stored events in
getEventById when the keys arrived later", "send the join key to the participants who just joined"; 14.1.0 "compare new
inbound sessions with the stored one"; 9.0.0 "always fetch member list from server for first megolm session"
([SDK CHANGELOG][sdk-cl]).

**Verdict (encryption).** Not materially weaker in cryptography: same primitive, same Megolm/Olm, and on a family
homeserver the server operator is the parent, who is trusted anyway. Weaker in three concrete ways: (1) reliability -
the Dart protocol layer has more open UTD/backup bugs, one with data loss, and a kid's single phone has no second device
to recover from; (2) defaults - backup and verification are skippable, no QR, `share keys with: all`; (3) less
review: one app maintainer plus a few SDK developers vs. the shared Rust crate. (2) is fixable by us at provisioning
(set up backup ourselves, `shareKeysWith=crossVerified` as a managed default, §5); (1) and (3) aren't.

## 2. Calls

**What exists.** v2.10.0 (2026-10-02, "MatrixRTC calls"): "refactor: remove legacy calls" [`c83cd72a`][fc-c83c],
"implement livekit MVP" [`e1ca8ae5`][fc-e1ca], "call in a popup" [`b185e3cd`][fc-b185], "send rtc notification for new
call" [`71ffc728`][fc-71ff], "Implement callkit for livekit calls" [`9e593cdf`][fc-9e59] (all 2026-08-20..22). So:
no `m.call.*` 1:1 VoIP any more; group and 1:1 calls are MatrixRTC (`org.matrix.msc3401.call.member`,
[matrix_live_kit_call_member.dart L11][fc-lkm]) on a LiveKit SFU found via `/_matrix/client/unstable/org.matrix.msc4143/rtc/transports`
or `.well-known` `org.matrix.msc4143.rtc_foci` ([matrix_live_kit_call.dart L17-52][fc-lkc]), media E2EE keys as encrypted
to-device `io.element.call.encryption_keys` ([L232][fc-lkc-keys]), ring as `org.matrix.msc4075.rtc.notification`
([rtc_notification_content.dart][sdk-rtc]). **Native, not a widget/WebView** - livekit_client in Dart; this is Element
Call's protocol, not Element Call itself. Our homeserver already serves the LiveKit backend for Element X, so no new
server part. Fallback SFU: empty by default since 2.11.2 ("testserver is accidentally preconfigured as a livekit
fallback" - 2.11.0/2.11.1 shipped a third-party default).

**Quality today.** Open: [#3857][i3857] "No audio in calls" (Android+iOS, 2.11.1), [#3800][i3800] "Call does not end
locally after the other party hangs up" (Android F-Droid 2.10.0), [#3773][i3773] no call buttons with a custom
homeserver port, several "[Error ...] Unable to connect" reports ([#3841][i3841], [#3798][i3798], [#3743][i3743]);
[#1310][i1310] (2026-10-07): a FluffyChat (Linux) call rings on Element X and can be answered, "but no call audio/video is
exchanged". Interop with our parents' Element X is unproven.

**Android incoming call** (flutter_callkit_incoming 3.1.6, as FluffyChat calls it: `isFullScreen` and
`isCustomNotification` left false, [call_kit_params.dart][fc-ckp]):

- Push helper sees a `ring` rtc notification and calls `showCallkitIncoming` ([push_helper.dart L176-180, L491-545][fc-push-call]);
  it then polls `oneShotSync` until the caller leaves (ends the ring), we joined, or we joined on another device.
- The plugin registers a **self-managed Telecom** `PhoneAccount` and `addNewIncomingCall`s every ring
  (`MANAGE_OWN_CALLS`; [CallkitIncomingBroadcastReceiver.kt L117-152][ck-rcv], [CallkitConnectionService.kt][ck-cs],
  added in 3.1.0, PR #809).
- Notification: channel `callkit_incoming_channel_id_v2`, `CATEGORY_CALL`, `PRIORITY_MAX`, ongoing, timeout = ring
  lifetime, **full-screen intent** to `com.hiennv.flutter_callkit_incoming.CallkitIncomingActivity`
  ([CallkitNotificationManager.kt L191-212][ck-nm-in]); on API 34+ `CallStyle.forIncomingCall(person(name = room name, no
  key/URI), decline, accept)`, below 34 title + plain Accept/Decline actions ([L320-355][ck-nm-cs]).
- `CallkitIncomingActivity`: `showWhenLocked`, `turnScreenOn`, `singleInstance`, `taskAffinity=""`, excluded from recents
  ([plugin manifest][ck-man]). So it rings on a locked phone, provided `USE_FULL_SCREEN_INTENT` is granted (design 17's
  `FSI_DENIED` path covers the rest).
- Accept: broadcast -> the plugin starts the app, Dart gets `CallEventActionCallAccept` and joins
  ([chat_list.dart L111-136, L418-430][fc-cl-ev]).

**Android ongoing call.** `CallkitNotificationService` (`foregroundServiceType="phoneCall|microphone|camera"`) posts on
`callkit_ongoing_channel_id`, `CATEGORY_CALL` (S+), ongoing; with `isCustomNotification` false it is **not** CallStyle:
title = room name, text = "Voice call"/"Video call", one plain action "Hang up" (FluffyChat's `l10n.hangUp`) whose
intent is a broadcast `ACTION_CALL_ENDED` ([L605-620, L753-798][ck-nm-on]). That broadcast reaches Dart as
`CallEventActionCallEnded`, which clears `activeCallRoomId` - the overlay's `CallPage` is removed and its dispose
disconnects LiveKit and leaves the MatrixRTC call ([chat_list.dart L116-121][fc-cl-ev], [call_overlay.dart][fc-ovl],
[call_view_model.dart L455-475][fc-cvm]). (CallStyle `forOngoingCall` with `hangUpIntent` only with
`isCustomNotification: true` on API 34+, [L631-648][ck-nm-on].) A Telecom-side disconnect only ends the Telecom
connection, not the LiveKit call (`onDisconnect` -> `finishWithCause`, no event to Dart; [CallkitConnection.kt L107-111,
L308-321][ck-conn]).

**Call screen, background, PiP.** The call is an overlay inside `MainActivity`, full screen or a 256x256 tile over the
chats ([call_overlay.dart][fc-ovl]); there is no separate activity and no `supportsPictureInPicture`. `MainActivity` has
`showOnLockScreen="false"` (deprecated attribute) and `turnScreenOn="true"`, no `showWhenLocked`
([AndroidManifest.xml L37-50][fc-man]). The call runs while the app is in the background (FGS above; v2.11.0 "do not
destroy flutter engine on back button"), audio only, no window.

**What this means for Vibb One.** Better hooks than Element X: a real hang-up action on the in-call notice, an incoming
CallStyle with answer/decline extras, `CATEGORY_CALL` on both (our `voipNoticeKind` classifies both without a channel
rule: ring = `call` + FSI, in-call = FGS + `call`, [VoipCallPlan.kt L130-145][vh-vcp]). But design 57's rule - "only the
call's own activity over the lock" - **cannot fence FluffyChat**: after the answer the trusted screen would be
`chat.fluffy.fluffychat.MainActivity`, which is also every chat, and the kid can shrink the call to a tile and browse.
Only variant E fits (call behind the lock, our in-call bar, hang up through the notice's action). Also: the call is a
self-managed Telecom call, so `TelecomManager.isInCall` is true during it (`LauncherLocales.callActive` reads it;
`isInManagedCall`, used by the lock and `RefrontPolicy`, stays false), and an `InCallService` with
`android.telecom.INCLUDE_SELF_MANAGED_CALLS` would see its state.

## 3. Per-room display name and avatar

- **matrix-dart-sdk**: no `Room.setOwnDisplayName`-style API, but built-in commands `/myroomnick` and `/myroomavatar`
  copy our `m.room.member` content and `setRoomStateWithKey` it with a new `displayname` / `avatar_url`
  ([commands_extension.dart L314-355][sdk-cmd]). Display: senders are `User`s built from the room's member state, so
  names and avatars are per-room everywhere, notifications included (`senderFromMemoryOrFallback.calcDisplayname()`,
  [push_helper.dart L222][fc-push-msg]).
- **FluffyChat**: commands parsed by default (`parseCommands` true, [chat.dart L761-789][fc-chat]) with hints
  "Set your display name for this room" / "Set your picture for this room (by mxc-uri)" ([command_hints.dart L56-59][fc-ch]);
  no UI besides the command, `/myroomavatar` needs an `mxc://` URI. Bug: our own room nick/avatar shows as the global one in
  settings and menu ([#1179][i1179], open since 1.21.0).
- **Element X**: shows per-room member names (Rust SDK). Setting: `/myroomnick` (`setOwnMemberDisplayName`) exists but
  slash commands are behind `feature.slash_command`, default false, not finished ([FeatureFlags.kt L112-117][ex-ff-sc]);
  `/myroomavatar` returns "Not yet implemented" ([CommandExecutor.kt L174-188][ex-cmd]).
- For us: if the family wants "Mamma" in the kid's DM, the parent sets it from Element Web (or FluffyChat); both kid apps
  show it. The server could also do it (the launcher never sends Matrix events).

## 4. Notifications on Android

**Push delivery.** The pusher is registered with `format: event_id_only` ([setting_keys.dart L34-37][fc-sk-push],
[background_push.dart L236-255][fc-bp-pusher]) plus `client_name` and `data_message: true` (Android). Gateway:

- FCM builds (GitHub APK, Play: `scripts/add-firebase-messaging.sh` uncomments `//<GOOGLE_SERVICES>` in
  [release.yaml L80-83][fc-rel]): `https://push.fluffychat.im/_matrix/push/v1/notify` (the maintainer's gateway), then Google.
- UnifiedPush when a distributor is installed (`setupPush`, [L266-296][fc-bp-up]): it probes the distributor's host for
  `/_matrix/push/v1/notify` answering `{"gateway":"matrix"}` (ntfy does) and uses it, else the public
  `matrix.gateway.unifiedpush.org` ([L376-412][fc-bp-upep]). A self-hosted ntfy keeps the whole path in the family.
- F-Droid build: no FCM code at all; without a distributor there is no push (a "no Google services" warning), and there is
  no periodic background sync anywhere.

The body FluffyChat receives (FCM data or the UP message, `json['notification']`, [L426-446][fc-bp-onup]) is the
homeserver's push-gateway body for `event_id_only`:
`{event_id, room_id, counts{unread}, prio, devices[{app_id, pushkey, data{format, client_name, data_message}}]}` - no
`sender`, `sender_display_name`, `room_name`, `type` or `content`. The model would take them
([push_notification.dart][sdk-pn]) and `getEventByPushNotification` would use them, but here it fetches the event
(`GET /rooms/{room}/event/{id}`, else `/notifications`), loads the sender's member state, decrypts locally (one
`oneShotSync` retry when keys are missing) ([client.dart L1843-1990][sdk-gebpn]). So message text and names come from
the homeserver, decrypted on the phone; the gateway and Google see ids and counts only. Same as Element X
(`PushFormat.EVENT_ID_ONLY`, [RustPushersService.kt L40][ex-push]).

**The posted notification** ([push_helper.dart L190-380][fc-push-msg]; flutter_local_notifications maps it 1:1 onto
`NotificationCompat`, [FlutterLocalNotificationsPlugin.java L480, L1295-1350, L1470-1480][fln-java]). Side by side with
what `ElementDmReader`/`GlanceReader` read from Element X today
([NotificationCreator.kt L106-112, L160-230, L444-560][ex-nc]):

| Field | FluffyChat | Element X |
|---|---|---|
| Channel | `fluffychat_push` for every message (it also creates an unused channel per room id, group `directChats`/`groupChats`) | per session, noisy/silent |
| One per room | yes: id = `"<clientName>_<roomId>".hashCode`, new messages appended to the active MessagingStyle | yes |
| `sbn.tag` | **null** | room id (`!room:server`, `+ "|" + thread` for threads) |
| `shortcutId` | **room id** (`!room:server`), set on every message; a conversation shortcut is pushed on the first | `"<sessionId>-<roomId>"` (not for threads) |
| Style | MessagingStyle | MessagingStyle |
| `android.messagingUser` | Person(name = own display name, icon = own avatar bitmap, **key = own MXID**) | Person(name, icon, **key = own MXID**) |
| `isGroupConversation` | `!room.isDirectChat` (m.direct-based) | `!isDm` (or thread) |
| Conversation title | room name for groups, null for DMs | room name |
| Message `sender_person` | Person(name = sender's per-room display name, **key = sender MXID**, icon = sender avatar bitmap - **in a DM the room's avatar**, `bot` for notices); no URI | Person(name, icon, **key = sender MXID**, or `mention-or-reply:<eventId>` for mentions/replies); no URI |
| Message text | decrypted plain body; "New message in FluffyChat" if still encrypted | decrypted body; image messages carry data URI |
| Title/text extras | null on Android (style provides them) | style provides them |
| Room id elsewhere | `payload` `"<client>|<room>|<event>"` in the content intent's extras (opaque PendingIntent: not readable by a listener) | content intent (opaque) |
| Group summary | yes: id `clientName.hashCode`, `groupKey` = client name, InboxStyle of bodies, silent | yes, per session |
| Actions | Reply (RemoteInput), Mark as read, Mute; for a non-ring call event "Enter call" | Mark as read, Quick reply |
| Category | `message` | `message`; `missed_call` for a missed call |
| Failure fallback | title "New message in FluffyChat", body "Open app to read messages", no style ([L72-98][fc-push-fb]) | fallback notification |
| Push work notice | a low-priority FGS notice on `fluffychat_sync` ("Loading messages") while the push isolate runs | `fetch_push_notification_channel` FGS |

Calls: ring and in-call notices are the CallKit plugin's (§2: `callkit_incoming_channel_id_v2` / `callkit_ongoing_channel_id`,
person name = room name, no key); a missed ring posts on `callkit_missed_channel_id` with `CATEGORY_MISSED_CALL` and a
"Call back" action ([L435-586][ck-nm-mi]) - our `missed_call` category rule ([NotificationRule.kt L55][vh-nr]) counts it
unchanged. A non-ring rtc notification is a normal message notification with an "Enter call" action.

**Can the launcher learn contact -> room and show name/photo?** Yes, with the same four facts design 15 reads, one
swapped: the room comes from `sbn.notification.shortcutId` instead of `sbn.tag`. Own MXID = `messagingUser` key, sender
MXID = each bundle's `sender_person` key, DM/group = `isGroupConversation`. `GlanceReader`'s sender names and texts work
unchanged (MessagingStyle bundles); the contact photo needs a `chat.fluffy.fluffychat` branch in `contactOf`. The
Message button can open the learned room with `matrix:roomid/<id without !>` (FluffyChat 2.11.0 routes it straight to
`/rooms/!id`, [fluffy_chat_app.dart L61-76][fc-app]); `matrix:u/@user` opens a profile dialog with "Send a message", as
in Element X. Device checks: whether the second message keeps `isGroupConversation` and the keys (the style is rebuilt
from the active notification by `getActiveNotificationMessagingStyle`), and whether DMs Element X created carry
`m.direct` for FluffyChat (else a DM reads as a group).

## 5. Kid-suitability

- **Lockdown**: no settings to hide public-room join, room creation, invites or spaces. Stories were removed in 2023
  ([`895de76e`][fc-895d]). No Giphy/Tenor (stickers and emotes are Matrix packs, `im.ponies.*`), no link previews
  ([#3075][i3075] open). The chat-list search queries the homeserver's room directory - on a family server with
  federation off and nothing published it is empty; invites from strangers likewise only exist with federation. The
  real lockdown is the homeserver's config, for either app.
- **Managed config** (v2.10.0, [`34f6c14f`][fc-34f6]): `AppSettings.defaultValue` returns
  `ManagedConfigurations().getManagedConfigurations[<enum name>]` when its type matches
  ([setting_keys.dart L97-101, L139-143][fc-sk-mdm]). Only `defaultHomeserver`, `presetHomeserver`, `welcomeText` are
  declared in [`app_restrictions.xml`][fc-ar], but the lookup is by any setting's name, so a Device Owner's
  `setApplicationRestrictions` could also preset e.g. `shareKeysWith`, `pushNotificationsGatewayUrl`,
  `autoSendErrorReports`, `sendPublicReadReceipts`, `fallbackLiveKitInstance`, `applicationName` - as defaults only: a
  value the user saved wins. `presetHomeserver` skips the server picker. Device check: undeclared keys arrive.
- **Telemetry / external calls**: no analytics. Crash reports to `crash.fluffy.chat` only if `autoSendErrorReports` is
  true (default null = off; v2.10.0 "add automatic crash report sharing", [error_reporter.dart L36-41][fc-er]). Update
  check only on Windows ([show_update_snackbar.dart L29][fc-upd]). Default homeserver `matrix.org`, homeserver list from
  `raw.githubusercontent.com` at sign-in ([app_config.dart L56-60][fc-ac]), push.fluffychat.im (FCM builds),
  `matrix.gateway.unifiedpush.org` (UP without a self-hosted gateway), `{a,b,c}.tile.openstreetmap.org` when a location
  message is shown ([map_bubble.dart L49-55][fc-map]), ko-fi.com links in help. Emoji picker is local.
- **Android**: `minSdk = flutter.minSdkVersion` (Flutter's default, not pinned; irrelevant on the Jelly Star's Android 16);
  `compileSdk 37`. Permissions include location, camera, mic, `MANAGE_OWN_CALLS`, `USE_FULL_SCREEN_INTENT`.
- **F-Droid**: in the main repo, 2.11.3 (versionCode 3574) built by F-Droid with a pinned Rust 1.98.0 for vodozemac; no
  `AllowedAPKSigningKeys`, i.e. F-Droid-signed, not reproducible against the developer's APK ([fdroiddata][fdroid]).
  Its build has no FCM. The GitHub `fluffychat.apk` and Play builds include FCM.
- **Cadence and bus factor**: v2.8.0 07-18, v2.9.0 08-15, v2.10.0 10-02, v2.11.0 10-18, v2.11.3 10-24 - monthly-ish with
  hotfixes; issues are auto-closed after a year of silence. Of ~880 non-translation, non-bot commits in the last 12 months,
  ~830 are by Christian Kußowski (also "krille-chan"); the next contributor has 9. The SDK (famedly) adds a handful of
  developers. Bus factor 1.

## 6. Verdict for Vibb One

**(a) Encryption.** Not a cryptographic downgrade: vodozemac on both sides, libolm gone, and on our own homeserver the
operator is the parent. The real gap is robustness and defaults: a Dart protocol layer with more open UTD/backup bugs
(one data-loss report), skippable backup and verification, no QR, `share keys with: all`. On a kid's only device a broken
backup means lost history with no second device to repair from. We could close the default gap at provisioning (backup
set up by the parent, `shareKeysWith=crossVerified` as a managed default), not the robustness gap.

**(b) Calls and the lock.** On paper FluffyChat is easier than Element X: incoming CallStyle with answer/decline extras,
`CATEGORY_CALL` ring with FSI and a show-when-locked ring screen, a self-managed Telecom call, and an in-call notice with
a real hang-up action and a clean end (notice removed, Telecom disconnected). In practice: (1) the answered call lives in
`MainActivity`, the same activity as the chats, minimisable to a tile, so design 57's activity rule can't keep the kid
out of the chats - FluffyChat would need variant E (call behind the lock, our in-call bar, hang-up through the notice's
only action, or through CallStyle if FluffyChat set `isCustomNotification: true` - a one-line upstream change); (2) calls
are three weeks old, with open no-audio, no-end-on-remote-hangup and Element X interop reports. Launcher work:
`MessagePackages.FLUFFYCHAT = "chat.fluffy.fluffychat"`; room learning keyed on `shortcutId`; a glance `contactOf`
branch; the Message button's `matrix:roomid/` for FluffyChat; trusted ring class `CallkitIncomingActivity`; the E
in-call bar and a package-scoped "only action = hang up" rule (we never read titles); check that a self-managed Telecom
call doesn't trip our Telecom-precedence or `isInCall` paths (`LauncherLocales.callActive` reads `isInCall`).

**(c) Recommendation: stay on Element X; don't offer both yet.** Two apps doubles the reader/call matrix and design 57's
rules for no family benefit today. Re-evaluate FluffyChat in about three months (after call fixes and the promised
"mandatory backup, unsigned devices ignored") with a device test, and then as a parent-chosen per-phone option, not a
swap. Unknown until tested on the Jelly Star: ring over our PIN lock with the app killed (push -> background isolate ->
CallKit -> FSI), answer from our card via the CallStyle extras, audio both ways with Element X parents (both directions,
voice and video), call end when either side hangs up, what Telecom's self-managed call does to our dialer/lock paths,
whether the call keeps running when our lock re-fronts (no PiP, so no PiP hang-up - but untested), notification shape on
the second message and in DMs created by Element X, UnifiedPush with our own ntfy, and whether undeclared managed-config
keys are honoured.

[fc]: https://github.com/krille-chan/fluffychat/tree/85efb515d7c394c94401bf4f21b1087734d89799
[fc-cl]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/CHANGELOG.md
[fc-pubspec]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/pubspec.yaml
[fc-cm]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/client_manager.dart#L114-L158
[fc-login]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/login/login.dart#L77-L92
[fc-matrix]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/widgets/matrix.dart#L150-L170
[fc-matrix-krq]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/widgets/matrix.dart#L223-L235
[fc-boot]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/bootstrap/bootstrap_page.dart#L50-L62
[fc-sk]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/config/setting_keys.dart#L48
[fc-sk-push]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/config/setting_keys.dart#L28-L37
[fc-sk-mdm]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/config/setting_keys.dart#L95-L143
[fc-b852]: https://github.com/krille-chan/fluffychat/commit/b852d4b7bbd2a875d1545e6d281df0875acbb631
[fc-c83c]: https://github.com/krille-chan/fluffychat/commit/c83cd72a
[fc-e1ca]: https://github.com/krille-chan/fluffychat/commit/e1ca8ae5
[fc-b185]: https://github.com/krille-chan/fluffychat/commit/b185e3cd
[fc-71ff]: https://github.com/krille-chan/fluffychat/commit/71ffc728
[fc-9e59]: https://github.com/krille-chan/fluffychat/commit/9e593cdf
[fc-34f6]: https://github.com/krille-chan/fluffychat/commit/34f6c14f2043fdd8b6908010d0bbd66b487e8cee
[fc-895d]: https://github.com/krille-chan/fluffychat/commit/895de76e
[fc-lkm]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/matrix_live_kit_calls/matrix_live_kit_call_member.dart#L11
[fc-lkc]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/matrix_live_kit_calls/matrix_live_kit_call.dart#L16-L58
[fc-lkc-keys]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/matrix_live_kit_calls/matrix_live_kit_call.dart#L232
[fc-ckp]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/call_kit_params.dart
[fc-push-call]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/push_helper.dart#L491-L545
[fc-push-msg]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/push_helper.dart#L190-L380
[fc-push-fb]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/push_helper.dart#L72-L98
[fc-cl-ev]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/chat_list/chat_list.dart#L111-L136
[fc-ovl]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/widgets/layouts/call_overlay.dart
[fc-cvm]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/call/call_view_model.dart#L440-L480
[fc-man]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/android/app/src/main/AndroidManifest.xml#L37-L50
[fc-chat]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/chat/chat.dart#L761-L790
[fc-ch]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/chat/command_hints.dart#L56-L59
[fc-bp-pusher]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/background_push.dart#L236-L255
[fc-bp-up]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/background_push.dart#L266-L296
[fc-bp-upep]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/background_push.dart#L376-L412
[fc-bp-onup]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/background_push.dart#L426-L446
[fc-rel]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/.github/workflows/release.yaml#L72-L101
[fc-app]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/widgets/fluffy_chat_app.dart#L61-L82
[fc-ar]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/android/app/src/main/res/xml/app_restrictions.xml
[fc-er]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/error_reporter.dart#L36-L48
[fc-upd]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/utils/show_update_snackbar.dart#L29
[fc-ac]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/config/app_config.dart#L56-L66
[fc-map]: https://github.com/krille-chan/fluffychat/blob/85efb515d7c394c94401bf4f21b1087734d89799/lib/pages/chat/events/map_bubble.dart#L49-L55
[sdk]: https://github.com/famedly/matrix-dart-sdk/tree/d5881af76b11ddfb9dc75118bc0d46396a389a67
[sdk-cl]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/CHANGELOG.md
[sdk-kv]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/encryption/utils/key_verification.dart#L121
[sdk-skw]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/src/client.dart#L4340-L4359
[sdk-km]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/encryption/key_manager.dart#L1029-L1055
[sdk-cmd]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/src/utils/commands_extension.dart#L314-L355
[sdk-pn]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/src/utils/push_notification.dart
[sdk-gebpn]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/src/client.dart#L1843-L1990
[sdk-rtc]: https://github.com/famedly/matrix-dart-sdk/blob/d5881af76b11ddfb9dc75118bc0d46396a389a67/lib/msc_extensions/msc_4075_rtc_notification/models/rtc_notification_content.dart#L32
[ck]: https://github.com/hiennguyen92/flutter_callkit_incoming/tree/27cd33c1665b7fbc4c8032a7866c929103029592
[ck-man]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/AndroidManifest.xml
[ck-nm-in]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitNotificationManager.kt#L191-L212
[ck-nm-cs]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitNotificationManager.kt#L320-L357
[ck-nm-mi]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitNotificationManager.kt#L435-L586
[ck-nm-on]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitNotificationManager.kt#L605-L823
[ck-rcv]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitIncomingBroadcastReceiver.kt#L117-L152
[ck-cs]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitConnectionService.kt
[ck-conn]: https://github.com/hiennguyen92/flutter_callkit_incoming/blob/27cd33c1665b7fbc4c8032a7866c929103029592/android/src/main/kotlin/com/hiennv/flutter_callkit_incoming/CallkitConnection.kt#L95-L321
[fln-java]: https://github.com/MaikuB/flutter_local_notifications/blob/65fce50d25ea274e3751234104572b78821fa18e/flutter_local_notifications/android/src/main/java/com/dexterous/flutterlocalnotifications/FlutterLocalNotificationsPlugin.java
[ex]: https://github.com/element-hq/element-x-android/tree/4b62a99d925e5ecb051f635874bc47edc43b59e7
[ex-rmcf]: https://github.com/element-hq/element-x-android/blob/4b62a99d925e5ecb051f635874bc47edc43b59e7/libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/RustMatrixClientFactory.kt#L166-L186
[ex-ff]: https://github.com/element-hq/element-x-android/blob/4b62a99d925e5ecb051f635874bc47edc43b59e7/libraries/featureflag/api/src/main/kotlin/io/element/android/libraries/featureflag/api/FeatureFlags.kt#L39-L46
[ex-ff-sc]: https://github.com/element-hq/element-x-android/blob/4b62a99d925e5ecb051f635874bc47edc43b59e7/libraries/featureflag/api/src/main/kotlin/io/element/android/libraries/featureflag/api/FeatureFlags.kt#L112-L117
[ex-ftue]: https://github.com/element-hq/element-x-android/tree/4b62a99d925e5ecb051f635874bc47edc43b59e7/features/ftue/impl/src/main/kotlin/io/element/android/features/ftue/impl/sessionverification/choosemode
[ex-cmd]: https://github.com/element-hq/element-x-android/blob/4b62a99d925e5ecb051f635874bc47edc43b59e7/libraries/slashcommands/impl/src/main/kotlin/io/element/android/libraries/slashcommands/impl/CommandExecutor.kt#L170-L188
[ex-nc]: https://github.com/element-hq/element-x-android/blob/4b62a99d925e5ecb051f635874bc47edc43b59e7/libraries/push/impl/src/main/kotlin/io/element/android/libraries/push/impl/notifications/factories/NotificationCreator.kt
[ex-push]: https://github.com/element-hq/element-x-android/blob/4b62a99d925e5ecb051f635874bc47edc43b59e7/libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/pushers/RustPushersService.kt#L40
[fdroid]: https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/chat.fluffy.fluffychat.yml
[vh-vcp]: ../../launcher/app/src/main/java/com/kidslauncher/mdm/lock/VoipCallPlan.kt
[vh-nr]: ../../launcher/app/src/main/java/com/kidslauncher/mdm/badges/NotificationRule.kt
[i866]: https://github.com/krille-chan/fluffychat/issues/866
[i963]: https://github.com/krille-chan/fluffychat/issues/963
[i1179]: https://github.com/krille-chan/fluffychat/issues/1179
[i1209]: https://github.com/krille-chan/fluffychat/issues/1209
[i1310]: https://github.com/krille-chan/fluffychat/issues/1310
[i2818]: https://github.com/krille-chan/fluffychat/issues/2818
[i2871]: https://github.com/krille-chan/fluffychat/issues/2871
[i3047]: https://github.com/krille-chan/fluffychat/issues/3047
[i3075]: https://github.com/krille-chan/fluffychat/issues/3075
[i3140]: https://github.com/krille-chan/fluffychat/issues/3140
[i3185]: https://github.com/krille-chan/fluffychat/issues/3185
[i3189]: https://github.com/krille-chan/fluffychat/issues/3189
[i3593]: https://github.com/krille-chan/fluffychat/issues/3593
[i3743]: https://github.com/krille-chan/fluffychat/issues/3743
[i3762]: https://github.com/krille-chan/fluffychat/issues/3762
[i3773]: https://github.com/krille-chan/fluffychat/issues/3773
[i3798]: https://github.com/krille-chan/fluffychat/issues/3798
[i3800]: https://github.com/krille-chan/fluffychat/issues/3800
[i3841]: https://github.com/krille-chan/fluffychat/issues/3841
[i3857]: https://github.com/krille-chan/fluffychat/issues/3857
