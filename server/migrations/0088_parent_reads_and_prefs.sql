-- Design 62 step 5 (docs/design/62-parent-accounts.md §1, §5): Notify Me About and the inbox's read state per parent.
-- notification_prefs: one row per account that changed its switches; no row = every switch on. Seeded from the
--   family's switches for every account that isn't removed; the singleton goes (a missed reader fails loudly).
-- parent_event_reads: a row = this account has read this event. Seeded from the old read_at for every account that
--   isn't removed; read_at and its index go.
CREATE TABLE notification_prefs (
    admin_id INTEGER PRIMARY KEY REFERENCES admin_users(id) ON DELETE CASCADE,
    push_pin_used INTEGER NOT NULL DEFAULT 1,
    push_pin_lockout INTEGER NOT NULL DEFAULT 1,
    push_foreign_access INTEGER NOT NULL DEFAULT 1,
    push_unseen_boot INTEGER NOT NULL DEFAULT 1,
    push_low_battery INTEGER NOT NULL DEFAULT 1,
    push_phone_off INTEGER NOT NULL DEFAULT 1,
    push_phone_on INTEGER NOT NULL DEFAULT 1
);
INSERT INTO notification_prefs (admin_id, push_pin_used, push_pin_lockout, push_foreign_access,
    push_unseen_boot, push_low_battery, push_phone_off, push_phone_on)
  SELECT a.id, s.push_pin_used, s.push_pin_lockout, s.push_foreign_access, s.push_unseen_boot,
    s.push_low_battery, s.push_phone_off, s.push_phone_on
  FROM admin_users a, notification_settings s WHERE a.removed_at IS NULL;
DROP TABLE notification_settings;

CREATE TABLE parent_event_reads (
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    event_id INTEGER NOT NULL REFERENCES parent_events(id) ON DELETE CASCADE,
    PRIMARY KEY (admin_id, event_id)
) WITHOUT ROWID;
CREATE INDEX idx_parent_event_reads_event ON parent_event_reads(event_id);
INSERT INTO parent_event_reads (admin_id, event_id)
  SELECT a.id, e.id FROM admin_users a, parent_events e
  WHERE a.removed_at IS NULL AND e.read_at IS NOT NULL;
DROP INDEX idx_parent_events_unread;
ALTER TABLE parent_events DROP COLUMN read_at;
