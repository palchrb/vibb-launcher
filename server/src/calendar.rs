//! Dagen (design 69, `docs/design/69-dagen.md` at the monorepo root, migrations/0103_calendar.sql): the kid's
//! day view in Vibb One reads the phone's calendar provider, which ICSx5 and DAVx5 feed. The server never sees an
//! event: it keeps the parent's choices for the phone's calendars (shown, the reminder, the kid's own calendar) and
//! the title rules, sends them as `PolicyResponse.calendar`, and stores the phone's report of which calendars it has
//! (`calendar` in the status report, [sanitize_report]).
//!
//! The title rules are one grammar on both sides (§3.4): [clean] (control and bidi characters out, whitespace runs
//! one space, NFC), matching that ignores case per code point and whitespace, and the three actions. The launcher's
//! `day/TitleRules.kt` is the same code; `testdata/calendar_title_rules.json` (byte-identical in the launcher's test
//! resources) pins both. The PWA's preview runs this one on titles the parent pastes.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
use unicode_normalization::UnicodeNormalization;

/// A launcher with Dagen reports this capability.
pub const CALENDAR_CAPABILITY: &str = "calendar_v1";
/// Rules a phone may have.
pub const MAX_RULES: usize = 30;
/// A rule's text to match, in code points after [clean].
pub const PATTERN_MAX: usize = 120;
/// A rule's text to show, in code points after [clean].
pub const TEXT_MAX: usize = 40;
/// A title as the rules see it.
pub const TITLE_MAX: usize = 200;
/// A description as a rule sees it (and the phone shows it).
pub const DESCRIPTION_MAX: usize = 2000;
/// The reminders "N min before every event" offers.
pub const REMIND_MINUTES: [i64; 5] = [5, 10, 15, 30, 60];
/// Calendars a report may list.
pub const MAX_REPORTED_CALENDARS: usize = 50;

// ---- the grammar -------------------------------------------------------------------------------

/// Unicode's White_Space, spelled out so the launcher's copy is the same set.
fn is_space(c: char) -> bool {
    matches!(c,
        '\u{9}'..='\u{D}' | ' ' | '\u{85}' | '\u{A0}' | '\u{1680}' | '\u{2000}'..='\u{200A}'
        | '\u{2028}' | '\u{2029}' | '\u{202F}' | '\u{205F}' | '\u{3000}')
}

/// Removed outright: the other control characters, the bidi marks, overrides and isolates, and the invisible ones.
fn is_dropped(c: char) -> bool {
    matches!(c,
        '\u{0}'..='\u{1F}' | '\u{7F}'..='\u{9F}' | '\u{61C}' | '\u{200B}' | '\u{200E}' | '\u{200F}'
        | '\u{202A}'..='\u{202E}' | '\u{2060}'..='\u{2064}' | '\u{2066}'..='\u{2069}' | '\u{FEFF}')
}

/// Cleaning (§3.4): every whitespace run one space, the control and bidi characters out, trimmed, NFC, at most `max`
/// code points (trimmed again after the cut).
pub fn clean(raw: &str, max: usize) -> String {
    let mut spaced = String::with_capacity(raw.len());
    let mut space = false;
    for c in raw.chars() {
        if is_space(c) {
            space = true;
        } else if !is_dropped(c) {
            if space && !spaced.is_empty() {
                spaced.push(' ');
            }
            space = false;
            spaced.push(c);
        }
    }
    let normal: String = spaced.nfc().collect();
    crate::audit::clip(&normal, max).trim_end().to_string()
}

/// Simple lowercase per code point (Java's `Character.toLowerCase(int)`): Rust's when it is one char, `İ` by hand.
fn fold(c: char) -> char {
    if c == '\u{130}' {
        return 'i';
    }
    let mut lower = c.to_lowercase();
    match (lower.next(), lower.next()) {
        (Some(l), None) => l,
        _ => c,
    }
}

