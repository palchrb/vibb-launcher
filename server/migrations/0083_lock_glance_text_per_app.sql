-- Design 51 (docs/design/51-glance-text-per-app.md §1): "Show the text" per app on the lock's
-- glance page, no global switch. Each ticked app has its own flag (0 = the sender only); the
-- existing ticks take the phone's old switch. A new tick starts at the sender only, and an untick
-- drops the row, so a re-tick starts there again. device_policy.lock_glance_text is unused after
-- this backfill (the column stays; nothing reads or writes it).
ALTER TABLE device_lock_glance_apps ADD COLUMN show_text INTEGER NOT NULL DEFAULT 0;
UPDATE device_lock_glance_apps SET show_text = 1
WHERE device_id IN (SELECT device_id FROM device_policy WHERE lock_glance_text = 1);
