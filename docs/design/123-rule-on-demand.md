# Design 123: A rule turned on with no schedule ("Slå på regel" always)

Status: built (cut scope, below), 2026-11-22. The user's request (2026-11-20): "om ingen rules er satt på på telefonen, så får man ikke
på den snarveien om å slå på og pause rules? ... kanskje man vil sette på en \"skjermstopp\" pause eller noe deromkring,
selv om ikke denne er på en egen schedule?" The approved direction (2026-11-22, "Høres bra ut"): **Slå på regel** is
always shown and its sheet lists every family rule, those off for this phone too; a rule may have **no days** ("Bare
når du slår den på"), so a parent's own "Skjermstopp" is an ordinary rule; **Pause regler** stays hidden with nothing to
pause. Not a fixed built-in Skjermstopp (declined).

**Built in a cut scope (2026-11-22).** The user: "Trodde det bare var snakk om å få knappen til å dukke opp også på
jelly uten at den har aktive regler slått på". Built: Slå på regel always, its sheet listing the phone's scope (§3.1,
§3.2), "Ingen regler" and "Legg til regel" with no rule in the family, Pause regler hidden with nothing to pause (and
shown while a pause still runs, QA L4), the server's lookups (§4) with the design QA's fixes (M1, L1-L3, L5, L7-L9),
`pause_not_in_force`, and `lock_now.json`'s two policies and six cases. **Cut**: every word of §6 but the sheet's own
("Ingen regler", "Legg til regel" - the existing `device.time.add_rule` - and the refusal `pause_not_in_force`); the
rule editor and its words stay as they are (a rule with every day empty already saves and reads "Aldri"), and §10's
Q1 explicit choice is not built. `on_gone` keeps its words ("Regelen gjelder ikke lenger"). Design QA L2 (the kid's
pause title) is a launcher change and ships with the next launcher RC, the server first.

Builds on design 93 (`docs/design/93-lock-now-quick-actions.md`: the tiles, the Regler sheet, `time_lock_now`, the
copy with `id = -(lock id)`, `lock_now.json`) and design 98 §4.4 (the parent code's pause on the phone, which maps a
copy to its rule).

## 1. Verdict

**Small, server and PWA only; the launcher needs no code.** Everything the phone needs exists: a rule with seven
`null` days never locks (`spanOn` returns no span), and a copy is a one-weekday window with `until_ms`, whatever its
rule's own days are. A rule with no days already saves today (`parse_rule_form` takes seven empty rows; the lists call
it "Aldri"). What changes is which rules the server lets a parent turn on, where it looks them up, and the words.

## 2. Where it lives

- **Only the PWA's phone page, Quick Actions** (design 93 §6.1). The kid's phone has no "turn on": its only rule
  action is the parent code's pause (design 98 §4.4), and Snarveier (design 99) has no rule item. A parent at the
  phone uses the PWA. No new place.
- **Slå på regel** is rendered always (design 93 rendered both rule tiles only when `effective_rule_rows` was
  non-empty). With no rule at all in the family, its sheet shows one row "Ingen regler" and a row link **"Legg til
  regel"** (`device.time.add_rule`, to `/schedules/rules/new`, everyone's rules); no Hva segment, no button.
- **Pause regler** is rendered only when something can be paused (§3.3). Skjermtid then stands with Slå på regel.

## 3. Which rules, and where the server looks them up

### 3.1 Three sets

| Set | Rows | Used by |
|---|---|---|
| **Effective** (unchanged) | everyone's and the phone's own, minus `device_rule_off`, then its bedtime | the policy, the badge, counts, Attention, the lists |
| **Phone scope** (new: `time_rules::phone_rule_rows(db, device)`) | everyone's and the phone's own **ignoring** `device_rule_off`, then its bedtime; the effective order | the on-list, turning on, serving a copy, the phone's pause intake |
| **Pausable** (new: `quick_actions::pausable`) | the effective rows with at least one day, plus the running on-lock's rule if it isn't among them | the pause list, the Pause regler tile, `/lifts` `rule=<id>` |

Another phone's own rules are never in a phone's scope: they are that kid's. A family-wide Skjermstopp is made under
everyone's rules.

`phone_rule_rows` is `effective_rule_rows`'s query without the `NOT EXISTS` clause (one function with a flag, or two
thin wrappers over one query; the builder picks).

### 3.2 The on-list (the sheet in on mode)

The phone-scope rows, in their order (everyone's, the phone's own, Leggetid last), as one list, unmarked. A rule off
for this phone or without days is turned on exactly as a scheduled one. **Selected when the sheet opens**: the first
rule without days (it is made to be turned on), else design 93's first school rule, else the first rule; a refusal's
`rule=` wins.

### 3.3 The pause list (the sheet in pause mode)

