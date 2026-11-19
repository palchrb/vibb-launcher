//! Each outside call's last success and last failure (design 116 part B, consolidation C2): one row per integration
//! key and scope in `integration_health` (0158), fed at each call site by [ok] and [failed], read by Attention's
//! `Integration` row through [raised].
//!
//! - **One call per outcome.** A call site records its success with [ok] and its failure with [failed], its code
//!   classed by the site's own exhaustive `class_of` (no `_` arm: a new variant doesn't compile until it is classed;
//!   `None` = not recorded - our own state, a race, our own waits). A boundary integration (design 110's
//!   `integrations::Integration`) feeds it the same way, under its own name as the key
//!   (`docs/notes/server/integration-health.md`).
//! - **Never in the way.** A write error is logged and never fails the call it records.
//! - **Cheap.** [ok] writes only when the row is failing or its `ok_at` is older than [OK_EVERY] (QA L10): Spotify and
//!   Storytel call it per request.
//! - **A state, not an event** (§2.4): no push, no inbox; the next success clears the row at once.

use chrono::{DateTime, Duration, NaiveDateTime, Utc};
use sqlx::SqlitePool;

/// How old a success may be before the next one is written again (QA L10).
pub const OK_EVERY: Duration = Duration::minutes(10);
/// Failures in a row before a transient run raises `Down` (§2.2 rule 4).
pub const DOWN_AFTER: i64 = 3;
/// Parent-class failures in a row before `Fix` (§2.2 rule 3).
pub const FIX_AFTER: i64 = 2;
/// The longest a stored code is kept.
const CODE_MAX: usize = 64;
/// The stored times' form (UTC), comparable as text and by SQLite's `datetime()`.
const TIME_FORMAT: &str = "%Y-%m-%d %H:%M:%S";

/// A failure's kind (§2.1's table).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Class {
    /// The other side is away for now: raises `Down` after a sustained run.
    Transient,
    /// Something the parent can mend (a link gone, a feed private): `Fix` at the second.
    Parent,
    /// The integration has stopped itself and no retry will mend it: `Fix` at once.
    Stop,
}

impl Class {
    pub fn as_str(self) -> &'static str {
        match self {
            Class::Transient => "transient",
            Class::Parent => "parent",
            Class::Stop => "stop",
        }
    }

    fn parse(text: &str) -> Option<Class> {
        match text {
            "transient" => Some(Class::Transient),
            "parent" => Some(Class::Parent),
            "stop" => Some(Class::Stop),
            _ => None,
        }
    }
}

/// What a failing row asks of the parent (§2.2).
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Problem {
    /// The other side doesn't answer: wait or look.
    Down,
    /// The parent must mend something.
    Fix,
}

/// One stored row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    pub key: String,
    pub scope: String,
    pub ok_at: Option<DateTime<Utc>>,
    pub error_at: Option<DateTime<Utc>>,
    pub error: Option<String>,
    pub class: Option<Class>,
    pub failing_since: Option<DateTime<Utc>>,
    pub failures: i64,
}

/// How long a transient run must last before it raises `Down` (§2.2's table).
pub fn grace(key: &str) -> Duration {
    match key {
        "met" | "caldav_write" => Duration::hours(6),
        "music_feed" => Duration::hours(24),
        // github, fdroid, calendar_link, caldav, storytel, spotify, blocklist, and any boundary integration.
        _ => Duration::hours(12),
    }
}

/// Whether a row raises, and as what (§2.2):
/// 1. the newest outcome is a success (`failing_since` NULL): nothing;
/// 2. the newest failure is `stop`: `Fix` at once;
/// 3. it is `parent` and there were at least [FIX_AFTER] failures since the last success: `Fix`;
/// 4. otherwise `Down` once there were at least [DOWN_AFTER] **and** the run is at least `grace` old.
///
/// A run needs to be sustained and any success ends it, so an integration that fails every other call never raises.
pub fn raised(row: &Row, grace: Duration, now: DateTime<Utc>) -> Option<Problem> {
    let since = row.failing_since?;
    match row.class {
        Some(Class::Stop) => return Some(Problem::Fix),
        Some(Class::Parent) if row.failures >= FIX_AFTER => return Some(Problem::Fix),
        _ => {}
    }
    (row.failures >= DOWN_AFTER && now - since >= grace).then_some(Problem::Down)
}

fn stamp(at: DateTime<Utc>) -> String {
    at.format(TIME_FORMAT).to_string()
}

fn parse_time(text: Option<String>) -> Option<DateTime<Utc>> {
    let text = text?;
    NaiveDateTime::parse_from_str(&text, TIME_FORMAT)
        .ok()
        .map(|t| t.and_utc())
}

