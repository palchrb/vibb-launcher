# 77 - SMS and MMS in Vibb One: one message engine, the kid's screen first, a bridge later

Status: design note by the architect, 2026-11-08, on `main` at 721af132, branch `sms-design`. **Nothing is built.** Nothing
gets built until the user approves this plan (PLAN.md: "Design 77: SMS and MMS client in the launcher, in design").
Both directories are affected. This note supersedes design 09 part A (`docs/design/09-sms-and-fixes.md`), which was
parked in 2026-10. Its platform facts (A1) still hold and are cited here, not repeated.
**Revised 2026-11-08** after the security design QA (`docs/review/qa-77-design.md`, 3 High, 9 Medium, 11 Low, 7 Info):
every finding is fixed or explained, mapped in the review's "Fix round" section.
**Revision 2, 2026-11-09**, on branch `sms-design-2`, after the user answered every question of the first §13 (the
answers are in §11). New in this revision:
- per-contact text and picture switches (D3);
- a stranger's message held for the parent's decision instead of dropped (D16);
- the parent's list of sender names (D17);
- deleting (D18);
- the bridge's shape (§6.2);
- the kid's contact request as an optional, not-approved step (§14).
§13 now holds only what step 0 may bring back.
**Revision 3, 2026-11-09**, on branch `sms-design-3`, after the user's two confirmations and the delta QA of revision 2
(`docs/review/qa-77-design.md`, "Revision 2": 1 High, 4 Medium, 7 Low, 5 Info, every finding mapped in its "Fix round,
revision 2"). New in this revision:
- Calls and SMS each get their own three-way state (D2), replacing the old switches;
- the text and picture switches are independent of the call switches (D3);
- the kid may save and share a received picture (§6.7);
- the delta QA's fixes: no texts from emergency numbers, letters required in sender names, narrow allows, the sender
  cleaned and labelled, the intake bounded, and the hold split into pools.

The user's words:
- 2026-11-07: "Formål er å kunne bestemme hvem som kan sms til og fra tlf også. Men for mitt bruk er jo det beste at alt
  kunne kommet inn i element x".
- 2026-11-08: "Kan du ikke gjøre en kodereview av fossify client en og andre libraries vi trenger for sms og mms og komme
  opp med en plan?" MMS pictures must show from day 1. Unknown senders are a risk; the kids don't need messages from
  alphanumeric senders ("Posten", bank codes) now.
- 2026-11-08: "kan være en fordel om vi kan lage et apparat som kan være en slags grensesnitt man kan både vise som egen
  klient og som senere kan plugges inn i en mulig mautrix bro".
- 2026-11-08: "ikke gift med tanken om at det må være fossify, det er bare et eksempel på noe vi kunne forket".
- 2026-11-09, on the phone book: "er du i telefonboken og har lov å ringe til/fra så kan vi også angi at det er lov å
  sende melding til/fra".
- 2026-11-09, the kid's contact request: "en plus-knapp hvor de kan legge til nr og navn (og eventuelt matrix chat ID)
  og sende forespørsel til foreldre om å legge denne personen til? Så må foreldre godkjenne den kontakten i PWA".

Markers:
- **[F]** marks code read in an upstream repo at the commit listed in §2.0, cited by file:line.
- **[web]** marks a source cited by URL.
- **[verify]** marks what only the emulator, the Jelly Star or a real SIM can answer. §9 step 0 and §10.3 list those
  checks.

## 0. The shape

Vibb One becomes the phone's default SMS app (Android's `ROLE_SMS`), which it takes as Device Owner without a prompt.
Inside it sits a **message engine** (`sms/engine/`). The engine owns everything SMS and MMS do: receive, the parent's
allowlist, the store, send, MMS download and compose, and delivery state. It exposes **one internal interface**
(conversations, messages, an event stream with a cursor, an idempotent send command, read marks, parts by hash).

The engine has two front ends:
- **Front end 1** is the kid's own screen inside the launcher, in Dagen's spirit: a calm list of the phone-book contacts
  he has messages with, a thread view with pictures, the contact sheet's Message button, the lock glance's row, and a
  reply from the notification.
