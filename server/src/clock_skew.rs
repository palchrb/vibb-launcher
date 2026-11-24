//! The phone's clock as its decisions see it (time rules keep their own clock): the three `time_state` fields the
//! launcher reports - `clock_skew_ms` (its own wall clock minus the time its decisions use, in five-minute steps),
//! `clock_trust` (`server`, `gnss`, `carried`, `none`) and `unanchored_ms` (since the time was last checked, whole
//! hours) - cleaned here, and the one orange line they may raise ([problem]): the clock off by more than
//! [WRONG_AFTER_MS], or the time not checked for more than [UNCHECKED_AFTER_MS]. Nothing else is said: a clock that
//! is right shows nothing. The rows are pinned by `testdata/clock_skew.json` (byte-identical in the launcher's test
//! resources; `tests::clock_skew`, the launcher's `ClockSkewContractTest`).
//!
//! Also the server's own clock (design M1 §4): its GitHub fetches compare the answer's `Date` with this machine's
//! clock ([observe_date]); past [SERVER_OFF_AFTER_MS] the Software Updates page says so and every phone answer
//! carries `Vibb-Clock: off` ([CLOCK_HEADER]), which a phone never takes as its time.

use std::sync::atomic::{AtomicU8, Ordering};

use chrono::{DateTime, Utc};

use crate::i18n::{Lang, t, t_params};

/// A skew kept: at most this far either way (20 years); anything else is dropped.
pub const MAX_SKEW_MS: i64 = 20 * 365 * 24 * 3_600_000;
/// The time since the last check kept: 0 to a year; anything else is dropped.
pub const MAX_UNANCHORED_MS: i64 = 365 * 24 * 3_600_000;
/// The clock is wrong past this (the phone's own steps are five minutes).
pub const WRONG_AFTER_MS: i64 = 10 * 60_000;
/// The time is unchecked past this.
pub const UNCHECKED_AFTER_MS: i64 = 24 * 3_600_000;
/// The words `clock_trust` may hold.
pub const TRUSTS: [&str; 4] = ["server", "gnss", "carried", "none"];

/// The reported skew as kept: an integer within [MAX_SKEW_MS], else none.
pub fn clean_skew(value: &serde_json::Value) -> Option<i64> {
    value.as_i64().filter(|ms| ms.abs() <= MAX_SKEW_MS)
}

/// The reported trust as kept: one of [TRUSTS]; anything else (or nothing) reads as `none`.
pub fn clean_trust(value: &serde_json::Value) -> Option<String> {
    let word = value.as_str()?;
    Some(if TRUSTS.contains(&word) { word } else { "none" }.to_string())
}

/// The reported time since the last check as kept: 0 up to [MAX_UNANCHORED_MS], else none.
pub fn clean_unanchored(value: &serde_json::Value) -> Option<i64> {
    value
        .as_i64()
        .filter(|ms| (0..=MAX_UNANCHORED_MS).contains(ms))
}

/// What the parent is told about the phone's clock: at most one line.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClockProblem {
    /// The phone's own clock is this far off the time its decisions use (wall minus trusted; positive = ahead).
    Wrong(i64),
    /// The time hasn't been checked for this long.
    Unchecked(i64),
}

/// The line, if any: a wrong clock first, then an unchecked one. Under `none` (never checked: before the first sync)
/// there is nothing to compare.
pub fn problem(
    skew_ms: Option<i64>,
    trust: Option<&str>,
    unanchored_ms: Option<i64>,
) -> Option<ClockProblem> {
    if trust.is_none_or(|t| t == "none") {
        return None;
    }
    if let Some(skew) = skew_ms.filter(|s| s.abs() >= WRONG_AFTER_MS) {
        return Some(ClockProblem::Wrong(skew));
    }
    unanchored_ms
        .filter(|u| *u > UNCHECKED_AFTER_MS)
        .map(ClockProblem::Unchecked)
}

impl ClockProblem {
    /// The orange line (and the Attention title), in `lang`: "Klokken går feil (26 t foran)", "Tiden er ikke sjekket på
    /// 30 t".
    pub fn words(self, lang: Lang) -> String {
        match self {
            ClockProblem::Wrong(skew) => {
                let minutes = skew.abs() / 60_000;
                // Whole hours from an hour on, minutes below: one short amount.
                let shown = if minutes >= 60 {
                    (minutes / 60) * 60
                } else {
                    minutes
                };
                let amount = crate::display_time::duration(lang, shown);
                let direction = t(
                    lang,
                    if skew > 0 {
                        "device.clock.ahead"
                    } else {
                        "device.clock.behind"
                    },
                );
                t_params(
                    lang,
                    "device.clock.wrong",
                    &[("amount", &amount), ("direction", direction)],
                )
            }
            ClockProblem::Unchecked(ms) => t_params(
                lang,
                "device.clock.unchecked",
                &[("hours", &(ms / 3_600_000).to_string())],
            ),
        }
    }

