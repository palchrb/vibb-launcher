# 77 - SMS and MMS in Vibb One: one message engine, the kid's screen first, a bridge later

Status: design note by the architect, 2026-11-08, on `main` at 721af132, branch `sms-design`. **Nothing is built.** Nothing
gets built until the user approves this plan (PLAN.md: "Design 77: SMS and MMS client in the launcher, in design").
Both directories are affected. This note supersedes design 09 part A (`docs/design/09-sms-and-fixes.md`), which was
parked in 2026-10. Its platform facts (A1) still hold and are cited here, not repeated.

The user's words:
- 2026-11-07: "Formål er å kunne bestemme hvem som kan sms til og fra tlf også. Men for mitt bruk er jo det beste at alt
  kunne kommet inn i element x".
- 2026-11-08: "Kan du ikke gjøre en kodereview av fossify client en og andre libraries vi trenger for sms og mms og komme
  opp med en plan?" MMS pictures must show from day 1. Unknown senders are a risk; the kids don't need messages from
  alphanumeric senders ("Posten", bank codes) now.
- 2026-11-08: "kan være en fordel om vi kan lage et apparat som kan være en slags grensesnitt man kan både vise som egen
  klient og som senere kan plugges inn i en mulig mautrix bro".
- 2026-11-08: "ikke gift med tanken om at det må være fossify, det er bare et eksempel på noe vi kunne forket".

Markers:
- **[F]** marks code read in an upstream repo at the commit listed in §2.0, cited by file:line.
- **[web]** marks a source cited by URL.
- **[verify]** marks what only the emulator, the Jelly Star or a real SIM can answer. §10.3 lists those checks.

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
- Contacts with **In** may text the kid.
- Contacts with **Out** may be texted.
- Emergency numbers always work.
- Everything else, unknown numbers and every alphanumeric sender included, is dropped before it is stored. The parent
  sees who and when, never what.

MMS pictures are shown from the first build. The bytes of an MMS are parsed and its pictures decoded **in an isolated
process**, so a hostile MMS can't take down the launcher, which is also the phone app and the Device Owner agent.

**What we reuse, and how:**
- **No fork of any app.** Fossify Messages, QUIK, AOSP Messaging, LineageOS Messaging and GrapheneOS Messaging were
  weighed on equal terms (§4).
- **Vendored:** AOSP Messaging's MMS PDU codec (Apache-2.0, about 5,500 lines of Java), hardened with the fixes GrapheneOS
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
| RCS (Google Messages over data) | `DISALLOW_SMS` and the role don't cover it. Google Messages stays suspended and hidden whenever the mode isn't `open` (today: only when SMS is off). Runbook: turn RCS chats off before enrolling (design 09 A3). |
| SIM toolkit (STK) | Can show text and send SMS. It stays suspended in the `contacts` mode as in `off`. |
| Carrier messaging services, visual voicemail | System or privileged, they filter before us (`filterSms`, T `:1165`). Out of our control and not a path for strangers. |
| Cell broadcast (Nødvarsel) | Not SMS. The cell-broadcast receivers stay unsuspended (`AppEnforcer.kt:606-641`). Unchanged. |
| Notifications | Only ours, and only for accepted messages. The glance reads our engine directly (§6.8). |
| Other chat apps (WhatsApp, Element X) | Outside this design. The allowlist and the notification rule cover them as today. |

### 2.6 Verdict per component

| Component | Verdict | Why |
|---|---|---|
| Receivers, role, send, outbox, delivery state | **Write our own** | Each is about 100-300 lines. FM's versions have the issues above, and the gate and the seam must sit inside them. The platform APIs are small: `getMessagesFromIntent`, `divideMessage`, `sendMultipartTextMessage`. |
| MMS transport (HTTP, APN, NotifyResp, Ack) | **Use the platform** (`SmsManager`) | AM's pattern. No own HTTP or APN code: ML's `service_alt/` and `HttpUtils` are exactly the parts users report breaking. |
| MMS PDU codec: `PduParser`, `PduComposer`, `PduHeaders`, `PduPart`, `PduBody`, `EncodedStringValue`, `CharacterSets`, the message classes (not `PduPersister`) | **Vendor from AM** (Apache-2.0, about 5,500 lines), then harden (§8.1) | Fifteen years of carrier quirks we don't want to rediscover. Writing a WSP codec from scratch is the larger risk. |
| The store | **Write our own** (SQLite) | D6. |
| Image decode and re-encode | **Platform `ImageDecoder`, inside our isolated process** | D8. |
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
- Should the user later want "Posten" or "Vipps" let through, it would be a parent-typed list of exact sender names
  (§11, Q2), with the honest caveat that a fake "Posten" passes too.

## 4. The options, on equal terms

