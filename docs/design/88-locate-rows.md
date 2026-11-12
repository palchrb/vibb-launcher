# 88 - Locate's lower half as rows

Status: design note, 2026-11-12, on `main` at f4579f2c, branch `locate-rows`. Server only (templates, handlers,
style); no launcher change, no API change, no migration. The user approved the mockup on 2026-11-12 (scratchpad
`locate-rows/contact-sheet.png`) with one change: Location Settings sits in the same group as the other three rows.

## Why

Under the map, a chosen phone's Locate page had grown into a long page: the Locations row, the zones with their
warnings and footer, the two warnings, the three action tiles, Erase Phone…, Requests and Location Settings. The
map and the age line are what the tab is for; the rest is reached less often and now gets a page of its own, in the
PWA's usual grouped list with coloured tiles (as Settings).

## The pages

1. **Locate, one phone** (`/devices/locate?device={id}`, unchanged route): the span pick, the map (the chips with two
   phones or more, the marker, the legend), the age group with Update Location, the "Location is off" warning when
   it applies, then **one group** (`group-icons`, `data-group="locate-rows"`) of four rows:
   - **Locations** (teal tile) - the count in the span, as before ("22 in the last 12 hours") ›
     `/devices/{id}/locations?span=` (today's page);
   - **Zones** (orange) - "3 · Here now: School" (the first zone the phone is in now), "3" when in none,
     "None yet" without zones › `/devices/{id}/zones`;
   - **Find Phone** (red) - "Ring, lock, erase" under it › `/devices/{id}/find`;
   - **Location Settings** (grey) - "Off", "Only when asked" or "Every 15 min" (the setting, not the zones' served
     interval) › `/devices/{id}/location-settings`.

   The old map footer ("The map shows the span picked above…") goes, as in the mockup. The Alle view is unchanged.
2. **Zones** (`GET /devices/{id}/zones`, `locate::show_zones`): the phone band (back to Locate, the phone's avatar at
   the right), then today's zones section as it was: Locate Regularly's warning and button, the zone rows (each to its
   page), "Keep location on" off, Add Zone (or "10 zones is the most"), today's footer. No-store, as every zones path.
3. **Find Phone** (`GET /devices/{id}/find`, `locate::show_find`): the phone band; the wipe-not-ready warning; Ring,
   Stop Ringing, Lock as tiles (`#actions`); **Recent Requests** right under them (`#recent`): today's Requests rows -
   every one still in flight (waiting, sent and not confirmed, erasing), then the newest others up to 3 in all -, the newest highlighted
   (`group-newest`), then **Show all › Activity** to the summary's `#activity` (the summary itself while its Activity
   is empty); then
   Erase Phone… alone in its group at the bottom with today's offline footer and ⓘ (`#erase`).
4. **Location Settings** (`GET /devices/{id}/location-settings`, `locate::show_location_settings`): the phone band,
   today's section as it was (When to locate + Every as one form, Keep history for; both autosave) and its footer.

Every page is 404 for an unknown phone and 500 on a failed read, as Locate. The bell stays on Locate (a tab page);
the three are pages under it, like Locations, with no bell.

## Where the forms land

| Form | Before | Now |
|---|---|---|
| Ring, Stop Ringing, Lock, Erase (right name) | `/devices/locate?device={id}#actions` | `/devices/{id}/find#actions` |
| Erase, wrong name | Locate rendered, confirm open | Find Phone rendered (200), confirm open, at `#erase` |
| Cancel (`back` not `summary`) | `/devices/locate?device={id}&cancel=…#recent` | `/devices/{id}/find?cancel=…#recent` |
| When to locate / Every, Keep history for | `/devices/locate?device={id}#location-settings` | `/devices/{id}/location-settings#location-settings` |
| Locate Regularly (`back=zones`) | `/devices/locate?device={id}#zones` | `/devices/{id}/zones#zones` |
| A zone's add / save | `/devices/locate?device={id}&zone=…&zone_id=N#zone-N` | `/devices/{id}/zones?zone=…&zone_id=N#zone-N` |
| A zone's delete | `/devices/locate?device={id}&zone=deleted#zones` | `/devices/{id}/zones?zone=deleted#zones` |
| Update Location | `?device={id}&requested=1#location` | unchanged; since revision 2 `…#update-location` |