/// The text's non-space code points, folded, with where each starts and ends in `text` (bytes).
fn folded(text: &str) -> Vec<(char, usize, usize)> {
    text.char_indices()
        .filter(|(_, c)| !is_space(*c))
        .map(|(i, c)| (fold(c), i, i + c.len_utf8()))
        .collect()
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Field {
    Title,
    Description,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Match {
    Starts,
    Contains,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Replace,
    Prefix,
    Hide,
}

impl Field {
    pub fn parse(value: &str) -> Option<Field> {
        match value {
            "title" => Some(Field::Title),
            "description" => Some(Field::Description),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Field::Title => "title",
            Field::Description => "description",
        }
    }
}

impl Match {
    pub fn parse(value: &str) -> Option<Match> {
        match value {
            "starts" => Some(Match::Starts),
            "contains" => Some(Match::Contains),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Match::Starts => "starts",
            Match::Contains => "contains",
        }
    }
}

impl Action {
    pub fn parse(value: &str) -> Option<Action> {
        match value {
            "replace" => Some(Action::Replace),
            "prefix" => Some(Action::Prefix),
            "hide" => Some(Action::Hide),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Action::Replace => "replace",
            Action::Prefix => "prefix",
            Action::Hide => "hide",
        }
    }
}

/// One title rule, as the policy sends it (`match`/`text` on the wire).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Rule {
    pub id: i64,
    pub calendar: Option<String>,
    pub field: Field,
    #[serde(rename = "match")]
    pub match_kind: Match,
    pub pattern: String,
    pub action: Action,
    pub text: String,
}

/// Where `pattern` is in `text`, ignoring case and whitespace (§3.4): `(start, end)` in bytes of `text`, from the
/// first matched code point to the end of the last. Starts with: at the start only; Contains: the first occurrence.
pub fn find(text: &str, pattern: &str, how: Match) -> Option<(usize, usize)> {
    let hay = folded(text);
    let needle: Vec<char> = folded(pattern).into_iter().map(|(c, _, _)| c).collect();
    if needle.is_empty() || needle.len() > hay.len() {
        return None;
    }
    let at = |k: usize| {
        hay[k..k + needle.len()]
            .iter()
            .map(|h| h.0)
            .eq(needle.iter().copied())
    };
    let k = match how {
        Match::Starts => at(0).then_some(0)?,
        Match::Contains => (0..=hay.len() - needle.len()).find(|&k| at(k))?,
    };
    Some((hay[k].1, hay[k + needle.len() - 1].2))
}

/// The separators trimmed from the ends of a prefix rule's rest.
fn is_separator(c: char) -> bool {
    matches!(c, ' ' | '-' | '–' | '—' | ':' | '·' | '|' | ',' | ';' | '/')
}

/// What the phone shows after the rule that applies, or `None` when it hides the event.
fn act(rule: &Rule, title: &str, span: Option<(usize, usize)>) -> Option<String> {
    match rule.action {
        Action::Hide => None,
        Action::Replace => Some(rule.text.clone()),
        Action::Prefix => {
            let (start, end) = span.unwrap_or((0, 0));
            let rest = clean(
                &format!("{} {}", &title[..start], &title[end..]),
                usize::MAX,
            );
            let rest = rest.trim_matches(is_separator);
            Some(match (rule.text.is_empty(), rest.is_empty()) {
                (true, true) => title.to_string(),
                (true, false) => rest.to_string(),
                (false, true) => rule.text.clone(),
                (false, false) => format!("{} {rest}", rule.text),
            })
        }
    }
}

/// A rule the phone can apply: the description only hides, a replacement has a text, the pattern isn't empty after
/// cleaning. The launcher drops any other (and reports its id).
fn usable(rule: &Rule) -> bool {
    (rule.field == Field::Title || rule.action == Action::Hide)
        && !(rule.action == Action::Replace && clean(&rule.text, TEXT_MAX).is_empty())
        && !clean(&rule.pattern, PATTERN_MAX).is_empty()
}

/// What a rule did to one event.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    /// No rule applies: the cleaned title.
    Unchanged(String),
    /// Rule at this index (in the list) applied; `None` = hidden.
    Rule(usize, Option<String>),
}

