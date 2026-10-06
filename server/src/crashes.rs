//! Launcher crash reports (cleanup 2026-10-06): `POST /api/devices/crashes` stores them, the
//! device page shows them. The launcher sends only a stack-trace hash and a short trace of class
//! names and frames - never an exception message - and this module re-checks that shape before
//! anything is stored, so a phone can't push free text into the database.

use serde::Deserialize;

/// At most this many crashes per request (the launcher keeps 5).
pub const MAX_PER_BATCH: usize = 10;
/// The trace is cut here.
pub const MAX_TRACE_CHARS: usize = 4000;
/// The device page shows this many, newest first.
pub const SHOWN: i64 = 5;

#[derive(Deserialize)]
pub struct CrashReportBatch {
    pub crashes: Vec<CrashReport>,
}

#[derive(Deserialize, Clone, Debug)]
pub struct CrashReport {
    pub hash: String,
    pub trace: String,
    pub count: i64,
    pub first_at_ms: i64,
    pub last_at_ms: i64,
    pub app_version_code: i64,
}

/// A crash as stored: everything checked and clamped.
#[derive(Debug, PartialEq, Eq)]
pub struct CleanCrash {
    pub hash: String,
    pub trace: String,
    pub count: i64,
    pub first_at_ms: i64,
    pub last_at_ms: i64,
    pub app_version_code: i64,
}

/// One crash on the device page.
#[derive(sqlx::FromRow)]
pub struct DeviceCrash {
    pub hash: String,
    pub trace: String,
    pub count: i64,
    pub last_at_ms: i64,
    pub app_version_code: i64,
}

impl DeviceCrash {
    /// The phone's clock when it last crashed this way, as UTC.
    pub fn last_at(&self) -> String {
        chrono::DateTime::from_timestamp_millis(self.last_at_ms)
            .map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
            .unwrap_or_else(|| "unknown".to_string())
    }

    /// The first line: the exception class.
    pub fn exception(&self) -> &str {
        self.trace.lines().next().unwrap_or("")
    }
}

/// The newest [SHOWN] crashes of a phone (empty on a DB error, logged).
pub async fn latest(db: &sqlx::SqlitePool, device_id: i64) -> Vec<DeviceCrash> {
    sqlx::query_as::<_, DeviceCrash>(
        "SELECT hash, trace, count, last_at_ms, app_version_code FROM device_crashes \
         WHERE device_id = ? ORDER BY last_at_ms DESC, id DESC LIMIT ?",
    )
    .bind(device_id)
    .bind(SHOWN)
    .fetch_all(db)
    .await
    .unwrap_or_else(|err| {
        tracing::error!(device_id, %err, "couldn't load crash reports");
        Vec::new()
    })
}

/// 1970-01-01 .. 2100-01-01 in ms, like the other phone-supplied times.
const MAX_MS: i64 = 4_102_444_800_000;

/// `None` when the hash isn't 16 lowercase hex chars or no trace line looks like the launcher's
/// format (an exception class line, `    at ...` frames, `Caused by: ...`, `    ... N more`);
/// lines in another shape are dropped, the rest cut to [MAX_TRACE_CHARS].
pub fn sanitize(report: &CrashReport) -> Option<CleanCrash> {
    let hash_ok = report.hash.len() == 16
        && report
            .hash
            .chars()
            .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c));
    if !hash_ok {
        return None;
    }
    let mut trace = String::new();
    for line in report.trace.lines() {
        if !trace_line_ok(line) {
            continue;
        }
        if trace.len() + line.len() + 1 > MAX_TRACE_CHARS {
            break;
        }
        trace.push_str(line);
        trace.push('\n');
    }
    if trace.is_empty() {
        return None;
    }
    let first = report.first_at_ms.clamp(0, MAX_MS);
    Some(CleanCrash {
        hash: report.hash.clone(),
        trace,
        count: report.count.clamp(1, 1_000_000),
        first_at_ms: first,
        last_at_ms: report.last_at_ms.clamp(first, MAX_MS),
        app_version_code: report.app_version_code.clamp(0, i64::from(i32::MAX)),
    })
}

