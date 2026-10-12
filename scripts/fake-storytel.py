#!/usr/bin/env python3
# fake-storytel.py - a fake Storytel for the music app on the emulator (design 26b §4.6, step 3).
#
# Part of handy (palchrb/vibb-launcher), licensed under the GNU General Public License v3.0 or later
# (LICENSE). Modelled on palchrb/vibb's test fakes and its rule that both fakes answer from ONE source
# of truth (tests/sonos_contract.py; MIT License, Copyright (c) 2026 palchrb; see NOTICE.md), and on
# scripts/fake-sonos.py. Python 3, stdlib only; run it with `python3 -I`.
"""A fake Storytel: the login, the asset call's 302 and a CDN serving silent WAV books.

    python3 -I scripts/fake-storytel.py                          # port 8730, books 111:20,222:5,333:5 (MB)
    python3 -I scripts/fake-storytel.py --drop-at 40 --token-ttl 30
    python3 -I scripts/fake-storytel.py --locked 222
    python3 -I scripts/fake-storytel.py --refuse
    python3 -I scripts/fake-storytel.py --print-listing 5 > debug-feed/items/5.json
    python3 -I scripts/fake-storytel.py --selftest

Every answer comes from the fixtures in music/app/src/test/resources/storytel/ - the same files the JVM
tests read (StorytelApiTest): the login is accepted only for login_request.json's email and `pwd` hex
(stdlib Python has no AES, so the hex is compared as the fixture spells it; aes_vectors.json pins it),
answered with login_ok.json's (or login_no_jwt.json's) body; the asset call answers asset_302.json's
status with a Location shaped like the fixture's (`consumableId` and `token` in the query) and
asset_locked.json's status for a locked book. Every value is fake (FAKE-JWT-1, FAKE-SIG-<n>,
k@example.invalid): nothing here may look like a real account.

    GET /api/login.action?m=1&uid=<email>&pwd=<hex>   200 + {"accountInfo": {"jwt": "FAKE-JWT-1"}};
                                                       --refuse (or another login): 403; --no-jwt: 200 without one
    GET /assets/v2/consumables/<cid>/abook            the bearer checked (401 without it); 302 to
                                                       http://<advertise>:<port>/cdn/<cid>.wav?consumableId=<cid>&token=FAKE-SIG-<n>
                                                       (--slow-mint S: after S seconds); a --locked cid: 403, no Location
    GET /cdn/<cid>.wav                                 a silent WAV of --books cid:MB; Range (206, 416 past the end);
                                                       the token valid --token-ttl seconds after its mint, then 403;
                                                       --drop-at P closes each book's first transfer at P %

The log shows every request with its Range and token (fake values), so a resumed book reads as
"Range bytes=N- token=FAKE-SIG-2" after "dropped at 40 %".
"""

import argparse
import http.client
import json
import os
import re
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "..", "music", "app", "src", "test", "resources", "storytel")
T0 = time.monotonic()
RATE = 22050          # Hz, 16-bit mono: 44 100 bytes a second of silence
HEADER = 44
CID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def log(msg):
    print(f"[{time.monotonic() - T0:7.1f}] {msg}", flush=True)


