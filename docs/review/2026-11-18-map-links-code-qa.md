# Code QA - branch `map-links` (design 110, revision 3), read-only

Diff `main...map-links` (2f531b906, b84acaae5, 9dd485cb8, e5408be07). Line numbers are the branch's.

**Counts: 0 H, 2 M, 10 L.**

What holds up well (checked, no finding):
- **SSRF on `/resolve`.** The phone's URL is never fetched as given. `links::short_link` rebuilds it as one of three
  fixed `https://<host>/<prefix>/<id>` strings, with `[A-Za-z0-9_-]{1,64}` ids. Every hop's `Location` is either rebuilt
  the same way or returned to the phone, never fetched. `parse_https` refuses userinfo, an explicit port and non-https.
  `Url` normalises IDN to punycode, and an exact host match means `maps.app.goo.gl.`, IP literals and look-alikes all
  give `None`. The lookup URL is fixed `www.google.com` plus a hex-validated ftid and `hl` ∈ {no,en}. Both go through
  `Reach::Public` (the `PublicResolver`) with `follow = false`. Body caps are 16 KB / 256 KB (`truncated` → `format`).
  `Location` is cut at 2001 characters and then filtered at ≤ 2000. Timeouts are 3 s connect, 5 s / 4 s overall, and
  one 5 s deadline.
- **Limits.** 30 per phone an hour and 100 calls out an hour, every hop counted. The gate admits two (one in flight,
  one waiting) and a third caller gets `busy` at once. The body limit is 1 KB on the route.
- **Cache.** It cannot leak anything across phones. Its keys are `hl` plus the rebuilt short link, or `hl` plus ftid
  plus the phone's own text. Answers are deterministic Google data. At worst it shows that a household phone opened the
  same place.
- **Parser.** `preview::judge` reads `serde_json::Value` along `get()` paths only, so no index panics.
  `NaN`/`inf` can't come out of serde_json. The range check runs before S2. `text_agrees` and `windows(2)` are safe.
  The `percent_decode` bounds are right.
- **Logs.** Host and outcome only. `network_error` strips URLs and nothing logs the ftid, the text or the point.
- **Migration 0159.** Two new tables, `ON DELETE CASCADE`, no core column. sqlx applies the reserved 0157/0158 later
  without trouble.
- **Launcher handler.** It is installed disabled (`android:enabled="false"`) and enabled only on `parentChoice`. The
  PPA is set unconditionally, and a PPA to a disabled component is skipped by `findPersistentPreferredActivity`, so it
  is inert. The PPA is also in `HomePin`'s re-seat (PlayInvariantsTest).
- **Locks and leaving.** The keyguard and `LockReason != NONE` show nothing. The resolve is cancelled in `onStop` and
  nothing starts after `stopped`. The target starts with `setPackage` + `NEW_TASK`. "Open in browser" appears only in a
  pause or override, to a named browser that isn't us, with the original link.
- **`geo:` building.** Coordinates use `%.6f` `Locale.ROOT`, the zoom is clamped, and the label/query are percent-encoded
  as UTF-8 except unreserved characters, so æøå, commas, quotes, `(`, `)` and `&` can't break the URI.
- **Message links.** Only `http(s)://` is linkified, with no Linkify or autoLink. Phone numbers, e-mail addresses and
  addresses stay plain. There is no chooser (`createChooser` absent, `"android"` resolver skipped), and a missing handler
  gives a note, not a crash.
- **PWA.** Auto-saving form back to `#map-links` (303), so no jump to the top. Orange only for missing/hidden/step
  left/lookup broken. Explanations sit behind ⓘ. No sub-line under the switch.

---

## M1 - Meldinger hands a link to any non-suspended package, including never-restricted system apps without a launcher icon

`launcher/app/src/main/java/com/kidslauncher/mdm/ui/messages/MessageLinks.kt:122-131` (`handler`), used at `:107-113`.

- **The cause.** `handler()` takes Android's resolved package, or the first candidate, if it is not `"android"`, not
  suspended and enabled. Enforcement hides only *controllable* packages, those with a launcher icon
  (`EnforcementPlan.kt:225`). It never hides or suspends `PLAY_NEVER_RESTRICT` (`com.google.android.gms`, GSF), not even
  under a time-rule lock (`:229-231`).
- **What reaches it.**
  - A message link to a host Play services holds as a system-configured app link (`*.app.goo.gl`, recorded in §6.2/G2,
    plus whatever G2 finds) now starts Play services' link activity from the kid's own Meldinger.
  - A `maps.app.goo.gl` link does the same while the map switch is off, or before step 1.
  - What that activity then does is unmeasured: Custom Tab, WebView, forwarding to Maps. It is also reachable during a
    rule lock, because GMS is never suspended.
