//! Design 76: the launcher's certificate check, switched on per phone by this server
//! (`docs/design/76-server-issuer-pin.md` D2; migration 0129).
//!
//! Every phone with [CAPABILITY] checks the chain Android validated for this server against its built-in set (Let's
//! Encrypt's roots, ZeroSSL's operator Sectigo's - the launcher's alone: the server never judges a chain) and reports
//! what it sees in every status report: `server_tls: {enforcing, seen?: {in_set, name, pin, network, at_ms},
//! outside: [{kind, host, name, pin, network, at_ms}]}` (`testdata/server_tls_report.json`, [sanitize]). Per phone the
//! server is in one [State]:
//! - `watching` (no row, the start): the policy carries `server_tls: {}` - no `enforce`, so a phone keeps its flag
//!   (R-1). In-set sightings spanning [WINDOW] of receive time (never the phone's `at_ms`), on [WINDOW_DAYS] days, on
//!   Wi-Fi and mobile data when the phone has reported mobile at all (R-3), switch it `on` ([next_state]); an outside
//!   sighting stops that (R-2: it doesn't restart the window) - the device page shows it and offers "Turn the check on
//!   now". A phone reporting `enforcing: true` stored that only from a pinned `true`: the server adopts `on`.
//! - `on`: `{"enforce": true}`. Sticky: an outside sighting never switches it off (an attacker could cause one).
//! - `off`: `{"enforce": false}`, only by the parent ([by_parent]), with its own security event, a push to every
//!   parent and an SSE nudge (R-4) - sticky until "Turn it on again" (`watching`) or "Turn the check on now" (`on`).
//!
//! A new outside sighting is pushed at most once a day per phone (`watching` and `on`): a split horizon or an attack,
//! the parent hears of both. A launcher without [CAPABILITY] gets no `server_tls`.

use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use crate::AppState;
use crate::display_time::Zone;
use crate::i18n::{t, t_params};

/// The launcher's capability (`SERVER_PIN_CAPABILITY`): it reads the policy's `server_tls` and reports its own.
pub const CAPABILITY: &str = "server_pin_v1";
/// In-set sightings spanning this much receive time switch a watched phone on.
pub const WINDOW: Duration = Duration::hours(72);
/// ... on at least this many distinct days (server time, UTC dates).
pub const WINDOW_DAYS: usize = 3;
/// The outside rows kept per phone, as the phone keeps them.
pub const OUTSIDE_MAX: usize = 8;
/// A reported name is cut to this many characters, a pin is at most this long (44: SHA-256 in base64), a host this.
pub const NAME_MAX: usize = 64;
pub const PIN_MAX: usize = 44;
pub const HOST_MAX: usize = 253;
/// The days kept of a window (only [WINDOW_DAYS] count).
const DAYS_KEPT: usize = 7;

/// Where a phone's check is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    Watching,
    On,
    Off,
}

impl State {
    pub fn wire(self) -> &'static str {
        match self {
            State::Watching => "watching",
            State::On => "on",
            State::Off => "off",
        }
    }

    pub fn of(wire: &str) -> State {
        match wire {
            "on" => State::On,
            "off" => State::Off,
            _ => State::Watching,
        }
    }
}

/// An outside sighting the phone reported (`refused` with its check on, `seen` with it off), with the receive time.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Outside {
    pub kind: String,
    pub host: String,
    pub name: String,
    pub pin: String,
    pub network: String,
    pub at_ms: i64,
    /// When this server first received it (RFC 3339); empty in a report.
    #[serde(default)]
    pub received_at: String,
}

impl Outside {
    /// The same row as the phone sent it (the receive time aside).
    fn same(&self, other: &Outside) -> bool {
        self.kind == other.kind
            && self.host == other.host
            && self.name == other.name
            && self.pin == other.pin
            && self.network == other.network
            && self.at_ms == other.at_ms
    }
}

/// The report's `seen`: this sync's policy connection as the phone saw it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Seen {
    pub in_set: bool,
    pub name: String,
    pub pin: String,
    pub network: String,
    pub at_ms: i64,
}

/// A report's `server_tls`, within limits.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub enforcing: Option<bool>,
    pub seen: Option<Seen>,
    pub outside: Vec<Outside>,
}

fn network(value: Option<&serde_json::Value>) -> String {
    match value.and_then(|v| v.as_str()) {
        Some(n @ ("wifi" | "mobile")) => n.to_string(),
        _ => "other".to_string(),
    }
}

