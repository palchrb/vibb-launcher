# QA 62 - revision check (`docs/design/62-parent-accounts.md`, 348 lines, vs `qa-62-design.md`)

2026-10-22, against `0ece4ae9`. The note and the QA were committed together, so the note was compared with the QA's
findings directly. Result: all 16 findings resolved, no High or Medium left; 3 new Lows, 3 Nits.

## The findings

- Q62-1 (High) resolved. No `DELETE` anywhere. Withdraw is Remove's label on a not-ready row (§3, §4.2), one soft
  statement and one guard. A mistyped name stays taken (§4.2, `removed_name_stays_taken`). Test
  `withdraw_a_reset_parent_keeps_the_row` (§9).
- Q62-2 resolved. §2 lists the five writes with the exact lines (`auth.rs:444-460`, `:742-759`, `:832-847`, `:510`,
  `:579`; all match the code); the forced change also compares `password_hash`. Three tests in §9.
- Q62-3 resolved. `GET /account/parents/{id}/reset` serves the Account page (§3 table); `reset_answer_reloads_as_get`;
  browser step 6. The Add answer's path `/account/parents` has a GET too.
- Q62-4 resolved. `reset-2fa` restores a removed account, always clears the expiry, logs `parent_restored` (§2); the
  trade-off paragraph and DEPLOY.md say it; tests `cli_reset_restores_a_removed_account`, `cli_reset_clears_the_expiry`.
- Q62-5 resolved. 0088 has `WHERE a.removed_at IS NULL` in both inserts and named columns; the column names match
  0078/0080. "Steps 1-5 ship in one `server-v*`" is in the header and §11.
- Q62-6 resolved. §5's table lists the nine `read_at` and the three `notification_settings` readers; against `grep`
  they are complete (`push.rs:673,904`, `parent_events.rs:852`, `notifications.rs:205,251,259,516,540`; `push.rs:338`,
  `notifications.rs:489,561`), and `Due`/`DueRow` get `admin_id`.
- Q62-7 resolved (message names no one; step 1 test `expired_otp_says_expired`). Q62-8 resolved (`setup_2fa_verify`
  clears the expiry; `expiry_clears_at_2fa_setup`; the row state derives from "ready", §3).
- Q62-9 resolved (`passwords.change` + `record_reauth`, `admin_auth::reauth` for Remove: all exist, `admin_auth.rs:113,
  158,181`; times in SQL). Q62-10 resolved (`new_username`, `autocomplete="off"`, hidden own username).
- Q62-11 resolved (bootstrap trims and refuses, Add lowercases). Q62-12 resolved (race tests call the statements on two
  connections; tests added for Q62-1/2/3/8). Q62-13 resolved (step 6 is gone; PLAN.md:777 holds it).
- Q62-14 resolved (the cap and `<ready>` are in the statements; `verify_2fa` reads `removed_at` only).
- Q62-15 resolved (stale text, `reveal.test.js`, `<h1>`, Cf characters, the OTP sign-in on the waiting row).
  Q62-16 resolved ("Copy it before you leave this page", `.secret-once` CSS hide, browser step 2).
- Trimming resolved: 348 lines against the QA's ~340; §12 gone, §8/§7/§9 cut as asked.

## Contradictions

None in the state machine: waiting (before expiry), expired, signed in (ready), removed. Reset makes any account not
ready; Remove/Withdraw are the same statement; the last-ready guard holds with Withdraw soft (a not-ready target
leaves the actor as the other ready account; self-removal needs another). Reset-vs-Reset and Remove-vs-Remove cannot
both commit, because the loser's `<actor ok>` fails.

## New problems

- Low, N1: `load_active_admin` (`security.rs:340`) filters on the epoch only. §2 adds the expiry there but not
  `removed_at IS NULL`; a removed row is kept out by Remove's epoch bump alone. Fix: add `AND removed_at IS NULL`
  beside the expiry, so no later write path (a restore, a bug) revives a session.
- Low, N2: `username` is `UNIQUE` without NOCASE (`0001_init.sql:3`) and login is an exact match, so an old install with
  "Mamma" lets Add create "mamma" next to it. Fix: Add refuses when `lower(username)` is taken
  (`NOT EXISTS (... WHERE lower(username) = ?)` in the same `INSERT`), message "taken".
- Low, N3: Remove and Reset answer "no row" with one message, "Pappa is the only parent who can sign in", but a
  missing row also means the target was already removed or the actor was signed out, and the guard only fails for
  a self-removal (then "you are"). Fix: say the message follows a re-read (target removed: "already removed"; actor not
  ok: signed out; else the guard's, naming the actor), and run the push/prefs/reads cleanup only when one row changed
  (same transaction).
- Nit, N4: §2 (step 1) says the CLI restore "seeds its reads as Add does" but the reads table is 0088 (step 5), as
  §11 step 5 says. Add "(step 5)" in §2. `security::valid_username` does not exist yet; say "new".
- Nit, N5: a mid-onboarding invitee who has set their own password and passes the expiry sees "This one-time password
  has expired". Say "This sign-up link has expired"-style wording, e.g. "This invitation has expired - ask for a new
  one".
- Nit, N6: `setup_2fa_form`'s write (`auth.rs:510`) could carry `AND totp_secret IS NULL`, so two tabs of one
  onboarding keep the first secret (today the last write wins and the first tab's QR is dead). Optional.

## For the implementer brief

- Step 1 first, with Q62-2's five statements and the three race tests; add N1 and N6 there. N2 and N3 belong to step 2/3.
- Browser pass: the reload after a POST answer relies on `reveal.js`'s `replaceState` turning the entry into a GET;
  the existing PIN reveal uses it, but check step 2 and 6 in Safari and Firefox.
- All file:line references hold at `0ece4ae9`; a few shift when step 1 lands (`auth.rs` lines), so use function names.
