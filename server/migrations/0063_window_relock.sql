-- Design 24b (docs/design/24b-override-relock.md): the phone's last relock of a parent-PIN window
-- (an override or a pause that ended) - what it reset and what it couldn't undo, stored through
-- the known fields only (window_relock::sanitize_relock). NULL from older launchers.
ALTER TABLE device_status ADD COLUMN relock_json TEXT;
-- The fix round (QA High 1 (b)): a device admin or an accessibility service of an app outside the
-- allowlist - the phone refuses the parent PIN while it reports one (window_relock::
-- sanitize_foreign_access). NULL when nothing was found, and from older launchers.
ALTER TABLE device_status ADD COLUMN foreign_access_json TEXT;