A save never lands at the top: every redirect carries its group's fragment, the pickers autosave through
`form.submit()` with `scroll-restore.js` as every editor. The zone editor's back chevron goes to the Zones page.
Lock Screen's two weather links go to `/devices/{id}/location-settings` and `/devices/{id}/zones`.

## Words (en / nb)

`locate.find.title` Find Phone / Finn telefonen; `locate.find.sub` Ring, lock, erase / Ring, lås, slett;
`locate.find.recent` Recent Requests / Siste forespørsler; `locate.find.all` Show all / Vis alle;
`locate.find.activity` Activity / Aktivitet; `locate.rows.zones_none` None yet / Ingen ennå;
`locate.rows.zones_here` {count} · Here now: {zone} / {count} · Her nå: {zone}; `locate.rows.every_min`
Every {n} min / Hvert {n}. min; `locate.rows.every_hour` Every hour / Hver time; `locate.rows.every_hours`
Every {n} hours / Hver {n}. time. Zones and Location Settings keep their titles (`locate.zones.title`,
`locate.settings.title`). `locate.map_footer` goes.

## Tests

`tests::locate_page`: the one-phone page has the four rows and none of the zone list, tiles, Requests or pickers;
the redirect table is now built from the three pages' forms; the two new pages render with the phone band; a
command lands on Find Phone with its request shown; the settings save lands at `#location-settings`; the repointed
links resolve. Existing tests that read those parts off the Locate page now read the new pages.

## Revision 2: no box under the map (the user, 2026-11-12)

The user approved ("Do it!"): the white box under the map - the age row ("● 1 h ago / Within 100 m") and Update
Location - goes; the avatar marker's time tag and its bubble already say the age and the accuracy.

- **The bubble says what only the box said.** Its first line stays `data-shown-text` ("10 min ago · within 40 m · at
  School": the zone the fix is in, `locate.fix.at_zone`, was already there). After it, each when it applies, from the
  map box's `data-fix-*` (`locate::FixView`, set as text by `static/locate.js`'s `bubble`, `?v=9`): the legend's word
  ("Recent" / "Older position", `data-fix-seen`, hidden for screen readers after the first line), "Hasn't moved since
  14:02" (`data-fix-still`), "Last seen 9 days ago" (`data-fix-aged`), "Last position before the phone went off"
  (`data-fix-before-off`), and the exact time with the provider and "test location" (`data-fix-title`, until now only
  the age's tooltip, now a line of its own). A fix newer than the one rendered keeps its one line ("Newest location").
- **Update Location is the rows' first** (`data-row="update-location"`, a blue tile, the same row look as the others;
  `<form id="update-location">`). Its redirect is `?device={id}&requested=1#update-location`, so the press lands on
  the row, never the top. Under its name: "No position yet." / "No position kept while location is off."
  (`data-fix-none`, the old empty age row's words - with location Off the row still presses, as the button did, and
  says why no position comes), the phone's answer (`data-row="locate-answer"`, its time after a " · ") and, after a
  press, the wait's line (`#fix_wait`, `role="status"`, its `data-w-*` as before). The map box keeps ` data-waiting`
  and a new fix still reloads at the map (`#location`).
- **"N made-up locations ignored"** (design 64 step 2) had no place but the box: it is a row of its own right after
  Update Location (graphite tile, no chevron), shown only while the count is above 0.
- The "Location is off" warning keeps its place above the rows. The Alle view is unchanged.

Tests: `locate_page::update_location_is_the_first_row_and_the_map_has_no_box`, `the_bubble_says_fresh_or_old` (was
`the_age_line_says_fresh_or_old`), the forms table's `#update-location`; `jstest/locate.test.js` (the bubble's lines,
their text-only build, the marker opening it).
