#!/usr/bin/env python3
# fake-audio.py - a fake podcast host for the music app on the emulator (design 30 §8 check 10;
# docs/testing/emulator.md §6f).
#
# Part of handy (palchrb/vibb-launcher), licensed under the GNU General Public License v3.0 or later
# (LICENSE). Modelled on scripts/fake-storytel.py. Python 3, stdlib only; run it with `python3 -I`.
"""A fake podcast host: silent WAV episodes with Range, optionally a different length per request.

    python3 -I scripts/fake-audio.py                         # port 8731, episodes ep1..ep3 of 20 MB
    python3 -I scripts/fake-audio.py --vary                  # every request serves another total length
    python3 -I scripts/fake-audio.py --print-listing 6 > debug-feed/items/6.json
    python3 -I scripts/fake-audio.py --selftest

Design 30 §1.6 assumes a source serves the same bytes for every request; a host that stitches ads per
request breaks that, and the app's length guard (`MediaCache.LengthGuard`) catches it by the total
length of a resumed request. `--vary` is such a host: request n serves a file n x --step bytes longer
than the first, so a stream paused long enough to drop its connection resumes against another total
(a synthetic 416 in the app: the row goes, the episode plays on uncached, the next sync's orphan sweep
empties its bytes - §8 check 10). Without it the host is honest: a resumed stream is written on and,
played to its end, settles into a copy (check 5).

    GET /audio/<key>.wav     a silent WAV of --mb MB; Range (206, 416 past the end), Content-Length always;
                             --vary: the n-th request's file is n x --step bytes longer
    HEAD /audio/<key>.wav    the same headers

The library entry for it is a `lan` RSS entry (a private address is refused for a public one, 21b
§2.7): the emulator reaches the host's loopback as 10.0.2.2. The log shows every request with its
Range and the total length served.
"""

import argparse
import http.client
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

T0 = time.monotonic()
RATE = 22050          # Hz, 16-bit mono: 44 100 bytes a second of silence
HEADER = 44
KEY = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def log(msg):
    print(f"[{time.monotonic() - T0:7.1f}] {msg}", flush=True)


