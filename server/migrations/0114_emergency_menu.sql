-- Design 73 (round B2): a phone's emergency menu, the numbers the kid gets when it taps Nødsamtale, in the parent's
-- order. No rows = the default menu, 112 alone (`emergency::device_menu`); the first write stores 112 first and then
-- applies the change. `number` is normalised (`phone::normalize`), `name` may be empty (the phone then says
-- "Emergency number"). At most `emergency::MAX_ENTRIES` rows, at least one of them on the fixed emergency list
-- (`testdata/emergency_numbers.json`) - the handlers keep both.
CREATE TABLE device_emergency_numbers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    number TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    sort_order INTEGER NOT NULL DEFAULT 0,
    UNIQUE (device_id, number)
);
CREATE INDEX idx_device_emergency_numbers ON device_emergency_numbers(device_id, sort_order);
