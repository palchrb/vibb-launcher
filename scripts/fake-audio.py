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

Design 39 (episode pictures in the list, the server's art pass for every item; emulator.md §6q):

    python3 -I scripts/fake-audio.py --feed 250 --hls 4    # an RSS feed of 250 items with their own pictures

    GET /feed.xml            --feed N: an RSS feed of N items (f001..fN, oldest first), each with its own
                             itunes:image, every third the channel picture's URL (the server judges it
                             `same` by URL, no request); image URLs name the feed request's Host (the
                             address the server used), enclosures --advertise (the emulator's view);
                             --hls K: item K's enclosure is /audio/fK.m3u8 (listed `hls: true`; nothing
                             plays behind it - a Sonos room leaves it out of its queue)
    GET /img/<n>.png         512 px grey PNGs the server judges `own`: a 9x8 grid of blocks, one per cell
    GET /img/channel.png     of the server's print (`photos::print_image`: grey, 9x8, triangle filter,
                             a bit per horizontal pair), levels 64 apart, seeded per n; the selftest models
                             the print and its filter and checks 12-52 bits, every pair apart, and more than
                             12 bits between any two pictures, the channel picture included
"""

import argparse
import http.client
import json
import random
import re
import struct
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

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


# --- design 39: the feed's pictures ---------------------------------------------------------------

PIC = 512                       # px, the server's art size
LEVELS = (32, 96, 160, 224)     # the blocks' greys: neighbours differ by 64 or more
BLUR = (0.125, 0.75, 0.125)     # the triangle filter's share at 512 -> 9x8: a block 3/4, each neighbour 1/8 per axis
BLUR_MARGIN = 16                # the filtered neighbours stay this far apart (the bits survive the filter and JPEG)
MIN_APART = 13                  # bits between any two pictures (the server's same picture is 5 or fewer)


def bits_of(cells):
    """The server's print of a 9x8 grid of greys: a bit per horizontal pair, left brighter than right."""
    bits = 0
    for y in range(8):
        for x in range(8):
            bits = bits << 1 | (1 if cells[y][x] > cells[y][x + 1] else 0)
    return bits


def blurred(grid, y):
    """Row y of the 9x8 cells as the triangle filter gives them from the blocks (renormalised at the edges)."""
    row = []
    for x in range(9):
        s = wsum = 0.0
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                yy, xx = y + dy, x + dx
                if 0 <= yy < len(grid) and 0 <= xx < 9:
                    w = BLUR[dy + 1] * BLUR[dx + 1]
                    s += w * grid[yy][xx]
                    wsum += w
        row.append(s / wsum)
    return row


def holds(grid, y):
    """Row y's print survives the filter: the same bits as its blocks, every pair BLUR_MARGIN apart."""
    cells = blurred(grid, y)
    return all((cells[x] > cells[x + 1]) == (grid[y][x] > grid[y][x + 1]) and abs(cells[x] - cells[x + 1]) >= BLUR_MARGIN
               for x in range(8))


def grid_for(rnd):
    """A 9x8 grid of block greys, row by row, each row kept only when the one above it still holds."""
    while True:
        grid = []
        for y in range(8):
            for _ in range(200):
                row = [rnd.choice(LEVELS)]
                for _x in range(8):
                    row.append(rnd.choice([g for g in LEVELS if g != row[-1]]))
                grid.append(row)
                if (y == 0 or holds(grid, y - 1)) and (y < 7 or holds(grid, 7)):
                    break
                grid.pop()
            else:
                break
        if len(grid) == 8:
            return grid


def pictures(n):
    """The channel picture (index 0) and items 1..n: seeded per index, each more than MIN_APART - 1 bits from the ones before."""
    grids, prints = [], []
    for i in range(n + 1):
        rnd = random.Random(f"fake-audio-picture-{i}")
        while True:
            grid = grid_for(rnd)
            b = bits_of(grid)
            if 12 <= bin(b).count("1") <= 52 and all(bin(b ^ p).count("1") >= MIN_APART for p in prints):
                break
        grids.append(grid)
        prints.append(b)
    return grids


