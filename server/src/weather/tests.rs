//! `testdata/weather.json`'s rows, run against the pure functions, and its computed parts (design 75 §7.1).

use super::*;
use serde_json::{Value, json};

const CONTRACT: &str = "testdata/weather.json";
const LAUNCHER_COPY: &str = "../launcher/app/src/test/resources/weather.json";
/// The contract's answer: 2026-11-03 at 15:20 in Oslo, 20 minutes after the recorded fetch.
const ANSWER_NOW: &str = "2026-11-03T14:20:00Z";
const CHECKED_AT: &str = "2026-11-03T14:00:22Z";
/// The nowcast rows' minutes count from here.
const NOWCAST_T0: &str = "2026-11-03T14:00:00Z";

fn contract() -> Value {
    serde_json::from_str(&std::fs::read_to_string(CONTRACT).unwrap()).unwrap()
}

fn utc(text: &str) -> DateTime<Utc> {
    DateTime::parse_from_rfc3339(text)
        .unwrap()
        .with_timezone(&Utc)
}

fn tz(row: &Value) -> Tz {
    row["zone"].as_str().unwrap().parse().unwrap()
}

fn local_instant(tz: Tz, text: &str) -> DateTime<Utc> {
    let naive = NaiveDateTime::parse_from_str(text, "%Y-%m-%dT%H:%M").unwrap();
    tz.from_local_datetime(&naive)
        .earliest()
        .unwrap()
        .with_timezone(&Utc)
}

fn time(text: &str) -> NaiveTime {
    NaiveTime::parse_from_str(text, "%H:%M").unwrap()
}

fn apply(hour: &mut Hour, values: &Value) {
    let num = |key: &str| values.get(key).and_then(Value::as_f64).map(|v| v as f32);
    let text = |key: &str| values.get(key).and_then(Value::as_str).map(str::to_string);
    if let Some(sym) = text("sym") {
        hour.sym = Some(sym);
    }
    if let Some(sym6) = text("sym6") {
        hour.sym6 = Some(sym6);
    }
    for (key, field) in [
        ("temp", &mut hour.temp),
        ("feels", &mut hour.feels),
        ("mm", &mut hour.mm),
        ("pop", &mut hour.pop),
        ("gust", &mut hour.gust),
    ] {
        if let Some(value) = num(key) {
            *field = Some(value);
        }
    }
}

/// Every UTC hour of the local `date` and the day after: `base` on each, then each `set` over its local
/// `[from, to)` on its `day` (0 = `date`, 1 = the next). A null base is no hours at all.
fn hours_of(tz: Tz, date: NaiveDate, base: &Value, set: &[Value]) -> Vec<Hour> {
    if base.is_null() {
        return Vec::new();
    }
    let midnight = |date: NaiveDate| {
        tz.from_local_datetime(&date.and_hms_opt(0, 0, 0).unwrap())
            .earliest()
            .unwrap()
            .with_timezone(&Utc)
    };
    let end = midnight(date + Duration::days(2));
    let mut hours = Vec::new();
    let mut t = midnight(date);
    while t < end {
        let mut hour = Hour {
            t: t.timestamp(),
            sym: None,
            sym6: None,
            temp: None,
            feels: None,
            mm: None,
            pop: None,
            gust: None,
        };
        apply(&mut hour, base);
        let at = local(tz, t);
        let day = (at.date() - date).num_days();
        for change in set {
            let on_day = change.get("day").and_then(Value::as_i64).unwrap_or(0);
            let from = time(change["from"].as_str().unwrap());
            let to = time(change["to"].as_str().unwrap());
            if on_day == day && at.time() >= from && at.time() < to {
                apply(&mut hour, change);
            }
        }
        hours.push(hour);
        t += Duration::hours(1);
    }
    hours
}

fn row_date(text: &str) -> NaiveDate {
    NaiveDate::parse_from_str(&text[..10], "%Y-%m-%d").unwrap()
}

