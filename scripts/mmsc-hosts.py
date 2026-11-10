#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The MMS servers AOSP's APN list names for each SIM operator (design 83): the server's starting set.

    python3 -I scripts/mmsc-hosts.py                  # download, check, write server/testdata/mmsc_hosts.txt
    python3 -I scripts/mmsc-hosts.py --cache DIR      # the same, keeping the download in DIR (read from it next time)
    python3 -I scripts/mmsc-hosts.py --selftest       # convert scripts/mmsc-hosts-sample.xml under every UNASKED
                                                      # value, and check the committed output's lines (no network)

Source: AOSP's device/sample, etc/apns-full-conf.xml at COMMIT (Apache-2.0), whose SHA-256 is pinned below
(INPUT_SHA256): a changed file fails the run before anything is written. A row's `mmsc` is the URL a phone posts an
MMS to; the list says nothing about the host a notification names, and nobody has checked who holds its public names
today - which is why UNASKED decides what a phone may fetch from without the parent's allow:

    "none"      every entry is a hint: shown to the parent who is asked about an address, never sent to a phone
    "internal"  single-label names and private or CGNAT addresses (10/8, 172.16/12, 192.168/16, 100.64/10) are
                unasked - they mean something only inside an operator's MMS network - and the rest are hints
    "listed"    every listed entry is unasked

It ships as "none" until design 77's step 0 has shown on a real SIM that the platform fetches on the MMS network and
nowhere else (design 83 §3, the gate). Changing it is that one line, the regenerated file and BODY_SHA256.

What is read: every <apn> with an `mmsc` and an MCC/MNC, whatever its spn/gid/imsi rule (the union per MCC/MNC). An
entry is the lower-cased host, and `host:port` too when the row names a port other than 80, 443 or 8080. A host is a
single-label name, a dotted name whose last label is in LAST_LABELS, or four decimal octets; a host in SKIP is left
out with its reason. Any other last label, scheme or host fails the run, so a made-up top-level name is seen when the
set is generated. A host later found in an outsider's hands goes into SKIP (it still works through the parent's allow).

Writes server/testdata/mmsc_hosts.txt: a header, then one line per MCC/MNC, `mccmnc unasked... | hints...`. The
lines' SHA-256 is BODY_SHA256: a run that changes them writes the file and fails, naming the new value.

Python 3, nothing else.
"""
import base64
import collections
import hashlib
import os
import re
import shutil
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

COMMIT = "73f904fbeb872f5675cb5f18cd9dfb15856425a6"
SOURCE = f"https://android.googlesource.com/device/sample/+/{COMMIT}/etc/apns-full-conf.xml"
INPUT_SHA256 = "9e44015793ec01d0773123103dca19b181fbc5fdd43cfc1b1d5cfacbda622726"

# Which entries a phone gets without the parent's allow (the module doc): "none", "internal" or "listed".
UNASKED = "none"
# SHA-256 of the output's lines (everything under the header) as generated with UNASKED.
BODY_SHA256 = "87f81870a64ce20b75c80a56b26a26fbca9bcf9c5b0fea027277344307751e8a"

# The last labels a public name may end in: the country codes and old generic top-level names the list's names end
# in at COMMIT. Another one fails the run.
LAST_LABELS = frozenset("""
    ae ar at au aw ba be bg bh bo br by ca cl cm cn co com cu cy cz de dk do ec ee eg es fi fr gh gl gp gr hk hr hu
    id ie il in is it jm kr kz lb ls lt lu lv ma mc md me mk mo mobi mq mt mv mx my net no nz om pa ph pl ps pt py qa
    re ro rs ru sa se si sk tel th tn tr tw ua uk us uy ve vn za
