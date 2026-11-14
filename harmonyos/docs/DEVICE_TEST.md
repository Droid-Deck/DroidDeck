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

## From a checkout to a phone

The whole path, in order. Every step is one command except the first two.

### 1. Get DevEco Studio

The build orchestrator (Hvigor), the package manager (ohpm) and Node are not
in the public OpenHarmony SDK — they ship inside DevEco Studio.

* Download the **macOS (ARM)** build for an Apple Silicon Mac from
  <https://developer.huawei.com/consumer/cn/deveco-studio/>. The page asks for
  a Huawei account; the file is named `devecostudio-mac-arm-<version>.dmg`.
  The Intel build will not run on an Apple Silicon Mac.
* Open the `.dmg` and drag **DevEco-Studio.app** into `/Applications`.
* Launch it once and accept the licence. It may want to download the
  HarmonyOS SDK (3–5 GB); the port builds against its own SDK copy if you
  point `--sdk` at one, so this is not required.

If DevEco Studio lives somewhere else, set `DEVECO_HOME` to its app bundle.

### 2. Put the phone in developer mode

Settings > About phone > tap the build number seven times, then Settings >
System and updates > Developer options > **USB debugging**. Plug it in and
trust the computer. `hdc list targets` must show it.

### 3. Build the HAP

```bash
cd harmonyos
tools/build_hap.sh
```

The script finds DevEco Studio, points the build at the SDK it installed, and
writes an **unsigned** HAP under `entry/build/default/outputs/default/`. It
checks the result with `tools/check_hap.sh` before it returns, so a HAP that
is missing a library is caught here rather than on the phone.

### 4. Sign it

A phone only installs a HAP that Huawei has signed, and the signature is
yours: the repository ships no keys on purpose.

Generate a signature once in DevEco Studio — File > Project Structure >
Signing Configs > *Automatically generate signature*, and sign in with your
Huawei account. The files land in `~/.ohos/config/`.

Then sign the HAP from the command line:

```bash
DDK_KEYSTORE_PASSWORD=... DDK_KEY_PASSWORD=... \
  tools/sign_hap.sh --in entry/build/default/outputs/default/entry-default-unsigned.hap
```

`tools/sign_hap.sh` drives DevEco Studio's own `hap-sign-tool`, so no IDE has
to be open. The passwords come from the environment, so they stay out of the
shell history and out of the repository. The signed HAP is written next to the
unsigned one, with `-signed` in its name.

### 5. Install it

```bash
hdc install entry/build/default/outputs/default/entry-default-unsigned-signed.hap
```

### 6. Check what it managed to do

```bash
tools/device_smoke.sh --hap entry/build/default/outputs/default/entry-default-unsigned-signed.hap
```

That installs it again, starts the app, waits for it to come up, reads the
port's own log out of the phone and reports row by row what worked. The table
below says what each row means.

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
