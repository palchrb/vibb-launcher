# QA 31 step 8 (code) - Calls & Contacts

Reviewer: code QA, 2026-10-14. Branch `design-31-s8`, `2eee4de1..552d3eed` (one commit, 28 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.3, §2.2b, §3.0, §3.2 (Calls & Contacts), §3.6 (Phone Setup), §4, §5 (§5.1 incl. §5.1.4, §5.2, §5.5, §5.6), §8
  step 8 (H1, M6, L6) and §9;
- step 6's and step 14's status and fix-round tables, and "Step 8 implementation status" with its 7 choices;
- `qa-31-s6-code.md` and `qa-31-s14-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/calls.rs` in full (the page, `call_report` / `Finding` / `CallProblem`, every write handler, the photo
  handlers), the old `call_report` at `2eee4de1` side by side with the new one, condition by condition;
- `handlers/settings.rs` (`save_country_code`, the Phone Setup render), `handlers/devices.rs` (the Calls & Contacts row,
  `call_problems`), `attention.rs` (`facts`);
- `device_calls.html`, `provisioning_settings.html`, `device_detail.html`, `partials/note_row.html`,
  `partials/note_warning.html`, the CSS and head diffs; `static/auto-reload.js`, `scroll-restore.js`, `autosave.js`;
- the launcher's `CallRules.kt` (`homeContacts`, `PhoneBookView.home`), to check what a contact row's value claims;
- every changed test; `tests/calls.rs` in full; `jstest/auto-reload.test.js`; the docs diff.

Line numbers are `552d3eed`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge; the fix round is small.** The step does what §8 step 8 asks:
- the route is unchanged and inside the admin router's auth, CSRF and session layers; the phone always comes from the
  path. Every contact write is scoped `WHERE device_id = ? AND contact_id = ?` (flags) or checked with
  `contact_on_device` first (photo upload, photo removal), so a contact id from another phone writes nothing; an unknown
  phone is a 404; a contact that is gone lands on `#contacts` (with "nothing was saved" for a save and an upload);
- the forms (H1, M6): the switches and the Message button are one form, each contact is one form (switches, picker,
  Matrix ID), photo and photo removal are their own forms outside it (no nested forms), Remove Contact… is a `<details>`
  confirm naming the contact and the phone. The redirect table is built from the rendered forms, checks each fragment
  is the form's own element, and checks every auto-saving form's `<noscript>` Save;
- refusals render in place (400), the form open, what was typed kept, the reason under the field with `aria-invalid`
  and `autofocus`, and every action carries its row's fragment, so the answer lands on the form with or without JS:
  Add Contact (`#add-contact`), a contact's Matrix ID and photo (`#contact-<id>`), the country code (`#country-code`);
- no contract change: the policy builder is untouched, `policy_json_keys_snapshot` and the phone-number vectors pass,
  `phone_vectors.json` and `phone.rs` are untouched; photo limits and type checks are the same calls
  (`photos::process_limited`, the route's body limit unchanged);
- `call_report` is typed: every old warning's condition maps to exactly one `Finding`, in the same order, with the same
  guards (I compared all 16 against `2eee4de1`; the only parse change, `parse_db_utc`, accepts a superset). Each
  `Finding` gives its `CallProblem` 1:1, so the device page's Attention rows are raised for exactly the same conditions
  as before. The visible text has no package, adb, role, UTC or raw error; those sit behind the ⓘ, escaped. Times are
  in Phone Setup's zone, report times with their age;
- names, numbers, Matrix IDs and the phone's error are askama-escaped (pinned with `<b>Mor</b>`, a `"><script>` Matrix
  ID and an `<img onerror>` error).

On the choices the review was asked to look at:
- **(5) `auto-reload.js`, generalised.** Step 6's QA fixes all still hold: a capturing `submit` and an autosave `change`
  stop it for good (nothing re-arms it), `pagehide` too; `?notice=` is stripped with `replaceState` and the hash kept;
  the calls page and the Apps page both leave it out of a refusal's 400 render (`auto_refresh` needs `refused.is_none()`
  and `add_error.is_none()`; Apps' `installing` needs `entered.is_none()`). Holding on any `details[open]` is right: on the
  calls page a contact's form, Add Contact, a confirm and every ⓘ are `<details>`, so nothing typed or opened is lost.
  On the Apps page the only server-opened `<details>` is a refused Name and icon form, whose page has no reload, so the
  wider selector can't freeze an install's percentage. 3 s instead of 5 s is fine: the calls page only reloads for 2
  minutes after "Manage calls and SMS" changes (`ROLES_REFRESH_SECS`), about 40 cheap renders. The jstest is complete
  for what the script does (each stop, the hold, the strip); the hold's test keys on the selector string, which is
  enough (narrowing the selector fails it). One thing it doesn't cover is where the reload lands (M1).
- **(4) No `back` on `/settings/calls`.** Fine. §5.1's rule 2 ("a form's POST redirects to the one page its form lives
  on, with no extra field") applies once one page posts there; `provisioning` was the table's only value and its
  default, so the field could only ever say what the route already does. An old Calls page's `device_id` is ignored and
  lands on Phone Setup, which is harmless. §5.1's table and §8's L6 line still say `back=provisioning` (N1).
- **(1)** No fragment on the settings form, a fragment on each contact's: right, and consistent with step 14. Without
  JS, the landing is the page top, where the title and any warnings sit above the switches; with JS, scroll-restore
  keeps the place.
- **(3)** A gone contact is not a 404: right; a second tap on Remove and a stale tab both land on the group.
- **(6), (7)**: right; "waiting" and the screening hint are states, not faults, and an unmanaged phone's contacts reach
  no phone.

What is left:
- **M1:** after "Manage calls and SMS", each auto-reload lands at the top of the page.
- **L1:** the Waiting row promises "This page reloads until it's in", which is true for 2 of its 5 minutes, never
  without JS, and not while its own ⓘ is open.
- **L2:** a contact row reads "Calls in only · Home", but the launcher shows a Home button only for phone-book
  contacts.
- **L3:** two guards are unpinned (mutations survived): another phone's contact's photo removal, and the escaping of
  Add Contact's refused name and number.

Counts: 0 High, 1 Medium, 3 Low, 4 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-8`), under `~/build.lock`.
- **`cargo test`, unchanged:** 812 passed, 0 failed.
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` dead fields, `dns_engine.rs`,
  `storytel.rs`, the `sort_by` in `devices.rs:1365`). None is in the step's code.
