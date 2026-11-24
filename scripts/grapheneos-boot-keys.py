#!/usr/bin/env python3
"""GrapheneOS's verified-boot keys for design 129 §9 (step 2): server/src/attestation/grapheneos_boot_keys.json.

Fetches GrapheneOS's signed https://grapheneos.org/attestation.json and its OpenSSH signature attestation.json.sig,
checks the signature with `ssh-keygen -Y verify` against the signer key pinned below (GrapheneOS's
https://grapheneos.org/allowed_signers, read 2026-11-24; the downloaded allowed_signers must name the same key),
refuses a `timestamp` older than the committed file's, and writes the list the server compiles in. Standard library
and ssh-keygen only; run with `python3 -I`:

    python3 -I scripts/grapheneos-boot-keys.py            # fetch, verify, print what would be written
    python3 -I scripts/grapheneos-boot-keys.py --write    # ... and write it
    python3 -I scripts/grapheneos-boot-keys.py --selftest # no network: the vendored sample, a throwaway signer

These are the fingerprints GrapheneOS's Auditor app pins; cross-check them against Auditor's source whenever the
list changes (a PLAN chore at every server release, as design 76's pins).
"""
import json, os, re, shutil, subprocess, sys, tempfile, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "server", "src", "attestation", "grapheneos_boot_keys.json")
SAMPLE = os.path.join(ROOT, "scripts", "grapheneos-boot-keys-sample")
SOURCE = "https://grapheneos.org/attestation.json"
SIGNERS = "https://grapheneos.org/allowed_signers"
IDENTITY = "contact@grapheneos.org"
NAMESPACE = "attestation keys"
PINNED_SIGNER = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIUg/m5CoP83b0rfSCzYSVA4cw4ir49io5GPoxbgxdJE"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "vibb-server-script"})
    with urllib.request.urlopen(request, timeout=30) as answer:
        return answer.read(1 << 20)


def signer_key(allowed_signers):
    """The `<type> <base64>` of the line naming IDENTITY."""
    for line in allowed_signers.decode().splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0] == IDENTITY:
            return parts[1] + " " + parts[2]
    return None


def verify(document, signature, signer, identity=IDENTITY):
    """`ssh-keygen -Y verify` of `document` against the one `signer` (`<type> <base64>`)."""
    with tempfile.TemporaryDirectory() as tmp:
        signers = os.path.join(tmp, "allowed_signers")
        sig = os.path.join(tmp, "doc.sig")
        with open(signers, "w") as f:
            f.write(f'{identity} namespaces="{NAMESPACE}" {signer}\n')
        with open(sig, "wb") as f:
            f.write(signature)
        done = subprocess.run(
            ["ssh-keygen", "-Y", "verify", "-f", signers, "-I", identity, "-n", NAMESPACE, "-s", sig],
            input=document, capture_output=True)
        return done.returncode == 0


def read_list(document):
    data = json.loads(document)
    keys = data["verifiedBootKeys"]
    stamp = data["timestamp"]
    if not isinstance(stamp, int) or not keys or not all(
            isinstance(k, str) and re.fullmatch(r"[0-9a-f]{64}", k) for k in keys):
        raise ValueError("attestation.json is not the expected form")
    return stamp, sorted(set(keys))


def render(stamp, keys):
    return json.dumps({"source": SOURCE, "timestamp": stamp, "verified_boot_keys": keys}, indent=2) + "\n"


def committed_stamp(path=OUT):
    try:
        with open(path) as f:
            return json.load(f)["timestamp"]
    except FileNotFoundError:
        return 0


def build(document, signature, allowed_signers, signer, identity, floor):
    named = signer_key(allowed_signers) if identity == IDENTITY else signer
    if named != signer:
        raise SystemExit(f"allowed_signers names {named!r}, not the pinned signer")
    if not verify(document, signature, signer, identity):
        raise SystemExit("the signature does not verify")
    stamp, keys = read_list(document)
    if stamp < floor:
        raise SystemExit(f"timestamp {stamp} is older than the committed {floor}")
    return render(stamp, keys)


def selftest():
    if not shutil.which("ssh-keygen"):
        raise SystemExit("selftest needs ssh-keygen")
    with open(os.path.join(SAMPLE, "attestation.json"), "rb") as f:
        document = f.read()
    with open(os.path.join(SAMPLE, "attestation.json.sig"), "rb") as f:
        signature = f.read()
    with open(os.path.join(SAMPLE, "signer.pub")) as f:
        fake = " ".join(f.read().split()[:2])
    identity = "selftest@example.invalid"
    out = build(document, signature, b"", fake, identity, 0)
    assert json.loads(out)["verified_boot_keys"] == sorted(json.loads(document)["verifiedBootKeys"])
    # A changed byte fails; the real signer doesn't sign the sample; an older timestamp is refused.
    assert not verify(document.replace(b"a", b"b", 1), signature, fake, identity), "tampered"
    assert not verify(document, signature, PINNED_SIGNER, identity), "another signer"
    try:
        build(document, signature, b"", fake, identity, json.loads(document)["timestamp"] + 1)
        raise AssertionError("an older timestamp was taken")
    except SystemExit:
        pass
    # The committed list is the script's form.
    with open(OUT) as f:
        committed = json.load(f)
    assert render(committed["timestamp"], committed["verified_boot_keys"]) == open(OUT).read(), "committed form"
    print("grapheneos-boot-keys selftest ok")


def main():
    if "--selftest" in sys.argv:
        return selftest()
    document, signature = fetch(SOURCE), fetch(SOURCE + ".sig")
    out = build(document, signature, fetch(SIGNERS), PINNED_SIGNER, IDENTITY, committed_stamp())
    if "--write" in sys.argv:
        with open(OUT, "w") as f:
            f.write(out)
        print(f"wrote {OUT}")
    else:
        sys.stdout.write(out)


if __name__ == "__main__":
    main()
