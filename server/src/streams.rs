//! Which phones hold the SSE command stream open right now (design 19, open question 1): an
//! in-memory count per device and when it last changed, for the PWA's Online / Away / Offline
//! ([connection_state], design 31 §3.10; About This Phone shows "Online since 08:12"). Nothing is
//! stored and nothing reaches the device API - a server restart starts empty.
//!
//! The count goes up when `handlers::device_api::commands_stream` answers and down when its
//! response stream is dropped ([StreamGuard]). A phone that vanishes without closing its
//! connection (no FIN: lost network, a phone switched off) still counts until whoever holds its
//! TCP gives up on it - so Online also needs a recent contact (design 121: 35 min, the phone's
//! backstop reports every 30).
//!
//! Every open stream also has a close signal (design 22 §2): [CommandStreams::close_all] ends
//! them at shutdown, so a graceful stop doesn't wait for phones that never hang up.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use chrono::{DateTime, Utc};
use tokio::sync::watch;

/// One device's streams: how many are open and when that last changed (opened, or the last one
/// closed).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct StreamState {
    pub open: u32,
    pub since: DateTime<Utc>,
}

/// Live command streams per phone; a new one past this closes the oldest.
pub const MAX_PER_DEVICE: usize = 2;

/// One open stream's close signal.
struct OpenStream {
    id: u64,
    close: watch::Sender<bool>,
}

pub struct CommandStreams {
    devices: Mutex<HashMap<i64, StreamState>>,
    /// Per device, the open streams, oldest first.
    open: Mutex<HashMap<i64, Vec<OpenStream>>>,
    next_id: AtomicU64,
    /// Set by [CommandStreams::close_all]: a stream opened afterwards is closed at once.
    closed: AtomicBool,
    /// When this server started counting (design 121 §4.3, the restart grace).
    started: Mutex<DateTime<Utc>>,
}

impl Default for CommandStreams {
    fn default() -> Self {
        CommandStreams {
            devices: Mutex::default(),
            open: Mutex::default(),
            next_id: AtomicU64::default(),
            closed: AtomicBool::default(),
            started: Mutex::new(Utc::now()),
        }
    }
}

impl CommandStreams {
    /// When this server started: a phone with no stream since then may have held one when it stopped.
    pub fn started(&self) -> DateTime<Utc> {
        *self.started.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Tests: a server that started at `at`.
    #[cfg(test)]
    pub fn set_started(&self, at: DateTime<Utc>) {
        *self.started.lock().unwrap_or_else(|e| e.into_inner()) = at;
    }

    /// A stream for `device_id` opened; dropping the guard closes it. A new stream while one is
    /// still counted (the phone reconnected before the old connection timed out here) moves
    /// `since` to now: the newest stream is the live one.
    ///
    /// The guard's [StreamGuard::closed] turns `true` when the stream must end.
    pub fn open(self: &Arc<Self>, device_id: i64) -> StreamGuard {
        {
            let mut devices = self.devices.lock().unwrap_or_else(|e| e.into_inner());
            let entry = devices.entry(device_id).or_insert(StreamState {
                open: 0,
                since: Utc::now(),
            });
            entry.open = entry.open.saturating_add(1);
            entry.since = Utc::now();
        }
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        // `closed` is read under the lock `close_all` takes after setting it, so a stream opened
        // during shutdown is either closed there or starts closed.
        let closed = {
            let mut open = self.open.lock().unwrap_or_else(|e| e.into_inner());
            let streams = open.entry(device_id).or_default();
            // At most MAX_PER_DEVICE live streams per phone: a new one closes the oldest (design
            // 22 §3.1) - a phone that reconnected before its old connection timed out here.
            let mut live: Vec<&OpenStream> = streams
                .iter()
                .filter(|stream| !*stream.close.borrow())
                .collect();
            while live.len() >= MAX_PER_DEVICE {
                live.remove(0).close.send_replace(true);
            }
            let (close, closed) = watch::channel(self.closed.load(Ordering::SeqCst));
            streams.push(OpenStream { id, close });
            closed
        };
        StreamGuard {
            streams: Arc::clone(self),
            device_id,
            id,
            closed,
            opened: std::time::Instant::now(),
        }
    }

    /// Ends every open stream of one phone (deleted, or its access revoked).
    pub fn close_device(&self, device_id: i64) {
        let open = self.open.lock().unwrap_or_else(|e| e.into_inner());
        for stream in open.get(&device_id).into_iter().flatten() {
            stream.close.send_replace(true);
        }
    }

    /// Ends every open stream, and every one opened from now on (shutdown).
    pub fn close_all(&self) {
        self.closed.store(true, Ordering::SeqCst);
        let open = self.open.lock().unwrap_or_else(|e| e.into_inner());
        for stream in open.values().flatten() {
            stream.close.send_replace(true);
        }
    }

    /// The device's streams, `None` if none opened since this server started.
    pub fn state(&self, device_id: i64) -> Option<StreamState> {
        self.devices
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&device_id)
            .copied()
    }

