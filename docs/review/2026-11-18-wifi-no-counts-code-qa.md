# Code QA: Wi-Fi sign-in without daily counts (`wifi-no-counts`, f31e9439f)

2026-11-18. Read-only review of `git diff main...wifi-no-counts` against the user's decision (no daily counts; each window
at most 10 minutes or until the network works; "Gi én innlogging til nå" one opening per tap, uncapped; an unused grant
still ends with the day). No build run (CI green on both sides).

**Result: 0 High, 2 Medium, 4 Low.**

## What holds

- **Nothing refuses or closes on a budget any more.** `portalApprove` (PortalRules.kt:261-262) opens `none` with the
  requirement off and `grant` with a grant left, with no other gate; `portalClose` has no `budget` branch;
  `CaptivePortalEntry` lost its `"budget"` reason; `PortalWindowActivity` lost the `portal_budget` toast; `wifiRowState`
  has no `ASK` row. `grep` finds no `budgetLeft`, `chargeOpening`, `chargeTime`, `GRANTS_A_DAY`, `DAY_OPENINGS`,
  `day_openings`, `PortalToday`, `portal_ask` or `portal_budget` left in either side's code, strings or the touched notes.
- **The pref migration** (PortalRuntime.kt:130-143, 380-388): the new `grants` key is read first, the old `budget` key
  only as a fallback, through `PortalJson` (`ignoreUnknownKeys = true`, so the old JSON's `openings`/`ms` don't fail the
  decode). `left = grantUsed - grantOpenings` (floored at 0) and `seen = grantSeen` hand over today's unused grants
  exactly; an older date drops them as before. `save` writes `grants` and removes `budget` in one `commit()`, and
  `grants()` saves whenever `budget` is still present, so the handover happens once and can't double up. A malformed
  pref is a `runCatching` null: no crash, read as a fresh state (today's unused grants lost, the counter taken as seen).
  `@Synchronized` on `grants`/`take`/`fold`; `:portal` never touches these prefs.
- **Older launchers on the new server**: the policy no longer sends `day_openings`/`day_minutes`, and the old launcher's
  `CaptivePortalPolicy` defaults them to 4/20, so it keeps its built-in budget and 3-a-day grant cap. Its `today` is
  dropped by `clean_portal`; its `budget` outcome stays in `OUTCOMES` (logins.rs:51) and is still worded
  (`logins.outcome.budget`, handlers/logins.rs:223).
- **The new launcher on older servers**: the extra policy keys are read past (`PolicyResponseCompatTest`'s new case);
  an old server's `clean_portal` reads a missing `today` as zeros and its `budget_spent` stays false.
- **Stored rows still render**: `Kind::WifiSigninBudget`, its wire name, switch, title key
  (`events.wifi_signin_budget.title`, nb/en) and target stay; only the constructor went `#[cfg(test)]`. A stored
  `captive_portal_json` with `today` deserialises (`PortalReport` has `serde(default)`, no `deny_unknown_fields`). An
  old snapshot with `budgetLeftMs` decodes (ignoreUnknownKeys).
- **Contracts**: `server/testdata/login_windows.json` and `launcher/app/src/test/resources/login_windows.json` are
  byte-identical (`cmp`). Every `login_rules.json` row in `approve`, `close`, `grants` and `wifi_rows` gives the
  expected result when traced through `portalApprove`, `portalClose`, `grantsToday`/`takeGrant`/`grantLeft` and
  `wifiRowState`. The `clean` row's malformed `today` shows it is dropped.
- **Save anchors**: the grant form still posts to `…/logins#logins-wifi`, and the handler answers
  `?notice=granted#logins-wifi`. Advanced's row still answers on `#wifi-signin`. No save jumps to the top.
- **Android 14-17**: no new platform API (only `SharedPreferences.contains`/`remove`), so no gate is needed.
- No dead code found: `count()` is still used by the sessions and `login_browser`; `MINUTES` by `portal_policy`; the
  removed strings have no references left; the test helpers (`grantsOf`, the rows) are all read.

## Medium

### M1. "Gi én innlogging til nå" still shows while Foreldrekode is off, where it now does nothing

`server/templates/device_logins.html:36-40, 49`; `PortalRules.kt:261-262`.

Before this change a grant mattered with the code off: it added one opening and 10 minutes to the spent daily budget.
Now `!input.requireCode -> Open("none")` comes before the grant check, so with the code off a grant is never taken
(`login_rules.json`'s "the requirement off: no grant used"). It sits on the phone and ends at midnight. The PWA still
shows the button, the flash "Én innlogging til er på vei til telefonen" and the (i) line about it, so the parent is
told something happened when nothing did.

**Fix:** wrap the grant form, the `granted` flash and the `grant_sub` paragraph in `{% if require_code %}` (it sits
inside `{% if wifi_on %}` already). In `tests::logins`, assert that `data-grant` is absent once `section=wifi_signin`
is saved off and present again when it is on. The handler can stay as it is: a stale page's grant is harmless.

### M2. With no count, a kid can raise a push every 10 minutes

`server/src/logins.rs:455` (`wide`) and `parent_events.rs:252-267` (`push_budget`, no entry for `WifiSigninWide`).

With Foreldrekode off, openings are now unlimited. Every session that times out, visits more than 3 sites or shows a
blocked frame raises its own `wifi_signin_wide`, keyed by `opened_ms`, and pushes it. One window at a time and 10
minutes each still allows about 6 pushes an hour from one phone. Only the general `EVENTS_AN_HOUR` (20) stops them. The
daily count used to cap this at 4 code-free openings plus 3 grants a day. Before this was built, the user's decision
did not cover the push side.

**Fix (the user's choice, since it changes what the parent sees):** either give `Kind::WifiSigninWide` a `PushBudget`
(for example `per_hour: 2, per_key_hour: 2`), so the rest go to the inbox and the hour's digest as `sms_held` does, or
stop treating a `timeout` from an `approved: "none"` opening as wide. The kid had free access, so leaving the window
open isn't news; `sites` and `frames` still raise. Add a `tests::logins` case of seven timed-out `none` sessions in one
report.

## Low

### L1. Mixed versions during the rollout show the parent the wrong thing

`server/src/logins.rs:357-372` (`clean_portal` drops `today`); `server/src/tests/logins.rs:383-404`.

- **An older launcher on the new server** still enforces 4 openings and 20 minutes a day and shows the kid "Spør en
  voksen om Wi-Fi-innlogging". The server now drops its `today`, so the parent gets no "used up" event and no
  "I dag uten kode" row. The new (i) says only "Hver innlogging varer høyst 10 minutter".
- **The new launcher on an older server** gets the old page's row "I dag uten kode: 0 av 4 · 0 av 20 min" and the old
  footer "høyst 4 innlogginger … om dagen".

**Fix:** release the server and the launcher RC together and say so in both release notes. Alternatively, until the
launcher's stable release, keep raising `wifi_signin_budget` from an older launcher's spent `today` (keep
`budget_spent` and read `today` only for the event, not storing it).

### L2. The `budget` to `grants` migration has no test

`launcher/app/src/main/java/com/kidslauncher/mdm/portal/PortalRuntime.kt:130-136, 380-388`.

The handover is right, but no test pins it. If someone drops `ignoreUnknownKeys` from `PortalJson`, or renames a field
in `OldBudgetJson`, every updated phone would lose today's grants without a sound.

**Fix:** pull the read into a pure `storedGrants(grantsJson: String?, oldBudgetJson: String?): PortalGrants?` and test
it with:
- a real old pref string such as
  `{"date":"2026-11-18","openings":4,"ms":1200000,"grantUsed":2,"grantOpenings":1,"grantSeen":7}`, which should give
  `left 1, seen 7`;
- `grantOpenings > grantUsed`, which should give `left 0`;
- a malformed old string, which should give `null`;
- both keys present, where `grants` wins.

### L3. The (i) restates the button and leaves out the one new fact

`server/src/i18n/nb.json:4421-4425`, `en.json:4421-4425` (rendered at device_logins.html:47-49).

- `logins.wifi.grant_sub` opens by quoting the button's own label and then says what the label already says
  ("Say it once"). The English "once without the code. Works once the phone has a connection" uses "once" twice in two
  senses.
- The parent is never told that a grant lasts only for that day, which is the only rule left on it.
- `logins.wifi.footer` is one more paragraph that could be half a sentence in `logins.wifi.sub`.

**Fix:**
- nb `logins.wifi.sub`: "… og lukker den når nettet virker, senest etter 10 minutter. Nettfilterets lister gjelder,
  ikke din egen DNS-tjener."
- en `logins.wifi.sub`: "… and closes it once the network works, or after 10 minutes. …"
- Drop the `footer` key and its `<p>`.
- nb `grant_sub`: "«Gi én innlogging til nå»: én gang uten kode, samme dag. Telefonen må ha nett, for eksempel
  mobildata."
- en `grant_sub`: "«Allow one more sign-in now»: one sign-in without the code, the same day. The phone needs a
  connection, for example mobile data."
- If M1 is taken, this paragraph only shows with the code on.

### L4. The "no cap a day" row doesn't test the removed cap

`launcher/app/src/test/resources/login_rules.json:130`.

"the fifth one more today counts too" starts from `left 0, seen 4` and adds one step. The old code would have added
that step too, because the old cap counted `grantUsed` (0 in that state), not `seen`. The row passes under both the
old and the new rules, so it pins nothing about the cap.

**Fix:** use a row the old cap would have refused. Either a jump of more than 3 in one day
(`stored {left 0, seen 0}`, `grant 5` -> `left 5, seen 5`), or four steps taken one by one
(`stored {left 3, seen 3}`, `grant 4` -> `left 4`).

## Resolution (2026-11-18)

- L1: no code - both sides ship together in the next server and launcher releases.
- M1: the grant row, its flash and its (i) line show only with Foreldrekode on; `tests::logins` checks both states.
- M2: waits for the user's answer; unchanged.
- L2: `storedGrants` (pure, PortalRuntime.kt) with the four cases in `PortalRulesTest`.
- L3: the review's wording; `logins.wifi.footer` gone.
- L4: two rows the old cap would have refused (a jump of 5; a fourth step in a day).

## Not findings

- The 2026-11-15 run log in `docs/testing/emulator.md` (E6, E10, E12 "Passed" rows) still describes the daily count.
  It is a dated record of that run, and the E-table above it was updated, so leave it.
- `PREFS = "portal_budget"` keeps the old file name on purpose (renaming it would need another migration). It is
  commented at PortalRuntime.kt:29.
- PLAN.md on `main` shows the item as "IN BUILD 2026-11-18 (`wifi-no-counts`)". The merge commit marks it done.
