# 84 - The PWA's top: one night band on every page (F2b)

Status: design note by the architect, 2026-11-11, on `main` at 9227c709, branch `pwa-band`. Built on branch
`pwa-band-1` (revision 3 below), not merged; the Rust tests have run nowhere yet (CI), and §8 is the user's. Server
only: templates, `style.css`, two scripts, two handlers' back fields and one helper, the ten phone pages' picture,
tests. No API, no migration, no launcher or music change.

Revision 2 (after design QA, 2026-11-11). What moved: the back link's address is an expression, on two pages a
handler's (§2); the first header's rule is the lab's five selectors (§4); the push banner has its spacing (§4, §5);
the new tests count and render, `jstest/locate.test.js` is in, and there is a list of what runs before CI (§7, §9);
the iPhone list is longer and starts with a before picture (§8); O1 has three options and measured widths and is
still open, and O2 (a long name is cut) is new and decided (§10).

Revision 3 (the build, 2026-11-11, branch `pwa-band-1`). Built as written; what differs is said in place. The three
new askama forms compiled as written, so no fallback was taken (§2). O1 is decided, (c): the phone's small picture
on its ten pages (§2, §3, §10). An editor's form is `display: flow-root`, a fault the Chromium pass found (§4).
`device_calls` opens on its empty Held section, so its first header is 40 px under the sheet's edge, as on the lab's
page (§4). The sheet's top is 15 px of padding beside a 1 px border (§4, the last table). §9 has the counts as they
turned out and what was measured.

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
7. A title is one line. One that does not fit is cut with an ellipsis; none wraps (O2, decided by the user, §10).

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
| `band::phone(title, href, parent, who)` | the back chevron | the phone's picture, then the call's body | the ten pages of a phone (O1 (c), §10); `who` is the handler's `devices::BandAvatar` |
| `band::editor(title, href, parent)` | the back chevron | the call's body (Save) | the four editors; adds `band-fixed` |
| `band::bare(title)` | the mark | nothing | `music_spotify_done.html` |