def fixture(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
        return json.load(f)


def wav_header(size):
    """The 44-byte RIFF header of a silent 16-bit mono PCM file of `size` bytes in all."""
    data = size - HEADER
    return (b"RIFF" + (size - 8).to_bytes(4, "little") + b"WAVEfmt " + (16).to_bytes(4, "little")
            + (1).to_bytes(2, "little") + (1).to_bytes(2, "little") + RATE.to_bytes(4, "little")
            + (RATE * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
            + b"data" + data.to_bytes(4, "little"))


def duration_ms(size):
    return (size - HEADER) * 1000 // (RATE * 2)


class Storytel:
    """The fake's state: the fixtures, the books, the minted tokens."""

    def __init__(self, args):
        self.request = fixture("login_request.json")
        self.ok = fixture("login_ok.json")
        self.no_jwt_answer = fixture("login_no_jwt.json")
        self.asset = fixture("asset_302.json")
        self.locked_answer = fixture("asset_locked.json")
        self.jwt = self.ok["body"]["accountInfo"]["jwt"]
        self.books = {}
        for part in filter(None, (args.books or "").split(",")):
            cid, _, mb = part.partition(":")
            if not CID.match(cid):
                raise SystemExit(f"--books: {cid!r} is not a consumable id")
            self.books[cid] = HEADER + int(float(mb or "1") * 1_000_000) // 2 * 2
        self.locked = set(filter(None, (args.locked or "").split(",")))
        self.refuse, self.no_jwt = args.refuse, args.no_jwt
        self.token_ttl, self.drop_at, self.slow_mint = args.token_ttl, args.drop_at, args.slow_mint
        self.advertise, self.port = args.advertise, args.port
        self.lock = threading.Lock()
        self.minted = {}        # token -> (cid, monotonic at mint)
        self.serial = 0
        self.dropped = set()    # books whose first transfer was cut already
        self.logins = 0

    def location(self, cid):
        """A CDN URL shaped like asset_302.json's Location: the same query names, our host."""
        names = list(urllib.parse.parse_qs(urllib.parse.urlsplit(self.asset["location"]).query))
        assert names == ["consumableId", "token"], names
        with self.lock:
            self.serial += 1
            token = f"FAKE-SIG-{self.serial}"
            self.minted[token] = (cid, time.monotonic())
        query = urllib.parse.urlencode({"consumableId": cid, "token": token})
        return f"http://{self.advertise}:{self.port}/cdn/{cid}.wav?{query}", token

    def listing(self, entry, version):
        """A listing document (design 26b §2.4: one item per book, url null) for the debug feed."""
        return {"v": 1, "entry": entry, "version": version, "items": [
            {"key": cid, "title": f"Bok {i + 1}", "url": None, "hls": False, "duration_ms": duration_ms(size), "art": None}
            for i, (cid, size) in enumerate(self.books.items())]}


def handler_for(st):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send(self, status, body=b"", headers=()):
            self.send_response(status)
            for k, v in headers:
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def do_GET(self):
            url = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(url.query)
            m = re.match(r"^/assets/v2/consumables/([^/]+)/abook$", url.path)
            c = re.match(r"^/cdn/([^/]+)\.wav$", url.path)
            if url.path == "/api/login.action":
                return self.login(query)
            if m:
                return self.mint(m.group(1))
            if c:
                return self.cdn(c.group(1), query)
            log(f"GET {url.path}: 404")
            self.send(404)

        def login(self, query):
            st.logins += 1
            who = query.get("uid", [""])[0]
            known = who == st.request["email"] and query.get("pwd", [""])[0] == st.request["pwd"] \
                and query.get("m", [""])[0] == "1"
            if st.refuse or not known:
                log(f"login #{st.logins}: 403 ({'--refuse' if st.refuse else 'not the fixture login'})")
                return self.send(403, b'{"error":"refused"}', [("Content-Type", "application/json")])
            answer = st.no_jwt_answer if st.no_jwt else st.ok
            log(f"login #{st.logins}: {answer['status']}{' without a jwt' if st.no_jwt else ''}")
            self.send(answer["status"], json.dumps(answer["body"]).encode(), [("Content-Type", "application/json")])

        def mint(self, cid):
            if self.headers.get("Authorization") != f"Bearer {st.jwt}":
                log(f"asset {cid}: 401 (no bearer or another one)")
                return self.send(401)
            if cid in st.locked or cid not in st.books:
                status = st.locked_answer["status"]
                log(f"asset {cid}: {status} ({'locked' if cid in st.locked else 'no such book'}), no Location")
                return self.send(status)
            if st.slow_mint:
                time.sleep(st.slow_mint)
            location, token = st.location(cid)
            log(f"asset {cid}: {st.asset['status']} -> /cdn/{cid}.wav token={token}")
            self.send(st.asset["status"], headers=[("Location", location)])

        def cdn(self, cid, query):
            token = query.get("token", [""])[0]
            rng = self.headers.get("Range")
            with st.lock:
                minted = st.minted.get(token)
            if cid not in st.books:
                log(f"cdn {cid}: 404")
                return self.send(404)
            if minted is None or minted[0] != cid:
                log(f"cdn {cid}: 403 (token={token or '-'} unknown)")
                return self.send(403)
            if st.token_ttl and time.monotonic() - minted[1] > st.token_ttl:
                log(f"cdn {cid}: 403 (token={token} expired)")
                return self.send(403)
            size = st.books[cid]
            start, end, status = 0, size - 1, 200
            if rng:
                r = re.match(r"^bytes=(\d+)-(\d*)$", rng.strip())
                if not r:
                    return self.send(416, headers=[("Content-Range", f"bytes */{size}")])
                start = int(r.group(1))
                end = min(int(r.group(2)), size - 1) if r.group(2) else size - 1
                if start >= size or start > end:
                    log(f"cdn {cid}: 416 (Range {rng}, {size} bytes)")
                    return self.send(416, headers=[("Content-Range", f"bytes */{size}")])
                status = 206
            log(f"cdn {cid}: {status} Range {rng or '-'} token={token} ({end - start + 1} bytes)")
            self.send_response(status)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            cut = None
            with st.lock:
                if st.drop_at and cid not in st.dropped:
                    st.dropped.add(cid)
                    cut = size * st.drop_at // 100
            header = wav_header(size)
            pos = start
            zeros = bytes(1 << 16)
            try:
                while pos <= end:
                    if cut is not None and pos >= cut:
                        log(f"cdn {cid}: dropped at {st.drop_at} % ({pos} bytes)")
                        self.close_connection = True
                        self.connection.shutdown(2)
                        return
                    stop = min(end + 1, pos + len(zeros), cut if cut is not None and cut > pos else end + 1)
                    if pos < HEADER:
                        chunk = header[pos:min(stop, HEADER)]
                    else:
                        chunk = zeros[:stop - pos]
                    self.wfile.write(chunk)
                    pos += len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                log(f"cdn {cid}: the client went away at {pos} bytes")
                self.close_connection = True

    return Handler


def serve(st, bind):
    srv = ThreadingHTTPServer((bind, st.port), handler_for(st))
    st.port = srv.server_address[1]   # port 0 -> the one the system chose (the selftest)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# --- the selftest ---------------------------------------------------------------------------------

def selftest():
    # 1. The fixtures the JVM tests read hold what this fake answers from, and agree with each other.
    request, ok, no_jwt = fixture("login_request.json"), fixture("login_ok.json"), fixture("login_no_jwt.json")
    asset, locked, vectors = fixture("asset_302.json"), fixture("asset_locked.json"), fixture("aes_vectors.json")
    assert request["query"] == urllib.parse.urlencode({"m": "1", "uid": request["email"], "pwd": request["pwd"]}), request
    assert {"password": request["password"], "hex": request["pwd"]} in vectors["vectors"], "the pwd is an AES vector"
    assert ok["status"] == 200 and ok["body"]["accountInfo"]["jwt"].startswith("FAKE-JWT-")
    assert no_jwt["status"] == 200 and "jwt" not in no_jwt["body"]["accountInfo"]
    assert 300 <= asset["status"] < 400 and urllib.parse.parse_qs(urllib.parse.urlsplit(asset["location"]).query)["token"][0].startswith("FAKE-SIG-")
    assert locked["status"] == 403 and locked["location"] is None
    for name in os.listdir(FIXTURES):
        text = open(os.path.join(FIXTURES, name), encoding="utf-8").read()
        assert "eyJ" not in text and "@" not in text.replace("k@example.invalid", "").replace("%40", ""), name
    print(f"1. {len(os.listdir(FIXTURES))} fixtures parse, agree with each other and hold fake values only OK")

    def start(**over):
        ns = argparse.Namespace(books="111:0.2,222:0.05,333:0.05", locked="", refuse=False, no_jwt=False, token_ttl=0.0,
                                drop_at=0, slow_mint=0.0, advertise="127.0.0.1", port=0)
        for k, v in over.items():
            setattr(ns, k, v)
        st = Storytel(ns)
        return st, serve(st, "127.0.0.1")

    def get(st, path, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", st.port, timeout=10)
        conn.request("GET", path, headers=headers or {})
        r = conn.getresponse()        # http.client never follows a redirect: the 302 is ours
        body = r.read()
        conn.close()
        return r.status, dict(r.getheaders()), body

    bearer = {"Authorization": f"Bearer {ok['body']['accountInfo']['jwt']}"}
    st, srv = start()
    try:
        # 2. the login: the fixture's exact query, anything else refused
        status, _, body = get(st, "/api/login.action?" + request["query"])
        assert status == 200 and json.loads(body)["accountInfo"]["jwt"] == "FAKE-JWT-1", (status, body)
        wrong = urllib.parse.urlencode({"m": "1", "uid": request["email"], "pwd": vectors["vectors"][1]["hex"]})
        assert get(st, "/api/login.action?" + wrong)[0] == 403
        print("2. the login answers the fixture's login only OK")

        # 3. the asset call: the bearer checked, the 302 captured with the fixture's query shape, a locked book 403
        assert get(st, "/assets/v2/consumables/111/abook")[0] == 401
        status, headers, _ = get(st, "/assets/v2/consumables/111/abook", bearer)
        assert status == asset["status"], status
        loc = urllib.parse.urlsplit(headers["Location"])
        q = urllib.parse.parse_qs(loc.query)
        assert loc.path == "/cdn/111.wav" and q["consumableId"] == ["111"] and q["token"] == ["FAKE-SIG-1"], headers
        assert get(st, "/assets/v2/consumables/999/abook", bearer)[0] == locked["status"]
        print("3. the asset call: bearer, 302 with consumableId and a fake token, unknown book 403 OK")

        # 4. the CDN: the whole book, a WAV of the given size; Range resumes; past the end 416; a stranger's token 403
        cdn = f"{loc.path}?{loc.query}"
        status, headers, body = get(st, cdn)
        size = st.books["111"]
        assert status == 200 and len(body) == size and body[:4] == b"RIFF" and body[8:12] == b"WAVE", (status, len(body))
        assert body[HEADER:] == bytes(size - HEADER), "silence"
        status, headers, part = get(st, cdn, {"Range": "bytes=1000-"})
        assert status == 206 and part == body[1000:] and headers["Content-Range"] == f"bytes 1000-{size - 1}/{size}", headers
        assert get(st, cdn, {"Range": f"bytes={size}-"})[0] == 416
        assert get(st, "/cdn/222.wav?consumableId=222&token=FAKE-SIG-1")[0] == 403, "a token is for its book"
        assert get(st, "/cdn/111.wav?consumableId=111&token=FAKE-SIG-99")[0] == 403
        listing = st.listing(5, "00ff00ff00ff00ff")
        assert [i["key"] for i in listing["items"]] == ["111", "222", "333"] and all(i["url"] is None for i in listing["items"])
        assert listing["items"][0]["duration_ms"] == duration_ms(size)
        print("4. the CDN: a silent WAV, Range 206, 416 past the end, tokens per book; the listing OK")
    finally:
        srv.shutdown()

    # 5. --token-ttl, --drop-at, --slow-mint, --refuse, --no-jwt
    st, srv = start(token_ttl=0.3, drop_at=40, slow_mint=0.3)
    try:
        t0 = time.monotonic()
        loc = urllib.parse.urlsplit(get(st, "/assets/v2/consumables/111/abook", bearer)[1]["Location"])
        assert time.monotonic() - t0 >= 0.3, "--slow-mint"
        cdn = f"{loc.path}?{loc.query}"
        size = st.books["111"]
        conn = http.client.HTTPConnection("127.0.0.1", st.port, timeout=10)
        conn.request("GET", cdn)
        r = conn.getresponse()
        got = b""
        try:
            got = r.read()
        except http.client.IncompleteRead as e:
            got = e.partial
        conn.close()
        assert len(got) == size * 40 // 100, (len(got), size)
        status, _, rest = get(st, cdn, {"Range": f"bytes={len(got)}-"})
        assert status == 206 and len(got) + len(rest) == size, "the second transfer runs to the end"
        time.sleep(0.4)
        assert get(st, cdn, {"Range": f"bytes={len(got)}-"})[0] == 403, "the token expired"
        fresh = urllib.parse.urlsplit(get(st, "/assets/v2/consumables/111/abook", bearer)[1]["Location"])
        assert get(st, f"{fresh.path}?{fresh.query}", {"Range": f"bytes={len(got)}-"})[0] == 206, "a fresh mint resumes"
        print("5a. --slow-mint, --drop-at 40 (the first transfer cut, Range resumes), --token-ttl (403, a re-mint heals) OK")
    finally:
        srv.shutdown()
    for flag, want in (("refuse", 403), ("no_jwt", 200)):
        st, srv = start(**{flag: True})
        try:
            status, _, body = get(st, "/api/login.action?" + request["query"])
            assert status == want and b"FAKE-JWT" not in body, (flag, status, body)
        finally:
            srv.shutdown()
    st, srv = start(locked="222")
    try:
        assert get(st, "/assets/v2/consumables/222/abook", bearer)[0] == locked["status"]
        assert get(st, "/assets/v2/consumables/111/abook", bearer)[0] == asset["status"]
    finally:
        srv.shutdown()
    print("5b. --refuse 403, --no-jwt 200 without a jwt, --locked 222 403 while 111 mints OK")
    print("\nFAKE STORYTEL SELFTEST OK - answers built from the same fixtures the JVM tests read.")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--port", type=int, default=8730, help="(default %(default)s)")
    p.add_argument("--bind", default="127.0.0.1",
                   help="address to listen on (default %(default)s: the emulator's 10.0.2.2 is the host's loopback)")
    p.add_argument("--advertise", default="10.0.2.2",
                   help="the host the CDN Locations name, as the emulator sees it (default %(default)s)")
    p.add_argument("--books", default="111:20,222:5,333:5", help="cid:MB,... the series' books in reading order (default %(default)s)")
    p.add_argument("--locked", default="", help="cids whose asset call answers 403 without a Location: 222,333")
    p.add_argument("--refuse", action="store_true", help="refuse every login (403)")
    p.add_argument("--no-jwt", action="store_true", help="answer the login 200 without a jwt")
    p.add_argument("--token-ttl", type=float, default=0.0, help="seconds a minted token is valid (default: forever)")
    p.add_argument("--drop-at", type=int, default=0, help="close each book's first transfer at this percent")
    p.add_argument("--slow-mint", type=float, default=0.0, help="seconds before every 302 (step 4b's mint in flight)")
    p.add_argument("--print-listing", type=int, metavar="ENTRY",
                   help="print the listing of these books for library entry ENTRY (debug-feed/items/ENTRY.json) and exit")
    p.add_argument("--version", default="00ff00ff00ff00ff",
                   help="the listing's version for --print-listing (default %(default)s: the library snapshot's Storytel entry)")
    p.add_argument("--selftest", action="store_true", help="check the fixtures and the fake against itself, then exit")
    args = p.parse_args()
    if args.selftest:
        selftest()
        return
    st = Storytel(args)
    if args.print_listing is not None:
        print(json.dumps(st.listing(args.print_listing, args.version), indent=2))
        return
    serve(st, args.bind)
    log(f"fake Storytel on {args.bind}:{st.port}, CDN Locations name {args.advertise}:{st.port}; "
        f"books {', '.join(f'{c} ({s // 1_000_000} MB)' for c, s in st.books.items())}")
    log('debug-feed/config.json: {"storytel_base": "http://%s:%d"}' % (args.advertise, st.port))
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
