-- Design 126 A5, the user's decision of 2026-11-22: "Blokker 2G" stays on abroad too; "Tillat 2G i utlandet" on the
-- Mobilnett page opts a phone into the abroad lift (the design's option (b)). Off for every phone, the existing ones too.
ALTER TABLE device_policy ADD COLUMN allow_2g_abroad INTEGER NOT NULL DEFAULT 0;