#[test]
fn the_words_vectors() {
    for row in contract()["words"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let tz = tz(row);
        let now_text = row["now"].as_str().unwrap();
        let hours = hours_of(
            tz,
            row_date(now_text),
            &row["base"],
            row["set"].as_array().unwrap(),
        );
        let got = words(&hours, tz, local_instant(tz, now_text)).map(|w| {
            let mut out = json!({ "key": w.key, "form": w.form });
            if let Some(at) = w.at {
                out["at"] = local(tz, at).format("%H:%M").to_string().into();
            }
            out
        });
        assert_eq!(got.unwrap_or(Value::Null), row["expect"], "{name}");
    }
}

#[test]
fn the_periods_vectors() {
    for row in contract()["periods"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let tz = tz(row);
        let date = row_date(row["date"].as_str().unwrap());
        let now = local_instant(tz, row["now"].as_str().unwrap());
        let all = hours_of(tz, date, &row["base"], row["set"].as_array().unwrap());
        let split = |key: &str| {
            row.get(key).and_then(Value::as_str).map(|at| {
                local_instant(tz, &format!("{}T{at}", date.format("%Y-%m-%d"))).timestamp()
            })
        };
        let hours = if let Some(until) = split("old_until") {
            // The morning comes from an older fetch: the new one starts at `old_until`.
            let (old, new): (Vec<Hour>, Vec<Hour>) = all.into_iter().partition(|h| h.t < until);
            merge(&old, &new, now)
        } else if let Some(until) = split("drop_until") {
            all.into_iter().filter(|h| h.t >= until).collect()
        } else {
            all
        };
        assert_eq!(
            serde_json::to_value(periods(&hours, tz, date)).unwrap(),
            row["expect"],
            "{name}"
        );
    }
}

fn dark_json(dark: Dark, tz: Tz) -> Value {
    match dark {
        Dark::At(at) => {
            json!({ "kind": "at", "at": at.with_timezone(&tz).format("%Y-%m-%dT%H:%M%:z").to_string() })
        }
        Dark::Never => json!({ "kind": "never" }),
        Dark::AllDay => json!({ "kind": "all_day" }),
    }
}

fn minutes_apart(a: DateTime<Utc>, b: &str) -> i64 {
    let b = DateTime::parse_from_rfc3339(&format!("{}:00{}", &b[..16], &b[16..])).unwrap();
    (a - b.with_timezone(&Utc)).num_minutes().abs()
}

fn computed_dark(row: &Value) -> Dark {
    dusk(
        row["lat"].as_f64().unwrap(),
        row["lon"].as_f64().unwrap(),
        row_date(row["date"].as_str().unwrap()),
    )
}

/// `expect` is the code's (written by [the_contract]); each is checked against a second reference: the civil dusk of
/// USNO's tables and MET Sunrise 3.0's sunset (the same function at −0.833°), within 2 minutes (server QA L1).
#[test]
fn the_dark_vectors() {
    for row in contract()["dark"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let (lat, lon) = (row["lat"].as_f64().unwrap(), row["lon"].as_f64().unwrap());
        let date = row_date(row["date"].as_str().unwrap());
        let dark = computed_dark(row);
        assert_eq!(dark_json(dark, tz(row)), row["expect"], "{name}");
        match (dark, row["civil_ref"].as_str()) {
            (Dark::At(at), Some(reference)) => {
                assert!(
                    minutes_apart(at, reference) <= 2,
                    "{name}: {at} vs {reference}"
                )
            }
            (Dark::At(_), None) | (_, Some(_)) => {
                panic!("{name}: {dark:?} against {}", row["civil_ref"])
            }
            _ => {}
        }
        let sunset = crossing(lat, lon, date, -0.833);
        match (sunset, row["sunset_met"].as_str()) {
            (Dark::At(at), Some(met)) => {
                assert!(minutes_apart(at, met) <= 2, "{name}: sunset {at} vs {met}")
            }
            (Dark::At(at), None) => panic!("{name}: a sunset at {at} where MET has none"),
            (_, Some(met)) => panic!("{name}: no sunset where MET has {met}"),
            _ => {}
        }
    }
}