/// Records a success now.
pub async fn ok(db: &SqlitePool, key: &str, scope: &str) {
    ok_at(db, key, scope, Utc::now()).await;
}

/// Records a failure now, with the call site's code and its class.
pub async fn failed(db: &SqlitePool, key: &str, scope: &str, code: &str, class: Class) {
    failed_at(db, key, scope, code, class, Utc::now()).await;
}

/// Records a success at `now`: `ok_at` set, `failing_since` cleared, `failures` 0 - written only when the row is
/// failing, new, or its `ok_at` is older than [OK_EVERY]. One upsert; an error is logged.
pub async fn ok_at(db: &SqlitePool, key: &str, scope: &str, now: DateTime<Utc>) {
    let done = sqlx::query(
        "INSERT INTO integration_health (key, scope, ok_at) VALUES (?1, ?2, ?3) \
         ON CONFLICT (key, scope) DO UPDATE SET ok_at = excluded.ok_at, failing_since = NULL, failures = 0 \
         WHERE integration_health.failing_since IS NOT NULL OR integration_health.ok_at IS NULL \
            OR integration_health.ok_at < ?4",
    )
    .bind(key)
    .bind(scope)
    .bind(stamp(now))
    .bind(stamp(now - OK_EVERY))
    .execute(db)
    .await;
    if let Err(err) = done {
        tracing::error!(key, scope, %err, "integration health: can't record a success");
    }
}

/// Records a failure at `now`: `error`, `error_at` and `class` set, `failing_since` set if empty, `failures` + 1. One
/// upsert; an error is logged.
pub async fn failed_at(
    db: &SqlitePool,
    key: &str,
    scope: &str,
    code: &str,
    class: Class,
    now: DateTime<Utc>,
) {
    tracing::debug!(
        key,
        scope,
        code,
        class = class.as_str(),
        "integration failed"
    );
    let code: String = code.chars().take(CODE_MAX).collect();
    let done = sqlx::query(
        "INSERT INTO integration_health (key, scope, error_at, error, class, failing_since, failures) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?3, 1) \
         ON CONFLICT (key, scope) DO UPDATE SET error_at = excluded.error_at, error = excluded.error, \
            class = excluded.class, \
            failing_since = COALESCE(integration_health.failing_since, excluded.failing_since), \
            failures = integration_health.failures + 1",
    )
    .bind(key)
    .bind(scope)
    .bind(stamp(now))
    .bind(code)
    .bind(class.as_str())
    .execute(db)
    .await;
    if let Err(err) = done {
        tracing::error!(key, scope, %err, "integration health: can't record a failure");
    }
}

type RawRow = (
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    i64,
);

fn row_of(raw: RawRow) -> Row {
    let (key, scope, ok_at, error_at, error, class, failing_since, failures) = raw;
    Row {
        key,
        scope,
        ok_at: parse_time(ok_at),
        error_at: parse_time(error_at),
        error,
        class: class.as_deref().and_then(Class::parse),
        failing_since: parse_time(failing_since),
        failures,
    }
}

const COLUMNS: &str = "key, scope, ok_at, error_at, error, class, failing_since, failures";

/// One row, if any.
pub async fn row(db: &SqlitePool, key: &str, scope: &str) -> Result<Option<Row>, sqlx::Error> {
    let raw: Option<RawRow> = sqlx::query_as(&format!(
        "SELECT {COLUMNS} FROM integration_health WHERE key = ? AND scope = ?"
    ))
    .bind(key)
    .bind(scope)
    .fetch_optional(db)
    .await?;
    Ok(raw.map(row_of))
}

