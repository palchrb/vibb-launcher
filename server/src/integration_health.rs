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
//!   Storytel call it per request. Inside that, an in-memory note of the last success written skips the statement and
//!   its write lock (code QA L7).
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
        "music_feed" | "wifi_lookup" => Duration::hours(24),
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

/// The successes this process wrote, by database file, key and scope (code QA L7): a success inside [OK_EVERY] of the
/// last one written, with no failure recorded since, skips the statement - and SQLite's write lock - entirely. Keyed
/// by the database's file, so two databases in one process (the tests') never share an entry.
type OkKey = (std::path::PathBuf, String, String);
static OK_WRITTEN: std::sync::LazyLock<
    std::sync::Mutex<std::collections::HashMap<OkKey, DateTime<Utc>>>,
> = std::sync::LazyLock::new(Default::default);

fn ok_key(db: &SqlitePool, key: &str, scope: &str) -> OkKey {
    (
        db.connect_options().get_filename().to_path_buf(),
        key.to_string(),
        scope.to_string(),
    )
}

/// Records a success at `now`: `ok_at` set, `failing_since` cleared, `failures` 0 - written only when the row is
/// failing, new, or its `ok_at` is older than [OK_EVERY]. One upsert, skipped while [OK_WRITTEN] says the last success
/// written is recent; an error is logged.
pub async fn ok_at(db: &SqlitePool, key: &str, scope: &str, now: DateTime<Utc>) {
    let cache = ok_key(db, key, scope);
    let recent = OK_WRITTEN
        .lock()
        .map(|written| {
            written
                .get(&cache)
                .is_some_and(|at| *at <= now && now - *at <= OK_EVERY)
        })
        .unwrap_or(false);
    if recent {
        return;
    }
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
    match done {
        Ok(done) if done.rows_affected() > 0 => {
            if let Ok(mut written) = OK_WRITTEN.lock() {
                // Only the recent ones are worth keeping: the map stays as small as the keys in use.
                written.retain(|_, at| (now - *at).abs() <= OK_EVERY);
                written.insert(cache, now);
            }
        }
        Ok(_) => {}
        Err(err) => {
            tracing::error!(key, scope, %err, "integration health: can't record a success")
        }
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
    // The next success is written whatever the cache says.
    if let Ok(mut written) = OK_WRITTEN.lock() {
        written.remove(&ok_key(db, key, scope));
    }
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

/// An Attention row's title (design 116 §2.3, §6): one per title key. Two failing scopes of one title make one row,
/// their count summed (QA L3).
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Title {
    GithubDown,
    FdroidDown,
    /// github's and fdroid's `Fix`, summed: "N apps can't get updates".
    AppsFix,
    MetDown,
    MetFix,
    CaldavDown,
    CaldavFix,
    /// Both problems.
    CaldavWrite,
    /// Both problems, counted.
    CalendarLink,
    /// Both problems, counted.
    MusicFeed,
    StorytelDown,
    SpotifyDown,
    /// Both problems, counted.
    Blocklist,
    /// Both problems (design 125 A7: over 24 h of failed Apple calls, or the pause at its day).
    WifiLookup,
}

impl Title {
    /// The title of a failing row, `None` for a key that shows on no phone (`backup`, part D: a family row).
    pub fn of(key: &str, problem: Problem) -> Option<Title> {
        Some(match (key, problem) {
            ("github", Problem::Down) => Title::GithubDown,
            ("fdroid", Problem::Down) => Title::FdroidDown,
            ("github" | "fdroid", Problem::Fix) => Title::AppsFix,
            ("met", Problem::Down) => Title::MetDown,
            ("met", Problem::Fix) => Title::MetFix,
            ("caldav", Problem::Down) => Title::CaldavDown,
            ("caldav", Problem::Fix) => Title::CaldavFix,
            ("caldav_write", _) => Title::CaldavWrite,
            ("calendar_link", _) => Title::CalendarLink,
            ("music_feed", _) => Title::MusicFeed,
            ("storytel", _) => Title::StorytelDown,
            ("spotify", _) => Title::SpotifyDown,
            ("blocklist", _) => Title::Blocklist,
            ("wifi_lookup", _) => Title::WifiLookup,
            _ => return None,
        })
    }

    /// Its words' key (`device.attention.integration.*`); a counted one has `.one`/`.other`.
    pub fn words(self) -> &'static str {
        match self {
            Title::GithubDown => "device.attention.integration.github_down",
            Title::FdroidDown => "device.attention.integration.fdroid_down",
            Title::AppsFix => "device.attention.integration.apps_fix",
            Title::MetDown => "device.attention.integration.met_down",
            Title::MetFix => "device.attention.integration.met_fix",
            Title::CaldavDown => "device.attention.integration.caldav_down",
            Title::CaldavFix => "device.attention.integration.caldav_fix",
            Title::CaldavWrite => "device.attention.integration.caldav_write",
            Title::CalendarLink => "device.attention.integration.calendar_link",
            Title::MusicFeed => "device.attention.integration.music_feed",
            Title::StorytelDown => "device.attention.integration.storytel_down",
            Title::SpotifyDown => "device.attention.integration.spotify_down",
            Title::Blocklist => "device.attention.integration.blocklist",
            Title::WifiLookup => "device.attention.integration.wifi_lookup",
        }
    }

    /// Whether the words carry the count.
    pub fn counted(self) -> bool {
        matches!(
            self,
            Title::AppsFix | Title::CalendarLink | Title::MusicFeed | Title::Blocklist
        )
    }

    /// The row's stable name (`data-attention`): `integration-<title>`.
    pub fn key(self) -> &'static str {
        match self {
            Title::GithubDown => "integration-github-down",
            Title::FdroidDown => "integration-fdroid-down",
            Title::AppsFix => "integration-apps-fix",
            Title::MetDown => "integration-met-down",
            Title::MetFix => "integration-met-fix",
            Title::CaldavDown => "integration-caldav-down",
            Title::CaldavFix => "integration-caldav-fix",
            Title::CaldavWrite => "integration-caldav-write",
            Title::CalendarLink => "integration-calendar-link",
            Title::MusicFeed => "integration-music-feed",
            Title::StorytelDown => "integration-storytel-down",
            Title::SpotifyDown => "integration-spotify-down",
            Title::Blocklist => "integration-blocklist",
            Title::WifiLookup => "integration-wifi-lookup",
        }
    }

    /// The page that says what the error is (§2.3).
    pub fn target(self, device_id: i64) -> String {
        match self {
            // The Apps list's `update_failed` badge says which.
            Title::GithubDown | Title::FdroidDown | Title::AppsFix => "/apps".to_string(),
            Title::MetDown | Title::MetFix => format!("/devices/{device_id}/lock#weather"),
            Title::CaldavDown | Title::CaldavFix | Title::CaldavWrite | Title::CalendarLink => {
                format!("/devices/{device_id}/calendar")
            }
            Title::MusicFeed => "/music".to_string(),
            Title::StorytelDown => "/music/settings#storytel".to_string(),
            Title::SpotifyDown => format!("/devices/{device_id}/music#music-spotify"),
            Title::Blocklist => "/dns".to_string(),
            Title::WifiLookup => {
                format!("/devices/{device_id}/location-settings?from=device#wifi-lookup")
            }
        }
    }
}

