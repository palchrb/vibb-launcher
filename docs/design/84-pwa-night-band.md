# 84 - The PWA's top: one night band on every page (F2b)

Status: design note by the architect, 2026-11-11, on `main` at 9227c709, branch `pwa-band`. Nothing built. Server
only: templates, `style.css`, two scripts, tests. No API, no migration, no launcher or music change.

The user, 2026-11-11, on the clickable lab's variant F2b: "Jeg liker f2b! Sett den i produksjon!"

What is read and what is inferred: everything below about today's code was read in this tree (templates, `style.css`,
`static/*.js`, `src/tests/`); the lab's pages, CSS and screenshots were read too. A sentence marked *(inference)* is
not proven by either - most of them are things only an iPhone can show (§8).

## 1. The decision

1. Every page in the app shell opens on a **night band** (`--night`): the round Vibb mark and the page's name beside
   it (Nunito 800, `--night-text`), the page's actions at the right in `--night-action`, the bell last.
2. The content is a **sheet** on the page's ground with rounded top corners (22 px) that lies over the band's foot.
3. **The band scrolls away with the page.** It stays in place only on the four pages with "Lagre": there "Lagre" sits
   on the band and the editors' own sticky bar is gone.
4. On a page under a tab a **back chevron** takes the mark's place. The parent page's name is no longer shown; it is
   the chevron's spoken name ("Tilbake til Enheter"). On a phone's summary the band's title is the phone's name
   ("Emma").
5. Dark scheme: band and sheet have the same colour, so a 1 px hairline (#34344A) marks the sheet's edge.
6. What goes: the "vibb" wordmark, the large title under the bar, the back link's visible word, the editors' sticky
   bar. What stays untouched: the tab bar's pill, the status bar's night strip, every page's content.

Measured in the lab at 390x844 (its `checks.json`, the five tabs): the first content starts at 88-92 px where it
starts at 124.5 px today.

## 2. The band partial

One new file, `server/templates/partials/band.html`, holds the band's whole markup and the page's only `<h1>`. No
template writes a title bar, a back link or an `<h1>` of its own after this. It is a set of askama macros, used as
`group_flash.html` is today (`{% import "partials/band.html" as band %}`, then `{% call ... %}`):

| Macro | Left | Right | Used by |
|---|---|---|---|
| `band::tab(title, tab)` | the mark | the call's body, then the bell | the five tabs |
| `band::sub(title, href, parent)` | the back chevron | the call's body | every page under a tab, the inbox |
| `band::editor(title, href, parent)` | the back chevron | the call's body (Save) | the four editors; adds `band-fixed` |
| `band::bare(title)` | the mark | nothing | `music_spotify_done.html` |

Inputs: `title` is the visible name, already translated, escaped by askama as any value. `href` is the back target
exactly as today's `nav-back` has it. `parent` is the parent page's name, the word today's back link shows. `tab` is
the page's `{% let tab %}` (the bell's `?from=`). The page's actions are the call's body (`{{ caller() }}` in the
macro): today's `title-action` elements moved over unchanged. The bell is not an input: `tab` includes
`partials/bell.html` after the body, so it is last by construction and a page cannot place it elsewhere.

How askama passes them. Read in `askama_derive` 0.16.0's source on the build machine (`generator/node.rs`,
`helpers/macro_invocation.rs`): call bodies and `caller()` exist. No template here uses `caller()` yet, and no macro
here reads a struct field (`bell`) or includes a partial, so that the four macros compile as written is an
*inference*. The contract is what is fixed; if askama refuses the form, the fallback is a pair of macros per kind
(`tab_open(title)` / `tab_close(tab)`) with the actions between them - same output.

The output (a tab, then a page under a tab):

```html
<header class="band band-tab">
    <img class="band-mark" src="/static/icons/icon-192.png" width="36" height="36" alt="" decoding="async">
    <h1 class="page-title">Enheter</h1>
    <div class="band-actions">[the page's actions][the bell]</div>
</header>

<header class="band">
    <a class="nav-back" href="/devices"><svg ...today's chevron... aria-hidden="true" focusable="false"/><span
        class="visually-hidden">Tilbake til Enheter</span></a>
    <h1 class="page-title">Emma</h1>
    <div class="band-actions">[the page's actions]</div>
</header>
```

