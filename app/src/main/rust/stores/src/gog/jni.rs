
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

use jni::objects::{GlobalRef, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};

use super::engine::{self, GogEvents, GogRequest, GogRunResult, PlanKind};

pub const LOG_TAG: &str = "GogNative";

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

pub fn android_log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let Ok(tag) = CString::new(LOG_TAG) else {
            return;
        };
        let sanitized = message.replace('\0', " ");
        let Ok(text) = CString::new(sanitized) else {
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

pub struct GogRunHandle {
    pub cancel: AtomicBool,
}

struct JniEvents {
    vm: JavaVM,
    listener: GlobalRef,
}

impl JniEvents {
    fn with_env(&self, f: impl FnOnce(&mut JNIEnv)) {
        let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else {
            return;
        };
        f(&mut env);
    }

    fn clear_exception(env: &mut JNIEnv) {
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    }

    fn complete(&self, result: &GogRunResult) {
        self.with_env(|env| {
            let Ok(error) = env.new_string(&result.error) else {
                return;
            };
            let error = JObject::from(error);
            let _ = env.call_method(
                self.listener.as_obj(),
                "onComplete",
                "(ZZZLjava/lang/String;JI)V",
                &[
                    JValue::Bool(if result.success { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Bool(if result.cancelled { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Bool(if result.link_expiry { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Object(&error),
                    JValue::Long(result.bytes_written as jlong),
                    JValue::Int(result.files_done as jint),
                ],
            );
            Self::clear_exception(env);
            let _ = env.delete_local_ref(error);
        });
    }
}

impl GogEvents for JniEvents {
    fn on_file_done(
        &self,
        rel_path: &str,
        file_bytes: u64,
        verified: bool,
        files_done: u32,
        files_total: u32,
        bytes_done: u64,
        bytes_total: u64,
    ) {
        self.with_env(|env| {
            let Ok(path) = env.new_string(rel_path) else {
                return;
            };
            let path = JObject::from(path);
            let _ = env.call_method(
                self.listener.as_obj(),
                "onProgress",
                "(JJIILjava/lang/String;JZ)V",
                &[
                    JValue::Long(bytes_done as jlong),
                    JValue::Long(bytes_total as jlong),
                    JValue::Int(files_done as jint),
                    JValue::Int(files_total as jint),
                    JValue::Object(&path),
                    JValue::Long(file_bytes as jlong),
                    JValue::Bool(if verified { JNI_TRUE } else { JNI_FALSE }),
                ],
            );
            Self::clear_exception(env);
            let _ = env.delete_local_ref(path);
        });
    }

    fn on_bytes(&self, bytes_done: u64, bytes_total: u64) {
        self.with_env(|env| {
            let _ = env.call_method(
                self.listener.as_obj(),
                "onBytes",
                "(JJ)V",
                &[
                    JValue::Long(bytes_done as jlong),
                    JValue::Long(bytes_total as jlong),
                ],
            );
            Self::clear_exception(env);
        });
    }

    fn on_log(&self, line: &str) {
        android_log(line);
        self.with_env(|env| {
            let Ok(text) = env.new_string(line) else {
                return;
            };
            let text = JObject::from(text);
            let _ = env.call_method(
                self.listener.as_obj(),
                "onLog",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&text)],
            );
            Self::clear_exception(env);
            let _ = env.delete_local_ref(text);
        });
    }
}

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
}

fn string_array_to_vec(env: &mut JNIEnv, array: &JObjectArray) -> Vec<String> {
    if array.is_null() {
        return Vec::new();
    }
    let len = env.get_array_length(array).unwrap_or(0);
    let mut out = Vec::with_capacity(len.max(0) as usize);
    for i in 0..len {
        let Ok(element) = env.get_object_array_element(array, i) else {
            continue;
        };
        if element.is_null() {
            continue;
        }
        let text = JString::from(element);
        if let Some(s) = jstring_to_string(env, &text) {
            out.push(s);
        }
        let _ = env.delete_local_ref(text);
    }
    out
}

unsafe fn handle_ref(handle: jlong) -> Option<&'static Arc<GogRunHandle>> {
    let ptr = handle as *const Arc<GogRunHandle>;
    if ptr.is_null() {
        None
    } else {
        Some(unsafe { &*ptr })
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gog_GogNative_nativeProbe(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gog_GogNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    kind: jint,
    depot_manifests: JObjectArray,
    cdn_bases: JObjectArray,
    install_dir: JString,
    skip_paths: JObjectArray,
    ca_bundle_path: JString,
    max_workers: jint,
    process_workers: jint,
    sort_largest_first: jboolean,
    label: JString,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        android_log("nativeStart: null listener");
        return 0;
    }
    let Ok(vm) = env.get_java_vm() else {
        android_log("nativeStart: JavaVM unavailable");
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        android_log("nativeStart: listener ref failed");
        return 0;
    };
    let request = GogRequest {
        kind: PlanKind::from_i32(kind),
        depot_manifests: string_array_to_vec(&mut env, &depot_manifests),
        cdn_bases: string_array_to_vec(&mut env, &cdn_bases),
        install_dir: jstring_to_string(&mut env, &install_dir).unwrap_or_default(),
        skip_paths: string_array_to_vec(&mut env, &skip_paths),
        ca_bundle_path: jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default(),
        max_workers: max_workers.max(1) as usize,
        process_workers: process_workers.max(1) as usize,
        sort_largest_first: sort_largest_first != JNI_FALSE,
        label: jstring_to_string(&mut env, &label).unwrap_or_else(|| "gog".to_string()),
    };
    let needs_base = request.kind == PlanKind::Gen2Chunks;
    if (needs_base && super::engine::distinct_bases(&request.cdn_bases).is_empty())
        || request.install_dir.is_empty()
        || request.depot_manifests.is_empty()
    {
        android_log("nativeStart: invalid request (no cdn base / empty install dir / no manifests)");
        return 0;
    }

    let handle = Arc::new(GogRunHandle {
        cancel: AtomicBool::new(false),
    });
    let worker = Arc::clone(&handle);
    let spawned = thread::Builder::new()
        .name("gog-dl".to_string())
        .spawn(move || {
            crate::priority::background();
            let events = JniEvents { vm, listener };
            let result = engine::run(&request, &worker.cancel, &events);
            events.complete(&result);
        });
    if spawned.is_err() {
        android_log("nativeStart: worker thread spawn failed");
        return 0;
    }
    Box::into_raw(Box::new(handle)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gog_GogNative_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(run) = unsafe { handle_ref(handle) } {
        run.cancel.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gog_GogNative_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let ptr = handle as *mut Arc<GogRunHandle>;
    if ptr.is_null() {
        return;
    }
    unsafe {
        drop(Box::from_raw(ptr));
    }
}
