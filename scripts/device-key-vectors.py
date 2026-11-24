#!/usr/bin/env python3
"""The crypto rows of design 129's shared contract (§7.1): server/testdata/device_key.json.

Made with a throwaway P-256 key whose private scalar is derived from the phrase below (a fake key that looks fake, the
secrets rule). Only public data is written: the public key, the signed bytes and the signatures. Signing is pure Python
(standard library only, deterministic nonces), so the file is reproducible. The route rows (`edge`, `answers`,
`enroll`, `register`, `policy`) are written here as the contract says them; the server's
`tests::device_key::the_contract` produces each through the real routes and compares. Run with `python3 -I`:

    python3 -I scripts/device-key-vectors.py            # prints the file
    python3 -I scripts/device-key-vectors.py --write    # writes server/testdata/device_key.json
    python3 -I scripts/device-key-vectors.py --check    # the committed file is exactly what this script writes
"""
import base64, hashlib, hmac, json, os, sys

P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
A = P - 3
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)
PHRASE = b"vibb fake phone key - test only, never a real phone"
OTHER = b"vibb fake phone key - test only, never a real phone (another one)"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "server", "testdata",
                   "device_key.json")
# The window (s4.3) and the clock's floor step (10 s writes + the future window).
PAST_MS, FUTURE_MS, FLOOR_STEP_MS = 60_000, 30_000, 40_000


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
def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def b64(b): return base64.b64encode(b).decode()
def pub(d): x, y = mul(d, G); return b"\x04" + x.to_bytes(32, "big") + y.to_bytes(32, "big")


def sign_der(d, msg):
    z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
    ctr = 0
    while True:
        k = int.from_bytes(hmac.new(d.to_bytes(32, "big"), msg + bytes([ctr]), hashlib.sha256).digest(), "big") % (N - 1) + 1
        r = mul(k, G)[0] % N
        s = pow(k, -1, N) * (z + r * d) % N
        if r and s: break
        ctr += 1

    def i(v):
        b = v.to_bytes(32, "big").lstrip(b"\0") or b"\0"
        if b[0] & 0x80: b = b"\0" + b
        return b"\x02" + bytes([len(b)]) + b
    body = i(r) + i(s)
    return b"\x30" + bytes([len(body)]) + body


def fixed(label, n=16):
    """Fixed 'random' bytes for the rows: a hash of a label."""
    return hashlib.sha256(b"vibb device-key vectors " + label.encode()).digest()[:n]


def canonical(key_id, t, nonce, method, path, query, encoding, body_hash):
    target = path + ("?" + query if query else "")
    return "\n".join(["vibb-req-v1", key_id, str(t), nonce, method, target, encoding, body_hash])


def normalise(code):
    return "".join(c for c in code if not c.isspace() and c != "-").upper()


def enroll_challenge(code):
    return hashlib.sha256(b"vibb-enroll-v1\x00" + normalise(code).encode()).digest()


def proof_message(challenge, kind):
    return "vibb-key-v1\n" + b64u(challenge) + "\n" + kind


