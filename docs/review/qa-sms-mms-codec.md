# QA: design 77 step 3a, the vendored MMS PDU codec (branch `sms-mms-codec`, a353f363)

Security-focused code QA, 2026-11-09. Branch `qa-sms-mms-codec` holds this file and `PduQaTest.kt`.

## What was checked

- **The diff against upstream.** I diffed every vendored file against AOSP Messaging at `de315b76`, cloned read-only into
  a scratch directory and never built. Most files only gain the header note and the package name. The other changes
  are:
  - `PduPart`: the `Uri` removed;
  - `PduComposer`: no `Context`, an empty text allowed;
  - `PduParser`: 486 changed lines, read in full.
- **Inputs that drive the parser.** I traced every length, index, count and recursion that comes from the input:
  - `parseUnsignedInt`, `parseValueLength`, `parseLongInteger`;
  - `parseParts` (both length checks, the count, the alternative recursion), `parseContentType`(`Params`),
    `parsePartHeaders`, `skipWapValue`;
  - every header case in `parseHeaders`.
- **Mutation check of the fuzz test.** I removed H5's two length checks and ran `PduFuzzTest`. It failed at iteration
  243 ("allocated 805318920 bytes for 242"). So the allocation assertion is real, and the fuzzer does reach the part
  lengths. A second mutation (removing H7's bound) was not run: an emulator came up and the build lock rule applies.
- **Worst cases at the 4 MB ceiling,** each measured once in a throwaway test, not committed:

  | Input | Time | Allocated |
  |---|---|---|
  | one 4 MB text header | 36 ms | 13 MB |
  | 290 To addresses of 13 KB | 27 ms | 22 MB |
  | 20 multipart/alternative parts of 20 children | 1 ms | 8 MB |
  | one 4 MB part | 1 ms | 4 MB |
  | a 3 MB flood of `name` parameters | 57 ms | 92 MB, short-lived garbage |
  | 20 Content-Dispositions with 190 KB skipped | under 1 ms | |

  Nothing is superlinear, and nothing keeps more than a small multiple of the input.
- **GrapheneOS Messaging** (HEAD `51c07da`) was cloned read-only. It still has no LICENSE file. I compared its
  `PduParser` changes with ours line by line.
- **The test run:**

  ```
  testDebugUnitTest --tests '*MmsCodec*' --tests '*PduHardening*' --tests '*PduFuzz*' --tests '*PduQa*'
  ```

  - 32 tests ran. The 30 on the branch pass; `PduFuzzTest` printed "20000 inputs in 0.40 s, slowest 2.2 ms".
  - The two `PduQaTest` M1 rows fail, as intended. They prove M1.

**Verdict:** no unbounded path was found.
- **Allocation:** every allocation the input can size is checked against what remains, or is skipped.
- **Loops:** each loop consumes at least one octet per pass, or is capped by 20 parts or 300 header fields.
- **Recursion:** it is only `parseParts`, at most one level.
- **Size caps:** 1 MB, 2 KB, 8 KB and the 4 MB ceiling are enforced twice, in `MmsCodec.parse` and in `parseOrThrow`.
- **Exceptions:** nothing escapes the parse API. `MmsCodec.parse` catches `Throwable`, and the fuzz test fails on
  `INTERNAL`.
- **H14 holds.** From, To and Cc keep `/TYPE=PLMN` with and without a charset, on both the notification and the
  retrieve-conf. An insert-address-token is no sender on both. `PduQaTest`'s two H14 rows show this, and they pass.
- **The one real defect is in the composer** (M1).

## Critical

None.

## High

None.

## Medium

### M1 - Header injection through `composeSendReq`'s `subject` and `transactionId` (proven by `PduQaTest`)

**Evidence:**
- `MmsCodec.kt:132` and `:134` pass the caller's strings straight into the PDU. The composer writes a Text-string as
  the octets followed by End-of-string. It does not check them for a NUL (`PduComposer.java:314` ff.,
  `appendTextString`).
- A subject `"Hei\u0000\u0081+4700000099"` becomes `... 'H' 'e' 'i' 00 C2 81 '+' '4' ... 00`. A reader that ends the
  string at the NUL then sees:
  - `C2`: an unknown field, skipped;
  - `81`: **Bcc** `+4700000099`.
- Our own parser is such a reader: `PduParser.java:1155` reads the text to the NUL and ignores the Value-length.
- `PduQaTest` "M1 a NUL in the subject …" composes this, parses it back and gets
  `bcc = [+4700000099]`.
- A transaction id `"T\u0000\u0081+4700000099"` does the same (ISO-8859-1 makes `\u0081` the raw octet 0x81). See
  "M1 a NUL in the transaction id …".

**Impact:** an extra recipient (Bcc, To or Cc) or any other header can be smuggled into the send-req. That is a way
around "the kid may only text contacts with `sms_out`" (D11, D12). It works whenever an MMSC parses the way AOSP does.
- The design has no subject in the compose UI today, so this is latent.
- But the API is the boundary step 3b relies on. A later "Re: subject", a group name, or an engine-made id built from
  received text would open it.
- `composeNotifyResp` and `composeAcknowledge` (`:151-156`) take a `transactionId` the same way. Today that id comes
  from our parser, which can't produce a NUL (`getWapString` stops at one), so those two are safe by accident, not by
  contract.

**Fix:** validate in `MmsCodec` and return a typed failure.
- `subject`: refuse any char below U+0020 and U+007F (new `MmsComposeFailure.BAD_SUBJECT`), or strip them. Also cap
  it, for example at 40 characters, as many MMSCs truncate.
- `transactionId`, in `composeSendReq`, `composeNotifyResp` and `composeAcknowledge`: require
  `[\x21-\x7E]{1,64}`. Refuse anything else (`BAD_TRANSACTION_ID`, or `null` for the two that return `ByteArray?`).
- Then `PduQaTest`'s two M1 rows pass as written: they accept either a refusal or a PDU with no Bcc.

## Low

### L1 - `REJECTED` also covers malformed structure, which the typed failures say is something else

**Evidence:**
- These paths all return `null` from the parser, which `MmsCodec.parse` maps to `REJECTED`:
  - a part whose content-type overruns its header length (`PduParser.java:942-945`);
  - a part-header parse that fails (`:938-941`, `:1836-1838`, `:1864-1866`, `:1872-1874`);
  - a part with no bytes left (`:908-911`);
  - an octet header with an out-of-range value (`:337-344`, `:384-391`).
- `MmsParseFailure.REJECTED`'s KDoc says "Well-formed, but not a PDU we accept" (`MmsCodec.kt:270`).
- `MalformedPduException.Reason.BAD_STRUCTURE` documents "a part header that overruns", but nothing throws it for that
  case.

**Fix:** in `parseParts`, throw `MalformedPduException(BAD_STRUCTURE, …)` for the overrun and for a failed
`parsePartHeaders`. Or reword both KDocs, so that the count D8 reports ("malformed" vs "not accepted") means what it
says.

### L2 - Header strings have no length or count bound beyond the input; D8's IPC carries them over Binder

**Evidence:**
- An address, the subject, the content location or the transaction id can each be up to the input's size (4 MB at the
  ceiling).
