# QA 26c step 8 code review - no Spotify word in the kid's app; one child account per phone, the parent's never

Scope: branch `music-3-spotify`, `d23038a..598a152` (one commit, 26 files: the music strings in both languages,
`logic/{LibraryView,Spotify}.kt`, `ui/{LibraryScreen,ListScreens,PlayerScreen,UiModel}.kt`, `playback/PlayerModel.kt`,
`spotify/SpotifyWebHost.kt` (a comment), the tests - `SourceScanTest`'s new "no kid-facing Spotify" test, `FitTest`,
`GuardsTest`, `LibraryViewTest`, `SpotifyRulesTest`, `UiModelTest`; the server's `templates/music_spotify.html`,
`device_detail.html`, `handlers/music.rs`, `src/tests/music_spotify.rs`; `docs/testing/emulator.md` §6l, `music/CLAUDE.md`,
`server/CLAUDE.md`, design 26, `PLAN.md`, and design 26c). Read against root, `music/` and `server/CLAUDE.md`, design 26c
(§0.2, §0.4, §2.1-§2.5, §3.1-§3.3, §4.3, §5.8, §5.9, §7.3, §11, §12, §13, §14, the QA checklist, the Release, "Step 8
implementation status" with its five deviations and its one finding), `vibb-spotify-options.md` (what the user accepted
on 2026-10-06), `qa-26c-step7-docs.md` and `qa-26c-step5b-code.md` (the templates), and the code they name. Line numbers
are `598a152`'s.

The user's words this step answers (2026-10-14, verbatim): "Jeg er enig i at barna skal ha en konto hver (1 pr tlf), men
ser ingen grunn til at min konto skal involveres. Jeg fikser vel selv å paste in en linke fra alt spotify innhold de skal
ha tilgang til. I tillegg tenker jeg at vi ikke trenger noe spor av spotify noe sted i appen? For barna skal jo dette bare
være en tile til? Samme hvilke kilde?"

