# QA 75 (design, server side): weather on the lock glance

Note dd19c4f2 (`docs/design/75-lock-weather.md`), against `main` at 6539d573, MET's terms, Locationforecast 2.0 and
Nowcast 2.0 docs, MET answers fetched on 2026-11-03 (59.94, 10.76), Kartverket's stedsnavn OpenAPI 1.1 and a live
`/punkt` call. The area is the server, data sources, privacy and contracts; the launcher UI is the other reviewer's.
Read-only; no build run.

**Verdict: revise first - a small revision.** The shape is right and calm: one pure `weather.rs` pinned by one vector
file, the phone a renderer, a per-cell cache fetched on demand, and nothing new leaves the phone. MET compliance holds
point by point. One thing must change before the brief: the Off erase, put in `retention::prune`'s Location Off pass,
would wipe the cache every hour while any phone is Off (H1). Five Mediums are a paragraph each; no second design QA is
needed if the note takes them in. **1 High, 5 Medium, 10 Low, 3 over-design notes.**

## Claims checked (all hold)

- Migration `0122` is free on `main` and on every remote branch (`server/migrations` ends at `0121_time_rule_silent.sql`).
- `models::LauncherUi.lock_wake_on_notification` (`models.rs:284`) is where `lock_weather` goes; `save_wake`
  (`handlers/lock_glance.rs:218-266`) is the pattern: read before, UPDATE, log only on change, `saved(.., anchor)`.
- `music_net`: `SourceRequest.headers` exists (`music_net.rs:101`), and reqwest only adds a client default header when
  the request lacks it, so a request `User-Agent` replaces `net::USER_AGENT`; `SourceResponse` has no `expires` yet
  (`:140-156`, adding it is as the note says); `Kind` gets a variant (`:30-72`); `PhoneAsks` (`:642`) fits both limits;
  `reqwest` has `gzip` on (`Cargo.toml:45`), so `Accept-Encoding: gzip` is sent; `chrono-tz` is there (`:24`).
- `device_api::extra_time_day_zone` (`device_api.rs:2562`) is the zone helper to rename.
- `location_mode` is `off | on_request | interval` (`0025`), the longest interval 240 min (`time_rules.rs:23`), so
  D5's 6 h covers one missed report. `device_locations.mock` exists but is `NULL` from older launchers (`0093`).
