# Launching DroidDeck games from a frontend

Frontends such as ES-DE, Daijisho, Cocoon, Beacon and iiSU can start any game in the DroidDeck library. DroidDeck can be the default player for a whole Windows or PC platform folder: it reads every file format listed below and starts the matching library game.

## Launch target

Send an explicit intent to `com.droiddeck.launcher/com.droiddeck.launcher.FrontEndActivity`. This activity is never disabled. `.MainActivity` is a launcher-icon alias and is disabled when the user picks the light icon, so do not target it.

The release variants use other package names but the same activity class and action:

| Build | Package |
| --- | --- |
| Standard | `com.droiddeck.launcher` |
| PUBG | `com.tencent.ig` |
| AnTuTu | `com.antutu.benchmark.full` |
| Ludashi | `com.ludashi.benchmark` |

An intent that names only the action `com.droiddeck.launcher.LAUNCH_GAME`, without a package, reaches whichever build is installed.

## Intents

**Action with extras**, the same shape other PC game apps use:

```sh
am start -n com.droiddeck.launcher/.FrontEndActivity \
  -a com.droiddeck.launcher.LAUNCH_GAME --es app_id 620 --es game_source STEAM
```

- `app_id`: an int, long or decimal string. DroidDeck ids for added and store games are unsigned 64-bit, so send them with `--es` or `--el`, not `--ei`.
- `game_source` (optional, default `STEAM`): `STEAM`, `EPIC`, `GOG`, `AMAZON`, `XBOX`, `UBISOFT`, `EA`, `ROCKSTAR`, `BATTLENET`, `ITCH`, `GAMEJOLT` or `CUSTOM_GAME`.

**View a link or a file**:

```sh
am start -n com.droiddeck.launcher/.FrontEndActivity -a android.intent.action.VIEW -d 'droiddeck://game/620'
am start -n com.droiddeck.launcher/.FrontEndActivity -a android.intent.action.VIEW -d '<file or content URI>'
```

The data can be `droiddeck://game/<id>`, `steam://rungameid/<id>`, a bare id, or a `file://`, `content://` or absolute path to one of the files below.

## Game files

**Frontend files** (the sync button at the top of the Games page) keeps a chosen folder filled with one file per installed game, named after the game, holding only its DroidDeck id. Steam and added games use `.steam`; store games use the store's own extension.

| Extension | Games |
| --- | --- |
| `.steam` | Steam games and games added from a folder |
| `.epic` `.gog` `.amazon` | Epic, GOG and Amazon games |
| `.xbox` `.ubisoft` `.ea` `.rockstar` `.battlenet` `.itch` `.gamejolt` | Xbox, Ubisoft, EA, Rockstar, Battle.net, itch.io and Game Jolt games |

DroidDeck also reads files written by other apps:

- A bare id in any of the extensions above, plus `.pcgame` and `.droiddeck`. For `.epic`, `.gog`, `.amazon` and `.pcgame`, an id DroidDeck does not know falls back to the store's product id (GOG) and then to the game's title from the file name.
- Daijisho player templates (`.steamappid`) with a `[steamappid] <id>` line.
- Windows Internet Shortcuts (`.url`) whose `URL=` is `steam://rungameid/<id>`.
- `.exe` and `.bat` files. DroidDeck starts the library game whose folder holds the program. A program that belongs to no library game is not run; add its folder in DroidDeck first.

## Frontend examples

**ES-DE** (`ES-DE/custom_systems/es_find_rules.xml`):

```xml
<emulator name="DROIDDECK">
    <rule type="androidpackage">
        <entry>com.droiddeck.launcher/com.droiddeck.launcher.FrontEndActivity</entry>
    </rule>
</emulator>
```

and in `es_systems.xml` for the `steam` or `windows` system:

```xml
<command label="DroidDeck">%EMULATOR_DROIDDECK% %ACTION%=android.intent.action.VIEW %DATA%=%ROMSAF%</command>
```

`%ROMPROVIDER%` works as well. DroidDeck reads the file through the URI, or through the shared-storage path behind it when the URI grants no access.

**Daijisho** player:

```text
-n com.droiddeck.launcher/.FrontEndActivity -a android.intent.action.VIEW -d {file.uri}
```

**Frontends that inject the file's contents** can send `-a com.droiddeck.launcher.LAUNCH_GAME --es app_id <contents>` or `-a android.intent.action.VIEW -d <contents>`.

## What happens on launch

DroidDeck matches the request against a fresh library scan and starts the game the same way its own Play button does, through Steam or without Steam as the game is set up. A cold launch boots the session straight into the game. A warm launch hands the game to the running Steam session. When the game exits, DroidDeck returns to the frontend that started it.
