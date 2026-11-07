//! What happened on the phones, for the parent (design 35 §2, `docs/design/35-parent-notifications.md`):
//! one row per event in `parent_events`, raised once from its real input - a status report or the
//! phone's `pin-events` - by [raise] (`ON CONFLICT DO NOTHING` on `(device_id, kind, dedup_key)`, a
//! catalog event's - no phone, migration 0099 - on `(kind, dedup_key)`,
//! so a repeated report, a retried POST or two parallel ones give one row). The words are made here
//! at raise from typed values, never from phone-supplied text, with local times, and stored: the
//! inbox (`/notifications`) and, from step 2, the push say the same. Every kind is stored; the
//! switches in `notification_prefs` (each parent's own, design 62 §5) govern the push only. The
//! events are the family's; whether one is read is each parent's (`parent_event_reads`, [UNREAD]).
//!
//! The words and the battery rule are pure and unit-tested; `src/tests/notifications.rs` raises each
//! kind from its real input.

use chrono::{DateTime, SecondsFormat, Utc};
use sha2::{Digest, Sha256};
use sqlx::{SqliteConnection, SqlitePool};

use crate::display_time::Zone;
use crate::i18n::{Lang, t, t_count, t_params};
use crate::models::{PowerOff, PowerOn, PowerReport};
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

/// An event's kind, as stored in `parent_events.kind`. (`app_blocked` is reserved, design 35 §2.1,
/// and gets a variant when something raises it.)
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
    /// Design 64: a phone arrived in or left one of its zones (`zones::evaluate`), told from its
    /// parameters ([NewEvent::params]).
    ZoneArrived,
    ZoneLeft,
    /// Design r13 §2.6: the kid tapped "Be om mer tid" in the phone's notification
    /// (`POST /api/devices/time-request`), told from its parameters ([ExtraTimeParams]).
    ExtraTimeRequest,
    /// App update notices (2026-10-25): the sweep or Check Now stored a new release of a catalog
    /// app (no phone, one event per release), and a phone's report shows a release installed (one
    /// per phone and version). Told from their parameters ([AppUpdateParams]).
    AppUpdateAvailable,
    AppUpdated,
    /// Design 74 D1.2: a parent added a passkey (no phone). Pushed to every parent at once - no
    /// switch - and in each inbox, so a passkey a held session planted is seen. Told from its
    /// parameters ([PasskeyParams]).
    PasskeyAdded,
    /// Push never lost silently (2026-11-04): the server ended a parent's subscriptions (an account change, three
    /// wrong tries, a Reset) or a push service answered that one is gone (404/410). No phone; pushed to every parent's
    /// remaining devices - no switch - and in each inbox. Told from its parameters ([PushOffParams]).
    PushOff,
}

impl Kind {
    pub const ALL: [Kind; 15] = [
        Kind::EmergencyCall,
        Kind::PinUsed,
        Kind::PinLockout,
        Kind::ForeignAccess,
        Kind::UnseenBoot,
        Kind::PhoneOff,
        Kind::PhoneOn,
        Kind::ZoneArrived,
        Kind::ZoneLeft,
        Kind::LowBattery,
        Kind::ExtraTimeRequest,
        Kind::AppUpdateAvailable,
        Kind::AppUpdated,
        Kind::PasskeyAdded,
        Kind::PushOff,
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
            Kind::ZoneArrived => "zone_arrived",
            Kind::ZoneLeft => "zone_left",
            Kind::ExtraTimeRequest => "extra_time_request",
            Kind::AppUpdateAvailable => "app_update_available",
            Kind::AppUpdated => "app_updated",
            Kind::PasskeyAdded => "passkey_added",
            Kind::PushOff => "push_off",
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
    /// emergency call, an hour for a low battery (later it is charging or flat), a zone's
    /// crossing (later it is old news, design 64 §3.4) and a request for more time (design r13
    /// §2.6; the inbox keeps it), else 12 h.
    pub fn ttl(self) -> i64 {
        match self {
            Kind::EmergencyCall => 86_400,
            Kind::LowBattery | Kind::ZoneArrived | Kind::ZoneLeft | Kind::ExtraTimeRequest => 3_600,
            _ => 43_200,
        }
    }

    /// The push's `Urgency` header.
    pub fn urgency(self) -> &'static str {
        if self.urgent() { "high" } else { "normal" }
    }

    /// Its push switch: the `notification_prefs` column and the label on Notify Me About.
    /// `None` for the emergency call, which is always pushed.
    pub fn switch(self) -> Option<(&'static str, &'static str)> {
        match self {
            // A new way into the account is always told (design 74 D1.2), and a device that stopped getting
            // notifications (2026-11-04: "å miste push sub er kritisk").
            Kind::EmergencyCall | Kind::PasskeyAdded | Kind::PushOff => None,
            Kind::PinUsed => Some(("push_pin_used", "Parent PIN used")),
            Kind::PinLockout => Some(("push_pin_lockout", "Parent PIN locked")),
            Kind::ForeignAccess => Some(("push_foreign_access", "An app may read the PIN")),
            Kind::UnseenBoot => Some(("push_unseen_boot", "Start without Vibb One")),
            Kind::LowBattery => Some(("push_low_battery", "Battery low")),
            Kind::PhoneOff => Some(("push_phone_off", "Phone turned off")),
            Kind::PhoneOn => Some(("push_phone_on", "Phone turned on")),
            // One switch for both directions (design 64 §3.4).
            Kind::ZoneArrived | Kind::ZoneLeft => Some(("push_zones", "Zones: arrived and left")),
            // Design r13 §2.6 (the user, 2026-10-25): its own switch, on by default.
            Kind::ExtraTimeRequest => Some(("push_extra_time", "Asks for more time")),
            // App update notices (2026-10-25): one switch for both, off by default.
            Kind::AppUpdateAvailable | Kind::AppUpdated => {
                Some((APP_UPDATES_SWITCH, "App updates"))
            }
        }
    }

    /// Whether a parent with no `notification_prefs` row - or a NULL there - has this switch on:
    /// every switch is on by default but "App updates" (migration 0099, the user's "kanskje man kan
    /// få valget").
    pub fn switch_default(column: &str) -> bool {
        column != APP_UPDATES_SWITCH
    }

    /// Notify Me About's switches, each once, in [Kind::ALL]'s order (two kinds may share one).
    pub fn switches() -> Vec<(&'static str, &'static str)> {
        let mut switches: Vec<(&'static str, &'static str)> = Vec::new();
        for switch in Kind::ALL.iter().filter_map(|kind| kind.switch()) {
            if !switches.contains(&switch) {
                switches.push(switch);
            }
        }
        switches
    }
}

/// The "App updates" switch's `notification_prefs` column (migration 0099), off by default.
pub const APP_UPDATES_SWITCH: &str = "push_app_updates";

/// The phone an event is about: read once per intake ([Phone::load]).
#[derive(Clone, Debug)]
pub struct Phone {
    pub id: i64,
    pub name: String,
    /// `devices.battery_low_since`: the battery latch (§2.5), `None` = armed.
    pub battery_low_since: Option<String>,
    /// `devices.enrolled_at`: part of a `phone_on` key (design 38 §3.2, a re-enrollment starts
    /// another count).
    pub enrolled_at: Option<String>,
    /// The Phone Setup zone, for day arithmetic only: an intake has no reader, so the words are
    /// made when an event is read, in the reader's language and zone (design 70 §4, [told]).
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
            zone: Zone::load(db, Lang::default()).await,
            now: Utc::now(),
        })
    }

    fn event(&self, kind: Kind, dedup_key: String, happened_at: DateTime<Utc>) -> NewEvent {
        NewEvent {
            device_id: Some(self.id),
            kind,
            dedup_key,
            title: String::new(),
            summary: String::new(),
            target: format!("/devices/{}", self.id),
            detail: None,
            params: None,
            push_title: String::new(),
            push_body: String::new(),
            happened_at,
            push: Push::Now,
        }
    }

    /// An event told from its parameters (design 70 §4): `params` stored, every word left to
    /// [told], [pushed] and [detail] - the `title`, `summary`, `push_title`, `push_body` and
    /// `detail` columns stay empty.
    fn with_params<P: serde::Serialize>(
        &self,
        kind: Kind,
        dedup_key: String,
        happened_at: DateTime<Utc>,
        params: &P,
    ) -> NewEvent {
        NewEvent {
            params: Some(serde_json::to_string(params).expect("strings serialize")),
            ..self.event(kind, dedup_key, happened_at)
        }
    }

    /// An emergency call the phone reported (`call_state.last_emergency_call_at`).
    pub fn emergency_call(&self, at: DateTime<Utc>) -> NewEvent {
        let params = PhoneAtParams {
            phone: self.name.clone(),
            at: instant(at),
        };
        NewEvent {
            target: format!("/devices/{}/calls", self.id),
            ..self.with_params(Kind::EmergencyCall, instant(at), at, &params)
        }
    }

    /// A newly stored parent-PIN use or lockout from the phone's `pin-events` (`None` for a wrong
    /// try, which is no event). Happened at the phone's time, or at its arrival when its clock was
    /// off (`clean_event` set it so). A lockout keeps its end only while it is ahead at the raise:
    /// one already over says when it happened.
    pub fn pin_event(&self, event: &crate::parent_pin::PinEvent) -> Option<NewEvent> {
        use crate::parent_pin::{Entry, Kind as PinKind};
        let at = DateTime::from_timestamp_millis(event.occurred_at_ms).unwrap_or(self.now);
        let (kind, until) = match event.kind {
            PinKind::Wrong => return None,
            PinKind::Unlocked => (Kind::PinUsed, None),
            PinKind::Lockout => (
                Kind::PinLockout,
                event
                    .until_ms
                    .and_then(DateTime::from_timestamp_millis)
                    .filter(|until| *until > self.now)
                    .map(instant),
            ),
        };
        let entry = match event.entry {
            Entry::Override => "override",
            Entry::PinLock => "pin_lock",
            Entry::Settings => "settings",
            Entry::Pause => "pause",
            Entry::InstallMode => "install_mode",
        };
        let params = PinParams {
            phone: self.name.clone(),
            at: instant(at),
            entry: entry.to_string(),
            until,
        };
        Some(NewEvent {
            target: format!("/devices/{}/lock#parent-pin", self.id),
            ..self.with_params(kind, event.event_id.clone(), at, &params)
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
        let count = found.admins.len()
            + found
                .accessibility
                .iter()
                .filter(|p| !found.admins.contains(p))
                .count();
        let named = |list: &[String]| {
            list.iter()
                .map(|p| names.get(p).cloned().unwrap_or_else(|| p.clone()))
                .collect::<Vec<_>>()
        };
        let params = ForeignAccessParams {
            phone: self.name.clone(),
            at: instant(self.now),
            count: count as i64,
            admins: named(&found.admins),
            accessibility: named(&found.accessibility),
        };
        let digest = hex::encode(Sha256::digest(stored.as_bytes()));
        NewEvent {
            target: format!("/devices/{}#foreign-access", self.id),
            ..self.with_params(
                Kind::ForeignAccess,
                format!("{previous_status_id}:{}", &digest[..16]),
                self.now,
                &params,
            )
        }
    }

    /// Starts without the launcher: the phone's count rose (`boot_witness::log_line` said so).
    pub fn unseen_boot(&self, count: i64, last_at_ms: Option<i64>) -> NewEvent {
        let at = last_at_ms
            .and_then(DateTime::from_timestamp_millis)
            .filter(|at| *at <= self.now)
            .unwrap_or(self.now);
        let params = PhoneAtParams {
            phone: self.name.clone(),
            at: instant(at),
        };
        NewEvent {
            target: format!("/devices/{}/about#diagnostics", self.id),
            ..self.with_params(
                Kind::UnseenBoot,
                format!("{count}:{}", last_at_ms.unwrap_or(0)),
                at,
                &params,
            )
        }
    }

    /// The battery latched low at `since` (§2.5).
    pub fn low_battery(
        &self,
        battery: &crate::models::BatteryReport,
        since: DateTime<Utc>,
    ) -> NewEvent {
        let params = LowBatteryParams {
            phone: self.name.clone(),
            at: instant(since),
            level: battery.level,
            charging: battery.charging,
        };
        self.with_params(Kind::LowBattery, instant(since), since, &params)
    }

    /// A shutdown the phone saw (design 38 §3.2): told as "Anna was turned off" / "21:14 · battery
    /// 2 %, probably flat". `late`: it reached the server only with the next boot's start (" · told
    /// at the next start", never pushed). Pushed after [OFF_HOLD] (a restart's start drops it),
    /// never for a restart the phone knew of. The phone's last stored fix (2026-10-25) rides in the
    /// parameters (`location`, never in any text) for the inbox's "Last position at 21:10 – see the
    /// map in Locate", until retention removes it with the location history.
    pub fn phone_off(&self, off: &PowerOff, at: DateTime<Utc>, late: bool) -> NewEvent {
        let restarting = off.reboot == Some(true);
        let reason = if restarting {
            OffReason::Restart
        } else {
            OffReason::of(off.reason.as_deref())
        };
        let push = if restarting || late {
            Push::No
        } else {
            Push::Held(self.now + OFF_HOLD)
        };
        let params = PhoneOffParams {
            phone: self.name.clone(),
            at: instant(at),
            reason: Some(reason.code().to_string()),
            battery: off.battery.map(|b| b.level),
            charging: off.battery.map(|b| b.charging),
            late,
            location: off.location.clone(),
        };
        NewEvent {
            push,
            ..self.with_params(Kind::PhoneOff, instant(at), at, &params)
        }
    }

    /// The boot's start (design 38 §3.2), always pushed at once: told as "Anna restarted" / "07:02
    /// · battery 64 %", "Anna was turned on" / "07:02 · off since 21:14, battery was 3 %", or with
    /// no shutdown seen the newest report before the boot.
    pub fn phone_on(&self, on: &PowerOn, at: DateTime<Utc>, start: &Start) -> NewEvent {
        // Design 59 §2.3: with Android's lock the phone waits at "type the PIN to start" after a
        // restart; "· unlocked 07:15" once that took more than [UNLOCK_SAID_AFTER].
        let unlocked = on
            .unlocked_at_ms
            .and_then(DateTime::from_timestamp_millis)
            .filter(|u| *u - at > UNLOCK_SAID_AFTER && *u <= self.now + POWER_FUTURE)
            .map(instant);
        let (start_code, off_at, off_battery, last) = match start {
            Start::Restart => ("restart", None, None, None),
            Start::AfterOff { off, at: off_at } => (
                "after_off",
                Some(instant(*off_at)),
                off.battery.map(|b| b.level),
                None,
            ),
            Start::NoShutdownSeen(last) => ("no_shutdown", None, None, last.as_ref()),
        };
        let params = PhoneOnParams {
            phone: self.name.clone(),
            at: instant(at),
            battery: on.battery.map(|b| b.level),
            unlocked,
            start: start_code.to_string(),
            off_at,
            off_battery,
            last_at: last.map(|l| instant(l.at)),
            last_battery: last.and_then(|l| l.level),
            last_charging: last.and_then(|l| l.charging),
        };
        self.with_params(
            Kind::PhoneOn,
            format!(
                "boot:{}:{}",
                self.enrolled_at.as_deref().unwrap_or(""),
                on.boot_count
            ),
            at,
            &params,
        )
    }
}

/// The parameters of an event with a phone and an instant (`emergency_call`, `unseen_boot`): the
/// phone's name as it was when it was raised and when (RFC 3339). Design 70 §4: every stored-words
/// kind raises so since slice 2, and is worded when read ([told]).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PhoneAtParams {
    pub phone: String,
    pub at: String,
}

