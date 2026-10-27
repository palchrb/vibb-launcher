# QA (code) - round S-i18n slice 1: the i18n layer and each parent's language

Reviewer: code QA, 2026-10-27. Branch `pwa-i18n-1`, commit `07099709` on `1829f20a`, read against design 70 §2, §3,
§8, §10 and "Carried into the briefs", and `qa-70-design.md` M4-M6, L1. Line numbers are `07099709`'s. No product
code was changed; each mutation was applied alone by a script and its file restored (`git status` clean after).

**Verdict: sound - merge after a small fix round (or merge, with the fixes riding on slice 2).** The extractor's
order, the store-once, the POST and its guards, the catalog rules and the escaping hold under tests, probes, 16
mutations and a browser pass. Nothing here is a bug a parent hits on Account in normal use. The findings: unmoved
pages now say `lang="nb"` over English text, one English banner left on Account (stored in the session), one corner
where the select can't save, and four test gaps.

**Counts: High 0, Medium 0, Low 5, Nit 5.**

Checked and fine:
- **Extractor** (`i18n.rs:105-116`): `?lang=` (else ignored; `zz&lang=nb` → nb), then `CurrentAdmin.lang` from the
  extensions (no query), then `Accept-Language`, then En. HTTP probes: `nb-NO,en;q=0.8` → nb, `en-GB,nb;q=0.9` → en,
  `nn`/`no` → nb, `!!!,;;;q=` → en, `*` → en, `NB-no` → nb, `nb;q=0, en;q=0` → en. `/login` follows the header.
- **Store once** (`security.rs:859`, `:874-891`): only while `lang` is NULL, from the header, never `?lang=` (a first
  request `/devices?lang=en` with an nb header renders en and stores `nb`); later requests write nothing;
  `require_session` pages store nothing. An unknown stored value (`de`) reads as no choice and is kept - right, a
  rollback from a build with a third language mustn't erase the choice (see L3).
- **`POST /account/language`** (`auth.rs:1297-1327`): inside `require_full_auth` and `settings_log`, behind
  `csrf_guard`; `zz`/`""`/`NB` → 400 `text/plain` in the reader's language, nothing written; epoch and `removed_at`
  guarded; 303 `/account#language`; one `settings_saved` row. Signed out → 303 `/login`, nothing written.
  Cross-site → 403 "Avvist: dette skjemaet ble sendt fra et annet nettsted." for an nb browser.
- **Escaping**: no `|safe` on a word; a name `<i>Ås</i> {username}` shows escaped in the field, the Parents row and the
  confirms, `{username}` not expanded; `?lang="><script>` → `<html lang>` from the enum. `display_name`'s keys are
  translated by both callers (`parents.rs:47`, `:142`).
- **Mechanical change**: every template struct but `sw.js`'s has `lang` + `words!`; no handler renders with a `Lang`
  other than its extractor's (the only literal `Lang::En`s are in `#[cfg(test)]` helpers); one `Lang` per request, so
  no en/nb swap by argument order is possible. `account.html` keeps no literal word, and its English values equal the
  old template's text word for word.
- **Migration 0104**: fresh, and on a 0103 database with an onboarded admin (column and 0104's row dropped):
  `pre-migrate-0103-0104.db` taken, `AdminUser` (`SELECT *`) loads, sign-in works, the first page stores the language.
- **Browser pass** (Chromium 390×844, light/dark, JS on/off, nb account with an en browser and the reverse; 8 runs,
  plus 4 at 390×420 with `main` scrolled): the row "Vibb Phone på · Norsk", each option with its own `lang`; with JS
  the save comes back at the same scroll position (row at 105 px before and after) in the new language with
  `<html lang>` flipped; without JS the `<noscript>` Save lands on `#language`; a reload stays; no horizontal scroll,
  no console errors. `/login` with an nb browser: `lang="nb"` (words English until slice 3 - L1).

## Findings

**Low 1 - every not-yet-moved page now says `<html lang="nb">` over English words.** All ~60 page templates
(`login.html:2`, `devices_list.html:2`, …) went from `lang="en"` to `lang="{{ lang.code() }}"`; §3 says "Every
*moved* page sets it". For an nb parent each English page is declared Norwegian until its slice-3 PR: a screen reader
reads it with a Norwegian voice, spell check and translate go by it. Slice 1 is taggable alone, so this ships. Fix:
unmoved templates keep `lang="en"`, each slice-3 PR flips its own with its words; the test's `/devices?lang=nb` check
moves to `/account?lang=nb`.

**Low 2 - "Wrong codes before this sign-in" stays English on the nb Account page.** `admin_auth::login_warning`
(`admin_auth.rs:384-409`) builds an English sentence at sign-in and the session keeps the text; Account shows it under
the Norwegian title (`auth.rs:849`), as does the Devices list. It is the one security message on Account an nb parent
can't read; its twin, the live warning, was moved. The session should keep the count and addresses, and the page word
them (`t_count`). Not on L5's list, so name its slice (3a, with the Devices list) in PLAN.

**Low 3 - with no usable stored language the select shows "Norsk" and can't save it.** `auth.rs:867` selects an
option only when `admin.lang` equals its code. With `lang` unknown (`de` after a rollback) or NULL (the store failed),
the browser shows the first option, "Norsk", on an English page; choosing "Norsk" fires no `change`, so autosave
never saves it. Fix: select `Lang::from_code(admin.lang)` or else the header's language (never `?lang=`'s).

**Low 4 - four guarantees untested** (surviving mutations below): (b) `AND lang IS NULL` - the race guard, hard to
test, acceptable; (m) `t_params` as one `replace` per param - `parameters_are_filled_once` (`i18n.rs:425`) uses one
param naming itself, which a naive `replace` passes too; use `[("name", "{until}"), ("until", "14:05")]`; (n) the
epoch guard on `set_language`; (p) `selected` from the request's language - nothing checks the select under `?lang=`
(probed: `?lang=en` on an nb account selects `nb`, right). A signed-out POST isn't in the test either (probed).

