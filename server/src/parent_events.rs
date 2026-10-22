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
use crate::models::{BatteryReport, PowerOff, PowerOn, PowerReport};
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
    /// Design 38: the phone's shutdown broadcast (`ACTION_SHUTDOWN`) and the first process start of
    /// the next boot.
    PhoneOff,
    PhoneOn,
}

impl Kind {
    pub const ALL: [Kind; 8] = [
        Kind::EmergencyCall,
        Kind::PinUsed,
        Kind::PinLockout,
        Kind::ForeignAccess,
        Kind::UnseenBoot,
        Kind::PhoneOff,
        Kind::PhoneOn,
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
            Kind::PhoneOff => "phone_off",
            Kind::PhoneOn => "phone_on",
        }
    }

    pub fn of(wire: &str) -> Option<Kind> {
        Kind::ALL.into_iter().find(|kind| kind.wire() == wire)
    }

    /// Urgent: the parent should see it now (design 35 §2.2; the push's `Urgency: high`, and
    /// phase 2's second channel).
    pub fn urgent(self) -> bool {
        matches!(
            self,
            Kind::EmergencyCall | Kind::PinLockout | Kind::ForeignAccess
        )
    }

    /// Seconds the push service keeps the push for an offline device (§4.3): a day for the
    /// emergency call, an hour for a low battery (later it is charging or flat), else 12 h.
    pub fn ttl(self) -> i64 {
        match self {
            Kind::EmergencyCall => 86_400,
            Kind::LowBattery => 3_600,
            _ => 43_200,
        }
    }

    /// The push's `Urgency` header.
    pub fn urgency(self) -> &'static str {
        if self.urgent() { "high" } else { "normal" }
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
            Kind::PhoneOff => Some(("push_phone_off", "Phone turned off")),
            Kind::PhoneOn => Some(("push_phone_on", "Phone turned on")),
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
    /// `devices.enrolled_at`: part of a `phone_on` key (design 38 §3.2, a re-enrollment starts
    /// another count).
    pub enrolled_at: Option<String>,
    pub zone: Zone,
    pub now: DateTime<Utc>,
}

impl Phone {
    /// The phone's name and battery latch, and the Phone Setup zone. `None` when the phone is gone
    /// (deleted while its request ran) or the read failed (logged).
    pub async fn load(db: &SqlitePool, device_id: i64) -> Option<Phone> {
        let row: Option<(String, Option<String>, Option<String>)> =
            sqlx::query_as("SELECT name, battery_low_since, enrolled_at FROM devices WHERE id = ?")
                .bind(device_id)
                .fetch_optional(db)
                .await
                .unwrap_or_else(|err| {
                    tracing::error!(device_id, %err, "can't read the phone for its events");
                    None
                });
        let (name, battery_low_since, enrolled_at) = row?;
        Some(Phone {
            id: device_id,
            name,
            battery_low_since,
            enrolled_at,
            zone: Zone::load(db).await,
            now: Utc::now(),
        })
    }

