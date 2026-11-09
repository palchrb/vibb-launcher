#!/usr/bin/env python3
"""A fake MMS server (MMSC) for the emulator - design 77 step 3b, docs/testing/emulator.md §6v "step 3b".

Synthetic only: made-up numbers in the repo's fake series (+479000xxxx), no carrier, no real host. Python's standard
library alone; run it with -I.

  fake-mmsc.py push N FROM LOCATION    prints an m-notification-ind (X-Mms-Message-Type 0x82) as hex, for the debug
                                       launcher's MmsDebugReceiver (`--es push_hex`): FROM's MMS waits at LOCATION
  fake-mmsc.py serve PORT OUT_DIR      serves GET /mms/<n> as an m-retrieve-conf (a small JPEG and a text) and answers
                                       every POST (a send-req gets an m-send-conf "Ok", an acknowledgement an empty 200),
                                       saving each POST body to OUT_DIR
  fake-mmsc.py --selftest              builds each PDU and checks its shape

GET variants: /mms/<n>?from=+4790000002 (the retrieve-conf's From; default +4790000001), ?to= (the kid; default
+4790000010), /mms/<n>-group (To also +4790000005), /mms/<n>-stranger-group (To also +4790000099), /mms/<n>-gone (404).
The launcher only fetches from a host on its MMSC list (D10) on port 80, 443 or 8080: serve on 8080 and put 10.0.2.2
on the list with the debug hook's `--es mmsc_host`.
"""
import base64
import hashlib
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

# A made-up 160x120 picture (a gradient and a peach disc), quality 80, no EXIF.
JPEG_B64 = (
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYn"
    "KSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgo"
    "KCgoKCgoKCj/wAARCAB4AKADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF"
    "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW"
    "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi"
    "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC"
    "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm"
    "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq"
    "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDhcUYp2KMV9lzngcg3FGKdijFHOHINxRinYoxRzhyDcUYp2KMUc4cg3FGKdijFHOHINxRi"
    "nYoxRzhyDcUYp2KMUc4cg3FGKdijFHOHINxRinYoxRzhyDcUYp2KMUc4cg7FGKdijFcXOdfINxRinYoxRzhyDcUYp2KMUc4cg3FG"
    "KdijFHOHINxRinYoxRzhyDcUYp2KMUc4cg3FGKdijFHOHINxRinYoxRzhyDcUYp2KMUc4cg3FGKdijFHOHIOxRinYoxXHznVyDcU"
    "Yp2KMUc4cg3FGKdijFHOHINxRinYrp/Bnhv+2JTc3RxYxNtYA8yNwdvsORk/l6jOriY0YOc3ojSlh5VZKEdzO0Xw5qOr4a2h2QH/"
    "AJbS/Knfoep5GOAcd66+0+Htqu/7XfTy5xt8pRHj1zndnt6V2saJHGscaqiKAqqowAB0AFOr5yvm1eo/cfKj6CjldCmvfXMzirr4"
    "e2TRgWt5cxvnkyBXGPoMfzrldc8J6jpW+Ty/tFsMnzYhnA55YdRwMnsPWvX6Kmjm2Ipv3nzLzKq5XQqL3VZ+R4DijFdt448MLZB9"
    "S08KtsSPNi6eWScZX2JPTt246cXivo6GKjXgpwPn62GlRnySG4oxTsUYrbnMeQbijFOxRijnDkG4oxTsUYo5w5B2KMU7FGK4+c6+"
    "QbijFOxRijnDkG4oxTsUYo5w5BuK9v0ixj03TbeziOViXBP949SfbJJOK8h0D/kO6b/18x/+hCvaa8fNarfLDpuetllNLml1Ciii"
    "vIPWCiiigCO4hjuLeWGZd0UilHXOMgjBFeI6hbfZL+5tt+/yZWj3YxnBIzj8K9yryXx1/wAjTe/8A/8AQFr1MrqOM5R6WPMzOmpQ"
    "jLrc5/FGKdijFe3znjcg3FGKdijFHOHINxRinYoxRzhyDsUYp2KMVx8518g3FGKdijFHOHINxRinYoxRzhyBGzRyK8bMjqQyspwQ"
    "R3Br22wuor6zhuYDmOVQw5GR7HHcdDXiWK6/wR4iSx/0C+bbbu2Y5SeIyex9FP6EnPXI4sZB1IprdHXg5qnJp7M9FoooryT1gooo"
    "oAbI6xxs8jKiKCzMxwAB3JrxbVrr7dql1dAuVlkZl3nkLngfgMCu18ceIkEUul2TbpG+WeRTwo7qMdT2P5euOBxXqYKDgnN9Ty8b"
    "NTagug3FGKdijFd/OcXINxRinYoxRzhyDcUYp2KMUc4cg7FGKdijFcfOdXINxRinYoxRzhyDcUYp2KMUc4cg3FGKdijFHOHIdBon"
    "iu902NIZALm2QYVHOGUc8Bvy654GBiuttfGWkzbvMea3xjHmR53fTbn9a8yxRisJ0oTdzeFWcFY9OufGOkQoGjllnJONscZBHv8A"
    "NgVzWs+M7u63RWC/ZYTkb+shHPf+HjHTkEda5bFGKUKVOOu4SqzloNxRinYoxXRzmHINxRinYoxRzhyDcUYp2KMUc4cg3FGKdijF"
    "HOHIOxRinYoxXFznXyDcUYp2KMUc4cg3FGKdijFHOHINxRinYoxRzhyDcUYp2KMUc4cg3FGKdijFHOHINxRinYoxRzhyDcUYp2KM"
    "Uc4cg3FGKdijFHOHINxRinYoxRzhyDsUYoorluzpsgxRiiii7CyDFGKKKLsLIMUYooouwsgxRiiii7CyDFGKKKLsLIMUYooouwsg"
    "xRiiii7CyDFGKKKLsLIMUYooouwsj//Z"
)