    fn close(&self, device_id: i64, id: u64) {
        {
            let mut open = self.open.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(streams) = open.get_mut(&device_id) {
                streams.retain(|stream| stream.id != id);
                if streams.is_empty() {
                    open.remove(&device_id);
                }
            }
        }
        let mut devices = self.devices.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = devices.get_mut(&device_id) {
            entry.open = entry.open.saturating_sub(1);
            if entry.open == 0 {
                entry.since = Utc::now();
            }
        }
    }
}

/// Held by an open command stream; dropping it (the connection ended) counts the stream closed.
pub struct StreamGuard {
    streams: Arc<CommandStreams>,
    device_id: i64,
    id: u64,
    closed: watch::Receiver<bool>,
    /// When the stream opened: the "closed" log line's age (server round 5 §3).
    opened: std::time::Instant,
}

impl StreamGuard {
    /// How long this stream has been open.
    pub fn age(&self) -> std::time::Duration {
        self.opened.elapsed()
    }

    /// `true` once this stream must end; the stream watches it (`WatchStream`).
    pub fn closed(&self) -> watch::Receiver<bool> {
        self.closed.clone()
    }
}

impl Drop for StreamGuard {
    fn drop(&mut self) {
        // The server's half of the SSE diagnosis (the phone's: `adb logcat -s
        // CommandListenerService`); `close_all` at shutdown gives one line per stream.
        tracing::info!(
            device = self.device_id,
            secs = self.age().as_secs(),
            "command stream closed"
        );
        self.streams.close(self.device_id, self.id);
    }
}

/// Online: a stream is open and the last contact is at most this old (design 121, the user's decision of
/// 2026-11-19; design 31 §3.10 had 60).
///
/// A phone whose stream is open sends nothing else on a quiet night (the keepalive goes from the server to the
/// phone), and whoever holds its TCP may keep a vanished phone's stream "open" for 15-20 min. The launcher's backstop
/// sync runs every 30 min (`push/SyncSchedule.kt`, `BACKSTOP_INTERVAL_MS`), so 35 min without a contact is "not
/// green" at zero extra bytes. Since 2026-11-21 the backstop is an exact while-idle alarm (`push/BackstopAlarm.kt`,
/// `backstopArm`: `USE_EXACT_ALARM`; inexact only if Android refuses exact alarms): the inexact one came up to 3/4 of
/// its 30 min late even on a charger, and the Jelly Star read "No contact" with its stream open (design 121, "Known
/// cost"). An idle phone's last contact is now at most 30 min plus the time from the last sync's report to the next
/// sync's first request - seconds: the alarm is re-armed when a sync ends, Doze's while-idle floor (~9 min) never
/// meets a 30-min spacing, and a sync whose request fails has no contact to give, so a wider window wouldn't help it.
/// The 5 min above 30 are the margin; kept at 35 (not 40) since a wider one only holds a vanished phone green longer.
/// Nothing links the two files at compile time: a launcher change to the backstop must revisit this (the launcher's
/// `SyncScheduleTest` names 35 too).
pub const ONLINE_WINDOW_MINUTES: i64 = 35;

