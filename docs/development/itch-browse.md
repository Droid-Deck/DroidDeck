# itch.io browsing

The itch Store tab opens on the front page shelves. A row of chips above them (`ui/ItchBrowseBar.kt`)
sets an order (Popular, New, Top rated, Top sellers) and a price (Any, Free, On sale). Leave both at
their defaults and the shelves stay; pick anything else and the tab shows that query's games as a grid
that loads the next page as it scrolls into view.

## Data

`ItchBrowse` (`stores/itch/ItchBrowse.kt`) turns a `BrowseQuery` into itch's listing feed:

```
https://itch.io/games[/<sort>][/<price>][/platform-<name>...][/tag-<slug>...].xml[?page=N]
```

`BrowseQuery` already carries platforms and tags, so the URL builder is complete; the controls for them
come in a later change. The path pieces are itch's own, copied from its links: sorts `new-and-popular`, `top-sellers`, `top-rated`,
`newest` (Popular has none); prices `free`, `on-sale`, `store` (Paid), `5-dollars-or-less`,
`15-dollars-or-less`; platforms `platform-windows`, `-osx`, `-linux`, `-android`, `-ios`, `-web`,
`-mobile-web`; a tag is `tag-` and its lowercase name with hyphens. `ItchBrowse.path()` builds the page
address, `url()` adds `.xml` and `?page=N`. Every request is logged
(`itch.io browse: <url> answered <code> with <n> games`).

Requests go out one at a time, at least 1.2 s apart. A 429 waits 8 s and tries once more; if that fails
the games already on screen stay. Each page is kept under the app cache for three hours, so going back to
a query does not ask itch again. Results are parsed by `ItchShelves.parse`, the same reader the shelves
use, so a game looks the same wherever it appears.

`ItchBrowseState` holds the query, the games loaded so far and whether more can be asked for. A newer
query bumps a generation counter, so a slow answer for an older one is dropped. A game already in the
library replaces its browse copy on the grid, so Install and the owned markers are right; the detail page
looks browse results up through `StoresState.detailItem`.

## When a page fails

A fetch that throws or answers with a non-2xx status marks the list as failed but leaves it open. The end of the list then shows a Retry button that asks for the same page again. Scrolling does not retry on its own, so a failing page cannot loop. Only a successful page with no new games ends the list.

## Platform and tag

The platform and tag rows take several choices, and the address lists them as itch does (`platform-windows/platform-linux/tag-pixel-art/tag-2d`, platforms in the order of the chip row). A typed tag becomes its slug (`Pixel Art` is `pixel-art`) and the address gets `tag-<slug>`.
