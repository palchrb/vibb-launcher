-- A colour per calendar (design 69, "A colour per calendar instead of a 'Skole:' prefix", 2026-10-27): the dot before
-- the time on Dagen's rows and on the lock's reminder row, sent as `calendar.calendars[].color`. One of eight calm
-- names (`calendar::COLORS`; the phone keeps the ARGB values), never hex. NULL = no dot. Peach is the kid's own
-- calendar's and never stored. The first free name is chosen when a calendar is first ticked (`handlers::calendar`).
-- 0105 is the ICS feeds branch's (`calendar-feeds-1`): this file was numbered past it on purpose.
ALTER TABLE device_calendars ADD COLUMN color TEXT
    CHECK (color IS NULL OR color IN ('sky', 'forest', 'sun', 'berry', 'sea', 'lavender', 'sand', 'slate'));
