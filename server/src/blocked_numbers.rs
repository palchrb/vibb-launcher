//! Numbers blocked in Android's own list (`BlockedNumberContract`), which rejects a caller's calls and texts before
//! Vibb One sees them. While calls or SMS are managed the launcher clears a row only when it blocks a number of the
//! rules' contacts, one of the phone's own numbers or an emergency number; the kid's other blocks stay. It counts what
//! it cleared, by class only (never the number), in its `call_state`: `blocked_cleared_<class>` (counts since its
//! install), `blocked_cleared_at` (the last clearing, RFC 3339), `blocked_readable`, `blocked_left` (rows it should
//! have cleared and couldn't) and `blocked_kept` (the kid's own blocks: stored, never a line or a warning).
//! The first sweep after the phone became managed (the parent's switch) is `blocked_cleared_initial` alone: stored, never
//! a line or a warning. The flat keys go through `device_api::CALL_STATE_FIELDS`. Each class's rise against the previous
//! report is a security-log line ([log_lines], `phone_blocked_cleared`); the Calls page words the rest (`calls::Finding::BlockedCleared`,
//! `BlockedLeft`). The shapes are `contracts/core/blocked_numbers.json`, shared with the launcher.

/// The classes the launcher sorts a cleared entry into, in the contract's order.
pub const CLASSES: [&str; 2] = ["contact", "emergency"];

/// The `call_state` key of a class's count.
pub fn count_key(class: &str) -> String {
    format!("blocked_cleared_{class}")
}

/// A stored `call_state`'s counts per [CLASSES] (a missing or negative count is 0).
pub fn counts(call_state: Option<&serde_json::Value>) -> [i64; 2] {
    let mut counts = [0; 2];
    if let Some(state) = call_state {
        for (slot, class) in counts.iter_mut().zip(CLASSES) {
            *slot = state
                .get(count_key(class))
                .and_then(serde_json::Value::as_i64)
                .unwrap_or(0)
                .max(0);
        }
    }
    counts
}

/// Each class whose count rose since the previous report, with the rise. A lower count (the launcher's data cleared)
/// is taken silently; with no previous report nothing is logged (a fresh enrolment).
pub fn rises(previous: Option<[i64; 2]>, now: [i64; 2]) -> Vec<(&'static str, i64)> {
    let Some(previous) = previous else {
        return Vec::new();
    };
    CLASSES
        .iter()
        .zip(previous.iter().zip(now.iter()))
        .filter(|(_, (before, after))| after > before)
        .map(|(class, (before, after))| (*class, after - before))
        .collect()
}

/// The security log's line for one class's rise: the class and the count, never a number.
pub fn line(class: &str, rose: i64) -> crate::security::Detail {
    let key = match class {
        "contact" => "security.detail.blocked_cleared.contact",
        _ => "security.detail.blocked_cleared.emergency",
    };
    crate::security::Detail::counted(key, rose, &[])
}

/// Writes a line per class that rose between the previous stored `call_state` and this one (within the phone's
/// hourly line budget, `security::record_phone_event`).
pub async fn log_lines(
    db: &sqlx::SqlitePool,
    device_id: i64,
    previous: Option<Option<&str>>,
    now: Option<&str>,
) {
    let parse = |json: Option<&str>| json.and_then(|j| serde_json::from_str(j).ok());
    let now_state: Option<serde_json::Value> = parse(now);
    let previous = previous.map(|json| counts(parse(json).as_ref()));
    for (class, rose) in rises(previous, counts(now_state.as_ref())) {
        crate::security::record_phone_event_logged(
            db,
            device_id,
            crate::security::PhoneLine::BlockedCleared,
            line(class, rose),
        )
        .await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract() -> serde_json::Value {
        serde_json::from_str(
            &std::fs::read_to_string(crate::contracts::path("core/blocked_numbers.json")).unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn the_classes_are_the_contracts() {
        let classes: Vec<String> = serde_json::from_value(contract()["classes"].clone()).unwrap();
        assert_eq!(classes, CLASSES);
        // Every class has its words.
        for lang in [crate::i18n::Lang::En, crate::i18n::Lang::Nb] {
            for class in CLASSES {
                let worded = crate::security::worded_in(&line(class, 2), lang);
                assert!(worded.contains('2'), "{class}: {worded}");
                assert!(!worded.contains("blocked_cleared"), "{class}: {worded}");
            }
        }
    }

    #[test]
    fn the_rows_rise_as_the_contract_says() {
        for row in contract()["lines"].as_array().unwrap() {
            let previous = (!row["previous"].is_null()).then(|| counts(Some(&row["previous"])));
            let got: Vec<serde_json::Value> = rises(previous, counts(Some(&row["now"])))
                .into_iter()
                .map(|(class, n)| serde_json::json!({"class": class, "count": n}))
                .collect();
            assert_eq!(serde_json::Value::from(got), row["lines"], "{row}");
        }
    }

    #[test]
    fn the_report_reads_as_counts() {
        let report = &contract()["call_state"];
        assert_eq!(counts(Some(report)), [1, 2]);
    }
}