- A retrieve-conf can list up to about 290 To/Cc addresses, under the 300-field cap. The throwaway measurement parsed
  290 addresses of 13 KB each into `MmsMessage.to`.
- D8 bounds text parts (5,000 chars), pictures and the part count. It bounds no header string, and a Binder
  transaction is about 1 MB.
- The outcome fails closed (the message "couldn't be shown"), but the D12 judging and the blocked row would carry
  megabytes.

**Fix:** cap in `MmsCodec`, before the values leave it. Anything over a cap is `REJECTED`, or a new `TOO_LONG`.
- each address: at most 128 chars, and at most `MAX_RECIPIENTS * 2` addresses in to+cc;
- subject: at most 1,000 chars;
- content location: at most 1,024;
- transaction id and message id: at most 256 each.

### L3 - A carrier maximum of 0 or below makes every message `TOO_LARGE`

**Evidence:** `MmsCodec.kt:73` and `:127` use `maxBytes.coerceIn(1, MAX_PDU_BYTES_CEILING)`. A carrier config that
answers 0 or -1 for `MMS_CONFIG_MAX_MESSAGE_SIZE` gives a 1-byte cap.

**Fix:** `if (maxBytes <= 0) DEFAULT_MAX_PDU_BYTES else maxBytes.coerceAtMost(MAX_PDU_BYTES_CEILING)`, in both places.

### L4 - The fuzz seeds miss several of the paths the API parses

**Evidence:** the seeds (`PduFuzzTest.kt:16-40`) contain:
- no Content-Disposition part header (H6, H10);
- no Encoded-string-value in the charset form (the subject and addresses on a real carrier, and the H14 path);
- no Cc;
- no retrieve-conf with an error status (the no-body path);
- no Element-Descriptor;
- no notification with a subject.

The mutator rarely synthesises these: it would need byte 0xC5 at a part-header position followed by a valid length.
The time bound (2 s per input, `:111`) is three orders above the slowest real input (2.2 ms), so it only catches a hang.
That is fine, but it is not a performance check.

**Fix:**
- Add two seeds:
  - a retrieve-conf with a charset-form From, To, Cc and Subject, and parts with Content-Disposition (filename and
    extra parameters);
  - a notification with a charset-form subject and an Element-Descriptor.