- **Exactly one `h1`**, and it stays `<h1 class="page-title">X</h1>` with no other attribute; the back link stays
  `<a class="nav-back" href="X">`. The two class names are kept on purpose: about 45 test assertions spell exactly
  those two strings and stay true. Their CSS rules are rewritten (§4).
- **The back link's spoken name** is a `visually-hidden` span (the class exists), not an `aria-label`: the same
  accessible name, and the link's opening tag keeps its pinned form. New string `nav.back_to`: "Back to {name}" /
  "Tilbake til {name}". `account.back` (the one back word with a key of its own) goes; Account passes `nav.settings`.
- **The mark** is decorative (`alt=""`) and is not a link. Today's brand is a `<span>` that does nothing on a tap
  (read in `devices_list.html`); the band's mark does nothing either.
- **Title size class** on the `<header>`, never on the `h1`: `band-long` for a title over 16 letters, `band-xlong`
  over 24 (the lab's two steps), counted in the macro (`title.chars().count()`; *inference* that askama takes the
  call - else a helper beside `t()`).
- **Focus order** follows the source: the tab bar (first in the source, as today), the back link, the page's actions,
  the bell, then the content. On an editor: tab bar, back, Save, the form's fields - as today.
- `<header>` inside `<main>` is no landmark; the editors' band, a child of `<body>`, is the page's banner. Accepted.

Where it goes. A scrolling page: `<main class="container">`, the band as its first child, then one
`<div class="sheet">` holding everything the page has today below its title. `<main>` stays the scroller - the
scripts in §5 find it so. An editor: the band between the tab bar partial and `<main class="container
container-fixed">`, a normal flex child of `<body>` - nothing fixed, nothing sticky; the form stays `main`'s child.

Save on the band: `<button type="submit" class="title-action" form="rule" data-save="rule">Lagre</button>` - today's
button with one attribute more, `form`, naming the form's `id` (all four forms have one). A `form=` button that comes
before its form is still that form's default button, so Enter in a field saves as today.

## 3. Every template

54 `*.html` in `server/templates/` (the `partials/` aside). 49 get the band, 5 do not. No error page and no offline
page exist as templates: errors are plain text, and `sw.js` caches nothing ("It does NOT cache pages or assets").

**Tabs** - `band::tab`, the mark, no back.

| Template | Title | Actions before the bell |
|---|---|---|
| `devices_list` | `nav.devices` "Enheter" | "+" to `/devices/new` |
| `apps_list` | `nav.apps` "Apper" | "+" to `/apps/tracked/new` |
| `device_locate` | `nav.locate` "Finn" | none |
| `music` | `nav.music` "Musikk" | none (Edit/Done stays in its group header, where it is today) |
| `settings_hub` | `nav.settings` "Innstillinger" | none |

**Editors with Save** - `band::editor`, the band stays in place.

