//! DroidDeck's GeForce NOW streamer host.
//!
//! OpenNOW's native streamer engine (external/opennow, MIT, by Zortos) runs on its own
//! thread with DroidDeck's Android media backend. Kotlin sends the engine's JSON
//! commands (`hello`, `start`, `stop`, ...) and polls one queue for everything the
//! engine answers or reports. The player's Surface and gamepad state come in through
//! the other entry points.

use std::collections::VecDeque;
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use ndk::native_window::NativeWindow;
use opennow_streamer_core::Engine;
use opennow_streamer_platform::{CapturedInput, CapturedInputQueue, create_android_runtime};
use opennow_streamer_protocol::{Command, error};
use serde_json::Value;

const MAX_QUEUED_MESSAGES: usize = 4096;

#[derive(Default)]
struct Outbox {
    messages: Mutex<VecDeque<String>>,
    ready: Condvar,
}

impl Outbox {
    fn push(&self, message: &Value) {
        let mut messages = self.messages.lock().unwrap_or_else(|error| error.into_inner());
        if messages.len() >= MAX_QUEUED_MESSAGES {
            messages.pop_front();
        }
        messages.push_back(message.to_string());
        self.ready.notify_one();
    }

    fn pop(&self, timeout: Duration) -> Option<String> {
        let messages = self.messages.lock().unwrap_or_else(|error| error.into_inner());
        let (mut messages, _) = self
            .ready
            .wait_timeout_while(messages, timeout, |messages| messages.is_empty())
            .unwrap_or_else(|error| error.into_inner());
        messages.pop_front()
    }
}

struct Host {
    commands: Mutex<Option<Sender<String>>>,
    outbox: Arc<Outbox>,
    input: Arc<CapturedInputQueue>,
    engine: Mutex<Option<JoinHandle<()>>>,
    events: Mutex<Option<JoinHandle<()>>>,
}

impl Host {
    fn open() -> Result<Self, String> {
        let outbox = Arc::new(Outbox::default());
        let input = Arc::new(CapturedInputQueue::default());
        let runtime = create_android_runtime(Arc::clone(&input));
        let (event_tx, event_rx) = mpsc::channel::<Value>();
        let (command_tx, command_rx) = mpsc::channel::<String>();

        let event_outbox = Arc::clone(&outbox);
        let events = thread::Builder::new()
            .name("gfn-events".to_owned())
            .spawn(move || {
                while let Ok(message) = event_rx.recv() {
                    event_outbox.push(&message);
                }
            })
            .map_err(|error| error.to_string())?;

        let engine_outbox = Arc::clone(&outbox);
        let engine = thread::Builder::new()
            .name("gfn-engine".to_owned())
            .spawn(move || run_engine(Engine::with_media_runtime(event_tx, runtime), &command_rx, &engine_outbox))
            .map_err(|error| error.to_string())?;

        Ok(Self {
            commands: Mutex::new(Some(command_tx)),
            outbox,
            input,
            engine: Mutex::new(Some(engine)),
            events: Mutex::new(Some(events)),
        })
    }

    fn send(&self, command: String) -> bool {
        self.commands
            .lock()
            .ok()
            .and_then(|commands| commands.as_ref().map(|sender| sender.send(command).is_ok()))
            .unwrap_or(false)
    }

    fn close(&self) {
        let shutdown = serde_json::json!({"id": "droiddeck-close", "type": "shutdown"}).to_string();
        let _ = self.send(shutdown);
        if let Ok(mut commands) = self.commands.lock() {
            commands.take();
        }
        if let Some(engine) = self.engine.lock().ok().and_then(|mut engine| engine.take()) {
            let _ = engine.join();
        }
        if let Some(events) = self.events.lock().ok().and_then(|mut events| events.take()) {
            let _ = events.join();
        }
    }
}

fn run_engine(mut engine: Engine, commands: &Receiver<String>, outbox: &Outbox) {
    while let Ok(line) = commands.recv() {
        let command: Command = match serde_json::from_str(&line) {
            Ok(command) => command,
            Err(parse_error) => {
                outbox.push(&error(None, "invalid-command", parse_error.to_string()));
                continue;
            }
        };
        let (responses, keep_running) = engine.handle(command);
        for response in &responses {
            outbox.push(response);
        }
        if !keep_running {
            break;
        }
    }
    drop(engine);
}

fn host<'a>(handle: jlong) -> Option<&'a Host> {
    // SAFETY: the handle is a Box<Host> from nativeOpen, alive until nativeClose.
    (handle != 0).then(|| unsafe { &*(handle as *const Host) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    match Host::open() {
        Ok(host) => Box::into_raw(Box::new(host)) as jlong,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativeCommand(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    json: JString,
) -> jboolean {
    let Some(host) = host(handle) else {
        return 0;
    };
    let Ok(command) = env.get_string(&json).map(String::from) else {
        return 0;
    };
    jboolean::from(host.send(command))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativePoll(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    timeout_ms: jint,
) -> jstring {
    let Some(host) = host(handle) else {
        return std::ptr::null_mut();
    };
    let timeout = Duration::from_millis(u64::try_from(timeout_ms).unwrap_or(0));
    match host.outbox.pop(timeout) {
        Some(message) => env
            .new_string(message)
            .map(|string| string.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: reclaims the Box created by nativeOpen; Kotlin calls this once.
    let host = unsafe { Box::from_raw(handle as *mut Host) };
    host.close();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativeSetSurface(
    env: JNIEnv,
    _class: JClass,
    surface: JObject,
) {
    if surface.is_null() {
        opennow_streamer_platform::clear_surface();
        return;
    }
    // SAFETY: env and surface are valid for this call; from_surface acquires its own reference.
    let window = unsafe { NativeWindow::from_surface(env.get_raw().cast(), surface.as_raw().cast()) };
    match window {
        Some(window) => opennow_streamer_platform::set_surface(window),
        None => opennow_streamer_platform::clear_surface(),
    }
}

/// One controller's full state, XInput layout: `buttons` uses XInput's bits, sticks are
/// -32768..32767 with up positive, triggers 0..255. `connected` is the bitmap of
/// connected slots (bit n and bit n+8 for slot n, as OpenNOW sends it).
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativeGamepad(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    slot: jint,
    connected: jint,
    buttons: jint,
    left_trigger: jint,
    right_trigger: jint,
    left_x: jint,
    left_y: jint,
    right_x: jint,
    right_y: jint,
) {
    let Some(host) = host(handle) else {
        return;
    };
    let axis = |value: jint| value.clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16;
    let trigger = |value: jint| value.clamp(0, 255) as u8;
    host.input.push(CapturedInput::Gamepad {
        controller_id: slot.clamp(0, 3) as u8,
        bitmap: connected as u16,
        buttons: buttons as u16,
        left_trigger: trigger(left_trigger),
        right_trigger: trigger(right_trigger),
        left_stick_x: axis(left_x),
        left_stick_y: axis(left_y),
        right_stick_x: axis(right_x),
        right_stick_y: axis(right_y),
    });
}

/// A keyboard key as a Windows virtual-key code.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_droiddeck_launcher_stores_gfn_GfnStreamer_nativeKey(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    virtual_key: jint,
    modifiers: jint,
    pressed: jboolean,
) {
    let Some(host) = host(handle) else {
        return;
    };
    host.input.push(CapturedInput::Key {
        virtual_key: virtual_key as u16,
        modifiers: modifiers as u16,
        pressed: pressed != 0,
    });
}
