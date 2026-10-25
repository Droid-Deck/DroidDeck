// DroidDeck: the desktop backends (Windows, macOS, Linux) are upstream OpenNOW's code in
// desktop_lib.rs. Android builds use the `android` module instead: the same public types,
// with MediaCodec video on a surface the app supplies and AAudio sound.
mod media_types;
mod queue;
mod video_queue;

#[cfg(not(target_os = "android"))]
include!("desktop_lib.rs");

#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
pub use android::*;