# The picture's SHA-256: the selftest checks the constant decodes to exactly these bytes.
JPEG_SHA256 = "02858e03303f132f975fa00e93149c3cd3dcf68103f907c28abafbdf8cb93ab1"

KID = "+4790000010"


def jpeg():
    return base64.b64decode("".join(JPEG_B64))


def uintvar(v):
    groups = []
    while True:
        groups.append(v & 0x7F)
        v >>= 7
        if v == 0:
            break
    groups.reverse()
    return bytes([g | 0x80 for g in groups[:-1]] + [groups[-1]])


def text(s):
    return s.encode("latin-1") + b"\x00"


def long_integer(v):
    octets = []
    while v:
        octets.insert(0, v & 0xFF)
        v >>= 8
    octets = octets or [0]
    return bytes([len(octets)] + octets)


def with_length(inner):
    n = len(inner)
    return (bytes([n]) if n <= 30 else b"\x1f" + uintvar(n)) + inner


def address(a):
    return with_length(b"\x80" + text(a))


def notification(n, sender, location):
    """An m-notification-ind: transaction id, version 1.2, From, class personal, size, expiry in 7 days, location."""
    return (
        b"\x8c\x82"
        + b"\x98" + text("T-fake-%04d" % n)
        + b"\x8d\x92"
        + b"\x89" + address(sender + "/TYPE=PLMN")
        + b"\x8a\x80"
        + b"\x8e" + long_integer(40_000)
        + b"\x88" + with_length(b"\x81" + long_integer(7 * 24 * 3600))
        + b"\x83" + text(location)
    )


def part(headers, data):
    return uintvar(len(headers)) + uintvar(len(data)) + headers + data


