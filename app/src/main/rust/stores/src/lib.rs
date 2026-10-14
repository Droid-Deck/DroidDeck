//! Ported from Bannerlator's bl-steam-client crate (GPL-3.0-or-later), itself derived from WinNative's wn-steam-client.

#![allow(clippy::missing_safety_doc, clippy::result_large_err)]

pub mod amazon;
pub mod epic;
pub mod fetch_core;
pub mod fetch_tuning;
pub mod gog;
pub mod md5_small;
pub mod priority;
pub mod stores_jni;
