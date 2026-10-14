//! Budgeted native install: ordered lookahead, shared peak schedule, fetch-core overlap and
//! verified per-file atomic swaps. The JNI snapshot is the Java delta pass's exact pending set.

use crate::fetch_core::{run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};
use serde_json::Value;
use sha1::{Digest, Sha1};
use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::{BufWriter, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::Duration;

const MIB: u64 = 1024 * 1024;
const RETAINED: u64 = 64 * MIB;

#[derive(Clone, Debug)]
pub struct Chunk {
    guid: String,
    path: String,
    size: u64,
    wire: u64,
    sha: Option<[u8; 20]>,
    cached: Option<u64>,
    exact: bool,
}
#[derive(Clone, Debug)]
pub struct Part {
    chunk: usize,
    offset: u64,
    size: u64,
}
#[derive(Clone, Debug)]
pub struct Output {
    path: String,
    sha: Option<[u8; 20]>,
    old: u64,
    parts: Vec<Part>,
}
#[derive(Clone, Debug)]
pub struct Request {
    pub install: PathBuf,
    pub cache: PathBuf,
    pub staging: PathBuf,
    pub same_volume: bool,
    pub free_game: Option<u64>,
    pub free_cache: Option<u64>,
    pub chunks: Vec<Chunk>,
    pub files: Vec<Output>,
    pub hosts: Vec<String>,
    pub max_workers: usize,
}

fn uint(v: &Value, key: &str) -> Result<u64, String> {
    v[key].as_u64().ok_or_else(|| format!("invalid {key}"))
}
fn text(v: &Value, key: &str) -> Result<String, String> {
    v[key]
        .as_str()
        .map(str::to_string)
        .ok_or_else(|| format!("invalid {key}"))
}
fn digest(v: &Value) -> Result<Option<[u8; 20]>, String> {
    let s = v.as_str().ok_or("invalid sha1")?;
    if s.is_empty() {
        return Ok(None);
    }
    if s.len() != 40 || !s.is_ascii() {
        return Err("invalid sha1".into());
    }
    let mut out = [0; 20];
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).map_err(|_| "invalid sha1")?;
    }
    Ok(out.iter().any(|b| *b != 0).then_some(out))
}
fn relative(s: &str) -> Result<(), String> {
    if s.is_empty()
        || s.contains('\\')
        || s.contains(':')
        || Path::new(s)
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err(format!("unsafe manifest path {s}"));
    }
    Ok(())
}
impl Request {
    pub fn parse(json: &str) -> Result<Self, String> {
        let v: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
        if uint(&v, "schema_version")? != 1 {
            return Err("unsupported adaptive request schema".into());
        }
        let mut chunks = Vec::new();
        let mut by_guid = HashMap::new();
        for c in v["chunks"].as_array().ok_or("missing chunks")? {
            let guid = text(c, "guid")?;
            if guid.len() != 32
                || !guid.bytes().all(|b| b.is_ascii_hexdigit())
                || by_guid.contains_key(&guid)
            {
                return Err("invalid or duplicate chunk GUID".into());
            }
            let path = text(c, "path")?;
            relative(&path)?;
            by_guid.insert(guid.clone(), chunks.len());
            chunks.push(Chunk {
                guid,
                path,
                size: uint(c, "size")?,
                wire: uint(c, "wire_size")?,
                sha: digest(&c["sha1"])?,
                cached: c["cached"].as_u64(),
                exact: c["exact_size"].as_bool().ok_or("missing exact_size")?,
            });
        }
        let mut files = Vec::new();
        for f in v["files"].as_array().ok_or("missing files")? {
            let path = text(f, "path")?;
            relative(&path)?;
            if path.split('/').next() == Some(".droiddeck-epic-staging") {
                return Err("manifest conflicts with owned staging".into());
            }
            let mut parts = Vec::new();
            for p in f["parts"].as_array().ok_or("missing parts")? {
                let chunk = *by_guid
                    .get(&text(p, "guid")?)
                    .ok_or("missing chunk metadata")?;
                let offset = uint(p, "offset")?;
                let size = uint(p, "size")?;
                if offset
                    .checked_add(size)
                    .filter(|n| *n <= chunks[chunk].size)
                    .is_none()
                {
                    return Err("part exceeds decoded window".into());
                }
                parts.push(Part {
                    chunk,
                    offset,
                    size,
                });
            }
            files.push(Output {
                path,
                parts,
                sha: digest(&f["sha1"])?,
                old: uint(f, "old_size")?,
            });
        }
        let install = PathBuf::from(text(&v, "install_dir")?);
        let staging = PathBuf::from(text(&v, "staging_dir")?);
        Self::validate_staging(&install, &staging)?;
        let hosts = v["hosts"]
            .as_array()
            .ok_or("missing hosts")?
            .iter()
            .map(|h| {
                h.as_str()
                    .map(str::to_string)
                    .ok_or_else(|| "invalid host".to_string())
            })
            .collect::<Result<Vec<_>, _>>()?;
        if hosts.is_empty() {
            return Err("no Epic CDN".into());
        }
        let cache = PathBuf::from(text(&v, "cache_dir")?);
        let declared_same = v["same_volume"].as_bool().ok_or("missing same_volume")?;
        let same_volume = declared_same || Self::same_filesystem(&install, &cache);
        let game_probe = v["free_game"].as_u64();
        let cache_probe = v["free_cache"].as_u64();
        let free_game = if same_volume {
            match (game_probe, cache_probe) {
                (Some(a), Some(b)) => Some(a.min(b)),
                (a, b) => a.or(b),
            }
        } else {
            game_probe
        };
        Ok(Self {
            install,
            staging,
            cache,
            chunks,
            files,
            hosts,
            same_volume,
            free_game,
            free_cache: cache_probe,
            max_workers: uint(&v, "max_workers")?.clamp(1, 32) as usize,
        })
    }

