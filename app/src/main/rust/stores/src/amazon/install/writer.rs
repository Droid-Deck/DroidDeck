use std::fs::{self, File};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};

use super::check::temp_path;
use super::{Events, ReplaceGate, Stop};
use crate::fetch_core::{distinct_host_count, run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};

const PROGRESS_EVERY_MS: u64 = 50;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);

pub struct Job {
    pub path: String,
    pub dest: PathBuf,
    pub size: u64,
    pub sha256: Option<[u8; 32]>,
    pub url: String,
}

pub struct Settings {
    pub max_workers: usize,
    pub process_workers: usize,
    pub ca_bundle: String,
    pub user_agent: String,
    pub hosts: Vec<String>,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Baseline {
    pub done_bytes: u64,
    pub done_files: u32,
    pub total_bytes: u64,
    pub total_files: u32,
}

#[derive(Debug, Default)]
pub struct Stats {
    pub bytes_written: u64,
    pub files_written: usize,
}

struct Active {
    file: File,
    tmp: PathBuf,
    hasher: Sha256,
    written: u64,
}

struct Placer<'a> {
    jobs: &'a [Job],
    slots: Vec<Mutex<Option<Active>>>,
    placed: Vec<AtomicBool>,
    events: &'a dyn Events,
    gate: &'a ReplaceGate,
    stop: &'a AtomicBool,
    failure: Mutex<Option<String>>,
    base: Baseline,
    placed_bytes: AtomicU64,
    in_flight: AtomicU64,
    shown: AtomicU64,
    bytes_written: AtomicU64,
    files_placed: AtomicUsize,
    last_emit: AtomicU64,
    started: Instant,
}

fn discard(active: Active) {
    drop(active.file);
    let _ = fs::remove_file(&active.tmp);
}

impl Placer<'_> {
    fn fail(&self, message: String) -> String {
        let mut slot = self.failure.lock().expect("failure lock");
        if slot.is_none() {
            *slot = Some(message.clone());
        }
        drop(slot);
        self.stop.store(true, Ordering::SeqCst);
        message
    }

    fn emit(&self, force: bool) {
        let now = self.started.elapsed().as_millis() as u64;
        let last = self.last_emit.load(Ordering::Relaxed);
        if !force && now < last + PROGRESS_EVERY_MS {
            return;
        }
        if !force && self.last_emit.compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed).is_err() {
            return;
        }
        self.last_emit.store(now, Ordering::Relaxed);
        let value = self.base.done_bytes
            + self.placed_bytes.load(Ordering::Relaxed)
            + self.in_flight.load(Ordering::Relaxed);
        let shown = self.shown.fetch_max(value, Ordering::Relaxed).max(value);
        self.events.progress(
            shown.min(self.base.total_bytes),
            self.base.total_bytes,
            self.base.done_files + self.files_placed.load(Ordering::Relaxed) as u32,
            self.base.total_files,
        );
    }

    fn start(&self, job: &Job) -> Result<Active, String> {
        if let Some(parent) = job.dest.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create the folder for {}: {e}", job.path))?;
        }
        let tmp = temp_path(&job.dest);
        let file = File::create(&tmp).map_err(|e| format!("Could not write {}: {e}", job.path))?;
        Ok(Active { file, tmp, hasher: Sha256::new(), written: 0 })
    }

    fn piece(&self, i: usize, offset: u64, data: &[u8]) -> Result<(), SinkError> {
        let job = &self.jobs[i];
        let mut slot = self.slots[i].lock().expect("slot lock");
        if offset == 0 {
            if let Some(old) = slot.take() {
                self.in_flight.fetch_sub(old.written, Ordering::Relaxed);
                discard(old);
            }
            *slot = Some(self.start(job).map_err(|e| SinkError::Fatal(self.fail(e)))?);
        }
        let Some(active) = slot.as_mut() else {
            return Err(SinkError::Retry(format!("{}: data arrived before the start of the file", job.path)));
        };
        if offset != active.written {
            return Err(SinkError::Retry(format!("{}: a piece arrived out of order", job.path)));
        }
        if active.written + data.len() as u64 > job.size {
            return Err(SinkError::Retry(format!("{}: the CDN sent more than the manifest size", job.path)));
        }
        active
            .file
            .write_all(data)
            .map_err(|e| SinkError::Fatal(self.fail(format!("Could not write {}: {e}", job.path))))?;
        active.hasher.update(data);
        active.written += data.len() as u64;
        self.in_flight.fetch_add(data.len() as u64, Ordering::Relaxed);
        self.bytes_written.fetch_add(data.len() as u64, Ordering::Relaxed);
        drop(slot);
        self.emit(false);
        Ok(())
    }

    fn finish(&self, i: usize) -> Result<u64, SinkError> {
        let job = &self.jobs[i];
        let taken = self.slots[i].lock().expect("slot lock").take();
        let active = match taken {
            Some(a) => a,
            None => self.start(job).map_err(|e| SinkError::Fatal(self.fail(e)))?,
        };
        self.in_flight.fetch_sub(active.written, Ordering::Relaxed);
        if active.written != job.size {
            let got = active.written;
            discard(active);
            return Err(SinkError::Retry(format!("{}: {got} bytes arrived, the manifest says {}", job.path, job.size)));
        }
        let Active { file, tmp, hasher, .. } = active;
        if let Some(expected) = job.sha256 {
            let got: [u8; 32] = hasher.finalize().into();
            if got != expected {
                drop(file);
                let _ = fs::remove_file(&tmp);
                return Err(SinkError::Retry(format!("{}: SHA-256 mismatch", job.path)));
            }
        }
        if let Err(e) = file.sync_all() {
            drop(file);
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Fatal(self.fail(format!("Could not write {}: {e}", job.path))));
        }
        drop(file);
        if self.stop.load(Ordering::SeqCst) {
            let _ = fs::remove_file(&tmp);
            return Ok(0);
        }
        if !self.gate.allow(self.events) {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Fatal(self.fail(super::REPLACE_REFUSED.into())));
        }
        if let Err(e) = fs::rename(&tmp, &job.dest) {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Fatal(self.fail(format!("Could not put {} in place: {e}", job.path))));
        }
        self.placed[i].store(true, Ordering::SeqCst);
        self.placed_bytes.fetch_add(job.size, Ordering::Relaxed);
        self.files_placed.fetch_add(1, Ordering::Relaxed);
        self.emit(true);
        Ok(0)
    }
}

