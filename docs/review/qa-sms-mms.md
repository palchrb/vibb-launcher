# Code QA: design 77 step 3b - MMS pictures (branch `sms-mms`)

Reviewed: `sms-mms` at b06ac01d (three commits on main 77671a37: e646ff2f, 0dff8827, b06ac01d), against
`docs/design/77-sms-mms-client.md` rev 3 (§9 step 3b, D8, D10, D11, D12, D14, D16, §6.4-§6.6, §8.2) and the earlier
reviews (`qa-77-design.md`, `qa-sms-mms-codec.md` "For step 3b", `qa-sms-engine.md` N5 and L4, `qa-sms-screens.md` and
its step 3b checklist). Focus: security first, then correctness, product rules and tests.

AOSP checked for two platform facts (`packages/services/Mms`, main): `MmsService.writePduToContentUri` opens our URI
with mode `"w"` (so `MmsFileProvider`'s `mode == "w"` matches), and `MmsHttpClient` never calls
`setInstanceFollowRedirects`, so the platform follows same-protocol redirects (N2).

## Verdict

The security shape is right and mostly well executed. The parser and both decoders run only in a real
`isolatedProcess` service; the wire is hand-read with every length and count checked first; pictures come back as
pixels in a read-only shared region and the main process only ever encodes its own JPEGs (no EXIF either way). D10's
gate runs before any fetch: the `/TYPE=PLMN` digits rule, D4 on the number, a strict `java.net.URI` host/port/userinfo
check against the SIM's MCC/MNC row (an unknown operator fails closed), the size, and the second `From` check after
the download. Held pushes are fetched only after a release that names the exact sender (rule 5, never an emergency
number), and every D10 check runs again. The debug hook is cleanly out of the release build. No Critical or High
finding.

The Mediums are a missing aggregate bound on one message's pixels (with no death count when the *main* process is the
one that dies), a download that isn't re-gated on the sender after the first check (a revoked contact's waiting MMS
is still fetched), and the photo picker's returned URI read without any check.

Counts: **C 0, H 0, M 3, L 10, N 9.**

## Medium

### M1. One message can make the main process hold about 200 MB of pixels, and a main-process death during the parse is never counted

`sms/parse/MmsParseService.kt:116-136`, `sms/parse/MmsParser.kt:91-106`, `sms/mms/MmsRuntime.kt:111-153`.

The isolated service decodes **every** image part (up to `MAX_PARTS` = 20), each up to 1,600 x 1,600 RGBA (10.24 MB),
and returns them all in one reply. `MmsParser.parseRetrieve` then builds all of them as main-process bitmaps before
`MmsRuntime.retrieved` encodes the first one. §8.2's "at most 1,600 px a side (so at most about 10 MB)" is per picture;
nothing bounds the message.

Scenario: an allowed contact, or someone spoofing that contact's number through the real MMSC (§3's residual risk),
sends a 300 KB MMS with 20 PNGs of 1,600 x 1,600 flat colour (a few KB each). The isolated process holds ~200 MB of
shared memory, then the Device Owner/Home process allocates ~200 MB of native bitmaps at once, plus a 10 MB `flat`
copy per encode. On a phone under memory pressure the launcher process (Home, dialer, Device Owner) is the one that
dies. The `mms` row is still `DOWNLOADED`, `onParseCrashed` is only reached on `Answer.Crashed` (the *isolated*
process dying), so the next start parses the same file again: a restart loop of the kid's Home with no counter and no
end until the file is pruned a day later.

Fix:
- Bound the message, not just the picture: in the service, decode at most N pictures (e.g. 4, or a total of 16 MP) and
  return the rest as `PART_UNDECODABLE` (or "not shown"). The kid's screen shows one picture per bubble anyway.
- Stream on the main side: keep the `SharedMemory` objects from the reply and build, encode and recycle one bitmap at
  a time (`retrieved()`), instead of materialising every bitmap in `parseRetrieve`.
- Count the attempt **before** the parse: persist `crashes + 1` (or an `attempts` column) before calling
  `parseRetrieve` and reset it on any answer. A main-process death then counts toward `PARSE_DEATHS` like an isolated
  one, and the loop ends at the third start.

### M2. The download isn't re-gated on the sender: a revoked contact's waiting MMS is still fetched and shown

`sms/engine/MmsFlow.kt:249-272` (`drainDownloads`), `:406-424` (`retryWaiting`, `retryDownload`), `:295-301`
(`parseJobs`), `:309-345` (`onRetrieved`).