| | In-launcher engine (recommended) | Fork of Fossify Messages | Fork of QUIK | Fork of AOSP/LineageOS Messaging | Fork of GrapheneOS Messaging |
|---|---|---|---|---|---|
| Maintained upstream | n/a (ours) | yes (1.10.2, 2026-10) | yes, slower (v4.3.7, 2026-09) | AOSP: "not actively supported"; Lineage: lightly | very (daily) |
| MMS stack | platform transport + AM codec, hardened, isolated | ML: own NotifyResp HTTP, exported runtime receiver, unbounded parser, check after download | same lineage as ML | platform transport, unbounded parser | platform transport, partly hardened parser |
| Size we'd own | engine + UI, about 4-6k lines of Kotlin + 5.5k vendored | 14.7k + Commons (67 of 116 files) | 35k + Realm/Dagger/Rx | about 100k Java | 240k+ |
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

**D1. Vibb One takes `ROLE_SMS` while the SMS mode is `contacts`**, with `DevicePolicyManager.setDefaultSmsApplication`.
- It checks `RoleManager.isRoleHeld(ROLE_SMS)` afterwards, because the call returns silently on failure (design 09 A1).
- It clears `DISALLOW_CONFIG_DEFAULT_APPS` around the change, as for the dialer.
- The role is handed back to the previous holder when the mode leaves `contacts`, in design 09 A6's order.
- **No role, no SMS:** `restrictSms` holds while the mode is `contacts` and the role isn't ours.

**D2. Three modes, `call_policy.sms_mode`: `off` | `contacts` | `open`.**
- `off` is today's off.
- `open` is today's on: any SMS app, no filter, kept for a parent who wants it.
- `contacts` is this design.
- The server keeps sending `sms_enabled = (mode == "open")`, so a launcher from before this design fails closed on
  `contacts` (`DISALLOW_SMS`).
- A new phone gets `open`, today's default (`sms_enabled INTEGER NOT NULL DEFAULT 1`, migration 0022). The migration
  maps `sms_enabled` 0 to `off` and 1 to `open`, so nothing changes until the parent picks Contacts.

**D3. SMS reuses the call flags.**
- **In** (`inbound`) means "may text the kid", and **Out** (`outbound`) means "the kid may text".
- One mental model: who may reach him, whom he may reach. `message_app` still only picks what the Message button opens.
- Separate SMS flags are an open question (§11, Q4), not built.

**D4. The receive rule** (pure `sms/engine/SmsRules.kt`; the first rule that applies wins):
1. The callback window after an emergency call is open (`callbackWindowUntil`): ALLOW from any sender, alphanumeric
   included. The emergency services may text a link.
2. The rules are unknown (fail closed) or SMS isn't `contacts`: BLOCK.
3. The sender normalises (`PhoneNumbers.normalize`, the shared `phone_vectors.json`) and is an inbound contact: ALLOW.
4. The sender normalises to a number the platform calls an emergency number: ALLOW.
5. Anything else BLOCKS: an unknown number, an alphanumeric sender, an empty sender, a short code that isn't a contact.

