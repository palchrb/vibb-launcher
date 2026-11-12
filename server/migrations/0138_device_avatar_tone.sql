-- Design 85 §5: every phone's own avatar colour until a photo is set. `avatar_tone` is an index into
-- `handlers::devices::AVATAR_TONES` (0 peach, 1 sky, 2 butter, 3 lilac, 4 sage, 5 teal, 6 rose), chosen once when the
-- phone is added (`devices::next_tone`: the first tone no phone has, past seven the one the fewest have) and stored, so
-- it never moves when another phone is removed. The phones there are numbered by id: the oldest keeps today's peach.
ALTER TABLE devices ADD COLUMN avatar_tone INTEGER NOT NULL DEFAULT 0;
UPDATE devices SET avatar_tone =
    ((SELECT COUNT(*) FROM devices AS earlier WHERE earlier.id < devices.id) % 7);