/// Class names, method names and file names are Java identifiers, `.`, `$`, `<>`, `-` (synthetic
/// lambdas), digits and `:` before a line number - no spaces inside, no free text.
fn trace_line_ok(line: &str) -> bool {
    let ident = |s: &str| {
        !s.is_empty()
            && s.len() <= 300
            && s.chars().all(|c| {
                c.is_ascii_alphanumeric() || matches!(c, '.' | '$' | '_' | '<' | '>' | '-')
            })
    };
    if let Some(frame) = line.strip_prefix("    at ") {
        let Some((call, rest)) = frame.split_once('(') else {
            return false;
        };
        let Some(location) = rest.strip_suffix(')') else {
            return false;
        };
        let location_ok = location == "Unknown Source"
            || match location.split_once(':') {
                Some((file, line)) => ident(file) && line.chars().all(|c| c.is_ascii_digit()),
                None => ident(location),
            };
        return ident(call) && location_ok;
    }
    if let Some(more) = line.strip_prefix("    ... ") {
        return more
            .strip_suffix(" more")
            .is_some_and(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()));
    }
    ident(line.strip_prefix("Caused by: ").unwrap_or(line))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report(hash: &str, trace: &str) -> CrashReport {
        CrashReport {
            hash: hash.to_string(),
            trace: trace.to_string(),
            count: 2,
            first_at_ms: 1_000,
            last_at_ms: 2_000,
            app_version_code: 23_007_000,
        }
    }

    const TRACE: &str = "java.lang.IllegalStateException\n    at com.kidslauncher.mdm.calls.PhoneBookActivity.call(PhoneBookActivity.kt:10)\n    at android.app.Activity.performCreate(Unknown Source)\n    at com.example.Foo$lambda$0.invoke-abc(Foo.kt:7)\n    ... 3 more\nCaused by: java.lang.RuntimeException\n";

    #[test]
    fn a_launcher_trace_is_kept_whole() {
        let clean = sanitize(&report("0123456789abcdef", TRACE)).unwrap();
        assert_eq!(clean.trace, TRACE);
        assert_eq!(clean.count, 2);
    }

    #[test]
    fn free_text_never_gets_in() {
        let sneaky = format!(
            "{TRACE}Mamma called +47 912 34 567\n    at a.b(c.kt:1) secret\nhttps://x.example/?q=1\n"
        );
        let clean = sanitize(&report("0123456789abcdef", &sneaky)).unwrap();
        assert_eq!(clean.trace, TRACE);
        assert!(sanitize(&report("0123456789abcdef", "hello world\n")).is_none());
        assert!(sanitize(&report("0123456789abcdef", "")).is_none());
    }

    #[test]
    fn hashes_and_numbers_are_checked() {
        assert!(sanitize(&report("0123456789ABCDEF", TRACE)).is_none());
        assert!(sanitize(&report("0123456789abcde", TRACE)).is_none());
        assert!(sanitize(&report("0123456789abcdeg", TRACE)).is_none());
        let mut wild = report("0123456789abcdef", TRACE);
        wild.count = -5;
        wild.first_at_ms = i64::MIN;
        wild.last_at_ms = i64::MAX;
        wild.app_version_code = i64::MAX;
        let clean = sanitize(&wild).unwrap();
        assert_eq!(
            (
                clean.count,
                clean.first_at_ms,
                clean.last_at_ms,
                clean.app_version_code
            ),
            (1, 0, MAX_MS, i64::from(i32::MAX))
        );
    }

    #[test]
    fn long_traces_are_cut() {
        let long = "    at a.b(C.kt:1)\n".repeat(1000);
        let clean = sanitize(&report("0123456789abcdef", &long)).unwrap();
        assert!(clean.trace.len() <= MAX_TRACE_CHARS);
    }
}
