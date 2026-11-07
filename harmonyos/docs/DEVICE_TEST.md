# Device test

What the port has to do on a real phone, how to check that it does, and what
each result means. Everything here is written against the port's own log, so
there is one source of truth: what the app says it managed to do.

## The short version

```bash
tools/check_device_capabilities.sh          # can this phone run the port?
tools/device_smoke.sh --hap entry-default-signed.hap   # does it?
```

The first asks the phone what it is. The second installs the app, starts it
and reads back what the port actually did. `harmonyos/docs/HARDWARE.md` has
the requirements the first script checks against.

## Before you start

1. **The phone**: Settings > About phone > tap the build number seven times to
   turn on developer mode, then Settings > System and updates > Developer
   options > USB debugging. Plug it in and trust the computer.
2. **The HAP**: build it with `tools/build_hap.sh`, check it with
   `tools/check_hap.sh`, and sign it with `tools/sign_hap.sh`. The repository
   ships no keys on purpose — signing needs your own Huawei account.
   `tools/sign_hap.sh` drives DevEco Studio's own `hap-sign-tool`, so the
   whole path is one command and no IDE has to be open.
3. **The phone needs**: 3 GiB of free storage (the runtime image unpacks to
   1.09 GiB) and 4 GiB of RAM. The first script says whether it has both.

## What the smoke test reports

| Row | What it proves | The log line |
|---|---|---|
| the GPU gave the app a context | the phone's GPU gives the app an ES 3 context, so the compositor can present | `gpu renderer=… vendor=… model=…` |
| the OpenGL ES version | which ES level the driver offers | `gles version=OpenGL ES 3.2 …` |
| the compositor surface | the XComponent is alive and has a size | `XComponent surface created: 1080x2400` |
| the input path | the guest's input socket is bound and ready | `the input ring is ready: …` |
| the virtual gamepad | the app could create a gamepad | `uinput gamepad created` |
| the audio server | PulseAudio is running inside the app | `the PulseAudio server started, pid=…` |
| the guest's audio | the guest's audio reached the phone's sink | `guest audio socket connected: …` |
| the guest runtime on the phone | the published image unpacked and the overlay applied | `applied the overlay: 91 files` |

`MISSING` means the phone could not do that, which is a platform limit rather
than a bug in the port. Two of them are expected on HarmonyOS:

* **the virtual gamepad** — an app gets no `/dev/uinput`, so there is no
  virtual gamepad. The guest's own input path (the row above it) is the one
  that matters, and it does work.
* **the guest runtime on the phone** — the 291 MB image has not been unpacked
  yet. It happens from the app, and it takes a while on a phone.

One warning is expected and is not a failure:

```
dma-buf import is unavailable without a Vulkan driver; the guest has to use shared memory
```

An app on HarmonyOS cannot see the phone's Vulkan driver, so the guest's frames
travel over shared memory instead. That is the CPU-rendering limit described
in `HARDWARE.md`.

## Reading the log by hand

```bash
hdc shell hilog -T DroidDeck
```

The port's own messages are tagged `DroidDeck`. The ones worth looking for
are the rows in the table above. Anything tagged `E` is a failure the port
reported; `I` is progress and `W` is a warning.

## Starting a guest session

The smoke test only proves the launcher comes up. To start the guest:

1. Open the app.
2. The runtime is unpacked on first use. Watch it happen in the log:
   `applied the overlay: 91 files`.
3. Start a session. The port runs the guest in the session's `steam` mode,
   because the guest cannot install packages on HarmonyOS (`droiddeck-root`
   talks to the Android root service, which does not exist here).
4. The session script reports what it is doing in the same log.

The guest renders on the CPU through Mesa's lavapipe, so a game inside the
guest is slow however good the phone's GPU is. The launcher, the compositor,
the input and the audio are all native.

## What a pass looks like

* every row above is `PASS` or an expected `MISSING`;
* the summary says *The launcher is running on this phone*;
* the app's own Settings page shows a renderer and an OpenGL ES version,
  because the app reads them from a real ES context.

Anything else is a bug in the port, and the log line that says so is the first
thing to look at.

## Running the checks without a phone

Both scripts have a self-test that stands in for `hdc`, so they can be checked
on a machine with no phone attached:

```bash
tools/test_check_device_capabilities.sh
tools/test_device_smoke.sh
```

And every host check can be run at once with:

```bash
tools/check_all.sh
```
