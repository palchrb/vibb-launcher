-- Design 26a §5.5 (step 3c): the Sonos rooms a phone's music app may play on. NULL = every room
-- (the default); a JSON array of speaker uids (`RINCON_` + 12-20 of [A-Z0-9], at most 32) = only
-- those, `[]` = none. Written only by the device page's Music card (`#music-sonos`); read into the
-- phone's library (`sonos_rooms`), so a change moves the library revision (migration 0050's rule
-- for anything a library is built from).
--
-- Numbered 0065 on the branch `music-1b-sonos` (main had 0063-0064 when it was written); renumber
-- it after the final rebase if main has moved on.
--
-- A new column and a trigger only: safe on an existing database.
ALTER TABLE device_policy ADD COLUMN music_sonos_rooms_json TEXT;

CREATE TRIGGER device_policy_sonos_rooms_revision
AFTER UPDATE OF music_sonos_rooms_json ON device_policy
WHEN OLD.music_sonos_rooms_json IS NOT NEW.music_sonos_rooms_json
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;
