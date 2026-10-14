//! The adaptive Epic install: chunks fetched in parallel and written straight into the files.
//!
//! The batch engine ([`super::driver`] + [`super::assemble`]) fetched every chunk into a cache
//! first and assembled the files afterwards: fast, but the cache and the game existed side by side
//! (twice the game's size on disk) and every byte was written twice. The bounded Java writer fixed
//! the space by fetching one chunk at a time, at the cost of the parallel fetch. This module keeps
//! the fetch core's parallel, adaptive download (window ramp, per-host caps, the in-flight byte
//! budget, retries, host rotation, stall handoff — none of it changes) and keeps the bounded
//! writer's safety rules, by deciding up front what each downloaded chunk is for:
//!
//! - **A chunk exactly one file part uses** is decompressed, verified and written straight into
//!   that file's staging file at the part's offset (a positioned write, so chunks may arrive in
//!   any order). It never touches a cache.
//! - **A chunk several parts use** is written into every part whose file is already open, and the
//!   decompressed copy is kept in the cache (`<cache>/<GUID>`, the same verified form and name the
//!   batch engine and the Java writer use, so a resume on any engine reuses it) until its last
//!   covered part is written; then it is deleted. Decompressed, not raw: a later part is then a
//!   plain read and write with no second inflate or hash, and the file is exactly what a resume
//!   already knows how to check.
//! - The cache has a **byte budget** (from the Java planner, sized from free space). The plan
//!   simulates the whole install in file order and, when the budget would be exceeded, evicts the
//!   cached chunk whose next use is furthest away (Belady's rule, the fewest refetches for that
//!   budget). An evicted chunk is downloaded again for its later uses, as its own fetch item.
//!
//! **Files open in order.** The fetch items are queued in the order of their first use, so the
//! fetch core works through the game front to back. A file is opened (its staging file created)
//! only once the download has reached it: every file before the first chunk still to arrive, plus
//! the file of every chunk that has arrived. That keeps the set of half-written files to what the
//! fetch window spans, and it is also why the cache never holds more than the plan allowed: a
//! cached chunk is written into every newly reached file at once, so what stays in the cache is
//! exactly what the simulation kept at that point of the file order.
//!
//! **Updates are paced by a replacement-overlap budget.** A staged file that replaces an existing
//! one sits beside the old copy until its swap, so every replaced file open at once costs its full
//! new size in extra space (on a fresh install a staged file simply becomes the file). Files are
//! admitted in order; a replaced file is admitted only while the planned sizes of admitted,
//! not-yet-swapped replaced files stay within the budget the manager computed from free space,
//! and one is always admitted when none is open, so the install always progresses. The fetch core
//! dispatches a chunk only once the file of its first use is admitted ([`run_fetch_gated`]).
//! Admission never changes the order files are reached in, only when, so the cache simulation
//! below (which works in file order) stays exact: the cache holds at any moment what the plan holds
//! at that point of the order. Nothing waits on a gated file: every chunk an admitted file needs is
//! first used at or before that file, so it was dispatched before the gate closed.
//!
//! **A slow disk throttles the download by itself.** Every write happens in the fetch core's
//! process pool, inside [`FetchSink::process`]; the core frees a body's share of its in-flight
//! byte budget only when `process` returns, so while the disk is behind, the budget fills and no
//! new request goes out. Memory is that budget plus one decompressed chunk per pool thread.
//!
//! **Each file is replaced safely**, with the bounded Java writer's rules: written into a unique
//! `.stage` file in the manager's owned staging directory (`<installDir>/.droiddeck-epic-staging`,
//! whose leftovers the manager recovers before the next run; a sibling `.droiddeck-epic-*.stage`
//! when no directory is given), never a manifest path, so a game file legitimately named `x.part`
//! is safe. Its SHA-1 computed as the bytes go out (parts written out of order are
//! read back once the bytes before them are in, while they are still in the page cache), then
//! synced, its size and hash checked, the install marked `updating` once (the Java callback,
//! before the first replacement), and the stage renamed over the destination. On cancel or failure
//! every unfinished stage is removed and the old files are untouched; verified files that were
//! already swapped stay, and the next run's delta pass skips them. The cache is left as it stands
//! for that run, and removed when the install completes.

