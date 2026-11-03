//! Weather on the lock glance (design 75 §3.2): everything the phone shows, computed here and pure - the grid
//! cell, MET's hours as the cache keeps them, the words (D3-D4), the card's periods, the dusk (D10), the hour
//! schedule (D2), the nowcast line (D9) and the answer's JSON (§3.5). No I/O: `weather_cache` fetches and stores,
//! `handlers::weather` answers. Every function is pinned by `testdata/weather.json`, shared with the launcher.

use chrono::{DateTime, Duration, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Timelike, Utc};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};

// ---- the grid cell (D6) -------------------------------------------------------------------------

/// A grid cell of 0.02° latitude by 0.04° longitude (about 2 x 2 km in Norway), kept as two integers: what MET,
/// Kartverket and the cache see - never the phone's own position.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Cell {
    /// `round(lat / 0.02)`, the latitude clamped to ±90.
    pub lat_i: i32,
    /// `round(lon / 0.04)`, the longitude wrapped into [−180, 180).
    pub lon_i: i32,
}

impl Cell {
    /// The cell of a position; `None` for a value that isn't a finite number.
    pub fn of(lat: f64, lon: f64) -> Option<Cell> {
        if !lat.is_finite() || !lon.is_finite() {
            return None;
        }
        let lat_i = (lat.clamp(-90.0, 90.0) * 50.0).round() as i32;
        // 180° is 4500 steps of 0.04°: wrapped into [-4500, 4500).
        let lon_i = ((lon * 25.0).round() as i64 + 4500).rem_euclid(9000) - 4500;
        Some(Cell {
            lat_i,
            lon_i: lon_i as i32,
        })
    }

    /// The latitude and longitude with two decimals, written from the integers (never "59.940000001" or "-0.00").
    pub fn coordinates(&self) -> (String, String) {
        (hundredths(self.lat_i * 2), hundredths(self.lon_i * 4))
    }

    /// MET's query: `lat=59.94&lon=10.76`.
    pub fn query(&self) -> String {
        let (lat, lon) = self.coordinates();
        format!("lat={lat}&lon={lon}")
    }

    /// The cell's centre, for the dusk and Kartverket.
    pub fn centre(&self) -> (f64, f64) {
        (f64::from(self.lat_i) * 0.02, f64::from(self.lon_i) * 0.04)
    }
}

fn hundredths(value: i32) -> String {
    let sign = if value < 0 { "-" } else { "" };
    let abs = value.unsigned_abs();
    format!("{sign}{}.{:02}", abs / 100, abs % 100)
}

// ---- MET's hours as the cache keeps them ---------------------------------------------------------

/// One forecast hour (`weather_cells.hours_json`): `t` the UTC hour's start (epoch seconds), `sym`
/// `next_1_hours`'s symbol code, `sym6` `next_6_hours`'s, the instant's temperature, feels-like and gust, and
/// `next_1_hours`' precipitation and its chance. Every value may be missing.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Hour {
    pub t: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sym: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sym6: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub temp: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub feels: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mm: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pop: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gust: Option<f32>,
}

impl Hour {
    fn start(&self) -> DateTime<Utc> {
        DateTime::from_timestamp(self.t, 0).unwrap_or_default()
    }
}

/// A body that isn't Locationforecast's shape (or made the reader panic).
#[derive(Debug, PartialEq, Eq)]
pub struct Unreadable;

/// A symbol code as MET writes them: at most 64 of `[a-z_]`, else none.
fn code(value: &serde_json::Value) -> Option<String> {
    let text = value.as_str()?;
    (!text.is_empty()
        && text.len() <= 64
        && text.bytes().all(|b| b.is_ascii_lowercase() || b == b'_'))
    .then(|| text.to_string())
}

fn number(value: &serde_json::Value) -> Option<f32> {
    value
        .as_f64()
        .filter(|v| v.is_finite() && v.abs() < 10_000.0)
        .map(|v| v as f32)
}

