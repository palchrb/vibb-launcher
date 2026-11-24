#!/usr/bin/env python3
"""The emulator profile's vectors (design 129b §7, `emulator_keys`): server/testdata/attestation/emulator/.

Chains in the shape the emulator gate's images attest (design 129 §7.3, read on 2026-11-24): three P-256
certificates - the leaf "Android Keystore Key", an intermediate `O=TEE, CN=<32 hex>` and a self-signed root
`CN=Droid Unregistered Device CA, O=Google Test LLC` -, the provisioning info on the intermediate and the root, both
security levels Software with every tag in the software list, attestation and KeyMint 300 (the Android 14 image) or
400 (the Android 16 image), an unverified, unlocked RootOfTrust with a zero boot key, the debug launcher's package.

Every key here is a throwaway derived from a fake phrase and every value is made up: nothing is a capture of an
emulator, a phone or Google's backend, and the debug signer's digest is a hash of a fake phrase. Signing is pure
Python (standard library only, deterministic nonces), so the files are reproducible. Each negative chain changes one
thing (the release package, the root's name, a fourth certificate, a tag in the hardware list, ...). Beside them
the gate's real chains (CAPTURED, from scripts/emulator-attestation-captures/), copied as they are. The server's
`attestation::emulator_tests::the_rows` reads `rows.json`: with the feature each row's verdict, without it `root`.

    python3 -I scripts/emulator-attestation-vectors.py --write    # writes the folder
    python3 -I scripts/emulator-attestation-vectors.py --check    # the committed files are exactly what this writes
"""
import base64, hashlib, hmac, json, os, sys

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "server", "testdata", "attestation",
                   "emulator")


class Curve:
    def __init__(self, p, n, g):
        self.p, self.n, self.g = p, n, g
        self.size = (p.bit_length() + 7) // 8

    def add(self, a, b):
        p = self.p
        if a is None: return b
        if b is None: return a
        if a[0] == b[0] and (a[1] + b[1]) % p == 0: return None
        if a == b: l = (3 * a[0] * a[0] - 3) * pow(2 * a[1], -1, p) % p
        else: l = (b[1] - a[1]) * pow(b[0] - a[0], -1, p) % p
        x = (l * l - a[0] - b[0]) % p
        return (x, (l * (a[0] - x) - a[1]) % p)

    def mul(self, k, pt=None):
        pt, r = pt or self.g, None
        while k:
            if k & 1: r = self.add(r, pt)
            pt = self.add(pt, pt)
            k >>= 1
        return r

    def point(self, d):
        x, y = self.mul(d)
        return b"\x04" + x.to_bytes(self.size, "big") + y.to_bytes(self.size, "big")


P256 = Curve(0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF,
             0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551,
             (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
              0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5))
P384 = Curve(2 ** 384 - 2 ** 128 - 2 ** 96 + 2 ** 32 - 1,
             0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973,
             (0xAA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7,
              0x3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F))


def scalar(curve, phrase):
    return int.from_bytes(hashlib.sha512(b"vibb fake emulator key - test only: " + phrase).digest(), "big") \
        % (curve.n - 1) + 1


def sign(d, msg):
    """ECDSA P-256 SHA-256, DER, with a deterministic nonce."""
    n, z, ctr = P256.n, int.from_bytes(hashlib.sha256(msg).digest(), "big"), 0
    while True:
        k = int.from_bytes(hmac.new(d.to_bytes(32, "big"), msg + bytes([ctr]), hashlib.sha256).digest(), "big") \
            % (n - 1) + 1
        r = P256.mul(k)[0] % n
        s = pow(k, -1, n) * (z + r * d) % n
        if r and s: break
        ctr += 1
    return seq(integer(r), integer(s))


# DER.
def length(n):
    if n < 0x80: return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def tlv(tag, content): return tag + length(len(content)) + content
def seq(*items): return tlv(b"\x30", b"".join(items))
def set_of(*items): return tlv(b"\x31", b"".join(sorted(items)))
def octets(b): return tlv(b"\x04", b)
def bits(b): return tlv(b"\x03", b"\x00" + b)
def null(): return b"\x05\x00"
def boolean(v): return tlv(b"\x01", b"\xff" if v else b"\x00")
def utf8(s): return tlv(b"\x0c", s.encode())


