# 83 - The MMSC host list: a generated starting set, and the parent's allow for a host outside it

Status: design note by the architect, 2026-11-10, on `main` at 52deaf23, branch `design-83`. Nothing built. Server,
launcher and PWA; the shared contract `sms_contract.json` changes. Replaces the "hand-filled, a server release per
change" part of design 77 D10 ("The MMSC host list"); D10's check itself stays.

The user, 2026-11-10: "jeg kan jo ikke hardcode alle forskjellige operatlrers MMS adresse? I norge kan det jo være
mange forskjellige?" - and, to the direction below: "OK, det høres bra ut".

## The decision in ten lines

1. D10's host check stays: the phone fetches a picture only from a host on its SIM's list, checked before any fetch.
2. The list's starting set is **generated** from AOSP's `apns-full-conf.xml` (Apache-2.0) by a pinned script,
   `scripts/mmsc-hosts.py`, into `server/testdata/mmsc_hosts.txt` (862 MCC/MNCs, 24 KB), compiled into the server.
3. All hosts the APN list has for an MCC/MNC are one row (the union, MVNOs included); an entry is `host` or `host:port`.
4. The phone reports its SIMs' MCC/MNC (`sms_state.sim_operators`); the policy carries rows only for those.
5. A picture from a **contact** whose host is not on the list is kept unfetched ("Bilde kan ikke hentes", as today),
   and the phone reports the host - never the URL - in `sms_state.mmsc_unlisted`.
6. The PWA's Calls page asks the parent once per phone, SIM operator and host: "Telefonen vil hente bilder fra X.
   Tillat?" - with who sent the picture and when. The answer is stored (`device_mmsc_hosts`, migration 0137).
7. "Tillat" reaches the phone as a policy change; the waiting picture is fetched by the existing re-fetch path while
   it has not expired. "Ikke tillat" stays declined and never asks again. Both can be changed later on the same card.
8. Nothing is fetched from a host that is neither generated nor allowed. No SIM report, no row, an older launcher
   or an older server: every picture fails closed exactly as today.
9. No new capability, no new device route, no new kid-facing string. One round over server, launcher and PWA.
10. Unproven, and said so below: that an operator's notification names the host its APN row names. Where it does not,
    point 5-7 is what makes MMS work - so for some SIMs the parent will be asked once.

## 1. Does the APN list give what D10 needs?

**What it is.** `device/sample` on android.googlesource.com, `etc/apns-full-conf.xml`, commit
`73f904fbeb872f5675cb5f18cd9dfb15856425a6` (2025-03-23), 834,122 bytes, SHA-256
`9e44015793ec01d0773123103dca19b181fbc5fdd43cfc1b1d5cfacbda622726`, `<apns version="8">`, Apache-2.0 header. A row's
`mmsc` is the URL the phone **posts to** (sending, NotifyResp). The list says nothing about `X-Mms-Content-Location`.

**Counts** (computed from that file; the script reproduces them):

| | |
|---|---|
| `<apn>` rows | 3,667 |
| rows with an `mmsc` | 1,256, all `http://` |
| - with no `mcc`/`mnc` (carrier id only): skipped | 26 |
| - usable rows | 1,230 |
| usable rows by host: a name with a dot / single-label / IP literal | 821 / 150 / 259 |
| IP-literal rows: private or CGNAT range / public | 215 / 44 |
| rows naming a port outside 80, 443, 8080 (8002: 136, 10021: 72, ...) | 311, on 239 MCC/MNCs |
| MCC/MNCs in the generated set | 862 (267 with a 3-digit MNC) |
| distinct hosts: with a dot / single-label / IP (private + public) | 454: 358 / 20 / 76 (62 + 14) |
| MCC/MNCs with one host / more | 820 / 42 (most: 310000 with 10, 20815 with 7) |
| MCC/MNCs with only dotted names / with a single-label or IP entry | 524 / 338 |

