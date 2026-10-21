#!/usr/bin/env python3
# fake-sonos.py - fake Sonos speakers for the music app on the emulator (design 26a §8 step 1).
#
# Part of handy (palchrb/vibb-launcher), licensed under the GNU General Public License v3.0 or later
# (LICENSE). Modelled on palchrb/vibb's test fakes - tests/sonos_renderer.py's FakeSidecar and
# tests/sonos_contract.py's rule that both fakes answer from ONE source of truth (MIT License,
# Copyright (c) 2026 palchrb; see NOTICE.md). Python 3, stdlib only.
"""Fake Sonos speakers: N speakers on N ports, one household topology, a scripted transport.

    python3 scripts/fake-sonos.py --speakers Stua:1401,Kjøkken:1402 --advertise 10.0.2.2
    python3 scripts/fake-sonos.py --speakers Stua:1401,Kjøkken:1402 --group Stua+Kjøkken
    python3 scripts/fake-sonos.py --script migration.txt
    python3 scripts/fake-sonos.py --volume 90          # every speaker starts loud (the start cap, 26a §5.3)
    python3 -I scripts/fake-sonos.py --selftest

Every answer is built from the canned SOAP replies in music/app/src/test/resources/sonos/ - the same
files the JVM tests parse (SonosFixturesTest) - with the scripted values put into their elements: the
"two fakes" guard (neither fake can drift into agreeing with itself). Each situation answers from its
own fixture: GetPositionInfo from position_{playing_ours,paused,transitioning,stopped_retained,
stopped_empty,x_rincon}.xml, GetTransportInfo from transport_<state>.xml, a fault from its
fault_<code>.xml (701's with the code when there is none), the topology in zgs_wrapped.xml's shape. It answers the actions of
design 26a §2.2 (AVTransport, RenderingControl, ZoneGroupTopology), the device description and
/status/accounts, and logs every SOAP call. No SSDP: the emulator's user-mode network carries no
multicast and every host fake is 10.0.2.2, so the app's cache is seeded through the debug feed
(debug-feed/sonos.json, docs/testing/emulator.md §6h); --print-seed prints that file.

The transport: SetAVTransportURI sets the uri (STOPPED), Play plays (TRANSITIONING for
--transition-s first), RelTime runs in real time and the track ends by itself at its duration (from
the DIDL's res@duration, else --duration; STOPPED with the uri kept, like a real episode end), Seek
on a STOPPED transport is UPnP 701, Pause, Stop, volume and mute. Grouping (design 53 §0.2, soco's
verbs): SetAVTransportURI to x-rincon:<coordinator> joins that group (a stream the room coordinated
stops); BecomeCoordinatorOfStandaloneGroup leaves it - a member stops and the rest play on, a
coordinator's stream moves to its next member (as `move`) - answering from become_standalone.xml.

The speaker's own queue (design 54, Spotify on a room: vibb's spotify_sharelink, SoCo's ShareLink): AddURIToQueue takes
a share link (SoCo's EnqueuedURI and metadata, service 2311 or 3079) of the fixtures in server/testdata/spotify/ - the
playlist of playlist_items.json (its playable tracks and episodes in its order: an episode our listing drops is an
extra), the album (album.json + album_tracks.json), the show (show_episodes.json), track.json, episode.json - and
answers from add_uri_to_queue.xml; another id is fault 800 (fault_800.xml, provisional). RemoveAllTracksFromQueue,
SetAVTransportURI x-rincon-queue:<uid>#0 (the queue as the source), Seek TRACK_NR, the queue's own advance (NORMAL: the
next position, STOPPED after the last with its track kept; REPEAT_ALL wraps; REPEAT_ONE plays it again; SHUFFLE_* a
seeded pick), SetPlayMode/GetTransportSettings (transport_settings.xml), ContentDirectory Browse Q:0 - BrowseMetadata
(browse_queue_meta.xml) and BrowseDirectChildren paged (browse_queue_page.xml), every one of the six arguments required
(402 without SortCriteria), `UpdateID` one more with every edit -, GetMediaInfo (media_info_queue.xml) and the sleep
timer: ConfigureSleepTimer / GetRemainingSleepTimerDuration (sleep_timer.xml; when it runs out the speaker pauses -
provisional, design 54 [D21]). GetPositionInfo of a queue track answers from position_spotify_queue.xml. The options
--refuse-share (every add a fault), --empty-share (0 added), --queue-reversed, --queue-extra (a track our listing lacks,
at position 2), --queue-gap (one of ours missing) and --track-seconds N (every queue track N s long) shape the add.

--script FILE: one event per line, `t+<seconds> [@<speaker>] <event>`, the time counted from the
first Play any speaker receives (the session start); the speaker defaults to the first. Events:
    PLAYING "<uri>" | PAUSED_PLAYBACK "<uri>" | STOPPED "" | TRANSITIONING -   set state and uri (- keeps it)
    x-rincon:<uid> | group <speaker>      grouped away: a member of that coordinator's group
    move <speaker>                        migration: the stream moves there, this one STOPPED ""
    drop | back                           stop / start answering (connection closed: unreachable)
    stall | unstall                       RelTime frozen while PLAYING (the stall retry)
    volume <0-100>                        changed from "the Sonos app"
    next | previous                       the speaker's own buttons on a queue (design 54 [D9])
    queue-insert                          an edit from the Sonos app: a track our listing lacks after the current one
    shuffle                               the Sonos app sets SHUFFLE_NOREPEAT
Lines starting with # are comments.
"""

import argparse
import http.client
import json
import os
import random
import re
import shlex
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "..", "music", "app", "src", "test", "resources", "sonos")
SPOTIFY_DATA = os.path.join(HERE, "..", "server", "testdata", "spotify")
SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
SERVICES = {
    "/MediaRenderer/AVTransport/Control": "urn:schemas-upnp-org:service:AVTransport:1",
    "/MediaRenderer/RenderingControl/Control": "urn:schemas-upnp-org:service:RenderingControl:1",
    "/ZoneGroupTopology/Control": "urn:schemas-upnp-org:service:ZoneGroupTopology:1",
    "/MediaServer/ContentDirectory/Control": "urn:schemas-upnp-org:service:ContentDirectory:1",
}
# design 54: SoCo's ShareLink EnqueuedURI (soco/plugins/sharelink.py magic(): a container's prefix, or none for an item)
SHARE_URI = re.compile(r"(?:x-rincon-cpcontainer:(?:1004206c|1006206c))?spotify%3a(playlist|album|show|track|episode)%3a([A-Za-z0-9]+)")
SHARE_SN = re.compile(r"SA_RINCON(2311|3079)_X_#Svc(2311|3079)-0-Token")
PLAY_MODES = ("NORMAL", "REPEAT_ALL", "REPEAT_ONE", "SHUFFLE", "SHUFFLE_NOREPEAT", "SHUFFLE_REPEAT_ONE")  # SoCo's
DIDL_NS = ('xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
           'xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"')
# a track our listing lacks (album_tracks.json's bonus track): --queue-extra and the script's queue-insert
EXTRA_TRACK = "1FakeTrackG00000000007"
LIVE = ("PLAYING", "PAUSED_PLAYBACK")
T0 = time.monotonic()
# The canned reply each situation is answered from: the fixture the JVM tests parse for that very case
# (SonosFixturesTest), with the live values put into its elements.
POSITION = {
    "grouped": "position_x_rincon.xml",            # a member: x-rincon:<coordinator>
    "stopped_empty": "position_stopped_empty.xml",  # the migration / lost-session signature
    "STOPPED": "position_stopped_retained.xml",     # an episode's end, the uri kept
    "PAUSED_PLAYBACK": "position_paused.xml",
    "TRANSITIONING": "position_transitioning.xml",
    "PLAYING": "position_playing_ours.xml",
}
TRANSPORT = {
    "PLAYING": "transport_playing.xml",
    "PAUSED_PLAYBACK": "transport_paused.xml",
    "STOPPED": "transport_stopped.xml",
    "TRANSITIONING": "transport_transitioning.xml",
}
POSITION_TAGS = ("Track", "TrackDuration", "TrackMetaData", "TrackURI", "RelTime")


