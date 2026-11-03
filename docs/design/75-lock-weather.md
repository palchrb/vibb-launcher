# 75 - Weather on the lock glance: yr's symbol and the degrees by the date, the day folded out

Status: design note, 2026-11-03, on `main` at 6539d573; to be built on branch `lock-weather` (PLAN queue: "Weather on
the lock glance - APPROVED 2026-11-03"). Both directories. Approved mockup: v7, artifact QVvKwiZWkNFmpCTFxf7LYu
(`yr-glance7`; its script holds the rule table). The user, 2026-11-03: "la oss fokusere på lock screen
implementasjonen først"; "La oss droppe påkledningsrådet og refleksrådene ... Barnet kan heller vurdere påkledning
selv"; "Liker at det står når det blir mørkt"; "Jeg liker best den lille pilen"; "for mye med radaren synlig"; "OK vi
kjører standard" (MET's User-Agent).

## 0. The shape

**Phone.** On the lock's glance page, after the date: yr's symbol, the temperature now and, only when the day has
something to say, a few fixed weather words, then a small chevron - "Man. 3. nov.  ☁ 4° · Regn fra kl. 14 ⌄". No
frame, no pill. A tap (no unlock: weather isn't private) folds a card out under the clock face's lines, above the
tiles and rows: at its top one line of text only when rain is due or falling within ~90 min ("Regn om ca. 25 min",
"Opphold om ca. 45 min"), then morning / afternoon / evening (symbol, degrees, the period's summed precipitation; the
current period highlighted), then the foot "Nydalen · 2° / 7° · Mørkt kl. 17:04". Another tap, or the screen going
off, folds it. At large font scales the words go before anything is cut; symbol and degrees stay.

**Server.** The phone only ever asks our server (`GET /api/devices/weather`, `GET /api/devices/weather/now`). The
server takes the phone's newest stored fix, snaps it to a grid cell of about 2 x 2 km, and answers from a per-cell
cache of MET Norway's Locationforecast 2.0 (`complete`), fetched only past MET's `Expires`, conditionally. The words,
the periods, the darkness and the nowcast line are computed on the server, in the phone's own time zone; the phone
renders keys and numbers. The place name comes once per cell from Kartverket's place-name API.

**PWA.** Device › Lock Screen & PINs › Lock screen gets "Vær på låseskjermen" / "Weather on the lock screen", off by
default, in the policy as `launcher_ui.lock_weather`.

**Out of scope** (decided with the user): clothing advice and reflex hints; radar bars or maps; a weather app and
weather in Kalender (both on the longlist, PLAN "Asked for"); notifications; choosing other places; an hour-by-hour
view.

## 1. Decisions

**D1 - The server computes everything; the phone renders keys.** The words, the card's periods, low/high, the dusk
and the nowcast line are the server's (`src/weather.rs`, pure). The phone gets a rule key and a form
(`{"key": "rain", "form": "from", "at_ms": ...}`) and maps it to a fixed string in `values`/`values-nb`. *Why:* one
implementation of the rule table and the time arithmetic (DST, the 18:00 switch, midnight), pinned by one vector file;
the launcher stays a renderer, and a threshold change is a server release only.

**D2 - Time-dependent answers carry an hour schedule.** The answer's `hours` holds four entries, the UTC hour that
contains "now" and the next three, each with that hour's symbol, temperature, words and which card day/period applies.
The phone shows the entry whose hour contains its clock. *Why:* the phone pulls hourly, but "Regn fra kl. 14" must
become "Regn nå" at 14:00 and the words must switch to tomorrow at 18:00 without a pull. Four entries cover the 3 h
stale limit (D11); past the last one the line hides.

**D3 - The window, the forms, the day.** Local time `t` (the phone's zone, D12):
- before 18:00: the day is today and the window is `[max(07:00, start of t's hour), 18:00)`;
- from 18:00: the day is tomorrow and the window is tomorrow's `[07:00, 18:00)`.

An hour belongs to the window when its start (local) is inside it. A precipitation rule's **form**: `now` when its
first wet hour is the window's first hour and `t ≥ 07:00`; `today` when it is the window's first hour and `t < 07:00`;
`from` (with that hour) otherwise; `tomorrow` (no hour) whenever the day is tomorrow. The other rules have `today`
and `tomorrow` only ("Veldig kaldt", "Veldig kaldt i morgen"). *Why:* the window starting at the current hour retires
rain that has already stopped; "Regn i dag" is the mockup's "rain already at 7"; after 18 a bare "Regn fra kl. 14"
under today's date would read as today, so tomorrow's words say "i morgen" and drop the hour (the card shows the
hours).

**D4 - The rule table (first match wins; none = no words).** Over the window's hours (§3.2 for the inputs):

| # | Key | When | nb (`from` / `today` / `now` / `tomorrow`) |
|---|---|---|---|
| 1 | `thunder` | a symbol with `thunder` | Torden fra kl. 15 / Torden i dag / Torden nå / Torden i morgen |
| 2 | `snow` | snow ≥ 1.0 mm | Snø fra kl. X / Snø i dag / Snør nå / Snø i morgen |
| 3 | `sleet` | sleet ≥ 0.5 mm | Sludd fra kl. X / Sludd i dag / Sludd nå / Sludd i morgen |
| 4 | `heavy_rain` | rain ≥ 5.0 mm | Mye regn fra kl. X / Mye regn i dag / Mye regn nå / Mye regn i morgen |
| 5 | `rain` | rain ≥ 0.5 mm, or chance ≥ 60 % on a rain day | Regn fra kl. X / Regn i dag / Regn nå / Regn i morgen |
| 6 | `maybe_rain` / `maybe_snow` | all precipitation ≥ 0.1 mm, or chance ≥ 30 % | Kanskje litt regn (snø) / … i morgen |
| 7 | `very_cold` | feels-like ≤ −10° | Veldig kaldt / Veldig kaldt i morgen |
| 8 | `windy` | gust ≥ 15 m/s | Mye vind / Mye vind i morgen |
| 9 | `very_warm` | high ≥ 25° | Veldig varmt / Veldig varmt i morgen |

The day's **kind** is the kind with the most millimetres in the window (none, or a tie: rain; a tie between snow and
sleet: snow). Rule 5's chance arm and rule 6's snow form go by it, so 0.3 mm of snow at 70 % reads "Kanskje litt snø",
never "Kanskje litt regn". The first wet hour of a precipitation rule is its kind's first hour with ≥ 0.1 mm; for rule
5 matched by the chance alone, the first hour with a chance ≥ 60 %; for rule 1 the first thunder hour. No clothing
words and no "refleks" anywhere (the user). *Why the extensions:* the user listed the nb words for the main forms; a
`now` and a `tomorrow` form for every precipitation rule and "Kanskje litt snø" keep each string fixed and true (no
gluing), at no cost in the rules.

**D5 - The position is the server's newest stored fix, only while Location is "Regularly".** The weather uses the
phone's newest non-mock `device_locations` row with `captured_at` at most 6 h old, and only while the phone's
`location_mode` is `interval`. Otherwise the answer is `no_position` and the weather hides. *Why:* nothing new leaves
the phone and the server learns nothing it doesn't hold already; "On request" means the phone takes no fix unless the
parent asks (an active fix lights Android's location indicator, `server/LocationPolicies.kt`), so its last fix may be
days old - the wrong town's weather is worse than none; "Off" has no fixes at all. 6 h covers the longest interval
(240 min) with one missed report. The PWA's switch says when Location isn't "Regularly" (§6). See open question 1.

**D6 - The grid cell.** Latitude to the nearest 0.02°, longitude to the nearest 0.04° (≈ 2.2 x 2.2 km at 60° N,
2.2 x 1.5 km at 70° N); the cell is kept as two integers (`lat_i = round(lat / 0.02)`, `lon_i = round(lon / 0.04)`,
the longitude wrapped into [−180, 180), the latitude clamped to ±90) and every URL is written from the integers with two
decimals ("lat=59.94&lon=10.76"; MET allows four). *Why:* MET's model grid (MEPS) is 2.5 km, so a finer cell buys
nothing; the cell is what MET, Kartverket and our cache see, and phones in one cell share one fetch; integers avoid
"59.940000001" and "−0.00".

**D7 - Fetch politely, on demand, from the cache.** A forecast is fetched when a phone asks and the cell's cache is
due: never before `max(Expires, last fetch + 30 min) + jitter (0-120 s)`; with `If-Modified-Since` = the stored
`Last-Modified` verbatim (MET's rule); `Accept-Encoding: gzip` (reqwest's); HTTPS only; at most one fetch per cell at a
time (a per-cell lock, the row read again inside it). No background sweep of forecasts: a cell nobody asks for is
never fetched. *Why:* MET's terms (§2.1); phones pull hourly, so on demand costs a phone at most one MET round trip an
hour, and an idle family costs MET nothing.

**D8 - MET's User-Agent** (the user: "OK vi kjører standard"): `vibb-server/<CARGO_PKG_VERSION>
github.com/<SERVER_RELEASE_REPO>` (default `palchrb/vibb-launcher`), with the operator's contact appended when the
env var **`MET_CONTACT`** is set (`vibb-server/0.65.0 github.com/palchrb/vibb-launcher ola@example.no`). `MET_CONTACT`
is read once in `ForkConfig`: 3-100 printable ASCII characters with no control or quote characters, else logged and
ignored. Only MET's requests carry it; security review P5's neutral `net::USER_AGENT` ("vibb/1.0") stays for every
other request, Kartverket's included. *Why:* MET blocks unidentified clients and asks for a contact; P5's neutrality
still holds where nobody asks for more. The repo slug follows `SERVER_RELEASE_REPO`, so a fork points at itself.

**D9 - Nowcast only when the card opens.** `GET /api/devices/weather/now` (the phone asks when the card opens, at most
every 2 min) reads MET Nowcast 2.0 for the same cell, cached in memory per cell until `max(Expires, fetch + 3 min) +
jitter (0-20 s)`; an answer older than 10 min is never used. `meta.radar_coverage` other than `"ok"`, a 4xx (outside
the Nordic area), or any failure: no line. *Why:* radar data is stale after ~5 min (the user); the hourly pull would
waste it; "no coverage" is simply silence.

**D10 - "Mørkt" is the end of civil twilight**, computed on the server for the cell's centre (the sun 6° below the
horizon, NOAA's solar position algorithm), no outside request. Three outcomes: `at` (an instant; may fall after
midnight, it is shown as it is), `never` ("Lyst hele natta": the sun stays above −6°, white nights and midnight sun)
and `all_day` ("Mørkt hele dagen": it never rises above −6°, the depth of polar night). *Why:* at sunset it is still
light for ~45 min in Oslo in November (MET Sunrise: sunset 16:16 on 2026-11-03, civil dusk ~17:04); "mørkt" means
dark. In Tromsø's polar night civil twilight still happens around noon, so `at` (≈13:54 on 2026-12-21) is right there.

**D11 - Freshness.** The answer's `checked_at_ms` is when MET last answered 200 or 304 for the cell. On the phone: an
answer checked more than 60 min ago adds "Oppdatert kl. HH:MM" to the card; more than 3 h ago, or no hour entry
covering now, hides the line (and folds an open card). The phone pulls when its answer is older than 55 min (§5.4).
*Why:* the user's ">3 h hides"; 55 min with the 30-min backstop gives a pull about hourly.

**D12 - The phone's zone.** The words, the periods and the "kl." hours are in the phone's own reported
`time_state.zone` (design 34 P13), else Phone Setup's zone - today's `device_api::extra_time_day_zone`, renamed
`phone_zone` and shared. `chrono-tz` (already a dependency) does the conversion; every hour is a UTC hour converted to
local time, so 23- and 25-hour days need nothing special. *Why:* the kid's day is the phone's day; no external
timezone service.

**D13 - The place name** comes from Kartverket once per cell, at the cell's centre, and is kept per cell (`NULL` =
looked up, no name: outside Norway or nothing near). **Location Off erases the weather caches** (`weather_cells`,
`weather_places`) together with the fixes. *Why:* the user's "cached per cell for good"; but a kept list of cells is a
coarse trail of where the family has been, and "Off erases every fix at once" (0.57.0) must stay true - the cache has
no device id, so it goes whole, and refetching names is cheap.

**D14 - The date line on the glance only, short date when the weather shows.** The weather mark joins the glance's
date; the time-rule screen (which shares `LockClockFace`) gets none. While the weather shows, the no-profile glance
uses the short date (`EEEdMMM`, "Man. 3. nov.", as the mockup and as the profile phones already do); without it the
long date stays. *Why:* the long date ("Mandag 3. november") plus symbol, degrees and words needs ~335 dp; the short
one leaves the words a chance on a 360 dp phone.

**D15 - Room on the line: a ladder, never a cut.** Pure `weatherPlace(...)` (§5.3) picks the first that fits: the mark
beside the date with words; the mark on its own line under the date with words; beside the date without words; its
own line without words. Words are never ellipsized. *Why:* the user's "drop the words, never cut them mid-word";
the own-line step keeps the words on the 320 dp Jelly Star, where they rarely fit beside the date (open question 2).

**D16 - The symbols are yr's darkmode set as VectorDrawables**, made by `scripts/yr-symbols.py` from
nrkno/yr-weather-symbols at a pinned commit (§4). Shadows (`filter`) and `mix-blend-mode` layers are dropped; the
rest converts. *Why:* the user's choice; CC BY 4.0 is GPL-compatible with attribution; vectors stay sharp at every
density (~330 KB of SVG in).

**D17 - Default off, per phone, in the policy.** `device_policy.lock_weather` (0122), `launcher_ui.lock_weather`
(always sent; a launcher without the key reads off). The server answers `off` and fetches nothing for a phone with the
switch off. *Why:* the parent decides what the lock shows; defense in depth if a phone asks anyway.

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
  (`altitude` left out: MET's topography is used, and the phone's altitude stays ours). GeoJSON; `properties.meta.
  updated_at`; `properties.timeseries[]` sorted by `time` (UTC), each with `data.instant.details` (`air_temperature`,
  `apparent_air_temperature` - in `complete` -, `wind_speed`, `wind_speed_of_gust`, ...) and `next_1_hours` /
  `next_6_hours` / `next_12_hours` with `summary.symbol_code` and `details` (`next_1_hours`: `precipitation_amount`,
  `probability_of_precipitation`, `probability_of_thunder`). Hourly steps for ~60 h (a sample on 2026-11-03 at 59.94,
  10.76: 85 entries, 53 with `next_1_hours`, the hourly part to 2026-11-05T17:00Z), then 6-hourly to 10 days. The same
  sample: `Expires` 32 min after the request, `Last-Modified` set, ~90 KB decoded.
- **Nowcast 2.0** (https://api.met.no/weatherapi/nowcast/2.0/documentation): `GET https://api.met.no/weatherapi/
  nowcast/2.0/complete?lat=..&lon=..`; Norway, Sweden, Finland and Denmark; updated every 5 min; the sample:
  `properties.meta.radar_coverage` `"ok"`, 23 entries 5 min apart (~110 min), each `instant.details.precipitation_rate`
  (mm/h), the first with `next_1_hours.summary.symbol_code`; `Expires` 3 min. The documented coverage values are
  `ok`, `temporarily unavailable`, `no coverage` (an error status no longer).
- **Credit**: "Data: MET Norway" with the licence (§4.3).

### 2.2 Kartverket's place names

- `GET https://api.kartverket.no/stedsnavn/v1/punkt?nord=59.94&ost=10.76&koordsys=4258&radius=2000&utkoordsys=4258
  &treffPerSide=500&side=1&filtrer=navn.stedsnavn.skrivemåte,navn.navneobjekttype,navn.meterFraPunkt,metadata.
  totaltAntallTreff` (OpenAPI at https://api.kartverket.no/stedsnavn/v1/openapi.json, "Kartverkets åpne API for søk
  etter stedsnavn" 1.1; no login; `ws.geonorge.no/stedsnavn/v1` is a proxy to it "until further notice"; radius at
  most 5000 m, at most 500 per page; changes announced 3 months ahead on status.kartverket.no). The answer:
  `navn[]` with `meterFraPunkt`, `navneobjekttype` and `stedsnavn[].skrivemåte`. **Not sorted by distance** (a page
  holds hits up to its radius), and the nearest hits are houses, farms and streets (a test at 59.95, 10.76: 447 hits
  in 2 km, 333 of them `Adressenavn`; the nearest settlement `Tettbebyggelse` "Tåsen" at 880 m).
- **The pick**: the nearest hit whose `navneobjekttype` is one of `Bydel`, `Tettsteddel`, `Tettbebyggelse`,
  `Boligfelt`, `Grend`, `Bygdelag (bygd)`, `Tettsted`, `By` (spelled as `/navneobjekttyper` lists them), its first
  `skrivemåte`; pages 2-3 while `totaltAntallTreff` says there are more; none within 2000 m → once more at 5000 m
  (`Tettsted`, `By`, `Tettbebyggelse`, `Bygdelag (bygd)`, `Grend`; up to 5 pages); none → `NULL`. A cell outside
  57.5-71.5° N, 4.0-31.5° E is never asked (`NULL` at once). The name is cleaned (control and bidi characters out,
  whitespace collapsed) and cut to 40 characters at a word boundary.
- **Licence**: Kartverket's open data is CC BY 4.0 (Geonorge's SSR record); the implementer checks the
  kartkatalog.geonorge.no entry for "Stedsnavn" and cites it in NOTICE.md. Credit "Stedsnavn: © Kartverket".

## 3. Server

### 3.1 Data (migration `0122_lock_weather.sql`)

```sql
ALTER TABLE device_policy ADD COLUMN lock_weather INTEGER NOT NULL DEFAULT 0;

-- One forecast per grid cell (design 75 D6-D7): no device id, no time of who asked beyond the last ask.
CREATE TABLE weather_cells (
    id INTEGER PRIMARY KEY,
    lat_i INTEGER NOT NULL,            -- round(lat / 0.02)
    lon_i INTEGER NOT NULL,            -- round(lon / 0.04), wrapped
    hours_json TEXT,                   -- the compact hours (§3.2), NULL before the first 200
    updated_at TEXT,                   -- MET's meta.updated_at
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
    PRIMARY KEY (lat_i, lon_i)
);
```

The nowcast cache and Kartverket's retry gate (a failed lookup isn't retried for 1 h) are in memory only
(`AppState.weather`, §3.4). Retention (`retention::prune`): a `weather_cells` row not asked for in 48 h goes;
`weather_places` stays until Location Off (D13). Switching a phone's Location to Off (`handlers::locate::
update_location_policy`'s erase) and `retention::prune`'s `LOCATION_OFF` pass both `DELETE FROM weather_cells` and
`DELETE FROM weather_places` in the same transaction as the fixes.

### 3.2 `src/weather.rs` - pure

- `Cell { lat_i, lon_i }`, `Cell::of(lat, lon)`, `Cell::query()` → `"lat=59.94&lon=10.76"` (from the integers),
  `Cell::centre()` → (f64, f64) for the dusk and Kartverket (`nord`/`ost`).
- `Hour` (the cache's compact row): `{t, sym, sym6, temp, feels, mm, pop, thunder_pop, gust}` - `t` the UTC hour,
  `sym` `next_1_hours.summary.symbol_code`, `sym6` `next_6_hours`'s, `temp`/`feels`/`gust` from `instant.details`
  (`air_temperature`, `apparent_air_temperature`, `wind_speed_of_gust`), `mm`/`pop`/`thunder_pop` from
  `next_1_hours.details`; every number `Option<f32>`. `parse_forecast(body) -> Result<(updated_at, Vec<Hour>), _>`
  keeps only entries with `next_1_hours` (the hourly part); a body that isn't the shape is `unreadable`, inside
  `catch_unwind` as calendar_feeds does. `merge(old, new, now)`: the new hours win; the old ones before the new
  series' first hour are kept back to 24 h before `now` (so this morning's period still has data at 15:00; §3.3).
- `Kind::of(hour)`: `snow` / `sleet` / `rain` from the symbol's name (`snow`, `sleet`, `rain` - MET's names, including
  its `lightssleet...` spellings), a symbol with none of them: `snow` below 0 °C, else `rain`; `thunder` when the name
  holds `thunder`.
- `words(hours, zone, at) -> Option<Words>`: §1 D3-D4. `Words { key: &'static str, form: Form, at: Option<DateTime>
  }`; the thresholds are named constants (`SNOW_MM = 1.0`, `SLEET_MM = 0.5`, `HEAVY_RAIN_MM = 5.0`, `RAIN_MM = 0.5`,
  `RAIN_CHANCE = 60.0`, `MAYBE_MM = 0.1`, `MAYBE_CHANCE = 30.0`, `WET_HOUR_MM = 0.1`, `VERY_COLD = -10.0`,
  `WINDY_GUST = 15.0`, `VERY_WARM = 25.0`, `WINDOW = 07..18`, `TOMORROW_FROM = 18`). Missing values count as
  nothing (no rain, no gust); feels-like missing falls back to `temp`.
- `periods(hours, zone, date) -> [Period; 3]`: morning 06-12, afternoon 12-18, evening 18-24 local; each `symbol` =
  the yr id (`symbols::id`, §4) of `sym6` at the period's first hour, `temp` = the temperature at its middle hour
  (09, 15, 21 - what the kid meets out of the door, not the period's extreme), `precip_mm` = the sum of its hours'
  `mm` rounded to 0.1. A period without its hours has all three `null`.
- `day(hours, zone, date, cell)`: the periods, `low`/`high` (min/max hourly `temp` of the local date's hours we have),
  `dark` (`dusk(cell.centre(), date, zone)`).
- `dusk(lat, lon, date, zone) -> Dark { At(DateTime) | Never | AllDay }`: NOAA's solar position (Julian day, the sun's
  declination and equation of time) solved for an altitude of −6°; the same function at −0.833° gives the sunset the
  vectors compare with MET's Sunrise 3.0 (§7).
- `entries(hours, zone, now) -> Vec<Entry>`: four, from the UTC hour containing `now`; each `{at, symbol, temp, words,
  card, current}` - `symbol` the hour's `sym` as a yr id, `temp` rounded half away from zero with `-0` → `0`, `card`
  the local date the card shows at that hour (D3), `current` 0-2 for the period containing it on today's card, `null`
  before 06:00 or on tomorrow's card. Entries without a forecast hour are left out.
- `nowcast_line(rates, first_symbol, first_temp, coverage) -> Option<NowLine>`: the 5-min steps of the first 90 min;
  a step is wet at `precipitation_rate ≥ 0.1` mm/h; coverage not `ok` or no step wet → `None`. Wet now: the first dry
  step → `dry_in` (minutes rounded to the nearest 5, at least 5), none dry → `rain_all` / `snow_all`. Dry now: the first
  wet step → `rain_in` / `snow_in`. Snow when the first step's symbol names snow, or with no precipitation symbol
  when its temperature is below 0 °C.
- `answer(...)` builds §3.5's JSON. Nothing here does I/O; every function is tested from `testdata/weather.json`.

`src/weather_symbols.rs` (generated, §4): `id(symbol_code) -> Option<&'static str>` over upstream's 83 keys.

### 3.3 `src/weather_cache.rs` - the fetches

- `AppState.weather_fetch: Arc<dyn music_net::Source>` - its own `Gated(HttpSource::new(), REQUEST_SPACING)`, not
  `music_fetch`'s gate: a card's nowcast must never queue behind a 120 s feed. Requests are `Reach::Public`, a new
  `Kind::Weather` (1 MB decoded, 15 s overall), MET's User-Agent set as a request header (`SourceRequest.headers`; a
  request header replaces the client's default). `SourceResponse` gains `expires: Option<String>` (the header as
  sent, at most 100 characters). The tests swap in `tests::music::CannedFetch`.
- `forecast(state, cell, now) -> Result<CellRow, sqlx::Error>`: upsert the row's `asked_at`; when `now <
  next_fetch_at` (or a fetch for the cell is in flight - the per-cell `tokio::sync::Mutex` in `AppState.weather.cells`,
  taken, the row read again), answer from the row. Else fetch:
  - **200** → `parse_forecast`, `merge` with the stored hours, store `hours_json`, `updated_at`, `last_modified`,
    `checked_at = now`, `failures = 0`; **203** the same, plus one `tracing::warn!` a day ("MET says Locationforecast
    2.0 is deprecated (203) - a new version is due") ; **304** → `checked_at = now`.
  - `next_fetch_at = max(Expires, now + 30 min) + jitter(0..120 s)`; no or unreadable `Expires` → `now + 30 min +
    jitter`.
  - **429** → `next_fetch_at = now + max(Retry-After, 10 min)`, doubling per failure up to 2 h, `warn!`; **403** →
    `now + 6 h`, `error!` ("MET refused the request (403) - check MET_CONTACT and the User-Agent"); 5xx, network,
    timeout, unreadable → 5, 10, 20, 40, 60 min by `failures`, `warn!`. The old hours stay and are served.
  - Logs name the cell by its row id and the code, **never coordinates** (as zones' security log); the body is never
    logged.
- `nowcast(state, cell, now) -> Option<NowLine>`: §1 D9, from `AppState.weather.nowcast: Mutex<HashMap<Cell,
  NowEntry>>` (pruned of entries older than 1 h at each write); a 4xx other than 429/403 remembers "outside" for the
  cell for 24 h (no ask per card open in Spain); 429/403 as above, in memory.
- `place(state, cell) -> Option<String>`: the `weather_places` row; none → Kartverket (§2.2, `Kind::Weather`, the
  neutral User-Agent) unless a lookup failed in the last hour; a successful lookup stores the name or
  `NULL`; a failure stores nothing. Asked inline on a forecast answer (the first answer for a new cell may wait ~1 s
  more; every later one reads the row).

### 3.4 Routes (`src/handlers/weather.rs`, declared in `device_routes.rs`)

`GET /api/devices/weather` (bearer) - in order: the phone's `device_policy` (`lock_weather`, `location_mode`); off →
`{"v":1,"status":"off"}`, no outside request; not `interval`, or no non-mock fix of the last 6 h → `no_position`; else
`Cell::of(fix)`, `forecast`, `place`, the zone (`phone_zone`: the newest report's `time_state.zone`, else Phone
Setup's) and `weather::answer(...)`. No hours stored yet and the fetch failed → `unavailable`. DB error → 500 (the
phone keeps what it has). 20 asks a rolling hour per phone (`music_net::PhoneAsks`) → 429 with `Retry-After`.
`Cache-Control: no-store`, gzip.

`GET /api/devices/weather/now` (bearer) - the same gates (off, no position → `{"v":1,"status":"none"}`), then
`nowcast`; 60 asks a rolling hour per phone → 429. Neither route reads or writes `device_locations`, and neither
returns coordinates or the cell.

### 3.5 The answers (the shapes of `testdata/weather.json`'s `answer` and `now_answers`)

```json
{
  "v": 1,
  "status": "ok",
  "checked_at_ms": 1793709715000,
  "place": "Nydalen",
  "hours": [
    {"at_ms": 1793707200000, "symbol": "03d", "temp": 6,
     "words": {"key": "rain", "form": "from", "at_ms": 1793710800000}, "card": "2026-11-03", "current": 1},
    {"at_ms": 1793710800000, "symbol": "09", "temp": 6,
     "words": {"key": "rain", "form": "now"}, "card": "2026-11-03", "current": 1},
    {"at_ms": 1793714400000, "symbol": "09", "temp": 5,
     "words": {"key": "rain", "form": "now"}, "card": "2026-11-03", "current": 1},
    {"at_ms": 1793718000000, "symbol": "46", "temp": 5,
     "words": null, "card": "2026-11-03", "current": 1}
  ],
  "days": [
    {"date": "2026-11-03",
     "periods": [
       {"part": "morning", "symbol": "03d", "temp": 3, "precip_mm": 0.0},
       {"part": "afternoon", "symbol": "09", "temp": 6, "precip_mm": 2.1},
       {"part": "evening", "symbol": "46", "temp": 4, "precip_mm": 0.4}],
     "low": 2, "high": 7,
     "dark": {"kind": "at", "at_ms": 1793721840000}}
  ]
}
```

- `days` holds every date an entry's `card` names (one to three: an entry at 23:00 names tomorrow, so near midnight
  `days` is today and tomorrow). `dark.kind` is `at` (with `at_ms`), `never` or `all_day`. `place` is `null` when there
  is no name. `words` is `null` for no words; `at_ms` only with `form` `from`.
- `{"v":1,"status":"off"}`, `"no_position"`, `"unavailable"` carry nothing else.
- `/now`: `{"v":1,"status":"ok","line":{"key":"rain_in","minutes":25}}`; `{"v":1,"status":"ok","line":null}` (dry, no
  radar coverage, outside the Nordic area, MET failing); `{"v":1,"status":"none"}` (off, no position). Keys: `rain_in`, `snow_in`, `dry_in` (with `minutes`), `rain_all`, `snow_all`.
- A phone ignores a key, form, part, kind or symbol it doesn't know (that piece is left out, §5.2), so a newer server
  never breaks an older launcher; `v` changes only for a breaking shape.

### 3.6 The policy

`models::LauncherUi.lock_weather: bool` (always sent), from `device_policy.lock_weather` in `build_policy` beside
`lock_wake_on_notification`; `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest` gain it in the
same commit. Capability: the launcher reports `lock_weather_v1` (§6 reads it).

## 4. Symbols and credit

### 4.1 `scripts/yr-symbols.py`

Pattern `scripts/material-symbols.sh`: pinned upstream, checked, converted, every output named.

- **Source**: `nrkno/yr-weather-symbols` at commit `34039b509167ba1701f1729df5cc95488813dd5f` (HEAD on 2026-11-03,
  package 14.0.0): `symbols/darkmode/*.svg` (83 files), `src/index.ts` (`weatherSymbolKeys`, MET's legend names → ids,
  MET's own spellings kept: `lightssleetshowersandthunder_*`), `locales/en.json` and `locales/nb.json` (the names by
  number, "03" → "partly cloudy" / "delvis skyet"), `LICENSE` (CC BY 4.0, "Yr weather symbols © 2015 by Yr/NRK").
  The script downloads through `raw.githubusercontent.com` at the commit and checks one SHA-256 over a manifest of
  every file's SHA-256 (pinned in the script; a changed upstream fails loudly).
- **Conversion** (Python 3 standard library only, run with `-I`): `svg` 100x100 viewBox → `<vector
  android:viewportWidth="100" android:viewportHeight="100" android:width="24dp" android:height="24dp">`; `path`
  (`d`, `fill`, `fill-rule="evenodd"` → `android:fillType="evenOdd"`, `opacity`/`fill-opacity` → `fillAlpha`);
  `linearGradient` / `radialGradient` with `gradientUnits="userSpaceOnUse"` and their stops → `aapt:attr` gradients
  (`gradientTransform`, if present, applied to the coordinates); `clipPath` → `<clip-path>` at the start of a `<group>`;
  `g` with `transform` translate/scale/matrix → a group. **Dropped**, each reported per file on stdout: groups with
  `filter` (upstream's soft drop shadows, `feGaussianBlur`/`feBlend`/`feFlood`) and elements with `mix-blend-mode`
  (the multiply shading layers - normal blending would darken them wrongly). **Anything else** (`mask`, `circle`,
  text, `use`, an unknown attribute that changes the picture) fails the run. A sample check on `05d` (rain showers,
  day): 8 paths, 2 linear and 1 radial gradient, a clip path, one filter group and two multiply layers - the sun,
  cloud, gradients and drops convert; the shadow and the cloud's multiply shade drop.
- **Writes**: `launcher/app/src/main/res/drawable/weather_<id>.xml` (`weather_03d`; each starts with a comment: the
  upstream path and commit, "© Yr/NRK, CC BY 4.0, converted to a VectorDrawable; shadows and blend layers removed" -
  CC BY's "indicate if changes were made"); `launcher/app/src/main/java/com/kidslauncher/mdm/weather/WeatherSymbols.kt`
  (id → drawable and name resource); `launcher/app/src/main/res/values/weather_symbols.xml` and `values-nb/` (the
  names, `weather_symbol_03`, one per number); `server/src/weather_symbols.rs` (`symbol_code` → id);
  `server/testdata/weather_symbols.json` (`{"commit", "keys": {"clearsky_day": "01d", ...}}`) and its byte-identical
  copy `launcher/app/src/test/resources/weather_symbols.json`; `launcher/app/src/main/assets/licenses/CC-BY-4.0.txt`
  (upstream's LICENSE text). `--selftest` converts a built-in tiny SVG with each supported and each dropped feature
  and checks the output (run by `launcher.yml` when the script changes).

### 4.2 Accessibility names

The mark's and the card's TalkBack text uses `weather_symbol_<nn>` (the number without d/n/m): "Delvis skyet, 6
grader" / "Partly cloudy, 6 degrees". An unknown id has no name (the degrees alone).

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
  model, the strings' resource names.
- `weather/WeatherSymbols.kt` - generated table (§4.1).
- `weather/WeatherStore.kt` - main-thread memory plus one CE `SharedPreferences` file `weather` (`answer_json`,
  `pulled_at_ms`, `tried_at_ms`); listeners (`addListener`/`removeListener`); `clear()`.
- `weather/WeatherPull.kt` - the two GETs through the existing device API client (`MdmApi`, `ResponseBody`, decoded by
  `ServerJson` into `server/dto/WeatherAnswer.kt` / `WeatherNow.kt`).
- `lock/WeatherLockPanel.kt` - the glue under `GlancePanel` (as `MusicLockPanel`, `ClockLockPanel`): binds the mark
  and the card, owns `open`.
- `res/layout/view_lock_weather_mark.xml`, `view_lock_weather_card.xml`; `res/drawable/ic_weather_chevron.xml`.

### 5.2 The model (`WeatherModel.kt`)

```kotlin
const val WEATHER_PULL_AFTER_MS = 55 * 60_000L      // D11
const val WEATHER_UPDATED_AFTER_MS = 60 * 60_000L   // "Oppdatert kl." in the card
const val WEATHER_HIDE_AFTER_MS = 3 * 3_600_000L    // the line hides
const val WEATHER_TRY_GAP_MS = 5 * 60_000L          // a screen-on retry after a failed pull
const val WEATHER_NOW_REUSE_MS = 2 * 60_000L        // the card's nowcast answer reused

data class WeatherLine(val symbol: String?, val temp: Int, val words: WeatherWords?, val card: WeatherDay?, val current: Int?)
data class WeatherWords(val res: String, val atMs: Long?)   // "weather_rain_from" + the hour

fun weatherLine(answer: WeatherAnswer?, policyOn: Boolean, nowMs: Long): WeatherLine?
fun weatherPullDue(policyOn: Boolean, pulledAtMs: Long?, triedAtMs: Long?, nowMs: Long): Boolean
fun weatherUpdatedAt(answer: WeatherAnswer, nowMs: Long): Long?        // checked_at when > 60 min old
fun weatherWordsRes(key: String, form: String): String?                // null for an unknown pair
fun weatherDegrees(temp: Int): String                                  // "4°", "−3°" (U+2212), never "−0°"
fun weatherHour(atMs: Long, zone: ZoneId, twentyFour: Boolean, locale: Locale): String
fun weatherMm(mm: Double, locale: Locale): String?                     // "2,1" / "2.1"; null under 0.1
fun nowcastRes(line: NowLine?): Pair<String, Int?>?
```

- `weatherLine`: `null` when the policy is off, the status isn't `ok`, `checked_at` is more than 3 h old, or no entry
  has `at_ms ≤ now < at_ms + 1 h` (a clock before the first entry counts as none). An unknown symbol id → `symbol =
  null` (degrees alone); unknown words → `words = null`; an unknown `card` date → no card (the mark still shows, the
  tap does nothing - it isn't focusable then).
- `weatherHour`: "kl. 14" style for the `from` words: 24-hour nb `"14"` (the string carries "kl."), 24-hour en
  `"14:00"`, 12-hour `DateFormat.getBestDateTimePattern(locale, "ha")` ("2 PM"), following the phone's one clock format
  (`ClockFormat`, design 34 P12). The instant is formatted in the phone's zone (the server computed the hour in the
  zone the phone reported; the two differ only right after a zone change, until the next pull).
- The card model `weatherCard(answer, line, nowMs)`: the day (`days[card]`), a "I morgen" label when `card` isn't the
  phone's today, the three columns (the part's label resource, symbol, degrees, mm; dimmed before `current`,
  highlighted at `current`), the foot (`place` · low / high · the dark text) and the updated line.

### 5.3 The line's place (D14-D15)

```kotlin
enum class WeatherPlace { BESIDE_WORDS, LINE_WORDS, BESIDE, LINE }

/** [lineDp] the room from the date's start to the page's end; [dateDp] the date's drawn width; [coreDp] symbol,
 * degrees and chevron with their gaps; [wordsDp] the dot and the words with their gaps (null = no words). */
fun weatherPlace(lineDp: Float, dateDp: Float, gapDp: Float, coreDp: Float, wordsDp: Float?): WeatherPlace
```

First that fits: `dateDp + gapDp + coreDp + wordsDp ≤ lineDp` → BESIDE_WORDS; `coreDp + wordsDp ≤ lineDp` → LINE_WORDS;
`dateDp + gapDp + coreDp ≤ lineDp` → BESIDE; else LINE. No hysteresis: the inputs only change with the text, the font
scale or the width. The panel measures with the views' own paints (`Paint.measureText` of the date's shown -
transformed - text, the degrees and the words) at bind, at the date's text change (midnight), at `refit()` (font
scale) and at a page-width change; it moves the one mark view (`lock_glance_weather`) between the date row and its
own slot only when the place changes. A rough budget at font 1.0 (Nunito Bold): "Man. 3. nov." 14 sp ≈ 92 dp, the core
(22 dp symbol, "4°", 14 dp chevron, gaps) ≈ 70 dp, "· Regn fra kl. 14" at 13 sp ≈ 125 dp - beside with words on a
360 dp phone (328 dp of room), on its own line with words on the 320 dp Jelly Star (the profile's date at 16 sp
starts at 35.3 dp: ≈ 269 dp of room), beside without words at font 1.3 on 360 dp, its own line without words at 2.0.

### 5.4 The store and the pull

- **When**: (1) at the end of every accepted `performMdmSync` - before the launcher's own self-update commit (nothing
  may run after it) - when `weatherPullDue` (policy on, the answer older than 55 min or none, no try in the last 5
  min); the SyncRunner's wake lock covers it, the 30-min backstop makes it about hourly; (2) at screen-on
  (`PinLockRuntime.addScreenOnListener`) on the same rule, on a background coroutine with a 10 s timeout; (3) at once
  when an accepted policy turns `lock_weather` on. No new alarm or wake-up.
- **What it keeps**: an `ok` answer replaces the kept one; `off` and `no_position` clear the store (the line hides at
  once); `unavailable`, a 429, a 5xx, no network keep the old answer (it ages out by D11) and stamp `tried_at_ms`. The
  policy turning the switch off clears the store.
- **The card's nowcast**: opening the card asks `/weather/now` unless an answer of the last 2 min is kept (memory
  only); the line appears at the card's top when it arrives (a 150 ms fade, none with "Remove animations"); a failure
  or `none` shows nothing.
- **Logs**: "Weather: pulled (ok, 4 hours, place yes)" / "Weather: pull failed (IOException)" - status, counts and
  exception classes; never the place, a temperature or a time of day. The answer's `toString()` is redacted.
- **No location code**: nothing under `weather/` or `WeatherLockPanel` touches `android.location`, and nothing asks
  any host but our server's API client (`WeatherGuardTest`).

### 5.5 The glance

- **Layout** (`view_lock_glance.xml`): the date `TextClock` goes into a horizontal row `lock_glance_date_row`
  (wrap_content, `baselineAligned`, `baselineAlignedChildIndex="0"`) beside the mark `lock_glance_weather` (an include
  of `view_lock_weather_mark.xml`: a 22 dp `ImageView`, the degrees, the dot and the words as one `TextView` with
  `maxLines=1` and **no** ellipsize, the 14 dp chevron in `kid_core_dim`); under the row the slot
  `lock_glance_weather_slot` (its own-line place, start-aligned with the date); then the alarm line and the "Neste"
  line as today; then the card `lock_weather_card` (include of `view_lock_weather_card.xml`), before
  `lock_rule_card`. The mark's text is the date's colour and size (`kid_core_dim`, the date's sp; the words 1 sp
  smaller); the degrees in `kid_ink`.
- **`LockClockFace`** gains `dateRow: View? = null`: when present, `placeProfile` measures, margins and baselines the
  row instead of the date (the row's baseline is the date's), and the slot is one of the `extra` lines that follow the
  date's start; `shortDate(on: Boolean)` switches the no-profile date's skeleton (D14). The time-rule screen passes no
  row and never calls it.
- **The card** (`bg_glance_compact_tile`, 12 dp padding, 14 dp under the face's last line, matching the rule card):
  the nowcast line (bold, gone unless), the "I morgen" label (gone unless), three equal columns (part label, 32 dp
  symbol, degrees in bold, the mm in the dim ink - gone under 0.1 mm), the current column with the compact tile's
  raised tone, the ones before it at 60 % alpha; the foot "Nydalen · 2° / 7°" at the start and the dark text at the
  end ("Mørkt kl. 17:04" in `kid_ink`), the place left out when `null`; "Oppdatert kl. 13:41" under the foot when due.
  The mockup's look, calm (launcher-look): no colour field, no animation beyond the fade and the chevron's 150 ms turn.
- **Open and fold** (`WeatherLockPanel.open`, memory only): a tap on the mark or on the card toggles; every lock-up
  (`lockStageDue`), a stage change off the glance, `stop()`, the line hiding and the VoIP ring card coming fold it. A
  card whose bottom lands below the scroller's fold after opening is brought into view (`requestRectangleOnScreen`,
  no animation). `GlancePanel.fitNext` measures with the open card taken out, as it takes its own line out, so opening
  the card never hides the "Neste" line.
- **Touch**: the mark and the card are tappable like a row and not in `swipeControls`: a swipe up from them still asks
  for the unlock (a recognised swipe cancels the tap, as on a row); a sideways drag does nothing (not dismissable).
- **Ticks**: the panel re-picks `weatherLine` on the clock's minute text change and binds only when the model
  changed (the 1 s music tick never touches it).
- **TalkBack**: the mark is a button, "Været: delvis skyet, 6 grader. Regn fra klokka 14." with expand / collapse
  actions and the state "Utvidet" / "Lukket"; the card one node: the nowcast line, each period ("Ettermiddag: regn, 6
  grader, 2,1 millimeter"), "Nydalen, laveste 2, høyeste 7 grader. Mørkt klokka 17:04." Not data-sensitive (weather
  isn't private; see Risk 3 for the place).
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
| `weather_mark_description` / `weather_card_description` / `weather_period_description` | Været: %1$s, %2$d grader. %3$s / … / %1$s: %2$s, %3$d grader, %4$s millimeter | Weather: %1$s, %2$d degrees. %3$s / … |
| `weather_expanded` / `weather_collapsed` | Utvidet / Lukket | Expanded / Collapsed |
| `legal_info_weather` | §4.3 | §4.3 |

The `weather_symbol_<nn>` names are generated (§4.1). `TranslationsTest` keeps the two catalogs' keys equal; the
contract test (§7.2) checks every key/form pair the server can send has both strings with the placeholder it needs.

## 6. The PWA switch

- **Where**: Device › Lock Screen & PINs (`/devices/{id}/lock`, `templates/device_lock.html`), the "Lock screen"
  group, right under "Wake the screen for a notification": a switch row `#lock-weather`, the wake switch's markup and
  handler pattern - `<form method="post" action="/devices/{id}/lock-weather#lock-weather" id="lock-weather">`, the
  checkbox `on` with `data-autosave`; `POST /devices/{id}/lock-weather` (`handlers::lock_glance::save_weather`, as
  `save_wake`): 404 unknown device, 500 with nothing written on a DB error, a `lock_weather_changed` security-log line
  only when the value changed (words through `security::Detail`, as `lock_wake_changed`), the phone nudged
  (`command_notify`), and the answer back on its own anchor (`Redirect::to("/devices/{id}/lock#lock-weather")`; with JS
  `scroll-restore.js` keeps the exact place). **A save never jumps to the top.**
- **Words** (`src/i18n/en.json` + `nb.json`, through `self.t`): `device.lock.weather` "Weather on the lock screen" /
  "Vær på låseskjermen"; `device.lock.weather_sub` "Yr's symbol and the temperature by the date; tap for the day. From
  MET Norway, for where the phone is (about 2 km)." / "Yr-symbol og temperatur ved datoen; trykk for dagen. Fra MET
  Norge, for der telefonen er (omtrent 2 km)."; `device.lock.weather_needs_location` (shown under the switch while it
  is on and Location isn't "Regularly", with a link to the Locate tab's Location Settings) "Needs Location set to
  Regularly - the lock shows no weather now." / "Krever at Posisjon står på Jevnlig - låseskjermen viser ikke vær nå.";
  `device.lock.weather_needs_update` (on, and the phone hasn't reported `lock_weather_v1`) "This phone's Vibb One
  doesn't show weather yet - it comes with its next update." / "Vibb One på denne telefonen viser ikke vær ennå - det
  kommer med neste oppdatering."; `security.event.lock_weather_changed` "Lock screen weather changed" / "Vær på
  låseskjermen endret". The `i18n::tests` keep both catalogs equal.
- No other PWA page changes. The parent never sees the cell or the forecast in the PWA.

## 7. Tests (lean, contract-pinned)

### 7.1 The contract files

- **`server/testdata/weather.json`**, byte-identical in `launcher/app/src/test/resources/`:
  - `forms`: `{"thunder": ["from","today","now","tomorrow"], ..., "very_warm": ["today","tomorrow"]}` and `now_keys`
    (`rain_in`, ...) - everything the server may send.
  - `words`: vectors `{name, zone, now, base, set, expect}` - `base` one hour's values applied to every hour of two
    local days, `set` overrides by local hour range (`{"from": "14:00", "to": "17:00", "sym": "rain", "mm": 0.8,
    "pop": 80}`), `expect` `{key, form, at?}` or `null`. Rows: each rule matching alone; the first-match order (thunder
    over heavy rain; snow over cold); the chance arm on a rain day and its absence on a snow day; `maybe_snow`; `today`
    at 06:30; `now` at 07:42 and at 14:05; rain that stopped by 16:30 → none; 17:59 → today, 18:00 → tomorrow; the
    23:30 and 00:10 entries; a DST day (2026-03-29, 2026-10-25 in Europe/Oslo); a `:30` zone (Asia/Kolkata); a missing
    gust; no hours → none.
  - `periods`: a day with the morning's hours from a merged older fetch, one without them (all `null`), the mm sums.
  - `dark`: `{lat, lon, date, zone, expect, sunset_met}` - Oslo 2026-11-03 (`at`, sunset_met 16:16), Oslo 2026-06-21
    (`at` after midnight), Trondheim 2026-06-21 (`never`), Tromsø 2026-12-21 (`at`, around 13:54), Longyearbyen
    2026-12-21 (`all_day`), Tromsø 2026-06-21 (`never`); `sunset_met` is MET Sunrise 3.0's sunset for the same day,
    fetched once by hand when the rows are written, and each row's own sunset must be within 2 min of it.
  - `cells`: `{lat, lon, cell, query}` - Oslo; a point on a 0.01° edge; a negative latitude and longitude; 179.99 →
    −180.00; 89.999.
  - `nowcast`: the four lines and the three silences (no coverage, dry, `temporarily unavailable`).
  - `answer` (§3.5) and `now_answers`, produced by `weather::tests::the_contract` from `server/testdata/weather/
    met_complete.json` and `met_nowcast.json` (real MET answers trimmed to a day, CC BY 4.0 MET Norway - said in
    `server/testdata/weather/README.md`) at a fixed `now` and zone (`WEATHER_CONTRACT_WRITE=1` rewrites them).
- **`server/testdata/weather_symbols.json`** (generated, §4.1), byte-identical in the launcher's test resources.
- Both go into the root `CLAUDE.md`'s contract list and the CI paths: `launcher.yml` (the server's two files,
  `scripts/yr-symbols.py`) and `server-ci.yml` (the launcher's two copies).

### 7.2 Server (`weather::tests`, `src/tests/weather.rs`)

- Pure: every `words`, `periods`, `dark`, `cells` and `nowcast` row; `parse_forecast` on the sample (and a body that
  isn't the shape → `unreadable`, a panic inside is caught); `merge` keeps 24 h back; `weather_symbols::id` maps every
  key of `weather_symbols.json`; both copies of both files equal the launcher's.
- Through the router with `CannedFetch`: the switch off → `off` and no outside request; `on_request` with a fresh fix
  → `no_position`, no request; a mock fix or one 7 h old → `no_position`; a good fix → exactly one MET request to
  `.../complete?lat=59.94&lon=10.76` with the User-Agent (and `MET_CONTACT` appended when set) and one Kartverket
  request; a second phone in the cell → no new request; past `Expires` → `If-Modified-Since` equals the stored
  `Last-Modified`, a 304 moves `checked_at`; a 203 is served; a 429 backs off and the old hours are served; a 5xx with
  no hours → `unavailable`; Kartverket outside the box → no request, `NULL`; `/now` on no coverage → `{"status": "ok", "line": null}`, with no position → `none`; the per-phone limits → 429; Location Off empties `weather_cells` and
  `weather_places`; retention drops a cell unasked for 48 h.
- PWA: `POST /devices/{id}/lock-weather` saves, redirects to `#lock-weather`, logs once per change, nudges; an
  unauthenticated POST writes nothing; the page shows the two notes in their cases.
- `policy_json_keys_snapshot` with `launcher_ui.lock_weather`.
- **Mutations for the code QA** (~8): the window's start not moved to the current hour; `>=` → `>` at 18:00; rule 5's
  chance arm without the kind; the cell's longitude step 0.02; `next_fetch_at` ignoring `Expires`; `If-Modified-Since`
  sent from `updated_at`; the Off erase skipping `weather_places`; the off switch still fetching.

### 7.3 Launcher

- `WeatherModelTest`: the entry pick at `at_ms` − 1 / + 0 / + 1 h; 3 h + 1 ms hides; 60 min + 1 ms adds "updated";
  the policy off hides; unknown key, symbol and card date; `weatherPullDue` (55 min, the 5-min try gap);
  `weatherDegrees` (−0.0 → "0°", U+2212); `weatherHour` nb/en 24 h and 12 h; `weatherMm` nb/en and under 0.1;
  `weatherPlace` - each step, at the §5.3 budgets for 320 / 360 / 411 dp and fonts 1.0 / 1.3 / 2.0.
- `WeatherContractTest`: both files equal the server's copies; `answer` and `now_answers` decode and give the expected
  line at the contract's `now`; every `forms` pair and `now_keys` key has a string in `values` and `values-nb` with
  the placeholder it needs; every id in `weather_symbols.json` has `weather_<id>.xml` and a name string.
- `PolicyResponseCompatTest`: `lock_weather` read, missing = false.
- `LockGlanceLayoutTest`: the date row's parts from the XML; the own-line place adds its line; the open card's
  height in the model scrolls, never clips, at 473 / 497 dp; the profile clock's date baseline unchanged with the row.
- `WeatherGuardTest` (§5.4): no `android.location` import and no URL literal under `weather/` and in
  `WeatherLockPanel`; the mark and the card are not in `swipeControls`.
- Emulator (runbook in `docs/testing/emulator.md`, a new §: a canned server answer from `TestApp`'s contract
  `answer`): the line at 320/360 dp and fonts 1.0/1.3/2.0; the card open/fold, folded after power off/on; TalkBack;
  the clock twin unchanged on `jelly36` with the weather off.

## 8. Steps

**Step 1 - server, the contracts and the symbols** (one commit for the API, as `api-both-dirs` asks): migration
0122; `weather.rs`, `weather_cache.rs`, `weather_symbols.rs`, the two routes, `Kind::Weather` and
`SourceResponse.expires`, `AppState.weather_fetch`/`weather`, `ForkConfig.met_contact` (DEPLOY.md: the env var), the
Off erase and retention; the PWA switch and its words; `scripts/yr-symbols.py` run once (it writes the launcher's
drawables, table and names too - unused until step 2, compiled and checked by the contract test); on the launcher
side only the DTOs (`LauncherUi.lockWeather`, `WeatherAnswer`, `WeatherNow`), `PolicyResponseCompatTest`, the two
copies and `WeatherContractTest`'s decode and file checks; NOTICE.md; CLAUDE.md's contract list and both workflows'
paths. A server alone with step 1 is harmless: no launcher asks.

**Step 2 - the launcher**: `WeatherModel`, `WeatherStore`, `WeatherPull` and the three triggers, the glance's row,
slot, mark and card, `LockClockFace`'s `dateRow` and short date, `WeatherLockPanel`, the strings, the licences
paragraph, the capability `lock_weather_v1`, the layout and guard tests, the emulator runbook. The notes:
`docs/notes/launcher/lock-and-glance.md` ("Weather on the glance (design 75)") and `docs/notes/server/launcher-ui.md`
("Lock screen weather") get their sections; the cores stay as they are.

Why two: step 1 is all of the outside traffic and the contract, testable without a phone; step 2 is the glance's
layout, where the risk is the face's placement. Sizes: step 1 ~700 lines of Rust (~250 of them pure rules and the
solar function) + ~250 of Python + the generated files; step 2 ~600 lines of Kotlin and XML.

## 9. Risks

1. **The location dependency.** Weather needs Location "Regularly"; a family on the default "On request" sees
   nothing. The PWA says so under the switch (§6). Open question 1.
2. **The face's placement.** The date moves into a row on a page whose clock and date are placed to Android's pixels
   (design 59d step L). The row reports the date's baseline, `LockGlanceLayoutTest` pins it, and the emulator's clock
   twin is checked with the weather off and on.
3. **The place name on a locked screen.** Anyone holding the phone reads the nearest settlement's name (as in the
   approved mockup). It is coarse (the cell's centre, a settlement within 2-5 km) and the switch is the parent's,
   default off; the PWA sub-line says "for where the phone is".
4. **MET's terms and versions.** A 403 (User-Agent) or a 203 (deprecation) is logged loudly and the weather hides by
   age, nothing breaks; a new Locationforecast version is a server release. MET Nowcast's coverage values could change
   wording: anything but `ok` is silence.
5. **The symbols' fidelity.** Dropping shadows and multiply shading flattens the clouds a little; checked by eye on the
   emulator against upstream's PNG previews before step 2 merges. A future upstream feature the converter doesn't know
   fails the script rather than drawing wrong.
6. **A stale or wrong answer.** A phone that can't reach the server shows the last answer up to 3 h (with "Oppdatert
   kl." after 1 h), then nothing; the entry schedule keeps the words honest within those hours.
7. **Battery and load.** No new wake-up: the pull rides the sync and the screen-on; one small GET an hour, gzip. The
   server's outside traffic is about one MET request per occupied cell per hour plus nowcasts on card opens (bounded
   by `Expires`, 3 min) - far under MET's 20 requests/s.

## 10. Open questions for the user

1. **"On request" location.** With Location on "On request" (the default) the phone takes no position on its own, so
   the lock shows no weather (my default; the PWA explains it). The alternative: while the weather switch is on, the
   phone takes a rough position itself about hourly (network-based, no GPS) and sends only the 2 km cell with its
   weather request - the server uses it and stores nothing. Android then shows its location indicator briefly each
   hour. Keep "Regularly only", or allow that?
2. **The narrow phone.** On the Jelly Star (320 dp) "Man. 3. nov. ☁ 4° · Regn fra kl. 14 ⌄" rarely fits on one line.
   My default: the weather mark moves to its own line just under the date, words kept. The alternative: it stays beside
   the date and drops the words there (they are still in the card). Which do you prefer?