impl Outcome {
    /// The title shown, `None` = hidden.
    pub fn shown(self) -> Option<String> {
        match self {
            Outcome::Unchanged(title) => Some(title),
            Outcome::Rule(_, shown) => shown,
        }
    }
}

/// The rules on one event (§3.4): the first usable rule whose calendar is `None` or `calendar` and whose field's
/// cleaned text matches wins.
pub fn apply(rules: &[Rule], calendar: Option<&str>, title: &str, description: &str) -> Outcome {
    let title = clean(title, TITLE_MAX);
    let description = clean(description, DESCRIPTION_MAX);
    for (index, rule) in rules.iter().enumerate() {
        if !usable(rule)
            || rule
                .calendar
                .as_deref()
                .is_some_and(|c| Some(c) != calendar)
        {
            continue;
        }
        let rule = Rule {
            pattern: clean(&rule.pattern, PATTERN_MAX),
            text: clean(&rule.text, TEXT_MAX),
            ..rule.clone()
        };
        let text = match rule.field {
            Field::Title => &title,
            Field::Description => &description,
        };
        if let Some(span) = find(text, &rule.pattern, rule.match_kind) {
            return Outcome::Rule(index, act(&rule, &title, Some(span)));
        }
    }
    Outcome::Unchanged(title)
}

// ---- the policy --------------------------------------------------------------------------------

/// `PolicyResponse.calendar` (§4.1), always sent.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct CalendarPolicy {
    /// Dagen on Home: off hides the tile, the glance line and the reminders (the rules stay).
    pub on_home: bool,
    /// The kid may add events: to the calendar with `writable_for_kid`, else the phone's own "Min kalender".
    pub kid_adds: bool,
    /// The lock's "Neste: …" line (A).
    pub glance_next: bool,
    /// `off` or `row`: the master switch over every calendar's reminder (C).
    pub lock_reminders: String,
    pub calendars: Vec<PolicyCalendar>,
    pub rules: Vec<Rule>,
}

#[derive(Serialize, Clone, Debug, PartialEq, Eq, sqlx::FromRow)]
pub struct PolicyCalendar {
    pub key: String,
    pub shown: bool,
    pub remind: String,
    pub remind_minutes: Option<i64>,
    pub writable_for_kid: bool,
}

#[derive(sqlx::FromRow)]
struct RuleRow {
    id: i64,
    calendar_key: Option<String>,
    field: String,
    match_kind: String,
    pattern: String,
    action: String,
    replacement: String,
}

impl RuleRow {
    fn rule(self) -> Option<Rule> {
        Some(Rule {
            id: self.id,
            calendar: self.calendar_key,
            field: Field::parse(&self.field)?,
            match_kind: Match::parse(&self.match_kind)?,
            pattern: self.pattern,
            action: Action::parse(&self.action)?,
            text: self.replacement,
        })
    }
}

/// The phone's rules in order (the stored ones that read; the CHECKs keep the rest out).
pub async fn rules(db: &SqlitePool, device_id: i64) -> Result<Vec<Rule>, sqlx::Error> {
    let rows: Vec<RuleRow> = sqlx::query_as(
        "SELECT id, calendar_key, field, match_kind, pattern, action, replacement \
         FROM device_calendar_rules WHERE device_id = ? ORDER BY sort_order, id",
    )
    .bind(device_id)
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().filter_map(RuleRow::rule).collect())
}

