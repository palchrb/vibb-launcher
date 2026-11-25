# contracts/

Files that two or three builds read, kept once (design 133). The server reads them through
`crate::contracts::contract!("<root>/<file>")` (compiled in) or `contracts::path("<root>/<file>")` (at run time); the
launcher and the music app read them as test resources by their bare names (`getResource("phone_vectors.json")`),
the AIDL and the DoH hosts as main sources.

## Layout

- `core/` - read by the server and the launcher. Flat, the files' own names.
- `integrations/<key>/` - read by the server and the launcher, one integration's (design 110's boundary):
  `integrations/<key>/contract.json`.
- `music/` - everything the music app reads; the server and the launcher may read it too.
- `shipped/` - compiled into a release build, the only root that is: `bridge-aidl/` (both apps' AIDL source set) and
  `launcher-assets/` (the launcher's assets). Everything outside `shipped/` is test data only.

A new contract: a file under the root of its readers, a row below (the server's `contracts::tests` fails on a file
without a row and on a row without a file), and both sides' tests read it in place. No copy goes into a module's own
`src/test/resources/`: the shadow tests (the launcher's `BuildChainTest`, the music app's `ContractFilesTest`) fail on
one.

## Build from the monorepo

The server, the launcher and the music app read `../contracts/` from their own folders, and the server binary compiles
in `shipped/launcher-assets/dns/doh_hosts.txt`. Every build (CI, `scripts/dev-rebuild.sh`, the release workflows) runs
from a whole checkout of the repo; a copy of `server/`, `launcher/` or `music/` alone does not build, and a future
Dockerfile that compiles must use the repo root as its context.

Prose written before 2026-11-25 names `server/testdata/<file>` (and the apps' copies); that is now
`contracts/<root>/<file>`.

## Files

| File | Design | Written by | Read by |
|---|---|---|---|
