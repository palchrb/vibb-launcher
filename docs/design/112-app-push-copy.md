# Settings › App push: copy and layout proposal

Read from `origin/up-r2r3-server` (d66b9f8d6): `server/templates/settings_push.html`, `server/src/handlers/app_push.rs`
(`render_settings`, `DeliveryRows`), `server/src/i18n/{nb,en}.json` (`app_push.*`), designs 108 §3.1/§3.10, 108b §3.4,
108c §7, `docs/notes/server/pwa.md`. Note: `app_push_group.html` is not on this page - it is Device › Apps' group (the
per-phone switch and the app rows). The Settings page has no switch of its own: saving an address turns app push on,
emptying it turns it off.

## 1. Today's page (nb), top to bottom

Typical state: address saved, 20+ pushes in the last day, no warning, no refusal, no old address.

| # | Key | nb text |
|---|-----|---------|
| 1 | `app_push.settings.title` (band) | Push for apper  ‹ Innstillinger |
| - | `app_push.settings.no_listener` (only without `DEVICE_BIND_ADDR`) | Denne serveren har ingen egen lytter for telefonene (DEVICE_BIND_ADDR), så push for apper blir ikke servert. Se DEPLOY.md, «App push». |
| 2 | `app_push.settings.origin` (field label) | Telefonenes offentlige adresse |
| 3 | `app_push.settings.origin_example` (placeholder) | https://phones.example.com |
| 4 | `app_push.settings.origin_hint` (or `.prefilled` / `.refused`) | Adressen appenes servere sender til, for eksempel https://phones.example.com. Den må nå denne serverens telefonport fra internett, uten noe etter navnet. |
| - | `app_push.settings.prefilled` | Fylt inn fra Telefonoppsett - ikke lagret ennå. |
| - | `app_push.settings.refused` | Adressen må være https:// og bare et vertsnavn |
| - | `app_push.settings.previous` (per old address) | {origin} virker også til {when}, mens appene flytter til den nye adressen. |
| 5 | `app_push.settings.save_note` | Når du lagrer, får alle telefonene push for apper (du kan slå det av per telefon) |
| 6 | `common.save` | Lagre |
| 7 | `app_push.settings.footer` | Med push for apper får apper på en telefons liste (Element X først) varslene sine gjennom Vibb One og denne serveren, uten Google. Det krever at telefonenes adresse kan nås fra internett; en server som bare er på tailnettet, får ikke push for apper. |
| 8 | `app_push.settings.clear_note` (when set, in the footer) | Tøm feltet og lagre for å slå av push for apper på alle telefonene. |
| - | `common.saved` (flash after save) | Lagret |
| 9 | `app_push.settings.seen` (header) | Sist sett utenfra |
| 10 | `app_push.settings.discovery` / value | Adressen sjekket av en app · 14:02 (or `app_push.settings.never` «Ikke ennå») |
| 11 | `app_push.settings.pushed` / value | Push mottatt · 14:05 |
| 12 | `app_push.settings.seen_footer` | Bare tidspunkter, for alle telefonene samlet. Serveren sjekker aldri seg selv utenfra. |
| 13 | `app_push.delivery.group` (header) | Levering til telefonene |
| 14 | `app_push.delivery.usually` / value | Vanligvis · 2 s |
| 15 | `app_push.delivery.p95` / value | 95 % innen · 9 s |
| 16 | `app_push.delivery.pushes` / `.pushes_value` (`.pushes_capped`) | Antall push · 38 siste døgn ({count}, høyst de nyeste 5 000 per telefon) |
| - | `app_push.delivery.none` (instead of 14-16) | Ingen push levert mens telefonen var tilkoblet, siste døgn |
| 17 | `app_push.delivery.footer` | Fra serveren tar imot et push til det er levert til telefonen, for alle telefonene samlet. Bare push som kom mens telefonen var tilkoblet, er talt med. |

**Word count, typical state: 170** (3 groups, 3 footers, 7 rows; about 105 of the words are explanation).

## 2. The proposal

One form group, one status group, four small ⓘs. The page answers: what is it, where's the address, does it work.

