# 83 - The MMSC host list: a generated starting set, and the parent's allow for a host outside it

Status: design note by the architect, 2026-11-10, on `main` at 52deaf23, branch `design-83`. Nothing built. Server,
launcher and PWA; the shared contract `sms_contract.json` changes. Replaces the "hand-filled, a server release per
change" part of design 77 D10 ("The MMSC host list"); D10's check itself stays.

**Revision 2, 2026-11-10**: the design QA (`docs/review/qa-83-design.md`: one High, three Mediums, nine Lows), all
taken in; H1 by the orchestrator's decision for the strict variant. The table at the end says where each went.

The user, 2026-11-10: "jeg kan jo ikke hardcode alle forskjellige operatlrers MMS adresse? I norge kan det jo være
mange forskjellige?" - and, to the direction below: "OK, det høres bra ut".

## The decision in ten lines

1. D10's host check stays: the phone fetches a picture only from a host on its SIM's list, checked before any fetch.
2. A pinned script, `scripts/mmsc-hosts.py`, reads AOSP's `apns-full-conf.xml` (Apache-2.0) into
   `server/testdata/mmsc_hosts.txt` (852 MCC/MNCs, 26 KB), compiled into the server, every entry in one of two classes.
3. **Unasked** (sent in the policy): only names that mean something only inside an operator's MMS network -
   single-label names and private or CGNAT address literals (298 MCC/MNCs). **Hints** (never sent, only shown to the
   parent): every public dotted name and public address (554 MCC/MNCs have nothing else).
4. **A gate**: the unasked class is released only once design 77 step 0 has shown, on a real SIM, that the platform
   fetches on the MMS network and nowhere else. Until then the file is generated with every entry a hint.
5. The phone reports its SIMs' MCC/MNC (`sms_state.sim_operators`); the policy carries rows only for those.
6. A picture from a **contact** whose host is not on the list is kept unfetched ("Bilde kan ikke hentes", as today),
   and the phone reports the host - never the URL - in `sms_state.mmsc_unlisted`.
7. The PWA's Calls page asks the parent **once per address** (per phone and SIM operator; an operator with several
   addresses asks for each, at most 8): "Telefonen vil hente bilder fra X. Tillat?" - with who sent the picture and
   when, and whether Android's list names that address for the operator. Stored in `device_mmsc_hosts` (0137).
8. "Tillat" reaches the phone as a policy change, the waiting picture is fetched by the existing re-fetch path, and
   the allow lasts until the parent removes it. "Ikke tillat" stays declined and never asks again.
9. Nothing is fetched from a host that is neither unasked nor allowed; every mixed-version pair fails closed as
   today. No new capability, no new device route, no new kid-facing string. One round.
10. Unproven, and said so below: that an operator's notification names the host its APN row names, and the gate's
    premise. Step 0's reading decides whether the generated set earns its keep; the allow works without it.

## 1. Does the APN list give what D10 needs?

**What it is.** `device/sample` on android.googlesource.com, `etc/apns-full-conf.xml`, commit
`73f904fbeb872f5675cb5f18cd9dfb15856425a6` (2025-03-23), 834,122 bytes, SHA-256
`9e44015793ec01d0773123103dca19b181fbc5fdd43cfc1b1d5cfacbda622726`, `<apns version="8">`, Apache-2.0 header. A row's
`mmsc` is the URL the phone **posts to** (sending, NotifyResp). The list says nothing about `X-Mms-Content-Location`,
and it is more than ten years of accumulated rows: nobody has checked who holds its public names today.

**Counts** (computed from that file; the script reproduces them; the QA reproduced the first seven):

| | |
|---|---|
| `<apn>` rows | 3,667 |
| rows with an `mmsc` | 1,256, all `http://` |
| - with no `mcc`/`mnc` (carrier id only): skipped | 26 |
| - usable rows | 1,230, on 862 MCC/MNCs |
| usable rows by host: a name with a dot / single-label / address literal | 821 / 150 / 259 |
| rows naming a port outside 80, 443, 8080 (8002: 136, 10021: 72, ...) | 311, on 239 MCC/MNCs |
| distinct hosts | 454 |
| - **unasked**: single-label names / private or CGNAT addresses | 82: 20 / 62 |
| - **hints**: public dotted names / public addresses | 365: 351 / 14 |
| - **skipped by name**: a made-up last label / a malformed address | 7: 5 / 2 |
| MCC/MNCs with at least one unasked host (8 of them also have hints) | 298 |
| MCC/MNCs with only hints: the parent is asked once, with the hint | 554 |
| MCC/MNCs left with nothing: asked once, with the plain warning | 10 |