D4 and D10's sender check run once, in `judgePush`. Afterwards a `NOTIFIED`, `WAITING_DATA`, `FAILED/retry` or
`DOWNLOADED` row is fetched and shown on the host and expiry checks alone. D10's promise is "an MMS is never fetched
for a blocked sender".

Scenario: Mamma's ex-partner is a contact with "Kan sende melding til". An MMS from them arrives while mobile data is
off (`WAITING_DATA`, "Bilde venter - trenger mobildata"). The parent turns that contact's texts off (or removes the
contact) that evening. The next morning mobile data comes back: `retryWaiting(dataOn = true)` moves it to `NOTIFIED`,
`drainDownloads` fetches it, `onRetrieved` checks only `From == notification From` and the group rule (which uses the
*current* contacts for the others, not for the sender), and the picture shows. The same holds for the kid's "Trykk for
å prøve igjen" days later, and for a callback-window stranger whose fetch waited past the window.

Fix: re-judge the sender at `drainDownloads` (before `DOWNLOADING` is committed) and again in `onRetrieved`:
`SmsRules.decideIncoming(SenderKind.NUMBER, m.from, judge)` must still be `Allow` (for a 1:1) and `SmsRules.releases`
for a group. Otherwise `drop(m, clean, ...)` as a blocked `rule` (the placeholder goes, `MessageDeleted`). If the
callback window is meant to cover a later fetch, store the admission basis (`rule5` / `callback`) on the `mms` row and
let a callback admission expire with the window. Test: "a contact switched off before the fetch - nothing is fetched".

### M3. The photo picker's answer is read as any URI the answering activity returns

`ui/messages/ThreadActivity.kt:96-99` (`pickLauncher`), `ui/messages/PicturePick.kt:89-97` (`prepare`).

`result.data?.data` goes straight to `contentResolver.openInputStream(uri)` in the main process, with the launcher's
own identity. `ACTION_PICK_IMAGES` is an implicit intent. Whatever activity answers it can return a `file://` URI into
our private storage, or a `content://` URI of our own non-exported providers (`${applicationId}.mms`, the share
provider), and we read it as ourselves (the confused-deputy pattern).

Scenario: on a phone where another allowed app also declares `android.provider.action.PICK_IMAGES` (any app may declare
the filter; with kiosk off the resolver offers it), it returns `file:///data/user/0/me.vibb.launcher/files/sms/pictures/<sha>`.
That is a received picture from another conversation. Because the bytes are a valid JPEG, the isolated decode
succeeds, and the kid's "Send" sends a picture the kid was never shown in this thread, to this recipient. More
generally, the main process reads up to 30 MB of any file it can open. The decode in the isolated process limits what
leaks to things that decode as images, but it doesn't stop the read.

Fix: accept only `content://` URIs whose authority isn't ours (`!authority.startsWith(packageName)`), and refuse
`file://` (the camera path already has its `File` and can call `prepare` with it directly, not via `Uri.fromFile`).
Optionally require the result's `ComponentName`/package to be the resolved photo-picker helper
(`HelperKind.PHOTO_PICKER`'s package) by launching the intent with `setPackage` of that resolved package, as
`captureIntent` already does for the camera. A pure `pickedUriAcceptable(uri, ownPackage)` with a JVM test.

## Low

### L1. The m-acknowledge-ind goes out before the From and group checks

`sms/engine/MmsFlow.kt:326-344`. `answer(m, ...)` runs before `sameSender` and `MmsGate.group`. A message that is then
dropped (D10's second From check, or D12's stranger group) has already been acknowledged, which confirms delivery (a
read/delivery signal, "this number is live") for a message D10 says we don't answer. Fix: move `answer` after both
checks (only for a message that is kept).

### L2. Acknowledge vs. NotifyResp, and the 24 h dedup window

`sms/engine/MmsFlow.kt:462-470`, `SmsEngine.RECEIVED_KEEP_MS` (24 h).
- For an immediate fetch, OMA MMS ENC (and AOSP Messaging: `sendNotifyResponseForMmsDownload(STATUS_RETRIEVED)` when
  auto-retrieving, `sendAcknowledgeForMmsDownload` only for a deferred one) answers with an M-NotifyResp.ind
  (`Retrieved`), not an M-Acknowledge.ind. An MMSC that waits for the NotifyResp keeps re-pushing.
- Held and blocked pushes are never answered (by design), so the MMSC re-pushes them until `X-Mms-Expiry` (often
  days). Dedup forgets after 24 h. A stranger's held MMS then becomes a new held row each day, with a new
  `sms_held` push to the parent for the same message. An early `blockPush` (hidden or non-PLMN sender, malformed) never
  calls `rememberReceived` at all, so each repeat adds a `blocked` row (QA L5 asked for the blocked rows too).
Fix: NotifyResp(`Retrieved`) for an immediate fetch, Acknowledge for a released or retried (deferred) one. Keep the
push key for the push's own expiry (capped, e.g. 7 days), and remember it in `blockPush` too. Confirm the carriers'
behaviour in step 0.

### L3. Concurrent calls share one isolated process

`sms/parse/MmsParser.kt:136-158`. Each call binds and unbinds `MmsParseService`. Sequential calls get a fresh process
("isolated not needed" kills it on unbind, as the emulator log shows). But the kid's `DECODE_IMAGE` (the `sms-picture`
thread) and `PARSE_SEND_CONF` (the results thread) can overlap a worker's `PARSE_NOTIFICATION`/`PARSE_RETRIEVE`, and
then both bindings land in the same process. A parser compromised by a stranger's push (reachable by anyone) can
stall up to 30 s to stay alive. Meanwhile it sees the kid's picked photo and can alter the pixels the kid sends, or
answer the other call. Fix: `Context.bindIsolatedService(intent, flags, instanceName = <fresh per call>, executor, conn)`
(API 29). Each call then gets its own isolated process, and a compromise ends with its input.