**Stays:** the address field with its placeholder, Save, the refusal, the prefill note, the old-address line, the
no-listener warning (only shows when the server can't serve it at all; the env var name is what the admin acts on).

**Cut to one short sub-line under the field:**
- `origin_hint` (28 words; the placeholder already shows the example) → "Må kunne nås fra internett."
- `save_note` + `clear_note` → one line under Save, by state: not set "Når du lagrer, slås app-push på for alle
  telefonene."; set "Tøm feltet og lagre for å slå av for alle telefonene." ("per telefon" moves to ⓘ2.)

**The explanations go behind the PWA's existing ⓘ, one per thing a parent may wonder about, next to what it
explains.** The pattern is `<details class="info">` + `<div class="info-panel">` (CSS only, `static/style.css`
~1245-1320, no JS), its aria-label key `*_about`/`*_info_label` (nb "Om …") and its text key `*_info`. It comes in
three placements, and this page uses all three:
- **ⓘ1, on the field label** (`details class="info field-info"` right after the `<label>`, as `tracked_app_add.html`'s
  "Pakke"): *why the internet, tailnet-only can't, nothing after the name.*
- **ⓘ2, in the footer** (`<div class="group-footer">` sentence + `details.info`, as Device › Apps' "Downloads" footer /
  Advanced › Restrictions): *what app push is, no message text kept, the one-day hold, the per-phone switch.*
- **ⓘ3 and ⓘ4, the whole row opens** (`details class="info cell"` with `summary class="row"` + the icon, as
  `partials/info_row.html` / the app page's "Pakke" row): ⓘ3 on "Sist sett utenfra" (*what it means, the server never
  checks itself, what "Ikke ennå" tells you*); ⓘ4 on "Vanligvis levert på" (*how it's measured, only while
  connected*). Written inline rather than through `info_row.html`, since the delivery row has a sub-line the partial
  doesn't carry.

Visible footer line: "Varsler fra apper som Element X går gjennom Vibb One og denne serveren, uten Google." Matrix,
VAPID, "distributor", "telefonport" and "Element X først" leave the page altogether.

**"Sist sett utenfra" and "Levering til telefonene" merge into one group "Levering"** with two rows and no footer:
- "Sist sett utenfra" · the newer of the two times (discovery or push). Either one proves the address is reached;
  the split is diagnostics, not something a parent acts on.
- "Vanligvis levert på" · "2 s", with the sub-line "95 % innen 9 s · 38 push siste døgn" (below 20 pushes:
  "3 push siste døgn"; cut by the cap: the existing `app_push.delivery.cap_note` appended, as the device line does).
- None: one row-note "Ingen push levert siste døgn".
- `app_push.outside` keeps pointing at "Sist sett utenfra" (label unchanged), only the page name changes.

**Name:** nb "Push for apper" → "App-push" everywhere in the PWA (en stays "App push"). The launcher's own
`settings_up_title` ("Push for apper", PIN-gated diagnostics) can follow in a launcher commit; not needed for this.

**Optional (not counted below), for "the apps":** a third group "Telefoner" with one row per enrolled phone - name,
value "På"/"Av", sub-line the app names with registrations ("Element X") - each a link to
`/devices/{id}/apps#app-push`, where the switch and Stop… live. Costs a query in `render_settings`
(`up::switch` per device + `SELECT DISTINCT package FROM up_registrations WHERE device_id = ?`, names via
`KNOWN_UP_APPS`/the catalog). No text, only navigation; skip it if the page should stay at two groups.

### The proposed nb page, top to bottom

```
App-push                                              ‹ Innstillinger

[only without DEVICE_BIND_ADDR, orange]
Serveren har ingen egen lytter for telefonene (DEVICE_BIND_ADDR), så app-push virker ikke. Se DEPLOY.md, «App push».

┌ Telefonenes offentlige adresse ⓘ1
│ [ https://phones.example.com                       ]
│ Må kunne nås fra internett.
│   (prefilled: Fra Telefonoppsett - ikke lagret ennå.)
│   (refused:   Adressen må være https:// og bare et vertsnavn)
│   (old one:   https://old.example.com virker også til 12. des, mens appene flytter over.)
│ Tøm feltet og lagre for å slå av for alle telefonene.
│   (not set:   Når du lagrer, slås app-push på for alle telefonene.)
│ [ Lagre ]
└
Varsler fra apper som Element X går gjennom Vibb One og denne serveren, uten Google. ⓘ2
[Lagret]

LEVERING
┌ Sist sett utenfra                                 14:05  ⓘ3
│ Vanligvis levert på                               2 s    ⓘ4
│   95 % innen 9 s · 38 push siste døgn
└   (none: Ingen push levert siste døgn)
```

Behind the ⓘs (all closed by default):

| ⓘ | Where | Label key → nb | Text key → nb |
|---|-------|----------------|---------------|
| 1 | field label | `app_push.settings.origin_about` → Om adressen | `app_push.settings.origin_info` → Appens server sender varslene til denne adressen, så den må kunne nås fra internett. En server som bare er på tailnettet, kan ikke brukes. Skriv https:// og navnet, ingenting etter. |
| 2 | footer | `app_push.settings.info_label` → Om app-push | `app_push.settings.info` → Hver app på telefonen får sin egen adresse på denne serveren. Serveren lagrer ikke meldingsteksten og holder et varsel i høyst et døgn. Hver telefon kan slå av app-push under Apper › App-push. |
| 3 | "Sist sett utenfra" row | `common.more_info` (the row's own label names it) | `app_push.settings.seen_info` → Sist en app eller appens server nådde adressen fra internett. Serveren sjekker aldri seg selv. Står det «Ikke ennå» lenge etter at en app er koblet til, når de ikke adressen. |
| 4 | "Vanligvis levert på" row | `common.more_info` | `app_push.delivery.info` → Fra serveren tar imot et varsel til telefonen har fått det, for alle telefonene samlet. Bare varsler som kom mens telefonen var tilkoblet, er talt med. |

## 3. After

**Word count, same state: 58 visible** (was 170; -66 %), plus 120 behind four ⓘs (30/33/31/26). Groups 3 → 2, footers 3 → 1, rows 5 → 2.

### Keys

Changed (nb / en):

| Key | nb | en |
|-----|----|----|
| `app_push.settings.title` | App-push | App push (unchanged) |
| `app_push.group`, `app_push.switch` | App-push | App push (unchanged) |
| `app_push.error.read` | Kunne ikke lese app-push - sjekk serverloggen. | (unchanged) |
| `app_push.turn_off_dots` | Slå av app-push… | (unchanged) |
| `app_push.no_origin` | Sett telefonenes offentlige adresse i Innstillinger › App-push først | (unchanged) |
| `app_push.outside` | …se «Sist sett utenfra» under Innstillinger › App-push. (only the page name) | (unchanged) |
| `app_push.settings.origin_hint` | Må kunne nås fra internett. | Must be reachable from the internet. |
| `app_push.settings.prefilled` | Fra Telefonoppsett - ikke lagret ennå. | From Phone Setup - not saved yet. |
| `app_push.settings.previous` | {origin} virker også til {when}, mens appene flytter over. | {origin} also works until {when}, while the apps move over. |
| `app_push.settings.save_note` | Når du lagrer, slås app-push på for alle telefonene. | Saving turns app push on for every phone. |
| `app_push.settings.clear_note` | Tøm feltet og lagre for å slå av for alle telefonene. | Empty the field and save to turn it off for every phone. |
| `app_push.settings.no_listener` | Serveren har ingen egen lytter for telefonene (DEVICE_BIND_ADDR), så app-push virker ikke. Se DEPLOY.md, «App push». | This server has no phone listener (DEVICE_BIND_ADDR), so app push doesn't work. See DEPLOY.md, “App push”. |
| `app_push.settings.footer` | Varsler fra apper som Element X går gjennom Vibb One og denne serveren, uten Google. | Notifications from apps like Element X come through Vibb One and this server, without Google. |
| `app_push.delivery.group` | Levering | Delivery |
| `app_push.delivery.usually` | Vanligvis levert på | Usually delivered in |
| `app_push.delivery.none` | Ingen push levert siste døgn | No pushes delivered in the last 24 hours |

Added:

| Key | nb | en |
|-----|----|----|
| `app_push.settings.origin_about` | Om adressen | About the address |
| `app_push.settings.origin_info` | (ⓘ1 above) | The app's server sends its notifications to this address, so it must be reachable from the internet. A server only on the tailnet can't be used. Type https:// and the name, nothing after it. |
| `app_push.settings.info_label` | Om app-push | About app push |
| `app_push.settings.info` | (ⓘ2 above) | Each app on the phone gets its own address on this server. The server keeps no message text and holds a notification for at most a day. Each phone can turn app push off under Apps › App push. |
| `app_push.settings.seen_info` | (ⓘ3 above) | When an app or the app's server last reached the address from the internet. The server never checks itself. If it says “Not yet” long after an app has connected, they can't reach the address. |
| `app_push.delivery.info` | (ⓘ4 above) | From the server receiving a notification to the phone having it, for every phone together. Only notifications that came while the phone was connected are counted. |
| `app_push.delivery.sub_p95` | 95 % innen {p95} · {count} push siste døgn | 95% within {p95} · {count} pushes in the last 24 hours |
| `app_push.delivery.sub.one` / `.other` | {count} push siste døgn | {count} push / {count} pushes in the last 24 hours |

Deleted (both files): `app_push.settings.discovery`, `app_push.settings.pushed`, `app_push.settings.seen_footer`,
`app_push.delivery.p95`, `app_push.delivery.pushes`, `app_push.delivery.pushes_value`,
`app_push.delivery.pushes_capped`, `app_push.delivery.footer`. Kept as is: `app_push.settings.seen` (now a row label),
`.never`, `.refused`, `.origin`, `.origin_example`, `app_push.delivery.cap_note` (both pages),
`app_push.delivery.under_second/seconds/minutes/hours`, every device-page key not listed above.

### Template (`settings_push.html`)

- Under Save: `save_note` when `!set`, `clear_note` when `set` (it leaves the footer). The field hint stays
  error › prefilled › `origin_hint`.
- ⓘ1: `<details class="info field-info">` after the field's `<label>` (markup as `tracked_app_add.html` line 43).
- Footer: `<div class="group-footer">` with `app_push.settings.footer` + `<details class="info">` (ⓘ2; markup as
  `device_apps.html`'s Downloads footer).
- One `<section id="delivery">` replaces `#seen` and `#delivery`: header `app_push.delivery.group`; row
  `data-row="seen"` (`app_push.settings.seen`, `seen` or `never`); row `data-row="delivery-usually"` with a
  `row-sub` (`rows.sub`), or the `delivery-none` note. No footer. Nothing posts to these anchors, so no landing changes
  (the only form still returns to `#saved`/`#push`; pwa.md's no-jump rule holds).

### Handler (`render_settings`)

- `discovery` + `pushed` → `seen: Option<String>`, the later of `last_discovery_at` and `MAX(last_push_at)`
  (compare the parsed instants, then `zone.when`).
- `DeliveryRows { usually, p95, pushes }` → `{ usually, sub }`: `sub_p95` from 20 pushes, else `t_count(sub)`; when
  capped append `cap_note` (as `group` does for the device line).

### Tests to update

`tests/unified_push.rs::delivery_time_on_the_pages` ("Delivery to the phones" → "Delivery"; drop the
`delivery-p95` check; "3 in the last 24 hours" → "3 pushes in the last 24 hours"); any test reading
`data-row="discovery"`/`"pushed"` or the seen footer (grep `settings/push`); the i18n key-parity/unused-key test
covers the deletions. Design 108 §3.1/§3.10 and 108c §7.4 get a one-line "words changed" note; pwa.md's App push
entry if it lists the groups.

### Optional, Device › Apps (same spirit, not asked)

`app_push.footer` (Matrix, "privat adresse", 24 h) → nb "Ingen meldingstekst lagres, og kontonavnene blir på
telefonen." / en "No message text is kept, and the account names stay on the phone.", with the rest behind a footer
ⓘ of its own (`app_push.footer_info_label` «Om app-push», `app_push.footer_info`: the private address and the one-day
hold), same pattern as ⓘ2.
