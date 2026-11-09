# Design QA: 77 - SMS and MMS in Vibb One (`docs/design/77-sms-mms-client.md`)

Security-focused design review by Opus, 2026-11-08, of the note at b61a2835 on `sms-design`.

**What was checked:**
- **The launcher at that commit:**
  - `EnforcementPlan.kt:50-70, 175-290`, `AppEnforcer.kt:431, 600-645`, `OwnPermissions.kt`, `PhoneNumbers.kt`, `CallRules.kt:95-200`
    and `CallPolicyStates.kt`;
  - `Application.kt:169-185`, the manifest (`allowBackup`, `data_extraction_rules.xml`, processes) and `app/build.gradle.kts` (minSdk 34);
  - greps for `DISALLOW_SMS`, `KNOWN_SMS_PACKAGES`, `smsEnabled`, SQLite/Room and `setWhitelistedRestrictedPermissions`.
- **The server:** `models.rs:1265-1295` and `migrations/0022_calls.sql`; the last migration is `0129`.
- **Notes and history:** `docs/notes/launcher/calls.md`, `docs/notes/server/calls-and-contacts.md`, design 09 part A and its
  "Decisions after QA review", and PLAN's two entries.
- **Upstream code, cloned `--depth 1` on 2026-11-08 and read only:**
  - Fossify Messages and mmslib, AOSP Messaging, GrapheneOS Messaging;
  - AOSP `frameworks/opt/telephony` and `packages/services/Mms`;
  - one file from `frameworks/base` (`GsmSmsAddress.java`, fetched from gitiles).

**Verdict: the shape is right, but the gate has holes that a stranger can use.** These are all fixed by changing the
design's text, before any code is written:
- One engine in the launcher, the parent's rules enforced inside it, our own store and the platform's MMS transport are
  the right choices.
- So are taking the role as Device Owner, persisting before anything else, and parsing in an isolated process.
- The four §2 comparisons are fair, and every upstream citation I spot-checked holds (I1).
- But the receive gate has two easy bypasses:
  - Any sender that calls itself "112" is let in (H1).
  - An alphanumeric sender whose text looks like the parent's number passes as that number (H2). The two combine:
    an alphanumeric "112" gets past the operators' number filters and past the phone.
- D10's promise that a blocked MMS is never fetched rests on a header in the attacker's own push (H3). That is
  exactly the NSO technique the note cites.
- The isolated process sends its output back in a compressed image format that the Device Owner process then decodes
  (M1).
- Several paths still lose a message silently or go around the engine (M2-M4).
- The bridge seam assumes the phone serves (M5).

**Counts: 0 Critical, 3 High, 9 Medium, 11 Low, 7 Info.**

## High

**H1 - D4 rule 4 lets any sender in by naming itself an emergency number.**
- **The rule.** D4 rule 4 says "The sender normalises to a number the platform calls an emergency number: ALLOW". It
  applies always, not only in the callback window.
- **How easy it is to use.** The SMS sender field (TP-OA) is set by whoever submits the message (§3 says so itself).
  A bulk-SMS aggregator with sender "112", "113" or "110" reaches the kid with any text.
  - As an *alphanumeric* "112" it even escapes the operators' number filters (§3: ekomforskriften § 9-5 is about
    numbers, and Nkom describes no rule for text senders).
  - `PhoneNumbers.normalize("112")` is `Short("112")` (`PhoneNumbers.kt:47-60`), and the platform calls it an
    emergency number.
- **Why it is bad.** "112: Mamma er på sykehuset, ring dette nummeret" is a phishing text aimed at a child. The
  design is public, so the trick is too.
- **It contradicts the call rules.** Incoming calls never get "emergency number" as a reason. `Emergency.ALWAYS` is
  documented as "Used only to ALLOW an outgoing call (never to open the callback window)" (`CallRules.kt:131-135`).
  `decideIncoming` allows a stranger only through `callbackWindowOpen()` (`CallRules.kt:114-127`).
- **Fix:**
  - Delete rule 4. Rule 1 (the callback window) already covers the emergency services texting back after the kid
    called them.
  - Add a contract vector: a sender "112" outside the window BLOCKs.
  - Optional: inside the window, a non-contact message is shown with an "Ukjent avsender" ("Unknown sender") line,
    so a window-time phish at least reads as unknown.

**H2 - An alphanumeric sender whose text looks like a number passes as that number.**
- **What Android returns.** For a GSM sender address of type alphanumeric (TON `101`), the platform decodes the address
  as GSM-7 *text* and returns it from `getOriginatingAddress()` (frameworks/base `GsmSmsAddress.java:60-64`, `:97-98`).
  The public `SmsMessage` doesn't say which type it was.
- **The attack.** `+4791234567` is 11 characters, exactly the usual alphanumeric limit, and `+` is in the GSM-7
  alphabet. So a stranger who knows the parent's number can send from the *text* sender "+4791234567". On the phone
  it is indistinguishable from the parent's number after `normalize` (`91234567` works too, through the default
  country code).
- **Why the note's reasoning doesn't cover it.** §3 relies on § 9-5 to make number spoofing harder. Text senders are
  outside that rule, so this is the cheapest spoof there is.
- **The requirement it breaks:** the user's "alphanumeric senders blocked".
- **Fix:**
  - Judge the sender type from the raw PDU. The intent carries `pdus` and `format`. A small pure parser reads the TP-OA
    type byte of a 3GPP SMS-DELIVER (after the SMSC address, the first octet and the OA length) and returns `NUMBER`,
    `ALPHANUMERIC` or `MALFORMED`.
  - D4 classifies `ALPHANUMERIC` as alphanumeric **whatever its characters are**: BLOCK outside the window, and
    `sender_class = alphanumeric` in the blocked row.
  - Treat `3gpp2` PDUs as malformed unless step 0 shows they occur.
  - Do the same for MMS: a `From` that isn't `<digits>/TYPE=PLMN` (an `@` address, `TYPE=` other than PLMN, or
    letters) blocks.
  - JVM vectors in `sms_contract.json` `rules`: an alphanumeric "+4791234567", an alphanumeric "112", a numeric
    "+4791234567".
  - Emulator check: `sms pdu` with TON 101 (§10.3 already plans a TON-alphanumeric PDU).

**H3 - "A blocked MMS is never fetched" rests on a header the attacker writes; a crafted WAP push still makes the phone
fetch any URL.**
- **What the gate checks.** D10 gates the download on the m-notification-ind's `From`. But the notification is just a
  binary SMS to port 2948. Anyone with an aggregator that sends binary SMS can craft it with:
  - `From: +4791234567/TYPE=PLMN` (the parent);
  - `X-Mms-Content-Location: http://attacker.example/x`.
- That is how Enea's "MMS Fingerprint" research says NSO did it: a crafted push, then the phone's fetch. The design
  cites that research for the opposite claim.
- **The fetch has no URL check.** The platform fetches whatever URL the app passes. `DownloadRequest.doHttp` calls
  `mmsHttpClient.execute(mLocationUrl, ...)` with no host check (MS `DownloadRequest.java:75-93`).