- **The spec.** Q1 says "the allowed browser, or an allowed app that holds it". The code says "any app Android names".
  Before this branch Meldinger opened nothing (`KidScreensEscapeTest`'s old rule), so this is a new path from Vibb's own
  screens.
- **Fix.**
  - Restrict the non-map path to packages the parent allows: the policy's allowlist, the login-browser role, or our own
    package. Refuse `PLAY_NEVER_RESTRICT` and any non-controllable system package with the calm note.
  - During `LockReason != NONE`, allow only the lock's usable apps.
  - Add a `KidScreensEscapeTest` row (or a pure `messageLinkTarget(...)` with rows) for "gms resolves → note".

**Resolved:** `MessageLinks.messageLinkTarget` takes only an app the parent allows: the allowlist, the login browser and Vibb One (anything on a phone with no allowlist). It never takes Play services or its framework (`PLAY_NEVER_RESTRICT`) and takes nothing during a time rule; otherwise the kid gets the calm note. Rows are in `MessageLinksTest`.

## M2 - A short link whose lookup failed is cached as "found" for 10 minutes

`server/src/integrations/map_links/resolve.rs:344-362` (`MapLinks::short`).

- **The cause.** After the hops, the lookup runs when needed (`facts.ftid` without a point or an address). Its result
  can be `None` because the deadline left too little time after slow hops, the gate or the hour's window said `busy`, or
  Google answered `http`/`format`. In every case the whole `ShortAnswer { url, place: None }` is cached under
  `KEEP_FOUND` (600 s).
- **The effect.** For 10 minutes every tap on that short link, from any phone, gets the offline fallback search instead
  of the pin. The kid's natural retry can't recover, and §8.1 says "1 minute for a failure".
- **Fix.** Either:
  - cache with `KEEP_FAILED` when a lookup was wanted and `place` is `None`; or, cleaner,
  - cache the hops' long URL alone (the `Short` entry) and run the lookup through `place()`, which already keeps a
    `None` for 1 minute and a found place for 10, under its own `place {hl} {ftid} {text}` key.
- **Test.** Add a row to `tests.rs`: a held lookup that runs out, then a second ask after release, gets the `place`.

---

**Resolved:** the cache keeps a short link's long link alone. Its place goes under the place's own key (`cached_lookup`): a found place is kept 10 minutes and Google's answer without one 1 minute. A lookup that got no answer (unreachable, busy) isn't kept, and the next tap asks again without the hop. Test: `a_lookup_without_an_answer_is_asked_again`.

## L1 - The design's "or Vibb is LOCKED" (design QA 13) isn't in the lock check

- **Where.** `launcher/.../map/MapLinkActivity.kt:58-59`. The design is §7.1 step 1 and design QA #13's fix.
- **What it checks.** Only the keyguard and `lockReason()`. Vibb's legacy PIN lock (`PinLockState` LOCKED) isn't read.
  Lock task usually blocks the target's start anyway, so this is a spec deviation, not a hole.
- **Fix.** Add the PIN lock's LOCKED state to `locked` (and a `mapLinkPlan` row), or record in the design that it was
  dropped as legacy.

**Resolved by recording it:** Vibb's own PIN lock is legacy and phase D removes it (`android-lock-only`). Lock task already blocks a target's start under it, so the check stays on the keyguard and the time rule.

## L2 - Cancelling the waiting dialog doesn't stop a late start

`MapLinkActivity.kt:96-100`. `setOnCancelListener { finish() }` doesn't set `stopped` or cancel `job`. Between
`finish()` and `onStop`, a resolve that completes still reaches `run()` and `open()`, so Organic Maps opens after the
kid backed out.

- **Fix.** In the cancel listener: `stopped = true; job?.cancel(); finish()`. Optionally check `isFinishing` in `run`.

**Resolved:** cancelling the waiting dialog runs `leave()` (stopped, the job cancelled, finish), and `run` returns while finishing.

## L3 - Quadratic trim in `webLinks` on the UI thread

`launcher/.../map/MapLinks.kt:163-168`. Each trailing-punctuation trim re-counts `(`/`)` over the whole candidate (two
`substring().count`).

- **The effect.** `https://a.b` followed by tens of thousands of `.` or `)` is O(K²) per bind in `onBindViewHolder`.
  A long concatenated SMS or MMS text from an allowed contact can jank or ANR the thread view.
- **Fix.** Count `(` and `)` once over `[start, end)`, then decrement as you trim. Also consider a length cap on the
  scanned link (e.g. 2,048 characters, the handler's own `MAX_LINK`).

**Resolved:** a link is capped at `MapLinks.MAX_LINK` (2,048 characters), and its brackets are counted once and decremented while trimming. A test runs 50,000 `)` characters.

## L4 - An uppercase scheme is linkified but can't open

- **Where.** `MapLinks.kt:157` matches `(?i)https?://`, but `MessageLinks.kt:107` passes `Uri.parse(url)` unchanged.
- **The effect.** Intent-filter scheme matching is case-sensitive, so `HTTPS://example.com` resolves to nothing and gets
  the "can't open" toast. Map links are fine because they go through `upgrade()`.
- **Fix.** Use `Uri.parse(url).normalizeScheme()`.

**Resolved:** `Uri.parse(url).normalizeScheme()`.

## L5 - Meldinger's "can't open" is a Toast, not the kid's calm sheet

`MessageLinks.kt:110,116`. Q1 says "the kid's calm note (§7.4)", and §7.4 is "one calm sheet, one OK" in the kid's
dialog look. A Toast disappears and isn't in the kid's look.

- **Fix.** Use a `KidAlertDialog` with one OK, or record the choice.

**Resolved:** the page's own `AlertDialog`, with one OK, in the page's dialog look.

## L6 - Dagen's "Kart" row ignores the target's state

`launcher/.../ui/day/EventActivity.kt:127-147` shows the rows whenever the handler is on. §7.3 shows the chip only for
an `allowed` or `suspended` target. With Organic Maps hidden or missing, the row leads straight to "Kartet kan ikke
åpnes her".

- **Fix.** Gate on `MapLinksRuntime.target(this).second` ∈ {ALLOWED, SUSPENDED}.

**Resolved:** Dagen's rows show only for an allowed or suspended target.

## L7 - The integration boundary: Google hosts outside the one isolated file (launcher) and an unstated core exception (server)

- **Launcher.** `map/MapLinksRuntime.kt:61-68` (`ANY_PATH_HOSTS`, `MAPS_PATH_HOSTS`) lists `maps.google.*`,
  `www.google.*`, `goo.gl` and `maps.apple*`. The PLAN decision says "the launcher knows only 'links the server
  resolves', never Google by name", with `MapLinkForms` as the one file.
  - **Fix.** Move both lists into `MapLinkForms` (the manifest test already pins them). The manifest and strings are
    unavoidable.
- **Server.** `models.rs:1620` (`StatusReportRequest.map_links`) and `status_heartbeat.rs:99` (`REPORT_KEYS`) name the
  integration in the core. `docs/notes/server/integrations.md` says "Nothing outside `src/integrations/` names it".
  - **Fix.** Either state this as the one exception in the note, or give `StatusReportRequest` a
    `#[serde(flatten)] integrations: Map` that `intake()` reads by key (REPORT_KEYS then takes the integrations' names
    from `Integrations::all()`).

**Resolved:** the launcher's host lists moved into `MapLinkForms`. The server's two exceptions (the report's wire field and the core's snapshot tests) are stated in `docs/notes/server/integrations.md`.

## L8 - Stale "for a launcher that claims it" comments, and an unused `capabilities` hook parameter

- **Comments.** `server/src/handlers/device_api.rs:419` and `models.rs:1347-1348` say the integration keys are "each
  for a launcher that claims it". `map_links` is always sent (the user's 2026-11-18 rule, `mod.rs:10-11`).
- **Parameter.** `Integration::policy`'s `capabilities` parameter has no user, which goes against "kept to what the first
  integration needs".
- **Fix.** Correct the two comments. Drop the parameter, or keep it and say why.

**Resolved:** both comments are corrected, and the trait's `policy` takes no capabilities (the note says a later integration adds the parameter).

## L9 - "A build can leave one out" is unchecked

`server/Cargo.toml` `[features]` and the integrations note. No CI job builds `--no-default-features`, and the core
tests assert `map_links` in the policy (`tests/device_api.rs:353,395,435`), so a featureless test run fails.

- **Fix.** Add `cargo check --no-default-features` (build only) to `server-ci.yml`, or soften the note's claim.

**Resolved:** `server-ci.yml`'s build job runs `cargo check --no-default-features --locked`. The boundary module allows its own unused pieces without the feature.

## L10 - The PWA's "held" (and "off_play") lines are a visible note about a parent's choice

`server/src/integrations/map_links/page.rs:77-79`, 56-58, and the template's `row-note`.

- **"held".** "Google Maps links open in another app on this phone (Google Maps is allowed)" follows from the parent
  allowing Google Maps. The "Words in the UI" rule of 2026-11-18 says such a consequence is "at most a quiet note at
  that choice, behind its (i)". The design table predates that rule.
- **"off_play".** The off-state line is similar: a leftover of the parent's own step.
- **Fix.** Move "held" into the switch's/footer's ⓘ (or show nothing). Keep "off_play" only if the user wants it
  visible. Neither is orange, so this is wording only.

**Resolved:** "held" and "off_play" are no longer lines under the switch. They are a quiet note in the footer's ⓘ.

---

The user's answers (2026-11-18): Meldinger's links stay in the bubble's ink with an underline. Organic Maps isn't added to the catalog by us (the user adds it if they want).
