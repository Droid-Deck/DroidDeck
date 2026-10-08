//! DroidDeck's native store download engines (`libdroiddeckstores.so`).
//!
//! The GOG, Epic Games Store and Amazon Games download loops, ported from Bannerlator's
//! `bl-steam-client` crate (GPL-3.0-or-later, itself derived from WinNative's `wn-steam-client`).
//! Only the store engines and the fetch core they run on came across: DroidDeck's Steam client is
//! the real one, running in the Linux runtime, so none of the Steam CM / depot code is here.
//!
//! Each store module owns its manifest → plan → `FetchItem` mapping, its `FetchSink` (inflate +
//! hash + write in the exact output layout of the Kotlin manager that drives it) and its JNI facade.
//! The JNI contract — classes, method names, signatures, threading — is written down in `JNI.md`
//! next to `Cargo.toml`; the Kotlin side mirrors it exactly.
//!
//! What a run looks like: Kotlin hands over a manifest it already parsed (or, for Amazon, the
//! plan it built from one), the resolved CDN base(s) and the install directory, calls `nativeStart`
//! and gets a handle back at once. Everything after that happens on native threads: the fetch core
//! keeps an adaptive window of HTTP requests in flight on one tokio runtime, hands bodies to a sync
//! process pool that inflates, hashes and writes them, and reports progress through the listener
//! object Kotlin passed in. `nativeCancel` flips a flag; `onComplete` fires exactly once.

#![allow(clippy::missing_safety_doc, clippy::result_large_err)]

pub mod amazon;
pub mod epic;
pub mod fetch_core;
pub mod fetch_tuning;
pub mod gog;
pub mod md5_small;
pub mod stores_jni;
