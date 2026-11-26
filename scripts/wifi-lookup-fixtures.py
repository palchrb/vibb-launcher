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
- `golden_cells` (design 125b C6, design 137 N2): the cell call for one GSM, one LTE and one NR cell on the test
  network 001-01 (ITU-T E.212's, never a real cell): GrapheneOS's towers (`GsmCellTower` 1, `LteCellTower` 25,
  `Nr5GCellTower` 29, `{mcc 1, mnc 2, cell_id 3, lac_id/tac_id 4}`), each standard's count of surrounding towers (3, 26,
  30: max(1, 12 x its share)) and `meta`, field-number order. The protoc check, as for `golden` (by hand before the
  first release with design 137):
      printf 'gsm_cell_towers { mcc: 1 mnc: 1 cell_id: 5001 lac_id: 1001 } number_of_surrounding_gsm_cells: 4
      lte_cell_towers { mcc: 1 mnc: 1 cell_id: 12345678 tac_id: 4711 } number_of_surrounding_lte_cells: 4
      nr5g_cell_towers { mcc: 1 mnc: 1 cell_id: 4294967297 tac_id: 1048577 } number_of_surrounding_nr5g_cells: 4
      meta { software_build: "macOS15.4/24E248" product_id: "arm64" }' |
      protoc --encode=app.grapheneos.networklocation.proto.ALSLocationRequest apple_wps.proto | xxd -p
  (the field names as GrapheneOS's .proto spells them).
- `apple_cells`: fake answers of a cell call, each with the towers the server must read ([canonical cell, lat, lng,
  acc_m] or [canonical cell, null] = unknown, then {"ch", "pci"} when either is read - design 137 N1) or malformed.
  Test network 001-01 only, made-up coordinates, channels and PCIs. Design 137's rows have the shape of the probe of
  2026-11-25 (an asked LTE cell with location fields 4, 11 and 12 and no channel; surrounding LTE towers with EARFCN 6
  and PCI 7; id-less areas with cid -1 at 149 km) - never its cells, positions, channels or PCIs.

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


# Design 125b: cells on the test network 001-01. Radio -> (the request's towers field, its count field, the answer's).
RADIOS = {"gsm": (1, 3, 1), "lte": (25, 26, 22), "wcdma": (27, 28, 23), "nr": (29, 30, 24)}
LTE = ("lte", 1, 1, 4711, 12345678)
NR = ("nr", 1, 1, 1048577, 4294967297)
GSM = ("gsm", 1, 1, 1001, 5001)
WCDMA = ("wcdma", 1, 1, 2001, 70001)


def canonical(cell):
    return "%s:%d:%d:%d:%d" % cell


def tower(cell, loc=None, skip=(), extra=b""):
    _, mcc, mnc, area, cid = cell
    body = b""
    for field, n in ((1, mcc), (2, mnc), (3, cid), (4, area)):
        if field not in skip:
            body += vi(field, n)
    if loc is not None:
        body += ld(5, loc)
    return body + extra


def answer_tower(cell, loc=None, skip=(), field=None, extra=b""):
    return ld(field or RADIOS[cell[0]][2], tower(cell, loc, skip, extra))


def known_cell(cell, lat, lng, acc, ch=None, pci=None):
    row = [canonical(cell), deg(lat), deg(lng), acc]
    return row + near_fields(ch, pci)


def near_fields(ch, pci):
    """Design 137 N1: what the server reads of a tower's channel and PCI, when either."""
    fields = {}
    if ch is not None:
        fields["ch"] = ch
    if pci is not None:
        fields["pci"] = pci
    return [fields] if fields else []


# Design 137: the channel and PCI fields of each radio read (N1).
NEAR_FIELDS = {"lte": (6, 7), "wcdma": (11, 12)}


def radio_fields(radio, ch=None, pci=None):
    channel_field, pci_field = NEAR_FIELDS.get(radio, (11, 12))
    out = b""
    if ch is not None:
        out += vi(channel_field, ch)
    if pci is not None:
        out += vi(pci_field, pci)
    return out


def lte(cid, tac=4711):
    return ("lte", 1, 1, tac, cid)


def golden_cells():
    cells = [GSM, LTE, NR]
    message = b""
    for radio in ("gsm", "lte", "wcdma", "nr"):
        towers_field, count_field, _ = RADIOS[radio]
        mine = [c for c in cells if c[0] == radio]
        for c in mine:
            message += ld(towers_field, tower(c))
        if mine:
            message += vi(count_field, max(1, 12 * len(mine) // len(cells)))
    message += ld(33, ld(1, b"macOS15.4/24E248") + ld(2, b"arm64"))
    head = (1).to_bytes(2, "big")
    for text in (b"en-US_US", b"com.apple.locationd", b"15.4.24E248"):
        head += len(text).to_bytes(2, "big") + text
    head += (1).to_bytes(4, "big") + len(message).to_bytes(4, "big")
    return {
        "cells": [canonical(c) for c in cells],
        "throttled": False,
        "hex": (head + message).hex(),
        "origin": "scripts/wifi-lookup-fixtures.py's own encoder from GrapheneOS's apple_wps.proto (474472e); "
        "the protoc check is in the script's docstring",
    }


def apple_cell_rows():
    rows = []

    def row(name, body, towers=None, note=""):
        entry = {"name": name, "note": note, "hex": body.hex()}
        if towers is None:
            entry["malformed"] = True
        else:
            entry["towers"] = towers
        rows.append(entry)

    near = ("lte", 1, 1, 4711, 12345679)
    far = ("lte", 1, 1, 4712, 22345678)
    row(
        "cells_fresh",
        answer(
            answer_tower(LTE, location(H[0], H[1], 800)),
            answer_tower(NR, location(H[0] + 200_000, H[1] + 100_000, 350)),
            answer_tower(GSM, location(UNKNOWN, UNKNOWN, -1)),
            answer_tower(near, location(H[0] + 900_000, H[1], 1200)),
            answer_tower(far, location(H[0] - 1_500_000, H[1] + 2_000_000, 2500)),
        ),
        [
            known_cell(LTE, H[0], H[1], 800),
            known_cell(NR, H[0] + 200_000, H[1] + 100_000, 350),
            [canonical(GSM), None],
            known_cell(near, H[0] + 900_000, H[1], 1200),
            known_cell(far, H[0] - 1_500_000, H[1] + 2_000_000, 2500),
        ],
        "the contract's cells: LTE and NR known (Apple's MNC 1 for the asked 01; the NR cid over 32 bits), GSM "
        "unknown; two surrounding LTE towers, kept in the asked cells' lists (design 137)",
    )
    # Design 137: the probe's shape, made up. The asked LTE cell at H (1500 m) with location fields 4, 11 and 12 and
    # no channel; 14 surrounding LTE towers, 12 overlapping it (9 with EARFCN and PCI, 2 with neither, 1 with the
    # EARFCN alone) and 2 that don't; 20 id-less areas (cid -1, 149 km) on top, dropped. Offsets in 0.001 degrees.
    neighbours = [
        # (cell, dlat, dlng, acc, ch, pci)
        (lte(12345679), 3, 10, 1400, 6200, 13),  # a sibling sector, ~0.65 km
        (lte(12345602), 12, -5, 1400, 6200, 101),
        (lte(12345603), -15, 20, 1400, 6300, 202),
        (lte(12345604), 20, 25, 1400, 6300, 250),
        (lte(12345605), -6, -30, 1400, 3100, 333),
        (lte(12345606), 8, 40, 1400, 6200, 404),
        (lte(12345607), -22, -10, 1400, 3100, 471),
        (lte(12345608), 2, -45, 1400, 6300, 14),
        (lte(12345609), -9, 5, 1400, 6200, 12),
        (lte(12345610), 15, 30, 1400, None, None),
        (lte(12345611), -25, 12, 6500, None, None),
        (lte(12345612), 5, 15, 1400, 3100, None),
        (lte(12345690), 36, 0, 1000, 6300, 55),  # ~4.0 km, past 1500 + 1000: cut
        (lte(12345691), -80, 40, 6500, None, None),  # ~9.2 km, past 1500 + 6500: cut
    ]
    order = [3, 0, 12, 6, 9, 1, 11, 4, 13, 8, 2, 10, 5, 7]

    def at(dlat, dlng):
        return H[0] + dlat * 100_000, H[1] + dlng * 100_000

    asked_extra = vi(4, 0) + vi(11, 700) + vi(12, 4000)
    body = [answer_tower(LTE, location(H[0], H[1], 1500, extra=asked_extra))]
    towers = [known_cell(LTE, H[0], H[1], 1500)]
    for i in order:
        cell, dlat, dlng, acc, ch, pci = neighbours[i]
        lat, lng = at(dlat, dlng)
        body.append(answer_tower(cell, location(lat, lng, acc), extra=radio_fields("lte", ch, pci)))
        towers.append(known_cell(cell, lat, lng, acc, ch, pci))
    for k in range(20):
        lat, lng = at(k - 10, 2 * k - 20)
        body.append(answer_tower(("lte", 1, 1, 4800 + k, -1), location(lat, lng, 149_000)))
    row(
        "cells_near",
        answer(*body),
        towers,
        "the probe's shape: the asked LTE cell (location fields 4, 11, 12; no EARFCN or PCI), 14 surrounding towers - "
        "12 overlap it (9 with EARFCN and PCI, 2 with neither, 1 with the EARFCN alone), 2 don't - and 20 id-less "
        "areas (cid -1, 149 km) dropped",
    )
    edge = [
        (lte(301, 4720), vi(6, 6200) + vi(7, -1), 6200, None, "PCI -1 (a ten-byte varint): unknown"),
        (lte(302, 4720), vi(6, 6300) + vi(7, 504), 6300, None, "PCI 504: unknown"),
        (lte(303, 4720), ld(6, b"\x01\x02") + vi(7, 7), None, 7, "EARFCN length-delimited: unknown"),
        (lte(304, 4720), vi(6, 262144) + vi(7, 503), None, 503, "EARFCN 262144: unknown; PCI 503 kept"),
        (lte(305, 4720), vi(6, 262143) + vi(7, 0), 262143, 0, "EARFCN 262143 and PCI 0 kept"),
        (lte(306, 4720), key(6, 5) + bytes(4) + vi(7, 9), None, 9, "EARFCN as fixed32: unknown"),
        (lte(307, 4720), vi(6, 6200) + vi(6, 6300) + vi(7, 1) + vi(7, 2), 6300, 2, "sent twice: the last counts"),
        (lte(308, 4720), vi(6, 1 << 31) + vi(11, 5) + vi(12, 5), None, None, "EARFCN past int32; WCDMA's fields"),
        (("nr", 1, 1, 1048577, 4294967300), vi(6, 500000) + vi(7, 3), None, None, "NR's field 6 not read"),
        (("wcdma", 1, 1, 2001, 70002), vi(11, 10700) + vi(12, 300), 10700, 300, "WCDMA's UARFCN 11 and PSC 12"),
        (("wcdma", 1, 1, 2001, 70003), vi(11, 16384) + vi(12, 512), None, None, "UARFCN 16384, PSC 512: unknown"),
        (("gsm", 1, 1, 1001, 5002), vi(11, 70) + vi(12, 40), None, None, "GSM's 11 and 12 not read"),
        (lte(310, 4720), key(6, 3) + vi(1, 6200) + key(6, 4) + vi(7, 8), None, 8, "EARFCN as a group: unknown"),
        (
            lte(311, 4720),
            key(9, 3) + key(10, 3) + vi(1, 1) + ld(2, b"x") + key(10, 4) + key(9, 4) + vi(6, 6200) + vi(7, 4),
            6200,
            4,
            "a nested group in an unknown field skipped",
        ),
    ]
    body, towers = [], []
    for n, (cell, extra, ch, pci, _) in enumerate(edge):
        lat, lng = at(n, 0)
        body.append(answer_tower(cell, location(lat, lng, 1000), extra=extra))
        towers.append(known_cell(cell, lat, lng, 1000, ch, pci))
    row(
        "cells_near_fields",
        answer(*body),
        towers,
        "design 137 N1: " + "; ".join(e[4] for e in edge) + " - every tower kept with its position",
    )
    body = [answer_tower(LTE, location(H[0], H[1], 1500))]
    towers = [known_cell(LTE, H[0], H[1], 1500)]
    for n in range(12):
        cell = lte(12345701 + n)
        lat, lng = at(n - 6, 6 - n)
        body.append(answer_tower(cell, location(lat, lng, 1400), extra=radio_fields("lte", 6200, 20 + n)))
        towers.append(known_cell(cell, lat, lng, 1400, 6200, 20 + n))
    for k in range(5):
        body.append(answer_tower(("lte", 1, 1, 4900 + k, -1), location(*at(k, k), 149_000)))
    row(
        "cells_thirteen",
        answer(*body),
        towers,
        "13 towers to one cell asked with a count of 12 (and 5 id-less areas, dropped): no throttle",
    )
    body = [answer_tower(LTE, location(H[0], H[1], 900))]
    towers = [known_cell(LTE, H[0], H[1], 900)]
    for n in range(119):
        cell = lte(12346001 + n)
        body.append(answer_tower(cell, location(H[0], H[1], 900)))
        towers.append(known_cell(cell, H[0], H[1], 900))
    row("cells_flood", answer(*body), towers, "120 towers: more than asked, throttled")
    # Two asked cells, A (the LTE) and B 2.2 km north, both 1200 m; X near A only, Y between, Z near B only.
    b, x, y, z = lte(12345650), lte(12345651), lte(12345652), lte(12345653)
    two = [
        (LTE, 0, 1200, None, None),
        (b, 200, 1200, None, None),
        (x, -100, 800, None, None),
        (y, 95, 800, 6200, 21),
        (z, 300, 800, 6300, 22),
    ]
    body, towers = [], []
    for cell, dlat, acc, ch, pci in two:
        # dlat in 0.0001 degrees.
        lat, lng = H[0] + dlat * 10_000, H[1]
        body.append(answer_tower(cell, location(lat, lng, acc), extra=radio_fields("lte", ch, pci)))
        towers.append(known_cell(cell, lat, lng, acc, ch, pci))
    row(
        "cells_two",
        answer(*body),
        towers,
        "two asked cells 2.2 km apart and three neighbours: X overlaps only A, Y both, Z only B",
    )
    row(
        "cells_kinds",
        answer(
            answer_tower(GSM, location(H[0], H[1], 3000)),
            answer_tower(WCDMA, location(H[0] + 10_000, H[1], 1500)),
            answer_tower(LTE, location(H[0] + 20_000, H[1], 50_000)),
            answer_tower(NR, location(H[0] + 30_000, H[1], 1)),
        ),
        [
            known_cell(GSM, H[0], H[1], 3000),
            known_cell(WCDMA, H[0] + 10_000, H[1], 1500),
            known_cell(LTE, H[0] + 20_000, H[1], 50_000),
            known_cell(NR, H[0] + 30_000, H[1], 1),
        ],
        "each tower type (SCDMA's field 23 read as WCDMA); accuracy 1 and 50,000 kept",
    )
    row(
        "cells_unknowns",
        answer(
            answer_tower(LTE),
            answer_tower(NR, location(UNKNOWN, 0, 0)),
            answer_tower(GSM, location(H[0], H[1], 0)),
            answer_tower(WCDMA, location(H[0], H[1], 60_000)),
        ),
        [[canonical(LTE), None], [canonical(NR), None], [canonical(GSM), None], [canonical(WCDMA), None]],
        "no location, Apple's -18000000000, accuracy 0 and 60 km: unknown",
    )
    row(
        "cells_dropped",
        answer(
            answer_tower(LTE, location(H[0], H[1], 500), skip=(4,)),
            answer_tower(NR, location(H[0], H[1], 500), skip=(1,)),
            answer_tower(GSM, location(H[0], H[1], 500), skip=(2,)),
            answer_tower(WCDMA, location(H[0], H[1], 500), skip=(3,)),
            answer_tower(("lte", 1, 1, 4711, -5), location(H[0], H[1], 500)),
            answer_tower(("gsm", 1, 1, 1001, 65535), location(H[0], H[1], 500)),
            answer_tower(("lte", 1, 1, 0, 7), location(H[0], H[1], 500)),
            answer_tower(("nr", 1, 1, 7, 68719476735), location(H[0], H[1], 500)),
            answer_tower(("lte", 1000, 1, 7, 7), location(H[0], H[1], 500)),
        ),
        [],
        "a missing TAC, MCC, MNC or cid; a negative cid (a ten-byte varint); GSM's all-ones cid, area 0, NR's "
        "all-ones NCI, an MCC of 1000: left out",
    )
    row(
        "cells_duplicates",
        answer(
            answer_tower(LTE, location(H[0], H[1], 500)),
            answer_tower(LTE, location(H[0] + 50_000, H[1], 700)),
            answer_tower(("lte", 1, 1, 4711, 12345678), location(H[0] + 90_000, H[1], 900)),
        ),
        [known_cell(LTE, H[0], H[1], 500)],
        "the first of duplicates wins",
    )
    row(
        "cells_skipped",
        answer(
            ld(21, tower(("lte", 1, 1, 7, 7), location(H[0], H[1], 500))),
            ap(mac(1), location(H[0], H[1], 25)),
            answer_tower(LTE, location(H[0], H[1], 500)),
        ),
        [known_cell(LTE, H[0], H[1], 500)],
        "a CDMA tower (field 21) and an AP (field 2) skipped, never cached",
    )
    row(
        "cells_many",
        answer(*[answer_tower(("lte", 1, 1, 1, n + 1), location(H[0], H[1], 900)) for n in range(201)]),
        [[canonical(("lte", 1, 1, 1, n + 1)), deg(H[0]), deg(H[1]), 900] for n in range(200)],
        "201 towers: the first 200 read",
    )
    row("cells_wrong_wire", answer(key(22, 0) + varint(5)), None, "a tower as a varint")
    # Code QA L1: a group inside a tower is skipped to its end tag; one that doesn't close, or closes another, is not.
    row(
        "cells_group_unclosed",
        answer(answer_tower(LTE, location(H[0], H[1], 500), extra=key(9, 3) + vi(1, 1))),
        None,
        "a group in a tower without its end tag",
    )
    row(
        "cells_group_mismatched",
        answer(answer_tower(LTE, location(H[0], H[1], 500), extra=key(9, 3) + key(8, 4))),
        None,
        "a group in a tower closed by another field's end tag",
    )
    row(
        "cells_group_in_mcc",
        answer(ld(22, key(1, 3) + key(1, 4) + vi(2, 1) + vi(3, 7) + vi(4, 7) + ld(5, location(H[0], H[1], 500)))),
        None,
        "the MCC as a group: fields 1-5 keep the strict rule",
    )
    row(
        "cells_group_too_deep",
        answer(answer_tower(LTE, location(H[0], H[1], 500), extra=b"".join(key(9, 3) for _ in range(9)) + b"".join(key(9, 4) for _ in range(9)))),
        None,
        "groups nested nine deep in a tower",
    )
    row("cells_short_header", bytes(9), None, "nine bytes")
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
        "histories (hours from the first) to the orange line, through the pause steps and integration_health. "
        "golden_cells: the cell call for three cells (design 125b). apple_cells: fake answers of a cell call with "
        "the towers the server reads ([canonical cell, lat, lng, acc_m], [cell, null] = unknown; then {\"ch\", \"pci\"} "
        "when either is read, design 137) or malformed.",
        "golden": golden(),
        "apple": apple_rows(),
        "health": HEALTH,
        "golden_cells": golden_cells(),
        "apple_cells": apple_cell_rows(),
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
