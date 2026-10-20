#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""A spike for design 53: does a Sonos speaker push playback events over its local WebSocket?

Connects to wss://<ip>:1443/websocket/api (the speaker's own Control API endpoint, the one the
sonos-websocket library - used by Home Assistant only for announcement clips - talks to), finds the
speaker's group, subscribes to playback, playback metadata, group volume and groups, and prints
every message as it arrives with a timestamp. With --poll it also asks the speaker's UPnP
AVTransport (port 1400, what the music app uses today) for GetPositionInfo every second, so the
two can be compared side by side while you skip tracks in the Sonos app or the music app.

Nothing is changed on the speaker: subscribe and read only. Run on a machine on the same LAN:

    python3 -m pip install aiohttp        # or: sudo apt install python3-aiohttp
    python3 sonos-ws-probe.py 192.168.1.50 --poll

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
PROTOCOL = "v1.api.smartspeaker.audio"
T0 = time.monotonic()


def stamp() -> str:
    return f"{time.strftime('%H:%M:%S')} +{time.monotonic() - T0:7.2f}s"


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


async def websocket_side(session: aiohttp.ClientSession, ip: str, ready: asyncio.Event) -> None:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # the speaker's certificate is self-signed
    url = f"wss://{ip}:1443/websocket/api"
    async with session.ws_connect(url, headers={"X-Sonos-Api-Key": API_KEY}, protocols=(PROTOCOL,),
                                  ssl=ctx, heartbeat=30) as ws:
        print(f"{stamp()} WS connected to {url}")
        # An empty command answers with the household id (sonos-websocket's trick).
        await command(ws, {})
        household = None
        player = group = None
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                print(f"{stamp()} WS {msg.type.name}")
                if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    break
                continue
            header, body = msg.json()
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
                group = next(g for g in body.get("groups", []) if player in g.get("playerIds", []))
                print(f"{stamp()} WS player {me.get('name')} in group {group.get('name')} ({group['id']})")
                gid = group["id"]
                for ns in ("playback:1", "playbackMetadata:1", "groupVolume:1"):
                    await command(ws, {"namespace": ns, "command": "subscribe", "groupId": gid})
                await command(ws, {"namespace": "groups:1", "command": "subscribe", "householdId": household})
                await command(ws, {"namespace": "playback:1", "command": "getPlaybackStatus", "groupId": gid})
                ready.set()
                continue
            kind = header.get("type") or header.get("response") or header.get("command") or "?"
            ok = "" if header.get("success", True) else " FAILED"
            print(f"{stamp()} WS {header.get('namespace', '-')}/{kind}{ok} {short(body)}")


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
    args = ap.parse_args()
    ready = asyncio.Event()
    async with aiohttp.ClientSession() as session:
        tasks = [asyncio.create_task(websocket_side(session, args.ip, ready))]
        if args.poll:
            tasks.append(asyncio.create_task(upnp_side(session, args.ip, ready)))
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_EXCEPTION)
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