def log(msg):
    print(f"[{time.monotonic() - T0:7.1f}] {msg}", flush=True)


def fixture(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
        return f.read()


def put(template, tag, value):
    """The fixture with <tag>'s text replaced by value, escaped once (as the speaker sends it)."""
    pattern = re.compile(rf"<{tag}>.*?</{tag}>|<{tag}/>", re.S)
    if not pattern.search(template):
        raise KeyError(f"fixture has no <{tag}>")
    return pattern.sub(lambda _m: f"<{tag}>{escape(value)}</{tag}>", template, count=1)


def hms(sec):
    sec = max(0, int(sec or 0))
    return f"{sec // 3600}:{sec % 3600 // 60:02d}:{sec % 60:02d}"


def hms_to_s(s):
    try:
        h, m, sec = (s or "0:00:00").split(":")
        return int(h) * 3600 + int(m) * 60 + float(sec)
    except (ValueError, AttributeError):
        return None


class Fault(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


# --- design 54: the speaker's own queue, filled by a Spotify share link from server/testdata/spotify/ ---------------

def spotify_fixture(name):
    with open(os.path.join(SPOTIFY_DATA, name), encoding="utf-8") as f:
        return json.load(f)


def queue_row(item, album=None):
    """A queue item from a Spotify fixture's track or episode: its TrackURI as the 54-0 run showed it (sid 9, sn 6), its
    length, title, artists and album."""
    kind = item.get("type") or "track"
    return {"id": item["id"], "uri": f"x-sonos-spotify:spotify%3a{kind}%3a{item['id']}?sid=9&flags=8232&sn=6",
            "dur": (item.get("duration_ms") or 0) / 1000.0, "title": item.get("name") or "",
            "creator": ", ".join(a.get("name", "") for a in item.get("artists") or []),
            "album": (item.get("album") or {}).get("name") or album or (item.get("show") or {}).get("name") or ""}


def share_catalog():
    """(kind, id) -> the queue rows a share link of it adds: what the household's account plays of the fake Spotify's
    data (playable, not local, with an id), in Spotify's order - a playlist's episodes too."""
    def playable(items):
        return [i for i in items if i and i.get("id") and not i.get("is_local") and i.get("is_playable", True)]

    cat = {}
    pl = spotify_fixture("playlist_items.json")
    cat[("playlist", urllib.parse.urlsplit(pl["href"]).path.split("/")[3])] = \
        [queue_row(i) for i in playable(r.get("item") for r in pl["items"])]
    album = spotify_fixture("album.json")
    tracks = (album.get("tracks") or {}).get("items", []) + spotify_fixture("album_tracks.json").get("items", [])
    cat[("album", album["id"])] = [queue_row(i, album.get("name")) for i in playable(tracks)]
    show = spotify_fixture("show.json")
    cat[("show", show["id"])] = [queue_row(i, show.get("name")) for i in playable(spotify_fixture("show_episodes.json").get("items", []))]
    for name in ("track.json", "episode.json"):
        it = spotify_fixture(name)
        cat[(it["type"], it["id"])] = [queue_row(it)]
    return cat


def row_didl(row, item_id, parent):
    """A queue item's DIDL-Lite (browse_queue_page.xml's and position_spotify_queue.xml's shape), values escaped once."""
    return (f'<item id="{item_id}" parentID="{parent}" restricted="true">'
            f'<res protocolInfo="sonos.com-spotify:*:audio/x-spotify:*" duration="{hms(row["dur"])}">{escape(row["uri"])}</res>'
            f'<dc:title>{escape(row["title"])}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>'
            f'<dc:creator>{escape(row["creator"])}</dc:creator><upnp:album>{escape(row["album"])}</upnp:album></item>')


class Speaker:
    def __init__(self, name, port, uid):
        self.name, self.port, self.uid = name, port, uid
        self.coordinator = uid
        self.state = "STOPPED"
        self.uri = ""
        self.meta = ""
        self.duration = 0.0
        self.rel = 0.0
        self.since = None          # monotonic when PLAYING started counting
        self.transition_until = 0.0
        self.volume = 30
        self.mute = False
        self.reachable = True
        self.stalled = False
        # design 54: the speaker's own queue
        self.queue = []            # rows (queue_row)
        self.update_id = 1         # one more with every edit (the 54-0 run's queueVersion)
        self.from_queue = False    # the transport's source is x-rincon-queue:<uid>#0
        self.track = 0             # its 1-based position while from_queue
        self.play_mode = "NORMAL"
        self.shuffled = set()      # SHUFFLE_NOREPEAT: the positions played since the mode was set
        self.rng = random.Random(54)
        self.sleep_until = None    # monotonic: the sleep timer runs out

    def position(self):
        if self.state == "PLAYING" and self.since is not None and not self.stalled:
            return self.rel + (time.monotonic() - self.since)
        return self.rel

    def select(self, n):
        """The queue's position n (1-based; 0 for none) on the transport: its TrackURI, length and DIDL, from 0."""
        row = self.queue[n - 1] if 0 < n <= len(self.queue) else None
        self.track = n if row else 0
        self.uri = row["uri"] if row else ""
        self.duration = row["dur"] if row else 0.0
        self.meta = f"<DIDL-Lite {DIDL_NS}>{row_didl(row, '-1', '-1')}</DIDL-Lite>" if row else ""
        self.rel = 0.0
        self.since = time.monotonic() if self.state == "PLAYING" else None
        if row:
            self.shuffled.add(n)

    def settle(self):
        """The sleep timer runs out (paused); the track ends by itself at its duration: a direct uri STOPPED with the uri
        kept, a queue track the queue's own advance."""
        if self.sleep_until is not None and time.monotonic() >= self.sleep_until:
            self.sleep_until = None
            if self.state == "PLAYING":
                self.set_state("PAUSED_PLAYBACK")
                log(f"{self.name}: the sleep timer ran out - paused")
        if self.state == "PLAYING" and self.duration > 0 and self.position() >= self.duration:
            if self.from_queue:
                self.advance()
                return
            self.state, self.rel, self.since = "STOPPED", 0.0, None
            log(f"{self.name}: track ended (uri kept)")

    def advance(self):
        """The queue's own advance under the play mode: NORMAL the next position (STOPPED after the last, its track and
        TrackURI kept - provisional, design 54 [D7]); REPEAT_ALL wraps; REPEAT_ONE again; SHUFFLE_* a seeded pick."""
        over = max(0.0, self.position() - self.duration)
        n, last, mode = self.track, len(self.queue), self.play_mode
        if mode in ("REPEAT_ONE", "SHUFFLE_REPEAT_ONE"):
            nxt = n
        elif mode.startswith("SHUFFLE"):
            left = [p for p in range(1, last + 1) if p != n and (mode == "SHUFFLE" or p not in self.shuffled)]
            nxt = self.rng.choice(left) if left else None
        elif n < last:
            nxt = n + 1
        else:
            nxt = 1 if mode == "REPEAT_ALL" and last else None
        if nxt is None:
            self.state, self.rel, self.since = "STOPPED", 0.0, None
            log(f"{self.name}: the queue ended at position {n} (uri kept)")
            return
        self.select(nxt)
        self.rel = min(over, self.duration)
        log(f"{self.name}: the queue's own advance {n} -> {nxt}")

    def freeze(self):
        self.rel, self.since = self.position(), (time.monotonic() if self.state == "PLAYING" else None)

    def set_state(self, state):
        self.freeze()
        self.state = state
        self.since = time.monotonic() if state == "PLAYING" else None
        if state == "STOPPED":
            self.rel = 0.0


class Household:
    def __init__(self, speakers, advertise, transition_s, duration):
        self.speakers = speakers
        self.by_name = {s.name: s for s in speakers}
        self.by_uid = {s.uid: s for s in speakers}
        self.advertise = advertise
        self.transition_s = transition_s
        self.default_duration = duration
        self.lock = threading.RLock()
        self.script = []           # [(t, speaker, words)]
        self.started = None        # monotonic of the first Play
        # design 54: what a share link adds, and how the add is shaped
        self.catalog = share_catalog()
        self.extra_row = next(r for rows in self.catalog.values() for r in rows if r["id"] == EXTRA_TRACK)
        self.refuse_share = self.empty_share = self.queue_reversed = self.queue_extra = self.queue_gap = False
        self.track_seconds = 0.0

    def speaker(self, name):
        s = self.by_name.get(name)
        if s is None:
            raise SystemExit(f"fake-sonos: no speaker named {name!r} (have {', '.join(self.by_name)})")
        return s

    # -- the script --

    def load_script(self, text):
        for n, line in enumerate(text.splitlines(), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"t\+([0-9.]+)\s+(?:@(\S+)\s+)?(.+)$", line)
            if not m:
                raise SystemExit(f"fake-sonos: script line {n}: {line!r}")
            spk = self.speaker(m.group(2)) if m.group(2) else self.speakers[0]
            self.script.append((float(m.group(1)), spk, shlex.split(m.group(3))))
        self.script.sort(key=lambda e: e[0])

    def run_due(self):
        with self.lock:
            if self.started is None:
                return
            now = time.monotonic() - self.started
            while self.script and self.script[0][0] <= now:
                t, spk, words = self.script.pop(0)
                shown = " ".join(words) if words else "-"
                log(f"script t+{t:g} {spk.name}: {shown}")
                self.event(spk, words)

    def event(self, spk, words):
        verb = words[0]
        if verb in ("PLAYING", "PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING"):
            spk.set_state(verb)
            if len(words) > 1 and words[1] != "-":
                spk.uri = words[1]
            spk.coordinator = spk.uid
        elif verb.startswith("x-rincon:") or verb == "group":
            coord = verb.split(":", 1)[1] if verb.startswith("x-rincon:") else self.speaker(words[1]).uid
            spk.freeze()
            spk.coordinator = coord
        elif verb == "move":
            self.hand_over(spk, self.speaker(words[1]))
        elif verb == "drop":
            spk.reachable = False
        elif verb == "back":
            spk.reachable = True
        elif verb == "stall":
            spk.freeze()
            spk.stalled = True
        elif verb == "unstall":
            spk.stalled = False
            spk.since = time.monotonic() if spk.state == "PLAYING" else None
        elif verb == "volume":
            spk.volume = max(0, min(100, int(words[1])))
        elif verb in ("next", "previous"):
            n = spk.track + (1 if verb == "next" else -1)
            if spk.from_queue and 1 <= n <= len(spk.queue):
                spk.freeze()
                spk.select(n)
        elif verb == "queue-insert":
            spk.queue.insert(spk.track, dict(self.extra_row))
            spk.update_id += 1
        elif verb == "shuffle":
            spk.play_mode = "SHUFFLE_NOREPEAT"
            spk.shuffled = {spk.track}
        else:
            raise SystemExit(f"fake-sonos: unknown script event {verb!r}")

    def hand_over(self, spk, to):
        """Migration: spk's stream moves to `to` (its group's new coordinator), spk STOPPED "" and standalone."""
        spk.freeze()
        to.uri, to.meta, to.duration, to.rel = spk.uri, spk.meta, spk.duration, spk.rel
        # design 54 [D13]: a queue moves with the stream, at the same positions
        to.queue, to.from_queue, to.track, to.play_mode = list(spk.queue), spk.from_queue, spk.track, spk.play_mode
        to.update_id += 1
        to.coordinator = to.uid
        to.set_state(spk.state if spk.state in LIVE else "PLAYING")
        to.rel = spk.rel
        for other in self.speakers:
            if other.coordinator == spk.uid and other is not spk:
                other.coordinator = to.uid
        spk.coordinator = spk.uid
        spk.uri, spk.meta, spk.duration = "", "", 0.0
        spk.from_queue, spk.track = False, 0
        spk.set_state("STOPPED")

    def members_of(self, spk):
        return [s for s in self.speakers if s.coordinator == spk.uid and s is not spk]

    def join(self, spk, coord_uid):
        """`SetAVTransportURI(x-rincon:<uid>)` (soco `join`, design 53 §0.2): spk joins that coordinator's group; a
        stream it coordinated stops (its own members, if any, keep it on the next of them, as a real household does)."""
        coord = self.by_uid.get(coord_uid)
        if coord is None or coord is spk:
            raise Fault(714)  # Illegal MIME-Type / no such group: the speaker refuses it
        coord = self.by_uid.get(coord.coordinator, coord)
        if coord is spk:
            # a coordinator joining one of its own members: the app never sends it (joinOffered refuses a row holding
            # our uid), and the fake would hand its stream over first and end standalone - refused instead
            raise Fault(714)
        if spk.coordinator == spk.uid:
            members = self.members_of(spk)
            if members:
                self.hand_over(spk, members[0])
            else:
                spk.uri, spk.meta, spk.duration = "", "", 0.0
                spk.from_queue, spk.track = False, 0
                spk.set_state("STOPPED")
        spk.freeze()
        spk.coordinator = coord.uid
        log(f"{spk.name}: joined {coord.name}'s group")

    def standalone(self, spk):
        """`BecomeCoordinatorOfStandaloneGroup` (soco `unjoin`): a member leaves and stops, the rest play on; a
        coordinator's stream moves to its next member (as `move`) and it stands alone, STOPPED ""."""
        if spk.coordinator != spk.uid:
            spk.coordinator = spk.uid
            spk.uri, spk.meta, spk.duration = "", "", 0.0
            spk.from_queue, spk.track = False, 0
            spk.set_state("STOPPED")
            log(f"{spk.name}: left its group")
            return
        members = self.members_of(spk)
        if members:
            self.hand_over(spk, members[0])
            log(f"{spk.name}: left its group - the stream moved to {members[0].name}")

    # -- what a speaker reports --

    def view(self, spk):
        """(state, uri, rel, duration, meta) as this speaker's own transport reports them."""
        spk.settle()
        if spk.coordinator != spk.uid:
            coord = self.by_uid.get(spk.coordinator)
            if coord is not None:
                coord.settle()
                return coord.state, f"x-rincon:{coord.uid}", coord.position(), coord.duration, ""
            return "PLAYING", f"x-rincon:{spk.coordinator}", 0.0, 0.0, ""
        state = spk.state
        if state == "PLAYING" and time.monotonic() < spk.transition_until:
            state = "TRANSITIONING"
        return state, spk.uri, spk.position(), spk.duration, spk.meta

    def zone_group_state(self):
        """The household as zgs_wrapped.xml's document: its wrapper, and each member shaped like its first
        visible member (SoftwareVersion, SWGen, BootSeq, ...) with this speaker's uid, Location and name."""
        root, member = zgs_template()
        groups = {}
        for s in self.speakers:
            groups.setdefault(s.coordinator if s.coordinator in self.by_uid else s.uid, []).append(s)
        zone_groups = root.find("ZoneGroups")
        for coord, members in groups.items():
            g = ET.SubElement(zone_groups, "ZoneGroup", {"Coordinator": coord, "ID": f"{coord}:1"})
            for m in members:
                attrs = {"UUID": m.uid, "Location": f"http://{self.advertise}:{m.port}/xml/device_description.xml",
                         "ZoneName": m.name}
                attrs.update((k, v) for k, v in member.items() if k not in attrs)
                ET.SubElement(g, "ZoneGroupMember", attrs)
        return ET.tostring(root, encoding="unicode", short_empty_elements=False)

    # -- the actions --

    def act(self, spk, service, action, args):
        if service.endswith("AVTransport:1"):
            return self.av(spk, action, args)
        if service.endswith("RenderingControl:1"):
            if action == "GetVolume":
                return put(fixture("get_volume.xml"), "CurrentVolume", str(spk.volume))
            if action == "SetVolume":
                spk.volume = max(0, min(100, int(args.get("DesiredVolume", "0"))))
                return empty(service, action)
            if action == "GetMute":
                return put(fixture("get_mute.xml"), "CurrentMute", "1" if spk.mute else "0")
            if action == "SetMute":
                spk.mute = args.get("DesiredMute") in ("1", "true", "True")
                return empty(service, action)
        if service.endswith("ZoneGroupTopology:1") and action == "GetZoneGroupState":
            return put(fixture("zgs_wrapped.xml"), "ZoneGroupState", self.zone_group_state())
        if service.endswith("ContentDirectory:1") and action == "Browse":
            return self.browse(spk, args)
        raise Fault(401)  # Invalid Action

    # -- design 54: the queue --

    def shape(self, rows):
        """A share link's rows as the options shape them (copies): one of ours missing, an extra, reversed, short."""
        rows = [dict(r) for r in rows]
        if self.queue_gap and len(rows) > 2:
            del rows[2]
        if self.queue_extra:
            rows.insert(1, dict(self.extra_row))
        if self.queue_reversed:
            rows.reverse()
        if self.track_seconds:
            for r in rows:
                r["dur"] = self.track_seconds
        return rows

    def add_share(self, spk, args):
        """AddURIToQueue with SoCo's ShareLink request: the link's rows appended (DesiredFirstTrackNumberEnqueued 0)."""
        link = SHARE_URI.fullmatch(args.get("EnqueuedURI", ""))
        rows = self.catalog.get((link.group(1), link.group(2))) if link else None
        if self.refuse_share or rows is None or not SHARE_SN.search(args.get("EnqueuedURIMetaData", "")):
            raise Fault(800)  # the speaker's refusal of a link it can't play (the code is design 54 [D12]'s)
        rows = [] if self.empty_share else self.shape(rows)
        first = len(spk.queue) + 1 if rows else 0
        spk.queue += rows
        spk.update_id += 1
        body = put(fixture("add_uri_to_queue.xml"), "FirstTrackNumberEnqueued", str(first))
        return put(put(body, "NumTracksAdded", str(len(rows))), "NewQueueLength", str(len(spk.queue)))

    def browse(self, spk, args):
        """ContentDirectory Browse of Q:0 (SoCo's queue_size and get_queue): all six arguments or 402."""
        if any(k not in args for k in ("ObjectID", "BrowseFlag", "Filter", "StartingIndex", "RequestedCount", "SortCriteria")):
            raise Fault(402)
        if args["ObjectID"] != "Q:0":
            raise Fault(701)  # no such object
        if args["BrowseFlag"] == "BrowseMetadata":
            didl = (f'<DIDL-Lite {DIDL_NS}><container id="Q:0" parentID="Q:" restricted="true" childCount="{len(spk.queue)}">'
                    '<dc:title>Queue</dc:title><upnp:class>object.container.playlistContainer</upnp:class></container></DIDL-Lite>')
            body, returned = put(fixture("browse_queue_meta.xml"), "Result", didl), 1
        elif args["BrowseFlag"] == "BrowseDirectChildren":
            start, count = int(args["StartingIndex"] or 0), int(args["RequestedCount"] or 0)
            rows = spk.queue[start:start + count]
            items = "".join(row_didl(r, f"Q:0/{start + i + 1}", "Q:0") for i, r in enumerate(rows))
            body, returned = put(fixture("browse_queue_page.xml"), "Result", f"<DIDL-Lite {DIDL_NS}>{items}</DIDL-Lite>"), len(rows)
        else:
            raise Fault(402)
        body = put(put(body, "NumberReturned", str(returned)), "TotalMatches", str(len(spk.queue)))
        return put(body, "UpdateID", str(spk.update_id))

    def av(self, spk, action, args):
        if action == "GetPositionInfo":
            state, uri, rel, dur, meta = self.view(spk)
            body = fixture(position_fixture(state, uri))
            track = str(spk.track) if spk.from_queue and uri.startswith("x-sonos-spotify:") else ("1" if uri else "0")
            for tag, value in (("Track", track), ("TrackDuration", hms(dur)), ("TrackMetaData", meta),
                               ("TrackURI", uri), ("RelTime", hms(rel))):
                body = put(body, tag, value)
            return body
        if action == "AddURIToQueue":
            return self.add_share(spk, args)
        if action == "RemoveAllTracksFromQueue":
            spk.queue, spk.update_id = [], spk.update_id + 1
            if spk.from_queue:
                spk.set_state("STOPPED")
                spk.select(0)
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "SetPlayMode":
            if args.get("NewPlayMode") not in PLAY_MODES:
                raise Fault(402)
            spk.play_mode, spk.shuffled = args["NewPlayMode"], {spk.track}
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "GetTransportSettings":
            return put(fixture("transport_settings.xml"), "PlayMode", spk.play_mode)
        if action == "GetMediaInfo":
            current = f"x-rincon-queue:{spk.uid}#0" if spk.from_queue else spk.uri
            nr = len(spk.queue) if spk.from_queue else (1 if spk.uri else 0)
            return put(put(fixture("media_info_queue.xml"), "NrTracks", str(nr)), "CurrentURI", current)
        if action == "ConfigureSleepTimer":
            value = args.get("NewSleepTimerDuration", "")
            secs = hms_to_s(value) if value else None
            if value and secs is None:
                raise Fault(402)
            spk.sleep_until = time.monotonic() + secs if value else None
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "GetRemainingSleepTimerDuration":
            spk.settle()
            left = "" if spk.sleep_until is None else hms(max(0.0, spk.sleep_until - time.monotonic()))
            return put(fixture("sleep_timer.xml"), "RemainingSleepTimerDuration", left)
        if action == "SetAVTransportURI" and args.get("CurrentURI", "") == f"x-rincon-queue:{spk.uid}#0":
            spk.coordinator = spk.uid
            spk.from_queue = True
            spk.set_state("STOPPED")
            spk.select(1 if spk.queue else 0)
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "Seek" and args.get("Unit") == "TRACK_NR":
            n = int(args.get("Target") or 0) if (args.get("Target") or "").isdigit() else 0
            if not spk.from_queue or not 1 <= n <= len(spk.queue):
                raise Fault(711)
            spk.freeze()
            spk.select(n)
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "GetTransportInfo":
            state = self.view(spk)[0]
            return put(fixture(TRANSPORT.get(state, "transport_playing.xml")), "CurrentTransportState", state)
        if action == "BecomeCoordinatorOfStandaloneGroup":
            self.standalone(spk)
            body = put(fixture("become_standalone.xml"), "DelegatedGroupCoordinatorID", spk.uid)
            return put(body, "NewGroupID", f"{spk.uid}:1")
        if action == "SetAVTransportURI" and args.get("CurrentURI", "").startswith("x-rincon:"):
            self.join(spk, args["CurrentURI"].split(":", 1)[1])
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "SetAVTransportURI":
            spk.coordinator = spk.uid  # a play on a member makes it standalone again
            spk.from_queue, spk.track = False, 0
            spk.uri, spk.meta = args.get("CurrentURI", ""), args.get("CurrentURIMetaData", "")
            m = re.search(r'duration="([0-9:]+)"', spk.meta)
            spk.duration = (hms_to_s(m.group(1)) if m else None) or self.default_duration
            spk.set_state("STOPPED")
            return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)
        if action == "Play":
            if not spk.uri:
                raise Fault(701)
            if spk.state != "PLAYING":
                spk.set_state("PLAYING")
                spk.transition_until = time.monotonic() + self.transition_s
            if self.started is None:
                self.started = time.monotonic()
                log("session start: the script's clock runs")
        elif action == "Pause":
            spk.set_state("PAUSED_PLAYBACK")
        elif action == "Stop":
            spk.set_state("STOPPED")
        elif action == "Seek":
            state = self.view(spk)[0]
            if state not in LIVE:
                raise Fault(701)
            target = hms_to_s(args.get("Target"))
            if target is None or (spk.duration and target > spk.duration):
                raise Fault(711)
            spk.freeze()
            spk.rel = target
        else:
            raise Fault(401)
        return empty(SERVICES["/MediaRenderer/AVTransport/Control"], action)


def empty(service, action):
    return fixture("empty_response.xml").replace("PlayResponse", f"{action}Response").replace(
        "urn:schemas-upnp-org:service:AVTransport:1", service)


def position_fixture(state, uri):
    """GetPositionInfo's template for what the speaker's own transport reports."""
    if uri.startswith("x-rincon:"):
        return POSITION["grouped"]
    if uri.startswith("x-sonos-spotify:"):
        return "position_spotify_queue.xml"  # design 54: a track of the speaker's own queue
    if state == "STOPPED" and not uri:
        return POSITION["stopped_empty"]
    return POSITION.get(state, POSITION["PLAYING"])


def zgs_template():
    """(zgs_wrapped.xml's ZoneGroupState with its ZoneGroups emptied, its first visible member's attributes)."""
    reply = ET.fromstring(fixture("zgs_wrapped.xml").encode("utf-8"))
    inner = next(e for e in reply.iter() if e.tag.split("}", 1)[-1] == "ZoneGroupState")
    root = ET.fromstring(inner.text)
    member = next(m for m in root.iter("ZoneGroupMember") if m.get("Invisible") != "1")
    template = {k: v for k, v in member.attrib.items() if k != "Invisible"}
    zone_groups = root.find("ZoneGroups")
    for g in list(zone_groups):
        zone_groups.remove(g)
    return root, template


def fault(code):
    """The fixture of that fault when there is one (fault_701/710/711/500.xml), else 701's with the code."""
    name = f"fault_{code}.xml"
    if os.path.exists(os.path.join(FIXTURES, name)):
        return fixture(name)
    return put(fixture("fault_701.xml"), "errorCode", str(code))


def parse_call(body):
    """(action, {arg: value}) of a SOAP request."""
    root = ET.fromstring(body)
    call = list(root.find(f"{{{SOAP_NS}}}Body"))[0]
    action = call.tag.split("}", 1)[-1]
    return action, {c.tag.split("}", 1)[-1]: (c.text or "") for c in call}


def handler_for(house, spk):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def send(self, code, text, ctype='text/xml; charset="utf-8"'):
            out = text.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)

        def unreachable(self):
            house.run_due()
            if spk.reachable:
                return False
            self.close_connection = True  # no answer at all: the client reads a dropped connection
            return True

        def do_GET(self):
            if self.unreachable():
                return
            with house.lock:
                if self.path == "/xml/device_description.xml":
                    text = put(put(fixture("device_description.xml"), "UDN", f"uuid:{spk.uid}"), "roomName", spk.name)
                    self.send(200, text)
                elif self.path == "/status/accounts":
                    self.send(200, fixture("accounts.xml"))
                else:
                    self.send(404, "not found", "text/plain")
            log(f"{spk.name}:{spk.port} GET {self.path}")

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n).decode("utf-8")
            if self.unreachable():
                log(f"{spk.name}:{spk.port} (dropped) POST {self.path}")
                return
            service = SERVICES.get(self.path)
            soapaction = (self.headers.get("SOAPACTION") or "").strip('"')
            try:
                action, args = parse_call(body)
            except (ET.ParseError, IndexError, TypeError):
                self.send(400, "bad SOAP", "text/plain")
                return
            shown = {k: (v if len(v) <= 60 else v[:57] + "...") for k, v in args.items() if k != "InstanceID"}
            if service is None or soapaction != f"{service}#{action}":
                log(f"{spk.name}:{spk.port} REFUSED {self.path} SOAPACTION={soapaction!r}")
                self.send(500, fault(401))
                return
            refused = None
            with house.lock:
                try:
                    reply, code = house.act(spk, service, action, args), 200
                except Fault as f:
                    refused = f.code
                    reply, code = fault(f.code), 500
            outcome = f" -> fault {refused}" if refused is not None else ""
            log(f"{spk.name}:{spk.port} {service.rsplit(':', 2)[-2]}#{action} {shown}{outcome}")
            self.send(code, reply)

    return Handler


