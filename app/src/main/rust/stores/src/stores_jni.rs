
use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

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
