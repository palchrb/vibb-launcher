//! What happened on the phones, for the parent (design 35 §2, `docs/design/35-parent-notifications.md`):
//! one row per event in `parent_events`, raised once from its real input - a status report or the
//! phone's `pin-events` - by [raise] (`ON CONFLICT DO NOTHING` on `(device_id, kind, dedup_key)`,
//! so a repeated report, a retried POST or two parallel ones give one row). The words are made here
//! at raise from typed values, never from phone-supplied text, with local times, and stored: the
//! inbox (`/notifications`) and, from step 2, the push say the same. Every kind is stored; the
//! switches in `notification_settings` govern the push only.
//!
//! The words and the battery rule are pure and unit-tested; `src/tests/notifications.rs` raises each
//! kind from its real input.

use chrono::{DateTime, SecondsFormat, Utc};
use sha2::{Digest, Sha256};
use sqlx::{SqliteConnection, SqlitePool};

use crate::display_time::Zone;
use crate::window_relock::ForeignAccess;

/// A title is at most this many characters, a summary at most [SUMMARY_MAX] (design 35 §2.2): they
/// fit a notification banner. A long phone name is cut to fit, the rest of the words stay.
pub const TITLE_MAX: usize = 60;
pub const SUMMARY_MAX: usize = 100;
/// Events are kept this many days (`retention::prune`, by `created_at`).
pub const EVENT_DAYS: i64 = 90;
/// The inbox shows this many a page.
pub const PAGE: i64 = 100;
/// A first report's emergency call is announced only when at most this old by the server's clock
/// (§2.1): an older one was made before the phone reported to this server.
const FIRST_REPORT_CALL_HOURS: i64 = 24;

/// An event's kind, as stored in `parent_events.kind`. (`app_blocked` and `extra_time_request` are
/// reserved, design 35 §2.1, and get a variant when something raises them.)
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    EmergencyCall,
    PinLockout,
    PinUsed,
    ForeignAccess,
    UnseenBoot,
    LowBattery,
}

impl Kind {
    pub const ALL: [Kind; 6] = [
        Kind::EmergencyCall,
        Kind::PinUsed,
        Kind::PinLockout,
        Kind::ForeignAccess,
        Kind::UnseenBoot,
        Kind::LowBattery,
    ];

    pub fn wire(self) -> &'static str {
        match self {
            Kind::EmergencyCall => "emergency_call",
            Kind::PinLockout => "pin_lockout",
            Kind::PinUsed => "pin_used",
            Kind::ForeignAccess => "foreign_access",
            Kind::UnseenBoot => "unseen_boot",
            Kind::LowBattery => "low_battery",
        }
    }

    /// Its push switch: the `notification_settings` column and the label on Notify Me About.
    /// `None` for the emergency call, which is always pushed.
    pub fn switch(self) -> Option<(&'static str, &'static str)> {
        match self {
            Kind::EmergencyCall => None,
            Kind::PinUsed => Some(("push_pin_used", "Parent PIN used")),
            Kind::PinLockout => Some(("push_pin_lockout", "Parent PIN locked")),
            Kind::ForeignAccess => Some(("push_foreign_access", "An app may read the PIN")),
            Kind::UnseenBoot => Some(("push_unseen_boot", "Start without Vibb One")),
            Kind::LowBattery => Some(("push_low_battery", "Battery low")),
        }
    }
}

/// The phone an event is about, and how its times are told: read once per intake ([Phone::load]).
#[derive(Clone, Debug)]
pub struct Phone {
    pub id: i64,
    pub name: String,
    /// `devices.battery_low_since`: the battery latch (§2.5), `None` = armed.
    pub battery_low_since: Option<String>,
    pub zone: Zone,
    pub now: DateTime<Utc>,
}

impl Phone {
    /// The phone's name and battery latch, and the Phone Setup zone. `None` when the phone is gone
    /// (deleted while its request ran) or the read failed (logged).
    pub async fn load(db: &SqlitePool, device_id: i64) -> Option<Phone> {
        let row: Option<(String, Option<String>)> =
            sqlx::query_as("SELECT name, battery_low_since FROM devices WHERE id = ?")
                .bind(device_id)
                .fetch_optional(db)
                .await
                .unwrap_or_else(|err| {
                    tracing::error!(device_id, %err, "can't read the phone for its events");
                    None
                });
        let (name, battery_low_since) = row?;
        Some(Phone {
            id: device_id,
            name,
            battery_low_since,
            zone: Zone::load(db).await,
            now: Utc::now(),
        })
    }

