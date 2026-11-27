use std::fs::{self, File, OpenOptions};
use std::io;
use std::os::unix::fs::{FileExt, MetadataExt, OpenOptionsExt};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

pub const FILL_STEP: usize = 4 * 1024 * 1024;
pub const DIRECT_GAP: u64 = 4 * 1024 * 1024;
const PROBE_GAP: u64 = 1024 * 1024;
static PROBE_SEQ: AtomicU64 = AtomicU64::new(0);

const PROBE_PREFIX: &str = ".droiddeck-hole-probe-";

pub fn holes_supported(dir: &Path) -> bool {
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let stale = entry.file_name().to_string_lossy().starts_with(PROBE_PREFIX)
                && entry.file_type().map(|t| t.is_file()).unwrap_or(false);
            if stale {
                let _ = fs::remove_file(entry.path());
            }
        }
    }
    let probe = dir.join(format!(
        "{PROBE_PREFIX}{}-{}",
        std::process::id(),
        PROBE_SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    let Ok(file) = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&probe)
    else {
        return true;
    };
    let written = file.write_all_at(&[0u8], PROBE_GAP - 1);
    drop(file);
    let measured = written.and_then(|_| fs::symlink_metadata(&probe));
    let _ = fs::remove_file(&probe);
    measured
        .map(|meta| meta.blocks().saturating_mul(512) < PROBE_GAP)
        .unwrap_or(true)
}

pub struct GrowFront {
    holes: bool,
    size: AtomicU64,
    known: AtomicBool,
    fill: Mutex<()>,
}

impl GrowFront {
    pub fn new(holes: bool) -> Self {
        GrowFront {
            holes,
            size: AtomicU64::new(0),
            known: AtomicBool::new(false),
            fill: Mutex::new(()),
        }
    }

    pub fn reset(&self) {
        let _guard = self.fill.lock().unwrap_or_else(|e| e.into_inner());
        self.known.store(false, Ordering::Release);
        self.size.store(0, Ordering::Release);
    }

    pub fn write_all_at(
        &self,
        file: &File,
        offset: u64,
        data: &[u8],
        cancel: &AtomicBool,
    ) -> io::Result<()> {
        let end = offset.saturating_add(data.len() as u64);
        if self.holes
            || (self.known.load(Ordering::Acquire) && end <= self.size.load(Ordering::Acquire))
        {
            return file.write_all_at(data, offset);
        }
        let _guard = self.fill.lock().unwrap_or_else(|e| e.into_inner());
        let mut size = self.current(file)?;
        if offset > size.saturating_add(DIRECT_GAP) {
            self.fill_to(file, &mut size, offset - DIRECT_GAP, cancel)?;
        }
        if let Err(err) = file.write_all_at(data, offset) {
            self.known.store(false, Ordering::Release);
            return Err(err);
        }
        self.publish(size.max(end));
        Ok(())
    }

    pub fn extend_to(&self, file: &File, len: u64, cancel: &AtomicBool) -> io::Result<()> {
        if self.holes {
            return file.set_len(len);
        }
        let _guard = self.fill.lock().unwrap_or_else(|e| e.into_inner());
        let mut size = self.current(file)?;
        if size < len {
            self.fill_to(file, &mut size, len, cancel)?;
        }
        Ok(())
    }

    fn current(&self, file: &File) -> io::Result<u64> {
        if self.known.load(Ordering::Acquire) {
            Ok(self.size.load(Ordering::Acquire))
        } else {
            Ok(file.metadata()?.len())
        }
    }

    fn fill_to(
        &self,
        file: &File,
        size: &mut u64,
        target: u64,
        cancel: &AtomicBool,
    ) -> io::Result<()> {
        let zeros = vec![0u8; FILL_STEP];
        while *size < target {
            if cancel.load(Ordering::Relaxed) {
                self.publish(*size);
                return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
            }
            let step = ((target - *size) as usize).min(FILL_STEP);
            if let Err(err) = file.write_all_at(&zeros[..step], *size) {
                self.known.store(false, Ordering::Release);
                return Err(err);
            }
            *size += step as u64;
            self.publish(*size);
        }
        Ok(())
    }