/// A parent-PIN use or lockout: where the PIN was typed (`override`, `pin_lock`, `settings`,
/// `pause`, `install_mode`) and, for a lockout still ahead at the raise, its end.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PinParams {
    pub phone: String,
    pub at: String,
    pub entry: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub until: Option<String>,
}

/// A foreign app: how many apps in all, and the phone's names for the device admins and the
/// accessibility services (the inbox's detail).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ForeignAccessParams {
    pub phone: String,
    pub at: String,
    pub count: i64,
    pub admins: Vec<String>,
    pub accessibility: Vec<String>,
}

/// The battery latched low: its level and whether it was charging.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct LowBatteryParams {
    pub phone: String,
    pub at: String,
    pub level: i64,
    pub charging: bool,
}

/// A shutdown (design 38 §3.2; H2 of design 70's review): the reason as a code (`user`, `battery`,
/// `heat`, `restart`), the battery and whether it charged, whether it was told only at the next
/// start, and the phone's last stored fix - the one field a `phone_off` from before slice 2 has
/// (with its words stored), so `reason` is `None` on such a row and its text is read instead.
/// Retention removes `location` alone (`json_remove`), so the words outlive the position.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PhoneOffParams {
    #[serde(default)]
    pub phone: String,
    #[serde(default)]
    pub at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub battery: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub charging: Option<bool>,
    #[serde(default)]
    pub late: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub location: Option<crate::models::PowerLocation>,
}

/// A boot's start (design 38 §3.2): the battery now, the unlock when it was late (design 59
/// §2.3), how the start is told (`restart`, `after_off` with the shutdown's instant and battery,
/// `no_shutdown` with the newest report before the boot).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PhoneOnParams {
    pub phone: String,
    pub at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub battery: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unlocked: Option<String>,
    pub start: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub off_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub off_battery: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_battery: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_charging: Option<bool>,
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
/// charging", "battery 64 %, not charging" - in `lang`.
pub fn battery_state(lang: Lang, level: i64, charging: bool, flat_level: i64) -> String {
    let key = if charging {
        "events.battery.charging"
    } else if level <= flat_level {
        "events.battery.flat"
    } else {
        "events.battery.not_charging"
    };
    t_params(lang, key, &[("level", &level.to_string())])
}

/// "battery 64 %" (a push's one number, a start's level).
fn battery_level(lang: Lang, level: i64) -> String {
    t_params(
        lang,
        "events.battery.level",
        &[("level", &level.to_string())],
    )
}

/// Why a phone shut down (design 38 §3.2), from `sys.shutdown.requested`'s text or the phone's
/// restart flag; stored in a `phone_off`'s parameters as [OffReason::code].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OffReason {
    /// `userrequested`, and anything unknown: no more than "turned off".
    User,
    /// `battery`: Android shut down for a flat battery.
    Battery,
    /// `thermal-shutdown`, `thermal`.
    Thermal,
    /// The phone said it was restarting.
    Restart,
}

impl OffReason {
    /// The known reasons; `userrequested` and anything else are [OffReason::User].
    pub fn of(reason: Option<&str>) -> OffReason {
        match reason {
            Some("battery") => OffReason::Battery,
            Some("thermal-shutdown" | "thermal") => OffReason::Thermal,
            _ => OffReason::User,
        }
    }

    /// The code in the parameters.
    pub fn code(self) -> &'static str {
        match self {
            OffReason::User => "user",
            OffReason::Battery => "battery",
            OffReason::Thermal => "heat",
            OffReason::Restart => "restart",
        }
    }

    pub fn from_code(code: &str) -> Option<OffReason> {
        [
            OffReason::User,
            OffReason::Battery,
            OffReason::Thermal,
            OffReason::Restart,
        ]
        .into_iter()
        .find(|reason| reason.code() == code)
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

/// A `phone_off`'s parameters, if the row has any that read.
fn off_params(params: Option<&str>) -> Option<PhoneOffParams> {
    params.and_then(|p| serde_json::from_str(p).ok())
}

/// A pages' line about a `phone_off` may say it (the device page's "Off since", Locate's "Last
/// position before the phone went off" - qa-last-position-code Q-5): not for a restart the phone
/// announced (the parameters' `reason` since design 70, "Anna is restarting" in an older row's
/// stored words), not while the off's push is still held ([OFF_HOLD] from its arrival,
/// `created_at`) - the pages say no more than the pushes.
pub fn off_said(
    title: &str,
    params: Option<&str>,
    created_at: DateTime<Utc>,
    now: DateTime<Utc>,
) -> bool {
    let restart = match off_params(params).and_then(|p| p.reason) {
        Some(reason) => reason == OffReason::Restart.code(),
        None => title.ends_with(" is restarting"),
    };
    !restart && now - created_at >= OFF_HOLD
}

/// A phone turned off and not on since (design 38 §3.4): when it shut down and the battery it had
/// then. The Devices row and the summary's header lead with it (the user's pick C, 2026-11-01):
/// "Turned off 21:14 · battery was 3 %".
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OffSince {
    pub at: DateTime<Utc>,
    pub level: Option<String>,
}

impl OffSince {
    /// "Turned off 21:14" (nb "Slått av 21:14"): [Zone::since]'s time, never a date for today.
    pub fn lead(&self, zone: &Zone, now: DateTime<Utc>) -> String {
        t_params(
            zone.lang(),
            "devices.status.off",
            &[("at", &zone.since(self.at, now))],
        )
    }

    /// "battery was 3 %" (nb "batteriet var 3 %"), when the off said its battery.
    pub fn battery(&self, lang: Lang) -> Option<String> {
        self.level
            .as_ref()
            .map(|level| t_params(lang, "devices.status.off_battery", &[("level", level)]))
    }

    /// The whole line, "Turned off 21:14 · battery was 3 %" (the tests').
    #[cfg(test)]
    pub fn text(&self, zone: &Zone, now: DateTime<Utc>) -> String {
        match self.battery(zone.lang()) {
            Some(battery) => format!("{} · {battery}", self.lead(zone, now)),
            None => self.lead(zone, now),
        }
    }
}

/// The newest `phone_off` of each phone - of `device` alone, or of every phone - that is later than
/// its newest `phone_on` and than every status report (design 38 §3.4; qa-38-code M1: a report
/// after the shutdown proves the phone is on, whatever start never arrived - a re-enrollment, a boot
/// count of -1, a dropped start), as `(device_id, happened_at, created_at, title, summary,
/// params)`: one query for the whole Devices list. The reports are weighed against the off's
/// arrival (`created_at`), not its `happened_at`: both are this server's clock (SQLite's
/// `datetime('now')` text, compared through `datetime()`), while `happened_at` is the phone's - a
/// phone clock 3 min behind hid "Turned off" behind the report it sent as it shut down (2026-11-01,
/// "a phone that is off says so"). A minute's slack for a report racing the shutdown (no start
/// reports within a minute of its shutdown). The off against the starts stays `happened_at`, both
/// the phone's clock; [off_since] words it from `happened_at`.
pub async fn offs_now<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device: Option<i64>,
) -> Result<Vec<(i64, String, String, String, String, Option<String>)>, sqlx::Error> {
    sqlx::query_as(
        "SELECT o.device_id, o.happened_at, o.created_at, o.title, o.summary, o.params \
         FROM parent_events o \
         WHERE o.kind = 'phone_off' AND o.device_id IS NOT NULL \
         AND (?1 IS NULL OR o.device_id = ?1) \
         AND o.id = (SELECT n.id FROM parent_events n \
                     WHERE n.device_id = o.device_id AND n.kind = 'phone_off' \
                     ORDER BY n.happened_at DESC, n.id DESC LIMIT 1) \
         AND o.happened_at > \
             COALESCE((SELECT MAX(n.happened_at) FROM parent_events n \
                       WHERE n.device_id = o.device_id AND n.kind = 'phone_on'), '') \
         AND datetime(o.created_at, '+60 seconds') >= COALESCE((SELECT datetime(MAX(s.reported_at)) \
                       FROM device_status s \
                       WHERE s.device_id = o.device_id), '')",
    )
    .bind(device)
    .fetch_all(db)
    .await
}

/// The status lines' [OffSince] between a shutdown and the next start (design 38 §3.4), the battery
/// from the off's parameters (design 70), or read from an older row's stored words. Nothing for a
/// restart the phone announced or while the off's push is still held ([OFF_HOLD] from its arrival,
/// `created_at`): a restart's start may still fold it (qa-38-code nit 1), and the page says no more
/// than the pushes.
pub fn off_since(
    happened_at: DateTime<Utc>,
    created_at: DateTime<Utc>,
    title: &str,
    summary: &str,
    params: Option<&str>,
    now: DateTime<Utc>,
) -> Option<OffSince> {
    if !off_said(title, params, created_at, now) {
        return None;
    }
    let level = match off_params(params).filter(|p| p.reason.is_some()) {
        Some(p) => p.battery.map(|level| level.to_string()),
        None => summary.split(" · ").find_map(|part| {
            let rest = part.strip_prefix("battery ")?;
            let digits: String = rest.chars().take_while(char::is_ascii_digit).collect();
            (!digits.is_empty() && rest[digits.len()..].starts_with(" %")).then_some(digits)
        }),
    };
    Some(OffSince {
        at: happened_at,
        level,
    })
}

/// [off_since] from a row of [offs_now].
pub fn off_since_of(
    row: &(i64, String, String, String, String, Option<String>),
    now: DateTime<Utc>,
) -> Option<OffSince> {
    let (_, at, created, title, summary, params) = row;
    off_since(
        crate::display_time::parse_db_utc(at)?,
        crate::display_time::parse_db_utc(created)?,
        title,
        summary,
        params.as_deref(),
        now,
    )
}

