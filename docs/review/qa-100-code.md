# Code QA: design 100 (one Vibb notification; the request for more time, answered)

Branch `design-100`. Reviewed against `docs/design/100-one-vibb-notification.md` (revision 2 authoritative) and both
rounds of `docs/review/qa-100-design.md` (§15 and §16 map them). Commits reviewed:

- step 1: c9f245e42 and 77a4bdf62;
- the main merge: ce4fa4545;
- step 2: 308a10001;
- step 3: efa11c62c;
- the docs: b2dca5dca.

This is a reading review, with these checks run:

- `cargo check --tests` passes;
- `cargo fmt --check` passes;
- `node --test jstest/` passes (389/0);
- `cmp` of the three changed contract files (`time_request.json`, `lock_now.json`, `rule_pause.json`) between
  `server/testdata/` and `launcher/app/src/test/resources/`: byte-identical.

Not run: `cargo test`, clippy and Gradle (reported green at 2635/0).

**Verdict: no High, no Medium, 11 Low.** Every design-QA fix of §15 and §16 is in the code. I reasoned through each new
and changed server test against the code and found none that should fail in CI. The detail is below.

## Did the design QA's fixes land?

### Launcher

- **Startup**:
  - `startForeground(1002, AnchorNotification.forStart(this))` is the first call (`CommandListenerService.kt:119`);
  - `forStart` never throws, never calls `computePanel` and never reads the Snarveier prefs (Q100-L7). It builds from
    the last key and its channel, else r13's plain notice.
- **One notification id.** `notify(1002…)` exists only at `AnchorNotification.kt:133`. `ShortcutsService` is gone: its
  class, its manifest entry and its subtype. The old id 1011 is cancelled at the anchor's start.
- **Channels.** Every build ensures its channel first (`ensuredBuild`, `AnchorNotification.kt:146-157`; M7).
- **Rate limit and shed updates.**
  - `AnchorPostQueue` (`AnchorView.kt:34-58`) posts the first change at once, then one trailing post per second, built
    from the state at that moment.
  - The shed check compares both the channel and a view key kept in the extras (Q100-L4, R2-L11).
- **Locking.** The `isRunning` check and the `notify` happen under the lock `onDestroy` takes (Q100-L5).
- **Threads.**
  - `showAgain` runs on the poster thread (Q100-L8).
  - `AnchorNotice` posts its listener loop to the main `Handler`, and the anchor check comes after the dispatch
    (Q100-H1, Q100-L3).
  - The ask cache is set from the handed time part before `computePanel`. A throwing `computePanel` gives `Plain`
    (Q100-L6, Q100-M1).
  - Lock order is `AnchorNotice` → `TimeRequests` → queue, with no callback under a lock.
- **The phone's request state machine.**
  - One request stays open until an answer to its own id or the ledger day changes.
  - With an older server (no `request_answers`), r13's rule applies: a newer lift closes it.
  - `Capped` reads the policy's answer; `uploadStep` sends a pending request while it is of today.
  - The 15-second "offline" mark and D12's 30 minutes are instants in `nextBoundary` (R2-L3).
  - D10's gift filter: a budget lift, positive id, no `phone_key` (R2-L2).
  - The answer's name is preferred over the lift's (R2-L10).
  - Reboot (DE prefs), clock set back (`stale` → dropped) and the day's end are all consistent.
- **The time-rule screen.**
  - `lockAsk` matches §8.2, including D4 (no block under a rule).
  - D6's `askButton` in `unlockOpenRow`; `ButtonRole` on the buttons; polite live regions.
  - Every new string is in nb and en, and the `TIME_REQUESTS_A_DAY == 3` pin is there.
- **Icons and channel name.** `ic_stat_gear` has a literal white fill. The five §4 sites use `ic_stat_vibb`.
  `baseline_bug_report_24` has no reader left, and `baseline_settings_24` stays. D7's channel name is in nb and en.
- **The contract on the launcher side.** `time_answer_v1` is in `STATUS_CAPABILITIES`. `TimeRequestsTest` decodes every
  `policy_*` row and runs all 16 `states` rows. `LockNowTest`, `RulePauseTest` and `TimeRequestsTest` compare their
  copies with the server's. `PolicyResponseCompatTest` decodes `by`, `at_ms`, `request_answers` and `answer`.

### Server

- **The answer route** (`handlers/time_answer.rs`):
  - it runs in one `BEGIN IMMEDIATE`: first `standing` (open / answered / asked again / gone / unknown), then the lift
    (`insert_lift`), then the answer row with `ON CONFLICT DO NOTHING`, where a conflict rolls back;
  - the security line and `command_notify` come after the commit;
  - "Nei" says that the kid sees it only when the phone claims `time_answer_v1` (§14.7).
- **`record_lift`** is one `IMMEDIATE` transaction for both callers (`create_lift`, `rules_now`), with the event and
  the nudge after the commit (R2-L6).
