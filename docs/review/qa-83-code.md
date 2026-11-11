# Code review: design 83 (the MMSC host list without a hand-kept table)

Reviewed 2026-11-10: `1b94ee09..aae5ca72` on `mmsc-hosts`, by reading, one full launcher unit-test run and 12 launcher
mutations. The server was reviewed by reading: its test crate cannot be compiled on the build machine tonight (rustc is
killed for memory, on main too), and its suite ran on GitHub for exactly aae5ca72 (Server CI run 42917485467: fmt,
1864 tests, `clippy --all-targets`, all green). Nothing was run on an emulator or a phone. The design is
`docs/design/83-mmsc-hosts.md`.

## Verdict

Merge after fixes. No High: no way was found to make the phone fetch from an address that is not on the SIM's row, and
nothing the phone reports reaches the parent's page unbounded, unescaped or clickable. The fixes are two missing
launcher tests on the security rule (both mutations survived), a gap in step 0's text that can give a false pass on
the gate, and one untested purge guard.

The full launcher run at aae5ca72: `BUILD SUCCESSFUL`, 2447 tests, 0 failures, 0 errors, 0 skipped.

## Findings

**M1 - Medium. The last check before the fetch has no test.** `MmsFlow.drainDownloads`' re-check is the only gate for
a row that returns to `NOTIFIED` without a location check: `retryWaiting` moves `WAITING_DATA` and `RETRY` rows back
unchecked. After a parent's "Fjern", a replaced policy or a SIM swap between push and fetch, only that line stops the
fetch. Mutation m5 (`location !is Ok` to `location is Bad`) survived.

**M2 - Medium. "Never a suffix" is held by no test.** `MmsGateTest`'s case is a listed name as a prefix; a real
suffix match (`endsWith`) survived as mutation m2, and with it a listed `10.0.0.9` would admit `110.0.0.9`.

**M3 - Medium. Step 0's check (b) can pass falsely as written** (`docs/design/77-sms-mms-client.md`, the same text in
design 83, `docs/testing/emulator.md` §6v "Not on the AVD"). "No request arrives at `fake-mmsc.py` on Wi-Fi" is also
the result when the address is not on the SIM's row, because our own gate keeps the picture and the platform is never
asked; and with §6v's `--es stand_in` on, our own code fetches over Wi-Fi and (b) "fails". The text states two
outcomes, not steps. Read, not run.

**M4 - Medium. The 30-day purge's `state = 'open'` guard has no test** (`server/src/retention.rs`;
`tests/cleanup.rs` expects `mmsc_hosts: 0` with no row seeded). Without the guard a parent's allowed address leaves
30 days after its last report and a declined one is asked about again. Read.

**L1 - Low.** `server/CLAUDE.md` words the caps wrongly: the code has 4 open per phone, 8 allowed per phone and
MCC/MNC, 32 declined per phone.

