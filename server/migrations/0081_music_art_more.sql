-- Episode pictures as soon as possible (the user, 2026-10-19; design 39's addendum): the art pass
-- has no picture budget any more, and an entry whose check left untried pictures (cut short by the
-- check's own request or time budget, or a host's blip) is due again at once. This is how many.
ALTER TABLE music_listings ADD COLUMN art_more INTEGER NOT NULL DEFAULT 0;
-- The day's count of image requests (design 27's `ART_PER_DAY`) limits nothing any more.
DROP TABLE music_art_requests;
