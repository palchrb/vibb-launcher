# 51 - "Show the text" per app on the lock's glance page (proposal)

Asked 2026-10-20 ("definere per enhet som får poste push meldinger på lock screen hvorvidt full tekst skal vises
eller ikke ... for en klokke app er det lite å tape på det, samtidig som fulle meldinger vil man kanskje ikke vise på
låst skjerm"). Server + launcher in one commit (the policy's shape changes: root CLAUDE.md "A change to the API goes
into both directories in the same commit"). Builds on design 25 §2.1 (`lock_glance`), `lock_glance.rs`,
`device_lock.html` `#notifications`, the launcher's `GlanceRows.lockGlancePolicy`/`GlancePolicy`,
`policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

## 0. Today

`lock_glance` = `{"apps": ["pkg", …], "show_text": bool}`: `device_lock_glance_apps` holds the ticked packages,
`device_policy.lock_glance_text` the one switch (migration 0066). The Lock page's Notifications group has the ticked
apps and one "Show the text" switch. The launcher reads it field by field (`lockGlancePolicy`): a bad shape never
fails the policy; the fallback is missed calls only.

## 1. Decisions

1. **Per app**: `device_lock_glance_apps` gets `show_text INTEGER NOT NULL DEFAULT 0` (migration 0083 - the next free
   number after round 5's 0082; `server/CLAUDE.md` "Data model" moves to 0084), backfilled from the phone's
   `lock_glance_text`; `lock_glance_text` stays as the default for an app ticked later (the "new apps show their text"
   default = the old switch's meaning) and keeps the policy readable by an old launcher (§1.3).
2. **The policy**: `{"apps": [{"package": "pkg", "show_text": bool}, …], "show_text": bool}` - the array's elements
   become objects; the top-level `show_text` stays (the default/old meaning). `policy_json_keys_snapshot` pins the new
   shape.
3. **The launcher** (`lockGlancePolicy`): an element that is a string = that package with the top-level `show_text`
   (the old shape, so a launcher with the new code reads an old server); an element that is an object with a valid
   `package` = that package with its own `show_text` (missing = the top-level); anything else dropped. An **old
   launcher** given the new shape drops the objects (its rule: non-strings are dropped) and shows missed calls only
   until it updates - design 25's fail-safe, acceptable for the days between the two releases, said in the release
   notes. `GlancePolicy` becomes `apps: Map<String, Boolean>` (package -> show text); `GlanceRows` reads the app's
   flag where it read `showText`.
4. **The Lock page**: in `#notifications`, each ticked app's row gets its own "Show the text" switch
   (`data-autosave`, name `text_<package>`), the global switch becomes "Show the text for new apps" at the bottom
   (the default); every save comes back to `#notifications` (the no-jump rule). The words: the row's sub-line says
   "Text shown on the lock" / "Sender's name only".
5. **Fossify Clock** is not in this list (design 42 reads its timer itself); the example that drove this stays valid
   for any app the parent ticks (a calendar, a weather app).

## 2. Tests (required)

Server: the policy JSON with two apps, one with text and one without; a newly ticked app takes the default; the
migration backfills from the old switch; `policy_json_keys_snapshot`; the Lock page's per-app switches save and come
back to `#notifications` (`check_forms`). Launcher: `PolicyResponseCompatTest` (the shape), `GlanceRowsTest`: the old
shape (strings) and the new (objects), a mixed list, an object without a package dropped, a row's text shown or
hidden per app; `LockGlanceGuardTest` unchanged.

## 3. Device check

Emulator: tick two apps, text on for one; a message from each; the lock shows the sender for both and the text for
one; the save never jumps.