fn pin(value: Option<&serde_json::Value>) -> Option<String> {
    let pin = value?.as_str()?;
    (!pin.is_empty()
        && pin.len() <= PIN_MAX
        && pin
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'='))
    .then(|| pin.to_string())
}

fn text(value: Option<&serde_json::Value>, max: usize) -> String {
    crate::audit::clip(value.and_then(|v| v.as_str()).unwrap_or(""), max).to_string()
}

/// The report's `server_tls` within limits (8 rows, names 64 characters, pins 44 characters of base64): `None` for
/// anything but an object. A row without a valid pin or kind is dropped, never the report.
pub fn sanitize(value: &serde_json::Value) -> Option<Report> {
    let object = value.as_object()?;
    let seen = object.get("seen").and_then(|s| {
        Some(Seen {
            in_set: s.get("in_set")?.as_bool()?,
            name: text(s.get("name"), NAME_MAX),
            pin: pin(s.get("pin"))?,
            network: network(s.get("network")),
            at_ms: s.get("at_ms")?.as_i64()?,
        })
    });
    let rows: Vec<Outside> = object
        .get("outside")
        .and_then(|o| o.as_array())
        .map(|rows| {
            rows.iter()
                .filter_map(|r| {
                    let kind = r.get("kind")?.as_str()?;
                    if kind != "seen" && kind != "refused" {
                        return None;
                    }
                    Some(Outside {
                        kind: kind.to_string(),
                        host: text(r.get("host"), HOST_MAX),
                        name: text(r.get("name"), NAME_MAX),
                        pin: pin(r.get("pin"))?,
                        network: network(r.get("network")),
                        at_ms: r.get("at_ms")?.as_i64()?,
                        received_at: String::new(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let skip = rows.len().saturating_sub(OUTSIDE_MAX);
    Some(Report {
        enforcing: object.get("enforcing").and_then(|e| e.as_bool()),
        seen,
        outside: rows.into_iter().skip(skip).collect(),
    })
}

/// A phone's stored state ([load], [store]); [Default] is `watching` from the start.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    pub state: State,
    pub window_from: Option<DateTime<Utc>>,
    pub window_days: Vec<String>,
    pub window_networks: Vec<String>,
    pub mobile_seen: bool,
    pub stopped_at: Option<DateTime<Utc>>,
    pub outside: Vec<Outside>,
    pub outside_pushed_on: Option<String>,
    pub phone_enforcing: Option<bool>,
    pub changed_at: Option<DateTime<Utc>>,
    pub changed_by: Option<String>,
}

impl Default for Row {
    fn default() -> Row {
        Row {
            state: State::Watching,
            window_from: None,
            window_days: Vec::new(),
            window_networks: Vec::new(),
            mobile_seen: false,
            stopped_at: None,
            outside: Vec::new(),
            outside_pushed_on: None,
            phone_enforcing: None,
            changed_at: None,
            changed_by: None,
        }
    }
}

/// What a report changed: the new row, and the outside row to push about (one a day).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Next {
    pub row: Row,
    pub push: Option<Outside>,
}

/// Whether a watched phone's window is complete at `now` (R-3).
pub fn window_complete(row: &Row, now: DateTime<Utc>) -> bool {
    let Some(from) = row.window_from else {
        return false;
    };
    let networks = |n: &str| row.window_networks.iter().any(|w| w == n);
    now - from >= WINDOW
        && row.window_days.len() >= WINDOW_DAYS
        && (!row.mobile_seen || (networks("wifi") && networks("mobile")))
}

/// The state after a report received at `now` (receive time only - never the phone's `at_ms`).
pub fn next_state(row: &Row, report: &Report, now: DateTime<Utc>) -> Next {
    let mut next = row.clone();
    let stamp = instant(now);
    if report.enforcing.is_some() {
        next.phone_enforcing = report.enforcing;
    }
    if report.seen.as_ref().is_some_and(|s| s.network == "mobile")
        || report.outside.iter().any(|o| o.network == "mobile")
    {
        next.mobile_seen = true;
    }
    let new: Vec<Outside> = report
        .outside
        .iter()
        .filter(|o| !row.outside.iter().any(|kept| kept.same(o)))
        .map(|o| Outside {
            received_at: stamp.clone(),
            ..o.clone()
        })
        .collect();
    next.outside.extend(new.iter().cloned());
    let skip = next.outside.len().saturating_sub(OUTSIDE_MAX);
    next.outside.drain(..skip);
    let outside_now = !new.is_empty() || report.seen.as_ref().is_some_and(|s| !s.in_set);

    if row.state == State::Watching {
        if report.enforcing == Some(true) {
            // The phone stored `true` only from a policy over a connection that passed its pins.
            next.state = State::On;
            next.changed_at = Some(now);
            next.changed_by = None;
        } else {
            if outside_now && next.stopped_at.is_none() {
                next.stopped_at = Some(now);
            }
            if let Some(seen) = report.seen.as_ref().filter(|s| s.in_set) {
                next.window_from.get_or_insert(now);
                let day = now.date_naive().to_string();
                if !next.window_days.contains(&day) && next.window_days.len() < DAYS_KEPT {
                    next.window_days.push(day);
                }
                if seen.network != "other" && !next.window_networks.contains(&seen.network) {
                    next.window_networks.push(seen.network.clone());
                }
            }
            if next.stopped_at.is_none() && window_complete(&next, now) {
                next.state = State::On;
                next.changed_at = Some(now);
                next.changed_by = None;
            }
        }
    }

    // A new outside row: pushed once a day per phone, never while the parent has the check off.
    let today = now.date_naive().to_string();
    let push =
        if row.state != State::Off && next.outside_pushed_on.as_deref() != Some(today.as_str()) {
            new.last().cloned()
        } else {
            None
        };
    if push.is_some() {
        next.outside_pushed_on = Some(today);
    }
    Next { row: next, push }
}

/// A parent's one-shot on the device page (R-4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    /// "Turn off the certificate check".
    Off,
    /// "Turn it on again": back to `watching`, a new window.
    Watch,
    /// "Turn the check on now".
    OnNow,
}

impl Action {
    /// Its security event (labelled `security.event.<id>`): the way off, or back on.
    pub fn event(self) -> &'static str {
        match self {
            Action::Off => "server_tls_off",
            Action::Watch | Action::OnNow => "server_tls_on",
        }
    }

    /// Its security line's sentence, naming the phone.
    pub fn sentence(self) -> &'static str {
        match self {
            Action::Off => "security.detail.server_tls.off",
            Action::Watch => "security.detail.server_tls.watch",
            Action::OnNow => "security.detail.server_tls.on",
        }
    }

    /// The device page's flash after it ([card]'s `notice`).
    pub fn notice(self) -> &'static str {
        match self {
            Action::Off => "server_tls_off",
            Action::Watch => "server_tls_watch",
            Action::OnNow => "server_tls_on",
        }
    }

    pub fn of(wire: &str) -> Option<Action> {
        Some(match wire {
            "off" => Action::Off,
            "watch" => Action::Watch,
            "on" => Action::OnNow,
            _ => return None,
        })
    }
}

/// The row after the parent's `action` (named `parent`) at `now`.
pub fn by_parent(row: &Row, action: Action, parent: &str, now: DateTime<Utc>) -> Row {
    let mut next = row.clone();
    next.changed_at = Some(now);
    next.changed_by = Some(crate::audit::clip(parent, 100).to_string());
    match action {
        Action::Off => next.state = State::Off,
        Action::OnNow => {
            next.state = State::On;
            next.stopped_at = None;
        }
        Action::Watch => {
            next.state = State::Watching;
            next.window_from = None;
            next.window_days.clear();
            next.window_networks.clear();
            next.stopped_at = None;
        }
    }
    next
}

/// The policy's `server_tls` for `state`: `{}` while watching (no `enforce`: keep), `true` on, `false` off.
pub fn policy_value(state: State) -> serde_json::Value {
    match state {
        State::Watching => serde_json::json!({}),
        State::On => serde_json::json!({"enforce": true}),
        State::Off => serde_json::json!({"enforce": false}),
    }
}

fn instant(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn parse(t: Option<String>) -> Option<DateTime<Utc>> {
    t.as_deref().and_then(crate::display_time::parse_db_utc)
}

type Stored = (
    String,
    Option<String>,
    String,
    String,
    bool,
    Option<String>,
    String,
    Option<String>,
    Option<bool>,
    Option<String>,
    Option<String>,
);

/// The phone's row ([Row::default] without one).
pub async fn load<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
) -> Result<Row, sqlx::Error> {
    let stored: Option<Stored> = sqlx::query_as(
        "SELECT state, window_from, window_days, window_networks, mobile_seen, stopped_at, outside_json, \
         outside_pushed_on, phone_enforcing, changed_at, changed_by FROM device_server_tls WHERE device_id = ?",
    )
    .bind(device_id)
    .fetch_optional(db)
    .await?;
    let Some((
        state,
        window_from,
        days,
        networks,
        mobile_seen,
        stopped_at,
        outside,
        outside_pushed_on,
        phone_enforcing,
        changed_at,
        changed_by,
    )) = stored
    else {
        return Ok(Row::default());
    };
    Ok(Row {
        state: State::of(&state),
        window_from: parse(window_from),
        window_days: serde_json::from_str(&days).unwrap_or_default(),
        window_networks: serde_json::from_str(&networks).unwrap_or_default(),
        mobile_seen,
        stopped_at: parse(stopped_at),
        outside: serde_json::from_str(&outside).unwrap_or_default(),
        outside_pushed_on,
        phone_enforcing,
        changed_at: parse(changed_at),
        changed_by,
    })
}

/// Writes the phone's row.
pub async fn store<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
    row: &Row,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO device_server_tls (device_id, state, window_from, window_days, window_networks, mobile_seen, \
         stopped_at, outside_json, outside_pushed_on, phone_enforcing, changed_at, changed_by) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT(device_id) DO UPDATE SET state = excluded.state, window_from = excluded.window_from, \
         window_days = excluded.window_days, window_networks = excluded.window_networks, \
         mobile_seen = excluded.mobile_seen, stopped_at = excluded.stopped_at, outside_json = excluded.outside_json, \
         outside_pushed_on = excluded.outside_pushed_on, phone_enforcing = excluded.phone_enforcing, \
         changed_at = excluded.changed_at, changed_by = excluded.changed_by",
    )
    .bind(device_id)
    .bind(row.state.wire())
    .bind(row.window_from.map(instant))
    .bind(serde_json::to_string(&row.window_days).expect("strings serialize"))
    .bind(serde_json::to_string(&row.window_networks).expect("strings serialize"))
    .bind(row.mobile_seen)
    .bind(row.stopped_at.map(instant))
    .bind(serde_json::to_string(&row.outside).expect("strings serialize"))
    .bind(&row.outside_pushed_on)
    .bind(row.phone_enforcing)
    .bind(row.changed_at.map(instant))
    .bind(&row.changed_by)
    .execute(db)
    .await?;
    Ok(())
}

