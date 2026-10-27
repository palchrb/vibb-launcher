# QA 72 (design): calendars without sync apps - paste a link in the PWA

Note a2f64349 (`docs/design/72-calendar-feeds.md`); against `main` at 4fa63dd5, the provider's documented write rules
and RFC 5545. Read-only; no build run.

**Verdict: revise first - a small revision.** The shape is right and fits the user's "paste a link and that's it":
the server fetches through the RSS client's rules, the phone writes one read-only LOCAL calendar per link through the
sync-adapter URI, the per-series diff with the feed rev written last is resumable, and the password never touches the
kid's phone. Every claim about today's code checks out (below). Two things must change before the brief: the decoder
runs before unfolding, which breaks æøå split at a fold (H1), and a deleted phone leaves its sources fetched forever
(H2). Five Mediums are a paragraph each; no second design QA is needed if the note takes them in.
**2 High, 5 Medium, 12 Low, 8 Nits.**

## Claims checked against the code (all hold)

`music_net`: `Kind` per-kind cap and timeout (`music_net.rs:30-72`; `Kind::Calendar` is new, `Feed` is 20 MB/120 s,
`:22,54,66`, so the 5 MB cap is new and fine); `Reach::Public`'s resolver drops private answers at connect (`:7-11`);
`pinned_to` + same-host redirects (`:104-131,374-391`); `SourceRequest` has no `Debug` (`:84-87`); errors `without_url`
(`:488`). `music_sweep::backoff` is exactly 15 min · 4^(n-1) capped at the interval (`music_sweep.rs:492-496`, daily
after 7 days `:11`); `lan_addresses` is `pub` (`:2425`). `versioned_json` is private in `handlers/music_api.rs:110`
(made `pub(crate)` as the note says). `policy_json_keys_snapshot` pins `calendar` as a literal (`tests/device_api.rs`
~`:483-492`) - `feeds: []` goes there, a feed row's keys in a calendar test; `PolicyResponseCompatTest:593-613` reads
`calendar` leniently through `calendarPolicy` (`CalendarPolicy.kt:70-80`). `ensureKidCalendar` is at
`CalendarStore.kt:423-455`; `vibbLocal` needs the NAME too (`:215`), so `vibb-feed-*` calendars under "Vibb One" never
stop "Min kalender" being made; `kidCalendarOf` needs access ≥ 500 (`KidEvent.kt:41`) and the PWA refuses LOCAL
(`handlers/calendar.rs:311-314`), so a link is never the kid's calendar. `calendarKey` is SHA-256 over type NUL name NUL
NAME, 16 hex (`CalendarKey.kt:13-17`): the server can compute `LOCAL\0Vibb One\0vibb-feed-<uid>`. "Own" reminders read
`Reminders` rows of METHOD default/alert/alarm, ≤ 120 min (`CalendarStore.kt:367-375`, `Reminders.kt:14,37`). Sealing:
`seal_with`/`open_with` with caller-built AAD (`music_secret.rs:63-108`); `calendar-v1:` and `caldav-v1:` are distinct
from `storytel-v1:`/`spotify-v1:`. `encoding_rs`, `quick-xml`, `chrono-tz` (all zones), `flate2` are in `Cargo.toml`.
0104 is `pwa-i18n-1`'s (`0104_admin_lang.sql`); the hook site `MdmSyncWorker.kt:143,161` is right. Writing `_SYNC_ID`,
`SYNC_DATA*`, `ORIGINAL_SYNC_ID`, `CAL_SYNC*` is a sync adapter's; a plain app may act as one for its own LOCAL account
with `CALLER_IS_SYNCADAPTER` + the account params (the provider checks no signature) - what the kid's calendar already
does. `retention::Budget` (`retention.rs:66`) has nothing to add: the set is replaced, not accumulated.

## High

**H1. Unfold the bytes, then decode.** §2.1 decodes the body (charset, else UTF-8 with U+FFFD) and §2.2 unfolds the
text. RFC 5545 §3.1 warns that simple generators fold inside a UTF-8 sequence and that unfolding must restore it: a
fold through "å" (2 bytes) becomes "��" in the kid's title. **Fix:** unfold on bytes (CRLF or LF + SP/HTAB;
the same bytes in every ASCII-compatible charset), then decode the whole by BOM > `Content-Type` charset > UTF-8 lossy
(as `music_sources.rs:347-361` does). A fixture row: "skoleåret" folded between the two bytes of "å".