def png(grid):
    """A 512 px 8-bit grey PNG of the grid's blocks (stdlib zlib/struct)."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    rows = bytearray()
    for py in range(PIC):
        line = grid[py * 8 // PIC]
        rows.append(0)
        rows.extend(line[px * 9 // PIC] for px in range(PIC))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", PIC, PIC, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def feed_key(n):
    return f"f{n:03d}"


class Host:
    """The fake's state: the episodes and how many requests each has had."""

    def __init__(self, args):
        self.keys = [k for k in (args.episodes or "").split(",") if k]
        for k in self.keys:
            if not KEY.match(k):
                raise SystemExit(f"--episodes: {k!r} is not an item key")
        # design 39: the feed's items play from this host too; their pictures are made once
        self.feed = getattr(args, "feed", 0) or 0
        self.hls = getattr(args, "hls", None)
        if self.hls is not None and not 1 <= self.hls <= self.feed:
            raise SystemExit("--hls K needs --feed N with 1 <= K <= N")
        self.keys += [feed_key(n) for n in range(1, self.feed + 1)]
        self.grids = pictures(self.feed) if self.feed else []
        self.pngs = {}
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

    def picture(self, n):
        """Picture n's PNG (0 = the channel's), made once."""
        with self.lock:
            if n not in self.pngs:
                self.pngs[n] = png(self.grids[n])
            return self.pngs[n]

    def feed_xml(self, host_header):
        """The RSS feed (design 39): items oldest first; image URLs on the Host the server used, enclosures on --advertise."""
        img = f"http://{host_header}/img"
        audio = f"http://{self.advertise}:{self.port}/audio"
        out = ['<?xml version="1.0" encoding="UTF-8"?>',
               '<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>',
               "<title>Fake feed with pictures</title>", f'<itunes:image href="{escape(img)}/channel.png"/>']
        for n in range(1, self.feed + 1):
            key = feed_key(n)
            picture = f"{img}/channel.png" if n % 3 == 0 else f"{img}/{n}.png"
            if n == self.hls:
                enclosure = f'<enclosure url="{escape(audio)}/{key}.m3u8" type="application/vnd.apple.mpegurl"/>'
            else:
                enclosure = f'<enclosure url="{escape(audio)}/{key}.wav" type="audio/wav" length="{self.size}"/>'
            date = time.strftime("%a, %d %b %Y %H:%M:%S +0000", time.gmtime(1_767_225_600 + n * 3600))
            out.append(f"<item><title>Bilde {n}</title><guid>{key}</guid>{enclosure}"
                       f'<itunes:image href="{escape(picture)}"/><itunes:duration>{duration_ms(self.size) // 1000}</itunes:duration>'
                       f"<pubDate>{date}</pubDate></item>")
        out.append("</channel></rss>")
        return "\n".join(out).encode()

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
            if host.feed and (self.path == "/feed.xml" or self.path.startswith("/img/")):
                return self.feed_or_picture()
            self.audio(body=True)

        def body(self, status, kind, data):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def feed_or_picture(self):
            if self.path == "/feed.xml":
                where = self.headers.get("Host") or f"127.0.0.1:{host.port}"
                log(f"GET /feed.xml: {host.feed} items, pictures on {where}")
                return self.body(200, "application/rss+xml", host.feed_xml(where))
            m = re.match(r"^/img/(channel|\d+)\.png$", self.path)
            n = None if not m else 0 if m.group(1) == "channel" else int(m.group(1))
            if n is None or n > host.feed:
                log(f"GET {self.path}: 404")
                return self.send(404)
            log(f"GET {self.path}: 200")
            self.body(200, "image/png", host.picture(n))

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
        ns = argparse.Namespace(episodes="ep1,ep2", mb=0.2, vary=False, step=4096, advertise="127.0.0.1", port=0, feed=0, hls=None)
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

    # 3. --feed 250 --hls 4 (design 39): the feed, its addresses, and pictures the server judges `own`
    host, srv = start(feed=250, hls=4, advertise="10.0.2.2")
    try:
        status, headers, body = get(host, "/feed.xml", {"Host": "127.0.0.1:9999"})
        assert status == 200 and headers["Content-Type"] == "application/rss+xml", (status, headers)
        text = body.decode()
        items = re.findall(r"<item>(.*?)</item>", text)
        assert len(items) == 250, len(items)
        for n, item in enumerate(items, start=1):
            image = re.search(r'<itunes:image href="([^"]+)"', item).group(1)
            enclosure = re.search(r'<enclosure url="([^"]+)"', item).group(1)
            want = "http://127.0.0.1:9999/img/" + ("channel.png" if n % 3 == 0 else f"{n}.png")
            assert image == want, (n, image)
            ext = "m3u8" if n == 4 else "wav"
            assert enclosure == f"http://10.0.2.2:{host.port}/audio/{feed_key(n)}.{ext}", (n, enclosure)
        assert '<itunes:image href="http://127.0.0.1:9999/img/channel.png"/>' in text.split("<item>")[0]
        assert get(host, f"/audio/{feed_key(250)}.wav", {"Range": "bytes=0-9"})[0] == 206, "a feed item plays"
        # the pictures: the print from the block levels, every compared pair apart, the filter can't flip a bit
        prints = []
        for n, grid in enumerate(host.grids):
            b = bits_of(grid)
            assert 12 <= bin(b).count("1") <= 52, (n, bin(b).count("1"))
            assert all(abs(row[x] - row[x + 1]) >= 32 for row in grid for x in range(8)), n
            assert all(holds(grid, y) for y in range(8)), n
            assert bits_of([blurred(grid, y) for y in range(8)]) == b, n
            prints.append(b)
        apart = min(bin(a ^ b).count("1") for i, a in enumerate(prints) for b in prints[i + 1:])
        assert apart > 12, apart
        # a PNG of 512 px whose blocks are the grid's (decoded here: the IDAT rows, filter 0)
        for n in (0, 1, 250):
            status, headers, data = get(host, "/img/channel.png" if n == 0 else f"/img/{n}.png")
            assert status == 200 and headers["Content-Type"] == "image/png" and data[:8] == b"\x89PNG\r\n\x1a\n", n
            w, h, depth, colour = struct.unpack(">IIBB", data[16:26])
            assert (w, h, depth, colour) == (PIC, PIC, 8, 0), (w, h, depth, colour)
            idat = data[data.index(b"IDAT") + 4:data.index(b"IEND") - 8]
            raw = zlib.decompress(idat)
            grid = host.grids[n]
            for by in range(8):
                for bx in range(9):
                    py, px = by * 64 + 32, int((bx + 0.5) * PIC / 9)
                    assert raw[py * (PIC + 1) + 1 + px] == grid[by][bx], (n, by, bx)
        assert get(host, "/img/251.png")[0] == 404 and get(host, "/img/x.png")[0] == 404
        print(f"3. --feed 250 --hls 4: the feed on the request's Host, every third the channel picture, item 4 HLS; "
              f"251 pictures, 12-52 bits, pairs apart through the filter, at least {apart} bits between any two OK")
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
    p.add_argument("--feed", type=int, default=0, metavar="N",
                   help="serve /feed.xml: an RSS feed of N items with their own pictures, /img/<n>.png (design 39)")
    p.add_argument("--hls", type=int, metavar="K", help="with --feed: item K's enclosure is an HLS playlist (.m3u8)")
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
