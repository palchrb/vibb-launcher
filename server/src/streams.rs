//! Which phones hold the SSE command stream open right now (design 19, open question 1): an
//! in-memory count per device and when it last changed, for the device page's "Instant changes"
//! line and the PWA's Online / Away / Offline ([connection_state], design 31 §3.10). Nothing is
//! stored and nothing reaches the device API - a server restart starts empty.
//!
//! The count goes up when `handlers::device_api::commands_stream` answers and down when its
//! response stream is dropped ([StreamGuard]). A phone that vanishes without closing its
//! connection (no FIN: lost network, a phone switched off) still counts until this server's TCP
//! gives up on the peer - so the page says "as far as this server can tell".
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

#[derive(Default)]
pub struct CommandStreams {
    devices: Mutex<HashMap<i64, StreamState>>,
    /// Per device, the open streams, oldest first.
    open: Mutex<HashMap<i64, Vec<OpenStream>>>,
    next_id: AtomicU64,
    /// Set by [CommandStreams::close_all]: a stream opened afterwards is closed at once.
    closed: AtomicBool,
}

impl CommandStreams {
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
}

impl StreamGuard {
    /// `true` once this stream must end; the stream watches it (`WatchStream`).
    pub fn closed(&self) -> watch::Receiver<bool> {
        self.closed.clone()
    }
}

impl Drop for StreamGuard {
    fn drop(&mut self) {
        self.streams.close(self.device_id, self.id);
    }
}

/// The device page's line about the stream (design 19 QA #11).
pub fn instant_changes_line(state: Option<StreamState>) -> String {
    let at = |t: DateTime<Utc>| t.format("%Y-%m-%d %H:%M:%S").to_string();
    match state {
        Some(s) if s.open > 0 => format!(
            "Instant changes: connected since {} UTC, as far as this server can tell. The phone also checks in by itself every 30 minutes.",
            at(s.since)
        ),
        Some(s) => format!(
            "Instant changes: not connected since {} UTC - changes reach the phone when it reconnects or at its next check-in (every 30 minutes, 15 while it can't connect).",
            at(s.since)
        ),
        None => "Instant changes: not connected since this server started - changes reach the phone when it connects or at its next check-in (every 30 minutes).".to_string(),
    }
}

/// Online: a stream is open and the last contact is at most this old (design 31 §3.10, H2).
///
/// With the stream up, nothing else reaches the server on a quiet night (the keepalive goes from
/// the server to the phone). The launcher's backstop sync then runs every 30 min
/// (`push/SyncSchedule.kt`, `BACKSTOP_INTERVAL_MS`), armed as an inexact alarm
/// (`push/BackstopAlarm.kt`, `setAndAllowWhileIdle`) that Android may deliver up to 0.75 × its
/// delay late: 30 × 1.75 = 52.5 min, plus margin. A healthy idle phone stays Online all night.
/// Nothing links the two files at compile time: a launcher change to the backstop must revisit
/// this.
pub const ONLINE_WINDOW_MINUTES: i64 = 60;

/// Away: no stream, and the last contact is at most this old (design 31 §3.10, H2).
///
/// With the stream down the backstop runs every 15 min (`BACKSTOP_SSE_DOWN_INTERVAL_MS`), up to
/// 0.75 × late: 15 × 1.75 = 26.25 min, plus up to 9 min of Doze's while-idle quota
/// (`MIN_BACKSTOP_MS`) and margin. Older is Offline. Revisit with any launcher backstop change.
pub const AWAY_WINDOW_MINUTES: i64 = 45;

/// What the parent sees about a phone's link to this server (design 31 §3.10): the header's dot
/// and word, the Devices list's status line.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectionState {
    /// Not enrolled.
    NotSetUp,
    /// Enrolled, but its access was revoked (no token): it can't come back by itself.
    Disconnected,
    /// Enrolled, no contact ever.
    NotSeenYet,
    /// A stream is open (since `since`) and the last contact is at most an hour old.
    Online {
        since: DateTime<Utc>,
        last_contact: DateTime<Utc>,
    },
    /// No stream, last contact at most 45 min ago: changes may be delayed.
    Away { last_contact: DateTime<Utc> },
    /// Anything older, or a stream "open" with no contact for over an hour (a phone that
    /// vanished without closing it).
    Offline { last_contact: DateTime<Utc> },
}

