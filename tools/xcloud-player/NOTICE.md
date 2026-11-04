# Xbox Cloud Gaming player

`greenlight/lib` is the WebRTC client of **Greenlight** by **UnknownSKL (Jim Kroon)**
(https://github.com/unknownskl/greenlight, `packages/player/src/client/lib`, commit ef852c3, MIT —
see `greenlight/LICENSE`). DroidDeck changes: `player.ts` renders video with the `<video>`
component instead of WebGPU, `render/video.ts` sets a transparent poster, and `input/gamepad.ts` treats missing buttons/axes as unpressed (Android reports no guide button); `uuid` is replaced by `uuid.ts` (crypto.randomUUID).

`sdp-tweaks.ts` (H.264 profile preference, bitrate cap), the CAS / unsharp-mask sharpening in
`renderer.ts` and the "stop quality drops" metadata change follow **Better xCloud** by **redphx**
(https://github.com/redphx/better-xcloud, MIT). The colour, deband, FXAA, Toon, CRT and NTSC shaders are
ported from Bannerlator's screen effects (The412Banner). `streamstats.ts` is DroidDeck's.

`entry.ts` is DroidDeck's: it drives a cloud session through the app (`DDCloud` bridge; the
streaming and Microsoft account tokens never reach the page) and attaches the first controller.

Build: `bash tools/xcloud-player/build.sh` (esbuild) writes
`app/src/main/assets/xcloud/player.js`; the built file is committed.

The page, its sources and the Android side (`stores/xbox/XboxCloud*.kt`) are ported from
Bannerlator's Xbox Cloud Gaming player (https://github.com/The412Banner/Bannerlator).