/// The policy's `server_tls` for a phone whose last report has `capabilities`: `None` (absent) without
/// [CAPABILITY]. A read error fails the policy (the phone keeps its cache), never a made-up state.
pub async fn policy(
    db: &SqlitePool,
    device_id: i64,
    capabilities: &[String],
) -> Result<Option<serde_json::Value>, sqlx::Error> {
    if !capabilities.iter().any(|c| c == CAPABILITY) {
        return Ok(None);
    }
    Ok(Some(policy_value(load(db, device_id).await?.state)))
}

/// A status report's `server_tls` (raw), received now: the new state stored in one transaction, then the outside
/// push (once a day). A report without it (an older launcher) changes nothing; a failure is logged.
pub async fn intake(state: &AppState, device_id: i64, raw: Option<&serde_json::Value>) {
    let Some(report) = raw.and_then(sanitize) else {
        return;
    };
    let now = Utc::now();
    let result = async {
        let mut tx = state.db.begin().await?;
        let row = load(&mut *tx, device_id).await?;
        let next = next_state(&row, &report, now);
        if next.row != row {
            store(&mut *tx, device_id, &next.row).await?;
        }
        tx.commit().await?;
        Ok::<_, sqlx::Error>((row.state, next))
    };
    match result.await {
        Ok((before, next)) => {
            if before != next.row.state {
                tracing::info!(
                    device_id,
                    from = before.wire(),
                    to = next.row.state.wire(),
                    "the certificate check moved"
                );
            }
            if let Some(outside) = &next.push
                && let Some(phone) = crate::parent_events::Phone::load(&state.db, device_id).await
            {
                crate::parent_events::raise_logged(
                    state,
                    &crate::parent_events::server_tls_seen(&phone, outside, now),
                )
                .await;
            }
        }
        Err(err) => {
            tracing::error!(device_id, %err, "can't store the certificate check's report")
        }
    }
}

