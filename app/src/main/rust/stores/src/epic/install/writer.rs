use std::fs::{self, File, OpenOptions};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use sha1::{Digest, Sha1};

use super::cache;
use super::schedule::{PlanPart, Schedule, NONE};
use super::{staging, Events, Stop};
use crate::epic::chunk;
use crate::epic::manifest::{Manifest, Sha1 as Hash};
use crate::fetch_core::{run_fetch_gated, FetchItem, FetchOptions, FetchSink, SinkError};

const ADMIT_AHEAD: usize = 48;
const PROGRESS_EVERY_MS: u64 = 50;
const STUCK_AFTER: Duration = Duration::from_secs(10);

pub struct EngineFile {
    pub path: String,
    pub dest: PathBuf,
    pub size: u64,
    pub sha1: Option<Hash>,
    pub replacing: bool,
    pub parts: Vec<PlanPart>,
    pub resume: Option<Resume>,
}

pub struct Resume {
    pub stage: PathBuf,
    pub kept: Vec<u64>,
}

pub struct EngineInput<'a> {
    pub manifest: &'a Manifest,
    pub files: Vec<EngineFile>,
    pub schedule: Schedule,
    pub stage_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub prefixes: Vec<String>,
    pub overlap: Option<u64>,
    pub narrow: bool,
    pub max_workers: usize,
    pub process_workers: usize,
    pub ca_bundle: String,
    pub user_agent: String,
    pub progress_base: u64,
    pub progress_total: u64,
}

#[derive(Debug, Default)]
pub struct Stats {
    pub bytes_written: u64,
    pub files_written: usize,
}

struct Slot {
    file: Option<Arc<File>>,
    stage: Option<PathBuf>,
    written: Vec<bool>,
    next_hash: usize,
    hasher: Option<Sha1>,
    left: usize,
    done: bool,
}

struct Coord {
    next_file: usize,
    open: usize,
    open_replacing: u64,
    processed: Vec<bool>,
    remaining: Vec<u32>,
    cached: Vec<bool>,
    served: Vec<bool>,
    fetch_done: bool,
}

struct Engine<'a> {
    input: &'a EngineInput<'a>,
    slots: Vec<Mutex<Slot>>,
    coord: Mutex<Coord>,
    wake: Condvar,
    admitted: AtomicUsize,
    hint: AtomicUsize,
    stop: &'a AtomicBool,
    failure: Mutex<Option<String>>,
    written: AtomicU64,
    files_done: AtomicUsize,
    last_emit: AtomicU64,
    started: Instant,
    replace_ok: Mutex<Option<bool>>,
    events: &'a dyn Events,
    total_bytes: u64,
}

enum Source<'d> {
    Memory(&'d [u8]),
    Cache(PathBuf),
}

impl<'a> Engine<'a> {
    fn fail(&self, message: String) -> String {
        let mut slot = self.failure.lock().expect("failure lock");
        if slot.is_none() {
            *slot = Some(message.clone());
        }
        drop(slot);
        self.stop.store(true, Ordering::SeqCst);
        self.wake.notify_all();
        message
    }

    fn cache_path(&self, producer: usize) -> PathBuf {
        let p = &self.input.schedule.producers[producer];
        let info = &self.input.manifest.chunks[p.chunk as usize];
        self.input.cache_dir.join(cache::entry_name(info, p.copy))
    }

