# Test certificates for the Sonos event socket's trust (design 53, round 53-c)

`SonosTrustTest` checks `speakerCertRefusal` (`sonos/SonosSocket.kt`) with these: a chain to the pinned root whose
leaf's SAN holds `urn:sonos:udn:<the uid we dial>` is accepted, everything else refused. They are **throwaway test
certificates**, not Sonos': a self-made "Vibb Test Root" (and "Vibb Other Test Root", "Vibb Test Intermediate") over
EC P-256 keys that were deleted right after signing (`leaf_good`'s too, which signed `leaf_under_leaf`) - no private key is in the repo, and none is needed (the tests
call the trust manager directly, no TLS handshake). The SAN mimics a speaker's shape with obviously fake values
(`RINCON_00000000000001400`, `sonos-000000000000.local`, `urn:sonos:hhid:Sonos_TEST`).

| File | What it is |
|---|---|
| `test_root.pem` | the test's pinned root (CA, 2020-2120) |
| `other_root.pem` | another root, never pinned |
| `test_intermediate.pem` | a CA under `test_root` (pathlen 0) |
| `leaf_good.pem` | under `test_root`, udn `RINCON_00000000000001400` |
| `leaf_via_intermediate.pem` | under `test_intermediate`, the same udn |
| `leaf_wrong_udn.pem` | under `test_root`, udn `RINCON_00000000000001401` |
| `leaf_no_san.pem` | under `test_root`, no SAN |
| `leaf_dns_only.pem` | under `test_root`, a SAN with a DNS name only |
| `leaf_expired.pem` | under `test_root`, the right udn, expired 2021-01-01 |
| `leaf_other_root.pem` | under `other_root`, the right udn |
| `leaf_self_signed.pem` | self-signed, the right udn |
| `leaf_two_udns.pem` | under `test_root`, two udns: `...1401` first, ours second |
| `leaf_not_yet_valid.pem` | under `test_root`, the right udn, valid from 2100 |
| `leaf_under_leaf.pem` | the right udn, signed with `leaf_good`'s key (a `CA:FALSE` leaf as an issuer) |

Made with `make-certs.sh` (OpenSSL 3.5, which has `-not_before`/`-not_after`):
`sh make-certs.sh <out dir> <throwaway key dir>`, then delete the key dir. Regenerating gives new keys and serials;
the tests only depend on the names, the udns, the issuers and the dates above.

The real pinned root (the Sonos Device Authentication Root CA) is not here: it is `SONOS_ROOT_PEM` in
`SonosSocket.kt`, and `SonosTrustTest` checks its SHA-256 against the fingerprint the user's speaker's chain ended in.