/// Locationforecast 2.0's `complete` body to its hourly part (the entries with `next_1_hours`), sorted by time,
/// one per hour. A body that isn't the shape, or has no hourly entry, is [Unreadable]; a panic inside is caught.
pub fn parse_forecast(body: &[u8]) -> Result<Vec<Hour>, Unreadable> {
    std::panic::catch_unwind(|| read_forecast(body)).unwrap_or_else(|_| {
        tracing::error!("a forecast body made the reader panic");
        Err(Unreadable)
    })
}

fn read_forecast(body: &[u8]) -> Result<Vec<Hour>, Unreadable> {
    let value: serde_json::Value = serde_json::from_slice(body).map_err(|_| Unreadable)?;
    let series = value["properties"]["timeseries"]
        .as_array()
        .ok_or(Unreadable)?;
    let mut hours: Vec<Hour> = series
        .iter()
        .filter(|entry| entry["data"]["next_1_hours"].is_object())
        .filter_map(|entry| {
            let t = DateTime::parse_from_rfc3339(entry["time"].as_str()?)
                .ok()?
                .timestamp();
            let data = &entry["data"];
            let instant = &data["instant"]["details"];
            let next = &data["next_1_hours"];
            Some(Hour {
                t,
                sym: code(&next["summary"]["symbol_code"]),
                sym6: code(&data["next_6_hours"]["summary"]["symbol_code"]),
                temp: number(&instant["air_temperature"]),
                feels: number(&instant["apparent_air_temperature"]),
                mm: number(&next["details"]["precipitation_amount"]),
                pop: number(&next["details"]["probability_of_precipitation"]),
                gust: number(&instant["wind_speed_of_gust"]),
            })
        })
        .collect();
    hours.sort_by_key(|h| h.t);
    hours.dedup_by_key(|h| h.t);
    if hours.is_empty() {
        return Err(Unreadable);
    }
    Ok(hours)
}

/// The stored hours after a new fetch: the new ones win; the old ones before the new series' first hour are kept
/// back to 24 h before `now` (this morning's period still has its hours at 15:00).
pub fn merge(old: &[Hour], new: &[Hour], now: DateTime<Utc>) -> Vec<Hour> {
    let first = new.first().map_or(i64::MAX, |h| h.t);
    let floor = (now - Duration::hours(24)).timestamp();
    let mut merged: Vec<Hour> = old
        .iter()
        .filter(|h| h.t < first && h.t >= floor)
        .cloned()
        .chain(new.iter().cloned())
        .collect();
    merged.sort_by_key(|h| h.t);
    merged.dedup_by_key(|h| h.t);
    merged
}

// ---- the words (D3-D4) --------------------------------------------------------------------------

pub const SNOW_MM: f64 = 1.0;
pub const SLEET_MM: f64 = 0.5;
pub const HEAVY_RAIN_MM: f64 = 5.0;
pub const RAIN_MM: f64 = 0.5;
pub const RAIN_CHANCE: f32 = 60.0;
pub const MAYBE_MM: f64 = 0.1;
pub const MAYBE_CHANCE: f32 = 30.0;
pub const WET_HOUR_MM: f32 = 0.1;
pub const VERY_COLD: f32 = -10.0;
pub const WINDY_GUST: f32 = 15.0;
pub const VERY_WARM: f32 = 25.0;
/// The words speak of 07:00-18:00 local.
pub const WINDOW: std::ops::Range<u32> = 7..18;
/// From this local hour the words are tomorrow's.
pub const TOMORROW_FROM: u32 = 18;

/// What falls in an hour, read from its symbol code's name (MET's spellings; `lightssleet...` included).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Rain,
    Sleet,
    Snow,
}

impl Kind {
    /// The kind of `hour`: from its code, else snow below 0 °C and rain otherwise.
    pub fn of(hour: &Hour) -> Kind {
        match hour.sym.as_deref().and_then(Kind::named) {
            Some(kind) => kind,
            None if hour.temp.is_some_and(|t| t < 0.0) => Kind::Snow,
            None => Kind::Rain,
        }
    }

