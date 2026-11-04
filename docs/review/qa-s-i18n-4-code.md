# Code QA: S-i18n slice 4 (`pwa-i18n-4`, 80a44202 + b1827b1a)

Scope: design 70 §6 (the words Rust builds) and §8 (the nb-page test). Read-only review; no cargo/Gradle run (the
builder reports cargo test 1712/0, fmt and clippy clean, node 309/0).

Checked and fine:
- **Device API:** no change. `device_api.rs`, `music_api.rs`, `server/testdata/` and `launcher/` are untouched, and
  none of the changed helpers (`SCREEN_TIMEOUTS`, `blocklist_name`, `shown_label`, `music_warnings`, the error texts)
  feeds the policy or the library's shape. The phone still gets the stored wallpaper label and the timeout in seconds.
- **Logic on worded values:** `attention::items(Lang::En, …)` in `devices.rs:168` is only counted, and
  `music_warnings(Lang::En, …)` in `attention.rs:624` only feeds booleans. `admin_policy_effects` is read as
  `!is_empty()` (attention) or worded (`device_pages.rs:204`). `PinAnswer::words` is worded at its one caller
  (`locate.rs:647`). `LockBadge.name` is empty only when `name_key` is set, and `words()` prefers the key. The
  import's `Skip` stays in memory (session flash text worded at confirm). `flash_in`/`done_flash` keys are worded in
  `DeviceDetailTemplate::flash`.
- **Params:** every literal `t_params`/`t_count`/`tp`/`tc` call in `src/` and `templates/` was checked against both
  catalogs' `{names}` and `.one`/`.other` forms (script). The only miss is `a.b` in an i18n test. Every new literal
  key is in both catalogs.
- **Language:** each new `lang` comes from the request extractor or the reader's `Zone` (`Zone::load(db, lang)`). No
  background task or push words anything new. `push::failure_text` is only used on the reader's subscriptions page.
- **PWA save rule:** no `Redirect`, `back_to` or `#fragment` changed. The new `lang: Lang` extractors are
  `Infallible` and sit before `Form`.
- **`i18n::number`, Format's typed size** (`backups.rs:940`, either decimal mark): OK.

## High

None.

## Medium

**M1 - The nb-page test can't see many common English words, among them "phone", "settings", "add" and "password".**
`i18n.rs:191` `catalog_words(Lang::Nb)` takes every letter run of every nb value. That includes brand names
("Vibb Phone"), commands and env vars (`adb shell appops set … WRITE_SETTINGS allow`, `HANDY_VERSION`,
`kid_phone_server reset-sign-in`), URLs and quoted English log lines (`«failed to build device policy»`,
`«Storytel logins are off: …»`). The English set is en minus nb, so all of those words drop out of it. A Python
re-run of the same rule found 86 words the test can't see. If the nb side drops the same fact tokens that
`english_on` drops (`i18n_pages.rs:80`), plus «…» quotes, `Vibb Phone/One/Music` and `adb shell …`, these come back,
among others: `phone, settings, add, allow, account, password, device, notification, error, failed, open, off, set,
call, code, key, version, library, children, badges, playlist, proxies, write, debug`.
Scenario: a helper left with `"Settings"`, `"Add"`, `"Phone"` or `"Password"` as a row label or value (the most
likely kind of leftover: one word) on an nb page passes the test. The test's own check hides this:
`i18n_pages.rs:106` accepts "phone" being in *either* set.
Fix:
- Build the nb word set with the same `FACT` filter, and drop «…» quotes, the brand names and `adb …` commands.
- Or build it only from values with no fact token.
- Then assert that `phone`, `settings`, `add` and `password` are in the English set.

**M2 - "Every page" leaves out pages the parent sees, and every one-shot state.** `i18n_pages.rs:113-174` has no:
- `/login`: signed out and worded by `Accept-Language`, seen at every sign-in. Easy to add with a header.
- `/devices/{id}/weather`.
- `/account/parents`, `/account/password-sign-in`.
- `/devices/{id}/calendar/rules/{rule}`, `/calendar/links/{uid}`, `/calendar/caldav/{account}`.
- `/music/spotify/done`.
- `/auth/change-password`, `/auth/setup-2fa`.

