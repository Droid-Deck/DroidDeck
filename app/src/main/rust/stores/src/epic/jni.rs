
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

use jni::objects::{GlobalRef, JByteArray, JClass, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};

use super::assemble::{build_assemble_plan, run_assemble, AssembleRequest};
use super::driver::{build_plan, run_plan, EpicRequest};
use super::stream::{build_stream_plan, run_stream, Hooks, StreamProgress, StreamRequest};

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

fn android_log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let Ok(tag) = CString::new(super::LOG_TAG) else {
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

pub struct EpicDownloadHandle {
    cancel: Arc<AtomicBool>,
    worker: Option<thread::JoinHandle<()>>,
}

fn clear_pending_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
}

fn string_array_to_vec(env: &mut JNIEnv, array: &JObjectArray) -> Vec<String> {
    let len = env.get_array_length(array).unwrap_or(0);
    let mut out = Vec::with_capacity(len.max(0) as usize);
    let mut i = 0;
    while i < len {
        match env.get_object_array_element(array, i) {
            Ok(obj) => {
                if obj.is_null() {
                    out.push(String::new());
                } else {
                    let js = JString::from(obj);
                    out.push(jstring_to_string(env, &js).unwrap_or_default());
                }
            }
            Err(_) => {
                clear_pending_exception(env);
                out.push(String::new());
            }
        }
        i += 1;
    }
    out
}

fn int_array_to_vec(env: &JNIEnv, array: &JIntArray) -> Vec<i32> {
    let len = env.get_array_length(array).unwrap_or(0);
    if len <= 0 {
        return Vec::new();
    }
    let mut values = vec![0i32; len as usize];
    if env.get_int_array_region(array, 0, &mut values).is_err() {
        return Vec::new();
    }
    values
}

fn call_on_log(env: &mut JNIEnv, listener: &JObject, line: &str) {
    if listener.is_null() {
        return;
    }
    let Ok(s) = env.new_string(line) else {
        clear_pending_exception(env);
        return;
    };
    let obj = JObject::from(s);
    let _ = env.call_method(
        listener,
        "onLog",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&obj)],
    );
    clear_pending_exception(env);
}

fn call_on_plan(env: &mut JNIEnv, listener: &JObject, chunks_total: u64, bytes_total: u64, chunk_dir: &str) {
    if listener.is_null() {
        return;
    }
    let Ok(s) = env.new_string(chunk_dir) else {
        clear_pending_exception(env);
        return;
    };
    let obj = JObject::from(s);
    let _ = env.call_method(
        listener,
        "onPlan",
        "(IJLjava/lang/String;)V",
        &[
            JValue::Int(chunks_total.min(i32::MAX as u64) as jint),
            JValue::Long(bytes_total as jlong),
            JValue::Object(&obj),
        ],
    );
    clear_pending_exception(env);
}

fn call_on_progress(
    env: &mut JNIEnv,
    listener: &JObject,
    bytes_done: u64,
    bytes_total: u64,
    chunks_done: u64,
    chunks_total: u64,
) {
    if listener.is_null() {
        return;
    }
    let _ = env.call_method(
        listener,
        "onProgress",
        "(JJII)V",
        &[
            JValue::Long(bytes_done as jlong),
            JValue::Long(bytes_total as jlong),
            JValue::Int(chunks_done.min(i32::MAX as u64) as jint),
            JValue::Int(chunks_total.min(i32::MAX as u64) as jint),
        ],
    );
    clear_pending_exception(env);
}

fn call_on_complete(env: &mut JNIEnv, listener: &JObject, success: bool, error: &str, bytes: u64) {
    if listener.is_null() {
        return;
    }
    let Ok(s) = env.new_string(error) else {
        clear_pending_exception(env);
        return;
    };
    let obj = JObject::from(s);
    let _ = env.call_method(
        listener,
        "onComplete",
        "(ZLjava/lang/String;J)V",
        &[
            JValue::Bool(if success { JNI_TRUE } else { JNI_FALSE }),
            JValue::Object(&obj),
            JValue::Long(bytes as jlong),
        ],
    );
    clear_pending_exception(env);
}