    /// The kind a code names, if any.
    pub fn named(code: &str) -> Option<Kind> {
        if code.contains("sleet") {
            Some(Kind::Sleet)
        } else if code.contains("snow") {
            Some(Kind::Snow)
        } else if code.contains("rain") {
            Some(Kind::Rain)
        } else {
            None
        }
    }
}

/// A thunder hour, by its code alone.
pub fn is_thunder(code: &str) -> bool {
    code.contains("thunder")
}

/// A rule's form: `from` an hour, `today`, `now` or `tomorrow`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Form {
    From,
    Today,
    Now,
    Tomorrow,
}

/// The words of a day: a rule key, its form and, with `from`, the hour.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Words {
    pub key: &'static str,
    pub form: Form,
    pub at: Option<DateTime<Utc>>,
}

/// Every key and form the server may send (`weather.json`'s `forms`).
#[cfg(test)]
pub const FORMS: [(&str, &[Form]); 10] = [
    ("thunder", &PRECIPITATION_FORMS),
    ("snow", &PRECIPITATION_FORMS),
    ("sleet", &PRECIPITATION_FORMS),
    ("heavy_rain", &PRECIPITATION_FORMS),
    ("rain", &PRECIPITATION_FORMS),
    ("maybe_rain", &DAY_FORMS),
    ("maybe_snow", &DAY_FORMS),
    ("very_cold", &DAY_FORMS),
    ("windy", &DAY_FORMS),
    ("very_warm", &DAY_FORMS),
];
#[cfg(test)]
const PRECIPITATION_FORMS: [Form; 4] = [Form::From, Form::Today, Form::Now, Form::Tomorrow];
#[cfg(test)]
const DAY_FORMS: [Form; 2] = [Form::Today, Form::Tomorrow];

/// The nowcast line's keys (`weather.json`'s `now_keys`).
#[cfg(test)]
pub const NOW_KEYS: [&str; 5] = ["rain_in", "snow_in", "dry_in", "rain_all", "snow_all"];

fn local(tz: Tz, t: DateTime<Utc>) -> NaiveDateTime {
    t.with_timezone(&tz).naive_local()
}

fn at_hour(date: NaiveDate, hour: u32) -> NaiveDateTime {
    date.and_time(NaiveTime::from_hms_opt(hour, 0, 0).unwrap_or_default())
}

/// The start of the UTC hour containing `t`.
fn hour_floor(t: DateTime<Utc>) -> DateTime<Utc> {
    DateTime::from_timestamp(t.timestamp().div_euclid(3600) * 3600, 0).unwrap_or(t)
}

/// The day the words speak of at `at`, and whether it is tomorrow: today before 18:00, else tomorrow.
fn word_day(tz: Tz, at: DateTime<Utc>) -> (NaiveDate, bool) {
    let t = local(tz, at);
    if t.hour() < TOMORROW_FROM {
        (t.date(), false)
    } else {
        (t.date() + Duration::days(1), true)
    }
}