No page is fetched with `?notice=` / `?done=`, so none of the reworded flashes are read on a page:
`kid_lock::flash_text`, `parent_pin::flash_text`, `lifts::done_flash`, `window_relock::flash`. The same goes for
these lines, which the seed never produces:
- Activity's `set_kid_pin` answer line (`PinAnswer::words`).
- The PIN-send states (`pin_send`, "time unknown").
- A refused form re-render.

Their keys exist in both catalogs (checked), so a missing key can't slip through. What can slip through is English
left in an nb value, or a literal on those paths. Fix: add the missing GETs, plus one pass over each flash code via
`?notice=`/`?done=` on the summary and Lock pages.

## Low

- **L1 - Names the server stores now depend on the language of the parent who adds the entry, and they reach the
  kid.** These fallback names are stored as the entry's name, which is the kid's tile, notification and lock screen
  through the library:
  - `spotify.rs:202` `plain_name(lang)` ("Spilleliste", "Spor", "Podkast").
  - `music.rs:765` `feed_title(lang, …)` ("Podkast").

  The API shape is unchanged, but this is a value the phone shows that changed in this slice. §7 keeps the
  server-made words for the kid for slice 5, so say so in design 70 §6/§7 or PLAN. Storytel's fallback stays English
  (`handlers/music.rs:2278`: "Storytel series" / "Storytel book"), which is inconsistent with the two above. Either
  word it the same way or leave all three English until slice 5.
- **L2 - A non-`security.detail.*` key is now stored in `security_events.params`.** `handlers/music.rs:5630` stores
  `.word("limit", "music.size.no_limit")`. pwa.md says a `security.detail.*` key is frozen once a release writes it,
  but this key is in the music group, and a later rename there would make old rows read the raw key. Fix: use a
  `security.detail.no_limit` key, or note on the key that it's frozen.
- **L3 - The blocked-app log line changed format** (`window_relock.rs:590`): `key …` is gone and an unknown key drops
  its trailing `key ` (good). Old rows still show "key 3f2a…". The page test then allows `install` on *every* page
  (`i18n_pages.rs:13`) for the `found` code in that line. A narrower fix is to skip the parenthesised facts of the
  `security.detail.blocked` line, or allow the word only on `/security`.
- **L4 - The seed's comment claims more than it seeds.** `seeded_family.rs:332` says "a few with words", but every
  security line is recorded with `None` detail. Only the status-written lines (blocked app, zone) have
  `security.detail.*` sentences, so the log's worded sentences are barely exercised.
- **L5 - The page test doesn't catch an unfilled `{param}`.** If an nb value names a parameter the code doesn't pass,
  the text shows `{tid}`, and `words_of_text` reads `tid` as a Norwegian word. The parity test covers catalog
  against catalog, not catalog against call site, for non-literal keys (`t_params(lang, key, …)`). Fix: fail on
  `\{[a-z_]+\}` in `seen_text`.
- **L6 - Blocklists are sorted by their stored English names but shown with nb names**
  (`dns_filter.rs:630`, `device_pages.rs:1589`). nb shows Reklame og sporing, Voksent innhold, Pengespill, Sosiale
  medier, which isn't alphabetical. Cosmetic; sort after `blocklist_name`.
- **L7 - A note points to a group that doesn't exist, in both languages.** `device.lock.glance.no_access_sub` says
  "The fix is under Launcher on the phone's page", but the phone's page has no Launcher group: Attention sends
  `NoNotificationAccess` to `/home#layout`. Say "Home Screen" / "Hjem-skjerm".
- **L8 - A stale comment.** `app_downloads.rs:125-127` mentions `.lead` keys that don't exist (the code calls
  `capitalized`).
- **L9 - `music::megabytes(Lang::En, …)`** in the orphans log param (`handlers/music.rs:1032`) shows "12.3 MB" to an
  nb reader. It's a fact like the others, so keep it, but `i18n::number` at read time isn't possible there; this is
  only a note.
