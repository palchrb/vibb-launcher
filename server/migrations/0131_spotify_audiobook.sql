-- Spotify audiobooks (2026-11-09): a Spotify show whose every item was an audiobook's chapter. The PWA's entry page
-- says a Spotify audiobook needs listening time on the child's account; the phone's library marks the entry
-- (`audiobook`), so the web player can say why nothing of it plays. The library says it: a change moves the cached
-- libraries (0050's rule).
ALTER TABLE music_listings ADD COLUMN spotify_audiobook INTEGER NOT NULL DEFAULT 0 CHECK (spotify_audiobook IN (0, 1));
CREATE TRIGGER music_listings_spotify_audiobook_revision AFTER UPDATE OF spotify_audiobook ON music_listings
WHEN OLD.spotify_audiobook IS NOT NEW.spotify_audiobook
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
-- Every Spotify show reads in full at its next check (no gate mark), so a book listed under the spike loses its
-- sample and gets the marker without waiting for a new chapter.
UPDATE music_listings SET spotify_snapshot = NULL
WHERE spotify_mode = 'list'
  AND entry_id IN (SELECT id FROM music_entries WHERE source = 'spotify' AND target LIKE 'spotify:show:%');