def retrieve_conf(n, sender, recipients, words):
    """An m-retrieve-conf, multipart/mixed: the JPEG and a UTF-8 text."""
    jpeg_headers = b"\x9e" + b"\x8e" + text("image000.jpg") + b"\xc0\x22" + text("<image000>")
    text_headers = with_length(b"\x83\x81\xea") + b"\x8e" + text("text000.txt")
    headers = (
        b"\x8c\x84"
        + b"\x98" + text("T-fake-%04d" % n)
        + b"\x8d\x92"
        + b"\x8b" + text("fake-msg-%04d" % n)
        + b"\x85" + long_integer(int(time.time()))
        + b"\x89" + address(sender + "/TYPE=PLMN")
    )
    for r in recipients:
        headers += b"\x97" + text(r + "/TYPE=PLMN")
    headers += b"\x84\xa3"
    return headers + uintvar(2) + part(jpeg_headers, jpeg()) + part(text_headers, words.encode("utf-8"))


def send_conf():
    """An m-send-conf with X-Mms-Response-Status Ok."""
    return b"\x8c\x81" + b"\x98" + text("T-fake-send") + b"\x8d\x92" + b"\x92\x80" + b"\x8b" + text("fake-sent-0001")


class Handler(BaseHTTPRequestHandler):
    out_dir = "."

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-mmsc: " + (fmt % args) + "\n")

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        sender = q.get("from", ["+4790000001"])[0]
        kid = q.get("to", [KID])[0]
        name = url.path.rsplit("/", 1)[-1]
        if name.endswith("-gone"):
            self.send_response(404)
            self.end_headers()
            return
        first = name.split("-")[0]
        n = int(first) if first.isdigit() else 1
        recipients = [kid]
        if name.endswith("-stranger-group"):
            recipients.append("+4790000099")
        elif name.endswith("-group"):
            recipients.append("+4790000005")
        body = retrieve_conf(n, sender, recipients, "Hei fra den falske MMS-serveren %d" % n)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.wap.mms-message")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        kind = body[1] if len(body) > 1 else 0
        path = "%s/post-%d-%02x.bin" % (self.out_dir, int(time.time() * 1000), kind)
        with open(path, "wb") as f:
            f.write(body)
        sys.stderr.write("fake-mmsc: POST %d bytes, type 0x%02x -> %s\n" % (len(body), kind, path))
        answer = send_conf() if kind == 0x80 else b""
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.wap.mms-message")
        self.send_header("Content-Length", str(len(answer)))
        self.end_headers()
        self.wfile.write(answer)


def selftest():
    n = notification(3, "+4790000001", "http://10.0.2.2:8080/mms/3")
    assert n[:2] == b"\x8c\x82" and n.endswith(b"http://10.0.2.2:8080/mms/3\x00") and len(n) <= 2048, n
    assert b"+4790000001/TYPE=PLMN\x00" in n
    r = retrieve_conf(3, "+4790000002", [KID, "+4790000005"], "hei")
    assert r[:2] == b"\x8c\x84" and r.count(b"\x97") >= 2 and b"\x84\xa3" in r
    picture = jpeg()
    assert picture[:3] == b"\xff\xd8\xff" and picture[-2:] == b"\xff\xd9" and b"Exif" not in picture
    assert hashlib.sha256(picture).hexdigest() == JPEG_SHA256, "the embedded picture isn't the one made"
    assert r.endswith(b"hei") and picture in r
    assert send_conf()[:2] == b"\x8c\x81" and b"\x92\x80" in send_conf()
    assert uintvar(0) == b"\x00" and uintvar(127) == b"\x7f" and uintvar(128) == b"\x81\x00"
    assert long_integer(40_000) == b"\x02\x9c\x40"
    print("fake-mmsc selftest ok")


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        selftest()
    elif len(sys.argv) == 5 and sys.argv[1] == "push":
        print(notification(int(sys.argv[2]), sys.argv[3], sys.argv[4]).hex())
    elif len(sys.argv) == 4 and sys.argv[1] == "serve":
        Handler.out_dir = sys.argv[3]
        HTTPServer(("0.0.0.0", int(sys.argv[2])), Handler).serve_forever()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