    fn emit(&self, force: bool) {
        let now = self.started.elapsed().as_millis() as u64;
        let last = self.last_emit.load(Ordering::Relaxed);
        if !force && now < last + PROGRESS_EVERY_MS {
            return;
        }
        if self.last_emit.compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed).is_err() && !force {
            return;
        }
        self.events.progress(
            self.input.progress_base + self.written.load(Ordering::Relaxed),
            self.total_bytes,
            self.files_done.load(Ordering::Relaxed) as u32,
            self.input.files.len() as u32,
        );
    }

    fn admissible(&self, c: &Coord, f: usize) -> bool {
        let file = &self.input.files[f];
        if self.input.narrow {
            return c.open == 0;
        }
        if !file.replacing {
            return true;
        }
        match self.input.overlap {
            None => true,
            Some(budget) => c.open_replacing == 0 || c.open_replacing + file.size <= budget,
        }
    }

    fn admit_target(&self, fetch_done: bool) -> usize {
        let fetch = &self.input.schedule.fetch;
        if fetch_done || fetch.is_empty() {
            return self.input.files.len();
        }
        let at = (self.hint.load(Ordering::Relaxed) + ADMIT_AHEAD).min(fetch.len() - 1);
        self.input.schedule.producers[fetch[at] as usize].anchor as usize + 1
    }

    fn write_use(&self, u: usize, source: &Source<'_>) -> Result<(), String> {
        let schedule = &self.input.schedule;
        let f = schedule.use_file[u] as usize;
        let j = u - schedule.file_start[f] as usize;
        let file = &self.input.files[f];
        let part = &file.parts[j];
        let loaded;
        let data: &[u8] = match source {
            Source::Memory(d) => d,
            Source::Cache(path) => {
                loaded = fs::read(path).map_err(|e| format!("chunk cache read for {}: {e}", file.path))?;
                &loaded
            }
        };
        let start = part.chunk_offset as usize;
        let end = start + part.size as usize;
        if end > data.len() {
            return Err(format!("A chunk is too short for {}", file.path));
        }
        let piece = &data[start..end];
        let handle = self.slots[f].lock().expect("slot lock").file.clone();
        let Some(handle) = handle else {
            return Err(format!("internal: {} is not open", file.path));
        };
        handle
            .write_all_at(piece, part.file_offset)
            .map_err(|e| format!("Could not assemble or atomically replace {}: {e}", file.path))?;
        self.written.fetch_add(piece.len() as u64, Ordering::Relaxed);

        let complete = {
            let mut slot = self.slots[f].lock().expect("slot lock");
            slot.written[j] = true;
            if slot.hasher.is_some() {
                while slot.next_hash < file.parts.len() && slot.written[slot.next_hash] {
                    let k = slot.next_hash;
                    if k == j {
                        slot.hasher.as_mut().expect("hasher").update(piece);
                    } else {
                        let other = &file.parts[k];
                        let mut buf = vec![0u8; other.size as usize];
                        handle
                            .read_exact_at(&mut buf, other.file_offset)
                            .map_err(|e| format!("Could not read back {}: {e}", file.path))?;
                        slot.hasher.as_mut().expect("hasher").update(&buf);
                    }
                    slot.next_hash += 1;
                }
            }
            slot.left -= 1;
            slot.left == 0
        };

        let p = schedule.use_producer[u] as usize;
        let drop_cache = {
            let mut c = self.coord.lock().expect("coord lock");
            c.remaining[p] -= 1;
            c.remaining[p] == 0 && c.cached[p]
        };
        if drop_cache {
            let _ = fs::remove_file(self.cache_path(p));
        }
        if complete {
            self.finish_file(f)?;
        }
        self.emit(false);
        Ok(())
    }

    fn finish_file(&self, f: usize) -> Result<(), String> {
        let file = &self.input.files[f];
        let (handle, stage, hash) = {
            let mut slot = self.slots[f].lock().expect("slot lock");
            slot.done = true;
            (slot.file.take(), slot.stage.clone(), slot.hasher.take().map(|h| -> Hash { h.finalize().into() }))
        };
        let stage = stage.ok_or_else(|| format!("internal: {} has no stage file", file.path))?;
        let result = (|| -> Result<(), String> {
            let handle = handle.ok_or_else(|| format!("internal: {} is not open", file.path))?;
            handle
                .sync_all()
                .map_err(|e| format!("Could not assemble or atomically replace {}: {e}", file.path))?;
            let len = handle.metadata().map(|m| m.len()).unwrap_or(u64::MAX);
            if len != file.size {
                return Err(format!("Size mismatch for {}", file.path));
            }
            let hash = match (hash, &file.resume) {
                (None, Some(_)) if file.sha1.is_some() => Some(hash_whole(&handle, file)?),
                (hash, _) => hash,
            };
            drop(handle);
            if let (Some(expected), Some(got)) = (file.sha1, hash) {
                if expected != got {
                    return Err(format!("SHA-1 mismatch for {}", file.path));
                }
            }
            if self.stop.load(Ordering::SeqCst) {
                return Err(String::new());
            }
            {
                let mut ok = self.replace_ok.lock().expect("replace lock");
                if ok.is_none() {
                    *ok = Some(self.events.before_replace());
                }
                if *ok != Some(true) {
                    return Err("The install record could not be switched to updating; nothing was replaced".into());
                }
            }
            if let Some(parent) = file.dest.parent() {
                fs::create_dir_all(parent)
                    .map_err(|e| format!("Could not assemble or atomically replace {}: {e}", file.path))?;
            }
            fs::rename(&stage, &file.dest)
                .map_err(|e| format!("Could not assemble or atomically replace {}: {e}", file.path))
        })();
        if file.resume.is_some() {
            let _ = fs::remove_file(staging::list_path(&stage));
        }
        if let Err(e) = result {
            let _ = fs::remove_file(&stage);
            return Err(e);
        }
        {
            let mut c = self.coord.lock().expect("coord lock");
            c.open -= 1;
            if file.replacing {
                c.open_replacing = c.open_replacing.saturating_sub(file.size);
            }
        }
        self.files_done.fetch_add(1, Ordering::Relaxed);
        self.wake.notify_all();
        self.emit(true);
        Ok(())
    }

    fn open_stage(&self, f: usize) -> Result<(), String> {
        let file = &self.input.files[f];
        let (path, opened) = match &file.resume {
            Some(r) => (r.stage.clone(), OpenOptions::new().read(true).write(true).open(&r.stage)),
            None => {
                let path = self.input.stage_dir.join(staging::unique_name());
                let opened = OpenOptions::new().read(true).write(true).create_new(true).open(&path);
                (path, opened)
            }
        };
        let handle = opened.map_err(|e| format!("Could not stage {}: {e}", file.path))?;
        let mut slot = self.slots[f].lock().expect("slot lock");
        slot.file = Some(Arc::new(handle));
        slot.stage = Some(path);
        Ok(())
    }

    fn admit_loop(&self) {
        let n = self.input.files.len();
        let mut last_change = Instant::now();
        let mut last_seen = (usize::MAX, usize::MAX);
        loop {
            if self.stop.load(Ordering::SeqCst) {
                return;
            }
            let done = self.files_done.load(Ordering::Relaxed);
            if done == n {
                return;
            }
            let candidate = {
                let c = self.coord.lock().expect("coord lock");
                let target = self.admit_target(c.fetch_done);
                if c.next_file < target.min(n) && self.admissible(&c, c.next_file) {
                    Some(c.next_file)
                } else {
                    if c.fetch_done && c.next_file == n {
                        let seen = (done, self.written.load(Ordering::Relaxed) as usize);
                        if seen != last_seen {
                            last_seen = seen;
                            last_change = Instant::now();
                        } else if last_change.elapsed() > STUCK_AFTER {
                            drop(c);
                            self.fail("internal: some files never completed".into());
                            return;
                        }
                    }
                    let _ = self.wake.wait_timeout(c, Duration::from_millis(20)).expect("coord lock");
                    None
                }
            };
            let Some(f) = candidate else { continue };
            if let Err(e) = self.admit(f) {
                self.fail(e);
                return;
            }
        }
    }

    fn admit(&self, f: usize) -> Result<(), String> {
        self.open_stage(f)?;
        let schedule = &self.input.schedule;
        let file = &self.input.files[f];
        let claims: Vec<usize> = {
            let mut c = self.coord.lock().expect("coord lock");
            c.next_file += 1;
            c.open += 1;
            if file.replacing {
                c.open_replacing += file.size;
            }
            let mut out = Vec::new();
            for u in schedule.uses_of_file(f) {
                let p = schedule.use_producer[u] as usize;
                if c.processed[p] && !c.served[u] {
                    c.served[u] = true;
                    out.push(u);
                }
            }
            out
        };
        self.admitted.store(f + 1, Ordering::SeqCst);
        for u in claims {
            let p = schedule.use_producer[u] as usize;
            self.write_use(u, &Source::Cache(self.cache_path(p)))?;
        }
        if file.parts.is_empty() {
            self.finish_file(f)?;
        }
        Ok(())
    }

    fn deliver(&self, fetch_index: usize, body: Vec<u8>) -> Result<u64, SinkError> {
        if self.stop.load(Ordering::SeqCst) {
            return Ok(0);
        }
        let schedule = &self.input.schedule;
        let p = schedule.fetch[fetch_index] as usize;
        let producer = &schedule.producers[p];
        let info = &self.input.manifest.chunks[producer.chunk as usize];
        let data = chunk::decode(&body, info).map_err(SinkError::Retry)?;
        drop(body);
        let needs_cache = {
            let c = self.coord.lock().expect("coord lock");
            producer.uses.iter().any(|&u| schedule.use_file[u as usize] as usize >= c.next_file)
        };
        if needs_cache {
            cache::store(&self.input.cache_dir, &cache::entry_name(info, producer.copy), &data)
                .map_err(|e| SinkError::Fatal(self.fail(e)))?;
        }
        let claims: Vec<usize> = {
            let mut c = self.coord.lock().expect("coord lock");
            c.processed[p] = true;
            c.cached[p] = needs_cache;
            let mut out = Vec::new();
            for &u in &producer.uses {
                let u = u as usize;
                if (schedule.use_file[u] as usize) < c.next_file && !c.served[u] {
                    c.served[u] = true;
                    out.push(u);
                }
            }
            out
        };
        for u in claims {
            self.write_use(u, &Source::Memory(&data)).map_err(|e| SinkError::Fatal(self.fail(e)))?;
        }
        Ok(0)
    }
}