/// The words at `at` in the phone's zone (D3-D4): the first rule that matches over the window's hours, or none.
pub fn words(hours: &[Hour], tz: Tz, at: DateTime<Utc>) -> Option<Words> {
    let t = local(tz, at);
    let (day, tomorrow) = word_day(tz, at);
    let end = at_hour(day, WINDOW.end);
    let start = if tomorrow {
        at_hour(day, WINDOW.start)
    } else {
        at_hour(day, WINDOW.start).max(local(tz, hour_floor(at)))
    };
    let window: Vec<&Hour> = hours
        .iter()
        .filter(|h| {
            let s = local(tz, h.start());
            s >= start && s < end
        })
        .collect();
    let first_hour = window.first()?.t;
    let after_seven = t.time() >= NaiveTime::from_hms_opt(WINDOW.start, 0, 0).unwrap_or_default();
    let form_of = |first_wet: Option<&&Hour>| -> (Form, Option<DateTime<Utc>>) {
        match first_wet {
            _ if tomorrow => (Form::Tomorrow, None),
            Some(h) if h.t == first_hour && after_seven => (Form::Now, None),
            Some(h) if h.t == first_hour => (Form::Today, None),
            Some(h) => (Form::From, Some(h.start())),
            None => (Form::Today, None),
        }
    };
    let day_form = if tomorrow {
        Form::Tomorrow
    } else {
        Form::Today
    };
    let precipitation = |key: &'static str, first_wet: Option<&&Hour>| {
        let (form, at) = form_of(first_wet);
        Some(Words { key, form, at })
    };
    let simple = |key: &'static str| {
        Some(Words {
            key,
            form: day_form,
            at: None,
        })
    };

    let mm_of = |kind: Kind| -> f64 {
        window
            .iter()
            .filter(|h| Kind::of(h) == kind)
            .map(|h| f64::from(h.mm.unwrap_or(0.0)))
            .sum()
    };
    let (rain, sleet, snow) = (mm_of(Kind::Rain), mm_of(Kind::Sleet), mm_of(Kind::Snow));
    let most = rain.max(sleet).max(snow);
    let day_kind = if most <= 0.0 || rain == most {
        Kind::Rain
    } else if snow == most {
        Kind::Snow
    } else {
        Kind::Sleet
    };
    let chance = window
        .iter()
        .map(|h| h.pop.unwrap_or(0.0))
        .fold(0.0_f32, f32::max);
    let first_wet = |kind: Kind| {
        window
            .iter()
            .find(|h| Kind::of(h) == kind && h.mm.unwrap_or(0.0) >= WET_HOUR_MM)
    };

    if let Some(first) = window
        .iter()
        .find(|h| h.sym.as_deref().is_some_and(is_thunder))
    {
        return precipitation("thunder", Some(first));
    }
    if snow >= SNOW_MM {
        return precipitation("snow", first_wet(Kind::Snow));
    }
    if sleet >= SLEET_MM {
        return precipitation("sleet", first_wet(Kind::Sleet));
    }
    if rain >= HEAVY_RAIN_MM {
        return precipitation("heavy_rain", first_wet(Kind::Rain));
    }
    if rain >= RAIN_MM {
        return precipitation("rain", first_wet(Kind::Rain));
    }
    if chance >= RAIN_CHANCE && day_kind == Kind::Rain {
        let first = window.iter().find(|h| h.pop.unwrap_or(0.0) >= RAIN_CHANCE);
        return precipitation("rain", first);
    }
    if rain + sleet + snow >= MAYBE_MM || chance >= MAYBE_CHANCE {
        return simple(if day_kind == Kind::Snow {
            "maybe_snow"
        } else {
            "maybe_rain"
        });
    }
    let feels = window
        .iter()
        .filter_map(|h| h.feels.or(h.temp))
        .reduce(f32::min);
    if feels.is_some_and(|f| f <= VERY_COLD) {
        return simple("very_cold");
    }
    let gust = window
        .iter()
        .map(|h| h.gust.unwrap_or(0.0))
        .fold(0.0_f32, f32::max);
    if gust >= WINDY_GUST {
        return simple("windy");
    }
    let high = window.iter().filter_map(|h| h.temp).reduce(f32::max);
    if high.is_some_and(|h| h >= VERY_WARM) {
        return simple("very_warm");
    }
    None
}

// ---- the card: periods, the day, the dusk -------------------------------------------------------

/// Degrees for the phone: rounded half away from zero, never −0.
pub fn degrees(value: f32) -> i32 {
    let rounded = f64::from(value).round() as i32;
    if rounded == 0 { 0 } else { rounded }
}

/// One of the card's three columns.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Period {
    pub part: &'static str,
    pub symbol: Option<String>,
    pub temp: Option<i32>,
    pub precip_mm: Option<f64>,
}

/// The hour whose span contains the local wall time `at` (a missing or ambiguous local time: none).
fn hour_containing(hours: &[Hour], tz: Tz, at: NaiveDateTime) -> Option<&Hour> {
    let instant = tz.from_local_datetime(&at).earliest()?.with_timezone(&Utc);
    let t = instant.timestamp();
    hours.iter().find(|h| h.t <= t && t < h.t + 3600)
}