**Real rows** (read from the list). Norway's six:

| MCC/MNC | row | class | the first picture |
|---|---|---|---|
| 24201 Telenor | `http://mmsc` (proxy `mms-proxy.telenor.no:8080`) | unasked | fetched, if the notification names `mmsc` |
| 24202 Telia | `http://mm/` (proxy `212.169.66.4:8080`) | unasked | fetched, if it names `mm` |
| 24205 OneCall | `http://mmsc/` | unasked | fetched, if it names `mmsc` |
| 24214 ice | `http://mmsc/` | unasked | fetched, if it names `mmsc` |
| 24204 Tele2 | `http://mmsc.tele2.no` | hint | asks once, "Android's list names it" |
| 24215 eRate | `http://mms.media` (no proxy) | skipped | asks once, with the warning |

"Unasked" is after the gate (§3); before it all six ask once. Elsewhere: 23801 TDC `http://mmsc.tdc.dk:8002`, 23420
Three UK `http://mms.um.three.co.uk:10021/mmsc`, 310260 (the emulator's SIM) five dotted names - all hints.
**Skipped**: `mms.media` (24006, 24215), `mms.mobileuc.global` (23453, 313770), `mms.zain` (41902), `mmsc.axis`
(51008), `mms.primetel` (28020) - last labels that were not public TLDs when the name was made up, and are or can be
now; `10.152.10.70.38090` (64602) and `172.026.005.132` (70804, 708040) - not an address a resolver reads one way.

**Is the notification's host the APN's `mmsc` host?** Unproven, for every operator, the Norwegian ones included. The
standard does not require it and the list cannot show it; an operator may also name several hosts (a node per region
- from memory of public logs, unverified, some US operators do). Only a real MMS on a real SIM shows it (design 77
step 0, not done). The generated set is a first guess from "listed in AOSP's APN file for that MCC/MNC", not a promise;
the parent's allow (§4) absorbs every difference, and if step 0 finds no operator where the two agree, the unasked
class is dropped again and the hints alone stay.

**An entry** is `host` or `host:port`, one grammar on both sides (`mmsc_entries`, §6): `host` is 1-253 bytes of
`[a-z0-9.-]` in non-empty labels, and either its last label starts with a letter or it is exactly four decimal octets
(0-255, no leading zeros; the first never 0 or 127 - "this host", code QA L4); `port` is 1-65535 and never 80, 443 or
8080. An `xn--` label is kept and shown as written. The script writes the lower-cased host, and `host:port` too when
the APN names another port.

**The launcher's matcher** (`MmsGate.location`). It matches exactly and without case today, and accepts a listed
single-label name or IPv4 literal; that stays. No suffix matching (it would need a public-suffix list). Changes:
- **Ports move from the shape rule to the list.** No port, 80, 443 or 8080 matches a bare `host` entry (as today);
  any other port only an exact `host:port` entry. Without it a quarter of the list's rows could never match.
- **A third result.** `Ok(host)`; `Unlisted(entry)` - a plain http(s) URL whose host and port make an entry that is
  not on this SIM's list (today's `UnknownOperator` is this with an empty list); `Bad` - everything else (not
  http/https, userinfo, unparsable, an IPv6 literal, a host outside the grammar such as `010.0.0.1`, `2130706433` or
  `0x7f000001`, and since code QA L3 a single label with a trailing dot: `mmsc.` is an absolute name, another lookup
  than the entry `mmsc`), blocked as `bad_location` with no prompt and no allow path, as today.

## 2. MVNOs and several rows per MCC/MNC

