# Code QA: security round 2 (branch `sec-round-2`), 2026-11-18

Read-only review of `git diff main...sec-round-2` at `f6da81396` (eaff67d5b, 278e886ac, 71bba30c4, and f6da81396,
which took the rule-pause cap back out by the user's choice: "no cap, only the notice"). No build, no tests run (CI
green). Findings: **0 High, 2 Medium, 5 Low**.

## Medium

### M1. A parent's safety change past 20 an hour on one phone is neither stored nor pushed

**Resolved 2026-11-18:** `Kind::parent_made` (app, contact, location, certificate check off) skips the budget; a phone pause's `pin_used` past it is stored without a push. Tests: `safety_changes::a_parents_changes_are_never_past_a_phones_budget`, `rule_pause::a_pause_past_the_hourly_budget_is_still_told`.

- **Where:** `server/src/parent_events.rs:1288-1316` (`raise`: `EVENTS_AN_HOUR = 20` per phone and kind, then
  `return Ok(None)`), hit by `app_added`, `contact_added` and `location_regular` (all carry `device_id`), and by
  `Phone::rule_pause` (`pin_used`).
- **What:** the per-phone budget was made for events a phone raises (security review L1: a leaked token can't bury
  the inbox). The new kinds are raised by a parent session, and the budget drops them silently. The log line also
  says "a phone's events", which is wrong for these kinds. A misused session (the threat in privacy review M2) can turn a
  harmless app off and on 20 times, then allow the real one. That one has no inbox line and no push. A parent who
  sets up a new phone with more than 20 apps in an hour also loses the rest without a word. The rule pause is the
  same: the user chose "told, not capped", but after 20 `pin_used` rows in an hour a further pause is still stored
  as a lift (it lifts the rule) and is not told.
- **Fix:** let `raise` skip the store budget for the parent-made kinds (`AppAdded`, `ContactAdded`,
  `LocationRegular`). A session limits them, and they are rare. Past the budget, a `pin_used` from
  `Phone::rule_pause` should still be stored with `Push::No` and counted into a digest (as `budget_digest` does),
  not dropped. Test: 21 app toggles give 21 rows, and the 21st phone pause in an hour still gives a row.

### M2. `update-image.sh` accepts an attestation from any workflow in any repo of the account

**Resolved 2026-11-18:** pinned to `--repo palchrb/vibb-launcher --signer-workflow .../server-release.yml --source-ref refs/tags/server-vX.Y.Z --deny-self-hosted-runners` (the release builds and attests on GitHub-hosted runners only); the overrides are gone; DEPLOY.md "Updating" says what a pass means.

- **Where:** `server/deploy/update-image.sh:43` (`gh attestation verify ... --owner "$OWNER"`), and `:15-16`
  (`HANDY_IMAGE`/`HANDY_OWNER` overrides from the caller's environment).
- **What:** `--owner palchrb` only proves that some GitHub Actions run in some `palchrb/*` repo signed the image. A
  token that can create a repo and run Actions (`repo` + `workflow` scopes) is part of H1's threat. With it, an
  attacker can build an image in a throwaway repo, attest it with `actions/attest-build-provenance`, push it to
  `ghcr.io/palchrb/handy-server:X.Y.Z`, and the check passes. The release workflow's own gates (the `release`
  environment's approval, the minisign job that `server-release-image-merge` needs) are never required. Separately,
  `HANDY_IMAGE`/`HANDY_OWNER` change what is verified, but `compose.yaml` always pulls
  `ghcr.io/palchrb/handy-server@<digest>`. An inherited `HANDY_OWNER` therefore weakens the check without changing what runs.
- **Fix:** pin the signer:
  `gh attestation verify "oci://$IMAGE@$digest" --repo palchrb/vibb-launcher --signer-workflow palchrb/vibb-launcher/.github/workflows/server-release.yml --source-ref "refs/tags/server-v$version" --deny-self-hosted-runners`.
  Drop the two overrides, or make compose use the same `${HANDY_IMAGE}`. Say in DEPLOY.md "Updating" what a pass
  now means: the release workflow ran for that tag.

## Low

### L1. After a download, the form still holds the used passkey assertion and the typed password

**Resolved 2026-11-18:** `static/backups.js` (`data-download`) empties the password and the assertion after the submit, puts `required` back and closes the `<details>`; a submit passkey.js stopped is left alone.

- **Where:** `server/templates/backups.html:39-48`, `server/static/passkey.js:332-352`.
- **What:** Download is the first re-auth form whose success doesn't leave the page (a zip attachment). After
  "Confirm with passkey", the hidden `assertion` keeps the used assertion, and the password field is empty with
  `required=false`. A second Download from the same page sends that assertion again. The challenge is gone, so the
  attempt is logged as a passkey failure and counted as a wrong re-auth (`record_reauth(false)`). Repeated, this can
  reach the sign-out. After a password download, the password stays in the field on a page left open.
- **Fix:** a few lines in `passkey.js`, or a `data-` hook on the download form: right after its submit (next tick),
  empty `assertion` and `password`, set `required` back on the password, and close the `<details>`.

### L2. `set_env` can merge `HANDY_DIGEST` into the last line of `.env`

**Resolved 2026-11-18:** a newline is added first when `.env` doesn't end in one.

- **Where:** `server/deploy/update-image.sh:54`.
- **What:** an existing install's `.env` has no `HANDY_DIGEST` line, so the script appends one. If the file doesn't
  end in a newline, the line joins the last setting (e.g. `LAUNCHER_SIGNATURE_CHECKSUM=...HANDY_DIGEST=sha256:...`).
  Compose then refuses on `:?`, after `.env` has already been corrupted.
- **Fix:** before appending, `[ -s .env ] && [ -n "$(tail -c1 .env)" ] && printf '\n' >>.env`. Or write both keys to
  a temp copy and `mv` it into place once both are set.

### L3. The digest is taken from the first `"digest"` in the JSON

**Resolved 2026-11-18:** `jq -r '.digest // empty'`, as the workflow; jq is a requirement.

- **Where:** `server/deploy/update-image.sh:36`.
- **What:** `grep -o ... | head -n 1` relies on the index's own `digest` being printed before `manifests[]`. The
  release workflow reads it with `jq -r '.digest'` (`server-release.yml:481`). It fails closed (a platform digest has
  no attestation), so this only matters if buildx changes its field order.
- **Fix:** read it the way the workflow does (`jq -r .digest`, with jq added to the requirements). Or hash the raw
  index (`docker buildx imagetools inspect --raw ... | sha256sum`) and compare the two.

### L4. Existing installs keep Watchtower and `latest` until the host's files change

**Resolved 2026-11-18 by the user's change of direction:** Watchtower stays a supported way (the operator's own risk), and the verified script is offered beside it, so there is nothing to move: DEPLOY.md "Updating" names both, and the review line says so.