/// An event to store: the words made, the key that makes it one row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewEvent {
    /// The phone it is about; `None` for a catalog event (`app_update_available`, migration 0099).
    pub device_id: Option<i64>,
    pub kind: Kind,
    pub dedup_key: String,
    pub title: String,
    pub summary: String,
    /// A path on this server, where the inbox row leads.
    pub target: String,
    /// Inbox only, never pushed.
    pub detail: Option<String>,
    /// A kind told from parameters (design 64's zones): the JSON the words are made from when
    /// shown or pushed ([told]); `title` and `summary` stay empty then.
    pub params: Option<String>,
    /// The push's own short words (2026-10-25), stored in `push_title`/`push_body` (empty = NULL)
    /// and sent by [pushed]; the inbox shows `title`/`summary`. A kind told from parameters
    /// leaves them empty: [pushed] words it.
    pub push_title: String,
    pub push_body: String,
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

/// A phone raises at most this many events of one kind in a rolling hour; past it they are not
/// stored (logged) - a leaked token or a rooted phone can't bury the inbox (security review L1).
pub const EVENTS_AN_HOUR: i64 = 20;
/// Of an urgent kind, a phone's first this many in a rolling hour are pushed; the rest of the hour's
/// are stored without a push of their own (L1: the parent has had that many "PIN locked" or
/// "another app has control" pushes; the inbox has the rest). Never the emergency call (QA L-b):
/// every one stored is pushed, so at most [EVENTS_AN_HOUR] an hour.
pub const URGENT_PUSHES_AN_HOUR: i64 = 3;

/// Stores `event` once: the new row's id, or `None` when it was there already (the same phone,
/// kind and key), its phone is past [EVENTS_AN_HOUR] of the kind (logged), or its target isn't a
/// local path (a bug, logged). A new row queues its pushes (`push::queue`; an urgent kind past
/// [URGENT_PUSHES_AN_HOUR], none). Runs on the caller's connection, so a caller's transaction holds
/// both; the caller wakes the sender (`state.push.wake()`) once that committed.
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
    let mut push = event.push;
    if let Some(device_id) = event.device_id {
        let this_hour: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM parent_events WHERE device_id = ? AND kind = ? \
             AND created_at > datetime('now', '-1 hour')",
        )
        .bind(device_id)
        .bind(event.kind.wire())
        .fetch_one(&mut *conn)
        .await?;
        if this_hour >= EVENTS_AN_HOUR {
            tracing::warn!(
                device_id,
                kind = event.kind.wire(),
                "a phone's events of one kind are past their hourly budget; not stored"
            );
            return Ok(None);
        }
        if event.kind.urgent()
            && event.kind != Kind::EmergencyCall
            && this_hour >= URGENT_PUSHES_AN_HOUR
        {
            push = Push::No;
        }
    }
    let id: Option<i64> = sqlx::query_scalar(
        "INSERT INTO parent_events \
         (device_id, kind, dedup_key, title, summary, target, detail, params, push_title, \
          push_body, happened_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), ?) \
         ON CONFLICT DO NOTHING RETURNING id",
    )
    .bind(event.device_id)
    .bind(event.kind.wire())
    .bind(&event.dedup_key)
    .bind(crate::audit::clip(&event.title, TITLE_MAX))
    .bind(crate::audit::clip(&event.summary, SUMMARY_MAX))
    .bind(&event.target)
    .bind(&event.detail)
    .bind(&event.params)
    .bind(crate::audit::clip(&event.push_title, TITLE_MAX))
    .bind(crate::audit::clip(&event.push_body, SUMMARY_MAX))
    .bind(instant(event.happened_at))
    .fetch_optional(&mut *conn)
    .await?;
    if let Some(id) = id {
        match push {
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
            tracing::error!(device_id = ?event.device_id, kind = event.kind.wire(), %err, "can't store an event")
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
    // A call dated past the server's clock (a phone clock that runs fast) is announced at the
    // server's now (QA L-b: dropped, it would never be news later - the previous report already
    // holds its time); `EVENTS_AN_HOUR` bounds a phone that sends a new one with every report.
    let at = reported?.min(now);
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

/// "Event `e` (`parent_events`' alias) is unread for the admin bound as `?1`" (design 62 §5): no
/// `parent_event_reads (admin, event)` row.
pub const UNREAD: &str =
    "NOT EXISTS (SELECT 1 FROM parent_event_reads r WHERE r.admin_id = ?1 AND r.event_id = e.id)";

/// Whether any parent has read the device's `emergency_call` event for the call at `at` (the
/// event's dedup key is the call's instant). `false` when no such event exists.
pub async fn emergency_call_read<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    device_id: i64,
    at: DateTime<Utc>,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM parent_events e JOIN parent_event_reads r ON r.event_id = e.id \
         WHERE e.device_id = ? AND e.kind = ? AND e.dedup_key = ?)",
    )
    .bind(device_id)
    .bind(Kind::EmergencyCall.wire())
    .bind(instant(at))
    .fetch_one(db)
    .await
}

/// The events `admin_id` hasn't read.
pub async fn unread_count<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    admin_id: i64,
) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(&format!(
        "SELECT COUNT(*) FROM parent_events e WHERE {UNREAD}"
    ))
    .bind(admin_id)
    .fetch_one(db)
    .await
}

/// Marks the events in `ids` (a JSON array of ids) read for `admin_id`; ones already read stay.
pub async fn mark_read<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    admin_id: i64,
    ids: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) \
         SELECT ?1, e.id FROM parent_events e WHERE e.id IN (SELECT value FROM json_each(?2))",
    )
    .bind(admin_id)
    .bind(ids)
    .execute(db)
    .await?;
    Ok(())
}

/// A new or restored parent starts with nothing unread (design 62 §4.1): every event so far is
/// read for `admin_id`.
pub async fn read_everything<'e, E: sqlx::SqliteExecutor<'e>>(
    db: E,
    admin_id: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT OR IGNORE INTO parent_event_reads (admin_id, event_id) SELECT ?, id FROM parent_events",
    )
    .bind(admin_id)
    .execute(db)
    .await?;
    Ok(())
}

/// The bell on the tab pages and the Settings hub's row: the signed-in parent's unread count (no
/// cache).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Bell {
    pub unread: i64,
}

impl Bell {
    /// A failed read shows no count (logged); the bell still leads to the inbox.
    pub async fn load(db: &SqlitePool, admin_id: i64) -> Bell {
        let unread = unread_count(db, admin_id).await.unwrap_or_else(|err| {
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

/// `words` with `name` in it, the name cut ("…") so the whole is at most `max` characters.
fn fit_name(name: &str, max: usize, words: impl Fn(&str) -> String) -> String {
    let room = max.saturating_sub(words("").chars().count());
    if name.chars().count() <= room {
        return words(name);
    }
    let cut: String = name.chars().take(room.saturating_sub(1)).collect();
    words(&format!("{}…", cut.trim_end()))
}

/// A zone event's parameters (design 64 §3.4, the user's decision 5): what the words are made of,
/// stored instead of the words - the zone's and the phone's names as they were when it was
/// raised, the direction, and when (the first fix of the pair, RFC 3339).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ZoneParams {
    pub zone_id: i64,
    pub zone: String,
    pub phone: String,
    /// `arrived` or `left`.
    pub direction: String,
    pub at: String,
}

/// A phone's crossing of one of its zones (design 64 §3.4): kind `zone_arrived`/`zone_left`, key
/// `<zone id>:<inside|outside>:<the first fix's time>` (one crossing is one row however often it
/// is evaluated), the words left to [told]. Target: the Locate page's map.
pub fn zone_crossing(
    device_id: i64,
    phone: &str,
    zone_id: i64,
    zone_name: &str,
    arrived: bool,
    at: DateTime<Utc>,
    push: Push,
) -> NewEvent {
    let (kind, side, direction) = if arrived {
        (Kind::ZoneArrived, "inside", "arrived")
    } else {
        (Kind::ZoneLeft, "outside", "left")
    };
    let params = ZoneParams {
        zone_id,
        zone: zone_name.to_string(),
        phone: phone.to_string(),
        direction: direction.to_string(),
        at: instant(at),
    };
    NewEvent {
        device_id: Some(device_id),
        kind,
        dedup_key: format!("{zone_id}:{side}:{}", instant(at)),
        title: String::new(),
        summary: String::new(),
        target: format!("/devices/locate?device={device_id}#location"),
        detail: None,
        params: Some(serde_json::to_string(&params).expect("strings serialize")),
        push_title: String::new(),
        push_body: String::new(),
        happened_at: at,
        push,
    }
}

/// Requests a phone may ask for more time a day (design r13 D5, the server's backstop for the
/// phone's own count): the fourth of the server zone's day is answered `capped` and not stored.
pub const EXTRA_TIME_REQUESTS_A_DAY: i64 = 3;
/// A request is news this long after the kid asked: pushed when younger at intake, else only in the
/// inbox and Activity (design r13 §2.6); the phone stops sending it after the same 30 minutes.
pub const EXTRA_TIME_NEWS_MINUTES: i64 = 30;

/// Where the phone stood when the kid asked (design r13 §2.6), from the server's own latest report
/// for it - never from text in the request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TimeAsk {
    /// Today's screen time has this many minutes left.
    Left(i64),
    /// Today's screen time is used up (or the phone can't read its record).
    UsedUp,
    /// A time rule locks: its `lock_reason` (`SCHOOL`, `BEDTIME`, `RULE`), never its name.
    Rule(String),
    /// No current report says.
    Unknown,
}

impl TimeAsk {
    /// From the phone's newest report (`time_state`, else `lock_reason`): a rule that locks first
    /// (its kind word, D3), then today's screen time - only a report of the current day counts
    /// (`time_state::screen_time`).
    pub fn of(report: &crate::time_state::TimeReport, zone: &Zone, now: DateTime<Utc>) -> TimeAsk {
        match report.lock_reason.as_str() {
            reason @ ("SCHOOL" | "BEDTIME" | "RULE") => return TimeAsk::Rule(reason.to_string()),
            "SCREEN_TIME" => return TimeAsk::UsedUp,
            _ => {}
        }
        if report.ledger_unreadable {
            return TimeAsk::UsedUp;
        }
        match crate::time_state::screen_time(report, zone, now) {
            Some(crate::time_state::ScreenTime {
                used,
                budget: Some(budget),
                ..
            }) => {
                if used >= budget {
                    TimeAsk::UsedUp
                } else {
                    TimeAsk::Left(budget - used)
                }
            }
            _ => TimeAsk::Unknown,
        }
    }
}

/// A request for more time's parameters (design r13 §2.6): the phone's name as it was when it was
/// raised, when the kid asked (RFC 3339), and where the phone stood - `state` `left`, `used_up`,
/// `rule` or `unknown`, with `left_minutes` for `left` and `rule` (the rule's `lock_reason`, no
/// name) for `rule`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ExtraTimeParams {
    pub phone: String,
    pub at: String,
    pub state: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub left_minutes: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rule: Option<String>,
}

/// The kid's request for more screen time (design r13 §2.6): key the phone's own request `id`, so
/// a retried POST gives one row; the words left to [told]. Target: Device › Time's Extra Time
/// Today, with the lift buttons in view.
pub fn extra_time_request(
    device_id: i64,
    phone: &str,
    id: &str,
    at: DateTime<Utc>,
    state: &TimeAsk,
    push: Push,
) -> NewEvent {
    let (word, left_minutes, rule) = match state {
        TimeAsk::Left(minutes) => ("left", Some(*minutes), None),
        TimeAsk::UsedUp => ("used_up", None, None),
        TimeAsk::Rule(reason) => ("rule", None, Some(reason.clone())),
        TimeAsk::Unknown => ("unknown", None, None),
    };
    let params = ExtraTimeParams {
        phone: phone.to_string(),
        at: instant(at),
        state: word.to_string(),
        left_minutes,
        rule,
    };
    NewEvent {
        device_id: Some(device_id),
        kind: Kind::ExtraTimeRequest,
        dedup_key: id.to_string(),
        title: String::new(),
        summary: String::new(),
        target: format!("/devices/{device_id}#quick"),
        detail: None,
        params: Some(serde_json::to_string(&params).expect("strings serialize")),
        push_title: String::new(),
        push_body: String::new(),
        happened_at: at,
        push,
    }
}

/// A request's words (design r13 §2.6): "Anna asks for more screen time" / "17:21 · Screen time
/// used up", "Yesterday 21:40 · Bedtime", "14:02 · 1 h 20 min left", the clock alone when the
/// phone's state was unknown. Pushed (2026-10-25): "Anna: asks for more time" / "17:21", with the
/// time left its one number ("14:02 · 1 h 20 min left").
fn extra_time_told(
    form: Form,
    p: &ExtraTimeParams,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    let lang = zone.lang();
    let at = crate::display_time::parse_db_utc(&p.at)
        .map(|at| capitalized(&zone.at(at, now)))
        .unwrap_or_default();
    let left = || {
        p.left_minutes
            .filter(|m| *m > 0 && p.state == "left")
            .map(|m| {
                t_params(
                    lang,
                    "events.extra_time_request.left",
                    &[("left", &crate::display_time::duration(lang, m))],
                )
            })
    };
    let title = titled(
        lang,
        &format!("events.extra_time_request.{}.title", form.key()),
        &p.phone,
    );
    if form == Form::Push {
        return (title, push_body(&at, left()));
    }
    let state = match p.state.as_str() {
        "left" => left(),
        "used_up" => Some(t(lang, "events.extra_time_request.used_up").to_string()),
        "rule" => Some(
            t(
                lang,
                match p.rule.as_deref() {
                    Some("SCHOOL") => "events.extra_time_request.rule.school",
                    Some("BEDTIME") => "events.extra_time_request.rule.bedtime",
                    _ => "events.extra_time_request.rule.any",
                },
            )
            .to_string(),
        ),
        _ => None,
    };
    (title, push_body(&at, state))
}

/// The request still open for the summary's "Asked for more time at 17:21" above Quick actions' tiles
/// (design r13 §2.7; on Device › Time until its Extra Time Today went, 2026-11-01):
/// when the kid asked, if the phone's newest `extra_time_request` was raised in the server zone's
/// today and no lift for the phone was made since (`time_lifts.created_at` at or after the
/// event's `created_at` - both the server's `datetime('now')`, compared through `datetime()`; any
/// lift counts, and one in the same second as the request counts as its answer).
pub async fn open_extra_time_request(
    db: &SqlitePool,
    device_id: i64,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Result<Option<DateTime<Utc>>, sqlx::Error> {
    let row: Option<String> = sqlx::query_scalar(
        "SELECT e.happened_at FROM parent_events e \
         WHERE e.device_id = ?1 AND e.kind = 'extra_time_request' \
         AND datetime(e.created_at) >= datetime(?2) \
         AND e.id = (SELECT n.id FROM parent_events n WHERE n.device_id = ?1 \
                     AND n.kind = 'extra_time_request' ORDER BY datetime(n.created_at) DESC, n.id DESC LIMIT 1) \
         AND NOT EXISTS (SELECT 1 FROM time_lifts l WHERE l.device_id = ?1 \
                         AND datetime(l.created_at) >= datetime(e.created_at))",
    )
    .bind(device_id)
    .bind(crate::push::db_time(zone.start_of_today(now)))
    .fetch_optional(db)
    .await?;
    Ok(row
        .and_then(|at| crate::display_time::parse_db_utc(&at))
        .map(|at| at.min(now)))
}

/// An app update notice's parameters (2026-10-25, migration 0099): the catalog app's id and name
/// as they were when it was raised, the version before (none known: `None`) and the new one -
/// the release's version as the Apps tab shows it -, when (RFC 3339), and for `app_updated` the
/// phone's name.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct AppUpdateParams {
    pub app_id: i64,
    pub app: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from: Option<String>,
    pub to: String,
    pub at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub phone: Option<String>,
}

/// A version is kept to this many characters in the parameters, an app's name to this many.
const VERSION_MAX: usize = 40;
const APP_NAME_MAX: usize = 40;

fn app_update_params(
    app_id: i64,
    app: &str,
    from: Option<&str>,
    to: &str,
    at: DateTime<Utc>,
    phone: Option<&str>,
) -> String {
    let clip = |text: &str, max| crate::audit::clip(text.trim(), max).to_string();
    let params = AppUpdateParams {
        app_id,
        app: clip(app, APP_NAME_MAX),
        from: from.map(|f| clip(f, VERSION_MAX)).filter(|f| !f.is_empty()),
        to: clip(to, VERSION_MAX),
        at: instant(at),
        phone: phone.map(str::to_string),
    };
    serde_json::to_string(&params).expect("strings serialize")
}

/// A new release of a catalog app was stored (2026-10-25): the catalog's event, no phone, key
/// `<app id>:<release>` (the release as the sweep stored it, `tag@asset id`), so a sweep or a
/// Check Now that finds it again raises nothing. Target: the app's page.
pub fn app_update_available(
    app_id: i64,
    app: &str,
    release: &str,
    from: Option<&str>,
    to: &str,
    at: DateTime<Utc>,
) -> NewEvent {
    NewEvent {
        device_id: None,
        kind: Kind::AppUpdateAvailable,
        dedup_key: format!("{app_id}:{release}"),
        title: String::new(),
        summary: String::new(),
        target: format!("/apps/tracked/{app_id}"),
        detail: None,
        params: Some(app_update_params(app_id, app, from, to, at, None)),
        push_title: String::new(),
        push_body: String::new(),
        happened_at: at,
        push: Push::Now,
    }
}

/// A phone's report shows a catalog app's release installed (2026-10-25): key `<app id>:<the
/// installed versionCode>`, one per phone and version. Target: the phone's Apps page.
#[allow(clippy::too_many_arguments)]
pub fn app_updated(
    device_id: i64,
    phone: &str,
    app_id: i64,
    app: &str,
    version_code: i64,
    from: Option<&str>,
    to: &str,
    at: DateTime<Utc>,
) -> NewEvent {
    NewEvent {
        device_id: Some(device_id),
        kind: Kind::AppUpdated,
        dedup_key: format!("{app_id}:{version_code}"),
        title: String::new(),
        summary: String::new(),
        target: format!("/devices/{device_id}/apps"),
        detail: None,
        params: Some(app_update_params(app_id, app, from, to, at, Some(phone))),
        push_title: String::new(),
        push_body: String::new(),
        happened_at: at,
        push: Push::Now,
    }
}

/// An app update notice's words: the inbox's "Signal 7.2.0 is available" / "14:02 · 7.1.4 →
/// 7.2.0" and "Vibb One updated on Anna" / the same, the push's "Signal: update available" /
/// "14:02 · version 7.2.0" and "Anna: Vibb One updated" / the same.
fn app_update_told(
    form: Form,
    kind: Kind,
    p: &AppUpdateParams,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    let at = crate::display_time::parse_db_utc(&p.at)
        .map(|at| capitalized(&zone.at(at, now)))
        .unwrap_or_default();
    let lang = zone.lang();
    let (app, to) = (p.app.as_str(), p.to.as_str());
    let phone = p.phone.as_deref().unwrap_or("");
    let key = format!("events.{}.{}.title", kind.wire(), form.key());
    // The catalog app's name is cut for a new release, the phone's for an installed one.
    let title = match kind {
        Kind::AppUpdateAvailable => fit_name(app, TITLE_MAX, |a| {
            t_params(lang, &key, &[("app", a), ("to", to)])
        }),
        _ => fit_name(phone, TITLE_MAX, |n| {
            t_params(lang, &key, &[("name", n), ("app", app)])
        }),
    };
    let body = match form {
        Form::Inbox => push_body(&at, p.from.as_ref().map(|from| format!("{from} → {to}"))),
        Form::Push => push_body(
            &at,
            Some(t_params(lang, "events.app_update.version", &[("to", to)])),
        ),
    };
    (crate::audit::clip(&title, TITLE_MAX).to_string(), body)
}

/// A passkey added (design 74 D1.2): the parent's name and the passkey's as they were, and when.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PasskeyParams {
    pub parent: String,
    pub passkey: String,
    pub at: String,
}

