# Code QA: the kid's contact request (design 77 §14)

Branch `sms-contact-request`, commits 58b7f07c and c2fa0147 on main b8254bcc. Security first. The scope is the server
endpoint `POST /api/devices/contact-request`, migration 0133, `call_policy.contact_requests`, the push event, the PWA's
"Forespørsler" card and the prefilled Add Contact, and the launcher's model and API client (`calls/ContactRequests.kt`,
the DTOs, `MdmApi`, the sync hooks). The kid's screens wait for design 79 and are out of scope.

**Verdict:** the security core holds:
- auth and per-phone scoping on every route;
- the prefill and the Matrix ID always come from the stored row;
- the input checks, escaping, the contract files, the snapshot, the CI paths and migration numbering are all right.

There are no Critical findings. One High finding breaks the feature in ordinary use: an open request goes invisible
after 7 days but keeps blocking new requests. One Medium finding lets the kid's typed name rename a family contact on
every phone. Both are small fixes.

**Run (targeted):**
- Server: `cargo test contact_request` passed 11 tests. The related server tests also passed: 84 of them, covering
  `policy_json_keys_snapshot`, `sms::the_policy_is_the_contracts`, `i18n`, `shell`, `parent_events`,
  `migrations_from_73_in_order`, `pruning_keeps_only_what_retention_allows` and `notifications`. `cargo fmt --check` is
  clean.
- Launcher: `ContactRequestsTest` passed 8 of 8, and `PolicyResponseCompatTest` passed 51 of 51.
- Two throwaway server tests reproduced H1, M1 and L1 (outputs quoted below). They were not committed.

Counts: **C 0 · H 1 · M 1 · L 5 · N 6.**

## High

### H1. An open request older than 7 days is invisible, but it still blocks requests for up to 30 days

**Where:**
- `server/src/handlers/contact_requests.rs:196-205`: `known` counts any open row for the number, and `open` counts
  every open row, with no age bound.
- `contact_requests.rs:280-286` (`policy_rows`) and `:341-346` (`load`): only the last 7 days are sent or shown.
- `server/src/parent_events.rs:1179-1185` (`waiting_count`): the digest counts every open row.
- `contact_requests.rs:393-399` (`prefill`) and `:448-451` (`mark_added`) also act on rows nobody can see.

**Scenario** (reproduced by a throwaway test against this branch):
1. The kid asks for five people, and the parent doesn't decide within a week. That's ordinary: the parent saw the push
   and decided "not now".
2. On day 8 the card is empty (`card shows stale: false`), the policy's `contact_requests` is `[]`, and the phone has
   dropped its records (`CONTACT_REQUEST_KEEP_MS`).
3. Every new request answers `capped` (`new: {"accepted":false,"reason":"capped"}`) until retention purges the rows
   on day 30.
4. With a single stale open row, asking for the same number again answers `known`
   (`same number: {"accepted":false,"reason":"known"}`).

Neither side has anything to act on: the parent has no row to decline, and the kid sees only "capped" or "known".
The digest's "N contact requests are waiting" also counts rows the card doesn't show.

**Fix:** use one rule for a live request: `state = 'open' AND received_at > now - SHOWN_DAYS`. Apply it in the
`known` and `open` counts, in `waiting_count`, and in `prefill`. A stale open row then acts like a lapsed one, as the
phone already treats it. Alternatively, show every open row on the card whatever its age, but then the policy and the
phone must keep them too, and the 7-day rule changes. The first option is smaller.

**Test:** add five open rows received 8 days ago, then check that a new request is `accepted`, that the same number
is `accepted`, and that the digest count is 0.

## Medium

### M1. "Legg til…" prefills the kid's typed name, and saving renames a family contact on every phone

**Where:**
- `server/src/handlers/calls.rs:744-755`: the prefill takes the name from the request.
- `calls.rs:1890-1893`: `add_contact` runs `INSERT INTO contacts … ON CONFLICT(phone_number) DO UPDATE SET name =
  excluded.name`. The address book is global.
- `contact_requests.rs:199-200`: `known` checks only this phone's contacts.

**Scenario** (reproduced):
1. Per's phone has "Mamma" (+4790000001).
2. Anna's kid asks for +4790000001 with the name "Dum". The request is accepted, since the number isn't on Anna's
   phone.
