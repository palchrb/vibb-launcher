#!/usr/bin/env python3
"""The server's VAPID vectors for design 108b (§4.3, §10): server/testdata/unified_push_vapid.json.

Made with a throwaway test key whose private scalar is derived from the phrase below (a fake key that looks fake, the
secrets rule). Only public data is written: the public keys, the signed headers and the expected outcome. Signing is
pure Python (standard library only, deterministic nonces), so the file is reproducible. Run with `python3 -I`:

    python3 -I scripts/up-vapid-vectors.py            # prints the file
    python3 -I scripts/up-vapid-vectors.py --write    # writes server/testdata/unified_push_vapid.json

`--keys` prints the two public keys the contract's `body_vapid` and `body_vapid_changed` carry.
"""
import base64, hashlib, hmac, json, os, sys

P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
A = P - 3
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)
NOW = 1790000000
ORIGIN = "https://phones.example.com"
PHRASE = b"vibb fake vapid key - test only, never a real sender"
PHRASE2 = b"vibb fake vapid key - test only, never a real sender (second key)"


def add(p, q):
    if p is None: return q
    if q is None: return p
    if p[0] == q[0] and (p[1] + q[1]) % P == 0: return None
    if p == q: l = (3 * p[0] * p[0] + A) * pow(2 * p[1], -1, P) % P
    else: l = (q[1] - p[1]) * pow(q[0] - p[0], -1, P) % P
    x = (l * l - p[0] - q[0]) % P
    return (x, (l * (p[0] - x) - p[1]) % P)


def mul(k, p):
    r = None
    while k:
        if k & 1: r = add(r, p)
        p = add(p, p)
        k >>= 1
    return r


def scalar(phrase): return int.from_bytes(hashlib.sha256(phrase).digest(), "big") % (N - 1) + 1
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def pub(d): x, y = mul(d, G); return b"\x04" + x.to_bytes(32, "big") + y.to_bytes(32, "big")


def sign(d, msg):
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    ctr = 0
    while True:
        k = int.from_bytes(hmac.new(d.to_bytes(32, "big"), msg + bytes([ctr]), hashlib.sha256).digest(), "big") % (N - 1) + 1
        r = mul(k, G)[0] % N
        s = pow(k, -1, N) * (z + r * d) % N
        if r and s: return r.to_bytes(32, "big") + s.to_bytes(32, "big")
        ctr += 1


def der(sig):
    def i(b):
        b = b.lstrip(b"\0") or b"\0"
        if b[0] & 0x80: b = b"\0" + b
        return b"\x02" + bytes([len(b)]) + b
    body = i(sig[:32]) + i(sig[32:])
    return b"\x30" + bytes([len(body)]) + body


def jwt(d, claims, header=None, tamper=None, asn1=False):
    h = b64(json.dumps(header or {"typ": "JWT", "alg": "ES256"}, separators=(",", ":")).encode())
    c = b64(json.dumps(claims, separators=(",", ":")).encode())
    sig = sign(d, (h + "." + c).encode())
    if tamper: sig = sig[:-1] + bytes([sig[-1] ^ 1])
    if asn1: sig = der(sig)
    return h + "." + c + "." + b64(sig)