/// A parent added a passkey: no phone, key = the passkey's row id, target the Parents group
/// (where another parent can Reset them).
pub fn passkey_added(id: i64, parent: &str, passkey: &str, at: DateTime<Utc>) -> NewEvent {
    let params = PasskeyParams {
        parent: parent.to_string(),
        passkey: passkey.to_string(),
        at: instant(at),
    };
    NewEvent {
        device_id: None,
        kind: Kind::PasskeyAdded,
        dedup_key: id.to_string(),
        title: String::new(),
        summary: String::new(),
        target: "/account#parents".to_string(),
        detail: None,
        params: Some(serde_json::to_string(&params).expect("strings serialize")),
        push_title: String::new(),
        push_body: String::new(),
        happened_at: at,
        push: Push::Now,
    }
}

/// "Mamma added a passkey “iPhone”" / "14:02"; pushed "Mamma: new passkey “iPhone”".
fn passkey_told(
    form: Form,
    p: &PasskeyParams,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    let key = format!("events.passkey_added.{}.title", form.key());
    let title = fit_name(&p.parent, TITLE_MAX, |name| {
        t_params(
            zone.lang(),
            &key,
            &[("name", name), ("passkey", &p.passkey)],
        )
    });
    let at = crate::display_time::parse_db_utc(&p.at)
        .map(|at| capitalized(&zone.at(at, now)))
        .unwrap_or_default();
    (crate::audit::clip(&title, TITLE_MAX).to_string(), at)
}

/// Notifications ended on a parent's devices (2026-11-04): the parent's name and the devices' labels as they were,
/// how many, why (`push::Forget::wire`), and when.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PushOffParams {
    pub parent: String,
    pub labels: Vec<String>,
    pub reason: String,
    pub at: String,
}

/// The server ended `labels` (one or more) of a parent's subscriptions for `why`: no phone, a key of its own per
/// raise (the parent, the reason, the instant, the labels' hash), target the device list of Notification Settings.
pub fn push_off(
    admin_id: i64,
    parent: &str,
    labels: &[String],
    why: crate::push::Forget,
    at: DateTime<Utc>,
) -> NewEvent {
    let params = PushOffParams {
        parent: parent.to_string(),
        labels: labels.to_vec(),
        reason: why.wire().to_string(),
        at: instant(at),
    };
    let key = format!(
        "{admin_id}-{}-{}-{}",
        why.wire(),
        at.timestamp_millis(),
        crate::push::endpoint_hash(&labels.join("\n"))
    );
    NewEvent {
        device_id: None,
        kind: Kind::PushOff,
        dedup_key: key,
        title: String::new(),
        summary: String::new(),
        target: "/notifications/settings#devices".to_string(),
        detail: None,
        params: Some(serde_json::to_string(&params).expect("strings serialize")),
        push_title: String::new(),
        push_body: String::new(),
        happened_at: at,
        push: Push::Now,
    }
}

/// "Notifications stopped on iPhone · Safari (Mamma)" / "14:02 · Password changed"; pushed "Mamma: notifications
/// off on iPhone · Safari"; with more devices their count instead of the label.
fn push_off_told(
    form: Form,
    p: &PushOffParams,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    let lang = zone.lang();
    let count = p.labels.len().to_string();
    let title = match p.labels.as_slice() {
        [one] => {
            let key = format!("events.push_off.{}.title", form.key());
            fit_name(&p.parent, TITLE_MAX, |name| {
                t_params(lang, &key, &[("name", name), ("label", one)])
            })
        }
        _ => {
            let key = format!("events.push_off.{}.many", form.key());
            fit_name(&p.parent, TITLE_MAX, |name| {
                t_params(lang, &key, &[("name", name), ("count", &count)])
            })
        }
    };
    let reason = crate::push::Forget::of(&p.reason).map(|why| t(lang, why.key()).to_string());
    let at = crate::display_time::parse_db_utc(&p.at)
        .map(|at| capitalized(&zone.at(at, now)))
        .unwrap_or_default();
    let body = push_body(&at, reason);
    (
        crate::audit::clip(&title, TITLE_MAX).to_string(),
        crate::audit::clip(&body, SUMMARY_MAX).to_string(),
    )
}

/// How an event is worded: the inbox's long form (and Activity's), or the push's short one (the
/// shorter push texts, 2026-10-25: "<name>: <fact>" / the time and at most one number).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Form {
    Inbox,
    Push,
}

impl Form {
    /// The segment of a key: `events.<kind>.inbox.*` or `events.<kind>.push.*`.
    fn key(self) -> &'static str {
        match self {
            Form::Inbox => "inbox",
            Form::Push => "push",
        }
    }
}

/// A push's body: the time, then at most one number ("07:02 · battery 64 %").
fn push_body(at: &str, number: Option<String>) -> String {
    match number {
        Some(number) if !at.is_empty() => format!("{at} · {number}"),
        Some(number) => number,
        None => at.to_string(),
    }
}

/// An event's words as shown (the inbox, Activity), made from its parameters at `now` in `zone`
/// and its language ("Sverre arrived at Home" / "15:42", "Yesterday 21:14"; nb "I går 21:14"). A
/// row with stored words - every stored-words kind raised before design 70's slice 2 - keeps its
/// `title` and `summary`. Parameters that don't read leave them too.
pub fn told(
    kind: &str,
    title: String,
    summary: String,
    params: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    told_as(Form::Inbox, kind, title, summary, params, zone, now)
}

/// An event's words as pushed (2026-10-25): the row's stored push words when it has them (a
/// stored-words kind raised between migration 0098 and design 70's slice 2), else the short form
/// of its parameters in the subscriber's language, else - a row from before 0098 - the inbox's
/// words.
#[allow(clippy::too_many_arguments)]
pub fn pushed(
    kind: &str,
    title: String,
    summary: String,
    push_title: Option<String>,
    push_body: Option<String>,
    params: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    match push_title.filter(|t| !t.is_empty()) {
        Some(push_title) => (push_title, push_body.unwrap_or_default()),
        None => told_as(Form::Push, kind, title, summary, params, zone, now),
    }
}

