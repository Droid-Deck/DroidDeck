# itch.io collections

On the Library's itch tab a Collections button in the header lists the collections of the signed-in
itch account. Picking one hides every game that is not in it; the games of the collection that are not
installed show in the existing "Not installed" section, and All brings the full library back. The button
exists only while the itch chip is selected.

## Data

`ItchCollections` (`stores/itch/ItchCollections.kt`) asks itch with the account's API key:

```
GET /profile/collections                          -> collections[{id, title, games_count}]
GET /collections/<id>/collection-games?page=N     -> collection_games[{game{...}}]
```

Pages are read until one comes back empty. Each game goes through `ItchLibrary.item`, the reader the
library and shelves share, so it looks the same everywhere. The selection and the loaded games live in
memory; the list is asked again whenever the itch tab opens. Counts are logged
(`itch.io: <n> collections`, `itch.io: collection <id> has <n> games`).

## Filtering

`collectionEntries` keeps the itch entries whose store key is in the collection, then adds the
collection games the library has no entry for as not installed ones. Their game page resolves through
`StoresState.detailItem`, which also looks at the collection games.