- **`node --test server/jstest/*.test.js`:** 23 passed.
- **The mutations.** Each was run with targeted filters, then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | photo upload goes on for a contact that isn't on the phone | `launcher_ui::bad_uploads_change_nothing` |
  | 2 | Remove Contact… on an already removed contact is a 404 | `remove_detaches_and_deletes_orphan` |
  | 3 | a refused Matrix ID shows the stored ID, not what was typed | `contact_flags_are_scoped_to_the_device` |
  | 4 | `auto-reload.js` without the `submit` stop | `jstest/auto-reload` "a submitted form stops the reload" |
  | 5 | the hold narrowed to `details.edit[open]` | `jstest/auto-reload` "an open disclosure … pauses" |
  | 6 | the hold removed (`if (false)`) | `jstest/auto-reload` "an open disclosure … pauses" |
  | 7 | a refused contact's 400 page loads the reload (`refused.is_none()` dropped) | `step9::an_error_page_never_reloads_itself` |
  | 8 | a contact's save returns without `#contact-<id>` | `every_form_on_the_calls_page_returns_to_its_place`, `contact_flags_are_scoped_to_the_device` |
  | 9 | the country code's group renamed (`id="phone-numbers"`) | `settings_pages::every_form_on_the_settings_pages_returns_to_its_group` |
  | 10 | the country code's refusal shows "47" instead of what was typed | `default_country_code_is_validated_and_used` |
  | 11 | a contact's name `|safe` in its row | `the_calls_page_escapes_what_it_shows` |
  | 12 | Add Contact's refused name and number `|safe` | **nothing** (L3) |
  | 13 | "A call may have rung unanswered" in UTC (`at.format("%H:%M")`) | `calls::tests::findings_read_as_short_local_notes` |
  | 14 | `problems()` drops `CallLogUnreadable` (an Attention row lost) | `attention::every_call_warning_is_typed` |
  | 15 | photo removal goes on for another phone's contact (`Ok(Some(_))`) | **nothing** (L3) |

  Two of fifteen survived (L3).

## Medium

### M1. After "Manage calls and SMS", each auto-reload lands at the top of the page
`static/auto-reload.js:42`, `static/scroll-restore.js` (`install`), `src/handlers/calls.rs:929-975`.

The reload only runs after the Manage switch changes (`roles_changed_at`), and that save returns to
`/devices/{id}/calls` without a fragment (choice 1). Scroll-restore then restores the offset and sets
`history.scrollRestoration = "manual"`. That setting belongs to the history entry, and `location.reload()` reloads the
same entry. So 3 s later the browser does not restore the scroll position; the reloaded page's scroll-restore has
nothing saved (it removed its record) and does nothing. The page shows its top. The same happens on every reload for
2 minutes. A parent who toggles Manage and then scrolls down to read the contacts (without opening one) is pulled to
the top every 3 s. That breaks the user's rule in effect, if not in letter: the save itself lands in place, and the
page jumps on its own 3 s later. The `<meta refresh>` it replaces did the same, and qa-31-s6-code N1 saw the same
mechanism on the Apps page (rare there; here it follows every Manage change). I reasoned this from the code and the
History API's rules. There is no browser on this VM, so the step's owed browser pass should confirm it.

