# RGB USB camera transport

The optional RGB diagnostic backend uses [libusb 1.0.30](https://github.com/libusb/libusb/releases/tag/v1.0.30)
(LGPL 2.1 or later) and [libuvc 0.0.8](https://github.com/libuvc/libuvc/releases/tag/v0.0.8)
(BSD 3-Clause). Full license texts are in `LICENSE_LIBUSB` and `LICENSE_LIBUVC` here.

`tools/build_rgb_uvc.py` obtains the official sources with these SHA-256 pins:

| Source | SHA-256 |
| --- | --- |
| [libusb-1.0.30.tar.bz2](https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2) | `fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf` |
| [libuvc v0.0.8 ZIP](https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.8.zip) | `67922938ad81eb82b8ce14576f15a68ac75e30af738728a1f3070b6bbc938bce` |

## Build and replace

Use Python 3.12 or newer, Android NDK 27.3.13750724, CMake 3.22.1, and Ninja.
From the repository root on Windows:

```powershell
python tools/build_rgb_uvc.py --ndk "$env:LOCALAPPDATA/Android/Sdk/ndk/27.3.13750724" --cmake "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
```

The default output is `build/rgb-uvc/dist`: `arm64-v8a/libusb.so`, `arm64-v8a/libuvc.a`,
public headers in `include`, and license texts in `licenses`. The script records archive,
patch, recipe, and binary hashes plus complete build commands in `provenance.json`.
Use `--cache` to reuse downloaded archives and `--output` for an independent build.
Each run refreshes the extracted vendor sources from the pinned archives before patching.

Set `droiddeck.uvcDir=C:/path/to/build/rgb-uvc/dist` in the untracked `local.properties`, or
pass `-PuvcDir=C:/path/to/build/rgb-uvc/dist` to Gradle. The optional backend and shared libusb
are packaged through CMake; license texts are staged into APK assets under `licenses/uvc`.
Without this input, the RGB SDK diagnostic remains available and direct UVC reports unavailable.

libusb is a separate shared library with SONAME `libusb.so`; the application must package
it beside its JNI backend and keep it dynamically linked. libuvc is static PIC with hidden
symbols. A backend links `libuvc.a`, `libusb.so`, and Android `log`, and includes `libusb.h`
before `libuvc/libuvc.h`. Both native components target ARM64/API 26; shared libraries use
16 KiB ELF LOAD alignment. No JPEG conversion dependency is included: camera payloads
remain MJPEG and are decoded by the caller.

To rebuild libusb with modifications, run the script once, edit its extracted sources under
the output's `src/libusb-1.0.30`, then use the recorded CMake configure/build commands directly
without rerunning the Python extraction step. Package the resulting `libusb.so` in a rebuilt
APK. Keep the corresponding sources, recipe, patches, and LGPL text with release source
distribution; do not rely solely on the upstream download links. The generated manifest
describes the pinned build; regenerate hashes when distributing modified binaries.

## Local changes and validation

libuvc's queue uses eight transfer buffers instead of the upstream Linux default of 100.
The per-transfer limit remains 32 isochronous packets. The patch in
`tools/uvc/libuvc-submit-failure.patch` preserves a USB submission error instead of returning
success with an incomplete or empty queue. Unsubmitted transfers are removed under the
callback mutex. The normal stop path then holds that mutex while checking and cancelling
remaining transfers, waits for completion callbacks to clear them, and joins the frame
callback thread before returning. This prevents cancellation of already-cleared slots.

`uvc_submit_failure_test` replaces submission, cancellation, alternate-setting, and companion
descriptor USB calls with in-process fakes. It runs 60 starts covering failure before any
transfer, failure after three queued transfers, and all eight submitted successfully. Each
case checks the return code, cancellation count, stopped state, and cleared transfer slots.
The Android executable needs `libusb.so` on its loader path; it does not access USB hardware.
Building it verifies linking but does not execute the runtime tests. A separate device run
is required to confirm them:

```sh
LD_LIBRARY_PATH=. ./uvc_submit_failure_test
```

The executable built under `build/rgb-uvc-reprotest` passed all 60 cases on the Fold7 on
2026-10-16. That run used simulated USB calls and does not establish camera-stream reliability.