#[test]
fn the_cells_vectors() {
    for row in contract()["cells"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        let cell = Cell::of(row["lat"].as_f64().unwrap(), row["lon"].as_f64().unwrap()).unwrap();
        assert_eq!(json!([cell.lat_i, cell.lon_i]), row["cell"], "{name}");
        assert_eq!(cell.query(), row["query"].as_str().unwrap(), "{name}");
    }
    assert_eq!(Cell::of(f64::NAN, 10.0), None);
    assert_eq!(Cell::of(59.9, f64::INFINITY), None);
}

fn nowcast_of(row: &Value) -> Nowcast {
    let t0 = utc(NOWCAST_T0).timestamp();
    Nowcast {
        coverage: row["coverage"].as_str().unwrap().to_string(),
        steps: row["steps"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| NowStep {
                t: t0 + s["min"].as_i64().unwrap() * 60,
                rate: s["rate"].as_f64().map(|r| r as f32),
                sym: s.get("sym").and_then(Value::as_str).map(str::to_string),
                temp: s.get("temp").and_then(Value::as_f64).map(|t| t as f32),
            })
            .collect(),
    }
}

fn nowcast_row_line(row: &Value) -> Option<NowLine> {
    let now = utc(NOWCAST_T0) + Duration::minutes(row["now_min"].as_i64().unwrap());
    nowcast_line(&nowcast_of(row), now)
}

#[test]
fn the_nowcast_vectors() {
    for row in contract()["nowcast"].as_array().unwrap() {
        let name = row["name"].as_str().unwrap();
        assert_eq!(
            serde_json::to_value(nowcast_row_line(row)).unwrap(),
            row["expect"],
            "{name}"
        );
    }
}

fn recorded_hours() -> Vec<Hour> {
    parse_forecast(&std::fs::read("testdata/weather/met_complete.json").unwrap()).unwrap()
}