def build():
    d, other = scalar(PHRASE), scalar(OTHER)
    key = b64u(pub(d))
    key_id = b64u(fixed("key id"))
    t = 1_790_000_000_000

    canonical_rows = []
    for name, method, path, query, encoding, body in [
        ("a policy fetch", "GET", "/api/devices/policy", None, "", b""),
        ("a status report, gzip'd", "POST", "/api/devices/status", None, "gzip", bytes(range(32))),
        ("percent-encoded segments, raw", "GET", "/api/devices/calendar/feeds/a%2Fb%20c", "x=%2F&y=1", "",
         b""),
        ("a JSON body", "POST", "/api/devices/time-request", None, "", b'{"id":"00ff","asked_at_ms":1}'),
    ]:
        nonce = b64u(fixed("nonce " + name))
        body_hash = hashlib.sha256(body).hexdigest()
        canonical_rows.append({
            "name": name, "key_id": key_id, "t": t, "nonce": nonce, "method": method, "encoded_path": path,
            "encoded_query": query, "content_encoding": encoding, "body_b64": b64(body), "body_sha256": body_hash,
            "signed": canonical(key_id, t, nonce, method, path, query, encoding, body_hash),
        })

    signed_rows = []
    for row in canonical_rows:
        sig = b64u(sign_der(d, row["signed"].encode()))
        signed_rows.append({
            "name": row["name"], "public_key": key, "key_id": key_id, "t": t, "nonce": row["nonce"],
            "method": row["method"], "encoded_path": row["encoded_path"], "encoded_query": row["encoded_query"],
            "content_encoding": row["content_encoding"], "body_b64": row["body_b64"],
            "body_sha256": row["body_sha256"],
            "signature": f"v1;k={key_id};t={t};n={row['nonce']};s={sig}", "valid": True,
        })
    good = signed_rows[3]
    signed_rows.append(dict(good, name="the body hash header names another body", valid=False,
                            body_sha256=hashlib.sha256(b"another body").hexdigest(), reason="bad_sig"))
    signed_rows.append(dict(good, name="another key signed it", valid=False, reason="bad_sig",
                            signature=f"v1;k={key_id};t={t};n={good['nonce']};s="
                                      + b64u(sign_der(other, canonical_rows[3]["signed"].encode()))))
    signed_rows.append(dict(good, name="another method", method="PUT", valid=False, reason="bad_sig"))

    challenge = fixed("register challenge", 32)
    code = "abcd-efgh 2345"
    enroll = enroll_challenge(code)
    proof_rows = []
    for kind, ch in (("register", challenge), ("enroll", enroll)):
        message = proof_message(ch, kind)
        proof_rows.append({"kind": kind, "public_key": key, "challenge": b64u(ch), "signed": message,
                           "proof": b64u(sign_der(d, message.encode())), "valid": True})
    proof_rows.append(dict(proof_rows[0], name="a register proof is no enrol proof", kind="enroll",
                           signed=proof_message(challenge, "enroll"), valid=False))

    return {
        "comment": "Design 129 S1's shared contract (s7.1). The crypto rows are scripts/device-key-vectors.py's (a "
                   "throwaway key from a fake phrase - public data only); the route rows are produced by the "
                   "server's tests::device_key through the real routes. The launcher's copy and its "
                   "DeviceKeyContractTest come with L1; S1 keeps the file server-side.",
        "headers": {"signature": "Vibb-Signature", "body_sha256": "Vibb-Body-SHA256", "time": "Vibb-Time",
                    "auth": "Vibb-Auth"},
        "encodings": {"key_id": "16 bytes, Base64url without padding", "nonce": "16 bytes, Base64url without padding",
                      "signature": "DER ECDSA P-256 SHA-256, Base64url without padding",
                      "chain": "each certificate DER, standard Base64", "challenge": "Base64url without padding",
                      "proof": "DER ECDSA P-256 SHA-256, Base64url without padding"},
        "canonical": canonical_rows,
        "signed": signed_rows,
        "proof": proof_rows,
        "enroll_challenge": [
            {"code": code, "normalised": normalise(code), "challenge": b64u(enroll)},
            {"code": "ABCDEFGH2345", "normalised": "ABCDEFGH2345", "challenge": b64u(enroll_challenge("ABCDEFGH2345"))},
        ],
        "clock": {
            "sig_now": [
                {"name": "no floor: the wall clock", "stored": None, "wall_at_start": 1_790_000_000_000,
                 "elapsed": 5_000, "sig_now": 1_790_000_005_000},
                {"name": "the wall clock behind the floor: the floor", "stored": 1_790_000_000_000,
                 "wall_at_start": 1_789_000_000_000, "elapsed": 1_000,
                 "sig_now": 1_790_000_000_000 + FLOOR_STEP_MS + 1_000},
                {"name": "the wall clock past the floor: the wall clock", "stored": 1_790_000_000_000,
                 "wall_at_start": 1_791_000_000_000, "elapsed": 0, "sig_now": 1_791_000_000_000},
            ],
            "offset": [
                {"name": "the round trip's middle", "vibb_time": 1_790_000_000_000, "sent_elapsed": 10_000,
                 "received_elapsed": 10_400, "now_elapsed": 20_000, "offset": 1_790_000_000_000 - 10_200,
                 "t": 1_790_000_000_000 - 10_200 + 20_000},
            ],
        },
        "window": {"past_ms": PAST_MS, "future_ms": FUTURE_MS, "rows": [
            {"t": t - PAST_MS, "sig_now": t, "verdict": "ok"},
            {"t": t - PAST_MS - 1, "sig_now": t, "verdict": "stale"},
            {"t": t + FUTURE_MS, "sig_now": t, "verdict": "ok"},
            {"t": t + FUTURE_MS + 1, "sig_now": t, "verdict": "stale"},
        ]},
        "edge": [
            {"kind": "bearer_valid", "authenticated": True, "status": 200, "vibb_auth": None},
            {"kind": "bearer_invalid", "authenticated": False, "status": 401, "vibb_auth": None},
            {"kind": "nothing", "authenticated": False, "status": 401, "vibb_auth": "missing"},
            {"kind": "signature_valid", "authenticated": True, "status": 200, "vibb_auth": None},
            {"kind": "signature_unknown_key", "authenticated": False, "status": 401, "vibb_auth": "unknown_key"},
            {"kind": "signature_bad", "authenticated": False, "status": 401, "vibb_auth": "bad_sig"},
            {"kind": "signature_stale", "authenticated": False, "status": 401, "vibb_auth": "stale"},
            {"kind": "signature_replayed", "authenticated": False, "status": 401, "vibb_auth": "replay"},
            {"kind": "signature_no_body_hash", "authenticated": False, "status": 401, "vibb_auth": "missing"},
            {"kind": "signature_body_changed", "authenticated": True, "status": 400, "vibb_auth": "bad_body"},
            {"kind": "signature_bad_with_own_bearer", "authenticated": True, "status": 200, "vibb_auth": None},
        ],
        "answers": [
            {"reason": "stale", "phone": "take_offset_and_retry_once"},
            {"reason": "replay", "phone": "retry_once_new_nonce"},
            {"reason": "unknown_key", "phone": "ask_registration"},
            {"reason": "bad_sig", "phone": "silent"},
            {"reason": "bad_body", "phone": "silent"},
            {"reason": "missing", "phone": "silent"},
            {"reason": "key_required", "phone": "silent"},
        ],
        "enroll": {
            "body_code_only": {"enrollment_code": "<code>"},
            "body_with_key": {"enrollment_code": "<code>", "chain": ["<Base64 DER>"], "proof": "<Base64url>"},
            "answers": {
                "code_only": {"device_id": "<id>", "device_token": "<token>"},
                "observed": {"device_id": "<id>", "device_token": "<token>",
                             "key": {"key_id": "<key id>", "state": "observed"}},
                "refused": {"device_id": "<id>", "device_token": "<token>",
                            "key": {"state": "refused", "reason": "app"}},
                "list_stale": {"device_id": "<id>", "device_token": "<token>",
                               "key": {"state": "refused", "reason": "list_stale", "retry_after_s": 3600}},
                "repeat": {"device_id": "<id>", "device_token": "<token>",
                           "key": {"key_id": "<key id>", "state": "observed"}},
            },
            "refusal_words": ["chain", "root", "revoked", "expired", "software", "properties", "app", "boot",
                              "lost", "challenge", "proof", "list_stale"],
        },
        "register": {
            "get_key_none": {"state": "none", "rotate": False},
            "get_key_observed": {"state": "observed", "key_id": "<key id>", "spki_sha256": "<hex>", "rotate": False},
            "challenge": {"challenge": "<Base64url, 32 bytes>", "expires_in_s": 600},
            "body": {"challenge": "<Base64url>", "chain": ["<Base64 DER>"], "proof": "<Base64url>"},
            "answers": [
                {"case": "ok", "status": 200, "body": {"key_id": "<key id>", "state": "observed"}},
                {"case": "same_key_again", "status": 200, "body": {"key_id": "<key id>", "state": "observed"}},
                {"case": "another_key", "status": 409, "body": {"reason": "exists"}},
                {"case": "refused", "status": 400, "body": {"reason": "app"}},
                {"case": "challenge_used", "status": 400, "body": {"reason": "challenge"}},
                {"case": "list_stale", "status": 503, "body": {"reason": "list_stale", "retry_after_s": 3600}},
            ],
            "conflict": {"status": 204},
        },
        "policy": {
            "none": {"state": "none", "rotate": False},
            "observed": {"state": "observed", "key_id": "<key id>", "spki_sha256": "<hex>", "rotate": False},
        },
    }


def render():
    return json.dumps(build(), indent=2, ensure_ascii=False) + "\n"


def main():
    out = render()
    if "--write" in sys.argv:
        with open(OUT, "w") as f:
            f.write(out)
        print(f"wrote {OUT}")
    elif "--check" in sys.argv:
        with open(OUT) as f:
            if f.read() != out:
                raise SystemExit("server/testdata/device_key.json is not what the script writes")
        print("device-key-vectors check ok")
    else:
        sys.stdout.write(out)


if __name__ == "__main__":
    main()
