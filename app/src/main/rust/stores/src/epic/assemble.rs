//! Files from the chunk cache: the last stage of an Epic install, as a native run.
//!
//! The Kotlin manager's own loop does this too (`EpicDownloadManager.install`, after the chunk
//! fetch): for every pending file, open it, and for every part read the cached chunk and write
//! `chunk[offset..offset+size]`. This module does the same thing, in the same order, with the
//! same wording on a missing chunk, plus what the Kotlin loop cannot do without re-reading the
//! finished file from a slow card:
//!
//! - the file's SHA-1 is computed from the bytes as they are written (no extra I/O) and compared
//!   with the manifest's, when the manifest has one; a mismatch deletes the file and fails the
//!   run, so a bad assembly can never pass the next delta pass by size alone;
//! - with a scratch cache — one the caller placed apart from the install directory — every chunk
//!   is reference-counted across the pending files and deleted the moment the last file that
//!   needs it is written and verified, so the cache never holds more than what is still owed.
//!
//! The cache directory itself goes at the end of a successful run, scratch or not, which is what
//! the Kotlin loop does with `<installDir>/.chunks`. Cancellation and failure leave the cache
//! exactly as it stands, so the next run's delta pass skips the finished files and the fetch
//! skips the chunks still cached — on either engine.
//!
//! Why the cache may live elsewhere at all: an install to an SD card wrote every byte twice to
//! the card (chunk cache, then assembled file). With the cache on internal storage the card sees
//! each byte once, and the fetchers stop waiting on the card's write speed.

use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use sha1::{Digest, Sha1};

use super::manifest::{parse_manifest, FileInfo, Manifest};
use super::plan::resolve_cache_dir;

/// Everything the JNI layer hands over for one assembly run.
pub struct AssembleRequest {
    pub manifest_bytes: Vec<u8>,
    pub install_dir: String,
    /// `""` = `<installDir>/.chunks` (no scratch semantics); anything else = scratch cache.
    pub chunk_cache_dir: String,
    /// Indices into `manifest.files` of the files to assemble, in order.
    pub file_indices: Vec<usize>,
}

/// Terminal result of an assembly run.
#[derive(Clone, Debug, Default)]
pub struct AssembleOutcome {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    /// Bytes written into assembled files this run.
    pub bytes_written: u64,
    pub files_done: u64,
    pub files_total: u64,
    pub bytes_total: u64,
    /// Chunk files deleted early because no remaining file needed them (scratch cache only).
    pub chunks_removed: u64,
}

/// The parsed plan: manifest, the files to write, and how many of them still need each chunk.
/// Built up front so a bad index fails before anything is written.
#[derive(Debug)]
pub struct AssemblePlan {
    pub manifest: Manifest,
    pub install_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub scratch_cache: bool,
    pub files: Vec<usize>,
    pub bytes_total: u64,
    /// GUID → number of files in `files` whose parts reference it (a file counts once per GUID,
    /// however many of its parts use the chunk).
    pub refs: HashMap<String, u32>,
}

/// Parse + plan. `Err` = nothing was written.
pub fn build_assemble_plan(req: &AssembleRequest) -> Result<AssemblePlan, String> {
    if req.install_dir.is_empty() {
        return Err("assemble: empty install dir".to_string());
    }
    let manifest = parse_manifest(&req.manifest_bytes).map_err(|e| format!("assemble: {e}"))?;
    for &i in &req.file_indices {
        if i >= manifest.files.len() {
            return Err(format!(
                "assemble: file index {i} out of range ({} files)",
                manifest.files.len()
            ));
        }
    }
    let refs = chunk_refs(&manifest, &req.file_indices);
    let bytes_total = req
        .file_indices
        .iter()
        .map(|&i| manifest.files[i].file_size())
        .sum();
    Ok(AssemblePlan {
        install_dir: PathBuf::from(&req.install_dir),
        cache_dir: resolve_cache_dir(&req.install_dir, &req.chunk_cache_dir),
        scratch_cache: !req.chunk_cache_dir.is_empty(),
        files: req.file_indices.clone(),
        bytes_total,
        refs,
        manifest,
    })
}

