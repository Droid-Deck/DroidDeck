use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use serde_json::json;

use super::install;
use crate::amazon::install::{Events, Stop};

fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
}

fn new_string(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn owned(ticket: &str, session: &str) -> Result<serde_json::Value, String> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let mut d = super::demux::Demux::connect(ticket).await?;
        let (mut ownership, mut games) = super::service::Ownership::open(&mut d, ticket, session).await?;
        let missing: Vec<u32> = games.iter().filter(|g| g.owned && g.product_type == 0 && g.manifest.is_empty()).map(|g| g.product_id).collect();
        let (found, manifest_error) = match ownership.latest_manifests(&mut d, &missing).await {
            Ok(m) => (m, String::new()),
            Err(e) => (Vec::new(), e),
        };
        for (product, manifest) in &found {
            if let Some(g) = games.iter_mut().find(|g| g.product_id == *product) {
                g.manifest = manifest.clone();
            }
        }
        Ok(json!({ "games": games.iter().map(|g| g.to_json()).collect::<Vec<_>>(),
                   "manifestsAsked": missing.len(), "manifestsFound": found.len(), "manifestError": manifest_error }))
    })
}

fn launch(ticket: &str, session: &str, product: u32) -> Result<serde_json::Value, String> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let mut d = super::demux::Demux::connect(ticket).await?;
        let (mut ownership, games) = super::service::Ownership::open(&mut d, ticket, session).await?;
        if !games.iter().any(|g| g.product_id == product && g.owned) {
            return Err("Ubisoft says this account does not own the game".into());
        }
        let pc_ticket = ownership.pc_ticket(&mut d, product, ticket, session).await?;
        let owned: Vec<u32> = games.iter().filter(|g| g.in_session()).map(|g| g.product_id).collect();
        Ok(json!({ "ticket": pc_ticket, "owned": owned }))
    })
}

fn call(op: &str, request: &str) -> String {
    let parsed: serde_json::Value = serde_json::from_str(request).unwrap_or(serde_json::Value::Null);
    let s = |k: &str| parsed.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
    let answer = match op {
        "owned" => owned(&s("ticket"), &s("sessionId")).unwrap_or_else(|e| json!({ "error": e })),
        "launch" => launch(&s("ticket"), &s("sessionId"), parsed.get("productId").and_then(|v| v.as_u64()).unwrap_or(0) as u32)
            .unwrap_or_else(|e| json!({ "error": e })),
        _ => json!({ "error": format!("unknown operation {op}") }),
    };
    answer.to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_ubisoft_UbisoftNative_nativeCall(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    request: JString,
) -> jstring {
    let op = read_string(&mut env, &op);
    let request = read_string(&mut env, &request);
    match catch_unwind(AssertUnwindSafe(|| call(&op, &request))) {
        Ok(text) => new_string(&mut env, &text),
        Err(_) => std::ptr::null_mut(),
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

    fn text(&self, method: &str, text: &str) {
        self.with_env((), |env| {
            let Ok(s) = env.new_string(text) else { return };
            let s = JObject::from(s);
            let _ = env.call_method(self.target.as_obj(), method, "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
            let _ = env.delete_local_ref(s);
        });
    }

    fn counts(&self, method: &str, a: u64, b: u64, c: u32, d: u32) {
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
        self.text("onLog", &crate::amazon::net::scrub_urls(line));
    }

    fn status(&self, text: &str) {
        self.text("onStatus", text);
    }

    fn checking(&self, done_bytes: u64, total_bytes: u64, files: u32, files_total: u32) {
        self.counts("onCheck", done_bytes, total_bytes, files, files_total);
    }

    fn sizes(&self, pending_bytes: u64, total_bytes: u64) {
        self.with_env((), |env| {
            let _ = env.call_method(
                self.target.as_obj(),
                "onSizes",
                "(JJ)V",
                &[
                    JValue::Long(pending_bytes.min(i64::MAX as u64) as i64),
                    JValue::Long(total_bytes.min(i64::MAX as u64) as i64),
                ],
            );
        });
    }

    fn progress(&self, done: u64, total: u64, files: u32, files_total: u32) {
        self.counts("onProgress", done, total, files, files_total);
    }

    fn before_replace(&self) -> bool {
        self.with_env(false, |env| match env.call_method(self.target.as_obj(), "beforeReplace", "()Z", &[]) {
            Ok(v) => v.z().unwrap_or(false),
            Err(_) => false,
        })
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_ubisoft_UbisoftNative_nativeInstall(
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
    let spawned = std::thread::Builder::new().name("ubisoft-install".into()).spawn(move || {
        crate::priority::background();
        let result = catch_unwind(AssertUnwindSafe(|| install::run(&request, &worker_cancel, &listener)))
            .unwrap_or_else(|_| Err(Stop::Failed("The Ubisoft install stopped on an internal error".into())));
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
pub extern "system" fn Java_com_droiddeck_launcher_stores_ubisoft_UbisoftNative_nativeCancel(_env: JNIEnv, _class: JClass, handle: jlong) {
    let ptr = handle as *const Arc<AtomicBool>;
    if let Some(cancel) = unsafe { ptr.as_ref() } {
        cancel.store(true, Ordering::SeqCst);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_ubisoft_UbisoftNative_nativeRelease(_env: JNIEnv, _class: JClass, handle: jlong) {
    let ptr = handle as *mut Arc<AtomicBool>;
    if !ptr.is_null() {
        drop(unsafe { Box::from_raw(ptr) });
    }
}