def main():
    d1, d2 = scalar(PHRASE), scalar(PHRASE2)
    k1, k2 = b64(pub(d1)), b64(pub(d2))
    if "--keys" in sys.argv:
        print(k1); print(k2); return
    ok = lambda **kw: dict({"aud": ORIGIN, "exp": NOW + 86400, "sub": "mailto:admin@example.org"}, **kw)
    rows = []
    def row(name, outcome, auth=None, crypto_key=None, note=None):
        r = {"name": name, "outcome": outcome, "authorization": auth}
        if crypto_key is not None: r["crypto_key"] = crypto_key
        if note: r["note"] = note
        rows.append(r)
    t = jwt(d1, ok())
    std = k1.replace("-", "+").replace("_", "/")
    # taken
    row("rfc_mastodon", "taken", f"vapid t={t},k={k1}", note="Mastodon's spelling: no space after the comma")
    row("rfc_spaced", "taken", f"vapid k={k1}, t={t}", note="a space, the other order")
    row("rfc_quoted", "taken", f'vapid t="{t}", k="{k1}"')
    row("scheme_case", "taken", f"Vapid t={t},k={k1}")
    row("scheme_case_webpush", "taken", f"WEBPUSH {t}", f"p256ecdsa={k1}")
    row("k_padded", "taken", f"vapid t={t},k={k1}=")
    row("k_std_alphabet", "taken", f"vapid t={t},k={std}", note="the standard alphabet; equals k when the key has no - or _")
    row("draft_webpush", "taken", f"WebPush {t}", f"dh=AAAAAAAAAAAAAAAAAAAAAA;p256ecdsa={k1}")
    row("exp_float", "taken", f"vapid t={jwt(d1, ok(exp=NOW + 86400.5))},k={k1}")
    row("exp_24h_plus_30min", "taken", f"vapid t={jwt(d1, ok(exp=NOW + 86400 + 1800))},k={k1}")
    row("exp_slow_sender_edge", "taken", f"vapid t={jwt(d1, ok(exp=NOW - 299))},k={k1}")
    row("aud_array", "taken", f"vapid t={jwt(d1, ok(aud=[ORIGIN]))},k={k1}")
    row("aud_with_port_443", "taken", f"vapid t={jwt(d1, ok(aud='HTTPS://Phones.Example.com:443'))},k={k1}")
    row("previous_origin", "taken", f"vapid t={jwt(d1, ok(aud='https://old.example.net'))},k={k1}")
    # missing
    row("missing", "missing", None)
    row("no_key", "missing", f"vapid t={t}", note="a token without k")
    # wrong key
    row("wrong_key", "wrong_key", f"vapid t={jwt(d2, ok())},k={k2}", note="signed correctly, by a key that is not the registered one")
    # bad: the signature is checked before exp and aud (Q3 refinement), so a bad one is always 403
    row("bad_signature", "bad", f"vapid t={jwt(d1, ok(), tamper=True)},k={k1}")
    row("asn1_signature", "bad", f"vapid t={jwt(d1, ok(), asn1=True)},k={k1}")
    row("alg_none", "bad", f"vapid t={b64(b'{\"typ\":\"JWT\",\"alg\":\"none\"}')}.{b64(json.dumps(ok(), separators=(',', ':')).encode())}.,k={k1}")
    row("crit_header", "bad", f"vapid t={jwt(d1, ok(), header={'typ': 'JWT', 'alg': 'ES256', 'crit': ['exp']})},k={k1}")
    row("oversized", "bad", f"vapid t={jwt(d1, ok(sub='mailto:' + 'a' * 1100 + '@example.org'))},k={k1}", note="Authorization over 1024 bytes")
    row("expired_bad_signature", "bad", f"vapid t={jwt(d1, ok(exp=NOW - 600), tamper=True)},k={k1}", note="a forger never gets a 503: the signature is judged first")
    row("wrong_aud_bad_signature", "bad", f"vapid t={jwt(d1, ok(aud='https://other.example.com'), tamper=True)},k={k1}", note="the same for the address")
    # time and address: correctly signed only
    row("expired", "time", f"vapid t={jwt(d1, ok(exp=NOW - 600))},k={k1}")
    row("exp_26h", "time", f"vapid t={jwt(d1, ok(exp=NOW + 26 * 3600))},k={k1}")
    row("wrong_aud", "address", f"vapid t={jwt(d1, ok(aud='https://other.example.com'))},k={k1}")
    row("previous_origin_ended", "address", f"vapid t={jwt(d1, ok(aud='https://ended.example.net'))},k={k1}")
    out = {
        "comment": "Design 108b §4.3/§10, server-only. Made by scripts/up-vapid-vectors.py with a throwaway test key "
                   "(the private scalar is SHA-256 of a fixed phrase, never committed). Order of the check: form, key, "
                   "shape, SIGNATURE, then exp, then aud (outcome time/address only for a correctly signed push). "
                   "Public data only.",
        "now": NOW,
        "origin": ORIGIN,
        "previous_origins": [
            {"origin": "https://old.example.net", "until": NOW + 10 * 86400},
            {"origin": "https://ended.example.net", "until": NOW - 86400},
        ],
        "registered_key": k1,
        "other_key": k2,
        "not_a_point": b64(b"\x04" + hashlib.sha256(b"not a point x").digest() + hashlib.sha256(b"not a point y").digest()),
        "rows": rows,
    }
    text = json.dumps(out, indent=1, ensure_ascii=False) + "\n"
    if "--write" in sys.argv:
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", "testdata", "unified_push_vapid.json")
        open(path, "w").write(text)
    else:
        sys.stdout.write(text)


main()
