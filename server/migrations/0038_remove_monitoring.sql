-- Cleanup (2026-10-06): the conversation journal (kids-mdm-im, migration 0015) and browser
-- history (kids-mdm-browser, migration 0016) are removed - routes, viewers and the launcher's
-- sync are gone, and so is the collected data. The journal's media files under
-- data/journal_media are deleted at startup (retention::remove_journal_media). The connection
-- runs with secure_delete on (connect_db), so the dropped pages are overwritten, not just freed.
DROP INDEX IF EXISTS idx_device_journal_entries_thread;
DROP TABLE IF EXISTS device_journal_entries;
DROP INDEX IF EXISTS idx_device_browser_history_entries_device_time;
DROP TABLE IF EXISTS device_browser_history_entries;
