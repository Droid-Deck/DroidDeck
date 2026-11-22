# Epic Games Launcher parent stand-in

`EpicGamesLauncher.exe` starts the game named by its first argument as its own child, passes the rest of its command line to the game unchanged, waits for the game and returns its exit code.

Some Epic titles only accept a launch whose parent process is named `EpicGamesLauncher.exe`. Rockstar's launcher logs `Epic Games Launcher not detected as parent process.` and then treats the Epic copy as unowned. In DroidDeck the Epic client is a Linux program, so it can never be that parent. The check matches the process name, not its path: Heroic's stand-in works from `C:\windows\command` and from the game folder.

`StoreLaunch` writes the Epic `.droiddeck-launch.bat` to run the game through this executable for the titles in `EPIC_PARENT_TITLES`, or for any Epic game whose per-game environment sets `DD_EPIC_PARENT=1`. `DD_EPIC_PARENT=0` turns it off. Without the staged executable the game starts directly, as before.

`build.sh` builds it with the GNU Windows target for the `epic` add-on pack. The app fetches the pack when it starts signed in to Epic, or when a launch that needs it finds it missing, and stages it in the Linux runtime at `/usr/local/lib/droiddeck-epic`.

```sh
cargo test --locked --manifest-path tools/epic-launcher/Cargo.toml
```
