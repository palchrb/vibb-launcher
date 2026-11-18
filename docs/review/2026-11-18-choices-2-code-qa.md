# Code QA - choices-2 (the borderline choices are no warnings), 2026-11-18

Branch `choices-2`, commits f19fcb6c3, d440c2bed, a8f4b1a78, against `origin/main`. Server only. Read-only review;
no `cargo test` run locally (CI: the run on f19fcb6c3 failed only `enrollment::revoking_access_retires_the_token_and_closes_the_stream`,
which a8f4b1a78 updates; the run on a8f4b1a78 was still going when this was written).

Intent (the user, 2026-11-18, "a choice the parent made is never a warning"): seven borderline rows of
`docs/review/2026-11-18-choices-not-warnings.md` made quiet. All seven are implemented as described.

## Checked and fine

- **Access removed** has no other writer: `token_hash = NULL` only in `handlers/devices.rs:717` (`revoke_access`,
  an admin session, the confirm box, a security event naming the admin). Enrolment sets a new token
  (`device_api.rs:99`); New Setup Code doesn't clear it. The Devices list still says "Tilgangen er fjernet" in the
  grey Off tone (`phone_status.rs:424`), the header keeps "Connect again". Nothing wrong is hidden.
- **Play install mode** is started on the phone with the Parent PIN (`parent_pin::Entry::InstallMode`), logged
  (`security::PhoneLine::PlayInstallMode`) and ended from the summary's "Lock again". As a non-warning it now lands in
  `play_rows` (`device_pages.rs:927` partitions on `warning`) and renders as a row with its (i) - quiet, correct.
  Play not suspendable with the block on: nothing shown; block off: `play_openable` behind the block switch's (i).
  `RestartOnce` stays a warning. Nothing in Play's state blocks the kid's updates.
- **Spotify shared**: `Note { warning: false, ..warning(..) }` through `note_warning.html` gives `class="attention"
  data-note`; the extra `COUNT(*)` read is gone from `spotify_account_warnings`.
- **Storytel unsent**: `storytel_unsent_orange = policy.music_storytel` - the line exists only when ticked and
  (switch off or no login), so orange means exactly "switch on, no login". **Spotify unsent** (`spotify-off`) exists
  only while unbound (`music.rs:5242`, "Spiller som: Av"), so always quiet is right.
- **Assistant role**: every use on the phone is the kid's Flymodus (`AirplaneRuntime`; the rule sheet's and the lock's
  rows need bit 64 and the role, `LockActivity.kt:138`; Snarveier's `airplane` item is gated by bit 64 in
  `shortcuts.json`). `assistant_needed()` = bit 64 covers it.
- **Weather**: `needs_position` is pure policy (`weather_page.rs:64`: on, location not Jevnlig, no zone) - a choice.
  `needs-update` stays a warning.
- **Devices list lock**: `LockBadge` is only a rule or the screen-time budget in force (`time_state::lock_badge`) -
  never a fault. Low battery stays `Tone::Warn`.
- **Tailnet**: `Via::Public` is the phone reaching a public https address the parent configured; quiet is right.
- **i18n**: nb.json and en.json have identical key sets; the six removed keys are referenced nowhere (src, templates,
  static, notes). `device.advanced.play.block_off` is still used (the (i)).
- **Templates**: `{% if play_openable %}`, the `storytel_unsent_orange` class, the two `data-note` blocks are valid
  askama; `class="attention"` alone is the grey level (`style.css:1422`).
- **In-place refresh**: `data-note` is a `SOFT_KEYS` key (`swap.js:34`); the new values `access-removed`,
  `needs-position`, `play-openable` are unique on their pages, and a warning turning into a note changes the node's key
  (`DIV[data-warning=…]` → `DIV[data-note=…]`), so it is replaced, not patched.