- **The response is not capped.** `MmsHttpClient` reads the whole response into a `ByteArrayOutputStream`
  (MS `MmsHttpClient.java:220-223`). So a huge answer also weighs on `com.android.phone`, the process that carries calls.
- **What the attacker gains:**
  - the UA/UAProf fingerprint;
  - a retrieve-conf of their choosing, whose `From` they also set (D10's "must equal the notification's" holds by
    construction);
  - a picture shown in the parent's thread.
- **Fix:**
  - **Check the host of `X-Mms-Content-Location`** against the carrier's MMSC host before any fetch. Blocked as
    `kind = mms`, `sender_class = bad_location`.
    - The app can't read the APN table, so the hosts come from step 0 (Telia, Telenor, Ice and their MVNOs, by
      MCC/MNC).
    - Keep them in a small server-held list that the policy carries, pinned in `sms_contract.json`.
    - An unknown carrier fails closed, with a finding "MMS can't be checked on this SIM" and the MMS shown as "Bilde
      kan ikke hentes" ("Picture can't be fetched").
  - **Require** an `http(s)` scheme, no userinfo, and a port that is absent or 80/443/8080. Also require
    `X-Mms-Message-Size` within the carrier maximum (already in D10).
  - **Cap the bytes accepted on our side** (L3).
  - Step 0 records each carrier's content-location host from a real MMS. Also check whether the MMS APN can reach an
    outside host at all; many carriers wall it in.
  - Restate D10's promise honestly: "never fetched for a blocked sender, nor from a host that isn't the SIM's MMSC".
    Note the residual: a sender spoofing an allowed number through the real MMSC is the same residual as SMS
    spoofing (§3).

## Medium

**M1 - The isolated process sends back JPEG bytes, which the Device Owner process then decodes: the sandbox's output is
an attack surface.**
- **What crosses the boundary.** D8 returns "images re-encoded by the isolated process as JPEG". The thread, the
  notification's large icon and `PictureActivity` then decode those bytes in the main process. "The main process
  never decodes an image we didn't encode" is true only while the isolated process is honest.
- **The two-step attack.** An attacker who gets code running in it through one decoder bug (Qmage and Stagefright
  are the note's own examples) chooses the "JPEG" the main process decodes next. That is a second shot, at
  libjpeg-turbo, inside the Home + phone + Device Owner process.
- **Fix:**
  - Return **raw pixels**: width, height and RGBA_8888 in a `SharedMemory` region, at most 1,600 px a side, so at most
    about 10 MB.
  - The main process checks `width * height * 4 == size`, builds the bitmap with `Bitmap.copyPixelsFromBuffer` (no
    parser), and does the JPEG encode itself.
  - Thumbnails are made the same way, from the bounded pixels.
  - Every file the main process ever decodes is then one it encoded. Name the files by the hash of the main
    process's own bytes, as §6.4 intends.
  - Text crosses as UTF-16 strings with a length checked before `readString`.
  - Headers cross as strings that the main process normalises itself (D4).

**M2 - Two paths deliver SMS text around the engine without any permission the design denies.**
- **(a) `AppSmsManager`.**
  - What it does: any app can call `SmsManager.createAppSpecificSmsToken(PendingIntent)` **without a permission**.
    Only the calling package is checked (T `AppSmsManager.java:84-101`; `SmsController.java:922-929`).
  - An incoming SMS that contains the token goes to that app with the full text, and is then *dropped* before
    `SMS_DELIVER` (T `InboundSmsHandler.java:1571-1576`, `dropSms`).
  - Nonce tokens don't expire (only package-based ones time out, `AppSmsManager.java:188-210`). The package-based
    token is stable per app.
  - So an allowed app can be a receive-anything channel for strangers. The engine never sees the message, not even
    as a blocked row.
- **(b) Google Play services.**
  - GMS is a system app with `RECEIVE_SMS`, and §2.5 denies only "every non-system requester". It receives
    `SMS_RECEIVED` for every message, blocked ones included (T `:1841-1852`).
  - It passes them on through the SMS Retriever API (to an app whose hash is in the text) and the SMS User Consent
    API. The User Consent API shows the full text of the next SMS *from a sender not in the contacts* in a GMS sheet
    when any app asks.
- **Fix:**
  - (a) has no Device Owner switch. Name it in §2.5 as an accepted residual, limited to apps the parent allows.
  - Step 0 lists every holder of `RECEIVE_SMS`, `RECEIVE_MMS` and `RECEIVE_WAP_PUSH` on the Jelly Star
    (`dumpsys package` / `appops`).
  - Step 0 also tests `setPermissionGrantState(DENIED)` on GMS's `RECEIVE_SMS`, and what breaks (account phone
    verification).
  - The note then picks: deny it in `contacts` mode, or accept and name it. This choice is for the user (M9).

**M3 - A Bluetooth MAP client may send SMS around D5 [verify].**
- The phone's Bluetooth MAP server (`com.android.bluetooth`, a system app with `SEND_SMS`) accepts pushed messages
  from a paired car or PC once "message access" is allowed on the phone, and sends them with `SmsManager`.