3. The parent taps "Legg til…" and saves the prefilled form. The form has no hint that the number is already a family
   contact.
4. The shared row is now named "Dum", so Per's phone book shows "Dum" (`shared contact name on Per's phone now: Dum`).

Before this branch the parent typed the name; now it comes from the kid, one tap from a family-wide write. The same
applies to a photo once design 79 adds one (`contacts.photo_hash` is global too).

**Fix (either one):**
- (a) In `prefill`, when the number is already in `contacts`, prefill that contact's name, not the kid's. Show a hint
  that it is the family's contact "X" (the kid called it "Y"), with "Add from the family" as the natural path.
- (b) When the form carries `request`, `add_contact` never renames an existing contact: `ON CONFLICT DO NOTHING`, then
  read the id.

(a) is clearer for the parent. Add a test: a family contact's name survives "Legg til…" from a request with another
name.

## Low

### L1. A `?request=` link combined with `?add_number=` mixes two prefills

**Where:** `calls.rs:560` and `:730-760`. The `add_held` arm wins for name, number and switches, but `add_request`
still adds the hidden `request` and the "Bruk Matrix-ID-en" switch.

**Scenario** (reproduced): `/devices/1/calls?request=<id>&add_number=%2B4799999999` opens Add Contact with
+4799999999. The form also carries the kid's request id and shows "Anna suggested @ola:example.org". Saving marks the
request `added`, and if the switch is ticked, it stores the kid's Matrix ID on an unrelated number.

Crafting the link needs the 128-bit request id, which only the phone and the server know, so this is not exploitable
from outside. It is still an inconsistent state the page can reach.

**Fix:** make the arms exclusive. Ignore `request` when `add_number` or a refused form without `request` is present,
or the other way round.

### L2. A retry can be answered `invalid` for a request the server already holds

**Where:** `contact_requests.rs:176-186` (`check` and the 7-day rule) run before the stored-id lookup at `:212`.

**Scenario:** the first POST is stored but the answer is lost. Then either the default country code changes, which
re-normalises differently or makes the number an emergency number, or the retry crosses day 7 by the server's clock
(phone clock skew). The retry answers `invalid`, so the phone shows "Ikke sendt" while the parent has the request on
the card.

**Fix:** look up `(device_id, id)` first, outside or at the start of the transaction, and answer `accepted` for a
stored id before any other check.

### L3. A short-number request prefills texting on, and the form doesn't carry the cost warning

**Where:** `calls.rs:1814` (`REQUEST_ADD_SWITCHES`) and `device_calls.html` Add Contact.

**Scenario:** the kid asks for "Konkurranse 2160" (a premium SMS code). The card says "Short number - may cost money",
but the Add Contact form it opens has "Kan sende melding til" on by default and no warning. A parent saving quickly
lets the kid text a paid short code. The defaults are §14's, so this is a design-level nudge, not a defect of the
build.

**Fix:**
- When the prefilled number has no `+`, default `sms_out` (and `outbound`?) off.
- Repeat the mark as the number field's hint in the form.
- Raise this with the user against §14's defaults.

### L4. Test gaps (behaviour that is right in the code but unpinned)

- **Escaping in the push and the inbox.** `the_push_and_its_switch` uses "Ola" and "Kari" only. Post a name such as
  `"<&x` and check that the inbox shows it escaped and the push title carries it verbatim, as text.
- **Norwegian words.** `tests::i18n_pages::nb_pages_have_no_english` never sees the card: `seeded_family.rs:363` seeds
  the event but no `device_contact_requests` row. Seed one open request with a Matrix ID, so the card and the
  "Bruk Matrix-ID-en" hint are scanned in Norwegian.
- **Cross-phone decline.** `POST /devices/A/calls/requests/<B's id>/decline` should land on `request_gone` and leave
  B's row open. Also: an unknown device answers 404.
- **Cross-phone mark.** Anna's Add Contact with Per's open request's number must leave Per's request open (by number,
  not only by a forged id).
