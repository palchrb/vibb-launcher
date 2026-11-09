# Code QA: design 77 step 4, the PWA and server side of SMS (`sms-pwa`)

Branch `sms-pwa`, one commit 31c5a235 on 5a31e8bb, read in full against design 77 revision 3 (D2, D3, D12, D16, D17,
§6.8, §7, §8.5) and against `server/CLAUDE.md`, `docs/notes/server/calls-and-contacts.md`, `notifications.md` and
`pwa.md`. No code changed by this QA.

## Verdict

- **Mergeable after M1 and M2.** No finding lets a sender in, reaches another phone's rows or skips the parent's
  intent. Every new admin route sits behind `require_full_auth` and the global `csrf_guard`; every query binds the
  path's device id; an allow is decided from the stored `device_sms_held` row (class, sender, state, `dropped_at`,
  `expires_at` by the server's clock), never from the form; an emergency number is refused on the allow, on Add
  Contact, on the contact save and in the policy; a letterless name is never added; every template value goes through
  askama's escaping and the prefill link through `form_urlencoded`. Push and inbox words quote and label the sender.
- 0 Critical, 0 High, 2 Medium, 8 Low, 5 Nits.
- `cargo test` (full, on `sms-pwa`): 1799 passed, 0 failed, 0 ignored (720 s). `cargo fmt --check` clean.

## Findings

### Medium

- **M1. The RCS line shows only once SMS is already "Styrt av Vibb", when Google Messages is suspended and the step
  can no longer be done** (`server/src/handlers/calls.rs:377`, `server/templates/device_calls.html:171`).
  `extra: (sms && mode == "managed")` puts "Slå av chatfunksjoner i Meldinger først" under the sub-line of the *stored*
  state. §8.5 says the line is "to do while the mode is still `unmanaged` (Messages is visible then)", and its word is
  "først". With the line hidden until after the switch, the scenario is: the parent taps "Styrt av Vibb" (the
  recommended choice); `restrictSms` suspends the SMS packages (§8.3); only then does the page say to turn off chat
  features in Messages, which is now hidden. Meanwhile every Android contact with RCS sends to Google's servers and the
  kid gets nothing, silently, until the sender's phone falls back (if ever). The parent has to go back to "Ikke styrt",
  find Messages, and come back. The ⓘ with Google's deregistration form (§8.5 path 2, for a phone already locked down)
  is missing too.
  **Fix:** show the line while SMS is *not* managed and the phone has the engine (worded as a step before choosing:
  "Før du velger Styrt av Vibb: slå av chatfunksjoner i Meldinger"), keep it under the managed state, and add the ⓘ
  with the deregistration link. Test: the line on the page for `unmanaged` + engine.

- **M2. Under a flood the digest undercounts, and held messages past `EVENTS_AN_HOUR` reach neither the inbox nor the
  digest** (`server/src/parent_events.rs:1021`, `:1109-1173`, `server/src/sms.rs:435`). `raise` returns `None` once a
  phone has 20 events of the kind in the hour, *before* the budget and `budget_digest`. The digest row itself is one of
  the 20. So in the flood the budget exists for (the phone holds up to 30 numbers + 20 names a day, §D16), events 1-3
  push, event 4 opens the digest, 5-19 count into it, and from the 20th on nothing is stored or counted: the digest
  pushes "Anna: 16 messages are waiting" while 47 wait, and a real person's held message arriving later that hour has
  no inbox row at all. `raise_held` runs only at a row's first sight, so the lost events never come back. The design
  says "the rest go to the inbox only" and the digest says how many wait. The card itself is correct, so the parent
  who opens it sees everything; the push and inbox are wrong.
  **Fix:** word the digest from the live count at push time (`device_sms_held` rows still waiting: `state = 'held'`,
  `dropped_at IS NULL`, `expires_at > now`) instead of an increment, and let `budget_digest` run (open or bump the
  digest) even when the event itself is past `EVENTS_AN_HOUR`. That also makes a digest whose rows were all decided
  say so (see L3). Test: 25 held rows in one post; the digest's push says 25 (or the live waiting count).

### Low

- **L1. "Slett nå" has no confirm** (`server/templates/device_calls.html:59`). Deviation 7. The delete is
  irreversible (the phone deletes the body at its next policy), and it is a full-width row directly under "Tillat
  meldinger fra Mamma" / "Opprett kontakt…": a mis-tap loses the message the parent meant to let in. Every other
  delete on the page (Remove Contact, M6; Slett alle som venter) and every delete in the PWA's notes ("Every delete is
  a `<details>` confirm", `pwa.md`) is behind a `<details>` confirm. **Fix:** a `details.confirm` like Remove
  Contact's, posting to the same `…/drop#held-<id>`.

