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

Imported and installed movies are kept in `files/boot-movie/library` (with their list, `files/boot-movie/movies.json`) and
copied into Steam's movies folder, so resetting the Linux runtime does not lose them; the copy is put back when it is
missing. The list is changed under one lock, so imports at the same time cannot overwrite each other. Every movie in Steam's custom movies
folder (`config/uioverrides/movies`) can be removed after a confirmation, whether DroidDeck put it there or it was added by
hand or by Steam's own picker. Steam's own three startup movies (`deck_startup`, `oled_startup`, `startup_machine` in
`steamui/movies`) and points shop movies (any other `.webm` below Steam's `config`, `userdata`, `appcache` and `steamui`
folders, plus whatever path `StartupMovie.LocalPath` names) are listed as Steam movies, adopted when picked in Steam, and
never deleted. Bought movies live in `config/communityitemscache/startupmovies/<item number>_<hash>.webm`; their titles
come from the `GetStartupMovies` list in `userdata/<id>/config/localconfig.vdf`. Other videos in `steamui/movies` are not startup movies and are not listed.
Only one processed copy (volume or length change) is kept in `files/boot-movie/variants`; a volume that cannot be applied
plays without sound rather than louder, and plays nothing if that fails too. Covers are cached in `files/boot-movie/thumbs`. Steam reads its startup movie from the `StartupMovie` section inside the `Customization` section of
`config/config.vdf` (`MovieID`, and `LocalPath` relative to `config`; a Points Shop movie has its item number as
`MovieID`). The `StartupMovie` section under `Software/Valve/Steam` is where older clients read it, and DroidDeck writes both.
Selecting a movie here sets `bootMovieApplyNext`, so the next session stages the movie as `bigpicture_startup.webm` and
points both sections at it, over a movie Steam had picked; otherwise a movie picked in Steam is left alone and taken over as
the choice here without being staged over Steam's own setting. Settings > Boot movie > Installed has a report button that
writes what Steam and DroidDeck know to `Download/DroidDeck/boot-movie-report.txt`.
