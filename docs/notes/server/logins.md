# Server notes: Wi-Fi sign-in and Chrome only for logins (design 102)

The design is `docs/design/102-captive-portals-and-logins.md` (revision 5 on top), its QA `docs/review/qa-102-design.md`.
The launcher's side is `docs/notes/launcher/logins.md`.

- **Migration 0150** (`0150_login_windows.sql`; design 100 holds 0149 on its branch): `device_policy`'s
  `captive_portal_enabled` (1, D1), `captive_portal_grant` (0, D14), `captive_portal_require_code` (1, revision 5),
  `login_browser_enabled` (0, D10) and `login_browser_presets` (`'[]'`, a JSON set of `logins::PRESETS` keys, Q102-M8);
  the parent's own sites in **`login_role_sites`** (`(device_id, entry)`, a note of at most 60 characters) - not the
  design's `login_browser_sites`: `tests::cleanup` asserts that no table named like `%browser%` is left from the removed
  browser history; `device_status.captive_portal_json`/`login_browser_json`; Notify Me About's `push_wifi_signin` (on).
- **`logins.rs`**: the policy's `captive_portal` (`portal_policy`: the switch, `require_code`, each opening's 10
  minutes, the `grant` counter; always sent - no daily count since 2026-11-18, the user) and `login_browser` (`browser_policy`: `{package: "com.android.chrome", hosts}` - the
  ticked presets expanded at build and the own entries, sorted, each once - or `null` while off or with Chrome ticked
  under Apps, so the two stay exclusive; a DB error is a 500); `valid_entry` (a host with an optional leading dot, port
  and path, a pasted http(s) URL keeping its host and path; no other scheme, `*`, query without a pasted URL, space or
  non-ASCII; at most 100 own entries); `clean_portal`/`clean_browser` (field by field: known words, plausible counts and
  instants, hosts cleaned and cut to 20 visited and 10 blocked hosts and frames, the last 10 sessions; a session without
  a known outcome, start, approval or `opened_ms` is dropped); the events.
- **The events** (`logins::intake`, from the stored report): `wifi_signin_wide` for a session
  that visited more than 3 sites, had a blocked frame (never a blocked sub-resource alone: portal pages load blocked
  analytics, R2-L3) or timed out, keyed by its `opened_ms` (every report repeats the last 10). With the parent code off
  (`approved: "none"`, the user 2026-11-18) a timeout alone raises nothing, and the rest push at most once an hour a
  phone (`PushBudget` 1/1 under the key `logins::push_key`, no digest; the inbox line always lands). `wifi_signin_budget` (the
  day's code-free openings used up) is no longer raised since the daily count went (2026-11-18); its kind stays so the
  stored rows are still told, and an older launcher's `today` is dropped by `clean_portal`. Told from parameters (`WifiSigninParams`), to `/devices/{id}/logins#logins-wifi`, pushed at once in the day
  and held until 07:00 between 23:00 and 07:00 in the family's zone (`logins::night_push`), one switch "Wi-Fi sign-in".
- **The parent code's entry**: `parent_pin::Entry::WifiSignin` (`wifi_signin`), its security-log words ("Wi-Fi sign-in
  opened with the parent code", "at the Wi-Fi sign-in") and the PIN event's place ("Wi-Fi-innlogging").
- **Device › Sign-in** (`/devices/{id}/logins`, `handlers::logins`, `device_logins.html`; linked from the Web Filter
  page's row "Innlogging · Wi-Fi: på · Apper: av"): `#logins-wifi` - the switch with its sub-line (the window, the
  close, the filter's lists not the parent's own DNS server), the code's state linking to Advanced, "Gi én
  innlogging til nå" (`section=grant`, bumps the counter, uncapped; shown only with Foreldrekode on, where a grant
  matters; the (i) says it lasts the day and needs the phone online);
  `#logins-recent` - the last sign-ins with how each was approved, the sites visited and the blocked hosts;
  `#logins-apps` - "Innlogging i apper med Chrome" (`section=apps`; disabled with the reason while Chrome is ticked, a
  forged on refused), the clear's cost under it (D13), "Chrome finnes ikke på telefonen", the phone's state (Klar,
  Venter på telefonen, Krever nyere Vibb One, Chrome mangler, Kunne ikke settes); `#logins-sites` - the presets
  (`section=preset`), the own sites with a note and Remove (`section=site`, `action=add|remove`; a refused entry back with
  its reason), "Sperret av nettfilteret" for a listed site the phone's filter blocks (`device_api::filter_blocks`, the
  delivered lists walked by suffix), and the note about other sites and web-page apps. Every save is a `303` back to its
  group's anchor (no jump; `scroll-restore.js`), server-rendered, no new client JS (Safari 14).
- **Advanced › `#wifi-signin`**: "Krev foreldrekode for Wi-Fi-innlogging" (on by default), "Gjelder også å åpne
  påloggingssiden igjen", the warning "Ingen foreldrekode er satt …" while the phone reports none set; its form posts `section=wifi_signin` to the Sign-in page's
  handler, answered with a `303` to `/devices/{id}/advanced#wifi-signin`.
- **The Apps page**: Chrome's row says "Brukes bare til innlogging (Innlogging-siden)" while the role is on.
- **The report**: `captive_portal` and `login_browser` are full-report fields (`status_heartbeat::REPORT_KEYS`, not
  heartbeat fields), cleaned into their columns; `intake_limits` keeps the contract's keys.
- **The contract**: `testdata/login_windows.json` (root `CLAUDE.md`); `tests::logins` (the policy rows from the page's
  saves, the reports, `clean`, `entries`, the pages and their anchors, the Apps page's line, the events, the PIN entry);
  `policy_json_keys_snapshot` pins `captive_portal` (non-null) and `login_browser` (nullable).
