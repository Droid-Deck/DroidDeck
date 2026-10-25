//! The Surface the app draws the stream on. The player activity sets it when its
//! SurfaceView is created and clears it when the view is destroyed; the video decoder
//! picks up a change at its next frame.

use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use ndk::native_window::NativeWindow;

static SURFACE: Mutex<Option<NativeWindow>> = Mutex::new(None);
static GENERATION: AtomicU64 = AtomicU64::new(0);

pub fn set_surface(window: NativeWindow) {
    if let Ok(mut surface) = SURFACE.lock() {
        *surface = Some(window);
        GENERATION.fetch_add(1, Ordering::AcqRel);
    }
}

pub fn clear_surface() {
    if let Ok(mut surface) = SURFACE.lock() {
        *surface = None;
        GENERATION.fetch_add(1, Ordering::AcqRel);
    }
}

pub(crate) fn generation() -> u64 {
    GENERATION.load(Ordering::Acquire)
}

pub(crate) fn current() -> Option<NativeWindow> {
    SURFACE.lock().ok().and_then(|surface| surface.clone())
}