- **Anchors**: no form or redirect changed; the block switch still posts to `#app-screens`.
- **Tests** assert real things: About's two value classes flip with the mask/clock (`tests/play.rs`,
  `tests/airplane_mode.rs`), the step9 test checks the (i) sentence with the block off and its absence with it on, the
  lock part's tone and hook (`phone_status.rs:661`), `data-note` vs `data-warning` on the weather, Spotify and
  access-removed notes, Storytel's orange on/off.

## Findings

### M1 - `write_settings_needed()` misses the Sound bit: the kid's ringtone and message-sound picks need the grant

`server/src/airplane_mode.rs:313`. The rule is "touch switch (bit 16) or a clock format". Since 24b832fea
(2026-11-04) the launcher's Sound bit (8) also shows "Ringetone" and "Meldingslyd" in Kid Settings, written with
`RingtoneManager.setActualDefaultRingtoneUri`, which needs "Modify system settings"
(`launcher/.../ui/kidsettings/KidSounds.kt:22-41,103`; `KidSettingsActivity.kt:456,627-635`: without the app-op both
rows at 50 %, not clickable, no chevron).

Failure: a phone with the default mask (bit 8 is in `DEFAULT_QUICK_CONTROLS`), touch off, clock "system" and no grant:
About's "Endre systeminnstillinger: Ikke tillatt" is now quiet and the Oppsett card has no step, while the kid's two
sound rows are greyed out - a feature the parent turned on that doesn't work. (The Oppsett step had the same gap
before; this branch makes the same rule decide About's colour too.) The row's sub-line
`device.about.write_settings_sub` ("Bryterne for berøringslyder og klokkeformatet trenger den.") also leaves the
sound picks out.

Fix: add `|| policy.quick_controls_mask & QUICK_CONTROL_SOUND != 0` and name the ringtone in the sub-line (nb/en), with a
`tests/play.rs` row `(8, "system", true)`. Note this makes the row orange on most phones without the grant, since bit 8
is a default - if the user would rather treat the read-only sound picks as a quiet degradation, say so in the
function's doc instead. Either way it's a choice for the user, not a silent gap.

### L1 - A new Spotify client ID unbinds every phone, and nothing orange says so

`server/src/spotify.rs:1006-1013` (`remove_all`, "a new client ID removes the accounts only") sets every phone's
`music_spotify_account = NULL`; the only per-phone trace was the "Plays as" row's orange sub-line
(`device_music.html:180`), now always quiet. With the app row still there, "Ingen Spotify-app er satt opp" doesn't fire
either. It's the parent's own action, but the consequence (log in again, bind again) is a step left, not the choice
itself. Fix (optional): keep `spotify-off` orange while ticked entries exist and accounts exist but none is bound, or
accept it - the Spotify page itself shows no accounts.

### L2 - The block switch's (i) repeats its own label

`server/templates/device_advanced.html:208` puts `device.advanced.play.block_off` ("Blokker andre appers skjermer er av,
så apper kan åpne Play Butikk.") inside the (i) of the switch named "Blokker andre appers skjermer" - against "no
sentence that repeats the label above it". Fix: a sentence of its own, e.g. nb "Play kan ikke settes på pause på denne
telefonen, så apper kan åpne Play Butikk." / en "Play can't be paused on this phone, so apps can open the Play Store."

### L3 - Stale comments and notes

- `server/src/handlers/music.rs:4564`: `SpotifyBindingCard.note` "the row's orange sub-line" - now plain.
- `server/src/handlers/music.rs:4675`: "Three small reads (the app, the account, the phones bound to it)" - two now.
- `docs/notes/server/pwa.md:1628`: "The lock is orange text now (`tone-orange`)" - plain since this branch.
- `docs/notes/server/calls-and-contacts.md:19`: "The group warns when the phone reports `play_store_suspendable:
  false`" - now nothing with the block on, a sentence behind the (i) with it off.

### L4 - No test pins "Utenfor tailnettet" as quiet

`server/src/handlers/device_pages.rs:530` dropped the `row-value-action` class for `Via::Public`, but
`tests/edge.rs::about_shows_the_last_access` only checks the tailnet case. Fix: after the public requests (before the
tailnet one) assert the Network row's value has no `row-value-action`.