- **L2. The intake accepts `state: "held"` (and its moves) for classes with no allow path** (`server/src/sms.rs:288`,
  `:435`). `refusal` checks the state and the class separately, so a `held` row of class `malformed`, `empty`,
  `group` or `bad_location` (sender `""`) is stored and raises `sms_held`: the push reads "Anna: message from unknown
  number " with an empty number, and the card shows a row with "Slett nå" only. D4 rule 8 and §7 say those classes are
  `blocked` only. Only a misbehaving or tampered phone posts it. **Fix:** refuse (400, `reason: "state"`) any state but
  `blocked` for those four classes; a contract row in `sms_contract.json`'s `intake`.

- **L3. The digest is an inbox row from the moment it opens, with a future time, never read** (`server/src/
  parent_events.rs:1160-1170`). Deviation 4. Its `happened_at` is the hour's end, so the inbox shows "Anna: 1 message
  is waiting · 15:02" at 14:10, beside the over-budget rows it counts (each its own inbox row already), it adds one to
  every parent's unread badge (`push.rs:1117`'s count), and nothing marks it read when its rows are released, expire or are
  dropped. **Fix:** keep the row as the push's carrier but leave it out of the inbox and the badge
  (`dedup_key NOT LIKE 'digest:%'`), or insert it read for every parent; with M2's live count it can also skip its push
  when nothing waits any more.

- **L4. The parent's own allow or "Slett nå" leaves the event unread until the phone posts the new state**
  (`server/src/sms.rs:451`, `server/src/handlers/calls_sms.rs:396-578`). For a phone offline for hours the inbox keeps
  an unread "message waiting" the parent already handled, and the row on the card says "Slettet"/"Tillatt". The design
  names only the phone's states, so this is a polish point. **Fix:** mark the row's `sms_held` event read for every
  parent in `allow_held`, `drop_held` and `drop_all_held` (the same `INSERT OR IGNORE` as the intake).

