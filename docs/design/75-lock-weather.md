# 75 - Weather on the lock glance: yr's symbol and the degrees by the date, the day folded out

Status: design note, 2026-11-03, on `main` at 6539d573; to be built on branch `lock-weather` (PLAN queue: "Weather on
the lock glance - APPROVED 2026-11-03"). Both directories. Revised the same day after the two design QAs
(`docs/review/qa-75-design-server.md`, `docs/review/qa-75-design-launcher.md`) and the user's answers (§11 lists what
changed). Changed again during step 1's build (2026-11-03, §12): step 3 dropped by the user, the PWA's rows on a
page of their own (Device › Weather), the symbol script in step 1. Approved mockup: v7, artifact QVvKwiZWkNFmpCTFxf7LYu (`yr-glance7`; its script holds the rule table). The
user, 2026-11-03: "la oss fokusere på lock screen implementasjonen først"; "La oss droppe påkledningsrådet og
refleksrådene ... Barnet kan heller vurdere påkledning selv"; "Liker at det står når det blir mørkt"; "Jeg liker best
den lille pilen"; "for mye med radaren synlig"; "OK vi kjører standard" (MET's User-Agent); "Egen linje under datoen"
(the narrow phone); the Home-zone approach when there is no fresh position.

## 0. The shape