    fn validate_staging(install: &Path, staging: &Path) -> Result<(), String> {
        let expected = fs::canonicalize(install)
            .map_err(|e| e.to_string())?
            .join(".droiddeck-epic-staging");
        let metadata = fs::symlink_metadata(staging).map_err(|e| e.to_string())?;
        if !metadata.file_type().is_dir()
            || fs::canonicalize(staging).map_err(|e| e.to_string())? != expected
        {
            return Err("unsafe staging directory; refusing to traverse it".into());
        }
        let owner = staging.join("owner");
        let metadata = fs::symlink_metadata(&owner).map_err(|e| e.to_string())?;
        if !metadata.file_type().is_file()
            || metadata.len() != 26
            || fs::canonicalize(&owner).map_err(|e| e.to_string())? != expected.join("owner")
            || fs::read(&owner).map_err(|e| e.to_string())? != b"DroidDeck Epic staging v1\n"
        {
            return Err("unowned staging directory".into());
        }
        for entry in fs::read_dir(staging).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            let name = entry.file_name();
            if name == "owner" {
                continue;
            }
            if !entry.file_type().map_err(|e| e.to_string())?.is_file()
                || !name.to_string_lossy().ends_with(".stage")
                || fs::canonicalize(entry.path()).map_err(|e| e.to_string())?
                    != expected.join(&name)
            {
                return Err("unsafe staging entry".into());
            }
        }
        Ok(())
    }

    #[cfg(unix)]
    fn same_filesystem(a: &Path, b: &Path) -> bool {
        use std::os::unix::fs::MetadataExt;
        match (fs::metadata(a), fs::metadata(b)) {
            (Ok(a), Ok(b)) => a.dev() == b.dev(),
            _ => false,
        }
    }
    #[cfg(not(unix))]
    fn same_filesystem(_a: &Path, _b: &Path) -> bool {
        false
    }
    fn total(&self) -> u64 {
        self.files
            .iter()
            .flat_map(|f| &f.parts)
            .map(|p| p.size)
            .sum()
    }
}

/// Resident includes full-size reservations for in-flight writes; only ready chunks can be read.
#[derive(Clone, Debug)]
struct Schedule {
    order: Vec<usize>,
    cursor: usize,
    refs: Vec<usize>,
    resident: Vec<Option<u64>>,
    oldest: Vec<usize>,
    lookahead: usize,
    retained: u64,
}
impl Schedule {
    fn new(req: &Request, lookahead: usize, retained: u64) -> Self {
        let order: Vec<_> = req
            .files
            .iter()
            .flat_map(|f| f.parts.iter().map(|p| p.chunk))
            .collect();
        let mut refs = vec![0; req.chunks.len()];
        for &c in &order {
            refs[c] += 1;
        }
        let resident: Vec<_> = req.chunks.iter().map(|c| c.cached).collect();
        let oldest = (0..resident.len())
            .filter(|&c| resident[c].is_some())
            .collect();
        Self {
            order,
            cursor: 0,
            refs,
            resident,
            oldest,
            lookahead,
            retained,
        }
    }
    fn window(&self) -> HashSet<usize> {
        self.order[self.cursor..self.order.len().min(self.cursor + self.lookahead)]
            .iter()
            .copied()
            .collect()
    }
    fn fill(&mut self, req: &Request) -> Vec<usize> {
        let mut fetch = Vec::new();
        for &c in &self.order[self.cursor..self.order.len().min(self.cursor + self.lookahead)] {
            if self.resident[c].is_none() {
                self.resident[c] = Some(req.chunks[c].size);
                self.oldest.push(c);
                fetch.push(c);
            }
        }
        fetch
    }
    fn consume(&mut self) -> Vec<usize> {
        let c = self.order[self.cursor];
        self.cursor += 1;
        self.refs[c] -= 1;
        let mut release = Vec::new();
        if self.refs[c] == 0 {
            self.resident[c] = None;
            release.push(c);
        }
        let pinned = self.window();
        let mut reusable: u64 = self
            .oldest
            .iter()
            .filter(|c| !pinned.contains(c))
            .map(|&c| self.resident[c].unwrap_or(0))
            .sum();
        for &c in &self.oldest {
            if reusable <= self.retained {
                break;
            }
            if pinned.contains(&c) {
                continue;
            }
            if let Some(bytes) = self.resident[c].take() {
                reusable -= bytes;
                release.push(c);
            }
        }
        self.oldest.retain(|&c| self.resident[c].is_some());
        release
    }
    fn bytes(&self) -> u64 {
        self.oldest
            .iter()
            .map(|&c| self.resident[c].unwrap_or(0))
            .sum()
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Budget {
    pub lookahead: usize,
    pub retained: u64,
    pub game_peak: u64,
    pub cache_peak: u64,
}
fn peaks(req: &Request, lookahead: usize, retained: u64) -> (u64, u64) {
    let mut s = Schedule::new(req, lookahead, retained);
    let initial = s.bytes() as i128;
    let mut game = 0i128;
    let mut game_peak = 0i128;
    let mut cache_peak = 0i128;
    for file in &req.files {
        for part in &file.parts {
            s.fill(req);
            let cache = s.bytes() as i128 - initial;
            cache_peak = cache_peak.max(cache);
            game_peak = game_peak.max(game + if req.same_volume { cache } else { 0 });
            game += part.size as i128;
            game_peak = game_peak.max(game + if req.same_volume { cache } else { 0 });
            s.consume();
        }
        game -= file.old as i128;
    }
    (game_peak.max(0) as u64, cache_peak.max(0) as u64)
}
fn fits(needed: u64, free: Option<u64>) -> bool {
    free.is_none_or(|n| needed <= n)
}
pub fn choose_budget(req: &Request) -> Result<Budget, String> {
    // Unknown probes deliberately choose a conservative bounded mode, never "0 free".
    let unknown = req.free_game.is_none() || (!req.same_volume && req.free_cache.is_none());
    for lookahead in [128, 64, 32, 16, 8, 4, 2, 1] {
        if unknown && lookahead > 8 {
            continue;
        }
        for retained in [RETAINED, 16 * MIB, 0] {
            let (game_peak, cache_peak) = peaks(req, lookahead, retained);
            if fits(game_peak, req.free_game)
                && (req.same_volume || fits(cache_peak, req.free_cache))
            {
                return Ok(Budget {
                    lookahead,
                    retained,
                    game_peak,
                    cache_peak,
                });
            }
        }
    }
    let (game, cache) = peaks(req, 1, 0);
    Err(format!("Not enough free space for safe staged Epic replacement: minimum game additional={game}, cache additional={cache}; free game={:?}, cache={:?}", req.free_game, req.free_cache))
}

fn remaining_peaks(
    req: &Request,
    schedule: &Schedule,
    fi: usize,
    pi: usize,
    disk_cache: u64,
) -> (u64, u64) {
    let mut s = schedule.clone();
    let mut game = 0i128;
    let mut game_peak = 0i128;
    let mut cache_peak = 0i128;
    let outstanding = s.bytes() as i128 - disk_cache as i128;
    cache_peak = cache_peak.max(outstanding);
    if req.same_volume {
        game_peak = game_peak.max(outstanding);
    }
    for (index, file) in req.files.iter().enumerate().skip(fi) {
        for part in file.parts.iter().skip(if index == fi { pi } else { 0 }) {
            s.fill(req);
            let cache = s.bytes() as i128 - disk_cache as i128;
            cache_peak = cache_peak.max(cache);
            game_peak = game_peak.max(game + if req.same_volume { cache } else { 0 });
            game += part.size as i128;
            game_peak = game_peak.max(game + if req.same_volume { cache } else { 0 });
            s.consume();
        }
        game -= file.old as i128;
    }
    (game_peak.max(0) as u64, cache_peak.max(0) as u64)
}

#[cfg(unix)]
fn available(path: &Path) -> Option<u64> {
    use std::os::unix::ffi::OsStrExt;
    let path = std::ffi::CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::statvfs(path.as_ptr(), stat.as_mut_ptr()) } != 0 {
        return None;
    }
    let stat = unsafe { stat.assume_init() };
    Some((stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64))
}
#[cfg(not(unix))]
fn available(_path: &Path) -> Option<u64> {
    None
}

/// Re-probe at admission boundaries. Already dispatched reservations cannot be withdrawn.
/// Shrink only unsent lookahead/reusable budget; never wait forever for unrelated space use.
fn live_admit(
    req: &Request,
    state: &mut State,
    fi: usize,
    pi: usize,
    stage_remaining: u64,
) -> Result<(), String> {
    let disk_cache = state
        .schedule
        .oldest
        .iter()
        .map(|&index| {
            if state.ready[index] {
                return state.disk_sizes[index];
            }
            if !state.writing[index] {
                return 0;
            }
            let c = &req.chunks[index];
            fs::metadata(req.cache.join(&c.guid))
                .map(|m| m.len())
                .unwrap_or(0)
                + fs::metadata(super::chunk::part_path(&req.cache.join(&c.guid)))
                    .map(|m| m.len())
                    .unwrap_or(0)
        })
        .sum();
    let free_game = available(&req.install);
    let free_cache = available(&req.cache);
    admit_with_probes(
        req,
        state,
        fi,
        pi,
        stage_remaining,
        disk_cache,
        free_game,
        free_cache,
    )
}

fn admit_with_probes(
    req: &Request,
    state: &mut State,
    fi: usize,
    pi: usize,
    stage_remaining: u64,
    disk_cache: u64,
    free_game: Option<u64>,
    free_cache: Option<u64>,
) -> Result<(), String> {
    loop {
        let incoming: u64 = state
            .schedule
            .window()
            .iter()
            .filter(|&&c| state.schedule.resident[c].is_none())
            .map(|&c| req.chunks[c].size)
            .sum();
        let cache_now = state
            .schedule
            .bytes()
            .saturating_add(incoming)
            .saturating_sub(disk_cache);
        let game_now = stage_remaining.saturating_add(if req.same_volume { cache_now } else { 0 });
        if fits(game_now, free_game) && (req.same_volume || fits(cache_now, free_cache)) {
            return Ok(());
        }
        // Full remaining simulation is only needed when live admission headroom is exhausted.
        let (game, cache) = remaining_peaks(req, &state.schedule, fi, pi, disk_cache);
        if fits(game, free_game) && (req.same_volume || fits(cache, free_cache)) {
            return Ok(());
        }
        if state.schedule.oldest.iter().any(|&c| !state.ready[c]) {
            return Err("Not enough free space for already reserved Epic downloads".into());
        }
        if state.schedule.retained > 0 {
            state.schedule.retained = 0;
            continue;
        }
        if state.schedule.lookahead > 1 {
            state.schedule.lookahead /= 2;
            continue;
        }
        return Err(format!("Not enough free space during Epic install: need additional game={game} cache={cache}, free game={free_game:?} cache={free_cache:?}"));
    }
}

struct State {
    schedule: Schedule,
    ready: Vec<bool>,
    writing: Vec<bool>,
    disk_sizes: Vec<u64>,
    pending: Vec<usize>,
    error: Option<String>,
    done: bool,
}
struct Shared {
    state: Mutex<State>,
    changed: Condvar,
    stop: AtomicBool,
}
struct Sink<'a> {
    req: &'a Request,
    shared: &'a Shared,
}
impl FetchSink for Sink<'_> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        if self.shared.stop.load(Ordering::Acquire) {
            return Err(SinkError::Fatal("cancelled".into()));
        }
        let c = item.id as usize;
        let chunk = &self.req.chunks[c];
        self.shared.state.lock().unwrap().writing[c] = true;
        let written = super::chunk::write_verified_chunk_bounded(
            &body,
            chunk.sha.as_ref(),
            &self.req.cache.join(&chunk.guid),
            chunk.size,
            chunk.exact,
        );
        let mut state = self.shared.state.lock().unwrap();
        state.writing[c] = false;
        let written = written.map_err(SinkError::Retry)?;
        state.ready[c] = true;
        state.disk_sizes[c] = written;
        self.shared.changed.notify_all();
        Ok(chunk.wire)
    }
}

