# 47 - "Add from Spotify" opens on the add form; the setup behind a row (proposal)

Asked 2026-10-20 ("trykker på add from spotify, så kommer jeg inn i en tab som øverst viser ... settings; client id og
pålogging av kontoer ... add spotify link, category og 'on these phones' ... er det som burde komme opp ... Blir mye
støy"). Server only (`handlers::music_spotify`, `music_spotify.html` split in two); no API change. Rides in server
round 5 (the B implementer) with designs 44/45 and the small items. Builds on design 26c §2 (the PWA's Spotify page).

## 0. Today

`GET /music/spotify` (`music_spotify.html`, reached from the Music tab's "From Spotify" row) is one page: Your
Spotify App (`#app`: client id, redirect URI, Clear), Children's Accounts (`#accounts`: Log in with Spotify, the paste
flow `#paste`, Remove), Add a Spotify link (`#add`: link, category), On these phones (`#add-phones`), the added
result (`#added`). Every POST comes back to its own group's fragment (the no-jump rule).

## 1. Decisions

The Music tab already has one settings place, `/music/settings` (`music_settings.html`: New episodes, **Storytel** -
the login, On phones, Pick Books -, The music app, Files no entry uses), and the Storytel shelf page already shows the
pattern for "not set up yet" (`music_storytel.html`: "No Storytel login is saved - Save the family's login first" and a
row to `/music/settings#storytel`). Spotify follows that pattern instead of getting a page of its own (the user,
2026-10-20: "burde spotify heller legges inn der?").

1. **`GET /music/spotify` is the add page**: back link "Music" (`/music#spotify`), title "Add from Spotify", then
   "Add a Spotify link" (`#add`), "On these phones" (`#add-phones`), the added result (`#added`), and one row at the
   end, **"Spotify setup"** with its value - "2 accounts" (the number of children's accounts logged in), "App saved, no
   account yet", or "Not set up yet" - to `/music/settings#spotify`.
2. **The setup moves into Music › Settings as its own group, "Spotify"** (`#spotify`, after Storytel): Your Spotify
   App (`#spotify-app`: client id, redirect URI, Clear) and Children's Accounts (`#spotify-accounts`: Log in with
   Spotify, the paste flow `#spotify-paste`, Remove) exactly as they are today, ids prefixed so they can't clash with
   the page's other groups. The POSTs `save_app`, `clear_app`, `login`, `paste`, `remove_account` and the OAuth
   callback's return (`done`) come back to `/music/settings?…#spotify-app|#spotify-accounts` with their notices; `add`
   comes back to `/music/spotify?…#add|#add-phones|#added` as today. The settings page's view gains the Spotify
   group's data (the client value, `client_from_env`, the accounts, the flashes), loaded by the same functions the
   Spotify page used.
3. **Not set up yet** (no app, or no child account): the add page shows, as the Storytel shelf does, a row on top
   ("No Spotify app is saved - set it up first" / "No child's account is logged in yet", `attention attention-action`,
   the chevron) linking to `/music/settings#spotify`, and the add form below it stays visible but disabled
   (`fieldset disabled`, the sub-line says why). `add`'s handler refuses a POST in that state as it does today.
4. **The Music tab's "From Spotify" row** keeps its link and its `spotify_state` value; the Settings row's value may
   mention Spotify ("Storytel off · Spotify 2 accounts") if it mentions Storytel today - the implementer keeps its
   current shape.
5. One view struct per page; the shared pieces move with their groups; the add page needs only the setup summary
   (counts) and the add form's state. `music_spotify.html` keeps the add groups; the setup groups become a partial
   (`partials/spotify_setup.html`) included by `music_settings.html`, so the markup moves rather than being retyped.

## 2. Tests (required)

`src/tests/music_spotify.rs` (or where the Spotify page tests are): the add page has the add form and the setup row
and no `client_id` field; Music › Settings has the Spotify group with the app and accounts forms and every form still
lands (`check_forms` on both pages); each POST's redirect lands on the right page's group; the not-set-up state renders
the attention row and the disabled form and the add POST is refused as before; the OAuth `done` return lands on
`/music/settings#spotify-accounts`.

## 3. Device check

The iPhone: Music › From Spotify opens on the form; the setup row's count right; in Music › Settings, Save/Log in/Remove
come back to the Spotify group without a jump; a fresh server (no app) shows the attention row on the add page.