### L4. The main process maps a peer-supplied fd: a compromised parser can SIGBUS the launcher

`sms/parse/MmsParser.kt:116-132`. `readTypedObject(SharedMemory.CREATOR)` accepts any fd that `SharedMemory` sizes.
A compromised isolated process can hand over a memfd it made without `F_SEAL_SHRINK`, then truncate it after the
size check. `copyPixelsFromBuffer` then faults with SIGBUS, which kills the Device Owner/Home process. That is a
second-stage crash beyond "wrong pixels" (D8's stated bound), and the parse is retried at the next start (M1's loop).
Fix: copy with `Os.pread` from the fd into a heap or `Bitmap` buffer (a short read is `Malformed`, never a fault). Or
require `F_SEAL_SHRINK | F_SEAL_GROW` (`Os.fcntlInt(fd, F_GET_SEALS)`) before mapping.

### L5. An over-cap download is retried as an ordinary failure, again at each data reconnect

`sms/mms/MmsFileProvider.kt:68-71`, `sms/mms/MmsRuntime.kt:159-167`. Past the cap the reader stops and the platform's
write fails. The result maps to `DownloadError.OTHER`: five more full downloads, then "trykk for å prøve igjen", and
`retryWaiting(dataOn = true)` resets the count on every reconnect. The MMSC sets the real size, so this is mostly a
data-cost loop on a carrier that understates `X-Mms-Message-Size`. Fix: the provider records "over the cap" for the id.
`onDownloadResult` then moves the message to `TOO_LARGE`, which is final.

### L6. `Unavailable` is silent forever

`sms/mms/MmsRuntime.kt:99, 129`. If the isolated service can't be bound (a vendor restriction, a broken install),
every push stays in `pending_push` (100 rows, a week) and every fetched file stays `DOWNLOADED`. Nothing reaches
`sms_state`. Fix: count `Unavailable` (`mms.parse_unavailable`) and set `last_error`, so the PWA shows it.

### L7. `mms_data_allowed` still reports mobile data (qa-sms-engine N5, deferred to 3b)

`sms/SmsRuntime.kt:411`. Unchanged. With the per-SIM "MMS-meldinger" on and data off, the PWA says pictures can't
arrive although they can. The notes say it can't be read by an app. Then name the field's meaning in the PWA's words,
or read `TelephonyManager.isDataEnabledForReason` plus the carrier's `KEY_CARRIER_..._MMS_ALWAYS_ALLOWED` where
available. Either way, put it on the step 0 list explicitly.

### L8. Notifications: a waiting MMS is told, its arrival isn't; a moved group message keeps the 1:1 notification

`sms/engine/MmsFlow.kt:379`, `:431-445`. `WAITING_DATA` is in `TOLD_STATES`, so the kid is notified "Bilde" with no
text. When the message arrives hours later (its text included), `result?.takeIf { !m.notified }` is null and nothing
is notified. And if the message turns out to be a group, it moves to the group conversation while the notification
stays on the sender's 1:1 thread. Fix: notify (silently, `setOnlyAlertOnce`) on `DONE` when the earlier notification
was a waiting one, and cancel or re-post the 1:1 notification on a group move.