/// The device page's group (Advanced › Certificate check): the state as one line, the last refusal as an alarm, the
/// buttons that apply.
pub struct Card {
    pub line: String,
    pub alarm: Option<String>,
    /// "Turn off the certificate check" (watching or on).
    pub can_turn_off: bool,
    /// "Turn it on again" (off).
    pub can_watch: bool,
    /// "Turn the check on now" (watching after an outside sighting, or off).
    pub can_turn_on: bool,
    /// The flash after a button: the text, and whether it is a refusal.
    pub notice: Option<(&'static str, bool)>,
}

fn network_word(zone: &Zone, network: &str) -> &'static str {
    t(
        zone.lang(),
        match network {
            "wifi" => "server_tls.network.wifi",
            "mobile" => "server_tls.network.mobile",
            _ => "server_tls.network.elsewhere",
        },
    )
}

/// The time an outside row stands for: its receive time (the phone's clock may be off), else now.
fn received(row: &Outside, now: DateTime<Utc>) -> DateTime<Utc> {
    crate::display_time::parse_db_utc(&row.received_at).unwrap_or(now)
}

/// The group for `row`, in `zone`'s language and time.
pub fn card(row: &Row, zone: &Zone, now: DateTime<Utc>, notice: Option<&str>) -> Card {
    let lang = zone.lang();
    let line = match row.state {
        State::On => t(lang, "server_tls.on").to_string(),
        State::Off => t_params(
            lang,
            "server_tls.off_since",
            &[
                (
                    "since",
                    &row.changed_at
                        .map(|at| zone.since(at, now))
                        .unwrap_or_default(),
                ),
                (
                    "parent",
                    row.changed_by
                        .as_deref()
                        .unwrap_or(t(lang, "server_tls.a_parent")),
                ),
            ],
        ),
        State::Watching => match (row.stopped_at, row.outside.last()) {
            (Some(_), Some(last)) => t_params(
                lang,
                "server_tls.stopped",
                &[
                    ("issuer", &last.name),
                    ("network", network_word(zone, &last.network)),
                    ("time", &zone.since(received(last, now), now)),
                ],
            ),
            _ => t_params(
                lang,
                "server_tls.watching",
                &[(
                    "from",
                    &zone.until(row.window_from.unwrap_or(now) + WINDOW, now),
                )],
            ),
        },
    };
    let alarm = row
        .outside
        .iter()
        .rev()
        .find(|o| o.kind == "refused")
        .filter(|_| row.state != State::Off)
        .map(|o| {
            t_params(
                lang,
                "server_tls.refused",
                &[
                    ("issuer", &o.name),
                    ("network", network_word(zone, &o.network)),
                    ("time", &zone.since(received(o, now), now)),
                ],
            )
        });
    Card {
        line,
        alarm,
        can_turn_off: row.state != State::Off,
        can_watch: row.state == State::Off,
        can_turn_on: row.state == State::Off
            || (row.state == State::Watching && row.stopped_at.is_some()),
        notice: notice.and_then(|code| {
            Some(match code {
                "server_tls_off" => (t(lang, "server_tls.notice.off"), false),
                "server_tls_watch" => (t(lang, "server_tls.notice.watch"), false),
                "server_tls_on" => (t(lang, "server_tls.notice.on"), false),
                "server_tls_unconfirmed" => (t(lang, "devices.access.unconfirmed"), true),
                _ => return None,
            })
        }),
    }
}

