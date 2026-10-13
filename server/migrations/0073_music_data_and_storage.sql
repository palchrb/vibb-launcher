-- Vibb music on mobile data and the music storage limit (design 30 §1.2, §1.8).
-- The one switch `music_mobile_data` ("downloads and streaming also off Wi-Fi") splits in two:
-- streaming (an item that isn't downloaded plays off Wi-Fi) and downloads (the offline episodes and
-- Storytel books download off Wi-Fi). Both take the old value, so every phone behaves as before
-- until the parent changes something; the policy's `mobile_data` key stays, as "both" (AND).
-- `music_storage_limit_gb` is the parent's per-phone limit on the music app's audio cache:
-- 1, 2, 5 or 10 (GB, decimal), NULL = no limit; every phone, new or existing, starts at 2.
-- No trigger or index names `music_mobile_data` (precedents for the drop: 0012, 0017).
--
-- Written as 0073 on the branch `design-30-s1` (main at 0072); the number may move at the merge if
-- another branch takes 0073 first.
ALTER TABLE device_policy ADD COLUMN music_stream_mobile_data INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN music_download_mobile_data INTEGER NOT NULL DEFAULT 0;
UPDATE device_policy SET music_stream_mobile_data = music_mobile_data,
                         music_download_mobile_data = music_mobile_data;
ALTER TABLE device_policy DROP COLUMN music_mobile_data;
ALTER TABLE device_policy ADD COLUMN music_storage_limit_gb INTEGER DEFAULT 2;