fn log_both(env: &mut JNIEnv, listener: &JObject, line: &str) {
    android_log(line);
    call_on_log(env, listener, line);
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    install_dir: JString,
    chunk_cache_dir: JString,
    cdn_prefixes: JObjectArray,
    pending_file_idx: JIntArray,
    expected_chunks: jint,
    expected_bytes: jlong,
    ca_bundle_path: JString,
    max_workers: jint,
    process_workers: jint,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        android_log("nativeStart: null listener");
        return 0;
    }
    let Ok(manifest_bytes) = env.convert_byte_array(manifest) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "manifest bytes unreadable", 0);
        return 0;
    };
    let install_dir = jstring_to_string(&mut env, &install_dir).unwrap_or_default();
    let chunk_cache_dir = jstring_to_string(&mut env, &chunk_cache_dir).unwrap_or_default();
    let cdn_prefixes = string_array_to_vec(&mut env, &cdn_prefixes);
    let pending_file_indices: Vec<usize> = int_array_to_vec(&env, &pending_file_idx)
        .into_iter()
        .map(|i| i.max(0) as usize)
        .collect();
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let max_workers = max_workers.max(1) as usize;
    let process_workers = process_workers.max(1) as usize;
    let label = {
        let base = std::path::Path::new(&install_dir)
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        format!("epic app={base}")
    };

    let host_count = super::plan::distinct_prefixes(&cdn_prefixes).len();
    let host_cap = super::plan::per_host_cap(max_workers, host_count);
    log_both(
        &mut env,
        &listener,
        &format!(
            "engine=rust label=\"{label}\" install_dir={install_dir} cache_dir={} cdns={} hosts={host_count} pending_files={} workers={max_workers} per_host_cap={host_cap} process_workers={process_workers} manifest_bytes={}",
            if chunk_cache_dir.is_empty() { "<installDir>/.chunks" } else { chunk_cache_dir.as_str() },
            cdn_prefixes.len(),
            pending_file_indices.len(),
            manifest_bytes.len()
        ),
    );

    let req = EpicRequest {
        manifest_bytes,
        install_dir,
        chunk_cache_dir,
        cdn_prefixes,
        pending_file_indices,
        expected_chunks: if expected_chunks >= 0 {
            Some(expected_chunks as u64)
        } else {
            None
        },
        expected_bytes: if expected_bytes >= 0 {
            Some(expected_bytes as u64)
        } else {
            None
        },
        ca_bundle_path,
        max_workers,
        process_workers,
        label,
    };

    let plan = match build_plan(&req) {
        Ok(plan) => plan,
        Err(err) => {
            log_both(&mut env, &listener, &format!("not started: {err}"));
            call_on_complete(&mut env, &listener, false, &err, 0);
            return 0;
        }
    };
    call_on_plan(
        &mut env,
        &listener,
        plan.needed.len() as u64,
        plan.total_bytes,
        &plan.manifest.chunk_dir,
    );

    let Ok(vm) = env.get_java_vm() else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "JavaVM unavailable", 0);
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "listener ref failed", 0);
        return 0;
    };

    let cancel = Arc::new(AtomicBool::new(false));
    let thread_cancel = Arc::clone(&cancel);
    let worker = thread::spawn(move || {
        crate::priority::background();
        run_on_thread(vm, listener, plan, req, thread_cancel);
    });
    let handle = Box::new(EpicDownloadHandle { cancel, worker: Some(worker) });

    Box::into_raw(handle) as jlong
}