### L9. A released MMS is reported "Levert" before it is known to arrive

`sms/engine/SmsEngine.kt:293`. The held row becomes `RELEASED` (posted, PWA "Levert") as soon as the push goes back to
`pending_push`. `judgePush` may then block it (`bad_location`), fail it (unknown operator, too large) or find it
`GONE`. The parent sees "Delivered" for something the kid never got. Fix: keep the held row `HELD`/`releasing` until
`judgePush`'s outcome, or post `released` only once the message is shown (a failed fetch is still "shown").

### L10. `MMSC_UNKNOWN` failures stay failed after the table is filled

`sms/engine/MmsFlow.kt:217, 256`. Before step 0 fills `mmsc_hosts`, every MMS is `FAILED/mmsc_unknown`, with no tap
and no retry. The first release that ships the table leaves every earlier picture unfetchable, even within the
carrier's expiry. Fix: `retryWaiting` also moves `FAILED/mmsc_unknown` rows back to `NOTIFIED` when
`MmsGate.known(...)` becomes true (`drainDownloads` re-checks the host anyway).

## Nits

- **N1** Parser compromise means header forgery. D8 trusts the isolated process's `From`/`To`/`Cc`/Content-Location
  strings. A decoder or codec RCE in it can forge an allowed `From` for its own push and pass both From checks. That
  is inherent in D8, but §8.6 ("residual risks") should name it next to "wrong pixels". L3 limits it to one input.
- **N2** The platform follows same-protocol HTTP redirects (`MmsHttpClient`, no `setInstanceFollowRedirects(false)`), so
  the host check covers the first hop only. The MMSC is the carrier's, so this is residual and goes into §8.6. Step 0
  can check whether the MMS APN reaches outside hosts at all.
- **N3** `SmsEngine.send` checks the picture against `judge.carrier(null)` (`SmsEngine.kt:416`) and `PicturePick.prepare`
  against the default subscription, while the send uses the conversation's `subId`. Use the conversation's SIM.
- **N4** `sendLimit`'s 8 KB headroom is less than `MMS_OVERHEAD` (2 KB) + a 1,600-character body x 4 (6.4 KB). Near the
  carrier's limit, a prepared picture plus a long text is then refused `TOO_LARGE` at Send. Make the headroom 10 KB, or
  derive it from the same constant.
- **N5** `captureIntent` grants READ as well as WRITE (`PicturePick.kt:73`); the camera only needs WRITE. Harmless for
  our own empty file, but it is wider than needed.