- Optionally add `MmsCodec.parseRetrieveConf(PduVectors.retrieveConf().copyOf(0))`-style edge seeds.
- The run costs 0.4 s, so 20,000 inputs per seed (not in total) is affordable.

### L5 - `skipWapValue` matches GrapheneOS's version line for line, comment included

**Evidence:**
- `PduParser.java:1429-1434` is identical to GrapheneOS's `skipWapValue` body, down to the reworded comment "The
  actually skipped length is lower than the length".
- Upstream's comment said "read". The README says "none of its code is copied".
- Five lines of the only natural way to write it are de minimis. The other matches are idioms already in upstream (the
  `index < contentTypes.length` bound, `//"*/*"`).

**Fix:** reword the comment and the shape, for example
`return (pduDataStream.skip(length) == length) ? length : -1;` after the bounds check. That keeps the README's claim
literally true.

### L6 - The in-app licence screen doesn't name the vendored codec

**Evidence:**
- The Apache-2.0 text is shown, and `NOTICE.md:37-43` names the source. Upstream has no NOTICE file, so §4(d) needs
  nothing more.
- But `res/values/donottranslate.xml:90-96` lists Material Symbols as the Apache-2.0 component and not AOSP Messaging.
  The project's own practice (design 14) is to name each one.

**Fix:** add a `legal_info_aosp_messaging` entry before the Apache text: "MMS message format: AOSP Messaging's
mmslib/pdu, © 2007-2008 Esmertec AG and The Android Open Source Project, modified".

### L7 - The no-Android-import check misses fully qualified names and static imports

**Evidence:**
- `PduHardeningTest.kt:250` matches only `^import (android|androidx|com\.android)\.`.
- A fully qualified `android.text.TextUtils.isEmpty(…)` or an `import static android…` would pass it, and so would
  compile against `android.jar`.
- Today there are none: a grep finds only comments. And a reached stub would fail the JVM tests at run time.

**Fix:** after stripping comments, match `\b(android|androidx|com\.android)\.[a-z]` anywhere.

### L8 - Value-lengths of From, Expiry and Encoded-string-value are not used as boundaries

**Evidence:**
- From (`PduParser.java:578-586`), the time headers (`:535`) and Encoded-string-value (`:1150-1155`) check the length
  against the input (H4). But they then parse to the NUL or the token, not to the declared end. So a short declared
  length lets the following octets be read as new headers. M1 rides on this.
- This is upstream behaviour, and our parser is D10's only judge, so it is no D10 bypass.

**Fix (optional; keep the diff small):** in `parseEncodedStringValue` and the From case, after the inner parse, check
that the consumed octets equal the Value-length, else `BAD_LENGTH`. Re-run the fuzz test, since real carriers may pad.

## Nits

- **N1** - `PduFuzzTest.kt:83-88`: the seed sanity check accepts `parseRetrieveConf(seed) == UNEXPECTED_TYPE`, which
  any parseable PDU of another type satisfies. Assert each seed's own type instead: notification Ok, send-conf Ok, the
  send-req parsed as `SendReq`.
- **N2** - `printStackTrace()` is left at `EncodedStringValue.java:194`, `:217` and `PduComposer.java:938`. These are
  unreachable in practice and print no content. But on Android `System.err` goes to logcat, so drop them, which makes
  "the codec never logs" literal.
- **N3** - `MmsCodec.kt:160`: `MIME` accepts any `a/b`, so a caller could add `application/smil` or a multipart type as
  a part. D11 sends only JPEG and text, so an allow-list (`text/plain`, `image/jpeg`, `image/png`, `image/gif`, the
  types `fileName` knows) is harder to misuse.
- **N4** - `PduParser.java:259-266` is dead code, kept from upstream: the first branch already matched alternative. So
  a top-level `multipart/alternative` returns every alternative, a text/plain and a text/html one alike. Step 3b
  should show the first `text/plain` only.
- **N5** - `MmsPart.text()` (`MmsCodec.kt:357-362`) decodes the whole part (up to 4 MB) before D8's 5,000-character
  cap. A `text(maxChars)` that decodes at most `maxChars * 4` octets is cheaper and states the cap in the API.

## For step 3b (no change in 3a)

- **D10 on `MmsAddress`:**
  - compare `type == "PLMN"` exactly;
  - check `value` against `^\+?[0-9]+$` with ASCII digits. Kotlin's `Char.isDigit()` also accepts Arabic-Indic and
    fullwidth digits, which an address in the charset form can carry.