/// [told] in either form.
pub fn told_as(
    form: Form,
    kind: &str,
    title: String,
    summary: String,
    params: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    let Some(kind) = Kind::of(kind) else {
        return (title, summary);
    };
    let worded = match kind {
        Kind::ExtraTimeRequest => params
            .and_then(|p| serde_json::from_str::<ExtraTimeParams>(p).ok())
            .map(|p| extra_time_told(form, &p, zone, now)),
        Kind::AppUpdateAvailable | Kind::AppUpdated => params
            .and_then(|p| serde_json::from_str::<AppUpdateParams>(p).ok())
            .map(|p| app_update_told(form, kind, &p, zone, now)),
        Kind::ZoneArrived | Kind::ZoneLeft => params
            .and_then(|p| serde_json::from_str::<ZoneParams>(p).ok())
            .map(|p| zone_told(form, kind, &p, zone, now)),
        Kind::PasskeyAdded => params
            .and_then(|p| serde_json::from_str::<PasskeyParams>(p).ok())
            .map(|p| passkey_told(form, &p, zone, now)),
        Kind::PushOff => params
            .and_then(|p| serde_json::from_str::<PushOffParams>(p).ok())
            .map(|p| push_off_told(form, &p, zone, now)),
        // An older row's words are stored (design 70 §4: one whose title is not empty).
        _ if !title.is_empty() => None,
        _ => params.and_then(|p| phone_event_told(form, kind, p, zone, now)),
    };
    worded.unwrap_or((title, summary))
}