Runs, here (the worktree, JDK 21, `music/local.properties`; no emulator, no account):
- Music `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD SUCCESSFUL,
  then `testDebugUnitTest --rerun`: **845 of 845** JVM tests (844 + the new scan), lint 0 errors.
- Server `cargo fmt --check` clean; `cargo test` **827 passed**, 0 failed (`parent_pin` green this time).
- `python3 -I scripts/fake-spotify.py --selftest`: "FAKE SPOTIFY SELFTEST OK".
- `git diff --stat d23038a..598a152 -- launcher/ server/testdata music/app/src/test/resources music/app/src/main/aidl`:
  empty; `cmp` of `music_{library,listing,icons}.json`, `spotify_hosts.json` (server vs music) and `bridge_contract.json`
  (music vs launcher): identical. No contract file, no migration, no dependency; `launcher/` untouched. The server diff
  is copy only (`handlers/music.rs:3162-3165`'s notice string, two templates) and its tests - nothing in the account,
  binding, reader or token code changed.
- Mutations (each reverted, `git status` clean): nb `renderer_spotify_elsewhere` back to "Spotify spiller et annet sted"
  -> red (the step 8 scan, the nb pin, `FitTest`); a ui literal `label = "Spotify item"` in `PlayerScreen.kt` -> red
  (the step 8 scan alone: `PlayerScreen.kt: no "Spotify" literal`); `kindOf` giving every Spotify entry `MUSIC` -> red
  (`SpotifyRulesTest`, `LibraryViewTest`); **the session's album title `.setAlbumTitle("Spotify")`
  (`PlaybackService.kt:896`) -> 845 of 845 green** (L1).
- Text widths: Nunito Bold/ExtraBold from `res/font/*.ttf`'s advances (a 60-line script, no kerning, as `FitTest`'s):
  every one of `FitTest`'s new numbers reproduced exactly (the 14 lines 78.8-153.4 dp; the context hint 198.0/187.0 Bold,
  203.0/191.6 ExtraBold); the tile hints with "Musikk" at 13 sp are narrower than today's "Egne filer" ones (189.5 vs
  205.3 dp nb, 214.6 vs 238.0 en).

**Verdict: ready; a short fix round of Lows, none blocking.** Counts: **0 High, 0 Medium, 6 Low.** The main risk of the
step - a Spotify entry inheriting NRK/RSS behaviour through `Kind.PODCAST` - does not exist: `logic.Kind` is a display
type only (it is read in `ui/` and nowhere else; every rule keys on `Source`/`isSpotify`, below). Every string value in
both languages is free of the word, the player's source line and the list's are gone, the hints keep their shape, the
seven lines fit at 320 dp, and the server's copy says the account model the user chose. The Lows: the new scan guards
`ui/` only, so the lock screen's and Home's text could take the word back with every test green (L1); one server
fallback can still name an entry "Spotify playlist" (L2); the collaborative route's copy omits that the child has to
join (L3); five owed device-check lines still say "a playlist the parent owns" (L4); §7.3 files the deviation under a
risk the user accepted for another clause, and §14 misquotes the user (L5); and three small doc truths, §2.5's "400"
among them (L6).

## Findings

### L1 (Low) - the "no Spotify" scan covers `ui/` only: the notification, the lock screen and Home's card are unguarded

- **Where:** `music/app/src/test/java/me/vibb/music/SourceScanTest.kt:461-474` (the step 8 test: the two `strings.xml`
  and `sources("src/main/java").filter { it.path.contains("/ui/") }`); the kid-facing text built outside `ui/`:
  `M/playback/PlaybackService.kt:893-896` (the session's `MediaMetadata`: title, artist, album - the notification and
  the lock screen), `:1505-1508` (`setNowPlaying`'s `entry`/`item` - Home's music card and the lock's tile, design 28),
  `M/spotify/SpotifyRenderer.kt:1352-1353` (`titled`, a context's live title into the session item).
- **Why it matters:** the docs say the scan pins "no word ... on a tile, a hint, the player, the list or a line"
  (26c §7.3, `music/CLAUDE.md` "No source on the kid's screens"), and the step's status says "the session's metadata
  ... was always the entry's name and the track's title" - true today (read: only `item.title` and `a.entry.name` go
  there, and the context title is the SDK's track name), but nothing holds it. Mutation: `.setAlbumTitle("Spotify")`
  passes all 845 tests. The string resources are covered completely (the app has only the two `strings.xml`; the
  helper reads `<string>` and plural `<item>`s).
- **Fix:** extend the step 8 test to the string literals of `playback/`, `bridge/` and `sync/` too, with an explicit
  allowlist of the non-kid ones (`SPOTIFY_LOG_TAG` "VibbSpotify", `PlaybackService.kt:1921`; the resumption future's
  `UnsupportedOperationException("playing on Spotify")`, `:543` - or reword that one to "playing on the web player" and
  need no entry); and pin the three sites above: the metadata builder sets title/artist/album only from `item.title`
  and `a.entry.name`, `reportNowPlaying` puts only `a.entry.name` and `title`, `titled` only the live title. The
  mutation above as the proof the pin bites.

### L2 (Low) - an entry Spotify gives no name is named "Spotify playlist" - on the kid's tile, notification and lock screen

- **Where:** `S/handlers/music_spotify.rs:1152-1157` (`check.meta.name ... .unwrap_or_else(|| music::kind_label("spotify",
  Some(&target)).to_string())`), `S/music.rs:447-457` (`kind_label`: "Spotify playlist" / "album" / ... / "Spotify");
  the name is optional only for a playlist (`S/spotify.rs:2453-2457`, `text()` drops an empty or blank name; the other
  kinds refuse without one); design 26c §2b status "Names" (8.) and §3.2 step 3.
- **Why it matters:** the entry's name is the kid's tile title, the session's artist and album (`PlaybackService.kt:
  895-896`) and Home's/the lock's `entry` (`:1507`) - so this is the one path left by which the word reaches the kid.
  Rare (a playlist whose name Spotify returns blank) and the parent can rename the entry (`handlers/music.rs:1981`), but
  the user asked for no trace, and the PWA's `kind_label` is the right word for the card, not for a name.
- **Fix:** a sourceless fallback for the name only (e.g. "Playlist", "Album", "Track", "Artist", "Podcast", "Episode"
  - or the category kind's word), `kind_label` unchanged for the PWA's labels; a server test with a blank playlist name
  (`parse_playlist_meta` on `{"name": "  "}` -> the add names it without "Spotify"); §3.2 step 3 and the 2b status's
  "Names" say it.

### L3 (Low) - the collaborative route: "invite" is Spotify's word, but the child's account has to join, and the copy doesn't say how

- **Where:** `S/templates/music_spotify.html:94` (setup step 6: "make your own playlist collaborative and invite the
  child"), `:169` and `S/handlers/music.rs:3163-3165` (the context notice: "make yours collaborative and invite your
  child"); design 26c §0.2 item 4 (line 73), §2.1 step 6 (:260-263), §3.2 (:485-488). The QA checklist item 3 already
  says it right: "one of yours made collaborative **and joined by the child**".
- **Why it matters:** in today's Spotify apps a playlist becomes collaborative through ⋯ > "Invite collaborators", which
  makes an invite link; the playlist's collaborators are the accounts that **open that link and join**. Dev mode lists
  items only to the owner or a collaborator (§0.2 item 2), so until the child's account has joined, the playlist stays
  a context. The child has no Spotify app on a Vibb phone (§0.1), so the parent has to open the link as the child - the
  same private window the PWA's login hint already asks for. As written, a parent who invites and stops gets context
  mode and a notice telling them to do what they did. "Invite" is the right term; the copy is one clause short.
- **Fix:** step 6: "... or invite the child to collaborate on yours (in Spotify, the playlist's ⋯ > Invite
  collaborators; then open the invite link while logged in as the child - the private window you logged the child in
  from - and join)"; the notice: "... or invite your child to collaborate on yours and join from your child's account."
  Then "Check now" lists it. The three server tests that pin the strings (`not_configured_shows_the_steps`,
  `context_playlists_short_links_and_kinds`, `the_cards_and_check_now`) and §0.2 item 4, §2.1 step 6, §3.2 with it.
  Whether a joined collaborator reads the items in dev mode stays the device check it is (QA checklist item 3).

### L4 (Low) - the owed real-SDK checks still say "a playlist the parent owns" and "the user's account"

- **Where:** design 26c §9.4 part B (line 1522-1523: "the user's account ... a real playlist the parent owns"), §11
  step 6 (:1692: "a playlist the parent doesn't own"), the Release's "Owed before the stable `music-v0.4.0`" (:1989-1993:
  "the user's Premium account ... a playlist the parent owns ... a playlist the parent doesn't own");
  `docs/testing/emulator.md:1332` ("**a playlist the parent doesn't own**"). §6l's own part B preamble (:1315-1317) and
  its context-mode check (:1341) were updated; these five weren't.
- **Why it matters:** with only the kid's account logged in, "a playlist the parent owns" is exactly the playlist that
  plays as a **context** (§3.3, :515) - the list-mode run would test the other mode, or the user logs in their own
  account to make it list, against the decision. "A playlist the parent doesn't own" no longer names the context case
  (a playlist the parent owns is one too).
- **Fix:** "a real playlist the kid's account owns (or the parent's, made collaborative and joined by the kid)" for list
  mode, "a playlist the kid's account doesn't own or collaborate on (the parent's own, say)" for context mode, and "the
  kid's Premium account" for whose login - as §6l part B's preamble already says.

### L5 (Low) - §7.3 puts the attribution deviation inside the 2026-10-06 acceptance, which assumed attribution; §14 misquotes the user

- **Where:** design 26c §7.3 row II (line 1339: "Inside the risk the user accepted on 2026-10-06 (III below)"), row II
  link-back (:1340: "a deviation inside the accepted risk"), §14 item 5 (:1792-1794: "inside the accepted Policy risk"
  and the quote "ingen spor av spotify noe sted i appen"); `vibb-spotify-options.md:54` (the option the user accepted:
  "Spotify tiles carry Spotify attribution (Policy II.4)") and `:178` ("Accepted: Developer Policy III.8 risk").
- **Why it matters:** what was accepted on 2026-10-06 is III.8 ("targeted to children"), on a design that met II.4 with
  the word on the tiles. Dropping the attribution is a second, separate deviation, decided by the user on 2026-10-14;
  calling it "inside" the earlier acceptance overstates what the user signed off. The sanction is the same (a revoked
  client ID), so nothing in the design changes - only the words. §14's quote marks hold words the user didn't write:
  the user wrote "vi ikke trenger noe spor av spotify noe sted i appen" (as §7.3 quotes correctly).
- **Fix:** §7.3 row II: "a second deviation, the user's decision of 2026-10-14 (the 2026-10-06 acceptance was III.8's,
  on a design with the word on the tiles); the sanction is the same revoked client ID"; the link-back row and §14 item 5
  likewise; §14 item 5 quotes "vi ikke trenger noe spor av spotify noe sted i appen". (For the main session: say this
  to the user once, in one line, when reporting step 8 - see the end.)

### L6 (Low) - three small doc truths: §2.5's "400", the Release's "Last full runs", §3.1's table

- **§2.5's "400"** (line 400: "refused for an unknown id (a 400 back at `#music-spotify`)"): step 2b built a 303 to
  `/devices/{id}?music_notice=spotify_unknown#music-spotify` (`S/handlers/music.rs:4102`, `:4146`; 2b's deviation 3;
  `tests/music_spotify.rs:3742`). The step left it ("Found, not changed"); it is one line: "refused for an unknown id (a
  303 back at `#music-spotify` with the notice `spotify_unknown`, step 2b's deviation 3)", and the status's "Found, not
  changed" becomes "fixed in the fix round".
- **The Release's runs** (:1890-1894): "Last full runs ... music ... 843 JVM tests (step 6's fix round) ... Step 7 changed
  docs only." Step 8 changed code: music 845 (step 8), server 827 with `parent_pin` green (step 8), and "Step 8 changed
  the music app's strings and words, the server's copy and its tests".
- **§3.1's table** (:452-464): step 8 put its note "(The tile column is step 8's ...)" between the audiobook row and the
  Liked Songs row (:462-463), so the Liked Songs row renders as stray text; a blank line already split the table after
  the artist row (:458, from before this step), so the show, episode and audiobook rows don't render as a table either.
  Remove both blank lines and move the note under the table.

## The implementer's deviations and finding (6, all judged)

1. **The kind chip and the hints' prefix changed too; `Kind.MUSIC` new, shows and episodes `Kind.PODCAST`** - right and
   necessary: the chip and every hint began with `kind_spotify`, so "no trace" needs them. `MUSIC` for a playlist,
   album, track or artist and `PODCAST` for a show or episode is exactly the server's own split for the default category
   (`S/music.rs:473-487`, `default_kind_for`), so the tile's word agrees with the category the entry lands in. Safe: see
   "Checked and fine" for the rules.
2. **The lines' wording** - accepted. "Trenger Wi-Fi" / "Trenger internett" take the app's own "trenger Wi-Fi"
   (`not_downloaded` would be wrong: nothing is downloaded); "Spilles et annet sted" takes the passive of the Sonos line
   "Spilles i en annen gruppe" and reads naturally for "the account plays on another device"; "Ikke tilgjengelig nå",
   "Spør en voksen", "Virker ikke på telefonen", "Stoppet - trykk spill" are plain and parallel in en. nb and en carry
   the same meaning line for line.
3. **The context hint "%1$s · rekkefølgen velges for deg"** - accepted: lower case after the dot and the kind first, as
   `hint_continues`/`hint_from_start`; the list's line uses the same string with `kindText(kindOf(entry))`
   (`ListScreens.kt:75-77`), so tile and list agree. (A playlist context plays in the playlist's own order with shuffle
   forced off; "velges for deg" is loose for it but was as loose with "Spotify velger" - the user's look on the phone
   decides, nothing to change now.)
4. **"No resume" only for an artist** - verified, and the docs now say what is built: `contextResumes(entry) =
   !isSpotifyContext(entry) || kind == PLAYLIST` (`logic/Spotify.kt:89`), read by the renderer's offsets
   (`SpotifyRenderer.kt:400`) and the screens' places (`UiModel.kt:155`, `:219`). §0.2 items 4 and 7, §5.9, §14 item 1,
   the Release's open point 1 and `music/CLAUDE.md` (line 289) all say "an artist without resume; a playlist resumes at
   its last track while it is still in it".
5. **`fakeparent` kept, read as a second child's account** - accepted: the machinery is N accounts and the server
   can't tell a parent's from a child's; §6l (:1097-1099) says how to read it, and renaming fixtures would churn the
   fake's selftest and the server tests for no behaviour.
6. **Found, not changed: §2.5's "400"** - true (verified above); fix it now (L6), it's a one-line doc truth.

## Checked and fine

- **No rule keys on `Kind`.** `logic.Kind` is declared in `logic/LibraryView.kt:23` and read only by `kindOf`/`hint`
  there and by `ui/` (`LibraryScreen.kt:73-89`, `ListScreens.kt:75-77, 147-151, 203`, `PlayerScreen.kt:110-115`,
  `UiModel.kt:63`, `MusicRoot.kt:216`) - grep of every `Kind.` in `src/main`; the `Kind` in `sonos/` is the speaker's own
  enum (`SonosSession.kt:21`). The rules the brief named key on `Source`: `canShuffle` (`logic/Queue.kt:18`, own files
  only), `contextResumes` and `isSpotify` (`logic/Spotify.kt:37, 89`), `speakerDropReason` (`sonos/PlayBody.kt:96-104`,
  `"spotify"` by source), the play order (`logic/PlayOrder.kt:24`, a Spotify show by `SpotifyKind.SHOW`), the cache-through
  flag (`PlaybackService.kt:886`, NRK and RSS only), `KeepItem.fetchable`/`downloadPlan`/the Nytt tray (unchanged by this
  step, all by source, and pinned by `SpotifyRulesTest`/`GuardsTest`, 845 green). So a Spotify show as `PODCAST` gets
  the podcast's *words* (chip, "episode N av M", "Ny episode") and nothing else - the same words it had through
  `spotifyEpisodes` before.
- **Every kid-facing string**: no string value in `values/strings.xml` or `values-nb/strings.xml` contains "spotify" in
  any case (the scan, and a grep); the only resource files with text are those two; `kind_spotify` is gone and nothing
  reads it; string *names* (`renderer_spotify_*`, `hint_spotify_picks`) are code. `ui/` has no "Spotify" literal; the
  Spotify literals elsewhere in `src/main` are hosts, file names, uris, regexes, wake-lock tags, the log tag and the
  resumption future's message - none reaches a screen (L1 asks for the pin). The launcher's kid-facing music card and
  lock tile take only `entry`/`item`/position/art from the bridge (`bridge_contract.json`'s `now_playing`), and the
  launcher's `res/` has no Spotify word; its Spotify literals are the token courier, the host list and log lines.
- **The screens**: the player's source line is gone (`PlayerScreen.kt:108-117`; the number line keeps `MUSIC ->
  track_of`); "Liste" for a list-mode Spotify entry shows the header and the rows with nothing in the summary's place
  (no empty `Line`, no stray "·": `ListScreens.kt:64-78`, `summary` null and not a context -> no element); a context
  shows the hint line. The search row and Nytt read `kindText`/`row.kind` like every source. The row's "Spilles nå"
  without a trailing dot for a context is step 6's and unchanged.
- **The tests**: `FitTest`'s maps are the strings themselves (the test reads them from both `strings.xml` and fails on a
  string not in the map), and the numbers are the font's (reproduced). `GuardsTest`'s exact pin of the list's context
  line includes the whitespace left by the stripped comment (`"{\n            \n            Line("`) - brittle but
  true; leave it.
- **Server copy** (`music_spotify.html:36, 91, 94, 143, 169`; `device_detail.html:403`; `handlers/music.rs:3162-3165`):
  factually right for dev mode - a playlist lists only for its owner or a collaborator (§0.2 item 2, [verified
  2026-10-13]); albums, tracks, shows and episodes list with any account; an artist is a context (step 6 copy: "Albums,
  tracks, artists and podcasts need nothing" - true, an artist needs no ownership); Spotify's editorial playlists are
  said to be unavailable in step 5 right above step 6, and the add refuses them with the sentence (§3.2). The dev-app
  owner stays a Premium account (step 1) while "never yours" is about the logins - consistent: owning the developer app
  isn't a login on the server. The five server tests pin the intro, steps 3 and 6, the login hint, the binding hint and
  the notice's new tail, and assert the old "yours (if it reads your playlists)" and "Log in as its owner" are gone.
- **Docs**: "step 8" now means this step everywhere; Spotify on Sonos is "step 9" in 26c (§0 "Out", §1 table, §11.9,
  §12, §13, §14 item 11, the Release's open point 5), design 26 (:102-103) and `music/CLAUDE.md:257` - no stale "step 8"
  meaning Sonos in those files or `PLAN.md`. §14 items 1 and 2 are closed, item 2 with the user's words verbatim (item 1
  with "item 2's answer" - enough: the user answered the accounts question and the links consequence follows). The §7.3
  deviation is stated once in its table row, with pointers elsewhere (L5 is about its wording, not its repetition).
  §6l reads `fakeparent` as a second child's account and every line it quotes is the new wording.

## Fix-round checklist

- [ ] L1: the step 8 scan over `playback/`, `bridge/`, `sync/` literals with the two allowlisted (or the future's
      message reworded); pins on `PlaybackService.kt:893-896`, `:1507-1508`, `SpotifyRenderer.kt:1352-1353`; the
      `.setAlbumTitle("Spotify")` mutation red.
- [ ] L2: a sourceless fallback name for a Spotify entry without one (`music_spotify.rs:1157`), `kind_label` kept for
      the PWA; a server test with a blank playlist name; §3.2 step 3 and 2b's "Names".
- [ ] L3: setup step 6 and the context notice (template and `music.rs`) say the child joins the invite from the
      child's account; the three server tests; §0.2 item 4, §2.1 step 6, §3.2.
- [ ] L4: §9.4 part B, §11 step 6, the Release's owed list and `emulator.md:1332`: the kid's account and the kid's
      (or a joined collaborative) playlist for list mode, a playlist no child's account owns for context mode.
- [ ] L5: §7.3's two rows and §14 item 5: a second deviation decided 2026-10-14, not inside 2026-10-06's III.8
      acceptance; §14 item 5's quote verbatim.
- [ ] L6: §2.5's 303 with the notice (and the status's "Found, not changed" closed); the Release's "Last full runs" with
      step 8's numbers; §3.1's table joined and its note moved under it.
- [ ] Suites: music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` (with
      `--rerun`), server `cargo fmt --check` + `cargo test` (+ `clippy --all-targets` if L2 touches Rust), `python3 -I
      scripts/fake-spotify.py --selftest`; no contract file, `launcher/` untouched.
- [ ] Still owed (unchanged): §6l parts A1, A2 and B, the Jelly Star checks, QA checklist items 1-4 with the children's
      own accounts, and the user's look at "Musikk" on a playlist's cover and "rekkefølgen velges for deg".

No question for the user. One line for the main session to pass on when reporting step 8 (L5): removing the word is a
second departure from Spotify's Developer Policy - its attribution clause (II), beside the "targeted to children" one
(III) accepted on 2026-10-06 - with the same sanction (Spotify may switch the family's app off; the tiles then say
"Spør en voksen"); the attribution lives in the PWA only.
