-- A contact photo keeps a downscaled original (the user, 2026-11-03, "Ja takk"; QA photo-crop M2): "Adjust crop"
-- re-cuts the 512 px square from it, so it can widen again and never loses a generation. `photo_original_hash` is the
-- SHA-256 of the re-encoded original (`data/contact_photo_originals/<hash>.jpg`, `photos::CONTACT_ORIGINALS`: at most
-- 2048 px on its long side and about 3.1 MP in all; upright, no EXIF/GPS/camera data, never sent to a phone),
-- `photo_view` the square the parent chose in it, "x,y,w,h" as fractions of the original's width and height. Both
-- NULL for a photo stored before this (an adjust refines the stored square, as before) and cleared with the photo.
-- 0122 is taken on another branch (lock weather).
ALTER TABLE contacts ADD COLUMN photo_original_hash TEXT;
ALTER TABLE contacts ADD COLUMN photo_view TEXT;