**Real rows** (read from the list): Norway is 24201 Telenor `http://mmsc` (proxy `mms-proxy.telenor.no:8080`), 24202
Telia `http://mm/` (proxy `212.169.66.4:8080`), 24204 Tele2 `http://mmsc.tele2.no`, 24205 OneCall `http://mmsc/`,
24214 ice `http://mmsc/`, 24215 eRate `http://mms.media` - four of six are single-label names that only resolve
inside the operator's MMS network. Elsewhere: 23801 TDC `http://mmsc.tdc.dk:8002`, 23420 Three UK
`http://mms.um.three.co.uk:10021/mmsc`, 310410 AT&T `http://mmsc.mobile.att.net`, 24007 with five hosts, one of them
`http://185.110.178.96:38090/was`. Every host fits `[a-z0-9.-]` once lower-cased; there is no IPv6 literal.

**Is the notification's host the APN's `mmsc` host?** Unproven, for every operator, the Norwegian ones included. The
standard does not require it and the list cannot show it. Only a real MMS on a real SIM shows the host and the port an
operator's notification names (design 77 step 0, not done). The generated set is therefore a **best first guess made
of operator-run hosts**, not a promise that MMS works on a SIM; the parent's allow (§4) absorbs every difference.

**How the set stores them.** One line per MCC/MNC: `24201 mmsc`, `23801 mmsc.tdc.dk mmsc.tdc.dk:8002`. For each row
the script writes the lower-cased host, and also `host:port` when the APN names a port outside 80/443/8080 (the
notification may name either; both are the operator's). Scheme, path and `mmsproxy` are dropped.

**The launcher's matcher** (`MmsGate.location`). Today it already matches exactly, without case, and accepts a listed
single-label name or IPv4 literal; that stays, and it is right: such a name is listed only because the operator's own
APN row names it, and a single-label name or a private address cannot be claimed by an outsider on the MMS network.
No suffix matching (it would need a public-suffix list to be safe). Two changes:
- **Ports move from the shape rule to the list.** A URL with no port, 80, 443 or 8080 matches a bare `host` entry
  (as today). Any other port matches only an exact `host:port` entry. Without this, a quarter of the list's rows
  could never match if the operator's notification names the port its APN does.
- **A third result.** `Ok(host)`; `Unlisted(entry)` - the URL is a plain http(s) URL with a host that can be written
  as an entry, but the entry is not on this SIM's list (today's `UnknownOperator` is this case with an empty list);
  `Bad` - everything else (not http/https, userinfo, unparsable, a host outside `[a-z0-9.-]` or over 253 bytes, an
  IPv6 literal), blocked as `bad_location` with no allow path, as today.

## 2. MVNOs and several rows per MCC/MNC

