use std::fs;
use std::io::Read;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

use sha1::{Digest, Sha1};

use crate::epic::manifest::Sha1 as Hash;

pub enum Check {
    UpToDate,
    Pending { old_size: u64 },
    Cancelled,
}

fn hash_file(path: &Path, cancel: &AtomicBool) -> Option<Option<Hash>> {
    let Ok(mut f) = fs::File::open(path) else { return Some(None) };
    let mut h = Sha1::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        if cancel.load(Ordering::Relaxed) {
            return None;
        }
        match f.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => h.update(&buf[..n]),
            Err(_) => return Some(None),
        }
    }
    Some(Some(h.finalize().into()))
}

pub fn check(dest: &Path, size: u64, sha1: Option<Hash>, cancel: &AtomicBool) -> Check {
    let old_size = match fs::metadata(dest) {
        Ok(m) if m.is_file() => m.len(),
        _ => return Check::Pending { old_size: 0 },
    };
    let Some(expected) = sha1 else {
        return Check::Pending { old_size };
    };
    if old_size != size {
        return Check::Pending { old_size };
    }
    match hash_file(dest, cancel) {
        None => Check::Cancelled,
        Some(Some(got)) if got == expected => Check::UpToDate,
        Some(_) => Check::Pending { old_size },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn up_to_date_needs_size_and_hash() {
        let dir = std::env::temp_dir().join(format!("dd-epic-delta-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let f = dir.join("a.bin");
        fs::write(&f, b"hello").unwrap();
        let good: Hash = Sha1::digest(b"hello").into();
        let cancel = AtomicBool::new(false);
        assert!(matches!(check(&f, 5, Some(good), &cancel), Check::UpToDate));
        assert!(matches!(check(&f, 5, None, &cancel), Check::Pending { old_size: 5 }));
        assert!(matches!(check(&f, 6, Some(good), &cancel), Check::Pending { old_size: 5 }));
        assert!(matches!(check(&f, 5, Some([0; 20]), &cancel), Check::Pending { .. }));
        assert!(matches!(check(&dir.join("missing"), 5, Some(good), &cancel), Check::Pending { old_size: 0 }));
        cancel.store(true, Ordering::Relaxed);
        assert!(matches!(check(&f, 5, Some(good), &cancel), Check::Cancelled));
        let _ = fs::remove_dir_all(&dir);
    }
}