/// Morning 06-12, afternoon 12-18 and evening 18-24 of `date`: the symbol of the hour containing the start
/// (`next_6_hours`), the temperature of the hour containing the middle (09, 15, 21), the hours' precipitation summed
/// and rounded to 0.1. Without the start's symbol or the middle's temperature, the period's first hour we have gives
/// them (its `next_6_hours`, else its `next_1_hours`). A period with none of its hours has all three null.
pub fn periods(hours: &[Hour], tz: Tz, date: NaiveDate) -> [Period; 3] {
    let period = |part: &'static str, from: u32, to: u32| {
        let start = at_hour(date, from);
        let end = if to == 24 {
            at_hour(date + Duration::days(1), 0)
        } else {
            at_hour(date, to)
        };
        let inside: Vec<&Hour> = hours
            .iter()
            .filter(|h| {
                let s = local(tz, h.start());
                s >= start && s < end
            })
            .collect();
        if inside.is_empty() {
            return Period {
                part,
                symbol: None,
                temp: None,
                precip_mm: None,
            };
        }
        let mm: f64 = inside.iter().map(|h| f64::from(h.mm.unwrap_or(0.0))).sum();
        Period {
            part,
            // A cell's first fetch starts at the current hour: the period now running has lost its start (and
            // maybe its middle) - its first hour we have stands in (code QA M1).
            symbol: hour_containing(hours, tz, start)
                .and_then(|h| h.sym6.clone())
                .or_else(|| inside[0].sym6.clone().or_else(|| inside[0].sym.clone())),
            temp: hour_containing(hours, tz, at_hour(date, (from + to) / 2))
                .and_then(|h| h.temp)
                .or(inside[0].temp)
                .map(degrees),
            precip_mm: Some((mm * 10.0).round() / 10.0),
        }
    };
    [
        period("morning", 6, 12),
        period("afternoon", 12, 18),
        period("evening", 18, 24),
    ]
}

/// When it gets dark (D10): the end of civil twilight after the date's solar noon, or never, or not at all that day.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dark {
    At(DateTime<Utc>),
    /// The sun stays above −6° (white nights, midnight sun): "Lyst hele natta".
    Never,
    /// It never rises above −6° (the depth of polar night): "Mørkt hele dagen".
    AllDay,
}

/// The sun's elevation in degrees at `t` (NOAA's solar position algorithm, no refraction).
pub fn solar_elevation(lat: f64, lon: f64, t: DateTime<Utc>) -> f64 {
    let jd = t.timestamp() as f64 / 86_400.0 + 2_440_587.5;
    let c = (jd - 2_451_545.0) / 36_525.0;
    let mean_long = (280.46646 + c * (36_000.769_83 + c * 0.0003032)).rem_euclid(360.0);
    let anomaly = 357.52911 + c * (35_999.050_29 - 0.0001537 * c);
    let ecc = 0.016708634 - c * (0.000042037 + 0.0000001267 * c);
    let m = anomaly.to_radians();
    let centre = m.sin() * (1.914602 - c * (0.004817 + 0.000014 * c))
        + (2.0 * m).sin() * (0.019993 - 0.000101 * c)
        + (3.0 * m).sin() * 0.000289;
    let true_long = mean_long + centre;
    let omega = 125.04 - 1934.136 * c;
    let apparent = true_long - 0.00569 - 0.00478 * omega.to_radians().sin();
    let mean_obliquity =
        23.0 + (26.0 + (21.448 - c * (46.815 + c * (0.00059 - c * 0.001813))) / 60.0) / 60.0;
    let obliquity = mean_obliquity + 0.00256 * omega.to_radians().cos();
    let declination = (obliquity.to_radians().sin() * apparent.to_radians().sin()).asin();
    let y = (obliquity.to_radians() / 2.0).tan().powi(2);
    let l0 = mean_long.to_radians();
    let eq_time = 4.0
        * (y * (2.0 * l0).sin() - 2.0 * ecc * m.sin() + 4.0 * ecc * y * m.sin() * (2.0 * l0).cos()
            - 0.5 * y * y * (4.0 * l0).sin()
            - 1.25 * ecc * ecc * (2.0 * m).sin())
        .to_degrees();
    let minutes = f64::from(t.num_seconds_from_midnight()) / 60.0;
    let true_solar = (minutes + eq_time + 4.0 * lon).rem_euclid(1440.0);
    let hour_angle = (true_solar / 4.0 - 180.0).to_radians();
    let lat = lat.to_radians();
    let cos_zenith =
        lat.sin() * declination.sin() + lat.cos() * declination.cos() * hour_angle.cos();
    90.0 - cos_zenith.clamp(-1.0, 1.0).acos().to_degrees()
}

