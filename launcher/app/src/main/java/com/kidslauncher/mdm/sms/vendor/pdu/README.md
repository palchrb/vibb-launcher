# The vendored MMS PDU codec (design 77 D9, §8.1)

**Source:** AOSP Messaging, `src/com/android/messaging/mmslib/pdu/` and `mmslib/{InvalidHeaderValueException,MmsException}.java`
at commit `de315b762312dd1a5d2bbd16e62ef2bd123f61e5` (https://android.googlesource.com/platform/packages/apps/Messaging),
Apache License 2.0, Copyright (C) 2007-2008 Esmertec AG and The Android Open Source Project. The text of the licence is
in `launcher/app/src/main/assets/licenses/Apache-2.0.txt`; the repository's `NOTICE.md` names this copy. Each vendored
file keeps its header and says "Modified for Vibb One".

**Kept Java** so upstream fixes stay diffable: compare a file with upstream after replacing
`package com.kidslauncher.mdm.sms.vendor.pdu;` by upstream's package. The Kotlin entry point is
`sms/mms/MmsCodec.kt`; nothing else calls this package.

**Not vendored:** `PduPersister` (the Telephony provider writer), `DeliveryInd`, `ReadOrigInd`, `ReadRecInd`, `Base64`,
`QuotedPrintable`, `mmslib/util/*` (cache, DRM), `Downloads`, `SqliteWrapper`.

**Written here** (GPL-3.0-or-later, not from upstream): `Log`, `SparseArray`, `SimpleArrayMap`, `TextUtils` (plain-Java
stand-ins with the Android names, so the vendored files keep their lines; `Log` is silent: no PDU content in logcat),
`MalformedPduException`. `ContentType.java` is three constants copied from AOSP Messaging's
`com.android.messaging.util.ContentType`.

## Pure JVM

- `android.util.Log`, `SparseArray`, `TextUtils` and androidx `SimpleArrayMap` swapped for the stand-ins above.
- `PduPart` lost its `android.net.Uri` (`setDataUri`/`getDataUri`): parts carry their bytes.
- `PduComposer` lost `Context`/`ContentResolver`: a part without bytes is a compose error. An empty Text-string no
  longer throws (`text[0]`).

## Hardening of `PduParser` (each marked `Vibb One (Hn)` in the code, each with a test in `PduHardeningTest`)

- **H1** The input must be within a cap before anything is parsed: the carrier's maximum, else 1 MB
  (`DEFAULT_MAX_PDU_BYTES`; `MmsCodec` adds 2 KB for a push, 8 KB for a send-conf, 4 MB as a ceiling).
- **H2** The end of input inside a value is `TRUNCATED`. Upstream read `-1` as `0xFF` behind `assert`s that are off on
  Android (and on in JVM tests); every such `assert` went. A string still ends at the end of input, as upstream.
- **H3** `parseUnsignedInt` (uintvar) reads at most 5 octets and the value must fit a non-negative `int`; upstream
  overflowed and returned `-1` at the end of input, which callers used as a length.
- **H4** Every Value-length is checked against the remaining bytes (`parseValueLength`), so every length-driven read
  and skip is bounded. Covers the platform's 2026 `parseContentType` length check.
- **H5** A part's header and data lengths are checked against the remaining bytes before `new byte[dataLength]`
  (the review's AM `PduParser.java:931`), and the data is read in full or `TRUNCATED`.
- **H6** `skipWapValue` passes over the bytes without copying them (upstream read them into `new byte[length]`), and so
  does the Content-Disposition's "skip other parameters" (`new byte[last]` of an attacker's length). Rewritten here as
  a bounds check and one `skip` (QA L5).
- **H7** The well-known content-type index is bounded; an unknown one is `*/*` (the review's `:1594`).
- **H8** `multipart/alternative` nests at most `MAX_DEPTH` = 1 level, and a child body that fails or is empty ends the
  parse (`BAD_STRUCTURE`) instead of dereferencing null (the review's `:936-938`).
- **H9** At most `MAX_PARTS` = 20 parts per body (the count is judged before any part is read) and
  `MAX_HEADER_FIELDS` = 300 header fields per PDU, the PDU's and its parts' together.
- **H10** Null dereferences on empty values: a charset parameter as an empty Text-string, the Element-Descriptor's
  type/charset parameter put into a null map, an empty Content-Disposition token, an empty filename, an untyped part
  header with an empty name or value.
- **H11** The Content-Type parameter loop ends after a skip that fails (upstream kept looping, up to 2^31 times).
- **H12** The multipart `start`/`type` parameters are per parser, not `static` (one parse's value leaked into another
  running at the same time).
- **H13** Content-Transfer-Encoding is never decoded (base64 and quoted-printable not vendored). Upstream's untyped
  part-header parse drops the name's first octet, so a real carrier's header never matched: only a crafted PDU
  reached those decoders. The parts' bytes are as sent.
- **H14** Addresses are kept whole: From, To, Cc and Bcc keep `/TYPE=PLMN` (upstream cut at the first `/`), so D10 can
  require a PLMN sender.
- **H15** m-delivery-ind, m-read-orig-ind and m-read-rec-ind are not parsed (not needed; less surface).
- **H16** A `MalformedPduException` is not swallowed by the header parser's `catch (RuntimeException)` blocks, so its
  reason survives; a Long-integer over 8 octets is one too.
- **H17** `parse()` keeps upstream's null contract; `parseOrThrow()` gives the reason. Any other exception out of the
  parser is a bug: `PduFuzzTest` (a fixed seed, 20,000 mutations of the golden PDUs) fails on it.

### Fix round (docs/review/qa-sms-mms-codec.md)

- **L1** Malformed part structure throws `BAD_STRUCTURE`: a content type that overruns the part's header length, part
  headers that overrun theirs, a part with no bytes left. Upstream returned null, which read as "not accepted".
- **L8** A value with a Value-length ends where its length says (`valueEnd`/`endValue`): From, the time headers,
  Previously-sent-by/-date, MM-flags, the Mbox totals and an Encoded-string-value in the charset form. Octets the parse
  left inside the value are passed over, never read as the next header; a parse that ran past the length is
  `BAD_LENGTH`. This closes the parser's half of M1 (a NUL inside a subject or address can no longer smuggle a header);
  a plain Text-string has no length to bound it, so `MmsCodec` refuses a NUL in what it composes (M1).
- **N2** No `printStackTrace` (on Android `System.err` is logcat).
- M1, L2, L3, N3 and N5 are in `MmsCodec`; L4, L7 and N1 in the tests.

GrapheneOS Messaging (51c07da3, 2026-06 commits a6e2daee, 4a938177, 674301e3) fixed H5, H6, H7 and H11 in its copy and
added tests for them. Its repository has no top-level licence, so none of its code is copied: the fixes above are our
own, and its tests' inputs are re-run in `PduHardeningTest` as a checklist.

## Each Android security bulletin

When this package is touched, check the bulletin's telephony and Messaging entries against it (design 77 §12). Checked at
vendoring (2026-11-09): the platform's `frameworks/base` `PduParser.java` on main, whose one parser change since AM's copy
is the `parseContentType` length check (H4); the 2026 CVEs in design 77 §2.3 name `parseParts` (H5), `parseContentType`
(H4, H7) and denial of service in the parser (H2-H11).

## For step 3b

- **D10 on `MmsAddress`:** use `plmnNumber` (non-null only for exactly `+`/ASCII digits with `type == "PLMN"`), not
  `type` and `value` with `Char.isDigit()`, which also takes Arabic-Indic and fullwidth digits an address in the
  charset form can carry.
- **`contentLocation`** can hold CR, LF, TAB (the parser's `isText` keeps them) and latin-1 octets 0x80-0xFF. Parse it
  with `java.net.URI` and refuse any whitespace before D10's host check.
- **`MmsCodec.parse*` catches `Throwable`, OutOfMemoryError included.** Right in the isolated process (D8), wrong
  anywhere else: add a test or lint that only `sms/parse/` calls `parseNotification` and `parseRetrieveConf`.
- **A top-level `multipart/alternative`** returns every alternative (upstream's dead branch, QA N4): show the first
  `text/plain` only.
- **Caps that matter to the Binder hand-over** are in `MmsCodec` (`MAX_ADDRESS_CHARS`, `MAX_ADDRESSES`,
  `MAX_SUBJECT_CHARS`, `MAX_CONTENT_LOCATION_CHARS`, `MAX_ID_CHARS`, `MAX_TEXT_CHARS`); over them a parse is `TOO_LONG`.
- **Compose:** `composeNotifyResp` and `composeAcknowledge` return `MmsComposeResult` and refuse a transaction id
  outside `[\x21-\x7E]{1,64}`; a notification whose id fails that can't be answered, so treat it as malformed.

**Done in step 3b** (branch `sms-mms`, `docs/notes/launcher/calls.md` "MMS pictures, step 3b"): D10's sender is
`MmsGate.sender` on `plmnNumber`; the content location goes through `MmsGate.location` (printable ASCII without
whitespace, `java.net.URI`, scheme, userinfo, port, the SIM's host); only `sms/parse/MmsParseService` calls the
parsers (`MmsParseBoundaryTest`); a top-level `multipart/alternative` gives its first `text/plain` (the service's
`texts`); a notification whose transaction id can't be answered is blocked as malformed (`MmsGate.answerableId`).
