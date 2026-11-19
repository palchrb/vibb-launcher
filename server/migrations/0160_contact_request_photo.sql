-- Calls & Contacts tidied (6), approved 2026-11-19: the kid's contact request carries the picture the kid gave it on
-- the phone (design 79 §5.5), uploaded after the request was accepted (`POST /api/devices/contact-request/{id}/photo`).
-- Stored as a parent's contact photo is (`photos::process_contact`, cover): `photo_hash` the square in
-- `data/contact_photos/`, `photo_original_hash` the kept original in `data/contact_photo_originals/`, `photo_view` its
-- view - all three NULL, or all three set, and set only once. "Legg til…" copies them to a contact new to the family;
-- a decision, a lapse (7 days from the ask) and retention clear them, and `photos::prune` deletes the files nobody
-- names.
ALTER TABLE device_contact_requests ADD COLUMN photo_hash TEXT;
ALTER TABLE device_contact_requests ADD COLUMN photo_original_hash TEXT;
ALTER TABLE device_contact_requests ADD COLUMN photo_view TEXT;