/// Deletes the rows with neither a success nor a failure in 30 days (`retention::prune`).
pub async fn prune(db: &SqlitePool) -> Result<sqlx::sqlite::SqliteQueryResult, sqlx::Error> {
    sqlx::query(
        "DELETE FROM integration_health \
         WHERE MAX(COALESCE(ok_at, ''), COALESCE(error_at, '')) < datetime('now', '-30 days')",
    )
    .execute(db)
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(minutes: i64) -> DateTime<Utc> {
        DateTime::parse_from_rfc3339("2026-11-18T12:00:00Z")
            .unwrap()
            .with_timezone(&Utc)
            + Duration::minutes(minutes)
    }

    /// The row after these outcomes, each a minute apart from `start` (`None` a success), as the store writes it.
    fn after(outcomes: &[Option<Class>], start: DateTime<Utc>) -> Row {
        let mut row = Row {
            key: "github".into(),
            scope: "app:1".into(),
            ok_at: None,
            error_at: None,
            error: None,
            class: None,
            failing_since: None,
            failures: 0,
        };
        for (i, outcome) in outcomes.iter().enumerate() {
            let now = start + Duration::minutes(i as i64);
            match outcome {
                None => {
                    row.ok_at = Some(now);
                    row.failing_since = None;
                    row.failures = 0;
                }
                Some(class) => {
                    row.error_at = Some(now);
                    row.class = Some(*class);
                    row.failing_since.get_or_insert(now);
                    row.failures += 1;
                }
            }
        }
        row
    }

    /// §2.2's rules, row by row.
    #[test]
    fn the_rule() {
        use Class::*;
        let grace = Duration::hours(12);
        let start = at(0);
        let late = at(13 * 60);
        let cases: [(&str, &[Option<Class>], DateTime<Utc>, Option<Problem>); 9] = [
            ("never failed", &[None], late, None),
            ("stop at once", &[Some(Stop)], at(1), Some(Problem::Fix)),
            ("one parent waits", &[Some(Parent)], late, None),
            (
                "parent twice",
                &[Some(Parent), Some(Parent)],
                at(2),
                Some(Problem::Fix),
            ),
            (
                "transient, three, inside the grace",
                &[Some(Transient), Some(Transient), Some(Transient)],
                at(60),
                None,
            ),
            (
                "transient, three, after the grace",
                &[Some(Transient), Some(Transient), Some(Transient)],
                late,
                Some(Problem::Down),
            ),
            (
                "a success clears",
                &[Some(Transient), Some(Transient), Some(Transient), None],
                late,
                None,
            ),
            (
                "alternating never raises",
                &[
                    Some(Transient),
                    None,
                    Some(Transient),
                    None,
                    Some(Transient),
                    None,
                    Some(Transient),
                ],
                late,
                None,
            ),
            (
                "transient, transient, parent is Fix",
                &[Some(Transient), Some(Transient), Some(Parent)],
                at(3),
                Some(Problem::Fix),
            ),
        ];
        for (name, outcomes, now, expected) in cases {
            assert_eq!(
                raised(&after(outcomes, start), grace, now),
                expected,
                "{name}"
            );
        }
    }

    #[test]
    fn the_graces() {
        assert_eq!(grace("met"), Duration::hours(6));
        assert_eq!(grace("caldav_write"), Duration::hours(6));
        assert_eq!(grace("music_feed"), Duration::hours(24));
        for key in [
            "github",
            "fdroid",
            "calendar_link",
            "caldav",
            "storytel",
            "spotify",
            "blocklist",
        ] {
            assert_eq!(grace(key), Duration::hours(12), "{key}");
        }
    }

    /// The upserts: a failure counts and keeps the run's start, a success clears it, and a success within
    /// [OK_EVERY] of the last one writes nothing.
    #[tokio::test]
    async fn the_upserts_and_the_ok_throttle() {
        let dir = tempfile::tempdir().unwrap();
        let db =
            crate::connect_db(&format!("sqlite://{}", dir.path().join("t.db").display())).await;
        failed_at(&db, "met", "", "network", Class::Transient, at(0)).await;
        failed_at(&db, "met", "", "status_403", Class::Parent, at(5)).await;
        let row = row(&db, "met", "").await.unwrap().unwrap();
        assert_eq!(row.failures, 2);
        assert_eq!(row.failing_since, Some(at(0)));
        assert_eq!(row.error_at, Some(at(5)));
        assert_eq!(row.error.as_deref(), Some("status_403"));
        assert_eq!(row.class, Some(Class::Parent));

        // A success while failing is always written.
        ok_at(&db, "met", "", at(6)).await;
        let row = super::row(&db, "met", "").await.unwrap().unwrap();
        assert_eq!(
            (row.ok_at, row.failing_since, row.failures),
            (Some(at(6)), None, 0)
        );
        // The last error stays for the logs.
        assert_eq!(row.error.as_deref(), Some("status_403"));

        // Within ten minutes of it: nothing written.
        ok_at(&db, "met", "", at(12)).await;
        assert_eq!(
            super::row(&db, "met", "").await.unwrap().unwrap().ok_at,
            Some(at(6))
        );
        // Past ten minutes: written.
        ok_at(&db, "met", "", at(17)).await;
        assert_eq!(
            super::row(&db, "met", "").await.unwrap().unwrap().ok_at,
            Some(at(17))
        );

        // A new scope's first success is written.
        ok_at(&db, "github", "app:1", at(0)).await;
        assert!(super::row(&db, "github", "app:1").await.unwrap().is_some());
    }
}
