//! The one export that is not a store engine: `com.droiddeck.launcher.stores.StoresNative`.
//!
//! `nativeVersion()` exists so the app can prove, at Setup time, that `libdroiddeckstores.so`
//! loaded and its JNI exports bind — before a user is mid-download and an
//! `UnsatisfiedLinkError` would surface as a failed install. It returns the crate version from
//! `Cargo.toml`, which the app can show next to the other component versions.

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

/// The crate version, as `Cargo.toml` declares it.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// `StoresNative.nativeVersion(): String` — the crate version (`"0.1.0"`). A null return means the
/// JVM could not allocate the string, which the caller treats like a failed load.
#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_StoresNative_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(VERSION) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