**Phone.** On the lock's glance page, after the date: yr's symbol, the temperature now and, only when the day has
something to say, a few fixed weather words, then a small chevron - "Man. 3. nov.  ☁ 4° · Regn fra kl. 14 ⌄". No
frame, no pill. Where the line can't hold it (the 320 dp Jelly Star), the same mark sits on its own text line right
under the date, words kept. A tap (no unlock: weather isn't private) folds a card out under the clock face's lines -
the date, the weather's own line, the alarm line and "Neste", which belong to the face - and above the tiles and rows:
at its top one line of text only when rain is due or falling within ~90 min ("Regn om ca. 25 min", "Opphold om ca.
45 min"), then morning / afternoon / evening (symbol, degrees, the period's summed precipitation; the current period
highlighted), then the foot "Nydalen · 2° / 7° · Mørkt kl. 17:02". Another tap, or the screen going off, folds it.

**Server.** The phone only ever asks our server (`GET /api/devices/weather`, `GET /api/devices/weather/now`). The
server finds a position (the phone's fresh stored fix; else the zone the parent chose for the weather; D5), snaps it to
a grid cell of about 2 x 2 km, and answers from a per-cell cache of MET Norway's Locationforecast 2.0 (`complete`),
fetched only past MET's `Expires`, conditionally. The words, the periods, the darkness and the nowcast line are
computed on the server, in the phone's own time zone; the phone renders keys, MET's symbol codes and numbers. The
place name comes once per cell from Kartverket's place-name API.

**PWA.** A page of its own, Device › Vær / Weather (`/devices/{id}/weather`, a row on the phone's page beside Apps,
Time and the others; §6): "Vær på låseskjermen" / "Weather on the lock screen", off by default
(`launcher_ui.lock_weather`), and under it "Når posisjonen ikke er fersk, bruk: [sone ▾]" (none by default).

**Out of scope** (decided with the user): clothing advice and reflex hints; radar bars or maps; a weather app and
weather in Kalender (both on the longlist, PLAN "Asked for"); notifications; choosing other places beyond the
fallback zone; an hour-by-hour view.

## 1. Decisions

**D1 - The server computes everything; the phone renders.** The words, the card's periods, low/high, the dusk and the
nowcast line are the server's (`src/weather.rs`, pure). The phone gets a rule key and a form (`{"key": "rain", "form":
"from", "at_ms": ...}`) and maps the pair to a fixed string in `values`/`values-nb`; symbols come as MET's own
`symbol_code` ("partlycloudy_day"), which the phone maps to yr's drawable (§4). *Why:* one implementation of the rule
table and the time arithmetic (DST, the 18:00 switch, midnight), pinned by one vector file; the launcher stays a
renderer, a threshold change is a server release; and the server needs no symbol table at all - it reads thunder,
snow, sleet and rain from the code's own name (server QA O1).

**D2 - Time-dependent answers carry an hour schedule.** The answer's `hours` holds four entries, the UTC hour that
contains "now" and the next three, each with that hour's symbol, temperature, words and which card day/period applies.
The phone shows the entry whose hour contains its clock. *Why:* the phone pulls hourly, but "Regn fra kl. 14" must
become "Regn nå" at 14:00 and the words must switch to tomorrow at 18:00 without a pull. Four entries cover the 3 h
stale limit (D11); past the last one the line hides.

**D3 - The window, the forms, the day.** Everything runs on MET's UTC hours; "local" is the hour's start in the phone's
zone (D12). With `t` the local time of now:
- before 18:00: the day is today and the window is `[max(07:00, start of the forecast hour containing t), 18:00)`;
- from 18:00: the day is tomorrow and the window is tomorrow's `[07:00, 18:00)`.

An hour belongs to the window when its local start is inside it. A precipitation rule's **form**: `now` when its first
wet hour is the window's first hour and `t ≥ 07:00`; `today` when it is the window's first hour and `t < 07:00`;
`from` (with that hour's start) otherwise; `tomorrow` (no hour) whenever the day is tomorrow. The other rules have
`today` and `tomorrow` only ("Veldig kaldt", "Veldig kaldt i morgen"). *Why:* the window starting at the current hour
retires rain that has already stopped; "Regn i dag" is the mockup's "rain already at 7"; after 18 a bare "Regn fra kl.
14" under today's date would read as today, so tomorrow's words say "i morgen" and drop the hour (the card shows the
hours). Zones whose hours start at :30 local get the same rules on their forecast hours (a "kl. 14:30"); they are
defined, not pinned by a vector (server QA L8: the family is in Norway).

**D4 - The rule table (first match wins; none = no words).** The inputs, over the window's hours (server QA M5; a
missing value counts as nothing - no rain, chance 0, no gust; a missing feels-like falls back to the temperature):
- **mm per kind**: the sum of `next_1_hours.precipitation_amount` over the hours of that kind; an hour's **kind** from
  its `symbol_code` name - `snow`, `sleet` or `rain` (MET's spellings, `lightssleet...` included); a code with none of
  them: snow below 0 °C, else rain.
- **the day's kind**: the kind with the most mm (none or a tie: rain; snow against sleet: snow).
- **chance**: the window's highest `probability_of_precipitation`.
- **thunder**: an hour whose code holds `thunder` (the code only - no `probability_of_thunder`).
- **feels-like**: the window's lowest `apparent_air_temperature`; **gust**: its highest `wind_speed_of_gust`;
  **high**: its highest `air_temperature` (the window, not the whole day: the words speak of 07-18).
- **first wet hour**: the kind's first hour with ≥ 0.1 mm; rule 5 matched by the chance alone: the first hour with a
  chance ≥ 60 %; rule 1: the first thunder hour.

| # | Key | When | nb (`from` / `today` / `now` / `tomorrow`) |
|---|---|---|---|
| 1 | `thunder` | a thunder hour | Torden fra kl. 15 / Torden i dag / Torden nå / Torden i morgen |
| 2 | `snow` | snow ≥ 1.0 mm | Snø fra kl. X / Snø i dag / Snør nå / Snø i morgen |
| 3 | `sleet` | sleet ≥ 0.5 mm | Sludd fra kl. X / Sludd i dag / Sludd nå / Sludd i morgen |
| 4 | `heavy_rain` | rain ≥ 5.0 mm | Mye regn fra kl. X / Mye regn i dag / Mye regn nå / Mye regn i morgen |
| 5 | `rain` | rain ≥ 0.5 mm, or chance ≥ 60 % on a rain day | Regn fra kl. X / Regn i dag / Regn nå / Regn i morgen |
| 6 | `maybe_rain` / `maybe_snow` | all mm ≥ 0.1, or chance ≥ 30 % (`maybe_snow` on a snow day, `maybe_rain` otherwise - sleet too) | Kanskje litt regn (snø) / … i morgen |
| 7 | `very_cold` | feels-like ≤ −10° | Veldig kaldt / Veldig kaldt i morgen |
| 8 | `windy` | gust ≥ 15 m/s | Mye vind / Mye vind i morgen |
| 9 | `very_warm` | high ≥ 25° | Veldig varmt / Veldig varmt i morgen |

No clothing words and no "refleks" anywhere (the user). *Why the extensions:* the user listed the nb words for the main
forms; a `now` and a `tomorrow` form for every precipitation rule and "Kanskje litt snø" keep each string fixed and
true (no gluing), at no cost in the rules.

**D5 - Where the position comes from, in order.** The answer takes the first source that gives a cell:
1. **The fresh stored fix** - the phone's newest `device_locations` row with `COALESCE(mock, 0) = 0`, `captured_at`
   at most 6 h old and not in the future, while its `location_mode` is `interval`.
2. **The weather zone** - the zone the parent chose for the weather (`device_policy.weather_zone_id`, one of the
   phone's design-64 zones; none by default): its centre.
3. **Nothing** - `no_position`, the weather hides.

(A source between these, the phone's own last-known cell, was designed as D18 / step 3 and dropped by the user, §12.)

*Why:* nothing new leaves the phone and the server learns nothing it doesn't hold already. "On
request" means the phone takes no fix unless the parent asks (an active fix lights Android's location indicator,
`server/LocationPolicies.kt`), so its last fix may be days old - the wrong town's weather is worse than none; "Off" has
no fixes at all; 6 h covers the longest interval (240 min) with one missed report. Design 64 has no special "home"
zone (zones are named freely per phone), so the parent picks which one stands in (the user's Home-zone approach); its
centre is snapped to the same cell as a fix would be, and it is the parent's own choice of place, not a trail. The zone
is used with Location Off too: it reveals nothing about where the phone is.

**D6 - The grid cell.** Latitude to the nearest 0.02°, longitude to the nearest 0.04° (≈ 2.22 x 2.23 km at 60° N,
2.22 x 1.52 km at 70° N); the cell is kept as two integers (`lat_i = round(lat / 0.02)`, `lon_i = round(lon / 0.04)`,
the longitude wrapped into [−180, 180), the latitude clamped to ±90) and every URL is written from the integers with two
decimals ("lat=59.94&lon=10.76"; MET allows four). *Why:* MET's model grid (MEPS) is 2.5 km, so a finer cell buys
nothing; the cell is what MET, Kartverket and our cache see, and phones in one cell share one fetch; integers avoid
"59.940000001" and "−0.00".

**D7 - Fetch politely, on demand, from the cache.** A forecast is fetched when a phone asks and the cell's cache is
due: never before `max(now + (Expires − Date), last fetch + 30 min) + jitter (0-120 s)` - the server's clock never
compared with MET's (server QA L2); with `If-Modified-Since` = the stored `Last-Modified` verbatim (MET's rule);
`Accept-Encoding: gzip` (reqwest's); HTTPS only; one forecast fetch at a time server-wide (one `tokio::sync::Mutex`,
the row read again inside it; server QA L4/O2). No background sweep of forecasts: a cell nobody asks for is never
fetched. A phone's answer waits at most 6 s for a fetch; past that the fetch carries on in its task and the phone gets
what the cache has (or `unavailable`) - the phone's call times out at 15 s and must never cancel the fill (launcher QA
M3). *Why:* MET's terms (§2.1); phones pull hourly, so on demand costs at most one MET round trip a cell an hour, and
an idle family costs MET nothing.

**D8 - MET's User-Agent** (the user: "OK vi kjører standard"): `vibb-server/<CARGO_PKG_VERSION>
github.com/<SERVER_RELEASE_REPO>` (default `palchrb/vibb-launcher`), with the operator's contact appended when the
env var **`MET_CONTACT`** is set (`vibb-server/0.65.0 github.com/palchrb/vibb-launcher ola@example.no`). `MET_CONTACT`
is read once in `ForkConfig`: 3-100 printable ASCII characters with no control or quote characters, else logged and
ignored. Only MET's requests carry it; security review P5's neutral `net::USER_AGENT` ("vibb/1.0") stays for every
other request, Kartverket's included, and `net.rs`'s doc names MET as the one exception ("never the server's version"
no longer holds there). Mitigations for one shared string (server QA M4): one `warn!` at the first MET fetch while
`MET_CONTACT` is unset; DEPLOY.md asks every operator to set it, and says that with it MET can tie the cells it is
asked for to that address. *Why:* MET blocks unidentified clients and asks for a contact; a misbehaving install
should be blockable on its own, and its mail should go to its operator, not to the repo.

**D9 - Nowcast only when the card opens.** `GET /api/devices/weather/now` (the phone asks when the card opens, at most
every 2 min) reads MET Nowcast 2.0 for the same cell. The server keeps the fetched steps with their times, in memory
per cell, until `max(now + (Expires − Date), fetch + 3 min) + jitter (0-20 s)`; steps older than 10 min are never
used; the line is computed at each `/now` answer from `now` (steps that ended before it dropped, minutes counted from
it; server QA M3). `meta.radar_coverage` other than `"ok"`, an error status (outside MEPS), or any failure: no line.
*Why:* radar data is stale after ~5 min (the user); the hourly pull would waste it; "no coverage" is simply silence.

**D10 - "Mørkt" is the end of civil twilight**, computed on the server for the cell's centre: the first downward
crossing of −6° after the local date's solar noon (NOAA's solar position algorithm), no outside request. Three
outcomes: `at` (an instant; may fall after midnight - Oslo on 21 June, 00:30 on the 22nd - and is shown as it is),
`never` ("Lyst hele natta": the sun stays above −6°, white nights and midnight sun) and `all_day` ("Mørkt hele dagen":
it never rises above −6°, the depth of polar night). *Why:* at sunset it is still light for ~45 min in Oslo in
November (MET Sunrise: sunset 16:16 on 2026-11-03; NOAA's civil dusk 17:02); "mørkt" means dark. In Tromsø's polar
night civil twilight still happens around noon, so `at` (≈13:53 on 2026-12-21) is right there.

**D11 - Freshness.** The answer's `checked_at_ms` is when MET last answered 200 or 304 for the cell. On the phone: an
answer checked more than 60 min ago adds "Oppdatert kl. HH:MM" to the card; more than 3 h ago, or no hour entry
covering now, hides the line (and folds an open card). The phone pulls when its answer is older than 55 min (§5.4).
*Why:* the user's ">3 h hides" and "oppdatert kl."; 55 min with the 30-min backstop gives a pull about hourly. (The
launcher QA offers "Oppdatert" as the first cut if the brief must shrink; it stays - it is the user's ask and two
lines of code.)

**D12 - The phone's zone.** The words, the periods and the "kl." hours are in the phone's own reported
`time_state.zone` (design 34 P13), else Phone Setup's zone - today's `device_api::extra_time_day_zone`, renamed
`phone_zone` and shared. `chrono-tz` (already a dependency) does the conversion; every hour is a UTC hour converted to
local time, so 23- and 25-hour days need nothing special. *Why:* the kid's day is the phone's day; no external
timezone service.

**D13 - The place name and the caches' lifetime.** The place name comes from Kartverket once per cell, at the cell's
centre, looked up **after** answering (`place` is `null` on a new cell's first answer; the next pull has it). It is
kept per cell (`NULL` = looked up, no name) and, like a cell's forecast, dropped when no phone has asked for it within
the location retention (§3.1). **A change of a phone's Location to Off erases the weather caches** (`weather_cells`,
`weather_places` and the nowcast map) in `update_location_policy`, at that change only - never in the hourly prune
(server QA H1). *Why:* the user's "cached per cell"; but a kept list of cells is a coarse trail of where the family has
been, so it lives no longer than the fixes it came from, and "Off erases every fix at once" (0.57.0) stays true. The
cache has no device id, so the erase takes it whole; the one early MET refetch per cell after such an erase is rare
and accepted (a cell's `next_fetch_at` and `Last-Modified` go with it).

**D14 - The date line on the glance only; the short date follows the switch.** The weather mark joins the glance's
date; the time-rule screen (which shares `LockClockFace`) gets none. While the policy's `lock_weather` is on, the
no-profile glance uses the short date (`EEEdMMM` with `FirstCapital`, "Man. 3. nov.", as the profile phones already
do) whether or not the line shows at the moment; with the switch off the long date stays (launcher QA M1, L2). *Why:*
the long date plus symbol, degrees and words needs ~335 dp; tying the date to the switch, not to the line's moods,
keeps the date from flipping when the line hides after 3 h and comes back.

**D15 - The mark's place is chosen at a lock-up, never in between.** Pure `weatherPlace(...)` (§5.3) picks the first
that fits: beside the date with words; on its own line under the date with words; beside without words; its own line
without words. It runs only at a lock-up, a refit (font scale), a page-width change and the first bind, measuring the
widest words of the answer's four entries but never less than a reference ("Regn fra kl. 14" in the phone's catalog).
Between those moments a new model that no longer fits the place drops its words; the mark never moves while the screen
is on, and words are never cut. *Why:* the user's "drop the words, never cut them mid-word" and "Egen linje under
datoen"; the launcher QA's M1: a mark hopping between lines moves every row while the kid looks. The reference keeps
the Jelly Star on its own line every day, not only on days with words.

**D16 - The symbols are yr's darkmode set as VectorDrawables**, made by `scripts/yr-symbols.py` from
nrkno/yr-weather-symbols at a pinned commit (§4). Shadows (`filter`) drop; a multiply-blend layer that repeats an earlier
solid path is baked into it, any other blend layer drops. *Why:* the user's choice; CC BY 4.0 is GPL-compatible with
attribution; vectors stay sharp at every density (~350 KB of SVG in).

**D17 - Default off, per phone, in the policy.** `device_policy.lock_weather` (0122), `launcher_ui.lock_weather`
(always sent; a launcher without the key reads off). The server answers `off` and fetches nothing for a phone with the
switch off. *Why:* the parent decides what the lock shows; defense in depth if a phone asks anyway.

**D18 - Dropped (the user, 2026-11-03: "nei jeg angrer meg, jeg vil ikke ha den siste rute greia").** The phone's
own last-known cell (a second switch, migration 0123, the phone rounding Android's cached fix and sending the cell) is
not built. The source order is D5's: the fresh stored fix, the parent's chosen zone, nothing.

## 2. Outside sources (verified 2026-11-03)

### 2.1 MET Norway

- **Terms** (https://api.met.no/doc/TermsOfService): an identifying User-Agent with an application name and a way to
  contact ("a company email address or a link to the company website"), else blocking without warning; above 20
  requests/s per application needs an agreement; don't repeat a request before `Expires`; `If-Modified-Since` must be
  the previous `Last-Modified`; no HEAD-then-GET; at most 4 decimals (5+ → 403 on new products); HTTPS only; clients
  must support gzip; a **203** means the product version is deprecated (usually ended about a month later) - log it;
  **429** means throttled - reduce traffic at once; 403 is usually a bad User-Agent. Data is CC BY 4.0 ("give
  appropriate credit, provide a link to the license, and indicate if changes were made").
- **Locationforecast 2.0** (https://api.met.no/weatherapi/locationforecast/2.0/documentation, the format at
  https://api.met.no/doc/ForecastJSON): `GET https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=..&lon=..`
  (`altitude` left out: MET's topography is used). GeoJSON; `properties.timeseries[]` sorted by `time` (UTC), each with
  `data.instant.details` (`air_temperature`, `apparent_air_temperature` - in `complete` -, `wind_speed_of_gust`, ...)
  and `next_1_hours` / `next_6_hours` / `next_12_hours` with `summary.symbol_code` and `details` (`next_1_hours`:
  `precipitation_amount`, `probability_of_precipitation`). Hourly steps for ~60 h (a sample on 2026-11-03 at 59.94,
  10.76: 85 entries, 53 with `next_1_hours`, the hourly part to 2026-11-05T17:00Z, the last 5 of them without
  `next_6_hours` - past the card's horizon), then 6-hourly to 10 days. The same sample: `Expires` 32 min after `Date`,
  `Last-Modified` set, ~90 KB decoded.
- **Nowcast 2.0** (https://api.met.no/weatherapi/nowcast/2.0/documentation): `GET https://api.met.no/weatherapi/
  nowcast/2.0/complete?lat=..&lon=..`; Norway, Sweden, Finland and Denmark; updated every 5 min; the sample:
  `properties.meta.radar_coverage` `"ok"`, 23 entries 5 min apart (~110 min, the first 2 min after the fetch), each
  `instant.details.precipitation_rate` (mm/h), only the first with `next_1_hours.summary.symbol_code` and the
  temperature; `Expires` 3 min. The documented coverage values are `ok`, `temporarily unavailable`, `no coverage` (a
  200 inside MEPS; outside MEPS an error status).
- **Credit**: "Data: MET Norway" with the licence (§4.3).

### 2.2 Kartverket's place names

- `GET https://api.kartverket.no/stedsnavn/v1/punkt?nord=59.94&ost=10.76&koordsys=4258&radius=2000&utkoordsys=4258
  &treffPerSide=500&side=1&filtrer=navn.stedsnavn.skrivemåte,navn.navneobjekttype,navn.meterFraPunkt,metadata.
  totaltAntallTreff` (OpenAPI at https://api.kartverket.no/stedsnavn/v1/openapi.json, "Kartverkets åpne API for søk
  etter stedsnavn" 1.1; no login; `ws.geonorge.no/stedsnavn/v1` is a proxy to it "until further notice"; radius at
  most 5000 m, at most 500 per page; changes announced 3 months ahead on status.kartverket.no). The answer:
  `navn[]` with `meterFraPunkt`, `navneobjekttype` and `stedsnavn[].skrivemåte`. **No type filter, not strictly sorted
  by distance**, and the nearest hits are houses, farms and streets (a test at 59.95, 10.76: 447 hits in 2 km, 333 of
  them `Adressenavn`; the nearest `Tettbebyggelse` "Tåsen" at 880 m).
- **The pick** (server QA L6, O3): the nearest by `meterFraPunkt` whose `navneobjekttype` is one of `Bydel`,
  `Tettsteddel`, `Tettbebyggelse`, `Tettsted`, `By`; only when none, the nearest `Boligfelt`, `Grend` or `Bygdelag
  (bygd)` (an estate's own name only as a last resort); its first `skrivemåte`. Radius 2000 m, page 2 while
  `totaltAntallTreff` says there is more; none → once more at 5000 m, up to 2 pages; none → `NULL`. A cell outside
  57.5-71.5° N, 4.0-31.5° E is never asked (`NULL` at once). The name is cleaned (control and bidi characters out,
  whitespace collapsed) and cut to 40 characters at a word boundary.
- **Licence**: Kartverket's open data is CC BY 4.0 (Geonorge's SSR record); the implementer checks the
  kartkatalog.geonorge.no entry for "Stedsnavn" and cites it in NOTICE.md. Credit "Stedsnavn: © Kartverket".

## 3. Server

### 3.1 Data (migration `0122_lock_weather.sql`)

```sql
ALTER TABLE device_policy ADD COLUMN lock_weather INTEGER NOT NULL DEFAULT 0;
-- D5's source 3: one of the phone's zones; a deleted zone leaves none.
ALTER TABLE device_policy ADD COLUMN weather_zone_id INTEGER REFERENCES device_zones(id) ON DELETE SET NULL;

-- One forecast per grid cell (D6-D7): no device id.
CREATE TABLE weather_cells (
    id INTEGER PRIMARY KEY,
    lat_i INTEGER NOT NULL,            -- round(lat / 0.02)
    lon_i INTEGER NOT NULL,            -- round(lon / 0.04), wrapped
    hours_json TEXT,                   -- the compact hours (§3.2), NULL before the first 200
    last_modified TEXT,                -- MET's Last-Modified, verbatim
    checked_at TEXT,                   -- the last 200 or 304
    next_fetch_at TEXT,                -- not before this (Expires, the floor, the jitter, a backoff)
    failures INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,                   -- a code: network, timeout, status_429, status_403, status_5xx, unreadable
    asked_at TEXT NOT NULL,            -- the last phone ask (retention)
    UNIQUE (lat_i, lon_i)
);

-- The place name per cell (D13): NULL = looked up, no name.
CREATE TABLE weather_places (
    lat_i INTEGER NOT NULL,
    lon_i INTEGER NOT NULL,
    name TEXT,
    asked_at TEXT NOT NULL,
    PRIMARY KEY (lat_i, lon_i)
);
```

- `weather_zone_id` must name a zone of the same phone: the save checks it (§6), and a zone's delete clears it through
  the foreign key (a test pins it; `device_zones` rows are deleted, never soft-removed).
- The nowcast steps and Kartverket's retry gate (a failed lookup isn't retried for 1 h) are in memory only
  (`AppState.weather`).
- **Retention** (`retention::prune`, by age only): a `weather_cells` row not asked for in 48 h goes; a `weather_places`
  row not asked for in the longest `location_retention_days` of any phone (default 7) goes.
- **Location Off** (D13): `handlers::locate::update_location_policy` reads the old mode first (today it only
  UPDATEs) and, when the mode changes to `off`, calls `weather_cache::erase(db)` (`DELETE FROM weather_cells`,
  `DELETE FROM weather_places`, the nowcast map cleared) after its own erase; prune's `LOCATION_OFF` pass never touches
  the weather tables.
- **Backups** (server QA L7): the Backups page's copies and `pre-migrate-*` / `prerestore-*` keep both tables after an
  Off erase, as they keep fixes; DEPLOY.md's backups paragraph says so.

### 3.2 `src/weather.rs` - pure

- `Cell { lat_i, lon_i }`, `Cell::of(lat, lon)`, `Cell::query()` → `"lat=59.94&lon=10.76"` (from the integers), `Cell::centre()` → (f64, f64) for the dusk
  and Kartverket (`nord`/`ost`).
- `Hour` (the cache's compact row): `{t, sym, sym6, temp, feels, mm, pop, gust}` - `t` the UTC hour, `sym`
  `next_1_hours.summary.symbol_code`, `sym6` `next_6_hours`'s, `temp`/`feels`/`gust` from `instant.details`
  (`air_temperature`, `apparent_air_temperature`, `wind_speed_of_gust`), `mm`/`pop` from `next_1_hours.details`; every
  number `Option<f32>`, a code at most 64 ASCII characters `[a-z_]` (anything else `None`). `parse_forecast(body) ->
  Result<Vec<Hour>, _>` keeps only entries with `next_1_hours` (the hourly part); a body that isn't the shape is
  `unreadable`, inside `catch_unwind` as calendar_feeds does. `merge(old, new, now)`: the new hours win; the old ones
  before the new series' first hour are kept back to 24 h before `now` (this morning's period still has data at 15:00).
- `Kind::of(hour)` and `is_thunder(code)` from the code's name (D4).
- `words(hours, zone, at) -> Option<Words>`: D3-D4. `Words { key: &'static str, form: Form, at: Option<DateTime> }`;
  the thresholds are named constants (`SNOW_MM = 1.0`, `SLEET_MM = 0.5`, `HEAVY_RAIN_MM = 5.0`, `RAIN_MM = 0.5`,
  `RAIN_CHANCE = 60.0`, `MAYBE_MM = 0.1`, `MAYBE_CHANCE = 30.0`, `WET_HOUR_MM = 0.1`, `VERY_COLD = -10.0`, `WINDY_GUST =
  15.0`, `VERY_WARM = 25.0`, `WINDOW = 07..18`, `TOMORROW_FROM = 18`).
- `periods(hours, zone, date) -> [Period; 3]`: morning 06-12, afternoon 12-18, evening 18-24 local; each `symbol` =
  `sym6` of the forecast hour containing the period's start, `temp` = the temperature at the hour containing its middle
  (09, 15, 21 - what the kid meets out of the door, not the period's extreme), `precip_mm` = the sum of its hours' `mm`
  rounded to 0.1. Without the start's `sym6` or the middle's temperature (a cell's first fetch starts at the current
  hour, so the period now running has lost its start), the period's first hour we have gives them (its `sym6`, else
  its `sym`; its `temp`; §12). A period without its hours has all three `null`.
- `day(hours, zone, date, cell)`: the periods, `low`/`high` (min/max hourly `temp` of the local date's hours we have),
  `dark` (`dusk(cell.centre(), date, zone)`).
- `dusk(lat, lon, date, zone) -> Dark { At(DateTime) | Never | AllDay }`: D10. The same function at −0.833° gives the
  sunset the vectors compare with MET's Sunrise 3.0 (§7).
- `entries(hours, zone, now) -> Vec<Entry>`: four, from the UTC hour containing `now`; each `{at, symbol, temp, words,
  card, current}` - `symbol` the hour's `sym`, `temp` rounded half away from zero with `-0` → `0`, `card` the local
  date the card shows at that hour (D3), `current` 0-2 for the period containing it on today's card, `null` before
  06:00 or on tomorrow's card. Entries without a forecast hour are left out.
- `nowcast_line(steps, now, coverage) -> Option<NowLine>`: the steps from `now` to `now + 90 min` (a step ended before
  `now` dropped; minutes counted from `now`); a step is wet at `precipitation_rate ≥ 0.1` mm/h; coverage not `ok`, no
  step left or none wet → `None`. Wet now: the first dry step → `dry_in` (minutes rounded to the nearest 5, at least 5),
  none dry → `rain_all` / `snow_all`. Dry now: the first wet step → `rain_in` / `snow_in`. Snow when the first step's
  code names snow, or with no precipitation in its code when its temperature is below 0 °C.
- `answer(...)` builds §3.5's JSON. Nothing here does I/O; every function is tested from `testdata/weather.json`.

### 3.3 `src/weather_cache.rs` - the fetches

- `AppState.weather_fetch: Arc<dyn music_net::Source>` - its own `Gated(HttpSource::new(), REQUEST_SPACING)` for MET
  only, not `music_fetch`'s gate: a card's nowcast must never queue behind a 120 s feed. Requests are
  `Reach::Public`, a new `Kind::Weather` (1 MB decoded, 10 s overall), MET's User-Agent set as a request header
  (`SourceRequest.headers`; reqwest adds the client default only when the request lacks one). `SourceResponse` gains
  `expires` and `date` (the headers as sent, at most 100 characters each). The tests swap in `tests::music::CannedFetch`.
- **Kartverket** goes through a plain `HttpSource` of its own (no gate - one lookup per new cell, at most one in flight:
  an in-memory "looking up" set), `Kind::Weather`, the neutral User-Agent; spawned after the answer (D13).
- `forecast(state, cell, now) -> Result<CellRow, sqlx::Error>`: upsert the row's `asked_at`; when `now <
  next_fetch_at`, answer from the row. Else, under the one forecast lock (the row read again inside it), a fetch in a
  spawned task, awaited up to 6 s (D7):
  - **200** → `parse_forecast`, `merge` with the stored hours, store `hours_json`, `last_modified`, `checked_at = now`,
    `failures = 0`; **203** the same, plus one `tracing::warn!` a day ("MET says Locationforecast 2.0 is deprecated
    (203) - a new version is due"); **304** → `checked_at = now`.
  - `next_fetch_at = max(now + (Expires − Date), now + 30 min) + jitter(0..120 s)`; no or unreadable `Expires`/`Date`
    → `now + 30 min + jitter`.
  - **429** → `next_fetch_at = now + max(Retry-After, 10 min)` (`Retry-After` as seconds or an HTTP date), doubling per
    failure up to 2 h, `warn!`; **403** → `now + 6 h`, `error!` ("MET refused the request (403) - check MET_CONTACT
    and the User-Agent"); 5xx, network, timeout, unreadable → 5, 10, 20, 40, 60 min by `failures`, `warn!`. The old
    hours stay and are served.
  - Logs name the cell by its row id and the code, **never coordinates** - MET's and Kartverket's failures and
    `last_error` alike (`music_net::network_error` already strips the URL); the body is never logged.
- `nowcast(state, cell, now) -> Option<NowLine>`: D9, from `AppState.weather.nowcast: Mutex<HashMap<Cell,
  NowEntry>>` (`NowEntry`: the steps with their times, the coverage, the fetch time and `next_fetch_at`; pruned of
  entries older than 1 h at each write); an error status other than 429/403 remembers "outside" for the cell for 24 h
  (no ask per card open in Spain); 429/403 as above, in memory; awaited at most 3 s.
- `place(state, cell)`: the `weather_places` row (its `asked_at` moved); none → a lookup spawned (§2.2) unless one is in
  flight for the cell or one failed in the last hour; a successful lookup stores the name or `NULL`; a failure stores
  nothing.
- `erase(db)` (D13) and `prune(db)` (§3.1).

### 3.4 Routes (`src/handlers/weather.rs`, declared in `device_routes.rs`)

`GET /api/devices/weather` (bearer) - in order: the phone's `device_policy` (`lock_weather`, `location_mode`,
`weather_zone_id`); off → `{"v":1,"status":"off"}`, no outside request; the cell by D5's order; none → `no_position`; else `forecast`, `place`, the zone
(`phone_zone`) and `weather::answer(...)`. No hours stored yet and the fetch failed or is still running →
`unavailable`. DB error → 500 (the phone keeps what it has). 20 asks a rolling hour per phone (`music_net::PhoneAsks`)
→ 429 with `Retry-After`. `Cache-Control: no-store`, gzip. It answers within ~6.5 s.

`GET /api/devices/weather/now` (bearer) - the same gates and source order (off, no position → `{"v":1,"status":
"none"}`), then `nowcast`; 60 asks a rolling
hour per phone → 429; it answers within ~3.5 s. Neither route reads or writes `device_locations` beyond D5's one
SELECT, or returns coordinates or the cell.

### 3.5 The answers (the shapes of `testdata/weather.json`'s `answer` and `now_answers`)

```json
{
  "v": 1,
  "status": "ok",
  "checked_at_ms": 1793709715000,
  "place": "Nydalen",
  "hours": [
    {"at_ms": 1793707200000, "symbol": "partlycloudy_day", "temp": 6,
     "words": {"key": "rain", "form": "from", "at_ms": 1793710800000}, "card": "2026-11-03", "current": 1},
    {"at_ms": 1793710800000, "symbol": "rain", "temp": 6,
     "words": {"key": "rain", "form": "now"}, "card": "2026-11-03", "current": 1},
    {"at_ms": 1793714400000, "symbol": "rain", "temp": 5,
     "words": {"key": "rain", "form": "now"}, "card": "2026-11-03", "current": 1},
    {"at_ms": 1793718000000, "symbol": "lightrain", "temp": 5,
     "words": null, "card": "2026-11-03", "current": 1}
  ],
  "days": [
    {"date": "2026-11-03",
     "periods": [
       {"part": "morning", "symbol": "partlycloudy_day", "temp": 3, "precip_mm": 0.0},
       {"part": "afternoon", "symbol": "rain", "temp": 6, "precip_mm": 2.1},
       {"part": "evening", "symbol": "lightrain", "temp": 4, "precip_mm": 0.4}],
     "low": 2, "high": 7,
     "dark": {"kind": "at", "at_ms": 1793721720000}}
  ]
}
```

- `days` holds every date an entry's `card` names (one to three: an entry at 23:00 names tomorrow, so near midnight
  `days` is today and tomorrow). `dark.kind` is `at` (with `at_ms`), `never` or `all_day`. `place` is `null` when there
  is no name (yet). `words` is `null` for no words; `at_ms` only with `form` `from`. `symbol` is MET's `symbol_code`.
- `{"v":1,"status":"off"}`, `"no_position"`, `"unavailable"` carry nothing else.
- `/now`: `{"v":1,"status":"ok","line":{"key":"rain_in","minutes":25}}`; `{"v":1,"status":"ok","line":null}` (dry, no
  radar coverage, outside the Nordic area, MET failing); `{"v":1,"status":"none"}` (off, no position). Keys:
  `rain_in`, `snow_in`, `dry_in` (with `minutes`), `rain_all`, `snow_all`.
- A phone ignores a key, form, part, kind or symbol it doesn't know (that piece is left out, §5.2), so a newer server
  never breaks an older launcher; `v` changes only for a breaking shape.

### 3.6 The policy

`models::LauncherUi.lock_weather: bool` (always sent), from `device_policy.lock_weather` in `build_policy` beside
`lock_wake_on_notification`. `policy_json_keys_snapshot` and the launcher's
`PolicyResponseCompatTest` gain each in the same commit. `weather_zone_id` is never sent (the phone needs no zone).
Capability: the launcher reports `lock_weather_v1` (§6 reads it).

## 4. Symbols and credit

### 4.1 `scripts/yr-symbols.py`

Pattern `scripts/material-symbols.sh`: pinned upstream, checked, converted, every output named.

- **Source**: `nrkno/yr-weather-symbols` at commit `34039b509167ba1701f1729df5cc95488813dd5f` (HEAD on 2026-11-03,
  package 14.0.0): `symbols/darkmode/*.svg` (83 files), `src/index.ts` (`weatherSymbolKeys`, MET's codes → yr ids,
  MET's own spellings kept: `lightssleetshowersandthunder_*`), `locales/en.json` and `locales/nb.json` (the names by
  number, "03" → "partly cloudy" / "delvis skyet"), `LICENSE` (CC BY 4.0, "Yr weather symbols © 2015 by Yr/NRK").
  The script downloads through `raw.githubusercontent.com` at the commit and checks one SHA-256 over a manifest of
  every file's SHA-256 (pinned in the script; a changed upstream fails loudly).
- **Conversion** (Python 3 standard library only, run with `-I`; launcher QA L15): `svg` 100x100 viewBox → `<vector
  android:viewportWidth="100" android:viewportHeight="100" android:width="24dp" android:height="24dp">`; `path`
  (`d`, `fill`, `fill-rule="evenodd"` → `android:fillType="evenOdd"`, `opacity`/`fill-opacity` → `fillAlpha`);
  `linearGradient` / `radialGradient` with `gradientUnits="userSpaceOnUse"` and their stops (`stop-opacity` into the
  colour's alpha) → `aapt:attr` gradients; a `gradientTransform` of translate, uniform scale or `matrix(a 0 0 a e f)`
  applied to the coordinates, anything else (skew, rotation, non-uniform scale) fails; a `clipPath` that is the whole
  viewBox (`M0 0h100v100H0z`, 16 of 18 sampled files) is dropped, any other → `<clip-path>` at the start of a
  `<group>`; `g` with translate/scale/matrix → a group; `style=""` is parsed for `mix-blend-mode`, `opacity` and
  `fill`. **Changed**, each reported per file on stdout: groups with `filter` (upstream's soft blurred cloud
  shading: a faint multiply layer of one path under a filter of `feFlood`/`feBlend`/`feGaussianBlur` only - any other
  filtered group fails) are dropped; a `mix-blend-mode:multiply` path whose `d` repeats the solid path right before it
  exactly (every one in the sample) is **baked** into that path - its gradient's stops become `stop × fill` blended at the layer's opacity over the fill,
  so yr's cloud shading stays; any other blend layer is dropped. **Anything else** (`mask`, `circle`, text, `use`, an
  unknown attribute that changes the picture, a gradient that leaves `x2`/`y2`/`cx`/`cy`/`r` to their defaults, a radius
  that isn't positive, a `weatherSymbolKeys` line the reader doesn't take) fails the run.
- **Writes**: `launcher/app/src/main/res/drawable/weather_<id>.xml` (`weather_03d`; each starts with a comment: the
  upstream path and commit, "© Yr/NRK, CC BY 4.0, converted to a VectorDrawable; the soft blurred cloud shading removed, blend
  layers merged"
  - CC BY's "indicate if changes were made"); `launcher/app/src/main/java/com/kidslauncher/mdm/weather/
  WeatherSymbols.kt` (MET's `symbol_code` → the drawable and the name resource, from `weatherSymbolKeys`);
  `launcher/app/src/main/res/values/weather_symbols.xml` and `values-nb/` (the names, `weather_symbol_03`, one per
  number); `launcher/app/src/main/assets/licenses/CC-BY-4.0.txt` (upstream's LICENSE text). `--selftest` converts a
  built-in tiny SVG with each supported, baked and dropped feature and checks the output, and converts the vendored
  `scripts/yr-symbols-11.svg` (upstream's `11.svg`, unchanged) to exactly the committed `weather_11.xml` (run by
  `launcher.yml`'s always-run changes job). Nothing goes to `server/`.

### 4.2 Accessibility names

The mark's and the card's TalkBack text use `weather_symbol_<nn>` (the number without d/n/m): "Delvis skyet." /
"Partly cloudy.". An unknown code has no name (that sentence is left out, §5.6).

### 4.3 Credit

- `NOTICE.md`: the symbols (© Yr/NRK, CC BY 4.0, converted, what was changed, the commit), the forecast data (MET
  Norway, CC BY 4.0, https://api.met.no/doc/License), the place names (© Kartverket, CC BY 4.0) - the server only
  fetches the data, the launcher shows it.
- The launcher's Open Source Licenses screen (`LegalInfoActivity`, `legal_info.xml`): a new paragraph
  `legal_info_weather` ("Værsymboler © Yr/NRK, CC BY 4.0, omgjort til vektorer. Værdata: MET Norway, CC BY 4.0.
  Stedsnavn: © Kartverket, CC BY 4.0.") and the licence text from `licenses/CC-BY-4.0.txt`, as the Nunito and Material
  Symbols paragraphs. The card's foot stays as the mockup (the brief: the foot only when no licences page exists).
- The PWA switch's sub-line names MET Norway (§6).

## 5. Launcher

### 5.1 Files

- `weather/WeatherModel.kt` - pure (JVM-tested): the decoded answer, the entry pick, freshness, the ladder, the card
  model, the keep rule, the open/fold step, the nowcast window (launcher QA L9).
- `weather/WeatherSymbols.kt` - generated table (§4.1).
- `weather/WeatherWords.kt` - the `(key, form)` → `R.string` table as a `when` (launcher QA L10: no `getIdentifier`).
- `weather/WeatherStore.kt` - main-thread memory plus one CE `SharedPreferences` file `weather` (`answer_json`,
  `pulled_at_ms`, `tried_at_ms`); listeners (`addListener`/`removeListener`); `clear()`.
- `weather/WeatherPull.kt` - the two GETs through the existing device API client (`MdmApi`, `ResponseBody`, decoded by
  `ServerJson` into `server/dto/WeatherAnswer.kt` / `WeatherNow.kt`).
- `lock/WeatherLockPanel.kt` - the glue under `GlancePanel` (as `MusicLockPanel`, `ClockLockPanel`): binds the marks
  and the card, owns the open state and the hit area.
- `res/layout/view_lock_weather_mark.xml`, `view_lock_weather_card.xml`; `res/drawable/ic_weather_chevron.xml`.

### 5.2 The model (`WeatherModel.kt`)

```kotlin
const val WEATHER_PULL_AFTER_MS = 55 * 60_000L      // D11
const val WEATHER_UPDATED_AFTER_MS = 60 * 60_000L   // "Oppdatert kl." in the card
const val WEATHER_HIDE_AFTER_MS = 3 * 3_600_000L    // the line hides
const val WEATHER_TRY_GAP_MS = 5 * 60_000L          // a retry after a failed pull
const val WEATHER_NOW_REUSE_MS = 2 * 60_000L        // the card's nowcast answer reused
const val WEATHER_NOW_INSERT_MS = 1_000L            // a later nowcast waits for the next open (M2)

data class WeatherLine(val symbol: String?, val temp: Int, val words: WeatherWordsKey?, val card: WeatherDay?, val current: Int?)
data class WeatherWordsKey(val key: String, val form: String, val atMs: Long?)

fun weatherLine(answer: WeatherAnswer?, policyOn: Boolean, nowMs: Long): WeatherLine?
fun weatherPullDue(policyOn: Boolean, pulledAtMs: Long?, triedAtMs: Long?, nowMs: Long): Boolean
fun weatherKeep(kept: WeatherAnswer?, got: PullResult, policy: WeatherPolicy, previous: WeatherPolicy?): Keep
fun weatherFold(open: Boolean, event: WeatherEvent, hasCard: Boolean): Boolean
fun weatherNowShown(openedAtMs: Long, answeredAtMs: Long, generation: Int, current: Int, rowsHeld: Boolean): Boolean
fun weatherUpdatedAt(answer: WeatherAnswer, nowMs: Long): Long?        // checked_at when > 60 min old
fun weatherDegrees(temp: Int): String                                  // "4°", "−3°" (U+2212), never "−0°"
fun weatherHour(atMs: Long, zone: ZoneId, twentyFour: Boolean, catalogNb: Boolean): String
fun weatherMm(mm: Double, locale: Locale): String?                     // "2,1" / "2.1"; null under 0.1
```

- `weatherLine`: `null` when the policy is off, the status isn't `ok`, `checked_at` is more than 3 h old, or no entry
  has `at_ms ≤ now < at_ms + 1 h` (a clock before the first entry counts as none). An unknown symbol → `symbol = null`
  (degrees alone); an unknown key/form pair → `words = null`; an unknown `card` date → no card (the mark still shows,
  without the chevron, and isn't a button then; launcher QA L12).
- `weatherKeep` (launcher QA M4, L9): an `ok` answer replaces the kept one; `off` and `no_position` clear; the policy
  switch turning off clears; **a change of the policy's location mode** (any change) clears
  the kept answer at once and makes a pull due - so "Off" never leaves "Nydalen" on the lock, and a zone fallback
  answers from the next pull; `unavailable`, a 429, a 5xx, no network keep the old answer and stamp `tried_at_ms`.
- `weatherHour` is keyed on the strings' catalog (nb for `values-nb`, en for every other locale; launcher QA L11):
  24-hour nb `"14"` (the string carries "kl."), 24-hour en `"14:00"`, 12-hour `DateFormat.getBestDateTimePattern(...,
  "ha")` ("2 PM"); a start not on :00 shows its minutes ("14:30"). It follows the phone's one clock format
  (`ClockFormat`, design 34 P12) and formats in the phone's zone (the server computed the hour in the zone the phone
  reported; the two differ only right after a zone change, until the next pull).
- The card model `weatherCard(answer, line, nowMs)`: the day (`days[card]`), a "I morgen" label when `card` isn't the
  phone's today, the three columns (the part's label, symbol, degrees, mm; the current one highlighted), the foot
  (`place` · low / high · the dark text) and the updated line.

### 5.3 The line's place (D14-D15)

```kotlin
enum class WeatherPlace { BESIDE_WORDS, LINE_WORDS, BESIDE, LINE }

/** [lineDp] the room the date's line has: the content width less the date's start with a profile, the content width
 * without one (the row and the slot centred); [dateDp] the date's drawn width; [coreDp] symbol, degrees and chevron
 * with their gaps; [wordsDp] the dot and the widest words to plan for with their gaps (null = no words). */
fun weatherPlace(lineDp: Float, dateDp: Float, gapDp: Float, coreDp: Float, wordsDp: Float?): WeatherPlace
fun weatherWordsFit(place: WeatherPlace, lineDp: Float, dateDp: Float, gapDp: Float, coreDp: Float, wordsDp: Float): Boolean
```

- `weatherPlace`, first that fits: `dateDp + gapDp + coreDp + wordsDp ≤ lineDp` → BESIDE_WORDS; `coreDp + wordsDp ≤
  lineDp` → LINE_WORDS; `dateDp + gapDp + coreDp ≤ lineDp` → BESIDE; else LINE.
- **When** (D15, launcher QA M1): only at a lock-up, at `refit()` (font scale), at a page-width change and at the first
  bind. `wordsDp` there is the widest of the answer's four entries' words, never less than the reference "Regn fra kl.
  14" / "Rain from 14:00" in the phone's catalog. Between those moments the place stays; a model whose words don't fit
  it (`weatherWordsFit` false: a new pull, a longer form at an hour's turn) shows without words until the next lock-up.
  A place of BESIDE/LINE shows no words.
- **Measuring** (launcher QA L3): `Layout.getDesiredWidth` of the shown (transformed) date, the degrees and the words,
  each at its view's paint (as `MusicLockPanel.fixWidth`), plus 2 dp of slack each; the words' view is
  `setHorizontallyScrolling(true)` with `maxLines=1` and no ellipsize, so a measure 1 px short never wraps to "· Regn
  fra".
- **Two mark instances** (launcher QA L5): `lock_glance_weather` in the date row and `lock_glance_weather_line` in the
  own-line slot, bound from one model, at most one visible; no view is reparented.
- **Budgets** (approximate, Nunito Bold; the symbol 1.4 × the text size, at least 22 dp; recomputed per the launcher
  QA M1):

| Phone (room) | Font 1.0 | Font 1.3 | Font 2.0 |
|---|---|---|---|
| 360 dp, no profile (328 dp): date 92, core 70, words 125 | BESIDE_WORDS (295) | LINE_WORDS (core 75 + words 163 = 238) | BESIDE (date 184 + 8 + core 100 = 292) |
| Jelly Star, profile, date at 16 sp from 35.3 dp (≈269 dp): date 105, core 74 | LINE_WORDS (74 + 125 = 199; beside 312) | LINE_WORDS (96 + 163 = 259) | LINE (core 148 + words 250 > 269; beside 210 + 8 + 148 > 269) |

  `WeatherModelTest` uses exactly these numbers as inputs.

### 5.4 The store and the pull

- **When**: (1) at the end of every accepted `performMdmSync`, just before `commitPendingSelfUpdateIfDue` (nothing may
  run after it), beside `MusicSync` and in its pattern, when `weatherPullDue` (policy on, the answer older than 55 min
  or none, no try in the last 5 min); the SyncRunner's wake lock covers it, the 30-min backstop makes it about hourly;
  (2) at screen-on (`PinLockRuntime.addScreenOnListener`) on the same rule, on a background coroutine; (3) at once when
  `weatherKeep` makes a pull due (the switch turned on, a location-mode change). No new alarm or wake-up.
- **Limits** (launcher QA M3): the pull catches everything but `CancellationException` (it never fails a sync); its
  call has its own 15 s timeout (the server answers within ~6.5 s, D7), so a hung server can't spend the sync's budget
  before the self-update commit; one in-flight guard (an `AtomicBoolean`), so a sync pull and a screen-on pull never
  send two GETs.
- **The card's nowcast** (launcher QA M2): opening the card asks `/weather/now` with a 3 s timeout unless an answer of
  the last 2 min is kept (memory only). Nothing shows while waiting (no spinner). The line is inserted only when the
  answer arrives within 1 s of the open, the card is still the one opened (a generation counter bumped at every fold,
  lock-up and `stop()`) and no finger holds the rows (`rowsHeld()`); a later answer is kept for the next open. So the
  card never grows under a finger.
- **Logs**: "Weather: pulled (ok, 4 hours, place yes)" / "Weather: pull failed (IOException)" - status, counts and
  exception classes; never the place, a temperature, a time of day or a cell. The answer's `toString()` is redacted.
- **No location code**: nothing under `weather/` or in `WeatherLockPanel` touches `android.location`, and nothing asks any host but our server's API client (`WeatherGuardTest`).

### 5.5 The glance

- **Layout** (`view_lock_glance.xml`): the date `TextClock` goes into a horizontal row `lock_glance_date_row`
  (wrap_content, `baselineAligned`, `baselineAlignedChildIndex="0"`) beside the mark `lock_glance_weather` (an include
  of `view_lock_weather_mark.xml`: the symbol, the degrees, the dot and the words as one `TextView` - the words a
  `RelativeSizeSpan(0.93)` so `FontScaleRefresh` scales both (launcher QA L4) -, the 14 dp chevron in `kid_core_dim`);
  under the row the slot `lock_glance_weather_line` (the second instance, centred without a profile, at the date's
  start with one); then the alarm line and the "Neste" line as today; then the card `lock_weather_card` (include of
  `view_lock_weather_card.xml`), before `lock_rule_card`. The mark's text is the date's colour and size; the degrees in
  `kid_ink`. The symbol's size follows the text (1.4 × the text size, at least 22 dp), set at bind and at `refit()`.
- **`LockClockFace`** (launcher QA L2) gains `dateRow: View? = null`: when present, `placeProfile` measures, margins
  and baselines the row instead of the date (the row's baseline is the date's), and `profileLook`'s gravity loop takes
  the row; `extra` becomes `List<View>` and holds the own-line slot, which follows the date's start;
  `shortDate(on: Boolean)` switches the no-profile date's skeleton and its `FirstCapital` (D14). The time-rule screen
  passes no row and never calls it.
- **The hit area** (launcher QA H1): the visible mark's touch rectangle is at least 48 dp tall - its own bounds
  extended evenly above and below to 48 dp - and 8 dp wider at each side, through one `TouchDelegate` on
  `lock_glance_top` (the one view containing it; set again whenever the place, the font or the layout moves the mark),
  so the layout and the rows don't move. It is never taller than the gap to the clock's ink above. The card's whole
  area is its own target. `LockGlanceLayoutTest` pins the rectangle for both places.
- **The card** (`bg_glance_compact_tile`, 12 dp padding, 14 dp under the face's last line, matching the rule card):
  the nowcast line (bold, gone unless), the "I morgen" label (gone unless), three equal columns (part label, 32 dp
  symbol, degrees in bold, the mm in rain blue `kid_rain` - the mockup's `#7CC4F2`, a new token - `INVISIBLE` under
  0.1 mm while any column has mm, `GONE` when none has, so the card's height doesn't change within a day); the current
  column on the mockup's raised tone (`kid_weather_now`, white at 6 % over the tile, a token in `KidPaletteTest`); no
  dimmed past periods (not in the mockup; under 4.5:1; launcher QA L7); the foot "Nydalen · 2° / 7°" at the start and
  the dark text at the end ("Mørkt kl. 17:02" in `kid_ink`), the place left out when `null`; "Oppdatert kl. 13:41"
  under the foot when due. Calm (launcher-look): no colour field, no motion beyond the chevron's 150 ms turn (none with
  "Remove animations").
- **Open and fold** (pure `weatherFold`): a tap on the mark or on the card toggles; every lock-up (`lockStageDue`), a
  stage change off the glance, `stop()`, the line hiding, the card going (an unknown date) and the VoIP ring card coming
  fold it. A card whose bottom lands below the scroller's fold after opening is brought into view
  (`requestRectangleOnScreen`, no animation). `GlancePanel.fitNext` measures with the open card taken out, as it takes
  its own line out, so opening the card never hides the "Neste" line; `placeList` counts the open card in its margin
  rule (rows 14 dp under it; launcher QA L6). The own-line slot is part of the face: "Neste" yields before it
  (`glanceNextFits` - on the Jelly Star's worst case row 3 keeps ≈2.4 dp instead of 22.4, so "Neste" yields there;
  launcher QA L13).
- **Touch**: the mark and the card are tappable like a row and not in `swipeControls`: a swipe up from them still asks
  for the unlock (a recognised swipe cancels the tap, as on a row); a sideways drag does nothing (not dismissable).
- **Ticks**: the panel re-picks `weatherLine` on the clock's minute text change and at `resume` (a line stale for 3 h
  hides before the first frame; launcher QA L12), and binds only when the model changed (the 1 s music tick never
  touches it). A re-pick never moves the mark (D15).