    /// The sentence behind its (i).
    pub fn detail(self, lang: Lang) -> String {
        t(lang, "device.clock.info").to_string()
    }
}

/// The server's own clock against an outside answer ([observe_date]), in memory: 0 unknown (no check yet, or the
/// last check failed - never said as wrong), 1 right, 2 off. A restart checks again.
static SERVER_CLOCK: AtomicU8 = AtomicU8::new(0);

/// The server's clock is off past this against an outside answer's `Date`.
pub const SERVER_OFF_AFTER_MS: i64 = 2 * 60_000;

/// How often the server checks its clock ([run_check]).
pub const CHECK_EVERY: std::time::Duration = std::time::Duration::from_secs(10 * 60);

/// The header a phone answer carries while the server's clock is off.
pub const CLOCK_HEADER: &str = "vibb-clock";

/// Whether `date` (an outside answer's `Date`, received at `now`) says this machine's clock is off.
pub fn server_off(date: DateTime<Utc>, now: DateTime<Utc>) -> bool {
    (now - date).num_milliseconds().abs() > SERVER_OFF_AFTER_MS
}

/// The clock's state from one check: `Date`'s text (RFC 2822 / HTTP-date) received at `now`, `None` for a check that
/// failed or an answer without one - unknown, never off.
pub fn judged(header: Option<&str>, now: DateTime<Utc>) -> u8 {
    match header.and_then(|h| DateTime::parse_from_rfc2822(h.trim()).ok()) {
        Some(date) if server_off(date.with_timezone(&Utc), now) => 2,
        Some(_) => 1,
        None => 0,
    }
}

/// One check's answer (`None`: it failed) against this machine's clock.
pub fn observe_date(header: Option<&str>) {
    let state = judged(header, Utc::now());
    let before = SERVER_CLOCK.swap(state, Ordering::Relaxed);
    if state != before {
        match state {
            2 => tracing::warn!(
                "this server's clock is off against GitHub's: phones won't take its time"
            ),
            1 => tracing::info!("this server's clock agrees with GitHub's"),
            _ => tracing::info!("this server's clock can't be checked now"),
        }
    }
}

/// The server's clock was off at the last check that worked.
pub fn server_clock_off() -> bool {
    SERVER_CLOCK.load(Ordering::Relaxed) == 2
}

/// Checks the server's clock at the start and every [CHECK_EVERY]: a `HEAD` to GitHub's API, its `Date` against this
/// machine's clock (a failure is unknown). Started by `main`, never by the tests.
pub async fn run_check(api: String) {
    let client = match reqwest::Client::builder()
        .user_agent(crate::net::USER_AGENT)
        .timeout(std::time::Duration::from_secs(15))
        .build()
    {
        Ok(client) => client,
        Err(err) => {
            tracing::error!(%err, "no client for the server's clock check");
            return;
        }
    };
    loop {
        let date = match client.head(&api).send().await {
            Ok(answer) => answer
                .headers()
                .get(reqwest::header::DATE)
                .and_then(|v| v.to_str().ok())
                .map(str::to_string),
            Err(_) => None,
        };
        observe_date(date.as_deref());
        tokio::time::sleep(CHECK_EVERY).await;
    }
}

/// Adds `Vibb-Clock: off` to a phone answer while the server's clock is off.
pub async fn mark_answers(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    let mut response = next.run(request).await;
    if server_clock_off() {
        response
            .headers_mut()
            .insert(CLOCK_HEADER, axum::http::HeaderValue::from_static("off"));
    }
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_server_clock_check() {
        let now = DateTime::parse_from_rfc3339("2026-11-24T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        assert!(!server_off(now - chrono::Duration::seconds(90), now));
        assert!(server_off(now - chrono::Duration::minutes(3), now));
        assert!(server_off(now + chrono::Duration::minutes(3), now));
        assert_eq!(judged(None, now), 0, "a failed check is unknown, never off");
        assert_eq!(judged(Some("not a date"), now), 0);
        assert_eq!(judged(Some("Tue, 24 Nov 2026 10:00:30 GMT"), now), 1);
        assert_eq!(judged(Some("Tue, 24 Nov 2026 10:05:00 GMT"), now), 2);
    }
}