def wav_header(size):
    """The 44-byte RIFF header of a silent 16-bit mono PCM file of `size` bytes in all."""
    data = size - HEADER
    return (b"RIFF" + (size - 8).to_bytes(4, "little") + b"WAVEfmt " + (16).to_bytes(4, "little")
            + (1).to_bytes(2, "little") + (1).to_bytes(2, "little") + RATE.to_bytes(4, "little")
            + (RATE * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
            + b"data" + data.to_bytes(4, "little"))


def duration_ms(size):
    return (size - HEADER) * 1000 // (RATE * 2)


class Host:
    """The fake's state: the episodes and how many requests each has had."""

    def __init__(self, args):
        self.keys = [k for k in (args.episodes or "").split(",") if k]
        for k in self.keys:
            if not KEY.match(k):
                raise SystemExit(f"--episodes: {k!r} is not an item key")
        self.size = HEADER + int(args.mb * 1_000_000) // 2 * 2
        self.vary, self.step = args.vary, args.step // 2 * 2
        self.advertise, self.port = args.advertise, args.port
        self.lock = threading.Lock()
        self.requests = {}      # key -> requests served so far

    def size_now(self, key):
        """The total length this request serves: the same every time, or one --step longer each time (--vary)."""
        with self.lock:
            n = self.requests.get(key, 0)
            self.requests[key] = n + 1
        return self.size + (n * self.step if self.vary else 0)

    def listing(self, entry, version):
        """A listing document (design 21b §2.4) for the debug feed: the episodes oldest first."""
        return {"v": 1, "entry": entry, "version": version, "items": [
            {"key": k, "title": f"Episode {i + 1}", "url": f"http://{self.advertise}:{self.port}/audio/{k}.wav",
             "hls": False, "duration_ms": duration_ms(self.size), "art": None}
            for i, k in enumerate(self.keys)]}


def handler_for(host):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send(self, status, headers=()):
            self.send_response(status)
            for k, v in headers:
                self.send_header(k, v)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_HEAD(self):
            self.audio(body=False)

        def do_GET(self):
            self.audio(body=True)

        def audio(self, body):
            m = re.match(r"^/audio/([^/?]+)\.wav(\?.*)?$", self.path)
            if not m or m.group(1) not in host.keys:
                log(f"{self.command} {self.path}: 404")
                return self.send(404)
            key = m.group(1)
            size = host.size_now(key)
            rng = self.headers.get("Range")
            start, end, status = 0, size - 1, 200
            if rng:
                r = re.match(r"^bytes=(\d+)-(\d*)$", rng.strip())
                if not r:
                    return self.send(416, [("Content-Range", f"bytes */{size}")])
                start = int(r.group(1))
                end = min(int(r.group(2)), size - 1) if r.group(2) else size - 1
                if start >= size or start > end:
                    log(f"{key}: 416 (Range {rng}, {size} bytes)")
                    return self.send(416, [("Content-Range", f"bytes */{size}")])
                status = 206
            log(f"{self.command} {key}: {status} Range {rng or '-'} total {size}")
            self.send_response(status)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if not body:
                return
            header = wav_header(size)
            zeros = bytes(1 << 16)
            pos = start
            try:
                while pos <= end:
                    stop = min(end + 1, pos + len(zeros))
                    chunk = header[pos:min(stop, HEADER)] if pos < HEADER else zeros[:stop - pos]
                    self.wfile.write(chunk)
                    pos += len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                log(f"{key}: the client went away at {pos} bytes")
                self.close_connection = True

    return Handler


def serve(host, bind):
    srv = ThreadingHTTPServer((bind, host.port), handler_for(host))
    host.port = srv.server_address[1]   # port 0 -> the one the system chose (the selftest)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# --- the selftest ---------------------------------------------------------------------------------

def selftest():
    def start(**over):
        ns = argparse.Namespace(episodes="ep1,ep2", mb=0.2, vary=False, step=4096, advertise="127.0.0.1", port=0)
        for k, v in over.items():
            setattr(ns, k, v)
        host = Host(ns)
        return host, serve(host, "127.0.0.1")

    def get(host, path, headers=None, method="GET"):
        conn = http.client.HTTPConnection("127.0.0.1", host.port, timeout=10)
        conn.request(method, path, headers=headers or {})
        r = conn.getresponse()
        body = r.read()
        conn.close()
        return r.status, dict(r.getheaders()), body

    host, srv = start()
    try:
        # 1. an honest host: the same file every time, Range resumes, 416 past the end
        status, headers, body = get(host, "/audio/ep1.wav")
        size = host.size
        assert status == 200 and len(body) == size and int(headers["Content-Length"]) == size, (status, len(body))
        assert body[:4] == b"RIFF" and body[8:12] == b"WAVE" and body[HEADER:] == bytes(size - HEADER)
        status, headers, part = get(host, "/audio/ep1.wav", {"Range": "bytes=1000-"})
        assert status == 206 and part == body[1000:] and headers["Content-Range"] == f"bytes 1000-{size - 1}/{size}", headers
        status, headers, part = get(host, "/audio/ep1.wav?utm=x", {"Range": "bytes=10-19"})
        assert status == 206 and len(part) == 10 and headers["Content-Range"] == f"bytes 10-19/{size}", "a query is the same file"
        assert get(host, "/audio/ep1.wav", {"Range": f"bytes={size}-"})[0] == 416
        assert get(host, "/audio/nope.wav")[0] == 404
        status, headers, body = get(host, "/audio/ep2.wav", method="HEAD")
        assert status == 200 and body == b"" and int(headers["Content-Length"]) == size
        listing = host.listing(6, "00ff00ff00ff00ff")
        assert [i["key"] for i in listing["items"]] == ["ep1", "ep2"] and listing["items"][0]["url"].endswith("/audio/ep1.wav")
        assert listing["items"][0]["duration_ms"] == duration_ms(size) and listing["v"] == 1
        print("1. the honest host: a silent WAV, Range 206 (a query too), 416 past the end, HEAD, the listing OK")
    finally:
        srv.shutdown()

    # 2. --vary: every request another total, the header's sizes with it - a resume sees another length
    host, srv = start(vary=True, step=4096)
    try:
        status, headers, body = get(host, "/audio/ep1.wav")
        first = len(body)
        status, headers, part = get(host, "/audio/ep1.wav", {"Range": "bytes=1000-"})
        total = int(headers["Content-Range"].rsplit("/", 1)[1])
        assert status == 206 and total == first + 4096 and len(part) == total - 1000, (first, headers)
        status, headers, body = get(host, "/audio/ep1.wav")
        assert len(body) == first + 2 * 4096 and int.from_bytes(body[40:44], "little") == len(body) - HEADER, "a valid WAV each time"
        status, headers, _ = get(host, "/audio/ep2.wav", {"Range": "bytes=0-9"})
        assert headers["Content-Range"] == f"bytes 0-9/{first}", "counted per episode"
        print("2. --vary: request n serves n x --step more bytes, per episode, each a valid WAV OK")
    finally:
        srv.shutdown()
    print("\nFAKE AUDIO SELFTEST OK")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--port", type=int, default=8731, help="(default %(default)s)")
    p.add_argument("--bind", default="127.0.0.1",
                   help="address to listen on (default %(default)s: the emulator's 10.0.2.2 is the host's loopback)")
    p.add_argument("--advertise", default="10.0.2.2",
                   help="the host the listing's urls name, as the emulator sees it (default %(default)s)")
    p.add_argument("--episodes", default="ep1,ep2,ep3", help="item keys, oldest first (default %(default)s)")
    p.add_argument("--mb", type=float, default=20.0, help="each episode's size in MB (default %(default)s)")
    p.add_argument("--vary", action="store_true", help="serve another total length at every request (ads stitched per request)")
    p.add_argument("--step", type=int, default=65536, help="--vary's growth per request in bytes (default %(default)s)")
    p.add_argument("--print-listing", type=int, metavar="ENTRY",
                   help="print the listing of these episodes for library entry ENTRY (debug-feed/items/ENTRY.json) and exit")
    p.add_argument("--version", default="00aa00aa00aa00aa", help="the listing's version for --print-listing (default %(default)s)")
    p.add_argument("--selftest", action="store_true", help="check the fake against itself, then exit")
    args = p.parse_args()
    if args.selftest:
        selftest()
        return
    host = Host(args)
    if args.print_listing is not None:
        json.dump(host.listing(args.print_listing, args.version), sys.stdout, indent=2)
        print()
        return
    srv = serve(host, args.bind)
    log(f"fake podcast host on {args.bind}:{host.port}, episodes {','.join(host.keys)} of {host.size} bytes"
        + (f", --vary +{host.step} bytes a request" if host.vary else ""))
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