- **L10 - The `StatusCode` scan is coarse at its edges** (`i18n.rs:870`). It skips all of `security.rs`, not only the
  log-reason pairs. Its cut only matches `#[cfg(test)]\nmod tests`, so `pub(crate) mod tests` (spotify.rs) is
  scanned too: stricter, but a test literal there could false-positive later.

## nb words

The builder's open questions:
- **"Steg til side"** (stepped aside) reads like a dance move. Use **"Ga vei"** ("Ga vei · 2 ganger").
- **«Appmerker»**: the service's label is the launcher's own string, and its nb is **«Varseltall på apper»**
  (`launcher/app/src/main/res/values-nb/strings.xml:98`, `badges_listener_label`). Android shows that label in
  Varseltilgang, so quote that.
- **"Oppdateringsskjerm"**: an existing term since slice 3 (`device.advanced.fence`), but the fence isn't a screen; it
  pauses other home screens and the shade. Suggest **"Oppdateringssperre"** everywhere, existing keys included, with
  "sperren ble opphevet" for "the fence was lifted". If the term stays, at least change "Skjermen ble fjernet med en
  gang" (`install_failed_sub`, `commit_failed_sub`, `rebooted_sub`, `expired_sub`) to "Oppdateringsskjermen ble slått
  av med en gang": "skjermen" alone reads as the phone's screen.
- **"Skjerm ved neste/siste start"**: under the boot cover group use **"Neste oppstart"** / **"Siste oppstart"**
  (values "Klar" / "Vist {at}").
- **"feed"**: keep it. It's consistent with existing "podkast-feed (RSS)" and "Feeder på …".
- **"på pause under roaming"**: use **"satt på pause ved roaming"** (existing "Aldri ved roaming"); it reads
  "Installert · oppdatering satt på pause ved roaming".

Others:
- `device.apps.download.any_network`: "alle nettverk fra {at}" → "hvilket som helst nettverk fra {at}" (as
  `device.apps.downloads_info`).
- "hjem-skjerm(er)" in the new escape keys (`needs_update_sub`, `installing_sub`, `unsuspendable`, `orphan_sub`,
  `cover_not_shown_sub`) → "hjemskjerm(er)". The page's name "Hjem-skjerm" stays.
- `device.lock.note.answered_at`: "Svart · {at}" (reads as "black") → "Besvart · {at}".
- `device.lock.note.wrong_pins`: "Feil PIN på rad" → "Feil PIN-er på rad" (as `backoff_sub`).
- `device.summary.relock.step.finish`: "Avslutningen" → "Avslutning" (a list of step names).
- `device.advanced.escape.cover_ready_sub`: "Den vises fra telefonens neste avslåing …" → "Den vises ved telefonens
  neste avslåing …" (avoids "fra … fra").
- `device.advanced.escape.cover_by_itself` / `cover_when_started`: "gikk over til" → "overlot til Vibb One av seg
  selv" / "overlot da Vibb One startet".
- `device.advanced.play.effect.limit_password`: "merke med en gang når …" → "oppdage med en gang at …".
- `device.about.starts.detail`: "Sist merket {when}" → "Sist registrert {when}".
- `device.lock.note.android_credential_sub`: "skriv foreldre-PIN-en" → "tast inn foreldre-PIN-en".
- `music.sweep_error.spotify.refused`: "pekte et sted denne serveren ikke går" → "pekte til et sted denne serveren
  ikke vil koble seg til".
- `music.spotify.link_error.dead`: "ledet ingen steder" → "førte ingen steder".
- `error.change_allowed_apps`: "for denne enheten" → "for denne telefonen" (the PWA's word).
- `push.failure.*` say "varseltjenesten" but `security.detail.push_gone` says "push-tjenesten". Pick one (most nb
  keys use "Varseltjenesten", so change the security detail).
- "Play Butikk" (new keys, 9 in all) against the one older "Play Store åpnet for installering"
  (`security.event.play_install_mode`): change that one to "Play Butikk".
- `settings.wallpapers.builtin.navy`: "Mørk blå" → "Mørkeblå". It's copied from the launcher's nb, so fix both or
  neither.