    /// "14:02" today, "yesterday 21:40", else "12 Oct 14:02": when something happened, with its
    /// clock time (an event may arrive days late, from a phone that was off).
    fn at(&self, t: DateTime<Utc>) -> String {
        let when = self.zone.when(t, self.now);
        if when.contains(':') {
            when
        } else {
            format!("{when} {}", self.zone.clock(t))
        }
    }

    /// `words` with the phone's name in it, the name cut ("…") so the whole is at most `max`
    /// characters.
    fn named(&self, max: usize, words: impl Fn(&str) -> String) -> String {
        let room = max.saturating_sub(words("").chars().count());
        if self.name.chars().count() <= room {
            return words(&self.name);
        }
        let cut: String = self.name.chars().take(room.saturating_sub(1)).collect();
        words(&format!("{}…", cut.trim_end()))
    }

    fn event(&self, kind: Kind, dedup_key: String, happened_at: DateTime<Utc>) -> NewEvent {
        NewEvent {
            device_id: self.id,
            kind,
            dedup_key,
            title: String::new(),
            summary: String::new(),
            target: format!("/devices/{}", self.id),
            detail: None,
            happened_at,
        }
    }

    /// An emergency call the phone reported (`call_state.last_emergency_call_at`).
    pub fn emergency_call(&self, at: DateTime<Utc>) -> NewEvent {
        let when = self.at(at);
        let summary = if self.zone.is_today(at, self.now) {
            format!("At {when}")
        } else {
            capitalized(&when)
        };
        NewEvent {
            title: self.named(TITLE_MAX, |name| {
                format!("{name} called an emergency number")
            }),
            summary,
            target: format!("/devices/{}/calls", self.id),
            ..self.event(Kind::EmergencyCall, instant(at), at)
        }
    }

    /// A newly stored parent-PIN use or lockout from the phone's `pin-events` (`None` for a wrong
    /// try, which is no event). Happened at the phone's time, or at its arrival when its clock was
    /// off (`clean_event` set it so).
    pub fn pin_event(&self, event: &crate::parent_pin::PinEvent) -> Option<NewEvent> {
        use crate::parent_pin::{Entry, Kind as PinKind};
        let at = DateTime::from_timestamp_millis(event.occurred_at_ms).unwrap_or(self.now);
        let (kind, title, summary) = match event.kind {
            PinKind::Wrong => return None,
            PinKind::Unlocked => {
                let entry = match event.entry {
                    Entry::Override => "Time lock",
                    Entry::PinLock => "Lock screen",
                    Entry::Settings => "Vibb One's Settings",
                    Entry::Pause => "Pause",
                    Entry::InstallMode => "Play install mode",
                };
                (
                    Kind::PinUsed,
                    self.named(TITLE_MAX, |name| format!("Parent PIN used on {name}")),
                    format!("{entry} · {}", self.at(at)),
                )
            }
            PinKind::Lockout => {
                let until = event
                    .until_ms
                    .and_then(DateTime::from_timestamp_millis)
                    .filter(|until| *until > self.now)
                    .map(|until| format!("until {}", self.zone.until(until, self.now)))
                    .unwrap_or_else(|| self.at(at));
                (
                    Kind::PinLockout,
                    self.named(TITLE_MAX, |name| format!("Parent PIN locked on {name}")),
                    format!("Too many wrong tries · {until}"),
                )
            }
        };
        Some(NewEvent {
            title,
            summary,
            target: format!("/devices/{}/lock#parent-pin", self.id),
            ..self.event(kind, event.event_id.clone(), at)
        })
    }