    /// "14:02" today, "yesterday 21:40", else "12 Oct 14:02": when something happened, with its
    /// clock time (an event may arrive days late, from a phone that was off).
    fn at(&self, t: DateTime<Utc>) -> String {
        self.zone.at(t, self.now)
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
            push: Push::Now,
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

    /// A shutdown the phone saw (design 38 §3.2): "Anna was turned off" / "21:14 · battery 2 %,
    /// probably flat". `late`: it reached the server only with the next boot's start (" · told at
    /// the next start", never pushed). Pushed after [OFF_HOLD] (a restart's start drops it), never
    /// for a restart the phone knew of.
    pub fn phone_off(&self, off: &PowerOff, at: DateTime<Utc>, late: bool) -> NewEvent {
        let restarting = off.reboot == Some(true);
        let reason = off_reason_words(off.reason.as_deref());
        let level = off.battery.map(|b| format!("battery {} %", b.level));
        let (title, battery): (String, Option<String>) = match (restarting, reason) {
            (true, _) => (
                self.named(TITLE_MAX, |name| format!("{name} is restarting")),
                off.battery.map(|b| battery_words(&b, FLAT_LEVEL)),
            ),
            (false, Some(OffReason::Battery)) => (
                self.named(TITLE_MAX, |name| format!("{name} shut down: battery flat")),
                level,
            ),
            (false, Some(OffReason::Thermal)) => (
                self.named(TITLE_MAX, |name| format!("{name} shut down: too hot")),
                level,
            ),
            (false, None) => (
                self.named(TITLE_MAX, |name| format!("{name} was turned off")),
                off.battery.map(|b| battery_words(&b, FLAT_LEVEL)),
            ),
        };
        let mut summary = capitalized(&self.at(at));
        if let Some(battery) = battery {
            summary.push_str(&format!(" · {battery}"));
        }
        if late {
            summary.push_str(" · told at the next start");
        }
        let push = if restarting || late {
            Push::No
        } else {
            Push::Held(self.now + OFF_HOLD)
        };
        NewEvent {
            title,
            summary,
            push,
            ..self.event(Kind::PhoneOff, instant(at), at)
        }
    }

    /// The boot's start (design 38 §3.2), always pushed at once: "Anna restarted" / "07:02 ·
    /// battery 64 %", "Anna was turned on" / "07:02 · off since 21:14, battery was 3 %", or with no
    /// shutdown seen the newest report before the boot.
    pub fn phone_on(&self, on: &PowerOn, at: DateTime<Utc>, start: &Start) -> NewEvent {
        let mut when = capitalized(&self.at(at));
        // Design 59 §2.3: with Android's lock the phone waits at "type the PIN to start" after a
        // restart; "· unlocked 07:15" once that took more than [UNLOCK_SAID_AFTER].
        if let Some(unlocked) = on
            .unlocked_at_ms
            .and_then(DateTime::from_timestamp_millis)
            .filter(|u| *u - at > UNLOCK_SAID_AFTER && *u <= self.now + POWER_FUTURE)
        {
            when.push_str(&format!(" · unlocked {}", self.at(unlocked)));
        }
        let (title, summary) = match start {
            Start::Restart => (
                self.named(TITLE_MAX, |name| format!("{name} restarted")),
                match on.battery {
                    Some(b) => format!("{when} · battery {} %", b.level),
                    None => when,
                },
            ),
            Start::AfterOff { off, at: off_at } => {
                let battery = off
                    .battery
                    .map(|b| format!(", battery was {} %", b.level))
                    .unwrap_or_default();
                (
                    self.named(TITLE_MAX, |name| format!("{name} was turned on")),
                    format!("{when} · off since {}{battery}", self.at(*off_at)),
                )
            }
            Start::NoShutdownSeen(last) => {
                let last = last
                    .as_ref()
                    .map(|last| {
                        let battery = last
                            .level
                            .map(|level| {
                                let battery = BatteryReport {
                                    level,
                                    charging: last.charging == Some(true),
                                    low: false,
                                };
                                format!(", {}", battery_words(&battery, LAST_REPORT_FLAT_LEVEL))
                            })
                            .unwrap_or_default();
                        format!(" · last report {}{battery}", self.at(last.at))
                    })
                    .unwrap_or_default();
                (
                    self.named(TITLE_MAX, |name| format!("{name} was turned on")),
                    format!("{when} · no shutdown seen{last}"),
                )
            }
        };
        NewEvent {
            title,
            summary,
            ..self.event(
                Kind::PhoneOn,
                format!(
                    "boot:{}:{}",
                    self.enrolled_at.as_deref().unwrap_or(""),
                    on.boot_count
                ),
                at,
            )
        }
    }
}

/// A `phone_off`'s push waits this long on the server's clock for the next boot's `phone_on`
/// (design 38 §3.3): a restart's start drops it. Apart from [RESTART_GAP], since the start's
/// upload comes only after the boot, the unlock, the network and tsnet's connect (qa-38-design N2;
/// if the Jelly Star's check times it above this, raise this, not the gap).
pub const OFF_HOLD: chrono::Duration = chrono::Duration::minutes(5);
/// Between the phone's two instants, a shutdown and the next boot's start this close together
/// with the restart flag unknown are a restart.
pub const RESTART_GAP: chrono::Duration = chrono::Duration::minutes(3);
/// "probably flat" at or below this level, not charging, at the shutdown...
pub const FLAT_LEVEL: i64 = 3;
/// ...and in the last report before a start with no shutdown seen (qa-38-design M5).
pub const LAST_REPORT_FLAT_LEVEL: i64 = 10;
/// A start's `unlocked_at_ms` this long after it is told ("· unlocked 07:15", design 59 §2.3): the
/// minutes the phone sat at "type the PIN to start".
pub const UNLOCK_SAID_AFTER: chrono::Duration = chrono::Duration::minutes(2);
/// A part's instant this far in the server's future is dropped (that part only).
const POWER_FUTURE: chrono::Duration = chrono::Duration::minutes(5);
/// A part (a shutdown or a start) older than this is dropped (qa-38-code nit 2: a start too).
const POWER_MAX_AGE: chrono::Duration = chrono::Duration::days(30);

/// "battery 2 %, probably flat" (at or below `flat_level`, not charging), "battery 64 %,
/// charging", "battery 64 %, not charging".
pub fn battery_words(battery: &BatteryReport, flat_level: i64) -> String {
    let state = if battery.charging {
        "charging"
    } else if battery.level <= flat_level {
        "probably flat"
    } else {
        "not charging"
    };
    format!("battery {} %, {state}", battery.level)
}

/// The shutdown reasons worded (design 38 §3.2), from `sys.shutdown.requested`'s text.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OffReason {
    /// `battery`: Android shut down for a flat battery.
    Battery,
    /// `thermal-shutdown`, `thermal`.
    Thermal,
}