fn run_on_thread(
    vm: JavaVM,
    listener: GlobalRef,
    plan: super::driver::EpicPlan,
    req: EpicRequest,
    cancel: Arc<AtomicBool>,
) {
    let bytes_total = plan.total_bytes;
    let chunks_total = plan.needed.len() as u64;

    let progress = |bytes_done: u64, chunks_done: u64| {
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_progress(
            &mut env,
            listener.as_obj(),
            bytes_done,
            bytes_total,
            chunks_done,
            chunks_total,
        );
    };
    let log = |line: &str| {
        android_log(line);
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_log(&mut env, listener.as_obj(), line);
    };

    let outcome = run_plan(&plan, &req, cancel.as_ref(), &progress, &log);

    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    call_on_complete(
        &mut env,
        listener.as_obj(),
        outcome.success,
        &outcome.error,
        outcome.bytes_credited,
    );
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeAssemble(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    install_dir: JString,
    chunk_cache_dir: JString,
    file_idx: JIntArray,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        android_log("nativeAssemble: null listener");
        return 0;
    }
    let Ok(manifest_bytes) = env.convert_byte_array(manifest) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "manifest bytes unreadable", 0);
        return 0;
    };
    let req = AssembleRequest {
        manifest_bytes,
        install_dir: jstring_to_string(&mut env, &install_dir).unwrap_or_default(),
        chunk_cache_dir: jstring_to_string(&mut env, &chunk_cache_dir).unwrap_or_default(),
        file_indices: int_array_to_vec(&env, &file_idx)
            .into_iter()
            .map(|i| i.max(0) as usize)
            .collect(),
    };
    let plan = match build_assemble_plan(&req) {
        Ok(plan) => plan,
        Err(err) => {
            log_both(&mut env, &listener, &format!("not started: {err}"));
            call_on_complete(&mut env, &listener, false, &err, 0);
            return 0;
        }
    };
    let Ok(vm) = env.get_java_vm() else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "JavaVM unavailable", 0);
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "listener ref failed", 0);
        return 0;
    };

    let cancel = Arc::new(AtomicBool::new(false));
    let thread_cancel = Arc::clone(&cancel);
    let worker = thread::spawn(move || {
        crate::priority::background();
        let progress = |bytes_done: u64, bytes_total: u64, files_done: u64, files_total: u64| {
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
                return;
            };
            call_on_progress(&mut env, listener.as_obj(), bytes_done, bytes_total, files_done, files_total);
        };
        let log = |line: &str| {
            android_log(line);
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
                return;
            };
            call_on_log(&mut env, listener.as_obj(), line);
        };
        let outcome = run_assemble(&plan, thread_cancel.as_ref(), &progress, &log);
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_complete(
            &mut env,
            listener.as_obj(),
            outcome.success,
            &outcome.error,
            outcome.bytes_written,
        );
    });
    let handle = Box::new(EpicDownloadHandle { cancel, worker: Some(worker) });

    Box::into_raw(handle) as jlong
}

fn call_on_stream_progress(env: &mut JNIEnv, listener: &JObject, p: &StreamProgress) {
    if listener.is_null() {
        return;
    }
    let _ = env.call_method(
        listener,
        "onStreamProgress",
        "(JJIIJ)V",
        &[
            JValue::Long(p.bytes_written as jlong),
            JValue::Long(p.bytes_total as jlong),
            JValue::Int(p.files_done.min(i32::MAX as u64) as jint),
            JValue::Int(p.files_total.min(i32::MAX as u64) as jint),
            JValue::Long(p.files_bytes as jlong),
        ],
    );
    clear_pending_exception(env);
}

/// `boolean onBeforeReplace()`: the Java side marks an existing install `updating`. A thrown
/// exception or `false` is a refusal.
fn call_on_before_replace(env: &mut JNIEnv, listener: &JObject) -> Result<(), String> {
    if listener.is_null() {
        return Ok(());
    }
    let result = env
        .call_method(listener, "onBeforeReplace", "()Z", &[])
        .and_then(|v| v.z());
    let threw = env.exception_check().unwrap_or(false);
    clear_pending_exception(env);
    match result {
        Ok(true) if !threw => Ok(()),
        _ => Err("could not mark the install as updating".to_string()),
    }
}