**The union**: every entry the list has for an MCC/MNC, whatever its `spn`/`gid`/`imsi` rule (335 rows carry one; 42
MCC/MNCs get more than one host, at most 5 unasked). The launcher reads `getSimOperator()` only. Precise matching is
not impossible - the SDK documents the subscriber id and GID1 as readable by a Device Owner with `READ_PHONE_STATE`
(not checked on the phone) - but it would copy the platform's matching rules for little gain: in the strict variant
the union can add only another brand's internal name to the row, never a public one.
The server sends one row per MCC/MNC (unasked and allowed merged), so the launcher's `firstOrNull` lookup stays.

## 3. Where the generated set lives, how it is pinned, and the gate

- **Script** `scripts/mmsc-hosts.py`: Python's standard library, run with `-I`, the pattern of `yr-symbols.py`. It
  holds the commit and the input's SHA-256, fetches `…/device/sample/+/<commit>/etc/apns-full-conf.xml?format=TEXT`
  (base64; `--cache DIR`), and writes nothing unless the hash matches. It holds the **last labels it accepts** (the 94
  country codes and old generic TLDs the public names end in today) and a **skip list** of hosts, each with its
  reason (the seven above); any other last label, scheme or malformed host **fails the run**, so a made-up TLD is
  seen when the set is generated. The skip list is also the remedy for a listed name later found in an outsider's
  hands (a server release, as any fix); a skipped host still works through the parent's allow.
- **One constant decides the classes**, `UNASKED`: `"none"` (every entry a hint), `"internal"` (the strict variant:
  single-label names and addresses in 10/8, 172.16/12, 192.168/16, 100.64/10), `"listed"` (the first revision's:
  every listed entry unasked - the owner may choose it later). Changing it is that one line and the regenerated file.
- **The gate.** Trusting a single-label name or a private address is right only if the platform always fetches on the
  SIM's MMS network (through `mmsproxy` where set) and never falls back to Wi-Fi - there, `mmsc` or `10.0.0.9` is
  whoever answers on that network. That is read from the platform's source (`MmsService` asks for a network with the
  MMS capability and opens the connection on it), **not shown on a phone**. So the committed file is generated with
  `"none"` until step 0 records, on the test phone with a real SIM: (a) with Wi-Fi on, a picture at a single-label
  or private address is fetched; (b) a notification naming a private address on the Wi-Fi network brings no request
  there although the platform was asked, with mobile data on and off. The steps a person follows, with the control
  that tells "the platform tried elsewhere" from "our own gate kept it", are design 77 §9's "Design 83's gate, step
  by step" - the one full text. Then `"internal"` goes in as its own commit and server release. If (b) fails it
  never does - and a parent's allow of such a name has the same weakness, which step 0 must then report to the owner
  before MMS ships.
- **`--selftest`, no network**: converts the vendored `scripts/mmsc-hosts-sample.xml` (our own rows, `.invalid`
  hosts, every class and every skip reason, under each `UNASKED` value) to exactly the lines kept in the script, and
  compares the committed output's body with the SHA-256 the script holds for it (as `yr-symbols.py` holds its
  manifest's), so a hand edit fails. Run by `server-ci.yml`'s always-run `server-changes` job; the script, the sample
  and the output join that workflow's path filter.
- **Output** `server/testdata/mmsc_hosts.txt`: a header (source, commit, hashes, `UNASKED`, the Apache-2.0 notice;
  `NOTICE.md` gets its entry), then `mccmnc unasked… | hints…` per line (`24201 mmsc |`, `23801 | mmsc.tdc.dk
  mmsc.tdc.dk:8002`). Compiled in with `include_str!` like `doh_hosts.txt`; `mmsc_hosts.rs` keeps its shape check
  (now the entry grammar, over both classes) and loses the hand `TABLE`.
- **On the wire**: never the table, never a hint. `call_policy.mmsc_hosts` has a row for each MCC/MNC in the newest
  status report that carries an `sms_state` (as `sms::reported_history` reads; at most 4) that has an unasked or an
  allowed entry.
