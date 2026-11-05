# Steam startup failures on older CPUs and non-Adreno GPUs

These are different failures, even when neither reaches the Steam interface.

## Steam exits with SIGILL (#1376)

The [Redmi Note 8 report](https://github.com/Droid-Deck/DroidDeck/issues/1376)
shows gamescope selecting Adreno 610 successfully, all 17 client parts being
downloaded, and the native Steam executable immediately exiting with status
132 (`Illegal instruction`). The failure occurs after installation, not during
the runtime download. The supplied logs do not identify the faulting program
counter or CPU feature flags, so they cannot establish the exact instruction.

The official ARM64 Steam client and web helper inspected during this
investigation contain LSE atomic instructions. This is a compatibility concern
for CPUs without LSE, not proof that LSE caused this particular crash. Device
reports now include only the kernel's CPU `Features` lists, without unrelated
`/proc/cpuinfo` fields such as serial numbers.

After SIGILL, the failure screen explains the signal and, for an ARM64 client,
points to **Steam settings → Steam client → x86-64 (experimental)**. This is an
explicit user choice with an additional download and emulation overhead. It
does not establish that the device can run every component or game.

The x86-64 client normally reuses a matching-build native ARM web helper.
When CPU feature flags explicitly lack `atomics`, it now retains the existing
x86 web helper instead. Otherwise the fallback could re-enter native code
containing the same instruction class. All reported core feature lists must
include that exact flag; absent or unreadable feature data retains the prior
behavior. Explicit x86-helper selection and the client-build matching rule
remain unchanged.

## gamescope cannot find a Vulkan device (#1368)

The [MT6993 report](https://github.com/Droid-Deck/DroidDeck/issues/1368) shows a
non-Adreno device starting with an Adreno Turnip display driver, followed by
gamescope failing to find a physical device through the Linux freedreno ICD.
Steam has not been started in that trace.

Automatic Android display-driver selection now leaves non-Adreno GPUs on the
system driver rather than choosing Turnip from the Android SDK version. An
Adreno GPU with an unknown model still uses the existing SDK fallback; Quest,
external overrides and manual selections retain their priority. The failure
screen distinguishes an unavailable Linux Vulkan device from a failed Steam
download.

This does not implement MT6993/Mali graphics support. The Linux session needs
a driver for that GPU and kernel, and the Android compositor also has Vulkan
requirements. The system Android driver alone does not provide the Linux
driver; the desktop's software rendering does not replace the outer
compositor. Removing the unsupported-device notice or importing Adreno
Turnip cannot supply these missing components.

## Verification boundary

Regression tests cover actual automatic driver selection, log classification
and the x86 client's web-helper selection. The supplied session logs can be
replayed through the classifier. No target phone was available, and the
specific ARM64 instruction fault has not been reproduced under a debugger.
Neither report should be treated as verified fixed on hardware based on these
tests alone.