use std::collections::{BTreeSet, HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::ErrorKind;
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::Instant;

use sha1::{Digest, Sha1};

use crate::fetch_core::{run_fetch_gated, FetchItem, FetchOptions, FetchSink, SinkError};

use super::assemble::relative_path;
use super::chunk::{chunk_window_bytes, decode_verified_chunk, part_path};
use super::driver::REQUEST_TIMEOUT;
use super::manifest::{parse_manifest, ChunkInfo, Manifest};
use super::plan::{chunk_url, distinct_prefixes, per_host_cap, resolve_cache_dir};

/// "No further use" in the schedule's next-use table.
const NONE: usize = usize::MAX;

/// Progress callbacks are rate-limited to one per this many milliseconds (a finished file always
/// reports): a large game has hundreds of thousands of parts, each a JNI call otherwise.
const PROGRESS_EVERY_MS: u64 = 50;

// ─────────────────────────────────────────────────────────────────────────────────────────────
// The schedule: which download serves which file part
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// One download of a chunk (or one cached copy left by an earlier run) and the file parts it
/// serves. Positions index the install's part sequence: every pending file's parts, file by file.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ScheduledEvent {
    /// Index into `manifest.unique_chunks`.
    pub chunk: usize,
    /// The part positions this copy serves, in order.
    pub uses: Vec<usize>,
    /// Already in the cache when the run started; not fetched.
    pub preloaded: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Schedule {
    pub events: Vec<ScheduledEvent>,
    /// Position → the event that serves it.
    pub use_event: Vec<usize>,
    /// Cached chunks left by an earlier run that this run will not use (no pending part needs
    /// them, or keeping them would exceed the budget): deleted before anything is fetched.
    pub discarded: Vec<usize>,
    /// The most the cache holds at any point of the file order.
    pub peak_cache_bytes: u64,
}

/// Plans the downloads for the part sequence `chunk_of_use` (the chunk each part position reads)
/// with at most `budget` bytes of cached chunks (`chunk_bytes[c]` each). A chunk with one use is
/// a plain download. A chunk with more uses stays cached from one use to the next; when the cache
/// would exceed the budget, the cached chunk needed furthest in the future (possibly the one just
/// downloaded) is dropped, and its next use becomes a new download. `preloaded` chunks start in
/// the cache, the soonest needed first while they fit.
pub fn schedule(
    chunk_of_use: &[usize],
    chunk_bytes: &[u64],
    preloaded: &[usize],
    budget: u64,
) -> Schedule {
    let n = chunk_of_use.len();
    let mut next_use = vec![NONE; n];
    let mut first_use: HashMap<usize, usize> = HashMap::new();
    for p in (0..n).rev() {
        let c = chunk_of_use[p];
        next_use[p] = first_use.get(&c).copied().unwrap_or(NONE);
        first_use.insert(c, p);
    }

    let mut out = Schedule {
        use_event: vec![NONE; n],
        ..Schedule::default()
    };
    // Cached chunk → (its event, the position it is next needed at), and the same keyed by next
    // use so the furthest one is the last entry.
    let mut resident: HashMap<usize, (usize, usize)> = HashMap::new();
    let mut by_next: BTreeSet<(usize, usize)> = BTreeSet::new();
    let mut bytes: u64 = 0;

    let mut pre: Vec<(usize, usize)> = Vec::new();
    let mut seen_pre: HashSet<usize> = HashSet::new();
    for &c in preloaded {
        if !seen_pre.insert(c) {
            continue;
        }
        match first_use.get(&c) {
            Some(&p) => pre.push((p, c)),
            None => out.discarded.push(c),
        }
    }
    pre.sort_unstable();
    for (p, c) in pre {
        let size = chunk_bytes[c];
        if bytes + size <= budget {
            let e = out.events.len();
            out.events.push(ScheduledEvent {
                chunk: c,
                uses: Vec::new(),
                preloaded: true,
            });
            resident.insert(c, (e, p));
            by_next.insert((p, c));
            bytes += size;
        } else {
            out.discarded.push(c);
        }
    }
    out.peak_cache_bytes = bytes;

    for p in 0..n {
        let c = chunk_of_use[p];
        let next = next_use[p];
        if let Some((e, _)) = resident.remove(&c) {
            by_next.remove(&(p, c));
            out.events[e].uses.push(p);
            out.use_event[p] = e;
            if next == NONE {
                bytes -= chunk_bytes[c];
            } else {
                resident.insert(c, (e, next));
                by_next.insert((next, c));
            }
            continue;
        }
        let e = out.events.len();
        out.events.push(ScheduledEvent {
            chunk: c,
            uses: vec![p],
            preloaded: false,
        });
        out.use_event[p] = e;
        if next == NONE {
            continue;
        }
        resident.insert(c, (e, next));
        by_next.insert((next, c));
        bytes += chunk_bytes[c];
        while bytes > budget {
            let Some(&(far, victim)) = by_next.iter().next_back() else {
                break;
            };
            by_next.remove(&(far, victim));
            resident.remove(&victim);
            bytes -= chunk_bytes[victim];
        }
        out.peak_cache_bytes = out.peak_cache_bytes.max(bytes);
    }
    out
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// The plan
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// Everything the JNI layer hands over for one run.
pub struct StreamRequest {
    pub manifest_bytes: Vec<u8>,
    pub install_dir: String,
    /// `""` = `<installDir>/.chunks`; a path = scratch cache (internal storage for a card install).
    pub chunk_cache_dir: String,
    /// The manager's owned staging directory on the install volume; `""` = stage beside each file.
    pub staging_dir: String,
    pub cdn_prefixes: Vec<String>,
    /// Indices into `manifest.files` of the files still to write (Java's delta pass), in order.
    pub pending_file_indices: Vec<usize>,
    /// Java's `Σ fileSize` over the pending files, for a cross-check (`None` = skip).
    pub expected_bytes: Option<u64>,
    /// Most bytes of shared chunks the cache may hold.
    pub cache_budget: u64,
    /// Most planned bytes of replaced files (destination already there) staged at once; one is
    /// always allowed. Fresh files are not limited by it.
    pub replace_overlap_budget: u64,
    pub ca_bundle_path: String,
    pub max_workers: usize,
    pub process_workers: usize,
    pub label: String,
}

#[derive(Debug)]
pub struct PlanPart {
    /// Index into `manifest.unique_chunks`.
    pub chunk: usize,
    pub chunk_offset: usize,
    pub file_offset: u64,
    pub size: usize,
}

#[derive(Debug)]
pub struct PlanFile {
    pub rel_path: String,
    pub size: u64,
    /// The manifest's SHA-1 when it has one (all-zero = none, as the Java delta pass treats it).
    pub sha1: Option<[u8; 20]>,
    pub parts: Vec<PlanPart>,
    /// Position of the file's first part in the install's part sequence.
    pub first_use: usize,
    /// The destination exists with data: staging this file costs space until its swap.
    pub replaces: bool,
}

#[derive(Debug)]
pub struct StreamPlan {
    pub manifest: Manifest,
    pub install_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub scratch_cache: bool,
    pub files: Vec<PlanFile>,
    /// Position → (file, part index in that file).
    pub use_file: Vec<usize>,
    pub use_part: Vec<usize>,
    pub schedule: Schedule,
    /// The events that are fetched, in fetch order; a fetch item's id indexes this.
    pub fetch_events: Vec<usize>,
    /// Event → the file of its first use (0 for a preloaded copy nothing ends up using).
    pub event_first_file: Vec<usize>,
    /// Event → it is its chunk's first copy, cached as `<GUID>` (later copies get `<GUID>.<event>`,
    /// so a copy being deleted can never remove the next one).
    pub event_first_copy: Vec<bool>,
    /// Every manifest file's destination: a staging name may never be one of them.
    pub reserved: HashSet<PathBuf>,
    /// Where staging files go; `None` = beside each destination.
    pub staging_dir: Option<PathBuf>,
    pub bytes_total: u64,
    pub budget: u64,
    pub overlap_budget: u64,
    pub hosts: Vec<String>,
    pub direct_chunks: usize,
    pub shared_chunks: usize,
    pub refetches: usize,
}

/// A cached chunk left by an earlier run is reused only when it is what the manifest says:
/// the window size when there is one, the SHA-1 when there is one (`isValidCachedChunk`).
pub fn cached_chunk_is_valid(path: &Path, chunk: &ChunkInfo) -> bool {
    let Ok(meta) = fs::metadata(path) else {
        return false;
    };
    if !meta.is_file() {
        return false;
    }
    if chunk.window_size > 0 && meta.len() != chunk.window_size as u64 {
        return false;
    }
    let Some(expected) = chunk.verifiable_sha1() else {
        return true;
    };
    match fs::read(path) {
        Ok(data) => Sha1::digest(&data).as_slice() == &expected[..],
        Err(_) => false,
    }
}

/// Parse, check, clean the cache and schedule. `Err` = nothing was fetched or written (stale cache
/// entries may have been removed, as the Java writer's own preparation does); the manager then
/// runs the bounded Java writer instead.
pub fn build_stream_plan(req: &StreamRequest) -> Result<StreamPlan, String> {
    if req.install_dir.is_empty() {
        return Err("stream: empty install dir".to_string());
    }
    let manifest = parse_manifest(&req.manifest_bytes).map_err(|e| format!("stream: {e}"))?;
    let by_guid = manifest.chunk_index_by_guid();

    let mut files: Vec<PlanFile> = Vec::with_capacity(req.pending_file_indices.len());
    let mut use_file: Vec<usize> = Vec::new();
    let mut use_part: Vec<usize> = Vec::new();
    let mut chunk_of_use: Vec<usize> = Vec::new();
    let mut bytes_total: u64 = 0;
    for &fi in &req.pending_file_indices {
        let Some(f) = manifest.files.get(fi) else {
            return Err(format!(
                "stream: pending file index {fi} out of range ({} files)",
                manifest.files.len()
            ));
        };
        let rel_path = relative_path(f);
        let first_use = chunk_of_use.len();
        let mut parts = Vec::with_capacity(f.parts.len());
        let mut file_offset: u64 = 0;
        for (k, part) in f.parts.iter().enumerate() {
            let guid = part.guid_str();
            let Some(&ci) = by_guid.get(&guid) else {
                return Err(format!("Missing chunk metadata {guid} for {rel_path}"));
            };
            let size = part.size as u32 as usize;
            let chunk_offset = usize::try_from(part.offset)
                .map_err(|_| format!("Chunk {guid} is too short for {rel_path}"))?;
            let window = chunk_window_bytes(manifest.unique_chunks[ci].window_size);
            if (chunk_offset as u64).saturating_add(size as u64) > window {
                return Err(format!("Chunk {guid} is too short for {rel_path}"));
            }
            parts.push(PlanPart {
                chunk: ci,
                chunk_offset,
                file_offset,
                size,
            });
            file_offset += size as u64;
            use_file.push(files.len());
            use_part.push(k);
            chunk_of_use.push(ci);
        }
        bytes_total += file_offset;
        let replaces = fs::metadata(Path::new(&req.install_dir).join(&rel_path))
            .map(|m| m.is_file() && m.len() > 0)
            .unwrap_or(false);
        files.push(PlanFile {
            replaces,
            rel_path,
            size: file_offset,
            sha1: f.sha1.filter(|h| h.iter().any(|&b| b != 0)),
            parts,
            first_use,
        });
    }
    if let Some(expected) = req.expected_bytes {
        if expected != bytes_total {
            return Err(format!(
                "stream: byte total mismatch java={expected} rust={bytes_total}"
            ));
        }
    }
    let hosts = distinct_prefixes(&req.cdn_prefixes);
    if hosts.is_empty() {
        return Err("stream: no CDN prefixes".to_string());
    }

    let install_dir = PathBuf::from(&req.install_dir);
    let staging_dir = if req.staging_dir.is_empty() {
        None
    } else {
        let dir = PathBuf::from(&req.staging_dir);
        if !dir.is_dir() {
            return Err(format!("stream: staging dir {} missing", dir.display()));
        }
        Some(dir)
    };
    let cache_dir = resolve_cache_dir(&req.install_dir, &req.chunk_cache_dir);
    fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("stream: mkdirs {}: {e}", cache_dir.display()))?;
    let reserved: HashSet<PathBuf> = manifest
        .files
        .iter()
        .map(|f| install_dir.join(relative_path(f)))
        .collect();

    // The cache keeps only verified chunks this install still needs, as the Java writer's
    // `prepareBoundedCache` does: anything else (a stale chunk, a `.part`, a later copy from a
    // cancelled run) would only take space the files need.
    let needed: HashSet<usize> = chunk_of_use.iter().copied().collect();
    let mut needed_by_name: HashMap<String, usize> = HashMap::new();
    for &ci in &needed {
        needed_by_name.insert(manifest.unique_chunks[ci].guid_str(), ci);
    }
    let entries = fs::read_dir(&cache_dir)
        .map_err(|e| format!("stream: list {}: {e}", cache_dir.display()))?;
    let mut preloaded: Vec<usize> = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        if let Some(&ci) = needed_by_name.get(&name) {
            if cached_chunk_is_valid(&path, &manifest.unique_chunks[ci]) {
                preloaded.push(ci);
                continue;
            }
        }
        remove_entry(&path);
    }

    let chunk_bytes: Vec<u64> = manifest
        .unique_chunks
        .iter()
        .map(|c| chunk_window_bytes(c.window_size))
        .collect();
    let schedule = schedule(&chunk_of_use, &chunk_bytes, &preloaded, req.cache_budget);
    for &ci in &schedule.discarded {
        remove_entry(&cache_dir.join(manifest.unique_chunks[ci].guid_str()));
    }

    let mut fetch_events = Vec::new();
    let mut event_first_file = Vec::with_capacity(schedule.events.len());
    let mut event_first_copy = Vec::with_capacity(schedule.events.len());
    let mut copies: HashMap<usize, usize> = HashMap::new();
    for (e, ev) in schedule.events.iter().enumerate() {
        event_first_file.push(ev.uses.first().map(|&p| use_file[p]).unwrap_or(0));
        let count = copies.entry(ev.chunk).or_insert(0);
        event_first_copy.push(*count == 0);
        *count += 1;
        if !ev.preloaded {
            fetch_events.push(e);
        }
    }
    let mut uses_per_chunk: HashMap<usize, usize> = HashMap::new();
    for &c in &chunk_of_use {
        *uses_per_chunk.entry(c).or_insert(0) += 1;
    }
    let direct_chunks = uses_per_chunk.values().filter(|&&n| n == 1).count();
    let shared_chunks = uses_per_chunk.len() - direct_chunks;
    let refetches: usize = copies.values().map(|&n| n - 1).sum();

    Ok(StreamPlan {
        manifest,
        install_dir,
        cache_dir,
        scratch_cache: !req.chunk_cache_dir.is_empty(),
        files,
        use_file,
        use_part,
        schedule,
        fetch_events,
        event_first_file,
        event_first_copy,
        reserved,
        staging_dir,
        bytes_total,
        budget: req.cache_budget,
        overlap_budget: req.replace_overlap_budget,
        hosts,
        direct_chunks,
        shared_chunks,
        refetches,
    })
}