- MET sample: `complete` has `apparent_air_temperature` in `instant.details`, `probability_of_precipitation` and
  `probability_of_thunder` in `next_1_hours.details`; 85 entries, 53 hourly (to 2026-11-05T17Z), the last 5 hourly
  ones without `next_6_hours` (beyond the card's horizon, harmless); the first entry is the current UTC hour;
  `Expires` 32 min. Nowcast: `radar_coverage` `ok`, 23 steps, only the first carries `next_1_hours` and temperature,
  the first step 2 min after the fetch; `Expires` 3 min. MET's documented coverage values match §2.1; inside MEPS but
  outside the radar the answer is a 200 with `no coverage`, outside MEPS an error - the note's "4xx = outside" fits.
- MET's terms: a website link counts as the contact; Expires, `If-Modified-Since` = `Last-Modified`, no HEAD, at most
  4 decimals, HTTPS, gzip, 203 = deprecated (log), 429 = reduce at once, 20 req/s per application in total. The note
  meets each. Attribution: CC BY 4.0 with a link to the licence and "changes made" - §4.3 covers it.
- Kartverket `/punkt` has no type filter (only `nord, ost, koordsys, radius ≤ 5000, utkoordsys, treffPerSide ≤ 500,
  side, filtrer`), so the client-side pick with paging is needed. A Tromsø centre call: 370 hits, 278 `Adressenavn`,
  nearly but not strictly sorted by distance - the note's "pick the nearest by `meterFraPunkt`" is right.
- Yr symbols at the pinned commit: `LICENSE` is CC BY 4.0, "Yr weather symbols © 2015 by Yr/NRK"; no trademark clause
  in the README. CC BY 4.0 is one-way compatible with GPLv3 (FSF), so drawables in a GPL repo with the notice are fine.
- Civil dusk, NOAA's formula re-run here at the cells: Tromsø 2026-12-21 13:53 (note: ≈13:54), Trondheim 21 June
  `never`, Longyearbyen 21 Dec `all_day` (max altitude −11.6°), Oslo sunset 16:17 (MET: 16:16). But see L1 for Oslo.
- The cell: 0.02° x 0.04° is 2.22 x 2.23 km at 60° N and 2.22 x 1.52 km at 70° N; two decimals are within MET's four.

## High

**H1. The Off erase must not live in the hourly prune (§3.1 lines 240-243, D13).** `update_location_policy` erases by
calling `retention::prune` (`handlers/locate.rs:2144-2147`), and prune runs every hour (`retention.rs:52`). The note
puts `DELETE FROM weather_cells` / `weather_places` into prune's `LOCATION_OFF` pass, but the cache has no device id,
so that pass can only be "any phone is Off": a family with one phone Off (a younger kid's, a spare) wipes both tables
every hour. Then every cell is refetched before its `Expires` (the stored `next_fetch_at` and `Last-Modified` are
gone - against MET's terms), Kartverket is asked again hourly, and "names cached for good" never holds. Also, prune has
no transaction (each statement on its own, `rows(..)`), so "in the same transaction as the fixes" isn't so.
**Fix:** a `weather_cache::erase(db)` called from `update_location_policy` only when the mode changes to `off` (read
the old mode first; today it only UPDATEs), plus the in-memory nowcast map cleared; prune touches the weather tables
only by age (H1 + M1). The one early MET refetch per cell after an erase is acceptable and rare - say so.

## Medium

**M1. `weather_places` outlives the fixes (D13, §3.1 line 241).** While Location stays "Regularly", places are never
pruned: after a year it is a list of every 2 km cell the family has been in, kept past `location_retention_days`
(fixes are pruned by it, `retention.rs:249-258`), in every backup. **Fix:** `asked_at` on `weather_places` too, pruned
at the default location retention (or 30 days); a name refetch is one cheap call.

**M2. Kartverket inline, behind the one gate (§3.3 lines 288, 309-312).** `Gated` holds one lock across the whole
request - "never two requests at once" (`music_net.rs:602-630`). A first lookup is up to 3 + 5 pages at 15 s each,
all inline in the forecast answer, and every nowcast queues behind it: the very wait §3.3 gives its own gate to avoid,
and "~1 s more" is the best case. **Fix:** look the place up after answering (spawned; `place` is `null` on the first
answer, the next pull has it), cap it at 2 + 2 pages, and use a separate `Gated` for Kartverket or none (it is one
call per new cell).

**M3. The nowcast minutes go stale (§3.2 lines 277-281, D9).** `nowcast_line(rates, ..)` takes no `now`, and an
answer is used up to 10 min old: "Regn om ca. 25 min" can be 10 min wrong, and a "dry_in" can point at a step already
past. **Fix:** keep the cached steps with their times; `nowcast_line(steps, now, ..)` drops steps that ended before
`now`, counts minutes from `now`, and runs at each `/now` answer. A vector with `now` 7 min after the first step.

**M4. One User-Agent for every install (D8).** Every server sends `vibb-server/<v> github.com/palchrb/vibb-launcher`
unless the operator sets `MET_CONTACT` (or a fork changes `SERVER_RELEASE_REPO`). MET blocks by User-Agent, so one
misbehaving install can get the shared string blocked for all, and MET's mail goes to the repo owner. The user chose
"standard", so only mitigations: DEPLOY.md says to set `MET_CONTACT`; one `warn!` at the first MET fetch when it is
unset; and DEPLOY.md says that with it MET can tie the cells it is asked for to that address (the mockup's privacy
text said MET sees the server and a 2 km cell; with the contact it sees whose). Also give P5's
`every_client_says_the_one_neutral_user_agent` a line in `net.rs`'s doc for the exception ("never the server's
version" no longer holds for MET).

**M5. The rule inputs are underspecified (D4, §3.2 line 259).** The implementer must guess: "chance" = the window's
highest hourly `pop` (the mockup: `Math.max`); rule 9's "high" = the window's highest `temp` or the day's (the mockup
uses the day's `hi`); rule 7 = the window's lowest feels-like; rule 6's form on a sleet day (`maybe_rain` or
`maybe_snow`?); missing `pop` = 0. **Fix:** one line per input in D4, and the vectors pin each. Drop `thunder_pop`
from `Hour`: rule 1 reads the symbol only.

## Low

- **L1. Dusk, which crossing (D10, §3.2 line 270).** Oslo on 21 June crosses −6° at 00:29 on the 21st (yesterday
  evening's) and 00:30 on the 22nd; "the dusk of the local date" must be the first downward crossing **after that
  date's solar noon**. NOAA at 59.94, 10.76 gives 17:02 for 2026-11-03, not D10's/§3.5's 17:04 - write the vectors from
  the code with a second reference, not by hand. `sunset_met` is `null` in the `never`/`all_day` rows, and the 2-min
  sunset check never tests the −6° path: add one civil-dusk reference (USNO or timeanddate) for Oslo and Tromsø.
- **L2. Clock skew and `Retry-After`.** Take `now + (Expires − Date)` rather than `Expires` against our own clock (a Pi
  with a wrong clock would otherwise hammer or starve); `SourceResponse` needs `date` beside `expires`. `Retry-After`
  may be an HTTP date as well as seconds.
- **L3. The fix query (D5).** `COALESCE(mock, 0) = 0` (old launchers send `NULL`) and `captured_at` not in the future
  (as prune's own guard, `retention.rs:241`).
- **L4. The per-cell mutex map is never pruned (§3.3 line 294).** Single family: one `tokio::sync::Mutex` for all
  forecast fetches is enough and has nothing to prune.
- **L5. Missing words (§6).** `security.detail.lock_weather.on` / `.off` (as `security.detail.lock_wake.*`,
  `en.json:794-795`), and the link text in `weather_needs_location`, in both catalogs.
- **L6. The place on the lock (Risk 3).** Acceptable as coarse, but `Boligfelt` and `Grend` within 2000 m of the
  cell's centre can name the very estate a kid lives in. Pick `Bydel`, `Tettsteddel`, `Tettbebyggelse`, `Tettsted`,
  `By` first and `Boligfelt`/`Grend` only when none is found.
- **L7. Backups (§3.1).** The Backups page's copies and `pre-migrate-*` / `prerestore-*` keep both weather tables after
  an Off erase, as they keep fixes; one sentence in §3.1 and DEPLOY.md.
- **L8. The `:30` zone row (D3, §7.1).** In Asia/Kolkata MET's hours start at :30 local: "the start of t's hour", the
  period's first hour (06:00) and the 18:00 switch (it happens at 18:30, entry granularity) need defining on the
  forecast hour containing the local time - or drop the row; the family is in Norway.
- **L9. CI (§7.1 lines 621-622).** `music.yml` needs nothing (the music app reads neither file) - say so, since root
  CLAUDE.md lists all three. `launcher.yml`'s regex gains `server/testdata/weather(_symbols)?\.json` and
  `scripts/yr-symbols.py` with its `--selftest`; `server-ci.yml`'s gains the launcher's two copies; root CLAUDE.md's
  CI paragraph lists them too, not only the contract list.
- **L10. Logs.** "never coordinates" also covers Kartverket's failures and `last_error`; `network_error` already
  strips the URL (`music_net.rs:491-495`) - add a test that a failed fetch logs no `lat=`.

## Over-design

- **O1.** The server could send MET's `symbol_code` and let the phone map it (it has the generated table anyway):
  no `server/src/weather_symbols.rs`, no `weather_symbols.json` pair, one CI path fewer. Optional; the ids are fine too.
- **O2.** `updated_at` is stored and never read; `thunder_pop` (M5); the per-cell lock map (L4).
- **O3.** Kartverket's second pass of up to 5 pages at 5000 m (M2): two pages are plenty where so few names exist.

## Privacy, answered

- **What leaves the server:** to MET, the cell (two decimals), the server's IP, the User-Agent (with `MET_CONTACT`
  when set) and the timing of the phones' pulls - so over time a 2 km trail per server (M4). To Kartverket, the cell's
  centre once per cell with the neutral User-Agent, and only inside the Norway box. Nothing else: no device id, no
  altitude, no fix.
- **Logs and caches:** the cell's row id and an error code; `weather_cells` 48 h; `weather_places` forever today (M1);
  backups keep both (L7).
- **Location Off:** erases the right things, at the wrong cadence (H1).
- **One family learning another's:** no. The routes take no coordinates and answer only the asking phone's own newest
  fix; the PWA never shows a cell or a forecast; the server is single-family.
- **The place name on the locked screen:** acceptable, coarse, parent's switch, default off (L6 tightens it).

## Contracts and fit

The two shared files, the policy key (always sent, missing = off, both snapshots in one commit) and the step split are
right; step 1 alone is harmless. i18n is complete but for L5. The PWA switch reuses `save_wake`'s redirect to its own
anchor with `scroll-restore.js`, and the two notes appear under the switch, so a save never jumps. The symbol
script's licence handling (pinned commit, manifest hash, per-file notice, "changes made", the licence text in the
launcher's assets and NOTICE.md) is sound.

## Revision check (689bb9cb)

Against the revised note and §11's map. **All of H1 and M1-M5 are closed** and none adds a defect:

- H1: the erase is `weather_cache::erase` in `update_location_policy`, only on the change to `off` (old mode read
  first); prune touches the weather tables by age only.
- M1: `weather_places.asked_at`, pruned at the longest `location_retention_days` (default 7, `retention.rs:16`).
- M2: Kartverket is spawned after the answer, on its own ungated client, one lookup per cell, 2 + 2 pages.
- M3: `nowcast_line(steps, now, ..)` runs at each `/now` answer.
- M4: the `warn!` when `MET_CONTACT` is unset, DEPLOY.md, and `net.rs`'s doc.
- M5: every input is defined in D4, sleet → `maybe_rain`, `thunder_pop` gone.

The Lows I spot-checked are in too: the dusk after solar noon at 17:02, `Expires − Date`, `COALESCE(mock, 0)`, one
lock, the words. O1 holds as well: the server sends MET's code, and `weather.json` is the one contract file left
(CI paths updated, `music.yml` untouched).

**D5's source order** is sound. The zone is the parent's own place, not a trail, so using it with Location Off is
right, and the Off erase followed by a re-fetch of the zone's cell is fine. A deleted zone clears the column: foreign
keys are on (`main.rs:1804`), zones are hard-deleted (`handlers/zones.rs:686`), and a phone's delete cascades to its
zones. Two Lows:
- **R1.** A deleted zone leaves the phone showing that zone's weather until its next pull, and the answer lives up to
  3 h. Fix: the zone delete nudges the phone (`command_notify`) when it cleared a `weather_zone_id`; the `no_position`
  answer then clears the store (§5.4).
- **R2.** Migrations run with foreign keys on (`0099`'s note). A future rebuild of `device_zones` (copy, drop,
  rename) would set every `weather_zone_id` to NULL without a word. Say so in 0122's comment and in the zones note,
  so such a rebuild saves and restores the column.

**Step 3** is sound as designed: integers checked, used only with its switch on and the mode `on_request`, kept only
as the cache key, and covered by the Off erase. One Low:
- **R3.** Send the cell in a request header (e.g. `X-Vibb-Cell`), not `?cell=`. URLs land in reverse-proxy access
  logs (Caddy, nginx, Tailscale serve); a header doesn't.

Whether the indicator wording is honest is the launcher review's question.

**Verdict: ready for the brief.** The three Lows can go in during the build.