/// How many of `file_indices` reference each chunk GUID.
pub fn chunk_refs(m: &Manifest, file_indices: &[usize]) -> HashMap<String, u32> {
    let mut refs: HashMap<String, u32> = HashMap::new();
    for &fi in file_indices {
        let Some(f) = m.files.get(fi) else {
            continue;
        };
        for g in distinct_guids(f) {
            *refs.entry(g).or_insert(0) += 1;
        }
    }
    refs
}

/// The GUIDs a file's parts reference, each once, in first-seen order.
fn distinct_guids(f: &FileInfo) -> Vec<String> {
    let mut out: Vec<String> = Vec::with_capacity(f.parts.len());
    for p in &f.parts {
        let g = p.guid_str();
        if !out.contains(&g) {
            out.push(g);
        }
    }
    out
}

/// The manifest's relative path with Windows separators normalised, as the Kotlin loop does
/// (`filename.replace("\\", "/")`).
pub fn relative_path(f: &FileInfo) -> String {
    f.filename.replace('\\', "/")
}

/// A manifest SHA-1 that is all zero means "none" (the Kotlin delta pass treats it the same way).
fn expected_sha1(f: &FileInfo) -> Option<&[u8; 20]> {
    f.sha1.as_ref().filter(|h| h.iter().any(|&b| b != 0))
}

/// Chunk bytes, read whole, with the last chunk kept: consecutive parts of one file usually come
/// from the same chunk, and a file's parts are the only place a chunk is read.
struct ChunkReader {
    cache_dir: PathBuf,
    last: Option<(String, Vec<u8>)>,
}

impl ChunkReader {
    fn read(&mut self, guid: &str, rel_path: &str) -> Result<&[u8], String> {
        if self.last.as_ref().map(|(g, _)| g.as_str()) != Some(guid) {
            let path = self.cache_dir.join(guid);
            if !path.exists() {
                return Err(format!("Missing chunk {guid} for {rel_path}"));
            }
            let data = fs::read(&path).map_err(|e| format!("read chunk {guid}: {e}"))?;
            self.last = Some((guid.to_string(), data));
        }
        Ok(self.last.as_ref().map(|(_, d)| d.as_slice()).unwrap_or(&[]))
    }

    /// Drop the kept copy of a chunk that is about to be deleted.
    fn forget(&mut self, guid: &str) {
        if self.last.as_ref().map(|(g, _)| g.as_str()) == Some(guid) {
            self.last = None;
        }
    }
}

/// Write one file from the cache. Returns the bytes written. The output is removed on any error,
/// so a file only ever exists complete (and, when the manifest hashes it, verified) or not at all.
fn write_file(reader: &mut ChunkReader, f: &FileInfo, out_path: &Path) -> Result<u64, String> {
    let rel_path = relative_path(f);
    if let Some(parent) = out_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("mkdirs {}: {e}", parent.display()))?;
    }
    let result = (|| {
        let file = File::create(out_path).map_err(|e| format!("create {rel_path}: {e}"))?;
        let mut out = BufWriter::with_capacity(1024 * 1024, file);
        let mut sha = expected_sha1(f).map(|_| Sha1::new());
        let mut written: u64 = 0;
        for part in &f.parts {
            let guid = part.guid_str();
            let data = reader.read(&guid, &rel_path)?;
            let start = usize::try_from(part.offset).map_err(|_| {
                format!("Chunk {guid} part offset {} invalid for {rel_path}", part.offset)
            })?;
            let len = part.size as u32 as usize;
            let end = start
                .checked_add(len)
                .filter(|&e| e <= data.len())
                .ok_or_else(|| {
                    format!(
                        "Chunk {guid} too short for {rel_path}: need {start}+{len}, have {}",
                        data.len()
                    )
                })?;
            let slice = &data[start..end];
            out.write_all(slice).map_err(|e| format!("write {rel_path}: {e}"))?;
            if let Some(sha) = sha.as_mut() {
                sha.update(slice);
            }
            written += len as u64;
        }
        out.flush().map_err(|e| format!("flush {rel_path}: {e}"))?;
        drop(out);
        if let (Some(expected), Some(sha)) = (expected_sha1(f), sha) {
            if sha.finalize().as_slice() != &expected[..] {
                return Err(format!("SHA-1 mismatch for {rel_path}"));
            }
        }
        Ok(written)
    })();
    if result.is_err() {
        let _ = fs::remove_file(out_path);
    }
    result
}

