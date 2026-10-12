-- The server's own Storytel session (design 26b §2.1): the refused-login cooldown (vibb
-- storytel-login-refused.json) for the login generation it refused, and the card's last outcome
-- (a code, never text: `login_error`, with its time `login_error_at`).
-- Also from 0072 on: a Storytel entry's music_items rows are state 'ok' with url NULL (listed and
-- playable; the phone mints the audio URL), which 0051's comment ("ok (it has a URL)") predates.
--
-- Written as 0066 on the branch `music-2-storytel`; renumbered 0072 at the merge of music-1b-sonos
-- (with main) on 2026-10-12: main's 0065-0069 are the security wave's and design 25's, 0070 is
-- reserved for design 27 by the main session, 0071 is 26a's Sonos rooms. If 0070 or 0071 reaches a
-- server after this one ran, sqlx applies it out of order and `startup::pre_migration_backup`
-- (MAX(version) only) takes no backup first: 26a's and design 27's servers ship before or together
-- with this one (server/CLAUDE.md, "Data model").
ALTER TABLE music_storytel ADD COLUMN login_refused_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_refused_generation INTEGER;
ALTER TABLE music_storytel ADD COLUMN login_ok_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_error TEXT;
ALTER TABLE music_storytel ADD COLUMN login_error_at TEXT;
-- A phone's library holds Storytel entries only with its switch on and a login stored (§2.7).
CREATE TRIGGER device_policy_storytel_revision AFTER UPDATE OF music_storytel ON device_policy
WHEN OLD.music_storytel IS NOT NEW.music_storytel
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER music_storytel_presence_revision AFTER UPDATE OF ciphertext ON music_storytel
WHEN (OLD.ciphertext IS NULL) IS NOT (NEW.ciphertext IS NULL)
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