/// Away: no live stream, and the phone was reachable at most this long ago ([reach]; design 31 §3.10, H2).
///
/// With the stream down the backstop runs every 15 min (`BACKSTOP_SSE_DOWN_INTERVAL_MS`), exact since 2026-11-21; sized
/// for the inexact fallback, up to 0.75 × late: 15 × 1.75 = 26.25 min, plus up to 9 min of Doze's while-idle quota
/// (`MIN_BACKSTOP_MS`) and margin. Older is Offline. Revisit with any launcher backstop change.
pub const AWAY_WINDOW_MINUTES: i64 = 45;

/// A phone whose stream is still counted open is never Offline before this long after its last contact (code QA M1,
/// the coordinator's decision of 2026-11-20): it stays orange "No contact" until the stream closes or this passes - the
/// old Online window, sized for the 30-min backstop that Android may deliver up to 1.75 × late (exact since 2026-11-21;
/// this still covers the inexact fallback). A holder drops a dead phone's connection within ~20 min, so a stream still
/// open past 45 min is almost always a live phone.
pub const OPEN_STREAM_AWAY_MINUTES: i64 = 60;

/// Away reads green this long after the phone was last reachable ([reach]; design 121 §4.3): a network change, a
/// proxy reload or a server restart drops the stream, and the phone reconnects in seconds.
pub const CLOSE_GRACE_MINUTES: i64 = 1;

/// After a server start, a phone with no stream yet reads green this long (design 121 §4.3, QA L4): phones reconnect
/// in seconds after a clean stop, within their 300 s read timeout after a crash.
pub const RESTART_GRACE_MINUTES: i64 = 6;

/// What the parent sees about a phone's link to this server (design 31 §3.10). The pages' dot and
/// line read it through `phone_status::dot`: Away until `green_until` reads as online, after it as
/// "No contact" (2026-11-01, design 121).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectionState {
    /// Not enrolled.
    NotSetUp,
    /// Enrolled, but its access was revoked (no token): it can't come back by itself.
    Disconnected,
    /// Enrolled, no contact ever.
    NotSeenYet,
    /// A stream is open (since `since`) and the last contact is at most [ONLINE_WINDOW_MINUTES] old.
    Online {
        since: DateTime<Utc>,
        last_contact: DateTime<Utc>,
    },
    /// No live stream, reachable at most [AWAY_WINDOW_MINUTES] ago: changes may be delayed. Green until
    /// `green_until` ([CLOSE_GRACE_MINUTES] after [reach], [RESTART_GRACE_MINUTES] after a start), then "No contact".
    Away {
        last_contact: DateTime<Utc>,
        green_until: DateTime<Utc>,
    },
    /// Anything older: reachable more than [AWAY_WINDOW_MINUTES] ago.
    Offline { last_contact: DateTime<Utc> },
}

/// The signals [connection_state] combines (§1.2b). `last_access` is any authenticated request
/// since this server started (`limits::last_access`), `last_seen` the last status report
/// (`devices.last_seen_at`, kept across a restart), `started` this server's start
/// ([CommandStreams::started]; `None` = no restart grace).
#[derive(Clone, Copy, Debug, Default)]
pub struct ConnectionInputs {
    pub enrolled: bool,
    pub has_token: bool,
    pub stream: Option<StreamState>,
    pub last_access: Option<DateTime<Utc>>,
    pub last_seen: Option<DateTime<Utc>>,
    pub started: Option<DateTime<Utc>>,
}

/// The newest of the last access, the last report and an open stream's `since`, whatever the
/// state - also for a Disconnected phone, whose last contact About This Phone still shows
/// (qa-31-s3-code M1: "cut off a minute ago" vs "gone for weeks"). "Last seen" is always this,
/// never [reach].
pub fn last_contact(inputs: &ConnectionInputs) -> Option<DateTime<Utc>> {
    let open = inputs.stream.filter(|s| s.open > 0);
    [inputs.last_access, inputs.last_seen, open.map(|s| s.since)]
        .into_iter()
        .flatten()
        .max()
}

