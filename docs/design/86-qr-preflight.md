# 86 - The setup QR's preflight: the server checks the launcher the QR names

Status: design note, 2026-11-12, on `main` at fc954ca7, branch `qr-preflight`. Server only; no launcher change, no
API change, no migration. The user approved it on 2026-11-12 ("Do it!") as part one of "the QR setup made robust".

## Why

The setup QR names three launcher facts from `.env` (`config::ForkConfig`): the download address
(`LAUNCHER_APK_URL`, default `releases/latest/download/kids-launcher-mdm.apk`), the signing certificate's checksum
(`LAUNCHER_SIGNATURE_CHECKSUM`) and the admin component (`LAUNCHER_ADMIN_COMPONENT`). Nothing has ever checked them
against each other. Android downloads the APK during setup and compares its signer with the checksum; any mismatch,
or an address that serves nothing, ends in "Can't set up device" with no reason. And `releases/latest` only serves a
stable release: on 2026-11-12 that is launcher-v0.34.0 of 2026-10-21, while every newer launcher is a prerelease -
a phone set up today would start on a launcher weeks old.

## The decision

1. **The server fetches the APK the QR names** (redirects followed: at most 5, never away from https -
   `tracked_apps::https_redirects`; the catalog's size cap and stall timeout, `tracked_apps::stream_to_file`; 10
   minutes at most in all), into a temp file under `data/` that is deleted as soon as it is read.
2. **It reads the APK** (`apk::read_identity`, which now also reads `android:versionName`) and judges it:
   - not an APK, or no manifest package: **not an APK**;
   - the package isn't `LAUNCHER_ADMIN_COMPONENT`'s: **wrong package**;
   - no v2/v3 signer, or its SHA-256 isn't the checksum: **wrong signer**. The checksum is URL-safe base64 of the
     certificate's SHA-256 (no padding), `apk.rs` gives lower-case hex: both are decoded to the 32 bytes and compared
     (`launcher_check::checksum_matches`), never compared as text;
   - else it **works**, and is **older** when a newer launcher release is known.
   - Nothing came (DNS, refused, an HTTP error, a timeout, a stall, over the cap, not a zip): **unreachable**.
3. **"Newer" is judged by versionCode** (`install_now::launcher_tag_version_code`, both rules): the newest of the
   launcher catalog row's stored release and, read once per check, the `launcher-v*` releases in the row's GitHub
   repo (else `LAUNCHER_APK_URL`'s), **prereleases included** - the point is to say that a newer build exists, not
   to pick one for the phones. The same list gives the QR version's date ("from 21 Oct"). A failed list read only
   loses the date and the comparison with GitHub; the catalog row still counts.
4. **In memory, off the request path** (as `system_update::ServerRelease`): `AppState.launcher_check` keeps one
   result keyed by the address and the checksum. A result stands an hour (a failed fetch: 5 minutes); a page that
   finds it due starts a check in the background and never waits. The check also starts when the server starts.
   "Check again" (`POST /settings/provisioning/launcher-check`) starts one at once and waits up to 10 s, then goes
   back to the row (`#launcher`), so the page never jumps to the top. A restart forgets the result: no migration.
5. **What the parent sees.** Phone Setup gets one group, "Setup QR code", with one row and Check again:
   - checking: "Checking the launcher download…" (the page reloads itself while it checks, `auto-reload.js`);
   - works: "New phones get Vibb One 0.39.0-rc.6" (and the date when known);
   - older: "The setup QR points to Vibb One 0.34.0 from 21 Oct - older than 0.39.0-rc.6", with what to do
     (DEPLOY.md: point `LAUNCHER_APK_URL` at a release's own asset);
   - wrong signer: "The QR's signature checksum doesn't match the launcher at the download address - a phone would
     refuse it"; wrong package: "The download address serves another app (…)"; not an APK / unreachable: "The download
     address can't be reached" / "…doesn't give an app".
   The Connect page shows the same row in the QR group.
6. **Fail closed on the Connect page.** Wrong signer, wrong package, not an APK and unreachable show the warning in
   place of the QR and its form, as the missing checksum does, and the POST makes no code. Older shows the QR with the
   warning. While no check has ended yet ("Checking…") no QR is made either - the check starts at boot, so this is
   seconds on a normal start; the page reloads itself until it is done. Without a checksum nothing is checked (the
   missing-checksum banner already says why).
7. **No host action, no secret.** The check only reads a public URL and the public release list; it never writes
   `.env`. Pointing the QR at a prerelease is the owner's `.env` edit (DEPLOY.md, "Launcher provisioning settings"),
   and the signer check still guards it.

## Not done

- The server does not serve the APK itself (part two of the round, if wanted): the QR still names GitHub.
- The check reads the first signer only, as `apk.rs` does; a rotated key (v3 lineage) is out of scope.

## Tests (CI)

`launcher_check::tests`: the checksum comparison (hex vs URL-safe base64, a wrong one, junk); every verdict from
the fixtures in `testdata/apk/` served by an in-process HTTP server (works, older against a catalog row and a fake
GitHub list with a prerelease, wrong signer, wrong package, v1-only, not an APK, unreachable). `tests::provisioning`:
the Connect page shows the QR for works and older (with the warning) and none for a blocking verdict or while
checking; Phone Setup shows the row; Check again redirects to `#launcher`.
