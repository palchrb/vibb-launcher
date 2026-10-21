//! The server's Spotify side, step 1 of design 26c (`docs/design/26c-spotify.md` §2, §7.1, §9.3):
//! the family's own developer app (bring your own client ID: this server ships none), the Spotify
//! accounts the parent logs in with PKCE in their own browser, each account's refresh token sealed
//! with the music key, and the access-token cache; and step 2a's listing (§3.1, §3.3, §3.4): the
//! Web API's answers read into tracks and episodes ([parse_playlist_items] and its siblings, over
//! `items[].item`), the readers ([readers]: the accounts that may read, the listing's last reader
//! first) and short links resolved one hop at a time ([resolve_short_link]). The sweep's check is
//! `music_sweep::spotify_check`; the entries and the binding (2b) and the phones' token endpoint
//! (step 3: `handlers::music_api::spotify_token`, its per-phone limit [Accounts::phone_ask]) build
//! on it, and the phone's DNS filter exempts [HOST_SUFFIXES] and [EXACT_HOSTS] while it plays
//! Spotify (§6.2, [is_spotify_host]).
//!
//! - **The app** ([App]): one `music_spotify_app` row from the PWA, or `SPOTIFY_CLIENT_ID` (and
//!   `SPOTIFY_REDIRECT_URI`) from the environment, which wins. A PKCE client ID is public (it is in
//!   the address bar of every login), so it is stored in the clear; there is no client secret.
//! - **The login** ([Accounts::start_login], [Accounts::complete_login]): a PKCE pair and a `state`
//!   kept in memory only (at most [PENDING_MAX], each [PENDING_TTL]), the authorize URL, then the
//!   code exchanged for tokens and `/v1/me` read; the account row upserted by its Spotify user id
//!   (a new login of the same account bumps `generation`).
//! - **Tokens** ([Accounts::access_token]): per account a tokio `Mutex` around its cached access
//!   token, so one refresh runs at a time; a rotated refresh token is sealed and written before the
//!   access token is used. `invalid_grant` = the login ended (`needs_login`, never tried again until
//!   a new login), `invalid_client` = the app is gone or refused.
//! - **Reads** ([Accounts::api_get]): the bearer, a 401 refreshes once, a short `Retry-After` is
//!   waited for once, `QUOTA_EXCEEDED` stops every Web API read for [QUOTA_PAUSE]. Every URL is
//!   built from a canonical id ([Accounts::read_url]), never from pasted text.
//! - **Every request** goes through `AppState.music_fetch` (the one gate), [Reach::Public] - and
//!   [Reach::Any] only while a loopback base override (§9.3: the fake on 127.0.0.1) is set.
//! - **Secrets** (§7.1): refresh tokens, access tokens, the PKCE verifier and the authorization
//!   code never reach a log, an event, a page, an error text or a `Debug`. [AccessToken],
//!   [RefreshToken], [Verifier], [Pending], [TokenAnswer] and [Minted] have no `Debug` and no
//!   `Display` (`tests::secret_types_have_no_debug`); errors carry codes and statuses only.
//!
//! The link grammar follows palchrb/vibb `pi/vibb/spotify.py` (`URI_RE`, `LINK_RE`, `to_uri`; MIT,
//! Copyright (c) palchrb), reimplemented in Rust under GPL-3.0 with 26c's changes (§3.1: ids
//! bounded, audiobooks, Liked Songs and profiles refused, short links resolved once at add time).

use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::Duration;

use argon2::password_hash::rand_core::{OsRng, RngCore};
use base64::Engine;
use chrono::{DateTime, TimeDelta, Utc};
use serde_json::Value;
use sha2::{Digest, Sha256};
use tokio::time::Instant;

use crate::AppState;
use crate::music_net::{FetchError, Kind, Reach, Source, SourceRequest, SourceResponse};
use crate::music_sweep::{parse_stamp, stamp};

// --- CONSTANTS -----------------------------------------------------------------------------------

/// Spotify's accounts service (authorize, token).
pub const ACCOUNTS_BASE: &str = "https://accounts.spotify.com";
/// The Web API.
pub const API_BASE: &str = "https://api.spotify.com";
/// One scope set for every account (§2.2): the SDK's three (the web-app-player how-to), the play,
/// transfer and queue calls, and the owner's private and collaborative playlists. No
/// `user-read-playback-state`: the phone reads its state from the SDK.
pub const SCOPES: &str = "streaming user-read-email user-read-private user-modify-playback-state \
                          playlist-read-private playlist-read-collaborative";
/// The redirect for a server without https (§2.3): nothing listens there, the parent pastes the
/// address their browser shows ([Accounts::complete_login] through `POST /music/spotify/paste`).
pub const LOOPBACK_REDIRECT: &str = "http://127.0.0.1:43117/callback";
/// The callback's path on this server (an https redirect URI ends in it).
pub const CALLBACK_PATH: &str = "/music/spotify/callback";
/// The defence-in-depth cookie of a login (§2.3): hex SHA-256 of its `state`.
pub const LOGIN_COOKIE: &str = "spotify_login";
/// Unfinished logins kept at once; a ninth drops the oldest.
pub const PENDING_MAX: usize = 8;
/// How long an unfinished login can be completed.
pub const PENDING_TTL: Duration = Duration::from_secs(600);
/// The code exchange and `/v1/me` at a login, the gate's wait included (a feed fetch can hold the
/// gate for 120 s): past it the login is "unreachable" (26b's probe budget).
pub const LOGIN_BUDGET: Duration = Duration::from_secs(45);
/// "Log in again before": Spotify's refresh tokens end 6 months after the login; the page says
/// 180 days, a margin under it.
pub const LOGIN_VALID_DAYS: i64 = 180;
/// From this many days before the line, it is a warning.
pub const LOGIN_WARN_DAYS: i64 = 14;
/// Development mode's limit of users per app (the page says it; the server doesn't enforce it).
pub const DEV_MODE_USERS: usize = 5;
/// An access token is used until this long before Spotify says it expires.
pub const EXPIRY_MARGIN: Duration = Duration::from_secs(60);
/// The longest `expires_in` taken from the accounts service (Spotify's is 3600).
pub const MAX_EXPIRES_IN: u64 = 86_400;
/// A Web API read wants a token with at least this much left.
pub const READ_MIN_LEFT: Duration = Duration::from_secs(60);
/// A `Retry-After` up to this long is waited for, once.
pub const RETRY_WAIT_MAX: Duration = Duration::from_secs(5);
/// After a `QUOTA_EXCEEDED` no Web API read for this long (§2.4: the phones' play calls draw on
/// the same developer account's bucket).
pub const QUOTA_PAUSE: Duration = Duration::from_secs(3600);
/// `/me`'s display name is cut to this.
pub const MAX_DISPLAY_NAME_CHARS: usize = 60;
/// A pasted or typed link longer than this is no link.
pub const MAX_LINK_CHARS: usize = 2000;
/// A short link is followed this many hops at most (§3.1).
pub const SHORT_LINK_HOPS: usize = 3;
/// The Web API's page size for every list read.
pub const PAGE_LIMIT: usize = 50;
/// A playlist or an album lists at most this many tracks (`keep_end = 'first'`, `capped` past it).
pub const MAX_LIST_TRACKS: usize = 1000;
/// A show keeps its newest this many episodes (NRK podcast's rule).
pub const MAX_SHOW_EPISODES: usize = 100;
/// The artist option (§3.3): at most this many albums and singles, newest first...
pub const MAX_ARTIST_ALBUMS: usize = 20;
/// ...and this many tracks.
pub const MAX_ARTIST_TRACKS: usize = 500;
/// The page size of `GET /artists/{id}/albums`: the reference's maximum is 10 (default 5;
/// qa-26c-step2a-code M2), not the 50 of the other list reads.
pub const ARTIST_ALBUMS_LIMIT: usize = 10;
/// The artist option reads at most this many pages of albums - 100 albums and singles - before
/// it picks the newest [MAX_ARTIST_ALBUMS] (Spotify doesn't document the endpoint's order, so the
/// whole of it is sorted by date; past 100 the oldest are cut and the list is `capped`).
pub const MAX_ARTIST_ALBUM_PAGES: usize = 10;
/// A phone's token (§2.6) has at least this much left: the phone refreshes ahead of expiry at 10
/// minutes left while it plays (§5.5), so a token handed out never needs a second ask at once.
pub const PHONE_TOKEN_MIN_LEFT: Duration = Duration::from_secs(600);
/// At most this many token asks per phone in [PHONE_TOKEN_WINDOW] (§2.6: about one an hour while
/// it plays, plus a retry or two); past it a 429 `busy`.
pub const PHONE_TOKENS_PER_WINDOW: usize = 20;
/// The phone token limit's rolling window.
pub const PHONE_TOKEN_WINDOW: Duration = Duration::from_secs(3600);
/// The token route's own budget (qa-26c-step1-code's note): a refresh can wait in the server-wide
/// request gate behind a sweep (a feed fetch holds it up to 120 s). A refresh still running when it
/// ends finishes on its own ([Accounts::access_token] runs it as a task) and is kept for the next
/// ask - the music app's `TokenCache` asks once more about 5 s after an `unreachable` at a play start
/// (§5.5, step 4). 8 s, under the launcher's and the music app's 10 s each (qa-26c-step3-code L3), so
/// the server's honest 502 `unreachable` reaches the phone before their own timeouts fire.
pub const PHONE_TOKEN_BUDGET: Duration = Duration::from_secs(8);

// --- the phone's hosts (§6.2) --------------------------------------------------------------------

/// Spotify's hosts for the Web Playback SDK (§6.2; `testdata/spotify_hosts.json`, byte-identical
/// in both apps' test resources, `tests::hosts_match_the_contract`): a domain equal to or under one
/// of these. **Provisional** (unverified) until the spike's DNS capture replaces the list (before
/// any tag of design 26c's release: its "Release" names the one commit - the three JSON copies with
/// their comment, these constants and both apps').
pub const HOST_SUFFIXES: [&str; 5] = [
    "spotify.com",
    "scdn.co",
    "spotifycdn.com",
    "spotifycdn.net",
    "pscdn.co",
];
/// Third-party CDN hosts of Spotify's audio, exempted by exact name only (never their parent).
pub const EXACT_HOSTS: [&str; 2] = [
    "audio-ak-spotify-com.akamaized.net",
    "audio-akp-quic-spotify-com.akamaized.net",
];

/// A domain the delivered blocklist leaves out for a phone that plays Spotify (§6.2): equal to or
/// under one of [HOST_SUFFIXES], or exactly one of [EXACT_HOSTS]. Unlike FCM's exemption
/// (`play::is_fcm_protected`) a Spotify-owned parent (`spotify.com`) is dropped too - the list is
/// Spotify's own names; a third-party parent (`akamaized.net`) stays, the launcher exempts the exact
/// host before its suffix walk.
pub fn is_spotify_host(domain: &str) -> bool {
    let domain = domain.trim_end_matches('.').to_ascii_lowercase();
    EXACT_HOSTS.contains(&domain.as_str())
        || HOST_SUFFIXES.iter().any(|suffix| {
            domain == *suffix
                || domain
                    .strip_suffix(suffix)
                    .is_some_and(|rest| rest.ends_with('.') && rest.len() > 1)
        })
}

// --- links (the grammar; a short link is resolved by [resolve_short_link]) -----------------------

/// The kinds of link an entry can be made of (§3.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum LinkKind {
    Playlist,
    Album,
    Track,
    Artist,
    Show,
    Episode,
}

impl LinkKind {
    pub fn as_str(self) -> &'static str {
        match self {
            LinkKind::Playlist => "playlist",
            LinkKind::Album => "album",
            LinkKind::Track => "track",
            LinkKind::Artist => "artist",
            LinkKind::Show => "show",
            LinkKind::Episode => "episode",
        }
    }

    /// An entry's name when Spotify gives it none (only a playlist's is optional): the kind's
    /// word without the source's, because the name is the kid's tile, notification and lock
    /// screen, which never name Spotify (design 26c step 8, qa-26c-step8-code L2). The PWA's
    /// labels stay `music::kind_label`'s ("Spotify playlist" ...).
    pub fn plain_name(self) -> &'static str {
        match self {
            LinkKind::Playlist => "Playlist",
            LinkKind::Album => "Album",
            LinkKind::Track => "Track",
            LinkKind::Artist => "Artist",
            LinkKind::Show => "Podcast",
            LinkKind::Episode => "Episode",
        }
    }

    fn parse(text: &str) -> Option<Self> {
        Some(match text {
            "playlist" => LinkKind::Playlist,
            "album" => LinkKind::Album,
            "track" => LinkKind::Track,
            "artist" => LinkKind::Artist,
            "show" => LinkKind::Show,
            "episode" => LinkKind::Episode,
            _ => return None,
        })
    }
}

/// A playlist, album, track, artist, show or episode: `spotify:<kind>:<id>`.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct SpotifyRef {
    pub kind: LinkKind,
    pub id: String,
}

impl SpotifyRef {
    /// The canonical target (the entry's `target`).
    pub fn target(&self) -> String {
        format!("spotify:{}:{}", self.kind.as_str(), self.id)
    }

    /// The entry's key: vibb's `state_key` (`bm_path`) of the same URI (2b's add form).
    pub fn key(&self) -> String {
        crate::music::state_key(&self.target())
    }

    /// The link back to Spotify (Developer Policy, §3.6; 2b's card).
    pub fn open_url(&self) -> String {
        format!(
            "https://open.spotify.com/{}/{}",
            self.kind.as_str(),
            self.id
        )
    }
}

/// What a pasted link is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Link {
    Ref(SpotifyRef),
    /// `https://spotify.link/<code>` or `https://spotify.app.link/<code>`: resolved once at add
    /// time, one hop at a time ([resolve_short_link]).
    Short(String),
}

/// Why a link can't be added, each with its sentence (§3.1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkError {
    NotALink,
    Audiobook,
    Collection,
}

impl LinkError {
    /// The add form's sentence (2b).
    pub fn message(self) -> &'static str {
        match self {
            LinkError::NotALink => {
                "That isn't a Spotify link to a playlist, album, track, artist, podcast or episode."
            }
            LinkError::Audiobook => "Spotify audiobooks can't be played here.",
            LinkError::Collection => {
                "Liked Songs and Spotify profiles can't be added - make a playlist instead."
            }
        }
    }
}

/// A Spotify id: 1 to 64 of `[A-Za-z0-9]` (base62; 22 characters in practice).
pub fn id_ok(id: &str) -> bool {
    (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

fn kind_and_id(kind: &str, id: &str) -> Result<Link, LinkError> {
    match kind {
        "audiobook" | "chapter" => return Err(LinkError::Audiobook),
        "collection" => return Err(LinkError::Collection),
        _ => {}
    }
    let kind = LinkKind::parse(kind).ok_or(LinkError::NotALink)?;
    if !id_ok(id) {
        return Err(LinkError::NotALink);
    }
    Ok(Link::Ref(SpotifyRef {
        kind,
        id: id.to_string(),
    }))
}

/// `intl-de`, `intl-pt-br`: open.spotify.com's language prefix.
fn intl_prefix(segment: &str) -> bool {
    segment.strip_prefix("intl-").is_some_and(|rest| {
        (2..=8).contains(&rest.len()) && rest.bytes().all(|b| b.is_ascii_alphabetic() || b == b'-')
    })
}

/// vibb `to_uri`'s grammar (§3.1): `spotify:<kind>:<id>`,
/// `https://open.spotify.com/[intl-<xx>/]<kind>/<id>[?si=...][#...]` (query and fragment
/// dropped), or a `spotify.link`/`spotify.app.link` short link. Audiobooks, Liked Songs and
/// profiles are refused with their sentence; anything else is no link. No request is made.
pub fn parse_link(text: &str) -> Result<Link, LinkError> {
    let text = text.trim();
    if text.is_empty() || text.len() > MAX_LINK_CHARS {
        return Err(LinkError::NotALink);
    }
    if let Some(rest) = text.strip_prefix("spotify:") {
        let parts: Vec<&str> = rest.split(':').collect();
        return match parts.as_slice() {
            [kind, id] if *kind != "user" => kind_and_id(kind, id),
            // A profile, or a profile's Liked Songs.
            ["user", _] | ["user", _, "collection", ..] => Err(LinkError::Collection),
            _ => Err(LinkError::NotALink),
        };
    }
    let url = reqwest::Url::parse(text).map_err(|_| LinkError::NotALink)?;
    if !matches!(url.scheme(), "https" | "http")
        || !url.username().is_empty()
        || url.password().is_some()
        || url.port().is_some()
    {
        return Err(LinkError::NotALink);
    }
    let host = url.host_str().unwrap_or("").to_ascii_lowercase();
    let mut segments: Vec<&str> = url
        .path_segments()
        .map(Iterator::collect)
        .unwrap_or_default();
    if segments.last() == Some(&"") {
        segments.pop();
    }
    match host.as_str() {
        "open.spotify.com" => {
            if segments.first().is_some_and(|s| intl_prefix(s)) {
                segments.remove(0);
            }
            match segments.as_slice() {
                ["user", ..] | ["collection", ..] => Err(LinkError::Collection),
                [kind, id] => kind_and_id(kind, id),
                [kind, ..] if matches!(*kind, "audiobook" | "chapter") => Err(LinkError::Audiobook),
                _ => Err(LinkError::NotALink),
            }
        }
        "spotify.link" | "spotify.app.link" if url.scheme() == "https" => match segments.as_slice()
        {
            [code] if id_ok(code) => Ok(Link::Short(format!("https://{host}/{code}"))),
            _ => Err(LinkError::NotALink),
        },
        _ => Err(LinkError::NotALink),
    }
}

/// A stored target, canonical only (`spotify:<kind>:<id>`).
pub fn parse_target(target: &str) -> Option<SpotifyRef> {
    match parse_link(target) {
        Ok(Link::Ref(reference)) if reference.target() == target => Some(reference),
        _ => None,
    }
}

// --- PKCE (RFC 7636) ----------------------------------------------------------------------------

/// RFC 7636's unreserved characters.
const VERIFIER_ALPHABET: &[u8; 66] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
/// The verifier's length (RFC 7636: 43 to 128).
pub const VERIFIER_CHARS: usize = 64;

/// A PKCE code verifier: memory only, single use. No `Debug`, no `Display`.
pub struct Verifier(String);

impl Verifier {
    /// 64 characters of [VERIFIER_ALPHABET] from the OS's generator (unbiased: bytes past the
    /// last whole multiple of 66 are drawn again).
    pub fn new() -> Self {
        let mut out = String::with_capacity(VERIFIER_CHARS);
        let mut buf = [0u8; 96];
        while out.len() < VERIFIER_CHARS {
            OsRng.fill_bytes(&mut buf);
            for &byte in &buf {
                if out.len() == VERIFIER_CHARS {
                    break;
                }
                if (byte as usize) < 66 * 3 {
                    out.push(VERIFIER_ALPHABET[byte as usize % 66] as char);
                }
            }
        }
        Verifier(out)
    }

    fn as_str(&self) -> &str {
        &self.0
    }

    /// `base64url(sha256(verifier))` without padding (S256).
    pub fn challenge(&self) -> String {
        s256(&self.0)
    }
}

/// The S256 code challenge of `verifier`.
pub fn s256(verifier: &str) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

/// A login's `state`: 32 hex characters.
pub fn new_state() -> String {
    let mut bytes = [0u8; 16];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

/// The `spotify_login` cookie's value for `state`: hex SHA-256.
pub fn state_cookie(state: &str) -> String {
    hex::encode(Sha256::digest(state.as_bytes()))
}

/// The authorize URL (§2.3), `form_urlencoded` in this order; `show_dialog=true` so the parent
/// always sees which account is logging in.
pub fn authorize_url(
    accounts_base: &str,
    client_id: &str,
    redirect_uri: &str,
    challenge: &str,
    state: &str,
) -> String {
    let query = form_urlencoded::Serializer::new(String::new())
        .append_pair("response_type", "code")
        .append_pair("client_id", client_id)
        .append_pair("scope", SCOPES)
        .append_pair("redirect_uri", redirect_uri)
        .append_pair("code_challenge_method", "S256")
        .append_pair("code_challenge", challenge)
        .append_pair("state", state)
        .append_pair("show_dialog", "true")
        .finish();
    format!("{accounts_base}/authorize?{query}")
}

// --- configuration (environment) ------------------------------------------------------------------

/// What the environment says (read once at start; §2.1, §9.3).
#[derive(Clone, Debug)]
pub struct Config {
    /// `SPOTIFY_CLIENT_ID` (wins over the PWA's).
    pub env_client_id: Option<String>,
    /// `SPOTIFY_REDIRECT_URI` (wins over the PWA's).
    pub env_redirect_uri: Option<String>,
    /// `SPOTIFY_ACCOUNTS_BASE` when it is a loopback literal, else Spotify's.
    pub accounts_base: String,
    pub accounts_override: bool,
    /// `SPOTIFY_API_BASE` when it is a loopback literal, else Spotify's.
    pub api_base: String,
    pub api_override: bool,
    /// `INSECURE_COOKIES`: the login cookie isn't `Secure` (the emulator loop over plain http).
    pub insecure_cookies: bool,
    /// `SPOTIFY_ARTIST_ALBUMS` (`1`/`true`; off by default): an artist is listed as its albums'
    /// tracks instead of played as a context (§3.3's option, QA checklist item 18).
    pub artist_albums: bool,
    /// One line per variable that was ignored, for the start's log.
    pub warnings: Vec<String>,
}

impl Default for Config {
    fn default() -> Self {
        Config::from_vars(&HashMap::new())
    }
}

/// `http://127.0.0.1:<port>` or `http://[::1]:<port>` (a trailing `/` allowed), else `None`
/// (§9.3: a base override is honoured only for the fake on this machine).
pub fn loopback_base(value: &str) -> Option<String> {
    let url = reqwest::Url::parse(value.trim()).ok()?;
    let host_ok = matches!(url.host_str(), Some("127.0.0.1") | Some("[::1]"));
    let clean = url.scheme() == "http"
        && host_ok
        && url.port().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.path() == "/";
    clean.then(|| url.as_str().trim_end_matches('/').to_string())
}

impl Config {
    pub fn from_env() -> Self {
        Config::from_vars(&std::env::vars().collect())
    }

    pub fn from_vars(vars: &HashMap<String, String>) -> Self {
        let get = |name: &str| {
            vars.get(name)
                .map(|v| v.trim().to_string())
                .filter(|v| !v.is_empty())
        };
        let mut warnings = Vec::new();
        let env_client_id = get("SPOTIFY_CLIENT_ID").filter(|id| {
            let ok = valid_client_id(id);
            if !ok {
                warnings
                    .push("SPOTIFY_CLIENT_ID is not 32 letters and digits - ignored".to_string());
            }
            ok
        });
        let env_redirect_uri = get("SPOTIFY_REDIRECT_URI").filter(|uri| {
            let ok = valid_redirect_uri(uri);
            if !ok {
                warnings.push(format!(
                    "SPOTIFY_REDIRECT_URI must be https://<host>{CALLBACK_PATH} or exactly \
                     {LOOPBACK_REDIRECT} - ignored"
                ));
            }
            ok
        });
        let mut base = |name: &str, default: &str| match get(name) {
            None => (default.to_string(), false),
            Some(value) => match loopback_base(&value) {
                Some(base) => (base, true),
                None => {
                    warnings.push(format!(
                        "{name} is honoured only as http://127.0.0.1:<port> or http://[::1]:<port> \
                         (the fake Spotify, design 26c §9.3) - ignored"
                    ));
                    (default.to_string(), false)
                }
            },
        };
        let (accounts_base, accounts_override) = base("SPOTIFY_ACCOUNTS_BASE", ACCOUNTS_BASE);
        let (api_base, api_override) = base("SPOTIFY_API_BASE", API_BASE);
        Config {
            env_client_id,
            env_redirect_uri,
            accounts_base,
            accounts_override,
            api_base,
            api_override,
            insecure_cookies: vars
                .get("INSECURE_COOKIES")
                .is_some_and(|v| v == "true" || v == "1"),
            artist_albums: vars
                .get("SPOTIFY_ARTIST_ALBUMS")
                .is_some_and(|v| matches!(v.trim(), "1" | "true")),
            warnings,
        }
    }

    /// The accounts service's reach: [Reach::Any] only with the loopback override (§2.4).
    pub fn accounts_reach(&self) -> Reach {
        if self.accounts_override {
            Reach::Any
        } else {
            Reach::Public
        }
    }

    /// The Web API's reach: [Reach::Any] only with the loopback override.
    pub fn api_reach(&self) -> Reach {
        if self.api_override {
            Reach::Any
        } else {
            Reach::Public
        }
    }

    /// The accounts service's origin, for the Spotify page's `form-action` (its login form's 303
    /// leads there).
    pub fn accounts_origin(&self) -> String {
        reqwest::Url::parse(&self.accounts_base)
            .map(|url| url.origin().ascii_serialization())
            .unwrap_or_else(|_| ACCOUNTS_BASE.to_string())
    }
}

// --- the app -------------------------------------------------------------------------------------

/// A client ID: 32 of `[0-9A-Za-z]` (Spotify's are hex; letters and digits are taken).
pub fn valid_client_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// `https://<host>[:port]/music/spotify/callback`, or exactly [LOOPBACK_REDIRECT] (§2.1).
pub fn valid_redirect_uri(uri: &str) -> bool {
    if uri == LOOPBACK_REDIRECT {
        return true;
    }
    if uri.len() > 300 || uri.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return false;
    }
    reqwest::Url::parse(uri).is_ok_and(|url| {
        url.scheme() == "https"
            && url.host_str().is_some_and(|h| !h.is_empty())
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
            && url.path() == CALLBACK_PATH
            && uri.ends_with(CALLBACK_PATH)
    })
}

/// A `Host` (or `X-Forwarded-Host`) value usable in a redirect URI: a name or an address, with
/// an optional port.
fn host_ok(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 255
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':' | b'[' | b']'))
}