fn remove_entry(path: &Path) {
    if path.is_dir() {
        let _ = fs::remove_dir_all(path);
    } else {
        let _ = fs::remove_file(path);
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// The run
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// What the Java side sees of a run's progress.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct StreamProgress {
    /// File bytes written so far (into staging files).
    pub bytes_written: u64,
    pub bytes_total: u64,
    pub files_done: u64,
    pub files_total: u64,
    /// Sizes of the files verified and swapped into place.
    pub files_bytes: u64,
}

/// The run's callbacks. All may be called from any engine thread.
pub struct Hooks<'a> {
    pub progress: &'a (dyn Fn(&StreamProgress) + Sync),
    /// Called once, before the first file replaces its destination: the Java side marks an
    /// existing install `updating`. `Err` fails the run and nothing is replaced.
    pub before_replace: &'a (dyn Fn() -> Result<(), String> + Sync),
    pub log: &'a (dyn Fn(&str) + Sync),
}

#[derive(Clone, Debug, Default)]
pub struct StreamOutcome {
    pub success: bool,
    pub cancelled: bool,
    pub error: String,
    pub bytes_written: u64,
    pub files_done: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EventState {
    /// Not downloaded yet.
    Pending,
    /// Downloaded, its cache copy being written; uses are only claimed by the downloading thread.
    Caching,
    /// In the cache: newly reached files read it from there.
    Cached,
    /// Every use is claimed; no cache copy (left).
    Done,
}

struct Shared {
    state: Vec<EventState>,
    /// Event → uses not written yet.
    remaining: Vec<u32>,
    /// Event → bytes its cache copy takes.
    cached_size: Vec<u64>,
    /// Position → a thread has taken this part to write.
    claimed: Vec<bool>,
    /// File → parts not written yet.
    parts_left: Vec<u32>,
    /// Files `[0, active)` have been reached and may be written.
    active: usize,
    /// One past the highest first-use file of a downloaded event.
    reached: usize,
    /// `fetch_events[..next_fetch]` have all been downloaded.
    next_fetch: usize,
    /// Files `[0, admitted)` passed the replacement-overlap budget; `active` never passes it.
    admitted: usize,
    /// Planned bytes of admitted replaced files not swapped in yet.
    overlap_open: u64,
    peak_overlap: u64,
    cache_bytes: u64,
    peak_cache: u64,
}

#[derive(Default)]
struct FileWriter {
    staging: Option<PathBuf>,
    file: Option<File>,
    sha: Option<Sha1>,
    /// Parts `[0, hashed)` are in the SHA-1.
    hashed: usize,
    done: Vec<bool>,
    /// Bytes written into the staging file.
    written: u64,
}

#[derive(Default)]
struct Stats {
    written: AtomicU64,
    direct: AtomicU64,
    from_cache: AtomicU64,
    cache_stored: AtomicU64,
    staging_now: AtomicU64,
    staging_peak: AtomicU64,
    files_done: AtomicU64,
    files_bytes: AtomicU64,
    fetched: AtomicU64,
    last_progress_ms: AtomicU64,
}

/// Parts to write from cache copies (event → positions), and part-less files to finish.
#[derive(Default)]
struct Work {
    from_cache: HashMap<usize, Vec<usize>>,
    empty_files: Vec<usize>,
}

/// Unique part of every staging name this process creates.
static STAGE_SEQ: AtomicU64 = AtomicU64::new(0);

/// The run's state; it is the fetch core's sink.
pub struct Engine<'a> {
    plan: &'a StreamPlan,
    cancel: &'a AtomicBool,
    hooks: &'a Hooks<'a>,
    shared: Mutex<Shared>,
    writers: Vec<Mutex<FileWriter>>,
    replace_gate: Mutex<Option<Result<(), String>>>,
    stats: Stats,
    started: Instant,
    /// `Shared::admitted`, readable by the fetch driver without the lock.
    admitted: AtomicUsize,
}

fn fatal_write(rel: &str, err: impl std::fmt::Display) -> SinkError {
    SinkError::Fatal(format!("Could not assemble or atomically replace {rel}: {err}"))
}

impl<'a> Engine<'a> {
    pub fn new(plan: &'a StreamPlan, cancel: &'a AtomicBool, hooks: &'a Hooks<'a>) -> Self {
        let events = &plan.schedule.events;
        let mut state = Vec::with_capacity(events.len());
        let mut cached_size = vec![0u64; events.len()];
        let mut cache_bytes = 0u64;
        for (e, ev) in events.iter().enumerate() {
            if !ev.preloaded {
                state.push(EventState::Pending);
                continue;
            }
            let path = cache_path(plan, e);
            if ev.uses.is_empty() {
                // Evicted before its first use in the plan: it only takes space.
                let _ = fs::remove_file(&path);
                state.push(EventState::Done);
                continue;
            }
            let size = fs::metadata(&path)
                .map(|m| m.len())
                .unwrap_or(plan_chunk_bytes(plan, ev.chunk));
            cached_size[e] = size;
            cache_bytes += size;
            state.push(EventState::Cached);
        }
        Engine {
            plan,
            cancel,
            hooks,
            shared: Mutex::new(Shared {
                state,
                remaining: events.iter().map(|ev| ev.uses.len() as u32).collect(),
                cached_size,
                claimed: vec![false; plan.use_file.len()],
                parts_left: plan.files.iter().map(|f| f.parts.len() as u32).collect(),
                active: 0,
                reached: 0,
                next_fetch: 0,
                admitted: 0,
                overlap_open: 0,
                peak_overlap: 0,
                cache_bytes,
                peak_cache: cache_bytes,
            }),
            writers: plan.files.iter().map(|_| Mutex::new(FileWriter::default())).collect(),
            replace_gate: Mutex::new(None),
            stats: Stats::default(),
            started: Instant::now(),
            admitted: AtomicUsize::new(0),
        }
    }

    /// The fetch driver's gate: fetch item `id` may go out once its first file is admitted.
    pub fn may_dispatch(&self, id: usize) -> bool {
        let p = self.plan;
        match p.fetch_events.get(id) {
            Some(&e) => p.event_first_file[e] < self.admitted.load(Ordering::Acquire),
            None => true,
        }
    }

    /// The most planned bytes of replaced files that were staged at once.
    pub fn peak_replace_overlap(&self) -> u64 {
        self.shared.lock().map(|st| st.peak_overlap).unwrap_or_default()
    }

    /// Keep reaching files until nothing new can be written: a file that finishes can admit the
    /// next replaced file, whose parts may already be in the cache. Iterative, so a long run of
    /// cache-only files never deepens the stack.
    fn settle(&self) -> Result<(), SinkError> {
        loop {
            let mut work = Work::default();
            {
                let mut st = self.shared.lock().expect("stream state poisoned");
                self.advance(&mut st, &mut work);
            }
            if work.from_cache.is_empty() && work.empty_files.is_empty() {
                return Ok(());
            }
            self.run_work(work)?;
        }
    }

    /// Before the first fetch: the files before the first chunk to download can be written from
    /// the cache an earlier run left (or are empty).
    pub fn start(&self) -> Result<(), SinkError> {
        self.settle()
    }

    /// Reach every file that can be written now: all files before the first chunk still to
    /// download, and every file up to the furthest first use of a chunk that has arrived. Parts
    /// of newly reached files whose chunk is in the cache are claimed for writing.
    fn advance(&self, st: &mut Shared, work: &mut Work) {
        let p = self.plan;
        while st.admitted < p.files.len() {
            let file = &p.files[st.admitted];
            if file.replaces {
                if st.overlap_open > 0 && st.overlap_open + file.size > p.overlap_budget {
                    break;
                }
                st.overlap_open += file.size;
                st.peak_overlap = st.peak_overlap.max(st.overlap_open);
            }
            st.admitted += 1;
        }
        self.admitted.store(st.admitted, Ordering::Release);
        while st.next_fetch < p.fetch_events.len()
            && st.state[p.fetch_events[st.next_fetch]] != EventState::Pending
        {
            st.next_fetch += 1;
        }
        let frontier = match p.fetch_events.get(st.next_fetch) {
            Some(&e) => p.event_first_file[e],
            None => p.files.len(),
        };
        let target = st.active.max(frontier).max(st.reached).min(st.admitted);
        for f in st.active..target {
            let file = &p.files[f];
            if file.parts.is_empty() {
                work.empty_files.push(f);
                continue;
            }
            for k in 0..file.parts.len() {
                let pos = file.first_use + k;
                let e = p.schedule.use_event[pos];
                if st.state[e] == EventState::Cached && !st.claimed[pos] {
                    st.claimed[pos] = true;
                    work.from_cache.entry(e).or_default().push(pos);
                }
            }
        }
        st.active = target;
    }

