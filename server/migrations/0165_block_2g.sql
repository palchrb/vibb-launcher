-- Design 126 A5: "Blokker 2G" on the Mobilnett page - `DISALLOW_CELLULAR_2G` on the phone, lifted by the pause and, with
-- the design's option (b), abroad by itself. On for every phone, the existing ones too (only emulators run Vibb).
ALTER TABLE device_policy ADD COLUMN block_2g INTEGER NOT NULL DEFAULT 1;