/// The redirect URI the page suggests (§2.1): `https://<host>/music/spotify/callback` only when
/// the request itself was https - a trusted proxy said `X-Forwarded-Proto: https` - with the
/// trusted proxy's `X-Forwarded-Host`, else `Host`. Plain http (by name or address) gets
/// [LOOPBACK_REDIRECT].
pub fn suggested_redirect(headers: &axum::http::HeaderMap, peer_trusted: bool) -> String {
    let https =
        peer_trusted && first_header(headers, "x-forwarded-proto").as_deref() == Some("https");
    match request_host(headers, peer_trusted).filter(|_| https) {
        Some(host) => format!("https://{host}{CALLBACK_PATH}"),
        None => LOOPBACK_REDIRECT.to_string(),
    }
}

/// A header's first comma-separated value, trimmed and lower-cased.
fn first_header(headers: &axum::http::HeaderMap, name: &str) -> Option<String> {
    headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').next())
        .map(|v| v.trim().to_ascii_lowercase())
}

/// The address the browser used for this request: a trusted proxy's `X-Forwarded-Host`, else
/// `Host` - lower-cased, `:443` dropped. `None` when neither is a usable host.
pub fn request_host(headers: &axum::http::HeaderMap, peer_trusted: bool) -> Option<String> {
    let host = first_header(headers, "x-forwarded-host")
        .filter(|h| peer_trusted && host_ok(h))
        .or_else(|| first_header(headers, "host").filter(|h| host_ok(h)))?;
    Some(host.strip_suffix(":443").unwrap_or(&host).to_string())
}

/// An https redirect URI's host[:port] (lower case; the default port left out), `None` for the
/// loopback one.
pub fn redirect_host(redirect_uri: &str) -> Option<String> {
    let url = reqwest::Url::parse(redirect_uri).ok()?;
    if url.scheme() != "https" {
        return None;
    }
    let host = url.host_str()?.to_ascii_lowercase();
    Some(match url.port() {
        Some(port) => format!("{host}:{port}"),
        None => host,
    })
}

/// The app in effect: the environment's client ID (and redirect URI) over the PWA's row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct App {
    pub client_id: String,
    pub redirect_uri: Option<String>,
    pub client_from_env: bool,
    pub redirect_from_env: bool,
    /// The PWA's row exists.
    pub saved: bool,
}

impl App {
    /// The client ID and the redirect URI, when both are known (a login can start).
    pub fn ready(&self) -> Option<(&str, &str)> {
        Some((self.client_id.as_str(), self.redirect_uri.as_deref()?))
    }

    /// "…abcd": the client ID's last four characters, to match it with the dashboard.
    pub fn last_four(&self) -> String {
        let n = self.client_id.len();
        self.client_id[n.saturating_sub(4)..].to_string()
    }
}

/// The app in effect, `None` = Spotify isn't configured (§2.1: nothing else changes then).
pub async fn app(state: &AppState) -> Result<Option<App>, sqlx::Error> {
    let row: Option<(String, String)> =
        sqlx::query_as("SELECT client_id, redirect_uri FROM music_spotify_app WHERE id = 1")
            .fetch_optional(&state.db)
            .await?;
    let config = state.spotify.config();
    let saved = row.is_some();
    let (row_client, row_redirect) = match row {
        Some((client, redirect)) => (Some(client), Some(redirect)),
        None => (None, None),
    };
    let (client_id, client_from_env) = match (&config.env_client_id, row_client) {
        (Some(env), _) => (env.clone(), true),
        (None, Some(row)) => (row, false),
        (None, None) => return Ok(None),
    };
    let (redirect_uri, redirect_from_env) = match (&config.env_redirect_uri, row_redirect) {
        (Some(env), _) => (Some(env.clone()), true),
        (None, row) => (row, false),
    };
    Ok(Some(App {
        client_id,
        redirect_uri,
        client_from_env,
        redirect_from_env,
        saved,
    }))
}

// --- secrets -------------------------------------------------------------------------------------

/// An access token: memory only (one hour). No `Debug`, no `Display`.
#[derive(Clone)]
pub struct AccessToken(String);

impl AccessToken {
    /// The token itself, for an `Authorization` header or the phone's answer (step 3).
    pub fn secret(&self) -> &str {
        &self.0
    }

    fn bearer(&self) -> String {
        format!("Bearer {}", self.0)
    }
}

/// A refresh token: sealed at rest, sent only to the accounts service. No `Debug`, no `Display`.
#[derive(Clone)]
pub struct RefreshToken(String);

/// The associated data a refresh token is sealed with (§2.2): `spotify-v1:<fingerprint>:<user>`.
pub fn aad(fingerprint: &str, spotify_user: &str) -> Vec<u8> {
    format!("spotify-v1:{fingerprint}:{spotify_user}").into_bytes()
}

/// A token answer from the accounts service. No `Debug`, no `Display`.
pub struct TokenAnswer {
    pub access: AccessToken,
    pub expires_in: u64,
    pub refresh: Option<RefreshToken>,
    pub scope: Option<String>,
}

/// Why the accounts service didn't give a token.
#[derive(Debug, Clone, PartialEq)]
enum TokenError {
    /// 400 `invalid_grant`: the refresh token (or the code) is no longer valid.
    InvalidGrant,
    /// 400/401 `invalid_client`: the client ID is wrong, or the app was deleted or switched off.
    InvalidClient,
    Other(SpotifyError),
}

/// The `Retry-After` seconds of a response (a date form is no number: `None`).
fn retry_after_secs(response: &SourceResponse) -> Option<u64> {
    response.retry_after.as_deref()?.trim().parse().ok()
}

/// A token answer read (§2.4). The body is never kept in an error.
fn read_token_answer(response: &SourceResponse) -> Result<TokenAnswer, TokenError> {
    let body = || serde_json::from_slice::<Value>(&response.body).ok();
    match response.status {
        200 => {
            if response.truncated {
                return Err(TokenError::Other(SpotifyError::TooBig));
            }
            let body = body().ok_or(TokenError::Other(SpotifyError::BadAnswer))?;
            let text = |key: &str| {
                body.get(key)
                    .and_then(Value::as_str)
                    .filter(|v| !v.is_empty())
                    .map(str::to_string)
            };
            let access = text("access_token").ok_or(TokenError::Other(SpotifyError::BadAnswer))?;
            let expires_in = body
                .get("expires_in")
                .and_then(Value::as_u64)
                // At most a day (code QA L4): Spotify's are an hour, and an absurd value would
                // overflow the expiry's `Instant`.
                .filter(|s| (1..=MAX_EXPIRES_IN).contains(s))
                .ok_or(TokenError::Other(SpotifyError::BadAnswer))?;
            Ok(TokenAnswer {
                access: AccessToken(access),
                expires_in,
                refresh: text("refresh_token").map(RefreshToken),
                scope: text("scope").map(|s| s.chars().take(500).collect()),
            })
        }
        400 | 401 => match body()
            .as_ref()
            .and_then(|b| b.get("error"))
            .and_then(Value::as_str)
        {
            Some("invalid_grant") => Err(TokenError::InvalidGrant),
            Some("invalid_client") => Err(TokenError::InvalidClient),
            _ => Err(TokenError::Other(SpotifyError::Status(response.status))),
        },
        429 => Err(TokenError::Other(SpotifyError::RateLimited(
            retry_after_secs(response),
        ))),
        status => Err(TokenError::Other(SpotifyError::Status(status))),
    }
}

/// A 429's body says the developer account's quota is used up (`"reason": "QUOTA_EXCEEDED"`,
/// at the top or inside `error`).
fn quota_exceeded(body: &[u8]) -> bool {
    serde_json::from_slice::<Value>(body).is_ok_and(|body| {
        let reason = |v: &Value| v.get("reason").and_then(Value::as_str) == Some("QUOTA_EXCEEDED");
        reason(&body) || body.get("error").is_some_and(reason)
    })
}

// --- errors --------------------------------------------------------------------------------------

/// What went wrong, as a code (§2.4). Nothing here holds a URL, a token, a code or a body.
#[derive(Debug, Clone, PartialEq)]
pub enum SpotifyError {
    /// No app set up (`spotify_off`).
    Off,
    /// No account can be used (`no_account`): none, or the one asked for is gone.
    NoAccount,
    /// The account's login ended (`needs_login`): the parent logs in again (`reauthorize`).
    Reauthorize,
    /// The accounts service refused the client ID (`app_refused`).
    AppRefused,
    /// 404 from the Web API (`not_found`).
    NotFound,
    /// 429 (`rate_limited`), with `Retry-After` when it was a number.
    RateLimited(Option<u64>),
    /// 429 `QUOTA_EXCEEDED`, or inside [QUOTA_PAUSE] after one (`quota`).
    Quota,
    Fetch(FetchError),
    /// Another status (`http_<n>`).
    Status(u16),
    /// The answer was past its kind's cap (`too_big`).
    TooBig,
    /// An answer that isn't what Spotify documents (`bad_answer`).
    BadAnswer,
    /// The music key can't open the sealed refresh token (`key`).
    Key,
    Database(String),
}

impl SpotifyError {
    pub fn code(&self) -> String {
        match self {
            SpotifyError::Off => "spotify_off".to_string(),
            SpotifyError::NoAccount => "no_account".to_string(),
            SpotifyError::Reauthorize => "reauthorize".to_string(),
            SpotifyError::AppRefused => "app_refused".to_string(),
            SpotifyError::NotFound => "not_found".to_string(),
            SpotifyError::RateLimited(_) => "rate_limited".to_string(),
            SpotifyError::Quota => "quota".to_string(),
            SpotifyError::Fetch(err) => err.code().to_string(),
            SpotifyError::Status(status) => format!("http_{status}"),
            SpotifyError::TooBig => "too_big".to_string(),
            SpotifyError::BadAnswer => "bad_answer".to_string(),
            SpotifyError::Key => "key".to_string(),
            SpotifyError::Database(_) => "database".to_string(),
        }
    }

    /// One line for a page.
    pub fn message(&self) -> String {
        match self {
            SpotifyError::Off => "No Spotify app is set up on this server.".to_string(),
            SpotifyError::NoAccount => "No Spotify account is logged in.".to_string(),
            SpotifyError::Reauthorize => "The Spotify login needs renewing.".to_string(),
            SpotifyError::AppRefused => {
                "Spotify doesn't accept this server's client ID any more.".to_string()
            }
            SpotifyError::NotFound => {
                "Spotify doesn't let this app open it (or it was deleted).".to_string()
            }
            SpotifyError::RateLimited(_) => {
                "Spotify asked this server to slow down - it tries again later.".to_string()
            }
            SpotifyError::Quota => {
                "Spotify's limit for this app was reached - it tries again later.".to_string()
            }
            SpotifyError::Fetch(FetchError::Timeout) => {
                "Spotify didn't answer in time.".to_string()
            }
            SpotifyError::Fetch(_) => "Spotify couldn't be reached.".to_string(),
            SpotifyError::Status(status) => format!("Spotify answered {status}."),
            SpotifyError::TooBig => "Spotify's answer was too big to read.".to_string(),
            SpotifyError::BadAnswer => "Spotify sent an answer this server can't read.".to_string(),
            SpotifyError::Key => {
                "The saved Spotify login was locked with another key - log in again.".to_string()
            }
            SpotifyError::Database(_) => {
                "Couldn't read the Spotify accounts - check the server log.".to_string()
            }
        }
    }
}

impl From<sqlx::Error> for SpotifyError {
    fn from(err: sqlx::Error) -> Self {
        SpotifyError::Database(err.to_string())
    }
}

// --- the accounts --------------------------------------------------------------------------------

/// An account as the page lists it (no secret in it).
#[derive(sqlx::FromRow, Clone)]
pub struct AccountRow {
    pub id: i64,
    pub display_name: String,
    pub authorized_at: String,
    /// The last refresh's failure (a code: `invalid_grant`, `invalid_client`, `network`,
    /// `http_<n>`, `key`), or `None`.
    pub refresh_error: Option<String>,
    pub needs_login: i64,
}

impl AccountRow {
    /// When the login has to be renewed: `authorized_at` + [LOGIN_VALID_DAYS].
    pub fn login_ends(&self) -> Option<DateTime<Utc>> {
        parse_stamp(&self.authorized_at).map(|at| at + TimeDelta::days(LOGIN_VALID_DAYS))
    }

    /// Within [LOGIN_WARN_DAYS] of the line, or past it.
    pub fn login_ending(&self, now: DateTime<Utc>) -> bool {
        self.login_ends()
            .is_some_and(|ends| now >= ends - TimeDelta::days(LOGIN_WARN_DAYS))
    }
}

/// Every account, by id.
pub async fn accounts(db: &sqlx::SqlitePool) -> Result<Vec<AccountRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, display_name, authorized_at, refresh_error, needs_login \
         FROM music_spotify_accounts ORDER BY id",
    )
    .fetch_all(db)
    .await
}

/// The sealed parts of an account (read only to refresh).
#[derive(sqlx::FromRow)]
struct SealedRow {
    spotify_user: String,
    display_name: String,
    ciphertext: Vec<u8>,
    nonce: Vec<u8>,
    key_fingerprint: String,
    generation: i64,
    needs_login: i64,
}

async fn sealed_row(db: &sqlx::SqlitePool, account: i64) -> Result<Option<SealedRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT spotify_user, display_name, ciphertext, nonce, key_fingerprint, generation, \
         needs_login FROM music_spotify_accounts WHERE id = ?",
    )
    .bind(account)
    .fetch_optional(db)
    .await
}

/// Removes one account (§2.2): its phones unbound first (each `UPDATE` moves the library
/// revision), then the row (the token and the name go). The phones that were bound, and the
/// display name, or `None` for an unknown id.
pub async fn remove_account(
    state: &AppState,
    account: i64,
) -> Result<Option<(String, Vec<i64>)>, sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let phones: Vec<i64> = sqlx::query_scalar(
        "UPDATE device_policy SET music_spotify_account = NULL \
         WHERE music_spotify_account = ? RETURNING device_id",
    )
    .bind(account)
    .fetch_all(&mut *tx)
    .await?;
    let name: Option<String> = sqlx::query_scalar(
        "DELETE FROM music_spotify_accounts WHERE id = ? RETURNING display_name",
    )
    .bind(account)
    .fetch_optional(&mut *tx)
    .await?;
    tx.commit().await?;
    state.spotify.forget(account);
    Ok(name.map(|name| (name, phones)))
}

/// Removes every account, and the app row with `app_too` (Clear, §2.1; a new client ID removes
/// the accounts only): the phones unbound first, all in one transaction. How many accounts went,
/// and the phones that were bound.
pub async fn remove_all(state: &AppState, app_too: bool) -> Result<(u64, Vec<i64>), sqlx::Error> {
    let mut tx = state.db.begin().await?;
    let phones: Vec<i64> = sqlx::query_scalar(
        "UPDATE device_policy SET music_spotify_account = NULL \
         WHERE music_spotify_account IS NOT NULL RETURNING device_id",
    )
    .fetch_all(&mut *tx)
    .await?;
    let removed = sqlx::query("DELETE FROM music_spotify_accounts")
        .execute(&mut *tx)
        .await?
        .rows_affected();
    if app_too {
        sqlx::query("DELETE FROM music_spotify_app")
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    state.spotify.forget_all();
    Ok((removed, phones))
}

/// An unfinished login (memory only). No `Debug`, no `Display`.
pub struct Pending {
    pub state: String,
    verifier: Verifier,
    /// The admin who started it (the event's name; the paste must come from the same admin).
    pub admin: String,
    pub client_id: String,
    pub redirect_uri: String,
    created: Instant,
}

/// What [Accounts::start_login] made.
pub struct Started {
    /// Where the parent's browser goes.
    pub authorize_url: String,
    /// The `spotify_login` cookie's value.
    pub cookie: String,
}

/// How a login ended, as the page's `?login=` code.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LoginOutcome {
    /// Logged in (the display name).
    Ok(String),
    /// The parent said no on Spotify's page.
    Cancelled,
    /// `/me` answered 403: the account isn't under the app's User Management.
    NotRegistered,
    /// The exchange or `/me` refused, or an answer that isn't Spotify's.
    Refused,
    Unreachable,
    /// No such unfinished login (unknown, used, older than 10 minutes, another app since, or
    /// another browser).
    Expired,
    /// No app or no music key any more.
    Off,
    /// The account couldn't be stored.
    Error,
}