**Low 5 - the nb wording**: four lines worth changing before the user reads the catalog diff (→ below).

**Nit 1** `security.rs:886`: `store_first_lang` returns `Some(code)` at 0 rows too, so the request that lost a race
renders in the header's language, not the chosen one (one page). **Nit 2** `i18n.rs:83`: an unparsable `q` counts
as 1.0 (`nb;q=abc, en;q=0.9` → nb), `q=5` wins; ignoring such an entry is safer. **Nit 3** `every_key_used_exists`
misses a first-segment typo outside the call forms (`i18n::error(lang, s, "eror.csrf")`, `refuse(…, "acount.…")`):
the literal is in no namespace and no marker matches; add `error(lang, ` as a call form. **Nit 4** `i18n.rs:137`
warns on every render of a missing key, not once per key (N3 allows either; the tests keep keys present).
**Nit 5** `partials/group_flash.html:7`: the ⓘ is labelled "Why" in English (3a's partial; no Account refusal has a
detail today).

## The implementer's deviations

- `/sw.js` gets no `lang`: right - §4/H1, a public route with `escape = "none"`.
- `<html lang>` in each page, not `head.html`: right place (`head.html` is inside `<head>`); *which* pages: L1.
- sqlx's "admin lang": from the file name; the order test pins it. Fine.
- `Words::tc` `#[allow(dead_code)]`: fine until 3a uses it; drop the allow then.
- Two `too_many_arguments` allows (`settings.rs`, `tracked_apps.rs` render helpers): fine, ten exist already.
- `tp` params without `&`: right, Askama passes a reference (documented in pwa.md "Words").
- Refusals joined from keys: fine - each piece is a whole sentence, so no word order crosses a key.
- English left: the session's wrong-codes banner → L2. `display_time` ("Logget inn today 14:02", the OTP's "until"):
  slice 2's by D1 - confirmed. Security details, `busy_response`/`signed_out_response`: slices 2 and 4 - confirmed.

## The nb wording

All 107 values read: "du" throughout, plain and calm, no English left, placeholders identical, sentence case where
English uses Title Case (right for Norwegian). No overlap with the launcher's `values-nb`.
- → `account.add.name_hint` "… De kan endre det selv." - at a sentence start "De" reads as the polite "you":
  "Den nye forelderen kan endre det selv."
- → `account.parents.last_you`/`last_other` "legg til en forelder til først" (til … til): "legg til en annen
  forelder først".
- → `account.sessions.title` "Økter": correct but technical; "Innlogginger" says what the group is.
- → `account.otp.hint` "lager et nytt passord og tofaktorinnlogging": "… og setter opp tofaktorinnlogging".
- "Deg" ("admin · Deg"): fine; "Meg" (as iOS's family list) is the alternative. "Vibb Phone på" + "Norsk": fine.
- "Brukernavnet deres"/"Navnet deres": singular "deres" can read as plural - "Brukernavn"/"Navn" suffice under
  "Legg til forelder"; likewise "Det de har, slutter å virke" → "Det gamle slutter å virke med en gang."
- Optional: "Ikke et språk denne serveren har." → "Denne serveren har ikke det språket."; "Tofaktorinnlogging"
  mirrors "two-factor login" (Google says "totrinnsbekreftelse", Apple "tofaktorautentisering") - one term everywhere.

## Mutations

Each alone; run `i18n:: tests::parents:: tests::calendar:: admin_auth security:: migrations_73_to_104 csrf
settings_log tests::shell::` (120 tests, all pass unmutated). 12 killed, 4 survived, 0 compile errors.

| # | Mutation | Result |
|---|---|---|
| a | the first store takes `?lang=`'s language | killed - `language_is_each_parents` |
| b | `store_first_lang` without `AND lang IS NULL` | **survived** (L4) |
| c | `nn` → English | killed - `the_language_follows_accept_language` |
| d | the account's `lang` ignored | killed - 3 (`a_requests_language_…`, calendar, parents) |
| e | the POST stores `lang=zz` | killed - parents |
| f | the POST's 303 to `/account` (no anchor) | killed - parents |
| g | the POST under `require_session` only | killed - parents (no `settings_saved`) |
| h | `{count}` unfilled in `t_count` | killed - `a_count_picks_one_or_other`, `a_live_session_sees_wrong_codes_…` |
| i | a key removed from `nb.json` only | killed - `both_catalogs_have_every_key_…` |
| j | `t` falls back to English on a miss | survived - unkillable while parity holds (both fallbacks give the key) |
| k | `i18n::error` answers HTML | killed - `an_error_is_plain_text_in_the_language` |
| l | `from_code` by prefix, any case (the code from the request string) | killed - `a_requests_language_…`, parents |
| m | `t_params` as `replace` per param | **survived** (L4) |
| n | `set_language` without the epoch guard | **survived** (L4) |
| o | the CSRF refusal always English | killed - parents |
| p | `selected` from the request's language | **survived** (L4) |

(l) stands in for "the attribute's code from the request string", which the types rule out (`Lang::code` is a
`&'static str` from the enum); the injection probe checks it end to end.

## Runs

`cargo fmt --check` clean; `cargo clippy --all-targets --locked` 0 warnings; `cargo test --locked` **1424 passed**, 0
failed. No `static/` change (jstest not needed). Browser pass 12 runs, 0 console errors. HTTP probes: 14
`Accept-Language` values, 6 `?lang=` values, store-once, the POST (400/303/403/signed out), escaping, 0103 → 0104.