/// The signals [connection_state] combines (§1.2b). `last_access` is any authenticated request
/// since this server started (`limits::last_access`), `last_seen` the last status report
/// (`devices.last_seen_at`, kept across a restart).
#[derive(Clone, Copy, Debug, Default)]
pub struct ConnectionInputs {
    pub enrolled: bool,
    pub has_token: bool,
    pub stream: Option<StreamState>,
    pub last_access: Option<DateTime<Utc>>,
    pub last_seen: Option<DateTime<Utc>>,
}

/// The state (§3.10's table). Last contact is the newest of the last access, the last report and
/// an open stream's `since`. Right after a server restart only the last report is known, so a
/// phone shows Away - or Offline if that report is over 45 min old, as an idle phone's often is -
/// until its stream reconnects: seconds after a clean stop (the streams are closed, the phones
/// reconnect at once), up to 5 min after a crash or a power cut (no FIN reaches the phones; they
/// reconnect after 300 s of silence). Honest, so no special case.
pub fn connection_state(inputs: &ConnectionInputs, now: DateTime<Utc>) -> ConnectionState {
    if !inputs.enrolled {
        return ConnectionState::NotSetUp;
    }
    if !inputs.has_token {
        return ConnectionState::Disconnected;
    }
    let open = inputs.stream.filter(|s| s.open > 0);
    let Some(last_contact) = [inputs.last_access, inputs.last_seen, open.map(|s| s.since)]
        .into_iter()
        .flatten()
        .max()
    else {
        return ConnectionState::NotSeenYet;
    };
    let age = now - last_contact;
    match open {
        Some(stream) if age <= chrono::Duration::minutes(ONLINE_WINDOW_MINUTES) => {
            ConnectionState::Online {
                since: stream.since,
                last_contact,
            }
        }
        None if age <= chrono::Duration::minutes(AWAY_WINDOW_MINUTES) => {
            ConnectionState::Away { last_contact }
        }
        _ => ConnectionState::Offline { last_contact },
    }
}

impl ConnectionState {
    /// The word next to the dot.
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

    /// The colour: `online` (green), `away` (orange), else `offline` (grey).
    pub fn css(&self) -> &'static str {
        match self {
            ConnectionState::Online { .. } => "online",
            ConnectionState::Away { .. } => "away",
            _ => "offline",
        }
    }

    pub fn last_contact(&self) -> Option<DateTime<Utc>> {
        match *self {
            ConnectionState::Online { last_contact, .. }
            | ConnectionState::Away { last_contact }
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
            state(phone(now, None, Some(12), Some(20))),
            ConnectionState::Away {
                last_contact: minutes_ago(now, 12)
            }
        );
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

        // A healthy idle phone: the stream up, the last backstop sync 50 min ago.
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(50), Some(50))).word(),
            "Online"
        );
        assert_eq!(
            state(phone(now, Some((1, 300)), Some(60), Some(60))).word(),
            "Online"
        );
        // A stream "open" with no contact for 61 min: the phone vanished without closing it.
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
        // A stream that closed counts for nothing (open 0), its `since` is not a contact.
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
        let away = ConnectionState::Away { last_contact: t };
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
    fn the_line_says_what_the_server_can_tell() {
        let since = DateTime::from_timestamp(1_790_000_000, 0).unwrap();
        let connected = instant_changes_line(Some(StreamState { open: 1, since }));
        assert!(
            connected.starts_with("Instant changes: connected since 2026-"),
            "{connected}"
        );
        assert!(connected.contains("as far as this server can tell"));
        assert!(connected.contains("every 30 minutes"));
        let down = instant_changes_line(Some(StreamState { open: 0, since }));
        assert!(
            down.starts_with("Instant changes: not connected since 2026-"),
            "{down}"
        );
        assert!(instant_changes_line(None).contains("since this server started"));
    }
}