/// The date's solar noon: the highest elevation within the local date's UTC-noon ±12 h, to the minute.
fn solar_noon(lat: f64, lon: f64, date: NaiveDate) -> DateTime<Utc> {
    // The sun is highest near 12:00 UTC − 4 min per degree east; refined by a scan of ±90 min.
    let guess = Utc.from_utc_datetime(&at_hour(date, 12)) - Duration::seconds((lon * 240.0) as i64);
    (-90..=90)
        .map(|m| guess + Duration::minutes(m))
        .max_by(|a, b| solar_elevation(lat, lon, *a).total_cmp(&solar_elevation(lat, lon, *b)))
        .unwrap_or(guess)
}

/// The first downward crossing of `elevation` degrees after the date's solar noon, within 24 h: `At` (to the
/// nearest minute), `Never` (it stays above), `AllDay` (the sun is below it at noon). −6° is the dusk; −0.833° the
/// sunset (the vectors' check against MET Sunrise).
pub fn crossing(lat: f64, lon: f64, date: NaiveDate, elevation: f64) -> Dark {
    let noon = solar_noon(lat, lon, date);
    if solar_elevation(lat, lon, noon) < elevation {
        return Dark::AllDay;
    }
    let step = Duration::minutes(5);
    let mut before = noon;
    while before < noon + Duration::hours(24) {
        let after = before + step;
        if solar_elevation(lat, lon, after) < elevation {
            // Bisect the five minutes to a second.
            let (mut high, mut low) = (before, after);
            while low - high > Duration::seconds(1) {
                let mid = high + (low - high) / 2;
                if solar_elevation(lat, lon, mid) < elevation {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            let minute = (low.timestamp() + 30).div_euclid(60) * 60;
            return Dark::At(DateTime::from_timestamp(minute, 0).unwrap_or(low));
        }
        before = after;
    }
    Dark::Never
}

/// "Mørkt" for the date at the position: the end of civil twilight (D10).
pub fn dusk(lat: f64, lon: f64, date: NaiveDate) -> Dark {
    crossing(lat, lon, date, -6.0)
}

/// A card's day (§3.5's `days[]`).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Day {
    pub date: String,
    pub periods: [Period; 3],
    pub low: Option<i32>,
    pub high: Option<i32>,
    pub dark: DarkJson,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct DarkJson {
    pub kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_ms: Option<i64>,
}

impl From<Dark> for DarkJson {
    fn from(dark: Dark) -> Self {
        match dark {
            Dark::At(at) => DarkJson {
                kind: "at",
                at_ms: Some(at.timestamp_millis()),
            },
            Dark::Never => DarkJson {
                kind: "never",
                at_ms: None,
            },
            Dark::AllDay => DarkJson {
                kind: "all_day",
                at_ms: None,
            },
        }
    }
}

/// The card for `date`: its periods, the local date's low and high, when it gets dark at the cell's centre.
pub fn day(hours: &[Hour], tz: Tz, date: NaiveDate, cell: Cell) -> Day {
    let temps = hours
        .iter()
        .filter(|h| local(tz, h.start()).date() == date)
        .filter_map(|h| h.temp);
    let (low, high) = temps.fold((None::<f32>, None::<f32>), |(lo, hi), t| {
        (
            Some(lo.map_or(t, |lo| lo.min(t))),
            Some(hi.map_or(t, |hi| hi.max(t))),
        )
    });
    let (lat, lon) = cell.centre();
    Day {
        date: date.format("%Y-%m-%d").to_string(),
        periods: periods(hours, tz, date),
        low: low.map(degrees),
        high: high.map(degrees),
        dark: dusk(lat, lon, date).into(),
    }
}

// ---- the hour schedule (D2) ---------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct WordsJson {
    pub key: &'static str,
    pub form: Form,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_ms: Option<i64>,
}

/// One entry of the answer's `hours`: what the phone shows while its clock is in `[at, at + 1 h)`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Entry {
    pub at_ms: i64,
    pub symbol: Option<String>,
    pub temp: Option<i32>,
    pub words: Option<WordsJson>,
    /// The local date the card shows at that hour.
    pub card: String,
    /// The period of today's card containing it (0-2), null before 06:00 or on tomorrow's card.
    pub current: Option<u8>,
}