/// JNI owns this entire blocking run. All scoped workers join before completion is signalled.
pub fn run(
    req: &Request,
    budget: Budget,
    cancel: &AtomicBool,
    before_swap: &(dyn Fn() -> Result<(), String> + Sync),
    progress: &(dyn Fn(u64, u64, u64, u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> Result<u64, String> {
    let shared = Shared {
        state: Mutex::new(State {
            schedule: Schedule::new(req, budget.lookahead, budget.retained),
            ready: req.chunks.iter().map(|c| c.cached.is_some()).collect(),
            writing: vec![false; req.chunks.len()],
            disk_sizes: req.chunks.iter().map(|c| c.cached.unwrap_or(0)).collect(),
            pending: Vec::new(),
            error: None,
            done: false,
        }),
        changed: Condvar::new(),
        stop: AtomicBool::new(false),
    };
    log(&format!(
        "adaptive-native lookahead={} retained={} game_peak={} cache_peak={} workers={}",
        budget.lookahead,
        budget.retained,
        budget.game_peak,
        budget.cache_peak,
        req.max_workers.min(budget.lookahead)
    ));
    let result = std::thread::scope(|scope| {
        scope.spawn(|| {
            crate::priority::background();
            let sink = Sink {
                req,
                shared: &shared,
            };
            loop {
                let batch = {
                    let mut state = shared.state.lock().unwrap();
                    while state.pending.is_empty()
                        && !state.done
                        && !shared.stop.load(Ordering::Acquire)
                    {
                        state = shared
                            .changed
                            .wait_timeout(state, Duration::from_millis(100))
                            .unwrap()
                            .0;
                    }
                    if state.done || shared.stop.load(Ordering::Acquire) {
                        break;
                    }
                    std::mem::take(&mut state.pending)
                };
                let items = batch
                    .into_iter()
                    .map(|c| FetchItem {
                        id: c as u64,
                        urls: req
                            .hosts
                            .iter()
                            .map(|h| format!("{h}/{}", req.chunks[c].path))
                            .collect(),
                        reserve: req.chunks[c].wire,
                        range: None,
                    })
                    .collect();
                let opts = FetchOptions {
                    max_workers: req.max_workers.min(budget.lookahead),
                    per_host_cap: super::plan::per_host_cap(
                        req.max_workers.min(budget.lookahead),
                        req.hosts.len(),
                    ),
                    timeout: Duration::from_secs(60),
                    process_workers: 2,
                    headers: vec![("User-Agent".into(), super::USER_AGENT.into())],
                    label: "epic adaptive".into(),
                    ..Default::default()
                };
                let fetched = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    run_fetch(
                        items,
                        &req.hosts,
                        &opts,
                        &sink,
                        &shared.stop,
                        &|_, _| {},
                        log,
                    )
                }));
                let outcome = match fetched {
                    Ok(outcome) => outcome,
                    Err(_) => {
                        let mut state = shared.state.lock().unwrap();
                        state.error = Some("native fetch panicked".into());
                        shared.stop.store(true, Ordering::Release);
                        shared.changed.notify_all();
                        break;
                    }
                };
                if !outcome.ok() {
                    let mut state = shared.state.lock().unwrap();
                    state.error = Some(outcome.error.unwrap_or_else(|| "cancelled".into()));
                    shared.stop.store(true, Ordering::Release);
                    shared.changed.notify_all();
                    break;
                }
            }
        });
        // Cancel forwarding is independent of a writer blocked on disk I/O.
        scope.spawn(|| {
            while !shared.stop.load(Ordering::Acquire) {
                if cancel.load(Ordering::Acquire) {
                    shared.stop.store(true, Ordering::Release);
                    shared.changed.notify_all();
                    break;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
        });
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            assemble(req, &shared, cancel, before_swap, progress)
        }))
        .unwrap_or_else(|_| Err("native assembly panicked".into()));
        shared.stop.store(true, Ordering::Release);
        {
            let mut state = shared.state.lock().unwrap();
            state.done = true;
            shared.changed.notify_all();
        }
        result
    });
    // No .part writer survives this point; preserve verified resident chunks for resume.
    let resident = shared.state.lock().unwrap().schedule.oldest.clone();
    for index in resident {
        let _ = fs::remove_file(super::chunk::part_path(
            &req.cache.join(&req.chunks[index].guid),
        ));
    }
    if result.is_ok() {
        fs::remove_dir_all(&req.cache).map_err(|e| format!("clear cache: {e}"))?;
    }
    result
}

