//! Writes one depot's files in place under the target folder, the way the Steam client lays them
//! out: no temp files, no space reservation (`fallocate`/zero-fill are slow on Android's FUSE
//! storage), each file trimmed to its exact size with `set_len` once its last chunk lands.
//!
//! Chunk bodies come from the shared fetch core (`crate::fetch_core`), which owns the adaptive
//! request window, host rotation and retries; this module is the sink (decrypt, decompress,
//! Adler-32 check, positioned write). A chunk that appears several times in the manifest is
//! fetched once and written to every place it belongs. On resume, chunks already on disk are
//! read back and checked before anything is fetched.

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};
use crate::steam::cdn;
use crate::steam::content_manifest::{ChunkData, ContentManifest};
use crate::steam::depot_chunk::{process_depot_chunk, steam_adler_hash};
use crate::steam::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const DEPOT_FILE_FLAG_EXECUTABLE: u32 = 32;
pub const DEPOT_FILE_FLAG_DIRECTORY: u32 = 64;
/// Chunks per fetch-core run. Every chunk carries one URL per CDN host, so batches bound memory on
/// very large depots.
pub const FETCH_BATCH_ITEMS: usize = 8192;
/// Above this many open files a written file's handle is closed and reopened on its next chunk.
pub const MAX_OPEN_FILES: usize = 256;
pub const CHUNK_TIMEOUT: Duration = Duration::from_secs(60);
const FREE_SPACE_MARGIN_BYTES: u64 = 64 * 1024 * 1024;

pub struct DepotWriteRequest<'a> {
    /// Filenames already decrypted (see `ContentManifest::decrypt_filenames`).
    pub manifest: &'a ContentManifest,
    pub depot_key: &'a [u8],
    pub servers: &'a [CContentServerDirectoryServerInfo],
    pub target_dir: &'a Path,
    pub ca_bundle_path: &'a str,
    pub max_workers: usize,
    pub process_workers: usize,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DepotWriteOutcome {
    /// Bytes of the depot that are complete on disk (verified + written).
    pub bytes_done: u64,
    /// Of those, bytes that were already correct on disk.
    pub bytes_verified: u64,
    pub error: Option<String>,
    pub cancelled: bool,
}

impl DepotWriteOutcome {
    pub fn ok(&self) -> bool {
        self.error.is_none() && !self.cancelled
    }

    fn fail(error: impl Into<String>) -> Self {
        Self {
            error: Some(error.into()),
            ..Default::default()
        }
    }
}

pub fn path_is_safe(rel: &str) -> bool {
    if rel.is_empty() || rel.starts_with('/') || rel.starts_with('\\') {
        return false;
    }
    let path = Path::new(rel);
    !path.is_absolute()
        && path
            .components()
            .all(|c| matches!(c, Component::Normal(_) | Component::CurDir))
}

pub fn depot_total_bytes(manifest: &ContentManifest) -> u64 {
    manifest
        .files
        .iter()
        .filter(|f| is_regular(f.flags, &f.linktarget))
        .map(|f| f.size)
        .sum()
}