A parent who wants a short code (say a sports club's 4-digit sender) adds it as a contact with In. 3-6 digits are
matched exactly, as for calls.

**D5. The send rule**, at the engine's `send`, every time, whoever calls it:
1. To an emergency number: ALLOW.
2. Fail closed, the mode isn't `contacts`, or the time rule blocks calls: BLOCK.
3. To an outbound contact: ALLOW.
4. Anything else: BLOCK.

The override PIN and the pause never open SMS (the existing explicit exception). A time rule's block is lifted by them,
as for calls.

**D6. Our own SQLite store is the system of record. Nothing is written to the Telephony provider in v1.**
- The framework's `SQLiteOpenHelper`, in CE storage. No new Gradle dependency, so no verification-metadata churn.
- Why:
  - Stable ids the seam can hand out. Provider ids are reused after a delete, and another default app may rewrite them.
  - Our states (verdict, delivery, part states) and client ids for idempotency.
  - Nothing for other apps to read.
  - One store instead of FM's two.
- The cost: when the parent hands the role back (mode `open` or `off`), the next SMS app sees no history. Exporting to the
  provider at the hand-back is a later S step (§11, Q6).
- Blocked messages never enter the store. A separate `blocked` table keeps kind, sender and time only.

**D7. Persist first.**
- `SmsDeliverReceiver` decides and commits in one transaction before anything else: no contact photo, no bitmap, no
  network.
- Then it emits the event, then the notification.
- If the process dies before the commit, the message is lost (§2.2). Keeping that window tiny is the only defence any
  default app has.

**D8. MMS bytes are parsed, and their pictures decoded, only in an isolated process.**
- A bound service with `android:isolatedProcess="true"` and its own process name: no permissions, no network, no
  provider, no files of ours.
- The main process passes the downloaded PDU in through a pipe (`ParcelFileDescriptor`). It gets back a small, validated
  structure:
  - headers: from, to and cc, transaction id, message id, date, subject;
  - text parts as strings, each at most 5,000 characters;
  - images re-encoded by the isolated process as JPEG: one at most 1,600 px on the long side, one thumbnail at 320 px.
- **The main process never decodes an image we didn't encode.** A parser or codec crash kills only the isolated process.
  The message is then shown as "Meldingen kunne ikke vises" ("The message couldn't be shown") and counted in the report.
- The same process composes outgoing pictures (D11): the kid's photo is re-encoded there too.

**D9. The PDU codec is vendored from AM**, at de315b76, `src/com/android/messaging/mmslib/pdu/`, without
`PduPersister` and its `ContentResolver` paths.
- Package `com.kidslauncher.mdm.sms.vendor.pdu`, Apache headers kept, a `NOTICE.md` entry with the source and commit.
- Its few Android imports (`android.util.Log`, `SparseArray`, `TextUtils`, androidx `SimpleArrayMap`) are swapped for
  plain Java, marked in each file's header as "modified". The launcher's JVM tests run without Robolectric and without
  `isReturnDefaultValues`, so the codec must be pure JVM to be tested and fuzzed.
- Hardened per §8.1, every fix with a JVM test.
- Re-checked against each Android security bulletin's telephony entries when we touch it.

**D10. MMS download is gated before the fetch.**
- On `WAP_PUSH_DELIVER`, the notification-ind is parsed in the isolated process.
- Its `From` must ALLOW by D4. A missing `From` (the "insert-address-token" hidden sender) blocks.
- `X-Mms-Message-Size` must be at most the carrier's maximum (`SmsManager.getCarrierConfigValues()`'s
  `MMS_CONFIG_MAX_MESSAGE_SIZE`, else 1 MB).