| Template | Title | Back to (spoken parent) | Save submits |
|---|---|---|---|
| `schedule_rule` | `{{ title }}` (the rule's name, "Ny regel") | `{{ back_href }}` (`{{ back_label }}`) | `form="rule"`, `data-save="rule"`, Add when new |
| `device_bedtime` | `device.bedtime.title` | `/devices/{id}/time` (`device.page.time`) | `form="bedtime"`, `data-save="bedtime"`, Add when not stored |
| `device_zone` | `{{ title }}` (the zone's name, "Ny sone") | `/devices/locate?device={id}#zones` (`nav.locate`) | `form="zone-form"`, `data-save="zone"` |
| `device_calendar_rule` | `{{ title }}` | `/devices/{id}/calendar#rules` (`calendar.title`) | `form="rule-form"`, `data-save="rule"` |

**Pages under a tab** - `band::sub`, no action on the band unless said.

| Template | Title | Back to (spoken parent) |
|---|---|---|
| `notifications` (no tab current) | `settings.notifications.title` | `{{ back.1 }}` (`self.t(back.2)`, the tab it came from); action: the gear, with its `data-unread` |
| `notifications_settings` | `settings.notifications.settings_title` | `/notifications{{ query }}` (`settings.notifications.title`) |
| `device_detail` | the phone's name | `/devices` (`nav.devices`) |
| `device_about`, `device_advanced`, `device_calls`, `device_filter`, `device_home`, `device_lock`, `device_time` | `device.page.<its own>` | `/devices/{id}` (the phone's name) |
| `device_music` | `nav.music` | `/devices/{id}` (the phone's name) |
| `device_calendar` | `{{ title }}` | `/devices/{id}` (the phone's name) |
| `device_apps` | `nav.apps` | `{{ back_href }}` (`{{ back_label }}`: the phone, Apps, or the app it was opened from) |
| `device_calendar_add` | `{{ title }}` | `/devices/{id}/calendar#sources` (`calendar.title`) |
| `device_calendar_caldav` | `{{ username }}` | the same |
| `device_calendar_link` | `{{ name }}` | the same |
| `device_locations` | `locate.locations.title` | `/devices/locate?device={id}` (`nav.locate`) |
| `enroll_code` | `devices.code.title` | `/devices/{id}/advanced#connection` (`device.page.advanced`) once enrolled, else `/devices/{id}#access` (the phone's name) |
| `provision_qr` | `devices.qr.title` "Koble til Emma" | as `enroll_code` |
| `device_add` | `devices.list.add` | `/devices` (`nav.devices`) |
| `tracked_app_add` | `apps.add.title` | `/apps` (`nav.apps`) |
| `tracked_app_detail` | the app's name | `/apps` (`nav.apps`) |
| `music_categories`, `music_import`, `music_settings`, `music_storytel` | `music.categories.title`, `music.import.title`, `music.settings.short`, `music.storytel.title` | `/music` (`nav.music`) |
| `music_spotify` | `music.spotify.title` | `/music#spotify` (`nav.music`) |
| `music_entry` | the entry's name | `/music#entry-{id}` (`nav.music`) |
| `account` | `account.title` | `/settings` (`nav.settings`) |
| `backups`, `dns_filter`, `dns_log`, `provisioning_settings`, `schedules`, `security`, `updates`, `wallpapers` | `settings.backups.title`, `settings.web_filter.title`, `settings.dns_log.title`, `settings.provisioning.title`, `settings.time_rules.title`, `security_log.title`, `settings.updates.title`, `settings.wallpapers.title` | `/settings` (`nav.settings`) |
| `schedule_budget` | `device.time.limit` | `/schedules` (`settings.time_rules.title`) |
| `password_sign_in` | `password_on.title` | `/account#passkey-only` (`account.title`) |

Every back target is today's, unchanged. `device_detail`'s `<h1 class="summary-name">` is the one title that is not
at the page's top today: it moves to the band, and the summary head keeps the picture and the status lines.

**Outside the shell.**

| Template | Gets | Why |
|---|---|---|
| `music_spotify_done` | `band::bare`, title `music.spotify.title`, its sentence in the sheet | It is a `main.container` page (no session, no tab bar, no `h1` today) that passes on to the Spotify page at once. With the band it has the same top as the page it lands on, and the rule "every `main.container` opens on the band" has no exception. No bell and no back: it has no session and nowhere to go back to. |
| `login`, `verify_2fa`, `setup_2fa`, `change_password` | nothing new | `body.auth-page`: one centred card with its own `h1.auth-title`, no tab bar, the document scrolls (design 43 §1.4). There is no page name, action or bell to put on a band, and a band above a centred card is a second header. The night strip behind the status bar is on them already. |
| `lock` | nothing new | The whole page is night with the breathing mark; the splash hands over to it without a change. |

The lab said the same of these pages (its "Ikke med i mockupen" page): the user has not seen them with a band.

## 4. The CSS

Browser floor as before (Chrome 80, Safari 14): no `:is()`, no `:has()`, no nesting. `min()`/`max()` are in use today.

**New tokens.** `--band-top: max(calc(6px + env(safe-area-inset-top, 0px)), var(--top-strip))` (the band's top
padding), `--band-h: calc(var(--band-top) + 54px)` (its visible height: 60 px in a browser tab, 66 px in the installed
app on iOS 27, 60 px + the inset under the old translucent bar), `--band-edge: transparent`, in the dark scheme
`#34344A` (the colour exists today only as dark `--track` and `--switch-off`).

**Rules that come.**

- `.band`: `flex: none; display: flex; align-items: center; background: var(--night); color:
  var(--night-text)`, top padding `--band-top`, sides exactly `.container`'s side paddings of today (16 px, the safe-area insets, the 720 px column),
  `padding-bottom: 32px; margin-bottom: -22px` - 10 px of band under the row, then 22 px that the next box covers. The
  row is `min-height: 44px`, so a larger text size grows the band instead of cutting it.
- `.band` also takes today's `.title-bar-night` trick, upward only: `border-image: linear-gradient(var(--night),
  var(--night)) fill 0 / 0 / 100vh 0 0`. Its comment in `style.css` says what it is for: the 100vh of night above
  the band is what an iOS pull-down bounce shows. The lab used a two-colour gradient on `main` instead; the design
  keeps the mechanism that is on the user's iPhone today (Devices) and leaves `main`'s and `body`'s grounds alone.
- `.band-mark`: `flex: none; width: 36px; height: 36px; margin-right: 10px`. Under 300 px of width it is not shown.
- `.nav-back` (rewritten): `flex: none; display: flex; align-items: center; width: 46px; min-height: 44px;
  margin-left: -16px; padding-left: 16px; color: var(--night-action)` - a 46x44 target from the screen's edge; the
  12x20 chevron as today, its `margin-right` gone.
- `.page-title` (rewritten): `flex: 0 1 auto; min-width: 0; margin: 0; font-family: var(--font-display);
  font-weight: 800; line-height: 1.2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis`, and the sizes
  below. No fixed line height (the lab's 44 px line with `overflow: hidden` would cut letters at a large text size).
- `.band-actions`: `flex: none; display: flex; align-items: center; margin-left: auto; padding-left: 8px`.
  `.band .title-action` takes `--night-action`, hover `--night-action-hover`, focus `outline-color:
  var(--night-action)` (the three `.title-bar-night` rules, renamed); `.band .nav-back:focus` the same with
  `outline-offset: -4px`. `.title-add`, `.title-bell`, `.bell-count` and `button.title-action` stay as they are.
- `.sheet`: `background: var(--bg); border-top: 1px solid var(--band-edge); border-radius: 22px 22px 0 0`, top
  padding 16 px, the sides as `.band`, bottom `calc(16px + env(safe-area-inset-bottom, 0px))`.
  `.tab-bar ~ .container > .sheet` takes today's tab-bar room as its bottom padding. `.container-narrow > .band` and
  `.container-narrow > .sheet` take the 480 px column.
- `.container-fixed` (an editor's `main`): today's side and bottom paddings of `.container`, `padding-top: 16px`,
  `background: var(--bg)`, the same border and radius as `.sheet`. It scrolls inside its rounded top, so rows pass
  under the curve and the hairline never moves.
- `.editor-flash`: the editors' "Ikke lagret ..." / "Lagret ..." lines, today inside the sticky bar. It is the form's
  first child, rendered only when there is a flash, holding today's `.bar-flash` paragraphs in today's look:
  `position: sticky; top: -16px; z-index: 5; margin-top: -16px; padding: 16px 0 8px; background: var(--bg)` - the
  sticky bar's own arithmetic (an offset counts from inside the scroller's padding, 16 px here), so the line stays
  where it is before any scrolling. The flash is in view wherever the page comes back, as today, and nothing new is
  drawn on the night band.

**Rules that change.** `.container`: `padding: 0` (its top, side and bottom paddings move to the band and the sheet);
`flex`, `min-height`, `overflow-y`, `overscroll-behavior-y`, `touch-action` and the desktop `overflow-y: scroll`
stay. `.tab-bar ~ .container` keeps `scroll-padding-bottom`; its `padding-bottom` is now `.tab-bar ~ .container > .sheet`'s
and `.tab-bar ~ .container-fixed`'s.
`.container > form > .button-wide` becomes `.sheet > form > .button-wide, .container-fixed > form > .button-wide`.
`.summary-head`: 4 px top margin. The first `.group-header` / `.group-head` in a sheet: 10 px top margin (written as
two `:first-child` selectors). `.pull-refresh`: `top: calc(var(--band-h) + 6px)`, so the arrow comes down over the
sheet, not over the title. `.net-progress`, `.net-chip`, `.timing-line` keep their places from `--top-strip`: they
lie over the band's top as they lie over Devices' night bar today.

**Rules that go.** `.title-bar`, `.title-bar-split`, `.title-bar-night` (and its three action rules), `.vibb-brand`,
`.vibb-mark`, `.vibb-word` (and `--brand-word`, used by nothing else), the three `.editor-bar` rules,
`.summary-name`, `.container > .page-title:first-child`, `.nav-back svg`'s margin.

**What does not change.** `body` (still the screen, still `--bg` - the lab painted it night; not taken, see the last
section), `.top-strip` and `--top-strip`, the lock veil rule, the tab bar, the auth pages. The PWA still has exactly
one `position: fixed` box.

**The questions the brief asked.**

- *Behind the status bar once the band has scrolled away*: `.top-strip`, as today. It stays. It is what gives the
  status area its colour on iOS 26 and later (the long comment above it in `style.css`), 12 px high at least in the
  installed app; in a browser tab it is 0 px and nothing is needed.
- *iOS overscroll*: above the band, the band's own 100vh of night; under the last row, the ground (`body`'s `--bg`
  through `main`, as today). On an editor the band does not move and `main` bounces inside its rounded top, showing
  its own `--bg`. *(inference for all but the first, which Devices shows today)*
- *Both schemes*: light - night band, `--bg` sheet, no line. Dark - band and sheet are both #0C0C14; the sheet's 1 px
  top border follows the corners. A border, not the lab's inset shadow and `::after`: it needs no layer of its own
  and scrolled rows cannot cover it.
- *Landscape*: the band's sides take the left and right insets as `main` does today; its height is 60 px, and on a
  scrolling page it leaves with the first scroll. On an editor it stays: 60 px where today's sticky bar takes 52 px.
- *Wide screens*: the band's ground runs the whole width, its row sits in the 720 px (480 px) column, the sheet's
  corners are at the window's edges - as the lab's 1024 px screenshot. On a desktop with a classic scrollbar an
  editor's Save sits the scrollbar's width right of the cards' edge (the band is outside `main`); the left edges
  agree. Accepted.
- *Title sizes* - one line, shrink, then the ellipsis; the actions are never cut or pushed out:

  | Title | Size | At 320 px, 100 % text |
  |---|---|---|
  | a tab's | `min(1.5rem, 7.5vw)` | 24 px; 209 px free beside the bell, 161 px beside "+" and the bell |
  | up to 16 letters | `min(1.375rem, 6.9vw)` | 22 px; 258 px free with no action, about 190 px beside "Lagre" |
  | 17-24 letters (`band-long`) | as above; at 374 px and narrower `min(1.1875rem, 6vw)` | 19 px; 24 letters are about 255 px |
  | over 24 (`band-xlong`) | `min(1.1875rem, 6vw)`; at 374 px and narrower `min(1.0625rem, 5.4vw)` | 17 px; about 27 letters, then "..." |

  The rem values, the two letter steps and the 374 px step are the lab's. The `vw` caps are not in the lab: each
  equals its rem value from 320 px up at 100 % text, so nothing the user saw changes. Widths count Nunito 800 at
  about 0.56 em a letter - an estimate from the lab's screenshots ("Varselinnstillinger" fits at 320 px), not a
  measurement of the font.
- *200 % text*: with the root size doubled the caps hold the title to 7.5vw / 6.9vw / 6vw of the width (29, 27 and
  23 px at 390 px), so it grows by about a fifth and still fits where it fitted; the band's actions do double
  ("Lagre" about 95 px wide) and take their room first. This is the one text in the PWA that does not double: a
  one-line band cannot hold a doubled title, and the brief's rule is shrink, then ellipsis. A browser's page zoom at
  200 % is a viewport half as wide: a 640 px window lays out as 320 px above; a phone zoomed to about 200 px is under
  the PWA's 320 px floor - the mark leaves under 300 px and the title is cut first.
- *The editors' band beside the iOS keyboard*: the band is not fixed and not sticky - it is a block at the top of a
  screen-high `body`. When iOS pans the page for the keyboard the band pans with it, as today's sticky bar at the
  top of `main` does, and comes back with the keyboard's close. *(inference; §8)*

## 5. The scripts

Found by grep in `server/static/` for `page-title`, `nav-back`, `editor-bar`, `title-bar`, `data-bar`, `data-save`,
`bar-flash`, `summary-head`, `vibb-`, `querySelector("main")`, `paddingTop`, `firstChild`, `submitter`:

| Script | What it reads today | After |
|---|---|---|
| `register-sw.js` `banner()` (l. 246-271) | `main.querySelector(".page-title")`; puts the push banner after the title when the title is `main`'s child, else first in `main` | **Changes.** The banner goes in as the first child of `main > .sheet`; on an editor (no sheet) first in `main`. Never before the band. The scroll compensation stays as written. `?v=5` -> 6. |
| `locate.js` `mapIntoView()` (l. 255-264) | `main`'s computed `padding-top` as the map's offset under the status bar | **Changes.** `main`'s top padding is 0 now: the offset is the `.top-strip`'s height + 8 px. `?v=5` -> 6 (two templates and `locations.rs`'s pin). |
| `register-sw.js` l. 29 | `[data-unread]` (the bell, or the inbox's gear) for the app badge; `[data-signed-in]` on the tab bar | Unchanged: both elements keep their attributes. |
| `pull-refresh.js`, `scroll-restore.js`, `reconnect.js`, `auto-reload.js`, `music-cards.js`, `install-now.js` | `main` as the scroller (its `overflow-y`, `scrollTop`) | Unchanged: `main` is still the scroller and the band is inside it. |
| `photo-crop.js` l. 293 | sets `inert` on `main` and `.tab-bar` (`device_calls`, a scrolling page) | Unchanged: the band is inside `main`. |
| `reconnect.js` (the progress line, the chip), `pull-refresh.js` (the arrow), `nav-timing.js` (the timing line) | append to `body`; placed by CSS | Unchanged; only `.pull-refresh`'s `top` moves (§4). |
| `app-lock.js` | toggles `html.locked` / `html.veiled`; the CSS hides `body > :not(.top-strip)` | Unchanged: an editor's band is a child of `body` and is hidden with the rest. |
| `calendar-preview.js` (`#rule-form` by id), `rule-kind.js` (`el.form`), `zones.js` (data attributes) | the editors' forms | Unchanged: the forms and their ids stay. |

No script reads `.nav-back`, `.editor-bar`, `.title-bar`, `data-bar`, `data-save`, `.bar-flash` or `.summary-head`,
and none uses `event.submitter`. The lab removed the scripts from its pages, so none of the rows above was seen
there; the lab builder's list was not used for this table.

## 6. Standing rules kept

- **A save never jumps to the top.** Save submits the same form, to the same action with the same `#fragment`, by
  the same `submit` event; only the button's place in the document changed. `scroll-restore.js` is not touched: it
  records the form and its ancestors with an id, not the button.
- **The bell is last, top right, on every tab.** It is written by `band::tab` after the page's own actions; a tab
  page has no other way to get a band.
- **Every string in `nb` and `en`.** One new key, `nav.back_to`, in both files; `account.back` removed from both. Every
  title and parent name is a key that exists.
- **CSP stays** (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`): no inline script, no inline style, the
  mark is the same-origin PNG of today.
- **Versions.** `style.css?v=92` -> 93, the two scripts above. The service worker has no cache to rename: `sw.js`
  caches nothing and its version is the server's.
- **The splash stays as it is.** It is flat night with the mark in the middle; the first page's top is night with the
  mark on every tab now (before: on Devices alone), so the handover is closer than today. No new PNGs,
  `pwa_startup_screens.json` untouched.
- **The manifest's colours stay** (`background_color` and `theme_color` #0C0C14, the `theme-color` meta the same):
  they name the band's colour, now on every page.

## 7. Tests

The server's test crate does not compile on the build machine; these run in CI only, so every change is a string
swap or a moved slice.

Pinned today, and what they pin after:

- `shell::the_tab_pages_open_on_their_large_title`: the band is `main`'s first child on all five tabs with the 36 px
  mark, no `nav-back`, no `vibb-word` and no `title-bar` anywhere; the theme colour line stays.
- `shell::the_app_shell_scrolls_main_not_the_document`: the pins that name removed rules go (`.page-title`'s margin,
  `.editor-bar`, `.title-bar`, `.vibb-brand`, `.vibb-mark`, `main`'s `padding-top` string); the one border-image
  declaration is `.band`'s; the bottom padding is `.tab-bar ~ .container > .sheet`'s. Kept as they are: one
  `position: fixed`, the strip first in every body, the veil rule, `--night`, the manifest.
- `shell::every_template_with_the_bar_sets_its_tab`: "a landing page has no `class="nav-back"`" becomes "a landing
  page calls `band::tab`, every other `band::sub` or `band::editor`".
- `time_pages`: the helper `bar()` (from `data-bar` to `<h1`) reads the band (`<header class="band` to
  `</header>`); l. 324 (Save before the name field inside the form) becomes "Save has `form="rule"`";
  `the_editor_bar_holds_save_and_its_flash` pins `.editor-flash`'s `position: sticky` and that the flash is in it.
- `notifications.rs` l. 513: the bell is looked for from `<header class="band` to `</header>`.
- `device_pages.rs`'s helper `nav_back()`: the words it returns are the hidden span's ("Back to ...").
- `summary.rs`: the escaped name is checked in the band's `h1`, not in the summary head.
- `locations.rs` l. 374: `locate.js?v=6`. `jstest/register-sw.test.js`: the banner lands first in the sheet.
- Untouched: the roughly 45 `<a class="nav-back" href="...">` and `<h1 class="page-title">...</h1>` assertions, the
  auth and lock pages' tests, `every_form_on_every_page_lands_on_its_own_group`.
  `every_class_a_template_uses_is_in_style_css` passes once the new classes have their rules.

New, three, scans of the template sources (no constants repeated):

1. Every template with `<main class="container` calls exactly one `band::` macro and writes no `<h1` itself; the
   partial is the only file with `<h1 class="page-title"`. (Every page has the band; exactly one `h1`.) The rendered
   side is one line more in `every_page_marks_its_own_tab`'s loop: one `<h1` in the page.
2. In the partial, `tab`'s actions box ends with the bell's include. (Bell last.)
3. In each of the four editors the Save button's `form="..."` is the `id` of a `<form` in the same file.

## 8. What only the iPhone can show

The lab ran in desktop Chromium. After the build, before any `server-v` tag, the build is served on the LAN as the
lab was and the user's iPhone (iOS 27.2, `apple-mobile-web-app-status-bar-style: default`) goes through this list,
in a Safari tab and installed, in both schemes:

| What | How it is checked |
|---|---|
| The status bar's join | Installed, at rest on Devices and on Emma's page: no seam between the status area and the band. Scrolled: the strip stays night over the sheet. |
| Standalone safe areas | Installed, upright and on its side: the row clears the status bar, the chevron and the bell clear the notch, the tab bar sits where it sat. |
| WebKit's rubber band | Pull down at the top of Music and of Emma's page: night above the band, never a light gap. Pull up at the bottom: the ground. On a short page (Settings): no worse than Devices today. On an editor: the band does not move. |
| Safari's moving toolbar | In a tab: the band sits right under the toolbar, the toolbar's tint agrees with it, and nothing jumps when the toolbar changes size. |
| The tab bar's glass | A long page scrolled under the pill looks as today; in dark the hairline is only at the sheet's top. |
| Real pull-to-refresh | Devices and Emma's page: the arrow comes down under the band, arms, reloads; the band does not jump. |
| Time and select fields | Bedtime's times, the rule's kind, a zone's fields: the pickers open and close with the band in place. |
| The keyboard against the band | Rule editor: type in the top field and in the last one. The band may pan with the page as today's bar does; with the keyboard closed it is back at the top and "Lagre" works. Background the app for a while and repeat (design 43's stale viewport). |

A finding here is a fix on the same branch, not a new design, unless it costs something the user can see.

## 9. Steps

**One merge.** Not two: a first merge without the editors would leave the four of them for a release with the new
`main` and the old sticky bar - CSS written only to be deleted, and the iPhone list walked twice. There is no clean
halfway commit either: `.container`'s paddings move to the band and the sheet for every page at once. The editors'
part is small and separate enough for the code QA to read on its own:

| Part | Files | Lines, about |
|---|---|---|
| The shell and the 45 scrolling pages | `partials/band.html` (new, ~45 lines), 45 templates (~6 lines each), `style.css` (+90/-45), `register-sw.js`, `locate.js`, `head.html`, `nb.json`, `en.json`, `shell.rs`, `notifications.rs`, `device_pages.rs`, `summary.rs`, `locations.rs`, `jstest/register-sw.test.js`, `docs/notes/server/pwa.md` | 59 files, ~750 |
| The four editors: the band outside `main`, Save's `form=`, `.editor-flash` | 4 templates, `time_pages.rs`, in `style.css` +15 | 5 files more, ~150 |

About 64 files and 900 lines, most of them the same six lines in every template.

## 10. Open points for the user

One.

**O1 - Which phone am I on?** Today ten of a phone's pages (Anrop og kontakter, Tidsregler, Nettfilter, Hjem-skjerm,
Låseskjerm og PIN-koder, Musikk, Kalender, Apper, Om denne telefonen, Avansert) show the phone's name as the back
link's word, "‹ Emma", above the title. In F2b the chevron stands alone and the name is only spoken. The lab had one
phone, so it never showed the cost: with two phones nothing at the top of "Anrop og kontakter" says whose page it is.
Should the phone's name be shown on those ten pages' band?

Recommendation: **yes** - the name at the band's right end, in the band's quiet text (`--night-text-2`, 0.9375rem,
one line), where these pages have no action. The chevron, the title and the band's height stay exactly as the lab
shows them; the name gives way first (an ellipsis) and is not shown on a 374 px or narrower screen when the title is
a long one. The alternative is the lab as shown: no name, and the parent remembers which phone they opened.

The build does not wait for the answer: the ten pages call `band::sub` with an empty body, and a yes is one `<span>`
in each and three CSS rules.

## Where the build leaves the lab

The lab is the look; these are the places where its own code cannot be the build's.

| In the lab | In the build | Why |
|---|---|---|
| `:is()` in its selectors | plain selector lists | `:is()` needs Chrome 88; the floor is 80 |
| `body { background: var(--night) }` | `body` stays `--bg` | It would turn the auth pages' ground night and changes what iOS shows around the page; the band's own outset does the job and is proven on Devices |
| a two-colour gradient on `main` for the bounce | the band's 100vh outset upward | the same reason |
| the title's `line-height: 44px` | unitless, the row `min-height: 44px` | a fixed line with `overflow: hidden` cuts a larger text size |
| the letter count done by the lab's generator | counted in the macro | there is no generator in production |
| the back link's `aria-label` | a hidden span in the link | the same spoken name; the link's tag keeps its form |
| the dark hairline as an inset shadow and a `::after` | a 1 px top border | no extra layer, cannot be covered by scrolled rows |
| the editors' flash lines: nowhere (forms were switched off) | `.editor-flash` (§4) | the lab never showed a save |
| the push banner, the map's scroll offset: not there (scripts were removed) | §5's two changes | the same |
| `device_calendar_caldav`, `device_calendar_link`, an existing title rule, `enroll_code`, `password_sign_in`, `music_spotify_done`: not in the lab | §3's rows | they follow the same two macros as their neighbours |
