# Music notes: the test fixtures and the fakes

Moved from music/CLAUDE.md's "Build and test" on 2026-11-27; the core file's 'Where the rest is' lists every note.

## The fixtures the fakes answer from

The canned SOAP replies in `src/test/resources/sonos/`
(`SonosFixturesTest`) are also what `scripts/fake-sonos.py` answers from (vibb's "two fakes" rule; the fake has no event
socket - the app's connect to 1443 is refused and parks after five, so the emulator checks the polling fallback;
`sonos/ws/` holds the socket's messages - the user's capture of 2026-10-21 and hand-written cases -, `scripts/sonos-ws-probe.py --save`
captures real ones with every id, address, name, serial, device, location, queue and item id and picture URL replaced
(`--rescrub IN OUT` re-applies the rules to an earlier capture); design 53-b: the fake answers the grouping verbs - `x-rincon:` joins, `BecomeCoordinatorOfStandaloneGroup`
from `become_standalone.xml`): change a fixture
and run `python3 -I scripts/fake-sonos.py --selftest` (CI does, `music.yml`). On the emulator the app plays on the
fake speakers (docs/testing/emulator.md §6h; no multicast there, so no SSDP: the debug feed seeds the cache). Likewise
`src/test/resources/storytel/` (the asset 302 and the locked 403) and `../contracts/music/storytel_token_answers.json` (the server's
session route, `/fake/jwt`'s answers) are what `scripts/fake-storytel.py` answers from (`--selftest`, CI too; its login
and bookshelf are the server's, `server/testdata/storytel/`): its options `--books cid:MB,...`, `--locked`, `--refuse`,
`--jwt-ttl`, `--off`, `--busy S`, `--jwt-down`, `--revoke-after K`, `--token-ttl`, `--drop-at`, `--slow-mint`,
`--no-download` (minted but never on the phone, for a room) and `--print-listing ENTRY` (the debug feed's listing)
drive docs/testing/emulator.md §6i; every value in it is fake (`FAKE-JWT-<n>`, sessions `1:<16 hex>`,
`FAKE-SIG-<n>`, `k@example.invalid`, never `eyJ...`). `scripts/fake-audio.py` is a fake podcast
host for design 30's checks (silent WAV episodes with Range; `--vary` serves another length per request, the
changed-source check; `--print-listing ENTRY`; `--selftest`, CI too; docs/testing/emulator.md §6f). Spotify's
`src/test/resources/spotify/` (`play_request.json` - the exact play, context play, transfer, queue, shuffle and repeat
requests -, `play_answers.json`, `token_answers.json`, and `sdk_states/*.json`, the state sequences
`SpotifyTrackerTest` replays) and `server/testdata/spotify/` (the accounts and Web API answers) are what
`scripts/fake-spotify.py` answers from (`--selftest`, CI too): the accounts service with PKCE (`--as`, `--deny`,
`--rotate`, `--access-ttl`, `--expire-refresh`, `--bad-client`), the Web API the server and the phone use
(`--snapshot`, `--rate-limit N`, `--quota`, `--not-premium`, `--offset-404`, `--audiobook` - the show as an audiobook's chapters with its free sample, `--no-book-time` - the sample played in their place) and a stub Web Playback SDK playing a tone
per track through the WebView (`--elsewhere`, `--autoplay`, `--transfer-in`, `--unplayable`, `--relink`, `--no-eme`,
`--slow-ready`, `--shuffle`, `--repeat`, `--token-ttl`, `--track-seconds`), plus `--print-library` and
`--print-listing ENTRY [--tracks N]`; they drive docs/testing/emulator.md §6l. Every value in it is fake
(`FAKE-ACCESS-<n>`, `FAKE-REFRESH-<n>`, `FAKE-CODE-<n>`, client id `0123456789abcdef0123456789abcdef`, users
`fakeparent`/`fakekid`, never a `BQ...`/`AQ...` token shape).
