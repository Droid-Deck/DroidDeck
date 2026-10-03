# Game launcher shortcuts

DroidDeck exposes installed Steam titles and AddedGames through a canonical launch link:

```text
droiddeck://game/<unsigned-decimal-game-id>
```

The ID is the value accepted by `steam://rungameid/`, including an AddedGames shortcut ID that occupies the unsigned 64-bit range. The app accepts only the exact URI form and only after a fresh library scan finds that game. It does not accept paths, Steam URLs, extra parameters, leading zeroes, or IDs outside the unsigned 64-bit range.

Each game page's **Shortcut** menu offers **Add to home screen** through Android's pinned-shortcut request and **Copy launch link**. When a launcher invokes `ACTION_CREATE_SHORTCUT`, DroidDeck opens the Games page and returns Android's standard shortcut result after the user chooses a game.

With the debug app installed, exercise link routing with an installed game ID from the current library:

```sh
adb shell am start -n com.droiddeck.launcher/.MainActivity \
  -a android.intent.action.VIEW -d 'droiddeck://game/620'
```

Open the standard shortcut picker with:

```sh
adb shell am start -n com.droiddeck.launcher/.MainActivity \
  -a android.intent.action.CREATE_SHORTCUT
```

A cold link starts the normal Steam session with `steam://rungameid/<id>` as its startup URL. A warm link is accepted only during a running Steam session. The service waits for READY, resumes a suspended session, then writes a decimal-only request for the existing Steam client to consume. Desktop and program sessions remain active and report that the game cannot be launched until the current session ends.

Build a full local debug APK, including the native/audio bundle, with:

```sh
DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh
```

The focused JVM suite is `./gradlew app:testDebugUnitTest`. See the task report and session logs when validating cold launch, warm launch, resume, queueing, and rejection paths on a device.

## Thor acceptance run

Validated on the AYN Thor (Android 13 / API 33, serial `adb-d234a848-fv2FDl (2)._adb-tls-connect._tcp`). The full debug APK at `app/build/outputs/apk/debug/app-debug.apk` installed with `adb install -r --no-incremental`, without uninstalling or clearing app data.

- Pinned **Geometry Wars: Retro Evolved** from its game page, confirmed `game:8400` in Android's pinned shortcut database, and tapped that actual home-screen icon. The cold session reached READY with first frame; `Download/DroidDeck/2026-10-02-27-steam/session.log` records `steam://rungameid/8400`, Steam game ID 8400, `GeometryWars.exe`, and Gamescope focus on app 8400.
- Copied the link from the game menu. Android's clipboard preview showed `droiddeck://game/8400`. An ACTION_VIEW of that URI reused session `2026-10-02-27-steam`, guest PID 14740, and gamescope PID 14762; its event log recorded `steam.game_launch_requested` without a new compositor or guest process.
- Sent another game link while the generic Steam session was in `STARTING_STEAM`. Session `2026-10-02-28-steam` reached READY before recording the queued launch, and its guest log says it launched game 8400 through the running client.
- Pressing Home suspended the warm Steam session. Tapping the same pinned shortcut resumed session `2026-10-02-27-steam` with guest PID 14740 and dispatched the game request after `session.resumed`.
- Leading-zero, overflow-with-query, and unknown-ID links left the agent IDLE with no guest. A MODE_RUN session using `/usr/bin/sleep 300` remained the same session and program after opening the game link; no Steam launch was requested.
- ACTION_CREATE_SHORTCUT opened the Games picker on a fresh scan; choosing the title finished the picker. A shell-started activity has no caller to inspect the returned payload, so the end-to-end result extras were not directly captured on-device. Robolectric covers the result API call and shortcut intent metadata.
- The existing Game files and Proton prefix controls still opened Geometry Wars' installed folder and `compatdata/8400/pfx` in the file manager.

The device logs remain under `/sdcard/Download/DroidDeck/2026-10-02-27-steam` and `.../2026-10-02-28-steam`. Screenshots from the run are in `/tmp/game-launcher-thor-*.png`, `/tmp/files.png`, and `/tmp/prefix.png`. The only shortcut added for this run was `game:8400`; it was removed after validation.
