#!/usr/bin/env python3
"""The server's own rows for design 125 step 2 (the Apple Wi-Fi lookup): server/testdata/wifi_lookup/server.json.

An independent protobuf encoder (standard library only) writes:

- `golden`: the request the server must send for three BSSIDs and 100 surrounding APs - GrapheneOS NetworkLocation's
  header and `ALSLocationRequest` (MIT, Copyright (c) 2024-2025 GrapheneOS; proto/apple_wps.proto at 474472e), the
  repeated enum packed as proto3 does. Check it once against protoc when one is at hand:
      printf 'wireless_aps { mac_id: "00:00:5e:00:53:01" } ... number_of_surrounding_wifis: 100
      surrounding_wifi_bands: K2DOT4GHZ surrounding_wifi_bands: K5GHZ
      wifi_altitude_scale: KWIFI_ALTITUDE_SCALE_10_TO_THE_2 meta { software_build: "macOS15.4/24E248" product_id: "arm64" }' |
      protoc --encode=app.grapheneos.networklocation.proto.ALSLocationRequest apple_wps.proto | xxd -p
  (the message after the header's 47 bytes).
- `apple`: fake answers of Apple's service, each with what the server must read from it. Documentation MACs only (RFC
  7042, 00:00:5e:00:53:xx), made-up coordinates; no real capture is ever committed.
- `health`: failure histories and whether the orange "Apple lookups aren't working" shows.

    python3 -I scripts/wifi-lookup-fixtures.py --write     # writes the file
    python3 -I scripts/wifi-lookup-fixtures.py --selftest  # the committed file is exactly what this writes
"""
# The request format is GrapheneOS NetworkLocation's (proto/apple_wps.proto, ApplePositioningService.kt at 474472e):
#
# Copyright © 2024-2025 GrapheneOS
#
# Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
# documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
# rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
# persons to whom the Software is furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
# Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
# WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
# COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
# OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
import json
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", "testdata", "wifi_lookup", "server.json")
UNKNOWN = -18000000000


def varint(n):
    n &= (1 << 64) - 1
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def key(field, wire):
    return varint((field << 3) | wire)


def ld(field, payload):
    return key(field, 2) + varint(len(payload)) + payload


def vi(field, n):
    return key(field, 0) + varint(n)


def mac(n, short=False):
    octets = [0x00, 0x00, 0x5E, 0x00, 0x53, n]
    return ":".join(("%x" if short else "%02x") % o for o in octets)


def location(lat=None, lng=0, acc=None, extra=b""):
    body = b""
    if lat is not None:
        body += vi(1, lat)
    if lng is not None:
        body += vi(2, lng)
    if acc is not None:
        body += vi(3, acc)
    return body + extra


def ap(mac_id, loc=None, extra=b""):
    body = ld(1, mac_id.encode())
    if loc is not None:
        body += ld(2, loc)
    return ld(2, body + extra)


HEADER = bytes([0, 1, 0, 0, 0, 1, 0, 0, 0, 0])


def answer(*aps, header=HEADER):
    return header + b"".join(aps)


def deg(e8):
    # The server keeps 1e-7 degrees; these fixtures use whole 1e-7 values.
    assert e8 % 10 == 0
    return round(e8 / 1e8, 7)


def known(n, lat, lng, acc):
    return [mac(n), deg(lat), deg(lng), acc]


def golden():
    bssids = [mac(1), mac(2), mac(3)]
    message = b"".join(ld(2, ld(1, b.encode())) for b in bssids)
    message += vi(4, 100) + ld(31, bytes([1, 2])) + vi(32, 2)
    message += ld(33, ld(1, b"macOS15.4/24E248") + ld(2, b"arm64"))
    head = (1).to_bytes(2, "big")
    for text in (b"en-US_US", b"com.apple.locationd", b"15.4.24E248"):
        head += len(text).to_bytes(2, "big") + text
    head += (1).to_bytes(4, "big") + len(message).to_bytes(4, "big")
    return {
        "bssids": bssids,
        "surrounding": 100,
        "hex": (head + message).hex(),
        "origin": "scripts/wifi-lookup-fixtures.py's own encoder from GrapheneOS's apple_wps.proto (474472e); "
        "the protoc check is in the script's docstring",
    }