- **`glanceLayout`** is unchanged: the card is not a tile and never counts toward the compact player (r13 D4's rule).
- Every entry point catches `Throwable` and logs the class (a weather fault must never trip the lock's crash guard,
  QA Medium 2 of design 25).

### 5.6 Words on the phone (`values/strings.xml` + `values-nb/strings.xml`)

| Resource | nb | en |
|---|---|---|
| `weather_thunder_from` / `_today` / `_now` / `_tomorrow` | Torden fra kl. %1$s / Torden i dag / Torden nå / Torden i morgen | Thunder from %1$s / Thunder today / Thunder now / Thunder tomorrow |
| `weather_snow_*` | Snø fra kl. %1$s / Snø i dag / Snør nå / Snø i morgen | Snow from %1$s / Snow today / Snowing now / Snow tomorrow |
| `weather_sleet_*` | Sludd fra kl. %1$s / Sludd i dag / Sludd nå / Sludd i morgen | Sleet from %1$s / Sleet today / Sleet now / Sleet tomorrow |
| `weather_heavy_rain_*` | Mye regn fra kl. %1$s / Mye regn i dag / Mye regn nå / Mye regn i morgen | Heavy rain from %1$s / Heavy rain today / Heavy rain now / Heavy rain tomorrow |
| `weather_rain_*` | Regn fra kl. %1$s / Regn i dag / Regn nå / Regn i morgen | Rain from %1$s / Rain today / Raining now / Rain tomorrow |
| `weather_maybe_rain_today` / `_tomorrow` | Kanskje litt regn / Kanskje litt regn i morgen | Maybe a little rain / Maybe a little rain tomorrow |
| `weather_maybe_snow_today` / `_tomorrow` | Kanskje litt snø / Kanskje litt snø i morgen | Maybe a little snow / Maybe a little snow tomorrow |
| `weather_very_cold_today` / `_tomorrow` | Veldig kaldt / Veldig kaldt i morgen | Very cold / Very cold tomorrow |
| `weather_windy_today` / `_tomorrow` | Mye vind / Mye vind i morgen | Windy / Windy tomorrow |
| `weather_very_warm_today` / `_tomorrow` | Veldig varmt / Veldig varmt i morgen | Very warm / Very warm tomorrow |
| `weather_now_rain_in` / `_snow_in` / `_dry_in` | Regn om ca. %1$d min / Snø om ca. %1$d min / Opphold om ca. %1$d min | Rain in about %1$d min / Snow in about %1$d min / Dry in about %1$d min |
| `weather_now_rain_all` / `_snow_all` | Regn de neste 90 minuttene / Snø de neste 90 minuttene | Rain for the next 90 minutes / Snow for the next 90 minutes |
| `weather_part_morning` / `_afternoon` / `_evening` | Morgen / Ettermiddag / Kveld | Morning / Afternoon / Evening |
| `weather_card_tomorrow` | I morgen | Tomorrow |
| `weather_dark_at` / `_never` / `_all_day` | Mørkt kl. %1$s / Lyst hele natta / Mørkt hele dagen | Dark at %1$s / Light all night / Dark all day |
| `weather_updated` | Oppdatert kl. %1$s | Updated %1$s |
| `weather_degrees` / `weather_mm` / `weather_low_high` | %1$s° / %1$s mm / %1$s / %2$s | the same |
| `weather_spoken_now` | Været nå: %1$s. | Weather now: %1$s. |
| plurals `weather_spoken_degrees` | %1$s grad. / %1$s grader. | %1$s degree. / %1$s degrees. |
| `weather_spoken_period` | %1$s: | %1$s: |
| plurals `weather_spoken_mm` | %1$s millimeter. | %1$s millimetre. / %1$s millimetres. |
| plurals `weather_spoken_low` / `_high` | Laveste %1$s grad(er). / Høyeste %1$s grad(er). | Low %1$s degree(s). / High %1$s degree(s). |
| `weather_expanded` / `weather_collapsed` / `weather_card_hint` | Utvidet / Lukket / Trykk for å se dagen | Expanded / Collapsed / Tap to see the day |
| `legal_info_weather` | §4.3 | §4.3 |

**Spoken text** (launcher QA M5): a description is a sequence of whole sentences, each its own string, joined with a
space - never a sentence assembled from fragments. The mark: `weather_spoken_now` with the symbol's name (left out
when unknown), `weather_spoken_degrees` (`getQuantityString` on `abs(temp)`, the sign spoken as "minus" by a `−` in
the number), the words exactly as shown plus "." (left out when none); the card: the nowcast line, per period
`weather_spoken_period` + its symbol's name + degrees + mm, then the place, low, high and the dark text. The "kl." of
the shown words is left to TalkBack's own reading (nb TTS says "klokka"); no spoken variants of the words - pinned in
`WeatherContractTest` (the description reuses the shown string). The mark is a button with expand/collapse actions and
the state `weather_expanded` / `weather_collapsed`.

