-- Storage full: a status report's `time_state.storage_full` (the phone couldn't save its screen-time record; it keeps
-- counting until a restart) raises the event storage_full at most once a day per phone. Notify Me About's switch for
-- it, on by default. The flag itself rides in device_status.time_state_json.
ALTER TABLE notification_prefs ADD COLUMN push_storage_full INTEGER NOT NULL DEFAULT 1;
