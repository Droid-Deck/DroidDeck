//! Ported from Bannerlator's bl-steam-client crate (GPL-3.0-or-later), itself derived from WinNative's wn-steam-client.

#![allow(clippy::missing_safety_doc, clippy::result_large_err)]

#[cfg(feature = "amazon")]
pub mod amazon;
#[cfg(feature = "epic")]
pub mod epic;
pub mod fetch_core;
pub mod fetch_tuning;
#[cfg(feature = "gog")]
pub mod gog;
pub mod md5_small;
pub mod priority;
pub mod stores_jni;
