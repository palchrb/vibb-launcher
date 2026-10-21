#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A spike for design 53: does a Sonos speaker push playback events over its local WebSocket?

Connects to wss://<ip>:1443/websocket/api (the speaker's own Control API endpoint, the one the
sonos-websocket library - used by Home Assistant only for announcement clips - talks to), finds the
speaker's group, subscribes to playback, playback metadata, group volume and groups, and prints
every message as it arrives with a timestamp. With --poll it also asks the speaker's UPnP
AVTransport (port 1400, what the music app uses today) for GetPositionInfo every second, so the
two can be compared side by side while you skip tracks in the Sonos app or the music app.

Without --do nothing is changed on the speaker: subscribe and read only. With --do it sends the
listed commands one by one (--gap seconds apart, default 4) after the subscriptions, so the events
each command causes show in the stream. Run on a machine on the same LAN:

    python3 -m pip install aiohttp        # or: sudo apt install python3-aiohttp
    python3 sonos-ws-probe.py 192.168.1.50 --poll
    python3 sonos-ws-probe.py 192.168.1.50 --poll --do pause play seek+30 seek-15 volume=20
    python3 sonos-ws-probe.py 192.168.1.50 --do group=Kjøkken ungroup=Kjøkken --volume 0 --all

Commands: play, pause, toggle, next, prev, seek+N / seek-N (seconds, relative), seek=N (seconds,
absolute), volume=N (0-100, this speaker's group), group=<room> (the room joins this speaker's
group), ungroup=<room> (the room leaves its group and stands alone). Room names as the Sonos app
shows them, matched case-insensitively.

