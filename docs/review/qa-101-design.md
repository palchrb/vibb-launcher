# Design QA: design 101, the summary page as mockup A and the app's logo in its band

Reviewed: `docs/design/101-summary-page-a.md` at `design-101` 30ea7bcd3, against the code on that branch (server only).
Read-only review; no build or tests run (the design adds no code yet).

**Verdict: no High. 7 Medium, 11 Low.** The direction is sound and stays server-only: no launcher change, no
API or contract change, no migration (`parent_event_reads` and `parent_events::UNREAD` already hold the per-parent
read state, and `time_rules`, `time_lifts` and `time_state.zone` already hold what "Neste" needs). Every file:line
the design cites checks out to within a line or two. The Medium items are one wrong number, two rule gaps in the new
tile lines, a CSS selector that does not do what it says, a line-wrap rule that can scroll the page sideways, and two
gaps in the sheet's keyboard handling and the Activity page's "new" state.

---

## Medium

### M1. The picture's offset is -52 px, not -36 px (§2.1). The test would pin the wrong number

`.sheet` has `border-top: 1px` and `padding-top: 15px` (`style.css:623-627`), and they don't collapse with a child's
margin. With `.summary-sheet { margin-top: 38px }` the sheet's edge is 16 px under the band's box (band `margin-bottom:
-22px`, `style.css:479`), and its content edge is 32 px under it. A head at `margin-top: -36px` puts the picture's top
4 px *under* the band's box. That is 18 px below the band's solid part, with 20 px of the picture over the sheet's edge
and 68 px below it. The design's own figures ("2 px below the solid part", "36 px above the edge, 52 below", "10 + 38
+ 52 + 12 = 112 px") all need **-52 px** (32 − 52 = −20, inside the band's 22 px foot). Change the number in §2.1 and in
§8's shell test. With -36 the picture floats in the gap instead of lying half over the edge as in the mockup.

### M2. `white-space: nowrap` on every part can make the summary scroll sideways (§3.2)

`.summary-state > span { white-space: nowrap }` treats each `Part` as a unit that can't wrap. But the lock is one part
whose text is `LockBadge::words` joined with " · " (`time_state.rs:177-205`), for example "Fotballtrening til i morgen
07:00 · anrop av", or "Leggetid · anrop av · ved siste melding (i går 21:40)". The zone part is "{zones} siden {at}"
with names the parent types (`nb.json:511`). A single part like that is wider than the 288 px column at 320 px. Main
is `overflow-y: auto` (`style.css:423-430`), so its x overflow becomes auto too, and the summary scrolls sideways.
**Fix**: make each part `display: inline-block; max-width: 100%` with normal wrapping. A part then moves to the next
line as a whole and wraps inside only when it alone is too wide. Keep the `.bat` icon in the same box as its battery
text (today it is a sibling span, `status_line.html`), so the icon never ends one line and its percent starts the next.

### M3. "Neste" handles a pause wrongly: a paused start is delayed, not skipped (§4.2)

On the phone, a rule lift stops the rule only while the lift runs (`TimeRules.kt:159` `lifts(rule)`, with
`liftRecordActive` in `ScreenTime.kt:142`). A window that starts inside a pause therefore locks when the pause ends,
if the window is still running then. `next_start` as specified skips that start. Example: at 19:00 a parent pauses all
rules for 2 h (`time_lifts.rule_id IS NULL`, `quick_actions.rs:395`), and Leggetid runs 20:30-07:00. The design skips
20:30 and looks for tomorrow's 20:30, which is 25.5 h away, past D9's 24 h. The tile then says nothing or names
another rule, while the phone locks at 21:00. **Fix**: when a pause (of that rule, or of all rules) covers a start,
the effective start is the pause's end if the same window still covers that minute, else skip. Add a vector for that
case to `lock_now::tests::the_next_start`.

### M4. "Ingen regel nå" can say no rule while one has started since the report (§4.1)