impl LoginOutcome {
    pub fn code(&self) -> &'static str {
        match self {
            LoginOutcome::Ok(_) => "ok",
            LoginOutcome::Cancelled => "cancelled",
            LoginOutcome::NotRegistered => "not_registered",
            LoginOutcome::Refused => "refused",
            LoginOutcome::Unreachable => "unreachable",
            LoginOutcome::Expired => "expired",
            LoginOutcome::Off => "off",
            LoginOutcome::Error => "error",
        }
    }

    /// The page's line for a `?login=` code (`ok` names the newest account), and whether it is
    /// good news. `None` for an unknown code.
    pub fn text(code: &str, newest: Option<&str>) -> Option<(String, bool)> {
        Some(match code {
            "ok" => (
                match newest {
                    Some(name) => format!("Logged in as {name}."),
                    None => "Logged in.".to_string(),
                },
                true,
            ),
            "cancelled" => ("Spotify login cancelled.".to_string(), false),
            "not_registered" => (
                "Spotify refused this account for your app: add it under User Management in the \
                 dashboard (name and e-mail), then log in again."
                    .to_string(),
                false,
            ),
            "refused" => (
                "Spotify refused the login - check that the Client ID and the Redirect URI here \
                 are exactly the ones in your app's settings, then log in again."
                    .to_string(),
                false,
            ),
            "unreachable" => (
                "Spotify couldn't be reached - log in again in a moment.".to_string(),
                false,
            ),
            "expired" => (
                "That login wasn't started in this browser, was started at another address of \
                 this server, was already used, or took longer than 10 minutes - log in again."
                    .to_string(),
                false,
            ),
            "off" => (
                "Spotify isn't set up on this server any more - nothing was stored.".to_string(),
                false,
            ),
            "error" => (
                "The login couldn't be saved - check the server log.".to_string(),
                false,
            ),
            _ => return None,
        })
    }
}

/// The accounts service's token request (§2.4): a form POST to `<accounts base>/api/token`.
fn token_request(config: &Config, form: String) -> SourceRequest {
    use reqwest::header::{ACCEPT, CONTENT_TYPE};
    let mut request = SourceRequest::new(
        format!("{}/api/token", config.accounts_base),
        Kind::Token,
        config.accounts_reach(),
    );
    request.method = reqwest::Method::POST;
    request.headers = vec![
        (
            CONTENT_TYPE,
            "application/x-www-form-urlencoded".to_string(),
        ),
        (ACCEPT, "application/json".to_string()),
    ];
    request.body = Some(form.into_bytes());
    request
}

/// A cached access token. No `Debug`, no `Display`.
struct Access {
    token: AccessToken,
    expires: Instant,
    generation: i64,
}

/// One account's cache: its access token, and a rotated refresh token whose write failed (used
/// and written again at the next refresh).
#[derive(Default)]
struct Slot {
    access: Option<Access>,
    unsaved: Option<(i64, RefreshToken)>,
}

/// An access token handed out. No `Debug`, no `Display`.
pub struct Minted {
    pub token: AccessToken,
    /// What is left of it.
    pub left: Duration,
    pub account: i64,
    pub generation: i64,
}

/// `AppState.spotify`: the configuration, the unfinished logins, the access-token cache and the
/// quota pause - in memory only.
pub struct Accounts {
    config: Config,
    pending: std::sync::Mutex<VecDeque<Pending>>,
    access: std::sync::Mutex<HashMap<i64, Arc<tokio::sync::Mutex<Slot>>>>,
    quota_until: std::sync::Mutex<Option<Instant>>,
    /// The tests move the tokens' and the quota's clock forward.
    skew: std::sync::Mutex<Duration>,
    /// The add form's budget ([ADD_BUDGET]; the tests shorten it).
    add_budget: std::sync::Mutex<Duration>,
    /// Each phone's token asks in the last [PHONE_TOKEN_WINDOW] ([Accounts::phone_ask]).
    phone_asks: std::sync::Mutex<HashMap<i64, VecDeque<Instant>>>,
    /// The token route's budget ([PHONE_TOKEN_BUDGET]; the tests shorten it).
    phone_token_budget: std::sync::Mutex<Duration>,
}

impl Default for Accounts {
    fn default() -> Self {
        Accounts::new(Config::default())
    }
}

fn lock<T>(mutex: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl Accounts {
    pub fn new(config: Config) -> Self {
        Accounts {
            config,
            pending: Default::default(),
            access: Default::default(),
            quota_until: Default::default(),
            skew: Default::default(),
            add_budget: std::sync::Mutex::new(ADD_BUDGET),
            phone_asks: Default::default(),
            phone_token_budget: std::sync::Mutex::new(PHONE_TOKEN_BUDGET),
        }
    }

    /// The token route's budget for one mint ([PHONE_TOKEN_BUDGET]).
    pub fn phone_token_budget(&self) -> Duration {
        *lock(&self.phone_token_budget)
    }

    /// A shorter token route budget (the tests).
    #[cfg(test)]
    pub fn set_phone_token_budget(&self, budget: Duration) {
        *lock(&self.phone_token_budget) = budget;
    }

    /// One token ask of `device` (§2.6): `Ok` within [PHONE_TOKENS_PER_WINDOW] asks a rolling
    /// [PHONE_TOKEN_WINDOW], else `Err(seconds)` until the oldest ask leaves the window (at least
    /// 1). A refused ask isn't counted. Phones with no ask left in the window are forgotten.
    pub fn phone_ask(&self, device: i64) -> Result<(), u64> {
        let now = self.now();
        let mut asks = lock(&self.phone_asks);
        asks.retain(|_, times| {
            while times
                .front()
                .is_some_and(|at| now.saturating_duration_since(*at) >= PHONE_TOKEN_WINDOW)
            {
                times.pop_front();
            }
            !times.is_empty()
        });
        let times = asks.entry(device).or_default();
        if times.len() >= PHONE_TOKENS_PER_WINDOW {
            let oldest = times.front().copied().unwrap_or(now);
            let wait = PHONE_TOKEN_WINDOW.saturating_sub(now.saturating_duration_since(oldest));
            // Rounded up: asking again after it never meets the same refusal.
            return Err((wait.as_secs() + u64::from(wait.subsec_nanos() > 0)).max(1));
        }
        times.push_back(now);
        Ok(())
    }

    /// The add form's budget for a short link and the add check together ([ADD_BUDGET]).
    pub fn add_budget(&self) -> Duration {
        *lock(&self.add_budget)
    }

    /// A shorter add budget (the tests).
    #[cfg(test)]
    pub fn set_add_budget(&self, budget: Duration) {
        *lock(&self.add_budget) = budget;
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    fn now(&self) -> Instant {
        Instant::now() + *lock(&self.skew)
    }

    /// Moves this cache's clock (tokens, unfinished logins, the quota pause) forward.
    #[cfg(test)]
    pub fn advance(&self, by: Duration) {
        *lock(&self.skew) += by;
    }

    // --- the login ---

    /// Starts a login (§2.3): a fresh PKCE pair and `state`, kept in memory with the admin who
    /// started it (a ninth drops the oldest); the authorize URL and the cookie value.
    pub fn start_login(&self, admin: &str, client_id: &str, redirect_uri: &str) -> Started {
        let verifier = Verifier::new();
        let state = new_state();
        let authorize_url = authorize_url(
            &self.config.accounts_base,
            client_id,
            redirect_uri,
            &verifier.challenge(),
            &state,
        );
        let cookie = state_cookie(&state);
        let now = self.now();
        let mut pending = lock(&self.pending);
        pending.retain(|p| now.saturating_duration_since(p.created) < PENDING_TTL);
        while pending.len() >= PENDING_MAX {
            pending.pop_front();
        }
        pending.push_back(Pending {
            state,
            verifier,
            admin: admin.to_string(),
            client_id: client_id.to_string(),
            redirect_uri: redirect_uri.to_string(),
            created: now,
        });
        Started {
            authorize_url,
            cookie,
        }
    }

    /// The unfinished login of `state`, taken out (a replay finds nothing); `None` when unknown
    /// or older than [PENDING_TTL]. With `admin` (the paste) a login another admin started is
    /// left in place (code QA L1): it isn't this admin's to finish, nor to use up.
    pub fn take_pending(&self, state: &str, admin: Option<&str>) -> Option<Pending> {
        let now = self.now();
        let mut pending = lock(&self.pending);
        let index = pending.iter().position(|p| p.state == state)?;
        if admin.is_some_and(|admin| admin != pending[index].admin) {
            return None;
        }
        let found = pending.remove(index)?;
        (now.saturating_duration_since(found.created) < PENDING_TTL).then_some(found)
    }

    /// How many logins are unfinished (the tests).
    #[cfg(test)]
    pub fn pending_count(&self) -> usize {
        lock(&self.pending).len()
    }

    fn token_request(&self, form: String) -> SourceRequest {
        token_request(&self.config, form)
    }

    fn api_request(&self, url: &str, kind: Kind, token: &AccessToken) -> SourceRequest {
        use reqwest::header::{ACCEPT, AUTHORIZATION};
        let mut request = SourceRequest::new(url, kind, self.config.api_reach());
        request.headers = vec![
            (AUTHORIZATION, token.bearer()),
            (ACCEPT, "application/json".to_string()),
        ];
        request
    }

    /// A Web API URL: the API base and `path` (built from canonical ids only, never pasted text).
    pub fn api_url(&self, path: &str) -> String {
        format!("{}{path}", self.config.api_base)
    }

    /// Ends a login (§2.3) - the callback's and the paste's one code path, after `state` was
    /// taken out: the code exchanged (`authorization_code`, the verifier), `/v1/me` read with the
    /// new access token, the account upserted (a new login of the same account replaces the
    /// token and bumps `generation`, keeping the row and its phones), the refresh token sealed.
    /// Within [LOGIN_BUDGET] in all. Events: `spotify_account_added`, `spotify_login_failed`
    /// (the code only).
    pub async fn complete_login(
        &self,
        state: &AppState,
        pending: Pending,
        code: &str,
    ) -> LoginOutcome {
        let admin = pending.admin.clone();
        let outcome =
            match tokio::time::timeout(LOGIN_BUDGET, self.finish(state, pending, code)).await {
                Ok(outcome) => outcome,
                Err(_) => Err(("timeout".to_string(), LoginOutcome::Unreachable)),
            };
        match outcome {
            Ok((name, new)) => {
                let detail = if new {
                    format!("Spotify account {name} logged in")
                } else {
                    format!("Spotify account {name} logged in again")
                };
                crate::security::record_security_event(
                    &state.db,
                    "spotify_account_added",
                    Some(&admin),
                    None,
                    Some(&detail),
                )
                .await;
                // The Spotify entries that failed for want of a (good) login are due now
                // (`music_sweep::SPOTIFY_LOGIN_ERRORS`, design 26c step 2b).
                state.music_sweep.wake();
                LoginOutcome::Ok(name)
            }
            Err((code, outcome)) => {
                tracing::info!(%code, "spotify: a login failed");
                crate::security::record_security_event(
                    &state.db,
                    "spotify_login_failed",
                    Some(&admin),
                    None,
                    Some(&code),
                )
                .await;
                outcome
            }
        }
    }

    /// [Accounts::complete_login]'s work: `(display name, a new account)`, or `(code, outcome)`.
    async fn finish(
        &self,
        state: &AppState,
        pending: Pending,
        code: &str,
    ) -> Result<(String, bool), (String, LoginOutcome)> {
        let Some(key) = state.music_key.clone() else {
            return Err(("key".to_string(), LoginOutcome::Off));
        };
        let form = form_urlencoded::Serializer::new(String::new())
            .append_pair("grant_type", "authorization_code")
            .append_pair("code", code)
            .append_pair("redirect_uri", &pending.redirect_uri)
            .append_pair("client_id", &pending.client_id)
            .append_pair("code_verifier", pending.verifier.as_str())
            .finish();
        let answer = match state.music_fetch.get(self.token_request(form)).await {
            Ok(response) => read_token_answer(&response),
            Err(err) => Err(TokenError::Other(SpotifyError::Fetch(err))),
        };
        let answer = answer.map_err(|err| match err {
            TokenError::InvalidGrant => ("invalid_grant".to_string(), LoginOutcome::Refused),
            TokenError::InvalidClient => ("invalid_client".to_string(), LoginOutcome::Refused),
            TokenError::Other(err) => {
                let outcome = match &err {
                    SpotifyError::Fetch(_) | SpotifyError::RateLimited(_) => {
                        LoginOutcome::Unreachable
                    }
                    SpotifyError::Status(status) if *status >= 500 => LoginOutcome::Unreachable,
                    _ => LoginOutcome::Refused,
                };
                (err.code(), outcome)
            }
        })?;
        let Some(refresh) = answer.refresh else {
            return Err(("no_refresh_token".to_string(), LoginOutcome::Refused));
        };
        // `/v1/me`: who logged in. A 403 = not under the app's User Management (Quota modes).
        let me = state
            .music_fetch
            .get(self.api_request(&self.api_url("/v1/me"), Kind::Token, &answer.access))
            .await
            .map_err(|err| (err.code().to_string(), LoginOutcome::Unreachable))?;
        let me = match me.status {
            200 if !me.truncated => serde_json::from_slice::<Value>(&me.body)
                .map_err(|_| ("bad_answer".to_string(), LoginOutcome::Refused))?,
            403 => return Err(("not_registered".to_string(), LoginOutcome::NotRegistered)),
            status if status == 429 || status >= 500 => {
                return Err((format!("http_{status}"), LoginOutcome::Unreachable));
            }
            status => return Err((format!("http_{status}"), LoginOutcome::Refused)),
        };
        let user = me
            .get("id")
            .and_then(Value::as_str)
            .filter(|id| (1..=128).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_graphic()))
            .ok_or(("bad_answer".to_string(), LoginOutcome::Refused))?
            .to_string();
        let name: String = me
            .get("display_name")
            .and_then(Value::as_str)
            .map(|name| {
                name.chars()
                    .filter(|c| !c.is_control())
                    .take(MAX_DISPLAY_NAME_CHARS)
                    .collect::<String>()
                    .trim()
                    .to_string()
            })
            .filter(|name| !name.is_empty())
            .unwrap_or_else(|| user.chars().take(MAX_DISPLAY_NAME_CHARS).collect());
        let (ciphertext, nonce) =
            key.seal_with(&aad(&key.fingerprint, &user), refresh.0.as_bytes());
        let now = stamp(state.music_sweep.now());
        let stored: Result<(i64, i64), sqlx::Error> = sqlx::query_as(
            "INSERT INTO music_spotify_accounts (spotify_user, display_name, ciphertext, nonce, \
             key_fingerprint, scopes, authorized_at, generation, needs_login) \
             VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0) \
             ON CONFLICT (spotify_user) DO UPDATE SET display_name = excluded.display_name, \
             ciphertext = excluded.ciphertext, nonce = excluded.nonce, \
             key_fingerprint = excluded.key_fingerprint, scopes = excluded.scopes, \
             authorized_at = excluded.authorized_at, \
             generation = music_spotify_accounts.generation + 1, needs_login = 0, \
             refresh_ok_at = NULL, refresh_error = NULL, refresh_error_at = NULL \
             RETURNING id, generation",
        )
        .bind(&user)
        .bind(&name)
        .bind(&ciphertext)
        .bind(&nonce)
        .bind(&key.fingerprint)
        .bind(answer.scope.as_deref().unwrap_or(SCOPES))
        .bind(&now)
        .fetch_one(&state.db)
        .await;
        let (account, generation) = match stored {
            Ok(row) => row,
            Err(err) => {
                tracing::error!(%err, "spotify: couldn't store a logged-in account");
                return Err(("database".to_string(), LoginOutcome::Error));
            }
        };
        tracing::info!(account, generation, "spotify: an account logged in");
        // The exchange's access token serves until it expires (never waits for a refresh in
        // progress: the generation check makes an older token unusable).
        if let Ok(mut slot) = self.slot(account).try_lock() {
            slot.unsaved = None;
            slot.access = Some(Access {
                token: answer.access,
                expires: self.now()
                    + Duration::from_secs(answer.expires_in).saturating_sub(EXPIRY_MARGIN),
                generation,
            });
        }
        Ok((name, generation == 1))
    }

    // --- tokens ---

    fn slot(&self, account: i64) -> Arc<tokio::sync::Mutex<Slot>> {
        lock(&self.access).entry(account).or_default().clone()
    }

    /// Forgets one account's cached tokens (Remove).
    pub fn forget(&self, account: i64) {
        lock(&self.access).remove(&account);
    }

    /// Forgets every cached token (Clear, a new client ID).
    pub fn forget_all(&self) {
        lock(&self.access).clear();
    }

    /// An access token of `account` with at least `min_left` left (§2.4): the kept one when the
    /// row's generation is its own, else a refresh under the account's lock (one at a time).
    /// `needs_login` = [SpotifyError::Reauthorize] without a request; a key that can't open the
    /// token = [SpotifyError::Key].
    pub async fn access_token(
        &self,
        state: &AppState,
        account: i64,
        min_left: Duration,
    ) -> Result<Minted, SpotifyError> {
        self.mint(state, account, min_left, None, &mut 0).await
    }

    /// [Accounts::access_token]; with `rejected` (a token the Web API answered 401 to) the kept
    /// token is used only when it is another one - a concurrent 401 refreshed it already - else
    /// it is refreshed (code QA L3: two 401s with one token make one refresh). `sent` counts the
    /// requests made (the sweep's budget).
    ///
    /// Everything under the account's lock runs as a task of its own (qa-26c-step2b-code M1): the
    /// caller only awaits it, so a caller dropped partway - the add form's 45 s budget, a phone's
    /// request that went away - never cancels a refresh after Spotify answered, the rotated
    /// refresh token's write, or the in-memory copy of one whose write failed. The lock moves into
    /// the task, so a second caller still waits for the one refresh.
    async fn mint(
        &self,
        state: &AppState,
        account: i64,
        min_left: Duration,
        rejected: Option<&AccessToken>,
        sent: &mut u32,
    ) -> Result<Minted, SpotifyError> {
        let slot = self.slot(account).lock_owned().await;
        let job = MintJob {
            state: state.clone(),
            config: self.config.clone(),
            now: self.now(),
            account,
            min_left,
            rejected: rejected.cloned(),
        };
        let (minted, made) = tokio::spawn(job.run(slot)).await.map_err(|err| {
            tracing::error!(account, %err, "spotify: the token task failed");
            SpotifyError::Database("the token task failed".to_string())
        })?;
        *sent += made;
        minted
    }
}

/// One [Accounts::mint] under the account's lock, owning everything it needs (it runs as a
/// spawned task: see `mint`). No `Debug`: it holds a token.
struct MintJob {
    state: AppState,
    config: Config,
    now: Instant,
    account: i64,
    min_left: Duration,
    rejected: Option<AccessToken>,
}

impl MintJob {
    /// The minted token (or why not) and how many requests it made.
    async fn run(
        self,
        mut slot: tokio::sync::OwnedMutexGuard<Slot>,
    ) -> (Result<Minted, SpotifyError>, u32) {
        let mut sent = 0;
        let minted = self.mint(&mut slot, &mut sent).await;
        (minted, sent)
    }

    async fn mint(&self, slot: &mut Slot, sent: &mut u32) -> Result<Minted, SpotifyError> {
        let state = &self.state;
        let account = self.account;
        let min_left = self.min_left;
        let rejected = self.rejected.as_ref();
        let now = self.now;
        let Some(row) = sealed_row(&state.db, account).await? else {
            *slot = Slot::default();
            return Err(SpotifyError::NoAccount);
        };
        // A rotated refresh token whose write failed is written again at every call, the
        // cached path included (code QA L2): memory is its only copy until then.
        if let Some((generation, token)) = slot.unsaved.take()
            && generation == row.generation
            && !write_refresh(state, account, &row, &token).await
        {
            slot.unsaved = Some((generation, token));
        }
        if let Some(access) = &slot.access
            && access.generation == row.generation
        {
            let left = access.expires.saturating_duration_since(now);
            let usable = match rejected {
                Some(rejected) => access.token.0 != rejected.0,
                None => left >= min_left,
            };
            if left > Duration::ZERO && usable {
                return Ok(Minted {
                    token: access.token.clone(),
                    left,
                    account,
                    generation: row.generation,
                });
            }
        }
        if row.needs_login != 0 {
            slot.access = None;
            return Err(SpotifyError::Reauthorize);
        }
        let Some(app) = app(state).await? else {
            return Err(SpotifyError::Off);
        };
        let kept = slot
            .unsaved
            .take()
            .filter(|(generation, _)| *generation == row.generation)
            .map(|(_, token)| token);
        let refresh = match kept.clone() {
            Some(token) => token,
            None => {
                let opened = state.music_key.as_ref().and_then(|key| {
                    key.open_with(
                        &aad(&row.key_fingerprint, &row.spotify_user),
                        &row.ciphertext,
                        &row.nonce,
                        &row.key_fingerprint,
                    )
                });
                match opened.and_then(|plain| String::from_utf8(plain).ok()) {
                    Some(token) => RefreshToken(token),
                    None => {
                        tracing::warn!(
                            account,
                            "spotify: the music key can't open the account's login"
                        );
                        record_error(state, account, row.generation, "key").await;
                        return Err(SpotifyError::Key);
                    }
                }
            }
        };
        let form = form_urlencoded::Serializer::new(String::new())
            .append_pair("grant_type", "refresh_token")
            .append_pair("refresh_token", &refresh.0)
            .append_pair("client_id", &app.client_id)
            .finish();
        *sent += 1;
        let answer = match state
            .music_fetch
            .get(token_request(&self.config, form))
            .await
        {
            Ok(response) => read_token_answer(&response),
            Err(err) => Err(TokenError::Other(SpotifyError::Fetch(err))),
        };
        let answer = match answer {
            Ok(answer) => answer,
            Err(err) => {
                // A rotated token not yet written stays for the next try.
                slot.unsaved = kept.map(|token| (row.generation, token));
                slot.access = None;
                return Err(refresh_failed(state, account, &row, err).await);
            }
        };
        // A rotated refresh token is written before the access token is used (a refresh that
        // didn't rotate retries a write that failed earlier). A write that keeps failing leaves
        // it in memory, tried again at every call.
        if let Some(token) = answer.refresh.or(kept)
            && !write_refresh(state, account, &row, &token).await
        {
            slot.unsaved = Some((row.generation, token));
        }
        let stamped = sqlx::query(
            "UPDATE music_spotify_accounts SET refresh_ok_at = ?, refresh_error = NULL, \
             refresh_error_at = NULL WHERE id = ? AND generation = ?",
        )
        .bind(stamp(state.music_sweep.now()))
        .bind(account)
        .bind(row.generation)
        .execute(&state.db)
        .await;
        if let Err(err) = stamped {
            tracing::warn!(account, %err, "spotify: couldn't stamp a refresh");
        }
        let life = Duration::from_secs(answer.expires_in).saturating_sub(EXPIRY_MARGIN);
        tracing::debug!(account, "spotify: access token refreshed");
        slot.access = Some(Access {
            token: answer.access.clone(),
            expires: now + life,
            generation: row.generation,
        });
        Ok(Minted {
            token: answer.access,
            left: life,
            account,
            generation: row.generation,
        })
    }
}

/// A refresh that got no token: the row's outcome and the error (§2.4).
async fn refresh_failed(
    state: &AppState,
    account: i64,
    row: &SealedRow,
    err: TokenError,
) -> SpotifyError {
    match err {
        TokenError::InvalidGrant => {
            tracing::info!(account, "spotify: the login ended (invalid_grant)");
            let marked = sqlx::query(
                "UPDATE music_spotify_accounts SET needs_login = 1, \
                 refresh_error = 'invalid_grant', refresh_error_at = ? \
                 WHERE id = ? AND generation = ?",
            )
            .bind(stamp(state.music_sweep.now()))
            .bind(account)
            .bind(row.generation)
            .execute(&state.db)
            .await;
            match marked {
                Ok(done) if done.rows_affected() > 0 => {
                    crate::security::record_security_event(
                        &state.db,
                        "spotify_login_needed",
                        None,
                        None,
                        Some(&format!(
                            "Spotify account {}: log in again",
                            row.display_name
                        )),
                    )
                    .await;
                }
                Ok(_) => {}
                Err(err) => {
                    tracing::error!(account, %err, "spotify: couldn't mark a login ended")
                }
            }
            SpotifyError::Reauthorize
        }
        TokenError::InvalidClient => {
            tracing::warn!(
                account,
                "spotify: the accounts service refused the client ID"
            );
            record_error(state, account, row.generation, "invalid_client").await;
            SpotifyError::AppRefused
        }
        TokenError::Other(err) => {
            tracing::warn!(account, code = %err.code(), "spotify: a refresh failed");
            let code = match &err {
                SpotifyError::RateLimited(_) => None,
                SpotifyError::Fetch(_) => Some("network".to_string()),
                SpotifyError::Status(status) => Some(format!("http_{status}")),
                other => Some(other.code()),
            };
            if let Some(code) = code {
                record_error(state, account, row.generation, &code).await;
            }
            err
        }
    }
}

impl Accounts {
    // --- reads ---

    /// The quota pause's end, while it lasts.
    pub fn quota_until(&self) -> Option<Instant> {
        let now = self.now();
        lock(&self.quota_until).filter(|until| *until > now)
    }

    /// A Web API GET as `account` (§2.4): `Authorization: Bearer`, `Accept: application/json`; a
    /// 401 refreshes once and retries once; a 429 waits a `Retry-After` of at most
    /// [RETRY_WAIT_MAX] once, else [SpotifyError::RateLimited] - or, with `QUOTA_EXCEEDED`,
    /// [SpotifyError::Quota], and no Web API read for [QUOTA_PAUSE]; 404 = `not_found`; a
    /// truncated answer `too_big`. `url` is built by the caller from canonical ids only.
    pub async fn api_get(
        &self,
        state: &AppState,
        account: i64,
        url: &str,
        kind: Kind,
    ) -> Result<SourceResponse, SpotifyError> {
        self.api_get_counted(state, account, url, kind, &mut 0)
            .await
    }

    /// [Accounts::api_get], counting in `sent` every request it made - the token refreshes too
    /// (the sweep's budget, `music_sweep::spotify_check`).
    pub async fn api_get_counted(
        &self,
        state: &AppState,
        account: i64,
        url: &str,
        kind: Kind,
        sent: &mut u32,
    ) -> Result<SourceResponse, SpotifyError> {
        let mut refreshed = false;
        let mut rejected: Option<AccessToken> = None;
        let mut waited = false;
        loop {
            if self.quota_until().is_some() {
                return Err(SpotifyError::Quota);
            }
            // After a 401 the next token is another one than the rejected (code QA L3).
            let minted = self
                .mint(
                    state,
                    account,
                    READ_MIN_LEFT,
                    rejected.take().as_ref(),
                    sent,
                )
                .await?;
            *sent += 1;
            let response = state
                .music_fetch
                .get(self.api_request(url, kind, &minted.token))
                .await
                .map_err(SpotifyError::Fetch)?;
            match response.status {
                200 if response.truncated => return Err(SpotifyError::TooBig),
                200 => return Ok(response),
                401 if !refreshed => {
                    tracing::info!(account, "spotify: 401 from the Web API, refreshing once");
                    refreshed = true;
                    rejected = Some(minted.token.clone());
                }
                429 => {
                    if quota_exceeded(&response.body) {
                        tracing::warn!(
                            account,
                            "spotify: the developer account's quota is used up"
                        );
                        *lock(&self.quota_until) = Some(self.now() + QUOTA_PAUSE);
                        return Err(SpotifyError::Quota);
                    }
                    let retry = retry_after_secs(&response);
                    match retry.map(Duration::from_secs) {
                        Some(wait) if !waited && wait <= RETRY_WAIT_MAX => {
                            waited = true;
                            tokio::time::sleep(wait).await;
                        }
                        _ => return Err(SpotifyError::RateLimited(retry)),
                    }
                }
                404 => return Err(SpotifyError::NotFound),
                status => return Err(SpotifyError::Status(status)),
            }
        }
    }
}

/// Writes a rotated refresh token, sealed with the current key, for this generation only; `true`
/// when it is stored (or the row has moved on: a new login or a Remove wins). Tried three times
/// at once (code QA L2): the database is its only durable home, and until it is written a
/// restart loses it - if Spotify revoked the old token on rotation, the account then needs a new
/// login (`invalid_grant` -> `needs_login`, recoverable). The failure is logged at `error`, with
/// the account id only.
async fn write_refresh(
    state: &AppState,
    account: i64,
    row: &SealedRow,
    token: &RefreshToken,
) -> bool {
    let Some(key) = state.music_key.as_ref() else {
        tracing::error!(account, "spotify refresh token not saved (no music key)");
        return false;
    };
    let (ciphertext, nonce) = key.seal_with(
        &aad(&key.fingerprint, &row.spotify_user),
        token.0.as_bytes(),
    );
    for attempt in 0..3u64 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(100 * attempt)).await;
        }
        let written = sqlx::query(
            "UPDATE music_spotify_accounts SET ciphertext = ?, nonce = ?, key_fingerprint = ? \
             WHERE id = ? AND generation = ?",
        )
        .bind(&ciphertext)
        .bind(&nonce)
        .bind(&key.fingerprint)
        .bind(account)
        .bind(row.generation)
        .execute(&state.db)
        .await;
        if written.is_ok() {
            return true;
        }
    }
    tracing::error!(account, "spotify refresh token not saved");
    false
}

