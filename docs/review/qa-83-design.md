# Design QA of design 83 - the MMSC host list

Reviewed: `docs/design/83-mmsc-hosts.md` at df00a9f6 (branch `design-83`), one round, 2026-11-10. Line numbers are the
note's. Read against design 77 (D4, D10, D16, §7, §8.6), `qa-77-design.md` H3, `server/src/mmsc_hosts.rs`,
`sms.rs`, `handlers/calls.rs`, `parent_events.rs`, the launcher's `MmsGate.kt`, `MmsFlow.kt`, `SmsRuntime.kt`,
`MmsRuntime.kt`, `MdmSyncWorker.kt`, the debug `MmsDebug.kt`, and `sms_contract.json`. Nothing was built or run. The
pinned APN file was downloaded and read with a throwaway script: the SHA-256, the size and every count in §1's table
are reproduced (3,667 / 1,256 / 1,230 / 821-150-259 / 862 / 454 hosts / 24 KB), and the Norwegian rows are as quoted.

## Verdict: go with these fixes

The direction holds: the check stays before any fetch, nothing new is fetched without a list entry or a parent's tap,
both mixed-version pairs fail closed, and the design still delivers if no operator's notification host equals its APN
host (then every family is asked - see M2). One claim the security argument stands on is wrong as written (H1) and
must be fixed in the note and the script before the build; the rest are small and can go into the build brief.

**Needs the owner's word:** H1 (which residual to accept). **For the owner to know, no decision:** M2 ("once per SIM"
is "once per address"), and the kid's-screen point under "Judged sound".

## High

**H1 - The generated hosts are not all "operator-run"; some are names an outsider can hold today** (lines 61-62, 69-70,
85-86, 218, 223-224).
- The note calls the set "operator-run hosts" three times, and the guard for a lapsed name is "the pinned commit and a
  reviewed diff at each refresh". The file is more than ten years of accumulated rows, and a refresh diff cannot show
  a name that is already claimable at the pinned commit.
- Read from the list: five hosts end in a label that was not a public TLD when the operator made the name up -
  `mms.media` (24006 and **24215 eRate, Norway**; no `mmsproxy` on either row, so the phone resolves the name itself),
  `mms.mobileuc.global` (23453, 313770), `mms.zain` (41902), `mmsc.axis` (51008), `mms.primetel` (28020). `.media` and
  `.global` are open public TLDs now; the other three can become one. Further rows name the domains of brands that
  have closed or been absorbed (`virginmms.fr` 20823, `jazztelmms.com` 21403/21421). Whether any of these is
  registered, and by whom, was **not checked** here.