**H2. A deleted phone orphans its sources.** §2 deletes a source "in the same transaction" as its last unsubscribe,
but `DELETE FROM devices` (`handlers/devices.rs:2853`, FKs on, `main.rs:1592`) cascades `device_calendar_feeds` only:
the source, its sealed URL and its event texts stay, fetched hourly for nobody - against §9's "removing the link
deletes it all at once". **Fix:** an `AFTER DELETE ON device_calendar_feeds` trigger deleting a source left with no
row (the remove route then needs no code of its own), and a test: delete the phone → the source is gone.

## Medium

**M1. An empty but valid feed wipes the kid's day.** A school server's broken session often answers 200 with a bare
`VCALENDAR`; §2 keeps the set only on a *failing* fetch. **Decide:** a 200 with zero events when the last good set had
any is held (`error: empty`, "Lenken ga ingen hendelser - viser forrige"), and accepted at the third such fetch in a row
or 24 h later. The add check still accepts an empty calendar.

**M2. A series the provider refuses loops forever.** §4.2 skips it and leaves `CAL_SYNC1` old, so every sync GETs the
whole document (`If-None-Match` is the old rev) and retries the insert, indefinitely. **Fix:** tell the provider being
gone (`IllegalStateException`/`null` client, a process death - rev stays old, as written) from a row it refuses
(`IllegalArgumentException`/`SQLiteException`: logged with the series' index, never its text, counted in `CAL_SYNC3`);
write the rev after a pass whose only misses were refusals. `FeedPlanTest` row.

**M3. "Paste a link and that's it": the name is optional.** Default it to `X-WR-CALNAME` (through `calendar::clean`,
60), else the host; the parent renames on the row. §2.2 reads no `X-` line today - read that one (and see L6).

**M4. An override's own zone isn't on the wire.** §2.3 converts EXDATE/RDATE/RECURRENCE-ID into DTSTART's zone, not an
override's `DTSTART`/`DTEND` (Google moves an instance with another TZID or `Z`). **Fix in the contract commit:** an
override's start/end are converted into the series' zone too (one instant each) and written with the series'
`EVENT_TIMEZONE`; an override whose all-day-ness differs from its master becomes a standalone event.

**M5. Step 2: a refused password must stop, and Test is rate-limited.** §6 has no rule for a 401/403 in a routine
fetch; retried hourly with backoff, it can get an Apple ID locked. **Fix:** a 401/403 marks the account `login_refused`
(no request until Test or a new password; every phone's status line says so); Test at most once per 10 s per account
and no retry after a 401. The REPORT's size cap stated (the 5 MB of `Kind::Calendar`, `too_big` keeps the set).

## Low

- **L1.** `SourceResponse` has no `Content-Type` (`music_net.rs:136-147`): add `content_type` (cut to 200), to the
  `Debug` and the canned test source.
- **L2.** The http rule's classes are syntactic (`config.rs:261-291`): `http://nas.home.arpa/…` or a split-DNS name is
  "Public" and refused. Judge plain http by `lan_addresses` (LAN only → the warning; Tailscale fine; else refused). And
  refuse an https → http redirect for `Kind::Calendar` (the public rule allows it, `music_net.rs:355-368`).
- **L3.** The uid hashes "the normalized URL" - define it: scheme and host lowercased, default port and fragment
  dropped, `webcal(s)` → https, path and query byte for byte (a token is case-sensitive).
- **L4.** Duplicate UIDs: bad generators reuse one UID for distinct single events; keep both when neither recurs and
  their DTSTARTs differ (uid + `~` + 8 hex of DTSTART). A synthesized UID also hashes DTEND and LOCATION. A UID over
  255 bytes → its hash.
- **L5.** Say each: `RECURRENCE-ID;RANGE=THISANDFUTURE` read as a single-instance override (as ICSx5); `RDATE;
  VALUE=PERIOD` dropped (`bad`); a cancelled recurring master drops its overrides too; a VALARM with `ACTION:EMAIL`
  skipped; `\N` unescaped like `\n`; `METHOD` and `DTSTAMP` unused (a feed without them is fine).
- **L6.** Floating times: the server can't rewrite a floating (or unknown-zone) `UNTIL` to UTC - the phone does, with
  the zone it writes in. `X-WR-TIMEZONE`, when Olson, is a better zone for a floating feed than the phone's. Outlook's
  "Customized Time Zone" and display-name TZIDs ("(UTC+01:00) Amsterdam, …") land in `unknown_tz` - fine, say so.
- **L7.** A landing for the EXDATE [verify]: if §6y shows a form ignored, write each exclusion as a cancelled
  override row (`ORIGINAL_INSTANCE_TIME` + `STATUS_CANCELED`) - the plan already makes such rows.
- **L8.** Apply cost: one `applyBatch` per series is up to 2000 binder calls and provider notifications at a first
  apply or a mapping bump. Pack whole series into batches of ≤ ~200 operations (atomic per series still); §6y times a
  2000-event first apply. A feed whose series are all new at three fetches in a row (UID churn) → status line and a
  6 h interval.
- **L9.** "Feeds gone" under the lenient parse: a `feeds` element that doesn't read means delete nothing this sync
  (`calendarPolicy` drops bad rows silently today).
- **L10.** Every Calendars/Events/Reminders write *and delete* through the sync-adapter URI with `ACCOUNT_NAME` +
  `ACCOUNT_TYPE` (a plain delete only marks `DELETED=1`, and `CAL_SYNC1`/the rename are sync columns); the calendar
  gets `OWNER_ACCOUNT` as the kid's (`CalendarStore.kt:445`).
- **L11.** No music key (`main.rs:130`, `Option`) → no link can be sealed: the Add form says why (as Storytel).
- **L12.** The parent's copy: the page's "i" and `calendar.preview.footer` (`nb.json:112`) say what the server now
  holds; a day more than 60 days back shows nothing from a link (Dagen pages back without end); `calendar.md:4,75`.

## Nits

N1. `rev` hashes the events (and a series' rev the series without its `rev`), not a JSON holding itself. N2.
`CAL_SYNC1` = the body's `rev`, not the policy's (benign either way; `DnsFilterEngine.kt:190` writes the policy's). N3.
`versioned_json`'s log says "music" (`music_api.rs:138`). N4. Fetch now's 5 minutes are per source, not per link. N5.
All-day `DTEND` is exclusive; `DTEND ≤ DTSTART` → one day / zero length. N6. The ICSx5 hint can add that Dagen already
shows a duplicate once (`DayModel.kt:144`). N7. The real itslearning fold's bytes are unseen (the e4-e5 run saw ICSx5's
output): capture the line before writing `itslearning.ics`. N8. Server is M, not S-M (parser, zones, scheduler, page).

## The decisions (recommendation first)

1. **Per phone, fetch shared - agree.** The dedupe is one `UNIQUE` and H2's trigger; 10/50 caps fine.
2. **60 min + Fetch now - agree.** 30 halves nothing a parent notices; Fetch now covers the urgent case.
3. **60 back / 400 ahead - agree**, with L12's line for the parent; the whole past costs bytes for nothing.
4. **Sealed - agree.** The code exists; a lost key costs a re-paste; L11 for no key.
5. **Plain http to a public host refused - agree**, judged by resolution (L2), no https → http redirect.

## What the implementer's brief must carry

- H1 (bytes unfolded, then decoded; the æ/ø/å-split row) and H2 (the trigger + test); M1's empty-feed hold, M2's
  refused-vs-gone split, M3's optional name, M4's override zone (in the contract commit, first), and for step 2 M5.
- The contract `calendar_feeds.json` in both dirs in one commit with the workflows' path lists and root `CLAUDE.md`;
  `policy_json_keys_snapshot` + `PolicyResponseCompatTest`; capability `calendar_feeds_v1`; migration = next free.
- No URL or body text in any log, page, error or settings-log row (host and codes only); `SourceRequest` stays
  without `Debug`; L10's sync-adapter rule for every write and delete.
- i18n nb + en for every word (`calendar.links.*`, the error codes, the statuses); a save is a 303 to `#link-<uid>`
  (no jump to the top); routes behind `require_full_auth`.
- The Lows as written; §6y answers every [verify] (UNTIL, EXDATE forms with L7's fallback, override linking) and times
  L8's first apply. PLAN's queue updated in the merge commit.
