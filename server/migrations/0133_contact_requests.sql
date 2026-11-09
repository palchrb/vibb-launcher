-- Design 77 §14 (step 6): the kid asks for a new contact from the phone book's "+" (`POST /api/devices/contact-request`).
-- `id` is the phone's (16-64 lowercase hex); `name` the kid's, cleaned (`calendar::clean`, at most 40 characters);
-- `number` normalised by the server (`phone::normalize`); `matrix_id` the kid's suggestion (`valid_matrix_id`), saved
-- on a contact only when the parent ticks "Bruk Matrix-ID-en"; `asked_at` the phone's epoch milliseconds (never after
-- the server's now); `received_at` the server's epoch milliseconds, which the day's cap counts by. `state`: `open`
-- until the parent adds the number ("Legg til…" or any Add Contact with it) - `added` - or declines it - `declined`;
-- the policy's `call_policy.contact_requests` carries the rows of the last 7 days. Purged 30 days after `received_at`
-- (retention).
CREATE TABLE device_contact_requests (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    number TEXT NOT NULL,
    matrix_id TEXT,
    asked_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'declined', 'added')),
    decided_at TEXT,
    PRIMARY KEY (device_id, id)
);
CREATE INDEX idx_device_contact_requests_received ON device_contact_requests(device_id, received_at);

-- §14: the parent's push switch for a request, on by default.
ALTER TABLE notification_prefs ADD COLUMN push_contact_request INTEGER NOT NULL DEFAULT 1;