fn is_regular(flags: u32, linktarget: &str) -> bool {
    linktarget.is_empty() && (flags & DEPOT_FILE_FLAG_DIRECTORY) == 0
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Target {
    file_idx: u32,
    offset: u64,
}

#[derive(Clone, Debug)]
struct UniqueChunk {
    sha: Vec<u8>,
    crc: u32,
    cb_original: u32,
    cb_compressed: u32,
    targets: Vec<Target>,
}

struct FileSlot {
    handle: Mutex<Option<Arc<File>>>,
    remaining: AtomicUsize,
    size: u64,
    path: PathBuf,
    mode: u32,
}

struct DepotFiles {
    slots: Vec<FileSlot>,
    open_count: AtomicUsize,
}

impl DepotFiles {
    fn acquire(&self, file_idx: usize) -> Result<Arc<File>, String> {
        let slot = &self.slots[file_idx];
        let mut handle = slot.handle.lock().expect("file slot poisoned");
        if let Some(file) = handle.as_ref() {
            return Ok(Arc::clone(file));
        }
        make_parent_dirs(&slot.path)?;
        let file = OpenOptions::new()
            .create(true)
            .write(true)
            .read(true)
            .truncate(false)
            .open(&slot.path)
            .map_err(|err| format!("open '{}': {err}", slot.path.display()))?;
        set_file_mode(&slot.path, slot.mode)?;
        let file = Arc::new(file);
        *handle = Some(Arc::clone(&file));
        self.open_count.fetch_add(1, Ordering::Relaxed);
        Ok(file)
    }

    fn release(&self, file_idx: usize) {
        let slot = &self.slots[file_idx];
        if slot
            .handle
            .lock()
            .expect("file slot poisoned")
            .take()
            .is_some()
        {
            self.open_count.fetch_sub(1, Ordering::Relaxed);
        }
    }

    fn write(&self, file_idx: usize, offset: u64, data: &[u8]) -> Result<(), String> {
        let file = self.acquire(file_idx)?;
        pwrite_all_at(&file, offset, data).map_err(|err| {
            format!(
                "write '{}' at {offset}: {err}",
                self.slots[file_idx].path.display()
            )
        })?;
        let slot = &self.slots[file_idx];
        if slot.remaining.fetch_sub(1, Ordering::AcqRel) == 1 {
            finalize(&file, slot)?;
            drop(file);
            self.release(file_idx);
        } else if self.open_count.load(Ordering::Relaxed) > MAX_OPEN_FILES {
            drop(file);
            self.release(file_idx);
        }
        Ok(())
    }

    /// Files with nothing left to fetch (zero chunks, or every chunk already correct on disk).
    fn finalize_untouched(&self, idx: usize) -> Result<(), String> {
        let slot = &self.slots[idx];
        make_parent_dirs(&slot.path)?;
        let file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(&slot.path)
            .map_err(|err| format!("open '{}': {err}", slot.path.display()))?;
        set_file_mode(&slot.path, slot.mode)?;
        let current = file.metadata().map(|m| m.len()).unwrap_or(u64::MAX);
        if current != slot.size {
            file.set_len(slot.size)
                .map_err(|err| format!("set_len '{}': {err}", slot.path.display()))?;
        }
        Ok(())
    }
}

fn finalize(file: &File, slot: &FileSlot) -> Result<(), String> {
    file.set_len(slot.size)
        .map_err(|err| format!("set_len '{}': {err}", slot.path.display()))?;
    file.sync_all()
        .map_err(|err| format!("sync '{}': {err}", slot.path.display()))
}

struct ChunkSink<'a> {
    chunks: &'a [UniqueChunk],
    files: &'a DepotFiles,
    depot_key: &'a [u8],
}

impl FetchSink for ChunkSink<'_> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        let Some(chunk) = self.chunks.get(item.id as usize) else {
            return Err(SinkError::Fatal("bad chunk index".to_string()));
        };
        let processed = process_depot_chunk(&body, self.depot_key, chunk.crc, chunk.cb_original);
        if !processed.ok() {
            return Err(SinkError::Retry(processed.error));
        }
        let mut credited = 0u64;
        for target in &chunk.targets {
            self.files
                .write(target.file_idx as usize, target.offset, &processed.data)
                .map_err(SinkError::Fatal)?;
            credited += processed.data.len() as u64;
        }
        Ok(credited)
    }
}

struct Prepared {
    files: DepotFiles,
    chunks: Vec<UniqueChunk>,
    bytes_verified: u64,
    total: u64,
}