/// Four entries from the UTC hour containing `now` (the first one judged at `now`, the rest at their start); an
/// hour the forecast doesn't have is left out.
pub fn entries(hours: &[Hour], tz: Tz, now: DateTime<Utc>) -> Vec<Entry> {
    let first = hour_floor(now);
    (0..4)
        .filter_map(|i| {
            let start = first + Duration::hours(i);
            let hour = hours.iter().find(|h| h.t == start.timestamp())?;
            let at = if i == 0 { now } else { start };
            let (card, tomorrow) = word_day(tz, at);
            let local_hour = local(tz, at).hour();
            let current = match local_hour {
                _ if tomorrow => None,
                6..=11 => Some(0),
                12..=17 => Some(1),
                18..=23 => Some(2),
                _ => None,
            };
            Some(Entry {
                at_ms: start.timestamp_millis(),
                symbol: hour.sym.clone(),
                temp: hour.temp.map(degrees),
                words: words(hours, tz, at).map(|w| WordsJson {
                    key: w.key,
                    form: w.form,
                    at_ms: w.at.map(|t| t.timestamp_millis()),
                }),
                card: card.format("%Y-%m-%d").to_string(),
                current,
            })
        })
        .collect()
}

// ---- the nowcast line (D9) ----------------------------------------------------------------------

/// One Nowcast step (5 minutes from `t`, epoch seconds).
#[derive(Clone, Debug, PartialEq)]
pub struct NowStep {
    pub t: i64,
    pub rate: Option<f32>,
    pub sym: Option<String>,
    pub temp: Option<f32>,
}

/// A Nowcast fetch's readable part: `meta.radar_coverage` and the steps.
#[derive(Clone, Debug, PartialEq)]
pub struct Nowcast {
    pub coverage: String,
    pub steps: Vec<NowStep>,
}

/// Nowcast 2.0's body; `None` when it isn't the shape.
pub fn parse_nowcast(body: &[u8]) -> Option<Nowcast> {
    std::panic::catch_unwind(|| {
        let value: serde_json::Value = serde_json::from_slice(body).ok()?;
        let coverage = value["properties"]["meta"]["radar_coverage"]
            .as_str()?
            .chars()
            .take(40)
            .collect();
        let mut steps: Vec<NowStep> = value["properties"]["timeseries"]
            .as_array()?
            .iter()
            .filter_map(|entry| {
                let t = DateTime::parse_from_rfc3339(entry["time"].as_str()?)
                    .ok()?
                    .timestamp();
                let data = &entry["data"];
                Some(NowStep {
                    t,
                    rate: number(&data["instant"]["details"]["precipitation_rate"]),
                    sym: code(&data["next_1_hours"]["summary"]["symbol_code"]),
                    temp: number(&data["instant"]["details"]["air_temperature"]),
                })
            })
            .collect();
        steps.sort_by_key(|s| s.t);
        Some(Nowcast { coverage, steps })
    })
    .unwrap_or(None)
}

