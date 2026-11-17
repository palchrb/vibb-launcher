-- Design 108b: the last push through our own Matrix gateway (R2: it ends the outside-gateway mark), and the last
-- push refused by the VAPID check (R3: the PWA's warning and Q3's recovery). Both written at most once a minute.
ALTER TABLE up_registrations ADD COLUMN gateway_at TEXT;
ALTER TABLE up_registrations ADD COLUMN vapid_refused_at TEXT;
