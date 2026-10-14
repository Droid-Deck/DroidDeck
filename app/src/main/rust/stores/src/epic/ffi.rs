use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};

use super::install::{self, Events, Stop};
use super::{net, service};

pub const LOG_TAG: &str = "EpicNative";

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

pub fn android_log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let (Ok(tag), Ok(text)) = (CString::new(LOG_TAG), CString::new(message.replace('\0', " "))) else {
            return;
        };
        unsafe {
            let _ = __android_log_write(4, tag.as_ptr().cast(), text.as_ptr().cast());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = message;
    }
}

fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn new_string(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn guarded<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| {
        android_log("a native Epic call panicked");
        fallback
    })
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeConfigure(
    mut env: JNIEnv,
    _class: JClass,
    app_version: JString,
) {
    let version = read_string(&mut env, &app_version);
    net::set_app_version(&version);
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeCall(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    request: JString,
) -> jstring {
    let op = read_string(&mut env, &op);
    let request = read_string(&mut env, &request);
    let answer = guarded(None, || Some(service::call(&op, &request)));
    match answer {
        Some(text) => new_string(&mut env, &text),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeFetch(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    request: JString,
) -> jbyteArray {
    let op = read_string(&mut env, &op);
    let request = read_string(&mut env, &request);
    match guarded(None, || service::fetch(&op, &request)) {
        Some(bytes) => env
            .byte_array_from_slice(&bytes)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeSend(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    request: JString,
    body: JByteArray,
) -> jstring {
    let op = read_string(&mut env, &op);
    let request = read_string(&mut env, &request);
    let bytes = if body.is_null() { Vec::new() } else { env.convert_byte_array(&body).unwrap_or_default() };
    let answer = guarded(None, || Some(service::send(&op, &request, bytes)));
    match answer {
        Some(text) => new_string(&mut env, &text),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeInspect(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    request: JString,
) -> jstring {
    let request = read_string(&mut env, &request);
    let bytes = if manifest.is_null() { Vec::new() } else { env.convert_byte_array(&manifest).unwrap_or_default() };
    let answer = guarded(None, || Some(service::inspect(&bytes, &request)));
    match answer {
        Some(text) => new_string(&mut env, &text),
        None => std::ptr::null_mut(),
    }
}

struct Listener {
    vm: JavaVM,
    target: GlobalRef,
}

impl Listener {
    fn with_env<R>(&self, fallback: R, f: impl FnOnce(&mut JNIEnv) -> R) -> R {
        let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else {
            return fallback;
        };
        let out = f(&mut env);
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        out
    }

    fn call_text(&self, method: &str, text: &str) {
        self.with_env((), |env| {
            let Ok(s) = env.new_string(text) else { return };
            let s = JObject::from(s);
            let _ = env.call_method(self.target.as_obj(), method, "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
            let _ = env.delete_local_ref(s);
        });
    }

    fn call_counts(&self, method: &str, a: u64, b: u64, c: u32, d: u32) {
        self.with_env((), |env| {
            let _ = env.call_method(
                self.target.as_obj(),
                method,
                "(JJII)V",
                &[
                    JValue::Long(a.min(i64::MAX as u64) as i64),
                    JValue::Long(b.min(i64::MAX as u64) as i64),
                    JValue::Int(c.min(i32::MAX as u32) as i32),
                    JValue::Int(d.min(i32::MAX as u32) as i32),
                ],
            );
        });
    }

    fn complete(&self, success: bool, cancelled: bool, error: &str, result: &str) {
        self.with_env((), |env| {
            let (Ok(e), Ok(r)) = (env.new_string(error), env.new_string(result)) else { return };
            let (e, r) = (JObject::from(e), JObject::from(r));
            let _ = env.call_method(
                self.target.as_obj(),
                "onComplete",
                "(ZZLjava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Bool(if success { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Bool(if cancelled { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Object(&e),
                    JValue::Object(&r),
                ],
            );
            let _ = env.delete_local_ref(e);
            let _ = env.delete_local_ref(r);
        });
    }
}

impl Events for Listener {
    fn log(&self, line: &str) {
        android_log(line);
        self.call_text("onLog", line);
    }

    fn status(&self, text: &str) {
        self.call_text("onStatus", text);
    }

    fn checking(&self, done_bytes: u64, total_bytes: u64, files: u32, files_total: u32) {
        self.call_counts("onCheck", done_bytes, total_bytes, files, files_total);
    }

    fn sizes(&self, pending_bytes: u64, disk_bytes: u64) {
        self.with_env((), |env| {
            let _ = env.call_method(
                self.target.as_obj(),
                "onSizes",
                "(JJ)V",
                &[JValue::Long(pending_bytes as i64), JValue::Long(disk_bytes as i64)],
            );
        });
    }

    fn progress(&self, written: u64, total: u64, files: u32, files_total: u32) {
        self.call_counts("onProgress", written, total, files, files_total);
    }

    fn installing(&self, done: u64, total: u64, files: u32, files_total: u32) {
        self.call_counts("onInstall", done, total, files, files_total);
    }

    fn before_replace(&self) -> bool {
        self.with_env(false, |env| {
            match env.call_method(self.target.as_obj(), "beforeReplace", "()Z", &[]) {
                Ok(v) => v.z().unwrap_or(false),
                Err(_) => false,
            }
        })
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeInstall(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        return 0;
    }
    let Ok(vm) = env.get_java_vm() else { return 0 };
    let Ok(target) = env.new_global_ref(&listener) else { return 0 };
    let listener = Listener { vm, target };
    let text = read_string(&mut env, &request);
    let parsed = serde_json::from_str::<serde_json::Value>(&text)
        .map_err(|_| "The install request could not be read".to_string())
        .and_then(|v| install::Request::from_json(&v));
    let request = match parsed {
        Ok(r) => r,
        Err(e) => {
            listener.complete(false, false, &e, "");
            return 0;
        }
    };
    let cancel = Arc::new(AtomicBool::new(false));
    let worker_cancel = Arc::clone(&cancel);
    let spawned = std::thread::Builder::new().name("epic-install".into()).spawn(move || {
        crate::priority::background();
        let result = catch_unwind(AssertUnwindSafe(|| install::run(&request, &worker_cancel, &listener)))
            .unwrap_or_else(|_| Err(Stop::Failed("Native Epic install failed: internal error".into())));
        match result {
            Ok(done) => listener.complete(true, false, "", &done.to_json().to_string()),
            Err(Stop::Cancelled) => listener.complete(false, true, "cancelled", ""),
            Err(Stop::Failed(e)) => listener.complete(false, false, &e, ""),
        }
    });
    if spawned.is_err() {
        return 0;
    }
    Box::into_raw(Box::new(cancel)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let ptr = handle as *const Arc<AtomicBool>;
    if let Some(cancel) = unsafe { ptr.as_ref() } {
        cancel.store(true, Ordering::SeqCst);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let ptr = handle as *mut Arc<AtomicBool>;
    if !ptr.is_null() {
        drop(unsafe { Box::from_raw(ptr) });
    }
}