/// The known reasons; `userrequested` and anything else say nothing more than "turned off".
pub fn off_reason_words(reason: Option<&str>) -> Option<OffReason> {
    match reason? {
        "battery" => Some(OffReason::Battery),
        "thermal-shutdown" | "thermal" => Some(OffReason::Thermal),
        _ => None,
    }
}

/// The newest status report before a boot: its time and battery, for "no shutdown seen".
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LastReport {
    pub at: DateTime<Utc>,
    pub level: Option<i64>,
    pub charging: Option<bool>,
}

/// How a boot's start is told.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Start {
    Restart,
    AfterOff { off: PowerOff, at: DateTime<Utc> },
    NoShutdownSeen(Option<LastReport>),
}

/// What one `POST /api/devices/power` does (design 38 §3.3), applied by the handler in one
/// transaction.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PowerPlan {
    /// Raised in order (the `off` first).
    pub events: Vec<NewEvent>,
    /// A restart: the `phone_off` with this key has its still-queued pushes `dropped`, `restart`.
    pub drop_held_off: Option<String>,
    /// A start after a real shutdown: the `phone_off` with this key, if its held push hasn't gone
    /// yet, goes now - before the "turned on", never after it.
    pub release_held_off: Option<String>,
    /// Parts left out, for the log.
    pub dropped: Vec<&'static str>,
}

/// The state machine of one shutdown and the start after it (design 38 §3.3), pure over the body,
/// the newest report before the boot and the phone. An `off` and an `on` pair when the off's boot
/// is earlier; a pair one boot apart is a restart when the phone said so, or said nothing and the
/// two instants are at most [RESTART_GAP] apart.
pub fn power_events(report: &PowerReport, last: Option<&LastReport>, phone: &Phone) -> PowerPlan {
    let mut plan = PowerPlan::default();
    let in_reach = |at_ms: i64| {
        DateTime::from_timestamp_millis(at_ms).filter(|at| *at <= phone.now + POWER_FUTURE)
    };
    let on = report.on.as_ref().and_then(|on| match in_reach(on.at_ms) {
        None => {
            plan.dropped.push("on in the future");
            None
        }
        Some(at) if at < phone.now - POWER_MAX_AGE => {
            plan.dropped.push("on older than 30 days");
            None
        }
        Some(at) => Some((on, at)),
    });
    let off = report
        .off
        .as_ref()
        .and_then(|off| match in_reach(off.at_ms) {
            None => {
                plan.dropped.push("off in the future");
                None
            }
            Some(at) if at < phone.now - POWER_MAX_AGE => {
                plan.dropped.push("off older than 30 days");
                None
            }
            Some(at) if on.is_some_and(|(_, on_at)| at > on_at) => {
                plan.dropped.push("off after the on");
                None
            }
            Some(at) => Some((off, at)),
        });
    match (off, on) {
        (Some((off, off_at)), Some((on, on_at))) if off.boot_count < on.boot_count => {
            let restart = off.boot_count + 1 == on.boot_count
                && match off.reboot {
                    Some(reboot) => reboot,
                    None => on_at - off_at <= RESTART_GAP,
                };
            let key = instant(off_at);
            plan.events.push(phone.phone_off(off, off_at, true));
            if restart {
                plan.events.push(phone.phone_on(on, on_at, &Start::Restart));
                plan.drop_held_off = Some(key);
            } else {
                let start = Start::AfterOff {
                    off: off.clone(),
                    at: off_at,
                };
                plan.events.push(phone.phone_on(on, on_at, &start));
                plan.release_held_off = Some(key);
            }
        }
        (off, on) => {
            if let Some((off, off_at)) = off {
                plan.events.push(phone.phone_off(off, off_at, false));
            }
            if let Some((on, on_at)) = on {
                let start = Start::NoShutdownSeen(last.cloned());
                plan.events.push(phone.phone_on(on, on_at, &start));
            }
        }
    }
    plan
}