/// The contract's computed parts: everything the server may send (`forms`, `now_keys`), the dusk rows' `expect`, the
/// answer from the recorded MET answer at [ANSWER_NOW] in Oslo, the line a phone shows then
/// (`answer_line`), the nowcast answers and the bare statuses. `WEATHER_CONTRACT_WRITE=1` rewrites them; the
/// launcher's `WeatherContractTest` decodes them.
#[test]
fn the_contract() {
    let mut contract = contract();
    let oslo: Tz = "Europe/Oslo".parse().unwrap();
    let forms: serde_json::Map<String, Value> = FORMS
        .iter()
        .map(|(key, forms)| ((*key).to_string(), serde_json::to_value(forms).unwrap()))
        .collect();
    let darks: Vec<Value> = contract["dark"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| dark_json(computed_dark(row), tz(row)))
        .collect();
    let now = utc(ANSWER_NOW);
    let cell = Cell::of(59.94, 10.76).unwrap();
    let answer = answer(&recorded_hours(), oslo, now, utc(CHECKED_AT), cell);
    let line = answer["hours"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| {
            let at = e["at_ms"].as_i64().unwrap();
            at <= now.timestamp_millis() && now.timestamp_millis() < at + 3_600_000
        })
        .cloned()
        .unwrap();
    // The same answer with rain laid over 16:00-18:00 (the recorded day is dry): words a phone decodes - `from` with
    // its hour at 15:20, `now` from 16:00 (code QA M1 of the launcher review).
    let mut wet = recorded_hours();
    for hour in &mut wet {
        let at = local(oslo, hour.start());
        if at.date() == row_date("2026-11-03") && (16..18).contains(&at.hour()) {
            hour.sym = Some("rain".to_string());
            hour.mm = Some(0.8);
            hour.pop = Some(80.0);
        }
    }
    let answer_words = crate::weather::answer(&wet, oslo, now, utc(CHECKED_AT), cell);
    let forms_sent: Vec<&str> = answer_words["hours"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|e| e["words"]["form"].as_str())
        .collect();
    assert!(
        forms_sent.contains(&"from") && forms_sent.contains(&"now"),
        "{forms_sent:?}"
    );
    let recorded_now =
        parse_nowcast(&std::fs::read("testdata/weather/met_nowcast.json").unwrap()).unwrap();
    let rows = contract["nowcast"].as_array().unwrap().clone();
    let row_answer = |i: usize| now_answer(nowcast_row_line(&rows[i]));
    let now_answers = json!([
        { "name": "the recorded nowcast (dry)", "answer": now_answer(nowcast_line(&recorded_now, now)) },
        { "name": rows[0]["name"], "answer": row_answer(0) },
        { "name": rows[2]["name"], "answer": row_answer(2) },
        { "name": rows[3]["name"], "answer": row_answer(3) },
        { "name": "off or no position", "answer": status("none") },
    ]);
    let statuses = json!([status("off"), status("no_position"), status("unavailable")]);
    if std::env::var("WEATHER_CONTRACT_WRITE").is_ok() {
        contract["forms"] = Value::Object(forms.clone());
        contract["now_keys"] = json!(NOW_KEYS);
        for (row, dark) in contract["dark"]
            .as_array_mut()
            .unwrap()
            .iter_mut()
            .zip(&darks)
        {
            row["expect"] = dark.clone();
        }
        contract["answer"] = answer.clone();
        contract["answer_now_ms"] = now.timestamp_millis().into();
        contract["answer_line"] = line.clone();
        contract["answer_words"] = answer_words.clone();
        contract["now_answers"] = now_answers.clone();
        contract["statuses"] = statuses.clone();
        std::fs::write(
            CONTRACT,
            serde_json::to_string_pretty(&contract).unwrap() + "\n",
        )
        .unwrap();
    }
    let changed = "the contract changed - WEATHER_CONTRACT_WRITE=1, then copy it to the launcher";
    assert_eq!(contract["forms"], Value::Object(forms), "{changed}");
    assert_eq!(contract["now_keys"], json!(NOW_KEYS), "{changed}");
    for (row, dark) in contract["dark"].as_array().unwrap().iter().zip(&darks) {
        assert_eq!(&row["expect"], dark, "{changed}");
    }
    assert_eq!(contract["answer"], answer, "{changed}");
    assert_eq!(
        contract["answer_now_ms"],
        now.timestamp_millis(),
        "{changed}"
    );
    assert_eq!(contract["answer_line"], line, "{changed}");
    assert_eq!(contract["answer_words"], answer_words, "{changed}");
    assert_eq!(contract["now_answers"], now_answers, "{changed}");
    assert_eq!(contract["statuses"], statuses, "{changed}");
    // Every key and form the answer sends is one the phone knows.
    for entry in answer["hours"]
        .as_array()
        .unwrap()
        .iter()
        .chain(answer_words["hours"].as_array().unwrap())
    {
        if let Some(words) = entry["words"].as_object() {
            let forms = &contract["forms"][words["key"].as_str().unwrap()];
            assert!(forms.as_array().unwrap().contains(&words["form"]));
        }
    }
}

#[test]
fn the_contract_is_identical_to_the_launchers_copy() {
    assert_eq!(
        std::fs::read(CONTRACT).unwrap(),
        std::fs::read(LAUNCHER_COPY).unwrap(),
        "testdata/weather.json and the launcher's copy differ - update both in one commit"
    );
}

