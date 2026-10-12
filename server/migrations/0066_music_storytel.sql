-- The server's own Storytel session (design 26b §2.1): the refused-login cooldown (vibb
-- storytel-login-refused.json) for the login generation it refused, and the card's last outcome
-- (a code, never text: `login_error`).
-- Also from 0066 on: a Storytel entry's music_items rows are state 'ok' with url NULL (listed and
-- playable; the phone mints the audio URL), which 0051's comment ("ok (it has a URL)") predates.
ALTER TABLE music_storytel ADD COLUMN login_refused_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_refused_generation INTEGER;
ALTER TABLE music_storytel ADD COLUMN login_ok_at TEXT;
ALTER TABLE music_storytel ADD COLUMN login_error TEXT;
-- A phone's library holds Storytel entries only with its switch on and a login stored (§2.7).
CREATE TRIGGER device_policy_storytel_revision AFTER UPDATE OF music_storytel ON device_policy
WHEN OLD.music_storytel IS NOT NEW.music_storytel
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER music_storytel_presence_revision AFTER UPDATE OF ciphertext ON music_storytel
WHEN (OLD.ciphertext IS NULL) IS NOT (NEW.ciphertext IS NULL)
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
