-- Server round 5 (docs/design/r5-server-small-items.md §2, §4).
--
-- Episode pictures: an entry checked under the old caps (before 0081, 2026-10-19) may still hold
-- pictures never tried. Flag it (`art_more` is a count; 1 = "some left") so the next sweep takes
-- it at once instead of at its 6-hour regular time. The condition is the art pass's own
-- (`music_sweep::art_candidate`/`art_due`): a listed item with a picture URL not tried at that URL.
UPDATE music_listings SET art_more = 1
WHERE art_more = 0 AND EXISTS (
    SELECT 1 FROM music_items i
    WHERE i.entry_id = music_listings.entry_id AND i.state = 'ok' AND i.art_url IS NOT NULL
      AND (i.art_from IS NULL OR i.art_from <> i.art_url)
);

-- The last time a push to this subscription was opened (a tap: the `?push=` marker or the
-- worker's `clicked` receipt). Deliveries are pruned after 30 days; this outlives them, for the
-- device row's "N since one was opened".
ALTER TABLE push_subscriptions ADD COLUMN last_opened_at TEXT;