def build(args):
    speakers = []
    for i, part in enumerate(args.speakers.split(",")):
        name, _, port = part.strip().rpartition(":")
        if not name or not port.isdigit():
            raise SystemExit(f"fake-sonos: --speakers wants Name:port, got {part!r}")
        # Sonos-shaped (RINCON_ + 12-20 of [A-Z0-9], design 26a §5.5), naming the port; port 0 (the
        # selftest's) takes a number of its own
        speakers.append(Speaker(name, int(port), f"RINCON_FAKE{int(port) or 90001 + i:05d}01400"))
    house = Household(speakers, args.advertise, args.transition_s, args.duration)
    for spk in speakers:
        spk.volume = max(0, min(100, args.volume))
    for opt in ("refuse_share", "empty_share", "queue_reversed", "queue_extra", "queue_gap"):
        setattr(house, opt, bool(getattr(args, opt, False)))
    house.track_seconds = float(getattr(args, "track_seconds", 0.0) or 0.0)
    if args.group:
        names = args.group.split("+")
        coord = house.speaker(names[0])
        for n in names[1:]:
            house.speaker(n).coordinator = coord.uid
    if args.script:
        with open(args.script, encoding="utf-8") as f:
            house.load_script(f.read())
    return house


def serve(house, bind):
    servers = []
    for spk in house.speakers:
        srv = ThreadingHTTPServer((bind, spk.port), handler_for(house, spk))
        spk.port = srv.server_address[1]  # port 0 -> the one the system chose (the selftest)
        servers.append(srv)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
    return servers


