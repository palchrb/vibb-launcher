# QA 34 step 12 (code): the launcher is "Vibb One" (P11, migration 0077)

Reviewed: `design-34-s12` head `7c76557c` on main `c1570a95` (`git diff c1570a95 7c76557c`, 50 files), against
design 34 §P11, §5 step 12, §7 L3 and the "Step 12 implementation status".

## Summary and verdict

**0 High, 0 Medium, 2 Low, 2 Nits. Ready to merge**; the Lows are test gaps, not wrong behaviour.

The rename is consistent. Every user-visible text that means the launcher now says "Vibb One": the label (and
"Vibb One (debug)"), the anchor's notification in en and nb, the VPN session, and the PWA (Attention titles, About,
Advanced, calls, kid lock, kiosk escapes, lock glance, Locate, sound mode, music, time rules, provisioning, the
catalog). I grepped `server/src`, `server/templates`, `server/static`, `launcher/app/src/{main,debug}`,
`music/app/src/main`, the READMEs and DEPLOY.md myself:

- "Kids Launcher", "Kids Device MDM" and "Kid Phone Filter" are left only in the 2FA issuer, the migration and its
  test, and docs that describe the rename.
- Every remaining bare "Vibb" means something else: "Vibb style", "Import from Vibb", "(from Vibb)", the Vibb box/Pi,
  the logo, "Vibb Phone", "Vibb Music", the Spotify example "Vibb hjemme", and the music app's Connect/DIDL name.
- The music app has no text that names the launcher.

Nothing functional reads the name:

- **Provisioning** keeps the admin component, `kids-launcher-mdm.apk`, the QR's URL and checksum (`config.rs`).
- **Catalog matching and updates** go by `is_launcher` and the package. No server code compares a row name.
  `tracked_apps.rs:1422` is only a comment.
- **The music bridge** names the launcher by package.

The migration is right. It touches only `is_launcher = 1` rows, and only the two default names (an exact,
case-sensitive match). The test runs on a database migrated to 76 and covers 6 cases. `migrations_73_to_77_in_order`
pins the order.

**The 2FA issuer**: I agree with keeping "Kids Device MDM". The issuer is only the label of a new authenticator
entry and plays no part in verification. Renaming it mid-life gives a parent who re-enrols an entry that looks
different from the old one. If it is ever renamed, the new name should be "Vibb Phone" (the PWA the parent logs into),
not "Vibb One", and it should go with a 2FA reset.

Text fit: the Attention titles that grew are 6 to 7 words, inside the 8-word rule (`attention.rs:777` asserts it).
nb `notification_listener_title` "Vibb One" is right as a proper name. The tests that quote a text were updated to
the new texts, not loosened.

Runs (own `CARGO_TARGET_DIR`, under `~/build.lock`):

| Check | Result |
|---|---|
| `cargo fmt --check` | ok |
| `cargo clippy --all-targets` | ok; 13 warnings, all in files this step didn't touch |
| `cargo test` | 956 passed |
| `node --test jstest/*.test.js` | 30 passed |
| launcher `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` | BUILD SUCCESSFUL |

## Findings

### L1. Two renamed PWA texts are unpinned (mutations S3 and S4 survive)

- `server/templates/provision_qr.html:87` ("You type it in Vibb One's settings on the phone").
- `server/src/handlers/device_pages.rs:948` `clock_format_note`, the Settings path "› Vibb One ›" (it names the
  label, so it must follow it).

Reverting either one to "Vibb" passes all 956 tests. The other rename sites are mostly quoted by tests (Attention,
About, step 11, tracked apps, parent PIN).

**Fix**:

- In `tests/device_pages.rs`, add an assert that the clock warning's fix contains
  `"Modify system settings › Vibb One › Allow"`. That is the one text that must equal the phone's label.
- In the provisioning QR page test, assert that the page contains `"Vibb One's settings"`.
- Optional, as a guard against later regressions: a server test over `templates/` that fails on `Vibb's ` and on
  `Vibb (is|isn't|on|can't|needs)`.

### L2. `VibbOneNameTest` does not scan what its doc says (mutation L1b survives)

`launcher/app/src/test/java/com/kidslauncher/mdm/ui/VibbOneNameTest.kt:49` scans `src/main/res`, `src/debug/res`,
`src/main/java` and the main manifest. `launcher/CLAUDE.md:628` and design 34 line 1065 say the test covers
`src/main`/`src/debug`. In fact, `src/debug/java` (debug receivers and toasts) and `src/debug/AndroidManifest.xml` are
not scanned. `src/main/assets` is not scanned either, but it holds only licenses.

A string `"Kids Launcher [debug]"` added to `src/debug/java/.../LockTaskDebug.kt` passes.

**Fix**: make the roots `src/main/res`, `src/main/java`, `src/main/AndroidManifest.xml`, `src/debug` (the whole
directory).

### N1. The VPN session name is not localized

`launcher/.../server/KidVpnService.kt:44` `VPN_SESSION = "Vibb One filter"` is shown in English in Android's VPN
settings and dialog on an nb phone. The old "Kid Phone Filter" was English too, so this is not a regression.

**Fix (optional)**: use a string resource `vpn_session` (en "Vibb One filter", nb "Vibb One-filter") read with
`getString` in `startVpn`. The test would then read the resource.

### N2. Two security-log details still say "the launcher"

- `server/src/parent_pin.rs:412`: "The launcher's Settings opened.", while the event row on the device page now says
  "Vibb One's Settings opened".
- `server/src/boot_witness.rs:113`: "start(s) without the launcher running", next to the renamed label "Phone started
  without Vibb One".

Neither is wrong, since they are generic. They are the only parent-visible texts left that name the launcher
generically.

**Fix (optional)**: "Vibb One's Settings opened." and "start(s) without Vibb One running". Update the two tests that
quote them (`parent_pin.rs:818`, `boot_witness.rs:201`).

## Mutations

| # | Mutation | Result |
|---|---|---|
| S1 | 0077 renames every launcher row (`WHERE is_launcher = 1`, so a parent's own name is renamed too) | caught (`the_launcher_row_becomes_vibb_one_only_from_a_default_name`) |
| S2 | 0077 renumbered to 0079 (the order test no longer finds 77) | caught (`migrations_73_to_77_in_order`) |
| S3 | `provision_qr.html` back to "Vibb's settings" | **survives** (L1) |
| S4 | `clock_format_note`'s path back to "› Vibb ›" | **survives** (L1) |
| L1 | "Kids Launcher" in an nb launcher string | caught (`VibbOneNameTest`, the old-name scan) |
| L1b | "Kids Launcher [debug]" in a `src/debug/java` string | **survives** (L2) |

An earlier attempt at L1b, a manifest `android:description` literal, broke the resource build. That means it was
caught by aapt, not by the test, so it is not counted.

## Counts

0 High, 0 Medium, 2 Low (L1, L2), 2 Nits (N1, N2). Mutations: 6, of which 3 caught and 3 survived (S3, S4, L1b).