- Only then do we call `downloadMultimediaMessage`, with an explicit, manifest-registered result receiver (AM's pattern).
- After the download, the retrieve-conf's `From` must equal the notification's, normalised, and D12's group rule must
  hold. Else it is dropped and counted.
- **A blocked MMS is never fetched**, so no request to a stranger's URL ever leaves the phone (Enea's "MMS
  Fingerprint", §2.2).
- We don't answer the MMSC for a blocked MMS. Whether a NotifyResp "rejected" is worth sending is [verify].
- NotifyResp and Acknowledge for allowed messages go through `sendMultimediaMessage` (AM `MmsSender.java:89-135`).

**D11. Pictures are shown from day 1. Sending pictures is proposed, open (§11, Q3).**
- If approved: the kid picks from the camera or the system photo picker, the isolated process re-encodes under the
  carrier's limit (FM lowered its default to 200 KB after reports, FM #815), and the engine composes the send-req with
  the vendored `PduComposer`.
- Video and audio parts are not played in v1. They show as "Vedlegg som ikke kan vises" ("An attachment that can't be
  shown"); their bytes are not kept.

**D12. Group MMS.** A group (more than one other address in from, to and cc) is accepted only when the sender ALLOWs and
every other address is a phone-book contact (In or Out) or the phone's own number.
- The own number comes from `SubscriptionManager.getPhoneNumber`. Norwegian SIMs often don't carry it [verify]. If it is
  unknown, every group is blocked (counted as "group").
- The kid may reply in a group only when every participant is an Out contact. Otherwise the compose bar says "Du kan
  bare svare i samtaler med folk i telefonboka" ("You can only reply in conversations with people in the phone book").
- Starting a group: not in v1.
- FM's group bugs (#595 replies to an unrelated recipient; #709 and #744 quick reply reaching only the last participant)
  are why the recipient list of a reply is always the stored conversation's participants, never re-derived from the
  last message.

**D13. Emergency.**
- Sending to an emergency number is always allowed (D5).
- Norway has no general SMS-to-112. Whether the emergency text service for the deaf works from an SMS app is [verify].
  Nothing in this design blocks it.
- Whether `DISALLOW_SMS` (mode `off`) also stops the system's AML location SMS during an emergency call is design 09's
  open device check, unchanged.

**D14. Without data, and in airplane mode.**
- SMS needs only the cellular network: it works without data and with Wi-Fi off.
- MMS needs mobile data on the MMS APN (MS `:285`). Without it, an allowed MMS is stored as "Bilde venter - trenger
  mobildata" ("Picture waiting - needs mobile data") and downloaded when data returns:
  - on a `NetworkCallback` for `NET_CAPABILITY_MMS` availability, and on each sync;
  - at most five tries;
  - the notification-ind is kept in our DB, not in memory (FM #298 "MMS lost when received while no data").
- Airplane mode: nothing arrives. The network holds SMS and MMS notifications and delivers them later.
- Whether Telia, Telenor and Ice allow MMS with mobile data switched off is [verify].

## 6. Architecture in the launcher

### 6.1 Components (new package `sms/`)

| Component | Kind | Does |
|---|---|---|
| `sms/SmsDeliverReceiver` | receiver, `SMS_DELIVER`, `permission=BROADCAST_SMS` | `goAsync`, then `engine.receiveSms(parts, subId)` on the engine thread; `finish` in `finally` |
| `sms/MmsPushReceiver` | receiver, `WAP_PUSH_DELIVER`, `application/vnd.wap.mms-message`, `permission=BROADCAST_WAP_PUSH` | hands the PDU to `engine.receiveMmsNotification` |
| `sms/MmsResultReceiver` | receiver, not exported, explicit | download and send results from the platform |
| `sms/RespondViaMessageService` | service, `RESPOND_VIA_MESSAGE`, `permission=SEND_RESPOND_VIA_MESSAGE` | `engine.send` (the gate applies) |
| `sms/ComposeActivity` | `SENDTO`/`VIEW` `sms: smsto: mms: mmsto:` | an allowed number opens its thread with any `sms_body` as a draft (never sent without a tap); otherwise "Du kan ikke sende melding til dette nummeret" ("You can't send a message to this number") |
| `sms/SmsRole` | pure + glue | D1, shaped like `DialerRole.kt` |
| `sms/engine/*` | the engine (§6.2) | store, gate, outbox, MMS state machine, events |
| `sms/parse/MmsParseService` | `isolatedProcess` service | D8 |
| `sms/vendor/pdu/*` | vendored Java | D9 |
| `ui/messages/MessagesActivity`, `ThreadActivity`, `PictureActivity` | front end 1 | §6.7 |

**Process layout.** The engine and the receivers live in the main process, the Device Owner and phone-app process,
because the rules live there in memory (`CallPolicyStore`) and are refreshed there. A separate `:sms` process would have
to read the rules from SharedPreferences across processes, which isn't safe for the writers. The engine's own code is
small and touches only platform parsers (`SmsMessage`). The untrusted MMS formats are what goes out of process (D8).
§6.10 covers the rest of the containment.

None of these components is direct-boot-aware: the platform holds messages until the first unlock (§2.2).

### 6.2 The engine and its seam (front end 1 now, the bridge later)

The interface `sms/engine/MessageEngine.kt` is pure Kotlin types plus an implementation over SQLite. Every front end
goes through it. Nothing reaches the store or the radio around it.

**Data model**, with stable ids and ordering:

```kotlin
@JvmInline value class ConversationId(val value: String)      // ULID, engine-assigned, never reused
@JvmInline value class MessageId(val value: String)           // ULID, engine-assigned, never reused

data class Participant(val address: String,                   // normalised E.164 or short number
                       val contactId: Long?)                  // the policy contact, null = not in the phone book

data class Conversation(
    val id: ConversationId,
    val participants: List<Participant>,                      // sorted; 1 = one-to-one, >1 = group
    val lastSeq: Long, val lastAt: Long,
    val unread: Int,
    val canSend: Boolean,                                     // D5/D12 as of now (rules change)
)

enum class Direction { IN, OUT }
enum class Delivery { RECEIVED, QUEUED, SENDING, SENT, DELIVERED, FAILED, UNCERTAIN }
enum class PartState { PENDING, AVAILABLE, FAILED, TOO_LARGE, NOT_SHOWN }

data class Part(val index: Int, val mime: String,            // image/jpeg after our re-encode; text/plain
                val size: Long, val sha256: String?,          // of the stored (re-encoded) bytes
                val width: Int?, val height: Int?, val state: PartState)

data class Message(
    val id: MessageId, val conversationId: ConversationId,
    val seq: Long,                       // the engine's monotonic order, the only ordering key
    val direction: Direction,
    val sender: String?,                 // normalised; null for OUT
    val at: Long,                        // engine time: received, or queued
    val sentAt: Long?,                   // the SMSC's time for IN (shown only if plausible)
    val body: String?,                   // text (joined multipart SMS, or the MMS text parts)
    val parts: List<Part>,               // empty for SMS
    val delivery: Delivery, val failure: String?,
    val read: Boolean,
    val clientId: String?,               // the front end's idempotency key for OUT
    val origin: Origin,                  // KID_SCREEN, NOTIFICATION, REMOTE (the bridge, later)
)

sealed interface EngineEvent { val seq: Long
    data class MessageAdded(override val seq: Long, val message: Message) : EngineEvent
    data class MessageUpdated(override val seq: Long, val id: MessageId,
                              val delivery: Delivery, val parts: List<Part>) : EngineEvent
    data class Read(override val seq: Long, val conversationId: ConversationId, val upToSeq: Long,
                    val origin: Origin) : EngineEvent
}

data class SendCommand(val clientId: String,                 // UUID from the front end
                       val conversationId: ConversationId?,  // or:
                       val to: String?,                      // a raw number (normalised + gated here)
                       val body: String,
                       val pictures: List<PictureRef>,       // only if D11's sending is approved
                       val origin: Origin)

sealed interface SendResult {
    data class Accepted(val message: Message) : SendResult      // the same message for a repeated clientId
    data class Refused(val reason: Refusal) : SendResult        // NOT_ALLOWED, TIME_RULE, SMS_OFF, TOO_LARGE, EMPTY
}

interface MessageEngine {
    fun conversations(): List<Conversation>
    fun messages(id: ConversationId, beforeSeq: Long?, limit: Int): List<Message>   // backfill, newest first
    fun events(afterSeq: Long): List<EngineEvent>        // replay from a cursor; Flow for live use
    val live: kotlinx.coroutines.flow.Flow<EngineEvent>
    fun send(command: SendCommand): SendResult
    fun markRead(id: ConversationId, upToSeq: Long, origin: Origin)
    fun openPart(messageId: MessageId, index: Int): java.io.InputStream?    // by message, or:
    fun openPartByHash(sha256: String): java.io.InputStream?                // by hash
}
```

**How it behaves:**

- **Ordering.** `seq` is the engine's arrival order: one counter for messages and events, from SQLite. Lists sort by
  `seq`, never by the SMSC's time, which can be wrong. FM uses the receive time for the same reason (`SmsReceiver.kt:57`).
- **Events are a replayable log** (`event` table) with the same `seq`.
  - A front end keeps its cursor and asks `events(afterSeq)` after an absence.
  - The log is pruned after 30 days. A cursor older than the oldest kept `seq` gets a full `messages(...)` backfill
    instead. This maps 1:1 to SSE with `Last-Event-ID`.
- **Send is idempotent** on `clientId` (a unique index).
  1. The engine inserts the message `QUEUED` and returns it.
  2. A repeated `clientId` returns the same message and sends nothing.
  3. The outbox worker marks `SENDING` (committed) **before** handing it to `SmsManager`.
  4. After a process death, a message left in `SENDING` without a sent-result becomes `UNCERTAIN`, never re-sent by
     itself. This is at most once: the kid or the bridge may send again with a new `clientId`.
- **Delivery.** Each part's sent-intent moves it to `SENT` (or `FAILED` with the platform's code). The last part's
  delivery report moves it to `DELIVERED`. Delivery reports are requested for one-to-one SMS [verify that Norwegian
  operators don't bill them].
- **The gate sits inside `send` and inside the receive paths.** No front end gets a refused message's text into the
  store. `Refused` tells the front end why, in a closed vocabulary its words map to.
- **Rule changes are live.** `canSend` is computed when asked. A contact losing Out keeps the history but loses the
  compose bar. A contact losing In: their later messages are blocked. What is already stored stays (§11, Q5).

**The bridgev2 mapping, for a later step (not designed here).** The interface maps 1:1 to a JSON API over the existing
pinned phone endpoint, plus the SSE stream:

| bridgev2 needs | from the engine |
|---|---|
| Portal (room) id | `ConversationId` |
| `networkid.MessageID` | `MessageId` (stable, never reused) |
| Backfill (`FetchMessages`, forward and backward) | `messages(id, beforeSeq, limit)` + `events(afterSeq)` |
| Live remote events (`RemoteMessage`, edits of state) | `MessageAdded`, `MessageUpdated` over SSE, cursor = `seq` |
| Read receipts both ways | `markRead(origin = REMOTE)` in; `Read(origin = KID_SCREEN)` out |
| Matrix to network send, deduplicated | `send(SendCommand(clientId = the Matrix event's transaction id, origin = REMOTE))` |
| Message send status / checkpoints | `Delivery` + `Refused` reasons |
| Attachment download | `openPartByHash(sha256)` as `GET .../parts/{sha256}` |
| Attachment upload | a later `putPicture` returning a hash (D11's path), then `send` with that hash |
| Ghost identities | `Participant.address` + `contactId` (names come from the policy, which the server already has) |

The remote front end is the kid's own Matrix account seeing his SMS (the user's wish). It gets **only what the engine
already accepted, and can send only what the gate allows**. The phone's queue is the durable side. Whether bodies may
pass through the server (transport A: pass-through, no body at rest) is the bridge's own design.

### 6.3 Receiving an SMS

1. `getMessagesFromIntent`. The address is taken from part 0 and must be the same on every part. If not, the message is
   blocked as malformed. FM takes the last part's address with a TODO.
2. `subId` comes from the intent.
3. Then `SmsRules.decideIncoming` (D4).
4. ALLOW: one transaction inserts the message (finding or creating the one-to-one conversation by the normalised
   address) and the `MessageAdded` event; it commits; then the notification (§6.8).
5. BLOCK: one row in `blocked` (`kind = sms`, `sender_class` = number / short / alphanumeric / empty, the sender as
   received, cut to 20 characters; **no body**), then nothing visible.

**Class 0 ("flash") SMS** are treated like any other: gated, stored, shown in the thread, never as a pop-up.

### 6.4 Receiving an MMS

`notified` (the notification-ind is parsed and allowed; a row is created with the parts `PENDING`) →
`downloading` (`downloadMultimediaMessage`, result to `MmsResultReceiver`) → `parsing` (the isolated service) →
`available` | `failed(reason)`.

- Each step commits its state, so a process death resumes at the next start. FM's runtime receiver and static
  de-duplication set lose this (§2.2).
- De-duplication is by `(transactionId, contentLocation)`, a unique index.
- The downloaded file sits in `noBackupFilesDir/mms/in/`. It is deleted once parsed.
- Stored picture files are named by the SHA-256 of our re-encoded bytes. A file used by no message is deleted.

### 6.5 Sending

- SMS: `divideMessage` + `sendMultipartTextMessage`, sent on the default SMS subscription (single SIM is the target
  phone). Long text stays multipart SMS, never auto-converted to MMS (FM's option).
- The counter shows "2 SMS" only past 160 GSM-7 or 70 UCS-2 characters.
- Pictures: D11, only if approved.

### 6.6 What the store holds

| Table | Holds |
|---|---|
| `conversation` | id, a key (the sorted participant set), created, last seq |
| `participant` | conversation, address, contact id |
| `message` | §6.2 fields; `client_id` unique; MMS transaction id and content location |
| `part` | message, index, mime, size, sha256, size in px, state |
| `event` | seq, type, ids, at |
| `blocked` | at, kind (`sms` / `mms` / `group`), sender class, sender (at most 20 characters), reported |

- Messages are kept until the kid or the parent deletes them (§11, Q5). The launcher's existing CE file encryption
  covers them at rest.
- Pruning `blocked`: rows older than 30 days, or once the server confirmed them (§7).

### 6.7 The kid's screen (front end 1), in Dagen's spirit

Design 34's look: night ground, Nunito, `kid_tile`s, the one peach accent, `KidHeader`.

- **Home:** a "Meldinger" tile next to the Phone book (`GridTile.Messages`) while the mode is `contacts` and the role is
  ours. Its badge is the unread total.
- **Contact avatars:** the badge is missed calls plus unread messages.
- **`MessagesActivity`:**
  - one row per conversation with messages, newest first: avatar (`KidAvatars`), name (800), the last line (600, a
    picture shows as "Bilde"), an unread dot;
  - a "Ny melding" action listing only Out contacts.
- **`ThreadActivity`:**
  - bubbles: incoming on the tile surface, outgoing in the peach tint;
  - day separators in the one clock format (P12);
  - pictures as rounded thumbnails, at most 70 % of the width. A tap opens `PictureActivity`: fit to screen, no save, no
    share (§11, Q7);
  - "Sendt", "Levert" or "Ikke sendt - trykk for å prøve igjen" ("Not sent - tap to try again", a new `clientId`) under
    the kid's last message.
  - The compose bar shows only when `canSend`.
- **The contact sheet's Message button:** when `messageApp == "sms"` and the mode is `contacts`, it opens the thread
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
  - Decoding for the large icon uses only our re-encoded thumbnail, never the original bytes.
- **"Svar" ("Reply"), a `RemoteInput` direct reply:** a not-exported receiver calls `engine.send(origin = NOTIFICATION)`
  with a `clientId` taken from the reply's intent, so a double tap sends once. It is shown only while `canSend`.
- **The lock glance:**
  - a "Meldinger" row like the missed-call row (design 25): the sender's name and count;
  - the text only if the parent switches it on (`call_policy.sms_lock_text`, default off, the same spirit as design
    51's per-app text);
  - a tap opens through `GlanceOpener` as the missed-call row does.
  - Our own notifications stay out of `GlanceReader` (it skips ours). The row reads the engine.

### 6.9 i18n

- Every new string is in `values/strings.xml` (en) and `values-nb/strings.xml` (nb). The nb words quoted in this note
  are the intended nb strings; the en strings follow the existing pattern.
- The PWA strings are in both catalogues (`server/src/i18n/`).

### 6.10 What keeps working when the SMS code fails

- **Calls, call screening, Home and enforcement must not depend on the SMS code.**
  - Every receiver and service body is wrapped in `runCatching` (catching `Throwable` in the receivers, as the call
    services do). A failure is recorded in `sms_state.last_error` and counted.
  - The engine thread is its own single-thread executor. An exception on it never reaches the main looper.
- The isolated parser can only kill itself (D8). A parser that dies three times on the same message marks it `FAILED`
  and stops retrying it.
- **If the SMS code is broken, incoming SMS are lost:** nobody else gets `SMS_DELIVER`, and others are denied
  `SMS_RECEIVED`. That is the fail-closed choice. The parent sees it on the Calls page (§7), and can switch the mode to
  `open` from the PWA, which hands the role back.
- Before the first unlock, nothing of ours runs for SMS. The platform holds messages.

## 7. Server

**Migration `0130_sms.sql`:**
- `device_policy.sms_mode TEXT NOT NULL DEFAULT 'open' CHECK (sms_mode IN ('off','contacts','open'))`, backfilled from
  `sms_enabled`.
- `device_policy.sms_lock_text INTEGER NOT NULL DEFAULT 0`.
- `device_sms_blocked(id, device_id, at, kind, sender_class, sender, received_at)` with an index on `(device_id, at)`.
  Purged after 30 days by `retention`.

**Policy** (`build_call_policy`, `models::CallPolicy`):
- `sms_mode` (always sent);
- `sms_enabled = (sms_mode == "open")`;
- `sms_lock_text`.
- Pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

**Status report:**
- capability `sms_engine_v1`;
- `sms_state`, held by the server through its known keys like `call_state`:
  - `role_held`, `mode_applied`;
  - `received` and `sent` counts since the last report;
  - `mms_waiting` (count), `parse_failures`, `last_error` (300 characters), `own_number_known`.
- **No content and no senders.**

**Blocked senders:** `POST /api/devices/sms-blocked` (like `dns-events`; rows `{at, kind, sender_class, sender}`; at most
50 per post). The phone marks its rows reported on a 2xx.

**The PWA, Device › Calls & Contacts:**
- The SMS row becomes a three-way choice: "Av" ("Off") / "Bare kontakter" ("Contacts only") / "Alle (uten filter)"
  ("Everyone, no filter").
- A "Vis meldingstekst på låseskjermen" ("Show message text on the lock screen") switch.
- A **"Blokkerte meldinger"** ("Blocked messages") card:
  - the last 30 days, newest first;
  - sender (a number with "Legg til som kontakt…" ("Add as contact…"), which opens Add Contact prefilled; a name such as
    "DNB" shown as is), kind and time;
  - **never content**;
  - a device-page summary row "3 blokkerte meldinger denne uken" ("3 blocked messages this week").
- The calls report's findings gain "SMS-appen er ikke Vibb One" ("Vibb One isn't the SMS app": role not held while
  `contacts`; design 09 B1's pending rule applies) and "Meldinger kunne ikke vises" ("Messages couldn't be shown":
  parse failures).
- No push per blocked message in v1. A parent inbox event (design 35) is an open question (§11, Q1).

**Contract file:** `server/testdata/sms_contract.json`, byte-identical in `launcher/app/src/test/resources/`:
- `policy` rows (each mode, the old-launcher view);
- `report` (the exact `sms_state` and the capability);
- `blocked` (the exact POST body, and the server's answers);
- `rules` (D4/D5 vectors: sender or destination, mode, contact flags, window, emergency → verdict).

Both sides run every row and compare the copies. It lands in both directories in one commit, and goes into
`launcher.yml`'s and `server-ci.yml`'s path lists and the root `CLAUDE.md`'s contract list.

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
- no permissions;
- input by pipe;
- output as a parcelable, size-checked on receipt: at most 20 parts, re-encoded images at most 1,600 px and 512 KB
  each, text at most 5,000 characters;
- `ImageDecoder` with `setTargetSize` from the header's dimensions, refusing more than 40 MP or more than 8,192 px a
  side before decoding.
- HEIF and AVIF may need `MediaCodec`, which an isolated process may not reach [verify]. If they fail, they show as
  "Bilde kan ikke vises" ("Picture can't be shown").

### 8.3 Other apps

The policy denials in §2.5. The SMS packages stay suspended in `contacts` (`restrictSms` widens to "mode isn't `open`"
for the suspension, while `DISALLOW_SMS` itself stays off in `contacts`). The new permissions are self-granted and
policy-fixed, so a role hand-back never kills us (design 09 A1/B2):
- `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, added to the manifest;
- plus `READ_PHONE_NUMBERS` for D12.

### 8.4 No exported surface beyond the role's four components

- Each of the four is guarded by its system permission.
- The result receivers, the reply receiver and the parse service are not exported.

FM's exported `DeleteSmsReceiver` (#834) is the lesson.

## 9. Steps and sizes

0. **Device checks first (S, no code):**
   - `setDefaultSmsApplication` on the Jelly Star; denying the SMS permissions to a third-party app; the own number on a
     Telia/Telenor/Ice SIM;
   - MMS with mobile data off on each carrier; the carrier's MMS maximum (`getCarrierConfigValues`);
   - a real MMS round trip with a picture.
   - These decide D12's fallback and D14's words.
1. **The engine and SMS (L), launcher:**
   - the role (D1), the receivers, `SmsRules`, the store, outbox and delivery, the event log, notifications with reply,
     the permission denials;
   - `sms_state`, the blocked-rows post;
   - the contract file with the server's policy key and intake (S on the server, same commit).
2. **The kid's screens (M):** Home tile, `MessagesActivity`, `ThreadActivity`, the contact sheet's button, the time-rule
   behaviour, en + nb.
3. **MMS receive and show (L):** the vendored codec and its hardening and fuzz test (D9, §8.1), the isolated service
   (D8), the gated download (D10), the state machine, pictures in the thread, D14's waiting state, the group rule (D12).
4. **The PWA (M), server:** the three-way choice, the lock-text switch, the Blocked Messages card with "Add as contact",
   the findings, the device-page row, retention.
5. **The glance row (S).**
6. **Sending pictures (M) - only if the user says yes (Q3).**
7. **Provider export at the hand-back (S) - only if wanted (Q6).**
8. **Bridge front end (later, not approved; L+, its own design):** the JSON + SSE mapping of §6.2 on the phone, the
   server's pass-through, the mautrix bridgev2 connector.

Order: 1 → 2 → 3 → 4 can follow 1 at any point. Steps 1-4 ship together as one release for the user's phones, since
`contacts` mode without the screens or MMS would lose messages from the kid's point of view.

## 10. Tests (lean) and the emulator

### 10.1 Launcher (JVM)

- `SmsRulesTest` runs `sms_contract.json`'s `rules`.
- `SmsEngineTest` (the engine's logic over a `MessageStore` interface with an in-memory fake; the launcher has no
  Robolectric and this design adds none, so the thin SQLite implementation is checked on the emulator, §10.3):
  - send idempotency (the same `clientId` twice, one send);
  - `SENDING` to `UNCERTAIN` after a simulated death;
  - event replay from a cursor;
  - `canSend` after a rule change.
- `PduHardeningTest` + `PduFuzzTest` (§8.1).
- `MmsGateTest`: notification From vs retrieve From, the group rule, a missing From.
- `SmsContractTest` compares the copies and encodes `report` and `blocked` exactly.
- `PolicyResponseCompatTest` gains the keys.

### 10.2 Server

- `sms::tests` covers the policy rows and the intake (known keys, caps).
- `tests::calls` gains the three-way form, the card and Add as contact prefilled.
- The `policy_json_keys_snapshot` update.

### 10.3 Emulator, and what it can't do

- **SMS in:** `adb emu sms send <number> <text>` from a contact, an unknown number and a short code. Expect the thread,
  a blocked row, and a blocked row respectively.
- **Alphanumeric senders:** the console's `sms pdu <hex>` with a TP-OA of type alphanumeric (a captured "DNB"-style PDU
  in `docs/testing/emulator.md`) [verify that the console accepts it].
- **SMS out:** the emulator's own number or a second emulator.
- **MMS notification:** a WAP-push PDU injected with `sms pdu` (port 2948) reaches `MmsPushReceiver`, so the gate and the
  "From missing" block are testable.
- **MMS download:** needs an MMSC on the emulator's MMS APN [verify]. If that fails, the download and picture path is
  covered by JVM tests on captured retrieve-confs, plus step 0's real-SIM check.
- **Crash containment:** a crafted retrieve-conf fed to the parse service in a debug build kills only the isolated
  process (`adb shell ps`).

## 11. Open for the user

1. **What the parent sees of blocked messages:** sender and time on the Calls page (proposed), or only a count? Should a
   push come, for example once a day?
2. **Alphanumeric senders later** ("Posten", "Vipps", school SMS): blocked for now as you said. If wanted later, a
   parent-typed list of exact names, knowing that a fake "Posten" gets through too (§3).
3. **May the kid send pictures** from the camera and gallery (step 6), or only receive them?
4. **May the same contact flags (Can call / Can be called) decide SMS**, or do you want separate "can text" switches?
5. **May the kid delete messages or conversations?** Should what a contact sent stay visible after the parent removes
   the contact?
6. **When SMS is set back to "Everyone" or "Off"**, should the history go to Google Messages (the export step), or stay
   only in Vibb One?
7. **May the kid save a received picture** to the gallery or share it on?
