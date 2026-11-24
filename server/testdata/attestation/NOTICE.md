# Test data in `testdata/attestation/`

- `chains/` - Android key attestation chains from Google's `github.com/android/keyattestation` (its `testdata/`,
  commit c391708adccdeff0ac12f51e81cebfe5de42a017, read 2026-11-24), Copyright 2024 Google LLC, Apache License 2.0
  (<https://www.apache.org/licenses/LICENSE-2.0>). Copied unchanged; each file is named `<device>-sdk<N>-<name>.pem`
  after its folder there (`invalid-*` from `testdata/invalid/`, `p256-sha384-intermediate.pem` and
  `allow-while-on-body.pem` from the top). Test devices' and preproduction chains only - never a capture of a family's
  phone (an attestation certificate's serial is per device; design 129 §7.2).
- `roots/` - Google's attestation root certificates, as published on developer.android.com ("Verify hardware-backed
  key pairs with key attestation", read 2026-11-24): the RSA key `SERIALNUMBER=f92009e853b6b045` in its four
  certificates (named by expiry) and "Key Attestation CA1" (P-384). The pins in `src/attestation/roots.rs` are computed
  from these by `attestation::tests::the_pins_are_the_roots`.
- `status.json` - a revocation status list in Google's form, written for the tests: one serial of
  `tegu-sdk36-TEE_EC_2026_ROOT.pem` revoked, one of `caiman-sdk36-TEE_EC_RKP.pem` suspended.
- `rows.json` - each chain's expected verdict and reading (`attestation::tests::the_rows`).
- `emulator/` - the `emulator_keys` profile's chains and rows (design 129b §7), written by
  `scripts/emulator-attestation-vectors.py` from throwaway keys derived from fake phrases and made-up values, in the
  shape of the emulator gate's chains; never a capture of an emulator, a phone or Google's backend.
