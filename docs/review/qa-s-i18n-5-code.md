# Code QA: S-i18n slice 5 (`pwa-i18n-5`, 86a986e9 on main 2e73a78e)

Light review of `git diff 2e73a78e..86a986e9` against design 70 §7. Read-only: nothing built or run (the implementer
ran cargo test 1708/0, fmt, clippy). Server-only slice: the server's own rule names go to the phone as `name: ""`, any
other name loses a trailing " (morning)"/" (evening)", the PWA words them (`device.time.rule.default.<n>`,
`device.time.rule.morning`), names an active rule by `active_rule_id`, and a save of the shown name keeps the stored one.

**Verdict: ship (merge-ready).** 0 High, 0 Medium, 4 Low, 4 Info. The launcher side is verified to handle `name: ""`
everywhere it matters; the Lows are edge cases that can be fixed in a follow-up or accepted.

## High

None.

## Medium

None.

## Low

**L1. The save-keeps-name rule compares in the POST's language, which can differ from the GET's**
`server/src/handlers/schedules.rs:1447` (`input.name == time_rules::shown_name(lang, &stored.name)`), form action
`server/templates/schedule_rule.html:16` (carries no `?lang=`).
`Lang::of_request` takes `?lang=` first (this request only, never stored), then the account's language. Scenario: an
admin opens `/schedules/rules/7?lang=nb` (a link, or the account language changed in another tab after the form
loaded). The form shows "Utenfor tillatte tider"; the POST goes to `/schedules/rules/7` with the account language en,
`shown_name(En, "Outside allowed hours")` is "Outside allowed hours", the names differ, and the rule is silently renamed
to the Norwegian literal. The phone then gets a non-blank name (the Norwegian text on an English phone, and an English
PWA parent sees Norwegian). The new test passes only because it puts `?lang=nb` on the POST too. Recoverable by
renaming. Cheap fix: also keep the stored name when the input equals `shown_name` in the other language (compare
against both `Lang::En` and `Lang::Nb`), or compare against the stored name too.

**L2. A parent-typed name equal to a server name is blank on the phone, and the worded name can never be set on purpose**
`server/src/time_rules.rs:62-66` (`phone_name`, exact `SERVER_NAMES` match), `schedules.rs:1447`.
The match is on the exact string only, so it cannot tell a typed "Bedtime" or "Outside allowed hours" from the server's.
Scenario 1: an English parent creates a custom rule "Bedtime" for tablet-free evenings; the kid's lock screen shows
"Skjermfri tid" (kind label), not the typed name, and a nb parent sees it as "Leggetid" in the PWA. Scenario 2: a nb
parent renames the server's "Outside allowed hours" (custom kind) to "Utenfor tillatte tider" on purpose; it equals the
shown name, so the stored English name is kept and the phone shows "Skjermfri tid". Nobody gets stuck (any other name
works, and the rule works), and the design accepts exact-string matching, so Low. Worth one line in `time-rules.md`
("a typed name equal to a server name is treated as the server's").

**L3. "Bedtime (morning)" is cut to "Bedtime", not blanked, so the phone can show English**
`server/src/time_rules.rs:62-70` (`phone_name`) with `morning_name` (`:620`) and `migrate_crossing` (`:700-745`).
`SERVER_NAMES` has the four exact names, but a split of a rule named "Bedtime" (old DBs: `migrate_crossing` split every
crossing rule before design 66 made phone bedtimes) gives "Bedtime (morning)". It is not in `SERVER_NAMES`, so the
phone gets "Bedtime" (suffix cut, non-blank) and a Norwegian kid sees the English word, while the PWA shows
"Leggetid (morgen)" through `shown_name`. Fix: in `phone_name`, strip the suffix first, then test the stripped name
against `SERVER_NAMES` (and mirror it in `shown_name` for the default words). Only old split rows are affected.