- **"Add from the family"** marks the open request `added`: `add_family_contact` is untested.
- **The push budget and digest.** A fourth request within the hour (two days' caps across midnight) gets no push of
  its own and queues one digest. The digest's words and count are untested.
- **A decided request** (`?request=<declined id>`) doesn't open Add Contact.
- **A refused Add Contact from a request** (bad number) keeps the hidden `request` and the tick state.
- **Launcher.** `ContactRequests.upload` is untested glue: RETRY stops the loop, and only that record changes when
  another one was added meanwhile. Consider extracting the per-record step into a pure function with a test.

### L5. The phone's emergency check is weaker than §14 says until the screen passes the platform's answer

**Where:** `launcher/.../calls/ContactRequests.kt:112`: `isEmergency` defaults to `{ false }`. The server's list is
static per country (`emergency::is_emergency`).

**Scenario:** a number the platform calls an emergency number but the static set doesn't (a SIM's own list, roaming)
passes both sides. Nothing is added by the request itself, so this is low.

**Fix:** design 79's screen must pass `PhoneNumberUtils.isEmergencyNumber` (or the dialer's check) as `isEmergency`.
Put this in that brief.

## Nits

- **N1.** "Bruk Matrix-ID-en" stores `message_address` but leaves `message_app` at the phone's default (`'sms'` unless
  the parent changed it, `0022_calls.sql`). The ID then feeds Element's room filter (`ElementRooms.kt:62`), but the
  contact's Message button stays SMS. Either say so in the hint, or ask the user whether ticking it should also set
  `message_app = 'element'`.
- **N2.** The event's `happened_at` is the phone's `asked_at` (`contact_requests.rs:238`), up to 7 days in the past.
  This sets where it sorts in the inbox and the "at" in its words. Consider the receive time for the event, and keep
  `asked_at` for the card.
- **N3.** `known` is answered before the cap and doesn't count, so the phone can probe "is this number a contact here"
  without limit. The phone has the full contact list in the policy anyway, so no action is needed. It is noted only
  because the order is deliberate (deviation 3).
- **N4.** `decline` opens a deferred transaction (`begin()`) and SELECTs before it UPDATEs. Under contention the upgrade
  can return `SQLITE_BUSY` and answer 500. That matches the rest of `calls.rs`, so it is only noted.
- **N5.** `ContactRequests.records()` returns an empty list on a decode failure, and the next `add` then overwrites
  the stored list. Acceptable for a 7-day cache. A comment would help.
- **N6.** `contact_requests.rs:296`: the doc comment on `RequestView.mark` says "(QA R2-L5)". The mark is
  `is_emergency`/`starts_with('+')`, so the "Nødnummer" branch is now reachable only after a country-code change. The
  notes say so, and the code comment could say it too.

## Checked and fine

**Auth and limits:**
- The route is in `device_routes.rs`, inside the authed router (`require_device_token` with the per-device limits),
  under its own 1 KiB `DefaultBodyLimit`. The handler reads `Bytes`, so the limit applies; the test checks 413 and 401.
- The decline route is in `build_admin_router`, behind `require_full_auth` and `csrf_guard`.

**Scoping:**
- Every query is scoped by the path's device.
- `prefill` reads `WHERE device_id = ? AND id = ? AND state = 'open'` from the path's phone. The link carries only the
  id, never words or a number. The test checks that another phone's id doesn't prefill.
- The Matrix ID comes only from `mark_added`'s SELECT of the stored, still-open row of this phone, filtered by
  `use_matrix`. The test checks a forged tick with another phone's id.
- `mark_added` is scoped by `device_id` in both the number and the id branch, so it can't touch another phone's
  request.
- `decline` redirects to `#request-{request}` only when a stored (hex) row matched. Otherwise it goes to the fixed
  notice URL, so no header or path injection from the path segment.

**Input checks:**
- The name goes through `calendar::clean` (drops bidi and zero-width, folds space, NFC) and is clipped in chars, with
  1-40 enforced.
- The number goes through `phone::normalize` with the server's own country code, never trusting the phone's
  normalisation, and an emergency number is refused.
- `valid_matrix_id` restricts the charset, so no `?`, `#` or `&`.
- The id is 16-64 lowercase hex (`time_request_id_ok`), on the intake and in `prefill`.
- `asked_at` is clamped to now, and an `asked_at_ms` that doesn't parse answers 400.
- One `BEGIN IMMEDIATE` covers the stored, known, today and open reads and the insert. The event is raised in the same
  transaction, so a retried id gives one row and one event (tested).

