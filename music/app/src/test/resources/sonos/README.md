The canned UPnP replies `SonosFixturesTest` parses and `scripts/fake-sonos.py` answers from (one source of truth), the
golden DIDLs and the golden share-link requests. `ws/` holds the event socket's messages (its own README).

**Design 54 (Spotify on a room, round 54-a1).** From the user's measuring run 54-0 (2026-10-21): `add_uri_to_queue.xml`
(first 1, 30 added, queue 30 - the run's answer) and `media_info_queue.xml` (`NrTracks` 30); the rest is written by
hand in SoCo's and the speaker's shapes and is **provisional** until the 54-0 remainder reads the real ones (design 54
§5): `add_uri_to_queue_empty.xml` (0 added, [D12]), `browse_queue_page.xml` (`Q:0/1..5`: the fake playlist's queue -
`1FakeTrackA`, an episode the server's listing drops, `C`, `A` again, `D`; `UpdateID` 39; the episode's TrackURI form is
[D11]'s), `browse_queue_meta.xml` (`childCount` 30), `position_spotify_queue.xml` (position 3 playing),
`transport_settings.xml`, `sleep_timer.xml` ([D21]) and `fault_800.xml` (the refused add's code is a guess, [D12]).
Every id is a fake one of `server/testdata/spotify/`.

**The share-link goldens** `sharelink_{playlist,album,track,show,episode}.xml`: `AddURIToQueue`'s arguments in order
for each share type (`ShareLinkTest` compares `ShareLink.kt` and the wire with them byte for byte), written by
`scripts/sonos-play-test.py --golden DIR` - by the real SoCo `ShareLinkPlugin` when it is importable (`writer="soco
<version>"`: vibb's venv on the Pi, design 54 §2.2), else by the script's own transcription `share_request`
(`writer="share_request"`). **The committed ones are `share_request`'s** (no SoCo here): the run on the Pi that writes
them with SoCo is owed. On 2026-10-21 they were checked byte for byte against SoCo master's `soco/plugins/sharelink.py`
run with stub base classes: identical. Regenerate, never hand-edit.
