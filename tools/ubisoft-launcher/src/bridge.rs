//! The file bridge DroidDeck answers Denuvo activations through.

use std::path::Path;
use std::sync::{Condvar, Mutex};
use std::thread;
use std::time::Duration;

pub const INVALIDATE: &str = "denuvo-invalidate";

pub fn publish_invalidation(dir: &Path, account: &str, tries: u32, pause: Duration) -> bool {
    let marker = dir.join(INVALIDATE);
    let part = dir.join(format!("{INVALIDATE}.upc"));
    for attempt in 0..tries {
        if marker.is_file() {
            return true;
        }
        if std::fs::write(&part, account.as_bytes()).is_ok() && std::fs::rename(&part, &marker).is_ok() {
            return true;
        }
        let _ = std::fs::remove_file(&part);
        if attempt + 1 < tries {
            thread::sleep(pause);
        }
    }
    false
}

#[derive(Default)]
pub struct Generation {
    state: Mutex<(u64, usize)>,
    idle: Condvar,
}

impl Generation {
    pub fn current(&self) -> u64 {
        self.state.lock().unwrap().0
    }

    pub fn invalidate<T>(&self, wait: Duration, acknowledge: impl FnOnce() -> T) -> T {
        let mut state = self.state.lock().unwrap();
        state.0 += 1;
        let _ = self.idle.wait_timeout_while(state, wait, |s| s.1 > 0).unwrap();
        acknowledge()
    }

    pub fn serve<T>(&self, seen: u64, reply: impl FnOnce() -> T) -> Option<T> {
        {
            let mut state = self.state.lock().unwrap();
            if state.0 != seen {
                return None;
            }
            state.1 += 1;
        }
        let out = reply();
        self.state.lock().unwrap().1 -= 1;
        self.idle.notify_all();
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::Instant;

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("upc-bridge-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn an_invalidation_is_published_as_the_marker() {
        let d = dir("publish");
        assert!(publish_invalidation(&d, "user-a", 1, Duration::ZERO));
        assert_eq!(std::fs::read(d.join(INVALIDATE)).unwrap(), b"user-a");
        assert!(!d.join("denuvo-invalidate.upc").exists());
    }

    #[test]
    fn a_marker_naming_its_account_is_kept() {
        let d = dir("named");
        std::fs::write(d.join(INVALIDATE), b"user-a").unwrap();
        assert!(publish_invalidation(&d, "user-b", 1, Duration::ZERO));
        assert_eq!(std::fs::read(d.join(INVALIDATE)).unwrap(), b"user-a");
    }

    #[test]
    fn a_marker_that_cannot_be_written_is_reported_after_every_try() {
        let d = dir("missing").join("gone");
        let started = Instant::now();
        assert!(!publish_invalidation(&d, "user-a", 3, Duration::from_millis(20)));
        assert!(started.elapsed() >= Duration::from_millis(40));
        assert!(!d.join(INVALIDATE).exists());
    }

    #[test]
    fn a_marker_written_while_retrying_counts() {
        let d = dir("late").join("bridge");
        let later = d.clone();
        let maker = thread::spawn(move || {
            thread::sleep(Duration::from_millis(30));
            std::fs::create_dir_all(later).unwrap();
        });
        assert!(publish_invalidation(&d, "user-a", 50, Duration::from_millis(10)));
        maker.join().unwrap();
        assert!(d.join(INVALIDATE).is_file());
    }

    #[test]
    fn a_kept_token_asked_for_before_an_invalidation_is_not_served_after_it() {
        let g = Generation::default();
        let seen = g.current();
        assert_eq!(g.invalidate(Duration::from_secs(1), || "acknowledged"), "acknowledged");
        assert_eq!(g.serve(seen, || "token"), None);
        assert_eq!(g.serve(g.current(), || "token"), Some("token"));
    }

    #[test]
    fn a_reply_being_served_holds_the_acknowledgement_back() {
        let g = Arc::new(Generation::default());
        let order = Arc::new(Mutex::new(Vec::new()));
        let seen = g.current();
        let (g2, o2) = (g.clone(), order.clone());
        let served = g.serve(seen, || {
            let ack = thread::spawn(move || g2.invalidate(Duration::from_secs(5), || o2.lock().unwrap().push("acknowledged")));
            thread::sleep(Duration::from_millis(50));
            order.lock().unwrap().push("token");
            ack
        });
        served.unwrap().join().unwrap();
        assert_eq!(*order.lock().unwrap(), vec!["token", "acknowledged"]);
    }

    #[test]
    fn a_reply_that_stalls_never_blocks_other_connections_or_the_acknowledgement_for_long() {
        let g = Arc::new(Generation::default());
        let seen = g.current();
        let (entered, inside) = std::sync::mpsc::channel();
        let (release, held) = std::sync::mpsc::channel::<()>();
        let stalled = {
            let g = g.clone();
            thread::spawn(move || g.serve(seen, move || {
                entered.send(()).unwrap();
                let _ = held.recv();
            }))
        };
        inside.recv().unwrap();
        let started = Instant::now();
        assert_eq!(g.current(), seen);
        assert_eq!(g.serve(seen, || "other"), Some("other"));
        assert!(started.elapsed() < Duration::from_millis(200));
        assert_eq!(g.invalidate(Duration::from_millis(100), || "acknowledged"), "acknowledged");
        assert!(started.elapsed() < Duration::from_millis(450));
        assert_eq!(g.serve(seen, || "late"), None);
        release.send(()).unwrap();
        assert!(stalled.join().unwrap().is_some());
    }
}
