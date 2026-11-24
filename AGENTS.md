# Rules

- Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.

# Building and testing

- For an install-and-look iteration, use `tools/quick_deploy.sh` (build without lint, sign, install, about 30 s). Run `tools/build_local.sh` once to prepare the runtime and again only before a PR. It is not a per-change step.
- Helper scripts (`tools/linuxfs/overlay`, `tools/linuxfs/desktop`) are staged on every Gradle build, so editing them needs no `build_local.sh`. After preloads or other runtime components change, the fast loop warns and reuses the old binaries; run `tools/build_local.sh` when those matter. "Missing or changed runtime input" means the prepared files themselves changed: run `tools/build_local.sh` once. Do not revert scripts or copy `app/build` between checkouts; `tools/seed_worktree.sh` seeds a new worktree from the main checkout.
- Every build defaults to the NDK in `tools/ci/toolchains.env`, as CI does. CI runs `testReleaseUnitTest`; when a test fails only in CI, run the release variant locally.
- Run the tests that cover what you changed, including unchanged tests of code you edited: `./gradlew -PskipRust=true :app:testDebugUnitTest --tests '<class>'` and `python3 -m unittest discover -s tools/tests -p 'test_<area>*.py'`. The full Python suite takes about 9 minutes, and some preload tests fail on any host without zram/ntsync. Leave full runs to CI.
- For UI screenshots without a device, run `./gradlew -PskipRust=true :app:testDebugUnitTest --tests '*PreviewRenderTest'` and read `app/build/preview-renders/{handheld,portrait}/`. Add cases per `docs/compose-previews.md`. Check portrait, not just landscape.
- After changing `SettingsIndex`, regenerate the doc: `UPDATE_SETTINGS_MAP=1 ./gradlew -PskipRust=true :app:testDebugUnitTest --tests '*SettingsMapTest*'`.
- Compile errors in code you didn't touch, after switching branches, are usually stale incremental state. Rebuild once before you change anything.

# Worktrees and shared machines

- After `git worktree add`, run `git submodule update --init --recursive`. Without it, `buildVkBridgeServer` fails on missing `external/MaliBridge`. Do not commit the `tools/syncthing/syncthing-android` pointer the build dirties.
- Other agents may be building on the same machine. Never run `./gradlew --stop` or kill Gradle/Kotlin daemons you did not start. If you need isolation, use `GRADLE_USER_HOME=<own dir> GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches`.

# Driving the app and device

- Do it yourself with `tools/droiddeckctl` (see `docs/agent-control.md`) instead of asking the user: `cdp eval` for Steam's UI (`SteamClient.Apps.TerminateApp('<appid>', false)` quits a game, including on a Remote Play host), `input`, `quit`, `displays`, `screenshot --display <id>`.
- A stream has started only when the session state reads `Streaming`. `SteamClient.Apps.StreamGame` alone can leave Steam on its menu, so open the game page and press Stream.
- Test controller navigation with `tools/droiddeckctl input gamepad ...`, a real uinput pad. `adb shell input keyevent DPAD_*` does not arrive as a joystick event and hides d-pad/stick bugs.
- A release-signed build installs over a release-signed install with `adb install -r`. Don't back up, uninstall or clear data to update. Scope `pm` and permission resets to `com.droiddeck.launcher`, never the whole device.

# CI

- CI builds the PR merged onto `main`. Before pushing, merge or rebase onto `origin/main` and compile: most "works locally" Kotlin failures come from that merge.
- Before a PR that touches strings or resources, run `./gradlew :app:lintVitalRelease`. The fast loop skips lint, and lint catches `ExtraTranslation` and missing-locale errors.
- Commit messages and PR bodies must not carry `Co-Authored-By` or other bot trailers. `tools/release/check_authorship.py` fails the build on them.
- `gh run view --log-failed` truncates the unit-test log, so the failing test may not show. Read the whole job log with `gh api repos/Droid-Deck/DroidDeck/actions/jobs/<job-id>/logs`, or `gh run download <run> -n unit-test-results`. Don't call a failure a runner problem until you have read it.
- Only these are known flakes worth a `gh run rerun <run> --failed`: `ProtonMenuAutoTest` "Proton work did not finish", Robolectric `HTTP response code: 429` from Maven, `Gradle Test Executor N finished with non-zero exit value 2`, apt mirror and GitHub API rate-limit errors. Treat any other failure as real.
- A workflow step that names a script your branch lacks means the branch predates a CI change. Rebase onto `main`.

# Agents running on the device (DroidDeck's in-app agent)

These apply only where `dd-lab-build` exists. Agents on a dev host use `tools/quick_deploy.sh` instead.

- `dd-lab-build` builds only committed work. Check `git status` and `gh api user` for the identity before committing; if `git config user.name` is empty, use the `gh` login's name and noreply email for this repo, never a guess.
- Do not commit or start a build in the same step: a build started next to a failed commit fails too. Commit first, check it, then build.
- Uncommitted changes in the tree may not be yours. Read `git diff` before committing them and tell the user what they do.
- If `dd-lab-build` says "could not read DroidDeck's state", loopback adb is down and `$ADB_SERIAL` is stale. Run `adb devices`; the port changes whenever wireless debugging is toggled. Ask the user to turn it on rather than retrying.
- A build that "did not start" can be adb, not the app. Read `~/.cache/dd-lab-build.json` and `.log` for the real reason; they can be stale from an earlier build, so compare their times with `date`.
- Run `dd-lab-build` with `run_in_background` and read its output file. A foreground `sleep` is blocked; wait with Monitor and an until-loop.
- The build restarts DroidDeck and ends your process. A turn that ends this way is not a failure.
