# Agent control

The debug APK exposes a ContentProvider at `content://com.droiddeck.launcher.agent` and an Activity for starting sessions. Android's shell-only `DUMP` permission protects both. They are absent from release APKs.

Build and install the debug APK:

```sh
./gradlew assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The provider can also be called directly for state, stop, and resume:

```sh
adb shell content call --uri content://com.droiddeck.launcher.agent --method state
adb shell content call --uri content://com.droiddeck.launcher.agent --method stop
```

Start requests go through `tools/droiddeckctl`, which launches the protected debug Activity from the ADB shell. Calling the provider's `start` method directly returns an error because a background provider cannot reliably open the session screen.

The host CLI resolves one authorized device using `ADB_SERIAL` or `ANDROID_SERIAL` when set. Otherwise it deduplicates transports that report the same device serial and asks for an explicit serial if multiple devices remain.

```sh
tools/droiddeckctl state --json
tools/droiddeckctl start steam
tools/droiddeckctl wait ready --timeout 90
tools/droiddeckctl start desktop
tools/droiddeckctl run /usr/bin/foo -- arg1 arg2
tools/droiddeckctl stop
tools/droiddeckctl resume
tools/droiddeckctl logs latest ./session-logs.zip
tools/droiddeckctl screenshot ./screen.png
```

Every command writes JSON to stdout and reports its resolved ADB serial to stderr. Exit codes are 0 for success, 2 for invalid or rejected commands, 3 for ADB/device errors, 4 for session failures, 5 for timeouts, and 6 for artifact or file errors. Set `ADB` to select an adb executable (or pass `--adb`); pass `--serial` before the command to select a device directly.

`start` accepts `steam` or `desktop`. Steam starts in Big Picture by default; `--ui desktop` selects the client's desktop UI, and `--url steam://...` passes a client URL. Use `--wait` to wait for `READY` as part of `start`. `run` accepts a program path and optional guest arguments.

The `state` response uses schema 1 and includes the build label, runtime version, session ID and phase, mode, requested program, suspend and first-frame state, output size, guest PID, failure details, and artifact paths. Phases are `IDLE`, `PREPARING`, `INSTALLING_RUNTIME`, `STARTING_COMPOSITOR`, `STARTING_GUEST`, `STARTING_STEAM`, `READY`, `SUSPENDED`, `STOPPING`, and `FAILED`. Each session also writes `events.jsonl` alongside its existing artifacts.

`tools/deploy_local.sh` uses the same device resolver. If multiple distinct devices are connected, select one with `ADB_SERIAL` or `ANDROID_SERIAL`.

`logs latest` exports the same sanitized ZIP as Share logs. Working logs stay in app-private storage; the command retrieves the ZIP through `run-as` on debug builds.
