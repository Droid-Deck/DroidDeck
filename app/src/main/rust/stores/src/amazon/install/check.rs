use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use sha2::{Digest, Sha256};

use crate::amazon::manifest::{GameFile, TEMP_SUFFIX};

#[derive(Debug, PartialEq, Eq)]
pub enum State {
    Complete,
    Pending,
    Cancelled,
}

pub fn sha256_of(path: &Path, cancel: &AtomicBool) -> Result<Option<[u8; 32]>, ()> {
    let Ok(mut f) = fs::File::open(path) else { return Ok(None) };
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        if cancel.load(Ordering::Relaxed) {
            return Err(());
        }
        match f.read(&mut buf) {
            Ok(0) => return Ok(Some(hasher.finalize().into())),
            Ok(n) => hasher.update(&buf[..n]),
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return Ok(None),
        }
    }
}

pub fn state(dest: &Path, file: &GameFile, cancel: &AtomicBool) -> State {
    match fs::symlink_metadata(dest) {
        Ok(m) if m.is_file() && m.len() == file.size => {}
        _ => return State::Pending,
    }
    let Some(expected) = file.sha256() else { return State::Complete };
    match sha256_of(dest, cancel) {
        Err(()) => State::Cancelled,
        Ok(Some(got)) if got == expected => State::Complete,
        Ok(_) => State::Pending,
    }
}

pub fn temp_path(dest: &Path) -> PathBuf {
    let mut name = dest.as_os_str().to_owned();
    name.push(TEMP_SUFFIX);
    PathBuf::from(name)
}

pub fn remove_stray_temps(game_dir: &Path, files: &[GameFile]) -> usize {
    files
        .iter()
        .map(|f| temp_path(&game_dir.join(&f.path)))
        .filter(|t| fs::symlink_metadata(t).map(|m| m.is_file() || m.file_type().is_symlink()).unwrap_or(false))
        .filter(|t| fs::remove_file(t).is_ok())
        .count()
}

pub fn free_bytes(path: &Path) -> i64 {
    let mut probe = Some(path);
    while let Some(p) = probe {
        if p.exists() {
            return available(p);
        }
        probe = p.parent();
    }
    -1
}

#[cfg(unix)]
fn available(path: &Path) -> i64 {
    use std::os::unix::ffi::OsStrExt;
    let Ok(c) = std::ffi::CString::new(path.as_os_str().as_bytes()) else { return -1 };
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
        return -1;
    }
    (st.f_bavail as u64).saturating_mul(st.f_frsize as u64).min(i64::MAX as u64) as i64
}

#[cfg(not(unix))]
fn available(_path: &Path) -> i64 {
    -1
}

pub fn human(bytes: u64) -> String {
    const KIB: f64 = 1024.0;
    let b = bytes as f64;
    if b >= KIB * KIB * KIB {
        format!("{:.1} GB", b / (KIB * KIB * KIB))
    } else if b >= KIB * KIB {
        format!("{:.1} MB", b / (KIB * KIB))
    } else {
        format!("{:.1} KB", b / KIB)
    }
}

pub fn space_problem(needed: u64, free: i64) -> Option<String> {
    if needed > 0 && free >= 0 && (free as u64) < needed {
        Some(format!("Not enough free space: need {}, only {} free", human(needed), human(free as u64)))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(path: &str, size: u64, digest: Vec<u8>) -> GameFile {
        GameFile { path: path.into(), size, algorithm: 0, digest }
    }

    #[test]
    fn complete_needs_size_and_hash() {
        let dir = std::env::temp_dir().join(format!("dd-amazon-check-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let p = dir.join("a.bin");
        fs::write(&p, b"hello").unwrap();
        let good = Sha256::digest(b"hello").to_vec();
        let cancel = AtomicBool::new(false);
        assert_eq!(state(&p, &file("a.bin", 5, good.clone()), &cancel), State::Complete);
        assert_eq!(state(&p, &file("a.bin", 5, Sha256::digest(b"HELLO").to_vec()), &cancel), State::Pending);
        assert_eq!(state(&p, &file("a.bin", 6, good.clone()), &cancel), State::Pending);
        let other = GameFile { path: "a.bin".into(), size: 5, algorithm: 3, digest: vec![1, 2] };
        assert_eq!(state(&p, &other, &cancel), State::Complete, "no SHA-256: size decides");
        assert_eq!(state(&dir, &file("x", 0, vec![]), &cancel), State::Pending, "a directory is not a file");
        assert_eq!(state(&dir.join("missing"), &file("m", 0, vec![]), &cancel), State::Pending);
        cancel.store(true, Ordering::Relaxed);
        assert_eq!(state(&p, &file("a.bin", 5, good), &cancel), State::Cancelled);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stray_temps_are_removed_only_for_manifest_files() {
        let dir = std::env::temp_dir().join(format!("dd-amazon-temps-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("sub")).unwrap();
        fs::write(dir.join("sub/a.bin.tmp"), b"x").unwrap();
        fs::write(dir.join("save.tmp"), b"keep").unwrap();
        let files = vec![file("sub/a.bin", 1, vec![0; 32]), file("b.bin", 1, vec![0; 32])];
        assert_eq!(temp_path(&dir.join("sub/a.bin")), dir.join("sub/a.bin.tmp"));
        assert_eq!(remove_stray_temps(&dir, &files), 1);
        assert!(!dir.join("sub/a.bin.tmp").exists());
        assert!(dir.join("save.tmp").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn space_messages() {
        assert_eq!(space_problem(0, 0), None);
        assert_eq!(space_problem(10, -1), None);
        assert_eq!(space_problem(10, 10), None);
        assert_eq!(
            space_problem(3 * 1024 * 1024 * 1024, 512 * 1024 * 1024).unwrap(),
            "Not enough free space: need 3.0 GB, only 512.0 MB free"
        );
        assert_eq!(space_problem(2048, 0).unwrap(), "Not enough free space: need 2.0 KB, only 0.0 KB free");
        assert!(free_bytes(&std::env::temp_dir()) != 0);
    }
}
