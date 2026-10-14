pub const BACKGROUND_NICE: i32 = 10;

pub fn background() {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    unsafe {
        let tid = libc::gettid();
        let _ = libc::setpriority(libc::PRIO_PROCESS, tid as libc::id_t, BACKGROUND_NICE);
    }
}

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