- **N6** In "Ikke styrt" with the role still ours (qa-sms-engine L3's unfiltered path), `judge.rules` is null, so
  `mmscHosts` is empty and every MMS fails `mmsc_unknown`. Groups would also fail D12. That is fail-closed and fine,
  but say so in the notes, since L3 promised "kept and shown unfiltered".
- **N7** A parse failure or crash shows "Bildet kunne ikke hentes" even for a text-only MMS, where D8 says "Meldingen
  kunne ikke vises". Give `failure = parse|crash` its own line (en + nb).
- **N8** If the kid re-picks a picture whose re-encode equals an existing message's file, then deletes that message
  before sending, `dropUnheld` deletes the staged pick (`MmsFlow.dropUnheld` only knows parts). The send then answers
  `NOT_ALLOWED`. Rare. Exclude hashes in `ThreadActivity.pictureDrafts`, or re-`put` at send.
- **N9** The photo picker is a kiosk helper that isn't suspended, so under a time rule that allows calls the kid can
  browse the gallery from a thread. It is read-only, and the parent may accept it, but it belongs in the time-rule
  notes next to L6's share sheet.

## The builder's deviations

1. **`PartState.GONE`** - *Good.* D16's "Bildet er ikke lenger tilgjengelig" needed its own state, and `partViews` maps
   it. It is an additive enum value on the seam.
2. **`PictureRef` gained width and height** - *Good.* Defaults keep old callers. The engine trusts nothing in it except
   the hash, which it checks against its own files.
3. **`MessageUpdated` without a body** - *Acceptable for front end 1*, since the screens reload the message on any
   event. For the bridge (§9 step 8), write into §6.2 that `MessageUpdated` can change `body` and that a consumer
   re-reads the message. Otherwise a bridge mirroring from events alone misses an MMS's text.
4. **One JPEG per part, no thumbnail** - *Acceptable.* `SmsPictures.decode` samples down (`inSampleSize`) from our own
   JPEG and caches. It is one fewer file to keep in step with deletes. D8's thumbnail line should be updated.
5. **The public data listeners instead of `DataEnabledListener`** - *Acceptable.* `DataEnabledListener` is a system
   API. Each sync still retries, so a missed edge costs at most one sync interval. The listener is on the default
   subscription only; note it for dual SIM.
6. **Reworded failure lines** - *Acceptable*, except N7 (a parse failure isn't a "fetch"). Both languages are present.
7. **D15 pending in `pending_push`** - *Acceptable.* The push stays at most 2 KB, is capped at 100 rows and judged at
   each rule change. Two notes: a pending push is re-parsed in a fresh isolated process at every pass while the rules
   are unknown (fine at 100 rows); and the 100-row cap is shared with strangers, so a flood while the rules are
   unknown can push out a contact's push (counted `malformed`). D15's 200-row `HELD_RULES` cap for SMS has the same
   shape. Accept and say so in the notes.
8. **Group move via delete plus add** - *Acceptable for the kid's screen*, which can't know the group before the
   retrieve-conf. A seam consumer sees `MessageDeleted(id)` followed by `MessageAdded(id)` for the same id. §6.2 must
   say a deleted id can come back (or use a new id for the moved row). See L8 for the notification.
9. **Groups need rule 5 (the callback window is not enough)** - *Correct.* This is D12's first bullet, not a
   deviation. Add a test (missing, below).
10. **One picture per message** - *Acceptable* for v1 sending (`SEND_PICTURES_MAX = 1`). Receiving keeps every
    picture, which M1 bounds.
11. **Send limit = carrier max - 8 KB, capped at 200 KB** - *Acceptable* (FM #815), with N4's headroom.
12. **Text-only group replies need `sms_out` only** - *Acceptable.* D11's `mms` switch is about pictures, and D11 itself
    keeps group texts working with `mms` off. The cost of an MMS for a group reply is the parent's to know about: one
    line in the PWA's help.
13. **m-send-conf parsed in isolation** - *Good*, and consistent with D8 (no PDU parse in the main process). A crash
    there leaves the send "Sendt" (status unknown means not refused). Acceptable.
14. **The ack goes to the APN's MMSC with no NotifyResp** - *Change*, see L2: NotifyResp(`Retrieved`) for an immediate
    fetch, Acknowledge for a deferred one. Verify on each carrier in step 0.
15. **Duplicate detection by a hash in `received_key`** - *Acceptable* as the mechanism. The window (24 h) and the early
    blocks need L2's fix.
16. **The camera via `ACTION_IMAGE_CAPTURE` to the parent-allowed camera app** - *Good.* There is no camera code in the
    Device Owner's process. `setPackage` targets a package that is usable now (installed, not suspended, on the
    allowlist), so the camera is not offered under a time rule that suspends it. The output is a fresh random file
    under the share provider's `capture/` path, read once and deleted, and its EXIF dies in the re-encode.
    `KidScreensEscapeTest` pins all of this. One leftover: the camera app's own UI (gallery thumbnail, settings) is the
    camera app's; with kiosk on, lock task stops a jump into another app.

## Security checklist (the brief's points)

- **Isolated process** - Yes: `isolatedProcess="true"`, `exported="false"`, `:mmsparse`, no permission, no filter
  (`MmsParseBoundaryTest`). `Application.onCreate` and the component factory return at once (`Process.isIsolated()`).
  Only strings, counts, ints and `SharedMemory` cross; each string's length is read and checked before
  `readString` (`MmsParseProtocol.readString`); counts are bounded (`MAX_ADDRESSES`, `MAX_PARTS`); pixel dimensions
  are within the cap and `size == w*h*4`. Fail-closed: a death, a 30 s hang (`Crashed`; the unbind kills the isolated
  process, "isolated not needed"), or an answer out of protocol counts toward three deaths and then blocks the push
  or fails the message. Gaps: M1 (aggregate pixels, main-process death), L3, L4.
- **Re-encode** - The main process's bitmaps come only from `copyPixelsFromBuffer`, and `PictureEncoder` writes plain
  JPEG (no EXIF, so no GPS) on both receive and send. Bombs: 8,192 px a side and 40 MP refused at the header, the
  target size at most 1,600/1,280, software allocation, any `Throwable` treated as undecodable. Per-message
  aggregate: M1.
- **Download gate** - Nothing is fetched before: the PLMN-digits `From`, D4 Allow, a host on the SIM's MCC/MNC row
  (strict URI: printable ASCII, http/https, no userinfo, ports -1/80/443/8080; unknown operator fails closed), and
  the size. The host is checked again at every drain. The second From check is right (normalised, PLMN, null fails).
  The capped pipe is right ("w" matches AOSP). Redirects: N2. The sender isn't re-checked after the first judge: M2.
- **Debug hook** - `MmsDebugReceiver` and the live `MmsDebug` are only in `src/debug`. The release twin returns
  `false`/empty and adds no `var` (`DebugHookAbsentTest`). `checkReleaseHasNoDebugHook` greps the merged manifest and
  APK for `MmsDebugReceiver` and `push_hex`. It is exported only under `android.permission.DUMP`
  (signature|privileged|development): `adb shell` has it, and no app can get it without `pm grant` over adb. So on a
  debug build nothing but adb can trigger it, and debug builds never go on the kid's phone (house rule).
- **FileProvider paths** - `MmsFileProvider`: two path segments, ULID / 32-hex names, mode `w` only for `in/`, `r` only
  for `out/`; no traversal (decoded segments must match the regexes). Not exported; the telephony broker grants the
  one URI, and we revoke on the result. Share provider: `capture/` added beside `share/`, one random file per capture,
  granted only to the chosen camera package (READ is extra: N5). The picker's returned URI: M3.
- **Camera / photo picker vs kiosk** - `PicturePick.kt` is the only file allowed to name them
  (`KidScreensEscapeTest`). The picker is the lock-task helper, and the camera must be usable and allowed. N9 for time
  rules.
- **Held MMS** - Only the ≤2 KB push is kept, nothing is fetched, and the 30/10 caps apply. A release requires rule 5's
  exact number (`SmsRules.releases`, never an emergency number) after drops and expiry. `judgePush` re-runs D10, and
  the From check runs after the fetch. L9 (reported "released" too early).
- **Groups** - Every To/Cc address must be a PLMN number that is an own number, the sender, or a contact with a text
  switch. With no own number, more than one recipient blocks. A non-PLMN recipient blocks. The sender must pass rule
  5. Replies go to the stored participants only, and every one must pass D5 (`sms_out`). No way found to get a
  stranger's content in via To/Cc. Own numbers matter only as "skip"; a wrong own number fails closed.
- **Storage** - `secure_delete` is set and read back (`secureDeleteOn`). Parts and the `mms` row are deleted with the
  message in one transaction. Picture files are deleted when no part holds them (delete, conversation delete via
  per-message delete, drop). Unheld leftovers go after a day. Downloads (`no_backup/mms/in`) are deleted after the
  parse; a crashed one stays until its day-old prune. Shared storage gets a picture only through "Lagre"
  (`PictureShare`'s `MediaStore` insert, the only one in the code).

## Correctness

- **Push-first state machine** - Each step commits before the next side effect: `DOWNLOADING` with its try before the
  platform call, `DOWNLOADED` before the parse, and a released push back in `pending_push` in the same transaction as
  `RELEASED`. A process death resumes correctly, except for the main-process death during the parse (M1).
- **Retries and D14** - Five tries, then the kid's tap. A data reconnect resets the count; each sync retries `WAITING`.
  A download silent for 15 minutes is failed. Sound, apart from L5 and L10.
- **Data listeners** - The first `DataConnectionState` report is ignored correctly (`was == null`). Registered once
  on the results executor.
- **Duplicate pushes** - Deduplicated inside the judging transaction for allowed, held and rule-blocked pushes. L2 covers
  early blocks and the window.
- **DB v1 to v2** - Inside `SQLiteOpenHelper`'s upgrade transaction: three `ALTER TABLE ... ADD COLUMN` with defaults,
  the new `mms` table, two indexes. `onCreate` runs v1 then the upgrade steps, so new and upgraded databases match. A
  step-1 `pending_push` row (no `held_id`, `tries` 0) is judged at the first pass, and an old allowed push is fetched
  if not expired. Correct by reading. Never run: the emulator run used `-wipe-data` (see Tests).

## Product rules

- en + nb: every new string is in both files (12 each). No vendor or carrier names in strings; the server's table
  doc uses a placeholder host. Fail-closed throughout (unknown operator, no own number, unknown rules), with no
  weaker fallback. No adb step for the user (the debug hook is emulator-only). The "MMS-meldinger" toggle is left
  for step 0 as an on-phone runbook line.
- `server/src/mmsc_hosts.rs`: the doc and `the_table_is_well_formed` match the phone's matcher (lower-case host, no
  scheme, port or path, 5-6 digit MCC/MNC, no duplicates). Not run here (no cargo run in this review; tests only).

## Tests

The tests that exist test behaviour, and test it well: `MmsEngineTest`'s 20 cases walk the real state machine over
the in-memory store, `MmsGateTest` covers the pure rules, and `MmsParseBoundaryTest` and `KidScreensEscapeTest` pin
the boundaries in source.

Missing cases:
1. M2: a contact whose `sms_in` goes off (or who is removed) between the push and the fetch, for `NOTIFIED`,
   `WAITING_DATA` and the kid's retry. Nothing may be fetched.
2. D12: a group from a callback-window sender (no rule 5) is dropped as `group`. This is deviation 9's rule, untested.
3. D16: a held MMS from an emergency number is never released, even after a contact with that number and `sms_in`
   appears.
4. L1: no `answer` for a message dropped by the From or group check (the From-mismatch test passes today without
   asserting `answers`).
5. M1: the aggregate bound and the pre-parse attempt counter, once added (pure parts in `MmsFlow`).
6. M3: `pickedUriAcceptable` (file://, our own authorities, a foreign `content://`).
7. The wire: `MmsParseProtocol.readString`/`readCount` and `MmsParser.pixels`'s size check run only on Android
   `Parcel`, so they are untested. Put the reader behind a small interface (a `Reader` over `Parcel` or a test double)
   so that a length over the cap, a negative count and `w*h*4 != size` each have a JVM row.
8. `MmsFileProvider`: the path and mode predicate (`in/` only `w`, `out/` only `r`, bad names) as a pure function
   with a test. The over-cap stop is device-only.
9. The v1-to-v2 migration on step-1 data: one emulator run without `-wipe-data`. Install the step-2 build, receive an
   SMS and a stranger's push (`held`), install 3b, and check that the history, the held row and the push survive.
   Add it to `docs/testing/emulator.md` §6v.
10. `deleteConversation` deletes its pictures' files (it goes through `delete`, so it works today, but nothing pins it).

Run here (`testDebugUnitTest -PwarningsAsErrors=true`, targeted): `MmsEngineTest`, `MmsGateTest`,
`MmsParseBoundaryTest`, `DebugHookAbsentTest`, `KidScreensEscapeTest`, `MessagesModelTest`, `SmsEngineTest`: 92 tests,
0 failed. No emulator run, no cargo run.

## Fix round

On `sms-mms` after b06ac01d, by the builder. Every finding is fixed or answered; the missing tests are added.

### Medium

- **M1** - fixed three ways:
  - the message is bounded: `ParseWire.PictureBudget` (pure, `ParseWireTest`) - at most four pictures and 8 MP
    between them are decoded per message; past it a picture is "not shown". The service stops there, and the main
    process refuses an answer past it as out of protocol (`parseRetrieve`);
  - the main process streams: `MmsParser.Media.Picture` holds the pixels' descriptor, and `MmsRuntime.retrieved` makes,
    encodes and recycles one bitmap at a time, closing each descriptor;
  - the attempt is counted before the parse (`MmsFlow.beginParse` writes `crashes + 1`; a fourth start gives up as
    "Meldingen kunne ikke vises" and counts `parse_failures`; `parseUnavailable` gives an unbindable service's attempt
    back). A main-process death now ends after three tries. Test: `the parse's attempt counts before it runs`.
- **M2** - fixed: `MmsFlow.senderNow` judges the sender by D4 as of now in `drainDownloads` (before `DOWNLOADING` is
  committed), in `parseJobs` (before the parse) and in `onRetrieved` (at its answer). Not allowed: the message is
  dropped as a blocked `rule` row (`MessageDeleted`, its files, no answer); rules that can't be read: it waits. A
  callback-window admission thus ends with the window (D4 is judged again; no stored basis needed). Tests: `a contact
  switched off before the fetch`, `the kid's tap and a fetched file are judged again too`, `a stranger the callback
  window let in isn't fetched once the window closed, nor in a group`.
- **M3** - fixed both ways: `PicturePick.pickIntent` sends `ACTION_PICK_IMAGES` to the system's own picker package
  (`MATCH_SYSTEM_ONLY` and `FLAG_SYSTEM`; none = "Bilder kan ikke sendes akkurat nå"), and the answer is read only when
  `pickedUriAcceptable` (pure, `MessagesModelTest`): `content://`, an authority (user-qualified ones by their provider
  part) that is neither our package nor `<package>.*`. The camera's capture is `PicturePick.Source.Captured(file)` and
  read as that file, never via `Uri.fromFile`. `KidScreensEscapeTest` pins it.

### Low

- **L1** - fixed: `answer` runs after the sender, From and group checks, for a kept message only. The From-mismatch
  test asserts no answer.
- **L2** - fixed: an immediate fetch (first try, not released) answers with m-notifyresp-ind "retrieved" and the
  notification's transaction id; a deferred one (released, waited or failed - `MmsRow.deferred`) with an
  m-acknowledge-ind. A push's key is kept until its `X-Mms-Expiry`, at least a day and at most a week
  (`PUSH_KEY_KEEP_MS`), and remembered by `blockPush` too (an unparseable push by its bytes' hash). Test: `repeated
  pushes are known until their own expiry, blocked ones too`. Step 0 confirms each carrier.
- **L3** - fixed: `bindIsolatedService` with a fresh instance name per call (`MmsParseBoundaryTest`).
- **L4** - fixed: the service hands the region's descriptor alone; the main process reads exactly `width * height * 4`
  bytes with `Os.pread` (a short read is out of protocol) and builds the bitmap from a heap buffer. Nothing is mapped.
- **L5** - fixed: past the cap the provider's reader leaves `in/<id>.over`; `onDownloadResult` then moves the message
  to `TOO_LARGE` (final), whatever the platform answered. Test: `a download past the cap is too large for good`.
- **L6** - fixed: an unbindable service ends the pass, counts `mms_parse_unavailable` and writes `sms_state.last_error`
  ("the parse service can't be reached - MMS wait"), so the parent's page shows it. Nothing is lost: pushes and fetched
  files wait (the parse attempt is given back).
- **L7** - fixed as far as an app can: `mms_data_allowed` is mobile data on, or the last fetch that ran with mobile
  data off worked (`SmsPrefs.mmsWithoutData`, cleared by a fetch that failed for want of data with data off).
  `isDataEnabledForApn(TYPE_MMS)` and `isMobileDataPolicyEnabled(MMS_ALWAYS_ALLOWED)` are system APIs (checked against
  android-36's `android.jar`). On step 0's list: the "MMS-meldinger" toggle, and what the phone reports with it.
- **L8** - fixed: `onRetrieved` always returns the arrived message, with `again` (it was told of before - notified
  quietly) and `movedFrom` (the sender's conversation, whose notification is cancelled). Test: `a picture that waited is
  told again quietly ...`.
- **L9** - fixed: `releasePass` gives a held MMS's push back to `pending_push` and keeps the row `held` (push gone);
  `judgePush` settles it: `released` once shown (a fetch that then fails is still shown), `dropped` when blocked or held
  again. Expiry skips a row on its way back; a parent's delete after the allow wins (the push is dropped unfetched).
  Tests: the release tests, `the parent's delete after the allow wins`.
- **L10** - fixed: `retryWaiting` moves `FAILED/mmsc_unknown` back to `NOTIFIED` once `MmsGate.known` says the SIM is
  listed; it runs at each sync and at every rule change (`SmsRuntime`'s rules listener, then an MMS pass). Test: `an MMS
  that failed for an unknown operator is fetched once the table knows the SIM`.

### Nits

- **N1, N2** - named in the design's §8.6 (header forgery by a compromised parser, bounded by L3/L4/M1; redirects after
  the first hop, a step 0 check).
- **N3** - fixed: the send's size check and the pick's re-encode use the conversation's SIM.
- **N4** - fixed: 10 KB headroom.
- **N5** - fixed: write grant only.
- **N6** - said in `calls.md` (MMS fails closed in that state; SMS stays unfiltered).
- **N7** - fixed: `FAILURE_UNREADABLE` on the message, `PartView.Unreadable`, "Meldingen kunne ikke vises" / "The
  message couldn't be shown".
- **N8** - fixed: `MmsFlow` keeps the unsent picks (`staged`); `dropUnheld` and `prune` leave them. Test: `a pick the kid
  hasn't sent survives a delete ...`.
- **N9** - said in `time-rules.md`, beside step 2's share sheet.

### Deviations

- 3 and 8 are now in the design's §6.2 ("What a consumer of the events must know"); 4 in D8 (one JPEG per picture,
  sampled for the thumbnail; the per-message bound); 7 in `calls.md`.

### The missing tests

1-6 and 10 are in `MmsEngineTest` (33 now), `MessagesModelTest` and `ParseWireTest`; 7 (the wire) and 8 (the provider's
routes, `MmsFileRoutes`) in `ParseWireTest`; 9 (the v1-to-v2 migration on step-1/2 data) is an emulator run, below.