/// The failing rows that map to this phone (§2.3), in one query: a parent's choice maps to no phone. A scope that is
/// gone joins nothing. `caldav_write` maps only while the phone writes to a marked CalDAV calendar (code QA M1), and
/// its stop only while the phone's stored error says so (code QA M2: the row follows `device_kid_events.error`).
const FOR_DEVICE: &str = "SELECT h.key, h.scope, h.ok_at, h.error_at, h.error, h.class, h.failing_since, h.failures \
    FROM integration_health h WHERE h.failing_since IS NOT NULL AND ( \
    (h.key IN ('github', 'fdroid') AND EXISTS (SELECT 1 FROM tracked_apps t \
        WHERE h.scope = 'app:' || t.id AND t.enabled = 1 AND (t.is_launcher = 1 OR EXISTS ( \
            SELECT 1 FROM device_tracked_apps d WHERE d.tracked_app_id = t.id AND d.device_id = ?1)))) \
    OR (h.key = 'met' AND h.scope = '' AND EXISTS (SELECT 1 FROM device_policy p \
        WHERE p.device_id = ?1 AND p.lock_weather = 1)) \
    OR (h.key = 'caldav' AND EXISTS (SELECT 1 FROM calendar_sources s \
        JOIN device_calendar_feeds f ON f.source_id = s.id \
        WHERE f.device_id = ?1 AND h.scope = 'account:' || s.account_id)) \
    OR (h.key IN ('caldav', 'calendar_link') AND EXISTS (SELECT 1 FROM device_calendar_feeds f \
        WHERE f.device_id = ?1 AND h.scope = 'source:' || f.source_id)) \
    OR (h.key = 'caldav_write' AND h.scope = 'device:' || ?1 \
        AND EXISTS (SELECT 1 FROM device_calendar_feeds f JOIN calendar_sources s ON s.id = f.source_id \
            WHERE f.device_id = ?1 AND f.kid_writes = 1 AND s.kind = 'caldav') \
        AND (h.class IS NOT 'stop' OR EXISTS (SELECT 1 FROM device_kid_events k \
            WHERE k.device_id = ?1 AND k.error IS NOT NULL))) \
    OR (h.key = 'music_feed' AND EXISTS (SELECT 1 FROM device_music_entries e \
        WHERE e.device_id = ?1 AND h.scope = 'entry:' || e.entry_id)) \
    OR (h.key = 'storytel' AND h.scope = '' AND EXISTS (SELECT 1 FROM device_policy p \
        WHERE p.device_id = ?1 AND p.music_storytel = 1)) \
    OR (h.key = 'spotify' AND EXISTS (SELECT 1 FROM device_policy p \
        WHERE p.device_id = ?1 AND h.scope = 'account:' || p.music_spotify_account)) \
    OR (h.key = 'blocklist' AND EXISTS (SELECT 1 FROM dns_blocklists b \
        JOIN device_policy p ON p.device_id = ?1 AND p.vpn_filter_enabled = 1 \
        LEFT JOIN device_blocklist_overrides o ON o.blocklist_id = b.id AND o.device_id = ?1 \
        WHERE h.scope = 'feed:' || b.id AND COALESCE(o.enabled, b.enabled) = 1)) \
    OR (h.key = 'wifi_lookup' AND h.scope = '' AND EXISTS (SELECT 1 FROM wifi_lookup_devices w \
        WHERE w.device_id = ?1 AND w.is_on = 1)) \
    )";