- **`contentLocation`:**
  - It can hold CR, LF and TAB (the parser's `isText` keeps them) and latin-1 octets 0x80-0xFF.
  - Parse it with `java.net.URI` and refuse any whitespace before the host check.
- **`MmsCodec.parse*` catches `Throwable`, OOM included.** That is right in the isolated process (D8) and wrong
  anywhere else. Add a test or lint in 3b that only `sms/parse/` calls `parseNotification` and `parseRetrieveConf`.

## Tests

- **Lean but real.** There is one crafted PDU per H, built byte by byte. The goldens are not made by the composer, so
  a bug the composer and parser share can't hide.
- **The fixtures are synthetic.** The numbers are `+4700000000`-style, which Norway doesn't assign. The hosts are
  `.invalid`, and the JPEG is made up.
- **The fuzz test is deterministic and bounded.** It uses seeds 77 and 9, finishes in 0.4 s, and has a 120 s timeout.
  The parser's two `currentTimeMillis` reads (a relative expiry, the default Content-Location) don't change outcomes.
- **The allocation assertion is real:** the mutation check above shows it.
- **`PduQaTest.kt` (added on this branch):**
  - the H14 rows: From, To and Cc in the charset form, and an insert-address-token on a retrieve-conf. They pass.
  - the two M1 rows. They fail until M1 is fixed.

## Fix round (branch `sms-mms-codec`, after the merge of `qa-sms-mms-codec`)

| Finding | Fix |
|---|---|
| M1 | `composeSendReq` refuses a subject with any C0/C1 control character or DEL, or over 40 characters (`BAD_SUBJECT`), and a transaction id outside `[\x21-\x7E]{1,64}` (`BAD_TRANSACTION_ID`). `composeNotifyResp` and `composeAcknowledge` now return `MmsComposeResult` and refuse the same ids. Both `PduQaTest` M1 rows pass. New tests: `MmsCodecTest` "M1 …" (two). |
| L1 | `parseParts` throws `BAD_STRUCTURE` for a content type that overruns the part header length, part headers that overrun theirs, and a part with no bytes. `REJECTED`'s KDoc now says "a header value out of its range" too. Test: `PduHardeningTest` "L1 …". |
| L2 | `MmsCodec` caps what leaves it: each address 128 chars, To+Cc 40 addresses (counted before decoding), received subject 1,000, content location 1,024, ids, message class and a part's content type, name, location and id 256 each, text via `text()` 5,000. Over a cap is the new `MmsParseFailure.TOO_LONG`. Test: "L2 …". |
| L3 | Clamped, not refused: `MmsCodec.capOf` reads 0 or below as `DEFAULT_MAX_PDU_BYTES` (1 MB) and clamps above at the 4 MB ceiling, for both parse and compose. A carrier config without a value is the common case, so refusing would break every MMS. Test: "L3 …". |
| L4 | Three new seeds: a retrieve-conf with From, To, Cc and Subject in the charset form, a second Cc, a Content-Disposition with a filename and an extra parameter, and a charset given by name; a retrieve-conf with an error status and no body; a notification with a charset-form subject and an Element-Descriptor. The fuzz run is now 5,000 mutations per seed, 40,000 in all. |
| L5 | `skipWapValue` rewritten as one bounds check and one `skip` (`fits && skip == length ? length : -1`), its comment our own; the README says so. |
| L6 | The licence screen lists "AOSP Messaging" (`legal_info_aosp_messaging` in `donottranslate.xml`, shown in `legal_info.xml` before the Apache-2.0 text): the mmslib/pdu source, its copyright and "modified". |
| L7 | The no-Android check strips comments and then refuses `\b(android|androidx|com\.android)\.[a-z]` anywhere: fully qualified names and static imports included. |
| L8 | Done, as it closes the parser's half of M1: From, the time headers, Previously-sent-by/-date, MM-flags, the Mbox totals and a charset-form Encoded-string-value end where their Value-length says. Octets left inside the value are passed over (carriers that pad keep working); a parse that runs past it is `BAD_LENGTH`. A plain Text-string has no length, so the composer-side checks of M1 remain the guard there. Test: "L8 …" (a Cc smuggled after a NUL inside a subject is not read; a From that runs past its length is `BAD_LENGTH`). The fuzz run stays clean. |
| N1 | The seed check asserts each seed's own type (notification, retrieve-conf, send-conf, send-req). |
| N2 | The three `printStackTrace` calls are gone. |
| N3 | Sendable part types are an allow-list: `text/plain`, `image/jpeg`, `image/png`, `image/gif`. Test: "N3 …". |
| N4 | Left as upstream; written into the package README's "For step 3b" (show the first `text/plain` of a top-level alternative). |
| N5 | `MmsPart.text(maxChars = 5,000)` decodes at most `maxChars * 4` octets and drops a half character at the cut. Test: "N5 …". |
| 3b notes | In the package README under "For step 3b". The ASCII-only digit helper is `MmsAddress.plmnNumber` (non-null only for `+`/ASCII digits with `TYPE=PLMN`); test "the PLMN number …". |