struct Sink<'e, 'a>(&'e Engine<'a>);

impl FetchSink for Sink<'_, '_> {
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError> {
        self.0.deliver(item.id as usize, body)
    }
}

pub fn run(input: &EngineInput<'_>, stop: &AtomicBool, events: &dyn Events) -> Result<Stats, Stop> {
    let schedule = &input.schedule;
    let total_uses = schedule.use_file.len();
    let mut remaining = vec![0u32; schedule.producers.len()];
    for &p in &schedule.use_producer {
        if p != NONE {
            remaining[p as usize] += 1;
        }
    }
    let mut processed = vec![false; schedule.producers.len()];
    let mut cached = vec![false; schedule.producers.len()];
    for (i, p) in schedule.producers.iter().enumerate() {
        if p.preloaded {
            processed[i] = true;
            cached[i] = true;
        }
    }
    let engine = Engine {
        input,
        slots: input
            .files
            .iter()
            .map(|f| {
                Mutex::new(Slot {
                    file: None,
                    stage: None,
                    written: vec![false; f.parts.len()],
                    next_hash: 0,
                    hasher: f.sha1.filter(|_| f.resume.is_none()).map(|_| Sha1::new()),
                    left: f.parts.len(),
                    done: false,
                })
            })
            .collect(),
        coord: Mutex::new(Coord {
            next_file: 0,
            open: 0,
            open_replacing: 0,
            processed,
            remaining: remaining.clone(),
            cached,
            served: vec![false; total_uses],
            fetch_done: false,
        }),
        wake: Condvar::new(),
        admitted: AtomicUsize::new(0),
        hint: AtomicUsize::new(0),
        stop,
        failure: Mutex::new(None),
        written: AtomicU64::new(0),
        files_done: AtomicUsize::new(0),
        last_emit: AtomicU64::new(0),
        started: Instant::now(),
        replace_ok: Mutex::new(None),
        events,
        total_bytes: input.progress_total,
    };
    for (i, p) in schedule.producers.iter().enumerate() {
        if p.preloaded && remaining[i] == 0 {
            let _ = fs::remove_file(engine.cache_path(i));
        }
    }

    let hosts: Vec<String> = input.prefixes.iter().map(|p| crate::epic::net::origin(p)).collect();
    let items: Vec<FetchItem> = schedule
        .fetch
        .iter()
        .enumerate()
        .map(|(i, &p)| {
            let info = &input.manifest.chunks[schedule.producers[p as usize].chunk as usize];
            let path = input.manifest.chunk_path(info);
            FetchItem {
                id: i as u64,
                urls: input.prefixes.iter().map(|prefix| format!("{prefix}/{path}")).collect(),
                reserve: info.cdn_size,
                range: None,
            }
        })
        .collect();
    let distinct = crate::fetch_core::distinct_host_count(&hosts);
    let workers = input.max_workers.clamp(1, 128);
    let options = FetchOptions {
        max_workers: workers,
        per_host_cap: 6usize.max(workers.div_ceil(distinct.max(1))),
        timeout: Duration::from_secs(60),
        headers: vec![("User-Agent".to_string(), input.user_agent.clone())],
        ca_bundle_path: input.ca_bundle.clone(),
        process_workers: input.process_workers.max(1),
        label: "epic".to_string(),
        stream: false,
    };

    events.progress(input.progress_base, engine.total_bytes, 0, input.files.len() as u32);
    let log = |line: &str| events.log(line);
    let gate = |i: usize| -> bool {
        engine.hint.fetch_max(i, Ordering::Relaxed);
        let p = schedule.fetch[i] as usize;
        let ready = (schedule.producers[p].anchor as usize) < engine.admitted.load(Ordering::SeqCst);
        if !ready {
            engine.wake.notify_all();
        }
        ready
    };
    let outcome = std::thread::scope(|scope| {
        let admitter = std::thread::Builder::new()
            .name("epic-admit".into())
            .spawn_scoped(scope, || {
                crate::priority::background();
                engine.admit_loop();
            });
        if let Err(e) = admitter {
            engine.fail(format!("could not start the install thread: {e}"));
            return None;
        }
        let outcome = if items.is_empty() {
            None
        } else {
            Some(run_fetch_gated(items, &hosts, &options, &Sink(&engine), stop, &|_: u64, _: u64| {}, &log, &gate))
        };
        {
            let mut c = engine.coord.lock().expect("coord lock");
            c.fetch_done = true;
        }
        if outcome.as_ref().is_some_and(|o| !o.ok()) {
            stop.store(true, Ordering::SeqCst);
        }
        engine.wake.notify_all();
        outcome
    });

    let failure = engine.failure.lock().expect("failure lock").take().filter(|m| !m.is_empty());
    let fetch_error = outcome.as_ref().and_then(|o| o.error.clone());
    let complete = failure.is_none() && engine.files_done.load(Ordering::Relaxed) == input.files.len();

    if !complete {
        for (file, slot) in input.files.iter().zip(&engine.slots) {
            let mut s = slot.lock().expect("slot lock");
            if !s.done {
                s.file.take();
                if let Some(stage) = s.stage.take() {
                    if failure.is_some() || !keep_partial(file, &s.written, &stage, &input.stage_dir) {
                        let _ = fs::remove_file(stage);
                    }
                }
            }
        }
        cache::remove_partials(&input.cache_dir);
    }

    let elapsed = engine.started.elapsed().as_secs_f64().max(0.001);
    let written = engine.written.load(Ordering::Relaxed);
    events.log(&format!(
        "summary mode={} files={}/{} bytes_written={written} elapsed_s={elapsed:.1} avg_mbps={:.1} \
fetches={} refetches={} peak_cache_mib={} hosts={}",
        if input.narrow { "narrow" } else { "adaptive" },
        engine.files_done.load(Ordering::Relaxed),
        input.files.len(),
        written as f64 / 1_048_576.0 / elapsed,
        schedule.fetch.len(),
        schedule.refetches,
        schedule.peak_cache / 1_048_576,
        hosts.join(","),
    ));

    if let Some(message) = failure {
        return Err(Stop::Failed(message));
    }
    if complete {
        return Ok(Stats { bytes_written: written, files_written: input.files.len() });
    }
    if let Some(message) = fetch_error {
        return Err(Stop::Failed(format!("Download failed: {}", tidy_fetch_error(&message))));
    }
    if stop.load(Ordering::SeqCst) {
        return Err(Stop::Cancelled);
    }
    Err(Stop::Failed("The download ended before every file was written".into()))
}