- **Which lift answers a request.** `answer_with_lift` answers only a lift that `fits` (R2-M2). `need` decides from the
  fresh report (`report_is_fresh`, connection-aware), else from the ask's own state. The card's minutes pause every
  rule when a rule locks (D9). A phone-key pause never answers (`rule_pause.rs` doesn't call it).
- **Supersede** runs only after `raise` stored the request, inside the intake's transaction (R2-L6).
- **Policy keys**:
  - `time_policy.request_answers` and `answer` (R2-L4);
  - `answer` is the newest yes/no of a request asked in the phone's day (R2-L5) and carries `minutes` (R2-L1);
  - `lifts[].by` is the answer's snapshot, else the giver's live display name (R2-L10);
  - D11 holds: the phone never gets the login. Both `by_name` and the live `by` are display names only, trimmed and
    dropped when empty. The login appears only in the event's `params` (`admin.name()`), which never reach the phone.
- **Migration 0149** is the next number after `main`'s 0148, and design 102 has taken 0150. The branch isn't pushed, so
  the file is still free to change. The backfill gives the first non-phone-key lift at or after each request a `yes`
  (R2-M4), and `INSERT OR IGNORE` covers any NULL key. Retention drops answers after `LIFT_DAYS`.
- **The PWA card** (`device_detail.html`):
  - it sits right after `</header>`, never as the sheet's first child;
  - its form posts to `…/time-request/answer#ask`, and the route lands on `#quick` with the flash (R2-M1). The card's
    form is in `summary.rs`'s list and in `check_forms`;
  - `scroll-restore.js` appends the submitter's name and value. A second tap does nothing while the first save is out
    (`isBusy`). Without JS it is a plain post;
  - the CSS uses a grid `gap` and margins, with no flex gap (Safari 14.0), and its tokens exist in light and dark;
  - nb and en have the same keys and placeholders (checked with a script);
  - the push target is `#ask`.
- **Contracts**:
  - `policy_json_keys_snapshot` pins `request_answers`;
  - the `policy_*` rows pin `answer`'s and a lift's keys;
  - `lock_now.json` and `rule_pause.json` are updated in both directories;
  - the root `CLAUDE.md` paragraphs are rewritten.

## Tests that could fail in CI (reasoned, not run)

None found. Each risk the builder named:

- **`the_contract_policies` assumes lift id 1.** Safe:
  - every row runs on a fresh `TestApp`;
  - `time_lifts` is `AUTOINCREMENT`, and no migration or test setup inserts a lift.
  - Its `by` and `answer.by` match too: `admin_cookie()` seeds "parent" with `display_name` NULL, so
    `policy_unnamed` has no `by`, and `admin_cookie_as` gives "Mamma" and "Pappa".
- **The tests that need a fresh report** (`under_a_rule_the_minutes_pause_the_rules`,
  `a_fitting_lift_answers_the_open_request`, and the card test's used-up state) all hold:
  - `report()` posts `time_state.day` = Setup's today;
  - the status intake sets `last_seen_at`, so the connection is `Away` and the report is under 15 minutes old;
  - `report_is_fresh` is then true, and `TimeAsk::of` reads `BEDTIME` as a rule.
- **"gave 15\u{a0}min".** `time.duration.min` is `"{min} min"`. The inbox already asserts literal NBSPs elsewhere
  (`1\u{a0}h 20\u{a0}min left`). The answering parent's name is `admin.name()`, which is "parent" for the default
  account.
- **The seeded summary now shows a card.** Only `i18n_pages` and `policy_etag` use the seeded family.
  - `i18n_pages` checks only the words, and the card's text is all nb. Button `value`s aren't read, since only
    `<input>` buttons count.
  - The policy's ETag is a hash of the bytes served, so a new answer changes it and the phone is never sent a stale
    304.
- **Strings and markup that changed:**
  - `data-asked`, `device.summary.asked` and "Asked for more time" have no test left;
  - the `#quick` → `#ask` event target is updated in `parent_events.rs`'s two tests and in `time_request.rs`;
  - `time_rules.rs:1905` still expects `#quick` for an old event, which 0149 doesn't touch: correct;
  - the ⓘ sentence is updated in `tests::shortcuts` (nb and en);
  - `fresh_policy_has_no_rules…`, `lock_now::the_contract` and `rule_pause`'s key-set comparison are updated, with the
    phone-key lifts' sets having `at_ms` and no `by`;
  - `the_summary_fails_loudly_on_a_db_error`: the card's reads fail the page in the same way.
- **The summary form test** (`every_form_on_the_summary_returns_to_its_group`). The card's form follows the avatar's.
  `forms()` reads only `<input type=hidden>`. The answer is decided from the ask state, which with no time_state
  report is Time, so it lands on `?done=extra#quick`.

## Findings

### Launcher

- **L1 - a request the server took can stay uncounted** (`timerules/TimeRequests.kt:189-195`).
  - When it happens: the upload's 200 is lost (a retry) and a parent answers before the next sync.
  - What follows: `uploadStep` returns NONE, because the answer matches, so the record never becomes "asked" and the
    phone's day count isn't incremented. The kid can then try a fourth request, which the server refuses as `capped`.
  - Harmless, since the state shown is right. Fix: on NONE-because-answered, apply ACCEPTED's step (count + 1).
- **L2 - possible repeat TalkBack announcements** (`ui/LockActivity.kt:400-436`).
  - `renderAsk` sets the text of polite live regions on every `render()`: alarms, calendar, airplane, lock key.
  - A same-text `setText` may make TalkBack read "Be om mer tid" again. Guard each `setText` with a compare. To check
    in emulator check 8.
- **L3 - TalkBack focus drops after the tap** (`LockActivity.kt:404-411`). The focused button turns into the dim pill
  with `isFocusable = false`. The live region still reads the new text. Acceptable; check in check 8.
- **L4 - an older server's broad lift rule gets new words** (`TimeRequests.kt:83-86`).
  - On a server without `request_answers`, any newer lift closes the request, including a rule pause or a phone-key
    pause. The screen then says "Du fikk N min mer".
  - This is §14.7's rule as designed; only the words are new. The note could come only from a budget lift with no
    `phone_key`.
- **L5 - a brief "Vibb One" after an in-process anchor restart** (`AnchorNotification.kt:216-221`).
  - `forStart`'s fallback records `Plain(PLAIN_NOTICE)` even when a time part is already cached.
  - The first refresh replaces it within the deferral window. Could build `Plain(cachedNotice ?: PLAIN_NOTICE)`.

### Server

- **L6 - "{parent} har allerede svart." can name the wrong parent** (`time_answers.rs` `last_answerer`,
  `handlers/devices.rs:2410`).
  - `last_answerer` reads the device's newest answer, not the stale card's request.
  - Example: A is answered by Mamma; the kid asks B; Pappa answers B; Mamma's old tab then taps A's card. The flash
    says "Pappa has already answered".
  - Fix: carry the request id in the redirect (`&request=…`), or look up the answer row of that request.
- **L7 - a pause of a turned-on rule's original doesn't answer a request asked under its copy** (`time_answers.rs`
  `fits`).
  - The phone reports `active_rule_id` = the rule in force (`TimeRulesRuntime.kt:228`), which for design 93's copy is
    the negative copy id.
  - A Regler-sheet pause of the original (a positive id) unlocks the phone through the twin, but
    `rule_id == active_rule` is false, so the card stays open.
  - The parent can still answer on the card, which pauses every rule, so this is a second lift, not a wrong word. Fix:
    match `rule_id` against `copy_of` as well, or let a pause that the phone serves with a twin count.
- **L8 - a yes ended early still reads as given.** "End now" on a lift that answered a request leaves the answer's
  `yes` and `minutes` in the policy. The kid sees "Mamma ga deg 30 min" for D12's 30 minutes, and on the screen until
  the next ask, after the parent took it back. Rare. Could drop `minutes` and the note when the lift has
  `ended_early_at`.
- **L9 - the card doesn't say when its buttons add time to a request asked under a rule** (`handlers/devices.rs`
  `ask_card`). The sub-line keeps the ask's words ("21:40 · Bedtime") while `need` now says Time. The opposite case
  has the line "The buttons pause the rules in force."; this one has no counterpart.
- **L10 - backfilled answers.** On upgrade day, a request answered under r13 by a rule pause is served as a `yes` with
  that pause's minutes, so the phone says "Du fikk 15 min mer". The lift's `by` is then also the backfill's NULL rather
  than the giver's display name. This only affects the upgrade day.
- **L11 - docs and small code.**
  - `ask_card` was inserted between `render_device`'s doc comment and the function (`handlers/devices.rs:2030-2036`):
    the summary's doc now heads `ask_card`, and `render_device` has none.
  - R2-L9 (the optional `open` key) is not built, as §16 says.

## Not verified

- Nothing was run beyond the checks named at the top. In particular, the SQL strings (the backfill, the `policy_answer`
  and `deliverable_lifts` subqueries, and the supersede) were checked only by reading against the schema; `cargo test`
  will be their first run.
- Device behaviour still owed per §11:
  - whether SystemUI draws Nunito and the `<include>` in `shortcuts_panel_big.xml`;
  - whether `getActiveNotifications()` lists a deferred foreground-service notification, which the shed check assumes;
  - `TimeRuleAlarm`'s precision at the 15-second mark;
  - L2 and L3's TalkBack behaviour;
  - the gear in the Jelly Star's header.
- The PWA card's browser pass: 390 px, light/dark, JS off, two parents.
- Whether the WAL `SQLITE_BUSY_SNAPSHOT` case is now gone. Every writer that reads the open request is `IMMEDIATE`;
  by reading, nothing else reads and then writes `time_request_answers`.
