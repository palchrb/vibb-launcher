//! Boots the launcher didn't see (design 24b §3, security review H4): the phone counts jumps of
//! `Settings.Global.BOOT_COUNT` between its process starts - a boot in safe mode, which runs no app
//! of ours and so no app, call or time rule, or a start cut short - and reports
//! `unseen_boots: {count, last_at_ms}`. While the last one is under 30 days old it is an Attention
//! alarm, and About This Phone's "Starts without Vibb" row explains it ([unseen], typed - design 31
//! M5); the security log gets `boot_unseen` when the count rose. Pure, unit-tested.

use serde_json::Value;

/// The warning shows this long after the last unseen boot.
pub const WARN_MS: i64 = 30 * 24 * 60 * 60 * 1000;
/// Phone-supplied values are kept within range (1970..2100, 0..1e6).
const MAX_MS: i64 = 4_102_444_800_000;
const MAX_COUNT: i64 = 1_000_000;

/// `StatusReportRequest.unseen_boots`, read leniently: `(count, last_at_ms)`, or `None` for
/// anything that isn't an object with a numeric `count`.
pub fn sanitize(value: &Value) -> Option<(i64, Option<i64>)> {
    let count = value.get("count")?.as_i64()?.clamp(0, MAX_COUNT);
    let last_at_ms = value
        .get("last_at_ms")
        .and_then(Value::as_i64)
        .map(|ms| ms.clamp(0, MAX_MS));
    Some((count, last_at_ms))
}

/// What to tell the parent about the cause.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BootAdvice {
    /// "Block safe mode" was on before the last one: probably a start cut short.
    CutShort,
    /// It is on now, but maybe not yet at that start (or since when is unknown): either.
    Either,
    /// Safe mode is allowed: block it.
    BlockSafeMode,
}

/// Starts without the launcher, the last one within 30 days.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UnseenBoots {
    /// How many the phone counted in all.
    pub count: i64,
    pub last_at_ms: i64,
    pub advice: BootAdvice,
}

impl UnseenBoots {
    /// The row's value: "1 time", "3 times".
    pub fn value(&self) -> String {
        if self.count == 1 {
            "1 time".to_string()
        } else {
            format!("{} times", self.count)
        }
    }

    /// The ⓘ under the row: when, what it means and what to do.
    pub fn detail(
        &self,
        zone: &crate::display_time::Zone,
        now: chrono::DateTime<chrono::Utc>,
    ) -> String {
        let when = chrono::DateTime::from_timestamp_millis(self.last_at_ms)
            .map(|t| zone.when(t, now))
            .unwrap_or_default();
        let advice = match self.advice {
            BootAdvice::CutShort => {
                "Safe mode is blocked, so it was probably a start cut short. Ask your child if it \
                 repeats."
            }
            BootAdvice::Either => {
                "Safe mode is blocked now, but maybe not yet at that start. Ask your child."
            }
            BootAdvice::BlockSafeMode => "Turn on Block safe mode in Advanced.",
        };
        format!(
            "Last noticed {when}. In safe mode the phone skips every app, call and time rule; a \
             start can also be cut short (battery, a system update rolling back). {advice}"
        )
    }
}

/// The unseen boots worth telling the parent about: `None` without one in the last 30 days.
/// `blocked_since`: when "Block safe mode" was turned on (`None` = unknown) - only a boot noticed
/// after it was probably a start cut short (QA Low 12); before it, or unknown, it may have been
/// either.
pub fn unseen(
    count: Option<i64>,
    last_at_ms: Option<i64>,
    now_ms: i64,
    safe_boot_blocked: bool,
    blocked_since: Option<i64>,
) -> Option<UnseenBoots> {
    let count = count.filter(|n| *n > 0)?;
    let last = last_at_ms.filter(|at| now_ms - at <= WARN_MS)?;
    let advice = match (safe_boot_blocked, blocked_since) {
        (true, Some(since)) if last > since => BootAdvice::CutShort,
        (true, _) => BootAdvice::Either,
        (false, _) => BootAdvice::BlockSafeMode,
    };
    Some(UnseenBoots {
        count,
        last_at_ms: last,
        advice,
    })
}