**L4. Names the server writes into the settings and activity log stay English**
`server/src/handlers/lifts.rs:131-137` (`security.detail.lift.rule` gets the stored `rule_name`),
`schedules.rs:1508` and `:1735`, `log_change(..., &input.name)` at `:1469` (the kept stored name), and
`time_rules.rs:745, 809, 861` (`scoped_detail`, `now_bedtime_of`). A nb parent reading the log sees "Bedtime" or
"Outside allowed hours" inside Norwegian sentences. The slice reworded the visible rule rows and the lift rows on the
summary/today pages (`activity.rs:160`, `lifts.rs:380`), not these. `pwa.md` still lists logs as English by design, so
this is a gap in the wording only. A `shown_name(lang, ..)` at the points that render the log (not at write time) would
fix it, but the log stores the words with facts, so it is out of a light slice.

## Info

**I1. Launcher handles `name: ""` everywhere (verified, no launcher change needed).**
`ruleLabel(kind, name)` (`launcher/.../ui/TimeLockModel.kt:37-38`) gives `Named` only for a non-blank name with a
kind, else `Kind(ruleCardKind(kind))` ("Skole" / "Leggetid" / "Skjermfri tid"; `values-nb` has `lock_kind_*`). It is
the only reader of a rule's name for display: `RuleCard.kt:66` (glance card) and `LockActivity.kt:273` (lock
screen) go through it. Nothing else in `timerules/`, `lock/`, `badges/`, `calls/` keys, dedupes or compares on a time
rule's name (rule/lift identity is the id; `ZenRuleRuntime` uses a fixed string resource). `TimeRulesRuntime.kt:207`
reports `top?.name`, which is now "" for those rules; `time_state.rs` parses a blank to `None` and the new
`named_by` covers it (the unit test at `time_state.rs:528` pins the blank case). The kid-visible change: a custom
"Outside allowed hours" rule now shows "Skjermfri tid" instead of English text on a Norwegian phone (intended).
The policy keys are unchanged: only `name`'s value moves (`PolicyTimeRule` untouched; `policy_json_keys_snapshot`
pins keys, not values).

**I2. N5 holds: a typed " (morning)"/" (evening)" suffix is cut on the phone.**
`phone_name` strips it from any name, so "Natt (evening)" is "Natt" for the kid while the PWA shows "Natt (evening)"
(`shown_name` words only the morning suffix; "(evening)" only inside the exact server name). In nb, a typed
"Brann (morning)" shows as "Brann (morgen)" in the PWA. Both match design note N5 and the doc comment. One small
inconsistency: the same text typed as "Natt (morgen)" is not cut on the phone, so identical-looking names differ
by history. Acceptable.

**I3. Correctness checks that came out clean.**
Save-keeps-name: the compare is on the trimmed input; a rule whose stored name is a literal "Leggetid" (typed) is
unaffected; a stored "Natt (morning)" shows "Natt (morgen)" in nb and a save of that text keeps "Natt (morning)"; any
other name saves as typed, so no parent is stuck. Bedtime rows (`bedtime = 1`) are not editable here
(`rule_in_scope`). The save still redirects to `/schedules/rules/{id}?saved=1#rule`, so a save never jumps to the
top. Sizes: nb "(morgen)" is shorter than " (morning)", and the longest shown default is 31 chars, under the
form's `maxlength="60"`. `t_params` does no escaping and askama escapes the result once, so a name with `&` or `<` is
not double-escaped. `badge_and_screen` call sites (`devices.rs:323` and `:1850`, `schedules.rs:1899`) all pass the
device's effective rules, loaded only when a rule locks; a rule no longer there falls back to the reported name, then to
"A time rule". Diagnostics' extra load (`device_pages.rs:738`) is one indexed `effective_rule_rows` query, only on the
single About page and only when `locking() && active_rule_id.is_some()`: cheap.

**I4. i18n and tests.**
en and nb each gain the same 5 keys, `{name}` present in both. nb words are natural: "Leggetid", "Utenfor tillatte
tider", "(morgen)", "(kveld)"; no nynorsk. Test coverage is good for the badge, About and save-keeps-name
(`tests/time_pages.rs:1930`) and the wire (`time_rules::tests::server_names_go_to_the_phone_blank`), but not covered:
the morning form in a rules list, the lift rows ("Paused Bedtime" in nb) and the Pause picker, and the policy
endpoint end to end (`name: ""` for a legacy "Outside allowed hours" row). The commit does not touch PLAN.md (its
queue entry for slice 5 still needs "built, awaiting QA / merged" at the merge).
