**Captured** (design 53 §5 check 1, 2026-10-21): `capture_2026-10-21.json` is every message one of the user's speakers
sent to `scripts/sonos-ws-probe.py <ip> --all --save` while `scripts/sonos-play-test.py --volume 0` played on it -
the handshake, the probe's subscriptions of every group (each other group answering MOVED), then play, pause,
resume, a seek, next, previous with the muted seek, the natural end and the advance, the park - 58 messages, scrubbed
by `sonos-ws-probe.py --rescrub` (every uid, household, address, name, serial number, device id, location, queue and
item id and picture URL replaced by a fake one, consistently: `RINCON_000000000000014xx`, `Sonos_FAKE...`,
`10.0.0.x`, `Navn N`, `FAKE-SERIAL-N`, `FAKEDEVICENN`, `item-N`, `images.example.invalid`). `SonosEventsTest` replays
it whole through `SonosEvents.Plan`.

`handshake.json` is the capture's own messages in the order the app's handshake gets them (the empty command's
`globalError` carrying the household, `getGroups`, the playback subscribe's ack, the `getPlaybackStatus` reply, the
`groups` subscribe's ack). The other files are written by hand in the capture's shapes for what one session couldn't
show: `groups_changed.json` (a room joins, our room grouped away, coordinating again under a new id), `moved.json`
(a MOVED for our own subscribed group, and an UPDATED), `subscribe_refused.json` (a refused subscribe), and
`playback_events.json` (the state spellings, another group's status, an unknown state, a metadata event).

**Captured, a Spotify queue** (design 54's measuring run 54-0, 2026-10-21): `spotify_queue_2026-10-21.json` is every
message one of the user's speakers sent to `scripts/sonos-ws-probe.py <ip> --save` while `scripts/sonos-play-test.py
--volume 0 --spotify <link>` played a playlist from the speaker's own queue - the handshake, the other groups' MOVED
replies, the clear and the ShareLink add (`queueVersion` 37 -> 39), the start at position 1, a jump to position 3, a
pause and a jump back to 2 while paused, the speaker's own advance from 2 to 3 (PLAYING with a new `itemId`, no
BUFFERING, `previousPositionMillis` past the track's end), REPEAT_ALL then NORMAL (`playModes.repeat`), the park's
clear (`queueVersion` 40, IDLE) - 53 messages. `metadataStatus` names the playlist as the container and the current and
next tracks. Scrubbed by `sonos-ws-probe.py --rescrub` with design 54's rule on top: every Spotify id replaced by a
fake one of `server/testdata/spotify/` (the playlist `0FakePlaylistKveld0001`; the tracks at queue positions 1-4 are
`1FakeTrackA...`, `C`, `D` and `E`), so `scripts/fake-sonos.py` and these messages name the same tracks. The raw
capture stays outside the repo (`~/handy-captures/`). `SonosEventsTest`'s `d54` replays it.

Each file is a JSON array of `[header, body]` messages in the order a speaker sends them.
