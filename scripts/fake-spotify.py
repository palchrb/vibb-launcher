#!/usr/bin/env python3
# fake-spotify.py - a fake Spotify for the server and the music app on the emulator (design 26c §9;
# docs/testing/emulator.md §6l, written in step 5b).
#
# Part of handy (palchrb/vibb-launcher), licensed under the GNU General Public License v3.0 or later
# (LICENSE). Modelled on palchrb/vibb's test fakes and its rule that both fakes answer from ONE source
# of truth (tests/sonos_contract.py; MIT License, Copyright (c) 2026 palchrb; see NOTICE.md), and on
# scripts/fake-storytel.py. Python 3, stdlib only; run it with `python3 -I`.
"""A fake Spotify: the accounts service, the Web API subset the server and the phone use, and a stub
Web Playback SDK that plays a real tone through the music app's web player.

    python3 -I scripts/fake-spotify.py                         # port 8731, the PWA logs in as the parent
    python3 -I scripts/fake-spotify.py --as kid --rotate --access-ttl 600
    python3 -I scripts/fake-spotify.py --track-seconds 20 --autoplay --elsewhere 60
    python3 -I scripts/fake-spotify.py --unplayable 1FakeTrackA00000000001 --relink 1FakeTrackH00000000008:FAKErelink00000000001
    python3 -I scripts/fake-spotify.py --print-listing 6 > debug-feed/items/6.json
    python3 -I scripts/fake-spotify.py --print-listing 6 --tracks 60 > debug-feed/items/6.json   # past the window
    python3 -I scripts/fake-spotify.py --selftest

Every answer comes from the fixtures the tests read: server/testdata/spotify/ (the accounts service's
and the Web API's answers: spotify::tests and tests::music_spotify) and music/app/src/test/resources/
spotify/ (play_request.json - the exact requests the phone sends -, play_answers.json, token_answers.json
and sdk_states/, the state sequences SpotifyTrackerTest replays). Every value is fake (FAKE-ACCESS-<n>,
FAKE-REFRESH-<n>, FAKE-CODE-<n>, users fakeparent/fakekid, tracks FAKEtrack<13 digits>): nothing here
may look like a real account or token.

The accounts service (the server's SPOTIFY_ACCOUNTS_BASE=http://127.0.0.1:8731):
    GET  /authorize?...&code_challenge=..&state=..    302 to redirect_uri?code=FAKE-CODE-<n>&state=<state>
                                                     (auto-consent as --as; --deny: error=access_denied);
                                                     GET /fake/as?user=kid|parent switches it while running
    POST /api/token                                  authorization_code: the code once, the verifier's S256
                                                     against the challenge; refresh_token: FAKE-ACCESS-<n>
                                                     for --access-ttl s, a new FAKE-REFRESH-<n> with
                                                     --rotate; --expire-refresh: 400 invalid_grant;
                                                     --bad-client: 400 invalid_client
The Web API (the server's SPOTIFY_API_BASE, and the phone's with the debug switch), a bearer each:
    GET  /v1/me, /v1/playlists/<id>[/items] (403 unless the bearer's user owns the fixture's playlist;
         --snapshot changes its snapshot_id), /v1/albums/<id>[/tracks], /v1/tracks/<id>,
         /v1/artists/<id>[/albums], /v1/shows/<id>[/episodes], /v1/episodes/<id>
    PUT  /v1/me/player/play?device_id=<id>           the body checked against play_request.json's shapes;
                                                     404 for an unknown device; --not-premium 403
                                                     PREMIUM_REQUIRED; handed to the stub device. A context
                                                     (design 26c §5.9: an artist, or a playlist no account may
                                                     list) plays its tracks - the fixture playlist's listed
                                                     tracks, the fixture artist's album, else FAKEtrack 1..5 -
                                                     from the offset's track (none, or one not in it: the
                                                     top; --offset-404: a 404) at position_ms; the stub's
                                                     next and previous step
                                                     through it (previous after 3 s restarts the track)
    PUT  /v1/me/player                               transfer; POST /v1/me/player/queue?uri=&device_id=;
    PUT  /v1/me/player/shuffle?state=&device_id=,    shuffle and repeat (the phone turns both off;
         /v1/me/player/repeat?state=&device_id=      qa-26c-step4-code M1)
    --rate-limit N: every Nth Web API request 429 with Retry-After: 2; --quota: 429 QUOTA_EXCEEDED
The stub SDK (the phone's web player reaches it as https://fake-spotify.invalid/ through the debug
build's interceptor, design 26c §9.3; every URL is relative to the script's own origin, and every
request is a plain GET - the interceptor sees no request body):
    GET  /sdk/spotify-player.js                      Spotify.Player's documented surface, playing a tone
    GET  /fake/device/connect?name=&token=           registers a device (--no-eme, --not-premium,
                                                     --slow-ready S answer as the SDK's errors would)
    GET  /fake/device/<id>/commands?after=<n>         the long-poll: play, queue, transfer, elsewhere, foreign
    GET  /fake/device/<id>/token?token=              getOAuthToken's answer every --token-ttl s, checked
    GET  /fake/audio/<id>.wav                        a tone the track's length (the fixture's, or
                                                     --track-seconds), Range answered
    GET  /fake/token?user=kid                        the server's token route's answer (design 26c §2.6),
                                                     for a run without a launcher (debugTokens)
Player options: --elsewhere S (a null state S s after the first play), --autoplay (after the last uri a
track that isn't ours), --transfer-in S (a foreign track starts S s after the first play), --unplayable
<id,...> (a playback_error and a skip), --relink <id>:<other> (the track plays as <other>, linked_from
<id>), --no-eme, --not-premium, --slow-ready S, --token-ttl S, --shuffle and --repeat context|track (the
account's settings until the phone turns them off: PUT /v1/me/player/shuffle?state=false and
/repeat?state=off). Spotify's queue (the phone's add-to-queue) survives a new play, as Spotify's does.
"""

import argparse
import base64
import hashlib
import http.client
import json
import math
import os
import re
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_FIXTURES = os.path.join(HERE, "..", "server", "testdata", "spotify")
MUSIC_FIXTURES = os.path.join(HERE, "..", "music", "app", "src", "test", "resources", "spotify")
LIBRARY = os.path.join(HERE, "..", "server", "testdata", "music_library.json")
PLAYER_JS = os.path.join(HERE, "..", "music", "app", "src", "main", "assets", "spotify", "player.js")
T0 = time.monotonic()
RATE = 8000                       # Hz, 16-bit mono: 16 000 bytes a second of tone
HEADER = 44
ID = re.compile(r"^[A-Za-z0-9]{1,64}$")
URI = re.compile(r"^spotify:(track|episode):[A-Za-z0-9]{1,64}$")
CLIENT_ID = re.compile(r"^[0-9A-Za-z]{32}$")
USERS = {"parent": ("fakeparent", "me_parent.json", 1), "kid": ("fakekid", "me_kid.json", 2)}
AUTOPLAY = {"uri": "spotify:track:FAKEautoplay0000000001", "name": "Something Spotify picked",
            "artists": ["Someone Else"], "duration_ms": 30000, "type": "track"}
FOREIGN = {"uri": "spotify:track:FAKEforeign0000000001", "name": "Another app's song",
           "artists": ["Someone Else"], "duration_ms": 30000, "type": "track"}
FOREIGN_CONTEXT = "spotify:playlist:FAKEforeignList000001"
AUTOPLAY_CONTEXT = "spotify:station:FAKEautoplay000000001"   # Autoplay after a context plays in a context of its own
CONTEXT = re.compile(r"^spotify:(playlist|artist):([A-Za-z0-9]{1,64})$")
CONTEXT_TRACKS = 5                                          # a context the fixtures don't name: FAKEtrack 1..5
REPEAT = {"off": 0, "context": 1, "track": 2}


def log(msg):
    print(f"[{time.monotonic() - T0:7.1f}] {msg}", flush=True)


def server_fixture(name):
    with open(os.path.join(SERVER_FIXTURES, name), encoding="utf-8") as f:
        return json.load(f)


def music_fixture(name):
    with open(os.path.join(MUSIC_FIXTURES, name), encoding="utf-8") as f:
        return json.load(f)


def s256(verifier):
    """RFC 7636's S256: base64url(sha256(verifier)) without padding."""
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode("ascii")


def fake_track(n, seconds=None):
    return {"uri": "spotify:track:FAKEtrack%013d" % n, "name": "Fake track %d" % n, "artists": ["Fake Artist"],
            "duration_ms": int((seconds or 20) * 1000), "type": "track"}


def listing_title(name, artists):
    """The server's listing title (spotify::track_title + music::cut_title): "<name> — <a>, <b>"."""
    names = [a.strip() for a in artists if a and a.strip()]
    raw = name.strip() if not names else f"{name.strip()} — {', '.join(names)}"
    return " ".join(raw.split())[:200]


def wav_header(size):
    data = size - HEADER
    return (b"RIFF" + struct.pack("<I", size - 8) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16)
            + b"data" + struct.pack("<I", data))


def tone(freq, start_sample, count):
    """`count` samples of a quiet sine at `freq` Hz from sample `start_sample` (16-bit little-endian)."""
    out = bytearray()
    for i in range(start_sample, start_sample + count):
        out += struct.pack("<h", int(6000 * math.sin(2 * math.pi * freq * i / RATE)))
    return bytes(out)


