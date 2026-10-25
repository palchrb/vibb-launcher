-- A phone's own picture (server round C, 2026-10-25, the user: "Når kommer muligheten til å sette avatar?"): the
-- SHA-256 of the square JPEG `photos::DEVICE_AVATARS` stored for it in `data/device_avatars/<hash>.jpg`, NULL for the
-- letter tile. The parent's PWA only (the Devices list, the phone's header, every phone row, the push icon); never
-- sent to a phone. Backed up with the file like a contact photo (`device_avatars/<hash>.jpg` in the zip).
ALTER TABLE devices ADD COLUMN avatar_hash TEXT;
