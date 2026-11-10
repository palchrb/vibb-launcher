#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The launcher's emoji list for SMS reactions (design 81 D2, D13): Unicode's emoji-test.txt 16.0, stripped.

    python3 -I scripts/emoji-list.py                  # download, check, write the asset below
    python3 -I scripts/emoji-list.py --source FILE    # the same from a copy already downloaded (checked the same way)

Source: https://www.unicode.org/Public/emoji/16.0/emoji-test.txt, whose SHA-256 is pinned below (SOURCE_SHA256): a
changed file fails the run before anything is written. Copyright (c) 2024 Unicode, Inc., under the Unicode License v3
(https://www.unicode.org/license.txt) - the asset's header says so.

Writes launcher/app/src/main/assets/sms/emoji-16.0.txt: Unicode's group lines (`# group: <name>`, the Component group
left out) and one line per sequence in Unicode's order, `<code points>;<status>` with the status `f`
(fully-qualified), `m` (minimally-qualified) or `u` (unqualified). Components and names are left out: the launcher
accepts an emoji only as a listed sequence (D2) and its picker shows the fully-qualified ones (D13). The launcher's
SmsReactionsTest pins the written file's SHA-256.

Python 3, nothing else.
"""
import hashlib
import os
import sys
import urllib.request

URL = "https://www.unicode.org/Public/emoji/16.0/emoji-test.txt"
SOURCE_SHA256 = "24f0c534e86cf142e2496953e8f0e46a3e702392911eddcd29c6cced85139697"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "launcher", "app", "src", "main", "assets", "sms", "emoji-16.0.txt")

STATUS = {"fully-qualified": "f", "minimally-qualified": "m", "unqualified": "u"}
HEADER = f"""# The emoji list of the launcher's SMS reactions (design 81 D2, D13), made by scripts/emoji-list.py from
# {URL} (SHA-256 {SOURCE_SHA256}).
# Copyright (c) 2024 Unicode, Inc. Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S.
# and other countries. Unicode License v3: https://www.unicode.org/license.txt
# One sequence per line in Unicode's order: code points;status (f fully-qualified, m minimally-qualified,
# u unqualified). Unicode's group lines are kept, the Component group and every name left out.
"""


def strip(text):
    lines = [HEADER.rstrip("\n")]
    group = None
    for raw in text.splitlines():
        if raw.startswith("# group: "):
            group = raw[len("# group: "):].strip()
            if group != "Component":
                lines.append(f"# group: {group}")
            continue
        data = raw.split("#", 1)[0].strip()
        if not data:
            continue
        points, status = (part.strip() for part in data.split(";"))
        if status == "component" or group == "Component":
            continue
        if status not in STATUS:
            raise SystemExit(f"unknown status {status!r} in {raw!r}")
        for point in points.split():
            if not (1 <= len(point) <= 6 and all(c in "0123456789ABCDEF" for c in point)):
                raise SystemExit(f"bad code point {point!r} in {raw!r}")
        lines.append(f"{points};{STATUS[status]}")
    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--source":
        with open(sys.argv[2], "rb") as f:
            data = f.read()
    elif len(sys.argv) == 1:
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read()
    else:
        raise SystemExit(__doc__)
    digest = hashlib.sha256(data).hexdigest()
    if digest != SOURCE_SHA256:
        raise SystemExit(f"emoji-test.txt has SHA-256 {digest}, expected {SOURCE_SHA256}: nothing written")
    out = strip(data.decode("utf-8"))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {out.count(chr(10))} lines, SHA-256 {hashlib.sha256(out.encode()).hexdigest()}")


if __name__ == "__main__":
    main()
