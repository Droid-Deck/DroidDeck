# Experimental Quest 2/3/3S support

Quest runs DroidDeck as a 2D panel, with an experimental immersive SteamVR path
in the standard app. Quest 2 has a separate experimental Adreno 6xx driver path.

Quest 2 selects `turnip26.3.0-quest2`, a pinned Banners-Turnip A6xx/A7xx build
with the same GPUOBJ adapter. It does not select the Quest 3 A7xx binary.
The first Quest 2 launch applies manual driver selection and the SurfaceControl
workaround, even if `questDefaultsV1` exists from an earlier install. Later user
choices are retained. The separate `turnip26.3.0-a6xx` bundle provides a plain
Adreno 6xx offline fallback for other devices; their driver Auto behavior remains.
Neither family-wide hardware coverage nor Quest 2 operation has been verified.

The A6xx input URL and SHA-256 are pinned in `tools/quest/build-driver.py`.
Both A6xx bundles are prepared by the existing local/CI wrapper build step and
covered by runtime-input attestation. Quest 3/3S retains its original driver.
Early installation and compositor breadcrumbs are written to `driver-setup.txt`
in private files and, when available, the app's external files directory. They
do not require a Steam session. The existing Quest Java crash file now also
applies to Quest 2; native crashes still require Android logcat.