The `weather_symbol_<nn>` names are generated (§4.1). `TranslationsTest` keeps the two catalogs' keys equal; the
contract test (§7.3) checks every key/form pair the server can send has both strings with the placeholder it needs.

## 6. The PWA

- **Where** (the user, 2026-11-03: "Om det er flere settings så legger vi kanskje inn en egen Vær settings sammen med
  app og time rules etc inne på en spesifikk device sin side?"): a page of its own, Device › Vær / Weather
  (`GET /devices/{id}/weather`, `templates/device_weather.html`, `handlers::weather_page`), listed on the phone's page
  among Apps, Time and the others with its value "På" / "Av" (the switch). Two rows, each its own form with
  `data-autosave` and its own anchor, in the Lock Screen page's switch and select markup:
  1. `#weather-lock` - the switch (`POST /devices/{id}/weather/lock`, as `save_wake`: 404 unknown device, 500 with
     nothing written on a DB error, a `lock_weather_changed` security-log line only when the value changed, the phone
     nudged).
  2. `#weather-zone` - shown while the switch is on: "Når posisjonen ikke er fersk, bruk:" with a select of "Ingen"
     and the phone's zones by name (`POST /devices/{id}/weather/zone`, the field `zone` = a zone id or empty; a zone
     of another phone or an unknown id → 400 and nothing written; a `lock_weather_zone_changed` line with the zone's
     name, never coordinates, only when it changed; nudged). With no zones, the row says so and links to Locate's
     zones.
  Each save answers on its own anchor (`Redirect::to("/devices/{id}/weather#weather-zone")`; with JS
  `scroll-restore.js` keeps the exact place). **A save never jumps to the top.**