**Escaping:**
- Askama auto-escapes the card, the prefilled `value=`, the Matrix hint and the inbox. There is no `|safe` outside the
  provisioning QR, and the test checks `Ola &#60;b&#62;&#38;` on the card and in the form.
- Push words are plain text in JSON.

**Contracts and CI:**
- `contact_request.json` is byte-identical on both sides, and both sides compare it.
- `policy_json_keys_snapshot` lists `contact_requests`. `sms::the_policy_is_the_contracts` strips it explicitly
  rather than loosening the SMS contract.
- Both workflows' path regexes and the root `CLAUDE.md` lists are updated.

**Migration 0133:**
- It is the next number after main's 0132, and no active branch (`sms-glance`, `sms-mms`, `calendar-fresh`) holds a
  0133.
- `migrations_from_73_in_order` is extended, the CASCADE FK and the state CHECK are present, and `server/CLAUDE.md`
  says the next free number is 0134.

**Push:**
- There is its own switch (on by default) with its label key.
- The `PushBudget` is 3 an hour on one key. A past-budget event reuses `budget_digest` and `waiting_count` (with the
  H1 caveat), and the digest leads to `#requests`.
- A decision (add or decline) marks the event read for every parent in the same transaction.

**PWA:**
- Decline returns to `#request-<id>`, and a gone request goes to `#requests`, a section that is always rendered.
- "Legg til…" opens `#add-contact`, and a save lands on `#contact-<id>`. Nothing jumps to the top.
- Auto-refresh is off while Add Contact is prefilled.
- en and nb are complete and equal (`i18n::tests`).

**Launcher:**
- The body encodes exactly per the contract. `encodeDefaults` is off, so a null `matrix_id` is left out.
- The answer mapping is: 401, 408, 429, 5xx and no answer retry; 404, other 4xx and `invalid` are "Ikke sendt" once.
- `reconcileContactRequests` runs only on an accepted fresh policy. It uses the contacts only when calls are managed,
  and the `added` row covers an unmanaged phone or another number.
- An answer shows once (`shown`, then dropped when the phone book is left), and a shown answer isn't re-opened by a
  later policy.
- The records live in CE prefs, read and written under `@Synchronized`, and a failure before unlock reads as empty
  with nothing written. `allowBackup="false"`.
- The upload runs behind the sync mutex, stops at the first RETRY, and changes only that record while it is still
  PENDING.

## The deviations

1. **Accepted: the policy rows carry `added`.** Without it, the phone can't forget a request the parent added under
   another number, or any request on a phone whose calls are unmanaged (no contacts list to match). The contract pins
   it, and older launchers ignore the key.
2. **Accepted: an emergency number is refused outright.** It fails closed: an emergency number is never a contact to
   ask for, and the SMS rules never let one in. The card's "Nødnummer" mark stays as a guard for rows stored before a
   country-code change. See L5 for the phone's half.
3. **Accepted, with L2:**
   - Counting the cap by the server's receive time in the phone's zone is the right call, since the phone controls
     `asked_at`.
   - A request older than 7 days is `invalid`, which matches the phone's own keep time.
   - `known` before the cap is friendlier and leaks nothing the phone doesn't hold.
   - But the stored-id lookup must come before both the checks and the age rule (L2).
4. **Accepted, with H1: a cap of 5 open requests.** It is reasonable and mirrored on the phone. Combined with open rows
   that never lapse, it turns into H1's dead end. Fix H1 and the cap is fine.
5. **Accepted: the wording doesn't name a "+".** Design 79 may move the entry point, and "from the phone book" stays
   true.

## The photo extension plan (`docs/notes/server/calls-and-contacts.md`, `docs/notes/launcher/calls.md`)

The plan is sound as an API shape. The intake ignores unknown keys, an optional `photo` hash is added, and an older
server takes the request without it. The launcher's claim that a null field is left out holds, since `ServerJson`
doesn't set `encodeDefaults`. Conditions for when it is built:

- **Scope the hash to the phone.** The upload route stores per device. The intake accepts `photo` only if this phone
  uploaded that hash: otherwise `invalid`, or the photo is dropped. A hash must never resolve to another phone's upload
  or to an existing family contact photo; the latter would also be an existence oracle.
- **Re-encode and strip metadata on the server.** A kid's camera photo carries GPS EXIF, so treat it like
  `photos::CONTACT_ORIGINALS`. Enforce the size, a per-phone daily count (the 3-a-day cap, applied to uploads too)
  and a storage budget.