/// Whether the phone's last report has [CAPABILITY] (the group shows only then).
pub fn capable(capabilities_json: Option<&str>) -> bool {
    capabilities_json
        .and_then(|json| serde_json::from_str::<Vec<String>>(json).ok())
        .is_some_and(|caps| caps.iter().any(|c| c == CAPABILITY))
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn at(day: u32, hour: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 11, day, hour, 0, 0).unwrap()
    }

    fn seen(in_set: bool, network: &str) -> Report {
        Report {
            enforcing: Some(false),
            seen: Some(Seen {
                in_set,
                name: if in_set {
                    "ISRG Root X1"
                } else {
                    "emSign Root CA - G1"
                }
                .into(),
                pin: "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=".into(),
                network: network.into(),
                at_ms: 1,
            }),
            outside: Vec::new(),
        }
    }

    fn outside(kind: &str, network: &str, at_ms: i64) -> Outside {
        Outside {
            kind: kind.into(),
            host: "vibb.example.org".into(),
            name: "emSign Root CA - G1".into(),
            pin: "N2oacIKlk9zMINVh0Rnpq40w8RzDIdCjf6QfDfKE4Bw=".into(),
            network: network.into(),
            at_ms,
            received_at: String::new(),
        }
    }

    /// Reports at each `(day, hour, network)`, from `row`.
    fn run(mut row: Row, reports: &[(u32, u32, &str)]) -> Row {
        for (day, hour, network) in reports {
            row = next_state(&row, &seen(true, network), at(*day, *hour)).row;
        }
        row
    }

    #[test]
    fn the_window_is_72_hours_of_receive_time_on_three_days() {
        // Wi-Fi only: 72 hours on 4 days switch on; 71 hours don't.
        let row = run(
            Row::default(),
            &[(1, 12, "wifi"), (2, 12, "wifi"), (3, 12, "wifi")],
        );
        assert_eq!(row.state, State::Watching);
        assert_eq!(
            next_state(&row, &seen(true, "wifi"), at(4, 11)).row.state,
            State::Watching
        );
        let on = next_state(&row, &seen(true, "wifi"), at(4, 12)).row;
        assert_eq!(on.state, State::On);
        assert_eq!(on.changed_by, None);
        // 72 hours, but on two days only (a report at 00:00 and one at 23:00 three days later on two days isn't
        // possible; two reports far apart are): not on until a third day has a sighting.
        let two = run(Row::default(), &[(1, 0, "wifi"), (5, 0, "wifi")]);
        assert_eq!(two.state, State::Watching);
        assert_eq!(two.window_days.len(), 2);
        assert_eq!(run(two, &[(5, 1, "wifi"), (6, 0, "wifi")]).state, State::On);
        // The phone's own at_ms counts for nothing: one report far in the "future" is one sighting now.
        let mut early = seen(true, "wifi");
        early.seen.as_mut().unwrap().at_ms = at(30, 0).timestamp_millis();
        assert_eq!(
            next_state(&Row::default(), &early, at(1, 12)).row.state,
            State::Watching
        );
    }

    #[test]
    fn a_phone_that_reported_mobile_needs_both_networks() {
        let wifi_days = [
            (1, 12, "wifi"),
            (2, 12, "wifi"),
            (3, 12, "wifi"),
            (4, 12, "wifi"),
        ];
        let mobile_once = Row {
            mobile_seen: true,
            ..Row::default()
        };
        assert_eq!(run(mobile_once.clone(), &wifi_days).state, State::Watching);
        assert_eq!(
            run(run(mobile_once, &wifi_days), &[(4, 13, "mobile")]).state,
            State::On
        );
        // A sighting on mobile marks it.
        let row = run(Row::default(), &[(1, 12, "mobile")]);
        assert!(row.mobile_seen);
        assert_eq!(row.window_networks, ["mobile"]);
    }

    #[test]
    fn an_outside_sighting_stops_the_switch_on_and_is_pushed_once_a_day() {
        let row = run(Row::default(), &[(1, 12, "wifi"), (2, 12, "wifi")]);
        let mut report = seen(true, "wifi");
        report.outside = vec![outside("seen", "mobile", 5)];
        let next = next_state(&row, &report, at(2, 13));
        assert_eq!(next.row.stopped_at, Some(at(2, 13)));
        assert_eq!(next.push.as_ref().map(|o| o.at_ms), Some(5));
        assert_eq!(next.row.outside.len(), 1);
        assert_eq!(next.row.outside[0].received_at, instant(at(2, 13)));
        // The same row again: not new, no push; another one the same day: kept, no push; the next day: pushed.
        let again = next_state(&next.row, &report, at(2, 14));
        assert_eq!(again.push, None);
        assert_eq!(again.row.outside.len(), 1);
        report.outside.push(outside("seen", "wifi", 6));
        let same_day = next_state(&again.row, &report, at(2, 15));
        assert_eq!(same_day.push, None);
        assert_eq!(same_day.row.outside.len(), 2);
        report.outside.push(outside("refused", "wifi", 7));
        let next_day = next_state(&same_day.row, &report, at(3, 9));
        assert_eq!(next_day.push.map(|o| o.kind), Some("refused".to_string()));
        // Stopped: never switched on by itself, the window doesn't restart either.
        let later = run(
            next_day.row.clone(),
            &[(4, 12, "wifi"), (5, 12, "mobile"), (6, 12, "wifi")],
        );
        assert_eq!(later.state, State::Watching);
        assert_eq!(later.window_from, Some(at(1, 12)));
        // A chain outside the set in `seen` alone stops it too.
        let stopped = next_state(&Row::default(), &seen(false, "wifi"), at(1, 12)).row;
        assert!(stopped.stopped_at.is_some());
        assert_eq!(stopped.window_from, None);
        // At most 8 rows kept, the newest.
        let many = Report {
            outside: (0..20).map(|i| outside("seen", "wifi", i)).collect(),
            ..Report::default()
        };
        let kept = next_state(&Row::default(), &many, at(1, 12)).row.outside;
        assert_eq!(kept.len(), OUTSIDE_MAX);
        assert_eq!(kept.last().unwrap().at_ms, 19);
    }

    #[test]
    fn on_is_sticky_and_a_phones_enforcing_is_adopted() {
        let mut row = Row::default();
        let mut enforcing = seen(true, "wifi");
        enforcing.enforcing = Some(true);
        row = next_state(&row, &enforcing, at(1, 12)).row;
        assert_eq!(row.state, State::On);
        assert_eq!(row.phone_enforcing, Some(true));
        let mut refused = seen(false, "wifi");
        refused.outside = vec![outside("refused", "wifi", 9)];
        let next = next_state(&row, &refused, at(1, 13));
        assert_eq!(next.row.state, State::On);
        assert!(next.push.is_some());
        // Off: an enforcing report doesn't move it, and nothing is pushed.
        let off = by_parent(&row, Action::Off, "Mamma", at(1, 14));
        let mut more = refused.clone();
        more.enforcing = Some(true);
        more.outside.push(outside("refused", "mobile", 10));
        let still = next_state(&off, &more, at(2, 1));
        assert_eq!(still.row.state, State::Off);
        assert_eq!(still.push, None);
    }

    #[test]
    fn the_parents_buttons() {
        let watched = run(Row::default(), &[(1, 12, "wifi"), (2, 12, "wifi")]);
        let off = by_parent(&watched, Action::Off, "Mamma", at(2, 13));
        assert_eq!(
            (off.state, off.changed_by.as_deref()),
            (State::Off, Some("Mamma"))
        );
        let again = by_parent(&off, Action::Watch, "Pappa", at(3, 9));
        assert_eq!(again.state, State::Watching);
        assert_eq!((again.window_from, again.window_days.len()), (None, 0));
        let mut stopped = again.clone();
        stopped.stopped_at = Some(at(3, 10));
        let on = by_parent(&stopped, Action::OnNow, "Pappa", at(3, 11));
        assert_eq!((on.state, on.stopped_at), (State::On, None));
        assert_eq!(Action::of("nonsense"), None);
    }

    #[test]
    fn watching_sends_no_enforce() {
        assert_eq!(policy_value(State::Watching), serde_json::json!({}));
        assert_eq!(
            policy_value(State::On),
            serde_json::json!({"enforce": true})
        );
        assert_eq!(
            policy_value(State::Off),
            serde_json::json!({"enforce": false})
        );
    }

    #[test]
    fn a_report_is_kept_within_limits() {
        let long = "x".repeat(300);
        let value = serde_json::json!({
            "enforcing": "yes",
            "seen": {"in_set": true, "name": long, "pin": "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "network": "ethernet", "at_ms": 1},
            "outside": [
                {"kind": "seen", "host": long, "name": "a", "pin": "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "network": "wifi", "at_ms": 1},
                {"kind": "other", "host": "h", "name": "a", "pin": "AAAA", "network": "wifi", "at_ms": 2},
                {"kind": "seen", "host": "h", "name": "a", "pin": "<script>", "network": "wifi", "at_ms": 3},
                {"kind": "seen", "host": "h", "name": "a", "pin": long, "network": "wifi", "at_ms": 4},
                "junk"
            ]
        });
        let report = sanitize(&value).unwrap();
        assert_eq!(report.enforcing, None);
        let seen = report.seen.unwrap();
        assert_eq!(
            (seen.name.chars().count(), seen.network.as_str()),
            (NAME_MAX, "other")
        );
        assert_eq!(report.outside.len(), 1);
        assert_eq!(report.outside[0].host.chars().count(), HOST_MAX);
        assert_eq!(sanitize(&serde_json::json!([1])), None);
        assert_eq!(sanitize(&serde_json::json!({})), Some(Report::default()));
    }
}
