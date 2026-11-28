//! JNI surface of the Steam store: `com.droiddeck.launcher.stores.steam.SteamNative`
//! (see `JNI.md`). Every answer is a JSON object with `"ok"`; failures carry `"error"`.

use crate::steam::install::{self, Events, StartRequest};
use crate::steam::register::{self, LibraryRoot};
use crate::steam::session;
use crate::steam::signin;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

static RUNNING: Mutex<Option<HashSet<u32>>> = Mutex::new(None);

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn answer(env: &mut JNIEnv, value: Value) -> jstring {
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn error(message: impl Into<String>) -> Value {
    json!({"ok": false, "error": message.into()})
}

/// Runs `f`, turning a panic into an `internal_error` answer.
fn guarded(f: impl FnOnce() -> Value) -> Value {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| error("internal_error"))
}

fn parse_string_array(text: &str) -> Vec<PathBuf> {
    serde_json::from_str::<Value>(text)
        .ok()
        .and_then(|v| v.as_array().cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
        .collect()
}

pub fn parse_library_roots(text: &str) -> Vec<LibraryRoot> {
    serde_json::from_str::<Value>(text)
        .ok()
        .and_then(|v| v.as_array().cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(|v| {
            let host = v.get("host")?.as_str()?;
            let guest = v.get("guest")?.as_str()?;
            (!host.is_empty()).then(|| LibraryRoot {
                host: PathBuf::from(host),
                guest: guest.to_string(),
            })
        })
        .collect()
}

/// All libraries, with the game library first when the list misses it.
fn libraries_with(game: &LibraryRoot, mut libraries: Vec<LibraryRoot>) -> Vec<LibraryRoot> {
    if !libraries.iter().any(|l| l.host == game.host) {
        libraries.insert(0, game.clone());
    }
    libraries
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeSignIn(
    mut env: JNIEnv,
    _class: JClass,
    steam_roots_json: JString,
    registry_vdf_path: JString,
    ca_bundle_path: JString,
) -> jstring {
    let roots = parse_string_array(&jstring_to_string(&mut env, &steam_roots_json));
    let registry = jstring_to_string(&mut env, &registry_vdf_path);
    let ca = jstring_to_string(&mut env, &ca_bundle_path);
    let value = guarded(|| {
        if let Some(live) = session::current() {
            return json!({"ok": true, "account": live.account, "steamId": live.steam_id.to_string()});
        }
        let registry = (!registry.is_empty()).then(|| Path::new(&registry));
        let login = match signin::load_saved_login(&roots, registry) {
            Ok(login) => login,
            Err(err) => {
                crate::steam::log(&format!("sign-in: {}", err.code()));
                return error(err.code());
            }
        };
        match session::sign_in(&login, &ca) {
            Ok(live) => {
                json!({"ok": true, "account": live.account, "steamId": live.steam_id.to_string()})
            }
            Err(err) => error(err),
        }
    });
    answer(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeSignOut(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(session::sign_out);
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeLibrary(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = guarded(|| {
        let Some(live) = session::current() else {
            return error("not_signed_in");
        };
        match live.games() {
            Ok(games) => crate::steam::library::games_json(&games),
            Err(err) => error(err),
        }
    });
    answer(&mut env, value)
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativePlan(
    mut env: JNIEnv,
    _class: JClass,
    app_id: jint,
    language: JString,
) -> jstring {
    let language = jstring_to_string(&mut env, &language);
    let value = guarded(|| {
        let Some(live) = session::current() else {
            return error("not_signed_in");
        };
        if app_id <= 0 {
            return error("bad app id");
        }
        match install::build_plan(&live, app_id as u32, &language, false) {
            Ok(plan) => plan.to_json(),
            Err(err) => error(err),
        }
    });
    answer(&mut env, value)
}

pub struct SteamRunHandle {
    pub cancel: Arc<AtomicBool>,
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
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    }

    fn complete(&self, result: &Value) {
        let ok = result.get("ok").and_then(Value::as_bool).unwrap_or(false);
        let text = result.to_string();
        self.with_env(|env| {
            let Ok(text) = env.new_string(&text) else {
                return;
            };
            let text = JObject::from(text);
            let _ = env.call_method(
                self.listener.as_obj(),
                "onComplete",
                "(ZLjava/lang/String;)V",
                &[
                    JValue::Bool(if ok { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Object(&text),
                ],
            );
            let _ = env.delete_local_ref(text);
        });
    }
}

impl Events for JniEvents {
    fn progress(&self, done: u64, total: u64, phase: &str) {
        self.with_env(|env| {
            let Ok(phase) = env.new_string(phase) else {
                return;
            };
            let phase = JObject::from(phase);
            let _ = env.call_method(
                self.listener.as_obj(),
                "onProgress",
                "(JJLjava/lang/String;)V",
                &[
                    JValue::Long(done as jlong),
                    JValue::Long(total as jlong),
                    JValue::Object(&phase),
                ],
            );
            let _ = env.delete_local_ref(phase);
        });
    }
}

fn claim(app_id: u32) -> bool {
    let mut guard = RUNNING.lock().expect("running poisoned");
    guard.get_or_insert_with(HashSet::new).insert(app_id)
}

fn release_claim(app_id: u32) {
    if let Some(set) = RUNNING.lock().expect("running poisoned").as_mut() {
        set.remove(&app_id);
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    app_id: jint,
    language: JString,
    steam_root: JString,
    game_library_host: JString,
    game_library_guest: JString,
    library_roots_json: JString,
    journal_dir: JString,
    ca_bundle_path: JString,
    max_workers: jint,
    process_workers: jint,
    listener: JObject,
) -> jlong {
    if listener.is_null() || app_id <= 0 {
        crate::steam::log("nativeStart: null listener or bad app id");
        return 0;
    }
    let Ok(vm) = env.get_java_vm() else {
        return 0;
    };
    let Ok(listener) = env.new_global_ref(&listener) else {
        return 0;
    };
    let game_library = LibraryRoot {
        host: PathBuf::from(jstring_to_string(&mut env, &game_library_host)),
        guest: jstring_to_string(&mut env, &game_library_guest),
    };
    let libraries = parse_library_roots(&jstring_to_string(&mut env, &library_roots_json));
    let request = StartRequest {
        app_id: app_id as u32,
        language: jstring_to_string(&mut env, &language),
        steam_root: PathBuf::from(jstring_to_string(&mut env, &steam_root)),
        libraries: libraries_with(&game_library, libraries),
        game_library,
        journal_dir: PathBuf::from(jstring_to_string(&mut env, &journal_dir)),
        ca_bundle_path: jstring_to_string(&mut env, &ca_bundle_path),
        max_workers: max_workers.max(0) as usize,
        process_workers: process_workers.max(0) as usize,
    };
    if request.steam_root.as_os_str().is_empty()
        || request.game_library.host.as_os_str().is_empty()
        || request.game_library.guest.is_empty()
        || request.journal_dir.as_os_str().is_empty()
    {
        crate::steam::log("nativeStart: empty path argument");
        return 0;
    }
    let cancel = Arc::new(AtomicBool::new(false));
    let handle = SteamRunHandle {
        cancel: Arc::clone(&cancel),
    };
    let spawned = thread::Builder::new()
        .name("steam-dl".to_string())
        .spawn(move || {
            crate::priority::background();
            let events = JniEvents { vm, listener };
            let result = if !claim(request.app_id) {
                error("already_running")
            } else {
                let result = guarded(|| match session::current() {
                    Some(live) => install::run(&live, &request, &cancel, &events),
                    None => error("not_signed_in"),
                });
                release_claim(request.app_id);
                result
            };
            crate::steam::log(&format!("run app={} result={result}", request.app_id));
            events.complete(&result);
        });
    if spawned.is_err() {
        crate::steam::log("nativeStart: worker thread spawn failed");
        return 0;
    }
    Box::into_raw(Box::new(handle)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let ptr = handle as *const SteamRunHandle;
    if let Some(run) = unsafe { ptr.as_ref() } {
        run.cancel.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let ptr = handle as *mut SteamRunHandle;
    if !ptr.is_null() {
        unsafe { drop(Box::from_raw(ptr)) };
    }
}

/// The file list of a shared depot for uninstall: the client's `depotcache` copy of the manifest
/// (it stores decrypted file names), else the manifest from the CDN through the live session.
fn shared_depot_files(
    steam_root: &Path,
    from_app: u32,
    depot_id: u32,
    manifest_id: u64,
) -> Option<Vec<String>> {
    let cached = steam_root
        .join("depotcache")
        .join(format!("{depot_id}_{manifest_id}.manifest"));
    if let Some(mut manifest) = std::fs::read(&cached)
        .ok()
        .and_then(|raw| crate::steam::content_manifest::ContentManifest::parse(&raw))
    {
        if !manifest.metadata.filenames_encrypted && manifest.decrypt_filenames(&[]) {
            return Some(register::manifest_file_list(&manifest));
        }
    }
    let live = session::current()?;
    let servers = live.cdn_servers()?;
    let ca = live.ca_bundle_path.clone();
    let prepared = crate::steam::depot_downloader::prepare_depot(
        &live,
        &servers,
        None,
        &ca,
        from_app,
        depot_id,
        manifest_id,
        &AtomicBool::new(false),
    )
    .ok()?;
    Some(register::manifest_file_list(&prepared.manifest))
}

#[no_mangle]
pub extern "system" fn Java_com_droiddeck_launcher_stores_steam_SteamNative_nativeUninstall(
    mut env: JNIEnv,
    _class: JClass,
    app_id: jint,
    steam_root: JString,
    game_library_host: JString,
    game_library_guest: JString,
    library_roots_json: JString,
) -> jstring {
    let steam_root = PathBuf::from(jstring_to_string(&mut env, &steam_root));
    let game_library = LibraryRoot {
        host: PathBuf::from(jstring_to_string(&mut env, &game_library_host)),
        guest: jstring_to_string(&mut env, &game_library_guest),
    };
    let libraries = libraries_with(
        &game_library,
        parse_library_roots(&jstring_to_string(&mut env, &library_roots_json)),
    );
    let value = guarded(|| {
        if app_id <= 0 || steam_root.as_os_str().is_empty() {
            return error("bad arguments");
        }
        let root = steam_root.clone();
        let files = move |from_app: u32, depot: u32, manifest: u64| {
            shared_depot_files(&root, from_app, depot, manifest)
        };
        match register::uninstall(
            app_id as u32,
            &steam_root,
            &game_library,
            &libraries,
            &files,
        ) {
            Ok(report) => json!({
                "ok": true,
                "removedShared": report.removed_shared,
                "keptShared": report.kept_shared,
            }),
            Err(err) => error(err),
        }
    });
    answer(&mut env, value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_library_roots_and_keeps_the_game_library() {
        let libs = parse_library_roots(
            r#"[{"host":"/data/Steam","guest":"/root/.local/share/Steam"},{"host":"","guest":"x"},{"guest":"y"}]"#,
        );
        assert_eq!(libs.len(), 1);
        let game = LibraryRoot {
            host: "/sd/steam".into(),
            guest: "/mnt/droiddeck-sd".into(),
        };
        let all = libraries_with(&game, libs);
        assert_eq!(all[0], game);
        assert_eq!(all.len(), 2);
        assert_eq!(
            parse_string_array(r#"["/a", "", 3, "/b"]"#),
            vec![PathBuf::from("/a"), PathBuf::from("/b")]
        );
        assert!(parse_library_roots("not json").is_empty());
    }
}