- **L5. "Slett alle som venter" and `sms_held_drop` include stale rows** (`server/src/handlers/calls_sms.rs:561`,
  `server/src/sms.rs:147`). Drop-all sets `dropped_at` on every `held` row with no `expires_at` filter, and the policy
  sends at most 100 ids ordered by `dropped_at`. A phone that failed to post its expiries leaves old `held` rows (up to
  500, 30 days); one drop-all then puts them all at the same instant and the 100-id cut can leave out rows that still
  wait on the phone. **Fix:** `AND expires_at > ?now` in drop-all (and in the policy's drop list); a test with a stale
  row.

- **L6. A segmented radio saves on every arrow key** (`server/templates/device_calls.html:168`,
  `server/static/autosave.js:57-66`). Keyboard focus in a radio group moves *and* checks with the arrows, firing
  `change` each time; `autosave.js` defers only a `<select>`'s keyboard changes. From "Av" to "Ikke styrt" with the
  arrows the form posts "Styrt av Vibb" first (a policy and an SSE nudge for a state nobody chose, and a page reload
  that drops the focus). **Fix:** treat a radio changed from the keyboard like a keyed select (save on Enter/Space or
  focus leaving the group), with a case in `jstest/autosave.test.js`.

- **L7. The phone's own number accepts a short code or an emergency number** (`server/src/handlers/calls_sms.rs:611`).
  `phone::normalize` keeps 3-6 digits as is and `sender_ok("number")` takes them, so "112" or "2222" can be stored as
  SIM 1's number. D12 then treats that address as "us" in the group rule, so a group with "2222" in it is accepted. No
  SIM's MSISDN is a short code. **Fix:** require the E.164 form (`starts_with('+')`) for an own number, refusing in
  place like a bad number.

- **L8. The push TTL is a fixed 24 h, not "until `expires_at`"** (`server/src/parent_events.rs:158`). Deviation 3. A
  held row posted late (the phone was offline 20 h) is pushed with 24 h of TTL, so a parent's device that comes online
  later still shows "a message is waiting" for a message the phone deleted hours ago (the inbox words do drop "Allow
  within" once expired). Acceptable for v1; a per-delivery TTL of `expires_at - now` (floor a minute) would match §7.

### Nits

- **N1.** `calls_sms::load` runs one `contact_by_number` query per waiting number row (up to 100). One query for the
  numbers of the page's rows would do.
- **N2.** A held number that is another phone's contact gets "Opprett kontakt…"; Add Contact then reuses the global
  contact and renames it for every phone (existing `add_contact` semantics). "Legg til fra familien" with "Kan sende
  melding til" alone would be the narrower path there.
- **N3.** `allow_held` answers `held_gone` ("that message isn't waiting any more") also for a number row that has no
  contact (its action is "Opprett kontakt…", a link). Unreachable from the page; a clearer code would help a stale tab.
- **N4.** The "Ventelisten er full" finding names "Slett alle som venter" in words; §7 asks for the action with it. The
  card is at the top of the same page, so a link to `#held-drop-all` is enough.
- **N5.** The rolling-hour budget and the digest at "oldest pushed + 1 h" can let five pushes fall within one rolling
  hour (three, the digest, then one more pushed as soon as the oldest leaves the window, and a second digest). Within
  the spirit of R2-M3; worth a sentence in `notifications.md`.

## The seven deviations

1. **The MMSC table compiled in and empty, not parent-editable: accepted.** §7 says exactly "a compiled-in table …
   Not parent-editable in v1. A change is a server release." Empty is step 0's state, not a choice: with no row every
   MMS fails closed (D10) and the page says "MMS kan ikke sjekkes på dette SIM-kortet". It must be filled from step 0
   before the release that ships steps 1-4 together (§9), or no picture reaches the kid from anyone; that belongs in
   PLAN's release checklist.
2. **Shorter push words: accepted.** "Anna: message from unknown number +4791234567" / "Anna: melding fra avsendernavnet
   «DNB»" keep what R2-M2 asks for (the sender labelled by its type, a name in «», the phone named first) and fit
   `TITLE_MAX` with the longest number and name (`parent_events` tests both). The summary keeps "Tillat innen {left}".
3. **A fixed 24 h TTL: accepted for v1**, with L8's note.
4. **The digest as a `parent_events` row: accepted as the mechanism** (it reuses the push queue, its held
   `next_try_at`, the switch and the subscriber's language), **not with its side effects**: L3 (inbox row with a future
   time, unread badge, never read) and M2 (the count and the `EVENTS_AN_HOUR` cut).
5. **Four findings as rows, not Attention items: accepted.** Parse failures (a count since install), the MMS server
   list (only a server release fixes it, and it is true for every phone until step 0 fills the table), mobile data
   (the phone's own setting) and the role kept under "Ikke styrt" (the phone hands it back itself, and the channel is
   unfiltered by choice) would be permanent or unactionable Attention noise. The three that mean messages are lost or
   held back (role not ours, the full pool, the own number) stay warnings, which is what matters.
6. **The hidden `switches=on` marker on Add Contact: accepted.** It keeps the one meaning a post without the switches
   had (every flag on, as Add from the family and an older cached page post) while letting "Opprett kontakt…" save
   exactly one switch; a missing switch is off only when the form says it carries them. The test covers both posts.
7. **"Slett nå" without a confirm: not accepted** - L1.

## Checked and fine

- **Authorization and scope.** The six new routes are in `build_admin_router` under `require_full_auth`,
  `settings_log::log_saves` and the global `csrf_guard`; each reads and writes with the path's device id
  (`device_sms_held WHERE device_id = ? AND held_id = ?`), so another phone's held id lands on `held_gone`
  (`delete_now_and_delete_everything_waiting` checks it for the drop). `held_id` is checked as 16-64 lowercase hex
  before it reaches a redirect. One family, every parent sees every phone, as everywhere else.
- **The allow from the stored row** (`calls_sms::allow_held`): a number row turns on `sms_in` of the contact with
  exactly that number on this phone (nothing else), never for `emergency::is_emergency`; a name row adds
  `clean_name(sender)` only when `valid_name`; a row dropped, decided or past `expires_at` by the server's clock is
  refused. A release is the phone's, for the exact sender (D16), so no allow releases another sender's messages.
- **Emergency numbers**: refused on the allow, stored off on Add Contact and the contact save
  (`sms_in && !is_emergency`), shown disabled with D3's reason, and masked again in the policy
  (`device_api.rs:665`). Short numbers normalise only as 3-6 digits, so "+47112" never exists to slip past the exact
  match.
- **Names (D17)**: `valid_name` (1-11 after the shared cleaner, a Unicode letter), uniqueness by `sms::fold` in the
  writer and the primary key, 50 a phone, the refusal in place (400) with what was typed, Remove by the fold; the
  policy drops any stored row that fails the rule.
- **Escaping**: the prefill (`?add_number=`, at most 32 characters, escaped into `value=`; `a_prefill_is_escaped`),
  the sender labels, contact names and fold values go through askama; the link's number through
  `form_urlencoded::byte_serialize`; push titles are plain text from the intake-cleaned sender.
- **SQL**: every value is bound; the one `format!` into SQL is `push::queue`'s compiled-in switch column. The intake
  stays one `BEGIN IMMEDIATE` transaction with the event and the read marks in it, the push woken after the commit.
  Each PWA write is one transaction scoped to the phone, 500 on a database error, `command_notify` on success.
- **Intake limits** (unchanged from step 1, re-read): 16 KiB body, 50 rows, the id/kind/class/sender/time/state
  checks, forward-only states, 500 held and 500 blocked rows a phone, the capped line in the security log.
- **Migration 0132** adds a nullable `push_budget_key` and a partial index matching the budget query; its row in
  `migrations_from_73_in_order`; `server/CLAUDE.md`'s next free number moved to 0133.
- **The capability gate**: a choice the phone can't do is a disabled radio (never the stored one) with "<choice> -
  Oppdater Vibb One først", and `save_settings` still refuses it server-side unless the phone reports
  `sms_engine_v1` or the pair is unchanged; nothing in 0132 touches D2's mapping.
- **Findings**: only from a launcher reporting the engine and only while SMS is managed (but "role kept" under "Ikke
  styrt"); "role not ours" waits for the pending rule; "until" is the earliest `expires_at` of the full pool's rows.
  The device page's row counts rows still waiting by the server's clock and blocked rows of 7 days.
- **PWA rules**: every new form carries its fragment (`#held-<id>`, `#held`, `#sms-settings`, `#sms-names`), the
  refusals render in place (400) with what was sent, `?notice=held_gone#held` lands on a section that always exists,
  auto-refresh is off on every refusal and on the prefill (`every_form_on_every_page_lands_on_its_own_group` walks the
  new forms). en and nb have every new key. Nothing here is on the kid's screen. Every new restriction is liftable
  (switches, names, own numbers, the channel row); the emergency `sms_in` is the designed exception. At 390 px the
  segmented control gives each segment about 115 px with wrapping allowed (`overflow-wrap: anywhere`, 40 px minimum
  height), and the held actions are full-width rows.

## Tests

The new tests mostly test behaviour through the router (`tests::sms_page`: each allow path's stored flags and the
policy, the escaped prefill, drop and drop-all into `sms_held_drop`, the name rule, the SMS settings round trip, the
contact group, the budget's pushes through `send_due`, the findings' words and warning count). Two restate the code:
`calls::tests::a_contact_row_says_which_way_calls_go`'s new rows assert the summary string the function builds, and
`calls::tests::findings_read_as_short_local_notes` now copies `needs_attention`'s list into its own `matches!`. Missing cases worth a test:

- the digest past `EVENTS_AN_HOUR` (25 held rows in one post), M2;
- the RCS line's visibility per state, M1;
- an allow posted against *another phone's* held id (the drop is tested, the allow isn't);
- read marks for *every* parent (two accounts; today's test checks that some read exists) and for `expired` and
  `dropped` (only `released` is tested);
- Add Contact with `switches`, `sms_in` on and the number "113": stored off (the contact save is tested, the add isn't);
- a held sender name `"<&x` (a valid name in `sms_contract.json`'s `clean`) escaped on the card and in the inbox;
- the intake refusing `held` for a `malformed` row, once L2 is fixed.

## Fix round

On `sms-pwa`, after this review. Every finding is fixed except L8 and N2/N4, explained below. Migration 0132 is
unchanged; `sms_contract.json` is unchanged (L2, below).

- **M1 - fixed.** SMS's RCS step shows *before* "Styrt av Vibb" is chosen: under "Ikke styrt" and "Av" on a phone
  that reports `sms_engine_v1` it reads "Før du velger Styrt av Vibb: slå av chatfunksjoner i Meldinger." (Messages is
  visible then), and under "Styrt av Vibb" "Slå av chatfunksjoner i Meldinger først." as before. Its ⓘ explains why
  (other Android phones send over the internet, Vibb One never gets it) and links Google's form
  `https://messages.google.com/disable-chat` (§8.5 path 2) for a phone already locked down, saying the form's code
  arrives as a held message to let through. Without the engine there is no line ("Styrt av Vibb" can't be chosen).
  Test: `sms_page::the_rcs_step_shows_before_and_under_managed` (unmanaged, off, managed, no engine).
- **M2 and L3 - fixed, by changing the digest's mechanism.** The digest is no longer a `parent_events` row: it is a
  push of its own (`push_deliveries.kind = 'digest'`, `push::queue_digest`), anchored on the phone's newest `sms_held`
  event (for the phone, the switch and the tap), held until the hour's end, at most one queued a phone. `send_due`
  words it when it is due from the live count (`parent_events::waiting_count`: held, not dropped, inside the window by
  the server's clock) with `parent_events::digest_words`, and drops it (`nothing_waiting`) when nothing waits any more.
  `raise` opens the digest also for an event past `EVENTS_AN_HOUR`, so a flood's 25 messages are counted as 25. With
  no row there is no inbox entry with a future time and no unread badge (the anchor's inbox row is the event's own;
  the inbox's delivery lines leave digest deliveries out). Tests: `sms_page::the_push_budget` (no digest row, the
  count after a release), `the_digest_counts_past_the_hourly_cap` (25 held, 20 stored, "25 messages are waiting"),
  `a_digest_with_nothing_waiting_isnt_sent`.
- **L1 - fixed.** "Slett nå…" is a `details.confirm` ("Slette meldingen fra {sender}?") with the Cancel include, posting
  to the same `…/drop#held-<id>`. Test: `delete_everything_waiting_leaves_expired_rows_out` checks the drop form sits in
  the row's confirm.
- **L2 - fixed in the intake.** `sms::refusal` answers `reason: "state"` for any state but `blocked` of the classes
  `malformed`, `empty`, `group`, `bad_location`. Not added to `sms_contract.json`'s `intake`: those rows test the sender
  rule alone (both sides run them), and the phone never posts such a row held (its rule 8 blocks), so a row with a
  state would need a launcher change for a server-only check. Test: `a_held_row_without_an_allow_path_is_refused`.
- **L4 - fixed.** `allow_held`, `drop_held` and `drop_all_held` mark the rows' `sms_held` events read for every parent
  in their transaction (`calls_sms::mark_read`). Test: `every_settling_marks_the_event_read_for_every_parent` (two
  parents; `released`, `expired`, `dropped` from the phone, the parent's allow and drop; a waiting row stays unread).
- **L5 - fixed.** "Slett alle som venter" and "Slett nå" take only rows inside their window by the server's clock, and
  the policy's `sms_held_drop` leaves out rows past `expires_at`. Test: `delete_everything_waiting_leaves_expired_rows_out`.
- **L6 - fixed.** `static/autosave.js` (v=3) treats a radio group like a keyed select: a radio checked by an arrow
  key waits; Enter or Space saves it, and so does the focus leaving the group; moving within the group keeps the wait;
  a pointer press ends the keyboard's turn, and a click saves at once. Tests: three cases in
  `jstest/autosave.test.js`.
- **L7 - fixed.** An own number must be E.164 (`+…`) after `phone::normalize`; "112" or "2222" is refused in place
  ("telefonens eget nummer er et helt nummer, ikke et kortnummer eller nødnummer"). Test: `the_own_number_is_a_full_number`.
- **L8 - kept, explained.** The TTL is per kind (`Kind::ttl`, one header per delivery built from the kind), so a
  per-event "until `expires_at`" would need the expiry carried on each delivery. 24 h is the window itself, so the
  push service drops a push at most 24 h after it was queued; a push shown late says "14:02" without "Tillat innen"
  once expired, and the card says "Utløpt". Worth doing with the next push-layer change, not here.
- **N1 - fixed.** The card reads the phone's contacts once (`contacts_by_number`).
- **N2 - kept.** "Opprett kontakt…" for a number that is another phone's contact reuses the family's contact (the
  name is shared), as every Add Contact does; the new phone gets only "Kan sende melding til". "Legg til fra familien"
  with narrow switches would be a second form for a rare case.
- **N3 - fixed.** A number row without a contact answers `?notice=held_no_contact` ("Det nummeret er ikke en kontakt
  på denne telefonen ennå. Bruk Opprett kontakt.").
- **N4 - kept.** The finding is a warning `Note` (title and sentence; its only extra is the ⓘ fix text); the card with
  "Slett alle som venter…" is the first group on the same page, right under the warning.
- **N5 - noted** in `docs/notes/server/notifications.md`: within one rolling hour three pushes, the digest, and one
  more as soon as the oldest leaves the window can come; within R2-M3's spirit.
- **Missing tests** - added: the digest past the cap, the RCS line per state, an allow against another phone's held
  id (`an_allow_never_reaches_another_phones_row`), read marks for every parent and every move, Add Contact with
  "113" (`add_contact_never_lets_an_emergency_number_text_in`), a held name `"<&x` escaped on the card and in the inbox
  (`a_hostile_sender_name_is_escaped`), the intake refusing `held` for `malformed` and its kin.