/// The phone's saved calendars, by key.
pub async fn calendars(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Vec<PolicyCalendar>, sqlx::Error> {
    sqlx::query_as(
        "SELECT calendar_key AS key, shown, remind, remind_minutes, writable_for_kid \
         FROM device_calendars WHERE device_id = ? ORDER BY calendar_key",
    )
    .bind(device_id)
    .fetch_all(db)
    .await
}

/// The switches of a phone without a policy row: the columns' defaults (on Home, the kid may add events, nothing on
/// the lock).
pub fn default_switches() -> (bool, bool, bool, String) {
    (true, true, false, "off".to_string())
}

/// The phone-wide switches: (on_home, kid_adds, glance_next, lock_reminders).
pub async fn switches(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Option<(bool, bool, bool, String)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT calendar_on_home, calendar_kid_adds, calendar_glance_next, calendar_lock_reminders \
         FROM device_policy WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await
}

/// The policy's `calendar` for one phone. A DB error fails the policy (500, the phone keeps its cache): a missing
/// hide rule would show what the parent hid.
pub async fn policy_calendar(
    db: &SqlitePool,
    device_id: i64,
) -> Result<CalendarPolicy, sqlx::Error> {
    let (on_home, kid_adds, glance_next, lock_reminders) = switches(db, device_id)
        .await?
        .unwrap_or_else(default_switches);
    Ok(CalendarPolicy {
        on_home,
        kid_adds,
        glance_next,
        lock_reminders,
        calendars: calendars(db, device_id).await?,
        rules: rules(db, device_id).await?,
    })
}

// ---- the phone's report ------------------------------------------------------------------------

/// One calendar of the phone's report (§4.2), as stored.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ReportedCalendar {
    pub key: String,
    pub account_type: String,
    pub name: String,
    pub source: Option<String>,
    pub access_level: i64,
}

/// The phone's `calendar` report, as stored.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq, Default)]
pub struct CalendarReport {
    pub readable: bool,
    pub calendars: Vec<ReportedCalendar>,
    pub rules_skipped: Vec<i64>,
}