# Home's street: 59.91 N, 10.75 E (made up), in 1e-8 degrees.
H = (5_991_000_000, 1_075_000_000)


def apple_rows():
    rows = []

    def row(name, body, aps=None, note=""):
        entry = {"name": name, "note": note, "hex": body.hex()}
        if aps is None:
            entry["malformed"] = True
        else:
            entry["aps"] = aps
        rows.append(entry)

    # The contract's fresh lookup: 01 and 02 known (Apple's short mac_ids), 03 unknown, 04 and 05 surrounding.
    row(
        "fresh",
        answer(
            ap(mac(1, short=True), location(H[0], H[1], 25)),
            ap(mac(2, short=True), location(H[0] + 15_000, H[1] + 23_000, 40)),
            ap(mac(3), location(UNKNOWN, UNKNOWN, -1)),
            ap(mac(4, short=True), location(H[0] + 30_000, H[1] - 20_000, 30)),
            ap(mac(5, short=True), location(H[0] - 20_000, H[1] + 50_000, 60)),
        ),
        [
            known(1, H[0], H[1], 25),
            known(2, H[0] + 15_000, H[1] + 23_000, 40),
            [mac(3), None],
            known(4, H[0] + 30_000, H[1] - 20_000, 30),
            known(5, H[0] - 20_000, H[1] + 50_000, 60),
        ],
        "the contract's fresh answer",
    )
    # 06 for the lookup that finds the limits full.
    row("other", answer(ap(mac(6), location(H[0] + 40_000, H[1], 35))), [known(6, H[0] + 40_000, H[1], 35)])
    row(
        "unknowns",
        answer(
            ap(mac(1)),
            ap(mac(2), location(None, H[1], 20)),
            ap(mac(3), location(UNKNOWN, 0, 0)),
            ap(mac(4), location(H[0], None, 20)),
        ),
        [[mac(1), None], [mac(2), None], [mac(3), None], [mac(4), None]],
        "no location, no latitude, Apple's -18000000000, no longitude",
    )
    row(
        "out_of_range",
        answer(
            ap(mac(1), location(9_500_000_000, H[1], 20)),
            ap(mac(2), location(H[0], 18_100_000_000, 20)),
            ap(mac(3), location(H[0], H[1], 0)),
            ap(mac(4), location(H[0], H[1], 5001)),
            ap(mac(5), location(H[0], H[1], -1)),
            ap(mac(6), location(-3_350_000_000, -7_060_000_000, 5000)),
        ),
        [[mac(n), None] for n in range(1, 6)] + [known(6, -3_350_000_000, -7_060_000_000, 5000)],
        "latitude past 90, longitude past 180, accuracy 0, 5001 and -1 (a ten-byte varint) unknown; "
        "negative coordinates (ten-byte varints) read",
    )
    row(
        "macs",
        answer(
            ap("0:0:5E:0:53:A", location(H[0], H[1], 20)),
            ap(mac(10), location(H[0] + 10_000, H[1], 20)),
            ap("zz:0:5e:0:53:1", location(H[0], H[1], 20)),
            ap("0:0:5e:0:53", location(H[0], H[1], 20)),
            ap("0:0:5e:0:53:100", location(H[0], H[1], 20)),
            ap(mac(11), location(H[0], H[1] + 10_000, 30)),
        ),
        [known(10, H[0], H[1], 20), known(11, H[0], H[1] + 10_000, 30)],
        "upper case and short groups normalised; the first of duplicates wins; non-hex, five or 3-digit groups skipped",
    )
    skip = vi(9, 7) + key(10, 1) + bytes(8) + key(11, 5) + bytes(4) + ld(12, b"\x01\x02")
    row(
        "unknown_fields",
        answer(
            skip,
            ap(mac(1), location(H[0], H[1], 20, extra=vi(5, 300) + skip), extra=vi(21, 6) + skip),
            ld(22, ap(mac(9))),
        ),
        [known(1, H[0], H[1], 20)],
        "fields of wire types 0, 1, 2 and 5 skipped at every level",
    )
    row("too_long_varint", answer(key(2, 2) + bytes([0x80] * 10) + b"\x01"), None, "an eleven-byte varint")
    row("overflowing_varint", answer(vi(9, 0)[:-1] + bytes([0xFF] * 9) + b"\x02"), None, "a tenth byte past 64 bits")
    row("group", answer(ap(mac(1), location(H[0], H[1], 20)), key(7, 3), key(7, 4)), None, "a group (wire type 3)")
    row("wrong_wire", answer(key(2, 0) + varint(5)), None, "wireless_aps as a varint")
    row("short_header", bytes(9), None, "nine bytes")
    row("truncated", answer(key(2, 2) + varint(40) + ld(1, mac(1).encode())), None, "a length past the end")
    row("header_only", HEADER, [], "no APs: an empty answer")
    row(
        "throttle",
        answer(*[ap(mac(20 + n), location(H[0] + n * 1000, H[1], 30)) for n in range(17)]),
        [known(20 + n, H[0] + n * 1000, H[1], 30) for n in range(17)],
        "17 APs: GrapheneOS's throttle",
    )
    row(
        "many",
        answer(*[ap("00:00:5e:%02x:53:%02x" % (n // 256, n % 256), location(H[0], H[1], 50)) for n in range(205)]),
        [["00:00:5e:%02x:53:%02x" % (n // 256, n % 256), deg(H[0]), deg(H[1]), 50] for n in range(200)],
        "205 APs: the first 200 read",
    )
    return rows


HEALTH = [
    {
        "name": "23 h of failures",
        "outcomes": [{"at_h": h, "failure": "timeout"} for h in (0, 1, 3, 7, 15, 23)],
        "now_h": 23.5,
        "switch": True,
        "line": False,
    },
    {
        "name": "25 h of failures",
        "outcomes": [{"at_h": h, "failure": f} for h, f in ((0, "proxy"), (1, "proxy"), (3, "timeout"), (25, "proxy"))],
        "now_h": 25,
        "switch": True,
        "line": True,
    },
    {
        "name": "the 24-h pause step",
        "outcomes": [{"at_h": h / 10, "failure": "blocked"} for h in range(6)],
        "now_h": 1,
        "switch": True,
        "line": True,
    },
    {
        "name": "one short of the 24-h step",
        "outcomes": [{"at_h": h / 10, "failure": "format"} for h in range(5)],
        "now_h": 1,
        "switch": True,
        "line": False,
    },
    {
        "name": "a success clears it",
        "outcomes": [{"at_h": h, "failure": "proxy"} for h in (0, 1, 3, 25)] + [{"at_h": 26, "ok": True}],
        "now_h": 26,
        "switch": True,
        "line": False,
    },
    {
        "name": "the switch off on every phone",
        "outcomes": [{"at_h": h / 10, "failure": "blocked"} for h in range(6)],
        "now_h": 1,
        "switch": False,
        "line": False,
    },
]


def build():
    return {
        "_doc": "Design 125 step 2, the server's own rows (written by scripts/wifi-lookup-fixtures.py - edit the "
        "script, not this file). golden: the request for three BSSIDs. apple: fake answers of Apple's service with "
        "the APs the server reads ([bssid, lat, lng, acc_m], [bssid, null] = unknown) or malformed. health: failure "
        "histories (hours from the first) to the orange line, through the pause steps and integration_health.",
        "golden": golden(),
        "apple": apple_rows(),
        "health": HEALTH,
    }


def text():
    return json.dumps(build(), indent=1) + "\n"


def selftest():
    data = build()
    # The encoder against a known protobuf: field 1 = 150 is 08 96 01.
    assert vi(1, 150) == bytes([0x08, 0x96, 0x01])
    assert len(varint(UNKNOWN)) == 10 and len(varint(-1)) == 10
    assert data["golden"]["hex"].startswith("0001")
    with open(OUT) as f:
        if f.read() != text():
            raise SystemExit("selftest: server/testdata/wifi_lookup/server.json is not what this script writes - run "
                             "it with --write")
    print("wifi-lookup-fixtures selftest: ok")


def main(argv):
    if argv == ["--selftest"]:
        selftest()
    elif argv == ["--write"]:
        with open(OUT, "w") as f:
            f.write(text())
    elif not argv:
        sys.stdout.write(text())
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