- **How the server knows the SIM**: the new `sms_state.sim_operators` (§6). Report-driven like `last_capabilities`;
  rejected: a query parameter on `GET /policy` (the policy would stop being a function of the server's state alone).
- **First sync and SIM swap**: a sync fetches the policy before it reports, so the first policy for a new SIM has no
  row - fail closed. The status handler nudges the phone when the reported operators differ from the previous
  report's (as `allow_installed_catalog_apps` does); that is this design's only nudge from a report. A picture that
  came in between waits as `mmsc_unknown` and is fetched by the re-fetch path.

## 4. The parent's approval

**What the phone reports.** `sms_state.mmsc_unlisted: [{mccmnc, host, from, at}]`: `host` is the entry - never the
URL, its path or query; `from` the contact's normalised number; `at` the notification's arrival. It is **state, not
an event**, computed at every report from the store's rows that are `FAILED` with `mmsc_unknown`, whose sender still
passes, whose location is still `Unlisted` and which **arrived within 7 days** (`PUSH_KEY_KEEP_MS` - whatever the
notification's own expiry says: its sender writes that, and without one a row never expires). One row per MCC/MNC
and host, the **4 with the newest arrival**. It survives a restart and needs no queue or acknowledgement.

**When.** Only for a notification whose sender passed D10 check 1 **as a contact** (D4 rule 5: `verdict.contact`
set). A stranger (rule 7) is held unfetched before the host is looked at; a non-contact let in by the emergency
callback window (rule 2) gets the bubble but no report. Launcher changes: `MmsFlow.admit` keeps an `Unlisted` picture
as the `FAILED`/`mmsc_unknown` row it keeps today for an unknown operator, instead of blocking it as `bad_location`
(which deletes the notification); and the runtime then asks the coalesced sync a held message asks
(`SmsRuntime.requestHeldSync`), so the question does not wait for the periodic sync while the picture's expiry runs.

**What a spoofer can and cannot do.** Someone who can deliver a crafted notification naming a contact's number can:
leave a "Bilde kan ikke hentes" bubble in that contact's thread (no host check prevents that: naming a listed host
with a dead path leaves the same bubble), and raise a prompt naming a host of their choosing. By keeping at it they can hold the
phone's four report rows and the server's four open rows, so a real operator's prompt stays out until the parent has
declined theirs, four at a time - no fetch, no data, the parent's time. They cannot: make the phone send one byte
without the parent's tap; raise anything as a non-contact; learn whether a prompt was shown. **If the parent allows
an attacker's host**, every later crafted notification naming *any* contact on that SIM is fetched from it without a
further question - pictures and text of the attacker's choosing in a contact's thread, the phone's User-Agent seen
(design QA H3) - until "Fjern". That is why the prompt names the sender and the time and says that the allow lasts.

**What the server keeps of a report.** A row is taken only when its `mccmnc` is in the same report's
`sim_operators`, `host` is a valid entry, `from` is one of this phone's contacts, and the entry is neither unasked
nor allowed for that MCC/MNC (then the policy has just not reached the phone; nothing is raised). Else dropped, logged.

**Stored** in `0137_device_mmsc_hosts.sql`: `device_mmsc_hosts(id, device_id, mccmnc, host, state, picture_from,
picture_at, reported_at, decided_at, decided_by)`, `state` `open`/`allowed`/`declined`, unique on `(device_id,
mccmnc, host)`, deleted with the phone. While a row is open, `picture_from` and `picture_at` follow the newest report;
`reported_at` is when a report last named it and is the purge's clock alone: an open row goes 30 days after it. **Per
phone and MCC/MNC**: an allow never follows the phone to another operator's SIM. Caps: 4 `open` rows a phone (a fifth
host is not stored - a row a parent is reading never vanishes under them), 8 `allowed` per phone and MCC/MNC (a ninth
still shows its prompt, with `full` in place of "Tillat"), 32 `declined` (the oldest goes).

**How the PWA shows it.** Open rows sit right under "Meldinger som venter" (`#held`) on the phone's Calls page, in a
section `#mmsc` whose anchor is always there; decided rows are a folded group "Bildeadresser" at the foot of the
page. Forms post to `/devices/{id}/calls/mmsc/{row}/allow#mmsc-{row}` (and `/decline`), the `#held` forms' pattern:
**a save returns to its own row and never jumps to the top**, and a POST for a row that is gone or already decided
lands on `notice` in place. An open row is counted wherever a held message is, and the device page's count row leads
to `#mmsc` when no message is held (code QA). `allowed_on` is an allowed row whose account is gone (code QA L7). A
prompt is `ask`, `about`, then `listed` when the entry is a hint for that MCC/MNC, else `unlisted` (or
`unlisted_none`) and `warn`; then `lasts`.