/// The card's top line.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct NowLine {
    pub key: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub minutes: Option<i64>,
}

const STEP_SECONDS: i64 = 300;
const NOWCAST_MINUTES: i64 = 90;
const WET_RATE: f32 = 0.1;

/// The line at `now` (D9): the steps from `now` to `now + 90 min` (a step that ended before it dropped); coverage
/// not `ok`, no step or none wet → none. Wet now: the first dry step's minutes (`dry_in`) or `rain_all`/`snow_all`;
/// dry now: the first wet step's (`rain_in`/`snow_in`). Minutes from `now`, rounded to 5, at least 5.
pub fn nowcast_line(nowcast: &Nowcast, now: DateTime<Utc>) -> Option<NowLine> {
    if nowcast.coverage != "ok" {
        return None;
    }
    let now_s = now.timestamp();
    let steps: Vec<&NowStep> = nowcast
        .steps
        .iter()
        .filter(|s| s.t + STEP_SECONDS > now_s && s.t < now_s + NOWCAST_MINUTES * 60)
        .collect();
    let wet = |s: &NowStep| s.rate.unwrap_or(0.0) >= WET_RATE;
    if !steps.iter().any(|s| wet(s)) {
        return None;
    }
    let snow = nowcast.steps.first().is_some_and(|first| {
        match first.sym.as_deref().and_then(Kind::named) {
            Some(kind) => kind == Kind::Snow,
            None => first.temp.is_some_and(|t| t < 0.0),
        }
    });
    let minutes = |s: &NowStep| {
        let m = ((s.t - now_s).max(0) as f64 / 60.0 / 5.0).round() as i64 * 5;
        Some(m.max(5))
    };
    let line = |key: &'static str, minutes: Option<i64>| Some(NowLine { key, minutes });
    if wet(steps[0]) {
        match steps.iter().find(|s| !wet(s)) {
            Some(dry) => line("dry_in", minutes(dry)),
            None => line(if snow { "snow_all" } else { "rain_all" }, None),
        }
    } else {
        let first = steps.iter().find(|s| wet(s))?;
        line(if snow { "snow_in" } else { "rain_in" }, minutes(first))
    }
}

// ---- the answer (§3.5) --------------------------------------------------------------------------

#[derive(Serialize)]
struct Answer<'a> {
    v: u8,
    status: &'static str,
    checked_at_ms: i64,
    place: Option<&'a str>,
    hours: Vec<Entry>,
    days: Vec<Day>,
}

/// A bare `{"v":1,"status":...}` (`off`, `no_position`, `unavailable`, `/now`'s `none`).
pub fn status(word: &str) -> serde_json::Value {
    serde_json::json!({ "v": 1, "status": word })
}

/// `GET /api/devices/weather`'s `ok` answer, or `unavailable` when no stored hour covers `now`.
pub fn answer(
    hours: &[Hour],
    tz: Tz,
    now: DateTime<Utc>,
    checked_at: DateTime<Utc>,
    place: Option<&str>,
    cell: Cell,
) -> serde_json::Value {
    let entries = entries(hours, tz, now);
    if entries.is_empty() {
        return status("unavailable");
    }
    let mut dates: Vec<String> = entries.iter().map(|e| e.card.clone()).collect();
    dates.dedup();
    let days = dates
        .iter()
        .filter_map(|d| NaiveDate::parse_from_str(d, "%Y-%m-%d").ok())
        .map(|date| day(hours, tz, date, cell))
        .collect();
    serde_json::to_value(Answer {
        v: 1,
        status: "ok",
        checked_at_ms: checked_at.timestamp_millis(),
        place,
        hours: entries,
        days,
    })
    .unwrap_or_else(|_| status("unavailable"))
}

/// `GET /api/devices/weather/now`'s `ok` answer: the line or null.
pub fn now_answer(line: Option<NowLine>) -> serde_json::Value {
    serde_json::json!({ "v": 1, "status": "ok", "line": line })
}

#[cfg(test)]
mod tests;