/// The device page's header line between a shutdown and the next start (design 38 §3.4): "Off
/// since 21:14 · battery was 3 %", the battery read from the off's stored words. Nothing for a
/// restart the phone announced ("Anna is restarting") or while the off's push is still held
/// ([OFF_HOLD] from its arrival, `created_at`): a restart's start may still fold it (qa-38-code
/// nit 1), and the page says no more than the pushes.
pub fn off_since_line(
    happened_at: DateTime<Utc>,
    created_at: DateTime<Utc>,
    title: &str,
    summary: &str,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<String> {
    if title.ends_with(" is restarting") || now - created_at < OFF_HOLD {
        return None;
    }
    let level = summary.split(" · ").find_map(|part| {
        let rest = part.strip_prefix("battery ")?;
        let digits: String = rest.chars().take_while(char::is_ascii_digit).collect();
        (!digits.is_empty() && rest[digits.len()..].starts_with(" %")).then_some(digits)
    });
    Some(match level {
        Some(level) => format!(
            "Off since {} · battery was {level} %",
            zone.at(happened_at, now)
        ),
        None => format!("Off since {}", zone.at(happened_at, now)),
    })
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
    /// Whether and when a new row's push is queued (design 38 §3.2).
    pub push: Push,
}

/// When a new event's push goes (design 38 §3.2): at once, held until an instant (the sender's
/// `next_try_at`; a `phone_off` waits [OFF_HOLD] for a restart's `phone_on`), or not at all (a
/// restart's shutdown, an off told only at the next start). The row is stored either way.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Push {
    Now,
    Held(DateTime<Utc>),
    No,
}

/// A path on this server: it starts with one `/` (a `//host` would be another site, design 35 §7).
pub fn local_target(target: &str) -> bool {
    target.starts_with('/') && !target.starts_with("//") && !target.starts_with("/\\")
}

/// Stores `event` once: the new row's id, or `None` when it was there already (the same phone,
/// kind and key) or its target isn't a local path (a bug, logged). A new row queues its pushes
/// (`push::queue`). Runs on the caller's connection, so a caller's transaction holds both; the
/// caller wakes the sender (`state.push.wake()`) once that committed.
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
    let id: Option<i64> = sqlx::query_scalar(
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
    .fetch_optional(&mut *conn)
    .await?;
    if let Some(id) = id {
        match event.push {
            Push::Now => crate::push::queue(conn, id, event.kind, None).await?,
            Push::Held(until) => crate::push::queue(conn, id, event.kind, Some(until)).await?,
            Push::No => {}
        }
    }
    Ok(id)
}

/// [raise] in its own transaction, for an intake without one, then the sender's wake-up; a
/// failure is logged.
pub async fn raise_logged(state: &crate::AppState, event: &NewEvent) {
    let result = async {
        let mut tx = state.db.begin().await?;
        let id = raise(&mut tx, event).await?;
        tx.commit().await.map(|()| id)
    };
    match result.await {
        Ok(Some(_)) => state.push.wake(),
        Ok(None) => {}
        Err(err) => {
            tracing::error!(device_id = event.device_id, kind = event.kind.wire(), %err, "can't store an event")
        }
    }
}