    /// An app outside the allowlist that could read the parent PIN (the report's `foreign_access`,
    /// a new set). `stored` is the set as stored (its JSON, for the key), `previous_status_id` the
    /// phone's report before this one (0 for none): the same set again after it went away is a new
    /// event. `names` are the phone's names for its apps, for the inbox's detail.
    pub fn foreign_access(
        &self,
        found: &ForeignAccess,
        stored: &str,
        previous_status_id: i64,
        names: &std::collections::HashMap<String, String>,
    ) -> NewEvent {
        let mut apps = found.admins.clone();
        apps.extend(
            found
                .accessibility
                .iter()
                .filter(|p| !found.admins.contains(p))
                .cloned(),
        );
        let what = match (found.admins.is_empty(), found.accessibility.is_empty()) {
            (false, true) => "with device admin",
            (true, false) => "with accessibility on",
            _ => "with device admin or accessibility",
        };
        let count = if apps.len() == 1 {
            "1 app".to_string()
        } else {
            format!("{} apps", apps.len())
        };
        let named = |list: &[String]| {
            list.iter()
                .map(|p| names.get(p).cloned().unwrap_or_else(|| p.clone()))
                .collect::<Vec<_>>()
                .join(", ")
        };
        let mut detail = Vec::new();
        if !found.admins.is_empty() {
            detail.push(format!("Device admin: {}.", named(&found.admins)));
        }
        if !found.accessibility.is_empty() {
            detail.push(format!("Accessibility: {}.", named(&found.accessibility)));
        }
        let digest = hex::encode(Sha256::digest(stored.as_bytes()));
        NewEvent {
            title: self.named(TITLE_MAX, |name| {
                format!("An app may read the PIN on {name}")
            }),
            summary: format!("{count} {what}"),
            target: format!("/devices/{}#foreign-access", self.id),
            detail: Some(detail.join(" ")),
            ..self.event(
                Kind::ForeignAccess,
                format!("{previous_status_id}:{}", &digest[..16]),
                self.now,
            )
        }
    }

    /// Starts without the launcher: the phone's count rose (`boot_witness::log_line` said so).
    pub fn unseen_boot(&self, count: i64, last_at_ms: Option<i64>) -> NewEvent {
        let at = last_at_ms
            .and_then(DateTime::from_timestamp_millis)
            .filter(|at| *at <= self.now)
            .unwrap_or(self.now);
        NewEvent {
            title: self.named(TITLE_MAX, |name| format!("{name} started without Vibb One")),
            summary: "Safe mode or a start cut short".to_string(),
            target: format!("/devices/{}/about#diagnostics", self.id),
            ..self.event(
                Kind::UnseenBoot,
                format!("{count}:{}", last_at_ms.unwrap_or(0)),
                at,
            )
        }
    }

    /// The battery latched low at `since` (§2.5).
    pub fn low_battery(
        &self,
        battery: &crate::models::BatteryReport,
        since: DateTime<Utc>,
    ) -> NewEvent {
        let level = battery.level;
        let charging = if battery.charging {
            "Charging"
        } else {
            "Not charging"
        };
        NewEvent {
            title: self.named(TITLE_MAX, |name| {
                format!("Battery low on {name} ({level} %)")
            }),
            summary: format!("{charging} · {}", self.at(since)),
            ..self.event(Kind::LowBattery, instant(since), since)
        }
    }
}

/// An event to store: the words made, the key that makes it one row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewEvent {
    pub device_id: i64,
    pub kind: Kind,
    pub dedup_key: String,
    pub title: String,
    pub summary: String,
    /// A path on this server, where the inbox row leads.
    pub target: String,
    /// Inbox only, never pushed.
    pub detail: Option<String>,
    pub happened_at: DateTime<Utc>,
}

/// A path on this server: it starts with one `/` (a `//host` would be another site, design 35 §7).
pub fn local_target(target: &str) -> bool {
    target.starts_with('/') && !target.starts_with("//") && !target.starts_with("/\\")
}

/// Stores `event` once: the new row's id, or `None` when it was there already (the same phone,
/// kind and key) or its target isn't a local path (a bug, logged). Runs on the caller's connection,
/// so a caller's transaction holds it.
pub async fn raise(
    conn: &mut SqliteConnection,
    event: &NewEvent,
) -> Result<Option<i64>, sqlx::Error> {
    if !local_target(&event.target) {
        tracing::error!(
            target = event.target,
            "an event's target isn't a local path"
        );
        return Ok(None);
    }
    sqlx::query_scalar(
        "INSERT INTO parent_events \
         (device_id, kind, dedup_key, title, summary, target, detail, happened_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT (device_id, kind, dedup_key) DO NOTHING RETURNING id",
    )
    .bind(event.device_id)
    .bind(event.kind.wire())
    .bind(&event.dedup_key)
    .bind(crate::audit::clip(&event.title, TITLE_MAX))
    .bind(crate::audit::clip(&event.summary, SUMMARY_MAX))
    .bind(&event.target)
    .bind(&event.detail)
    .bind(instant(event.happened_at))
    .fetch_optional(conn)
    .await
}

