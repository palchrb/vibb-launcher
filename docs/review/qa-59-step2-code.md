# QA (code): design 59 step 2 - the parent PWA's side of Android's lock screen

Branch `design-59-step2`, commit 2285972e (on c148c3b7). Server only. Reviewed 2026-10-22 against
`docs/design/59-android-keyguard-design.md` (§2.2, §2.3, "Step 1 as built", "Step 2 as built"), `qa-59-design.md`
(Q59-11), `qa-59-revision-check.md` (N-C), `qa-59-step1-code.md` and `docs/notes/server/pwa.md` (save/anchor rules).

## Summary

The PIN handling holds. The PIN is shown in one place only: the HTML answer to `POST /devices/{id}/lock`. That
answer is `no-store` and marked `data-post-answer`, it never redirects, and nothing logs the PIN. The GET page never
opens the sealed copy. The switch, Send again and the card's notes match §2.3, and every one of them has a test.

One real gap is in the new re-auth rule. The session stores the wrong-password count, and each request works from the
copy of the session it loaded at its start. Wrong passwords sent in parallel all count from the same number. A request
that started earlier can also write a session back after the third wrong one deleted it. Two probe tests showed both:
four wrong passwords in a row without being signed out, and a deleted session working again (S2P-1). The other
findings are a suppression that is wider than N-C asked for (S2P-2) and the reveal form posting without a fragment
(S2P-3).

Runs: `cargo test` 1156 passed, `cargo fmt --check` clean. Mutations: 19 run, 17 killed, 2 survived (both explained
in the table below).

Counts: High 0, Medium 1, Low 2, Nit 3.

## The deviations ("Step 2 as built")

| # | Deviation | Verdict |
|---|---|---|
| D1 | The switch is the "Lock" row and returns to `#lock-keyguard`, not `#child-pin` | Accepted. The anchor is on the form itself, so `check_forms` passes, and a warning that comes in above it doesn't move it. |
| D2 | `autocomplete="off"` instead of `current-password` | Accepted. Browsers mostly ignore `off` on password fields, so a password manager may still fill it. The field never holds the PIN and has no `value`. |
| D3 | The re-auth helper also covers Account's password form: four forms share one session count | Accepted: the same password oracle. The count itself can be raced (S2P-1). |
| D4 | "Send the Child PIN again" is offered whenever the phone doesn't run the current PIN and nothing is on its way, not only after a refusal | Accepted. It is the only retry the step-1 D1 rule leaves. `send_kid_pin` runs the queue rule with no report, so a stale page or a double tap can add a row while one is in flight. The pop retires the older one, so this is harmless. See also S2P-4. |
| D5 | The command row shows only while Android's lock is chosen; Activity calls it "Child PIN to the phone" and words it with `PinAnswer` | Accepted. `LOCATE_KINDS` is unchanged, and `reword` only adds the six answers. |
| D6 | `lock_off` reads `lock_card`'s notes (the N-C pair raises no alarm) | Accepted as a structure: the page and the alarm can't disagree. The suppression itself is wider than N-C (S2P-2). |
| D7 | The summary's value follows the parent's choice; the Devices list has none | Accepted. |
| D8 | "· unlocked HH:MM" on a restart as well as on a turn-on | Accepted. The value must be more than 2 minutes after the start (strictly) and no more than 5 minutes in the server's future. M16 is killed. |
| D9 | Locate's ⓘ says "Erase" | Accepted (the page's word). The note shows only with Android's lock chosen and a Child PIN set. |

## Checks that held

- **The PIN only in the reveal's answer.** `lock_action` (`device_pages.rs:911`) renders the page. `no_store_path`
  covers `["devices", _, "lock"]` for both the GET and the POST. `mark_post_answer` adds `data-post-answer`, so
  `reconnect.js` never reloads the answer. The service worker has no fetch handler (`templates/sw.js:169`).
  `scroll-restore.js` stores scroll positions only. `RevealView` and `Reveal` derive no `Debug`, and `KidPin`'s
  `Debug` is redacted.
- **No PIN or password in any log.** The diff adds 3 `tracing::error!` calls (`device_id`, `err`) and 5
  security-event writers (the username and "device N", or the fixed sentence of `reauth_signed_out`). There is no
  request-body tracing layer in `main.rs`.
- **No PIN in an address.** The only redirect is `SignedOut` → `/login`. Lock Screen & PINs' auto-reload is not
  loaded.