/// Layout, free-space check, resume verify pass, and the de-duplicated chunk list still to fetch.
fn prepare(
    request: &DepotWriteRequest<'_>,
    cancel: &AtomicBool,
) -> Result<Prepared, DepotWriteOutcome> {
    let manifest = request.manifest;
    if manifest.metadata.filenames_encrypted {
        return Err(DepotWriteOutcome::fail(
            "manifest filenames are still encrypted",
        ));
    }
    if request.depot_key.len() != 32 {
        return Err(DepotWriteOutcome::fail("bad depot key length"));
    }
    if let Some(bad) = manifest.files.iter().find(|f| !path_is_safe(&f.filename)) {
        return Err(DepotWriteOutcome::fail(format!(
            "unsafe path '{}'",
            bad.filename
        )));
    }
    if let Err(err) = fs::create_dir_all(request.target_dir) {
        return Err(DepotWriteOutcome::fail(format!(
            "mkdir '{}': {err}",
            request.target_dir.display()
        )));
    }

    let mut slots = Vec::with_capacity(manifest.files.len());
    let mut existing = Vec::with_capacity(manifest.files.len());
    for file in &manifest.files {
        let path = request.target_dir.join(&file.filename);
        let regular = is_regular(file.flags, &file.linktarget);
        let len = if regular {
            fs::metadata(&path).map(|m| m.len()).unwrap_or(0)
        } else {
            0
        };
        existing.push(len);
        slots.push(FileSlot {
            handle: Mutex::new(None),
            remaining: AtomicUsize::new(0),
            size: file.size,
            path,
            mode: if (file.flags & DEPOT_FILE_FLAG_EXECUTABLE) != 0 {
                0o755
            } else {
                0o644
            },
        });
    }

    let total = depot_total_bytes(manifest);
    let present: u64 = manifest
        .files
        .iter()
        .zip(&existing)
        .map(|(f, len)| (*len).min(f.size))
        .sum();
    let needed = total.saturating_sub(present);
    if let Some(available) = available_space_bytes(request.target_dir) {
        if available > 0 && available.saturating_add(FREE_SPACE_MARGIN_BYTES) < needed {
            return Err(DepotWriteOutcome::fail(format!(
                "not_enough_space: need {} MiB, have {} MiB",
                needed / (1024 * 1024),
                available / (1024 * 1024)
            )));
        }
    }

    for file in &manifest.files {
        let path = request.target_dir.join(&file.filename);
        let result = if !file.linktarget.is_empty() {
            create_symlink(&path, &file.linktarget)
        } else if (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0 {
            fs::create_dir_all(&path).map_err(|err| format!("mkdir '{}': {err}", path.display()))
        } else {
            Ok(())
        };
        if let Err(err) = result {
            return Err(DepotWriteOutcome::fail(err));
        }
    }

    let verified = verify_existing(manifest, &slots, &existing, request.process_workers, cancel);
    if cancel.load(Ordering::Relaxed) {
        return Err(DepotWriteOutcome {
            cancelled: true,
            ..Default::default()
        });
    }
    let mut bytes_verified = 0u64;
    let mut chunks: Vec<UniqueChunk> = Vec::new();
    let mut by_sha: HashMap<Vec<u8>, usize> = HashMap::new();
    for (file_idx, file) in manifest.files.iter().enumerate() {
        if !is_regular(file.flags, &file.linktarget) {
            continue;
        }
        let mut remaining = 0usize;
        for (chunk_idx, chunk) in file.chunks.iter().enumerate() {
            if verified[file_idx].get(chunk_idx).copied().unwrap_or(false) {
                bytes_verified += chunk.cb_original as u64;
                continue;
            }
            remaining += 1;
            let target = Target {
                file_idx: file_idx as u32,
                offset: chunk.offset,
            };
            match by_sha.get(&chunk.sha) {
                Some(&idx) => chunks[idx].targets.push(target),
                None => {
                    by_sha.insert(chunk.sha.clone(), chunks.len());
                    chunks.push(UniqueChunk {
                        sha: chunk.sha.clone(),
                        crc: chunk.crc,
                        cb_original: chunk.cb_original,
                        cb_compressed: chunk.cb_compressed,
                        targets: vec![target],
                    });
                }
            }
        }
        slots[file_idx]
            .remaining
            .store(remaining, Ordering::Relaxed);
    }
    drop(by_sha);

    let files = DepotFiles {
        slots,
        open_count: AtomicUsize::new(0),
    };
    for (idx, file) in manifest.files.iter().enumerate() {
        if is_regular(file.flags, &file.linktarget)
            && files.slots[idx].remaining.load(Ordering::Relaxed) == 0
        {
            if let Err(err) = files.finalize_untouched(idx) {
                return Err(DepotWriteOutcome::fail(err));
            }
        }
    }
    Ok(Prepared {
        files,
        chunks,
        bytes_verified,
        total,
    })
}

/// Writes (or resumes) one depot. `progress` gets the depot's completed bytes; `log` gets the
/// fetch core's lines.
pub fn write_depot(
    request: &DepotWriteRequest<'_>,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> DepotWriteOutcome {
    let manifest = request.manifest;
    let servers = cdn::usable_servers(request.servers);
    if servers.is_empty() {
        return DepotWriteOutcome::fail("no usable CDN server");
    }
    let Prepared {
        files,
        chunks,
        bytes_verified,
        total,
    } = match prepare(request, cancel) {
        Ok(prepared) => prepared,
        Err(outcome) => return outcome,
    };
    progress(bytes_verified);
    log(&format!(
        "depot={} files={} unique_chunks={} verified_bytes={bytes_verified} total_bytes={total}",
        manifest.metadata.depot_id,
        manifest.files.len(),
        chunks.len()
    ));

    let hosts: Vec<String> = servers.iter().map(cdn::host_key).collect();
    let opts = FetchOptions {
        max_workers: request.max_workers.max(1),
        timeout: CHUNK_TIMEOUT,
        ca_bundle_path: request.ca_bundle_path.to_string(),
        process_workers: request.process_workers.max(1),
        label: format!("steam depot={}", manifest.metadata.depot_id),
        ..Default::default()
    };
    let sink = ChunkSink {
        chunks: &chunks,
        files: &files,
        depot_key: request.depot_key,
    };
    let depot_id = manifest.metadata.depot_id;
    let done_before = AtomicU64::new(bytes_verified);
    let mut start = 0usize;
    while start < chunks.len() {
        if cancel.load(Ordering::Relaxed) {
            return DepotWriteOutcome {
                bytes_done: done_before.load(Ordering::Relaxed),
                bytes_verified,
                cancelled: true,
                error: None,
            };
        }
        let end = (start + FETCH_BATCH_ITEMS).min(chunks.len());
        let mut items = Vec::with_capacity(end - start);
        for (idx, chunk) in chunks.iter().enumerate().take(end).skip(start) {
            let mut urls = Vec::with_capacity(servers.len());
            for server in &servers {
                match cdn::build_chunk_url(server, depot_id, &chunk.sha) {
                    Ok(url) => urls.push(url),
                    Err(err) => return DepotWriteOutcome::fail(err),
                }
            }
            items.push(FetchItem {
                id: idx as u64,
                urls,
                reserve: chunk.cb_compressed as u64,
                range: None,
            });
        }
        let base = done_before.load(Ordering::Relaxed);
        let batch_progress = |credited: u64, _items: u64| progress(base + credited);
        let outcome = run_fetch(items, &hosts, &opts, &sink, cancel, &batch_progress, log);
        done_before.fetch_add(outcome.bytes_credited, Ordering::Relaxed);
        if outcome.cancelled {
            return DepotWriteOutcome {
                bytes_done: done_before.load(Ordering::Relaxed),
                bytes_verified,
                cancelled: true,
                error: None,
            };
        }
        if let Some(error) = outcome.error {
            return DepotWriteOutcome {
                bytes_done: done_before.load(Ordering::Relaxed),
                bytes_verified,
                cancelled: false,
                error: Some(error),
            };
        }
        start = end;
    }

    if let Some((idx, _)) = files
        .slots
        .iter()
        .enumerate()
        .find(|(_, s)| s.remaining.load(Ordering::Relaxed) != 0)
    {
        return DepotWriteOutcome::fail(format!(
            "file '{}' incomplete after fetch",
            manifest.files[idx].filename
        ));
    }
    DepotWriteOutcome {
        bytes_done: done_before.load(Ordering::Relaxed),
        bytes_verified,
        error: None,
        cancelled: false,
    }
}

/// Per file, per chunk: is the chunk already correct on disk? Only files that already have bytes
/// are read; a fresh install skips this entirely.
fn verify_existing(
    manifest: &ContentManifest,
    slots: &[FileSlot],
    existing: &[u64],
    workers: usize,
    cancel: &AtomicBool,
) -> Vec<Vec<bool>> {
    let mut out: Vec<Vec<bool>> = manifest
        .files
        .iter()
        .map(|f| vec![false; f.chunks.len()])
        .collect();
    let todo: Vec<usize> = (0..manifest.files.len())
        .filter(|&i| existing[i] > 0 && !manifest.files[i].chunks.is_empty())
        .collect();
    if todo.is_empty() {
        return out;
    }
    let queue = Mutex::new(todo.into_iter());
    let results: Mutex<Vec<(usize, Vec<bool>)>> = Mutex::new(Vec::new());
    std::thread::scope(|scope| {
        for _ in 0..workers.clamp(1, 8) {
            scope.spawn(|| {
                crate::priority::background();
                loop {
                    if cancel.load(Ordering::Relaxed) {
                        return;
                    }
                    let next = queue.lock().expect("verify queue poisoned").next();
                    let Some(idx) = next else {
                        return;
                    };
                    let Ok(file) = File::open(&slots[idx].path) else {
                        continue;
                    };
                    let flags = manifest.files[idx]
                        .chunks
                        .iter()
                        .map(|chunk| existing_chunk_matches(&file, existing[idx], chunk))
                        .collect();
                    results
                        .lock()
                        .expect("verify results poisoned")
                        .push((idx, flags));
                }
            });
        }
    });
    for (idx, flags) in results.into_inner().expect("verify results poisoned") {
        out[idx] = flags;
    }
    out
}

pub fn existing_chunk_matches(file: &File, file_len: u64, chunk: &ChunkData) -> bool {
    if chunk.cb_original == 0 {
        return false;
    }
    let end = chunk.offset.saturating_add(chunk.cb_original as u64);
    if file_len < end {
        return false;
    }
    let mut buf = vec![0u8; chunk.cb_original as usize];
    if pread_exact_at(file, chunk.offset, &mut buf).is_err() {
        return false;
    }
    steam_adler_hash(&buf) == chunk.crc
}

fn make_parent_dirs(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|err| format!("mkdir '{}': {err}", parent.display()))?;
        }
    }
    Ok(())
}

