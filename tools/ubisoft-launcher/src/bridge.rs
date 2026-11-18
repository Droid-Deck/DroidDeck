//! The file bridge DroidDeck answers Denuvo activations through.

use std::path::Path;
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

pub const INVALIDATE: &str = "denuvo-invalidate";

/// Publishes the marker that makes DroidDeck drop the kept game token. A marker already there
/// stands for this invalidation too, and may name the account it belongs to.
pub fn publish_invalidation(dir: &Path, tries: u32, pause: Duration) -> bool {
    let marker = dir.join(INVALIDATE);
    let part = dir.join(format!("{INVALIDATE}.upc"));
    for attempt in 0..tries {
        if marker.is_file() {
            return true;
        }
        if std::fs::write(&part, b"").is_ok() && std::fs::rename(&part, &marker).is_ok() {
            return true;
        }
        let _ = std::fs::remove_file(&part);
        if attempt + 1 < tries {
            thread::sleep(pause);
        }
    }
    false
}

/// Counts acknowledged invalidations, so a kept token asked for before one is never served after it.
#[derive(Default)]
pub struct Generation(Mutex<u64>);

impl Generation {
    pub fn current(&self) -> u64 {
        *self.0.lock().unwrap()
    }

    pub fn invalidate<T>(&self, acknowledge: impl FnOnce() -> T) -> T {
        let mut g = self.0.lock().unwrap();
        *g += 1;
        acknowledge()
    }

    pub fn serve<T>(&self, seen: u64, reply: impl FnOnce() -> T) -> Option<T> {
        let g = self.0.lock().unwrap();
        (*g == seen).then(reply)
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
        assert!(publish_invalidation(&d, 1, Duration::ZERO));
        assert!(d.join(INVALIDATE).is_file());
        assert!(!d.join("denuvo-invalidate.upc").exists());
    }

    #[test]
    fn a_marker_naming_its_account_is_kept() {
        let d = dir("named");
        std::fs::write(d.join(INVALIDATE), b"user-a").unwrap();
        assert!(publish_invalidation(&d, 1, Duration::ZERO));
        assert_eq!(std::fs::read(d.join(INVALIDATE)).unwrap(), b"user-a");
    }

    #[test]
    fn a_marker_that_cannot_be_written_is_reported_after_every_try() {
        let d = dir("missing").join("gone");
        let started = Instant::now();
        assert!(!publish_invalidation(&d, 3, Duration::from_millis(20)));
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
        assert!(publish_invalidation(&d, 50, Duration::from_millis(10)));
        maker.join().unwrap();
        assert!(d.join(INVALIDATE).is_file());
    }

    #[test]
    fn a_kept_token_asked_for_before_an_invalidation_is_not_served_after_it() {
        let g = Generation::default();
        let seen = g.current();
        assert_eq!(g.invalidate(|| "acknowledged"), "acknowledged");
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
            let ack = thread::spawn(move || g2.invalidate(|| o2.lock().unwrap().push("acknowledged")));
            thread::sleep(Duration::from_millis(50));
            order.lock().unwrap().push("token");
            ack
        });
        served.unwrap().join().unwrap();
        assert_eq!(*order.lock().unwrap(), vec!["token", "acknowledged"]);
    }
}
