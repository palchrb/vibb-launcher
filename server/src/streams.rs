//! Which phones hold the SSE command stream open right now (design 19, open question 1): an
//! in-memory count per device and when it last changed, for the device page's "Instant changes"
//! line. Nothing is stored and nothing reaches the device API - a server restart starts empty.
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
            let (close, closed) = watch::channel(self.closed.load(Ordering::SeqCst));
            open.entry(device_id)
                .or_default()
                .push(OpenStream { id, close });
            closed
        };
        StreamGuard {
            streams: Arc::clone(self),
            device_id,
            id,
            closed,
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

#[cfg(test)]
mod tests {
    use super::*;

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