"Fresh" in `lock_badge` (`time_state.rs:327-334`) means "today's report, the phone online or away ≤ 15 min". For an
online phone, a report of any age passes. That is safe for the badge because `provable_end` adds the opposite check:
a lock whose end has passed makes the report stale (`time_state.rs:337-341`). "Ingen regel nå" has no such check.
The launcher re-checks at each rule boundary (`docs/notes/launcher/time-rules.md:89`), but nothing I found sends a
status report at that edge. A report sent at 20:25 saying `NONE` can therefore stand past Leggetid's 20:30, and the
tile states something false at the moment a parent looks. **Fix**: say "Ingen regel nå" only if, in addition, no
effective rule's window covers `now_local` in the phone's zone (`time_state::covering_ends` over
`served_days(parse_days(..))`) once running pauses are taken out. If the schedule says a rule is in force and the
report says none, leave the line empty. This uses the same inputs as `next_start`, so it costs one loop. Also add a
`report_stale` case for a report without a `day`: the badge treats it as fresh (`report.day.is_some_and(..)`), but
§4.1 says "today's".

### M5. The band tile's `:only-child` selectors don't separate the logo from the glyph (§7)

`:only-child` counts only elements. In the logo tile (`…{{ tile.letter }}<img …>`) the letter is a text node, so the
`<img>` *is* `:only-child`, just as the glyph tile's is. `.band-who > .app-row-tile img:not(:only-child)` therefore
matches nothing. `.band-who > .app-row-tile > img:only-child { 17px }` (specificity 0,3,1) matches the logo too and
beats `.app-row-tile.app-row-logo img { width/height: 100% }` (0,2,1, `style.css:2149-2155`). Every real logo would
draw at 17 px in a 28 px tile, over its letter. **Fix**: key the rules on the class:
`.band-who > .app-row-tile:not(.app-row-logo) > img { width: 17px; height: 17px; }`. `:not(.class)` is fine at the
browser floor. `app_logos.rs::the_pages_show_the_logos` should check the logo tile's class, not only that the `<img>`
is there.

### M6. The picture's sheet looks modal but neither Escape nor the focus treats it as one (§2.3)

The panel dims the whole screen over the tab bar (z 11/12) and reads as a sheet. But `confirm.js` only handles clicks
(`confirm.js:12-21`), and a `<details>` doesn't close on Escape by itself. A keyboard user who opens it can't leave
with Escape. Tab also walks past Avbryt into the page under the dim while the sheet stays open. The repo's one real
modal does both (`photo-crop.js:40, 606-609`: inert, Tab cycles, Escape cancels). **Minimum**: in the same small
`confirm.js` addition, Escape on an open `details.pic-menu` closes it and focuses its summary (`preventScroll`), and
a `focusout` that leaves the panel closes it (as a menu does). Keep `role="group"`, not `dialog`: without a focus
trap it must not claim to be modal. Also: `toggle` doesn't bubble, so confirm.js's one-listener-on-the-document
pattern needs `addEventListener("toggle", fn, true)`. Name that in §2.3 and in the `confirm.test.js` case.

### M7. "2 nye" leads to a page that doesn't show which rows are new (§5.1)

The design keeps the Activity page's look ("no visual change"). But `device_activity.html:38-45` draws event rows
with no unread mark, while the inbox draws `inbox-unread` plus `unread-dot` with a spoken word
(`notifications.html:65-66`). A parent who taps "2 nye" lands on a list with nothing marking those two, and leaving
the page clears them. The rows already carry `unread` (`InboxRow.unread`). **Fix**: draw the inbox's dot and its
visually-hidden word on the Activity page's unread event rows (the state before marking, as the inbox does). That is
one template line and one more assertion in the renamed test.

---

## Low

