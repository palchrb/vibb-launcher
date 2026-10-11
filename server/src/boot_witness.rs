//! Boots the launcher didn't see (design 24b §3, security review H4): the phone counts jumps of
//! `Settings.Global.BOOT_COUNT` between its process starts - a boot in safe mode, which runs no app
//! of ours and so no app, call or time rule, or a start cut short - and reports
//! `unseen_boots: {count, last_at_ms}`. The device page warns while the last one is under 30 days
//! old; the security log gets `boot_unseen` when the count rose. Pure, unit-tested.

use serde_json::Value;

/// The device page shows the warning this long after the last unseen boot.
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

/// The Status card's warning: `None` without an unseen boot in the last 30 days.
pub fn warning(
    count: Option<i64>,
    last_at_ms: Option<i64>,
    now_ms: i64,
    safe_boot_blocked: bool,
) -> Option<String> {
    let count = count.filter(|n| *n > 0)?;
    let last = last_at_ms.filter(|at| now_ms - at <= WARN_MS)?;
    let times = if count == 1 {
        "1 time".to_string()
    } else {
        format!("{count} times")
    };
    let advice = if safe_boot_blocked {
        "Safe mode is blocked, so it was probably a start cut short - ask your child if it repeats."
    } else {
        "Turn on \"Block safe mode\" under Phone hardening."
    };
    Some(format!(
        "The phone started {times} without the launcher running, last noticed {}. That happens in \
         safe mode, which skips every app, call and time rule, or with a start cut short (battery, \
         a system update rolling back). {advice}",
        crate::parent_pin::when_text(last, now_ms)
    ))
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
    fn both_warning_variants_and_their_expiry() {
        let at = NOW - 3 * 60 * 60 * 1000;
        assert_eq!(
            warning(Some(1), Some(at), NOW, false).unwrap(),
            "The phone started 1 time without the launcher running, last noticed 2025-10-10 09:40 UTC \
             (3 h ago). That happens in safe mode, which skips every app, call and time rule, or with a \
             start cut short (battery, a system update rolling back). Turn on \"Block safe mode\" under \
             Phone hardening."
        );
        assert!(warning(Some(3), Some(at), NOW, true).unwrap().ends_with(
            "The phone started 3 times without the launcher running, last noticed 2025-10-10 09:40 UTC (3 h ago). That happens in safe mode, which skips every app, call and time rule, or with a start cut short (battery, a system update rolling back). Safe mode is blocked, so it was probably a start cut short - ask your child if it repeats."
        ));
        assert_eq!(warning(Some(2), Some(NOW - WARN_MS - 1), NOW, false), None);
        assert_eq!(warning(Some(0), Some(at), NOW, false), None);
        assert_eq!(warning(None, None, NOW, false), None);
        assert_eq!(warning(Some(2), None, NOW, false), None);
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
