# 26c - Vibb music phase 3: Spotify (buildable spec)

Architect draft (2026-10-13), the first buildable step of design 26's phase 3 after the engine spike. Inputs: design 26
(phase 3 and "How to work"), the user's product definition and route decision (below), the spike's record
`docs/testing/spotify-spike.md` (§1 terms, §7 checklist, **§7b results**: item 10 passed on the emulator, forced Doze
included) and its throwaway APK on branch `spotify-spike` (`spike/spotify/`: `web-playback.html`, `WebPlayer.kt`,
`WebApi.kt`, `KeepAliveService.kt`, `WebPlaybackActivity.kt`, `NetProbe.kt` - the proven recipe), `vibb-spotify-options.md`
(the research), design 26a (the renderer axis: `Renderer`, `attach()`, `saveNow()`, `showsPlaying`, `RendererChoice`,
`PlayerHub.State.waiting`; its hush, bedtime and mobile-data sections), design 26b (the pattern this follows: a server
client with a gate, a sealed secret, a listing like NRK/RSS, a phone-side client behind a seam, a fake with
`--selftest`), design 27 (item art), design 28 (the listening window), the CLAUDE.md files, and vibb **`pipewire`
`1fcadac`**: `pi/vibb/spotify.py` (`to_uri`, `LINK_RE`/`URI_RE`, the `spotify.link` short links, `bookmark_step`,
`read_bookmark`, `PREV_RESTART_MS`, `context_tracks`), `pi/vibb/library.py` (`ORDERS`, the precache gate 426-618:
playlists by `snapshot_id`, albums/tracks/episodes immutable, artists "change invisibly", `expand_target`'s Spotify
branch 966-1023), `pi/daemon.py` (`_spotify_bookmarker` 4836-4900, the Sonos `spotify_sharelink` start 1580-1660),
`pi/sonosd.py` (`spotify_sharelink`, 35, 520-566) and `tests/sonos_spotify_queue.py`, and README §5 ("paste a Spotify
link (playlist/album/artist/audiobook)").

**Spotify is new ground** (design 26): the Pi's go-librespot path doesn't carry over (the audio-key denial for new
accounts). What the kid sees and how an entry behaves is vibb's (one tile per link, its list, resume per context, the
play-order setting, the same player); how the sound is made is new.

`M/` = `music/app/src/main/java/me/vibb/music/`, `S/` = `server/src/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`.
Markers: **[verified 2026-10-13]** = read on developer.spotify.com that day (the February 2026 dev-mode migration guide,
the July/March/May 2026 changelogs, the Start/Resume Playback, Get Playlist Items, Redirect URIs, Refreshing tokens,
Quota modes and Rate limits pages, the Web Playback SDK reference, the Developer Policy of 15 May 2025);
**[unverified]** = not confirmed from a primary source; **[device check]** = needs the Jelly Star and a real Premium
account; **[spike]** = shown by the spike's §7b on the emulator.

**In:** the family's own Spotify developer app (bring your own), parent-side login of Spotify accounts (PKCE in the
parent's browser, the server keeps the refresh tokens sealed), share links of playlists, albums, tracks, artists, shows
and episodes added in the PWA with per-phone ticks, the server listing them like an RSS feed (tracks/episodes as items,
per-track art through design 27), a per-phone "plays as" account (one per phone, the child's own; the parent's never
logged in - step 8), the phone playing through Spotify's Web Playback SDK in a headless WebView owned by the playback
service (a third renderer), resume per context, the kid's UI unchanged and naming no source (step 8), the launcher's
token courier and DNS exemption, a fake Spotify for tests and the emulator. **Out:** offline (the SDK has none), the
Spotify app / App Remote, Spotify on Sonos (designed as a later optional step 9), Liked Songs, Spotify audiobooks,
following a profile, shuffle (§13).

## 0. Summary

### 0.1 The product (the user's words, binding)

> "spotify skal fungere bare slik at man legger til en sharelink i server pwa, og velger at den skal over på tlf; så
> dukker den opp som en tile på tlf og oppfører seg som bare en annen podcast/rss"

The parent pastes a Spotify share link into the server's PWA, ticks the phones that get it, and it shows on each phone
as a tile that behaves like any other podcast/RSS entry: its list (the tracks or episodes), play/pause/skip/seek, resume
where it stopped, the same carousel, player and lock/Home surfaces. **The route is the WebView one** (the user's
decision after the spike): Spotify's Web Playback SDK in a headless WebView in the music app; no Spotify app on the phone
(App Remote's login failed on the emulator and needs Play services activities that lock task blocks, spike §7b). Offline is
"not very critical" (user): there is none.

### 0.2 Assumptions (stated to the user; the design rests on them - correct any that is wrong)

1. **Each child has their own Spotify Premium Family member account, one per phone, and only the children's accounts
   are logged in** (the user's answer to QA question 2, 2026-10-14: "barna skal ha en konto hver (1 pr tlf), men ser
   ingen grunn til at min konto skal involveres. Jeg fikser vel selv å paste in en linke"). Not a mobile-only Premium
   plan, which the SDK excludes [verified 2026-10-13: "mobile only types of premium subscriptions are excluded"]; the
   SDK plays only for Premium (an `account_error` otherwise). **The parent's own account is never logged in on the
   server**: the parent pastes the links, and each child's account both reads (§3.3) and plays (§2.5). So one of the
   app's five dev-mode users per phone. The code is unchanged by this (N accounts, one bound per phone, any of them a
   reader): it is the setup text and the advice that say it (step 8).
2. **Development mode is accepted for private use**: the family's own developer app, at most 5 Spotify users per app,
   playlist contents only for playlists an authorized account owns or collaborates on, refresh tokens that end 6 months
   after a login [verified 2026-10-13]. Spotify may revoke the app; then Spotify tiles say so and nothing else breaks.
3. **Streaming follows the parent's streaming switch** for the music app - design 30's "Streaming may use mobile data"
   (`stream_mobile_data`; Wi-Fi only unless it is on, never roaming), as every other stream does; before the merge of
   main 571f86f it was the one `mobile_data` switch. The downloads switch and the music storage limit never apply:
   Spotify has no downloads and nothing of it is cached (design 30's played extras and cache-through never see it).
4. **A playlist lists only when a child's account owns it or collaborates on it** (dev mode lists a playlist's items
   only to its owner or a collaborator, and only the children's accounts are logged in): the parent makes the playlist
   while logged into the child's account in the Spotify app, or invites the child to collaborate on their own (in
   Spotify, the playlist's ⋯ › Invite collaborators) and opens the invite link **logged in as the child** (the private
   window the child was logged in from) to join - until the child's account has joined, it is no collaborator and the
   playlist stays a context (qa-26c-step8-code L3). Any other playlist plays in **context mode** (§3.3, §5.9): Spotify's order, no track list, no order
   setting, no NY, and only if the child's account can open it (public, shared by link or followed [device check]); it
   resumes at the last track while that track is still in the playlist (§5.9's offset), an artist never. Albums,
   tracks, shows and episodes list with any account.
5. **Spotify is opt-in and fails gracefully**: no developer app set up, no account, no Premium, no network, an expired
   login - the Spotify tiles say why in one line; NRK, RSS, own files and Storytel are untouched.
6. **The Developer Policy's clauses are documented, not dodged** (§7.3): this is a player for a child, which the Policy
   forbids for products; the user accepted that risk for private use on 2026-10-06 (`vibb-spotify-options.md`,
   "Decision").
7. **Links no family account may list play as a Spotify context** (QA question 1, answer (a), the default; the user's
   account answer of 2026-10-14 keeps it - the parent pastes links, so a playlist no child's account owns plays this
   way): an artist, and a playlist no logged-in (child's) account owns or collaborates on, play in Spotify's order with
   no track list (an artist with no resume, §5.9). Option (c), **an artist listed as its albums' tracks** (§3.3), is
   written up and gated on a dev-mode check in the QA checklist (item 18): if `GET /artists/{id}/albums` pages work for
   a dev-mode app, the user may switch artists to it. Option (b), refusing such links at add, is a later switch, built
   only if the user asks.
8. **Autoplay may be switched off on the children's accounts** (QA question 2): the phone never adopts a track outside
   our list (§5.4, H4's guard in code, whatever the account says); if the device check (QA checklist item 17) shows the
   web player autoplaying after a list, the PWA's setup text tells the parent to switch Autoplay off in each child's
   Spotify settings, so the guard has nothing to pause.

### 0.3 The shape

- **The server** holds the family's developer app setting (a client ID the parent registered at developer.spotify.com;
  the server ships none), logs Spotify accounts in through PKCE in the parent's browser, and keeps each account's
  refresh token sealed with the music key (the same AES-256-GCM as Storytel's login). It turns a share link into an
  entry (source `spotify`, target `spotify:<kind>:<id>`), **lists** it with the Web API like an RSS feed (one item per
  track or episode, `url: null`, the track's own art through design 27), re-sweeps playlists and shows, and mints a
  short-lived access token for a phone on request (`POST /api/devices/music/spotify/token`, device bearer).
- **Two account roles, one table** (§2.2, §2.5, §3.3): the listing reads with whichever authorized account can read the link (a
  playlist only its owner or a collaborator); the phone plays with **its** account (the kid's), chosen per phone in the
  PWA ("Spotify plays as"). **The family's setup (step 8, the user's answer)**: only the children's accounts are logged
  in, one per phone, each doing both - the parent's own account never is; a playlist one child's account owns or
  collaborates on lists for every phone that has the entry.
- **The phone** never sees a password or a refresh token. The music app asks the launcher (the only way to the server,
  phase 1's bridge rule) for an access token through a new AIDL method; the launcher asks the server. A headless WebView
  owned by `PlaybackService` loads our page (assets, an https origin) which loads Spotify's SDK script from
  `sdk.scdn.co`, registers a Connect device, and plays what the renderer tells it: the Web API `PUT /me/player/play` with
  the **track URIs of our own listing** (a window from the current item), the SDK's pause/resume/seek, the SDK's state
  events back over a `@JavascriptInterface`. That renderer is **`SpotifyRenderer`**, a Media3 `SimpleBasePlayer` like
  `SonosRenderer`, so the session, the notification, Home's card, the lock's tile and design 28's listening window keep
  working unchanged.
- **List mode is primary, context mode the fallback** (§3.3): an entry the Web API can list plays our list (any order
  setting, our resume, our skips). An artist (whose top tracks are gone from dev mode) and a playlist no authorized
  account may list play as a Spotify **context** (Spotify picks the tracks; the list shows the SDK's `track_window` as it
  plays; resume = the context bookmark, vibb's `bookmark_step`).
- **Positions are the phone's**, in Room like every entry's (per entry key + track key; the entry's bookmark = the track
  playing last and where): vibb's per-context bookmark, which Spotify's cloud doesn't keep for us.
- **The launcher** carries the token (no cache, no log) and stops its DNS filter from blocking Spotify's hosts on a
  phone that has Spotify (a fixed host list like FCM's, gated by a new policy flag).
- **Threading.** Server: `AppState.spotify` (`spotify::Accounts`: the pending logins and, per account, a tokio `Mutex`
  around its cached access token so one refresh runs at a time); every Web API and accounts request goes through
  `AppState.music_fetch` (the one gate). Phone: the WebView and `SpotifyRenderer` are main-thread; Web API calls and the
  token wait run on IO and post back; the SDK's JavaScript runs in the WebView's renderer process and talks to us only
  through the page bridge (§5.3).
- **Secrets**: the refresh tokens (sealed at rest, never sent anywhere but Spotify's token endpoint), the access tokens
  (memory only on every side; to the phone over the device-bearer route and the signature-permission bridge), a pending
  login's PKCE verifier (memory only, 10 minutes), the authorization code (in one redirect's query) never reach a log, an
  event, a page, Room, `music_state`, an error text or a `toString`/`Debug`. Fakes never look real (`FAKE-ACCESS-1`,
  `FAKE-REFRESH-1`, never a `BQ...`/`AQ...` token shape).

### 0.4 Decisions this design takes (where the brief left the choice)

| Question | Decision | Why |
|---|---|---|
| Whose developer app | The family's own, registered by the self-hoster at developer.spotify.com; client ID in the PWA (or `SPOTIFY_CLIENT_ID`); the server ships none | the user's rule; dev-mode limits are then each self-hoster's own; no shared client ID to revoke for everyone |
| OAuth flow | **Authorization code with PKCE, public client: no client secret** | PKCE returns refresh tokens and refreshes with `client_id` only [verified 2026-10-13: "client_id - Only required for the PKCE extension"]; no secret to store, leak or rotate; the spike's login was PKCE [spike] |
| Who logs in where | the parent, in their own browser, on the PWA ("Log in with Spotify"), logs in **each child's account** (never their own, step 8); the server keeps the refresh token | the phone never holds a password or a long-lived secret, the kid never sees a login screen, no WebView login on a kiosk phone (Google/Facebook sign-in refuse WebViews, spike §7 item 10); fallback: the loopback paste flow for a server without https (§2.3) |
| Which account reads, which plays | reads: any authorized account that can read the link (playlists: owner/collaborator) - in the family's setup a child's; plays: the phone's bound account, one per phone, the child's own (step 8) | dev mode lists playlist items only to owner/collaborators [verified 2026-10-13]; the SDK needs the listener's own Premium token; one account plays on one device at a time |
| Link types | playlist, album, track, show, episode: **list mode**; artist: **context mode** (the default; "artists as their albums' tracks" an option behind a dev-mode check, §3.3); a playlist no account may list: context mode; `spotify.link` short links resolved once; audiobook, Liked Songs, user profiles: refused with a sentence | §3.1 table, assumption 7 |
| How the phone plays | `PUT /me/player/play` with **`uris`** (our list, a window of at most 50 from the current item) and `position_ms`; past the window, the next item added with `POST /me/player/queue` one track ahead; SDK `pause`/`resume`/`seek`; a track Spotify plays that isn't ours is paused at once | our order, our skips, our list; track URIs need no access to the playlist; `offset` is documented only for a `context_uri` [verified 2026-10-13]; never uncurated audio (§5.4) |
| Shuffle | off for Spotify (decided, not a question) | the phone's rule: `canShuffle` is own files without resume (`logic/Queue.kt:18`), and every Spotify entry is resume on; vibb's `shuffle_context` is a deviation (§12) |
| Listing source | Web API with a reader token (primary); the SDK's `track_window` while playing (fallback, context mode) | §3.3 |
| Wake lock | `SpotifyRenderer` holds a partial wake lock and a Wi-Fi lock while it plays or starts, timed and renewed, released on every pause/end/error/detach | ExoPlayer holds `WAKE_MODE_NETWORK` for every stream; the spike's forced-Doze pass was the emulator without OEM battery management; the Jelly Star run is still owed [spike §7b] |
| DNS hosts | a fixed list (`spotify_hosts.json`, three byte-identical copies; provisional in step 3, the capture's list before any stable tag) exempted by the launcher's filter and dropped from the delivered blocklist **only for a phone with Spotify on** (policy `music.spotify`, and the binding hashed into `dns_filter_version`); the same list is the WebView's host allowlist | FCM's proven pattern (`push/FcmHosts.kt`, `play::is_fcm_protected`); one list, two gates; per phone is the stricter choice (FCM is global because every phone needs it) |
| SDK script | loaded at run time from `https://sdk.scdn.co/spotify-player.js`, not pinned (no SRI) | the Developer Terms forbid copying it (spike §1); Spotify changes it in place, a pin would break playback at its next release; the WebView admits only Spotify's hosts and our bridge is narrow (§7.2) |
| Fake Spotify | `scripts/fake-spotify.py`: accounts (authorize, token, refresh rotation), the Web API the server and phone use, **and a stub SDK** (the documented `Spotify.Player` surface, playing a generated tone through an `<audio>` element), which the debug build's WebView reaches through its request interceptor (26b M2's one debug switch, §9.3) | the whole chain runs on the emulator without an account; the real SDK, Widevine and Spotify's refusals remain device checks (§9) |
| Version | server **0.26.0**, music **`music-v0.4.0`** (RC first), a launcher tag | §Release |
| What the kid's app says (step 8, the user's decision 2026-10-14) | **no trace of Spotify**: a Spotify entry is a tile like any other - "Musikk" or "Podkast" on the cover, the same hints, no source line in the player or the list, the web player's lines without the word | "For barna skal jo dette bare være en tile til? Samme hvilke kilde?"; the attribution the Policy asks for is the PWA's only (§7.3) |

## 1. What vibb does, and where it lands

| vibb (`pipewire`) | Here | Notes |
|---|---|---|
| `spotify.py` `URI_RE`, `LINK_RE`, `is_spotify`, `to_uri` (playlist/album/artist/track/show/episode links, `intl-xx/` prefixes, `spotify:user:<id>:collection`) | `S/spotify.rs` `parse_link`, `canonical`; `M/spotify/SpotifyRef.kt` `spotifyTarget` (the stored target only) | the target is the canonical `spotify:<kind>:<id>`; ids `[A-Za-z0-9]{1,64}`; `user:..:collection` refused (§3.1) |
| `to_uri`'s `spotify.link/` (a redirect followed once, memoised) | `spotify::resolve_short_link` at add time, `follow = false`, the `Location` parsed as an `open.spotify.com` link | resolved once into the canonical target; never at play time |
| `snapshot(uri)` (go-librespot's `/cache/snapshot`) + `library.py` `_precache_due` (playlists by `snapshot_id`, album/track/episode immutable with a daily re-ask, show always, artist always) | the sweep's Spotify check (§3.4): a playlist's `snapshot_id` gates the item read; album/track/episode/artist daily; show at the interval | the same politeness, now against the Web API's quota |
| `context_tracks` (the listing from go-librespot, no Web API; "Title — Artists" rows, the album cover per row; artists too, `spotify.py:331-366`) | the server's listing (§3.4): one item per track, title `"<name> — <artists>"`, the album image as the item's `art_url` (design 27); an artist in context mode by default, as its albums' tracks as an option (§3.3) | the phone never lists (21b): the server does, with a reader token |

| `expand_target` Spotify branch: `order: "auto"` always (Spotify's context order) | `auto` = the listing's order (a playlist top first, an album in track order, a show newest first); `newest_first`/`oldest_first` reverse or keep it, as for every entry (`library.py` `ORDERS`) | we play our own list, so the parent's order setting can hold (§4.2); context mode keeps Spotify's order |
| `bookmark_step`/`save_bookmark`/`read_bookmark` (one bookmark per context: track uri, position, duration, name, artists, art; only a box-initiated session writes) | phase 1's `Bookmarks` (per entry key = `state_key(target)` = vibb's `bm_path` hash, per track key); a session we didn't start never writes (§5.4, "Not ours") | vibb's `play_origin` rule becomes "only our own command's state is ours" |
| `clear_bookmark` (stop / play `--fresh`), `clear_all_bookmarks` (account switch) | phase 1's `clear_state` at a queue's natural end; positions survive an account change (they are the kid's, not the account's) | §12 |
| `PREV_RESTART_MS` 5000 (prev deep in a track restarts it) | phase 1's `skipTarget` (the phone's own previous rule) | unchanged |
| `_spotify_bookmarker` (5 s ticks, write on track change or every 30 s, flush on pause) | the service's 15 s `saveTick`, `saveNow(force)` on pause/stop/detach, a write on every track change (`ItemChanged`) | the phone's existing cadence |
| `shuffle` -> `shuffle_context` | not ported (decided) | the phone's rule `canShuffle` (own files without resume; every Spotify entry is resume on) - §12 |
| `daemon.py` Sonos `spotify_sharelink` start (listing from go-librespot, the context bookmark's index and position, `/queue_play`) + `sonosd.py` ShareLink + `tests/sonos_spotify_queue.py` | **later step 9** (§11), not in the first release | needs the household's Spotify in the Sonos app; the speaker plays, not the SDK |
| `spotify_user` sections (`spotify_web.user_playlists`) | not ported | `GET /users/{id}/playlists` is removed in dev mode [verified 2026-10-13] |
| README §5 "audiobook" links | refused at add ("Spotify audiobooks can't be played here") | the Pi played them through go-librespot; no Web API listing/SDK path is known to work in Norway [unverified] |
| go-librespot's on-disk audio cache, `/cache/download` precache | none | the SDK keeps only the WebView's HTTP cache (~15 MB ring, not offline [spike 10b]) |

### 1.1 Platform facts this design rests on

- Dev mode (since 2026-02-11/03-09) [verified 2026-10-13]: the app owner needs Premium; 5 users per app (each added
  under User Management); 25 client IDs per developer since July 2026; `GET /playlists/{id}/items` "only for playlists
  the user owns or collaborates on" (403 otherwise), `GET /playlists/{id}` returns metadata without `items` for others;
  the playlist's `tracks` field renamed `items` and each entry's `track` renamed `item`; batch endpoints (`GET /tracks`,
  `/albums`, `/shows`, `/episodes`) removed; `GET /users/{id}`, `/users/{id}/playlists`, `/artists/{id}/top-tracks`,
  browse removed; `/me` has no `product`, `country` or `email` any more (so Premium can't be checked by the API: the SDK's
  `account_error` is the check); a track object has no `linked_from`, `popularity` or `available_markets` any more;
  quota per developer account, shared by all its dev-mode apps and every caller (the server's sweep **and** each
  phone's play calls), `429` with `"reason": "QUOTA_EXCEEDED"`, rate limits over a rolling 30 s window with
  `Retry-After`. A user not in the app's User Management gets 403 on every API request (Quota modes).
- Tokens [verified 2026-10-13]: access tokens live 1 hour; refresh tokens 6 months from the authorization, "refreshing
  an access token does not extend the refresh token's lifetime", no issue time is exposed ("store the authorization
  timestamp in your own database"); a refresh "might not" return a new refresh token - "when a refresh token is not
  returned, continue using the existing token" (and when one is, use the new one).
- Redirect URIs [verified 2026-10-13]: must be HTTPS unless a loopback literal (`http://127.0.0.1:PORT`,
  `http://[::1]:PORT`); `localhost` refused; an exact match, except the port of a loopback literal.
- Start/Resume Playback [verified 2026-10-13]: `PUT /me/player/play?device_id=`, body `context_uri` (albums, artists,
  playlists) or `uris` (track URIs), `offset` ("only available when context_uri corresponds to an album or playlist"),
  `position_ms`; Premium only; answers 204/401/403/429; "the order of execution is not guaranteed when you use this API
  with other Player API endpoints" (so a transfer and a play are sequenced by waiting for the first's answer and the
  SDK's state). No maximum for `uris` is documented [unverified: the window of 50 is our choice]. Transfer Playback
  (`PUT /me/player`, one `device_ids` entry, `play`) and Add Item to Playback Queue (`POST /me/player/queue?uri=`) are
  in the same Player API (not on the dev-mode removed list).
- Web Playback SDK [verified 2026-10-13]: `getOAuthToken` is called at `connect()` **and whenever the token expired**
  (so a running player needs a fresh token every hour - the spike's "a running player doesn't need it" was a one-night
  observation); events `ready`, `not_ready`, `player_state_changed`, `autoplay_failed`; errors
  `initialization_error` (no EME), `authentication_error`, `account_error` (no Premium), `playback_error`; methods
  `pause`, `resume`, `togglePlay`, `seek`, `previousTrack`, `nextTrack`, `getCurrentState`, `setVolume`,
  `activateElement`, `disconnect`; `enableMediaSession` defaults to false; a state is null when this player isn't the
  active device; a track's `type` can be `track`, `episode` or `ad`; the documented `WebPlaybackTrack` has no
  `linked_from`. The SDK overview: "supported by most web browsers ... in both mobile (Android and iOS) and Desktop",
  "requires a Spotify Premium subscription (mobile only types of premium subscriptions are excluded)" - an Android
  WebView isn't named; the spike is the evidence it works. The scopes the SDK needs (`streaming user-read-email
  user-read-private`) are from the web-app-player how-to
  (developer.spotify.com/documentation/web-playback-sdk/howtos/web-app-player).

- The spike [spike §7b]: a WebView made with the application context and owned by a `mediaPlayback` foreground service,
  an https origin (`https://appassets.androidplatform.net/`), `RESOURCE_PROTECTED_MEDIA_ID` granted, media without a
  user gesture, `RENDERER_PRIORITY_IMPORTANT`, third-party cookies on, the default user agent: `ready`, audio, pause and
  resume, and the page's JavaScript running through forced Doze (no wake lock) on the emulator (WebView 153, Widevine
  L3). Still owed: the Jelly Star under real Doze with and without a wake lock, the desktop-UA variant, the DNS host
  list.

## 2. Server: the developer app, the accounts, the tokens

### 2.1 Bring your own developer app (`music_spotify_app`, `SPOTIFY_CLIENT_ID`)
- **Nothing ships.** The server has no client ID of its own. Until the parent sets one, Spotify is "not configured":
  `/music/spotify` explains how to register an app (below), the add form and every Spotify control are absent, the
  library has no Spotify entries (none can exist), the policy's `music.spotify` is false (no phone can be bound: Clear
  and a new client ID unbind every phone), and nothing else changes. `music.spotify` follows the binding alone (§2.5):
  in 2b L1's one case - an environment client ID gone between restarts with bindings left - the phone keeps the DNS
  exemption and its entries, and the token route answers `off` (step 3, qa-26c-step3-code L6).
- **The setting**: one row `music_spotify_app (client_id, redirect_uri, saved_at)` written from the PWA, or the
  environment's `SPOTIFY_CLIENT_ID` (and optionally `SPOTIFY_REDIRECT_URI`), which then wins and shows as "set by the
  server's configuration" with the field read-only. A client ID is 32 characters `[0-9A-Za-z]` (else a 400 by the
  field). **It is stored in the clear on purpose**: a PKCE client ID is public by design (it is in the address bar of
  every login), and the page shows its last four characters so the parent can match it with the dashboard. The secrets
  are the refresh tokens (§2.2), sealed. No client secret is asked for or stored (§0.4).
- **The redirect URI** the parent must paste into the dashboard is suggested from the request the page was loaded
  with: `https://<host>/music/spotify/callback`, where `<host>` is the `Host` header (or `X-Forwarded-Host` from a
  trusted proxy, `NetConfig`), **only when the request itself was https** - a trusted proxy said `X-Forwarded-Proto:
  https` (the PWA usually sits behind `tailscale serve` or another TLS proxy; the server listens on plain http on
  loopback) [unverified: whether `tailscale serve` sends that header - if not, the parent edits the field]. Otherwise
  (plain http, by name or by address - Docker on the LAN at `http://pi.lan:8080` included, where an https guess would
  lead nowhere) the suggestion is **the loopback fallback**: `http://127.0.0.1:43117/callback` (§2.3). The field is
  editable and validated: either `https://<host>[:port]/music/spotify/callback` or exactly the loopback form. The page
  shows the saved value large and copyable ("Paste exactly this into your app's Redirect URIs"); the authorize request
  always uses the saved one. With an https redirect URI **a login must be started from that address**: Spotify sends
  the browser back to the redirect URI's host, and the `spotify_login` cookie (§2.3) is the starting host's, so a start
  from another address of the server (a LAN name, a second proxy name) is refused by the button with a link to the
  right address (step 1's code QA M1). The loopback redirect (the paste) works from any address.
- **The registration steps the page shows** (not configured, and behind a "How to set it up" toggle afterwards):
  1. Log in at developer.spotify.com/dashboard with a **Premium** account (dev-mode apps need a Premium owner; if that
     Premium lapses, the app stops working until it resumes) and create an app. Name: anything not starting with
     "Spot" (Developer Policy §VI), e.g. "Vibb hjemme". Website: empty.
  2. Redirect URI: the exact value shown. APIs used: **Web API** and **Web Playback SDK**.
  3. User Management: add each child's Spotify account - one per phone, the child's own, never yours (step 8) - by
     name and e-mail. **At most 5 accounts can use a Development-mode app**; Spotify only grants more to
     organisations with 250k monthly users.
  4. Copy the **Client ID** (not the secret: this server doesn't need it) into the field below.
  5. The limits are your app's own: refresh logins every 6 months (this page reminds you), playlists list only for
     their owner or a collaborator, Spotify's own editorial playlists aren't available to developer apps, and Spotify's
     Developer Policy forbids apps "targeted to children" - using this for your child is at your own risk; Spotify may
     switch the app off.
  6. (Step 8.) Playlists: a phone shows a playlist's tracks only when a child's account here owns it or collaborates
     on it - make it in the Spotify app while logged in as the child, or invite the child to collaborate on yours (in
     Spotify, the playlist's ⋯ › Invite collaborators; then open the invite link while logged in as the child - the
     private window you logged the child in from - and join). Then "Check now" on its card lists it. Any other playlist plays in Spotify's order without a track list, and only if the child's
     account can open it (public, or shared with the child). Albums, tracks, artists and podcasts need nothing.
  The page's intro says the model in one line: "your children's Spotify accounts: one account per phone, the child's
  own. Your own Spotify account is never logged in here - you paste the links."
- **Clear** (`POST /music/spotify/app/clear`, a confirm checkbox): deletes the app row, **every account** (its sealed
  token; Developer Policy §I "disconnect ... delete"), unbinds every phone (§2.5), forgets the cached access tokens;
  entries stay in the library but go to no phone (the gate, §3.6), and their cards say "no Spotify app is set up". Event
  `spotify_app_cleared`. Saving another client ID with accounts present also clears the accounts (their tokens belong to
  the old app; the confirm text says so).

### 2.2 The accounts (`music_spotify_accounts`)
One row per authorized Spotify account: `spotify_user` (the `/me` `id`; unique - a second login of the same account
replaces its row's token and bumps `generation`, keeping the row id and every phone bound to it), `display_name`
(`/me`'s, cut to 60, else the id), the sealed refresh token (`ciphertext`, `nonce`, `key_fingerprint`; AAD
`spotify-v1:<fingerprint>:<spotify_user>` through a new `MusicKey::seal_with(aad)`/`open_with(aad)`, Storytel's
`storytel-v1` AAD unchanged), `scopes` (as granted), `authorized_at` (the 6-month clock: Spotify exposes no issue time),
`generation`, `refresh_ok_at`, `refresh_error` (a code, never text: `invalid_grant`, `invalid_client`, `network`,
`http_<n>`, `key`), `refresh_error_at`, `needs_login` (0/1).

- **Scopes** (one set for every account; an account can be both reader and player): `streaming user-read-email
  user-read-private user-modify-playback-state playlist-read-private playlist-read-collaborative`. The SDK needs the
  first three (the web-app-player how-to, §1.1); `user-modify-playback-state` is the play, transfer and queue calls; the
  playlist scopes let an owner's private and collaborative playlists list. No `user-read-playback-state`: the phone
  reads its state from the SDK. `market=from_token` is documented on the token's account (no scope reason of its own).
  **One scope set means the phone's hourly token carries every scope of its account**: a parent account bound as
  "plays as" would give the phone a 1-hour token that can also read the parent's private playlists. Since step 8 the
  parent's account is never logged in (§0.2 item 1), so the case arises only against the setup text; the binding's hint
  says "One account per phone: the child's own Premium account, never yours" (§2.5, §7.1).
- **The account model** (step 8, the user's answer of 2026-10-14): one account per phone, the child's own; the parent's
  never. Nothing in the code enforces it - the table holds any accounts, a phone binds one, readers are any (§3.3) - the
  Spotify page's intro, its setup steps 3 and 6 and its login hint say it (§2.1, §2.3), the device card's binding hint
  repeats it (§2.5), and the two-phones-one-account warning stays. The card's "read as <name>" stays too: it names which
  child's account listed the entry.
- **The 6-month line**: the accounts card shows "Logged in 12 Oct 2026 - log in again before 10 Apr 2027"
  (`authorized_at` + 180 days, a margin under Spotify's 6 months); from 14 days before, the line is a warning on the
  Spotify page and on the music card of every phone bound to the account ("Ella's Spotify login ends on 10 Apr: log in
  again on the Spotify page"). A new login of the same account restarts the clock. After it the refresh fails with
  `invalid_grant` -> `needs_login` (§2.4).
- **Remove** (`POST /music/spotify/accounts/{id}/remove`, confirm): unbinds its phones (each `UPDATE` moves the
  library revision, §3.7), deletes the row (Developer Policy §I: the token and the account's name go), forgets its
  cached access token, event `spotify_account_removed` (the display name only). Listings it read stay (catalog
  metadata, not the user's data) and are re-read by another account at the next check, or go to context mode.
- At most 5 rows are expected (dev mode); the server doesn't enforce Spotify's limit (grandfathered apps may have more)
  but the page says it.

### 2.3 The login (PKCE in the parent's browser)
- **Start** (`POST /music/spotify/login`, a signed-in session, CSRF-guarded like every POST): a fresh PKCE pair - the
  verifier 64 characters from `[A-Za-z0-9-._~]` (`OsRng`), the challenge `base64url(sha256(verifier))` without padding,
  `state` 32 hex - kept **in memory only** in `spotify::Accounts.pending` with the admin user who started it and the
  redirect URI used (at most 8, each 10 minutes; a ninth drops the oldest; a server restart loses an unfinished login,
  which the parent simply starts again); then a 303 to
  `https://accounts.spotify.com/authorize?response_type=code&client_id=<id>&scope=<scopes>&redirect_uri=<saved>&code_challenge_method=S256&code_challenge=<c>&state=<s>&show_dialog=true`
  (`form_urlencoded` in that order; `show_dialog=true` so the parent always sees which account is logging in).
- **Which account logs in**: whoever is logged into Spotify in that browser. The page says (step 8's wording): "Log in
  each child's own account - one per phone, never yours. Whoever is logged into Spotify in this browser logs in, so
  open this page in a private window (or log out of Spotify in this browser first) and log in as the child. An account
  plays on one device at a time: give each child their own."
- **The callback** (`GET /music/spotify/callback?code&state` or `?error&state`): **reachable without a session** - the
  admin session cookie is `SameSite=Strict`, so the browser doesn't send it on the top-level navigation that comes from
  `accounts.spotify.com`; the route stays behind the admin gate (tailnet/loopback clients only, `gate.rs`, and
  `ADMIN_TAILSCALE_USERS` when set) and is registered among `build_admin_router`'s `public_routes` beside `/login`
  (outside `require_full_auth`). It does nothing without a live pending `state` (taken out of the map
  on first use: a replay finds nothing). With `error` (the parent denied): the page "Spotify login cancelled". With a
  code: the token exchange (§2.4, `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`,
  `code_verifier`), then `GET /v1/me` with the new access token:
  - 200: upsert the row (new: `generation 1`; same `spotify_user`: token replaced, `generation + 1`, `needs_login 0`,
    `refresh_error` cleared, `authorized_at` now), seal, event `spotify_account_added` (attributed to the admin who
    started the login).
  - **The answer is a 303**, never a page on the code's URL (so the spent code stays out of the history and out of any
    `Referer`): to `/music/spotify?login=<outcome>#accounts` (`ok`, `cancelled`, `not_registered`, `refused`,
    `unreachable`, `expired` - an unknown or used `state`); the session-bearing page then shows the one-line outcome
    (for `ok` "Logged in as <display name>", the name read back from the newest row).
  - **The login cookie** (defence in depth): the start also sets `spotify_login` = hex SHA-256 of the `state`
    (`HttpOnly`, `Secure` unless `INSECURE_COOKIES`, `SameSite=Lax`, `Path=/music/spotify/callback`, 10 minutes); the
    callback requires it to match and clears it, so an authorize URL that leaked can't be completed from another
    browser. (`Lax` is sent on the top-level GET navigation from Spotify; the admin cookie, `Strict`, is not.)
  - 403 from `/me` (the account isn't in the app's User Management: "API requests with an access token associated to
    that user and app will receive a 403", Quota modes, verified 2026-10-13): nothing stored; the page "Spotify refused
    this account for your app: add it under User Management in the dashboard (name and e-mail), then log in again".
    Event `spotify_login_failed` (`not_registered`).
  - Any other failure (the exchange refused - `invalid_grant` for a stale code, `invalid_client` for a wrong client ID
    -, network): nothing stored, a one-line reason, event `spotify_login_failed` (the code only).
  The callback's query never reaches a log: the audit and the gate log paths only (`audit::clip(path)`), and a test pins
  that the code string appears in no log line, event or page.
- **The loopback fallback** (a server without https): the redirect is `http://127.0.0.1:43117/callback`. Nothing
  listens there, so after the consent the parent's browser shows "can't connect" with the code in its address bar. The
  Spotify page shows, under the login button, "Paste the address your browser shows after logging in" and a field
  (`POST /music/spotify/paste`, CSRF-guarded, a session): the pasted URL must parse, be `http://127.0.0.1:43117/callback`
  with `code` and `state` (or `error`), and then runs exactly the callback's code path. This works from any device the
  parent uses, e.g. a private tab on the parent's phone.
- **Not designed**: a login on the kid's phone (the spike's in-app WebView PKCE). It would put a password field and a
  web view on a kiosk phone (design 20: the kid's screens open no web views), and Google/Facebook/Apple sign-in refuse
  WebViews. The PWA flow covers every case: the parent's browser, any device, https or the paste.

### 2.4 Tokens on the server (`S/spotify.rs`, `spotify::Accounts`)
- `Accounts.access: Mutex<HashMap<account id, Arc<tokio::sync::Mutex<Option<Access>>>>>`; `Access { token, expires:
  tokio::time::Instant, generation }` (no `Debug`, no `Display`: the compile-time probe like
  `storytel::tests::secret_types_have_no_debug`). Its clock is `tokio::time::Instant` plus a test offset
  (`Accounts::advance`, Storytel's reason).
- `access_token(state, account, min_left)`: under the account's lock, the kept token when it has at least `min_left`
  left and the row's generation is the token's; else **refresh**: read the row (`needs_login = 1` -> `Reauthorize`
  without a request; a key that can't open it -> `Key`), POST `https://accounts.spotify.com/api/token` (form
  `grant_type=refresh_token&refresh_token=<rt>&client_id=<id>`, `Content-Type: application/x-www-form-urlencoded`,
  `Kind::Token` 64 KB / 20 s, `Reach::Public` - **`Reach::Any` only while the loopback base override of §9.3 is set**
  (the fake on 127.0.0.1; `music_net` refuses a private address for `Public`, `music_net.rs:71-76`, 311-325) - through
  the gate):
  - 200 with `access_token` and `expires_in`: kept with `now + expires_in - 60 s`. **When the answer carries a new
    `refresh_token`, it is sealed and written before the access token is used** (PKCE refresh tokens rotate in
    practice [unverified]); a failed write keeps the new refresh token in `Access.unsaved` and the next refresh uses and
    retries it (log `spotify refresh token not saved` with the account id only). `refresh_ok_at` stamped, `refresh_error`
    cleared.
  - 400 `{"error": "invalid_grant"}`: `needs_login = 1`, `refresh_error = invalid_grant`, event `spotify_login_needed`
    (the display name), `Reauthorize`. Never tried again until a new login (new generation).
  - 400/401 `invalid_client`: the app's client ID is wrong or the app was deleted or revoked: `refresh_error =
    invalid_client` on the account, `AppRefused` - the Spotify page's app card says "Spotify doesn't accept this client
    ID any more (the app was deleted, or Spotify switched it off)"; not `needs_login` (a new client ID re-logs anyway).
  - 429: `Busy(retry_after)` (no state change); 5xx, a transport error or a timeout: `Unreachable`
    (`refresh_error = network | http_<n>`), no state change beyond it.
- **Reads** `api_get(state, account, url, kind)` (the same `Reach` rule as the token request): `Authorization: Bearer`,
  `Accept: application/json`; a 401 refreshes
  once (`min_left` forced) and retries once, never twice; a 429 waits `Retry-After` when it is at most 5 s (once), else
  returns `RateLimited(retry_after)` or, with `"reason": "QUOTA_EXCEEDED"` in the body, `Quota` (then the sweep makes
  no Spotify request for 60 minutes, `Accounts.quota_until`, so the phones' play calls - the same developer account's
  bucket, §1.1 - have room; the token endpoint is the accounts service, not the Web API, and goes on); other statuses as

  `Status(code)`; truncated as `TooBig`. Every URL is built with `reqwest::Url` and `form_urlencoded` from the
  canonical id (never from pasted text).
- **Errors** carry codes: `spotify_off` (no app), `no_account` (no account can be used), `reauthorize`, `app_refused`,
  `not_found`, `rate_limited`, `quota`, `network`, `timeout`, `http_<n>`, `too_big`, `bad_answer`, `key`. No error
  text holds a URL (`music_net::network_error`'s `without_url`), a token or a code.
- **Logs**: reqwest stays floored at `info` (26b, `main::log_filter`); `SourceResponse`'s `Debug` already leaves the body
  out (a token answer's body is a secret); the source clients' `.referer(false)` covers the redirect of a short link.
  Spotify logs carry account ids, entry ids, statuses and codes - never a token, a code, a verifier or a display name
  in a warning (names only in events, as Storytel's).

### 2.5 Which account a phone plays as (`device_policy.music_spotify_account`)
- NULL = Spotify is off on this phone (the default; design 20's opt-in). Set on the device page's music card: "Spotify
  plays as: [Off | Ella | Ola ...]" (auto-saving select, `POST /devices/{id}/music/spotify`, event
  `music_spotify_binding_changed`), only accounts that exist; refused for an unknown id (a 303 back to `#music-spotify` with the notice
  `music_notice=spotify_unknown`, step 2b's deviation 3).
  Its footer (step 8, design 31's one sentence since the second merge's code QA): "One account per phone: the
  child's own Premium account, never yours."
- Beside it: the account's state ("logged in, log in again before ..." / "needs a new login" / "Spotify says this
  account has no Premium" from the phone's `music_state.spotify` = `not_premium`), and **one account bound to two
  phones** gives a warning on both cards: "Spotify plays on one device at a time per account: Ella's and Ola's phones
  both play as Ella - give each child their own account".
- The phone's library, listings and covers include Spotify entries only while it is bound (§3.6). The policy's
  `music.spotify` (§6.2) is true exactly then: `models::DevicePolicy` gains `music_spotify_account: Option<i64>` and
  `music::policy_music` (`music.rs:1062-1087`, computed per request) reads it (bound = the column is set; the FK makes
  the account exist). The binding also enters the phone's `dns_filter_version` (§6.2).

### 2.6 The phone's token (`POST /api/devices/music/spotify/token`)
Device bearer (`require_device_token`), no body, `Cache-Control: no-store` (the edge's default for a device route), a
per-phone limit of 20 a rolling hour (a 429 `busy` past it: the phone asks about once an hour while it plays, plus a
retry or two). Answers (always JSON, never a token outside `ok`):

| Status | Body | When |
|---|---|---|
| 200 | `{"status": "ok", "access_token": "<token>", "expires_in_s": 3300, "account": "3:2"}` | the bound account's token with at least 10 minutes left (refreshed if needed); `account` = `"<id>:<generation>"` |
| 404 | `{"status": "off"}` | no app, or the phone isn't bound |
| 409 | `{"status": "reauthorize"}` | `needs_login`, or `app_refused` (the parent must act either way) |
| 429 | `{"status": "busy", "retry_after_s": 30}` | Spotify's 429 or the per-phone limit |
| 502 | `{"status": "unreachable"}` | Spotify's accounts service failed or timed out |
| 503 | `{"status": "key"}` | the music key can't open the sealed token (another key) |

`expires_in_s` is what is left of the token. The token is never written to the database, a log or `music_state`. A
phone whose account changes gets the new account's token on its next ask; `account` tells it to drop the WebView's
storage (§5.2).

**The route's budget** (step 3, qa-26c-step3-code L3): a refresh goes through `AppState.music_fetch`, the server-wide
gate (one request in flight, FIFO), so during a sweep it can wait behind a feed fetch (up to 120 s), listings and art.
The route waits at most `PHONE_TOKEN_BUDGET` = **8 s** for it, then answers 502 `unreachable`; the refresh is not
cancelled (2b's spawned `MintJob`) - it finishes and is kept, so the next ask gets it at once. 8 s is under the
launcher's 10 s and the music app's 10 s, which start earlier, so the server's honest answer arrives before either
gives up. The phone's side of this case is §5.5's one prompt retry. (A second gate lane for the accounts service would
change §0.3's "one gate"; not needed with the retry.) Order of the checks: binding and app first (`off`, not counted),
then the limit, then the mint; a database error is 500 `unreachable`; Spotify's 429 without a number waits 30 s.

## 3. Server: links, entries, listings, the PWA

### 3.1 Share links (`spotify::parse_link`, `spotify::resolve_short_link`)
Accepted forms (vibb `to_uri`): `spotify:<kind>:<id>`; `https://open.spotify.com/[intl-<xx>/]<kind>/<id>[?si=...][#...]`
(any query and fragment dropped); `https://spotify.link/<code>` and `https://spotify.app.link/<code>`: resolved like
vibb's `urlopen(target).url` (`spotify.py:42-46`, the whole chain) but one hop at a time - a GET through the gate,
`Reach::Public`, with **`follow: false`** (a new field on `music_net::SourceRequest`; the response's `Location` is
returned in a new `SourceResponse.location`, both added in step 2a), at most 3 hops, each `Location` an https URL on
`spotify.link`, `spotify.app.link` or `open.spotify.com` only (anything else stops: "That Spotify link didn't lead
anywhere"), and the first `open.spotify.com` URL parsed. `<id>` is `[A-Za-z0-9]{1,64}`; anything else in the path is
refused. The
canonical target is `spotify:<kind>:<id>`; the entry's `key` is `state_key(target)` (`sha1[:12]` - vibb's `bm_path`
hash of the same URI, so a Pi bookmark file and our entry key match).

| Link | Mode | Listing (Web API, `market=from_token`) | Re-check | The tile |
|---|---|---|---|---|
| playlist | list (an account can read its items); else context | `GET /playlists/{id}?fields=name,snapshot_id,images,owner(id,display_name)` then `GET /playlists/{id}/items?limit=50&offset=N&additional_types=track,episode` pages, each `items[].item` (episodes asked for as such, so one comes as the documented `EpisodeObject` and is left out by its `type`; qa-26c-step2a-code L1) | the interval, items only when `snapshot_id` changed or a day passed (vibb `_precache_due`) | Musikk · spor N av M |
| album | list | `GET /albums/{id}` (name, artists, images, the first 50 tracks) + `GET /albums/{id}/tracks?limit=50&offset=50..` | daily (immutable: vibb's daily re-ask) | Musikk · spor N av M |
| track | list (one item) | `GET /tracks/{id}` | daily | Musikk |
| artist | context (default); **option**: list as its albums' tracks | context: `GET /artists/{id}` (name, images) - top tracks are removed in dev mode [verified 2026-10-13]. Option: `GET /artists/{id}/albums?include_groups=album,single&limit=10` (not on the removed list; the reference's `limit` is at most 10, default 5 [verified 2026-10-13, qa-26c-step2a-code M2]; dev-mode paging [unverified]) then each album as the album row, newest album first, at most 20 albums and 500 tracks | daily | context: Musikk · rekkefølgen velges for deg; option: Musikk · spor N av M |
| show | list | `GET /shows/{id}` + `GET /shows/{id}/episodes?limit=50` newest first, the newest 100 kept | the interval | Podkast · episode N av M |
| episode | list (one item) | `GET /episodes/{id}` | daily | Podkast |
| audiobook, chapter | refused: "Spotify audiobooks can't be played here." | | | |
| `spotify:user:<id>:collection` (Liked Songs), user profiles | refused: "Liked Songs and Spotify profiles can't be added - make a playlist instead." | | | |

(The tile column is step 8's: the kind is what the entry holds, never the source, §4.3.)

- **Episodes in the `uris` array** are not documented (the reference says "track URIs") [unverified]: shows and
  episodes are built, and step 5b's device check plays one; if the SDK refuses them, the parser refuses `show` and
  `episode` with "Spotify podcasts can't be played here - add the podcast's RSS feed instead" (one constant,
  `SHOWS_PLAYABLE`), and nothing else changes. **As built there is no such constant** (qa-26c-step7-docs L2): shows
  and episodes are listed and played unconditionally; if the device check fails, that refusal is a small code change.
- The generic add-by-link form on `/music` answers a Spotify link with "Spotify links are added on the Spotify page"
  and a link there (`parse_link` returns that error for every Spotify form; `Link::Spotify` is only made by
  `spotify::parse_link`).

### 3.2 Entries (`POST /music/spotify/add`)
Fields: `link`, `category`, `device` (repeated; read as `Form<Vec<(String, String)>>` like Storytel's Add, more than
200 fields a 400). Without an app: a 400 "Set up your Spotify app first". Without a usable account (none, or all
`needs_login`): "Log in with a Spotify account first". Then:
1. Parse (and resolve a short link). A target already in the library: "Already in the library" with a link to its card.
2. **The add check** (like NRK's, `PROBE_BUDGET` 45 s in all, the gate's wait included): the metadata read for the kind
   with the first usable account (§3.3's reader order), so the parent learns at once that it exists: 404 for every
   account -> refused "Spotify doesn't let this app open it - Spotify's own playlists (made by Spotify) aren't available
   to developer apps, or the link is private or deleted"; unreachable -> refused "Spotify couldn't be reached - try
   again". For a playlist, also the first items page with each account in turn until one isn't 403: none -> the entry is
   added in **context mode** with the notice "No logged-in account owns this playlist or collaborates on it, so the
   phones can't show its tracks: they play it in Spotify's order. For the track list, make the playlist in your
   child's Spotify account, or invite your child to collaborate on yours and join from your child's account." (step 8's
   wording and its fix round's, L3; it said "Log in as its owner" before, which would log the parent in)
3. Insert (within `MAX_ENTRIES`, the INSERT's own `WHERE`): source `spotify`, the canonical target, the key, name = the
   metadata's - a playlist without one (Spotify's name is optional only there) is named by its kind's word,
   `LinkKind::plain_name` "Playlist", never the PWA's "Spotify playlist" (the name is the kid's tile and lock screen;
   qa-26c-step8-code L2) - (a playlist's or show's name; an album "<album> — <first artist>"; a track "<name> — <artists>"; an
   artist's name) cut to `MAX_NAME_CHARS`, the category from a new kind-aware `music::default_kind_for(source,
   target)` - `podcast` for a Spotify show or episode, `music` for every other Spotify kind, `default_kind(source)` for
   every other source (today's `default_kind` is per source only, `music.rs:319-325`) - through `default_category` (the
   first category of that kind, else the first), `order` `auto`, **`cache` 0** (nothing is ever downloaded; the entry page
   and the card's offline select hide for Spotify, and a stored or posted value is saved as 0), `resume` on (vibb's
   per-context bookmark), sort next. The listing's cover candidate = the metadata's image (picked as in §3.4).
4. **Ticks**: each `device` that is bound to an account is ticked in the same transaction (`music::tick_fits`); one that
   isn't is skipped with "Ola: not ticked - Spotify is off on this phone". This is the user's "velger at den skal over på
   tlf"; every other entry kind keeps its ticks on the device card (design 20 decision 6), and Spotify entries can be
   ticked there too.
5. Event `music_entry_added` ("Spotify: <name>"), the sweep woken (its first check lists it within a minute), back to
   `/music/spotify?added=<id>#added` - "Added - it shows on the phones within a few minutes" - never jumping to the top.

The import (`music_import.rs`, design 21a): `Skip::Spotify` is lifted - a vibb `library.json` entry whose target
`spotify::parse_link` accepts **without a request** (a URI or an `open.spotify.com` link) imports as a Spotify entry
(name, order, resume; cache 0; untick); a `spotify.link` short link stays a skip ("resolve it by adding it on the
Spotify page"), audiobook/collection targets skip with §3.1's sentence, `spotify_user` sections stay
`Skip::SpotifyProfile`. Nothing is fetched at import; the sweep lists it (or reports `no_account`).

### 3.3 Readers, list mode and context mode
- **Readers**: the accounts without `needs_login`, ordered **the listing's last reader first** (`music_listings.spotify_reader`),
  then by id. Album, track, artist, show and episode reads take the first that answers; a playlist's items try each in
  turn on 403 and remember the one that worked. So each child's account lists the playlists it owns or collaborates
  on, for every phone that has the entry, without the parent saying which (the parent's own account is never logged in,
  step 8: a playlist only the parent owns is a context until a child's account has joined it as a collaborator).
- **List mode** (`spotify_mode = 'list'`, the primary): the listing is ours; the phone plays its items by track URI
  (§5.4). Every order setting works; resume, done marks, NY and design 27's art work as for a podcast.
- **Context mode** (`spotify_mode = 'context'`, the fallback): an artist always; a playlist when every reader gets 403
  on its items. The listing has **no items** (its version still moves with the mode, the name and the cover); the phone
  plays `context_uri` (with `offset.uri` = the bookmarked track for a playlist; an artist has no offset) and shows what
  the SDK's `track_window` says while it plays (§5.9). The order setting doesn't apply (the entry page says "Spotify
  picks the order" and hides the select); resume is the context bookmark, as on the Pi. A playlist in context mode is
  read again at every check and flips to list mode as soon as an account may list it (the card's notice tells the
  parent how).
- **Option: an artist as its albums' tracks** (QA L12; assumption 7; the server setting `SPOTIFY_ARTIST_ALBUMS`, read
  once at start, off by default - an environment variable, so QA checklist item 18 can switch it without a build): only
  `/artists/{id}/top-tracks` left dev mode; `GET /artists/{id}/albums` is not on the removed list. With the option on,
  an artist is **list mode**: its albums and singles newest first (one request per page of 10 albums - the endpoint's
  maximum, qa-26c-step2a-code M2 -, then one per album, the batch endpoints being gone), at most 20 albums and 500
  tracks (`keep_end = 'first'`, `capped`), each
  album's tracks in order, titles and art as an album's (the album image per track, judged `own`), a daily check -
  resume, the order setting, NY and the track list then work as for an album. vibb listed artist contexts too
  (`context_tracks`). It stays off until QA checklist item 18 shows the albums endpoint paging for a dev-mode app; with
  it on, a check that gets 403/404 there falls back to context mode for that artist (the card says "Spotify refused the
  artist's albums - played as Spotify picks"). Switching it changes an artist entry's mode at its next check.

- Why not list a foreign playlist from the SDK into the server: the server never plays, the phone never lists (21b);
  and the `track_window` shows only a few tracks around the current one.

### 3.4 The Spotify check (`music_sweep::spotify_check`, `S/spotify.rs` pure parsers)
- `schedule`'s `WHERE e.source IN (...)` and `check_entry`'s source check take `'spotify'`; `music::entry_link` returns
  `Link::Spotify { kind, id }` for a `spotify` entry. **The cadence needs the kind**: `interval(source, ticked,
  setting_hours)` (`music_sweep.rs:415`) has none, so it becomes `interval(link: &Link, ticked, setting_hours)` - NRK
  as today, a Spotify album, track, episode or artist 24 h, everything else (RSS, Storytel, a Spotify playlist or show)
  the setting `I`; `schedule` builds the link from each row's `(source, target)` with `music::entry_link` (a row whose
  link doesn't parse is skipped and logged, as today). Check now as for the others (and it ignores the snapshot gate).
  **`restamp_versions`** (`music_sweep.rs:960-966`, `IN ('nrk', 'rss', 'storytel')`) takes `'spotify'`, so a restore
  restamps a Spotify listing like the others.
- **Without an app** the check fails `spotify_off`, without a usable account `no_account`, with all readers
  `needs_login` `reauthorize` - no request; the last good list stays (21b `finish`).
- **Playlist**: the metadata read (one request). The listing keeps `spotify_snapshot`; when it equals the answer's
  `snapshot_id` and the items were read less than 24 h ago (`spotify_items_at`), the check ends "unchanged" (no item
  request). Else the items, page by page (`next`), at most 1000 tracks (`keep_end = 'first'`, `capped` past it): one
  item per `items[].item` whose `type` is `track`, that has an `id` (`is_local` files have none), and whose
  `is_playable` isn't false. Everything left out is counted in `spotify_skipped` ("3 left out: not playable here, local
  files or podcast episodes" on the card). A track twice in a playlist is listed once (21b's dedupe rule: the first
  place; `music_items`' primary key).
- **Album**: the album's tracks in disc/track order (all pages); no per-track art (`art_url` NULL: the album cover is the
  entry cover, so design 27 shows it); `is_playable` false left out and counted.
- **Track / episode**: one item. **Show**: newest first from the API, the newest 100 kept (`keep_end = 'newest'`, NRK
  podcast's rule), each episode's own image as `art_url` (design 27 judges it against the show picture as for RSS).
- **Artist**: context mode; the metadata only (name, image) - a check is one request a day.
- **The item** (`music_sweep::Item`): `key` = the Spotify id (`valid_key`: 22 base62 characters fit), `title` = `"<name>
  — <artist>, <artist>"` (vibb's row title; an episode its name) through `cut_title(.., MAX_TITLE_CHARS)`, `state 'ok'`,
  **`url NULL`** (the phone plays by `spotify:<type>:<key>`; 0072's note that `ok` + `url NULL` is a listed, playable
  item holds), `hls 0`, `duration_ms` (0 = NULL), `art_url` = the album image (playlist and track entries; §3.5).
- **The listing row**: `title` = the name, `cover_candidate` = the image (the one of at least 300 px with the smallest
  width, else the largest; `media_url_ok`; fetched by 21b §2.6's cover machinery, `Reach::Public`), `spotify_mode`,
  `spotify_snapshot`, `spotify_items_at` (the last full item read, `Sweep::now()`), `spotify_reader`, `spotify_skipped`,
  `keep_end`, `capped`.
- **Requests** count against the check's budget (250 / 10 min) like every source request; a 1000-track playlist is 21
  requests. `rate_limited` and `quota` fail the check with their code and back off (21b's backoff); a `quota` stops
  every Spotify check for 60 minutes (`Accounts.quota_until`, §2.4: no request; they fail `quota` too, "Spotify's limit
  for this app was reached - it tries again later"), so the playing phones, which draw on the same developer account's
  bucket, keep room.

- **Errors** (`sweep_error_text("spotify", code)`): `spotify_off` "no Spotify app is set up on this server",
  `no_account` "no Spotify account is logged in", `reauthorize` "the Spotify logins need renewing", `app_refused`
  "Spotify doesn't accept this server's client ID any more", `not_found` "Spotify doesn't let this app open it (or it
  was deleted)", `rate_limited`, `quota`, `network`, `timeout`, `http_<n>`, `too_big`, `bad_answer`, `key` "a saved
  Spotify login was locked with another key - log in again", `no_items` "nothing in it can be played here" (the last
  two added after step 2a, qa-26c-step2a-code).
- `flag_reports` skips Spotify (a track the phone can't play is the phone's `unplayable`, §5.8; nothing to re-resolve).

### 3.5 Art (design 27)
- `art_candidate(item, link)` takes an `ok` Spotify item with no `url` and an `art_url` (as Storytel's books). Judging:
  a playlist's or a track entry's track art is **`own`** (like a Storytel book: no reference prints, no `same`, no twin
  merge - a playlist's mosaic cover is no reference for its tracks); a show's episode art is judged as an RSS episode's
  (it often equals the show picture: `same`); an album lists none.
- **One fetch per URL per pass**: a playlist repeats album images; the art pass keeps a per-pass map `art_url -> hash`
  so a second item naming the same URL takes the stored picture's hash without a request (if the pass doesn't have this
  yet, it is added here for every source). Budgets (`ART_PER_CHECK`, `ART_PER_DAY`) unchanged: a big playlist fills over
  a few days; until then a track shows the entry cover (design 27's `artHash ?: entry.cover`).
- `art_in_library` and `cover_in_library` serve Spotify entries behind `spotify_gate!()` (§3.6).
- Spotify's Policy wants cover art and metadata shown while it plays [verified 2026-10-13]: the player and the session
  always show a picture (the track's, else the entry's) and the title.

### 3.6 What a phone gets (the gate)
- `spotify_gate!()` (an exported macro beside `storytel_gate!()`, `concat!`ed into the SQL): `AND (e.source != 'spotify'
  OR EXISTS (SELECT 1 FROM device_policy p JOIN music_spotify_accounts a ON a.id = p.music_spotify_account WHERE
  p.device_id = ?))` in `LIBRARY_ENTRIES` (whose `IN` takes `'spotify'`), `device_listing`, `cover_in_library` and
  `art_in_library`. A phone not bound gets no Spotify entry, listing or cover (404).
- `needs_login` does **not** drop the entries: the tiles stay and say "Spør en voksen" (§5.8); positions are
  the kid's and nothing is downloaded, so there is nothing to prune.
- `LibEntry` for Spotify: `source "spotify"`, `target`, `cache 0`, `items` = the listing version, `cover` = the parent's
  or the listing's, `lan false`, and **`spotify`: `"list"` or `"context"`** (from `music_listings.spotify_mode`; absent
  on every other source, `#[serde(skip_serializing_if = "Option::is_none")]`; null before the first check -> the field
  absent and `items` null: the phone hides the entry until it is listed).
- The library card (`partials/music_card.html`): `kind_label` "Spotify playlist" / "album" / "track" / "artist" /
  "podcast" / "episode"; "42 tracks · read as Ella" (the reader's display name - which child's account listed it), "· 3
  left out", context mode's notice; per-phone lines: "Ella: plays as Ella", "Ola: not sent - Spotify is off on this
  phone" (not counted in "on N phones": `SweepView::gated` and `phone_count`, `handlers/music.rs:2786-2806`,
  Storytel-only today, gain the Spotify rule - a `spotify` entry is gated on a phone with no account bound (its line:
  "not sent - Spotify is off on this phone", or "not sent - no Spotify app is set up" while there is no app); without an
  app the bound phones still get it, as `spotify_gate!()` says, and their lines say it can't play - qa-26c-step2b-code
  L1);

  item errors from `music_state` (`unplayable` "Spotify couldn't play this track on the phone"). A link back to
  Spotify on the card ("Open in Spotify": `https://open.spotify.com/<kind>/<id>`, `rel="noopener noreferrer"`) - the
  Policy's link-back (§7.3). No offline select, no "on the phone" counts.
- The device card (`#music-spotify`): §2.5's select and lines, and the phone's `music_state.spotify` in words: `ok`
  "Spotify plays", `reauthorize` "the Spotify login needs renewing on the Spotify page", `not_premium` "Spotify says
  this account has no Premium - the phone can't play Spotify", `no_widevine` "this phone's web player can't play
  Spotify (no Widevine)", `unreachable` "the phone couldn't reach Spotify the last time it tried". The **streaming**
  mobile-data switch's help text (design 30's `stream_mobile_data`; the one switch before the merge of main 571f86f)
  gains "This includes Spotify, which streams (about 60-150 MB an hour [device check]) and is never saved on the
  phone".

### 3.7 Migration `0074_music_spotify.sql`
```sql
-- Spotify (design 26c §2-§3). The family's own developer app (bring your own: the server ships no
-- client ID; a PKCE client ID is public, so it is stored in the clear), the Spotify accounts the
-- parent logged in (each refresh token sealed with the music key, AAD spotify-v1:<fp>:<user>), the
-- account a phone plays as (NULL = Spotify off), and a listing's Spotify state. music_entries.source
-- already allows 'spotify' (0049); a Spotify item is state 'ok' with url NULL like a Storytel book (0072).
-- Written as 0073 on the branch `music-3-spotify` (main at 0072); renumbered 0074 at the merge of main
-- (571f86f) on 2026-10-13: main's 0073 is design 30's music_data_and_storage. If 0073 reaches a server
-- after this one ran, sqlx applies it out of order and `startup::pre_migration_backup` (MAX(version)
-- only) takes no backup first: design 30's server ships before or together with this one.
CREATE TABLE music_spotify_app (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    saved_at TEXT NOT NULL
);
CREATE TABLE music_spotify_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    spotify_user TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    ciphertext BLOB NOT NULL,
    nonce BLOB NOT NULL,
    key_fingerprint TEXT NOT NULL,
    scopes TEXT NOT NULL,
    authorized_at TEXT NOT NULL,
    generation INTEGER NOT NULL DEFAULT 1,
    refresh_ok_at TEXT,
    refresh_error TEXT,
    refresh_error_at TEXT,
    needs_login INTEGER NOT NULL DEFAULT 0 CHECK (needs_login IN (0, 1))
);
ALTER TABLE device_policy ADD COLUMN music_spotify_account INTEGER REFERENCES music_spotify_accounts(id);
ALTER TABLE music_listings ADD COLUMN spotify_mode TEXT CHECK (spotify_mode IN ('list', 'context'));
ALTER TABLE music_listings ADD COLUMN spotify_snapshot TEXT;
ALTER TABLE music_listings ADD COLUMN spotify_items_at TEXT;
ALTER TABLE music_listings ADD COLUMN spotify_reader INTEGER;
ALTER TABLE music_listings ADD COLUMN spotify_skipped INTEGER NOT NULL DEFAULT 0;
-- A phone's library holds Spotify entries only while it plays as an account (§3.6), and says each
-- entry's mode: both move the cached libraries.
CREATE TRIGGER device_policy_spotify_revision AFTER UPDATE OF music_spotify_account ON device_policy
WHEN OLD.music_spotify_account IS NOT NEW.music_spotify_account
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER music_listings_spotify_mode_revision AFTER UPDATE OF spotify_mode ON music_listings
WHEN OLD.spotify_mode IS NOT NEW.spotify_mode
BEGIN UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1; END;
```
- `AUTOINCREMENT` so a removed account's id never names a new account (a stale binding can't silently become another
  person's). Removing an account first `UPDATE`s its phones to NULL (the trigger moves the revision), then deletes the
  row; the app's Clear does the same for every row in one transaction.
- The access tokens and the pending logins are never stored. Backups copy the sealed refresh tokens like Storytel's
  login (useless without `data/keys/`, which no backup copies).
- **Applied migrations are never edited** (sqlx checksums, 26b H1).

## 4. The phone: data and rules (pure; `M/model`, `M/logic`, `M/spotify`)

### 4.1 The library
`Source.SPOTIFY` (`"spotify"`): the target must pass `spotifyTarget`
(`^spotify:(playlist|album|track|artist|show|episode):[A-Za-z0-9]{1,64}$`, `M/spotify/SpotifyRef.kt`:
`SpotifyRef(kind, id)`, `uriOf(kind, key)` - a show's and an episode entry's items are `spotify:episode:<key>`,
everything else's `spotify:track:<key>`; corrected after step 2a, qa-26c-step2a-code), `cache` read as 0 whatever is
sent, `lan` false, and the new optional field
**`spotify`**: `"list"` or `"context"` (`Entry.spotifyMode`, null for every other source; a Spotify entry without it, or
with another value, is hidden with its id kept, phase 1's lenient rule). An older music app drops the whole entry
(`else -> return null`) with its id kept, so nothing is pruned (qa-21-step2-code H1).

### 4.2 Visibility, order, keep
- `Snapshot.visible(entry)`: a listed Spotify item is visible **in both views** - the phone's and a room's (it streams;
  `url == null` doesn't hide it, unlike a Storytel book). The screens build their snapshot with `forRenderer(onRoom)`
  (`MusicViewModel`), so hiding Spotify on a room would drop its tiles from the carousel whenever a room is the output.
  **A room's queue drops them instead**: `PlayBodies.speakerDropReason` returns `spotify` for a Spotify item, so a pick
  on a room is 26a's existing "nothing speaker-playable" path (`CANT_PLAY_ON_SONOS`, "Kan ikke spilles på Sonos"); step
  9 lifts it. A context-mode entry has one synthetic item made by the snapshot (§5.9, H3), so it shows as a tile too.
- `naturallyNewestFirst`: a Spotify **show** true (Spotify lists episodes newest first; a podcast), every other kind
  false (a playlist top first, an album in track order). The entry's `order` then applies as for every source
  (`auto`/`newest_first`/`oldest_first`, vibb's `ORDERS`): `newest_first` on a playlist plays it bottom first - the
  last-added song first when the parent appends. Context mode ignores the order.
- **Never fetchable, never kept**: `KeepItem.fetchable` false for Spotify, so the download plan, the keep set, the prune
  and "N av M lastet ned" never see a Spotify item; the list shows no offline marks for Spotify (no "Ikke lastet ned").
  **No shuffle** (decided): `canShuffle` is own files without resume (`logic/Queue.kt:18`) and every Spotify entry is
  resume on; vibb's `shuffle_context` is a §12 deviation, not a question.
- `publicOnly` is moot (the SDK's traffic isn't ours; the play call goes through the guarded client, §5.4).

### 4.3 NY, Nytt, words
- `applyListing`'s news eligibility (26b: "fetchable") also takes a listed Spotify item: a song added to a playlist or a
  new episode of a show joins the tray, NY shows on the tile (vibb AM-97: "the carousel's 'new' dot for Spotify lists").
  The first listing acknowledges everything (phase 1). Nytt rows: the existing `new_track` ("Nytt spor" / "New track",
  `strings.xml:78/80`) for a track, `new_episode` for an episode - no new string.
- **No source on the kid's screens** (step 8, the user's decision of 2026-10-14: "vi ikke trenger noe spor av spotify
  noe sted i appen ... For barna skal jo dette bare være en tile til? Samme hvilke kilde?"). `kindOf` gives a Spotify
  entry the kind of what it holds, as NRK's, RSS's, Storytel's and own files' kinds are: a show or an episode
  `Kind.PODCAST` ("Podkast" / "Podcast", `hint_episode`, "Episode N av M", "Ny episode" - exactly an NRK or RSS
  podcast's), a playlist, album, track or artist the new `Kind.MUSIC` (`kind_music` "Musikk" / "Music", `hint_track`,
  "Spor N av M", "Nytt spor"). There is no `Kind.SPOTIFY` and no `kind_spotify` string any more (no other code path
  needed a kind label: the parent's words are the server's). `hint_continues`/`hint_from_start` as for any resume
  entry; context mode `hint_spotify_picks` "Musikk · rekkefølgen velges for deg" / "Music · the order is picked for
  you" (the string's name keeps "spotify": names are code, the kid reads values). "Spor N av M" counts the visible list
  in play order (the NRK/RSS rule, `countedKeys`/`playerNumber` unchanged for list mode; context mode shows no number).
  (Steps 3-7 had `Kind.SPOTIFY` with `kind_spotify` "Spotify" as the Policy's attribution - "Spotify · spor 3 av 12" -
  and the context hint "Spotify · Spotify velger rekkefølgen"; step 8 took them out, §7.3.)
- The player shows the title (`"Song — Artist"`), the art (`artHash ?: entry.cover`), the number line as for any entry
  and **no source line** (steps 5b-7 showed a small "Spotify" under the title; step 8 removed it). "Liste" shows no
  line where other entries show "N av M lastet ned" (nothing of Spotify is downloaded), except a context's
  `hint_spotify_picks` (§5.9). The session's metadata (notification, Home's card, the lock's tile) was never the
  source's: the entry's name as artist and album, as for every entry.

### 4.4 The report (`M/logic/Report.kt`)
- `spotify` (new key, after `storytel`): the phone's last Spotify outcome - `none` (not bound, or never played),
  `ok`, `reauthorize`, `refused` (the play call's 403: the account or the app, §5.4), `not_premium`, `no_widevine`,
  `unreachable`, `quota` (Spotify's `QUOTA_EXCEEDED` on a play call: the developer account's bucket is empty). Never a
  token, an account id or a device id.
- **Its store** (QA L8): the renderer runs in the service, the report is written by the sync worker
  (`Sync.writeReport`, `sync/Sync.kt:214-234`, which reads Storytel's state from a file). So the renderer persists its
  last outcome in `noBackupFilesDir/spotify_state.json` (`{"outcome": "<word>", "at": <wall ms>}`, tmp + rename, written
  only when the word changes) through `logic/Spotify.kt`'s pure `SpotifyOutcome` and `data/Store.kt`; the report and the
  tiles (§5.8) read the same file. `Off` from the token writes `none`, and so does the service's `forget()` when the
  library has no Spotify entry left (step 5b's fix round, qa-26c-step5b-code L4: a later binding never shows the last
  account's word); a rebind without an unbind shows the old word until the first play (the app learns the account only
  from a token).

- `item_errors` for a Spotify track or episode: `unplayable` (the SDK's `playback_error` on it, or Spotify skipping it:
  region, explicit filter, removed; §5.8). `downloads` never lists a Spotify entry.

## 5. The phone: the player (`M/spotify/`, `M/playback/`)

### 5.1 The pieces
| File | What | Thread / tests |
|---|---|---|
| `spotify/SpotifyRef.kt` | the target grammar, `uriOf` | pure, `SpotifyRulesTest` |
| `spotify/SpotifyApi.kt` + `SpotifyHttp.kt` | the Web API play/transfer calls over a seam (`SpotifyHttp`, like `StorytelHttp`), outcomes as a sealed `PlayOutcome` | pure, IO only; `SpotifyApiTest`, `SpotifyHttpTest` (`TinyHttp`) |
| `spotify/SdkState.kt` | the page's state JSON parsed, length-capped (`SdkState(paused, positionMs, durationMs, uri, linkedUri, nextUris, prevUris, type, name, artists)`, or `Inactive`) | pure, `SdkStateTest` |
| `spotify/SpotifyTracker.kt` | the state machine: commands sent vs states seen -> the current index, measured positions, `ItemChanged(auto)`, `Ended`, `Paused(cause)`, `Elsewhere`, `Unplayable` | pure, `SpotifyTrackerTest` (synthetic sequences + the fake's recorded ones) |
| `spotify/SpotifyHosts.kt` + `WebPolicy.kt` | the host list (= `spotify_hosts.json`), `WebPolicy.decide(url)`: `Asset(path)` / `NotFound` (our origin, no asset) / `Pass` (also `blob:`/`data:`) / `Proxy(url)` (the debug switch's fake) / `Block`; `SpotifyPage` (the page's CSP, its substitution, the bridge's names) | pure, `SpotifyHostsTest`, `WebPolicyTest` |
| `spotify/SpotifyEndpoints.kt` | the API base, the SDK URL, the debug switch (§9.3) | pure |
| `spotify/TokenCache.kt` | the access token in memory, `get(deadlineMs, minLeftMs, atStart)`, the cooldowns, the prompt retry, the refresh ahead of expiry | pure, `TokenCacheTest` |
| `bridge/SpotifyBridgeToken.kt` | the AIDL call `getSpotifyToken` with a 10 s timeout -> `TokenResult` | Android, IO |
| `spotify/SpotifyWebHost.kt` | the one WebView: create, settings, the interceptor, the page bridge, destroy, clear | Android, main |
| `spotify/SpotifyRenderer.kt` | `Renderer` + `SpotifyPlayer` (`SimpleBasePlayer`), focus, noisy, locks, the sleep fade | Android, main |
| `assets/spotify/player.html`, `player.js` | our page and its glue (GPL, ours; the SDK is never copied) | `PlayerJsTest` (the bridge names), the fake's stub |
| `logic/Spotify.kt` | `spotifyPlayability` (H1), the context item (H3), words, the play window, the start budget, the tile line, `SpotifyOutcome` (L8) | pure, `SpotifyRulesTest` |

`RendererKind` gains `SPOTIFY` and a property `onPhone` (`LOCAL`, `SPOTIFY` true; `SONOS` false). Every
`RendererKind.LOCAL`/`SONOS` comparison in `PlaybackService`, `MusicViewModel`, `UiModel` is re-read in step 5b by that
rule (§5.10's table names each site).

### 5.2 The WebView host (`SpotifyWebHost`, owned by `PlaybackService`)
- **Owner**: the service, never an Activity (the spike's lesson: a WebView owned by its screen died with it, spike §7b). One
  per process (the SDK runs one player per page). Created with the application context **at the first Spotify play**
  (never at a restore: "nothing starts by itself" also means no Connect device appears by itself), destroyed when the
  service is destroyed, at every suspension of the app (§5.7), at 5 minutes after the last Spotify renderer detached or
  paused (the device then leaves the account's Connect list: the SDK's `disconnect()` runs first, the view goes when it
  has, at most 1 s later - qa-26c-step5a-code L5), on the renderer process's death, and when the token's `account`
  changes (another account or a new login: the old account's state must not linger). **The page's account is stored**
  (step 5a's fix round, M1): `noBackupFilesDir/spotify_page_account` holds the token's `"<id>:<generation>"` (not a
  secret) of the account the page's cookies and storage on disk belong to; a new page waits for the token, and is made
  only after the stores are cleared when the marker differs or is missing (a rebind or a new login while the app was
  down, an upgrade, a crash before the write); no token of another account is ever handed to a page.
- **Settings**: `javaScriptEnabled` (the SDK), `domStorageEnabled` (the SDK keeps its device state there),
  `mediaPlaybackRequiresUserGesture = false` (the spike played on a Web API command without a gesture), `allowFileAccess
  = false`, `allowContentAccess = false`, `setGeolocationEnabled(false)`, `setSupportMultipleWindows(false)`,
  `cacheMode` default (the SDK's own HTTP caching only), the default user agent (the spike's pass),
  `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)` (the spike: the renderer stays important with no
  screen), `CookieManager.setAcceptThirdPartyCookies(view, true)` (the SDK's iframe is on `sdk.scdn.co`, the page on our
  origin; the spike's proven setting). Manifest meta-data `android.webkit.WebView.EnableSafeBrowsing = false` (the WebView only
  ever loads Spotify's hosts and ours, and Safe Browsing would add Google lookups) and
  `android.webkit.WebView.MetricsOptOut = true`.
- **Never shown**: the WebView is never added to a view hierarchy (`SourceScanTest`: `addView` of it nowhere; the kid's
  screens still open no web views). It plays headless, as the spike's did once its screen was gone.
- **The page**: `assets/spotify/player.html` loaded with
  `loadDataWithBaseURL("https://appassets.androidplatform.net/spotify/", html, ...)` (the spike's secure origin; no
  `androidx.webkit` dependency). The HTML has no inline script: it loads
  `player.js` from the same origin and the SDK from `SpotifyEndpoints.sdkUrl()` (`https://sdk.scdn.co/spotify-player.js`,
  or `https://fake-spotify.invalid/sdk/spotify-player.js` with the debug switch), the template's only substituted value
  - in two slots: the URL in the script tag, its origin in the CSP's `script-src` and `frame-src` (step 4's deviation 2).
  A CSP meta tag: `default-src 'none';

  script-src https://appassets.androidplatform.net https://sdk.scdn.co; frame-src https://sdk.scdn.co; connect-src https:
  wss:; media-src https: blob:; img-src https: data:` (the debug build adds `https://fake-spotify.invalid`, §9.3). It
  binds our frame only; Spotify's iframe brings its own.
- **The interceptor** (`WebViewClient.shouldInterceptRequest`, every frame's requests): `WebPolicy.decide(url)` - our
  origin's `/spotify/player.js` and `/spotify/player.html` from assets (anything else there a 404); `https` to a host on
  the Spotify list (§6.2: exact or under a suffix) passes (`null`: the WebView loads it); everything else gets an empty
  403 and a counter (`blocked hosts` in the debug log, host only, never the URL; a release logs nothing). **A
  WebSocket isn't seen by `shouldInterceptRequest`** (the SDK's `wss://dealer...spotify.com` connection): it is bounded
  only by the DNS filter and, in our frame, the CSP's `wss:` - Spotify's iframe is Spotify's code (§7.2). **Nor is a
  redirect's target** ("only called for the initial resource URL, not any subsequent redirect URLs"): a Spotify host
  answering 3xx to another host is loaded unseen and not counted - bounded by the DNS filter only, like a WebSocket
  (qa-26c-step5a-code L2; §9.4 part B and QA checklist item 15 read the parent's DNS log for such hosts).
  **Service workers** don't pass through `shouldInterceptRequest`: `ServiceWorkerController.getInstance()
  .setServiceWorkerClient(...)` (platform API 24, no `androidx.webkit`) applies the same `WebPolicy` to their requests
  (process-wide; the music app has no other WebView), installed before the first WebView, with the service workers'
  own settings' file and content access off.
  **The debug build only** (§9.3, 26b M2's one switch): with the switch set, the interceptor also answers the fake's
  https name `https://fake-spotify.invalid/` by fetching the same path from the debug base over the LAN client
  (`SpotifyEndpoints.webProxy()`), so the stub SDK, its tone and its long-poll come in under https - mixed content stays
  blocked (`MIXED_CONTENT_NEVER_ALLOW`, the WebView's default) and the release WebView never sees the name.
  `shouldOverrideUrlLoading` returns true for everything (the page never navigates). `onPermissionRequest` grants only

  `RESOURCE_PROTECTED_MEDIA_ID`, only to `https://sdk.scdn.co` or our origin (which one asks is logged in the debug
  build for step 5b's device check), and denies the rest. `onRenderProcessGone` returns true and tells the renderer
  (§5.8).
- **Clearing**: on an `account` change (also against the stored marker, above), when the token answer says `off`, and
  when the service sees the library without Spotify entries: the page destroyed, then `WebStorage.deleteAllData()`,
  `clearCache(true)` (on the page, or on a throwaway view when none is alive - the HTTP cache is the app's), and
  `CookieManager.removeAllCookies`, whose callback the next page waits for (at most 3 s; the music app has no other
  WebView, so the process-wide stores are only Spotify's). `off` and `forget()` delete the marker.

### 5.3 The page bridge (`player.js` <-> `SpotifyWebHost`)
JS -> Kotlin, one `@JavascriptInterface` object `VibbSpotify` (its methods run on a WebView thread and post to main;
each validates and caps its inputs and does nothing privileged):
- `ready(deviceId)` (`[A-Za-z0-9]{1,64}`), `notReady(deviceId)`.
- `needToken(requestId)` (an int): the SDK's `getOAuthToken` - at `connect()` and whenever the token expired.
- `state(json)` (at most 8 KB): every `player_state_changed`, and every 5 s while not paused (`setInterval` in
  `player.js`, started by a playing state, cleared by a paused or null one) - the compact JSON `SdkState` parses:
  `{"paused", "position", "duration", "uri", "linked", "type", "name", "artists", "next": [<=3 uris], "prev": [<=3 uris],
  "context", "shuffle", "repeat"}` (`context` the state's context uri for §5.9, `shuffle` and `repeat` - 0 off, 1 context,
  2 track - for §5.4's settings; step 4 and its QA fix round), or `null` (this player isn't the active device).
- `error(kind, message)`: `kind` in `initialization`, `authentication`, `account`, `playback`, `autoplay`, `script`
  (the SDK script failed to load), `csp` (a CSP violation in our frame - the directive and the blocked origin only;
  qa-26c-step4-code L6); `message` cut to 200 characters, kept only in the debug log.

Kotlin -> JS (`evaluateJavascript`, main thread; every argument `JSONObject.quote`d): `vibb.connect(name)` (creates
`new Spotify.Player({name, getOAuthToken, volume: 1.0})` - `enableMediaSession` stays false, our Media3 session is the
one - and `connect()`), `vibb.token(requestId, tokenOrNull)`, `vibb.pause()`, `vibb.resume()`, `vibb.seek(ms)`,
`vibb.next()`, `vibb.previous()` (context mode only), `vibb.volume(v)` (the sleep fade only), `vibb.state()` (a
`getCurrentState` posted back), `vibb.disconnect()`. The player's name is **"Vibb"** (Policy §VI: not "Spot..."; it is
what the account's other Spotify apps list as a device).

The bridge object is visible to every frame of the page (an `addJavascriptInterface` property), Spotify's iframe
included: that is Spotify's code, which already gets the token through `getOAuthToken`; `needToken` only starts a mint
whose answer goes to the main frame's `vibb.token`. Nothing else is exposed.

### 5.4 Playing (list mode)
- `load(items, startIndex, startMs, playNow)`: keeps the queue (the service's `Active.items`, never filtered), the index
  and the position; plays only with `playNow`. No WebView, no network before a play.
- **A start** (`play()` on a loaded queue, a tile, a pick, a step): at most `SPOTIFY_START_BUDGET_MS` = 25 s in all:
  1. the network gate - the service's (§5.10, `spotifyPlayability`): else no start, paused, the state
     `SPOTIFY_NEEDS_WIFI` / `SPOTIFY_NEEDS_INTERNET` (§5.8) - the entry is all streams, so nothing moves on;
  2. in parallel: the host and its `ready` device id (create + page + SDK, at most 20 s), and a token with at least
     5 minutes left (`TokenCache`, the bridge's at most 10 s);
  3. `SpotifyApi.play(token, deviceId, Uris(window, positionMs))`: `PUT {base}/v1/me/player/play?device_id=<id>`, body
     `{"uris": [...], "position_ms": <ms>}`, the guarded public-only OkHttp client (the LAN client only with the debug
     switch, §9.3), 10 s. **The window** is the queue from the current index, at most `WINDOW_MAX` = 50 uris
     (`playWindow`). Outcomes:
     - 204 -> wait for our first uri in a playing state (at most 10 s);
     - 404 (the device not known yet) -> `PUT /v1/me/player {"device_ids": [<id>], "play": false}`, **wait for its 204
       and then for a state or `ready` from the SDK (at most 3 s)** - the Player API doesn't order calls (§1.1) - and the
       play once more;
     - 401 -> `TokenCache.invalidate()`, one new token, once more. **This helps only when the server already holds a
       newer token**: the token route can't be told which token Spotify refused (the server's `Accounts::mint` takes a
       `rejected` token, but the route doesn't pass one) and answers its kept token while 10 minutes are left. So a
       renewed token equal to the refused one ends the start at once, and so does a second 401: `TokenRefused` -
       Spotify refused the account's token, an adult's matter (a revoked login or app access), shown as
       `SPOTIFY_ASK_ADULT` with the outcome `refused`, not as "Trenger internett" (qa-26c-step4-code L4). A later
       contract change could add an optional `{"rejected": true}` to the token route through a new appended bridge
       method;
     - **403 -> an account or app outcome, never the track's** (QA M5): a user not in User Management, the app owner's
       Premium lapsed, `PREMIUM_REQUIRED`, `DEVICE_NOT_CONTROLLABLE`, `REMOTE_CONTROL_DISALLOW` - a track the account
       can't play starts and is skipped by the SDK, it isn't refused by the call. The body's `reason`
       `PREMIUM_REQUIRED` -> outcome `not_premium`, any other 403 -> `refused`; the renderer pauses with
       `SPOTIFY_ASK_ADULT`; no item error, no move to the next item. A 403 `PREMIUM_REQUIRED` is tried once more after
       about 1 s (inside the start's deadline) before it counts: intermittent ones on Premium accounts are reported
       (qa-26c-step4-code L7);
     - 429 -> with `QUOTA_EXCEEDED` the outcome `quota` (paused, `SPOTIFY_UNAVAILABLE`'s "Ikke tilgjengelig
       nå"); else wait `Retry-After` if at most 5 s, once, else `unreachable`;
     - 5xx/IO -> `unreachable`.
  4. `position_ms` carries phase 1's resume position, so the track starts there - no audible second from 0:00 [device
     check] (Sonos needed its hush; Spotify starts at the position).
  5. **Shuffle and repeat off** (qa-26c-step4-code M1): they are the account's player state, set from any of the kid's
     Spotify apps, and our list plays in our order only with both off. Before a device session's first play, and again
     whenever a state says either is on (the tracker's `ShuffleOrRepeat`; such a state is read for nothing), the start
     first sends `PUT /v1/me/player/shuffle?state=false&device_id=<id>` and `PUT /v1/me/player/repeat?state=off&device_id=<id>`,
     each 204 awaited before the next call (the Player API doesn't order calls), with the play's retries; a failed one
     ends the start like a failed play (nothing plays shuffled). Two Web API requests per device session, from the
     developer account's shared quota (§1.1).
- **The Media3 state** (QA M1; like 26a §3.2's table, `SpotifyPlayer` = the `SimpleBasePlayer`):

| Situation | `playWhenReady` | `playbackState` | `showsPlaying` |
|---|---|---|---|
| loaded, not started (`load(.., playNow = false)`, a restore) | false | READY | false |
| a start of ours runs (host, token, `PUT`, waiting for our first state; up to 25 s) | true | BUFFERING | **true** (the intent: the button shows pause, design 28's `sessionPlay` and `setNowPlaying` say playing, Media3 keeps the foreground) |
| a fresh state of ours, not paused | true | READY | true |
| no state for up to 30 s while we play (a gap in the 5 s posts, a throttled timer, a track change) | true (held) | READY | true |
| no state for more than 30 s, or the start budget ran out | false | READY | false (`unreachable`, §5.8) |
| a paused state of ours, `vibb.pause()` confirmed | false | READY | false |
| `null` state (confirmed, below) or a foreign state | false | READY | false |
| `Ended` (the queue's last item played out) | false | ENDED | false |
| an error that stops the renderer | false | IDLE | false |

  `play()` while a start of ours runs is a no-op (a second tap on the tile during a cold start sends nothing); `pause()`
  during a start cancels it (no `PUT` after it; one already sent is followed by `vibb.pause()`, also one a newer play
  replaced when that newer play was dropped - qa-26c-step5a-code H1). As built (step 5a and its fix round): IDLE is
  only the two errors that destroy the page (no Widevine, the renderer process gone), every other error line READY
  and paused; the renderer gates too (`playAllowed` and the network line before every start and SDK resume); the
  states the page posts while a start's Web API calls run are held - those posted before the play call went out only
  tell the tracker what played before, the rest are read after `played()` (our first state can beat the 204) -; while
  a start runs or a step coalesces the old play's events are passed over but a foreign track's pause; **a play the
  renderer starts by itself** (`NewWindow`, `ShuffleOrRepeat`, the gain after a transient loss) **only goes on with a
  play the kid wants now** - never while paused or under a transient focus loss (H1, M2): paused, `ShuffleOrRepeat`
  only makes the next play turn them off, and an SDK resume is a fresh start then; a transient focus loss during a
  start pauses the play when it goes out, the first-state rule waits, and the gain resumes it (M2). Chromium throttles
  timers of hidden pages, but an audible page is exempt [device check: the 5 s posts keep coming with the screen off];
  the 30 s hold covers a throttled one.
- **Pause / resume / seek** within the current item: the SDK (`vibb.pause()`, `vibb.resume()`, `vibb.seek(ms)`), no
  Web API call. A resume after the device was dropped (a confirmed `null` state, or the host destroyed after 5 idle
  minutes) is a fresh start at the bookmark.
- **Steps** (next, previous, a pick, the carousel's swipe) are the service's (`skipTarget`, `pickPlan`, design 27's
  `SKIP_TO`), which call `renderer.seekTo(index, ms)`: a new play with the window from that index. Steps within 300 ms
  coalesce into one call (a mashed "next" sends one `PUT`, vibb's skip debounce).
- **Natural advance**: Spotify plays on through the window by itself (no call from a sleeping phone); the tracker sees the
  expected next uri and emits `ItemChanged(index + 1, auto = true)`; the service marks the old item done and moves the
  bookmark, and **sends nothing** - the next track starts where Spotify starts it, at 0 (QA M4: the service's
  "resume an item heard before" seek on a natural advance stays ExoPlayer's, §5.10).
- **Past the window** (a queue longer than 50): when the window's last item becomes current, the renderer adds the next
  queue item with `POST /v1/me/player/queue?uri=<uri>&device_id=<id>` (Add Item to Playback Queue), and again each time
  such an added item becomes current - always one item ahead, so the list never runs out under Spotify's control and
  nothing is cut (re-sending a window in a track's last seconds, QA H4's proposal, would restart the playing track: not
  taken). If the add call fails, the item's end is a gap: the renderer starts a new window from the next index (one
  `PUT`). [device check: the queue call on the SDK's device] **Spotify's queue survives a new play**: an add the kid's
  pick of an earlier track left there plays after the picked track, out of our order. The tracker remembers the uris it
  asked to add (until each is seen playing, or a new device) and reads such a leftover as not ours - paused, and our
  list goes on from the next item (`NewWindow`), never as skipped tracks (qa-26c-step4-code H1).
- **Ownership: only our uris are ours** (QA H4). A state's current uri is ours when it is the uri we sent for the
  current index, or **the expected next item** - the next uri of our queue - matched by uri, or by the SDK's `linked`
  when it gives one (undocumented; the Web API removed `linked_from` in February 2026), or **by title and duration**:
  `"<name> — <artists>"` built from the state exactly as the server builds the listing's title (§3.4) equals the item's
  title, and the durations are within 2 s (a relinked copy of the same recording). **Anything else is not ours**: a
  recommended track after the window or the queue (the account's Autoplay), another Spotify app transferring playback
  to "Vibb", a leftover session - it is paused at once (`vibb.pause()`), never saved, never mapped to an index, and the
  player shows paused with nothing new. After `Ended` (the queue's last item played out) every later playing state is
  paused at once. vibb's `play_origin` rule ("only a box-initiated session may write") is this rule. As built (step 4
  and its QA fix round): the relinking matches apply to the sent item too (an episode also by its name alone: the
  server lists an episode by its name); while nothing was sent every playing state is paused; a playing state while
  our pause is pending is passed over; a state naming no track is evidence of nothing; **a start passes over what
  played before it** - the track of the last state seen before the play call is stale in a start, unless it is the
  sent track within 3 s of the sent position (a restart from 0 isn't the old play's tick: qa-26c-step4-code H1, L1); a
  later uri of ours that isn't proven a skip (below) and isn't our leftover add is not ours either - paused, our list
  going on from the current item at its place. Step 5a's fix round (qa-26c-step5a-code M3) widens the start's "stale":
  besides the last state's track, the old play's expected next track (Spotify's own advance while the call was on its
  way - its `prev` names our tracks by history) and our adds still in Spotify's queue are passed over in a start,
  unless it is the sent track near the sent position (`sdk_states/start_old_advance`).
- **The end of a track** is read from the state: a state for the current uri with `paused`, position 0 (under 1 s) and
  the last playing position - carried on by the time since that state, for reading the end only, since the page posts
  every 5 s - within 3 s of the duration (or the duration known and passed), after a playing state and without a pause
  of ours pending = ended; a track not ours playing right then is Autoplay after it (`NewWindow` from the next index, or
  `Ended` after the queue); Spotify's own advance shows as the expected next uri. `SpotifyTrackerTest` pins: the advance, the end of the
  window (the queue add), the end of the queue, autoplay after the window and after the queue (paused, nothing saved),
  a relink matched by title and duration, a foreign transfer.
- **Unplayable** (QA M5): only from the SDK - a `playback_error` while one of our uris is current, or Spotify skipping
  past one of our uris **on evidence** (qa-26c-step4-code H1): the state names a later uri of ours, every item before
  it that counts as skipped is named in the state's `prev` (the current one only when it hadn't played 3 s), and none
  of them - nor the state's track - is one we added to Spotify's queue. `prev` names at most three tracks, so a longer
  skip is no skip (paused, our list goes on); a stale state or our own leftover add can never read as one. Then
  `Error("unplayable")`: the service records it (§5.10) and the queue moves on; **after 3 unplayable items in a row the
  renderer stops** (paused, the row "Kan ikke spilles", no more calls) - a whole list the account can't play costs at
  most three calls.
- **Positions** (`saveNow`): the last **measured** position - the most recent state's `position` for the current item,
  as received (at most 5 s old while playing) - never extrapolated (26a: no guess reaches Room). `positionMs()` (display
  only) extrapolates while playing. `durationMs()`: the state's `duration`, else the listing's.
- **A `null` state** (the account moved playback elsewhere) is confirmed before it counts (QA L11: one `null` can be
  transient around a transfer or a track change): `vibb.state()` 2 s later, or a `not_ready`; still `null` -> `Elsewhere`:
  the bookmark of the last measured position is written, the renderer shows paused with `SPOTIFY_ELSEWHERE` ("Spotify
  spiller et annet sted" / "Spotify is playing somewhere else"), and a play takes it back (a fresh start).

### 5.5 Tokens on the phone (`TokenCache`, `SpotifyBridgeToken`)
- In memory only (never a file, Room, `renderer.json`, a log or a `toString`: `TokenResult.Ok.toString()` redacted,
  pinned). `get(deadlineMs, minLeftMs = 5 min, atStart)`: the kept token when it has 5 minutes left; else one bridge
  call (one at a time: a waiter shares the call in flight; a call its waiter gave up on is kept when it answers).
  **"The same token back"** - the refresh ahead of expiry getting the server's kept token again - is told by the
  token's value, not its expiry (the server answers whole seconds left; qa-26c-step4-code L3): no refresh, tried again
  in 2 minutes. After a 401 the renewal is §5.4's; it can't help when the route answers the refused token again.
- **The bridge** (§8): `getSpotifyToken(callback)`, 10 s; `status` -> `TokenResult`: `ok` -> `Ok(token, expiresAt =
  elapsed + expiresInMs, account)`; `off` -> `Off` (the host cleared and destroyed; the library will drop the entries);
  `reauthorize` -> `Reauthorize`; `busy` -> `Busy(retryAfter)`; `unreachable`, `key`, `launcher` (the launcher couldn't
  reach the server) and a timeout (also an older launcher without the method: its oneway call is dropped) ->
  `Unreachable`.
- **Cooldowns**: after `Reauthorize` no bridge call for 10 minutes (and the line says so); after `Unreachable` the next
  play asks again; `Busy` waits its `retry_after` (the callback's `expiresInMs`, §8 item 2).
- **One prompt retry at a start** (qa-26c-step3-code L3, built in step 4's `TokenCache`): an `Unreachable` during a
  play start is asked once more about 5 s later, inside the start budget, before the line shows - the server's refresh
  may only have waited behind a sweep past its 8 s budget (§2.6) and is then kept, so the second ask finds it. One
  retry only; a second `Unreachable` is the line.
- **Ahead of expiry while playing** (QA M2): the SDK asks for a token when the old one expired (§1.1) - typically with
  the screen off, and the bridge -> launcher -> tsnet -> server path is untested in Doze. So while the renderer's
  `playWhenReady` is true, `TokenCache` refreshes when 10 minutes are left (under the renderer's wake lock, §5.7), and a
  failed refresh retries every 2 minutes until the old token expires; the SDK's `needToken` is then answered from
  memory. The debug log names each refresh (time and outcome, never the token). QA checklist item 6 runs 2.5 hours, so
  at least two refreshes happen with the screen off.
- The SDK's `needToken` is answered from the cache or the bridge; a failure answers `null` (the SDK then reports an
  `authentication_error`, which the renderer reads through the token result it already has: as built in step 5a's fix
  round, `reauthorize` or `off` at the SDK's ask is §5.8's line at once - `SPOTIFY_ASK_ADULT` and `reauthorize` -,
  and an `authentication_error` outside a start shows the last failed answer's line, else "Trenger internett";
  qa-26c-step5a-code L3). A token of another account than the page's is never answered (§5.2).

### 5.6 Audio: focus, noisy, volume, the sleep fade, calls
- **It is local audio** (the phone's speaker, wired or Bluetooth; STREAM_MUSIC through the WebView's media stack), so
  `LocalRenderer`'s rules apply, not Sonos's: no hush, no speaker volume.
- **Audio focus**: the renderer requests `AUDIOFOCUS_GAIN` (`AudioAttributes` USAGE_MEDIA, CONTENT_TYPE_MUSIC, SPEECH for
  a show) before a start and abandons it on pause/detach; a loss is `Paused(FAULT)`, a transient loss `Interrupted`
  (paused, played again on the gain, vibb's resume overlap as `LocalRenderer` does), a "duck" is `vibb.volume(0.3)` and
  back. **[device check]** whether the WebView requests focus itself for its media: if it does (our own request would
  then be taken away by our own WebView), the renderer drops its request and maps the WebView's pauses instead - step 5a
  logs the focus changes on the Jelly Star and picks the branch; the open point says so.
- **Becoming noisy** (a headset unplugged, Bluetooth gone): a receiver for `ACTION_AUDIO_BECOMING_NOISY` while playing
  -> `Paused(FAULT)` (ExoPlayer's `setHandleAudioBecomingNoisy` on the local side).
- **Our own ExoPlayer stands down** (QA L1): attaching the Spotify renderer from the phone calls `local.standDown()`
  first, as `toRoom` does (`PlaybackService.kt:1552`, "a paused Exo keeps its audio focus"), so our own player doesn't
  hold focus against the Spotify renderer's request.
- **The output picker**: a `dev:<id>` choice can't be forced on the WebView's audio (no `setPreferredDevice` for it);
  under the Spotify renderer the sheet keeps the choice for the next local item and Android routes Spotify like any
  media (a connected headset or speaker wins). `setPreferredAudioDevice`, `setWakeMode`, `setAudioAttributes` are
  no-ops here (26a §3.2's remote rule).
- **Volume**: the system's STREAM_MUSIC volume and the launcher's cap (the WebView plays on STREAM_MUSIC [device check]);
  the SDK's own volume stays 1.0 except during the fade.
- **The sleep timer**: `fadeMs` = 10 s; `fadeOutAndPause` steps `vibb.volume` from 1.0 to 0 over 10 s, pauses, then puts
  1.0 back; `cancelFade` stops and restores.
- **Calls** (26a §6.7): `CALL_HOLD`/`CALL_RELEASE` go through the service to `renderer.pause()`/`play()` with
  `CallPause` unchanged; the audio-mode backstop stays Sonos-only (focus covers a system call here).

### 5.7 Doze, locks, bedtime
- **Locks** (`SpotifyLocks`, beside `SonosLocks`): while a start runs or the renderer shows playing, a partial wake lock
  and a Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`: `FULL_HIGH_PERF` is deprecated since API 34, the app's minSdk, and
  taken as LOW_LATENCY), each acquired with a 30-minute timeout and renewed by the 15 s save tick, released on every
  pause, `Ended`, error, `Elsewhere`, detach and destroy. Never a screen wake lock (`SourceScanTest`). Why (§0.4): the
  stream must not stall in Doze, and the spike's no-lock pass was the emulator. **On API 34+ every Wi-Fi lock is a
  LOW_LATENCY lock, active only with the screen on and the app in the foreground** (qa-26c-step5a-code L1): with the
  screen off it keeps nothing out of Wi-Fi power save - kept, harmless, but not the guard this section once called it
  (SonosLocks' Wi-Fi lock is the same). Only the Jelly Star's screen-off run shows whether Wi-Fi power save stalls the
  stream, and whether the wake lock is needed; the open point asks both.
- **The foreground service**: the session's (Media3 starts it while the player plays; "FGS only from a tap or a media
  command" holds, a Spotify start is one).
- **Bedtime and time rules** (21 step 4, 26a §6): the launcher pauses the session before it suspends the app
  (`pauseBeforeSuspend`) -> `renderer.pause()`; `MY_PACKAGE_SUSPENDED` (the service up) -> `saveNow(force)`, `pause()`,
  and **the host is destroyed whatever the state** (QA L11): the audio stops with the renderer process whatever the
  network does, and no Connect device "Vibb" exists while the app is suspended (else another Spotify app could transfer
  playback to it, and it would sound for a moment before the ownership rule pauses it - media needs no gesture). While
  suspended `playAllowed` refuses every start (`KidPlayer`, the commands, resumption). Outside a suspension, that
  moment of a foreign transfer to an idle-but-alive host (at most 5 minutes after the last play) is accepted.

- **Restart**: the service attaches `LocalRenderer` as today; a stored Spotify queue is restored paused (`load`, no
  play, no WebView, no network); the next play starts at the bookmark.

### 5.8 Errors and what the kid sees
**The carriers** (QA M6; the code has three, and each row below names its own):
- **`RendererState`** (`PlayerModel.kt:46`, today the Sonos four) gains `SPOTIFY_NEEDS_WIFI`, `SPOTIFY_NEEDS_INTERNET`,
  `SPOTIFY_UNAVAILABLE` (the quota's line, its own value as built in step 5a: an enum value carries one string),
  `SPOTIFY_ASK_ADULT`, `SPOTIFY_NOT_ON_PHONE`, `SPOTIFY_ELSEWHERE`, `SPOTIFY_STOPPED`, with strings in `values` and
  `values-nb`; the Spotify renderer's `rendererState` carries the current one, `PlayerHub.State.rendererState` brings it
  to the player (`PlayerScreen.rendererStateText`, the quiet line under the title) and - for the entry that is loaded -
  to its tile's hint (`UiModel` reads `PlayerHub.State.entryId` + `rendererState`; the tile's line is otherwise
  `LibraryView.hint` from the snapshot). A new property `RendererState.remote` (true for the four Sonos values) keeps
  26a's meaning where it matters: **`callStep`'s "paused" test** (`PlaybackService.kt:1121`, `rendererState == null`)
  becomes `rendererState?.remote != true`, so a paused Spotify renderer with a state counts as paused for the call rule.
- **`PlayerHub.Message`** (transient, `PlayerHub.kt:124`): `CANT_PLAY` for an unplayable track's skip (the existing
  message), `CANT_PLAY_ON_SONOS` on a room (26a). No new message; `NOT_DOWNLOADED` is never emitted for Spotify.
- **The stored outcome** (`spotify_state.json`, §4.4): the persistent account states - `reauthorize`, `refused`,
  `not_premium`, `no_widevine`, `quota` - show on **every** Spotify tile's hint ("Spør en voksen" / "Ikke
  tilgjengelig nå"), not only the loaded one's, until a play succeeds (`ok`).

**The lines name no source** (step 8). Through step 7 each began with "Spotify"; now each is the app's own wording for
the case, as another source's line would say it. The app has no exact equivalents to reuse: its stream refusal is
`not_downloaded` "Ikke lastet ned - trenger Wi-Fi" (wrong for an entry that is never downloaded, so only its "trenger
Wi-Fi" is taken), its Sonos lines name the speaker ("Høyttaleren stoppet", "Spilles i en annen gruppe" - the passive
"Spilles ..." form is taken for elsewhere); the rest are neutral. `RendererState`'s names and the string names
(`renderer_spotify_*`) are unchanged - code, pinned by the tests; only the values changed.

| What happened | The renderer | Carrier: the line (nb / en) | `music_state.spotify` |
|---|---|---|---|
| no Wi-Fi (streaming on mobile data not allowed: design 30's streaming switch off) | no start, paused | `SPOTIFY_NEEDS_WIFI`: "Trenger Wi-Fi" / "Needs Wi-Fi" | unchanged |
| offline, the SDK script or `ready` not reached in 20 s, the play call unreachable, no state for 30 s | paused | `SPOTIFY_NEEDS_INTERNET`: "Trenger internett" / "Needs the internet" | `unreachable` |
| `Reauthorize` (refresh token ended, app refused) | paused | `SPOTIFY_ASK_ADULT` + the stored outcome: "Spør en voksen" / "Ask a grown-up" | `reauthorize` |
| the play call's 403 (not `PREMIUM_REQUIRED`), or a 401 the one renewal can't cure (the same token back, or a second 401; qa-26c-step4-code L4) | paused | `SPOTIFY_ASK_ADULT` + the stored outcome | `refused` |
| `account_error`, or the play call's 403 `PREMIUM_REQUIRED` twice (one retry after about 1 s, L7) | paused | `SPOTIFY_ASK_ADULT` + the stored outcome | `not_premium` |
| the play call's 429 `QUOTA_EXCEEDED` | paused | `SPOTIFY_UNAVAILABLE` (built as its own value in step 5a): "Ikke tilgjengelig nå" / "Not available right now" + the stored outcome | `quota` |
| `initialization_error` (no EME/Widevine) | paused, the host destroyed | `SPOTIFY_NOT_ON_PHONE`: "Virker ikke på telefonen" / "Doesn't work on this phone" + the stored outcome | `no_widevine` |
| `playback_error` on one of our tracks, or Spotify skipping it (region, the Family explicit filter, removed) | `Error("unplayable")`: the queue moves on; after 3 in a row, paused | `PlayerHub.Message.CANT_PLAY`; the list's row "Kan ikke spilles" | `item_errors` `unplayable` |
| a confirmed `null` state while playing | paused, bookmark kept | `SPOTIFY_ELSEWHERE`: "Spilles et annet sted" / "Playing somewhere else" | unchanged |
| the renderer process died, or a crash | paused, the host destroyed | `SPOTIFY_STOPPED`: "Stoppet - trykk spill" / "Stopped - press play" | unchanged |
| `Off` (the phone was unbound) | paused, the host cleared | none: the entries leave with the next library | `none` |

The parent sees the same through `music_state` on the device card (§3.6). Nothing here touches another source.

### 5.9 Context mode
(As built in step 6 and its code QA fix round, qa-26c-step6-code.)
- **The synthetic item is the snapshot's** (QA H3): `Snapshot.listed` gives a `spotifyMode == "context"` entry one
  `PlayItem` made by `logic/Spotify.kt`'s pure `contextItem(entry, live)` - key **`context`** (stable, never a track id),
  titled by the live track once one has played (the entry's name before), no url, no duration - so `Snapshot.entries`
  shows the tile, `playEntry`'s order and `restore()`'s `storedPlan` find the key, and the stored queue
  (`NowPlayingRow.keys`), `Active.items`, `lastIndex` and the position rows stay valid across tracks. `LibraryView`
  gives it `hint_spotify_picks` and no "N av M" (the player shows no number either); the list screen shows one row.
- **Position and bookmark**: the row `(entry key, "context")` holds the live track's position; the renderer saves a
  context item with `durationMs = null` and the service keeps no duration for it (`keepsDuration`), so it is never
  marked done (a track's end is not the context's; vibb's bookmark has no done mark). **The live track** - its uri and
  title - is in `noBackupFilesDir/spotify_context.json` (`{entryKey: {"uri": "spotify:track:<id>", "title": "<Song —
  Artist>"}}` - the uri, since a playlist context also plays episodes; tmp + rename, `data/Store.kt`, the renderer
  writes it through its ports), written on every track change; the snapshot (the item's title) and the renderer (where a
  playlist goes on) read it; a parsed library drops the rows of entries no longer in context mode.
- **A start** is `PUT .../play` with `{"context_uri": <target>, "offset": {"uri": <the live track's uri>},
  "position_ms": <the context row's position>}` for a playlist (Spotify plays its own order from the stored track),
  `{"context_uri": <target>}` for an artist (no offset is documented for artists; the artist plays from Spotify's start -
  **an artist in context mode has no resume**, §12: the renderer and the screens show its place as 0,
  `contextResumes`). **A stored track that left the playlist**: when the play call carrying the offset is refused (a 404
  after the transfer, a 400), the offset is dropped - in memory and in the file - and the context starts from the top
  with **one** more play call in the same budget. A playlist context needs the **player's** account to be able to open
  the playlist (public, shared by link, or followed) [device check]; if it can't, the play call's 403 is `REFUSED`
  (`SPOTIFY_ASK_ADULT`, the stored word `refused`, as every 403, §5.4) and a 404 after the transfer without an offset is
  `CONTEXT_REFUSED` (`SPOTIFY_ASK_ADULT` on that tile only, no stored word: one playlist, not the account). The parent's
  card does not name the playlist (no report field for it; a later contract change).
- **Ownership** in context mode: any track of the context is ours (Spotify picks them). The SDK reference says a state's
  `context` uri "can be null": with none, our own live track and the track our last state named next are still ours,
  any other track is a uri list (another app's play) - foreign and paused, as is a state of another context. **Our list
  mode's add left in Spotify's queue** (it survives a new play, §5.4) is never ours and never the end: while the kid wants
  it playing the renderer skips it with one `vibb.next()` (the context goes on), else it is paused.
- As tracks change, the renderer swaps its session item's title in place ("Song — Artist"; the entry cover as art - no
  per-track picture fetch, the phone never loads remote images; the service's own `replaceItem` keeps the live title).
  Next and previous are the SDK's (`vibb.next()`/`vibb.previous()`; Spotify's rule), sent only while a track of our
  context is on the player; **paused, a step only moves**: if Spotify plays the track it moved to, that play is paused at
  once. Seek as in list mode. A track change on a network the gate refuses now pauses with the line.
- **The end, on evidence only**: our live track paused at 0 after it ran out, or another of the context's tracks paused
  at 0 then that our last state didn't name next (Spotify back at the top) - `Ended` (`clear_state` drops the context
  row's position; `spotify_context.json`'s entry goes too); a track of another, non-null context right then is Autoplay
  after the end (paused, `Ended`). A uri list, a `null` context, our leftover add and the named next track posted paused
  are never the end.
- No unplayable item in a context (Spotify skips what the account can't play in its own order; the synthetic item is no
  track). While this entry plays, its list shows "Musikk · rekkefølgen velges for deg" (step 8's wording) and its one
  row, the current track's title (no "neste: N spor": the state's `next` names at most three, usually two). No NY for a
  context entry (nothing is listed).

### 5.10 The service (`PlaybackService`) and the renderer axis
- **Which renderer**: at `start(a, plan)`, an entry with `source == SPOTIFY` on a phone output gets the
  `SpotifyRenderer` (made once per service, kept), every other entry the `LocalRenderer`; a room keeps its
  `SonosRenderer`. A change goes through `attach()` (the old one saved with force and paused before it detaches; 26a
  §0; from the phone `local.standDown()` too, §5.6) - the queue is always one entry, so sources never mix inside a
  renderer. **A detached web player owns nothing** (step 5b; deviation 5, qa-26c-step5b-code M1): its queue is null
  while another renderer plays (`queue = { active.takeIf { renderer === spotify } }`: no place written), and `attach`
  tells it (`SpotifyRenderer.detached()`): until its next `load` every playing state - the account's other app resuming
  "Vibb", a transfer - is paused at once, never the focus, the locks or a play the session can't show or stop.
- **The service decides playability itself** (QA H1), so every site below gets an explicit Spotify rule. The rule
  `isSpotify(a) = a.entry.source == Source.SPOTIFY`; `spotifyPlayability(caps, streamMobileData)` (pure,
  `logic/Spotify.kt`) = `streamAllowed` only - `STREAM`, or `NEEDS_NETWORK` with the reason (Wi-Fi or internet) - and
  **never `GONE`** (a Spotify item has no copy and no url by design). `streamMobileData` is design 30's **streaming**
  switch (`Store.streamMobileData`, the policy's and managed config's `stream_mobile_data`), never the downloads switch
  nor the old `mobile_data` (= both); the web player's own gate (`SpotifyRenderer.networkLine`) reads the same switch.

| Site (`PlaybackService.kt` at `437eb84`) | Today | With Spotify |
|---|---|---|
| `playable()` (`:706-712`) | true on a room, else `playability(hasCopy, url != null, ...)` (design 30: with `store.streamMobileData`) | a Spotify item: `spotifyPlayability(caps, store.streamMobileData) == STREAM` (`itemPlayable`) - the streaming switch, never the downloads one |
| `playableFrom()` (`:714-718`) | the first playable from an index | a Spotify entry: all items or none (the gate is the network, not the item), so `index` or null |
| `start()` (`:670-702`) | `NOT_DOWNLOADED` when nothing or another item is playable | a Spotify entry with no network: no play - as built (step 5b, deviation 2) the queue is loaded paused at its place (no page, no network) so the loaded entry carries the renderer's state `SPOTIFY_NEEDS_WIFI`/`_NEEDS_INTERNET` (§5.8, `SpotifyRenderer.refused`), never `NOT_DOWNLOADED`, never another index |
| `goTo()` (`:883-891`, steps, swipes) | `NOT_DOWNLOADED` and a jump | the same Spotify rule as `start` |
| `onItemChanged()` (`:1048-1080`) | an unplayable item moves on, else pauses with `NOT_DOWNLOADED` | a Spotify entry: the network check as `start` (pause with the Spotify state, never move on) - as built (deviation 4) for Spotify's own advance (`auto`) only: a load, a step and the renderer's own moves were gated where they were made; **the natural-advance seek (`auto && kind == LOCAL`) stays `LOCAL` only** (QA M4: on Spotify, as on a room, the next track starts where Spotify starts it) |
| `onError()` (`:1150-1182`) | records `item_errors` only for `url != null`; `NOT_DOWNLOADED` when offline (design 30: or `gated`, the phone's own player's stream gate; an `http_416` reloads the item uncached, `renderer.kind == LOCAL` only) | a Spotify item: `unplayable` is recorded (`ItemErrors.record`), the message `CANT_PLAY`, the queue moves on (the renderer stops after 3 in a row, §5.4); the offline test doesn't apply. As built in step 5a: the existing move-on (`seekTo(next)`, `prepare()`, `play()`) **inside** the renderer's `Error("unplayable")` is the renderer's own move-on - no call when Spotify already plays that item or past it, nothing after the third - so **`onError` must stay synchronous on main for a Spotify item** (a `post`/`launch` around the move-on would make it an unguarded play; 5b pins it) |
| `mediaItem()` (`:726-768`) | uri = the url (empty for url-null), an `ItemTag` (design 30: `through` for NRK/RSS) | a Spotify item: uri `uriOf(kind, key)` (`spotify:track:<key>` / `spotify:episode:<key>`; the context item the entry's target), **no `ItemTag`, no cache key** - `GuardsTest`: a Spotify MediaItem never reaches `ItemMediaSourceFactory`, so design 30's cache-through sink never sees one |
| design 30's `markPlayed`, `updateHeld`, `activeFor`'s claim (merged from main 571f86f) | the phone's own player: a played mark (`downloads.playedAt`), the held set, the queue's claim | `markPlayed`/`updateHeld`: `renderer.kind != LOCAL` returns first, so the web player marks nothing played and holds nothing; the claim follows the **queue's** kind: `playEntry`, `playItem` and `restore` build a queue with `queueKind(renderer.kind, entry)` (the phone's renderer for the entry on the phone, else the room - never the attached one, still the web player after a Spotify play; qa-26c-merge-code M1) and `activeFor` claims only when `claimsQueue(kind, entry)` (`LOCAL`, not own files), so a Spotify queue claims nothing and the next NRK/RSS/Storytel queue after it does; `downloadPlan` prunes any Spotify row, never a played extra (`SpotifyRulesTest`) |
| `wakeModeFor()` (`:721-724`) | `WAKE_MODE_LOCAL`/`NETWORK` | a no-op on the Spotify renderer (its own locks, §5.7) |
| `onIsPlayingChanged()` (`:1137-1148`) | deletes the item's error when it plays | unchanged (a Spotify item has no `fileId`) |
| `onPlaybackResumption()` (`:473-509`) | not a room: `restore()` and the items handed to the session (Media3 plays them on `KidPlayer(local.player)`) | **a stored Spotify queue takes the room's shape** (QA H2): attach the Spotify renderer, `resumeLast()` (through `start`, gated), and fail the future ("playing on Spotify") - no local items; `restore()` builds for the renderer its entry needs |
| `onDestroy()` (`:321`, `renderer !== local`) | releases the local player when a room renders | the Spotify renderer is released (the host destroyed) besides `local.release()` |
| `lookAtRoom()` (`:1372`, `:1388`), `lookAgainBeforePlaying()` (`:1451`) | look at a recorded room only while the phone's ExoPlayer renders | `renderer.kind.onPhone` instead of `=== local`: a recorded room is looked at before a Spotify start too (Home's RESUME never starts the phone under our stream still on a room) |
| `comeHome()` (`:1602`, `renderer !== local`) | the phone's queue after a room | coming home to a Spotify entry attaches the Spotify renderer paused (the room had nothing of it: `toRoom` refuses a Spotify entry) |
| `toRoom()` (`:1525-1560`) | a queue a speaker can play, else the phone stays | from a Spotify entry: nothing speaker-playable, so **the phone keeps playing** and `CANT_PLAY_ON_SONOS` shows (`:565`, `:1632`) - the code's behaviour, kept (QA L1) |
| `loadForRenderer`/`load(remote)` | `remote = SONOS` | unchanged (`!onPhone`) |
| `queueFor`'s speaker filter | `kind == LOCAL ||` speaker-playable | `onPhone ||` speaker-playable |
| the audio-mode call backstop (`kind == SONOS`) | rooms | unchanged |
| `callStep()`'s paused test (`:1121`) | `rendererState == null` | `rendererState?.remote != true` (§5.8) |
| `RendererGate`, `renderer.json` | rooms only | unchanged (Spotify is never stored there) |
| `MusicViewModel`/`UiModel` `onRoom` | read from the **output** (`OutputKind.SONOS`, `MusicViewModel.kt:41`, `MusicRoot.kt:75`), not `RendererKind` | unchanged (QA L1); `playerNumber`/`swipeSwitch` as for the phone |
| `reportNowPlaying` (every change, the 15 s tick, `onDestroy`) | `renderer.showsPlaying` | unchanged: it reads the Spotify renderer's `showsPlaying` (§5.4's table) |

  Step 5b re-reads every `renderer === local`/`!== local` and `RendererKind` site against this table (`GuardsTest`
  pins the list).
- **Tests** (step 5b): a Spotify cover tap reaches `SpotifyRenderer.load` and `play` (no `NOT_DOWNLOADED`); offline it
  shows "Trenger internett" and nothing moves; a natural advance into a partly heard track sends no `PUT`; a headset's
  play after a restart with a stored Spotify queue starts the Spotify renderer and hands the session nothing; an
  `unplayable` is in `item_errors`.

- **Design 28 (the listening window)**: a Spotify session **counts as listening like any renderer**, and needs nothing
  new: the launcher's window stamps from the bridge's `setNowPlaying` (`playing = renderer.showsPlaying`, sent on every
  flip, every 15 s while playing, paused from `onDestroy`), and Home's card and the lock's tile read the platform
  session, which wraps `KidPlayer(spotifyRenderer.player)`. What it needs from this design: `showsPlaying` as §5.4's
  table says (the intent during a start, held through a 30 s gap, false on a paused, confirmed-`null` or foreign
  state), the 15 s tick running for this renderer (it does for every renderer), and a paused report when the host dies
  or the state goes `null` - all in step 5b's tests.


## 6. The launcher

### 6.1 The token courier (`MusicBridgeService.getSpotifyToken`, `SpotifyTokenCourier`)
The appended AIDL method (§8 item 2) runs off the main thread: `POST /api/devices/music/spotify/token` through
`MusicServer` (`music/MusicServer.kt`, `createMusicServer` - the music routes' one Retrofit interface, beside its
`storytel()`; the device bearer, over tsnet like every server call), 10 s; the pure
`SpotifyTokenCourier.spotifyTokenAnswer(httpStatus, body)` maps the answer; the callback gets the body's `status`, the token (only
for `ok`), `expires_in_s * 1000` and `account`; a transport failure or a timeout -> `launcher`. **No cache** (the
music app caches; one source of expiry), **no log** of the body or the token (the existing `callerAllowed` check: only
the music packages signed like the launcher). An unknown status from a newer server passes through as is (the music app
maps unknowns to `Unreachable`).

### 6.2 The DNS filter (`L/music/SpotifyHosts.kt`, `server/testdata/spotify_hosts.json`)
- The phone's on-device filter (`KidVpnService`/`DnsFilterEngine`) walks label suffixes, so a family blocklist that
  carries one Spotify host (ad lists often carry `spclient.wg.spotify.com`) or a parent of one would break playback.
  FCM's pattern (`push/FcmHosts.kt`, `play::is_fcm_protected`) applies, **gated per phone**:
  - the policy's `music` object gains `spotify` (bool): true while the phone is bound to an account (§2.5);
  - the server drops every delivered domain that **equals or lies under** a listed suffix, or equals a listed exact
    host, from that phone's categories and custom blocks (`handlers::device_api`, beside `is_fcm_protected`) when it is
    true;
  - the launcher's `DnsFilterEngine.classify` (`DnsFilterEngine.kt:107-111`, where `isFcmHost` is) returns null (not
    blocked) for such a domain **before its suffix walk** while the cached policy's `music.spotify` is true (so a
    blocked parent like `akamaized.net` never blocks the listed exact host under it);
  - **the version moves with the binding** (QA M7): the launcher refetches the delivered list only when
    `dns_filter_version` changes, and `compute_dns_filter_version` (`handlers/device_api.rs:591-624`) hashes the global
    list, the overrides and the custom domains - not the binding. It gains `\nspotify:true` in its hash **while the
    phone is bound** (built so in step 3: an unbound phone keeps the version it had, so the upgrade makes no phone
    refetch its list), so binding *and* unbinding a phone refetch its list (else an unbound phone kept a list without
    the Spotify domains until some other change). A test: bind and unbind each change the version.
  - **What it overrides**, said on the device card's DNS section while bound: "Spotify's hosts are allowed on this phone
    while Spotify is on" - a parent's deliberate block of `spotify.com` or `scdn.co` (a streaming category) is
    overridden on that phone for every app, not only the music app. And where the parent types a block
    (`/dns?device=<id>`, qa-26c-step3-code L5): each of the phone's custom blocks the rule names reads "not applied while
    Spotify is on for this phone", and the site-wide ones it names are listed under them.
  - **The rule**, the same on both sides (`spotify::is_spotify_host`, `isSpotifyHost`): a domain equal to or under a
    suffix - Spotify's own parents included, so the delivered list drops `spotify.com` itself - or exactly an exact host
    (a third-party parent like `akamaized.net` stays delivered; the launcher exempts the exact hosts before its walk).
- **The list** (`spotify_hosts.json`, byte-identical in `server/testdata/`, `launcher/app/src/test/resources/` and
  `music/app/src/test/resources/`; each side's constant checked against it by a test - the server's `HOST_SUFFIXES`/
  `EXACT_HOSTS` and the launcher's `SPOTIFY_HOST_SUFFIXES`/`SPOTIFY_EXACT_HOSTS` from step 3, the music app's with
  `M/spotify/SpotifyHosts.kt` in step 4):
  ```json
  {
    "comment": "Spotify's hosts for the Web Playback SDK (design 26c §6.2): the DNS filter never blocks them on a phone with Spotify on, and the music app's WebView loads nothing else. PROVISIONAL (unverified) until the spike's item 10 step 7 capture replaces it (step 6 at the latest).",
    "suffixes": ["spotify.com", "scdn.co", "spotifycdn.com", "spotifycdn.net", "pscdn.co"],
    "exact": ["audio-ak-spotify-com.akamaized.net", "audio-akp-quic-spotify-com.akamaized.net"]
  }
  ```
  **[unverified]** as a whole: the suffixes are Spotify's known domains (the SDK `sdk.scdn.co`, accounts and the Web
  API under `spotify.com`, `spclient`/`dealer`/`apresolve` under `spotify.com`, images `i.scdn.co`, audio CDNs under
  `scdn.co`/`spotifycdn.com`/`akamaized.net`, podcasts `pscdn.co`). **Step 3 lands with this provisional list** (its
  comment says so); the capture replaces it in a one-line commit of the three copies whenever the user has run it, **before any
  stable tag** (step 6 found it not run: the capture is the user's), and the stable tags wait for it (QA M9: the contract doesn't wait on the capture). **How to find
  the real set** (the spike's §7 item 10 step 7): the emulator with `-tcpdump spike.pcap` playing playlists, a skip storm, a

  podcast and a 30-minute run; `tshark -r spike.pcap -Y "dns.flags.response == 0" -T fields -e dns.qry.name | sort | uniq
  -c`; plus the device page's DNS log on the Jelly Star with the family's categories on. Every Spotify-owned name goes in;
  a third-party CDN goes in as an exact host only. A host Spotify adds later shows as a blocked request in the music
  app's debug log and in the parent's DNS log; adding it is a one-line change of the three copies.
- **Widevine provisioning**: a WebView provisions Widevine L3 with Google (`www.googleapis.com`) once [unverified];
  that host isn't exempted (it would open `googleapis.com`). If a family list blocks it, the phone reports
  `no_widevine` and the device check names it.

### 6.3 The WebView provider is never restricted
The music app's player is the system WebView. `controllablePackages` already skips system packages without a launcher
icon (the WebView provider has none), and the spike ran with the kiosk on and Spotify's app hidden [spike]. As a guard,
`computeEnforcementPlan`'s `neverRestrict` gains the current WebView provider (`WebView.getCurrentWebViewPackage()`,
read off main once per `apply`), so no allowlist or time rule can suspend or hide it (a suspended provider breaks every
WebView, the launcher's own login screens included). One pure rule, `EnforcementPlanTest` pins it.

### 6.4 Nothing else
No new launcher screen, no engine-app state (that was option A's), no change to `controllablePackages`' other rules or
the music card (design 28 reacts to `setNowPlaying` as before, §5.10). `MusicPlanTest` re-reads the library snapshot
with the Spotify entries (the courier carries their listings like any other).

## 7. Security, privacy and Spotify's terms

### 7.1 Secrets (server and phone)
- **Refresh tokens**: sealed at rest (AES-256-GCM, the music key, AAD bound to the account), opened only to refresh,
  never sent anywhere but `accounts.spotify.com/api/token`, never in a page, event, log, error, `Debug`. Deleted with the
  account (Remove, Clear). A token rotated by a refresh is written before the access token is used (§2.4).
- **The phone's token carries its account's whole scope set** (§2.2, QA L2): one hour, memory only; a parent account
  bound as "plays as" would hand the phone `playlist-read-private` for that hour. Since step 8 the parent's account is
  never logged in (§0.2 item 1), so only a parent ignoring the setup text could bind one; the binding's hint says
  "the child's own Premium account, never yours".
- **Access tokens**: memory only on the server (per account), in the launcher (in flight), in the music app
  (`TokenCache`) and in the page's JavaScript (the SDK's callback). On the phone's Kotlin side `Authorization`/`Bearer`
  appear only in `StorytelApi.kt` and `spotify/SpotifyApi.kt` (`SourceScanTest`); `MediaCache`'s clients never add one.
  The token goes into the page through `evaluateJavascript` with a quoted string, never into a URL, the DOM or a log.
- **The PKCE verifier and the code**: memory only, single use, 10 minutes; the callback's query is never logged.
- **Device ids**: the SDK's device id stays in memory (it names this phone in the account's Connect list; not a secret,
  but not reported either).
- **Logs**: no `Log.` in `spotify/` at all (`SourceScanTest`, as built in step 5a): the host, the locks and the renderer
  take a `log` function the service gives them (debug-only: `spotifyLog`, `Log.d` under `BuildConfig.DEBUG`, 5b).
  **The page's console is a log channel too**: `WebChromeClient.onConsoleMessage` is handled and returns true always
  (else the WebView writes every console message, Spotify's iframe's included, with its source URL to logcat - a failed
  WebSocket names its URL, and the SDK's may carry the token); a debug build notes the level and the source's host only
  (qa-26c-step5b-code M2). The rest: fixed-code lines only (host names, statuses, state
  kinds - never a token, a uri list or a state JSON), and the SDK's error message only in a debug build
  (`BuildConfig.DEBUG`, qa-26c-step5a-code L8); the server's Spotify lines carry ids,
  statuses and codes. Fakes: `FAKE-ACCESS-<n>`, `FAKE-REFRESH-<n>`, `FAKE-CODE-<n>`, client id
  `0123456789abcdef0123456789abcdef`, user ids `fakeparent`/`fakekid`, track ids `FAKEtrack000000000000N` (22
  characters) - never a real token shape.

### 7.2 The WebView
- Loads only our page and Spotify's hosts (§5.2's interceptor - service workers' requests too -, the CSP, no navigation,
  no new windows; WebSockets and a redirect's target only through the DNS filter and the CSP - qa-26c-step5a-code L2),
  with no file or

  content access, no geolocation, the protected-media permission only, never on screen, cleared on an account change
  and on "off".
- **The SDK script is not pinned**: it comes from `https://sdk.scdn.co/spotify-player.js` over TLS at every page load.
  Pinning (SRI or a hash check in the interceptor) would stop playback whenever Spotify updates the file in place, and
  the Developer Terms forbid shipping a copy (spike §1: "distribute ... only in binary form as part of an SDA"; the spike
  never copied it). The trust is Spotify's TLS host, the same trust every browser gives it; what that script can reach is
  bounded by the page (Spotify's hosts only), the bridge (§5.3: no privileged method) and the token's scopes.
- Cookies and storage are the SDK's own; nothing of ours is stored in the WebView. Safe Browsing and WebView metrics
  are off (§5.2).
- **The kiosk rule stands**: no web view is ever shown to the kid; `SourceScanTest`'s "no web views" becomes "the one
  WebView is `SpotifyWebHost`'s, never attached to a window" (the scan pins `WebView(` only in that file, no `addView`
  of it, `setAllowFileAccess(false)` and `setAllowContentAccess(false)` present, `addJavascriptInterface` only there).

### 7.3 Spotify's Developer Policy and Terms (15 May 2025) - what the design does
| Clause (the Policy's text verified 2026-10-13; the Terms' as read for spike §1 on 2026-10-12) | Here |
|---|---|
| II "Attribution ... by using the Spotify Marks"; "Display metadata and cover art when streaming" | **Not met on the kid's screens - a deliberate deviation** (step 8, the user's decision of 2026-10-14 for private use: "vi ikke trenger noe spor av spotify noe sted i appen"). Policy II.4 asks for attribution wherever Spotify content is shown; the kid's app shows Spotify content (titles, cover art, audio) with **no** attribution: no logo, no word "Spotify" on a tile, a hint, the player, the list or a line (`SourceScanTest` pins it). Steps 5b-7 had the word on the tile, the hints and under the player's title; step 8 removed it. **The attribution lives only in the parent's PWA**: the Spotify page (its name, the accounts), each library card's kind label ("Spotify playlist" ...) and "Open in Spotify", the device card's "Spotify plays as" and its lines. Cover art and metadata are still shown while streaming (every playback has a title and a picture). **This is a second deviation, separate from the one accepted on 2026-10-06**: that acceptance was III's ("targeted to children", below), on a design that met II with the word on the tiles (`vibb-spotify-options.md`); dropping the word breaks II too, decided by the user on 2026-10-14 ("vi ikke trenger noe spor av spotify noe sted i appen? For barna skal jo dette bare være en tile til? Samme hvilke kilde?"). The sanction is the same: Spotify may switch the family's app off (a revoked client ID), and the tiles then say "Spør en voksen" (qa-26c-step8-code L5) |
| II "Metadata, cover art ... must be accompanied by a link back" | the PWA's card ("Open in Spotify") and entry page link each entry to `open.spotify.com`; the kid's screens open no links (design 20's kiosk rule) and, since step 8, name no source either - part of the second deviation above (II, the user's decision of 2026-10-14), not of the 2026-10-06 acceptance (III) |
| III "Do not build products and services which are targeted to children" | **this is a child's player**. Private use, the family's own dev-mode app, the risk the user accepted on 2026-10-06; the PWA's setup text says it plainly to every self-hoster (§2.1). The sanction is a revoked client ID: every refresh then fails `invalid_client`, the tiles say "Spør en voksen", nothing else breaks |
| III "Do not create any product or service which is integrated with streams or content from another service" | Vibb plays NRK, RSS, Storytel and own files in the same player (spike §1's "new finding"): the same accepted risk |
| III "Do not ... segue, mix, re-mix, or overlap any Spotify Content with any other audio" | one entry plays at a time; a switch pauses the old renderer before the new one starts (`attach`); the sleep fade only lowers the volume |
| III "mimic or replicate a core user experience of Spotify" | Vibb's curated kid UI (a parent-picked library, no search, no recommendations) is the "independent value"; the risk is noted (spike §1) |
| III "Do not analyze ... usage statistics" | nothing about listening leaves the phone except what every entry reports (`music_state`: errors and the account state; no plays, no history) |
| IV "Streaming ... only ... to subscribers to the Premium Spotify Service" | the SDK enforces it (`account_error`); a Free account is `not_premium` |
| I "an easily accessible mechanism to disconnect" + delete the data | Remove (one account) and Clear (everything) on the Spotify page delete the tokens and the account names; the WebView's storage is cleared on the phone |
| VI the app name must not begin with "Spot" | the PWA's setup text; the SDK device is named "Vibb" |
| Terms: "Do not store Spotify Content indefinitely"; local caching "limited to the temporary caching of metadata and cover art" | **an accepted-risk row** (QA L7): no audio is stored by us (no offline; the WebView's HTTP cache is the SDK's own, ~15 MB ring [spike 10b], cleared on an account change), but the listing's metadata (titles, durations) is stored on the server (`music_items`) and the phone (Room), and track art in the server's art store and the phone's covers for as long as the entry is in the library. What deletes them: removing the entry (its listing and items cascade; design 27's art prune drops pictures no listing names; the phone prunes on the next parsed library), a track leaving a playlist (its item goes at the next check), Clear (the accounts; the entries then reach no phone) |
| Terms: no copying or distributing the platform | the SDK is loaded at run time, never in the repo or the APK (spike §1) |

Spotify's own Norwegian terms ask for 13+ for an ordinary account (spike §8 Q6): the kid's account and its age are the
family's matter, outside the code.

## 8. The contract commit (step 3; one commit across `server/`, `launcher/` and `music/`)

1. **The policy**: `PolicyResponse.music` = `{library_version, mobile_data, volume_cap_pct, storytel_generation,
   spotify}` (`spotify`: bool, §6.2; since the merge of main 571f86f also design 30's `stream_mobile_data`,
   `download_mobile_data` and `storage_limit_gb`). `policy_json_keys_snapshot` (server) and `PolicyResponseCompatTest`
   (launcher).
   An older launcher ignores the key.
2. **The bridge** (byte-identical in both apps, appended):
   - `IMusicBridge.aidl`: the import `import me.vibb.bridge.ISpotifyTokenCallback;` beside the existing
     `IStorytelCallback` import at its top (an import is not a reordering of methods), and at the end:
     ```aidl
     /** A Spotify access token for this phone's account (design 26c §2.6, §6.1), through [callback]; the launcher
      *  never caches or logs it. */
     oneway void getSpotifyToken(ISpotifyTokenCallback callback);
     ```
   - new `ISpotifyTokenCallback.aidl`:
     ```aidl
     // The Spotify token, answered by the launcher (design 26c §6.1). Byte-identical in launcher/ and music/.
     package me.vibb.bridge;

     interface ISpotifyTokenCallback {
         /** [status]: one of bridge_contract.json's spotify_token.statuses; [accessToken] only with "ok", else null;
          *  [expiresInMs] its life from now with "ok", the wait before asking again with "busy" (the server's
          *  retry_after_s), else 0; [account] "<id>:<generation>" with "ok", else null. */
         oneway void onToken(String status, String accessToken, long expiresInMs, String account);
     }
     ```
   - `bridge_contract.json` gains `"spotify_token": {"statuses": ["ok", "off", "reauthorize", "busy", "unreachable",
     "key", "launcher"], "timeout_ms": 10000}`; `MusicPlanTest` and `BridgeTest` check their constants against it, and
     each pins `IMusicBridge`'s whole method sequence (a new method is appended to the list; qa-26c-step3-code L2).
3. **The library**: `server/testdata/music_library.json` regenerated (`MUSIC_LIBRARY_SNAPSHOT_WRITE=1`) with two Spotify
   entries - `"source": "spotify"`, `"target": "spotify:playlist:FAKEplaylist0000000001"`, `"cache": 0`, `"resume":
   true`, `"order": "auto"`, `"items": "<a version>"`, **`"spotify": "list"`**; and `"target":
   "spotify:artist:FAKEartist000000000001"`, `"spotify": "context"` - copied byte-identically to
   `music/app/src/test/resources/`. The new field appears only on Spotify entries. The tests that read it are re-checked
   (26b L12's list: `music_library_snapshot`, `ContractFilesTest`'s entry count and ids, `OfflineTest`'s and
   `BookmarksTest`'s string replacements - the new entries' values are chosen so those replacements still hit only what
   they meant - and the launcher's `MusicPlanTest`).
4. **`server/testdata/music_state.json`**: `"spotify": "ok"` after `"storytel"`; `item_errors` unchanged in shape (the
   sanitizer test gains `unplayable` and the six `spotify` words; anything else is dropped).
5. **`spotify_hosts.json`** (§6.2), three copies, provisional. Equality is pinned as for the other shared files (QA
   L10): music's `ContractFilesTest` compares its copy with `server/testdata/spotify_hosts.json` byte for byte, the
   launcher's `MusicPlanTest` compares its copy with the music app's and the server's (as it does for the AIDL), and
   `spotify::tests::hosts_match_the_contract` reads both app copies too (as `phone::tests` does), which is what
   `server-ci.yml` watching them needs; each side's constant is checked against its copy (`SpotifyHostsTest` in the
   launcher and `hosts_match_the_contract` on the server in step 3; the music app's `SpotifyHostsTest` with its
   constant in step 4).
6. **CI paths** (root `CLAUDE.md`'s list and the workflows): `launcher.yml` and `music.yml` watch
   `server/testdata/spotify_hosts.json` and both app copies; `server-ci.yml` watches the app copies; `music.yml` also
   watches `server/testdata/spotify/` and `scripts/fake-spotify.py` (step 4).

7. `server/testdata/music_listing.json` is **unchanged** (a Spotify item is an ordinary item: `url: null`, `hls: false`,
   `duration_ms`, `art`).

Only step 3 changes a shared file. The listing contract, the policy's other keys, the restrictions and
`report_state`/`now_playing` names don't change.

## 9. The fake Spotify, the debug switch, the emulator (§6l)

### 9.1 What can be faked, and what can't
The SDK, Widevine and Spotify's servers can't run in CI or the sandbox, and no account may be used there. A fake still
covers everything that is ours: the PWA login and the token exchange, refresh rotation and `invalid_grant`, the
listings and their gates, the token's way through the launcher, the WebView host (page, interceptor, bridge, token
callbacks), the renderer and the tracker (starts, windows, natural advance, ends, steps, pauses, `null` states,
errors), positions and resume, focus, locks, Doze, bedtime and calls - with **real audio** from the WebView (a tone), so
the volume cap and focus are exercised. It can't cover: the real SDK's event shapes and timing, Widevine/EME, track
relinking, Spotify's dev-mode refusals and quotas, the real host list, and the real network's Doze. Those are §6l part
B (the real SDK on the emulator with the user's account, the spike's recipe) and the Jelly Star checks.

### 9.2 `scripts/fake-spotify.py` (GPL, Python 3 stdlib, `-I`, `--selftest`)
Built like `fake-storytel.py`, answering from the **same fixtures the tests read** (vibb's "two fakes" rule):
`server/testdata/spotify/` (the accounts and Web API answers as step 2a named them: `me_parent.json`, `me_kid.json`,
`token_code.json`, `token_refresh.json`, `token_refresh_rotated.json`, `token_invalid_grant.json`,
`token_invalid_client.json`, `playlist.json`, `playlist_items.json`, `forbidden.json`, `album.json`,
`album_tracks.json`, `track.json`, `artist.json`, `artist_albums.json`, `show.json`, `show_episodes.json`,
`episode.json`, `rate_limited.json`, `quota.json`) and `music/app/src/test/resources/spotify/` (`play_request.json` -
the exact requests the phone sends: play, the context form, transfer, queue, shuffle, repeat -, `play_answers.json`,
`token_answers.json`, `sdk_states/*.json` - state sequences: start, advance, end of window and its gap, end of queue,
pause, elsewhere, relinked three ways, unplayable, autoplay, transfer-in, our queue add left over, a stale state after
a step back or a restart, a trackless state, shuffle/repeat on).
- **Accounts**: `/authorize` (an auto-consent page that 302s to `redirect_uri?code=FAKE-CODE-<n>&state=<state>`,
  remembering the challenge; `--deny` sends `error=access_denied`; `--as kid|parent` picks the user), `/api/token`
  (`authorization_code`: the verifier's S256 must match the remembered challenge, single-use code; `refresh_token`:
  `FAKE-ACCESS-<n>` with `--access-ttl` seconds, a new `FAKE-REFRESH-<n>` when `--rotate`; `--expire-refresh` ->
  `400 invalid_grant`; `--bad-client` -> `invalid_client`).
- **Web API**: `/v1/me`, `/v1/playlists/<id>` and `/items` (403 unless the bearer's user owns the fixture's playlist;
  `--snapshot` to change its `snapshot_id`), `/v1/albums/<id>` (+ `/tracks`), `/v1/tracks/<id>`, `/v1/artists/<id>`,
  `/v1/shows/<id>` (+ `/episodes`), `/v1/episodes/<id>`, `PUT /v1/me/player/play` (checks the body against
  `play_request.json`'s shape, 404 for an unknown device, `--not-premium` -> 403 `PREMIUM_REQUIRED`; records it and
  hands it to the stub device), `PUT /v1/me/player` (transfer), `PUT /v1/me/player/shuffle` and `/repeat` (checked
  against `play_request.json`'s queries, handed to the stub), `--rate-limit N` (every Nth request a 429 with
  `Retry-After: 2`), `--quota` (429 `QUOTA_EXCEEDED`).
- **The stub SDK** `/sdk/spotify-player.js`: our own JavaScript implementing the documented `Spotify.Player` surface
  (constructor, `connect`, `disconnect`, `addListener`/`on`, `getCurrentState`, `pause`, `resume`, `togglePlay`,
  `seek`, `previousTrack`, `nextTrack`, `setVolume`, `activateElement`; the events and errors of §1.1), playing what the
  fake's play endpoint received: a generated tone per track (`/fake/audio/<id>.wav`, the fixture's duration or
  `--track-seconds 20`) through an `<audio>` element - real audio through the WebView, no DRM. It long-polls
  `/fake/device/<id>/commands` - every URL relative to the script's own origin, so under the debug build's
  `https://fake-spotify.invalid/` they all pass the interceptor's proxy (§9.3), and every one a plain GET (the
  interceptor sees no request body; no header that would need a CORS preflight: the stub's own connect and token check
  carry the fake token in their query, to the fake only) - calls `getOAuthToken` at `connect` and
  every `--token-ttl` seconds, keeps Spotify's queue (the phone's adds) across a new play as Spotify does, emits
  `player_state_changed` like the documented `WebPlaybackState`, and serves `POST /v1/me/player/queue`. Options:
  `--elsewhere 60` (a `null` state at 60 s), `--autoplay` (after the last uri it plays a track that isn't in the list),
  `--transfer-in 30` (at 30 s a foreign context starts on the device),

  `--unplayable <id>` (a `playback_error` and a skip), `--no-eme` (`initialization_error`), `--not-premium`
  (`account_error`), `--slow-ready 15`, `--relink <id>:<other>`, `--shuffle` and `--repeat context|track` (the
  account's settings until the phone turns them off). `GET /fake/as?user=kid|parent` switches the auto-consent's user
  while running; `GET /fake/token?user=kid` answers as the server's token route (the debug switch's `debugTokens`).
- `--print-library` / `--print-listing ENTRY` print the debug feed's documents for a run without a server (26b's way).
- `--selftest`: every route against its fixture, the S256 check, rotation, the stub's JS parsed for the documented
  names, the play body checked against `play_request.json`. `music.yml` runs it after the build and watches
  `scripts/fake-spotify.py`, `server/testdata/spotify/` and `server/testdata/spotify_hosts.json` (root CLAUDE.md's
  paths list gains them).

### 9.3 The debug switch (music, 26b M2's solution applied again; QA M3)
26b met the same problem - a fake on `10.0.2.2`, a private address every guard refuses - with **one debug-only switch
and an exact list of readers**, each taking the base only for `10.0.2.2` or loopback (a `config.json` left on a real
phone running a debug build must never send a token elsewhere). Spotify does the same:
- `SpotifyEndpoints.debugBase: String?`, null unless the debug feed's `config.json` sets `"spotify_base":
  "http://10.0.2.2:8731"`; assigned only in `src/debug` (`DebugSpotify.kt`, from the receiver and from `DebugStartup` at
  every process start, as `DebugStorytel.kt`); a release has no feed (`checkReleaseHasNoDebugFeed`), so it is null there.
- **Exactly six readers, all in `SpotifyEndpoints.kt`** (`SourceScanTest` pins the writer and the readers):
  - `apiBase()` - the play, transfer and queue calls go to the fake's `/v1/...`;
  - `apiOverLan()` - those calls and `debugTokens` use `MediaCache`'s LAN client instead of the guarded public-only one
    (`MediaCache.guarded` refuses 10.0.2.2, `MediaCache.kt:64-67`; Storytel's `apiOverLan`, `StorytelEndpoints.kt:52-53`);
  - `sdkUrl()` - the page's script becomes `https://fake-spotify.invalid/sdk/spotify-player.js`;
  - `webHosts()` - the interceptor and the CSP admit `fake-spotify.invalid`;
  - `webProxy()` - **the interceptor fetches every `https://fake-spotify.invalid/<path>` request from
    `<debugBase>/<path>` over the LAN client and answers it** (the stub SDK, its tone, its long-poll), so the page sees
    https only and the WebView's mixed-content block (`MIXED_CONTENT_NEVER_ALLOW`, the default) stays as it is;
  - `debugTokens()` - `TokenCache` asks the fake's `/fake/token?user=kid` directly instead of the bridge, for a run
    without a launcher: as built (step 5b, deviation 7) only while the app is unmanaged; a managed phone takes its
    token through the bridge (§6l part A1's chain), and with the switch set **only a fake token** (`FAKE-ACCESS-`) goes
    on - a real one is `Unreachable` and never meets the debug base (`debugSwitchToken`; qa-26c-step5b-code L2).
- **The server side**: `SPOTIFY_ACCOUNTS_BASE` and `SPOTIFY_API_BASE` are honoured **only** as `http://127.0.0.1:<port>`
  or `http://[::1]:<port>` (anything else is ignored with one warning at start), and **only while one is set do the
  Spotify requests use `Reach::Any`** (§2.4; `music_net` refuses a loopback address for `Public`). With the override,
  §6l part A runs the whole chain - PWA login, server token, launcher, music app - against the fake on the emulator's
  host; without it every Spotify request is `Reach::Public` to Spotify's hosts. `spotify::tests` pins both.

### 9.4 `docs/testing/emulator.md` §6l (written in step 5b)
(§6j, the number this design first gave it, is design 28's in `emulator.md`, step 5b's deviation 1; step 5b's §6k became
§6l when main's design 32 took §6k, "Merge of main (571f86f)".) As built, part A
is two runs: A1 the whole chain with one fake process (the PWA, the server, the debug launcher, the music app managed:
its token through the bridge), A2 the phone alone on the debug feed (unmanaged: the fake's token route) for the
fake's options, each a fake restart.
- **Part A, the fake** (no account): the server with the two base variables, the fake on the host, the debug launcher
  and the debug music app. The PWA's Spotify page: not configured -> the registration text; save the fake client id with
  the loopback redirect, "Log in with Spotify" (the fake consents), the paste flow, the account row. Add the fixture
  playlist (list mode; ticks), the artist (context mode), an album, a show. On the phone: the tiles, a cover tap (the
  fake's log: one token, one transfer or play, the window), the tone, pause/resume/seek (no Web API call), next and a
  mashed next (one `PUT`), the window's end (a second `PUT` from the next index), the queue's end (`clear_state`),
  `--elsewhere` (paused, "Spilles et annet sted", the bookmark), `--unplayable` (moves on, `item_errors`), `--autoplay`
  (the track after the list is paused at once, nothing saved), `--transfer-in` (the foreign context paused), a 60-track
  playlist (the queue add at the window's end, no gap), a cold start of 15 s with `--slow-ready 15` (the button shows
  pause throughout, a second tap sends nothing), a headset's play after a restart with a stored Spotify queue (the
  Spotify renderer starts; ExoPlayer gets nothing), a context entry (the tile, play, a restart restores it paused at
  the live track),

  `--not-premium`, `--no-eme`, `--expire-refresh` (the tile "Spør en voksen", the parent's card "log in again"), screen
  off with `dumpsys deviceidle force-idle` for 30 minutes (the tone and the states continue; `dumpsys power` shows our
  wake lock only while playing), the sleep timer's fade, a launcher bedtime edge (paused, then nothing starts), a VoIP
  `CALL_HOLD` (paused and resumed), Home's card and the lock's tile following design 28's window, a restart (paused at
  the bookmark, no request), the output picker with a room (Spotify isn't offered there). The secrets grep: logcat, the
  app's data folder and the server's journal hold no `FAKE-ACCESS`, `FAKE-REFRESH`, `FAKE-CODE` or verifier.
- **Part B, the real SDK** (the kid's Premium account, the spike's emulator image): the real login of the kid's
  account on the PWA (https or the paste; the parent's is never logged in, step 8), a real playlist the kid's account
  owns (or the parent's, made collaborative and joined by the kid), play, pause, skip, resume after a restart; the debug log's permission line
  (which origin asked for protected media), the blocked-hosts counter (should stay 0; a redirect's target never shows
  there, so the parent's DNS log is read for hosts outside the list, L2), no `csp` error line in the debug
  log (the page's CSP admits what the real SDK's loader needs; qa-26c-step4-code L6), no token in logcat after a Wi-Fi
  drop while playing (the page's console is never logged, §7.1; qa-26c-step5b-code M2), and the DNS capture of §6.2.
  **Real state sequences** for `sdk_states/` are recorded by step 6's debug-only recorder (qa-26c-step5b-code, "the
  state-recording gap"), never through the log (§7.1), as built: the page bridge posts the compact JSON with the parsed
  state (`HostEvent.State.json`, left out of its `toString`) and the renderer passes it to the port
  `SpotifyPorts.recordState(json)` (events and our calls by name to `record(event)`); in the main source set the port
  is the hook `SpotifyRecording.recorder` - null, assigned only in `src/debug` (`DebugSpotify.kt`), so a no-op in a
  release - on only while the debug feed's `config.json` says `"record_spotify_states": true`: one JSON line per state
  (`{"t", "state"}`: the page's compact state - the track, `paused`, `position`, `duration`, the track window's `next`
  and `prev`, up to three uris each, and `context`, null for "no context object" and "uri null" alike; not
  `disallows`) plus the page's events and our own calls by name (`{"t", "event"}`), appended by one daemon thread to
  `debug-feed/sdk_states/<UTC switch-on time>.jsonl`, at most 32 MiB a file; part B records the real context sequences
  with it.

## 10. Tests

| Area | Tests |
|---|---|
| Server, pure | `spotify::tests`: links (every form of §3.1, `intl-xx`, `?si=`, junk, ids, refused kinds with their sentences), PKCE (RFC 7636's appendix B vector for S256; the verifier alphabet and length), the authorize URL's exact query, token answers (rotation, no rotation, `invalid_grant`, `invalid_client`), the listing parsers over the fixtures (`items[].item`, `is_local`, `is_playable: false`, episodes in a playlist left out and counted, duplicates once, titles "Name — A, B", the image pick, a 1000-track cap), `secret_types_have_no_debug` (`Access`, the pending login, the token answer), `no_secret_in_errors`, `hosts_match_the_contract` |
| Server, routes (`src/tests/music_spotify.rs`, `CannedFetch`) | the app setting (400s, env wins, the computed redirect URI from `Host`/`X-Forwarded-*`, http -> the loopback offer), Clear and a new client id remove the accounts and unbind phones (revision moves), login start (303, the query), the callback without a session (state required, single use, 10 minutes, replay nothing, `error`, `/me` 403 -> nothing stored), the paste flow, re-login of the same user (row kept, generation + 1, bound phones kept), Remove, the 180-day line and the 14-day warning, `needs_login` after `invalid_grant` (no request after), the token endpoint's six answers and its rate limit, `no-store`, the per-account refresh lock (two concurrent mints -> one refresh), the code never in a log line, event or page |
| Server, sweep | list mode for playlist/album/track/show/episode, context mode for an artist and a 403 playlist (and back to list mode once an account may list it), the reader order and memory, the snapshot gate (one request when unchanged, items after a day or Check now), `rate_limited`, `quota` ending the pass's Spotify checks, `spotify_off`/`no_account`/`reauthorize` without a request, the last good list kept, art candidates (own for tracks, the per-pass URL memo), the gate on library/listing/covers/art, the library's `spotify` field, the cards' lines, the device card (binding, two phones one account, the state words), the import (URI and open link imported, short link and collection skipped), the policy's `music.spotify` and the delivered blocklist's drop for a bound phone only |
| Launcher | `SpotifyTokenCourierTest` (new; the pure `spotifyTokenAnswer(httpStatus, body)` behind `getSpotifyToken`: every status mapped, `launcher` on a transport failure or timeout, an unknown status passed through, nothing cached), `DnsFilterEngineTest` (a Spotify host under a blocked parent passes only with `music.spotify`), `SpotifyHostsTest`, `EnforcementPlanTest` (the WebView provider never restricted), `PolicyResponseCompatTest`, `MusicPlanTest` (the AIDL files and `bridge_contract.json` equal to the music app's) |
| Music, pure | `SpotifyRulesTest` (the grammar, `uriOf`, visibility on the phone and never on a room, natural order per kind and the order setting, never fetchable, NY eligibility, the words and hints, the play window, the start budget), `SdkStateTest` (caps, nulls, junk), `SpotifyTrackerTest` (every sequence of `sdk_states/`, the end rule, relinking three ways, not-ours paused, `null` -> Elsewhere, measured positions only), `SpotifyApiTest` + `SpotifyHttpTest` (the exact play/transfer requests, every outcome, 401 once, 404 -> transfer -> once, `Retry-After`), `TokenCacheTest` (5-minute margin, one call in flight, cooldowns, redacted `toString`), `SpotifyHostsTest`/`WebPolicyTest` (our asset paths, Spotify hosts, http refused, the debug base only with the switch), `ReportTest` (`spotify` words, `unplayable`), `LibraryViewTest` (`Kind.SPOTIFY`, the hints, context mode's hint), `ContractFilesTest` (the Spotify entries parse; `spotify` field), `BridgeTest` (the callback's statuses), `PlayerJsTest` (the bridge's method names in `player.js` match `SpotifyWebHost`'s) |
| Music, service | `GuardsTest` (a Spotify item never reaches `LocalRenderer.load`/`ItemMediaSourceFactory`, nor the session through `onPlaybackResumption`; every `RendererKind` read through §5.10's rule; `saveNow` writes only measured positions), `SourceScanTest` (§7.1, §7.2: the one WebView, its settings, `addJavascriptInterface` only there, no `addView` of it, `Authorization` only in the two API files, no `Log.` with a token, the debug switch's writer and readers, the nb strings), the renderer's behaviour through a fake host (`SpotifyRendererTest` with a scripted page: start, pause, steps coalesced, the window's end, `Ended`, focus loss and transient loss, noisy, the fade, a suspension destroying the host, `showsPlaying` per §5.4's table - held through a 30 s gap, false after it) |
| The fake | `python3 -I scripts/fake-spotify.py --selftest` (CI, `music.yml`) |

As built in step 3 (and its fix round): the server's token rows are `tests::music_spotify::phones` (the six answers, the
limit, a refresh slower than the budget kept, the policy and the blocklist following the binding, `/dns?device=`'s
marks); `spotify::tests` adds `hosts_match_the_contract` (both app copies too) and the limit. The launcher's
`DnsFilterEngineTest` also pins where the flag is set (the sync, before the refetch; the VPN's load from the cached
policy), and `MusicPlanTest`/`BridgeTest` the whole AIDL sequence. The music app's `SpotifyHostsTest` and `WebPolicyTest`
come with step 4's constant; step 3's music rows are `SpotifyRulesTest`'s library part (the grammar, `uriOf`, hidden
until step 5b, both views and the room's drop once playable, the order, never fetchable or kept - `downloadPlan` skips
Spotify, so `downloads` never lists one -, NY gated, the words), `ContractFilesTest`, `BridgeTest` and `ReportTest`.

Added by the design QA fix round (each in its step's tests):
- **Server**: `interval(link, ..)` per kind; `restamp_versions` restamps a Spotify listing; `SweepView::gated`/`phone_count`
  for an unbound phone and for no app; `DevicePolicy.music_spotify_account` read by `policy_music`;
  `compute_dns_filter_version` changes on bind and on unbind; `default_kind_for` (a show to Podkast); short links followed
  hop by hop (`follow: false`, `SourceResponse.location`, at most 3, hosts checked); `Reach::Any` only with the loopback
  override; the callback answers 303 to `/music/spotify?login=...` and needs the `spotify_login` cookie; the redirect
  suggestion is https only for an https request; a `QUOTA_EXCEEDED` stops Spotify checks for 60 minutes; the artist
  option's listing and its fallback; the scope string without `user-read-playback-state`.
- **Music, pure**: `SpotifyTrackerTest` - autoplay after the window and after the queue paused and never saved, a
  foreign transfer paused, a relink matched by title and duration (and not by name alone), a confirmed `null` only after
  the 2 s look, three unplayable in a row stop, a skip past our uri is `unplayable`; `SpotifyApiTest` - a 403 is
  `refused`/`not_premium` and never an item error, 429 `QUOTA_EXCEEDED` is `quota`, the transfer's 204 awaited before the
  second play, the queue add's request; `TokenCacheTest` - the 10-minute prefetch while playing, the 2-minute retry;
  `SpotifyRulesTest` - `spotifyPlayability` never `GONE`, `contextItem`'s stable key, `SpotifyOutcome`'s file round trip.
- **Music, service and renderer**: §5.4's state table row by row (`SpotifyRendererTest`), §5.10's service tests,
  `RendererState.remote` in `callStep`, the loaded tile's state and the stored outcome on every Spotify tile
  (`LibraryViewTest`/`UiModelTest`), a context entry from the snapshot to a restored queue, the service worker client's
  policy, the host destroyed at a suspension.

## 11. Steps (each one implementer, one sitting, its tests, the suites green; branch `music-3-spotify`)

Suites: server `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets` (Rust 1.99, pinned); music `./gradlew
assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`; launcher `./gradlew assembleDebug
testDebugUnitTest` when it or a file its tests read changes. **No new dependency** on any side: the server has `sha2`,
`base64`, `form_urlencoded`, `rand_core` (through argon2) and reqwest; the phone uses the platform WebView, OkHttp and
`org.json` (no `androidx.webkit`). If one becomes necessary, its checksums go into `gradle/verification-metadata.xml`
in the same commit (root CLAUDE.md). Migration number: 0074 (0073 until the merge of main 571f86f, where design 30
took 0073), re-checked at the final rebase.

**Order** (QA M9): 1 -> 2a -> 2b -> 3 -> 4 -> 5a -> 5b -> 6 -> 7 -> 8 (added 2026-10-14), each after the one before
(step 4 reads the music copy of `spotify_hosts.json`, which step 3 adds). Step 3 lands with the **provisional** host
list (§6.2); the spike's DNS capture replaces the three copies in a one-line commit whenever the user has run it,
**before any stable tag** (not run by step 6). The Jelly Star run of item 10 (Doze with and without the wake lock, §5.7)
is owed before step 5b's acceptance closes.

1. **Server: the app, the accounts, the login, the token cache.** Files: `S/spotify.rs` (new: links - only the grammar,
   no listing yet -, PKCE, the authorize URL, `Accounts` with the pending logins, the per-account access lock and
   `quota_until`, the exchange and refresh with rotation, `api_get`, the error codes, the `Reach` rule with the loopback
   override), `S/music_secret.rs` (`seal_with`/`open_with` with an AAD; Storytel's path unchanged), `S/music_net.rs`
   (`Kind::Token`), `S/main.rs` (`AppState.spotify`; the routes `GET /music/spotify`, `POST /music/spotify/app`,
   `/app/clear`, `/login`, `/paste`, `/accounts/{id}/remove`, and `GET /music/spotify/callback` among the
   `public_routes`; the env variables and the loopback-only base overrides), `S/handlers/music_spotify.rs` (new: the
   callback's 303 and the `spotify_login` cookie, §2.3), `templates/music_spotify.html` (new: the setup text with the
   https-only redirect suggestion, the app card, the accounts card - no add form yet), `templates/music.html` (a link
   to the Spotify page), `migrations/0074_music_spotify.sql` (0073 when written), `server/testdata/spotify/` (the
   accounts fixtures), `S/tests/music_spotify.rs` (+ `tests/mod.rs`). Tests: §10's server-pure and routes rows for the app, the login, the
   tokens. Acceptance: the server suite green; the Spotify page at 360 px (no horizontal scroll; a 400 by its field;
   saves never jump to the top); "not configured" changes nothing else (the library snapshot unchanged). **Not**:
   entries, the token endpoint, any device-facing change. Owed [device check]: QA checklist items 1-2.
2. **a. Server: the Spotify listing.** Files: `S/spotify.rs` (the listing parsers over `items[].item`, the readers and
   their order, the modes, the artist option behind `SPOTIFY_ARTIST_ALBUMS`, short-link resolution one hop at a
   time), `S/music_net.rs` (**`SourceRequest.follow: bool`**, default true, and **`SourceResponse.location`**, M8),
   `S/music.rs` (`Link::Spotify`, `entry_link`, `parse_link`'s pointer to the Spotify page, `default_kind_for`),
   `S/music_sweep.rs` (`schedule` and `check_entry` take `'spotify'`, **`interval(link, ...)`** with the kind,
   `spotify_check` with the snapshot gate, the modes, `quota_until`, `restamp_versions` with `'spotify'`,
   `device_listing`'s `IN`, `flag_reports` skipping Spotify, `art_candidate` and the per-pass URL memo),
   `server/testdata/spotify/` (the Web API fixtures), `S/tests/music_spotify.rs` (the sweep rows). Tests: §10's sweep
   rows. Acceptance: the server suite green; a check over CannedFetch lists the fixture playlist, a 403 playlist goes to
   context mode and back, a restore restamps a Spotify listing. **Not**: the PWA's add form, the library gate.
   **b. Server: entries, the PWA, the binding.** Files: `S/handlers/music_spotify.rs` (`POST /music/spotify/add` with
   the add check and ticks), `S/handlers/music.rs` (`SweepView::card`, **`SweepView::gated` and `phone_count`**,
   `check_now`, `/music/cards`, `save_offline` hiding Spotify, the entry page without offline and - in context mode -
   order, the device card's select at `POST /devices/{id}/music/spotify` with its lines, the DNS note and the
   mobile-data text), `S/models.rs` (**`DevicePolicy.music_spotify_account`**), `S/music.rs` (`LIBRARY_ENTRIES`' `IN`
   and `spotify_gate!()`, `cover_in_library`, `art_in_library`, `kind_label`, `LibEntry.spotify`, `item_error_code`
   `unplayable`, `sanitize_music_state`'s `spotify` words), `S/music_import.rs` (§3.2's import), templates
   (`music_spotify.html`'s add form, `partials/music_card.html`, `music_entry.html`, `device_detail.html`). **The library
   snapshot is not regenerated here** (step 3's contract commit does it). Tests: the routes for add, ticks, binding,
   cards, gate, import. Acceptance: the server suite green; Check now on a Spotify card lists the playlist; the PWA
   pages at 360 px. Owed [device check]: QA checklist item 3.
3. **The contract commit: the token endpoint, the launcher, the music app's parsing** (§8, one commit, all three
   suites). Files: server `S/handlers/music_api.rs` (`POST /api/devices/music/spotify/token`, its limit),
   `S/device_routes.rs`, `S/handlers/device_api.rs` (`music.spotify` in the policy through `policy_music`; the delivered
   blocklist's drop; **`compute_dns_filter_version` hashing the binding**), `S/spotify.rs` (`hosts` + its test),
   `S/handlers/dns_filter.rs` + `templates/dns_filter.html` (the marks, fix round L5), `templates/device_detail.html`
   (the DNS note owed by 2b), `server/testdata/{music_library,music_state,spotify_hosts}.json`; launcher `aidl/me/vibb/bridge/{IMusicBridge,
   ISpotifyTokenCallback}.aidl` (the import), `L/music/MusicServer.kt` (the token route), `L/music/MusicBridgeService.kt`
   (`getSpotifyToken`) + `L/music/SpotifyTokenCourier.kt` (new, pure), `L/music/SpotifyHosts.kt` (new),
   `L/server/DnsFilterEngine.kt` (`classify`'s gated exemption), `L/server/dto/PolicyResponse.kt` (`music.spotify`),
   `L/server/EnforcementPlan.kt` + `AppEnforcer.kt` (the WebView provider in `neverRestrict`), the test resources
   (`bridge_contract.json`, `spotify_hosts.json`) and tests; music `aidl/...` (the same two files), `M/model/Library.kt`
   (`Source.SPOTIFY`, `spotifyMode`, cache 0), `M/spotify/SpotifyRef.kt` (new), `M/logic/{PlayOrder,LibraryView,
   Offline,NewItems}.kt` (§4.2-§4.3: visible in both views, not fetchable), `M/logic/Spotify.kt` (new:
   `SPOTIFY_PLAYABLE`, which step 5b flips there), `M/sonos/PlayBody.kt` (`speakerDropReason` = `spotify`),
   `M/sync/DownloadPlan.kt` (Spotify skipped), the UI's `Kind` branches, `M/bridge/SpotifyBridgeToken.kt` (new;
   unused by playback yet), `M/logic/Report.kt` (`spotify` = `none`), strings, the test resources
   (`music_library.json`, `bridge_contract.json`, `spotify_hosts.json`); CI paths (§8 item 6). Until step 5b
   `Snapshot.visible` keeps Spotify items hidden behind one constant (`SPOTIFY_PLAYABLE = false`, flipped in step 5b),
   so a library with Spotify entries parses and shows nothing (26b step 1's way). Tests: §10's contract rows on every
   side. Acceptance: the three suites green; the shared files byte-identical. **Not**: the renderer, the WebView.
4. **Music: the Android-free core and the fake.** Files: `M/spotify/{SpotifyApi,SpotifyHttp,SdkState,SpotifyTracker,
   SpotifyHosts,WebPolicy,SpotifyEndpoints,TokenCache}.kt` (`SpotifyHosts.kt`: the constant checked against the music
   copy of `spotify_hosts.json` by `SpotifyHostsTest`, §8 item 5; `TokenCache`: §5.5's one prompt retry), `M/logic/Spotify.kt`
   (step 3 made it with `SPOTIFY_PLAYABLE`; step 4 adds `spotifyPlayability`, `contextItem`, `SpotifyOutcome`, the
   window, the words), `assets/spotify/{player.html,player.js}`, `src/test/resources/spotify/`
   (the play request and answers, `sdk_states/` incl. autoplay, transfer-in and relink sequences),
   `scripts/fake-spotify.py`, `.github/workflows/music.yml` (the selftest and the watched paths), root `CLAUDE.md`'s
   paths line. Tests: §10's music-pure rows (the tracker's ownership rule, 3 unplayable in a row, the token prefetch),
   `PlayerJsTest`, the fake's selftest. Acceptance: the music suite green, `python3 -I scripts/fake-spotify.py
   --selftest` OK. **Not**: anything Android-side.
5. **a. Music: the WebView host and the renderer.** Files: `M/spotify/{SpotifyWebHost,SpotifyRenderer,SpotifyLocks}.kt`
   (the host with its interceptor, service-worker client and debug proxy; the `SimpleBasePlayer` with §5.4's state
   table; focus, noisy, locks, the fade, the queue add past the window, the token prefetch), `M/playback/{Renderer,
   PlayerModel}.kt` (`RendererKind.SPOTIFY`/`onPhone`, the `RendererState` values and `remote`), `AndroidManifest.xml`
   (the two WebView meta-data), `src/debug/.../DebugSpotify.kt` (the switch, `DebugStartup`), strings (the states).
   Tests: `SpotifyRendererTest` against a scripted host (a start held 15 s shows playing intent with one `PUT`; a second
   tap sends nothing; steps coalesced; the window's end adds to the queue; `Ended`; focus loss and transient loss;
   noisy; the fade; a suspension destroys the host; a confirmed `null` -> `ELSEWHERE`; three unplayable -> stopped).
   Acceptance: the music suite green. **Not**: the service's sites (5b).
   **b. Music: the service, the UI, the report, §6l.** Files: `M/playback/{PlaybackService,PlayerHub}.kt` (§5.10's
   table, every row: `playable`, `playableFrom`, `start`, `goTo`, `onItemChanged`, `onError`, `mediaItem`,
   `wakeModeFor`, `onPlaybackResumption`, `onDestroy`, the room looks, `comeHome`, `callStep`, the renderer choice and
   `standDown`), `M/data/{Snapshot,Store}.kt` (`spotify_state.json`; the context item from step 6 comes later),
   `M/ui/{MusicViewModel,UiModel,LibraryScreen,PlayerScreen,ListScreens}.kt` (the words, the quiet line, the
   "Spotify" line, the loaded tile's state, the stored outcome on every Spotify tile), `M/logic/Spotify.kt`
   (`SPOTIFY_PLAYABLE = true`), `M/logic/Report.kt` + `M/sync/Sync.kt` (the `spotify` outcome from the file,
   `unplayable`), `docs/testing/emulator.md` §6l. Tests: §5.10's service tests, `GuardsTest` (the site list; a Spotify
   MediaItem never reaches `ItemMediaSourceFactory`), `SourceScanTest`, `ReportTest`, `LibraryViewTest`. Acceptance: the
   music suite green, the fake's selftest; **§6l part A runs end to end** on the emulator (the step's status lists each
   line); part B with the user's account if they run it. Owed [device check]: QA checklist items 4-17.
6. **Music: context mode, and the host list.** Files: `M/data/Snapshot.kt` (the synthetic `context` item from
   `contextItem`), `M/logic/LibraryView.kt` (the hint, no "N av M"), `M/ui/{UiModel,ListScreens}.kt` (the one row, the
   live title), `M/spotify/{SpotifyRenderer,SpotifyTracker}.kt` (the context start with its offset, the SDK's
   next/previous, `replaceItem` per track, context ownership), `M/playback/PlaybackService.kt` (the context item's save
   with no duration, `Ended`; as built also the steps to the web player, the prune of stale rows and
   `M/playback/RendererRules.kt`'s `keepsDuration`/`stepsOnWebPlayer`), `M/data/Store.kt` (`spotify_context.json`), the
   state recorder (`M/spotify/SpotifyRecording.kt`, the renderer's `recordState`/`record`/`contextTrack`/`storeContextTrack`
   ports, `src/debug/.../DebugSpotify.kt`), the fake's context answers; and, if the user has run the capture and it
   hasn't landed yet, the three `spotify_hosts.json` copies with their constants (it hadn't: they stay provisional). Tests: `SpotifyTrackerTest`'s context
   sequences, `SpotifyRendererTest`, a snapshot test (a context entry shows as a tile; a restart restores it paused at
   the live track). Acceptance: the suites; §6l's artist and 403-playlist lines. Owed [device check]: a real artist, a
   playlist the kid's account doesn't own or collaborate on (the parent's own, say); QA checklist item 18 (the artist option's dev-mode check).
7. **Docs and release.** `music/CLAUDE.md` (the `spotify/` package, the renderer, the WebView rules, the token, the
   secrets rule, the debug switch, the fake), `server/CLAUDE.md` (the Spotify section: app, accounts, PKCE, the
   callback's exemption, the token endpoint, the listing modes, the gate, 0074), `launcher/CLAUDE.md` (the token
   courier, the gated DNS exemption, the WebView provider), root `CLAUDE.md` (`scripts/fake-spotify.py`, the CI paths,
   `spotify_hosts.json` among the shared files), `server/DEPLOY.md` is the main session's - a note for it (the two env
   variables, and that a Spotify login needs the PWA on https or the paste), `NOTICE.md` (vibb's `spotify.py` link
   grammar and per-context bookmark rule, ported, MIT), design 26's status, `PLAN.md`. Then the Release below.
8. **No Spotify word in the kid's app; one child account per phone** (added after step 7 by the user's decisions of
   2026-10-14; "Step 8 implementation status" below). Music: the strings (`kind_spotify` gone, `kind_music` new, the
   context hint and the seven web player lines without "Spotify"), `M/logic/LibraryView.kt` (`kindOf`: a show or episode
   `PODCAST`, the rest `MUSIC`; no `Kind.SPOTIFY`, no `Hint.Started.episodes`), `M/ui/{LibraryScreen,
   PlayerScreen,ListScreens}.kt` (no source line under the title, none in the list's summary place), the tests
   (`FitTest`'s measured lines, `SourceScanTest`'s "no kid-facing Spotify", `SpotifyRulesTest`, `LibraryViewTest`,
   `UiModelTest`, `GuardsTest`). Server: copy only - the Spotify page's intro, setup steps 3 and 6 and login hint, the
   context notice (card and add), the device card's binding hint - and the tests that read them; no code change to the
   account machinery. Docs: this design (§0.2 items 1, 4 and 7, §0.3, §0.4, §2.1-§2.5, §3.1-§3.6, §4.3, §5.8, §5.9,
   §7.1, §7.3, §12, §13, §14, the QA checklist, the Release), `docs/testing/emulator.md` §6l, `music/CLAUDE.md`,
   `server/CLAUDE.md`, design 26, `PLAN.md`. No contract file, `launcher/` untouched.
9. **Later, optional: Spotify on a Sonos room** (not in the first release; step 8 until 2026-10-14). vibb's
   `spotify_sharelink`: the speaker plays the link through **the household's own Spotify account linked in the Sonos
   app** (no SDK, no Web API token, no dev-mode limit on the speaker); the queue is ours (vibb's v2: "vibb owns the
   spotify queue - the Sonos merely holds it"). Port: SoCo's `ShareLinkPlugin` (MIT; `AddURIToQueue` with the share
   URI's magic prefix and the service's `SA_RINCON..._X_#Svc...-0-Token` description, then the queue index and the
   seek), `daemon.py`'s start (the context bookmark's index and position), `sonosd.py`'s `spotify_sharelink`
   classification and `tests/sonos_spotify_queue.py` (the two field bugs: a transfer restarting from the top; the card
   fed from the speaker's DIDL instead of our list). `speakerDropReason` lets a list-mode Spotify item through when the
   room's household has Spotify. Acceptance: the ported test, the fake speaker learning the ShareLink action, and a
   device check on real speakers (a playlist from the bookmark, steps, phone -> room and back at the same track and
   place). Its own architect pass first: the Sonos household's Spotify account and the `sn` service number are not known
   yet.

Shared contracts: only step 3 changes shared files (§8), plus the host list's later one-line update (the three
copies, byte-identical, in one commit). Each step is one sitting: 1 (one new module and its PWA page), 2a (the
listing machinery), 2b (wiring through the PWA and the gates), 3 (small changes across three trees in one commit), 4
(pure Kotlin and the fake), 5a (the Android renderer against a scripted host), 5b (the service and the UI), 6 (context
mode), 7 (docs), 8 (the kid's words and the account copy).

## 12. Deviations from vibb (each with its reason)

| vibb | Here | Why |
|---|---|---|
| go-librespot plays on the box (zeroconf login, the box's own account) | the Web Playback SDK in a headless WebView, the kid's account through a server-minted token | go-librespot can't play for new accounts; the route the user chose after the spike |
| the listing from go-librespot's own session (`context_tracks`, no Web API) | the server's Web API listing with a reader account | the phone can't run librespot; dev mode lists playlists only to owner/collaborators |
| play = the context (`/player/play {uri, skip_to_uri}` + seek), Spotify's order | list mode plays our list by track URIs (a window), our order setting, our steps | the kid's list and the player match exactly; the order setting works as for every entry; no playlist access needed to play |
| artists play with resume (the bookmark's `skip_to_uri`) | by default an artist context has no offset: it starts where Spotify starts; with the option (§3.3) an artist is listed as its albums' tracks and resumes like an album | no `offset` for artist contexts [verified 2026-10-13]; the option waits for a dev-mode check |
| a recommended track after a context (Spotify's autoplay) plays on the box | a track that isn't ours is paused at once, never saved | the parent-picked library; "nothing starts by itself" (§5.4) |
| one bookmark per context (track + position) | phase 1's positions per track plus the entry's bookmark | the phone's one resume model; a re-tapped track resumes at its own place (within 20 s of the end it counts as done) |
| shuffle via `shuffle_context` | none (decided) | the phone's rule: `canShuffle` is own files without resume (`logic/Queue.kt:18`), and every Spotify entry is resume on; a music behaviour question decided by the existing phone rule, not asked |

| the precache (`/cache/download`, encrypted audio on disk) | nothing kept | the SDK has no offline; the Terms allow only temporary caching |
| `spotify_user` sections (follow a profile) | refused | `GET /users/{id}/playlists` is removed in dev mode |
| audiobooks, Liked Songs | refused with a sentence | no documented SDK path in dev mode [unverified]; Liked Songs has no share link |
| a phone streaming its own music through the box keeps its bookmark (`play_origin`) | a state we didn't command is paused at once and never saved | a kid's phone plays only what the kid picked ("nothing starts by itself") |
| the Pi's PWA logs Spotify in by the Spotify app's device picker (zeroconf) | the parent's browser, PKCE, the server keeps the refresh token | no zeroconf on the phone; no password or WebView on the kid's phone |
| Spotify on Sonos via ShareLink (`spotify_sharelink`) | later step 9 | the first release is the phone |
| the box's screen names Spotify ("Spotify needs internet", `pi/ui.py:3595`; a "Spotify cache" row) | the kid's screens never name a source: "Trenger internett", the kind "Musikk"/"Podkast" (§4.3, §5.8) | the user's decision of 2026-10-14 (step 8): a Spotify entry is a tile like any other; the attribution is the PWA's (§7.3) |
| one Spotify account on the box (the household's, zeroconf) | one account per phone, the child's own; the parent's never logged in, the parent pastes links (§0.2 item 1) | the user's answer of 2026-10-14 (step 8); one account plays on one device at a time |

## 13. What this design does NOT do
- **No offline and no downloads.** Not in the app, not via the WebView cache (~15 MB, not offline [spike 10b]). The tile
  says "Trenger internett" offline.
- **No Spotify app, no App Remote, no engine-app state** in the launcher (option A of `vibb-spotify-options.md` is
  replaced by the user's route decision).
- **No Spotify on Sonos** in the first release (step 9, later).
- **No search, browse or recommendations** on the phone; the parent adds links in the PWA only.
- **No playlist editing** from the PWA (`vibb-spotify-options.md`'s "container playlists" idea) and no writes to
  Spotify at all (no saves, follows, library changes).
- **No Liked Songs, no audiobooks, no profile following, no editorial/algorithmic playlists** (dev mode refuses them).
- **No shuffle** for Spotify entries (phase 1's rule).
- **No position mirroring** to Spotify's cloud (the kid's other Spotify apps don't see Vibb's place; same as Storytel).
- **No client ID, no client secret shipped**, no extended-quota application, no multi-tenant handling.
- **No login on the phone**, no password anywhere in the system.
- **No pinning of the SDK script** (§7.2) and no copy of it in the repo or the APK.
- **No Spotify logo asset, and no Spotify word on the kid's screens** (step 8; the attribution is the PWA's, §7.3).
- **No login of the parent's own Spotify account** (step 8): the children's accounts only, one per phone; nothing in
  the code stops it, the setup text says it.
- **No per-phone account limit enforcement** beyond the warning (Spotify's dev-mode list is the limit).

## 14. Open points

**The user's (the design runs on the assumption in brackets; overturn it to change the design):**
1. **Links the family's accounts can't list** (QA question 1) - **closed 2026-10-14** with the user's account answer
   (item 2): the parent pastes links and only the children's accounts are logged in, so (a) stands - a playlist no
   child's account owns or collaborates on, and an artist, play as a Spotify context (Spotify's order, no track list;
   an artist without resume; a playlist resumes at its last track while it is still in it); the setup text tells the
   parent how to get a track list (the playlist made in the child's account, or a collaborative one). (c) stays an
   opt-in behind `SPOTIFY_ARTIST_ALBUMS` and QA checklist item 18; (b) was not asked for.
2. **Accounts** (QA question 2) - **closed 2026-10-14**, the user: "Jeg er enig i at barna skal ha en konto hver (1 pr
   tlf), men ser ingen grunn til at min konto skal involveres. Jeg fikser vel selv å paste in en linke fra alt spotify
   innhold de skal ha tilgang til." One Premium Family member account per child and phone (not a mobile-only plan),
   the parent's own account never logged in (§0.2 items 1 and 4, step 8). Still open within it: assumption 8 - the
   setup text may tell the parent to switch Autoplay off on the children's accounts if QA checklist item 17 shows the
   web player autoplaying (the guard in code pauses anything outside our list either way).
3. **The remaining spike runs** (`spotify-spike.md` §7b "Still to run", the user's to run): the DNS capture (it replaces
   the provisional host list, before any stable tag), and the Jelly Star under real Doze with and without a wake lock,
   now for 2.5 hours so the token refreshes ahead of expiry at least twice (QA checklist item 6, M2).

**Settled (from the docs, the code, the brief's rules; listed so nothing is lost):**
4. Shuffle: off for Spotify - the phone's rule (`canShuffle`), a §12 deviation, not a question (QA L14).
5. Attribution: **none on the kid's screens** (step 8, the user's decision of 2026-10-14: "vi ikke trenger noe spor av
   spotify noe sted i appen") - no logo, no word; the attribution and the link-back are the PWA's only (§7.3). This is a
   **second departure** from Spotify's Developer Policy - its attribution clause (II) - beside the "targeted to
   children" one (III) the user accepted on 2026-10-06 on a design that still carried the word; the sanction is the
   same (Spotify may switch the family's app off; the tiles then say "Spør en voksen"). (Through step 7 it was the
   word "Spotify" on the tiles and the player.)
6. The wake lock while playing: kept; dropped only if the Jelly Star's run A shows an hour of stream and page without it.
   The Wi-Fi lock stays but is inactive with the screen off on API 34+ (every Wi-Fi lock is LOW_LATENCY there; step
   5a's fix round, L1 - SonosLocks' too): whether Wi-Fi power save stalls the stream with the screen off is what the
   run shows, not something the lock prevents.
7. The DNS exemption per phone (the stricter choice; FCM is global because every phone needs it), with the binding in
   `dns_filter_version` (M7).
8. The WebView's own audio focus (§5.6) and episodes in a `uris` list (§3.1): device checks with a default each
   (step 5a's focus branch; `SHOWS_PLAYABLE`).
9. The SDK on a phone: Spotify now lists mobile browsers as supported (Android and iOS); a WebView isn't named - the
   spike is the evidence; if Spotify breaks it, the tiles say "Virker ikke på telefonen" and the rest of
   Vibb is untouched.
10. Spotify's 13+ age rule (spike §8 Q6): the family's matter, outside the code.
11. Spotify on Sonos (step 9) and the household account it would use: a later architect pass.
12. For the main session: the migration number (0073 at `437eb84`, 0074 since the merge of main 571f86f) is re-checked
    at the final rebase; `pre_migration_backup` comparing the set of applied versions would end the out-of-order
    hazard for good (26b open point 6).

## QA checklist for the user (Jelly Star, a real Premium Family member account; after the step that owes each)

1. **The app** (step 1; step 8's wording): register a dev-mode app as the Spotify page says (your Premium account owns
   the app, the redirect URI shown, Web API + Web Playback SDK, **each child's account** under User Management - one
   per phone, never yours); save the client ID; the page shows its last four characters and the redirect URI.
2. **Logins** (step 1; step 8): in a private window log in as each child (https PWA, or the paste flow on http) -
   never your own account; each row shows "log in again before ..."; an account not in User Management is refused with
   the sentence; Remove deletes a row.
3. **Links** (step 2b; step 8): a playlist the child's account owns, or one of yours made collaborative and joined by
   the child (list mode: tracks, art arriving over a day, the entry page's "read as <child>"), a playlist of yours that
   isn't (context mode, the entry page's notice saying how to get the track list), an album, a single track, an artist (context
   mode), a playlist made by Spotify (refused at add), a `spotify.link` short link, a show (listed newest first), with
   phone ticks on the add form.
4. **The phone plays** (step 5b): bind the phone ("Spotify plays as: <kid>"); the tiles appear; a cover tap plays within a
   few seconds; pause, resume, seek, next, previous, a pick from the list, the carousel swipe; "Spor N av M"; the title
   and picture in the player, the notification, Home's card and the lock's tile; **no "Spotify" anywhere** on the
   kid's screens (step 8): "Musikk"/"Podkast" on the covers, no source line under the title or in "Liste".
5. **Resume**: play to the middle of track 4, pause, kill the app (`adb shell am force-stop me.vibb.music`), reopen:
   paused at track 4; play: track 4 at the place, **no audible second from 0:00**.
6. **Screen off for 2.5 hours** on battery (real Doze), a long playlist playing: the music plays on throughout; the
   debug log shows the token refreshed ahead of expiry at least twice (each line's time and outcome; QA M2), and the
   page's state posts keep coming; `adb shell dumpsys power | grep -i vibb` shows our wake lock only while playing;
   afterwards the bookmark is within 15 s of where it stopped. Battery % before and after. **Listen for audio gaps and
   look for gaps in the state posts** (a "Trenger internett" after 30 s without one): the Wi-Fi lock does nothing with
   the screen off on this Android (L1), so this run is what shows whether Wi-Fi power save stalls the stream.
7. **Mobile data**: Wi-Fi off, the parent's **streaming** switch off (design 30; the downloads switch on, to show it doesn't
   count) -> "Trenger Wi-Fi", nothing streams; streaming switch on
   -> it plays over mobile data; note `adb shell dumpsys netstats` (or Android's data usage) for an hour of music.
8. **Offline** (airplane mode): "Trenger internett"; NRK/own files still play.
9. **Bedtime**: a time rule starts while Spotify plays -> it stops within a couple of seconds and nothing restarts; the
   headset's play button does nothing while suspended.
10. **Calls and focus**: a phone call and an Element X call pause Spotify and resume it after; a notification sound
    ducks or briefly pauses; unplugging a headset pauses; the volume cap holds for Spotify (`adb shell dumpsys audio |
    grep -A4 STREAM_MUSIC`).
11. **Elsewhere**: start the kid's account in Spotify on another device while the phone plays -> the phone shows paused,
    "Spilles et annet sted"; playing on the phone takes it back. Connect a Spotify app to "Vibb" from another
    device while the phone is idle -> nothing plays on the phone.
12. **Errors**: a track the kid's explicit filter blocks is skipped with "Kan ikke spilles" (the entry page's phone line lists it);
    the kid's account without Premium (if one is at hand) -> "Spør en voksen", the phone's Music page says "No Spotify Premium" and its summary "Spotify can't play on this
    phone".
13. **Six months**: the PWA's warning 14 days before; after `invalid_grant` (simulate by removing the app's access at
    spotify.com/account/apps) the tiles say "Spør en voksen", the phone's Music page and its summary say "Spotify login needs renewing", a new login heals it
    without touching the phone.
14. **No secrets**: `adb logcat -d | grep -E 'Bearer |BQ[A-Za-z0-9_-]{20}|AQ[A-Za-z0-9_-]{20}|code='` and the server's
    `journalctl` (also with `RUST_LOG=debug`) find nothing after a day; `music_state` holds none.
15. **The DNS filter on** with the family's categories: playback works; the DNS log shows no blocked Spotify host, and
    no host outside the list that a Spotify host redirected to (the WebView's interceptor never sees a redirect's
    target; step 5a's fix round, L2).
16. **Design 28**: Home's card and the lock's tile show while Spotify plays and for 10 minutes after a pause, then hide;
    during a cold start (the first play after a while) the play button shows "pause" at once, and a second tap starts
    nothing twice.
17. **Autoplay** (QA H4): with Autoplay **on** in the kid's Spotify settings, play a 3-track album to its end - nothing
    plays after it (the phone pauses whatever Spotify starts; the debug log names a "not ours" pause if it tried). If a
    "not ours" pause shows, switch Autoplay off in each child's Spotify settings (assumption 8) and note it in the setup
    text. Also: from another device, transfer the kid's playback to "Vibb" while the phone is idle - nothing sounds
    beyond a moment, the phone shows paused.
18. **The artist option's dev-mode check** (assumption 7, QA L12): start the server with `SPOTIFY_ARTIST_ALBUMS=1`, add
    an artist with more than 50 albums and singles, Check Now: its entry page lists its tracks ("N tracks from M albums"), or
    says "Spotify refused the artist's albums - played as Spotify picks" (context mode). Listed with both pages read =
    the albums endpoint works for a dev-mode app, and the user may keep the setting on; refused = artists stay context
    mode. (No token leaves the server for this check.)
19. **A refused play** (QA M5): remove the kid's account from the app's User Management, play - the tile says "Spør en
    voksen", the phone's Music page says "Spotify refused the account" (and its summary "Spotify can't play on this
    phone"), and no track shows "Kan ikke spilles"; put it back
    and play again.
20. **Our queue add left over** (qa-26c-step4-code H1): a playlist over 50 tracks, let track 50 start (the add of 51),
    pick track 3, let it play out: the next track is 4 (or a short pause and 4), no "Kan ikke spilles" anywhere; the
    debug log shows whether Spotify played the leftover add.
21. **Shuffle and repeat** (qa-26c-step4-code M1): shuffle on and repeat on (one track) in the kid's Spotify app, then
    play a list on the phone - it plays in order, once; the kid's Spotify app shows both off afterwards.


## Release

Final (step 7, 2026-10-13; it replaces the drafts written with the design and at the merge of main `571f86f`).

**The branch**: `music-3-spotify` holds §11 steps 1-7, each code step with QA's code review and its fix round, step 8
(2026-10-14, "Step 8 implementation status" below, with its QA and fix round `038822a`), and three merges of `main` -
at `a5d30b9` (merge commit `5d6bd5b`), at `571f86f` (merge commit `e4d4feb`, with its own code QA and fix round) and at
`5584772` (merge commit `a023e4d`, design 31 steps 4-15, 2026-10-14, "Merge of main (5584772)" below, with its code QA
`8ba881b` and fix round `102745b`, which rebuilt `/music/spotify` in design 31's layout). The final head is the
handover commit on top of `102745b`. `origin/main` is `5584772`, an ancestor: nothing on `main` is missing here.
**The merge into `main` and the releases follow `docs/design/26c-handover.md`**, a self-contained handover for the
agent doing them (what the branch is and changes, how it was verified, the exact procedure, the decisions taken and
the open points). Last full runs (the server on `102745b`; the launcher and music on `a023e4d`, untouched since): server `cargo test` 934
passed, `cargo fmt --check` clean, clippy's
12 known warnings, `node --test jstest/*.test.js` 25 passed; launcher `assembleDebug testDebugUnitTest` 1146 tests (its
`assembleRelease` needs `app/libs/tsnet.aar`, which only CI builds); music `assembleDebug assembleRelease
testDebugUnitTest lintDebug -PwarningsAsErrors=true` 845 JVM tests; the four fakes' `--selftest`s OK. CI last ran on
`a023e4d` (all green); `8ba881b`, `102745b` and the handover were not pushed. Step 7 changed docs only; step 8 changed
the music app's strings and words, the server's copy and one fallback name, and their tests (`launcher/` untouched);
the merges moved the Spotify controls onto design 31's pages.

**Facts on 2026-10-13** (tags from `git ls-remote`, releases and runs from the GitHub REST API):
- `server/Cargo.toml` says **0.24.0** here and on `main`. `server-v0.24.0` **is tagged** - on `6ae54e8`, a `main`
  commit with 0065-0072, so Storytel (26b) is 0.24.0, not the 0.25.0 26b's Release planned - but **its release run
  failed** at `server-release-sign` ("Sign the tarball and update.sh") and **no GitHub release exists for it**.
  The cause, from the run's log (step 7's QA, run 38404900686): the `release` environment **admitted** the tag (the
  `server-v*` rule is proven), the `SERVER_MINISIGN_KEY` secret **is set** (it passed the workflow's empty-secret
  check), but minisign can't read it - `Error while loading the secret key file` (exit 2): not the whole two-line key
  file (`untrusted comment:` line first), or a key made without `-W`. **The fix (main's, step 0 below)**: re-enter
  the secret as the whole content of the `handy-server.key` that `minisign -G -W` wrote - the pair whose public key is
  pinned in `server/deploy/*.sh` (DEPLOY.md, "Release signing", step 2) - then re-run the failed job (or delete and
  re-push the tag). This repository has **no `server-v*` GitHub release at all** (the API lists
  only `music-v0.0.2`, `launcher-v0.2.7` and `launcher-v0.25.0-rc.1`; `server-v0.19.0`'s run of 2026-10-07 failed
  too). `main` after `6ae54e8` carries design 30's **0073** with `Cargo.toml` still 0.24.0.
- Launcher tags: `launcher-v0.25.0-rc.1` (versionCode 24999, 2026-10-07) and `launcher-v0.2.7` (2007, GitHub's
  "latest", the provisioning QR's APK) - 26b's open point 3 (0.2.x is below the RC).
- Music tags: `music-v0.0.1`, `music-v0.0.2` (1, 2). 26b's `music-v0.3.0-rc.1` (2999) was never tagged.

**The merge** (the user's): `git merge --no-ff music-3-spotify` into `main` - `main` is an ancestor, so git would
fast-forward; `--no-ff` gives the one merge commit the tags below name, like main's earlier design merges. If `main`
moves on first, merge it into the branch (with its code QA) as at `571f86f`; re-check the migration number then (0074
must stay the highest - open point 7). CI on the merge commit runs all three build jobs.

**Tags and versions** (versionCode `X*1_000_000 + Y*1_000 + Z`, an RC minus 1: root `CLAUDE.md`, checked in
`launcher.yml`/`music.yml`):
- **Server: Spotify is 0.26.0.** As in 26b's Release, the bump is **one commit on `main` after the merge**, not on
  this branch: `server/Cargo.toml`'s `version` and the `kid_phone_server` entry in `Cargo.lock` to 0.26.0, then
  `server-v0.26.0` on that commit (the tag must equal `Cargo.toml`). **0.25.0 is design 30's** (0073 without 0074): a
  bump and `server-v0.25.0` on `main` **before** the merge, if `main` releases design 30's server on its own. If it
  doesn't, 0.25.0 is never tagged and 0.26.0 carries 0073 and 0074 in one release (allowed: one start applies both,
  in order, after one pre-migration backup). Either way Spotify's version is 0.26.0 - never 0.25.0, so no version
  names two migration sets, and the update check (`is_newer(latest, APP_VERSION)`) offers 0.26.0 to every 0.24/0.25
  server. 0.24.0's own release (above) is main's and comes first.
- **Launcher: `launcher-v0.26.0`** (versionCode 26000) on the merge commit (or the host-list commit, as for the music
  RC below) - above `launcher-v0.25.0-rc.1` (24999),
  so a phone that ever had the RC takes it, and as a stable tag it becomes "latest", which also ends the 0.2.x/RC
  split of 26b's open point 3 (every later launcher tag stays above 26000). It carries the token courier, the gated DNS
  exemption, the WebView provider in `neverRestrict` and the policy key - and design 30's switches, since `571f86f`
  is merged in. If `main` tags a launcher of its own first, this one is the next minor above the highest
  `launcher-v*` then (`launcher-v0.27.0` after a `0.26.0`). Inert without Spotify - `music.spotify` is false until the
  parent binds a phone, and nothing asks for a token before - **except the WebView provider's place in
  `neverRestrict`, which holds on every phone** at every `apply()` (§6.3; harmless: a system package without a
  launcher icon, never Chrome at minSdk 34), the one enforcement change the moment this tag becomes "latest".
- **Music: `music-v0.4.0-rc.1`** (versionName `0.4.0-rc.1`, versionCode **3999** = `0*1_000_000 + 4*1_000 + 0 - 1`),
  a prerelease, never "latest", on the merge commit (or the host-list commit below, if that lands on `main` after the
  merge: `music.yml` refuses a tag whose commit isn't on `main`). It is above `music-v0.0.2` (2) and above 26b's
  untagged `music-v0.3.0-rc.1` (2999), should `main` still tag that first; it also carries Storytel, designs 29 and
  30. The stable **`music-v0.4.0`** (4000) after the owed checks below. Through the catalog (the music row's "include
  prereleases" for the RC).
- **The `release` environment** must allow `launcher-v*`, `music-v*` and `server-v*`, with `SERVER_MINISIGN_KEY` for
  the server - the rule is proven by the 0.24.0 run; **the server's signing secret isn't** (above).

**Release and deploy order** (the merge's code QA L2 and design 30's step 1 L4: no `server-v*` carrying design 30
before the `launcher-v*` and `music-v*` that read its two switches, else the device card's "Update both" names
updates that don't exist):
0. **Main's**: `server-v0.24.0` published - `SERVER_MINISIGN_KEY` re-entered as the whole two-line key file from
   `minisign -G -W` (the pinned pair), then its failed `server-release-sign` job re-run (above); it carries 0070-0072
   (design 27, 26a, 26b) and no 0073. And design 30's apps, if `main` releases them before the merge (then steps 1-2
   below are only Spotify's).
1. `launcher-v0.26.0` (it reads `stream_mobile_data`, `download_mobile_data` and `storage_limit_gb`, design 30
   step 2).
2. `music-v0.4.0-rc.1` through the catalog (it reads them, design 30 steps 3a/3b) - a prerelease, which the catalog
   offers only with the music row's "include prereleases" on; with it off, step 3 waits for a stable `music-v*`
   (design 30's L4 is about the phones having the app).
3. The first `server-v*` carrying design 30's 0073: `server-v0.25.0` (main's) - or none, see 4.
4. `server-v0.26.0` with Spotify's 0074: after 0073 or in the same release, **never before** (`pre_migration_backup`
   compares `MAX(version)` only: a 0073 reaching a server that ran 0074 is applied out of order with no backup -
   `server/CLAUDE.md` "Data model"). A backup before the update as always.

Spotify tolerates the apps first: an older server sends no `music.spotify` (false) and no Spotify entry, so the new
launcher and music app show no Spotify and break nothing until the server comes. The reverse (the server first: an
older music app drops Spotify entries with their ids kept; an older launcher ignores `music.spotify` and has no
`getSpotifyToken`, so the music app reads "Trenger internett") is never needed. After the server, nothing changes
until the parent registers an app on Music › From Spotify, logs an account in and binds a phone (the DNS note in
"DEPLOY.md note" below).

**Before any tag of this release** (the server has no RC form and the launcher's tag is a stable one - it becomes
"latest", the provisioning QR's APK -, and both carry the host list to every bound phone; "before any stable tag",
read literally):
- **The DNS capture** (§6.2, spike §7 item 10 step 7; §6l part B with the user's account, the emulator image of the
  spike): **one commit** of the list, the constants and the comment - the three byte-identical
  `spotify_hosts.json` copies (`server/testdata/`, both apps' `src/test/resources/`) with the captured hosts and a
  comment that drops "PROVISIONAL" and names the capture's date and source; the six constants (the server's
  `HOST_SUFFIXES`/`EXACT_HOSTS` in `spotify.rs`, the launcher's and the music app's `SPOTIFY_HOST_SUFFIXES`/
  `SPOTIFY_EXACT_HOSTS` in their `SpotifyHosts.kt`) and their doc comments' "provisional" - all three suites green -
  on this branch before the merge, or on `main` before the tags. If design 30's apps must go out before the capture, `main`
  tags them from a commit before the merge, never from this merge.
- **§6l part A** (A1 the whole chain, A2 the phone alone) on the user's emulator: §11's acceptance of steps 5b and 6,
  **never run** (no emulator, VM or account where they were built) - every line, including the streaming-switch line
  (`stream_mobile_data` off with `download_mobile_data` on: "Trenger Wi-Fi"), the context and recorder lines
  and the secrets greps.
- The `parent_pin` test green or re-run (open point 6): `server-v*` runs the tests before it builds.

**Owed before the stable `music-v0.4.0`** (none could run where this was built):
- **§6l part B** on the emulator with the kid's Premium account (the spike's recipe; the parent's is never logged in,
  step 8): the real PWA login (https or the paste), a playlist the kid's account owns (or the parent's, made
  collaborative and joined by the kid), play, pause, skip, resume after a restart; the protected-media origin line;
  the blocked-hosts counter at 0 and the parent's DNS log for hosts outside the list (redirect targets); no `csp`
  line; no token in logcat after a Wi-Fi drop; **the state recorder's real sequences** (an artist, a playlist the
  kid's account doesn't own or collaborate on, a context's end with Autoplay on and off, the `context` field around track changes, a paused
  `nextTrack`, an offset no longer in the playlist) turned into `sdk_states/*.json` for `SpotifyTrackerTest` (the
  main session's follow-up); and a cold start's `needToken` -> `vibb.token` -> `ready` order against the scripted
  page's (qa-26c-step5a-code, "Owed by step 5b").
- **The Jelly Star** (a real Premium Family member account): the QA checklist above, items 1-21 - in particular
  item 6, **2.5 hours screen off on battery in real Doze** (the spike's item 10 run with and without the wake lock,
  owed since 5b's acceptance: the token refreshed ahead of expiry at least twice, no audio or state-post gaps -
  the Wi-Fi lock is inactive with the screen off on API 34+), item 15 (the DNS filter on with the family's
  categories), item 17 (Autoplay after a list and after a context), item 18 (the artist option's dev-mode check),
  items 19-21 (a refused play, the leftover queue add, shuffle and repeat).
- **Device checks with a default in code**: the WebView's own audio focus (§5.6; the renderer keeps its request),
  episodes in a `uris` list (§3.1; **no switch is built** - if the SDK refuses them, the server's parser must refuse
  `show`/`episode`, a small code change), the protected-media origin, `getCurrentWebViewPackage` on the
  Jelly Star (§6.3), whether the player's account can open a playlist it doesn't own as a context (§5.9), what the
  real SDK posts at a context's end, whether `tailscale serve` sends `X-Forwarded-Proto` (§2.1; if not, the parent
  edits the redirect field).
- Phase 1's, 26a's, 26b's and design 30's own owed checks are theirs (their Releases); Spotify stands on them.

**Open points for the user** (§14 pruned to what still stands):
1. **Links the family's accounts can't list** (§14 item 1, closed 2026-10-14 with item 2's answer): an artist and a
   playlist no child's account owns or collaborates on play as a Spotify context (Spotify's order, no track list; an
   artist without resume). `SPOTIFY_ARTIST_ALBUMS=1` lists an artist as its albums' tracks once QA checklist item 18
   shows the endpoint paging for a dev-mode app; refusing such links at add was not asked for.
2. **Accounts** (§14 item 2, closed 2026-10-14): one Premium Family member account per child and phone (not a
   mobile-only plan), **the parent's own never logged in** - the parent pastes links (step 8); at most 5 per dev-mode
   app. Still open: Autoplay off on the children's accounts only if item 17 shows the web player autoplaying (the
   guard pauses it either way).
3. **The wake lock** (§14 item 6): kept; dropped only if the Doze run shows an hour of stream and page without it.
4. **Attribution** (closed 2026-10-14, step 8): none on the kid's screens - no logo, no word (the user's decision,
   a second deviation from the Developer Policy - II, attribution - beside III's accepted on 2026-10-06, with the same
   sanction: the family's app switched off, the tiles saying "Spør en voksen"; §7.3); the PWA carries it (the Spotify
   page, the cards, "Open in Spotify").
5. **Spotify on Sonos** (§11 step 9): a later architect pass (the household's Spotify account in the Sonos app, the
   `sn` service number).
6. **For the main session**: `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` is timing-dependent
   (red 4 of 4 on this tree and 2 of 2 on `571f86f` in the merge's code QA: its events are stamped `now + 1-2 s`;
   stamping them in the past would fix it); a red run stops a `server-v*` release.
7. **For the main session**: `pre_migration_backup` comparing the set of applied versions (not `MAX(version)`) would
   end the out-of-order hazard for good (§14 item 12, 26b open point 6); until then 0073 before (or with) 0074.
8. **Design 31 step 12 - settled at the merge of main `5584772`** ("From Spotify ›" is the Music tab's fifth Add row;
   the app and account cards stay on `/music/spotify`). The request as it stood: design 31 doesn't name Spotify - its
   step 12 gives the Music tab's Add four rows and its redirect list no `/music/spotify` entry, so built from design 31
   alone it would drop the "From Spotify ›" row (`templates/music.html`). When this branch merges, step 12 must take
   that row as a **fifth Add row** (`/music/spotify`, the value "No app" / "No login" / "Signed in"), and may move the
   app and account cards to `/music/settings` with a redirect from `/music/spotify` like its other anchors. **But
   `/music/spotify/callback` and `/music/spotify/done` stay where they are, for good**: the callback is the redirect URI
   every family registered in its Spotify dashboard (`spotify::CALLBACK_PATH`, the only https path `valid_redirect_uri`
   accepts, the `spotify_login` cookie's `Path`, `SPOTIFY_REDIRECT_URI` in `.env`); moving it breaks every family's next
   login until each parent edits the dashboard. The login-address rule stays too. (`server/CLAUDE.md` and `PLAN.md` say
   the same.)
9. **A later contract change**: an optional `{"rejected": true}` on the token route through a new appended bridge
   method would let the server refresh a token Spotify refused (§5.4's 401 row); the parent's card naming a context
   playlist the player's account can't open (§5.9) needs a report field.

### DEPLOY.md note (for the main session to paste into `server/DEPLOY.md`)

Its place: after "Vibb music: episode art (design 27, migration 0070)", before "Server 0.23.0 (design 22)" - with the
other music sections.

```markdown
## Vibb music: Spotify (server 0.26.0, design 26c, migration 0074)

Spotify is opt-in: nothing changes until a parent sets up a Spotify app on Music › From Spotify. The server ships no
Spotify client ID - each family registers its own (bring your own app, Spotify's Development mode).

- **The app**: the page walks through it - developer.spotify.com/dashboard with a **Premium** account, a new app (a name
  not starting with "Spot"), the redirect URI the page shows, APIs **Web API** and **Web Playback SDK**, each child's
  Spotify account under User Management (one per phone; **at most 5**), then the **Client ID** (never the secret: the
  server uses PKCE and stores no client secret) into the page. Or in `.env`:

  | `.env` setting | Default | What it does |
  |---|---|---|
  | `SPOTIFY_CLIENT_ID` | unset | The app's client ID (32 letters and digits). Wins over the page's, whose field is then read-only. A client ID is public; it is stored in the clear. |
  | `SPOTIFY_REDIRECT_URI` | unset | `https://<the PWA's host>/music/spotify/callback`, or exactly `http://127.0.0.1:43117/callback` (the paste, below). Wins over the page's. |
  | `SPOTIFY_ARTIST_ALBUMS` | unset (off) | `1` (or `true`) lists an artist as the tracks of its newest 20 albums and singles - with a track list, resume and the order setting - instead of playing it as a Spotify context in Spotify's order. Opt-in: turn it on once the design's checklist item 18 shows the albums endpoint works for your app; an artist Spotify refuses falls back to a context. |

  A value that doesn't fit is ignored with one warning in the log at start.
- **A Spotify login needs the PWA on https, or the paste.** Spotify accepts only https redirect URIs (or a loopback
  address). Behind `tailscale serve` or Caddy with https, the redirect URI is the PWA's own https address, and a login
  must be started **from that address** (the page refuses another address of the server with a link to the right one).
  The page suggests the https form only when the proxy says `X-Forwarded-Proto: https`; if it suggests the loopback form
  behind your https proxy, type the https form in. Without https (e.g. Docker on the LAN over http) use the loopback
  redirect: after Spotify's consent the browser shows "can't connect" with the code in its address bar - paste that
  address into the page. The callback answers without a session but stays behind the admin gate, so the browser must
  reach the PWA as usual (tailnet). Log the children in from a private window - one account per phone, the child's own;
  your own Spotify account is never logged in (you paste the links, and a playlist lists its tracks only when a child's
  account owns it or collaborates on it). A login lasts 6 months (Spotify's limit): the page and the phone's card warn
  14 days before.
- **Keys and backups**: each account's refresh token is sealed with the music key (`MUSIC_SECRET_KEY` or
  `data/keys/music-secret.key`), like the Storytel login. Backups hold the sealed tokens, never the key: a restore on
  another box, or a lost key, means logging the accounts in again.
- **What it fetches**: `accounts.spotify.com` and `api.spotify.com` (one request at a time through the music gate),
  Spotify's short links (`spotify.link`, `spotify.app.link`) when one is added, and covers and track pictures from Spotify's image hosts. Playlists
  and shows are checked at the sweep's interval (a playlist's tracks are read again only when it changed, or after a
  day), albums, tracks, episodes and artists daily. The quota is per developer account and shared with the phones'
  play calls: if Spotify says it is used up, the server pauses its Spotify reads for an hour.
- **The phones**: a phone gets Spotify entries only while its Music page (Devices › the phone › Music) says "Plays as
  <account>". For such a phone the Web Filter never blocks Spotify's hosts - a parent's own block of `spotify.com` or
  `scdn.co` is overridden on that phone for every app (the phone's Web Filter page says so, and marks the blocks).
- **Testing only, never on a real server**: `SPOTIFY_ACCOUNTS_BASE` and `SPOTIFY_API_BASE` point the server at
  `scripts/fake-spotify.py` and are honoured **only** as `http://127.0.0.1:<port>` or `http://[::1]:<port>` (anything
  else is ignored with a warning; while one is set Spotify requests may go to a private address, and the start logs a
  warning naming them). `INSECURE_COOKIES` also drops `Secure` from the login cookie (the emulator loop over http).
- **Order**: the launcher and the music app that read design 30's two switches first, then the server with design
  30's migration 0073 (0.25.0), then this one with 0074 - or 0073 and 0074 in one release, **never 0074 before
  0073** (the pre-migration backup compares the highest version only). A dev database that ran the Spotify branch
  before its merge of main holds its old 73 and stops at `VersionMismatch(73)`: start it on a fresh `data/`.
- **Smoke check**: `journalctl -u kid-phone-server -b | grep -i spotify` shows no "ignored" warning (and no override
  line on a real server); the Music page's "From Spotify ›" row says "No app", then "Signed in" after a login.
- Rolling back means restoring the `update.sh` backup: an older server refuses to start on a database with
  migration 0074.
```

## Design QA fix round (2026-10-13)

The design QA (`docs/design/qa-26c-design.md`, `8b6a3b4`: 4 High, 9 Medium, 14 Low, "buildable after a fix round") is
taken in full; each finding was checked against the code it cites on `437eb84`. The QA's two questions are not
answered yet: the design runs on the main session's assumptions, written into §0.2 (items 1, 7, 8) and §14 (items 1-2)
so the user can overturn them.

- **H1** (Spotify items unplayable in the service): §5.10's table now names every site - `playable`, `playableFrom`,
  `start`, `goTo`, `onItemChanged`, `onError`, `mediaItem`, `wakeModeFor`, `onDestroy`, the room looks, `comeHome`,
  `callStep` - with `spotifyPlayability` (never `GONE`), the Spotify states instead of `NOT_DOWNLOADED`, `unplayable`
  recorded, the uri from `uriOf` and no `ItemTag`; tests in step 5b.
- **H2** (playback resumption into ExoPlayer): `onPlaybackResumption` gives a stored Spotify queue the room's shape -
  attach, `resumeLast()`, fail the future (§5.10); `GuardsTest` and a service test.
- **H3** (context mode never reaches the screen): the synthetic item comes from the snapshot (`contextItem`, stable key
  `context`), its row holds the live track's position with no duration, the live track lives in
  `spotify_context.json` (§5.9); step 6 adds `Snapshot.kt`, `LibraryView.kt`, `UiModel.kt` and the tests.
- **H4** (the tracker adopts autoplay): ownership is our uri or the expected next item by uri, `linked` or title +
  duration within 2 s; anything else is paused and never saved; after `Ended` every playing state is paused (§5.4);
  tracker sequences, fake options `--autoplay`/`--transfer-in`, QA checklist item 17. **Changed from the QA's fix:** the
  next window is not re-sent in the last 15 s of a track (a `PUT` then restarts the playing track); past the window the
  next item is added with Add Item to Playback Queue one track ahead, and a failed add costs one gap and one `PUT`.
- **M1** (Media3 state during a start and a gap): §5.4's state table (BUFFERING with play-when-ready during a start,
  held through a 30 s gap, `play()` mid-start a no-op); design 28's bullet points to it.
- **M2** (the hourly token at expiry in Doze): `TokenCache` refreshes at 10 minutes left while playing, retries every
  2 minutes (§5.5); QA checklist item 6 is 2.5 hours with the refresh log lines.
- **M3** (the emulator chain): 26b M2's one debug switch with six readers (`apiBase`, `apiOverLan`, `sdkUrl`,
  `webHosts`, `webProxy`, `debugTokens`); the page reaches the fake as `https://fake-spotify.invalid/` through the
  interceptor (mixed content stays blocked); the server uses `Reach::Any` only with the loopback override (§9.3, §2.4);
  step 5b's acceptance is §6j part A end to end.
- **M4** (natural advance): the resume seek on a natural advance stays `kind == LOCAL`; on Spotify a natural advance
  sends nothing (§5.4, §5.10); a renderer test.
- **M5** (403s and the cascade): the play call's 403 is an account/app outcome (`refused` or `not_premium`), never an
  item error; `unplayable` only from the SDK; the renderer stops after 3 in a row (§5.4, §5.8); QA checklist item 19.
- **M6** (no carrier for the lines): new `RendererState` values with `remote`, the loaded tile through
  `PlayerHub.State`, the stored outcome on every Spotify tile, `callStep` reads a Spotify state as paused (§5.8).
- **M7** (stale delivered blocklist): `compute_dns_filter_version` hashes the binding; the device card says the
  override; the launcher's site is `DnsFilterEngine.classify` (§6.2).
- **M8** (server machinery): `restamp_versions`, `SweepView::gated`/`phone_count`, `DevicePolicy.music_spotify_account`
  with `policy_music`, `interval(link, ..)`, `default_kind_for`, `SourceRequest.follow` and `SourceResponse.location`
  (§3.1, §3.2, §3.4, §3.6, §2.5; steps 2a/2b).
- **M9** (step sizes and order): steps 2a/2b and 5a/5b; the order 1 -> 2a -> 2b -> 3 -> 4 -> 5a -> 5b -> 6 -> 7; step 3
  lands with the provisional host list, the capture replaces it at the latest in step 6 (§11, §6.2).
- **L1**: `onRoom` reads the output (no change), `local.standDown()` when the Spotify renderer attaches, `toRoom` from a
  Spotify entry keeps the phone playing with `CANT_PLAY_ON_SONOS` (§5.6, §5.10).
- **L2**: scopes without `user-read-playback-state`, their source the web-app-player how-to, and the phone's token
  carrying the account's whole scope set stated (§2.2, §7.1).
- **L3**: `/me` 403 documented (§2.3); the transfer's 204 and an SDK state awaited before the second play (§5.4); the
  SDK's mobile support and the mobile-only-plan exclusion (§1.1, §0.2, §14); `linked_from` gone (§1.1, §5.4).
- **L4**: short links followed hop by hop, at most 3, each on Spotify's three hosts (§3.1).
- **L5**: the redirect suggestion is https only for an https request, else the loopback form (§2.1).
- **L6**: the callback answers 303 to `/music/spotify?login=<outcome>`, and the `SameSite=Lax` `spotify_login` cookie
  (the state's hash) is required at the callback - the optional defence adopted (§2.3).
- **L7**: §7.3's Terms row is an accepted-risk row naming what is stored and what deletes it.
- **L8**: the outcome persists in `spotify_state.json`, read by the report and the tiles (§4.4).
- **L9**: Spotify items are visible in both views; a room's queue drops them through `speakerDropReason` (§4.2).
- **L10**: the AIDL import, the courier through `MusicServer`, `new_track` reused, the CI paths and the tests that pin
  the three host-list copies (§6.1, §4.3, §8).
- **L11**: the service-worker client, a `null` state confirmed after 2 s, the host destroyed at every suspension, the
  accepted blip otherwise (§5.2, §5.4, §5.7).
- **L12**: an artist as its albums' tracks written up as an option behind `SPOTIFY_ARTIST_ALBUMS` and QA checklist item
  18; context mode stays the default per the assumed answer (a) (§3.3, §0.2 item 7). **Differs from the QA's
  proposal** (the option as the default): the main session's assumption keeps context mode the default until the user
  answers and the dev-mode check passes.
- **L13**: the quota is shared with the phones: a `QUOTA_EXCEEDED` stops Spotify checks for 60 minutes, the phone
  reports `quota` (§2.4, §3.4, §4.4, §5.4).
- **L14**: shuffle decided off by the phone's rule, removed from the questions (§4.2, §12, §14).

## Step 1 implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of the design commits (`2c95349`), one commit: §11 step 1, server only. No new
dependency; no device-facing change (no policy key, no device route, no library change: `music_library_snapshot`,
`music_listing_snapshot` and `policy_json_keys_snapshot` pass unchanged).

- **In**: `S/spotify.rs` (new): the link grammar of §3.1 (`parse_link` -> `Link::Ref(SpotifyRef)` / `Link::Short`,
  `parse_target`, the refused kinds with their sentences; no request, short links only recognised - resolving them is
  2a's), PKCE (`Verifier`, 64 characters of RFC 7636's alphabet from `OsRng`, unbiased; `s256`; a 32-hex `state`; the
  cookie's hex SHA-256), `authorize_url` (the exact query of §2.3), `Config` (`SPOTIFY_CLIENT_ID`,
  `SPOTIFY_REDIRECT_URI`, `SPOTIFY_ACCOUNTS_BASE`/`SPOTIFY_API_BASE` honoured only as `http://127.0.0.1:<port>` or
  `http://[::1]:<port>` - anything else ignored with one warning at start -, `INSECURE_COOKIES`), `App` (the environment
  over the row), `valid_client_id`, `valid_redirect_uri`, `suggested_redirect` (https only when a trusted proxy said
  `X-Forwarded-Proto: https`), and `Accounts` (`AppState.spotify`: the pending logins - at most 8, 10 minutes, memory
  only -, the per-account `tokio::sync::Mutex` around the cached access token, `quota_until`, a test clock offset):
  `start_login`, `take_pending`, `complete_login` (exchange, `/v1/me`, the upsert by `spotify_user`, the seal),
  `access_token` (refresh with rotation written before use, `unsaved` on a failed write, `invalid_grant` ->
  `needs_login` + `spotify_login_needed`, `invalid_client` -> `app_refused`), `api_get` (bearer, 401 once, `Retry-After`
  <= 5 s once, `QUOTA_EXCEEDED` -> 60 minutes without a Web API read, 404 `not_found`, `too_big`), the error codes of
  §2.4, `remove_account`/`remove_all` (phones unbound first). `S/music_secret.rs`: `seal_with`/`open_with` with an
  AAD (`spotify-v1:<fingerprint>:<spotify user>`); `seal`/`open` are Storytel's `storytel-v1` path as before.
  `S/music_net.rs`: `Kind::Token` (64 KB / 20 s). `S/main.rs`: `AppState.spotify`, the routes, the callback (and
  `/music/spotify/done`, below) among `public_routes`, the start's warnings. `S/handlers/music_spotify.rs` (new): the
  page, `POST /music/spotify/app`, `/app/clear`, `/login`, `/paste`, `/accounts/{id}/remove`, `GET
  /music/spotify/callback` (303, the `spotify_login` cookie checked and cleared). `templates/music_spotify.html` (new:
  the app card with the last four and the redirect URI large, "How to set it up" with §2.1's five steps - open while not
  configured -, Clear; the accounts card with each 6-month line and its 14-day warning, Remove, "Log in with Spotify",
  the private-window and 5-account hints, the paste for the loopback redirect); `templates/music.html` (a "Spotify"
  card linking to the page). `migrations/0073_music_spotify.sql`: §3.7's block byte for byte (its header already says
  that `pre_migration_backup` compares `MAX(version)` only); nothing else in `migrations/` changed.
  `server/testdata/spotify/`: `me_parent.json`, `me_kid.json`, `me_not_registered.json`, `token_code.json`,
  `token_refresh.json`, `token_refresh_rotated.json`, `token_invalid_grant.json`, `token_invalid_client.json`,
  `rate_limited.json`, `quota.json` (fakes only: `FAKE-ACCESS-<n>`, `FAKE-REFRESH-<n>`, users `fakeparent`/`fakekid`).
- **Tests**: `spotify::tests` 17 - the link grammar (every form of §3.1, `intl-xx`, `?si=`, junk, ids, refused kinds),
  PKCE (RFC 7636 appendix B, the alphabet and length, the state and its cookie), the authorize URL's exact query (and no
  `user-read-playback-state`), token answers (rotation, none, `invalid_grant`, `invalid_client`, 429, junk), the app
  settings' checks, the redirect suggestion, the loopback-only overrides and `Reach::Any` only with them (also over
  CannedFetch), a refresh's exact request and the cache's margin and generation, rotation written before use and the
  `unsaved` retry after a failed write, refusals (`invalid_grant` -> no request ever after), a key that can't open the
  token (another key, no key, a row moved to another user: the AAD), the per-account lock (two mints, one refresh),
  `api_get` (401, 404, `too_big`, `Retry-After`, the quota pause), `secret_types_have_no_debug`,
  `no_secret_in_errors` (a dropped connection and a redirect loop over loopback; answers echoing the token), the
  180-day line. `music_secret::tests::seal_with_binds_the_associated_data`. `tests::music_spotify` 14 - sessions
  (every POST and the page; the callback and the done page need none), "not configured" (the steps, no login button,
  the CSP, a phone's library unchanged), the app setting's 400s by field, the environment winning, the suggestion from
  `Host`/`X-Forwarded-*`, the start (303, the query, the cookie's attributes, 8 pending, no key = 503), the callback
  (the exchange with the verifier whose S256 the URL carried, the row, the seal, the kept access token, the event, the
  page's "Logged in as Ella.", a replay), the state and cookie checks (none, another browser's, unknown, 10 minutes:
  nothing asked of Spotify), refusals storing nothing (cancel, `/me` 403, `invalid_grant`, `invalid_client`, 503, no
  refresh token, the network, an app changed since the start), a new login of the same account (row and binding kept,
  generation + 1, `needs_login` cleared, the clock restarted), the paste (refusals never shown back, single use, another
  admin's login refused, cancel), Remove / a new client ID / Clear (the box, 404, phones unbound, the revision moves,
  tokens forgotten), the 6-month line and its warnings, and no code, token or verifier in any log line (a capturing
  subscriber at TRACE), event, page or stored text.

Deviations from the spec (each with its reason):
1. **The callback's 303 goes to `/music/spotify/done?login=<outcome>`**, a public page with no secret that moves on to
   `/music/spotify?login=<outcome>#accounts` by a `<meta http-equiv="refresh">` (and a link). §2.3's direct 303 to the
   session page can't work: the admin cookie is `SameSite=Strict`, and a browser withholds Strict cookies for the whole
   redirect chain of a navigation another site (accounts.spotify.com) started, so the page would answer with `/login`.
   The done page's own navigation is same-site, so the cookie is sent. The code still never sits on a page's URL. The
   paste (already same-site) goes straight to the page.
2. **The Spotify page carries its own CSP** - `ADMIN_CSP` with `form-action 'self' https://accounts.spotify.com` (the
   loopback fake's origin with the override) - and `admin_auth::admin_headers` now keeps a CSP a handler set (only this
   page does; every other page gets `ADMIN_CSP` as before, pinned by `not_configured_shows_the_steps`). Without it the
   login form's 303 to Spotify breaks: browsers enforce `form-action` on a form's redirect. `admin_auth.rs` is outside
   §11 step 1's file list.
3. **The paste checks the session's admin** (the one who started the login) instead of the `spotify_login` cookie,
   whose `Path` is the callback's (§2.3), so a POST to `/music/spotify/paste` never carries it.
4. **Two more login outcomes**: `off` (no app or no music key any more at the callback) and `error` (the account
   couldn't be stored). `expired` also covers a missing or mismatched cookie (the pending login is left in place then,
   so the right browser can still finish it) and an app whose client ID or redirect URI changed since the start.
5. **`LOGIN_BUDGET` = 45 s** for the exchange and `/me`, the gate's wait included (26b's probe budget; a feed fetch can
   hold the gate for 120 s); past it `unreachable`.
6. **`SourceResponse.retry_after`** (the `Retry-After` header, read by `HttpSource`; `CannedFetch::retry_after` in the
   tests): §2.4's 429 handling needs it and `music_net` didn't carry it.
7. **`api_get` itself refuses (`quota`, no request) during the 60-minute pause**, so the sweep (2a) and the add check
   (2b) both honour it; the accounts service (refreshes) goes on (§2.4).
8. **A 429 from the token endpoint is `RateLimited` (`rate_limited`)**, §2.4's `Busy` under the code list's name; step
   3's route maps it to `busy`.
9. **`refresh_error` codes**: as §2.2 plus `bad_answer`/`too_big`; a timeout is recorded as `network`; a 4xx error
   other than `invalid_grant`/`invalid_client` as `http_<n>`.
10. **The exchange's access token is kept** for its hour (no refresh right after a login); a new login also clears
    `refresh_ok_at` (a new token, not refreshed yet).
11. **Migration 0073 is §3.7 whole** (with 2a/2b's listing columns, the binding column and both triggers): a migration
    is frozen once pushed, and no later step lists one. Remove and Clear already unbind phones through the column.
12. **The link grammar's edges** (no vibb source at hand for them): `http://open.spotify.com` is taken too; `intl-` is
    followed by 2-8 letters or hyphens; the legacy `spotify:user:<id>:playlist:<id>` is no link;
    `open.spotify.com/collection/...` and `spotify:collection:...` get the Liked Songs sentence. Until 2a the grammar
    is `allow(dead_code)` outside the tests.
13. **The page**: the client ID is shown whole in its editable field (it is public, §2.1) and by its last four in the
    summary; with `SPOTIFY_CLIENT_ID` the field is read-only and a save stores the environment's ID with the redirect
    URI; Remove and Clear take a confirm checkbox (a 400 by the box without it). `/me`'s display name loses control
    characters; its `id` must be 1-128 visible ASCII characters.
14. **Events**: `spotify_app_saved` (the last four and the redirect URI; how many accounts a new client ID removed) is
    new; `spotify_login_failed` also records `authorize_error` (Spotify's `error` other than `access_denied`, as a fixed
    code) and `no_refresh_token`; a cancel records none.

Found in the spec: 1-3 and 6 above (§2.3's 303 against `SameSite=Strict`; `form-action 'self'`; the cookie's path
against the paste; `Retry-After`). Nothing in §11 step 1 was impossible.

Validation (from `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` 12 warnings, all on `437eb84`
already (`models.rs`, `dns_engine.rs`, `handlers/devices.rs`) - none added; `cargo test`: all pass but the
pre-existing, time-dependent `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`.

Owed: QA checklist items 1-2 [device check] (a real dev-mode app, the https login and the paste, a refused account);
the page at 360 px in a browser (no browser here: the URIs wrap, `max-width: 100%` on fields); whether `tailscale serve`
sends `X-Forwarded-Proto` [unverified] (if not, the parent edits the field); the browser behaviour behind deviations 1-2
is from the cookie and CSP specifications, not a run. `server/CLAUDE.md`'s Spotify section is step 7's; for the main
session's `DEPLOY.md`: `SPOTIFY_CLIENT_ID`, `SPOTIFY_REDIRECT_URI`, the two loopback-only base overrides, and that a
login needs the PWA on https or the paste.

### Fix round after QA's code review (qa-26c-step1-code, 2026-10-13)

All seven findings fixed; none disputed. `cargo fmt --check` clean; `cargo clippy --all-targets` the same 12
pre-existing warnings, none added; `cargo test` all pass but the pre-existing, time-dependent
`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`. Spotify tests: `spotify::tests` 18,
`tests::music_spotify` 15.
- **M1** (a login from another address ends "expired"): `POST /music/spotify/login` refuses (400 by the button, nothing
  pending, no cookie) when the redirect URI is https and the request's host - a trusted proxy's `X-Forwarded-Host`,
  else `Host`, lower-cased, `:443` dropped (`spotify::request_host`, shared with `suggested_redirect`) - is known and
  isn't the redirect URI's host[:port] (`spotify::redirect_host`): "Spotify sends this login back to https://<host>:
  open this page there to log in." with a link to `https://<host>/music/spotify#accounts`; the loopback redirect is
  unaffected. The `expired` sentence adds "was started at another address of this server"; §2.1's promise rewritten
  (above). Test `a_login_starts_only_at_the_redirects_address`.
- **L1** (the paste used up another admin's login): `Accounts::take_pending(state, admin)` compares the admin before it
  removes anything; the refused paste leaves the login, which its own browser then completes (in
  `the_paste_finishes_a_loopback_login`).
- **L2** (an unsaved rotated token retried only at the next refresh): the safe ordering is "write, then use" with the
  write retried at once - `write_refresh` tries three times (100 and 200 ms apart) before the access token is handed
  out; if the database still refuses, the token stays in memory, the failure is logged at `error` (the account id
  only), and **every** later `access_token` call - the cached path included - writes it again before anything else.
  The database is its only durable home: while it refuses writes nothing can carry the token across a restart, and if
  Spotify revoked the old token on rotation the account then comes back `invalid_grant` -> `needs_login` (a new login,
  recoverable). Test: after the failed write, a cached call writes it with no token request.
- **L3** (a 401's forced refresh not tied to the rejected token): `api_get` passes the token that got the 401 to the
  private `Accounts::mint`, which under the account's lock takes the kept token when it is another one (a concurrent
  401 refreshed already) and refreshes only when it is still the rejected one. Test `two_401s_refresh_once`: two
  concurrent mints rejecting the same token make one token request and write one rotation (red with the old forced
  refresh); a 401 for the new token refreshes again; `api_get`'s 401 still retries once. (Two concurrent `api_get`s
  over CannedFetch can't pin it: the canned 401 is per URL, not per token, so a read already holding the new token can
  draw the second queued 401.)
- **L4** (an absurd `expires_in` panics): `expires_in` must be `1..=86_400` (`MAX_EXPIRES_IN`), else `bad_answer`; rows
  in `token_answers_are_read` (86 400 taken; 86 401 and `u64::MAX` refused).
- **L5** (three claims unpinned): `the_callback_needs_its_state_and_cookie` completes the login with the right cookie
  after the missing/wrong-cookie attempts (red if those consumed it), then checks the 10 minutes on a second login;
  `routes_need_a_session` pins `/music/spotify/done?login=%22%3E%3Cscript%3E` -> `login=expired`, nothing reflected;
  the other admin's paste is L1's test.
- **L6** (the log capture's retry, a partial probe): the race is fixed, not retried. The test no longer uses a scoped
  `set_default`: tracing caches each callsite's interest process-wide from the dispatchers alive when it registers, and
  a scoped subscriber racing a callsite's first use on another test's thread left it "never" (the first lines missed).
  Instead one process-wide subscriber (`ThreadCapture`, installed once - no other test sets a global one - then
  `rebuild_interest_cache`) answers "sometimes" for every callsite and records the events of a thread whose
  thread-local capture is on. The probe requires all eight expected lines by count (`a login started` x3, `an account
  logged in` x2, `a login failed`, `access token refreshed`, `a refresh failed`), so a partial capture fails rather
  than passes. Six parallel runs of `cargo test tests::music` (149 tests) captured everything on the first try; a
  mutation logging the code at `trace` fails the test.

## Step 2a implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of step 1 and its fix round (`ca2b454`), one commit: §11 step 2a, server
only. No new dependency, no migration (0073's frozen columns are used as they are), nothing device-facing: a phone's
library still leaves Spotify out (`LIBRARY_ENTRIES` is 2b's), so `music_library_snapshot`, `music_listing_snapshot`
and `policy_json_keys_snapshot` pass unchanged.

- **In**: `S/spotify.rs` - the listing (§3.1, §3.3, §3.4): `Read` and `Accounts::read_url` (every Web API URL built
  from the canonical id with `form_urlencoded`, `market=from_token` on every track/episode read; the exact queries
  pinned), the parsers over the fixtures (`parse_playlist_meta`, `parse_playlist_items` over `items[].item`,
  `parse_album` + `parse_album_tracks`, `parse_track`, `parse_named` for an artist and a show, `parse_show_episodes`,
  `parse_episode`, `parse_artist_albums`) into `Listed` (id, title `"<name> — <artist>, <artist>"` by `track_title`,
  duration, picture, an album track's disc/track) and `Page` (left out counted: episodes in a playlist, local files,
  `is_playable: false`, removed `item: null`, no usable id; `next`, `total`), `pick_image` (of at least 300 px the
  narrowest, else the widest; `media_url_ok`), `Meta` (§3.2's entry name, the picture, a playlist's snapshot),
  `readers` (no `needs_login`, the last reader first, a removed reader's id counts for nothing; `Readers::refusal` =
  `no_account` / `reauthorize` before any request), `try_next_reader`, `Config.artist_albums`
  (`SPOTIFY_ARTIST_ALBUMS`, `1`/`true`, off by default), `resolve_short_link` (one hop at a time: `follow: false`,
  `Reach::Public`, at most `SHORT_LINK_HOPS` = 3 requests, each `Location` - relative ones resolved - https on
  `spotify.link`/`spotify.app.link`/`open.spotify.com` without userinfo or port, the first `open.spotify.com` URL
  parsed and never asked; `ShortLinkError::{Dead, Link, Unreachable}` with their sentences), and
  `Accounts::api_get_counted` (the read counting every request it makes, token refreshes included, for the sweep's
  budget; `mint` counts). Step 1's `cfg_attr(not(test), allow(dead_code))` is gone from everything 2a uses; it stays
  only on what 2b's add form and card will use (`SpotifyRef::key`/`open_url`, `LinkError::message`,
  `ShortLinkError`, `resolve_short_link`, `SHORT_LINK_HOPS`). `S/music_net.rs`: `SourceRequest.follow` (default
  `true`; `false` = two more clients with `redirect::Policy::none()`, the public one keeping its resolver) and
  `SourceResponse.location` (cut one character past 2000; in `Debug` only as present/absent). `S/music.rs`:
  `Link::Spotify { kind, id }` (`target`, `source`), `entry_link("spotify", ..)` through `spotify::parse_target`
  (canonical only), `parse_link`'s pointer (`SPOTIFY_ON_ITS_PAGE` for a `spotify:` URI or any link on Spotify's three
  hosts), `default_kind_for` + `default_category_for` (a Spotify show or episode is a podcast), `CheckError::Spotify`
  with `spotify_check_message`, `sweep_error_text("spotify", ..)` with §3.4's sentences, `check_link` refusing a
  Spotify link like a Storytel one, and `spotify_gate!()`. `S/music_sweep.rs`: `schedule` and `check_entry` take
  `'spotify'` (a target that doesn't parse is skipped with a debug line); `interval(link, ticked, setting)` (NRK as
  before; a Spotify album, track, episode or artist daily; the rest - RSS, Storytel, a Spotify playlist or show - the
  setting); `spotify_check` (below); the listing row's Spotify state (`Listing.spotify_*`, a `SpotifyPlan` kept as it
  was by a failed check); `listing_for` / `build_context_listing` (the check, the route and the restamp build a
  listing alike); `device_listing` and `restamp_versions` take `'spotify'`; `flag_reports` skips Spotify;
  `art_candidate` takes a Spotify item without a URL; `art_judged_alone` (Storytel, and a Spotify playlist's,
  track's, album's or artist's track pictures: own, no references, no twin merge; a show's episodes as RSS); the art
  pass's play order for a show newest first; the per-pass memo. `server/testdata/spotify/`: `playlist.json`,
  `playlist_items.json`, `album.json`, `album_tracks.json`, `track.json`, `artist.json`, `artist_albums.json`,
  `show.json`, `show_episodes.json`, `episode.json`, `forbidden.json` (ids `0Fake…`/`1Fake…`; pictures on
  `i.scdn.co`/`mosaic.scdn.co` under `fake-…` paths). `S/tests/music.rs`: `Canned.location`, `CannedFetch::redirect`,
  `Recorded.follow`.
- **The check** (`spotify_check`): no app `spotify_off`, no account `no_account`, every login ended `reauthorize`,
  inside the quota pause `quota` - each without a request. Then the kind's metadata read with the first reader that
  answers (403/404/401 and an account that can't be used now let the next try; anything else - `app_refused`,
  `quota`, `rate_limited`, the network, 5xx, `bad_answer` - ends it; every reader failing reports `not_found` if one
  said 404, else the first failure). The listing's title is the metadata's name (§3.2's), its cover candidate the
  picked image. A **playlist**: the snapshot gate (list mode, the same `snapshot_id`, the items read under 24 h ago,
  not Check now: done, one request), else `/items` with each reader in turn: one may list - its pages with that
  reader, at most 1000 kept (`capped`), a repeat once (its first place), list mode, `spotify_reader` = that reader;
  none may (every one 403 or 404) - **context mode**. An **album**: the embedded first page and `/tracks` pages,
  sorted by disc and track when every track has both. A **track** / **episode**: one item. A **show**: `/shows/{id}`
  then `/episodes` pages, the newest 100 (`capped`), kept oldest first, `keep_end = 'newest'`. An **artist**: context
  mode (one read); with the option, `/artists/{id}/albums` (the first reader that may; every one 403/404 = context
  mode), at most 10 pages, newest first by `release_date` (a stable sort), 20 albums, each album read (a 404 album
  passed over) with its tracks in order and the album's picture, 500 tracks, `capped` when anything was cut.
  **Context mode**: no items, `keep_end = 'first'`, the reader and (a playlist's) snapshot kept, `spotify_items_at`
  NULL; a playlist in context mode is read again at every check and flips back as soon as a reader may list it.
  Items keep their rows (first sighting, the art pass's result) as a feed's; one that left the list is gone from it.
  Every request counts against the check's budget; past it `timeout`.
- **Tests**: `spotify::tests` 25 (7 new: `listing_parsers_read_the_fixtures`, `the_image_pick`,
  `read_urls_are_exact`, `readers_put_the_last_one_first`, `short_links_are_followed_hop_by_hop`,
  `reads_count_their_requests`, `the_artist_option_is_off_by_default`; `two_401s_refresh_once` now also counts its
  one refresh). `tests::music_spotify::listing` 12 - `a_check_lists_the_fixture_playlist` (the acceptance's first line;
  the exact requests, the bearer, `Reach::Public`, the listing route 404 for a phone not bound and the list for a
  bound one, an emptied playlist served empty), `the_snapshot_gate` (one request when unchanged; the items after a
  day, for Check now, for a new snapshot), `a_403_playlist_goes_to_context_mode_and_back` (the second line; each
  reader in turn, context mode's version and empty list served, the revision moved, re-read at every check, back to
  list mode read by the parent, the parent asked first from then on, a removed reader counting for nothing),
  `an_artist_is_a_context_unless_the_option_lists_its_albums` (and the option's 403 fallback),
  `albums_tracks_shows_and_episodes_are_listed`, `long_playlists_and_shows_are_capped` (1000 tracks in 20 pages,
  the 21st never asked; a show's newest 100), `failures_keep_the_last_good_list` (`rate_limited`, `not_found`,
  `http_503`, `bad_answer` with one request; `reauthorize`, `no_account`, `spotify_off` with none - the list, the
  version and the Spotify state kept), `the_quota_ends_the_passs_spotify_checks` (one Spotify read in a pass, then
  `quota` without a request, the RSS entry checked, Spotify asked again after the pause), `the_interval_follows_the_kind`,
  `track_art_is_own_and_each_url_is_asked_once` (two tracks, one album picture, one request; own even when the album
  picture is the playlist's cover; a failing picture asked once for both; a show's episode `same` by the show
  picture's URL), `a_restore_restamps_a_spotify_listing` (the third line; the route serves the restamped version; a
  context listing restamps to itself), `reports_never_flag_spotify_items`. `music::tests::
  spotify_entries_have_their_own_link_and_kind` (entry links, the pointer for every Spotify form, `default_kind_for`
  and `default_category_for`, the sentences). `music_net`'s client test: `follow: false` answers the 3xx with its
  `Location`, never past the public rule. `tests::music_sweep::the_cadence_follows_the_setting` on links.

Deviations from the spec (each with its reason):
1. **`spotify_gate!()` is defined in 2a and used by `device_listing`** (the macro and `LIBRARY_ENTRIES`,
   `cover_in_library`, `art_in_library` were 2b's): 2a adds `'spotify'` to the listing route's `IN`, and without the
   gate a ticked phone that doesn't play Spotify could fetch a Spotify listing; §3.6's "a phone not bound gets no
   Spotify listing" holds at every commit. 2b adds it to the other three.
2. **Context mode's version moves with the mode, the title and the cover's source URL** (`cover_url`), not the
   stored cover's hash: `MUSIC_COVERS.recover_missing` forgets a listing's `cover_hash` and keeps its URL without a
   restamp, and a version built from the hash would then differ from what the route rebuilds. The route serves a
   context listing as `{v, entry, version, items: []}` (no new field: the listing contract is step 3's).
3. **A Spotify list that became empty is listed and served empty** (`listing_for`; not in the spec): `build_listing`
   gives nothing for no items, which for NRK/RSS/Storytel keeps the old version while the route answers 404; a
   playlist the parent empties is ordinary, so the library and the route agree on an empty list. A first fill with
   nothing playable is still `no_items`.
4. **Items 404 for every reader is context mode too** (the spec says 403): dev mode's documented refusal is 403, but
   a private playlist answers 404 to an account that may not see it, and the metadata read already answered; the
   only other outcome would be a failing check with the playlist playable as a context.
5. **The per-pass URL memo is two parts**: a URL judged in the pass is found again by design 27's step 2 (this
   entry's items first, so no request and no database read); the new `tried` map remembers a URL that got no picture
   (failed - a try used, as for the first item - or its host in trouble - a day later, no try used), so a failing
   album picture costs one request, not one per track. It applies to every source.
6. **The artist option's details** (§3.3 leaves them open): the albums pages (at most 10) are read before the newest
   20 are picked (Spotify orders by group, then date), dates compared as text (`2026-03` > `2025-11-14` > `2019`); a
   404 album is passed over; a track on two albums is kept once; `capped` when an album or a track was cut.
7. **`parse_playlist_items` also reads `track`** (the field's name before February 2026) where `item` is absent - a
   grandfathered app's answer still lists; `item` wins.
8. **The generic add form's pointer is a sentence** (`SPOTIFY_ON_ITS_PAGE`, naming `/music/spotify` and the Spotify
   card below the form), not an anchor: the form's error is plain text, and the template is 2b's (the PWA).
9. **Web API reads and short-link hops use `Kind::Page`** (2 MB / 30 s; the spec names no kind; a 50-track page is a
   few hundred KB): no new `Kind`.
10. **A playlist's metadata read uses `fields=` without `market`**, every other read `market=from_token`; the show's
    episodes come from `/shows/{id}/episodes` from offset 0 (the spec's two reads), the album's first 50 tracks from
    `/albums/{id}` (the spec's).
11. **`sweep_error_text("spotify", ..)` also covers `key` and `no_items`** ("nothing in it can be played here").

Found in the spec:
- §4.1's `uriOf` ("a show's items are `spotify:episode:<key>`, everything else's `spotify:track:<key>`") is wrong for
  an **episode entry**: its one item is an episode (`spotify:episode:<key>`). Step 3's `uriOf` must take `show` and
  `episode` to `spotify:episode:`. Nothing server-side depends on it.
- §3.4's "Errors (`sweep_error_text`)" lists no text for `key` or `no_items` (11 above).
- Nothing in §11 step 2a was impossible.

Owed: 2b (the add form with the add check - it can reuse `readers`, `try_next_reader`, `read_url`, the parsers and
`resolve_short_link`; the library gate in `LIBRARY_ENTRIES`, `cover_in_library`, `art_in_library`; the cards'
lines, `SweepView::gated`/`phone_count`, `check_now` and `/music/cards` taking Spotify; the import; and - as Storytel's
L1 - making entries that failed `spotify_off`/`no_account`/`reauthorize` due at once after a new login or app save,
which 2a doesn't do: they wait for their backoff). Step 3: the token route, the policy, the listing contract (a
Spotify entry's `spotify` field in the library; an empty `items` for context mode is already what the route serves).
QA checklist item 18 (the artist option on a real dev-mode app). The real Web API's shapes are from the reference and
the February 2026 migration guide, not a run [unverified]: `items[].item`, `is_playable` with `market=from_token`, a
403 (or 404) on another account's playlist items.

### Fix round after QA's code review (qa-26c-step2a-code, 2026-10-13)

All six findings fixed; none disputed (one premise corrected, L3). `cargo fmt --check` clean; `cargo clippy
--all-targets` the same 12 pre-existing warnings, none added; `cargo test` all pass but the pre-existing, time-dependent
`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`; the library, listing and policy snapshots unchanged.
- **M1** (a transient after a 403 made a listed playlist a context): `SpotifyReads::first` ends the read with
  `refused: false` on any failure that doesn't pass to the next reader, and calls the read refused only when **every**
  reader answered 403/404 (a reader that can't be used - another key, a login ended mid-check - isn't a refusal: its
  code is reported, as the parent can mend it; stricter than the QA asked). Test
  `a_transient_after_a_403_keeps_the_listed_playlist` (the QA's probe, kept: 403 then 503 / `QUOTA_EXCEEDED` / a key
  that can't open the next login - the check fails with that code, list, mode, reader, version and revision unchanged;
  red with the old flag), and the artist option's 403-then-503 fails in `the_artist_options_albums_page_by_ten`.
- **M2** (`limit=50` on the artist's albums, documented maximum 10): `ARTIST_ALBUMS_LIMIT` = 10, offsets by 10,
  `MAX_ARTIST_ALBUM_PAGES` kept at 10 = 100 albums and singles read before the newest 20 are picked (the endpoint's
  order is undocumented, so it is read whole up to that and sorted; past it `capped`) - §3.3's "one request per page of
  10 albums". The fixture's `limit` 10; test `the_artist_options_albums_page_by_ten` (pages at offsets 0..90, the 11th
  never asked, the newest 20 across pages, `capped`).
- **L1** (an episode in a playlist told apart by `type` only): the items read asks `additional_types=track,episode` -
  the parameter the reference documents ("item types that your client supports besides the default track type ...
  check against the type field"), so an episode arrives as an `EpisodeObject` with `type: "episode"` and is left out
  by it; §3.1's URL updated. As defence for the undocumented track-shaped episode, a track with `"episode": true` or a
  `uri` not starting `spotify:track:` is left out too (parser rows in `listing_parsers_read_the_fixtures`).
- **L2** (four test gaps): `first_fills_readers_pages_and_repeats` - `no_items` on a first fill (an unplayable track
  entry; no version, a failure; the card's sentence), the metadata read moving to the next reader (the kid 404, the
  parent reads, `spotify_reader` = the parent), a track repeated on a later page listed once at its first place, and a
  later page's 503 failing the check with the list, mode and version kept.
- **L3** (raw codes on the card): `sweep_error_text("spotify", ..)` has `database` ("this server couldn't read its
  Spotify login - it tries again later") and `refused` ("Spotify's address pointed somewhere this server won't go"),
  rows in `spotify_entries_have_their_own_link_and_kind`. Premise corrected: `refused` can't reach the card today -
  `FetchError::code` maps `Refused` to `network` - so that row is for a future code; `database` was real.
- **L4** (`spotify_gate!()` on one line): written with `\` continuations, one clause per line, as `storytel_gate!()`
  (a `\`-newline in the generator script had joined the lines).

## Merge of main (a5d30b9, 2026-10-13)

`origin/main` (designs 29, 31 step 1 - the PWA shell - and 32 step 1) merged into `music-3-spotify` after step 2a's fix
round, before step 2b. One textual conflict: `PLAN.md`'s music paragraph - both sides kept (this branch's "Phase 3,
Spotify" text, then main's design 29 sentence); main's one-line "Phase 3 (Spotify) follows design 26; its first step,
the engine spike, ..." was dropped as superseded by the 26c text. `templates/music.html` merged by itself: main's shell
(no Apps|Music switch, `{% let tab = "music" %}`) with step 1's Spotify card. What the merge broke, fixed in the merge
commit: `templates/music_spotify.html` (step 1's page) set no `tab`, which `tests::shell::every_template_with_the_bar_sets_its_tab`
requires - it now sets `{% let tab = "music" %}` before the header include (it had no inline `onchange`). Nothing
in `server/src/music*.rs`, `handlers/music.rs`, migrations or testdata changed on main. On the merge: `cargo fmt
--check` clean, `cargo clippy --all-targets` the 12 known warnings, `cargo test` 733 passed, 0 failed -
`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` passes now (main changed `tests/parent_pin.rs`).
## Step 2b implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of the merge of main (`5d6bd5b`, above: step 2a and its fix round plus
design 31 step 1's shell), one commit: §11 step 2b, server only, its templates on design 31's shell.
No new dependency, no migration (0073's frozen columns as they are: `device_policy.music_spotify_account` and its
revision trigger, `music_listings.spotify_*`). Nothing device-facing beyond the gate: no token endpoint, no policy key
`music.spotify`, no DNS hosts (step 3). `music_library_snapshot`, `music_listing_snapshot` and
`policy_json_keys_snapshot` pass unchanged (the library's fixture has no Spotify entry, and `LibEntry.spotify` is
skipped when absent); `testdata/music_state.json` is unchanged too (`MusicState.spotify` is not written when absent).

- **Entries** (`S/handlers/music_spotify.rs`, §3.2): `POST /music/spotify/add` (`link`, `category`, `device` repeated,
  read as `Form<Vec<(String, String)>>`; more than 200 fields a 400). In order: no app "Set up your Spotify app first.",
  no usable account "Log in with a Spotify account first." (every login ended: "The Spotify logins need renewing - log
  in again ..."), a picked phone that doesn't exist, `spotify::parse_link` (its sentences), a short link resolved hop
  by hop (`resolve_short_link`), "Already in the library." with a link to its card (`/music#entry-<id>`), the category
  (picked, else `default_category_for` by the link's kind: a show or episode to Podkast), then **the add check**
  (`spotify::add_check`): the kind's metadata read with the readers in turn, and for a playlist its first items page
  with each reader until one may list it - every reader 403/404 = the context notice. The short link and the check
  share one `ADD_BUDGET` (45 s, the gate's wait included). Every account refusing (404/403) is "Spotify doesn't let
  this app open it - Spotify's own playlists (made by Spotify) aren't available to developer apps, or the link is
  private or deleted."; the network, a timeout, a 5xx or a rate limit "Spotify couldn't be reached - try again.";
  anything else the error's own line (`add_refusal`). Then one `BEGIN IMMEDIATE` transaction: the INSERT (source
  `spotify`, the canonical target, `SpotifyRef::key`, the metadata's name cut to 60 - else the kind's label -, order
  `auto`, cache 0, resume on, sort next, `BELOW_ENTRY_LIMIT`), and each picked phone that plays as an account ticked
  through `music::tick_fits`; the others are named in the notice ("Ola: not ticked - Spotify is off on this phone", "...
  - its music library would be bigger than 3 MB"). The sweep woken, the ticked phones nudged, `music_entry_added`
  ("entry N: Spotify: <name> (<target>), ticked on phones ..."), 303 to
  `/music/spotify?added=<id>[&context=1][&on=..][&off=..][&big=..]#added`. Every refusal is a 400 with the page again,
  the link, category and phones as entered and the error by its field (`autofocus`).
- **The readers' turns are shared** (qa-26c-step2a-code's note): `spotify::ReaderTurns` (and `NoReader`, moved from
  `music_sweep`) is the one rule - 403/404 and an account that can't be used now pass to the next reader, anything
  else ends the read as that failure, `refused` only when every reader said 403/404 - used by
  `music_sweep::SpotifyReads::first` and by the add check.
- **The page** (`templates/music_spotify.html`, `tab` = music): an "Add a Spotify link" card (`#add`, once an app is
  set up) - one line ("A playlist, album, track, artist or podcast: in Spotify, Share › Copy link.") with the rest
  behind an ⓘ (`details.info`, design 31 §4.4), the link, the category ("By its kind" or one), the phones as a grouped
  list (`.group` of `label.row`, each "Plays as <account>" / "Spotify is off on this phone" as `.row-sub`), the
  refusal by its field (`aria-invalid`, `autofocus`), and after an add a `.flash` at `#added` ("Added <name>. On Ella
  within a few minutes." / "Turn it on for a phone on that phone's Music card." and the per-phone notes). The app and
  accounts cards stay on `/music/spotify` as step 1 built them: design 31 step 12 moves the login/app part to
  `/music/settings`, which doesn't exist yet and isn't built here.
- **Music's Add section** (`templates/music.html`, design 31 §3.4): step 1's Spotify card is replaced by a grouped-list
  navigation row **From Spotify ›** (`/music/spotify`, `id="spotify"` so the page's back link still lands) whose value
  is "Signed in" (an account without `needs_login`), "No login" or "No app" - the place §3.4 gives "From Storytel ›"
  (main's Music page has no Storytel row yet: step 12 builds those rows).
- **The library gate** (`S/music.rs`, §3.6): `LIBRARY_ENTRIES`' `IN` takes `'spotify'` behind `spotify_gate!()` (a
  fourth bind), and so do `cover_in_library` (both entry branches) and `art_in_library`. `LibEntry.spotify`
  (`#[serde(skip_serializing_if = "Option::is_none")]`): `"list"`/`"context"` from `music_listings.spotify_mode` for a
  Spotify entry, absent for every other source and for a Spotify entry not listed yet - whose `items` is then null too
  (the phone hides it until it is listed); a Spotify entry's `cache` is sent as 0, `lan` false. `needs_login` doesn't
  close the gate.
- **Words** (`S/music.rs`): `kind_label` "Spotify playlist" / "album" / "track" / "artist" / "podcast" / "episode";
  `item_error_code` takes `unplayable` ("Spotify couldn't play this track on the phone"); `MusicState.spotify` keeps
  only `SPOTIFY_WORDS` (`none`, `ok`, `reauthorize`, `refused`, `not_premium`, `no_widevine`, `unreachable`, `quota`).
- **The cards** (`S/handlers/music.rs`): `SweepView` loads whether an app is set up, the bound phones (with their
  account's name) and the accounts' names; `gated` gives a Spotify entry "no Spotify app is set up" on every phone
  without an app and "Spotify is off on this phone" on an unbound one, and `phone_count` counts only the others.
  `SweepView::spotify_card`: "42 tracks · read as Pål" (a show or episode "episodes"), context mode "Spotify picks the
  order · read as Pål" with its notice (a playlist: "No logged-in account owns this playlist ..."; an artist: "An
  artist plays as Spotify picks ..." or, with `SPOTIFY_ARTIST_ALBUMS`, "Spotify refused the artist's albums - played
  as Spotify picks."), "N left out: not playable here, local files or podcast episodes", the caps ("the first 1000
  tracks (the playlist has more)", a show's newest 100, the artist option's 20 albums / 500 tracks), "Open in Spotify"
  (`open.spotify.com/<kind>/<id>`, `rel="noopener noreferrer"`), no offline select (`SweepCard.offline`). Per-phone
  lines: "Ella: plays as Ella" plus "· N failing (Spotify couldn't play this track on the phone)" from `item_errors` at
  the current version; no "on the phone" counts. The never-listed text goes through `CheckError::Spotify`. `check_now`
  and `/music/cards` take Spotify; `save_offline` stores 0 for a Spotify entry whatever is posted.
- **The entry page** (`templates/music_entry.html`): the target with "Open in Spotify", "Tracks" for the status card, no
  offline select (a hint that Spotify streams instead), and in context mode no order select ("Spotify picks the order
  ..."); `save_entry` stores cache 0 and keeps the stored order when none is posted.
- **The device card** (`#music-spotify`, `S/handlers/music.rs`, `templates/device_detail.html`, §2.5): in the
  existing Music card's style (design 31 steps 3-10 haven't rebuilt the device page), shown once an app is set up, or
  while accounts or a binding remain (step 1's QA note: accounts left without an app still show).
  "Spotify plays as: Off | <accounts>" (`data-autosave`, a `<noscript>` Save; `POST /devices/{id}/music/spotify`, `account` = `off` or an id;
  the account's existence is checked in the `UPDATE` itself; an unknown or junk id is refused back at
  `#music-spotify` with `music_notice=spotify_unknown`, nothing changed; an unknown phone 404; the phone nudged; event
  `music_spotify_binding_changed`), the hint to use the child's own account (§2.2's scope note), the bound account's
  line ("Ella: logged in, log in again before ...") or warning (needs a new login, another key, ends within 14 days),
  "Spotify plays on one device at a time per account: Ella's and Ola's phones both play as Ella - give each child their
  own account." on every such card, the phone's `music_state.spotify` in words ("On the phone: Spotify plays." / ...
  `not_premium`, `no_widevine`, `reauthorize`, `refused` as warnings), "Spotify is off on this phone: its N Spotify
  entries aren't sent." for ticked entries on an unbound phone, no app set up as a warning. The mobile-data text gains
  "This includes Spotify, which streams (about 60-150 MB an hour)." (The DNS section's note "Spotify's hosts are
  allowed on this phone while Spotify is on ..." was built here and moved to step 3 by the fix round: L3.)
  `DevicePolicy.music_spotify_account`.
- **Due after a login or an app save** (2a's owed item, Storytel's L1): `schedule` reads the app's `saved_at` and the
  newest `authorized_at`; a Spotify entry failing `spotify_off`, `no_account`, `reauthorize`, `key` or `app_refused`
  since before either is due at once (`Why::Rework`). `complete_login` and the app's save wake the sweep.
- **The import** (`S/music_import.rs`, `S/handlers/music_import.rs`, §3.2): `Skip::Spotify` is gone. A target
  `spotify::parse_link` takes as a `Ref` (a URI, an `open.spotify.com` link with `intl-xx`/`?si=`) imports as a Spotify
  entry (name, order, resume; cache 0) and is ticked on no phone, though phones were picked; a short link is
  `Skip::SpotifyShortLink` ("a Spotify short link - resolve it by adding it on the Spotify page"); audiobooks and Liked
  Songs/profiles are `Skip::BadLink` with §3.1's sentence; a `spotify:` text that isn't a link too; any other text on
  Spotify's hosts goes on to `parse_link` (the pointer); a look-alike host is a feed as before. `spotify_user` sections
  stay `Skip::SpotifyProfile` (its reason now "... which can't be followed here - add its playlists on the Spotify
  page"). The preview words a Spotify row "streamed, on no phone yet". The /music import text and Spotify card follow.
- **Validation** (from `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` the 12 known warnings
  (`models.rs`, `dns_engine.rs`, `handlers/devices.rs`), none new; `cargo test` 744 passed, 0 failed (the
  `parent_pin` test passes since the merge).
- **Tests**: `tests::music_spotify::entries` 9 - `a_link_is_added_and_ticked_on_the_phones_that_play_spotify` (the
  exact requests of the add check, the row, the key, the redirect, the tick on the bound phone only, the wake, the
  nudge, the event, the page's notice; before its first check the library hides it - no `spotify`, `items` null, cache
  0; after it `spotify: "list"` and the version; an unbound phone ticked on its card gets nothing until bound),
  `the_add_form_refuses_with_nothing_stored` (no app, no account, every login ended, junk, an audiobook, Liked Songs, an
  unknown phone and category - no request for any of them -, 404 for every account, 503, already in the library with
  its link; the link kept), `context_playlists_short_links_and_kinds` (403 items for both readers -> `context=1` and the
  notice; a short link over two hops stored canonical with "Nattlys — Ola Fjell" in Musikk, again = already in the
  library, a dead one; a show to Podkast, a picked category wins), `the_gate_holds_on_library_covers_and_art` (library,
  listing, listing cover, parent's cover, a track's art: 200 bound, 404 unbound; `needs_login` keeps it; unbinding drops
  it and moves the revision), `the_cards_and_check_now` (the acceptance's Check now: the card's Check now asks, the
  next pass's first due entry is it, the check lists the playlist; "3 tracks · read as Ella", the left-out note, "on 1
  phone", the per-phone lines with `unplayable`, the link back, no offline form, `/music/cards`, the offline save's 0;
  context mode's card; no app = every phone gated, "on 0 phones"),
  `the_entry_page_has_no_offline_and_in_context_mode_no_order`, `the_device_card_binds_a_phone_to_an_account` (absent
  before an app; bind, revision, nudge, event; junk and unknown refused at `#music-spotify`; 404; one account on two
  phones on both cards; `not_premium`; `needs_login`; off; the ticked-but-off note; the mobile-data text; no DNS note
  yet - step 3's), `a_new_login_or_app_save_makes_the_waiting_entries_due` (`spotify_off` cured by an app save,
  `no_account` by a newer login, not by an older one), `a_vibb_library_imports_spotify_entries_on_no_phone` (through
  the confirm route: no request, the RSS row ticked, the Spotify rows not, the short link's reason). `routes_need_a_session`
  covers the two new routes. `music::tests::spotify_words_and_labels`, a `LibEntry.spotify` row in
  `the_version_ignores_itself_and_follows_every_field` (absent = no key and the old bytes; `"list"` hashed and served),
  `music_import::tests::spotify_links_import_as_spotify_entries`; the import route tests and
  `rows_are_classified_like_add_without_a_fetch` updated for the Spotify rows that now import (their skipped row is a
  short link now; `a_vibb_library_is_previewed_then_imported_without_a_fetch` offers the Spotify row - left unticked,
  so "5 skipped"). `the_add_form_refuses_with_nothing_stored` also reads the "From Spotify ›" row's value through
  No app / No login / Signed in. Every template touched sets `tab` and has no inline `onchange`
  (`tests::shell` green).

Deviations from the spec (each with its reason):
1. **The add doesn't write the listing's cover candidate** (§3.2 step 3): no `music_listings` row is made at add (a row
   without a version would change how `schedule` treats a new entry); the add wakes the sweep, whose first check -
   within a minute, first in line as `Why::New` - writes the same picture from the same metadata read.
2. **The add check reads a playlist's items only for the notice**: a failure there other than every reader's
   403/404 adds the playlist without it (the sweep's first check decides list or context mode for good); §3.2 names no
   outcome for it.
3. **An unknown account on the binding select is a 303 back to `#music-spotify` with a notice**
   (`music_notice=spotify_unknown`), not a 400: the device page has no 400 re-render, and the card's other refusals
   (Sonos rooms, too-big ticks) answer the same way.
4. **More codes cure a waiting entry**: `key` and `app_refused` too (a new login and a new app save cure them as well
   as `spotify_off`, `no_account`, `reauthorize`).
5. **The card's line is "42 tracks · read as Pål"** and the left-out count is its own note ("3 left out: not playable
   here, local files or podcast episodes", §3.4's wording) rather than "· 3 left out" on the line: the line is one
   ellipsised row on a 360 px card.
6. **More words on the device card**: `refused` and `quota` (§4.4's words) have lines too, `none` none; "no Spotify
   app is set up" is a warning there; the account's 6-month line and its warnings are the Spotify page's (§2.2).
7. **The import ticks a Spotify entry on no phone** (§3.2's "untick") even when the parent picked phones; a `spotify:`
   text that isn't a link is `BadLink` with §3.1's sentence, and other text on Spotify's hosts falls through to
   `parse_link`'s pointer (so `open.spotify.com.example.org` stays a feed).
8. **Names**: an entry Spotify gave no name is named by its kind's label ("Spotify playlist", ...); a Spotify entry's
   name is cut like a title (`cut_title`, 60).

Found in the spec:
- §3.6's `gated` says "on every phone while no app is set up, 'no Spotify app is set up'", but `spotify_gate!()`
  (§3.6's own SQL) doesn't look at the app: Clear and a new client ID unbind every phone, so the two agree except when
  `SPOTIFY_CLIENT_ID` is taken away between restarts with accounts and bindings left - then the bound phones still get
  the entries (the token endpoint, step 3, answers `off`) while the card says they aren't sent. Built as written; step
  3's token route is where "off" reaches the phone.
- §3.2's "A target already in the library: 'Already in the library'" comes after the parse and the short link's
  resolution (the canonical target is needed); built in that order.
- Nothing in §11 step 2b was impossible.

Owed: design 31 step 12 (the Music tab: `/music/settings` takes the Spotify app and logins as it takes Storytel's
login; the entry page and the cards as list rows; `overflow-wrap` fallbacks and inline flex `gap` in the `music*.html`
style blocks, which step 1 left for it); QA checklist item 3 [device check] (a real playlist added and listed; Check
now on its card); the PWA at 360/390 px, light and dark, JS off, in a browser (no browser here: the new forms use the existing classes, `max-width: 100%` on fields, wrapping text, no
fixed width; every save redirects to its anchor - `#added`, `#music-spotify`, `#entry-<id>`); step 3: the token
endpoint, `music.spotify` in the policy through `policy_music` (which then reads `DevicePolicy.music_spotify_account`),
the delivered blocklist's drop and `compute_dns_filter_version`'s binding, the library snapshot with a Spotify entry
(its `spotify` field) and `music_state.json`'s `spotify`, **and the device page's DNS note** (§6.2: "Spotify's hosts
are allowed on this phone while Spotify is on", in the Web Filter section while the phone is bound, with its test
lines) - beside the change that makes it true (qa-26c-step2b-code L3).

### Fix round after QA's code review (qa-26c-step2b-code, 2026-10-13)

All six findings fixed; none disputed. `cargo fmt --check` clean; `cargo clippy --all-targets` the same 12 known
warnings, none new; `cargo test` twice: 745 passed, 1 failed both times - the failure is
`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` (main's timing-dependent test, the QA's note; the
only tolerated failure).
- **M1** (a dropped caller could cancel a refresh and lose a rotated refresh token): `Accounts::mint` takes the
  account's lock as an `OwnedMutexGuard` and runs everything under it - the cached path's retried write of an
  `unsaved` token, the refresh request, the rotated token's write, the `refresh_ok_at` stamp, the slot's update - as a
  `tokio::spawn`ed `MintJob` that owns what it needs (a clone of `AppState`, the `Config`, the clock's now); the caller
  only awaits the `JoinHandle`, so dropping it (the add form's budget, step 3's token route, a client gone) cancels
  nothing, and a second caller still waits for the one refresh. `token_request` and `refresh_failed` became free
  functions; `MintJob` is in `secret_types_have_no_debug`. Test `a_dropped_caller_never_loses_a_rotated_token`: a
  refresh held at the gate when its caller gives up still writes `FAKE-REFRESH-2` and keeps the access token (the next
  call makes no request); a rotated token whose write failed survives a caller dropped during the retried write and is
  written. Red with the task inlined (checked).
- **L1** (the card said "not sent" while the gate sent it): `gated` follows the binding as `spotify_gate!()` does -
  a bound phone is never gated; without an app its line reads "Ella: plays as Ella - no Spotify app is set up, so it
  can't play" and it counts in "on N phones"; an unbound phone is "not sent - no Spotify app is set up" (no app) or
  "not sent - Spotify is off on this phone". §3.6's sentence amended; `the_cards_and_check_now`'s last block (and the
  bound phone still getting the entry) pinned.
- **L2** (the binding read before the network): the add decides each picked phone's tick inside its `BEGIN
  IMMEDIATE` transaction with the gate's own `EXISTS` (`PLAYS_SPOTIFY`); the up-front read only refuses a phone that
  doesn't exist.
- **L3** (the DNS note before step 3 makes it true): removed from `device_detail.html`; its test now pins its absence;
  owed to step 3 (above).
- **L4** (copy): `add_refusal` - `Quota` "Spotify's limit for this app was reached - try again in an hour.", `Status`,
  `TooBig`, `BadAnswer` "Spotify couldn't open it just now - try again." (no status code on the form); the entry page's
  status card is "Episodes" for a Spotify podcast or episode (`list_title`), "Tracks" for the rest; `no_widevine` reads
  "this phone can't play Spotify (its web player lacks the copy protection Spotify needs)"; the binding hint is two
  short sentences ("Use your child's own Premium account: the phone can read its playlists. Accounts are on the
  Spotify page.").
- **L5** (test gaps): `the_size_the_budget_and_the_entry_limit` (a phone whose library would pass 3 MB named and not
  ticked; the shared budget - `Accounts::set_add_budget`, cfg(test) - outlasted by a held check and a held short link:
  "Spotify couldn't be reached - try again.", nothing stored; 200 entries refused after the check); the 401 and quota
  rows in `the_add_form_refuses_with_nothing_stored`; the three-phone wording ("Ella's, Kari's and Ola's phones all
  play as Ella") and every phone word (`ok`, `reauthorize`, `refused`, `no_widevine` with no "Widevine" on the page,
  `unreachable`, `quota`) in `the_device_card_binds_a_phone_to_an_account`; `/music/spotify` in
  `tests::shell::every_page_marks_its_own_tab`.

## Step 3 implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of step 2b and its fix round (`45a6b51`), one commit across `server/`,
`launcher/` and `music/`: §11 step 3 / §8, the contract. No new dependency on any side, no migration (0073's
`device_policy.music_spotify_account` is the binding). Nothing plays yet: no renderer, no WebView, no fake, and the
token reaches the music app's bridge type only.

- **Server.** `POST /api/devices/music/spotify/token` (`handlers::music_api::spotify_token`, in `device_routes`, behind
  `require_device_token`; always JSON, `Cache-Control: no-store`): 404 `off` when the phone isn't bound or there is no
  app (checked first, not counted), then the per-phone limit (`Accounts::phone_ask`: 20 a rolling hour, a refused ask
  not counted, 429 `busy` with `retry_after_s` rounded up to when the oldest ask leaves the hour), then
  `Accounts::access_token(account, PHONE_TOKEN_MIN_LEFT = 10 min)` under the route's own budget
  (`PHONE_TOKEN_BUDGET` = 10 s; step 1's QA note): 200 `{"status": "ok", "access_token", "expires_in_s" (what is
  left), "account": "<id>:<generation>"}`; `Off`/`NoAccount` 404 `off`; `Reauthorize`/`AppRefused` 409
  `reauthorize`; Spotify's 429 429 `busy` (its `Retry-After`, else 30 s); `Key` 503 `key`; a database error 500
  `unreachable`; anything else - the network, 5xx, `bad_answer`, the budget - 502 `unreachable`. The mint is 2b's
  spawned `MintJob`, so a refresh the budget gives up on finishes and is kept for the next ask. The token is never
  stored or logged (a debug line with the device and account ids only). `music::MusicPolicy.spotify` =
  `DevicePolicy.music_spotify_account.is_some()` through `policy_music`. `handlers::device_api`: `plays_spotify` (the
  same column), `compute_dns_filter_version` hashes `\nspotify:true` while bound, and `effective_blocklist` leaves out
  every domain `spotify::is_spotify_host` names (categories and custom blocks) for a bound phone only. `spotify.rs`:
  `HOST_SUFFIXES`, `EXACT_HOSTS`, `is_spotify_host` (equal to or under a suffix - Spotify's own parents included -, an
  exact CDN host exactly; `akamaized.net` stays delivered, the launcher exempts the exact hosts before its walk), the
  limit and the budget. `templates/device_detail.html`: the DNS note owed from 2b (qa-26c-step2b-code L3) is back in
  the filter section while the phone is bound. `testdata/`: `spotify_hosts.json` (§6.2's block byte for byte,
  provisional), `music_state.json` + `"spotify": "ok"` after `storytel`, `music_library.json` regenerated
  (`MUSIC_LIBRARY_SNAPSHOT_WRITE=1`; version `ceca94f70db5e7a0`) with entry 6 "Kveldsliste"
  (`spotify:playlist:FAKEplaylist0000000001`, Musikk, items `11ee11ee11ee11ee`, `"spotify": "list"`) and entry 7 "Fake
  Artist" (`spotify:artist:FAKEartist000000000001`, items `22dd22dd22dd22dd`, `"spotify": "context"`), both `cache` 0,
  resume on, order auto, no cover, `spotify` between `lan` and `sort` and on them alone; the phone is bound to a fake
  account row (`x'00'` ciphertext, never opened). `music_listing.json` unchanged.
- **Launcher.** `aidl/me/vibb/bridge/IMusicBridge.aidl` (the import, `getSpotifyToken` appended after `wantArt`) and
  the new `ISpotifyTokenCallback.aidl`; `music/MusicServer.kt` (`spotifyToken()`, `@POST`); `MusicBridgeService.getSpotifyToken`
  (`check()` first, then `answerSpotify` on IO: no server set up, a transport failure or `SPOTIFY_TOKEN_TIMEOUT_MS`
  (10 s) -> `launcher`; no cache, no store, the log lines name the exception class only); `music/SpotifyTokenCourier.kt`
  (pure `spotifyTokenAnswer(httpStatus, body)` -> `SpotifyTokenAnswer`, redacted `toString`); `music/SpotifyHosts.kt`
  (`SPOTIFY_HOST_SUFFIXES`, `SPOTIFY_EXACT_HOSTS`, `isSpotifyHost`); `server/DnsFilterEngine.kt` (`classify` ->
  the pure top-level `classifyDomain(blocked, domain, spotify)`: FCM's hosts always, Spotify's only with `spotify`,
  both before the suffix walk; the flag from the policy's `music.spotify`, set by every fresh sync and read from the
  cached policy in `loadFromDisk`); `MdmSyncWorker` sets it before `refreshIfNeeded`; `music/MusicPlan.kt`
  (`musicSpotify(element)`, read on its own and leniently; `musicState` passes the music app's `spotify`);
  `server/dto/PolicyResponse.kt` (the `music` doc: `spotify` and its readers - the field stays raw JSON);
  `server/EnforcementPlan.kt` (`webViewProvider` in `neverRestrict`) + `AppEnforcer.kt`
  (`webViewProviderPackage()`: `WebView.getCurrentWebViewPackage()` at each apply, off main). Test resources:
  `bridge_contract.json` (`spotify_token`), `spotify_hosts.json`.
- **Music.** The same two AIDL files byte for byte; `model/Library.kt` (`Source.SPOTIFY`; `Entry.spotifyMode`; a Spotify
  entry needs `spotifyTarget` and a `spotify` of `list`/`context`, else hidden with its id kept; cache read as 0, never
  `lan`; the field on another source is ignored; an unknown source is still dropped with its id kept);
  `spotify/SpotifyRef.kt` (`SpotifyKind`, `SpotifyRef`, `spotifyTarget`, `uriOf` - show and episode entries to
  `spotify:episode:`, step 2a's correction -, `spotifyEpisodes`, the mode constants); `logic/Spotify.kt`
  (`SPOTIFY_PLAYABLE = false`, `isSpotify`); `data/Snapshot.kt` (`visible`: a Spotify entry's listed items in both
  views when `spotifyPlayable`, a field defaulting to `SPOTIFY_PLAYABLE`, else none - so the library parses and shows
  nothing); `logic/PlayOrder.kt` (a show newest first, every other kind the listing's order); `logic/Storytel.kt`
  `fetchable` false for Spotify and `logic/Offline.kt`'s doc (cache 0: no keep, no plan, no prune);
  `logic/NewItems.kt` (`newsEligible`/`listedOf` take a listed Spotify item once `spotifyPlayable`);
  `logic/LibraryView.kt` (`Kind.SPOTIFY`, `Hint.Started.episodes`); the UI's three `when`s (`kind_spotify`;
  `hint_track`/`hint_episode`, `track_of`/`episode_of`, `new_track`/`new_episode` by `spotifyEpisodes`);
  `sonos/PlayBody.kt` (`speakerDropReason` = `spotify`); `logic/Report.kt` (`spotify`, default `none`);
  `bridge/SpotifyBridgeToken.kt` (`TokenResult`, the pure `tokenResult`, `SpotifyBridgeToken.ask`/`get` with the 10 s
  wait; used by nothing yet); strings `kind_spotify` "Spotify" in both locales; test resources `music_library.json`,
  `bridge_contract.json`, `spotify_hosts.json`.
- **CI** (§8 item 6): `launcher.yml` and `music.yml` watch the three `spotify_hosts.json` (and `music.yml` the
  launcher's `bridge_contract.json`, as before), `server-ci.yml`'s build the two app copies; root `CLAUDE.md`'s shared
  files and CI paths say so. `server/testdata/spotify/` and `scripts/fake-spotify.py` are step 4's.
- **Tests.** Server: `spotify::tests::hosts_match_the_contract` (the constants against the file, both app copies byte
  for byte, the rule), `phone_asks_are_limited_per_phone`; `tests::music_spotify::phones` -
  `the_token_route_answers` (off unbound and without an app, ok with the exact body, kept above 10 minutes and
  refreshed under, busy with Spotify's `Retry-After`, unreachable on a 503, reauthorize on `invalid_client` and
  `invalid_grant` and then with no request, key, off after Remove, 401 without a bearer; every answer `no-store` JSON,
  no token outside ok), `the_token_route_is_limited_per_phone`, `a_slow_refresh_is_unreachable_then_kept`,
  `the_policy_and_the_blocklist_follow_the_binding` (`music.spotify`, the version moved by bind and back by unbind, an
  unbound phone's version unchanged by step 3, the delivered list without Spotify's hosts for the bound phone only);
  `music_library_snapshot` (the Spotify entries and their key set, `music.spotify` true),
  `library_lan_only_for_a_lan_rss_feed`, the policy's music object in `tests::music` and `policy_json_keys_snapshot`,
  the device card's DNS note. Launcher: `SpotifyTokenCourierTest`, `SpotifyHostsTest`, `DnsFilterEngineTest`,
  `EnforcementPlanTest` (the WebView provider), `PolicyResponseCompatTest` (`spotify`), `MusicPlanTest` (three AIDL
  files, `getSpotifyToken` last, 13 bridge methods each `check()` first, `spotify_token` against its constants, the
  host list against the music app's and the server's, the snapshot's listings 6 and 7, `musicSpotify`, `music_state`
  with `spotify`), `MusicFileDownloadsTest`'s fake server (the new method). Music: `SpotifyRulesTest` (new: the grammar, `uriOf` with an episode entry, hidden until step 5b on
  both renderers, both views and the room's drop once playable, the natural order per kind and the setting, never
  fetchable or kept, NY gated, the words), `ContractFilesTest` (the entries, a Spotify entry without its mode or
  target hidden with its id kept, cache 0 and no `lan` whatever is sent, `spotify_hosts.json` equal to the server's),
  `BridgeTest` (three AIDL files, appended, `spotify_token` and `tokenResult`), `ReportTest`, `NewItemsTest`
  (`keyById`), `EpisodeArtTest` (`wantArt` then `getSpotifyToken` last), `OfflineTest` (its unnamed entry is now 99:
  7 is the snapshot's artist).
- **Validation.** Server (from `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` the 12 known
  warnings, none new; `cargo test` 751 passed, 1 failed - `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`
  (main's timing test, the tolerated one). Music: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true` BUILD SUCCESSFUL, 624 JVM tests, lint 0 errors (its 39 warnings none in a touched line).
  Launcher: `./gradlew assembleDebug testDebugUnitTest` BUILD SUCCESSFUL (the debug build on the tsnet stub), 1108 JVM
  tests.

Deviations from the spec (each with its reason):
1. **`ISpotifyTokenCallback.expiresInMs` also carries `busy`'s wait** (the server's `retry_after_s` in ms; §8 said "0
   unless ok"): §5.5's `Busy(retryAfter)` and §2.6's `retry_after_s` had no field in the callback, and an AIDL file is
   frozen once shipped (methods only appended). The doc comment says so; the courier and `tokenResult` read it.
2. **The DNS version hashes the binding only while bound** (`\nspotify:true`), not `spotify:<bool>` always: an unbound
   phone keeps the version it had, so the server's upgrade doesn't make every phone refetch its 100k-domain list; bind
   and unbind still each move it (tested).
3. **The token route's order and edges** (§2.6 names none): binding and app first (`off` is never counted against the
   limit), the limit before any token work, a database error 500 `unreachable`, Spotify's 429 without a numeric
   `Retry-After` 30 s, the route's own 10 s budget (502 `unreachable`; `Accounts::set_phone_token_budget` for the
   tests). `expires_in_s` is whole seconds left.
4. **`spotify::hosts` is three items** (`HOST_SUFFIXES`, `EXACT_HOSTS`, `is_spotify_host`), and the server's test also
   compares both app copies byte for byte (like `phone::tests`), which is what `server-ci.yml` watching them needs.
5. **The launcher reads `music.spotify` through a flag, not the cached policy per query**: `DnsFilterEngine.classify` is
   the VPN's per-packet path; the flag is set at every fresh sync and from the cached policy when the VPN loads its
   list (the cache is only written by the sync). `musicSpotify` reads the field on its own (`MusicPolicy` is
   unchanged: a malformed other field must not lift the exemption, nor `spotify` fail the music sync). `PolicyResponse`
   keeps `music` as raw JSON; only its doc changed.
6. **The courier's mapping where §6.1 is silent**: an `ok` that isn't whole (no token, life outside 1..86 400 s, no
   `<id>:<generation>` account, not HTTP 200) is `launcher`; without a status word an older server's 404 is `off`, a
   5xx `unreachable`, the rest `launcher`; `busy` without a usable wait 30 s.
7. **`logic/Spotify.kt` now** (in step 4's list) holds `SPOTIFY_PLAYABLE`, because step 5b flips it there; it is a
   `val` (no constant-condition folding), and `Snapshot.spotifyPlayable` / the news rules' `spotifyPlayable` take it
   as a default so the tests pin 5b's behaviour already.
8. **NY is gated too** (§4.3 makes a listed Spotify item eligible): while nothing plays, a Spotify listing is only
   acknowledged, so the first listings after the flip don't flood Nytt with a whole playlist.
9. **Small UI branches for `Kind.SPOTIFY`** (step 5b's files; needed to compile): `Hint.Started.episodes` (default
   false) picks `hint_episode` for a show or episode entry, the player and Nytt pick by `spotifyEpisodes`; only
   `kind_spotify` is added - `hint_spotify_picks` and the state lines are steps 6 and 5b's.
10. **`PlayBodies.speakerDropReason` = `spotify`** (§4.2, not in step 3's file list): so "visible in both views" is safe
    the moment 5b flips the constant.
11. **The music app's `SpotifyHosts.kt` and its `SpotifyHostsTest` are not built**: the constant is step 4's file
    (`M/spotify/SpotifyHosts.kt` + `WebPolicy.kt`); step 3 lands the music copy and `ContractFilesTest`'s equality
    with the server's. §8 item 5's "each side's constant" holds for the server and the launcher now, the music app in
    step 4.
12. **Report**: `spotify` is always sent, `none` by default (the service's file is step 5b's).
13. **The snapshot's ids, names and versions** (§8 names targets and modes only): 6 and 7, Musikk, `11ee…`/`22dd…`, no
    cover (so `covers` and the string replacements of `OfflineTest`, `BookmarksTest` and `ContractFilesTest` still hit
    only what they meant), the phone bound to a fake account.

Found in the spec:
- §8 item 2's callback could not carry §2.6's / §5.5's `busy` wait (deviation 1).
- §6.2's `spotify:<bool>` in the version would have made every phone refetch its blocklist at the upgrade (deviation 2).
- §8 item 6 has `server-ci.yml` watch the app copies, but no server test read them (deviation 4 makes it meaningful).
- §8 item 5's music `SpotifyHostsTest` checks a constant that §11 puts in step 4 (deviation 11).
- Nothing in §11 step 3 was impossible.

Owed: step 4 (the music app's host constant and `WebPolicy`, `TokenCache` over `SpotifyBridgeToken`, the fake and
`server/testdata/spotify/`'s CI path); step 5b (`SPOTIFY_PLAYABLE = true`, the outcome file behind the report's
`spotify`); step 6 (the context item, `hint_spotify_picks`); step 7 (`server/CLAUDE.md`, `launcher/CLAUDE.md`,
`music/CLAUDE.md` for the token route, the courier, the gated exemption and the WebView provider). **The host list is
provisional** (its comment says so; unverified as a whole): the spike's DNS capture replaces the three copies (and the
server's and launcher's constants) in one commit, at the latest in step 6, before any stable tag. [device check]: the
WebView provider read on the Jelly Star (`getCurrentWebViewPackage`), and the exemption with the family's categories on
(QA checklist item 15).

### Fix round after QA's code review (qa-26c-step3-code, 2026-10-13)

All six findings fixed; none disputed. The shared files are unchanged (still byte-identical).
- **L1** (the launcher's flag unpinned): `DnsFilterEngineTest`'s source scan pins `MdmSyncWorker`'s
  `DnsFilterEngine.setSpotify(com.kidslauncher.mdm.music.musicSpotify(freshPolicy.music))` before `refreshIfNeeded`,
  `loadFromDisk`'s `spotifyOn` from `cachedPolicy()`, `setSpotify`'s assignment and `classify` passing the flag.
- **L2** (only the AIDL's tail pinned): `MusicPlanTest` and `BridgeTest` each pin `IMusicBridge`'s whole ordered
  sequence of 13 methods (`version` ... `wantArt`, `getSpotifyToken`); an append adds one name at the end.
- **L3** (a refresh behind a sweep answers `unreachable`): taken with the optional part - `PHONE_TOKEN_BUDGET` is **8 s**
  (under the launcher's and the music app's 10 s, so the server's 502 reaches the phone first); §2.6 says the refresh can
  outlast the budget behind the gate and is kept, §5.5 gives step 4's `TokenCache` one retry about 5 s after an
  `Unreachable` at a play start; §11 step 4 names it. The second gate lane stays out (§0.3's one gate).
- **L4** (a 0/0 `downloads` row per Spotify entry): `downloadPlan` skips Spotify entries (a copy that somehow exists is
  pruned); `SpotifyRulesTest`'s never-fetchable test pins no state row, nothing wanted and that prune.
- **L5** (the override unsaid where the block is typed): `/dns?device=<id>` marks each of the bound phone's custom blocks
  `is_spotify_host` names "- not applied while Spotify is on for this phone" and lists the site-wide ones it overrides
  ("Not applied on Ella while Spotify is on for this phone (its Music card): ..."); nothing marked unbound.
  `tests::music_spotify::phones::the_web_filter_page_says_what_spotify_overrides`.
- **L6** (spec body vs the build): §2.1 (`music.spotify` follows the binding, 2b L1's case), §2.6 (the budget, the gate,
  the check order), §5.5 (`busy`'s wait, the retry), §6.2 (`\nspotify:true` while bound, the rule, the constants per
  side, the `/dns` marks), §8 items 2 (the callback's doc as built, the full-sequence pin) and 5 (who compares what; the
  music `SpotifyHostsTest` in step 4), §10 (step 3's rows as built), §11 steps 3 and 4 (`logic/Spotify.kt`,
  `sonos/PlayBody.kt`, `sync/DownloadPlan.kt`, the `/dns` page; step 4's `SpotifyHosts.kt` + `SpotifyHostsTest` and
  the retry). §4.4 stands as written after L4.

## Step 4 implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of step 3 and its fix round (`726f533`), one commit: §11 step 4, the music
app's Android-free Spotify core and the fake. No new dependency (OkHttp, kotlinx.serialization and coroutines, all
already in the build; `org.json` is a stub in the JVM tests, so the pure code reads JSON with kotlinx), no contract
file changed (§8's files are byte-identical as step 3 left them), nothing Android-side (no WebView host, renderer or
service change: step 5a), `SPOTIFY_PLAYABLE` still false. The launcher's tests read no file this commit touches.

- **`M/spotify/SpotifyHosts.kt`**: `SPOTIFY_HOST_SUFFIXES`, `SPOTIFY_EXACT_HOSTS`, `isSpotifyHost` - the server's and
  the launcher's rule; `SpotifyHostsTest` checks the constants against the music copy of `spotify_hosts.json` (§8
  item 5's music half, owed by step 3).
- **`M/spotify/SpotifyEndpoints.kt`**: the Web API base, `SDK_URL`, `FAKE_HOST` (`fake-spotify.invalid`) and the debug
  switch `debugBase` with exactly its six readers (§9.3): `apiBase()`, `apiOverLan()`, `sdkUrl()`, `webHosts()`,
  `webProxy(url)`, `debugTokens()`, each through one parse that takes only `http://` on 10.0.2.2 or loopback without
  user info. Nothing assigns it yet (`src/debug`'s `DebugSpotify.kt` is step 5a's).
- **`M/spotify/SpotifyHttp.kt`**: the seam (`SpotifyHttp`, `SpotifyRequest`, `SpotifyResponse` with `Retry-After`,
  both printing no token, body capped at 64 KB) and `OkHttpSpotifyHttp` (redirects never followed, the User-Agent on
  every request, an empty body for a body-less PUT/POST, the call timeout).
- **`M/spotify/SpotifyApi.kt`**: `PlayBody` (`Uris` - play_request.json's exact body - and step 6's `Context`),
  `play`/`transfer`/`queue` (§5.4; the bearer header here and in `StorytelApi.kt` only), `classify` into `PlayOutcome`
  (204 ok, 404 device unknown, 401, 403 `PREMIUM_REQUIRED` -> not premium and any other 403 -> refused - never an item
  error, QA M5 -, 429 `QUOTA_EXCEEDED` -> quota, else rate limited with `Retry-After`), and the suspend `start` with
  §5.4 step 3's retries inside the start's deadline: a 401 renews the token once; a 404 transfers once, awaits the
  transfer's 204, then `awaitDevice` (a state or `ready`, at most 3 s) and plays once more; a `Retry-After` of at most
  5 s is waited once; each call at most 10 s cut to the deadline. `StartResult.problem()` maps to §5.8. Also
  `debugToken`/`tokenAnswer`: the fake's token route read in the server route's shape (§2.6), for `debugTokens()`.
- **`M/spotify/SdkState.kt`**: the page's post parsed (`Inactive` for `null`, `SdkState` for an object with a boolean
  `paused` and a numeric `position`, else junk): 8 KB, uris checked, at most 3 next/prev, names 200 code points, 10
  artists, times clamped to a day; `title` and `listingTitle`/`cutTitle` - the server's `track_title` + `cut_title`
  exactly (Unicode White_Space split, controls dropped after the join, 200 code points).
- **`M/spotify/SpotifyTracker.kt`**: §5.4's state machine (modes `IDLE`, `STARTING`, `OURS`, `ENDED`, `LOST`; events
  `Started`, `ItemChanged(auto)`, `Unplayable(stop)`, `Paused(ours)`, `Resumed`, `NotOurs(playing)`, `Ended`,
  `NewWindow`, `QueueAdd`, `Look`, `Elsewhere`; `measured` only from states of ours, as received). The strict ownership
  rule: our sent uri or the expected next by uri, `linked` or title + duration within 2 s; anything else is `NotOurs`
  and from then on every playing state is paused until our next play - also after `Ended`, three unplayable and a
  confirmed `null`. The end rule, the unplayable rule (a `playback_error`, or a skip past ours before it played 3 s;
  three in a row stop), the `null` confirmed by a second one 2 s on or a `not_ready`, the add-to-queue one track
  ahead (`queued` when the add's 204 comes; playing an item is knowing it).
- **`M/spotify/TokenCache.kt`**: §5.5 over an `ask` function (the bridge, or `debugToken`): the kept token with 5
  minutes left, one ask in flight shared by its waiters and kept when its waiter gave up at the deadline, the
  cooldowns (`reauthorize` 10 minutes, `busy` its wait, `unreachable` none, `off` clears), **the one prompt retry 5 s
  after `unreachable` at a start** (qa-26c-step3-code L3), and the refresh ahead of expiry while playing
  (`prefetchDue`, `nextPrefetchAtMs`, `prefetch`: at 10 minutes left, again every 2 minutes until the old token
  expires; QA M2). `TokenResult.problem()` maps to §5.8. The debug log callback gets fixed words only.
- **`M/spotify/WebPolicy.kt`**: `WebPolicy.decide(url)` -> `Asset(path)` (our two assets), `NotFound` (anything else on
  our origin), `Pass` (https on 443 to a Spotify host; `blob:`/`data:`), `Proxy(url)` (the fake's https name, debug
  switch only), `Block` (everything else: http, odd ports, user info, other hosts, other schemes); `WebPolicy.current()`
  reads `webHosts()`/`webProxy`. `SpotifyPage`: the origin, `BASE_URL`, the bridge's names both ways (`BRIDGE`
  `VibbSpotify`, `FROM_PAGE`, `TO_PAGE`, `ERROR_KINDS`), `PLAYER_NAME` "Vibb", `csp(sdkOrigin)` (§5.2's policy),
  `html(template, sdkUrl)` (the substitution) and `call(name, args)` (an `evaluateJavascript` call with every argument
  a quoted JSON literal, U+2028/2029 escaped).
- **`M/logic/Spotify.kt`**: `spotifyPlayability` (STREAM or NEEDS_NETWORK, never GONE: H1), `spotifyNetworkLine`,
  `contextItem` (key `context`, the entry's name, no url, no duration: H3), `SPOTIFY_WINDOW_MAX` 50 and `playWindow`,
  `nextQueueAdd` (one track ahead), the start's budgets (25 s, ready 20 s, a call 10 s, the first state 10 s, the
  transfer's settle 3 s, the 5 s post and 30 s gap, the 300 ms step coalescing, the 5-minute idle host),
  `SpotifyLine`, `SpotifyWords`, `SpotifyProblem` (§5.8's table row by row: the line and the stored word),
  `sdkErrorProblem`, `spotifyTileLine` (the stored outcome on every tile) and `SpotifyOutcome` (the
  `spotify_state.json` round trip, written only when the word changes; L8).
- **`assets/spotify/player.html`, `player.js`** (GPL, ours): the page per §5.2/§5.3 - a CSP meta, no inline script or
  handler, `player.js` before the SDK (`{{SDK_URL}}`, unpinned), the `VibbSpotify` bridge (`ready`, `notReady`,
  `needToken`, `state`, `error`) and `window.vibb`'s ten entry points; `getOAuthToken` asks the host and the answer goes
  only to its callback; `new Spotify.Player({name, getOAuthToken, volume: 1.0})` (no `enableMediaSession`); the state
  compacted to `SdkState.KEYS` on every `player_state_changed` and every 5 s while playing; the SDK's four errors,
  `autoplay_failed` and a script that failed to load (a capture-phase `error` listener) as `error(kind, message)`.
- **`src/test/resources/spotify/`**: `play_request.json` (the exact play, context play, transfer and queue requests),
  `play_answers.json` (every answer the start reads), `token_answers.json` (the token route's six answers), and
  `sdk_states/` - 16 sequences: `start`, `advance`, `end_of_window`, `end_of_window_gap`, `end_of_queue`, `pause`,
  `elsewhere`, `elsewhere_not_ready`, `relinked`, `relinked_not_by_name`, `relinked_other_artist`, `unplayable`,
  `unplayable_reset`, `autoplay`, `autoplay_window`, `transfer_in` (each step a command or a posted state with the
  events it must give, and the end's index, mode and measured position).
- **`scripts/fake-spotify.py`** (GPL, stdlib, `-I`, port 8731): the accounts service (`/authorize` auto-consent with
  `--as` and `--deny`, `/api/token` with the S256 check, single-use codes, `--rotate`, `--access-ttl`,
  `--expire-refresh`, `--bad-client`), the Web API subset (`/v1/me`, the fixture playlist with its owner check and
  `--snapshot`, album, tracks, track, artist, artist albums, show, episodes, episode; the play body checked against
  play_request.json's shapes, 404 for an unknown or another account's device, `--not-premium`, transfer, queue,
  `--rate-limit N`, `--quota`), the stub SDK (`/sdk/spotify-player.js`: the documented `Spotify.Player` surface over a
  long-poll, a tone per track from `/fake/audio/<id>.wav` with Range, the fixture's length or `--track-seconds`;
  `--elsewhere`, `--autoplay`, `--transfer-in`, `--unplayable`, `--relink`, `--no-eme`, `--not-premium`, `--slow-ready`,
  `--token-ttl`), `/fake/token?user=kid`, `--print-listing ENTRY [--tracks N]`, `--print-library`, and `--selftest`
  (8 parts: the fixtures and fake values, PKCE with RFC 7636's vector, rotation and refusals, every route against its
  fixture, the device and the play body, the tone, every option, the stub against what `player.js` uses, the debug
  feed's documents). `.github/workflows/music.yml` runs it after the build and watches `server/testdata/spotify/` and
  the script; root `CLAUDE.md`'s `scripts/` and CI lines say so.
- **Tests** (music, 9 new or extended classes): `SpotifyTrackerTest` (every `sdk_states/` sequence through
  `SdkState.parse`; autoplay after the window and the queue paused and never measured; relinking three ways and not by
  name alone; three unplayable in a row stop and a fresh play restarts the count; the `null` look; nothing measured
  from a start, a stale or a foreign state; the queue add one ahead; an idle player; an episode relinked by its name),
  `SdkStateTest`, `SpotifyApiTest` (the exact requests, every answer, 401 once, 404 -> transfer -> its 204 -> the
  settle -> once, `Retry-After`, the deadline, the problems, the fake's token route), `SpotifyHttpTest` (the wire bytes
  = play_request.json, no redirect followed, `Retry-After`, the body cap, the timeout, a whole start over loopback),
  `TokenCacheTest` (the margin, one ask in flight, the cooldowns, the prompt retry, the deadline, the prefetch and its
  2-minute retry, redaction), `SpotifyHostsTest`, `WebPolicyTest` (our assets, Spotify's hosts, http and the rest
  refused, the fake only with the switch, the six readers, the CSP and the substitution, the quoted calls),
  `PlayerJsTest`, `SpotifyRulesTest` (+7: the gate, the context item, the window and the add, the budget, §5.8's table,
  the tile line, `SpotifyOutcome`), `SourceScanTest` (the bearer in `SpotifyApi.kt` too; Spotify's debug base, one
  declaration and six readers, never assigned in main; the Spotify holders' redacted `toString`s; no `Log.` in
  `spotify/`; no redirect followed). Checked by mutation (each red): the relink without its duration, a stop after
  four, the end rule ignoring our pause, no prompt retry, the prefetch at 5 minutes, a transfer-in read as an end.
- **Validation** (from `music/`): `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true` BUILD SUCCESSFUL, 679 JVM tests (624 before), 0 failures; lint 0 errors, the same 39
  warnings as step 3, none in a file this step touches. `python3 -I scripts/fake-spotify.py --selftest` "FAKE SPOTIFY
  SELFTEST OK"; `fake-storytel.py` and `fake-sonos.py --selftest` OK (unchanged). The launcher's suite not run: no file
  its tests read changed.

Deviations from the spec (each with its reason):
1. **`SdkState.contextUri` and the post's `context` key** (§5.3's JSON lists no context): §5.9's context ownership
   (step 6) needs the state's context, and the page's post is pinned now (`PlayerJsTest`, `SdkState.KEYS`), so it is in
   from step 4; the tracker doesn't read it yet.
2. **The page template has two slots of one value** (§5.2: "the only substitution"): `{{SDK_URL}}` in the script tag
   and `{{SDK_ORIGIN}}` (its origin) in the CSP's `script-src`/`frame-src`, so the debug build's fake origin needs no
   second template; a release page's CSP is §5.2's exactly (`PlayerJsTest`, `WebPolicyTest`).
3. **`WebDecision` has `NotFound` and `Proxy(url)`** besides §5.1's `Asset`/`Pass`/`Block` (§5.2's own 404 for our
   origin, §9.3's proxy), and `blob:`/`data:` pass (in memory: the SDK's media source, an inline picture; the CSP's
   `media-src blob:`, `img-src data:`).
4. **`SpotifyApi.start` is a suspend function with its waits as functions** (`tokens(renew)`, `awaitDevice(timeout)`,
   `sleep`); the blocking calls run in `runInterruptible(Dispatchers.IO)`. The 404 -> transfer path awaits the
   transfer's 204 and then `awaitDevice` (the renderer completes it on a state or `ready`, 5a).
5. **`TokenCache.get(deadlineMs, minLeftMs, atStart)`** (§5.1 wrote `get(minLeftMs, deadlineMs)`): the prompt retry is
   asked for by `atStart`; `prefetchDue`/`nextPrefetchAtMs`/`prefetch` are the M2 refresh's pure half (the renderer's
   timer and wake lock are 5a's). A refresh that brings the same token back (lasting no longer) counts as failed, so a
   server answering with its kept token can't loop; `reauthorize`, `busy` and `unreachable` during a refresh keep the
   old token until it expires.
6. **The tracker's readings where §5.4 is open:**
   a. the end rule's "last playing position within 3 s of the duration" carries that position on by the time since its
      state - for reading the end only, never a saved position: the page posts every 5 s, so the last post can be up to
      5 s short and the literal rule would read the end of a list as a plain pause. And only after a playing state: a
      track paused near its end that a transfer-in replaces hours later didn't play out (it must not start the next
      window);
   b. "Spotify skipping past one of our uris": a later uri of the window Spotify knows (by uri only) is ours, every item
      between it and the current one unplayable; at a start only when it plays and names the sent uri among its `prev`
      (else it is a stale state of what played before - a step back would read as a skip); "played 3 s" is the
      furthest playing position past the first, or the playing time since the last playing state;
   c. relinking (`linked`, title + duration) applies to the sent item too, not only the expected next; an episode
      matches by its name alone too (the server lists an episode by its name; the SDK may name the show as an artist);
   d. `NewWindow(index)` also when ours played out and a track that isn't ours plays (Autoplay after the window: §5.4's
      "a failed add is a gap" with Autoplay instead of a pause); after the queue that is `NotOurs` + `Ended`;
   e. while `IDLE` (nothing sent since the queue was loaded) every playing state is `NotOurs` - a transfer-in to an
      idle host, paused at once (§5.7's accepted moment);
   f. "three in a row" restarts on a fresh play of the kid's (`played(fresh = true)`), not on the renderer's own move-on;
      one `Unplayable` per item (a `playback_error` and then Spotify moving on is one);
   g. a playing state while our pause is pending is passed over (from before the pause took effect); a paused state
      under 1 s reads as position 0;
   h. `played()` takes no position and `measured` stays null until a state of ours: the start position is the stored
      bookmark, not a measurement.
7. **`SpotifyLine.UNAVAILABLE`** is the quota's line (§5.8: `SPOTIFY_NEEDS_INTERNET`'s carrier with "Spotify er ikke
   tilgjengelig nå"), so 5a can map it to that state with its own text; **`spotifyTileLine("no_widevine")`** is "spør en
   voksen" (§5.8's two tile phrases), the renderer's own line `NOT_ON_PHONE`; **a `busy` token** is `UNREACHABLE`
   (§5.8 has no row for it); **`spotifyNetworkLine`**: Wi-Fi when a network with internet is refused by the gate
   (metered without the switch, or roaming), else internet.
8. **The fake:**
   a. it answers from step 2a's fixture names (`playlist.json`, `playlist_items.json`, `album_tracks.json`,
      `forbidden.json`, ...), not §9.2's earlier list (`playlist_meta.json`, `playlist_items_1.json`, `playlist_404.json`,
      ...): 2a named them;
   b. the stub's own protocol is plain GETs (`/fake/device/connect?name=&token=`, `/commands?after=`,
      `/token?token=`), answered with `Access-Control-Allow-Origin: *`: the debug interceptor sees no request body
      (`shouldInterceptRequest` has none) and an `Authorization` header would cost a CORS preflight. The fake token rides
      in the stub's query to the fake only (fake values; the real SDK is Spotify's own);
   c. `GET /fake/as?user=kid|parent` switches the auto-consent while running (both logins in one §6j run);
      `--print-library` prints the library snapshot (entries 6 and 7 are Spotify's, as §6i copies it for Storytel);
      `--tracks N` makes `--print-listing` list N generated tracks (60 for the past-the-window line; 0 for the context
      artist's empty list); `--not-premium` is both the play's 403 and the SDK's `account_error`; `--elsewhere` and
      `--transfer-in` count from the device's first play;
   d. the selftest checks the stub's JavaScript as text (no browser in CI): the documented names, every method and
      event `player.js` uses, relative URLs, GETs only.
9. **`SourceScanTest`'s Spotify readers** name step 5a's files as their callers (`apiBase`, `apiOverLan`,
   `debugTokens`: `SpotifyRenderer.kt`; `sdkUrl`: `SpotifyWebHost.kt`; `webHosts`/`webProxy`: `WebPolicy.kt`) - 5a
   moves a name if its split differs.

Found in the spec:
- §5.3's post has no `context`, which §5.9 needs (deviation 1).
- §5.2's "only substitution" and the debug build's CSP host can't both hold with one slot (deviation 2).
- §5.4's end rule read literally misses the end with the page's 5 s posts (deviation 6a).
- §9.3's proxy can't carry a request body or decide CORS on its own: the stub uses GETs only (deviation 8b), and step
  5a's proxy must forward the `Range` header and pass back the status, `Content-Type`, `Content-Range`,
  `Accept-Ranges` and `Access-Control-Allow-Origin` (the tone's seek and the stub's fetches need them).
- §5.1's "`SpotifyTrackerTest` (synthetic sequences + the fake's recorded ones)": the stub can't run without a browser
  here, so `sdk_states/` are synthetic; recording the stub's sequences from the emulator is owed to step 5b's §6j run.
- §9.2's fixture list (deviation 8a).
- Nothing in §11 step 4 was impossible.

Owed: step 5a - the host applies `WebPolicy.current()` (frames and the service-worker client), serves the assets,
proxies the fake (above), maps the tracker's events (`Look` -> `vibb.state()` 2 s later; `NotOurs(playing)` ->
`vibb.pause()`; `QueueAdd` -> `SpotifyApi.queue` then `queued`; `NewWindow` -> a play from that index with
`played(fresh = false)`, an auto advance to the service; `Unplayable` -> `Error("unplayable")`, moving on only when the
tracker's index hasn't moved past it already), runs the prefetch timer under its wake lock, `DebugSpotify.kt` (and
`SourceScanTest`'s assignment list), the `RendererState` strings for `SpotifyLine`; step 5b - `SPOTIFY_PLAYABLE`, the
`spotify_state.json` file through `SpotifyOutcome`, §6j with the fake (its recorded state sequences); step 6 - the
context ownership through `SdkState.contextUri`. [unverified]: the 404's reason `NO_ACTIVE_DEVICE` in
play_answers.json, the real SDK's state shapes beyond the documented ones (`linked_from`), episodes in a `uris` list
(§3.1, `SHOWS_PLAYABLE`).

### Fix round after QA's code review (qa-26c-step4-code, 2026-10-13)

All ten findings fixed; none disputed (one detail chosen differently, L3). No contract file changed, `SPOTIFY_PLAYABLE`
still false, no new dependency. From `music/`: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` BUILD SUCCESSFUL, then `testDebugUnitTest --rerun-tasks`: 692 JVM tests (679 before), 0
failures; lint 0 errors, the same 39 warnings, none in a Spotify file. The three fakes' `--selftest`s OK under `-I`.
Checked by mutation (each red): a skip without `prev` evidence, no stale pre-play state, a trackless state read, the
shuffle flag ignored, "the same token" by its expiry, `initialization`/`account` swapped in `player.js`, no 403 retry,
the refused token retried.
- **H1** (a later uri of ours read as skipped tracks): a skip needs evidence - each item counted skipped named in the
  state's `prev`, none of them nor the state's track an add of ours (`skipProven`); the tracker remembers the uris it
  asked to add across plays (until seen playing, or `newDevice()`), and a leftover add - or any later uri of ours
  without evidence - is `NotOurs` plus `NewWindow` (from the next index when ours played out, else from the current
  item at its place: `NewWindow(index, resume = true)`); a start passes over the track of the last state seen before
  the play call (`played(.., positionMs)`) unless it is the sent track within 3 s of the sent position. The QA's two
  counter-examples are `SpotifyTrackerTest` cases (no `Unplayable`; `Started 1`, no `NotOurs`), with `sdk_states/`
  `queue_leftover` and `step_back_stale`, plus a skip `prev` can't prove and a new device's empty queue; the stub keeps
  Spotify's queue across a new play and plays it before the context (selftest part 7); QA checklist item 20. Step 4's
  deviation 6b is replaced by this rule (§5.4 amended).
- **M1** (shuffle and repeat): `player.js` posts `shuffle` and `repeat` (`repeat_mode`, at most 2), `SdkState` reads
  them (`reordered`; junk is off), `SdkState.KEYS` has 13 keys; the tracker reads nothing from a state with either on
  and emits one `ShuffleOrRepeat(index)` until the next play; `SpotifyApi.shuffle`/`repeat` and `start(.., settings =
  true)`: shuffle off then repeat off, each 204 awaited, sharing the play's retries (a 404 transfers, a 401 renews...),
  a failed one ending the start like a failed play; `play_request.json` has both requests; the fake's routes
  (`PUT /v1/me/player/shuffle`, `/repeat`, queries checked against the fixture, handed to the stub), `--shuffle`,
  `--repeat context|track`, the stub shuffling and repeating until told; `sdk_states/shuffled`; §5.4 step 5, QA
  checklist item 21. The QA's counter-example is a `SpotifyTrackerTest` case. (Two Web API requests per device
  session; when the renderer sends them - its first play per device, and on `ShuffleOrRepeat` - is step 5a's.)
- **L1** (a stale same-track state at a restart measured): with H1's start rule; the QA's test and `sdk_states/restart_stale`.
- **L2** (a track-less state paused ours): `SdkState.trackless` (no uri, no name) is passed over in a start and while
  ours plays; the QA's test and `sdk_states/trackless`.
- **L3** ("the same token" by its expiry): by the token's value (and account); a same token keeps the earlier of the two
  expiry estimates - the QA suggested the later; the earlier errs towards refreshing sooner, and the two differ by a
  second at most. A test with the same token one second "later".
- **L4** (the 401 renewal handing back the refused token): a renewed token equal to the refused one ends the start at
  once, as does a second 401: `StartResult.TokenRefused` -> `SpotifyProblem.REFUSED` (`SPOTIFY_ASK_ADULT`, outcome
  `refused`) instead of "trenger internett" - Spotify refused the account's token, an adult's matter; §5.4's 401 row,
  §5.5 and §5.8's table say what the renewal can and can't do, and the later contract note (`{"rejected": true}`
  through an appended bridge method).
- **L5** (event-to-kind pairs unpinned): `PlayerJsTest` pins each `addListener` event to its `report` kind.
- **L6** (a CSP violation silent): a `securitypolicyviolation` listener reports `error('csp', <directive> <blocked
  origin>)` - origin only, never a path or query; `csp` in `SpotifyPage.ERROR_KINDS`, no problem and no line
  (`sdkErrorProblem`), debug log only; §5.3, and §9.4 part B's "no `csp` line in the debug log".
- **L7** (one 403 decides "not Premium"): a 403 `PREMIUM_REQUIRED` is tried once more after 1 s inside the deadline
  (`PREMIUM_RETRY_MS`); another 403 isn't; §5.4 and §5.8 say so.
- **L8** (the spec body behind the deviations): §5.1's `WebPolicy` and `TokenCache` rows, §5.2's substitution, §5.3's
  post (`context`, `shuffle`, `repeat`) and error kinds, §5.4 (the skip evidence, the leftover add, the start rule, the
  end rule's carried position, the readings of deviation 6, shuffle/repeat, the 401 and 403 rows), §5.5 (`get`'s
  signature, the same token), §5.8's table, §9.2's fixture list, the stub's GET protocol and new options, §9.4 part B.

Owed by step 5a (from the QA's notes, not findings): the debug proxy logs the host only (the stub's query carries a fake
token) and forwards `Range` plus the status, `Content-Type`, `Content-Range`, `Accept-Ranges` and
`Access-Control-Allow-Origin`; the bridge's `state(json)` cap counts what `SdkState.MAX_BYTES` counts (UTF-16
characters); the token's deadline at a start leaves room for the play call (e.g. `get(deadline - SPOTIFY_CALL_MS, ..)`);
`newDevice()` on each new device id, `settings = true` on a device session's first start and after `ShuffleOrRepeat`;
`SourceScanTest`'s Spotify assignment check becomes the exact `listOf("DebugSpotify.kt")` once that file exists.

## Step 5a implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of step 4 and its fix round (`17a2543`), one commit: §11 step 5a, the music
app's WebView host and the Spotify renderer. No new dependency (the platform WebView only - no `androidx.webkit`;
nothing was impossible without it), no contract file changed, no service, `PlayerHub`, `Snapshot`/`Store`, UI screen
or `Report` site wired (5b), `SPOTIFY_PLAYABLE` still false. The launcher's tests read no file this commit touches.

- **`M/spotify/SpotifyWebHost.kt`**: the renderer's seam `SpotifyHost` (`events`, `alive`, `open()`, `call(name,
  args)`, `destroy(clear)`) and `HostEvent` (`Ready`, `NotReady`, `NeedToken`, `State` - already parsed by
  `SdkState.parse` -, `Error`, `Gone`); the Android `SpotifyWebHost`: one WebView made with the application context,
  never added to a view; §5.2's settings (JavaScript, DOM storage, media without a gesture, file and content access
  off, geolocation and multiple windows off, Safe Browsing off, `RENDERER_PRIORITY_IMPORTANT`, third-party cookies for
  the SDK's iframe, the default cache mode and user agent); the page from the `player.html` template
  (`SpotifyPage.html(template, SpotifyEndpoints.sdkUrl())`) through `loadDataWithBaseURL(SpotifyPage.BASE_URL, ...)`,
  and `vibb.connect("Vibb")` once the page finished loading; the interceptor applying `WebPolicy.current().decide` to
  every frame's requests and - through `ServiceWorkerController.setServiceWorkerClient` (platform API 24, process-wide,
  installed once) - the service workers' (`Asset` from the app's assets, `NotFound` 404, `Pass`, `Block` an empty 403
  and a counter, the host only in the log); the debug proxy (GET only, else 405; `Range` forwarded; the status,
  `Content-Type` with its charset, `Content-Range`, `Accept-Ranges`, `Access-Control-Allow-Origin` passed back; a 3xx
  or a transport failure 502, since `WebResourceResponse` refuses 3xx; the LAN client, no redirects, 45 s for the
  stub's 20 s long-poll; the host logged, never the URL); `shouldOverrideUrlLoading` true for everything;
  `onPermissionRequest` granting only `RESOURCE_PROTECTED_MEDIA_ID`, only to https `sdk.scdn.co` or our origin (the
  asking host in the debug log, step 5b's device check); `onRenderProcessGone` returning true and reporting `Gone`;
  the page bridge `VibbSpotify` (exactly `SpotifyPage.FROM_PAGE`, each validating - the device id's grammar, a request
  id 1..10^9, the state's cap counted in UTF-16 characters as `SdkState.MAX_BYTES`, an `ERROR_KINDS` word, the message
  cut to 200 - and posting to main; a destroyed page's late calls dropped by its generation); `destroy(clear)`
  (`vibb.disconnect()`, then with `clear` the cookies, web storage and the view's cache - also with no page alive, an
  earlier page's account being on disk).
- **`M/spotify/SpotifyRenderer.kt`**: `SpotifyRenderer` (`Renderer`, `kind = SPOTIFY`) with Android only at its edges:
  the `SpotifyHost`, the `SpotifyHttp` seam under step 4's `SpotifyApi`, a token ask under step 4's `TokenCache`, and
  `SpotifyPorts` (the main thread's timers and clock, focus, noisy, locks, `playAllowed`, the network gate, the
  bookmark's write, the stored outcome, the debug log; `AndroidSpotifyPorts` is the real one). The session's player is
  a `SimpleBasePlayer` made on first use from the main looper, its State read from `spec()` (§5.4's table: IDLE with
  no queue or after an error that destroyed the page, BUFFERING with play-when-ready while a start of ours runs - the
  host, the token, the calls, the first state - or a step coalesces, READY otherwise, ENDED; `showsPlaying =
  playWhenReady && !ended`; the transient focus loss as `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS`;
  local `DeviceInfo`, the audio attributes speech for a show); handlers only set intent and post their events, as
  `SonosRenderer`'s. Built as §5.4-§5.8 say:
  - **a start**: the renderer's own belt of `playAllowed` and the network gate (`spotifyNetworkLine`), the audio focus,
    then in parallel the page and its `ready` (at most 20 s; created only at a play) and `TokenCache.get(deadline -
    SPOTIFY_CALL_MS, atStart = true)` (the token's deadline leaves the play call its room); another account than the
    page's clears the page first; then `SpotifyApi.start` with the window (`playWindow`, 50 from the current item), the
    start position, `awaitDevice` (a state or `ready`, the timers' timeout) and `settings` = a device session's first
    play (a new `ready` device id: `tracker.newDevice()`) or after `ShuffleOrRepeat`, decided when the calls begin; on
    the 204 `tracker.played(index, index + window - 1, posMs, fresh)` and our first uri playing within 10 s (and the
    25 s budget), else "trenger internett". A second tap while a start runs sends nothing; a pause cancels it outright
    before its Web API calls and, during them, follows a sent play with `vibb.pause()` (a tap then revives it without a
    second call); steps coalesce for 300 ms into one play from the last target, and a step while paused only moves.
  - **playing**: pause, resume and seek within the item are `vibb.pause()`/`resume()`/`seek(ms)`; the natural advance
    sends nothing (`ItemChanged(auto)`); `QueueAdd` -> `POST .../queue` and `tracker.queued(i)` on its 204;
    `NotOurs(playing)` -> `vibb.pause()`; `NewWindow(i, resume)` -> a non-fresh play from `i` (at the measured place
    with `resume`); `ShuffleOrRepeat(i)` -> a play with `settings = true`; `Look` -> `vibb.state()` 2 s later;
    `Elsewhere` -> the bookmark, paused, `SPOTIFY_ELSEWHERE` (never a `vibb.pause()` then: the account's other player
    isn't ours to pause); `Ended` -> ENDED, no pause event with it; `Unplayable(i, stop)` -> `Error("unplayable")` with
    `i` current (below); the 30 s gap -> paused, `SPOTIFY_NEEDS_INTERNET`, `unreachable`.
  - **§5.6**: `AUDIOFOCUS_GAIN` (music, speech for a show; `setWillPauseWhenDucked(true)`, the renderer ducks) at a
    start and an SDK resume, abandoned on every pause; a loss `Paused(FAULT)`; a transient loss `vibb.pause()` with
    play-when-ready held and `Interrupted`, the gain `vibb.resume()` and `InterruptionEnded`; a duck `vibb.volume(0.3)`;
    `ACTION_AUDIO_BECOMING_NOISY` watched while playing -> `Paused(FAULT)`; the sleep fade steps `vibb.volume` from 1
    to 0 in 250 ms steps over 10 s, pauses, puts 1 back (`cancelFade` puts 1 back); `setAudioAttributes`,
    `setWakeMode`, `setPreferredAudioDevice` no-ops.
  - **§5.5, §5.7**: the token refreshed ahead of expiry while playing (`TokenCache.nextPrefetchAtMs`/`prefetchDue`/
    `prefetch` on the timers, at least 1 s apart), under the locks; the SDK's `needToken` answered from the cache
    (`null` on a failure); the locks held while a start runs or it shows playing, renewed by `saveNow` (the service's
    15 s tick), released on every pause, end, error, `Elsewhere`, release; the page destroyed 5 minutes after the last
    pause, at `suspended()`, on `Gone`, at no Widevine, on `off` (cleared) and at `release()`.
  - **positions**: `saveNow` writes only `tracker.measured` of the current item, as received (nothing before the
    first state of ours; the duration the SDK's, else the listing's) through `persistPlace`; `positionMs` extrapolates
    for display only.
  - **errors** (§5.8): `SpotifyProblem`'s line as the `RendererState`, its word through the outcome port (`ok` at every
    started play); no Widevine and the renderer process gone destroy the page and show IDLE.
- **`M/spotify/SpotifyLocks.kt`**: a PARTIAL wake lock (`vibb:spotify`) and a Wi-Fi lock, each at most 30 minutes
  without a renewal (`hold(true)` and `renew()` start the timeout again; the Wi-Fi lock's timeout is a main-thread
  timer, it has none of its own), released by `hold(false)`.
- **`M/playback/Renderer.kt`**: `RendererKind(val onPhone)` - `LOCAL(true)`, `SONOS(false)`, `SPOTIFY(true)`.
  **`M/playback/PlayerModel.kt`**: `RendererState(val remote)` - the four Sonos values `remote`, and
  `SPOTIFY_NEEDS_WIFI`, `SPOTIFY_NEEDS_INTERNET`, `SPOTIFY_UNAVAILABLE`, `SPOTIFY_ASK_ADULT`, `SPOTIFY_NOT_ON_PHONE`,
  `SPOTIFY_ELSEWHERE`, `SPOTIFY_STOPPED`; `rendererStateOf(SpotifyLine)` maps step 4's lines to them.
- **Strings** (`values`, `values-nb`): `renderer_spotify_*` - "Spotify - trenger Wi-Fi", "Spotify - trenger
  internett", "Spotify er ikke tilgjengelig nå", "Spotify - spør en voksen", "Spotify virker ikke på denne telefonen",
  "Spotify spiller et annet sted", "Spotify stoppet - trykk spill" (and §5.8's English); `ui/PlayerScreen.kt`'s
  `rendererStateText` maps the seven (its `when` is exhaustive).
- **`AndroidManifest.xml`**: `android.webkit.WebView.EnableSafeBrowsing` false and
  `android.webkit.WebView.MetricsOptOut` true.
- **`src/debug/.../DebugSpotify.kt`**: `applyBase` - `config.json`'s `spotify_base` into `SpotifyEndpoints.debugBase`,
  from the receiver and from `DebugStartup` at every process start (as `DebugStorytel`'s; the provider's comment and
  the feed's doc say so).
- **Tests**: `SpotifyRendererTest` (new, 29 cases, against a scripted page, a scripted Web API and a virtual clock,
  the test's `onEvent` playing `PlaybackService.onError`'s move-on): §11 5a's list - a cold start held 15 s shows the
  intent (BUFFERING, play-when-ready, `showsPlaying`, the locks and the focus) with exactly shuffle, repeat and one
  `PUT`, and a second tap sends nothing; steps within 300 ms coalesce into one play (and a paused step only moves);
  the window's end adds to Spotify's queue one ahead (a 60-track list) and the advance sends nothing; `Ended` (and
  Autoplay after it paused, nothing of it saved); focus loss and transient loss and a duck; noisy; the fade (0.5 at 5
  s, then 0, pause, 1) and `cancelFade`; a suspension destroys the page, writes the bookmark with force and nothing
  starts while suspended; a confirmed `null` -> `ELSEWHERE` with the bookmark kept and no pause sent, and a play takes
  it back at the bookmark; three unplayable -> stopped (three calls, the third's move-on only moves); plus step 4's
  events - `NewWindow(resume)` at the measured place, `ShuffleOrRepeat` -> `settings = true` (and on a new device's
  first play), `TokenRefused` -> `SPOTIFY_ASK_ADULT`/`refused` - and §5.4's table row by row (loaded, a start, playing,
  held through a 29 s gap and paused past 30 s, the start's 10 s for our first state, paused, `null`/foreign, ended,
  stopped), a pause before and during the calls, a new queue while the old call runs, a seek before a start's calls
  (the service's resume overlap), pause/resume/seek without a Web API call, only measured positions saved, the 5-minute
  idle page, the renderer process gone, no Widevine and no Premium, the network gate's two lines, another account's
  token clearing the page (at a start, and at the SDK's own ask), the token refreshed ahead of expiry under the wake
  lock, release. `SourceScanTest`: "no web views" became §7.2's rule (`WebView(`, `android.webkit` and
  `addJavascriptInterface` only in `SpotifyWebHost.kt`, no `addView` anywhere, every §5.2 setting pinned, no
  `androidx.webkit`, no mixed-content change, the manifest's two meta-data, the page bridge's methods exactly
  `FROM_PAGE` and the only `@JavascriptInterface`s); Spotify's debug base assigned by exactly `listOf("DebugSpotify.kt")`
  and read at start-up (Storytel's assignment check now names `StorytelEndpoints.debugBase`, since `DebugSpotify.kt`
  assigns a `debugBase` too); the nb lines and every `RendererState`'s string. `GuardsTest`: `persistPlace`'s callers
  add `SpotifyRenderer.kt`. `OutputKeyTest`: the eleven `RendererState`s, `remote` the Sonos four, `onPhone` LOCAL and
  SPOTIFY. Checked by mutation (each red): the move-on guard in `play()`, no step coalescing, a pause during the calls
  dropping the start instead of following it with `vibb.pause()`.
- **Validation** (from `music/`): `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true` BUILD SUCCESSFUL, then `testDebugUnitTest --rerun`: 723 JVM tests (692 before), 0 failures;
  lint 0 errors, 39 warnings (the same count as step 4; lint's `Typos` on "internett" is ignored on that string with a
  comment). `python3 -I scripts/fake-spotify.py --selftest` "FAKE SPOTIFY SELFTEST OK"; `fake-storytel.py` and
  `fake-sonos.py --selftest` OK.

Deviations from the spec (each with its reason):
1. **A seventh state, `SPOTIFY_UNAVAILABLE`** (§5.8 gives the quota `SPOTIFY_NEEDS_INTERNET`'s carrier with another
   text): an enum value carries one string, so the quota's line is its own value (not `remote`), mapped from step 4's
   `SpotifyLine.UNAVAILABLE`.
2. **`ui/PlayerScreen.kt` touched** (the UI is 5b's): `rendererStateText`'s `when` over `RendererState` is exhaustive,
   so the seven values needed their strings to compile - one line each, nothing else in the UI.
3. **The Wi-Fi lock is `WIFI_MODE_FULL_LOW_LATENCY`**, not §5.7's `FULL_HIGH_PERF`: HIGH_PERF is deprecated (a Kotlin
   deprecation warning, an error under `-PwarningsAsErrors`) and a no-op since API 34, the app's minSdk - SonosLocks'
   choice and reason. The Wi-Fi lock's 30-minute timeout is a timer (a WifiLock has none).
4. **No `Log.` in `spotify/`** (§7.1 allows the debug build's fixed lines there; `SourceScanTest` bans `Log.` in the
   package): the host, the locks and the renderer take a `log` function; 5b's service passes a debug-only one.
5. **IDLE for "an error that stops the renderer"** means the two that destroy the page (no Widevine, the renderer
   process gone); the network lines, "spør en voksen" and "elsewhere" are READY and paused (§5.4's table names only
   "unreachable" READY).
6. **The unplayable move-on rides on the service's existing `onError`** (§5.10: record, `CANT_PLAY`, then `seekTo(next,
   pos)`, `prepare()`, `play()`): while the renderer's `Error("unplayable")` is being handled, those calls are the
   renderer's own move-on - no call when Spotify already plays that item or past it (step 4's "moving on only when the
   tracker's index hasn't moved past it"), else one non-fresh play; after the third in a row only the index moves and
   `play()` is ignored ("no more calls"). 5b changes nothing there but the recording (a Spotify item has no url).
7. **While a start runs or a step coalesces, the old play's tracker events are ignored** but a foreign track's pause;
   **states posted during a start's Web API calls are held and read after `played()`** - our own new play's first
   state can arrive before the 204, and read before `played()` it would be foreign and paused (§5.4 doesn't say how a
   start and the posts interleave).
8. **The renderer gates too**: `playAllowed` and `spotifyNetworkLine` before every start and SDK resume (a belt
   beside 5b's service gate), and Spotify's own resume of our paused track (another app's controls) is shown only with
   `playAllowed` and the focus, else paused at once.
9. **The account check** (§5.2's "when the token's `account` changes") is made at each start (before the page plays),
   at the SDK's own token ask and at each refresh ahead of expiry (the live page is cleared and destroyed, no token of
   the other account reaches it); the page's account is known in memory only, so a new process's first page isn't
   cleared. **Rejected by the code QA (M1) and replaced in the fix round**: the account marker is stored (below).
10. **`reorder()` is a logged no-op**: no shuffle for Spotify (§4.2), so the service never calls it.
11. **The focus branch** (§5.6's device check): the renderer keeps its own request and logs every change for the Jelly
    Star; whether the WebView takes focus itself is not decided here (no device).

Found in the spec:
- §5.7's `WIFI_MODE_FULL_HIGH_PERF` is deprecated and a no-op at the app's minSdk (deviation 3).
- §5.8's quota line can't share `SPOTIFY_NEEDS_INTERNET` as an enum value with another text (deviation 1).
- §7.1's "debug-build lines in `spotify/`" contradicts `SourceScanTest`'s ban; the stricter rule kept (deviation 4).
- §5.10's `onError` move-on would send a second `PUT` when Spotify already moved on past an unplayable track, and a
  fourth after the third; the renderer reads it (deviation 6).
- §5.4 doesn't say what happens to states that arrive between the play call and its 204 (deviation 7).
- `OutputKeyTest` pinned the four Sonos states, and Storytel's debug-base scan matched any `debugBase =` in `src/debug`;
  both adjusted.
- Nothing in §11 step 5a was impossible without `androidx.webkit`: `ServiceWorkerController`/`ServiceWorkerClient` and
  `loadDataWithBaseURL` + the interceptor are platform API.

Owed by step 5b - the renderer API it wires:
- **Make it once per service and keep it** (§5.10): `SpotifyRenderer.create(context, store, scope, writer, queue = {
  active }, outcome = { word -> Store's spotify_state.json through SpotifyOutcome.next }, log = a debug-only logger)`
  (`internal`, `me.vibb.music.spotify`). `queue()` must return the `Active` whose items `load` gets: the renderer builds
  its tracks from `Active.items` and `entry.target`/`entry.spotifyMode` (list mode only; a context entry loads nothing
  until step 6), and its MediaItems are the service's (`mediaItem`: uri `uriOf(kind, key)`, no `ItemTag`).
- The `Renderer` calls as for the others (`load`, `play`, `pause`, `seekTo(index, ms)` for steps and swipes, `seekTo(ms)`,
  `saveNow`, `fadeOutAndPause`/`cancelFade`, `showsPlaying`, `rendererState`, `clear`, `release` in `onDestroy` beside
  `local.release()`), `kind == RendererKind.SPOTIFY` and §5.10's sites by `kind.onPhone`, `callStep`'s paused test by
  `rendererState?.remote != true`, `local.standDown()` when attaching it.
- `suspended()` at `MY_PACKAGE_SUSPENDED` with the service up (the bookmark with force, the pause, the page destroyed);
  `forget()` when the token is `off` from elsewhere or the library has no Spotify entry (the page cleared, the token
  dropped); `spec()` for the service tests.
- `onError` for a Spotify item: record `unplayable` (`ItemErrors.record`; the url test excludes it today), `CANT_PLAY`,
  and the existing move-on; never `NOT_DOWNLOADED`.
- `SPOTIFY_PLAYABLE = true`; the outcome file; §6j part A on the emulator - the proxy's `Range` seek of the tone, the
  permission line, the blocked-hosts counter (`SpotifyWebHost.blocked`), the recorded `sdk_states/`, the scripted
  page's `needToken` -> `vibb.token` -> `ready` order checked against a real cold start.
- [device check]: the WebView's own audio focus (§5.6, deviation 11), the protected-media origin, the wake lock's need
  (§14 item 6).

### Fix round after QA's code review (qa-26c-step5a-code, 2026-10-13)

All fourteen findings fixed; none disputed. No contract file changed, no new dependency, `SPOTIFY_PLAYABLE` still false.
From `music/`: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD
SUCCESSFUL, then `testDebugUnitTest --rerun`: 736 JVM tests (723 before), 0 failures; lint 0 errors, the same 39
warnings. The three fakes' `--selftest`s OK under `-I` (`start_old_advance.json` passes the fake's key check).
Every red test the QA quoted (its scratch `QaCounterTest`: the shuffle toggle while paused, the pause behind a replaced
step, the transient focus loss during the play call and while the page loads, the pick near a track's end with and
without the held states, the new process with another account) is rebuilt in `SpotifyRendererTest`, and green.
- **H1** (a paused Spotify started by itself): `apply` reads the intent before the batch (`wanted = pwr &&
  !suppressed`) and `NewWindow`/`ShuffleOrRepeat` start a play only with it (a source test pins both `request(` lines);
  paused, they only move the place, and `ShuffleOrRepeat` sets `needSettings` - the kid's next play is a fresh start
  that turns shuffle and repeat off first (`resumable()` is false while `needSettings`: the tracker reads nothing until
  a play call). A replaced start whose play went out and whose successor was dropped (a pause, a new queue) is paused
  at its 204 and the tracker told it is lost; a cancelled one as before. Tests: both QA counter-examples.
- **M1** (deviation 9 rejected): the page's account marker (`"<id>:<generation>"`) is stored through
  `SpotifyPorts.pageAccount`/`storePageAccount` in `noBackupFilesDir/spotify_page_account` (tmp + rename), written when
  a page is made under an account, deleted by `off`/`forget()`/another account's clear. A new page (no page alive)
  waits for the start's token; when the account differs from the page's (in memory, else the marker), or no marker
  exists, the stores are cleared first. A live page of a known account still loads beside the token. The SDK's own
  token ask is never answered with another account's token (`null` during a start, else the page is cleared). Tests:
  a stored `2:1` with a `3:1` token clears before the first page; the same account doesn't; no marker clears once; the
  SDK's ask of another account.
- **M2** (a transient loss during a start): the play that goes out under a transient focus loss is paused at its 204
  (`vibb.pause()`, `pauseSent()`), play-when-ready kept; the first-state rule doesn't fail while suppressed; the gain
  resumes a `STARTING` or `OURS` track (and re-arms the first-state rule), lets a start on its way go on, or starts
  afresh at the place. Tests: the loss during the play call and while the page loads.
- **M3** (Spotify's own advance read as skips): the tracker's start rule passes over the old play's expected next
  track (kept across `load()`) and our adds still in Spotify's queue, besides the last state's track, unless it is the
  sent track near the sent position (`SpotifyTracker.played`, `stale`); and the held window is tightened - the states
  posted before the play call went out (its send time, taken in a wrapper of the `SpotifyHttp` seam) are read only to
  tell the tracker what played before, the rest after `played()`. `sdk_states/start_old_advance.json`
  (`SpotifyTrackerTest`) and the renderer's pick near the end, held and not held. §5.4 says so.
- **L1** (the Wi-Fi lock inactive with the screen off on API 34+): §5.7, open point 6 and QA checklist item 6 say it -
  the lock is kept but not the guard; the screen-off run looks for audio and state gaps. `SpotifyLocks`' comment says
  it. **For the user: `SonosLocks`' Wi-Fi lock is the same** (LOW_LATENCY, screen-on only) - it never kept Wi-Fi out of
  power save for a room either; the partial wake lock is what keeps the polls running there.
- **L2** (redirects unseen): §5.2, §7.2, §9.4 part B and QA checklist item 15 - a redirect's target is bounded by the
  DNS filter only, like a WebSocket; the parent's DNS log is read for it. No code change.
- **L3** (`reauthorize` at the SDK's ask): `reauthorize` or `off` at the SDK's own ask fails at once with its line and
  word (`SPOTIFY_ASK_ADULT`/`reauthorize`); the last failed token answer is kept, and an `authentication_error` outside a
  start shows its line (else "trenger internett"). Tests for both. §5.5 says so.
- **L4** (an SDK problem during the calls overwritten): the SDK's problem (the page gone, no Widevine, no Premium)
  wins over a failed call's in every branch but a replaced or cancelled start. Test: `Gone` while the call runs and
  fails - `SPOTIFY_STOPPED`, IDLE.
- **L5** (the disconnect lost): `destroy()` takes the view out at once and destroys it 300 ms after `vibb.disconnect()`
  has run, at most 1 s later.
- **L6** (the clears): the host's `clearStorage(done)` (web storage, the HTTP cache - through a throwaway view when no
  page is alive - and the cookies, `done` from `removeAllCookies`' callback) is awaited (at most 3 s) before the next
  page; the service workers' own settings have file and content access off. `SourceScanTest` pins all three.
- **L7** (the scans): `SpotifyWebHost.kt` is scanned for every banned token but `WebView`; every `debugBase =` in
  `src/debug`, qualified or not, must be exactly Storytel's in `DebugStorytel.kt` and Spotify's in `DebugSpotify.kt`.
- **L8** (the SDK's message): only in a debug build (`if (BuildConfig.DEBUG)` on that one line; `SourceScanTest`).
- **L9** (tests): the scripted page now behaves as `player.js` and the stub - a new page's `needToken`, `ready` only
  after a non-null `vibb.token` - and the cold start answers the SDK's ask from the start's token; deviation 7 has its
  two cases (our first state before the 204 held and read as ours; the old play's news during a step ignored).
  Checked by mutation (each red): no holding, no "old play ignored" filter, no old expected next in the tracker, a
  self-started play without the intent, no pause of the play sent under a transient loss, the marker ignored, no pause
  of a replaced play that went out.
- **L10** (the spec body): §5.2 (the marker, the clears, the disconnect, redirects), §5.4 (the as-built start: IDLE,
  the gate, the held states, the intent rule, focus during a start, the wider stale rule), §5.5, §5.7, §5.8
  (`SPOTIFY_UNAVAILABLE`), §5.10 (`onError`), §7.1, §7.2, §9.4, open point 6, QA checklist items 6 and 15.
- **Pinned for step 5b** (deviation 6's safety argument, the QA's note): `PlaybackService.onError` must stay
  synchronous on the main thread for a Spotify item - the renderer reads the `seekTo`/`prepare`/`play` made *inside* its
  `Error("unplayable")` as its own move-on; a `post`/`launch` around it would turn it into an unguarded play. 5b adds a
  `GuardsTest` pin. The `log` passed to `SpotifyRenderer.create` is debug-only (5b names it).

## Step 5b implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of step 5a and its fix round (`1bebd77`), one commit: §11 step 5b, the music
app's service, UI and report, and the emulator loop. No new dependency, no contract file changed (`music_state.json`'s
`spotify` words are step 3's; the shared files are byte-identical as step 3 left them), `launcher/` and `server/`
untouched. `SPOTIFY_PLAYABLE` is true: list-mode Spotify entries show and play.

- **The service** (`M/playback/PlaybackService.kt`, §5.10's table row by row; the pure rules in the new
  `M/playback/RendererRules.kt`):
  - *Which renderer*: `start()` calls `usePhoneRenderer(entry)` after its own save and before the queue changes:
    `phoneRendererFor(entry)` - `SPOTIFY` for a Spotify entry, else `LOCAL` -; a room keeps its renderer (its queue
    never holds a Spotify item). The change is `attach()` (the old one saved with force and paused first), and for the
    web player `local.standDown()` (§5.6, QA L1). `spotifyRenderer()` makes the one `SpotifyRenderer.create(...)` of
    the service's life with `queue = { active.takeIf { renderer === spotify } }` (deviation 5), `outcome` = the
    store's `recordSpotifyOutcome` on the writer, and `log = ::spotifyLog` - `Log.d` under `BuildConfig.DEBUG` only,
    the service's one log line (qa-26c-step5a-code L8: nothing at all in a release).
  - `playable` = `itemPlayable(kind, entry, ...)` (a Spotify item by `spotifyPlayability` alone, never `GONE`);
    `playableFrom` = `playableIndex` (a Spotify entry: the index or null, never another item).
  - `start`: a Spotify entry the gate refuses never says `NOT_DOWNLOADED` and never moves: its queue is loaded paused
    at the plan's place (no page, no network) and `refuseSpotify()` gives the renderer the gate's line
    (`SpotifyRenderer.refused(line)`: "trenger Wi-Fi" or "trenger internett", the word `unreachable` for the latter);
    a restore (`play = false`) just loads it (deviation 2).
  - `goTo`: the same - the line, no jump. `onItemChanged`: only Spotify's own advance is gated here (refused: paused
    with the line, never moved on; deviation 4); the natural-advance seek is `resumesOnAdvance(auto, kind)` - the
    phone's own player only (QA M4).
  - `onError`: `recordsItemError(entry, hasUrl)` records a Spotify item's `unplayable` through `ItemErrors.record`
    (only the record is launched on the writer), the message is `CANT_PLAY` (the offline test is skipped for
    Spotify), and the move-on stays synchronous on main, as 5a's renderer reads it (`GuardsTest` pins it: the
    `seekTo`/`prepare`/`play` straight after, no `launch`/`post`/`withContext` around them, and `onRendererEvent`
    calling `onError` directly).
  - `mediaItem`: a Spotify branch before Storytel's and the feeds' - uri `spotifyItemUri(entry, key)`
    (`spotify:track:`/`spotify:episode:`; step 6's context item the entry's target), no `ItemTag`, no cache key.
    A Spotify MediaItem never reaches `ItemMediaSourceFactory`: only `LocalRenderer` has the factory, a queue is
    loaded only in `start` after `usePhoneRenderer`, a room's queue (adopt included) drops Spotify, and playback
    resumption hands Media3 only a non-Spotify queue after `usePhoneRenderer` attached the phone's own player.
  - `wakeModeFor`: unchanged (the web player's `setWakeMode` is a no-op; its own locks, §5.7). `onIsPlayingChanged`:
    unchanged.
  - `onPlaybackResumption`: `!onPhone` keeps 26a's branch; a stored Spotify queue takes the room's shape -
    `start(...)` (gated: `playAllowed` first, the renderer's belt and the network gate) and the future failed
    ("playing on Spotify"), no local items (QA H2).
  - `onDestroy`: the web player released besides `local.release()` (before the writer closes); the current one
    through `renderer.release()`.
  - The room looks (`lookAtRoom`, `lookAgainBeforePlaying`), `loadForRenderer`, `queueFor`, `activeFor`, `output`:
    by `kind.onPhone`. `comeHome`: `activeFor(..., phoneRendererFor(entry))`, then `start` chooses the renderer.
    `toRoom`: unchanged (a Spotify entry keeps the phone playing with `CANT_PLAY_ON_SONOS`). The audio-mode call
    backstop: unchanged (rooms).
  - `callStep`: `pausedForCall(playing, itemCount, state)` = `state?.remote != true` (§5.8).
  - `suspendStop`: `spotify?.suspended()` first, whatever renders (the bookmark with force, the pause, the page
    destroyed: no Connect device while suspended, §5.7 QA L11), then 26a's path.
  - A parsed library with no Spotify entry: `forget()` (the page, its stores, the marker, the token) - also when
    only the page's marker from an earlier run is on disk (`SpotifyRenderer.pageAccountFile`). The token's `off` is
    the renderer's own (5a). `RendererGate`, `renderer.json`, `reportNowPlaying`: unchanged (design 28 reads the web
    player's `showsPlaying` through `setNowPlaying`, its pauses as every renderer's).
- **`M/spotify/SpotifyRenderer.kt`** (5a's file, three small additions): `refused(line)` (the service's gate's
  verdict through the renderer's own `refuse`), `pageAccountFile(context)` (the marker's one name), and `create`'s
  token ask - the fake's `/fake/token` only while the app is **unmanaged** (a run without a launcher, §9.3), else the
  bridge (deviation 7).
- **`M/data/Store.kt`**: `spotify_state.json` (`noBackupFilesDir`): `recordSpotifyOutcome(word, now)` through
  `SpotifyOutcome.next` (written only when the word changes, `writeAtomic` = tmp + rename), `readSpotifyOutcome()`,
  and `spotifyOutcomes()` (a flow for the tiles). The page's account marker stays the renderer's ports' file (5a's
  host keeps it; the store doesn't need it). **`M/data/Snapshot.kt`**: list-mode Spotify items visible in both views;
  a context-mode entry shows nothing yet (deviation 6).
- **`M/logic/Spotify.kt`**: `SPOTIFY_PLAYABLE = true`; `SpotifyOutcome.reported(text)` (the report's word: the stored
  one, else `none`). **`M/logic/Report.kt`** + **`M/sync/Sync.kt`**: `spotify` = `SpotifyOutcome.reported(` the
  file `)`; `item_errors` carries `unplayable` rows (the service's record; `ItemErrors`' doc says so).
- **The UI**: `EntryUi.state` = `spotifyTileState(entry, loadedId, loadedState, outcome)` - the loaded entry's web
  player line (`PlayerHub.State.entryId` + `rendererState`, never a room's), else the stored outcome's account line
  on **every** Spotify tile (`spotifyTileLine`), else null; `LibraryScreen` shows it in place of the hint;
  `MusicRoot` passes the hub and `MusicViewModel.spotifyOutcome` (from `store.spotifyOutcomes()`). The player:
  the quiet line under the entry name (5a's `rendererStateText`), and a small "Spotify" line (`kind_spotify`) under
  the title's number line for every Spotify entry (§4.3, §7.3). "Liste": no "N av M lastet ned" and no offline marks
  for Spotify - the word "Spotify" in that place. `countedKeys`/`playerNumber`: unchanged (the visible list in play
  order, the order setting included; `UiModelTest`). `PlayerHub.State.rendererState`'s doc names Spotify.
- **`docs/testing/emulator.md` §6k** (deviation 1): part A1 (the PWA, the server with the loopback bases, the debug
  launcher and music app, against `scripts/fake-spotify.py`: login, paste, plays-as, the links, the tiles, a cover
  tap's exact log, pause/resume/seek, mashed next, a pick, the queue's end, a restart, a headset's play, Doze, the
  sleep fade, a bedtime edge, a call, design 28, the output picker, a refused refresh, the secrets greps), part A2 (the
  phone alone on the debug feed: the 60-track window and queue add, a cold start, elsewhere, unplayable, autoplay,
  transfer-in, relink, shuffle/repeat, no Premium, no Widevine, the quota, the token refused, offline and no Wi-Fi, no
  Spotify entry left), part B (the real SDK). Every adb line is in it.
- **`scripts/fake-spotify.py`**: `listed_tracks` lists a duplicate once (as the server does - `--print-listing 6`
  printed the fixture's duplicate track twice, two items with one key; the selftest checks keys are unique), and an
  `audio <id>: <status> [Range ...]` log line (§6k reads the debug proxy's `Range` there). The `§6j` pointers in root
  `CLAUDE.md`, `music.yml`, `DebugSpotify.kt` and the fake now say `§6k`.
- **Tests**: `RendererRulesTest` (new, 7: the renderer per entry, a Spotify item's playability over every network,
  all-or-none starts and steps, the MediaItem uri, `pausedForCall` over every `RendererState`, `resumesOnAdvance`,
  `recordsItemError`); `UiModelTest` (new, 4: the loaded tile's line, the stored outcome on every Spotify tile and
  never another source's or a room's, `spotifyTileState`, "Spor N av M" with the order setting, a cover tap's switch
  on the phone and none on a room); `GuardsTest` (+5: §5.10's sites - which renderer, made once, the exact list of
  `RendererKind.` and `=== local` reads, the debug-only log, `playable`/`playableFrom`/`start`/`goTo`/`onItemChanged`,
  `onError` recording and synchronous, `mediaItem` and the factory's one user, resumption, `onDestroy`, the
  suspension, the looks, `comeHome`, `callStep`, the clearing, the report's and the tiles' wiring; 3 existing pins
  moved to `onPhone` and the attach count to five callers); `SpotifyRendererTest` (+3: `refused` loaded and while
  playing, a detached renderer writes nothing); `SourceScanTest` (+1: made by the service only, the one debug-only
  log line, `spotify_state.json` the store's alone and a word, `kind_spotify` in both languages); `ReportTest` (+1:
  the word from the file, every word, `none` without it, `unplayable` as an item error); `LibraryViewTest` (+1: a
  Spotify tile's hints and lines); `SpotifyRulesTest` (the flip, a context entry hidden, NY on by default,
  `reported`).
- **Validation** (from `music/`): `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true` BUILD SUCCESSFUL, 759 JVM tests (736 before), 0 failures; lint 0 errors, 39 warnings (the
  same count as step 5a; none in a line this step wrote). `python3 -I scripts/fake-spotify.py --selftest` "FAKE SPOTIFY
  SELFTEST OK"; `fake-storytel.py` and `fake-sonos.py --selftest` OK. Checked by mutation (each red): the natural
  advance's seek on every phone renderer, a Spotify start moving to another item, a room's line on a Spotify tile.
  The launcher's and the server's suites not run: no file their tests read changed.
- **Not run here: all of §6k.** The sandbox has no emulator, no VM and no account: not one line of part A1, A2 or B
  was run - not the server's PWA steps, not an `adb` line, not the tone, not the greps. What was run of it: the fake's
  `--selftest`, and `--print-library` / `--print-listing 6` (`--tracks 60`) to check the documents §6k pushes (and
  that found the duplicate key fixed above). §11's acceptance "§6j part A runs end to end" is therefore **owed to the
  user's emulator run** of §6k part A1 and A2; part B is the user's account.

Deviations from the spec (each with its reason):
1. **The emulator section is §6k, not §6j**: `docs/testing/emulator.md` §6j is design 28's (the music card's
   listening window, merged from `main` at `5d6bd5b`); every "§6j" in this design means §6k. The pointers in code,
   CI and root `CLAUDE.md` were moved; the earlier status and QA sections are left as written.
2. **A Spotify start the gate refuses loads its queue paused** (§5.10's `start` row: "no load or play"): the line's
   carriers are the loaded entry's `PlayerHub.State` (the player's quiet line, the loaded tile) - with nothing loaded
   the kid's tap would show nothing. Loading touches no network and no web player (§5.4), and nothing plays; the
   restore path (`play = false`) loads as for every entry.
3. **`SpotifyRenderer.refused(line)`** (a new public method on 5a's renderer): the service's own gate (H1) needs a
   way to show its verdict; it is the renderer's private `refuse`, so the line and the word are §5.8's.
4. **`onItemChanged` gates only Spotify's own advance**: a load, a step and the renderer's own moves (`NewWindow`, the
   unplayable move-on) were gated where they were made (`start`/`goTo`, the renderer's belt); gating the load's own
   item change too would, on a restore while offline, run the old "pause and bookmark the next item at 0" branch.
5. **A detached web player sees no queue** (`queue = { active.takeIf { renderer === spotify } }`, not in §5.10): the
   page outlives a detach by up to 5 minutes, and a late state (a transfer-in, `Elsewhere`'s bookmark) would otherwise
   write a Spotify position into the phone player's entry (`saveNow` reads `Active.items` by index). Pinned
   (`GuardsTest`, `SpotifyRendererTest`).
6. **A context-mode entry shows nothing in 5b** (`Snapshot.visible`): §11 puts its synthetic item in step 6, and
   the renderer loads nothing outside list mode (5a), so a 403 playlist's last good list would show a tile that
   plays nothing. Step 6 replaces the rule with `contextItem`.
7. **The fake's token route only for an unmanaged app** (5a used `debugTokens()` whenever the switch was set): §9.3
   says "for a run without a launcher", and §9.4 part A's chain needs the token through the launcher; with the
   switch on a managed phone the token now comes from the bridge (`Bridge.config(app).managed`, read off main).
8. ~~**"No Spotify entry left" doesn't write `none`**~~ - **withdrawn in the fix round** (qa-26c-step5b-code L4:
   `forget()` writes `none`). Clearing also runs when only a page marker from an earlier run exists.
9. **"Liste" says "Spotify"** where the offline summary was (§4.2 asks only for no "lastet ned"): the attribution
   (§7.3) costs no new string (`kind_spotify`).
10. **§5.10's "service tests" are pure rules, renderer seams and source pins**: the service is Android-only and the
    module has no Robolectric (no new dependency allowed). The cover tap -> `load` + `play`, offline -> the line and
    no move, the advance sending nothing, the headset's play after a restart, `unplayable` recorded: each is
    `RendererRulesTest` (the rule), `SpotifyRendererTest` (what the renderer does with it) and `GuardsTest` (the site
    asks it, in that order) - and §6k's lines on the emulator.
11. **The fake** (step 4's file): a duplicate playlist track listed once, and an `audio` log line - both for §6k.

Found in the spec:
- §6j was taken by design 28 (deviation 1).
- §5.10's `start` row can't both load nothing and carry the line on the loaded tile (deviation 2).
- 5a's `debugTokens()` made §9.4 part A's token path impossible (deviation 7).
- §9.4 part B's "each compact state post logged" contradicts §7.1 (no state JSON in a log line): not built; recording
  real sequences into `sdk_states/` needs a debug-only recorder (owed).
- §11 5b's test list names `UiModelTest`, which didn't exist: made.
- `--print-listing 6` printed two items with one key (fixed, deviation 11).
- A restarted fake refuses the server's kept access token, which the token route hands out unchecked: the phone reads
  `refused`, not `reauthorize` (L4's limit, §5.4) - §6k restarts the server for the `reauthorize` check and keeps A1
  on one fake process.
- Nothing in §11 step 5b was impossible without an emulator except running §6k.

Owed: **§6k part A (A1 and A2) on the user's emulator** - every line (§11's acceptance), and part B with the user's
account (the protected-media origin, the blocked-hosts counter at 0, no `csp` line, the DNS capture, episodes in a
`uris` list - `SHOWS_PLAYABLE`); a debug-only recorder of real state sequences for `sdk_states/` (part B); step 6 -
the context item (`contextItem` in `Snapshot`, `hint_spotify_picks`, `spotify_context.json`, the artist's tile,
context ownership); step 7 - the docs (`music/CLAUDE.md`'s renderer axis, the service sites, the outcome file, §6k).
[device check]: QA checklist items 4-17 and 19-21 (Jelly Star, a real Premium Family member account), the item-10
Doze run with and without the wake lock (before 5b's acceptance closes, §11), the WebView's own audio focus (§5.6),
a VoIP call's `CALL_HOLD`.

### Fix round after QA's code review (qa-26c-step5b-code, 2026-10-13)

All eight findings fixed; none disputed. No contract file changed, no new dependency, `launcher/` and `server/`
untouched. From `music/`: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true
--rerun-tasks` BUILD SUCCESSFUL, 765 JVM tests (759 before), 0 failures; lint 0 errors, the same 39 warnings. The three
fakes' `--selftest`s OK under `-I`. Checked by mutation: `detached()` doing nothing turns both M1 cases red.
- **M1** (a detached web player started from outside): `attach` tells the old web player (`if (old === spotify)
  spotify?.detached()`, after its events are cut); `SpotifyRenderer.detached()` pauses, drops any start, sets
  `detached` and `tracker.lost()` - until the next `load` every playing state is `NotOurs` (paused at once), `Resumed`
  never takes focus, and `begin`/`resume` refuse. The QA's counter-example and a real detach sequence (a transfer-in
  and our own track resumed from outside, both paused; no play call, no place written; a re-attach's load plays again)
  in `SpotifyRendererTest`; a `GuardsTest` pin in `attach`; §6k A2's "a detached web player" line.
- **M2** (the console in logcat): `SpotifyWebHost.Chrome.onConsoleMessage` returns true always; a debug build logs the
  level and the source's host only. `SourceScanTest` pins it (one override, one `return true`, no `.message()`); §6k
  part B greps logcat for `access_token|BQ…|Bearer` after a Wi-Fi drop while playing; §7.1 names the channel.
- **L1** (a line too long at 320 dp): nb "Spotify virker ikke på telefonen" (174.2 dp); `FitTest` measures all
  fourteen Spotify lines (Nunito Bold 12 sp from the font's advances, the QA's method) against the player's state line
  and the tiles, and checks the map holds every `renderer_spotify_*` string in both languages.
- **L2** (a real token to the debug base): with the switch set a bridge token goes on only when it is a fake one
  (`debugSwitchToken`: `FAKE-ACCESS-` prefix, else `Unreachable` and one debug line); `SpotifyRulesTest` and a
  `SourceScanTest` pin; §9.3.
- **L3** (§6k lines): the consent key (`consented as parent`/`kid`, `the next login consents as kid`), no
  `getOAuthToken ok` in a cover tap (only the stub's renewal timer logs it), the playlist path without its query, the
  headset check by `dumpsys media_session` (ExoPlayer's `Init` line is expected; the stopped package after a
  force-stop), a force-stop before playing after a re-login (the 10-minute `reauthorize` cooldown), A2's report after a
  broadcast, "No Spotify entry left" with the app open, part B's `the real hosts` (logged only on a change).
- **L4** (the last word outlives the binding): `forget()` writes `none` (deviation 8 withdrawn); a renderer test; §4.4
  and §6k say so.
- **L5** (tests): `GuardsTest` asks `PlayBodies.speakerDropReason` itself (not a comment's text); `LibraryViewTest`
  asserts `listRows(...).none { it.offline }` over the real `onPhone` with a stray download row - which showed a
  Spotify entry *could* be marked, so `buildSnapshot` now never puts a Spotify key on the phone; M1's cases sit beside
  deviation 5's.
- **L6** (the spec body): §4.4 (`forget` writes `none`), §5.10 (the `start` and `onItemChanged` rows as built, "Which
  renderer" with the detached queue and `detached()`), §7.1 (`spotifyLog`, the console), §9's headings, §9.3 (the
  token route only unmanaged, fake tokens only), §9.4 (§6k's A1/A2 and part B's recorder), §11 step 5b (§6k).
- **The state recorder** (the QA's decision, built by **step 6**): a port `SpotifyPorts.recordState(json)` called by
  the page bridge's `state()` with the compact JSON as received; a no-op in the main source set (a `SourceScanTest`
  pin), the writer in `src/debug` (`DebugSpotify.kt`); on only while the debug feed's `config.json` says
  `"record_spotify_states": true` (read at the receiver and at `DebugStartup`, as `spotify_base`); one JSON line per
  state `{"t": <elapsed ms>, "state": <the compact JSON>}` plus the page's `ready`/`notReady`/`error(kind)` and our own
  Web API calls by name (`play from 3`, `queue 51`), appended to `getExternalFilesDir("debug-feed")/sdk_states/<start
  time>.jsonl`; never a token or the SDK's error text. §6k part B records the real context sequences with it.
- Still owed (unchanged): §6k parts A1 and A2 on the user's emulator (§11's acceptance; none of it was run here), part B
  with the user's account, the Jelly Star's item-10 Doze run before 5b's acceptance closes, QA checklist items 4-17 and
  19-21.

## Merge of main (571f86f, 2026-10-13)

`origin/main` at `571f86f` (designs 30 - streaming and downloads on mobile data as two switches, the stream saved as
it plays, played extras within a per-phone music storage limit -, 31 steps 2, 3 and 11 - the PWA's display helpers with
`chrono-tz`, the Devices list, About This Phone/Advanced, the Apps tab -, 32 step 2 - the launcher's keyguard cover -,
33 - Locate's map tiles -, and the handoff note) merged into `music-3-spotify` after step 5b's fix round (`2d0805e`), in
one merge commit; everything the merge broke is fixed inside it. Written here at the end, after step 5b's fix round.

**Renumbered:**
- **The migration is `0074_music_spotify.sql`** (was 0073, which main's `0073_music_data_and_storage.sql` - design 30 -
  now has). Content unchanged but the header comment (the 0074 wording, the renumbering and the backup hazard); §3.7's
  block is the file byte for byte. §3.7's title, §11's migration-number line, step 1's file list and step 7's docs line,
  §14 open point 12, Release (0072, design 30's 0073, then 0074), the comments in `models.rs`, `music.rs`,
  `music_sweep.rs` and `handlers/music.rs` that name Spotify's columns or triggers, PLAN.md and `server/CLAUDE.md`
  ("Data model": the out-of-order hazard if 0073 reaches a server that ran 0074 - design 30's server ships before or
  with Spotify's - and that a dev database that ran this branch before the merge holds its old 73 with another checksum
  and stops at `VersionMismatch(73)`: a fresh `data/`) follow. The status and QA sections above keep "0073" as written.
- **The emulator section is §6l** (was §6k, which main's design 32 step 2 - the keyguard cover - now has). Its heading
  and first paragraph, root `CLAUDE.md`, `music.yml`, `DebugSpotify.kt`, `SpotifyRenderer.kt`'s one comment,
  `scripts/fake-spotify.py` and this design's body (§9, §9.4, §11, the QA checklist, Release) say §6l. **Every "§6k"
  in the status, fix-round and QA sections above means §6l** (and their "§6j" the same, step 5b's deviation 1).

**Textual conflicts and how they were resolved:**
- `.github/workflows/music.yml`: the header comment, the path filter and the selftest steps keep both - Spotify's
  files (`spotify_hosts.json` copies, `server/testdata/spotify/`, `fake-spotify.py`) and main's `fake-audio.py`; four
  fakes' selftests run.
- Root `CLAUDE.md`: the `scripts/` line names `fake-spotify.py` (§6l) and `fake-audio.py`; the CI paragraph names both.
- `PLAN.md`: main's design 30 sentence kept after this branch's text; main's "Phase 3 (Spotify) follows design 26; its
  first step, the engine spike, ..." replaced by a pointer to 26c (as at the previous merge), and the 0074 note.
- `docs/testing/emulator.md`: main's §6k (keyguard cover), then ours as §6l.
- `server/src/models.rs` (`DevicePolicy`): both fields - main's `music_storage_limit_gb`, our `music_spotify_account`.
- `server/src/tests/device_api.rs` (`policy_json_keys_snapshot`), `server/src/tests/music.rs` (a fresh phone's music
  object): main's `stream_mobile_data`, `download_mobile_data`, `storage_limit_gb` and our `spotify`.
- `server/templates/device_detail.html`: main's two switches; our Spotify sentence moved onto the **streaming**
  switch's text as "This includes Spotify, which streams (about 60-150 MB an hour) and is never saved on the phone."
  (§3.6's device card line); `the_device_card_binds_a_phone_to_an_account` checks the new text and that it sits
  between the streaming and the downloads switch.
- `launcher/.../music/MusicPlan.kt` (`musicState`'s keys): main's `audio_bytes`, `extra_bytes` and our `spotify`.
- `launcher/.../dto/PolicyResponse.kt`: one KDoc naming every key, both readers (`musicPolicy`, `musicSpotify`).
- `PolicyResponseCompatTest`: today's music object has both sides' keys; main's `musicPolicy` check and our
  `musicSpotify` checks both run; the odd objects of both sides stay.
- `music/.../DebugFeedReceiver.kt` (the `config.json` doc: main's switches and limit plus our `spotify_base`),
  `Store.kt` (the class doc: both).
- `music/.../PlaybackService.kt`: `playable` and `playableFrom` keep ours (`itemPlayable`/`playableIndex`, §5.10) with
  main's `streamMobileData`; `onError`'s offline is `gated || !isSpotify(a.entry) && copy == null && ... &&
  !streamAllowed(..., store.streamMobileData)`; `comeHome`'s `activeFor` takes our `phoneRendererFor(a0.entry)` and
  main's `plan.startIndex`.
- `GuardsTest`: both imports. `ReportTest`: the report's keys of both sides; both new tests kept, ours calling main's
  `stateReport` with `audioBytes`/`extraBytes`.
- Merged by themselves and read through: `bridge_contract.json` (both copies: main's three restrictions and our
  `spotify_token`, `cmp`-identical), `server/testdata/music_state.json` (main's `audio_bytes`/`extra_bytes` and our
  `spotify`; the one copy), `DownloadPlan.kt` (main's extras and held set; our Spotify `continue` comes first, so a
  Spotify row never reaches the extras rule), `Sync.kt`'s `writeReport` (main's two byte counts and our `spotify`),
  `activeFor` (our `kind.onPhone` filter, main's `kind == LOCAL` claim), `music/CLAUDE.md`, `launcher/CLAUDE.md`,
  `server/CLAUDE.md`, the PWA templates (main's shell with our Spotify card and page), `Renderer.kt` (main's
  `Error.gated` defaults to false: the web player never sets it), the music app's manifest (main's network security
  config beside our WebView meta-data). Main changed neither the AIDL, nor `music_library.json`/`music_listing.json`/
  `music_icons.json`, nor `spotify_hosts.json`: ours stand; every copy is `cmp`-identical; no snapshot was regenerated
  (no test asked for it). `server/Cargo.toml`/`Cargo.lock`: main's (0.24.0, `chrono-tz`); this branch adds nothing.

**Design 30 vs this design (the adaptation, in the merge commit):**
- **A Spotify stream obeys design 30's streaming switch** (`stream_mobile_data`), never the downloads switch and not the
  old `mobile_data` (= both, an older app's): `spotifyPlayability(caps, streamMobileData)`,
  `spotifyNetworkLine(caps, streamMobileData)` and `itemPlayable(..., streamMobileData)` take it by that name;
  `PlaybackService.playable`/`playableFrom`/`refuseSpotify` and `SpotifyRenderer.networkLine` read
  `store.streamMobileData` (the old `store.mobileData` is gone on main). `RendererRulesTest`/`SpotifyRulesTest` name
  the switch; `GuardsTest` pins both read sites, the web player's, and that `SpotifyRenderer.kt`, `logic/Spotify.kt`
  and `RendererRules.kt` never name `downloadMobileData` or `storageLimit`. §0.2 assumption 3, §3.6's device card line,
  §5.8's "no Wi-Fi" row, §5.10's rule and its `playable` row, §8 item 1 and QA checklist item 7 say so; §6l's mobile-data
  line uses `stream_mobile_data` (with `download_mobile_data` on, to show it doesn't count).
- **The storage limit, the downloads switch, played extras and cache-through never touch Spotify**, pinned:
  `SpotifyRulesTest` "design 30 - a Spotify item is never a played extra, never downloaded, never under the storage
  limit" (a played Spotify row is pruned, never an extra, at depth 0, 5 and keep-all, held or not; nothing wanted, no
  Storytel floor, no `downloads` state); `GuardsTest`: `mediaItem`'s `through` is NRK/RSS only (a Spotify item has no
  `ItemTag` at all, so the guarded sink never sees one), `markPlayed` and `updateHeld` return unless
  `renderer.kind == LOCAL`, `activeFor`'s claim needs the queue's kind `LOCAL` (fix round, M1), `onError`'s changed-source reload (`playOnUncached`) is `LOCAL` only, and §5.10's
  list of `RendererKind.` sites holds main's four `LOCAL` lines; the `renderer.load(` count is 2 (`start`, and
  `playOnUncached`, the phone's own player's).
- Main's cleartext network security config doesn't widen the web player: `WebPolicy` refuses every http URL itself.
  It does make §6l's fake reachable from the phone at all: before the merge the music app had no config and refused
  every `http://10.0.2.2:8731` call of the debug switch (the Web API, the token route, the proxied SDK) - §6l part A2
  and A1's phone half work only since this merge (fix round, L3).

**Pins updated for main's code** (not behaviour changes): `start`'s no-playable branch now ends the queue's claims
(`updateHeld(loaded = true)`, main's) - our "NOT_DOWNLOADED only for another source" pin includes it; `onError`'s
signature and `gated` branch; main's own `offline` pin follows our Spotify clause; `comeHome`'s `activeFor` call.

**The suites on the merge result** (all in the merge commit):
- server (`CARGO_TARGET_DIR` in the scratchpad): `cargo fmt --check` clean (after `cargo fmt` on the new device-card
  assertions); `cargo clippy --all-targets` 12 warnings (`dns_engine.rs`, `handlers/devices.rs`, `models.rs`' dead
  fields - the known set, in files where this branch differs from main only by the Spotify field), none new;
  `cargo test` "test result: ok. 827 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out" in this run - but
  `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` is **not** reliably green: the merge's code QA
  found it failing 4 of 4 on this tree and 2 of 2 on main's own `571f86f` (its events are stamped `now + 1-2 s` and
  the notice clears only if the second PIN save lands after them). It is main's timing test, not this branch's;
  flagged to the main session in `qa-26c-merge-code.md`.
- launcher: `./gradlew assembleDebug testDebugUnitTest` BUILD SUCCESSFUL, 1145 tests, 0 failed.
- music: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD
  SUCCESSFUL, 809 tests, 0 failed (the first run: 4 `GuardsTest` pins failed on main's changed service text - fixed as
  above).
- `python3 -I scripts/fake-{sonos,storytel,spotify,audio}.py --selftest`: all four OK.

**Owed** (unchanged by the merge, plus): §6l part A on the user's emulator now also checks the streaming switch line
(`stream_mobile_data` false with `download_mobile_data` true: "Spotify - trenger Wi-Fi"); design 30's own owed checks
(its §8, `scripts/fake-audio.py`) are main's. Release order: Release's four steps (fix round, L2) - the launcher and
music releases that read design 30's two switches before any `server-v*` with design 30, then 0073's server, then
0074's (or both in one release, never 0074 first).

### Fix round after QA's code review (qa-26c-merge-code, 2026-10-13)

- **M1** (after a Spotify play the next podcast/Storytel queue skipped design 30's claim and stale check): pure
  `queueKind(current, entry)` (on the phone the phone's renderer for the entry, else the room) and `claimsQueue(kind,
  entry)` (`LOCAL`, not own files; main's `activeFor` condition, moved unchanged into a function) in `RendererRules.kt`;
  `playEntry`, `playItem` and `restore` pass `queueKind(renderer.kind, entry)` to `queueFor`, `activeFor` asks
  `claimsQueue(kind, entry)`. `RendererRulesTest`: LOCAL/SPOTIFY/SONOS x every source and Spotify, and the QA's
  scenario (a Spotify play, then an NRK item with a stale partial: `queueKind` gives `LOCAL`, it claims, and
  `staleThrough` marks the partial; the old `claimsQueue(SPOTIFY, nrk)` is false). `GuardsTest`: the three call sites,
  exactly three `queueKind(renderer.kind, entry)`, no `queueFor(`/`activeFor(` call passing a bare `renderer.kind`,
  `activeFor`'s `claimsQueue` line (main's own pin too); the `RendererKind.` site list drops the claim line (it is
  `claimsQueue`'s now). Mutation (`playItem` back to `renderer.kind`): `GuardsTest` red. Also tightened main's
  "start() giving up ends the claims" pin to our `if (first == null && !isSpotify(a.entry)) {` (it searched for main's
  `if (first == null) {`, which no longer exists, so it read the whole body). §5.10's row and the section above say
  the claim follows the queue's kind.
- **L1** (the "never a played extra" check couldn't fail): the case gains depth 1 with the older track `t1` played and
  held - outside the keep set (`t2`), listed and played, an extra for any other source. Mutation M (the Spotify block
  after the extras rule): red, "depth 1: never an extra"; reverted.
- **L2** (Release order against design 30's): Release now gives the exact order - a `launcher-v*` and a `music-v*`
  that read the two switches, then the first `server-v*` with design 30's 0073, then Spotify's 0074 (after it, or in
  the same release) - and why Spotify tolerates the apps first; the section above says so.
- **L3** (the cleartext config's comment): `network_security_config.xml` names `fake-spotify.py` (the Web API, the
  token route and the proxied SDK, debug switch only) and that the web player never loads http; the section above
  says §6l's phone half needs main's config.
- The section's `parent_pin` claim corrected (above): it passed in this branch's run, but QA saw it red 4 of 4 here and
  2 of 2 on main - main's timing test, owed to the main session.
- Validation: music `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`
  BUILD SUCCESSFUL, 811 tests, 0 failed (809 + the two new `RendererRulesTest` cases), `lintDebug` passed (no error;
  the known warnings); the four fakes' `--selftest` under `python3 -I`: all OK. No server or launcher file changed, so their
  suites weren't re-run.

## Step 6 implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of the merge's fix round (`665ea7b`), one commit: §11 step 6 - context mode in
the music app and the debug-only state recorder (qa-26c-step5b-code, "the state-recording gap"). No new dependency, no
contract file changed (`music_library.json`'s entry 7, the step 3 artist in context mode, is the one the tests and the
fake use), `server/` and `launcher/` untouched. **The host list stays provisional**: the user has not run the DNS
capture (§6.2, spike §7 item 10 step 7), so the three byte-identical `spotify_hosts.json` copies and the server's,
launcher's and music app's constants are unchanged, still marked PROVISIONAL - no host was guessed. The stable tags
wait for the capture (Release, "Before the stable tags").

- **The context item** (`M/logic/Spotify.kt`, `M/data/Snapshot.kt`): `isSpotifyContext(entry)`, `isContextItem(entry,
  key)`; `buildSnapshot` gives a context-mode entry its one synthetic item `contextItem(entry, live)` - key `context`, no
  url, no duration - whatever rows its last good list left in Room, so the tile shows (`Snapshot.entries`), the play
  order, `restore()`'s `storedPlan` and the stored queue find it; `visible()` shows it in both views (a room's queue drops
  it as every Spotify item, `speakerDropReason`). Its title is the live track's once one has played (deviation 1).
- **`spotify_context.json`** (`M/data/Store.kt`, `noBackupFilesDir`): `{"<entry key>": {"uri": "spotify:track:<id>",
  "title": "<Song — Artist>"}}` through the pure `SpotifyContexts` (decode drops junk rows; `next` writes only on a change;
  the title cut as the server's `cut_title`), tmp + rename, an in-memory copy, `spotifyContextFlow()` combined into
  `Store.snapshots()` (and `snapshot()`), so the synthetic item's title follows the live track.
- **The screens** (`M/logic/LibraryView.kt`, `M/ui/{UiModel,ListScreens,PlayerScreen,LibraryScreen}.kt`, strings):
  `Hint.Picks` - "Spotify · Spotify velger rekkefølgen" / "Spotify · Spotify picks the order" (`hint_spotify_picks`,
  both languages; 197.6 dp at Nunito Bold 12 sp, so 214 dp on a 13 sp tile in 288 dp - `FitTest`), whether started or
  not, and no progress line; the player shows the item's (the live) title and **no** "Spor N av M" (`showsNumber`);
  `skipSwitch` gives no instant view for a context (only Spotify knows where next goes); "Liste" shows the hint in place
  of "Spotify" and its one row, "Spilles nå" without a duration.
- **The tracker** (`M/spotify/SpotifyTracker.kt`): `loadContext(contextUri, live)`, `playedContext(offset, positionMs,
  fresh)`, `liveUri`, the event `TrackChanged(uri, title)`. A state is ours when its `context` is the entry's target and
  it isn't one of our list's adds left in Spotify's queue; every track Spotify plays there is ours (its own advance, a
  step through the SDK, the account's other controls) and moves the live track; anything else - another context, none
  (a uri list), our leftover add - is `NotOurs`, paused, and from then on every playing state until our next play, as
  in list mode. A start passes over the last state's track unless it is the sent offset near the sent position (an
  artist: near 0). The end: our live track paused at 0 right after it ran out (or Spotify back at another of its tracks,
  paused, then) is `Ended`; a track of another context right then is Autoplay after it (`NotOurs` + `Ended`). A step
  while paused that Spotify plays is `TrackChanged` + `Resumed` (the renderer decides, below). No `Unplayable`, no
  window, no queue add in a context (deviation 6). `ShuffleOrRepeat` as in list mode.
- **The renderer** (`M/spotify/SpotifyRenderer.kt`): `load` reads the entry's mode - a context loads its one item, the
  stored live track (`SpotifyPorts.contextTrack`) and shows its title at once (a restart: paused at the live track, no
  page, no network). A start is one `PUT .../play` with `PlayBody.Context` - a playlist from `offset.uri` = the live track
  at its place (`position_ms`), an artist `{"context_uri": ...}` alone (no resume, §12: its place shows 0) - with the
  same budget, retries, shuffle/repeat-off and held states as list mode; `tracker.playedContext` at the 204. `step(forward)`
  (`stepsInContext`) sends `vibb.next()`/`vibb.previous()` only while a track of our context is on the player and no
  start runs - else nothing (deviation 7). Each `TrackChanged` swaps the session item's title in place (the entry's cover
  stays its art), stores the live track (`storeContextTrack`), saves the place (the position row is the live track's) and
  - on a network the gate now refuses - pauses with the line (deviation 12). `saveNow` passes **no duration** for a
  context (never done). The service's art swap (`replaceItem`) keeps the live title. Ownership, `Ended`, "nothing starts
  by itself" (a paused step Spotify plays shows playing only with `playAllowed` and focus, else `vibb.pause()`) and the
  detached rule are list mode's.
- **The service** (`M/playback/{PlaybackService,RendererRules}.kt`): `keepsDuration(entry, key)` - the Ready handler
  keeps no duration for the context item and `queueEnded` never marks it done; the context's end (`clear_state`) also
  drops its `spotify_context.json` row; `stepsOnWebPlayer(entry)` - `skipNext`/`skipPrevious` go to the web player's
  `step` for a context (after the network gate, `refuseSpotify`), never the one item's rules; `reportNowPlaying` sends
  the live title (the session item's) and a track change is a new report (Home's card, the lock's tile).
- **The state recorder** (§9.4; `M/spotify/SpotifyRecording.kt`, `SpotifyPorts.recordState`/`record`,
  `src/debug/.../DebugSpotify.kt`): the main source set has the port and one hook, `SpotifyRecording.recorder`, declared
  null and assigned only in `src/debug` - `AndroidSpotifyPorts.recordState(json)` is `recorder?.state(now(), json)`, a
  no-op in a release (it has no debug code). `DebugSpotify.applyRecorder` (the receiver and `DebugStartup`, beside
  `applyBase`) switches it with config.json's `"record_spotify_states": true`, appending to
  `debug-feed/sdk_states/<UTC yyyyMMddTHHmmssZ>.jsonl` on its own thread: `{"t": <elapsed ms>, "state": <the page's
  compact JSON, re-encoded on one line>}` per posted state, and `{"t": ..., "event": "<name>"}` per page event (`ready`,
  `not_ready`, `error <kind>` - never the SDK's text -, `gone`) and per call of ours (`play from 0 to 2 at 0 ms`, `play
  context`, `play context from the live track at 42000 ms`, `queue 51`, `pause`, `resume`, `seek`, `next`, `previous`).
  The JSON reaches the renderer in `HostEvent.State.json` (an explicit `toString` leaves it out); no log line anywhere
  carries it (§7.1).
- **The fake** (`scripts/fake-spotify.py`): a context play plays the context's tracks - the fixture playlist's listed
  tracks, the fixture artist's album, else `FAKEtrack` 1..5 (the library snapshot's entries 6 and 7) - from the offset's
  index (none, or a track not in it: the top) at `position_ms`; a context that is neither a playlist nor an artist is a
  400; `--autoplay` after a context plays in its own context (`spotify:station:FAKEautoplay000000001`); the stub's
  `previousTrack` follows Spotify's rule (after 3 s a restart, else the track before) and its `nextTrack` goes on through
  the context (the end: paused at 0). `--selftest`: the context forms (offset index, the artist's album from Spotify's
  start, an offset not in it, a refused context), Autoplay's context, the stub's previous and offset as text, the context
  `sdk_states` files' shape, entry 7's empty listing and its context's tracks.
- **§6l** (`docs/testing/emulator.md`): A1 - the artist's tile and play (one `play: context …` line, no number, the
  title following next/previous, Spotify's previous rule), the 403 playlist (Remove `fakeparent`, Check now: context mode,
  a restart goes on at the live track - `(track 2 of 3) at <the place> ms` -, `spotify_context.json`, back to list mode
  after a new login); A2 - the artist (entry 7) to its end, entry 6 flipped to context mode (a restart at the live track,
  `--autoplay`, `--transfer-in`), the state recorder (switch on/off, the JSONL's lines, no token, no state in logcat);
  part B - real context sequences recorded with the recorder, and a real artist and a playlist the parent doesn't own.
- **Tests**: `SpotifyTrackerTest` - four new `sdk_states/` sequences (`context_artist`: a foreign state before the play,
  Started + TrackChanged, its own advance, a step, our pause and resume, the end, nothing after it; `context_playlist`: the
  stored live track's offset, a stale state far from the place passed over, Spotify's previous restart and back, next;
  `context_foreign`: a uri list and another context paused, a re-play, LOST after it; `context_autoplay`: Autoplay's own
  context after the last track is `NotOurs` + `Ended`) replayed by the same harness (`context`/`live`/`played_context`), plus
  four synthetic cases (titles, no unplayable, a paused step, the leftover add, the stale start, the artist's start
  near 0). `SpotifyRendererTest` (+6): the playlist context's one `PUT` with offset and place, every track of it ours, the
  title swapped and stored, no duration saved, the art swap keeping the title, `vibb.next()`/`previous()` with no Web API
  call; another context and a uri list paused, nothing stored; a refused network at a track change; an artist from
  Spotify's start, `Ended`, nothing after it; nothing self-starting (a restore opens nothing, a step without our context
  sends nothing, a paused step Spotify plays paused while the app may not play, detached: paused, no step); the recorder's
  lines (states as received, our calls and events by name, never the SDK's text, no state in the log). `SpotifyRulesTest`:
  the 5b "context shows nothing" test became "shows as one tile" (both views, the stable key, the hint, the uri, the room's
  drop) and "a restart restores it paused at the live track" (the title, `nowView` from the stored row, `storedPlan`, a
  cover tap at the place); `SpotifyContexts`' round trip and refusals; `contextItem`'s live title; `keepsDuration`,
  `stepsOnWebPlayer`. `UiModelTest`: a context entry's `playerNumber` 1 of 1 and `playerNumberOf`, `listOrder`,
  `showsNumber` false (true for a list entry), no `skipSwitch`, `Hint.Picks` and no progress, the stored account line still
  wins, a cover tap's view at the place, none on a room. `LibraryViewTest`: the hint started or not, resume off, the one
  row. `FitTest`: the picks hint on a tile and in the list's header at 320/360/411 dp. `GuardsTest` (+1): the Ready
  handler, `queueEnded`, the steps (gated first), `reportNowPlaying`'s title and key, the renderer's null duration and
  the move-on's intent, `spotify_context.json` the store's alone, the snapshot's wiring, the UI's two sites.
  `SourceScanTest` (+1): the recorder's hook declared null and assigned only by `DebugSpotify.kt`, no `sdk_states` or
  `record_spotify_states` in main, the writer's path and line shape, its log lines without the JSON, both start-up
  sites, the port's two bodies, the one `recordState` call, the bridge passing the JSON on untouched, `State`'s
  `toString`, and no `log(`/`Log.` line in `spotify/` or the service naming a `json`. Checked by mutation (three at once, then
  reverted; five tests red): context ownership without the context test (the `sdk_states` replay, the synthetic
  "not ours" case, the renderer's "another context"), a context's save with the state's duration (the renderer's
  playlist case), a step sent with no context of ours on the player (the renderer's "nothing starts by itself").
- **Validation** (from `music/`): `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true --rerun-tasks` BUILD SUCCESSFUL, 828 JVM tests (811 before), 0 failures; lint "0 errors, 42
  warnings" (the merge's 42, none in a line this step wrote). `python3 -I scripts/fake-{spotify,storytel,sonos,audio}.py
  --selftest`: "FAKE SPOTIFY SELFTEST OK", "FAKE STORYTEL SELFTEST OK", "FAKE SONOS SELFTEST OK", "FAKE AUDIO SELFTEST OK".
  The server's and the launcher's suites not run: no file their tests read changed. The new hint's widths were measured
  from `res/font/nunito_{bold,extrabold}.ttf`'s advances (the script reproduces `FitTest`'s 127.7 and 174.2).

Deviations from the spec (each with its reason):
1. **The synthetic item's title is the live track's** once one has played (`contextItem(entry, live)`; §5.9: "title the
   entry's name"), the entry's name before: the player, the list's one row, the now-playing bar after a restart, the
   session's item at a load and search all read the item's title from the snapshot - one source, so "a restart restores
   it paused at the live track" shows that track. **The tile's hint is `hint_spotify_picks` only** (§4.3, §5.9's first
   bullet): §5.9's "the tile's hint ... read it" would put a "Song — Artist" beside the attribution in one 13 sp line; the
   bar under the carousel shows the live title.
2. **`spotify_context.json` stores the track's uri** (`"uri": "spotify:track:<id>"`), not its id (`"track": "<id>"`): a
   playlist context also plays episodes, and the start's `offset.uri` needs the type.
3. **Who writes the file**: the renderer through two new ports (`contextTrack`, `storeContextTrack` -> the store on the
   service's writer); the service drops the row in `queueEnded` (§11 names `Store.kt` and the service's `Ended`).
4. **The title swap is the renderer's own** (its session item, `showTitle`), not a `Renderer.replaceItem` call from the
   service: the renderer owns the session's MediaItems and learns the track first; the service's own `replaceItem` (a late
   picture) keeps the live title.
5. **No "neste: N spor"** in the list (§5.9's last bullet): the state's `next` carries at most three uris (the SDK's
   `next_tracks` usually two), so the line would always say 2 or 3. Whether `next_tracks`' names are worth showing stays
   §5.9's device check.
6. **No unplayable item in a context**: a `playback_error` there is only logged - Spotify skips the track in its own
   order, and the synthetic item isn't that track (an `unplayable` on `context` would mark the whole entry "Kan ikke
   spilles"). A context the account can't play at all ends as "trenger internett" after the start's 10 s first-state
   wait [device check: what the real SDK posts].
7. **A step with no context of ours on the player sends nothing** (a restored queue, a page gone idle, a start on its
   way, detached): with no SDK player only Spotify knows the next track; the next play goes on from the live track. A
   step while paused is `vibb.next()` too; if Spotify plays it, the renderer shows it playing only with `playAllowed` and
   the focus (else `vibb.pause()` at once) [device check: whether the SDK's `nextTrack` plays a paused player].
8. **An artist's place shows 0** (no resume, §12) although the service passes the stored position; its position row
   still records the live track's place (never used for an artist's start).
9. **The end and Autoplay** (§5.9 names only "a playlist context that plays out is `Ended`"): the live track paused at 0
   right after it ran out - or Spotify back at another of its tracks paused then - is the end for an artist too; a track
   of another context right then is Autoplay (`NotOurs` + `Ended`). If the real SDK keeps the context's uri for
   Autoplay's tracks, they would read as ours [device check, QA checklist item 17 with a context].
10. **"Ours" also refuses a state with no context, and our list mode's leftover add** (§5.9: "a state whose `context` uri
    isn't the entry's target"): a uri list is another app's play; the add is ours in list mode only.
11. **The recorder's plumbing** (§9.4: "called by the page bridge's `state()`"): the bridge posts the JSON with the parsed
    state (`HostEvent.State.json`) and the renderer, which has the ports, records it on the main thread; the hook is
    `SpotifyRecording.recorder` (main: null, assigned in `src/debug` only - the main source set can't name a debug class);
    the events have their own port (`record`). The file is named by the UTC time the recorder was switched on (a process
    start or the broadcast).
12. **A context's track change on a network the gate refuses pauses with the line** (not in §5.9): list mode's advance
    is gated by the service's `onItemChanged` (§5.10), a context's index never changes, so the renderer gates it.
13. **The move-on after an unplayable plays only with the intent** (`moveOnTo`: `pwr && ports.mayPlay()`; step 5a's
    file): found while testing the recorder - a `playback_error` on a paused track made the service's move-on start the
    next track by itself; now it only moves the index, as a step while paused does ("nothing starts by itself",
    qa-26c-step5a-code H1's rule).
14. **The fake**: a context of another kind is a 400 (only playlists and artists are contexts here); Autoplay after a
    context plays in its own context; its previous follows Spotify's 3 s rule. `HostEvent.State` is no longer a data class.

Found in the spec:
- §5.9's "title the entry's name" against "a restart restores it paused at the live track" and the live title on the
  screens (deviation 1); the file's `"track": "<id>"` can't name an episode (deviation 2).
- §5.9's "neste: N spor" can't be more than 2 or 3 (deviation 5).
- §9.4's recorder "called by the page bridge's `state()`": the bridge has no ports (deviation 11).
- Step 5a's `moveOnTo` started a paused queue on a `playback_error` (deviation 13, fixed).
- §5.9 rests the whole ownership rule on the state's `context` being the entry's target in **every** state, around track
  changes too. ~~The documented `WebPlaybackState.context` says so~~ - **corrected in the fix round** (qa-26c-step6-code
  M2): the reference says the opposite may happen, `uri: ... // The URI of the context (can be null)`; the rule now
  reads a `null` context on our live track or the named next track as ours (below).
- What the Web API answers when a playlist context's offset track has left the playlist is not documented [device
  check]; ~~the fake plays from the top~~ - the fix round adds the fallback (one play from the top) and the fake's
  `--offset-404`.
- Nothing in §11 step 6 was impossible.

Owed: **the DNS capture** (§6.2; the user's: it replaces the three provisional `spotify_hosts.json` copies and the three
constants in one commit before any stable tag); **§6l not run here** (no emulator, no VM, no account): A1's artist and
403-playlist lines, A2's two context lines and the recorder line, part B's recorded real sequences (then turned into
`sdk_states/*.json` for `SpotifyTrackerTest` - the main session's follow-up) and its real artist and playlist the
parent doesn't own; QA checklist item 18 (the artist option's dev-mode check) and item 17 with a context (Autoplay
after it); [device check]: the SDK's `context` in every state, a paused `nextTrack`, an offset no longer in the
playlist, the player's account opening a playlist it doesn't own (public, shared, followed; else "spør en voksen"),
what the real SDK posts at a context's end. Step 7: `music/CLAUDE.md` (context mode, `spotify_context.json`, the
recorder and its hook).

### Fix round after QA's code review (qa-26c-step6-code, 2026-10-13)

All eight findings fixed; none disputed. No contract file changed, no new dependency, `server/` and `launcher/` untouched,
the host list unchanged (still provisional). Every red test the QA quoted is rebuilt in the suite and green.
- **M1** (`Ended` without evidence): the end in a context needs evidence - our live track paused at 0 after it ran out,
  or another of the context's tracks paused at 0 then that our last state didn't name next (the tracker keeps the last
  state's `next`, `lastNext`); a track of another, **non-null** context right then is Autoplay. Our list's leftover add
  is `Event.LeftoverAdd` - used up once seen (its later states until another track are still it), never ours, never the
  end, never measured; the renderer skips it with one `vibb.next()` while the kid wants it playing (also when the kid's
  own next lands on it mid-track: the context isn't lost), else pauses it. Deviation 9 replaced. Tests: the QA's three
  cases, the "next on a leftover add" case, `sdk_states/context_leftover_add.json` (a list play past its window, a
  switch, a context; the harness's new `load_context` step), and two renderer cases (skipped once while playing; paused
  while the kid has the context paused).
- **M2** (a `null` context paused our track): with no context, our live track and the track our last state named next
  are ours; any other track is a uri list - not ours, paused, never the end; a start counts a context-less state of the
  sent offset at its place. Deviation 10 and the spec finding corrected (the reference says `context.uri` "can be
  null"). Tests: the QA's case and three more (a uri list after a run out, Autoplay's non-null context, the start).
- **M3** (a stored track that left the playlist): a context start whose play call carrying the offset is refused (a 404
  after the transfer, a 400 - `CONTEXT_REFUSALS`, only once the play call itself went out) drops the offset - the
  tracker's (`dropLive`) and the file's row - and plays the context from the top with **one** more call in the same
  budget (no shuffle/repeat calls again). `SpotifyApi` now names the transfer's own 404 `Unreachable("transfer")`
  (the device - "trenger internett"), so only the play call's 404 after a 204 transfer reads as the context's refusal
  (`SpotifyApiTest`). A context play without an offset still refused is
  `SpotifyProblem.CONTEXT_REFUSED`: "Spotify - spør en voksen" on its tile, **no stored word** (one playlist, not the
  account); a 403 stays `REFUSED` (§5.4's every 403). **The text was made to match the code**: §5.9 now says 403 ->
  `REFUSED`, 404 after the transfer without an offset -> `CONTEXT_REFUSED`, and that the parent's card doesn't name the
  playlist (no report field; a later contract change). The fake's `--offset-404` (+ `--selftest`), §6l A2's line.
  Deviation 6's "trenger internett" for a context the account can't play is replaced. Tests: the QA's case (offset,
  offset, then one from the top; the row dropped) and the refused context (two plays, the line, no word).
- **L1** (a paused step played): a step sent while paused is marked (`stepWhilePausedAt`, 10 s); a `Resumed` Spotify
  makes right after it is paused at once (`vibb.pause()`, no focus) - the title still moves. Deviation 7's second half
  replaced. Test: the QA's case (and a playing step goes on playing).
- **L2** (an artist's place on the screens): one pure rule, `contextResumes(entry)` (a playlist context true, an artist
  false, every other entry true), read by the renderer (`contextOffsets`) and the UI (`playSwitch`'s start, `nowView`'s
  stored row): an artist shows 0 everywhere. Deviation 8's "although" gone. Tests: `UiModelTest` (artist 0, playlist its
  place, a pick of the row, the restored bar).
- **L3** (the recorder): one daemon writer thread for the process (named `spotify-state-recorder`), a 32 MiB cap per file
  (one `Log.i` when full); §9.4 and §6l part B say what the compact state records (the track, `paused`, `position`, the
  track window's `next`/`prev` up to three, `context` - null for no object and a null uri alike - not `disallows`).
  `SourceScanTest` pins the cap, the single daemon executor, none per file. The page's posted shape is not widened (see
  below).
- **L4**: `Store.spotifyContexts()` is a lock-free read once loaded (the service's snapshot, on IO before every start and
  restore, loads it), so the renderer's `load` on main never waits on the file or the writer; a parsed library prunes the
  rows of entries no longer in context mode (`pruneSpotifyContexts`, from a service collector on the writer); deviation
  13 has its own test (a `playback_error` on a paused list track: the index moves, no `PUT`); the end's second clause is
  pinned (a context's last track ran out, then its first track paused at 0, not named next: `Ended`; mid-track: a
  change, paused). `GuardsTest` pins the prune, the lock-free read, the rule's two readers and the fallback.
- **L5**: §5.9 rewritten as built (the uri, the title, the title swap, the ownership with a null context and the
  leftover add, the end on evidence, the 403/404 lines, the fallback, the paused step, no "neste"); §9.4's recorder
  plumbing; §11 step 6's file list; §0.4, §6.2, §11's order and §14 item 3 say the capture lands "before any stable tag"
  (the JSON comment of `spotify_hosts.json`, a contract file, is left to the capture's commit); §6l's part B context line
  (M2), its real-playlist line (M3), A1's paused next (L1), A2's `--offset-404` and leftover-add lines.
- **On the coordinator's "the `context` and `track_window` per state"** (L3): the recorded state is the page's compact
  state, which already carries the context's uri and the track window (`uri`/`linked` of the current track, `next` and
  `prev` up to three each) - what part B's questions read. Widening the page's post (the context object's presence,
  `disallows`) would change a pinned shape (`SdkState.KEYS`, `PlayerJsTest`, every `sdk_states` file and the fake's
  check), so it stays the separate change the QA named; after M2 the tracker reads "no object" and "uri null" alike.
- **Validation** (from `music/`): `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
  -PwarningsAsErrors=true --rerun-tasks` BUILD SUCCESSFUL, 843 JVM tests (828 before), 0 failures; lint "0 errors, 42
  warnings" (the same 42). The four fakes' `--selftest` under `python3 -I`: "FAKE SPOTIFY SELFTEST OK", "FAKE STORYTEL
  SELFTEST OK", "FAKE SONOS SELFTEST OK", "FAKE AUDIO SELFTEST OK". Checked by mutation (three at once, then reverted;
  eight tests red): a `null` context never ours, the end without the "not named next" evidence, the leftover add not
  told apart.


## Step 7 implementation status (2026-10-13)

Built on branch `music-3-spotify` on top of step 6's fix round (`f59f472`), one commit: §11 step 7, docs only. No code,
no contract file, no migration, no `Cargo.toml` (below); the shared files are byte-identical as step 3 left them.

- **`music/CLAUDE.md`**: the intro names 26c; the layout gains `spotify/` (every file and what it holds, the page in
  `assets/spotify/`, `bridge/SpotifyBridgeToken.kt`), the third renderer and `RendererRules.kt` under `playback/`,
  Spotify's files in `noBackupFilesDir` under `data/`, and `DebugSpotify.kt` in `src/debug/`; "Build and test" gains
  `spotify_hosts.json` among the shared files, the Spotify fixtures shared with `scripts/fake-spotify.py`, its options
  and its fake values. New rules after Storytel's: the web player as the third renderer (list mode's window, queue
  add, shuffle/repeat off, the SDK's pause/resume/seek, coalesced steps, no send on Spotify's advance); ownership (only
  our uris, relinks, everything else paused and never saved, unplayable only from the SDK, 403 an account matter, the
  confirmed `null`, measured positions); nothing starts by itself (no page at a restore, the intent rule, when the page
  dies, **detached**, `onError` synchronous); context mode as built; **the stream switch** (design 30's
  `stream_mobile_data`, never the downloads switch or the limit, `refused(line)`, nothing fetchable, kept, written
  through, extra or claimed, **`queueKind`/`claimsQueue`**); the lines and the stored word; the WebView rules (§7.2 as
  built); the token path; the secrets rule (no `Log.` in `spotify/`, the console swallowed, the recorder debug-only);
  the debug switch's six readers and §6l; the owed device checks. Two existing lines widened: the kid's screens' "no
  web views" (the one headless WebView) and "never write through" (Spotify too).
- **`server/CLAUDE.md`**: a section "Vibb music: Spotify on the server" (steps 1, 2a, 2b, 3 in one voice): bring your
  own app, the accounts and their 6-month line, PKCE as built (the in-memory pending logins, the `spotify_login`
  cookie, the page's own CSP, **the login-address rule**, the https-only suggestion, the paste), **the callback's and
  the done page's exemption** (`public_routes`, still behind the admin gate) and why, the tokens (the spawned
  `MintJob`, rotation written before use, `invalid_grant`/`invalid_client`, `api_get`, the quota pause, one gate,
  `Reach::Any` only with the override), **the phone's token route** with its order, limit and **8 s budget** (the
  spawned refresh kept), links and entries, **`ReaderTurns`** and the two listing modes (the artist option, the
  snapshot gate, the cadence), **the gate**, the binding and the cards, **the DNS binding override and the `/dns`
  marks**, **migration 0074 and the backup hazard**, **the environment** (`SPOTIFY_CLIENT_ID`,
  `SPOTIFY_REDIRECT_URI`, the loopback-only `SPOTIFY_ACCOUNTS_BASE`/`SPOTIFY_API_BASE`, `SPOTIFY_ARTIST_ALBUMS`,
  `INSECURE_COOKIES`), the secrets (the no-`Debug` types as `secret_types_have_no_debug` lists them), the tests.
  Elsewhere: `music_entries` takes `spotify`, the binding column, the policy's `spotify` key, the import's Spotify
  rows, the sweep's "What", `art_in_library`'s Spotify gate, the data model's two tables, Play's DNS bullet (the
  per-phone split). The "Data model" paragraph on 0074 (the merge's) stands.
- **`launcher/CLAUDE.md`**: a Spotify bullet in the music section - the token courier (status mapping, no cache, no
  store, no log, older apps either side), the gated DNS exemption (`classifyDomain` before the suffix walk, **the flag
  set per phone at every sync and at the VPN's load**, `musicSpotify`'s leniency, the provisional list), the WebView
  provider in `neverRestrict`, the tests; the Sources/DNS line and the bridge-method count (13) follow.
- **Root `CLAUDE.md`**: steps 3-6 had already written `scripts/fake-spotify.py` (§6l), the CI paths of all three
  workflows (checked against `launcher.yml`, `music.yml` and `server-ci.yml`'s filters) and `spotify_hosts.json` among
  the shared files; only the `music/` layout line gained "Sonos 26a, Storytel 26b, Spotify 26c". The root names no
  migration numbers, so 0074 isn't there (it is in `server/CLAUDE.md`).
- **`NOTICE.md`**: a Spotify paragraph - `spotify.py`'s link grammar (`URI_RE`, `LINK_RE`, `to_uri`, short links)
  and its per-context bookmark rule (`bookmark_step`/`save_bookmark`/`read_bookmark`, `daemon.py`'s `play_origin`),
  and `library.py`'s precache gate and `context_tracks` rows, each with where it lands; what was written here, not
  ported; that Spotify's SDK is never in the repository or an APK. vibb's licence checked at
  `/home/user/palchrb/vibb/LICENSE`: "MIT License, Copyright (c) 2026 palchrb" - named exactly; its notice is the
  existing `music/app/src/main/assets/licenses/MIT-vibb.txt`, so **no new license file** (Storytel needed one only for
  its second upstream, storytel-player).
- **Design 26**: a phase 3 bullet in "Where things stand" and the phase's status line. **`PLAN.md`**: phase 3's status
  (built, owed, the release path, the failed 0.24.0 run) and the long list's Spotify item.
- **The Release** (above), rewritten as the final one. What changed from the draft, and why:
  1. **The server version, settled: Spotify is 0.26.0, and the bump is not on this branch.** 26b's Release made the
     bump "one commit on `main` after the merge" (a version names one migration set; a `main` build already reports
     the version in `Cargo.toml`), and this does the same - so `Cargo.toml`/`Cargo.lock` are unchanged here and no
     server suite was needed. What changed underneath: `server-v0.24.0` was tagged on `6ae54e8` with Storytel's 0072
     (26b planned 0.25.0), so `main`'s next server - design 30's 0073 - is 0.25.0, and Spotify keeps the 0.26.0 §0.4
     gave it. If 0.25.0 is never tagged, 0.26.0 carries both migrations (the order allows it); Spotify is never 0.25.0.
  2. **The launcher tag: `launcher-v0.26.0` (26000)**, above `launcher-v0.25.0-rc.1` (24999; `git tag -l
     'launcher-v*' | sort -V | tail -3` gave `launcher-v0.2.7`, `launcher-v0.25.0-rc.1`), the number 26b's open point
     3 proposed; the next minor above the highest tag if `main` tags one first.
  3. **`music-v0.4.0-rc.1` = 3999** checked against `music.yml`'s formula (`major*1000000 + minor*1000 + patch`, an RC
     minus 1); 26b's `music-v0.3.0-rc.1` was never tagged, so 3999 is above every music tag either way.
  4. **The capture (and §6l part A) before any tag of this release**, not only "before the stable tags": the server
     has no RC form, the launcher's tag is stable (it becomes "latest"), and both ship the host list to every bound
     phone, so their tags are the first stable tags of the release (§6.2's "before any stable tag", read literally); part A is steps 5b and 6's
     acceptance, never run. The rest - part B, the Jelly Star, the device checks with a default - before the stable
     `music-v0.4.0`.
  5. **Found while writing**: `server-v0.24.0`'s release run **failed at the signing step** (`server-release-sign`,
     "Sign the tarball and update.sh"; the GitHub REST API on 2026-10-13: no GitHub release for it - nor for any
     `server-v*` in this repository, `server-v0.19.0`'s run failed too) - main's to fix, now step 0 of the order and
     stated in PLAN.md. The order's four steps
     are the merge fix round's (L2), unchanged in substance.
  6. The owed checks in one list each (before the tags; before the stable music), the open points pruned from §14 to
     the nine that stand (the QA's two questions as assumptions, the wake lock, the logo, Sonos, `parent_pin`, the
     backup check, design 31 step 12's "From Spotify ›" row and the callback that never moves - reworded in the
     fix round, M2 -, the later contract fields), and the **DEPLOY.md
     note** as a block to paste (the env variables in DEPLOY.md's table form, the login's https-or-paste rule, keys and
     backups, what it fetches, the phones' DNS override, the testing-only overrides, the order with 0073 -> 0074, a
     smoke check, the rollback).
- **Validation**: docs only. `python3 -I scripts/fake-spotify.py --selftest`: "FAKE SPOTIFY SELFTEST OK". No test reads
  a `CLAUDE.md`, `NOTICE.md`, `PLAN.md` or a design doc (grepped in `server/src`, both apps' `src/test`, `scripts/` and
  the workflows: only comments name them), so no suite needed a run.

Found, not changed: `launcher.yml`'s filter also watches `music/app/src/main/AndroidManifest.xml`, which root
`CLAUDE.md`'s CI line doesn't name (from design 21 step 3, not this branch's); design 26's phase 1b and 2 lines still
describe their branches as unmerged (main's to update).

Owed: everything in the Release's two lists - the DNS capture's one commit and §6l part A before any tag; part B, the
Jelly Star (QA checklist items 1-21, the Doze run) and the device checks before the stable `music-v0.4.0`; the merge,
the server bump and the tags are the user's; the DEPLOY.md note is the main session's to paste.

### Fix round after QA's review (qa-26c-step7-docs, 2026-10-13)

All eight findings fixed; none disputed. The shared files are unchanged (the three `spotify_hosts.json` copies still
byte-identical, still provisional).
- **M1** (the capture's commit would turn three suites red): fixed at the root rather than by describing the test
  edits - the three host-list tests no longer read the comment's wording. `spotify::tests::hosts_match_the_contract`
  and both apps' `SpotifyHostsTest` now pin the list's **shape** (exactly `comment`, `suffixes`, `exact`; a non-blank
  comment; non-empty lists of distinct lower-case host names without a leading, trailing or double dot; no exact host
  under a suffix) besides the constants and the copies' identity (the server's byte comparison, `ContractFilesTest`,
  `MusicPlanTest`, unchanged). So the capture is data only: **one commit of the list, the constants and the
  comment** - the three copies (the comment without "PROVISIONAL", naming the capture's date and source), the **six**
  constants and their doc comments - as the Release, `server/CLAUDE.md`, `launcher/CLAUDE.md`, `music/CLAUDE.md`,
  root `CLAUDE.md` and `PLAN.md` now say (none names a test). The constants' doc comments (`spotify.rs`, both
  `SpotifyHosts.kt`) lost their passed deadline ("step 6 at the latest") and point to the Release; the JSON's own
  comment keeps it until the capture rewrites it (a contract file).
- **M2** (open point 8): rewritten as a request to the main session - design 31 doesn't name Spotify, so its step 12
  must take "From Spotify ›" as a fifth Add row and may move the app and account cards behind a redirect, but
  `/music/spotify/callback` and `/done` never move (`CALLBACK_PATH`, the registered redirect URI, the cookie's
  `Path`, `.env`). Said in `server/CLAUDE.md`'s Spotify section and in `PLAN.md` (it has no design 31 entry: the
  line is in phase 3's paragraph). Design 31 itself is main's and not edited.
- **L1**: the 0.24.0 run's cause as the QA read it from the log - the environment rule proven, the secret set but
  unreadable (`Error while loading the secret key file`) - and the remedy (the whole two-line key file from
  `minisign -G -W`, the pinned pair, then re-run the failed job), in the Release's facts, the environment bullet, the
  order's step 0 (main's) and `PLAN.md`.
- **L2**: `SHOWS_PLAYABLE` doesn't exist - the Release, `music/CLAUDE.md` and a note in §3.1 say no switch is built
  (the parser's refusal would be a small code change).
- **L3**: the DEPLOY note's `SPOTIFY_ARTIST_ALBUMS` moved into the `.env` table as the opt-in it is (`1`/`true`); the
  testing bullet keeps the two base overrides and `INSECURE_COOKIES`; both short-link hosts named; the note's place in
  DEPLOY.md (after "Vibb music: episode art", before "Server 0.23.0"). `server/CLAUDE.md` calls it an opt-in too.
- **L4**: the launcher's "inert without Spotify" names the exception - the WebView provider in `neverRestrict` on
  every phone; `launcher/CLAUDE.md` says "on every phone" too.
- **L5**: step 0's migrations by design (0070-0072: 27, 26a, 26b); step 2's RC needs the music row's "include
  prereleases", else step 3 waits for a stable `music-v*`; the merges named with their merge commits (`5d6bd5b`,
  `e4d4feb`) here and in design 26; part B's list gains the cold start's `needToken` -> `vibb.token` -> `ready` order.
- **L6**: the two cross-references name the real places (`launcher/CLAUDE.md`'s Spotify bullet, `server/CLAUDE.md`'s
  "Vibb music: Spotify on the server").
- **Validation**: server (from `server/`) `cargo fmt --check` clean (after `cargo fmt` on the new test), `cargo test`
  826 passed, 1 failed - `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`, main's timing test (open
  point 6), the only failure; `spotify::tests::hosts_match_the_contract` green. Launcher `./gradlew assembleDebug
  testDebugUnitTest` BUILD SUCCESSFUL, 1146 tests (1145 + the new shape test), 0 failed. Music `./gradlew
  assembleDebug testDebugUnitTest -PwarningsAsErrors=true` BUILD SUCCESSFUL, 844 tests (843 + 1), 0 failed; both
  `SpotifyHostsTest`s 3 tests. (`assembleDebug` because two main-source files changed - the `SpotifyHosts.kt` doc
  comments only.) `python3 -I scripts/fake-{sonos,storytel,spotify,audio}.py --selftest`: all four OK.

## Step 8 implementation status (2026-10-14)

Built on branch `music-3-spotify` on top of step 7's fix round (`d23038a`), one commit: §11 step 8, added after step 7
by the user's two decisions of 2026-10-14 (verbatim): "Jeg er enig i at barna skal ha en konto hver (1 pr tlf), men
ser ingen grunn til at min konto skal involveres. Jeg fikser vel selv å paste in en linke fra alt spotify innhold de
skal ha tilgang til. I tillegg tenker jeg at vi ikke trenger noe spor av spotify noe sted i appen? For barna skal jo
dette bare være en tile til? Samme hvilke kilde?" - (1) one Spotify account per phone, the child's own, the parent's
never logged in, the parent pastes links; (2) no trace of the word "Spotify" in the kid's music app: a Spotify entry is
a tile like NRK, RSS, Storytel or own files. No contract file, no migration, no dependency, no route; `launcher/`
untouched; the account machinery (N accounts, a per-phone binding, any account a reader) is unchanged.

- **The music app, source-neutral** (§4.3, §5.8, §5.9). What the kid sees, old -> new (nb / en):

  | Where | Through step 7 | Step 8 |
  |---|---|---|
  | the cover's kind chip, the search row's kind: a playlist, album, track or artist | "Spotify" / "Spotify" | "Musikk" / "Music" (`kind_music`, new) |
  | the same for a show or an episode | "Spotify" / "Spotify" | "Podkast" / "Podcast" (`kind_podcast`, NRK's and RSS's) |
  | the tile's hint (list mode) | "Spotify · spor 3 av 12", "Spotify · episode 2 av 5", "Spotify · fortsetter der du slapp" ... | "Musikk · spor 3 av 12", "Podkast · episode 2 av 5", "Musikk · fortsetter der du slapp" ... |
  | the context hint (tile and "Liste"), `hint_spotify_picks` | "%1$s · Spotify velger rekkefølgen" / "%1$s · Spotify picks the order" (read "Spotify · Spotify velger rekkefølgen") | "%1$s · rekkefølgen velges for deg" / "%1$s · the order is picked for you" (read "Musikk · rekkefølgen velges for deg") |
  | the player, under the title | "Spotify" (`kind_spotify`, 11 sp) | nothing |
  | "Liste", in the offline summary's place (list mode) | "Spotify" | nothing (a context: the context hint, as before) |
  | `SPOTIFY_NEEDS_WIFI` | "Spotify - trenger Wi-Fi" / "Spotify - needs Wi-Fi" | "Trenger Wi-Fi" / "Needs Wi-Fi" |
  | `SPOTIFY_NEEDS_INTERNET` | "Spotify - trenger internett" / "Spotify - needs the internet" | "Trenger internett" / "Needs the internet" |
  | `SPOTIFY_UNAVAILABLE` | "Spotify er ikke tilgjengelig nå" / "Spotify isn't available right now" | "Ikke tilgjengelig nå" / "Not available right now" |
  | `SPOTIFY_ASK_ADULT` | "Spotify - spør en voksen" / "Spotify - ask a grown-up" | "Spør en voksen" / "Ask a grown-up" |
  | `SPOTIFY_NOT_ON_PHONE` | "Spotify virker ikke på telefonen" / "Spotify doesn't work on this phone" | "Virker ikke på telefonen" / "Doesn't work on this phone" |
  | `SPOTIFY_ELSEWHERE` | "Spotify spiller et annet sted" / "Spotify is playing somewhere else" | "Spilles et annet sted" / "Playing somewhere else" |
  | `SPOTIFY_STOPPED` | "Spotify stoppet - trykk spill" / "Spotify stopped - press play" | "Stoppet - trykk spill" / "Stopped - press play" |

  Unchanged: "Spor N av M" / "Episode N av M" in the player, "Nytt spor" / "Ny episode" in Nytt, "Kan ikke spilles" for
  an unplayable track, "Kan ikke spilles på Sonos" on a room - none named Spotify. The session's metadata (the
  notification, Home's card, the lock's tile) was always the entry's name and the track's title.
  Code: `logic/LibraryView.kt` - `Kind` is `PODCAST, OWN, AUDIOBOOK, MUSIC` (no `SPOTIFY`); `kindOf` gives a Spotify
  show or episode `PODCAST` and every other Spotify kind `MUSIC`; `Hint.Started.episodes` is gone (the kind says it).
  `ui/LibraryScreen.kt` (`kindText`/`hintText`: `MUSIC` -> `kind_music`, `hint_track`), `ui/PlayerScreen.kt` (`MUSIC`
  -> `track_of`; the source line removed), `ui/ListScreens.kt` (no line in list mode, the context hint with the kind;
  `MUSIC` -> `new_track`), the strings (`kind_spotify` removed - no other code path read a kind label; the parent's
  words are the server's), kdoc quotes in `PlayerModel.kt`, `UiModel.kt`, `logic/Spotify.kt`, `SpotifyWebHost.kt`.
  `RendererState`'s names and every string *name* (`renderer_spotify_*`, `hint_spotify_picks`) are unchanged: code,
  pinned by tests and docs; only the values changed.
- **Tests** (music): `SourceScanTest` - the bokmål lines as above, and a new test: no string value in either language
  contains "Spotify" (case-insensitive), no `kind_spotify`, `kind_music` "Musikk"/"Music", and no `ui/` source holds
  `kind_spotify` or a "Spotify" string literal (comments stripped); the old pin on `kind_spotify` "Spotify" is gone.
  `FitTest` - the seven lines re-measured from Nunito Bold's advances at 12 sp (78.8-133.2 dp nb, 69.7-153.4 dp en;
  each fits the player's header and the tile), the context hint measured with its kind (Bold 198.0/187.0, ExtraBold
  203.0/191.6 dp; the map's keys are the strings with `%1$s` replaced by that locale's `kind_music`).
  `SpotifyRulesTest` (the kind per Spotify kind, music's tracks and a show's episodes, the strings), `LibraryViewTest`
  and `UiModelTest` (`Kind.MUSIC`/`Kind.PODCAST` hints and kinds), `GuardsTest` (the player has no `kind_spotify` and
  maps `MUSIC` to `track_of`; the list's context line pinned exactly, no `kind_spotify` in the list).
- **The server: the account model in the copy** (§0.2 items 1 and 4, §2.1-§2.5; no code change to the machinery).
  `templates/music_spotify.html`: the intro ("... your children's Spotify accounts: one account per phone, the
  child's own. Your own Spotify account is never logged in here - you paste the links. ..."), setup step 3 ("add each
  child's Spotify account - one per phone, the child's own, never yours - by name and e-mail"), a new step 6 on
  playlists (a playlist lists only when a child's account owns it or collaborates on it - made in the Spotify app
  while logged in as the child, or the parent's made collaborative and the child invited; any other plays in
  Spotify's order without a track list, if the child's account can open it), the login hint ("Log in each child's own
  account - one per phone, never yours. Whoever is logged into Spotify in this browser logs in, so open this page in
  a private window ..."), the add page's context notice. `handlers/music.rs`: the card's context notice - "Log in as
  its owner to get the track list" (which would have logged the parent in) became "For the track list, make the
  playlist in your child's Spotify account, or make yours collaborative and invite your child."
  `templates/device_detail.html`: the binding hint "One account per phone: this child's own Premium account, never
  yours." (was "Use your child's own Premium account: the phone can read its playlists."). Kept: the two-phones-one-
  account warning, the card's "read as <name>" (which child's account listed it), "Open in Spotify", every "Spotify"
  label in the PWA. `tests::music_spotify`: `not_configured_shows_the_steps` pins the intro, step 3 and step 6 (and
  that "yours (if it reads your playlists)" is gone), `the_app_setting` the login hint,
  `the_device_card_binds_a_phone_to_an_account` the binding hint, `context_playlists_short_links_and_kinds` the whole
  context notice, `the_cards_and_check_now` its new tail and no "Log in as its owner".
- **The policy deviation, plainly** (§7.3): Spotify's Developer Policy II (attribution, "Spotify Marks", II.4) asks
  for attribution wherever Spotify content is shown. The kid's app now shows Spotify content - titles, cover art,
  audio - with no attribution at all: no logo, no word. That is the user's decision for private use, inside the risk
  the user accepted on 2026-10-06 (the Policy's "targeted to children" clause was already broken by design). The
  attribution and the link-back live only in the parent's PWA (the Spotify page, the cards' kind labels and "Open in
  Spotify", the device card). The sanction is unchanged: a revoked client ID, the tiles then saying "Spør en voksen".
- **Docs**: this design (§0.2 items 1, 4 and 7; §0.3; §0.4 two rows changed and one added; §2.1's steps 3 and 6; §2.2's
  account model; §2.3; §2.5; §3.1's tile column; §3.2's notice; §3.3's readers; §3.6; §4.3; §5.8 with its table, the
  quota row now naming `SPOTIFY_UNAVAILABLE` as built in step 5a; §5.9; §7.1; §7.3; §11's new step 8, Spotify on
  Sonos renumbered **step 9** everywhere in this design, design 26 and `music/CLAUDE.md`; §12's two rows; §13; §14
  items 1, 2 and 5 closed; QA checklist items 1-4, 7, 8, 11-13, 19; the Release's branch line, open points 1, 2 and 4
  and the DEPLOY note), `docs/testing/emulator.md` §6l (the tiles, the player, the list and every line; `fakeparent`
  read as a second child's account; part B with the kid's own login and playlist), `music/CLAUDE.md` (a "No source on
  the kid's screens" rule), `server/CLAUDE.md` (an account-model bullet, the binding hint), design 26's status,
  `PLAN.md`.

Deviations from the brief (each with its reason):
1. **The kind chip and the hints' prefix changed too**, not only the lines the brief listed: the cover's chip and every
   hint began with `kind_spotify` "Spotify". A Spotify show or episode takes NRK's and RSS's `Kind.PODCAST`; for a
   playlist, album, track or artist no existing kind fits ("Egne filer" is own files, "Podkast" would be wrong), so
   a new `Kind.MUSIC` with `kind_music` "Musikk" / "Music". `kind_spotify` is gone: no non-kid-facing path read it.
2. **No exact equivalents existed** to reuse: the app's stream refusal is `not_downloaded` "Ikke lastet ned - trenger
   Wi-Fi" (wrong for an entry that is never downloaded), so only its "trenger Wi-Fi" wording is taken; the Sonos lines
   name the speaker. "Spilles et annet sted" takes the Sonos line "Spilles i en annen gruppe"'s passive form rather
   than "Spiller et annet sted"; "Stoppet - trykk spill" keeps the old wording without the name; the rest are the
   brief's neutral forms.
3. **The context hint** is "%1$s · rekkefølgen velges for deg" - lower case after the dot like every hint
   ("fortsetter der du slapp"), with the kind before it.
4. **"No resume" for a context**: the brief says context mode has no resume. As built (§5.9) a playlist context resumes
   at its live track while that track is still in the playlist; only an artist has none. The docs say it as built.
5. **The fixtures keep their second account** (`fakeparent`, "Pål" in the server tests): the machinery is N accounts
   and the tests exercise readers in turn; §6l says to read `fakeparent` as a second child's account. No test was
   renamed.

Found: the §2.5 text said an unknown binding id is "a 400 back at `#music-spotify`" while step 2b built a 303 with a
notice (its deviation 3) - fixed in the fix round (L6).

Validation: music (from `music/`) `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` BUILD SUCCESSFUL, 845 JVM tests (844 + the new no-Spotify scan), 0 failed; lint 0 errors, 42
warnings, none in a file this step touched (`strings.xml:82`'s `some_downloaded` plural hint is old). Server (from
`server/`) `cargo fmt --check` clean, `cargo test` 827 passed, 0 failed (`parent_pin` passed this time).
`python3 -I scripts/fake-spotify.py --selftest` "FAKE SPOTIFY SELFTEST OK". No contract file changed (the shared files
are byte-identical as step 3 left them), `launcher/` untouched, so its suite was not run.

Owed: QA's review of this step. The user's look at the words on a phone ("Musikk" on a Spotify playlist's cover,
"rekkefølgen velges for deg"). §6l parts A and B and the Jelly Star checks as before (QA checklist items 1-4 now with
the children's own accounts only). The Developer Policy deviation is the user's accepted risk, recorded in §7.3.

### Fix round after QA's code review (qa-26c-step8-code, 2026-10-14)

All six findings fixed; none disputed. No contract file, no migration, `launcher/` untouched.
- **L1** (the scan guarded `ui/` only): `SourceScanTest`'s step 8 test also reads every string literal of `playback/`,
  `bridge/` and `sync/` (where the notification, the lock screen and Home's card get their text): none may contain
  "spotify" in any case except an allowlist of two that never reach a screen - `PlaybackService.kt`'s
  `SPOTIFY_LOG_TAG` "VibbSpotify" and the resumption future's `UnsupportedOperationException("playing on Spotify")`.
  It pins the three sites that put text there: the session's `MediaMetadata` sets exactly `.setTitle(item.title)`,
  `.setArtist(a.entry.name)`, `.setAlbumTitle(a.entry.name)`; `reportNowPlaying`'s bundle puts exactly
  `NowPlayingKeys.ENTRY` = `a.entry.name` and `ITEM` = `title` (`live ?: item?.title`); the web player's `titled`
  takes only the live title (its three callers, and `liveTitle` assigned only the stored live track's title, the
  SDK's track title or the entry's name, or null). Mutation: `.setAlbumTitle("Spotify")` turns the test red.
- **L2** (a nameless playlist named "Spotify playlist"): `spotify::LinkKind::plain_name` ("Playlist", "Album",
  "Track", "Artist", "Podcast", "Episode") is the add's fallback name (`handlers::music_spotify`); `kind_label`
  stays the PWA's label. `context_playlists_short_links_and_kinds` adds a playlist whose metadata name is blank: the
  entry is named "Playlist", and no `plain_name` holds the word. §3.2 step 3 says it.
- **L3** (the collaborative route omitted the join): setup step 6 - "invite the child to collaborate on yours (in
  Spotify, the playlist's ⋯ › Invite collaborators; then open the invite link while logged in as the child - the
  private window you logged the child in from - and join). Then "Check now" on its card lists it." - and the context
  notice (card and add) - "... or invite your child to collaborate on yours and join from your child's account." The
  three server tests pin the new text; §0.2 item 4, §2.1 step 6, §3.2, §3.3 and `server/CLAUDE.md` follow. Whether a
  joined collaborator's account reads the items in dev mode stays QA checklist item 3's device check.
- **L4** (owed checks in the old account model): §9.4 part B, §11 step 6's device check, the Release's part B line
  and `docs/testing/emulator.md` part B (heading and the context-mode check) name the kid's Premium account, a
  playlist the kid's account owns (or the parent's, made collaborative and joined) for list mode, and a playlist the
  kid's account doesn't own or collaborate on (the parent's own, say) for context mode.
- **L5** (the deviation filed under the wrong acceptance): §7.3's attribution row says it plainly - dropping the word
  is a **second** departure from the Developer Policy, its attribution clause (II), beside the "targeted to children"
  one (III) the user accepted on 2026-10-06 on a design that still carried the word; decided by the user on
  2026-10-14, quoted exactly ("vi ikke trenger noe spor av spotify noe sted i appen? For barna skal jo dette bare være
  en tile til? Samme hvilke kilde?"); the same sanction: Spotify may switch the family's app off, and the tiles then
  say "Spør en voksen". The link-back row, §14 item 5 (its quote corrected to the user's words) and the Release's open
  point 4 say the same.
- **L6**: §2.5 says the unknown binding id's 303 back to `#music-spotify` with `music_notice=spotify_unknown` (step
  2b's deviation 3); the status's finding above is closed. The Release's "Last full runs" carries step 8's numbers
  and what step 8 changed. §3.1's table is one table again (the blank line after the artist row and the note between
  the rows removed; the note under the table).
- **Validation**: music `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`
  BUILD SUCCESSFUL, 845 JVM tests, 0 failed (L1's pins inside the existing step 8 test; the `.setAlbumTitle("Spotify")`
  mutation red, reverted); lint 0 errors, 42 warnings, none in a touched file. Server `cargo fmt --check` clean, `cargo
  test` 827 passed, 0 failed (L2's case inside `context_playlists_short_links_and_kinds`), `cargo clippy --all-targets`
  the 12 known warnings, none new. `python3 -I scripts/fake-spotify.py --selftest` OK.

## Merge of main (5584772, 2026-10-14)

`origin/main` at `5584772` (design 31 steps 4-10 and 12-15 since `571f86f`: the parent PWA rebuilt as iOS-style
sub-pages - Device › Music (step 7, with the music warnings as Attention rows), Calls & Contacts (8), Time Rules (9),
the phone's summary without the Status card (10), the Music tab with Settings, Categories and Import as their own
pages (12), the Web Filter split into Settings › Web Filter and each phone's own page (5), the add-phone flow and the
sign-in pages without the brand bar (15)) merged into `music-3-spotify` after step 8's fix round (`038822a`). No
migration came from `main` (0073 is still its newest, design 30's; 0074 is ours), no contract file changed on either
side of the merge, and `launcher/` and `music/` changed on `main` only in their `CLAUDE.md`. **Main's shell and pages
win**; the Spotify parts moved to where design 31 puts their neighbours.

Conflicts and their resolutions:
- `server/templates/device_detail.html` (main rebuilt it as the summary; ours had the Music card's Spotify part and
  the DNS note in the filter card): **main's file**. The Spotify part moved to **Device › Music**
  (`templates/device_music.html`): a "Spotify" group (`<section id="music-spotify">`) between the settings form and
  Sonos - the account warnings above it as orange notes, "Plays as" as an inline picker (`select.pick`,
  `data-autosave`, `<noscript>` Save), the account's lines and the "its N Spotify entries aren't sent" note as row
  notes, a refused save's reason as the group's flash, the footer "One account per phone: this child's own Premium
  account, never yours. Accounts are on the Spotify page." The mobile-data sentence about Spotify went into the
  Mobile data group's ⓘ (the streaming paragraph). The DNS note moved to the phone's **Web Filter page**
  (`templates/device_filter.html`, `device_pages::view_filter`: `spotify_on`).
- `server/src/handlers/dns_filter.rs`, `templates/dns_filter.html` (main moved the per-phone part of `/dns` to
  `/devices/{id}/filter` and redirects `/dns?device=`; ours had the Spotify marks there, qa-26c-step3-code L5):
  **main's files**; the marks moved with the per-phone part - `view_filter` reads which of the phone's blocks and of
  the site-wide blocks are Spotify hosts while it is bound (`spotify_overridden`, `spotify_global`; a DB error is
  the page's 500 like its other reads): "Not applied while Spotify is on for this phone" under the block's row, "Not
  applied on Ella while Spotify is on for this phone (its Music page): i.scdn.co." and the DNS note under the filter
  switch.
- `server/src/handlers/music.rs` (6 hunks): main's `SweepCard.count`, local times (`Zone`), `phone_lines` returning
  `(phone, line)` and Device › Music's `device_card` kept; ours on top - `check_after` stays a method (Spotify's card
  shares it) but words its time with `self.zone.until` like main's inline code it replaces; `spotify_card` gives the
  row's `count` ("42 tracks"; none in context mode) besides the entry page's line with the reader;
  `spotify_phone_line` returns the line without the name, capitalised as main's ("Plays as Ella", "Not sent - Spotify
  is off on this phone" through `gated`), its "(as of ...)" in the zone; `SpotifyBindingCard` and
  `spotify_binding_card` unchanged, fed by `device_card` (`spotify_notice` from a `spotify_*` notice, as Sonos's);
  `save_device_spotify` redirects to `/devices/{id}/music#music-spotify` (refused: `?music_notice=spotify_unknown`).
  The "From Spotify ›" row's value (`spotify_state`) had auto-merged onto Music › Settings' template: moved back to
  the tab's (`MusicTemplate`).
- `templates/music.html` (main's tab): **main's file** plus **"From Spotify ›" as the fifth Add row** (after "From
  Storytel ›", a green tile, the value No app / No login / Signed in, `id="spotify"` for the Spotify page's back
  link) - open point 8, settled. The import's ⓘ (now `music_import.html`, auto-merged) says Storytel and Spotify
  entries come over (Spotify ones on no phone), short links and profiles don't.
- `templates/partials/music_card.html` (main's library row): **main's file**; a Spotify row shows "Open in Spotify"
  in place of the Offline select (`sweep.offline` false).
- `templates/music_entry.html` (main's entry page): **main's file**; ours re-applied - "Open in Spotify" by the
  target, `list_title` ("Tracks"/"Episodes") as the status group's header, and for Spotify no offline field: the play
  order (none in context mode, with the hint "Spotify picks the order ...") and the hint that Spotify streams ("the
  phone's Music page").
- `server/src/tests/music.rs` (the import test): main's `/music/import` page with our short-link reason.
- `server/CLAUDE.md`: main's import paragraph with our Spotify import sentence.

Not in a conflict, needed by the merge:
- `templates/music_spotify.html` still included the brand bar (`partials/app_header.html`, which design 31 step 15
  deleted): now the tab bar, a "‹ Music" back link (to `/music#spotify`) and the large title, like the Storytel shelf.
  Its cards keep their step 1/2b markup inside (`.card` and friends are still in `style.css`); a rebuild as groups is
  a later step (below).
- **Attention** (design 31 §3.8, step 7's M1): `MusicWarnings.spotify_cant_play` - a bound phone whose last Spotify
  word is `reauthorize`, `refused`, `not_premium` or `no_widevine` - raises a new orange row "Spotify can't play on
  this phone" (`attention::Kind::MusicSpotify`, to `/devices/{id}/music#music-spotify`); the badge and the summary
  count it like the other music rows. The account-side warnings (a login ending, two phones on one account, no app)
  stay on the page only: `music_warnings` reads the phone's report and policy, never the accounts table.
- **Event labels** (main's `every_written_event_has_a_label`, qa-31-s14-code M2): labels for `spotify_app_saved`,
  `spotify_app_cleared`, `spotify_account_added`, `spotify_account_removed`, `spotify_login_failed`,
  `spotify_login_needed`, `music_spotify_binding_changed`; the login failure's codes (`access_denied`,
  `authorize_error`, `invalid_grant`) listed in `NOT_EVENTS`.
- **The redirect table** (§5.1, `tests::device_pages::music_forms_return_to_their_groups`): its phone now has a
  Spotify app and a child's login, so the Spotify form is on the page and in the table (`account` -> `#music-spotify`,
  the binding checked afterwards).
- Tests moved with the pages: `the_device_card_binds_a_phone_to_an_account` reads `/devices/{id}/music` (the
  streaming sentence in the ⓘ between the streaming and downloads paragraphs, the select's options, the redirects,
  the refused save's flash) and the DNS note on `/devices/{id}/filter`, and pins the new Attention row on the summary
  (raised by `not_premium`, gone with `ok` and when unbound); `the_cards_and_check_now` reads the library row ("Spotify
  playlist · 3 tracks · 1 phone", "Open in Spotify", no offline form; context mode without a count) and the entry
  page (the line with the reader, the notes, each phone's line through `tests::music::phone_line`);
  `the_entry_page_has_no_offline_and_in_context_mode_no_order` ("Check Now"); the import's Spotify reason on
  `/music/import`; `the_web_filter_page_says_what_spotify_overrides` on `/devices/{id}/filter` (and `/dns?device=`
  redirecting there); the From Spotify row's markup.
- Docs: `server/CLAUDE.md` (the binding on Device › Music, the Attention row, the library row and entry page, the
  Web Filter page), `docs/testing/emulator.md` §6l (Plays as on the Music page, the DNS note on the Web Filter page),
  this design's Release (the branch, the runs, open point 8 settled) and DEPLOY note, design 26, `PLAN.md`.

Validation on the merge commit: server (from `server/`) `cargo fmt --check` clean, `cargo test` **933 passed**, 0
failed, `cargo clippy --all-targets` the 12 known warnings (none in a file the merge touched), `node --test
jstest/*.test.js` 25 passed; launcher `./gradlew assembleDebug testDebugUnitTest` BUILD SUCCESSFUL, 1146 tests (the
debug build on the tsnet stub); music `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
-PwarningsAsErrors=true` BUILD SUCCESSFUL, 845 tests, lint 0 errors (42 warnings, none new); `python3 -I
scripts/fake-{sonos,storytel,spotify,audio}.py --selftest` all four OK.

Owed: the browser pass of Device › Music's Spotify group, the Web Filter page's marks and the "From Spotify ›" row
(390 px, light and dark, JS off - no browser here, as for design 31's own steps); the Spotify page itself was still
step 1/2b's cards inside the new shell - **rebuilt as groups in the fix round below** (M3; its app and account groups
stay on `/music/spotify`, and `/music/spotify/callback` and `/done` never move); a code QA of this merge (done:
`qa-26c-merge2-code.md`).

### Fix round after QA's code review (qa-26c-merge2-code, 2026-10-14)

All nine findings fixed; none disputed. QA's question 1 (the Spotify page before the merge to `main`) was answered by
the coordinator: rebuild now (M3). No contract file, no migration, `launcher/` and `music/` untouched.
- **M1** (a Storytel entry's page said "Episodes"): `handlers::music::list_title(entry)` gives "Books" for Storytel,
  "Tracks" for a Spotify playlist, album, track or artist, "Episodes" for everything else (NRK, RSS, a Spotify show or
  episode) - main's "Books" back. Pinned by `tests::music_spotify::entries::the_entry_pages_list_header_follows_the_source`
  (a Storytel series, a Spotify playlist and show, an NRK series).
- **M2** (the Spotify text on Device › Music and Web Filter in 26c's sentence shapes): `SpotifyBindingCard.warnings`
  are `Note`s through `partials/note_warning.html` - a title of at most 6 words and one sentence, "phone" never
  "device": "No Spotify app set up", "Spotify login needs renewing" (the account's `needs_login` and the phone's
  `reauthorize`: one note), "Spotify login can't be opened" (`key`), "Spotify login ends 14 Apr" / "Spotify login has
  ended", "Two phones share one account" (more: "Several phones ..."), "Spotify refused the account", "No Spotify
  Premium", "This phone can't play Spotify". The account's "Log in again before 14 Apr 2027" and an unbound phone's
  "Its N Spotify entries aren't sent." are the "Plays as" row's sub-lines; the phone's passing words are a "Spotify"
  row in "On the phone" ("Plays", "Couldn't reach Spotify", "Limit reached" - the last two with their sentence behind
  the row's ⓘ; `spotify_report_row`). The footer is one sentence of 11 words ("One account per phone: the child's own
  Premium account, never yours."), with a "Spotify Accounts ›" row in the group. Web Filter's first group has one
  footer again: while bound "Blocks the lists and domains below, except Spotify's while this phone plays Spotify.",
  the DNS note and the site-wide blocks it overrides as paragraphs of the existing ⓘ panel; the per-row mark reads
  "Not applied while this phone plays Spotify". The tests' strings follow (`the_device_card_binds_a_phone_to_an_account`
  checks the Spotify section has no "device", `the_web_filter_page_says_what_spotify_overrides` one footer).
- **M3** (the Spotify page in pre-design-31 markup): **rebuilt** in design 31's layout (`templates/music_spotify.html`,
  as `music_settings.html` and the Storytel shelf): the "‹ Music" back link and the large title; a one-line intro with
  the account model behind its ⓘ; "Spotify logins are off" as an alarm row with the key file and `MUSIC_SECRET_KEY`
  behind its ⓘ; **Your Spotify App** - the save's flash, "Client ID · ending in cdef" (its sub-line "Set by the
  server's configuration"), "Spotify refused this Client ID" as an alarm row, the Redirect URI as a copyable value row
  (`code.key#redirect-uri`, `user-select: all`), the form (`#app`) with the fields' errors under them, the suggestion
  and `SPOTIFY_CLIENT_ID`/`SPOTIFY_REDIRECT_URI` behind the Redirect URI's ⓘ, How to Set It Up as a disclosure row
  (`details#setup`, open until an app is set up), Remove the App… as a confirm (`#app-clear`, its box kept: the handler
  requires `confirm`), a one-line footer with the limits behind its ⓘ; **Children's Accounts** - the login's outcome
  as the group's flash, one row per account (the name, "Logged in ... - log in again before ...", a warning in red as a
  second sub-line) with Remove … as a confirm (`#account-<id>`), Log in with Spotify as a row button, the paste
  (`#paste`) with its explanation behind the field's ⓘ, a one-line footer with the login hint behind its ⓘ; **Add a
  Spotify link** - the add's flash and notes (`#added`), the link and category fields (`#add`), "On these phones" with a
  check per phone and whom it plays as, a footer and Add. No `<style>` block, no `.card`, no `.error`/`.success`; every
  route, field name and test id kept. The login form posts without a fragment (its answer is a 303 to Spotify; the
  redirect table's rule). Its forms are rows of the Music tab's redirect table
  (`every_form_on_the_music_pages_returns_to_its_page`: the app save, the clear, the login, the paste, an account's
  Remove, the add - each lands back at its group; a refusal's fragment pinned per form; the table's delete-last sort now
  reads the action without its fragment and puts a clear after every delete). The Release's merge path has no
  Spotify-page step any more.
- **L1**: `tests::attention::each_music_warning_raises_its_row_and_marks_the_music_row` has two Spotify cases - the
  phone's `not_premium` (`music-spotify`) and a login to renew (`music-spotify-login`): the row, its target
  `/devices/{id}/music#music-spotify` and that element on the page, "Needs attention" on the Music row, the Devices
  list's `data-attention-count="1"`, nothing once unbound.
- **L2**: the picker's `<noscript>` Save is pinned inside its form (`the_device_card_binds_a_phone_to_an_account`).
- **L3**: the account's side raises Attention rows: `music::spotify_account_warnings(state, policy)` (the app, the
  bound account's row, the phones bound to it - three small reads, §3.8) into `Sources.spotify_account`, read by
  `attention::facts` (the Devices list's badge) and by the summary, folded into `MusicWarnings.spotify_account`:
  "Spotify login needs renewing" (`needs_login`, `key`, or past the 6 months), "Spotify login ends soon" (the 14
  days), "Two phones share a Spotify account", "No Spotify app set up" (`Kind::MusicSpotifyLogin`, `...Ending`,
  `...Shared`, `...NoApp`; Action, to `#music-spotify`). A login to renew and the phone's `reauthorize` are one row.
  `attention::tests` count them in the specs' order; `the_device_card_binds_a_phone_to_an_account` pins the shared
  and the renewing rows on the summary.
- **L4**: Spotify's dates in the Phone Setup zone (`display_time::Zone::local`): the Spotify page's account lines and
  warnings (`music_spotify::day(zone, ..)`), Device › Music's "Log in again before" and "Spotify login ends ...".
- **L5**: §6l (the links' check on the entry page, the 403 playlist's notes there, the refused refresh on the phone's
  Music page, the Spotify page's accounts and the summary's Attention row), the QA checklist items 3, 12, 13, 18 and 19,
  `PLAN.md`'s design 31 step 12 line (settled), `server/CLAUDE.md` (the Music page, the entry page, the page's new
  layout, the Device › Music shapes and the Attention rows).
- **L6**: a Spotify playlist's entry page is one of the redirect table's pages (its save, Check Now, cover, delete).

Validation (from `server/`): `cargo fmt --check` clean; `cargo clippy --all-targets` the same 12 warnings as `main`
(none new); `cargo test` **934 passed**, 0 failed (933 + `the_entry_pages_list_header_follows_the_source`; the other
pins inside existing tests); `node --test jstest/*.test.js` 25 passed. `python3 -I
scripts/fake-{sonos,storytel,spotify,audio}.py --selftest` all four OK. `launcher/` and `music/` untouched (their
suites not run).
