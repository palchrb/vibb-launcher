-- handy step 7 (docs/design/07-battery-fcm-play.md in the handy workspace): FCM nudges and Play.

-- One row per device that has reported push state. fcm_ok is this server's health verdict for the
-- current token (acks seen, see src/push.rs); the policy only tells the phone to use FCM while it
-- is true. Send/ack times are unix seconds.
CREATE TABLE device_push (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    fcm_token TEXT,
    fcm_token_updated_at TEXT,
    fcm_token_seen_at TEXT,
    push_transport TEXT,
    last_nudge_at TEXT,
    fcm_ok INTEGER NOT NULL DEFAULT 0,
    last_fcm_error TEXT,
    last_fcm_error_at TEXT,
    last_send_at INTEGER,
    last_send_nonce TEXT,
    send_pending INTEGER NOT NULL DEFAULT 0,
    unacked_sends INTEGER NOT NULL DEFAULT 0,
    last_ack_at INTEGER
);

-- The phone's push object as reported (capped JSON), Play install mode and the nightly Play update
-- window.
ALTER TABLE device_status ADD COLUMN push_state_json TEXT;
ALTER TABLE device_status ADD COLUMN install_mode_until_ms INTEGER;
ALTER TABLE device_status ADD COLUMN play_window_active INTEGER NOT NULL DEFAULT 0;