#[test]
fn the_recorded_forecast_reads() {
    let hours = recorded_hours();
    // The sample's 35 entries are all hourly (the trim ends before the 6-hourly part).
    assert_eq!(hours.len(), 35);
    assert_eq!(hours[0].t, utc("2026-11-03T13:00:00Z").timestamp());
    assert!(
        hours
            .iter()
            .all(|h| h.temp.is_some() && h.sym.is_some() && h.sym6.is_some())
    );
    // A dry November day: no code names precipitation, so every hour reads as rain above 0 °C.
    for hour in &hours {
        assert_eq!(Kind::of(hour), Kind::Rain, "{:?}", hour.sym);
    }
    assert_eq!(
        Kind::named("lightssleetshowersandthunder_day"),
        Some(Kind::Sleet)
    );
    assert_eq!(Kind::named("heavysnow"), Some(Kind::Snow));
    assert_eq!(Kind::named("rainshowers_night"), Some(Kind::Rain));
    assert!(is_thunder("lightrainandthunder"));
    assert_eq!(parse_forecast(b"{\"type\":\"Feature\"}"), Err(Unreadable));
    assert_eq!(parse_forecast(b"<html>"), Err(Unreadable));
    assert_eq!(
        parse_forecast(
            br#"{"properties":{"timeseries":[{"time":"2026-11-03T13:00:00Z","data":{}}]}}"#
        ),
        Err(Unreadable),
        "no hourly entry"
    );
    // A code that isn't MET's shape is dropped, the hour kept.
    let odd = parse_forecast(
        br#"{"properties":{"timeseries":[{"time":"2026-11-03T13:00:00Z","data":{"instant":{"details":{}},"next_1_hours":{"summary":{"symbol_code":"<b>rain</b>"}}}}]}}"#,
    )
    .unwrap();
    assert_eq!(odd[0].sym, None);
}

/// The emulator's cell (Mountain View, 37.42/-122.08), recorded 2026-11-04 (`testdata/weather/README.md`): outside
/// MEPS, so ECMWF's shape - no gust, no chance of rain, the last five hourly entries without `next_6_hours`, then the
/// 6-hourly tail. It reads whole.
#[test]
fn the_mountain_view_forecast_reads() {
    let hours =
        parse_forecast(&std::fs::read("testdata/weather/met_complete_mountain_view.json").unwrap())
            .unwrap();
    assert_eq!(hours.len(), 63);
    assert_eq!(hours[0].t, utc("2026-11-04T21:00:00Z").timestamp());
    assert_eq!(hours[0].sym.as_deref(), Some("clearsky_day"));
    assert_eq!(hours[0].temp, Some(18.3));
    assert_eq!(hours[62].t, utc("2026-11-07T11:00:00Z").timestamp());
    assert!(hours.iter().all(|h| h.gust.is_none() && h.pop.is_none()));
    assert!(hours.iter().all(|h| h.temp.is_some() && h.sym.is_some()));
    assert_eq!(hours.iter().filter(|h| h.sym6.is_some()).count(), 58);
}