"Alle regler" first, then the pausable rows. A rule without days is left out unless it is the one turned on now: it
can't lock, so there is nothing to pause. When the pausable set is empty and no on-lock runs, the Pause regler tile is
not rendered and the Regler sheet has no Hva segment (on mode only; `m-pause` not rendered).

### 3.4 The one select

Design 93 has one `<select name="rule">` for both modes. Each `<option>` gets `data-on` and/or `data-pause`; with JS,
`quick-sheets.js` hides and disables the options the mode lacks (as it already does for "Alle regler" in on mode) and
moves the selection to §3.2's/§3.3's default when the selected one goes. Without JS every option shows; the server
refuses a mismatch: on with "all" stays `on_needs_rule`; pause with a rule outside the pausable set is the new
`pause_not_in_force` ("{rule} gjelder ikke på {name}" / "{rule} isn't in use on {name}"), the sheet reopened in pause
mode.

## 4. Data model and the policy

**No migration, no new policy key.** A rule with no days is `days_json` = seven `null`s, already valid everywhere:
`parse_days`, `to_policy`, `served_days`, `days_summary`, the launcher's DTO.

Changes in the server's reads:

- `rules_now` (`mode=on`, design 93 §5.1 step 1): the rule must be in **phone scope**, not effective. `on_gone` now
  means only "deleted" (its words change, §6).
- `lock_now::served_rule_rows`: the running lock's rule is looked up in **phone scope**. Today a lock whose rule is not
  effective serves nothing; after this, a rule switched off for the phone **before** it was turned on is served as its
  copy. The copy's index (`copy_first`) is still computed against the effective rows. The rule's own row stays out
  of `rules[]` when it is off: only the copy is served.
- **Switching a rule off while it is turned on still ends it** (0142's trigger on `INSERT INTO device_rule_off`,
  unchanged): the parent acted on that rule for this phone after turning it on. Switching it on again doesn't bring it
  back, as today. Deleting the rule ends it (`rule_id` NULL), as today.
- `handlers::rule_pause::intake` (design 98 §4.4): a copy's `-(lock id)` and its `copy_of` are looked up in **phone
  scope**. Without this, the parent code's pause of a turned-on rule that is off for the phone is answered
  `unknown_rule` and the server never records it: the phone pauses, the next policy relocks. This is the one real
  bug the change would bring; the fix is the lookup.
- `handlers::lifts` (`rule=<id>`, the PWA's pause and a "yes" to the kid's request): the rule must be in the
  **pausable** set. `twin_lifts` already serves a pause of the copy's rule whatever the rule's switch.
- `time_answers` already maps a copy's id through `time_lock_now`; no change.

What the launcher does with it (no code change, checked):

| Function | A rule with no days | A copy of a rule off for the phone / of a no-day rule |
|---|---|---|
| `inForceAt` | kept (no `until_ms`) | dropped at its `until_ms`, as every copy |
| `decideTimeLock` | never active (`spanOn` null every day) | active in its one window; its mode is the rule's |
| `cardRule` / `lockEnd` | never picked | picked by `card_first`; Til at its end when that is an unlock |
| `nextBoundary`, waiting notices | no edges | the copy's two edges, as today |
| `RulePauseActivity` | - | `names` filters copies; with only the copy locking it falls back to the first rule's label, which is the copy's: "Skjermstopp" |
| `rulePausePlan` | - | sends the copy's id and its `copy_of`; the server now finds both (above) |

## 5. The contract: `server/testdata/lock_now.json`