- **CSRF.** The three new POSTs sit behind the admin listener's global `csrf_guard` (Sec-Fetch-Site/Origin), which
  has no exemption for them, like every other admin form.
- **Re-auth semantics.** A right password from any of the four forms resets the shared count. That gives nothing to
  someone who doesn't know the password. A new session needs a full sign-in (password + 2FA, with the login lockout),
  so a fresh cookie is no bypass. The `_ => signed_out_response()` arms in `change_password` and
  `update_account_password` catch only `(Right, None)` and `Busy`, which can't happen there. They fail closed (an
  answer of `/login`, no flush), which is fine.
- **`update_kid_keyguard`.** The server checks for a Child PIN twice: a read first, then the `UPDATE`'s `WHERE
  kid_pin_hash IS NOT NULL`, so a parallel removal wins (M9 survives as an equivalent; removing both checks, M9b, is
  killed). The queue rule runs inside the same transaction, and the switch back cancels a waiting row (M11).
- **`lock_card`.** `NeedsUpdate` still returns early before Android's notes. `AndroidNeedsUpdate` comes before
  `Waiting`. The N-C pair gives `SwitchingBack { stuck: true }` and no `Off`/alarm (M12, M13). `pin_send`'s
  states line up with `CommandStatus`, and a cancelled or expired row says nothing.

## Findings

**S2P-1 (Medium): "three wrong in a row" is counted on each request's copy of the session, so parallel requests get
past it and a deleted session can come back.**
- Evidence: `record_reauth` (`admin_auth.rs:180-212`) reads and writes `reauth_wrong` through `tower_sessions`. The
  record is loaded once per request by the auth middleware (`security.rs:341`), before the body is read, and stored
  when the response is sent. `SqliteStore::save` is an upsert (`insert ... on conflict(id) do update`).
- Probe 1 (a temporary test, reverted): `tokio::join!` sends three wrong reveals, which all get 400 and all write
  count 1. A fourth wrong one also gets 400 ("one more"), and the GET is still 200. That is four wrong passwords with
  no sign-out.
- Probe 2: a POST whose body is held back passes the middleware with count 0. Three sequential wrong reveals follow,
  and the third ends the session (`/login`; the GET also goes to `/login`). Then the held body is released. That
  request answers 400 and saves its copy of the session, and the old cookie's GET is 200 again.
- Scenario: someone has the parent's session cookie but not the password. They hold several requests open (or keep a
  pipeline of up to the gate's 5) and get as many guesses as they like, limited only by Argon2's speed. They can then
  Show Child PIN, change the password or reset 2FA. Q59-11 asked for exactly this limit.
- Fix:
  - Keep the count in the database, keyed by the account, and change it in one statement: `UPDATE admin_users SET
    reauth_wrong = reauth_wrong + 1 WHERE id = ? RETURNING reauth_wrong` (migration), and reset it to 0 on a right
    password.
  - At the third wrong one, bump the session epoch (`bump_epoch`) as well as flushing. A session written back later
    still carries the old epoch, so the middleware refuses it. This signs out the parent's other sessions too, which
    is right after three wrong passwords; reword `reauth_signed_out` to match.
  - This also makes "a session that can't store the count is ended" real. Today `insert` only edits the in-memory
    record, and a failure to save at the end of the response never reaches this code.
  - Keep both probes as regression tests: a `join!` of three, and the held body.

**S2P-2 (Low): the N-C suppression also hides the alarm when Android's lock is chosen but the phone still runs Vibb
One's lock with an Android PIN it didn't set.**
- Evidence: `kid_lock.rs:926` `!(credential && (android || runs_android))`. N-C needed only the `runs_android` half.
  The test pins the wider form: the case `the_card_words…` "...but not with Android's lock chosen" expects
  `[AndroidWaiting { sending: false }]`.
- Scenario: Android's lock is chosen. The phone reports `keyguard: own`, `active: false`, `inactive:
  android_credential` (an Android PIN set outside Vibb One). The page says "Android's lock · Waiting - it starts the
  next time the Child PIN is typed", but Vibb One's lock is off, so nobody types it there. The summary shows no "Lock
  is off" alarm. In own mode the same report raises that alarm.
- Today's step-3 launcher treats a chosen Android lock with a credential as the gate (`lockActivation`'s
  `androidGate`), so this pair may never be sent once step 4 fills the report. The rule is still wider than its
  reason.
- Fix: suppress only when `runs_android` (narrow §2.3's line "only while own is requested" to match). Alternatively,
  with Android's lock chosen and not yet running, word the pair as "Android's PIN on the phone isn't the Child PIN"
  and keep the alarm. Decide in step 4 if the launcher can't send it.

**S2P-3 (Low): Show Child PIN posts to `/devices/{id}/lock` with no fragment, and `check_forms` now lets any form do
that.**
- Evidence: `device_lock.html:67`; `settings_pages.rs:116`, `if action == page { continue; }`. That `continue` also
  skips the `<noscript>` Save check for an auto-saving form.
- Scenario: without JS, or when an installed iPhone app loses the scroll record, the answer lands at the top of the
  page and not on the form. The 2026-10-14 rule says every form that stays on its page posts to its own group's
  `#fragment`. No other form uses the exception today, but nothing stops the next one.