- **Notes under the rows**: `needs_position` while on, Location isn't "Regularly" and no zone is chosen: the lock shows
  no weather now; `needs_update` while on and the phone hasn't reported `lock_weather_v1`.
- **Words** (`src/i18n/en.json` + `nb.json`, through `self.t`; server QA L5):

| Key | en | nb |
|---|---|---|
| `device.page.weather` | Weather | Vær |
| `device.weather.lock` | Weather on the lock screen | Vær på låseskjermen |
| `device.weather.lock_sub` | Yr's symbol and the temperature by the date; tap for the day. From MET Norway, for where the phone is (about 2 km). | Yr-symbol og temperatur ved datoen; trykk for dagen. Fra MET Norge, for der telefonen er (omtrent 2 km). |
| `device.weather.zone` | When the position isn't fresh, use | Når posisjonen ikke er fersk, bruk |
| `device.weather.zone_sub` | The phone's position is fresh while Location is Regularly. Otherwise the weather is for this zone. | Posisjonen er fersk når Posisjon står på Jevnlig. Ellers gjelder været for denne sonen. |
| `device.weather.zone_none` | None | Ingen |
| `device.weather.zone_empty` / `_empty_link` | This phone has no zones yet. / Add a zone | Telefonen har ingen soner ennå. / Legg til en sone |
| `device.weather.needs_position` / `_link` | No fresh position and no zone - the lock shows no weather now. / Location Settings | Ingen fersk posisjon og ingen sone - låseskjermen viser ikke vær nå. / Posisjonsinnstillinger |
| `device.weather.needs_update` | This phone's Vibb One doesn't show weather yet - it comes with its next update. | Vibb One på denne telefonen viser ikke vær ennå - det kommer med neste oppdatering. |
| `security.event.lock_weather_changed` / `_zone_changed` | Lock screen weather changed / Lock screen weather zone changed | Vær på låseskjermen endret / Sone for vær på låseskjermen endret |
| `security.detail.lock_weather.on` / `.off` | phone {device}: weather on the lock screen: on / off | telefon {device}: vær på låseskjermen: på / av |
| `security.detail.lock_weather_zone` / `_none` | phone {device}: lock screen weather zone: {zone} / none | telefon {device}: sone for vær på låseskjermen: {zone} / ingen |

  The `i18n::tests` keep both catalogs equal and the page joins `MOVED`. No other PWA page changes but the phone's
  page's row; the parent never sees the cell or the
  forecast in the PWA.