/// `EpicNative.nativeStream(...)`: the adaptive install (see `stream.rs`). Parse, plan and cache
/// preparation run on the calling thread, so a refusal returns 0 (after a synchronous
/// `onComplete(false, reason, 0)`) before anything is fetched or written, and the manager runs
/// the bounded Java writer instead. The run itself is on a new thread; `onComplete` fires after
/// every unfinished staging file of a cancelled or failed run is gone.
#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeStream(
    mut env: JNIEnv,
    _class: JClass,
    manifest: JByteArray,
    install_dir: JString,
    chunk_cache_dir: JString,
    staging_dir: JString,
    cdn_prefixes: JObjectArray,
    pending_file_idx: JIntArray,
    expected_bytes: jlong,
    cache_budget: jlong,
    overlap_budget: jlong,
    ca_bundle_path: JString,
    max_workers: jint,
    process_workers: jint,
    listener: JObject,
) -> jlong {
    if listener.is_null() {
        android_log("nativeStream: null listener");
        return 0;
    }
    let Ok(manifest_bytes) = env.convert_byte_array(manifest) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "manifest bytes unreadable", 0);
        return 0;
    };
    let install_dir = jstring_to_string(&mut env, &install_dir).unwrap_or_default();
    let chunk_cache_dir = jstring_to_string(&mut env, &chunk_cache_dir).unwrap_or_default();
    let staging_dir = jstring_to_string(&mut env, &staging_dir).unwrap_or_default();
    let cdn_prefixes = string_array_to_vec(&mut env, &cdn_prefixes);
    let pending_file_indices: Vec<usize> = int_array_to_vec(&env, &pending_file_idx)
        .into_iter()
        .map(|i| i.max(0) as usize)
        .collect();
    let ca_bundle_path = jstring_to_string(&mut env, &ca_bundle_path).unwrap_or_default();
    let max_workers = max_workers.max(1) as usize;
    let process_workers = process_workers.max(1) as usize;
    let label = {
        let base = std::path::Path::new(&install_dir)
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        format!("epic app={base}")
    };
    log_both(
        &mut env,
        &listener,
        &format!(
            "engine=rust-stream label=\"{label}\" install_dir={install_dir} cache_dir={} cdns={} pending_files={} cache_budget={} workers={max_workers} process_workers={process_workers} manifest_bytes={}",
            if chunk_cache_dir.is_empty() { "<installDir>/.chunks" } else { chunk_cache_dir.as_str() },
            cdn_prefixes.len(),
            pending_file_indices.len(),
            cache_budget.max(0),
            manifest_bytes.len()
        ),
    );

    let req = StreamRequest {
        manifest_bytes,
        install_dir,
        chunk_cache_dir,
        staging_dir,
        cdn_prefixes,
        pending_file_indices,
        expected_bytes: if expected_bytes >= 0 {
            Some(expected_bytes as u64)
        } else {
            None
        },
        cache_budget: cache_budget.max(0) as u64,
        // Negative = no limit (nothing the manager knows of is being replaced).
        replace_overlap_budget: if overlap_budget < 0 {
            u64::MAX
        } else {
            overlap_budget as u64
        },
        ca_bundle_path,
        max_workers,
        process_workers,
        label,
    };
    let plan = match build_stream_plan(&req) {
        Ok(plan) => plan,
        Err(err) => {
            log_both(&mut env, &listener, &format!("not started: {err}"));
            call_on_complete(&mut env, &listener, false, &err, 0);
            return 0;
        }
    };
    let Ok(vm) = env.get_java_vm() else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "JavaVM unavailable", 0);
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        clear_pending_exception(&mut env);
        call_on_complete(&mut env, &listener, false, "listener ref failed", 0);
        return 0;
    };

    let cancel = Arc::new(AtomicBool::new(false));
    let thread_cancel = Arc::clone(&cancel);
    let worker = thread::spawn(move || {
        crate::priority::background();
        let progress = |p: &StreamProgress| {
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
                return;
            };
            call_on_stream_progress(&mut env, listener.as_obj(), p);
        };
        let before_replace = || {
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
                return Err("JavaVM attach failed".to_string());
            };
            call_on_before_replace(&mut env, listener.as_obj())
        };
        let log = |line: &str| {
            android_log(line);
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
                return;
            };
            call_on_log(&mut env, listener.as_obj(), line);
        };
        let hooks = Hooks {
            progress: &progress,
            before_replace: &before_replace,
            log: &log,
        };
        let outcome = run_stream(&plan, &req, thread_cancel.as_ref(), &hooks);
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        call_on_complete(
            &mut env,
            listener.as_obj(),
            outcome.success,
            &outcome.error,
            outcome.bytes_written,
        );
    });
    let handle = Box::new(EpicDownloadHandle { cancel, worker: Some(worker) });

    Box::into_raw(handle) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let handle = unsafe { &*(handle as *const EpicDownloadHandle) };
    handle.cancel.store(true, Ordering::Relaxed);
    android_log("cancel requested");
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut handle = unsafe { Box::from_raw(handle as *mut EpicDownloadHandle) };
    if let Some(worker) = handle.worker.take() { let _ = worker.join(); }
}

