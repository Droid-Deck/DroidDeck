# DroidDeck changes to the vendored OpenNOW code

Upstream: OpenCloudGaming/OpenNOW a49b124c. Each change keeps upstream behaviour on
Windows, macOS and Linux.

- `opennow-core/src/update_apply/{process,security,mod,managed,recovery}.rs`: the
  Linux-only process, ACL and boot-id helpers also cover Android (`target_os =
  "android"`), so the core compiles for aarch64-linux-android. DroidDeck never runs
  the self-updater.
- `opennow-streamer/Cargo.toml`: the workspace lists only the vendored crates; the
  Linux/macOS platform crates, the Qt FFI crate, the dev host and the ffmpeg/cros-codecs
  patches are not vendored (DroidDeck builds Android only).
- `opennow-streamer-platform`:
  - `src/media_types.rs`: the shared media types moved out of `media.rs` unchanged;
    `media.rs` re-exports them.
  - `src/lib.rs` → `src/desktop_lib.rs` (upstream's crate root, unchanged apart from the
    `queue`/`video_queue` declarations, now in the new `lib.rs`); `src/lib.rs` includes it
    on desktop and uses the new `src/android/` module on Android.
  - `src/video_queue.rs`: imports `EncodedFrame` from `media_types`.
  - `src/android/` (new, DroidDeck): MediaCodec decoding onto an app-supplied Surface,
    Opus → AAudio playback, the same public runtime/session/sink types as the desktop
    backends; recording, replay and the microphone report "not available".
  - `Cargo.toml`: SDL2, openh264, the recording muxers and the Windows crate are
    desktop-only; Android adds `ndk`, `ndk-sys`, `jni`, `log`.
