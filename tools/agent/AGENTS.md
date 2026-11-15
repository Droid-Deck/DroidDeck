# DroidDeck on-device agent

You run in DroidDeck's Agent tab, on the user's handheld, in DroidDeck's Linux userland (Ubuntu
under proot, aarch64). The user is often on a small screen: keep replies short. These notes come
from what tripped agents up on real devices.

## Where you run

- Steam, the desktop and games run in DroidDeck's session, a separate guest from yours: a program
  you start here is not in it and has no screen. Reach it with `droiddeckctl` (guide:
  `/root/.local/share/droiddeck/ctl/agent-control.md`): `state`, `start steam`, `stop`, `launch`,
  `quit`, `focus`, `screenshot`, `logs`, `pacing`, `thermal`, and `guest` to run a command in it.
- `droiddeckctl stop`, `start steam` and `resume` end or restart only the session. You keep
  running, so restart it yourself when a change needs a new session.
- The session shares your filesystem. Steam is in `/root/.local/share/Steam` (its logs in
  `logs/`), and the session's live logs are in
  `/data/user/0/com.droiddeck.launcher/files/logs/<session id>/` (the id is in `state`).
  `logs latest` makes a redacted, partial copy: read the live folder yourself. X properties,
  gamescope and the session's processes are only reachable through `guest`.

## Commands

- `guest` runs one program, not a shell line: `droiddeckctl guest -- sh -c '...'` for pipes,
  `$(...)` or `DISPLAY=:0 xprop ...`. Its answer is JSON; the output is in `stdout.text`.
- `screenshot` takes the path as an argument (`screenshot /tmp/top.png --display 0`); on a
  dual-screen device see `droiddeckctl displays`. `prefs get` takes a file (`prefs get session`).
- `input` drives a game session that has drawn a frame. For the launcher's own screens use
  `adb -s $ADB_SERIAL shell input keyevent KEYCODE_BACK` and similar.
- `droiddeck-scenario` replays a scripted test through `droiddeckctl` (`--help` for the format).
- Never kill by a command-line pattern (`pkill -f`, `pgrep -f` piped to `kill`): your own command
  line holds the user's message, so the pattern matches you. End a game with `droiddeckctl quit`,
  the session with `droiddeckctl stop`, anything else by its pid after checking what it is
  (`ps -o pid,args -p <pid>`): a pid near a game can be the Steam client.
- `DEVICE_RESOLUTION_FAILED`: run `adb devices` and use the serial it lists.
- Do not chain `sleep` before a check. Start long work in the background; when it ends you are
  started again.

## What applies when

- Many session settings (black frame insertion, environment, Proton) are read when a session or
  game starts. After changing one, restart the game or the session before judging it.
- With "Return after game" on (the default), quitting a game DroidDeck launched ends the whole
  session; `start steam --wait --reuse` before the next launch. Launching a game that is already
  running makes Steam show "Game already running" and `launch` time out.
- Session scripts are APK assets, copied into the rootfs at every session start over any local
  edit. A Lab build (`dd-lab-build`, see `docs/lab-builds.md`) carries app code and resources only:
  session scripts, native code and assets need a new APK, such as the pull request's CI test build
  (DroidDeck > Updates > Test builds).

## Changing DroidDeck

The source is checked out at `/root/DroidDeck` once the Agent tab's build tools are set up.

- It often holds the user's uncommitted work and a stale local `main`. Branch from `origin/main`
  in a worktree (`git worktree add -b <branch> ../<dir> origin/main`).
- `dd-lab-build` builds the checkout as a Lab build and restarts DroidDeck on it. DroidDeck runs the
  build in a process of its own and shows its progress; `dd-lab-build` follows its log. Run it in
  the background and keep checking, so the user can talk to you meanwhile. The restart ends your
  process, and the conversation continues on the new build.
- Python tests run from the repository root: `python3 -m unittest tools.tests.test_x`. Some fail on
  the device on `origin/main` too; run them on a worktree of `origin/main` before blaming a change.
- Gradle unit tests rarely run on the device. Leave them to CI and say so rather than retrying.
- If `git push` asks for credentials, run `gh auth setup-git` once.
- Commits and pull requests you make are the user's own: no co-author line, AI credit or
  generated-by note.

## This file

DroidDeck writes this block into your global instructions (`~/.claude/CLAUDE.md`,
`~/.codex/AGENTS.md`) before every turn, and its skills into `~/.claude/skills` and
`~/.codex/skills`. It replaces only its own block and its own skills: the user's rules go outside
the block, and their skills stay. To change DroidDeck's, edit `tools/agent/AGENTS.md` and
`tools/agent/skills` in the source.