fn await_chunk(
    req: &Request,
    shared: &Shared,
    cancel: &AtomicBool,
    c: usize,
    fi: usize,
    pi: usize,
    stage_remaining: u64,
) -> Result<(), String> {
    let mut state = shared.state.lock().unwrap();
    if cancel.load(Ordering::Acquire) {
        return Err("cancelled".into());
    }
    live_admit(req, &mut state, fi, pi, stage_remaining)?;
    let fetch = state.schedule.fill(req);
    state.pending.extend(fetch);
    shared.changed.notify_all();
    while !state.ready[c] {
        if cancel.load(Ordering::Acquire) {
            return Err("cancelled".into());
        }
        if let Some(error) = &state.error {
            return Err(error.clone());
        }
        if shared.stop.load(Ordering::Acquire) {
            return Err("cancelled".into());
        }
        state = shared
            .changed
            .wait_timeout(state, Duration::from_millis(100))
            .unwrap()
            .0;
    }
    Ok(())
}

fn assemble(
    req: &Request,
    shared: &Shared,
    cancel: &AtomicBool,
    before_swap: &(dyn Fn() -> Result<(), String> + Sync),
    progress: &(dyn Fn(u64, u64, u64, u64) + Sync),
) -> Result<u64, String> {
    let mut done = 0;
    let mut marked = false;
    let total = req.total();
    for (fi, file) in req.files.iter().enumerate() {
        Request::validate_staging(&req.install, &req.staging)?;
        if cancel.load(Ordering::Acquire) {
            return Err("cancelled".into());
        }
        let output = req.install.join(&file.path);
        fs::create_dir_all(output.parent().ok_or("missing output parent")?)
            .map_err(|e| e.to_string())?;
        let stage = req
            .staging
            .join(format!("native-{}-{fi}.stage", std::process::id()));
        let result = (|| {
            let stage_file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&stage)
                .map_err(|e| e.to_string())?;
            let mut out = BufWriter::with_capacity(64 * 1024, stage_file);
            let mut sha = Sha1::new();
            let mut written = 0;
            let file_total: u64 = file.parts.iter().map(|p| p.size).sum();
            for (pi, part) in file.parts.iter().enumerate() {
                // A live free-space probe must include already buffered staging bytes.
                out.flush().map_err(|e| e.to_string())?;
                await_chunk(
                    req,
                    shared,
                    cancel,
                    part.chunk,
                    fi,
                    pi,
                    file_total - written,
                )?;
                written += super::assemble::copy_chunk_part(
                    &req.cache.join(&req.chunks[part.chunk].guid),
                    part.offset,
                    part.size,
                    &mut out,
                    &mut sha,
                    cancel,
                )?;
                let mut state = shared.state.lock().unwrap();
                for c in state.schedule.consume() {
                    if !state.ready[c] {
                        return Err("attempt to release in-flight chunk".into());
                    }
                    fs::remove_file(req.cache.join(&req.chunks[c].guid))
                        .map_err(|e| e.to_string())?;
                    state.ready[c] = false;
                }
                done += part.size;
                progress(done, total, fi as u64, req.files.len() as u64);
            }
            out.flush().map_err(|e| e.to_string())?;
            out.get_ref().sync_all().map_err(|e| e.to_string())?;
            drop(out);
            if written != file_total {
                return Err("file size mismatch".into());
            }
            if file
                .sha
                .is_some_and(|expected| sha.finalize().as_slice() != expected)
            {
                return Err(format!("SHA-1 mismatch for {}", file.path));
            }
            if cancel.load(Ordering::Acquire) {
                return Err("cancelled".into());
            }
            if !marked {
                before_swap()?;
                marked = true;
            }
            if cancel.load(Ordering::Acquire) {
                return Err("cancelled".into());
            }
            // Android/Linux rename replaces atomically on the same output volume. Never delete first.
            fs::rename(&stage, &output)
                .map_err(|e| format!("atomic replacement {}: {e}", file.path))?;
            progress(done, total, fi as u64 + 1, req.files.len() as u64);
            Ok(())
        })();
        let _ = fs::remove_file(&stage);
        result?;
    }
    Ok(done)
}

