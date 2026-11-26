# Bottom screen: region mirror

Part of the [bottom screen research](bottom-screen.md). Researched 2026-11-26 against `main` at `d788afc12`. Not implemented.

Show live crops of the game image (minimap, HUD, ability bar, dialogue box) scaled up on the bottom screen, and optionally remove them from the top screen. Regions are saved per game.

## Use cases

- **Minimaps** in open-world and action games (GTA, Monster Hunter, The Witcher, Elden Ring's compass): bigger on the bottom, gone from the top.
- **MMO and ARPG bars**: ability, health and buff bars moved off a crowded 6" screen. Megingiard users asked for 90° rotation for a Guild Wars 2 ability bar and for very thin crops for visual-novel text.
- **Racing**: rear-view mirror or track map on the bottom while the top shows only the road.
- **Strategy**: the minimap of an RTS or a city builder, where it is the main navigation tool.
- **Clean top screen**: hide every HUD element on top and keep them all on the bottom.

## Prior art

| Project | Capture | Notes |
| --- | --- | --- |
| [Megingiard](https://github.com/stormpanda/megingiard) (Thor) | A privileged shell helper creates a mirror display (hidden `SurfaceControl.createDisplay` on API 33; `AUTO_MIRROR` virtual display through `mirrorSurface` on API 34+), then SurfaceTexture → GLES → TextureView. Falls back to MediaProjection with a consent prompt on every start | Up to 10 cutouts. Crop box edited on the top screen, placement on the bottom. Edge handles, 1 px gamepad steps, snap guides, aspect lock (free, source-locked, destination-locked), rotation and flips, pinch zoom, circle shape, edge blending, temporal smoothing. Variance masks cut moving background out of HUD art. "Visual anchors" (a 256-point colour signature checked at 60 Hz) switch layout or freeze cutouts when a menu covers the HUD. FPS cap 10–120. Complaints: black mirror, consent prompts, helper pairing, DRM blanking ([docs](https://github.com/stormpanda/megingiard/tree/main/docs/features/mirror)) |
| [Heimdall](https://github.com/mastercook777/Heimdall-AYN-Thor-Assistant) (Thor) | MediaProjection into one virtual display, fanned out on the GPU: one EGL surface per "Portal", crop in the texture matrix | Two Portals. Per-Portal zoom, shape, fps, and an "Interactive" mode that sends one-finger touch back to the region. Curated [community profiles](https://github.com/mastercook777/heimdall-community-profiles) keyed by app, checked by SHA-256 |
| [WalkThor](https://github.com/nerf-herder-29/WalkThor) (Thor) | MediaProjection at half resolution into an `ImageReader`, drawn with Compose | Four regions per game stored as fractions of the screen and keyed by game title. Pick box keeps the bottom screen's aspect by default. About 30 fps. Its bottom window is non-focusable so the game keeps top-app priority |
| [OnTopReplica](https://github.com/LorenzCK/OnTopReplica) (Windows) | DWM thumbnails (`DwmRegisterThumbnail` with source and destination rectangles): a crop done by the compositor, with no copy | The usual way to put an MMO minimap on a second monitor. SteamDeckHQ's [AYANEO Flip DS review](https://steamdeckhq.com/news/ayaneo-flip-ds-review/) used it for a Monster Hunter: World minimap on the bottom screen |
| [wl-mirror](https://github.com/Ferdi265/wl-mirror) (Linux) | wlr-screencopy or dmabuf export with `--region` | Scaling modes fit, cover, exact, linear and nearest, plus freeze |
| gamescope PipeWire stream | An extra composite pass of the whole output, used by Steam for recording | Full output only, no regions. Still a precedent for a second output rendered inside the compositor |

None of the Android tools can hide the region on the top screen: they capture the final display output, so any mask they drew would be captured too. Each also adds at least one composition cycle of latency and needs MediaProjection or a privileged helper.

## Why DroidDeck can do better

The game is composited by our own Wayland compositor in the app process. We have the game image as a Vulkan image before it reaches the display, so we can:

- crop without screen capture, a consent prompt or a privileged helper;
- avoid the extra SurfaceFlinger round trip;
- mask the region on the top screen in the same pass;
- know the game's own resolution and our letterbox or stretch transform, so regions can be stored in game coordinates.

## Current pipeline

Paths are under [`app/src/main/cpp/waylandcomp/src/`](../../app/src/main/cpp/waylandcomp/src/).

- gamescope runs nested in our compositor with its Wayland backend (`tools/linuxfs/overlay/usr/local/bin/droiddeck-session`). Client buffers arrive as dma-bufs or AHardwareBuffers and are imported as `VkImage`s ([`vk_present.c`](../../app/src/main/cpp/waylandcomp/src/vk_present.c)).
- `render_scene()` in [`compositor.c`](../../app/src/main/cpp/waylandcomp/src/compositor.c) builds a draw list; `vkp_render()` composites it, runs the effects chain ([`effects_chain.c`](../../app/src/main/cpp/waylandcomp/src/effects_chain.c): FSR, NIS, SGSR, CAS, CRT and others) and blits into one swapchain on one `ANativeWindow`.
- There is exactly one output window. Swapping the game to the bottom screen ([`BottomGameScreen.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomGameScreen.kt), `SessionActivity.putGameOnBottom`) re-targets that window to the Presentation's `SurfaceView`; it does not feed two surfaces.
- A gated zero-copy path ([`sc_layer.c`](../../app/src/main/cpp/waylandcomp/src/sc_layer.c), [`ZERO_COPY_SPIKE.md`](../../app/src/main/cpp/waylandcomp/ZERO_COPY_SPIKE.md)) posts the game's AHardwareBuffer straight to an `ASurfaceControl` child layer, with no composited image.
- `update_map()` holds the scene-to-output transform for each scale mode (letterbox, fit, stretch, fill, integer); `vkp_output_to_scene` and `vkp_scene_to_output` convert between them. `vkp_blit_image()` already blits into AHB-backed images.
- Screenshots use `PixelCopy` on the active `SurfaceView` (`SessionActivity.copyGameFrame`).
- The bottom-screen Presentation ([`SecondScreenPresentation.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/SecondScreenPresentation.kt)) lives in the same process as the compositor, so a new JNI call can hand it a surface. The bottom KDE desktop runs a second compositor in another process and cannot share images without AHB IPC.

## Technical options

### A. Second output from the compositor (recommended first)

Add a second, optional output to `vk_present`: its own `ANativeWindow` and swapchain, fed by a `nativeSetMirrorSurface` JNI call from a `SurfaceView` in the bottom Presentation.

- After compositing the top frame, blit each region's source rectangle from the scene image into the mirror swapchain image with `vkCmdBlitImage` (or a small shader pass), and present it.
- The copy is small: a 1240×1080 target at 60 Hz is about 0.3 GB/s against roughly 60 GB/s of memory bandwidth on the Thor's SoC (an estimate, not measured). The real costs are an extra queue submit competing with a GPU-bound game, and pacing a second display.
- A shader pass makes nearest or integer scaling (pixel-art HUDs), masks, circles, rounded corners and colour keys possible. An existing upscaler (FSR EASU or SGSR) could scale a small minimap cleanly.
- The mirror runs at its own rate (default 30 fps, options 15 and 60) and skips frames when the bottom panel is behind.
- The top pass can fill, blur or dim the same rectangles to hide them.

### B. Zero-copy layers

Composite the top frame into compositor-owned AHardwareBuffers, then attach the same buffer to one `ASurfaceControl` child per region under the bottom `SurfaceView`, with `setCrop`, `setPosition`, `setScale` and `setBufferTransform`.

- The NDK documents that each layer holding a buffer counts as its own reference, and that all of them must be released before the buffer is reused ([`surface_control.h`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/native/include/android/surface_control.h)).
- On API 33 (the Thor) the NDK `setBuffer` has no per-buffer release callback; release comes as a per-surface fence in the completion callback. All region fences must be merged before reuse, and the pool needs one or two extra buffers because the 60 Hz bottom panel holds buffers longer than the 120 Hz top panel.
- Avoid the Java `Transaction.setBuffer` release callback here: callbacks are keyed by buffer and per-layer frame number, so two layers showing the same buffer can collide. This is inferred from AOSP source, not tested.
- `ASurfaceControl_fromJava` needs API 34; on API 33 parent through `ASurfaceControl_createFromWindow` on the bottom `SurfaceView`'s window.
- Scaling is done by the display hardware with a fixed vendor filter (no nearest neighbour), and the number of hardware planes is limited. If they run out, SurfaceFlinger composites on the GPU instead, which still works.
- This only makes sense once the top screen itself is presented through AHB layers. Option A first.

### C. PixelCopy prototype

Crop a `PixelCopy` of the top `SurfaceView` at 5–10 fps into a bitmap. Useful for testing the editor UX and region presets quickly, but too slow for a real minimap, and it probably returns nothing when the zero-copy path puts the game on a child layer.

## Coordinates and presets

- Store rectangles in game-surface coordinates (gamescope's output, `DD_WIDTH`×`DD_HEIGHT`), not display pixels, so they survive changes to scale mode, FSR and output size. The compositor converts them each frame.
- Fractions of the screen alone break when the aspect ratio changes, because engines anchor HUD widgets to corners and scale by height or DPI ([Unreal anchors](https://dev.epicgames.com/documentation/en-us/unreal-engine/umg-anchors-in-unreal-engine-ui)). Store an anchor (one of nine corners, edges or centre), an offset and a size in units of the game's height.
- Key presets by Steam app id, aspect bucket (16:9, 16:10, 4:3, other) and an optional UI-scale tag set by the user.
- Later: a community preset catalog keyed by app id, curated and hashed like Heimdall's, offered as "Suggested regions" for the running game.

## UX

- **Editing on both screens.** The top screen shows the frozen frame with a crop box: edge and corner handles, D-pad nudge by 1 px and shoulder buttons by 10. The bottom screen shows placement with snap guides, aspect lock, rotation, flip, filter (smooth or sharp) and shape.
- **Limits.** Up to four regions per game. Long-press freezes a region; tap opens its options.
- **Hide on top.** Per region: off, fill, blur or dim.
- **Auto-hide.** Later, Megingiard-style colour anchors that hide or freeze regions when a menu covers the HUD.
- **Place in the panel system.** A new "Mirror" bottom-screen mode next to the dashboard and control modes, remembered per game (see per-game panel memory in the overview). Regions can also be shown in a corner of the dashboard.
- **Swapped mode.** With the game on the bottom screen, the mirror output goes to the top screen.
- Reuse DroidDeck's own settings components; no generic dialogs.

## Open questions

- Pacing and fencing of a second swapchain on a different display without stalling the top present.
- Interaction with glasses output, which already re-targets the single window.
- Whether regions apply before or after effects (a CRT shader on a minimap is probably unwanted).
- Battery and thermal cost, measured on the Thor and Duo with a GPU-bound game.
- Touch forwarding into a region (Heimdall's "Interactive"), mapped back to game coordinates.

## Milestones

1. PixelCopy prototype of the editor and per-game storage.
2. Second output in `vk_present` with one region and smooth scaling.
3. Multiple regions, shader filters, hide on top.
4. Presets keyed by aspect, suggested regions, anchors.
5. Zero-copy layers once the top screen uses AHB presentation.