/// E9 (2026-11-04): MET served a `complete` cut short. Every cut keeps exactly the whole hourly entries before it -
/// the first hours as the whole body reads them - and anything but a cut stays unreadable.
#[test]
fn a_cut_body_keeps_its_whole_hours() {
    let body = std::fs::read("testdata/weather/met_complete.json").unwrap();
    let whole = recorded_hours();
    assert_eq!(parse_cut_forecast(&body), Err(Unreadable), "a whole body");
    let mut kept = 0;
    for cut in (0..body.len()).step_by(997) {
        match parse_cut_forecast(&body[..cut]) {
            Ok(hours) => {
                assert!(hours.len() >= kept && hours.len() < whole.len(), "at {cut}");
                assert_eq!(hours[..], whole[..hours.len()], "at {cut}");
                kept = hours.len();
            }
            Err(Unreadable) => assert_eq!(kept, 0, "at {cut}: unreadable after a readable cut"),
        }
    }
    assert!(kept > 30, "{kept}");
    // E9's own cut: 38 038 of ~65 000 bytes, here the same share.
    let e9 = parse_cut_forecast(&body[..body.len() * 38 / 65]).unwrap();
    assert!((15..30).contains(&e9.len()), "{}", e9.len());
    // Wrong before its end, or not MET's head.
    let mut wrong = body[..body.len() / 2].to_vec();
    let at = wrong.windows(7).rposition(|w| w == b"\"data\":").unwrap() + 6;
    wrong[at] = b';';
    assert_eq!(parse_cut_forecast(&wrong), Err(Unreadable));
    assert_eq!(parse_cut_forecast(b"<html><body>503"), Err(Unreadable));
    assert_eq!(
        parse_cut_forecast(br#"{"type":"Feature","timeseries":[{"time":"2026-11-03T13:00:00Z""#),
        Err(Unreadable),
        "no properties"
    );
}

#[test]
fn merge_keeps_a_day_back() {
    let hour = |h: i64, temp: f32| Hour {
        t: utc("2026-11-03T00:00:00Z").timestamp() + h * 3600,
        sym: None,
        sym6: None,
        temp: Some(temp),
        feels: None,
        mm: None,
        pop: None,
        gust: None,
    };
    let old: Vec<Hour> = (0..30).map(|h| hour(h, 1.0)).collect();
    let new: Vec<Hour> = (26..40).map(|h| hour(h, 2.0)).collect();
    // 2026-11-04T03:00Z: a day back is 2026-11-03T03:00Z.
    let merged = merge(&old, &new, utc("2026-11-04T03:00:00Z"));
    assert_eq!(
        merged.first().unwrap().t,
        hour(3, 0.0).t,
        "older than 24 h goes"
    );
    assert_eq!(merged.len(), 37);
    assert!(
        merged
            .iter()
            .filter(|h| h.t >= hour(26, 0.0).t)
            .all(|h| h.temp == Some(2.0)),
        "the new hours win"
    );
    // After a cut body the old hours past the new series' last stay too.
    let cut: Vec<Hour> = (26..28).map(|h| hour(h, 2.0)).collect();
    let merged = merge_cut(&old, &cut, utc("2026-11-04T03:00:00Z"));
    assert_eq!(merged.len(), 27);
    assert_eq!(merged.last().unwrap().temp, Some(1.0));
    assert_eq!(merge(&old, &cut, utc("2026-11-04T03:00:00Z")).len(), 25);
}

#[test]
fn degrees_never_say_minus_zero() {
    assert_eq!(degrees(-0.4), 0);
    assert_eq!(degrees(-0.5), -1);
    assert_eq!(degrees(0.5), 1);
    assert_eq!(degrees(6.49), 6);
}

/// The evening card (2026-11-04): from 18:00 to midnight the card is tomorrow's and each entry carries what is left
/// of this evening; at midnight the card is the new day's, without it.
#[test]
fn tonight_runs_from_18_to_midnight() {
    let oslo: Tz = "Europe/Oslo".parse().unwrap();
    let base = json!({ "sym": "cloudy", "sym6": "cloudy", "temp": 4, "mm": 0 });
    let set = [
        json!({ "from": "18:00", "to": "19:00", "sym6": "rain", "temp": 8, "mm": 0.4 }),
        json!({ "from": "19:00", "to": "20:00", "sym": "clearsky_night", "sym6": "clearsky_night" }),
        json!({ "from": "21:00", "to": "22:00", "temp": 2 }),
        json!({ "from": "23:00", "to": "23:59", "sym": "fair_night", "sym6": "fair_night", "temp": -1 }),
    ];
    let hours = hours_of(oslo, row_date("2026-11-04"), &base, &set);
    let at = |text: &str| local_instant(oslo, text);
    let column = |symbol: &str, temp: i32, mm: f64| Period {
        part: "evening",
        symbol: Some(symbol.to_string()),
        temp: Some(temp),
        precip_mm: Some(mm),
    };

    // 17:59: today's card with the afternoon current, no evening yet; the next entry (18:00) has it all.
    let before = entries(&hours, oslo, at("2026-11-04T17:59"));
    assert_eq!(
        (before[0].card.as_str(), before[0].current),
        ("2026-11-04", Some(1))
    );
    assert_eq!(before[0].tonight, None);
    assert_eq!(before[1].card, "2026-11-05");
    assert_eq!(before[1].tonight, Some(column("rain", 2, 0.4)));

    // 19:04: tomorrow's card, and the evening from 19:00 - its symbol from 19:00, the degrees from 21:30.
    let evening = entries(&hours, oslo, at("2026-11-04T19:04"));
    assert_eq!(
        (evening[0].card.as_str(), evening[0].current),
        ("2026-11-05", None)
    );
    assert_eq!(evening[0].tonight, Some(column("clearsky_night", 2, 0.0)));

    // 23:30: the last hour still is this evening.
    assert_eq!(
        tonight(&hours, oslo, at("2026-11-04T23:30")),
        Some(column("fair_night", -1, 0.0))
    );

    // Midnight: the new day's card, no evening.
    let night = entries(&hours, oslo, at("2026-11-05T00:00"));
    assert_eq!(
        (night[0].card.as_str(), night[0].current, &night[0].tonight),
        ("2026-11-05", None, &None)
    );
    // No stored hour of the evening: none.
    assert_eq!(tonight(&[], oslo, at("2026-11-04T19:04")), None);
}

/// QA L1 (2026-11-04): from 19:00 the rest of the evening is under 6 h, so "I kveld"'s symbol comes from its own hours -
/// a thunder hour, else the wettest hour (the first of equals), else the first hour - and never from a `next_6_hours`
/// that reaches past midnight. The words read the same span, so they agree with the card.
#[test]
fn tonights_symbol_stays_inside_the_evening() {
    let oslo: Tz = "Europe/Oslo".parse().unwrap();
    let base = json!({ "sym": "cloudy", "sym6": "cloudy", "temp": 4, "mm": 0, "pop": 0 });
    let tonight_at = |set: &[Value], now: &str| {
        let hours = hours_of(oslo, row_date("2026-11-04"), &base, set);
        let at = local_instant(oslo, now);
        (tonight(&hours, oslo, at), words(&hours, oslo, at))
    };
    let symbol = |period: Option<Period>| period.and_then(|p| p.symbol);

    // 23:30, the 23:00 hour's six hours rain (from 03:00 on): the evening is the hour's own cloudy, no rain words.
    let late = [
        json!({ "from": "23:00", "to": "23:59", "sym6": "rain" }),
        json!({ "day": 1, "from": "03:00", "to": "05:00", "sym": "rain", "mm": 0.8, "pop": 80 }),
    ];
    let (night, said) = tonight_at(&late, "2026-11-04T23:30");
    assert_eq!(symbol(night.clone()).as_deref(), Some("cloudy"));
    assert_eq!(night.and_then(|p| p.precip_mm), Some(0.0));
    assert_eq!(said, None);

    // 19:04, rain 22:00-23:00 and a drizzle at 20:00: the wettest hour's symbol, the words and the mm agree.
    let wet = [
        json!({ "from": "20:00", "to": "21:00", "sym": "lightrain", "mm": 0.2 }),
        json!({ "from": "22:00", "to": "23:00", "sym": "rain", "mm": 0.9, "pop": 80 }),
    ];
    let (evening, said) = tonight_at(&wet, "2026-11-04T19:04");
    assert_eq!(symbol(evening.clone()).as_deref(), Some("rain"));
    assert_eq!(evening.and_then(|p| p.precip_mm), Some(1.1));
    let said = said.unwrap();
    assert_eq!((said.key, said.form), ("rain", Form::From));

    // A thunder hour wins over a wetter one.
    let storm = [
        json!({ "from": "20:00", "to": "21:00", "sym": "rainandthunder", "mm": 0.1 }),
        json!({ "from": "22:00", "to": "23:00", "sym": "heavyrain", "mm": 3.0 }),
    ];
    assert_eq!(
        symbol(tonight_at(&storm, "2026-11-04T19:04").0).as_deref(),
        Some("rainandthunder")
    );

    // From 18:00 exactly the span is the whole evening: the six hours' symbol, as the periods read it.
    let six = [json!({ "from": "18:00", "to": "19:00", "sym6": "snow" })];
    assert_eq!(
        symbol(tonight_at(&six, "2026-11-04T18:00").0).as_deref(),
        Some("snow")
    );
}