class Device:
    """A stub SDK player the fake knows: its owner, its command queue for the long-poll."""

    def __init__(self, device_id, name, user):
        self.id, self.name, self.user = device_id, name, user
        self.commands = []          # (seq, command)
        self.cond = threading.Condition()
        self.played = False

    def push(self, command):
        with self.cond:
            seq = (self.commands[-1][0] if self.commands else 0) + 1
            self.commands.append((seq, command))
            self.cond.notify_all()
        log(f"device {self.id}: -> {command['type']}")

    def after(self, seq, wait):
        with self.cond:
            deadline = time.monotonic() + wait
            while True:
                fresh = [(n, c) for n, c in self.commands if n > seq]
                left = deadline - time.monotonic()
                if fresh or left <= 0:
                    return fresh
                self.cond.wait(left)


class Spotify:
    """The fake's state: the fixtures, the logins, the tokens, the devices, the catalogue."""

    def __init__(self, args):
        self.args = args
        self.consent = args.as_user
        self.lock = threading.Lock()
        self.serial = {"code": 0, "access": 0, "refresh": 0, "device": 0}
        self.codes = {}             # code -> (challenge, redirect_uri, client_id, user)
        self.refresh = {}           # refresh token -> user
        self.access = {}            # access token -> (user, monotonic expiry)
        self.devices = {}
        self.requests = 0
        self.token_code = server_fixture("token_code.json")
        self.token_refresh = server_fixture("token_refresh.json")
        self.token_rotated = server_fixture("token_refresh_rotated.json")
        self.answers = music_fixture("play_answers.json")
        self.shapes = music_fixture("play_request.json")
        self.playlist = server_fixture("playlist.json")
        self.playlist_items = server_fixture("playlist_items.json")
        self.playlist_id = urllib.parse.urlsplit(self.playlist_items["href"]).path.split("/")[3]
        self.album = server_fixture("album.json")
        self.track = server_fixture("track.json")
        self.artist = server_fixture("artist.json")
        self.show = server_fixture("show.json")
        self.episode = server_fixture("episode.json")
        self.catalogue = {}
        for t in self.listed_tracks() + [t for t in self.album["tracks"]["items"]] + \
                server_fixture("album_tracks.json")["items"] + [self.track]:
            self.add(t, "track")
        for e in [e for e in server_fixture("show_episodes.json")["items"] if e] + [self.episode]:
            self.add(e, "episode")
        for t in (AUTOPLAY, FOREIGN):
            self.catalogue[t["uri"]] = dict(t)
        self.unplayable = set(filter(None, (args.unplayable or "").split(",")))
        self.relink = dict(p.split(":", 1) for p in filter(None, (args.relink or "").split(",")))

    def add(self, t, kind):
        if t.get("id") and t.get("uri", "").startswith(f"spotify:{kind}:"):
            self.catalogue[t["uri"]] = {"uri": t["uri"], "name": t.get("name", ""), "type": kind,
                                        "artists": [a["name"] for a in t.get("artists", []) if a.get("name")],
                                        "duration_ms": t.get("duration_ms") or 20000}

    def listed_tracks(self):
        """The fixture playlist's items the server lists (§3.4: a track with an id, not local, playable)."""
        out, seen = [], set()
        for row in self.playlist_items["items"]:
            item = row.get("item") or row.get("track")
            if item and item.get("type") == "track" and item.get("id") and not item.get("is_local") \
                    and item.get("is_playable") is not False and item.get("uri", "").startswith("spotify:track:") \
                    and item["id"] not in seen:
                seen.add(item["id"])        # a duplicate once, as the server lists it (its key is the track id)
                out.append(item)
        return out

    def meta(self, uri):
        """What the stub plays for `uri`: the catalogue's, a generated FAKEtrack, or a generic track."""
        t = self.catalogue.get(uri)
        m = re.match(r"^spotify:track:FAKEtrack(\d{13})$", uri)
        if t is None and m:
            t = fake_track(int(m.group(1)))
        if t is None:
            t = {"uri": uri, "name": "Spotify track " + uri.rsplit(":", 1)[1], "artists": ["Fake Artist"],
                 "duration_ms": 20000, "type": uri.split(":")[1]}
        t = dict(t)
        if self.args.track_seconds:
            t["duration_ms"] = int(self.args.track_seconds * 1000)
        tid = uri.rsplit(":", 1)[1]
        if tid in self.unplayable:
            t["unplayable"] = True
        if tid in self.relink:
            t["linked_from"] = uri
            t["uri"] = uri.rsplit(":", 1)[0] + ":" + self.relink[tid]
        return t

    def next_value(self, kind, prefix):
        with self.lock:
            self.serial[kind] += 1
            return f"{prefix}-{self.serial[kind]}"

    def mint_access(self, user):
        token = self.next_value("access", "FAKE-ACCESS")
        with self.lock:
            self.access[token] = (user, time.monotonic() + self.args.access_ttl)
        return token

    def user_of(self, token):
        with self.lock:
            entry = self.access.get(token or "")
        if entry is None or time.monotonic() >= entry[1]:
            return None
        return entry[0]

    def audio_size(self, uri):
        ms = self.meta(uri)["duration_ms"]
        return HEADER + (ms * RATE // 1000) * 2

    def context_tracks(self, context_uri):
        """A context's tracks in Spotify's order (design 26c §5.9): the fixture playlist's listed tracks, the fixture
        artist's album (its first page and the next), else FAKEtrack 1..5 - for the library snapshot's entries 6 and 7,
        whose ids no fixture has. None for anything that isn't a playlist or an artist."""
        m = CONTEXT.match(context_uri or "")
        if not m:
            return None
        kind, cid = m.group(1), m.group(2)
        if kind == "playlist" and cid == self.playlist_id:
            uris = [t["uri"] for t in self.listed_tracks()]
        elif kind == "artist" and cid == self.artist["id"]:
            uris = [t["uri"] for t in self.album["tracks"]["items"] + server_fixture("album_tracks.json")["items"]]
        else:
            uris = [fake_track(n)["uri"] for n in range(1, CONTEXT_TRACKS + 1)]
        return [self.meta(u) for u in uris]

    def listing(self, entry, version, tracks):
        """A listing document as the server serves it (design 26c §3.4: url null, the title, the duration)."""
        if tracks is None:
            rows = [{"key": t["id"], "title": listing_title(t["name"], [a["name"] for a in t.get("artists", [])]),
                     "duration_ms": t.get("duration_ms")} for t in self.listed_tracks()]
        else:
            rows = []
            for n in range(1, tracks + 1):
                t = fake_track(n, self.args.track_seconds)
                rows.append({"key": t["uri"].rsplit(":", 1)[1], "title": listing_title(t["name"], t["artists"]),
                             "duration_ms": t["duration_ms"]})
        return {"v": 1, "entry": entry, "version": version,
                "items": [{"key": r["key"], "title": r["title"], "url": None, "hls": False,
                           "duration_ms": r["duration_ms"], "art": None} for r in rows]}


def play_body_ok(body, shapes):
    """The play body's shape: play_request.json's `play` (uris + position_ms) or `play_context` keys."""
    if not isinstance(body, dict):
        return False
    uris_keys = set(json.loads(shapes["play"]["body"]))
    context_keys = set(json.loads(shapes["play_context"]["body"]))
    if set(body) == uris_keys:
        return isinstance(body["uris"], list) and 0 < len(body["uris"]) <= 100 \
            and all(isinstance(u, str) and URI.match(u) for u in body["uris"]) \
            and isinstance(body["position_ms"], int) and body["position_ms"] >= 0
    if set(body) <= context_keys and "context_uri" in body:
        offset = body.get("offset", {"uri": "spotify:track:x"})
        return isinstance(body["context_uri"], str) and body["context_uri"].startswith("spotify:") \
            and isinstance(offset, dict) and isinstance(offset.get("uri"), str) \
            and isinstance(body.get("position_ms", 0), int) and body.get("position_ms", 0) >= 0
    return False


def handler_for(sp):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send(self, status, body=b"", headers=(), content_type="application/json"):
            if isinstance(body, (dict, list)):
                body = json.dumps(body).encode()
            self.send_response(status)
            if body:
                self.send_header("Content-Type", content_type)
            # The stub's requests come from the page's origin (https://appassets.androidplatform.net).
            self.send_header("Access-Control-Allow-Origin", "*")
            for k, v in headers:
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def answer(self, name):
            a = sp.answers[name]
            headers = [("Retry-After", a["retry_after"])] if a.get("retry_after") else []
            self.send(a["status"], a["body"] if a["body"] is not None else b"", headers)

        def body(self):
            length = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(length) if length else b""

        def parts(self):
            url = urllib.parse.urlsplit(self.path)
            return url.path, {k: v[0] for k, v in urllib.parse.parse_qs(url.query, keep_blank_values=True).items()}

        def do_GET(self):
            path, q = self.parts()
            if path == "/authorize":
                return self.authorize(q)
            if path == "/fake/as":
                return self.switch(q)
            if path.startswith("/v1/"):
                return self.web_api("GET", path, q)
            if path == "/sdk/spotify-player.js":
                log("sdk: the stub's script")
                return self.send(200, STUB_SDK.encode(), content_type="application/javascript; charset=utf-8")
            if path == "/fake/device/connect":
                return self.connect(q)
            m = re.match(r"^/fake/device/([A-Za-z0-9]{1,64})/(commands|token)$", path)
            if m:
                return self.device(m.group(1), m.group(2), q)
            m = re.match(r"^/fake/audio/([A-Za-z0-9]{1,64})\.wav$", path)
            if m:
                return self.audio(m.group(1))
            if path == "/fake/token":
                return self.phone_token(q)
            log(f"GET {path}: 404")
            self.send(404)

        def do_PUT(self):
            path, q = self.parts()
            if path.startswith("/v1/"):
                return self.web_api("PUT", path, q)
            self.send(404)

        def do_POST(self):
            path, q = self.parts()
            if path == "/api/token":
                return self.token()
            if path.startswith("/v1/"):
                return self.web_api("POST", path, q)
            self.send(404)

        # --- the accounts service -------------------------------------------------------------

        def authorize(self, q):
            need = ("response_type", "client_id", "scope", "redirect_uri", "code_challenge_method", "code_challenge", "state")
            if any(k not in q for k in need) or q["response_type"] != "code" or q["code_challenge_method"] != "S256" \
                    or not CLIENT_ID.match(q["client_id"]):
                log("authorize: 400 (not the server's authorize query)")
                return self.send(400, {"error": "invalid_request"})
            if sp.args.deny:
                target = q["redirect_uri"] + "?" + urllib.parse.urlencode({"error": "access_denied", "state": q["state"]})
                log("authorize: --deny -> error=access_denied")
                return self.send(302, headers=[("Location", target)])
            code = sp.next_value("code", "FAKE-CODE")
            with sp.lock:
                sp.codes[code] = (q["code_challenge"], q["redirect_uri"], q["client_id"], USERS[sp.consent][0])
            target = q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": code, "state": q["state"]})
            log(f"authorize: consented as {sp.consent} -> {code}")
            self.send(302, headers=[("Location", target)])

        def switch(self, q):
            if q.get("user") not in USERS:
                return self.send(400, {"error": "user is parent or kid"})
            sp.consent = q["user"]
            log(f"the next login consents as {sp.consent}")
            self.send(200, {"as": sp.consent})

        def token(self):
            form = {k: v[0] for k, v in urllib.parse.parse_qs(self.body().decode("utf-8", "replace")).items()}
            if (self.headers.get("Content-Type") or "").split(";")[0].strip() != "application/x-www-form-urlencoded":
                return self.send(400, {"error": "invalid_request"})
            if sp.args.bad_client or not CLIENT_ID.match(form.get("client_id", "")):
                log("token: 400 invalid_client")
                return self.send(400, server_fixture("token_invalid_client.json"))
            grant = form.get("grant_type")
            if grant == "authorization_code":
                with sp.lock:
                    pending = sp.codes.pop(form.get("code", ""), None)   # single use
                if pending is None or pending[1] != form.get("redirect_uri") or pending[2] != form.get("client_id") \
                        or s256(form.get("code_verifier", "")) != pending[0]:
                    log("token: 400 invalid_grant (code, redirect, client or verifier)")
                    return self.send(400, {"error": "invalid_grant", "error_description": "Invalid authorization code"})
                user = pending[3]
                refresh = sp.next_value("refresh", "FAKE-REFRESH")
                with sp.lock:
                    sp.refresh[refresh] = user
                answer = dict(sp.token_code, access_token=sp.mint_access(user), refresh_token=refresh,
                              expires_in=int(sp.args.access_ttl))
                log(f"token: code exchanged for {user}: {answer['access_token']}, {refresh}")
                return self.send(200, answer)
            if grant == "refresh_token":
                with sp.lock:
                    user = sp.refresh.get(form.get("refresh_token", ""))
                if sp.args.expire_refresh or user is None:
                    log("token: 400 invalid_grant (--expire-refresh or an unknown refresh token)")
                    return self.send(400, server_fixture("token_invalid_grant.json"))
                if sp.args.rotate:
                    rotated = sp.next_value("refresh", "FAKE-REFRESH")
                    with sp.lock:
                        sp.refresh[rotated] = user
                    answer = dict(sp.token_rotated, access_token=sp.mint_access(user), refresh_token=rotated)
                else:
                    answer = dict(sp.token_refresh, access_token=sp.mint_access(user))
                answer["expires_in"] = int(sp.args.access_ttl)
                log(f"token: refreshed for {user}: {answer['access_token']}{', rotated' if sp.args.rotate else ''}")
                return self.send(200, answer)
            self.send(400, {"error": "unsupported_grant_type"})

        # --- the Web API ----------------------------------------------------------------------

        def web_api(self, method, path, q):
            auth = self.headers.get("Authorization") or ""
            user = sp.user_of(auth[len("Bearer "):] if auth.startswith("Bearer ") else None)
            body = self.body() if method in ("PUT", "POST") else b""
            if user is None:
                log(f"{method} {path}: 401")
                return self.answer("unauthorized")
            with sp.lock:
                sp.requests += 1
                n = sp.requests
            if sp.args.quota:
                log(f"{method} {path}: 429 QUOTA_EXCEEDED (--quota)")
                return self.send(429, server_fixture("quota.json"))
            if sp.args.rate_limit and n % sp.args.rate_limit == 0:
                log(f"{method} {path}: 429 Retry-After 2 (--rate-limit {sp.args.rate_limit})")
                return self.send(429, server_fixture("rate_limited.json"), [("Retry-After", "2")])
            if method == "GET":
                return self.read(path, q, user)
            if method == "PUT" and path == "/v1/me/player/play":
                return self.play(q, body, user)
            if method == "PUT" and path in ("/v1/me/player/shuffle", "/v1/me/player/repeat"):
                return self.setting(path.rsplit("/", 1)[1], q, user)
            if method == "PUT" and path == "/v1/me/player":
                return self.transfer(body, user)
            if method == "POST" and path == "/v1/me/player/queue":
                return self.queue(q, user)
            log(f"{method} {path}: 404")
            self.send(404, {"error": {"status": 404, "message": "Service not found"}})

        def read(self, path, q, user):
            not_found = {"error": {"status": 404, "message": "Resource not found"}}
            seg = path.strip("/").split("/")[1:]
            doc = None
            if seg == ["me"]:
                doc = server_fixture(next(f for u, f, _ in USERS.values() if u == user))
            elif seg[:1] == ["playlists"] and len(seg) in (2, 3) and seg[1] == sp.playlist_id:
                if len(seg) == 2:
                    doc = dict(sp.playlist)
                    if sp.args.snapshot:
                        doc["snapshot_id"] = sp.args.snapshot
                elif seg[2] == "items":
                    if user != sp.playlist["owner"]["id"]:
                        log(f"GET {path}: 403 ({user} doesn't own the playlist)")
                        return self.send(403, server_fixture("forbidden.json"))
                    doc = sp.playlist_items if q.get("offset", "0") == "0" else \
                        {"items": [], "next": None, "offset": int(q.get("offset", "0")), "total": sp.playlist_items.get("total")}
            elif seg[:1] == ["albums"] and len(seg) >= 2 and seg[1] == sp.album["id"]:
                doc = sp.album if len(seg) == 2 else server_fixture("album_tracks.json") if seg[2:] == ["tracks"] else None
            elif seg == ["tracks", sp.track["id"]]:
                doc = sp.track
            elif seg[:1] == ["artists"] and len(seg) >= 2 and seg[1] == sp.artist["id"]:
                doc = sp.artist if len(seg) == 2 else server_fixture("artist_albums.json") if seg[2:] == ["albums"] else None
            elif seg[:1] == ["shows"] and len(seg) >= 2 and seg[1] == sp.show["id"]:
                doc = sp.show if len(seg) == 2 else server_fixture("show_episodes.json") if seg[2:] == ["episodes"] else None
            elif seg == ["episodes", sp.episode["id"]]:
                doc = sp.episode
            log(f"GET {path}: {200 if doc is not None else 404} ({user})")
            self.send(200, doc) if doc is not None else self.send(404, not_found)

        def device_for(self, device_id, user):
            with sp.lock:
                d = sp.devices.get(device_id or "")
            return d if d is not None and d.user == user else None

        def play(self, q, raw, user):
            d = self.device_for(q.get("device_id"), user)
            if d is None:
                log(f"play: 404 (device {q.get('device_id')!r} unknown for {user})")
                return self.answer("device_unknown")
            if sp.args.not_premium:
                log("play: 403 PREMIUM_REQUIRED (--not-premium)")
                return self.answer("not_premium")
            try:
                body = json.loads(raw)
            except ValueError:
                body = None
            if not play_body_ok(body, sp.shapes):
                log("play: 400 (not play_request.json's shape)")
                return self.send(400, {"error": {"status": 400, "message": "Malformed json"}})
            command = {"type": "play", "position_ms": body.get("position_ms", 0), "context_uri": body.get("context_uri"),
                       "index": 0, "autoplay_context": None}
            if "uris" in body:
                command["tracks"] = [sp.meta(u) for u in body["uris"]]
                command["autoplay"] = sp.meta(AUTOPLAY["uri"]) if sp.args.autoplay else None
                log(f"play: {len(body['uris'])} uris from {body['uris'][0]} at {body['position_ms']} ms on {d.id}")
            else:
                tracks = sp.context_tracks(body["context_uri"])
                if tracks is None:
                    log(f"play: 400 (a context that is neither a playlist nor an artist: {body['context_uri']})")
                    return self.send(400, {"error": {"status": 400, "message": "Non supported context uri"}})
                start = (body.get("offset") or {}).get("uri")
                uris = [t.get("linked_from") or t["uri"] for t in tracks]
                if start and start not in uris and sp.args.offset_404:
                    # the stored track left the playlist and Spotify refuses the offset (qa-26c-step6-code M3)
                    log(f"play: 404 (--offset-404: {start} isn't in {body['context_uri']})")
                    return self.send(404, {"error": {"status": 404, "message": "Not found."}})
                index = uris.index(start) if start in uris else 0
                command.update(tracks=tracks, index=index, autoplay_context=AUTOPLAY_CONTEXT if sp.args.autoplay else None,
                               autoplay=sp.meta(AUTOPLAY["uri"]) if sp.args.autoplay else None)
                where = f"from {start} (track {index + 1} of {len(tracks)})" if start in uris else \
                    (f"from the top ({start} isn't in it)" if start else "from Spotify's start")
                log(f"play: context {body['context_uri']} {where} at {body.get('position_ms', 0)} ms on {d.id}")
            d.push(command)
            if not d.played:
                d.played = True
                if sp.args.elsewhere is not None:
                    threading.Timer(sp.args.elsewhere, d.push, [{"type": "elsewhere"}]).start()
                if sp.args.transfer_in is not None:
                    threading.Timer(sp.args.transfer_in, d.push,
                                    [{"type": "foreign", "track": sp.meta(FOREIGN["uri"]), "context_uri": FOREIGN_CONTEXT}]).start()
            self.answer("ok")

        def transfer(self, raw, user):
            try:
                body = json.loads(raw)
            except ValueError:
                body = None
            if not isinstance(body, dict) or set(body) != set(json.loads(sp.shapes["transfer"]["body"])) \
                    or not isinstance(body["device_ids"], list) or len(body["device_ids"]) != 1:
                return self.send(400, {"error": {"status": 400, "message": "Malformed json"}})
            d = self.device_for(body["device_ids"][0], user)
            if d is None:
                log("transfer: 404 (device unknown)")
                return self.answer("device_unknown")
            d.push({"type": "transfer", "play": bool(body["play"])})
            self.answer("ok")

        def setting(self, name, q, user):
            """Toggle Playback Shuffle / Set Repeat Mode (qa-26c-step4-code M1): play_request.json's query keys."""
            want = set(urllib.parse.parse_qs(sp.shapes[name]["query"]))
            d = self.device_for(q.get("device_id"), user)
            if d is None:
                log(f"{name}: 404 (device unknown)")
                return self.answer("device_unknown")
            values = {"shuffle": {"true": True, "false": False}, "repeat": REPEAT}[name]
            if set(q) != want or q.get("state") not in values:
                return self.send(400, {"error": {"status": 400, "message": "Illegal state"}})
            d.push({"type": name, "state": values[q["state"]]})
            log(f"{name}: {q['state']} on {d.id}")
            self.answer("ok")

        def queue(self, q, user):
            d = self.device_for(q.get("device_id"), user)
            if d is None:
                log("queue: 404 (device unknown)")
                return self.answer("device_unknown")
            if not URI.match(q.get("uri", "")):
                return self.send(400, {"error": {"status": 400, "message": "Invalid uri"}})
            d.push({"type": "queue", "track": sp.meta(q["uri"])})
            log(f"queue: {q['uri']} on {d.id}")
            self.answer("ok")

        # --- the stub device ------------------------------------------------------------------

        def connect(self, q):
            user = sp.user_of(q.get("token"))
            if user is None:
                log("connect: authentication error (no valid token)")
                return self.send(200, {"error": "authentication", "message": "Invalid token"})
            if sp.args.slow_ready:
                time.sleep(sp.args.slow_ready)
            if sp.args.no_eme:
                log("connect: initialization error (--no-eme)")
                return self.send(200, {"error": "initialization", "message": "Failed to initialize player (fake: no EME)"})
            if sp.args.not_premium:
                log("connect: account error (--not-premium)")
                return self.send(200, {"error": "account", "message": "This functionality is restricted to premium users only"})
            n = sp.next_value("device", "d").split("-")[1]
            device_id = "fakedevice%031d" % int(n)
            with sp.lock:
                sp.devices[device_id] = Device(device_id, (q.get("name") or "")[:64], user)
            log(f"connect: {device_id} \"{q.get('name')}\" for {user}")
            self.send(200, {"device_id": device_id, "token_ttl_s": sp.args.token_ttl,
                            "shuffle": sp.args.shuffle, "repeat": REPEAT[sp.args.repeat]})

        def device(self, device_id, what, q):
            with sp.lock:
                d = sp.devices.get(device_id)
            if d is None:
                return self.send(404, {"error": "no such device"})
            if what == "token":
                ok = sp.user_of(q.get("token")) == d.user
                log(f"device {device_id}: getOAuthToken {'ok' if ok else 'refused'}")
                return self.send(200, {"ok": ok})
            try:
                after = int(q.get("after", "0"))
            except ValueError:
                after = 0
            fresh = d.after(after, float(q.get("wait", "20")))
            self.send(200, {"seq": fresh[-1][0] if fresh else after, "commands": [c for _, c in fresh]})

        def audio(self, track_id):
            uri = next((u for u in sp.catalogue if u.endswith(":" + track_id)), None) or "spotify:track:" + track_id
            size = sp.audio_size(uri)
            freq = 330 + 55 * (int(hashlib.sha1(track_id.encode()).hexdigest(), 16) % 7)
            start, end, status = 0, size - 1, 200
            rng = self.headers.get("Range")
            if rng:
                r = re.match(r"^bytes=(\d+)-(\d*)$", rng.strip())
                if not r or int(r.group(1)) >= size:
                    return self.send(416, headers=[("Content-Range", f"bytes */{size}")])
                start = int(r.group(1))
                end = min(int(r.group(2)), size - 1) if r.group(2) else size - 1
                status = 206
            # the tone's fetches through the phone's debug proxy (§6l: a seek is a 206 from its byte, the proxy's Range)
            log(f"audio {track_id}: {status}" + (f" Range {rng.strip()}" if rng else ""))
            self.send_response(status)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            header = wav_header(size)
            pos = start
            try:
                while pos <= end:
                    if pos < HEADER:
                        chunk = header[pos:min(end + 1, HEADER)]
                    else:
                        first = (pos - HEADER) // 2
                        count = min((end + 1 - pos + 1) // 2 + 1, RATE)
                        block = tone(freq, first, count)[(pos - HEADER) % 2:]
                        chunk = block[:end + 1 - pos]
                    self.wfile.write(chunk)
                    pos += len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True

        def phone_token(self, q):
            user = q.get("user", "kid")
            if user not in USERS:
                return self.send(404, {"status": "off"})
            if sp.args.expire_refresh:
                return self.send(409, {"status": "reauthorize"})
            uid, _, account = USERS[user]
            token = sp.mint_access(uid)
            log(f"phone token for {user}: {token}")
            self.send(200, {"status": "ok", "access_token": token, "expires_in_s": int(sp.args.access_ttl) - 60,
                            "account": f"{account}:1"})

    return Handler


# The stub Web Playback SDK: the documented Spotify.Player surface (design 26c §1.1), playing the fake's
# tone through an <audio> element. Every URL is relative to the script's own origin and every request a
# plain GET (no body, no custom header: the debug interceptor sees no body, and no CORS preflight).
STUB_SDK = r"""// The fake Spotify's stub Web Playback SDK (scripts/fake-spotify.py; design 26c §9.2). GPL-3.0-or-later.
// Not Spotify's code: our own implementation of the documented Spotify.Player surface, for the emulator.
(function () {
  'use strict';
  var script = document.currentScript;
  var ORIGIN = new URL(script.src).origin;

  function get(path) {
    return fetch(ORIGIN + path, { cache: 'no-store' }).then(function (r) {
      if (!r.ok) throw new Error('fake: HTTP ' + r.status);
      return r.json();
    });
  }

  function trackObject(t) {
    if (!t) return null;
    var id = t.uri.split(':').pop();
    return {
      uri: t.uri, id: id, type: t.type || 'track', media_type: 'audio', name: t.name, is_playable: !t.unplayable,
      album: { uri: '', name: 'Fake album', images: [] },
      artists: (t.artists || []).map(function (n) { return { uri: '', name: n }; }),
      linked_from: t.linked_from ? { uri: t.linked_from, id: t.linked_from.split(':').pop() } : { uri: null, id: null }
    };
  }

  function Player(options) {
    this._options = options || {};
    this._listeners = {};
    this._token = null;
    this._deviceId = null;
    this._seq = 0;
    this._active = false;
    this._queue = [];        // the context: the uris of the last play
    this._index = 0;
    this._current = null;    // what plays: a context track, or one from Spotify's queue
    this._userQueue = [];    // Spotify's queue (our adds): it survives a new play, as Spotify's does
    this._history = [];
    this._shuffle = false;
    this._repeat = 0;        // 0 off, 1 the context, 2 the track
    this._context = null;
    this._autoplay = null;
    this._autoplayContext = null;
    this._ended = false;
    this._polling = false;
    this._audio = new Audio();
    this._audio.volume = typeof this._options.volume === 'number' ? this._options.volume : 1;
    var self = this;
    this._audio.addEventListener('ended', function () { self._advance(); });
    this._audio.addEventListener('playing', function () { self._changed(); });
    this._audio.addEventListener('pause', function () { self._changed(); });
  }

  Player.prototype.addListener = function (name, cb) {
    (this._listeners[name] = this._listeners[name] || []).push(cb);
    return true;
  };
  Player.prototype.on = Player.prototype.addListener;
  Player.prototype.removeListener = function (name, cb) {
    if (!cb) delete this._listeners[name];
    else this._listeners[name] = (this._listeners[name] || []).filter(function (x) { return x !== cb; });
    return true;
  };
  Player.prototype._emit = function (name, arg) {
    (this._listeners[name] || []).slice().forEach(function (cb) { try { cb(arg); } catch (e) { /* the page's */ } });
  };
  Player.prototype._getToken = function () {
    var self = this;
    return new Promise(function (resolve) { self._options.getOAuthToken(function (t) { resolve(t); }); });
  };

  Player.prototype.connect = function () {
    var self = this;
    return this._getToken().then(function (token) {
      self._token = token;
      return get('/fake/device/connect?name=' + encodeURIComponent(self._options.name || '') +
        '&token=' + encodeURIComponent(token || ''));
    }).then(function (d) {
      if (d.error) {
        self._emit(d.error + '_error', { message: d.message });
        return false;
      }
      self._deviceId = d.device_id;
      self._shuffle = !!d.shuffle;
      self._repeat = d.repeat || 0;
      self._emit('ready', { device_id: d.device_id });
      self._polling = true;
      self._poll();
      if (d.token_ttl_s) self._tokenTimer = setInterval(function () { self._renew(); }, d.token_ttl_s * 1000);
      return true;
    }, function () { return false; });
  };

  Player.prototype._renew = function () {
    var self = this;
    this._getToken().then(function (token) {
      self._token = token;
      return get('/fake/device/' + self._deviceId + '/token?token=' + encodeURIComponent(token || ''));
    }).then(function (d) {
      if (!d.ok) self._emit('authentication_error', { message: 'Authentication failed' });
    }, function () { /* the next renewal */ });
  };

  Player.prototype._poll = function () {
    var self = this;
    if (!this._polling) return;
    get('/fake/device/' + this._deviceId + '/commands?after=' + this._seq).then(function (d) {
      self._seq = d.seq;
      d.commands.forEach(function (c) { self._command(c); });
      self._poll();
    }, function () {
      if (self._polling) self._emit('not_ready', { device_id: self._deviceId });
      setTimeout(function () { self._poll(); }, 2000);
    });
  };

  Player.prototype._command = function (c) {
    if (c.type === 'play' || c.type === 'foreign') {
      var tracks = c.type === 'play' ? c.tracks.slice() : [c.track];
      var index = c.index || 0;  // a context's offset (design 26c §5.9)
      if (this._shuffle) { tracks.sort(function () { return Math.random() - 0.5; }); index = 0; }
      if (this._current) this._history.push(this._current);
      this._queue = tracks;
      this._index = index;
      this._current = tracks[index];
      this._context = c.context_uri || null;
      this._autoplay = c.type === 'play' ? c.autoplay : null;
      this._autoplayContext = c.type === 'play' ? c.autoplay_context : null;
      this._active = true;
      this._ended = false;
      this._start(c.position_ms || 0);
    } else if (c.type === 'queue') {
      this._userQueue.push(c.track);
      this._changed();
    } else if (c.type === 'shuffle') {
      this._shuffle = !!c.state;
      this._changed();
    } else if (c.type === 'repeat') {
      this._repeat = c.state;
      this._changed();
    } else if (c.type === 'transfer') {
      this._active = true;
      this._changed();
    } else if (c.type === 'elsewhere') {
      this._audio.pause();
      this._active = false;
      this._emit('player_state_changed', null);
    }
  };

  Player.prototype._start = function (positionMs) {
    var t = this._current;
    if (!t) return;
    if (t.unplayable) {
      this._emit('playback_error', { message: 'fake: this track cannot be played' });
      this._advance();
      return;
    }
    var audio = this._audio;
    audio.src = ORIGIN + '/fake/audio/' + t.uri.split(':').pop() + '.wav';
    var at = positionMs / 1000;
    var seek = function () { audio.currentTime = at; audio.removeEventListener('loadedmetadata', seek); };
    if (at > 0) audio.addEventListener('loadedmetadata', seek);
    audio.play();
    this._changed();
  };

  // After a track: repeat-track, then Spotify's queue, then the context (repeat-context wraps), then
  // Autoplay (--autoplay), else paused at 0 on the played-out track.
  Player.prototype._advance = function () {
    if (this._repeat === 2 && this._current) {
      this._start(0);
      return;
    }
    if (this._current) this._history.push(this._current);
    if (this._userQueue.length) {
      this._current = this._userQueue.shift();
      this._start(0);
    } else if (this._index + 1 < this._queue.length) {
      this._index++;
      this._current = this._queue[this._index];
      this._start(0);
    } else if (this._repeat === 1 && this._queue.length) {
      this._index = 0;
      this._current = this._queue[0];
      this._start(0);
    } else if (this._autoplay && !this._ended) {
      this._ended = true;
      this._current = this._autoplay;
      // after a context, Autoplay plays in a context of its own (a uri list keeps none)
      if (this._autoplayContext) this._context = this._autoplayContext;
      this._start(0);
    } else {
      this._history.pop();
      this._audio.pause();
      this._audio.currentTime = 0;
      this._changed();
    }
  };

  Player.prototype._state = function () {
    var t = this._current;
    if (!this._active || !t) return null;
    return {
      context: { uri: this._context, metadata: {} },
      disallows: {},
      paused: this._audio.paused,
      position: Math.floor((this._audio.currentTime || 0) * 1000),
      duration: t.duration_ms,
      repeat_mode: this._repeat,
      shuffle: this._shuffle,
      timestamp: Date.now(),
      track_window: {
        current_track: trackObject(t),
        previous_tracks: this._history.slice(-2).map(trackObject),
        next_tracks: this._userQueue.concat(this._queue.slice(this._index + 1)).slice(0, 2).map(trackObject)
      }
    };
  };
  Player.prototype._changed = function () { this._emit('player_state_changed', this._state()); };

  Player.prototype.getCurrentState = function () { return Promise.resolve(this._state()); };
  Player.prototype.pause = function () { this._audio.pause(); return Promise.resolve(); };
  Player.prototype.resume = function () {
    if (this._active && this._current) this._audio.play();
    return Promise.resolve();
  };
  Player.prototype.togglePlay = function () { return this._audio.paused ? this.resume() : this.pause(); };
  Player.prototype.seek = function (ms) { this._audio.currentTime = ms / 1000; this._changed(); return Promise.resolve(); };
  // Spotify's previous: more than 3 s into a track restarts it, else the track before it in the context
  Player.prototype.previousTrack = function () {
    if ((this._audio.currentTime || 0) > 3 || this._index === 0) {
      this._audio.currentTime = 0;
      this._changed();
    } else {
      if (this._current) this._history.push(this._current);
      this._index--;
      this._current = this._queue[this._index];
      this._start(0);
    }
    return Promise.resolve();
  };
  Player.prototype.nextTrack = function () {
    this._advance();
    return Promise.resolve();
  };
  Player.prototype.setVolume = function (v) { this._audio.volume = Math.min(1, Math.max(0, v)); return Promise.resolve(); };
  Player.prototype.getVolume = function () { return Promise.resolve(this._audio.volume); };
  Player.prototype.activateElement = function () { return Promise.resolve(); };
  Player.prototype.disconnect = function () {
    this._polling = false;
    if (this._tokenTimer) clearInterval(this._tokenTimer);
    this._audio.pause();
    this._active = false;
  };

  window.Spotify = { Player: Player };
  setTimeout(function () {
    if (typeof window.onSpotifyWebPlaybackSDKReady === 'function') window.onSpotifyWebPlaybackSDKReady();
  }, 0);
})();
"""


def serve(sp, bind):
    srv = ThreadingHTTPServer((bind, sp.args.port), handler_for(sp))
    srv.daemon_threads = True
    sp.args.port = srv.server_address[1]    # port 0 -> the one the system chose (the selftest)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# --- the selftest ---------------------------------------------------------------------------------

def defaults(**over):
    ns = argparse.Namespace(port=0, as_user="parent", deny=False, offset_404=False, rotate=False, access_ttl=3600.0, expire_refresh=False,
                            bad_client=False, snapshot=None, rate_limit=0, quota=False, not_premium=False, no_eme=False,
                            slow_ready=0.0, token_ttl=0, track_seconds=None, elsewhere=None, autoplay=False,
                            transfer_in=None, unplayable="", relink="", shuffle=False, repeat="off")
    for k, v in over.items():
        setattr(ns, k, v)
    return ns


def selftest():
    # 1. The fixtures the tests read hold what this fake answers from, and fake values only.
    real = re.compile(r"\b(BQ|AQ)[A-Za-z0-9_-]{20,}")
    names = sorted(os.listdir(SERVER_FIXTURES))
    for name in names:
        text = open(os.path.join(SERVER_FIXTURES, name), encoding="utf-8").read()
        json.loads(text)
        assert not real.search(text) and "@" not in text, name
    for k in ("access_token", "refresh_token", "expires_in", "scope", "token_type"):
        assert k in server_fixture("token_code.json"), k
    assert "refresh_token" not in server_fixture("token_refresh.json")
    assert server_fixture("token_refresh_rotated.json")["refresh_token"].startswith("FAKE-REFRESH-")
    assert server_fixture("token_invalid_grant.json")["error"] == "invalid_grant"
    assert server_fixture("token_invalid_client.json")["error"] == "invalid_client"
    shapes, answers, tokens = music_fixture("play_request.json"), music_fixture("play_answers.json"), music_fixture("token_answers.json")
    for key in ("play", "play_context", "transfer", "queue", "shuffle", "repeat"):
        r = shapes[key]
        assert r["method"] in ("PUT", "POST") and r["path"].startswith("/v1/me/player"), key
    assert play_body_ok(json.loads(shapes["play"]["body"]), shapes)
    assert play_body_ok(json.loads(shapes["play_context"]["body"]), shapes)
    assert shapes["headers"]["authorization"] == "Bearer " + shapes["token"] and shapes["token"].startswith("FAKE-ACCESS-")
    assert answers["not_premium"]["body"]["error"]["reason"] == "PREMIUM_REQUIRED"
    assert answers["quota"]["body"]["error"]["reason"] == "QUOTA_EXCEEDED" and answers["rate_limited"]["retry_after"] == "2"
    assert tokens["ok"]["body"]["access_token"].startswith("FAKE-ACCESS-") and re.match(r"^\d+:\d+$", tokens["ok"]["body"]["account"])
    js = open(PLAYER_JS, encoding="utf-8").read()
    compact = re.search(r"function compact\(s\) \{.*?return \{(.*?)\};", js, re.S).group(1)
    keys = re.findall(r"^\s*([a-z]+):", compact, re.M)
    states = sorted(os.listdir(os.path.join(MUSIC_FIXTURES, "sdk_states")))
    for name in states:
        doc = music_fixture(os.path.join("sdk_states", name))
        if "context" in doc:   # context mode (design 26c §5.9, step 6): the context and its stored live track, no queue
            assert CONTEXT.match(doc["context"]) and (doc["live"] is None or URI.match(doc["live"])) and "queue" not in doc, name
        else:
            assert doc["queue"] and all(URI.match(q["uri"]) for q in doc["queue"]), name
        for step in doc["steps"]:
            if isinstance(step.get("state"), dict):
                assert list(step["state"]) == keys, (name, list(step["state"]), keys)
                assert all(URI.match(u) for u in step["state"]["next"] + step["state"]["prev"]), name
    for name in os.listdir(MUSIC_FIXTURES):
        if name.endswith(".json"):
            assert not real.search(open(os.path.join(MUSIC_FIXTURES, name), encoding="utf-8").read()), name
    print(f"1. {len(names)} server fixtures, the play requests and answers, the token answers and {len(states)} state "
          f"sequences (each state player.js's {len(keys)} keys) parse, agree and hold fake values only OK")

    def start(**over):
        sp = Spotify(defaults(**over))
        return sp, serve(sp, "127.0.0.1")

    def call(sp, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", sp.args.port, timeout=30)
        data = body.encode() if isinstance(body, str) else body
        conn.request(method, path, body=data, headers=headers or {})
        r = conn.getresponse()           # http.client never follows a redirect
        raw = r.read()
        conn.close()
        try:
            doc = json.loads(raw) if raw and r.getheader("Content-Type", "").startswith("application/json") else raw
        except ValueError:
            doc = raw
        return r.status, dict(r.getheaders()), doc

    form = {"Content-Type": "application/x-www-form-urlencoded"}
    client = "0123456789abcdef0123456789abcdef"
    redirect = "http://127.0.0.1:43117/callback"

    def login(sp, verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", state="0123456789abcdef0123456789abcdef"):
        query = urllib.parse.urlencode({"response_type": "code", "client_id": client, "scope": "streaming",
                                        "redirect_uri": redirect, "code_challenge_method": "S256",
                                        "code_challenge": s256(verifier), "state": state, "show_dialog": "true"})
        status, headers, _ = call(sp, "GET", "/authorize?" + query)
        assert status == 302, status
        back = urllib.parse.urlsplit(headers["Location"])
        q = urllib.parse.parse_qs(back.query)
        assert f"{back.scheme}://{back.netloc}{back.path}" == redirect and q["state"] == [state], headers
        return q.get("code", [None])[0], q.get("error", [None])[0]

    def exchange(sp, code, verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"):
        body = urllib.parse.urlencode({"grant_type": "authorization_code", "code": code, "redirect_uri": redirect,
                                       "client_id": client, "code_verifier": verifier})
        return call(sp, "POST", "/api/token", body, form)

    def refresh(sp, token):
        body = urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": token, "client_id": client})
        return call(sp, "POST", "/api/token", body, form)

    # 2. PKCE: RFC 7636 appendix B's vector, the authorize redirect, the exchange once, the verifier checked
    assert s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    sp, srv = start()
    try:
        code, _ = login(sp)
        assert code == "FAKE-CODE-1"
        assert exchange(sp, code, verifier="x" * 43)[0] == 400, "a wrong verifier"
        code, _ = login(sp)
        status, _, answer = exchange(sp, code)
        assert status == 200 and set(answer) == set(server_fixture("token_code.json")), answer
        assert answer["access_token"].startswith("FAKE-ACCESS-") and answer["refresh_token"] == "FAKE-REFRESH-1"
        assert exchange(sp, code)[0] == 400, "a code is used once"
        parent_access, parent_refresh = answer["access_token"], answer["refresh_token"]
        call(sp, "GET", "/fake/as?user=kid")
        status, _, kid = exchange(sp, login(sp)[0])
        assert status == 200
        kid_access = kid["access_token"]
        print("2. PKCE: RFC 7636's S256 vector, the authorize 302 with code and state, the exchange once, "
              "a wrong verifier refused, /fake/as OK")

        # 3. refresh without rotation; the Web API's routes against their fixtures
        status, _, r = refresh(sp, parent_refresh)
        assert status == 200 and "refresh_token" not in r and r["access_token"].startswith("FAKE-ACCESS-"), r
        assert refresh(sp, "FAKE-REFRESH-99")[0] == 400

        def api(path, token=parent_access):
            return call(sp, "GET", path, headers={"Authorization": "Bearer " + token})

        assert api("/v1/me")[2] == server_fixture("me_parent.json")
        assert api("/v1/me", kid_access)[2] == server_fixture("me_kid.json")
        assert call(sp, "GET", "/v1/me")[0] == 401, "a bearer each"
        pid = sp.playlist_id
        routes = [(f"/v1/playlists/{pid}?fields=name", "playlist.json"),
                  (f"/v1/playlists/{pid}/items?limit=50&offset=0", "playlist_items.json"),
                  (f"/v1/albums/{sp.album['id']}", "album.json"), (f"/v1/albums/{sp.album['id']}/tracks?offset=50", "album_tracks.json"),
                  (f"/v1/tracks/{sp.track['id']}", "track.json"), (f"/v1/artists/{sp.artist['id']}", "artist.json"),
                  (f"/v1/artists/{sp.artist['id']}/albums", "artist_albums.json"), (f"/v1/shows/{sp.show['id']}", "show.json"),
                  (f"/v1/shows/{sp.show['id']}/episodes", "show_episodes.json"), (f"/v1/episodes/{sp.episode['id']}", "episode.json")]
        for path, name in routes:
            status, _, doc = api(path)
            assert status == 200 and doc == server_fixture(name), (path, status)
        assert api(f"/v1/playlists/{pid}/items", kid_access)[0] == 403, "only the owner lists the items"
        assert api("/v1/tracks/0NotAFixture0000000000")[0] == 404
        print(f"3. refresh (no rotation), /v1/me for both users, {len(routes)} Web API routes = their fixtures, "
              "the playlist's items 403 for the kid, 401 without a bearer OK")

        # 4. the stub device: connect, the play body = play_request.json's, the long-poll, transfer, queue, token
        status, _, d = call(sp, "GET", "/fake/device/connect?" + urllib.parse.urlencode({"name": "Vibb", "token": kid_access}))
        device = d["device_id"]
        assert status == 200 and ID.match(device), d
        put = {"Authorization": "Bearer " + kid_access, "Content-Type": "application/json"}
        status, _, body = call(sp, "PUT", "/v1/me/player/play?device_id=" + device, shapes["play"]["body"], put)
        assert status == 204 and body == b"", status
        status, _, cmds = call(sp, "GET", f"/fake/device/{device}/commands?after=0&wait=1")
        play = cmds["commands"][0]
        assert play["type"] == "play" and play["position_ms"] == 42000 and \
            [t["uri"] for t in play["tracks"]] == json.loads(shapes["play"]["body"])["uris"], cmds
        assert play["tracks"][0]["name"] == "Fake track 1" and play["tracks"][0]["duration_ms"] == 20000
        assert call(sp, "PUT", "/v1/me/player/play?device_id=nobody", shapes["play"]["body"], put)[2] == answers["device_unknown"]["body"]
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, shapes["play"]["body"],
                    {"Authorization": "Bearer " + parent_access})[0] == 404, "another account's device"
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, '{"uris": "x"}', put)[0] == 400
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, shapes["play_context"]["body"], put)[0] == 204
        transfer = shapes["transfer"]["body"].replace("fakedevice0000000000000000000000000000001", device)
        assert call(sp, "PUT", "/v1/me/player", transfer, put)[0] == 204
        queue = "/v1/me/player/queue?" + urllib.parse.urlencode({"uri": "spotify:track:FAKEtrack0000000000004", "device_id": device})
        assert call(sp, "POST", queue, None, {"Authorization": "Bearer " + kid_access})[0] == 204
        status, _, cmds = call(sp, "GET", f"/fake/device/{device}/commands?after=1&wait=1")
        assert [c["type"] for c in cmds["commands"]] == ["play", "transfer", "queue"], cmds
        assert cmds["commands"][2]["track"]["uri"] == "spotify:track:FAKEtrack0000000000004" and cmds["seq"] == 4
        # the context form (design 26c §5.9): the library's playlist 6 in context mode - no fixture names its id, so
        # FAKEtrack 1..5 - from the offset's track (2: index 1) at the place
        ctx = cmds["commands"][0]
        assert ctx["context_uri"] == "spotify:playlist:FAKEplaylist0000000001" and ctx["position_ms"] == 42000, ctx
        assert [t["uri"] for t in ctx["tracks"]] == [fake_track(n)["uri"] for n in range(1, 6)] and ctx["index"] == 1, ctx
        assert ctx["autoplay"] is None and ctx["autoplay_context"] is None
        bare = {"Authorization": "Bearer " + kid_access}
        for name, sent in (("shuffle", False), ("repeat", 0)):
            query = shapes[name]["query"].replace("fakedevice0000000000000000000000000000001", device)
            assert call(sp, "PUT", f"{shapes[name]['path']}?{query}", None, bare)[0] == 204, name
            assert call(sp, "PUT", f"{shapes[name]['path']}?state=sideways&device_id={device}", None, bare)[0] == 400, name
            assert call(sp, "PUT", f"{shapes[name]['path']}?state=off&device_id=nobody", None, bare)[0] == 404, name
        status, _, cmds = call(sp, "GET", f"/fake/device/{device}/commands?after=4&wait=1")
        assert [(c["type"], c["state"]) for c in cmds["commands"]] == [("shuffle", False), ("repeat", 0)], cmds
        # context answers: the fixture artist from Spotify's start (no offset, no position), the fixture playlist from an
        # offset that isn't in it (the top), and a context that is neither a playlist nor an artist refused
        artist = json.dumps({"context_uri": sp.artist["uri"]}, separators=(",", ":"))
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, artist, put)[0] == 204
        listed = json.dumps({"context_uri": f"spotify:playlist:{sp.playlist_id}", "offset": {"uri": "spotify:track:FAKEnotInIt0000000001"},
                             "position_ms": 5000}, separators=(",", ":"))
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, listed, put)[0] == 204
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, json.dumps({"context_uri": "spotify:album:x"}), put)[0] == 400
        status, _, cmds = call(sp, "GET", f"/fake/device/{device}/commands?after=6&wait=1")
        by_artist, by_list = cmds["commands"]
        album = [t["uri"] for t in sp.album["tracks"]["items"] + server_fixture("album_tracks.json")["items"]]
        assert [t["uri"] for t in by_artist["tracks"]] == album and by_artist["index"] == 0 and by_artist["position_ms"] == 0, by_artist
        assert [t["uri"] for t in by_list["tracks"]] == [t["uri"] for t in sp.listed_tracks()] and by_list["index"] == 0, by_list
        assert by_list["context_uri"] == f"spotify:playlist:{sp.playlist_id}" and cmds["seq"] == 8
        sp.args.offset_404 = True   # --offset-404 (qa-26c-step6-code M3): an offset the context lacks is a 404, the top plays
        status, _, e = call(sp, "PUT", "/v1/me/player/play?device_id=" + device, listed, put)
        assert status == 404 and e["error"]["status"] == 404, (status, e)
        top = json.dumps({"context_uri": f"spotify:playlist:{sp.playlist_id}"})
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + device, top, put)[0] == 204
        sp.args.offset_404 = False
        assert call(sp, "GET", f"/fake/device/{device}/token?token={kid_access}")[2] == {"ok": True}
        assert call(sp, "GET", f"/fake/device/{device}/token?token=FAKE-ACCESS-99")[2] == {"ok": False}
        status, headers, t = call(sp, "GET", "/fake/token?user=kid")
        assert status == 200 and set(t) == set(tokens["ok"]["body"]) and t["account"] == "2:1", t
        assert headers.get("Access-Control-Allow-Origin") == "*"
        status, _, me = call(sp, "GET", "/v1/me", headers={"Authorization": "Bearer " + t["access_token"]})
        assert me["id"] == "fakekid", "the phone's token is the kid's"
        print("4. the stub device: connect, the play body of play_request.json (and its context form: the context's tracks "
              "from the offset - the fixture artist's, the fixture playlist's, generated ones; --offset-404), the long-poll "
              "(play, transfer, queue, shuffle off, repeat off), 404/400 for a stranger, the phone's token route OK")

        # 5. the tone: a WAV of the track's length; Range 206 and 416
        status, headers, wav = call(sp, "GET", "/fake/audio/FAKEtrack0000000000001.wav")
        assert status == 200 and wav[:4] == b"RIFF" and wav[8:12] == b"WAVE" and len(wav) == HEADER + 20 * RATE * 2, len(wav)
        assert any(wav[HEADER + i] for i in range(2, 200)), "a tone, not silence"
        status, headers, part = call(sp, "GET", "/fake/audio/FAKEtrack0000000000001.wav", headers={"Range": "bytes=1001-"})
        assert status == 206 and part == wav[1001:] and headers["Content-Range"] == f"bytes 1001-{len(wav) - 1}/{len(wav)}"
        assert call(sp, "GET", "/fake/audio/FAKEtrack0000000000001.wav", headers={"Range": f"bytes={len(wav)}-"})[0] == 416
        stille = call(sp, "GET", "/fake/audio/1FakeTrackA00000000001.wav", headers={"Range": "bytes=0-43"})[2]
        assert struct.unpack("<I", stille[40:44])[0] == 201000 * RATE // 1000 * 2, "the fixture's 201 s"
        print("5. the tone: a 16-bit WAV the track's length (the fixture's, else 20 s), Range 206, 416 past the end OK")
    finally:
        srv.shutdown()

    # 6. the options
    sp, srv = start(rotate=True, rate_limit=3)
    try:
        status, _, a = exchange(sp, login(sp)[0])
        status, _, r = refresh(sp, a["refresh_token"])
        assert status == 200 and r["refresh_token"] == "FAKE-REFRESH-2" and set(r) == set(server_fixture("token_refresh_rotated.json"))
        auth = {"Authorization": "Bearer " + r["access_token"]}
        got = [call(sp, "GET", "/v1/me", headers=auth) for _ in range(3)]
        assert [g[0] for g in got] == [200, 200, 429] and got[2][1]["Retry-After"] == "2", [g[0] for g in got]
    finally:
        srv.shutdown()
    for flags, check in (({"expire_refresh": True}, "invalid_grant"), ({"bad_client": True}, "invalid_client")):
        sp, srv = start(**flags)
        try:
            code = login(sp)[0]
            if flags.get("bad_client"):
                status, _, e = exchange(sp, code)
            else:
                status, _, a = exchange(sp, code)
                status, _, e = refresh(sp, a["refresh_token"])
            assert status == 400 and e["error"] == check, (flags, e)
        finally:
            srv.shutdown()
    sp, srv = start(deny=True)
    try:
        assert login(sp) == (None, "access_denied")
    finally:
        srv.shutdown()
    sp, srv = start(quota=True, not_premium=True)
    try:
        token = sp.mint_access("fakekid")
        assert call(sp, "GET", "/v1/me", headers={"Authorization": "Bearer " + token})[2]["error"]["reason"] == "QUOTA_EXCEEDED"
        assert call(sp, "GET", "/fake/device/connect?token=" + token)[2]["error"] == "account"
    finally:
        srv.shutdown()
    sp, srv = start(not_premium=True)
    try:
        token = sp.mint_access("fakekid")
        device = "fakedevice%031d" % 1
        sp.devices[device] = Device(device, "Vibb", "fakekid")
        put = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        status, _, e = call(sp, "PUT", "/v1/me/player/play?device_id=" + device, shapes["play"]["body"], put)
        assert status == 403 and e == answers["not_premium"]["body"], e
    finally:
        srv.shutdown()
    sp, srv = start(no_eme=True, slow_ready=0.3)
    try:
        token = sp.mint_access("fakekid")
        t0 = time.monotonic()
        assert call(sp, "GET", "/fake/device/connect?token=" + token)[2]["error"] == "initialization"
        assert time.monotonic() - t0 >= 0.3, "--slow-ready"
        assert call(sp, "GET", "/fake/device/connect?token=FAKE-ACCESS-99")[2]["error"] == "authentication"
    finally:
        srv.shutdown()
    sp, srv = start(elsewhere=0.2, transfer_in=0.3, autoplay=True, unplayable="FAKEtrack0000000000002",
                    relink="FAKEtrack0000000000003:FAKErelink00000000003", token_ttl=600, track_seconds=5,
                    shuffle=True, repeat="track")
    try:
        token = sp.mint_access("fakekid")
        d = call(sp, "GET", "/fake/device/connect?token=" + token)[2]
        assert d["token_ttl_s"] == 600 and d["shuffle"] is True and d["repeat"] == 2, d
        put = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + d["device_id"], shapes["play"]["body"], put)[0] == 204
        time.sleep(0.5)
        cmds = call(sp, "GET", f"/fake/device/{d['device_id']}/commands?after=0&wait=1")[2]["commands"]
        assert [c["type"] for c in cmds] == ["play", "elsewhere", "foreign"], cmds
        tracks = cmds[0]["tracks"]
        assert tracks[1]["unplayable"] and tracks[2]["uri"] == "spotify:track:FAKErelink00000000003" \
            and tracks[2]["linked_from"] == "spotify:track:FAKEtrack0000000000003" and tracks[0]["duration_ms"] == 5000
        assert cmds[0]["autoplay"]["uri"] == AUTOPLAY["uri"] and cmds[2]["track"]["uri"] == FOREIGN["uri"]
        assert cmds[2]["context_uri"] == FOREIGN_CONTEXT
        assert cmds[0]["autoplay_context"] is None, "after a uri list, Autoplay keeps no context"
        body = json.dumps({"context_uri": "spotify:artist:FAKEartist000000000001"})
        assert call(sp, "PUT", "/v1/me/player/play?device_id=" + d["device_id"], body, put)[0] == 204
        ctx = call(sp, "GET", f"/fake/device/{d['device_id']}/commands?after=3&wait=1")[2]["commands"][0]
        assert ctx["autoplay_context"] == AUTOPLAY_CONTEXT and ctx["autoplay"]["uri"] == AUTOPLAY["uri"], ctx
    finally:
        srv.shutdown()
    print("6. --rotate, --rate-limit 3, --expire-refresh, --bad-client, --deny, --quota, --not-premium (403 and account_error), "
          "--no-eme, --slow-ready, --elsewhere, --transfer-in, --autoplay, --unplayable, --relink, --token-ttl, "
          "--track-seconds, --shuffle, --repeat OK")

    # 7. the stub SDK: the documented surface, and everything player.js uses of it
    for name in ("Spotify = { Player: Player }", "onSpotifyWebPlaybackSDKReady", "track_window", "current_track",
                 "next_tracks", "previous_tracks", "linked_from", "paused", "position", "duration", "context"):
        assert name in STUB_SDK, name
    methods = set(re.findall(r"Player\.prototype\.([a-zA-Z]+) =", STUB_SDK))
    documented = {"connect", "disconnect", "addListener", "on", "removeListener", "getCurrentState", "pause", "resume",
                  "togglePlay", "seek", "previousTrack", "nextTrack", "setVolume", "getVolume", "activateElement"}
    assert documented <= methods, documented - methods
    used = set(re.findall(r"player\.([a-zA-Z]+)\(", js))
    assert used and used <= methods, used - methods
    events = set(re.findall(r"addListener\('([a-z_]+)'", js))
    emitted = set(re.findall(r"_emit\('([a-z_]+)'", STUB_SDK)) | {e + "_error" for e in ("initialization", "authentication", "account")}
    assert events - {"autoplay_failed"} <= emitted, events - emitted
    assert {"ready", "not_ready", "player_state_changed", "initialization_error", "authentication_error",
            "account_error", "playback_error", "autoplay_failed"} == events, events
    assert "http://" not in STUB_SDK and "https://" not in STUB_SDK, "every URL relative to the script's origin"
    assert "method:" not in STUB_SDK and "headers:" not in STUB_SDK, "plain GETs only"
    play_branch = STUB_SDK.split("if (c.type === 'play' || c.type === 'foreign') {")[1].split("} else if")[0]
    assert "_userQueue" not in play_branch and "this._userQueue.push(c.track)" in STUB_SDK, \
        "Spotify's queue (our adds) survives a new play (qa-26c-step4-code H1)"
    assert "this._userQueue.length" in STUB_SDK.split("Player.prototype._advance = function () {")[1].split("};")[0], \
        "after a track, Spotify's queue plays before the context"
    assert "repeat_mode: this._repeat" in STUB_SDK and "shuffle: this._shuffle" in STUB_SDK
    # context mode (design 26c §5.9): the play starts at the offset's index, previous follows Spotify's 3 s rule, and
    # Autoplay after a context plays in its own context (the phone pauses it: not ours)
    assert "var index = c.index || 0;" in play_branch and "this._current = tracks[index];" in play_branch
    previous = STUB_SDK.split("Player.prototype.previousTrack = function () {")[1].split("};")[0]
    assert "(this._audio.currentTime || 0) > 3" in previous and "this._index--;" in previous
    assert "if (this._autoplayContext) this._context = this._autoplayContext;" in \
        STUB_SDK.split("Player.prototype._advance = function () {")[1].split("};")[0]
    assert re.search(r"new Spotify\.Player\(|new window\.Spotify\.Player\(", js), "player.js makes a Spotify.Player"
    print(f"7. the stub SDK: {len(documented)} documented methods, the events and errors, the state's fields (shuffle and "
          f"repeat_mode too); player.js's {len(used)} calls and {len(events)} events all in it; Spotify's queue kept across "
          f"a play; a context from its offset, previous's 3 s rule, Autoplay's own context; relative URLs, plain GETs OK")

    # 8. the debug feed's documents
    sp = Spotify(defaults())
    listing = sp.listing(6, "11ee11ee11ee11ee", None)
    assert [i["key"] for i in listing["items"]] == [t["id"] for t in sp.listed_tracks()] and listing["items"]
    assert len({i["key"] for i in listing["items"]}) == len(listing["items"]), "a key once (the phone's item key)"
    assert all(i["url"] is None and set(i) == {"key", "title", "url", "hls", "duration_ms", "art"} for i in listing["items"])
    assert listing["items"][0]["title"] == "Stille natt — Ola Fjell, Kari Li"
    long = sp.listing(6, "11ee11ee11ee11ee", 60)
    assert len(long["items"]) == 60 and long["items"][59]["key"] == "FAKEtrack0000000000060"
    library = json.load(open(LIBRARY, encoding="utf-8"))
    six = next(e for e in library["entries"] if e["id"] == 6)
    assert six["source"] == "spotify" and six["items"] == "11ee11ee11ee11ee" and six["spotify"] == "list"
    # entry 7, the artist in context mode (design 26c §5.9): its listing has no items, and its context plays FAKEtrack 1..5
    seven = next(e for e in library["entries"] if e["id"] == 7)
    assert seven["spotify"] == "context" and sp.listing(7, seven["items"], 0)["items"] == []
    assert [t["uri"] for t in sp.context_tracks(seven["target"])] == [fake_track(n)["uri"] for n in range(1, CONTEXT_TRACKS + 1)]
    print("8. --print-listing (the fixture playlist as the server lists it, --tracks 60) for the library snapshot's entry 6, "
          "entry 7's empty context listing (--tracks 0) and its context's tracks OK")
    print("\nFAKE SPOTIFY SELFTEST OK - answers built from the same fixtures the tests read.")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--port", type=int, default=8731, help="(default %(default)s)")
    p.add_argument("--bind", default="127.0.0.1",
                   help="address to listen on (default %(default)s: the emulator's 10.0.2.2 is the host's loopback)")
    p.add_argument("--as", dest="as_user", choices=sorted(USERS), default="parent",
                   help="who the auto-consent logs in (default %(default)s; GET /fake/as?user= switches it)")
    p.add_argument("--deny", action="store_true", help="the consent answers error=access_denied")
    p.add_argument("--rotate", action="store_true", help="every refresh returns a new refresh token")
    p.add_argument("--access-ttl", type=float, default=3600.0, help="seconds an access token lives (default %(default)s)")
    p.add_argument("--expire-refresh", action="store_true", help="every refresh answers 400 invalid_grant")
    p.add_argument("--bad-client", action="store_true", help="the token endpoint answers 400 invalid_client")
    p.add_argument("--snapshot", help="the fixture playlist's snapshot_id")
    p.add_argument("--rate-limit", type=int, default=0, metavar="N", help="every Nth Web API request 429, Retry-After: 2")
    p.add_argument("--quota", action="store_true", help="every Web API request 429 QUOTA_EXCEEDED")
    p.add_argument("--not-premium", action="store_true", help="the play call 403 PREMIUM_REQUIRED, the SDK account_error")
    p.add_argument("--no-eme", action="store_true", help="the SDK's initialization_error")
    p.add_argument("--slow-ready", type=float, default=0.0, metavar="S", help="seconds before the SDK is ready")
    p.add_argument("--token-ttl", type=int, default=0, metavar="S", help="the SDK asks getOAuthToken every S seconds")
    p.add_argument("--track-seconds", type=float, metavar="S", help="every track's length (default: the fixture's, else 20 s)")
    p.add_argument("--elsewhere", type=float, metavar="S", help="a null state S seconds after the first play")
    p.add_argument("--autoplay", action="store_true", help="after the last uri, play a track that isn't in the list")
    p.add_argument("--transfer-in", type=float, metavar="S", help="a foreign track starts S seconds after the first play")
    p.add_argument("--unplayable", default="", metavar="ID,...", help="tracks that answer playback_error and are skipped")
    p.add_argument("--relink", default="", metavar="ID:OTHER,...", help="track ID plays as OTHER with linked_from ID")
    p.add_argument("--shuffle", action="store_true", help="the account's shuffle on until the phone turns it off")
    p.add_argument("--offset-404", action="store_true",
                   help="a context play whose offset isn't in the context answers 404 (else it plays from the top)")
    p.add_argument("--repeat", choices=sorted(REPEAT), default="off", help="the account's repeat until the phone turns it off")
    p.add_argument("--print-listing", type=int, metavar="ENTRY",
                   help="print the fixture playlist's listing for library entry ENTRY (debug-feed/items/ENTRY.json) and exit")
    p.add_argument("--tracks", type=int, metavar="N", help="--print-listing: N generated tracks instead (0: a context's empty list)")
    p.add_argument("--version", default="11ee11ee11ee11ee",
                   help="the listing's version for --print-listing (default %(default)s: the library snapshot's Spotify playlist)")
    p.add_argument("--print-library", action="store_true",
                   help="print the library snapshot (server/testdata/music_library.json: entries 6 and 7 are Spotify's) and exit")
    p.add_argument("--selftest", action="store_true", help="check the fixtures and the fake against itself, then exit")
    args = p.parse_args()
    if args.selftest:
        selftest()
        return
    if args.print_library:
        print(open(LIBRARY, encoding="utf-8").read(), end="")
        return
    sp = Spotify(args)
    if args.print_listing is not None:
        print(json.dumps(sp.listing(args.print_listing, args.version, args.tracks), indent=2, ensure_ascii=False))
        return
    serve(sp, args.bind)
    log(f"fake Spotify on {args.bind}:{args.port}; the PWA consents as {sp.consent}")
    log(f"server: SPOTIFY_ACCOUNTS_BASE=http://127.0.0.1:{args.port} SPOTIFY_API_BASE=http://127.0.0.1:{args.port}")
    log('music app debug feed config.json: {"spotify_base": "http://10.0.2.2:%d"}' % args.port)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