- Why it matters: for such a row D10's promise is gone without any parent's tap. A crafted push naming a contact and
  that host is fetched (H3's outcome: a picture or text of the attacker's choosing in the contact's thread). It needs
  the kid's number, one contact's number, a SIM on that MCC/MNC, and an MMS network that resolves and reaches the
  public name - the last is unproven either way (step 0).
- Smallest fix:
  1. The script knows the last labels it accepts (the country codes and the old generic TLDs present today) and
     **fails the run on any other**; the five names above go on an explicit skip list in the script with the reason.
     A skipped host still works through the parent's allow.
  2. The same skip list is the remedy for a listed host later found claimable (a server release, as any fix).
  3. The note says "listed in AOSP's APN file for that MCC/MNC" instead of "operator-run", and §7's residual says
     plainly that it holds at the pinned commit already: 358 dotted names and 14 public addresses, nobody has checked
     their owners, an outsider who holds one is fetched from on that MCC/MNC with no question asked.
- **Owner's decision:** accept that residual (the approved direction, MMS works unasked on more SIMs), or the strict
  variant: only entries no outsider can claim (single-label names, private and CGNAT addresses) are trusted unasked,
  and a dotted public name from the list is shown in the prompt as "Vibb knows this address" but still needs the tap.
  The strict variant keeps Telenor, Telia, OneCall and ice unasked and asks on at least 524 of the 862 MCC/MNCs.

## Medium

**M1 - `mmsc_unlisted` does not "empty by itself", and a declined host can hold a report slot for good** (lines
117-121, 144-147, 180-181).
- "Not expired" reads the notification's own `X-Mms-Expiry`, which the sender of the push writes; absent, the row
  never expires (`MmsFlow.admit`: `expiresAt` null is never `GONE`). Such a row is reported for ever, so the 30-day
  purge never runs, or runs and the row is opened and pushed again.
- The phone sends at most 4 rows but cannot know which hosts the parent declined, and the note does not say which 4.
  Four declined hosts with long-lived rows can keep a real operator's host out of the report, so its prompt never
  appears. With the server's cap of 4 open rows, the same flood hides a real prompt until the parent declines the junk.
- Smallest fix: a row is reported only while it arrived within 7 days (`PUSH_KEY_KEEP_MS`), whatever its expiry says;
  the 4 are the hosts with the newest arrival; the note names what is left (someone who keeps sending crafted pushes
  naming a contact can keep a real prompt out and costs the parent four declines at a time - no fetch, no data).

**M2 - "Asked once per SIM" is not what the design gives** (lines 19, 26-27, 144-146, 253-255).
- The key is phone, MCC/MNC and **host**. An operator whose notifications name several hosts (a node name or address
  per region) asks once for each; from memory, unverified, T-Mobile US retrieval URLs carry a regional host name that
  is not its APN `mmsc`. Exact matching cannot fold them.
- What happens at the ninth host is not said: 8 allowed per MCC/MNC, then?
- If the APN host equals the notification host for no operator, every family is asked on its first picture and the
  generated set adds only hosts to trust. The design still works; the note should say that step 0's reading decides
  whether the generated set is kept.
- Smallest fix: "once per address; an operator with several asks for each, at most 8" in points 6 and 10 and in "For
  the user"; define the ninth (the prompt shows, "Tillat" is replaced by a line saying to remove one first).

**M3 - "Nudge, no prompt" can loop** (line 139).
- A reported host that is already generated or allowed nudges the phone. If that phone cannot take the new policy
  (a decode failure, a refused policy - `MdmSyncWorker` still posts its status), every report raises the next sync.
- Smallest fix: drop this nudge. The operator-change nudge (line 111-112) and the Calls page's own (line 174) cover
  the two real cases; a refreshed generated set reaches the phone at its next sync. Less code, no loop.

## Low

**L1 - The entry grammar leaves address forms open** (lines 57, 75-78, 93-95, 204-206).
- Three rows in the file are malformed and pass `[a-z0-9.-]` silently: `10.152.10.70.38090` (64602, a port typed as
  a label) and `172.026.005.132` (70804, 708040; octets with leading zeros, which a resolver may read as octal). The
  script's "fails on a reason it does not know" does not catch them.
- A parent's prompt could show `010.0.0.1`, which looks private and may resolve to a public address.
- Fix: a host whose last label is all digits must be four decimal octets without leading zeros, else `Bad` (no
  prompt) on the phone, refused at intake, and a named skip in the script. Pin it in `mmsc_entries` with a trailing
  dot, upper case, `xn--` (kept as written, never decoded for display), `host:80` and `host:0` as invalid rows.

**L2 - The emulator's debug host is shadowed** (line 219, "`MmsDebug.mmscRules()`: unchanged").
- The AVD's SIM is 310260, which gets a generated row of five hosts. `SmsRuntime.withDebugMmsc` appends its row and
  `MmsGate.location` takes the first row for the MCC/MNC, so `--es mmsc_host 10.0.2.2 --es mccmnc 310260` stops
  working. Fix: the debug row's hosts are merged into the row for its MCC/MNC; `emulator.md` §6v says which path
  (the hook or the parent's allow) each scenario uses.

**L3 - `--selftest` cannot see a hand-edited `mmsc_hosts.txt`** (lines 96-99). It checks the header only. Fix: the
script holds the SHA-256 of the output's body, as `yr-symbols.py` holds its manifest's; the selftest compares.

**L4 - The shared push budget is not needed** (lines 171-172). An event is raised only when a row is created, and at
most 4 rows are open until a parent acts, so the cap is the budget. `PushBudget` is per kind today; sharing one across
two kinds is new machinery. Keep the shared switch, drop the budget.

**L5 - The finding's new sentence is false on an older launcher** (lines 188-191). It reports nothing, so "you can
allow its address here" never comes true. Show the sentence only when the newest `sms_state` has `sim_operators`.

**L6 - The card's place and its edges** (lines 149-152). Allowed and declined rows keep "Bildeadresser" at the top of
the Calls page for good; put decided rows below (or folded), open rows at the top. Follow `#held` fully: the anchor
always present, a POST for a row that is gone or already decided lands on a notice in place (design 31 §5.1).

**L7 - The prompt waits for the next periodic sync** (lines 117-127). A kept picture is `PushOutcome.Shown`, which
asks for no sync; a hold does (`requestHeldSync`). Ask the same coalesced sync when a contact's picture is kept
unlisted, so the question does not eat into the picture's expiry.

**L8 - Stored row: which picture, which clock** (lines 142-147). `seen_at` is used both as "when the picture came" and
"last reported". Say that an open row's `from_number` and picture time follow the newest report, and keep the purge's
clock apart.

**L9 - Honesty, four places.**
- Line 82: "the IMSI is not readable". The launcher is the Device Owner; the SDK documents the subscriber id and GID1
  as readable for a device owner with `READ_PHONE_STATE` (not checked on the phone). The union stands on the cost of
  copying the platform's matching; say that.
- Lines 69-70: "cannot be claimed by an outsider on the MMS network" rests on the platform always fetching over the
  SIM's MMS network (and through `mmsproxy` where set). That is read from the platform's source, not shown on the
  phone; mark it so, beside step 0.
- Line 130: "already so today" is true only because the table is empty; with a filled table D10 blocked a wrong-host
  push silently. The argument that holds is under "Judged sound".
- Lines 133-135 and the `warn` string: after an allow of an attacker's host, every later crafted push naming **any**
  contact is fetched with no further question - pictures and text - until "Fjern". Say it in the note, and let `warn`
  say that the allow lasts.

## Judged sound

- **No fetch outside the list or the allow.** `drainDownloads` checks sender, host and expiry again at every fetch;
  a released held push goes through `admit`; rows are matched by the picture's own subscription, so a swapped SIM
  never reads the old SIM's row (an inactive subscription has no operator and fails closed); the first policy after a
  swap has no row; the phone's report only opens a question, never an entry.
- **Compatibility.** Old launcher on a new server: no `sim_operators`, so `[]`; were a `host:port` entry to reach it,
  `uri.host` never equals it and its port rule blocks the rest. New launcher on an old server: `[]`, and
  `sanitize_state` drops the two unknown keys without refusing the report. `sim_operators` is enough of a signal;
  no capability is needed.
- **The union of MVNO rows** matters only through H1: another operator's real MMSC serves nothing for a crafted id.
- **Userinfo, IPv6 literals, non-ASCII**: `Bad` today in `MmsGate.location`, and stay so. A single all-digit or hex
  label should be `Bad` by `java.net.URI`'s rule that a name's last label starts with a letter - read, not run; L1's
  vectors pin it.
- **The kid's screen.** Keeping a spoofed push as "Bilde kan ikke hentes" gives a spoofer nothing new: naming the
  listed host with a dead path already leaves a failed-picture bubble in a contact's thread. Hiding the bubble until
  an allow would only hide a real picture from the kid. Acceptable; for the owner to know.
- **Policy before report, the nudge precedent, `calls::saved`, the `#held` form anchors, `reported_history`, the
  empty released table, migration 0137, `bad_location` deleting the push**: all as the note says.
- **Generator**: pinned commit and hash, no network in the selftest, sorted output, Apache-2.0 into GPL-3.0 with a
  `NOTICE.md` entry, the always-run job. 24 KB compiled in and never sent is fine.
- **Emulator claims** (lines 239-243) are within what `fake-mmsc.py` can show.