/// [raise] on a pool connection, for an intake without a transaction; a failure is logged.
pub async fn raise_logged(db: &SqlitePool, event: &NewEvent) {
    let result = match db.acquire().await {
        Ok(mut conn) => raise(&mut conn, event).await,
        Err(err) => Err(err),
    };
    if let Err(err) = result {
        tracing::error!(device_id = event.device_id, kind = event.kind.wire(), %err, "can't store an event");
    }
}

/// An emergency call to announce from a status report (§2.1): the reported call is newer than the
/// previous report's; with no previous report, only one at most a day old by the server's clock.
/// `previous` is `None` when there was no previous report, `Some(None)` when it had no call.
pub fn new_emergency_call(
    reported: Option<DateTime<Utc>>,
    previous: Option<Option<DateTime<Utc>>>,
    now: DateTime<Utc>,
) -> Option<DateTime<Utc>> {
    let at = reported?;
    match previous {
        Some(previous) => previous.is_none_or(|before| at > before).then_some(at),
        None => (now - at <= chrono::Duration::hours(FIRST_REPORT_CALL_HOURS)).then_some(at),
    }
}

/// What a report's `battery` does to the latch (§2.5).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Edge {
    /// Armed and low: latch it and raise the event.
    Raise,
    /// Latched and running on battery above Android's warning level: arm it again.
    Rearm,
    None,
}

/// Not latched and `low` → [Edge::Raise]; latched and neither `low` nor charging → [Edge::Rearm]
/// (plugging in clears Android's `low` and unplugging below the level sets it again: re-arming on
/// `!low` alone would announce every plug and unplug); else nothing.
pub fn battery_edge(latched: bool, battery: &crate::models::BatteryReport) -> Edge {
    match (latched, battery.low, battery.charging) {
        (false, true, _) => Edge::Raise,
        (true, false, false) => Edge::Rearm,
        _ => Edge::None,
    }
}

/// A report's `battery` against the phone's latch (§2.5), in one transaction: [Edge::Raise]
/// latches it with a conditional UPDATE and raises only when that changed the row (two parallel
/// reports latch once); [Edge::Rearm] arms it again. Failures are logged.
pub async fn battery_report(
    db: &SqlitePool,
    phone: &Phone,
    battery: &crate::models::BatteryReport,
) {
    let result = async {
        match battery_edge(phone.battery_low_since.is_some(), battery) {
            Edge::Raise => {
                let mut tx = db.begin().await?;
                let since = phone.now;
                let latched = sqlx::query(
                    "UPDATE devices SET battery_low_since = ? \
                     WHERE id = ? AND battery_low_since IS NULL",
                )
                .bind(instant(since))
                .bind(phone.id)
                .execute(&mut *tx)
                .await?
                .rows_affected();
                if latched == 1 {
                    raise(&mut tx, &phone.low_battery(battery, since)).await?;
                }
                tx.commit().await
            }
            Edge::Rearm => sqlx::query("UPDATE devices SET battery_low_since = NULL WHERE id = ?")
                .bind(phone.id)
                .execute(db)
                .await
                .map(|_| ()),
            Edge::None => Ok(()),
        }
    };
    if let Err(err) = result.await {
        tracing::error!(device_id = phone.id, %err, "can't keep the battery's state");
    }
}

/// A report's battery as the Devices row and the device page show it: "Battery 14 %, charging".
pub fn battery_line(level: Option<i64>, charging: Option<bool>) -> Option<String> {
    let level = level?;
    Some(if charging == Some(true) {
        format!("Battery {level} %, charging")
    } else {
        format!("Battery {level} %")
    })
}

/// The bell on the tab pages and the Settings hub's row: the unread count (one partial-index
/// `COUNT`; no cache).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Bell {
    pub unread: i64,
}

impl Bell {
    /// A failed read shows no count (logged); the bell still leads to the inbox.
    pub async fn load(db: &SqlitePool) -> Bell {
        let unread = sqlx::query_scalar("SELECT COUNT(*) FROM parent_events WHERE read_at IS NULL")
            .fetch_one(db)
            .await
            .unwrap_or_else(|err| {
                tracing::error!(%err, "can't count the unread events");
                0
            });
        Bell { unread }
    }

    /// The pill's text: "3", "99+".
    pub fn pill(&self) -> String {
        if self.unread > 99 {
            "99+".to_string()
        } else {
            self.unread.to_string()
        }
    }
}

