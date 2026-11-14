# Lab builds

A Lab build is DroidDeck app code and resources built on the device itself, by the on-device agent
or by hand, and loaded over the installed APK at the next start. Nothing is reinstalled or
re-signed, so a tester on a release build keeps their games, sign-ins and settings while trying a
change.

While a Lab build is active, add-on packs reuse the newest complete copy already installed on the
device, even when the checkout pins another version. This keeps helpers such as proot available
after restarting. If no complete copy exists, the pack uses the checkout's pin.

## How it works

- `tools/agent/dd-lab-build` builds the checkout with Gradle on the device (`processReleaseResources`
  and `mergeDexRelease`), packs the linked resources and dex files into `overlay.apk`, writes
  `overlay.json`, and stages both in the app's `files/lab/next`. It then recompiles the app without
  an app image and restarts DroidDeck.
- Run by the app's own agent (`DD_AGENT` set), it writes `files/agent/lab-request` instead, and the
  app runs the build in a process of its own, logging to `~/.cache/dd-lab-build.log`; the agent's
  copy follows that log and exits with the build. A turn's processes end with the turn, so a build
  started there would otherwise die when the agent stops or finishes early.
- At start, `LabOverlay` (called from `App.attachBaseContext`) promotes `next` to `active`, checks
  that the build was made for this install, puts its dex files ahead of the APK's, and adds its
  resources through `ResourcesLoader` to the app and every activity. Contexts made from them
  (presentations on the second screen) inherit the loader.
- The watermark shows `LAB | <branch> @ <commit>`. When the app comes back on a new build it posts
  a "Lab build installed" notification, so the restart does not look like a crash.
- A build that is replaced, turned off or refused is kept in `files/lab/builds` (the newest 12).
  Updates lists them under Lab builds: the one in use can be turned off, one built for this
  install can be used again, and any can be deleted.

The build targets the package owning `DD_AGENT_INBOX` when run from the Agent tab.
For a manual build of another package, set `DROIDDECK_PACKAGE`, for example
`DROIDDECK_PACKAGE=com.tencent.ig dd-lab-build`. This also selects the control provider,
Lab storage, and package to restart.

## What a Lab build cannot change

The manifest, native code (`cpp`, `jniLibs`, Rust), Gradle files, `App`, `AppLanguage`, and `LabOverlay` itself:
they are loaded before a Lab build can be, or are not part of it. `dd-lab-build` refuses those
changes; they need a new APK.

## Safety

- A build is only used on the install it was built for: `overlay.json` carries the APK's commit
  (`apkCommit` in `droiddeckctl state`) and the APK's path, which changes with every install and
  update. A build for another install is turned off.
- Two starts in a row that do not reach a resumed activity for 10 seconds turn the build off.
- It needs Android 11 or later (`ResourcesLoader`) and no app image (classes in one load before the
  Lab build). `dd-lab-build` runs `cmd package compile -m verify` before restarting. Android's
  background optimization can bring an app image back; the build is then paused, a notification
  says so, and Restart in Updates recompiles through the app's wireless debugging pairing.

## Setting up a device

From the DroidDeck terminal, in a checkout of this repository:

```sh
tools/agent/setup
```

It installs Claude Code, git, gh and loopback adb, plus the build toolchain: JDK 17, the Android
SDK platform, and Google's x86-64 `aapt2` run under `qemu-user`. The first Lab build compiles the
whole app and takes about 8 minutes on a Snapdragon 865; later ones compile only what changed.
That needs the Kotlin daemon: Kotlin's in-process compiles always redo the whole app. BuildConfig
is given the installed commit, so committing a change does not change it. Setup
does that first build ahead of time with `dd-lab-build --prepare`, which compiles the installed
commit without making a Lab build.
`~/.cache/dd-lab-build.json` reports the stage, the current task, progress in thousandths and the
seconds left, estimated from how long each task took the last time (`~/.cache/dd-lab-build-times`).
`~/.cache/dd-lab-build-base` holds the installed commit the last successful build compiled, so the
Agent tab can offer to prepare again after DroidDeck updates.