struct Sink<'p, 'a>(&'p Placer<'a>);

impl FetchSink for Sink<'_, '_> {
    fn process(&self, item: &FetchItem, _body: Vec<u8>) -> Result<u64, SinkError> {
        Err(SinkError::Fatal(format!("item {} arrived whole; streaming was expected", item.id)))
    }

    fn on_chunk(&self, item: &FetchItem, offset: u64, data: &[u8]) -> Result<(), SinkError> {
        self.0.piece(item.id as usize, offset, data)
    }

    fn on_finish(&self, item: &FetchItem, _total_len: u64) -> Result<u64, SinkError> {
        self.0.finish(item.id as usize)
    }
}

pub fn worker_counts(max_workers: usize, process_workers: usize) -> (usize, usize) {
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2);
    (max_workers.clamp(1, 128), process_workers.max(cores / 2).max(1))
}

pub fn run(jobs: &[Job], settings: &Settings, base: Baseline, gate: &ReplaceGate, stop: &AtomicBool, events: &dyn Events) -> Result<Stats, Stop> {
    let placer = Placer {
        jobs,
        slots: jobs.iter().map(|_| Mutex::new(None)).collect(),
        placed: jobs.iter().map(|_| AtomicBool::new(false)).collect(),
        events,
        gate,
        stop,
        failure: Mutex::new(None),
        base,
        placed_bytes: AtomicU64::new(0),
        in_flight: AtomicU64::new(0),
        shown: AtomicU64::new(0),
        bytes_written: AtomicU64::new(0),
        files_placed: AtomicUsize::new(0),
        last_emit: AtomicU64::new(0),
        started: Instant::now(),
    };
    let (window, process) = worker_counts(settings.max_workers, settings.process_workers);
    let distinct = distinct_host_count(&settings.hosts).max(1);
    let options = FetchOptions {
        max_workers: window,
        per_host_cap: 6usize.max(window.div_ceil(distinct)),
        timeout: REQUEST_TIMEOUT,
        headers: vec![("User-Agent".to_string(), settings.user_agent.clone())],
        ca_bundle_path: settings.ca_bundle.clone(),
        process_workers: process,
        label: "amazon".to_string(),
        stream: true,
    };
    let items: Vec<FetchItem> = jobs
        .iter()
        .enumerate()
        .map(|(i, j)| FetchItem { id: i as u64, urls: vec![j.url.clone()], reserve: j.size, range: None })
        .collect();
    events.log(&format!(
        "engine files={} window={window} process_workers={process} per_host_cap={} hosts={}",
        jobs.len(),
        options.per_host_cap,
        settings.hosts.join(","),
    ));
    placer.emit(true);
    let log = |line: &str| events.log(line);
    let outcome = if items.is_empty() {
        None
    } else {
        Some(run_fetch(items, &settings.hosts, &options, &Sink(&placer), stop, &|_: u64, _: u64| {}, &log))
    };

    for slot in &placer.slots {
        if let Some(active) = slot.lock().expect("slot lock").take() {
            discard(active);
        }
    }
    let placed_all = placer.placed.iter().all(|p| p.load(Ordering::SeqCst));
    if !placed_all {
        for (job, placed) in jobs.iter().zip(&placer.placed) {
            if !placed.load(Ordering::SeqCst) {
                let _ = fs::remove_file(temp_path(&job.dest));
            }
        }
    }
    placer.emit(true);

    let failure = placer.failure.lock().expect("failure lock").take();
    let stats = Stats {
        bytes_written: placer.bytes_written.load(Ordering::Relaxed),
        files_written: placer.files_placed.load(Ordering::Relaxed),
    };
    if let Some(message) = failure {
        return Err(Stop::Failed(message));
    }
    if placed_all {
        return Ok(stats);
    }
    if let Some(error) = outcome.as_ref().and_then(|o| o.error.clone()) {
        events.log(&format!("download failed: {error}"));
        return Err(Stop::Failed(super::FILE_FAILED.into()));
    }
    if stop.load(Ordering::SeqCst) {
        return Err(Stop::Cancelled);
    }
    Err(Stop::Failed("The download ended before every file was written".into()))
}