- That path never meets the engine's `send`, so it reaches any number.
- Design 52 lets the kid pair Bluetooth headphones; a MAP client pairs the same way.
- (D6 is a benefit here: MAP *reads* the Telephony provider, which holds nothing of ours. Reading isn't a leak.)
- **Fix:**
  - Step 0: pair a MAP client (a Linux PC with `bluetoothctl`/obexd, or a car kit) and push an SMS to a non-contact.
  - If it is sent, add a rule in `contacts` mode. The options: deny `SEND_SMS` to the Bluetooth package if the Device
    Owner may and it breaks nothing else, or `DISALLOW_BLUETOOTH_SHARING` plus a refusal of MAP access. Or name it as
    a residual and ask the user.
  - Write the result in §2.5.

**M4 - Several states lose messages silently. The note treats them as "blocked" or doesn't list them.**
- **(a) `DISALLOW_SMS` drops messages; it doesn't hold them.**
  - In opt/telephony the restriction is checked on dispatch only for *non-main* users (`InboundSmsHandler.java:1440-1447`,
    inside `if (!isMainUser)`).
  - For the main user it acts through the receive app-op passed to `sendOrderedBroadcast` (`:1385-1450`). The
    message was already acknowledged to the network once it was in the raw table (`handleNewSms`, `:677-707`), and
    the rows are then deleted "anyway" (`:1888-1897`).
  - So in `off`, in "contacts but the role isn't ours" (D1) and in fail-closed, every incoming SMS is **lost, not
    delayed**. The operator won't retry it.
  - The same happens when the parent picks "Contacts only" for a phone whose launcher predates this design: D2's
    `sms_enabled = false` sets `DISALLOW_SMS` there.
- **(b)** D4 rule 2 drops the message, body and all, while the rules are unknown, even with the role held.
- **(c) A process death mid-parse loses the push.** §6.4 creates the row only after the m-notification-ind is parsed
  in the isolated process (D10). The platform deletes its copy when our receiver finishes.
- **(d) A slow receive can kill the launcher.** `SMS_DELIVER`/`WAP_PUSH_DELIVER` are foreground broadcasts
  (`FLAG_RECEIVER_FOREGROUND`, `:1389-1400`), so `goAsync` has about 10 s before an ANR. An ANR kills the Home,
  phone and Device Owner process.
  - The receive path runs "on the engine thread", the same single thread as the outbox, the MMS state machine,
    pruning and binds to the parse service.
- **Fix:**
  - (a) Say it plainly in D1, §6.10 and the PWA. The calls report's "SMS-appen er ikke Vibb One" finding and a new
    "Telefonen kan ikke bruke Bare kontakter ennå - meldinger til den går tapt" finding (no `sms_engine_v1` while the
    mode is `contacts`) say "lost", not "blocked".
  - (a) Better: make the PWA refuse `contacts` for a phone that hasn't reported `sms_engine_v1`.
  - (a) Step 0 checks the loss on a device: text a phone in `off`, lift it, nothing arrives.
  - (b) While the role is ours and the rules are unknown, **hold** the message instead of dropping it, and set no
    `DISALLOW_SMS`. Store it with verdict `HELD`: not shown, not notified, not on the seam. Judge it again when
    the rules are known: ALLOW → shown, else → moved to `blocked` and its body deleted. Sends stay refused (D5
    rule 2), and the other apps' permission denials still hold. Keep `DISALLOW_SMS` for `off` and "no role" only.
  - (c) Commit the raw notification-ind (capped at 2 KB, `pending_push` table) in the receiver, then `finish()`,
    then parse. Delete the row after the decision.
  - (d) The receive path gets its own executor and a 3 s budget. It only judges (the rules are in memory), commits
    and finishes. The notification, the event fan-out and everything MMS run after `finish()`.

**M5 - The seam's remote mapping assumes the phone serves.**
- §6.2 maps `events(afterSeq)` to "SSE with `Last-Event-ID`" and `openPartByHash` to `GET .../parts/{sha256}`.
- Both make the server (or the bridge) a client of the phone. The phone never serves: the SSE stream runs from the
  server to the phone, and every phone API is phone-initiated.
- The interface also has no event for a conversation changing (participants' `contactId`, `canSend`, a contact
  renamed) or for a message deleted (Q5). bridgev2 needs both for room state and redactions.
- **Fix:**
  - Restate the remote direction:
    - The phone **pushes** events in order, in batches from a cursor the server acknowledges (`remote_acked_seq`,
      stored by the engine).
    - Commands (`send`, `markRead`, "upload part X") arrive as SSE nudges plus a phone-initiated fetch.
    - Parts are *uploaded* by the phone on request, by hash.
    - Replay after an absence is "push from `remote_acked_seq`". A pruned cursor gets a backfill push.
  - Add `ConversationChanged(seq, conversationId)` and `MessageDeleted(seq, id, origin)` to `EngineEvent` now. They
    cost nothing and keep the "no engine change later" promise honest.
  - Define `Origin` (the code block uses it without declaring it).
  - A repeated `clientId` with a *different* body is `Refused(DUPLICATE_CLIENT_ID)`, not the old message.

**M6 - Switching a number that uses RCS to `contacts` can make contacts' messages vanish. The runbook can't be followed
on a phone where Messages is already hidden.**
- **What happens.** While the kid's number stays registered for RCS ("chat features"), an Android contact's Messages
  sends over RCS to Google's servers, never as SMS. With Google Messages hidden (§2.5), nothing on the phone receives
  them. Whether and when the sender's phone falls back to SMS is its own setting.
- **Why the runbook doesn't help.** "Turn RCS chats off before enrolling" (design 09 A3) needs Messages visible. On a
  phone in `off` today, it's hidden. Taking the role also doesn't deregister RCS.
- **Fix:**
  - Step 0: with RCS on, switch to `contacts` and text from an Android phone with RCS. Time how long until it arrives
    as SMS, if ever.
  - Add an on-phone path for the switch. The launcher shows Google Messages for one session, or the PWA tells the
    parent to turn chat features off first, while the mode is still `open`.
  - Point to Google's web deregistration form for a phone that is already locked down.
  - Add a PWA line under "Bare kontakter" ("Contacts only"): "Slå av chatfunksjoner i Meldinger først" ("Turn off chat
    features in Messages first").

**M7 - D12 blocks every group when the phone's own number is unknown, which the note expects on Norwegian SIMs.**
- D12 checks every group participant against the contacts "or the phone's own number". The own number comes from
  `SubscriptionManager.getPhoneNumber`, and the note expects it to be missing ("often don't carry it").
- So a family group MMS from the parent is blocked on the user's own phones.
- There is no safe guess: with `To = [kid, stranger]`, the "one unknown address must be us" heuristic would admit the
  stranger.
- **Fix:**
  - Let the parent type the phone's own number on the Calls page. It is normalised like a contact, sent as
    `call_policy.own_number`, and pinned in the contract.
  - Use it when the SIM has none.
  - Report `own_number_known` and a mismatch, "Nummeret i Vibb stemmer ikke med SIM-kortet" ("The number in Vibb
    doesn't match the SIM card"), when both exist.
  - The SIM's own answer wins only if the parent's is empty.

**M8 - "Works without mobile data" isn't true for MMS, and D14 misses the switch that would make it true.**
- MMS needs the MMS APN, and `MmsService` refuses when MMS data is disabled (MS `MmsService.java:285, 384`).
- Android has a per-SIM setting, "MMS messages" (send and receive MMS when mobile data is off), and a carrier policy
  that always allows MMS data. D14 mentions neither. It goes straight to "Bilde venter - trenger mobildata"
  ("Picture waiting - needs mobile data").
- **Fix:**
  - Step 0: on the Jelly Star with a Telia, Telenor and Ice SIM, check whether the "MMS messages" toggle exists and
    whether MMS arrives with mobile data off and the toggle on.
  - If it works, add it to the runbook (an on-phone step, no adb). Report `mms_data_allowed` in `sms_state`, with a
    PWA finding "Bilder i meldinger kommer ikke fram uten mobildata" ("Pictures in messages don't arrive without
    mobile data").
  - Tell the user plainly what still needs data (Q in M9).
  - The retry trigger: a passive `NET_CAPABILITY_MMS` callback won't fire, because nobody requests that network until
    we download. Use a data-enabled listener (`TelephonyCallback.DataEnabledListener`; we hold
    `READ_PHONE_STATE`) plus each sync. After the fifth try, show "Trykk for å prøve igjen" ("Tap to try again")
    instead of waiting forever.

**M9 - Some choices the note made silently should be questions. One question uses the wrong words.**
- **Silent choices to ask about, in plain words:**
  - *Whose Element X?* §6.2 says the remote front end "is the kid's own Matrix account seeing his SMS (the user's
    wish)". The user wrote "for mitt bruk er jo det beste at alt kunne kommet inn i element x". That could just as
    well mean the parent's own Element X. That decides who reads the kid's texts, so ask it now, even though the
    bridge comes later.
  - *Replies from people the kid may only text.* With D3, a contact with Out and not In (a coach, a shop) never
    reaches the kid, not even with an answer to his own question. Ask: should such a contact be able to answer
    within, say, 24 hours after the kid texted them?
  - *Messages while the phone can't read its rules:* held and shown later (M4 proposal), or dropped as now?
  - *The phone's own number for groups* (M7): may the parent type it?
  - *RCS* (M6) and *MMS with mobile data off* (M8): say what the parent must do on the phone.
  - *GMS* (M2): may Google Play services lose SMS access in Contacts mode, if that breaks phone-number checks in
    Google apps?
- **Earlier proposals the note reverses without saying so.** Design 09's "Decisions after QA review" put three
  proposals to the user, still unconfirmed:
  - separate SMS flags;
  - the blocked log's text if the parent turns it on;
  - a sender-name allowlist pre-seeded with Vipps and ice.
  The note's D3, §7 ("never content") and Q2 go the other way. Q1 and Q4 should offer the earlier options, or say
  why they're dropped.
- **The wrong words in Q4.** Q4 says "Can call / Can be called". The PWA's words are "Can call" and "In the phone book"
  (`docs/notes/server/calls-and-contacts.md`, design 31 step 8). Use the parent's words: "Kan ringe" and "I
  telefonboka".

## Low

**L1 - The isolated process runs `Application.onCreate` and the component factory.**
- Android creates the `Application` in every process, isolated ones included. Ours refreshes `CallPolicyStore`, reads
  preferences and installs crash handlers first thing (`Application.kt:169-185`).
- In an isolated process with no file access, that throws, or at best runs enforcement code next to untrusted input.
- **Fix:**
  - Guard it like `:bootcover` (`Process.isIsolated()` → return before anything else).
  - Make `KidAppComponentFactory` do nothing there too.
  - Mark the service `exported="false"`, and put `isolatedProcess` in the manifest's checked list.

**L2 - The vendored codec is larger and more Android-bound than "about 5,500 lines, a few imports".**
- **Size.** AM's `mmslib/pdu/` without `PduPersister` is about 7,580 lines.
- **More Android ties.** `PduComposer` imports `ContentResolver`/`Context` and reads parts by `Uri`. `PduPart` holds
  an `android.net.Uri`. `PduParser` uses `com.android.messaging.util.ContentType`.
- **Fix.** Count it right, and plan the composer to take bytes (the isolated process gives it the re-encoded JPEG).
  Drop `Uri` from `PduPart`. Copy the few `ContentType` constants in. The pure-JVM goal holds; it is just more than
  swapping imports.

**L3 - The MMS download file needs a provider that the note doesn't list, plus a byte cap.**
- `downloadMultimediaMessage` writes into a content URI that the app grants to the MMS service, as AM does with
  `MmsFileProvider` (AM `MmsSender.java:251-267`, manifest `:313-316`).
- §6.1 and §8.4 don't list that provider. §6.4 names a path in `noBackupFilesDir`, but the platform can't write there
  without one.
- **Fix:**
  - Add a non-exported, `grantUriPermissions` provider to §6.1/§8.4, with a write-only URI per request and the grant
    revoked on the result.
  - Its `openFile` writes through a pipe that stops at the carrier maximum (or 1 MB). That's the byte cap H3 needs.

**L4 - A sent-result can arrive after a process death.**
- §6.2 turns `SENDING` into `UNCERTAIN` "after a process death". But the sent `PendingIntent` targets a manifest
  receiver, so the platform still delivers it to a restarted process.
- **Fix:**
  - Mark `UNCERTAIN` only after 10 minutes without a result.
  - "Each part's sent-intent moves it to `SENT`" should read: `SENT` when every part is sent, `FAILED` when any part
    fails.

**L5 - Floods: blocked rows, MMSC repeats and lossy counters.**
- A stranger's flood fills `blocked`, which has no row limit. An MMSC that gets no NotifyResp repeats the push, and
  each repeat becomes another row.
- `received`/`sent` "since the last report" are lost when a report fails.
- **Fix:**
  - Cap `blocked` at 500 rows, then keep a count per sender class.
  - Deduplicate MMS rows by `(transactionId, contentLocation)`.
  - Report cumulative counters, as `call_state` does.

**L6 - Replying from the lock screen.**
- The notification's "Svar" ("Reply") works from the lock screen. Whoever holds a lost phone can answer the parent as
  the kid.
- **Fix:** `RemoteInput` reply actions with `setAuthenticationRequired(true)` (API 31, so within minSdk 34).

**L7 - D1's hand-back for `off`, unmanaged calls, and the kill check.**
- `off` hides and suspends `KNOWN_SMS_PACKAGES` (`EnforcementPlan.kt:202`). D1 hands the role back to the previous
  holder, which `off` then hides.
- Calls turning unmanaged isn't covered.
- The role's SMS permissions are hard-restricted. Whether our policy-fixed grant survives the role's exemption going
  away, without killing the process, is untested (design 09 A1 was about the dialer). The dialer role also grants SMS
  permissions (`OwnPermissions.kt:39-41`).
- **Fix:**
  - In `off`, keep the role: `DISALLOW_SMS` stops everything anyway, and the history stays where it is.
  - Hand it back for `open` and for unmanaged calls (which behave as `open`).
  - Step 0: hand the SMS role back on the Jelly Star while the launcher holds the dialer role, and check that the
    process survives (`adb shell pidof`).
  - Say in §6.7 what the kid sees of the history in `off` and `open`: nothing (the tile is gone). Q6 then covers both.

**L8 - Lean tests miss what the guarantees rest on.**
- **Idempotency and de-duplication.** They live in SQLite unique indexes. An in-memory fake of `MessageStore` doesn't
  test them.
  - Fix: put the dedup decision in the store interface's contract (`insertOutgoing` returns `Existing` or
    `Inserted`), give the fake the same semantics, and give the emulator step one scripted double-send.
- **The `rules` vectors.** `sms_contract.json`'s `rules` would make the server implement D4/D5 only for the test. The
  phone judges every message, as with `zone_vectors.json` the other way round.
  - Fix: keep `rules` in a launcher-only test resource. The shared file keeps `policy`, `report` and `blocked`.

**L9 - Step 3 is too large for one review.**
- Step 3 holds the codec, its hardening and fuzzing, the isolated service, the gated download, the state machine,
  display, D14 and D12.
- **Fix:** split it.
  - 3a: the codec, hardening and the fuzz test. JVM only, reviewable alone.
  - 3b: the service, the download, the state machine and display.
  - Both still ship in the one release.

**L10 - Dual SIM.**
- The Jelly Star has two SIM slots, and the note designs for one.
- **Fix:** store `subId` on the conversation and reply on it. When it is gone, use the default SMS subscription.
  Give each SIM's own number to D12/M7.

**L11 - Window-allowed senders don't fit the data model.**
- D4 rule 1 admits alphanumeric senders inside the callback window. But `Participant.address` is "normalised E.164 or
  short number".
- **Fix:** define a conversation key for a raw sender (`raw:<text>`, no `contactId`, `canSend = false`).

## Info

**I1 - The upstream citations hold.** Spot-checked against today's heads, the same day as the note:
- **Parsers:**
  - ML `PduParser`'s unbounded `new byte[dataLength]`;
  - AM `PduParser.java:876-931`;
  - GM's added check (`:940-942`) and bounded content-type index (`:1623-1628`).
- **mmslib:**
  - `PushReceiver`'s persist before any check (`:184`) and static `HashSet` (`:85`);
  - `DownloadManager`'s `RECEIVER_EXPORTED` (`:55-56`);
  - `MmsReceivedReceiver`'s raw thread, length read and check after download (`:109-124`) and unchecked
    `getFrom()` (`:190`).
- **Fossify Messages:** `SmsReceiver.kt` (last-part address, receive time, bitmap before insert).
- **AOSP Messaging:** `MmsSender.java:89-135, 251-267`.
- **The platform (T and MS):**
  - `InboundSmsHandler.java:959, 1841-1896`;
  - `WapPushOverSms.java:266-284`;
  - MS `DownloadRequest.java:75-93, 108-113` and `MmsService.java:285, 384`.
- **GrapheneOS Messaging:** `minSdk = 36` (`app/build.gradle.kts:59`) and its two parser tests exist. There is no
  top-level LICENSE.

**I2 - Not re-checked here.** The four 2026 CVEs and the "SMS of Death". The note says it didn't re-check them line by
line either. They only motivate §8.1, which stands without them.

**I3 - Backup is covered.** `allowBackup="false"`, and `data_extraction_rules.xml` excludes every domain for cloud
backup and device transfer, so the new database can't leave the phone that way.

**I4 - D6 also shuts out readers.** Bluetooth MAP, and any future `READ_SMS` holder, read an empty provider. That's
worth a line in D6's "Why".

**I5 - Pictures the kid sends.** Re-encoding drops EXIF, GPS included. Say so in D11, since it's a privacy property
the parent may ask about.

**I6 - Migration number.** `0130_sms.sql` is the next free number on `main` today. Re-check it at build time (design
76's server work is in flight).

**I7 - Sending to emergency numbers.** D5 rule 1 also lets the kid text 110/112/113, which in Norway don't take SMS.
That's harmless and consistent with calls. Just note it.

## What the step 0 list should gain

These come from the findings above. All are device checks, and none needs code:
- **Senders and pushes:**
  - an alphanumeric sender reading "+47…" and "112" (H1, H2; the emulator's `sms pdu` is enough);
  - each carrier's MMSC host as seen in a real m-notification-ind, and whether the MMS APN reaches an outside host
    (H3);
  - whether each carrier's m-notification-ind carries `From` (D10's missing-`From` block).
- **Other receivers:**
  - every holder of `RECEIVE_SMS`, `RECEIVE_MMS` and `RECEIVE_WAP_PUSH`, and denying GMS's (M2);
  - a Bluetooth MAP push to a non-contact (M3).
- **Losses:**
  - an SMS sent while `DISALLOW_SMS` is set never arrives after it is lifted (M4a);
  - RCS-on to `contacts`, with the time until an RCS sender falls back (M6).
- **Data and roles:**
  - the "MMS messages" toggle with mobile data off (M8);
  - the SMS-role hand-back without a process kill (L7).

## Fix round (architect, 2026-11-08, on `sms-design`)

Every finding below is fixed in `docs/design/77-sms-mms-client.md` or explained.

**High:**
- **H1 - fixed.** D4's emergency-sender rule is deleted, and incoming SMS from emergency numbers are allowed only
  through the callback window (D4 rule 2, the "No sender is let in" paragraph). A window-time non-contact shows "Ukjent
  avsender" (D4 rule 2, §6.7). The vector "112 outside the window BLOCKs" is in §10.1. §0 and D13 are reworded to match.
- **H2 - fixed.** `SmsSenderType` reads the TP-OA type from the raw PDU, and an alphanumeric sender BLOCKs whatever its
  characters are; `3gpp2` counts as malformed (D4 rule 1, §6.1, §6.3). MMS `From` must be `<digits>/TYPE=PLMN` (D10).
  The vectors are in §10.1 and the emulator TON 101 check in §10.3; the cheapest-spoof point is in §3 and §2.5.
- **H3 - fixed.** Content-location is checked against the SIM's MMSC host from a server-held list by MCC/MNC, with
  scheme, userinfo and port rules, failing closed for an unknown carrier (D10, §7 "The MMSC host list"). The byte cap is
  in `MmsFileProvider` (D10, L3). Step 0 records the hosts and whether the APN reaches outside (§9). D10's promise is
  restated, with the residual.

**Medium:**
- **M1 - fixed.** The isolated process returns raw pixels in `SharedMemory`; the main process checks the size, copies
  the pixels and encodes its own JPEG and thumbnail; text lengths are checked before reading (D8, §8.2, §6.4, §6.8).
- **M2 - fixed (named, the user decides GMS).** App-specific tokens are an accepted residual with no Device Owner switch.
  GMS goes through step 0 tests and §13 Q8. See §8.6, §2.5 and §9 step 0.
- **M3 - fixed (step 0 decides).** The Bluetooth MAP push test is in step 0, with the rule if it sends: deny `SEND_SMS` to
  Bluetooth if possible, else a named residual put to the user (§8.6, §2.5).
- **M4 - fixed.**
  - (a) `DISALLOW_SMS` is stated as lost in D1 and §6.10, the PWA says "går tapt", and "Bare kontakter" is offered only
    after `sms_engine_v1` (D2, §7). The step 0 loss check is in §9.
  - (b) `HELD` replaces dropping while the rules are unknown, with `restrictSms` narrowed (D15, §13 Q6).
  - (c) The raw push is committed to `pending_push` before `finish()` (D7, §6.4, §6.6).
  - (d) Receive has its own executor and a 3 s budget; everything else runs after `finish()` (D7, §6.10).
- **M5 - fixed.** The remote direction is restated: the phone pushes from a server-acknowledged `remote_acked_seq`,
  commands come as nudges plus a phone fetch, and parts are uploaded on request (§6.2 "The remote direction").
  `ConversationChanged`, `MessageDeleted`, `Origin` (declared) and `Refused(DUPLICATE_CLIENT_ID)` are added (§6.2).
- **M6 - fixed.** The RCS path is in §8.5: the PWA line, Google's deregistration form, and a possible one-session
  Messages. The step 0 timing check is in §9, and the user question is §13 Q7.
- **M7 - fixed.** The parent types the phone's own numbers, one per SIM. The SIM's number is used only when that field
  is empty, and a mismatch becomes a finding (D12, §7).
- **M8 - fixed.** The "MMS-meldinger" toggle step 0 check and runbook line, `mms_data_allowed` with its finding, the
  `DataEnabledListener` plus sync retry trigger, and "Trykk for å prøve igjen" after five tries (D14, §7, §13 Q7).
- **M9 - fixed.**
  - The silent choices are now user questions: whose Element X (§13 Q9, §6.2), replies from text-only contacts (§13 Q3,
    D3), hold or drop (§13 Q6, D15), the own number, RCS and MMS without data (§13 Q7), and GMS (§13 Q8).
  - The reversals of design 09 are stated in §11, and §13 Q1-Q3 offer the earlier options.
  - Q4 now uses the PWA's own words. The nb catalogue says "I telefonboken" (`server/src/i18n/nb.json:1228`), not
    "telefonboka", so the note uses that and "Kan ringe <navn>".

**Low:**
- **L1 - fixed.** The `isIsolated()` guard in `Application` and the component factory, `exported="false"`, and a
  manifest test like `BootCoverManifestTest` (D8, §6.1).
- **L2 - fixed.** About 7,580 lines; `Uri`, `ContentResolver` and `ContentType` are handled; the composer takes bytes
  (D9, §0, §2.6, §4).
- **L3 - fixed.** `MmsFileProvider` with per-request write-only URIs, revoked grants and a capped pipe (§6.1, D10, §8.4).
- **L4 - fixed.** `UNCERTAIN` only after 10 minutes, with late results applied; `SENT` only when every part is sent,
  `FAILED` when any part fails (§6.2).
- **L5 - fixed.** `blocked` is capped at 500 rows plus counts, pushes are deduplicated for blocked rows too, and the
  counters are cumulative (§6.4, §6.6, §7).
- **L6 - fixed.** `setAuthenticationRequired(true)` on the reply (§6.8).
- **L7 - fixed.** The role is kept in `off` and handed back for `open` and unmanaged calls (D1). The step 0 hand-back
  survival check is in §9 and §8.3, and §6.7 says what the kid sees in `off` and `open`.
- **L8 - fixed.** `insertOutgoing` (`Inserted`/`Existing`) is the store contract, the fake implements it, and there is
  an emulator double-send (§6.2, §10.1, §10.3). The `rules` vectors moved to the launcher-only `sms_rules.json` (§7,
  §10.1).
- **L9 - fixed.** Step 3 is split into 3a (codec, JVM only) and 3b (§9).
- **L10 - fixed.** `subId` is on the conversation and replies go out on it, with an own number per SIM (§6.2, §6.5,
  D12).
- **L11 - fixed.** A `raw:<text>` conversation key with no `contactId` and `canSend = false` (§6.2, §6.6).

**Info:**
- **I1, I2 - noted.** No change needed.
- **I3 - fixed.** The backup line is in D6.
- **I4 - fixed.** D6's "Why" now includes MAP and `READ_SMS` reading an empty provider.
- **I5 - fixed.** EXIF and GPS are dropped on send (D11).
- **I6 - fixed.** The migration number is to be re-checked at build time (§7).
- **I7 - fixed.** D5 rule 1's note.

**"What the step 0 list should gain":** every item is in §9 step 0.

## Revision 2 (delta QA, 2026-11-09)

Opus reviewed the diff `7f5eb1c2..8fc14ecf` of `docs/design/77-sms-mms-client.md` (revision 2, merged on `main`)
and what it newly touches:
- **The server:**
  - `parent_events::raise` and `EVENTS_AN_HOUR`/`URGENT_PUSHES_AN_HOUR` (`parent_events.rs:930-1010`);
  - `security::record_phone_event` (`security.rs:554-600`);
  - the time-request intake as the model for §14 (`handlers/device_api.rs:2590-2720`);
  - the calls page's contact and Add Contact forms (`templates/device_calls.html:141-220`);
  - the contact defaults (`migrations/0022_calls.sql:30-32`: `allow_inbound`, `allow_outbound` and `show_on_home` all
    default 1);
  - the existing text cleaners (`calendar::clean`, `calendar.rs:192-201`; `security::is_format_char`, `security.rs:53-70`).
- **The launcher:** `CallRules.kt`'s `Emergency`.

**Scope** (the coordinator, 2026-11-09). Revision 3, written in parallel, changes three things:
- D3's text and picture switches become independent of the call switches;
- the device switches become per channel (Calls and SMS each Off / managed by Vibb / not managed);
- the kid may save and share a received picture.

For those three this section judges the security of the idea only, not the wording. Where a finding below names a
switch, read it as the switch's meaning ("texts from this contact may reach the kid"), whatever revision 3 calls it.

**Verdict:** the hold-for-the-parent design is sound where it matters most:
- nothing of a held body leaves the phone;
- a held MMS is never fetched before an allow, and D10 runs again on release;
- a release is keyed on the exact sender, and a name never releases a number.

The new problems sit at the edges. The allow actions can re-open the first round's H1/H2. An allow grants much more
than "let these texts through". A stranger's chosen text reaches the parent's push. And neither the server nor the
phone bounds a cheap flood well enough.

**Counts: 0 Critical, 1 High, 4 Medium, 7 Low, 5 Info.**

### High

**R2-H1 - The allow paths re-open H1 and H2: an emergency number, or a name that reads as a number, can be let in by
default or with one tap.**
- **Emergency numbers, by default.**
  - D4 rule 5 allows any contact whose "may text the kid" is in effect (design `:427-455`, `:479-490`). The migration
    turns it on for every existing contact (`:448-451`; revision 3 keeps a per-contact text-in switch, whatever it
    depends on).
  - The calls notes keep contacts whose number is an emergency number in the phone book (`calls.md`, "UI").
  - So a phone with a "113" or "112" contact lets in every SMS whose numeric sender reads "113", the moment SMS
    becomes managed by Vibb. That is exactly the hole H1 closed: anyone can send as "113" (§3).
- **Emergency numbers, by one tap.** Revision 2 says of a held "112": "The parent sees '112' and can decide"
  (`:490`). "Opprett kontakt…" then makes it a contact whose texts come in, permanently.
- **Names that read as numbers.** "Tillat avsendernavnet" on a held alphanumeric row adds the row's text to D17's list.
  Its only limit is 1-11 characters (`:749-760`).
  - H2's spoof, an alphanumeric "+4791234567", is held as a name. The PWA shows the parent a row whose sender is their
    own number, with an allow button.
  - One tap whitelists exactly the spoof H2 closed. The kid then gets a `name:+4791234567` conversation that reads as
    Mum's number.
  - The same goes for an alphanumeric "112".
- **Fix:**
  - **On the phone (the gate):** D4 rule 5 never lets an incoming text through for a contact whose number is an
    emergency number (`Emergency.staticSet(defaultCc)` or the platform's answer). Such a contact keeps its calls; its
    texts go to rule 7 (held), and the release rule follows. Add a `sms_rules.json` vector: a "113" contact with
    text-in on, sender "113", holds.
  - **On the server:**
    - the text-in switch is shown disabled for an emergency-number contact ("Nødnumre kan ikke sende meldinger hit -
      alle kan utgi seg for dem", "Emergency numbers can't text here - anyone can pretend to be one", using
      `emergency::is_emergency`);
    - the held card offers no "Opprett kontakt…" for an emergency number.
  - **D17 names:** a name must hold at least one letter (any Unicode letter after cleaning). That refuses `+4791234567`,
    `112`, `91 23 45 67` and anything else `PhoneNumbers.normalize` accepts. The server refuses the name, and the
    phone ignores a listed name without a letter (both sides; contract vectors).
  - **The held card shows the sender's type:**
    - an alphanumeric row reads "Avsendernavn «{text}»" ("Sender name «{text}»");
    - a name with no letter reads "Utgir seg for å være et nummer - kan ikke tillates" ("Pretends to be a number -
      can't be allowed"), with only "Slett nå".

### Medium

**R2-M1 - An allow grants far more than "let these texts through", and a kid's request can carry an unchecked Matrix
ID.**
- **What D16 says.** "Opprett kontakt…" opens Add Contact with the texts-in switch on, and "the other switches follow
  D3's defaults" (`:719-722`).
- **What Add Contact actually does.** Today's form has only name and number (`device_calls.html:210-220`). A new
  contact gets every flag at its column default: `allow_inbound`, `allow_outbound` and `show_on_home` all 1
  (`0022_calls.sql:30-32`), and texts out and pictures on (D3).
- So letting a held stranger's texts through also:
  - puts them on the kid's Home screen and in his phone book;
  - lets him call and text them, and lets them call him;
  - allows pictures both ways.
- Revision 3's independent switches make the narrow allow possible, but the defaults decide what one tap grants.
- **§14's "Legg til…" has the same problem** (`:1535`). It also prefills the kid's own Matrix ID. The parent can check
  a number, but an `@x:server` the kid typed can point to anyone. Saved, it makes Element X the Message button for a
  person the parent never verified.
- **Fix (the principle; revision 3 owns the words):**
  - **An allow from a held message grants exactly one thing: this sender's texts reach the kid.** The prefilled Add
    Contact shows every switch with that alone on. Calls both ways, the phone book, Home, texts out and pictures stay
    off until the parent turns them on.
  - **The defaults are named per path.** A kid's request starts with "the kid may call and text them"; the rest is
    the parent's choice.
  - The Matrix ID is shown as the kid's suggestion with its own unticked "Bruk Matrix-ID-en" ("Use the Matrix ID")
    switch.
  - Any action that grants more than texts in says so in its confirm.
  - Add a `tests::calls` row for each path's defaults.

**R2-M2 - A stranger's chosen text reaches the parent's push, inbox and card; the design has no cleaning for it.**
- **What carries it.** `sender` (at most 20 characters) is posted by the phone (`:1155-1161`) and shown in:
  - the push title "{phone}: en melding fra {sender} venter" (`:1167-1171`);
  - the inbox event;
  - the "Meldinger som venter" card;
  - the prefill of "Opprett kontakt…"/"Tillat avsendernavnet".
- **What it can hold.** For an alphanumeric sender it is the stranger's GSM-7 text. That alphabet includes LF, CR and
  ESC, plus `<`, `>`, `&` and quotes.
- **What else can write it.** A leaked token or a rooted phone posts any 20 characters, including bidi overrides
  (U+202E).
- **What is already safe and what isn't.** Askama escapes HTML on the page (the only `|safe` in the templates is the
  QR svg). The rest isn't covered:
  - a push title is plain text that the OS shows as is;
  - a line break or override can forge the title's shape ("…fra DNB\nRing 22 22 22 22 …");
  - the stranger chooses words in a push the parent trusts.
- **Fix:**
  - **The intake validates `sender` by its class and refuses the row (400) otherwise:**
    - `number`/`short`: `+` and digits only, 3-16 characters;
    - `alphanumeric`: cleaned with the existing `calendar::clean` grammar (controls, bidi and invisible characters out,
      NFC), 1-11 characters after cleaning;
    - `malformed`/`empty`: `sender` must be empty.
  - The phone sends the same cleaned form (one shared rule, vectors in `sms_contract.json`).
  - **The push words quote and label the sender:**
    - "{phone}: en melding fra avsendernavnet «DNB» venter" ("{phone}: a message from the sender name «DNB» is
      waiting");
    - "{phone}: en melding fra et ukjent nummer, +47 915 55 555, venter" ("{phone}: a message from an unknown number,
      +47 915 55 555, is waiting").
  - Prefill values go through the form's normal escaping and the query string's encoding (a `tests::calls` row with a
    `"<&` name).

**R2-M3 - The `sms-held` intake has no per-phone bound, and the push rate is new logic with no home.**
- **What §7 gives the intake** (`:1155-1161`): `require_device_token`, 16 KiB, at most 50 rows a post, an id format and
  a state vocabulary.
- **What it lacks:**
  - a bound on how many rows a phone may store. A leaked token or a rooted phone posting new ids in a loop grows
    `device_sms_held` without limit for 30 days;
  - a check on `at`/`expires_at`;
  - a rule on which state may follow which. A `released` row re-posted as `held` must not show again or raise again.
- **The rate rule has no home.** "One push per phone and sender per hour, at most 10 per phone per hour" (`:1167`) is
  new logic. `parent_events::raise` knows only `EVENTS_AN_HOUR` (20 events of a kind) and `URGENT_PUSHES_AN_HOUR`
  (urgent kinds only), and `sms_held` is "Urgency normal".
- **The flood arithmetic.** A stranger can make the parent's phone buzz 10 times an hour by rotating sender names,
  which is free with alphanumeric senders, until the phone's held cap of 50 is full: five hours.
- **Fix:**
  - **Bounds:** at most 500 `device_sms_held` rows per phone in 30 days. Past that, new ids answer `{"accepted": 0,
    "capped": true}`, with one `record_phone_event` line (a new `PhoneLine::SmsHeldCapped`, inside the existing
    `PHONE_LINES_AN_HOUR` budget).
  - **Time checks:** `at` no later than the server's now plus 5 minutes; `expires_at - at` between 0 and 24 hours
    (plus a minute).
  - **States:** `held` → `released` | `expired` | `dropped`; `blocked` is final. Other changes are ignored and counted.
  - **The rate** is implemented in `raise` as a per-kind option, `PushBudget { per_hour: 3, per_key_hour: 1 }`, keyed
    by sender:
    - the first three of an hour push;
    - the rest go to the inbox;
    - one digest push at the hour's end: "{phone}: 7 meldinger venter" ("{phone}: 7 messages are waiting").
    3 an hour matches `URGENT_PUSHES_AN_HOUR`.
  - Pin the rate in `sms::tests`.

**R2-M4 - A cheap flood fills the hold for a day, and every later unknown sender is lost without a trace for the
parent.**
- **What the caps do.** The caps never evict (`:699-703`). Past 50 held messages a phone, each new stranger's message
  becomes a body-less `blocked` row (`over_cap`).
- **The cost of filling it.** Fifty messages from five spoofed numbers, or fifty free name senders, fill the hold. For
  24 hours the new teacher's or coach's first text can't be allowed any more.
- **What the parent sees.** Only a blocked row, folded away.
- **More flood effects.** Each hold also requests a sync (`:712-714`). Fifty holds must not mean fifty syncs.
- **Fix:**
  - Split the per-phone cap by class: 30 for numbers, 20 for names. A name flood (the free one) can then never take
    the numbers' places.
  - Lower the per-sender cap for names to 3.
  - When a class is full, post the phone's state with `hold_full: true`. The PWA shows "Ventelisten er full - nye
    ukjente avsendere blir ikke holdt før {time}" ("The waiting list is full - new unknown senders aren't held until
    {time}"), with "Slett alle som venter" ("Delete everything waiting"). That is a bulk `sms_held_drop`; raise its cap
    to 100 ids.
  - D16 says plainly that a flood can make the phone stop holding for up to 24 hours. Allowed contacts are unaffected.
  - The sync request is coalesced by `SyncRunner`, at most one queued per minute for the reason `sms_held`. Pin it in
    `SmsEngineTest`.

### Low

**R2-L1 - D17's case folding must be one function on both sides.**
- The server says "unique after case folding". The phone says "NFC normalisation and case folding".
- Rust's `to_lowercase` and Kotlin's `lowercase()` agree only with `Locale.ROOT` and no full folding. GSM-7 has `ß`,
  `Æ/Ø/Å`, `É` and Greek capitals.
- **Fix:** name the rule ("NFC, then lowercase per Unicode's default mapping, Locale.ROOT, no full folding"). Add
  vectors (`DNB`/`dnb`, `ÆØÅ`, `ß`, `ΣΩ`) to `sms_contract.json`, run on both sides.

**R2-L2 - Expiry needs a clock rule and a re-check at release.**
- **The phone:**
  - Run expiry at every sync, the backstop alarm and at boot.
  - Re-check `now < expires_at` immediately before any release. A phone offline for two days that comes online with
    an allow in its first policy must expire first, then release nothing.
- **The server:** the card renders a `held` row past its `expires_at` by the server's clock as "Utløpt" ("Expired"),
  with no allow actions, even before the phone posts `expired`.
- Both clocks are reasonably trustworthy: the phone's time is locked while managed (`DISALLOW_CONFIG_DATE_TIME`).

**R2-L3 - A "deleted" held body stays in SQLite's free pages.**
- D16 promises that the body is deleted at expiry or "Slett nå". In SQLite it stays in free pages and the WAL until
  they are overwritten.
- **Fix:** open the store with `PRAGMA secure_delete = ON` (cheap at this size) and checkpoint after an expiry pass.
  D18's deletes then mean the same thing.

**R2-L4 - The order inside one policy.**
- A policy can carry both a held row's id in `sms_held_drop` and a new contact for its sender: the parent deleted it,
  then created the contact.
- **Fix:** drops are applied before releases, always. Add a `SmsEngineTest` row.

**R2-L5 - The §14 intake needs the time request's checks, plus three of its own.**
- **The time request's checks:** "3 a day" counted in the phone's own day (`phone_zone`, as r13's
  `EXTRA_TIME_REQUESTS_A_DAY` does) in one `BEGIN IMMEDIATE` transaction; a retried id is one row.
- **Its own:**
  - the server re-normalises the number (`phone::normalize`) and answers `invalid` if it doesn't;
  - it runs `valid_matrix_id`;
  - it cleans the name (`calendar::clean`, 40 characters).
- **The card** marks an emergency or short number ("Kortnummer - kan koste penger" ("Short number - may cost money")
  / "Nødnummer" ("Emergency number")).
- **Is it a channel?** The kid's own text reaching the parent (40 characters, 3 a day, behind `EVENTS_AN_HOUR`) is not
  a smuggling concern. It is the kid talking to the parent, and approval stays a prefill the parent saves (R2-M1).

**R2-L6 - §6.2's bridge: rule out shared-secret login now.**
- Double puppeting by shared-secret login creates a full session for the kid on his homeserver. The Vibb server could
  then read his unencrypted rooms and act as him everywhere.
- **Fix:** write into §6.2 that the later design uses an appservice registration scoped to exactly the kid's user ID
  (exclusive: false) for double puppeting. It must never use a login or access token of the kid's stored on the Vibb
  server. Portal rooms are encrypted (bridgev2 supports it), so the homeserver doesn't keep SMS bodies in the clear.
- Nothing in v1 commits otherwise. The seam holds (R2-I3).

**R2-L7 - Revision 3's per-channel switches: an older launcher must never see "SMS managed".**
- **Why the gate matters more now.** Today's backward-compatibility trick is `sms_enabled = (mode == "open")`. It
  fails closed on an older launcher only while `call_policy.managed` is true. An older launcher with `managed = false`
  is `Unmanaged` and leaves SMS fully open (`EnforcementPlan.kt:187-191`).
- So once Calls can be "not managed" while SMS is "managed by Vibb", the only guard is that the PWA refuses "managed"
  SMS for a phone that hasn't reported `sms_engine_v1` (§7). It also holds for a phone whose launcher is rolled back.
- **The principle to keep in revision 3:**
  - the capability gate covers every combination;
  - `call_policy.managed` stays true whenever either channel is managed, so an older launcher still fails closed;
  - the new per-channel keys say which channel is which.
- Pin it with a contract row: an old-launcher view of "Calls not managed, SMS managed".

### Info

**R2-I1 - D3's deny default is right.**
- Absent per-contact text and picture keys decode as `false`, which `ServerJson`'s no-`coerceInputValues` rule allows
  for an absent key. A `null` would still fail the whole decode, as intended.
- The server must always send them for every contact, pinned by `policy_json_keys_snapshot`. The same holds for
  `sms_names`, `own_numbers`, `mmsc_hosts`, `sms_held_drop` and `contact_requests` in `call_policy` (also when
  unmanaged).
- This holds equally for revision 3's independent switches.

**R2-I2 - Nothing of a held body leaves the phone.**
- **Paths checked:**
  - the `sms-held` row has no body;
  - the push and inbox carry the sender only;
  - held rows are not on the seam, so not on the bridge;
  - no notification;
  - GMS is denied while SMS is managed (§8.6);
  - backup is excluded (I3).
- **Residual, unchanged:** the app-specific SMS token (§8.6). The release delivers only to the kid.

**R2-I3 - The release keeps the seam's order.**
- A released message is a `MessageAdded` with its old `at` and a new `seq`. Any front end, the bridge included, orders
  by `seq` (§6.2), so nothing re-sorts history.
- D18's deletes are `MessageDeleted` events. The seam holds.

**R2-I4 - The read-only history needs no new permission.** It is our own store, read without any SMS permission and
outside the role. With SMS Off the role is kept (D1) and `DISALLOW_SMS` stops sending, so read-only is enforced twice.
With SMS not managed, the role is another app's.

**R2-I5 - Revision 3's "save and share a received picture" is safe in principle, on one condition.** Only our own
re-encoded JPEG (EXIF-free, made by the main process from the isolated process's pixels, D8) may leave:
- saved through a MediaStore insert (no storage permission);
- shared through `ACTION_SEND` with a one-off `FileProvider` grant.

The original MMS part never leaves; its bytes aren't kept anyway. The share sheet lists only unsuspended apps. Where a
picture can go is then the allowlist's business, as for any photo.
