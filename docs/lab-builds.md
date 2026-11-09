# Lab builds

A Lab build is DroidDeck app code and resources built on the device itself, by the on-device agent
or by hand, and loaded over the installed APK at the next start. Nothing is reinstalled or
re-signed, so a tester on a release build keeps their games, sign-ins and settings while trying a
change.

## How it works

- `tools/agent/dd-lab-build` builds the checkout with Gradle on the device (`processReleaseResources`
  and `mergeDexRelease`), packs the linked resources and dex files into `overlay.apk`, writes
  `overlay.json`, and stages both in the app's `files/lab/next`. It then recompiles the app without
  an app image and restarts DroidDeck.
- At start, `LabOverlay` (called from `App.attachBaseContext`) promotes `next` to `active`, checks
  that the build was made for this install, puts its dex files ahead of the APK's, and adds its
  resources through `ResourcesLoader` to the app and every activity. Contexts made from them
  (presentations on the second screen) inherit the loader.
- The watermark shows `LAB | <branch> @ <commit>`, and Updates has a Lab build card that goes back
  to the installed version.

## What a Lab build cannot change

The manifest, native code (`cpp`, `jniLibs`, Rust), Gradle files, `App`, and `LabOverlay` itself:
they are loaded before a Lab build can be, or are not part of it. `dd-lab-build` refuses those
changes; they need a new APK.

## Safety

- A build is only used on the install it was built for: `overlay.json` carries the APK's commit
  (`apkCommit` in `droiddeckctl state`) and the APK's path, which changes with every install and
  update. A build for another install is turned off.
- Two starts in a row that do not reach a resumed activity for 10 seconds turn the build off.
- It needs Android 11 or later (`ResourcesLoader`) and no app image (classes in one load before the
  Lab build). `dd-lab-build` runs `cmd package compile -m verify` before restarting.

## Setting up a device

From the DroidDeck terminal, in a checkout of this repository:

```sh
tools/agent/setup
```

It installs Claude Code, git, gh and loopback adb, plus the build toolchain: JDK 17, the Android
SDK platform, and Google's x86-64 `aapt2` run under `qemu-user`. The first Lab build compiles the
whole app and takes about 20 minutes on a Snapdragon 865; later ones are incremental.
`~/.cache/dd-lab-build.json` reports the stage, task count and current task while it runs.