    fn publish(&self, size: u64) {
        self.size.store(size, Ordering::Release);
        self.known.store(true, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn temp_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "grow-write-{tag}-{}-{}",
            std::process::id(),
            PROBE_SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn open(path: &Path) -> File {
        OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(path)
            .unwrap()
    }

    #[test]
    fn far_write_fills_the_gap_in_bounded_steps_and_keeps_data() {
        let dir = temp_dir("far");
        let path = dir.join("f.bin");
        let file = open(&path);
        let front = GrowFront::new(false);
        let cancel = AtomicBool::new(false);
        front.write_all_at(&file, 0, b"head", &cancel).unwrap();
        let far = 3 * FILL_STEP as u64 + 123;
        front.write_all_at(&file, far, b"tail", &cancel).unwrap();
        front.write_all_at(&file, 100, b"mid", &cancel).unwrap();
        let bytes = fs::read(&path).unwrap();
        assert_eq!(bytes.len() as u64, far + 4);
        assert_eq!(&bytes[0..4], b"head");
        assert_eq!(&bytes[100..103], b"mid");
        assert_eq!(&bytes[far as usize..], b"tail");
        assert!(bytes[103..far as usize].iter().all(|b| *b == 0));
        fs::remove_file(&path).unwrap();
        fs::remove_dir(&dir).unwrap();
    }

    #[test]
    fn cancel_stops_a_gap_fill_between_steps() {
        let dir = temp_dir("cancel");
        let path = dir.join("f.bin");
        let file = open(&path);
        let front = GrowFront::new(false);
        let cancel = AtomicBool::new(true);
        let err = front
            .write_all_at(&file, 8 * FILL_STEP as u64, b"x", &cancel)
            .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::Interrupted);
        assert_eq!(fs::metadata(&path).unwrap().len(), 0);
        cancel.store(false, Ordering::Relaxed);
        front.write_all_at(&file, 10, b"ok", &cancel).unwrap();
        assert_eq!(&fs::read(&path).unwrap()[10..], b"ok");
        fs::remove_file(&path).unwrap();
        fs::remove_dir(&dir).unwrap();
    }

    #[test]
    fn concurrent_writers_never_lose_data_to_the_fill() {
        let dir = temp_dir("race");
        let path = dir.join("f.bin");
        let file = Arc::new(open(&path));
        let front = Arc::new(GrowFront::new(false));
        let piece = 64 * 1024u64;
        let count = 256u64;
        let mut order: Vec<u64> = (0..count).collect();
        order.reverse();
        let lanes: Vec<Vec<u64>> = (0..4)
            .map(|lane| order.iter().copied().filter(|i| i % 4 == lane).collect())
            .collect();
        let handles: Vec<_> = lanes
            .into_iter()
            .map(|lane| {
                let file = Arc::clone(&file);
                let front = Arc::clone(&front);
                std::thread::spawn(move || {
                    let cancel = AtomicBool::new(false);
                    for i in lane {
                        let data = vec![(i % 251) as u8 + 1; piece as usize];
                        front.write_all_at(&file, i * piece, &data, &cancel).unwrap();
                    }
                })
            })
            .collect();
        for handle in handles {
            handle.join().unwrap();
        }
        let bytes = fs::read(&path).unwrap();
        assert_eq!(bytes.len() as u64, piece * count);
        for i in 0..count {
            let start = (i * piece) as usize;
            assert!(bytes[start..start + piece as usize]
                .iter()
                .all(|b| *b == (i % 251) as u8 + 1));
        }
        fs::remove_file(&path).unwrap();
        fs::remove_dir(&dir).unwrap();
    }

    #[test]
    fn extend_to_grows_in_steps_and_never_shrinks() {
        let dir = temp_dir("extend");
        let path = dir.join("f.bin");
        let file = open(&path);
        let front = GrowFront::new(false);
        let cancel = AtomicBool::new(false);
        front.write_all_at(&file, 0, b"data", &cancel).unwrap();
        let len = 2 * FILL_STEP as u64 + 7;
        front.extend_to(&file, len, &cancel).unwrap();
        front.extend_to(&file, 2, &cancel).unwrap();
        let bytes = fs::read(&path).unwrap();
        assert_eq!(bytes.len() as u64, len);
        assert_eq!(&bytes[0..4], b"data");
        assert!(bytes[4..].iter().all(|b| *b == 0));
        fs::remove_file(&path).unwrap();
        fs::remove_dir(&dir).unwrap();
    }

    #[test]
    fn hole_capable_storage_writes_directly() {
        let dir = temp_dir("holes");
        let path = dir.join("f.bin");
        let file = open(&path);
        let front = GrowFront::new(true);
        let cancel = AtomicBool::new(true);
        front
            .write_all_at(&file, 8 * FILL_STEP as u64, b"x", &cancel)
            .unwrap();
        assert_eq!(fs::metadata(&path).unwrap().len(), 8 * FILL_STEP as u64 + 1);
        fs::remove_file(&path).unwrap();
        fs::remove_dir(&dir).unwrap();
    }

    #[test]
    fn probe_leaves_nothing_behind() {
        let dir = temp_dir("probe");
        let _ = holes_supported(&dir);
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
        fs::remove_dir(&dir).unwrap();
    }
}
