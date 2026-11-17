#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A fake Matrix homeserver and Web Push sender for design 108's emulator gate (docs/testing/emulator.md §6ay).
Python's standard library only; it talks to the Vibb server's phone listener and nothing else.

The endpoint is the one the phone handed its app (``https://<origin host>/up/<secret>``, read from the app's log). The
script sends every request to ``--base`` (the phone listener, e.g. ``http://127.0.0.1:3109``) with the endpoint's host as
the ``Host`` header, so no DNS name or certificate is needed.

- ``discover ENDPOINT``: ``GET /_matrix/push/v1/notify`` (Element X's gateway discovery);
- ``notify ENDPOINT``: ``POST /_matrix/push/v1/notify`` with Synapse-shaped ``event_id_only`` JSON (``devices[0]`` with
  the pushkey and ``data.cs``; ``--pushkey`` sends another pushkey, e.g. one on another host);
- ``up ENDPOINT``: ``POST`` Web-Push-like bytes (``Content-Encoding: aes128gcm``, random, ``--size`` bytes) to the
  endpoint's path, with ``--ttl``, ``--urgency`` and ``--topic``;
- ``--count N`` sends N, ``--gap-ms`` apart (``--burst N`` = N with no gap); each answer is printed as
  ``<n> <status> <ms> <body>``, then a tally of the statuses.

``--selftest`` runs every command against a loopback recorder and checks the requests (no network). Usage:
``python3 -I scripts/fake-homeserver.py --base http://127.0.0.1:3109 notify https://up.test/up/<secret> --count 2``.
"""
import argparse
import collections
import http.client
import json
import os
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def matrix_body(pushkey, n):
    return {
        "notification": {
            "event_id": "$fakeEvent%04d:example.org" % n,
            "room_id": "!fakeRoom0001:example.org",
            "type": "m.room.message",
            "sender": "@lily:example.org",
            "sender_display_name": "Lily",
            "room_name": "Fake room",
            "content": {"msgtype": "m.text", "body": "never stored"},
            "counts": {"unread": n},
            "prio": "high",
            "devices": [{
                "app_id": "me.vibb.probe.up",
                "pushkey": pushkey,
                "pushkey_ts": 1790000000,
                "data": {"cs": "fake-client-secret"},
            }],
        }
    }


def request(base, host, method, path, body=None, headers=None):
    url = urllib.parse.urlsplit(base)
    conn_class = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    conn = conn_class(url.hostname, url.port, timeout=15)
    hdrs = {"Host": host}
    hdrs.update(headers or {})
    started = time.monotonic()
    try:
        conn.request(method, path, body=body, headers=hdrs)
        res = conn.getresponse()
        text = res.read().decode(errors="replace")
        return res.status, int((time.monotonic() - started) * 1000), text
    finally:
        conn.close()


def run(args, out=print):
    endpoint = urllib.parse.urlsplit(args.endpoint)
    host = endpoint.netloc
    tally = collections.Counter()
    for n in range(1, args.count + 1):
        if args.command == "discover":
            status, ms, text = request(args.base, host, "GET", "/_matrix/push/v1/notify")
        elif args.command == "notify":
            body = json.dumps(matrix_body(args.pushkey or args.endpoint, n)).encode()
            status, ms, text = request(args.base, host, "POST", "/_matrix/push/v1/notify", body,
                                       {"Content-Type": "application/json"})
        else:
            headers = {"Content-Encoding": "aes128gcm", "Content-Type": "application/octet-stream"}
            if args.ttl is not None:
                headers["TTL"] = str(args.ttl)
            if args.urgency:
                headers["Urgency"] = args.urgency
            if args.topic:
                headers["Topic"] = args.topic
            status, ms, text = request(args.base, host, "POST", endpoint.path, os.urandom(args.size), headers)
        tally[status] += 1
        out("%d %d %dms %s" % (n, status, ms, text.strip()[:120]))
        if n < args.count and args.gap_ms:
            time.sleep(args.gap_ms / 1000)
    out("tally " + " ".join("%d:%d" % kv for kv in sorted(tally.items())))
    return tally


def parse(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base", default="http://127.0.0.1:3109")
    p.add_argument("--selftest", action="store_true")
    p.add_argument("command", nargs="?", choices=["discover", "notify", "up"])
    p.add_argument("endpoint", nargs="?")
    p.add_argument("--pushkey")
    p.add_argument("--count", type=int, default=1)
    p.add_argument("--burst", type=int)
    p.add_argument("--gap-ms", type=int, default=0)
    p.add_argument("--size", type=int, default=128)
    p.add_argument("--ttl", type=int)
    p.add_argument("--urgency")
    p.add_argument("--topic")
    args = p.parse_args(argv)
    if args.burst:
        args.count, args.gap_ms = args.burst, 0
    if not args.selftest and (not args.command or not args.endpoint):
        p.error("a command and an endpoint (or --selftest)")
    return args


def selftest():
    seen = []

    class Recorder(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def handle_one(self, method):
            length = int(self.headers.get("Content-Length") or 0)
            seen.append((method, self.path, dict(self.headers), self.rfile.read(length)))
            body = b'{"rejected":[]}' if "matrix" in self.path else b""
            self.send_response(200 if method == "GET" or "matrix" in self.path else 201)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.handle_one("GET")

        def do_POST(self):
            self.handle_one("POST")

    server = ThreadingHTTPServer(("127.0.0.1", 0), Recorder)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % server.server_address[1]
    endpoint = "https://up.test/up/FakeSecretForTheSelftest01"
    quiet = lambda *_: None
    assert run(parse(["--base", base, "discover", endpoint]), quiet) == {200: 1}
    assert run(parse(["--base", base, "notify", endpoint, "--burst", "2"]), quiet) == {200: 2}
    assert run(parse(["--base", base, "up", endpoint, "--ttl", "60", "--urgency", "high", "--topic", "t1",
                      "--size", "4097"]), quiet) == {201: 1}
    server.shutdown()
    get, n1, n2, up = seen
    assert get[0] == "GET" and get[1] == "/_matrix/push/v1/notify" and get[2]["Host"] == "up.test"
    for n, rec in ((1, n1), (2, n2)):
        note = json.loads(rec[3])["notification"]
        assert rec[1] == "/_matrix/push/v1/notify" and rec[2]["Host"] == "up.test"
        assert note["devices"][0]["pushkey"] == endpoint and note["devices"][0]["data"] == {"cs": "fake-client-secret"}
        assert note["counts"]["unread"] == n and note["prio"] == "high"
    assert up[1] == "/up/FakeSecretForTheSelftest01" and len(up[3]) == 4097
    assert up[2]["Content-Encoding"] == "aes128gcm" and up[2]["TTL"] == "60" and up[2]["Urgency"] == "high"
    assert up[2]["Topic"] == "t1" and up[2]["Host"] == "up.test"
    print("selftest ok")


def main(argv):
    args = parse(argv)
    if args.selftest:
        selftest()
        return 0
    run(args)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
