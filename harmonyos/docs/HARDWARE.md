# Hardware support

What a phone has to be able to do for this port to run, what it does *not*
have to do, and how to check the phone that is actually in your hand.

## The short answer

| Requirement | Needed? | Why |
|---|---|---|
| aarch64 CPU | **yes** | The HAP ships `arm64-v8a` libraries, and the guest runtime is an aarch64 Linux, so the phone runs the guest natively. |
| OpenGL ES 3.0 | **yes** | The compositor keeps the guest's frames in GLES textures and presents them in an XComponent, so the phone's GPU has to give the app an ES 3 context. |
| Vulkan | no | The port backs the guest's frames with GLES textures instead of Vulkan images, and reports the Vulkan-only features as unavailable. |
| A GPU device node | no | An app on HarmonyOS gets no `/dev/kgsl` or `/dev/mali0`, so the guest renders on the CPU. This is a limit of the platform, not of the port. |
| 4 GiB of RAM | **yes**, 6 GiB is comfortable | The guest runs a full Linux userspace next to the phone's own. |
| 3 GiB of free storage | **yes** | The published runtime unpacks to 1.09 GiB, and the download itself is 291 MB. |

Every phone that runs HarmonyOS meets the first two requirements: they are
64-bit ARM with OpenGL ES 3. The interesting fact is the third row — see
[The GPU](#the-gpu).

## The CPU

The port is not an emulator host for the guest. The published runtime image
is an **aarch64** Linux: `gamescope`, `bash` and `libc` in it are all
`EM_AARCH64` binaries, and the supervisor launches the guest through the
guest's own `ld-linux-aarch64.so.1` when proot is not available
(`entry/src/main/cpp/runtime/runtime.cpp`). The guest's system calls are
therefore the phone's own.

What *is* emulated is the x86-64 part:

* The **Steam client** is x86-64. The overlay's `droiddeck-steam-x64`
  downloads an Ubuntu 24.04 x86_64 rootfs and the Steam client into the
  guest, and `droiddeck-fex-update` fetches FEX (the x86-64 to arm64
  emulator, `fex-2610-r5`) on first use.
* **Windows games through Proton** are x86-64 as well, and run under the
  same emulator.

FEX is a JIT, so this is a translation cost rather than an interpreter, but
it is a real cost: it burns CPU that the guest's software renderer also
wants. Four or more cores are worth having.

## The GPU

The phone's GPU does two different jobs in this port, and only one of them
is fast.

**The presentation path is fast.** The compositor copies the guest's frame
into a GLES texture and draws it into the XComponent's window. That is the
phone's GPU doing the work, at the phone's full rate, and it needs an
OpenGL ES 3.0 context — which the platform provides.

**The guest's own rendering is slow.** Upstream runs the guest's games
against the phone's Vulkan driver (Turnip on Adreno, PanVK on Mali), which
is what makes Steam games playable on Android. On HarmonyOS that path is
closed: the open driver builds need a device node (`/dev/kgsl`,
`/dev/mali0`) that an app sandbox never gets, and the phone's own Vulkan
driver is not visible to an app either.

The image ships nine Vulkan manifests — `freedreno` (Adreno), `panfrost`
(Mali), `nouveau`, `intel`, `virtio`, `powervr_mesa`, `asahi` and the
software one, `lvp` — and every hardware driver needs a node the guest
cannot have. So the port pins **`lvp_icd.json`**, Mesa's lavapipe, which
renders on the CPU (`entry/src/main/ets/service/RuntimeService.ets`,
`vulkanIcd()`).

The practical consequence: the launcher, the compositor, the input and the
audio are all native and fast, but a 3D game inside the guest is rendered
by the CPU through a software Vulkan rasteriser, underneath an x86-64
emulator. Expect it to be slow. Two-dimensional games and the desktop
itself are the realistic target on HarmonyOS.

So the phone's GPU model — Adreno or Maleoon — does not change the verdict.
Both are fine for the shell; neither can accelerate the guest's games.

## How the guest is started

The published runtime carries **no proot**: its 26,022 entries hold no
`host/proot` and no proot anywhere, because upstream ships the patched
binary in an addon pack rather than in the runtime image. So the guest is
started in **direct mode** — the supervisor execs the guest's own dynamic
loader with the rootfs on its library path.

That has one wrinkle worth knowing about: a loader can only run an ELF file,
and the guest's command (`/usr/local/bin/droiddeck-session`) is a shell
script. The port therefore reads the script's `#!` line and runs it with the
interpreter that line names, taken out of the rootfs. The published image
merges `/usr`, so the interpreter a script names as `/bin/bash` is really at
`/usr/bin/bash`.

Direct mode has no path rewriting, so it only works for a guest that does
not depend on absolute paths. Upstream's path rewriting needs proot, and
proot needs `ptrace`, which HarmonyOS may not grant to an app at all: the
supervisor probes for it (`PTRACE_TRACEME` in a throwaway child) and reports
the mode it actually got on the Settings page.

## Checking the phone in your hand

```bash
tools/check_device_capabilities.sh
```

The script reads the phone over `hdc` and reports the architecture, the
cores, the memory, the free storage, and whether the app could get an EGL
context. It needs the phone connected over USB with developer mode and USB
debugging enabled. `tools/test_check_device_capabilities.sh` checks the
script itself against a stand-in for `hdc`, so it can be run without a
phone.

Two more sources of truth on the phone itself:

* **The app's own report.** Open the app and look at *Settings > GPU*: the
  renderer string is read from a real ES 3 context, so a name there means
  the phone's GPU gave the app one, and the OpenGL ES version it reports is
  the version the driver offers.
* **The log.** The native module logs what it finds:

  ```bash
  hdc shell hilog -T DroidDeck
  ```

  A line like `gpu renderer=Mali-G78 vendor=ARM model=78` is the port
  telling you what it measured on the device, and
  `gles version=OpenGL ES 3.2 V@0.0.0` says which ES level it got.

## What is still unproven

No phone has run this yet. Everything above is read out of the code, the
published runtime image and the SDK; the parts that need a device — the
actual frame rate, the audio path end to end, the pointer and key
forwarding — are still waiting for one. `tools/check_device_capabilities.sh`
is the first thing to run once a phone is connected.