**L2 - Low. Three more server rules without a test:** the `DECLINED_MAX` trim, `open_count` in the device page's
"N meldinger venter", and deletion with the device (the foreign key's cascade alone, no test naming the table).

**L3 - Low. A trailing dot on a single-label host.** `http://mm./x` becomes entry `mm`, but the platform resolves
`mm.` as an absolute name, another lookup than `mm`. Matters only once a single-label entry is unasked or allowed.

**L4 - Low. A parent can be asked about `127.0.0.1` or `0.0.0.0`.** Harmless to fetch from, an odd question to put.

**L5 - Low, accepted. Two parents answering differently: the last answer wins, both ways.** A stale "Tillat" on a row
the other parent just declined stores `allowed`. An explicit action on the exact target, and "Bildeadresser" shows who
and when.

**L6 - Low. Design 83 has never run end to end.** `emulator.md` §6v says "Not run yet". Its "parent's allow"
scenarios are to run before a release carries this.

**L7 - Low. Wording.** nb `device.calls.mmsc.notice` "Denne adressen er allerede besvart." (an address is not
"besvart"); a decided row whose deciding account is gone reads "… av -".

**L8 - accepted by the design (§4).** A declined address still gives the kid one "Bildet kunne ikke hentes" bubble per
crafted push from a spoofed contact number; four newer spoofed addresses can hold the report's four slots. Neither
fetches anything.

**L9 - not verified.** How a 253-character host wraps in the question row on a narrow screen.

## Tried against the gate

All `Bad`: userinfo, backslash, `%`, underscore, IPv6 literals, `010.0.0.9`, `2130706433`, `0x7f000001`, five-octet
forms, port 0 or oversized, an empty host, non-ASCII or IDN, whitespace or CRLF. Case is folded and the entry matched
exactly; another port needs its own `host:port` entry. The kid's tap cannot reach an `mmsc_unknown` row. A held push
released later is re-judged at admit. An allow is per phone and MCC/MNC; the policy carries rows only for the SIMs in
the newest report; hints are never sent. Both mixed-version directions fail closed. Only the validated entry reaches
the server, never the path or query. The page prints every value escaped, with no link; the push and the event carry
names, never the address. The two POST routes sit inside the admin router's CSRF and full-auth layers; an answer is one
`BEGIN IMMEDIATE` transaction scoped to the device. Both `sms_contract.json` copies are byte-identical.

## The implementer's deviations

All judged right or acceptable: where each form lands (every case on an anchor that exists); a stale "Ikke tillat"
declines; the count row's link (could point at `#mmsc` when nothing is held); the nudge on changed operators; the host
normalisation (apart from L3); the phone's own bounds; `bad_location` as a shape failure only - the production table
was empty before, so nothing refused before can be fetched now, and there is no new kid-facing string.

## The generator

`--selftest` passes; the pinned AOSP file's SHA-256 matches; regenerating gives exactly the committed
`server/testdata/mmsc_hosts.txt` (`UNASKED = "none"`, every entry a hint); standard library only, runs with `-I`; the
`NOTICE.md` entry is there; `server-ci.yml` runs the self-test in the always-run job.

## Mutations

Launcher, run:

| # | Broken | Result |
|---|---|---|
| m1 | userinfo check dropped | caught, `MmsGateTest`, `MmsEngineTest` |
| m2 | suffix match instead of exact | **survived** (M2) |
| m3 | port ignored in the entry | caught |
| m4 | an octet with a leading zero accepted | caught |
| m5 | the pre-fetch re-check lets `Unlisted` through | **survived** (M1) |
| m6 | `retryWaiting` back to "the SIM has any entry" | caught |
| m7 | the report without the 7-day cut | caught |
| m8 | the report names non-contacts | caught |
| m9 | the report not cut to 4 | caught |
| m10 | `validEntry` not called in `location` | caught |
| m11 | the sync asked for non-contacts | caught |
| m12 | admit and pre-fetch both let `Unlisted` through | caught |

Server, by reading: no test for the purge guard (M4), the declined trim and `open_count` (L2); the policy, the intake,
the caps, the device scope and the redirects each have a test that would fail.

## Only a real phone or SIM can settle

Which network the platform fetches on and that Wi-Fi is never used (step 0 a and b); whether the platform's MMS client
follows redirects off the allowed host (only the first hop is checked); what real operators' notifications name
against the list's hints; `getSimOperator()` on MVNOs and dual SIM, a push with no `subId`, reports while the SIM is
not ready; the page with a long host on a narrow screen.

## Fixes

The fix round, 2026-11-10, three commits on aae5ca72 and this file's own.

| Finding | Commit | What changed |
|---|---|---|
| M1 | d3d78b71 | `MmsEngineTest`, "a waiting picture whose address left the SIM's row is kept at the fetch, not fetched": a picture waiting for data, its entry taken off the row and then the SIM's operator changed; `retryWaiting(dataOn = true)` and `drainDownloads()` download nothing and the row is `FAILED`/`mmsc_unknown`. |
| M2 | d3d78b71 | `MmsGateTest`: `evil.mmsc.carrier.invalid`, `110.0.0.9` (with `10.0.0.9` listed) and `xmmsc` (with `mmsc` listed) are each `Unlisted`. |
| M3 | ffdba6f3 | Design 77 §9 step 0, "Design 83's gate, step by step": no stand-in, the address on the SIM's row first, the push made with `fake-mmsc.py push` and `--es push_hex`, the control that the phone reaches the fake on Wi-Fi, the three outcomes told apart by `MmsRuntime`'s "A download failed" line, mobile data on and off, and the file the result goes in. Design 83 and `emulator.md` §6v point at it. |
| M4 | 34afc912 | `tests::cleanup::pruning_keeps_only_what_retention_allows` seeds an open, an allowed and a declined row 31 days old and a fresh open one: `mmsc_hosts: 1`, the other three left. |
| L1 | ffdba6f3 | `server/CLAUDE.md`: 4 open per phone, 8 allowed per phone and MCC/MNC, 32 declined per phone. |
| L2 | 34afc912 | `tests::mmsc_hosts::the_oldest_decline_goes_past_the_cap` (new); `allow_decline_and_take_back` reads the device page's "2 messages waiting"; `tests::lock_glance`'s device delete counts `device_mmsc_hosts`. |
| L3 | d3d78b71 | `MmsGate.location`: a single label with a trailing dot is `Bad`; a name of several labels keeps the trim. Vector `http://mmsc./x`. |
| L4 | d3d78b71 | `MmsGate.validEntry` and `mmsc_hosts::valid_entry` refuse four octets whose first is 0 or 127; two rows in `mmsc_entries` (`127.0.0.1`, `0.0.0.0:8002`), both copies. |
| L5 | - | No change (accepted). |
| L6 | - | No work in this round: still not run end to end. |
| L7 | 34afc912 | nb "Noen har allerede svart på denne.", en "Someone has already answered this one."; a decided row with no account reads "Tillatt {date}" / "Allowed {date}" (`device.calls.mmsc.allowed_on`, chosen in the handler; the template is unchanged). One assertion in `the_ninth_allow_is_refused`. |
| L8 | - | No change (accepted by the design). |
| L9 | - | No change needed, by reading: the question's and "Bildeadresser"'s host are inside `.row-label`, which has `overflow-wrap: anywhere` and `min-width: 0`. Not seen on a screen. |
| The count row's link | 34afc912 | `calls_sms::summary_value` returns the anchor with the words: `#mmsc` when no message is held and a question is open, `#held` otherwise. One assertion in `allow_decline_and_take_back`. |

After the fixes: the launcher's full run is `BUILD SUCCESSFUL`, 2448 tests, 0 failures, 0 errors, 0 skipped. m2 is
caught by `MmsGateTest` ("the content location must be an entry of the SIM's list") and m5 by M1's test.
`cargo fmt --check` and `cargo clippy` on the binary are clean, the generator's `--selftest` passes and the two
contract copies are identical. The server's new and changed tests were written by reading and not run on the build
machine: Server CI proves them. Nothing was run on an emulator or a phone.