/// `boot_unseen`'s detail when the count rose since the previous report (`None` = it didn't).
pub fn log_line(device_id: i64, previous: Option<i64>, count: Option<i64>) -> Option<String> {
    let count = count?;
    let rose = count - previous.unwrap_or(0);
    (rose > 0).then(|| {
        format!("device {device_id}: {rose} start(s) without the launcher running noticed ({count} in all)")
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const NOW: i64 = 1_760_100_000_000;

    #[test]
    fn sanitizing_keeps_numbers_in_range() {
        assert_eq!(
            sanitize(&json!({"count": 1, "last_at_ms": 1760100000000_i64})),
            Some((1, Some(1_760_100_000_000)))
        );
        assert_eq!(
            sanitize(&json!({"count": 0, "last_at_ms": null})),
            Some((0, None))
        );
        assert_eq!(
            sanitize(&json!({"count": -4, "last_at_ms": -1})),
            Some((0, Some(0)))
        );
        assert_eq!(sanitize(&json!({"count": "7"})), None);
        assert_eq!(sanitize(&json!("x")), None);
    }

    #[test]
    fn unseen_boots_and_their_expiry() {
        let at = NOW - 3 * 60 * 60 * 1000;
        let one = unseen(Some(1), Some(at), NOW, false, None).unwrap();
        assert_eq!(
            one,
            UnseenBoots {
                count: 1,
                last_at_ms: at,
                advice: BootAdvice::BlockSafeMode
            }
        );
        assert_eq!(one.value(), "1 time");
        let zone = crate::display_time::Zone::from_setting("Europe/Oslo");
        let now = chrono::DateTime::from_timestamp_millis(NOW).unwrap();
        assert_eq!(
            one.detail(&zone, now),
            "Last noticed 11:40. In safe mode the phone skips every app, call and time rule; a \
             start can also be cut short (battery, a system update rolling back). Turn on Block \
             safe mode in Advanced."
        );
        let three = unseen(Some(3), Some(at), NOW, true, Some(at - 1)).unwrap();
        assert_eq!(three.value(), "3 times");
        assert!(
            three
                .detail(&zone, now)
                .ends_with("so it was probably a start cut short. Ask your child if it repeats.")
        );
        // The last one more than 30 days ago, none at all, or no time: nothing.
        assert_eq!(
            unseen(Some(2), Some(NOW - WARN_MS - 1), NOW, false, None),
            None
        );
        assert!(unseen(Some(2), Some(NOW - WARN_MS), NOW, false, None).is_some());
        assert_eq!(unseen(Some(0), Some(at), NOW, false, None), None);
        assert_eq!(unseen(None, None, NOW, false, None), None);
        assert_eq!(unseen(Some(2), None, NOW, false, None), None);
    }

    #[test]
    fn the_advice_follows_the_switch_in_force_at_the_boot() {
        let at = NOW - 3 * 60 * 60 * 1000;
        let advice = |blocked: bool, since: Option<i64>| {
            unseen(Some(1), Some(at), NOW, blocked, since)
                .unwrap()
                .advice
        };
        // Turned on after the boot (S4's migration, or the parent), or when is unknown: either.
        assert_eq!(advice(true, Some(at + 1)), BootAdvice::Either);
        assert_eq!(advice(true, Some(at)), BootAdvice::Either);
        assert_eq!(advice(true, None), BootAdvice::Either);
        assert_eq!(advice(true, Some(at - 1)), BootAdvice::CutShort);
        assert_eq!(advice(false, Some(at - 1)), BootAdvice::BlockSafeMode);
    }

    #[test]
    fn the_log_line_only_when_the_count_rose() {
        assert_eq!(
            log_line(3, Some(1), Some(2)).as_deref(),
            Some("device 3: 1 start(s) without the launcher running noticed (2 in all)")
        );
        assert_eq!(log_line(3, Some(2), Some(2)), None);
        assert_eq!(log_line(3, None, Some(0)), None);
        assert!(log_line(3, None, Some(2)).is_some());
        assert_eq!(log_line(3, Some(2), None), None);
    }
}