- **Purge orphans.** An upload no request names within, say, a day is deleted. A request's photo goes with the
  request's 30-day retention.
- **Serve it only to the PWA.** Use the admin session, scoped by the path's device, never on the phones' listener and
  never through the session-less `phone-avatars`-style route.
- **"Legg til…" offers it behind its own unticked switch**, as the Matrix ID. Because `contacts.photo_hash` is global,
  the switch must not overwrite an existing family contact's photo. This is the same issue as M1: fix M1 first and
  apply the same rule.
- **Ordering on the phone.** Upload the photo, then post the request in the same sync. If the photo upload fails, the
  request waits (RETRY) rather than going without its photo, unless the user prefers it to go without.

## Fix round (branch `sms-contact-request`)

- **H1 - fixed.** One rule for a live request, `contact_requests::live_since`: `state = 'open' AND asked_at >
  now - 7 days`, in the intake's `known` and open count, `parent_events::waiting_count`, `prefill`, `from_request` and
  `mark_added`. Test `a_lapsed_request_blocks_nothing`: five open rows 8 days old, then a new request and the same
  number are accepted, the digest counts 0, the lapsed one isn't prefilled or marked.
- **M1 - fixed, both ways.** (a) `prefill` takes the family contact's name when the number is in `contacts`; the name
  field is read-only with "Finnes allerede som «Mamma» - navnet beholdes. Anna kalte det «Dum»." (b) A save carrying a
  live `request` never renames an existing contact (`ON CONFLICT … SET name = contacts.name`), so a forged name changes
  nothing either. The same rule is noted for the later photo. Test `a_request_never_renames_a_family_contact` (two
  phones).
- **L1 - fixed.** One prefill source: a held number's link wins and drops `request`; a refused form carries its own
  `request` or none.
- **L2 - fixed.** A stored id answers `accepted` before `check` and the 7-day rule.
- **L3 - fixed.** A short number starts with "{kid} kan sende melding til kontakten" (`sms_out`) off; calls and the
  phone book stay on, as §14 says. "Kortnummer - kan koste penger" repeats under the number field. For the user:
  should a short number also start with calls off?
- **L4 - fixed.** Tests for: escaping in the inbox and plain text in the push; the Norwegian card and "Legg til…"
  (`i18n_pages`, seeded request); cross-phone decline, an unknown phone's 404, cross-phone mark by number; "Add from the
  family"; the push budget's digest and its words; a declined request not opening Add Contact; a refused save keeping
  `request` and the tick; the launcher's upload step as pure `recordsAfterUpload`/`uploadGoesOn`.
- **L5 - noted** for design 79's screen in `docs/notes/launcher/calls.md`: pass `CallSystem.isEmergencyOutgoing`
  (Android's `TelephonyManager.isEmergencyNumber` plus the static list) as `isEmergency`.
- **N1 - fixed by design 80 M6:** ticking "Bruk Matrix-ID-en" also sets `message_app = 'element'`; the sub-line says
  the Message button opens Element X with it.
- **N2 - fixed.** The event happens at the server's receive time; the card keeps `asked_at`.
- **N3, N4 - kept as they are**, as the review says.
- **N5, N6 - comments added.**

**Follow-ups after the verification of 4fec6751:**
- The live window counts from `asked_at` (clamped to now, refused past 7 days at intake), so it matches the phone's
  `askedAtMs` drop exactly: every `live_since` use, the card's `load`, `policy_rows` and the digest. The day's cap and
  retention keep `received_at`. Test `the_window_counts_from_the_ask` (asked 8 days ago and received 3 days ago:
  lapsed; asked 6 and received 1: live).
- "Avslå" on a lapsed row no longer declines it and lands on `?notice=request_gone#requests`, which always renders,
  never on a missing `#request-<id>` (no jump to the top).
- The kept name's hint and the short number's warning follow the number in the field (`add_kept`, `add_short` from
  the field's number on every render). The name field is no longer read-only: a save carrying a live request with
  another typed name for an existing number is refused in place (400), with the kept name in the field and why, so it
  is never silently ignored. Saving again adds it. The transaction still never renames.
- One `aria-describedby` on the name field: the error line when refused, else the kept-name hint.
