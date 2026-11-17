# Design QA - 110 map links (revision 1)

Reviewer: design QA, 2026-11-17. Read against `main` at `daf656d13`, the 2026-11-04 field research the coordinator
passed on (the user's desktop in Norway), Organic Maps' `libs/ge0/geo_url_parser.cpp` (master) and AOSP's
`DomainVerificationEnforcer.java` on `android14-release`. No file in the repo was changed.

Count: **1 High, 9 Medium, 13 Low.**

Confirmed and fine as written (no finding):
- §6.2 matches the 2026-11-04 measurement: Play services' `*.app.goo.gl` is `system_configured` (VERIFIED, level 4).
  A user's selection (SELECTION, level 3) loses to it. Only "Open supported links" off for Play services
  (`pm set-app-links-allowed --package com.google.android.gms false`), plus the handler's selection, made a plain tap
  work. Step order 1 then 2 is right. Settings also won't let a host be selected while another app holds it VERIFIED,
  so step 2 before step 1 can't work.
- §6.2's "reading another app's state is allowed": `assertApprovedUserStateQuerent` on android14-release asks for
  `INTERACT_ACROSS_USERS` only across users. Otherwise it filters on visibility alone, so this holds on 14 as well as main.
- §5's label cut: OM's parser runs `raw.rfind('(')` on the raw, still-encoded string and only then `UrlDecode`s the
  label. So a percent-encoded `(`, `)` or `&` inside the label is safe, and a search text such as "Kiwi (Storgata)",
  encoded, doesn't end in `)` and isn't cut.
- The capability-gated policy key has a precedent: `unified_push` and `server_tls` are both
  `skip_serializing_if = "Option::is_none"` (`models.rs:1416-1424`).
- The phone's report can see a hidden Organic Maps: `collectInstalledApps` uses `MATCH_UNINSTALLED_PACKAGES`. This
  matters for finding 5.

---

## High

### 1. The common case is a text search, and the design doesn't fix the search text that works (§2 G9, §5, §15 Q2, §13)

- **What's wrong:**
  - The user's own Android share from 2026-11-04 resolves to G9: `https://maps.app.goo.gl/ZtCcrVTKt54Dx2hVA` ->
    `maps.google.com/maps?q=Artbox+AS,+Drammensveien+133,+0277+Oslo&ftid=…&entry=gps`. It has no coordinates.
  - All three of §4's measured links are GeoShare test links that carry coordinates, so the design treats the pin as
    the main path and G9 as one row among twelve. For the kid's real links it is the other way round: **the search is
    the main path.**
  - What was measured on 2026-11-04: `geo:0,0?q=Drammensveien 133, 0277 Oslo`, the address alone, found the right
    place as the first hit, offline, with one extra tap.
  - The design sends the whole joined string, "Artbox AS, Drammensveien 133, 0277 Oslo", which nobody has tested. OM's
    offline search ranks token matches. A business name OSM doesn't carry, or carries in another spelling, can push the
    right hit down or out.
  - Q2's "the whole string as the title" applies only to pins. For a search there is no title: OM's card shows its own
    data. So for G9 "name and address" reaches the kid only as far as OM's search finds them.
- **Fix:**
  - Say in §2 and §4 that G9 is the common case (the user's measurement), and that it opens as a search.
  - Make the query rule explicit in `map_link_rules.json` and gate it:
    - The candidates are (a) the full string, (b) the part after the first comma when it holds a digit (a house number
      or postcode, as "Drammensveien 133, 0277 Oslo") and (c) the part before the first comma.
    - A new gate step runs a handful of real Norwegian shares (Artbox, a Kiwi, a school, a cabin address) through
      `geo:0,0?q=` with (a) and (b) on Organic Maps with Oslo downloaded, and records the first hit.
    - The recommendation follows the measurement. The 2026-11-04 data point favours (b) when it exists, else (a).
  - Keep the one tap the user already accepted on 2026-11-04 (search, one tap, private). Write that acceptance into
    the note, so the "no pin for most Android shares" cost isn't news to the user.

---

## Medium

### 2. Apple's `sll`/`near`/`center` and similar "map centre" parameters are taken as the place (§3 A2)

- **What's wrong:**
  - A2 ranks `ll > coordinate > q > sll/near/center` as the place's coordinates, and opens a pin.
  - In Apple's own reference, `sll` is "the search location" and `near` is a hint for a search. `center` is the
    viewport. With `q=<text>`, they say where to search, not where the place is.
  - So `?q=Kiwi&sll=59.9,10.7` would drop a pin labelled "Kiwi" on the viewer's map centre. That is the same trap as
    item 2 of the 2026-11-04 research: Google's page `center=` was the *requester's IP location* (Asker/Vollen), not
    Drammensveien 133. It gives a confidently wrong pin.
  - The same applies to Google's old `?q=<text>&ll=`/`sll=` long links.
- **Fix:**
  - When a `q` (or `name`/`address`) text is present and the only coordinates are `sll`/`near`/`center` (Google:
    `sll`, or `ll` next to a text `q`), open **a search centred there**: `geo:{lat},{lon}?q={text}`. OM centres a
    search on the given point (§5's own reading of `mwm_url.cpp`).
  - A pin comes only from coordinates that name the place: `coordinate`, `ll`, or `q=lat,lon` for Apple; `!3d!4d`,
    `q`/`query`/`ll` lat,lon, `destination`, or a `place/<lat,lon>` segment for Google; the `@` viewport only as G4's
    fallback when a place segment exists.
  - Add rows to `map_link_rules.json`: `?q=Kiwi&sll=…` -> search centred, never a pin.

### 3. The resolver can't be built "like Spotify's" with the numbers the note gives (§8.1)

- **What's wrong:**
  - `music_net::HttpSource` always reads the body up to `kind.limit()`. For `Kind::Page` that is 2 MB, with a
    30 s overall timeout (`music_net.rs:56,70,574-585`).
  - Its connect timeout is a fixed 10 s, with a 30 s read timeout (`music_net.rs:423-424`).
  - So these claims are false with the client as it is: "the body never read", "a 3 s connect timeout and 5 s overall".
  - A 200 interstitial (which §4 says counts as `dead`) would be read in full.
  - The hops have no total deadline: up to 3 hops at up to 5 s each, while the phone gives up at 6 s.
  - "One in flight, 200 ms apart" is what `Gated` does (FIFO waiting, `main.rs:523,549`), but its queue has no bound.
    A burst waits past the phone's 6 s rather than answering `busy`.
- **Fix:**
  - Add a `Kind::ShortLink` with a tiny body cap (say 16 KB, or 0 if `HttpSource` learns to skip the body when
    `follow == false` and the status is 3xx) and a 5 s overall timeout. Either make the connect timeout part of
    `Kind`, or drop the "3 s connect" claim.
  - Wrap the whole resolve, all hops, in one `tokio::time::timeout` of 5 s, so it answers before the phone's 6 s.
  - Give the resolver its own `Gated` instance, as `weather_fetch` has (`main.rs:549`). When one request is already
    waiting, answer `busy` at once (`try_acquire`), not after a wait.
  - The result is cached even if the phone has given up, so a second tap is instant. Say so.
  - `network_error` already uses `without_url()`, so the short id doesn't reach a log through a reqwest error. Keep
    the resolver's own log to the host and the outcome, as §8.1 says.

### 4. "Open in browser" during a pause would loop back into `MapLinkActivity` (§7.2)

- **What's wrong:** A plain `VIEW https://…` of the same link resolves to Vibb again. Vibb holds SELECTION after
  step 2, the PPA covers the time before it, and with no browser Vibb is the only candidate. The kid taps the button,
  sees the same note again, and never reaches the browser.
- **Fix:**
  - Start the browser explicitly. Resolve the default browser with
    `Intent(ACTION_VIEW, Uri.parse("https://example.com")).addCategory(BROWSABLE)` and `MATCH_DEFAULT_ONLY`, or with
    `CATEGORY_APP_BROWSER`. Then `setPackage(browser)` on the link's VIEW intent, or set the
    `makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_BROWSER)` selector.
  - Pin it in `mapLinkPlan`'s test: the browser action names a package and is never Vibb's.
  - Use the original link (after the https upgrade), never the resolved long URL, so the browser sees exactly what was
    shared.

### 5. A hidden (not allowed) Organic Maps counts as "installed" (§9 visibility, §7.3 chip, §7.4, §9 lines)

- **What's wrong:**
  - The Apps group shows when "its last report lists `app.organicmaps`". `installed_apps` lists hidden apps on purpose
    (`MdmSyncWorker.kt:1312-1330`, `MATCH_UNINSTALLED_PACKAGES`).
  - So a parent who has Organic Maps installed but not allowed gets the switch. When they turn it on, the kid gets "Kartet
    kan ikke åpnes her…", and the PWA says "Organic Maps isn't on the phone any more". That is false: it's on the
    phone, only not allowed.
  - §7.3's chip condition is "a target is installed", which has the same gap.
  - Under the kiosk (`setLockTaskPackages`), only allowed packages are in the lock-task list, so a hidden target can't
    start anyway.
- **Fix:**
  - The report's `target` gets a companion `target_state`: `allowed` | `hidden` | `suspended` | `missing`.
  - The PWA keeps showing the switch when it is installed in any state, as the user asked: "installed". But it adds the
    first-match line "Organic Maps isn't allowed on this phone. Allow it above." (nb: "Organic Maps er ikke tillatt på
    telefonen. Tillat den over."), in orange.
  - The chip shows only for an allowed target (suspended included, which gives the "not available right now" note).
  - The "missing" line is used only for `missing`.
  - Add the rows to `map_links.json`'s `report` and `pwa_lines`.

### 6. Turning the switch off leaves Play services' links off, but G7 says "resolves as before" (§6.2, §7.2, §13 G7)

- **What's wrong:**
  - Step 1 is a user setting that stays after the switch goes off, after Vibb's activity is disabled and after an
    uninstall of Organic Maps.
  - With the switch off, a `maps.app.goo.gl` link then goes to the browser or Google Maps, or to nothing at all, but
    not "as before".
  - Any other `*.app.goo.gl` or Play-services-held link also stays off. §15 Q3 names Firebase links only, but the full
    set Play services declares is unknown until G2.
  - GeoShare (the 2026-11-04 manual path) may still be installed with its own selections for the same hosts. Step 2's
    selection then moves them, and Settings asks "open in Vibb One instead?".
- **Fix:**
  - When the switch is off and `play_services_links == false`, the Settings group keeps one row, "Google Play-tjenester
    kan åpne lenker igjen" / "Let Google Play services open links again", which opens the same page. The PWA's
    off-state sub-line names it.
  - G7 checks this explicitly.
  - G2 records Play services' whole declared host list, and the info panel names the side effect in one line.
  - Add a line to `android-settings.md` saying the GeoShare path is superseded, and that GeoShare should be removed
    from a phone that has it.

### 7. The PWA's "step left" line can stay orange for ever (§9 table, §7.5 `vibb_hosts`)

- **What's wrong:**
  - `vibb_hosts.selected < declared` is orange "One step left…". But Settings won't let Vibb select a host that another
    app holds VERIFIED.
  - Likely cases: Google Maps once the parent allows it (`maps.google.*`, `www.google.*`), and possibly the Google app
    or Play services for `www.google.com` and `google.com` (G2 is still to measure them).
  - On such a phone, `selected` can never reach `declared`. The parent is told to do a step that can't be done. The
    table's `reach.google false` row comes after it and never shows.
  - `play_services_links` can be `null` (can't be read), which skips the step-1 row, and the count row then names the
    wrong step.
- **Fix:**
  - Drive the lines from `reach` first, as the outcome.
  - Step 1 is left when `reach.short` is false and `play_services_links != false`.
  - Step 2 is left when the host classes (`short`, `google`, `apple`) are false, counting only hosts whose state is
    selectable. The phone reads that: a host in `getHostToStateMap()` that is neither SELECTED nor VERIFIED-by-us, and
    whose `resolveActivity` lands on another app's VERIFIED handler, is "held by another app", not "left".
  - Report `vibb_hosts` as `{selected, selectable, declared}`.
  - Add a `pwa_lines` row with Google Maps allowed and the Google hosts held.

### 8. One filter must not mix "any path" and "/maps" hosts, and "empty path with `q`" must not cover Google Search (§7.1, §2 Hosts)

- **What's wrong:**
  - In an `IntentFilter`, paths apply to every authority in the same filter. In the manifest, `<data>` elements inside
    one `<intent-filter>` merge.
  - If `maps.google.*` and `www.google.*` share a filter with `pathPrefix="/maps"`, then `maps.google.com?q=…` (path
    empty) and `maps.google.com/?q=` stop matching. That loses the common G9 form for links from other apps.
  - The PPA built in code has the same rule (`IntentFilter.addDataPath` covers all authorities).
  - §2's "the path is `/maps…` or empty with a `q`/`ll`/`cid`/`ftid` query" doesn't say which hosts. Read for
    `www.google.com`, it makes `https://www.google.com/?q=pizza`, a web search, a "map search" in Meldinger's
    `find`.
- **Fix:**
  - State that each §7.1 bullet is its own `<intent-filter>` and its own PPA `IntentFilter`. There are three groups: any
    path (`maps.app.goo.gl`, `maps.google.*`, `maps.apple.com`, `maps.apple`), `/maps` prefix
    (`www.google.*`, `google.com`, `goo.gl`, `app.goo.gl`), and so on.
  - In `long_hosts`, the "empty path with a query" form is for `maps.google.<tld>` only.
  - Add a `long_hosts` row `https://www.google.com/?q=pizza` -> not a map link.
  - `PlayInvariantsTest` counts PPAs: say "the sixth reseat step, with N filters".

### 9. Element X with Chrome visible is not gated, and Custom Tabs bypass the resolver (§6.1 path 2, §13 G6/G11)

- **What's wrong:**
  - G11 runs only with Chrome hidden. G6 tests Chrome visible but uses `am start` VIEW, not Element X.
  - Many apps open a link with `CustomTabsIntent`, which `setPackage`s the Custom Tabs browser. Android's app-link
    resolution, and Vibb's selection with it, is then skipped unless the app first asks for a non-browser handler
    (`FLAG_ACTIVITY_REQUIRE_NON_BROWSER`).
  - With Chrome as the login role's browser (design 102), a map link in Element X may open in a Chrome Custom Tab and
    hit the `URLBlocklist`, whatever steps 1 and 2 did.
- **Fix:** Add G11b: Element X with Chrome as the login browser. Record whether the link reaches Vibb or a Custom
  Tab. If it's a Custom Tab, state the limit in the PWA's info panel ("Element X may open map links in Chrome; …") or
  in the design's §16. Don't promise "whatever the browser" in §6.2 until this is measured.

### 10. The design should say why the server never fetches the long page (§4, §11)

- **What's wrong:**
  - §4 gives only the consent page as the reason. The 2026-11-04 research found a stronger one.
  - Google's long page (227 KB) holds `center=59.7884928%2C10.4464384`. That is **the requester's IP location**, not
    the place: Drammensveien 133 is about 59.92/10.69. The place's point is only filled in by JavaScript.
  - A later "improvement" that scrapes the page would put a confidently wrong pin on the server's own town.
  - Item 1 of the research also answers §4's "EU check still open" for Norway. The short link resolved without consent
    in 0.81 s.
  - The landing host depends on User-Agent: `curl` gave `maps.google.com/maps?q=…`, and `-A Mozilla/5.0` gave
    `www.google.com/maps?q=…&ucbcb=1`.
- **Fix:**
  - In §4 and §11, say: never fetch the long URL, never read HTML, never read `center=` or any viewport from a page.
    Give the reason: the requester's location, measured 2026-11-04 (Asker/Vollen instead of Oslo), plus GeoShare's
    16 s headless page load.
  - Add the Artbox share to §4's table and to `map_links_hops.json`, with the `vibb/1.0` answer recorded at G1.
  - Reword G1 as a recheck with `User-Agent: vibb/1.0` rather than an open question.
  - `long_hosts` must accept both landing forms: `maps.google.com/maps?q=…` and `www.google.com/maps?q=…&ucbcb=1`.

---

## Low

### 11. The migration number is missing (§8.2)

`main` ends at `0152_unified_push.sql`. `0153_up_gateway_and_vapid.sql` (branch `up-r2r3`) and `0154_up_attention.sql`
(`up-r2r3-server`) are taken. No branch has a higher number. **Fix:** name `0155_map_links.sql` (`ALTER TABLE devices
ADD COLUMN map_links_on INTEGER NOT NULL DEFAULT 0`), and recheck at build time in case 108c lands another first.

### 12. Server vs phone resolving the short link: keep the server, and say why (§1, §11; research item 4)

- The 2026-11-04 idea was the phone following the link itself, in under a second. The server is the better choice:
  - Google sees the household server's IP, not the kid's carrier IP.
  - The kid's DNS filter never needs a Google host allowed: `maps.app.goo.gl` and `www.google.com` stay blocked, which
    a phone-side resolver would have to open.
  - It matches "the phone never talks to Google or Apple".
- The costs: one more round trip (about 1 s), and short links fail when the server is unreachable. Long links still
  open offline.
- The server learns which shared places were opened. It is the parents' own server, but say so. The in-memory cache
  (7 days) holds short -> long pairs, which are places. The log has none.
- **Fix:** Add this comparison to §11 in four lines, so the choice is recorded against the earlier idea. Consider
  1 day instead of 7 for found links: a household re-opens few links, and less is held.

### 13. Which lock "the PIN lock" means (§7.1 order, step 1)

- The kid's phone runs Android's keyguard (Vibb's own PIN lock is legacy). There is also the time lock
  (`LockReason != NONE`, `KidModeEnforcer.kt:20`) and Vibb's LOCKED state, under which the lock-task list holds
  our own package.
- `MapLinkActivity` is our own package, so it is the one thing that could start over the time lock.
- **Fix:** Say "while `LockReason != NONE`, or LOCKED, or `KeyguardManager.isKeyguardLocked`: finish with nothing
  shown", and make it a `mapLinkPlan` row.

### 14. Starting Organic Maps: the task, and a late start (§7.1)

- **The task:** `MapLinkActivity` has no `taskAffinity`, so it joins the caller's task: Dagen's `.day` task, the
  Messages task, or Element X's. Without `FLAG_ACTIVITY_NEW_TASK`, Organic Maps stacks inside that task.
- **A late start:** if the kid leaves during the up-to-6 s wait, `noHistory` finishes the activity. Starting OM after
  that is a background start, blocked on 14+ (BAL).
- **Fix:** Start the target with `FLAG_ACTIVITY_NEW_TASK`. Cancel the resolve in `onStop`, and don't start anything
  after it.

### 15. `KidScreensEscapeTest` is a banned-strings test, not an allow-list of starts (§7.3, §13)

- The test bans strings (`Settings.ACTION_`, choosers, pickers) in the guarded directories. It has no "allowed start"
  list to add `MapLinkActivity` to, and starting our own activity isn't banned.
- **Fix:**
  - Add `map/` (`MapLinkActivity` and its notes) to `guarded`, so the kid's note can never open Settings or a chooser.
  - The Settings rows of §6.2 live in the PIN-gated Settings, which isn't guarded, and they must stay there.
  - Update `calls.md:287`'s "bubbles never … linked: nothing opens another app" with the chip's one exception.

### 16. The 409 `off` answer has no defined phone action (§8.1)

"Treated as off" doesn't say what the kid sees. **Fix:** show the "Fikk ikke åpnet lenken nå" note and trigger a
policy sync. Don't disable the activity on a 409 alone: the policy owns the switch (`parentChoice`). Add the row to
`resolve.phone`.

### 17. The report "changes only after the parent does a step" isn't true (§7.5)

`reach` also changes when Google Maps or Chrome is allowed or hidden, during a pause (everything unhidden), and when the
target is installed or suspended. The heartbeat's hash copes with that. **Fix:**
- Reword the sentence.
- Have the PWA ignore `reach` from a report sent during a pause (or flag it as such), so a pause doesn't flash "Ready"
  or a step line.

### 18. Hidden isn't "not installed for the user" (§6.2, approval drops)

`setApplicationHidden` sets the hidden flag, not `installed=false`. The outcome is the same: a hidden package isn't a
query candidate, so it is never approved among the candidates. **Fix:** correct the wording, so nobody relies on the
approval level of a hidden app.

### 19. `pwa_lines` in the shared contract is run by the server alone (§10)

The launcher doesn't produce or read the PWA lines. **Fix:** move `pwa_lines` to the server-only
`map_links_hops.json`, or to a `map_links_pwa.json`, and keep the shared file to what both sides run, as CLAUDE.md's
pattern does (`unified_push_gateway.json`).

### 20. The short-link hop check should go back through `short_links` (§4, §8.1)

"A short-link host means one more hop" fetches the `Location` as it stands. **Fix:** when the next hop is a short-link
host, rebuild it through the same `short_links` rule (host + id, https, no query). An odd `Location` such as
`maps.app.goo.gl/x?link=…` is then never sent on as is. Add a hops row.

### 21. Over-built for the ask, and design calls in the user questions (§2 G7, §15)

- **Q5's plain-text address row** in Dagen goes beyond the user's ask. It makes every event location a map button, and
  free text is not a parsed link. Recommend it for §16, not this round.
- **G7's plus-code decoder** is rare in real shares. Keep it only if GeoShare's fixtures show it in Android or iOS
  shares. Otherwise give it the "no place" note.
- **Q4 (directions -> the destination) and Q7 (an unreadable link -> a calm note)** are design calls with no visible
  cost. Settle them in the note. Ask the user only Q1 (the first tappable thing in Meldinger: it changes a stated
  rule), Q2 (the title), Q3 (the two phone steps) and Q6 (CoMaps, which widens "only when Organic Maps is installed").
- **A missing question for the user:** with the switch off, should the Settings row offer to give Play services' links
  back (finding 6)? Recommend yes.

### 22. i18n gaps (§6.2, §9)

The Settings group's rows ("Google Play-tjenester", "Vibb Ones lenker", their sub-lines and the "done" state), the
"Åpne i nettleseren" button and finding 5's new line have no en/nb text in the note. **Fix:** list them in §7.4 / §9's
tables.

### 23. Smaller points

- §1 says "both sides read that check from one shared file". At runtime each side has code, and the file pins it in
  tests. Say "test against".
- §8.1 accepts `http://` input. The phone upgrades it already, so the server can refuse `http` outright, and its input
  is then https only.
- §5's search form `geo:0,0?q={text}`: OM reads `q` through `url::Url::GetParamValue`. Gate step G5 confirms that
  `%2C`, `æøå` and `+` arrive decoded. Use `%20`, not `+`, for spaces in the search text, since `+` may not be decoded.