/// A calendar key: exactly 16 lowercase hex digits.
pub fn valid_key(key: &str) -> bool {
    key.len() == 16 && key.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// A feed's host as the phone names it: a hostname of `[a-z0-9.-]`, at most 253 (lowercased).
fn hostname(value: &str) -> Option<String> {
    let host = value.to_ascii_lowercase();
    (!host.is_empty()
        && host.len() <= 253
        && host
            .bytes()
            .all(|b| matches!(b, b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-')))
    .then_some(host)
}

/// The keys of one reported calendar, through `device_api::known_fields` (security review L10).
pub const CALENDAR_ROW_FIELDS: &[(&str, crate::handlers::device_api::Field)] = &[
    ("key", crate::handlers::device_api::Field::Word(16)),
    (
        "account_type",
        crate::handlers::device_api::Field::Word(200),
    ),
    ("name", crate::handlers::device_api::Field::Text(100)),
    ("source", crate::handlers::device_api::Field::Word(253)),
    ("access_level", crate::handlers::device_api::Field::Int),
];

/// The phone's `calendar` through its known keys (§4.2): `readable` a bool (else nothing is kept); at most
/// [MAX_REPORTED_CALENDARS] calendars, each with a valid key (once) and an account type, its name cut to 100, its
/// source a hostname or null, its access level an int (else 0); `rules_skipped` at most [MAX_RULES] ids, only ids in
/// `rule_ids`. Anything else is dropped.
pub fn sanitize_report(
    value: &serde_json::Value,
    rule_ids: &HashSet<i64>,
) -> Option<CalendarReport> {
    let readable = value.get("readable")?.as_bool()?;
    let mut seen = HashSet::new();
    let calendars = value
        .get("calendars")
        .and_then(|c| c.as_array())
        .map(Vec::as_slice)
        .unwrap_or_default()
        .iter()
        .filter_map(|row| {
            let kept: serde_json::Value = serde_json::from_str(
                &crate::handlers::device_api::known_fields(row, CALENDAR_ROW_FIELDS)?,
            )
            .ok()?;
            let key = kept.get("key")?.as_str().filter(|k| valid_key(k))?;
            if !seen.insert(key.to_string()) {
                return None;
            }
            Some(ReportedCalendar {
                key: key.to_string(),
                account_type: clean(kept.get("account_type")?.as_str()?, 200),
                name: kept
                    .get("name")
                    .and_then(|n| n.as_str())
                    .map(|n| clean(n, 100))
                    .unwrap_or_default(),
                source: kept
                    .get("source")
                    .and_then(|s| s.as_str())
                    .and_then(hostname),
                access_level: kept
                    .get("access_level")
                    .and_then(|a| a.as_i64())
                    .unwrap_or(0),
            })
        })
        .take(MAX_REPORTED_CALENDARS)
        .collect();
    let mut skipped = HashSet::new();
    let rules_skipped: Vec<i64> = value
        .get("rules_skipped")
        .and_then(|r| r.as_array())
        .map(Vec::as_slice)
        .unwrap_or_default()
        .iter()
        .filter_map(|id| {
            id.as_i64()
                .filter(|id| rule_ids.contains(id) && skipped.insert(*id))
        })
        .take(MAX_RULES)
        .collect();
    Some(CalendarReport {
        readable,
        calendars,
        rules_skipped,
    })
}

/// The phone's last report, `None` before its first (or for anything [sanitize_report] didn't write).
pub async fn last_report(
    db: &SqlitePool,
    device_id: i64,
) -> Result<Option<CalendarReport>, sqlx::Error> {
    let stored: Option<Option<String>> =
        sqlx::query_scalar("SELECT calendar_report FROM devices WHERE id = ?")
            .bind(device_id)
            .fetch_optional(db)
            .await?;
    Ok(stored
        .flatten()
        .and_then(|json| serde_json::from_str(&json).ok()))
}

/// Stores a report's `calendar` (status intake): through [sanitize_report] with the phone's rule ids. A report
/// without it (an older launcher) leaves the last one. Never fails the report: an error is logged.
pub async fn store_report(db: &SqlitePool, device_id: i64, value: Option<&serde_json::Value>) {
    let Some(value) = value else { return };
    let result = async {
        let ids: Vec<i64> =
            sqlx::query_scalar("SELECT id FROM device_calendar_rules WHERE device_id = ?")
                .bind(device_id)
                .fetch_all(db)
                .await?;
        let Some(report) = sanitize_report(value, &ids.into_iter().collect()) else {
            return Ok(());
        };
        sqlx::query("UPDATE devices SET calendar_report = ? WHERE id = ?")
            .bind(serde_json::to_string(&report).expect("a report serializes"))
            .bind(device_id)
            .execute(db)
            .await
            .map(|_| ())
    }
    .await;
    if let Err(err) = result {
        tracing::error!(device_id, %err, "couldn't store the phone's calendar report");
    }
}

/// The app a reported calendar comes from, by its account type.
pub fn app_of(account_type: &str) -> &str {
    match account_type {
        "at.bitfire.icsdroid" => "ICSx5",
        "bitfire.at.davdroid" | "at.bitfire.davdroid" => "DAVx5",
        "LOCAL" => "Phone",
        "com.google" => "Google",
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Deserialize)]
    struct Vectors {
        titles: Vec<Vector>,
    }

    #[derive(Deserialize)]
    struct Vector {
        rules: Vec<serde_json::Value>,
        calendar: Option<String>,
        title: String,
        #[serde(default)]
        description: String,
        shown: Option<String>,
    }

    /// The shared vectors (§3.4): a rule the policy would drop (an unknown field, match or action) is left out, as
    /// on the phone.
    #[test]
    fn the_title_rules_vectors() {
        let text = std::fs::read_to_string("testdata/calendar_title_rules.json").unwrap();
        let vectors: Vectors = serde_json::from_str(&text).unwrap();
        assert!(vectors.titles.len() >= 20);
        for (i, v) in vectors.titles.iter().enumerate() {
            let rules: Vec<Rule> = v
                .rules
                .iter()
                .filter_map(|r| serde_json::from_value(r.clone()).ok())
                .collect();
            let shown = apply(&rules, v.calendar.as_deref(), &v.title, &v.description).shown();
            assert_eq!(shown, v.shown, "row {i}: {:?}", v.title);
        }
    }

    #[test]
    fn the_vectors_are_identical_to_the_launchers_copy() {
        let ours = std::fs::read_to_string("testdata/calendar_title_rules.json").unwrap();
        let theirs =
            std::fs::read_to_string("../launcher/app/src/test/resources/calendar_title_rules.json")
                .unwrap();
        assert_eq!(ours, theirs);
        let ours = std::fs::read_to_string("testdata/calendar_status_report.json").unwrap();
        let theirs = std::fs::read_to_string(
            "../launcher/app/src/test/resources/calendar_status_report.json",
        )
        .unwrap();
        assert_eq!(ours, theirs);
    }

    #[test]
    fn cleaning_and_finding() {
        assert_eq!(
            clean(" a\u{A0}\u{2009}b\tc\u{202E}d\u{200B}e  ", 200),
            "a b cde"
        );
        assert_eq!(clean("a\u{1}b", 200), "ab");
        // NFD å becomes NFC; the cut counts code points and trims.
        assert_eq!(clean("a\u{30A}", 200), "å");
        assert_eq!(clean("abc def", 4), "abc");
        assert_eq!(
            find("Gruppe 2- skoleåret", "gruppe2-", Match::Starts),
            Some((0, 9))
        );
        assert_eq!(
            find("Det kuleste fra R omerriket", "Romerriket", Match::Contains),
            Some((16, 27))
        );
        assert_eq!(find("abc", "abcd", Match::Contains), None);
        assert_eq!(find("abc", " ", Match::Contains), None);
        assert_eq!(find("xabc", "abc", Match::Starts), None);
    }

    #[test]
    fn the_report_is_sanitized() {
        let ids: HashSet<i64> = [7, 9].into();
        let report = sanitize_report(
            &serde_json::json!({
                "readable": true,
                "calendars": [
                    {"key": "9f3a61c2d84be705", "account_type": "at.bitfire.icsdroid", "name": "Skolen",
                     "source": "Skole.Kommune.no", "access_level": 200, "extra": 1},
                    {"key": "9f3a61c2d84be705", "account_type": "LOCAL", "name": "Twin", "access_level": 700},
                    {"key": "9F3A61C2D84BE705", "account_type": "LOCAL", "access_level": 700},
                    {"key": "abc", "account_type": "LOCAL"},
                    {"key": "c04e7b19a2f6d853", "name": "no type"},
                    {"key": "51d8e0aa3b7c9462", "account_type": "x", "name": "n\u{1}", "source": "host:8080",
                     "access_level": "high"},
                ],
                "rules_skipped": [7, 8, 7, "9"],
                "events": [{"title": "never"}],
            }),
            &ids,
        )
        .unwrap();
        assert!(report.readable);
        assert_eq!(report.calendars.len(), 2);
        assert_eq!(
            report.calendars[0].source.as_deref(),
            Some("skole.kommune.no")
        );
        assert_eq!(report.calendars[1].name, "n");
        assert_eq!(report.calendars[1].source, None);
        assert_eq!(report.calendars[1].access_level, 0);
        assert_eq!(report.rules_skipped, [7]);
        assert_eq!(
            sanitize_report(&serde_json::json!({"readable": "yes"}), &ids),
            None
        );
        let many: Vec<serde_json::Value> = (0..60)
            .map(|i| serde_json::json!({"key": format!("{i:016x}"), "account_type": "LOCAL"}))
            .collect();
        let capped = sanitize_report(
            &serde_json::json!({"readable": true, "calendars": many}),
            &ids,
        )
        .unwrap();
        assert_eq!(capped.calendars.len(), MAX_REPORTED_CALENDARS);
    }
}