/// The phone's `Integration` rows (§2.3): every failing row that maps to it and is [raised], grouped by [Title] with
/// the failing scopes summed, in [Title]'s order.
pub async fn for_device(
    db: &SqlitePool,
    device_id: i64,
    now: DateTime<Utc>,
) -> Result<Vec<(Title, usize)>, sqlx::Error> {
    let rows: Vec<RawRow> = sqlx::query_as(FOR_DEVICE)
        .bind(device_id)
        .fetch_all(db)
        .await?;
    let mut titles = std::collections::BTreeMap::<Title, usize>::new();
    for row in rows.into_iter().map(row_of) {
        if let Some(title) =
            raised(&row, grace(&row.key), now).and_then(|problem| Title::of(&row.key, problem))
        {
            *titles.entry(title).or_default() += 1;
        }
    }
    Ok(titles.into_iter().collect())
}

/// Whether `key` at `scope` raises now (the lock page's weather line reads MET's).
pub async fn problem(
    db: &SqlitePool,
    key: &str,
    scope: &str,
    now: DateTime<Utc>,
) -> Result<Option<Problem>, sqlx::Error> {
    Ok(row(db, key, scope)
        .await?
        .and_then(|row| raised(&row, grace(key), now)))
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
        type Case<'a> = (&'a str, &'a [Option<Class>], DateTime<Utc>, Option<Problem>);
        let cases: [Case; 9] = [
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
        assert_eq!(grace("wifi_lookup"), Duration::hours(24));
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