fn hash_whole(handle: &File, file: &EngineFile) -> Result<Hash, String> {
    let mut h = Sha1::new();
    let mut buf = vec![0u8; 1024 * 1024];
    let mut at = 0u64;
    while at < file.size {
        let n = buf.len().min((file.size - at) as usize);
        handle
            .read_exact_at(&mut buf[..n], at)
            .map_err(|e| format!("Could not read back {}: {e}", file.path))?;
        h.update(&buf[..n]);
        at += n as u64;
    }
    Ok(h.finalize().into())
}

fn keep_partial(file: &EngineFile, written: &[bool], stage: &Path, dir: &Path) -> bool {
    let mut kept: Vec<u64> = file.resume.as_ref().map(|r| r.kept.clone()).unwrap_or_default();
    kept.extend(file.parts.iter().zip(written).filter(|(_, w)| **w).map(|(p, _)| p.file_offset));
    if kept.is_empty() || file.sha1.is_none() {
        return false;
    }
    kept.sort_unstable();
    let partial = dir.join(staging::partial_name(&file.path, file.size, file.sha1));
    let list: String = kept.iter().map(|o| format!("{o}\n")).collect();
    let synced = OpenOptions::new().write(true).open(stage).and_then(|h| h.sync_all());
    if synced.is_err() || fs::rename(stage, &partial).is_err() {
        return false;
    }
    let resumed = file.resume.as_ref().is_some_and(|r| r.stage == partial);
    let temp = dir.join(staging::unique_name());
    let listed = fs::write(&temp, list).and_then(|_| fs::rename(&temp, staging::list_path(&partial))).is_ok();
    if !listed {
        let _ = fs::remove_file(&temp);
        if !resumed {
            let _ = fs::remove_file(&partial);
        }
    }
    listed || resumed
}