/// ABI v1 snapshot: selected chunks/files, verified cache lengths, old output lengths and both
/// measured free-space probes. Parse/budget failures are terminal, never fallback requests.
#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_epic_EpicNative_nativeInstallAdaptive(
    mut env: JNIEnv, _class: JClass, request: JString, listener: JObject,
) -> jlong {
    if listener.is_null() { return 0; }
    let parsed = jstring_to_string(&mut env, &request).ok_or("unreadable adaptive request".to_string())
        .and_then(|s| super::adaptive::Request::parse(&s))
        .and_then(|req| super::adaptive::choose_budget(&req).map(|budget| (req, budget)));
    let (req, budget) = match parsed {
        Ok(plan) => plan,
        Err(error) => {
            call_on_complete(&mut env, &listener, false, &error, 0);
            return 0;
        }
    };
    call_on_plan(&mut env, &listener, req.chunks.len() as u64, 0,
        &format!("lookahead={} retained={} game_peak={} cache_peak={}",
            budget.lookahead, budget.retained, budget.game_peak, budget.cache_peak));
    let Ok(vm) = env.get_java_vm() else {
        call_on_complete(&mut env, &listener, false, "JavaVM unavailable", 0);
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        call_on_complete(&mut env, &listener, false, "listener ref failed", 0);
        return 0;
    };
    let cancel = Arc::new(AtomicBool::new(false));
    let thread_cancel = cancel.clone();
    let worker = thread::spawn(move || {
        crate::priority::background();
        let progress = |done, total, files, files_total| {
            if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
                call_on_progress(&mut env, listener.as_obj(), done, total, files, files_total);
            }
        };
        let before_swap = || -> Result<(), String> {
            let mut env = vm.attach_current_thread_as_daemon().map_err(|e| e.to_string())?;
            match env.call_method(listener.as_obj(), "beforeSwap", "()Z", &[]) {
                Ok(value) => if value.z().unwrap_or(false) { Ok(()) } else { Err("Could not mark installed record updating before replacement".into()) },
                _ => {
                    clear_pending_exception(&mut env);
                    Err("Could not mark installed record updating before replacement".into())
                }
            }
        };
        let log = |line: &str| {
            android_log(line);
            if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
                call_on_log(&mut env, listener.as_obj(), line);
            }
        };
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(||
            super::adaptive::run(&req, budget, &thread_cancel, &before_swap, &progress, &log)))
            .unwrap_or_else(|_| Err("Native Epic install panicked".into()));
        let (success, error, bytes) = match outcome {
            Ok(bytes) => (true, String::new(), bytes),
            Err(error) => (false, error, 0),
        };
        if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
            call_on_complete(&mut env, listener.as_obj(), success, &error, bytes);
        }
    });
    Box::into_raw(Box::new(EpicDownloadHandle { cancel, worker: Some(worker) })) as jlong
}