    /// One downloaded chunk body (fetch item `id`).
    fn on_body(&self, id: usize, body: Vec<u8>) -> Result<u64, SinkError> {
        let p = self.plan;
        let Some(&e) = p.fetch_events.get(id) else {
            return Err(SinkError::Fatal(format!("item id {id} out of range")));
        };
        let ev = &p.schedule.events[e];
        let chunk = &p.manifest.unique_chunks[ev.chunk];
        let data = decode_verified_chunk(&body, chunk.window_size, chunk.verifiable_sha1())
            .map_err(|reason| SinkError::Retry(format!("{} {reason}", chunk.guid_str())))?;
        drop(body);
        self.stats.fetched.fetch_add(1, Ordering::Relaxed);

        let mut work = Work::default();
        let mut now: Vec<usize> = Vec::new();
        let caching;
        {
            let mut st = self.shared.lock().expect("stream state poisoned");
            if st.state[e] != EventState::Pending {
                return Ok(chunk.credit_bytes());
            }
            // Provisional: until the cache copy exists, nobody else may claim this chunk's uses.
            st.state[e] = EventState::Caching;
            st.reached = st.reached.max(p.event_first_file[e] + 1);
            self.advance(&mut st, &mut work);
            for &pos in &ev.uses {
                if p.use_file[pos] < st.active && !st.claimed[pos] {
                    st.claimed[pos] = true;
                    now.push(pos);
                }
            }
            caching = now.len() < ev.uses.len();
            if !caching {
                st.state[e] = EventState::Done;
            }
        }
        self.write_from_memory(&data, &now)?;
        self.run_work(work)?;
        if caching {
            let path = cache_path(p, e);
            write_cache_file(&path, &data).map_err(|err| {
                SinkError::Fatal(format!("cache {}: {err}", chunk.guid_str()))
            })?;
            self.stats
                .cache_stored
                .fetch_add(data.len() as u64, Ordering::Relaxed);
            let mut more = Vec::new();
            {
                let mut st = self.shared.lock().expect("stream state poisoned");
                st.state[e] = EventState::Cached;
                st.cached_size[e] = data.len() as u64;
                st.cache_bytes += data.len() as u64;
                st.peak_cache = st.peak_cache.max(st.cache_bytes);
                // Files reached while the copy was being written.
                for &pos in &ev.uses {
                    if p.use_file[pos] < st.active && !st.claimed[pos] {
                        st.claimed[pos] = true;
                        more.push(pos);
                    }
                }
            }
            self.write_from_memory(&data, &more)?;
        }
        self.settle()?;
        Ok(chunk.credit_bytes())
    }

    fn write_from_memory(&self, data: &[u8], positions: &[usize]) -> Result<(), SinkError> {
        for &pos in positions {
            let written = self.write_use(pos, data)?;
            self.stats.direct.fetch_add(written, Ordering::Relaxed);
            self.complete_use(pos)?;
        }
        Ok(())
    }

    fn run_work(&self, work: Work) -> Result<(), SinkError> {
        let mut groups: Vec<(usize, Vec<usize>)> = work.from_cache.into_iter().collect();
        groups.sort_unstable();
        for (e, positions) in groups {
            let ev = &self.plan.schedule.events[e];
            let guid = self.plan.manifest.unique_chunks[ev.chunk].guid_str();
            let data = fs::read(cache_path(self.plan, e))
                .map_err(|err| SinkError::Fatal(format!("read cached chunk {guid}: {err}")))?;
            for pos in positions {
                let written = self.write_use(pos, &data)?;
                self.stats.from_cache.fetch_add(written, Ordering::Relaxed);
                self.complete_use(pos)?;
            }
        }
        for f in work.empty_files {
            self.finalize(f)?;
        }
        Ok(())
    }

    /// Write the part at `pos` from its chunk's bytes into its file's staging file.
    fn write_use(&self, pos: usize, chunk_data: &[u8]) -> Result<u64, SinkError> {
        let p = self.plan;
        let f = p.use_file[pos];
        let k = p.use_part[pos];
        let file = &p.files[f];
        let part = &file.parts[k];
        let guid = p.manifest.unique_chunks[part.chunk].guid_str();
        let end = part
            .chunk_offset
            .checked_add(part.size)
            .filter(|&end| end <= chunk_data.len())
            .ok_or_else(|| {
                SinkError::Fatal(format!("Chunk {guid} is too short for {}", file.rel_path))
            })?;
        let slice = &chunk_data[part.chunk_offset..end];
        {
            let mut guard = self.writers[f].lock().expect("writer poisoned");
            if guard.file.is_none() {
                self.open_staging(f, &mut guard)?;
            }
            let FileWriter {
                file: out,
                sha,
                hashed,
                done,
                written,
                ..
            } = &mut *guard;
            let out = out.as_ref().expect("staging open");
            out.write_all_at(slice, part.file_offset)
                .map_err(|err| fatal_write(&file.rel_path, err))?;
            done[k] = true;
            *written += slice.len() as u64;
            // SHA-1 in file order: the part just written comes from memory, parts that arrived
            // earlier out of order are read back from the staging file.
            if let Some(sha) = sha.as_mut() {
                while *hashed < file.parts.len() && done[*hashed] {
                    let h = *hashed;
                    if h == k {
                        sha.update(slice);
                    } else {
                        let q = &file.parts[h];
                        let mut buf = vec![0u8; q.size];
                        out.read_exact_at(&mut buf, q.file_offset)
                            .map_err(|err| fatal_write(&file.rel_path, err))?;
                        sha.update(&buf);
                    }
                    *hashed += 1;
                }
            }
        }
        let len = slice.len() as u64;
        let staged = self.stats.staging_now.fetch_add(len, Ordering::Relaxed) + len;
        self.stats.staging_peak.fetch_max(staged, Ordering::Relaxed);
        self.stats.written.fetch_add(len, Ordering::Relaxed);
        self.report(false);
        Ok(len)
    }

    /// A part is written: release its chunk copy after its last use, finish its file after its
    /// last part.
    fn complete_use(&self, pos: usize) -> Result<(), SinkError> {
        let p = self.plan;
        let e = p.schedule.use_event[pos];
        let f = p.use_file[pos];
        let (release, finish) = {
            let mut st = self.shared.lock().expect("stream state poisoned");
            st.remaining[e] = st.remaining[e].saturating_sub(1);
            let release = st.remaining[e] == 0 && st.state[e] == EventState::Cached;
            if release {
                st.state[e] = EventState::Done;
                st.cache_bytes = st.cache_bytes.saturating_sub(st.cached_size[e]);
            }
            st.parts_left[f] = st.parts_left[f].saturating_sub(1);
            (release, st.parts_left[f] == 0)
        };
        if release {
            let _ = fs::remove_file(cache_path(p, e));
        }
        if finish {
            self.finalize(f)?;
        }
        Ok(())
    }