/// Run the plan. `progress(bytes_done, bytes_total, files_done, files_total)` fires after every
/// file; `log` receives the start line, failures and the terminal summary.
pub fn run_assemble(
    plan: &AssemblePlan,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64, u64, u64, u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> AssembleOutcome {
    let files_total = plan.files.len() as u64;
    let mut outcome = AssembleOutcome {
        files_total,
        bytes_total: plan.bytes_total,
        ..AssembleOutcome::default()
    };
    log(&format!(
        "assemble cache_dir={} scratch={} {} files={files_total} bytes={} chunks_referenced={}",
        plan.cache_dir.display(),
        plan.scratch_cache,
        crate::priority::log_field(),
        plan.bytes_total,
        plan.refs.len()
    ));

    let mut refs = plan.refs.clone();
    let mut reader = ChunkReader {
        cache_dir: plan.cache_dir.clone(),
        last: None,
    };
    for &fi in &plan.files {
        if cancel.load(Ordering::Relaxed) {
            outcome.cancelled = true;
            outcome.error = "cancelled".to_string();
            log(&format!(
                "cancelled during assembly ({}/{files_total} files)",
                outcome.files_done
            ));
            return outcome;
        }
        let f = &plan.manifest.files[fi];
        let out_path = plan.install_dir.join(relative_path(f));
        match write_file(&mut reader, f, &out_path) {
            Ok(written) => outcome.bytes_written += written,
            Err(err) => {
                outcome.error = err;
                log(&format!(
                    "FAIL {} ({}/{files_total} files ok)",
                    outcome.error, outcome.files_done
                ));
                return outcome;
            }
        }
        // Scratch cache: this file was the last user of a chunk → the chunk goes now, not at the
        // end, so the cache holds only what is still owed to files not yet written. Released
        // before the progress event, so "file done" also means "its chunks are freed".
        if plan.scratch_cache {
            for guid in distinct_guids(f) {
                let Some(count) = refs.get_mut(&guid) else {
                    continue;
                };
                *count = count.saturating_sub(1);
                if *count == 0 {
                    reader.forget(&guid);
                    if fs::remove_file(plan.cache_dir.join(&guid)).is_ok() {
                        outcome.chunks_removed += 1;
                    }
                }
            }
        }
        outcome.files_done += 1;
        progress(
            outcome.bytes_written,
            plan.bytes_total,
            outcome.files_done,
            files_total,
        );
    }

    outcome.success = true;
    // Done with the cache: remove it whole (a scratch cache is already down to the chunks no
    // pending file referenced; the default one is what the Kotlin loop deleted at this point).
    match fs::remove_dir_all(&plan.cache_dir) {
        Ok(()) => log(&format!("cache removed {}", plan.cache_dir.display())),
        Err(e) => log(&format!(
            "cache not removed {}: {e}",
            plan.cache_dir.display()
        )),
    }
    log(&format!(
        "assembled files={} bytes={} chunks_removed_early={}",
        outcome.files_done, outcome.bytes_written, outcome.chunks_removed
    ));
    outcome
}

#[cfg(test)]
mod tests {
    use super::super::chunk::test_support::temp_dir;
    use super::super::manifest::test_support::*;
    use super::*;
    use std::sync::Mutex;

    const A: [u32; 4] = [1, 2, 3, 4];
    const B: [u32; 4] = [5, 6, 7, 8];

    fn chunk_a() -> Vec<u8> {
        (0..300u32).map(|i| (i % 251) as u8).collect()
    }

    fn chunk_b() -> Vec<u8> {
        (0..100u32).map(|i| (200 - i) as u8).collect()
    }

    fn sha(bytes: &[u8]) -> [u8; 20] {
        let mut out = [0u8; 20];
        out.copy_from_slice(&Sha1::digest(bytes));
        out
    }

    /// file 0 = A[0..100] ++ B[10..60]; file 1 = A[100..300]. A is shared, B is file 0's alone.
    fn expected_files() -> (Vec<u8>, Vec<u8>) {
        let (a, b) = (chunk_a(), chunk_b());
        let mut f0 = a[0..100].to_vec();
        f0.extend_from_slice(&b[10..60]);
        (f0, a[100..300].to_vec())
    }

    fn manifest_bytes(file0_sha: [u8; 20]) -> Vec<u8> {
        let (_, f1) = expected_files();
        let chunks = vec![
            TestChunk { guid: A, hash: 1, sha1: [0x11; 20], group: 1, window: 300, file_size: 300 },
            TestChunk { guid: B, hash: 2, sha1: [0x22; 20], group: 2, window: 100, file_size: 100 },
        ];
        let files = vec![
            TestFile {
                name: "Game\\Binaries\\Game.exe".to_string(),
                sha1: file0_sha,
                tags: vec![],
                parts: vec![(A, 0, 100), (B, 10, 50)],
            },
            TestFile {
                name: "Game/Content/data.pak".to_string(),
                sha1: sha(&f1),
                tags: vec![],
                parts: vec![(A, 100, 200)],
            },
        ];
        build_manifest(&chunks, &files, 21, false)
    }

    fn guid(g: [u32; 4]) -> String {
        super::super::manifest::guid_to_string(&g)
    }

    fn fill_cache(cache: &Path) {
        fs::create_dir_all(cache).unwrap();
        fs::write(cache.join(guid(A)), chunk_a()).unwrap();
        fs::write(cache.join(guid(B)), chunk_b()).unwrap();
    }

    fn request(install: &Path, cache: &str) -> AssembleRequest {
        let (f0, _) = expected_files();
        AssembleRequest {
            manifest_bytes: manifest_bytes(sha(&f0)),
            install_dir: install.to_str().unwrap().to_string(),
            chunk_cache_dir: cache.to_string(),
            file_indices: vec![0, 1],
        }
    }

    fn run(plan: &AssemblePlan, cancel: bool) -> (AssembleOutcome, Vec<(u64, u64, u64, u64)>) {
        let calls = Mutex::new(Vec::new());
        let progress = |b: u64, bt: u64, f: u64, ft: u64| calls.lock().unwrap().push((b, bt, f, ft));
        let log = |_: &str| {};
        let out = run_assemble(plan, &AtomicBool::new(cancel), &progress, &log);
        (out, calls.into_inner().unwrap())
    }

    #[test]
    fn default_cache_assembles_like_the_kotlin_loop_and_removes_the_cache_at_the_end() {
        let dir = temp_dir("asm-default");
        let install = dir.join("install");
        let cache = install.join(".chunks");
        fill_cache(&cache);
        let plan = build_assemble_plan(&request(&install, "")).unwrap();
        assert!(!plan.scratch_cache);
        assert_eq!(plan.refs[&guid(A)], 2);
        assert_eq!(plan.refs[&guid(B)], 1);
        let (out, calls) = run(&plan, false);
        assert!(out.success, "{}", out.error);
        let (f0, f1) = expected_files();
        assert_eq!(fs::read(install.join("Game/Binaries/Game.exe")).unwrap(), f0);
        assert_eq!(fs::read(install.join("Game/Content/data.pak")).unwrap(), f1);
        assert_eq!(out.bytes_written, 350);
        assert_eq!(calls, vec![(150, 350, 1, 2), (350, 350, 2, 2)]);
        assert_eq!(out.chunks_removed, 0, "no early deletion without a scratch cache");
        assert!(!cache.exists(), "the cache goes at the end, as the Kotlin loop's deleteDir");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn scratch_cache_frees_each_chunk_when_its_last_file_is_done() {
        let dir = temp_dir("asm-scratch");
        let install = dir.join("install");
        let cache = dir.join("cache");
        fill_cache(&cache);
        let plan = build_assemble_plan(&request(&install, cache.to_str().unwrap())).unwrap();
        assert!(plan.scratch_cache);
        // After file 0: B (only file 0 used it) must be gone, A (file 1 still needs it) must stay.
        let seen = Mutex::new(Vec::new());
        let progress = |_: u64, _: u64, files_done: u64, _: u64| {
            if files_done == 1 {
                seen.lock().unwrap().push((cache.join(guid(A)).exists(), cache.join(guid(B)).exists()));
            }
        };
        let log = |_: &str| {};
        let out = run_assemble(&plan, &AtomicBool::new(false), &progress, &log);
        assert!(out.success, "{}", out.error);
        assert_eq!(*seen.lock().unwrap(), vec![(true, false)]);
        assert_eq!(out.chunks_removed, 2);
        assert!(!cache.exists());
        let (f0, f1) = expected_files();
        assert_eq!(fs::read(install.join("Game/Binaries/Game.exe")).unwrap(), f0);
        assert_eq!(fs::read(install.join("Game/Content/data.pak")).unwrap(), f1);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_chunk_fails_with_the_kotlin_wording_and_leaves_the_cache() {
        let dir = temp_dir("asm-missing");
        let install = dir.join("install");
        let cache = dir.join("cache");
        fill_cache(&cache);
        fs::remove_file(cache.join(guid(B))).unwrap();
        let plan = build_assemble_plan(&request(&install, cache.to_str().unwrap())).unwrap();
        let (out, calls) = run(&plan, false);
        assert!(!out.success && !out.cancelled);
        assert_eq!(out.error, format!("Missing chunk {} for Game/Binaries/Game.exe", guid(B)));
        assert!(calls.is_empty());
        assert!(!install.join("Game/Binaries/Game.exe").exists(), "no partial file survives");
        assert!(cache.join(guid(A)).exists(), "failure leaves the cache for the next run");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_sha1_mismatch_deletes_the_file_and_fails() {
        let dir = temp_dir("asm-sha");
        let install = dir.join("install");
        let cache = dir.join("cache");
        fill_cache(&cache);
        let mut req = request(&install, cache.to_str().unwrap());
        req.manifest_bytes = manifest_bytes([0x99; 20]);
        let plan = build_assemble_plan(&req).unwrap();
        let (out, _) = run(&plan, false);
        assert!(!out.success);
        assert_eq!(out.error, "SHA-1 mismatch for Game/Binaries/Game.exe");
        assert!(!install.join("Game/Binaries/Game.exe").exists());
        assert!(cache.join(guid(B)).exists(), "nothing was freed for a file that failed");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_all_zero_manifest_hash_means_no_check() {
        let dir = temp_dir("asm-nohash");
        let install = dir.join("install");
        let cache = install.join(".chunks");
        fill_cache(&cache);
        let mut req = request(&install, "");
        req.manifest_bytes = manifest_bytes([0u8; 20]);
        let plan = build_assemble_plan(&req).unwrap();
        let (out, _) = run(&plan, false);
        assert!(out.success, "{}", out.error);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn cancel_before_the_first_file_writes_nothing_and_keeps_the_cache() {
        let dir = temp_dir("asm-cancel");
        let install = dir.join("install");
        let cache = dir.join("cache");
        fill_cache(&cache);
        let plan = build_assemble_plan(&request(&install, cache.to_str().unwrap())).unwrap();
        let (out, calls) = run(&plan, true);
        assert!(out.cancelled && !out.success);
        assert!(calls.is_empty());
        assert!(cache.join(guid(A)).exists() && cache.join(guid(B)).exists());
        assert!(!install.join("Game").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn bad_inputs_fail_before_anything_is_written() {
        let dir = temp_dir("asm-inputs");
        let mut req = request(&dir, "");
        req.file_indices = vec![0, 2];
        assert!(build_assemble_plan(&req).unwrap_err().contains("out of range"));
        req.file_indices = vec![0];
        req.install_dir.clear();
        assert!(build_assemble_plan(&req).unwrap_err().contains("empty install dir"));
        req.install_dir = dir.to_str().unwrap().to_string();
        req.manifest_bytes = vec![1, 2, 3];
        assert!(build_assemble_plan(&req).is_err());
        let _ = fs::remove_dir_all(&dir);
    }
}
