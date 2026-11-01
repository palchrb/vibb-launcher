-- The Spotify diagnostic log for 15 minutes (2026-11-01, PLAN "Spotify diagnostic log for 15 minutes"): Device ›
-- Advanced's one-shot button writes now + 15 minutes here (epoch seconds, UTC), sent as the policy's
-- `music.spotify_diag_until` while it is in the future (`music::policy_music`); the launcher hands it to the music app
-- as the managed config's `spotify_diag_until`, and the music app writes its `VibbSpotify` log lines until then.
-- NULL = never turned on. Nothing else reads it.
ALTER TABLE device_policy ADD COLUMN music_spotify_diag_until INTEGER;