    /// A unique `.stage` file in the owned staging directory (or beside the destination), never a
    /// manifest path (`createStagingFile`). The destination's directory is created either way.
    fn open_staging(&self, f: usize, w: &mut FileWriter) -> Result<(), SinkError> {
        let file = &self.plan.files[f];
        let dest = self.plan.install_dir.join(&file.rel_path);
        let parent = dest
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| self.plan.install_dir.clone());
        fs::create_dir_all(&parent).map_err(|err| fatal_write(&file.rel_path, err))?;
        let (stage_dir, prefix) = match &self.plan.staging_dir {
            Some(dir) => (dir.clone(), "stream-"),
            None => (parent.clone(), ".droiddeck-epic-"),
        };
        for _ in 0..64 {
            let nanos = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.subsec_nanos())
                .unwrap_or(0);
            let seq = STAGE_SEQ.fetch_add(1, Ordering::Relaxed);
            let path = stage_dir.join(format!(
                "{prefix}{}-{seq}-{nanos}.stage",
                std::process::id()
            ));
            if self.plan.reserved.contains(&path) {
                continue;
            }
            match OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .open(&path)
            {
                Ok(out) => {
                    w.staging = Some(path);
                    w.file = Some(out);
                    w.sha = file.sha1.map(|_| Sha1::new());
                    w.hashed = 0;
                    w.done = vec![false; file.parts.len()];
                    w.written = 0;
                    return Ok(());
                }
                Err(err) if err.kind() == ErrorKind::AlreadyExists => continue,
                Err(err) => return Err(fatal_write(&file.rel_path, err)),
            }
        }
        Err(fatal_write(&file.rel_path, "no free staging name"))
    }

    /// All parts are in: sync, check size and SHA-1, mark the install updating (once), rename
    /// over the destination. On any failure the staging file goes and the destination stays.
    fn finalize(&self, f: usize) -> Result<(), SinkError> {
        let mut guard = self.writers[f].lock().expect("writer poisoned");
        if guard.file.is_none() {
            self.open_staging(f, &mut guard)?;
        }
        let result = self.finish_file(f, &mut guard);
        let staged = guard.written;
        if result.is_err() {
            guard.file = None;
            if let Some(path) = guard.staging.take() {
                let _ = fs::remove_file(&path);
            }
        }
        drop(guard);
        self.stats
            .staging_now
            .fetch_sub(staged.min(self.stats.staging_now.load(Ordering::Relaxed)), Ordering::Relaxed);
        if result.is_ok() {
            let size = self.plan.files[f].size;
            if self.plan.files[f].replaces {
                let mut st = self.shared.lock().expect("stream state poisoned");
                st.overlap_open = st.overlap_open.saturating_sub(size);
            }
            self.stats.files_done.fetch_add(1, Ordering::Relaxed);
            self.stats.files_bytes.fetch_add(size, Ordering::Relaxed);
            self.report(true);
        }
        result
    }

    fn finish_file(&self, f: usize, w: &mut FileWriter) -> Result<(), SinkError> {
        let file = &self.plan.files[f];
        let rel = file.rel_path.as_str();
        let out = w.file.take().expect("staging open");
        out.sync_all().map_err(|err| fatal_write(rel, err))?;
        if self.cancel.load(Ordering::Relaxed) {
            return Err(SinkError::Fatal("cancelled".to_string()));
        }
        let len = out.metadata().map_err(|err| fatal_write(rel, err))?.len();
        drop(out);
        if len != file.size {
            return Err(SinkError::Fatal(format!("Size mismatch for {rel}")));
        }
        if let (Some(expected), Some(sha)) = (file.sha1.as_ref(), w.sha.take()) {
            if w.hashed != file.parts.len() || sha.finalize().as_slice() != &expected[..] {
                return Err(SinkError::Fatal(format!("SHA-1 mismatch for {rel}")));
            }
        }
        // Once a swap can mix versions, the old installed record is no longer truthful.
        self.mark_updating().map_err(|err| fatal_write(rel, err))?;
        if self.cancel.load(Ordering::Relaxed) {
            return Err(SinkError::Fatal("cancelled".to_string()));
        }
        let staging = w.staging.clone().expect("staging path");
        let dest = self.plan.install_dir.join(&file.rel_path);
        fs::rename(&staging, &dest).map_err(|err| fatal_write(rel, err))?;
        w.staging = None;
        Ok(())
    }

    fn mark_updating(&self) -> Result<(), String> {
        let mut gate = self.replace_gate.lock().expect("replace gate poisoned");
        if let Some(result) = gate.as_ref() {
            return result.clone();
        }
        let result = (self.hooks.before_replace)();
        *gate = Some(result.clone());
        result
    }

    fn report(&self, force: bool) {
        let now_ms = self.started.elapsed().as_millis() as u64;
        let last = self.stats.last_progress_ms.load(Ordering::Relaxed);
        if !force {
            if now_ms.saturating_sub(last) < PROGRESS_EVERY_MS {
                return;
            }
            if self
                .stats
                .last_progress_ms
                .compare_exchange(last, now_ms, Ordering::Relaxed, Ordering::Relaxed)
                .is_err()
            {
                return;
            }
        } else {
            self.stats.last_progress_ms.store(now_ms, Ordering::Relaxed);
        }
        (self.hooks.progress)(&self.snapshot());
    }

    fn snapshot(&self) -> StreamProgress {
        StreamProgress {
            bytes_written: self.stats.written.load(Ordering::Relaxed),
            bytes_total: self.plan.bytes_total,
            files_done: self.stats.files_done.load(Ordering::Relaxed),
            files_total: self.plan.files.len() as u64,
            files_bytes: self.stats.files_bytes.load(Ordering::Relaxed),
        }
    }

    /// Cancel or failure: every unfinished staging file goes; the destinations are untouched and
    /// the cache stays for the next run. Call only when no other thread is writing.
    pub fn discard_staging(&self) -> usize {
        let mut removed = 0;
        for writer in &self.writers {
            let mut guard = writer.lock().unwrap_or_else(|e| e.into_inner());
            guard.file = None;
            if let Some(path) = guard.staging.take() {
                if fs::remove_file(&path).is_ok() {
                    removed += 1;
                }
            }
        }
        removed
    }

    /// Files not yet swapped into place.
    pub fn unfinished_files(&self) -> u64 {
        self.plan.files.len() as u64 - self.stats.files_done.load(Ordering::Relaxed)
    }

    pub fn summary(&self) -> String {
        let secs = self.started.elapsed().as_secs_f64().max(0.001);
        let written = self.stats.written.load(Ordering::Relaxed);
        let (peak_cache, peak_overlap) = self
            .shared
            .lock()
            .map(|st| (st.peak_cache, st.peak_overlap))
            .unwrap_or_default();
        format!(
            "stream-summary mode=adaptive files={}/{} bytes_written={written} direct_bytes={} from_cache_bytes={} cache_stored_bytes={} peak_cache_bytes={peak_cache} budget={} plan_peak_cache_bytes={} peak_staging_bytes={} peak_replace_overlap_bytes={peak_overlap} overlap_budget={} fetched_chunks={} refetches={} elapsed={secs:.1}s avg_MBps={:.2}",
            self.stats.files_done.load(Ordering::Relaxed),
            self.plan.files.len(),
            self.stats.direct.load(Ordering::Relaxed),
            self.stats.from_cache.load(Ordering::Relaxed),
            self.stats.cache_stored.load(Ordering::Relaxed),
            self.plan.budget,
            self.plan.schedule.peak_cache_bytes,
            self.stats.staging_peak.load(Ordering::Relaxed),
            self.plan.overlap_budget,
            self.stats.fetched.load(Ordering::Relaxed),
            self.plan.refetches,
            written as f64 / secs / (1024.0 * 1024.0),
        )
    }
}

impl FetchSink for Engine<'_> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        self.on_body(item.id as usize, body)
    }
}

fn plan_chunk_bytes(plan: &StreamPlan, chunk: usize) -> u64 {
    chunk_window_bytes(plan.manifest.unique_chunks[chunk].window_size)
}

/// Where event `e`'s copy lives in the cache.
fn cache_path(plan: &StreamPlan, e: usize) -> PathBuf {
    let guid = plan.manifest.unique_chunks[plan.schedule.events[e].chunk].guid_str();
    if plan.event_first_copy[e] {
        plan.cache_dir.join(guid)
    } else {
        plan.cache_dir.join(format!("{guid}.{e}"))
    }
}

