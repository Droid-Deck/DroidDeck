//! DroidDeck's store download engines. The Steam client and the shared fetch core are derived from WinNative's wn-steam-client (GPL-3.0-or-later, https://github.com/WinNative-Emu/WinNative).

#![allow(clippy::missing_safety_doc, clippy::result_large_err)]

pub mod amazon;
pub mod epic;
pub mod fetch_core;
pub mod fetch_tuning;
pub mod gog;
pub mod md5_small;
pub mod priority;
pub mod steam;
pub mod stores_jni;