Rows added (byte-identical copy in `launcher/app/src/test/resources/`, one commit with the server's change):

- `policy_on_demand`: the `time_policy` served at a fixed now from seeded rows: Skole (scheduled, id 1), **Skjermstopp**
  (everyone's, `custom`, seven `null` days, id 3, on for the phone, served as itself), **Lekser** (everyone's, id 4,
  off for the phone: not served), the bedtime (id 2), and Skjermstopp turned on 2026-11-16 15:00-16:00 Europe/Oslo
  (lock 2: a copy `id -2`, `copy_of: 3`, `card_first: true`, `until_ms`).
- `policy_off_rule`: the same rows after lock 2 has ended and **Lekser** is turned on 15:00-16:00 (lock 3): the copy
  `id -3`, `copy_of: 4`, and no row with id 4.
- `cases`:

| policy | at | locked | card_rule | card_until |
|---|---|---|---|---|
| `policy_on_demand` | 2026-11-16T15:30 | true | -2 | 2026-11-16T16:00 |
| `policy_on_demand` | 2026-11-16T16:30 | false | null | null |
| `policy_on_demand` | 2026-11-17T15:30 | false | null | null |
| `policy_on_demand` | 2026-11-23T15:30 | false | null | null |
| `policy_off_rule` | 2026-11-16T15:30 | true | -3 | 2026-11-16T16:00 |

The server's `lock_now::tests::the_contract` produces both policies (`LOCK_NOW_CONTRACT_WRITE=1`); the launcher's
`LockNowTest` already runs every `cases` row generically and gets two `policy(..)` id-order assertions
(`[-2, 1, 3, 2]`-style, whatever `copy_first` gives). No key changes: `policy_json_keys_snapshot` and
`PolicyResponseCompatTest` are untouched. CLAUDE.md's paragraph on `lock_now.json` gains the two policy names.

## 6. The PWA's words (nb / en)

The rule editor gets **no new control**: a rule with all days empty is the on-demand rule (a new rule already opens
with the day rows empty). Its summary and the (i) say it:

| Key | nb | en |
|---|---|---|
| `settings.time_rules.never` (rows' summary, Settings and Device › Time Rules) | Bare når du slår den på | Only when turned on |
| `settings.time_rules.when_footer` (the Når (i)), the sentence "La en dag stå tom når regelen er av." becomes | La en dag stå tom når regelen er av. Uten noen dager gjelder regelen bare når du slår den på, under Hurtighandlinger. | Leave a day empty when the rule is off. With no days, the rule applies only when you turn it on, under Quick Actions. |
| `device.time.everyone_footer` | Av betyr at regelens tider ikke gjelder for {name}. En regel som legges til senere, er på. | Off means the rule's times don't apply to {name}. A rule added later is on. |
| `device.summary.flash.on_gone` | Regelen finnes ikke lenger | The rule no longer exists |
| `device.summary.flash.pause_not_in_force` (new) | {rule} gjelder ikke på {name} | {rule} isn't in use on {name} |
| `device.summary.qa.no_rules` (new) | Ingen regler | No rules |

No warning anywhere for a rule without days, or for turning on a rule that is off for the phone: both are a parent's
choice. The save flash stays as it is. The rule key `never` keeps its name (one value change).

**The page never jumps**: the editor's save is unchanged (sticky bar, in place); every Quick Actions form still returns
to `#quick` with `scroll-restore.js`, a refusal with `&open=rules&mode=..&rule=..`.

## 7. Android 14-17

Nothing platform-specific is touched: no launcher code changes, the copy is design 93's (checked there). No emulator
gate needed; the launcher's unit test runs the new rows.

## 8. Tests (lean)

- `lock_now::tests::the_contract` (both new policies) and the copy comparison; `LockNowTest` (the new cases).
- `tests::rules_now`: turning on a rule off for the phone serves its copy, and not the rule; a no-day rule's copy
  locks in its window; switching the rule off while it runs ends it; `on_gone` only for a deleted rule; pause with a
  no-day rule not turned on is `pause_not_in_force`.
- `tests::rule_pause`: the phone's pause of a copy whose rule is off for the phone is stored (one row on the rule,
  the twin served), not `unknown_rule`.
- `quick_actions` render: with only a no-day rule, Slå på regel shows and Pause regler doesn't, no Hva segment; with
  none, "Ingen regler" and the link; the selected option is the no-day rule; options carry `data-on`/`data-pause`.
- `jstest/quick-sheets.test.js`: switching mode hides the other mode's options and moves the selection.

## 9. Size and steps

About 450 lines: server about 420 (the scope read and four lookups ~50, `quick_actions` and the template ~90,
`quick-sheets.js` ~30, i18n 12 values, tests ~250), launcher about 30 (the contract copy and `LockNowTest`'s two
assertions). One server release; no launcher RC needed.

1. **One commit, both directories**: `phone_rule_rows`, the lookups in `rules_now`, `served_rule_rows`,
   `rule_pause::intake`, the pausable check in `lifts`; `lock_now.json`'s two policies and five cases in both copies;
   `LockNowTest`; CLAUDE.md's line. `cargo test`, `cargo clippy --all-targets`, `cargo fmt --check`; the launcher's
   `testDebugUnitTest`.
2. **The PWA**: the tiles' rendering (§2), the two lists and the default selection (§3.2, §3.3), `data-on`/`data-pause`
   and `quick-sheets.js` (§3.4), `pause_not_in_force`, the words (§6), the render and jstest tests.
3. Notes: `docs/notes/server/time-rules.md` ("A rule turned on now": phone scope, the switch-off rule) and the
   launcher's `time-rules.md` line on the contract.

## 10. Choices for the user (defaults above; each is visible)

- **Q1, how a parent makes an on-demand rule**: by leaving every day empty (default; the (i) and the summary say it),
  or an explicit choice at the top of Når, "Faste tider" / "Bare når jeg slår den på", that hides the day rows (about
  +60 lines, a segmented control in the editor).
- **Q2, the on-list**: one unmarked list of every rule (default), or the rules off for this phone and the no-day ones
  after a divider. And the preselected rule: the first without days (default) or design 93's first school rule.
- **Q3, with no rule in the family**: the tile shows and its sheet offers "Legg til regel" (default), or the tile stays
  hidden until a rule exists.