**Fix:** in `tick`, just before `win.location.reload()`, either set `win.history.scrollRestoration = "auto"` (the
browser restores the entry's position on reload), or write scroll-restore's own record
(`sessionStorage["handy-scroll"] = {path, y: scrollY, t: now}`) so the reloaded page restores it the usual way. The
second works the same in every browser and is easy to pin in `jstest/auto-reload.test.js`: a fake window scrolled to
y = 800 reloads with the record set.

## Low

### L1. The Waiting row promises a reload it often doesn't do
`src/handlers/calls.rs:466-477` (`Finding::Waiting`'s note), `:36-38`.

The sentence behind the "Waiting for the phone to confirm" row ends "This page reloads until it's in." In practice:
- the reload stops after 2 minutes (`ROLES_REFRESH_SECS`), while Waiting lasts 5 (`ROLES_PENDING_SECS`);
- without JS nothing reloads (the `<meta refresh>` did, so the sentence was true for the no-JS page before);
- the sentence sits behind the row's own ⓘ, a `<details>`. While the parent reads it, the reload is on hold.

So in minutes 2-5, and whenever someone reads it, the page says it reloads and it doesn't. The parent waits for
nothing. The old text didn't promise this.

**Fix:** drop the second sentence. Or say what is true in every case ("Reload the page in a minute."). The rule's
5 minutes and the reload's 2 can stay as they are.

### L2. "Calls in only · Home" claims a Home button the phone doesn't show
`src/handlers/calls.rs:104-117` (`contact_summary`), `tests/calls.rs:1218`.

The launcher puts a contact on Home only when it is also in the phone book:
`homeContacts = contacts.filter { it.outbound && it.showOnHome }` (`launcher/…/calls/CallRules.kt:57`), and
`PhoneBookView.home` filters a list that is already the phone book. A contact with Can call on, In the phone book off
and On the home screen on gets the row value "Calls in only · Home", but the child sees no button. The footer's ⓘ
says it right ("On the home screen: also a call button there"). The row value is new in this step, and the escaping
test pins the wrong text.

**Fix:** `if home && outbound` in `contact_summary`; change the test's expected value to "Calls in only" and add the
case to `a_contact_row_says_which_way_calls_go`. Leaving the switch as it is is fine (the flag is kept for when the
contact goes back into the phone book).

### L3. Two guards are unpinned
`src/handlers/calls.rs:1325-1341` (`remove_photo`), `templates/device_calls.html:141,144`.

- **Mutation 15:** `remove_photo` accepting `Ok(Some(_))` (another phone's contact) passed every test. The guard is
  correct, but nothing checks that `/devices/{a}/contacts/{b's contact}/photo/remove` leaves the photo, nor where it
  lands. The upload has both checks (`bad_uploads_change_nothing`). The gap is older than this step, but the step
  rewrote the match and gave it a new landing (`#contacts`).
- **Mutation 12:** `|safe` on Add Contact's refused name and number passed. The refusal echoes what was typed into
  `value="…"`. Askama escapes it today, and CSRF makes it hard to reach, but the test that covers the refusal
  (`invalid_contact_input_is_400_with_no_row`) types only plain text.

**Fix:** in `bad_uploads_change_nothing` (or `contact_flags_are_scoped_to_the_device`), give the stranger a photo,
post `photo/remove` from the other phone, and assert the hash is still there and the redirect is
`/devices/{id}/calls#contacts`. In the Add Contact refusal table, add a name of `"><b>x` with a bad number, and
assert that `&#34;&#62;&#60;b&#62;x` is in the form and `"><b>` is not.

## Nits

- **N1.** §5.1's table (`/settings/calls` | `provisioning` | `/settings/provisioning`) and §8 step 8's "with
  `/settings/calls` taking `back=provisioning` (L6)" still describe the field that choice 4 dropped. One line in each
  ("one page posts here; no `back`") keeps the spec true.
- **N2.** A refused Matrix ID for a contact that another tab removed meanwhile renders the page at the action's
  `#contact-<id>`, which no longer exists, so it opens at the top, with the notice down at `#contacts`. Rare. Pointing
  the render at `#contacts` isn't possible from the server (the fragment is the form's), but the notice could also
  show at the top of the page in that case. Or leave it.
- **N3.** Forms for a gone contact disagree slightly: a save and an upload say "That contact was already removed, so
  nothing was saved.", photo removal says nothing (`calls.rs:1337`, `contacts_group(id, false)`). Removal has nothing
  to undo, like Remove Contact…, so this is defensible. The status text ("A form for a contact that isn't on the phone
  any more → `?notice=contact_gone`") reads as if it covers every form.
- **N4.** When `call_report` fails on the device page (`devices.rs:1485-1491`), Attention silently has no call rows;
  the old card said "Couldn't read this phone's call state". A summary should degrade, and the log has it, but
  "Couldn't read" on the Calls & Contacts row (as for the contact count) would keep the parent from reading the empty
  Attention as "all good".