""".split())

MADE_UP = "its last label was not a public top-level name when the name was made up, and is or can be one now"
MALFORMED = "not an address a resolver reads one way"
# Hosts left out, each with its reason.
SKIP = {
    "mms.media": MADE_UP,
    "mms.mobileuc.global": MADE_UP,
    "mms.zain": MADE_UP,
    "mmsc.axis": MADE_UP,
    "mms.primetel": MADE_UP,
    "10.152.10.70.38090": MALFORMED,
    "172.026.005.132": MALFORMED,
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "server", "testdata", "mmsc_hosts.txt")
SAMPLE = os.path.join(ROOT, "scripts", "mmsc-hosts-sample.xml")

MMSC_RE = re.compile(r"(?:http|https)://([^/?#:@\s]+)(?::([0-9]+))?(?:[/?#]\S*)?")
# What a run counts of the rows, in the order it prints them (design 83 §1's table).
ROW_COUNTS = (
    "rows", "rows with an mmsc", "rows with no mcc/mnc, skipped", "usable rows", "- naming a name with a dot",
    "- naming a single-label name", "- naming an address", "rows naming another port",
)
# Ports a bare `host` entry covers on the phone (`MmsGate.location`); any other needs its `host:port` entry.
PLAIN_PORTS = (80, 443, 8080)


class ConvertError(Exception):
    pass


def octets(host):
    """Four decimal octets (0-255, no leading zeros) as numbers, else None."""
    parts = host.split(".")
    if len(parts) == 4 and all(re.fullmatch(r"0|[1-9][0-9]{0,2}", p) and int(p) <= 255 for p in parts):
        return [int(p) for p in parts]
    return None


def host_class(host, last_labels):
    """"internal" (a single-label name, a private or CGNAT address) or "public"; anything else fails."""
    if len(host) > 253 or not re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)*", host):
        raise ConvertError(f"{host!r}: not a host name")
    address = octets(host)
    if address:
        a, b = address[0], address[1]
        private = a == 10 or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168) or (a == 100 and 64 <= b <= 127)
        return "internal" if private else "public"
    last = host.rsplit(".", 1)[-1]
    if not last[0].isalpha():
        raise ConvertError(f"{host!r}: neither a name nor four decimal octets (SKIP it with its reason)")
    if "." not in host:
        return "internal"
    if last not in last_labels:
        raise ConvertError(f"{host!r}: its last label {last!r} is not in LAST_LABELS (add it, or SKIP the host)")
    return "public"


def convert(data, unasked, last_labels, skip):
    """The APN list -> (the output's lines, what was counted)."""
    if unasked not in ("none", "internal", "listed"):
        raise ConvertError(f"UNASKED is {unasked!r}")
    entries = collections.defaultdict(dict)  # mccmnc -> {entry: its host's class}
    classes = {}  # host -> class, "skipped" included
    seen = collections.defaultdict(set)  # mccmnc -> the hosts its rows name, skipped ones too
    other_port = set()
    counts = collections.Counter({what: 0 for what in ROW_COUNTS})
    for apn in ET.fromstring(data).iter("apn"):
        counts["rows"] += 1
        mmsc = apn.get("mmsc")
        if not mmsc:
            continue
        counts["rows with an mmsc"] += 1
        mcc, mnc = apn.get("mcc", ""), apn.get("mnc", "")
        if not mcc and not mnc:
            counts["rows with no mcc/mnc, skipped"] += 1
            continue
        mccmnc = mcc + mnc
        if not re.fullmatch(r"[0-9]{3}", mcc) or not re.fullmatch(r"[0-9]{2,3}", mnc):
            raise ConvertError(f"mcc {mcc!r} mnc {mnc!r}: not an MCC/MNC")
        found = MMSC_RE.fullmatch(mmsc)
        if not found:
            raise ConvertError(f"{mccmnc}: an mmsc this script doesn't read ({mmsc!r})")
        host, port = found.group(1).lower(), found.group(2)
        counts["usable rows"] += 1
        kind = "an address" if octets(host) else "a name with a dot" if "." in host else "a single-label name"
        counts[f"- naming {kind}"] += 1
        seen[mccmnc].add(host)
        names = [host]
        if port is not None:
            if not 1 <= int(port) <= 65535:
                raise ConvertError(f"{mccmnc}: port {port} in {mmsc!r}")
            if int(port) not in PLAIN_PORTS:
                names.append(f"{host}:{int(port)}")
                counts["rows naming another port"] += 1
                other_port.add(mccmnc)
        if host not in classes:
            classes[host] = "skipped" if host in skip else host_class(host, last_labels)
        if classes[host] != "skipped":
            for name in names:
                entries[mccmnc][name] = classes[host]
    lines = []
    for mccmnc in sorted(entries):
        free = [e for e, c in sorted(entries[mccmnc].items()) if unasked == "listed" or (unasked, c) == ("internal", "internal")]
        hints = [e for e in sorted(entries[mccmnc]) if e not in free]
        lines.append(f"{mccmnc}{''.join(' ' + e for e in free)} |{''.join(' ' + e for e in hints)}")

    def hosts(wanted, shape=None):
        return sum(1 for h, c in classes.items() if c == wanted and (shape is None or shape(h)))

    def operators(wanted):
        return sum(1 for hosts_ in seen.values() if wanted({classes[h] for h in hosts_}))

    counts.update({
        "MCC/MNCs with usable rows": len(seen),
        "MCC/MNCs naming another port": len(other_port),
        "distinct hosts": len(classes),
        "internal hosts": hosts("internal"),
        "- single-label names": hosts("internal", lambda h: not octets(h)),
        "- private or CGNAT addresses": hosts("internal", octets),
        "public hosts": hosts("public"),
        "- dotted names": hosts("public", lambda h: not octets(h)),
        "- public addresses": hosts("public", octets),
        "hosts skipped by name": hosts("skipped"),
        "MCC/MNCs with an internal host": operators(lambda c: "internal" in c),
        "- of them with a public one too": operators(lambda c: {"internal", "public"} <= c),
        "MCC/MNCs with only public hosts": operators(lambda c: "public" in c and "internal" not in c),
        "MCC/MNCs left with nothing": operators(lambda c: c == {"skipped"}),
        "MCC/MNCs written": len(lines),
    })
    return lines, counts


def body_of(lines):
    return "".join(line + "\n" for line in lines)


def header(body):
    return f"""# The MMS servers AOSP's APN list names for each SIM operator (design 83), made by scripts/mmsc-hosts.py from
# {SOURCE}
# (SHA-256 {INPUT_SHA256}). Never edited by hand: the script's
# --selftest compares the lines below with the SHA-256 it holds for them
# ({hashlib.sha256(body.encode()).hexdigest()}).
#
# The source is Copyright 2006, The Android Open Source Project, licensed under the Apache License, Version 2.0
# (http://www.apache.org/licenses/LICENSE-2.0; NOTICE.md). Changed here: reduced to the hosts of each MCC/MNC's `mmsc`
# URLs, in two classes; the hosts in the script's SKIP are left out.
#
# UNASKED = "{UNASKED}"
# One line per MCC/MNC: `mccmnc unasked... | hints...`, an entry `host` or `host:port`. An unasked entry is sent to
# a phone with that SIM (`call_policy.mmsc_hosts`); a hint is only shown to the parent who is asked about an address.
"""


def fetch(cache):
    local_path = os.path.join(cache, "apns-full-conf.xml")
    if not os.path.exists(local_path):
        with urllib.request.urlopen(f"{SOURCE}?format=TEXT", timeout=120) as response:
            data = base64.b64decode(response.read(), validate=True)
        with open(local_path + ".part", "wb") as out:
            out.write(data)
        os.replace(local_path + ".part", local_path)
    with open(local_path, "rb") as f:
        return f.read()


def run(cache):
    data = fetch(cache)
    got = hashlib.sha256(data).hexdigest()
    if got != INPUT_SHA256:
        raise ConvertError(f"the APN list's SHA-256 is {got}, pinned {INPUT_SHA256}: nothing written")
    lines, counts = convert(data, UNASKED, LAST_LABELS, SKIP)
    body = body_of(lines)
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        out.write(header(body) + body)
    for what, n in counts.items():
        print(f"{n:6}  {what}")
    digest = hashlib.sha256(body.encode()).hexdigest()
    if digest != BODY_SHA256:
        raise ConvertError(f"the lines changed: set BODY_SHA256 to {digest} (the file is written, UNASKED = {UNASKED!r})")


# ---- the selftest ----------------------------------------------------------------------------------------------

# The sample's own last label and skips; its rows say why each is there.
SAMPLE_LAST_LABELS = frozenset(["invalid"])
SAMPLE_SKIP = {"mms.madeup": MADE_UP, "10.1.2.3.4": MALFORMED, "172.016.000.001": MALFORMED}
SAMPLE_EXPECT = {
    "none": [
        "999003 | 10.0.0.9 10.0.0.9:38090 100.64.1.1 192.0.2.7 mmsc.operator.invalid",
        "99901 | mm mmsc",
        "99902 | mms.operator.invalid mmsc.operator.invalid mmsc.operator.invalid:8002",
        "99905 | mmsc.brand.invalid",
    ],
    "internal": [
        "999003 10.0.0.9 10.0.0.9:38090 100.64.1.1 | 192.0.2.7 mmsc.operator.invalid",
        "99901 mm mmsc |",
        "99902 | mms.operator.invalid mmsc.operator.invalid mmsc.operator.invalid:8002",
        "99905 | mmsc.brand.invalid",
    ],
    "listed": [
        "999003 10.0.0.9 10.0.0.9:38090 100.64.1.1 192.0.2.7 mmsc.operator.invalid |",
        "99901 mm mmsc |",
        "99902 mms.operator.invalid mmsc.operator.invalid mmsc.operator.invalid:8002 |",
        "99905 mmsc.brand.invalid |",
    ],
}
SAMPLE_COUNTS = {
    "rows": 15,
    "rows with an mmsc": 14,
    "rows with no mcc/mnc, skipped": 1,
    "usable rows": 13,
    "rows naming another port": 2,
    "distinct hosts": 11,
    "internal hosts": 4,
    "public hosts": 4,
    "hosts skipped by name": 3,
    "MCC/MNCs left with nothing": 1,
    "MCC/MNCs written": 4,
}
# One row each that must fail the run: a scheme, userinfo, a last label nobody accepted, hosts that are neither a
# name nor four octets, a port that is none, half an MCC/MNC.
SAMPLE_FAILS = [
    'mcc="999" mnc="01" mmsc="ftp://mmsc.operator.invalid"',
    'mcc="999" mnc="01" mmsc="mmsc.operator.invalid"',
    'mcc="999" mnc="01" mmsc="http://user@mmsc.operator.invalid"',
    'mcc="999" mnc="01" mmsc="http://mms.newlabel"',
    'mcc="999" mnc="01" mmsc="http://010.0.0.1"',
    'mcc="999" mnc="01" mmsc="http://2130706433"',
    'mcc="999" mnc="01" mmsc="http://mmsc.operator.invalid."',
    'mcc="999" mnc="01" mmsc="http://mmsc.operator.invalid:0"',
    'mcc="999" mmsc="http://mmsc.operator.invalid"',
]


def selftest():
    with open(SAMPLE, "rb") as f:
        sample = f.read()
    for unasked, expected in SAMPLE_EXPECT.items():
        lines, counts = convert(sample, unasked, SAMPLE_LAST_LABELS, SAMPLE_SKIP)
        if lines != expected:
            sys.stderr.write("\n".join(lines) + "\n")
            raise SystemExit(f"selftest: UNASKED {unasked!r} gave other lines than expected (above)")
        got = {what: counts[what] for what in SAMPLE_COUNTS}
        if got != SAMPLE_COUNTS:
            raise SystemExit(f"selftest: counted {got}")
    for row in SAMPLE_FAILS:
        try:
            convert(f"<apns><apn {row}/></apns>".encode(), "none", SAMPLE_LAST_LABELS, SAMPLE_SKIP)
        except ConvertError:
            continue
        raise SystemExit(f"selftest: {row} was converted instead of failing")
    # The committed output is what a run wrote: its lines hash to the pinned value, under the header a run writes.
    with open(OUT, encoding="utf-8") as f:
        text = f.read()
    body = "".join(line for line in text.splitlines(keepends=True) if not line.startswith("#"))
    if hashlib.sha256(body.encode()).hexdigest() != BODY_SHA256 or text != header(body) + body:
        raise SystemExit("selftest: server/testdata/mmsc_hosts.txt is not what this script wrote - run the script")
    print("mmsc-hosts selftest: ok")


def main(argv):
    if argv == ["--selftest"]:
        selftest()
        return 0
    if len(argv) == 2 and argv[0] == "--cache":
        os.makedirs(argv[1], exist_ok=True)
        run(argv[1])
        return 0
    if argv:
        sys.stderr.write(__doc__)
        return 2
    cache = tempfile.mkdtemp(prefix="mmsc-hosts-")
    try:
        run(cache)
    finally:
        shutil.rmtree(cache, ignore_errors=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except ConvertError as err:
        sys.exit(f"mmsc-hosts: {err}")