- **Front end 2 (later, not designed or approved here)** is a remote one: the mautrix bridgev2 connector through the Vibb
  server (transport A from PLAN's entry of 2026-11-07). It attaches to the same interface without changes to the
  engine.

The allowlist is enforced **inside the engine**, at receive and at every send, so no front end can bypass it:
- **Contacts.** Each phone-book contact has its own text switches beside the call ones: "Kan sende melding til <navn>"
  (may text the kid) and "Kan få melding fra <navn>" (the kid may text them), plus "Bilder i meldinger" (pictures with
  that contact, on by default). They are independent of the call switches: a contact may text without calling, and
  the other way round (D3).
- **Channels.** Calls and SMS each have their own state, "Av" / "Styrt av Vibb" / "Ikke styrt" (D2). Everything in this
  note applies while SMS is "Styrt av Vibb".
- **Sender names.** The parent may let exact sender names through ("Posten", "DNB"), knowing a fake sender with the same
  name gets through too (D17).
- **Emergency numbers.** The kid can always text one. Texts *from* an emergency number never come in through a contact
  or an allow, since anyone can send as "113". They come in only inside the hour after an emergency call, as from
  anyone.
- **Everyone else is held, not shown.** A message from an unknown number or a sender name not on the list is **held on
  the phone**, never shown to the kid (D16):
  - the parent gets a push at once with who and when, never what;
  - if the parent lets that sender in within 24 hours ("Opprett kontakt…" or "Tillat avsendernavnet"), the held messages
    from exactly that sender reach the kid;
  - otherwise they are deleted.
- **The sender's type** is read from the raw message, so a sender name can't pass as a number.
- **MMS** is fetched only for an allowed sender and only from the SIM's own MMS server.

MMS pictures are shown from the first build. The bytes of an MMS are parsed and its pictures decoded **in an isolated
process**, which hands back raw pixels only. So a hostile MMS can't take down the launcher, which is also the phone app
and the Device Owner agent, and the launcher never decodes an image it didn't encode itself.

**What we reuse, and how:**
- **No fork of any app.** Fossify Messages, QUIK, AOSP Messaging, LineageOS Messaging and GrapheneOS Messaging were
  weighed on equal terms (§4).
- **Vendored:** AOSP Messaging's MMS PDU codec (Apache-2.0, about 7,580 lines of Java), hardened with the fixes GrapheneOS
  and the 2026 Android bulletins made to the same code.
- **The platform does all MMS networking:** `SmsManager.downloadMultimediaMessage` and `sendMultimediaMessage` over the
  carrier's MMS APN.
- **Everything else is written here.**

## 1. What exists today (verified against the code, `main` 721af132)

### Launcher

- `CallPolicy.smsEnabled` (`server/dto/CallPolicy.kt:18`) and `CallRules.smsEnabled` (`calls/CallRules.kt:46`): SMS is
  on or off, nothing finer.
- `EnforcementPlan.restrictSms` (`server/EnforcementPlan.kt:187-191`):
  - true when calls are managed and SMS is off, and when calls fail closed;
  - `AppEnforcer` then sets `DISALLOW_SMS` (`AppEnforcer.kt:431`) and hides `KNOWN_SMS_PACKAGES` (Google Messages, AOSP
    Messaging, `com.android.mms`, STK) plus the default SMS app (`EnforcementPlan.kt:202, 275-282`);
  - the override and the pause never lift this (`docs/notes/launcher/calls.md`).
- Per contact, `inbound`/`outbound` (call rules), `messageApp` (`none`/`sms`/`element`/`signal`) and `messageAddress`
  (`CallPolicy.kt:38-48`, `CallRules.kt:23-38`). The Message button opens the default SMS app's `smsto:` only when SMS is
  on (`calls/MessageButtons.kt:64-97`).
- **No SMS role, receiver, store, UI or SMS permission in the manifest.** `OwnPermissions.kt:39-41` lists `SEND_SMS`,
  `RECEIVE_SMS` and `READ_SMS` only as permissions the dialer role grants.
- **No SQLite anywhere** in the launcher (no Room, no `SQLiteOpenHelper`).
- `PhoneNumbers.normalize` (`calls/PhoneNumbers.kt:47-60`) returns `null` for letters. An alphanumeric sender therefore
  never matches a contact.

### Server

- `call_policy.sms_enabled`, `default_message_app` and the contact flags (`server/src/models.rs:1268-1293`).
- The Calls & Contacts page's SMS switch (`docs/notes/server/calls-and-contacts.md`).

## 2. The code review

### 2.0 What was read

All cloned `--depth 1` into a scratch directory, read only. Nothing was built or run.

| Short | Repo | Commit / date | Licence |
|---|---|---|---|
| FM | github.com/FossifyOrg/Messages | 02f511b2 (release 1.10.2, 2026-10-24) | GPL-3.0 |
| ML | github.com/FossifyOrg/mmslib (the MMS library FM uses, `org.fossify:mmslib:1.0.1`) | ebfbb30d (2026-10-09) | Apache-2.0 |
| QK | github.com/quik-sms/quik (QUIK, the maintained QKSMS fork; it bundles its own `android-smsmms`) | 02542049 (v4.3.7, 2026-09-23) | GPL-3.0 |
| AM | android.googlesource.com/platform/packages/apps/Messaging | de315b76 | Apache-2.0 |
| GM | github.com/GrapheneOS/Messaging | 51c07da3 (2026-11-05) | Apache-2.0 files, no top-level LICENSE [verify] |
| T | AOSP frameworks/opt/telephony (`InboundSmsHandler`, `WapPushOverSms`) | 30780691 | Apache-2.0 |
| FB | AOSP frameworks/base `telephony/common/com/google/android/mms/pdu/PduParser.java` | main, fetched 2026-11-08 | Apache-2.0 |
| MS | AOSP packages/services/Mms (`MmsService`, `DownloadRequest`) | main, fetched 2026-11-08 | Apache-2.0 |

### 2.1 Lineage

The klinker lineage:
- klinker41/android-smsmms (Apache-2.0, **archived**) → tibbi → FossifyOrg/mmslib.
- QKSMS and QUIK bundle their own copy of the same library.

The AOSP lineage:
- AOSP Messaging; the platform's own `com.google.android.mms` copy (FB).
- LineageOS's and GrapheneOS's Messaging forks.

**Every one of these MMS stacks parses PDUs with descendants of the same 2007 Esmertec/AOSP `PduParser`.**

ML re-heads that file "Copyright (C) 2015 Jacob Klinker" (ML `pdu_alt/PduParser.java:1-15`; AM's copy keeps
"Esmertec AG / The Android Open Source Project", AM `mmslib/pdu/PduParser.java:2-3`). This licence-hygiene slip matters
if we copied from ML. We won't.

### 2.2 The default-SMS-app duties, and how the candidates do them

**The platform side** (T; design 09 A1 has the role's manifest requirements from `roles.xml`):

- **Receive SMS.** The platform joins the parts of a multipart SMS in its raw table and sends one ordered `SMS_DELIVER`
  to the default app only.
  - When that broadcast finishes, it sends `SMS_RECEIVED` to every holder of `RECEIVE_SMS`.
  - It then deletes the raw rows "anyway", whatever the default app did (T `InboundSmsHandler.java:1841-1852, 1896`).
  - **So a default app that dies between `onReceive` and its own write loses the message for good.** Nothing else
    writes it.
- **Before the first unlock** the platform holds messages (T `:1105, 1202`).
- **The platform's own block list** (`BlockedNumberContract`) is checked before dispatch (T `:959`; MMS at
  `WapPushOverSms.java:281`). It is a deny list of exact numbers. It can't express "contacts only".
- **MMS arrive as a WAP push.**
  - The platform parses the m-notification-ind itself (`WapPushOverSms.java:266`, with a catch).
  - It sends `WAP_PUSH_DELIVER` with the raw PDU in `data`, plus `address`: the push SMS's sender, which is the carrier's
    push gateway, not the person (`:371, 404`).
- **The platform's MMS service does the MMS HTTP** over the MMS APN.
  - `DownloadRequest.doHttp` (MS `:75-93`) writes the raw response to the app's content URI.
  - It parses or persists only if auto-persisting is on (MS `:108-113`), which is off for a normal default app.
  - **The app parses the downloaded bytes itself**, so the retrieve-conf parser runs in our process.
- **MMS needs mobile data for the MMS APN.** `MmsService` refuses with `MMS_ERROR_DATA_DISABLED` when
  `isDataEnabledForApn(TYPE_MMS)` is false (MS `:285, 384`).

**Fossify Messages (FM + ML):**

- **SMS receive** (`receivers/SmsReceiver.kt`):
  - `goAsync` plus a background thread, `finish` in `finally` (`:33-73`). Good.
  - It takes the address from the *last* part, with the comment "this is how it has always worked, but need to revisit
    this" (`:41-42`).
  - It dates the message with the receive time, not the SMSC's (`:57`).
  - **Before writing the message** it looks up the contact photo and decodes a bitmap (`:89-90`). A slow or crashing
    decode there loses the message (see the platform facts above).
  - It writes to the Telephony provider (`extensions/Context.kt:821-849`, every exception swallowed into id 0). Then it
    writes the same message again into its own Room cache (`databases/MessagesDatabase.kt:24-34`, schema v11). That is
    two stores to keep consistent.
- **Blocking:**
  - On receive, `isNumberBlocked` (Commons, `BlockedNumberContract`), a keyword filter, and "block unknown numbers"
    against the system contacts (`SmsReceiver.kt:48-55`).
  - On read, a second filter (`Context.kt:141-145`).
- **MMS receive** (ML), step by step:
  - `PushReceiver` parses the notification and **persists it into the provider before any sender check** (ML
    `transaction/PushReceiver.java:184`).
  - It always downloads (`:229`) through `DownloadManager`. That calls `SmsManager.downloadMultimediaMessage` with a
    **runtime-registered, `RECEIVER_EXPORTED`** result receiver (ML `DownloadManager.java:55-56, 97`). If the process
    dies before the result, the result is lost. The notification row stays in the provider, and the de-duplication set
    is a static in-memory `HashSet` (`PushReceiver.java:85, 200`).
  - `MmsReceivedReceiver` then:
    - reads the whole file into one array by its length (`MmsReceivedReceiver.java:118`) on a raw `new Thread` (`:109`);
    - parses it to find the sender, `pdu.getFrom().getString()` with no null check (`:190`);
    - only **after the full download** checks `isAddressBlocked` (`:124`).
  - **So a blocked or unknown sender's MMS is fetched and parsed anyway.** The fetch alone hands the sender the phone's
    MMS user agent and UAProf: Enea's "MMS Fingerprint" research on NSO Group's technique
    ([web](https://enea.com/insights/dusting-off-old-fingerprints-nso-groups-unknown-mms-hack)).
  - **An exception there is uncaught on a raw thread, which kills the app's process.**
  - FM's `MmsReceiver` falls back to "the latest MMS" when the URI is null (`receivers/MmsReceiver.kt:46-50`) and shows
    a toast from a receiver on error (`:59-61`).
- **SMS send** (`messaging/SmsSender.kt`, `MessagingUtils.kt`):
  - It writes to the outbox before sending. Good.
  - `divideMessage` + `sendMultipartTextMessage` with per-part sent intents and a delivery intent on the last part, made
    distinct by the message URI as data (`SmsSender.kt:56-105, 112-122`).
  - Subscription ids are carried. Group messages are sent as separate SMS unless "send group as MMS" is set
    (`Messaging.kt:52-69`).
- **Quality:**
  - 116 Kotlin files, 14,729 lines; 67 files import Fossify Commons (146 distinct imports): UI, contacts, blocking,
    threading.
  - **No unit or instrumentation tests at all.**
- **Issues** (§2.5, [web]): 153 issues and PRs match "MMS". None mentions Norway, Telia, Telenor or Ice.
  - Open: "MMS lost when received while no data" #298, "MMS notified but missing" #646, "Reply to group message is
    sent to unrelated recipient" #595, "can't send MMS (photos) on Samsung" #767.
  - Fixed: an exported `DeleteSmsReceiver` let any app delete messages (#834, closed 2026-07).

**QUIK (QK):**
- 35,244 lines of Kotlin, outside its bundled `android-smsmms` (a copy of the klinker lineage, the same parser and flow).
- Built on Realm 10.15 (MongoDB deprecated the Realm SDKs in 2024), Dagger 2.18 (2018) and RxJava 2.1.4.
- Three test files.
- Active (v4.3.7, 2026-09-23), 299 open issues. QKSMS had CVE-2024-3430 (a backup file exposed through the manifest;
  [web](https://nvd.nist.gov/vuln/detail/cve-2024-3430)).

**AOSP Messaging (AM):**
- Its README: "This app is not actively supported and the source is only available as a reference. This project will be
  removed from the source manifest sometime in the future."
- Its MMS flow is the reference we want:
  - a manifest-registered, explicit `SendStatusReceiver` for download and send results, through a `FileProvider` URI
    (AM `sms/MmsSender.java:251-267`);
  - NotifyResp and Acknowledge composed with `PduComposer` and sent **through the platform**, `sendMultimediaMessage`
    (`MmsSender.java:89-135`), never over its own HTTP. ML sends those over its own `HttpUtils` and APN code.
- Java, about 113,000 lines with its tests.

**LineageOS Messaging:** AM plus light maintenance (last commits 2026-10/11: a boot-notification fix, dark-theme fixes).
Same verdict as AM.

**GrapheneOS Messaging (GM):**
- Very active (several commits a day in 2026-11).
- 1,300 Kotlin files (181,000 lines with tests) plus 62,000 lines of Java; 455 test files.
- **It has hardened the PDU parser with regression tests** (`app/src/test/java/.../mmslib/pdu/PduParserTest.java`,
  `PduPartsParserTest.java`: oversized lengths, invalid From length, unknown constrained media types "do not crash").
- **But `minSdk = 36` (Android 16, `app/build.gradle.kts:59`).** Our launcher's `minSdk` is 34, so it can't run as our
  app on an Android 14/15 phone.

### 2.3 The PDU parser: attack surface any stranger can reach

**Where it runs:**
- An m-notification-ind is parsed in `com.android.phone` (`WapPushOverSms.java:266`) and again by the default app.
- The m-retrieve-conf (the message itself, after download) is parsed **only by the default app, in its process**.

**What the code shows:**

- **Part lengths are unchecked.** `parseParts` reads an attacker's `dataLength` and runs `new byte[dataLength]` without
  comparing it to what remains:
  - ML `pdu_alt/PduParser.java:859, 914`; AM `mmslib/pdu/PduParser.java:876, 931`; FB `:848, 903`.
  - A few bytes ask for up to 2 GB, giving `OutOfMemoryError`, which no `catch (RuntimeException)` stops.
  - `parseUnsignedInt` has no 5-octet limit and overflows (ML `:975-1000`).
  - GM added the check (`if (dataLength > pduDataStream.available()) return null`, GM `:940-942`).
- **A null child dereference.** A failed `multipart/alternative` child parse gives `null`, then `childBody.getPart(0)`
  throws (ML `:919-921`, AM `:936-938`, still in GM `:948`).
- **An unbounded index.** `PduContentTypes.contentTypes[parseShortInteger(...)]` is indexed without a bound check (ML
  `:1594`, FB `:1594`), giving `ArrayIndexOutOfBounds`. GM bounds it (`:1623-1628`).
- **The platform's copy got four CVEs in 2026** ([web], CVE.org records found by the research agent, not re-checked line
  by line):

  | CVE | Where | Effect | Bulletin |
  |---|---|---|---|
  | CVE-2026-49932 | `parseParts` | heap out-of-bounds, "Critical" | 2026-09 ([osv](https://osv.dev/vulnerability/ASB-A-513581684)) |
  | CVE-2026-55265 | the parser | remote denial of service | 2026-10 |
  | CVE-2026-58865 | the parser | remote denial of service | 2026-10 |
  | CVE-2026-58814 | `parseContentType` | integer overflow | 2026-11 |

  - Also CVE-2026-58882, the "SMS of Death": an 8-byte WAP push crashes `com.android.phone`; fixed 2026-11, Android 14-17
    ([web](https://bleepingcomputer.com/news/security/new-android-sms-of-death-exploit-can-remotely-crash-unpatched-phones)).
  - **None of these fixes reach the copies apps bundle** (ML, QK, AM).
- **Pictures are the other half of the surface.**
  - Stagefright (2015) came in through MMS, but the bugs were in the media decoders, not the PDU parser
    ([web](https://kb.cert.org/vuls/id/924951)).
  - Samsung's Qmage (CVE-2020-8899) was a zero-click RCE via an MMS image in Skia's Quram codec, which Samsung Messages
    decoded automatically ([web](https://bugs.chromium.org/p/project-zero/issues/detail?id=2002)).
  - FM decodes pictures with Glide in its own process, also for the notification (`MmsReceiver.kt:69-78`).

**For us:** a crash in a launcher receiver is a crash of the Home, phone-app and Device Owner process. **Untrusted MMS
bytes must never be parsed or decoded in the launcher's main process** (D8).

### 2.4 Licences

- **Apache-2.0** (AM, the platform, ML, GM's AOSP-headed files) is compatible one way with GPL-3.0: we may copy it into
  our GPL-3.0 launcher, keeping its headers and adding a `NOTICE.md` entry. ML's re-headed files are not a clean source
  (§2.1). GM's own additions carry no headers and the repo has no top-level LICENSE [verify before copying any GM line].
  Until confirmed, we re-implement GM's fixes ourselves on AM's code, using GM's tests only as a checklist.
- **GPL-3.0** (FM, QK) could be copied into the launcher too, but we take nothing from them (§2.6).
- **Placement.** The new code goes into a new `sms/` package, not into the MIT directories `ui/list/` and `apps/`. The
  vendored Java stays in its own package, `sms/vendor/pdu/` (D9), the second exception to the launcher's "one Java file"
  convention, kept as Java so upstream fixes stay diffable.

### 2.5 Other ways a not-allowed sender could reach the kid

| Path | What happens today, or after this design |
|---|---|
| `SMS_RECEIVED` to every `RECEIVE_SMS` holder (T `:1841-1852`); data SMS (`DATA_SMS_RECEIVED`); `WAP_PUSH_RECEIVED` | Deny `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `READ_SMS`, `SEND_SMS` to every non-system requester by policy, as `CALL_PERMISSIONS` are denied today. These are hard-restricted permissions, so whether a Device Owner may set them is [verify] (design 09 A3). |
| The Telephony provider (`content://sms`, `mms`) | Readable by `READ_SMS` holders and system apps. **We write nothing to it** (D6). Only the platform's transient raw table holds a message until our receiver finishes. |
| RCS (Google Messages over data) | `DISALLOW_SMS` and the role don't cover it. Google Messages stays suspended and hidden whenever the mode isn't `unmanaged` (today: only when SMS is off). Contacts' RCS messages would then never arrive: §8.5 is the path to switch RCS off. |
| An alphanumeric sender whose text reads like a contact's number or "112" | Android returns the text as the address without its type (QA H2). D4 rule 1 reads the type from the raw PDU and blocks it. |
| A crafted MMS push naming an allowed sender and an outside URL | D10 checks `/TYPE=PLMN` and the MMSC host before any fetch (QA H3). |
| App-specific SMS tokens (`createAppSpecificSmsToken`, no permission needed) | A message with an app's token goes to that app and never reaches us. No Device Owner switch: a named residual, §8.6. |
| Google Play services (system, holds `RECEIVE_SMS`) | Gets every message, blocked ones too, and its SMS User Consent API can show a stranger's text. Denied in "Styrt av Vibb" (the user, 2026-11-09), after step 0's check (§8.6). |
| Bluetooth MAP (a paired car or PC) | May send SMS around the engine [verify]. Step 0 tests it; §8.6. |
| SIM toolkit (STK) | Can show text and send SMS. It stays suspended in the `managed` mode as in `off`. |
| Carrier messaging services, visual voicemail | System or privileged, they filter before us (`filterSms`, T `:1165`). Out of our control and not a path for strangers. |
| Cell broadcast (Nødvarsel) | Not SMS. The cell-broadcast receivers stay unsuspended (`AppEnforcer.kt:606-641`). Unchanged. |
| Notifications | Only ours, and only for accepted messages. The glance reads our engine directly (§6.8). |
| Other chat apps (WhatsApp, Element X) | Outside this design. The allowlist and the notification rule cover them as today. |

### 2.6 Verdict per component

| Component | Verdict | Why |
|---|---|---|
| Receivers, role, send, outbox, delivery state | **Write our own** | Each is about 100-300 lines. FM's versions have the issues above, and the gate and the seam must sit inside them. The platform APIs are small: `getMessagesFromIntent`, `divideMessage`, `sendMultipartTextMessage`. |
| MMS transport (HTTP, APN, NotifyResp, Ack) | **Use the platform** (`SmsManager`) | AM's pattern. No own HTTP or APN code: ML's `service_alt/` and `HttpUtils` are exactly the parts users report breaking. |
| MMS PDU codec: `PduParser`, `PduComposer`, `PduHeaders`, `PduPart`, `PduBody`, `EncodedStringValue`, `CharacterSets`, the message classes (not `PduPersister`) | **Vendor from AM** (Apache-2.0, about 7,580 lines), then make pure JVM and harden (D9, §8.1) | Fifteen years of carrier quirks we don't want to rediscover. Writing a WSP codec from scratch is the larger risk. |
| The store | **Write our own** (SQLite) | D6. |
| Image decode | **Platform `ImageDecoder`, inside our isolated process**, raw pixels out; the main process encodes | D8. |
| UI | **Write our own**, in design 34's look | Neither FM's Commons UI nor GM's Compose fits the launcher's Views/Nunito look. |
| A whole app (fork) | **No** | §4. |

## 3. Can a phone tell a real "DNB" from a fake one?

**No.** The evidence, gathered by a research agent on 2026-11-08 and summarised here:

- **The protocol.**
  - An SMS sender ID, alphanumeric or a number, is a text field (TP-OA, 3GPP 23.040 §9.1.2.5) set by whoever submits the
    message (an aggregator or SMSC).
  - Nothing authenticates it end to end, and Android's `SmsMessage` carries no verification field
    ([web](https://developer.android.com/reference/android/telephony/SmsMessage)).
  - The STIR/SHAKEN-style verdict exists only for calls (`Call.Details#getCallerNumberVerificationStatus`).
  - Caveat: we found no 3GPP sentence saying "unauthenticated" in so many words. The conclusion follows from no such
    mechanism existing.
- **The law in Norway.**
  - Since 1 Jan 2025, ekomforskriften § 9-5 obliges operators to block calls and SMS whose sender has no right to the
    A-number, can't be routed, or "gjelder svindel" ([web](https://lovdata.no/dokument/SF/forskrift/2024-12-20-3410)).
    That rule is about numbers.
  - Nkom's consumer page (2026-01-30) says plainly "Ved SMS kan avsenderadressen også være en tekst" and describes no
    rule for text names ([web](https://nkom.no/telefoni-og-telefonnummer/telefonsvindel)).
- **The operators' filters.**
  - They run AI fraud filters on SMS, which Nkom put under supervision in 2025-12
    ([web](https://nkom.no/aktuelt/nkom-varsler-tilsyn-etter-danske-sms-avsloringer)).
  - They run "Det digitale skjoldet" against spoofed numbers.
- **The registry.** There is a **voluntary, operator-run "SMS SenderID-registry"**. A registered name is whitelisted and
  tied to a controlled sender, so fakes of *registered* names are stopped. Nkom and Økokrim urged public agencies to
  register (BT, 2024-03-20, [web](https://www.bt.no/innenriks/i/5BAkRK/oekokrim-og-nkom-ber-offentlige-etater-ta-sms-grep-mot-svindel)).
  - **Not confirmed:** which operators run it today, and whether Posten, DNB or Vipps are on it.
- **Elsewhere.**
  - Finland made sender-ID registration mandatory from 4 May 2026 (Traficom).
  - Sweden opened a voluntary register on 1 Aug 2026.
  - The UK and Singapore have registries.
  - There is no EU rule.
  - All of these act in the network, and none gives the phone a signal.

So:
- A message that reaches the phone has passed whatever the operator filters. That proves nothing about who sent it.
- **The only trustworthy signal on the phone is a match against the parent's list.** Even that is a number match, and
  numbers can be spoofed too. Norway's § 9-5 makes spoofing a Norwegian mobile number harder, but not impossible from
  abroad.
- **A text sender can even look like a number.** Android hands an app an alphanumeric sender's text without its type,
  so a sender *named* "+4791234567" would look like the parent's number. The phone must read the type from the raw
  message (D4 rule 1). Norway's § 9-5 doesn't touch text senders at all, so this is the cheapest spoof there is.
- Should the user later want "Posten" or "Vipps" let through, it would be a parent-typed list of exact sender names
  (D17), with the honest caveat that a fake "Posten" passes too.

## 4. The options, on equal terms

| | In-launcher engine (recommended) | Fork of Fossify Messages | Fork of QUIK | Fork of AOSP/LineageOS Messaging | Fork of GrapheneOS Messaging |
|---|---|---|---|---|---|
| Maintained upstream | n/a (ours) | yes (1.10.2, 2026-10) | yes, slower (v4.3.7, 2026-09) | AOSP: "not actively supported"; Lineage: lightly | very (daily) |
| MMS stack | platform transport + AM codec, hardened, isolated | ML: own NotifyResp HTTP, exported runtime receiver, unbounded parser, check after download | same lineage as ML | platform transport, unbounded parser | platform transport, partly hardened parser |
| Size we'd own | engine + UI, about 4-6k lines of Kotlin + 7.6k vendored | 14.7k + Commons (67 of 116 files) | 35k + Realm/Dagger/Rx | about 100k Java | 240k+ |
| Tests | ours, JVM, lean | none | 3 files | some | many |
| Runs on our phones (API 34+) | yes | yes | yes | yes | **no (minSdk 36)** |
| Allowlist enforced where | in the engine, in the receiver | in the fork's receiver, rules sent over IPC from the launcher | same as FM | same as FM | same as FM |
| Engine/bridge seam | one in-process interface | a new AIDL service in the fork + a policy channel in: two cross-app contracts | same | same | same |
| Release work | none new | a second APK: workflow, signing, catalog row, AIDL contract (the music app's precedent) | same | same | same |
| Look | design 34, Dagen | Commons UI to rewrite or live with | its own | its own | Compose, its own |

**Recommendation: the in-launcher engine.**

- **The gate belongs where the rules are.** The launcher already holds the rules, the fail-closed state, the time-rule
  block, the emergency callback window and the DE boot copy (`CallPolicyStore`). A fork must get all of that over IPC and
  fail closed whenever the channel is down.
- **The user's engine requirement** (one interface, two front ends) is one in-process Kotlin interface here. In a fork it
  is two cross-app contracts (rules in, messages out) on top of the bridge's own.
- **What a fork would actually keep is small.** In every candidate, the parts worth having are the platform calls (small)
  and the PDU codec (vendored either way). Everything else (UI, store, blocking) would be rewritten for the look, the gate
  and the seam.
- **The trade-offs we accept:**
  - We own the interop bugs that FM's users found the hard way. We mitigate by copying AM's transport pattern, which is
    the platform's own way.
  - The launcher grows by an SMS engine, so its crash containment (§6.10) and the isolated parser (D8) are not optional.

Rejected outright: mautrix-gmessages, a server-side modem, the launcher as a Matrix client (PLAN's entry of 2026-11-07),
and Silence and Simple SMS Messenger (abandoned 2021 and 2023).

## 5. Decisions

**D1. Vibb One takes `ROLE_SMS` when the parent sets SMS to "Styrt av Vibb" (`managed`)**, with
`DevicePolicyManager.setDefaultSmsApplication`.
- It checks `RoleManager.isRoleHeld(ROLE_SMS)` afterwards, because the call returns silently on failure (design 09 A1).
- It clears `DISALLOW_CONFIG_DEFAULT_APPS` around the change, as for the dialer.
- **Hand-back** (QA L7):
  - The role goes back to the previous holder when SMS becomes `unmanaged`. Design 09 A6's order applies.
  - In `off` the role is kept if we hold it, and never taken for `off`. `DISALLOW_SMS` stops everything anyway, the
    history stays where it is, and the hidden Google Messages never gets a role it can't use.
  - The calls channel doesn't touch the SMS role, and the other way round (D2).
- **No role, no SMS:** while SMS is `managed` and the role isn't ours, `DISALLOW_SMS` is set.
- **`DISALLOW_SMS` loses messages; it doesn't hold them** (QA M4a; T `InboundSmsHandler.java:677-707, 1385-1450,
  1888-1897`). The message is acknowledged to the network once it is in the raw table, the receive app-op refuses the
  broadcast, and the rows are deleted. The operator never retries.
  - So in `off`, in "managed but no role", and on a launcher too old for this design, every incoming SMS is **lost**.
  - `off` has always been like that. The PWA now says so in words (§7), and step 0 confirms it on a device.

**D2. Calls and SMS each have their own state** (the user, 2026-11-09: "Her bør vel egentlig det at man slår på SMS bety
at SMS er på, og det er filtrert/managed av Vibb. Kanskje man burde kunne slå på individuelt hvorvidt calls og sms er
managed av vibb eller ikke").

**Today** the Calls & Contacts page has three switches (`device_policy`, migration 0022):
- `calls_managed` ("Manage calls and SMS");
- `calls_enabled`;
- `sms_enabled`, a plain on/off. On is unfiltered; off is `DISALLOW_SMS`.

An unmanaged phone has both channels unrestricted, whatever `sms_enabled` says (`EnforcementPlan.restrictSms`:
`Unmanaged -> false`).

**The model: one three-way choice per channel.**

| Channel | `off` | `managed` | `unmanaged` |
|---|---|---|---|
| Calls (`calls_mode`) | only emergency calls | only allowed contacts call and are called (today's managed + calls on) | anyone, any app (today's unmanaged) |
| SMS (`sms_mode`) | `DISALLOW_SMS`; messages are lost | Vibb One is the SMS app and filters (this design) | any SMS app, no filter (today's "SMS on") |

**Why a three-way choice per channel, not an on switch plus a "managed by Vibb" switch.**
- An on switch plus a managed switch has four combinations, one of which means nothing ("off, but managed"). Three
  states have no such combination.
- One control per channel shows every state and says what it does, in the same segmented control the PWA already uses
  (Kid Settings' sound mode, design 34 P9).
- "Turning SMS on means managed" is the control's default. "Styrt av Vibb" sits in the middle, marked "anbefalt"
  ("recommended"). A phone enrolled from now on starts with SMS `managed` (below). Only a parent who taps "Ikke styrt"
  gets an unfiltered channel.

**The PWA words.** One row per channel, "Samtaler" ("Calls") and "SMS". Each has a segmented control "Av" / "Styrt av
Vibb" / "Ikke styrt" ("Off" / "Managed by Vibb" / "Not managed"), and a sub-line for the chosen state:

| State | Calls (nb / en) | SMS (nb / en) |
|---|---|---|
| Av | "Bare nødnumre kan ringes." / "Only emergency numbers can be called." | "SMS er av. Meldinger til telefonen går tapt." / "SMS is off. Messages to the phone are lost." |
| Styrt av Vibb | "Bare kontaktene du har tillatt kan ringe og bli ringt." / "Only the contacts you allow can call and be called." | "Bare kontaktene og avsendernavnene du har tillatt. Andre meldinger venter på deg." / "Only the contacts and sender names you allow. Other messages wait for you." |
| Ikke styrt | "Alle kan ringe, og telefonen kan ringe hvem som helst." / "Anyone can call, and the phone can call anyone." | "Alle kan sende SMS, og alle meldingsapper virker. Vibb filtrerer ikke." / "Anyone can text, and every messaging app works. Vibb doesn't filter." |

The old "Manage calls and SMS" switch goes. Each channel's sub-line replaces it.

**The wire.** `call_policy` gains `calls_mode` and `sms_mode`, each `"off" | "managed" | "unmanaged"`, always sent.
- The old keys stay, derived for launchers from before this design, and they fail closed:
  - `managed = calls_mode != "unmanaged" || sms_mode != "unmanaged"`;
  - `calls_enabled = calls_mode != "off"` (with calls `unmanaged` but SMS restricted, an old launcher filters calls by
    the contacts: stricter than asked, never looser);
  - `sms_enabled = sms_mode == "unmanaged"` (an old launcher turns `managed` into `DISALLOW_SMS`, so messages are lost
    and the PWA says so).
- **A new launcher reads `calls_mode`/`sms_mode` when present.** Once it has seen them, a policy without them is
  rejected whole (`REJECT_SUSPECT`), like a missing `call_policy` today (`PolicyGate.judgeFresh`). A server rolled back
  can't loosen a channel silently.
- Both keys are pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
- The DE boot copy (`BootCallPolicy`) gains `calls_mode`; its version goes up, and an old copy reads as unknown (fail
  closed), as its rule already says.

**An older launcher** (no `sms_engine_v1` in its reports; QA R2-L7). The rule: **the capability gate covers every
combination with SMS managed, and `call_policy.managed` stays true whenever either channel isn't `unmanaged`.** An
older launcher with `managed = false` is `Unmanaged` and leaves SMS fully open (`EnforcementPlan.kt:187-191`). The
derived `managed` above is never false while SMS is `off` or `managed`. A contract row pins the old-launcher view of
"Calls not managed, SMS managed": `managed: true, calls_enabled: true, sms_enabled: false`. Then:

- The PWA offers only the combinations today's wire can express:
  - Calls `managed`/`off` with SMS `off`/`unmanaged`;
  - both `unmanaged`.
- "Styrt av Vibb" for SMS, and calls "Ikke styrt" with SMS restricted, show disabled with "Oppdater Vibb One først"
  ("Update Vibb One first").
- If such a state is already stored (a phone set up again with an old build), the server sends the fail-closed derived
  keys above. The calls report says "Telefonen må oppdateres: SMS går tapt og samtaler er bare til kontakter til da"
  ("The phone needs an update: SMS is lost and calls are contacts-only until then").

**When only one channel is managed:**
- **SMS managed, calls not managed:**
  - The launcher doesn't take the dialer, screening or redirection role (it hands back a dialer role it took). Third
    parties' call permissions aren't denied, and `DISALLOW_OUTGOING_CALLS` follows today's unmanaged rule.
  - The phone book passes calls on to the system dialer, as unmanaged today.
  - The SMS engine works on the policy's contacts as usual. The Meldinger screen and the contact sheet's Message button
    work.
- **Calls managed, SMS not managed:** today's "managed + SMS on". Calls are filtered, and SMS is any app, unfiltered;
  Google Messages stays usable.
- **Fail closed** (rules unknown) covers both channels as today: emergency calls only, and SMS held (D15), or lost
  without the role (D1).

**Every restriction stays liftable** from the PWA, per channel, by choosing "Ikke styrt". The offline override PIN and
the pause stay the existing explicit exception for calls and SMS: they lift only a time rule's block, never a channel's
state (launcher/CLAUDE.md, "Standing rule on restrictions").

**The migration** (`0130_sms.sql`) maps every existing combination to the same behaviour. No phone becomes less
restricted, and none more:

| `calls_managed` | `calls_enabled` | `sms_enabled` | → `calls_mode` | → `sms_mode` | Why |
|---|---|---|---|---|---|
| 0 | any | any | `unmanaged` | `unmanaged` | Unmanaged today leaves both channels alone (`restrictSms` false). |
| 1 | 0 | 0 | `off` | `off` | Emergency calls only; `DISALLOW_SMS`. |
| 1 | 0 | 1 | `off` | `unmanaged` | Emergency calls only; SMS unfiltered. |
| 1 | 1 | 0 | `managed` | `off` | Contacts-only calls; `DISALLOW_SMS`. |
| 1 | 1 | 1 | `managed` | `unmanaged` | Contacts-only calls; SMS unfiltered. |

- No phone is migrated to SMS `managed`: that needs the engine and the parent's choice.
- The old columns stay, unused but for a rollback, and are dropped in a later migration.
- `roles_changed_at` is stamped when either mode changes (design 09 B1).
- **A phone created from now on** gets `calls_mode` as today's default (`unmanaged`) and `sms_mode = managed`. The QR
  installs the latest launcher, which has the engine. Until its first report, the PWA shows SMS as "Styrt av Vibb" with
  "Venter på telefonen" ("Waiting for the phone").

**D3. Each contact has its own text switches, independent of the call switches** (the user, 2026-11-09: "om det koster
lite så sett på at de kan være uavhengige").

**The switches.** Three new per-contact switches sit in the PWA's contact form, in a "Meldinger" ("Messages") group under
the call switches:

| Switch (nb / en) | Column / policy field | Means |
|---|---|---|
| "Kan sende melding til {name}" / "Can text {name}" | `device_contacts.sms_in` / `sms_in` | the contact may text the kid |
| "Kan få melding fra {name}" / "Can get texts from {name}" | `device_contacts.sms_out` / `sms_out` | the kid may text the contact |
| "Bilder i meldinger" / "Pictures in messages" | `device_contacts.mms` / `mms` | pictures both ways with this contact |

**The rules** (`SmsRules`):
- may text the kid = `sms_in`, never for an emergency-number contact (D4 rule 5). Its switch shows disabled in the PWA
  with "Nødnumre kan ikke sende meldinger hit - alle kan utgi seg for dem" ("Emergency numbers can't text here - anyone
  can pretend to be one"), judged by `emergency::is_emergency`. The server never sends `sms_in: true` for one (QA
  R2-H1);
- the kid may text = `sms_out`;
- pictures = `mms` and the text direction in question.

A contact may text without being allowed to call, and the other way round. Independence costs little: the call
switches' meaning doesn't change, and the phone simply reads three more booleans.

**Where a text-only contact shows on the phone:**
- In Meldinger: in the list once there are messages, and in "Ny melding" when `sms_out` is on.
- **Not** in the phone book or on Home: those stay the call side's (`outbound`).
- The contact sheet's Message button stays as it is: on a phone-book contact, when `sms_out` is on.

**The contact row's summary** gains "· Melding begge veier" / "· Melding inn" / "· Melding ut" ("· Texts both ways" /
"· Texts in" / "· Texts out") beside the call summary.

**Defaults.**
- **New contacts:** all three on, as every flag of Add Contact and Add from the family is today.
- **The migration:** `sms_in = inbound`, `sms_out = outbound`, `mms = 1`. A contact's texting starts exactly where its
  calling is, so nothing opens wider than the parent set for calls.
- A server without the keys sends none, and the launcher's defaults deny (`PolicyContact` decodes absent keys as
  `false`). That fails closed.

**What's dropped.** `message_app` still only picks what the Message button opens. The first draft's "answer for
24 hours" idea is dropped: the parent can simply turn on "Kan sende melding til {name}".

**D4. The receive rule** (pure `sms/engine/SmsRules.kt`; the first rule that applies wins):
1. **The sender's type comes from the raw PDU, not from the text** (QA H2).
   - Android decodes an alphanumeric TP-OA (TON `101`) as GSM-7 text and returns it from `getOriginatingAddress()`
     without saying so (frameworks/base `GsmSmsAddress.java:60-64, 97-98`). An alphanumeric sender reading
     "+4791234567" would otherwise pass as the parent.
   - A pure `SmsSenderType.of(pdu, format)` reads the TP-OA type octet of a 3GPP SMS-DELIVER (after the SMSC address,
     the first octet and the OA length) and returns `NUMBER`, `ALPHANUMERIC` or `MALFORMED`.
   - Every part must give the same type and the same address, or the message is `MALFORMED`.
   - `3gpp2` PDUs count as `MALFORMED` unless step 0 shows they occur.
2. The callback window after an emergency call is open (`callbackWindowUntil`): ALLOW from any sender, alphanumeric
   included. The emergency services may text a link.
   - A sender that isn't allowed by rule 5 or 6 shows in the thread with an "Ukjent avsender" ("Unknown sender") line,
     so a phish sent inside the window at least reads as unknown.
3. The engine can't judge now (a local failure: the rules cache unreadable, the engine not ready) while the role is
   ours: **HOLD for the rules** (D15).
4. SMS isn't `managed`: BLOCK. This can't happen with the role held, since we only take it for `managed`. It is a
   guard.
5. `NUMBER`: it normalises (`PhoneNumbers.normalize`, the shared `phone_vectors.json`) and is a contact with
   `sms_in`, **and the number isn't an emergency number** (`Emergency.staticSet(defaultCc)` or the platform's answer;
   QA R2-H1). ALLOW.
   - A "113" or "112" contact keeps its calls, but its texts fall to rule 7 (held), and the PWA never lets them in.
     Anyone can send as "113" (§3).
6. `ALPHANUMERIC`, its text holds a letter, and it equals a name on the parent's list (D17). ALLOW.
7. `NUMBER` that normalises, or `ALPHANUMERIC` (but not rule 6): **HOLD for the parent** (D16). This covers an unknown
   number, a short code that isn't a contact, a contact without "Kan sende melding til {name}", and a sender name not
   on the list.
8. Anything else BLOCKS, with no allow path: `MALFORMED`, an empty sender, a number that doesn't normalise, a group with
   a stranger in it (D12).

**No sender is let in for being an emergency number** (QA H1):
- anyone can send as "112" (§3);
- incoming calls never get that reason either (`Emergency.ALWAYS` is "used only to ALLOW an outgoing call",
  `CallRules.kt:131-135`).

The callback window (rule 2) covers the emergency services texting back. A sender "112" outside the window is held for
the parent like any unknown short number (rule 7). The parent sees "112" and can decide.

**D5. The send rule**, at the engine's `send`, every time, whoever calls it:
1. To an emergency number: ALLOW. Norway's 110, 112 and 113 don't take SMS; texting them is harmless and consistent with
   calls (QA I7).
2. Fail closed, the mode isn't `managed`, or the time rule blocks calls: BLOCK.
3. To a contact with `sms_out`: ALLOW. A picture also needs that contact's `mms` (D11).
4. Anything else: BLOCK.

The override PIN and the pause never open SMS (the existing explicit exception). A time rule's block is lifted by them,
as for calls.

**D6. Our own SQLite store is the system of record. Nothing is written to the Telephony provider in v1.**
- The framework's `SQLiteOpenHelper`, in CE storage. No new Gradle dependency, so no verification-metadata churn.
- Why:
  - Stable ids the seam can hand out. Provider ids are reused after a delete, and another default app may rewrite them.
  - Our states (verdict, delivery, part states) and client ids for idempotency.
  - Nothing for other apps to read. Every `READ_SMS` holder, and Bluetooth MAP (QA I4), reads an empty provider.
  - One store instead of FM's two.
  - Backup can't take it off the phone: `allowBackup="false"`, and `data_extraction_rules.xml` excludes every domain
    (QA I3).
- **No export to Google Messages in v1** (the user, 2026-11-09). When the mode goes back to `unmanaged`, the history stays
  in Vibb One's store, readable on a read-only Meldinger screen (§6.7). An export into the provider for the next SMS
  app is a later, optional step (§9 step 7, not approved).
- A held message's body (D16) lives in its own `held` table, never in `message`, until a release. Blocked messages
  never enter the store; a `blocked` table keeps kind, sender class, sender and time only.

**D7. Persist first, inside the broadcast's budget** (QA M4c, M4d).
- `SMS_DELIVER` and `WAP_PUSH_DELIVER` are foreground broadcasts (T `:1389-1400`). `goAsync` has about 10 s before an
  ANR, and an ANR kills the Home, phone and Device Owner process.
- The receivers run on **their own single-thread executor**, never shared with the outbox, the MMS state machine,
  pruning or the parse service. Their budget is 3 s.
- `SmsDeliverReceiver` only:
  1. classifies (D4 rule 1);
  2. judges (the rules are in memory);
  3. commits the message, the held row or the blocked row, with its event, in one transaction;
  4. calls `finish()`.
  The notification, the event fan-out and the badges run after `finish()`, on the engine thread.
- `MmsPushReceiver` commits the raw m-notification-ind (at most 2 KB, else blocked as `malformed`) to a `pending_push`
  table, then calls `finish()`. The parse (D10) runs afterwards. The row goes once the push is decided. A process death
  mid-parse resumes at the next start.
- If the process dies before the commit, the message is lost (§2.2). Keeping that window tiny is the only defence any
  default app has.

**D8. MMS bytes are parsed, and their pictures decoded, only in an isolated process. Its output is raw pixels, never an
image format** (QA M1).
- A bound service, `android:isolatedProcess="true"`, `exported="false"`, its own process name: no permissions, no
  network, no provider, no files of ours.
- `Application.onCreate` and `KidAppComponentFactory` return at once in it (`Process.isIsolated()`, like the
  `:bootcover` guard at `Application.kt:176-179`; QA L1). No rules, preferences or enforcement code run next to
  untrusted input.
- The main process passes the PDU in through a pipe (`ParcelFileDescriptor`). It gets back:
  - **headers** as strings: from, to and cc, transaction id, message id, date, subject. The main process normalises and
    judges them itself (D4, D10, D12).
  - **text parts** as strings, each at most 5,000 characters; the length is checked before `readString`.
  - **pictures as raw pixels**: width, height and RGBA_8888 in a `SharedMemory` region, at most 1,600 px a side (so at
    most about 10 MB).
- The main process checks `width * height * 4 == size` and builds the bitmap with `Bitmap.copyPixelsFromBuffer` (no
  parser). It then encodes its own JPEG (at most 1,600 px) from those pixels; a thumbnail is that JPEG sampled down
  (step 3b: one file per picture). At most four pictures, and 8 MP between them, are decoded per message.
- **Every image file the main process ever decodes is one it encoded itself.** A compromised isolated process can hand
  over wrong pixels, never a second decoder exploit.
- A parser or codec crash kills only the isolated process. The message is then shown as "Meldingen kunne ikke vises"
  ("The message couldn't be shown") and counted in the report.
- Outgoing pictures (D11) go the same way: the kid's photo is decoded in the isolated process, and its pixels are
  encoded by the main process.

**D9. The PDU codec is vendored from AM**, at de315b76, `src/com/android/messaging/mmslib/pdu/`, without
`PduPersister`. That is **about 7,580 lines**, not the first draft's 5,500 (QA L2).
- Package `com.kidslauncher.mdm.sms.vendor.pdu`, Apache headers kept, each changed file marked "modified", a
  `NOTICE.md` entry with the source and commit.
- **Made pure JVM.** The launcher's JVM tests run without Robolectric and without `isReturnDefaultValues`, so the codec
  must be pure JVM to be tested and fuzzed. That means:
  - `android.util.Log`, `SparseArray`, `TextUtils` and androidx `SimpleArrayMap` swapped for plain Java;
  - `PduPart` loses its `android.net.Uri`;
  - `PduComposer` loses `Context`/`ContentResolver` and takes the part bytes directly;
  - the few `com.android.messaging.util.ContentType` constants copied in.
- Hardened per §8.1, every fix with a JVM test.
- Re-checked against each Android security bulletin's telephony entries when we touch it.

**D10. MMS download is gated before any fetch: the sender and the host** (QA H2, H3).
- On `WAP_PUSH_DELIVER`, the push is committed first (D7). The isolated process then parses it, and the main process
  checks:
  1. **`From` is `<digits>/TYPE=PLMN`.**
     - These block: a missing `From` (the "insert-address-token" hidden sender), an `@` address, any other `TYPE=`,
       and letters.
     - The number is then judged by D4:
       - rule 2 or 5 goes on to the next checks;
       - rule 7 **holds the push unfetched for the parent** (D16): nothing is fetched before the parent allows the
         sender, and then only after every check here passes again;
       - rule 3 holds it for the rules (D15).
  2. **`X-Mms-Content-Location` is on the SIM's MMSC list** (the list, its entries and the parent's allow are
     `docs/design/83-mmsc-hosts.md`, which replaces this check's first text).
     - Its scheme is `http` or `https`, it has no userinfo, and its host is one the entry grammar takes (design 83 §1).
       Anything else is a shape failure and blocks as `kind = mms`, `sender_class = bad_location`.
     - The entry it names is on the list for this SIM's MCC/MNC (`getSimOperator()`): the host alone when the port is
       absent or 80, 443 or 8080, `host:port` for any other port - an exact match, never a suffix. The policy carries
       the list as `call_policy.mmsc_hosts: [{mccmnc, hosts}]`: for each SIM the phone reports, the entries that are
       unasked for it (generated from AOSP's APN list, design 83 §3) and the ones the parent allowed for it.
     - An entry that isn't there - an MCC/MNC without a row included - **fails closed**: the MMS shows as "Bilde kan
       ikke hentes" ("Picture can't be fetched"), and a contact's is reported (`sms_state.mmsc_unlisted`) so the
       parent can allow that address on Device › Calls (design 83 §4). A SIM whose row is empty still gives the
       finding "MMS kan ikke sjekkes på dette SIM-kortet" ("MMS can't be checked on this SIM").
  3. `X-Mms-Message-Size` is at most the carrier's maximum (`SmsManager.getCarrierConfigValues()`'s
     `MMS_CONFIG_MAX_MESSAGE_SIZE`, else 1 MB).
- **Only then** do we call `downloadMultimediaMessage`:
  - into our own non-exported, `grantUriPermissions` provider (QA L3). It hands out one write-only URI per request,
    revoked on the result.
  - Its `openFile` writes through a pipe that stops at that maximum. That is our byte cap.
  - The platform's `MmsHttpClient` still buffers whatever the MMSC sends inside `com.android.phone` (MS
    `MmsHttpClient.java:220-223`). The host check is what keeps that the carrier's own server.
  - The result goes to an explicit, manifest-registered receiver (AM's pattern).
- After the download, the retrieve-conf's `From` must equal the notification's (both normalised, both `/TYPE=PLMN`),
  and D12's group rule must hold. Otherwise it is dropped and counted.
- **The promise, stated honestly:** an MMS is never fetched for a blocked sender, nor for a held one before the
  parent's allow (D16), nor from a host that is neither unasked for the SIM nor allowed by the parent for it (design
  83). That shuts out Enea's "MMS Fingerprint" technique (a crafted push pointing at the attacker's URL).
  - What remains is the same as for SMS (§3): someone who spoofs an allowed number through the real MMSC.
- We don't answer the MMSC for a blocked MMS. Whether a NotifyResp "rejected" is worth sending is [verify].
- NotifyResp and Acknowledge for allowed messages go through `sendMultimediaMessage` (AM `MmsSender.java:89-135`).

**D11. Pictures both ways, by default, per contact** (the user, 2026-11-09: sending and receiving allowed by default,
with a switch per contact).
- **Receiving.** An MMS from a contact whose `mms` is on shows its pictures.
  - With `mms` off, the MMS is still fetched, since the sender is an allowed contact and the host is checked. Its text
    parts show, and the isolated process is told "text only" and returns no pixels.
  - The kid sees "Et bilde ble ikke vist" ("A picture wasn't shown") in the bubble. Long texts and group texts, which
    many phones send as MMS, keep working.
- **Sending.** The picture button shows only when every recipient's `mms` is on.
  - The kid picks from the camera or the system photo picker;
  - the isolated process decodes, the main process re-encodes under the carrier's limit (FM lowered its default to
    200 KB after reports, FM #815);
  - the engine composes the send-req with the vendored `PduComposer`.
- **Re-encoding drops EXIF, GPS position included** (QA I5): a picture the kid sends never says where it was taken.
- Video and audio parts are not played in v1. They show as "Vedlegg som ikke kan vises" ("An attachment that can't be
  shown"); their bytes are not kept.

**D12. Group MMS.** A group (more than one other address in from, to and cc) is accepted only when:
- the sender ALLOWs (D4 rule 5);
- every other address is a contact with a text switch in effect (either direction) or one of the phone's own numbers.

A group with any other address blocks with no allow path (D4 rule 8). The parent sees who sent it and that it was a
group. Pictures in a group follow the sender's `mms`.
- **The own numbers** (QA M7, L10):
  - The parent types them on the Calls page, one per SIM, at most two. They are normalised like a contact and sent as
    `call_policy.own_numbers`.
  - When a SIM knows its own number (`SubscriptionManager.getPhoneNumber(subId)`), the phone uses it only if the parent
    left that field empty.
  - When both exist and differ, the PWA says "Nummeret i Vibb stemmer ikke med SIM-kortet" ("The number in Vibb doesn't
    match the SIM card").
  - With no own number at all, every group is blocked (counted as "group"). There is no safe guess: with
    `To = [kid, stranger]`, "the one unknown address must be us" would let the stranger in.
- The kid may reply in a group only when every participant has `sms_out`. Otherwise the compose bar
  says "Du kan bare svare når alle i samtalen er i telefonboka di" ("You can only reply when everyone in the
  conversation is in your phone book").
- Starting a group: not in v1.
- FM's group bugs (#595 replies to an unrelated recipient; #709 and #744 quick reply reaching only the last participant)
  are why the recipient list of a reply is always the stored conversation's participants, never re-derived from the
  last message.

**D13. Emergency.**
- Sending to an emergency number is always allowed (D5). Receiving from one goes only through the callback window
  (D4).
- Norway has no general SMS-to-112. Whether the emergency text service for the deaf works from an SMS app is [verify].
  Nothing in this design blocks it.
- Whether `DISALLOW_SMS` (mode `off`) also stops the system's AML location SMS during an emergency call is design 09's
  open device check, unchanged.

**D14. Without data, and in airplane mode** (QA M8).
- **SMS** needs only the cellular network: it works without data and with Wi-Fi off.
- **MMS needs the MMS APN.** `MmsService` refuses when MMS data is disabled (MS `:285, 384`).
  - Android has a per-SIM setting, "MMS-meldinger" ("MMS messages"), to send and receive MMS while mobile data is off,
    and carriers may allow MMS data always.
  - Step 0 checks both on the Jelly Star with each carrier. If the toggle works, turning it on is an on-phone runbook
    step (no adb).
  - The phone reports `mms_data_allowed` (`isDataEnabledForApn(TYPE_MMS)`). The PWA says "Bilder i meldinger kommer
    ikke fram uten mobildata" ("Pictures in messages don't arrive without mobile data") while it is false.
- **The waiting state.** An allowed MMS that can't be fetched yet is kept in our DB (never in memory; FM #298 "MMS lost
  when received while no data") and shown as "Bilde venter - trenger mobildata" ("Picture waiting - needs mobile
  data").
  - The retry trigger is a `TelephonyCallback.DataEnabledListener` (we hold `READ_PHONE_STATE`) plus each sync. A
    passive `NET_CAPABILITY_MMS` callback would never fire, because nobody requests that network until we download.
  - After the fifth failed try, the picture shows "Trykk for å prøve igjen" ("Tap to try again") instead of waiting
    forever.
- **Airplane mode:** nothing arrives. The network holds SMS and MMS notifications and delivers them later.

**D15. Hold and retry on a local failure** (QA M4b; the user, 2026-11-09: as proposed).
- **When it applies.** The phone keeps its rules locally (the cached policy, `last_call_rules`, the DE boot copy), so
  "the rules are unknown" is a local failure: an unreadable cache, or the engine not ready yet.
- **What happens.** While the role is ours, the engine **holds** each incoming message instead of dropping it.
  - It is stored with verdict `HELD_RULES`: not shown, not notified, not on the seam, never posted.
  - `DISALLOW_SMS` is **not** set for this state. `EnforcementPlan.restrictSms` becomes "`off`, or `managed` without
    our role, or fail closed while the role isn't ours".
- **The retry.** The engine re-judges every `HELD_RULES` row at each `CallPolicyStore.refresh` and when it becomes
  ready. Each is then judged by D4 as if just received: shown, held for the parent (D16), or blocked.
- **What stays closed.** Sends stay refused (D5 rule 2). The other apps' permission denials and the SMS-app suspension
  still hold.
- **The cap.** At most 200 `HELD_RULES` rows. Past that, the body is not kept and the row is counted as blocked
  (`over_cap`).

**D16. A stranger's message is held for the parent, never shown, and delivered if the parent lets the sender in** (the
user, 2026-11-09).

**What is held.** A message that D4 rule 7 holds goes into the `held` table:
- **An SMS:** its body, kept only on the phone.
- **An MMS:** only the push (the m-notification-ind, at most 2 KB). **Nothing is fetched.**
- Neither is shown, notified, on the seam, or seen by the bridge.

**The window: 24 hours from the phone's receipt.**
- A push reaches the parent at once (below), and most parents look within hours.
- A day-old message from a stranger has mostly lost its point.
- Carriers keep an unfetched MMS for days (`X-Mms-Expiry`; step 0 reads each carrier's value). So an MMS allowed within
  the day can still be fetched.
- The window also bounds what a stranger can park on the phone.

**The caps** (QA R2-M4). A flood never pushes out a real message, and a name flood never takes the numbers' places:
- **Separate pools:** at most 30 held messages from numbers and 20 from sender names.
- **Per sender:** at most 10 from one number, and 3 from one sender name (names are free to invent).
- **Body length:** each SMS body at most 1,600 characters (ten parts; longer is cut, marked "…").
- **Past a cap:** the *new* message's body is not kept (a `blocked` row, `over_cap`, counted), and nothing held is
  evicted.
- **When a pool is full,** the phone reports `hold_full: {numbers, names}` in `sms_state`, and the PWA says so (§7).
  "Slett alle som venter" drops every held row.
- **Said plainly:** a flood of strangers can make the phone stop holding new unknown senders of that kind for up to 24
  hours. Allowed contacts and listed names are never affected.

**At expiry:**
- **The clock rule** (QA R2-L2). Expiry runs at every sync, at the backstop alarm, at boot, and **immediately before
  any release**.
  - A phone that was offline for two days and comes back with an allow in its first policy expires first, then
    releases nothing.
  - The phone's clock is locked while managed (`DISALLOW_CONFIG_DATE_TIME`).
- **What it does:**
  - the body, or the MMS push, is deleted on the phone;
  - the row's state becomes `expired` and is posted;
  - the PWA's row reads "Utløpt - meldingen er slettet" ("Expired - the message was deleted");
  - allowing the sender afterwards still works for later messages, but releases nothing.
- **Deleted means gone** (QA R2-L3). The store runs with `PRAGMA secure_delete = ON` and checkpoints the WAL after each
  expiry or drop pass, so no deleted body lingers in free pages. D18's deletes mean the same.

**Nothing of the body leaves the phone.**
- What is posted (§7) is `{id, at, kind (sms|mms), sender_class, sender, expires_at, state}`.
- The `sender` is cleaned by its class before it leaves (QA R2-M2):
  - **a number:** `+` and digits only, 3-16 characters;
  - **a name:** through the same cleaner as the server's `calendar::clean` (controls, bidi and invisible characters
    out, NFC), 1-11 characters;
  - **`malformed`/`empty`:** an empty sender.
- One shared rule, with vectors in `sms_contract.json`.
- The parent sees who and when, never what.

**The push, at once.**
- On a hold, the receive path requests a sync after `finish()`. `SyncRunner` coalesces the reason `sms_held` to at most
  one request a minute (QA R2-M4), so fifty holds are one sync, not fifty. That posts the held rows at once, or at the
  next contact if the phone is offline; the window keeps running on the phone.
- The server raises the parent event `sms_held` (§7) with its own push budget.

**The allow** (the PWA, from the push or the Calls page's "Meldinger som venter" ("Messages waiting") card). **An allow
grants exactly one thing: this sender's texts reach the kid** (QA R2-M1).
- **A number not in the phone book:** "Opprett kontakt…" ("Create contact…") opens Add Contact with the number filled
  in.
  - Every switch shows, and only "Kan sende melding til {name}" is on.
  - "Kan ringe {name}", "I telefonboken", "På Hjem-skjermen", "Kan få melding fra {name}" and "Bilder i meldinger" are
    off, until the parent turns them on.
  - The parent types the name and saves.
- **A contact without "Kan sende melding til {name}":** "Tillat meldinger fra {contact}" ("Allow texts from {contact}")
  turns on that switch alone (D3).
- **A sender name:** "Tillat avsendernavnet" ("Allow the sender name") adds it to the list (D17), with D17's warning.
- **"Slett nå" ("Delete now")** puts the row's id into the policy's `sms_held_drop`. The phone deletes the body at its
  next policy.
  - "Slett alle som venter" ("Delete everything waiting") puts every held id in at once.
  - `sms_held_drop` keeps ids for 24 hours, at most 100.
- **Never allowed** (QA R2-H1):
  - **An emergency-number sender** (`emergency::is_emergency`, the platform's answer on the phone): its row offers only
    "Slett nå", with "Nødnumre kan ikke sende meldinger hit - alle kan utgi seg for dem" ("Emergency numbers can't text
    here - anyone can pretend to be one"). D4 rule 5 refuses such a contact's texts anyway.
  - **A sender name with no letter** (D17): its row reads "Utgir seg for å være et nummer - kan ikke tillates"
    ("Pretends to be a number - can't be allowed"), with only "Slett nå".
- **The card shows the sender's type** on every row: "Nummer {number}" ("Number {number}") or "Avsendernavn «{name}»"
  ("Sender name «{name}»"). So "+4791234567" as a name never reads like the parent's number.

**How the allow reaches the phone:** a policy change plus the SSE nudge that every Calls-page write already sends
(`command_notify`). Nothing new on the wire beyond the policy keys.

**The release on the phone.** At every accepted policy:
1. **Drops first** (QA R2-L4). Every id in `sms_held_drop` is deleted before anything is released. A parent who deleted
   a row and then created the contact gets the deletion.
2. **Then expiry** (above).
3. **Then the re-judge.** The engine re-judges each remaining `held` row with the new rules. **A row is released only
   when D4 rule 5 or 6 now allows that row's exact sender:**
   - a `NUMBER` whose normalised number equals a contact with `sms_in`, and isn't an emergency number;
   - or an `ALPHANUMERIC` whose text equals a listed name (which holds a letter).

A release from a sender name never releases a number, and the reverse.

**What a release does:**
- An SMS goes into `message` with its original receive time (`at`), a new `seq` and a normal notification. Under a time
  rule it is stored and notified silently.
- An MMS push runs **every D10 check again** (`From`, the MMSC host for the SIM's MCC/MNC, the size) and only then is
  fetched. If the MMSC answers that it is gone, the bubble reads "Bildet er ikke lenger tilgjengelig" ("The picture
  isn't available any more").
- Released rows are posted with state `released`, and the PWA's row reads "Levert" ("Delivered").

**What the kid sees:** nothing while a message is held. A released message shows with the time it really arrived.

**D17. The parent's list of sender names** (the user, 2026-11-09).
- **Storage.** `device_sms_names(device_id, name)`, sent as `call_policy.sms_names: [string]`. The list starts empty;
  design 09's pre-seeded Vipps and ice are not used.
- **A valid name** (QA R2-H1):
  - 1-11 characters after the shared cleaner (an alphanumeric TP-OA holds at most 11 GSM-7 characters);
  - **at least one letter** (any Unicode letter). That refuses "+4791234567", "112", "91 23 45 67" and anything else
    `PhoneNumbers.normalize` would accept.
  - At most 50 names a phone.
  - The server refuses an invalid name in the form (400, in place), and the phone ignores a listed name without a letter.
    Vectors for both are in `sms_contract.json`.
- **Match.** The sender's type is `ALPHANUMERIC` (D4 rule 1) and its text equals a listed name under **one fold rule on
  both sides** (QA R2-L1): NFC, then lowercase by Unicode's default mapping with `Locale.ROOT`, no full case folding.
  - That is Rust's `str::to_lowercase` and Kotlin's `lowercase(Locale.ROOT)`.
  - Vectors: `DNB`/`dnb`, `ÆØÅ`/`æøå`, `ß`, `ΣΩ`.
  - Case is no protection, since a spoofer picks any case. No prefix or contains matching.
  - The server's uniqueness uses the same fold.
- **The honest warning,** shown in the card and on every "Tillat avsendernavnet": "En falsk avsender med samme navn
  kommer også gjennom." ("A fake sender with the same name gets through too.") §3 says why; Norway's sender-ID registry
  stops fakes only of names registered with the operators.
- **On the phone.** A name's messages get a conversation keyed `name:<text>`, with no contact id, `canSend = false` (a
  name can't be texted) and a neutral avatar. Name senders can't send MMS: D10 requires `TYPE=PLMN`.

**D18. Deleting** (the user, 2026-11-09).
- **What the kid can delete.** Long-press a message for "Slett" ("Delete"), or the conversation for "Slett samtale"
  ("Delete conversation"), with one confirm.
  - The rows and their parts go from the store (an unreferenced picture file is deleted), and `MessageDeleted(origin =
    KID_SCREEN)` is emitted.
  - No trash and no undo.
- **A removed contact's messages stay.** The conversation keeps the name it last had (a `display_name` snapshot on the
  conversation) and the number, with `canSend = false`.
- The parent can't delete on the phone: it wasn't asked for.

## 6. Architecture in the launcher

### 6.1 Components (new package `sms/`)

| Component | Kind | Does |
|---|---|---|
| `sms/SmsDeliverReceiver` | receiver, `SMS_DELIVER`, `permission=BROADCAST_SMS` | `goAsync`; on the receive executor: classify, judge, commit, `finish` (D7) |
| `sms/MmsPushReceiver` | receiver, `WAP_PUSH_DELIVER`, `application/vnd.wap.mms-message`, `permission=BROADCAST_WAP_PUSH` | commits the raw push to `pending_push`, `finish` (D7); the engine takes it from there |
| `sms/MmsResultReceiver` | receiver, not exported, explicit | download and send results from the platform |
| `sms/MmsFileProvider` | provider, not exported, `grantUriPermissions` | one write-only URI per request, byte-capped pipe, grant revoked on the result (D10) |
| `sms/RespondViaMessageService` | service, `RESPOND_VIA_MESSAGE`, `permission=SEND_RESPOND_VIA_MESSAGE` | `engine.send` (the gate applies) |
| `sms/ComposeActivity` | `SENDTO`/`VIEW` `sms: smsto: mms: mmsto:` | an allowed number opens its thread with any `sms_body` as a draft (never sent without a tap); otherwise "Du kan ikke sende melding til dette nummeret" ("You can't send a message to this number") |
| `sms/SmsRole` | pure + glue | D1, shaped like `DialerRole.kt` |
| `sms/engine/*` | the engine (§6.2) | store, gate, outbox, MMS state machine, events |
| `sms/engine/SmsSenderType` | pure | the TP-OA type from the raw PDU (D4 rule 1) |
| `sms/parse/MmsParseService` | `isolatedProcess` service, not exported | D8 |
| `sms/vendor/pdu/*` | vendored Java | D9 |
| `ui/messages/MessagesActivity`, `ThreadActivity`, `PictureActivity` | front end 1 | §6.7 |

**Process layout.**
- The engine and the receivers live in the main process, the Device Owner and phone-app process, because the rules
  live there in memory (`CallPolicyStore`) and are refreshed there.
- A separate `:sms` process would have to read the rules from SharedPreferences across processes, which isn't safe for
  the writers.
- The engine's own code is small and touches only platform parsers (`SmsMessage`) and our own TP-OA reader.
- The untrusted MMS formats are what goes out of process (D8). §6.10 covers the rest of the containment.
- A manifest test like `BootCoverManifestTest` pins the parse service's `isolatedProcess="true"`, `exported="false"`
  and process name, so a later edit can't drop them silently (QA L1).

None of these components is direct-boot-aware: the platform holds messages until the first unlock (§2.2).

### 6.2 The engine and its seam (front end 1 now, the bridge later)

The interface `sms/engine/MessageEngine.kt` is pure Kotlin types plus an implementation over SQLite. Every front end
goes through it. Nothing reaches the store or the radio around it.

**Data model**, with stable ids and ordering:

```kotlin
@JvmInline value class ConversationId(val value: String)      // ULID, engine-assigned, never reused
@JvmInline value class MessageId(val value: String)           // ULID, engine-assigned, never reused

/** Who acted: the kid's screen, the notification's reply, a remote front end (the bridge, later), the engine itself
 * (receive, rule changes, retention). Recorded on messages, reads and deletions. */
enum class Origin { KID_SCREEN, NOTIFICATION, REMOTE, ENGINE }

data class Participant(val address: String,                   // normalised E.164 or short number, or "name:<text>"
                       val contactId: Long?)                  // the policy contact; null = not in the phone book

data class Conversation(
    val id: ConversationId,
    val participants: List<Participant>,                      // sorted; 1 = one-to-one, >1 = group
    val displayName: String?,                                 // the name it last had (D18: a removed contact's stays)
    val subId: Int?,                                          // the SIM it lives on; replies go out on it
    val lastSeq: Long, val lastAt: Long,
    val unread: Int,
    val canSend: Boolean,                                     // D5/D12 as of now (rules change)
)

enum class Direction { IN, OUT }
enum class Delivery { RECEIVED, QUEUED, SENDING, SENT, DELIVERED, FAILED, UNCERTAIN }
enum class PartState { PENDING, WAITING_DATA, AVAILABLE, FAILED, TOO_LARGE, NOT_SHOWN }

data class Part(val index: Int, val mime: String,            // image/jpeg (our own encode); text/plain
                val size: Long, val sha256: String?,          // of the bytes the main process encoded
                val width: Int?, val height: Int?, val state: PartState)

data class Message(
    val id: MessageId, val conversationId: ConversationId,
    val seq: Long,                       // the engine's monotonic order, the only ordering key
    val direction: Direction,
    val sender: String?,                 // normalised, or name:<text>; null for OUT
    val unknownSender: Boolean,          // D4 rule 2: let in by the callback window, not a contact
    val at: Long,                        // engine time: received, or queued
    val sentAt: Long?,                   // the SMSC's time for IN (shown only if plausible)
    val body: String?,                   // text (joined multipart SMS, or the MMS text parts)
    val parts: List<Part>,               // empty for SMS
    val delivery: Delivery, val failure: String?,
    val read: Boolean,
    val clientId: String?,               // the front end's idempotency key for OUT
    val origin: Origin,
)

sealed interface EngineEvent { val seq: Long
    data class MessageAdded(override val seq: Long, val message: Message) : EngineEvent
    data class MessageUpdated(override val seq: Long, val id: MessageId,
                              val delivery: Delivery, val parts: List<Part>) : EngineEvent
    data class Read(override val seq: Long, val conversationId: ConversationId, val upToSeq: Long,
                    val origin: Origin) : EngineEvent
    /** Participants' contact ids, names, canSend or subId changed (a policy change, a new own number). */
    data class ConversationChanged(override val seq: Long, val conversationId: ConversationId) : EngineEvent
    data class MessageDeleted(override val seq: Long, val id: MessageId, val origin: Origin) : EngineEvent
}

data class SendCommand(val clientId: String,                 // UUID from the front end
                       val conversationId: ConversationId?,  // or:
                       val to: String?,                      // a raw number (normalised + gated here)
                       val body: String,
                       val pictures: List<PictureRef>,       // only if D11's sending is approved
                       val origin: Origin)

sealed interface SendResult {
    data class Accepted(val message: Message) : SendResult      // the same message for a repeated clientId
    data class Refused(val reason: Refusal) : SendResult
}
enum class Refusal { NOT_ALLOWED, TIME_RULE, SMS_OFF, RULES_UNKNOWN, TOO_LARGE, EMPTY, DUPLICATE_CLIENT_ID }

interface MessageEngine {
    fun conversations(): List<Conversation>
    fun messages(id: ConversationId, beforeSeq: Long?, limit: Int): List<Message>   // backfill, newest first
    fun events(afterSeq: Long): List<EngineEvent>        // replay from a cursor
    val live: kotlinx.coroutines.flow.Flow<EngineEvent>
    fun send(command: SendCommand): SendResult
    fun markRead(id: ConversationId, upToSeq: Long, origin: Origin)
    fun delete(id: MessageId, origin: Origin)            // D18: the kid's screen; later the bridge
    fun deleteConversation(id: ConversationId, origin: Origin)
    fun openPart(messageId: MessageId, index: Int): java.io.InputStream?
    fun openPartByHash(sha256: String): java.io.InputStream?
}

/** The store's contract carries the dedup decision, so the in-memory fake and SQLite behave alike (QA L8). */
sealed interface InsertOutgoing { data class Inserted(val m: Message) : InsertOutgoing
                                  data class Existing(val m: Message) : InsertOutgoing }
```

**How it behaves:**

- **Ordering.** `seq` is the engine's arrival order: one counter for messages and events, from SQLite. Lists sort by
  `seq`, never by the SMSC's time, which can be wrong. FM uses the receive time for the same reason (`SmsReceiver.kt:57`).
- **Events are a replayable log** (`event` table) with the same `seq`.
  - A front end keeps its cursor and asks `events(afterSeq)` after an absence.
  - The log is pruned after 30 days. A cursor older than the oldest kept `seq` gets a full `messages(...)` backfill
    instead.
- **Send is idempotent** on `clientId` (a unique index, decided by the store's `insertOutgoing`).
  1. The engine inserts the message `QUEUED` and returns it.
  2. A repeated `clientId` with the same body returns the same message and sends nothing. With a *different* body it
     is `Refused(DUPLICATE_CLIENT_ID)` (QA M5).
  3. The outbox worker marks `SENDING` (committed) **before** handing it to `SmsManager`.
  4. A message in `SENDING` with no sent-result for 10 minutes becomes `UNCERTAIN` (QA L4). The sent `PendingIntent`
     targets a manifest receiver, so a result can still arrive after a process death and is applied when it does.
     `UNCERTAIN` is never re-sent by itself: this is at most once. The kid or the bridge may send again with a new
     `clientId`.
- **Delivery.** `SENT` once every part is sent, `FAILED` (with the platform's code) when any part fails (QA L4). The
  last part's delivery report moves it to `DELIVERED`. Delivery reports are requested for one-to-one SMS [verify that
  Norwegian operators don't bill them].
- **The gate sits inside `send` and inside the receive paths.** No front end gets a refused message's text into the
  store. `Refused` tells the front end why, in a closed vocabulary its words map to.
- **Rule changes are live.** `canSend` is computed when asked, and a policy change emits `ConversationChanged` for each
  conversation it touches.
  - A contact losing "Kan få melding fra {name}" keeps the history but loses the compose bar.
  - A contact losing "Kan sende melding til {name}": their later messages are held for the
    parent (D16). What is already stored stays, as do a removed contact's messages (D18). An MMS of theirs not fetched
    yet (waiting for data, or the kid's "try again") is never fetched now: the sender is judged again at every fetch
    and after it, and the message goes (step 3b's code QA M2).
- **What a consumer of the events must know (step 3b):** `MessageUpdated` can change a message's `body` too (an MMS's
  text arrives with its fetch) - re-read the message; and an MMS that turns out to be a group is `MessageDeleted` from
  the sender's conversation and `MessageAdded` again, with the same id, in the group's.
- **A raw sender** (an alphanumeric or unknown one let in by the callback window) gets a conversation keyed
  `name:<text>` (or the number), with `contactId = null` and `canSend = false` (QA L11). A listed sender name (D17)
  uses the same key.

**The remote direction, for a later step: the phone never serves** (QA M5). The bridge front end is not designed here;
this only makes sure the seam fits it. Every phone API is phone-initiated, and the SSE stream runs from the server to
the phone, so:

- **Events go up as pushes.** The phone pushes events in `seq` order, in batches, from `remote_acked_seq`: a cursor the
  server acknowledges and the engine stores. Replay after an absence is "push from `remote_acked_seq`". A cursor older
  than the pruned log gets a backfill push of `messages(...)`.
- **Commands come down as nudges.** `send`, `markRead`, `delete` and "upload part X" arrive as an SSE nudge, then a
  phone-initiated fetch of the pending commands. Each command carries its `clientId`; results go back with the next
  push.
- **Parts go up on request, by hash.** The phone uploads a part when the server asks for that hash.

| bridgev2 needs | from the engine |
|---|---|
| Portal (room) id, room state | `ConversationId`; `ConversationChanged` |
| `networkid.MessageID` | `MessageId` (stable, never reused) |
| Backfill (forward and backward) | the backfill push of `messages(id, beforeSeq, limit)`; `events(afterSeq)` from `remote_acked_seq` |
| Live remote events | `MessageAdded`, `MessageUpdated` pushed in `seq` order |
| Redactions | `MessageDeleted` |
| Read receipts both ways | `markRead(origin = REMOTE)` in; `Read(origin = KID_SCREEN)` out |
| Matrix to network send, deduplicated | `send(SendCommand(clientId = the Matrix event's transaction id, origin = REMOTE))` |
| Message send status / checkpoints | `Delivery` + `Refused` reasons |
| Attachment download | a part uploaded by the phone on request, by `sha256` |
| Attachment upload | a later `putPicture` fetched by the phone, returning a hash (D11's path), then `send` with that hash |
| Ghost identities | `Participant.address` + `contactId` (names come from the policy, which the server already has) |

**The bridge's shape, as the user set it (2026-11-09; a later step, not approved, not designed here):**
- **It serves the kid's own Element X account:** he reads and answers his SMS there.
- **The parent turns SMS bridging on per phone in the PWA.**
- **Rooms for SMS-only contacts are provisioned automatically, with no login flow for the kid.** These are the contacts
  without an Element X chat (`message_app != "element"`). Contacts he already chats with in Element X keep that chat.
- **What that needs, noted for the bridge's own design:**
  - the Vibb server holds the bridge's login on the kid's behalf: the bridgev2 user login is the phone, reached through
    the server;
  - **double puppeting** on the kid's Matrix account, so what he sends from the phone shows as his in Element X, and
    read receipts match (QA R2-L6):
    - the later design uses an **appservice registration scoped to exactly the kid's user ID** (`exclusive: false`) for
      double puppeting;
    - it never uses a login, a shared-secret login, or an access token of the kid's stored on the Vibb server. Those
      would give the server a full session as him in every room;
    - portal rooms are encrypted (bridgev2 supports it), so the homeserver doesn't keep SMS bodies in the clear.

The remote front end gets **only what the engine already accepted, and can send only what the gate
allows**. The phone's queue is the durable side. Whether bodies may pass through the server (transport A:
pass-through, no body at rest) is the bridge's own design.

### 6.3 Receiving an SMS

On the receive executor, within D7's budget:
1. `getMessagesFromIntent` for the text, and `SmsSenderType.of` on each raw PDU (`pdus`, `format`) for the type (D4
   rule 1). The address must be the same on every part; otherwise `MALFORMED`. (FM takes the last part's address with a
   TODO.)
2. `subId` comes from the intent.
3. `SmsRules.decideIncoming` (D4).
4. **ALLOW:** one transaction inserts the message (finding or creating the one-to-one conversation by the normalised
   address, storing `subId` on it) and the `MessageAdded` event. Commit, `finish()`, then the notification (§6.8).
5. **HOLD for the rules** (D15): stored with verdict `HELD_RULES`, no event, no notification.
   **HOLD for the parent** (D16): a `held` row, no event, no notification. After `finish()`, a sync is requested so the
   row is posted and the parent pushed at once.
6. **BLOCK:** one row in `blocked`, then nothing visible. The row holds `kind = sms`, a `sender_class` (number / short /
   alphanumeric / malformed / empty), and the sender as received, cut to 20 characters. **No body.**

**Class 0 ("flash") SMS** are treated like any other: gated, stored, shown in the thread, never as a pop-up.

### 6.4 Receiving an MMS

`pushed` (the raw push committed, D7) → `notified` (parsed in the isolated process; sender, host and size allowed, D10;
parts `PENDING`) → `downloading` (`downloadMultimediaMessage` into `MmsFileProvider`; result to `MmsResultReceiver`) or
`waiting_data` (D14) → `parsing` (the isolated service) → `available` | `failed(reason)`.

- Each step commits its state, so a process death resumes at the next start. FM's runtime receiver and static
  de-duplication set lose this (§2.2).
- De-duplication is by `(transactionId, contentLocation)`, a unique index, for the pushed row and the blocked row
  alike. So an MMSC that repeats a push it got no answer to adds no rows (QA L5).
- The downloaded file sits in `noBackupFilesDir/mms/in/`, written through the provider's capped pipe. It is deleted once
  parsed.
- Stored picture files are named by the SHA-256 of the main process's own encoded bytes (D8). A file used by no message
  is deleted.

### 6.5 Sending

- SMS: `divideMessage` + `sendMultipartTextMessage`, on the conversation's `subId`, else the default SMS subscription
  (QA L10). Long text stays multipart SMS, never auto-converted to MMS (FM's option).
- The counter shows "2 SMS" only past 160 GSM-7 or 70 UCS-2 characters.
- Pictures: D11, when every recipient's `mms` is on.

### 6.6 What the store holds

| Table | Holds |
|---|---|
| `conversation` | id, a key (the sorted participant set, or `name:<text>`), display name, subId, created, last seq |
| `participant` | conversation, address, contact id |
| `message` | §6.2 fields; verdict (`SHOWN` / `HELD_RULES`); `client_id` unique; MMS transaction id and content location |
| `held` | D16: id, at, kind, sender type, sender, the SMS body or the MMS push (at most 2 KB), subId, expires at, state (`held` / `released` / `expired` / `dropped`), reported |
| `part` | message, index, mime, size, sha256, size in px, state |
| `pending_push` | the raw m-notification-ind (at most 2 KB), subId, at |
| `event` | seq, type, ids, origin, at |
| `blocked` | at, kind (`sms` / `mms` / `group`), sender class, sender (at most 20 characters), reported |
| `engine_state` | `remote_acked_seq` (later), cumulative counters |

- Messages are kept until the kid deletes them (D18). The launcher's existing CE file encryption covers them at rest.
- **Kept for a later export** (§9 step 7): every message keeps its direction, the other party's address, the SMSC's
  sent time, the receive time, read and delivery state, `subId`, and for MMS the text and our encoded pictures with
  their mime types.

- A `held` row's body is deleted at release (it moves to `message`), at expiry, or at the parent's "Slett nå". The row
  itself goes once the server has its final state.
- **`blocked`** (QA L5):
  - at most 500 rows; past that only a count per sender class is kept;
  - rows go after 30 days, or once the server confirmed them (§7).
- **Counters are cumulative** (received, sent, blocked by class, parse failures), as `call_state`'s are, so a failed
  report loses nothing.

### 6.7 The kid's screen (front end 1), in Dagen's spirit

Design 34's look: night ground, Nunito, `kid_tile`s, the one peach accent, `KidHeader`.

- **Home:** a "Meldinger" tile next to the Phone book (`GridTile.Messages`) while the mode is `managed` and the role is
  ours. Its badge is the unread total.
  - In `off` and `unmanaged`, the tile opens the history **read-only**: no compose bar, no reply, no receiving (the user,
    2026-11-09: the history stays in Vibb One, no export). The tile is gone only while there is no history.
- **Contact avatars:** the badge is missed calls plus unread messages.
- **`MessagesActivity`:**
  - one row per conversation with messages, newest first: avatar (`KidAvatars`), name (800), the last line (600, a
    picture shows as "Bilde"), an unread dot;
  - a "Ny melding" action listing only contacts with `sms_out`, text-only contacts included (D3);
  - long-press a row for "Slett samtale" ("Delete conversation", D18).
- **`ThreadActivity`:**
  - bubbles: incoming on the tile surface, outgoing in the peach tint;
  - a message let in by the callback window carries an "Ukjent avsender" ("Unknown sender") line (D4 rule 2);
  - day separators in the one clock format (P12);
  - pictures as rounded thumbnails, at most 70 % of the width. A tap opens `PictureActivity`: fit to screen, with
    "Lagre" ("Save") and "Del" ("Share"). The user, 2026-11-09: "Barnet skal vel kunne dele og lagre bilder mottatt via
    sms?"
    - **What leaves the engine's store is always the main process's own JPEG** (D8): EXIF-free and re-encoded, never
      the raw MMS part, which the main process never holds.
    - **"Lagre"** inserts a copy through `MediaStore.Images` into `Pictures/Vibb One/` (`IS_PENDING` while writing; no
      storage permission needed for our own inserts). It writes the bytes only, with no sender or time in the file name
      or metadata.
    - **"Del"** sends `ACTION_SEND` with a one-shot read grant on a `FileProvider` URI (not exported) to a copy in
      `cacheDir/share/`, deleted after an hour. The system share sheet is already a kiosk helper. Only apps the parent
      allows can receive: suspended and hidden apps don't resolve, and the allowlist applies as everywhere.
    - Both actions show only where the thread itself is reachable (a time rule's block covers them too). A picture
      stripped by `mms` off (D11) has nothing to save.

  - long-press a message for "Slett" ("Delete", D18);
  - the compose bar's picture button only when every recipient's `mms` is on (D11);

  - "Sendt", "Levert" or "Ikke sendt - trykk for å prøve igjen" ("Not sent - tap to try again", a new `clientId`) under
    the kid's last message.
  - The compose bar shows only when `canSend`.
- **The contact sheet's Message button:** when `messageApp == "sms"` and the mode is `managed`, it opens the thread
  (`resolveMessageButton` gains the internal target).
- **Under a time rule** that blocks calls:
  - the screens are reachable only where the rule says calls are (as the phone book);
  - incoming messages are stored and notified silently;
  - the compose bar says "Du kan ikke sende meldinger nå" ("You can't send messages now").

### 6.8 Notifications, reply, the lock glance

- **The notification:**
  - channel "Meldinger" ("Messages"), `MessagingStyle` with the contact's name and photo, id per conversation;
  - the public version reads "Ny melding" ("New message");
  - a tap opens the thread.
  - The large icon is the main process's own thumbnail (D8).
- **"Svar" ("Reply"), a `RemoteInput` direct reply:**
  - `setAuthenticationRequired(true)` (API 31): a reply from the lock screen needs the unlock first, so whoever finds a
    lost phone can't answer the parent as the kid (QA L6).
  - A not-exported receiver calls `engine.send(origin = NOTIFICATION)` with a `clientId` taken from the reply's intent,
    so a double tap sends once.
  - It is shown only while `canSend`.
- **The lock glance:**
  - a "Meldinger" row like the missed-call row (design 25): the sender's name and count;
  - the text only if the parent switches it on (`call_policy.sms_lock_text`, default off, the same spirit as design
    51's per-app text);
  - a tap opens through `GlanceOpener` as the missed-call row does.
  - Our own notifications stay out of `GlanceReader` (it skips ours). The row reads the engine.
  - As built (step 5, 2026-11-09): one row per conversation with unread messages, among the other rows by time, in
    every mode and under a time rule (the tap only where the rule allows calls); never a held message; an alerting
    post wakes the lock under the parent's existing notice-wake switch (round 7) - only with no Do Not Disturb and only
    when it brings a row onto the lock, as an app's notice. The decisions:
    `docs/notes/launcher/calls.md`, "The lock glance's Meldinger row, step 5".

### 6.9 i18n

- Every new string is in `values/strings.xml` (en) and `values-nb/strings.xml` (nb). The nb words quoted in this note
  are the intended nb strings; the en strings follow the existing pattern.
- The PWA strings are in both catalogues (`server/src/i18n/`).

### 6.10 What keeps working when the SMS code fails

- **Calls, call screening, Home and enforcement must not depend on the SMS code.**
  - Every receiver and service body is wrapped in `runCatching` (catching `Throwable` in the receivers, as the call
    services do). A failure is recorded in `sms_state.last_error` and counted.
  - The receive executor and the engine thread are separate single-thread executors (D7). An exception on either never
    reaches the main looper, and a slow engine never delays a receive.
- The isolated parser can only kill itself (D8). A parser that dies three times on the same message marks it `FAILED`
  and stops retrying it.
- **What is lost, said plainly** (QA M4a):
  - In `off`, in `managed` without the role, and on a launcher too old for `managed`, incoming SMS are **lost** (D1),
    not delayed.
  - If our receiver breaks, incoming SMS are lost too: nobody else gets `SMS_DELIVER`, and others are denied
    `SMS_RECEIVED`.
  - The PWA's words for these states say "går tapt" ("are lost"), never "blokkert" ("blocked"). The parent can switch
    to `unmanaged` from the PWA, which hands the role back.
- Before the first unlock, nothing of ours runs for SMS. The platform holds messages.

## 7. Server

**Migration `0130_sms.sql`** (the next free number today; re-check at build time, since design 76's server work is in
flight, QA I6):
- `device_policy.calls_mode` and `device_policy.sms_mode`, each `TEXT NOT NULL CHECK (... IN ('off','managed','unmanaged'))`,
  backfilled by D2's table; new rows get `calls_mode = 'unmanaged'`, `sms_mode = 'managed'` (D2).
- `device_policy.sms_lock_text INTEGER NOT NULL DEFAULT 0`.
- `device_contacts.sms_in`, `.sms_out`, `.mms`: `INTEGER NOT NULL DEFAULT 1`, backfilled `sms_in = inbound`,
  `sms_out = outbound`, `mms = 1` (D3).
- `device_own_numbers(device_id, slot, number)`: at most two per phone, normalised, deleted with the phone (D12).
- `device_sms_names(device_id, name)`: at most 50 per phone, 1-11 characters, unique per phone after case folding,
  deleted with the phone (D17).
- `device_sms_held(device_id, held_id, at, kind, sender_class, sender, expires_at, state, received_at, dropped_at)`:
  - primary key `(device_id, held_id)`;
  - an index on `(device_id, at)`;
  - purged 30 days after `at` by `retention` (D16).
- `notification_prefs.push_sms_held INTEGER NOT NULL DEFAULT 1` (the push switch, as r13's `push_extra_time`).

**The MMSC host list** (D10): `src/mmsc_hosts.rs`. Since design 83 (`docs/design/83-mmsc-hosts.md`) it reads the
generated `testdata/mmsc_hosts.txt` (`scripts/mmsc-hosts.py`, from AOSP's APN list) and the parent's answers in
`device_mmsc_hosts`; the first text here - a hand table filled from step 0, not parent-editable, a change a server
release - is replaced by that note.

**Policy** (`build_call_policy`, `models::CallPolicy`):
- `calls_mode` and `sms_mode` (always sent), and the old `managed`, `calls_enabled`, `sms_enabled` derived from them
  (D2's wire);
- `sms_lock_text`;
- per contact `sms_in`, `sms_out`, `mms` (D3);
- `sms_names` (D17);
- `own_numbers` (D12);
- `mmsc_hosts` (D10);
- `sms_held_drop`: the held ids the parent deleted in the last 24 hours, at most 100 (D16).
- Every one of these keys, and `contact_requests` if §14 is built, is always sent, also when a channel is `unmanaged`
  and for every contact (QA R2-I1). An absent per-contact key decodes as `false` on the phone; a `null` would fail the
  whole decode, as intended.
- Pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
- Every write on the Calls page already nudges the phone over SSE (`command_notify`), so an allow reaches it within
  seconds while it is online.

**Status report:**
- capability `sms_engine_v1`;
- `sms_state`, held by the server through its known keys like `call_state`:
  - `role_held`, `mode_applied`;
  - cumulative `received`, `sent`, `blocked` (by class), `held`, `released`, `expired`, `parse_failures`;
  - `mms_waiting` (count), `mms_data_allowed`, `mmsc_known` (this SIM's MCC/MNC is on the list), `last_error` (300
    characters);
  - `own_numbers` as the SIMs report them (`null` when a SIM doesn't know);
  - `hold_full: {numbers, names}`: whether each hold pool is full (D16).
- **No content.**

**Held and blocked senders:** `POST /api/devices/sms-held`, behind `require_device_token`, body limit 16 KiB.
- **The body:** `{"rows": [{"id", "at", "kind", "sender_class", "sender", "expires_at", "state"}]}`, at most 50 rows.
  - `id` is the phone's (16-64 lowercase hex);
  - `state` is one of `held`, `released`, `expired`, `dropped`, `blocked` (D4 rule 8 and over-cap rows, which have no
    allow path).
- **The answer:** `{"accepted": n}`, or `{"accepted": 0, "capped": true}` (below). The phone marks each row reported on
  a 2xx and posts it again only when its state changes.
- **The intake checks** (QA R2-M2, R2-M3). A row that fails one is refused, and the post answers 400 naming it:
  - **`sender` by its class:**
    - `number`/`short`: `+` and digits, 3-16 characters;
    - `alphanumeric`: `calendar::clean`'s grammar (controls, bidi and invisible characters out, NFC), 1-11 characters,
      unchanged by cleaning;
    - `malformed`/`empty`/`bad_location`: empty.
  - **Times:** `at` no later than the server's now plus 5 minutes; `expires_at - at` between 0 and 24 hours plus a
    minute.
  - **States move only forward:** `held` → `released` | `expired` | `dropped`. `released`, `expired`, `dropped` and
    `blocked` are final. Any other change is ignored and counted (`sms_state`'s `ignored_transitions`), never shown,
    never raised again. A re-post never changes the sender or time.
  - **The bound:** at most 500 `device_sms_held` rows per phone in 30 days. Past it, new ids answer `{"accepted": 0,
    "capped": true}`, and one `record_phone_event` line (a new `PhoneLine::SmsHeldCapped`, inside the existing
    `PHONE_LINES_AN_HOUR` budget) tells the parent's security log. State updates to rows already stored still apply.

**The parent event and the push** (as r13 §2.6):
- `Kind::SmsHeld`, wire `sms_held`, raised on the first sight of a `held` row (key: the phone's `id`).
- Urgency normal; TTL until `expires_at`.
- Switch `push_sms_held` ("A message is waiting for you to allow it"), on by default.
- **The rate** (QA R2-M3) lives in `parent_events::raise` as a new per-kind option, `PushBudget { per_hour: 3,
  per_key_hour: 1 }`, beside `EVENTS_AN_HOUR` (20 events of a kind) and `URGENT_PUSHES_AN_HOUR` (3):
  - the first three `sms_held` events of a phone's hour push, at most one per sender (the sender is the budget key);
  - the rest go to the inbox only;
  - one digest push at the hour's end: "{phone}: 7 meldinger venter" / "{phone}: 7 messages are waiting".
  - The same option serves §14's `contact_request` later. `sms::tests` pins it.
- **The words** quote and label the sender, so a stranger's text can't pose as Vibb's own words (QA R2-M2). In the
  parent's language:
  - a number: "{phone}: en melding fra et ukjent nummer, {number}, venter" / "{phone}: a message from an unknown number,
    {number}, is waiting";
  - a name: "{phone}: en melding fra avsendernavnet «{name}» venter" / "{phone}: a message from the sender name «{name}»
    is waiting";
  - summary "{at} · Tillat innen {left}" / "{at} · Allow within {left}".
  - Push titles are plain text the OS shows as is, so the intake's cleaning is the defence there. The page's escaping
    covers the card.
- **Target:** `/devices/{id}/calls#held-{id}`.
- A `released`, `expired` or `dropped` state marks the event read.

**The PWA, Device › Calls & Contacts:**
- **Two rows replace the old three switches** ("Manage calls and SMS", "Calls", "SMS"): "Samtaler" and "SMS", each a
  segmented "Av" / "Styrt av Vibb" / "Ikke styrt" with D2's sub-lines. The two rows are one form, so a save posts both,
  and it returns to `#settings` as today.
  - The combinations an older launcher can't express are disabled with "Oppdater Vibb One først" (D2).
  - Under SMS "Styrt av Vibb": "Slå av chatfunksjoner i Meldinger først" ("Turn off chat features in Messages first",
    §8.5).
- **Each contact's form** gains D3's "Meldinger" group with its three switches, under the call switches.
- **"Telefonens nummer"** ("The phone's number"): one field per SIM (D12).
- **A "Vis meldingstekst på låseskjermen"** ("Show message text on the lock screen") switch.
- **A "Meldinger som venter" ("Messages waiting") card** (D16), at the top of the page while it has rows:
  - one row per held message, newest first, each anchored `#held-{id}`;
  - the sender with its type, "Nummer {number}" or "Avsendernavn «{name}»" (D16), the kind ("SMS" / "Bilde"), the
    time, and "venter til {time}" ("waits until {time}");
  - **never content**;
  - the actions: "Opprett kontakt…" ("Create contact…"), "Tillat meldinger fra {contact}" ("Allow texts from
    {contact}") or "Tillat avsendernavnet" ("Allow the sender name"), and "Slett nå" ("Delete now");
    - none but "Slett nå" for an emergency number or a name without a letter, with D16's reason;
    - prefill values go through the form's escaping and the query string's encoding;
  - "Slett alle som venter" ("Delete everything waiting") at the card's foot;
  - **a row past its `expires_at` by the server's clock** reads "Utløpt" with no allow actions, even before the phone
    posts `expired` (QA R2-L2);
  - a row whose state changed reads "Levert" ("Delivered"), "Utløpt - meldingen er slettet" ("Expired - the message was
    deleted") or "Slettet" ("Deleted"), and goes after 7 days.
- **A "Blokkerte meldinger" ("Blocked messages") list** under it, folded: the `blocked` rows of the last 30 days, each
  with sender, kind and time.
- **An "Avsendernavn som slipper gjennom" ("Sender names let through") card** (D17):
  - the names, "Legg til navn…" ("Add name…"), and removing a name;
  - the warning "En falsk avsender med samme navn kommer også gjennom." ("A fake sender with the same name gets through
    too.")
- **A device-page summary row**, "2 meldinger venter · 3 blokkert denne uken" ("2 messages waiting · 3 blocked this
  week").
- **The calls report's findings gain:**
  - "Ventelisten er full - nye ukjente avsendere blir ikke holdt før {time}" ("The waiting list is full - new unknown
    senders aren't held until {time}"), from `hold_full`, with "Slett alle som venter" (QA R2-M4).

  - "SMS-appen er ikke Vibb One - meldinger går tapt" ("Vibb One isn't the SMS app - messages are lost"): role not held
    while `managed`. Design 09 B1's pending rule applies.
  - "Meldinger kunne ikke vises" ("Messages couldn't be shown"): parse failures.
  - "MMS kan ikke sjekkes på dette SIM-kortet" ("MMS can't be checked on this SIM"): `mmsc_known` false.
  - "Bilder i meldinger kommer ikke fram uten mobildata" ("Pictures in messages don't arrive without mobile data"):
    `mms_data_allowed` false.
  - "Nummeret i Vibb stemmer ikke med SIM-kortet" ("The number in Vibb doesn't match the SIM card").
- **Every form follows the Calls page's rules** (design 31 §5.1): it returns to its own anchor, a refusal renders in
  place, a gone row lands on a notice, and a save never jumps to the top.

**Contract file:** `server/testdata/sms_contract.json`, byte-identical in `launcher/app/src/test/resources/`:
- `policy` rows: each mode, the old-launcher view, a contact's `sms_in`/`sms_out`/`mms`, `sms_names`, `own_numbers`,
  `mmsc_hosts`, `sms_held_drop`;
- `report`: the exact `sms_state` and the capability;
- `held`: the exact POST body with a row in each state, and the server's answers.

Both sides run every row and compare the copies. It lands in both directories in one commit, and goes into
`launcher.yml`'s and `server-ci.yml`'s path lists and the root `CLAUDE.md`'s contract list.

The D4/D5 vectors stay the launcher's alone (QA L8: the phone judges every message, the server none), in
`launcher/app/src/test/resources/sms_rules.json`.

## 8. Hardening

### 8.1 The vendored codec

Each fix below comes with a JVM test of a crafted PDU.
- `parseUnsignedInt` takes at most 5 octets, and the result must fit 32 bits as a non-negative `Int`.
- Every length is checked against what remains before any allocation (GM `:940`).
- Bounded index for well-known content types (GM `:1623-1628`).
- A null `multipart/alternative` child ends the parse.
- Recursion depth of at most 1, at most 20 parts, at most 300 header fields.
- The whole input at most the carrier's maximum, or 1 MB, enforced before the parse. The parse returns `null` on any
  `Throwable`.
- Each 2026 bulletin's PduParser fix (§2.3) is checked against our copy at vendoring.
- **A JVM fuzz test** (a fixed seed and 20,000 random mutations of captured PDUs, in the normal test run) asserts "returns
  or `null`, never throws".

### 8.2 The isolated process

D8:
- no permissions, not exported, `Application` and the component factory inert in it;
- input by pipe;
- output: header strings, text strings (length-checked before reading), pixels in `SharedMemory`; at most 20 parts,
  1,600 px a side, 5,000 characters a text; everything checked on receipt;
- `ImageDecoder` with `setTargetSize` from the header's dimensions, refusing more than 40 MP or more than 8,192 px a
  side before decoding.
- HEIF and AVIF may need `MediaCodec`, which an isolated process may not reach [verify]. If they fail, they show as
  "Bilde kan ikke vises" ("Picture can't be shown").

### 8.3 Other apps

The policy denials in §2.5. The SMS packages stay suspended in `managed` (`restrictSms` widens to "mode isn't `unmanaged`"
for the suspension, while `DISALLOW_SMS` itself stays off in `managed`). The new permissions are self-granted and
policy-fixed, so a role hand-back never kills us (design 09 A1/B2; step 0 checks this while the dialer role is ours,
QA L7):
- `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, added to the manifest;
- plus `READ_PHONE_NUMBERS` for D12.

### 8.4 No exported surface beyond the role's four components

- Each of the four is guarded by its system permission.
- The result receivers, the reply receiver, `MmsFileProvider` and the parse service are not exported.

FM's exported `DeleteSmsReceiver` (#834) is the lesson.

### 8.5 RCS: making sure contacts' messages arrive as SMS (QA M6)

- While the kid's number is registered for RCS ("chat features"), a contact on Android sends over RCS to Google's
  servers, never as SMS. With Google Messages hidden, nothing on the phone receives that, and whether the sender's
  phone falls back to SMS is the sender's setting.
- Taking the SMS role doesn't deregister RCS. The design 09 runbook ("turn RCS chats off before enrolling") needs
  Messages visible, and on a phone in `off` it is hidden.
- **The path:**
  1. The PWA's line under "Styrt av Vibb": "Slå av chatfunksjoner i Meldinger først" ("Turn off chat features in
     Messages first"), to do while the mode is still `unmanaged` (Messages is visible then).
  2. For a phone already locked down: Google's web form to deregister a number from RCS, linked from the line's ⓘ.
  3. If neither works, a later launcher step can show Google Messages for one parent-unlocked session.
- Step 0 measures what the kid misses: with RCS on, switch to `managed` and text from an Android phone with RCS; time
  until it arrives as SMS, if ever.

### 8.6 Residual risks, named (QA M2, M3)

These are paths the engine doesn't see. Step 0 measures each; the design then picks a rule, or the user accepts the
risk.

- **App-specific SMS tokens.**
  - Any app can call `SmsManager.createAppSpecificSmsToken` without a permission (T `AppSmsManager.java:84-101`;
    `SmsController.java:922-929`).
  - An incoming SMS containing the token goes to that app with its full text and is dropped before `SMS_DELIVER` (T
    `InboundSmsHandler.java:1571-1576`). Nonce tokens don't expire.
  - So an app the parent allowed could be a receive-anything channel for strangers, invisible to the engine.
  - **There is no Device Owner switch for it.** It is an accepted residual, limited to apps the parent allows.
- **Google Play services: its SMS access is taken away in "Styrt av Vibb"** (the user, 2026-11-09).
  - **Why.** GMS is a system app holding `RECEIVE_SMS`, so it gets `SMS_RECEIVED` for every message, held and blocked
    ones included (T `:1841-1852`). Its SMS User Consent API shows the full text of the next SMS from a sender not in
    the contacts, in a GMS sheet, when any app asks. Its SMS Retriever API hands a message to the app whose hash is in
    the text.
  - **The rule.** In `managed` mode, `AppEnforcer` sets GMS's `RECEIVE_SMS` (and `READ_SMS`, `RECEIVE_MMS`,
    `RECEIVE_WAP_PUSH` if it holds them) to `PERMISSION_GRANT_STATE_DENIED`, and back to DEFAULT when the mode leaves
    `managed`.
  - **Step 0 first.** List every holder of `RECEIVE_SMS`, `RECEIVE_MMS` and `RECEIVE_WAP_PUSH` on the Jelly Star
    (`dumpsys package`, `appops`), deny GMS's, and check what breaks: phone-number verification in Google apps, Find My
    Device, Play's account checks.
  - **If something essential breaks**, the fallback comes back to the user before step 1 is built. Until then this rule
    stands.
- **Bluetooth MAP.**
  - The phone's MAP server (`com.android.bluetooth`, a system app with `SEND_SMS`) may send a message pushed from a
    paired car or PC once "message access" is allowed, around D5 [verify].
  - Step 0 pairs a MAP client (a Linux PC with `bluetoothctl`/obexd) and pushes an SMS to a non-contact.
  - If it is sent: in `managed` mode, deny `SEND_SMS` to the Bluetooth package if a Device Owner may and nothing else
    breaks. Otherwise it is named here as a residual and put to the user.
  - Reading over MAP is no leak (D6: the provider is empty).
- **A compromised parser forges headers** (code QA of step 3b, N1). D8 trusts the isolated process's `From`, `To`,
  `Cc` and `Content-Location` strings. A decoder or codec exploit in it can name an allowed `From` for its own push and
  pass both From checks, and alter the pixels it hands back. It can't reach the main process's memory, our files, the
  network or another message: every call has an isolated process of its own (`bindIsolatedService` with a fresh
  instance), pixels are read with `pread`, never mapped, and one message's pictures are bounded. Accepted as D8's bound.
- **Redirects after the host check** (N2). The platform's `MmsHttpClient` follows same-protocol redirects, so D10's host
  check covers the first hop only. The MMSC is the carrier's; step 0 checks whether the MMS APN reaches outside hosts at
  all.

## 9. Steps and sizes

0. **Device checks first (S, no code).** On the Jelly Star, with a Telia, a Telenor and an Ice SIM; the emulator where
   it says so:
   - **Senders:** an alphanumeric sender reading "+4791234567" and "112" (the emulator's `sms pdu`, TON 101); whether
     `3gpp2` PDUs ever occur.
   - **MMS:**
     - each carrier's MMSC host as seen in a real m-notification-ind, and whether the MMS APN reaches an outside host;
     - design 83's gate, before any entry is unasked (`scripts/mmsc-hosts.py`'s `UNASKED` stays `"none"` until both
       are recorded): (a) with Wi-Fi on, a picture from a single-label or private host is fetched (it resolved on the
       MMS network); (b) with the debug launcher, a notification naming a private address on the Wi-Fi network, where
       `scripts/fake-mmsc.py` listens, brings no request there, with mobile data on and off. If (b) fails, a parent's
       allow of such a name has the same weakness: that comes back to the owner before MMS ships;
     - whether each carrier's m-notification-ind carries `From`, and its `X-Mms-Expiry` (D16's window against it);
     - the carrier's MMS maximum (`getCarrierConfigValues`);
     - a real MMS round trip with a picture.
     - `pread` of the isolated parse's pixel region on the Jelly Star's kernel (ashmem; step 3b's verification V1).
   - **Data:** the "MMS-meldinger" toggle with mobile data off.
   - **Other receivers:** every holder of `RECEIVE_SMS`, `RECEIVE_MMS` and `RECEIVE_WAP_PUSH`; denying GMS's and what
     breaks (§8.6); a Bluetooth MAP push to a non-contact.
   - **Losses:** an SMS sent while `DISALLOW_SMS` is set never arrives after it is lifted; RCS on, switched to
     `managed`, the time until an RCS sender falls back.
   - **Roles:** `setDefaultSmsApplication`; denying the SMS permissions to a third-party app; handing the SMS role back
     while the dialer role is ours, the process surviving (`adb shell pidof`); the own number on each SIM.
   - These decide D10's host list, D12's fallback, D14's runbook line and §8.6's rules. If denying GMS breaks something
     essential, that comes back to the user here.
1. **The engine and SMS (L), launcher:**
   - the per-channel modes on the phone (D2): `calls_mode`/`sms_mode` read with the reject-if-missing rule, the calls
     path behaving as unmanaged when only SMS is managed, the DE boot copy's new version;
   - the role (D1), the receivers on their own executor (D7), `SmsSenderType`, `SmsRules` with D3's independent switches,
     the emergency-number rule and D17's names, the store (`secure_delete`) with its dedup contract, outbox and delivery,
     the event log;
   - holding for the rules (D15) and for the parent with its two pools, release, drops-first and expiry (D16);
   - notifications with reply, the permission denials (GMS's included);
   - `sms_state`, the `sms-held` post;
   - the contract file with the server's policy keys and intake (S on the server, same commit).
2. **The kid's screens (M):** Home tile, `MessagesActivity`, `ThreadActivity`, deleting (D18), saving and sharing a
   picture (§6.7), the read-only history in `off` and `unmanaged`, the contact sheet's button, the time-rule behaviour,
   en + nb.
3. **MMS, in two reviewable parts** (QA L9):
   - **3a (M), JVM only:** the vendored codec made pure JVM, its hardening and the fuzz test (D9, §8.1).
   - **3b (L):** the isolated service with pixel output (D8), `MmsFileProvider`, the gated download with the host check
     (D10), the push-first state machine, held pushes fetched after an allow (D16), pictures in the thread and sending
     them with the per-contact switch (D11), D14's waiting state and retry, the group rule (D12).
4. **The PWA and the server (L):**
   - D2's per-channel rows replacing the three switches, the migration of D2's table, the derived old keys, the
     capability gate;
   - D3's "Meldinger" group with the emergency-number rule;
   - the `sms-held` intake's checks, bound and state rule, and the `PushBudget` option in `parent_events::raise`;
   - the own-number fields, the lock-text switch, the MMSC host table;
   - "Meldinger som venter" with its allow actions and "Slett nå", the blocked list, and the sender-name card;
   - the `sms_held` event with its push, switch and rate;
   - the findings, the device-page row, retention.
5. **The glance row (S).**
6. **The kid asks for a contact (M) - optional, not approved** (§14).
7. **Export to Google Messages when the mode goes back to `unmanaged` (M) - later, optional, not approved** (the user,
   2026-11-09: "det høres jo ut som kjekt å ha hvis mulig, men ikke urgent").
   - **How it would work.** When the mode leaves `managed` for `unmanaged`, and before the role is handed back (only the
     default SMS app may write the provider), the engine writes its history into the Telephony provider:
     - SMS rows into `content://sms` (`address`, `body`, `date`, `date_sent`, `type` inbox/sent, `read`, `status`,
       `sub_id`), with threads from `Threads.getOrCreateThreadId`;
     - MMS rows through `content://mms` with their `part` and `addr` rows. That is the piece of `PduPersister` D9 left
       out, or a small writer of our own.
     - The next default SMS app (Google Messages) then shows the history.
   - **What it needs:** the role still held during the export (D1's hand-back waits for it); a resumable, idempotent
     export (a marker per message), since thousands of rows may take seconds; and a step-0-style check that Google
     Messages shows rows written this way.
   - **What it costs:**
     - the history leaves our store for a table every `READ_SMS` holder and Bluetooth MAP can read (D6's "Why");
     - a provider writer to keep working across Android versions;
     - the deleted messages stay deleted (D18).
   - **What v1 must keep for it:** §6.6's "Kept for a later export". v1 already stores all of it.
8. **Bridge front end (later, not approved; L+, its own design):** the push/nudge mapping of §6.2 on the phone, the
   server's pass-through and its login on the kid's behalf, double puppeting on the kid's account, the PWA switch, the
   mautrix bridgev2 connector.

Order: 1 → 2 → 3a → 3b; 4 can follow 1 at any point. Steps 1-4 ship together as one release for the user's phones, since
`managed` mode without the screens, MMS or the parent's allow would lose messages from the kid's point of view.

## 10. Tests (lean) and the emulator

### 10.1 Launcher (JVM)

- `SmsRulesTest` runs `sms_rules.json` (launcher-only). It includes:
  - a sender "112" outside the window is held for the parent, never allowed (QA H1);
  - an alphanumeric "+4791234567" and an alphanumeric "112" are held as names, never matched to a number; a numeric
    "+4791234567" from a contact with `sms_in` ALLOWs (QA H2);

  - D3's switches, independent of the calls: `sms_in` without `inbound` ALLOWs, `inbound` without `sms_in` holds;
    `sms_out` without `outbound` sends, `outbound` without `sms_out` refuses; `mms` off strips the
    pictures;
  - D17: an alphanumeric "DNB" with "dnb" listed ALLOWs; a numeric sender never matches a name;
  - HELD_RULES while the rules can't be read and the role is ours.
- `SmsSenderTypeTest`: captured and crafted PDUs (TON 101, international, national, malformed lengths).
- `SmsEngineTest` (the engine's logic over a `MessageStore` interface with an in-memory fake that implements the same
  `insertOutgoing` contract; the launcher has no Robolectric and this design adds none):
  - send idempotency (the same `clientId` twice, one send; a different body refused);
  - `SENDING` to `UNCERTAIN` after 10 minutes, and a late sent-result applied;
  - event replay from a cursor; `ConversationChanged` on a rule change; `MessageDeleted` on a delete;
  - D15: a `HELD_RULES` message judged when the rules return;
  - **D16:**
    - a held message released only for its exact sender (a contact added for another number releases nothing; a
      listed name releases no number);
    - expiry at 24 hours deletes the body, and runs before a release (an offline phone's allow releases nothing past
      the window);
    - the number and name pools and the per-sender caps (3 for a name) never evict; `hold_full` reported;
    - "Slett nå" and "Slett alle som venter" through `sms_held_drop`, applied before any release in the same policy;
    - an emergency-number contact's text held, never released;
    - a held MMS push re-checked by D10 before its fetch;
    - fifty holds request one sync (the one-a-minute coalescing).
- `PduHardeningTest` + `PduFuzzTest` (§8.1).
- `MmsGateTest`:
  - notification `From` vs retrieve `From`; `/TYPE=PLMN` required; a missing `From`;
  - the content-location host, scheme, userinfo and port rules; an unknown MCC/MNC failing closed;
  - the group rule with and without own numbers.
- `SmsContractTest` compares the copies and encodes `report` and `held` exactly. It runs:
  - the sender-cleaning rows;
  - the name rows (a letter required; the fold rule's `DNB`, `ÆØÅ`, `ß`, `ΣΩ`);
  - the old-launcher view of "Calls not managed, SMS managed".
- `PolicyResponseCompatTest` gains the keys.

### 10.2 Server

- `sms::tests` covers:
  - the policy rows (with the contact switches, `sms_names`, `own_numbers`, `mmsc_hosts`, `sms_held_drop`);
  - the intake (known keys, caps, a state update never changing sender or time);
  - the sender checked by class, a stranger's `"<&` and a bidi override refused;
  - the time checks;
  - the forward-only states;
  - the 500-row bound with its `PhoneLine::SmsHeldCapped` line;
  - the event's dedup, the `PushBudget` (3 an hour, one per sender, the hourly digest) and the quoted, labelled words;
  - D17's fold and letter rule on the shared vectors.
- `tests::calls` covers:
  - SMS "Styrt av Vibb", and calls "Ikke styrt" with SMS restricted, both refused for a phone without
    `sms_engine_v1`;
  - D2's two rows, every migration row of D2's table, the old-launcher combinations disabled, and the derived old keys;
  - D3's "Meldinger" group and the row summaries;
  - the own-number fields; the sender-name card with its limits;
  - "Meldinger som venter" with each action returning to its anchor ("Opprett kontakt…" prefilled, "Tillat
    avsendernavnet", "Slett nå", "Slett alle som venter");
  - each path's defaults: a held number's prefill with only "Kan sende melding til" on; §14's prefill and its unticked
    Matrix ID; an emergency number and a letterless name offering only "Slett nå"; an expired row by the server's clock
    with no allow actions; a prefill of `"<&` escaped;
  - the "waiting list full" finding;
  - the new findings' words ("går tapt" where messages are lost).
- The `policy_json_keys_snapshot` update.

### 10.3 Emulator, and what it can't do

- **SMS in:**
  - `adb emu sms send <number> <text>` from a contact, an unknown number and a short code. Expect the thread, a held row
    with a push, and a held row.
  - Then "Opprett kontakt…" in the PWA for the unknown number: its held message appears in the thread within seconds,
    with its first receive time.
- **Alphanumeric senders:** the console's `sms pdu <hex>` with a TP-OA of type alphanumeric (TON 101), one reading "DNB",
  one reading a contact's "+47…" number, one reading "112".
  - All three are held as names. Allowing "DNB" releases only the first.
  - [verify] that the console accepts it. The captured PDUs go into `docs/testing/emulator.md`.
- **SMS out:** the emulator's own number or a second emulator, including one scripted double-send with the same
  `clientId` (one SMS out).
- **MMS notification:** a WAP-push PDU injected with `sms pdu` (port 2948) reaches `MmsPushReceiver`. That makes these
  testable:
  - the push committed before the parse;
  - the "From missing" and non-PLMN blocks;
  - a content-location to a foreign host blocked as `bad_location` (`adb logcat`: no download request);
  - a stranger's push held unfetched (no download request until the allow).
- **MMS download:** needs an MMSC on the emulator's MMS APN [verify]. If that fails, the download and picture path is
  covered by JVM tests on captured retrieve-confs, plus step 0's real-SIM check.
- **Crash containment:** a crafted retrieve-conf fed to the parse service in a debug build kills only the isolated
  process (`adb shell ps`).

## 11. The user's answers (2026-11-09), and where they meet design 09

**The answers, and where each went:**
1. **Blocked messages.** Who and when with a push at once, and an allow that delivers the held message: D16, §7.
2. **Sender names.** A parent-edited list with the honest warning: D17.
3. **The phone book decides.** Text switches beside the call switches: D3. Revision 3: independent of the call
   switches (the user: "om det koster lite så sett på at de kan være uavhengige").
4. **Pictures.** Allowed both ways by default, with a switch per contact: D3, D11. Revision 3: the kid may save and share
   a received picture, our own re-encoded JPEG only (§6.7).
5. **Deleting.** Yes; a removed contact's messages stay: D18. The export to Google Messages is a later, optional step
   (§9 step 7), and v1 keeps the history readable in `unmanaged` (D6, §6.7).
6. **Hold and retry on a local failure:** D15.
7. **The setup steps.** RCS off, "MMS-meldinger", the phone's own number: §8.5, D14, D12, as runbook lines.
8. **GMS's SMS access taken away in "Styrt av Vibb",** after step 0's check: §8.6.
9. **The bridge serves the kid's own Element X,** switched on by the parent, with automatic rooms: §6.2, §9 step 8.
10. **Revision 3: Calls and SMS each get their own state** (the user: "Kanskje man burde kunne slå på individuelt
    hvorvidt calls og sms er managed av vibb eller ikke"): D2's three-way choice per channel, its migration and the
    old-launcher rule.

**Design 09's "Decisions after QA review" (2026-10-05), never confirmed then, now settled:**
- **Separate per-contact SMS flags:** yes, as design 09 proposed, and independent of the call switches (D3).

- **The blocked log's text if the parent turns it on:** no. Who and when only, and a held message reaches only the
  kid, after an allow (D16).
- **A sender-name allowlist pre-seeded with Vipps and ice:** a list, yes; pre-seeded, no. It starts empty (D17).

**Also changed from design 09 part A:**
- Messages go into our own store, not the Telephony provider (D6).
- Emergency numbers no longer let an incoming SMS in (D4, QA H1).

## 12. Risks

- **Interop.** We own the carrier quirks FM's users found. AM's transport pattern and step 0's real-SIM checks are the
  mitigation.
- **A new codec to keep patched** (D9): every Android bulletin's telephony entries are checked when the code is touched.
- **The residuals in §8.6:** app tokens (accepted), GMS (denied after step 0), Bluetooth MAP (step 0 decides).
- **Number spoofing through the real network** (§3): the same residual as for calls; only the operators' filters stand
  against it.
- **An allow is a decision about a sender, not a message.** A parent who creates a contact for a number a scammer
  spoofed lets that scammer's held messages through, and later ones. The push and the card show only who and when, so
  the parent decides on the sender alone, by design.

## 13. Open for the user

Nothing is open now. What step 0 may bring back:
- **If taking SMS access away from Google Play services breaks something essential** (§8.6), what to do instead.
- **If a Bluetooth car kit or PC can send SMS around the rules and a Device Owner can't stop it** (§8.6), whether to
  accept that.




## 14. Optional, not approved: the kid asks for a new contact

The user, 2026-11-09: "en plus-knapp hvor de kan legge til nr og navn (og eventuelt matrix chat ID) og sende forespørsel
til foreldre om å legge denne personen til? Så må foreldre godkjenne den kontakten i PWA". It follows design r13's time
request (§2.5-§2.6 there), and shares D16's parent-approval path: approving opens the same prefilled Add Contact.

**On the phone:**
- A **+** in the phone book's header ("Be om ny kontakt" / "Ask for a new contact") opens a small form:
  - name, at most 40 characters;
  - number, normalised on the phone with `PhoneNumbers` and refused if it doesn't normalise;
  - an optional Matrix ID, checked with `isMatrixId`.
- "Send til mamma og pappa" ("Send to Mum and Dad") posts it and shows "Venter på svar" ("Waiting for an answer").
  The request is kept locally for 7 days and is reachable whenever the phone book is.
- When the contact appears in the policy (its number), or the server answers declined, the row goes. A decline shows
  "Ikke lagt til" ("Not added") once.

**The API** (both directories in one commit):
- `POST /api/devices/contact-request`, behind `require_device_token`, body limit 1 KiB:
  `{"id": "<16-64 hex>", "name", "number", "matrix_id"?, "asked_at_ms"}`.
- **The intake's checks** (QA R2-L5):
  - the r13 ones: "3 a day" counted in the phone's own day (`phone_zone`, as `EXTRA_TIME_REQUESTS_A_DAY`), in one
    `BEGIN IMMEDIATE` transaction; a retried id is one row;
  - its own: the number re-normalised with `phone::normalize` (`invalid` if it doesn't); the Matrix ID through
    `valid_matrix_id`; the name through `calendar::clean`, at most 40 characters.
- **The answers:** `{"accepted": true}`, or `{"accepted": false, "reason": "capped" | "invalid" | "known"}` (`known`: the
  number is already a contact on this phone).
- **The contract file** `server/testdata/contact_request.json` (`request`, `answers`, and the policy's `contact_requests`
  row) is byte-identical in `launcher/app/src/test/resources/`. Both sides run it and compare the copies, as
  `time_request.json` does. Its paths go into both workflows and `CLAUDE.md`.
- **The outcome reaches the phone** in the policy: `call_policy.contact_requests: [{id, state: "open" | "declined"}]`,
  rows kept 7 days. An approval needs no row: the new contact itself arrives.

**On the server:**
- Table `device_contact_requests(device_id, id, name, number, matrix_id, asked_at, state)`, purged after 30 days.
- The rate: 3 a day per phone (`capped`). The push uses D16's `PushBudget`.
- The parent event `contact_request` (`Kind::ContactRequest`), key the phone's id, with the push switch
  `push_contact_request`, on by default.
  - Title: "{phone} ber om en ny kontakt: {name}" / "{phone} asks for a new contact: {name}".
  - Target: `/devices/{id}/calls#request-{id}`.
- **The PWA:** a "Forespørsler" ("Requests") card on the Calls page with:
  - each request marked by its number's kind: "Kortnummer - kan koste penger" ("Short number - may cost money") or
    "Nødnummer" ("Emergency number"), as `emergency::is_emergency` and `phone::normalize` say;
  - "Legg til…" ("Add…"): Add Contact prefilled with the name and number (QA R2-M1).
    - **The defaults for this path:** "Kan ringe {name}", "I telefonboken", "Kan sende melding til {name}" and "Kan få
      melding fra {name}" on (the kid asked to call and text this person); "På Hjem-skjermen" and "Bilder i meldinger"
      off.
    - **The Matrix ID** shows as the kid's suggestion, with its own unticked switch "Bruk Matrix-ID-en" ("Use the
      Matrix ID"). It is saved only when the parent ticks it, since an `@x:server` the kid typed can point to anyone.
    - The parent reviews and saves. Nothing is added by the request itself.
  - "Avslå" ("Decline").

- **Size:** M (both sides). Not approved: the user approves it together with the rest, or later.