/// An emergency call to announce from a status report (§2.1): the reported call is newer than the
/// previous report's; when the previous report named no call (none, an older report without
/// `call_state`, or no previous report at all) only one at most a day old by the server's clock -
/// a months-old call after a report without `call_state` is no news (qa-35-s1-code N4).
pub fn new_emergency_call(
    reported: Option<DateTime<Utc>>,
    previous: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> Option<DateTime<Utc>> {
    let at = reported?;
    match previous {
        Some(before) => (at > before).then_some(at),
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
/// reports latch once) and wakes the sender; [Edge::Rearm] arms it again. Failures are logged.
pub async fn battery_report(
    state: &crate::AppState,
    phone: &Phone,
    battery: &crate::models::BatteryReport,
) {
    let db = &state.db;
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
                let raised = if latched == 1 {
                    raise(&mut tx, &phone.low_battery(battery, since)).await?
                } else {
                    None
                };
                tx.commit().await?;
                if raised.is_some() {
                    state.push.wake();
                }
                Ok(())
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

/// A report's battery as the Devices row and the device page show it, with the report's age as
/// the other status lines have theirs: "Battery 14 %, charging · 3 min ago".
pub fn battery_line(
    level: Option<i64>,
    charging: Option<bool>,
    reported_at: &str,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<String> {
    let level = level?;
    let charging = if charging == Some(true) {
        ", charging"
    } else {
        ""
    };
    let age = crate::display_time::parse_db_utc(reported_at)
        .map(|at| format!(" · {}", zone.relative(at, now)))
        .unwrap_or_default();
    Some(format!("Battery {level} %{charging}{age}"))
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
            enrolled_at: Some("2026-10-01 08:00:00".to_string()),
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

    fn off(battery: Option<BatteryReport>, reboot: Option<bool>, reason: Option<&str>) -> PowerOff {
        PowerOff {
            at_ms: utc("2026-10-18 12:02:00").timestamp_millis(),
            boot_count: 41,
            battery,
            reboot,
            reason: reason.map(str::to_string),
        }
    }

    fn on(battery: Option<BatteryReport>) -> PowerOn {
        PowerOn {
            at_ms: utc("2026-10-18 12:20:00").timestamp_millis(),
            boot_count: 42,
            battery,
            unlocked_at_ms: None,
        }
    }

    /// Design 38 §3.2's table: the shutdown's and the start's words, and whether they push.
    #[test]
    fn the_power_words() {
        let phone = anna();
        // 19:14 yesterday, 14:02 and 14:20 today, in Oslo.
        let yesterday = utc("2026-10-17 17:14:00");
        let at = utc("2026-10-18 12:02:00");
        let b = |level, charging| Some(battery(level, charging, false));
        let held = Push::Held(phone.now + OFF_HOLD);
        for (event, title, summary, push) in [
            (
                phone.phone_off(&off(b(3, false), None, None), at, false),
                "Anna was turned off",
                "14:02 · battery 3 %, probably flat",
                held,
            ),
            (
                phone.phone_off(&off(b(4, false), None, None), yesterday, false),
                "Anna was turned off",
                "Yesterday 19:14 · battery 4 %, not charging",
                held,
            ),
            (
                phone.phone_off(&off(b(1, false), None, Some("battery")), at, false),
                "Anna shut down: battery flat",
                "14:02 · battery 1 %",
                held,
            ),
            (
                phone.phone_off(
                    &off(b(64, false), None, Some("thermal-shutdown")),
                    at,
                    false,
                ),
                "Anna shut down: too hot",
                "14:02 · battery 64 %",
                held,
            ),
            (
                phone.phone_off(&off(b(64, false), None, Some("thermal")), at, false),
                "Anna shut down: too hot",
                "14:02 · battery 64 %",
                held,
            ),
            // `userrequested` and an unknown reason say nothing more.
            (
                phone.phone_off(&off(b(64, false), None, Some("userrequested")), at, false),
                "Anna was turned off",
                "14:02 · battery 64 %, not charging",
                held,
            ),
            (
                phone.phone_off(&off(b(2, true), Some(false), Some("<b>x</b>")), at, false),
                "Anna was turned off",
                "14:02 · battery 2 %, charging",
                held,
            ),
            (
                phone.phone_off(&off(b(64, true), Some(true), None), at, false),
                "Anna is restarting",
                "14:02 · battery 64 %, charging",
                Push::No,
            ),
            (
                phone.phone_off(&off(None, None, None), at, true),
                "Anna was turned off",
                "14:02 · told at the next start",
                Push::No,
            ),
        ] {
            assert_eq!(
                (event.title.as_str(), event.summary.as_str(), event.push),
                (title, summary, push),
            );
            assert_eq!(
                (event.kind, event.dedup_key.as_str()),
                (Kind::PhoneOff, instant(event.happened_at).as_str())
            );
        }
        let start = utc("2026-10-18 12:20:00");
        let last = |level, charging| LastReport {
            at: utc("2026-10-18 12:10:00"),
            level,
            charging,
        };
        for (how, summary) in [
            (Start::Restart, "14:20"),
            (
                Start::AfterOff {
                    off: off(b(3, false), None, None),
                    at: yesterday,
                },
                "14:20 · off since yesterday 19:14, battery was 3 %",
            ),
            (
                Start::AfterOff {
                    off: off(None, None, None),
                    at,
                },
                "14:20 · off since 14:02",
            ),
            (
                Start::NoShutdownSeen(Some(last(Some(10), Some(false)))),
                "14:20 · no shutdown seen · last report 14:10, battery 10 %, probably flat",
            ),
            (
                Start::NoShutdownSeen(Some(last(Some(11), None))),
                "14:20 · no shutdown seen · last report 14:10, battery 11 %, not charging",
            ),
            (
                Start::NoShutdownSeen(Some(last(None, None))),
                "14:20 · no shutdown seen · last report 14:10",
            ),
            (Start::NoShutdownSeen(None), "14:20 · no shutdown seen"),
        ] {
            let event = phone.phone_on(&on(None), start, &how);
            assert_eq!(event.summary, summary);
            let title = if how == Start::Restart {
                "Anna restarted"
            } else {
                "Anna was turned on"
            };
            assert_eq!((event.title.as_str(), event.push), (title, Push::Now));
            assert_eq!(event.dedup_key, "boot:2026-10-01 08:00:00:42");
        }
        // Design 59 §2.3: the minutes at "type the PIN to start", when there were more than 2;
        // a time before the start or in the future says nothing.
        for (unlocked, summary) in [
            (
                "2026-10-18 12:23:00",
                "14:20 · unlocked 14:23 · off since 14:02",
            ),
            ("2026-10-18 12:22:00", "14:20 · off since 14:02"),
            ("2026-10-18 12:00:00", "14:20 · off since 14:02"),
            ("2026-10-18 13:30:00", "14:20 · off since 14:02"),
        ] {
            let mut on = on(None);
            on.unlocked_at_ms = Some(utc(unlocked).timestamp_millis());
            let how = Start::AfterOff {
                off: off(None, None, None),
                at,
            };
            assert_eq!(
                phone.phone_on(&on, start, &how).summary,
                summary,
                "{unlocked}"
            );
        }
        // The thresholds: flat at 3 not at 4 at the shutdown, at 10 not 11 in the last report.
        for (level, flat_level, words) in [
            (3, FLAT_LEVEL, "battery 3 %, probably flat"),
            (4, FLAT_LEVEL, "battery 4 %, not charging"),
            (10, LAST_REPORT_FLAT_LEVEL, "battery 10 %, probably flat"),
            (11, LAST_REPORT_FLAT_LEVEL, "battery 11 %, not charging"),
        ] {
            assert_eq!(
                battery_words(&battery(level, false, true), flat_level),
                words
            );
        }
        assert_eq!(off_reason_words(None), None);
        assert_eq!(off_reason_words(Some("Battery")), None);
    }

    fn report(off: Option<PowerOff>, on: Option<PowerOn>) -> PowerReport {
        PowerReport { off, on }
    }

    /// `(kind, push)` of a plan's events, and its two key effects.
    fn shape(plan: &PowerPlan) -> (Vec<(Kind, Push)>, bool, bool) {
        (
            plan.events.iter().map(|e| (e.kind, e.push)).collect(),
            plan.drop_held_off.is_some(),
            plan.release_held_off.is_some(),
        )
    }

    /// Design 38 §3.3's state machine.
    #[test]
    fn the_power_state_machine() {
        let phone = anna();
        let held = Push::Held(phone.now + OFF_HOLD);
        let ms = |s: &str| utc(s).timestamp_millis();
        let off_at = |at: &str, boot: i64, reboot: Option<bool>| PowerOff {
            at_ms: ms(at),
            boot_count: boot,
            battery: Some(battery(3, false, true)),
            reboot,
            reason: None,
        };
        let on_at = |at: &str, boot: i64| PowerOn {
            at_ms: ms(at),
            boot_count: boot,
            battery: None,
            unlocked_at_ms: None,
        };
        let (off_kind, on_kind) = (Kind::PhoneOff, Kind::PhoneOn);
        // An off alone: held; with the restart flag never pushed. An on alone: no shutdown seen.
        let alone = power_events(
            &report(Some(off_at("2026-10-18 12:00:00", 41, None)), None),
            None,
            &phone,
        );
        assert_eq!(shape(&alone), (vec![(off_kind, held)], false, false));
        let restarting = power_events(
            &report(Some(off_at("2026-10-18 12:00:00", 41, Some(true))), None),
            None,
            &phone,
        );
        assert_eq!(
            shape(&restarting),
            (vec![(off_kind, Push::No)], false, false)
        );
        let start = power_events(
            &report(None, Some(on_at("2026-10-18 12:00:00", 42))),
            None,
            &phone,
        );
        assert_eq!(shape(&start), (vec![(on_kind, Push::Now)], false, false));
        assert!(start.events[0].summary.contains("no shutdown seen"));

        // Paired one boot apart: a restart within the 3-minute gap (the flag unknown), or with the
        // flag whatever the gap; not with the flag false, nor past the gap.
        let pair = |off_s: &str, off_boot, reboot, on_s: &str, on_boot| {
            power_events(
                &report(
                    Some(off_at(off_s, off_boot, reboot)),
                    Some(on_at(on_s, on_boot)),
                ),
                None,
                &phone,
            )
        };
        let restart = |plan: &PowerPlan| {
            assert_eq!(
                shape(plan),
                (
                    vec![(off_kind, Push::No), (on_kind, Push::Now)],
                    true,
                    false
                )
            );
            assert_eq!(plan.events[1].title, "Anna restarted");
            assert!(
                plan.events[0]
                    .summary
                    .ends_with(" · told at the next start")
            );
        };
        let after_off = |plan: &PowerPlan| {
            assert_eq!(
                shape(plan),
                (
                    vec![(off_kind, Push::No), (on_kind, Push::Now)],
                    false,
                    true
                )
            );
            assert_eq!(plan.events[1].title, "Anna was turned on");
            assert!(
                plan.events[1]
                    .summary
                    .contains("off since 14:00, battery was 3 %")
            );
        };
        restart(&pair(
            "2026-10-18 12:00:00",
            41,
            None,
            "2026-10-18 12:03:00",
            42,
        ));
        restart(&pair(
            "2026-10-18 12:00:00",
            41,
            Some(true),
            "2026-10-18 12:20:00",
            42,
        ));
        after_off(&pair(
            "2026-10-18 12:00:00",
            41,
            None,
            "2026-10-18 12:03:01",
            42,
        ));
        after_off(&pair(
            "2026-10-18 12:00:00",
            41,
            Some(false),
            "2026-10-18 12:01:00",
            42,
        ));
        // A boot between the two is never a restart (unseen_boot tells it).
        after_off(&pair(
            "2026-10-18 12:00:00",
            40,
            Some(true),
            "2026-10-18 12:01:00",
            42,
        ));
        // The drop and the release name the off's key.
        let plan = pair("2026-10-18 12:00:00", 41, None, "2026-10-18 12:01:00", 42);
        assert_eq!(
            plan.drop_held_off.as_deref(),
            Some("2026-10-18T12:00:00.000Z")
        );
        // Not paired (the off isn't of an earlier boot): each told alone.
        let unpaired = pair("2026-10-18 12:00:00", 42, None, "2026-10-18 12:01:00", 42);
        assert_eq!(
            shape(&unpaired),
            (vec![(off_kind, held), (on_kind, Push::Now)], false, false)
        );

        // Drops: an off after its on (the on alone), an off older than 30 days, a part more than
        // 5 minutes in the future (that part only).
        let late = pair("2026-10-18 12:05:00", 41, None, "2026-10-18 12:01:00", 42);
        assert_eq!(shape(&late), (vec![(on_kind, Push::Now)], false, false));
        assert_eq!(late.dropped, ["off after the on"]);
        let old = pair("2026-09-18 12:29:00", 41, None, "2026-10-18 12:01:00", 42);
        assert_eq!(old.dropped, ["off older than 30 days"]);
        let kept = pair("2026-09-18 12:31:00", 41, None, "2026-10-18 12:01:00", 42);
        assert!(kept.dropped.is_empty());
        let ahead = pair("2026-10-18 12:00:00", 41, None, "2026-10-18 12:35:01", 42);
        assert_eq!(shape(&ahead), (vec![(off_kind, held)], false, false));
        assert_eq!(ahead.dropped, ["on in the future"]);
        let near = pair("2026-10-18 12:00:00", 41, None, "2026-10-18 12:35:00", 42);
        assert!(near.dropped.is_empty());
        let both = pair("2026-10-18 12:36:00", 41, None, "2026-10-18 12:37:00", 42);
        assert!(both.events.is_empty());
        // A start older than 30 days is dropped too (qa-38-code nit 2), the off then told alone.
        let ancient = pair("2026-09-18 12:20:00", 41, None, "2026-09-18 12:29:00", 42);
        assert_eq!(
            ancient.dropped,
            ["on older than 30 days", "off older than 30 days"]
        );
        let start = power_events(
            &report(None, Some(on_at("1970-01-01 00:00:00", 42))),
            None,
            &phone,
        );
        assert!(start.events.is_empty());
        let recent = pair("2026-09-18 12:31:00", 41, None, "2026-09-18 12:32:00", 42);
        assert!(recent.dropped.is_empty());
    }

    #[test]
    fn the_off_since_line() {
        let phone = anna();
        let at = utc("2026-10-17 19:14:00");
        let line = |created: DateTime<Utc>, title: &str, summary: &str| {
            off_since_line(at, created, title, summary, &phone.zone, phone.now)
        };
        let arrived = phone.now - OFF_HOLD;
        let off = "Anna was turned off";
        assert_eq!(
            line(arrived, off, "21:14 · battery 3 %, probably flat").as_deref(),
            Some("Off since yesterday 21:14 · battery was 3 %")
        );
        assert_eq!(
            line(arrived, off, "Yesterday 21:14 · told at the next start").as_deref(),
            Some("Off since yesterday 21:14")
        );
        assert_eq!(
            line(arrived, off, "21:14 · battery x %").as_deref(),
            Some("Off since yesterday 21:14")
        );
        // Not while the push is held (a restart's start may still fold it), not for a restart.
        let held = phone.now - OFF_HOLD + chrono::Duration::seconds(1);
        assert_eq!(line(held, off, "21:14 · battery 3 %"), None);
        assert_eq!(
            line(arrived, "Anna is restarting", "21:14 · battery 3 %"),
            None
        );
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
                phone.phone_off(&off(Some(battery(2, false, true)), None, None), two, false),
                "Anna was turned off",
                "14:02 · battery 2 %, probably flat",
                "/devices/3",
            ),
            (
                phone.phone_on(&on(Some(battery(64, true, false))), two, &Start::Restart),
                "Anna restarted",
                "14:02 · battery 64 %",
                "/devices/3",
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
        assert_eq!(rows[6].0.dedup_key, "boot:2026-10-01 08:00:00:42");
        assert_eq!(rows[7].0.dedup_key, "2026-10-18T12:02:00.000Z");
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
        assert_eq!(new_emergency_call(call, call, now), None);
        assert_eq!(
            new_emergency_call(call, Some(utc("2026-10-17 11:00:00")), now),
            call
        );
        assert_eq!(new_emergency_call(None, call, now), None);
        // No call in the previous report (or none at all): only a call at most a day old by the
        // server's clock - also an old one newer than an older report's.
        let old = Some(utc("2026-10-16 12:00:00"));
        assert_eq!(new_emergency_call(call, None, now), call);
        assert_eq!(new_emergency_call(old, None, now), None);
        assert_eq!(
            new_emergency_call(old, Some(utc("2026-10-01 12:00:00")), now),
            old
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
        let zone = Zone::from_setting("Europe/Oslo");
        let now = utc("2026-10-18 12:30:00");
        let line =
            |level, charging| battery_line(level, charging, "2026-10-18 12:27:00", &zone, now);
        assert_eq!(
            line(Some(14), Some(true)).as_deref(),
            Some("Battery 14 %, charging · 3 min ago")
        );
        assert_eq!(
            line(Some(80), Some(false)).as_deref(),
            Some("Battery 80 % · 3 min ago")
        );
        assert_eq!(line(None, Some(true)), None);
        assert_eq!(Bell { unread: 3 }.pill(), "3");
        assert_eq!(Bell { unread: 100 }.pill(), "99+");
    }
}