Install the standard DroidDeck APK on Quest 3/3S. Device detection on the
first launch selects manual drivers, the bundled
`turnip25.1.0-quest1` Android driver and the default Linux driver. It enables
`hdr.steam` to use the existing SurfaceControl presentation workaround from
[the Quest report](https://github.com/Droid-Deck/DroidDeck/issues/245).
Presentation also has a Quest-specific layer path for complete SDR scenes and
the black backdrop, independent of this HDR preference; see
[the presentation candidate and headset test](quest-presentation-test.md).
These defaults apply once; subsequent user changes are retained. Colour
accuracy and headset performance still need testing.

Selecting driver Auto also uses the bundled Quest display driver and runtime
default, without downloading a generic Adreno driver pair. Manual selections
remain available and are not replaced on startup. Android's Auto-by-GPU fallback
uses the Quest bundle too; the external `Download/droiddeck-driver` override
still takes precedence. Fully close and restart DroidDeck after changing the
display driver, since the compositor loads it once per app process.

Both the Android driver wrapper and the Linux session preload use
`tools/quest/kgsl_compat.c`. Successful legacy allocation/free calls are
unchanged. Only `EACCES`/`EPERM` failures retry through `GPUOBJ_ALLOC`,
`GPUOBJ_INFO` and `GPUOBJ_FREE`. The Android wrapper depends on the original
bundled Turnip binary. Unhandled controller ioctls forward to the next
preload hook rather than skipping it with a raw syscall.

`tools/build_local.sh` and CI build the wrapper from source using the pinned
Android NDK; the normal preload builds include the shared Linux adapter.
Runtime input verification checks the resulting driver archive and invalidates
stale prepared assets after compatibility sources change. To rebuild only the
Android driver archive:

```sh
python3 tools/quest/build-driver.py "$ANDROID_HOME/ndk/27.3.13750724"
```

Quest compatibility is included in the standard APK; no separate Quest
package is needed. Other devices retain their existing defaults. The remaining
performance-name APK variants continue to use separate app data and bwrap sockets.

Quest 3 testers have successfully launched games.

On a headset, check the Steam login panel, sign-in, game installation and
then an owned game's rendering/input. Capture the device/OS, session logs,
`files/quest-startup.txt` and any `files/quest-last-crash.txt` failure.
Host allocation and preload-chain tests do not establish headset operation.

## Experimental immersive SteamVR

In a running Steam session, open the session menu's SteamVR tab, install SteamVR
once, then choose Enter SteamVR. Cardboard has its own tab and can remain off. On supported Quest devices this opens the standard app's
OpenXR activity. The guest driver receives headset poses, Touch controller input
and display timing, and returns owned stereo images tagged with the latest eye poses.
SteamVR haptics return through the tracking bridge. No root or separate XR APK
is required. Other devices retain their existing SteamVR start behavior.

The left Touch menu button is SteamVR's system button and toggles its dashboard.
The right Meta button remains reserved by the headset OS.

The driver supplies SteamVR's version-5 chaperone configuration through
`Prop_DriverProvidedChaperoneJson_String`, following Valve's driver API. Standing
tracking keeps the OpenXR stage coordinates; seated tracking starts at the head's
position and yaw. No synthetic play area or collision walls are advertised.
Without a seated transform, SteamVR reported valid raw and standing poses but
invalid seated poses, causing Thumper to remain behind its tracking-loss fade.
On Quest, supplying both transforms made all three origins valid; seated height
started within a millimetre of zero while standing and raw height stayed unchanged.

Quest uses SteamVR's normal Linux compositor through its display component.
The Vulkan layer captures only the HMD display swapchain, retains its present
waits, and chains a GPU copy before presentation. Three bounded capture buffers
allow rendering and transfer to overlap. Where native GLES supports
`GL_EXT_external_buffer` and texture views, Android allocates three
`AHardwareBuffer` BLOBs and passes their DMA-BUF allocations to Linux through
`SCM_RIGHTS`. Vulkan imports them as buffers and transfers foreign queue-family
ownership before and after copying the display image. A native compute shader
reads each shared buffer into a private RGBA8 texture; an sRGB texture view
preserves hardware transfer decoding. Native GPU completion precedes the
acknowledgement, so Linux cannot overwrite an active reader. This removes the CPU
pixel copy and upload, but still includes GPU copies and explicit completion waits.

If capability negotiation or buffer import fails before frames are submitted,
the existing CPU transport remains available. It brackets the DMA-BUF read with
`DMA_BUF_IOCTL_SYNC`, copies to owned pixels, then acknowledges and uploads them.
Both channels authenticate the peer UID and bound buffer sizes. Missing
acknowledgements after submission quarantine the transfer and fail presentation
instead of falling back while a reader might still own a buffer.

The helper temporarily enables SteamVR's Linux Vulkan asynchronous queue and disables motion
smoothing, backing up those preferences and restoring them on stop or the next start
after an interrupted session. It excludes the pinned Steam Frame package's optional CVP backend,
whose library constructor exits on Quest's unsupported SoC. The original library
is retained with a `.droiddeck-disabled` suffix. SteamVR binaries are not patched.
Cardboard retains its existing direct-mode path; `questframes` remains an explicit
diagnostic override for the earlier driver presenter.

The panel compositor detaches before OpenXR starts displaying guest frames.
Session suspension accounts for the immersive activity while the panel is hidden.
Closing the immersive activity stops SteamVR. The panel remains available for
returning to the normal Steam session.
The immersive activity belongs to the Steam session that opened it. Ending that
session closes XR; a failed startup or stopped SteamVR also returns to the panel
with an error instead of leaving a black scene. Enter SteamVR can then start a
fresh connection.

SteamVR scene-application and dashboard events also guide Gamescope's game focus.
While the dashboard is hidden, the guest agent resolves the live scene process's
Steam app ID and promotes its focusable window. Opening the dashboard or ending
the scene restores the previous order if no other client has replaced it. This
keeps keyboard/gamepad input with the VR scene without game-specific IDs.

With Steam already running, `python3 tools/test_quest_xr.py --serial SERIAL`
launches the immersive activity and verifies advancing owned images, tracked
6DoF, focus and SteamVR status. Add `--require-controllers` while holding both
controllers. Results include telemetry and logcat in `build/questxr-validation`.
Host tests and APK installation alone do not establish headset operation.

On Quest 3, a 45-second hardware run maintained focused tracked 6DoF, both
controllers active, and advancing stereo images. A headset tester confirmed
controller models and trigger animation. That run delivered about 8.5 new stereo
images per second despite a 90 Hz XR session.
That earlier measurement used the driver-direct presenter. With SteamVR's normal
compositor, a native Vulkan scene passed its first submission and continued
submitting stereo frames. Thumper and The Thrill of the Fight produced clean
immersive scenes. SUPERHOT's Windows OpenXR build initially showed shared-image
corruption: DXVK supplied a restricted mutable-image format list while SteamVR
created its corresponding opaque shared images without one. Turnip can select
different memory layouts for those descriptions. On the Quest bridge, the layer
normalizes the known opaque-FD stereo-array creation chains to SteamVR's unrestricted list,
without changing application-owned single-layer OpenVR images, DMA-BUF/modifier
images or other targets. A headset capture then
showed clean SUPERHOT scene geometry; interactive gameplay remains unverified.
A subsequent 60-second shared-buffer dashboard run delivered about
43 new stereo images per second. Reusing Android pixel buffers raised the next
60-second run to about 68 received and 65 uploaded images per second, with valid
focused tracking and no rejected frames. These dashboard results do not establish refresh-rate gameplay: game input,
sustained game performance, and exact association with SteamVR's final compositor
pose still require validation.

The native GPU-buffer channel passed 256 full-size buffer reuse cycles, including
head/tail pixel checks and sRGB texture-view decoding. A subsequent SUPERHOT
scene run delivered about 43 new stereo images/s and 58 XR draws/s over one
minute, with focused tracking and no rejected frames. The earlier CPU-path
SUPERHOT run uploaded about 28 images/s; these are separate runs, not a controlled
benchmark. Capturing the source and eye images can delay frame consumption;
the GPU receiver acknowledges stale frames without reading them and keeps the
last private image until a fresh frame arrives. Missing completion still fails
closed. `frame_verify` explicitly selects the CPU path for byte comparison.

The frame channel supports linear and sRGB RGBA8. SteamVR's final display image
uses sRGB display values; Android decodes those during texture sampling and writes
to an sRGB eye swapchain, encoding the display values once. A Quest color-chart
capture verified that 64, 128, 192 and 224 grey patches retain those values;
the previous sRGB source upload darkened them to approximately 13, 55, 134 and
190. The transport remains 8-bit SDR; this correction does not add HDR or higher
precision gradients.

When available, `XR_EXT_performance_settings` requests sustained-high CPU and GPU
performance for the running OpenXR session. These are runtime-managed hints;
device power and thermal policies remain authoritative. Unsupported or rejected
requests do not prevent startup. This does not change render resolution.

The manifest declares the supported Quest models using Meta's standard
`com.oculus.supportedDevices` metadata. Without it, Quest 3 used compatibility
mode and its refresh-rate API returned an empty capability list. With the
declaration, the test runtime reported 72/80/90/120 Hz; a fresh normal launch
used 72 Hz in both OpenXR telemetry and SteamVR's HMD property. DroidDeck leaves
the refresh choice to the runtime. No forced refresh override is included.

The OpenVR driver maps OpenXR's asymmetric eye FOV to OpenVR's lower/upper Y
projection bounds. A live Quest 3 check confirmed that SteamVR and native
OpenXR now report the same vertical projection center; the previous mapping
mirrored that offset. This applies to every VR scene, without game-specific
rendering changes.

### Experimental flat games in SteamVR

A game Steam lists without any VR support, launched while SteamVR runs, opens as
a SteamVR panel automatically: the Proton and FEX launchers ask Steam whether the
app supports VR (`droiddeck-agent --steam-supports-vr`; optional-VR games such as
Thumper launch normally) and wrap it in
`droiddeck-steamvr-game` (`droiddeck-steamvr-flat`). Adding
`droiddeck-steamvr-game %command%` to a game's launch options does the same by hand.
This runs a nested, unmodified Gamescope OpenVR backend for that game. Gamescope
owns the GPU overlay, placement and input routing; no CPU screen capture or
PipeWire service is used. The existing DroidDeck Vulkan synchronization layer
remains enabled. On Quest, a connected XR receiver selects Gamescope's Vulkan
texture sharing instead of DMA-BUF modifiers. Explicit environment overrides
are preserved; other devices retain the upstream modifier default.

An upstream GPU cube overlay has been observed on Quest 3. Flat-game stability,
controller interaction and performance still require hardware validation. This
uses Valve's public Gamescope backend; the exact Steam Frame OS launch setup
has not been verified.