fn tidy_fetch_error(message: &str) -> String {
    message.strip_prefix("epic: ").unwrap_or(message).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn part(file_offset: u64, size: u32) -> PlanPart {
        PlanPart { chunk: 0, chunk_offset: 0, size, file_offset }
    }

    #[test]
    fn a_pause_keeps_every_written_part_and_lists_them() {
        let dir = std::env::temp_dir().join(format!("dd-epic-keep-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let file = EngineFile {
            path: "Game/a.pak".into(),
            dest: dir.join("a.pak"),
            size: 30,
            sha1: Some([1; 20]),
            replacing: false,
            parts: vec![part(0, 10), part(10, 10), part(20, 10)],
            resume: None,
        };
        let kept = dir.join(staging::partial_name("Game/a.pak", 30, Some([1; 20])));
        let stage = dir.join("x.stage");

        fs::write(&stage, [7u8; 30]).unwrap();
        assert!(!keep_partial(&file, &[false, false, false], &stage, &dir));
        let unhashed = EngineFile {
            path: file.path.clone(),
            dest: file.dest.clone(),
            size: 30,
            sha1: None,
            replacing: false,
            parts: file.parts.clone(),
            resume: None,
        };
        assert!(!keep_partial(&unhashed, &[true, true, true], &stage, &dir));
        assert!(stage.exists());
        assert!(keep_partial(&file, &[false, true, true], &stage, &dir));
        assert_eq!(fs::read(&kept).unwrap(), [7u8; 30]);
        assert_eq!(fs::read_to_string(staging::list_path(&kept)).unwrap(), "10\n20\n");
        assert!(!stage.exists());

        let resumed = EngineFile {
            parts: vec![part(0, 10)],
            resume: Some(Resume { stage: kept.clone(), kept: vec![20, 10] }),
            ..file
        };
        assert!(keep_partial(&resumed, &[false], &kept, &dir));
        assert_eq!(fs::read_to_string(staging::list_path(&kept)).unwrap(), "10\n20\n");
        let _ = fs::remove_dir_all(&dir);
    }
}