--all also subscribes to every other group's playback (does one socket follow the whole house?),
and to each new group as one forms. The own group is subscribed again whenever its id changes (a
group's id changes with its members). --volume N sets this speaker and every room named in a
group=/ungroup= to N (UPnP, each at its own ip) before the first command and puts each one's
volume back after the last; with --do the run then ends by itself.

--save FILE writes every message the speaker sends as a JSON array of `[header, body]` messages (one per
line, rewritten after each), the music app's fixture format (design 53 §5 check 1,
`music/app/src/test/resources/sonos/ws/`: split it into the scenario files, no conversion needed): the
household id, every RINCON uid, every LAN address (IPv4 and IPv6 - a link-local IPv6 can carry the
speaker's MAC) and every name (rooms, groups, titles) replaced by fake ones, consistently within the
file - `Sonos_FAKEhousehold000000000000001` (one household: every id maps to it),
`RINCON_000000000000014NN`, `192.168.1.NN`, `fd00::NN`, `Navn N` - so the capture can be committed as it is.

--key UUID sends another X-Sonos-Api-Key (a random one tells whether the speaker checks it at all), --key none
sends no key header.

Ctrl-C ends it. The X-Sonos-Api-Key below is the RFC 4122 example UUID that sonos-websocket sends;
the speaker accepts it locally - it is not a secret.
"""

import argparse
import asyncio
import json
import re
import ssl
import sys
import time
import uuid

try:
    import aiohttp
except ImportError:
    sys.exit("needs aiohttp: python3 -m pip install aiohttp (or apt install python3-aiohttp)")

API_KEY = "123e4567-e89b-12d3-a456-426655440000"
KEY = API_KEY  # --key: another value, or "none" for no X-Sonos-Api-Key header at all
PROTOCOL = "v1.api.smartspeaker.audio"
T0 = time.monotonic()


def stamp() -> str:
    return f"{time.strftime('%H:%M:%S')} +{time.monotonic() - T0:7.2f}s"


class Saver:
    """--save: the messages as a JSON array, their ids, addresses and names replaced by fake ones (consistently)."""

    UID = re.compile(r"RINCON_[0-9A-Fa-f]{6,}")
    HOUSEHOLD = re.compile(r"Sonos_[A-Za-z0-9._-]+")
    IPV4 = re.compile(r"\b(?:10|172|192|169)\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\b")
    # an IPv6 literal: compressed (with "::") or full (eight groups); never a time like 0:24:13
    IPV6 = re.compile(r"(?i)(?<![\w:])(?:(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?"
                      r"|::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4})(?![\w:])")
    NAME_KEYS = {"name", "groupName", "roomName", "title", "artist", "album"}

    def __init__(self, path: str):
        self.path = path
        self.messages = []
        self.uids, self.ips, self.ip6s, self.names = {}, {}, {}, {}

    def _uid(self, m):
        return self.uids.setdefault(m.group(0), f"RINCON_{'0' * 12}014{len(self.uids) % 100:02d}")

    def _ip(self, m):
        return self.ips.setdefault(m.group(0), f"192.168.1.{16 + len(self.ips)}")

    def _ip6(self, m):
        return self.ip6s.setdefault(m.group(0).lower(), f"fd00::{16 + len(self.ip6s):x}")

    def _text(self, v: str) -> str:
        v = self.UID.sub(self._uid, v)
        v = self.HOUSEHOLD.sub("Sonos_FAKEhousehold000000000000001", v)
        v = self.IPV6.sub(self._ip6, v)
        return self.IPV4.sub(self._ip, v)

    def scrub(self, v, key=None):
        if isinstance(v, dict):
            return {k: self.scrub(x, k) for k, x in v.items()}
        if isinstance(v, list):
            return [self.scrub(x, key) for x in v]
        if isinstance(v, str):
            if key in self.NAME_KEYS:
                return self.names.setdefault(v, f"Navn {len(self.names) + 1}")
            return self._text(v)
        return v

    def save(self, header: dict, body: dict) -> None:
        self.messages.append(json.dumps([self.scrub(header), self.scrub(body)], ensure_ascii=False))
        with open(self.path, "w", encoding="utf-8") as out:  # whole each time: a Ctrl-C leaves a valid array
            out.write("[\n  " + ",\n  ".join(self.messages) + "\n]\n")


def short(body: dict) -> str:
    """The fields that matter for the progress bar and the art, else the whole body cut."""
    keep = {}
    for key in ("playbackState", "positionMillis", "previousPositionMillis", "isDucking", "volume", "muted"):
        if key in body:
            keep[key] = body[key]
    item = (body.get("currentItem") or {}).get("track") or {}
    if item:
        keep["track"] = item.get("name")
        keep["artist"] = (item.get("artist") or {}).get("name")
        keep["durationMillis"] = item.get("durationMillis")
        keep["art"] = bool(item.get("imageUrl"))
    container = body.get("container") or {}
    if container.get("name"):
        keep["container"] = container.get("name")
    if keep:
        return json.dumps(keep, ensure_ascii=False)
    text = json.dumps(body, ensure_ascii=False)
    return text if len(text) <= 300 else text[:300] + "…"


async def command(ws, header: dict, body: dict | None = None) -> None:
    header = dict(header, cmdId=uuid.uuid4().hex[:8])
    await ws.send_json([header, body or {}])


def plan(step: str, gid: str, players: dict, groups: list) -> tuple[dict, dict] | None:
    """One --do step as (header, body), or None (said why)."""
    simple = {"play": "play", "pause": "pause", "toggle": "togglePlayPause", "next": "skipToNextTrack",
              "prev": "skipToPreviousTrack"}
    if step in simple:
        return {"namespace": "playback:1", "command": simple[step], "groupId": gid}, {}
    m = re.fullmatch(r"seek([+-])(\d+)", step)
    if m:
        delta = int(m.group(2)) * 1000 * (1 if m.group(1) == "+" else -1)
        return {"namespace": "playback:1", "command": "seekRelative", "groupId": gid}, {"deltaMillis": delta}
    m = re.fullmatch(r"seek=(\d+)", step)
    if m:
        return {"namespace": "playback:1", "command": "seek", "groupId": gid}, {"positionMillis": int(m.group(1)) * 1000}
    m = re.fullmatch(r"volume=(\d+)", step)
    if m:
        return {"namespace": "groupVolume:1", "command": "setVolume", "groupId": gid}, {"volume": min(100, int(m.group(1)))}
    m = re.fullmatch(r"(group|ungroup)=(.+)", step)
    if m:
        pid = players.get(m.group(2).strip().lower())
        if pid is None:
            print(f"{stamp()} DO {step}: no room of that name - known: {sorted(players)}")
            return None
        if m.group(1) == "group":
            return {"namespace": "groups:1", "command": "modifyGroupMembers", "groupId": gid}, {"playerIdsToAdd": [pid], "playerIdsToRemove": []}
        home = next((g for g in groups if pid in g.get("playerIds", [])), None)
        if home is None or len(home.get("playerIds", [])) < 2:
            print(f"{stamp()} DO {step}: that room isn't grouped")
            return None
        return {"namespace": "groups:1", "command": "modifyGroupMembers", "groupId": home["id"]}, {"playerIdsToAdd": [], "playerIdsToRemove": [pid]}
    print(f"{stamp()} DO {step}: unknown command")
    return None


RC = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:{action}
xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel>{extra}</u:{action}></s:Body></s:Envelope>"""


async def rc(session, ip: str, action: str, extra: str = "") -> str:
    """One RenderingControl call on the speaker at [ip] (its own volume, not the group's)."""
    async with session.post(f"http://{ip}:1400/MediaRenderer/RenderingControl/Control",
                            data=RC.format(action=action, extra=extra), headers={
                                "Content-Type": 'text/xml; charset="utf-8"',
                                "SOAPACTION": f'"urn:schemas-upnp-org:service:RenderingControl:1#{action}"'},
                            timeout=aiohttp.ClientTimeout(total=4)) as r:
        return await r.text()


def ip_of(player: dict) -> str:
    return re.sub(r"^wss://([^:/]+).*", r"\1", player.get("websocketUrl", ""))


async def run_steps(ws, session, steps: list, gap: float, live: dict, players: dict,
                    volume: int | None, quiet: list) -> None:
    """[live] is kept current from the groups events: a group's id changes when its members do.
    [quiet]: the players (dicts) set to [volume] first and put back at the end."""
    saved = {}
    try:
        if volume is not None:
            for p in quiet:
                try:
                    saved[p["name"]] = (ip_of(p), tag(await rc(session, ip_of(p), "GetVolume"), "CurrentVolume"))
                    await rc(session, ip_of(p), "SetVolume", f"<DesiredVolume>{volume}</DesiredVolume>")
                    print(f"{stamp()} VOLUME {p['name']} {saved[p['name']][1]} -> {volume}")
                except Exception as e:  # noqa: BLE001
                    print(f"{stamp()} VOLUME {p.get('name')}: {type(e).__name__} - not changed")
        for step in steps:
            await asyncio.sleep(gap)
            planned = plan(step, live["gid"], players, live["groups"])
            if planned:
                header, body = planned
                print(f"{stamp()} DO {step} -> {header['namespace']}/{header['command']} {json.dumps(body, ensure_ascii=False)}")
                await command(ws, header, body)
        await asyncio.sleep(gap)
    finally:
        for name, (ip, old) in saved.items():
            try:
                if old:
                    await rc(session, ip, "SetVolume", f"<DesiredVolume>{old}</DesiredVolume>")
                    print(f"{stamp()} VOLUME {name} back to {old}")
            except Exception as e:  # noqa: BLE001
                print(f"{stamp()} VOLUME {name}: {type(e).__name__} - NOT put back to {old}")
        if steps:
            await ws.close()


async def websocket_side(session: aiohttp.ClientSession, ip: str, ready: asyncio.Event,
                         steps: list | None = None, gap: float = 4.0, everything: bool = False,
                         volume: int | None = None, saver: Saver | None = None) -> None:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # the speaker's certificate is self-signed
    url = f"wss://{ip}:1443/websocket/api"
    headers = {} if KEY.lower() == "none" else {"X-Sonos-Api-Key": KEY}
    print(f"{stamp()} WS key: {'(no header)' if not headers else KEY}")
    async with session.ws_connect(url, headers=headers, protocols=(PROTOCOL,),
                                  ssl=ctx, heartbeat=30) as ws:
        print(f"{stamp()} WS connected to {url}")
        # An empty command answers with the household id (sonos-websocket's trick).
        await command(ws, {})
        household = None
        player = group = None
        subscribed = set()

        async def follow(gid, again=False):
            if gid in subscribed and not again:
                return
            subscribed.add(gid)
            for ns in ("playback:1", "playbackMetadata:1", "groupVolume:1"):
                await command(ws, {"namespace": ns, "command": "subscribe", "groupId": gid})
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                print(f"{stamp()} WS {msg.type.name}")
                if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    break
                continue
            header, body = msg.json()
            if saver:
                saver.save(header, body)
            if household is None and header.get("householdId"):
                household = header["householdId"]
                print(f"{stamp()} WS household {household}")
                await command(ws, {"namespace": "groups:1", "command": "getGroups", "householdId": household})
                continue
            if player is None and header.get("type") == "groups":
                players = body.get("players", [])
                me = next((p for p in players if p.get("websocketUrl") == url), None)
                if me is None:
                    print(f"{stamp()} WS this ip isn't in the household's players: {[p.get('name') for p in players]}")
                    return
                player = me["id"]
                names = {p.get("name", "").lower(): p["id"] for p in players}
                group = next(g for g in body.get("groups", []) if player in g.get("playerIds", []))
                print(f"{stamp()} WS player {me.get('name')} in group {group.get('name')} ({group['id']})")
                gid = group["id"]
                await follow(gid)
                if everything:
                    for g in body.get("groups", []):
                        await follow(g["id"])
                await command(ws, {"namespace": "groups:1", "command": "subscribe", "householdId": household})
                await command(ws, {"namespace": "playback:1", "command": "getPlaybackStatus", "groupId": gid})
                ready.set()
                live = {"gid": gid, "groups": body.get("groups", [])}
                if steps:
                    named = {re.sub(r"^(?:un)?group=", "", st).strip().lower() for st in steps if "group=" in st}
                    quiet = [me] + [p for p in players if p.get("name", "").lower() in named and p["id"] != player]
                    task = asyncio.create_task(run_steps(ws, session, steps, gap, live, names, volume, quiet))
                    task.add_done_callback(lambda t: t.cancelled() or t.exception() and print(
                        f"{stamp()} DO stopped: {type(t.exception()).__name__}: {t.exception()}"))
                continue
            if player is not None and header.get("type") == "groups":
                live["groups"] = body.get("groups", [])
                mine = next((g for g in live["groups"] if player in g.get("playerIds", [])), None)
                if mine and mine["id"] != live["gid"]:
                    print(f"{stamp()} WS own group is now {mine.get('name')} ({mine['id']}) - subscribing")
                    live["gid"] = mine["id"]
                    await follow(mine["id"], again=True)  # an old subscription may have ended with its group
                if everything:
                    for g in live["groups"]:
                        await follow(g["id"])
            kind = header.get("type") or header.get("response") or header.get("command") or "?"
            ok = "" if header.get("success", True) else " FAILED"
            where = ""
            if player is not None and header.get("groupId"):
                g = next((g for g in live["groups"] if g["id"] == header["groupId"]), None)
                where = f" [{g.get('name') if g else header['groupId']}]"
            print(f"{stamp()} WS {header.get('namespace', '-')}/{kind}{ok}{where} {short(body)}")


SOAP = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:{action}
xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID></u:{action}></s:Body></s:Envelope>"""


def tag(xml: str, name: str) -> str:
    m = re.search(rf"<{name}>(.*?)</{name}>", xml, re.S)
    return m.group(1) if m else ""


async def upnp_side(session: aiohttp.ClientSession, ip: str, ready: asyncio.Event) -> None:
    await ready.wait()
    url = f"http://{ip}:1400/MediaRenderer/AVTransport/Control"
    last = None
    while True:
        started = time.monotonic()
        try:
            out = []
            for action in ("GetTransportInfo", "GetPositionInfo"):
                async with session.post(url, data=SOAP.format(action=action), headers={
                        "Content-Type": 'text/xml; charset="utf-8"',
                        "SOAPACTION": f'"urn:schemas-upnp-org:service:AVTransport:1#{action}"'},
                        timeout=aiohttp.ClientTimeout(total=3)) as r:
                    out.append(await r.text())
            state = tag(out[0], "CurrentTransportState")
            uri = tag(out[1], "TrackURI").replace("&amp;", "&")
            line = f"{state} {tag(out[1], 'RelTime')}/{tag(out[1], 'TrackDuration')} track {tag(out[1], 'Track')} {uri[-60:]}"
            if line != last:  # only changes, so the WS lines stay readable
                print(f"{stamp()} UPnP {line}")
                last = line
        except Exception as e:  # noqa: BLE001 - a spike: say it and go on
            print(f"{stamp()} UPnP error {type(e).__name__}")
        await asyncio.sleep(max(0.0, 1.0 - (time.monotonic() - started)))


async def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ip", help="the speaker's LAN address (Sonos app › Settings › System › About)")
    ap.add_argument("--poll", action="store_true", help="also poll UPnP GetPositionInfo every second")
    ap.add_argument("--do", nargs="+", metavar="CMD", help="commands to send, in order (see above)")
    ap.add_argument("--gap", type=float, default=4.0, help="seconds between --do commands (default 4)")
    ap.add_argument("--all", action="store_true", help="also follow every other group's playback")
    ap.add_argument("--volume", type=int, help="with --do: this speaker and the rooms named set to N first, put back after")
    ap.add_argument("--save", metavar="FILE", help="write every message to a JSON array, ids, addresses and names replaced (fixtures)")
    ap.add_argument("--key", help="the X-Sonos-Api-Key to send instead of the example UUID; 'none' sends no header "
                    "(does the speaker check it?)")
    args = ap.parse_args()
    if args.key:
        global KEY
        KEY = args.key
    ready = asyncio.Event()
    async with aiohttp.ClientSession() as session:
        saver = Saver(args.save) if args.save else None
        tasks = [asyncio.create_task(websocket_side(session, args.ip, ready, args.do, args.gap, args.all, args.volume, saver))]
        if args.poll:
            tasks.append(asyncio.create_task(upnp_side(session, args.ip, ready)))
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()
        for t in done:
            if t.exception():
                print(f"{stamp()} stopped: {type(t.exception()).__name__}: {t.exception()}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