/// `.part` + rename, so a cache file under its final name is always complete.
fn write_cache_file(path: &Path, data: &[u8]) -> Result<(), String> {
    let tmp = part_path(path);
    let _ = fs::remove_file(&tmp);
    let result = fs::write(&tmp, data)
        .map_err(|e| format!("write {}: {e}", tmp.display()))
        .and_then(|()| fs::rename(&tmp, path).map_err(|e| format!("rename: {e}")));
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

/// Run the plan to the end: the fetch core downloads the chunks in order, the engine writes them.
pub fn run_stream(
    plan: &StreamPlan,
    req: &StreamRequest,
    cancel: &AtomicBool,
    hooks: &Hooks<'_>,
) -> StreamOutcome {
    let log = hooks.log;
    let host_cap = per_host_cap(req.max_workers, plan.hosts.len());
    log(&format!(
        "stream-plan chunk_dir={} cache_dir={} scratch={} {} files={} bytes={} uses={} chunks_direct={} chunks_shared={} fetches={} preloaded={} refetches={} budget={} plan_peak_cache={} replaced_files={} overlap_budget={} hosts={} workers={} per_host_cap={host_cap} process_workers={}",
        plan.manifest.chunk_dir,
        plan.cache_dir.display(),
        plan.scratch_cache,
        crate::priority::log_field(),
        plan.files.len(),
        plan.bytes_total,
        plan.use_file.len(),
        plan.direct_chunks,
        plan.shared_chunks,
        plan.fetch_events.len(),
        plan.schedule.events.len() - plan.fetch_events.len(),
        plan.refetches,
        plan.budget,
        plan.schedule.peak_cache_bytes,
        plan.files.iter().filter(|f| f.replaces).count(),
        plan.overlap_budget,
        plan.hosts.len(),
        req.max_workers,
        req.process_workers
    ));
    for (i, h) in plan.hosts.iter().enumerate() {
        log(&format!("host[{i}]={h}"));
    }

    let engine = Engine::new(plan, cancel, hooks);
    let mut outcome = StreamOutcome::default();
    let mut error: Option<String> = match engine.start() {
        Ok(()) => None,
        Err(SinkError::Fatal(m)) | Err(SinkError::Retry(m)) => Some(m),
    };

    if error.is_none() && !cancel.load(Ordering::Relaxed) && !plan.fetch_events.is_empty() {
        let items: Vec<FetchItem> = plan
            .fetch_events
            .iter()
            .enumerate()
            .map(|(id, &e)| {
                let chunk = &plan.manifest.unique_chunks[plan.schedule.events[e].chunk];
                FetchItem {
                    id: id as u64,
                    urls: plan
                        .hosts
                        .iter()
                        .map(|h| chunk_url(h, &plan.manifest.chunk_dir, chunk))
                        .collect(),
                    reserve: if chunk.file_size > 0 {
                        chunk.file_size as u64
                    } else {
                        0
                    },
                    range: None,
                }
            })
            .collect();
        let opts = FetchOptions {
            max_workers: req.max_workers.max(1),
            per_host_cap: host_cap,
            timeout: REQUEST_TIMEOUT,
            headers: vec![("User-Agent".to_string(), super::USER_AGENT.to_string())],
            ca_bundle_path: req.ca_bundle_path.clone(),
            process_workers: req.process_workers.max(1),
            label: req.label.clone(),
            ..FetchOptions::default()
        };
        // Progress is the engine's (file bytes written); the core's own credit only feeds its
        // `fetch-end` line.
        let no_progress = |_: u64, _: u64| {};
        let gate = |id: usize| engine.may_dispatch(id);
        let fetched = run_fetch_gated(
            items,
            &plan.hosts,
            &opts,
            &engine,
            cancel,
            &no_progress,
            log,
            &gate,
        );
        error = fetched.error;
    }

    let progress = engine.snapshot();
    outcome.bytes_written = progress.bytes_written;
    outcome.files_done = progress.files_done;
    if cancel.load(Ordering::Relaxed) {
        let removed = engine.discard_staging();
        outcome.cancelled = true;
        outcome.error = "cancelled".to_string();
        log(&engine.summary());
        log(&format!(
            "cancelled ({}/{} files, {removed} staging files removed)",
            progress.files_done, progress.files_total
        ));
        return outcome;
    }
    if error.is_none() && engine.unfinished_files() > 0 {
        error = Some(format!(
            "stream ended with {} files unfinished",
            engine.unfinished_files()
        ));
    }
    if let Some(err) = error {
        let removed = engine.discard_staging();
        outcome.error = err;
        log(&engine.summary());
        log(&format!(
            "FAIL {} ({}/{} files ok, {removed} staging files removed)",
            outcome.error, progress.files_done, progress.files_total
        ));
        return outcome;
    }
    outcome.success = true;
    log(&engine.summary());
    match fs::remove_dir_all(&plan.cache_dir) {
        Ok(()) => log(&format!("cache removed {}", plan.cache_dir.display())),
        Err(e) => log(&format!("cache not removed {}: {e}", plan.cache_dir.display())),
    }
    outcome
}

#[cfg(test)]
mod tests {
    use super::super::chunk::test_support::{build_chunk_body, sha1_of, temp_dir};
    use super::super::manifest::test_support::{build_manifest, TestChunk, TestFile};
    use super::*;

    // ── schedule ──

    #[test]
    fn single_use_chunks_are_plain_downloads_and_shared_ones_stay_cached() {
        // Chunk 0 is used at positions 0 and 2, chunk 1 once, chunk 2 once.
        let s = schedule(&[0, 1, 0, 2], &[10, 10, 10], &[], 100);
        assert_eq!(s.events.len(), 3);
        assert_eq!(s.events[0].uses, vec![0, 2]);
        assert_eq!(s.events[1].uses, vec![1]);
        assert_eq!(s.events[2].uses, vec![3]);
        assert_eq!(s.use_event, vec![0, 1, 0, 2]);
        assert_eq!(s.peak_cache_bytes, 10, "only the shared chunk is ever cached");
        assert!(s.discarded.is_empty());
    }

    #[test]
    fn a_chunk_bigger_than_the_budget_is_downloaded_for_every_use() {
        let s = schedule(&[0, 1, 0, 0], &[10, 1], &[], 5);
        let chunk0: Vec<&ScheduledEvent> = s.events.iter().filter(|e| e.chunk == 0).collect();
        assert_eq!(chunk0.len(), 3, "never cached → one download per use");
        assert!(chunk0.iter().all(|e| e.uses.len() == 1));
        assert_eq!(s.peak_cache_bytes, 0);
    }

    #[test]
    fn eviction_drops_the_chunk_needed_furthest_away() {
        // Budget fits two chunks. At position 2 chunk 2 arrives (next use 6) while 0 (next 3)
        // and 1 (next 4) are cached: chunk 2 itself is the furthest and is not kept.
        let uses = [0, 1, 2, 0, 1, 9, 2];
        let s = schedule(&uses, &[4, 4, 4, 0, 0, 0, 0, 0, 0, 1], &[], 8);
        let copies = |c: usize| s.events.iter().filter(|e| e.chunk == c).count();
        assert_eq!(copies(0), 1);
        assert_eq!(copies(1), 1);
        assert_eq!(copies(2), 2, "chunk 2 downloaded again for its later use");
        assert!(s.peak_cache_bytes <= 8);
        // Same sequence with room for all three: no refetch.
        let s = schedule(&uses, &[4, 4, 4, 0, 0, 0, 0, 0, 0, 1], &[], 12);
        assert_eq!(s.events.iter().filter(|e| e.chunk == 2).count(), 1);
        assert_eq!(s.peak_cache_bytes, 12);
    }

    #[test]
    fn preloaded_chunks_serve_their_uses_until_the_budget_says_otherwise() {
        // Chunk 0 cached by an earlier run, chunk 1 too but nothing needs it, chunk 2 cached but
        // over budget.
        let s = schedule(&[0, 2, 0], &[4, 4, 4], &[0, 1, 2], 4);
        assert!(s.events[0].preloaded);
        assert_eq!(s.events[0].chunk, 0);
        assert_eq!(s.events[0].uses, vec![0, 2]);
        let mut discarded = s.discarded.clone();
        discarded.sort_unstable();
        assert_eq!(discarded, vec![1, 2]);
        let fetched: Vec<&ScheduledEvent> = s.events.iter().filter(|e| !e.preloaded).collect();
        assert_eq!(fetched.len(), 1);
        assert_eq!(fetched[0].chunk, 2);
    }

    // ── engine (no network: bodies are handed to the sink directly) ──

    const A: [u32; 4] = [1, 0, 0, 0];
    const B: [u32; 4] = [2, 0, 0, 0];
    const C: [u32; 4] = [3, 0, 0, 0];

    fn data(seed: u32, len: usize) -> Vec<u8> {
        (0..len as u32).map(|i| ((i * 7 + seed) % 251) as u8).collect()
    }

    struct Fixture {
        dir: PathBuf,
        install: PathBuf,
        chunks: Vec<([u32; 4], Vec<u8>)>,
        files: Vec<(String, Vec<([u32; 4], i32, i32)>)>,
    }

    impl Fixture {
        /// Three files: `a.bin` = A[0..100] + B[0..50]; `dir/b.bin` = A[100..300] (A is shared);
        /// `c.bin` = C whole.
        fn new(tag: &str) -> Self {
            let dir = temp_dir(tag);
            let install = dir.join("game");
            fs::create_dir_all(&install).unwrap();
            Fixture {
                dir,
                install,
                chunks: vec![(A, data(1, 300)), (B, data(2, 50)), (C, data(3, 80))],
                files: vec![
                    ("a.bin".to_string(), vec![(A, 0, 100), (B, 0, 50)]),
                    ("dir\\b.bin".to_string(), vec![(A, 100, 200)]),
                    ("c.bin".to_string(), vec![(C, 0, 80)]),
                ],
            }
        }

        fn chunk(&self, guid: [u32; 4]) -> &Vec<u8> {
            &self.chunks.iter().find(|(g, _)| *g == guid).unwrap().1
        }

        fn expected(&self, file: usize) -> Vec<u8> {
            let mut out = Vec::new();
            for (g, off, size) in &self.files[file].1 {
                out.extend_from_slice(&self.chunk(*g)[*off as usize..(*off + *size) as usize]);
            }
            out
        }

        fn manifest(&self) -> Vec<u8> {
            let chunks: Vec<TestChunk> = self
                .chunks
                .iter()
                .map(|(g, d)| TestChunk {
                    guid: *g,
                    hash: g[0] as u64,
                    sha1: sha1_of(d),
                    group: 1,
                    window: d.len() as i32,
                    file_size: d.len() as u64,
                })
                .collect();
            let files: Vec<TestFile> = (0..self.files.len())
                .map(|i| TestFile {
                    name: self.files[i].0.clone(),
                    sha1: sha1_of(&self.expected(i)),
                    tags: vec![],
                    parts: self.files[i].1.clone(),
                })
                .collect();
            build_manifest(&chunks, &files, 21, true)
        }

        fn request(&self, pending: Vec<usize>, budget: u64) -> StreamRequest {
            StreamRequest {
                manifest_bytes: self.manifest(),
                install_dir: self.install.to_str().unwrap().to_string(),
                chunk_cache_dir: self.dir.join("cache").to_str().unwrap().to_string(),
                staging_dir: String::new(),
                cdn_prefixes: vec!["http://127.0.0.1:9/Builds/x".to_string()],
                pending_file_indices: pending,
                expected_bytes: None,
                cache_budget: budget,
                replace_overlap_budget: u64::MAX,
                ca_bundle_path: String::new(),
                max_workers: 4,
                process_workers: 2,
                label: "epic test".to_string(),
            }
        }

        fn body_for(&self, plan: &StreamPlan, id: usize) -> Vec<u8> {
            let ev = &plan.schedule.events[plan.fetch_events[id]];
            let guid = plan.manifest.unique_chunks[ev.chunk].guid;
            build_chunk_body(self.chunk(guid), true, 0, None)
        }

        fn stages(&self) -> Vec<PathBuf> {
            let mut out = Vec::new();
            let mut dirs = vec![self.install.clone()];
            while let Some(d) = dirs.pop() {
                for entry in fs::read_dir(&d).unwrap().flatten() {
                    let path = entry.path();
                    if path.is_dir() {
                        dirs.push(path);
                    } else if path.to_string_lossy().ends_with(".stage") {
                        out.push(path);
                    }
                }
            }
            out
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }

    struct Recorder {
        marks: Mutex<u32>,
        last: Mutex<StreamProgress>,
        fail_mark: bool,
    }

    impl Recorder {
        fn new() -> Self {
            Recorder {
                marks: Mutex::new(0),
                last: Mutex::new(StreamProgress::default()),
                fail_mark: false,
            }
        }
    }

    fn with_engine<R>(
        plan: &StreamPlan,
        cancel: &AtomicBool,
        rec: &Recorder,
        body: impl FnOnce(&Engine) -> R,
    ) -> R {
        let progress = |p: &StreamProgress| *rec.last.lock().unwrap() = *p;
        let before = || {
            *rec.marks.lock().unwrap() += 1;
            if rec.fail_mark {
                Err("mark failed".to_string())
            } else {
                Ok(())
            }
        };
        let log = |_: &str| {};
        let hooks = Hooks {
            progress: &progress,
            before_replace: &before,
            log: &log,
        };
        let engine = Engine::new(plan, cancel, &hooks);
        body(&engine)
    }

    #[test]
    fn plan_counts_direct_and_shared_chunks_and_orders_fetches_by_first_use() {
        let fx = Fixture::new("stream-plan");
        let plan = build_stream_plan(&fx.request(vec![0, 1, 2], 1 << 20)).unwrap();
        assert_eq!(plan.direct_chunks, 2, "B and C");
        assert_eq!(plan.shared_chunks, 1, "A");
        assert_eq!(plan.fetch_events.len(), 3);
        assert_eq!(plan.refetches, 0);
        assert_eq!(plan.bytes_total, 150 + 200 + 80);
        let order: Vec<[u32; 4]> = plan
            .fetch_events
            .iter()
            .map(|&e| plan.manifest.unique_chunks[plan.schedule.events[e].chunk].guid)
            .collect();
        assert_eq!(order, vec![A, B, C]);
        let mut req = fx.request(vec![0, 1, 2], 1 << 20);
        req.expected_bytes = Some(1);
        assert!(build_stream_plan(&req).unwrap_err().contains("byte total mismatch"));
    }

    #[test]
    fn out_of_order_arrival_writes_verified_files_and_empties_the_cache() {
        let fx = Fixture::new("stream-ooo");
        let plan = build_stream_plan(&fx.request(vec![0, 1, 2], 1 << 20)).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            // C, then B, then A: the shared chunk last.
            for id in (0..plan.fetch_events.len()).rev() {
                engine.process(&item(id), fx.body_for(&plan, id)).unwrap();
            }
            assert_eq!(engine.unfinished_files(), 0);
        });
        for i in 0..3 {
            let rel = fx.files[i].0.replace('\\', "/");
            assert_eq!(fs::read(fx.install.join(&rel)).unwrap(), fx.expected(i), "{rel}");
        }
        assert!(fx.stages().is_empty());
        assert_eq!(*rec.marks.lock().unwrap(), 1, "marked updating once");
        let last = *rec.last.lock().unwrap();
        assert_eq!(last.files_done, 3);
        assert_eq!(last.bytes_written, 430);
        assert_eq!(last.files_bytes, 430);
        let leftovers = fs::read_dir(&plan.cache_dir).unwrap().count();
        assert_eq!(leftovers, 0, "the shared chunk is released after its last use");
    }

    #[test]
    fn a_shared_chunk_arriving_first_is_cached_until_its_later_file_is_reached() {
        let fx = Fixture::new("stream-cache");
        let plan = build_stream_plan(&fx.request(vec![0, 1, 2], 1 << 20)).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            engine.process(&item(0), fx.body_for(&plan, 0)).unwrap(); // A
            let cached = plan.cache_dir.join(guid_to(A));
            assert!(cached.is_file(), "A waits in the cache for dir/b.bin");
            engine.process(&item(1), fx.body_for(&plan, 1)).unwrap(); // B: reaches b.bin
            assert!(!cached.exists(), "released after its last use");
            engine.process(&item(2), fx.body_for(&plan, 2)).unwrap(); // C
        });
        assert_eq!(fs::read(fx.install.join("dir/b.bin")).unwrap(), fx.expected(1));
    }

    #[test]
    fn a_zero_budget_refetches_shared_chunks_and_never_caches() {
        let fx = Fixture::new("stream-budget");
        let plan = build_stream_plan(&fx.request(vec![0, 1, 2], 0)).unwrap();
        assert_eq!(plan.refetches, 1, "A downloaded once per file");
        assert_eq!(plan.fetch_events.len(), 4);
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            for id in [3, 1, 0, 2] {
                engine.process(&item(id), fx.body_for(&plan, id)).unwrap();
                assert_eq!(fs::read_dir(&plan.cache_dir).unwrap().count(), 0);
            }
            assert_eq!(engine.unfinished_files(), 0);
        });
        for i in 0..3 {
            let rel = fx.files[i].0.replace('\\', "/");
            assert_eq!(fs::read(fx.install.join(&rel)).unwrap(), fx.expected(i));
        }
    }

    #[test]
    fn resume_writes_only_pending_files_and_reuses_a_cached_chunk() {
        let fx = Fixture::new("stream-resume");
        // An earlier run left A verified in the cache, plus a stale entry and a later copy.
        let cache = fx.dir.join("cache");
        fs::create_dir_all(&cache).unwrap();
        fs::write(cache.join(guid_to(A)), fx.chunk(A)).unwrap();
        fs::write(cache.join("FFFFFFFF000000000000000000000000"), b"stale").unwrap();
        fs::write(cache.join(format!("{}.7", guid_to(A))), fx.chunk(A)).unwrap();
        // a.bin was verified by the delta pass; only dir/b.bin and c.bin are pending.
        let plan = build_stream_plan(&fx.request(vec![1, 2], 1 << 20)).unwrap();
        assert_eq!(plan.fetch_events.len(), 1, "only C is downloaded");
        assert_eq!(fs::read_dir(&cache).unwrap().count(), 1, "stale entries removed");
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            assert!(fx.install.join("dir/b.bin").is_file(), "written from the cache at start");
            engine.process(&item(0), fx.body_for(&plan, 0)).unwrap();
            assert_eq!(engine.unfinished_files(), 0);
        });
        assert!(!fx.install.join("a.bin").exists());
        assert_eq!(fs::read(fx.install.join("c.bin")).unwrap(), fx.expected(2));
    }

    #[test]
    fn cancel_keeps_old_files_removes_staging_and_keeps_the_cache() {
        let fx = Fixture::new("stream-cancel");
        fs::write(fx.install.join("a.bin"), b"old a").unwrap();
        fs::write(fx.install.join("c.bin"), b"old c").unwrap();
        let plan = build_stream_plan(&fx.request(vec![0, 1, 2], 1 << 20)).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            // A arrives: a.bin half written, A cached for dir/b.bin.
            engine.process(&item(0), fx.body_for(&plan, 0)).unwrap();
            assert_eq!(fx.stages().len(), 1);
            cancel.store(true, Ordering::Relaxed);
            // B completes a.bin, but a cancelled run never swaps it in.
            assert!(engine.process(&item(1), fx.body_for(&plan, 1)).is_err());
            assert!(engine.discard_staging() <= 2);
        });
        assert!(fx.stages().is_empty());
        assert_eq!(fs::read(fx.install.join("a.bin")).unwrap(), b"old a");
        assert_eq!(fs::read(fx.install.join("c.bin")).unwrap(), b"old c");
        assert_eq!(*rec.marks.lock().unwrap(), 0, "nothing was replaced");
        assert!(plan.cache_dir.join(guid_to(A)).is_file(), "kept for the resume");
    }

    #[test]
    fn a_hash_failure_keeps_the_old_file_and_marks_nothing() {
        let fx = Fixture::new("stream-hash");
        fs::write(fx.install.join("c.bin"), b"old c").unwrap();
        let mut plan = build_stream_plan(&fx.request(vec![2], 1 << 20)).unwrap();
        // The manifest promises another hash than the bytes the chunk yields.
        plan.files[0].sha1 = Some([9; 20]);
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        let err = with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            let res = engine.process(&item(0), fx.body_for(&plan, 0));
            engine.discard_staging();
            res
        });
        match err {
            Err(SinkError::Fatal(m)) => assert!(m.contains("SHA-1 mismatch for c.bin"), "{m}"),
            other => panic!("expected a fatal hash failure, got {other:?}"),
        }
        assert_eq!(fs::read(fx.install.join("c.bin")).unwrap(), b"old c");
        assert!(fx.stages().is_empty());
        assert_eq!(*rec.marks.lock().unwrap(), 0);
    }

    #[test]
    fn a_corrupt_body_is_a_retry_that_changes_nothing() {
        let fx = Fixture::new("stream-corrupt");
        let plan = build_stream_plan(&fx.request(vec![2], 1 << 20)).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            let mut body = fx.body_for(&plan, 0);
            let n = body.len();
            body[n - 3] ^= 0xFF;
            assert!(matches!(engine.process(&item(0), body), Err(SinkError::Retry(_))));
            assert!(fx.stages().is_empty());
            engine.process(&item(0), fx.body_for(&plan, 0)).unwrap();
        });
        assert_eq!(fs::read(fx.install.join("c.bin")).unwrap(), fx.expected(2));
    }

    #[test]
    fn a_failed_mark_stops_before_any_replacement() {
        let fx = Fixture::new("stream-mark");
        fs::write(fx.install.join("c.bin"), b"old c").unwrap();
        let plan = build_stream_plan(&fx.request(vec![2], 1 << 20)).unwrap();
        let cancel = AtomicBool::new(false);
        let mut rec = Recorder::new();
        rec.fail_mark = true;
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            let err = engine.process(&item(0), fx.body_for(&plan, 0)).unwrap_err();
            assert!(matches!(err, SinkError::Fatal(ref m) if m.contains("atomically replace")));
        });
        assert_eq!(fs::read(fx.install.join("c.bin")).unwrap(), b"old c");
        assert!(fx.stages().is_empty());
    }

    #[test]
    fn an_owned_staging_dir_holds_the_stages_and_is_left_empty() {
        let fx = Fixture::new("stream-staging");
        let staging = fx.install.join(".droiddeck-epic-staging");
        fs::create_dir_all(&staging).unwrap();
        let mut req = fx.request(vec![0, 1, 2], 1 << 20);
        req.staging_dir = staging.to_str().unwrap().to_string();
        let plan = build_stream_plan(&req).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            engine.process(&item(0), fx.body_for(&plan, 0)).unwrap();
            let stages: Vec<_> = fs::read_dir(&staging).unwrap().flatten().collect();
            assert_eq!(stages.len(), 1, "a.bin is staged in the owned directory");
            assert!(stages[0].file_name().to_string_lossy().ends_with(".stage"));
            for id in 1..plan.fetch_events.len() {
                engine.process(&item(id), fx.body_for(&plan, id)).unwrap();
            }
        });
        assert_eq!(fs::read_dir(&staging).unwrap().count(), 0);
        assert_eq!(fs::read(fx.install.join("dir/b.bin")).unwrap(), fx.expected(1));
        let mut missing = fx.request(vec![0], 1 << 20);
        missing.staging_dir = fx.dir.join("nope").to_str().unwrap().to_string();
        assert!(build_stream_plan(&missing).unwrap_err().contains("staging dir"));
    }

    #[test]
    fn a_manifest_file_named_like_a_stage_is_never_used_for_staging() {
        let fx = Fixture::new("stream-reserved");
        let plan = build_stream_plan(&fx.request(vec![0, 1, 2], 1 << 20)).unwrap();
        assert!(plan.reserved.contains(&fx.install.join("dir/b.bin")));
        assert!(plan.reserved.contains(&fx.install.join("a.bin")));
    }

    // ── replacement-overlap gate ──

    /// `n` files of `len` bytes, one chunk each.
    fn big_fixture(tag: &str, n: usize, len: usize) -> Fixture {
        let dir = temp_dir(tag);
        let install = dir.join("game");
        fs::create_dir_all(&install).unwrap();
        let guid = |i: usize| [0x100 + i as u32, 0, 0, 0];
        Fixture {
            dir,
            install,
            chunks: (0..n).map(|i| (guid(i), data(i as u32 + 10, len))).collect(),
            files: (0..n)
                .map(|i| (format!("f{i}.bin"), vec![(guid(i), 0, len as i32)]))
                .collect(),
        }
    }

    /// Drive the engine the way the fetch core does: items in order while the gate allows,
    /// bodies handed back newest first (the most out-of-order the core can be).
    fn drive_gated(fx: &Fixture, plan: &StreamPlan, engine: &Engine) -> Result<(), SinkError> {
        engine.start()?;
        let mut next = 0;
        let mut in_flight: Vec<usize> = Vec::new();
        while next < plan.fetch_events.len() || !in_flight.is_empty() {
            while next < plan.fetch_events.len() && engine.may_dispatch(next) {
                in_flight.push(next);
                next += 1;
            }
            let id = in_flight.pop().expect("gate closed with nothing in flight: deadlock");
            engine.process(&item(id), fx.body_for(plan, id))?;
        }
        Ok(())
    }

    fn write_old(fx: &Fixture, files: &[usize]) {
        for &i in files {
            fs::write(fx.install.join(&fx.files[i].0), b"old").unwrap();
        }
    }

    #[test]
    fn replaced_files_stay_within_the_overlap_budget_and_complete() {
        let fx = big_fixture("stream-overlap", 6, 1000);
        write_old(&fx, &[0, 1, 2, 3, 4, 5]);
        let mut req = fx.request((0..6).collect(), 1 << 20);
        req.replace_overlap_budget = 2500;
        let plan = build_stream_plan(&req).unwrap();
        assert!(plan.files.iter().all(|f| f.replaces));
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            assert!(engine.may_dispatch(1), "two 1000-byte files fit 2500");
            assert!(!engine.may_dispatch(2), "a third does not");
            drive_gated(&fx, &plan, engine).unwrap();
            assert_eq!(engine.peak_replace_overlap(), 2000);
            assert_eq!(engine.unfinished_files(), 0);
        });
        for i in 0..6 {
            assert_eq!(fs::read(fx.install.join(&fx.files[i].0)).unwrap(), fx.expected(i));
        }
        assert!(fx.stages().is_empty());
    }

    #[test]
    fn a_budget_below_the_largest_file_still_progresses_one_at_a_time() {
        let fx = big_fixture("stream-overlap-one", 4, 800);
        write_old(&fx, &[0, 1, 2, 3]);
        let mut req = fx.request((0..4).collect(), 1 << 20);
        req.replace_overlap_budget = 1;
        let plan = build_stream_plan(&req).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            assert!(engine.may_dispatch(0));
            assert!(!engine.may_dispatch(1));
            drive_gated(&fx, &plan, engine).unwrap();
            assert_eq!(engine.peak_replace_overlap(), 800);
            assert_eq!(engine.unfinished_files(), 0);
        });
        for i in 0..4 {
            assert_eq!(fs::read(fx.install.join(&fx.files[i].0)).unwrap(), fx.expected(i));
        }
    }

    #[test]
    fn a_fresh_install_is_never_gated() {
        let fx = big_fixture("stream-overlap-fresh", 5, 600);
        let mut req = fx.request((0..5).collect(), 1 << 20);
        req.replace_overlap_budget = 1;
        let plan = build_stream_plan(&req).unwrap();
        assert!(plan.files.iter().all(|f| !f.replaces));
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            assert!((0..5).all(|id| engine.may_dispatch(id)));
            drive_gated(&fx, &plan, engine).unwrap();
            assert_eq!(engine.peak_replace_overlap(), 0);
        });
        assert_eq!(fs::read(fx.install.join("f4.bin")).unwrap(), fx.expected(4));
    }

    #[test]
    fn mixed_fresh_and_replaced_files_gate_only_the_replacements() {
        let fx = big_fixture("stream-overlap-mixed", 6, 700);
        write_old(&fx, &[1, 3, 5]);
        let mut req = fx.request((0..6).collect(), 1 << 20);
        req.replace_overlap_budget = 700;
        let plan = build_stream_plan(&req).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            // f0 fresh, f1 replaced (admitted), f2 fresh, f3 replaced (waits for f1's swap).
            assert!(engine.may_dispatch(2));
            assert!(!engine.may_dispatch(3));
            drive_gated(&fx, &plan, engine).unwrap();
            assert_eq!(engine.peak_replace_overlap(), 700);
            assert_eq!(engine.unfinished_files(), 0);
        });
        for i in 0..6 {
            assert_eq!(fs::read(fx.install.join(&fx.files[i].0)).unwrap(), fx.expected(i));
        }
    }

    #[test]
    fn a_shared_chunk_for_a_gated_file_waits_in_the_cache() {
        // f0 and f2 are replaced and share chunk A; f1 is fresh. With room for one replaced file,
        // f2 is admitted only after f0's swap, and A is cached until then.
        let dir = temp_dir("stream-overlap-shared");
        let install = dir.join("game");
        fs::create_dir_all(&install).unwrap();
        let fx = Fixture {
            dir,
            install,
            chunks: vec![(A, data(1, 300)), (B, data(2, 50))],
            files: vec![
                ("f0.bin".to_string(), vec![(A, 0, 100)]),
                ("f1.bin".to_string(), vec![(B, 0, 50)]),
                ("f2.bin".to_string(), vec![(A, 100, 200)]),
            ],
        };
        write_old(&fx, &[0, 2]);
        let mut req = fx.request(vec![0, 1, 2], 1 << 20);
        req.replace_overlap_budget = 150;
        let plan = build_stream_plan(&req).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            drive_gated(&fx, &plan, engine).unwrap();
            assert!(engine.peak_replace_overlap() <= 200);
            assert_eq!(engine.unfinished_files(), 0);
        });
        for i in 0..3 {
            assert_eq!(fs::read(fx.install.join(&fx.files[i].0)).unwrap(), fx.expected(i));
        }
        assert_eq!(fs::read_dir(&plan.cache_dir).unwrap().count(), 0);
    }

    #[test]
    fn cancelling_a_gated_update_keeps_every_old_file() {
        let fx = big_fixture("stream-overlap-cancel", 4, 900);
        write_old(&fx, &[0, 1, 2, 3]);
        let mut req = fx.request((0..4).collect(), 1 << 20);
        req.replace_overlap_budget = 1000;
        let plan = build_stream_plan(&req).unwrap();
        let cancel = AtomicBool::new(false);
        let rec = Recorder::new();
        with_engine(&plan, &cancel, &rec, |engine| {
            engine.start().unwrap();
            engine.process(&item(0), fx.body_for(&plan, 0)).unwrap(); // f0 swapped in
            assert!(engine.may_dispatch(1));
            cancel.store(true, Ordering::Relaxed);
            assert!(engine.process(&item(1), fx.body_for(&plan, 1)).is_err());
            engine.discard_staging();
        });
        assert_eq!(fs::read(fx.install.join("f0.bin")).unwrap(), fx.expected(0));
        for i in 1..4 {
            assert_eq!(fs::read(fx.install.join(&fx.files[i].0)).unwrap(), b"old");
        }
        assert!(fx.stages().is_empty());
    }

    fn item(id: usize) -> FetchItem {
        FetchItem {
            id: id as u64,
            urls: vec!["http://127.0.0.1:9/x".to_string()],
            reserve: 0,
            range: None,
        }
    }

    fn guid_to(g: [u32; 4]) -> String {
        super::super::manifest::guid_to_string(&g)
    }
}
