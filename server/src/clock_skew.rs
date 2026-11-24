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

use std::sync::atomic::{AtomicBool, Ordering};

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
    if let Some(skew) = skew_ms.filter(|s| s.abs() > WRONG_AFTER_MS) {
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

/// The server's own clock is off ([observe_date]), in memory: a restart checks again.
static SERVER_OFF: AtomicBool = AtomicBool::new(false);

/// The server's clock is off past this against an outside answer's `Date`.
pub const SERVER_OFF_AFTER_MS: i64 = 2 * 60_000;

/// The header a phone answer carries while the server's clock is off.
pub const CLOCK_HEADER: &str = "vibb-clock";

/// Whether `date` (an outside answer's `Date`, received at `now`) says this machine's clock is off.
pub fn server_off(date: DateTime<Utc>, now: DateTime<Utc>) -> bool {
    (now - date).num_milliseconds().abs() > SERVER_OFF_AFTER_MS
}

/// An outside answer's `Date` header (RFC 2822 / HTTP-date) against this machine's clock; an answer without one
/// changes nothing. No check yet counts as fine.
pub fn observe_date(header: Option<&str>) {
    let Some(date) = header.and_then(|h| DateTime::parse_from_rfc2822(h.trim()).ok()) else {
        return;
    };
    let off = server_off(date.with_timezone(&Utc), Utc::now());
    if off != SERVER_OFF.swap(off, Ordering::Relaxed) {
        if off {
            tracing::warn!(
                "this server's clock is off against GitHub's: phones won't take its time"
            );
        } else {
            tracing::info!("this server's clock agrees with GitHub's again");
        }
    }
}

/// The server's clock was off at the last check.
pub fn server_clock_off() -> bool {
    SERVER_OFF.load(Ordering::Relaxed)
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
    }
}