fn create_symlink(path: &Path, target: &str) -> Result<(), String> {
    make_parent_dirs(path)?;
    if fs::symlink_metadata(path).is_ok() {
        fs::remove_file(path).map_err(|err| format!("unlink '{}': {err}", path.display()))?;
    }
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, path)
            .map_err(|err| format!("symlink '{}': {err}", path.display()))
    }
    #[cfg(not(unix))]
    {
        let _ = target;
        Err(format!("symlink '{}': unsupported", path.display()))
    }
}

#[cfg(unix)]
fn set_file_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
        .map_err(|err| format!("chmod '{}': {err}", path.display()))
}

#[cfg(not(unix))]
fn set_file_mode(_path: &Path, _mode: u32) -> Result<(), String> {
    Ok(())
}

#[cfg(unix)]
fn pwrite_all_at(file: &File, offset: u64, data: &[u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.write_all_at(data, offset)
}

#[cfg(unix)]
fn pread_exact_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.read_exact_at(buf, offset)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn available_space_bytes(path: &Path) -> Option<u64> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let c_path = CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c_path.as_ptr(), &mut st) } != 0 {
        return None;
    }
    Some((st.f_bavail as u64).saturating_mul(st.f_frsize as u64))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn available_space_bytes(_path: &Path) -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::content_manifest::FileMapping;

    #[test]
    fn rejects_unsafe_paths() {
        assert!(path_is_safe("bin/game.exe"));
        assert!(path_is_safe("./a"));
        assert!(!path_is_safe(""));
        assert!(!path_is_safe("/etc/passwd"));
        assert!(!path_is_safe("../x"));
        assert!(!path_is_safe("a/../../x"));
        assert!(!path_is_safe("\\x"));
    }

    #[test]
    fn existing_chunk_check_reads_back_and_compares_adler() {
        let dir = std::env::temp_dir().join(format!(
            "droiddeck_steam_verify_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("f.bin");
        fs::write(&path, b"helloworld").unwrap();
        let file = File::open(&path).unwrap();
        let good = ChunkData {
            crc: steam_adler_hash(b"world"),
            offset: 5,
            cb_original: 5,
            ..Default::default()
        };
        let bad = ChunkData {
            crc: steam_adler_hash(b"xxxxx"),
            ..good.clone()
        };
        let past_end = ChunkData {
            offset: 8,
            ..good.clone()
        };
        assert!(existing_chunk_matches(&file, 10, &good));
        assert!(!existing_chunk_matches(&file, 10, &bad));
        assert!(!existing_chunk_matches(&file, 10, &past_end));
        let manifest = ContentManifest {
            files: vec![FileMapping {
                filename: "f.bin".into(),
                size: 10,
                chunks: vec![good.clone(), bad.clone()],
                ..Default::default()
            }],
            ..Default::default()
        };
        let slots = vec![FileSlot {
            handle: Mutex::new(None),
            remaining: AtomicUsize::new(0),
            size: 10,
            path: path.clone(),
            mode: 0o644,
        }];
        let flags = verify_existing(&manifest, &slots, &[10], 2, &AtomicBool::new(false));
        assert_eq!(flags, vec![vec![true, false]]);
        let _ = fs::remove_dir_all(&dir);
    }

    fn zip_stored(payload: &[u8]) -> Vec<u8> {
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&[0u8; 8]);
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.push(b'x');
        zip.extend_from_slice(payload);
        zip
    }

    fn encrypted_chunk(key: &[u8; 32], payload: &[u8]) -> Vec<u8> {
        use crate::steam::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block};
        let iv = [9u8; 16];
        let mut out = aes256_ecb_encrypt_block(key, &iv).unwrap().to_vec();
        out.extend(aes256_cbc_encrypt(key, &iv, &zip_stored(payload)).unwrap());
        out
    }

    fn chunk(sha: u8, data: &[u8], offset: u64) -> ChunkData {
        ChunkData {
            sha: vec![sha; 20],
            crc: steam_adler_hash(data),
            offset,
            cb_original: data.len() as u32,
            cb_compressed: data.len() as u32 + 64,
        }
    }

    #[test]
    fn writes_in_place_dedupes_chunks_and_resumes_from_disk() {
        let dir = std::env::temp_dir().join(format!(
            "droiddeck_steam_write_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let key = [3u8; 32];
        let manifest = ContentManifest {
            files: vec![
                FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                FileMapping {
                    filename: "bin/game.exe".into(),
                    size: 10,
                    flags: DEPOT_FILE_FLAG_EXECUTABLE,
                    chunks: vec![chunk(1, b"hello", 0), chunk(2, b"world", 5)],
                    ..Default::default()
                },
                FileMapping {
                    filename: "data/copy.bin".into(),
                    size: 5,
                    chunks: vec![chunk(2, b"world", 0)],
                    ..Default::default()
                },
                FileMapping {
                    filename: "empty.txt".into(),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        let request = DepotWriteRequest {
            manifest: &manifest,
            depot_key: &key,
            servers: &[],
            target_dir: &dir,
            ca_bundle_path: "",
            max_workers: 4,
            process_workers: 2,
        };
        let cancel = AtomicBool::new(false);
        let prepared = prepare(&request, &cancel).unwrap();
        assert_eq!(prepared.total, 15);
        assert_eq!(prepared.bytes_verified, 0);
        assert_eq!(prepared.chunks.len(), 2);
        assert_eq!(prepared.chunks[1].targets.len(), 2);
        assert!(dir.join("bin").is_dir());
        assert_eq!(fs::metadata(dir.join("empty.txt")).unwrap().len(), 0);

        let sink = ChunkSink {
            chunks: &prepared.chunks,
            files: &prepared.files,
            depot_key: &key,
        };
        let item = |id: u64| FetchItem {
            id,
            urls: vec!["x".into()],
            reserve: 0,
            range: None,
        };
        assert!(matches!(
            sink.process(&item(0), b"garbage-garbage-garbage-garbage!".to_vec()),
            Err(SinkError::Retry(_))
        ));
        assert_eq!(
            sink.process(&item(1), encrypted_chunk(&key, b"world"))
                .unwrap(),
            10
        );
        assert_eq!(fs::read(dir.join("data/copy.bin")).unwrap(), b"world");
        assert_eq!(
            sink.process(&item(0), encrypted_chunk(&key, b"hello"))
                .unwrap(),
            5
        );
        assert_eq!(fs::read(dir.join("bin/game.exe")).unwrap(), b"helloworld");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(dir.join("bin/game.exe"))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o755);
        }
        drop(prepared);

        // A resumed run reads back what is on disk and fetches only what is wrong or missing.
        fs::write(dir.join("bin/game.exe"), b"helloWORLD").unwrap();
        let again = prepare(&request, &cancel).unwrap();
        assert_eq!(again.bytes_verified, 10);
        assert_eq!(again.chunks.len(), 1);
        assert_eq!(
            again.chunks[0].targets,
            vec![Target {
                file_idx: 1,
                offset: 5
            }]
        );
        let _ = fs::remove_dir_all(&dir);
    }
}
