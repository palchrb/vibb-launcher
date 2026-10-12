-- Design 27 (docs/design/27-music-episode-art.md §1.2, with its design QA): per-episode art. The
-- sweep fetches an NRK/RSS item's own picture, stores it as a 512 px JPEG in `data/music_art`
-- (photos::MUSIC_ART, not backed up) and the listing's `art` names it by hash.

-- `art_from`: the `art_url` the art fields describe (an item whose `art_url` differs is untried).
-- `art_state`: 'own' (its own picture, `art_hash`), 'same' (the series picture: nothing stored),
-- 'failed' (the fetch or decode failed; retried a day later, at most 3 times per URL).
-- `art_print`: the picture's 64-bit dHash (photos::print), kept for 'own' and 'same', so a new
-- series cover re-judges without a request. `art_at`: the last attempt; `art_fetched_at`: the last
-- one that sent a request (the 400-a-day budget counts only those, QA L1 a).
ALTER TABLE music_items ADD COLUMN art_hash TEXT;
ALTER TABLE music_items ADD COLUMN art_from TEXT;
ALTER TABLE music_items ADD COLUMN art_state TEXT CHECK (art_state IN ('own', 'same', 'failed'));
ALTER TABLE music_items ADD COLUMN art_print INTEGER;
ALTER TABLE music_items ADD COLUMN art_at TEXT;
ALTER TABLE music_items ADD COLUMN art_fetched_at TEXT;
ALTER TABLE music_items ADD COLUMN art_fails INTEGER NOT NULL DEFAULT 0;
CREATE INDEX music_items_art_hash ON music_items(art_hash) WHERE art_hash IS NOT NULL;
-- Step 2's lookup across entries (QA L1 d) and the day's count.
CREATE INDEX music_items_art_from ON music_items(art_from) WHERE art_from IS NOT NULL;
CREATE INDEX music_items_art_fetched ON music_items(art_fetched_at) WHERE art_fetched_at IS NOT NULL;

-- The reference prints a picture is judged against (QA M2): the source cover's (`cover_print`,
-- made when it is stored or read from the stored file), NRK's 16:9 series image centre-cropped
-- (`cover_alt_*`, one request when its URL changes) and up to 3 earlier covers' (`old_prints`, a
-- JSON array). `art_refs`: the set the items were last judged against (a change re-judges them).
ALTER TABLE music_listings ADD COLUMN cover_print INTEGER;
ALTER TABLE music_listings ADD COLUMN cover_alt_url TEXT;
ALTER TABLE music_listings ADD COLUMN cover_alt_print INTEGER;
ALTER TABLE music_listings ADD COLUMN cover_alt_at TEXT;
ALTER TABLE music_listings ADD COLUMN old_prints TEXT;
ALTER TABLE music_listings ADD COLUMN art_refs TEXT;
-- §1.4: 0 = only new items are tried (5 pictures in a row were the series picture and the entry
-- has none of its own); retried once a month (`art_backlog_at`, QA L1 e).
ALTER TABLE music_listings ADD COLUMN art_backlog INTEGER NOT NULL DEFAULT 1;
ALTER TABLE music_listings ADD COLUMN art_same_run INTEGER NOT NULL DEFAULT 0;
ALTER TABLE music_listings ADD COLUMN art_backlog_at TEXT;
-- QA M3: NRK episodes listed before this took the 300 px picture, and a routine walk never reads a
-- known episode again: the next check of each NRK entry walks its pages once more for the 512 px
-- picture of the episodes it has.
ALTER TABLE music_listings ADD COLUMN art_refresh INTEGER NOT NULL DEFAULT 0;
UPDATE music_listings SET art_refresh = 1
    WHERE entry_id IN (SELECT id FROM music_entries WHERE source = 'nrk');
