#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A fake captive portal for the emulator checks of design 102's Wi-Fi sign-in window (docs/testing/emulator.md
§6ap, E1-E13). Python's standard library only; no network of its own beyond the port it listens on.

One server answers two names (by the Host header), both pointing at the VM from the emulator:

- the portal, PORTAL (default ``10-0-2-2.nip.io``), its first page's site;
- another site, OTHER (default ``10.0.2.2.sslip.io``: another registrable domain, the same server).

Point Android's probe at it (``captive_portal_http_url`` and ``captive_portal_https_url``/``fallback_url`` to
``http://PORTAL:PORT/generate_204``, ``captive_portal_use_https 0``, mobile data off). Always through a hostname, never
``10.0.2.2``: the window's own lookup on the bound network is part of the check (design 102 §11).

``/generate_204`` answers 204 once ``POST /accept`` was made (``/reset`` forgets it); before that, by ``--mode``:

- ``portal``     a 302 to the login page (E1, E3, E6, E10, E12, E13);
- ``meta``       a 200 whose ``<meta http-equiv=refresh>`` goes to the login page (E5);
- ``script``     a 200 whose onload ``location`` goes to the login page (E5);
- ``soft``       as ``portal``, and never 204: a portal that intercepts port 80 only, so Vibb's https check passes
                 (E4, closes as ``passes``);