/// An event's detail, inbox only (never pushed): a stored-words kind raised since design 70's
/// slice 2 words it from its parameters - the foreign apps by the phone's names, a shutdown's
/// last position (nothing once retention removed it); an older row shows its stored detail.
pub fn detail(
    kind: &str,
    title: &str,
    stored: Option<String>,
    params: Option<&str>,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<String> {
    if !title.is_empty() {
        return stored.filter(|d| !d.trim().is_empty());
    }
    let lang = zone.lang();
    match Kind::of(kind)? {
        Kind::ForeignAccess => {
            let p: ForeignAccessParams = serde_json::from_str(params?).ok()?;
            let mut parts = Vec::new();
            if !p.admins.is_empty() {
                parts.push(t_params(
                    lang,
                    "events.foreign_access.detail.admin",
                    &[("apps", &p.admins.join(", "))],
                ));
            }
            if !p.accessibility.is_empty() {
                parts.push(t_params(
                    lang,
                    "events.foreign_access.detail.accessibility",
                    &[("apps", &p.accessibility.join(", "))],
                ));
            }
            (!parts.is_empty()).then(|| parts.join(" "))
        }
        Kind::PhoneOff => {
            let location = off_params(params)?.location?;
            // "Last position at 21:10 – see the map in Locate" (nb "Siste posisjon kl. 21:10 – se
            // kartet i Finn": Finn the tab, never read as a verb); "Last position yesterday 21:10"
            // on another day.
            Some(match DateTime::from_timestamp_millis(location.at_ms) {
                Some(fix_at) if zone.local(fix_at).date_naive() == zone.today(now) => t_params(
                    lang,
                    "events.phone_off.detail.position_at",
                    &[("at", &zone.clock(fix_at))],
                ),
                Some(fix_at) => t_params(
                    lang,
                    "events.phone_off.detail.position_on",
                    &[("at", &zone.at(fix_at, now))],
                ),
                None => t(lang, "events.phone_off.detail.position").to_string(),
            })
        }
        _ => stored.filter(|d| !d.trim().is_empty()),
    }
}

/// A title with the phone's name in it, the name cut so the whole fits [TITLE_MAX].
fn titled(lang: Lang, key: &str, name: &str) -> String {
    let title = fit_name(name, TITLE_MAX, |n| t_params(lang, key, &[("name", n)]));
    crate::audit::clip(&title, TITLE_MAX).to_string()
}

/// A zone crossing's words (design 64 §3.4): "Anna arrived at Home" / "14:02", pushed "Anna:
/// arrived at Home".
fn zone_told(
    form: Form,
    kind: Kind,
    p: &ZoneParams,
    zone: &Zone,
    now: DateTime<Utc>,
) -> (String, String) {
    let place = crate::audit::clip(&p.zone, crate::zones::NAME_MAX).to_string();
    let key = format!("events.{}.{}.title", kind.wire(), form.key());
    let title = fit_name(&p.phone, TITLE_MAX, |name| {
        t_params(zone.lang(), &key, &[("name", name), ("zone", &place)])
    });
    let summary = crate::display_time::parse_db_utc(&p.at)
        .map(|at| capitalized(&zone.at(at, now)))
        .unwrap_or_default();
    (crate::audit::clip(&title, TITLE_MAX).to_string(), summary)
}

/// A stored-words kind's words from its parameters (design 70 §4), in the zone's language: design
/// 35 §2.2's table for the inbox, "Push wording"'s for the push. `None` when the parameters don't
/// read.
fn phone_event_told(
    form: Form,
    kind: Kind,
    params: &str,
    zone: &Zone,
    now: DateTime<Utc>,
) -> Option<(String, String)> {
    let lang = zone.lang();
    let wire = kind.wire();
    let title_key = |variant: &str| format!("events.{wire}.{}.title{variant}", form.key());
    let at_of =
        |stored: &str| crate::display_time::parse_db_utc(stored).map(|at| (at, zone.at(at, now)));
    Some(match kind {
        Kind::EmergencyCall => {
            let p: PhoneAtParams = serde_json::from_str(params).ok()?;
            let (at, when) = at_of(&p.at)?;
            let summary = match form {
                Form::Inbox if zone.is_today(at, now) => t_params(
                    lang,
                    "events.emergency_call.inbox.at_today",
                    &[("at", &when)],
                ),
                _ => capitalized(&when),
            };
            (titled(lang, &title_key(""), &p.phone), summary)
        }
        Kind::PinUsed => {
            let p: PinParams = serde_json::from_str(params).ok()?;
            let (_, when) = at_of(&p.at)?;
            let summary = match form {
                Form::Inbox => t_params(
                    lang,
                    "events.pin_used.inbox.summary",
                    &[
                        (
                            "entry",
                            t(lang, &format!("events.pin_used.entry.{}", p.entry)),
                        ),
                        ("at", &when),
                    ],
                ),
                Form::Push => capitalized(&when),
            };
            (titled(lang, &title_key(""), &p.phone), summary)
        }
        Kind::PinLockout => {
            let p: PinParams = serde_json::from_str(params).ok()?;
            let (_, at) = at_of(&p.at)?;
            let until = p
                .until
                .as_deref()
                .and_then(crate::display_time::parse_db_utc)
                .map(|until| {
                    t_params(
                        lang,
                        "events.pin_lockout.until",
                        &[("until", &zone.until(until, now))],
                    )
                })
                .unwrap_or(at);
            let summary = match form {
                Form::Inbox => t_params(
                    lang,
                    "events.pin_lockout.inbox.summary",
                    &[("when", &until)],
                ),
                Form::Push => capitalized(&until),
            };
            (titled(lang, &title_key(""), &p.phone), summary)
        }
        Kind::ForeignAccess => {
            let p: ForeignAccessParams = serde_json::from_str(params).ok()?;
            let (_, when) = at_of(&p.at)?;
            let summary = match form {
                Form::Inbox => {
                    let what = match (p.admins.is_empty(), p.accessibility.is_empty()) {
                        (false, true) => "admin",
                        (true, false) => "accessibility",
                        _ => "both",
                    };
                    t_count(
                        lang,
                        &format!("events.foreign_access.inbox.{what}"),
                        p.count,
                        &[],
                    )
                }
                Form::Push => push_body(
                    &capitalized(&when),
                    Some(t_count(
                        lang,
                        "events.foreign_access.push.apps",
                        p.count,
                        &[],
                    )),
                ),
            };
            (titled(lang, &title_key(""), &p.phone), summary)
        }
        Kind::UnseenBoot => {
            let p: PhoneAtParams = serde_json::from_str(params).ok()?;
            let (_, when) = at_of(&p.at)?;
            let summary = match form {
                Form::Inbox => t(lang, "events.unseen_boot.inbox.summary").to_string(),
                Form::Push => capitalized(&when),
            };
            (titled(lang, &title_key(""), &p.phone), summary)
        }
        Kind::LowBattery => {
            let p: LowBatteryParams = serde_json::from_str(params).ok()?;
            let (_, when) = at_of(&p.at)?;
            let level = p.level.to_string();
            match form {
                Form::Inbox => {
                    let title = fit_name(&p.phone, TITLE_MAX, |name| {
                        t_params(
                            lang,
                            "events.low_battery.inbox.title",
                            &[("name", name), ("level", &level)],
                        )
                    });
                    let state = if p.charging {
                        "events.low_battery.inbox.charging"
                    } else {
                        "events.low_battery.inbox.not_charging"
                    };
                    (
                        crate::audit::clip(&title, TITLE_MAX).to_string(),
                        t_params(lang, state, &[("at", &when)]),
                    )
                }
                Form::Push => (
                    titled(lang, "events.low_battery.push.title", &p.phone),
                    push_body(&capitalized(&when), Some(battery_level(lang, p.level))),
                ),
            }
        }
        Kind::PhoneOff => {
            let p = off_params(Some(params))?;
            let reason = OffReason::from_code(p.reason.as_deref()?)?;
            let (_, when) = at_of(&p.at)?;
            let title = titled(lang, &title_key(&format!(".{}", reason.code())), &p.phone);
            // The push: the fact, the time and the level at most (never "probably flat").
            let battery = match (form, reason, p.battery) {
                (_, _, None) => None,
                (Form::Push, OffReason::User | OffReason::Battery, Some(level)) => {
                    Some(battery_level(lang, level))
                }
                (Form::Push, _, Some(_)) => None,
                (Form::Inbox, OffReason::Battery | OffReason::Thermal, Some(level)) => {
                    Some(battery_level(lang, level))
                }
                (Form::Inbox, _, Some(level)) => Some(battery_state(
                    lang,
                    level,
                    p.charging.unwrap_or(false),
                    FLAT_LEVEL,
                )),
            };
            let mut summary = push_body(&capitalized(&when), battery);
            if form == Form::Inbox && p.late {
                summary.push_str(" · ");
                summary.push_str(t(lang, "events.phone_off.late"));
            }
            (title, summary)
        }
        Kind::PhoneOn => {
            let p: PhoneOnParams = serde_json::from_str(params).ok()?;
            let (_, when) = at_of(&p.at)?;
            let variant = if p.start == "restart" {
                ".restart"
            } else {
                ".on"
            };
            let title = titled(lang, &title_key(variant), &p.phone);
            if form == Form::Push {
                // The start, its time and the battery now (never the off's, "no shutdown seen",
                // the last report or the unlock - the inbox keeps those).
                return Some((
                    title,
                    push_body(
                        &capitalized(&when),
                        p.battery.map(|level| battery_level(lang, level)),
                    ),
                ));
            }
            let mut parts = vec![capitalized(&when)];
            if let Some((_, unlocked)) = p.unlocked.as_deref().and_then(at_of) {
                parts.push(t_params(
                    lang,
                    "events.phone_on.unlocked",
                    &[("at", &unlocked)],
                ));
            }
            match p.start.as_str() {
                "restart" => {
                    if let Some(level) = p.battery {
                        parts.push(battery_level(lang, level));
                    }
                }
                "after_off" => {
                    let (_, off_at) = at_of(p.off_at.as_deref()?)?;
                    parts.push(match p.off_battery {
                        Some(level) => t_params(
                            lang,
                            "events.phone_on.off_since_battery",
                            &[("at", &off_at), ("level", &level.to_string())],
                        ),
                        None => t_params(lang, "events.phone_on.off_since", &[("at", &off_at)]),
                    });
                }
                _ => {
                    parts.push(t(lang, "events.phone_on.no_shutdown").to_string());
                    if let Some((_, last_at)) = p.last_at.as_deref().and_then(at_of) {
                        parts.push(match p.last_battery {
                            Some(level) => t_params(
                                lang,
                                "events.phone_on.last_report_battery",
                                &[
                                    ("at", &last_at),
                                    (
                                        "battery",
                                        &battery_state(
                                            lang,
                                            level,
                                            p.last_charging == Some(true),
                                            LAST_REPORT_FLAT_LEVEL,
                                        ),
                                    ),
                                ],
                            ),
                            None => {
                                t_params(lang, "events.phone_on.last_report", &[("at", &last_at)])
                            }
                        });
                    }
                }
            }
            (title, parts.join(" · "))
        }
        _ => return None,
    })
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

    /// An event's inbox words as a reader sees them, in Anna's zone (English) at her `now`.
    fn inbox(phone: &Phone, event: &NewEvent) -> (String, String) {
        told(
            event.kind.wire(),
            event.title.clone(),
            event.summary.clone(),
            event.params.as_deref(),
            &phone.zone,
            phone.now,
        )
    }

    fn inbox_detail(phone: &Phone, event: &NewEvent) -> Option<String> {
        detail(
            event.kind.wire(),
            &event.title,
            event.detail.clone(),
            event.params.as_deref(),
            &phone.zone,
            phone.now,
        )
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
            location: None,
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
            let (told_title, told_summary) = inbox(&phone, &event);
            assert_eq!(
                (told_title.as_str(), told_summary.as_str(), event.push),
                (title, summary, push),
            );
            assert!(event.title.is_empty() && event.summary.is_empty());
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
            let (told_title, told_summary) = inbox(&phone, &event);
            assert_eq!(told_summary, summary);
            let title = if how == Start::Restart {
                "Anna restarted"
            } else {
                "Anna was turned on"
            };
            assert_eq!((told_title.as_str(), event.push), (title, Push::Now));
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
                inbox(&phone, &phone.phone_on(&on, start, &how)).1,
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
            assert_eq!(battery_state(Lang::En, level, false, flat_level), words);
        }
        assert_eq!(OffReason::of(None), OffReason::User);
        assert_eq!(OffReason::of(Some("Battery")), OffReason::User);
        assert_eq!(OffReason::of(Some("thermal")), OffReason::Thermal);
        for reason in [
            OffReason::User,
            OffReason::Battery,
            OffReason::Thermal,
            OffReason::Restart,
        ] {
            assert_eq!(OffReason::from_code(reason.code()), Some(reason));
        }
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
            location: None,
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
        assert!(
            inbox(&phone, &start.events[0])
                .1
                .contains("no shutdown seen")
        );

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
            assert_eq!(inbox(&phone, &plan.events[1]).0, "Anna restarted");
            assert!(
                inbox(&phone, &plan.events[0])
                    .1
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
            assert_eq!(inbox(&phone, &plan.events[1]).0, "Anna was turned on");
            assert!(
                inbox(&phone, &plan.events[1])
                    .1
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

    /// Design 38 §3.4's line from a `phone_off`'s parameters (design 70), or from an older
    /// row's stored words; nothing while the push is held or for a restart - told either way.
    #[test]
    fn the_off_since_line() {
        let phone = anna();
        let at = utc("2026-10-17 19:14:00");
        let arrived = phone.now - OFF_HOLD;
        let b = |level, charging| Some(battery(level, charging, false));
        let line = |event: &NewEvent, created: DateTime<Utc>, zone: &Zone| {
            off_since(
                at,
                created,
                &event.title,
                &event.summary,
                event.params.as_deref(),
                phone.now,
            )
            .map(|off| off.text(zone, phone.now))
        };
        let flat = phone.phone_off(&off(b(3, false), None, None), at, false);
        assert_eq!(
            line(&flat, arrived, &phone.zone).as_deref(),
            Some("Turned off yesterday 21:14 · battery was 3 %")
        );
        assert_eq!(
            line(&flat, arrived, &phone.zone.in_lang(Lang::Nb)).as_deref(),
            Some("Slått av i går 21:14 · batteriet var 3 %")
        );
        let no_battery = phone.phone_off(&off(None, None, None), at, true);
        assert_eq!(
            line(&no_battery, arrived, &phone.zone).as_deref(),
            Some("Turned off yesterday 21:14")
        );
        // Not while the push is held (a restart's start may still fold it), not for a restart.
        let held = phone.now - OFF_HOLD + chrono::Duration::seconds(1);
        assert_eq!(line(&flat, held, &phone.zone), None);
        let restart = phone.phone_off(&off(b(64, true), Some(true), None), at, false);
        assert_eq!(line(&restart, arrived, &phone.zone), None);
        assert!(!off_said("", restart.params.as_deref(), arrived, phone.now));
        assert!(off_said("", flat.params.as_deref(), arrived, phone.now));
        // An older row (its words stored, its parameters the location at most): the text is read.
        let older = |title: &str, summary: &str| NewEvent {
            title: title.to_string(),
            summary: summary.to_string(),
            params: Some(r#"{"location":{"lat":1.0,"lng":2.0,"at_ms":1}}"#.to_string()),
            ..flat.clone()
        };
        let off = "Anna was turned off";
        assert_eq!(
            line(
                &older(off, "21:14 · battery 3 %, probably flat"),
                arrived,
                &phone.zone
            )
            .as_deref(),
            Some("Turned off yesterday 21:14 · battery was 3 %")
        );
        assert_eq!(
            line(
                &older(off, "Yesterday 21:14 · told at the next start"),
                arrived,
                &phone.zone
            )
            .as_deref(),
            Some("Turned off yesterday 21:14")
        );
        assert_eq!(
            line(&older(off, "21:14 · battery x %"), arrived, &phone.zone).as_deref(),
            Some("Turned off yesterday 21:14")
        );
        assert_eq!(
            line(
                &older("Anna is restarting", "21:14 · battery 3 %"),
                arrived,
                &phone.zone
            ),
            None
        );
        // The older row's words stay its own, in any language.
        let (title, summary) = told(
            "phone_off",
            off.to_string(),
            "21:14 · battery 3 %".to_string(),
            None,
            &phone.zone.in_lang(Lang::Nb),
            phone.now,
        );
        assert_eq!(
            (title.as_str(), summary.as_str()),
            (off, "21:14 · battery 3 %")
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
            // Told from their parameters (design 64): nothing stored as words.
            (
                zone_crossing(3, "Anna", 5, "Home", true, two, Push::Now),
                "",
                "",
                "/devices/locate?device=3#location",
            ),
            (
                zone_crossing(3, "Anna", 6, "School", false, two, Push::No),
                "",
                "",
                "/devices/locate?device=3#location",
            ),
            (
                phone.low_battery(&battery(14, false, true), two),
                "Battery low on Anna (14 %)",
                "Not charging · 14:02",
                "/devices/3",
            ),
            // Design r13: told from its parameters too.
            (
                extra_time_request(3, "Anna", "ab12", two, &TimeAsk::UsedUp, Push::Now),
                "",
                "",
                "/devices/3#quick",
            ),
            // App update notices (2026-10-25): told from their parameters too.
            (
                app_update_available(9, "Signal", "v7.2.0@41", Some("7.1.4"), "7.2.0", two),
                "",
                "",
                "/apps/tracked/9",
            ),
            (
                app_updated(3, "Anna", 1, "Vibb One", 360_009, None, "0.36.0", two),
                "",
                "",
                "/devices/3/apps",
            ),
            // Design 74 D1.2: no phone, told from its parameters.
            (
                passkey_added(7, "Mamma", "iPhone", two),
                "Mamma added a passkey “iPhone”",
                "14:02",
                "/account#parents",
            ),
            // Push never lost silently (2026-11-04): no phone, told from its parameters.
            (
                push_off(
                    7,
                    "Mamma",
                    &["iPhone · Safari".to_string()],
                    crate::push::Forget::PasskeyOnly,
                    two,
                ),
                "Notifications stopped on iPhone · Safari (Mamma)",
                "14:02 · Passkey Only turned on",
                "/notifications/settings#devices",
            ),
        ];
        for (event, title, summary, target) in &rows {
            // Nothing is stored as words any more (design 70 §4): every kind is told from its
            // parameters, here in English - "" where the table has no inbox words to pin.
            assert!(
                event.title.is_empty() && event.summary.is_empty() && event.params.is_some(),
                "{:?}",
                event.kind
            );
            if !title.is_empty() {
                let (told_title, told_summary) = inbox(&phone, event);
                assert_eq!(
                    (told_title.as_str(), told_summary.as_str()),
                    (*title, *summary),
                    "{:?}",
                    event.kind
                );
            }
            assert_eq!(event.target.as_str(), *target, "{:?}", event.kind);
            assert!(local_target(&event.target));
        }
        let kinds: Vec<Kind> = rows.iter().map(|(e, ..)| e.kind).collect();
        assert_eq!(kinds, Kind::ALL);
        // Only the foreign apps have a detail, by the phone's names - told from the parameters.
        assert_eq!(
            inbox_detail(&phone, &rows[3].0).as_deref(),
            Some("Device admin: Helper.")
        );
        assert!(rows.iter().all(|(e, ..)| e.detail.is_none()));
        assert_eq!(
            rows.iter()
                .filter(|(e, ..)| inbox_detail(&phone, e).is_some())
                .count(),
            1
        );
        // The keys (§2.1).
        assert_eq!(rows[0].0.dedup_key, "2026-10-18T12:02:00.000Z");
        assert_eq!(rows[1].0.dedup_key, "0123456789abcdef0123456789abcdef");
        assert!(rows[3].0.dedup_key.starts_with("7:") && rows[3].0.dedup_key.len() == 18);
        assert_eq!(rows[4].0.dedup_key, format!("2:{}", two.timestamp_millis()));
        assert_eq!(rows[5].0.dedup_key, "2026-10-18T12:02:00.000Z");
        assert_eq!(rows[6].0.dedup_key, "boot:2026-10-01 08:00:00:42");
        assert_eq!(rows[7].0.dedup_key, "5:inside:2026-10-18T12:02:00.000Z");
        assert_eq!(rows[8].0.dedup_key, "6:outside:2026-10-18T12:02:00.000Z");
        assert_eq!(rows[9].0.dedup_key, "2026-10-18T12:02:00.000Z");
    }

    /// The shorter push texts (2026-10-25, `docs/notes/server/notifications.md` "Push wording"):
    /// every kind's push is "<name>: <fact>" / the time and at most one number, while the inbox
    /// keeps its long words. One row per wording, the note's table.
    #[test]
    fn the_push_words_are_short() {
        let phone = anna();
        let zone = &phone.zone;
        let two = utc("2026-10-18 12:02:00");
        let yesterday = utc("2026-10-17 17:14:00");
        let b = |level, charging| Some(battery(level, charging, false));
        let two_apps = ForeignAccess {
            admins: vec!["org.example.spy".to_string()],
            accessibility: vec!["org.example.reader".to_string()],
        };
        let last = LastReport {
            at: utc("2026-10-18 12:00:00"),
            level: Some(5),
            charging: Some(false),
        };
        let mut late_unlock = on(b(64, false));
        late_unlock.unlocked_at_ms = Some(utc("2026-10-18 12:23:00").timestamp_millis());
        let pushed_of = |event: &NewEvent| {
            pushed(
                event.kind.wire(),
                event.title.clone(),
                event.summary.clone(),
                Some(event.push_title.clone()),
                Some(event.push_body.clone()),
                event.params.as_deref(),
                zone,
                phone.now,
            )
        };
        let rows = [
            (phone.emergency_call(two), "Anna: emergency call", "14:02"),
            (
                phone.emergency_call(yesterday),
                "Anna: emergency call",
                "Yesterday 19:14",
            ),
            (
                phone
                    .pin_event(&pin(
                        PinKind::Unlocked,
                        Entry::Pause,
                        "2026-10-18 12:02:00",
                        None,
                    ))
                    .unwrap(),
                "Anna: parent PIN used",
                "14:02",
            ),
            (
                phone
                    .pin_event(&pin(
                        PinKind::Lockout,
                        Entry::PinLock,
                        "2026-10-18 12:15:00",
                        Some("2026-10-18 12:45:00"),
                    ))
                    .unwrap(),
                "Anna: parent PIN locked",
                "Until 14:45",
            ),
            (
                phone.foreign_access(&two_apps, "{}", 7, &Default::default()),
                "Anna: an app may read the PIN",
                "14:30 · 2 apps",
            ),
            (
                phone.unseen_boot(2, Some(two.timestamp_millis())),
                "Anna: started without Vibb One",
                "14:02",
            ),
            (
                phone.low_battery(&battery(14, false, true), two),
                "Anna: battery low",
                "14:02 · battery 14 %",
            ),
            (
                phone.phone_off(&off(b(3, false), None, None), two, false),
                "Anna: turned off",
                "14:02 · battery 3 %",
            ),
            (
                phone.phone_off(&off(b(1, false), None, Some("battery")), two, false),
                "Anna: battery flat, shut down",
                "14:02 · battery 1 %",
            ),
            (
                phone.phone_off(&off(b(64, false), None, Some("thermal")), two, false),
                "Anna: too hot, shut down",
                "14:02",
            ),
            (
                phone.phone_off(&off(b(64, true), Some(true), None), two, false),
                "Anna: restarting",
                "14:02",
            ),
            (
                phone.phone_on(
                    &on(b(64, false)),
                    utc("2026-10-18 12:20:00"),
                    &Start::Restart,
                ),
                "Anna: restarted",
                "14:20 · battery 64 %",
            ),
            (
                phone.phone_on(
                    &late_unlock,
                    utc("2026-10-18 12:20:00"),
                    &Start::AfterOff {
                        off: off(b(3, false), None, None),
                        at: yesterday,
                    },
                ),
                "Anna: turned on",
                "14:20 · battery 64 %",
            ),
            (
                phone.phone_on(
                    &on(None),
                    utc("2026-10-18 12:20:00"),
                    &Start::NoShutdownSeen(Some(last)),
                ),
                "Anna: turned on",
                "14:20",
            ),
            (
                zone_crossing(3, "Anna", 5, "Home", true, two, Push::Now),
                "Anna: arrived at Home",
                "14:02",
            ),
            (
                zone_crossing(3, "Anna", 6, "School", false, two, Push::Now),
                "Anna: left School",
                "14:02",
            ),
            (
                extra_time_request(3, "Anna", "ab12", two, &TimeAsk::UsedUp, Push::Now),
                "Anna: asks for more time",
                "14:02",
            ),
            (
                extra_time_request(3, "Anna", "ab13", two, &TimeAsk::Left(80), Push::Now),
                "Anna: asks for more time",
                "14:02 · 1 h 20 min left",
            ),
            (
                extra_time_request(
                    3,
                    "Anna",
                    "ab14",
                    two,
                    &TimeAsk::Rule("BEDTIME".into()),
                    Push::Now,
                ),
                "Anna: asks for more time",
                "14:02",
            ),
            (
                app_update_available(9, "Signal", "v7.2.0@41", Some("7.1.4"), "7.2.0", two),
                "Signal: update available",
                "14:02 · version 7.2.0",
            ),
            (
                app_updated(
                    3,
                    "Anna",
                    1,
                    "Vibb One",
                    360_009,
                    Some("0.36.0-rc.7"),
                    "0.36.0",
                    two,
                ),
                "Anna: Vibb One updated",
                "14:02 · version 0.36.0",
            ),
        ];
        for (event, title, body) in &rows {
            let (t, b) = pushed_of(event);
            assert_eq!(
                (t.as_str(), b.as_str()),
                (*title, *body),
                "{:?}",
                event.kind
            );
            assert!(t.chars().count() <= TITLE_MAX);
            // The inbox's words are untouched (zones and requests are told there in full).
            let (inbox, _) = told(
                event.kind.wire(),
                event.title.clone(),
                event.summary.clone(),
                event.params.as_deref(),
                zone,
                phone.now,
            );
            assert_ne!(inbox, t, "{:?}", event.kind);
        }
        // The same rows in Norwegian (design 70 §8: the nb column) - the push's short form, never
        // the inbox's long one ("14:02 · batteri 3 %", not "…, trolig tomt").
        let nb = zone.in_lang(Lang::Nb);
        let nb_rows = [
            ("Anna: nødanrop", "14:02"),
            ("Anna: nødanrop", "I går 19:14"),
            ("Anna: foreldre-PIN brukt", "14:02"),
            ("Anna: foreldre-PIN sperret", "Til 14:45"),
            ("Anna: en app kan lese PIN-koden", "14:30 · 2 apper"),
            ("Anna: startet uten Vibb One", "14:02"),
            ("Anna: lite batteri", "14:02 · batteri 14 %"),
            ("Anna: slått av", "14:02 · batteri 3 %"),
            ("Anna: tomt batteri, slått av", "14:02 · batteri 1 %"),
            ("Anna: for varm, slått av", "14:02"),
            ("Anna: starter på nytt", "14:02"),
            ("Anna: startet på nytt", "14:20 · batteri 64 %"),
            ("Anna: slått på", "14:20 · batteri 64 %"),
            ("Anna: slått på", "14:20"),
            ("Anna: kom til Home", "14:02"),
            ("Anna: dro fra School", "14:02"),
            ("Anna: ber om mer tid", "14:02"),
            ("Anna: ber om mer tid", "14:02 · 1 t 20 min igjen"),
            ("Anna: ber om mer tid", "14:02"),
            ("Signal: oppdatering tilgjengelig", "14:02 · versjon 7.2.0"),
            ("Anna: Vibb One oppdatert", "14:02 · versjon 0.36.0"),
        ];
        assert_eq!(rows.len(), nb_rows.len());
        for ((event, ..), (title, body)) in rows.iter().zip(nb_rows) {
            let (t, b) = pushed(
                event.kind.wire(),
                event.title.clone(),
                event.summary.clone(),
                Some(event.push_title.clone()),
                Some(event.push_body.clone()),
                event.params.as_deref(),
                &nb,
                phone.now,
            );
            assert_eq!((t.as_str(), b.as_str()), (title, body), "{:?}", event.kind);
        }
        // A long name is cut, the fact stays.
        let long = Phone {
            name: "Anna-Sofie ".repeat(8),
            ..anna()
        };
        let (title, _) = pushed_of(&long.emergency_call(two));
        assert!(title.chars().count() <= TITLE_MAX, "{title}");
        assert!(title.ends_with("…: emergency call"), "{title}");
        // A row from before migration 0098 (no push words) pushes its inbox words.
        assert_eq!(
            pushed(
                "low_battery",
                "t".into(),
                "s".into(),
                None,
                None,
                None,
                zone,
                phone.now
            ),
            ("t".to_string(), "s".to_string())
        );
    }

    #[test]
    fn a_zone_event_is_told_from_its_parameters() {
        let phone = anna();
        let zone = &phone.zone;
        let two = utc("2026-10-18 12:02:00");
        let arrived = zone_crossing(3, "Anna", 5, "Home", true, two, Push::Now);
        let params: ZoneParams = serde_json::from_str(arrived.params.as_deref().unwrap()).unwrap();
        assert_eq!(
            params,
            ZoneParams {
                zone_id: 5,
                zone: "Home".to_string(),
                phone: "Anna".to_string(),
                direction: "arrived".to_string(),
                at: "2026-10-18T12:02:00.000Z".to_string(),
            }
        );
        assert_eq!(arrived.push, Push::Now);
        let told_at = |event: &NewEvent, now| {
            told(
                event.kind.wire(),
                event.title.clone(),
                event.summary.clone(),
                event.params.as_deref(),
                zone,
                now,
            )
        };
        assert_eq!(
            told_at(&arrived, phone.now),
            ("Anna arrived at Home".to_string(), "14:02".to_string())
        );
        // The time is told when it is read: the next day it is yesterday's.
        assert_eq!(
            told_at(&arrived, utc("2026-10-19 09:00:00")).1,
            "Yesterday 14:02"
        );
        let left = zone_crossing(3, "Anna", 6, "School", false, two, Push::No);
        assert_eq!(told_at(&left, phone.now).0, "Anna left School");
        // A long phone name is cut so the title fits; the zone's name stays.
        let long_name = "Anna-Sofie ".repeat(8);
        let long = zone_crossing(3, &long_name, 5, "Bestemor", true, two, Push::Now);
        let (title, _) = told_at(&long, phone.now);
        assert!(title.chars().count() <= TITLE_MAX, "{title}");
        assert!(title.ends_with("… arrived at Bestemor"), "{title}");
        // Another kind keeps its stored words; parameters that don't read too.
        assert_eq!(
            told("low_battery", "t".into(), "s".into(), None, zone, phone.now),
            ("t".to_string(), "s".to_string())
        );
        assert_eq!(
            told(
                "zone_left",
                "t".into(),
                "s".into(),
                Some("{"),
                zone,
                phone.now
            ),
            ("t".to_string(), "s".to_string())
        );
    }

    /// Design r13 §2.6: a request for more time is told from its parameters - each state's words,
    /// a long name cut to 60, the day after; its key is the phone's id, its target `#quick`.
    #[test]
    fn a_request_for_more_time_is_told_from_its_parameters() {
        let phone = anna();
        let zone = &phone.zone;
        let at = utc("2026-10-18 15:21:00"); // 17:21 in Oslo
        let tell = |name: &str, state: TimeAsk, now: DateTime<Utc>| {
            let event = extra_time_request(3, name, "ab12", at, &state, Push::Now);
            told(
                "extra_time_request",
                event.title,
                event.summary,
                event.params.as_deref(),
                zone,
                now,
            )
        };
        let later = utc("2026-10-18 15:30:00");
        assert_eq!(
            tell("Anna", TimeAsk::UsedUp, later),
            (
                "Anna asks for more screen time".to_string(),
                "17:21 · Screen time used up".to_string()
            )
        );
        assert_eq!(
            tell("Anna", TimeAsk::Left(80), later).1,
            "17:21 · 1 h 20 min left"
        );
        assert_eq!(
            tell("Anna", TimeAsk::Left(45), later).1,
            "17:21 · 45 min left"
        );
        assert_eq!(
            tell("Anna", TimeAsk::Rule("SCHOOL".into()), later).1,
            "17:21 · School"
        );
        assert_eq!(
            tell("Anna", TimeAsk::Rule("BEDTIME".into()), later).1,
            "17:21 · Bedtime"
        );
        assert_eq!(
            tell("Anna", TimeAsk::Rule("RULE".into()), later).1,
            "17:21 · A time rule"
        );
        assert_eq!(tell("Anna", TimeAsk::Unknown, later).1, "17:21");
        // The day after, the clock with its day.
        assert_eq!(
            tell(
                "Anna",
                TimeAsk::Rule("BEDTIME".into()),
                utc("2026-10-19 08:00:00")
            )
            .1,
            "Yesterday 17:21 · Bedtime"
        );
        // A long name is cut so the title fits 60.
        let long = "Anna Sofie Margrethe Kristine Olsen-Haugen Nordby";
        let title = tell(long, TimeAsk::UsedUp, later).0;
        assert!(title.chars().count() <= TITLE_MAX, "{title}");
        assert!(title.ends_with("… asks for more screen time"), "{title}");
        // The event's own parts.
        let event = extra_time_request(3, "Anna", "ab12", at, &TimeAsk::UsedUp, Push::No);
        assert_eq!(event.kind, Kind::ExtraTimeRequest);
        assert_eq!(event.dedup_key, "ab12");
        assert_eq!(event.target, "/devices/3#quick");
        assert!(event.title.is_empty() && event.summary.is_empty());
        assert_eq!(Kind::of("extra_time_request"), Some(Kind::ExtraTimeRequest));
        assert!(!Kind::ExtraTimeRequest.urgent());
        assert_eq!(Kind::ExtraTimeRequest.ttl(), 3_600);
        assert_eq!(Kind::ExtraTimeRequest.urgency(), "normal");
        assert_eq!(
            Kind::ExtraTimeRequest.switch(),
            Some(("push_extra_time", "Asks for more time"))
        );
        // After it in `Kind::ALL` only the app update notices (2026-10-25).
        assert_eq!(Kind::ALL[10], Kind::ExtraTimeRequest);
        // Parameters that don't read keep the stored words.
        assert_eq!(
            told(
                "extra_time_request",
                "t".into(),
                "s".into(),
                Some("{"),
                zone,
                later
            ),
            ("t".to_string(), "s".to_string())
        );
    }

    /// Where the phone stood, from its report: a rule first (its kind), then today's screen time.
    #[test]
    fn a_requests_state_comes_from_the_report() {
        use crate::time_state::TimeReport;
        let phone = anna();
        let today = phone.zone.today(phone.now);
        let report = |reason: &str, used: Option<i64>, budget: Option<i64>| TimeReport {
            lock_reason: reason.to_string(),
            day: Some(today),
            used_minutes: used,
            budget_minutes: budget,
            ..Default::default()
        };
        let of = |r: TimeReport| TimeAsk::of(&r, &phone.zone, phone.now);
        assert_eq!(of(report("NONE", Some(10), Some(90))), TimeAsk::Left(80));
        assert_eq!(of(report("NONE", Some(90), Some(90))), TimeAsk::UsedUp);
        assert_eq!(
            of(report("SCREEN_TIME", Some(95), Some(90))),
            TimeAsk::UsedUp
        );
        assert_eq!(
            of(report("BEDTIME", Some(10), Some(90))),
            TimeAsk::Rule("BEDTIME".into())
        );
        assert_eq!(of(report("NONE", Some(10), None)), TimeAsk::Unknown);
        assert_eq!(of(report("NONE", None, None)), TimeAsk::Unknown);
        let unreadable = TimeReport {
            ledger_unreadable: true,
            ..report("NONE", Some(0), Some(90))
        };
        assert_eq!(of(unreadable), TimeAsk::UsedUp);
        // Yesterday's report says nothing about today's time.
        let old = TimeReport {
            day: today.pred_opt(),
            ..report("NONE", Some(10), Some(90))
        };
        assert_eq!(of(old), TimeAsk::Unknown);
    }

    /// App update notices (2026-10-25): the inbox's words from the parameters - the version step,
    /// none without a known "from" -, the keys (one per release, one per phone and version) and
    /// Activity's strip of the row's own clock.
    #[test]
    fn an_app_update_is_told_from_its_parameters() {
        let phone = anna();
        let zone = &phone.zone;
        let two = utc("2026-10-18 12:02:00");
        let inbox = |event: &NewEvent| {
            told(
                event.kind.wire(),
                event.title.clone(),
                event.summary.clone(),
                event.params.as_deref(),
                zone,
                phone.now,
            )
        };
        let available = app_update_available(9, "Signal", "v7.2.0@41", Some("7.1.4"), "7.2.0", two);
        assert_eq!(
            (available.device_id, available.dedup_key.as_str()),
            (None, "9:v7.2.0@41")
        );
        assert_eq!(
            inbox(&available),
            (
                "Signal 7.2.0 is available".to_string(),
                "14:02 · 7.1.4 → 7.2.0".to_string()
            )
        );
        let first = app_update_available(9, "Signal", "v7.2.0@41", None, "7.2.0", two);
        assert_eq!(inbox(&first).1, "14:02");
        let updated = app_updated(
            3,
            "Anna",
            1,
            "Vibb One",
            360_009,
            Some("0.36.0-rc.7"),
            "0.36.0",
            two,
        );
        assert_eq!(
            (updated.device_id, updated.dedup_key.as_str()),
            (Some(3), "1:360009")
        );
        let (title, summary) = inbox(&updated);
        assert_eq!(title, "Vibb One updated on Anna");
        assert_eq!(summary, "14:02 · 0.36.0-rc.7 → 0.36.0");
        assert_eq!(
            crate::handlers::activity::without_own_clock(&summary, "14:02"),
            "0.36.0-rc.7 → 0.36.0"
        );
        // A long phone name is cut so the title fits.
        let long = app_updated(
            3,
            &"Anna-Sofie ".repeat(8),
            1,
            "Vibb One",
            9,
            None,
            "1",
            two,
        );
        let (title, _) = inbox(&long);
        assert!(title.chars().count() <= TITLE_MAX, "{title}");
        assert!(title.starts_with("Vibb One updated on Anna"), "{title}");
    }

    #[test]
    fn two_kinds_share_the_zones_switch() {
        let switches = Kind::switches();
        let zones = switches.iter().filter(|(name, _)| *name == "push_zones");
        assert_eq!(zones.count(), 1);
        assert_eq!(
            switches.len(),
            10,
            "every switch once; the emergency call has none"
        );
        // "App updates": one switch for both kinds, the only one off by default.
        assert_eq!(Kind::AppUpdateAvailable.switch(), Kind::AppUpdated.switch());
        let off: Vec<&str> = switches
            .iter()
            .map(|(name, _)| *name)
            .filter(|name| !Kind::switch_default(name))
            .collect();
        assert_eq!(off, [APP_UPDATES_SWITCH]);
        assert_eq!(Kind::ZoneArrived.switch(), Kind::ZoneLeft.switch());
        assert!(!Kind::ZoneArrived.urgent() && Kind::ZoneLeft.ttl() == 3_600);
    }

    #[test]
    fn other_days_long_names_and_variants() {
        let mut phone = anna();
        assert_eq!(
            inbox(&phone, &phone.emergency_call(utc("2026-10-17 19:40:00"))).1,
            "Yesterday 21:40"
        );
        assert_eq!(
            inbox(&phone, &phone.emergency_call(utc("2026-10-12 06:12:00"))).1,
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
            inbox(&phone, &phone.pin_event(&over).unwrap()).1,
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
        assert_eq!(
            inbox(&phone, &event).1,
            "2 apps with device admin or accessibility"
        );
        assert_eq!(
            inbox_detail(&phone, &event).as_deref(),
            Some("Device admin: a.b. Accessibility: a.b, c.d.")
        );
        assert_eq!(
            inbox(
                &phone,
                &phone.low_battery(&battery(9, true, false), phone.now)
            )
            .1,
            "Charging · 14:30"
        );
        // A 40-character name is cut so the title fits, the level kept.
        phone.name = "Annabelle-Sofie Marie Kristiansen Olsen!".to_string();
        let title = inbox(
            &phone,
            &phone.low_battery(&battery(14, false, true), phone.now),
        )
        .0;
        assert_eq!(title.chars().count(), TITLE_MAX);
        assert!(title.starts_with("Battery low on Annabelle") && title.ends_with("… (14 %)"));
        for event in [
            phone.emergency_call(phone.now),
            phone.unseen_boot(1, None),
            phone.foreign_access(&both, "x", 0, &Default::default()),
        ] {
            let (title, _) = inbox(&phone, &event);
            assert!(title.chars().count() <= TITLE_MAX, "{title}");
        }
    }

    /// Design 70 §8: every kind, each of its variants, in both forms and both languages - worded
    /// (no key shows), within `TITLE_MAX`/`SUMMARY_MAX` with a 20-character name, and the two
    /// languages' titles differ (nothing left in English on an nb row).
    #[test]
    fn every_kind_is_worded_in_both_languages() {
        let phone = Phone {
            name: "Anna-Sofie Kristians".to_string(),
            ..anna()
        };
        assert_eq!(phone.name.chars().count(), 20);
        let two = utc("2026-10-18 12:02:00");
        let yesterday = utc("2026-10-17 17:14:00");
        let b = |level, charging| Some(battery(level, charging, false));
        let two_apps = ForeignAccess {
            admins: vec!["org.example.spy".to_string()],
            accessibility: vec!["org.example.reader".to_string()],
        };
        let last = LastReport {
            at: utc("2026-10-18 12:00:00"),
            level: Some(5),
            charging: Some(false),
        };
        let mut late_unlock = on(b(64, false));
        late_unlock.unlocked_at_ms = Some(utc("2026-10-18 12:23:00").timestamp_millis());
        let events = [
            phone.emergency_call(two),
            phone.emergency_call(yesterday),
            phone
                .pin_event(&pin(
                    PinKind::Unlocked,
                    Entry::Pause,
                    "2026-10-18 12:02:00",
                    None,
                ))
                .unwrap(),
            phone
                .pin_event(&pin(
                    PinKind::Lockout,
                    Entry::PinLock,
                    "2026-10-18 12:15:00",
                    Some("2026-10-18 12:45:00"),
                ))
                .unwrap(),
            phone
                .pin_event(&pin(
                    PinKind::Lockout,
                    Entry::Settings,
                    "2026-10-18 10:00:00",
                    Some("2026-10-18 10:15:00"),
                ))
                .unwrap(),
            phone.foreign_access(&two_apps, "{}", 7, &Default::default()),
            phone.unseen_boot(2, Some(two.timestamp_millis())),
            phone.low_battery(&battery(14, false, true), two),
            phone.low_battery(&battery(9, true, false), two),
            phone.phone_off(&off(b(3, false), None, None), two, false),
            phone.phone_off(&off(b(1, false), None, Some("battery")), two, false),
            phone.phone_off(&off(b(64, false), None, Some("thermal")), two, false),
            phone.phone_off(&off(b(64, true), Some(true), None), two, false),
            phone.phone_off(&off(None, None, None), two, true),
            phone.phone_on(&on(b(64, false)), two, &Start::Restart),
            phone.phone_on(
                &late_unlock,
                two,
                &Start::AfterOff {
                    off: off(b(3, false), None, None),
                    at: yesterday,
                },
            ),
            phone.phone_on(&on(None), two, &Start::NoShutdownSeen(Some(last))),
            phone.phone_on(&on(None), two, &Start::NoShutdownSeen(None)),
            zone_crossing(3, &phone.name, 5, "Home", true, two, Push::Now),
            zone_crossing(3, &phone.name, 6, "School", false, two, Push::Now),
            extra_time_request(3, &phone.name, "ab12", two, &TimeAsk::UsedUp, Push::Now),
            extra_time_request(3, &phone.name, "ab13", two, &TimeAsk::Left(80), Push::Now),
            extra_time_request(
                3,
                &phone.name,
                "ab14",
                two,
                &TimeAsk::Rule("BEDTIME".into()),
                Push::Now,
            ),
            app_update_available(9, "Signal", "v7.2.0@41", Some("7.1.4"), "7.2.0", two),
            app_updated(3, &phone.name, 1, "Vibb One", 360_009, None, "0.36.0", two),
            passkey_added(7, &phone.name, "Security key", two),
            push_off(
                7,
                &phone.name,
                &["iPhone · Safari".to_string()],
                crate::push::Forget::Gone,
                two,
            ),
            push_off(
                7,
                &phone.name,
                &[
                    "iPhone · Safari".to_string(),
                    "Android · Chrome".to_string(),
                ],
                crate::push::Forget::ReauthLockout,
                two,
            ),
        ];
        let kinds: std::collections::BTreeSet<&str> =
            events.iter().map(|e| e.kind.wire()).collect();
        assert_eq!(kinds.len(), Kind::ALL.len(), "every kind");
        for event in &events {
            let mut titles = Vec::new();
            for lang in Lang::ALL {
                let zone = phone.zone.in_lang(lang);
                for form in [Form::Inbox, Form::Push] {
                    let (title, summary) = told_as(
                        form,
                        event.kind.wire(),
                        event.title.clone(),
                        event.summary.clone(),
                        event.params.as_deref(),
                        &zone,
                        phone.now,
                    );
                    let what = format!("{:?} {lang:?} {form:?}: {title} / {summary}", event.kind);
                    assert!(!title.is_empty(), "{what}");
                    assert!(title.chars().count() <= TITLE_MAX, "{what}");
                    assert!(summary.chars().count() <= SUMMARY_MAX, "{what}");
                    assert!(
                        !title.contains("events.") && !summary.contains("events."),
                        "a key shows: {what}"
                    );
                    assert!(
                        !title.contains("time.") && !summary.contains("time."),
                        "a key shows: {what}"
                    );
                    titles.push(title);
                }
                if let Some(detail) = detail(
                    event.kind.wire(),
                    &event.title,
                    None,
                    event.params.as_deref(),
                    &zone,
                    phone.now,
                ) {
                    assert!(!detail.contains("events."), "{detail}");
                }
            }
            // en inbox, en push, nb inbox, nb push: each language's own words.
            assert_ne!(titles[0], titles[2], "{:?}", event.kind);
            assert_ne!(titles[1], titles[3], "{:?}", event.kind);
        }
        // A few of the nb rows, read.
        let nb = phone.zone.in_lang(Lang::Nb);
        let told_nb = |event: &NewEvent| {
            told(
                event.kind.wire(),
                String::new(),
                String::new(),
                event.params.as_deref(),
                &nb,
                phone.now,
            )
        };
        assert_eq!(
            told_nb(&events[1]),
            (
                "Anna-Sofie Kristians ringte et nødnummer".to_string(),
                "I går 19:14".to_string()
            )
        );
        assert_eq!(told_nb(&events[9]).1, "14:02 · batteri 3 %, trolig tomt");
        assert_eq!(
            told_nb(&events[15]).1,
            "14:02 · låst opp 14:23 · av siden i går 19:14, batteriet var 3 %"
        );
        assert_eq!(told_nb(&events[21]).1, "14:02 · 1 t 20 min igjen");
        assert_eq!(
            inbox_detail(
                &Phone {
                    zone: nb,
                    ..phone.clone()
                },
                &events[5]
            )
            .as_deref(),
            Some("Enhetsadministrator: org.example.spy. Tilgjengelighet: org.example.reader.")
        );
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
        // A call past the server's clock is announced at the server's now (QA L-b).
        let ahead = Some(utc("2026-10-18 12:06:00"));
        assert_eq!(new_emergency_call(ahead, call, now), Some(now));
        assert_eq!(new_emergency_call(ahead, None, now), Some(now));
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
    fn the_bell() {
        assert_eq!(Bell { unread: 3 }.pill(), "3");
        assert_eq!(Bell { unread: 100 }.pill(), "99+");
    }

    /// 2026-11-01 ("Hva er egentlig Finn sending??"): the off's last position names Locate as the
    /// tab to open, "kl." before a clock alone, the day's word otherwise.
    #[test]
    fn the_offs_last_position_points_to_the_map() {
        // 20:40 in Oslo.
        let now = utc("2026-11-01 19:40:00");
        let detail = |at_ms: i64, lang: Lang| {
            let params = format!(r#"{{"location":{{"lat":59.9,"lng":10.7,"at_ms":{at_ms}}}}}"#);
            let zone = Zone::from_setting("Europe/Oslo").in_lang(lang);
            super::detail("phone_off", "", None, Some(&params), &zone, now).unwrap()
        };
        let today = utc("2026-11-01 19:26:00").timestamp_millis();
        let yesterday = utc("2026-10-31 19:26:00").timestamp_millis();
        assert_eq!(
            detail(today, Lang::Nb),
            "Siste posisjon kl. 20:26 – se kartet i Finn"
        );
        assert_eq!(
            detail(today, Lang::En),
            "Last position at 20:26 – see the map in Locate"
        );
        assert_eq!(
            detail(yesterday, Lang::Nb),
            "Siste posisjon i går 20:26 – se kartet i Finn"
        );
        assert_eq!(
            detail(yesterday, Lang::En),
            "Last position yesterday 20:26 – see the map in Locate"
        );
        assert_eq!(
            detail(i64::MAX, Lang::Nb),
            "Siste posisjon – se kartet i Finn"
        );
    }
}