def integer(v, tag=b"\x02"):
    b = v.to_bytes(max(1, (v.bit_length() + 8) // 8), "big")
    return tlv(tag, b)


def enum(v): return integer(v, b"\x0a")


def oid(dotted):
    parts = [int(x) for x in dotted.split(".")]
    out = bytes([40 * parts[0] + parts[1]])
    for p in parts[2:]:
        enc = [p & 0x7F]
        p >>= 7
        while p:
            enc.append(0x80 | (p & 0x7F))
            p >>= 7
        out += bytes(reversed(enc))
    return tlv(b"\x06", out)


def explicit(n, content):
    """`[n] EXPLICIT`, constructed, context class; high tag numbers in base 128."""
    if n < 31: return tlv(bytes([0xA0 | n]), content)
    enc = [n & 0x7F]
    n >>= 7
    while n:
        enc.append(0x80 | (n & 0x7F))
        n >>= 7
    return tlv(bytes([0xBF]) + bytes(reversed(enc)), content)


def time(unix):
    import datetime
    t = datetime.datetime.fromtimestamp(unix, datetime.timezone.utc)
    if t.year < 2050: return tlv(b"\x17", t.strftime("%y%m%d%H%M%SZ").encode())
    return tlv(b"\x18", t.strftime("%Y%m%d%H%M%SZ").encode())


OID_CN, OID_O = "2.5.4.3", "2.5.4.10"
ECDSA_SHA256 = seq(oid("1.2.840.10045.4.3.2"))
KEY_DESCRIPTION = "1.3.6.1.4.1.11129.2.1.17"
PROVISIONING_INFO = "1.3.6.1.4.1.11129.2.1.30"


def name(*attrs): return seq(*[set_of(seq(oid(o), utf8(v))) for o, v in attrs])


def spki(curve, point):
    params = "1.2.840.10045.3.1.7" if curve is P256 else "1.3.132.0.34"
    return seq(seq(oid("1.2.840.10045.2.1"), oid(params)), bits(point))


def extension(dotted, value, critical=False):
    return seq(oid(dotted), *( [boolean(True)] if critical else [] ), octets(value))


def serial(label):
    b = bytearray(hashlib.sha256(b"vibb fake emulator serial " + label.encode()).digest()[:16])
    b[0] = (b[0] & 0x7F) | 0x10
    return int.from_bytes(b, "big")


def certificate(label, issuer, subject, not_before, not_after, key_spki, extensions, signer):
    tbs = seq(explicit(0, integer(2)), integer(serial(label)), ECDSA_SHA256, issuer,
              seq(time(not_before), time(not_after)), subject, key_spki, explicit(3, seq(*extensions)))
    return seq(tbs, ECDSA_SHA256, bits(sign(signer, tbs)))


CA = extension("2.5.29.19", seq(boolean(True)), critical=True)
# The provisioning info: a CBOR map (`{1: 1}`, certs_issued); only its form is read.
INFO = extension(PROVISIONING_INFO, b"\xa1\x01\x01")


def authorization_list(entries):
    return seq(*[explicit(n, v) for n, v in sorted(entries.items())])


def key_description(version, keymint, level, challenge, software, hardware):
    return seq(integer(version), enum(level), integer(keymint), enum(level), octets(challenge), octets(b""),
               authorization_list(software), authorization_list(hardware))


DEBUG_PACKAGE = "me.vibb.launcher.debug"
DEBUG_DIGEST = hashlib.sha256(b"vibb fake debug launcher signer - test only, never a real certificate").digest()
# A setup code in the QR form (26 of A-Z2-7), made up: the route test sets it on a phone and enrols with it.
CODE = "EMULATORTESTCODEVIBBFAKEAA"
CHALLENGE = hashlib.sha256(b"vibb-enroll-v1\x00" + CODE.encode()).digest()
NOW = 1795521600  # 2026-11-24T12:00:00Z
DAY = 86400

# Real chains from the emulator gate (design 129b §10.3, docs/testing/emulator.md "129b"): the first enrolment on a
# freshly wiped image, read off the enrolment body by the gate's logging proxy. Emulator certificates from Google's
# test CA for unregistered devices ("Droid Unregistered Device CA"), never a family phone's; public data only. Each is
# read at the `now` it was enrolled at (the intermediate lives two weeks); `challenge` is the leaf's attestation
# challenge (the typed setup code's), `signature` the gate machine's debug launcher signer as the leaf names it.
CAPTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "emulator-attestation-captures")
GATE_DEBUG_SIGNER = "oblmcWci6OgCZwUX5J0BLrNNNIXJAtkrrvvD3eqRYhA="
CAPTURED = [
    {"name": "a real Android 14 chain (compat34, the gate of 2026-11-24)", "file": "android14-2026-11-24.pem",
     "now": "2026-11-24T21:53:43Z", "challenge": "rcuvdN5sNsBG2BJPhf2qUAW4cw7/4VKrO5MO0fu/rFw=",
     "signature": GATE_DEBUG_SIGNER, "version": 300, "patch": 202309},
    {"name": "a real Android 16 chain (aosp36, the gate of 2026-11-24)", "file": "android16-2026-11-24.pem",
     "now": "2026-11-24T22:44:32Z", "challenge": "YBX6Qb7EVen6km8DujS8vS+6Pi6pa37MPKmWgqdNseM=",
     "signature": GATE_DEBUG_SIGNER, "version": 400, "patch": 202507},
]


def software_list(patch, os_version, package=DEBUG_PACKAGE):
    app = seq(set_of(seq(octets(package.encode()), integer(1))), set_of(octets(DEBUG_DIGEST)))
    return {
        1: set_of(integer(2)), 2: integer(3), 3: integer(256), 5: set_of(integer(4)), 10: integer(1),
        503: null(), 701: integer((NOW - 60) * 1000), 702: integer(0),
        704: seq(octets(bytes(32)), boolean(False), enum(2), octets(bytes(32))),
        705: integer(os_version), 706: integer(patch), 709: octets(app),
    }


def chain(image, version=400, keymint=None, patch=202507, os_version=160000, level=0, package=DEBUG_PACKAGE,
          root_name=("Droid Unregistered Device CA", "Google Test LLC"), hardware=None, fourth=False,
          root_info=True, self_signed=True, leaf_curve=P256, long=False):
    """Leaf first. `image` names the throwaway keys (a wiped image has another root key). `long`: valid until 2049
    (the route test reads it against the real clock), not the images' two and ten weeks."""
    end = 2524607999 if long else None
    root_d = scalar(P256, f"{image} root".encode())
    int_d = scalar(P256, f"{image} intermediate".encode())
    leaf_d = scalar(leaf_curve, f"{image} leaf".encode())
    root_subject = name((OID_CN, root_name[0]), (OID_O, root_name[1]))
    tee = hashlib.sha256(f"vibb fake emulator {image}".encode()).hexdigest()[:32]
    int_subject = name((OID_O, "TEE"), (OID_CN, tee))
    signer = root_d if self_signed else scalar(P256, f"{image} another".encode())
    root = certificate(f"{image} root", root_subject, root_subject, NOW - 7 * DAY, end or NOW + 63 * DAY,
                       spki(P256, P256.point(root_d)), [CA] + ([INFO] if root_info else []), signer)
    above, above_d, above_subject = [root], root_d, root_subject
    if fourth:
        extra_d = scalar(P256, f"{image} second intermediate".encode())
        extra_subject = name((OID_O, "TEE"), (OID_CN, tee + " 2"))
        extra = certificate(f"{image} second", root_subject, extra_subject, NOW - DAY, NOW + 13 * DAY,
                            spki(P256, P256.point(extra_d)), [CA], root_d)
        above, above_d, above_subject = [extra, root], extra_d, extra_subject
    intermediate = certificate(f"{image} intermediate", above_subject, int_subject, NOW - DAY, end or NOW + 13 * DAY,
                               spki(P256, P256.point(int_d)), [CA, INFO], above_d)
    description = key_description(version, keymint or version, level, CHALLENGE,
                                  software_list(patch, os_version, package), hardware or {})
    leaf = certificate(f"{image} leaf", int_subject, name((OID_CN, "Android Keystore Key")), 0, 2524608000,
                       spki(leaf_curve, leaf_curve.point(leaf_d)), [extension(KEY_DESCRIPTION, description)], int_d)
    return [leaf, intermediate] + above


def pem(certs):
    out = ""
    for der in certs:
        b = base64.b64encode(der).decode()
        out += "-----BEGIN CERTIFICATE-----\n" + "\n".join(b[i:i + 64] for i in range(0, len(b), 64)) \
            + "\n-----END CERTIFICATE-----\n"
    return out


def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def proof_message(challenge, kind):
    return "vibb-key-v1\n" + b64u(challenge) + "\n" + kind


def iso(unix):
    import datetime
    return datetime.datetime.fromtimestamp(unix, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build():
    files = {
        "android16.pem": chain("android16"),
        "android14.pem": chain("android14", version=300, patch=202309, os_version=140000),
        "release-package.pem": chain("android16", package="me.vibb.launcher"),
        "root-renamed.pem": chain("android16", root_name=("Droid Registered Device CA", "Google Test LLC")),
        "root-not-self-signed.pem": chain("android16", self_signed=False),
        "root-without-info.pem": chain("android16", root_info=False),
        "fourth-certificate.pem": chain("android16", fourth=True),
        "tee-claim.pem": chain("android16", level=1),
        "hardware-tag.pem": chain("android16", hardware={706: integer(202507)}),
        "versions-differ.pem": chain("android16", keymint=300),
        "route.pem": chain("route", long=True),
        "version-200.pem": chain("android16", version=200),
        "leaf-p384.pem": chain("android16", leaf_curve=P384),
    }
    challenge = base64.b64encode(CHALLENGE).decode()
    ok = {"verdict": "ok", "level": "software", "boot": "unverified", "locked": False, "reading": "unknown",
          "root": "emulator", "serials": 3}

    def row(name, chain_file, verdict, now=NOW, **extra):
        r = {"name": name, "chain": chain_file, "now": iso(now), "challenge": challenge}
        r.update(extra)
        if verdict == "ok": r.update(ok)
        else: r["verdict"] = verdict
        return r
    # The gate's real chains (design 129b §10.3), copied as they are from CAPTURES, each read at its own `now`.
    copies = {}
    for c in CAPTURED:
        with open(os.path.join(CAPTURES, c["file"])) as f:
            copies["captured-" + c["file"]] = f.read()
    captured = [r for c in CAPTURED for r in (
        {"name": c["name"], "chain": "captured-" + c["file"], "now": c["now"], "challenge": c["challenge"],
         "signature": c["signature"], "version": c["version"], "patch": c["patch"], **ok},
        {"name": c["name"] + ", another debug signer", "chain": "captured-" + c["file"], "now": c["now"],
         "challenge": c["challenge"], "verdict": "app"},
    )]
    rows = captured + [
        row("the Android 16 image", "android16.pem", "ok", version=400, patch=202507),
        row("the Android 14 image", "android14.pem", "ok", version=300, patch=202309),
        row("the route test's chain (valid for years)", "route.pem", "ok", version=400, patch=202507),
        row("the release package", "release-package.pem", "app"),
        row("no LAUNCHER_DEBUG_SIGNATURE_CHECKSUM", "android16.pem", "app", signature=""),
        row("another debug signer", "android16.pem", "app",
            signature=base64.b64encode(hashlib.sha256(b"vibb another fake signer").digest()).decode()),
        row("the root renamed", "root-renamed.pem", "root"),
        row("a root signed by another key", "root-not-self-signed.pem", "root"),
        row("no provisioning info on the root", "root-without-info.pem", "root"),
        row("a fourth certificate", "fourth-certificate.pem", "root"),
        row("a TEE claim under the emulator's root", "tee-claim.pem", "root"),
        row("a leaf on another curve", "leaf-p384.pem", "root"),
        row("a tag in the hardware list", "hardware-tag.pem", "properties"),
        row("attestation 400 with KeyMint 300", "versions-differ.pem", "properties"),
        row("attestation 200", "version-200.pem", "properties"),
        row("another challenge", "android16.pem", "challenge", challenge=base64.b64encode(b"other").decode()),
        row("the intermediate expired", "android16.pem", "expired", now=NOW + 14 * DAY),
        row("the root not valid yet", "android16.pem", "expired", now=NOW - 8 * DAY),
        row("the intermediate revoked", "android16.pem", "revoked",
            revoked=[format(serial("android16 intermediate"), "x")]),
    ]
    doc = {
        "comment": "Design 129b §7: the emulator_keys profile's rows. Made by scripts/emulator-attestation-vectors.py "
                   "from throwaway keys and made-up values in the shape of the gate images' chains (design 129 §7.3) "
                   "- never a capture -, and the `captured-*` chains: real ones from the emulator gate (design 129b "
                   "§10.3), copied from scripts/emulator-attestation-captures/. `challenge` and `signature` are "
                   "standard Base64; `revoked` the serials the row's status list names. With the feature each row's "
                   "verdict; without it every row is `root`.",
        "app": {"package": DEBUG_PACKAGE, "signature": base64.b64encode(DEBUG_DIGEST).decode()},
        "enroll": {
            "comment": "tests::emulator_keys: set `code` on a phone, enrol with `chain` and `proof` (the throwaway leaf's signature over `vibb-key-v1\\n<challenge>\\nenroll`), LAUNCHER_DEBUG_SIGNATURE_CHECKSUM `checksum`.",
            "code": CODE, "chain": "route.pem", "checksum": b64u(DEBUG_DIGEST),
            "proof": b64u(sign(scalar(P256, b"route leaf"), proof_message(CHALLENGE, "enroll").encode())),
        },
        "rows": rows,
    }
    out = {name: pem(certs) for name, certs in files.items()}
    out.update(copies)
    out["rows.json"] = json.dumps(doc, indent=1, ensure_ascii=False) + "\n"
    return out


def main():
    files = build()
    if "--write" in sys.argv:
        os.makedirs(OUT, exist_ok=True)
        for name, text in files.items():
            with open(os.path.join(OUT, name), "w") as f:
                f.write(text)
        print(f"wrote {len(files)} files to {OUT}")
    elif "--check" in sys.argv:
        present = sorted(os.listdir(OUT)) if os.path.isdir(OUT) else []
        if present != sorted(files):
            raise SystemExit(f"{OUT} holds {present}, the script writes {sorted(files)}")
        for name, text in files.items():
            with open(os.path.join(OUT, name)) as f:
                if f.read() != text:
                    raise SystemExit(f"{name} is not what the script writes")
        print("emulator-attestation-vectors check ok")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
