# Profile widgets, round two (2026-11-02)

What the owner chose on 2026-11-02, for review before building. Everything here is built on both the
web and Android, checked at 1366x768, 411x914 and in the emulator (Dark, Light, one preset), designed
through the frontend-design skill, and announced in one What's New release translated into all twelve
locales.

## Where things stand

Widgets are a JSON list kept on `users.widgets` and, for server profiles, `server_members.widgets`
(migration 0031), checked by `internal/api/widgets.go` and read defensively by web `lib/widgets.ts`
and Android `ui/profile/Widgets.kt`. Kinds today: Favorite Game, Playing Now, Wishlist, Played (IGDB),
Favorite Artists, Albums, Songs (Deezer, with previews), and up to four custom cards. A profile holds
eight; the first is the one the profile card shows.

New kinds need **no migration**: they are more entries in the same JSON. Older apps skip types they do
not know (`readWidgets` drops them), so an old app shows the rest of a profile unharmed. `maxWidgetsBytes`
(32 KiB) rises to 64 KiB for twelve widgets with formatted cards.

## New widgets

### Books (`reading`, `books`)

- Reading Now holds up to 3 books, each with optional progress (a page number or a percentage, drawn
  as a thin bar under the cover). Favorite Books holds up to 12.
- Search through Open Library (`internal/books`, like `internal/games`): no key, every request through
  the egress proxy with a plain `Nexa` User-Agent, covers fetched once and kept under
  `DATA_DIR/books`, served at `/media/book/{id}?size=s|l`. `GET /api/v1/books/search` under `limSearch`.
- A book can also be added by name with the person's own picture, as games can.

### Shows & Films (`watching`, `screen`)

- Watching Now holds up to 3 shows or films; a show may say where they are ("Season 2, Episode 5").
  Favorite Shows & Films holds up to 12, each marked show or film.
- Search through TMDB (`internal/screen`, `TMDB_API_KEY`, a v4 Read Access Token, added to
  `config.go`, `chatd.env.example` and CLAUDE.md's Configuration list). Through the egress proxy;
  posters kept under `DATA_DIR/screen`, served at `/media/screen/{id}`. Without the key the search
  is off and entries are added by name with an uploaded poster.
- TMDB's terms ask for attribution: its logo and "This product uses the TMDB API but is not endorsed or
  certified by TMDB." in Settings, About and Licenses, and a small TMDB credit under the search results.
- To check before release: TMDB's free key is for non-commercial use. Nexa sells plans, though none of
  them touches this feature; read their current terms (as was done for Deezer) and decide whether a
  commercial agreement is needed.

### Sticker Showcase (`stickers`)

- Up to 2 of the person's own public sticker packs. Each shows its name and a row of its first stickers
  (animated ones follow `animateStickers`), with Add Pack for viewers who do not have it and View Pack
  opening `/stickers/pack/:packId`.
- Checked when saved and when shown: a pack that was deleted, made private or blocked by staff simply
  drops out. Nothing new is stored.

### Countdown (`countdown`)

- One date (and optional time) with a title up to 40 characters and an emoji. Shows "in 12 days",
  then "Today!" on the day, and hides itself a day after.
- The date is stored as a UTC instant chosen in the person's timezone; viewers count against their own
  clock through the server clock offset (web `lib/serverClock.ts`, Android `data/ServerClock`).

### Local Time (`localtime`)

- Off until added. Shows the person's time ("3:14 am") and the gap from the viewer ("8 hours ahead"),
  ticking each minute.
- Stores an IANA zone name chosen from a list (defaulting to the device's), never worked out from the
  address. The widget says plainly that it shows the timezone to everyone who can see the profile.
- Full profile only, unless pinned as the card's widget.

## Changes to existing widgets

### Game page (new, opens from any game)

- Pressing a game on any game widget opens its page, as does a "Playing" activity card (Rich Presence
  or Discord sync) whose game IGDB knows. Activity cards carry no IGDB id, so the server matches by
  exact name (case-folded) through a cached search; no match, no page.
- `GET /api/v1/games/{id}` returns one IGDB query's worth, cached a day per game: name, summary,
  storyline, first release date, genres, themes, game modes, platforms, developers and publishers,
  IGDB's total rating and count, age ratings, screenshots, artworks, trailers (YouTube ids), similar
  games and store links (Steam, GOG, Epic, itch.io, PlayStation, Xbox, Nintendo). Under `limSearch`.
- Pictures go through the existing kept-cover path with new sizes for screenshots and artwork (IGDB's
  `screenshot_big`, `1080p`). Trailers play only through Nexa's own player at `/yt/`, sandboxed as the
  chat's YouTube player is, so viewers never load YouTube directly. Screenshots open in the picture
  viewer (web `chat/Lightbox.tsx`, Android `openLightbox`).
- Store links show their real host and confirm before opening, as activity links do.
- Web: a modal (kind `game`) at desktop width and a full-height sheet on phones; Android a sheet
  (`ui/profile/GamePage.kt`). Similar games open their own page in place, with Back.

### Music: fill from a link

- In the music widgets' editor, Paste a Link takes a Spotify, Apple Music, Deezer or SoundCloud link to
  a playlist, album or artist. `POST /api/v1/music/from-link` reuses `unfurl/music.go`'s lookups (raised
  from the card's five tracks to the widget's limits) and matches each track to Deezer (`matchPreview`'s
  rules), so previews play. A playlist or album fills Songs (up to 10), an artist link adds to Artists.
- What could not be matched is listed in the editor so the person can add it by name or leave it.

### Custom cards

- Text uses the chat's full markdown (bold, italics, underline, strikethrough, headings, lists, quotes,
  code, spoilers, coloured text, timestamps, links), drawn by the chat renderer on both apps. Mentions
  and custom emoji are drawn as plain text, since profiles are seen signed out too. The text limit
  rises from 300 to 1000 characters.
- Up to 3 links per card, each with an optional label, showing the real host and confirming first.
- A card colour: one accent or a two-colour gradient, picked with `ColourInput` (Android
  `openColourPicker`), drawn as the card's background tint with text contrast kept readable.
- A stat may be a bar: a label and a percentage (0 to 100) drawn as a progress bar.

### More room

- A profile holds 12 widgets (was 8); still at most 4 custom cards.

## Not doing (asked and declined)

Gamer Tags, Photo Wall, Prompts; ratings, platforms and hours on games; Play All, album tracklists and a
profile anthem; two widgets on the profile card, a Widgets tab, collapsing shelves.

## Order of work

1. Game page (the owner's favourite), with activity cards opening it.
2. Twelve widgets and the custom card changes.
3. Countdown and Local Time (no outside service).
4. Sticker Showcase.
5. Books (Open Library).
6. Shows & Films (TMDB, once the key is in `chatd.env`).
7. Music from a link.
8. What's New, CLAUDE.md, licenses (`make licenses` if a dependency is added), privacy text in the
   Privacy Policy if timezone or new outside services need a line.