## 7. Tests (lean, contract-pinned)

### 7.1 The contract file

- **`server/testdata/weather.json`**, byte-identical in `launcher/app/src/test/resources/` (the one shared file; the
  symbol mapping is the launcher's alone, §4.1):
  - `forms`: `{"thunder": ["from","today","now","tomorrow"], ..., "very_warm": ["today","tomorrow"]}` and `now_keys`
    (`rain_in`, ...) - everything the server may send.
  - `words`: vectors `{name, zone, now, base, set, expect}` - `base` one hour's values applied to every hour of two
    local days, `set` overrides by local hour range (`{"from": "14:00", "to": "17:00", "sym": "rain", "mm": 0.8,
    "pop": 80}`), `expect` `{key, form, at?}` or `null`. Rows: each rule alone; the first-match order (thunder over
    heavy rain; snow over cold); the chance arm on a rain day and its absence on a snow day; `maybe_snow` and a sleet
    day's `maybe_rain`; "high" from the window, not the evening; `today` at 06:30; `now` at 07:42 and at 14:05; rain
    that stopped by 16:30 → none; 17:59 → today, 18:00 → tomorrow; the 23:30 and 00:10 entries; the DST days
    2026-03-29 and 2026-10-25 in Europe/Oslo; a missing gust and a missing chance; no hours → none.
  - `periods`: a day with the morning's hours from a merged older fetch, one without them (all `null`), the mm sums.
  - `dark`: `{lat, lon, date, zone, expect, sunset_met, civil_ref}` - Oslo 2026-11-03, Oslo 2026-06-21 (`at` after
    midnight: the crossing after that date's solar noon), Trondheim 2026-06-21 (`never`), Tromsø 2026-12-21 (`at`),
    Longyearbyen 2026-12-21 (`all_day`), Tromsø 2026-06-21 (`never`). `expect` is written from the code
    (`WEATHER_CONTRACT_WRITE=1`), never by hand; `sunset_met` is MET Sunrise 3.0's sunset (fetched once by hand, `null`
    in the `never`/`all_day` rows) and `civil_ref` a second reference's civil dusk (USNO's or timeanddate's, for Oslo
    and Tromsø); the test checks each within 2 min (server QA L1).
  - `cells`: `{lat, lon, cell, query}` - Oslo; a point on a 0.01° edge; a negative latitude and longitude; 179.99 →
    −180.00; 89.999.
  - `nowcast`: the four lines and the three silences (no coverage, dry, `temporarily unavailable`), and one at `now`
    7 min after the first step (server QA M3).
  - `answer` (§3.5) and `now_answers`, with `answer_now_ms` and `answer_line` (the entry and words a phone shows at
    that time; launcher QA L15), produced by `weather::tests::the_contract` from `server/testdata/weather/
    met_complete.json` and `met_nowcast.json` (real MET answers trimmed to a day, CC BY 4.0 MET Norway - said in
    `server/testdata/weather/README.md`) at a fixed `now` and zone (`WEATHER_CONTRACT_WRITE=1` rewrites them).
