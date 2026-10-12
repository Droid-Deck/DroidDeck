# GOG Galaxy achievements

Comet v0.3.2, pinned to `3d7488ee72f83b7e8462a70fd4c4638d6da71360`, and its
protocol submodule are built for glibc ARM64. Apache-2.0; upstream:
https://github.com/imLinguin/comet.

Build with Rust 1.90, the `aarch64-unknown-linux-gnu` target and
`aarch64-linux-gnu-gcc`: `bash tools/comet/build.sh`.
The APK's runtime-input manifest records the prepared binary and Windows tools.

The patch accepts credentials on stdin, announces readiness and accepted uploads
without secrets, emits named local unlocks for the session notification, uses a periodic sync tick even during SDK traffic, and retains the retry flag
when an achievement upload fails.
The Android supervisor discards other upstream output instead of exporting
HTTP errors or protocol payloads containing game credentials.

The dummy service tools are the hash-checked upstream v0.3.2 release. They are
registered through the game's selected Proton in its own prefix. GOG overlays
are not installed. Pending databases survive session resets and sign-out.

Local unlocks show a five-second DroidDeck notification without taking input focus.
The notification queue belongs to the session, ignores duplicate SDK unlocks, and clears
on account replacement or session stop. Upload acknowledgements refresh the achievement
reader without replaying notifications.
