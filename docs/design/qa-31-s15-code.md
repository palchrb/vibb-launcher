# QA 31 step 15 (code) - Add Phone, sign-in, the brand bar

Reviewer: code QA, 2026-10-14. Branch `design-31-s15`, `83294eda..2856df48` (one commit, 29 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.4, §1.11, §3.0, §3.7, §4, §5 (§5.1, §5.2, §5.3, §5.5, §5.6), §8 step 15, §9;
- step 14's status and fix-round table, and "Step 15 implementation status" with its eight choices and the deferrals;
- `server/CLAUDE.md` (design 22: enrollment codes, admin auth, onboarding, CSRF, headers), `server/DEPLOY.md`
  (enrolling, the provisioning QR, the checksum);
- `qa-31-s14-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/provisioning.rs` in full, `handlers/devices.rs` (`create_device`, `insert_device_with_policy`,
  `regenerate_code`), `handlers/auth.rs` (the diff is the four `title` strings only), `admin_auth.rs`
  (`no_store_path`, `admin_headers`), `handlers/settings.rs` (Phone Setup's limits);
- all changed templates, `partials/app_header.html` / `tab_bar.html`, `device_advanced.html` (the docs now point
  there), the launcher's `strings.xml` (the quoted labels);
- every new or repointed test (`provisioning.rs`, `admin_auth.rs`, `shell.rs`, `enrollment.rs`, `devices_list.rs`).

Line numbers are `2856df48`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a short fix round.** The flow is what §3.7 and §8 step 15 ask for, and the parts that matter
most held up:
- **Auth strength is unchanged.** `handlers::auth` changes only four `title` strings; `admin_auth`, `enrollment`, the
  routes and `main.rs` are untouched. The four sign-in templates keep their field names, `action`s, `autocomplete`
  values (`username`, `current-password`, `new-password` ×2, `one-time-code` ×2), `minlength="12"`, `pattern`, and
  carry no `value` on any secret field; there's no hidden field. The TOTP secret renders once, on the setup page only,
  now inside "Can't scan it?" (pinned: `matches(secret).count() == 1`). `/auth/*`, `/devices/{id}/provision` and
  `/devices/{id}/regenerate-code` stay `no-store` (the path rule, plus the handler's own header on the code page);
  nothing new reaches a URL or a log - the new `tracing::error!`s carry the device id and the error, never a code,
  password or SSID. A refusal is one `role="alert"` line (choice 6, reasonable: the handlers return one message per
  form, and splitting it would touch `handlers::auth`).
- **No panic left in the QR path.** The `expect("… always fits a QR code")` is gone (a 500 with a reason), the
  `render().unwrap()`s are 500s, and the only `expect` left (`serde_json::to_value` of a struct of strings) can't
  fail. The Wi-Fi limits bound what a form can add, so only a huge server address or key can overflow (L1).
- **Refusals in place, nothing echoed.** The name and Wi-Fi refusals render their page (400) with the reason under the
  field, `aria-invalid`, `autofocus`; the name and SSID come back escaped, the password never ("Type the password
  again." when the other field was wrong). Both pinned (mutations 1, 5, 12).
- **Save never jumps to the top.** `/devices/new` answers on its own path (short page, `scroll-restore.js` restores
  it); the QR form posts to `/provision#qr`, so its answer - the QR, or a refusal - lands on the group with or without
  JS, and `scroll-restore.js` defers to the fragment; New Setup Code opens its own page. The table test is built from
  the rendered forms (mutation 9).
- **The brand bar.** The five landing pages include `tab_bar.html` alone and open on their large title (the "+" on
  Devices and Apps, Edit on Music, the title first on Settings and Locate); every inner page keeps its back link;
  only `device_detail.html` keeps `app_header.html`, step 10's page. The Devices page loses nothing but the brand
  line: `live_warning` (the sign-in warning rows) and the empty-state rows are still there. `shell.rs`' rules are
  right and pinned (mutation 11).
- **Robustness:** Connect's reads are 500s on a DB error (it used to build a QR from empty defaults when Phone Setup
  couldn't be read - pinned with the renamed table, no code made), the code page's read too.
- **Choice 7 (a reload of the QR answer makes a new code)** is the right trade-off here - see N1 for why PRG isn't.

What needs fixing:
- **L1:** a payload that overflows the QR code is found *after* the new code was stored: the 500 has already
  replaced the phone's live code.
- **L2:** a 64-hex-digit Wi-Fi key (a raw WPA PSK, which Android's provisioning takes) is refused.
- **L3:** the 40-character and 32-byte boundaries, "no code without a checksum", the overflow path and "Type the
  password again." are pinned by nothing (mutations 2, 3, 6, 13, 15).

Counts: 0 High, 0 Medium, 3 Low, 4 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-15`), under `~/build.lock`.
- **`cargo test`, unchanged:** 844 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** 13 warnings, the same as on main; none is in the step's code.
- **`node --test server/jstest/*.test.js`:** 25 passed.
- **The mutations.** Each was run against the named filter, then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | the name not trimmed (`form.name.as_str()`) | `provisioning::add_phone_refuses_a_bad_name_in_place` |
  | 2 | the name limit `>=` (a 40-character name refused) | **nothing** (L3) |
  | 3 | the SSID limit `>=` (a 32-byte name refused) | **nothing** (L3) |
  | 4 | the password limit 64 | `provisioning::a_wifi_field_that_cant_be_right_is_refused_before_any_code` |
  | 5 | a refusal types the password back (into the SSID's `value`) | `provisioning::a_wifi_field_that_cant_be_right_is_refused_before_any_code` |
  | 6 | the "no checksum → no code" return removed (`&& false`) | **nothing** (L3) |
  | 7 | `["devices", _, "provision"]` out of `no_store_path` | `admin_auth::tests::no_store_paths`, `admin_responses_carry_the_security_headers_and_cache_rules`, `provisioning::every_form_in_the_add_flow_lands_where_it_should` |
  | 8 | Connect's GET makes a typed code | `enrollment::the_setup_qr_carries_a_qr_code` |
  | 9 | the QR form posts without `#qr` | `provisioning::every_form_in_the_add_flow_lands_where_it_should`, `server_setup_needed_is_one_row_with_its_detail_behind_info` |
  | 10 | the login refusal includes the typed password | `admin_auth::the_sign_in_pages_use_the_new_components` |
  | 11 | `settings_hub.html` includes `app_header.html` again | `shell::every_template_with_the_bar_sets_its_tab`, `shell::the_tab_pages_open_on_their_large_title` |
  | 12 | a password without a network name accepted (`if false`) | `provisioning::a_wifi_field_that_cant_be_right_is_refused_before_any_code` |
  | 13 | the overflow `else` replaced by `.expect("fits")` | **nothing** (L3) |
  | 14 | `["devices", _, "regenerate-code"]` out of `no_store_path` | **nothing** - the handler sets `no-store` itself, so the page stays covered; the path rule's unit test just doesn't list it (L3) |
  | 15 | `password_dropped` never set ("Type the password again." gone) | **nothing** (L3) |

  Six of fifteen survived (2, 3, 6, 13, 14, 15); 14 is harmless (double cover).

## Low

### L1. A payload too long for a QR code is found after the code was stored: the 500 has replaced the live code
`src/handlers/provisioning.rs:265-303`.

`provision_qr` calls `enrollment::new_code(…, Kind::Qr)` (which replaces the phone's live code, typed or QR) and only
then builds the payload and tries `QrCode::new`. When that overflows, the answer is a 500 "The setup details are too
long …" - but a code the parent made a minute ago (a setup code being typed on the phone, or a QR already scanned and
still provisioning) has just stopped working, and the page says nothing was made. It is reachable: Phone Setup puts
no length limit on the server address or the Tailscale key (`handlers/settings.rs`), and the QR's binary capacity is
about 2.9 kB.

**Fix:** check the fit before the code. The code's length is fixed (26 characters for `Kind::Qr`), so build the
payload once with a placeholder of that length, `QrCode::new` it, and refuse (500, or better a 400 on Connect with
"Server setup needed"-style text) before `new_code`; then build the real one. A test: a 3 kB `server_url`, POST →
the refusal, `code_hash` unchanged (which also covers mutation 13's survivor).

### L2. A 64-hex-digit Wi-Fi key is refused, though Android's provisioning takes it
`src/handlers/provisioning.rs:95, 214-219`.

`MAX_WIFI_PASSWORD_CHARS = 63` is the WPA *passphrase* limit, but a WPA-PSK network can also be given as its raw
64-hex-digit key, and Android's ManagedProvisioning (`WifiConfigurationProvider`) passes a 64-character all-hex
`PROVISIONING_WIFI_PASSWORD` through unquoted as the PSK. Some routers print exactly that on their label. The refusal
then says "A Wi-Fi password has at most 63 characters" for a key that would have worked.

**Fix:** accept `len == 64 && all ascii hexdigit` too, with a test row. Optional, in the same place: a WPA password
under 8 characters can never connect (the payload always says `"WPA"`), so refusing 1-7 characters ("at least 8
characters") would catch a typo before the parent stands at the phone; leave it out if WEP networks are meant to be
supported (they aren't today - the security type is hard-coded).

### L3. The boundaries, "no code without a checksum" and the password-retype line are pinned by nothing; the overflow path has no test
`src/tests/provisioning.rs:407-508`, `:36-49`.

- The name test sends 41 characters and the Wi-Fi test 33 bytes, never the largest accepted value, so `>` → `>=`
  survives for both (mutations 2, 3). Add a 40-character name (303 to Connect) and a 32-byte SSID with a 63-character
  password (200, QR shown) - the SSID one with a multi-byte name (16 × "ø" = 32 bytes) also pins that the limit is
  bytes, not characters.
- `provision_page_without_checksum_has_no_qr` checks the page but not the database, so removing the "No code at all
  without a checksum" return (`:260`) survives (mutation 6): the POST then **replaces the live code** and shows no QR.
  Assert `code_hash` is unchanged there (a `create_device` gives the phone a code to compare).
- The overflow → 500 path (the reason this step exists in that function) is never reached by a test (mutation 13);
  L1's test covers it.
- "Type the password again." (`password_dropped`) is never asserted (mutation 15): the SSID-refusal rows of
  `a_wifi_field_that_cant_be_right_is_refused_before_any_code` should check that it shows, and that it doesn't when no
  password was sent.
- While there: add `/devices/3/regenerate-code` to `admin_auth::tests::no_store_paths` (mutation 14 survives only
  because the handler sets the header too).

## Nits

### N1. Choice 7 is right, but say why: PRG would store what this page promises not to
Choice 7, `provision_qr.html:966`.

A reload of the QR answer re-sends the POST and makes a new code. The case that matters: the kid's phone has scanned
the QR and is still provisioning (it downloads the launcher before it enrolls, minutes), and the parent's browser
re-POSTs - the scanned code dies and the phone ends up provisioned but not enrolled (recoverable with New Setup Code).
But the browser asks "Confirm form resubmission?" first (the page is `no-store`, so no silent replay from cache), the
new QR replaces the old one on the same screen, and the alternative is worse: PRG would need the code - and the Wi-Fi
password inside the QR - kept server-side between the POST and the GET (the session store), breaking "only the hash
is stored" (design 22) and "never stored" (the field's own hint). Keep it; add one sentence to choice 7 with this
reasoning, and optionally a footer line on the QR answer ("Keep this page open until the phone has connected; a new
QR code replaces this one" is what the ⓘ already half says).

### N2. "A network name has at most 32 characters" for a 32-byte limit
`src/handlers/provisioning.rs:208-212, 253`.

The check is bytes (right, IEEE 802.11), but the message says characters: "Familien Ødegård-5G" style names hit the
limit earlier than the text says. Say "32 letters (fewer with æ, ø, å)" or just "is too long for a Wi-Fi network".
And a too-long name isn't typed back, so the parent retypes from scratch; it isn't a secret - echoing it (escaped, as
the device name is) would be kinder.

### N3. The Connect POST loads `Zone` twice; the "until" formatting is written out twice
`src/handlers/provisioning.rs:127, 310`; `src/handlers/devices.rs:445-452`.

`ProvisionQrTemplate::new` loads the zone and `now`; `provision_qr` loads them again for `until`. Keep `zone`/`now`
from `new` (return them, or store the zone on the template builder). `parse_db_utc(..).map(|t| zone.until(t, now))
.unwrap_or_else(|| format!("{x} UTC"))` appears in both handlers; a `Zone::until_db(&str, now)` would be one line
each.

### N4. The status describes the code page's footer as it isn't
"Step 15 implementation status", item 3 and choice 5; `enroll_code.html:809`.

The status says the footer is "Type it in Vibb's settings on Anna." and that the launcher's labels are quoted only
inside ⓘ panels; the template's footer is "On Anna: Vibb's Settings › Enroll with code." (the right label, it matches
`strings.xml`). Fix the status text, not the page.
