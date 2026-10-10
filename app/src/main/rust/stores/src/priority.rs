//! Background priority for every thread the store engines create.
//!
//! A download keeps several threads busy at once (the run's worker, the tokio runtime and its
//! blocking pool for DNS, the inflate/hash/write pool, the throughput reporter, GOG's verify pass,
//! the Epic assembler). At the default nice 0 they compete as equals with the app's UI and input
//! threads, and on the device the menus' animations and controller navigation slowed visibly for
//! as long as a store download ran. At nice +10 the scheduler hands them only what the UI leaves,
//! which for a download bound by network and storage costs next to nothing.
//!
//! Linux applies `setpriority(PRIO_PROCESS, tid, …)` to the one thread `tid`, not the process, and
//! a new thread inherits its creator's nice value; every thread is still lowered explicitly at its
//! start so the guarantee does not depend on who spawned it. Only threads this library creates are
//! touched: the JNI calling thread belongs to the app and keeps its priority.

/// The nice value store-engine threads run at.
pub const BACKGROUND_NICE: i32 = 10;

/// Lower the calling thread to [`BACKGROUND_NICE`]. Best effort: a refusal leaves the thread at
/// its current priority and the download runs as before.
pub fn background() {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    unsafe {
        let tid = libc::gettid();
        let _ = libc::setpriority(libc::PRIO_PROCESS, tid as libc::id_t, BACKGROUND_NICE);
    }
}

/// The calling thread's nice value as a log field, e.g. `priority=nice10`.
pub fn log_field() -> String {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let nice = unsafe { libc::getpriority(libc::PRIO_PROCESS, libc::gettid() as libc::id_t) };
        format!("priority=nice{nice}")
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        "priority=default".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_lowered_thread_reports_nice10() {
        let field = std::thread::spawn(|| {
            background();
            log_field()
        })
        .join()
        .unwrap();
        assert_eq!(field, "priority=nice10");
    }
}