/// Design 121 §4.3: when the server last knew the phone reachable, and until when Away reads green. With no live
/// stream:
/// - a stream "open" with no contact for [ONLINE_WINDOW_MINUTES] (a phone that vanished without closing it): the last
///   contact;
/// - a stream that closed since this server started while it was still live (its close at most
///   [ONLINE_WINDOW_MINUTES] after the last contact): the later of the two (QA M1: the windows count from the close,
///   so an idle phone whose last sync is 30 min old never flashes grey). A later close is a dead phone's connection
///   dropped by its holder at last, no news of the phone (code QA H2): the last contact;
/// - no stream since the start, the last contact at most [ONLINE_WINDOW_MINUTES] before it: the last contact, but green
///   until [RESTART_GRACE_MINUTES] after the start (it may have held a stream when the server stopped; QA L4) - Offline
///   stays 45 min after the last contact (code QA L3);
/// - otherwise the last contact.
///
/// Green lasts [CLOSE_GRACE_MINUTES] after reach in every case: a reconnect within the minute never shows.
pub fn reach(
    inputs: &ConnectionInputs,
    last_contact: DateTime<Utc>,
) -> (DateTime<Utc>, DateTime<Utc>) {
    let grace = chrono::Duration::minutes(CLOSE_GRACE_MINUTES);
    match (inputs.stream, inputs.started) {
        (Some(stream), _)
            if stream.open == 0
                && stream.since - last_contact
                    <= chrono::Duration::minutes(ONLINE_WINDOW_MINUTES) =>
        {
            let at = last_contact.max(stream.since);
            (at, at + grace)
        }
        (None, Some(started))
            if last_contact >= started - chrono::Duration::minutes(ONLINE_WINDOW_MINUTES) =>
        {
            (
                last_contact,
                (last_contact + grace)
                    .max(started + chrono::Duration::minutes(RESTART_GRACE_MINUTES)),
            )
        }
        _ => (last_contact, last_contact + grace),
    }
}

/// The state (§3.10's table, design 121 §4.3): Online with a stream open and a contact within
/// [ONLINE_WINDOW_MINUTES]; else Away while [reach] is at most [AWAY_WINDOW_MINUTES] old - with the stream still
/// counted open, [OPEN_STREAM_AWAY_MINUTES] (code QA M1) -, else Offline.
pub fn connection_state(inputs: &ConnectionInputs, now: DateTime<Utc>) -> ConnectionState {
    if !inputs.enrolled {
        return ConnectionState::NotSetUp;
    }
    if !inputs.has_token {
        return ConnectionState::Disconnected;
    }
    let open = inputs.stream.filter(|s| s.open > 0);
    let Some(last_contact) = last_contact(inputs) else {
        return ConnectionState::NotSeenYet;
    };
    if let Some(stream) = open
        && now - last_contact <= chrono::Duration::minutes(ONLINE_WINDOW_MINUTES)
    {
        return ConnectionState::Online {
            since: stream.since,
            last_contact,
        };
    }
    let (at, green_until) = reach(inputs, last_contact);
    let window = if open.is_some() {
        OPEN_STREAM_AWAY_MINUTES
    } else {
        AWAY_WINDOW_MINUTES
    };
    if now - at <= chrono::Duration::minutes(window) {
        ConnectionState::Away {
            last_contact,
            green_until,
        }
    } else {
        ConnectionState::Offline { last_contact }
    }
}