Inputs, each an expression (a macro's argument cannot be template text): `title` is the visible name, already
translated, escaped by askama as any value. `href` is today's back target, `parent` the parent page's name - the word
today's back link shows. `tab` is the page's `{% let tab %}` (the bell's `?from=`). The page's actions are the call's
body (`{{ caller() }}` in the macro): today's `title-action` elements moved over unchanged. The bell is not an input:
`tab` includes `partials/bell.html` after the body, so it is last by construction and a page cannot place it
elsewhere.

**The href.** Today's 43 back links are template text, 23 of them with `{{ }}` inside. As an argument:

| Today's text | The argument | Templates |
|---|---|---|
| a literal (`/settings`, `/music#spotify`) | the string: `"/settings"` | 20 |
| one value (`{{ back_href }}`, `{{ back.1 }}`) | the field | `device_apps`, `schedule_rule`, `notifications` |
| text around a value (`/devices/{{ device.id }}`, `/devices/{{ device_id }}/calendar#sources`, `/devices/locate?device={{ device.id }}#zones`, `/music#entry-{{ entry.id }}`, `/notifications{{ query }}`) | askama's `~`: `"/devices/" ~ device.id`, `"/devices/" ~ device_id ~ "/calendar#sources"` | 18 |
| an `{% if enrolled %}` inside the attribute, and a conditional word | two handler fields, `back_href` and `back_label`, as `device_apps` and `schedule_rule` have | `enroll_code` (`handlers/devices.rs`), `provision_qr` (`handlers/provisioning.rs`) |

`~` is in askama 0.16.0's parser and generator (`Expr::Concat`, read in the source on the build machine) and in no
template here before this. It compiles in a macro call with an integer on one side (the spike, §9), written with a
space on both sides, which askama's parser wants (`"/devices/" ~ device.id`), so none of the 18 took a `back_href`
field. Askama 0.16 has no conditional expression, so
the two conditional pages take handler fields either way: the handler has `enrolled`, the id and the name and writes
the same two strings. The rendered `<a class="nav-back" href="…">` stays byte for byte: a `~` value is escaped as a
whole where today only the `{{ }}` part is, askama's escaper touches `< > & " '` alone, none of the template text has
one, and `query` and `back.1` are escaped as today. (§9's curl step found every pinned string.)

How askama passes them. Read in `askama_derive` 0.16.0's source on the build machine (`generator/node.rs`,
`helpers/macro_invocation.rs`): call bodies and `caller()` exist. A macro that calls `self.t(...)` exists here
(`group_flash.html`'s `show`), so the hidden span's words are no risk. New here are three forms: `caller()` with a
call body, composed arguments, and an `{% include %}` (`bell.html`, which reads `bell`) inside an imported macro -
all three compiled as written in the spike (§9), and so did two forms the build added: a macro that calls its
sibling macros by their bare names (`size`, `back`) from the imported file, and `{% if let Some(src) = who.photo %}`
in `phone`. No fallback was taken; it would have been a pair of macros per kind (`tab_open(title)` /
`tab_close(tab)`) with the actions between them.

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

On the ten phone pages (`band::phone`) one element more stands between the `h1` and the actions box - the summary's
avatar with the list's own classes, never spoken and never a link (§10, O1):

```html
    <span class="band-who" aria-hidden="true"><span class="avatar [avatar-grey]">E[<img class="avatar-img"
        src="/phone-avatars/…" alt="" decoding="async">]</span></span>
```

- **Exactly one `h1`**, and it stays `<h1 class="page-title">X</h1>` with no other attribute; the back link stays
  `<a class="nav-back" href="X">`. The two class names are kept on purpose: about 45 test assertions spell exactly
  those two strings and stay true. Their CSS rules are rewritten (§4).
- **The back link's spoken name** is a `visually-hidden` span (the class exists), not an `aria-label`: the same
  accessible name, and the link's opening tag keeps its pinned form. New string `nav.back_to`: "Back to {name}" /
  "Tilbake til {name}". `account.back` (the one back word with a key of its own) goes; Account passes `nav.settings`.
  `parent` arrives as a `&str` on most pages (`self.t(...)`) and as a `String` field on others (`device.name`,
  `back_label`), and `tp` takes `&str` pairs alone: the macro calls a new helper beside `tp` in `i18n.rs`'s `Words`,
  `back_to(&self, name: impl Display) -> String`, which takes both. The partial writes
  `</svg><span class="visually-hidden">…</span></a>` with nothing between the tags (§7's `nav_back()`).
- **The mark** is decorative (`alt=""`) and is not a link. Today's brand is a `<span>` that does nothing on a tap
  (read in `devices_list.html`); the band's mark does nothing either.
- **Title size class** on the `<header>`, never on the `h1`: `band-long` for a title over 16 letters, `band-xlong`
  over 24 (the lab's two steps), counted in the partial by a small macro of its own, `size(title)`
  (`title.chars().count()`), which the other macros call; the back link is a second one, `back(href, parent)`. The
  letters are the translated string's: a page can be `band-long` in one language and not in the other.
- **Focus order** follows the source: the tab bar (first in the source, as today), the back link, the page's actions,
  the bell, then the content. On an editor: tab bar, back, Save, the form's fields - as today.
- `<header>` inside `<main>` is no landmark; the editors' band, a child of `<body>`, is the page's banner. Accepted.

Where it goes. A scrolling page: `<main class="container">`, the band as its first child, then one
`<div class="sheet">` holding everything the page has today below its title. `<main>` stays the scroller - the
scripts in §5 find it so. An editor: the band between the tab bar partial and `<main class="container
container-fixed">`, a normal flex child of `<body>` - nothing fixed, nothing sticky; the form stays `main`'s child.
`{% import "partials/band.html" as band %}` goes after the tab bar's include, where `group_flash`'s import is today -
never between `{% let tab = … %}` and the include (`shell.rs` reads the line right before the include, §7).

Save on the band: `<button type="submit" class="title-action" form="rule" data-save="rule">Lagre</button>` - today's
button with one attribute more, `form`, naming the form's `id` (all four forms have one). The order is load-bearing:
`form=` before `data-save`, which stays the last attribute (`time_pages.rs:1293` pins
`data-save="bedtime">Add</button>`). A `form=` button that comes before its form is still that form's default button,
so Enter in a field saves as today.

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
| `device_zone` | `{{ title }}` (the zone's name, "Ny sone") | `/devices/locate?device={id}#zones` (`nav.locate`) | `form="zone-form"`, `data-save="zone"`, Add when new |
| `device_calendar_rule` | `{{ title }}` | `/devices/{id}/calendar#rules` (`calendar.title`) | `form="rule-form"`, `data-save="rule"`, Add when new |

**Pages under a tab** - `band::sub`, no action on the band unless said. The ten pages of a phone - the three rows
from `device_about` to `device_calendar`, and `device_apps` - call `band::phone` with their handler's `who` (O1 (c),
§10); none of the ten has an action on the band.

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
| `enroll_code` | `devices.code.title` | `{{ back_href }}` (`{{ back_label }}`), new handler fields (§2): `/devices/{id}/advanced#connection` (`device.page.advanced`) once enrolled, else `/devices/{id}#access` (the phone's name) |
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
| `music_spotify_done` | `band::bare`, title `music.settings.short`, its sentence in the sheet | It is a `main.container` page (no session, no tab bar, no `h1` today) that passes on to Music Settings at once (`/music/settings?login=…#spotify-accounts`; the code QA, L3). With the band it has the same top as the page it lands on, and the rule "every `main.container` opens on the band" has no exception. No bell and no back: it has no session and nowhere to go back to. |
| `login`, `verify_2fa`, `setup_2fa`, `change_password` | nothing new | `body.auth-page`: one centred card with its own `h1.auth-title`, no tab bar, the document scrolls (design 43 §1.4). There is no page name, action or bell to put on a band, and a band above a centred card is a second header. The night strip behind the status bar is on them already. |
| `lock` | nothing new | The whole page is night with the breathing mark; the splash hands over to it without a change. |

Leaving these five without a band is the architect's choice, not the lab's: the lab did not have them, and its page
for a missing page says "Utseendet øverst er det samme på alle sider". The coordinator has told the user so in a
line.

## 4. The CSS

Browser floor as before (Chrome 80, Safari 14): no `:is()`, no `:has()`, no nesting. `min()`/`max()` are in use today.

**New tokens.** `--band-top: max(calc(6px + env(safe-area-inset-top, 0px)), var(--top-strip))` (the band's top
padding), `--band-h: calc(var(--band-top) + 54px)` (its visible height: 60 px in a browser tab, 66 px in the installed
app on iOS 27, 60 px + the inset under the old translucent bar), `--band-edge: transparent`, in the dark scheme
`#34344A` (the colour exists today only as dark `--track` and `--switch-off`).

**Rules that come.**

- `.band`: `display: flex; align-items: center; background: var(--night); color:
  var(--night-text)`, top padding `--band-top`, sides exactly `.container`'s side paddings of today (16 px, the safe-area insets, the 720 px column),
  `padding-bottom: 32px; margin-bottom: -22px` - 10 px of band under the row, then 22 px that the next box covers. The
  row is 44 px at least (the band has no inner box, so the build writes it as the band's own `min-height:
  calc(var(--band-h) + 22px)`), so a larger text size grows the band instead of cutting it. `flex: none` is
  `.band-fixed`'s alone: only an editor's band is a flex child (of `body`); a scrolling page's is a block in `main`. The overlap is the band's
  own rule, so it holds for an editor's band as a child of `body` too: what shows behind `main`'s rounded corners
  there is the band's foot. (The lab's `body` was night and hid the difference; the build's is `--bg`.)
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
  Two small rules the build added: `.band-actions:empty { display: none }` (a page with no action - most of them -
  leaves the box's 8 px to the title) and `.nav-back:hover` in `--night-action-hover`, as the actions have.
- `.sheet`: `background: var(--bg); border-top: 1px solid var(--band-edge); border-radius: 22px 22px 0 0`, top
  padding 15 px (the border is there in both schemes, transparent in light, and takes its pixel: 15 + 1 are the lab's
  16 px from the sheet's edge to its first row), the sides as `.band`, bottom `calc(16px + env(safe-area-inset-bottom, 0px))`.
  `.tab-bar ~ .container > .sheet` takes today's tab-bar room as its bottom padding. `.container-narrow > .band` and
  `.container-narrow > .sheet` take the 480 px column.
- `.container-fixed` (an editor's `main`): today's side and bottom paddings of `.container`, `padding-top: 15px`
  (and the 1 px border, as the sheet), `background: var(--bg)`, the same border and radius as `.sheet`. It scrolls inside its rounded top, so rows pass
  under the curve and the hairline never moves.
- `.editor-flash`: the editors' "Ikke lagret ..." / "Lagret ..." lines, today inside the sticky bar. It is the form's
  first box - its first child but for the hidden fields that `schedule_rule` and `device_calendar_rule` start with -
  rendered only when there is a flash, holding today's `.bar-flash` paragraphs in today's look:
  `position: sticky; top: -15px; z-index: 5; margin-top: -15px; padding: 15px 0 8px; background: var(--bg)` - the
  sticky bar's own arithmetic (an offset counts from inside the scroller's padding, 15 px here), so the line stays
  where it is before any scrolling. The flash is in view wherever the page comes back, as today, and nothing new is
  drawn on the night band. One rule more, found by the build's Chromium pass: `.container-fixed > form { display:
  flow-root }`. Without it the flash's negative margin collapsed through the form and moved the form's own top edge
  15 px up, and `scroll-restore.js` puts the form back at the screen top it had before the save - a save came back
  15 px off (331 -> 316). With it a save comes back where it was (§9). `flow-root` is inside the floor (Chrome 58,
  Safari 13).
- The push banner (§5: `.attention.push-banner`, `margin: 0 0 8px`, its look unchanged). On a scrolling page it is
  the sheet's first child, 16 px under the sheet's edge. The header after it is then no first child and keeps its
  own 24 px, into which the banner's 8 px collapse: 24 px from banner to header (12 px to the summary head) - the
  gap today's banner has under the title, with no new rule. On an editor it is first in `main`, right before the
  form, and `.editor-flash`'s `margin-top: -15px` would lay the flash over the banner's margin and its lowest 7 px.
  One rule: `.push-banner + form > .editor-flash { margin-top: -8px }` - the flash starts at the banner's lower
  edge with its line 15 px under it, and once scrolled it sticks exactly as without a banner. (Measured in §9's
  Chromium pass, both cases: the banner at 76-139, a header 24 px under it, an editor's form 8 px under it, the
  flash box from 139 with its line at 154 and, scrolled, back at 76.)

**Rules that change.** `.container`: `padding: 0` (its top, side and bottom paddings move to the band and the sheet);
`flex`, `min-height`, `overflow-y`, `overscroll-behavior-y`, `touch-action` and the desktop `overflow-y: scroll`
stay. `.tab-bar ~ .container` keeps `scroll-padding-bottom`; its `padding-bottom` is now `.tab-bar ~ .container > .sheet`'s
and `.tab-bar ~ .container-fixed`'s.
`.container > form > .button-wide` becomes `.sheet > form > .button-wide, .container-fixed > form > .button-wide`.
`.pull-refresh`: `top: calc(var(--band-h) + 6px)`, so the arrow comes down over the
sheet, not over the title. `.net-progress`, `.net-chip`, `.timing-line` keep their places from `--top-strip`: they
lie over the band's top as they lie over Devices' night bar today.

The page's first header: `margin-top: 10px` in place of its 24 px, so it starts 26 px under the sheet's edge as in
the lab (40 px without). The lab's five selectors (`lab-f2b.css` l. 99-103) as a plain list of nine, written for
`.sheet` alone:

```css
.sheet > .group-header:first-child,
.sheet > .group-head:first-child,
.sheet > [hidden]:first-child + .group-header,
.sheet > form:first-child > .group-header:first-child,
.sheet > form:first-child > .group-head:first-child,
.sheet > section:first-child > .group-header:first-child,
.sheet > section:first-child > .group-head:first-child,
.sheet > section:first-child > section:first-child > .group-header:first-child,
.sheet > section:first-child > section:first-child > section:first-child > .group-header:first-child
```

The eleven templates that open on a `<section>` with its header first (`device_about`, `device_advanced`,
`device_calls`, `device_lock`, `device_time`, `dns_filter`, `schedule_budget`, `schedules`, `security`, `updates`,
`wallpapers`) take the sixth line, `devices_list` (the hidden push offer first) the third. In `device_calls` and
`device_time` the first node is a loop over the page's warnings: with none the section is the sheet's first child,
with one the warning is, and the header under it keeps its 24 px. `device_calls` takes the line only while messages
wait: its first section is Held, which is always there and empty without them, so the first header a parent sees is
the next section's with its own 24 px - 40 px under the edge, as on the lab's calls page - or, with no request
either, no header at all (the Samtaler group, 16 px under the edge). Measured: 26 px on the other ten and on
`devices_list`, at 390 and 320 px. `.sheet > .summary-head:first-child`: 4 px (the lab's l. 104). The lab wrote the list for its editors' `main` too; the build does not write those nine lines for
`.container-fixed`, because they would match nothing: the four editors' first `<section>` has an `aria-label` and no
header of its own (read in the four files), so a flash as the form's first box moves no header either.

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
  about 0.56 em a letter, a first estimate; the ten phone titles measured on the lab's screenshots since (§10, O1)
  come to 0.49-0.53 em, so the table errs on the safe side.
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
| `register-sw.js` `banner()` (l. 246-271) | `main.querySelector(".page-title")`; puts the push banner after the title when the title is `main`'s child, else first in `main` | **Changes.** The banner goes in as the first child of `main > .sheet`; on an editor (no sheet) first in `main`, before the form. Never before the band. Its spacing in both places is §4's. The scroll compensation stays as written. `?v=5` -> 6. |
| `locate.js` `mapIntoView()` (l. 255-264) | `main`'s computed `padding-top` as the map's offset under the status bar | **Changes.** `main`'s top padding is 0 now: the offset is the `.top-strip`'s height + 8 px. `?v=5` -> 6 (two templates and `locations.rs`'s pin). `jstest/locate.test.js` pins the old offset (§7). |
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
swap or a moved slice, and §9 lists what is run before CI.

Pinned today, and what they pin after:

- `shell::the_tab_pages_open_on_their_large_title`: the band is `main`'s first child on all five tabs with the 36 px
  mark, no `nav-back`, no `vibb-word` and no `title-bar` anywhere; the theme colour line stays.
- `shell::the_app_shell_scrolls_main_not_the_document`: the pins that name removed rules go (`.page-title`'s margin,
  `.editor-bar`, `.title-bar`, `.vibb-brand`, `.vibb-mark`, `main`'s `padding-top` string); the one border-image
  declaration is `.band`'s; the bottom padding is `.tab-bar ~ .container > .sheet`'s. Kept as they are: one
  `position: fixed`, the strip first in every body, the veil rule, `--night`, the manifest.
- `shell::every_template_with_the_bar_sets_its_tab`: "a landing page has no `class="nav-back"`" becomes "a landing
  page opens the tab's band, every other a sub page's or an editor's". The test takes the line right before the tab
  bar's include and needs it to be `{% let tab = … %}`: the band's import goes after the include (§2).
- `time_pages`: the helper `bar()` (from `data-bar` to `<h1`) reads the band (`<header class="band` to
  `</header>`); l. 324 (Save before the name field inside the form) becomes "Save has `form="rule"`";
  `the_editor_bar_holds_save_and_its_flash` pins `.editor-flash`'s `position: sticky` and that the flash is in it.
  L. 1293 (`data-save="bedtime">Add</button>`) stays true only while `data-save` is Save's last attribute (§2).
- `notifications.rs` l. 513: the bell is looked for from `<header class="band` to `</header>`.
- `device_pages.rs`'s helper `nav_back()` (l. 3247-3258) returns what follows `</svg>` in the link: after the change
  that is the hidden span, tags and all. The helper returns the span's text, and l. 3317 and l. 3352 expect
  "Back to " and the name they expect today; the partial leaves no whitespace around the span (§2).
- `summary.rs` l. 62-64: `header()`'s slice of the summary head no longer has the name. The escaped name is looked
  for in `<h1 class="page-title">…</h1>`, the unescaped one in the whole page.
- `locations.rs` l. 374: `locate.js?v=6`. `jstest/register-sw.test.js`: the banner lands first in the sheet, and on
  a page without a sheet first in `main`.
- `jstest/locate.test.js` l. 256-268 pins the fake `main`'s `paddingTop: "55px"` and `scrollTop == 900 - 840 - 55`:
  the fake page gets a `.top-strip` with a height, and the expected place is `900 - 840 -` that height `- 8`.
- Found by the build, outside this list: `settings_pages.rs` (two assertions on a Settings sub page's back link
  spelled its visible word, `</svg>Innstillinger</a>`; they spell the hidden span, "Tilbake til Innstillinger").
  New with O1 (c): `device_pages::about_this_phone_shows_the_last_reports_facts` finds the phone's letter with its
  class on the band between the `h1` and the actions box, and no `band-who` on the summary or on Settings;
  `avatars.rs` finds the photo on a phone page's band, from the route the summary uses.
- Untouched: the roughly 45 `<a class="nav-back" href="...">` and `<h1 class="page-title">...</h1>` assertions, the
  auth and lock pages' tests, `every_form_on_every_page_lands_on_its_own_group`.
  `every_class_a_template_uses_is_in_style_css` passes once the new classes have their rules.

New, three. The first two are worded to hold whichever macro form the spike leaves (§2's fallback, §9):

1. A source scan that counts. The templates that open a band are exactly all of `templates/*.html` less `login`,
   `verify_2fa`, `setup_2fa`, `change_password` and `lock`, which the test names - 49 today, and a new template is
   in the set or the test fails. Each of them opens one band and writes no `<h1` itself; the partial is the only
   file with `<h1 class="page-title"`. The rendered side is one line more in `every_page_marks_its_own_tab`'s loop:
   one `<h1` in the page.
2. Rendered, in `shell.rs`'s loop over the five tabs: between `<header class="band` and `</header>` the last
   `title-action` is the `title-bell`, and the page has exactly one `title-bell`. (Bell last, whatever the partial
   looks like.)
3. Named, not searched for: `schedule_rule.html` has `form="rule"`, `device_bedtime.html` `form="bedtime"`,
   `device_zone.html` `form="zone-form"`, `device_calendar_rule.html` `form="rule-form"`, and each file has a
   `<form` with that `id`.

## 8. What only the iPhone can show

The lab ran in desktop Chromium. After the build, before any `server-v` tag, the build is served on the LAN as the
lab was and the user's iPhone (iOS 27.2, `apple-mobile-web-app-status-bar-style: default`) goes through this list,
in a Safari tab and installed, in both schemes. First, before the build is served, a before picture: today's Devices
(the one page with a night bar) and today's Settings, in a tab and installed - at rest, pulled down at the top,
pulled up at the bottom. "As today" below means those pictures. The 320 px width and Android Chrome are not rows
here: they belong to §9's Chromium pass.

| What | How it is checked |
|---|---|
| The status bar's join | Installed, at rest on Devices and on Emma's page: no seam between the status area and the band. Scrolled: the strip stays night over the sheet. |
| Standalone safe areas | Installed, upright and on its side: the row clears the status bar, the chevron and the bell clear the notch, the tab bar sits where it sat. |
| WebKit's rubber band | Pull down at the top of Music and of Emma's page: night above the band, never a light gap. Pull up at the bottom: the ground. On a short page (Settings): as Devices and Settings in the before pictures, or better. On an editor: the band does not move. |
| Safari's moving toolbar | In a tab: the band sits right under the toolbar and nothing jumps when the toolbar changes size. The toolbar's tint is Safari's own choice and may differ from the band: accepted when it is as on Devices in the before picture. |
| The tab bar's glass | A long page scrolled under the pill looks as today; in dark the hairline is only at the sheet's top. |
| Real pull-to-refresh | Devices and Emma's page: the arrow comes down under the band, arms, reloads; the band does not jump. On an editor (no pull-to-refresh there): a pull at the top bounces the sheet inside its rounded top, the band stays, nothing reloads. |
| Time and select fields | Bedtime's times, the rule's kind, a zone's fields: the pickers open and close with the band in place. |
| The keyboard against the band | Rule editor: type in the top field and in the last one. The band may pan with the page as today's bar does; with the keyboard closed it is back at the top and "Lagre" works. Background the app for a while and repeat (design 43's stale viewport). The same once with the phone on its side: the field being typed in is in view, and "Lagre" is in reach when the keyboard closes. |
| A save on each editor | In each of the four: scroll down, change a field, "Lagre". The page comes back where it was with "Lagret ..." in view under the band, and the line stays there while scrolling. A refused save the same with "Ikke lagret ...". |
| The push banner | With notifications off on this iPhone: the banner on Devices sits in the sheet above the first header; on the rule editor above the form, and after a save there the flash does not cover it. |
| Long names | A phone named with 40 letters and a rule with 60: one line with an ellipsis, the chevron and "Lagre" whole (O2). |
| Landing in the middle, then Back | From the QR page, Back to Emma's page at `#access`: the group is in view, the band has scrolled away, the strip is night. Back again: Devices at its old place. |
| iOS 26's translucent status bar | **Not checked**: the user's iPhone is on 27.2. `.top-strip` and `--band-top` keep the rules written for it; nothing of this design has been seen on iOS 26. |

A finding here is a fix on the same branch, not a new design, unless it costs something the user can see.

## 9. Steps

**One merge.** Not two: a first merge without the editors would leave the four of them for a release with the new
`main` and the old sticky bar - CSS written only to be deleted, and the iPhone list walked twice. There is no clean
halfway commit either: `.container`'s paddings move to the band and the sheet for every page at once. The editors'
part is small and separate enough for the code QA to read on its own:

| Part | Files | Lines, about |
|---|---|---|
| The shell and the 45 scrolling pages | `partials/band.html` (new, ~45 lines), 45 templates (~6 lines each), `style.css` (+110/-45), `register-sw.js`, `locate.js`, `head.html`, `nb.json`, `en.json`, `i18n.rs` (`back_to`), `handlers/devices.rs` and `handlers/provisioning.rs` (the two back fields), `shell.rs`, `notifications.rs`, `device_pages.rs`, `summary.rs`, `locations.rs`, `jstest/register-sw.test.js`, `jstest/locate.test.js`, `docs/notes/server/pwa.md` | 63 files, ~820 |
| The four editors: the band outside `main`, Save's `form=`, `.editor-flash` | 4 templates, `time_pages.rs`, in `style.css` +15 | 5 files more, ~150 |

About 68 files and 970 lines, most of them the same six lines in every template.

**Before CI.** The test crate cannot be compiled on the build machine, and three askama forms are new here (§2). So,
in this order:

1. A spike commit first: `band.html` and one page of each kind - a tab, a sub page with an id in its href, an
   editor, `enroll_code`, `music_spotify_done` - and a plain local `cargo build -j 1` of the binary. It settles the
   macro form and `~` before the other 44 templates are written.
2. `node --test jstest/*.test.js` locally.
3. A throwaway server, and `curl` with grep for the literals the tests pin: `<h1 class="page-title">…</h1>`,
   `<a class="nav-back" href="…">`, `data-save="bedtime">Add</button>`, one `<h1` on every page.
4. A Chromium pass at 320 and 390 px, light and dark, and once with JS off: a save's flash on each editor, the push
   banner on a scrolling page and on an editor, a 40-letter phone name.

Then CI by dispatch.

**As it turned out (2026-11-11, branch `pwa-band-1`).** Two commits and a third for the pass's fix and the notes: the
spike (11 files, +119/-26; its sub page with an id in the href was `device_calendar_add`), then everything else (67
files, +881/-378). 49 templates call the band - 5 tabs, 29 `sub`, 10 `phone`, 4 editors, 1 `bare` - and five have none
(`login`, `lock`, `change_password`, `setup_2fa`, `verify_2fa`). The binary built without a warning, `cargo fmt
--check` and clippy on the binary are clean, the 335 `jstest` tests pass. Not run: the Rust tests (the test crate is
not compiled on the build machine; CI) and §8.

- *curl and grep*, a throwaway server with a seeded family, signed in, `?lang=nb` and `?lang=en`: all 49 answer 200
  with exactly one `<h1`, one band and, but for the editors, one sheet; every literal the tests pin was found.
- *The sweep*, Chromium, 71 addresses at 390 and 320 px, light in both languages and dark: 426 loads, no sideways
  scroll of the document, `body` or `main`, one band as wide as the screen, the title on one line, the back link
  46x44 px, every action on the screen. The band is 82 px high in a browser tab (no inset) and the sheet or an
  editor's `main` starts at 60; its edge is 1 px, transparent in light and `#34344A` in dark, radius 22 px.
- *A long name*: the 40-letter phone's name on its own summary is 32 px too long at 390 px (19 px type) and 69 px at
  320 px (17 px type): 36 and 31 of its letters would fit, less the ellipsis's own width, on one line. Beside the
  picture at 320 px "Låseskjerm og PIN-koder" is 5 px too long and reads "Låseskjerm og PIN-ko…"; "Lock Screen &
  PINs" and "Anrop og kontakter" are whole. Nothing else is cut.
- *A save's place* (`main.scrollTop` before -> after, at 390x560 and 320x480): `schedule_rule` 331 -> 331 and
  518 -> 518, `device_bedtime` 114 -> 114 and 424 -> 424, `device_zone` (a refused save) 275 -> 275 and 355 -> 355;
  the flash at 76, right under the band, in each, and still there scrolled to the end. A zone's good save leaves for
  Locate and lands on its row. `device_calendar_rule`'s refused save lands on the form's top with the refusal in
  view: its form posts to another path than the page's, so `scroll-restore.js` has nothing to restore - as before
  this design.
- *Scripts off*: the band and the sheet the same; a save on the rule editor shows its flash at 76.
- *The push banner* (the page's own script builds it in the place `register-sw.js` gives it): §4's numbers, and no
  sideways scroll at either width.
- *The landing* from the QR page's back link, `/devices/{id}#access`: the group at the top of the view, the band
  scrolled away.

## 10. Points for the user

Both decided.

**O1 - Which phone am I on? Decided: (c), the phone's small picture.** The user, 2026-11-11: "Enig i c!". What was
built stands under the three options, which are kept as they were asked. Before this design ten of a phone's pages (Anrop og kontakter, Tidsregler,
Nettfilter, Hjem-skjerm, Låseskjerm og PIN-koder, Musikk, Kalender, Apper, Om denne telefonen, Avansert) show the
phone's name as the back link's word, "‹ Emma", above the title. In F2b the chevron stands alone and the name is
only spoken. The lab had one phone, so it never showed the cost: with two phones nothing at the top of "Anrop og
kontakter" says whose page it is. Three ways:

- **(a) As the lab: no name.** The parent remembers which phone they opened. Nothing more is built.
- **(b) The name at the band's right, where it fits.** This is not the small addition revision 1 called it. The
  actions box is `flex: none`, so a name inside it never gives way - the title would be cut for it. The name has to
  be a flex item of its own between the title and the actions box (a fourth argument, or a macro of its own):
  `.band-who { flex: 0 9999 auto; min-width: 0; margin-left: auto; padding-left: 12px; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis }`, 0.9375rem in `--night-text-2`, and `.band-who + .band-actions` gives
  up its `margin-left: auto`. The title keeps `flex: 0 1 auto`, so the name takes the whole shortage before the title
  loses a letter, and at 374 px and narrower it is not shown beside a `band-long` title. What fits:

  | Title (nb) | Room right of the title, px, at 390 / 320 | Name letters that fit, at 390 / 320 |
  |---|---|---|
  | Apper | 264 / 194 | 31 / 22 |
  | Musikk | 256 / 186 | 30 / 21 |
  | Kalender | 236 / 166 | 28 / 19 |
  | Avansert | 234 / 164 | 27 / 19 |
  | Nettfilter | 232 / 162 | 27 / 18 |
  | Tidsregler | 220 / 150 | 26 / 17 |
  | Hjem-skjerm | 196 / 126 | 23 / 14 |
  | Anrop og kontakter | 124 / 81 | 14 / not shown (8) |
  | Om denne telefonen | 118 / 80 | 13 / not shown (8) |
  | Låseskjerm og PIN-koder | 64 / 32 | 6 / not shown (2) |

  The room is measured on the lab's light screenshots, from the title's last pixel to the band's 16 px side padding
  (a 375 px screen has 15 px less than the 390 column). The letters are an estimate: a 12 px gap, then 8 px a letter
  (0.52 em at 15 px; "Emma", with its wide letters, is about 42 px). The English titles were not measured. So at
  390 px "Emma" is whole on all ten and a ten-letter name is cut to about six letters on "Låseskjerm og PIN-koder";
  at 320 px the three long titles have no name at all. The name is there on most pages and short or gone exactly on
  the longest titles.
- **(c) The phone's small picture at the band's right.** The summary's avatar - the photo, or the initial on the
  phone's colour - at 28 px. Its width is fixed (36 px with its gap). It says which phone without words, as far as
  the pictures differ: two phones with no photo and the same initial differ by the colour alone.

**(c) as built.** The ten pages call `band::phone(title, href, parent, who)`. `who` is `devices::BandAvatar`
(`initial`, `avatar`, `photo`): the letter, the colour's class and the photo's address that the list and the summary
show, made in `handlers/devices.rs` (`BandAvatar::of(&Device)`; Device › Calendar reads the
phone's row once for its name and the picture, the code QA's L1); the ten handlers were read one by one and each passes it. The picture is
`<span class="band-who" aria-hidden="true">` around the list's own `.avatar`, 28 px, the photo with an empty `alt`:
never spoken and never a link - the back link still says "Back to Emma". It stands between the title and the actions
box; none of the ten pages has an action on the band. The summary has no picture on its band (its large one is right
under it), and nothing of (b) is built. What it costs, measured: the picture is always shown and the title gives way.
At 390 px all ten titles are whole in both languages; at 320 px "Låseskjerm og PIN-koder" is 5 px too long and reads
"Låseskjerm og PIN-ko…", and every other Norwegian title and all ten English ones are whole.

**O2 - A long name is cut. Decided.** The user, 2026-11-11: "Langt telefonnavn kan trygt kuttes, det er et
kjempe-edge case". A title that does not fit its line is cut with an ellipsis, on every page; no title wraps to a
second line, and the band has one height everywhere - what the lab's CSS does. What it means where today's titles
wrap: a phone's name (up to 40 letters) is cut after about 30 at 390 px and about 27 at 320 px *(estimates, from
0.52 em a letter)*, and the summary shows it in full nowhere else; a rule's and a music entry's name (60) and a
zone's (40) are cut too and stay whole in their own name field on the same page.

## Where the build leaves the lab

The lab is the look; these are the places where its own code cannot be the build's.

| In the lab | In the build | Why |
|---|---|---|
| `:is()` in its selectors | plain selector lists | `:is()` needs Chrome 88; the floor is 80 |
| `body { background: var(--night) }` | `body` stays `--bg` | It would turn the auth pages' ground night and changes what iOS shows around the page; the band's own outset does the job and is proven on Devices |
| a two-colour gradient on `main` for the bounce | the band's 100vh outset upward | the same reason |
| the title's `line-height: 44px` | unitless, the row `min-height: 44px` | a fixed line with `overflow: hidden` cuts a larger text size |
| the letter count done by the lab's generator, on Norwegian | counted in the macro, on the translated string | there is no generator in production; a title's step can differ between `nb` and `en` |
| rem sizes for the title | each capped with a `vw` value (§4) | a one-line band cannot hold a doubled title: at 200 % text it grows by about a fifth - the one text that does not double; page zoom works as everywhere |
| the mark's `border-radius: 50%` | not written | the icon's corners are its own night ground, the band's colour: nothing to see either way |
| Save's padding `0 2px 0 8px` | today's `.title-action`, `0 4px` | one padding for every action on the band; "Lagre" ends 2 px further in, in line with the bell |
| a focus ring of its own (offset 1 px, `border-radius: 8px`) | the PWA's ring (2 px, offset 2 px) in `--night-action`, the back link's inside (-4 px) | one focus look in the app |
| `music_storytel`'s first footer keeps its inline `margin: -8px 4px 20px` | `margin: 0 4px 20px` | the -8 px closed up to a title that is now on the band; it would lift the text to 8 px under the sheet's edge |
| the back link's `aria-label` | a hidden span in the link | the same spoken name; the link's tag keeps its form |
| the dark hairline as an inset shadow and a `::after` | a 1 px top border | no extra layer, cannot be covered by scrolled rows |
| the editors' flash lines: nowhere (forms were switched off) | `.editor-flash` (§4) | the lab never showed a save |
| the push banner, the map's scroll offset: not there (scripts were removed) | §5's two changes | the same |
| `device_calendar_caldav`, `device_calendar_link`, an existing title rule, `enroll_code`, `password_sign_in`, `music_spotify_done`: not in the lab | §3's rows | they follow the same two macros as their neighbours |
| the sheet's top padding 16 px, no border in light | 15 px and a 1 px border that is transparent in light | one box model in both schemes: the first row is 16 px under the sheet's edge either way |
| the first-header list written for the editors' `main` too | for `.sheet` alone | no editor opens on a header (§4) |
| a phone's ten pages: the chevron alone, no word of whose page it is | the phone's small picture at the band's right | O1 (c), the user's choice after the lab (§10) |