| key (`device.calls.mmsc.`) | nb | en |
|---|---|---|
| `title` | Bildeadresser | Picture addresses |
| `ask` | Telefonen vil hente bilder fra {host}. Tillat? | The phone wants to fetch pictures from {host}. Allow? |
| `about` | Det gjelder en bildemelding fra {name} {time}. | This is about a picture message from {name} {time}. |
| `listed` | Adressen står i Androids liste over MMS-adresser for SIM-kortets operatør ({operator}). | The address is in Android's list of MMS addresses for the SIM's operator ({operator}). |
| `unlisted` | Adressen står ikke i Androids liste for SIM-kortets operatør ({operator}). Listen har: {hosts}. | The address is not in Android's list for the SIM's operator ({operator}). The list has: {hosts}. |
| `unlisted_none` | Androids liste har ingen adresse for SIM-kortets operatør ({operator}). | Android's list has no address for the SIM's operator ({operator}). |
| `warn` | Tillat bare hvis {name} virkelig sendte et bilde da. En falsk melding kan be telefonen hente fra en fremmed adresse. | Allow only if {name} really sent a picture then. A fake message can ask the phone to fetch from a stranger's address. |
| `lasts` | Tillater du, hentes alle senere bilder fra adressen uten spørsmål, helt til du fjerner den. | If you allow, every later picture from the address is fetched without asking, until you remove it. |
| `full` | Åtte adresser er tillatt for dette SIM-kortet. Fjern én nederst på siden for å tillate denne. | Eight addresses are allowed for this SIM. Remove one at the foot of the page to allow this one. |
| `allow` / `decline` | Tillat / Ikke tillat | Allow / Don't allow |
| `allowed` + `remove` | Tillatt {date} av {parent} / Fjern | Allowed {date} by {parent} / Remove |
| `declined` + `allow_after` | Ikke tillatt / Tillat likevel | Not allowed / Allow after all |
| `gone` | Bildet er ikke lenger tilgjengelig. Neste bilde hentes hvis du tillater adressen. | The picture is no longer available. The next one is fetched if you allow the address. |
| `notice` | Noen har allerede svart på denne. | Someone has already answered this one. |
| `allowed_on` | Tillatt {date} | Allowed {date} |

`gone` shows on an open row the phone no longer reports. A new event kind `mmsc_host` (inbox and push, target
`#mmsc`) is raised once, when a row is created: "Emma: et bilde fra Mamma venter" / "Telefonen vil hente det fra en
adresse du må godkjenne." ("Emma: a picture from Mamma is waiting" / "The phone wants to fetch it from an address
you need to approve."). It shares the `push_sms_held` switch - to the parent the same thing, a message waiting for
them - and has no push budget: at most 4 rows are open until a parent acts, and that cap is the budget.

**After "Tillat".** The handler stores `allowed`, and the Calls page's usual nudge (`calls::saved`) fetches the
policy, now with the entry in that MCC/MNC's row. The launcher's rule-change listener calls `retryWaiting`, which
moves a waiting row back to `NOTIFIED` when **that row's location is now `Ok`** (today it asks only "is the operator
known", which would loop a still-unlisted row); `drainDownloads` checks sender, host and expiry again and fetches.
Past its expiry the row reads as every expired picture does.

**A decline** is `declined`: the phone may go on reporting the host; the server neither re-opens nor pushes.
**Taking back**: "Fjern" on an allowed row makes it `declined` (the next policy is without it; fetched pictures stay
on the phone); "Tillat likevel" the other way.

## 5. Fail closed stays

