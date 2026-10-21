#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A hands-off Sonos play test for design 53: plays an NRK podcast on a speaker the way vibb's Pi
(`pi/sonosd.py` + `pi/daemon.py`, branch pipewire) and the music app do, while
sonos-ws-probe.py's WebSocket subscription and a 1 s UPnP poll watch, and says at the end how
fast each one saw each step.

The play path, as vibb's `SonosSession.play`: RemoveAllTracksFromQueue, SetAVTransportURI with
the DIDL, Play; a start at 5 s or later is muted across Play -> PLAYING -> Seek (`_hush`,
`_seek_settled`). A step to another episode is a new play (the queue lives in the app, never on
the speaker); the natural end is STOPPED near the end, and the app plays the next one
(`SonosController.advance`). The end is `park`: Stop, then the speaker's own empty queue as its
source (x-rincon-queue:<uid>#0), so its Play button never restarts our episode.

The DIDL is built by vibb's `didl()` (Didl.kt), unchanged. --didl picks which fields go in:
  vibb  (default) what vibb's `_sonos_body` sends a podcast: title, album, the episode's picture
        when it is http; no duration
  app   what the music app's `PlayBodies.build` sent a podcast before design 53-0: title, album, the
        duration; no picture (since 53-0 it sends vibb's: the default, without the picture it has none of)
  bare  the title only

    python3 -m pip install aiohttp
    python3 sonos-play-test.py 192.168.1.16 --dry-run          # NRK only: episodes + DIDL, no speaker
    python3 sonos-play-test.py 192.168.1.16                     # the whole run, ~2 minutes, at volume 1
    python3 sonos-play-test.py 192.168.1.16 --didl app --volume 12
    python3 -I sonos-play-test.py --golden music/app/src/test/resources/sonos   # no speaker, no aiohttp

It REPLACES what the speaker is playing and leaves it stopped (parked). The speaker's volume is
set to 1 (--volume) before the first play and put back at the end. Needs sonos-ws-probe.py next to it.

--golden DIR writes didl_url.xml, didl_nrk.xml and didl_storytel.xml: vibb's DIDL for each kind, with
the per-kind arguments of vibb's `Session.play` and `_sonos_body` (url: title and album, no duration;
nrk_program: the service uri, the show class, the protocol and the desc, no duration; Storytel: the
minted url and the shelf's duration), for the fixed inputs below (no http art, the fallback serial, a
fake signed url). The music app's PlayBodyTest compares `builtDidl(PlayBodies.build(...))` with each,
byte for byte (design 53 §2.1).
"""

import argparse
import asyncio
import importlib.util
import json
import pathlib
import re
import sys
import time
from xml.sax.saxutils import escape

aiohttp = probe = None  # loaded by load_network(): --golden needs neither


def load_network():
    global aiohttp, probe
    try:
        import aiohttp as _aiohttp
    except ImportError:
        sys.exit("needs aiohttp: python3 -m pip install aiohttp (or apt install python3-aiohttp)")
    aiohttp = _aiohttp
    spec = importlib.util.spec_from_file_location("probe", pathlib.Path(__file__).with_name("sonos-ws-probe.py"))
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)


def stamp() -> str:
    return probe.stamp()


def tag(xml: str, name: str) -> str:
    return probe.tag(xml, name)


PSAPI = "https://psapi.nrk.no"

# --- vibb's didl(), field for field (sonosd.py 346-379, Didl.kt) -------------------------------

DIDL_NS = ('xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
           'xmlns:dc="http://purl.org/dc/elements/1.1/" '
           'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
           'xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/"')
MIME = {".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".m4b": "audio/mp4", ".aac": "audio/aac",
        ".flac": "audio/flac", ".wav": "audio/wav", ".ogg": "audio/ogg", ".opus": "audio/ogg"}


def hms(s) -> str:
    s = max(0, int(s or 0))
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


def hms_s(text: str):
    m = re.fullmatch(r"(\d+):(\d+):(\d+(?:\.\d*)?)", (text or "").strip())
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else None


def mime_for(uri: str) -> str:
    from urllib.parse import urlparse
    path = urlparse(uri).path.lower()
    return next((mime for ext, mime in MIME.items() if path.endswith(ext)), "audio/mpeg")


def didl(uri, title, artist=None, album=None, art=None, duration_s=None,
         upnp_class="object.item.audioItem.musicTrack", protocol=None, desc=None) -> str:
    proto = protocol or f"http-get:*:{mime_for(uri)}:*"
    dur = f' duration="{hms(duration_s)}"' if duration_s else ""
    tags = [f"<dc:title>{escape(title or 'Vibb')}</dc:title>"]
    if artist:
        tags.append(f"<dc:creator>{escape(artist)}</dc:creator>")
        tags.append(f"<upnp:artist>{escape(artist)}</upnp:artist>")
    if album:
        tags.append(f"<upnp:album>{escape(album)}</upnp:album>")
    if art:
        tags.append(f"<upnp:albumArtURI>{escape(art)}</upnp:albumArtURI>")
    tags.append(f"<upnp:class>{escape(upnp_class)}</upnp:class>")
    if desc:
        tags.append('<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">'
                    f"{escape(desc)}</desc>")
    tags.append(f'<res protocolInfo="{proto}"{dur}>{escape(uri)}</res>')
    return (f"<DIDL-Lite {DIDL_NS}>" '<item id="vibb-1" parentID="-1" restricted="true">'
            + "".join(tags) + "</item></DIDL-Lite>")


# --- the golden DIDLs (design 53 §2.1): vibb's per-kind arguments, fixed inputs ------------------

NRK_SID = 277
NRK_SVCTYPE = NRK_SID * 256 + 7


def nrk_program_uri(series: str, program_id: str, serial: str) -> str:
    """sonosd.py `nrk_program_uri` with the serial given (no speaker to ask)."""
    from urllib.parse import quote
    return f"x-sonos-http:series%3a{quote(series)}%3a1%3a{program_id}.unknown?sid={NRK_SID}&flags=0&sn={serial}"


def nrk_desc() -> str:
    return f"SA_RINCON{NRK_SVCTYPE}_X_#Svc{NRK_SVCTYPE}-0-Token"


# The same inputs as PlayBodyTest's (SonosFakes' entry and item): change both together.
GOLDEN_TITLE = "Sommerfuglen"
GOLDEN_URL = "https://podkast.example/ep12.mp3"
GOLDEN_URL_ALBUM = "Barnas Supersommer"
GOLDEN_NRK = ("barnas-supersommer", "MKTT81008181", "14", "Barnas supersommer")  # series, program id, serial, album
GOLDEN_STORYTEL_URL = "https://cdn.example.invalid/book?token=FAKE-SIG-1"  # fake: a real one is a bearer
GOLDEN_STORYTEL = ("Kokosbananas", 721.893)  # album, the shelf's dur_s


def goldens() -> dict:
    """vibb's `Session.play` for each `_sonos_body` kind: url and nrk_program carry no duration, Storytel does."""
    series, program_id, serial, nrk_album = GOLDEN_NRK
    st_album, st_dur = GOLDEN_STORYTEL
    return {
        "didl_url.xml": didl(GOLDEN_URL, GOLDEN_TITLE, None, GOLDEN_URL_ALBUM, None, None),
        "didl_nrk.xml": didl(nrk_program_uri(series, program_id, serial), GOLDEN_TITLE, None, nrk_album, None, None,
                             upnp_class="object.item.audioItem.show", protocol="sonos.com-http:*:audio/mpeg:*",
                             desc=nrk_desc()),
        "didl_storytel.xml": didl(GOLDEN_STORYTEL_URL, GOLDEN_TITLE, None, st_album, None, st_dur),
    }


def write_goldens(out: str) -> None:
    folder = pathlib.Path(out)
    folder.mkdir(parents=True, exist_ok=True)
    for name, text in goldens().items():
        (folder / name).write_text(text, encoding="utf-8")  # no trailing newline: compared byte for byte
        print(f"{folder / name} ({len(text)} chars)")


def built_for(ep: dict, mode: str) -> str:
    if mode == "vibb":
        art = ep["art"] if (ep["art"] or "").startswith("http") else None
        return didl(ep["url"], ep["title"], album=ep["album"], art=art)
    if mode == "app":
        return didl(ep["url"], ep["title"], album=ep["album"], duration_s=ep["dur_s"])
    return didl(ep["url"], ep["title"])


# --- NRK: the episodes and their mp3s, as the server's sweep reads psapi ------------------------

def pick_image(images, want=512):
    found = sorted(((i.get("width") or 0, i["url"]) for i in images or [] if i.get("url")))
    return next((u for w, u in found if w >= want), found[-1][1] if found else None)


async def episodes(session, slug: str, n: int) -> list:
    async with session.get(f"{PSAPI}/radio/catalog/podcast/{slug}/episodes",
                           params={"page": "1", "pageSize": str(n + 3), "sort": "asc"}) as r:
        r.raise_for_status()
        page = await r.json()
    out = []
    for e in page.get("_embedded", {}).get("episodes", []):
        key = e["_links"]["self"]["href"].rstrip("/").rsplit("/", 1)[-1]
        async with session.get(f"{PSAPI}/playback/manifest/podcast/{key}") as r:
            if r.status != 200:
                print(f"{stamp()} NRK {key}: manifest {r.status} - skipped")
                continue
            man = await r.json()
        asset = ((man.get("playable") or {}).get("assets") or [{}])[0]
        url = asset.get("url") or ""
        if not url or asset.get("encrypted") or (asset.get("format") or "").lower() == "hls" or ".m3u8" in url:
            print(f"{stamp()} NRK {key}: no plain mp3 - skipped")
            continue
        out.append({"key": key, "title": (e.get("titles") or {}).get("title"), "url": url,
                    "album": (e["_links"].get("series") or {}).get("title"),
                    "art": pick_image(e.get("squareImage") or e.get("image")),
                    "dur_s": e.get("durationInSeconds")})
        if len(out) == n:
            break
    return out


# --- UPnP, as Soap.kt / SoCo send it ------------------------------------------------------------

SERVICES = {"AVTransport": "/MediaRenderer/AVTransport/Control",
            "RenderingControl": "/MediaRenderer/RenderingControl/Control"}


class SoapFault(Exception):
    pass


class Speaker:
    def __init__(self, session, ip):
        self.session, self.base = session, f"http://{ip}:1400"

    async def call(self, service: str, action: str, **args) -> str:
        args = {"InstanceID": "0", **args}
        inner = "".join(f"<{k}>{escape(str(v))}</{k}>" for k, v in args.items())
        body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                f'<u:{action} xmlns:u="urn:schemas-upnp-org:service:{service}:1">{inner}</u:{action}>'
                "</s:Body></s:Envelope>")
        async with self.session.post(self.base + SERVICES[service], data=body.encode(), headers={
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPACTION": f'"urn:schemas-upnp-org:service:{service}:1#{action}"'},
                timeout=aiohttp.ClientTimeout(total=6)) as r:
            text = await r.text()
            if r.status != 200:
                raise SoapFault(f"{action}: HTTP {r.status} errorCode {tag(text, 'errorCode') or '?'}")
            return text

    async def av(self, action, **args):
        return await self.call("AVTransport", action, **args)

    async def state(self):
        info = await self.av("GetTransportInfo")
        pos = await self.av("GetPositionInfo")
        return (tag(info, "CurrentTransportState"), hms_s(tag(pos, "RelTime")),
                hms_s(tag(pos, "TrackDuration")), tag(pos, "TrackURI").replace("&amp;", "&"))


# --- what the watchers saw -----------------------------------------------------------------------

class Watch:
    """The latest state from each side, with when it last changed."""

    def __init__(self):
        self.ws = {"state": None, "pos": None, "at": None}
        self.upnp = {"state": None, "pos": None, "dur": None, "uri": None, "at": None}
        self.ws_log, self.upnp_log = [], []  # (monotonic, state)

    def from_ws(self, header, body):
        if header.get("type") == "playbackStatus":
            st = (body.get("playbackState") or "").replace("PLAYBACK_STATE_", "")
            self.ws.update(state=st, pos=(body.get("positionMillis") or 0) / 1000, at=time.monotonic())
            self.ws_log.append((time.monotonic(), st, self.ws["pos"]))

    def from_upnp(self, st, rel, dur, uri):
        if st != self.upnp["state"] or uri != self.upnp["uri"]:
            self.upnp_log.append((time.monotonic(), st, rel))
        self.upnp.update(state=st, pos=rel, dur=dur, uri=uri, at=time.monotonic())

    @staticmethod
    def first(log, since, want):
        return next((t - since for t, st, _ in log if t >= since and st in want), None)


async def websocket_watch(session, ip, watch: Watch, ready: asyncio.Event, info: dict, verbose):
    import ssl
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    url = f"wss://{ip}:1443/websocket/api"
    async with session.ws_connect(url, headers={"X-Sonos-Api-Key": probe.API_KEY}, protocols=(probe.PROTOCOL,),
                                  ssl=ctx, heartbeat=30) as ws:
        await probe.command(ws, {})
        household = player = None
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    print(f"{stamp()} WS {msg.type.name}")
                    break
                continue
            header, body = msg.json()
            if household is None and header.get("householdId"):
                household = header["householdId"]
                await probe.command(ws, {"namespace": "groups:1", "command": "getGroups", "householdId": household})
                continue
            if player is None and header.get("type") == "groups":
                me = next((p for p in body.get("players", []) if p.get("websocketUrl") == url), None)
                if me is None:
                    raise RuntimeError("this ip isn't one of the household's players")
                player = me["id"]
                group = next(g for g in body.get("groups", []) if player in g.get("playerIds", []))
                info.update(uid=player, name=me.get("name"), group=group)
                print(f"{stamp()} WS player {me.get('name')} ({player}), group {group.get('name')}")
                for ns in ("playback:1", "playbackMetadata:1", "groupVolume:1"):
                    await probe.command(ws, {"namespace": ns, "command": "subscribe", "groupId": group["id"]})
                ready.set()
                continue
            watch.from_ws(header, body)
            kind = header.get("type") or header.get("response") or "?"
            if verbose or kind in ("playbackStatus", "metadataStatus") or not header.get("success", True):
                ok = "" if header.get("success", True) else " FAILED"
                print(f"{stamp()} WS {header.get('namespace', '-')}/{kind}{ok} {probe.short(body)}")


async def upnp_watch(spk: Speaker, watch: Watch, ready: asyncio.Event):
    await ready.wait()
    last = None
    while True:
        started = time.monotonic()
        try:
            st, rel, dur, uri = await spk.state()
            watch.from_upnp(st, rel, dur, uri)
            line = f"{st} {hms(rel)}/{hms(dur)} {uri[-50:]}"
            if (st, uri, dur) != last:
                print(f"{stamp()} UPnP {line}")
                last = (st, uri, dur)
        except Exception as e:  # noqa: BLE001 - a test tool: say it and go on
            print(f"{stamp()} UPnP error {type(e).__name__}: {e}")
        await asyncio.sleep(max(0.0, 1.0 - (time.monotonic() - started)))


# --- the run ------------------------------------------------------------------------------------

class Run:
    def __init__(self, spk, watch, eps, mode, uid):
        self.spk, self.watch, self.eps, self.mode, self.uid = spk, watch, eps, mode, uid
        self.results = []  # (step, ok, ws latency, upnp latency, note)

    def say(self, text):
        print(f"{stamp()} STEP {text}")

    async def wait_for(self, want: set, timeout: float, since: float):
        """Until both sides have shown a state in [want] after [since], or the timeout."""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if (Watch.first(self.watch.ws_log, since, want) is not None
                    and Watch.first(self.watch.upnp_log, since, want) is not None):
                break
            await asyncio.sleep(0.1)

    def record(self, step, since, want, note=""):
        ws = Watch.first(self.watch.ws_log, since, want)
        up = Watch.first(self.watch.upnp_log, since, want)
        self.results.append((step, ws is not None or up is not None, ws, up, note))

    async def play(self, ep, start_s=0.0, step="play"):
        """vibb's SonosSession.play for kind url."""
        built = built_for(ep, self.mode)
        self.say(f"{step}: {ep['title']} from {hms(start_s)} (DIDL {self.mode}, {len(built)} chars)")
        since = time.monotonic()
        try:
            await self.spk.av("RemoveAllTracksFromQueue")
        except Exception:  # noqa: BLE001 - vibb ignores it too
            pass
        hushed = None
        if start_s >= 5:
            try:
                hushed = tag(await self.spk.call("RenderingControl", "GetMute", Channel="Master"), "CurrentMute")
                await self.spk.call("RenderingControl", "SetMute", Channel="Master", DesiredMute="1")
            except Exception:  # noqa: BLE001
                hushed = None
        note = ""
        try:
            try:
                await self.spk.av("SetAVTransportURI", CurrentURI=ep["url"], CurrentURIMetaData=built)
            except SoapFault as e:
                self.say(f"{step}: SetAVTransportURI REFUSED - {e}")
                self.results.append((step, False, None, None, f"SetAVTransportURI refused: {e}"))
                return False
            await self.spk.av("Play", Speed="1")
            if start_s >= 5:
                note = "sought" if await self.seek_settled(start_s) else "seek refused, from the top"
        finally:
            if hushed is not None:
                try:
                    await self.spk.call("RenderingControl", "SetMute", Channel="Master", DesiredMute=hushed or "0")
                except Exception:  # noqa: BLE001
                    pass
        await self.wait_for({"PLAYING"}, 10, since)
        self.record(step, since, {"PLAYING"}, note)
        return True

    async def seek_settled(self, start_s, timeout=8):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                if tag(await self.spk.av("GetTransportInfo"), "CurrentTransportState") == "PLAYING":
                    break
            except Exception:  # noqa: BLE001
                pass
            await asyncio.sleep(0.3)
        for _ in range(3):
            try:
                await self.spk.av("Seek", Unit="REL_TIME", Target=hms(start_s))
                return True
            except SoapFault as e:
                if not re.search(r"errorCode (701|710|711)", str(e)):
                    return False
                await asyncio.sleep(0.4)
        return False

    async def verb(self, step, action, want, **args):
        self.say(step)
        since = time.monotonic()
        await self.spk.av(action, **args)
        await self.wait_for(want, 8, since)
        self.record(step, since, want)

    async def seek(self, step, to_s):
        self.say(f"{step}: to {hms(to_s)}")
        since = time.monotonic()
        await self.spk.av("Seek", Unit="REL_TIME", Target=hms(to_s))
        # a seek doesn't change the state: when did each side show a position near the target?
        ws = up = None
        end = time.monotonic() + 8
        while time.monotonic() < end and (ws is None or up is None):
            if ws is None and self.watch.ws["at"] and self.watch.ws["at"] >= since \
                    and abs((self.watch.ws["pos"] or -99) - to_s) < 3:
                ws = self.watch.ws["at"] - since
            if up is None and self.watch.upnp["at"] and self.watch.upnp["at"] >= since \
                    and abs((self.watch.upnp["pos"] or -99) - to_s) < 3:
                up = self.watch.upnp["at"] - since
            await asyncio.sleep(0.1)
        self.results.append((step, ws is not None or up is not None, ws, up, ""))

    async def natural_end(self, cur, nxt):
        """Seek to near the end, then do what the app does: STOPPED near the end plays the next."""
        dur = self.watch.upnp["dur"] or cur["dur_s"]
        if not dur:
            self.results.append(("natural end", False, None, None, "no duration known"))
            return
        since = time.monotonic()
        await self.seek("near the end", max(0, dur - 15))
        self.say("waiting for the episode to end")
        end = since + 45
        while time.monotonic() < end:
            if self.watch.upnp["state"] == "STOPPED" or (self.watch.ws["at"] and self.watch.ws["at"] > since
                                                        and self.watch.ws["state"] in ("IDLE", "STOPPED")):
                break
            await asyncio.sleep(0.1)
        seen_ws = Watch.first(self.watch.ws_log, since, {"IDLE", "STOPPED"})
        seen_up = Watch.first(self.watch.upnp_log, since, {"STOPPED"})
        self.results.append(("the end seen", seen_ws is not None or seen_up is not None, seen_ws, seen_up,
                             "seconds after the seek; the audio really ends ~15 s after it"))
        await self.play(nxt, 0, "advance (the app's)")

    async def park(self):
        self.say("park: Stop + the speaker's own queue")
        await self.spk.av("Stop")
        try:
            await self.spk.av("SetAVTransportURI", CurrentURI=f"x-rincon-queue:{self.uid}#0", CurrentURIMetaData="")
        except SoapFault as e:
            self.say(f"park: queue uri refused - {e}")

    async def scenario(self):
        a, b = self.eps[0], self.eps[1]
        if not await self.play(a, 0, "play episode 1"):
            return
        await asyncio.sleep(8)
        await self.verb("pause", "Pause", {"PAUSED_PLAYBACK", "PAUSED"})
        await asyncio.sleep(4)
        await self.verb("resume", "Play", {"PLAYING"}, Speed="1")
        await asyncio.sleep(4)
        await self.seek("seek", 60)
        await asyncio.sleep(5)
        await self.play(b, 0, "next (episode 2)")
        await asyncio.sleep(6)
        await self.play(a, 90, "previous with a bookmark (episode 1 at 1:30, muted seek)")
        await asyncio.sleep(6)
        await self.natural_end(a, b)
        await asyncio.sleep(6)
        await self.park()
        await asyncio.sleep(3)

    def report(self):
        print("\n=== result (seconds from the command until each side showed it) ===")
        print(f"{'step':58} {'WS':>6} {'UPnP':>6}  note")
        for step, ok, ws, up, note in self.results:
            f = lambda v: f"{v:6.1f}" if v is not None else "     -"  # noqa: E731
            print(f"{step[:58]:58} {f(ws)} {f(up)}  {'' if ok else 'NOT SEEN '}{note}")
        pushes = [p for _, st, p in self.watch.ws_log if st == "PLAYING"]
        print(f"\nWS playbackStatus events: {len(self.watch.ws_log)} "
              f"({len(pushes)} while PLAYING) - many = it ticks, few = only on changes")


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ip", nargs="?", help="the speaker's LAN address")
    ap.add_argument("--podcast", default="hallo_bablo", help="NRK podcast slug (radio.nrk.no/podkast/<slug>)")
    ap.add_argument("--didl", choices=("vibb", "app", "bare"), default="vibb", help="which fields go in (see above)")
    ap.add_argument("--volume", type=int, default=1,
                    help="the volume set before anything plays (default 1); the old one is put back at the end")
    ap.add_argument("--dry-run", action="store_true", help="NRK only: list the episodes and print the DIDL")
    ap.add_argument("--verbose", action="store_true", help="print every WS message")
    ap.add_argument("--golden", metavar="DIR", help="write the golden DIDLs to DIR and end (no speaker, no network)")
    args = ap.parse_args()
    if not args.golden and not args.ip:
        ap.error("the speaker's ip is needed (or --golden DIR)")
    return args


async def main(args):
    async with aiohttp.ClientSession() as session:
        eps = await episodes(session, args.podcast, 2)
        if len(eps) < 2:
            sys.exit(f"needs two playable episodes of {args.podcast}, found {len(eps)}")
        for ep in eps:
            print(f"{stamp()} NRK {ep['key']} {ep['title']!r} {hms(ep['dur_s'])} {ep['url'][:80]}")
        if args.dry_run:
            print(built_for(eps[0], args.didl))
            return

        spk, watch, ready, info = Speaker(session, args.ip), Watch(), asyncio.Event(), {}
        ws_task = asyncio.create_task(websocket_watch(session, args.ip, watch, ready, info, args.verbose))
        poll_task = asyncio.create_task(upnp_watch(spk, watch, ready))
        await asyncio.wait({ws_task, asyncio.create_task(ready.wait())}, return_when=asyncio.FIRST_COMPLETED)
        if ws_task.done():
            sys.exit(f"WS failed: {ws_task.exception()}")
        if info["group"].get("coordinatorId") != info["uid"]:
            print(f"{stamp()} NOTE this speaker is grouped and not its group's coordinator - "
                  "AVTransport commands may be refused; use the coordinator's ip")
        old_volume = tag(await spk.call("RenderingControl", "GetVolume", Channel="Master"), "CurrentVolume")
        await spk.call("RenderingControl", "SetVolume", Channel="Master",
                       DesiredVolume=str(max(0, min(100, args.volume))))
        print(f"{stamp()} volume {old_volume or '?'} -> {args.volume} (put back at the end)")
        await asyncio.sleep(2)  # let both watchers show the starting state
        run = Run(spk, watch, eps, args.didl, info["uid"])
        try:
            await run.scenario()
        except Exception as e:  # noqa: BLE001
            print(f"{stamp()} STOPPED: {type(e).__name__}: {e}")
        finally:
            if old_volume:
                await spk.call("RenderingControl", "SetVolume", Channel="Master", DesiredVolume=old_volume)
            ws_task.cancel()
            poll_task.cancel()
        run.report()


if __name__ == "__main__":
    ARGS = parse_args()
    if ARGS.golden:
        write_goldens(ARGS.golden)
        sys.exit(0)
    load_network()
    try:
        asyncio.run(main(ARGS))
    except KeyboardInterrupt:
        pass
