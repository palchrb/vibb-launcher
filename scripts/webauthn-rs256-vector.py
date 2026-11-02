#!/usr/bin/env python3
"""Writes server/testdata/webauthn/rs256.json: an RS256 passkey's registration and one assertion, made once with a
throwaway RSA key that exists only in this process (design 74 §7: ring can verify RS256 but can't make an RSA key).
Only public data is written - the COSE key, authenticatorData, clientDataJSON and the signature. Running it again
writes another key's vector (the test reads whichever is there).

Needs python3 with `cryptography`. Run from the repo root: scripts/webauthn-rs256-vector.py
"""
import base64
import hashlib
import json
import os
import struct
import sys

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

RP_ID = "handy.example.com"
ORIGIN = "https://handy.example.com"
OUT = os.path.join(os.path.dirname(__file__), "..", "server", "testdata", "webauthn", "rs256.json")


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def head(major, n):
    if n < 24:
        return bytes([major << 5 | n])
    for info, width in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * width):
            return bytes([major << 5 | info]) + n.to_bytes(width, "big")
    raise ValueError(n)


def cbor(item):
    if isinstance(item, bool):
        raise TypeError("no simple values")
    if isinstance(item, int):
        return head(0, item) if item >= 0 else head(1, -1 - item)
    if isinstance(item, bytes):
        return head(2, len(item)) + item
    if isinstance(item, str):
        data = item.encode()
        return head(3, len(data)) + data
    if isinstance(item, dict):
        return head(5, len(item)) + b"".join(cbor(k) + cbor(v) for k, v in item.items())
    raise TypeError(type(item))


def client_data(kind, challenge):
    return json.dumps(
        {"type": kind, "challenge": b64url(challenge), "origin": ORIGIN, "crossOrigin": False},
        separators=(",", ":"),
    ).encode()


def main():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    numbers = key.public_key().public_numbers()
    n = numbers.n.to_bytes(256, "big")
    e = numbers.e.to_bytes(3, "big")
    cose = cbor({1: 3, 3: -257, -1: n, -2: e})
    credential_id = hashlib.sha256(n).digest()
    rp_hash = hashlib.sha256(RP_ID.encode()).digest()
    flags_up_uv = 0x01 | 0x04

    auth = (
        rp_hash
        + bytes([flags_up_uv | 0x40])
        + struct.pack(">I", 1)
        + bytes(16)
        + struct.pack(">H", len(credential_id))
        + credential_id
        + cose
    )
    reg_client = client_data("webauthn.create", bytes([0x2A] * 32))
    attestation = cbor({"fmt": "none", "attStmt": {}, "authData": auth})

    get_auth = rp_hash + bytes([flags_up_uv]) + struct.pack(">I", 2)
    get_client = client_data("webauthn.get", bytes([0x2B] * 32))
    signature = key.sign(get_auth + hashlib.sha256(get_client).digest(), padding.PKCS1v15(), hashes.SHA256())

    vector = {
        "about": "RS256, made once by scripts/webauthn-rs256-vector.py with a throwaway key; public data only",
        "rp_id": RP_ID,
        "origin": ORIGIN,
        "registration": {
            "id": b64url(credential_id),
            "rawId": b64url(credential_id),
            "type": "public-key",
            "response": {"clientDataJSON": b64url(reg_client), "attestationObject": b64url(attestation)},
        },
        "assertion": {
            "id": b64url(credential_id),
            "rawId": b64url(credential_id),
            "type": "public-key",
            "response": {
                "clientDataJSON": b64url(get_client),
                "authenticatorData": b64url(get_auth),
                "signature": b64url(signature),
                "userHandle": None,
            },
        },
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as out:
        json.dump(vector, out, indent=2)
        out.write("\n")
    print(f"wrote {os.path.normpath(OUT)}", file=sys.stderr)


if __name__ == "__main__":
    main()