- **Where:** `server/DEPLOY.md` "Docker"; `docs/review/privacy-review-2026-11-17.md:93` ("Server half fixed").
- **What:** the fix protects a host only once it has the new `compose.yaml` and `update-image.sh`, and Watchtower no
  longer follows `latest` for this container. The user's own VM runs Watchtower (project memory). DEPLOY.md gives a
  first install, but no steps for moving an existing one, and the review line reads as already done.
- **Fix:** a short "Moving from Watchtower" note in DEPLOY.md: copy the two files, run `./update-image.sh X.Y.Z`, check
  `docker compose config | grep image`. A PLAN queue item for the host step. Change the review line to "fixed in
  code; live when the host moves".

### L5. The restore's two events don't share a key or a time, though the comment says they do

**Resolved 2026-11-18:** the marker's fourth line carries the asked-at instant; `record_restore` keys and times the restored database's event by it (the one pushed before the restart has the same key). Test: `safety_changes::a_restore_asks_for_the_password_and_is_told_in_both_databases`.

- **Where:** `server/src/parent_events.rs:2769` (doc: "Raised twice with one key"), `server/src/startup.rs:99`
  (`chrono::Utc::now()`).
- **What:** the restored database's line is keyed and timed at the restart, not at the ask. Nothing is
  double-counted (they are two different databases), but the inbox shows the restart's time and the comment is wrong.
- **Fix:** carry the asked-at instant as a fourth marker line (`restore_marker`, parsed like the others and ignored
  when it is missing or garbled) and use it in `record_restore`, or correct the comment.

## Checked, nothing found

- **Rule pause (after f6da81396):** `rule_pause.json` is byte-identical in both copies and equal to main's, so the
  launcher's handling is unchanged. A retry inserts nothing and raises nothing (`inserted` is empty). The event key
  `rule_pause:<end>` uses `body.rules`' latest `until_ms`. That equals the gate's own `until_ms`
  (`ParentPin.kt:164`, `WindowStart(plan.rules.maxOf { it.untilMs })`, `ParentPin.kt:282`), so one pause gives one
  row whichever post comes first. The unique index is `(device_id, kind, dedup_key)`. Two distinct pauses with the
  same end in milliseconds on one phone can't happen in practice.
- **Backups re-auth:** the GET route is gone (now a 405). The POSTs pass the global `Sec-Fetch-Site` CSRF check.
  `reauth_route` allows `/backups/upload` and `/backups/{safe}/download|restore` only, and the challenge is bound to
  `Ceremony.route` (the request path). A passkey-only parent can't confirm with a password (`NeedsPasskey`). Upload
  checks the password before it opens or saves the zip. Restore checks the name, then the password, then writes the
  marker. The restart waits at most 10 s on `push_deliveries.status = 'queued'` for its own event, and a DB error
  counts as 0, so the push can't block the restart. Marker parsing: the old one-line marker gives `by: None`; CRLF
  and trimming are handled; control characters are dropped and names cut to 100 characters. The events and log
  carry only the filename and names, never hashes or TOTP.
- **Events once per real change:** `toggle_app` raises only on `INSERT OR IGNORE` `rows_affected > 0` or an
  allowlist that changed (`edit_allowlist` now returns it). It is the only parent write to `device_tracked_apps`;
  the other allowlist writes are automatic (the dialer re-add, `allow_installed_catalog_apps`, the first allowlist).
  Contacts reach `device_contacts` through `add_contact` and `add_family_contact`, both `INSERT OR IGNORE`, so
  re-adds and page saves are silent. DNS: `set_upstream` raises only on `rows_affected > 0` to `custom`; `set_custom`
  only when the choice or an address changed (the same address raises nothing); no address in the event. Location:
  only on `before != interval`. Words are one short line, in nb and en, and name the phone the way the existing
  events do.
- **PWA, no jump:** password and file fields make `scroll-restore.js` leave these forms to the browser. A refusal
  renders open at `#backups`/`#upload` with the reason under the field and autofocus on it. The answer is marked
  `data-post-answer`, so it is never reloaded in place.
- **Deploy:** the script uses `set -euo pipefail` and checks the version and digest with regexes before use. `.env`
  is written only after the check passes. `${HANDY_DIGEST:?}` refuses an empty or missing digest. The first install
  in DEPLOY.md (copy the files, then `./update-image.sh X.Y.Z`) works. `env.example` and DEPLOY.md agree with the
  script.
