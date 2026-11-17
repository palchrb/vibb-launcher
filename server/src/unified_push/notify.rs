//! The `up` nudge (design 108 §3.9): a content-free SSE event on the phone's command stream, sent after a push is
//! queued, so the phone pulls its queue. A channel of its own (`UnifiedPush::up_notify`) next to `command_notify`.
//!
//! **Coalesced per phone at the sender, on the trailing edge (M2).** The first push in a quiet phone's window sends at
//! once; a push within [WINDOW] of the last send arms one trailing send at the window's end. So the last push of every
//! burst is followed by an `up`: a pull that ran before a later message was queued can't leave that message waiting
//! for the 30-minute backstop. Every caller sends only after its row is committed.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Pushes to one phone closer than this share one `up`, plus the trailing one.
pub const WINDOW: Duration = Duration::from_millis(250);

struct Entry {
    last_sent: Instant,
    armed: bool,
}

/// Where the `up`s go: the broadcast channel in the server, a recorder in the tests.
pub trait Nudge: Send + Sync + 'static {
    fn nudge(&self, device_id: i64);
}

impl Nudge for tokio::sync::broadcast::Sender<i64> {
    fn nudge(&self, device_id: i64) {
        // No subscriber (the phone is offline) is expected: it pulls at its next stream open or sync.
        let _ = tokio::sync::broadcast::Sender::send(self, device_id);
    }
}

#[derive(Default)]
pub struct Coalescer {
    phones: Mutex<HashMap<i64, Entry>>,
}

impl Coalescer {
    /// A push for `device_id` was queued (committed): send now, or arm the window's trailing send.
    pub fn pushed(self: &Arc<Self>, device_id: i64, sender: Arc<dyn Nudge>) {
        let now = Instant::now();
        let mut phones = self.phones.lock().unwrap_or_else(|e| e.into_inner());
        if phones.len() >= crate::limits::MAX_KEYS && !phones.contains_key(&device_id) {
            phones.retain(|_, entry| entry.armed);
        }
        match phones.get_mut(&device_id) {
            Some(entry) if entry.armed => {}
            Some(entry) if now.saturating_duration_since(entry.last_sent) < WINDOW => {
                entry.armed = true;
                let at = entry.last_sent + WINDOW;
                let this = Arc::clone(self);
                tokio::spawn(async move {
                    tokio::time::sleep_until(at.into()).await;
                    let mut phones = this.phones.lock().unwrap_or_else(|e| e.into_inner());
                    if let Some(entry) = phones.get_mut(&device_id) {
                        entry.armed = false;
                        entry.last_sent = Instant::now();
                    }
                    sender.nudge(device_id);
                });
            }
            _ => {
                phones.insert(
                    device_id,
                    Entry {
                        last_sent: now,
                        armed: false,
                    },
                );
                sender.nudge(device_id);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Recorder(Mutex<Vec<(i64, Instant)>>);

    impl Nudge for Recorder {
        fn nudge(&self, device_id: i64) {
            self.0.lock().unwrap().push((device_id, Instant::now()));
        }
    }

    /// M2: two pushes 100 ms apart give two `up` sends, the second after the second enqueue; a third push inside the
    /// armed window adds none; another phone has its own window.
    #[tokio::test]
    async fn trailing_edge() {
        let coalescer = Arc::new(Coalescer::default());
        let recorder = Arc::new(Recorder::default());
        let sender: Arc<dyn Nudge> = recorder.clone();
        coalescer.pushed(1, sender.clone());
        assert_eq!(recorder.0.lock().unwrap().len(), 1, "the first at once");
        tokio::time::sleep(Duration::from_millis(100)).await;
        let second = Instant::now();
        coalescer.pushed(1, sender.clone());
        coalescer.pushed(1, sender.clone());
        coalescer.pushed(2, sender.clone());
        assert_eq!(recorder.0.lock().unwrap().len(), 2, "phone 2's at once");
        tokio::time::sleep(WINDOW * 2).await;
        let sent = recorder.0.lock().unwrap().clone();
        let ones: Vec<Instant> = sent
            .iter()
            .filter(|(d, _)| *d == 1)
            .map(|(_, at)| *at)
            .collect();
        assert_eq!(ones.len(), 2, "{sent:?}");
        assert!(
            ones[1] >= second,
            "the trailing send comes after the second push"
        );
        // Quiet again: the next push goes at once.
        coalescer.pushed(1, sender.clone());
        assert_eq!(recorder.0.lock().unwrap().len(), 4);
    }
}
