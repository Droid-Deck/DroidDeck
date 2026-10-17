# Boot movies

Settings → Boot movie has four tabs.

- **Installed**: "Browse" lists every movie Steam can play: DroidDeck's two, plus every `.webm` in Steam's
  `config/uioverrides/movies` folder (imported, installed from SteamDeckRepo, or put there by Steam). The list is re-read
  every two seconds, so a movie picked or removed inside Steam shows up, and a movie picked inside Steam becomes the choice.
- **Import**: the same importer as before (copy, remux or on-device transcode to VP8/Opus). The result is written into
  Steam's movies folder, so Steam lists it as well.
- **SteamDeckRepo**: browses `https://steamdeckrepo.com/api/posts` (`sort`, `device`, `search`, `page`; 12 posts a page).
  Installing downloads the movie through the site's own download address and imports it like any other movie.
- **Sound**: sound on/off, volume, and how many seconds of the movie play. Anything other than full volume and the whole
  movie is built into `files/boot-movie/variants` (audio re-encoded as Opus, video copied) and staged instead.

Titles, sources and SteamDeckRepo ids live next to the movies in `.droiddeck-movies.json`; covers are cached in
`files/boot-movie/thumbs`. Selecting a movie here sets `bootMovieApplyNext`, so the next session overwrites a movie Steam had
picked; otherwise a movie picked in Steam is left alone.