/// RFC 3339 with milliseconds, UTC: how an instant is stored and keyed.
fn instant(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn capitalized(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::BatteryReport;
    use crate::parent_pin::{Entry, Kind as PinKind, PinEvent};

    fn utc(s: &str) -> DateTime<Utc> {
        crate::display_time::parse_db_utc(s).unwrap()
    }

    fn anna() -> Phone {
        Phone {
            id: 3,
            name: "Anna".to_string(),
            battery_low_since: None,
            zone: Zone::from_setting("Europe/Oslo"),
            // 14:30 in Oslo.
            now: utc("2026-10-18 12:30:00"),
        }
    }

    fn pin(kind: PinKind, entry: Entry, at: &str, until: Option<&str>) -> PinEvent {
        PinEvent {
            event_id: "0123456789abcdef0123456789abcdef".to_string(),
            kind,
            entry,
            occurred_at_ms: utc(at).timestamp_millis(),
            until_ms: until.map(|u| utc(u).timestamp_millis()),
            failures: None,
            level: Some(1),
            clock_off: false,
        }
    }

    fn battery(level: i64, charging: bool, low: bool) -> BatteryReport {
        BatteryReport {
            level,
            charging,
            low,
        }
    }

    /// Design 35 §2.2's table: each kind's title, summary and target.
    #[test]
    fn the_words_and_targets() {
        let phone = anna();
        let two = utc("2026-10-18 12:02:00");
        let mut names = std::collections::HashMap::new();
        names.insert("org.example.spy".to_string(), "Helper".to_string());
        let one_admin = ForeignAccess {
            admins: vec!["org.example.spy".to_string()],
            accessibility: vec![],
        };
        let rows = [
            (
                phone.emergency_call(two),
                "Anna called an emergency number",
                "At 14:02",
                "/devices/3/calls",
            ),
            (
                phone
                    .pin_event(&pin(
                        PinKind::Unlocked,
                        Entry::Override,
                        "2026-10-18 12:02:00",
                        None,
                    ))
                    .unwrap(),
                "Parent PIN used on Anna",
                "Time lock · 14:02",
                "/devices/3/lock#parent-pin",
            ),
            (
                phone
                    .pin_event(&pin(
                        PinKind::Lockout,
                        Entry::PinLock,
                        "2026-10-18 12:15:00",
                        Some("2026-10-18 12:30:30"),
                    ))
                    .unwrap(),
                "Parent PIN locked on Anna",
                "Too many wrong tries · until 14:30",
                "/devices/3/lock#parent-pin",
            ),
            (
                phone.foreign_access(&one_admin, "{}", 7, &names),
                "An app may read the PIN on Anna",
                "1 app with device admin",
                "/devices/3#foreign-access",
            ),
            (
                phone.unseen_boot(2, Some(two.timestamp_millis())),
                "Anna started without Vibb One",
                "Safe mode or a start cut short",
                "/devices/3/about#diagnostics",
            ),
            (
                phone.low_battery(&battery(14, false, true), two),
                "Battery low on Anna (14 %)",
                "Not charging · 14:02",
                "/devices/3",
            ),
        ];
        for (event, title, summary, target) in &rows {
            assert_eq!(
                (
                    event.title.as_str(),
                    event.summary.as_str(),
                    event.target.as_str()
                ),
                (*title, *summary, *target),
                "{:?}",
                event.kind
            );
            assert!(local_target(&event.target));
        }
        let kinds: Vec<Kind> = rows.iter().map(|(e, ..)| e.kind).collect();
        assert_eq!(kinds, Kind::ALL);
        // Only the foreign apps have a detail, by the phone's names.
        assert_eq!(rows[3].0.detail.as_deref(), Some("Device admin: Helper."));
        assert!(rows.iter().filter(|(e, ..)| e.detail.is_some()).count() == 1);
        // The keys (§2.1).
        assert_eq!(rows[0].0.dedup_key, "2026-10-18T12:02:00.000Z");
        assert_eq!(rows[1].0.dedup_key, "0123456789abcdef0123456789abcdef");
        assert!(rows[3].0.dedup_key.starts_with("7:") && rows[3].0.dedup_key.len() == 18);
        assert_eq!(rows[4].0.dedup_key, format!("2:{}", two.timestamp_millis()));
        assert_eq!(rows[5].0.dedup_key, "2026-10-18T12:02:00.000Z");
    }

    #[test]
    fn other_days_long_names_and_variants() {
        let mut phone = anna();
        assert_eq!(
            phone.emergency_call(utc("2026-10-17 19:40:00")).summary,
            "Yesterday 21:40"
        );
        assert_eq!(
            phone.emergency_call(utc("2026-10-12 06:12:00")).summary,
            "12 Oct 08:12"
        );
        // A lockout already over says when it happened; a wrong try is no event.
        let over = pin(
            PinKind::Lockout,
            Entry::Settings,
            "2026-10-18 10:00:00",
            Some("2026-10-18 10:15:00"),
        );
        assert_eq!(
            phone.pin_event(&over).unwrap().summary,
            "Too many wrong tries · 12:00"
        );
        assert!(
            phone
                .pin_event(&pin(
                    PinKind::Wrong,
                    Entry::PinLock,
                    "2026-10-18 10:00:00",
                    None
                ))
                .is_none()
        );
        let both = ForeignAccess {
            admins: vec!["a.b".to_string()],
            accessibility: vec!["a.b".to_string(), "c.d".to_string()],
        };
        let event = phone.foreign_access(&both, "x", 0, &Default::default());
        assert_eq!(event.summary, "2 apps with device admin or accessibility");
        assert_eq!(
            event.detail.as_deref(),
            Some("Device admin: a.b. Accessibility: a.b, c.d.")
        );
        assert_eq!(
            phone
                .low_battery(&battery(9, true, false), phone.now)
                .summary,
            "Charging · 14:30"
        );
        // A 40-character name is cut so the title fits, the level kept.
        phone.name = "Annabelle-Sofie Marie Kristiansen Olsen!".to_string();
        let title = phone
            .low_battery(&battery(14, false, true), phone.now)
            .title;
        assert_eq!(title.chars().count(), TITLE_MAX);
        assert!(title.starts_with("Battery low on Annabelle") && title.ends_with("… (14 %)"));
        for event in [
            phone.emergency_call(phone.now),
            phone.unseen_boot(1, None),
            phone.foreign_access(&both, "x", 0, &Default::default()),
        ] {
            assert!(event.title.chars().count() <= TITLE_MAX, "{}", event.title);
        }
    }

    #[test]
    fn targets_must_be_local_paths() {
        assert!(local_target("/devices/3"));
        for bad in [
            "//evil.example/x",
            "https://evil.example",
            "devices",
            "",
            "/\\evil.example",
        ] {
            assert!(!local_target(bad), "{bad}");
        }
    }

    #[test]
    fn an_emergency_call_is_new_only_once() {
        let now = utc("2026-10-18 12:00:00");
        let call = Some(utc("2026-10-18 11:00:00"));
        assert_eq!(new_emergency_call(call, Some(None), now), call);
        assert_eq!(new_emergency_call(call, Some(call), now), None);
        assert_eq!(
            new_emergency_call(call, Some(Some(utc("2026-10-17 11:00:00"))), now),
            call
        );
        assert_eq!(new_emergency_call(None, Some(call), now), None);
        // The first report: only a call at most a day old by the server's clock.
        assert_eq!(new_emergency_call(call, None, now), call);
        assert_eq!(
            new_emergency_call(Some(utc("2026-10-16 12:00:00")), None, now),
            None
        );
    }

    /// Latched or not × low × charging.
    #[test]
    fn the_battery_edge() {
        for (latched, low, charging, edge) in [
            (false, true, false, Edge::Raise),
            (false, true, true, Edge::Raise),
            (false, false, false, Edge::None),
            (false, false, true, Edge::None),
            (true, true, false, Edge::None),
            (true, true, true, Edge::None),
            (true, false, true, Edge::None),
            (true, false, false, Edge::Rearm),
        ] {
            assert_eq!(
                battery_edge(latched, &battery(14, charging, low)),
                edge,
                "latched {latched}, low {low}, charging {charging}"
            );
        }
    }

    #[test]
    fn the_battery_line_and_the_bell() {
        assert_eq!(
            battery_line(Some(14), Some(true)).as_deref(),
            Some("Battery 14 %, charging")
        );
        assert_eq!(
            battery_line(Some(80), Some(false)).as_deref(),
            Some("Battery 80 %")
        );
        assert_eq!(battery_line(None, Some(true)), None);
        assert_eq!(Bell { unread: 3 }.pill(), "3");
        assert_eq!(Bell { unread: 100 }.pill(), "99+");
    }
}