/// `refresh_error` (a code, never text) and its time, for this generation only.
async fn record_error(state: &AppState, account: i64, generation: i64, code: &str) {
    let recorded = sqlx::query(
        "UPDATE music_spotify_accounts SET refresh_error = ?, refresh_error_at = ? \
         WHERE id = ? AND generation = ?",
    )
    .bind(code)
    .bind(stamp(state.music_sweep.now()))
    .bind(account)
    .bind(generation)
    .execute(&state.db)
    .await;
    if let Err(err) = recorded {
        tracing::error!(account, %err, "spotify: couldn't record a refresh's outcome");
    }
}

// --- the listing (step 2a: §3.1, §3.3, §3.4) ------------------------------------------------------

/// One Web API read of a listing, built from a canonical id only (§2.4): its URL is
/// [Accounts::read_url]'s.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Read<'a> {
    /// `GET /playlists/{id}?fields=name,snapshot_id,images,owner(id,display_name)`.
    Playlist(&'a str),
    /// `GET /playlists/{id}/items?limit=50&offset=N&additional_types=track,episode&market=from_token`
    /// (episodes asked for as such, so one always comes as the documented `EpisodeObject` with its
    /// `type`, and is left out by it - qa-26c-step2a-code L1).
    PlaylistItems(&'a str, usize),
    /// `GET /albums/{id}?market=from_token` (the first 50 tracks inside).
    Album(&'a str),
    /// `GET /albums/{id}/tracks?limit=50&offset=N&market=from_token`.
    AlbumTracks(&'a str, usize),
    /// `GET /tracks/{id}?market=from_token`.
    Track(&'a str),
    /// `GET /artists/{id}`.
    Artist(&'a str),
    /// `GET /artists/{id}/albums?include_groups=album,single&limit=10&offset=N&market=from_token`
    /// (the artist option, §3.3; [ARTIST_ALBUMS_LIMIT]).
    ArtistAlbums(&'a str, usize),
    /// `GET /shows/{id}?market=from_token`.
    Show(&'a str),
    /// `GET /shows/{id}/episodes?limit=50&offset=N&market=from_token` (newest first).
    ShowEpisodes(&'a str, usize),
    /// `GET /shows/{id}/episodes?limit=1&offset=0&market=from_token`: the newest episode and the
    /// paging object's `total` only - the sweep's show gate (`music_sweep::show_mark`).
    ShowNewest(&'a str),
    /// `GET /episodes/{id}?market=from_token`.
    Episode(&'a str),
}

impl Accounts {
    /// The URL of a listing read: the API base, the path from the canonical id, the query by
    /// `form_urlencoded`.
    pub fn read_url(&self, read: Read<'_>) -> String {
        let limit = PAGE_LIMIT.to_string();
        let (path, query): (String, Vec<(&str, String)>) = match read {
            Read::Playlist(id) => (
                format!("/v1/playlists/{id}"),
                vec![(
                    "fields",
                    "name,snapshot_id,images,owner(id,display_name)".to_string(),
                )],
            ),
            Read::PlaylistItems(id, offset) => (
                format!("/v1/playlists/{id}/items"),
                vec![
                    ("limit", limit),
                    ("offset", offset.to_string()),
                    ("additional_types", "track,episode".to_string()),
                    ("market", "from_token".to_string()),
                ],
            ),
            Read::Album(id) => (
                format!("/v1/albums/{id}"),
                vec![("market", "from_token".to_string())],
            ),
            Read::AlbumTracks(id, offset) => (
                format!("/v1/albums/{id}/tracks"),
                vec![
                    ("limit", limit),
                    ("offset", offset.to_string()),
                    ("market", "from_token".to_string()),
                ],
            ),
            Read::Track(id) => (
                format!("/v1/tracks/{id}"),
                vec![("market", "from_token".to_string())],
            ),
            Read::Artist(id) => (format!("/v1/artists/{id}"), Vec::new()),
            Read::ArtistAlbums(id, offset) => (
                format!("/v1/artists/{id}/albums"),
                vec![
                    ("include_groups", "album,single".to_string()),
                    ("limit", ARTIST_ALBUMS_LIMIT.to_string()),
                    ("offset", offset.to_string()),
                    ("market", "from_token".to_string()),
                ],
            ),
            Read::Show(id) => (
                format!("/v1/shows/{id}"),
                vec![("market", "from_token".to_string())],
            ),
            Read::ShowEpisodes(id, offset) => (
                format!("/v1/shows/{id}/episodes"),
                vec![
                    ("limit", limit),
                    ("offset", offset.to_string()),
                    ("market", "from_token".to_string()),
                ],
            ),
            Read::ShowNewest(id) => (
                format!("/v1/shows/{id}/episodes"),
                vec![
                    ("limit", "1".to_string()),
                    ("offset", "0".to_string()),
                    ("market", "from_token".to_string()),
                ],
            ),
            Read::Episode(id) => (
                format!("/v1/episodes/{id}"),
                vec![("market", "from_token".to_string())],
            ),
        };
        let mut url = self.api_url(&path);
        if !query.is_empty() {
            let mut serializer = form_urlencoded::Serializer::new(String::new());
            for (key, value) in &query {
                serializer.append_pair(key, value);
            }
            url.push('?');
            url.push_str(&serializer.finish());
        }
        url
    }
}

/// The accounts a listing may be read with (§3.3): those without `needs_login`, the listing's last
/// reader first (an id no row has any more - a removed account - counts for nothing), then by id;
/// and how many accounts there are at all (none = `no_account`, all needing a login =
/// `reauthorize`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Readers {
    pub ids: Vec<i64>,
    pub accounts: usize,
}

impl Readers {
    /// Why no account can read, before any request: `None` when one can.
    pub fn refusal(&self) -> Option<SpotifyError> {
        match (self.ids.is_empty(), self.accounts) {
            (false, _) => None,
            (true, 0) => Some(SpotifyError::NoAccount),
            (true, _) => Some(SpotifyError::Reauthorize),
        }
    }
}

pub async fn readers(db: &sqlx::SqlitePool, last: Option<i64>) -> Result<Readers, sqlx::Error> {
    let rows: Vec<(i64, i64)> =
        sqlx::query_as("SELECT id, needs_login FROM music_spotify_accounts ORDER BY id")
            .fetch_all(db)
            .await?;
    let mut ids: Vec<i64> = rows
        .iter()
        .filter(|(_, needs_login)| *needs_login == 0)
        .map(|(id, _)| *id)
        .collect();
    if let Some(at) = last.and_then(|last| ids.iter().position(|id| *id == last)) {
        let first = ids.remove(at);
        ids.insert(0, first);
    }
    Ok(Readers {
        ids,
        accounts: rows.len(),
    })
}

/// Whether a reader's failure lets the next reader try (§3.3): the account can't see it (403,
/// 404: another account may own it), or the account itself can't be used now (its login ended,
/// another key, removed, a 401 after the refresh). Anything else - the app refused, the quota, a
/// rate limit, the network, Spotify's 5xx, a bad answer - is the same for every account and ends
/// the read.
pub fn try_next_reader(err: &SpotifyError) -> bool {
    matches!(
        err,
        SpotifyError::NotFound
            | SpotifyError::Status(401 | 403)
            | SpotifyError::Reauthorize
            | SpotifyError::Key
            | SpotifyError::NoAccount
    )
}

/// Every reader failed (§3.3): the error to report, and whether **every** reader answered 403 or
/// 404 - no account may see it (qa-26c-step2a-code M1: only then is a playlist a context; any
/// other failure fails the read and keeps the last good list).
#[derive(Debug, Clone, PartialEq)]
pub struct NoReader {
    pub err: SpotifyError,
    pub refused: bool,
}

/// The readers' turns at one read (§3.3), shared by the sweep's check and the add form's check so
/// both agree on what a refusal is (qa-26c-step2a-code's note for 2b): a reader whose failure says
/// it may not see it (403, 404) or can't be used now ([try_next_reader]) lets the next try; any
/// other failure ends the read **as that failure, never a refusal**. Every reader failing:
/// `refused` only when each answered 403/404; the error is the first account-level one (the login,
/// the key: what the parent can mend), else 404 if one said so, else 403.
#[derive(Debug, Default)]
pub struct ReaderTurns {
    unusable: Option<SpotifyError>,
    tried: usize,
    refusals: usize,
    not_found: bool,
}

impl ReaderTurns {
    /// One reader's failure: `Some` ends the read with it, `None` lets the next reader try.
    pub fn failed(&mut self, err: SpotifyError) -> Option<NoReader> {
        self.tried += 1;
        match err {
            SpotifyError::NotFound | SpotifyError::Status(403) => {
                self.refusals += 1;
                self.not_found |= err == SpotifyError::NotFound;
                None
            }
            err if try_next_reader(&err) => {
                self.unusable.get_or_insert(err);
                None
            }
            err => Some(NoReader {
                err,
                refused: false,
            }),
        }
    }

    /// Every reader failed (or there was none).
    pub fn end(self) -> NoReader {
        let refused = self.refusals > 0 && self.refusals == self.tried;
        let err = match self.unusable {
            Some(err) => err,
            None if self.not_found => SpotifyError::NotFound,
            None if self.refusals > 0 => SpotifyError::Status(403),
            None => SpotifyError::NoAccount,
        };
        NoReader { err, refused }
    }
}

/// How long the add form's check may take in all (§3.2: like NRK's add check and Storytel's
/// probe), the gate's wait included - a feed fetch can hold the one gate for 120 s.
pub const ADD_BUDGET: Duration = Duration::from_secs(45);

/// What the add form's check found (§3.2): the metadata (the entry's name, its picture) and, for a
/// playlist, whether no logged-in account may list its tracks (the phones then play it as a
/// Spotify context; the sweep's first check decides for good).
#[derive(Debug, Clone, PartialEq)]
pub struct AddCheck {
    pub meta: Meta,
    pub context: bool,
}

/// The add form's check (§3.2): the kind's metadata read with the first reader that answers
/// (§3.3's turns, [ReaderTurns]), and for a playlist its first items page with each reader in turn
/// until one may list it - none may: [AddCheck::context]. A failure of the items read other than
/// every reader's refusal adds the playlist anyway (the sweep decides its mode). No request without
/// a usable account or inside the quota pause. The caller bounds it ([ADD_BUDGET]).
pub async fn add_check(state: &AppState, reference: &SpotifyRef) -> Result<AddCheck, NoReader> {
    let refuse = |err: SpotifyError| NoReader {
        err,
        refused: false,
    };
    let readers = readers(&state.db, None)
        .await
        .map_err(|err| refuse(err.into()))?;
    if let Some(err) = readers.refusal() {
        return Err(refuse(err));
    }
    if state.spotify.quota_until().is_some() {
        return Err(refuse(SpotifyError::Quota));
    }
    let accounts = &state.spotify;
    let id = reference.id.as_str();
    let read = match reference.kind {
        LinkKind::Playlist => Read::Playlist(id),
        LinkKind::Album => Read::Album(id),
        LinkKind::Track => Read::Track(id),
        LinkKind::Artist => Read::Artist(id),
        LinkKind::Show => Read::Show(id),
        LinkKind::Episode => Read::Episode(id),
    };
    let first = |url: String| {
        let ids = readers.ids.clone();
        async move {
            let mut turns = ReaderTurns::default();
            for account in ids {
                match accounts.api_get(state, account, &url, Kind::Page).await {
                    Ok(response) => return Ok(response.body),
                    Err(err) => {
                        if let Some(end) = turns.failed(err) {
                            return Err(end);
                        }
                    }
                }
            }
            Err(turns.end())
        }
    };
    let body = first(accounts.read_url(read)).await?;
    let meta = match reference.kind {
        LinkKind::Playlist => parse_playlist_meta(&body),
        LinkKind::Artist | LinkKind::Show => parse_named(&body),
        LinkKind::Album => parse_album(&body).map(|(meta, _)| meta),
        LinkKind::Track => parse_track(&body).map(|(meta, _)| meta),
        LinkKind::Episode => parse_episode(&body).map(|(meta, _)| meta),
    }
    .ok_or(refuse(SpotifyError::BadAnswer))?;
    let context = reference.kind == LinkKind::Playlist
        && matches!(
            first(accounts.read_url(Read::PlaylistItems(id, 0))).await,
            Err(NoReader { refused: true, .. })
        );
    Ok(AddCheck { meta, context })
}

/// The add form's sentence for a failed check (§3.2): every account refused (404/403) - Spotify
/// won't let this app open it; the network, a timeout, Spotify's 5xx or a rate limit - try again;
/// otherwise the error's own line.
pub fn add_refusal(none: &NoReader) -> String {
    match &none.err {
        SpotifyError::NotFound | SpotifyError::Status(403) => {
            "Spotify doesn't let this app open it - Spotify's own playlists (made by Spotify) \
             aren't available to developer apps, or the link is private or deleted."
                .to_string()
        }
        SpotifyError::Fetch(_) | SpotifyError::RateLimited(_) => {
            "Spotify couldn't be reached - try again.".to_string()
        }
        SpotifyError::Status(status) if *status >= 500 => {
            "Spotify couldn't be reached - try again.".to_string()
        }
        SpotifyError::Quota => {
            "Spotify's limit for this app was reached - try again in an hour.".to_string()
        }
        // No status codes or parser words on the form (qa-26c-step2b-code L4).
        SpotifyError::Status(_) | SpotifyError::TooBig | SpotifyError::BadAnswer => {
            "Spotify couldn't open it just now - try again.".to_string()
        }
        SpotifyError::NoAccount => "Log in with a Spotify account first.".to_string(),
        SpotifyError::Reauthorize => {
            "The Spotify logins need renewing - log in again first.".to_string()
        }
        other => other.message(),
    }
}

/// One listed track or episode (§3.4): the id (the item's key), the title (`"<name> — <artist>,
/// <artist>"` for a track, vibb's row title; the name for an episode - not cut yet), the duration
/// and the picture (a track's album image, an episode's own).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Listed {
    pub id: String,
    pub title: String,
    pub duration_ms: Option<i64>,
    pub art: Option<String>,
    /// An album track's `(disc_number, track_number)`: an album lists in that order.
    pub disc_track: Option<(i64, i64)>,
}

/// One page of tracks or episodes as read.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Page {
    pub listed: Vec<Listed>,
    /// Left out (§3.4): not playable here, local files, podcast episodes in a playlist, removed
    /// tracks (`item: null`), entries without an id.
    pub skipped: usize,
    /// Spotify has a next page (`next` is a URL; the next page's URL is built from the offset,
    /// never taken from the answer).
    pub next: bool,
    /// The paging object's `total`, when it says.
    pub total: Option<usize>,
}

/// What a metadata read says (§3.4's listing row, §3.2's entry name).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Meta {
    /// The entry's name (§3.2): a playlist's, show's, artist's or episode's name, an album's
    /// `"<album> — <first artist>"`, a track's `"<name> — <artists>"`.
    pub name: Option<String>,
    /// The picture: of at least 300 px the narrowest, else the widest ([pick_image]).
    pub image: Option<String>,
    /// A playlist's `snapshot_id`.
    pub snapshot: Option<String>,
}

/// The longest `snapshot_id` kept.
const MAX_SNAPSHOT_CHARS: usize = 200;

/// The picture of an `images` array (§3.4): the narrowest of at least 300 px, else the widest
/// (an image without a width counts as 0); only an http(s) URL without userinfo.
pub fn pick_image(images: Option<&Value>) -> Option<String> {
    let images: Vec<(i64, &str)> = images?
        .as_array()?
        .iter()
        .filter_map(|image| {
            let url = image.get("url")?.as_str()?;
            crate::music_net::media_url_ok(url)
                .then(|| (image.get("width").and_then(Value::as_i64).unwrap_or(0), url))
        })
        .collect();
    let big = images
        .iter()
        .filter(|(width, _)| *width >= 300)
        .min_by_key(|(width, _)| *width);
    big.or_else(|| images.iter().max_by_key(|(width, _)| *width))
        .map(|(_, url)| url.to_string())
}

/// A row title (vibb's): `"<name> — <artist>, <artist>"`, or the name alone without artists.
/// The phone builds the same from the SDK's state to match a relinked track (§5.4).
pub fn track_title(name: &str, artists: &[String]) -> String {
    if artists.is_empty() {
        name.to_string()
    } else {
        format!("{name} — {}", artists.join(", "))
    }
}

fn text(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn artists_of(value: &Value) -> Vec<String> {
    value
        .get("artists")
        .and_then(Value::as_array)
        .map(|artists| artists.iter().filter_map(|a| text(a, "name")).collect())
        .unwrap_or_default()
}

fn duration(value: &Value) -> Option<i64> {
    value
        .get("duration_ms")
        .and_then(Value::as_i64)
        .filter(|ms| *ms > 0)
}

/// A track object as one listed track, or `None` = left out (§3.4): not a track, a local file, no
/// usable id, `is_playable: false`. `art` is the track's album image (`album` absent in an
/// album's own track list).
fn track(value: &Value) -> Option<Listed> {
    if value.get("type").and_then(Value::as_str) != Some("track")
        // An episode in a track's shape (the older answer without `additional_types=episode`:
        // `"episode": true`), or anything whose URI isn't a track's (qa-26c-step2a-code L1).
        || value.get("episode").and_then(Value::as_bool) == Some(true)
        || value
            .get("uri")
            .and_then(Value::as_str)
            .is_some_and(|uri| !uri.starts_with("spotify:track:"))
        || value.get("is_local").and_then(Value::as_bool) == Some(true)
        || value.get("is_playable").and_then(Value::as_bool) == Some(false)
    {
        return None;
    }
    let id = value
        .get("id")
        .and_then(Value::as_str)
        .filter(|id| id_ok(id))?;
    let name = text(value, "name").unwrap_or_default();
    let number = |key: &str| value.get(key).and_then(Value::as_i64);
    Some(Listed {
        id: id.to_string(),
        title: track_title(&name, &artists_of(value)),
        duration_ms: duration(value),
        art: pick_image(value.get("album").and_then(|album| album.get("images"))),
        disc_track: number("disc_number").zip(number("track_number")),
    })
}

/// An episode object as one listed episode, or `None` = left out: not an episode, no usable id,
/// `is_playable: false`. Its own picture.
fn episode(value: &Value) -> Option<Listed> {
    if value
        .get("type")
        .and_then(Value::as_str)
        .is_some_and(|kind| kind != "episode")
        || value.get("is_playable").and_then(Value::as_bool) == Some(false)
    {
        return None;
    }
    let id = value
        .get("id")
        .and_then(Value::as_str)
        .filter(|id| id_ok(id))?;
    Some(Listed {
        id: id.to_string(),
        title: text(value, "name").unwrap_or_default(),
        duration_ms: duration(value),
        art: pick_image(value.get("images")),
        disc_track: None,
    })
}

/// A paging object's `items`, each through `one` (`None` = left out), with `next` and `total`.
fn paging(value: &Value, one: impl Fn(&Value) -> Option<Listed>) -> Option<Page> {
    let items = value.get("items")?.as_array()?;
    let mut page = Page {
        next: value
            .get("next")
            .and_then(Value::as_str)
            .is_some_and(|next| !next.is_empty()),
        total: value
            .get("total")
            .and_then(Value::as_u64)
            .map(|total| total as usize),
        ..Page::default()
    };
    for item in items {
        match one(item) {
            Some(listed) => page.listed.push(listed),
            None => page.skipped += 1,
        }
    }
    Some(page)
}

fn object(body: &[u8]) -> Option<Value> {
    serde_json::from_slice::<Value>(body)
        .ok()
        .filter(Value::is_object)
}

/// `GET /playlists/{id}?fields=...`: the name, the snapshot and the picture.
pub fn parse_playlist_meta(body: &[u8]) -> Option<Meta> {
    let value = object(body)?;
    Some(Meta {
        name: text(&value, "name"),
        image: pick_image(value.get("images")),
        snapshot: text(&value, "snapshot_id").filter(|s| s.chars().count() <= MAX_SNAPSHOT_CHARS),
    })
}

/// A page of `GET /playlists/{id}/items` (§3.4): one track per `items[].item` (the February 2026
/// name; `track`, the older one, is read too) whose `type` is `track`, that isn't a local file and
/// whose `is_playable` isn't false; everything else - podcast episodes, local files, removed
/// tracks - counted as left out.
pub fn parse_playlist_items(body: &[u8]) -> Option<Page> {
    let value = object(body)?;
    paging(&value, |entry| {
        if entry.get("is_local").and_then(Value::as_bool) == Some(true) {
            return None;
        }
        entry
            .get("item")
            .or_else(|| entry.get("track"))
            .filter(|item| item.is_object())
            .and_then(track)
    })
}

/// `GET /albums/{id}`: `"<album> — <first artist>"`, the cover, and the first page of its tracks
/// (an album's own track list has no album, so no per-track picture: the entry's cover shows).
pub fn parse_album(body: &[u8]) -> Option<(Meta, Page)> {
    let value = object(body)?;
    let name = text(&value, "name")?;
    let page = paging(value.get("tracks")?, track)?;
    let meta = Meta {
        name: Some(match artists_of(&value).first() {
            Some(artist) => format!("{name} — {artist}"),
            None => name,
        }),
        image: pick_image(value.get("images")),
        snapshot: None,
    };
    Some((meta, page))
}

/// A page of `GET /albums/{id}/tracks`.
pub fn parse_album_tracks(body: &[u8]) -> Option<Page> {
    paging(&object(body)?, track)
}

/// `GET /tracks/{id}`: the track as the entry's one item; the name is its row title, the picture
/// its album's.
pub fn parse_track(body: &[u8]) -> Option<(Meta, Page)> {
    let value = object(body)?;
    let name = text(&value, "name")?;
    let meta = Meta {
        name: Some(track_title(&name, &artists_of(&value))),
        image: pick_image(value.get("album").and_then(|album| album.get("images"))),
        snapshot: None,
    };
    let mut page = Page::default();
    match track(&value) {
        Some(listed) => page.listed.push(listed),
        None => page.skipped = 1,
    }
    Some((meta, page))
}

/// `GET /artists/{id}` (and the show's metadata, `GET /shows/{id}`): the name and the picture.
pub fn parse_named(body: &[u8]) -> Option<Meta> {
    let value = object(body)?;
    Some(Meta {
        name: Some(text(&value, "name")?),
        image: pick_image(value.get("images")),
        snapshot: None,
    })
}

/// A page of `GET /shows/{id}/episodes` (newest first).
pub fn parse_show_episodes(body: &[u8]) -> Option<Page> {
    paging(&object(body)?, |item| {
        item.is_object().then(|| episode(item)).flatten()
    })
}

/// A show's gate mark (the sweep stores it as the listing's `spotify_snapshot`): the paging
/// object's `total` and the first entry's id (`-` for a `null` entry; playable or not) of the first
/// page of `GET /shows/{id}/episodes` (newest first), as `total:id` - the same from the gate's
/// one-episode read ([Read::ShowNewest]) and from the full read's first page. `None` - no gate, the
/// next check reads in full - for an answer with no entry, no paging object or no `total` (without
/// it a `null` newest entry would stay `-` as episodes come and go, qa-srv-sweep-resume-code L10).
pub fn show_mark(body: &[u8]) -> Option<String> {
    let value = object(body)?;
    let first = value.get("items")?.as_array()?.first()?;
    let id = first.get("id").and_then(Value::as_str).unwrap_or("-");
    let total = value.get("total").and_then(Value::as_u64)?;
    Some(format!("{total}:{id}"))
}

/// `GET /episodes/{id}`: the episode as the entry's one item; its picture, else its show's.
pub fn parse_episode(body: &[u8]) -> Option<(Meta, Page)> {
    let value = object(body)?;
    let name = text(&value, "name")?;
    let meta = Meta {
        name: Some(name),
        image: pick_image(value.get("images"))
            .or_else(|| pick_image(value.get("show").and_then(|show| show.get("images")))),
        snapshot: None,
    };
    let mut page = Page::default();
    match episode(&value) {
        Some(listed) => page.listed.push(listed),
        None => page.skipped = 1,
    }
    Some((meta, page))
}

/// One of an artist's albums (the option, §3.3): its id and release date as Spotify writes it
/// (`2024`, `2024-05`, `2024-05-17`: compared as text, newest first).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AlbumRef {
    pub id: String,
    pub released: String,
}

/// A page of `GET /artists/{id}/albums`: the albums with a usable id, and whether there are more.
pub fn parse_artist_albums(body: &[u8]) -> Option<(Vec<AlbumRef>, bool)> {
    let value = object(body)?;
    let items = value.get("items")?.as_array()?;
    let albums = items
        .iter()
        .filter_map(|album| {
            let id = album.get("id")?.as_str().filter(|id| id_ok(id))?;
            Some(AlbumRef {
                id: id.to_string(),
                released: text(album, "release_date").unwrap_or_default(),
            })
        })
        .collect();
    let next = value
        .get("next")
        .and_then(Value::as_str)
        .is_some_and(|next| !next.is_empty());
    Some((albums, next))
}

/// Why a short link didn't resolve (§3.1; 2b's add form).
#[derive(Clone, Debug, PartialEq)]
pub enum ShortLinkError {
    /// No redirect, a redirect off Spotify's three hosts, or more than [SHORT_LINK_HOPS].
    Dead,
    /// It led to a link that can't be added (an audiobook, Liked Songs, a profile, junk).
    Link(LinkError),
    Unreachable(FetchError),
}

impl ShortLinkError {
    /// The add form's sentence (2b).
    pub fn message(&self) -> String {
        match self {
            ShortLinkError::Dead => "That Spotify link didn't lead anywhere.".to_string(),
            ShortLinkError::Link(err) => err.message().to_string(),
            ShortLinkError::Unreachable(_) => {
                "Spotify couldn't be reached - try again.".to_string()
            }
        }
    }
}

/// A `spotify.link`/`spotify.app.link` short link resolved like vibb's `urlopen(target).url`
/// (`spotify.py:42-46`), but **one hop at a time** (§3.1): a GET through `source` (the gate),
/// [Reach::Public], `follow: false`; each 3xx's `Location` (relative ones resolved) must be an
/// https URL on `spotify.link`, `spotify.app.link` or `open.spotify.com`, at most
/// [SHORT_LINK_HOPS] requests; the first `open.spotify.com` URL is parsed (not asked). Only the
/// add form uses it (2b); never at play time.
pub async fn resolve_short_link(
    source: &dyn Source,
    short: &str,
) -> Result<SpotifyRef, ShortLinkError> {
    let mut url = reqwest::Url::parse(short).map_err(|_| ShortLinkError::Dead)?;
    for _ in 0..SHORT_LINK_HOPS {
        let host = url.host_str().unwrap_or("").to_ascii_lowercase();
        if url.scheme() != "https" || !matches!(host.as_str(), "spotify.link" | "spotify.app.link")
        {
            return Err(ShortLinkError::Dead);
        }
        let mut request = SourceRequest::new(url.as_str(), Kind::Page, Reach::Public);
        request.follow = false;
        let answer = source
            .get(request)
            .await
            .map_err(ShortLinkError::Unreachable)?;
        let location = match answer.location {
            Some(location)
                if (300..400).contains(&answer.status)
                    && location.chars().count() <= MAX_LINK_CHARS =>
            {
                location
            }
            _ => return Err(ShortLinkError::Dead),
        };
        let next = url.join(&location).map_err(|_| ShortLinkError::Dead)?;
        let next_host = next.host_str().unwrap_or("").to_ascii_lowercase();
        if next.scheme() != "https"
            || !next.username().is_empty()
            || next.password().is_some()
            || next.port().is_some()
        {
            return Err(ShortLinkError::Dead);
        }
        match next_host.as_str() {
            "open.spotify.com" => {
                return match parse_link(next.as_str()) {
                    Ok(Link::Ref(reference)) => Ok(reference),
                    Ok(Link::Short(_)) => Err(ShortLinkError::Dead),
                    Err(err) => Err(ShortLinkError::Link(err)),
                };
            }
            "spotify.link" | "spotify.app.link" => url = next,
            _ => return Err(ShortLinkError::Dead),
        }
    }
    Err(ShortLinkError::Dead)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::tests::TestApp;

    pub(crate) const CLIENT: &str = "0123456789abcdef0123456789abcdef";
    pub(crate) const TOKEN_URL: &str = "https://accounts.spotify.com/api/token";
    pub(crate) const ME_URL: &str = "https://api.spotify.com/v1/me";

    pub(crate) fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!("testdata/spotify/{name}")).unwrap()
    }

    fn reference(kind: LinkKind, id: &str) -> Result<Link, LinkError> {
        Ok(Link::Ref(SpotifyRef {
            kind,
            id: id.to_string(),
        }))
    }

    #[test]
    fn links_follow_vibbs_grammar() {
        let id = "37i9dQZF1DXcBWIGoYBM5M";
        for (text, kind) in [
            (format!("spotify:playlist:{id}"), LinkKind::Playlist),
            (
                format!("https://open.spotify.com/playlist/{id}"),
                LinkKind::Playlist,
            ),
            (
                format!("https://open.spotify.com/playlist/{id}?si=abc123&pt=x"),
                LinkKind::Playlist,
            ),
            (
                format!("  https://open.spotify.com/intl-de/album/{id}#top  "),
                LinkKind::Album,
            ),
            (
                format!("https://open.spotify.com/intl-pt-br/track/{id}/"),
                LinkKind::Track,
            ),
            (
                format!("http://open.spotify.com/artist/{id}"),
                LinkKind::Artist,
            ),
            (
                format!("https://OPEN.spotify.com/show/{id}"),
                LinkKind::Show,
            ),
            (format!("spotify:episode:{id}"), LinkKind::Episode),
        ] {
            assert_eq!(parse_link(&text), reference(kind, id), "{text}");
        }
        // The canonical target and its key (vibb's bm_path hash of the same URI).
        let Ok(Link::Ref(playlist)) =
            parse_link(&format!("https://open.spotify.com/playlist/{id}"))
        else {
            panic!("a playlist")
        };
        assert_eq!(playlist.target(), format!("spotify:playlist:{id}"));
        assert_eq!(playlist.key(), crate::music::state_key(&playlist.target()));
        assert_eq!(playlist.key().len(), 12);
        assert_eq!(
            playlist.open_url(),
            format!("https://open.spotify.com/playlist/{id}")
        );
        assert_eq!(parse_target(&playlist.target()), Some(playlist.clone()));
        assert_eq!(
            parse_target(&format!("https://open.spotify.com/playlist/{id}")),
            None
        );
        // Short links: recognised, resolved later (step 2a).
        assert_eq!(
            parse_link("https://spotify.link/AbC123xyz"),
            Ok(Link::Short("https://spotify.link/AbC123xyz".to_string()))
        );
        assert_eq!(
            parse_link("https://spotify.app.link/AbC123xyz?_p=x"),
            Ok(Link::Short(
                "https://spotify.app.link/AbC123xyz".to_string()
            ))
        );
        assert_eq!(
            parse_link("http://spotify.link/AbC123xyz"),
            Err(LinkError::NotALink)
        );
        // Refused kinds, each with its sentence.
        for text in [
            format!("spotify:audiobook:{id}"),
            format!("https://open.spotify.com/audiobook/{id}"),
            format!("https://open.spotify.com/chapter/{id}"),
        ] {
            assert_eq!(parse_link(&text), Err(LinkError::Audiobook), "{text}");
        }
        assert_eq!(
            LinkError::Audiobook.message(),
            "Spotify audiobooks can't be played here."
        );
        for text in [
            "spotify:user:fakekid:collection",
            "spotify:user:fakekid:collection:tracks",
            "spotify:user:fakekid",
            "https://open.spotify.com/user/fakekid",
            "https://open.spotify.com/collection/tracks",
            "spotify:collection:tracks",
        ] {
            assert_eq!(parse_link(text), Err(LinkError::Collection), "{text}");
        }
        assert!(LinkError::Collection.message().contains("Liked Songs"));
        // Junk, bad ids, other hosts and shapes.
        for text in [
            String::new(),
            "spotify".to_string(),
            "spotify:playlist:".to_string(),
            "spotify:playlist:a-b".to_string(),
            format!("spotify:playlist:{id}:extra"),
            format!("spotify:user:x:playlist:{id}"),
            format!("spotify:genre:{id}"),
            format!("https://open.spotify.com/playlist/{id}/extra"),
            format!("https://open.spotify.com/playlist/{}", "a".repeat(65)),
            format!("https://open.spotify.com/playlist/{id}%20"),
            format!("https://evil.example/playlist/{id}"),
            format!("https://open.spotify.com.evil.example/playlist/{id}"),
            format!("https://open.spotify.com:8443/playlist/{id}"),
            format!("https://user@open.spotify.com/playlist/{id}"),
            format!("ftp://open.spotify.com/playlist/{id}"),
            format!("https://open.spotify.com/intl-/playlist/{id}"),
            "https://open.spotify.com/".to_string(),
            "https://spotify.link/".to_string(),
            "https://spotify.link/a/b".to_string(),
            "not a link".to_string(),
            format!(
                "https://open.spotify.com/playlist/{id}?{}",
                "x".repeat(2000)
            ),
        ] {
            assert_eq!(parse_link(&text), Err(LinkError::NotALink), "{text:?}");
        }
        assert!(id_ok(&"9".repeat(64)) && !id_ok(&"9".repeat(65)) && !id_ok(""));
    }

    /// RFC 7636 appendix B, the verifier's alphabet and length, the state and its cookie.
    #[test]
    fn pkce_follows_rfc_7636() {
        assert_eq!(
            s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
        let mut seen = std::collections::HashSet::new();
        for _ in 0..200 {
            let verifier = Verifier::new();
            assert_eq!(verifier.as_str().len(), VERIFIER_CHARS);
            assert!(
                verifier
                    .as_str()
                    .bytes()
                    .all(|b| VERIFIER_ALPHABET.contains(&b))
            );
            assert_eq!(verifier.challenge(), s256(verifier.as_str()));
            assert_eq!(verifier.challenge().len(), 43, "no padding");
            seen.extend(verifier.as_str().bytes());
        }
        // Every character of the alphabet turns up (the draw covers all 66).
        assert_eq!(seen.len(), 66);
        let state = new_state();
        assert_eq!(state.len(), 32);
        assert!(state.bytes().all(|b| b.is_ascii_hexdigit()));
        assert_ne!(state, new_state());
        assert_eq!(state_cookie("abc").len(), 64);
        assert_eq!(
            state_cookie("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn the_authorize_url_is_exact() {
        assert_eq!(
            authorize_url(
                ACCOUNTS_BASE,
                CLIENT,
                "https://pi.example.ts.net/music/spotify/callback",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "0123456789abcdef0123456789abcdef",
            ),
            "https://accounts.spotify.com/authorize?response_type=code\
             &client_id=0123456789abcdef0123456789abcdef\
             &scope=streaming+user-read-email+user-read-private+user-modify-playback-state\
             +playlist-read-private+playlist-read-collaborative\
             &redirect_uri=https%3A%2F%2Fpi.example.ts.net%2Fmusic%2Fspotify%2Fcallback\
             &code_challenge_method=S256&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM\
             &state=0123456789abcdef0123456789abcdef&show_dialog=true"
        );
        // QA L2: no user-read-playback-state.
        assert!(!SCOPES.contains("user-read-playback-state"));
        assert_eq!(SCOPES.split(' ').count(), 6);
    }

    #[test]
    fn token_answers_are_read() {
        let response = |status: u16, body: &[u8]| SourceResponse {
            status,
            body: body.to_vec(),
            ..Default::default()
        };
        let code = read_token_answer(&response(200, &fixture("token_code.json"))).unwrap();
        assert_eq!(code.access.secret(), "FAKE-ACCESS-1");
        assert_eq!(code.expires_in, 3600);
        assert_eq!(code.refresh.map(|t| t.0).as_deref(), Some("FAKE-REFRESH-1"));
        assert_eq!(code.scope.as_deref(), Some(SCOPES));
        // A refresh that doesn't rotate, and one that does.
        let plain = read_token_answer(&response(200, &fixture("token_refresh.json"))).unwrap();
        assert!(plain.refresh.is_none());
        let rotated =
            read_token_answer(&response(200, &fixture("token_refresh_rotated.json"))).unwrap();
        assert_eq!(
            rotated.refresh.map(|t| t.0).as_deref(),
            Some("FAKE-REFRESH-2")
        );
        assert_eq!(
            read_token_answer(&response(400, &fixture("token_invalid_grant.json"))).err(),
            Some(TokenError::InvalidGrant)
        );
        for status in [400, 401] {
            assert_eq!(
                read_token_answer(&response(status, &fixture("token_invalid_client.json"))).err(),
                Some(TokenError::InvalidClient)
            );
        }
        assert_eq!(
            read_token_answer(&response(400, br#"{"error":"invalid_request"}"#)).err(),
            Some(TokenError::Other(SpotifyError::Status(400)))
        );
        let mut limited = response(429, b"");
        limited.retry_after = Some("7".to_string());
        assert_eq!(
            read_token_answer(&limited).err(),
            Some(TokenError::Other(SpotifyError::RateLimited(Some(7))))
        );
        assert_eq!(
            read_token_answer(&response(503, b"")).err(),
            Some(TokenError::Other(SpotifyError::Status(503)))
        );
        for junk in [
            &b"<html>"[..],
            br#"{"access_token":"","expires_in":3600}"#,
            br#"{"access_token":"x"}"#,
            br#"{"access_token":"x","expires_in":0}"#,
            // Code QA L4: at most a day (an absurd value would overflow the expiry).
            br#"{"access_token":"x","expires_in":86401}"#,
            br#"{"access_token":"x","expires_in":18446744073709551615}"#,
        ] {
            assert_eq!(
                read_token_answer(&response(200, junk)).err(),
                Some(TokenError::Other(SpotifyError::BadAnswer))
            );
        }
        let day = read_token_answer(&response(
            200,
            br#"{"access_token":"x","expires_in":86400}"#,
        ))
        .unwrap();
        assert_eq!(day.expires_in, MAX_EXPIRES_IN);
        assert!(quota_exceeded(&fixture("quota.json")));
        assert!(quota_exceeded(br#"{"reason":"QUOTA_EXCEEDED"}"#));
        assert!(!quota_exceeded(&fixture("rate_limited.json")));
    }

    #[test]
    fn the_app_settings_are_checked() {
        assert!(valid_client_id(CLIENT));
        assert!(valid_client_id("ABCDEF0123456789abcdef0123456789"));
        for bad in [
            "",
            "0123",
            &format!("{CLIENT}0"),
            "0123456789abcdef0123456789abcde-",
        ] {
            assert!(!valid_client_id(bad), "{bad}");
        }
        for good in [
            LOOPBACK_REDIRECT,
            "https://pi.example.ts.net/music/spotify/callback",
            "https://pi.example.ts.net:8443/music/spotify/callback",
            "https://192.0.2.7/music/spotify/callback",
        ] {
            assert!(valid_redirect_uri(good), "{good}");
        }
        for bad in [
            "http://pi.example.ts.net/music/spotify/callback",
            "https://pi.example.ts.net/music/spotify/callback/",
            "https://pi.example.ts.net/music/spotify/callback?x=1",
            "https://pi.example.ts.net/music/spotify/callback#x",
            "https://user@pi.example.ts.net/music/spotify/callback",
            "https://pi.example.ts.net/other",
            "http://localhost:43117/callback",
            "http://127.0.0.1:43118/callback",
            "http://127.0.0.1:43117/callback/",
            "https://pi.example.ts.net/music/spotify/callback\n",
            "",
        ] {
            assert!(!valid_redirect_uri(bad), "{bad:?}");
        }
    }

    #[test]
    fn the_redirect_suggestion_is_https_only_for_an_https_request() {
        use axum::http::{HeaderMap, HeaderValue};
        let headers = |pairs: &[(&'static str, &str)]| {
            let mut map = HeaderMap::new();
            for (name, value) in pairs {
                map.insert(*name, HeaderValue::from_str(value).unwrap());
            }
            map
        };
        // Behind a trusted TLS proxy (tailscale serve).
        assert_eq!(
            suggested_redirect(
                &headers(&[
                    ("host", "pi.example.ts.net"),
                    ("x-forwarded-proto", "https")
                ]),
                true
            ),
            "https://pi.example.ts.net/music/spotify/callback"
        );
        assert_eq!(
            suggested_redirect(
                &headers(&[
                    ("host", "127.0.0.1:3100"),
                    ("x-forwarded-host", "pi.example.ts.net:443"),
                    ("x-forwarded-proto", "https"),
                ]),
                true
            ),
            "https://pi.example.ts.net/music/spotify/callback"
        );
        // An untrusted peer's forwarded headers count for nothing; plain http by name or address
        // gets the loopback form (L5).
        for (pairs, trusted) in [
            (
                vec![
                    ("host", "pi.example.ts.net"),
                    ("x-forwarded-proto", "https"),
                ],
                false,
            ),
            (vec![("host", "pi.lan:8080")], true),
            (vec![("host", "192.168.1.5:3100")], true),
            (vec![("x-forwarded-proto", "https")], true),
            (
                vec![("host", "bad host/x"), ("x-forwarded-proto", "https")],
                true,
            ),
        ] {
            assert_eq!(
                suggested_redirect(&headers(&pairs), trusted),
                LOOPBACK_REDIRECT,
                "{pairs:?}"
            );
        }
    }

    /// §9.3: the base overrides only as a loopback literal, and only then `Reach::Any`.
    #[test]
    fn base_overrides_are_loopback_only() {
        let vars = |pairs: &[(&str, &str)]| -> HashMap<String, String> {
            pairs
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect()
        };
        let plain = Config::default();
        assert_eq!(plain.accounts_base, ACCOUNTS_BASE);
        assert_eq!(plain.api_base, API_BASE);
        assert_eq!(plain.accounts_reach(), Reach::Public);
        assert_eq!(plain.api_reach(), Reach::Public);
        assert!(plain.warnings.is_empty());
        assert_eq!(plain.accounts_origin(), "https://accounts.spotify.com");
        let fake = Config::from_vars(&vars(&[
            ("SPOTIFY_ACCOUNTS_BASE", "http://127.0.0.1:8731"),
            ("SPOTIFY_API_BASE", "http://[::1]:8731/"),
        ]));
        assert_eq!(fake.accounts_base, "http://127.0.0.1:8731");
        assert_eq!(fake.api_base, "http://[::1]:8731");
        assert_eq!(fake.accounts_reach(), Reach::Any);
        assert_eq!(fake.api_reach(), Reach::Any);
        assert_eq!(fake.accounts_origin(), "http://127.0.0.1:8731");
        for bad in [
            "http://10.0.2.2:8731",
            "https://127.0.0.1:8731",
            "http://localhost:8731",
            "http://127.0.0.1",
            "http://127.0.0.1:8731/v1",
            "http://user@127.0.0.1:8731",
            "http://127.0.0.2:8731",
            "https://evil.example",
        ] {
            let config = Config::from_vars(&vars(&[
                ("SPOTIFY_ACCOUNTS_BASE", bad),
                ("SPOTIFY_API_BASE", bad),
            ]));
            assert_eq!(config.accounts_base, ACCOUNTS_BASE, "{bad}");
            assert_eq!(config.api_base, API_BASE, "{bad}");
            assert_eq!(config.accounts_reach(), Reach::Public, "{bad}");
            assert_eq!(config.api_reach(), Reach::Public, "{bad}");
            assert_eq!(config.warnings.len(), 2, "{bad}");
        }
        // The environment's client ID and redirect URI, checked like the form's.
        let env = Config::from_vars(&vars(&[
            ("SPOTIFY_CLIENT_ID", CLIENT),
            ("SPOTIFY_REDIRECT_URI", LOOPBACK_REDIRECT),
            ("INSECURE_COOKIES", "1"),
        ]));
        assert_eq!(env.env_client_id.as_deref(), Some(CLIENT));
        assert_eq!(env.env_redirect_uri.as_deref(), Some(LOOPBACK_REDIRECT));
        assert!(env.insecure_cookies);
        let junk = Config::from_vars(&vars(&[
            ("SPOTIFY_CLIENT_ID", "short"),
            ("SPOTIFY_REDIRECT_URI", "http://pi.lan/x"),
        ]));
        assert_eq!(junk.env_client_id, None);
        assert_eq!(junk.env_redirect_uri, None);
        assert_eq!(junk.warnings.len(), 2);
        assert!(!junk.insecure_cookies);
    }

    // --- the account and its tokens, over CannedFetch ---

    /// The app row (as the PWA saves it).
    pub(crate) async fn save_app(app: &TestApp, redirect: &str) {
        sqlx::query(
            "INSERT INTO music_spotify_app (id, client_id, redirect_uri, saved_at) \
             VALUES (1, ?, ?, '2026-10-13 10:00:00') ON CONFLICT (id) DO UPDATE SET \
             client_id = excluded.client_id, redirect_uri = excluded.redirect_uri",
        )
        .bind(CLIENT)
        .bind(redirect)
        .execute(&app.db)
        .await
        .unwrap();
    }

    /// An account with `refresh` sealed as the login does; its id.
    pub(crate) async fn store_account(app: &TestApp, user: &str, name: &str, refresh: &str) -> i64 {
        let key = app.state.music_key.clone().unwrap();
        let (ciphertext, nonce) = key.seal_with(&aad(&key.fingerprint, user), refresh.as_bytes());
        sqlx::query_scalar(
            "INSERT INTO music_spotify_accounts (spotify_user, display_name, ciphertext, nonce, \
             key_fingerprint, scopes, authorized_at) VALUES (?, ?, ?, ?, ?, ?, \
             '2026-10-13 10:00:00') RETURNING id",
        )
        .bind(user)
        .bind(name)
        .bind(ciphertext)
        .bind(nonce)
        .bind(&key.fingerprint)
        .bind(SCOPES)
        .fetch_one(&app.db)
        .await
        .unwrap()
    }

    /// The account's refresh token as stored, opened.
    pub(crate) async fn stored_refresh(app: &TestApp, account: i64) -> Option<String> {
        let row = sealed_row(&app.db, account).await.unwrap()?;
        let key = app.state.music_key.clone().unwrap();
        key.open_with(
            &aad(&row.key_fingerprint, &row.spotify_user),
            &row.ciphertext,
            &row.nonce,
            &row.key_fingerprint,
        )
        .map(|plain| String::from_utf8(plain).unwrap())
    }

    fn token_calls(app: &TestApp) -> usize {
        app.fetch
            .calls
            .lock()
            .unwrap()
            .iter()
            .filter(|url| url.as_str() == TOKEN_URL)
            .count()
    }

    type Outcome = (Option<String>, Option<String>, i64);

    async fn outcome(app: &TestApp, account: i64) -> Outcome {
        sqlx::query_as(
            "SELECT refresh_ok_at, refresh_error, needs_login FROM music_spotify_accounts \
             WHERE id = ?",
        )
        .bind(account)
        .fetch_one(&app.db)
        .await
        .unwrap()
    }

    #[tokio::test]
    async fn a_refresh_is_kept_and_its_request_is_exact() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        let spotify = &app.state.spotify;
        let minted = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(minted.token.secret(), "FAKE-ACCESS-2");
        assert_eq!((minted.account, minted.generation), (account, 1));
        assert_eq!(minted.left, Duration::from_secs(3540), "expires_in - 60 s");
        let requests = app.fetch.requests.lock().unwrap().clone();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].method, "POST");
        assert_eq!(requests[0].url, TOKEN_URL);
        assert_eq!(
            requests[0].header("content-type"),
            Some("application/x-www-form-urlencoded")
        );
        assert_eq!(
            String::from_utf8(requests[0].body.clone().unwrap()).unwrap(),
            format!("grant_type=refresh_token&refresh_token=FAKE-REFRESH-1&client_id={CLIENT}")
        );
        assert_eq!(app.fetch.reaches.lock().unwrap()[0].1, Reach::Public);
        let (ok_at, error, needs_login) = outcome(&app, account).await;
        assert!(ok_at.is_some());
        assert_eq!((error, needs_login), (None, 0));
        // Kept: no request while it has the margin asked for; past it, a refresh.
        spotify
            .access_token(&app.state, account, Duration::from_secs(600))
            .await
            .unwrap();
        assert_eq!(token_calls(&app), 1);
        spotify.advance(Duration::from_secs(3540 - 599));
        spotify
            .access_token(&app.state, account, Duration::from_secs(600))
            .await
            .unwrap();
        assert_eq!(token_calls(&app), 2);
        // A new login of the account (generation + 1) never uses the old token.
        sqlx::query("UPDATE music_spotify_accounts SET generation = 2 WHERE id = ?")
            .bind(account)
            .execute(&app.db)
            .await
            .unwrap();
        let minted = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(minted.generation, 2);
        assert_eq!(token_calls(&app), 3);
        // No app: off, nothing asked.
        sqlx::query("DELETE FROM music_spotify_app")
            .execute(&app.db)
            .await
            .unwrap();
        spotify.forget(account);
        app.fetch.clear_calls();
        assert_eq!(
            spotify
                .access_token(&app.state, account, READ_MIN_LEFT)
                .await
                .err(),
            Some(SpotifyError::Off)
        );
        assert_eq!(app.fetch.count(), 0);
        // An unknown account.
        assert_eq!(
            spotify
                .access_token(&app.state, 999, READ_MIN_LEFT)
                .await
                .err(),
            Some(SpotifyError::NoAccount)
        );
    }

    #[tokio::test]
    async fn a_rotated_refresh_token_is_written_before_use() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh_rotated.json"));
        let spotify = &app.state.spotify;
        let minted = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(minted.token.secret(), "FAKE-ACCESS-3");
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-2")
        );
        // The next refresh sends the rotated one; one that doesn't rotate keeps it.
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        app.fetch.clear_calls();
        spotify
            .access_token(&app.state, account, Duration::MAX)
            .await
            .unwrap();
        let body = app.fetch.requests.lock().unwrap()[0].body.clone().unwrap();
        assert!(
            String::from_utf8(body)
                .unwrap()
                .contains("refresh_token=FAKE-REFRESH-2")
        );
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-2")
        );

        // A write that fails keeps the new token in memory: the next refresh uses it and
        // writes it then.
        sqlx::query(
            "CREATE TRIGGER no_token_writes BEFORE UPDATE OF ciphertext ON music_spotify_accounts \
             BEGIN SELECT RAISE(ABORT, 'read-only'); END",
        )
        .execute(&app.db)
        .await
        .unwrap();
        app.fetch.answer(
            TOKEN_URL,
            200,
            fixture("token_refresh_rotated.json").replace_token(),
        );
        let minted = spotify
            .access_token(&app.state, account, Duration::MAX)
            .await
            .unwrap();
        assert_eq!(minted.token.secret(), "FAKE-ACCESS-3");
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-2"),
            "the write failed"
        );
        sqlx::query("DROP TRIGGER no_token_writes")
            .execute(&app.db)
            .await
            .unwrap();
        // Code QA L2: the next call writes it, the cached path included - no token request.
        app.fetch.clear_calls();
        let cached = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(cached.token.secret(), "FAKE-ACCESS-3");
        assert_eq!(app.fetch.count(), 0);
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-3"),
            "written by a cached call"
        );
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        app.fetch.clear_calls();
        spotify
            .access_token(&app.state, account, Duration::MAX)
            .await
            .unwrap();
        let body = app.fetch.requests.lock().unwrap()[0].body.clone().unwrap();
        assert!(
            String::from_utf8(body)
                .unwrap()
                .contains("refresh_token=FAKE-REFRESH-3"),
            "the written token is the one used next"
        );
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-3")
        );
    }

    /// qa-26c-step2b-code M1: a caller dropped while its refresh is under way (the add form's
    /// budget, a phone's request that went away) cancels nothing - Spotify's rotated refresh
    /// token is still written, the access token kept, and the next call needs no request; and a
    /// rotated token whose write failed (memory its only copy) survives a dropped caller too.
    #[tokio::test]
    async fn a_dropped_caller_never_loses_a_rotated_token() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let spotify = &app.state.spotify;
        let stored_soon = |want: &'static str| {
            let app = &app;
            async move {
                for _ in 0..500 {
                    if stored_refresh(app, account).await.as_deref() == Some(want) {
                        return true;
                    }
                    tokio::time::sleep(Duration::from_millis(5)).await;
                }
                false
            }
        };
        // The refresh is held at the gate (Spotify's answer pending) when the caller gives up.
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh_rotated.json"));
        app.fetch.hold();
        let dropped = tokio::time::timeout(
            Duration::from_millis(30),
            spotify.access_token(&app.state, account, READ_MIN_LEFT),
        )
        .await;
        assert!(dropped.is_err(), "the caller gave up");
        app.fetch.release();
        assert!(
            stored_soon("FAKE-REFRESH-2").await,
            "the rotated token is written"
        );
        app.fetch.answer(TOKEN_URL, 500, "");
        app.fetch.clear_calls();
        let cached = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(cached.token.secret(), "FAKE-ACCESS-3", "kept by the task");
        assert_eq!(app.fetch.count(), 0);

        // A rotated token whose write fails stays in memory; a caller dropped at the next call
        // (while the write is retried) doesn't lose it: the call after writes it.
        sqlx::query(
            "CREATE TRIGGER no_token_writes BEFORE UPDATE OF ciphertext ON music_spotify_accounts \
             BEGIN SELECT RAISE(ABORT, 'read-only'); END",
        )
        .execute(&app.db)
        .await
        .unwrap();
        app.fetch.answer(
            TOKEN_URL,
            200,
            fixture("token_refresh_rotated.json").replace_token(),
        );
        spotify
            .access_token(&app.state, account, Duration::MAX)
            .await
            .unwrap();
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-2"),
            "the write failed: FAKE-REFRESH-3 is in memory only"
        );
        // Dropped while the write is retried (100 + 200 ms apart).
        let dropped = tokio::time::timeout(
            Duration::from_millis(20),
            spotify.access_token(&app.state, account, READ_MIN_LEFT),
        )
        .await;
        assert!(dropped.is_err());
        sqlx::query("DROP TRIGGER no_token_writes")
            .execute(&app.db)
            .await
            .unwrap();
        app.fetch.clear_calls();
        let cached = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(cached.token.secret(), "FAKE-ACCESS-3");
        assert_eq!(app.fetch.count(), 0);
        assert!(
            stored_soon("FAKE-REFRESH-3").await,
            "the in-memory copy survived the dropped caller and is written"
        );
    }

    /// `FAKE-REFRESH-2` -> `FAKE-REFRESH-3` in a fixture.
    trait ReplaceToken {
        fn replace_token(self) -> Vec<u8>;
    }
    impl ReplaceToken for Vec<u8> {
        fn replace_token(self) -> Vec<u8> {
            String::from_utf8(self)
                .unwrap()
                .replace("FAKE-REFRESH-2", "FAKE-REFRESH-3")
                .into_bytes()
        }
    }

    #[tokio::test]
    async fn refusals_mark_the_account() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let spotify = &app.state.spotify;
        let ask = || spotify.access_token(&app.state, account, READ_MIN_LEFT);

        // 429: no state change; a 5xx or the network: `http_<n>` / `network`.
        app.fetch.answer(TOKEN_URL, 429, "");
        app.fetch.retry_after(TOKEN_URL, "30");
        assert_eq!(ask().await.err(), Some(SpotifyError::RateLimited(Some(30))));
        assert_eq!(outcome(&app, account).await, (None, None, 0));
        app.fetch.answer(TOKEN_URL, 503, "");
        assert_eq!(ask().await.err(), Some(SpotifyError::Status(503)));
        assert_eq!(outcome(&app, account).await.1.as_deref(), Some("http_503"));
        app.fetch
            .fail(TOKEN_URL, FetchError::Network("reset".to_string()));
        assert_eq!(ask().await.err().unwrap().code(), "network");
        assert_eq!(outcome(&app, account).await.1.as_deref(), Some("network"));

        // invalid_client: the app is refused, not the login.
        app.fetch
            .answer(TOKEN_URL, 400, fixture("token_invalid_client.json"));
        assert_eq!(ask().await.err(), Some(SpotifyError::AppRefused));
        assert_eq!(
            outcome(&app, account).await,
            (None, Some("invalid_client".to_string()), 0)
        );
        assert_eq!(SpotifyError::AppRefused.code(), "app_refused");

        // invalid_grant: needs_login, an event with the name, and never a request again.
        app.fetch
            .answer(TOKEN_URL, 400, fixture("token_invalid_grant.json"));
        assert_eq!(ask().await.err(), Some(SpotifyError::Reauthorize));
        assert_eq!(
            outcome(&app, account).await,
            (None, Some("invalid_grant".to_string()), 1)
        );
        let events: Vec<String> = sqlx::query_scalar(
            "SELECT detail FROM security_events WHERE event_type = 'spotify_login_needed'",
        )
        .fetch_all(&app.db)
        .await
        .unwrap();
        assert_eq!(events, ["Spotify account Ella: log in again"]);
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        app.fetch.clear_calls();
        for _ in 0..3 {
            assert_eq!(ask().await.err(), Some(SpotifyError::Reauthorize));
        }
        assert_eq!(app.fetch.count(), 0, "no request after invalid_grant");
        assert_eq!(SpotifyError::Reauthorize.code(), "reauthorize");
    }

    #[tokio::test]
    async fn a_key_that_cant_open_the_token() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let mut other = app.state.clone();
        other.music_key = Some(Arc::new(crate::music_secret::MusicKey::from_bytes(
            &[1; 32],
        )));
        let err = app
            .state
            .spotify
            .access_token(&other, account, READ_MIN_LEFT)
            .await
            .err()
            .unwrap();
        assert_eq!(err, SpotifyError::Key);
        assert_eq!(err.code(), "key");
        assert_eq!(outcome(&app, account).await.1.as_deref(), Some("key"));
        let mut keyless = app.state.clone();
        keyless.music_key = None;
        assert_eq!(
            app.state
                .spotify
                .access_token(&keyless, account, READ_MIN_LEFT)
                .await
                .err(),
            Some(SpotifyError::Key)
        );
        // A row moved to another account's user (the AAD binds the token to its account).
        sqlx::query("UPDATE music_spotify_accounts SET spotify_user = 'fakeparent' WHERE id = ?")
            .bind(account)
            .execute(&app.db)
            .await
            .unwrap();
        assert_eq!(
            app.state
                .spotify
                .access_token(&app.state, account, READ_MIN_LEFT)
                .await
                .err(),
            Some(SpotifyError::Key)
        );
        assert_eq!(app.fetch.count(), 0);
    }

    /// §2.4: one refresh at a time per account - two mints at once make one refresh.
    #[tokio::test]
    async fn the_per_account_lock_makes_one_refresh() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        app.fetch.delay(50);
        let spotify = &app.state.spotify;
        let (a, b) = tokio::join!(
            spotify.access_token(&app.state, kid, READ_MIN_LEFT),
            spotify.access_token(&app.state, kid, READ_MIN_LEFT),
        );
        assert_eq!(a.unwrap().token.secret(), "FAKE-ACCESS-2");
        assert_eq!(b.unwrap().token.secret(), "FAKE-ACCESS-2");
        assert_eq!(token_calls(&app), 1);
        // Another account has its own lock and its own token.
        let parent = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        spotify
            .access_token(&app.state, parent, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(token_calls(&app), 2);
        // Forgetting drops the cache.
        spotify.forget(kid);
        spotify
            .access_token(&app.state, kid, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(token_calls(&app), 3);
        spotify.forget_all();
        spotify
            .access_token(&app.state, parent, READ_MIN_LEFT)
            .await
            .unwrap();
        assert_eq!(token_calls(&app), 4);
    }

    #[tokio::test]
    async fn web_api_reads() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        let spotify = &app.state.spotify;
        let url = spotify.api_url("/v1/me");
        assert_eq!(url, ME_URL);
        app.fetch.answer(ME_URL, 200, fixture("me_kid.json"));
        let response = spotify
            .api_get(&app.state, account, &url, Kind::Token)
            .await
            .unwrap();
        assert_eq!(response.body, fixture("me_kid.json"));
        let read = app.fetch.requests.lock().unwrap().last().cloned().unwrap();
        assert_eq!(read.method, "GET");
        assert_eq!(read.header("authorization"), Some("Bearer FAKE-ACCESS-2"));
        assert_eq!(read.header("accept"), Some("application/json"));
        assert_eq!(
            app.fetch.reaches.lock().unwrap().last().unwrap().1,
            Reach::Public
        );

        // A 401 refreshes once and retries once; always 401 = the 401.
        app.fetch.clear_calls();
        app.fetch.answer_once(ME_URL, 401, "");
        spotify
            .api_get(&app.state, account, &url, Kind::Token)
            .await
            .unwrap();
        assert_eq!((token_calls(&app), app.fetch.count()), (1, 3));
        app.fetch.clear_calls();
        app.fetch.answer(ME_URL, 401, "");
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::Status(401))
        );
        assert_eq!((token_calls(&app), app.fetch.count()), (1, 3));

        // 404, a truncated answer.
        app.fetch.answer(ME_URL, 404, "");
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::NotFound)
        );
        app.fetch.answer(ME_URL, 200, vec![b' '; 70_000]);
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::TooBig)
        );

        // 429: a short Retry-After is waited for once, a long one is the answer.
        app.fetch.answer(ME_URL, 200, fixture("me_kid.json"));
        app.fetch
            .answer_once(ME_URL, 429, fixture("rate_limited.json"));
        app.fetch.retry_after(ME_URL, "0");
        app.fetch.clear_calls();
        spotify
            .api_get(&app.state, account, &url, Kind::Token)
            .await
            .unwrap();
        assert_eq!(app.fetch.count(), 2);
        app.fetch.answer(ME_URL, 429, fixture("rate_limited.json"));
        app.fetch.clear_calls();
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::RateLimited(Some(0)))
        );
        assert_eq!(app.fetch.count(), 2, "waited once only");
        app.fetch.retry_after(ME_URL, "30");
        app.fetch.clear_calls();
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::RateLimited(Some(30)))
        );
        assert_eq!(app.fetch.count(), 1);

        // QUOTA_EXCEEDED: no Web API read for an hour (the token endpoint goes on).
        app.fetch.answer(ME_URL, 429, fixture("quota.json"));
        app.fetch.clear_calls();
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::Quota)
        );
        assert!(spotify.quota_until().is_some());
        app.fetch.answer(ME_URL, 200, fixture("me_kid.json"));
        app.fetch.clear_calls();
        assert_eq!(
            spotify
                .api_get(&app.state, account, &url, Kind::Token)
                .await
                .err(),
            Some(SpotifyError::Quota)
        );
        assert_eq!(app.fetch.count(), 0);
        spotify
            .access_token(&app.state, account, Duration::MAX)
            .await
            .unwrap();
        assert_eq!(
            token_calls(&app),
            1,
            "the accounts service isn't the Web API"
        );
        spotify.advance(QUOTA_PAUSE);
        assert!(spotify.quota_until().is_none());
        spotify
            .api_get(&app.state, account, &url, Kind::Token)
            .await
            .unwrap();
    }

    /// §9.3: only with the loopback override do the requests use `Reach::Any`.
    #[tokio::test]
    async fn the_override_reaches_loopback() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let vars: HashMap<String, String> = [
            ("SPOTIFY_ACCOUNTS_BASE", "http://127.0.0.1:8731"),
            ("SPOTIFY_API_BASE", "http://127.0.0.1:8731"),
        ]
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
        let mut state = app.state.clone();
        state.spotify = Arc::new(Accounts::new(Config::from_vars(&vars)));
        app.fetch.answer(
            "http://127.0.0.1:8731/api/token",
            200,
            fixture("token_refresh.json"),
        );
        app.fetch
            .answer("http://127.0.0.1:8731/v1/me", 200, fixture("me_kid.json"));
        let url = state.spotify.api_url("/v1/me");
        state
            .spotify
            .api_get(&state, account, &url, Kind::Token)
            .await
            .unwrap();
        let reaches = app.fetch.reaches.lock().unwrap().clone();
        assert_eq!(
            reaches,
            vec![
                ("http://127.0.0.1:8731/api/token".to_string(), Reach::Any),
                ("http://127.0.0.1:8731/v1/me".to_string(), Reach::Any),
            ]
        );
    }

    /// The autoref probe (storytel's): `has_debug()` resolves to the inherent method when `T:
    /// Debug`, else to the trait's `false`.
    struct Has<T>(std::marker::PhantomData<T>);
    trait Lacks {
        fn has_debug(&self) -> bool {
            false
        }
        fn has_display(&self) -> bool {
            false
        }
    }
    impl<T> Lacks for Has<T> {}
    impl<T: std::fmt::Debug> Has<T> {
        fn has_debug(&self) -> bool {
            true
        }
    }
    impl<T: std::fmt::Display> Has<T> {
        fn has_display(&self) -> bool {
            true
        }
    }

    /// §7.1: the types that hold a token, the verifier or a pending login have no `Debug` and no
    /// `Display`.
    #[test]
    fn secret_types_have_no_debug() {
        use std::marker::PhantomData;
        assert!(Has::<String>(PhantomData).has_debug());
        assert!(Has::<String>(PhantomData).has_display());
        assert!(Has::<SpotifyError>(PhantomData).has_debug());
        macro_rules! secret {
            ($($t:ty),*) => {$(
                assert!(!Has::<$t>(PhantomData).has_debug(), stringify!($t));
                assert!(!Has::<$t>(PhantomData).has_display(), stringify!($t));
            )*};
        }
        secret!(
            AccessToken,
            RefreshToken,
            Verifier,
            Pending,
            Started,
            TokenAnswer,
            Access,
            Slot,
            Minted,
            MintJob,
            Accounts,
            SealedRow
        );
    }

    /// No error text holds a token, the code, the verifier or the client ID's form: a failing
    /// token request over loopback (a dropped connection, a redirect loop) and every answer whose
    /// body echoes the token.
    #[tokio::test]
    async fn no_secret_in_errors() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        use axum::response::IntoResponse;
        let router = axum::Router::new().route(
            "/api/token",
            axum::routing::post(|| async {
                axum::response::Redirect::temporary("/api/token").into_response()
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let dropper = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let dropped = dropper.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((socket, _)) = dropper.accept().await {
                drop(socket);
            }
        });
        let secret_free = |text: &str| {
            for secret in [
                "FAKE-REFRESH",
                "FAKE-ACCESS",
                "FAKE-CODE",
                "refresh_token=",
                "code=",
            ] {
                assert!(!text.contains(secret), "{secret} in {text}");
            }
        };
        let source = crate::music_net::HttpSource::new();
        for base in [format!("http://{addr}"), format!("http://{dropped}")] {
            let accounts = Accounts::new(Config::from_vars(
                &[("SPOTIFY_ACCOUNTS_BASE".to_string(), base)].into(),
            ));
            let request = accounts.token_request(
                "grant_type=authorization_code&code=FAKE-CODE-1&code_verifier=abc\
                 &refresh_token=FAKE-REFRESH-1"
                    .to_string(),
            );
            let err = crate::music_net::Source::get(&source, request)
                .await
                .expect_err("the request fails");
            let err = SpotifyError::Fetch(err);
            secret_free(&format!("{err:?} {} {}", err.message(), err.code()));
        }
        // Through the account: answers that echo the token never reach the error.
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        for status in [200, 400, 401, 429, 500] {
            app.fetch.answer(
                TOKEN_URL,
                status,
                r#"{"error":"FAKE-REFRESH-1","access_token":"FAKE-ACCESS-9"}"#,
            );
            match app
                .state
                .spotify
                .access_token(&app.state, account, READ_MIN_LEFT)
                .await
            {
                Ok(_) => panic!("{status}: no token"),
                Err(err) => secret_free(&format!("{err:?} {} {}", err.message(), err.code())),
            }
        }
        let stored: Vec<(Option<String>,)> =
            sqlx::query_as("SELECT refresh_error FROM music_spotify_accounts")
                .fetch_all(&app.db)
                .await
                .unwrap();
        secret_free(&format!("{stored:?}"));
    }

    #[test]
    fn the_login_line_is_180_days() {
        let row = AccountRow {
            id: 1,
            display_name: "Ella".to_string(),
            authorized_at: "2026-10-12 10:00:00".to_string(),
            refresh_error: None,
            needs_login: 0,
        };
        let ends = row.login_ends().unwrap();
        assert_eq!(ends, parse_stamp("2027-04-10 10:00:00").unwrap());
        assert!(!row.login_ending(parse_stamp("2027-03-27 09:59:59").unwrap()));
        assert!(row.login_ending(parse_stamp("2027-03-27 10:00:00").unwrap()));
        assert!(row.login_ending(parse_stamp("2027-05-01 10:00:00").unwrap()));
    }

    /// Code QA L3: two reads that got a 401 with the same token make one refresh - the second
    /// takes the token the first one got; a 401 for the new token refreshes again.
    #[tokio::test]
    async fn two_401s_refresh_once() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        let spotify = &app.state.spotify;
        let rejected = spotify
            .access_token(&app.state, account, READ_MIN_LEFT)
            .await
            .unwrap()
            .token;
        assert_eq!(rejected.secret(), "FAKE-ACCESS-2");
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh_rotated.json"));
        app.fetch.delay(20);
        app.fetch.clear_calls();
        let (mut sent_a, mut sent_b) = (0, 0);
        let (a, b) = tokio::join!(
            spotify.mint(
                &app.state,
                account,
                READ_MIN_LEFT,
                Some(&rejected),
                &mut sent_a
            ),
            spotify.mint(
                &app.state,
                account,
                READ_MIN_LEFT,
                Some(&rejected),
                &mut sent_b
            ),
        );
        assert_eq!(sent_a + sent_b, 1, "one refresh counted");
        let (a, b) = (a.ok().unwrap(), b.ok().unwrap());
        assert_eq!(a.token.secret(), "FAKE-ACCESS-3");
        assert_eq!(b.token.secret(), "FAKE-ACCESS-3");
        assert_eq!(token_calls(&app), 1, "one refresh for two 401s");
        assert_eq!(
            stored_refresh(&app, account).await.as_deref(),
            Some("FAKE-REFRESH-2"),
            "one rotation written"
        );
        // A 401 for the new token is a new refresh.
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        let c = spotify
            .mint(&app.state, account, READ_MIN_LEFT, Some(&a.token), &mut 0)
            .await
            .ok()
            .unwrap();
        assert_eq!(c.token.secret(), "FAKE-ACCESS-2");
        assert_eq!(token_calls(&app), 2);
        // And through api_get: a read whose token gets a 401 once retries with a fresh one.
        app.fetch.answer(ME_URL, 200, fixture("me_kid.json"));
        app.fetch.answer_once(ME_URL, 401, "");
        app.fetch.clear_calls();
        spotify
            .api_get(&app.state, account, ME_URL, Kind::Token)
            .await
            .unwrap();
        assert_eq!((token_calls(&app), app.fetch.count()), (1, 3));
    }

    // --- step 2a: the listing ---

    /// §3.4 over the fixtures: `items[].item`, local files, `is_playable: false`, episodes in a
    /// playlist and removed tracks left out and counted, titles "Name — A, B", the image pick.
    #[test]
    fn listing_parsers_read_the_fixtures() {
        let meta = parse_playlist_meta(&fixture("playlist.json")).unwrap();
        assert_eq!(meta.name.as_deref(), Some("Kveldsmusikk"));
        assert_eq!(meta.snapshot.as_deref(), Some("AAAAA0fakeSnapshotKveld1"));
        assert_eq!(
            meta.image.as_deref(),
            Some("https://mosaic.scdn.co/640/fake-mosaic-kveld"),
            "a mosaic without sizes is still the picture"
        );
        let page = parse_playlist_items(&fixture("playlist_items.json")).unwrap();
        let rows: Vec<(&str, &str, Option<&str>)> = page
            .listed
            .iter()
            .map(|t| (t.id.as_str(), t.title.as_str(), t.art.as_deref()))
            .collect();
        assert_eq!(
            rows,
            [
                (
                    "1FakeTrackA00000000001",
                    "Stille natt — Ola Fjell, Kari Li",
                    Some("https://i.scdn.co/image/fake-album-x-300")
                ),
                (
                    "1FakeTrackC00000000003",
                    "Månesang — Kari Li",
                    Some("https://i.scdn.co/image/fake-album-x-300")
                ),
                (
                    "1FakeTrackA00000000001",
                    "Stille natt — Ola Fjell, Kari Li",
                    Some("https://i.scdn.co/image/fake-album-x-300")
                ),
                (
                    "1FakeTrackD00000000004",
                    "Uten artist",
                    Some("https://i.scdn.co/image/fake-album-y-640")
                ),
            ],
            "the parser keeps a repeat; the sweep lists it once"
        );
        assert_eq!(page.listed[0].duration_ms, Some(201_000));
        // The episode, the local file, the unplayable track and the removed one.
        assert_eq!(page.skipped, 4);
        assert!(!page.next);
        assert_eq!(page.total, Some(8));
        // The older name of the field (`track`) is read too; a next page is seen.
        let older = serde_json::json!({
            "items": [{"is_local": false, "track": {
                "type": "track", "id": "1FakeTrackA00000000001", "name": "X",
                "artists": [{"name": "Y"}]}}],
            "next": "https://api.spotify.com/v1/playlists/x/items?offset=50", "total": 51
        });
        let page = parse_playlist_items(older.to_string().as_bytes()).unwrap();
        assert_eq!(page.listed[0].title, "X — Y");
        assert!(page.next);
        // qa-26c-step2a-code L1: an episode in a track's shape (`"episode": true`), or a URI that
        // isn't a track's, is left out and counted, whatever its `type` says.
        let shaped = serde_json::json!({
            "items": [
                {"is_local": false, "item": {"type": "track", "episode": true, "track": false,
                    "id": "5FakeEpisode0000000009", "name": "Episode",
                    "uri": "spotify:episode:5FakeEpisode0000000009"}},
                {"is_local": false, "item": {"type": "track",
                    "id": "5FakeEpisode0000000008", "name": "Episode",
                    "uri": "spotify:episode:5FakeEpisode0000000008"}},
                {"is_local": false, "item": {"type": "track", "episode": false,
                    "id": "1FakeTrackA00000000001", "name": "X",
                    "uri": "spotify:track:1FakeTrackA00000000001"}}
            ],
            "next": null
        });
        let page = parse_playlist_items(shaped.to_string().as_bytes()).unwrap();
        assert_eq!(
            page.listed
                .iter()
                .map(|t| t.id.as_str())
                .collect::<Vec<_>>(),
            ["1FakeTrackA00000000001"]
        );
        assert_eq!(page.skipped, 2);
        for junk in [&b"<html>"[..], b"[]", b"{}", br#"{"items": {}}"#] {
            assert_eq!(parse_playlist_items(junk), None, "{junk:?}");
        }

        let (album, page) = parse_album(&fixture("album.json")).unwrap();
        assert_eq!(album.name.as_deref(), Some("Nattlys — Ola Fjell"));
        assert_eq!(
            album.image.as_deref(),
            Some("https://i.scdn.co/image/fake-album-x-300")
        );
        assert_eq!(
            page.listed.iter().map(|t| t.disc_track).collect::<Vec<_>>(),
            [Some((1, 1)), Some((1, 3))]
        );
        assert!(
            page.listed.iter().all(|t| t.art.is_none()),
            "no album inside"
        );
        assert_eq!((page.skipped, page.next), (1, true));
        let more = parse_album_tracks(&fixture("album_tracks.json")).unwrap();
        assert_eq!(more.listed[0].title, "Bonusspor — Ola Fjell");
        assert!(!more.next);

        let (track, page) = parse_track(&fixture("track.json")).unwrap();
        assert_eq!(
            track.name.as_deref(),
            Some("Fjellvise — Ola Fjell, Kari Li")
        );
        assert_eq!(
            track.image.as_deref(),
            Some("https://i.scdn.co/image/fake-album-y-300")
        );
        assert_eq!(page.listed[0].art, track.image);
        let mut unplayable: Value = serde_json::from_slice(&fixture("track.json")).unwrap();
        unplayable["is_playable"] = Value::Bool(false);
        let (_, page) = parse_track(unplayable.to_string().as_bytes()).unwrap();
        assert_eq!((page.listed.len(), page.skipped), (0, 1));

        let artist = parse_named(&fixture("artist.json")).unwrap();
        assert_eq!(artist.name.as_deref(), Some("Ola Fjell"));
        assert_eq!(
            artist.image.as_deref(),
            Some("https://i.scdn.co/image/fake-artist-320")
        );
        let (albums, next) = parse_artist_albums(&fixture("artist_albums.json")).unwrap();
        assert_eq!(albums.len(), 3);
        assert_eq!(albums[2].released, "2026-03");
        assert!(!next);

        let show = parse_named(&fixture("show.json")).unwrap();
        assert_eq!(show.name.as_deref(), Some("Eventyrstund"));
        let page = parse_show_episodes(&fixture("show_episodes.json")).unwrap();
        assert_eq!(
            page.listed
                .iter()
                .map(|e| e.title.as_str())
                .collect::<Vec<_>>(),
            ["Episode 3: Trollet", "Episode 1: Prinsessen"],
            "newest first, as Spotify sends it"
        );
        // A null entry and an unplayable episode.
        assert_eq!(page.skipped, 2);
        assert_eq!(
            page.listed[0].art.as_deref(),
            Some("https://i.scdn.co/image/fake-episode-3")
        );
        let (episode, page) = parse_episode(&fixture("episode.json")).unwrap();
        assert_eq!(episode.name.as_deref(), Some("Episode 3: Trollet"));
        assert_eq!(
            episode.image.as_deref(),
            Some("https://i.scdn.co/image/fake-episode-3-300")
        );
        assert_eq!(page.listed[0].duration_ms, Some(900_000));
        for junk in [&b"null"[..], b"{}", br#"{"name": ""}"#] {
            assert_eq!(parse_named(junk), None);
            assert_eq!(parse_track(junk), None);
            assert_eq!(parse_episode(junk), None);
            assert_eq!(parse_album(junk), None);
        }
    }

    /// §3.4's pick: of at least 300 px the narrowest, else the widest; never a URL that isn't
    /// http(s) without userinfo.
    #[test]
    fn the_image_pick() {
        let pick = |images: Value| pick_image(Some(&images));
        let image = |url: &str, width: Option<u64>| serde_json::json!({"url": url, "width": width});
        assert_eq!(
            pick(serde_json::json!([
                image("https://a/640", Some(640)),
                image("https://a/300", Some(300)),
                image("https://a/64", Some(64)),
            ]))
            .as_deref(),
            Some("https://a/300")
        );
        assert_eq!(
            pick(serde_json::json!([
                image("https://a/64", Some(64)),
                image("https://a/200", Some(200)),
            ]))
            .as_deref(),
            Some("https://a/200"),
            "all small: the widest"
        );
        assert_eq!(
            pick(serde_json::json!([
                image("javascript:alert(1)", Some(640)),
                image("https://user@a/x", Some(640)),
                image("https://a/ok", None),
            ]))
            .as_deref(),
            Some("https://a/ok")
        );
        assert_eq!(pick(serde_json::json!([])), None);
        assert_eq!(pick(serde_json::json!("x")), None);
        assert_eq!(pick_image(None), None);
        assert_eq!(track_title("A", &[]), "A");
        assert_eq!(
            track_title("A", &["B".to_string(), "C".to_string()]),
            "A — B, C"
        );
    }

    /// Every read's URL, built from the canonical id (§3.1's table).
    #[test]
    fn read_urls_are_exact() {
        let accounts = Accounts::default();
        let id = "0FakePlaylistKveld0001";
        for (read, url) in [
            (
                Read::Playlist(id),
                "/v1/playlists/0FakePlaylistKveld0001?fields=name%2Csnapshot_id%2Cimages%2C\
                 owner%28id%2Cdisplay_name%29",
            ),
            (
                Read::PlaylistItems(id, 50),
                "/v1/playlists/0FakePlaylistKveld0001/items?limit=50&offset=50\
                 &additional_types=track%2Cepisode&market=from_token",
            ),
            (
                Read::Album(id),
                "/v1/albums/0FakePlaylistKveld0001?market=from_token",
            ),
            (
                Read::AlbumTracks(id, 100),
                "/v1/albums/0FakePlaylistKveld0001/tracks?limit=50&offset=100&market=from_token",
            ),
            (
                Read::Track(id),
                "/v1/tracks/0FakePlaylistKveld0001?market=from_token",
            ),
            (Read::Artist(id), "/v1/artists/0FakePlaylistKveld0001"),
            (
                Read::ArtistAlbums(id, 0),
                "/v1/artists/0FakePlaylistKveld0001/albums?include_groups=album%2Csingle\
                 &limit=10&offset=0&market=from_token",
            ),
            (
                Read::Show(id),
                "/v1/shows/0FakePlaylistKveld0001?market=from_token",
            ),
            (
                Read::ShowEpisodes(id, 0),
                "/v1/shows/0FakePlaylistKveld0001/episodes?limit=50&offset=0&market=from_token",
            ),
            (
                Read::ShowNewest(id),
                "/v1/shows/0FakePlaylistKveld0001/episodes?limit=1&offset=0&market=from_token",
            ),
            (
                Read::Episode(id),
                "/v1/episodes/0FakePlaylistKveld0001?market=from_token",
            ),
        ] {
            assert_eq!(accounts.read_url(read), format!("{API_BASE}{url}"));
        }
    }

    /// The show gate's mark (qa-srv-sweep-resume-code L10): `total:first id`, `-` for a `null`
    /// first entry, and no mark without a `total`, an entry or a paging object.
    #[test]
    fn a_shows_mark_needs_its_total() {
        let mark = |v: serde_json::Value| show_mark(v.to_string().as_bytes());
        assert_eq!(
            mark(serde_json::json!({"items": [{"id": "5Ep"}, {"id": "4Ep"}], "total": 7})),
            Some("7:5Ep".to_string())
        );
        assert_eq!(
            mark(serde_json::json!({"items": [null], "total": 3})),
            Some("3:-".to_string())
        );
        assert_eq!(mark(serde_json::json!({"items": [{"id": "5Ep"}]})), None);
        assert_eq!(mark(serde_json::json!({"items": [null]})), None);
        assert_eq!(mark(serde_json::json!({"items": [], "total": 0})), None);
        assert_eq!(mark(serde_json::json!({"total": 3})), None);
        assert_eq!(show_mark(b"not json"), None);
    }

    /// §3.3: the readers - no `needs_login`, the last reader first, a removed one counts for
    /// nothing; none at all = `no_account`, every login ended = `reauthorize`.
    #[tokio::test]
    async fn readers_put_the_last_one_first() {
        let app = TestApp::new().await;
        let none = readers(&app.db, None).await.unwrap();
        assert_eq!(none.refusal(), Some(SpotifyError::NoAccount));
        let kid = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        let parent = store_account(&app, "fakeparent", "Forelder", "FAKE-REFRESH-9").await;
        let third = store_account(&app, "fakeother", "Ola", "FAKE-REFRESH-8").await;
        assert_eq!(
            readers(&app.db, None).await.unwrap().ids,
            [kid, parent, third]
        );
        assert_eq!(
            readers(&app.db, Some(third)).await.unwrap().ids,
            [third, kid, parent]
        );
        assert_eq!(
            readers(&app.db, Some(999)).await.unwrap().ids,
            [kid, parent, third],
            "a removed reader's id is none"
        );
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 1 WHERE id IN (?, ?)")
            .bind(kid)
            .bind(third)
            .execute(&app.db)
            .await
            .unwrap();
        let some = readers(&app.db, Some(third)).await.unwrap();
        assert_eq!((some.ids.as_slice(), some.refusal()), (&[parent][..], None));
        sqlx::query("UPDATE music_spotify_accounts SET needs_login = 1")
            .execute(&app.db)
            .await
            .unwrap();
        let ended = readers(&app.db, None).await.unwrap();
        assert_eq!(ended.refusal(), Some(SpotifyError::Reauthorize));
        assert_eq!(ended.accounts, 3);
        // Which failures let the next reader try.
        for err in [
            SpotifyError::NotFound,
            SpotifyError::Status(403),
            SpotifyError::Status(401),
            SpotifyError::Reauthorize,
            SpotifyError::Key,
            SpotifyError::NoAccount,
        ] {
            assert!(try_next_reader(&err), "{err:?}");
        }
        for err in [
            SpotifyError::AppRefused,
            SpotifyError::Quota,
            SpotifyError::RateLimited(None),
            SpotifyError::Fetch(FetchError::Timeout),
            SpotifyError::Status(500),
            SpotifyError::BadAnswer,
            SpotifyError::TooBig,
        ] {
            assert!(!try_next_reader(&err), "{err:?}");
        }
    }

    /// §3.1 (QA L4): a short link followed hop by hop - `follow: false`, `Reach::Public`, at most
    /// three requests, each `Location` https on Spotify's three hosts, the first
    /// `open.spotify.com` URL parsed and never asked.
    #[tokio::test]
    async fn short_links_are_followed_hop_by_hop() {
        let app = TestApp::new().await;
        let source = app.state.music_fetch.as_ref();
        let id = "0FakePlaylistKveld0001";
        app.fetch.redirect(
            "https://spotify.link/AbC123",
            307,
            "https://spotify.app.link/AbC123?_p=c1",
        );
        app.fetch.redirect(
            "https://spotify.app.link/AbC123?_p=c1",
            302,
            &format!("https://open.spotify.com/playlist/{id}?si=x&utm_source=copy-link"),
        );
        let found = resolve_short_link(source, "https://spotify.link/AbC123")
            .await
            .unwrap();
        assert_eq!(found.target(), format!("spotify:playlist:{id}"));
        let requests = app.fetch.requests.lock().unwrap().clone();
        assert_eq!(
            requests.iter().map(|r| r.url.as_str()).collect::<Vec<_>>(),
            [
                "https://spotify.link/AbC123",
                "https://spotify.app.link/AbC123?_p=c1"
            ],
            "open.spotify.com is parsed, not asked"
        );
        assert!(requests.iter().all(|r| !r.follow && r.method == "GET"));
        assert!(
            app.fetch
                .reaches
                .lock()
                .unwrap()
                .iter()
                .all(|(_, reach)| *reach == Reach::Public)
        );
        // A relative Location on the same host.
        app.fetch
            .redirect("https://spotify.link/Rel1", 301, "/Rel2");
        app.fetch.redirect(
            "https://spotify.link/Rel2",
            301,
            &format!("https://open.spotify.com/intl-de/album/{id}"),
        );
        assert_eq!(
            resolve_short_link(source, "https://spotify.link/Rel1")
                .await
                .unwrap()
                .kind,
            LinkKind::Album
        );
        // Off Spotify's hosts, plain http, a port, userinfo: nowhere (nothing asked there).
        for (code, location) in [
            ("Off1", "https://evil.example/playlist/x"),
            (
                "Off2",
                "http://open.spotify.com/playlist/0FakePlaylistKveld0001",
            ),
            (
                "Off3",
                "https://open.spotify.com:8443/playlist/0FakePlaylistKveld0001",
            ),
            ("Off4", "https://user@spotify.app.link/x"),
            ("Off5", "ftp://spotify.link/x"),
        ] {
            app.fetch
                .redirect(&format!("https://spotify.link/{code}"), 302, location);
            app.fetch.clear_calls();
            assert_eq!(
                resolve_short_link(source, &format!("https://spotify.link/{code}")).await,
                Err(ShortLinkError::Dead),
                "{location}"
            );
            assert_eq!(app.fetch.count(), 1, "{location}");
        }
        // No redirect at all; a 404.
        app.fetch
            .answer("https://spotify.link/Page1", 200, "<html>");
        app.fetch.answer("https://spotify.link/Gone1", 404, "");
        for code in ["Page1", "Gone1"] {
            assert_eq!(
                resolve_short_link(source, &format!("https://spotify.link/{code}")).await,
                Err(ShortLinkError::Dead)
            );
        }
        // A chain longer than three hops: three requests, then nowhere.
        for n in 0..5 {
            app.fetch.redirect(
                &format!("https://spotify.link/Loop{n}"),
                302,
                &format!("https://spotify.app.link/Loop{}", n + 1),
            );
            app.fetch.redirect(
                &format!("https://spotify.app.link/Loop{}", n + 1),
                302,
                &format!("https://spotify.link/Loop{}", n + 2),
            );
        }
        app.fetch.clear_calls();
        assert_eq!(
            resolve_short_link(source, "https://spotify.link/Loop0").await,
            Err(ShortLinkError::Dead)
        );
        assert_eq!(app.fetch.count(), SHORT_LINK_HOPS);
        // Led to an audiobook: its sentence; the network: unreachable.
        app.fetch.redirect(
            "https://spotify.link/Book1",
            302,
            &format!("https://open.spotify.com/audiobook/{id}"),
        );
        let err = resolve_short_link(source, "https://spotify.link/Book1")
            .await
            .unwrap_err();
        assert_eq!(err, ShortLinkError::Link(LinkError::Audiobook));
        assert_eq!(err.message(), "Spotify audiobooks can't be played here.");
        let err = resolve_short_link(source, "https://spotify.link/Unknown1")
            .await
            .unwrap_err();
        assert!(matches!(err, ShortLinkError::Unreachable(_)));
        assert_eq!(err.message(), "Spotify couldn't be reached - try again.");
        assert_eq!(
            ShortLinkError::Dead.message(),
            "That Spotify link didn't lead anywhere."
        );
        // Only a short link is asked at all.
        app.fetch.clear_calls();
        assert_eq!(
            resolve_short_link(source, "https://open.spotify.com/x").await,
            Err(ShortLinkError::Dead)
        );
        assert_eq!(app.fetch.count(), 0);
    }

    /// The sweep's budget counts every request a read makes, the token refresh included.
    #[tokio::test]
    async fn reads_count_their_requests() {
        let app = TestApp::new().await;
        save_app(&app, LOOPBACK_REDIRECT).await;
        let account = store_account(&app, "fakekid", "Ella", "FAKE-REFRESH-1").await;
        app.fetch
            .answer(TOKEN_URL, 200, fixture("token_refresh.json"));
        app.fetch.answer(ME_URL, 200, fixture("me_kid.json"));
        app.fetch.answer_once(ME_URL, 401, "");
        let spotify = &app.state.spotify;
        let mut sent = 0;
        spotify
            .api_get_counted(&app.state, account, ME_URL, Kind::Page, &mut sent)
            .await
            .unwrap();
        // A refresh, the 401, a second refresh, the read.
        assert_eq!(sent, 4);
        assert_eq!(app.fetch.count(), 4);
        let mut sent = 0;
        spotify
            .api_get_counted(&app.state, account, ME_URL, Kind::Page, &mut sent)
            .await
            .unwrap();
        assert_eq!(sent, 1, "the kept token");
    }

    /// `SPOTIFY_ARTIST_ALBUMS` switches §3.3's option on (off by default).
    #[test]
    fn the_artist_option_is_off_by_default() {
        assert!(!Config::default().artist_albums);
        for (value, on) in [("1", true), ("true", true), ("0", false), ("yes", false)] {
            let config = Config::from_vars(
                &[("SPOTIFY_ARTIST_ALBUMS".to_string(), value.to_string())].into(),
            );
            assert_eq!(config.artist_albums, on, "{value}");
        }
    }

    /// §6.2 / §8 item 5: the host list is `testdata/spotify_hosts.json`'s, byte-identical in both
    /// apps' test resources (as `phone_vectors.json`; their own tests compare too), and a domain is
    /// Spotify's when it equals or lies under a suffix, or equals an exact host - never a look-alike
    /// or a third-party parent.
    #[test]
    fn hosts_match_the_contract() {
        const HOSTS: &str = include_str!("../testdata/spotify_hosts.json");
        for app in ["launcher", "music"] {
            let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
                "../{app}/app/src/test/resources/spotify_hosts.json"
            ));
            let copy = std::fs::read_to_string(&path)
                .unwrap_or_else(|e| panic!("{}: {e}", path.display()));
            assert_eq!(copy, HOSTS, "{} differs", path.display());
        }
        let contract: Value = serde_json::from_str(HOSTS).unwrap();
        let list = |key: &str| -> Vec<String> {
            contract[key]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap().to_string())
                .collect()
        };
        assert_eq!(list("suffixes"), HOST_SUFFIXES);
        assert_eq!(list("exact"), EXACT_HOSTS);
        // The list's shape, never its wording (qa-26c-step7-docs M1): the DNS capture's commit
        // changes the data and the comment, not this test. Exactly the three keys, a comment, and
        // two lists of distinct lower-case host names without a leading or trailing dot; an exact
        // host never lies under a suffix (it would say nothing the suffix doesn't).
        let keys: Vec<&String> = contract.as_object().unwrap().keys().collect();
        assert_eq!(keys.len(), 3, "{keys:?}");
        assert!(!contract["comment"].as_str().unwrap().trim().is_empty());
        for key in ["suffixes", "exact"] {
            let hosts = list(key);
            assert!(!hosts.is_empty(), "{key}");
            let distinct: std::collections::HashSet<&String> = hosts.iter().collect();
            assert_eq!(distinct.len(), hosts.len(), "{key} repeats a host");
            for host in &hosts {
                let ok = !host.is_empty()
                    && !host.starts_with('.')
                    && !host.ends_with('.')
                    && host.contains('.')
                    && !host.contains("..")
                    && host.chars().all(|c| {
                        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '.' || c == '-'
                    });
                assert!(ok, "{key}: {host:?}");
            }
        }
        for host in list("exact") {
            assert!(
                !list("suffixes")
                    .iter()
                    .any(|s| host == *s || host.ends_with(&format!(".{s}"))),
                "{host} lies under a suffix"
            );
        }
        for host in [
            "spotify.com",
            "api.spotify.com",
            "accounts.spotify.com",
            "gew1-spclient.spotify.com",
            "SDK.scdn.co.",
            "i.scdn.co",
            "audio4-fa.scdn.co",
            "x.spotifycdn.com",
            "y.spotifycdn.net",
            "anchor.pscdn.co",
            "audio-ak-spotify-com.akamaized.net",
            "audio-akp-quic-spotify-com.akamaized.net",
        ] {
            assert!(is_spotify_host(host), "{host}");
        }
        for host in [
            "akamaized.net",
            "other.akamaized.net",
            "x.audio-ak-spotify-com.akamaized.net",
            "evilspotify.com",
            "spotify.com.evil.net",
            "scdn.com",
            ".spotify.com",
            "spotify.co",
            "",
        ] {
            assert!(!is_spotify_host(host), "{host}");
        }
    }

    /// §2.6: at most 20 token asks a rolling hour per phone; a refused ask isn't counted, the
    /// wait is until the oldest ask leaves the hour, and each phone has its own count.
    #[test]
    fn phone_asks_are_limited_per_phone() {
        let accounts = Accounts::default();
        for _ in 0..PHONE_TOKENS_PER_WINDOW {
            assert_eq!(accounts.phone_ask(1), Ok(()));
            accounts.advance(Duration::from_secs(60));
        }
        // 20 asks over 20 minutes: the first leaves the window 40 minutes from now.
        assert_eq!(accounts.phone_ask(1), Err(40 * 60));
        assert_eq!(
            accounts.phone_ask(1),
            Err(40 * 60),
            "a refusal isn't counted"
        );
        assert_eq!(accounts.phone_ask(2), Ok(()), "another phone");
        accounts.advance(Duration::from_secs(40 * 60));
        assert_eq!(accounts.phone_ask(1), Ok(()), "the oldest left the hour");
        assert!(
            accounts.phone_ask(1).is_err(),
            "and the next one is full again"
        );
        accounts.advance(PHONE_TOKEN_WINDOW);
        assert_eq!(accounts.phone_ask(1), Ok(()));
        assert_eq!(
            lock(&accounts.phone_asks).len(),
            1,
            "phones with no ask left in the hour are forgotten"
        );
    }
}
