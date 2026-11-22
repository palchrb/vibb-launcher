-- The Matrix ID belongs to the contact (the user, 2026-11-22: one person, one ID): `contacts.message_address` is the
-- ID every phone's Message button opens Element X with, edited on any phone's Calls & Contacts page and seen on all of
-- them. Each phone keeps only which app its button opens (`device_contacts.message_app`, NULL = the phone's default).
-- Filled from the per-phone rows: a contact with different IDs on different phones takes the one on the phone with the
-- lowest device id (the oldest phone; the rows carry no time to pick a newer one by).
ALTER TABLE contacts ADD COLUMN message_address TEXT
    CHECK (message_address IS NULL OR length(message_address) BETWEEN 1 AND 255);
UPDATE contacts SET message_address = (
    SELECT dc.message_address FROM device_contacts dc
    WHERE dc.contact_id = contacts.id AND dc.message_address IS NOT NULL
    ORDER BY dc.device_id LIMIT 1
);
ALTER TABLE device_contacts DROP COLUMN message_address;