#[cfg(test)]
mod tests {
    use super::super::chunk::test_support::{build_chunk_body, sha1_of};
    use super::*;
    use std::io::Read;
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicU64, AtomicUsize};
    use std::sync::{Arc, Barrier};
    use std::thread;
    use std::time::{Instant, SystemTime, UNIX_EPOCH};

    static SEQUENCE: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        root: PathBuf,
        req: Request,
    }
    impl Fixture {
        fn new(count: usize, size: usize) -> Self {
            let root = PathBuf::from("target").join(format!(
                "epic-adaptive-{}-{}-{}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                SEQUENCE.fetch_add(1, Ordering::Relaxed)
            ));
            let install = root.join("game");
            let cache = root.join("cache");
            let staging = install.join(".droiddeck-epic-staging");
            fs::create_dir_all(&staging).unwrap();
            fs::create_dir_all(&cache).unwrap();
            fs::write(staging.join("owner"), b"DroidDeck Epic staging v1\n").unwrap();
            let data = vec![42; size];
            let sha = sha1_of(&data);
            let chunks: Vec<_> = (0..count)
                .map(|c| Chunk {
                    guid: format!("{c:032X}"),
                    path: format!("chunks/{c}"),
                    size: size as u64,
                    wire: size as u64 + 41,
                    sha: Some(sha),
                    cached: None,
                    exact: true,
                })
                .collect();
            let files = (0..count)
                .map(|c| Output {
                    path: format!("file-{c}.bin"),
                    sha: Some(sha),
                    old: 0,
                    parts: vec![Part {
                        chunk: c,
                        offset: 0,
                        size: size as u64,
                    }],
                })
                .collect();
            Self {
                root,
                req: Request {
                    install,
                    cache,
                    staging,
                    chunks,
                    files,
                    same_volume: false,
                    free_game: Some(u64::MAX),
                    free_cache: Some(u64::MAX),
                    hosts: vec!["http://127.0.0.1:1".into()],
                    max_workers: 8,
                },
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    struct Server {
        address: String,
        requests: Arc<AtomicUsize>,
        active: Arc<AtomicUsize>,
        stop: Arc<AtomicBool>,
        worker: Option<thread::JoinHandle<()>>,
    }
    impl Server {
        fn start(data: Vec<u8>, barrier_count: usize, stalled: bool) -> Self {
            Self::start_mode(data, barrier_count, stalled.then_some(usize::MAX))
        }

        fn start_mode(data: Vec<u8>, barrier_count: usize, stall_chunk: Option<usize>) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = format!("http://{}", listener.local_addr().unwrap());
            listener.set_nonblocking(true).unwrap();
            let requests = Arc::new(AtomicUsize::new(0));
            let active = Arc::new(AtomicUsize::new(0));
            let stop = Arc::new(AtomicBool::new(false));
            let requests2 = requests.clone();
            let active2 = active.clone();
            let stop2 = stop.clone();
            let worker = thread::spawn(move || {
                let body = Arc::new(build_chunk_body(&data, false, 0, None));
                let barrier = Arc::new(Barrier::new(barrier_count.max(1)));
                let mut handlers = Vec::new();
                while !stop2.load(Ordering::Acquire) {
                    let (mut socket, _) = match listener.accept() {
                        Ok(s) => s,
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(5));
                            continue;
                        }
                        Err(e) => panic!("{e}"),
                    };
                    let body = body.clone();
                    let requests = requests2.clone();
                    let active = active2.clone();
                    let stop = stop2.clone();
                    let barrier = barrier.clone();
                    handlers.push(thread::spawn(move || {
                        active.fetch_add(1, Ordering::Relaxed);
                        let _ = socket.set_read_timeout(Some(Duration::from_secs(5)));
                        let mut request = Vec::new();
                        let mut byte = [0];
                        while socket.read(&mut byte).unwrap_or(0) > 0 {
                            request.push(byte[0]);
                            if request.ends_with(b"\r\n\r\n") {
                                break;
                            }
                        }
                        let n = requests.fetch_add(1, Ordering::Relaxed);
                        if n < barrier_count {
                            barrier.wait();
                        }
                        let _ = write!(
                            socket,
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        );
                        let stalled = stall_chunk.is_some_and(|c| {
                            c == usize::MAX
                                || String::from_utf8_lossy(&request)
                                    .contains(&format!("/chunks/{c} "))
                        });
                        if stalled {
                            // Cancellation must close the native client's connection without data.
                            let _ = socket.set_read_timeout(Some(Duration::from_millis(100)));
                            loop {
                                if stop.load(Ordering::Acquire) {
                                    break;
                                }
                                match socket.read(&mut byte) {
                                    Ok(0) => break,
                                    Ok(_) => {}
                                    Err(e)
                                        if e.kind() == std::io::ErrorKind::WouldBlock
                                            || e.kind() == std::io::ErrorKind::TimedOut => {}
                                    Err(_) => break,
                                }
                            }
                        } else {
                            let _ = socket.write_all(&body);
                        }
                        active.fetch_sub(1, Ordering::Relaxed);
                    }));
                }
                for handler in handlers {
                    handler.join().unwrap();
                }
            });
            Self {
                address,
                requests,
                active,
                stop,
                worker: Some(worker),
            }
        }
    }
    impl Drop for Server {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Release);
            if let Some(worker) = self.worker.take() {
                worker.join().unwrap();
            }
        }
    }

    #[test]
    fn situation_table_selects_large_tight_minimal_and_rejects_no_fit() {
        let mut f = Fixture::new(300, MIB as usize);
        assert_eq!(choose_budget(&f.req).unwrap().lookahead, 128);
        f.req.free_cache = Some(8 * MIB);
        assert_eq!(choose_budget(&f.req).unwrap().lookahead, 8);
        f.req.free_cache = Some(MIB);
        assert_eq!(choose_budget(&f.req).unwrap().lookahead, 1);
        f.req.free_cache = Some(MIB - 1);
        assert!(choose_budget(&f.req)
            .unwrap_err()
            .contains("Not enough free space"));
        f.req.free_cache = None;
        assert_eq!(choose_budget(&f.req).unwrap().lookahead, 8);
        f.req.free_game = Some(0);
        assert!(choose_budget(&f.req).is_err());
    }

    #[test]
    fn planner_counts_all_incoming_old_outputs_and_existing_cache_release() {
        let mut f = Fixture::new(20, 100);
        f.req.same_volume = true;
        for file in &mut f.req.files {
            file.old = 100;
        }
        assert_eq!(peaks(&f.req, 8, 0), (900, 800));
        for chunk in &mut f.req.chunks {
            chunk.cached = Some(100);
        }
        assert_eq!(peaks(&f.req, 8, 0), (100, 0));
        f.req.same_volume = false;
        assert_eq!(peaks(&f.req, 8, 0), (100, 0));
    }

    #[test]
    fn shared_references_deduplicate_and_distant_chunks_refetch_under_budget() {
        let mut f = Fixture::new(12, (40 * MIB) as usize);
        f.req.files.extend(f.req.files.clone());
        let mut schedule = Schedule::new(&f.req, 8, RETAINED);
        assert_eq!(schedule.fill(&f.req), (0..8).collect::<Vec<_>>());
        assert!(schedule.fill(&f.req).is_empty());
        assert!(schedule.consume().is_empty());
        assert_eq!(schedule.fill(&f.req), vec![8]);
        assert_eq!(schedule.consume(), vec![0]);
        let mut refetched = false;
        for _ in 2..24 {
            refetched |= schedule.fill(&f.req).contains(&0);
            assert!(schedule.bytes() <= RETAINED + 8 * 40 * MIB);
            let removed = schedule.consume();
            assert!(removed.iter().all(|c| !schedule.window().contains(c)));
        }
        assert!(refetched);
        assert_eq!(schedule.bytes(), 0);
    }

    #[test]
    fn native_fetch_overlaps_across_files_and_never_fetches_outside_window() {
        let mut f = Fixture::new(30, 4096);
        let server = Server::start(vec![42; 4096], 8, false);
        f.req.hosts = vec![server.address.clone()];
        f.req.free_cache = Some(8 * 4096);
        let budget = choose_budget(&f.req).unwrap();
        assert_eq!(budget.lookahead, 8);
        let cancel = AtomicBool::new(false);
        let swapped = AtomicUsize::new(0);
        let progress = |_, _, files, _| {
            if files == 0 {
                cancel.store(true, Ordering::Release);
            }
        };
        let result = run(
            &f.req,
            budget,
            &cancel,
            &|| {
                swapped.fetch_add(1, Ordering::Relaxed);
                Ok(())
            },
            &progress,
            &|_| {},
        );
        assert!(result.is_err());
        assert_eq!(server.requests.load(Ordering::Relaxed), 8);
        assert_eq!(swapped.load(Ordering::Relaxed), 0);
        assert_eq!(fs::read_dir(&f.req.staging).unwrap().count(), 1);
        assert!(fs::read_dir(&f.req.cache).unwrap().all(|e| !e
            .unwrap()
            .path()
            .to_string_lossy()
            .ends_with(".part")));
    }

    #[test]
    fn native_success_stages_verifies_swaps_and_reclaims_both_cache_layouts() {
        for same_volume in [false, true] {
            let mut f = Fixture::new(20, 4096);
            let server = Server::start(vec![42; 4096], 8, false);
            f.req.hosts = vec![server.address.clone()];
            f.req.same_volume = same_volume;
            if same_volume {
                f.req.cache = f.req.install.join(".chunks");
                fs::create_dir_all(&f.req.cache).unwrap();
            }
            for file in &mut f.req.files {
                fs::write(f.req.install.join(&file.path), b"old").unwrap();
                file.old = 3;
            }
            let swaps = AtomicUsize::new(0);
            let before = || {
                assert_eq!(fs::read(f.req.install.join("file-0.bin")).unwrap(), b"old");
                swaps.fetch_add(1, Ordering::Relaxed);
                Ok(())
            };
            assert_eq!(
                run(
                    &f.req,
                    choose_budget(&f.req).unwrap(),
                    &AtomicBool::new(false),
                    &before,
                    &|_, _, _, _| {},
                    &|_| {}
                )
                .unwrap(),
                20 * 4096
            );
            assert_eq!(swaps.load(Ordering::Relaxed), 1);
            assert!(!f.req.cache.exists());
            for file in &f.req.files {
                assert_eq!(
                    fs::read(f.req.install.join(&file.path)).unwrap(),
                    vec![42; 4096]
                );
            }
            assert_eq!(fs::read_dir(&f.req.staging).unwrap().count(), 1);
        }
    }

    #[test]
    fn native_file_hash_failure_and_updating_failure_keep_old_output() {
        for updating_failure in [false, true] {
            let mut f = Fixture::new(1, 4096);
            let server = Server::start(vec![42; 4096], 1, false);
            f.req.hosts = vec![server.address.clone()];
            fs::write(f.req.install.join("file-0.bin"), b"old").unwrap();
            if !updating_failure {
                f.req.files[0].sha = Some([1; 20]);
            }
            let before = || Err("updating record failed".into());
            let result = run(
                &f.req,
                choose_budget(&f.req).unwrap(),
                &AtomicBool::new(false),
                &before,
                &|_, _, _, _| {},
                &|_| {},
            );
            assert!(result.is_err());
            assert_eq!(fs::read(f.req.install.join("file-0.bin")).unwrap(), b"old");
            assert_eq!(fs::read_dir(&f.req.staging).unwrap().count(), 1);
        }
    }

    #[test]
    fn native_cancellation_closes_stalled_parallel_requests_and_joins_before_return() {
        let mut f = Fixture::new(30, 4096);
        let server = Server::start(vec![42; 4096], 8, true);
        f.req.hosts = vec![server.address.clone()];
        f.req.free_cache = Some(8 * 4096);
        let cancel = AtomicBool::new(false);
        thread::scope(|scope| {
            scope.spawn(|| {
                let until = Instant::now() + Duration::from_secs(5);
                while server.requests.load(Ordering::Relaxed) < 8 && Instant::now() < until {
                    thread::sleep(Duration::from_millis(10));
                }
                cancel.store(true, Ordering::Release);
            });
            let started = Instant::now();
            assert!(run(
                &f.req,
                choose_budget(&f.req).unwrap(),
                &cancel,
                &|| Ok(()),
                &|_, _, _, _| {},
                &|_| {}
            )
            .is_err());
            assert!(started.elapsed() < Duration::from_secs(7));
        });
        let until = Instant::now() + Duration::from_secs(2);
        while server.active.load(Ordering::Relaxed) > 0 && Instant::now() < until {
            thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(server.active.load(Ordering::Relaxed), 0);
        assert_eq!(server.requests.load(Ordering::Relaxed), 8);
        assert_eq!(fs::read_dir(&f.req.staging).unwrap().count(), 1);
        assert_eq!(fs::read_dir(&f.req.cache).unwrap().count(), 0);
    }

    #[test]
    fn native_bounded_decode_never_publishes_oversized_or_truncated_chunks() {
        let f = Fixture::new(1, 10);
        let path = f.req.cache.join("chunk");
        let body = build_chunk_body(&[42; 11], true, 0, None);
        assert!(
            super::super::chunk::write_verified_chunk_bounded(&body, None, &path, 10, true)
                .is_err()
        );
        assert!(!path.exists());
        assert!(!super::super::chunk::part_path(&path).exists());
        let body = build_chunk_body(&[42; 10], false, 0, Some(11));
        assert!(
            super::super::chunk::write_verified_chunk_bounded(&body, None, &path, 10, true)
                .is_err()
        );
        let data = vec![42; 512 * 1024];
        let mut compressed = build_chunk_body(&data, true, 0, None);
        assert_eq!(
            super::super::chunk::write_verified_chunk_bounded(
                &compressed,
                Some(&sha1_of(&data)),
                &path,
                data.len() as u64,
                true
            )
            .unwrap(),
            data.len() as u64
        );
        fs::remove_file(&path).unwrap();
        compressed.pop();
        // A matching shorter payload header leaves the missing zlib stream end to detect failure.
        let short = (compressed.len() - 41) as i32;
        compressed[12..16].copy_from_slice(&short.to_le_bytes());
        assert!(super::super::chunk::write_verified_chunk_bounded(
            &compressed,
            None,
            &path,
            data.len() as u64,
            false
        )
        .is_err());
        assert!(!path.exists());
    }

    #[test]
    fn live_admission_shrinks_unsent_work_but_never_withdraws_inflight_reservations() {
        let f = Fixture::new(30, 4096);
        let mut state = State {
            schedule: Schedule::new(&f.req, 128, RETAINED),
            ready: vec![false; 30],
            writing: vec![false; 30],
            disk_sizes: vec![0; 30],
            pending: vec![],
            error: None,
            done: false,
        };
        admit_with_probes(
            &f.req,
            &mut state,
            0,
            0,
            4096,
            0,
            Some(u64::MAX),
            Some(8 * 4096),
        )
        .unwrap();
        assert_eq!(state.schedule.lookahead, 8);
        assert_eq!(state.schedule.retained, 0);
        assert_eq!(state.schedule.fill(&f.req).len(), 8);
        assert!(admit_with_probes(
            &f.req,
            &mut state,
            0,
            0,
            4096,
            0,
            Some(u64::MAX),
            Some(4096)
        )
        .is_err());
        assert_eq!(state.schedule.lookahead, 8);
        assert!(state
            .schedule
            .oldest
            .iter()
            .all(|&c| state.schedule.resident[c].is_some()));
    }

    #[test]
    fn snapshot_schema_rejects_unowned_staging_bad_paths_and_bad_version() {
        let f = Fixture::new(1, 10);
        let mut snapshot = serde_json::json!({
            "schema_version": 1,
            "install_dir": f.req.install, "cache_dir": f.req.cache, "staging_dir": f.req.staging,
            "same_volume": false, "free_game": -1, "free_cache": 0, "max_workers": 32,
            "hosts": ["http://127.0.0.1"],
            "chunks": [{ "guid": "00000000000000000000000000000000", "path": "chunks/00/chunk",
                "size": 10, "wire_size": 51, "sha1": "", "cached": -1, "exact_size": true }],
            "files": [{ "path": "legitimate.part", "old_size": 0, "sha1": "",
                "parts": [{ "guid": "00000000000000000000000000000000", "offset": 0, "size": 10 }] }]
        });
        let parsed = Request::parse(&snapshot.to_string()).unwrap();
        assert_eq!(
            parsed.free_game,
            if parsed.same_volume { Some(0) } else { None }
        );
        assert_eq!(parsed.free_cache, Some(0));
        assert_eq!(parsed.chunks[0].cached, None);
        assert!(choose_budget(&parsed).is_err());
        snapshot["schema_version"] = 2.into();
        assert!(Request::parse(&snapshot.to_string()).is_err());
        snapshot["schema_version"] = 1.into();
        snapshot["files"][0]["path"] = "../outside".into();
        assert!(Request::parse(&snapshot.to_string()).is_err());
        snapshot["files"][0]["path"] = ".droiddeck-epic-staging/owner".into();
        assert!(Request::parse(&snapshot.to_string()).is_err());
        snapshot["files"][0]["path"] = "legitimate.part".into();
        fs::remove_file(f.req.staging.join("owner")).unwrap();
        assert!(Request::parse(&snapshot.to_string()).is_err());
    }

    #[test]
    fn native_shared_chunk_survives_cancelled_file_boundary_and_is_reused_on_resume() {
        let mut f = Fixture::new(3, 4096);
        let server = Server::start(vec![42; 4096], 1, false);
        f.req.hosts = vec![server.address.clone()];
        for file in &mut f.req.files {
            file.parts[0].chunk = 0;
        }
        let cancel = AtomicBool::new(false);
        let before = AtomicUsize::new(0);
        let progress = |_, _, files, _| {
            if files == 1 {
                cancel.store(true, Ordering::Release);
            }
        };
        assert!(run(
            &f.req,
            choose_budget(&f.req).unwrap(),
            &cancel,
            &|| {
                before.fetch_add(1, Ordering::Relaxed);
                Ok(())
            },
            &progress,
            &|_| {}
        )
        .is_err());
        assert_eq!(before.load(Ordering::Relaxed), 1);
        assert_eq!(server.requests.load(Ordering::Relaxed), 1);
        assert_eq!(
            fs::read(f.req.install.join("file-0.bin")).unwrap(),
            vec![42; 4096]
        );
        assert!(!f.req.install.join("file-1.bin").exists());
        assert!(f.req.cache.join(&f.req.chunks[0].guid).is_file());
        f.req.files.remove(0);
        f.req.chunks[0].cached = Some(4096);
        assert_eq!(
            run(
                &f.req,
                choose_budget(&f.req).unwrap(),
                &AtomicBool::new(false),
                &|| Ok(()),
                &|_, _, _, _| {},
                &|_| {}
            )
            .unwrap(),
            8192
        );
        assert_eq!(server.requests.load(Ordering::Relaxed), 1);
        assert!(!f.req.cache.exists());
    }

    #[test]
    fn native_later_hash_failure_preserves_verified_swap_and_unreplaced_old_file() {
        let mut f = Fixture::new(2, 4096);
        let server = Server::start(vec![42; 4096], 2, false);
        f.req.hosts = vec![server.address.clone()];
        for file in &mut f.req.files {
            fs::write(f.req.install.join(&file.path), b"old").unwrap();
            file.old = 3;
        }
        f.req.files[1].sha = Some([1; 20]);
        let marked = AtomicUsize::new(0);
        let error = run(
            &f.req,
            choose_budget(&f.req).unwrap(),
            &AtomicBool::new(false),
            &|| {
                marked.fetch_add(1, Ordering::Relaxed);
                Ok(())
            },
            &|_, _, _, _| {},
            &|_| {},
        )
        .unwrap_err();
        assert!(error.contains("SHA-1 mismatch"));
        assert_eq!(marked.load(Ordering::Relaxed), 1);
        assert_eq!(
            fs::read(f.req.install.join("file-0.bin")).unwrap(),
            vec![42; 4096]
        );
        assert_eq!(fs::read(f.req.install.join("file-1.bin")).unwrap(), b"old");
        assert_eq!(fs::read_dir(&f.req.staging).unwrap().count(), 1);
    }

    #[test]
    fn actual_native_file_and_cache_growth_never_exceeds_the_shared_planned_peak() {
        let mut f = Fixture::new(20, 4096);
        let server = Server::start(vec![42; 4096], 8, false);
        f.req.hosts = vec![server.address.clone()];
        f.req.same_volume = true;
        f.req.free_game = Some(21 * 4096);
        for file in &mut f.req.files {
            file.old = 4096;
            fs::write(f.req.install.join(&file.path), vec![1; 4096]).unwrap();
        }
        let budget = choose_budget(&f.req).unwrap();
        let observed = AtomicU64::new(0);
        let progress = |_, _, _, _| {
            let game: u64 = f
                .req
                .files
                .iter()
                .map(|file| fs::metadata(f.req.install.join(&file.path)).unwrap().len())
                .sum();
            let stage: u64 = fs::read_dir(&f.req.staging)
                .unwrap()
                .filter_map(Result::ok)
                .filter(|e| e.file_name() != "owner")
                .map(|e| e.metadata().map(|m| m.len()).unwrap_or(0))
                .sum();
            let cache: u64 = fs::read_dir(&f.req.cache)
                .unwrap()
                .filter_map(Result::ok)
                .map(|e| e.metadata().map(|m| m.len()).unwrap_or(0))
                .sum();
            let additional = (game + stage + cache).saturating_sub(20 * 4096);
            observed.fetch_max(additional, Ordering::Relaxed);
            assert!(
                additional <= budget.game_peak,
                "{additional} > planned {}",
                budget.game_peak
            );
        };
        assert_eq!(
            run(
                &f.req,
                budget,
                &AtomicBool::new(false),
                &|| Ok(()),
                &progress,
                &|_| {}
            )
            .unwrap(),
            20 * 4096
        );
        assert!(observed.load(Ordering::Relaxed) > 8 * 4096);
    }

    #[test]
    fn native_assembly_swaps_a_verified_file_while_a_future_chunk_is_still_fetching() {
        let mut f = Fixture::new(20, 4096);
        let server = Server::start_mode(vec![42; 4096], 8, Some(7));
        f.req.hosts = vec![server.address.clone()];
        f.req.free_cache = Some(8 * 4096);
        let cancel = AtomicBool::new(false);
        let overlap = AtomicBool::new(false);
        let progress = |_, _, files, _| {
            if files == 1 {
                assert!(server.active.load(Ordering::Relaxed) > 0);
                assert_eq!(
                    fs::read(f.req.install.join("file-0.bin")).unwrap(),
                    vec![42; 4096]
                );
                overlap.store(true, Ordering::Release);
                cancel.store(true, Ordering::Release);
            }
        };
        assert!(run(
            &f.req,
            choose_budget(&f.req).unwrap(),
            &cancel,
            &|| Ok(()),
            &progress,
            &|_| {}
        )
        .is_err());
        assert!(overlap.load(Ordering::Acquire));
        assert_eq!(server.requests.load(Ordering::Relaxed), 8);
        assert_eq!(fs::read_dir(&f.req.staging).unwrap().count(), 1);
    }

    #[cfg(unix)]
    #[test]
    fn staging_validation_rejects_symlink_namespace_owner_and_entries() {
        use std::os::unix::fs::symlink;
        let f = Fixture::new(1, 4096);
        let outside = f.root.join("outside");
        fs::create_dir(&outside).unwrap();
        fs::write(outside.join("owner"), b"DroidDeck Epic staging v1\n").unwrap();
        fs::write(outside.join("keep.stage"), b"keep").unwrap();
        fs::remove_dir_all(&f.req.staging).unwrap();
        symlink(fs::canonicalize(&outside).unwrap(), &f.req.staging).unwrap();
        assert!(Request::validate_staging(&f.req.install, &f.req.staging).is_err());
        assert!(run(
            &f.req,
            choose_budget(&f.req).unwrap(),
            &AtomicBool::new(false),
            &|| Ok(()),
            &|_, _, _, _| {},
            &|_| {}
        )
        .is_err());
        assert_eq!(fs::read(outside.join("keep.stage")).unwrap(), b"keep");
        fs::remove_file(&f.req.staging).unwrap();
        fs::create_dir(&f.req.staging).unwrap();
        symlink(
            fs::canonicalize(outside.join("owner")).unwrap(),
            f.req.staging.join("owner"),
        )
        .unwrap();
        assert!(Request::validate_staging(&f.req.install, &f.req.staging).is_err());
        fs::remove_file(f.req.staging.join("owner")).unwrap();
        fs::write(f.req.staging.join("owner"), b"DroidDeck Epic staging v1\n").unwrap();
        symlink(
            fs::canonicalize(outside.join("keep.stage")).unwrap(),
            f.req.staging.join("linked.stage"),
        )
        .unwrap();
        assert!(Request::validate_staging(&f.req.install, &f.req.staging).is_err());
        assert_eq!(fs::read(outside.join("keep.stage")).unwrap(), b"keep");
        fs::remove_file(f.req.staging.join("linked.stage")).unwrap();
    }
}