- Fix: post to `/devices/{id}/lock#show-pin`. The fragment never reaches the server, so the PIN is still in no address
  or log. With JS, `scroll-restore.js` keeps the place, because `#show-pin` is one of the form's ancestors. Then drop
  the `action == page` exception, and add the reveal row to `lock_forms_return_to_the_page` with `Some("show-pin")`.

**S2P-4 (Nit): Send again reads an older install's report.** `offers_send_again` takes `android_capable` and `state`
from `load_latest`, which isn't filtered by enrollment. The queue SQL counts only reports since `enrolled_at`
(step-1 S59-3). After a re-enrollment, until the first report, the button can show and then answer `pin_not_sent`.
This is acceptable because the flash says why. A real fix would be to filter `load_latest` for this page by
`ENROLLED_AT`.

**S2P-5 (Nit): the shown PIN stays on screen.** A post answer is never reloaded, so a PWA left open shows the digits
until someone leaves the page. Collapse `#show-pin` (or remove the digits) on `pagehide`/`visibilitychange`, or after a
minute.

**S2P-6 (Nit): reloading the reveal re-posts the password.** The browser asks "resubmit?" and then counts the result.
This is inherent to answering on the POST, which is the design's choice. Note it in pwa.md next to the rule.

## Mutations

| # | Mutation | Test | Result |
|---|---|---|---|
| M1 | `no_store_path` without `["devices", _, "lock"]` | `admin_auth::tests::no_store_paths`, `android_lock_page::show_child_pin_needs_the_password` | killed |
| M2 | the PIN as `kid_pin_shown`'s detail | `show_child_pin_needs_the_password` | killed |
| M3 | a right password doesn't reset the count | same | killed |
| M4 | Account's password form doesn't count | `the_password_forms_share_one_count` | killed |
| M5 | `reauth` accepts any password | `show_child_pin_needs_the_password` | killed |
| M6 | the fourth wrong one ends the session (`<=`) | same | killed |
| M7 | the GET page renders `shown_pin` | same | killed |
| M8 | `lock_action` takes any `action` | same | killed |
| M9 | the switch without its read-first check | `the_switch_saves_queues_and_cancels` | survived, equivalent (the `UPDATE`'s guard) |
| M9b | without the read-first check and the `UPDATE` guard | same | killed |
| M10 | the switch to android doesn't queue | same | killed |
| M10b | queue on the pool after the commit (not in the transaction) | same, `send_again_queues_the_pin_when_it_can` | survived: atomicity can't be seen in a sequential test; the code reads right |
| M11 | the switch back doesn't cancel the waiting row | `the_switch_saves_queues_and_cancels` | killed |
| M12 | the N-C suppression only when chosen (`credential && android`) | `kid_lock::tests::the_card_words_android_lock_case_by_case`, `the_page_words_the_phone_against_the_choice` | killed |
| M13 | `SwitchingBack.stuck` without `credential` | `the_card_words_android_lock_case_by_case` | killed |
| M14 | Send again offered while in flight | `send_again_is_offered_only_when_it_can_help` | killed |
| M15 | the earlier PIN shown for any rev (no `prev_rev` filter) | `the_shown_pin_names_what_the_phone_has`, `show_child_pin_names_the_one_the_phone_still_has` | killed |
| M16 | "unlocked" at exactly 2 minutes (`>=`) | `parent_events::tests::the_power_words` | killed |
| M17 | no "Phone's PIN isn't the Child PIN" Attention row | `send_again_queues_the_pin_when_it_can` | killed |

Not mutated, by reasoning: logging the PIN through `tracing` can't be caught by a test. The diff was checked by grep
instead (see Checks that held).
