**Provisional** (design 53 §3): these messages are written from the shapes measured on the user's speakers on
2026-10-21 (design 53 §0.1, `scripts/sonos-ws-probe.py`, `scripts/sonos-play-test.py`), not captured byte for byte.
The capture (§5 check 1: `sonos-ws-probe.py <ip> --all --save ws.json ...`: the same JSON array format, ids, addresses
and names already replaced - split into these scenario files) replaces them in
one commit; every id stays fake (`RINCON_000000000000014xx`, `Sonos_FAKE...`, private 192.168.1.x addresses).
Each file is a JSON array of `[header, body]` messages in the order a speaker sends them; `SonosEventsTest` replays
them through `SonosEvents.Plan`.