- ``hotspot``    a 302 to ``--search-url`` (a search page; E4: the kid's own hotspot, closes as ``passes``);
- ``walled``     as ``portal`` with a "Log in with Google" link: a Google walled garden. Its point (gstatic passes,
                 nothing else does) needs ``cp.cloudflare.com`` unreachable from the emulator; E4 says how, and
                 ``login_rules.json``'s ``passes`` rows pin the rule either way;
- ``iframe``     the login page holds a full-page iframe of OTHER (E5: refused and counted);
- ``redirector`` the login page links to its own ``/out?to=`` (same site) which 302s to OTHER (E5: refused);
- ``formpost``   the login form posts to ``/post-out``, which 302s to OTHER (E5: allowed, OTHER becomes a portal
                 site).

Every login page also has a same-site link (``/terms``), a link straight to OTHER (refused), a ``target=_blank`` one,
a ``tel:`` link and the Accept button. OTHER's pages link back to the portal and to ``/away``, a 302 to a third site
(E5: a leaf's redirect onward is refused).

Usage: ``python3 -I scripts/fake-portal.py --mode portal --port 8105`` (``--selftest`` runs every mode on loopback).
"""

import argparse
import html
import http.client
import http.server
import sys
import threading
import urllib.parse

MODES = ("portal", "meta", "script", "soft", "hotspot", "walled", "iframe", "redirector", "formpost")
THIRD_SITE = "http://example.com/"


class Portal:
    """The fake's state: the mode, the names, and whether the kid pressed Accept."""

    def __init__(self, mode, portal, other, port, search_url):
        self.mode = mode
        self.portal = portal
        self.other = other
        self.port = port
        self.search_url = search_url
        self.accepted = False
        self.quiet = False
        self.lock = threading.Lock()

    def url(self, host, path):
        return "http://%s:%d%s" % (host, self.port, path)


def page(title, body, head=""):
    return ("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width'>"
            "<title>%s</title>%s</head><body><h1>%s</h1>%s</body></html>" % (title, head, title, body)).encode()


def answer(state, method, host, path):
    """The fake's answer to one request: ``(status, headers, body)``. Pure but for ``state.accepted``."""
    name = (host or "").split(":")[0].lower()
    parsed = urllib.parse.urlsplit(path)
    route = parsed.path
    query = urllib.parse.parse_qs(parsed.query)
    login = state.url(state.portal, "/login")
    other = state.url(state.other, "/page")

    if route == "/generate_204":
        with state.lock:
            if state.accepted and state.mode != "soft":
                return 204, {}, b""
        if state.mode == "hotspot":
            return 302, {"Location": state.search_url}, b""
        if state.mode == "meta":
            return 200, {}, page("Redirecting", "", "<meta http-equiv=refresh content='0; url=%s'>" % login)
        if state.mode == "script":
            return 200, {}, page("Redirecting", "<script>onload=function(){location='%s'}</script>" % login)
        return 302, {"Location": login}, b""
    if route == "/reset":
        with state.lock:
            state.accepted = False
        return 200, {}, page("Reset", "")

    if name == state.other:
        if route == "/away":
            return 302, {"Location": THIRD_SITE}, b""
        if route in ("/page", "/welcome"):
            return 200, {}, page("Another site", "<p><a id=back href='%s'>Back to the sign-in</a></p>"
                                 "<p><a id=away href='/away'>Somewhere else</a></p>" % login)
        return 404, {}, page("Not here", "")

    if route == "/login":
        links = ("<p><a id=terms href='/terms'>Terms</a></p>"
                 "<p><a id=out href='%s'>Another site</a></p>"
                 "<p><a id=blank target=_blank href='%s'>Another site in a new window</a></p>"
                 "<p><a id=tel href='tel:+4712345678'>Call the front desk</a></p>" % (other, other))
        action = "/post-out" if state.mode == "formpost" else "/accept"
        form = "<form method=post action='%s'><button id=accept>Accept</button></form>" % action
        extra = ""
        if state.mode == "walled":
            extra = "<p><a id=google href='https://accounts.google.com/'>Log in with Google</a></p>"
        elif state.mode == "iframe":
            extra = "<iframe id=frame src='%s' style='width:100%%;height:60vh'></iframe>" % other
        elif state.mode == "redirector":
            extra = "<p><a id=redirector href='/out?to=%s'>Our partner</a></p>" % urllib.parse.quote(other, safe="")
        return 200, {}, page("Sign in to Fake Wi-Fi", form + links + extra)
    if route == "/terms":
        return 200, {}, page("Terms", "<p><a href='/login'>Back</a></p>")
    if route == "/out":
        target = (query.get("to") or [""])[0]
        if urllib.parse.urlsplit(target).hostname != state.other:
            return 400, {}, page("Not a partner", "")
        return 302, {"Location": target}, b""
    if route == "/accept" and method == "POST":
        with state.lock:
            state.accepted = True
        return 302, {"Location": "/done"}, b""
    if route == "/post-out" and method == "POST":
        with state.lock:
            state.accepted = True
        return 302, {"Location": state.url(state.other, "/welcome")}, b""
    if route == "/done":
        return 200, {}, page("You're online", "<p>Have a nice stay.</p>")
    return 404, {}, page("Not here", html.escape(route))


def handler_for(state):
    class Handler(http.server.BaseHTTPRequestHandler):
        def respond(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(min(length, 65536))
            status, headers, body = answer(state, self.command, self.headers.get("Host"), self.path)
            self.send_response(status)
            for key, value in headers.items():
                self.send_header(key, value)
            if body:
                self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        do_GET = respond
        do_POST = respond

        def log_message(self, fmt, *args):
            if not state.quiet:
                sys.stderr.write("fake-portal %s %s\n" % (self.address_string(), fmt % args))

    return Handler


def serve(state, bind):
    server = http.server.ThreadingHTTPServer((bind, state.port), handler_for(state))
    if state.port == 0:
        state.port = server.server_address[1]
    return server


def selftest():
    """Every mode on loopback: the probe's answer before and after Accept, and the pages' moves."""
    def request(state, method, host, path):
        conn = http.client.HTTPConnection("127.0.0.1", state.port, timeout=5)
        conn.request(method, path, headers={"Host": "%s:%d" % (host, state.port), "Content-Length": "0"})
        res = conn.getresponse()
        body = res.read().decode()
        conn.close()
        return res.status, res.getheader("Location"), body

    for mode in MODES:
        state = Portal(mode, "portal.test", "other.test", 0, "https://search.example/")
        state.quiet = True
        server = serve(state, "127.0.0.1")
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            login = state.url("portal.test", "/login")
            status, location, body = request(state, "GET", "portal.test", "/generate_204")
            if mode == "hotspot":
                assert (status, location) == (302, "https://search.example/"), (mode, status, location)
            elif mode == "meta":
                assert status == 200 and "http-equiv=refresh" in body and login in body, mode
            elif mode == "script":
                assert status == 200 and "location='%s'" % login in body, mode
            else:
                assert (status, location) == (302, login), (mode, status, location)
            status, _, body = request(state, "GET", "portal.test", "/login")
            assert status == 200 and "id=accept" in body and "tel:" in body and "target=_blank" in body, mode
            assert ("<iframe" in body) == (mode == "iframe"), mode
            assert ("id=redirector" in body) == (mode == "redirector"), mode
            assert ("accounts.google.com" in body) == (mode == "walled"), mode
            other = state.url("other.test", "/page")
            if mode == "redirector":
                path = "/out?to=" + urllib.parse.quote(other, safe="")
                assert request(state, "GET", "portal.test", path)[:2] == (302, other), mode
                assert request(state, "GET", "portal.test", "/out?to=http%3A%2F%2Fevil.test%2F")[0] == 400
            if mode == "formpost":
                status, location, _ = request(state, "POST", "portal.test", "/post-out")
                assert (status, location) == (302, state.url("other.test", "/welcome")), mode
            else:
                assert request(state, "POST", "portal.test", "/accept")[:2] == (302, "/done"), mode
            status, location, _ = request(state, "GET", "portal.test", "/generate_204")
            if mode == "soft":
                assert status == 302, "a soft portal never validates"
            elif mode == "hotspot":
                assert status == 204, mode
            else:
                assert status == 204, (mode, status)
            request(state, "GET", "portal.test", "/reset")
            assert request(state, "GET", "portal.test", "/generate_204")[0] != 204, mode
            assert request(state, "GET", "other.test", "/away")[:2] == (302, THIRD_SITE), mode
            assert "Back to the sign-in" in request(state, "GET", "other.test", "/page")[2], mode
            assert request(state, "GET", "other.test", "/login")[0] == 404, mode
        finally:
            server.shutdown()
            server.server_close()
    print("fake-portal selftest: %d modes ok" % len(MODES))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--mode", choices=MODES, default="portal")
    parser.add_argument("--port", type=int, default=8105)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--portal", default="10-0-2-2.nip.io", help="the portal's host name")
    parser.add_argument("--other", default="10.0.2.2.sslip.io", help="another site's host name")
    parser.add_argument("--search-url", default="https://duckduckgo.com/", help="hotspot mode's 302")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return
    state = Portal(args.mode, args.portal.lower(), args.other.lower(), args.port, args.search_url)
    server = serve(state, args.bind)
    sys.stderr.write("fake-portal: %s mode on %s:%d (portal %s, other %s)\n"
                     % (args.mode, args.bind, state.port, state.portal, state.other))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
