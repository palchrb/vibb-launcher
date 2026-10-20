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

1. **`GET /music/spotify` is the add page**: back link "Music" (`/music#spotify`), title "Add from Spotify", then
   "Add a Spotify link" (`#add`), "On these phones" (`#add-phones`), the added result (`#added`), and one row at the
   end, **"Spotify setup"** with its value - "2 accounts" (the number of children's accounts logged in), "App saved, no
   account yet", or "Not set up yet" - to `/music/spotify/setup`.
2. **`GET /music/spotify/setup` is the setup page** (`music_spotify_setup.html`): back link "Add from Spotify"
   (`/music/spotify`), title "Spotify Setup", then Your Spotify App (`#app`), Children's Accounts (`#accounts`, the
   paste flow `#paste`) exactly as today. The POSTs `save_app`, `clear_app`, `login`, `paste`, `remove_account` and
   the OAuth callback's return come back to `/music/spotify/setup?…#app|#accounts` with their notices; `add` comes
   back to `/music/spotify?…#add|#add-phones|#added` as today.
3. **Not set up yet** (no app, or no child account): the add page shows the setup row on top as an attention row
   ("Set up Spotify first" / "Log in a child's account first", `attention attention-action`, the chevron) linking to
   the setup page, and the add form below it stays visible but disabled (`fieldset disabled`, the row's sub-line says
   why), so the parent sees what the page is for. `add`'s handler refuses a POST in that state as it does today
   (whatever it answers now stays).
4. **The Music tab's "From Spotify" row** keeps its link and its `spotify_state` value.
5. One view struct per page; the shared pieces (`client_value`, `accounts`, the flashes) move with their groups; the
   add page needs only the setup summary (counts) and the add form's state.

## 2. Tests (required)

`src/tests/music_spotify.rs` (or where the Spotify page tests are): the add page has the add form and the setup row
and no `client_id` field; the setup page has the app and accounts groups and every form (`check_forms` on both);
each POST's redirect lands on the right page's group; the not-set-up state renders the attention row and the disabled
form and the add POST is refused as before; the OAuth `done` return lands on the setup page's `#accounts`.

## 3. Device check

The iPhone: Music › From Spotify opens on the form; the setup row's count right; Save/Log in/Remove come back to their
groups without a jump; a fresh server (no app) shows the attention row.