The phone has `getSimOperator()` only (the IMSI is not readable; matching the list's `spn`/`gid`/`imsi` rules - 335
of the rows carry one - would need all three and a copy of the platform's matching). **The union is the decision**:
every host the list has for an MCC/MNC, in one row. The cost is a few more operator-run hosts on 42 of 862 MCC/MNCs
(38 where MVNO rows differ). It widens the first hop only to hosts some operator on that network code runs; it never
adds a host from outside the list. The server sends exactly one row per MCC/MNC (generated and allowed merged), so the
launcher's `firstOrNull` lookup stays.

## 3. Where the generated set lives and how it is pinned

- **Script** `scripts/mmsc-hosts.py`: Python's standard library, run with `-I`, the pattern of `yr-symbols.py`. It
  holds the commit and the input's SHA-256, fetches `…/device/sample/+/<commit>/etc/apns-full-conf.xml?format=TEXT`
  (base64; `--cache DIR` to reuse a download), and writes nothing unless the hash matches. A row it cannot use for a
  reason it does not know (a scheme other than http/https, a host outside the grammar, an MCC/MNC not 5-6 digits)
  **fails the run** - the only known skip is a row without `mcc`/`mnc`. Output is sorted and stable.
- **`--selftest`, no network**: converts the vendored `scripts/mmsc-hosts-sample.xml` (our own few rows, `.invalid`
  hosts, every case of §1) to exactly the lines kept in the script, and checks that the committed
  `server/testdata/mmsc_hosts.txt` names the script's commit and hash in its header. Run by `server-ci.yml`'s
  always-run `server-changes` job; the script, the sample and the output join that workflow's path filter.
- **Output** `server/testdata/mmsc_hosts.txt` (header: source, commit, SHA-256, the Apache-2.0 notice; `NOTICE.md`
  gets its entry), compiled in with `include_str!` like `doh_hosts.txt` and parsed once. `mmsc_hosts.rs` keeps its
  shape check (`the_table_is_well_formed`, now over the parsed file and with the `:port` form: 1-65535, never 80, 443
  or 8080) and loses the hand `TABLE`. Refreshing the set is: move the commit and hash, run the script, review the diff.
- **On the wire**: never the table. `call_policy.mmsc_hosts` has rows only for the MCC/MNCs in the newest status
  report that carries an `sms_state` (the phone sends `null` before the first unlock; `sms::reported_history` reads
  the same way) - at most 4, a row is about 50 bytes.
- **How the server knows the SIM**: the new `sms_state.sim_operators` (§6), from every active subscription. Report-
  driven like `last_capabilities`; rejected: a query parameter on `GET /policy` (the policy would stop being a
  function of the server's state alone, which the snapshot test and every page that explains the policy assume).
- **First sync and SIM swap**: a sync fetches the policy before it reports, so the first policy after provisioning or
  a swap has `mmsc_hosts: []` for the new SIM - fail closed. The status handler compares the reported operators with
  the previous report's and nudges the phone when they changed (as `allow_installed_catalog_apps` does); the next
  policy has the rows. A picture that arrived in between waits as `mmsc_unknown` and is fetched by the re-fetch path.

## 4. The parent's approval

**What the phone reports.** `sms_state.mmsc_unlisted: [{mccmnc, host, from, at}]`, at most 4 rows, the newest
waiting picture per MCC/MNC and host. `host` is the entry (§1) - never the URL, its path or query; `from` is the
contact's normalised number; `at` the notification's arrival. It is **state, not an event**: computed at every report
from the store's rows that are `FAILED` with `mmsc_unknown`, not expired, whose sender still passes and whose location
is still `Unlisted`. So it survives a restart, needs no queue or acknowledgement, and empties by itself.

**When.** Only for a notification whose sender passed D10 check 1 **as a contact** (D4 rule 5: `verdict.contact` set).
A stranger (rule 7) is held unfetched before the host is ever looked at and raises nothing; a non-contact let in by
the emergency callback window (rule 2) gets the bubble but no report. Launcher change: `MmsFlow.admit` keeps an
`Unlisted` picture as the `FAILED`/`mmsc_unknown` row it keeps today for an unknown operator, instead of blocking it
as `bad_location` (which deletes the notification, so nothing could be fetched after an allow).

**What a spoofer can and cannot do.** Someone who can deliver a crafted MMS notification naming a contact's number
can: put one "Bilde kan ikke hentes" bubble in that contact's thread (already so today, with the empty table), and
raise a prompt naming a host of their choosing - at most 4 open per phone, pushed within the budget below. They
cannot: make the phone send one byte to that host without the parent's tap; raise anything as a non-contact; learn
whether the prompt was shown. If the parent does allow an attacker's host, design QA H3's outcome applies to that SIM
until the host is removed (the phone's User-Agent seen, a reply of their choosing parsed in the isolated parser, a
picture in the contact's thread). That is why the prompt names the sender and the time, and says what to check.

**What the server keeps of a report.** A row is taken only when its `mccmnc` is in the same report's
`sim_operators`, `host` passes the entry check, `from` is one of this phone's contacts, and the entry is not already
generated or allowed for that MCC/MNC (then the policy just has not reached the phone: nudge, no prompt). Anything
else is dropped and logged.

**Stored** in `0137_device_mmsc_hosts.sql`: `device_mmsc_hosts(id, device_id, mccmnc, host, state, from_number,
seen_at, decided_at, decided_by)`, `state` `open`/`allowed`/`declined`, unique on `(device_id, mccmnc, host)`, deleted
with the phone. **Per phone and MCC/MNC**: an allow never follows the phone to another SIM operator. Caps: 4 `open`
rows a phone (a fifth host is not stored - a row a parent is reading never vanishes under them), 8 `allowed` per phone
and MCC/MNC, 32 `declined` (the oldest goes). An `open` row nobody answers is deleted 30 days after it was last
reported.

**How the PWA shows it.** A section `#mmsc` on the phone's Calls page, under "Meldinger som venter" (`#held`), there
only when it has rows. Its forms post to `/devices/{id}/calls/mmsc/{row}/allow#mmsc-{row}` (and `/decline`), the
`#held` forms' pattern, so **a save returns to its own row and never jumps to the top**. An open row is counted and
linked wherever a held message is. Words (`{name}` the contact's, `{operator}` from `sim_operators`, `{hosts}` the
generated hosts for that MCC/MNC):

| key (`device.calls.mmsc.`) | nb | en |
|---|---|---|
| `title` | Bildeadresser | Picture addresses |
| `ask` | Telefonen vil hente bilder fra {host}. Tillat? | The phone wants to fetch pictures from {host}. Allow? |
| `about` | Det gjelder en bildemelding fra {name} {time}. | This is about a picture message from {name} {time}. |
| `sim` | SIM-kortet er fra {operator}. Vibb kjenner disse adressene for det: {hosts}. | The SIM is from {operator}. Vibb knows these addresses for it: {hosts}. |
| `sim_none` | SIM-kortet er fra {operator}. Vibb kjenner ingen adresser for det. | The SIM is from {operator}. Vibb knows no addresses for it. |
| `warn` | Tillat bare hvis {name} virkelig sendte et bilde da. En falsk melding kan be telefonen hente fra en fremmed adresse. | Allow only if {name} really sent a picture then. A fake message can ask the phone to fetch from a stranger's address. |
| `allow` / `decline` | Tillat / Ikke tillat | Allow / Don't allow |
| `allowed` + `remove` | Tillatt {date} av {parent} / Fjern | Allowed {date} by {parent} / Remove |
| `declined` + `allow_after` | Ikke tillatt / Tillat likevel | Not allowed / Allow after all |
| `gone` | Bildet er ikke lenger tilgjengelig. Neste bilde hentes hvis du tillater adressen. | The picture is no longer available. The next one is fetched if you allow the address. |

`gone` shows on an open row the phone no longer reports. A new event kind `mmsc_host` (inbox and push, target
`#mmsc`) is raised once, when a row is created: "Emma: et bilde fra Mamma venter" / "Telefonen vil hente det fra en
adresse Vibb ikke kjenner." ("Emma: a picture from Mamma is waiting" / "The phone wants to fetch it from an address
Vibb doesn't know."). It shares the `push_sms_held` switch and that kind's budget (3 a phone's hour): to the parent
it is the same thing, a message waiting for them.

**After "Tillat".** The handler stores `allowed`, and the Calls page's usual nudge (`calls::saved`) fetches the
policy, now with the entry in that MCC/MNC's row. The launcher's rule-change listener calls `retryWaiting`, which
moves a waiting row back to `NOTIFIED` when **that row's location is now `Ok`** (today it asks only "is the operator
known", which would loop a still-unlisted row); `drainDownloads` checks sender, host and expiry again and fetches.
Past its expiry the row reads as every expired picture does.

**A decline** is `declined`: the phone goes on reporting the host until the picture expires, and later pictures
report it again; the server neither re-opens nor pushes. **Taking back**: "Fjern" on an allowed row makes it
`declined` (the next policy is without it; fetched pictures stay on the phone); "Tillat likevel" the other way.

## 5. Fail closed stays

A SIM with no generated row and no allow, an unreadable SIM operator, a policy without rows: no fetch, the bubble
"Bilde kan ikke hentes", the phone's `mmsc_known: false`. The finding "MMS kan ikke sjekkes på dette SIM-kortet"
(`mms_unchecked`) is **kept beside** the prompt: it is the standing state ("no address known for this SIM"), the
prompt is the way out once a contact's picture names one. Its long text gains one sentence, nb "Når en kontakt sender
et bilde, kan du tillate adressen her." / en "When a contact sends a picture, you can allow its address here." With
the generated set most SIMs never show it; after an allow the SIM has a row and it goes.

## 6. The contract

`server/testdata/sms_contract.json` (byte-identical in the launcher's test resources), one commit for both sides:

- **Policy** `call_policy.mmsc_hosts: [{mccmnc, hosts}]`: key and shape unchanged - `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` pin it as before. New meaning: one row per reported MCC/MNC, `hosts` the generated and
  allowed entries, each `host` or `host:port`. `policy.mmsc_hosts_row` becomes
  `{"mccmnc": "24299", "hosts": ["mmsc", "mmsc.example.invalid", "mmsc.example.invalid:8002"]}`.
- **Report** `report.sms_state` gains `sim_operators: [{mccmnc, name}]` (at most 4; `mccmnc` 5-6 digits; `name` the
  SIM's operator name, control characters out, at most 32 characters, may be empty) and `mmsc_unlisted` (§4). Both
  always sent, empty when there is nothing; `SMS_STATE_FIELDS` and `sms::sanitize_state` take them with these bounds.
- **New** `mmsc_entries: [{entry, valid}]`: the entry grammar, run by the server's intake and shape check and by the
  launcher's report code. The location vectors (URL, list -> `ok`/`unlisted`/`bad`) are the launcher's alone,
  in `MmsGateTest`.

**No new capability.** `sim_operators` is the signal: an older launcher reports none, so a new server sends it
`mmsc_hosts: []` and it fails closed as today (and if rows ever reached one, it would match bare hosts under its old
port rule, never a `host:port` entry, and block the rest - closed too). A new launcher on an older server gets `[]`
(every released server's table is empty), keeps each picture as `mmsc_unknown`, and its two new keys are dropped by
the old server's known-fields check. A launcher downgrade is covered by "the newest report decides".

## 7. Redirects and the rest of D10

- **Redirects (design 77 N2): unchanged and still unproven.** The platform's `MmsHttpClient` follows same-protocol
  redirects, so the check covers the first hop. A parent-allowed public host is a first hop the parent chose; the
  generated ones are operator-run. Whether an operator's MMS APN reaches outside hosts at all is step 0's to read.
- Check 1 (the sender), check 3 (the size), the isolated parser, `mmsc_known` and `MmsDebug.mmscRules()`: unchanged.
- Changed in D10's text: the port rule (§1), `bad_location` narrowed to shape failures, "unknown MCC/MNC" merged
  into `Unlisted`, and "not parent-editable, a server release" replaced by this note. D10's promise now reads: never
  fetched from a host that is neither on the generated set for the SIM nor allowed by the parent for it.
- Residual, named: a list row whose public domain has lapsed and been re-registered could be fetched from on that
  MCC/MNC, if the attacker also spoofs a contact. The pinned commit and a reviewed diff at each refresh are the guard.

## 8. Steps and what can be shown

**One round, size M-L**, the API change (both directories and the contract) in one commit: the generator (S: script,
sample, `mmsc_hosts.txt`, `mmsc_hosts.rs` reading it, the CI step, `NOTICE.md`); the server and PWA (M: 0137, the two
report keys, the per-SIM policy rows and the nudge, the section with its forms, the event kind, nb + en); the
launcher (S-M: `MmsGate`'s `Unlisted` and ports, `MmsFlow.admit` and `retryWaiting`, the two report keys).

Lean tests, behaviour only: the script's `--selftest`; server - the policy has exactly the reported SIMs' rows
(generated and allowed merged, `[]` without a report), intake keeps and drops what §4 says, allow/decline/take-back
change the next policy, a decided host raises no second event, each form lands on its own anchor; launcher -
`MmsGateTest`'s vectors, a kept picture is fetched once its host is allowed and not before, `SmsContractTest`.
Docs in the same round: design 77 D10's pointer here, the two calls notes, both `CLAUDE.md`s, `emulator.md` §6v.

**The emulator with `scripts/fake-mmsc.py` can show** the whole mechanism: an unlisted host is kept and reported,
the prompt, allow -> fetch, decline -> nothing, a restart or an expiry in between, a SIM operator change emptying
the rows. **It cannot show** what any operator's notification names (host, port), whether the generated row for a
Norwegian SIM matches it, redirects, or what the MMS APN can reach. That is step 0 on a real SIM, still open; this
round's QA must not claim "MMS works on Telenor/Telia/ice", only "the gate and the allow work".

## Not in this design

- Suffix or same-domain matching ("anything under telenor.no"); precise MVNO matching by SPN/GID1/IMSI.
- A family-wide allow across phones; a parent switching a generated host off, or typing a host by hand.
- Refreshing the APN list automatically or at runtime; gating sending (D10 does not); any change to redirects.

## For the user

No open design question. One thing to know, not to decide: the generated list is the operators' *send* addresses.
Whether a Norwegian operator's picture notification names the same host is not known before a real picture arrives
(design 77 step 0, still open); if it differs, you get the question once for that SIM.