def seed(house):
    return json.dumps({"players": [{"uid": s.uid, "name": s.name, "ip": house.advertise, "port": s.port}
                                   for s in house.speakers]}, ensure_ascii=False, indent=2)


# --- the selftest ---------------------------------------------------------------------------------

def selftest():
    # 1. Every fixture is well-formed, and every template has the elements this fake fills in.
    names = sorted(os.listdir(FIXTURES))
    for name in names:
        if name.endswith(".xml"):
            ET.fromstring(fixture(name).encode("utf-8"))
    templates = [(n, POSITION_TAGS) for n in sorted(set(POSITION.values()))] + \
        [(n, ("CurrentTransportState",)) for n in sorted(set(TRANSPORT.values()))] + [
        ("zgs_wrapped.xml", ("ZoneGroupState",)), ("get_volume.xml", ("CurrentVolume",)), ("get_mute.xml", ("CurrentMute",)),
        ("fault_701.xml", ("errorCode",)), ("device_description.xml", ("UDN", "roomName")),
        ("become_standalone.xml", ("DelegatedGroupCoordinatorID", "NewGroupID")),
        # design 54: the queue's replies
        ("position_spotify_queue.xml", POSITION_TAGS),
        ("add_uri_to_queue.xml", ("FirstTrackNumberEnqueued", "NumTracksAdded", "NewQueueLength")),
        ("browse_queue_meta.xml", ("Result", "NumberReturned", "TotalMatches", "UpdateID")),
        ("browse_queue_page.xml", ("Result", "NumberReturned", "TotalMatches", "UpdateID")),
        ("transport_settings.xml", ("PlayMode",)), ("media_info_queue.xml", ("NrTracks", "CurrentURI")),
        ("sleep_timer.xml", ("RemainingSleepTimerDuration",))]
    for name, tags in templates:
        for tag in tags:
            put(fixture(name), tag, "x")
    root, member = zgs_template()
    assert root.tag == "ZoneGroupState" and root.find("ZoneGroups") is not None and len(root.find("ZoneGroups")) == 0
    assert member.get("SoftwareVersion") and "UUID" in member, member
    used = {n for n, _ in templates} | {"empty_response.xml", "accounts.xml"} | \
        {n for n in names if re.fullmatch(r"fault_\d+\.xml", n)}
    print(f"1. {len(names)} fixtures parse; {len(used)} of them answer, each template carries what the fake fills OK")

    ns = argparse.Namespace(speakers="Stua:0,Kjøkken:0", advertise="127.0.0.1", transition_s=0.3, duration=0.0,
                            group=None, script=None, volume=30)
    house = build(ns)
    servers = serve(house, "127.0.0.1")
    stua, kjokken = house.speakers

    def get(spk, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{spk.port}{path}", timeout=5) as r:
            return r.read().decode("utf-8")

    def soap(spk, path, action, args=()):
        service = SERVICES[path]
        instance = (("InstanceID", "0"),) if path.startswith("/MediaRenderer") else ()
        inner = "".join(f"<{k}>{escape(v)}</{k}>" for k, v in instance + tuple(args))
        body = (f'<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="{SOAP_NS}"><s:Body>'
                f'<u:{action} xmlns:u="{service}">{inner}</u:{action}></s:Body></s:Envelope>').encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{spk.port}{path}", data=body, headers={
            "SOAPACTION": f'"{service}#{action}"', "Content-Type": 'text/xml; charset="utf-8"'})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                code, text = r.status, r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            code, text = e.code, e.read().decode("utf-8")
        replies.append(text)
        root = ET.fromstring(text.encode("utf-8"))
        first = list(root.find(f"{{{SOAP_NS}}}Body"))[0]
        if first.tag.endswith("Fault"):
            err = first.find(".//{urn:schemas-upnp-org:control-1-0}errorCode")
            return code, {"fault": err.text}
        assert code == 200, code
        return code, {c.tag.split("}", 1)[-1]: (c.text or "") for c in first}

    replies = []

    AV, RC, ZGT = ("/MediaRenderer/AVTransport/Control", "/MediaRenderer/RenderingControl/Control",
                   "/ZoneGroupTopology/Control")
    try:
        # 2. the device description, the accounts, the topology with the speakers' own ports
        assert f"<UDN>uuid:{stua.uid}</UDN>" in get(stua, "/xml/device_description.xml")
        assert 'Type="70919" SerialNum="7"' in get(stua, "/status/accounts")
        zgs = soap(stua, ZGT, "GetZoneGroupState")[1]["ZoneGroupState"]
        assert zgs.startswith("<ZoneGroupState><ZoneGroups>"), zgs[:60]
        assert f'Location="http://127.0.0.1:{kjokken.port}/xml/device_description.xml"' in zgs
        assert 'ZoneName="Kjøkken"' in zgs
        assert 'SoftwareVersion="70.3-35220"' in zgs and 'ChannelMapSet=""' in zgs, "members shaped like the fixture's"
        assert zgs.endswith("<VanishedDevices></VanishedDevices></ZoneGroupState>"), zgs[-60:]
        print("2. device description, accounts, topology (the fixture's shape) with each speaker's port OK")

        # 3. a play: the DIDL arrives once-escaped and is echoed back, TRANSITIONING then PLAYING, seek
        uri = "https://podkast.example/ep12.mp3?a=1&b=2"
        meta = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="vibb-1"><dc:title>Ole &amp; Dole'
                '</dc:title><res protocolInfo="http-get:*:audio/mpeg:*" duration="0:24:13">'
                'https://podkast.example/ep12.mp3?a=1&amp;b=2</res></item></DIDL-Lite>')
        assert soap(stua, AV, "Seek", (("Unit", "REL_TIME"), ("Target", "0:01:00")))[1] == {"fault": "701"}, "seek before a uri"
        soap(stua, AV, "RemoveAllTracksFromQueue")
        soap(stua, AV, "SetAVTransportURI", (("CurrentURI", uri), ("CurrentURIMetaData", meta)))
        soap(stua, AV, "Play", (("Speed", "1"),))
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "TRANSITIONING"
        time.sleep(0.35)
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "PLAYING"
        pos = soap(stua, AV, "GetPositionInfo")[1]
        assert pos["TrackURI"] == uri and pos["TrackDuration"] == "0:24:13" and pos["TrackMetaData"] == meta, pos
        soap(stua, AV, "Seek", (("Unit", "REL_TIME"), ("Target", "0:07:11")))
        assert soap(stua, AV, "GetPositionInfo")[1]["RelTime"] in ("0:07:11", "0:07:12")
        assert soap(stua, AV, "Seek", (("Unit", "REL_TIME"), ("Target", "9:00:00")))[1] == {"fault": "711"}
        assert replies[-1] == fixture("fault_711.xml"), "a fault with a fixture is that fixture"
        soap(stua, AV, "Pause")
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "PAUSED_PLAYBACK"
        assert replies[-1] == fixture("transport_paused.xml"), "PAUSED answers from transport_paused.xml"
        soap(stua, RC, "SetVolume", (("Channel", "Master"), ("DesiredVolume", "140")))
        assert soap(stua, RC, "GetVolume", (("Channel", "Master"),))[1]["CurrentVolume"] == "100"
        soap(stua, RC, "SetMute", (("Channel", "Master"), ("DesiredMute", "1")))
        assert soap(stua, RC, "GetMute", (("Channel", "Master"),))[1]["CurrentMute"] == "1"
        assert soap(stua, AV, "Next")[1] == {"fault": "401"}
        assert replies[-1] == put(fixture("fault_701.xml"), "errorCode", "401"), "no fixture for 401: 701's with the code"
        print("3. play, DIDL echo, transitioning, seek (701 when stopped, 711 past the end), pause, volume OK")

        # 4. the script: grouped away, migration, unreachable
        soap(stua, AV, "Play", (("Speed", "1"),))
        house.load_script("t+0 x-rincon:%s\n" % kjokken.uid)
        house.run_due()
        member = soap(stua, AV, "GetPositionInfo")[1]
        assert member["TrackURI"] == f"x-rincon:{kjokken.uid}", member
        expected = fixture("position_x_rincon.xml")
        for tag in POSITION_TAGS:
            expected = put(expected, tag, member[tag])
        assert replies[-1] == expected, "a member answers from position_x_rincon.xml"
        zgs = soap(kjokken, ZGT, "GetZoneGroupState")[1]["ZoneGroupState"]
        assert zgs.count("<ZoneGroup ") == 1, "both in Kjøkken's group"
        house.load_script("t+0 PLAYING -\nt+0 move Kjøkken\n")
        house.run_due()
        own = soap(stua, AV, "GetPositionInfo")[1]
        assert replies[-1] == fixture("position_stopped_empty.xml"), "the migration signature IS the fixture"
        assert own["TrackURI"] == "" and soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "STOPPED", own
        assert replies[-1] == fixture("transport_stopped.xml")
        assert soap(kjokken, AV, "GetPositionInfo")[1]["TrackURI"] == uri
        house.load_script("t+0 drop\n")
        house.run_due()
        try:
            soap(stua, AV, "GetTransportInfo")
            raise AssertionError("a dropped speaker must not answer")
        except (urllib.error.URLError, ConnectionError, http.client.HTTPException):
            pass
        house.load_script("t+0 back\n")
        house.run_due()
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "STOPPED"
        print("4. script: grouped away (x-rincon), migration (move), drop and back OK")

        # 4b. the grouping verbs (design 53 §0.2, §2.6): a join with x-rincon: and empty metadata stops the room's own
        # stream; a member's BecomeCoordinatorOfStandaloneGroup stops it, the rest play on; the coordinator's moves the
        # stream to its next member (as `move`)
        other = "https://podkast.example/other.mp3"
        soap(stua, AV, "SetAVTransportURI", (("CurrentURI", other), ("CurrentURIMetaData", "")))
        soap(stua, AV, "Play", (("Speed", "1"),))
        soap(stua, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon:{kjokken.uid}"), ("CurrentURIMetaData", "")))
        assert soap(stua, AV, "GetPositionInfo")[1]["TrackURI"] == f"x-rincon:{kjokken.uid}"
        assert stua.uri == "", "the joining room's own stream stopped"
        assert soap(kjokken, ZGT, "GetZoneGroupState")[1]["ZoneGroupState"].count("<ZoneGroup ") == 1
        left = soap(stua, AV, "BecomeCoordinatorOfStandaloneGroup")[1]
        assert left["DelegatedGroupCoordinatorID"] == stua.uid and left["NewGroupID"] == f"{stua.uid}:1", left
        assert soap(stua, AV, "GetPositionInfo")[1]["TrackURI"] == ""
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "STOPPED"
        assert soap(kjokken, AV, "GetPositionInfo")[1]["TrackURI"] == uri, "the rest play on"
        assert soap(kjokken, ZGT, "GetZoneGroupState")[1]["ZoneGroupState"].count("<ZoneGroup ") == 2
        soap(stua, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon:{kjokken.uid}"), ("CurrentURIMetaData", "")))
        soap(kjokken, AV, "BecomeCoordinatorOfStandaloneGroup")
        assert soap(stua, AV, "GetPositionInfo")[1]["TrackURI"] == uri, "the coordinator's stream moved to its member"
        assert soap(kjokken, AV, "GetPositionInfo")[1]["TrackURI"] == ""
        assert soap(kjokken, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon:{kjokken.uid}"), ("CurrentURIMetaData", "")))[1] == {"fault": "714"}
        soap(kjokken, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon:{stua.uid}"), ("CurrentURIMetaData", "")))
        assert soap(stua, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon:{kjokken.uid}"), ("CurrentURIMetaData", "")))[1] == {"fault": "714"}, \
            "a coordinator joining its own member is refused"
        assert soap(kjokken, AV, "GetPositionInfo")[1]["TrackURI"] == f"x-rincon:{stua.uid}"
        print("4b. grouping: join (x-rincon:, the room's own stream stops), a member's leave, the coordinator's leave (the stream moves) OK")

        # 5. the seed file the debug feed reads
        assert f'"port": {kjokken.port}' in seed(house)
        print("5. debug-feed seed OK")

        # 6. design 54: the speaker's own queue - SoCo's request as the golden pins it added, browsed, played from, its
        # own advance and end, the play modes, the buttons, an edit, the sleep timer, the park's clear, the add's options
        CD = "/MediaServer/ContentDirectory/Control"
        didl_ns = "{urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/}"

        def browse(flag, start, count, sort=True):
            args = (("ObjectID", "Q:0"), ("BrowseFlag", flag), ("Filter", "*"), ("StartingIndex", str(start)),
                    ("RequestedCount", str(count))) + ((("SortCriteria", ""),) if sort else ())
            return soap(stua, CD, "Browse", args)[1]

        def ids(rows):
            return [r["id"][:11] for r in rows]

        def near_end(spk):
            spk.freeze()
            spk.rel = spk.duration - 0.05
            time.sleep(0.1)

        golden = {c.tag: (c.text or "") for c in ET.fromstring(fixture("sharelink_playlist.xml").encode("utf-8"))}
        add_args = tuple((k, golden[k]) for k in ("EnqueuedURI", "EnqueuedURIMetaData", "DesiredFirstTrackNumberEnqueued", "EnqueueAsNext"))
        soap(stua, AV, "SetPlayMode", (("NewPlayMode", "NORMAL"),))
        soap(stua, AV, "RemoveAllTracksFromQueue")
        version = stua.update_id
        added = soap(stua, AV, "AddURIToQueue", add_args)[1]
        assert added == {"FirstTrackNumberEnqueued": "1", "NumTracksAdded": "5", "NewQueueLength": "5"}, added
        assert ids(stua.queue) == ["1FakeTrackA", "5FakeEpisod", "1FakeTrackC", "1FakeTrackA", "1FakeTrackD"], ids(stua.queue)
        assert stua.update_id == version + 1, "an edit moves the UpdateID"
        meta = browse("BrowseMetadata", 0, 1)
        assert 'childCount="5"' in meta["Result"] and meta["UpdateID"] == str(stua.update_id), meta
        page = browse("BrowseDirectChildren", 1, 2)
        res = [e.text for e in ET.fromstring(page["Result"]).iter(f"{didl_ns}res")]
        assert res == [stua.queue[1]["uri"], stua.queue[2]["uri"]] and page["NumberReturned"] == "2" and page["TotalMatches"] == "5", page
        assert res[1] == "x-sonos-spotify:spotify%3atrack%3a1FakeTrackC00000000003?sid=9&flags=8232&sn=6"
        assert browse("BrowseDirectChildren", 0, 100, sort=False) == {"fault": "402"}, "every Browse carries SortCriteria"
        # play_from_queue: the queue as the source, Seek TRACK_NR, Play
        soap(stua, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon-queue:{stua.uid}#0"), ("CurrentURIMetaData", "")))
        soap(stua, AV, "Seek", (("Unit", "TRACK_NR"), ("Target", "3")))
        soap(stua, AV, "Play", (("Speed", "1"),))
        time.sleep(0.35)
        pos = soap(stua, AV, "GetPositionInfo")[1]
        assert pos["Track"] == "3" and pos["TrackURI"] == res[1] and pos["TrackDuration"] == "0:03:42", pos
        assert "<dc:title>Månesang</dc:title>" in pos["TrackMetaData"]
        expected = fixture("position_spotify_queue.xml")
        for tag in POSITION_TAGS:
            expected = put(expected, tag, pos[tag])
        assert replies[-1] == expected, "a queue track answers from position_spotify_queue.xml"
        assert soap(stua, AV, "Seek", (("Unit", "TRACK_NR"), ("Target", "9")))[1] == {"fault": "711"}
        media = soap(stua, AV, "GetMediaInfo")[1]
        assert media["NrTracks"] == "5" and media["CurrentURI"] == f"x-rincon-queue:{stua.uid}#0", media
        near_end(stua)
        assert soap(stua, AV, "GetPositionInfo")[1]["Track"] == "4", "the speaker's own advance, gapless"
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "PLAYING"
        soap(stua, AV, "Seek", (("Unit", "TRACK_NR"), ("Target", "5")))
        near_end(stua)
        end = soap(stua, AV, "GetPositionInfo")[1]
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "STOPPED"
        assert end["Track"] == "5" and "1FakeTrackD" in end["TrackURI"], "NORMAL ends after the last, its track kept"
        soap(stua, AV, "SetPlayMode", (("NewPlayMode", "REPEAT_ALL"),))
        assert soap(stua, AV, "GetTransportSettings")[1]["PlayMode"] == "REPEAT_ALL"
        assert replies[-1] == put(fixture("transport_settings.xml"), "PlayMode", "REPEAT_ALL")
        assert soap(stua, AV, "SetPlayMode", (("NewPlayMode", "LOUD"),))[1] == {"fault": "402"}
        soap(stua, AV, "Play", (("Speed", "1"),))
        time.sleep(0.35)
        near_end(stua)
        assert soap(stua, AV, "GetPositionInfo")[1]["Track"] == "1", "REPEAT_ALL wraps"
        soap(stua, AV, "SetPlayMode", (("NewPlayMode", "NORMAL"),))
        # the speaker's buttons, an edit from the Sonos app, its shuffle
        version = stua.update_id
        house.load_script("t+0 next\nt+0 queue-insert\nt+0 shuffle\n")
        house.run_due()
        assert stua.track == 2 and ids(stua.queue)[2] == "1FakeTrackG" and stua.update_id == version + 1, ids(stua.queue)
        assert soap(stua, AV, "GetTransportSettings")[1]["PlayMode"] == "SHUFFLE_NOREPEAT"
        house.load_script("t+0 previous\n")
        house.run_due()
        assert soap(stua, AV, "GetPositionInfo")[1]["Track"] == "1"
        # the sleep timer: set, read back, cancelled; at 0 the speaker pauses (provisional, [D21])
        soap(stua, AV, "ConfigureSleepTimer", (("NewSleepTimerDuration", "1:00:00"),))
        assert soap(stua, AV, "GetRemainingSleepTimerDuration")[1]["RemainingSleepTimerDuration"] in ("1:00:00", "0:59:59")
        soap(stua, AV, "ConfigureSleepTimer", (("NewSleepTimerDuration", ""),))
        assert soap(stua, AV, "GetRemainingSleepTimerDuration")[1]["RemainingSleepTimerDuration"] == ""
        soap(stua, AV, "ConfigureSleepTimer", (("NewSleepTimerDuration", "0:00:00"),))
        assert soap(stua, AV, "GetTransportInfo")[1]["CurrentTransportState"] == "PAUSED_PLAYBACK", "the timer ran out"
        # the park: Stop, the queue cleared, the queue as the source - the speaker's Play button plays nothing
        soap(stua, AV, "Stop")
        soap(stua, AV, "RemoveAllTracksFromQueue")
        soap(stua, AV, "SetAVTransportURI", (("CurrentURI", f"x-rincon-queue:{stua.uid}#0"), ("CurrentURIMetaData", "")))
        parked = soap(stua, AV, "GetPositionInfo")[1]
        assert parked["TrackURI"] == "" and parked["Track"] == "0" and 'childCount="0"' in browse("BrowseMetadata", 0, 1)["Result"]
        assert soap(stua, AV, "Play", (("Speed", "1"),))[1] == {"fault": "701"}
        # the add's options
        house.refuse_share = True
        assert soap(stua, AV, "AddURIToQueue", add_args)[1] == {"fault": "800"}
        assert replies[-1] == fixture("fault_800.xml")
        house.refuse_share, house.empty_share = False, True
        assert soap(stua, AV, "AddURIToQueue", add_args)[1]["NumTracksAdded"] == "0"
        house.empty_share = False
        house.queue_gap = house.queue_extra = house.queue_reversed = True
        house.track_seconds = 2.0
        assert soap(stua, AV, "AddURIToQueue", add_args)[1]["NewQueueLength"] == "5"
        assert ids(stua.queue) == ["1FakeTrackD", "1FakeTrackA", "5FakeEpisod", "1FakeTrackG", "1FakeTrackA"], ids(stua.queue)
        assert all(r["dur"] == 2.0 for r in stua.queue)
        unknown = (("EnqueuedURI", "spotify%3atrack%3a1FakeTrackZ00000000099"),) + add_args[1:]
        assert soap(stua, AV, "AddURIToQueue", unknown)[1] == {"fault": "800"}, "a link the household can't play"
        print("6. the queue (design 54): SoCo's add, Browse paged with UpdateID, play_from_queue, the own advance and end, "
              "the modes, buttons, an edit, the sleep timer, the park's clear, the add's options OK")
    finally:
        for s in servers:
            s.shutdown()
    print("\nFAKE SONOS SELFTEST OK - answers built from the same fixtures the JVM tests parse.")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--speakers", default="Stua:1401,Kjøkken:1402", help="Name:port,... (default %(default)s)")
    p.add_argument("--bind", default="127.0.0.1",
                   help="address to listen on (default %(default)s: the emulator's 10.0.2.2 is the host's loopback)")
    p.add_argument("--advertise", default="10.0.2.2",
                   help="the ip the topology's Locations name: the host as the emulator sees it (default %(default)s)")
    p.add_argument("--group", help="an initial group, coordinator first: Stua+Kjøkken")
    p.add_argument("--script", help="scripted events, see above")
    p.add_argument("--duration", type=float, default=0.0,
                   help="track length in seconds when the DIDL has none (default: none, reads 0:00:00)")
    p.add_argument("--transition-s", type=float, default=0.5, help="TRANSITIONING after Play (default %(default)s)")
    p.add_argument("--volume", type=int, default=30, help="every speaker's volume at the start (default %(default)s)")
    p.add_argument("--refuse-share", action="store_true", help="design 54: every AddURIToQueue is fault 800 (no service takes the link)")
    p.add_argument("--empty-share", action="store_true", help="design 54: a share link adds 0 tracks")
    p.add_argument("--queue-reversed", action="store_true", help="design 54: the added queue in reverse order")
    p.add_argument("--queue-extra", action="store_true", help="design 54: a track our listing lacks at the added queue's position 2")
    p.add_argument("--queue-gap", action="store_true", help="design 54: one of our tracks missing from the added queue")
    p.add_argument("--track-seconds", type=float, default=0.0, help="design 54: every queue track this long (default: its own)")
    p.add_argument("--print-seed", action="store_true", help="print debug-feed/sonos.json for these speakers and exit")
    p.add_argument("--selftest", action="store_true", help="check the fixtures and the fake against itself, then exit")
    args = p.parse_args()
    if args.selftest:
        selftest()
        return
    house = build(args)
    if args.print_seed:
        print(seed(house))
        return
    serve(house, args.bind)
    for s in house.speakers:
        log(f"{s.name} ({s.uid}) on {args.bind}:{s.port}, advertised as {args.advertise}:{s.port}")
    log("debug-feed/sonos.json for these speakers: --print-seed")
    try:
        while True:
            time.sleep(0.5)
            house.run_due()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