- **L1. Design 100's request card: keep the header the sheet's first child, and drop the `:first-child` coupling.**
  The request mockup (`Foresporsel.dc.html`) puts "Emma ber om mer skjermtid … +15 min +30 min +1 t Nei" under the
  status line and above Hurtighandlinger. That works with 101. But 101's geometry depends on
  `.summary-sheet > .summary-head:first-child`. A card placed "at the top" *above* the header would quietly undo
  the negative margin (and today's `.sheet > .summary-head:first-child`, `style.css:3017`), leaving a 38 px empty night
  gap and the card under the band. State in §2.1 that anything new at the top of the summary goes right after
  `</header>`. Write the rule as `.summary-sheet > .summary-head` (the sheet holds one header), so it can't silently
  stop applying. Nothing else in 101 gets in design 100's way: the picture sheet is out of flow, and a POST from the card
  lands on its own fragment through `scroll-restore.js` as usual.
- **L2. Tests the design doesn't name will break.** List them in §8 so the build doesn't discover them one at a
  time:
  - `zones.rs:842-858`: `lines.last().starts_with("Home since ")`. The one line now starts "Online · …".
  - `power.rs:1054`: `summary.contains("avatar-dot dot-offline")`. Now `state-dot`.
  - `summary.rs:101`: the avatar dot in the header.
  - `summary.rs:817, 947-997`: `data-activity-count` in `an_event_row_shows_its_clock_once` and
    `activity_keeps_a_week_fifty_rows_and_what_can_be_cancelled`.
  - `summary.rs:188-192`: the header's screen-time line.
  - `shell.rs:2194`: pins `confirm.js?v=1`, which §8 bumps to v=2.
- **L3. Unused catalog keys.** Besides `qa.no_limit`, the summary was the only user of `device.activity.count.*` and
  `device.activity.count_more` (`activity.rs:108-115`; the page uses only `device.activity.footer`). Once
  `badge`/`spoken` go they are unused, and `i18n::every_key_is_used` (`i18n.rs:901`) fails. Say they go. The same
  goes for `in_window`/`more` if the page no longer reads them: it doesn't show a count.
- **L4. The words tables leave out the new Activity keys.** Add `device.activity.new` (one/other: "1 ny"/"{count} nye",
  "1 new"/"{count} new") and `device.activity.new_spoken` ("1 ny hendelse"/"{count} nye hendelser", "1 new event"/
  "{count} new events") to §5 with both languages, as §2.4 and §4.4 do for theirs.
- **L5. §4.1 contradicts itself on the screen-time lock.** `no_rule_now` is "true only when … `lock` is `None`", but
  the next sentence says a screen-time lock counts as no rule. That lock is a `Some` badge with
  `name_key == devices.status.lock.screen_time`. Write the condition as "no badge, or the screen-time badge".
- **L6. The app badge lags after Activity marks events read.** The home-screen badge comes from `[data-unread]`
  (`register-sw.js:29-32`), which only the tab pages' bell and the inbox carry. After the Activity page marks events
  read, the icon keeps the old number until the next tab page. Either put a hidden `data-unread` (the count after
  marking, as `notifications::page` computes it) on the Activity page, or accept the lag and say so in §10. Also
  unread events older than the week are neither counted nor marked there. That is right, but say it: the bell can
  stay above "2 nye".
- **L7. Focus ring at the iOS 14 floor.** `:focus-visible` arrived in Safari 15.4. On iOS 14,
  `.pic-file:focus-visible + span` is an invalid selector and draws no ring. Use the existing pattern
  (`style.css:3023-3024`): the ring on `:focus`, removed by `:focus:not(:focus-visible)`.
- **L8. `inset: 0`.** §2.3 uses `inset: 0` for `.pic-file` (line 197) and two paragraphs later says `inset` needs
  iOS 14.5 (line 214). Use four longhands for both.
- **L9. Tapping the backdrop on iOS.** Click delegation to `document` from a non-interactive `<span>` is unreliable in
  iOS Safari unless the element has `cursor: pointer`. Give `.pic-backdrop` `cursor: pointer`. Also never give it a
  `display` value: that would beat the `hidden` attribute without JS. To check on the iPhone walk.
- **L10. Details markup.** `details.pic-menu > summary` needs `list-style: none` and `::-webkit-details-marker
  { display: none }` (today only `.info`/`.confirm` have them, `style.css:1198-1206`). The badge's positioned box
  must be the `<summary>` (`position: relative`), which is allowed because it is not an ancestor of the panel. Say
  so, so "no ancestor positioned" isn't read as "nothing positioned".
- **L11. Wording.**
  - §2.1: "the ring separates the picture from the night in light and dark alike" doesn't hold in dark, where `--bg`
    is `--night` (`style.css:103, 142`). The ring is invisible there, which is fine; correct the sentence.
  - D8's note is true for phones (`pwa.md` "A phone's own picture": never sent to a phone). The file is served
    without a session at an unguessable `/phone-avatars/{hash}`, so "Bildet sendes ikke til telefonen" is the exact
    claim. Either wording is acceptable.
  - §1.4 and §5.1: `parent_events::mark_read` takes a JSON array string (`parent_events.rs:1478-1482`), not a list.

---

## The D-items

| | Verdict |
|---|---|
| D1 dot into the line | Sound. The Devices list keeps its avatar dot (`avatars.rs:199`, `devices_list.rs:97-109`), so only the summary changes. |
| D2 "{used} brukt i dag" without a limit | Sound. The header no longer says the time used, so without it the summary would say nothing of screen time. `screen_time()` already gives `None` for no budget and 0 min (`time_state.rs:288-297`), so the tile stays empty then. |
| D3 the lock stays the lead | Sound. It is the one place that says the lock's end and "anrop av". It is the case behind M2, though. |
| D4 per-parent "nye" + Activity marks read | Sound and matches the inbox. It needs M7 (show which rows are new) and L6. |
| D5 two-line sub-line | Sound. `-webkit-line-clamp` is already used (`.act-label`, `style.css:2595`). |
| D6 band unchanged | Sound on cost (49 templates, the pinned flash numbers). But PLAN's approval text lists "the band's title row tighter", so it must reach the user as a real choice. I can't verify the design's "the user agreed the gain is small". |
| D7 sheet also without a picture | Sound. It keeps one behaviour. |
| D8 the note | Sound (see L11 for the exact wording). |
| D9 24 h horizon | Sound, provided M3 is fixed: a pause can't then push a same-evening start past the horizon. |

## Checked and fine

- The picture sheet sits right. `body` is `position: relative` and screen-high (`style.css:240-246`). `main` and
  `.sheet` aren't positioned and make no stacking context. The tab bar is z 10, so the panel and dim at z 11/12 lie
  above it, and the same pattern already exists (`.map-box.map-full`, z 3000, `style.css:3704`).
- Opening the sheet shifts nothing: both children are out of flow. `auto-reload.js:34-45` and `pull-refresh.js:77-90`
  hold while it is open.
- `confirm.js` closes any `<details>` that holds a `[data-confirm-cancel]`, a `<span>` included.
  `every_confirm_has_a_cancel` matches only `class="…confirm…"`, so `pic-menu` is outside it.
- Autosave keeps working. `autosave.js` acts on any `[data-autosave]` with a form, and `scroll-restore.js` restores
  the place for forms under `#avatar`.
- The avatar error stays `id="avatar-error" role="alert"` (`avatars.rs:329-334`). "Remove picture" stays absent
  without a photo (`avatars.rs:108`).
- Every lead state in `phone_status.rs` survives the join. `with_roaming` and `with_airplane` work on the
  `Vec<Line>` before `one_line` (airplane replacing line 1 for no-contact and offline, `phone_status.rs:327-370`).
- The tie order `kind_rank` exists (`quick_actions.rs:159-165`). `served_days` maps 23:59 to 0, which
  `covering_ends` reads as running to midnight. A copy never appears in `effective_rule_rows`, so that skip is
  harmless. The phone's zone exists only from launchers that also have `time_rules_v1`.
- The bell rule holds: the summary is `band::sub` and the new `band::app` has no bell.
  `every_shell_page_opens_on_one_band` counts calls, not macro names.
- The app tile's order (logo, glyph, letter) is the list's (`apps_list.html:35-41`).
  `TrackedAppDetailTemplate` has one constructor (`tracked_apps.rs:3693`). `AppTile` doesn't need `pub(crate)`,
  because the template struct lives in the same module.
- `badge-warn` and the orange tokens exist in light and dark (`style.css:43-44, 170-171, 3226`).

## Not verified

- Behaviour on iOS 14 and on a real iPhone: the opacity-0 file input in an absolutely placed panel, backdrop taps
  (L9), and VoiceOver and TalkBack on `<summary aria-label>`. §8 already plans the iPhone walk.
- Whether the launcher sends a status report at a rule's edge (M4). I found no such trigger in the notes.
- Rendering at 320/390 px: these are layout numbers worked from the CSS, not measured.
- `cargo check` was not run: the design adds no code.