A SIM with no unasked entry and no allow, an unreadable SIM operator, a policy without rows: no fetch, the bubble
"Bilde kan ikke hentes", the phone's `mmsc_known: false`. The finding "MMS kan ikke sjekkes på dette SIM-kortet"
(`mms_unchecked`) is **kept beside** the prompt: it is the standing state, the prompt the way out once a contact's
picture names an address. Its long text gains one sentence - nb "Når en kontakt sender et bilde, kan du tillate
adressen her." / en "When a contact sends a picture, you can allow its address here." - shown only when the newest
`sms_state` has `sim_operators` (an older launcher reports nothing, so it would never come true). Before the gate
every managed SIM shows the finding until its first allow; after it, 564 of the list's 862 MCC/MNCs still do.

## 6. The contract

`server/testdata/sms_contract.json` (byte-identical in the launcher's test resources), one commit for both sides:

- **Policy** `call_policy.mmsc_hosts: [{mccmnc, hosts}]`: key and shape unchanged - `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` pin it as before. New meaning: one row per reported MCC/MNC, `hosts` its unasked and
  allowed entries. `policy.mmsc_hosts_row` becomes
  `{"mccmnc": "24299", "hosts": ["mmsc", "mmsc.example.invalid", "mmsc.example.invalid:8002"]}`.
- **Report** `report.sms_state` gains `sim_operators: [{mccmnc, name}]` (at most 4; `mccmnc` 5-6 digits; `name` the
  SIM's operator name, control characters out, at most 32 characters, may be empty) and `mmsc_unlisted` (§4). Both
  always sent, empty when there is nothing; `SMS_STATE_FIELDS` and `sms::sanitize_state` take them with these bounds.
- **New** `mmsc_entries: [{entry, valid}]`: §1's grammar, run by the server's intake and shape check and by the
  launcher's report code - with a trailing dot, upper case, `host:80`, `host:0`, `010.0.0.1`, five numeric labels
  and an all-digit label as invalid rows, an `xn--` name and a `host:8002` as valid. The location vectors (URL and
  list -> `ok`/`unlisted`/`bad`) are the launcher's alone, in `MmsGateTest`.

**No new capability.** `sim_operators` is the signal: an older launcher reports none, so a new server sends it
`mmsc_hosts: []` and it fails closed as today (were rows to reach one, it would match bare hosts under its old port
rule, never a `host:port` entry, and block the rest). A new launcher on an older server gets `[]` (every released
server's table is empty), keeps each picture as `mmsc_unknown`, and its two new keys are dropped by the old server's
known-fields check. A launcher downgrade is covered by "the newest report decides".

## 7. Redirects and the rest of D10

- **Redirects (design 77 N2): unchanged and still unproven.** The platform's `MmsHttpClient` follows same-protocol
  redirects, so the check covers the first hop. Whether an MMS APN reaches outside hosts at all is step 0's to read.
- Check 1 (the sender), check 3 (the size), the isolated parser and `mmsc_known`: unchanged.
- **The debug hook** (`MmsDebug.mmscRules()`, debug builds only): its hosts are merged into the row for its MCC/MNC
  instead of appended as a second row, which `firstOrNull` would never read once that MCC/MNC has a row.
  `emulator.md` §6v says which path each scenario uses: the hook, or the parent's allow.
- Changed in D10's text: the port rule (§1), `bad_location` narrowed to shape failures, "unknown MCC/MNC" merged
  into `Unlisted`, and "not parent-editable, a server release" replaced by this note. D10's promise now reads: never
  fetched from a host that is neither unasked for the SIM nor allowed by the parent for it.
- **Residuals, named.** (1) The unasked class stands on the gate's premise; step 0 (b) is its proof, not this note.
  (2) A hint is true and no more: the 351 dotted names and 14 public addresses are in the list, nobody has checked
  their owners, and brands behind some have closed (`virginmms.fr` 20823, `jazztelmms.com` 21403/21421). A spoofer
  who names such a host gets the reassuring `listed` line; it still takes the parent's tap, `about` and `lasts`.
  (3) With `"listed"`, should the owner choose it, the same hosts are fetched from with no question at all.

## 8. Steps and what can be shown

**One round, size M-L**, the API change (both directories and the contract) in one commit: the generator (S: script,
sample, `mmsc_hosts.txt`, `mmsc_hosts.rs` reading it, the CI step, `NOTICE.md`); the server and PWA (M: 0137, the two
report keys, the per-SIM policy rows and the one nudge, the section with its forms, the event kind, nb + en); the
launcher (S-M: `MmsGate`'s `Unlisted`, ports and grammar, `MmsFlow.admit` and `retryWaiting`, the two report keys,
the debug hook's merge).

Lean tests, behaviour only: the script's `--selftest`; server - the policy has exactly the reported SIMs' unasked
and allowed entries and never a hint (`[]` without a report), intake keeps and drops what §4 says, allow, decline,
take-back and the ninth allow, a decided host raises no second event, each form lands on its own anchor or notice;
launcher - `MmsGateTest`'s vectors, a kept picture is fetched once its host is allowed and not before, the report's
7 days and newest 4, `SmsContractTest`. Docs in the same round: design 77 D10's pointer here and step 0's two new
checks, the two calls notes, both `CLAUDE.md`s, `emulator.md` §6v.

**The emulator with `scripts/fake-mmsc.py` can show** the whole mechanism: an unlisted host is kept and reported,
the prompt with and without the hint (310260's five names are hints), allow -> fetch, decline -> nothing, a restart
or an expiry in between, a SIM operator change emptying the rows. **It cannot show** what any operator's
notification names, whether a Norwegian row matches it, which network the platform fetches on, redirects, or what
the MMS APN can reach. That is step 0 on a real SIM, still open; this round's QA must not claim "MMS works on
Telenor/Telia/ice", only "the gate and the allow work".

## Not in this design

- Suffix or same-domain matching ("anything under telenor.no"); precise MVNO matching by SPN/GID1/IMSI.
- A family-wide allow across phones; a parent switching an unasked host off, or typing a host by hand.
- Telling the phone which hosts are declined (it would free their report slots; §4 names what is left without it).
- Refreshing the APN list automatically or at runtime; gating sending (D10 does not); any change to redirects.

## For the user

No open design question; three things to know.
- **You are asked once per address, not once per SIM**, and until a real picture has been tried on a real SIM
  (design 77 step 0) every SIM asks for its first picture. After that check Telenor, Telia, OneCall and ice should
  work unasked - if their notifications name the address Android's list has, which only a real picture shows.
- The strict choice (public names from the list only as a hint in the question) was made for you under "no insecure
  fallbacks". It can be loosened to "every listed address unasked" by one line and a regenerated file.
- A fake picture message that names one of Emma's contacts shows as "Bilde kan ikke hentes" in that thread and as a
  question to you; it fetches nothing. Hiding it until you answer would also hide real pictures, so it stays.

## Revision 2: the design QA, finding by finding

| finding | what changed |
|---|---|
| H1 | Strict variant: two classes, hints never sent (points 2-3, §1, §3); accepted last labels and the skip list in the script; "operator-run" gone; `UNASKED` one line; residuals in §7 |
| M1 | Reported only within 7 days of arrival, the newest 4; the flood that is left is named (§4) |
| M2 | "Once per address" (point 7, "For the user"); the ninth allow has `full`; step 0 decides whether the set is kept (§1) |
| M3 | The "already listed" nudge is dropped; one nudge a report can raise (§3, §4) |
| L1 | One entry grammar with the four-octet rule; the two malformed rows skipped by name; `mmsc_entries` rows (§1, §6) |
| L2 | The debug hook's hosts merged into its MCC/MNC's row; `emulator.md` names the path (§7) |
| L3 | The script holds the output body's SHA-256; `--selftest` compares (§3) |
| L4 | The shared switch stays, the budget is gone (§4) |
| L5 | The finding's new sentence only when `sim_operators` is reported (§5) |
| L6 | Open rows under `#held`, decided rows folded at the foot, the anchor always there, `notice` (§4) |
| L7 | A kept unlisted picture asks the held sync (§4) |
| L8 | `picture_from`/`picture_at` follow the newest report, `reported_at` is the purge's clock (§4) |
| L9 | The subscriber id is readable in principle, the union stands on cost (§2); the MMS-network premise is marked as read, and is the gate (§3); "already so today" replaced by the argument that holds; the allow's reach said in §4 and in `lasts`, a string of its own shown on every prompt - the warning's and the hint's |