impl ConnectionState {
    /// The state's English name (the tests'; the pages say `phone_status`'s words and dot, and
    /// About This Phone its `devices.status.*`).
    #[cfg(test)]
    pub fn word(&self) -> &'static str {
        match self {
            ConnectionState::NotSetUp => "Not set up",
            ConnectionState::Disconnected => "Disconnected",
            ConnectionState::NotSeenYet => "Not seen yet",
            ConnectionState::Online { .. } => "Online",
            ConnectionState::Away { .. } => "Away",
            ConnectionState::Offline { .. } => "Offline",
        }
    }

    /// The colour: `online` (green), `away` (orange), else `offline` (grey) - About This Phone's
    /// Connection row; the avatars' dots are `phone_status::dot`.
    pub fn css(&self) -> &'static str {
        match self {
            ConnectionState::Online { .. } => "online",
            ConnectionState::Away { .. } => "away",
            _ => "offline",
        }
    }

    /// While Online: since when its stream is open.
    pub fn online_since(&self) -> Option<DateTime<Utc>> {
        match *self {
            ConnectionState::Online { since, .. } => Some(since),
            _ => None,
        }
    }

    pub fn last_contact(&self) -> Option<DateTime<Utc>> {
        match *self {
            ConnectionState::Online { last_contact, .. }
            | ConnectionState::Away { last_contact, .. }
            | ConnectionState::Offline { last_contact } => Some(last_contact),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minutes_ago(now: DateTime<Utc>, m: i64) -> DateTime<Utc> {
        now - chrono::Duration::minutes(m)
    }

    /// An enrolled phone with a token, its stream (`open`, since `stream_min` ago) and its last
    /// access and report.
    fn phone(
        now: DateTime<Utc>,
        stream: Option<(u32, i64)>,
        access_min: Option<i64>,
        seen_min: Option<i64>,
    ) -> ConnectionInputs {
        ConnectionInputs {
            enrolled: true,
            has_token: true,
            stream: stream.map(|(open, m)| StreamState {
                open,
                since: minutes_ago(now, m),
            }),
            last_access: access_min.map(|m| minutes_ago(now, m)),
            last_seen: seen_min.map(|m| minutes_ago(now, m)),
            started: None,
        }
    }

    /// Away with its green minute after `reach_min` ago.
    fn away(now: DateTime<Utc>, contact_min: i64, reach_min: i64) -> ConnectionState {
        ConnectionState::Away {
            last_contact: minutes_ago(now, contact_min),
            green_until: minutes_ago(now, reach_min)
                + chrono::Duration::minutes(CLOSE_GRACE_MINUTES),
        }
    }

    #[test]
    fn the_state_follows_the_windows() {
        let now = DateTime::from_timestamp(1_790_000_000, 0).unwrap();
        let state = |inputs: ConnectionInputs| connection_state(&inputs, now);
        // §3.10's table, row by row.
        assert_eq!(
            state(phone(now, Some((1, 3)), Some(3), Some(3))),
            ConnectionState::Online {
                since: minutes_ago(now, 3),
                last_contact: minutes_ago(now, 3)
            }
        );
        assert_eq!(
            state(phone(now, Some((1, 30)), Some(3), None)).online_since(),
            Some(minutes_ago(now, 30)),
            "About's \"Online since\""
        );
        assert_eq!(
            state(phone(now, None, Some(12), Some(20))),
            away(now, 12, 12)
        );
        assert_eq!(state(phone(now, None, Some(12), None)).online_since(), None);
        // A revoked phone is Disconnected, but its last contact is still known (qa-31-s3-code M1).
        let revoked = ConnectionInputs {
            has_token: false,
            ..phone(now, Some((0, 1)), Some(2), Some(5))
        };
        assert_eq!(state(revoked), ConnectionState::Disconnected);
        assert_eq!(last_contact(&revoked), Some(minutes_ago(now, 2)));
        // A closed stream's `since` is no contact.
        assert_eq!(last_contact(&phone(now, Some((0, 1)), None, None)), None);
        assert_eq!(
            state(phone(now, None, None, Some(120))),
            ConnectionState::Offline {
                last_contact: minutes_ago(now, 120)
            }
        );
        assert_eq!(
            state(ConnectionInputs {
                has_token: false,
                ..phone(now, None, Some(1), Some(1))
            }),
            ConnectionState::Disconnected
        );
        assert_eq!(
            state(phone(now, None, None, None)),
            ConnectionState::NotSeenYet
        );
        assert_eq!(
            state(ConnectionInputs {
                enrolled: false,
                has_token: false,
                ..Default::default()
            }),
            ConnectionState::NotSetUp
        );

        // A healthy idle phone: the stream up, the last backstop sync 30 min ago (design 121: 35 min).
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(30), Some(30))).word(),
            "Online"
        );
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(35), Some(35))).word(),
            "Online"
        );
        // A stream "open" with no contact for 36 min: the phone vanished without closing it - Away from its last
        // contact (orange at once), Offline 45 min after it.
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(36), Some(36))),
            away(now, 36, 36)
        );
        // Code QA M1: with the stream still counted open, orange - never grey - until 60 min after the contact.
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(50), Some(50))),
            away(now, 50, 50)
        );
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(60), Some(60))).word(),
            "Away"
        );
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(61), Some(61))).word(),
            "Offline"
        );
        // No stream: Away up to 45 min, then Offline.
        assert_eq!(state(phone(now, None, Some(30), Some(30))).word(), "Away");
        assert_eq!(state(phone(now, None, Some(45), Some(45))).word(), "Away");
        assert_eq!(
            state(phone(now, None, Some(46), Some(46))).word(),
            "Offline"
        );
        // A stream that closed is no contact (open 0), but the windows count from its close (design 121, QA M1)...
        assert_eq!(
            state(phone(now, Some((0, 1)), None, Some(30))),
            away(now, 30, 1)
        );
        // ...only while it was live: a close 49 min after the contact counts from the contact (code QA H2).
        assert_eq!(
            state(phone(now, Some((0, 1)), None, Some(50))).word(),
            "Offline"
        );
        // A stream that just opened is a contact by itself.
        assert_eq!(
            state(phone(now, Some((1, 0)), None, Some(300))).word(),
            "Online"
        );
        // The newest signal wins: a fresh request beats an old report.
        assert_eq!(
            state(phone(now, None, Some(2), Some(200))).last_contact(),
            Some(minutes_ago(now, 2))
        );
    }

    /// Design 121 §4.3/§7: green a minute after the close, orange after it, grey 45 min after it; the restart grace;
    /// "last seen" is the last contact in every row.
    #[test]
    fn the_windows_count_from_the_close_and_the_start() {
        let now = DateTime::from_timestamp(1_790_000_000, 0).unwrap();
        let green = |inputs: ConnectionInputs| {
            let state = connection_state(&inputs, now);
            let dot = match state {
                ConnectionState::Away { green_until, .. } => {
                    if now < green_until {
                        "green"
                    } else {
                        "orange"
                    }
                }
                ConnectionState::Online { .. } => "green",
                _ => "grey",
            };
            (dot, state.last_contact())
        };
        let closed = |secs: i64, contact_min: i64| ConnectionInputs {
            stream: Some(StreamState {
                open: 0,
                since: now - chrono::Duration::seconds(secs),
            }),
            ..phone(now, None, None, Some(contact_min))
        };
        let seen = |m: i64| Some(minutes_ago(now, m));
        assert_eq!(green(closed(30, 30)), ("green", seen(30)));
        assert_eq!(green(closed(120, 30)), ("orange", seen(30)));
        assert_eq!(green(closed(50 * 60, 60)), ("grey", seen(60)));
        // Code QA H2: a dead phone's stream dropped by its holder at last (the close 41 min after the last contact) is
        // no news of the phone: orange, Offline 45 min after the contact - never green again.
        assert_eq!(green(closed(60, 42)), ("orange", seen(42)));
        assert_eq!(green(closed(60, 46)), ("grey", seen(46)));
        // A close within 35 min of the contact still counts: green for its minute.
        assert_eq!(green(closed(30, 34)), ("green", seen(34)));
        // A reconnect within the minute: Online again.
        assert_eq!(green(phone(now, Some((1, 0)), None, Some(50))).0, "green");
        // After a start: no stream yet, the last contact 20 min before it - green for the grace, then orange.
        let started = |start_min: i64, contact_min: i64| ConnectionInputs {
            started: Some(minutes_ago(now, start_min)),
            ..phone(now, None, None, Some(contact_min))
        };
        assert_eq!(green(started(2, 22)), ("green", seen(22)));
        assert_eq!(green(started(7, 27)), ("orange", seen(27)));
        // Code QA L3: Offline stays 45 min after the stored contact, the grace only greens the first minutes.
        assert_eq!(green(started(20, 50)).0, "grey");
        // Long gone before the start: grey.
        assert_eq!(green(started(2, 182)), ("grey", seen(182)));
        // A phone that reported after the start: its own minute.
        assert_eq!(green(started(60, 30)), ("orange", seen(30)));
    }

    #[test]
    fn right_after_a_restart_a_fresh_report_is_away_until_the_stream_is_back() {
        let now = DateTime::from_timestamp(1_790_000_000, 0).unwrap();
        // Memory empty: no stream, no last access - only the database's last report.
        let restarted = phone(now, None, None, Some(5));
        assert_eq!(connection_state(&restarted, now).word(), "Away");
        let reconnected = ConnectionInputs {
            stream: Some(StreamState {
                open: 1,
                since: now,
            }),
            last_access: Some(now),
            ..restarted
        };
        assert_eq!(connection_state(&reconnected, now).word(), "Online");
        // Older than the Away window: Offline until it reconnects.
        assert_eq!(
            connection_state(&phone(now, None, None, Some(50)), now).word(),
            "Offline"
        );
    }

    #[test]
    fn words_and_colours() {
        let t = DateTime::from_timestamp(1_790_000_000, 0).unwrap();
        let online = ConnectionState::Online {
            since: t,
            last_contact: t,
        };
        assert_eq!((online.word(), online.css()), ("Online", "online"));
        let away = ConnectionState::Away {
            last_contact: t,
            green_until: t,
        };
        assert_eq!((away.word(), away.css()), ("Away", "away"));
        for (state, word) in [
            (ConnectionState::Offline { last_contact: t }, "Offline"),
            (ConnectionState::Disconnected, "Disconnected"),
            (ConnectionState::NotSeenYet, "Not seen yet"),
            (ConnectionState::NotSetUp, "Not set up"),
        ] {
            assert_eq!((state.word(), state.css()), (word, "offline"));
        }
        assert_eq!(ConnectionState::NotSeenYet.last_contact(), None);
    }

    #[test]
    fn guards_count_streams_per_device() {
        let streams = Arc::new(CommandStreams::default());
        assert_eq!(streams.state(1), None);
        let first = streams.open(1);
        assert_eq!(streams.state(1).unwrap().open, 1);
        let opened = streams.state(1).unwrap().since;
        // A reconnect before the old connection timed out here: two counted, since moves on.
        let second = streams.open(1);
        let both = streams.state(1).unwrap();
        assert_eq!(both.open, 2);
        assert!(both.since >= opened);
        drop(first);
        assert_eq!(streams.state(1).unwrap().open, 1);
        assert_eq!(
            streams.state(1).unwrap().since,
            both.since,
            "still connected"
        );
        drop(second);
        let closed = streams.state(1).unwrap();
        assert_eq!(closed.open, 0);
        assert!(closed.since >= both.since);
        assert_eq!(streams.state(2), None, "per device");
    }

    #[test]
    fn a_third_stream_closes_the_oldest_and_a_device_can_be_closed() {
        let streams = Arc::new(CommandStreams::default());
        let first = streams.open(1);
        let second = streams.open(1);
        let other = streams.open(2);
        assert!(!*first.closed().borrow() && !*second.closed().borrow());
        let third = streams.open(1);
        assert!(*first.closed().borrow(), "the oldest closes");
        assert!(!*second.closed().borrow() && !*third.closed().borrow());
        // The closed one is still counted until its connection ends; a fourth closes the next.
        let fourth = streams.open(1);
        assert!(*second.closed().borrow());
        assert!(!*third.closed().borrow() && !*fourth.closed().borrow());
        streams.close_device(1);
        assert!(*third.closed().borrow() && *fourth.closed().borrow());
        assert!(!*other.closed().borrow(), "per device");
    }

    #[test]
    fn close_all_ends_open_and_later_streams() {
        let streams = Arc::new(CommandStreams::default());
        let first = streams.open(1);
        let second = streams.open(2);
        assert!(!*first.closed().borrow());
        streams.close_all();
        assert!(*first.closed().borrow());
        assert!(*second.closed().borrow());
        let late = streams.open(3);
        assert!(*late.closed().borrow(), "opened during shutdown");
        drop(first);
        assert_eq!(streams.state(1).unwrap().open, 0);
    }

    #[test]
    fn a_guard_knows_its_age() {
        let streams = Arc::new(CommandStreams::default());
        let guard = streams.open(1);
        let first = guard.age();
        std::thread::sleep(std::time::Duration::from_millis(5));
        assert!(guard.age() >= first + std::time::Duration::from_millis(5));
        assert!(guard.age() < std::time::Duration::from_secs(60));
    }
}