- **CI** (server QA L9, launcher QA L15): `launcher.yml`'s path regex gains `server/testdata/weather\.json` and
  `scripts/yr-symbols\.py`, and a `python3 -I scripts/yr-symbols.py --selftest` step; `server-ci.yml`'s gains the
  launcher's copy; `music.yml` needs nothing (the music app reads neither). The root `CLAUDE.md` gets the file in its
  contract list and in the CI paragraph.

### 7.2 Server (`weather::tests`, `src/tests/weather.rs`)

- Pure: every `words`, `periods`, `dark`, `cells` and `nowcast` row; `parse_forecast` on the sample (and a body that
  isn't the shape → `unreadable`, a panic inside is caught); `merge` keeps 24 h back; `Kind::of` over every code in
  the sample; the copy equals the launcher's.
- Through the router with `CannedFetch`: the switch off → `off` and no outside request; `on_request` with a fresh fix
  and no zone → `no_position`, no request; a mock fix (and `mock` NULL counts as not mock), one 7 h old or one in the
  future → no fix; a good fix → exactly one MET request to `.../complete?lat=59.94&lon=10.76` with the User-Agent (and
  `MET_CONTACT` appended when set); a weather zone with Location `on_request` and with `off` → the zone's cell; a
  zone of another phone refused at save; a deleted zone clears `weather_zone_id`; a second phone in the cell → no new
  request; past `Expires` → `If-Modified-Since` equals the stored `Last-Modified`, a 304 moves `checked_at`; `Expires −
  Date` with a skewed `Date`; a 203 is served; a 429 (seconds and an HTTP date) backs off and the old hours are
  served; a 5xx with no hours → `unavailable`; a slow fetch → the answer by ~6 s, and the cache filled after; the
  first answer for a new cell has `place: null`, one Kartverket request follows, the second answer has the name;
  outside the box → no request, `NULL`; `/now` on no coverage → `{"status": "ok", "line": null}`, with no position →
  `none`; the per-phone limits → 429; Location changed to Off empties `weather_cells` and `weather_places`, and the
  hourly prune with a phone already Off doesn't (server QA H1); retention drops a cell unasked for 48 h and a place
  past the location retention; a failed fetch logs no `lat=` (server QA L10).
- PWA: the two saves land on their anchors, log once per change, nudge; an unauthenticated POST writes nothing; the
  page shows each note in its case.
- `policy_json_keys_snapshot` with `launcher_ui.lock_weather`.
- **Mutations for the code QA** (~8): the window's start not moved to the current hour; `>=` → `>` at 18:00; rule 5's
  chance arm without the kind; the cell's longitude step 0.02; `next_fetch_at` from `Expires` alone (skew); the Off
  erase moved into prune; the source order with the zone before the fresh fix; the off switch still fetching.

### 7.3 Launcher

- `WeatherModelTest`: the entry pick at `at_ms` − 1 / + 0 / + 1 h; 3 h + 1 ms hides; 60 min + 1 ms adds "updated";
  the policy off hides; unknown key, symbol and card date (no chevron); `weatherPullDue` (55 min, the 5-min try gap);
  `weatherKeep` (each status, the switch off, a location-mode change); `weatherFold` (each event);
  `weatherNowShown` (1 s, a stale generation, rows held); `weatherDegrees` (−0.0 → "0°", U+2212); `weatherHour` nb/en,
  24 h and 12 h, a :30 start; `weatherMm` nb/en and under 0.1; `weatherPlace` and `weatherWordsFit` at §5.3's table.
- `WeatherContractTest`: the file equals the server's copy; `answer` and `now_answers` decode and give `answer_line` at
  `answer_now_ms`; every `forms` pair and `now_keys` key maps through `WeatherWords` to a string in `values` and
  `values-nb` with the placeholder it needs; every code in `answer` maps to a drawable and a name; `values-nb` names
  every `weather_symbol_*` of `values`; `answer_words` carries `from` (with `at_ms`) and `now`.
- `PolicyResponseCompatTest`: `lock_weather` read, missing = false.
- `LockGlanceLayoutTest`: the date row's parts from the XML; the own-line slot's line in the 473 / 497 dp cases (and
  "Neste" yielding there); the open card's height in the model scrolls, never clips; the profile clock's date baseline
  unchanged with the row; the mark's 48 dp hit rectangle in both places.
- `LockGlanceTest`'s touchables test follows `<include>` and lists the mark and the card as touchables that keep no
  touch, beside the timer tile and the "more" line - not in `swipeControls` (launcher QA L8).
- `WeatherGuardTest` (§5.4): no `android.location` import and no URL literal under
  `weather/` and in `WeatherLockPanel`.
- Emulator (a new § in `docs/testing/emulator.md`: a canned server answer from the contract's `answer`): the line at
  320/360 dp and fonts 1.0/1.3/2.0 (the place held through an hour's turn); the card open/fold, folded after power
  off/on; a late nowcast not inserted; TalkBack; the hit area; the symbols on a bright photo and on Sky/Sunset
  (launcher QA L15); the clock twin unchanged on `jelly36` with the weather off and on.

## 8. Steps

**Step 1 - server and the contract** (one commit for the API, as `api-both-dirs` asks): migration 0122; `weather.rs`,
`weather_cache.rs`, the two routes, `Kind::Weather` and `SourceResponse.expires`/`date`,
`AppState.weather_fetch`/`weather`, `ForkConfig.met_contact` (DEPLOY.md: the env var, its privacy sentence, backups),
the Off erase in `update_location_policy` and the age prune; the PWA's Device › Weather page with its two rows and
words; `scripts/yr-symbols.py` and its generated outputs (moved here from step 2 at the build); on the
launcher side only the DTOs (`LauncherUi.lockWeather`, `WeatherAnswer`, `WeatherNow`), `PolicyResponseCompatTest`, the
copy and `WeatherContractTest`'s decode and file checks; NOTICE.md; `net.rs`'s doc line; CLAUDE.md's contract list and
both workflows' paths. A server alone with step 1 is harmless: no launcher asks.

**Step 2 - the launcher**: `WeatherModel`, `WeatherWords`, `WeatherStore`,
`WeatherPull` and its triggers and limits, the glance's row, slot, marks, hit area and card, `LockClockFace`'s
`dateRow` and short date, `WeatherLockPanel`, the strings, the licences paragraph, the capability `lock_weather_v1`,
the layout, touchables and guard tests, the emulator runbook. The notes: `docs/notes/launcher/lock-and-glance.md`
("Weather on the glance (design 75)") and `docs/notes/server/launcher-ui.md` ("Lock screen weather") get their
sections; the cores stay as they are.

**Step 3 - dropped** (the user, 2026-11-03: "nei jeg angrer meg, jeg vil ikke ha den siste rute greia"): no
migration 0123, no second switch, no cell from the phone.

Why this split: step 1 is all of the outside traffic and the contract, testable without a phone; step 2 is the
glance's layout, where the risk is the face's placement. Sizes:
step 1 ~650 lines of Rust (~250 of them pure rules and the solar function); step 2 ~650 lines of Kotlin and XML + ~300
of Python + the generated files.

## 9. Risks

1. **The location dependency.** Weather needs Location "Regularly" or a chosen zone; a family on the default "On
   request" with no zone sees nothing. The PWA says so (§6).
2. **The face's placement.** The date moves into a row on a page whose clock and date are placed to Android's pixels
   (design 59d step L). The row reports the date's baseline, `LockGlanceLayoutTest` pins it, and the emulator's clock
   twin is checked with the weather off and on.
3. **The place name on a locked screen.** Anyone holding the phone reads the nearest settlement's name (as in the
   approved mockup). It is coarse (the cell's centre, a district or settlement first, an estate's name only when none),
   and the switch is the parent's, default off; the PWA sub-line says "for where the phone is". With a weather zone it
   names the zone's area, not where the kid is.
4. **MET's terms and versions.** A 403 (User-Agent) or a 203 (deprecation) is logged loudly and the weather hides by
   age, nothing breaks; a new Locationforecast version is a server release. One shared User-Agent string without
   `MET_CONTACT` can be blocked for every install; DEPLOY.md and the warning push operators to set it (D8). MET Nowcast's
   coverage values could change wording: anything but `ok` is silence.
5. **The symbols' fidelity.** Dropping the shadows flattens them a little; the baked multiply keeps the shading;
   checked by eye on the emulator against upstream's PNG previews before step 2 merges. A future upstream feature the
   converter doesn't know fails the script rather than drawing wrong.
6. **A stale or wrong answer.** A phone that can't reach the server shows the last answer up to 3 h (with "Oppdatert
   kl." after 1 h), then nothing; the entry schedule keeps the words honest within those hours; a location-mode change
   clears it at once.
7. **Battery and load.** No new wake-up: the pull rides the sync and the screen-on; one small GET an hour, gzip. The
   server's outside traffic is about one MET request per occupied cell per hour plus nowcasts on card opens (bounded
   by `Expires`, 3 min) - far under MET's 20 requests/s.

## 10. Open questions for the user

None. The one open question (the phone's last-known position, D18) was answered no (§12).

## 11. The revision (2026-11-03, after the two design QAs and the user's answers)

- **The user's answers**: the narrow phone puts the mark on its own line under the date, words kept, chosen at a
  lock-up (D15); with no fresh position the parent's chosen zone stands in (D5, §3.1 `weather_zone_id`, §6's zone
  row); the phone's last-known cell is designed as step 3, pending (D18, §10).
- **Server QA**: H1 the Off erase moved to `update_location_policy` on the change to Off (D13, §3.1); M1
  `weather_places.asked_at` and its retention; M2 Kartverket spawned after the answer, its own ungated client, 2 + 2
  pages; M3 the nowcast computed from `now` at each answer; M4 the `MET_CONTACT` warning, DEPLOY.md and `net.rs`'s
  doc; M5 every rule input defined (D4), `thunder_pop` dropped; L1 the dusk's crossing after solar noon, the vectors
  from the code with a second civil reference (and 17:02, not 17:04); L2 `Expires − Date`, `Retry-After` as a date; L3
  `COALESCE(mock, 0)` and no future fix; L4/O2 one forecast lock, `updated_at` dropped; L5 the missing words; L6 the
  place types' order; L7 backups; L8 the `:30` row dropped, the rule defined; L9 CI; L10 no coordinates in logs, tested;
  O1 MET's `symbol_code` sent, the server's symbol table and `weather_symbols.json` gone; O3 fewer Kartverket pages.
- **Launcher QA**: H1 a 48 dp hit area by `TouchDelegate`, the layout untouched; M1 the place fixed between lock-ups,
  words dropped instead of a move, the short date on the switch, the budgets recomputed; M2 the nowcast's 3 s timeout,
  1 s insert window, generation and `rowsHeld`; M3 the pull's catch-all, 15 s timeout and one in-flight guard, the
  server's 6 s answer; M4 a location-mode change clears the kept answer; M5 plurals and whole-sentence spoken text, the
  shown "kl." left to TTS; L1 the centred room; L2 `LockClockFace`'s list type, gravity loop and `FirstCapital`; L3
  `getDesiredWidth` and horizontal scrolling; L4 the symbol from the text size, the words' span; L5 two mark
  instances; L6 the rows' gap under the card; L7 no dimmed periods, the two tokens, mm `INVISIBLE`; L8 the touchables
  test through includes; L9 the pure keep, fold and nowcast steps; L10 a `when` table, not `getIdentifier`; L11 the
  hour keyed on the catalog, minutes when not :00; L12 re-pick at resume, no chevron without a card; L13 "Neste"
  yields to the own line; L14 the card's place explained in §0; L15 the converter's details and the multiply bake, the
  bright-wallpaper shot, the contract's `answer_now_ms`/`answer_line`.
- **Not taken**: the launcher QA's suggestion to cut "Oppdatert kl." first - it is the user's ask and cheap (D11).

## 12. Changes at step 1's build (2026-11-03)

- **Step 3 dropped** (the user: "nei jeg angrer meg, jeg vil ikke ha den siste rute greia", after a first "Ja til
  bryteren takk"): D5's source 2, D18, `Cell::parse`, migration 0123, the third PWA row and the phone's cell are gone;
  the order is the fresh stored fix, the parent's chosen zone, nothing. The server QA's R3 (the cell in a header)
  falls with it.
- **Device › Weather** (the user's "egen Vær settings ... inne på en spesifikk device sin side"): the two rows move off
  Lock Screen & PINs to a page of their own, a row on the phone's page with "På" / "Av"; the keys are `device.weather.*`
  and `device.page.weather` (§6).
- **The symbol script in step 1** (the brief): `scripts/yr-symbols.py` and its outputs come with the server and the
  contract; step 2 keeps the code that uses them.
- **The server QA's R1 and R2**: a zone's delete already nudges the phone (`handlers::zones::saved`), and the foreign
  key clears `weather_zone_id` (a test pins both); 0122's comment and `docs/notes/server/zones.md` say that rebuilding
  `device_zones` would clear every weather zone.
- **Code QA fix round** (`docs/review/qa-75-code-server.md`, `qa-75-code-launcher.md`): the period now running takes
  its symbol and degrees from its first hour we have when its start (or middle) is gone (server M1, §3.2); the contract
  gains `answer_words` (the recorded answer with rain laid over 16:00-18:00), so the words' wire shape is pinned on both
  sides (launcher M1); an unusable 200/203 waits at least until its `Expires` (server M2); an erase keeps a lookup or a
  nowcast in flight from writing back, forgets the failed lookups, and an ask that overlaps it answers `unavailable`
  (L1, L2); a cell lives at most the longest location retention (L3); the converter's four gaps are closed (launcher M2,
  §4.1 - the 83 drawings unchanged), `weather_symbol_keys.json` and `WeatherSymbols.CODES` are gone (launcher L2), and
  the self-test runs in the always-run changes job with a vendored upstream symbol as its drift check (L1, L6).
- **Step 2's build** (the launcher): **R1** - a zone's save or delete only nudges, and nothing in the policy changes,
  so a sync the server nudged (`SyncRunner`'s reason `sse`) pulls the weather when the last try is at least
  `WEATHER_NUDGE_GAP_MS` (3 min) old, under the one in-flight guard (the coordinator's lean; the cheapest correct
  fix - no new policy key). The launcher review's **L1**: the hit area's top is capped at the clock's baseline and
  the rest goes below (`weatherHitRect`). **L2**: the own line's room is held (INVISIBLE) from a lock-up while a line
  that had gone stale is being pulled again, until the next lock-up - the rows never move when it lands. **L3**:
  `weather_spoken_mm` is a plain string, plural in English ("1.0 millimetres": the value always shows a decimal).
  `weatherKeep` is split into `weatherKeep` (the pull's result) and `weatherPolicyStep` (the policy's change); the
  hour's patterns live in `ClockFormat.weatherHour` (the one place a time pattern may be spelled); the mark is hidden
  until its place is known; `legal_info_weather` is English like the other licence paragraphs
  (`donottranslate.xml`).
